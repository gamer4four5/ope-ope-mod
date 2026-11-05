package dev.opeope.power;

import dev.opeope.OpeConfig;
import dev.opeope.entity.BlockSet;
import dev.opeope.entity.PieceEntity;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.inventory.Inventory;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * Takt: lo que señalas se levanta y se queda contigo sin mantener nada. Lo que pesa sigue pesando,
 * pero nada es demasiado pesado. Lanzar uno, todos, órbita, aparcar, posar, soltar.
 */
public final class Takt {
	private Takt() {}

	/** Seres lanzados con Takt: se hacen daño al chocar. id → ticks que le quedan para contar. */
	private static final Map<Integer, Integer> THROWN = new HashMap<>();
	private static final Map<Integer, Double> THROWN_SPEED = new HashMap<>();

	public static boolean canGrab(ServerPlayerEntity p, Entity e) {
		if (e == p || e.isSpectator() || !e.isAlive()) return false;
		if (e instanceof PlayerEntity pl && (pl.isCreative())) return false;
		if (e instanceof PieceEntity pe && pe.weapon != null) return pe.owner != null && pe.owner.equals(p.getUuid()) && pe.weapon == PieceEntity.Weapon.LANCE;
		return true;
	}

	public static List<Entity> resolve(ServerPlayerEntity p, PlayerPower pw) {
		List<Entity> out = new ArrayList<>();
		Iterator<Integer> it = pw.takt.iterator();
		boolean changed = false;
		while (it.hasNext()) {
			Entity e = p.getWorld().getEntityById(it.next());
			if (e == null || e.isRemoved() || !e.isAlive() || (e instanceof PieceEntity pe && pe.mode != PieceEntity.Mode.CONTROLLED)) {
				it.remove();
				changed = true;
				continue;
			}
			out.add(e);
		}
		if (changed) pw.dirty = true;
		return out;
	}

	public static void tap(ServerPlayerEntity p, PlayerPower pw) {
		Entity e = PowerManager.raycastEntity(p, OpeConfig.TAKT_RANGE, 0.3, ent -> canGrab(p, ent) && !pw.takt.contains(ent.getId()));
		if (e != null) {
			if (!PowerManager.inRoom(p, e.getBoundingBox().getCenter())) {
				PowerManager.noRoom(p);
				return;
			}
			grab(p, pw, e);
			return;
		}
		BlockHitResult hit = PowerManager.raycastBlock(p, OpeConfig.TAKT_RANGE);
		if (hit.getType() == HitResult.Type.BLOCK) {
			if (!PowerManager.inRoom(p, hit.getPos())) {
				PowerManager.noRoom(p);
				return;
			}
			PieceEntity chunk = ripChunk(p, hit.getBlockPos(), 1.6);
			if (chunk != null) grab(p, pw, chunk);
		}
	}

	public static void grab(ServerPlayerEntity p, PlayerPower pw, Entity e) {
		if (e instanceof PieceEntity pe && pe.weapon == PieceEntity.Weapon.LANCE) {
			// R sobre la lanza clavada: vuelve a la mano.
			pe.wState = 0;
			pe.impaled = -1;
			if (!pw.weapons.contains(pe.getId())) {
				Forge.dropWeapons(p, pw);
				pw.weapons.add(pe.getId());
				pw.weaponKind = PieceEntity.Weapon.LANCE.ordinal();
			}
			pw.dirty = true;
			return;
		}
		if (pw.takt.size() >= OpeConfig.TAKT_MAX) {
			p.sendMessage(Text.translatable("opeope.msg.takt_full"), true);
			return;
		}
		pw.takt.add(e.getId());
		if (e instanceof PieceEntity pe) {
			pe.control(pe.center().add(0, 1.2, 0));
			pe.setFlag(PieceEntity.F_SUSPENDED, false);
			pe.sewOnContact = false;
			pw.suspended.remove((Integer) pe.getId());
		}
		THROWN.remove(e.getId());
		pw.dirty = true;
		ServerWorld w = p.getServerWorld();
		Vec3d c = e.getBoundingBox().getCenter();
		w.spawnParticles(CutEngine.ROOM_DUST, c.x, c.y, c.z, 12, 0.5, 0.5, 0.5, 0);
		w.playSound(null, c.x, c.y, c.z, SoundEvents.BLOCK_AMETHYST_BLOCK_RESONATE, SoundCategory.PLAYERS, 0.8f, 1.7f);
	}

