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

		public boolean contains(Vec3d p) {
			return contains(p.x, p.y, p.z);
		}

		public boolean contains(double x, double y, double z) {
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

	/**
	 * Corta con varios planos a la vez.
	 * @param remember los planos que cruzan suelo quedan recordados (GroundCuts, como mucho 9 por corte) para que Takt
	 *                 arranque lo que encierran
	 */
	public static Result cut(ServerPlayerEntity player, List<Plane> planes, Region region, boolean suspend, int noReattach,
			float livingDamage, boolean blocks, boolean remember) {
		ServerWorld w = player.getServerWorld();
		UUID owner = player.getUuid();
		SPAWNED.clear();
		if (planes.isEmpty()) return new Result(0, 0);
		BlockView view = GroundRules.loaded(w);

		// 1. Semillas: los bloques pegados a cada plano dentro de la región (y de qué lado del plano quedan).
		LongOpenHashSet seen = new LongOpenHashSet();
		LongArrayList seeds = new LongArrayList();
		IntArrayList seedPlane = new IntArrayList();
		IntArrayList seedSide = new IntArrayList();
		List<Vec3d> fx = new ArrayList<>();
		List<BlockState> fxState = new ArrayList<>();
		int np = planes.size();
		boolean[] planeTerrain = new boolean[np];   // el plano tiene alguna semilla de suelo
		int[][] planeBox = new int[np][];           // la caja de las semillas de cada plano: min x y z, max x y z
		boolean anyTerrain = false;
		Vec3d scC = region.sampleC();
		double sr = region.sampleR();
		BlockPos.Mutable m = new BlockPos.Mutable();
		int budget = OpeConfig.CUT_MAX_SAMPLES / planes.size();
		for (int pi = 0; pi < (blocks ? np : 0); pi++) {
			Plane pl = planes.get(pi);
			double dc = pl.dist(scC);
			if (Math.abs(dc) >= sr) continue;
			double rr = Math.sqrt(sr * sr - dc * dc);
			Vec3d q = scC.subtract(pl.n().multiply(dc));
			Vec3d u = perpendicular(pl.n());
			Vec3d v = pl.n().crossProduct(u).normalize();
			double step = 0.7;
			double cells = (2 * rr / step) * (2 * rr / step);
			if (cells > budget) step = 2 * rr / Math.sqrt(budget);
			for (double a = -rr; a <= rr; a += step) {
				for (double b = -rr; b <= rr; b += step) {
					if (a * a + b * b > rr * rr) continue;
					double px = q.x + u.x * a + v.x * b, py = q.y + u.y * a + v.y * b, pz = q.z + u.z * a + v.z * b;
					if (!region.contains(px, py, pz)) continue;
					for (int s = -1; s <= 1; s += 2) {
						m.set(px + pl.n().x * 0.5 * s, py + pl.n().y * 0.5 * s, pz + pl.n().z * 0.5 * s);
						long key = m.asLong();
						if (!seen.add(key)) continue;
						BlockState st = w.getBlockState(m);
						if (cuttable(st, w, m)) {
							seeds.add(key);
							seedPlane.add(pi);
							// el lado real del bloque (por su centro), no el de la muestra
							seedSide.add(pl.dist(m.getX() + 0.5, m.getY() + 0.5, m.getZ() + 0.5) >= 0 ? 1 : -1);
							if (GroundRules.terrain(st)) {
								planeTerrain[pi] = true;
								anyTerrain = true;
							}
							planeBox[pi] = grow(planeBox[pi], m.getX(), m.getY(), m.getZ());
							if (s == 1 && fx.size() < 240 && w.random.nextInt(3) == 0) {
								fx.add(new Vec3d(px, py, pz));
								fxState.add(st);
							}
						}
					}
				}
			}
		}

		// 2a. Grupos conexos que no cruzan ningún plano: si no tocan el resto del mundo, se sueltan enteros
		//     (un árbol, una torre, una casa que cabe en el ROOM).
		Long2IntOpenHashMap comp = new Long2IntOpenHashMap();
		comp.defaultReturnValue(0);
		List<LongArrayList> detached = new ArrayList<>();
		IntArrayList detachedSeed = new IntArrayList();
		LongArrayFIFOQueue queue = new LongArrayFIFOQueue();
		int nextId = 1;
		for (int si = 0; si < seeds.size(); si++) {
			long seed = seeds.getLong(si);
			if (comp.get(seed) != 0) continue;
			LongArrayList members = new LongArrayList();
			boolean anchored = flood(w, planes, region, seed, nextId++, comp, members, queue, false, OpeConfig.CUT_MAX_PIECE_BLOCKS);
			if (anchored) {
				for (int i = 0; i < members.size(); i++) comp.put(members.getLong(i), -1);
			} else {
				detached.add(members);
				detachedSeed.add(si);
			}
		}

		// 2b. Lo que sigue pegado al terreno por fuera del ROOM: el borde del ROOM también corta. Lo que queda encima
		//     de un corte no vertical (la cima de una montaña, la parte de arriba de una pared o de un tejado) se separa
		//     igual, recortado por la esfera. Lo de debajo del corte es el suelo y se queda.
		Long2IntOpenHashMap compB = new Long2IntOpenHashMap();
		compB.defaultReturnValue(0);
		int nextB = 1;
		int visitedB = 0;
		for (int si = 0; si < seeds.size(); si++) {
			long seed = seeds.getLong(si);
			if (comp.get(seed) != -1 || compB.get(seed) != 0) continue;
			Plane sp = planes.get(seedPlane.getInt(si));
			if (seedSide.getInt(si) * sp.n().y < OpeConfig.CUT_SLIDE_MIN_UP) continue;
			if (visitedB > OpeConfig.CUT_MAX_SLICE_VISIT) break;
			LongArrayList members = new LongArrayList();
			boolean anchored = flood(w, planes, region, seed, nextB++, compB, members, queue, true, OpeConfig.CUT_MAX_SLICE_BLOCKS);
			visitedB += members.size();
			// La regla de la cuña: una loncha grande que no puede bajar por el plano (la sujeta suelo de fuera del ROOM o
			// de detrás de otro plano) se queda como suelo: sin parpadeo, y el corte queda recordado para Takt.
			if (!anchored && members.size() > OpeConfig.CUT_HOVER_MAX_BLOCKS && slideBlocked(view, members, sp, seedSide.getInt(si))) {
				anchored = true;
			}
			if (anchored) {
				for (int i = 0; i < members.size(); i++) compB.put(members.getLong(i), -1);
			} else {
				detached.add(members);
				detachedSeed.add(si);
			}
		}

		// Antes de tocar ningún bloque: el material, lo que se va a soltar, los seres que se cortan, lo que el suelo
		// recuerda y el aviso a los clientes (que dibujan la costura sobre el mundo aún entero).
		int count = Math.min(detached.size(), OpeConfig.CUT_MAX_PIECES);
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

		// 3. Cada grupo suelto pasa a ser una pieza, que resbala por el plano del corte. En tres pasadas, para que las
		//    caras cortadas de cada pieza se midan con todos los bloques aún en su sitio.
		PlayerPower power = PowerManager.get(player);
		Vec3d look = player.getRotationVector();
		long now = w.getTime();
		List<BlockSet> sets = new ArrayList<>(count);
		List<BlockPos> mins = new ArrayList<>(count);
		List<List<BlockPos>> groupCells = new ArrayList<>(count);
		for (int gi = 0; gi < count; gi++) {
			// A: los bloques (con los datos de cofres y demás) y sus caras cortadas
			LongArrayList members = detached.get(gi);
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
				BlockPos pos = BlockPos.fromLong(members.getLong(i));
				BlockState st = w.getBlockState(pos);
				NbtCompound beNbt = null;
				BlockEntity be = w.getBlockEntity(pos);
				if (be != null) beNbt = be.createNbtWithIdentifyingData(w.getRegistryManager());
				set.add(pos.getX() - minX, pos.getY() - minY, pos.getZ() - minZ, st, beNbt);
				mine.add(pos);
			}
			BlockPos min = new BlockPos(minX, minY, minZ);
			set.cutFaceMask(view, min);
			sets.add(set);
			mins.add(min);
			groupCells.add(mine);
		}
		List<BlockPos> removed = new ArrayList<>();
		for (List<BlockPos> mine : groupCells) {
			// B: fuera del mundo
			for (BlockPos pos : mine) {
				BlockEntity be = w.getBlockEntity(pos);
				if (be instanceof Inventory inv) inv.clear();
				w.setBlockState(pos, Blocks.AIR.getDefaultState(), Block.NOTIFY_LISTENERS | Block.FORCE_STATE | Block.SKIP_DROPS);
			}
			removed.addAll(mine);
		}
		int pieces = 0;
		for (int gi = 0; gi < count; gi++) {
			// C: las piezas, con sus caras recién cortadas brillando
			BlockSet set = sets.get(gi);
			BlockPos min = mins.get(gi);
			set.normalize();
			PieceEntity pe = PieceEntity.spawn(w, set, new Vec3d(min.getX(), min.getY(), min.getZ()), min, owner);
			SPAWNED.add(pe);
			pe.markFx(now);
			pe.setFlag(PieceEntity.F_FRESH, true);
			int si = detachedSeed.getInt(gi);
			Plane pl0 = planes.get(seedPlane.getInt(si));
			Vec3d nSide = pl0.n().multiply(seedSide.getInt(si));   // normal del plano hacia el lado de la pieza
			if (suspend) {
				pe.control(pe.center().add(nSide.multiply(0.3)));
				pe.setFlag(PieceEntity.F_SUSPENDED, true);
				power.suspended.add(pe.getId());
			} else if (nSide.y >= OpeConfig.CUT_SLIDE_MIN_UP) {
				// Encima del corte: resbala por el plano, cuesta abajo y hacia donde fue el tajo (el filo se lo lleva).
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
		for (BlockPos pos : removed) w.updateNeighbors(pos, Blocks.AIR);

		// 4. Seres vivos y piezas que ya estaban sueltas (las que se están posando o ya se posaron no se tocan: sus
		//    bloques están, o están a punto de estar, en el mundo; tampoco las que vuelven a su sitio o se están cosiendo:
		//    partirlas dejaría su hueco abierto para siempre).
		int living = 0;
		for (Entity e : w.getOtherEntities(player, region.box(), e -> !(e instanceof SterbenEntity))) {
			if (e instanceof PieceEntity pe) {
				if (pe.weapon != null || pe.isRemoved() || pe.settling() || pe.mode == PieceEntity.Mode.RETURNING
					|| pe.mode == PieceEntity.Mode.SEWING || (pe.flags() & PieceEntity.F_LANDED) != 0) continue;
				if (!region.contains(pe.center())) continue;
				splitPiece(pe, planes, suspend, power);
			} else if (e instanceof LivingEntity le && le.isAlive()) {
				Box b = le.getBoundingBox();
				if (!region.contains(b.getCenter())) continue;
				List<Plane> crossing = new ArrayList<>();
				for (Plane pl : planes) if (pl.crosses(b)) crossing.add(pl);
				if (crossing.isEmpty()) continue;
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
			// el material del corte: polvo, astillas o chispas de lo que se cortó
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
		for (ServerPlayerEntity sp : PlayerLookup.around(w, c, 128)) ServerPlayNetworking.send(sp, payload);
	}

	/**
	 * Recorre el grupo de bloques unidos a la semilla sin cruzar ningún plano. Devuelve true si está anclado:
	 * llega al fondo del mundo, a un bloque irrompible, a un chunk sin cargar, a otro grupo anclado o es demasiado grande.
	 * @param roomBounded el borde de la región también corta (lo de fuera no cuenta como unión)
	 */
	private static boolean flood(ServerWorld w, List<Plane> planes, Region region, long seed, int id, Long2IntOpenHashMap comp,
			LongArrayList members, LongArrayFIFOQueue queue, boolean roomBounded, int maxBlocks) {
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
				if (roomBounded && !region.contains(nx + 0.5, ny + 0.5, nz + 0.5)) continue;
				long nk = BlockPos.asLong(nx, ny, nz);
				int c = comp.get(nk);
				if (c == id) continue;
				if (separated(planes, region, cx, cy, cz, nx, ny, nz)) continue;
				m.set(nx, ny, nz);
				if (c != 0) {
					if (cuttable(w.getBlockState(m), w, m)) return true;
					continue;
				}
				if (!w.isChunkLoaded(m)) return true;
				BlockState st = w.getBlockState(m);
				if (st.isAir() || st.getBlock() instanceof FluidBlock) continue;
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
				np.noClip = pe.noClip;   // partida mientras salía de su hueco sin chocar: las partes salen igual
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
			for (PieceEntity np : made) Takt.adopt(holderPower, holder, np);
			holderPower.dirty = true;
		}
		if (holder != null) Takt.forget(pe.getId());
		pe.discard();
	}
}
