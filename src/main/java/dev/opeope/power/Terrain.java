package dev.opeope.power;

import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.entity.Entity;
import net.minecraft.particle.BlockStateParticleEffect;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;

/**
 * El suelo del ROOM: Tab Q pilar (mantener: sigue subiendo y te sube), Tab E muralla (sigue tu mirada como una ola),
 * Tab R sima (una zanja que sigue tu mirada) y Tab F meseta (alza el suelo con todo lo que tiene encima).
 */
public final class Terrain {
	private Terrain() {}

	public static void floor(ServerPlayerEntity p, PlayerPower pw, int arg) {
		if (arg == 0) {
			pw.floorOp = null;
			return;
		}
		PlayerPower.FloorOp op = PlayerPower.FloorOp.values()[Math.max(0, Math.min(3, arg - 1))];
		BlockHitResult hit = PowerManager.raycastBlock(p, 200);
		if (hit.getType() != HitResult.Type.BLOCK || !PowerManager.inRoom(p, hit.getPos())) {
			PowerManager.noRoom(p);
			return;
		}
		pw.floorOp = op;
		pw.floorTicks = 0;
		pw.floorCount = 0;
		switch (op) {
			case PILLAR -> pw.pillarBase = hit.getBlockPos();
			case WALL, CHASM -> {
				Vec3d look = p.getRotationVector();
				Vec3d flat = new Vec3d(look.x, 0, look.z);
				if (flat.lengthSquared() < 1e-4) flat = new Vec3d(0, 0, 1);
				pw.floorHead = p.getPos().add(flat.normalize().multiply(2.5));
			}
			case PLATEAU -> {
				pw.pillarBase = hit.getBlockPos();
				raisePlateau(p, pw);
			}
		}
	}

	public static void tick(ServerPlayerEntity p, PlayerPower pw) {
		if (pw.floorOp == null) return;
		pw.floorTicks++;
		switch (pw.floorOp) {
			case PILLAR -> {
				if (pw.floorTicks % 2 == 0) growPillar(p, pw);
			}
			case WALL -> {
				if (pw.floorTicks % 2 == 0) advance(p, pw, false);
			}
			case CHASM -> {
				if (pw.floorTicks % 2 == 0) advance(p, pw, true);
			}
			case PLATEAU -> {
				if (pw.floorTicks % 4 == 0 && pw.floorCount < 12) raisePlateau(p, pw);
			}
		}
	}

	private static boolean free(BlockState s) {
		return s.isAir() || s.isReplaceable();
	}

