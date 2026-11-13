package dev.opeope.client.room;

import dev.opeope.OpeOpeMod;
import dev.opeope.client.ClientState;
import dev.opeope.client.config.OpeSettings;
import dev.opeope.client.render.TerrainFx;
import dev.opeope.entity.BlockSet;
import dev.opeope.entity.PieceEntity;
import dev.opeope.net.Payloads;
import dev.opeope.terrain.GroundRules;
import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.particle.Particle;
import net.minecraft.client.render.Camera;
import net.minecraft.client.sound.PositionedSoundInstance;
import net.minecraft.client.sound.SoundManager;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.entity.Entity;
import net.minecraft.particle.BlockStateParticleEffect;
import net.minecraft.particle.DustParticleEffect;
import net.minecraft.particle.ParticleEffect;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.registry.tag.FluidTags;
import net.minecraft.sound.SoundEvent;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.Heightmap;
import org.jetbrains.annotations.Nullable;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

/**
 * Lo que se ve, se oye y se siente de Takt sobre el suelo (diseño §5.6, §5.7 y §6.3): partículas, sonidos y cámara de
 * cada momento que cuenta el servidor (TaktFx) y de cada corte (CutFx). Además, cada tick: los guijarros de la grieta,
 * la tierra que suelta un trozo recién arrancado, la estela de lo lanzado, el silbido de lo que pasa cerca, el rugido
 * y el chapuzón de las rocas grandes que caen, y el zumbido y el temblor de lo que llevas.
 * <p>Lo de otro jugador a más de 96 bloques de la cámara no saca nada (un impacto, hasta 160; el tuyo, siempre). La
 * cámara solo se mueve con lo propio, salvo los impactos. Al cambiar de mundo (dimensión, reaparecer) se limpia sola.
 * Los arrays de los paquetes pueden ser los del servidor (un jugador): aquí solo se leen.
 */
public final class TaktFxClient {
	private TaktFxClient() {}

	/** Cuántos de cada tipo llegaron (propios o no), en qué tick del cliente llegó el último y cuál fue. */
	public static final int[] COUNTS = new int[Payloads.TaktFx.KINDS];
	public static final int[] LAST_TICK = new int[Payloads.TaktFx.KINDS];
	public static final Payloads.TaktFx[] LAST = new Payloads.TaktFx[Payloads.TaktFx.KINDS];
	/** Cuántos rugidos de roca que cae han empezado (las pruebas lo miran). */
	public static int ROARS;

	private static final boolean LOG = Boolean.getBoolean("opeope.autotest");
	private static final double FAR = 96, FAR_IMPACT = 160;
	/** Como en RoomParticles: nada nace pegado a la cámara ni con un camino que la roce; el fogonazo, más lejos aún. */
	private static final double NEAR = 2.6, PASS = 1.8, FLASH_NEAR = 8.0;
	/** El bit de borde (columnas) o de cara al aire (celdas), la clave de columna (dx, dz) y la de celda (dx, dz, dy). */
	private static final int EDGE = 1 << 30, COLUMN = 0xFFFFF, CELL = EDGE - 1;
	private static final int MAX_SEGS = 1500, MAX_CRACKS = 8, MAX_LATER = 256, MAX_PENDING = 64, MAX_SHEDS = 32,
		MAX_TRAILS = 32, MAX_FLYBY = 3;
	private static final int SHED_TICKS = 40, TRAIL_TICKS = 80, PENDING_TICKS = 10;
	private static final int NO_TAG = Integer.MIN_VALUE;
	private static final float[] IMPACT_VOL = {0.5f, 0.8f, 1.0f, 1.0f};
	private static final double[] IMPACT_TRAUMA = {0.05, 0.22, 0.45, 0.85};
	/** La velocidad del sonido en bloques por tick (343 m/s). */
	private static final double SOUND_SPEED = 17.15;
	/** Rocas que caen: cuántas se siguen, cuántas rugen a la vez, desde qué radio, dónde se buscan y hasta dónde se siguen. */
	private static final int MAX_FALLS = 16, MAX_ROARS = 3;
	private static final double FALL_RADIUS = 2.0, FALL_SCAN = 80, FALL_KEEP = 96;
	private static final float[] EMPTY = new float[0];
	private static final int[] NONE = new int[0];

	// colores (diseño §0)
	private static final int[] SOIL = {168, 154, 130}, LANDED = {179, 166, 144}, TRAIL = {184, 171, 148},
		ZONEDUST = {176, 164, 140}, MINT = {112, 255, 214}, SNOW = {240, 245, 255}, PLANT = {120, 170, 90},
		SMOKE = {120, 112, 100};

	private static final Random RNG = new Random();

	/** Lo que pasa unos ticks más tarde; tag = el id del arranque (se tira si llega antes su DETACH o CANCEL). */
	private record Later(int due, int tag, Runnable run) {}

	/** Trabajo que necesita la pieza y aún no la tiene el cliente: soltar tierra (shed) o la estela. */
	private record Pending(int entityId, int born, boolean shed, boolean own, boolean chunk) {}

	/** El peso de una pieza, recordado por su BlockSet. */
	private record MassMemo(BlockSet set, double kg) {}

	/** La grieta de un arranque: los trozos del borde, con su punto medio, hacia fuera y cuándo llega el frente. */
	private static final class Crack {
		final boolean own, face;
		final int born, ticks;
		int n;
		double[] mid = new double[48], out = new double[48];
		int[] delay = new int[16];
		BlockState[] state = new BlockState[16];
		int[] timed = NONE;
		int next, maxDelay;
		boolean over;

		Crack(boolean own, boolean face, int born, int ticks) {
			this.own = own;
			this.face = face;
			this.born = born;
			this.ticks = ticks;
		}

		void add(double x, double y, double z, double ox, double oy, double oz, BlockState st) {
			if (n == delay.length) {
				mid = Arrays.copyOf(mid, mid.length * 2);
				out = Arrays.copyOf(out, out.length * 2);
				delay = Arrays.copyOf(delay, delay.length * 2);
				state = Arrays.copyOf(state, state.length * 2);
			}
			mid[3 * n] = x;
			mid[3 * n + 1] = y;
			mid[3 * n + 2] = z;
			out[3 * n] = ox;
			out[3 * n + 1] = oy;
			out[3 * n + 2] = oz;
			state[n] = st;
			n++;
		}

		/** Cuándo llega el frente a cada trozo (del lado del dueño al otro en 0,17 s) y qué trozos tienen guijarro con hora. */
		void finish(Payloads.TaktFx fx, boolean still) {
			for (int i = 0; i < n; i++) {
				double ang = Math.atan2(mid[3 * i + 2] - fx.z(), mid[3 * i] - fx.x());
				double th = Math.abs(Math.IEEEremainder(ang - fx.b(), 2 * Math.PI));
				delay[i] = still ? 0 : (int) Math.round(0.17 * th / Math.PI * 20);
				maxDelay = Math.max(maxDelay, delay[i]);
			}
			int k = Math.min(n, RoomParticles.count(24, own));
			timed = new int[k];
			for (int j = 0; j < k; j++) timed[j] = (int) ((long) j * n / k);
			// por hora de llegada (como mucho 24: inserción)
			for (int j = 1; j < k; j++) {
				int v = timed[j], h = j - 1;
				while (h >= 0 && delay[timed[h]] > delay[v]) {
					timed[h + 1] = timed[h];
					h--;
				}
				timed[h + 1] = v;
			}
		}
	}

	/** Una pieza recién arrancada que suelta tierra por debajo durante 2 s. */
	private static final class Shed {
		final PieceEntity pe;
		final boolean own;
		final int born;
		final double factor;
		double carry;
		boolean seen;

		Shed(PieceEntity pe, boolean own, int born) {
			this.pe = pe;
			this.own = own;
			this.born = born;
			this.factor = RoomParticles.count(1000, own) / 1000.0;
		}
	}

	/** Lo lanzado que deja estela (y quizá silba al pasar cerca). */
	private static final class Trail {
		final PieceEntity pe;
		final boolean own;
		final int born;
		@Nullable Vec3d last, last2;
		double v;
		@Nullable LoopSound fly;

		Trail(PieceEntity pe, boolean own, int born) {
			this.pe = pe;
			this.own = own;
			this.born = born;
		}

		void stopFly() {
			if (fly != null) fly.stop();
			fly = null;
		}
	}

	/** Una roca grande suelta: su velocidad (vista en el cliente), su rugido y si ya hizo el chapuzón o apartó el aire. */
	private static final class Fall {
		final PieceEntity pe;
		Vec3d last;
		double v, vy;
		boolean splashed, gusted;
		@Nullable LoopSound roar;

		Fall(PieceEntity pe) {
			this.pe = pe;
			this.last = pe.getPos();
		}

		void stopRoar() {
			if (roar != null) roar.stop();
			roar = null;
		}
	}

	private static int ticks;
	@Nullable private static ClientWorld lastWorld;
	private static boolean warned;
	private static long lastDenyAt;
	private static final List<Later> LATER = new ArrayList<>();
	private static final List<Pending> PENDING = new ArrayList<>();
	private static final LinkedHashMap<Integer, Crack> CRACKS = new LinkedHashMap<>();
	private static final List<Shed> SHEDS = new ArrayList<>();
	private static final List<Trail> TRAILS = new ArrayList<>();
	private static final Map<Integer, Fall> FALLS = new HashMap<>();
	/** Por dueño: los agarres en grupo seguidos (cuántos, tick del último) y las llegadas a casa desde su último REWIND. */
	private static final Map<UUID, int[]> CHORDS = new HashMap<>();
	private static final Map<UUID, Integer> HOMES = new HashMap<>();
	// lo que llevas: el peso de cada pieza y desde qué tick la llevas
	private static final Map<Integer, MassMemo> MASS = new HashMap<>();
	private static final Map<Integer, Integer> HELD_SINCE = new HashMap<>();
	@Nullable private static LoopSound hold;
	private static int holdRetry;

	// ------------------------------------------------------------------ lo público

	/** Un momento de Takt que cuenta el servidor. */
	public static void receive(Payloads.TaktFx fx) {
		if (fx == null) return;
		int k = fx.kind();
		if (k < 0 || k >= Payloads.TaktFx.KINDS) return;
		COUNTS[k]++;
		LAST_TICK[k] = RoomFxClient.clientTicks();
		LAST[k] = fx;
		if (LOG) {
			OpeOpeMod.LOG.info(String.format(Locale.ROOT, "AUTOTEST takt fx %s id=%d pieza=%d a=%.2f b=%.2f ticks=%d flags=%d estado=%d datos=%d",
				fx.name(), fx.id(), fx.entityId(), fx.a(), fx.b(), fx.ticks(), fx.flags(), fx.state(), fx.data() == null ? 0 : fx.data().length));
		}
		MinecraftClient mc = MinecraftClient.getInstance();
		checkWorld(mc);
		try {
			TerrainFx.onTaktFx(fx);
		} catch (RuntimeException e) {
			warn(e);
		}
		if (mc.world == null || mc.player == null) return;
		try {
			dispatch(mc, fx);
		} catch (RuntimeException e) {
			warn(e);
		}
	}

