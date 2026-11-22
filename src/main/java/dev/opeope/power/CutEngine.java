package dev.opeope.power;

import dev.opeope.OpeConfig;
import dev.opeope.entity.BlockSet;
import dev.opeope.entity.PieceEntity;
import dev.opeope.entity.SterbenEntity;
import dev.opeope.net.Payloads;
import dev.opeope.terrain.GroundRules;
import net.fabricmc.fabric.api.networking.v1.PlayerLookup;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayFIFOQueue;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.FluidBlock;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.inventory.Inventory;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.particle.BlockStateParticleEffect;
import net.minecraft.particle.DustParticleEffect;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.BlockView;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * El corte real, en bloques. Un plano dentro del ROOM separa los bloques de un lado y del otro; cada grupo de bloques
 * que queda sin unión con el resto del mundo se convierte en una pieza que cae (igual que el grafo de soldaduras del
 * juego: lo que toca el terreno fuera del ROOM o por debajo queda anclado). Los planos que entran en el suelo quedan
 * recordados (GroundCuts) para que Takt arranque justo lo que encierran.
 */
public final class CutEngine {
	private CutEngine() {}

	public static final DustParticleEffect ROOM_DUST = new DustParticleEffect(new Vector3f(0.56f, 0.85f, 1f), 1.2f);
	private static final Direction[] DIRS = Direction.values();

	public record Plane(Vec3d n, Vec3d p) {
		public double dist(Vec3d x) {
			return n.dotProduct(x.subtract(p));
		}

		public double dist(double x, double y, double z) {
			return n.x * (x - p.x) + n.y * (y - p.y) + n.z * (z - p.z);
		}

		public boolean crosses(Box b) {
			double min = Double.MAX_VALUE, max = -Double.MAX_VALUE;
			for (int i = 0; i < 8; i++) {
				double d = dist((i & 1) == 0 ? b.minX : b.maxX, (i & 2) == 0 ? b.minY : b.maxY, (i & 4) == 0 ? b.minZ : b.maxZ);
				min = Math.min(min, d);
				max = Math.max(max, d);
			}
			return min < 0 && max > 0;
		}
	}

	/** Dónde se corta: la esfera del ROOM, opcionalmente solo delante de quien corta y dentro de un alcance. */
	public static final class Region {
		public Vec3d c;
		public double r;
		public Vec3d eye, look;
		public Vec3d lc;
		public double lr;
		public int minX = Integer.MIN_VALUE, maxX = Integer.MAX_VALUE;
		public int minZ = Integer.MIN_VALUE, maxZ = Integer.MAX_VALUE;

		public static Region of(Room room) {
			Region g = new Region();
			g.c = room.center;
			g.r = room.radius;
			return g;
		}

		public static Region sphere(Vec3d c, double r) {
			Region g = new Region();
			g.c = c;
			g.r = r;
			return g;
		}

		public Region withEye(Vec3d eye, Vec3d look) {
			this.eye = eye;
			this.look = look;
			return this;
		}

		public Region withLimit(Vec3d c, double r) {
			this.lc = c;
			this.lr = r;
			return this;
		}

		public Region withXZBounds(int minX, int maxX, int minZ, int maxZ) {
			Region g = new Region();
			g.c = this.c;
			g.r = this.r;
			g.eye = this.eye;
			g.look = this.look;
			g.lc = this.lc;
			g.lr = this.lr;
			g.minX = minX;
			g.maxX = maxX;
			g.minZ = minZ;
			g.maxZ = maxZ;
			return g;
		}

		public boolean contains(Vec3d p) {
			return contains(p.x, p.y, p.z);
		}

		public boolean contains(double x, double y, double z) {
			if (x < minX || x > maxX || z < minZ || z > maxZ) return false;
			double dx = x - c.x, dy = y - c.y, dz = z - c.z;
			if (dx * dx + dy * dy + dz * dz > r * r) return false;
			if (lc != null) {
				double ex = x - lc.x, ey = y - lc.y, ez = z - lc.z;
				if (ex * ex + ey * ey + ez * ez > lr * lr) return false;
			}
			if (eye != null && look != null) {
				return (x - eye.x) * look.x + (y - eye.y) * look.y + (z - eye.z) * look.z > 0.2;
			}
			return true;
		}

		Vec3d sampleC() {
			return lc != null && lr < r ? lc : c;
		}

		double sampleR() {
			return lc != null ? Math.min(lr, r) : r;
		}

		public Box box() {
			Vec3d sc = sampleC();
			double sr = sampleR();
			return new Box(sc.x - sr, sc.y - sr, sc.z - sr, sc.x + sr, sc.y + sr, sc.z + sr);
		}
	}

	public record Result(int pieces, int living) {}

	/** Lo que el corte puede separar: no aire, no un fluido, no irrompible (la misma regla que GroundRules). */
	public static boolean cuttable(BlockState st, ServerWorld w, BlockPos pos) {
		return GroundRules.cuttable(st, w, pos);
	}

	private static Vec3d perpendicular(Vec3d n) {
		Vec3d a = Math.abs(n.y) < 0.9 ? new Vec3d(0, 1, 0) : new Vec3d(1, 0, 0);
		return n.crossProduct(a).normalize();
	}

	private static boolean separated(List<Plane> planes, Region region, int ax, int ay, int az, int bx, int by, int bz) {
		double cax = ax + 0.5, cay = ay + 0.5, caz = az + 0.5, cbx = bx + 0.5, cby = by + 0.5, cbz = bz + 0.5;
		for (Plane pl : planes) {
			double da = pl.dist(cax, cay, caz), db = pl.dist(cbx, cby, cbz);
			if ((da > 0) != (db > 0)) {
				if (region.contains((cax + cbx) / 2, (cay + cby) / 2, (caz + cbz) / 2)) return true;
			}
		}
		return false;
	}

