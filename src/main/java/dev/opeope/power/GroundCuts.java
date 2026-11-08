package dev.opeope.power;

import dev.opeope.OpeConfig;
import dev.opeope.net.Payloads;
import dev.opeope.terrain.ChunkPlanner;
import dev.opeope.terrain.GroundCut;
import net.fabricmc.fabric.api.networking.v1.PlayerLookup;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;

/**
 * Los cortes que entraron en el suelo dentro del ROOM: el suelo los recuerda tres minutos (como mucho 24 por jugador) y
 * Takt arranca justo lo que encierran. Un arranque pendiente aparta los cortes que le dan forma; al soltarse los gasta
 * (se guardan un rato por si R R lo deshace) y, si se deshace, vuelven. Todo vive en memoria del servidor y se copia a
 * cada cliente para que dibuje las marcas.
 */
public final class GroundCuts {
	private GroundCuts() {}

	/** Los cortes vivos por id. El id crece con la edad, así que el orden del mapa es el de antigüedad (también tras devolver uno). */
	private static final TreeMap<Integer, GroundCut> LIVE = new TreeMap<>();
	/** Cortes apartados por un arranque pendiente: id del corte → id del arranque. */
	private static final Map<Integer, Integer> RESERVED = new HashMap<>();
	/** Lo que gastó cada arranque, guardado mientras aún se puede deshacer: id del arranque → cortes. */
	private static final Map<Integer, List<GroundCut>> HELD = new HashMap<>();
	private static int nextId = 1;
	private static int tickCounter;
	/** El servidor en marcha, para avisar a todos (lo fijan tick, record y sendAll; clearAll lo olvida). */
	@Nullable private static MinecraftServer server;

	/** Un plano de un corte cruzó suelo: se recuerda. Si el jugador ya tiene 24, se olvidan los más viejos. */
	public static void record(ServerPlayerEntity owner, CutEngine.Plane plane, CutEngine.Region region, Box seedBox) {
		ServerWorld w = owner.getServerWorld();
		server = owner.getServer();
		UUID uuid = owner.getUuid();
		GroundCut cut = GroundCut.create(nextId++, uuid, w.getRegistryKey().getValue(), w.getTime(), plane.n(), plane.p(),
			region.c, region.r, region.lc, region.lr, region.eye, region.look, seedBox);
		List<GroundCut> mine = ownerCuts(uuid);
		for (int i = 0; mine.size() - i >= OpeConfig.GROUND_CUT_MAX; i++) drop(mine.get(i));
		LIVE.put(cut.id, cut);
		broadcast(addMsg(cut));
	}

	/** Los cortes de ese jugador que pueden dar forma a un arranque en a (los apartados por otro arranque no cuentan). */
	public static List<GroundCut> usable(UUID owner, ServerWorld w, Vec3d a) {
		List<GroundCut> free = new ArrayList<>();
		for (GroundCut c : LIVE.values()) {
			if (c.owner.equals(owner) && !RESERVED.containsKey(c.id)) free.add(c);
		}
		return ChunkPlanner.near(free, owner, w.getRegistryKey().getValue(), a, w.getTime());
	}

	/** Un arranque pendiente aparta los cortes que le dan forma (los que siguen vivos). */
	public static void reserve(int[] ids, int ripId) {
		for (int id : ids) {
			if (LIVE.containsKey(id)) RESERVED.put(id, ripId);
		}
	}

	/** El arranque se soltó: sus cortes se borran (las marcas desaparecen), pero se guardan por si se deshace. */
	public static void consume(int ripId) {
		List<Integer> ids = reservedBy(ripId);
		if (ids.isEmpty()) return;
		List<GroundCut> kept = HELD.computeIfAbsent(ripId, k -> new ArrayList<>());
		for (int id : ids) {
			RESERVED.remove(id);
			GroundCut c = LIVE.remove(id);
			if (c == null) continue;
			kept.add(c);
			broadcast(removeMsg(c));
		}
	}

	/** El arranque se deshizo: sus cortes dejan de estar apartados y los gastados vuelven (mismo id, misma edad). */
	public static void restore(int ripId) {
		for (int id : reservedBy(ripId)) RESERVED.remove(id);
		List<GroundCut> back = HELD.remove(ripId);
		if (back == null || back.isEmpty()) return;
		Set<UUID> owners = new HashSet<>();
		for (GroundCut c : back) {
			LIVE.put(c.id, c);
			broadcast(addMsg(c));
			owners.add(c.owner);
		}
		for (UUID o : owners) trim(o);
	}

