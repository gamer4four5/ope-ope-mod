package dev.opeope.terrain;

import dev.opeope.OpeConfig;
import dev.opeope.entity.BlockSet;
import net.minecraft.entity.LivingEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.BlockView;
import net.minecraft.world.World;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * Dónde cae una pieza si baja recta, en la rejilla de bloques (4.2.6): el fantasma del cliente, posar, soltar y el borde
 * del cráter. Una celda donde está un ser vivo no está libre: nunca se entierra a nadie.
 * <p>
 * Lee solo set.entries (x, y, z), set.sx/sy/sz y set.contains. Sin estado estático mutable: cliente y servidor a la vez.
 */
public final class Landing {
	private Landing() {}

	/** La celda de la rejilla más cercana a la esquina de la pieza. */
	public static BlockPos snap(Vec3d corner) {
		return BlockPos.ofFloored(corner.x + 0.5, corner.y + 0.5, corner.z + 0.5);
	}

	/** Las cajas de los seres vivos (vivos y no espectadores) en region. List.of() = nadie a quien esquivar. */
	public static List<Box> blockers(World w, Box region) {
		List<LivingEntity> found = w.getEntitiesByClass(LivingEntity.class, region, e -> e.isAlive() && !e.isSpectator());
		List<Box> out = new ArrayList<>(found.size());
		for (LivingEntity e : found) out.add(e.getBoundingBox());
		return out;
	}

	/** Lo que blockers() tiene que cubrir para find()/fits() de set cerca de corner. */
	public static Box region(BlockSet set, Vec3d corner) {
		return new Box(corner.x - 3, corner.y - OpeConfig.TAKT_LANDING_SEARCH - 2, corner.z - 3,
			corner.x + set.sx + 3, corner.y + set.sy + 4, corner.z + set.sz + 3);
	}

	/** Dentro de la altura del mundo, reemplazable y sin ningún ser vivo dentro de la celda. */
	public static boolean free(BlockView v, BlockPos cell, List<Box> bl) {
		int x = cell.getX(), y = cell.getY(), z = cell.getZ();
		if (y < v.getBottomY() || y >= v.getTopY()) return false;
		if (!GroundRules.replaceable(v.getBlockState(cell))) return false;
		for (Box b : bl) {
			if (b.intersects(x + 0.01, y + 0.01, z + 0.01, x + 0.99, y + 0.99, z + 0.99)) return false;
		}
		return true;
	}

	/** Todas las celdas de la pieza con su (0,0,0) en base están libres. */
	public static boolean fits(BlockView v, BlockSet set, BlockPos base, List<Box> bl) {
		BlockPos.Mutable m = new BlockPos.Mutable();
		int bx = base.getX(), by = base.getY(), bz = base.getZ();
		for (BlockSet.Entry e : set.entries) {
			if (!free(v, m.set(bx + e.x(), by + e.y(), bz + e.z()), bl)) return false;
		}
		return true;
	}

	/** Algún bloque de la pieza sin otro suyo debajo tiene justo debajo algo sólido del mundo. */
	public static boolean supported(BlockView v, BlockSet set, BlockPos base) {
		BlockPos.Mutable m = new BlockPos.Mutable();
		int bx = base.getX(), by = base.getY(), bz = base.getZ();
		for (BlockSet.Entry e : set.entries) {
			if (set.contains(e.x(), e.y() - 1, e.z())) continue;
			m.set(bx + e.x(), by + e.y() - 1, bz + e.z());
			if (GroundRules.solid(v.getBlockState(m), v, m)) return true;
		}
		return false;
	}

	/**
	 * Dónde se queda la pieza si baja recta desde corner: la celda de la rejilla más cercana (o hasta 4 más arriba si
	 * esa no cabe), y desde ahí baja mientras las celdas de debajo de su cara de abajo estén libres (como mucho
	 * TAKT_LANDING_SEARCH). Null si no cabe o si al final no queda apoyada.
	 */
	@Nullable
	public static BlockPos find(BlockView v, BlockSet set, Vec3d corner, List<Box> bl) {
		BlockPos base = snap(corner);
		boolean ok = false;
		for (int k = 0; k <= 3; k++) {
			ok = fits(v, set, base, bl);
			if (ok) break;
			base = base.up();
		}
		if (!ok && !fits(v, set, base, bl)) return null;
		// los bloques sin otro suyo debajo: los únicos que entran en celdas nuevas al bajar (una vez por llamada)
		List<BlockSet.Entry> under = new ArrayList<>();
		for (BlockSet.Entry e : set.entries) if (!set.contains(e.x(), e.y() - 1, e.z())) under.add(e);
		BlockPos.Mutable m = new BlockPos.Mutable();
		for (int step = 0; step < OpeConfig.TAKT_LANDING_SEARCH; step++) {
			int bx = base.getX(), by = base.getY() - 1, bz = base.getZ();
			boolean blocked = false;
			for (BlockSet.Entry e : under) {
				if (!free(v, m.set(bx + e.x(), by + e.y(), bz + e.z()), bl)) {
					blocked = true;
					break;
				}
			}
			if (blocked) break;   // los demás bloques bajan a celdas que ya eran suyas (y estaban libres)
			base = new BlockPos(bx, by, bz);
		}
		return supported(v, set, base) ? base : null;
	}
}