	/** Suelo natural continuo (tierra/césped/arena/grava/barro/netherrack/endstone) bajo árboles y edificios. */
	private static boolean isNaturalSoil(BlockState st) {
		return st.isOf(Blocks.GRASS_BLOCK) || st.isOf(Blocks.DIRT) || st.isOf(Blocks.COARSE_DIRT)
			|| st.isOf(Blocks.ROOTED_DIRT) || st.isOf(Blocks.PODZOL) || st.isOf(Blocks.MYCELIUM)
			|| st.isOf(Blocks.SAND) || st.isOf(Blocks.RED_SAND) || st.isOf(Blocks.GRAVEL)
			|| st.isOf(Blocks.MUD) || st.isOf(Blocks.CLAY) || st.isOf(Blocks.SNOW_BLOCK)
			|| st.isOf(Blocks.NETHERRACK) || st.isOf(Blocks.SOUL_SAND) || st.isOf(Blocks.SOUL_SOIL)
			|| st.isOf(Blocks.END_STONE);
	}

	/** Roca/terreno natural de montañas y acantilados (se corta junto con su tierra/césped/nieve, pero detiene cortes verticales de árboles/casas apoyados en piedra). */
	private static boolean isNaturalRock(BlockState st) {
		return st.isIn(net.minecraft.registry.tag.BlockTags.BASE_STONE_OVERWORLD)
			|| st.isIn(net.minecraft.registry.tag.BlockTags.BASE_STONE_NETHER)
			|| st.isIn(net.minecraft.registry.tag.BlockTags.TERRACOTTA)
			|| st.isOf(Blocks.SANDSTONE) || st.isOf(Blocks.RED_SANDSTONE)
			|| st.isOf(Blocks.CALCITE) || st.isOf(Blocks.DRIPSTONE_BLOCK)
			|| st.isOf(Blocks.MOSS_BLOCK);
	}

	/** Registra una semilla única junto con el plano más cercano, su caja en GroundCuts y su relieve vertical. */
	private static void addSeed(ServerWorld w, Plane pl, int pi, int bx, int by, int bz,
			LongOpenHashSet seen, LongArrayList seeds, IntArrayList seedPlane, IntArrayList seedSide,
			boolean[] planeTerrain, int[][] planeBox, LongOpenHashSet reliefCols, int[] reliefBounds,
			IntArrayList reliefTops, List<Vec3d> fx, List<BlockState> fxState, boolean addFx, double px, double py, double pz) {
		if (by < w.getBottomY() || by >= w.getTopY()) return;
		long key = BlockPos.asLong(bx, by, bz);
		if (!seen.add(key)) return;
		BlockPos bp = new BlockPos(bx, by, bz);
		if (!w.isChunkLoaded(bp)) return;
		BlockState st = w.getBlockState(bp);
		if (!cuttable(st, w, bp)) return;
		seeds.add(key);
		seedPlane.add(pi);
		seedSide.add(pl.dist(bx + 0.5, by + 0.5, bz + 0.5) >= 0 ? 1 : -1);
		if (GroundRules.terrain(st)) {
			planeTerrain[pi] = true;
			if (reliefCols.size() < 48 && reliefCols.add(BlockPos.asLong(bx >> 2, 0, bz >> 2))) {
				int topY = w.getTopY(net.minecraft.world.Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, bx, bz) - 1;
				reliefTops.add(topY);
				reliefBounds[0] = Math.min(reliefBounds[0], topY);
				reliefBounds[1] = Math.max(reliefBounds[1], topY);
			}
		}
		planeBox[pi] = grow(planeBox[pi], bx, by, bz);
		if (addFx && fx.size() < 260 && w.random.nextInt(3) == 0) {
			fx.add(new Vec3d(px, py, pz));
			fxState.add(st);
		}
	}

	/**
	 * Corta con varios planos a la vez. Devuelve cuántas piezas se soltaron y cuántos seres vivos quedaron en piezas.
	 * @param noReattach ticks en que lo cortado de un ser vivo no se puede volver a unir (Radio Knife)
	 * @param livingDamage daño a los seres vivos cortados (Amputate no quita vida: 0)
	 */
	public static Result cut(ServerPlayerEntity player, List<Plane> planes, Region region, boolean suspend, int noReattach, float livingDamage) {
		return cut(player, planes, region, suspend, noReattach, livingDamage, true);
	}

	/** Igual, y blocks dice si corta bloques. Recuerda los planos en el suelo cuando corta bloques sin alcance (lc). */
	public static Result cut(ServerPlayerEntity player, List<Plane> planes, Region region, boolean suspend, int noReattach, float livingDamage, boolean blocks) {
		return cut(player, planes, region, suspend, noReattach, livingDamage, blocks, blocks && region.lc == null);
	}

	public static Result cut(ServerPlayerEntity player, List<Plane> planes, Region region, boolean suspend, int noReattach,
			float livingDamage, boolean blocks, boolean remember) {
		return cut(player, planes, region, suspend, noReattach, livingDamage, blocks, remember, false);
	}

