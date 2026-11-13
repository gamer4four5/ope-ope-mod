package dev.opeope.entity;

import dev.opeope.OpeConfig;
import dev.opeope.terrain.GroundRules;
import dev.opeope.terrain.Landing;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.block.AbstractPlantPartBlock;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.FluidBlock;
import net.minecraft.block.PlantBlock;
import net.minecraft.particle.BlockStateParticleEffect;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.registry.tag.BlockTags;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.BlockView;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Random;
import java.util.UUID;

/**
 * El cráter de un trozo que golpea fuerte (nivel 2 o 3) sobre terreno, a su medida. Cava un cuenco (alargado en un surco
 * si llegó rasante: ara el suelo unos metros), deja caer el trozo dentro hasta que se apoya y rellena con lo cavado el
 * hueco que le quede debajo, lanza unos terrones por el aire (piezas de un bloque que se posan donde caen) y reparte el
 * resto alrededor: un borde levantado que adelgaza hacia fuera, más hacia donde iba (el manto de lo que salta). Lo
 * colosal además agrieta el suelo en rayos y oscurece la hierba de alrededor. Lo que no cabe vuelve a una celda cavada.
 * <p>Nunca se pierde ni se duplica un bloque, nunca suelta objetos, nunca se amontona tierra donde hay un ser vivo,
 * solo cava terreno y nunca cava bajo algo que no sea una planta (una casa, un árbol, un cofre, un raíl se quedan como
 * están) ni la tierra que sujeta algo de lado o por debajo (una antorcha en la pared, una escalera de mano, un farol
 * colgado): eso se quedaría en el aire y se soltaría.
 */
public final class Crater {
	private Crater() {}

	/**
	 * Lo cavado y adónde fue: al manto (piled), de vuelta a una celda cavada (putBack), bajo el trozo (backfill) o por el
	 * aire (ejected). seated: el trozo quedó posado dentro. radius, depth y plow: las medidas con las que se cavó.
	 */
	public record Result(int removed, int piled, int putBack, int backfill, int ejected, boolean seated,
						 double radius, double depth, double plow) {
		/** Todo lo cavado fue a alguna parte. */
		public boolean balanced() {
			return removed == piled + putBack + backfill + ejected;
		}
	}

	/** El último cráter (solo en el hilo del servidor; lo mira la prueba automática). */
	public static Result last = new Result(0, 0, 0, 0, 0, false, 0, 0, 0);

	// lo que llevan cavado los cráteres de este tick (CRATER_TICK_BUDGET)
	private static long budgetTick = Long.MIN_VALUE;
	private static int budgetUsed;

	/** Un bloque cavado: dónde estaba, qué era y a cuántos bloques bajo la superficie de su columna (0 = la de arriba). */
	private record Dug(BlockPos pos, BlockState state, int depth) {}

	/** Una columna del manto: dónde, su suelo (antes de amontonar), su distancia al cuenco y su parte del manto. */
	private static final class Col {
		final int x, z, base;
		final double rho, weight;
		int target, h;
		boolean blocked;

		Col(int x, int z, int base, double rho, double weight) {
			this.x = x;
			this.z = z;
			this.base = base;
			this.rho = rho;
			this.weight = weight;
		}
	}