	/** Arranca un trozo de tierra con su hierba y lo que tenga encima. */
	public static PieceEntity ripChunk(ServerPlayerEntity p, BlockPos center, double r) {
		ServerWorld w = p.getServerWorld();
		int ri = (int) Math.ceil(r);
		List<BlockPos> list = new ArrayList<>();
		for (BlockPos bp : BlockPos.iterate(center.add(-ri, -ri, -ri), center.add(ri, ri, ri))) {
			double dx = bp.getX() - center.getX(), dy = bp.getY() - center.getY(), dz = bp.getZ() - center.getZ();
			if (dx * dx + dy * dy + dz * dz > r * r) continue;
			BlockState st = w.getBlockState(bp);
			if (!CutEngine.cuttable(st, w, bp)) continue;
			if (!PowerManager.inRoom(p, Vec3d.ofCenter(bp))) continue;
			list.add(bp.toImmutable());
		}
		// Lo que está encima (plantas, nieve, flores) sube con el trozo.
		List<BlockPos> extra = new ArrayList<>();
		for (BlockPos bp : list) {
			for (int k = 1; k <= 2; k++) {
				BlockPos up = bp.up(k);
				if (list.contains(up) || extra.contains(up)) break;
				BlockState st = w.getBlockState(up);
				if (st.isAir() || !st.getCollisionShape(w, up).isEmpty() || !CutEngine.cuttable(st, w, up)) break;
				extra.add(up);
			}
		}
		list.addAll(extra);
		if (list.isEmpty()) return null;
		return detach(p, list, true);
	}

	/** Convierte una lista de bloques en una pieza (quitándolos del mundo). */
	public static PieceEntity detach(ServerPlayerEntity p, List<BlockPos> list, boolean fx) {
		ServerWorld w = p.getServerWorld();
		int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
		for (BlockPos bp : list) {
			minX = Math.min(minX, bp.getX());
			minY = Math.min(minY, bp.getY());
			minZ = Math.min(minZ, bp.getZ());
		}
		BlockSet set = new BlockSet();
		BlockState sample = null;
		for (BlockPos bp : list) {
			BlockState st = w.getBlockState(bp);
			NbtCompound nbt = null;
			BlockEntity be = w.getBlockEntity(bp);
			if (be != null) nbt = be.createNbtWithIdentifyingData(w.getRegistryManager());
			set.add(bp.getX() - minX, bp.getY() - minY, bp.getZ() - minZ, st, nbt);
			if (sample == null) sample = st;
		}
		for (BlockPos bp : list) {
			BlockEntity be = w.getBlockEntity(bp);
			if (be instanceof Inventory inv) inv.clear();
			w.setBlockState(bp, Blocks.AIR.getDefaultState(), Block.NOTIFY_LISTENERS | Block.FORCE_STATE | Block.SKIP_DROPS);
		}
		for (BlockPos bp : list) w.updateNeighbors(bp, Blocks.AIR);
		set.normalize();
		PieceEntity pe = PieceEntity.spawn(w, set, new Vec3d(minX, minY, minZ), new BlockPos(minX, minY, minZ), p.getUuid());
		if (fx && sample != null) {
			Vec3d c = pe.center();
			w.spawnParticles(new net.minecraft.particle.BlockStateParticleEffect(ParticleTypes.BLOCK, sample), c.x, c.y, c.z, 30, 0.8, 0.5, 0.8, 0.1);
			w.playSound(null, c.x, c.y, c.z, sample.getSoundGroup().getBreakSound(), SoundCategory.BLOCKS, 1f, 0.6f);
		}
		return pe;
	}

	/** R R: levanta de una vez todo lo suelto alrededor del punto. */
	public static void zone(ServerPlayerEntity p, PlayerPower pw) {
		Vec3d point = PowerManager.aimPoint(p, OpeConfig.TAKT_RANGE);
		if (!PowerManager.inRoom(p, point)) {
			PowerManager.noRoom(p);
			return;
		}
		double r = OpeConfig.TAKT_ZONE_RADIUS;
		int n = 0;
		for (Entity e : p.getWorld().getOtherEntities(p, new net.minecraft.util.math.Box(point, point).expand(r),
			e -> canGrab(p, e) && !pw.takt.contains(e.getId()) && e.getPos().distanceTo(point) <= r)) {
			if (pw.takt.size() >= OpeConfig.TAKT_MAX) break;
			grab(p, pw, e);
			n++;
		}
		if (n == 0) {
			BlockHitResult hit = PowerManager.raycastBlock(p, OpeConfig.TAKT_RANGE);
			if (hit.getType() == HitResult.Type.BLOCK) {
				PieceEntity chunk = ripChunk(p, hit.getBlockPos(), 2.4);
				if (chunk != null) grab(p, pw, chunk);
			}
		}
	}

