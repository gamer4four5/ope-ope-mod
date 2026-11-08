package dev.opeope.entity;

import dev.opeope.terrain.GroundRules;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;
import net.minecraft.nbt.NbtHelper;
import net.minecraft.nbt.NbtList;
import net.minecraft.registry.Registries;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.world.BlockView;
import net.minecraft.world.EmptyBlockView;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Los bloques de una pieza cortada, en coordenadas locales desde (0,0,0).
 * Cada bloque recuerda qué caras suyas eran cortes (tocaban terreno que se quedó): bit d = Direction.ordinal().
 */
public class BlockSet {
	/** faces: las caras cortadas (6 bits, por Direction.ordinal()). */
	public record Entry(int x, int y, int z, BlockState state, @Nullable NbtCompound be, int faces) {
		public Entry(int x, int y, int z, BlockState state, @Nullable NbtCompound be) {
			this(x, y, z, state, be, 0);
		}
	}

	public final List<Entry> entries = new ArrayList<>();
	public int sx = 1, sy = 1, sz = 1;

	private static final Direction[] DIRS = Direction.values();

	public boolean isEmpty() {
		return entries.isEmpty();
	}

	public int size() {
		return entries.size();
	}

	public void add(int x, int y, int z, BlockState state, @Nullable NbtCompound be) {
		add(x, y, z, state, be, 0);
	}

	public void add(int x, int y, int z, BlockState state, @Nullable NbtCompound be, int faces) {
		entries.add(new Entry(x, y, z, state, be, faces & 63));
		invalidate();
	}

	/** Mueve todo para que el mínimo quede en (0,0,0) y devuelve cuánto se movió. Las caras cortadas se mantienen. */
	public BlockPos normalize() {
		invalidate();
		if (entries.isEmpty()) {
			sx = sy = sz = 1;
			return BlockPos.ORIGIN;
		}
		int mx = Integer.MAX_VALUE, my = Integer.MAX_VALUE, mz = Integer.MAX_VALUE;
		int Mx = Integer.MIN_VALUE, My = Integer.MIN_VALUE, Mz = Integer.MIN_VALUE;
		for (Entry e : entries) {
			mx = Math.min(mx, e.x); my = Math.min(my, e.y); mz = Math.min(mz, e.z);
			Mx = Math.max(Mx, e.x); My = Math.max(My, e.y); Mz = Math.max(Mz, e.z);
		}
		if (mx != 0 || my != 0 || mz != 0) {
			List<Entry> moved = new ArrayList<>(entries.size());
			for (Entry e : entries) moved.add(new Entry(e.x - mx, e.y - my, e.z - mz, e.state, e.be, e.faces));
			entries.clear();
			entries.addAll(moved);
		}
		sx = Mx - mx + 1;
		sy = My - my + 1;
		sz = Mz - mz + 1;
		return new BlockPos(mx, my, mz);
	}

	private List<Entry> surfaceCache, visibleCache, undersideCache;
	private LongOpenHashSet posCache;
	private int posCacheSize = -1;

	private static final int[][] NEIGHBORS = {{1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}};

	/** Tras cambiar los bloques: todo lo calculado se rehace cuando se pida. */
	private void invalidate() {
		surfaceCache = visibleCache = undersideCache = null;
		posCache = null;
	}

	/** Tras cambiar solo las caras: las posiciones siguen valiendo, las listas de bloques no. */
	private void invalidateEntries() {
		surfaceCache = visibleCache = undersideCache = null;
	}

	private Map<Integer, Entry> byPos() {
		Map<Integer, Entry> map = new HashMap<>(entries.size() * 2);
		for (Entry e : entries) map.put(pack(e.x, e.y, e.z), e);
		return map;
	}

	/** ¿Hay un bloque de la pieza en esa celda local? (conjunto en caché, se rehace tras add o normalize). */
	public boolean contains(int x, int y, int z) {
		if (posCache == null || posCacheSize != entries.size()) {
			LongOpenHashSet set = new LongOpenHashSet(entries.size() * 2);
			for (Entry e : entries) set.add(BlockPos.asLong(e.x, e.y, e.z));
			posCache = set;
			posCacheSize = entries.size();
		}
		return posCache.contains(BlockPos.asLong(x, y, z));
	}