	/** Un corte (lo pasa CutFx con la costura que construyó, 9 floats por trozo: a, b y la normal de la cara). */
	public static void onCut(Payloads.CutFx fx, float[] seam) {
		MinecraftClient mc = MinecraftClient.getInstance();
		if (fx == null || mc.world == null || mc.player == null) return;
		checkWorld(mc);
		try {
			cut(mc, fx, seam == null ? EMPTY : seam);
		} catch (RuntimeException e) {
			warn(e);
		}
	}

	public static void tick(MinecraftClient mc) {
		if (mc == null) return;
		checkWorld(mc);
		if (mc.world == null || mc.player == null || mc.isPaused()) return;
		ticks++;
		try {
			runLater();
			tickCracks();
			tickPending(mc);
			tickSheds();
			tickTrails();
			tickFalls(mc);
			tickFlybys(mc);
			tickHold(mc);
		} catch (RuntimeException e) {
			warn(e);
		}
	}

	/** Fuera bucles, colas, grietas y estelas. Los contadores siguen (las pruebas comparan contra ellos). */
	public static void clear() {
		if (hold != null) hold.stop();
		hold = null;
		holdRetry = 0;
		for (Trail t : TRAILS) t.stopFly();
		TRAILS.clear();
		for (Fall f : FALLS.values()) f.stopRoar();
		FALLS.clear();
		SHEDS.clear();
		PENDING.clear();
		LATER.clear();
		CRACKS.clear();
		CHORDS.clear();
		HOMES.clear();
		MASS.clear();
		HELD_SINCE.clear();
		CameraFx.traumaFloor("takt", 0f);
	}

	// ------------------------------------------------------------------ los momentos

	private static void dispatch(MinecraftClient mc, Payloads.TaktFx fx) {
		boolean own = fx.owner() != null && fx.owner().equals(mc.player.getUuid());
		double dist = Math.sqrt(cameraPos(mc).squaredDistanceTo(fx.pos()));
		boolean near = own || dist <= FAR;
		switch (fx.kind()) {
			case Payloads.TaktFx.TEAR_START -> {
				if (near) tearStart(mc.world, fx, own);
			}
			case Payloads.TaktFx.DETACH -> detach(mc, fx, own, near);
			case Payloads.TaktFx.CANCEL -> cancel(fx, own, near);
			case Payloads.TaktFx.GRAB -> grab(fx, own, near);
			case Payloads.TaktFx.ZONE -> {
				if (near) zone(fx, own);
			}
			case Payloads.TaktFx.SWEEP -> {
				if (near) sweep(fx, own);
			}
			case Payloads.TaktFx.THROW -> {
				if (near) launch(mc, fx, own);
			}
			case Payloads.TaktFx.LAND -> {
				if (near) land(mc.world, fx, own);
			}
			case Payloads.TaktFx.SETTLE -> {
				if (near) settle(mc, fx, own);
			}
			case Payloads.TaktFx.IMPACT -> {
				if (dist <= FAR_IMPACT || own) impact(mc, fx, own, dist);
			}
			case Payloads.TaktFx.REWIND -> rewind(fx, own, near);
			case Payloads.TaktFx.HOME -> home(fx, own, near);
			case Payloads.TaktFx.DENY -> deny(mc, own);
			default -> {
			}
		}
	}

	/** Se agrieta el contorno: guijarros con el frente, el crujido y el tirón; un segundo crujido si la espera es larga. */
	private static void tearStart(ClientWorld w, Payloads.TaktFx fx, boolean own) {
		Crack c = crack(w, fx, own);
		CRACKS.remove(fx.id());
		CRACKS.put(fx.id(), c);
		while (CRACKS.size() > MAX_CRACKS) CRACKS.remove(CRACKS.keySet().iterator().next());
		firePebbles(c, 0);
		Vec3d at = fx.pos();
		float rate = TaktSounds.massRate(2000.0 * fx.state());
		TaktSounds.ownOr(own, "takt.crack", at, rate, 0.9f, 0);
		TaktSounds.ownOr(own, "takt.tear", at, 0.7f, 0.8f, 1);
		if (own) TaktSounds.own("takt.reach", 1.4f, 0.35f, 0);
		if (fx.ticks() >= 6) later(fx.ticks() - 2, fx.id(), () -> TaktSounds.ownOr(own, "takt.crack", at, 0.85f * rate, 0.6f, 0));
		if (own) CameraFx.kick(0.8f);
	}

	/** Se suelta: retumbo, dos ráfagas de polvo y piedras desde el borde, y la pieza empieza a soltar tierra. */
	private static void detach(MinecraftClient mc, Payloads.TaktFx fx, boolean own, boolean near) {
		Crack c = CRACKS.remove(fx.id());
		dropTag(fx.id());
		if (!near) return;
		float m = fx.b();
		float rate = TaktSounds.massRate(1000.0 * m);
		Vec3d at = fx.pos();
		TaktSounds.ownOr(own, "takt.rumble", at, rate, TaktSounds.group(fx.state()), 0);
		TaktSounds.ownOr(own, "takt.tear", at, 0.6f, 0.9f, 0);
		TaktSounds.ownOr(own, "takt.grab_low", at, 0.8f * rate, 0.6f, 2);
		if (own) {
			CameraFx.kick((float) Math.min(2.9, 0.8 + 2.1 * Math.min(1.0, m / 32.0)));
			CameraFx.trauma((float) Math.min(0.45, 0.15 + m / 100.0));
		}
		int edges = c != null && c.n > 0 ? c.n : (int) Math.round(2 * Math.PI * Math.max(0.5, fx.a()));
		int puffs = RoomParticles.count(MathHelper.clamp(edges / 2, 12, 60), own);
		int rocks = RoomParticles.count(MathHelper.clamp((int) Math.round(4 * fx.a()), 8, 40), own);
		int[] data = fx.data() == null ? NONE : fx.data();
		BlockState rock = data.length > 0 ? Block.getStateFromRawId(data[0]) : null;
		int p1 = (puffs + 1) / 2, r1 = (rocks + 1) / 2;
		burst(c, fx, p1, r1, rock);
		later(2, NO_TAG, () -> burst(c, fx, puffs - p1, rocks - r1, rock));
		pending(mc, fx.entityId(), true, own, true);
	}

	/** Deshecho o fallido: un desgarro corto y algo de polvo en la grieta. */
	private static void cancel(Payloads.TaktFx fx, boolean own, boolean near) {
		Crack c = CRACKS.remove(fx.id());
		dropTag(fx.id());
		if (!near) return;
		TaktSounds.point("takt.tear", fx.pos(), 1.2f, 0.5f, 0);
		if (c == null || c.n == 0) return;
		DustParticleEffect d = dust(SOIL, 1.4f);
		for (int i = RoomParticles.count(10, own); i > 0; i--) {
			int s = RNG.nextInt(c.n);
			add(d, c.mid[3 * s], c.mid[3 * s + 1] + 0.1, c.mid[3 * s + 2], 0.02 * rnd(), 0.03, 0.02 * rnd());
		}
	}

	/** Coger: el tono del agarre (o la nota del acorde si viene de una zona) y unas chispas en anillo. */
	private static void grab(Payloads.TaktFx fx, boolean own, boolean near) {
		boolean area = (fx.flags() & Payloads.TaktFx.F_AREA) != 0;
		int i = area ? chordIndex(fx.owner()) : 0;
		if (!near) return;
		Vec3d at = fx.pos();
		if (area) {
			TaktSounds.ownOr(own, "takt.chord", at, Math.min(2f, TaktSounds.CHORD[i % 4] * (i >= 4 ? 2 : 1)), 0.35f, 0);
		} else {
			TaktSounds.ownOr(own, "takt.grab", at, 1.7f, 0.8f, 0);
			if (fx.b() > 2) TaktSounds.ownOr(own, "takt.grab_low", at, TaktSounds.massRate(1000.0 * fx.b()), 0.5f, 0);
		}
		int n = RoomParticles.count(area ? 1 : 4, own);
		double r = Math.min(fx.a(), 3.0), phase = RNG.nextDouble() * 2 * Math.PI;
		for (int j = 0; j < n; j++) {
			double a = phase + 2 * Math.PI * j / n;
			add(ParticleTypes.END_ROD, fx.x() + Math.cos(a) * r, fx.y(), fx.z() + Math.sin(a) * r, 0, 0, 0);
		}
		if (own && !area && fx.b() > 0) CameraFx.kick((float) (0.25 * Math.min(1.0, fx.b() / 8.0)));
	}

	/** Zona (R R, R + E): el lanzamiento del hechizo y, 7 ticks después, un anillo de polvo en el suelo. */
	private static void zone(Payloads.TaktFx fx, boolean own) {
		Vec3d at = fx.pos();
		TaktSounds.ownOr(own, "takt.zone", at, 1.2f, 0.6f, 0);
		TaktSounds.ownOr(own, "takt.reach", at, 1.6f, 0.3f, 0);
		if (own) {
			CameraFx.kick(1.6f);
			CameraFx.fov(-3f, 0.08f, 0.5f);
		}
		later(7, NO_TAG, () -> {
			DustParticleEffect d = dust(ZONEDUST, 1.6f);
			int n = RoomParticles.count(24, own);
			double r = Math.max(0.5, fx.a()), phase = RNG.nextDouble() * 2 * Math.PI;
			for (int j = 0; j < n; j++) {
				double a = phase + 2 * Math.PI * j / n;
				add(d, fx.x() + Math.cos(a) * r, fx.y() + 0.1, fx.z() + Math.sin(a) * r, 0, 0.03, 0);
			}
		});
	}

	/** Lo cortado (R + C): como la zona, más suave y sin polvo. */
	private static void sweep(Payloads.TaktFx fx, boolean own) {
		Vec3d at = fx.pos();
		TaktSounds.ownOr(own, "takt.zone", at, 0.9f, 0.5f, 0);
		TaktSounds.ownOr(own, "takt.reach", at, 1.2f, 0.3f, 0);
		if (own) {
			CameraFx.kick(1.0f);
			CameraFx.fov(-2f, 0.08f, 0.5f);
		}
	}

	/** Lanzar: el golpe de aire (con retraso según su sitio en la ráfaga), el empujón de cámara y la estela. */
	private static void launch(MinecraftClient mc, Payloads.TaktFx fx, boolean own) {
		int index = fx.state();
		Vec3d at = fx.pos();
		float m = fx.b();
		boolean big = (fx.flags() & Payloads.TaktFx.F_BIG) != 0;
		if (index >= 0 && index < 4) {
			float rate = TaktSounds.massRate(1000.0 * m);
			if (big || m > 20) {
				TaktSounds.ownOr(own, "takt.throw_big", at, 0.8f, 0.8f, index);
				TaktSounds.ownOr(own, "takt.throw", at, 0.8f * rate, 0.7f, index);
			} else {
				TaktSounds.ownOr(own, "takt.throw", at, 1.1f * rate, 0.9f, index);
			}
		}
		if (own && index >= 0 && index < 3) {
			float s = index == 0 ? 1f : (index % 2 == 0 ? 0.5f : -0.5f);
			CameraFx.kick(-2.3f, -1.03f * s, 0.77f * s);
			if ((fx.flags() & (Payloads.TaktFx.F_CHUNK | Payloads.TaktFx.F_ALL)) != 0) CameraFx.fov(5f, 0.08f, 0.35f);
			CameraFx.trauma(big ? 0.27f : 0.18f);
		}
		pending(mc, fx.entityId(), false, own, (fx.flags() & Payloads.TaktFx.F_CHUNK) != 0);
	}