	/**
	 * Corta con varios planos a la vez.
	 * @param remember los planos que cruzan suelo quedan recordados (GroundCuts, como mucho 9 por corte) para que Takt
	 *                 arranque lo que encierran
	 * @param delayed  true en Bisturí: las piezas vivas nacen congeladas hasta soltar clic derecho
	 */
	public static Result cut(ServerPlayerEntity player, List<Plane> planes, Region region, boolean suspend, int noReattach,
			float livingDamage, boolean blocks, boolean remember, boolean delayed) {
		ServerWorld w = player.getServerWorld();
		UUID owner = player.getUuid();
		SPAWNED.clear();
		if (planes.isEmpty()) return new Result(0, 0);
		BlockView view = GroundRules.loaded(w);

		// 1. Semillas: priorizando la zona exacta donde miras y sin saltarse troncos/paredes finas.
		double aimReach = Math.max(32.0, region.sampleR() * 2.2);
		var aimHit = PowerManager.raycastBlock(player, aimReach);
		Vec3d aim = aimHit.getType() != net.minecraft.util.hit.HitResult.Type.MISS ? aimHit.getPos()
			: player.getEyePos().add(player.getRotationVector().multiply(Math.min(24.0, region.sampleR() * 0.5)));
		if (blocks) {
			SurgicalSurvival.harvestSlicedResources(player, w, aim, planes, region);
		}

		LongOpenHashSet seen = new LongOpenHashSet();
		LongArrayList seeds = new LongArrayList();
		IntArrayList seedPlane = new IntArrayList();
		IntArrayList seedSide = new IntArrayList();
		List<Vec3d> fx = new ArrayList<>();
		List<BlockState> fxState = new ArrayList<>();
		int np = planes.size();
		boolean[] planeTerrain = new boolean[np];
		int[][] planeBox = new int[np][];
		LongOpenHashSet reliefCols = new LongOpenHashSet();
		int[] reliefBounds = {Integer.MAX_VALUE, Integer.MIN_VALUE};
		IntArrayList reliefTops = new IntArrayList();
		Vec3d scC = region.sampleC();
		double sr = region.sampleR();
		int budget = Math.max(1, OpeConfig.CUT_MAX_SAMPLES / np);
		for (int pi = 0; pi < (blocks ? np : 0); pi++) {
			Plane pl = planes.get(pi);
			Vec3d u = perpendicular(pl.n());
			Vec3d v = pl.n().crossProduct(u).normalize();

			// Pasada fina alrededor de donde apunta la mira (garantiza que árboles 1x1, vallas o muros delgados queden sembrados)
			double dAim = pl.dist(aim);
			if (Math.abs(dAim) <= 6.5) {
				Vec3d qAim = aim.subtract(pl.n().multiply(dAim));
				double fineR = Math.min(6.0, sr);
				for (double a = -fineR; a <= fineR; a += 0.45) {
					for (double b = -fineR; b <= fineR; b += 0.45) {
						if (a * a + b * b > fineR * fineR) continue;
						double px = qAim.x + u.x * a + v.x * b, py = qAim.y + u.y * a + v.y * b, pz = qAim.z + u.z * a + v.z * b;
						if (!region.contains(px, py, pz)) continue;
						for (double off : new double[]{-0.75, -0.35, 0.35, 0.75}) {
							int bx = (int) Math.floor(px + pl.n().x * off);
							int by = (int) Math.floor(py + pl.n().y * off);
							int bz = (int) Math.floor(pz + pl.n().z * off);
							addSeed(w, pl, pi, bx, by, bz, seen, seeds, seedPlane, seedSide, planeTerrain, planeBox,
								reliefCols, reliefBounds, reliefTops, fx, fxState, off > 0 && off < 0.5, px, py, pz);
						}
					}
				}
			}

			// Pasada completa sobre la sección del plano dentro de la región (muestreo doble para troncos/muros de 1 bloque)
			double dc = pl.dist(scC);
			if (Math.abs(dc) >= sr) continue;
			double rr = Math.sqrt(sr * sr - dc * dc);
			Vec3d q = scC.subtract(pl.n().multiply(dc));
			double step = 0.58;
			double cells = (2 * rr / step) * (2 * rr / step);
			if (cells > budget) step = 2 * rr / Math.sqrt(budget);
			for (double a = -rr; a <= rr; a += step) {
				for (double b = -rr; b <= rr; b += step) {
					if (a * a + b * b > rr * rr) continue;
					double px = q.x + u.x * a + v.x * b, py = q.y + u.y * a + v.y * b, pz = q.z + u.z * a + v.z * b;
					if (!region.contains(px, py, pz)) continue;
					for (double off : new double[]{-0.65, -0.25, 0.25, 0.65}) {
						int bx = (int) Math.floor(px + pl.n().x * off);
						int by = (int) Math.floor(py + pl.n().y * off);
						int bz = (int) Math.floor(pz + pl.n().z * off);
						addSeed(w, pl, pi, bx, by, bz, seen, seeds, seedPlane, seedSide, planeTerrain, planeBox,
							reliefCols, reliefBounds, reliefTops, fx, fxState, off > 0 && off < 0.5, px, py, pz);
					}
				}
			}
		}

		boolean anyTerrain = false;
		for (boolean pt : planeTerrain) if (pt) { anyTerrain = true; break; }
		int groundMedianY = player.getBlockY() - 1;
		if (!reliefTops.isEmpty()) {
			int[] sortedTops = reliefTops.toIntArray();
			java.util.Arrays.sort(sortedTops);
			groundMedianY = sortedTops[sortedTops.length / 2];
		}
		boolean hasTerrainRelief = reliefBounds[1] != Integer.MIN_VALUE
			&& (reliefBounds[1] - groundMedianY >= 5 || reliefBounds[1] >= player.getBlockY() + 5);

		// Ordenamos las semillas de más cercana a más lejana respecto a donde apunta tu mira
		Integer[] seedOrder = new Integer[seeds.size()];
		double[] seedDist2 = new double[seeds.size()];
		for (int i = 0; i < seeds.size(); i++) {
			seedOrder[i] = i;
			long k = seeds.getLong(i);
			double dx = BlockPos.unpackLongX(k) + 0.5 - aim.x;
			double dy = BlockPos.unpackLongY(k) + 0.5 - aim.y;
			double dz = BlockPos.unpackLongZ(k) + 0.5 - aim.z;
			seedDist2[i] = dx * dx + dy * dy + dz * dz;
		}
		java.util.Arrays.sort(seedOrder, java.util.Comparator.comparingDouble(i -> seedDist2[i]));

		// 2a. Grupos conexos que no cruzan ningún plano: si no tocan el resto del mundo, se sueltan enteros.
		Long2IntOpenHashMap comp = new Long2IntOpenHashMap();
		comp.defaultReturnValue(0);
		List<LongArrayList> detached = new ArrayList<>();
		IntArrayList detachedSeed = new IntArrayList();
		LongArrayFIFOQueue queue = new LongArrayFIFOQueue();
		int nextId = 1;
		for (int si : seedOrder) {
			long seed = seeds.getLong(si);
			if (comp.get(seed) != 0) continue;
			LongArrayList members = new LongArrayList();
			boolean anchored = flood(w, planes, region, seed, nextId++, comp, members, queue, false,
				Integer.MIN_VALUE, false, Integer.MIN_VALUE, OpeConfig.CUT_MAX_PIECE_BLOCKS);
			if (anchored) {
				for (int i = 0; i < members.size(); i++) comp.put(members.getLong(i), -1);
			} else {
				detached.add(members);
				detachedSeed.add(si);
			}
		}

		// 2b. Lo que sigue conectado por fuera del ROOM o apoyado en el suelo:
		//     Montañas y edificios hasta el borde completo del ROOM (380k); cortes planos en el suelo quedan para Takt (GroundCuts).
		Long2IntOpenHashMap compB = new Long2IntOpenHashMap();
		compB.defaultReturnValue(0);
		int nextB = 1;
		int visitedB = 0;
		boolean found2b = false;
		int baseFloorY = Math.max(w.getBottomY(),
			Math.max(groundMedianY + 1, player.getBlockY()));
		for (int pass = 0; pass < 2; pass++) {
			for (int si : seedOrder) {
				long seed = seeds.getLong(si);
				if (comp.get(seed) != -1 || compB.get(seed) != 0) continue;
				Plane sp = planes.get(seedPlane.getInt(si));
				int side = seedSide.getInt(si);
				boolean verticalPlane = Math.abs(sp.n().y) < 0.32;
				if (pass == 1 && (!verticalPlane || side >= 0 || found2b)) continue;
				BlockPos spPos = BlockPos.fromLong(seed);
				BlockState seedSt = w.getBlockState(spPos);
				boolean terrainSeed = isNaturalSoil(seedSt) || isNaturalRock(seedSt);
				int minFloorY;
				boolean skipSoil;
				int skipRockBelowY;
				if (!verticalPlane) {
					if (side * sp.n().y < 0.32) continue;
					minFloorY = Integer.MIN_VALUE;
					skipSoil = false;
					skipRockBelowY = Integer.MIN_VALUE;
				} else {
					if (pass == 0 && side < 0) continue;
					if (terrainSeed && (!hasTerrainRelief || pass == 1 || spPos.getY() < baseFloorY)) continue;
					skipSoil = !terrainSeed;
					skipRockBelowY = terrainSeed ? Integer.MIN_VALUE : spPos.getY() - 1;
					minFloorY = baseFloorY;
				}
				if (visitedB > OpeConfig.CUT_MAX_SLICE_VISIT) break;
				LongArrayList members = new LongArrayList();
				int maxSlice = verticalPlane ? 220_000 : OpeConfig.CUT_MAX_SLICE_BLOCKS;
				boolean anchored = flood(w, planes, region, seed, nextB++, compB, members, queue, true,
					minFloorY, skipSoil, skipRockBelowY, maxSlice);
				visitedB += members.size();
				if (!anchored && verticalPlane && terrainSeed) {
					int minY = Integer.MAX_VALUE, maxY = Integer.MIN_VALUE;
					for (int i = 0; i < members.size(); i++) {
						int by = BlockPos.unpackLongY(members.getLong(i));
						if (by < minY) minY = by;
						if (by > maxY) maxY = by;
					}
					if (members.size() < 64 || maxY - minY < 4) anchored = true;
				}
				if (!anchored && !verticalPlane && members.size() > OpeConfig.CUT_HOVER_MAX_BLOCKS) {
					int minY = Integer.MAX_VALUE;
					for (int i = 0; i < members.size(); i++) {
						int by = BlockPos.unpackLongY(members.getLong(i));
						if (by < minY) minY = by;
					}
					if ((minY <= groundMedianY + 2 || Math.abs(sp.n().y) < 0.90)
						&& slideBlocked(view, members, sp, side)) {
						anchored = true;
					}
				}
				if (anchored) {
					for (int i = 0; i < members.size(); i++) compB.put(members.getLong(i), -1);
				} else {
					detached.add(members);
					detachedSeed.add(si);
					found2b = true;
				}
			}
			if (found2b) break;
		}

		// 2c. Respaldo para mega-montañas (>380k bloques): divide toda la extensión del ROOM en columnas sectorizadas 24x24.
		if (!found2b && !seeds.isEmpty() && sr > 12.0) {
			final int colW = 24;
			java.util.LinkedHashMap<Long, Integer> colReps = new java.util.LinkedHashMap<>();
			for (int si : seedOrder) {
				long seed = seeds.getLong(si);
				if (comp.get(seed) != -1) continue;
				Plane sp = planes.get(seedPlane.getInt(si));
				int side = seedSide.getInt(si);
				boolean verticalPlane = Math.abs(sp.n().y) < 0.32;
				if (!verticalPlane && side * sp.n().y < 0.32) continue;
				if (verticalPlane && side < 0) continue;
				BlockState seedSt = w.getBlockState(BlockPos.fromLong(seed));
				boolean terrainSeed = isNaturalSoil(seedSt) || isNaturalRock(seedSt);
				int bx = BlockPos.unpackLongX(seed), by = BlockPos.unpackLongY(seed), bz = BlockPos.unpackLongZ(seed);
				if (terrainSeed && (!hasTerrainRelief || by < baseFloorY)) continue;
				int gx = Math.floorDiv(bx, colW), gz = Math.floorDiv(bz, colW);
				long cKey = ((long) (gx & 0xFFFFFFFFL) << 32) | (gz & 0xFFFFFFFFL);
				colReps.putIfAbsent(cKey, si);
			}
			Long2IntOpenHashMap compF = new Long2IntOpenHashMap();
			compF.defaultReturnValue(0);
			int nextF = 1;
			int total2c = 0;
			for (int si : colReps.values()) {
				if (total2c >= 720_000) break;
				long seed = seeds.getLong(si);
				if (compF.get(seed) != 0) continue;
				int bx = BlockPos.unpackLongX(seed), by = BlockPos.unpackLongY(seed), bz = BlockPos.unpackLongZ(seed);
				int gx = Math.floorDiv(bx, colW), gz = Math.floorDiv(bz, colW);
				Region colReg = region.withXZBounds(gx * colW, (gx + 1) * colW, gz * colW, (gz + 1) * colW);
				Plane sp = planes.get(seedPlane.getInt(si));
				boolean verticalPlane = Math.abs(sp.n().y) < 0.32;
				BlockState seedSt = w.getBlockState(new BlockPos(bx, by, bz));
				boolean terrainSeed = isNaturalSoil(seedSt) || isNaturalRock(seedSt);
				boolean skipSoil = verticalPlane && !terrainSeed;
				int skipRockBelowY = (verticalPlane && !terrainSeed) ? by - 1 : Integer.MIN_VALUE;
				int minFloorY = verticalPlane ? baseFloorY : Integer.MIN_VALUE;
				LongArrayList members = new LongArrayList();
				boolean anchored = flood(w, planes, colReg, seed, nextF++, compF, members, queue, true,
					minFloorY, skipSoil, skipRockBelowY, 45000);
				if (anchored) {
					for (int i = 0; i < members.size(); i++) compF.put(members.getLong(i), -1);
				} else {
					detached.add(members);
					detachedSeed.add(si);
					total2c += members.size();
				}
			}
		}

		// Sectorización antes de tocar ningún bloque: montañas grandes se dividen en sectores sincronizados
		long totalDetachedBlocks = 0;
		for (LongArrayList m : detached) totalDetachedBlocks += m.size();
		int secXZ = totalDetachedBlocks > 450_000 ? 28 : (totalDetachedBlocks > 140_000 ? 24 : 20);
		int secY = totalDetachedBlocks > 450_000 ? 36 : (totalDetachedBlocks > 140_000 ? 32 : 24);
		List<LongArrayList> finalGroups = new ArrayList<>();
		IntArrayList finalSeeds = new IntArrayList();
		for (int gi = 0; gi < detached.size() && finalGroups.size() < OpeConfig.CUT_MAX_PIECES; gi++) {
			LongArrayList members = detached.get(gi);
			int si = detachedSeed.getInt(gi);
			if (members.size() <= 9600) {
				finalGroups.add(members);
				finalSeeds.add(si);
			} else {
				Map<Long, LongArrayList> sectors = new java.util.LinkedHashMap<>();
				for (int i = 0; i < members.size(); i++) {
					long k = members.getLong(i);
					int sx = Math.floorDiv(BlockPos.unpackLongX(k), secXZ);
					int sy = Math.floorDiv(BlockPos.unpackLongY(k), secY);
					int sz = Math.floorDiv(BlockPos.unpackLongZ(k), secXZ);
					long secKey = ((long) (sx & 0xFFFFF) << 40) | ((long) (sy & 0xFFFFF) << 20) | (long) (sz & 0xFFFFF);
					sectors.computeIfAbsent(secKey, ignored -> new LongArrayList()).add(k);
				}
				for (LongArrayList sec : sectors.values()) {
					if (finalGroups.size() >= OpeConfig.CUT_MAX_PIECES) break;
					finalGroups.add(sec);
					finalSeeds.add(si);
				}
			}
		}

		// Antes de tocar ningún bloque: material, GroundCuts y aviso a clientes (dibuja costura sobre el mundo entero).
		int count = finalGroups.size();
		Vec3d mid = Vec3d.ZERO;
		BlockState mainMat = null;
		Map<BlockState, Integer> matCount = new HashMap<>();
		for (int i = 0; i < fx.size(); i++) {
			mid = mid.add(fx.get(i));
			BlockState st = fxState.get(i);
			int c = matCount.merge(st, 1, Integer::sum);
			if (mainMat == null || c > matCount.getOrDefault(mainMat, 0)) mainMat = st;
		}
		int livingCut = countLiving(w, player, planes, region);
		int recorded = 0;
		if (remember && blocks) {
			for (int pi = 0; pi < np && recorded < OpeConfig.GROUND_CUT_MAX_PER_CUT; pi++) {
				if (!planeTerrain[pi]) continue;
				int[] b = planeBox[pi];
				GroundCuts.record(player, planes.get(pi), region, new Box(b[0], b[1], b[2], b[3] + 1, b[4] + 1, b[5] + 1));
				recorded++;
			}
		}
		int flags = 0;
		if (blocks && !seeds.isEmpty()) flags |= Payloads.CutFx.F_BLOCKS;
		if (anyTerrain) flags |= Payloads.CutFx.F_TERRAIN;
		if (suspend) flags |= Payloads.CutFx.F_SUSPEND;
		if (recorded > 0) flags |= Payloads.CutFx.F_REMEMBERED;
		sendFx(w, player, planes, region, count, livingCut, mainMat == null ? -1 : Block.getRawIdFromState(mainMat), flags);

		// 3. Cada grupo suelto pasa a ser una pieza (A: cutFaceMask, B: aire, C: entidades + slide/suspensión).
		PlayerPower power = PowerManager.get(player);
		Vec3d look = player.getRotationVector();
		long now = w.getTime();
		List<BlockSet> sets = new ArrayList<>(count);
		List<BlockPos> mins = new ArrayList<>(count);
		List<List<BlockPos>> groupCells = new ArrayList<>(count);
		LongOpenHashSet removedSet = new LongOpenHashSet();
		for (int gi = 0; gi < count; gi++) {
			LongArrayList members = finalGroups.get(gi);
			int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
			for (int i = 0; i < members.size(); i++) {
				long k = members.getLong(i);
				minX = Math.min(minX, BlockPos.unpackLongX(k));
				minY = Math.min(minY, BlockPos.unpackLongY(k));
				minZ = Math.min(minZ, BlockPos.unpackLongZ(k));
			}
			BlockSet set = new BlockSet();
			List<BlockPos> mine = new ArrayList<>(members.size());
			for (int i = 0; i < members.size(); i++) {
				long k = members.getLong(i);
				BlockPos pos = BlockPos.fromLong(k);
				BlockState st = w.getBlockState(pos);
				NbtCompound beNbt = null;
				BlockEntity be = w.getBlockEntity(pos);
				if (be != null) beNbt = be.createNbtWithIdentifyingData(w.getRegistryManager());
				set.add(pos.getX() - minX, pos.getY() - minY, pos.getZ() - minZ, st, beNbt);
				mine.add(pos);
				removedSet.add(k);
			}
			BlockPos min = new BlockPos(minX, minY, minZ);
			set.cutFaceMask(view, min);
			sets.add(set);
			mins.add(min);
			groupCells.add(mine);
		}
		List<BlockPos> removed = new ArrayList<>();
		for (List<BlockPos> mine : groupCells) {
			for (BlockPos pos : mine) {
				BlockEntity be = w.getBlockEntity(pos);
				if (be instanceof Inventory inv) inv.clear();
				w.setBlockState(pos, Blocks.AIR.getDefaultState(), Block.NOTIFY_LISTENERS | Block.FORCE_STATE | Block.SKIP_DROPS);
			}
			removed.addAll(mine);
		}
		int pieces = 0;
		for (int gi = 0; gi < count; gi++) {
			BlockSet set = sets.get(gi);
			BlockPos min = mins.get(gi);
			set.normalize();
			PieceEntity pe = PieceEntity.spawn(w, set, new Vec3d(min.getX(), min.getY(), min.getZ()), min, owner);
			SPAWNED.add(pe);
			pe.markFx(now);
			pe.setFlag(PieceEntity.F_FRESH, true);
			int si = finalSeeds.getInt(gi);
			Plane pl0 = planes.get(seedPlane.getInt(si));
			Vec3d nSide = pl0.n().multiply(seedSide.getInt(si));
			if (suspend) {
				pe.control(pe.center().add(nSide.multiply(0.3)));
				pe.setFlag(PieceEntity.F_SUSPENDED, true);
				power.suspended.add(pe.getId());
			} else if (nSide.y >= OpeConfig.CUT_SLIDE_MIN_UP) {
				Vec3d swipe = swipeOf(pl0, look);
				Vec3d dir = swipe.add(look.multiply(0.35));
				Vec3d push = dir.subtract(nSide.multiply(dir.dotProduct(nSide)));
				push = push.lengthSquared() < 1e-4 ? Vec3d.ZERO : push.normalize().multiply(OpeConfig.CUT_SLIDE_PUSH);
				pe.startSlide(nSide, pl0.p(), push);
				pe.hold(OpeConfig.CUT_HOLD_TICKS);
			} else {
				pe.setVelocity(nSide.multiply(0.08));
				pe.hold(OpeConfig.CUT_HOLD_TICKS);
			}
			pieces++;
		}
		if (removed.size() <= 8000) {
			for (BlockPos pos : removed) w.updateNeighbors(pos, Blocks.AIR);
		} else {
			BlockPos.Mutable nm = new BlockPos.Mutable();
			for (BlockPos pos : removed) {
				for (Direction d : DIRS) {
					nm.set(pos.getX() + d.getOffsetX(), pos.getY() + d.getOffsetY(), pos.getZ() + d.getOffsetZ());
					if (!removedSet.contains(nm.asLong())) {
						w.updateNeighbors(pos, Blocks.AIR);
						break;
					}
				}
			}
		}

		// 4. Seres vivos y piezas que ya estaban sueltas.
		int living = 0;
		for (Entity e : w.getOtherEntities(player, region.box(), e -> !(e instanceof SterbenEntity))) {
			if (e instanceof PieceEntity pe) {
				if (pe.weapon != null || pe.isRemoved() || pe.settling() || pe.mode == PieceEntity.Mode.RETURNING
					|| pe.mode == PieceEntity.Mode.SEWING || (pe.flags() & PieceEntity.F_LANDED) != 0) continue;
				if (!region.contains(pe.center())) continue;
				splitPiece(pe, planes, suspend, power);
			} else if (e instanceof dev.opeope.entity.BodyPartEntity bp) {
				if (bp.isRemoved() || bp.mounted() || bp.frozen()) continue;
				Box b = bp.getBoundingBox();
				if (!region.contains(b.getCenter())) continue;
				List<Plane> crossing = new ArrayList<>();
				for (Plane pl : planes) if (pl.crosses(b)) crossing.add(pl);
				if (!crossing.isEmpty()) Dismember.splitBodyPart(player, bp, crossing, noReattach > 0, delayed);
			} else if (e instanceof LivingEntity le && le.isAlive()) {
				Box b = le.getBoundingBox();
				if (!region.contains(b.getCenter())) continue;
				List<Plane> crossing = new ArrayList<>();
				for (Plane pl : planes) if (pl.crosses(b)) crossing.add(pl);
				if (crossing.isEmpty()) continue;
				// Corte libre / articulaciones (estilo Law)
				if (!CutStates.isCut(le) && Dismember.sweep(player, le, crossing, noReattach > 0, delayed)) {
					if (livingDamage > 0) le.damage(w.getDamageSources().playerAttack(player), livingDamage);
					living++;
					continue;
				}
				CutStates.cutLiving(le, crossing, OpeConfig.CUT_LIVING_TICKS, noReattach);
				if (livingDamage > 0) le.damage(w.getDamageSources().playerAttack(player), livingDamage);
				w.spawnParticles(ParticleTypes.END_ROD, b.getCenter().x, b.getCenter().y, b.getCenter().z, 12, b.getLengthX() / 2, b.getLengthY() / 3, b.getLengthZ() / 2, 0.02);
				living++;
			}
		}

		// 5. La línea del corte y el sonido del material (el tajo, el cristal y el golpe los pone el cliente).
		for (int i = 0; i < fx.size(); i++) {
			Vec3d p = fx.get(i);
			BlockState st = fxState.get(i);
			w.spawnParticles(ROOM_DUST, p.x, p.y, p.z, 1, 0.05, 0.05, 0.05, 0);
			w.spawnParticles(new BlockStateParticleEffect(ParticleTypes.BLOCK, st), p.x, p.y, p.z, 2, 0.1, 0.1, 0.1, 0.08);
			if (w.random.nextInt(4) == 0) w.spawnParticles(ParticleTypes.END_ROD, p.x, p.y, p.z, 1, 0, 0, 0, 0.005);
		}
		if (!fx.isEmpty() && mainMat != null) {
			mid = mid.multiply(1.0 / fx.size());
			var group = mainMat.getSoundGroup();
			w.playSound(null, mid.x, mid.y, mid.z, group.getBreakSound(), SoundCategory.BLOCKS, 1.0f, group.getPitch() * 0.8f);
			if (pieces > 0) w.playSound(null, mid.x, mid.y, mid.z, group.getHitSound(), SoundCategory.BLOCKS, 1.0f, 0.6f);
		}
		if (AUTOTEST) dev.opeope.OpeOpeMod.LOG.info("Corte: {} planos, {} piezas, {} seres, {} semillas", planes.size(), pieces, living, seeds.size());
		return new Result(pieces, living);
	}

