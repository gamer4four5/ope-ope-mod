package dev.opeope.terrain;

import dev.opeope.OpeConfig;
import dev.opeope.net.Payloads;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayFIFOQueue;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongIterator;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.registry.tag.BlockTags;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.BlockView;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Random;
import java.util.TreeSet;
import java.util.UUID;

/**
 * El trozo de suelo que arranca Takt (4.2). El mismo cálculo da el arranque en el servidor y la vista previa en el
 * cliente, así que todo es determinista: los cortes se ordenan por id, el disco sale de un java.util.Random con semilla
 * fija y StrictMath, y cada búsqueda recorre las celdas en un orden fijo (ningún orden de HashSet decide nada).
 * <p>
 * Plan de suelo: los cortes que encierran la mira dan la forma exacta; si no la cierran, un disco irregular del tamaño
 * elegido, recortado por los cortes que lo cruzan. Las columnas salen de una inundación que sigue el terreno (escalones
 * de hasta 3), así que en una cueva sigue su suelo. Lo que va encima (árboles, casas, plantas) viene entero o no viene.
 * Plan de cara: en una pared, un talud o un techo, un pedazo tosco alrededor del bloque mirado.
 * <p>
 * Sin estado estático mutable: el hilo del cliente y el del servidor integrado lo usan a la vez.
 */
public final class ChunkPlanner {
	private ChunkPlanner() {}

	/** El bit de borde (columnas) o de cara al aire (celdas). */
	private static final int EDGE = 1 << 30;
	private static final Direction[] DIRS = Direction.values();
	/** Marcas de la búsqueda de lo que va encima. */
	private static final int TAKEN = 1, LEFT = 2;

	/** Modos para lo que va encima del suelo (árboles, plantas, edificios). */
	public static final int RIDER_AUTO = 0, RIDER_WITH_TREES = 1, RIDER_TREE_ONLY = 2, RIDER_NONE = 3;
	private static final String[] RIDER_NAMES = { "Normal", "+Árbol", "Solo árbol", "Solo piso" };

	public static String riderModeName(int mode) {
		return RIDER_NAMES[mode >= 0 && mode < RIDER_NAMES.length ? mode : 0];
	}

	/** Un plano orientado con la mira dentro (n·x ≤ d) y el id de su corte. */
	private record Pl(double nx, double ny, double nz, double d, int id) {}

	/** Una columna incluida: su cima y la y de cada bloque que se lleva, ascendentes. */
	private record Col(int x, int z, int top, int[] ys) {}

	// ------------------------------------------------------------------ entradas

	/**
	 * Los cortes que pueden dar forma a un arranque en a: del dueño, de la misma dimensión, sin caducar en now, con el
	 * plano a TAKT_CUT_REACH como mucho de a y a dentro de su caja ± TAKT_CUT_REACH. Ordenados por id.
	 */
	public static List<GroundCut> near(Collection<GroundCut> cuts, UUID owner, Identifier dim, Vec3d a, long now) {
		List<GroundCut> out = new ArrayList<>();
		for (GroundCut c : cuts) {
			if (c == null || !c.owner.equals(owner) || !c.dim.equals(dim) || c.expired(now)) continue;
			if (!(Math.abs(c.dist(a)) <= OpeConfig.TAKT_CUT_REACH)) continue;
			if (!c.box.expand(OpeConfig.TAKT_CUT_REACH).contains(a)) continue;
			out.add(c);
		}
		out.sort(Comparator.comparingInt(c -> c.id));
		return out;
	}

	/**
	 * 4.2.3: el bloque de superficie para el bloque al que se mira, o null (entonces plan() hace un plan de cara).
	 * Terreno con aire encima: él mismo. Terreno con terreno encima (un lado, una pared de tierra, un techo): null.
	 * Otra cosa (una planta, un tronco, una casa): el suelo que tiene debajo, hasta TAKT_AIM_DOWN más abajo.
	 */
	@Nullable
	public static BlockPos surfaceAim(BlockView v, BlockPos hit) {
		BlockPos.Mutable m = new BlockPos.Mutable();
		int x = hit.getX(), y = hit.getY(), z = hit.getZ();
		if (GroundRules.terrain(v.getBlockState(m.set(x, y, z)))) {
			return GroundRules.terrain(v.getBlockState(m.set(x, y + 1, z))) ? null : new BlockPos(x, y, z);
		}
		boolean above = false;   // el bloque de encima de y es terreno (el primero es el mirado, que no lo es)
		for (int yy = y - 1; yy >= y - OpeConfig.TAKT_AIM_DOWN; yy--) {
			boolean t = GroundRules.terrain(v.getBlockState(m.set(x, yy, z)));
			if (t && !above) return new BlockPos(x, yy, z);
			above = t;
		}
		return null;
	}

	/** Igual que {@link #surfaceAim}, pero en modo +Árbol busca suelo hasta 48 bloques debajo de troncos o copas altas. */
	@Nullable
	public static BlockPos surfaceAimForMode(BlockView v, BlockPos hit, int riderMode) {
		BlockPos direct = surfaceAim(v, hit);
		if (direct != null || riderMode != RIDER_WITH_TREES) return direct;
		BlockState st = v.getBlockState(hit);
		if (!isTreeWood(st) && !isTreeLeaf(st)) return null;
		BlockPos.Mutable m = new BlockPos.Mutable();
		int x = hit.getX(), y = hit.getY(), z = hit.getZ();
		int bot = Math.max(v.getBottomY(), y - 48);
		boolean above = false;
		for (int yy = y - 1; yy >= bot; yy--) {
			boolean t = GroundRules.terrain(v.getBlockState(m.set(x, yy, z)));
			if (t && !above) return new BlockPos(x, yy, z);
			above = t;
		}
		BlockPos best = null;
		int bestY = Integer.MIN_VALUE;
		for (int dx = -1; dx <= 1; dx++) {
			for (int dz = -1; dz <= 1; dz++) {
				if (dx == 0 && dz == 0) continue;
				above = GroundRules.terrain(v.getBlockState(m.set(x + dx, y, z + dz)));
				for (int yy = y - 1; yy >= bot; yy--) {
					boolean t = GroundRules.terrain(v.getBlockState(m.set(x + dx, yy, z + dz)));
					if (t && !above) {
						if (yy > bestY) {
							bestY = yy;
							best = new BlockPos(x + dx, yy, z + dz);
						}
						break;
					}
					above = t;
				}
			}
		}
		return best;
	}

	/** El centro de la cara de arriba del bloque de superficie: (x + 0,5, y + 1, z + 0,5). */
	public static Vec3d aimPoint(BlockPos aimSurface) {
		return new Vec3d(aimSurface.getX() + 0.5, aimSurface.getY() + 1, aimSurface.getZ() + 0.5);
	}

	/** La semilla del disco irregular: aimSurface.asLong() · 31 + size2. */
	public static long seed(BlockPos aimSurface, int size2) {
		return aimSurface.asLong() * 31L + size2;
	}

	/**
	 * 4.2: el trozo que arrancaría un toque. hit = el bloque al que se mira. cuts = near(…, aimPoint(surfaceAim(v, hit)),
	 * now), o una lista vacía si surfaceAim(v, hit) es null; se ordena aquí una copia por id, así que el orden de quien
	 * llama no importa. roomR ya tiene que venir como (double) (float) radio. user = la caja del jugador que arranca, o
	 * null: su columna se cruza pero nunca se toma.
	 */
	public static ChunkPlan plan(BlockView v, BlockPos hit, int size2, List<GroundCut> cuts, Vec3d roomC, double roomR,
			@Nullable Box user) {
		return plan(v, hit, size2, 0, RIDER_AUTO, null, cuts, roomC, roomR, user);
	}

