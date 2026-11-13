package dev.opeope.client.render;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import dev.opeope.OpeConfig;
import dev.opeope.OpeOpeMod;
import dev.opeope.client.ClientState;
import dev.opeope.client.config.OpeSettings;
import dev.opeope.client.input.InputHandler;
import dev.opeope.client.room.RoomFxClient;
import dev.opeope.entity.BlockSet;
import dev.opeope.entity.PieceEntity;
import dev.opeope.net.Payloads;
import dev.opeope.terrain.ChunkPlan;
import dev.opeope.terrain.ChunkPlanner;
import dev.opeope.terrain.GroundCut;
import dev.opeope.terrain.GroundRules;
import dev.opeope.terrain.Landing;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext;
import net.minecraft.block.BlockState;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.BufferBuilder;
import net.minecraft.client.render.BufferRenderer;
import net.minecraft.client.render.GameRenderer;
import net.minecraft.client.render.Tessellator;
import net.minecraft.client.render.VertexFormat;
import net.minecraft.client.render.VertexFormats;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.util.Identifier;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.BlockView;
import net.minecraft.world.RaycastContext;
import net.minecraft.world.World;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix4f;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * La luz de Takt sobre el suelo y las piezas: la vista previa de lo que R arrancaría, el contorno de un trozo ya
 * encerrado por cortes, las marcas de los cortes que el suelo recuerda, el fantasma de dónde se posaría lo que llevas
 * (con su línea de caída y el hilo desde la mano), la grieta que rodea un arranque, los anillos de zona, «lo cortado»,
 * impacto y vuelta a casa, la costura al posar, y el brillo de las piezas (borde y caras recién cortadas).
 * Lo propio (vista previa, contorno, fantasmas, hilos) solo lo ve quien lo hace; lo demás lo ve todo el que esté cerca.
 */
public final class TerrainFx {
	private TerrainFx() {}

	// colores del diseño (RGB)
	private static final int ROOM = 0x73D9FF, HOT = 0xC5E2FF, GROUND = 0x4F95FF, PARK = 0x58B0FF, MINT = 0x70FFD6, DUST = 0xE6DCC8;
	/** Bit de borde (columnas) o de cara al aire (celdas) en los datos empaquetados de un plan. */
	private static final int EDGE = 1 << 30;
	/** dx y dz de un dato empaquetado (Payloads.TaktFx.pack), sin dy ni el bit de borde. */
	private static final int XZ = (1 << 20) - 1;
	private static final int[] DX = PieceVisuals.DX, DY = PieceVisuals.DY, DZ = PieceVisuals.DZ;
	private static final float[] EMPTY = new float[0];
	private static final int NEVER = Integer.MIN_VALUE / 2;

	// presupuestos por fotograma (5.1)
	private static final int PREVIEW_MAX = 2000, FACE_MAX = 1500, FILL_MAX = 4000, SCAR_MAX = 600, SCARS_MAX = 4000, GHOST_FACES = 3000,
		GHOST_FOOTPRINT = 1500, CRACK_MAX = 1500, CRACKS_DRAWN = 3, CRACKS_KEPT = 16, RINGS_MAX = 8, SEAMS_MAX = 8,
		BEAMS_MAX = 6, GLOW_PIECES = 12;
	private static final double GLOW_RANGE = 96, SCAR_RANGE = 64;

	private static final int RING_SEG = 64;
	private static final double[] RCOS = new double[RING_SEG + 1], RSIN = new double[RING_SEG + 1];

	static {
		for (int i = 0; i <= RING_SEG; i++) {
			double a = Math.PI * 2 * i / RING_SEG;
			RCOS[i] = Math.cos(a);
			RSIN[i] = Math.sin(a);
		}
	}

	/** Trozos de línea (9 floats: a, b, normal de la cara) medidos desde una celda: así no se pierde precisión lejos del origen. */
	private record Lines(int ox, int oy, int oz, float[] seg) {
		static final Lines NONE = new Lines(0, 0, 0, new float[0]);

		boolean isEmpty() {
			return seg.length == 0;
		}

		int count() {
			return seg.length / 9;
		}

		/** La cámara vista desde la celda de origen. */
		Vec3d eye(Vec3d cam) {
			return new Vec3d(cam.x - ox, cam.y - oy, cam.z - oz);
		}
	}

	/** Lo que se va juntando de un contorno, hasta un máximo de trozos. */
	private static final class Seg {
		private float[] a = new float[64 * 9];
		private int n;
		private final int max;

		Seg(int max) {
			this.max = max;
		}

		boolean add(float ax, float ay, float az, float bx, float by, float bz, float fx, float fy, float fz) {
			if (n >= max) return false;
			if ((n + 1) * 9 > a.length) a = Arrays.copyOf(a, a.length * 2);
			int o = n * 9;
			a[o] = ax;
			a[o + 1] = ay;
			a[o + 2] = az;
			a[o + 3] = bx;
			a[o + 4] = by;
			a[o + 5] = bz;
			a[o + 6] = fx;
			a[o + 7] = fy;
			a[o + 8] = fz;
			n++;
			return true;
		}

		float[] done() {
			return Arrays.copyOf(a, n * 9);
		}
	}

	/** El fantasma de una pieza propia en la mano: dónde se posaría (4.6) y su dibujo, rehecho si cambia. */
	private static final class Ghost {
		@Nullable BlockPos key, base, geoBase;
		@Nullable BlockSet set, geoSet, extSet;
		int at = NEVER;
		int[] ext = new int[0];
		int[] faces = new int[0];
		Lines edges = Lines.NONE;
	}

	private static final class Scar {
		float[] seg = EMPTY;
		int at = NEVER;
	}

	/** La grieta de un arranque (TEAR_START), con el retraso de cada trozo para el frente que da la vuelta. */
	private static final class Crack {
		final int id;
		@Nullable final UUID owner;
		final boolean own;
		final int ticks;
		final double t0;
		final Lines lines;
		final float[] delay;
		double detachAt = -1, cancelAt = -1;

		Crack(int id, @Nullable UUID owner, boolean own, int ticks, double t0, Lines lines, float[] delay) {
			this.id = id;
			this.owner = owner;
			this.own = own;
			this.ticks = ticks;
			this.t0 = t0;
			this.lines = lines;
			this.delay = delay;
		}
	}

	private record Ring(int kind, double x, double y, double z, double a, double t0) {}

	private record Seam(Lines lines, double t0) {}

	private static World lastWorld;
	private static boolean warned;

	// vista previa y contorno (un solo plan para los dos)
	private static ChunkPlan plan = ChunkPlan.EMPTY;
	private static int planAt = NEVER;
	private static long keyPos;
	private static int keySize;
	private static int[] keyCuts = new int[0];
	private static float keyRadius;
	@Nullable private static Vec3d keyCenter;
	private static Lines planLines = Lines.NONE, idleLines = Lines.NONE;
	private static float[] planFill = EMPTY;
	private static boolean shown, idleOn;
	private static int count;
	private static boolean closed;
	private static double idleK, idleLastT = -1;

	private static final Int2ObjectOpenHashMap<Ghost> GHOSTS = new Int2ObjectOpenHashMap<>();
	private static int ghostTick = NEVER;
	private static final int[] NO_IDS = new int[0];
	/** Las piezas de Takt (y su orden) con las que se calcularon los fantasmas la última vez. */
	private static int[] ghostHeld = NO_IDS;
	private static final Int2ObjectOpenHashMap<Scar> SCARS = new Int2ObjectOpenHashMap<>();
	private static final List<Crack> CRACKS = new ArrayList<>();
	private static final List<Ring> RINGS = new ArrayList<>();
	private static final List<Seam> SEAMS = new ArrayList<>();

	// de cada fotograma
	private static final List<PieceVisuals> GLOW = new ArrayList<>();
	private static final List<PieceVisuals> BEAMS = new ArrayList<>();

	// ------------------------------------------------------------------ lo público

	/** Bloques del plan de la vista previa (del último plan calculado); 0 = ninguno. */
	public static int previewCount() {
		return count;
	}

	public static boolean previewClosed() {
		return closed;
	}

	/** ¿Se está dibujando la vista previa (este tick)? */
	public static boolean previewShown() {
		return shown;
	}

	/** Dónde se posaría esa pieza propia que llevas en la mano (esquina mínima), o null si no hay sitio o no hay fantasma. */
	@Nullable
	public static BlockPos ghostFor(int entityId) {
		Ghost g = GHOSTS.get(entityId);
		return g == null ? null : g.base;
	}

