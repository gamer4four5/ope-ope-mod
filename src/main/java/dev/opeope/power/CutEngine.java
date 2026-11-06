package dev.opeope.power;

import dev.opeope.OpeConfig;
import dev.opeope.entity.BlockSet;
import dev.opeope.entity.PieceEntity;
import dev.opeope.entity.SterbenEntity;
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
import net.minecraft.particle.DustParticleEffect;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * El corte real, en bloques. Un plano dentro del ROOM separa los bloques de un lado y del otro; cada grupo de bloques
 * que queda sin unión con el resto del mundo se convierte en una pieza que cae (igual que el grafo de soldaduras del
 * juego: lo que toca el terreno fuera del ROOM o por debajo queda anclado).
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

	public static boolean cuttable(BlockState st, ServerWorld w, BlockPos pos) {
		if (st.isAir()) return false;
		if (st.getBlock() instanceof FluidBlock) return false;
		return st.getHardness(w, pos) >= 0;
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

	public static Result cut(ServerPlayerEntity player, List<Plane> planes, Region region, boolean suspend, int noReattach, float livingDamage, boolean blocks) {
		ServerWorld w = player.getServerWorld();
		UUID owner = player.getUuid();
		if (planes.isEmpty()) return new Result(0, 0);

		// 1. Semillas: los bloques pegados a cada plano dentro de la región (y de qué lado del plano quedan).
		LongOpenHashSet seen = new LongOpenHashSet();
		LongArrayList seeds = new LongArrayList();
		IntArrayList seedPlane = new IntArrayList();
		IntArrayList seedSide = new IntArrayList();
		List<Vec3d> fx = new ArrayList<>();
		Vec3d scC = region.sampleC();
		double sr = region.sampleR();
		BlockPos.Mutable m = new BlockPos.Mutable();
		int budget = OpeConfig.CUT_MAX_SAMPLES / planes.size();
		for (int pi = 0; pi < (blocks ? planes.size() : 0); pi++) {
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
							if (s == 1 && fx.size() < 240 && w.random.nextInt(3) == 0) fx.add(new Vec3d(px, py, pz));
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
			if (anchored) {
				for (int i = 0; i < members.size(); i++) compB.put(members.getLong(i), -1);
			} else {
				detached.add(members);
				detachedSeed.add(si);
			}
		}

		// 3. Cada grupo suelto pasa a ser una pieza, que resbala por el plano del corte.
		PlayerPower power = PowerManager.get(player);
		Vec3d look = player.getRotationVector();
		int pieces = 0;
		List<BlockPos> removed = new ArrayList<>();
		for (int gi = 0; gi < detached.size(); gi++) {
			if (pieces >= OpeConfig.CUT_MAX_PIECES) break;
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
			for (BlockPos pos : mine) {
				BlockEntity be = w.getBlockEntity(pos);
				if (be instanceof Inventory inv) inv.clear();
				w.setBlockState(pos, Blocks.AIR.getDefaultState(), Block.NOTIFY_LISTENERS | Block.FORCE_STATE | Block.SKIP_DROPS);
			}
			removed.addAll(mine);
			set.normalize();
			PieceEntity pe = PieceEntity.spawn(w, set, new Vec3d(minX, minY, minZ), new BlockPos(minX, minY, minZ), owner);
			int si = detachedSeed.getInt(gi);
			Plane pl0 = planes.get(seedPlane.getInt(si));
			Vec3d nSide = pl0.n().multiply(seedSide.getInt(si));   // normal del plano hacia el lado de la pieza
			if (suspend) {
				pe.control(pe.center().add(nSide.multiply(0.3)));
				pe.setFlag(PieceEntity.F_SUSPENDED, true);
				power.suspended.add(pe.getId());
			} else if (nSide.y >= OpeConfig.CUT_SLIDE_MIN_UP) {
				// Encima del corte: resbala por el plano, cuesta abajo y hacia donde va el tajo.
				Vec3d push = look.subtract(nSide.multiply(look.dotProduct(nSide)));
				push = push.lengthSquared() < 1e-4 ? Vec3d.ZERO : push.normalize().multiply(OpeConfig.CUT_SLIDE_PUSH);
				pe.startSlide(nSide, pl0.p(), push);
			} else {
				pe.setVelocity(nSide.multiply(0.08));
			}
			pieces++;
		}
		for (BlockPos pos : removed) w.updateNeighbors(pos, Blocks.AIR);

		// 4. Seres vivos y piezas que ya estaban sueltas.
		int living = 0;
		for (Entity e : w.getOtherEntities(player, region.box(), e -> !(e instanceof SterbenEntity))) {
			if (e instanceof PieceEntity pe) {
				if (pe.weapon != null || pe.isRemoved()) continue;
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

		// 5. La línea del corte y el sonido.
		for (Vec3d p : fx) {
			w.spawnParticles(ROOM_DUST, p.x, p.y, p.z, 1, 0.05, 0.05, 0.05, 0);
			if (w.random.nextInt(4) == 0) w.spawnParticles(ParticleTypes.END_ROD, p.x, p.y, p.z, 1, 0, 0, 0, 0.005);
		}
		Vec3d at = player.getEyePos();
		w.playSound(null, at.x, at.y, at.z, SoundEvents.ENTITY_PLAYER_ATTACK_SWEEP, SoundCategory.PLAYERS, 1.0f, 1.5f);
		w.playSound(null, at.x, at.y, at.z, SoundEvents.BLOCK_AMETHYST_BLOCK_BREAK, SoundCategory.PLAYERS, 0.8f, 1.8f);
		if (pieces > 0 || living > 0) w.playSound(null, at.x, at.y, at.z, SoundEvents.BLOCK_AMETHYST_CLUSTER_BREAK, SoundCategory.PLAYERS, 1.0f, 0.6f);
		return new Result(pieces, living);
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

	/** Parte una pieza suelta por los planos. */
	public static void splitPiece(PieceEntity pe, List<Plane> planes, boolean suspend, PlayerPower power) {
		Vec3d corner = pe.corner();
		List<Plane> crossing = new ArrayList<>();
		for (Plane pl : planes) if (pl.crosses(pe.getBoundingBox())) crossing.add(pl);
		if (crossing.isEmpty()) return;
		Map<Integer, BlockSet> parts = new HashMap<>();
		for (BlockSet.Entry e : pe.blocks.entries) {
			double x = corner.x + e.x() + 0.5, y = corner.y + e.y() + 0.5, z = corner.z + e.z() + 0.5;
			int mask = 0;
			for (int i = 0; i < crossing.size(); i++) if (crossing.get(i).dist(x, y, z) > 0) mask |= 1 << i;
			parts.computeIfAbsent(mask, k -> new BlockSet()).add(e.x(), e.y(), e.z(), e.state(), e.be());
		}
		if (parts.size() <= 1) return;
		ServerWorld w = (ServerWorld) pe.getWorld();
		Vec3d vel = pe.getVelocity();
		boolean wasHeld = power.takt.contains(pe.getId());
		boolean wasSuspended = (pe.flags() & PieceEntity.F_SUSPENDED) != 0 && pe.mode == PieceEntity.Mode.CONTROLLED;
		List<Integer> newIds = new ArrayList<>();
		for (Map.Entry<Integer, BlockSet> part : parts.entrySet()) {
			BlockSet set = part.getValue();
			BlockPos off = set.normalize();
			Vec3d nc = corner.add(off.getX(), off.getY(), off.getZ());
			BlockPos origin = pe.origin != null ? pe.origin.add(off) : null;
			PieceEntity np = PieceEntity.spawn(w, set, nc, origin, pe.owner);
			Vec3d sep = Vec3d.ZERO;
			for (int i = 0; i < crossing.size(); i++) {
				sep = sep.add(crossing.get(i).n().multiply(((part.getKey() >> i) & 1) == 1 ? 0.07 : -0.07));
			}
			if (wasHeld) {
				np.control(np.center().add(sep.multiply(4)));
				newIds.add(np.getId());
			} else if (suspend || wasSuspended) {
				np.control(np.center().add(sep.multiply(4)));
				np.setFlag(PieceEntity.F_SUSPENDED, true);
				power.suspended.add(np.getId());
			} else {
				np.setVelocity(vel.add(sep));
			}
		}
		if (wasHeld) {
			power.takt.remove((Integer) pe.getId());
			power.takt.addAll(newIds);
			power.dirty = true;
		}
		pe.discard();
	}
}
