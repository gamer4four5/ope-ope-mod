package dev.opeope.power;

import dev.opeope.entity.BlockSet;
import dev.opeope.entity.PieceEntity;
import dev.opeope.net.Payloads;
import dev.opeope.registry.ModDamage;
import dev.opeope.registry.ModEffects;
import dev.opeope.registry.ModItems;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.FluidBlock;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.entity.Entity;
import net.minecraft.entity.ExperienceOrbEntity;
import net.minecraft.entity.FallingBlockEntity;
import net.minecraft.entity.ItemEntity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.entity.mob.HostileEntity;
import net.minecraft.entity.projectile.ProjectileEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.network.packet.s2c.play.EntityVelocityUpdateS2CPacket;
import net.minecraft.particle.BlockStateParticleEffect;
import net.minecraft.particle.DustParticleEffect;
import net.minecraft.particle.ParticleEffect;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.recipe.AbstractCookingRecipe;
import net.minecraft.recipe.RecipeEntry;
import net.minecraft.recipe.RecipeType;
import net.minecraft.recipe.input.SingleStackRecipeInput;
import net.minecraft.registry.Registries;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.tag.BlockTags;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvent;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;
import org.joml.Vector3f;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Queue;
import java.util.Set;
import java.util.UUID;

/**
 * Motor independiente de la Fruta Gravity (Zushi Zushi no Mi — Almirante Fujitora).
 * <p>
 * 100 % independiente del ROOM de Trafalgar Law: Fujitora nunca despliega una cúpula ROOM, sino que proyecta
 * vectores direccionales de gravedad (+G aplastante, -G antigravedad, repulsión, atracción), levita rocas/escombros
 * libremente en el mundo, vuela únicamente surfeando sobre su plataforma de piedra e invoca meteoritos desde el espacio.
 */
public final class GravityPowers {
	private GravityPowers() {}

	public static final DustParticleEffect GRAVITY_DUST = new DustParticleEffect(new Vector3f(0.68f, 0.22f, 1.0f), 1.35f);
	public static final DustParticleEffect ABYSS_DUST = new DustParticleEffect(new Vector3f(0.18f, 0.04f, 0.34f), 1.65f);
	public static final DustParticleEffect ASTRAL_DUST = new DustParticleEffect(new Vector3f(0.92f, 0.72f, 1.0f), 1.1f);

	public static final String[] VECTOR_NAMES = {
		"⬇ Aplastar (+G)",
		"⬆ Antigravedad (-G)",
		"➡ Repulsión",
		"🌀 Vórtice (Atraer)"
	};

	public static final String[] METEOR_NAMES = {
		"Meteorito (12 m)",
		"Asteroide (24 m)",
		"Coloso de Dressrosa (40 m)",
		"Cataclismo (60 m)",
		"EXTINCIÓN PLANETARIA (85 m)"
	};
	public static final float[] METEOR_SCALES = {1.15f, 2.25f, 3.70f, 5.50f, 7.80f};
	public static final float[] METEOR_CRATERS = {10.5f, 18.5f, 28.0f, 42.0f, 58.0f};
	public static final double[] METEOR_HEIGHTS = {145.0, 175.0, 205.0, 235.0, 265.0};
	public static final double[] METEOR_SPEEDS = {2.35, 2.55, 2.75, 2.90, 3.05};

	private record ActiveMeteor(RegistryKey<World> dim, int entityId, UUID ownerId, Vec3d target, Vec3d portalPos, int tier, int age) {}

	/** Cráter progresivo por ondas concéntricas: abre cráteres colosales de hasta 116 m de ancho sin congelar el servidor. */
	private static final class PendingCrater {
		final RegistryKey<World> dim;
		final UUID ownerId;
		final BlockPos center;
		final BlockPos safeCenter;
		final double maxR;
		final int tier;
		double curR;
		int oresRewarded;

		PendingCrater(RegistryKey<World> dim, UUID ownerId, BlockPos center, BlockPos safeCenter, double maxR, int tier) {
			this.dim = dim;
			this.ownerId = ownerId;
			this.center = center;
			this.safeCenter = safeCenter;
			this.maxR = maxR;
			this.tier = tier;
			this.curR = 0.0;
		}
	}

	/** Pozo localizado y temporal de gravedad (no es un ROOM esférico): aplasta, eleva o atrae durante unos segundos. */
	private static final class ActiveZone {
		final RegistryKey<World> dim;
		final UUID ownerId;
		final Vec3d center;
		final double radius;
		final int vector; // 0 = +G, 1 = -G, 3 = atraer
		int ticksLeft;

		ActiveZone(RegistryKey<World> dim, UUID ownerId, Vec3d center, double radius, int vector, int ticksLeft) {
			this.dim = dim;
			this.ownerId = ownerId;
			this.center = center;
			this.radius = radius;
			this.vector = vector;
			this.ticksLeft = ticksLeft;
		}
	}

	private static final List<ActiveMeteor> METEORS = new ArrayList<>();
	private static final List<PendingCrater> CRATERS = new ArrayList<>();
	private static final List<ActiveZone> ZONES = new ArrayList<>();
	private static List<Block> cachedRareModOres = null;

	public static void clearAll() {
		METEORS.clear();
		CRATERS.clear();
		ZONES.clear();
	}

	public static void sendFx(ServerWorld w, int kind, int userId, Vec3d a, Vec3d b, float radius, float power, int ticks) {
		Payloads.GravityFx fx = new Payloads.GravityFx(kind, userId, a.x, a.y, a.z, b.x, b.y, b.z, radius, power, ticks);
		for (ServerPlayerEntity sp : w.getPlayers()) {
			if (sp.squaredDistanceTo(a) <= 420 * 420 || sp.squaredDistanceTo(b) <= 420 * 420) {
				ServerPlayNetworking.send(sp, fx);
			}
		}
	}

	public static void equipYakuzaKasenSafely(ServerPlayerEntity p) {
		if (ModItems.YAKUZA_KASEN == null || p.getMainHandStack().isOf(ModItems.YAKUZA_KASEN)) return;
		if (p.getOffHandStack().isOf(ModItems.YAKUZA_KASEN)) return;
		for (int i = 0; i < p.getInventory().size(); i++) {
			ItemStack st = p.getInventory().getStack(i);
			if (st.isOf(ModItems.YAKUZA_KASEN)) {
				if (i < 9) {
					p.getInventory().selectedSlot = i;
				} else if (p.getMainHandStack().isEmpty()) {
					p.setStackInHand(Hand.MAIN_HAND, st.copy());
					p.getInventory().setStack(i, ItemStack.EMPTY);
				}
				return;
			}
		}
		if (p.getMainHandStack().isEmpty()) {
			p.setStackInHand(Hand.MAIN_HAND, new ItemStack(ModItems.YAKUZA_KASEN));
		} else {
			p.getInventory().offerOrDrop(new ItemStack(ModItems.YAKUZA_KASEN));
		}
	}

	// ------------------------------------------------------------------ Tecla G: Gravedad Direccional / Carga y Vectores (SIN ROOM)

	public static void cycleVector(ServerPlayerEntity p, PlayerPower pw, int dir) {
		pw.gravityVector = Math.floorMod(pw.gravityVector + (dir >= 0 ? 1 : -1), 4);
		pw.dirty = true;
		playSound(p.getServerWorld(), p.getPos(), SoundEvents.BLOCK_AMETHYST_BLOCK_CHIME, 0.9f, 0.7f + pw.gravityVector * 0.22f);
		p.sendMessage(Text.literal("§5✦ Vector de Gravedad: §f" + VECTOR_NAMES[pw.gravityVector] + " §7(Shift+G o G+Rueda para cambiar)"), true);
	}

	public static void startGravityCharge(ServerPlayerEntity p, PlayerPower pw) {
		if (p.isSneaking()) {
			cycleVector(p, pw, 1);
			return;
		}
		equipYakuzaKasenSafely(p);
		pw.gravityCharging = true;
		pw.gravityChargeTicks = 0;
		pw.gravityChargePos = PowerManager.aimPoint(p, 52.0);
		pw.dirty = true;
		playSound(p.getServerWorld(), p.getPos(), SoundEvents.BLOCK_RESPAWN_ANCHOR_CHARGE, 0.7f, 0.65f);
	}

	public static void releaseGravityCharge(ServerPlayerEntity p, PlayerPower pw) {
		if (!pw.gravityCharging) return;
		int ticks = pw.gravityChargeTicks;
		Vec3d pos = pw.gravityChargePos != null ? pw.gravityChargePos : PowerManager.aimPoint(p, 52.0);
		pw.gravityCharging = false;
		pw.gravityChargeTicks = 0;
		pw.dirty = true;

		ServerWorld w = p.getServerWorld();
		float power = MathHelper.clamp(0.45f + ticks / 35.0f, 0.45f, 2.2f);
		double rad = 4.5 + power * 2.4;

		switch (pw.gravityVector) {
			case 0 -> {
				// ⬇ Aplastar (+G): Envaine "Kachin" + cascada de anillos 3D + pozo de presión + cráter si se cargó
				ZONES.add(new ActiveZone(w.getRegistryKey(), p.getUuid(), pos, rad, 0, 100));
				sendFx(w, Payloads.GravityFx.KIND_PRESS_COLUMN, p.getId(), pos.add(0, 14, 0), pos, (float) rad, power, 34);
				int crushedMobs = 0;
				for (LivingEntity le : w.getEntitiesByClass(LivingEntity.class, new Box(pos, pos).expand(rad), e -> e != p && e.isAlive())) {
					le.damage(ModDamage.of(w, ModDamage.GAMMA, p), (14f + power * 18f) * (1f + pw.masteryTier() * 0.15f));
					le.addVelocity(0, -2.2 * power, 0);
					le.velocityModified = true;
					le.addStatusEffect(new StatusEffectInstance(ModEffects.STUN, (int) (35 * power), 0, false, true));
					crushedMobs++;
				}
				if (ticks >= 10) {
					carveGravityCrater(p, pw, w, pos, Math.min(5.2, 2.6 + power * 1.4), Math.min(4, 1 + (int) power));
				}
				playSound(w, pos, SoundEvents.BLOCK_ANVIL_LAND, 1.3f, 0.48f);
				playSound(w, p.getPos(), SoundEvents.ITEM_TRIDENT_HIT_GROUND, 0.9f, 1.6f); // sonido metálico de envaine "Kachin"
				p.sendMessage(Text.literal("§5⬇ Gravito (+G ×" + Math.round(power * 100) + "): §f¡Presión liberada! (" + crushedMobs + " aplastados)"), true);
			}
			case 1 -> {
				// ⬆ Antigravedad (-G): eleva enemigos y escombros al cielo
				ZONES.add(new ActiveZone(w.getRegistryKey(), p.getUuid(), pos, rad, 1, 90));
				sendFx(w, Payloads.GravityFx.KIND_LIFT_PULSE, p.getId(), pos, pos.add(0, 12, 0), (float) rad, power, 32);
				int lifted = 0;
				for (Entity e : w.getOtherEntities(p, new Box(pos, pos).expand(rad), ent -> ent.isAlive() && !ent.isSpectator() && ent.getId() != pw.gravityMountId)) {
					e.setVelocity(e.getVelocity().x * 0.4, 0.85 + power * 0.55, e.getVelocity().z * 0.4);
					e.velocityModified = true;
					lifted++;
				}
				playSound(w, pos, SoundEvents.BLOCK_BEACON_ACTIVATE, 1.2f, 1.35f);
				p.sendMessage(Text.literal("§5⬆ Antigravedad (-G): §f¡" + lifted + " objetivos elevados hacia el cielo!"), true);
			}
			case 2 -> {
				// ➡ Repulsión brutal en onda 3D
				Vec3d origin = p.getPos().add(0, 1.0, 0);
				sendFx(w, Payloads.GravityFx.KIND_SHIELD_BURST, p.getId(), origin, pos, (float) (rad + 2.0), power, 22);
				int pushed = 0;
				for (Entity e : w.getOtherEntities(p, p.getBoundingBox().expand(rad + 2.5), ent -> ent.isAlive() && !ent.isSpectator() && ent.getId() != pw.gravityMountId)) {
					Vec3d away = e.getPos().subtract(p.getPos());
					if (away.lengthSquared() < 0.05) away = p.getRotationVector();
					Vec3d dir = away.normalize();
					e.setVelocity(dir.x * (2.2 + power), 0.55 + power * 0.25, dir.z * (2.2 + power));
					e.velocityModified = true;
					if (e instanceof LivingEntity le) {
						le.damage(ModDamage.of(w, ModDamage.GAMMA, p), 12f + power * 10f);
					}
					pushed++;
				}
				playSound(w, origin, SoundEvents.ENTITY_WARDEN_SONIC_BOOM, 1.1f, 0.9f);
				p.sendMessage(Text.literal("§5➡ Onda de Repulsión Gravitatoria: §f¡" + pushed + " repelidos!"), true);
			}
			default -> {
				// 🌀 Vórtice de Atracción localizado
				ZONES.add(new ActiveZone(w.getRegistryKey(), p.getUuid(), pos, rad + 2.0, 3, 100));
				sendFx(w, Payloads.GravityFx.KIND_PULL_VORTEX, p.getId(), pos, pos, (float) (rad + 2.0), power, 48);
				playSound(w, pos, SoundEvents.BLOCK_RESPAWN_ANCHOR_DEPLETE.value(), 1.2f, 0.6f);
				p.sendMessage(Text.literal("§5🌀 Vórtice Gravitatorio desplegado: §f¡Atrayendo todo hacia el centro!"), true);
			}
		}
	}