	static final boolean AUTOTEST = Boolean.getBoolean("opeope.autotest");

	/** Las piezas que salieron del último corte (para que el Bisturí las sujete). */
	public static final List<PieceEntity> SPAWNED = new ArrayList<>();

	/** La dirección del tajo dentro del plano: el plano contiene la mirada y el trazo (n = mirada × trazo). */
	static Vec3d swipeOf(Plane pl, Vec3d look) {
		Vec3d s = pl.n().crossProduct(look);
		if (s.lengthSquared() < 1e-6) {
			s = pl.n().crossProduct(new Vec3d(0, 1, 0));
			if (s.lengthSquared() < 1e-6) s = new Vec3d(1, 0, 0);
		}
		return s.normalize();
	}

	/** Agranda la caja (min x y z, max x y z) de las semillas de un plano con un bloque más. */
	private static int[] grow(int[] b, int x, int y, int z) {
		if (b == null) return new int[]{x, y, z, x, y, z};
		b[0] = Math.min(b[0], x);
		b[1] = Math.min(b[1], y);
		b[2] = Math.min(b[2], z);
		b[3] = Math.max(b[3], x);
		b[4] = Math.max(b[4], y);
		b[5] = Math.max(b[5], z);
		return b;
	}

	/** Los seres vivos que el corte va a cortar (la misma prueba que la fase 4, sin tocarlos). */
	private static int countLiving(ServerWorld w, ServerPlayerEntity player, List<Plane> planes, Region region) {
		int n = 0;
		for (Entity e : w.getOtherEntities(player, region.box(), e -> e instanceof LivingEntity && !(e instanceof SterbenEntity))) {
			if (!e.isAlive()) continue;
			Box b = e.getBoundingBox();
			if (!region.contains(b.getCenter())) continue;
			for (Plane pl : planes) {
				if (pl.crosses(b)) {
					n++;
					break;
				}
			}
		}
		return n;
	}