	/** Posado en su silueta: el asiento, el sello y polvo por fuera del borde de la huella. */
	private static void land(ClientWorld w, Payloads.TaktFx fx, boolean own) {
		int[] data = fx.data() == null ? NONE : fx.data();
		int bx = MathHelper.floor(fx.x()), by = MathHelper.floor(fx.y()), bz = MathHelper.floor(fx.z());
		Vec3d at = fx.pos();
		if (data.length > 0) {
			double sx = 0, sy = 0, sz = 0;
			for (int p : data) {
				sx += bx + Payloads.TaktFx.dx(p) + 0.5;
				sy += by + Payloads.TaktFx.dy(p);
				sz += bz + Payloads.TaktFx.dz(p) + 0.5;
			}
			at = new Vec3d(sx / data.length, sy / data.length, sz / data.length);
		}
		TaktSounds.point("takt.land", at, TaktSounds.massRate(1000.0 * fx.b()), 0.9f, 0);
		TaktSounds.point("takt.seal", at, 1.0f, 0.6f, 2);
		if (own) {
			double k = Math.min(1.0, fx.b() / 16.0);
			CameraFx.kick((float) (0.6 * k));
			CameraFx.trauma((float) (0.1 * k));
		}
		landDust(w, data, bx, by, bz, own);
	}

	/** Por cada celda del borde de la huella (como mucho 60, repartidas): polvo hacia fuera y, una sí y otra no, tierra. */
	private static void landDust(ClientWorld w, int[] data, int bx, int by, int bz, boolean own) {
		IntOpenHashSet foot = new IntOpenHashSet();
		for (int p : data) foot.add(p & COLUMN);
		List<int[]> edge = new ArrayList<>();
		for (int p : data) {
			int dx = Payloads.TaktFx.dx(p), dz = Payloads.TaktFx.dz(p);
			for (Direction d : Direction.values()) {
				if (d.getOffsetY() != 0) continue;
				if (foot.contains(Payloads.TaktFx.pack(dx + d.getOffsetX(), dz + d.getOffsetZ(), 0) & COLUMN)) continue;
				edge.add(new int[]{p, d.getOffsetX(), d.getOffsetZ()});
				break;
			}
		}
		if (edge.isEmpty()) return;
		int max = Math.min(edge.size(), RoomParticles.count(60, own));
		DustParticleEffect d = dust(LANDED, 2.0f);
		BlockPos.Mutable m = new BlockPos.Mutable();
		for (int j = 0; j < max; j++) {
			int[] e = edge.get((int) ((long) j * edge.size() / max));
			int x = bx + Payloads.TaktFx.dx(e[0]), y = by + Payloads.TaktFx.dy(e[0]), z = bz + Payloads.TaktFx.dz(e[0]);
			double ox = e[1], oz = e[2];
			double px = x + 0.5 + ox * 0.6, py = y + 0.1, pz = z + 0.5 + oz * 0.6;
			add(d, px, py, pz, ox * 0.06, 0.01, oz * 0.06);
			if ((j & 1) != 0) continue;
			BlockState st = w.getBlockState(m.set(x, y, z));
			if (st.isAir()) st = w.getBlockState(m.set(x, y - 1, z));
			if (st.isAir()) continue;
			add(new BlockStateParticleEffect(ParticleTypes.BLOCK, st), px, py + 0.1, pz,
				ox * 0.08 + 0.02 * rnd(), 0.12 + 0.05 * rnd(), oz * 0.08 + 0.02 * rnd());
		}
	}

	/** Asentado: sellado, un timbre y chispas dentro; si no, solo el asiento (el servidor pone sus partículas de siempre). */
	private static void settle(MinecraftClient mc, Payloads.TaktFx fx, boolean own) {
		Vec3d at = fx.pos();
		if ((fx.flags() & Payloads.TaktFx.F_SEALED) == 0) {
			// lo pequeño (los terrones de un cráter) suena más bajo: no tapa el golpe
			TaktSounds.point("takt.land", at, TaktSounds.massRate(1000.0 * fx.b()), 0.35f + 0.35f * Math.min(1f, fx.b() / 20f), 0);
			return;
		}
		TaktSounds.point("takt.seal", at, 1.2f, 0.5f, 0);
		Entity e = mc.world.getEntityById(fx.entityId());
		double h = Math.max(0.25, 0.5 * fx.a());
		Box b = e instanceof PieceEntity ? e.getBoundingBox() : new Box(at.x - h, at.y - h, at.z - h, at.x + h, at.y + h, at.z + h);
		for (int i = RoomParticles.count(8, own); i > 0; i--) {
			add(ParticleTypes.END_ROD, b.minX + RNG.nextDouble() * b.getLengthX(), b.minY + RNG.nextDouble() * b.getLengthY(),
				b.minZ + RNG.nextDouble() * b.getLengthZ(), 0.01 * rnd(), 0.01 * rnd(), 0.01 * rnd());
		}
	}

	/**
	 * Lo que cuenta un IMPACT, ya leído: dónde, nivel, fuerza dentro del nivel (k, 0..1), tamaño de la roca (r y sc = r/3
	 * entre 0,5 y 4), radio del cráter (0 sin cráter), arado y hacia dónde iba, el suelo y su color de polvo, el peso, si
	 * es tuyo y a qué distancia está la cámara.
	 */
	private record Hit(Vec3d c, int tier, double k, double r, double sc, double crater, double plow, Vec3d dir,
					   BlockState ground, int[] color, double kg, boolean own, double dist) {}

	/**
	 * Impacto. Todo crece con el tamaño de la roca y la fuerza del golpe, y baja con la distancia. Como en la realidad, de
	 * lejos primero tiembla el suelo y después llega el estruendo (el sonido va a 343 m/s): el golpe seco, la explosión
	 * sorda, el retumbo largo, el eco y la lluvia de terrones. Y la nube: polvo que corre por el suelo, una cúpula,
	 * columnas de tierra en el borde, terrones en arco, humo que sube y polvo que vuelve a caer.
	 */
	private static void impact(MinecraftClient mc, Payloads.TaktFx fx, boolean own, double dist) {
		int tier = MathHelper.clamp(fx.state(), 0, 3);
		Vec3d c = fx.pos();
		double r = Math.max(0.5, fx.datum(Payloads.TaktFx.I_RADIUS, 100) / 100.0);
		double k = MathHelper.clamp(fx.datum(Payloads.TaktFx.I_K, 500) / 1000.0, 0, 1);
		Vec3d dir = new Vec3d(fx.datum(Payloads.TaktFx.I_DIRX, 0) / 1000.0, 0, fx.datum(Payloads.TaktFx.I_DIRZ, 0) / 1000.0);
		BlockState ground = Block.getStateFromRawId(fx.datum(Payloads.TaktFx.I_GROUND, 0));
		if (ground.isAir() || !ground.getFluidState().isEmpty()) ground = Blocks.DIRT.getDefaultState();
		Entity e = mc.world.getEntityById(fx.entityId());
		double kg = e instanceof PieceEntity pe ? massOf(pe) : 3000 * r * r * r;
		Hit h = new Hit(c, tier, k, r, MathHelper.clamp(r / 3, 0.5, 4), Math.max(0, fx.a()),
			Math.max(0, fx.datum(Payloads.TaktFx.I_PLOW, 0) / 100.0), dir, ground,
			dustColor(mc.world, ground, BlockPos.ofFloored(c.x, c.y - 0.5, c.z)), kg, own, dist);
		int delay = soundDelay(dist);
		quake(h, delay);
		impactSounds(h, delay);
		if (dist <= FAR_IMPACT + h.crater()) impactParticles(h);
	}

	/** Los ticks que tarda el ruido en llegar (343 m/s: 17 bloques por tick); en los primeros 4 bloques, ninguno. */
	private static int soundDelay(double dist) {
		return (int) Math.round(Math.max(0.0, dist - 4.0) / SOUND_SPEED);
	}

	/**
	 * El suelo tiembla: por la roca la onda va mucho más rápida que por el aire, así que llega enseguida. Temblor (más
	 * fuerte y más lejos cuanto más grande), de cerca un golpe hacia abajo y el campo de visión, y un retumbo que sigue un
	 * segundo. Cuando llega el estruendo, la onda del aire da otro empujón.
	 */
	private static void quake(Hit h, int delay) {
		int tier = h.tier();
		double range = (18 + 22 * tier) * (0.8 + 0.3 * Math.sqrt(h.sc()));
		double fall = MathHelper.clamp(1 - h.dist() / range, 0, 1);
		if (fall <= 0) return;
		double amp = IMPACT_TRAUMA[tier] * (0.8 + 0.4 * h.k());
		CameraFx.trauma((float) Math.min(0.9, amp * fall * fall + 0.04 * fall));
		double near = MathHelper.clamp(1 - h.dist() / (40.0 + 30 * tier), 0, 1);
		if (tier >= 2 && near > 0) {
			CameraFx.kick((float) ((0.6 + 0.8 * tier) * near), 0f, (float) (rnd() * 1.2 * near));
			CameraFx.fov((float) ((tier == 3 ? 5.0 : 2.5) * near), 0.07f, 0.55f);
		}
		if (tier == 3 && h.dist() <= 40) ScreenFx.flash((float) (0.05 + 0.07 * (1 - h.dist() / 40)), 0.2f);
		if (tier < 2) return;
		// el retumbo: sigue temblando un rato, cada vez menos
		for (int i = 1, n = tier == 3 ? 4 : 2; i <= n; i++) {
			float add = (float) (0.2 * amp * fall / i);
			later(5 * i, NO_TAG, () -> CameraFx.trauma(add));
		}
		// la onda del aire llega con el estruendo (de cerca ya llegó con el golpe)
		if (delay >= 2) {
			float push = (float) (0.4 * tier * fall), shake = (float) (0.15 * amp * fall);
			later(delay, NO_TAG, () -> {
				CameraFx.kick(-0.5f * push, 0f, (float) (rnd() * push));
				CameraFx.trauma(shake);
			});
		}
	}

