package dev.opeope.client;

import dev.opeope.OpeConfig;
import dev.opeope.client.room.RoomFxClient;
import dev.opeope.client.room.RoomVisuals;
import dev.opeope.entity.PieceEntity;
import dev.opeope.net.Payloads;
import dev.opeope.terrain.GroundCut;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.Entity;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.Vec3d;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Lo que el cliente sabe de los poderes: el estado propio, los ROOMs a la vista, los seres cortados, lo que llevas en
 * Takt, el tamaño del trozo de suelo y los cortes del suelo que el servidor recuerda (de todos y de todas las
 * dimensiones; ownGroundCuts filtra los propios de la dimensión actual).
 */
public final class ClientState {
	private ClientState() {}

	public record RoomInfo(UUID owner, int slot, Identifier dim, Vec3d center, float radius, float prevRadius, int state) {}

	public static int flags, pattern, taktCount, taktMode, weapon = -1, scanTicks;
	public static int[] cooldowns = new int[0];
	/** Los ids de lo que llevas en Takt, en el orden del servidor (State.held). */
	public static int[] heldIds = new int[0];
	/** Ventana aérea de Shambles Táctico y piezas en vuelo propias elegibles para trueque en 1 toque. */
	public static int aerialTicks, aerialMaxTicks, finisherTargetId = -1, highlightedTacticalPieceId = -1;
	public static float aerialGravMult = OpeConfig.SHAMBLES_AERIAL_GRAV_MULT;
	public static boolean aerialPerfect;
	public static int[] tacticalPieceIds = new int[0];
	public static long shamblesModeToastUntil = 0;
	/** El ancho, grosor (0 = auto) y modo de lo que va encima (0..3) del trozo de Takt. Solo esta sesión. */
	public static double chunkSize = OpeConfig.TAKT_CHUNK_DEFAULT;
	public static int chunkDepth = 0;
	public static int chunkRiderMode = 0;
	public static final Map<String, RoomInfo> ROOMS = new HashMap<>();
	public static final Int2ObjectOpenHashMap<float[]> CUTS = new Int2ObjectOpenHashMap<>();
	/** Los cortes del suelo recordados, por id. */
	public static final Int2ObjectOpenHashMap<GroundCut> GROUND_CUTS = new Int2ObjectOpenHashMap<>();

	public static boolean has(int flag) {
		return (flags & flag) != 0;
	}

	public static boolean hasFruit() {
		return has(Payloads.State.HAS_FRUIT);
	}

	public static boolean awakened() {
		return has(Payloads.State.AWAKENED);
	}

	public static boolean union() {
		return has(Payloads.State.UNION);
	}

	public static int combatStyle() {
		if (has(Payloads.State.STYLE_SWORD)) return 2;
		if (has(Payloads.State.STYLE_FIGHT)) return 3;
		return 1;
	}

	public static boolean freeMode() {
		return has(Payloads.State.FREE_MODE);
	}

	public static boolean kroomBlade() {
		return has(Payloads.State.KROOM_BLADE);
	}

	public static boolean eternalYouth() {
		return has(Payloads.State.ETERNAL_YOUTH);
	}

	public static boolean zeroGravity() {
		return has(Payloads.State.ZERO_G);
	}

	public static int shamblesMode() {
		if (has(Payloads.State.SHAMBLES_MODE_ENTITIES)) return 2;
		if (has(Payloads.State.SHAMBLES_MODE_SWAP)) return 1;
		return 0;
	}

	public static void apply(Payloads.State s) {
		int oldMode = shamblesMode();
		flags = s.flags();
		int newMode = shamblesMode();
		if (newMode != oldMode && hasFruit()) shamblesModeToastUntil = System.currentTimeMillis() + 3500L;
		pattern = s.pattern();
		taktCount = s.taktCount();
		taktMode = s.taktMode();
		weapon = s.weapon();
		scanTicks = s.scanTicks();
		cooldowns = s.cooldowns();
		// el array del paquete puede ser el del servidor (un jugador): copia
		heldIds = s.held() == null ? new int[0] : s.held().clone();
	}

