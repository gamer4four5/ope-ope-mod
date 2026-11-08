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
import net.minecraft.sound.SoundEvent;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
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
 * la tierra que suelta un trozo recién arrancado, la estela de lo lanzado, el silbido de lo que pasa cerca, y el zumbido
 * y el temblor de lo que llevas.
 * <p>Lo de otro jugador a más de 96 bloques de la cámara no saca nada (un impacto, hasta 160). La cámara solo se mueve
 * con lo propio, salvo los impactos. Al cambiar de mundo (dimensión, reaparecer) se limpia sola. Los arrays de los
 * paquetes pueden ser los del servidor (un jugador): aquí solo se leen.
 */
public final class TaktFxClient {
	private TaktFxClient() {}

	/** Cuántos de cada tipo llegaron (propios o no), en qué tick del cliente llegó el último y cuál fue. */
	public static final int[] COUNTS = new int[Payloads.TaktFx.KINDS];
	public static final int[] LAST_TICK = new int[Payloads.TaktFx.KINDS];
	public static final Payloads.TaktFx[] LAST = new Payloads.TaktFx[Payloads.TaktFx.KINDS];

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
	private static final double[] IMPACT_TRAUMA = {0.05, 0.1, 0.25, 0.45};
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

	private static int ticks;
	@Nullable private static ClientWorld lastWorld;
	private static boolean warned;
	private static long lastDenyAt;
	private static final List<Later> LATER = new ArrayList<>();
	private static final List<Pending> PENDING = new ArrayList<>();
	private static final LinkedHashMap<Integer, Crack> CRACKS = new LinkedHashMap<>();
	private static final List<Shed> SHEDS = new ArrayList<>();
	private static final List<Trail> TRAILS = new ArrayList<>();
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
				if (dist <= FAR_IMPACT) impact(mc, fx, own, dist);
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
			TaktSounds.point("takt.land", at, TaktSounds.massRate(1000.0 * fx.b()), 0.7f, 0);
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

	/** Impacto: golpe (con el retraso del sonido por la distancia), temblor para quien esté cerca y una nube según el nivel. */
	private static void impact(MinecraftClient mc, Payloads.TaktFx fx, boolean own, double dist) {
		int tier = MathHelper.clamp(fx.state(), 0, 3);
		Vec3d c = fx.pos();
		int delay = (int) Math.round(Math.max(0.0, dist - 17.0) / 17.0);
		Entity e = mc.world.getEntityById(fx.entityId());
		double tonnes = e instanceof PieceEntity pe ? massOf(pe) / 1000.0 : 1000.0 * tier;
		float rate = TaktSounds.massRate(1000.0 * tonnes) * (tier >= 2 ? 0.8f : 1f);
		TaktSounds.point("takt.impact", c, rate, IMPACT_VOL[tier], delay);
		if (tier >= 2) TaktSounds.point("takt.impact_far", c, 0.6f, tier == 2 ? 2.0f : 3.5f, delay);
		if (dist <= 64) CameraFx.trauma((float) Math.min(0.6, IMPACT_TRAUMA[tier] * 30.0 / (30.0 + dist)));
		if (tier == 3 && dist <= 24) {
			CameraFx.fov(2f, 0.05f, 0.4f);
			ScreenFx.flash(0.08f, 0.2f);
		}
		impactParticles(mc.world, c, tier, Math.max(fx.a(), 1.0), own);
	}

	private static void impactParticles(ClientWorld w, Vec3d c, int tier, double r, boolean own) {
		DustParticleEffect landed = dust(LANDED, 2.5f);
		for (int i = RoomParticles.count(6, own); i > 0; i--) {
			double a = RNG.nextDouble() * 2 * Math.PI, d = range(0.2, 0.6) * r, s = range(0.04, 0.1);
			add(landed, c.x + Math.cos(a) * d, c.y + 0.2, c.z + Math.sin(a) * d, Math.cos(a) * s, 0.02, Math.sin(a) * s);
		}
		if (tier >= 1) {
			BlockState st = w.getBlockState(BlockPos.ofFloored(c.x, c.y - 0.5, c.z));
			if (st.isAir()) st = Blocks.DIRT.getDefaultState();
			BlockStateParticleEffect eff = new BlockStateParticleEffect(ParticleTypes.BLOCK, st);
			for (int i = RoomParticles.count(20, own); i > 0; i--) {
				double a = RNG.nextDouble() * 2 * Math.PI, s = range(0.1, 0.2);
				add(eff, c.x + 0.3 * rnd(), c.y + 0.1, c.z + 0.3 * rnd(), Math.cos(a) * s, range(0.2, 0.4), Math.sin(a) * s);
			}
		}
		if (tier >= 2) {
			dome(c, RoomParticles.count(40, own));
			for (int i = RoomParticles.count(3, own); i > 0; i--) {
				add(ParticleTypes.EXPLOSION, c.x + rnd() * r, c.y + Math.abs(rnd()) * r * 0.5, c.z + rnd() * r, 0, 0, 0);
			}
		}
		if (tier >= 3) {
			DustParticleEffect smoke = dust(SMOKE, 3.0f);
			for (int i = RoomParticles.count(24, own); i > 0; i--) {
				add(smoke, c.x + rnd() * 0.5 * r, c.y + RNG.nextDouble(), c.z + rnd() * 0.5 * r, 0, range(0.06, 0.12), 0);
			}
			dome(c, RoomParticles.count(80, own));
			if (OpeSettings.flashLevel() == 2) add(ParticleTypes.FLASH, c.x, c.y + 1, c.z, 0, 0, 0);
		}
	}

	/** Una cúpula de polvo: velocidades al azar sobre media esfera, entre 0,15 y 0,3. */
	private static void dome(Vec3d c, int n) {
		DustParticleEffect d = dust(ZONEDUST, 4.0f);
		double[] v = new double[3];
		for (int i = 0; i < n; i++) {
			unit(v);
			v[1] = Math.abs(v[1]);
			double s = range(0.15, 0.3);
			add(d, c.x + v[0] * 0.3, c.y + v[1] * 0.3, c.z + v[2] * 0.3, v[0] * s, v[1] * s, v[2] * s);
		}
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
			if (t.fly.isDone() || t.v < 0.4 || d > 14) {
				t.stopFly();
				continue;
			}
			t.fly.set(flyVolume(t.v, d), flyPitch(t.v));
			playing++;
		}
		SoundManager sm = mc.getSoundManager();
		for (Trail t : TRAILS) {
			if (playing >= MAX_FLYBY || sm == null) return;
			if (t.fly != null || t.v <= 0.8) continue;
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
