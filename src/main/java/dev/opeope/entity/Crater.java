package dev.opeope.entity;

import dev.opeope.terrain.GroundRules;
import dev.opeope.terrain.Landing;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.particle.BlockStateParticleEffect;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.BlockView;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * El cráter de un trozo lanzado que golpea fuerte: cava un cuenco en el terreno, amontona lo cavado en el borde y, si no
 * cabe, lo devuelve a su sitio. Nunca se pierde ni se duplica un bloque, y nunca se amontona tierra donde hay un ser vivo.
 */
public final class Crater {
	private Crater() {}

	/** Bloques cavados, amontonados en el borde y devueltos a su sitio (siempre removed == piled + putBack). */
	public record Result(int removed, int piled, int putBack) {}

	/** El último cráter (solo en el hilo del servidor; lo mira la prueba automática). */
	public static Result last = new Result(0, 0, 0);

	/** Una columna del borde: dónde está, su suelo (calculado antes de amontonar nada) y su ángulo y distancia al centro. */
	private record Rim(int x, int z, int base, double angle, double rho) {}

	/**
	 * Cava el cráter en contact (radio R en horizontal, hondo depth en el centro, a lo sumo maxBlocks bloques) y amontona lo
	 * cavado en el borde, entre R y R + 1,5. Lo que no cabe en el borde vuelve a su celda.
	 */
	public static Result dig(ServerWorld w, Vec3d contact, double R, double depth, int maxBlocks) {
		BlockView view = GroundRules.loaded(w);
		// En vertical, todo lo que el borde puede tocar: su suelo está a ±4 de yRef y se amontona hasta 4 encima.
		List<Box> bl = Landing.blockers(w, new Box(contact, contact).expand(R + 3, 10, R + 3));
		int yRef = MathHelper.floor(contact.y) - 1;
		List<BlockPos> cells = new ArrayList<>();
		List<BlockState> states = new ArrayList<>();
		if (R > 0 && depth > 0 && maxBlocks > 0) carve(w, view, contact, R, depth, yRef, maxBlocks, cells, states);
		for (BlockPos p : cells) w.updateNeighbors(p, Blocks.AIR);
		boolean[] done = new boolean[cells.size()];
		int piled = cells.isEmpty() ? 0 : pile(w, view, bl, rim(view, contact, R, yRef), states, done);
		int putBack = 0;
		for (int i = 0; i < cells.size(); i++) {
			if (done[i]) continue;
			w.setBlockState(cells.get(i), states.get(i), Block.NOTIFY_ALL);
			putBack++;
		}
		dust(w, cells, states);
		last = new Result(cells.size(), piled, putBack);
		return last;
	}

	/** El cuenco: columnas por x y luego z; en cada una, desde su superficie hacia abajo hasta la hondura de esa columna. */
	private static void carve(ServerWorld w, BlockView view, Vec3d c, double R, double depth, int yRef, int maxBlocks,
			List<BlockPos> cells, List<BlockState> states) {
		BlockPos.Mutable m = new BlockPos.Mutable();
		double r2 = R * R;
		int x0 = MathHelper.floor(c.x - R), x1 = MathHelper.floor(c.x + R);
		int z0 = MathHelper.floor(c.z - R), z1 = MathHelper.floor(c.z + R);
		for (int x = x0; x <= x1; x++) {
			for (int z = z0; z <= z1; z++) {
				double dx = x + 0.5 - c.x, dz = z + 0.5 - c.z, rho2 = dx * dx + dz * dz;
				if (rho2 > r2) continue;
				double d = depth * (1 - rho2 / r2);
				int top = GroundRules.surfaceNear(view, x, z, yRef, 4);
				if (top == GroundRules.NONE) continue;
				for (int y = top; y + 0.5 >= top + 1 - d; y--) {
					m.set(x, y, z);
					BlockState st = view.getBlockState(m);
					if (!GroundRules.cuttable(st, view, m) || st.hasBlockEntity() || st.getHardness(view, m) >= 50) break;
					BlockPos p = m.toImmutable();
					cells.add(p);
					states.add(st);
					w.setBlockState(p, Blocks.AIR.getDefaultState(), Block.NOTIFY_LISTENERS | Block.FORCE_STATE | Block.SKIP_DROPS);
					if (cells.size() >= maxBlocks) return;
				}
			}
		}
	}

	/** Las columnas del borde con suelo, ordenadas por ángulo y luego por distancia. */
	private static List<Rim> rim(BlockView view, Vec3d c, double R, int yRef) {
		List<Rim> out = new ArrayList<>();
		double outer = R + 1.5;
		int x0 = MathHelper.floor(c.x - outer), x1 = MathHelper.floor(c.x + outer);
		int z0 = MathHelper.floor(c.z - outer), z1 = MathHelper.floor(c.z + outer);
		for (int x = x0; x <= x1; x++) {
			for (int z = z0; z <= z1; z++) {
				double dx = x + 0.5 - c.x, dz = z + 0.5 - c.z, rho = Math.sqrt(dx * dx + dz * dz);
				if (rho <= R || rho > outer) continue;
				int s = GroundRules.surfaceNear(view, x, z, yRef, 4);
				if (s == GroundRules.NONE) continue;
				out.add(new Rim(x, z, s, Math.atan2(dz, dx), rho));
			}
		}
		out.sort(Comparator.comparingDouble(Rim::angle).thenComparingDouble(Rim::rho));
		return out;
	}

	/**
	 * Amontona en el borde: primero hasta 2 de alto, luego 3, luego 4. Cada bloque cavado prueba las columnas a partir de la
	 * suya (i % columnas) y se queda en la primera que tenga sitio libre (sin bloque y sin ser vivo). Devuelve cuántos puso.
	 */
	private static int pile(ServerWorld w, BlockView view, List<Box> bl, List<Rim> rim, List<BlockState> states, boolean[] done) {
		int n = rim.size();
		if (n == 0) return 0;
		int[] height = new int[n];
		boolean[] blocked = new boolean[n];   // la celda siguiente de esa columna está ocupada: ya no crece
		BlockPos.Mutable q = new BlockPos.Mutable();
		int piled = 0;
		for (int cap = 2; cap <= 4; cap++) {
			for (int i = 0; i < states.size(); i++) {
				if (done[i]) continue;
				for (int t = 0; t < n; t++) {
					int j = (i + t) % n;
					if (blocked[j] || height[j] >= cap) continue;
					Rim col = rim.get(j);
					q.set(col.x(), col.base() + 1 + height[j], col.z());
					if (!Landing.free(view, q, bl)) {
						blocked[j] = true;
						continue;
					}
					w.setBlockState(q.toImmutable(), states.get(i), Block.NOTIFY_ALL);
					height[j]++;
					piled++;
					done[i] = true;
					break;
				}
			}
		}
		return piled;
	}

	/** Polvo del material: 3 partículas en uno de cada ⌈cavados/80⌉ bloques cavados. */
	private static void dust(ServerWorld w, List<BlockPos> cells, List<BlockState> states) {
		int n = cells.size();
		if (n == 0) return;
		int stride = Math.max(1, (int) Math.ceil(n / 80.0));
		for (int i = 0; i < n; i += stride) {
			BlockPos p = cells.get(i);
			w.spawnParticles(new BlockStateParticleEffect(ParticleTypes.BLOCK, states.get(i)), p.getX() + 0.5, p.getY() + 0.5, p.getZ() + 0.5,
				3, 0.3, 0.3, 0.3, 0.15);
		}
	}
}