	/**
	 * Plan completo con grosor explícito (0 = auto, 1..TAKT_DEPTH_MAX), modo de lo que va encima ({@link #RIDER_AUTO},
	 * {@link #RIDER_WITH_TREES}, {@link #RIDER_TREE_ONLY}, {@link #RIDER_NONE}) y la cara mirada para orientar el grosor
	 * en paredes o techos.
	 */
	public static ChunkPlan plan(BlockView v, BlockPos hit, int size2, int depth, int riderMode,
			@Nullable Direction hitSide, List<GroundCut> cuts, Vec3d roomC, double roomR, @Nullable Box user) {
		int d = Math.max(0, Math.min(OpeConfig.TAKT_DEPTH_MAX, depth));
		int rm = riderMode >= 0 && riderMode <= RIDER_NONE ? riderMode : RIDER_AUTO;
		if (rm == RIDER_TREE_ONLY) return treePlan(v, hit, d, rm, roomC, roomR, user);
		List<GroundCut> sorted = new ArrayList<>();
		if (cuts != null) for (GroundCut c : cuts) if (c != null) sorted.add(c);
		sorted.sort(Comparator.comparingInt(c -> c.id));
		BlockPos aim = surfaceAimForMode(v, hit, rm);
		if (aim != null) {
			ChunkPlan p = new Ground(v, aim, size2, d, rm, sorted, roomC, roomR, user).plan();
			if (!p.isEmpty()) return p;
		}
		return facePlan(v, hit, size2, d, rm, hitSide, roomC, roomR, user);
	}

	// ------------------------------------------------------------------ plan de suelo (4.2.4)

	/** Un cálculo de plan de suelo: todo lo que usa vive aquí, un objeto por llamada. */
	private static final class Ground {
		final BlockView v;
		final BlockPos aim;
		final int size2, depth, riderMode;
		final List<GroundCut> cuts;
		final Vec3d roomC;
		final double roomR, sr;
		@Nullable final Box user;
		final Vec3d a;
		final List<Pl> walls = new ArrayList<>(), floors = new ArrayList<>();
		final List<Pl> vertical = new ArrayList<>(), bounding = new ArrayList<>();
		final BlockPos.Mutable m = new BlockPos.Mutable();
		double[] outline;
		int fx0, fx1, fz0, fz1;
		double rb;
		boolean hasFloor;

		Ground(BlockView v, BlockPos aim, int size2, int depth, int riderMode, List<GroundCut> cuts, Vec3d roomC,
				double roomR, @Nullable Box user) {
			this.v = v;
			this.aim = aim;
			this.size2 = size2;
			this.depth = depth;
			this.riderMode = riderMode;
			this.cuts = cuts;
			this.roomC = roomC;
			this.roomR = roomR;
			this.sr = roomR - 0.3;
			this.user = user;
			this.a = aimPoint(aim);
		}

		ChunkPlan plan() {
			if (!(sr > 0)) return ChunkPlan.EMPTY;
			double rr = clamp(size2 / 2.0, OpeConfig.TAKT_CHUNK_MIN, OpeConfig.TAKT_CHUNK_MAX);
			// 1. orientar los planos (por id); cada uno guarda el id de su corte
			for (GroundCut c : cuts) orient(c);
			TreeSet<Integer> binding = new TreeSet<>();
			// 2. ¿cierran los cortes la forma? (en 2D, a la altura de la mira)
			double big = OpeConfig.TAKT_CLOSED_HALF;
			double[] sq = {a.x - big, a.z - big, a.x + big, a.z - big, a.x + big, a.z + big, a.x - big, a.z + big};
			double[] poly = clipAll(sq, walls, -1, a.y);
			double polyArea = area(poly);
			boolean closed = walls.size() >= 3 && poly.length >= 6 && polyArea > 2 && within(poly, a.x, a.z, big - 0.05);
			if (closed) {
				for (int k = 0; k < walls.size(); k++) {
					if (area(clipAll(sq, walls, k, a.y)) > polyArea + 1e-6) binding.add(walls.get(k).id());   // k recorta la forma
				}
			}
			// 3. el contorno
			outline = closed ? poly : hull(disc(rr));
			// 4. la extensión, recortada por la caja del ROOM
			double minX = Double.POSITIVE_INFINITY, maxX = Double.NEGATIVE_INFINITY;
			double minZ = Double.POSITIVE_INFINITY, maxZ = Double.NEGATIVE_INFINITY;
			for (int i = 0; i + 1 < outline.length; i += 2) {
				minX = Math.min(minX, outline[i]);
				maxX = Math.max(maxX, outline[i]);
				minZ = Math.min(minZ, outline[i + 1]);
				maxZ = Math.max(maxZ, outline[i + 1]);
			}
			minX = Math.max(minX, roomC.x - roomR);
			maxX = Math.min(maxX, roomC.x + roomR);
			minZ = Math.max(minZ, roomC.z - roomR);
			maxZ = Math.min(maxZ, roomC.z + roomR);
			if (!(minX <= maxX) || !(minZ <= maxZ)) return ChunkPlan.EMPTY;
			double w = maxX - minX, h = maxZ - minZ, ext = Math.max(w, h);
			double t = depth > 0 ? depth : clamp(0.36 * ext, 1.6, 9);
			double maxDepth = depth > 0 ? depth : Math.max(3, 1.7 * t);
			rb = Math.max(2, (w + h) / 4);
			hasFloor = !floors.isEmpty();
			fx0 = (int) Math.floor(minX);
			fx1 = (int) Math.floor(maxX);
			fz0 = (int) Math.floor(minZ);
			fz1 = (int) Math.floor(maxZ);
			for (Pl p : walls) {
				if (Math.abs(p.ny()) < 1e-4) vertical.add(p);
				else bounding.add(p);
			}
			bounding.addAll(floors);   // los inclinados y los suelos: cada uno da un límite por abajo
			// 5. las columnas, y 6. el tope de bloques: se repite más somero, como mucho 4 veces
			TreeSet<Integer> fromClosed = new TreeSet<>(binding);
			List<Col> cols;
			for (int pass = 0; ; pass++) {
				binding = new TreeSet<>(fromClosed);
				cols = flood(t, maxDepth, binding);
				if (count(cols) <= OpeConfig.TAKT_CHUNK_MAX_BLOCKS || pass == 4) break;
				t *= 0.8;
				maxDepth *= 0.8;
			}
			if (count(cols) > OpeConfig.TAKT_CHUNK_MAX_BLOCKS) cols = dropLowest(cols, OpeConfig.TAKT_CHUNK_MAX_BLOCKS);
			if (cols.isEmpty()) return ChunkPlan.EMPTY;
			cols.sort(Comparator.comparingInt(Col::x).thenComparingInt(Col::z));
			// 8. el resultado (7, lo de encima, necesita los bloques y la cima)
			LongOpenHashSet incl = new LongOpenHashSet();
			for (Col c : cols) incl.add(key(c.x(), c.z()));
			long[] blocks = new long[count(cols)];
			int[] columns = new int[cols.size()];
			int n = 0, topMax = Integer.MIN_VALUE;
			double far = 0;
			for (int i = 0; i < cols.size(); i++) {
				Col c = cols.get(i);
				for (int y : c.ys()) blocks[n++] = BlockPos.asLong(c.x(), y, c.z());
				topMax = Math.max(topMax, c.top());
				boolean edge = !incl.contains(key(c.x() + 1, c.z())) || !incl.contains(key(c.x() - 1, c.z()))
					|| !incl.contains(key(c.x(), c.z() + 1)) || !incl.contains(key(c.x(), c.z() - 1));
				columns[i] = Payloads.TaktFx.pack(c.x() - aim.getX(), c.z() - aim.getZ(), c.top() - aim.getY()) | (edge ? EDGE : 0);
				double dx = c.x() + 0.5 - a.x, dz = c.z() + 0.5 - a.z;
				far = Math.max(far, Math.sqrt(dx * dx + dz * dz));
			}
			long[] riders = riders(cols, blocks, topMax);
			int[] used = new int[binding.size()];
			int u = 0;
			for (int id : binding) used[u++] = id;   // TreeSet: ascendentes
			return new ChunkPlan(aim, false, closed, outline, blocks, riders, columns, new int[0], used, far + 0.5, topMax,
				t, maxDepth, depth, riderMode, false);
		}