	/**
	 * Cava el cráter de rock (o solo el cráter, si es null) que golpea en contact: radio R en horizontal y hondo depth en
	 * el centro; si dir no es cero y plow &gt; 0, alargado plow bloques hacia dir y el trozo acaba al final del surco. Si
	 * no cabe en lo que queda de cavar en este tick (CRATER_MAX_BLOCKS, CRATER_TICK_BUDGET), todo más pequeño en
	 * proporción. Si el trozo encuentra sitio dentro, queda posado (Result.seated); si no, lo posa quien llama.
	 */
	public static Result dig(ServerWorld w, @Nullable PieceEntity rock, Vec3d contact, Vec3d dir, double plow, double R,
							 double depth, int tier) {
		long now = w.getTime();
		if (now != budgetTick) {
			budgetTick = now;
			budgetUsed = 0;
		}
		// cada bloque cavado cuenta dos veces en el presupuesto del tick: se quita y se vuelve a poner
		int cap = Math.min(OpeConfig.CRATER_MAX_BLOCKS, Math.max(150, (OpeConfig.CRATER_TICK_BUDGET - budgetUsed) / 2));
		if (dir.lengthSquared() < 1e-6) plow = 0;
		double vol = Math.PI / 2 * R * R * depth + 4.0 / 3 * R * depth * plow;
		if (vol > cap) {
			double s = Math.cbrt(cap / vol);
			R *= s;
			depth *= s;
			plow *= s;
		}
		R = Math.max(1.0, R);
		depth = Math.max(0.6, depth);
		BlockView view = GroundRules.loaded(w);
		Random rnd = new Random(BlockPos.ofFloored(contact).asLong() * 31 + now);
		double ax = contact.x, az = contact.z, bx = ax + dir.x * plow, bz = az + dir.z * plow;
		int yRef = MathHelper.floor(contact.y) - 1;
		double outer = R + Math.max(2.0, 0.9 * R);   // el manto llega hasta aquí, medido desde el surco
		List<Box> bl = Landing.blockers(w, new Box(Math.min(ax, bx) - outer - 1, contact.y - 16, Math.min(az, bz) - outer - 1,
			Math.max(ax, bx) + outer + 1, contact.y + 16, Math.max(az, bz) + outer + 1));

		List<Dug> dug = new ArrayList<>();
		LongOpenHashSet dugSet = new LongOpenHashSet();
		carve(w, view, ax, az, bx, bz, R, depth, yRef, cap, dug, dugSet);
		budgetUsed += 2 * dug.size();
		for (Dug d : dug) w.updateNeighbors(d.pos(), Blocks.AIR);

		// el trozo baja dentro del cuenco hasta apoyarse: si ara, desde lo más lejos que llega por el surco sin atravesar
		// nada (un muro, una casa, una ladera que no se cavó); si no, o si ahí no cabe, desde donde está
		BlockPos seat = null;
		if (rock != null && !dug.isEmpty()) {
			BlockSet set = rock.blocks;
			Vec3d here = rock.corner(), start = here;
			if (plow > 0) {
				double ex = bx - set.sx / 2.0 - here.x, ez = bz - set.sz / 2.0 - here.z;
				int steps = (int) Math.ceil(Math.hypot(ex, ez));
				for (int i = 1; i <= steps; i++) {
					Vec3d c = here.add(ex * i / steps, 0, ez * i / steps);
					if (!Landing.fits(view, set, Landing.snap(c), bl)) break;
					start = c;
				}
			}
			seat = Landing.find(view, set, start, bl);
			if (seat == null && start != here) seat = Landing.find(view, set, here, bl);
		}
		ArrayDeque<Dug> pool = new ArrayDeque<>(dug.size());
		List<Dug> sorted = new ArrayList<>(dug);
		sorted.sort(Comparator.comparingInt(Dug::depth).reversed());   // lo más hondo primero
		pool.addAll(sorted);
		int backfill = 0;
		double cx = bx, cz = bz;
		if (seat != null) {
			backfill = backfill(w, view, rock.blocks, seat, dugSet, bl, pool);
			rock.seat(seat);
			cx = seat.getX() + rock.blocks.sx / 2.0;
			cz = seat.getZ() + rock.blocks.sz / 2.0;
		}
		int ejected = pool.isEmpty() ? 0 : eject(w, view, rock, pool, cx, contact.y, cz, R, dir, tier, rnd);
		List<Col> cols = rim(view, ax, az, bx, bz, R, outer, yRef, dir);
		int piled = pile(w, view, bl, cols, pool, R, rnd);
		int putBack = putBack(w, view, bl, dug, pool, cols);
		scorch(w, view, cols, R, rnd);
		if (tier >= 3) cracks(w, view, bx, bz, R, yRef, rnd);
		dust(w, dug);
		last = new Result(dug.size(), piled, putBack, backfill, ejected, seat != null, R, depth, plow);
		return last;
	}

	/** Distancia horizontal de (x, z) al segmento a–b. */
	private static double seg(double x, double z, double ax, double az, double bx, double bz) {
		double ex = bx - ax, ez = bz - az, l2 = ex * ex + ez * ez;
		double u = l2 < 1e-9 ? 0 : MathHelper.clamp(((x - ax) * ex + (z - az) * ez) / l2, 0, 1);
		return Math.hypot(x - ax - ex * u, z - az - ez * u);
	}