	public static void gravityPullVortex(ServerPlayerEntity p, PlayerPower pw) {
		ServerWorld w = p.getServerWorld();
		Vec3d pos = PowerManager.aimPoint(p, 48.0);
		ZONES.add(new ActiveZone(w.getRegistryKey(), p.getUuid(), pos, 9.5, 3, 110));
		sendFx(w, Payloads.GravityFx.KIND_PULL_VORTEX, p.getId(), pos, pos, 9.5f, 1.6f, 55);
		playSound(w, pos, SoundEvents.BLOCK_RESPAWN_ANCHOR_DEPLETE.value(), 1.3f, 0.55f);
		p.sendMessage(Text.literal("§5🌀 Singularidad Gravitatoria: ¡Atrayendo enemigos y objetos!"), true);
	}

	// ------------------------------------------------------------------ Tecla R: Levitación Libre de Enemigos y Rocas (SIN ROOM)

	public static void levitateTap(ServerPlayerEntity p, PlayerPower pw) {
		ServerWorld w = p.getServerWorld();
		// Limpia entidades muertas
		cleanHeld(w, pw);
		if (pw.gravityHeld.size() >= 12) {
			p.sendMessage(Text.literal("§5Ya levitas 12 masas gravitatorias (Clic Izq. para lanzar · Clic Der. para aplastar)"), true);
			return;
		}

		// 1) Si apunta a una entidad viva o pieza suelta hasta 55 bloques (SIN ROOM!)
		Entity target = PowerManager.raycastEntity(p, 55.0, 0.45,
			e -> e != p && e.isAlive() && !e.isSpectator() && e.getId() != pw.gravityMountId && !pw.gravityHeld.contains(e.getId()));
		if (target != null) {
			pw.gravityHeld.add(target.getId());
			target.setNoGravity(true);
			target.setVelocity(0, 0.45, 0);
			target.velocityModified = true;
			if (target instanceof PieceEntity pe) {
				pe.control(target.getBoundingBox().getCenter().add(0, 2.0, 0));
				pe.setFlag(PieceEntity.F_HELD, true);
			}
			pw.gravityHoldDist = MathHelper.clamp(p.getEyePos().distanceTo(target.getPos()), 5.0, 36.0);
			pw.dirty = true;
			sendFx(w, Payloads.GravityFx.KIND_LIFT_PULSE, p.getId(), target.getPos(), target.getPos().add(0, 4, 0), 2.2f, 1.0f, 18);
			playSound(w, target.getPos(), SoundEvents.BLOCK_RESPAWN_ANCHOR_CHARGE, 0.8f, 1.15f);
			p.sendMessage(Text.literal("§5⬆ Gravedad invertida sobre §f" + target.getDisplayName().getString() + " §7(Clic Izq. lanzar · Clic Der. aplastar)"), true);
			return;
		}

		// 2) Si apunta a cualquier bloque/suelo/pared (hasta 55 bloques, SIN ROOM!): arranca un trozo real 3x3x3
		BlockHitResult hit = PowerManager.raycastBlock(p, 55.0);
		if (hit.getType() != HitResult.Type.BLOCK) {
			p.sendMessage(Text.literal("§7Apunta a un enemigo o bloque del terreno para levitarlo con Gravedad"), true);
			return;
		}

		BlockPos center = hit.getBlockPos();
		BlockSet set = new BlockSet();
		BlockPos feetBelow = p.getBlockPos().down();
		int count = 0;
		for (int dx = -1; dx <= 1; dx++) {
			for (int dy = -1; dy <= 1; dy++) {
				for (int dz = -1; dz <= 1; dz++) {
					BlockPos bp = center.add(dx, dy, dz);
					if (bp.getY() < w.getBottomY() + 1 || bp.getY() >= w.getTopY()) continue;
					if (p.isOnGround() && Math.abs(bp.getX() - feetBelow.getX()) <= 1 && Math.abs(bp.getZ() - feetBelow.getZ()) <= 1 && bp.getY() <= feetBelow.getY()) continue;
					BlockState st = w.getBlockState(bp);
					if (st.isAir() || st.getBlock() instanceof FluidBlock || st.hasBlockEntity() || st.getHardness(w, bp) < 0) continue;
					set.add(dx + 1, dy + 1, dz + 1, st, null);
					w.setBlockState(bp, Blocks.AIR.getDefaultState(), Block.NOTIFY_LISTENERS);
					count++;
				}
			}
		}
		if (count == 0) return;
		set.normalize();
		Vec3d spawnPos = Vec3d.ofCenter(center);
		PieceEntity pe = PieceEntity.spawn(w, set, spawnPos.subtract(set.sx / 2.0, set.sy / 2.0, set.sz / 2.0), center, p.getUuid());
		pe.control(spawnPos.add(0, 3.2, 0));
		pe.setFlag(PieceEntity.F_HELD, true);
		pe.setFlag(PieceEntity.F_SEAM, true);

		pw.gravityHeld.add(pe.getId());
		pw.gravityHoldDist = MathHelper.clamp(p.getEyePos().distanceTo(spawnPos), 6.0, 32.0);
		pw.dirty = true;
		sendFx(w, Payloads.GravityFx.KIND_LIFT_PULSE, p.getId(), spawnPos, spawnPos.add(0, 5.0, 0), 3.2f, 1.1f, 22);
		playSound(w, spawnPos, SoundEvents.BLOCK_STONE_BREAK, 1.2f, 0.55f);
		playSound(w, spawnPos, SoundEvents.BLOCK_RESPAWN_ANCHOR_CHARGE, 0.9f, 0.85f);
		p.sendMessage(Text.literal("§5🪨 Escombro de " + count + " bloques levitado §7(Clic Izq. lanzar · Clic Der. aplastar · Shift+R prensar)"), true);
	}

	public static void throwGravityHeld(ServerPlayerEntity p, PlayerPower pw, boolean all) {
		ServerWorld w = p.getServerWorld();
		cleanHeld(w, pw);
		if (pw.gravityHeld.isEmpty()) return;

		Vec3d aim = PowerManager.aimPoint(p, 140.0);
		List<Integer> toThrow = all ? new ArrayList<>(pw.gravityHeld) : List.of(pw.gravityHeld.get(0));
		for (int id : toThrow) {
			pw.gravityHeld.remove((Integer) id);
			Entity e = w.getEntityById(id);
			if (e == null || !e.isAlive()) continue;
			Vec3d from = e.getBoundingBox().getCenter();
			Vec3d dir = aim.subtract(from);
			if (dir.lengthSquared() < 0.01) dir = p.getRotationVector();
			else dir = dir.normalize();
			e.setNoGravity(false);
			if (e instanceof PieceEntity pe) {
				pe.setFlag(PieceEntity.F_SEAM, false);
				pe.release(dir.multiply(2.9), true);
			} else {
				e.setVelocity(dir.multiply(2.75));
				e.velocityModified = true;
			}
			sendFx(w, Payloads.GravityFx.KIND_MOKO, p.getId(), from, from.add(dir.multiply(12.0)), 1.8f, 0.9f, 14);
		}
		pw.dirty = true;
		playSound(w, p.getPos(), SoundEvents.ENTITY_BREEZE_WIND_BURST.value(), 1.3f, 0.75f);
		playSound(w, p.getPos(), SoundEvents.ENTITY_WARDEN_SONIC_BOOM, 0.7f, 1.35f);
	}

	public static void slamGravityHeld(ServerPlayerEntity p, PlayerPower pw) {
		ServerWorld w = p.getServerWorld();
		cleanHeld(w, pw);
		if (pw.gravityHeld.isEmpty()) return;

		for (int id : new ArrayList<>(pw.gravityHeld)) {
			Entity e = w.getEntityById(id);
			if (e == null || !e.isAlive()) continue;
			Vec3d pos = e.getBoundingBox().getCenter();
			e.setNoGravity(false);
			if (e instanceof PieceEntity pe) {
				pe.setFlag(PieceEntity.F_SEAM, false);
				pe.release(new Vec3d(0, -3.1, 0), true);
			} else {
				e.setVelocity(0, -2.9, 0);
				e.velocityModified = true;
				if (e instanceof LivingEntity le) {
					le.damage(ModDamage.of(w, ModDamage.GAMMA, p), 22f + pw.masteryTier() * 5f);
				}
			}
			sendFx(w, Payloads.GravityFx.KIND_PRESS_COLUMN, p.getId(), pos.add(0, 8, 0), pos.add(0, -4, 0), 3.4f, 1.2f, 22);
		}
		pw.gravityHeld.clear();
		pw.dirty = true;
		playSound(w, p.getPos(), SoundEvents.BLOCK_ANVIL_LAND, 1.4f, 0.5f);
		p.sendMessage(Text.literal("§5⬇ ¡Masas gravitatorias estampadas contra la tierra!"), true);
	}

	public static void releaseGravityHeld(ServerPlayerEntity p, PlayerPower pw) {
		releaseGravityHeld(p, pw, p.getServerWorld());
	}

	public static void releaseGravityHeld(ServerPlayerEntity p, PlayerPower pw, ServerWorld w) {
		if (pw.gravityHeld.isEmpty()) return;
		for (int id : pw.gravityHeld) {
			Entity e = w.getEntityById(id);
			if (e == null) continue;
			e.setNoGravity(false);
			if (e instanceof PieceEntity pe) {
				pe.setFlag(PieceEntity.F_SEAM, false);
				pe.release(Vec3d.ZERO, false);
			}
		}
		pw.gravityHeld.clear();
		pw.dirty = true;
	}

	public static void scrollHoldDist(PlayerPower pw, int dir) {
		pw.gravityHoldDist = MathHelper.clamp(pw.gravityHoldDist + dir * 2.0, 4.0, 48.0);
	}

	private static void cleanHeld(ServerWorld w, PlayerPower pw) {
		boolean changed = false;
		Iterator<Integer> it = pw.gravityHeld.iterator();
		while (it.hasNext()) {
			Entity e = w.getEntityById(it.next());
			if (e == null || e.isRemoved() || !e.isAlive()) {
				it.remove();
				changed = true;
			}
		}
		if (changed) pw.dirty = true;
	}

	// ------------------------------------------------------------------ Tick por Jugador y del Servidor