		/** 1: con la mira dentro (n·a ≤ d); un voladizo (n.y &gt; 0) se pone vertical a la altura de la mira. */
		void orient(GroundCut c) {
			double nx = c.n.x, ny = c.n.y, nz = c.n.z, d = c.d();
			if (!(nx * nx + ny * ny + nz * nz >= 0.25)) return;   // una normal rota nunca da forma (igual en los dos lados)
			if (nx * a.x + ny * a.y + nz * a.z > d) {
				nx = -nx;
				ny = -ny;
				nz = -nz;
				d = -d;
			}
			if (ny > 0) {
				double h = Math.sqrt(nx * nx + nz * nz);
				if (h < 0.08) return;
				d = (d - ny * a.y) / h;
				nx = nx / h;
				nz = nz / h;
				ny = 0;
			}
			Pl p = new Pl(nx, ny, nz, d, c.id);
			if (ny < -0.5) floors.add(p);
			else walls.add(p);
		}

		/** Los 9 puntos del disco irregular (primero el giro, luego cada punto: desvío y después radio). */
		double[] disc(double rr) {
			Random rnd = new Random(seed(aim, size2));
			double rot = rnd.nextDouble() * 2 * Math.PI;
			double[] pts = new double[18];
			for (int i = 0; i < 9; i++) {
				double ang = rot + i * 2 * Math.PI / 9 + (2 * rnd.nextDouble() - 1) * 0.225;
				double rad = rr * (0.82 + 0.30 * rnd.nextDouble());
				pts[2 * i] = a.x + rad * StrictMath.cos(ang);
				pts[2 * i + 1] = a.z + rad * StrictMath.sin(ang);
			}
			return pts;
		}

		/** 5: la inundación de columnas desde la de la mira (FIFO; vecinas +x, −x, +z, −z en este orden). */
		List<Col> flood(double t, double maxDepth, TreeSet<Integer> binding) {
			List<Col> cols = new ArrayList<>();
			Long2IntOpenHashMap ref = new Long2IntOpenHashMap();   // la y de referencia; estar aquí = ya visto
			LongArrayFIFOQueue queue = new LongArrayFIFOQueue();
			long k0 = key(aim.getX(), aim.getZ());
			ref.put(k0, aim.getY());
			queue.enqueue(k0);
			double sr2 = sr * sr, rb2 = rb * rb;
			IntArrayList ys = new IntArrayList();
			double[] extra = new double[3];
			while (!queue.isEmpty()) {
				long k = queue.dequeueLong();
				int x = (int) (k >> 32), z = (int) k;
				if (x < fx0 || x > fx1 || z < fz0 || z > fz1) continue;
				double cx = x + 0.5, cz = z + 0.5;
				if (!insideConvex(outline, cx, cz)) continue;
				boolean stopped = false;
				for (Pl p : vertical) {
					if (p.nx() * cx + p.nz() * cz > p.d()) {   // un corte para aquí la inundación: le dio forma
						binding.add(p.id());
						stopped = true;
					}
				}
				if (stopped) continue;
				int top = GroundRules.surfaceNear(v, x, z, ref.get(k), OpeConfig.TAKT_STEP_MAX);
				if (top == GroundRules.NONE) continue;   // un escalón de más de 3 o un agujero
				double rx = cx - roomC.x, rz = cz - roomC.z;
				double q = sr2 - rx * rx - rz * rz;
				if (q <= 0) continue;
				double sq = Math.sqrt(q);
				if (top + 0.5 > roomC.y + sq) continue;   // la superficie queda fuera del ROOM
				visit(ref, queue, x + 1, z, top);
				visit(ref, queue, x - 1, z, top);
				visit(ref, queue, x, z + 1, top);
				visit(ref, queue, x, z - 1, top);
				if (user != null && user.minX < x + 1.35 && user.maxX > x - 0.35 && user.minZ < z + 1.35 && user.maxZ > z - 0.35
					&& user.minY >= top + 0.5 && user.minY <= top + 2.5) continue;   // el jugador está aquí: se cruza, no se toma
				double topF = top + 1;
				// el fondo: el mayor de los límites; si lo pone un solo corte, ese corte dio forma al trozo
				double lo = Double.NEGATIVE_INFINITY;
				int loId = -1, loN = 0;
				for (Pl p : bounding) {
					double c = (p.d() - p.nx() * cx - p.nz() * cz) / p.ny();
					if (c > lo) {
						lo = c;
						loId = p.id();
						loN = 1;
					} else if (c == lo) {
						loN++;
					}
				}
				int ne = 0;
				if (!hasFloor) {
					if (depth > 0) {
						extra[ne++] = topF - t;
					} else {
						double ax = cx - a.x, az = cz - a.z;
						double u = Math.min(1.6, (ax * ax + az * az) / rb2);
						extra[ne++] = topF - t * (1 - 0.55 * u);
					}
				}
				extra[ne++] = topF - maxDepth;
				extra[ne++] = roomC.y - sq;
				for (int i = 0; i < ne; i++) {
					double c = extra[i];
					if (c > lo) {
						lo = c;
						loId = -1;
						loN = 1;
					} else if (c == lo) {
						loN++;
					}
				}
				if (loN == 1 && loId >= 0) binding.add(loId);
				ys.clear();
				for (int y = (int) Math.ceil(lo - 0.5); y <= top; y++) {   // el centro del bloque, y + 0,5, por encima de lo
					if (GroundRules.cuttable(v.getBlockState(m.set(x, y, z)), v, m)) ys.add(y);
				}
				if (!ys.isEmpty()) cols.add(new Col(x, z, top, ys.toIntArray()));
			}
			return cols;
		}

		/** 7: lo que va encima, en grupos enteros o según riderMode (Normal / +Árbol / Solo piso). */
		long[] riders(List<Col> cols, long[] blocks, int topMax) {
			if (riderMode == RIDER_NONE) return new long[0];
			if (riderMode == RIDER_WITH_TREES) return ridersWithTrees(cols, blocks, topMax);
			LongOpenHashSet inBlocks = new LongOpenHashSet(blocks);
			int cap = Math.min(OpeConfig.TAKT_RIDER_MAX, OpeConfig.TAKT_CHUNK_MAX_BLOCKS - blocks.length);
			int budget = 4 * OpeConfig.TAKT_RIDER_MAX;
			Long2IntOpenHashMap mark = new Long2IntOpenHashMap();   // celda → TAKEN o LEFT
			LongArrayList riders = new LongArrayList();
			for (Col col : cols) {
				if (budget <= 0) break;
				long s = BlockPos.asLong(col.x(), col.top() + 1, col.z());
				if (mark.containsKey(s) || !riderCell(s, inBlocks, topMax)) continue;
				LongArrayList comp = new LongArrayList();
				LongArrayFIFOQueue queue = new LongArrayFIFOQueue();
				LongOpenHashSet inq = new LongOpenHashSet();
				queue.enqueue(s);
				inq.add(s);
				boolean left = false;
				int room = cap - riders.size();
				while (!queue.isEmpty() && !left) {
					long c = queue.dequeueLong();
					comp.add(c);
					budget--;
					if (comp.size() > room || budget <= 0 || nearUser(c)) left = true;
					for (Direction dir : DIRS) {
						long q = BlockPos.offset(c, dir);
						int mk = mark.get(q);
						if (mk != 0) {
							if (mk == LEFT) left = true;   // toca un grupo que ya se quedó entero
						} else if (!inq.contains(q) && riderCell(q, inBlocks, topMax)) {
							inq.add(q);
							queue.enqueue(q);
						}
					}
				}
				int val = left ? LEFT : TAKEN;
				for (LongIterator it = inq.iterator(); it.hasNext(); ) mark.put(it.nextLong(), val);   // todas igual: el orden da igual
				if (!left) riders.addAll(comp);
			}
			return riders.toLongArray();
		}