	/**
	 * El cuenco: las columnas a menos de R del surco, de la más cercana a la más lejana (si se llega al tope, el cráter
	 * queda redondo); en cada una, desde su superficie hacia abajo hasta la hondura de esa columna, solo terreno y solo
	 * lo que no sujeta nada (holdsNothing). Las plantas de encima se van con el golpe (sin soltar nada); una columna con
	 * algo encima que no es una planta no se cava.
	 */
	private static void carve(ServerWorld w, BlockView view, double ax, double az, double bx, double bz, double R, double depth,
							  int yRef, int cap, List<Dug> out, LongOpenHashSet set) {
		int x0 = MathHelper.floor(Math.min(ax, bx) - R), x1 = MathHelper.floor(Math.max(ax, bx) + R);
		int z0 = MathHelper.floor(Math.min(az, bz) - R), z1 = MathHelper.floor(Math.max(az, bz) + R);
		List<double[]> cols = new ArrayList<>();
		for (int x = x0; x <= x1; x++) {
			for (int z = z0; z <= z1; z++) {
				double rho = seg(x + 0.5, z + 0.5, ax, az, bx, bz);
				if (rho <= R) cols.add(new double[]{x, z, rho});
			}
		}
		cols.sort(Comparator.comparingDouble(c -> c[2]));
		BlockPos.Mutable m = new BlockPos.Mutable();
		double r2 = R * R;
		for (double[] c : cols) {
			int x = (int) c[0], z = (int) c[1];
			double d = depth * (1 - c[2] * c[2] / r2);
			if (d < 0.5) continue;
			int top = GroundRules.surfaceNear(view, x, z, yRef, 4);
			if (top == GroundRules.NONE || !clearAbove(w, view, m, x, top + 1, z)) continue;
			for (int y = top, k = 0; y + 0.5 >= top + 1 - d; y--, k++) {
				m.set(x, y, z);
				BlockState st = view.getBlockState(m);
				if (!GroundRules.terrain(st) || !GroundRules.cuttable(st, view, m) || st.hasBlockEntity() || st.getHardness(view, m) >= 50
					|| !holdsNothing(view, m, set)) break;
				BlockPos p = m.toImmutable();
				out.add(new Dug(p, st, k));
				set.add(p.asLong());
				w.setBlockState(p, Blocks.AIR.getDefaultState(), Block.NOTIFY_LISTENERS | Block.FORCE_STATE | Block.SKIP_DROPS);
				if (out.size() >= cap) return;
			}
		}
	}

	/**
	 * Lo que hay encima de la superficie de una columna (desde y, dos celdas): aire, agua o lava, o plantas y nieve fina
	 * que el golpe se lleva (se quitan sin soltar nada; las de bajo el agua dejan su agua, y el kelp se va entero).
	 * false si hay otra cosa (un muro, un tronco, un cofre, un raíl, una antorcha, algo anegado): esa columna no se cava.
	 */
	private static boolean clearAbove(ServerWorld w, BlockView view, BlockPos.Mutable m, int x, int y, int z) {
		for (int k = 0; k < 2; k++) {
			m.set(x, y + k, z);
			BlockState st = view.getBlockState(m);
			if (st.isAir() || st.getBlock() instanceof FluidBlock) return true;
			if (st.isOf(Blocks.KELP) || st.isOf(Blocks.KELP_PLANT)) {
				// el kelp crece del suelo hacia arriba: se va todo el tallo (si no, el resto se soltaría flotando)
				for (int j = 0; j < 64 && (st.isOf(Blocks.KELP) || st.isOf(Blocks.KELP_PLANT)); j++) {
					w.setBlockState(m.toImmutable(), Blocks.WATER.getDefaultState(), Block.NOTIFY_LISTENERS | Block.FORCE_STATE | Block.SKIP_DROPS);
					st = view.getBlockState(m.move(Direction.UP));
				}
				return true;
			}
			if (!sweptAway(st)) return k > 0;
			BlockState left = st.getFluidState().isEmpty() ? Blocks.AIR.getDefaultState() : st.getFluidState().getBlockState();
			w.setBlockState(m.toImmutable(), left, Block.NOTIFY_LISTENERS | Block.FORCE_STATE | Block.SKIP_DROPS);
		}
		return true;
	}