	/**
	 * Las capas del estruendo, cada una con su alcance (un golpe colosal se oye a cientos de bloques; uno pequeño, cerca):
	 * el golpe seco, la tierra que cruje al abrirse el cráter (y al ararla), la explosión sorda, el retumbo largo (nivel 3,
	 * o 2 con una roca grande), el eco que vuelve de otro lado y los terrones que llueven alrededor.
	 */
	private static void impactSounds(Hit h, int delay) {
		Vec3d c = h.c();
		int tier = h.tier();
		double sc = h.sc(), k = h.k(), d = h.dist();
		float rate = TaktSounds.massRate(h.kg()) * (tier >= 2 ? 0.8f : 1f);
		double nearReach = (16 + 10 * tier) * (0.8 + 0.2 * sc);
		heard("takt.impact", c, rate, IMPACT_VOL[tier] * fade(d, nearReach, 1.0), delay);
		if (h.crater() > 0) {
			heard("takt.rumble", c, (float) (0.6 + 0.15 * (1 - k)), 0.7 * fade(d, nearReach, 1.0), delay + 1);
			if (h.plow() >= 1) heard("takt.rumble", c.add(h.dir().multiply(h.plow())), 0.5f, 0.8 * fade(d, nearReach, 1.0), delay + 4);
		}
		if (tier < 2) return;
		double farReach = (40 + 35 * tier) * (0.8 + 0.25 * sc) * (0.85 + 0.3 * k);
		float boom = (float) MathHelper.clamp(1.0 - 0.12 * tier - 0.08 * k - 0.05 * sc, 0.5, 1.0);
		double far = fade(d, farReach, 0.8);
		heard("takt.impact_far", c, boom, far, delay);
		if (tier == 3 || sc >= 1.5) {
			heard("takt.rumble_far", c, (float) MathHelper.clamp(0.72 - 0.04 * sc, 0.5, 0.72), 0.85 * fade(d, farReach * 1.25, 0.7), delay + 3);
		}
		// el eco: vuelve de lejos y de otro lado
		if (d >= 12) {
			double a = RNG.nextDouble() * 2 * Math.PI;
			heard("takt.impact_far", c.add(Math.cos(a) * 60, 6, Math.sin(a) * 60), boom * 0.9f, 0.3 * far, delay + 9 + RNG.nextInt(4));
			if (tier == 3) {
				heard("takt.impact_far", c.add(-Math.cos(a) * 80, 8, -Math.sin(a) * 80), boom * 0.85f, 0.15 * far, delay + 22 + RNG.nextInt(5));
			}
		}
		// lo que salió volando vuelve a caer alrededor
		if (h.crater() > 0) {
			double reach = 28 + 8 * sc;
			for (int i = 3 + 2 * tier + (int) Math.round(sc); i > 0; i--) {
				double a = RNG.nextDouble() * 2 * Math.PI, rho = h.crater() * range(1.1, 2.4) + 1;
				Vec3d p = c.add(Math.cos(a) * rho, 0.5, Math.sin(a) * rho);
				heard("takt.debris", p, (float) range(0.8, 1.1), 0.55 * fade(cameraDist(p), reach, 1.0), delay + 10 + RNG.nextInt(25));
			}
		}
	}

	/**
	 * La nube del golpe, con el tamaño de la roca (q = √sc) y el del cráter (R): polvo que corre por el suelo frenándose,
	 * columnas de tierra en el borde, terrones en arco (hacia delante si iba rasante), una cúpula de polvo, nubes de polvo
	 * en el borde, humo que sube (nivel 3, o 2 con una roca grande), una bruma baja, ráfagas de aire y polvo que vuelve a
	 * caer. Lo de otro sale a la mitad, salvo lo colosal.
	 */
	private static void impactParticles(Hit h) {
		Vec3d c = h.c();
		int tier = h.tier();
		double q = Math.sqrt(h.sc()), k = h.k();
		double R = Math.max(h.crater(), 0.6 * h.r() + 0.4);
		boolean own = h.own() || tier == 3;
		int[] col = h.color();
		// el aire que aparta la roca: polvo pegado al suelo que sale rápido y se frena
		DustParticleEffect low = dust(col, 3.5f);
		double surge = (0.25 + 0.3 * k) * (0.6 + 0.4 * q) * (tier >= 2 ? 1.0 : 0.6);
		for (int i = n((10 + 10 * tier) * q, own); i > 0; i--) {
			double a = RNG.nextDouble() * 2 * Math.PI, s = surge * range(0.6, 1.0);
			Vec3d p = around(h, a, R * range(0.6, 1.0));
			add(low, p.x, c.y + range(0.2, 0.8), p.z, Math.cos(a) * s, range(0.005, 0.03), Math.sin(a) * s);
		}
		if (tier >= 1) {
			// columnas de tierra en el borde (como las del mazo, del tamaño del cráter)
			BlockStateParticleEffect pillar = new BlockStateParticleEffect(ParticleTypes.DUST_PILLAR, h.ground());
			double up = Math.min(1.1, 0.35 + 0.15 * tier + 0.1 * q);
			for (int i = n(6 + 4 * R + 6 * tier, own); i > 0; i--) {
				double a = RNG.nextDouble() * 2 * Math.PI, s = range(0.03, 0.12);
				Vec3d p = around(h, a, R * range(0.85, 1.15));
				add(pillar, p.x, c.y + 0.1, p.z, Math.cos(a) * s, up * range(0.6, 1.0), Math.sin(a) * s);
			}
			// terrones en arco: salen del cráter y caen alrededor (la mitad hacia delante si araba)
			BlockStateParticleEffect clod = new BlockStateParticleEffect(ParticleTypes.BLOCK, h.ground());
			boolean plowing = h.plow() >= 1;
			double fwd = Math.atan2(h.dir().z, h.dir().x);
			for (int i = n((10 + 14 * tier) * q, own); i > 0; i--) {
				double a = plowing && RNG.nextBoolean() ? fwd + rnd() * 0.9 : RNG.nextDouble() * 2 * Math.PI;
				double vh = range(0.12, 0.4) * (0.7 + 0.3 * q), vy = Math.min(1.2, range(0.4, 0.85) * (0.8 + 0.2 * q));
				Vec3d p = around(h, a, R * range(0.2, 0.7));
				Particle pt = add(clod, p.x, c.y + 0.4, p.z, Math.cos(a) * vh, vy, Math.sin(a) * vh);
				if (pt != null) pt.setMaxAge(40 + RNG.nextInt(30));
			}
			// bruma baja que se queda un rato y se abre despacio
			for (int i = n((4 + 5 * tier) * q, own); i > 0; i--) {
				double a = RNG.nextDouble() * 2 * Math.PI, s = range(0.02, 0.07) * q;
				Vec3d p = around(h, a, R * range(0.7, 1.5));
				tint(add(ParticleTypes.CAMPFIRE_COSY_SMOKE, p.x, c.y + range(0.2, 1.2), p.z, Math.cos(a) * s,
					range(0.004, 0.02), Math.sin(a) * s), col, 0.85);
			}
		}
		// la cúpula: el polvo que levanta el golpe (núcleo oscuro, piel clara)
		int dome = n((12 + 22 * tier) * q, own);
		double dv = (0.12 + 0.06 * tier) * (0.8 + 0.3 * q);
		dome(c, (dome * 3 + 4) / 5, dust(darker(col, 0.75), 4f), 0.5 * dv, dv, 0.4 * R);
		dome(c, (dome * 2 + 4) / 5, dust(col, 4f), dv, 1.6 * dv, 0.6 * R);
		if (tier >= 2) {
			// nubes de polvo que revientan en el borde
			for (int i = n((2 + 2 * tier) * q, own); i > 0; i--) {
				Vec3d p = around(h, RNG.nextDouble() * 2 * Math.PI, R * range(0.0, 0.9));
				Particle pt = add(ParticleTypes.EXPLOSION, p.x, c.y + range(0.5, 1.0 + 0.4 * R), p.z, 0, 0, 0);
				tint(pt, col, 0.8);
				if (pt != null) pt.scale((float) (0.8 + 0.4 * q));
			}
			// ráfagas de aire alrededor
			for (int i = n(3 + 2 * tier, own); i > 0; i--) {
				Vec3d p = around(h, RNG.nextDouble() * 2 * Math.PI, R + range(1, 3));
				add(ParticleTypes.GUST, p.x, c.y + range(0.3, 1.2), p.z, 0, 0, 0);
			}
			// el polvo que vuelve a caer cuando la nube se abre
			BlockStateParticleEffect settle = new BlockStateParticleEffect(ParticleTypes.FALLING_DUST, h.ground());
			int m = n((6 + 6 * tier) * q, own);
			double top = 3 + 3 * q;
			later(12, NO_TAG, () -> {
				for (int i = m; i > 0; i--) {
					Vec3d p = around(h, RNG.nextDouble() * 2 * Math.PI, R * 1.8 * Math.sqrt(RNG.nextDouble()));
					add(settle, p.x, c.y + range(2, top), p.z, 0, 0, 0);
				}
			});
		}
		// la columna de polvo que sube despacio (y se queda en el cielo unos segundos)
		if (tier == 3 || (tier == 2 && h.sc() >= 2)) {
			for (int i = n(4 + 6 * q * (tier - 1), own); i > 0; i--) {
				Vec3d p = around(h, RNG.nextDouble() * 2 * Math.PI, R * 0.6 * Math.sqrt(RNG.nextDouble()));
				tint(add(ParticleTypes.CAMPFIRE_SIGNAL_SMOKE, p.x, c.y + range(0.5, 2.0), p.z, rnd() * 0.015,
					range(0.06, 0.14) * (0.8 + 0.2 * q), rnd() * 0.015), col, 0.7);
			}
		}
		if (h.plow() >= 1) furrow(h, own);
		if (tier == 3 && OpeSettings.flashLevel() == 2) add(ParticleTypes.FLASH, c.x, c.y + 1, c.z, 0, 0, 0);
	}

	/** Iba rasante: la ola de tierra a los lados del surco, que avanza hasta donde se paró la roca. */
	private static void furrow(Hit h, boolean own) {
		Vec3d c = h.c(), dir = h.dir(), side = new Vec3d(-dir.z, 0, dir.x);
		BlockStateParticleEffect clod = new BlockStateParticleEffect(ParticleTypes.BLOCK, h.ground());
		DustParticleEffect d = dust(h.color(), 3f);
		int steps = Math.min(30, (int) Math.ceil(h.plow()));
		double half = Math.max(1, 0.5 * Math.min(1.4 * h.r(), Math.max(h.crater(), 1)));
		int per = RoomParticles.count(4, own);
		for (int j = 0; j <= steps; j++) {
			Vec3d p = c.add(dir.multiply(h.plow() * j / steps));
			later(j / 2, NO_TAG, () -> {
				for (int i = 0; i < per; i++) {
					double sgn = (i & 1) == 0 ? 1 : -1, s = range(0.12, 0.3);
					double x = p.x + side.x * half * sgn, z = p.z + side.z * half * sgn;
					Particle pt = add(clod, x, p.y + 0.3, z, side.x * sgn * s + dir.x * 0.08, range(0.25, 0.45),
						side.z * sgn * s + dir.z * 0.08);
					if (pt != null) pt.setMaxAge(30 + RNG.nextInt(20));
					add(d, x, p.y + 0.4, z, side.x * sgn * s * 0.5, 0.03, side.z * sgn * s * 0.5);
				}
			});
		}
	}

	/** Un punto a rho del eje del cráter (del golpe hasta donde paró la roca, si araba), en el ángulo a, a la altura del golpe. */
	private static Vec3d around(Hit h, double a, double rho) {
		double u = h.plow() > 0 ? RNG.nextDouble() * h.plow() : 0;
		return new Vec3d(h.c().x + h.dir().x * u + Math.cos(a) * rho, h.c().y, h.c().z + h.dir().z * u + Math.sin(a) * rho);
	}