	/** Pasó el rato de deshacer: lo que gastó ese arranque queda gastado para siempre. */
	public static void forgetRip(int ripId) {
		HELD.remove(ripId);
	}

	/** Cada 20 ticks: se olvidan los cortes caducados que nadie tiene apartados. */
	public static void tick(MinecraftServer s) {
		server = s;
		if (++tickCounter % 20 != 0 || LIVE.isEmpty()) return;
		long now = s.getOverworld().getTime();
		List<GroundCut> old = new ArrayList<>();
		for (GroundCut c : LIVE.values()) {
			if (c.expired(now) && !RESERVED.containsKey(c.id)) old.add(c);
		}
		for (GroundCut c : old) drop(c);
	}

	/** Al entrar un jugador: borra su copia y le manda todos los cortes vivos (de todos, en todas las dimensiones). */
	public static void sendAll(ServerPlayerEntity to) {
		server = to.getServer();
		ServerPlayNetworking.send(to, clearMsg(Payloads.GroundCutSync.ALL));
		for (GroundCut c : LIVE.values()) ServerPlayNetworking.send(to, addMsg(c));
	}

	/** El servidor se para: todo fuera, sin avisar a nadie. */
	public static void clearAll() {
		LIVE.clear();
		RESERVED.clear();
		HELD.clear();
		nextId = 1;
		tickCounter = 0;
		server = null;
	}

	/** Los cortes vivos de ese jugador (los apartados cuentan; los gastados no). */
	public static int count(UUID owner) {
		int n = 0;
		for (GroundCut c : LIVE.values()) {
			if (c.owner.equals(owner)) n++;
		}
		return n;
	}

	/** Olvida todos los cortes de ese jugador (también los apartados y los guardados para deshacer) y avisa a todos. */
	public static void clearOwner(UUID owner) {
		Iterator<GroundCut> it = LIVE.values().iterator();
		while (it.hasNext()) {
			GroundCut c = it.next();
			if (!c.owner.equals(owner)) continue;
			RESERVED.remove(c.id);
			it.remove();
		}
		for (List<GroundCut> list : HELD.values()) list.removeIf(c -> c.owner.equals(owner));
		broadcast(clearMsg(owner));
	}

	// ------------------------------------------------------------------ por dentro

	/** Los cortes vivos de un jugador, del más viejo al más nuevo. */
	private static List<GroundCut> ownerCuts(UUID owner) {
		List<GroundCut> out = new ArrayList<>();
		for (GroundCut c : LIVE.values()) {
			if (c.owner.equals(owner)) out.add(c);
		}
		return out;
	}

	/** Los ids que tiene apartados un arranque, de menor a mayor. */
	private static List<Integer> reservedBy(int ripId) {
		List<Integer> out = new ArrayList<>();
		for (Map.Entry<Integer, Integer> en : RESERVED.entrySet()) {
			if (en.getValue() == ripId) out.add(en.getKey());
		}
		out.sort(null);
		return out;
	}

	/** Si al devolver cortes un jugador pasa de 24, se van sus más viejos que no estén apartados. */
	private static void trim(UUID owner) {
		List<GroundCut> mine = ownerCuts(owner);
		int excess = mine.size() - OpeConfig.GROUND_CUT_MAX;
		for (GroundCut c : mine) {
			if (excess <= 0) break;
			if (RESERVED.containsKey(c.id)) continue;
			drop(c);
			excess--;
		}
	}

	private static void drop(GroundCut c) {
		LIVE.remove(c.id);
		RESERVED.remove(c.id);
		broadcast(removeMsg(c));
	}

	private static void broadcast(Payloads.GroundCutSync s) {
		if (server == null) return;
		for (ServerPlayerEntity sp : PlayerLookup.all(server)) ServerPlayNetworking.send(sp, s);
	}

	private static Payloads.GroundCutSync addMsg(GroundCut c) {
		return new Payloads.GroundCutSync(Payloads.GroundCutSync.ADD, c.id, c.owner, c.dim, c.created, c.toFloats());
	}

	private static Payloads.GroundCutSync removeMsg(GroundCut c) {
		return new Payloads.GroundCutSync(Payloads.GroundCutSync.REMOVE, c.id, c.owner, c.dim, c.created, new float[0]);
	}

	private static Payloads.GroundCutSync clearMsg(UUID owner) {
		return new Payloads.GroundCutSync(Payloads.GroundCutSync.CLEAR, 0, owner, World.OVERWORLD.getValue(), 0L, new float[0]);
	}
}