		/** +Árbol: primero árboles completos enraizados en cols (con aislamiento de bosque), luego el resto (casas, plantas…). */
		long[] ridersWithTrees(List<Col> cols, long[] blocks, int topMax) {
			LongOpenHashSet inBlocks = new LongOpenHashSet(blocks);
			int cap = Math.min(OpeConfig.TAKT_TREE_MAX, OpeConfig.TAKT_CHUNK_MAX_BLOCKS - blocks.length);
			if (cap <= 0) return new long[0];
			double sr2 = sr * sr;
			LongOpenHashSet taken = new LongOpenHashSet();
			LongArrayList riders = new LongArrayList();
			for (Col col : cols) {
				if (riders.size() >= cap) break;
				for (int dy = 1; dy <= 3; dy++) {
					int y = col.top() + dy;
					long s = BlockPos.asLong(col.x(), y, col.z());
					if (taken.contains(s) || inBlocks.contains(s)) continue;
					BlockState st = v.getBlockState(m.set(col.x(), y, col.z()));
					if (!isTreeWood(st) || !GroundRules.cuttable(st, v, m)) continue;
					extractTree(v, new BlockPos(col.x(), y, col.z()), inBlocks, taken, cap - riders.size(),
						topMax + 64, roomC, sr2, user, true, riders, taken);
				}
			}
			int budget = 4 * OpeConfig.TAKT_TREE_MAX;
			Long2IntOpenHashMap mark = new Long2IntOpenHashMap();
			for (Col col : cols) {
				if (budget <= 0 || riders.size() >= cap) break;
				long s = BlockPos.asLong(col.x(), col.top() + 1, col.z());
				if (taken.contains(s) || mark.containsKey(s) || !riderCellExtra(s, inBlocks, taken, topMax + 32)) continue;
				LongArrayList comp = new LongArrayList();
				LongArrayFIFOQueue queue = new LongArrayFIFOQueue();
				LongOpenHashSet inq = new LongOpenHashSet();
				queue.enqueue(s);
				inq.add(s);
				boolean left = false;
				int room = cap - riders.size();
				while (!queue.isEmpty() && !left) {
					long c = queue.dequeueLong();
					comp.add(c);
					budget--;
					if (comp.size() > room || budget <= 0 || nearUser(c)) left = true;
					boolean cIsLeaf = isTreeLeaf(v.getBlockState(m.set(BlockPos.unpackLongX(c), BlockPos.unpackLongY(c), BlockPos.unpackLongZ(c))));
					for (Direction dir : DIRS) {
						long q = BlockPos.offset(c, dir);
						if (taken.contains(q) || inBlocks.contains(q)) continue;
						int mk = mark.get(q);
						if (mk != 0) {
							if (mk == LEFT) left = true;
						} else if (!inq.contains(q)) {
							int qx = BlockPos.unpackLongX(q), qy = BlockPos.unpackLongY(q), qz = BlockPos.unpackLongZ(q);
							BlockState qst = v.getBlockState(m.set(qx, qy, qz));
							if (!cIsLeaf && isTreeLeaf(qst)) continue;   // no mezclar hierba/estructuras con hojas bajas de otro árbol
							if (riderCellExtra(q, inBlocks, taken, topMax + 32)) {
								inq.add(q);
								queue.enqueue(q);
							} else if (cIsLeaf && isTreeWood(qst)) {
								left = true;   // copa ajena de otro árbol de fuera del trozo
							}
						}
					}
				}
				int val = left ? LEFT : TAKEN;
				for (LongIterator it = inq.iterator(); it.hasNext(); ) mark.put(it.nextLong(), val);
				if (!left) {
					riders.addAll(comp);
					taken.addAll(comp);
				}
			}
			return riders.toLongArray();
		}

		boolean riderCellExtra(long c, LongOpenHashSet inBlocks, LongOpenHashSet taken, int maxY) {
			if (inBlocks.contains(c) || taken.contains(c)) return false;
			int x = BlockPos.unpackLongX(c), y = BlockPos.unpackLongY(c), z = BlockPos.unpackLongZ(c);
			if (y > maxY) return false;
			double dx = x + 0.5 - roomC.x, dy = y + 0.5 - roomC.y, dz = z + 0.5 - roomC.z;
			if (dx * dx + dy * dy + dz * dz > sr * sr) return false;
			BlockState st = v.getBlockState(m.set(x, y, z));
			if (isTreeWood(st)) return false;
			return GroundRules.cuttable(st, v, m) && !GroundRules.terrain(st);
		}

		/** Una celda que puede ir encima: se corta, no es terreno, no es ya del trozo, dentro del ROOM y no muy alta. */
		boolean riderCell(long c, LongOpenHashSet inBlocks, int topMax) {
			if (inBlocks.contains(c)) return false;
			int x = BlockPos.unpackLongX(c), y = BlockPos.unpackLongY(c), z = BlockPos.unpackLongZ(c);
			if (y > topMax + 24) return false;
			double dx = x + 0.5 - roomC.x, dy = y + 0.5 - roomC.y, dz = z + 0.5 - roomC.z;
			if (dx * dx + dy * dy + dz * dz > sr * sr) return false;
			BlockState st = v.getBlockState(m.set(x, y, z));
			return GroundRules.cuttable(st, v, m) && !GroundRules.terrain(st);
		}

		/** La caja del jugador, ensanchada 0,35 en horizontal, toca la celda. */
		boolean nearUser(long c) {
			if (user == null) return false;
			int x = BlockPos.unpackLongX(c), y = BlockPos.unpackLongY(c), z = BlockPos.unpackLongZ(c);
			return user.minX - 0.35 < x + 1 && user.maxX + 0.35 > x && user.minY < y + 1 && user.maxY > y
				&& user.minZ - 0.35 < z + 1 && user.maxZ + 0.35 > z;
		}
	}

	private static void visit(Long2IntOpenHashMap ref, LongArrayFIFOQueue queue, int x, int z, int top) {
		long k = key(x, z);
		if (ref.containsKey(k)) return;
		ref.put(k, top);   // la primera columna que llega pone la referencia
		queue.enqueue(k);
	}

	private static long key(int x, int z) {
		return ((long) x << 32) | (z & 0xFFFFFFFFL);
	}

	private static int count(List<Col> cols) {
		int n = 0;
		for (Col c : cols) n += c.ys().length;
		return n;
	}

	/**
	 * 6, último recurso: quita los bloques más bajos (estable: a igual y, en el orden de las columnas) hasta que quepan
	 * max. Una columna que se queda sin bloques deja de estar incluida.
	 */
	private static List<Col> dropLowest(List<Col> cols, int max) {
		cols = new ArrayList<>(cols);
		cols.sort(Comparator.comparingInt(Col::x).thenComparingInt(Col::z));
		int total = count(cols);
		int[][] ref = new int[total][];   // {y, columna, índice en ys}
		int n = 0;
		for (int ci = 0; ci < cols.size(); ci++) {
			int[] ys = cols.get(ci).ys();
			for (int j = 0; j < ys.length; j++) ref[n++] = new int[]{ys[j], ci, j};
		}
		Arrays.sort(ref, Comparator.comparingInt(r -> r[0]));   // ordenación estable
		boolean[][] gone = new boolean[cols.size()][];
		for (int ci = 0; ci < cols.size(); ci++) gone[ci] = new boolean[cols.get(ci).ys().length];
		for (int i = 0; i < total - max; i++) gone[ref[i][1]][ref[i][2]] = true;
		List<Col> out = new ArrayList<>(cols.size());
		for (int ci = 0; ci < cols.size(); ci++) {
			Col c = cols.get(ci);
			IntArrayList keep = new IntArrayList();
			for (int j = 0; j < c.ys().length; j++) if (!gone[ci][j]) keep.add(c.ys()[j]);
			if (!keep.isEmpty()) out.add(new Col(c.x(), c.z(), c.top(), keep.toIntArray()));
		}
		return out;
	}