	/**
	 * La regla de la cuña: ¿puede la loncha bajar un poco por el plano del corte? Cada bloque, encogido 0,03 por cada lado
	 * y movido TAKT_SLIDE_PROBE cuesta abajo por el plano, no debe tocar suelo sólido que no sea de la loncha ni de la piel
	 * del corte (la escalera de bloques justo al otro lado del plano). Un corte horizontal no deja bajar nada.
	 * @param side el lado de la loncha (la normal del plano por side apunta hacia ella)
	 */
	private static boolean slideBlocked(BlockView view, LongArrayList members, Plane pl, int side) {
		Vec3d n = pl.n().normalize().multiply(side);
		double d = n.dotProduct(pl.p());
		Vec3d s = new Vec3d(n.y * n.x, n.y * n.y - 1, n.y * n.z);   // la gravedad a lo largo del plano
		double len = s.length();
		if (len < 1e-3) return true;
		Vec3d mv = s.multiply(OpeConfig.TAKT_SLIDE_PROBE / len);
		LongOpenHashSet inside = new LongOpenHashSet(members);
		BlockPos.Mutable q = new BlockPos.Mutable();
		final double e = 0.03;
		for (int i = 0; i < members.size(); i++) {
			long k = members.getLong(i);
			double x0 = BlockPos.unpackLongX(k) + e + mv.x, y0 = BlockPos.unpackLongY(k) + e + mv.y, z0 = BlockPos.unpackLongZ(k) + e + mv.z;
			int ix0 = MathHelper.floor(x0), iy0 = MathHelper.floor(y0), iz0 = MathHelper.floor(z0);
			int ix1 = MathHelper.floor(x0 + 1 - 2 * e), iy1 = MathHelper.floor(y0 + 1 - 2 * e), iz1 = MathHelper.floor(z0 + 1 - 2 * e);
			for (int x = ix0; x <= ix1; x++) {
				for (int y = iy0; y <= iy1; y++) {
					for (int z = iz0; z <= iz1; z++) {
						if (inside.contains(BlockPos.asLong(x, y, z))) continue;
						double dist = n.x * (x + 0.5) + n.y * (y + 0.5) + n.z * (z + 0.5) - d;
						if (dist > -1.05 && dist <= 0.05) continue;   // la piel del corte
						q.set(x, y, z);
						if (GroundRules.solid(view.getBlockState(q), view, q)) return true;
					}
				}
			}
		}
		return false;
	}