	/** Una cúpula de polvo: n partículas desde un disco de radio spread, con velocidades entre lo y hi sobre media esfera. */
	private static void dome(Vec3d c, int n, DustParticleEffect d, double lo, double hi, double spread) {
		double[] v = new double[3];
		for (int i = 0; i < n; i++) {
			unit(v);
			v[1] = Math.abs(v[1]);
			double s = range(lo, hi), ox = rnd() * spread, oz = rnd() * spread;
			add(d, c.x + ox + v[0] * 0.3, c.y + v[1] * 0.3, c.z + oz + v[2] * 0.3, v[0] * s, v[1] * s, v[2] * s);
		}
	}

	/**
	 * Un sonido en p que este oyente oye con fuerza g (0..1). Minecraft lo apaga en línea recta hasta 16 bloques ×
	 * volumen y nunca lo sube de 1: de cerca basta con bajar el volumen; de lejos se elige para que el alcance llegue hasta
	 * aquí con fuerza g. Así lo enorme se oye desde muy lejos sin sonar más fuerte de cerca.
	 */
	private static void heard(String id, Vec3d p, float pitch, double g, int delay) {
		if (!(g > 0.01)) return;
		g = Math.min(1.0, g);
		double d = cameraDist(p), lin = 1 - d / 16.0;
		double vol = lin > 0 && g <= lin ? g / lin : Math.min(48.0, d / (1 - Math.min(g, 0.97)) / 16.0);
		TaktSounds.point(id, p, pitch, (float) vol, delay);
	}

	/** (1 − d/reach)^pow entre 0 y 1: lo que queda de algo a la distancia d si se apaga a reach. */
	private static double fade(double d, double reach, double pow) {
		double f = MathHelper.clamp(1 - d / Math.max(1e-3, reach), 0, 1);
		return pow == 1.0 ? f : Math.pow(f, pow);
	}

	/** El color del polvo de un suelo: tierra para la tierra y las plantas, blanco para la nieve; lo demás, su color de mapa mezclado con tierra clara. */
	private static int[] dustColor(ClientWorld w, BlockState st, BlockPos at) {
		int fam = GroundRules.family(st);
		if (fam == GroundRules.SOIL || fam == GroundRules.PLANT) return SOIL;
		if (fam == GroundRules.SNOW) return SNOW;
		int rgb = st.getMapColor(w, at).color;
		return new int[]{(((rgb >> 16) & 0xFF) + LANDED[0]) / 2, (((rgb >> 8) & 0xFF) + LANDED[1]) / 2, ((rgb & 0xFF) + LANDED[2]) / 2};
	}

	private static int[] darker(int[] rgb, double f) {
		return new int[]{(int) (rgb[0] * f), (int) (rgb[1] * f), (int) (rgb[2] * f)};
	}

	/** Tiñe una partícula gris (humo, explosión) del color del polvo, oscurecido por f. */
	private static void tint(@Nullable Particle p, int[] rgb, double f) {
		if (p != null) p.setColor((float) (rgb[0] / 255.0 * f), (float) (rgb[1] / 255.0 * f), (float) (rgb[2] / 255.0 * f));
	}

	/** v partículas (como mucho 160) según la opción de partículas, la mitad si es de otro. */
	private static int n(double v, boolean own) {
		return RoomParticles.count((int) Math.round(MathHelper.clamp(v, 1, 160)), own);
	}

	/** Unir todo: el rebobinado y el acorde; al final del viaje, golpe y destello. */
	private static void rewind(Payloads.TaktFx fx, boolean own, boolean near) {
		if (fx.owner() != null) HOMES.remove(fx.owner());
		if (!near) return;
		TaktSounds.ownOr(own, "takt.rewind", fx.pos(), 1.0f, 0.8f, 0);
		if (!own) return;
		TaktSounds.own("takt.chord", 0.595f, 0.4f, 0);
		TaktSounds.own("takt.chord", 0.749f, 0.35f, 4);
		TaktSounds.own("takt.chord", 0.891f, 0.3f, 8);
		CameraFx.fov(-2f, 0.2f, 0.6f);
		later(Math.max(0, fx.ticks()), NO_TAG, () -> {
			CameraFx.kick(1.03f);
			CameraFx.trauma(0.25f);
			ScreenFx.flash(0.12f, 0.25f);
		});
	}

	/** Un trozo vuelve a su hueco: una nota del acorde que sube con cada llegada, y luz verde que se recoge. */
	private static void home(Payloads.TaktFx fx, boolean own, boolean near) {
		int k = homeIndex(fx.owner());
		if (!near) return;
		Vec3d c = fx.pos();
		TaktSounds.point("takt.seal", c, Math.min(2f, 1.335f * TaktSounds.CHORD[k % 4]), 0.45f, 0);
		if (own) CameraFx.kick((float) (0.35 * Math.min(1.0, fx.b() / 8.0)));
		DustParticleEffect d = dust(MINT, 1.2f);
		double r = Math.max(0.5, fx.a());
		double[] v = new double[3];
		for (int i = RoomParticles.count(8, own); i > 0; i--) {
			unit(v);
			double q = r * Math.cbrt(RNG.nextDouble());
			add(d, c.x + v[0] * q, c.y + v[1] * q, c.z + v[2] * q, -v[0] * 0.05, -v[1] * 0.05, -v[2] * 0.05);
		}
		for (int i = RoomParticles.count(4, own); i > 0; i--) {
			add(ParticleTypes.END_ROD, c.x + rnd() * 0.5 * r, c.y + rnd() * 0.5 * r, c.z + rnd() * 0.5 * r,
				0.02 * rnd(), 0.02 * rnd(), 0.02 * rnd());
		}
	}

	/** No se pudo: el sonido de «denegado», como mucho uno cada 250 ms. */
	private static void deny(MinecraftClient mc, boolean own) {
		if (!own) return;
		long now = System.currentTimeMillis();
		if (now - lastDenyAt < 250) return;
		lastDenyAt = now;
		SoundManager sm = mc.getSoundManager();
		if (sm != null) sm.play(PositionedSoundInstance.master(SoundEvent.of(OpeOpeMod.id("ui.denegado")), 0.8f, 0.35f));
	}

	// ------------------------------------------------------------------ el corte

	private static void cut(MinecraftClient mc, Payloads.CutFx fx, float[] seam) {
		boolean own = fx.cutterId() == mc.player.getId();
		int nSeg = seam.length / 9;
		Vec3d centre = nSeg > 0 ? seamCentre(seam, nSeg) : zoneCentre(fx.data());
		if (centre == null) centre = mc.player.getEyePos();
		if (!own && cameraPos(mc).squaredDistanceTo(centre) > FAR * FAR) return;
		TaktSounds.ownOr(own, "cut.sweep", centre, 1.0f, 0.9f, 0);
		TaktSounds.ownOr(own, "cut.ring", centre, 1.2f, 0.45f, 0);
		TaktSounds.ownOr(own, "cut.glint", centre, 1.8f, 0.4f, 1);
		if ((fx.flags() & Payloads.CutFx.F_TERRAIN) != 0) TaktSounds.ownOr(own, "cut.thump", centre, 0.55f, 0.8f, 0);
		boolean split = fx.pieces() + fx.living() > 0;
		if (split) TaktSounds.ownOr(own, "cut.split", centre, 0.6f, 0.9f, 2);
		if (own) cutCamera(mc, fx);
		if (nSeg == 0) return;
		cutParticles(fx, seam, nSeg, own);
		if (split) {
			for (int i = RoomParticles.count(4, own); i > 0; i--) {
				add(ParticleTypes.END_ROD, centre.x, centre.y, centre.z, 0.03 * rnd(), 0.03 * rnd(), 0.03 * rnd());
			}
		}
	}

	/** El tajo mueve la cámara hacia su lado: sx = el tajo del primer plano contra la derecha de tu vista. */
	private static void cutCamera(MinecraftClient mc, Payloads.CutFx fx) {
		float[] d = fx.data();
		double sx = 0;
		if (d != null && d.length >= 23) {
			Vec3d swipe = new Vec3d(d[20], d[21], d[22]);
			Vec3d right = mc.player.getRotationVector().crossProduct(new Vec3d(0, 1, 0));
			if (swipe.lengthSquared() > 1e-8 && right.lengthSquared() > 1e-8) {
				sx = MathHelper.clamp(swipe.normalize().dotProduct(right.normalize()), -1.0, 1.0);
			}
		}
		CameraFx.kick(-0.64f, (float) (1.16 * sx), (float) (-2.84 * sx));
		CameraFx.trauma((fx.flags() & Payloads.CutFx.F_BLOCKS) != 0 ? 0.2f : 0.1f);
	}

	/** Según el material: chispas (piedra, metal), astillas (madera), destellos (cristal) o polvo (lo demás). */
	private static void cutParticles(Payloads.CutFx fx, float[] seam, int nSeg, boolean own) {
		BlockState st = fx.material() < 0 ? null : Block.getStateFromRawId(fx.material());
		if (st != null && st.isAir()) st = null;
		int fam = st == null ? GroundRules.OTHER : GroundRules.family(st);
		int pts = Math.min(18, nSeg);
		double[] p = new double[6];
		BlockStateParticleEffect chips = st == null ? null : new BlockStateParticleEffect(ParticleTypes.BLOCK, st);
		if (fam == GroundRules.STONE || fam == GroundRules.METAL) {
			int n = RoomParticles.count(6 + Math.min(12, nSeg / 40), own);
			for (int j = 0; j < n; j++) {
				segPoint(seam, nSeg, pts, j, p);
				add(ParticleTypes.ELECTRIC_SPARK, p[0], p[1], p[2], p[3] * 0.12 + rnd() * 0.08, p[4] * 0.12 + rnd() * 0.08, p[5] * 0.12 + rnd() * 0.08);
			}
			chips(chips, seam, nSeg, pts, RoomParticles.count(4, own), p);
		} else if (fam == GroundRules.WOOD) {
			for (int j = RoomParticles.count(10, own) - 1; j >= 0 && chips != null; j--) {
				segPoint(seam, nSeg, pts, j, p);
				double s = range(0.15, 0.3);
				add(chips, p[0], p[1], p[2], p[3] * s + rnd() * 0.1, p[4] * s + rnd() * 0.1, p[5] * s + rnd() * 0.1);
			}
		} else if (fam == GroundRules.GLASS) {
			for (int j = RoomParticles.count(8, own) - 1; j >= 0; j--) {
				segPoint(seam, nSeg, pts, j, p);
				add(ParticleTypes.END_ROD, p[0], p[1], p[2], p[3] * 0.02, p[4] * 0.02, p[5] * 0.02);
			}
			chips(chips, seam, nSeg, pts, RoomParticles.count(4, own), p);
		} else {
			DustParticleEffect d = dust(fam == GroundRules.SNOW ? SNOW : fam == GroundRules.PLANT ? PLANT : SOIL, 1.4f);
			for (int j = RoomParticles.count(6, own) - 1; j >= 0; j--) {
				segPoint(seam, nSeg, pts, j, p);
				add(d, p[0], p[1], p[2], p[3] * 0.03, p[4] * 0.03 + 0.02, p[5] * 0.03);
			}
		}
	}