	// ------------------------------------------------------------------ plan de cara (4.2.5)

	/** Un pedazo en pared, talud o techo (con grosor plano orientado a {@code hitSide} si depth &gt; 0). */
	private static ChunkPlan facePlan(BlockView v, BlockPos hit, int size2, int depth, int riderMode,
			@Nullable Direction hitSide, Vec3d roomC, double roomR, @Nullable Box user) {
		BlockPos h = hit.toImmutable();
		if (!GroundRules.cuttable(v.getBlockState(h), v, h)) return ChunkPlan.EMPTY;
		double sr = roomR - 0.3;
		if (!(sr > 0)) return ChunkPlan.EMPTY;
		double rr = clamp(size2 / 2.0, OpeConfig.TAKT_CHUNK_MIN, OpeConfig.TAKT_CHUNK_MAX);
		double sr2 = sr * sr;
		int uy0 = user == null ? 0 : (int) Math.floor(user.minY) - 2, uy1 = user == null ? 0 : (int) Math.ceil(user.maxY);
		BlockPos.Mutable m = new BlockPos.Mutable();
		LongArrayList blocks = new LongArrayList();
		LongOpenHashSet set = new LongOpenHashSet();
		double far = 0;
		int topMax = Integer.MIN_VALUE;
		double rf;
		double effDepth = depth;
		if (depth > 0 && hitSide != null) {
			Direction in = hitSide.getOpposite();
			int ix = in.getOffsetX(), iy = in.getOffsetY(), iz = in.getOffsetZ();
			int t1x = iy != 0 ? 1 : ix != 0 ? 0 : 1, t1y = 0, t1z = ix != 0 ? 1 : 0;
			int t2x = 0, t2y = iy != 0 ? 0 : 1, t2z = iy != 0 ? 1 : 0;
			int hx = h.getX(), hy = h.getY(), hz = h.getZ();
			double effR = rr;
			int effD = depth;
			for (int pass = 0; ; pass++) {
				blocks.clear();
				set.clear();
				far = 0;
				topMax = Integer.MIN_VALUE;
				int rLim = (int) Math.ceil(effR);
				double r2 = effR * effR + (effR < 1.0 ? 0.05 : 0.15);
				for (int d = 0; d < effD; d++) {
					for (int u = -rLim; u <= rLim; u++) {
						for (int w = -rLim; w <= rLim; w++) {
							if (u * u + w * w > r2) continue;
							int x = hx + ix * d + t1x * u + t2x * w;
							int y = hy + iy * d + t1y * u + t2y * w;
							int z = hz + iz * d + t1z * u + t2z * w;
							double ex = x + 0.5 - roomC.x, ey = y + 0.5 - roomC.y, ez = z + 0.5 - roomC.z;
							if (ex * ex + ey * ey + ez * ez > sr2) continue;
							if (!GroundRules.cuttable(v.getBlockState(m.set(x, y, z)), v, m)) continue;
							if (user != null && user.minX < x + 1.35 && user.maxX > x - 0.35 && user.minZ < z + 1.35 && user.maxZ > z - 0.35
								&& y >= uy0 && y < uy1) continue;
							long l = BlockPos.asLong(x, y, z);
							blocks.add(l);
							set.add(l);
							double dc = (x - hx) * (x - hx) + (y - hy) * (y - hy) + (z - hz) * (z - hz);
							far = Math.max(far, dc);
							topMax = Math.max(topMax, y);
						}
					}
				}
				if (blocks.size() <= OpeConfig.TAKT_CHUNK_MAX_BLOCKS || pass == 4) break;
				if (effD > 1) effD = Math.max(1, (int) Math.floor(effD * 0.8));
				else effR *= 0.85;
			}
			if (blocks.size() > OpeConfig.TAKT_CHUNK_MAX_BLOCKS) {
				blocks.size(OpeConfig.TAKT_CHUNK_MAX_BLOCKS);
				set.clear();
				for (int i = 0; i < blocks.size(); i++) set.add(blocks.getLong(i));
			}
			rf = effR;
			effDepth = effD;
		} else {
			rf = depth > 0 ? Math.max(rr, depth * 0.5)
				: (rr >= 1.5 ? Math.max(1.6, 0.55 * rr) : Math.max(0.55, rr));
			double cx = h.getX() + 0.5, cy = h.getY() + 0.5, cz = h.getZ() + 0.5;
			double rf2 = rf * rf;
			for (int pass = 0; ; pass++) {
				blocks.clear();
				set.clear();
				far = 0;
				topMax = Integer.MIN_VALUE;
				int x0 = (int) Math.floor(cx - rf), x1 = (int) Math.floor(cx + rf);
				int y0 = (int) Math.floor(cy - rf), y1 = (int) Math.floor(cy + rf);
				int z0 = (int) Math.floor(cz - rf), z1 = (int) Math.floor(cz + rf);
				for (int x = x0; x <= x1; x++) {
					for (int z = z0; z <= z1; z++) {
						for (int y = y0; y <= y1; y++) {
							double dx = x + 0.5 - cx, dy = y + 0.5 - cy, dz = z + 0.5 - cz;
							double dc = dx * dx + dy * dy + dz * dz;
							if (dc > rf2) continue;
							double ex = x + 0.5 - roomC.x, ey = y + 0.5 - roomC.y, ez = z + 0.5 - roomC.z;
							if (ex * ex + ey * ey + ez * ez > sr2) continue;
							if (!GroundRules.cuttable(v.getBlockState(m.set(x, y, z)), v, m)) continue;
							if (user != null && user.minX < x + 1.35 && user.maxX > x - 0.35 && user.minZ < z + 1.35 && user.maxZ > z - 0.35
								&& y >= uy0 && y < uy1) continue;   // nunca lo que pisa el jugador
							long l = BlockPos.asLong(x, y, z);
							blocks.add(l);
							set.add(l);
							far = Math.max(far, dc);
							topMax = Math.max(topMax, y);
						}
					}
				}
				if (blocks.size() <= OpeConfig.TAKT_CHUNK_MAX_BLOCKS || pass == 4) break;
				rf *= 0.85;
				rf2 = rf * rf;
			}
			if (blocks.size() > OpeConfig.TAKT_CHUNK_MAX_BLOCKS) {
				blocks.size(OpeConfig.TAKT_CHUNK_MAX_BLOCKS);
				set.clear();
				for (int i = 0; i < blocks.size(); i++) set.add(blocks.getLong(i));
			}
		}
		if (blocks.isEmpty()) return ChunkPlan.EMPTY;
		LongArrayList riders = new LongArrayList();
		LongOpenHashSet rset = new LongOpenHashSet();
		if (riderMode != RIDER_NONE) {
			int riderCap = Math.max(0, OpeConfig.TAKT_CHUNK_MAX_BLOCKS - blocks.size());
			if (riderMode == RIDER_WITH_TREES && riderCap > 0) {
				int maxTreeCap = Math.min(OpeConfig.TAKT_TREE_MAX, riderCap);
				for (int i = 0; i < blocks.size() && riders.size() < maxTreeCap; i++) {
					long b = blocks.getLong(i);
					int bx = BlockPos.unpackLongX(b), by = BlockPos.unpackLongY(b) + 1, bz = BlockPos.unpackLongZ(b);
					long q = BlockPos.asLong(bx, by, bz);
					if (set.contains(q) || rset.contains(q)) continue;
					BlockState st = v.getBlockState(m.set(bx, by, bz));
					if (isTreeWood(st) && GroundRules.cuttable(st, v, m)) {
						extractTree(v, new BlockPos(bx, by, bz), set, rset, maxTreeCap - riders.size(),
							topMax + 64, roomC, sr2, user, true, riders, rset);
					}
				}
			}
			for (int i = 0; i < blocks.size() && riders.size() < riderCap; i++) {
				long b = blocks.getLong(i);
				int bx = BlockPos.unpackLongX(b), by = BlockPos.unpackLongY(b), bz = BlockPos.unpackLongZ(b);
				for (int k = 1; k <= 2 && riders.size() < riderCap; k++) {
					long q = BlockPos.asLong(bx, by + k, bz);
					if (set.contains(q) || rset.contains(q)) break;
					if (!GroundRules.plantLike(v.getBlockState(m.set(bx, by + k, bz)), v, m)) break;
					double ex = bx + 0.5 - roomC.x, ey = by + k + 0.5 - roomC.y, ez = bz + 0.5 - roomC.z;
					if (ex * ex + ey * ey + ez * ez > sr2) break;
					riders.add(q);
					rset.add(q);
				}
			}
		}
		int[] cells = exposedCells(v, m, h, blocks, set);
		return new ChunkPlan(h, true, false, new double[0], blocks.toLongArray(), riders.toLongArray(), new int[0], cells,
			new int[0], Math.sqrt(far) + 0.5, topMax, rf, effDepth, depth, riderMode, false);
	}