	/**
	 * Avisa a los que miran: la hoja de luz y la costura se dibujan en el cliente sobre el mundo aún entero. Lleva quién
	 * corta, cuánto se soltó, el material y qué cortó (bloques, suelo, suspender, recordado).
	 */
	private static void sendFx(ServerWorld w, ServerPlayerEntity player, List<Plane> planes, Region region, int pieces, int living,
			int material, int flags) {
		Vec3d look = region.look != null ? region.look : player.getRotationVector();
		int np = Math.min(planes.size(), 16);
		float[] d = new float[14 + np * 9];
		Vec3d c = region.c, e = region.eye;
		d[0] = (float) c.x; d[1] = (float) c.y; d[2] = (float) c.z; d[3] = (float) region.r;
		if (region.lc != null) {
			d[4] = (float) region.lc.x; d[5] = (float) region.lc.y; d[6] = (float) region.lc.z; d[7] = (float) region.lr;
		}
		if (e != null && region.look != null) {
			d[8] = (float) e.x; d[9] = (float) e.y; d[10] = (float) e.z;
			d[11] = (float) look.x; d[12] = (float) look.y; d[13] = (float) look.z;
		}
		for (int i = 0; i < np; i++) {
			Plane pl = planes.get(i);
			Vec3d s = swipeOf(pl, look);
			int o = 14 + i * 9;
			d[o] = (float) pl.n().x; d[o + 1] = (float) pl.n().y; d[o + 2] = (float) pl.n().z;
			d[o + 3] = (float) pl.p().x; d[o + 4] = (float) pl.p().y; d[o + 5] = (float) pl.p().z;
			d[o + 6] = (float) s.x; d[o + 7] = (float) s.y; d[o + 8] = (float) s.z;
		}
		Payloads.CutFx payload = new Payloads.CutFx(d, player.getId(), pieces, living, material, flags);
		double reach = Math.min(384.0, Math.max(192.0, region.sampleR() + 64.0));
		for (ServerPlayerEntity sp : PlayerLookup.around(w, c, reach)) ServerPlayNetworking.send(sp, payload);
	}