	public static void applyAerial(Payloads.AerialWindow a) {
		aerialTicks = Math.max(0, a.ticks());
		aerialMaxTicks = Math.max(0, a.maxTicks());
		aerialGravMult = a.gravMult();
		aerialPerfect = a.perfect();
		finisherTargetId = a.finisherTargetId();
		tacticalPieceIds = a.tacticalIds() == null ? new int[0] : a.tacticalIds().clone();
		MinecraftClient mc = MinecraftClient.getInstance();
		if (a.ticks() > 0 && mc.player != null) mc.player.setOnGround(false);
	}

	public static void room(Payloads.Room r) {
		String key = r.owner() + ":" + r.slot();
		if (r.radius() <= 0) {
			ROOMS.remove(key);
		} else {
			RoomInfo old = ROOMS.get(key);
			float prev = old != null ? old.radius() : r.radius();
			ROOMS.put(key, new RoomInfo(r.owner(), r.slot(), r.dim(), new Vec3d(r.x(), r.y(), r.z()), r.radius(), prev, r.state()));
		}
		// lo que se dibuja (centro y radio suavizados por tick, efectos) vive aparte
		RoomVisuals.onRoomPacket(r);
	}

	public static RoomInfo ownRoom() {
		MinecraftClient mc = MinecraftClient.getInstance();
		if (mc.player == null || mc.world == null) return null;
		RoomInfo r = ROOMS.get(mc.player.getUuid() + ":0");
		if (r == null || !r.dim().equals(mc.world.getRegistryKey().getValue())) return null;
		return r;
	}

	public static boolean inOwnRoom(Vec3d p) {
		RoomInfo r = ownRoom();
		return r != null && p.squaredDistanceTo(r.center()) <= (double) r.radius() * r.radius();
	}

	/** El ROOM propio (el 0 y luego el 1) de esta dimensión que contiene p, o null. */
	@Nullable
	public static RoomInfo ownRoomAt(Vec3d p) {
		MinecraftClient mc = MinecraftClient.getInstance();
		if (mc.player == null || mc.world == null || p == null) return null;
		Identifier dim = mc.world.getRegistryKey().getValue();
		for (int slot = 0; slot <= 1; slot++) {
			RoomInfo r = ROOMS.get(mc.player.getUuid() + ":" + slot);
			if (r == null || !r.dim().equals(dim)) continue;
			if (p.squaredDistanceTo(r.center()) <= (double) r.radius() * r.radius()) return r;
		}
		return null;
	}

	/** El ancho del trozo en medios bloques, como va por la red: (int) Math.round(chunkSize · 2). */
	public static int chunkSize2() {
		return (int) Math.round(chunkSize * 2);
	}

	/** Avanza o retrocede el grosor entre los pasos de {@link OpeConfig#TAKT_DEPTH_STEPS}. */
	public static void stepChunkDepth(int dir) {
		int[] steps = OpeConfig.TAKT_DEPTH_STEPS;
		int idx = 0;
		for (int i = 0; i < steps.length; i++) {
			if (steps[i] == chunkDepth) {
				idx = i;
				break;
			}
			if (steps[i] < chunkDepth) idx = i;
		}
		int next = Math.max(0, Math.min(steps.length - 1, idx + (dir > 0 ? 1 : -1)));
		chunkDepth = steps[next];
	}

	/** Cambia el modo de lo que va encima (Normal → +Árbol → Solo árbol → Solo piso). */
	public static void cycleChunkRiderMode() {
		chunkRiderMode = (chunkRiderMode + 1) & 3;
	}

	/** Un corte del suelo que el servidor empieza a recordar (ADD), olvida (REMOVE) u olvida en bloque (CLEAR). */
	public static void groundCut(Payloads.GroundCutSync s) {
		if (s == null) return;
		switch (s.op()) {
			case Payloads.GroundCutSync.ADD -> {
				GroundCut c = GroundCut.fromFloats(s.id(), s.owner(), s.dim(), s.created(), s.data());
				if (c != null) GROUND_CUTS.put(s.id(), c);
			}
			case Payloads.GroundCutSync.REMOVE -> GROUND_CUTS.remove(s.id());
			case Payloads.GroundCutSync.CLEAR -> {
				if (s.owner() == null || s.owner().equals(Payloads.GroundCutSync.ALL)) {
					GROUND_CUTS.clear();
				} else {
					for (GroundCut c : new ArrayList<>(GROUND_CUTS.values())) {
						if (s.owner().equals(c.owner)) GROUND_CUTS.remove(c.id);
					}
				}
			}
			default -> {
			}
		}
	}