	/** Solo árbol: arranca el árbol entero apuntado (aislado de árboles vecinos en un bosque). */
	private static ChunkPlan treePlan(BlockView v, BlockPos hit, int depth, int riderMode, Vec3d roomC, double roomR,
			@Nullable Box user) {
		double sr = roomR - 0.3;
		if (!(sr > 0)) return ChunkPlan.EMPTY;
		BlockPos seed = findTreeSeed(v, hit);
		if (seed == null) return ChunkPlan.EMPTY;
		LongOpenHashSet empty = new LongOpenHashSet();
		LongOpenHashSet taken = new LongOpenHashSet();
		LongArrayList out = new LongArrayList();
		extractTree(v, seed, empty, taken, OpeConfig.TAKT_TREE_MAX, v.getTopY(), roomC, sr * sr, user, true, out, taken);
		if (out.isEmpty()) return ChunkPlan.EMPTY;
		double cx = seed.getX() + 0.5, cy = seed.getY() + 0.5, cz = seed.getZ() + 0.5;
		double far = 0;
		int topMax = Integer.MIN_VALUE;
		for (int i = 0; i < out.size(); i++) {
			long l = out.getLong(i);
			int x = BlockPos.unpackLongX(l), y = BlockPos.unpackLongY(l), z = BlockPos.unpackLongZ(l);
			double dx = x + 0.5 - cx, dy = y + 0.5 - cy, dz = z + 0.5 - cz;
			far = Math.max(far, dx * dx + dy * dy + dz * dz);
			topMax = Math.max(topMax, y);
		}
		BlockPos.Mutable m = new BlockPos.Mutable();
		int[] cells = exposedCells(v, m, seed, out, taken);
		return new ChunkPlan(seed, true, false, new double[0], out.toLongArray(), new long[0], new int[0], cells,
			new int[0], Math.sqrt(far) + 0.5, topMax, 0, 0, depth, riderMode, true);
	}

	private static int[] exposedCells(BlockView v, BlockPos.Mutable m, BlockPos origin, LongArrayList blocks, LongOpenHashSet set) {
		int[] cells = new int[blocks.size()];
		int ox = origin.getX(), oy = origin.getY(), oz = origin.getZ();
		for (int i = 0; i < blocks.size(); i++) {
			long b = blocks.getLong(i);
			int bx = BlockPos.unpackLongX(b), by = BlockPos.unpackLongY(b), bz = BlockPos.unpackLongZ(b);
			boolean exposed = false;
			for (Direction d : DIRS) {
				int nx = bx + d.getOffsetX(), ny = by + d.getOffsetY(), nz = bz + d.getOffsetZ();
				if (set.contains(BlockPos.asLong(nx, ny, nz))) continue;
				if (GroundRules.replaceable(v.getBlockState(m.set(nx, ny, nz)))) {
					exposed = true;
					break;
				}
			}
			cells[i] = Payloads.TaktFx.pack(bx - ox, bz - oz, by - oy) | (exposed ? EDGE : 0);
		}
		return cells;
	}

	@Nullable
	private static BlockPos findTreeSeed(BlockView v, BlockPos hit) {
		BlockPos.Mutable m = new BlockPos.Mutable();
		BlockState direct = v.getBlockState(hit);
		if ((isTreeWood(direct) || isTreeLeaf(direct)) && GroundRules.cuttable(direct, v, hit)) return hit.toImmutable();
		BlockPos leafCand = null;
		int[] dys = { 0, 1, -1, 2 };
		for (int dy : dys) {
			for (int r = 1; r <= 1; r++) {
				for (int dx = -r; dx <= r; dx++) {
					for (int dz = -r; dz <= r; dz++) {
						int x = hit.getX() + dx, y = hit.getY() + dy, z = hit.getZ() + dz;
						BlockState st = v.getBlockState(m.set(x, y, z));
						if (!GroundRules.cuttable(st, v, m)) continue;
						if (isTreeWood(st)) return new BlockPos(x, y, z);
						if (leafCand == null && isTreeLeaf(st)) leafCand = new BlockPos(x, y, z);
					}
				}
			}
		}
		return leafCand;
	}

	private static boolean isTreeWood(BlockState st) {
		return st.isIn(BlockTags.LOGS) || st.isOf(Blocks.MANGROVE_ROOTS) || st.isOf(Blocks.MUDDY_MANGROVE_ROOTS)
			|| st.isOf(Blocks.MUSHROOM_STEM);
	}

	private static boolean isTreeLeaf(BlockState st) {
		return st.isIn(BlockTags.LEAVES) || st.isOf(Blocks.VINE) || st.isOf(Blocks.COCOA) || st.isOf(Blocks.BEE_NEST)
			|| st.isOf(Blocks.MOSS_CARPET) || st.isOf(Blocks.PINK_PETALS) || st.isOf(Blocks.AZALEA)
			|| st.isOf(Blocks.FLOWERING_AZALEA) || st.isIn(BlockTags.WART_BLOCKS) || st.isOf(Blocks.SHROOMLIGHT)
			|| st.isOf(Blocks.BROWN_MUSHROOM_BLOCK) || st.isOf(Blocks.RED_MUSHROOM_BLOCK)
			|| st.isOf(Blocks.WEEPING_VINES) || st.isOf(Blocks.WEEPING_VINES_PLANT)
			|| st.isOf(Blocks.TWISTING_VINES) || st.isOf(Blocks.TWISTING_VINES_PLANT);
	}

	private static boolean isHangingVine(BlockState st) {
		return st.isOf(Blocks.VINE) || st.isOf(Blocks.WEEPING_VINES) || st.isOf(Blocks.WEEPING_VINES_PLANT);
	}

	private static boolean inSphere(int x, int y, int z, Vec3d roomC, double sr2) {
		double dx = x + 0.5 - roomC.x, dy = y + 0.5 - roomC.y, dz = z + 0.5 - roomC.z;
		return dx * dx + dy * dy + dz * dz <= sr2;
	}

	private static boolean touchesUser(@Nullable Box user, int x, int y, int z) {
		if (user == null) return false;
		return user.minX < x + 1 && user.maxX > x && user.minY < y + 1 && user.maxY > y && user.minZ < z + 1 && user.maxZ > z;
	}