	/**
	 * Recorre el grupo de bloques unidos a la semilla sin cruzar ningún plano. Devuelve true si está anclado:
	 * llega al fondo del mundo, a un bloque irrompible, a un chunk sin cargar, a otro grupo anclado o es demasiado grande.
	 * @param roomBounded el borde de la región también corta (lo de fuera no cuenta como unión)
	 * @param minFloorY si > Integer.MIN_VALUE, el plano del suelo (minFloorY) también actúa como borde de corte
	 */
	private static boolean flood(ServerWorld w, List<Plane> planes, Region region, long seed, int id, Long2IntOpenHashMap comp,
			LongArrayList members, LongArrayFIFOQueue queue, boolean roomBounded, int minFloorY, boolean skipSoil, int skipRockBelowY, int maxBlocks) {
		BlockPos.Mutable m = new BlockPos.Mutable();
		int bottom = w.getBottomY(), top = w.getTopY();
		comp.put(seed, id);
		members.add(seed);
		queue.clear();
		queue.enqueue(seed);
		while (!queue.isEmpty()) {
			long cur = queue.dequeueLong();
			int cx = BlockPos.unpackLongX(cur), cy = BlockPos.unpackLongY(cur), cz = BlockPos.unpackLongZ(cur);
			for (Direction d : DIRS) {
				int nx = cx + d.getOffsetX(), ny = cy + d.getOffsetY(), nz = cz + d.getOffsetZ();
				if (ny < bottom) return true;
				if (ny >= top) continue;
				if (minFloorY > Integer.MIN_VALUE && ny < minFloorY) continue;
				if (roomBounded && !region.contains(nx + 0.5, ny + 0.5, nz + 0.5)) continue;
				long nk = BlockPos.asLong(nx, ny, nz);
				int c = comp.get(nk);
				if (c == id) continue;
				if (separated(planes, region, cx, cy, cz, nx, ny, nz)) continue;
				m.set(nx, ny, nz);
				if (c != 0) {
					if (minFloorY > Integer.MIN_VALUE) continue;
					if (cuttable(w.getBlockState(m), w, m)) return true;
					continue;
				}
				if (!w.isChunkLoaded(m)) return true;
				BlockState st = w.getBlockState(m);
				if (st.isAir() || st.getBlock() instanceof FluidBlock) continue;
				if (skipSoil && isNaturalSoil(st)) continue;
				if (skipRockBelowY > Integer.MIN_VALUE && ny <= skipRockBelowY && isNaturalRock(st)) continue;
				if (st.getHardness(w, m) < 0) return true;
				comp.put(nk, id);
				members.add(nk);
				queue.enqueue(nk);
				if (members.size() > maxBlocks) return true;
			}
		}
		return false;
	}