	/** Los bloques con alguna cara al aire (los únicos que pueden chocar al moverse). */
	public List<Entry> surface() {
		if (surfaceCache != null) return surfaceCache;
		Map<Integer, Entry> map = byPos();
		List<Entry> out = new ArrayList<>();
		for (Entry e : entries) {
			for (int[] d : NEIGHBORS) {
				int x = e.x + d[0], y = e.y + d[1], z = e.z + d[2];
				if (x < 0 || y < 0 || z < 0 || !map.containsKey(pack(x, y, z))) {
					out.add(e);
					break;
				}
			}
		}
		surfaceCache = out;
		return out;
	}

	/** Los bloques que se ven: se salta los tapados por bloques opacos por los seis lados (piezas grandes sin tirones). */
	public List<Entry> visible() {
		if (visibleCache != null) return visibleCache;
		Map<Integer, Entry> map = byPos();
		List<Entry> out = new ArrayList<>();
		for (Entry e : entries) {
			boolean hidden = true;
			for (int[] d : NEIGHBORS) {
				int x = e.x + d[0], y = e.y + d[1], z = e.z + d[2];
				Entry n = (x < 0 || y < 0 || z < 0) ? null : map.get(pack(x, y, z));
				if (n == null || !n.state.isOpaqueFullCube(EmptyBlockView.INSTANCE, BlockPos.ORIGIN)) {
					hidden = false;
					break;
				}
			}
			if (!hidden) out.add(e);
		}
		visibleCache = out;
		return out;
	}

	/** El bloque más bajo de cada columna (x, z), ordenados por x y luego por z: la cara de abajo de la pieza. */
	public List<Entry> underside() {
		if (undersideCache != null) return undersideCache;
		Map<Long, Entry> low = new HashMap<>(entries.size() * 2);
		for (Entry e : entries) {
			long key = ((long) e.x << 32) | (e.z & 0xFFFFFFFFL);
			Entry cur = low.get(key);
			if (cur == null || e.y < cur.y) low.put(key, e);
		}
		List<Entry> out = new ArrayList<>(low.values());
		out.sort(Comparator.comparingInt(Entry::x).thenComparingInt(Entry::z));
		undersideCache = out;
		return out;
	}

	/**
	 * Marca en cada bloque las caras cortadas: el bit d cuando la celda vecina (origin + local + d) no es de la pieza y es
	 * sólida en el mundo (terreno que se queda). Hay que llamarla con los bloques aún en el mundo.
	 * origin = posición en el mundo del (0,0,0) local.
	 */
	public void cutFaceMask(BlockView w, BlockPos origin) {
		BlockPos.Mutable m = new BlockPos.Mutable();
		for (int i = 0; i < entries.size(); i++) {
			Entry e = entries.get(i);
			int mask = 0;
			for (Direction d : DIRS) {
				int nx = e.x + d.getOffsetX(), ny = e.y + d.getOffsetY(), nz = e.z + d.getOffsetZ();
				if (contains(nx, ny, nz)) continue;
				m.set(origin.getX() + nx, origin.getY() + ny, origin.getZ() + nz);
				if (GroundRules.solid(w.getBlockState(m), w, m)) mask |= 1 << d.ordinal();
			}
			if (mask != 0) entries.set(i, new Entry(e.x, e.y, e.z, e.state, e.be, e.faces | mask));
		}
		invalidateEntries();
	}

	/** Añade caras cortadas a un bloque (por su índice en entries). */
	public void orFaces(int index, int mask) {
		Entry e = entries.get(index);
		int f = e.faces | (mask & 63);
		if (f == e.faces) return;
		entries.set(index, new Entry(e.x, e.y, e.z, e.state, e.be, f));
		invalidateEntries();
	}

	/** El estado más repetido (para forjar armas con el material de Takt). */
	public BlockState dominant() {
		Map<BlockState, Integer> count = new HashMap<>();
		BlockState best = null;
		int bestN = 0;
		for (Entry e : entries) {
			if (e.be != null) continue;
			int n = count.merge(e.state, 1, Integer::sum);
			if (n > bestN) {
				bestN = n;
				best = e.state;
			}
		}
		return best != null ? best : (entries.isEmpty() ? null : entries.get(0).state);
	}

	private static int pack(int x, int y, int z) {
		return (x & 0x3FF) | ((y & 0x3FF) << 10) | ((z & 0x3FF) << 20);
	}

	/** Las caras de todos los bloques en un array "f": las del bloque i en los bits 6·(i % 5) de f[i / 5]. Null si no hay ninguna. */
	@Nullable
	private int[] packFaces() {
		int[] f = null;
		int n = entries.size();
		for (int i = 0; i < n; i++) {
			int mask = entries.get(i).faces & 63;
			if (mask == 0) continue;
			if (f == null) f = new int[(n + 4) / 5];
			f[i / 5] |= mask << (6 * (i % 5));
		}
		return f;
	}