	public static void tickFieldAndFlight(ServerPlayerEntity p, PlayerPower pw) {
		ServerWorld w = p.getServerWorld();
		// Fujitora nunca debe tener un ROOM de Law activo
		if (pw.room != null || pw.rroom != null) {
			RoomOps.remove(p, pw);
		}

		// 1) Carga continua de G (mantener tecla G para aumentar presión sin ningún ROOM)
		if (pw.gravityCharging) {
			pw.gravityChargeTicks = Math.min(60, pw.gravityChargeTicks + 1);
			pw.gravityChargePos = PowerManager.aimPoint(p, 52.0);
			float chargeProg = pw.gravityChargeTicks / 60.0f;
			double rad = 3.5 + chargeProg * 4.5;
			if (pw.gravityChargeTicks % 4 == 0) {
				sendFx(w, Payloads.GravityFx.KIND_CHARGE_RING, p.getId(), pw.gravityChargePos,
					pw.gravityChargePos.add(0, pw.gravityVector == 1 ? 6 : -6, 0), (float) rad, 0.5f + chargeProg * 1.5f, 10);
			}
			if (pw.gravityChargeTicks % 5 == 0) {
				for (LivingEntity le : w.getEntitiesByClass(LivingEntity.class, new Box(pw.gravityChargePos, pw.gravityChargePos).expand(rad), e -> e != p && e.isAlive())) {
					if (pw.gravityVector == 0) {
						le.addVelocity(0, -0.35, 0);
						le.addStatusEffect(new StatusEffectInstance(StatusEffects.SLOWNESS, 15, 2, false, false, true));
					} else if (pw.gravityVector == 1) {
						le.setVelocity(le.getVelocity().x * 0.7, 0.14, le.getVelocity().z * 0.7);
					} else if (pw.gravityVector == 3) {
						Vec3d toC = pw.gravityChargePos.subtract(le.getPos()).normalize().multiply(0.35);
						le.addVelocity(toC.x, 0.05, toC.z);
					}
					le.velocityModified = true;
				}
			}
		}

		// 1b) Carga y selección de tamaño de meteorito (mantener Z: sube de nivel o usa la rueda para elegir 12 m .. 85 m)
		if (pw.meteorCharging) {
			pw.meteorChargeTicks++;
			pw.meteorAimPos = meteorAimTarget(p, w);
			if (pw.meteorChargeTicks > 12 && pw.meteorChargeTicks % 18 == 0 && pw.meteorTier < METEOR_NAMES.length - 1) {
				pw.meteorTier++;
				pw.dirty = true;
				playSound(w, p.getPos(), SoundEvents.BLOCK_RESPAWN_ANCHOR_CHARGE, 0.9f, 0.5f + pw.meteorTier * 0.12f);
			}
			if (pw.meteorChargeTicks % 6 == 0) {
				int t = MathHelper.clamp(pw.meteorTier, 0, METEOR_NAMES.length - 1);
				p.sendMessage(Text.literal("§5☄ TENGAI SHINSEI · §6[" + METEOR_NAMES[t] + "] §7(Cráter " + Math.round(METEOR_CRATERS[t] * 2) + "m · Rueda o mantén Z · Suelta para desplomar)"), true);
			}
		}

		// 2) Entidades y rocas levitadas libremente con R (pw.gravityHeld)
		if (!pw.gravityHeld.isEmpty()) {
			cleanHeld(w, pw);
			Vec3d eye = p.getEyePos();
			Vec3d look = p.getRotationVector();
			Vec3d right = look.crossProduct(new Vec3d(0, 1, 0));
			if (right.lengthSquared() < 1e-4) right = new Vec3d(1, 0, 0);
			else right = right.normalize();
			Vec3d up = right.crossProduct(look).normalize();
			int n = pw.gravityHeld.size();

			for (int i = 0; i < n; i++) {
				Entity e = w.getEntityById(pw.gravityHeld.get(i));
				if (e == null) continue;
				Vec3d slot;
				if (n == 1) {
					slot = eye.add(look.multiply(pw.gravityHoldDist));
				} else {
					double ang = (Math.PI * 2.0 * i / n) + p.age * 0.04;
					double ringR = Math.min(8.5, 3.2 + n * 0.45);
					slot = eye.add(look.multiply(Math.max(8.0, pw.gravityHoldDist)))
						.add(right.multiply(Math.cos(ang) * ringR))
						.add(up.multiply(Math.sin(ang) * ringR * 0.45 + 2.5));
				}
				if (e instanceof PieceEntity pe) {
					pe.control(slot);
				} else {
					e.setNoGravity(true);
					e.fallDistance = 0f;
					Vec3d delta = slot.subtract(e.getBoundingBox().getCenter());
					e.setVelocity(delta.multiply(0.32));
					e.velocityModified = true;
				}
				if (p.age % 5 == 0) {
					w.spawnParticles(GRAVITY_DUST, e.getX(), e.getY() + 0.2, e.getZ(), 2, 0.4, 0.2, 0.4, 0);
				}
			}
		}

		// 3) Escudo de Repulsión Orbital pegado al cuerpo de Fujitora (B)
		if (pw.curtainTicks > 0) {
			for (Entity e : w.getOtherEntities(p, p.getBoundingBox().expand(4.2), ent -> ent.isAlive() && !ent.isSpectator() && ent.getId() != pw.gravityMountId)) {
				if (e instanceof ProjectileEntity pr && pr.getOwner() == p) continue;
				if (pw.gravityHeld.contains(e.getId())) continue;
				Vec3d push = e.getPos().subtract(p.getPos());
				if (push.lengthSquared() < 0.05) push = new Vec3d(0, 0.4, 1);
				Vec3d dir = push.normalize();
				e.setVelocity(dir.x * 1.55, Math.max(0.35, dir.y * 0.8 + 0.25), dir.z * 1.55);
				e.velocityModified = true;
			}
		}

		// 4) Vuelo Canon de Fujitora: Fujitora NO vuela nunca; la roca es un vehículo real que lo transporta
		if (!p.isCreative() && !p.isSpectator() && pw.surgicalFlightGranted) {
			pw.surgicalFlightGranted = false;
			p.getAbilities().allowFlying = false;
			p.getAbilities().flying = false;
			p.sendAbilitiesUpdate();
		}
		if (p.getVehicle() instanceof PieceEntity surfRock && surfRock.isGravitySurf() && surfRock.isAlive()) {
			if (pw.gravityMountId != surfRock.getId()) {
				pw.gravityMountId = surfRock.getId();
				pw.dirty = true;
			}
			pw.fallImmunityTicks = 200;
			p.fallDistance = 0f;
			if (p.age % 3 == 0) {
				w.spawnParticles(ABYSS_DUST, surfRock.getX(), surfRock.getY() - 0.1, surfRock.getZ(), 3, 0.85, 0.05, 0.85, 0);
			}
		} else if (pw.gravityMountId >= 0) {
			Entity old = w.getEntityById(pw.gravityMountId);
			if (old instanceof PieceEntity pe && pe.mode == PieceEntity.Mode.SUMMON_SURF && pe.isAlive()) {
				// Roca real en camino hacia tus pies: protege contra caída mientras llega a recogerte
				pw.fallImmunityTicks = Math.max(pw.fallImmunityTicks, 140);
			} else {
				if (old instanceof PieceEntity pe && pe.isGravitySurf() && !pe.hasPassengers()) {
					pe.release(Vec3d.ZERO, false);
				}
				pw.gravityMountId = -1;
				pw.fallImmunityTicks = Math.max(pw.fallImmunityTicks, 140);
				pw.dirty = true;
			}
		}
	}

	public static void tickServer(MinecraftServer server) {
		tickZones(server);
		tickMeteors(server);
		tickCraters(server);
	}

	private static void tickZones(MinecraftServer server) {
		if (ZONES.isEmpty()) return;
		Iterator<ActiveZone> it = ZONES.iterator();
		while (it.hasNext()) {
			ActiveZone z = it.next();
			ServerWorld w = server.getWorld(z.dim);
			if (w == null || --z.ticksLeft <= 0) {
				it.remove();
				continue;
			}
			ServerPlayerEntity owner = server.getPlayerManager().getPlayer(z.ownerId);
			Box box = new Box(z.center, z.center).expand(z.radius);
			if (z.vector == 0) {
				// Pozo +G aplastante
				for (LivingEntity le : w.getEntitiesByClass(LivingEntity.class, box, e -> e != owner && e.isAlive())) {
					if (le.getPos().squaredDistanceTo(z.center) > z.radius * z.radius) continue;
					Vec3d v = le.getVelocity();
					le.setVelocity(v.x * 0.55, Math.min(-0.35, v.y - 0.25), v.z * 0.55);
					le.velocityModified = true;
					if (z.ticksLeft % 15 == 0) {
						le.addStatusEffect(new StatusEffectInstance(StatusEffects.SLOWNESS, 25, 2, false, false, true));
						if (le instanceof HostileEntity && owner != null) {
							le.damage(ModDamage.of(w, ModDamage.GAMMA, owner), 3.0f);
						}
					}
				}
				for (ProjectileEntity pr : w.getEntitiesByClass(ProjectileEntity.class, box, p -> p.isAlive() && !p.isOnGround())) {
					if (pr.getOwner() == owner) continue;
					pr.setVelocity(pr.getVelocity().x * 0.15, -2.0, pr.getVelocity().z * 0.15);
					pr.velocityModified = true;
				}
				if (z.ticksLeft % 12 == 0) {
					sendFx(w, Payloads.GravityFx.KIND_PRESS_COLUMN, owner != null ? owner.getId() : -1,
						z.center.add(0, 9, 0), z.center, (float) z.radius, 0.7f, 16);
				}
			} else if (z.vector == 1) {
				// Corriente -G antigravedad
				for (Entity e : w.getOtherEntities(owner, box, ent -> ent.isAlive() && !ent.isSpectator())) {
					if (e.getPos().squaredDistanceTo(z.center) > z.radius * z.radius) continue;
					e.fallDistance = 0f;
					e.setVelocity(e.getVelocity().x * 0.85, 0.12, e.getVelocity().z * 0.85);
					e.velocityModified = true;
				}
				if (z.ticksLeft % 14 == 0) {
					sendFx(w, Payloads.GravityFx.KIND_LIFT_PULSE, owner != null ? owner.getId() : -1,
						z.center, z.center.add(0, 9, 0), (float) z.radius, 0.7f, 18);
				}
			} else if (z.vector == 3) {
				// Vórtice de atracción en espiral
				for (Entity e : w.getOtherEntities(owner, box, ent -> ent.isAlive() && !ent.isSpectator())) {
					double d2 = e.getPos().squaredDistanceTo(z.center);
					if (d2 > z.radius * z.radius || d2 < 0.35) continue;
					Vec3d to = z.center.add(0, 0.8, 0).subtract(e.getPos()).normalize();
					Vec3d tang = new Vec3d(-to.z, 0, to.x).multiply(0.18);
					e.setVelocity(to.multiply(0.45).add(tang));
					e.velocityModified = true;
				}
			}
		}
	}

	private static void tickMeteors(MinecraftServer server) {
		if (METEORS.isEmpty()) return;
		Iterator<ActiveMeteor> it = METEORS.iterator();
		while (it.hasNext()) {
			ActiveMeteor am = it.next();
			ServerWorld w = server.getWorld(am.dim);
			if (w == null) {
				it.remove();
				continue;
			}
			Entity e = w.getEntityById(am.entityId);
			if (!(e instanceof PieceEntity pe) || pe.isRemoved() || !pe.isAlive()) {
				it.remove();
				continue;
			}

			int tier = MathHelper.clamp(am.tier, 0, METEOR_SCALES.length - 1);
			double radVis = 4.5 * METEOR_SCALES[tier];
			Vec3d mc = pe.center();
			double spread = Math.min(18.0, radVis * 0.45);
			w.spawnParticles(ParticleTypes.FLAME, mc.x, mc.y, mc.z, 18 + tier * 8, spread, spread, spread, 0.14);
			w.spawnParticles(ParticleTypes.CAMPFIRE_COSY_SMOKE, mc.x, mc.y + radVis * 0.3, mc.z, 10 + tier * 5, spread, spread, spread, 0.04);
			w.spawnParticles(ParticleTypes.LAVA, mc.x, mc.y, mc.z, 6 + tier * 4, spread * 0.7, spread * 0.7, spread * 0.7, 0.12);
			w.spawnParticles(GRAVITY_DUST, mc.x, mc.y, mc.z, 10 + tier * 4, spread, spread, spread, 0.02);

			// Rugido atmosférico descendente que se vuelve ensordecedor al acercarse a la tierra
			if (am.age % 12 == 0) {
				playGlobalSound(w, mc, 360.0, SoundEvents.ENTITY_ENDER_DRAGON_GROWL, 1.4f + tier * 0.35f, 0.45f);
			}

			// Si cayó al vacío absoluto sin tocar jamás un bloque sólido, desaparece en el abismo sin explotar en el aire
			if (mc.y < w.getBottomY() - 96 || am.age > 900) {
				pe.discard();
				it.remove();
				continue;
			}

			// Impacto 100 % físico: el meteorito JAMÁS explota en el aire; solo detona cuando su roca frontal/inferior toca un bloque real
			double surfaceR = 4.1 * METEOR_SCALES[tier];
			Vec3d hitPos = am.age >= 6 ? findMeteorContact(w, mc, pe.getVelocity(), surfaceR) : null;
			if (hitPos != null) {
				ServerPlayerEntity owner = server.getPlayerManager().getPlayer(am.ownerId);
				detonateMeteor(w, owner, pe, hitPos, tier);
				it.remove();
			} else {
				int idx = METEORS.indexOf(am);
				if (idx >= 0) {
					METEORS.set(idx, new ActiveMeteor(am.dim, am.entityId, am.ownerId, am.target, am.portalPos, am.tier, am.age + 1));
				}
			}
		}
	}