	/**
	 * El bloque de suelo al que apunta el jugador para Takt (4.2.7): el mismo rayo que el servidor (alcance de Takt,
	 * contorno de los bloques, sin líquidos, un trozo sin cargar no se toca). Null si no toca nada, si antes hay algo que
	 * Takt cogería (el servidor cogería eso) o si el punto no está dentro de un ROOM propio.
	 */
	@Nullable
	public static BlockHitResult aimGround() {
		MinecraftClient mc = MinecraftClient.getInstance();
		World w = mc.world;
		PlayerEntity p = mc.player;
		if (w == null || p == null) return null;
		BlockHitResult hit = raycastBlock(w, p, OpeConfig.TAKT_RANGE);
		if (hit.getType() == HitResult.Type.MISS) return null;
		if (grabbableFirst(w, p, hit.getPos().distanceTo(p.getEyePos()) + 0.5, 0.3)) return null;
		if (ClientState.ownRoomAt(hit.getPos()) == null) return null;
		return hit;
	}

	/** Un momento de Takt que contó el servidor (lo pasa TaktFxClient): grietas, anillos y la costura al posar. */
	public static void onTaktFx(Payloads.TaktFx fx) {
		MinecraftClient mc = MinecraftClient.getInstance();
		if (mc.world == null || fx == null) return;
		double t = RoomFxClient.time();
		try {
			switch (fx.kind()) {
				case Payloads.TaktFx.TEAR_START -> crack(mc, fx, t);
				case Payloads.TaktFx.DETACH -> {
					for (Crack c : CRACKS) if (c.id == fx.id() && sameOwner(c.owner, fx.owner()) && c.detachAt < 0 && c.cancelAt < 0) c.detachAt = t;
				}
				case Payloads.TaktFx.CANCEL -> {
					for (Crack c : CRACKS) if (c.id == fx.id() && sameOwner(c.owner, fx.owner()) && c.cancelAt < 0) c.cancelAt = t;
				}
				case Payloads.TaktFx.ZONE -> ring(new Ring(fx.kind(), fx.x(), fx.y() + 0.08, fx.z(), fx.a(), t));
				case Payloads.TaktFx.SWEEP -> {
					PlayerEntity owner = fx.owner() == null ? null : mc.world.getPlayerByUuid(fx.owner());
					Vec3d c = owner != null ? owner.getPos() : fx.pos();
					ring(new Ring(fx.kind(), c.x, c.y + 0.1, c.z, fx.a(), t));
				}
				case Payloads.TaktFx.IMPACT -> {
					if (fx.state() >= 1) ring(new Ring(fx.kind(), fx.x(), fx.y() + 0.1, fx.z(), fx.a(), t));
				}
				case Payloads.TaktFx.HOME -> {
					// el anillo, encima del sitio al que vuelve (su centro está bajo tierra en un trozo de suelo)
					double y = fx.y();
					if (mc.world.getEntityById(fx.entityId()) instanceof PieceEntity pe && pe.blocks != null) y += pe.blocks.sy / 2.0 + 0.05;
					ring(new Ring(fx.kind(), fx.x(), y, fx.z(), fx.a(), t));
				}
				case Payloads.TaktFx.LAND -> landSeam(fx, t);
				default -> {}
			}
		} catch (RuntimeException e) {
			warn(e);
		}
	}

	public static void clear() {
		plan = ChunkPlan.EMPTY;
		planAt = NEVER;
		keyCuts = new int[0];
		keyCenter = null;
		planLines = Lines.NONE;
		idleLines = Lines.NONE;
		planFill = EMPTY;
		shown = false;
		idleOn = false;
		idleK = 0;
		idleLastT = -1;
		count = 0;
		closed = false;
		GHOSTS.clear();
		ghostTick = NEVER;
		ghostHeld = NO_IDS;
		SCARS.clear();
		CRACKS.clear();
		RINGS.clear();
		SEAMS.clear();
		GLOW.clear();
		BEAMS.clear();
		PieceVisuals.DRAWN.clear();
		lastWorld = null;
	}

	// ------------------------------------------------------------------ cada tick

	public static void tick(MinecraftClient mc) {
		if (mc.world != lastWorld) {   // otra dimensión o reaparecer: nada de lo de antes sigue valiendo
			clear();
			lastWorld = mc.world;
		}
		if (mc.world == null || mc.player == null) return;
		int now = RoomFxClient.clientTicks();
		double t = RoomFxClient.time();
		try {
			tickAim(mc, now, t);
		} catch (RuntimeException e) {
			warn(e);
			shown = false;
			idleOn = false;
		}
		try {
			tickGhosts(mc, now);
		} catch (RuntimeException e) {
			warn(e);
		}
		try {
			tickScars(mc, now);
		} catch (RuntimeException e) {
			warn(e);
		}
		ageOut(t);
	}

	/** La vista previa (R mantenida, nada en la mano) y el contorno de un trozo cerrado (sin R): el mismo plan que el servidor. */
	private static void tickAim(MinecraftClient mc, int now, double t) {
		boolean rowR = InputHandler.rowR();
		BlockHitResult hit = ClientState.heldIds.length == 0 ? aimGround() : null;
		boolean busy = hit != null && ownCrackBusy(t);
		boolean wantPreview = rowR && ClientState.hasFruit() && mc.currentScreen == null && hit != null && !busy;
		boolean wantIdle = !rowR && hit != null && !busy;
		if (wantPreview || wantIdle) {
			BlockPos hb = hit.getBlockPos();
			ClientState.RoomInfo room = ClientState.ownRoomAt(hit.getPos());
			BlockPos aimS = ChunkPlanner.surfaceAim(mc.world, hb);
			List<GroundCut> near = aimS == null ? List.of() : ChunkPlanner.near(ClientState.ownGroundCuts(), mc.player.getUuid(),
				mc.world.getRegistryKey().getValue(), ChunkPlanner.aimPoint(aimS), mc.world.getTime());
			if (aimS == null || near.size() < 3) wantIdle = false;
			if (room == null) {
				wantPreview = false;
				wantIdle = false;
			}
			if (wantPreview || wantIdle) replan(mc, hb, room, near, now);
		}
		shown = wantPreview && !plan.isEmpty();
		idleOn = wantIdle && plan.closed && !plan.face && !planLines.isEmpty();
		if (idleOn) idleLines = planLines;
	}

	/**
	 * Recalcula el plan en cuanto cambia lo que lo decide (como mucho una vez por tick: lo dibujado es siempre lo que
	 * se arrancaría al soltar) y, si nada cambia, cada 20 ticks de todos modos.
	 */
	private static void replan(MinecraftClient mc, BlockPos hb, ClientState.RoomInfo room, List<GroundCut> near, int now) {
		int size2 = ClientState.chunkSize2();
		int[] ids = new int[near.size()];
		for (int i = 0; i < ids.length; i++) ids[i] = near.get(i).id;
		boolean same = planAt != NEVER && keyPos == hb.asLong() && keySize == size2 && Arrays.equals(keyCuts, ids)
			&& keyRadius == room.radius() && room.center().equals(keyCenter);
		if (same && now - planAt < 20) return;
		plan = ChunkPlanner.plan(mc.world, hb, size2, near, room.center(), (double) room.radius(), mc.player.getBoundingBox());
		planAt = now;
		keyPos = hb.asLong();
		keySize = size2;
		keyCuts = ids;
		keyRadius = room.radius();
		keyCenter = room.center();
		count = plan.size();
		closed = plan.closed;
		BlockPos aim = plan.aim;
		if (plan.isEmpty() || aim == null) {
			planLines = Lines.NONE;
			planFill = EMPTY;
		} else if (plan.face) {
			planLines = faceLines(mc.world, aim.getX(), aim.getY(), aim.getZ(), plan.cells, FACE_MAX);
			planFill = EMPTY;
		} else {
			planLines = columnLines(aim.getX(), aim.getY(), aim.getZ(), plan.columns, false, true, PREVIEW_MAX);
			planFill = fill(plan.columns);
		}
	}

	/** Una grieta propia muy reciente o aún pendiente: la vista previa espera (si no, taparía la grieta). */
	private static boolean ownCrackBusy(double t) {
		for (Crack c : CRACKS) {
			if (!c.own) continue;
			double tau = t - c.t0;
			if (tau < 0.5 || pending(c, tau)) return true;
		}
		return false;
	}

	/** Sin DETACH ni CANCEL todavía y dentro de su espera (el doble toque puede llegar). */
	private static boolean pending(Crack c, double tau) {
		return c.detachAt < 0 && c.cancelAt < 0 && tau < c.ticks / 20.0 + 0.5;
	}

