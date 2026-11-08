package dev.opeope.client;

import dev.opeope.OpeConfig;
import dev.opeope.client.room.RoomFxClient;
import dev.opeope.client.room.RoomVisuals;
import dev.opeope.net.Payloads;
import dev.opeope.terrain.GroundCut;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import net.minecraft.client.MinecraftClient;
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
	/** El ancho del trozo de suelo que arranca Takt (R + rueda sin nada en Takt). Solo esta sesión: no se guarda. */
	public static double chunkSize = OpeConfig.TAKT_CHUNK_DEFAULT;
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

	public static void apply(Payloads.State s) {
		flags = s.flags();
		pattern = s.pattern();
		taktCount = s.taktCount();
		taktMode = s.taktMode();
		weapon = s.weapon();
		scanTicks = s.scanTicks();
		cooldowns = s.cooldowns();
		// el array del paquete puede ser el del servidor (un jugador): copia
		heldIds = s.held() == null ? new int[0] : s.held().clone();
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
		if (MinecraftClient.getInstance().isPaused()) return;
		for (int i = 0; i < cooldowns.length; i++) if (cooldowns[i] > 0) cooldowns[i]--;
		if (scanTicks > 0) scanTicks--;
	}

	public static void clear() {
		flags = 0;
		pattern = 0;
		taktCount = 0;
		weapon = -1;
		scanTicks = 0;
		cooldowns = new int[0];
		heldIds = new int[0];
		chunkSize = OpeConfig.TAKT_CHUNK_DEFAULT;
		ROOMS.clear();
		CUTS.clear();
		GROUND_CUTS.clear();
		RoomFxClient.clear();
	}
}