	/**
	 * Comprueba colisión real de la superficie delantera/inferior del asteroide con bloques sólidos o agua del mundo.
	 * Atraviesa aire, hojas y hierba sin detonar; devuelve null si no ha tocado nada todavía.
	 */
	private static Vec3d findMeteorContact(ServerWorld w, Vec3d center, Vec3d vel, double surfaceR) {
		if (center.y - surfaceR > w.getTopY() + 2) return null;
		Vec3d fwd = vel.lengthSquared() > 1e-4 ? vel.normalize() : new Vec3d(0.0, -1.0, 0.0);
		Vec3d right = fwd.crossProduct(new Vec3d(0, 1, 0));
		if (right.lengthSquared() < 1e-4) right = new Vec3d(1, 0, 0);
		else right = right.normalize();
		Vec3d sideUp = right.crossProduct(fwd).normalize();

		// 5 sondas físicas sobre la nariz y panza del meteorito para detectar el primer bloque sólido real que choca
		Vec3d[] dirs = {
			fwd,
			new Vec3d(0.0, -1.0, 0.0),
			fwd.add(right.multiply(0.38)).normalize(),
			fwd.add(right.multiply(-0.38)).normalize(),
			fwd.add(sideUp.multiply(-0.38)).normalize()
		};
		BlockPos.Mutable bp = new BlockPos.Mutable();
		double step = Math.max(1.0, Math.min(2.0, surfaceR * 0.14));
		double bestDistSq = Double.MAX_VALUE;
		Vec3d bestHit = null;

		for (int dIdx = 0; dIdx < dirs.length; dIdx++) {
			Vec3d dir = dirs[dIdx];
			double maxLen = dIdx < 2 ? surfaceR : surfaceR * 0.85;
			for (double d = 0.0; d <= maxLen; d += step) {
				double px = center.x + dir.x * d;
				double py = center.y + dir.y * d;
				double pz = center.z + dir.z * d;
				if (py >= w.getTopY() || py < w.getBottomY()) continue;
				bp.set(MathHelper.floor(px), MathHelper.floor(py), MathHelper.floor(pz));
				if (isMeteorImpactBlock(w, bp)) {
					double d2 = d * d;
					if (d2 < bestDistSq) {
						bestDistSq = d2;
						bestHit = new Vec3d(bp.getX() + 0.5, bp.getY() + 0.5, bp.getZ() + 0.5);
					}
					break;
				}
			}
		}
		return bestHit;
	}

	private static boolean isMeteorImpactBlock(ServerWorld w, BlockPos bp) {
		BlockState st = w.getBlockState(bp);
		if (st.isAir()) return false;
		if (st.getBlock() instanceof FluidBlock) return true;
		if (st.isIn(BlockTags.LEAVES) || st.isReplaceable()) return false;
		return !st.getCollisionShape(w, bp).isEmpty();
	}

	private static void detonateMeteor(ServerWorld w, ServerPlayerEntity owner, PieceEntity meteor, Vec3d impact, int tier) {
		meteor.discard();

		double R = METEOR_CRATERS[MathHelper.clamp(tier, 0, METEOR_CRATERS.length - 1)];
		BlockPos cb = BlockPos.ofFloored(impact);
		BlockPos safeCenter = owner != null ? owner.getBlockPos() : null;

		// Cola de excavación progresiva por ondas expansivas (cero congelación incluso en cráteres de 116 metros)
		PendingCrater crater = new PendingCrater(w.getRegistryKey(), owner != null ? owner.getUuid() : null, cb, safeCenter, R, tier);
		stepCraterShell(w, owner, crater, Math.min(R, 11.0));
		if (crater.curR < crater.maxR) {
			CRATERS.add(crater);
		} else {
			placeSpatialCore(w, cb, tier, R);
		}

		// Corona volcánica de rocas ardientes catapultadas al cielo
		int debrisCount = 24 + tier * 14;
		BlockState[] debrisPalette = {
			Blocks.MAGMA_BLOCK.getDefaultState(), Blocks.BLACKSTONE.getDefaultState(),
			Blocks.BASALT.getDefaultState(), Blocks.CRYING_OBSIDIAN.getDefaultState()
		};
		for (int i = 0; i < debrisCount; i++) {
			double ang = (Math.PI * 2.0 * i / debrisCount) + w.random.nextDouble() * 0.35;
			double rad = 2.0 + w.random.nextDouble() * Math.min(12.0, R * 0.4);
			BlockPos sp = cb.add((int) Math.round(Math.cos(ang) * rad), 2 + w.random.nextInt(4), (int) Math.round(Math.sin(ang) * rad));
			if (sp.getY() >= w.getTopY() - 2) continue;
			BlockState st = debrisPalette[w.random.nextInt(debrisPalette.length)];
			FallingBlockEntity fb = FallingBlockEntity.spawnFromBlock(w, sp, st);
			double spd = 1.05 + tier * 0.32 + w.random.nextDouble() * 0.5;
			fb.setVelocity(Math.cos(ang) * spd, 0.95 + tier * 0.25 + w.random.nextDouble() * 0.6, Math.sin(ang) * spd);
			fb.velocityModified = true;
		}

		// Daño y onda expansiva masiva escalada al tamaño del meteorito
		double dmgRad = R * 1.65;
		float baseDmg = (60f + tier * 42f) + (owner != null ? PowerManager.get(owner).masteryTier() * 12f : 0f);
		for (LivingEntity le : w.getEntitiesByClass(LivingEntity.class, new Box(impact, impact).expand(dmgRad), Entity::isAlive)) {
			if (le == owner) continue;
			double d = le.getPos().distanceTo(impact);
			if (d > dmgRad) continue;
			float falloff = (float) MathHelper.clamp(1.2 - (d / dmgRad) * 0.75, 0.35, 1.0);
			le.damage(ModDamage.of(w, ModDamage.BURN, owner), baseDmg * falloff);
			le.setOnFireFor(8 + tier * 4);
			Vec3d away = le.getPos().subtract(impact);
			if (away.lengthSquared() < 0.1) away = new Vec3d(0, 1, 0);
			double kb = (2.2 + tier * 0.75) * falloff;
			Vec3d push = away.normalize().multiply(kb).add(0, 1.1 + tier * 0.32, 0);
			le.addVelocity(push.x, push.y, push.z);
			le.velocityModified = true;
		}

		// Explosión 3D colosal (anillos fuego + púrpura + columna de plasma hasta las nubes) + audio sísmico a 400 m
		float fxRadius = (float) (R * 1.85);
		double pillarH = 26.0 + R * 2.1;
		sendFx(w, Payloads.GravityFx.KIND_METEOR_IMPACT, owner != null ? owner.getId() : -1,
			impact, impact.add(0, pillarH, 0), fxRadius, 1.8f + tier * 0.75f, 52 + tier * 14);
		w.spawnParticles(ParticleTypes.EXPLOSION_EMITTER, impact.x, impact.y + 2, impact.z, 8 + tier * 5, R * 0.35, 4.0 + tier * 1.5, R * 0.35, 0);
		w.spawnParticles(ParticleTypes.LAVA, impact.x, impact.y + 1, impact.z, 90 + tier * 45, R * 0.45, 3.0, R * 0.45, 0.25);
		playGlobalSound(w, impact, 420.0, SoundEvents.ENTITY_GENERIC_EXPLODE.value(), 3.2f + tier * 0.5f, 0.42f);
		playGlobalSound(w, impact, 420.0, SoundEvents.ENTITY_DRAGON_FIREBALL_EXPLODE, 2.8f + tier * 0.4f, 0.38f);
		playGlobalSound(w, impact, 420.0, SoundEvents.ENTITY_WARDEN_SONIC_BOOM, 2.4f + tier * 0.4f, 0.45f);
		playGlobalSound(w, impact, 420.0, SoundEvents.ENTITY_LIGHTNING_BOLT_THUNDER, 2.6f, 0.48f);

		if (owner != null) {
			PowerManager.addBounty(owner, 900_000L + tier * 650_000L);
			owner.sendMessage(Text.literal("§5☄ ¡IMPACTO DE " + METEOR_NAMES[tier].toUpperCase() + "! §7(Cráter de " + Math.round(R * 2) + " m · Núcleo espacial formado)"), true);
		}
	}

	private static void tickCraters(MinecraftServer server) {
		if (CRATERS.isEmpty()) return;
		Iterator<PendingCrater> it = CRATERS.iterator();
		while (it.hasNext()) {
			PendingCrater c = it.next();
			ServerWorld w = server.getWorld(c.dim);
			if (w == null) {
				it.remove();
				continue;
			}
			ServerPlayerEntity owner = c.ownerId == null ? null : server.getPlayerManager().getPlayer(c.ownerId);
			// Paso adaptativo: ~3.5k-5k bloques por tick para excavación instantánea y fluida a 20 TPS
			double step = c.curR < 16 ? 4.0 : (c.curR < 30 ? 2.6 : (c.curR < 45 ? 1.9 : 1.5));
			double nextR = Math.min(c.maxR, c.curR + step);
			stepCraterShell(w, owner, c, nextR);
			if (c.curR >= c.maxR - 0.01) {
				placeSpatialCore(w, c.center, c.tier, c.maxR);
				it.remove();
			}
		}
	}

	private static void stepCraterShell(ServerWorld w, ServerPlayerEntity owner, PendingCrater c, double nextR) {
		double minR2 = c.curR * c.curR;
		double maxR2 = nextR * nextR;
		double totalR = c.maxR;
		double maxDepth = Math.min(34.0, totalR * 0.56);
		double maxSkyClear = Math.min(44.0, totalR * 0.78);
		int ri = (int) Math.ceil(nextR);
		int minY = w.getBottomY() + 3;
		int maxY = w.getTopY() - 1;
		ItemStack pick = owner != null ? SurgicalSurvival.createPickaxe(w, Math.max(1, PowerManager.get(owner).masteryTier())) : ItemStack.EMPTY;
		PlayerPower pw = owner != null ? PowerManager.get(owner) : null;
		BlockPos.Mutable bp = new BlockPos.Mutable();
		int flags = Block.NOTIFY_LISTENERS | Block.FORCE_STATE;

		for (int dx = -ri; dx <= ri; dx++) {
			for (int dz = -ri; dz <= ri; dz++) {
				double h2 = dx * dx + dz * dz;
				if (h2 < minR2 || h2 > maxR2) continue;
				if (c.safeCenter != null && Math.abs(c.center.getX() + dx - c.safeCenter.getX()) <= 2
					&& Math.abs(c.center.getZ() + dz - c.safeCenter.getZ()) <= 2) continue;

				double frac = Math.sqrt(h2) / totalR;
				// Perfil parabólico real de cráter planetario con bordes irregulares orgánicos
				double noise = 0.08 * Math.sin((c.center.getX() + dx) * 0.35) * Math.cos((c.center.getZ() + dz) * 0.35);
				double bowl = Math.max(0.0, 1.0 - frac * frac + noise);
				int floorY = Math.max(minY, c.center.getY() - (int) Math.round(maxDepth * bowl));
				int ceilY = Math.min(maxY, c.center.getY() + (int) Math.round(maxSkyClear * Math.sqrt(Math.max(0.0, 1.0 - frac * frac * 0.85))) + 4);
				int x = c.center.getX() + dx;
				int z = c.center.getZ() + dz;

				for (int y = ceilY; y >= floorY; y--) {
					bp.set(x, y, z);
					BlockState st = w.getBlockState(bp);
					if (st.isAir()) continue;
					if (st.hasBlockEntity() || st.getHardness(w, bp) < 0f) continue;

					if (owner != null && pw != null && c.oresRewarded < 96 && SurgicalSurvival.isOre(st)) {
						SurgicalSurvival.givePerfectOreYield(owner, w, pw, bp.toImmutable(), st, pick, false, true, false, null);
						c.oresRewarded++;
					}
					w.setBlockState(bp, Blocks.AIR.getDefaultState(), flags);
				}

				// Suelo calcinado del cráter: basalto, piedra negra, vetas de magma incandescente y fuego real
				int crustY = floorY - 1;
				if (crustY >= minY) {
					bp.set(x, crustY, z);
					BlockState floorSt = w.getBlockState(bp);
					if (!floorSt.isAir() && !floorSt.hasBlockEntity() && floorSt.getHardness(w, bp) >= 0f) {
						int roll = w.random.nextInt(16);
						if (roll <= 2 && frac < 0.72) {
							w.setBlockState(bp, Blocks.MAGMA_BLOCK.getDefaultState(), flags);
						} else if (roll <= 5) {
							w.setBlockState(bp, Blocks.BLACKSTONE.getDefaultState(), flags);
						} else if (roll <= 8) {
							w.setBlockState(bp, Blocks.BASALT.getDefaultState(), flags);
						}
						if (w.random.nextInt(14) == 0 && frac < 0.85) {
							bp.set(x, floorY, z);
							if (w.getBlockState(bp).isAir()) {
								w.setBlockState(bp, Blocks.FIRE.getDefaultState(), flags);
							}
						}
					}
				}
			}
		}
		c.curR = nextR;
	}