	/** Cada 2 ticks: el fantasma de cada pieza propia en la mano (solo en modo mano), en caché por celda y BlockSet. */
	private static void tickGhosts(MinecraftClient mc, int now) {
		int[] held = ClientState.heldIds;
		if (held.length == 0 || ClientState.taktMode != 0) {
			GHOSTS.clear();
			ghostHeld = NO_IDS;
			return;
		}
		if (!GHOSTS.isEmpty()) {
			for (int id : GHOSTS.keySet().toIntArray()) if (!contains(held, id)) GHOSTS.remove(id);
		}
		if (now - ghostTick < 2) return;
		ghostTick = now;
		// En el orden de Takt, cada fantasma aparta su sitio y el siguiente queda encima o al lado, nunca dentro: lo mismo
		// que hará el servidor al posar (Takt.setDown). Si uno cambia, se rehacen los que van detrás.
		boolean redo = !Arrays.equals(held, ghostHeld);
		ghostHeld = held;
		Long2ObjectOpenHashMap<BlockState> taken = new Long2ObjectOpenHashMap<>();
		BlockView view = Landing.reserving(mc.world, taken);
		for (int id : held) {
			Entity en = mc.world.getEntityById(id);
			if (!(en instanceof PieceEntity pe) || pe.isWeaponShape() || pe.blocks == null || pe.blocks.isEmpty()
				|| pe.blocks.size() > OpeConfig.TAKT_CHUNK_MAX_BLOCKS) {
				if (GHOSTS.remove(id) != null) redo = true;
				continue;
			}
			Ghost g = GHOSTS.get(id);
			if (g == null) {
				g = new Ghost();
				GHOSTS.put(id, g);
			}
			Vec3d corner = pe.corner();
			BlockPos key = Landing.snap(corner);
			if (redo || !key.equals(g.key) || pe.blocks != g.set || now - g.at >= 10) {
				BlockPos base = Landing.find(view, pe.blocks, corner, Landing.blockers(mc.world, Landing.region(pe.blocks, corner)));
				if (!Objects.equals(base, g.base)) redo = true;
				g.base = base;
				g.key = key;
				g.set = pe.blocks;
				g.at = now;
			}
			if (g.base != null) Landing.reserve(taken, pe.blocks, g.base);
		}
	}

	/** Las marcas de los cortes recordados cerca de la cámara: como mucho 2 por tick, cada una rehecha cada 40 ticks. */
	private static void tickScars(MinecraftClient mc, int now) {
		if (!SCARS.isEmpty()) {
			for (int id : SCARS.keySet().toIntArray()) if (!ClientState.GROUND_CUTS.containsKey(id)) SCARS.remove(id);
		}
		if (ClientState.GROUND_CUTS.isEmpty()) return;
		Identifier dim = mc.world.getRegistryKey().getValue();
		Vec3d cam = camera(mc);
		GroundCut first = null, second = null;
		long ageFirst = -1, ageSecond = -1;
		for (GroundCut c : ClientState.GROUND_CUTS.values()) {
			if (!dim.equals(c.dim) || boxDist2(c.box, cam) > SCAR_RANGE * SCAR_RANGE) continue;
			Scar s = SCARS.get(c.id);
			long age = s == null ? Long.MAX_VALUE : (long) now - s.at;
			if (age < 40) continue;
			if (age > ageFirst) {
				second = first;
				ageSecond = ageFirst;
				first = c;
				ageFirst = age;
			} else if (age > ageSecond) {
				second = c;
				ageSecond = age;
			}
		}
		if (first != null) scar(mc.world, first, now);
		if (second != null) scar(mc.world, second, now);
	}

	private static void scar(World w, GroundCut c, int now) {
		Box b = c.box;
		double hx = (b.maxX - b.minX) / 2, hy = (b.maxY - b.minY) / 2, hz = (b.maxZ - b.minZ) / 2;
		double lr = clamp(Math.sqrt(hx * hx + hy * hy + hz * hz) + 1.5, 2, 32);
		float[] seg = CutFx.segments(w, c.n, c.p, new CutFx.Zone(c.c, c.r, b.getCenter(), lr, c.eye, c.look), SCAR_MAX, 32);
		Scar s = SCARS.get(c.id);
		if (s == null) {
			s = new Scar();
			SCARS.put(c.id, s);
		}
		s.seg = seg;
		s.at = now;
	}

	/** Lo que ya acabó se va: grietas, anillos y costuras. */
	private static void ageOut(double t) {
		CRACKS.removeIf(c -> crackFade(c, t) <= 0);
		boolean rm = OpeSettings.reducedMotion();
		RINGS.removeIf(r -> t - r.t0() > ringLife(r, rm));
		SEAMS.removeIf(s -> t - s.t0() > 1.2);
	}

	// ------------------------------------------------------------------ eventos

	/** TEAR_START: el contorno del plan que se arranca (columnas de borde o caras al aire) y su retraso alrededor. */
	private static void crack(MinecraftClient mc, Payloads.TaktFx fx, double t) {
		int[] data = fx.data() == null ? new int[0] : fx.data();
		int bx = (int) Math.floor(fx.x()), by = (int) Math.floor(fx.y()), bz = (int) Math.floor(fx.z());
		Lines lines = (fx.flags() & Payloads.TaktFx.F_FACE) != 0
			? faceLines(mc.world, bx, by, bz, data, CRACK_MAX)
			: columnLines(bx, by - 1, bz, data, true, true, CRACK_MAX);
		float[] s = lines.seg();
		int n = lines.count();
		float[] delay = new float[n];
		for (int i = 0; i < n; i++) {
			int o = i * 9;
			// el frente sale del lado de quien arranca y da la vuelta: el retraso crece con el ángulo
			double mx = lines.ox() + (s[o] + s[o + 3]) * 0.5, mz = lines.oz() + (s[o + 2] + s[o + 5]) * 0.5;
			double ang = Math.atan2(mz - fx.z(), mx - fx.x()) - fx.b();
			ang -= Math.PI * 2 * Math.floor((ang + Math.PI) / (Math.PI * 2));
			delay[i] = (float) (0.17 * Math.abs(ang) / Math.PI);
		}
		UUID me = mc.player == null ? null : mc.player.getUuid();
		CRACKS.add(new Crack(fx.id(), fx.owner(), me != null && me.equals(fx.owner()), Math.max(0, fx.ticks()), t, lines, delay));
		while (CRACKS.size() > CRACKS_KEPT) CRACKS.remove(0);
	}

	/** LAND: la costura donde la pieza toca el suelo, por fuera de su cara de abajo. */
	private static void landSeam(Payloads.TaktFx fx, double t) {
		int[] data = fx.data() == null ? new int[0] : fx.data();
		if (data.length == 0) return;
		// El aviso trae como mucho 512 celdas: de una pieza más grande, toda su cara de abajo (la entidad sigue ahí unos
		// ticks); si no, la costura saldría partida por la mitad.
		MinecraftClient mc = MinecraftClient.getInstance();
		if (data.length >= 512 && mc.world != null && mc.world.getEntityById(fx.entityId()) instanceof PieceEntity pe && pe.blocks != null) {
			List<BlockSet.Entry> under = pe.blocks.underside();
			if (under.size() > data.length) {
				data = new int[under.size()];
				for (int i = 0; i < data.length; i++) {
					BlockSet.Entry e = under.get(i);
					data[i] = Payloads.TaktFx.pack(e.x(), e.z(), e.y());
				}
			}
		}
		int bx = (int) Math.floor(fx.x() + 0.5), by = (int) Math.floor(fx.y() + 0.5), bz = (int) Math.floor(fx.z() + 0.5);
		Lines lines = columnLines(bx, by, bz, data, false, false, CRACK_MAX);
		if (lines.isEmpty()) return;
		SEAMS.add(new Seam(lines, t));
		while (SEAMS.size() > SEAMS_MAX) SEAMS.remove(0);
	}

	private static void ring(Ring r) {
		RINGS.add(r);
		while (RINGS.size() > RINGS_MAX) RINGS.remove(0);
	}

	/** La de un impacto dura más cuanto más grande es el cráter (a): la onda de un cráter de 20 bloques no cabe en 0,4 s. */
	private static double ringLife(Ring r, boolean rm) {
		double life = ringLife(r.kind(), rm);
		return r.kind() == Payloads.TaktFx.IMPACT && !rm ? life + 0.02 * Math.max(0, r.a()) : life;
	}

	private static double ringLife(int kind, boolean rm) {
		if (rm) return 0.3;
		return switch (kind) {
			case Payloads.TaktFx.ZONE -> 0.65;
			case Payloads.TaktFx.SWEEP -> 0.6;
			case Payloads.TaktFx.IMPACT -> 0.4;
			default -> 0.5;
		};
	}

	/** Cuánto queda de una grieta: 2 s más tras soltarse y luego 0,5 s apagándose; 0,3 s si se deshace; 6 s como mucho. */
	private static double crackFade(Crack c, double t) {
		double tau = t - c.t0;
		if (tau > 6) return 0;
		double f = Math.min(1, (6 - tau) / 0.5);
		if (c.cancelAt >= 0) f = Math.min(f, 1 - (t - c.cancelAt) / 0.3);
		else if (c.detachAt >= 0 && t - c.detachAt > 2) f = Math.min(f, 1 - (t - c.detachAt - 2) / 0.5);
		return Math.max(0, f);
	}

	// ------------------------------------------------------------------ contornos