	/** R C: todo lo cortado del ROOM. */
	public static void grabCut(ServerPlayerEntity p, PlayerPower pw) {
		if (pw.room == null) {
			PowerManager.noRoom(p);
			return;
		}
		Room room = pw.room;
		net.minecraft.util.math.Box box = new net.minecraft.util.math.Box(room.center, room.center).expand(room.radius);
		for (PieceEntity pe : p.getWorld().getEntitiesByClass(PieceEntity.class, box, e -> e.weapon == null && room.contains(e.center()))) {
			if (pw.takt.size() >= OpeConfig.TAKT_MAX) break;
			if (!pw.takt.contains(pe.getId())) grab(p, pw, pe);
		}
	}

	public static void order(ServerPlayerEntity p, PlayerPower pw, int order) {
		switch (order) {
			case Actions.ORDER_ORBIT -> {
				pw.taktMode = pw.taktMode == PlayerPower.TaktMode.ORBIT ? PlayerPower.TaktMode.HAND : PlayerPower.TaktMode.ORBIT;
				p.sendMessage(Text.translatable(pw.taktMode == PlayerPower.TaktMode.ORBIT ? "opeope.msg.orbit" : "opeope.msg.hand"), true);
			}
			case Actions.ORDER_PARK -> {
				pw.taktMode = PlayerPower.TaktMode.PARK;
				pw.parked.clear();
				for (Entity e : resolve(p, pw)) pw.parked.put(e.getId(), e.getBoundingBox().getCenter());
				p.sendMessage(Text.translatable("opeope.msg.park"), true);
			}
			case Actions.ORDER_SETDOWN -> dropAll(p, pw, true);
			case Actions.ORDER_DROP -> dropAll(p, pw, false);
			case Actions.ORDER_ZONE -> zone(p, pw);
			case Actions.ORDER_CUT -> grabCut(p, pw);
			case Actions.ORDER_GATHER -> Union.gather(p, pw);
			case Actions.ORDER_UNITE -> Union.uniteAll(p, pw);
			case Actions.ORDER_THROW_ALL -> throwAll(p, pw);
			default -> {
				if (order >= Actions.ORDER_FORGE && order < Actions.ORDER_FORGE + 5) Forge.forge(p, pw, order - Actions.ORDER_FORGE);
			}
		}
		pw.dirty = true;
	}

	public static void scroll(PlayerPower pw, int dir) {
		pw.taktDist = MathHelper.clamp(pw.taktDist + dir * 1.0, 2.0, 40.0);
		pw.dirty = true;
	}

	/** Soltar todo. gently: posar (baja despacio al suelo). */
	public static void dropAll(ServerPlayerEntity p, PlayerPower pw, boolean gently) {
		for (Entity e : resolve(p, pw)) {
			if (e instanceof PieceEntity pe) {
				pe.release(Vec3d.ZERO, false);
				pe.slowFall = gently;
			} else {
				e.setVelocity(Vec3d.ZERO);
				e.velocityModified = true;
				if (gently && e instanceof LivingEntity le) le.addStatusEffect(new StatusEffectInstance(StatusEffects.SLOW_FALLING, 60, 0));
			}
		}
		pw.takt.clear();
		pw.parked.clear();
		if (pw.taktMode == PlayerPower.TaktMode.PARK) pw.taktMode = PlayerPower.TaktMode.HAND;
		pw.dirty = true;
	}

	public static void throwOne(ServerPlayerEntity p, PlayerPower pw) {
		List<Entity> list = resolve(p, pw);
		if (list.isEmpty()) return;
		Vec3d eye = p.getEyePos();
		Vec3d look = p.getRotationVector();
		Entity best = null;
		double bestDot = -2;
		for (Entity e : list) {
			Vec3d to = e.getBoundingBox().getCenter().subtract(eye).normalize();
			double d = to.dotProduct(look);
			if (d > bestDot) {
				bestDot = d;
				best = e;
			}
		}
		Vec3d aim = PowerManager.aimPoint(p, 200);
		launch(p, pw, best, aim);
		p.getWorld().playSound(null, p.getX(), p.getY(), p.getZ(), SoundEvents.ENTITY_ENDER_PEARL_THROW, SoundCategory.PLAYERS, 1f, 0.6f);
	}

	public static void throwAll(ServerPlayerEntity p, PlayerPower pw) {
		List<Entity> list = resolve(p, pw);
		if (list.isEmpty()) return;
		Vec3d aim = PowerManager.aimPoint(p, 200);
		for (Entity e : list) launch(p, pw, e, aim);
		p.getWorld().playSound(null, p.getX(), p.getY(), p.getZ(), SoundEvents.ENTITY_PLAYER_ATTACK_SWEEP, SoundCategory.PLAYERS, 1.5f, 0.5f);
		p.getWorld().playSound(null, p.getX(), p.getY(), p.getZ(), SoundEvents.ENTITY_ENDER_PEARL_THROW, SoundCategory.PLAYERS, 1.5f, 0.4f);
	}