	/** Deposita el núcleo cósmico del asteroide incrustado en el epicentro del cráter (más rico cuanto más colosal). */
	private static void placeSpatialCore(ServerWorld w, BlockPos cb, int tier, double maxR) {
		int depth = (int) Math.round(Math.min(34.0, maxR * 0.56));
		BlockPos floor = new BlockPos(cb.getX(), Math.max(w.getBottomY() + 4, cb.getY() - depth), cb.getZ());
		List<Block> rareOres = rareModOres();
		int coreR = Math.min(4, 1 + tier);
		for (int dx = -coreR; dx <= coreR; dx++) {
			for (int dy = 0; dy <= Math.max(1, coreR - 1); dy++) {
				for (int dz = -coreR; dz <= coreR; dz++) {
					double d2 = dx * dx + dy * dy * 1.5 + dz * dz;
					if (d2 > coreR * coreR + 0.5) continue;
					BlockPos op = floor.add(dx, dy, dz);
					if (op.getY() <= w.getBottomY() + 2 || op.getY() >= w.getTopY() - 1) continue;
					BlockState st;
					if (d2 <= 1.2) {
						st = Blocks.ANCIENT_DEBRIS.getDefaultState();
					} else {
						int r = w.random.nextInt(7);
						st = switch (r) {
							case 0 -> Blocks.DEEPSLATE_DIAMOND_ORE.getDefaultState();
							case 1 -> Blocks.RAW_GOLD_BLOCK.getDefaultState();
							case 2 -> Blocks.RAW_IRON_BLOCK.getDefaultState();
							case 3 -> rareOres.isEmpty() ? Blocks.DEEPSLATE_EMERALD_ORE.getDefaultState() : rareOres.get(w.random.nextInt(rareOres.size())).getDefaultState();
							case 4 -> Blocks.CRYING_OBSIDIAN.getDefaultState();
							default -> Blocks.MAGMA_BLOCK.getDefaultState();
						};
					}
					w.setBlockState(op, st, Block.NOTIFY_LISTENERS);
				}
			}
		}
	}

	private static void playGlobalSound(ServerWorld w, Vec3d pos, double range, SoundEvent sound, float volume, float pitch) {
		double r2 = range * range;
		for (ServerPlayerEntity sp : w.getPlayers()) {
			if (sp.squaredDistanceTo(pos) <= r2) {
				// Sonido centrado hacia el jugador para que se oiga desde el cielo y a gran distancia
				Vec3d dir = pos.subtract(sp.getPos());
				double d = dir.length();
				Vec3d at = d <= 18.0 ? pos : sp.getPos().add(dir.multiply(18.0 / d));
				w.playSound(null, at.x, at.y, at.z, sound, SoundCategory.PLAYERS, volume, pitch);
			}
		}
	}

	private static List<Block> rareModOres() {
		if (cachedRareModOres != null) return cachedRareModOres;
		List<Block> list = new ArrayList<>();
		for (Block b : Registries.BLOCK) {
			var id = Registries.BLOCK.getId(b);
			if (!id.getNamespace().equals("minecraft") && SurgicalSurvival.isOre(b.getDefaultState())) {
				String p = id.getPath();
				if (p.contains("uranium") || p.contains("platinum") || p.contains("nickel")
					|| p.contains("ruby") || p.contains("sapphire") || p.contains("iridium") || p.contains("silver")) {
					list.add(b);
				}
			}
		}
		cachedRareModOres = List.copyOf(list);
		return cachedRareModOres;
	}

	// ------------------------------------------------------------------ Alt izq.: Plataforma de Roca (Vuelo Canon) / Atracción de Veta (SIN ROOM)

	public static void rockFlightToggle(ServerPlayerEntity p, PlayerPower pw) {
		ServerWorld w = p.getServerWorld();
		// Shift + Alt: atracción gravitatoria de veta mineral en cualquier lugar (SIN ROOM!)
		if (p.isSneaking() && extractOreVeinByGravity(p, pw)) {
			pw.cooldown(Tech.SHAMBLES);
			return;
		}
		if (pw.onCooldown(Tech.SHAMBLES)) return;

		// Si ya vas de pie sobre tu roca voladora: saltas de ella y la disparas como proyectil
		if (p.getVehicle() instanceof PieceEntity ridingRock && ridingRock.isGravitySurf()) {
			Vec3d look = p.getRotationVector();
			Vec3d originPos = ridingRock.center();
			p.stopRiding();
			pw.gravityMountId = -1;
			pw.fallImmunityTicks = 240;
			p.setVelocity(look.x * -0.30, 0.58, look.z * -0.30);
			p.velocityModified = true;
			if (p.networkHandler != null) p.networkHandler.sendPacket(new EntityVelocityUpdateS2CPacket(p.getId(), p.getVelocity()));

			ridingRock.setFlag(PieceEntity.F_SEAM, false);
			ridingRock.release(look.multiply(2.9), true);
			sendFx(w, Payloads.GravityFx.KIND_MOKO, p.getId(), originPos, originPos.add(look.multiply(14.0)), 2.2f, 1.0f, 14);
			playSound(w, originPos, SoundEvents.ENTITY_BREEZE_WIND_BURST.value(), 1.2f, 0.8f);
			pw.dirty = true;
			p.sendMessage(Text.literal("§5🪨 ¡Roca gravitatoria disparada!"), true);
			pw.cooldown(Tech.SHAMBLES);
			return;
		}

		// Si ya hay una roca real volando hacia tus pies: cancelar la llamada
		if (pw.gravityMountId >= 0) {
			Entity incoming = w.getEntityById(pw.gravityMountId);
			if (incoming instanceof PieceEntity pe && pe.mode == PieceEntity.Mode.SUMMON_SURF && pe.isAlive()) {
				pe.surfCallerUuid = null;
				pe.release(Vec3d.ZERO, false);
				pw.gravityMountId = -1;
				pw.dirty = true;
				p.sendMessage(Text.literal("§7Llamada de roca cancelada"), true);
				return;
			}
		}

		// Prioridad 1: si ya tienes una roca levitando con [R], la atrae directamente bajo tus pies para subirte en ella
		cleanHeld(w, pw);
		PieceEntity heldRock = null;
		double bestHeldD2 = Double.MAX_VALUE;
		for (int id : pw.gravityHeld) {
			Entity e = w.getEntityById(id);
			if (e instanceof PieceEntity pe && pe.isAlive() && !pe.isRemoved() && pe.blocks != null && !pe.blocks.isEmpty()) {
				double d2 = pe.squaredDistanceTo(p);
				if (d2 < bestHeldD2) {
					bestHeldD2 = d2;
					heldRock = pe;
				}
			}
		}
		if (heldRock != null) {
			pw.gravityHeld.remove((Integer) heldRock.getId());
			Vec3d from = heldRock.center();
			heldRock.summonForSurf(p.getUuid());
			pw.gravityMountId = heldRock.getId();
			pw.fallImmunityTicks = Math.max(pw.fallImmunityTicks, 200);
			pw.dirty = true;
			sendFx(w, Payloads.GravityFx.KIND_HYPER_PRESS, p.getId(), from, p.getPos().add(0, 0.4, 0), 2.8f, 1.2f, 18);
			playSound(w, from, SoundEvents.BLOCK_RESPAWN_ANCHOR_CHARGE, 0.9f, 0.85f);
			p.sendMessage(Text.literal("§5🪨 Atrayendo tu roca levitada bajo tus pies..."), true);
			pw.cooldown(Tech.SHAMBLES);
			return;
		}

		// Prioridad 2 y 3: arranca una roca REAL de donde miras (3..52 m) o del terreno/piedra real cercana (5..34 m) y la trae volando
		BlockPos sourceCenter = findRealRockSource(p, w);
		if (sourceCenter == null) {
			p.sendMessage(Text.literal("§7No hay ninguna roca ni terreno real cerca para atraer (apunta a piedra o suelo)"), true);
			return;
		}

		BlockSet set = new BlockSet();
		BlockPos feetBelow = p.getBlockPos().down();
		int count = 0;
		for (int dx = -1; dx <= 1; dx++) {
			for (int dz = -1; dz <= 1; dz++) {
				BlockPos bp = sourceCenter.add(dx, 0, dz);
				if (p.isOnGround() && bp.equals(feetBelow)) continue;
				if (!isLiftableTerrain(w, bp)) continue;
				BlockState st = w.getBlockState(bp);
				set.add(dx + 1, 0, dz + 1, st, null);
				w.setBlockState(bp, Blocks.AIR.getDefaultState(), Block.NOTIFY_LISTENERS);
				count++;
			}
		}
		// Si en la capa superior de una ladera había pocos bloques, toma también la capa inferior real para formar una losa sólida
		if (count < 3) {
			for (int dx = -1; dx <= 1; dx++) {
				for (int dz = -1; dz <= 1; dz++) {
					BlockPos bp = sourceCenter.add(dx, -1, dz);
					if (p.isOnGround() && bp.equals(feetBelow)) continue;
					if (!isLiftableTerrain(w, bp)) continue;
					BlockState st = w.getBlockState(bp);
					set.add(dx + 1, 0, dz + 1, st, null);
					w.setBlockState(bp, Blocks.AIR.getDefaultState(), Block.NOTIFY_LISTENERS);
					count++;
				}
			}
		}
		if (count == 0) {
			p.sendMessage(Text.literal("§7No se pudo arrancar roca sólida en ese punto"), true);
			return;
		}
		set.normalize();

		Vec3d originPos = new Vec3d(sourceCenter.getX() + 0.5, sourceCenter.getY() + 0.25, sourceCenter.getZ() + 0.5);
		PieceEntity plat = PieceEntity.spawn(w, set, originPos.subtract(set.sx / 2.0, 0, set.sz / 2.0), sourceCenter, p.getUuid());
		plat.setYaw(p.getYaw());
		plat.summonForSurf(p.getUuid());

		pw.gravityMountId = plat.getId();
		pw.fallImmunityTicks = Math.max(pw.fallImmunityTicks, 200);
		pw.dirty = true;
		sendFx(w, Payloads.GravityFx.KIND_LIFT_PULSE, p.getId(), originPos, originPos.add(0, 4.5, 0), 3.0f, 1.1f, 20);
		sendFx(w, Payloads.GravityFx.KIND_HYPER_PRESS, p.getId(), originPos, p.getPos().add(0, 0.4, 0), 2.6f, 1.1f, 18);
		playSound(w, originPos, SoundEvents.BLOCK_STONE_BREAK, 1.3f, 0.52f);
		playSound(w, p.getPos(), SoundEvents.BLOCK_RESPAWN_ANCHOR_CHARGE, 0.9f, 0.75f);
		p.sendMessage(Text.literal("§5🪨 ¡Arrancando roca real (" + count + " bloques) y atrayéndola hacia ti!"), true);
		pw.cooldown(Tech.SHAMBLES);
	}

	/** Llamado desde {@link PieceEntity} en cuanto la roca real atraída llega bajo los pies de Fujitora. */
	public static void onSurfRockArrived(ServerPlayerEntity p, PieceEntity plat, Vec3d dest) {
		ServerWorld w = p.getServerWorld();
		PlayerPower pw = PowerManager.get(p);
		plat.setPosition(dest.x, dest.y, dest.z);
		plat.setYaw(p.getYaw());
		plat.setGravitySurf(true);
		if (!w.isSpaceEmpty(plat)) {
			plat.setPosition(dest.x, dest.y + 0.55, dest.z);
		}
		plat.setVelocity(0.0, 0.14, 0.0);
		plat.setFlag(PieceEntity.F_SEAM, true);

		if (p.getAbilities().flying || (!p.isCreative() && !p.isSpectator() && p.getAbilities().allowFlying)) {
			if (!p.isCreative() && !p.isSpectator()) p.getAbilities().allowFlying = false;
			p.getAbilities().flying = false;
			p.sendAbilitiesUpdate();
			pw.surgicalFlightGranted = false;
		}
		p.startRiding(plat, true);
		pw.gravityMountId = plat.getId();
		pw.fallImmunityTicks = 300;
		p.fallDistance = 0f;
		pw.dirty = true;
		sendFx(w, Payloads.GravityFx.KIND_LIFT_PULSE, p.getId(), dest, dest.add(0, 3.2, 0), 2.6f, 0.95f, 16);
		playSound(w, dest, SoundEvents.BLOCK_AMETHYST_BLOCK_RESONATE, 1.2f, 0.8f);
		p.sendMessage(Text.literal("§5🪨 ¡La roca te recoge y te lleva! §7(WASD dirigir · Espacio subir · Ctrl turbo · Mayús bajar · Alt lanzar)"), true);
	}