	/**
	 * Lo que el golpe se lleva de encima del suelo: plantas que crecen de él (flores, hierba, helechos, retoños,
	 * cultivos, setas, hierba marina, pepinos de mar), raíces y nieve fina. Las hojas no (son de un árbol), ni nada con
	 * entidad de bloque.
	 */
	private static boolean sweptAway(BlockState st) {
		if (st.hasBlockEntity()) return false;
		if (st.getBlock() instanceof PlantBlock || st.isOf(Blocks.SNOW)) return true;
		return st.isIn(BlockTags.REPLACEABLE_BY_TREES) && !st.isIn(BlockTags.LEAVES);
	}

	private static final Direction[] HOLDS = {Direction.NORTH, Direction.SOUTH, Direction.WEST, Direction.EAST, Direction.DOWN};

	/**
	 * ¿Se puede cavar la celda m sin dejar en el aire algo que se apoya en ella? Sus vecinos de los lados y el de debajo
	 * tienen que ser aire, agua o lava, terreno, un bloque entero, algo ya cavado o, a los lados, una planta de suelo. Una
	 * antorcha o una escalera de mano en su cara, un raíl que sube por ella, una puerta, una valla o un farol colgado
	 * debajo dicen que no: esa tierra se queda (y con ella, lo que queda de su columna).
	 */
	private static boolean holdsNothing(BlockView view, BlockPos m, LongOpenHashSet dug) {
		BlockPos.Mutable n = new BlockPos.Mutable();
		for (Direction d : HOLDS) {
			n.set(m, d);
			if (dug.contains(n.asLong())) continue;
			BlockState s = view.getBlockState(n);
			if (s.isAir() || s.getBlock() instanceof FluidBlock || GroundRules.terrain(s) || s.isFullCube(view, n)) continue;
			if (d != Direction.DOWN && !s.hasBlockEntity() && (s.getBlock() instanceof PlantBlock
				|| s.getBlock() instanceof AbstractPlantPartBlock || s.isOf(Blocks.SNOW))) continue;
			return false;
		}
		return true;
	}

	/**
	 * Rellena con lo cavado (lo más hondo primero) las celdas cavadas que quedan libres bajo la cara de abajo del trozo
	 * posado en seat: queda apoyado en tierra apretada, sin un hueco escondido debajo. Devuelve cuántos puso.
	 */
	private static int backfill(ServerWorld w, BlockView view, BlockSet set, BlockPos seat, LongOpenHashSet dugSet, List<Box> bl,
								ArrayDeque<Dug> pool) {
		int n = 0;
		BlockPos.Mutable m = new BlockPos.Mutable();
		for (BlockSet.Entry e : set.entries) {
			if (set.contains(e.x(), e.y() - 1, e.z())) continue;
			int x = seat.getX() + e.x(), z = seat.getZ() + e.z();
			for (int y = seat.getY() + e.y() - 1; !pool.isEmpty(); y--) {
				m.set(x, y, z);
				if (set.contains(x - seat.getX(), y - seat.getY(), z - seat.getZ())) break;   // el trozo mismo (un hueco suyo)
				if (!dugSet.contains(m.asLong()) || !Landing.free(view, m, bl)) break;
				w.setBlockState(m.toImmutable(), pool.pollFirst().state(), Block.NOTIFY_LISTENERS | Block.FORCE_STATE);
				n++;
			}
		}
		return n;
	}

