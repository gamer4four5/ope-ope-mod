package dev.opeope.client;

import dev.opeope.net.Payloads;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import net.minecraft.client.MinecraftClient;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.Vec3d;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** Lo que el cliente sabe de los poderes: el estado propio, los ROOMs a la vista y los seres cortados. */
public final class ClientState {
	private ClientState() {}

	public record RoomInfo(UUID owner, int slot, Identifier dim, Vec3d center, float radius, float prevRadius, int state) {}

	public static int flags, pattern, taktCount, taktMode, weapon = -1, scanTicks;
	public static int[] cooldowns = new int[0];
	public static final Map<String, RoomInfo> ROOMS = new HashMap<>();
	public static final Int2ObjectOpenHashMap<float[]> CUTS = new Int2ObjectOpenHashMap<>();

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
	}

	public static void room(Payloads.Room r) {
		String key = r.owner() + ":" + r.slot();
		if (r.radius() <= 0) {
			ROOMS.remove(key);
			return;
		}
		RoomInfo old = ROOMS.get(key);
		float prev = old != null ? old.radius() : r.radius();
		ROOMS.put(key, new RoomInfo(r.owner(), r.slot(), r.dim(), new Vec3d(r.x(), r.y(), r.z()), r.radius(), prev, r.state()));
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

	public static void tick() {
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
		ROOMS.clear();
		CUTS.clear();
	}
}