	/**
	 * Busca una fuente REAL de roca/terreno en el mundo (jamás inventa bloques de la nada ni aparece bajo tus pies):
	 * 1) Bloque apuntado a 3..52 m; 2) Piedra/roca real cercana (5..34 m); 3) Terreno sólido real cercano (5..32 m).
	 */
	private static BlockPos findRealRockSource(ServerPlayerEntity p, ServerWorld w) {
		BlockHitResult hit = PowerManager.raycastBlock(p, 52.0);
		if (hit.getType() == HitResult.Type.BLOCK) {
			BlockPos hp = hit.getBlockPos();
			if (hit.getPos().squaredDistanceTo(p.getPos()) >= 9.0 && isLiftableTerrain(w, hp)) {
				return hp;
			}
		}

		BlockPos pc = p.getBlockPos();
		double baseYaw = Math.toRadians(p.getYaw() + 90.0);
		// Prioriza piedra real primero; si no hay piedra visible (pradera/desierto), busca terreno sólido real a distancia
		for (int pass = 0; pass < 2; pass++) {
			boolean stoneOnly = pass == 0;
			int maxR = stoneOnly ? 34 : 30;
			for (int r = 5; r <= maxR; r += 3) {
				// 16 direcciones empezando al frente de tu mirada para que veas la roca despegar y venir hacia ti
				for (int step = 0; step < 16; step++) {
					int signed = (step % 2 == 0) ? (step / 2) : -((step + 1) / 2);
					double ang = baseYaw + signed * (Math.PI / 8.0);
					int bx = pc.getX() + (int) Math.round(Math.cos(ang) * r);
					int bz = pc.getZ() + (int) Math.round(Math.sin(ang) * r);

					// 1) Superficie expuesta en el heightmap (omite árboles en búsqueda automática)
					int topY = w.getTopY(net.minecraft.world.Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, bx, bz) - 1;
					if (topY >= w.getBottomY() + 1 && Math.abs(topY - pc.getY()) <= 20) {
						BlockPos cand = new BlockPos(bx, topY, bz);
						BlockState cst = w.getBlockState(cand);
						if (isLiftableTerrain(w, cand) && !cst.isIn(BlockTags.LOGS) && !cst.isIn(BlockTags.LEAVES)
							&& (!stoneOnly || isStoneLike(cst))) {
							return cand;
						}
					}
					// 2) En cuevas / laderas alrededor de tu altura
					for (int dy = -2; dy <= 5; dy += 2) {
						int cy = pc.getY() + dy;
						if (cy < w.getBottomY() + 1 || cy >= w.getTopY() - 1) continue;
						BlockPos cand = new BlockPos(bx, cy, bz);
						if (isLiftableTerrain(w, cand) && w.getBlockState(cand.up()).isAir()
							&& (!stoneOnly || isStoneLike(w.getBlockState(cand)))) {
							return cand;
						}
					}
				}
			}
		}
		return null;
	}

	private static boolean isLiftableTerrain(ServerWorld w, BlockPos bp) {
		if (bp.getY() < w.getBottomY() + 1 || bp.getY() >= w.getTopY()) return false;
		BlockState st = w.getBlockState(bp);
		return !st.isAir() && !(st.getBlock() instanceof FluidBlock) && !st.hasBlockEntity()
			&& st.getHardness(w, bp) >= 0f && !st.getCollisionShape(w, bp).isEmpty();
	}

	private static boolean isStoneLike(BlockState st) {
		return st.isIn(BlockTags.BASE_STONE_OVERWORLD)
			|| st.isIn(BlockTags.BASE_STONE_NETHER)
			|| st.isIn(BlockTags.TERRACOTTA)
			|| st.isOf(Blocks.COBBLESTONE) || st.isOf(Blocks.MOSSY_COBBLESTONE) || st.isOf(Blocks.COBBLED_DEEPSLATE)
			|| st.isOf(Blocks.SANDSTONE) || st.isOf(Blocks.RED_SANDSTONE) || st.isOf(Blocks.BLACKSTONE)
			|| st.isOf(Blocks.BASALT) || st.isOf(Blocks.SMOOTH_BASALT) || st.isOf(Blocks.END_STONE)
			|| st.isOf(Blocks.STONE_BRICKS) || st.isOf(Blocks.BRICKS) || st.isOf(Blocks.OBSIDIAN)
			|| st.isOf(Blocks.CALCITE) || st.isOf(Blocks.DRIPSTONE_BLOCK);
	}

	private static boolean extractOreVeinByGravity(ServerPlayerEntity p, PlayerPower pw) {
		ServerWorld w = p.getServerWorld();
		BlockHitResult hit = PowerManager.raycastBlock(p, 48.0);
		if (hit.getType() != HitResult.Type.BLOCK || !SurgicalSurvival.isOre(w.getBlockState(hit.getBlockPos()))) {
			return false;
		}
		BlockPos seed = hit.getBlockPos();
		Block targetBlock = w.getBlockState(seed).getBlock();
		List<BlockPos> vein = new ArrayList<>();
		Set<Long> visited = new HashSet<>();
		Queue<BlockPos> q = new ArrayDeque<>();
		q.add(seed);
		visited.add(seed.asLong());
		int max = 14 + pw.masteryTier() * 5;
		while (!q.isEmpty() && vein.size() < max) {
			BlockPos cur = q.poll();
			BlockState st = w.getBlockState(cur);
			if (!SurgicalSurvival.isOre(st)) continue;
			vein.add(cur);
			for (int dx = -1; dx <= 1; dx++) for (int dy = -1; dy <= 1; dy++) for (int dz = -1; dz <= 1; dz++) {
				BlockPos nxt = cur.add(dx, dy, dz);
				if (visited.add(nxt.asLong()) && w.getBlockState(nxt).isOf(targetBlock)) q.add(nxt);
			}
		}
		if (vein.isEmpty()) return false;
		ItemStack pick = SurgicalSurvival.createPickaxe(w, Math.max(2, pw.masteryTier()));
		int total = 0;
		for (BlockPos bp : vein) {
			BlockState st = w.getBlockState(bp);
			int[] y = SurgicalSurvival.givePerfectOreYield(p, w, pw, bp, st, pick, false, false, false, null);
			total += y[0];
			w.setBlockState(bp, Blocks.AIR.getDefaultState(), Block.NOTIFY_LISTENERS);
		}
		Vec3d c = Vec3d.ofCenter(seed);
		sendFx(w, Payloads.GravityFx.KIND_HYPER_PRESS, p.getId(), c, p.getEyePos(), 3.0f, 1.3f, 22);
		playSound(w, c, SoundEvents.BLOCK_ANVIL_LAND, 0.8f, 0.9f);
		ExperienceOrbEntity.spawn(w, p.getPos(), Math.max(3, vein.size() * 2));
		p.sendMessage(Text.literal("§5⚙ Atracción Gravitatoria: §f¡" + vein.size() + " menas colapsadas → " + total + " minerales puros!"), true);
		return true;
	}

	// ------------------------------------------------------------------ Jigoku Tabi (C o golpe con Yakuza Kasen)

	public static void jigokuTabi(ServerPlayerEntity p, PlayerPower pw) {
		if (pw.onCooldown(Tech.GAMMA)) return;
		equipYakuzaKasenSafely(p);
		ServerWorld w = p.getServerWorld();
		Vec3d aim = PowerManager.aimPoint(p, 42.0);
		double R = 5.0;
		int oresCount = carveGravityCrater(p, pw, w, aim, R, 4);

		for (LivingEntity le : w.getEntitiesByClass(LivingEntity.class, new Box(aim, aim).expand(7.0), e -> e != p && e.isAlive())) {
			le.damage(ModDamage.of(w, ModDamage.GAMMA, p), 46f + pw.masteryTier() * 8f);
			le.addStatusEffect(new StatusEffectInstance(ModEffects.STUN, 70, 0, false, true));
			le.addVelocity(0, -2.8, 0);
			le.velocityModified = true;
		}

		ZONES.add(new ActiveZone(w.getRegistryKey(), p.getUuid(), aim, R + 1.5, 0, 70));
		sendFx(w, Payloads.GravityFx.KIND_PRESS_COLUMN, p.getId(), aim.add(0, 18, 0), aim, (float) R, 2.0f, 36);
		w.spawnParticles(ParticleTypes.EXPLOSION, aim.x, aim.y, aim.z, 2, 1.5, 0.5, 1.5, 0);
		playSound(w, p.getPos(), SoundEvents.ITEM_TRIDENT_HIT_GROUND, 1.0f, 1.7f); // Kachin!
		playSound(w, aim, SoundEvents.BLOCK_ANVIL_LAND, 1.6f, 0.42f);
		playSound(w, aim, SoundEvents.ENTITY_WARDEN_SONIC_BOOM, 1.2f, 0.52f);
		PowerManager.addBounty(p, 350_000L + oresCount * 30_000L);
		p.sendMessage(Text.literal(oresCount > 0
			? "§5⬇ Gravito: ¡Jigoku Tabi! §7(" + oresCount + " minerales puros separados en el abismo)"
			: "§5⬇ Gravito: ¡Jigoku Tabi! (Envainado «Kachin»)"), true);
		pw.cooldown(Tech.GAMMA);
	}

	private static int carveGravityCrater(ServerPlayerEntity p, PlayerPower pw, ServerWorld w, Vec3d center, double R, int depth) {
		BlockPos cb = BlockPos.ofFloored(center);
		BlockPos safePlayer = p.getBlockPos();
		int ri = (int) Math.ceil(R);
		int oresCount = 0;
		ItemStack fortuneTool = SurgicalSurvival.createPickaxe(w, Math.max(2, pw.masteryTier()));

		for (int dx = -ri; dx <= ri; dx++) {
			for (int dy = 2; dy >= -depth; dy--) {
				for (int dz = -ri; dz <= ri; dz++) {
					if (dx * dx + dz * dz > R * R) continue;
					BlockPos bp = cb.add(dx, dy, dz);
					if (bp.getY() < w.getBottomY() + 1 || bp.getY() >= w.getTopY()) continue;
					if (Math.abs(bp.getX() - safePlayer.getX()) <= 1 && Math.abs(bp.getZ() - safePlayer.getZ()) <= 1) continue;
					BlockState st = w.getBlockState(bp);
					if (st.isAir() || st.hasBlockEntity() || st.getHardness(w, bp) < 0) continue;

					if (SurgicalSurvival.isOre(st)) {
						int[] y = SurgicalSurvival.givePerfectOreYield(p, w, pw, bp, st, fortuneTool, false, false, false, null);
						oresCount += y[0];
					}
					if (dy >= 1 && w.random.nextInt(12) == 0) {
						FallingBlockEntity fb = FallingBlockEntity.spawnFromBlock(w, bp.toImmutable(), st);
						fb.setVelocity(dx * 0.08, -0.6, dz * 0.08);
						fb.velocityModified = true;
					} else {
						w.setBlockState(bp, Blocks.AIR.getDefaultState(), Block.NOTIFY_LISTENERS);
					}
				}
			}
		}
		return oresCount;
	}

	// ------------------------------------------------------------------ Moko (X — Gravedad Horizontal)

	public static void moko(ServerPlayerEntity p, PlayerPower pw) {
		if (pw.onCooldown(Tech.INJECTION)) return;
		equipYakuzaKasenSafely(p);
		ServerWorld w = p.getServerWorld();
		Vec3d eye = p.getEyePos();
		Vec3d look = p.getRotationVector();
		Vec3d end = eye.add(look.multiply(28.0));

		sendFx(w, Payloads.GravityFx.KIND_MOKO, p.getId(), eye.add(look.multiply(1.5)), end, 3.6f, 1.8f, 28);

		int hitCount = 0;
		List<LivingEntity> knocked = new ArrayList<>();
		for (double d = 2.0; d <= 28.0; d += 2.0) {
			Vec3d pt = eye.add(look.multiply(d));
			BlockPos bp = BlockPos.ofFloored(pt);
			for (int dx = -1; dx <= 1; dx++) {
				for (int dy = -1; dy <= 1; dy++) {
					for (int dz = -1; dz <= 1; dz++) {
						BlockPos q = bp.add(dx, dy, dz);
						BlockState st = w.getBlockState(q);
						if (st.isAir() || st.hasBlockEntity()) continue;
						if (st.isIn(BlockTags.LEAVES) || st.isIn(BlockTags.LOGS) || st.getHardness(w, q) <= 1.5f) {
							if (st.getHardness(w, q) >= 0) {
								if (w.random.nextInt(10) == 0 && !st.isIn(BlockTags.LEAVES)) {
									FallingBlockEntity fb = FallingBlockEntity.spawnFromBlock(w, q.toImmutable(), st);
									fb.setVelocity(look.multiply(1.6).add(0, 0.4, 0));
									fb.velocityModified = true;
								} else {
									w.breakBlock(q, st.isIn(BlockTags.LOGS), p);
								}
							}
						}
					}
				}
			}

			for (LivingEntity le : w.getEntitiesByClass(LivingEntity.class, new Box(pt, pt).expand(3.0),
				e -> e != p && e.isAlive() && !knocked.contains(e))) {
				knocked.add(le);
				le.damage(ModDamage.of(w, ModDamage.GAMMA, p), 38f + pw.masteryTier() * 8f);
				le.addVelocity(look.x * 3.4, 0.7 + look.y * 1.4, look.z * 3.4);
				le.velocityModified = true;
				le.addStatusEffect(new StatusEffectInstance(ModEffects.STUN, 50, 0, false, true));
				hitCount++;
			}
		}

		playSound(w, eye, SoundEvents.ENTITY_WARDEN_SONIC_BOOM, 1.5f, 0.72f);
		playSound(w, eye, SoundEvents.ENTITY_RAVAGER_ROAR, 1.1f, 0.6f);
		p.sendMessage(Text.literal("§5🐅 Gravito: ¡MOKO (Tigre Feroz)! §7(" + hitCount + " barridos horizontalmente)"), true);
		pw.cooldown(Tech.INJECTION);
	}