	/**
	 * Unos terrones salen volando (lo de la superficie: hierba, tierra) y caen alrededor, entre 1,15 y 2,75 radios, más
	 * hacia donde iba si llegó con fuerza de lado. Son piezas de un bloque (debris) que se posan donde caen; como mucho
	 * EJECTA_MAX por cráter y EJECTA_LIVE_MAX en el aire cerca. Salen del borde del trozo, a ras del suelo de antes, y
	 * solo hacia donde hay suelo en el que caer (en el borde de una isla no se pierden en el vacío: se quedan en el manto).
	 */
	private static int eject(ServerWorld w, BlockView view, @Nullable PieceEntity rock, ArrayDeque<Dug> pool, double cx, double cy,
							 double cz, double R, Vec3d dir, int tier, Random rnd) {
		int want = MathHelper.clamp((int) Math.round(pool.size() * 0.08), tier >= 3 ? 6 : 3, OpeConfig.EJECTA_MAX);
		Box near = new Box(cx - 96, cy - 64, cz - 96, cx + 96, cy + 64, cz + 96);
		int live = w.getEntitiesByClass(PieceEntity.class, near, pe -> pe.debris && !pe.isRemoved()).size();
		int n = Math.min(Math.min(want, pool.size()), OpeConfig.EJECTA_LIVE_MAX - live);
		if (n <= 0) return 0;
		double rr = rock == null ? 0.6 : 0.5 * Math.sqrt((double) rock.blocks.sx * rock.blocks.sx + (double) rock.blocks.sz * rock.blocks.sz) + 0.6;
		UUID owner = rock == null ? null : rock.mover();
		boolean aimed = dir.lengthSquared() > 1e-6;
		double heading = aimed ? Math.atan2(dir.z, dir.x) : 0;
		int yRef = MathHelper.floor(cy) - 1, done = 0;
		for (int i = 0; i < n; i++) {
			double a = 0, dist = 0;
			boolean ground = false;
			for (int t = 0; t < 4 && !ground; t++) {
				a = aimed && rnd.nextDouble() < 0.6 ? heading + rnd.nextGaussian() * 0.9 : rnd.nextDouble() * 2 * Math.PI;
				dist = Math.max(1.5, R * (1.15 + 1.6 * Math.pow(rnd.nextDouble(), 1.4)) - rr);
				int lx = MathHelper.floor(cx + Math.cos(a) * (rr + dist)), lz = MathHelper.floor(cz + Math.sin(a) * (rr + dist));
				ground = GroundRules.surfaceNear(view, lx, lz, yRef, 12) != GroundRules.NONE;
			}
			if (!ground) break;   // ni así: lo que queda va al manto
			Dug d = pool.pollLast();
			done++;
			double el = Math.toRadians(48 + 20 * rnd.nextDouble());
			double sp = Math.min(2.2, Math.sqrt(0.06 * dist / Math.sin(2 * el)) * 1.12);   // un poco más por el aire
			double ca = Math.cos(a), sa = Math.sin(a), ce = Math.cos(el);
			double px = cx + ca * rr, pz = cz + sa * rr, py = cy + 0.3 + 0.4 * rnd.nextDouble();
			BlockSet set = new BlockSet();
			set.add(0, 0, 0, d.state(), null);
			PieceEntity e = PieceEntity.spawn(w, set, new Vec3d(px - 0.5, py, pz - 0.5), d.pos(), owner);
			e.debris = true;
			e.release(new Vec3d(ca * ce * sp, Math.sin(el) * sp, sa * ce * sp), false);
		}
		return done;
	}

	/**
	 * Las columnas del manto (entre R y outer del surco) con suelo, de la más cercana a la más lejana, cada una con su
	 * parte: (R/ρ)³, más hacia donde iba y menos hacia atrás.
	 */
	private static List<Col> rim(BlockView view, double ax, double az, double bx, double bz, double R, double outer, int yRef, Vec3d dir) {
		List<Col> out = new ArrayList<>();
		int x0 = MathHelper.floor(Math.min(ax, bx) - outer), x1 = MathHelper.floor(Math.max(ax, bx) + outer);
		int z0 = MathHelper.floor(Math.min(az, bz) - outer), z1 = MathHelper.floor(Math.max(az, bz) + outer);
		boolean aimed = dir.lengthSquared() > 1e-6;
		for (int x = x0; x <= x1; x++) {
			for (int z = z0; z <= z1; z++) {
				double rho = seg(x + 0.5, z + 0.5, ax, az, bx, bz);
				if (rho <= R || rho > outer) continue;
				int s = GroundRules.surfaceNear(view, x, z, yRef, 5);
				if (s == GroundRules.NONE) continue;
				double wgt = Math.pow(R / rho, 3);
				if (aimed) {
					double ex = x + 0.5 - bx, ez = z + 0.5 - bz, el = Math.hypot(ex, ez);
					if (el > 1e-3) {
						double c = (ex * dir.x + ez * dir.z) / el;
						wgt *= 1 + 0.7 * Math.max(0, c) - 0.45 * Math.max(0, -c);
					}
				}
				out.add(new Col(x, z, s, rho, wgt));
			}
		}
		out.sort(Comparator.comparingDouble(c -> c.rho));
		return out;
	}

