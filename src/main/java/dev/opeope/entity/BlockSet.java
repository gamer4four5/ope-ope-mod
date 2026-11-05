package dev.opeope.entity;

import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;
import net.minecraft.nbt.NbtHelper;
import net.minecraft.nbt.NbtList;
import net.minecraft.registry.Registries;
import net.minecraft.util.math.BlockPos;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Los bloques de una pieza cortada, en coordenadas locales desde (0,0,0). */
public class BlockSet {
	public record Entry(int x, int y, int z, BlockState state, @Nullable NbtCompound be) {}

	public final List<Entry> entries = new ArrayList<>();
	public int sx = 1, sy = 1, sz = 1;

	public boolean isEmpty() {
		return entries.isEmpty();
	}

	public int size() {
		return entries.size();
	}

	public void add(int x, int y, int z, BlockState state, @Nullable NbtCompound be) {
		entries.add(new Entry(x, y, z, state, be));
	}

	/** Mueve todo para que el mínimo quede en (0,0,0) y devuelve cuánto se movió. */
	public BlockPos normalize() {
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
			for (Entry e : entries) moved.add(new Entry(e.x - mx, e.y - my, e.z - mz, e.state, e.be));
			entries.clear();
			entries.addAll(moved);
		}
		sx = Mx - mx + 1;
		sy = My - my + 1;
		sz = Mz - mz + 1;
		return new BlockPos(mx, my, mz);
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
		nbt.putInt("sx", sx);
		nbt.putInt("sy", sy);
		nbt.putInt("sz", sz);
		return nbt;
	}

	public static BlockSet fromSyncNbt(NbtCompound nbt) {
		BlockSet set = new BlockSet();
		int[] pos = nbt.getIntArray("p");
		int[] ids = nbt.getIntArray("s");
		int n = Math.min(pos.length, ids.length);
		for (int i = 0; i < n; i++) {
			int p = pos[i];
			set.add(p & 0x3FF, (p >> 10) & 0x3FF, (p >> 20) & 0x3FF, Block.getStateFromRawId(ids[i]), null);
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
			set.add(p & 0x3FF, (p >> 10) & 0x3FF, (p >> 20) & 0x3FF, palette[idx[i]], bes.get(i));
		}
		set.normalize();
		return set;
	}
}