	// ------------------------------------------------------------------ Tengai Shinsei (Z — Meteorito Colosal Real desde la Estratosfera)

	public static void startMeteorCharge(ServerPlayerEntity p, PlayerPower pw) {
		if (pw.onCooldown(Tech.RADIO_KNIFE)) return;
		equipYakuzaKasenSafely(p);
		pw.meteorCharging = true;
		pw.meteorChargeTicks = 0;
		pw.meteorAimPos = meteorAimTarget(p, p.getServerWorld());
		pw.dirty = true;
		int t = MathHelper.clamp(pw.meteorTier, 0, METEOR_NAMES.length - 1);
		playSound(p.getServerWorld(), p.getPos(), SoundEvents.BLOCK_RESPAWN_ANCHOR_CHARGE, 0.85f, 0.55f + t * 0.1f);
		p.sendMessage(Text.literal("§5☄ TENGAI SHINSEI · §6[" + METEOR_NAMES[t] + "] §7(Rueda o mantén Z para agrandar hasta 85 m · Suelta para desplomar)"), true);
	}

	public static void adjustMeteorSize(ServerPlayerEntity p, PlayerPower pw, int dir) {
		pw.meteorTier = MathHelper.clamp(pw.meteorTier + (dir >= 0 ? 1 : -1), 0, METEOR_NAMES.length - 1);
		pw.meteorChargeTicks = 0; // reinicia auto-crecimiento si el jugador eligió manualmente con la rueda
		pw.dirty = true;
		int t = pw.meteorTier;
		playSound(p.getServerWorld(), p.getPos(), SoundEvents.BLOCK_AMETHYST_BLOCK_CHIME, 1.0f, 0.55f + t * 0.18f);
		p.sendMessage(Text.literal("§5☄ Tamaño del Meteorito: §6[" + METEOR_NAMES[t] + "] §7(Cráter " + Math.round(METEOR_CRATERS[t] * 2) + " m · Altura +" + Math.round(METEOR_HEIGHTS[t]) + " m)"), true);
	}

	public static void releaseMeteorCharge(ServerPlayerEntity p, PlayerPower pw, boolean triple) {
		if (!pw.meteorCharging) return;
		Vec3d aim = meteorAimTarget(p, p.getServerWorld());
		pw.meteorCharging = false;
		pw.meteorChargeTicks = 0;
		pw.dirty = true;
		launchMeteor(p, pw, aim, MathHelper.clamp(pw.meteorTier, 0, METEOR_NAMES.length - 1), triple);
	}

	public static void tengaiShinsei(ServerPlayerEntity p, PlayerPower pw, boolean triple) {
		if (pw.onCooldown(Tech.RADIO_KNIFE)) return;
		equipYakuzaKasenSafely(p);
		launchMeteor(p, pw, meteorAimTarget(p, p.getServerWorld()), MathHelper.clamp(pw.meteorTier, 0, METEOR_NAMES.length - 1), triple);
	}

	/** Punto real hacia el que apunta la trayectoria del meteorito (si miras al horizonte, proyecta al suelo de abajo). */
	private static Vec3d meteorAimTarget(ServerPlayerEntity p, ServerWorld w) {
		BlockHitResult hit = PowerManager.raycastBlock(p, 200.0);
		if (hit.getType() == HitResult.Type.BLOCK) {
			return hit.getPos();
		}
		Vec3d far = p.getEyePos().add(p.getRotationVector().multiply(115.0));
		int fx = MathHelper.floor(far.x), fz = MathHelper.floor(far.z);
		int topY = w.getTopY(net.minecraft.world.Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, fx, fz);
		double y = topY > w.getBottomY() + 1 ? topY : p.getY();
		return new Vec3d(far.x, y, far.z);
	}

	private static void launchMeteor(ServerPlayerEntity p, PlayerPower pw, Vec3d baseAim, int tier, boolean triple) {
		if (pw.onCooldown(Tech.RADIO_KNIFE)) return;
		ServerWorld w = p.getServerWorld();
		int count = triple ? 3 : 1;
		// En lluvia triple con Extinción, los 2 meteoritos laterales bajan 1 nivel para que sea apocalíptico y fluido
		for (int m = 0; m < count; m++) {
			int curTier = (triple && m > 0) ? Math.max(0, tier - 1) : tier;
			float visScale = METEOR_SCALES[curTier];
			double dropH = METEOR_HEIGHTS[curTier] + m * 28.0;
			double speed = METEOR_SPEEDS[curTier];
			double spread = Math.max(16.0, METEOR_CRATERS[curTier] * 0.9);
			double ox = m == 0 ? 0.0 : (m == 1 ? -spread : spread);
			double oz = m == 0 ? 0.0 : (m == 1 ? spread * 0.7 : -spread * 0.7);
			Vec3d target = baseAim.add(ox, 0, oz);

			// Ángulo oblicuo de entrada atmosférica desde MUY ARRIBA en el cielo (atravesando las nubes!)
			double slantAng = Math.toRadians(p.getYaw() + 180.0 + (m == 0 ? 18.0 : (m == 1 ? -28.0 : 35.0)));
			double slantDist = 48.0 + curTier * 14.0;
			Vec3d portalPos = new Vec3d(
				target.x + Math.cos(slantAng) * slantDist,
				target.y + dropH,
				target.z + Math.sin(slantAng) * slantDist
			);

			BlockSet set = buildMeteorBlockSet(w, 5, (int) (w.getTime() + m * 97));
			PieceEntity meteor = PieceEntity.spawn(w, set, portalPos.subtract(set.sx / 2.0, set.sy / 2.0, set.sz / 2.0), null, p.getUuid());
			meteor.setMeteorScale(visScale);
			meteor.thrown = true;
			meteor.ownerGraceTicks = 300;
			Vec3d plunge = target.subtract(portalPos).normalize().multiply(speed);
			meteor.setVelocity(plunge);
			meteor.velocityModified = true;

			float riftRad = 9.5f * visScale;
			sendFx(w, Payloads.GravityFx.KIND_METEOR_RIFT, p.getId(), p.getEyePos().add(p.getRotationVector()), portalPos,
				riftRad, 1.6f + curTier * 0.6f, 110 + curTier * 20);
			METEORS.add(new ActiveMeteor(w.getRegistryKey(), meteor.getId(), p.getUuid(), target, portalPos, curTier, 0));
		}

		playGlobalSound(w, baseAim.add(0, 60, 0), 380.0, SoundEvents.ENTITY_LIGHTNING_BOLT_THUNDER, 2.8f + tier * 0.4f, 0.44f);
		playGlobalSound(w, p.getPos(), 260.0, SoundEvents.BLOCK_RESPAWN_ANCHOR_DEPLETE.value(), 1.8f, 0.48f);
		playGlobalSound(w, baseAim.add(0, 80, 0), 380.0, SoundEvents.ENTITY_ENDER_DRAGON_GROWL, 2.0f, 0.42f);
		p.sendMessage(Text.literal(triple
			? "§5☄☄☄ ¡LLUVIA DE " + METEOR_NAMES[tier].toUpperCase() + " CAYENDO DEL ESPACIO!"
			: "§5☄ ¡TENGAI SHINSEI — " + METEOR_NAMES[tier].toUpperCase() + " CAYENDO DESDE LA ESTRATOSFERA!"), true);
		pw.cooldown(Tech.RADIO_KNIFE);
	}

	/**
	 * Esculpe un asteroide volcánico hueco e irregular (con grietas de magma, cráteres, basalto, piedra negra y menas).
	 * Al ser hueco (~350 bloques), se sincroniza al instante y luego se escala hasta 85 m en 3D sin lag.
	 */
	private static BlockSet buildMeteorBlockSet(ServerWorld w, int r, int seed) {
		BlockSet set = new BlockSet();
		for (int dx = -r; dx <= r; dx++) {
			for (int dy = -r; dy <= r; dy++) {
				for (int dz = -r; dz <= r; dz++) {
					double dist = Math.sqrt(dx * dx + dy * dy + dz * dz);
					// Relieve rugoso e irregular con cráteres orgánicos de asteroide real (jamás un cubo ni esfera perfecta)
					double bump = 0.72 * Math.sin(dx * 0.75 + seed) * Math.cos(dz * 0.72 - seed * 0.5)
						+ 0.55 * Math.cos(dy * 0.90 + dx * 0.45)
						- 0.35 * Math.sin((dx + dy + dz) * 0.65);
					double outer = r + bump * 0.75;
					double inner = outer - 1.55;
					if (dist > outer || dist < inner) continue;

					// Grietas de lava/magma ardiente cruzando el asteroide
					double crack = Math.abs(Math.sin(dx * 0.55 + dy * 0.65) + Math.cos(dz * 0.60 - dy * 0.45));
					BlockState st;
					if (crack < 0.28) {
						st = w.random.nextInt(5) == 0 ? Blocks.SHROOMLIGHT.getDefaultState() : Blocks.MAGMA_BLOCK.getDefaultState();
					} else if (crack < 0.44) {
						st = Blocks.CRYING_OBSIDIAN.getDefaultState();
					} else {
						int roll = Math.abs(dx * 17 + dy * 31 + dz * 13 + seed + w.random.nextInt(5)) % 9;
						st = switch (roll) {
							case 0 -> Blocks.MAGMA_BLOCK.getDefaultState();
							case 1 -> Blocks.ANCIENT_DEBRIS.getDefaultState();
							case 2 -> Blocks.DEEPSLATE_DIAMOND_ORE.getDefaultState();
							case 3, 4 -> Blocks.BASALT.getDefaultState();
							case 5 -> Blocks.COBBLED_DEEPSLATE.getDefaultState();
							default -> Blocks.BLACKSTONE.getDefaultState();
						};
					}
					set.add(dx + r + 1, dy + r + 1, dz + r + 1, st, null);
				}
			}
		}
		set.normalize();
		return set;
	}

	// ------------------------------------------------------------------ Haki Ciego (V), Repulsión (B) y Escombros de Dressrosa (Tab+V)

	public static void blindObservationHaki(ServerPlayerEntity p, PlayerPower pw) {
		if (pw.onCooldown(Tech.SCAN)) return;
		pw.scanTicks = 320;
		pw.dirty = true;
		p.addStatusEffect(new StatusEffectInstance(StatusEffects.NIGHT_VISION, 320, 0, false, false, true));
		p.addStatusEffect(new StatusEffectInstance(StatusEffects.SPEED, 320, 1, false, false, true));
		p.addStatusEffect(new StatusEffectInstance(StatusEffects.RESISTANCE, 320, 0, false, false, true));
		ServerWorld w = p.getServerWorld();
		sendFx(w, Payloads.GravityFx.KIND_LIFT_PULSE, p.getId(), p.getPos(), p.getPos().add(0, 6, 0), 5.0f, 1.0f, 22);
		playSound(w, p.getPos(), SoundEvents.BLOCK_AMETHYST_BLOCK_RESONATE, 1.3f, 1.55f);
		p.sendMessage(Text.literal("§d👁 Visión Ciega de Fujitora (Kenbunshoku Haki): §fAuras 3D y 45% esquiva activos"), true);
		pw.cooldown(Tech.SCAN);
	}

	public static void repulsionShield(ServerPlayerEntity p, PlayerPower pw) {
		if (pw.onCooldown(Tech.CURTAIN)) return;
		pw.curtainTicks = 200;
		pw.dirty = true;
		p.addStatusEffect(new StatusEffectInstance(StatusEffects.RESISTANCE, 200, 2, false, false, true));
		ServerWorld w = p.getServerWorld();
		sendFx(w, Payloads.GravityFx.KIND_SHIELD_BURST, p.getId(), p.getPos().add(0, 1.0, 0), p.getPos().add(0, 1.0, 0), 4.5f, 1.4f, 20);
		playSound(w, p.getPos(), SoundEvents.BLOCK_BEACON_ACTIVATE, 1.2f, 0.65f);
		p.sendMessage(Text.literal("§5🛡 Barrera de Repulsión Gravitatoria activa (10s)"), true);
		pw.cooldown(Tech.CURTAIN);
	}