	/** Trocitos del material que saltan de la costura. */
	private static void chips(@Nullable BlockStateParticleEffect chips, float[] seam, int nSeg, int pts, int n, double[] p) {
		if (chips == null) return;
		for (int j = 0; j < n; j++) {
			segPoint(seam, nSeg, pts, j, p);
			add(chips, p[0], p[1], p[2], p[3] * 0.12 + rnd() * 0.06, p[4] * 0.12 + 0.05 + rnd() * 0.06, p[5] * 0.12 + rnd() * 0.06);
		}
	}

	/**
	 * La partícula j sale del punto medio del trozo número (j mod pts) de pts repartidos con paso fijo, empujado 0,05
	 * por la normal de su cara. p = punto(3), normal(3).
	 */
	private static void segPoint(float[] seam, int nSeg, int pts, int j, double[] p) {
		int s = (int) ((long) (j % pts) * nSeg / pts) * 9;
		double nx = seam[s + 6], ny = seam[s + 7], nz = seam[s + 8];
		p[0] = (seam[s] + seam[s + 3]) * 0.5 + nx * 0.05;
		p[1] = (seam[s + 1] + seam[s + 4]) * 0.5 + ny * 0.05;
		p[2] = (seam[s + 2] + seam[s + 5]) * 0.5 + nz * 0.05;
		p[3] = nx;
		p[4] = ny;
		p[5] = nz;
	}

	private static Vec3d seamCentre(float[] seam, int nSeg) {
		double x = 0, y = 0, z = 0;
		for (int i = 0; i < nSeg; i++) {
			int s = i * 9;
			x += seam[s] + seam[s + 3];
			y += seam[s + 1] + seam[s + 4];
			z += seam[s + 2] + seam[s + 5];
		}
		return new Vec3d(x / (2.0 * nSeg), y / (2.0 * nSeg), z / (2.0 * nSeg));
	}

	/** El centro de la zona del corte: la zona limitada si la hay y es menor que la esfera, si no la esfera. */
	@Nullable
	private static Vec3d zoneCentre(@Nullable float[] d) {
		if (d == null || d.length < 8) return null;
		if (d[7] > 0 && d[7] < d[3]) return new Vec3d(d[4], d[5], d[6]);
		return new Vec3d(d[0], d[1], d[2]);
	}

	// ------------------------------------------------------------------ la grieta

	/** Los trozos del contorno: lados de columna de borde sin vecina (suelo) o caras al aire de las celdas (pared, techo). */
	private static Crack crack(ClientWorld w, Payloads.TaktFx fx, boolean own) {
		int[] data = fx.data() == null ? NONE : fx.data();
		boolean face = (fx.flags() & Payloads.TaktFx.F_FACE) != 0;
		int ax = MathHelper.floor(fx.x()), ay = MathHelper.floor(fx.y()) - (face ? 0 : 1), az = MathHelper.floor(fx.z());
		Crack c = new Crack(own, face, ticks, Math.max(0, fx.ticks()));
		if (face) faceSegments(w, data, ax, ay, az, c);
		else groundSegments(w, data, ax, ay, az, c);
		c.finish(fx, OpeSettings.reducedMotion());
		return c;
	}

	private static void groundSegments(ClientWorld w, int[] data, int ax, int ay, int az, Crack c) {
		IntOpenHashSet cols = new IntOpenHashSet();
		for (int p : data) cols.add(p & COLUMN);
		BlockPos.Mutable m = new BlockPos.Mutable();
		for (int p : data) {
			if ((p & EDGE) == 0) continue;
			int dx = Payloads.TaktFx.dx(p), dz = Payloads.TaktFx.dz(p), dy = Payloads.TaktFx.dy(p);
			int x = ax + dx, y = ay + dy, z = az + dz;
			BlockState st = w.getBlockState(m.set(x, y, z));
			for (Direction d : Direction.values()) {
				int ox = d.getOffsetX(), oz = d.getOffsetZ();
				if (d.getOffsetY() != 0 || cols.contains(Payloads.TaktFx.pack(dx + ox, dz + oz, 0) & COLUMN)) continue;
				c.add(x + 0.5 + ox * 0.5, y + 1, z + 0.5 + oz * 0.5, ox, 0, oz, st);
				if (c.n >= MAX_SEGS) return;
			}
		}
	}

	private static void faceSegments(ClientWorld w, int[] data, int ax, int ay, int az, Crack c) {
		IntOpenHashSet cells = new IntOpenHashSet();
		for (int p : data) cells.add(p & CELL);
		BlockPos.Mutable m = new BlockPos.Mutable();
		for (int p : data) {
			if ((p & EDGE) == 0) continue;
			int dx = Payloads.TaktFx.dx(p), dz = Payloads.TaktFx.dz(p), dy = Payloads.TaktFx.dy(p);
			int x = ax + dx, y = ay + dy, z = az + dz;
			BlockState st = w.getBlockState(m.set(x, y, z));
			for (Direction d : Direction.values()) {
				int ox = d.getOffsetX(), oy = d.getOffsetY(), oz = d.getOffsetZ();
				if (cells.contains(Payloads.TaktFx.pack(dx + ox, dz + oz, dy + oy))) continue;
				BlockState nb = w.getBlockState(m.set(x + ox, y + oy, z + oz));
				if (!nb.isAir() && !nb.isReplaceable()) continue;
				c.add(x + 0.5 + ox * 0.5, y + 0.5 + oy * 0.5, z + 0.5 + oz * 0.5, ox, oy, oz, st);
				if (c.n >= MAX_SEGS) return;
			}
		}
	}

	/** Los guijarros con hora cuyo frente ya llegó (age = ticks desde el TEAR_START); uno sí y otro no, con polvo. */
	private static void firePebbles(Crack c, int age) {
		while (c.next < c.timed.length && c.delay[c.timed[c.next]] <= age) {
			pebble(c, c.timed[c.next], (c.next & 1) == 0);
			c.next++;
		}
	}

	/** Un guijarro de la grieta: salta hacia arriba (suelo) o se desprende de la cara (pared, techo). */
	private static void pebble(Crack c, int i, boolean withDust) {
		double mx = c.mid[3 * i], my = c.mid[3 * i + 1], mz = c.mid[3 * i + 2];
		double ox = c.out[3 * i], oy = c.out[3 * i + 1], oz = c.out[3 * i + 2];
		double px, py, pz, vx, vy, vz;
		if (c.face) {
			double s = range(0.06, 0.12);
			px = mx + ox * 0.1;
			py = my + oy * 0.1;
			pz = mz + oz * 0.1;
			vx = ox * s + 0.03 * rnd();
			vy = oy * s + 0.06 + 0.04 * rnd();
			vz = oz * s + 0.03 * rnd();
		} else {
			px = mx;
			py = my + 0.1;
			pz = mz;
			vx = 0.05 * rnd();
			vy = 0.18 + 0.08 * rnd();
			vz = 0.05 * rnd();
		}
		BlockState st = c.state[i];
		if (st != null && !st.isAir()) add(new BlockStateParticleEffect(ParticleTypes.BLOCK, st), px, py, pz, vx, vy, vz);
		if (withDust) add(dust(SOIL, 1.0f), px, py, pz, 0.02 * rnd(), 0.02, 0.02 * rnd());
	}

	private static void tickCracks() {
		if (CRACKS.isEmpty()) return;
		Iterator<Crack> it = CRACKS.values().iterator();
		while (it.hasNext()) {
			Crack c = it.next();
			int age = ticks - c.born;
			if (age > c.ticks + 60 || c.n == 0) {
				it.remove();
				continue;
			}
			firePebbles(c, age);
			// mientras espera a soltarse (como mucho ticks + 10): dos guijarros más por tick, en trozos al azar
			if (age > c.maxDelay && age < c.ticks + 10) {
				for (int k = RoomParticles.count(2, c.own); k > 0; k--) pebble(c, RNG.nextInt(c.n), false);
			}
		}
	}

	/** Una de las dos ráfagas del arranque: polvo y piedras desde el borde (o desde un círculo de radio a). */
	private static void burst(@Nullable Crack c, Payloads.TaktFx fx, int puffs, int rocks, @Nullable BlockState rock) {
		double[] p = new double[6];
		for (int i = 0; i < puffs; i++) {
			edgePoint(c, fx, p);
			double s = range(0.05, 0.12);
			add(dust(SOIL, (float) (2.5 + rnd())), p[0], p[1] + 0.1, p[2], p[3] * s, 0.04 + p[4] * s, p[5] * s);
		}
		if (rock == null || rock.isAir()) return;
		BlockStateParticleEffect eff = new BlockStateParticleEffect(ParticleTypes.BLOCK, rock);
		for (int i = 0; i < rocks; i++) {
			edgePoint(c, fx, p);
			double s = range(0.1, 0.2);
			add(eff, p[0], p[1] + 0.1, p[2], p[3] * s, range(0.25, 0.45) + p[4] * s, p[5] * s);
		}
	}

	/** Un punto del borde al azar, p = punto(3), hacia fuera(3): de la grieta si la hay, si no del círculo de radio a. */
	private static void edgePoint(@Nullable Crack c, Payloads.TaktFx fx, double[] p) {
		if (c != null && c.n > 0) {
			int i = RNG.nextInt(c.n);
			System.arraycopy(c.mid, 3 * i, p, 0, 3);
			System.arraycopy(c.out, 3 * i, p, 3, 3);
			return;
		}
		double a = RNG.nextDouble() * 2 * Math.PI, r = Math.max(0.5, fx.a());
		double cx = Math.cos(a), cz = Math.sin(a);
		p[0] = fx.x() + cx * r;
		p[1] = fx.y();
		p[2] = fx.z() + cz * r;
		p[3] = cx;
		p[4] = 0;
		p[5] = cz;
	}

	// ------------------------------------------------------------------ más tarde

	private static void later(int delay, int tag, Runnable run) {
		if (LATER.size() >= MAX_LATER) LATER.remove(0);
		LATER.add(new Later(ticks + Math.max(0, delay), tag, run));
	}

	/** Llegó el DETACH o el CANCEL de ese arranque: fuera lo que esperaba por él (el segundo crujido). */
	private static void dropTag(int tag) {
		LATER.removeIf(l -> l.tag() == tag);
	}

	private static void runLater() {
		if (LATER.isEmpty()) return;
		List<Later> due = new ArrayList<>();
		Iterator<Later> it = LATER.iterator();
		while (it.hasNext()) {
			Later l = it.next();
			if (l.due() > ticks) continue;
			due.add(l);
			it.remove();
		}
		for (Later l : due) {
			try {
				l.run().run();
			} catch (RuntimeException e) {
				warn(e);
			}
		}
	}

	// ------------------------------------------------------------------ piezas: tierra que cae, estelas, silbidos

	/** Lo que necesita la pieza: si el cliente ya la tiene empieza ahora; si no, se reintenta 10 ticks. */
	private static void pending(MinecraftClient mc, int entityId, boolean shed, boolean own, boolean chunk) {
		if (entityId == -1) return;
		Pending p = new Pending(entityId, ticks, shed, own, chunk);
		if (tryStart(mc, p)) return;
		if (PENDING.size() >= MAX_PENDING) PENDING.remove(0);
		PENDING.add(p);
	}