	/**
	 * Amontona en el manto lo que queda (lo más hondo en las columnas más cercanas: la roca de abajo acaba en el borde y
	 * la hierba de arriba, lejos). Cada columna crece hasta su parte del manto (como mucho 0,22·R + 1,5 de alto); si
	 * sobra, capas de una en una, de la más cercana a la más lejana. Solo en celdas libres (sin bloque y sin ser vivo).
	 */
	private static int pile(ServerWorld w, BlockView view, List<Box> bl, List<Col> cols, ArrayDeque<Dug> pool, double R, Random rnd) {
		if (cols.isEmpty() || pool.isEmpty()) return 0;
		double sum = 0;
		for (Col c : cols) sum += c.weight;
		double t = pool.size() / Math.max(sum, 1e-9);
		int hmax = Math.max(2, (int) Math.round(0.22 * R + 1.5));
		for (Col c : cols) {
			double want = Math.min(hmax, t * c.weight);
			int h = (int) Math.floor(want);
			if (rnd.nextDouble() < want - h) h++;
			c.target = h;
		}
		int piled = 0;
		BlockPos.Mutable q = new BlockPos.Mutable();
		for (int pass = 0; pass < 10 && !pool.isEmpty(); pass++) {
			boolean grew = false;
			for (Col c : cols) {
				if (pool.isEmpty()) break;
				if (c.blocked) continue;
				int goal = pass == 0 ? c.target : Math.min(c.h + 1, hmax + pass);
				while (c.h < goal && !pool.isEmpty()) {
					q.set(c.x, c.base + 1 + c.h, c.z);
					if (!Landing.free(view, q, bl)) {
						c.blocked = true;
						break;
					}
					w.setBlockState(q.toImmutable(), pool.pollFirst().state(), Block.NOTIFY_ALL);
					c.h++;
					piled++;
					grew = true;
				}
			}
			if (pass > 0 && !grew) break;
		}
		return piled;
	}

	/**
	 * Lo que no cupo en el manto vuelve a las celdas cavadas que sigan libres, de abajo arriba (nada queda en el aire).
	 * Si ni así (todo ocupado por el trozo), encima de su columna del manto más cercana o, en último caso, en la primera
	 * celda libre sobre la suya: nunca se pierde.
	 */
	private static int putBack(ServerWorld w, BlockView view, List<Box> bl, List<Dug> dug, ArrayDeque<Dug> pool, List<Col> cols) {
		if (pool.isEmpty()) return 0;
		int n = 0;
		List<BlockPos> cells = new ArrayList<>(dug.size());
		for (Dug d : dug) cells.add(d.pos());
		cells.sort(Comparator.comparingInt(BlockPos::getY));
		for (BlockPos p : cells) {
			if (pool.isEmpty()) return n;
			if (!Landing.free(view, p, bl)) continue;
			w.setBlockState(p, pool.pollFirst().state(), Block.NOTIFY_ALL);
			n++;
		}
		BlockPos.Mutable q = new BlockPos.Mutable();
		for (Col c : cols) {
			while (!pool.isEmpty() && c.h < 32) {
				q.set(c.x, c.base + 1 + c.h, c.z);
				if (!Landing.free(view, q, bl)) break;
				w.setBlockState(q.toImmutable(), pool.pollFirst().state(), Block.NOTIFY_ALL);
				c.h++;
				n++;
			}
			if (pool.isEmpty()) return n;
		}
		while (!pool.isEmpty()) {
			Dug d = pool.pollFirst();
			BlockPos p = d.pos();
			for (int y = p.getY(); y < w.getTopY(); y++) {
				q.set(p.getX(), y, p.getZ());
				if (!Landing.free(view, q, bl)) continue;
				w.setBlockState(q.toImmutable(), d.state(), Block.NOTIFY_ALL);
				n++;
				break;
			}
		}
		return n;
	}