	/**
	 * El contorno de un conjunto de columnas (dx, dz, dy desde la celda de origen): un trozo por cada lado sin columna
	 * vecina, en el borde de arriba de la columna (top) o en el de abajo. edgeOnly: solo las columnas con el bit de borde
	 * (los datos de una grieta pueden venir cortados).
	 */
	private static Lines columnLines(int ox, int oy, int oz, int[] cols, boolean edgeOnly, boolean top, int max) {
		IntOpenHashSet have = new IntOpenHashSet(Math.max(16, cols.length * 2));
		for (int c : cols) have.add(c & XZ);
		Seg out = new Seg(max);
		for (int c : cols) {
			if (edgeOnly && (c & EDGE) == 0) continue;
			int dx = Payloads.TaktFx.dx(c), dz = Payloads.TaktFx.dz(c);
			float y = Payloads.TaktFx.dy(c) + (top ? 1 : 0);
			if (!have.contains(Payloads.TaktFx.pack(dx - 1, dz, 0) & XZ) && !out.add(dx, y, dz, dx, y, dz + 1, 0, 1, 0)) break;
			if (!have.contains(Payloads.TaktFx.pack(dx + 1, dz, 0) & XZ) && !out.add(dx + 1, y, dz, dx + 1, y, dz + 1, 0, 1, 0)) break;
			if (!have.contains(Payloads.TaktFx.pack(dx, dz - 1, 0) & XZ) && !out.add(dx, y, dz, dx + 1, y, dz, 0, 1, 0)) break;
			if (!have.contains(Payloads.TaktFx.pack(dx, dz + 1, 0) & XZ) && !out.add(dx, y, dz + 1, dx + 1, y, dz + 1, 0, 1, 0)) break;
		}
		return new Lines(ox, oy, oz, out.done());
	}

	/** La tapa de cada columna del plan (x, y, z de su esquina, desde aim), para el relleno suave de la vista previa. */
	private static float[] fill(int[] cols) {
		int n = Math.min(cols.length, FILL_MAX);
		float[] out = new float[n * 3];
		for (int i = 0; i < n; i++) {
			int c = cols[i];
			out[i * 3] = Payloads.TaktFx.dx(c);
			out[i * 3 + 1] = Payloads.TaktFx.dy(c) + 1;
			out[i * 3 + 2] = Payloads.TaktFx.dz(c);
		}
		return out;
	}

	/**
	 * El contorno de un plan de cara (celdas dx, dz, dy desde la celda de origen, bit de cara al aire): cada cara al aire
	 * (vecina fuera del plan y reemplazable en el mundo) con sus aristas, salvo donde la misma cara sigue en la celda de
	 * al lado; 0,01 hacia fuera.
	 */
	private static Lines faceLines(BlockView w, int ox, int oy, int oz, int[] cells, int max) {
		LongOpenHashSet in = new LongOpenHashSet(Math.max(16, cells.length * 2));
		for (int c : cells) in.add(BlockPos.asLong(Payloads.TaktFx.dx(c), Payloads.TaktFx.dy(c), Payloads.TaktFx.dz(c)));
		BlockPos.Mutable q = new BlockPos.Mutable();
		Seg out = new Seg(max);
		outer:
		for (int c : cells) {
			if ((c & EDGE) == 0) continue;
			int x = Payloads.TaktFx.dx(c), y = Payloads.TaktFx.dy(c), z = Payloads.TaktFx.dz(c);
			for (int d = 0; d < 6; d++) {
				if (!open(w, in, q, ox, oy, oz, x + DX[d], y + DY[d], z + DZ[d])) continue;
				for (int e = 0; e < 6; e++) {
					if ((e >> 1) == (d >> 1)) continue;   // la misma dirección o la contraria: no es una arista de esta cara
					int ex = x + DX[e], ey = y + DY[e], ez = z + DZ[e];
					if (in.contains(BlockPos.asLong(ex, ey, ez)) && open(w, in, q, ox, oy, oz, ex + DX[d], ey + DY[d], ez + DZ[d])) continue;
					if (!edge(out, x, y, z, d, e, 0.01f, DX[d], DY[d], DZ[d])) break outer;
				}
			}
		}
		return new Lines(ox, oy, oz, out.done());
	}

	/** ¿La celda (relativa) está fuera del plan y es aire o algo reemplazable en el mundo? */
	private static boolean open(BlockView w, LongOpenHashSet in, BlockPos.Mutable q, int ox, int oy, int oz, int x, int y, int z) {
		if (in.contains(BlockPos.asLong(x, y, z))) return false;
		return GroundRules.replaceable(w.getBlockState(q.set(ox + x, oy + y, oz + z)));
	}

	/** La arista de la cara (celda, lado d) que da hacia e, apartada push a lo largo de d, con la normal (fx, fy, fz). */
	private static boolean edge(Seg out, int x, int y, int z, int d, int e, float push, float fx, float fy, float fz) {
		float cx = x + 0.5f + 0.5f * DX[d] + 0.5f * DX[e] + push * DX[d];
		float cy = y + 0.5f + 0.5f * DY[d] + 0.5f * DY[e] + push * DY[d];
		float cz = z + 0.5f + 0.5f * DZ[d] + 0.5f * DZ[e] + push * DZ[d];
		// a lo largo de la arista: d × e
		int lx = DY[d] * DZ[e] - DZ[d] * DY[e], ly = DZ[d] * DX[e] - DX[d] * DZ[e], lz = DX[d] * DY[e] - DY[d] * DX[e];
		return out.add(cx - 0.5f * lx, cy - 0.5f * ly, cz - 0.5f * lz, cx + 0.5f * lx, cy + 0.5f * ly, cz + 0.5f * lz, fx, fy, fz);
	}

	/** Las caras y aristas del fantasma, rehechas solo cuando cambia su sitio o su BlockSet. */
	private static void ghostGeometry(Ghost g, PieceEntity pe) {
		BlockSet set = g.set;
		BlockPos base = g.base;
		if (set == null || base == null) return;
		if (set == g.geoSet && base.equals(g.geoBase)) return;
		int[] ext;
		if (pe.renderState instanceof PieceVisuals v && v.set == set) {
			ext = v.ext;
		} else {
			if (g.extSet != set) {
				g.ext = PieceVisuals.exterior(set);
				g.extSet = set;
			}
			ext = g.ext;
		}
		boolean footprint = ext.length > GHOST_FOOTPRINT;   // muy grande: solo la huella
		IntArrayList faces = new IntArrayList(footprint ? 256 : ext.length);
		for (int code : ext) if (!footprint || (code & 7) == 0) faces.add(code);
		Seg out = new Seg(faces.size() * 4);
		for (int i = 0; i < faces.size(); i++) {
			int code = faces.getInt(i);
			BlockSet.Entry b = set.entries.get(code >> 3);
			int d = code & 7;
			for (int e = 0; e < 6; e++) {
				if ((e >> 1) == (d >> 1)) continue;
				int ex = b.x() + DX[e], ey = b.y() + DY[e], ez = b.z() + DZ[e];
				// la misma cara de fuera sigue en la celda de al lado: no es borde
				if (set.contains(ex, ey, ez) && !set.contains(ex + DX[d], ey + DY[d], ez + DZ[d])) continue;
				// la huella va un pelo por encima del suelo (si no, el suelo la taparía)
				if (d == 0) edge(out, b.x(), b.y(), b.z(), d, e, -0.002f, 0, 1, 0);
				else edge(out, b.x(), b.y(), b.z(), d, e, 0.002f, DX[d], DY[d], DZ[d]);
			}
		}
		g.faces = faces.toIntArray();
		g.edges = new Lines(base.getX(), base.getY(), base.getZ(), out.done());
		g.geoSet = set;
		g.geoBase = base;
	}

	// ------------------------------------------------------------------ dibujo

	public static void render(WorldRenderContext ctx) {
		MinecraftClient mc = MinecraftClient.getInstance();
		try {
			if (mc.world == null || mc.player == null) return;
			Vec3d cam = ctx.camera().getPos();
			double t = RoomFxClient.time();
			float td = ctx.tickCounter().getTickDelta(true);
			boolean any;
			try {
				any = prepare(mc, cam, t);
			} catch (RuntimeException e) {
				warn(e);
				return;
			}
			if (!any) return;
			Matrix4f m = CutFx.isIdentity(RenderSystem.getModelViewMatrix()) ? new Matrix4f(ctx.positionMatrix()) : new Matrix4f();
			RenderSystem.enableBlend();
			RenderSystem.disableCull();
			RenderSystem.enableDepthTest();
			RenderSystem.depthMask(false);
			RenderSystem.setShader(GameRenderer::getPositionColorProgram);
			try {
				// «Contraste»: primero las sombras oscuras bajo las líneas del suelo, con mezcla normal
				if (OpeSettings.highContrast()) {
					RenderSystem.defaultBlendFunc();
					pass(mc, m, cam, t, td, true);
				}
				RenderSystem.blendFunc(GlStateManager.SrcFactor.SRC_ALPHA, GlStateManager.DstFactor.ONE);   // luz: se suma
				pass(mc, m, cam, t, td, false);
			} finally {
				RenderSystem.depthMask(true);
				RenderSystem.enableCull();
				RenderSystem.defaultBlendFunc();
				RenderSystem.disableBlend();
			}
		} finally {
			PieceVisuals.DRAWN.clear();
			PieceVisuals.stamp++;
			GLOW.clear();
			BEAMS.clear();
		}
	}