	/** true si ya no hay que esperar: empezó, o esa entidad no es una pieza. */
	private static boolean tryStart(MinecraftClient mc, Pending p) {
		Entity e = mc.world.getEntityById(p.entityId());
		if (e == null) return false;
		if (e instanceof PieceEntity pe && !pe.isRemoved()) start(pe, p);
		return true;
	}

	private static void start(PieceEntity pe, Pending p) {
		if (p.shed()) {
			for (Shed s : SHEDS) if (s.pe == pe) return;
			if (SHEDS.size() >= MAX_SHEDS) SHEDS.remove(0);
			SHEDS.add(new Shed(pe, p.own(), ticks));
			return;
		}
		// estela: trozos arrancados o piezas grandes
		if (!p.chunk() && (pe.flags() & PieceEntity.F_CHUNK) == 0 && pe.radius() <= 1.2) return;
		Iterator<Trail> it = TRAILS.iterator();
		while (it.hasNext()) {
			Trail t = it.next();
			if (t.pe != pe) continue;
			t.stopFly();
			it.remove();
		}
		if (TRAILS.size() >= MAX_TRAILS) TRAILS.remove(0).stopFly();
		TRAILS.add(new Trail(pe, p.own(), ticks));
	}

	private static void tickPending(MinecraftClient mc) {
		Iterator<Pending> it = PENDING.iterator();
		while (it.hasNext()) {
			Pending p = it.next();
			if (tryStart(mc, p) || ticks - p.born() > PENDING_TICKS) it.remove();
		}
	}

	/** La pieza recién arrancada suelta tierra por debajo 2 s (para si desaparece o deja de ser un trozo del suelo). */
	private static void tickSheds() {
		Iterator<Shed> it = SHEDS.iterator();
		while (it.hasNext()) {
			Shed s = it.next();
			int age = ticks - s.born;
			boolean chunk = (s.pe.flags() & PieceEntity.F_CHUNK) != 0;
			if (chunk) s.seen = true;
			if (s.pe.isRemoved() || age > SHED_TICKS || (!chunk && (s.seen || age > 5))) {
				it.remove();
				continue;
			}
			shed(s, age);
		}
	}

	private static void shed(Shed s, int age) {
		PieceEntity pe = s.pe;
		List<BlockSet.Entry> under = pe.blocks.underside();
		if (under.isEmpty()) return;
		double sc = MathHelper.clamp(pe.radius() / 3.0, 0.7, 4.0);
		s.carry += (14 + 10 * sc) / 20.0 * s.factor;
		Vec3d corner = pe.corner();
		while (s.carry >= 1) {
			s.carry -= 1;
			BlockSet.Entry e = under.get(RNG.nextInt(under.size()));
			if (e.state() == null || e.state().isAir()) continue;
			add(new BlockStateParticleEffect(ParticleTypes.BLOCK, e.state()), corner.x + e.x() + 0.5 + 0.2 * rnd(),
				corner.y + e.y() + 0.02, corner.z + e.z() + 0.5 + 0.2 * rnd(), 0.02 * rnd(), -0.05, 0.02 * rnd());
		}
		if (age % 8 == 0) TaktSounds.point("takt.shed", pe.center(), (float) (1.1 + 0.1 * rnd()), 0.25f, 0);
	}

	/** Estelas: cada 2 ticks, polvo detrás de lo lanzado mientras va a más de 0,4 bloques por tick (80 ticks como mucho). */
	private static void tickTrails() {
		Iterator<Trail> it = TRAILS.iterator();
		while (it.hasNext()) {
			Trail t = it.next();
			int age = ticks - t.born;
			Vec3d pos = t.pe.getPos();
			Vec3d prev = t.last;
			double d1 = prev == null ? 0 : pos.distanceTo(prev);
			t.v = t.last2 == null ? d1 : Math.max(d1, pos.distanceTo(t.last2) / 2);
			t.last2 = prev;
			t.last = pos;
			boolean slow = t.v <= 0.4;
			// recién lanzada puede ir aún despacio en el cliente: unos ticks de gracia
			if (t.pe.isRemoved() || age > TRAIL_TICKS || (slow && age > 6)) {
				t.stopFly();
				it.remove();
				continue;
			}
			if (!slow && age % 2 == 0 && prev != null) trailDust(t, pos.subtract(prev));
		}
	}

	/** Polvo en puntos al azar de la mitad de atrás de la caja (lo de delante se refleja por el centro). */
	private static void trailDust(Trail t, Vec3d dir) {
		Box b = t.pe.getBoundingBox();
		Vec3d c = b.getCenter();
		DustParticleEffect d = dust(TRAIL, 2.0f);
		for (int i = RoomParticles.count(MathHelper.clamp((int) Math.round(t.pe.radius()), 1, 4), t.own); i > 0; i--) {
			double x = b.minX + RNG.nextDouble() * b.getLengthX();
			double y = b.minY + RNG.nextDouble() * b.getLengthY();
			double z = b.minZ + RNG.nextDouble() * b.getLengthZ();
			if ((x - c.x) * dir.x + (y - c.y) * dir.y + (z - c.z) * dir.z > 0) {
				x = 2 * c.x - x;
				y = 2 * c.y - y;
				z = 2 * c.z - z;
			}
			add(d, x, y, z, 0, 0, 0);
		}
	}

	/** El silbido de lo que pasa cerca: como mucho 3 a la vez; se apaga lejos (14), despacio (0,4) o al acabar la estela. */
	private static void tickFlybys(MinecraftClient mc) {
		if (TRAILS.isEmpty()) return;
		Vec3d cam = cameraPos(mc);
		int playing = 0;
		for (Trail t : TRAILS) {
			if (t.fly == null) continue;
			double d = cam.distanceTo(t.pe.getLerpedPos(1f));
			if (t.fly.isDone() || t.v < 0.4 || d > 14 || roaring(t.pe)) {
				t.stopFly();
				continue;
			}
			t.fly.set(flyVolume(t.v, d), flyPitch(t.v));
			playing++;
		}
		SoundManager sm = mc.getSoundManager();
		for (Trail t : TRAILS) {
			if (playing >= MAX_FLYBY || sm == null) return;
			if (t.fly != null || t.v <= 0.8 || roaring(t.pe)) continue;
			double d = cam.distanceTo(t.pe.getLerpedPos(1f));
			if (d > 12) continue;
			PieceEntity pe = t.pe;
			LoopSound s = new LoopSound(OpeOpeMod.id("takt.flyby"), true, () -> pe.getLerpedPos(1f));
			s.set(flyVolume(t.v, d), flyPitch(t.v));
			sm.play(s);
			t.fly = s;
			playing++;
		}
	}

	private static float flyVolume(double v, double d) {
		return (float) (0.5 * Math.min(1.0, v / 2) * Math.max(0.0, 1 - d / 12));
	}

	private static float flyPitch(double v) {
		return (float) (0.8 + 0.3 * Math.min(1.0, v / 2));
	}

	// ------------------------------------------------------------------ rocas que caen

	/**
	 * Rocas grandes sueltas cerca de la cámara (soltadas, lanzadas o resbalando), mirado cada 4 ticks. Mientras van
	 * deprisa: el rugido del aire (como mucho 3 a la vez), la estela de polvo aunque nadie la lanzara, el chapuzón si
	 * entran en el agua y el aire que aparta justo antes de tocar el suelo.
	 */
	private static void tickFalls(MinecraftClient mc) {
		Vec3d cam = cameraPos(mc);
		if (ticks % 4 == 0 && FALLS.size() < MAX_FALLS) {
			for (PieceEntity pe : mc.world.getEntitiesByClass(PieceEntity.class, new Box(cam, cam).expand(FALL_SCAN),
				e -> e.radius() >= FALL_RADIUS && loose(e))) {
				if (FALLS.size() >= MAX_FALLS) break;
				FALLS.computeIfAbsent(pe.getId(), id -> new Fall(pe));
			}
		}
		if (FALLS.isEmpty()) return;
		int roaring = 0;
		for (Fall f : FALLS.values()) if (f.roar != null) roaring++;
		Iterator<Fall> it = FALLS.values().iterator();
		while (it.hasNext()) {
			Fall f = it.next();
			PieceEntity pe = f.pe;
			Vec3d pos = pe.getPos();
			Vec3d step = pos.subtract(f.last);
			f.last = pos;
			if (step.lengthSquared() > 64) step = Vec3d.ZERO;   // un salto, no una velocidad
			f.v = 0.5 * f.v + 0.5 * step.length();
			f.vy = 0.5 * f.vy + 0.5 * step.y;
			if (pe.isRemoved() || pe.getWorld() != mc.world || !loose(pe) || cam.squaredDistanceTo(pos) > FALL_KEEP * FALL_KEEP) {
				if (f.roar != null) roaring--;
				f.stopRoar();
				it.remove();
				continue;
			}
			double sc = MathHelper.clamp(pe.radius() / 3, 0.5, 4);
			if (f.v > 0.5) keepTrail(pe);
			splash(mc.world, f, sc);
			downdraft(mc.world, f, sc);
			boolean had = f.roar != null;
			roar(mc, f, sc, cam, step, roaring < MAX_ROARS);
			if (!had && f.roar != null) roaring++;
			if (had && f.roar == null) roaring--;
		}
	}

	/** Suelta: ni la lleva nadie, ni se está posando, arrancando o flotando tras un corte. */
	private static boolean loose(PieceEntity pe) {
		return (pe.flags() & (PieceEntity.F_HELD | PieceEntity.F_LANDED | PieceEntity.F_TEAR | PieceEntity.F_SUSPENDED)) == 0;
	}

	private static boolean roaring(PieceEntity pe) {
		Fall f = FALLS.get(pe.getId());
		return f != null && f.roar != null;
	}

	/**
	 * El rugido del aire: empieza a 0,6 bloques por tick (12 m/s) y suena más fuerte y más agudo cuanto más rápido, con
	 * efecto Doppler (más agudo mientras se acerca, más grave al alejarse). Se apaga si frena o queda lejos.
	 */
	private static void roar(MinecraftClient mc, Fall f, double sc, Vec3d cam, Vec3d step, boolean mayStart) {
		Vec3d at = f.pe.center();
		double d = cam.distanceTo(at), reach = 24 + 10 * sc;
		if (f.roar != null && (f.roar.isDone() || f.v < 0.4 || d > reach + 8)) f.stopRoar();
		float vol = (float) Math.min(0.9, (0.2 + 0.3 * Math.min(1.0, f.v / 2.5)) * (0.8 + 0.2 * sc));
		double closing = d > 1e-3 ? -step.dotProduct(at.subtract(cam)) / d : 0;
		double doppler = MathHelper.clamp(SOUND_SPEED / Math.max(1.0, SOUND_SPEED - closing), 0.8, 1.35);
		float pitch = (float) ((0.55 + 0.3 * Math.min(1.0, f.v / 3) - 0.04 * sc) * doppler);
		if (f.roar != null) {
			f.roar.set(vol, pitch);
			return;
		}
		if (!mayStart || f.v <= 0.6 || d > reach) return;
		SoundManager sm = mc.getSoundManager();
		if (sm == null) return;
		LoopSound s = new LoopSound(OpeOpeMod.id("takt.fall"), true, f.pe::center);
		s.set((float) (reach / 16), pitch);   // el alcance (16 bloques × volumen) se fija al empezar
		sm.play(s);
		s.set(vol, pitch);
		f.roar = s;
		ROARS++;
	}