	private static boolean hasCloserForeignLog(BlockView v, BlockPos.Mutable m, int x, int y, int z, int dist,
			LongOpenHashSet ownLogs) {
		int reach = dist == 2 ? 1 : 2;
		for (int oy = -reach; oy <= reach; oy++) {
			for (int ox = -reach; ox <= reach; ox++) {
				for (int oz = -reach; oz <= reach; oz++) {
					int manh = Math.abs(ox) + Math.abs(oy) + Math.abs(oz);
					if (manh == 0 || manh >= dist || manh > reach) continue;
					int nx = x + ox, ny = y + oy, nz = z + oz;
					if (isTreeWood(v.getBlockState(m.set(nx, ny, nz))) && !ownLogs.contains(BlockPos.asLong(nx, ny, nz))) {
						return true;
					}
				}
			}
		}
		return false;
	}

	/** Extrae un árbol entero (tronco + raíces 26-conexas, copa hasta distancia 6 con corte Voronoi ante troncos ajenos). */
	private static void extractTree(BlockView v, BlockPos seed, LongOpenHashSet excluded, LongOpenHashSet alreadyTaken,
			int cap, int maxY, Vec3d roomC, double sr2, @Nullable Box user, boolean avoidUser,
			LongArrayList out, LongOpenHashSet markSet) {
		if (cap <= 0) return;
		BlockPos.Mutable m = new BlockPos.Mutable();
		long seedL = seed.asLong();
		if (excluded.contains(seedL) || alreadyTaken.contains(seedL)) return;
		BlockState s0 = v.getBlockState(m.set(seed.getX(), seed.getY(), seed.getZ()));
		if (!GroundRules.cuttable(s0, v, m) || !inSphere(seed.getX(), seed.getY(), seed.getZ(), roomC, sr2)) return;

		LongArrayList wood = new LongArrayList();
		LongOpenHashSet trunk = new LongOpenHashSet();
		LongArrayFIFOQueue wq = new LongArrayFIFOQueue();

		if (isTreeWood(s0)) {
			trunk.add(seedL);
			wq.enqueue(seedL);
		} else if (isTreeLeaf(s0)) {
			// Si apuntó a la copa, busca el tronco conectado más cercano hasta 10 pasos antes de extraer
			LongArrayList fallbackBush = new LongArrayList();
			Long2IntOpenHashMap ldist = new Long2IntOpenHashMap();
			ldist.defaultReturnValue(-1);
			LongArrayFIFOQueue lq = new LongArrayFIFOQueue();
			ldist.put(seedL, 0);
			lq.enqueue(seedL);
			long foundLog = Long.MIN_VALUE;
			while (!lq.isEmpty() && ldist.size() < 450 && foundLog == Long.MIN_VALUE) {
				long c = lq.dequeueLong();
				int d = ldist.get(c);
				fallbackBush.add(c);
				if (d >= 10) continue;
				int cx = BlockPos.unpackLongX(c), cy = BlockPos.unpackLongY(c), cz = BlockPos.unpackLongZ(c);
				for (int dy = -1; dy <= 1 && foundLog == Long.MIN_VALUE; dy++) {
					for (int dx = -1; dx <= 1 && foundLog == Long.MIN_VALUE; dx++) {
						for (int dz = -1; dz <= 1; dz++) {
							if (dx == 0 && dy == 0 && dz == 0) continue;
							int nx = cx + dx, ny = cy + dy, nz = cz + dz;
							if (ny > maxY || ny < v.getBottomY() || ny >= v.getTopY()) continue;
							long q = BlockPos.asLong(nx, ny, nz);
							if (excluded.contains(q) || alreadyTaken.contains(q) || ldist.containsKey(q)) continue;
							if (!inSphere(nx, ny, nz, roomC, sr2)) continue;
							if (avoidUser && touchesUser(user, nx, ny, nz)) continue;
							BlockState ns = v.getBlockState(m.set(nx, ny, nz));
							if (!GroundRules.cuttable(ns, v, m)) continue;
							if (isTreeWood(ns)) {
								foundLog = q;
								break;
							}
							if (isTreeLeaf(ns)) {
								ldist.put(q, d + 1);
								lq.enqueue(q);
							}
						}
					}
				}
			}
			if (foundLog != Long.MIN_VALUE) {
				trunk.add(foundLog);
				wq.enqueue(foundLog);
			} else {
				int add = Math.min(cap, Math.min(fallbackBush.size(), 128));
				for (int i = 0; i < add; i++) {
					long c = fallbackBush.getLong(i);
					if (markSet.add(c)) out.add(c);
				}
				return;
			}
		} else {
			return;
		}

		// Fase 1: madera / tronco / ramas / raíces (26-conexo)
		while (!wq.isEmpty() && wood.size() < cap) {
			long c = wq.dequeueLong();
			wood.add(c);
			int cx = BlockPos.unpackLongX(c), cy = BlockPos.unpackLongY(c), cz = BlockPos.unpackLongZ(c);
			for (int dy = -1; dy <= 1; dy++) {
				for (int dx = -1; dx <= 1; dx++) {
					for (int dz = -1; dz <= 1; dz++) {
						if (dx == 0 && dy == 0 && dz == 0) continue;
						int nx = cx + dx, ny = cy + dy, nz = cz + dz;
						if (ny > maxY || ny < v.getBottomY() || ny >= v.getTopY()) continue;
						long q = BlockPos.asLong(nx, ny, nz);
						if (trunk.contains(q) || excluded.contains(q) || alreadyTaken.contains(q)) continue;
						if (!inSphere(nx, ny, nz, roomC, sr2)) continue;
						if (avoidUser && touchesUser(user, nx, ny, nz)) continue;
						BlockState ns = v.getBlockState(m.set(nx, ny, nz));
						if (!isTreeWood(ns) || !GroundRules.cuttable(ns, v, m)) continue;
						trunk.add(q);
						wq.enqueue(q);
					}
				}
			}
		}

		// Fase 2: hojas y accesorios desde el tronco (semilla 26-conexa + BFS 6-conexo hasta distancia 6)
		LongArrayList leaves = new LongArrayList();
		Long2IntOpenHashMap dist = new Long2IntOpenHashMap();
		dist.defaultReturnValue(-1);
		LongArrayFIFOQueue lq = new LongArrayFIFOQueue();
		for (int i = 0; i < wood.size() && wood.size() + dist.size() < cap; i++) {
			long wc = wood.getLong(i);
			int cx = BlockPos.unpackLongX(wc), cy = BlockPos.unpackLongY(wc), cz = BlockPos.unpackLongZ(wc);
			for (int dy = -1; dy <= 1; dy++) {
				for (int dx = -1; dx <= 1; dx++) {
					for (int dz = -1; dz <= 1; dz++) {
						if (dx == 0 && dy == 0 && dz == 0) continue;
						int nx = cx + dx, ny = cy + dy, nz = cz + dz;
						if (ny > maxY || ny < v.getBottomY() || ny >= v.getTopY()) continue;
						long q = BlockPos.asLong(nx, ny, nz);
						if (trunk.contains(q) || excluded.contains(q) || alreadyTaken.contains(q) || dist.containsKey(q)) continue;
						if (!inSphere(nx, ny, nz, roomC, sr2)) continue;
						if (avoidUser && touchesUser(user, nx, ny, nz)) continue;
						BlockState ns = v.getBlockState(m.set(nx, ny, nz));
						if (!isTreeLeaf(ns) || !GroundRules.cuttable(ns, v, m)) continue;
						dist.put(q, 1);
						lq.enqueue(q);
					}
				}
			}
		}
		while (!lq.isEmpty()) {
			long c = lq.dequeueLong();
			leaves.add(c);
			if (wood.size() + leaves.size() >= cap) break;
			int d = dist.get(c);
			if (d >= 6) continue;
			int cx = BlockPos.unpackLongX(c), cy = BlockPos.unpackLongY(c), cz = BlockPos.unpackLongZ(c);
			for (Direction dir : DIRS) {
				int nx = cx + dir.getOffsetX(), ny = cy + dir.getOffsetY(), nz = cz + dir.getOffsetZ();
				if (ny > maxY || ny < v.getBottomY() || ny >= v.getTopY()) continue;
				long q = BlockPos.asLong(nx, ny, nz);
				if (trunk.contains(q) || excluded.contains(q) || alreadyTaken.contains(q) || dist.containsKey(q)) continue;
				if (!inSphere(nx, ny, nz, roomC, sr2)) continue;
				if (avoidUser && touchesUser(user, nx, ny, nz)) continue;
				BlockState ns = v.getBlockState(m.set(nx, ny, nz));
				if (!isTreeLeaf(ns) || !GroundRules.cuttable(ns, v, m)) continue;
				int nd = d + 1;
				if (hasCloserForeignLog(v, m, nx, ny, nz, nd, trunk)) continue;
				dist.put(q, nd);
				lq.enqueue(q);
			}
		}

		// Lianas colgantes bajo hojas/lianas ya incluidas + nieve posada encima del árbol
		int baseLeaves = leaves.size();
		for (int i = 0; i < baseLeaves && wood.size() + leaves.size() < cap; i++) {
			long c = leaves.getLong(i);
			int cx = BlockPos.unpackLongX(c), cy = BlockPos.unpackLongY(c), cz = BlockPos.unpackLongZ(c);
			if (!isHangingVine(v.getBlockState(m.set(cx, cy, cz)))) continue;
			for (int k = 1; k <= 16 && wood.size() + leaves.size() < cap; k++) {
				int ny = cy - k;
				if (ny < v.getBottomY()) break;
				long q = BlockPos.asLong(cx, ny, cz);
				if (trunk.contains(q) || excluded.contains(q) || alreadyTaken.contains(q) || dist.containsKey(q)) break;
				if (!inSphere(cx, ny, cz, roomC, sr2)) break;
				BlockState ns = v.getBlockState(m.set(cx, ny, cz));
				if (!isHangingVine(ns) || !GroundRules.cuttable(ns, v, m)) break;
				dist.put(q, 6);
				leaves.add(q);
			}
		}
		int snowLimit = Math.min(wood.size() + leaves.size(), cap);
		for (int pass = 0; pass < 2 && wood.size() + leaves.size() < cap; pass++) {
			LongArrayList src = pass == 0 ? wood : leaves;
			int end = pass == 0 ? wood.size() : baseLeaves;
			for (int i = 0; i < end && wood.size() + leaves.size() < cap && snowLimit < cap; i++) {
				long c = src.getLong(i);
				int cx = BlockPos.unpackLongX(c), cy = BlockPos.unpackLongY(c) + 1, cz = BlockPos.unpackLongZ(c);
				if (cy > maxY || cy >= v.getTopY()) continue;
				long q = BlockPos.asLong(cx, cy, cz);
				if (trunk.contains(q) || excluded.contains(q) || alreadyTaken.contains(q) || dist.containsKey(q)) continue;
				if (!inSphere(cx, cy, cz, roomC, sr2)) continue;
				BlockState ns = v.getBlockState(m.set(cx, cy, cz));
				if (ns.isOf(Blocks.SNOW) && GroundRules.cuttable(ns, v, m)) {
					dist.put(q, 6);
					leaves.add(q);
					snowLimit++;
				}
			}
		}

		for (int i = 0; i < wood.size(); i++) {
			long c = wood.getLong(i);
			if (markSet.add(c)) out.add(c);
		}
		for (int i = 0; i < leaves.size(); i++) {
			long c = leaves.getLong(i);
			if (markSet.add(c)) out.add(c);
		}
	}