	private static void launch(ServerPlayerEntity p, PlayerPower pw, Entity e, Vec3d aim) {
		pw.takt.remove((Integer) e.getId());
		pw.parked.remove(e.getId());
		Vec3d dir = aim.subtract(e.getBoundingBox().getCenter());
		if (dir.lengthSquared() < 1e-3) dir = p.getRotationVector();
		dir = dir.normalize().multiply(OpeConfig.TAKT_LAUNCH);
		if (e instanceof PieceEntity pe) {
			pe.owner = p.getUuid();
			pe.release(dir, true);
		} else {
			e.setVelocity(dir);
			e.velocityModified = true;
			if (e instanceof LivingEntity) {
				THROWN.put(e.getId(), 40);
				THROWN_SPEED.put(e.getId(), dir.length());
			}
		}
		pw.dirty = true;
	}

	public static void tick(ServerPlayerEntity p, PlayerPower pw) {
		if (pw.takt.isEmpty()) return;
		List<Entity> list = resolve(p, pw);
		int n = list.size();
		if (n == 0) return;
		Vec3d eye = p.getEyePos();
		Vec3d look = p.getRotationVector();
		Vec3d right = look.crossProduct(new Vec3d(0, 1, 0));
		if (right.lengthSquared() < 1e-4) right = new Vec3d(1, 0, 0);
		right = right.normalize();
		Vec3d up = right.crossProduct(look).normalize();
		double biggest = 0;
		for (Entity e : list) biggest = Math.max(biggest, e.getBoundingBox().getAverageSideLength());
		double time = p.age;
		for (int i = 0; i < n; i++) {
			Entity e = list.get(i);
			Vec3d target;
			if (pw.taktMode == PlayerPower.TaktMode.PARK) {
				target = pw.parked.getOrDefault(e.getId(), e.getBoundingBox().getCenter());
				pw.parked.putIfAbsent(e.getId(), target);
			} else if (pw.taktMode == PlayerPower.TaktMode.ORBIT) {
				double ang = time * 0.07 + i * Math.PI * 2 / n;
				double r = pw.taktDist + biggest * 0.5;
				target = p.getPos().add(Math.cos(ang) * r, 1.3 + Math.sin(time * 0.05 + i) * 0.4, Math.sin(ang) * r);
			} else {
				double d = pw.taktDist + biggest * 0.6;
				Vec3d anchor = eye.add(look.multiply(d)).add(up.multiply(0.6));
				if (i == 0) {
					target = anchor;
				} else {
					double ang = (i - 1) * Math.PI * 2 / Math.max(1, n - 1) + time * 0.02;
					double ring = 1.0 + biggest * 0.7 + (n > 9 ? 0.8 : 0);
					target = anchor.add(right.multiply(Math.cos(ang) * ring)).add(up.multiply(Math.sin(ang) * ring));
				}
			}
			if (e instanceof PieceEntity pe) {
				pe.target = target;
			} else {
				Vec3d c = e.getBoundingBox().getCenter();
				Vec3d v = target.subtract(c).multiply(0.3);
				if (v.length() > 1.6) v = v.normalize().multiply(1.6);
				e.setVelocity(v.add(0, 0.08, 0));
				e.fallDistance = 0;
				e.velocityModified = true;
			}
		}
	}

	/** Daño de los seres lanzados al chocar. */
	public static void tickThrown(ServerWorld w) {
		if (THROWN.isEmpty()) return;
		Iterator<Map.Entry<Integer, Integer>> it = THROWN.entrySet().iterator();
		while (it.hasNext()) {
			Map.Entry<Integer, Integer> en = it.next();
			Entity e = w.getEntityById(en.getKey());
			if (e == null) continue;
			if (!(e instanceof LivingEntity le) || !le.isAlive()) {
				it.remove();
				THROWN_SPEED.remove(en.getKey());
				continue;
			}
			int left = en.getValue() - 1;
			double speed = THROWN_SPEED.getOrDefault(en.getKey(), 0.0);
			if ((le.horizontalCollision || le.isOnGround()) && left < 38) {
				float dmg = (float) Math.min(20, speed * 6);
				if (dmg > 1) le.damage(w.getDamageSources().flyIntoWall(), dmg);
				it.remove();
				THROWN_SPEED.remove(en.getKey());
				continue;
			}
			if (left <= 0) {
				it.remove();
				THROWN_SPEED.remove(en.getKey());
			} else {
				en.setValue(left);
			}
		}
	}
}