	/** Lo de este fotograma: el fundido del contorno, las piezas que brillan y los hilos. ¿Hay algo que dibujar? */
	private static boolean prepare(MinecraftClient mc, Vec3d cam, double t) {
		double dt = idleLastT < 0 ? 0 : clamp(t - idleLastT, 0, 0.1);
		idleLastT = t;
		idleK = idleOn ? Math.min(1, idleK + dt / 0.25) : Math.max(0, idleK - dt / 0.5);

		GLOW.clear();
		BEAMS.clear();
		for (PieceVisuals v : PieceVisuals.DRAWN) {
			if (v.pos.squaredDistanceTo(cam) > GLOW_RANGE * GLOW_RANGE) continue;
			int f = v.e.flags();
			if (rim(v.e, f) != 0 || (f & PieceEntity.F_FRESH) != 0) GLOW.add(v);
		}
		if (GLOW.size() > GLOW_PIECES) {
			GLOW.sort(Comparator.comparingDouble(v -> v.pos.squaredDistanceTo(cam)));
			GLOW.subList(GLOW_PIECES, GLOW.size()).clear();
		}
		if (ClientState.taktMode == 0) {
			for (int id : ClientState.heldIds) {
				PieceVisuals v = drawn(id);
				if (v != null) BEAMS.add(v);
				if (BEAMS.size() >= BEAMS_MAX) break;
			}
		}
		return shown || idleK > 0 || !SCARS.isEmpty() || !GHOSTS.isEmpty() || !CRACKS.isEmpty() || !RINGS.isEmpty()
			|| !SEAMS.isEmpty() || !GLOW.isEmpty() || !BEAMS.isEmpty();
	}

	/** Una tanda de dibujo: under = las sombras de «Contraste»; si no, la luz. */
	private static void pass(MinecraftClient mc, Matrix4f m, Vec3d cam, double t, float td, boolean under) {
		BufferBuilder buf = Tessellator.getInstance().begin(VertexFormat.DrawMode.QUADS, VertexFormats.POSITION_COLOR);
		try {
			try {
				drawPreview(buf, m, cam, t, under);
				drawIdle(buf, m, cam, t, under);
				drawScars(buf, m, cam, mc, t, under);
				drawGhosts(buf, m, cam, mc, t, td, under);
				drawCracks(buf, m, cam, t, under);
				drawSeams(buf, m, cam, t, under);
				if (!under) {
					drawRings(buf, m, cam, t);
					drawBeams(buf, m, cam, mc, t, td);
					drawGlow(buf, m, cam, mc, t);
				}
			} catch (RuntimeException e) {
				warn(e);
			}
		} finally {
			var built = buf.endNullable();
			if (built != null) BufferRenderer.drawWithGlobalProgram(built);
		}
	}

	/** La vista previa: el contorno de lo que R arrancaría (más claro si está cerrado) y una tapa muy suave. */
	private static void drawPreview(BufferBuilder buf, Matrix4f m, Vec3d cam, double t, boolean under) {
		if (!shown || planLines.isEmpty()) return;
		Vec3d eye = planLines.eye(cam);
		if (under) {
			underlay(buf, m, eye, planLines.seg(), 0.05, 1);
			return;
		}
		int rgb = closed ? HOT : ROOM;
		CutFx.flat(buf, m, eye, planLines.seg(), 0.16, r(rgb), g(rgb), b(rgb), A(hc(0.25)));
		CutFx.flat(buf, m, eye, planLines.seg(), 0.05, r(rgb), g(rgb), b(rgb), A(hc(0.9 * (0.75 + 0.25 * pulse(1.2, 0, t)))));
		int fa = A(hc(0.06));
		if (fa <= 0) return;
		for (int i = 0; i + 2 < planFill.length; i += 3) {
			float x = (float) (planFill[i] - eye.x), y = (float) (planFill[i + 1] + 0.01 - eye.y), z = (float) (planFill[i + 2] - eye.z);
			buf.vertex(m, x, y, z).color(r(ROOM), g(ROOM), b(ROOM), fa);
			buf.vertex(m, x, y, z + 1).color(r(ROOM), g(ROOM), b(ROOM), fa);
			buf.vertex(m, x + 1, y, z + 1).color(r(ROOM), g(ROOM), b(ROOM), fa);
			buf.vertex(m, x + 1, y, z).color(r(ROOM), g(ROOM), b(ROOM), fa);
		}
	}

	/** El contorno de un trozo ya cerrado por tus cortes, cuando miras ahí sin R: entra y sale despacio. */
	private static void drawIdle(BufferBuilder buf, Matrix4f m, Vec3d cam, double t, boolean under) {
		if (idleK <= 0 || idleLines.isEmpty()) return;
		Vec3d eye = idleLines.eye(cam);
		if (under) {
			underlay(buf, m, eye, idleLines.seg(), 0.05, idleK);
			return;
		}
		double a = 0.35 * (0.6 + 0.4 * (2 * pulse(2.09, 0, t) - 1)) * idleK;
		CutFx.flat(buf, m, eye, idleLines.seg(), 0.05, r(HOT), g(HOT), b(HOT), A(hc(a)));
	}

	/** Las marcas de los cortes que el suelo recuerda: aparecen cuando se apaga la costura y se van al caducar. */
	private static void drawScars(BufferBuilder buf, Matrix4f m, Vec3d cam, MinecraftClient mc, double t, boolean under) {
		if (SCARS.isEmpty()) return;
		UUID me = mc.player.getUuid();
		long now = mc.world.getTime();
		Identifier dim = mc.world.getRegistryKey().getValue();
		int budget = SCARS_MAX;
		for (GroundCut c : ClientState.GROUND_CUTS.values()) {
			Scar s = SCARS.get(c.id);
			if (s == null || s.seg.length == 0 || !dim.equals(c.dim) || boxDist2(c.box, cam) > SCAR_RANGE * SCAR_RANGE) continue;
			int n = s.seg.length / 9;
			if (n > budget) break;
			double ageS = (now - c.created) / 20.0;
			double fade = smoothstep(1.2, 2.0, ageS)
				* clamp((c.created + OpeConfig.GROUND_CUT_TICKS - now) / (double) OpeConfig.GROUND_CUT_FADE_TICKS, 0, 1);
			if (fade <= 0) continue;
			budget -= n;
			if (under) {
				underlay(buf, m, cam, s.seg, 0.035, fade);
				continue;
			}
			double a = 0.18 * (0.75 + 0.25 * (2 * pulse(3.2, c.id, t) - 1)) * fade * (me.equals(c.owner) ? 1 : 0.6);
			CutFx.flat(buf, m, cam, s.seg, 0.035, r(ROOM), g(ROOM), b(ROOM), A(hc(a)));
		}
	}

	/** Dónde se posaría lo que llevas: caras muy suaves, sus bordes, y la línea de caída a trazos desde la pieza. */
	private static void drawGhosts(BufferBuilder buf, Matrix4f m, Vec3d cam, MinecraftClient mc, double t, float td, boolean under) {
		if (GHOSTS.isEmpty() || ClientState.taktMode != 0) return;
		int budget = GHOST_FACES;
		for (int id : ClientState.heldIds) {
			Ghost g = GHOSTS.get(id);
			if (g == null || g.base == null || g.set == null) continue;
			if (!(mc.world.getEntityById(id) instanceof PieceEntity pe) || pe.blocks != g.set) continue;
			ghostGeometry(g, pe);
			if (g.faces.length > budget) continue;
			budget -= g.faces.length;
			Vec3d eye = g.edges.eye(cam);
			if (under) {
				underlay(buf, m, eye, g.edges.seg(), 0.03, 1);
				continue;
			}
			BlockPos base = g.base;
			Matrix4f at = new Matrix4f(m).translate((float) (base.getX() - cam.x), (float) (base.getY() - cam.y), (float) (base.getZ() - cam.z));
			int fa = A(hc(0.08));
			for (int code : g.faces) {
				BlockSet.Entry b = g.set.entries.get(code >> 3);
				int d = code & 7;
				face(buf, at, b.x(), b.y(), b.z(), d, d == 0 ? -0.002f : 0.002f, ROOM, fa);
			}
			CutFx.flat(buf, m, eye, g.edges.seg(), 0.03, r(ROOM), g(ROOM), b(ROOM), A(hc(0.5)));
			dropLine(buf, m, cam, pe, g, t, td);
		}
	}