	/** Las caras del bloque i en un array "f" (0 si falta). */
	private static int faceAt(int[] f, int i) {
		int k = i / 5;
		return k < f.length ? (f[k] >>> (6 * (i % 5))) & 63 : 0;
	}

	/** Versión para la red: ids de estado (iguales en cliente y servidor). */
	public NbtCompound toSyncNbt() {
		NbtCompound nbt = new NbtCompound();
		int[] pos = new int[entries.size()];
		int[] ids = new int[entries.size()];
		for (int i = 0; i < entries.size(); i++) {
			Entry e = entries.get(i);
			pos[i] = pack(e.x, e.y, e.z);
			ids[i] = Block.getRawIdFromState(e.state);
		}
		nbt.putIntArray("p", pos);
		nbt.putIntArray("s", ids);
		int[] f = packFaces();
		if (f != null) nbt.putIntArray("f", f);
		nbt.putInt("sx", sx);
		nbt.putInt("sy", sy);
		nbt.putInt("sz", sz);
		return nbt;
	}

	public static BlockSet fromSyncNbt(NbtCompound nbt) {
		BlockSet set = new BlockSet();
		int[] pos = nbt.getIntArray("p");
		int[] ids = nbt.getIntArray("s");
		int[] f = nbt.getIntArray("f");
		int n = Math.min(pos.length, ids.length);
		for (int i = 0; i < n; i++) {
			int p = pos[i];
			set.add(p & 0x3FF, (p >> 10) & 0x3FF, (p >> 20) & 0x3FF, Block.getStateFromRawId(ids[i]), null, faceAt(f, i));
		}
		set.sx = Math.max(1, nbt.getInt("sx"));
		set.sy = Math.max(1, nbt.getInt("sy"));
		set.sz = Math.max(1, nbt.getInt("sz"));
		return set;
	}

	/** Versión para guardar en el mundo: paleta de estados por nombre y los datos de cofres y demás. */
	public NbtCompound toSaveNbt() {
		NbtCompound nbt = new NbtCompound();
		Map<BlockState, Integer> palette = new HashMap<>();
		NbtList paletteList = new NbtList();
		int[] pos = new int[entries.size()];
		int[] idx = new int[entries.size()];
		NbtList bes = new NbtList();
		for (int i = 0; i < entries.size(); i++) {
			Entry e = entries.get(i);
			Integer id = palette.get(e.state);
			if (id == null) {
				id = palette.size();
				palette.put(e.state, id);
				paletteList.add(NbtHelper.fromBlockState(e.state));
			}
			pos[i] = pack(e.x, e.y, e.z);
			idx[i] = id;
			if (e.be != null) {
				NbtCompound b = new NbtCompound();
				b.putInt("i", i);
				b.put("n", e.be);
				bes.add(b);
			}
		}
		nbt.put("palette", paletteList);
		nbt.putIntArray("p", pos);
		nbt.putIntArray("i", idx);
		int[] f = packFaces();
		if (f != null) nbt.putIntArray("f", f);
		nbt.put("be", bes);
		return nbt;
	}

	public static BlockSet fromSaveNbt(NbtCompound nbt) {
		BlockSet set = new BlockSet();
		NbtList paletteList = nbt.getList("palette", NbtElement.COMPOUND_TYPE);
		BlockState[] palette = new BlockState[paletteList.size()];
		for (int i = 0; i < palette.length; i++) {
			palette[i] = NbtHelper.toBlockState(Registries.BLOCK.getReadOnlyWrapper(), paletteList.getCompound(i));
		}
		int[] pos = nbt.getIntArray("p");
		int[] idx = nbt.getIntArray("i");
		int[] f = nbt.getIntArray("f");
		Map<Integer, NbtCompound> bes = new HashMap<>();
		NbtList beList = nbt.getList("be", NbtElement.COMPOUND_TYPE);
		for (int i = 0; i < beList.size(); i++) {
			NbtCompound b = beList.getCompound(i);
			bes.put(b.getInt("i"), b.getCompound("n"));
		}
		int n = Math.min(pos.length, idx.length);
		for (int i = 0; i < n; i++) {
			int p = pos[i];
			if (idx[i] < 0 || idx[i] >= palette.length) continue;
			set.add(p & 0x3FF, (p >> 10) & 0x3FF, (p >> 20) & 0x3FF, palette[idx[i]], bes.get(i), faceAt(f, i));
		}
		set.normalize();
		return set;
	}
}