	private static void growPillar(ServerPlayerEntity p, PlayerPower pw) {
		if (pw.floorCount >= 40) return;
		ServerWorld w = p.getServerWorld();
		BlockPos base = pw.pillarBase;
		BlockState mat = w.getBlockState(base);
		if (mat.isAir() || mat.hasBlockEntity()) mat = Blocks.STONE.getDefaultState();
		int y = base.getY() + 1 + pw.floorCount;
		if (!PowerManager.inRoom(p, new Vec3d(base.getX() + 0.5, y, base.getZ() + 0.5))) return;
		for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) {
			BlockPos bp = new BlockPos(base.getX() + dx, y, base.getZ() + dz);
			if (free(w.getBlockState(bp))) w.setBlockState(bp, mat, Block.NOTIFY_ALL);
		}
		Box top = new Box(base.getX() - 1, y - 0.5, base.getZ() - 1, base.getX() + 2, y + 2, base.getZ() + 2);
		for (Entity e : w.getOtherEntities(null, top, e -> !e.isSpectator())) {
			if (e.getY() < y + 1) e.requestTeleport(e.getX(), y + 1.0, e.getZ());
			e.fallDistance = 0;
		}
		w.spawnParticles(new BlockStateParticleEffect(ParticleTypes.BLOCK, mat), base.getX() + 0.5, y, base.getZ() + 0.5, 12, 1, 0.2, 1, 0.1);
		if (pw.floorCount % 3 == 0) w.playSound(null, base.getX(), y, base.getZ(), mat.getSoundGroup().getPlaceSound(), SoundCategory.BLOCKS, 1f, 0.6f);
		pw.floorCount++;
	}

	/** El suelo en una columna: el primer bloque sólido bajando desde un poco más arriba. */
	private static int surface(ServerWorld w, int x, int z, int fromY) {
		BlockPos.Mutable m = new BlockPos.Mutable(x, fromY + 6, z);
		for (int i = 0; i < 24; i++) {
			BlockState s = w.getBlockState(m);
			if (!s.isAir() && !s.isReplaceable()) return m.getY();
			m.move(0, -1, 0);
		}
		return fromY - 1;
	}

	private static void advance(ServerPlayerEntity p, PlayerPower pw, boolean dig) {
		if (pw.floorCount >= 48 || pw.floorHead == null) return;
		ServerWorld w = p.getServerWorld();
		Vec3d aim = PowerManager.aimPoint(p, 120);
		Vec3d to = new Vec3d(aim.x - pw.floorHead.x, 0, aim.z - pw.floorHead.z);
		if (to.lengthSquared() < 1.0) return;
		Vec3d dir = to.normalize();
		pw.floorHead = pw.floorHead.add(dir);
		Vec3d side = new Vec3d(-dir.z, 0, dir.x);
		int baseY = (int) Math.floor(pw.floorHead.y);
		if (!PowerManager.inRoom(p, pw.floorHead)) return;
		BlockState sample = null;
		for (int k = -1; k <= 1; k++) {
			Vec3d c = pw.floorHead.add(side.multiply(k));
			int x = (int) Math.floor(c.x), z = (int) Math.floor(c.z);
			int sy = surface(w, x, z, baseY);
			if (dig) {
				for (int y = sy; y > sy - 5; y--) {
					BlockPos bp = new BlockPos(x, y, z);
					BlockState s = w.getBlockState(bp);
					if (s.isAir() || s.hasBlockEntity() || s.getHardness(w, bp) < 0) continue;
					if (sample == null) sample = s;
					w.setBlockState(bp, Blocks.AIR.getDefaultState(), Block.NOTIFY_ALL);
				}
			} else if (k == 0) {
				BlockState mat = w.getBlockState(new BlockPos(x, sy, z));
				if (mat.isAir() || mat.hasBlockEntity()) mat = Blocks.STONE.getDefaultState();
				sample = mat;
				for (int y = sy + 1; y <= sy + 4; y++) {
					BlockPos bp = new BlockPos(x, y, z);
					if (free(w.getBlockState(bp))) w.setBlockState(bp, mat, Block.NOTIFY_ALL);
				}
				for (Entity e : w.getOtherEntities(p, new Box(x, sy + 1, z, x + 1, sy + 5, z + 1))) {
					e.requestTeleport(e.getX(), sy + 5, e.getZ());
				}
			}
			pw.floorHead = new Vec3d(pw.floorHead.x, sy + 1, pw.floorHead.z);
		}
		if (sample != null) {
			w.spawnParticles(new BlockStateParticleEffect(ParticleTypes.BLOCK, sample), pw.floorHead.x, pw.floorHead.y + 1, pw.floorHead.z, 20, 0.8, 0.8, 0.8, 0.15);
			w.playSound(null, pw.floorHead.x, pw.floorHead.y, pw.floorHead.z, dig ? SoundEvents.BLOCK_ROOTED_DIRT_BREAK : SoundEvents.BLOCK_STONE_PLACE, SoundCategory.BLOCKS, 1f, 0.6f);
		}
		pw.floorCount++;
	}

	private static void raisePlateau(ServerPlayerEntity p, PlayerPower pw) {
		ServerWorld w = p.getServerWorld();
		BlockPos c = pw.pillarBase;
		int r = 5;
		BlockState sample = null;
		for (int dx = -r; dx <= r; dx++) for (int dz = -r; dz <= r; dz++) {
			if (dx * dx + dz * dz > r * r) continue;
			int x = c.getX() + dx, z = c.getZ() + dz;
			if (!PowerManager.inRoom(p, new Vec3d(x + 0.5, c.getY(), z + 0.5))) continue;
			int sy = surface(w, x, z, c.getY() + pw.floorCount);
			int top = sy;
			for (int y = sy + 1; y < sy + 30; y++) if (!w.getBlockState(new BlockPos(x, y, z)).isAir()) top = y;
			if (w.isOutOfHeightLimit(top + 1)) continue;
			boolean hasBe = false;
			for (int y = sy - 3; y <= top; y++) if (w.getBlockState(new BlockPos(x, y, z)).hasBlockEntity()) hasBe = true;
			if (hasBe) continue;
			for (int y = top; y >= sy - 3; y--) {
				BlockState s = w.getBlockState(new BlockPos(x, y, z));
				if (s.getHardness(w, new BlockPos(x, y, z)) < 0) break;
				w.setBlockState(new BlockPos(x, y + 1, z), s, Block.NOTIFY_LISTENERS | Block.FORCE_STATE);
				if (sample == null && !s.isAir()) sample = s;
			}
		}
		Box box = new Box(c.getX() - r, c.getY() - 4, c.getZ() - r, c.getX() + r + 1, c.getY() + 30, c.getZ() + r + 1);
		for (Entity e : w.getOtherEntities(null, box, e -> !e.isSpectator())) {
			e.requestTeleport(e.getX(), e.getY() + 1.0, e.getZ());
			e.fallDistance = 0;
		}
		if (sample != null) {
			w.spawnParticles(new BlockStateParticleEffect(ParticleTypes.BLOCK, sample), c.getX() + 0.5, c.getY() + 1, c.getZ() + 0.5, 60, r, 0.3, r, 0.1);
			w.playSound(null, c.getX(), c.getY(), c.getZ(), SoundEvents.BLOCK_GRAVEL_BREAK, SoundCategory.BLOCKS, 1.5f, 0.5f);
		}
		pw.floorCount++;
	}
}