	/** De la base de la pieza a la tapa de su fantasma, a trazos que bajan (quietos con «Menos movimiento»). */
	private static void dropLine(BufferBuilder buf, Matrix4f m, Vec3d cam, PieceEntity pe, Ghost g, double t, float td) {
		BlockSet set = g.set;
		BlockPos base = g.base;
		if (set == null || base == null) return;
		PieceVisuals v = drawn(pe.getId());
		Vec3d s = v != null ? v.pos : pe.getLerpedPos(td);
		double ex = base.getX() + set.sx / 2.0, ey = base.getY() + set.sy, ez = base.getZ() + set.sz / 2.0;
		double dx = ex - s.x, dy = ey - s.y, dz = ez - s.z;
		double len = Math.sqrt(dx * dx + dy * dy + dz * dz);
		if (len < 0.5) return;
		double ux = dx / len, uy = dy / len, uz = dz / len;
		double off = OpeSettings.reducedMotion() ? 0 : t % 0.5;
		int a = A(hc(0.45));
		for (double d0 = off - 0.5; d0 < len; d0 += 0.5) {
			double a0 = Math.max(0, d0), a1 = Math.min(len, d0 + 0.3);
			if (a1 - a0 < 0.02) continue;
			beam(buf, m, cam, s.x + ux * a0, s.y + uy * a0, s.z + uz * a0, s.x + ux * a1, s.y + uy * a1, s.z + uz * a1,
				0.03, 0.03, ROOM, a, ROOM, a);
		}
	}

	/** Las grietas (las 3 más nuevas): cada trozo se enciende cuando le llega el frente y se apaga despacio. */
	private static void drawCracks(BufferBuilder buf, Matrix4f m, Vec3d cam, double t, boolean under) {
		boolean rm = OpeSettings.reducedMotion();
		int drawn = 0;
		for (int k = CRACKS.size() - 1; k >= 0 && drawn < CRACKS_DRAWN; k--) {
			Crack c = CRACKS.get(k);
			double fade = crackFade(c, t);
			if (fade <= 0 || c.lines.isEmpty()) continue;
			drawn++;
			double tau = t - c.t0;
			boolean pending = pending(c, tau);
			Vec3d eye = c.lines.eye(cam);
			float[] s = c.lines.seg();
			for (int i = 0; i < c.delay.length; i++) {
				double d = rm ? 0 : c.delay[i];
				double in = tau < d ? 0 : tile(tau - d);
				if (pending && tau >= d) in = Math.max(in, 0.55);   // la grieta sigue encendida mientras espera el doble toque
				in *= fade;
				if (in <= 0.002) continue;
				int o = i * 9;
				if (under) {
					seg1(buf, m, eye, s, o, 0.08 * 1.8, 0, 0, 0, A(0.45 * Math.min(1, in)));
					continue;
				}
				int rgb = mix(HOT, ROOM, smoothstep(0, 0.25, tau - d));
				seg1(buf, m, eye, s, o, 0.22, r(ROOM), g(ROOM), b(ROOM), A(hc(flash(0.35 * in))));
				seg1(buf, m, eye, s, o, 0.08, r(rgb), g(rgb), b(rgb), A(hc(flash(0.9 * in))));
			}
		}
	}

	/** La costura menta donde se posa una pieza. */
	private static void drawSeams(BufferBuilder buf, Matrix4f m, Vec3d cam, double t, boolean under) {
		for (Seam s : SEAMS) {
			double tau = t - s.t0();
			if (tau < 0 || tau > 1.2) continue;
			double k = 1 - smoothstep(0, 1.2, tau);
			Vec3d eye = s.lines().eye(cam);
			if (under) {
				underlay(buf, m, eye, s.lines().seg(), 0.06, k);
				continue;
			}
			CutFx.flat(buf, m, eye, s.lines().seg(), 0.2, r(MINT), g(MINT), b(MINT), A(hc(flash(0.3 * k))));
			CutFx.flat(buf, m, eye, s.lines().seg(), 0.06, r(MINT), g(MINT), b(MINT), A(hc(flash(0.9 * k))));
		}
	}

	/** Los anillos: zona, «lo cortado», impacto y vuelta a casa. Con «Menos movimiento», solo el anillo final apagándose. */
	private static void drawRings(BufferBuilder buf, Matrix4f m, Vec3d cam, double t) {
		boolean rm = OpeSettings.reducedMotion();
		for (Ring rg : RINGS) {
			double age = t - rg.t0();
			double life = ringLife(rg, rm);
			if (age < 0 || age > life) continue;
			double k = 1 - age / life;   // lo que queda de luz
			double grow;                 // 0..1 del radio
			switch (rg.kind()) {
				case Payloads.TaktFx.ZONE -> {
					grow = rm ? 1 : easeOutCubic(Math.min(1, age / 0.35));
					double rad = rg.a() * grow;
					ring(buf, m, cam, rg.x(), rg.y(), rg.z(), rad, 0.35, ROOM, A(hc(flash(0.25 * k))));
					ring(buf, m, cam, rg.x(), rg.y(), rg.z(), rad, 0.10, ROOM, A(hc(flash(0.6 * k))));
				}
				case Payloads.TaktFx.SWEEP -> {
					grow = rm ? 1 : easeOutCubic(age / 0.6);
					double rad = rg.a() * grow;
					ring(buf, m, cam, rg.x(), rg.y(), rg.z(), rad, 0.4, ROOM, A(hc(flash(0.2 * k))));
					ring(buf, m, cam, rg.x(), rg.y(), rg.z(), rad, 0.12, ROOM, A(hc(flash(0.5 * k))));
				}
				case Payloads.TaktFx.IMPACT -> {
					grow = rm ? 1 : easeOutCubic(Math.min(1, age / life));   // se abre durante toda su vida (más larga si es grande)
					double rad = 2.2 * Math.max(rg.a(), 1.5) * grow;
					ring(buf, m, cam, rg.x(), rg.y(), rg.z(), rad, 0.25, DUST, A(hc(flash(0.5 * k))));
				}
				default -> {   // HOME: se cierra sobre su sitio
					double rad = rm ? rg.a() : rg.a() * (1.2 - 0.2 * Math.min(1, age / 0.5));
					ring(buf, m, cam, rg.x(), rg.y(), rg.z(), rad, 0.2, MINT, A(hc(flash(0.2 * k))));
					ring(buf, m, cam, rg.x(), rg.y(), rg.z(), rad, 0.06, MINT, A(hc(flash(0.5 * k))));
				}
			}
		}
	}

	/** El hilo de luz de la mano a cada pieza que llevas (modo mano), curvado hacia arriba y con pulsos que van hacia ella. */
	private static void drawBeams(BufferBuilder buf, Matrix4f m, Vec3d cam, MinecraftClient mc, double t, float td) {
		if (BEAMS.isEmpty()) return;
		PlayerEntity p = mc.player;
		Vec3d look = p.getRotationVec(td);
		Vec3d right = new Vec3d(-look.z, 0, look.x);   // look × (0, 1, 0)
		if (right.lengthSquared() < 1e-6) {
			double yaw = Math.toRadians(p.getYaw(td));
			right = new Vec3d(-Math.cos(yaw), 0, -Math.sin(yaw));
		} else {
			right = right.normalize();
		}
		Vec3d up = right.crossProduct(look);
		Vec3d s = cam.distanceTo(p.getCameraPosVec(td)) < 0.5
			? cam.add(look.multiply(1.1)).add(right.multiply(0.42)).subtract(up.multiply(0.38))
			: p.getLerpedPos(td).add(0, 1.25, 0).add(right.multiply(0.38)).add(look.multiply(0.25));
		boolean rm = OpeSettings.reducedMotion();
		for (PieceVisuals v : BEAMS) {
			Vec3d e = v.pos.add(0, v.set.sy / 2.0, 0);
			Vec3d c = s.add(e).multiply(0.5).add(up.multiply(0.15 * e.distanceTo(s)));
			double px = s.x, py = s.y, pz = s.z;
			int prevA = A(hc(beamAlpha(0, t, rm)));
			for (int i = 1; i <= 16; i++) {
				double u = i / 16.0, w = 1 - u;
				double qx = w * w * s.x + 2 * w * u * c.x + u * u * e.x;
				double qy = w * w * s.y + 2 * w * u * c.y + u * u * e.y;
				double qz = w * w * s.z + 2 * w * u * c.z + u * u * e.z;
				int a = A(hc(beamAlpha(u, t, rm)));
				double u0 = (i - 1) / 16.0;
				beam(buf, m, cam, px, py, pz, qx, qy, qz, 0.05 + 0.04 * u0, 0.05 + 0.04 * u, ROOM, prevA, ROOM, a);
				px = qx;
				py = qy;
				pz = qz;
				prevA = a;
			}
		}
	}

	private static double beamAlpha(double v, double t, boolean rm) {
		return 0.22 * smoothstep(0, 0.25, v) * (0.55 + (rm ? 0 : 0.45 * Math.sin(40 * v - 18 * t)));
	}