	/**
	 * Parte una pieza suelta por los planos. Cada parte guarda las caras cortadas que ya tenía y gana las que el corte
	 * acaba de abrir. Si alguien la llevaba con Takt, las partes siguen siendo suyas (no de quien corta).
	 */
	public static void splitPiece(PieceEntity pe, List<Plane> planes, boolean suspend, PlayerPower power) {
		Vec3d corner = pe.corner();
		List<Plane> crossing = new ArrayList<>();
		for (Plane pl : planes) if (pl.crosses(pe.getBoundingBox())) crossing.add(pl);
		if (crossing.isEmpty()) return;
		List<BlockSet.Entry> entries = pe.blocks.entries;
		int n = entries.size();
		int[] partOf = new int[n];
		Long2IntOpenHashMap partAt = new Long2IntOpenHashMap(Math.max(16, n * 2));
		for (int i = 0; i < n; i++) {
			BlockSet.Entry e = entries.get(i);
			double x = corner.x + e.x() + 0.5, y = corner.y + e.y() + 0.5, z = corner.z + e.z() + 0.5;
			int mask = 0;
			for (int k = 0; k < crossing.size(); k++) if (crossing.get(k).dist(x, y, z) > 0) mask |= 1 << k;
			partOf[i] = mask;
			partAt.put(BlockPos.asLong(e.x(), e.y(), e.z()), mask);
		}
		Map<Integer, BlockSet> parts = new HashMap<>();
		for (int i = 0; i < n; i++) {
			BlockSet.Entry e = entries.get(i);
			int faces = e.faces();
			for (Direction d : DIRS) {
				long nk = BlockPos.asLong(e.x() + d.getOffsetX(), e.y() + d.getOffsetY(), e.z() + d.getOffsetZ());
				if (partAt.containsKey(nk) && partAt.get(nk) != partOf[i]) faces |= 1 << d.ordinal();
			}
			parts.computeIfAbsent(partOf[i], k -> new BlockSet()).add(e.x(), e.y(), e.z(), e.state(), e.be(), faces);
		}
		if (parts.size() <= 1) return;
		ServerWorld w = (ServerWorld) pe.getWorld();
		long now = w.getTime();
		Vec3d vel = pe.getVelocity();
		UUID holder = Takt.holder(pe.getId());
		PlayerPower holderPower = holder == null ? null : PowerManager.peek(holder);
		boolean wasHeld = holderPower != null && holderPower.takt.contains(pe.getId());
		boolean wasSuspended = (pe.flags() & PieceEntity.F_SUSPENDED) != 0 && pe.mode == PieceEntity.Mode.CONTROLLED;
		List<PieceEntity> made = new ArrayList<>();
		for (Map.Entry<Integer, BlockSet> part : parts.entrySet()) {
			BlockSet set = part.getValue();
			BlockPos off = set.normalize();
			Vec3d nc = corner.add(off.getX(), off.getY(), off.getZ());
			BlockPos origin = pe.origin != null ? pe.origin.add(off) : null;
			PieceEntity np = PieceEntity.spawn(w, set, nc, origin, pe.owner);
			np.markFx(now);
			np.setFlag(PieceEntity.F_FRESH, true);
			Vec3d sep = Vec3d.ZERO;
			for (int i = 0; i < crossing.size(); i++) {
				sep = sep.add(crossing.get(i).n().multiply(((part.getKey() >> i) & 1) == 1 ? 0.07 : -0.07));
			}
			if (wasHeld) {
				np.control(np.center().add(sep.multiply(4)));
				np.leaveLike(pe);   // partida mientras salía de su hueco sin chocar: cada parte termina esa subida
				made.add(np);
			} else if (suspend || wasSuspended) {
				np.control(np.center().add(sep.multiply(4)));
				np.setFlag(PieceEntity.F_SUSPENDED, true);
				power.suspended.add(np.getId());
			} else {
				np.setVelocity(vel.add(sep));
			}
			if (pe.holdTicks() > 0) np.hold(pe.holdTicks());
			SPAWNED.add(np);
		}
		if (wasHeld) {
			holderPower.takt.remove((Integer) pe.getId());
			holderPower.parked.remove(pe.getId());
			holderPower.taktOffsets.remove(pe.getId());
			for (PieceEntity np : made) Takt.adopt(holderPower, holder, np);
			holderPower.dirty = true;
		}
		if (holder != null) Takt.forget(pe.getId());
		pe.discard();
	}
}