	/** La estela de polvo también para lo que cae sin que nadie lo lanzara (sin THROW no la empieza nadie más). */
	private static void keepTrail(PieceEntity pe) {
		for (Trail t : TRAILS) if (t.pe == pe) return;
		if (TRAILS.size() >= MAX_TRAILS) TRAILS.remove(0).stopFly();
		TRAILS.add(new Trail(pe, false, ticks));
	}

	/** Al entrar en el agua cayendo: una columna de agua y espuma del tamaño de la roca, y el chapuzón (una vez). */
	private static void splash(ClientWorld w, Fall f, double sc) {
		if (f.splashed || f.vy > -0.3) return;
		PieceEntity pe = f.pe;
		BlockPos.Mutable m = new BlockPos.Mutable(MathHelper.floor(pe.getX()), MathHelper.floor(pe.getY()), MathHelper.floor(pe.getZ()));
		if (!w.getFluidState(m).isIn(FluidTags.WATER)) return;
		f.splashed = true;
		int y0 = m.getY(), top = y0;
		while (top < y0 + 8 && w.getFluidState(m.setY(top + 1)).isIn(FluidTags.WATER)) top++;
		double x = pe.getX(), y = top + 0.9, z = pe.getZ(), q = Math.sqrt(sc), r = 0.5 * Math.max(pe.blocks.sx, pe.blocks.sz);
		double speed = Math.min(1.0, -f.vy / 2.5);
		for (int i = n((20 + 20 * q) * (0.5 + speed), true); i > 0; i--) {
			double a = RNG.nextDouble() * 2 * Math.PI, rho = r * range(0.6, 1.1), s = range(0.05, 0.25) * q;
			add(ParticleTypes.SPLASH, x + Math.cos(a) * rho, y, z + Math.sin(a) * rho, Math.cos(a) * s,
				range(0.4, 0.9) * (0.7 + 0.3 * q) * (0.6 + 0.4 * speed), Math.sin(a) * s);
		}
		for (int i = n(6 + 8 * q, true); i > 0; i--) {
			double a = RNG.nextDouble() * 2 * Math.PI, rho = r * range(0.3, 1.2);
			add(ParticleTypes.CLOUD, x + Math.cos(a) * rho, y + range(0, 1.5), z + Math.sin(a) * rho, Math.cos(a) * 0.08, range(0.05, 0.2), Math.sin(a) * 0.08);
		}
		TaktSounds.vanillaAt(SoundEvents.ENTITY_GENERIC_SPLASH, x, y, z, (float) Math.max(0.5, 0.8 - 0.07 * sc), (float) (1.5 + sc), 0);
		TaktSounds.vanillaAt(SoundEvents.ENTITY_PLAYER_SPLASH_HIGH_SPEED, x, y, z, (float) Math.max(0.5, 0.75 - 0.05 * sc), (float) (1.0 + 0.5 * sc), 1);
	}

	/** Justo antes de tocar el suelo, el aire que empuja la roca levanta polvo hacia fuera por debajo (una vez). */
	private static void downdraft(ClientWorld w, Fall f, double sc) {
		if (f.gusted || f.vy > -0.5) return;
		PieceEntity pe = f.pe;
		int top = w.getTopY(Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, MathHelper.floor(pe.getX()), MathHelper.floor(pe.getZ()));
		double h = pe.getY() - top;
		if (h > 1.5 + 0.8 * pe.radius() || h < -1) return;
		f.gusted = true;
		BlockState st = w.getBlockState(BlockPos.ofFloored(pe.getX(), top - 0.5, pe.getZ()));
		DustParticleEffect d = dust(dustColor(w, st, BlockPos.ofFloored(pe.getX(), top - 0.5, pe.getZ())), 3f);
		double r = 0.5 * Math.max(pe.blocks.sx, pe.blocks.sz), q = Math.sqrt(sc);
		for (int i = n(10 + 8 * q, true); i > 0; i--) {
			double a = RNG.nextDouble() * 2 * Math.PI, s = range(0.25, 0.5) * q;
			add(d, pe.getX() + Math.cos(a) * r, top + 0.2, pe.getZ() + Math.sin(a) * r, Math.cos(a) * s, 0.02, Math.sin(a) * s);
		}
	}

	// ------------------------------------------------------------------ lo que llevas

	/** El zumbido de lo que llevas (más fuerte cuanto más pesa) y, pasados 10 ticks, un temblor si pesa mucho. */
	private static void tickHold(MinecraftClient mc) {
		double m = 0;
		boolean longHeld = false;
		Set<Integer> seen = new HashSet<>();
		for (int id : ClientState.heldIds) {
			Entity e = mc.world.getEntityById(id);
			if (!(e instanceof PieceEntity pe) || pe.isRemoved()) continue;
			seen.add(id);
			m += massOf(pe);
			Integer since = HELD_SINCE.get(id);
			if (since == null) HELD_SINCE.put(id, ticks);
			else if (ticks - since > 10) longHeld = true;
		}
		HELD_SINCE.keySet().retainAll(seen);
		MASS.keySet().retainAll(seen);
		holdSound(mc, m);
		CameraFx.traumaFloor("takt", longHeld ? (m > 20000 ? 0.24f : m > 3000 ? 0.15f : 0f) : 0f);
	}

	/** El peso de una pieza en kg, recordado mientras no cambie su BlockSet. */
	private static double massOf(PieceEntity pe) {
		MassMemo memo = MASS.get(pe.getId());
		if (memo != null && memo.set() == pe.blocks) return memo.kg();
		double kg = GroundRules.mass(pe.blocks);
		MASS.put(pe.getId(), new MassMemo(pe.blocks, kg));
		return kg;
	}

	/** Como RoomSounds.keep: sigue sonando o se vuelve a lanzar (si Minecraft lo paró, como mucho cada 2 s). */
	private static void holdSound(MinecraftClient mc, double m) {
		if (m <= 0) {
			if (hold != null) hold.stop();
			hold = null;
			return;
		}
		float volume = (float) (0.10 + 0.08 * Math.min(1.0, m / 20000.0));
		SoundManager sm = mc.getSoundManager();
		if (sm == null) return;
		if (hold != null && !hold.stopping()) {
			if (sm.isPlaying(hold)) {
				hold.set(volume, 0.6f);
				return;
			}
			if (holdRetry > 0) {
				holdRetry--;
				hold.set(volume, 0.6f);
				return;
			}
		}
		LoopSound n = new LoopSound(OpeOpeMod.id("takt.hold"), true, null);
		n.set(volume, 0.6f);
		sm.play(n);
		hold = n;
		holdRetry = 40;
	}

	// ------------------------------------------------------------------ por dueño

	/** La nota del acorde de los agarres en grupo: vuelve a 0 si el anterior fue hace más de 30 ticks. */
	private static int chordIndex(@Nullable UUID owner) {
		if (owner == null) return 0;
		int[] s = CHORDS.computeIfAbsent(owner, k -> new int[]{0, Integer.MIN_VALUE / 2});
		int i = ticks - s[1] > 30 ? 0 : s[0];
		s[0] = i + 1;
		s[1] = ticks;
		return i;
	}

	/** Cuántos trozos de ese dueño llegaron a casa desde su último REWIND (y cuenta este). */
	private static int homeIndex(@Nullable UUID owner) {
		if (owner == null) return 0;
		int k = HOMES.getOrDefault(owner, 0);
		HOMES.put(owner, k + 1);
		return k;
	}

	// ------------------------------------------------------------------ ayudas

	/** Al cambiar de mundo (dimensión, reaparecer, salir) se limpia todo lo de antes. */
	private static void checkWorld(MinecraftClient mc) {
		if (mc.world == lastWorld) return;
		clear();
		lastWorld = mc.world;
	}

	private static void warn(RuntimeException e) {
		if (warned) return;
		warned = true;
		OpeOpeMod.LOG.warn("TaktFxClient: {}", e.toString());
	}

	private static DustParticleEffect dust(int[] rgb, float scale) {
		return new DustParticleEffect(new Vector3f(rgb[0] / 255f, rgb[1] / 255f, rgb[2] / 255f), Math.min(4f, Math.max(0.1f, scale)));
	}

	/** Uniforme en [−1, 1]. */
	private static double rnd() {
		return RNG.nextDouble() * 2 - 1;
	}

	private static double range(double a, double b) {
		return a + RNG.nextDouble() * (b - a);
	}

	/** Un vector al azar de largo 1. */
	private static void unit(double[] v) {
		double l;
		do {
			v[0] = rnd();
			v[1] = rnd();
			v[2] = rnd();
			l = v[0] * v[0] + v[1] * v[1] + v[2] * v[2];
		} while (l < 1e-4 || l > 1);
		l = Math.sqrt(l);
		v[0] /= l;
		v[1] /= l;
		v[2] /= l;
	}

	private static Vec3d cameraPos(MinecraftClient mc) {
		if (mc.gameRenderer != null && mc.gameRenderer.getCamera().isReady()) return mc.gameRenderer.getCamera().getPos();
		if (mc.player != null) return mc.player.getEyePos();
		return Vec3d.ZERO;
	}

	private static double cameraDist(Vec3d p) {
		return Math.sqrt(cameraPos(MinecraftClient.getInstance()).squaredDistanceTo(p));
	}

	/** Copia de RoomParticles.add: la velocidad se pone después de nacer (si no, Minecraft la cambia por una al azar). */
	@Nullable
	private static Particle add(ParticleEffect effect, double x, double y, double z, double vx, double vy, double vz) {
		MinecraftClient mc = MinecraftClient.getInstance();
		if (mc.world == null || mc.particleManager == null) return null;
		if (tooClose(mc, effect, x, y, z, vx, vy, vz)) return null;
		Particle p = mc.particleManager.addParticle(effect, x, y, z, vx, vy, vz);
		if (p != null) p.setVelocity(vx, vy, vz);
		return p;
	}

	/** Copia de RoomParticles.tooClose: demasiado cerca del ojo al nacer, o su camino pasa rozándolo. */
	private static boolean tooClose(MinecraftClient mc, ParticleEffect effect, double x, double y, double z,
									double vx, double vy, double vz) {
		Camera cam = mc.gameRenderer == null ? null : mc.gameRenderer.getCamera();
		if (cam == null || !cam.isReady()) return false;
		Vec3d c = cam.getPos();
		double px = x - c.x, py = y - c.y, pz = z - c.z;
		double d2 = px * px + py * py + pz * pz;
		if (effect == ParticleTypes.FLASH) return d2 < FLASH_NEAR * FLASH_NEAR;
		if (d2 < NEAR * NEAR) return true;
		double life = effect instanceof DustParticleEffect ? 20 : 11;
		double ex = vx * life, ey = vy * life, ez = vz * life;
		double ee = ex * ex + ey * ey + ez * ez;
		if (ee < 1e-6) return false;
		double t = -(px * ex + py * ey + pz * ez) / ee;
		if (t <= 0) return false;
		if (t > 1) t = 1;
		double qx = px + ex * t, qy = py + ey * t, qz = pz + ez * t;
		return qx * qx + qy * qy + qz * qz < PASS * PASS;
	}
}