	/**
	 * El brillo de las piezas (las 12 más cercanas): el borde según su estado y, recién cortadas o arrancadas, sus caras
	 * cortadas. Con la transformación que PieceRenderer usó en este fotograma.
	 */
	private static void drawGlow(BufferBuilder buf, Matrix4f m, Vec3d cam, MinecraftClient mc, double t) {
		long wt = mc.world.getTime();
		for (PieceVisuals v : GLOW) {
			PieceEntity e = v.e;
			int f = e.flags();
			Matrix4f at = new Matrix4f(m).translate((float) (v.pos.x - cam.x), (float) (v.pos.y - cam.y), (float) (v.pos.z - cam.z)).mul(v.local);
			List<BlockSet.Entry> es = v.set.entries;
			int rgb = rim(e, f);
			if (rgb != 0) {
				double k = rimK(e, f);
				double pulse = pulse(1.6, 0.7 * e.getId(), t);
				// la cámara vista desde la esquina de la pieza (aprox.: sin la inclinación ni el aplastón)
				double ox = cam.x - (v.pos.x - v.set.sx / 2.0), oy = cam.y - v.pos.y, oz = cam.z - (v.pos.z - v.set.sz / 2.0);
				for (int code : v.rimFaces()) {
					BlockSet.Entry b = es.get(code >> 3);
					int d = code & 7;
					double cx = b.x() + 0.5 + 0.5 * DX[d] - ox, cy = b.y() + 0.5 + 0.5 * DY[d] - oy, cz = b.z() + 0.5 + 0.5 * DZ[d] - oz;
					double len = Math.sqrt(cx * cx + cy * cy + cz * cz);
					double nv = len < 1e-6 ? 0 : Math.abs(DX[d] * cx + DY[d] * cy + DZ[d] * cz) / len;
					double a = hc((0.07 + 0.42 * pulse * (1 - nv) * (1 - nv)) * k);
					face(buf, at, b.x(), b.y(), b.z(), d, 0.01f, rgb, A(a));
				}
			}
			if ((f & PieceEntity.F_FRESH) != 0) {
				double tw = (wt + v.td - e.fxTime()) / 20.0;
				int crgb;
				double a;
				if ((f & PieceEntity.F_CHUNK) != 0) {
					crgb = GROUND;
					a = hc(0.8 * chunk(tw) + flash(0.5 * seamCap(tw)));
				} else {
					crgb = mix(HOT, ROOM, smoothstep(0, 0.3, tw));
					a = hc(0.85 * seamCore(tw) + flash(0.6 * seamCap(tw)));
				}
				if (a < 0.01) continue;
				int ca = A(a);
				for (int code : v.cutFaces()) {
					BlockSet.Entry b = es.get(code >> 3);
					face(buf, at, b.x(), b.y(), b.z(), code & 7, 0.005f, crgb, ca);
				}
			}
		}
	}

	/** El color del borde de una pieza según su estado (0 = sin borde). */
	private static int rim(PieceEntity e, int f) {
		if ((f & PieceEntity.F_LANDED) != 0) return 0;
		if ((f & PieceEntity.F_SEAM) != 0) return MINT;
		if ((f & PieceEntity.F_HELD) != 0) return contains(ClientState.heldIds, e.getId()) && ClientState.taktMode == 2 ? PARK : ROOM;
		if ((f & PieceEntity.F_SUSPENDED) != 0) return ROOM;
		return 0;
	}

	private static double rimK(PieceEntity e, int f) {
		if ((f & PieceEntity.F_SEAM) != 0) return 1.0;
		if ((f & PieceEntity.F_HELD) != 0) return contains(ClientState.heldIds, e.getId()) && ClientState.taktMode == 2 ? 1.0 : 0.6;
		return 0.8;
	}

	// ------------------------------------------------------------------ primitivas

	/** La sombra oscura de «Contraste» bajo una línea del suelo (fade: lo que queda de la línea). */
	private static void underlay(BufferBuilder buf, Matrix4f m, Vec3d eye, float[] seg, double width, double fade) {
		CutFx.flat(buf, m, eye, seg, 1.8 * width, 0, 0, 0, A(0.45 * clamp(fade, 0, 1)));
	}

	/** Un solo trozo de una lista (copia del cuerpo de CutFx.flat), para las grietas, que se encienden trozo a trozo. */
	private static void seg1(BufferBuilder buf, Matrix4f m, Vec3d cam, float[] s, int o, double width, int r, int g, int b, int a) {
		if (a <= 0) return;
		a = Math.min(255, a);
		double ax = s[o] - cam.x, ay = s[o + 1] - cam.y, az = s[o + 2] - cam.z;
		double bx = s[o + 3] - cam.x, by = s[o + 4] - cam.y, bz = s[o + 5] - cam.z;
		double dx = bx - ax, dy = by - ay, dz = bz - az;
		double fx = s[o + 6], fy = s[o + 7], fz = s[o + 8];
		double px = dy * fz - dz * fy, py = dz * fx - dx * fz, pz = dx * fy - dy * fx;
		double len = Math.sqrt(px * px + py * py + pz * pz);
		if (len < 1e-6) return;
		double h = width / 2 / len;
		px *= h;
		py *= h;
		pz *= h;
		double dl = Math.sqrt(dx * dx + dy * dy + dz * dz);
		double ex = dl > 1e-6 ? dx / dl * width * 0.5 : 0, ey = dl > 1e-6 ? dy / dl * width * 0.5 : 0, ez = dl > 1e-6 ? dz / dl * width * 0.5 : 0;
		double lift = width > 0.1 ? 0.006 : 0.004;
		ax -= ex - fx * lift;
		ay -= ey - fy * lift;
		az -= ez - fz * lift;
		bx += ex + fx * lift;
		by += ey + fy * lift;
		bz += ez + fz * lift;
		buf.vertex(m, (float) (ax - px), (float) (ay - py), (float) (az - pz)).color(r, g, b, a);
		buf.vertex(m, (float) (bx - px), (float) (by - py), (float) (bz - pz)).color(r, g, b, a);
		buf.vertex(m, (float) (bx + px), (float) (by + py), (float) (bz + pz)).color(r, g, b, a);
		buf.vertex(m, (float) (ax + px), (float) (ay + py), (float) (az + pz)).color(r, g, b, a);
	}

	/** Una cinta que mira a la cámara de A a B, con ancho y color propios en cada punta. */
	private static void beam(BufferBuilder buf, Matrix4f m, Vec3d cam, double ax, double ay, double az, double bx, double by, double bz,
			double wA, double wB, int rgbA, int aA, int rgbB, int aB) {
		if (aA <= 0 && aB <= 0) return;
		double dx = bx - ax, dy = by - ay, dz = bz - az;
		double mx = (ax + bx) / 2 - cam.x, my = (ay + by) / 2 - cam.y, mz = (az + bz) / 2 - cam.z;
		double sx = dy * mz - dz * my, sy = dz * mx - dx * mz, sz = dx * my - dy * mx;
		double len = Math.sqrt(sx * sx + sy * sy + sz * sz);
		if (len < 1e-9) return;
		sx /= len;
		sy /= len;
		sz /= len;
		double ha = wA / 2, hb = wB / 2;
		ax -= cam.x;
		ay -= cam.y;
		az -= cam.z;
		bx -= cam.x;
		by -= cam.y;
		bz -= cam.z;
		buf.vertex(m, (float) (ax - sx * ha), (float) (ay - sy * ha), (float) (az - sz * ha)).color(r(rgbA), g(rgbA), b(rgbA), aA);
		buf.vertex(m, (float) (bx - sx * hb), (float) (by - sy * hb), (float) (bz - sz * hb)).color(r(rgbB), g(rgbB), b(rgbB), aB);
		buf.vertex(m, (float) (bx + sx * hb), (float) (by + sy * hb), (float) (bz + sz * hb)).color(r(rgbB), g(rgbB), b(rgbB), aB);
		buf.vertex(m, (float) (ax + sx * ha), (float) (ay + sy * ha), (float) (az + sz * ha)).color(r(rgbA), g(rgbA), b(rgbA), aA);
	}

	/** Un anillo plano en el plano y = cy: una banda de 64 tramos sin solaparse (la luz se suma). */
	private static void ring(BufferBuilder buf, Matrix4f m, Vec3d cam, double cx, double cy, double cz, double radius, double width, int rgb, int a) {
		if (a <= 0 || radius < 0.05) return;
		double r0 = Math.max(0, radius - width / 2), r1 = radius + width / 2;
		float y = (float) (cy + (width > 0.1 ? 0.006 : 0.004) - cam.y);
		double x = cx - cam.x, z = cz - cam.z;
		int rr = r(rgb), gg = g(rgb), bb = b(rgb);
		for (int i = 0; i < RING_SEG; i++) {
			buf.vertex(m, (float) (x + r0 * RCOS[i]), y, (float) (z + r0 * RSIN[i])).color(rr, gg, bb, a);
			buf.vertex(m, (float) (x + r1 * RCOS[i]), y, (float) (z + r1 * RSIN[i])).color(rr, gg, bb, a);
			buf.vertex(m, (float) (x + r1 * RCOS[i + 1]), y, (float) (z + r1 * RSIN[i + 1])).color(rr, gg, bb, a);
			buf.vertex(m, (float) (x + r0 * RCOS[i + 1]), y, (float) (z + r0 * RSIN[i + 1])).color(rr, gg, bb, a);
		}
	}