	/** Todos los cortes del suelo que conoce el cliente (de cualquiera, en cualquier dimensión). */
	public static int groundCutCount() {
		return GROUND_CUTS.size();
	}

	/** Los cortes del suelo propios de la dimensión actual, por id. */
	public static List<GroundCut> ownGroundCuts() {
		MinecraftClient mc = MinecraftClient.getInstance();
		List<GroundCut> out = new ArrayList<>();
		if (mc.player == null || mc.world == null) return out;
		UUID me = mc.player.getUuid();
		Identifier dim = mc.world.getRegistryKey().getValue();
		for (GroundCut c : GROUND_CUTS.values()) {
			if (me.equals(c.owner) && dim.equals(c.dim)) out.add(c);
		}
		out.sort(Comparator.comparingInt(c -> c.id));
		return out;
	}

	public static void tick() {
		// con el juego en pausa el servidor tampoco avanza: los tiempos de espera no deben bajar
		MinecraftClient mc = MinecraftClient.getInstance();
		if (mc.isPaused()) return;
		for (int i = 0; i < cooldowns.length; i++) if (cooldowns[i] > 0) cooldowns[i]--;
		if (scanTicks > 0) scanTicks--;
		if (aerialTicks > 0) {
			aerialTicks--;
			if (mc.player != null && !mc.player.isOnGround() && !mc.player.getAbilities().flying) {
				Vec3d v = mc.player.getVelocity();
				if (v.y < 0) {
					mc.player.setVelocity(v.x, v.y * aerialGravMult, v.z);
					mc.player.fallDistance = 0f;
				}
			}
		} else {
			finisherTargetId = -1;
			aerialPerfect = false;
		}
		highlightedTacticalPieceId = -1;
		if (mc.player != null && mc.world != null && tacticalPieceIds.length > 0 && hasFruit()) {
			Vec3d eye = mc.player.getEyePos();
			Vec3d look = mc.player.getRotationVec(1f);
			double maxCos = Math.cos(Math.toRadians(OpeConfig.SHAMBLES_ASSIST_ANGLE_DEG));
			double minVelSq = OpeConfig.SHAMBLES_VEL_MIN * OpeConfig.SHAMBLES_VEL_MIN;
			double bestDot = maxCos;
			for (int pid : tacticalPieceIds) {
				Entity ent = mc.world.getEntityById(pid);
				if (!(ent instanceof PieceEntity pe) || !pe.isAlive() || pe.mode != PieceEntity.Mode.FREE || pe.isOnGround()) continue;
				Vec3d pv = pe.getVelocity();
				if (pv.lengthSquared() <= minVelSq) continue;
				Vec3d nowC = pe.center();
				if (ownRoomAt(nowC) == null) continue;
				Vec3d leadC = nowC.add(pv.multiply(OpeConfig.SHAMBLES_LEAD_TICKS));
				Vec3d toNow = nowC.subtract(eye);
				Vec3d toLead = leadC.subtract(eye);
				double dotNow = toNow.lengthSquared() > 1e-4 ? toNow.normalize().dotProduct(look) : -1;
				double dotLead = toLead.lengthSquared() > 1e-4 ? toLead.normalize().dotProduct(look) : -1;
				double dot = Math.max(dotNow, dotLead);
				if (dot >= bestDot) {
					bestDot = dot;
					highlightedTacticalPieceId = pid;
				}
			}
		}
	}

	public static void clear() {
		flags = 0;
		pattern = 0;
		taktCount = 0;
		weapon = -1;
		scanTicks = 0;
		aerialTicks = 0;
		aerialMaxTicks = 0;
		finisherTargetId = -1;
		highlightedTacticalPieceId = -1;
		aerialGravMult = OpeConfig.SHAMBLES_AERIAL_GRAV_MULT;
		aerialPerfect = false;
		tacticalPieceIds = new int[0];
		cooldowns = new int[0];
		heldIds = new int[0];
		chunkSize = OpeConfig.TAKT_CHUNK_DEFAULT;
		chunkDepth = 0;
		chunkRiderMode = 0;
		shamblesModeToastUntil = 0;
		ROOMS.clear();
		CUTS.clear();
		GROUND_CUTS.clear();
		RoomFxClient.clear();
	}
}