	/** Alrededor, la hierba que no tapó el manto se queda en tierra a trozos (más cerca, más): el golpe la arrancó. */
	private static void scorch(ServerWorld w, BlockView view, List<Col> cols, double R, Random rnd) {
		BlockPos.Mutable m = new BlockPos.Mutable();
		for (Col c : cols) {
			if (c.h > 0) continue;
			double k = R / c.rho;
			if (rnd.nextDouble() >= 0.55 * k * k) continue;
			m.set(c.x, c.base, c.z);
			BlockState st = view.getBlockState(m);
			if (!st.isOf(Blocks.GRASS_BLOCK) && !st.isOf(Blocks.PODZOL) && !st.isOf(Blocks.MYCELIUM)) continue;
			w.setBlockState(m.toImmutable(), (rnd.nextDouble() < 0.35 ? Blocks.COARSE_DIRT : Blocks.DIRT).getDefaultState(), Block.NOTIFY_LISTENERS);
		}
	}

	/** Lo colosal agrieta el suelo: de 6 a 9 rayos quebrados que salen del borde (tierra suelta en la tierra, grava de roca en la roca). */
	private static void cracks(ServerWorld w, BlockView view, double cx, double cz, double R, int yRef, Random rnd) {
		int n = 6 + rnd.nextInt(4);
		BlockPos.Mutable m = new BlockPos.Mutable();
		for (int i = 0; i < n; i++) {
			double a = (i + rnd.nextDouble() * 0.5) / n * 2 * Math.PI;
			double len = R * (0.6 + rnd.nextDouble());
			double x = cx + Math.cos(a) * R * 0.95, z = cz + Math.sin(a) * R * 0.95;
			int steps = Math.max(4, (int) Math.ceil(len / 0.7));
			for (int j = 0; j < steps; j++) {
				if (j % 3 == 0) a += (rnd.nextDouble() - 0.5) * 0.7;
				x += Math.cos(a) * len / steps;
				z += Math.sin(a) * len / steps;
				crack(w, view, m, MathHelper.floor(x), MathHelper.floor(z), yRef);
				if (j < steps / 2) crack(w, view, m, MathHelper.floor(x + Math.sin(a) * 0.7), MathHelper.floor(z - Math.cos(a) * 0.7), yRef);
			}
		}
	}

	private static void crack(ServerWorld w, BlockView view, BlockPos.Mutable m, int x, int z, int yRef) {
		int top = GroundRules.surfaceNear(view, x, z, yRef, 6);
		if (top == GroundRules.NONE) return;
		m.set(x, top, z);
		BlockState to = cracked(view.getBlockState(m));
		if (to != null) w.setBlockState(m.toImmutable(), to, Block.NOTIFY_LISTENERS);
	}

	@Nullable
	private static BlockState cracked(BlockState st) {
		Block b = st.getBlock();
		if (b == Blocks.GRASS_BLOCK || b == Blocks.DIRT || b == Blocks.PODZOL || b == Blocks.MYCELIUM || b == Blocks.ROOTED_DIRT) {
			return Blocks.COARSE_DIRT.getDefaultState();
		}
		if (b == Blocks.STONE || b == Blocks.ANDESITE || b == Blocks.DIORITE || b == Blocks.GRANITE || b == Blocks.TUFF) {
			return Blocks.COBBLESTONE.getDefaultState();
		}
		if (b == Blocks.DEEPSLATE) return Blocks.COBBLED_DEEPSLATE.getDefaultState();
		return null;
	}

	/** Polvo del material: 3 partículas en uno de cada ⌈cavados/60⌉ bloques cavados (el resto lo pone el cliente). */
	private static void dust(ServerWorld w, List<Dug> dug) {
		int n = dug.size();
		if (n == 0) return;
		int stride = Math.max(1, (int) Math.ceil(n / 60.0));
		for (int i = 0; i < n; i += stride) {
			Dug d = dug.get(i);
			BlockPos p = d.pos();
			w.spawnParticles(new BlockStateParticleEffect(ParticleTypes.BLOCK, d.state()), p.getX() + 0.5, p.getY() + 0.5, p.getZ() + 0.5,
				3, 0.3, 0.3, 0.3, 0.15);
		}
	}
}