	// ------------------------------------------------------------------ geometría 2D (x, z)

	private static double clamp(double v, double lo, double hi) {
		return Math.max(lo, Math.min(hi, v));
	}

	/** El polígono recortado por n.x·x + n.z·z ≤ d − n.y·ay de cada pared, en orden, salvo la de índice skip. */
	private static double[] clipAll(double[] poly, List<Pl> walls, int skip, double ay) {
		double[] cur = poly;
		for (int i = 0; i < walls.size() && cur.length >= 6; i++) {
			if (i == skip) continue;
			Pl p = walls.get(i);
			cur = clip(cur, p.nx(), p.nz(), p.d() - p.ny() * ay);
		}
		return cur;
	}

	/** Sutherland–Hodgman con un semiplano: se queda lo que cumple nx·x + nz·z ≤ lim. */
	private static double[] clip(double[] p, double nx, double nz, double lim) {
		int n = p.length / 2;
		double[] out = new double[n * 4];
		int k = 0;
		for (int i = 0; i < n; i++) {
			int j = (i + n - 1) % n;
			double cx = p[2 * i], cz = p[2 * i + 1], px = p[2 * j], pz = p[2 * j + 1];
			double fc = nx * cx + nz * cz - lim, fp = nx * px + nz * pz - lim;
			if (fc <= 0) {
				if (fp > 0) {
					double t = fp / (fp - fc);
					out[k++] = px + t * (cx - px);
					out[k++] = pz + t * (cz - pz);
				}
				out[k++] = cx;
				out[k++] = cz;
			} else if (fp <= 0) {
				double t = fp / (fp - fc);
				out[k++] = px + t * (cx - px);
				out[k++] = pz + t * (cz - pz);
			}
		}
		return Arrays.copyOf(out, k);
	}

	/** Área con signo (positiva en sentido antihorario); 0 si no hay tres vértices. */
	private static double area(double[] p) {
		int n = p.length / 2;
		if (n < 3) return 0;
		double s = 0;
		for (int i = 0; i < n; i++) {
			int j = (i + 1) % n;
			s += p[2 * i] * p[2 * j + 1] - p[2 * j] * p[2 * i + 1];
		}
		return s / 2;
	}

	/** ¿Todos los vértices a menos de lim de (ax, az) en cada eje? */
	private static boolean within(double[] p, double ax, double az, double lim) {
		for (int i = 0; i + 1 < p.length; i += 2) {
			if (!(Math.abs(p[i] - ax) < lim) || !(Math.abs(p[i + 1] - az) < lim)) return false;
		}
		return true;
	}

	/** Dentro de un polígono convexo en sentido antihorario: a la izquierda (o casi) de cada lado. */
	private static boolean insideConvex(double[] o, double px, double pz) {
		int n = o.length / 2;
		if (n < 3) return false;
		for (int i = 0; i < n; i++) {
			int j = (i + 1) % n;
			double ax = o[2 * i], az = o[2 * i + 1], bx = o[2 * j], bz = o[2 * j + 1];
			if ((bx - ax) * (pz - az) - (bz - az) * (px - ax) < -1e-9) return false;
		}
		return true;
	}

	/** La envolvente convexa (cadena monótona de Andrew), en sentido antihorario y sin puntos alineados. */
	private static double[] hull(double[] pts) {
		int n = pts.length / 2;
		double[][] p = new double[n][];
		for (int i = 0; i < n; i++) p[i] = new double[]{pts[2 * i], pts[2 * i + 1]};
		Arrays.sort(p, (u, w) -> u[0] != w[0] ? Double.compare(u[0], w[0]) : Double.compare(u[1], w[1]));
		double[][] h = new double[2 * n][];
		int k = 0;
		for (int i = 0; i < n; i++) {
			while (k >= 2 && cross(h[k - 2], h[k - 1], p[i]) <= 0) k--;
			h[k++] = p[i];
		}
		for (int i = n - 2, lower = k + 1; i >= 0; i--) {
			while (k >= lower && cross(h[k - 2], h[k - 1], p[i]) <= 0) k--;
			h[k++] = p[i];
		}
		int m = Math.max(0, k - 1);   // el último repite el primero
		double[] out = new double[2 * m];
		for (int i = 0; i < m; i++) {
			out[2 * i] = h[i][0];
			out[2 * i + 1] = h[i][1];
		}
		return out;
	}

	private static double cross(double[] o, double[] a, double[] b) {
		return (a[0] - o[0]) * (b[1] - o[1]) - (a[1] - o[1]) * (b[0] - o[0]);
	}
}