	/** La cara d de la celda (x, y, z) en coordenadas de la matriz, apartada push a lo largo de su normal. */
	private static void face(BufferBuilder buf, Matrix4f m, int x, int y, int z, int d, float push, int rgb, int a) {
		if (a <= 0) return;
		int rr = r(rgb), gg = g(rgb), bb = b(rgb);
		float x0 = x, y0 = y, z0 = z, x1 = x + 1, y1 = y + 1, z1 = z + 1;
		switch (d) {
			case 0 -> {
				float q = y0 - push;
				buf.vertex(m, x0, q, z0).color(rr, gg, bb, a);
				buf.vertex(m, x1, q, z0).color(rr, gg, bb, a);
				buf.vertex(m, x1, q, z1).color(rr, gg, bb, a);
				buf.vertex(m, x0, q, z1).color(rr, gg, bb, a);
			}
			case 1 -> {
				float q = y1 + push;
				buf.vertex(m, x0, q, z0).color(rr, gg, bb, a);
				buf.vertex(m, x0, q, z1).color(rr, gg, bb, a);
				buf.vertex(m, x1, q, z1).color(rr, gg, bb, a);
				buf.vertex(m, x1, q, z0).color(rr, gg, bb, a);
			}
			case 2 -> {
				float q = z0 - push;
				buf.vertex(m, x0, y0, q).color(rr, gg, bb, a);
				buf.vertex(m, x0, y1, q).color(rr, gg, bb, a);
				buf.vertex(m, x1, y1, q).color(rr, gg, bb, a);
				buf.vertex(m, x1, y0, q).color(rr, gg, bb, a);
			}
			case 3 -> {
				float q = z1 + push;
				buf.vertex(m, x0, y0, q).color(rr, gg, bb, a);
				buf.vertex(m, x1, y0, q).color(rr, gg, bb, a);
				buf.vertex(m, x1, y1, q).color(rr, gg, bb, a);
				buf.vertex(m, x0, y1, q).color(rr, gg, bb, a);
			}
			case 4 -> {
				float q = x0 - push;
				buf.vertex(m, q, y0, z0).color(rr, gg, bb, a);
				buf.vertex(m, q, y0, z1).color(rr, gg, bb, a);
				buf.vertex(m, q, y1, z1).color(rr, gg, bb, a);
				buf.vertex(m, q, y1, z0).color(rr, gg, bb, a);
			}
			default -> {
				float q = x1 + push;
				buf.vertex(m, q, y0, z0).color(rr, gg, bb, a);
				buf.vertex(m, q, y1, z0).color(rr, gg, bb, a);
				buf.vertex(m, q, y1, z1).color(rr, gg, bb, a);
				buf.vertex(m, q, y0, z1).color(rr, gg, bb, a);
			}
		}
	}

	// ------------------------------------------------------------------ el rayo de la mira (copia de PowerManager)

	private static BlockHitResult raycastBlock(World w, PlayerEntity p, double range) {
		Vec3d eye = p.getEyePos();
		Vec3d end = eye.add(p.getRotationVector().multiply(range));
		Vec3d back = eye.subtract(end);
		BlockHitResult miss = BlockHitResult.createMissed(end, Direction.getFacing(back.x, back.y, back.z), BlockPos.ofFloored(end));
		RaycastContext ctx = new RaycastContext(eye, end, RaycastContext.ShapeType.OUTLINE, RaycastContext.FluidHandling.NONE, p);
		return BlockView.raycast(eye, end, ctx, (c, pos) -> {
			if (!w.isChunkLoaded(pos)) return miss;
			BlockState st = w.getBlockState(pos);
			return w.raycastBlock(eye, end, pos, c.getBlockShape(st, w, pos), st);
		}, c -> miss);
	}

	/** ¿Hay antes del bloque (hasta maxD) algo que el Takt del servidor cogería? Copia de PowerManager.raycastEntity. */
	private static boolean grabbableFirst(World w, PlayerEntity p, double maxD, double margin) {
		Vec3d eye = p.getEyePos();
		Vec3d look = p.getRotationVector();
		Vec3d end = eye.add(look.multiply(maxD));
		Box box = p.getBoundingBox().stretch(look.multiply(maxD)).expand(1 + margin);
		for (Entity x : w.getOtherEntities(p, box, o -> grabbable(p, o) && !o.isSpectator())) {
			Box eb = x.getBoundingBox().expand(margin + x.getTargetingMargin());
			if (eb.contains(eye) || eb.raycast(eye, end).isPresent()) return true;
		}
		return false;
	}

	/** Lo que el cliente sabe de Takt.canGrab: vivo, no espectador, no creativo, no lo llevas ya, y una pieza libre. */
	private static boolean grabbable(PlayerEntity p, Entity e) {
		if (e == p || e.isSpectator() || !e.isAlive()) return false;
		if (e instanceof PlayerEntity pl && pl.isCreative()) return false;
		if (contains(ClientState.heldIds, e.getId())) return false;
		return !(e instanceof PieceEntity pe
			&& (pe.flags() & (PieceEntity.F_WEAPON | PieceEntity.F_LANDED | PieceEntity.F_SEAM | PieceEntity.F_HELD)) != 0);
	}

	// ------------------------------------------------------------------ ayudas

	@Nullable
	private static PieceVisuals drawn(int id) {
		for (PieceVisuals v : PieceVisuals.DRAWN) if (v.e.getId() == id) return v;
		return null;
	}

	private static boolean contains(int[] a, int v) {
		for (int x : a) if (x == v) return true;
		return false;
	}

	private static boolean sameOwner(@Nullable UUID a, @Nullable UUID b) {
		return a == null ? b == null : a.equals(b);
	}

	private static Vec3d camera(MinecraftClient mc) {
		return mc.gameRenderer != null && mc.gameRenderer.getCamera().isReady() ? mc.gameRenderer.getCamera().getPos() : mc.player.getEyePos();
	}

	/** Distancia al cuadrado de p a la caja (0 dentro). */
	private static double boxDist2(Box b, Vec3d p) {
		double dx = Math.max(0, Math.max(b.minX - p.x, p.x - b.maxX));
		double dy = Math.max(0, Math.max(b.minY - p.y, p.y - b.maxY));
		double dz = Math.max(0, Math.max(b.minZ - p.z, p.z - b.maxZ));
		return dx * dx + dy * dy + dz * dz;
	}

	private static void warn(RuntimeException e) {
		if (warned) return;
		warned = true;
		OpeOpeMod.LOG.warn("TerrainFx: {}", e.toString());
	}

	private static int r(int rgb) {
		return rgb >> 16 & 255;
	}

	private static int g(int rgb) {
		return rgb >> 8 & 255;
	}

	private static int b(int rgb) {
		return rgb & 255;
	}

	private static int A(double a) {
		return (int) Math.max(0, Math.min(255, Math.round(255 * a)));
	}

	private static double clamp(double v, double lo, double hi) {
		return v < lo ? lo : (v > hi ? hi : v);
	}

	private static double flash(double a) {
		return switch (OpeSettings.flashLevel()) {
			case 2 -> a;
			case 1 -> Math.min(a, 0.35);
			default -> Math.min(a, 0.12);
		};
	}

	private static double hc(double a) {
		return OpeSettings.highContrast() ? Math.min(1, 1.5 * a) : a;
	}

	private static int mix(int c1, int c2, double k) {
		int rr = (int) Math.round(r(c1) + (r(c2) - r(c1)) * k);
		int gg = (int) Math.round(g(c1) + (g(c2) - g(c1)) * k);
		int bb = (int) Math.round(b(c1) + (b(c2) - b(c1)) * k);
		return rr << 16 | gg << 8 | bb;
	}

	private static double pulse(double period, double phase, double t) {
		return OpeSettings.reducedMotion() ? 0.5 : 0.5 + 0.5 * Math.sin(Math.PI * 2 * t / period + phase);
	}

	private static double smoothstep(double a, double b, double x) {
		double u = clamp((x - a) / (b - a), 0, 1);
		return u * u * (3 - 2 * u);
	}

	private static double easeOutCubic(double u) {
		double k = 1 - clamp(u, 0, 1);
		return 1 - k * k * k;
	}

	private static double seamCap(double t) {
		return 0.55 * Math.max(0, 1 - t / 0.40);
	}

	private static double seamCore(double t) {
		return clamp(1.35 * (1 - smoothstep(0, 1.7, t)), 0, 1);
	}

	private static double chunk(double t) {
		return clamp((0.55 * Math.exp(-2.4 * t) + 0.015 * Math.exp(-0.08 * t)) * smoothstep(0, 0.15, t) / 0.55, 0, 1);
	}

	private static double tile(double t) {
		return clamp((1.25 * Math.exp(-1.4 * t) + 0.09 * Math.exp(-0.03 * t)) * smoothstep(0, 0.04, t) / 1.25, 0, 1);
	}
}