	/** Escombros de Dressrosa (Tab+V / Tab+Z): arranca hasta 8 bloques gigantes alrededor SIN ROOM y los suspende en el cielo. */
	public static void dressrosaRubble(ServerPlayerEntity p, PlayerPower pw) {
		if (pw.onCooldown(Tech.RAIN)) return;
		equipYakuzaKasenSafely(p);
		ServerWorld w = p.getServerWorld();
		cleanHeld(w, pw);

		int spawned = 0;
		BlockPos pc = p.getBlockPos();
		for (int i = 0; i < 8 && pw.gravityHeld.size() < 12; i++) {
			double ang = i * (Math.PI * 2.0 / 8.0);
			int rx = pc.getX() + (int) Math.round(Math.cos(ang) * 9.0);
			int rz = pc.getZ() + (int) Math.round(Math.sin(ang) * 9.0);
			int topY = w.getTopY(net.minecraft.world.Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, rx, rz) - 1;
			if (topY < w.getBottomY() + 2 || Math.abs(topY - pc.getY()) > 16) continue;

			BlockSet set = new BlockSet();
			int blocks = 0;
			for (int dx = -1; dx <= 1; dx++) {
				for (int dy = -1; dy <= 0; dy++) {
					for (int dz = -1; dz <= 1; dz++) {
						BlockPos bp = new BlockPos(rx + dx, topY + dy, rz + dz);
						BlockState st = w.getBlockState(bp);
						if (st.isAir() || st.getBlock() instanceof FluidBlock || st.hasBlockEntity() || st.getHardness(w, bp) < 0) continue;
						set.add(dx + 1, dy + 1, dz + 1, st, null);
						w.setBlockState(bp, Blocks.AIR.getDefaultState(), Block.NOTIFY_LISTENERS);
						blocks++;
					}
				}
			}
			if (blocks == 0) continue;
			set.normalize();
			Vec3d at = new Vec3d(rx + 0.5, topY + 0.5, rz + 0.5);
			PieceEntity pe = PieceEntity.spawn(w, set, at.subtract(set.sx / 2.0, 0, set.sz / 2.0), new BlockPos(rx, topY, rz), p.getUuid());
			pe.control(at.add(0, 7.0, 0));
			pe.setFlag(PieceEntity.F_HELD, true);
			pe.setFlag(PieceEntity.F_SEAM, true);
			pw.gravityHeld.add(pe.getId());
			spawned++;
		}
		if (spawned == 0) {
			p.sendMessage(Text.literal("§7No hay terreno sólido cerca para arrancar escombros"), true);
			return;
		}
		pw.gravityHoldDist = 11.0;
		pw.dirty = true;
		sendFx(w, Payloads.GravityFx.KIND_LIFT_PULSE, p.getId(), p.getPos(), p.getPos().add(0, 14, 0), 11.0f, 1.8f, 34);
		playSound(w, p.getPos(), SoundEvents.ENTITY_WITHER_SPAWN, 0.75f, 0.6f);
		p.sendMessage(Text.literal("§5🌋 ¡Escombros de Dressrosa (" + spawned + " rocas) flotando! §7(Clic Izq. lanzar · Clic Der. estrellar)"), true);
		pw.cooldown(Tech.RAIN);
	}

	// ------------------------------------------------------------------ Supervivencia de Fujitora (SIN ROOM): Prensa (Shift+R) y Cristalización (Tab+C)

	public static void gravityMinePress(ServerPlayerEntity p, PlayerPower pw) {
		ServerWorld w = p.getServerWorld();
		cleanHeld(w, pw);
		// Si llevas rocas levitando con R: las prensa en el aire extrayendo 3×–4× minerales + cristalización
		if (!pw.gravityHeld.isEmpty()) {
			ItemStack fortuneTool = SurgicalSurvival.createPickaxe(w, Math.max(2, pw.masteryTier()));
			int broken = 0, oresYield = 0, crushedPieces = 0;
			Vec3d fxPos = p.getEyePos().add(p.getRotationVector().multiply(6.0));
			for (int id : new ArrayList<>(pw.gravityHeld)) {
				Entity e = w.getEntityById(id);
				if (!(e instanceof PieceEntity pe) || pe.blocks == null) continue;
				fxPos = pe.center();
				BlockPos ref = BlockPos.ofFloored(fxPos);
				for (BlockSet.Entry entry : pe.blocks.entries) {
					BlockState st = entry.state();
					if (st.isAir() || st.getBlock() instanceof FluidBlock) continue;
					if (SurgicalSurvival.isOre(st)) {
						oresYield += SurgicalSurvival.givePerfectOreYield(p, w, pw, ref, st, fortuneTool, false, false, false, null)[0];
					} else if (w.random.nextInt(3) == 0 && st.getBlock().asItem() != Items.AIR) {
						p.getInventory().offerOrDrop(new ItemStack(st.getBlock().asItem(), 1));
					}
					broken++;
				}
				pe.discard();
				pw.gravityHeld.remove((Integer) id);
				crushedPieces++;
			}
			if (crushedPieces > 0) {
				pw.dirty = true;
				p.getInventory().offerOrDrop(new ItemStack(Items.OBSIDIAN, crushedPieces));
				if (w.random.nextFloat() < 0.70f) p.getInventory().offerOrDrop(new ItemStack(Items.DIAMOND, 1));
				sendFx(w, Payloads.GravityFx.KIND_HYPER_PRESS, p.getId(), fxPos, fxPos, 4.5f, 1.8f, 24);
				playSound(w, fxPos, SoundEvents.BLOCK_ANVIL_LAND, 1.3f, 0.55f);
				p.sendMessage(Text.literal("§5⚙ Prensa Gravitatoria Aérea: §f¡" + broken + " bloques prensados → " + oresYield + " minerales + Obsidiana/Diamante!"), true);
				return;
			}
		}

		BlockHitResult hit = PowerManager.raycastBlock(p, 42.0);
		if (hit.getType() != HitResult.Type.BLOCK) {
			p.sendMessage(Text.literal("§7Apunta a piedra o una mena para prensarla con Gravedad"), true);
			return;
		}
		BlockPos seed = hit.getBlockPos();
		ItemStack fortuneTool = SurgicalSurvival.createPickaxe(w, Math.max(2, pw.masteryTier()));
		BlockPos feetBelow = p.getBlockPos().down();
		int crushed = 0, oresYield = 0, diamonds = 0;

		for (int dx = -2; dx <= 2; dx++) {
			for (int dy = -2; dy <= 2; dy++) {
				for (int dz = -2; dz <= 2; dz++) {
					if (dx * dx + dy * dy + dz * dz > 6) continue;
					BlockPos bp = seed.add(dx, dy, dz);
					if (bp.getY() < w.getBottomY() + 1 || bp.getY() >= w.getTopY()) continue;
					if (p.isOnGround() && bp.equals(feetBelow)) continue;
					BlockState st = w.getBlockState(bp);
					if (st.isAir() || st.getBlock() instanceof FluidBlock || st.hasBlockEntity() || st.getHardness(w, bp) < 0) continue;

					if (SurgicalSurvival.isOre(st)) {
						oresYield += SurgicalSurvival.givePerfectOreYield(p, w, pw, bp, st, fortuneTool, false, false, false, null)[0];
						if (st.isIn(BlockTags.COAL_ORES) && w.random.nextFloat() < 0.35f) {
							p.getInventory().offerOrDrop(new ItemStack(Items.DIAMOND, 1));
							diamonds++;
						}
					} else if (st.isOf(Blocks.STONE) || st.isOf(Blocks.COBBLESTONE) || st.isOf(Blocks.DEEPSLATE)) {
						if (w.random.nextInt(4) == 0) p.getInventory().offerOrDrop(new ItemStack(st.getBlock().asItem(), 1));
					} else {
						for (ItemStack d : Block.getDroppedStacks(st, w, bp, null, p, fortuneTool)) {
							if (!d.isEmpty()) p.getInventory().offerOrDrop(d);
						}
					}
					w.setBlockState(bp, Blocks.AIR.getDefaultState(), Block.NOTIFY_LISTENERS);
					crushed++;
				}
			}
		}

		if (crushed == 0) return;
		Vec3d c = Vec3d.ofCenter(seed);
		sendFx(w, Payloads.GravityFx.KIND_HYPER_PRESS, p.getId(), c, c, 3.8f, 1.5f, 22);
		playSound(w, c, SoundEvents.BLOCK_ANVIL_LAND, 1.0f, 0.55f);
		if (oresYield > 0) ExperienceOrbEntity.spawn(w, p.getPos().add(0, 0.5, 0), Math.max(2, oresYield / 2));
		p.sendMessage(Text.literal("§5⚙ Prensa Gravitatoria ×100G: §f¡" + crushed + " bloques aplastados · "
			+ oresYield + " minerales puros" + (diamonds > 0 ? " + " + diamonds + " Diamantes!" : "!")), true);
	}

	public static boolean gravityHyperCompression(ServerPlayerEntity p, PlayerPower pw) {
		ServerWorld w = p.getServerWorld();
		Vec3d aim = PowerManager.aimPoint(p, 28.0);
		List<ItemEntity> piles = w.getEntitiesByClass(ItemEntity.class, new Box(aim, aim).expand(5.0),
			ie -> ie.isAlive() && !ie.getStack().isEmpty());
		if (piles.isEmpty()) {
			p.sendMessage(Text.literal("§7Tira Carbón (×8 → Diamante), Piedra (×8 → Obsidiana) o Menas Crudas al suelo y pulsa Tab+C"), true);
			return false;
		}

		int diamonds = 0, obsidian = 0, ingots = 0;
		Vec3d center = piles.get(0).getPos().add(0, 0.4, 0);

		for (ItemEntity ie : piles) {
			ItemStack st = ie.getStack();
			if (st.isEmpty()) continue;

			if (st.isOf(Items.COAL) && st.getCount() >= 8) {
				int makes = st.getCount() / 8;
				st.decrement(makes * 8);
				if (st.isEmpty()) ie.discard();
				p.getInventory().offerOrDrop(new ItemStack(Items.DIAMOND, makes));
				diamonds += makes;
			} else if (st.isOf(Items.COAL_BLOCK)) {
				int makes = st.getCount();
				ie.discard();
				p.getInventory().offerOrDrop(new ItemStack(Items.DIAMOND, makes));
				diamonds += makes;
			} else if ((st.isOf(Items.COBBLESTONE) || st.isOf(Items.STONE) || st.isOf(Items.COBBLED_DEEPSLATE)) && st.getCount() >= 8) {
				int makes = st.getCount() / 8;
				st.decrement(makes * 8);
				if (st.isEmpty()) ie.discard();
				p.getInventory().offerOrDrop(new ItemStack(Items.OBSIDIAN, makes));
				obsidian += makes;
			} else {
				Optional<RecipeEntry<AbstractCookingRecipe>> smelted = w.getRecipeManager()
					.getFirstMatch(RecipeType.SMELTING, new SingleStackRecipeInput(st), w)
					.map(e -> (RecipeEntry<AbstractCookingRecipe>) (RecipeEntry<?>) e);
				if (smelted.isPresent()) {
					ItemStack out = smelted.get().value().getResult(w.getRegistryManager()).copy();
					if (!out.isEmpty()) {
						out.setCount(out.getCount() * st.getCount());
						ingots += out.getCount();
						ie.discard();
						p.getInventory().offerOrDrop(out);
					}
				}
			}
		}

		if (diamonds > 0 || obsidian > 0 || ingots > 0) {
			sendFx(w, Payloads.GravityFx.KIND_HYPER_PRESS, p.getId(), center, center, 2.8f, 1.4f, 22);
			playSound(w, center, SoundEvents.BLOCK_AMETHYST_BLOCK_CHIME, 1.3f, 1.8f);
			playSound(w, center, SoundEvents.BLOCK_ANVIL_USE, 0.9f, 1.4f);
			List<String> msg = new ArrayList<>();
			if (diamonds > 0) msg.add(diamonds + " Diamante(s)");
			if (obsidian > 0) msg.add(obsidian + " Obsidiana");
			if (ingots > 0) msg.add(ingots + " Lingotes");
			p.sendMessage(Text.literal("§5💎 Cristalización por Hiper-Gravedad: §f¡" + String.join(" · ", msg) + "!"), true);
			return true;
		}
		p.sendMessage(Text.literal("§7Tira Carbón (×8 → Diamante), Piedra (×8 → Obsidiana) o Menas Crudas al suelo"), true);
		return false;
	}

	private static void playSound(ServerWorld w, Vec3d pos, SoundEvent sound, float volume, float pitch) {
		w.playSound(null, pos.x, pos.y, pos.z, sound, SoundCategory.PLAYERS, volume, pitch);
	}
}
