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

	private record ActiveMeteor(RegistryKey<World> dim, int entityId, UUID ownerId, Vec3d target, Vec3d portalPos, int age) {}

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
	private static final List<ActiveZone> ZONES = new ArrayList<>();
	private static List<Block> cachedRareModOres = null;

	public static void clearAll() {
		METEORS.clear();
		ZONES.clear();
	}

	public static void sendFx(ServerWorld w, int kind, int userId, Vec3d a, Vec3d b, float radius, float power, int ticks) {
		Payloads.GravityFx fx = new Payloads.GravityFx(kind, userId, a.x, a.y, a.z, b.x, b.y, b.z, radius, power, ticks);
		for (ServerPlayerEntity sp : w.getPlayers()) {
			if (sp.squaredDistanceTo(a) <= 180 * 180 || sp.squaredDistanceTo(b) <= 180 * 180) {
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
			if (old instanceof PieceEntity pe && pe.isGravitySurf() && !pe.hasPassengers()) {
				pe.release(Vec3d.ZERO, false);
			}
			pw.gravityMountId = -1;
			pw.fallImmunityTicks = Math.max(pw.fallImmunityTicks, 140);
			pw.dirty = true;
		}
	}

	public static void tickServer(MinecraftServer server) {
		tickZones(server);
		tickMeteors(server);
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

			Vec3d mc = pe.center();
			w.spawnParticles(ParticleTypes.FLAME, mc.x, mc.y, mc.z, 22, 1.5, 1.5, 1.5, 0.10);
			w.spawnParticles(ParticleTypes.LARGE_SMOKE, mc.x, mc.y, mc.z, 12, 1.3, 1.3, 1.3, 0.05);
			w.spawnParticles(GRAVITY_DUST, mc.x, mc.y, mc.z, 14, 1.8, 1.8, 1.8, 0.02);

			boolean closeToTarget = mc.y <= am.target.y + 3.0;
			boolean hitGround = pe.isOnGround() || pe.getVelocity().lengthSquared() < 0.12 || closeToTarget;
			if ((am.age >= 6 && hitGround) || am.age > 120) {
				ServerPlayerEntity owner = server.getPlayerManager().getPlayer(am.ownerId);
				detonateMeteor(w, owner, pe, am.target);
				it.remove();
			} else {
				int idx = METEORS.indexOf(am);
				if (idx >= 0) {
					METEORS.set(idx, new ActiveMeteor(am.dim, am.entityId, am.ownerId, am.target, am.portalPos, am.age + 1));
				}
			}
		}
	}

	private static void detonateMeteor(ServerWorld w, ServerPlayerEntity owner, PieceEntity meteor, Vec3d impact) {
		Vec3d center = meteor.center();
		if (center.distanceTo(impact) > 10.0) impact = center;
		meteor.discard();

		BlockPos cb = BlockPos.ofFloored(impact);
		double R = 6.5;
		int ri = (int) Math.ceil(R);
		BlockPos safeCenter = owner != null ? owner.getBlockPos() : null;

		for (int dx = -ri; dx <= ri; dx++) {
			for (int dy = 3; dy >= -4; dy--) {
				for (int dz = -ri; dz <= ri; dz++) {
					double d2 = dx * dx + dy * dy * 1.35 + dz * dz;
					if (d2 > R * R) continue;
					BlockPos bp = cb.add(dx, dy, dz);
					if (bp.getY() < w.getBottomY() + 2 || bp.getY() >= w.getTopY()) continue;
					// Cono de repulsión protector de Fujitora
					if (safeCenter != null && Math.abs(bp.getX() - safeCenter.getX()) <= 1
						&& Math.abs(bp.getZ() - safeCenter.getZ()) <= 1 && bp.getY() <= safeCenter.getY()) {
						continue;
					}
					BlockState st = w.getBlockState(bp);
					if (st.isAir() || st.hasBlockEntity() || st.getHardness(w, bp) < 0) continue;

					if (SurgicalSurvival.isOre(st) && owner != null) {
						PlayerPower pw = PowerManager.get(owner);
						SurgicalSurvival.givePerfectOreYield(owner, w, pw, bp, st,
							SurgicalSurvival.createPickaxe(w, Math.max(1, pw.masteryTier())), false, true, false, null);
					}
					if (dy >= 1 && w.random.nextInt(14) == 0) {
						FallingBlockEntity fb = FallingBlockEntity.spawnFromBlock(w, bp.toImmutable(), st);
						Vec3d out = new Vec3d(dx, 2.5, dz).normalize().multiply(0.95);
						fb.setVelocity(out);
						fb.velocityModified = true;
					} else {
						w.setBlockState(bp, Blocks.AIR.getDefaultState(), Block.NOTIFY_LISTENERS);
					}
				}
			}
		}

		// Núcleo espacial rico en menas raras en el fondo del cráter
		BlockPos craterFloor = cb.down(3);
		List<Block> rareOres = rareModOres();
		BlockPos[] coreOffsets = {
			craterFloor, craterFloor.east(), craterFloor.west(), craterFloor.north(), craterFloor.south(), craterFloor.up()
		};
		BlockState[] spatialCore = {
			Blocks.ANCIENT_DEBRIS.getDefaultState(),
			Blocks.DEEPSLATE_DIAMOND_ORE.getDefaultState(),
			Blocks.RAW_GOLD_BLOCK.getDefaultState(),
			Blocks.RAW_IRON_BLOCK.getDefaultState(),
			rareOres.isEmpty() ? Blocks.DEEPSLATE_EMERALD_ORE.getDefaultState() : rareOres.get(w.random.nextInt(rareOres.size())).getDefaultState(),
			Blocks.MAGMA_BLOCK.getDefaultState()
		};
		for (int i = 0; i < coreOffsets.length; i++) {
			BlockPos op = coreOffsets[i];
			if (op.getY() > w.getBottomY() + 1 && w.getBlockState(op).isAir()) {
				w.setBlockState(op, spatialCore[i], Block.NOTIFY_LISTENERS);
			}
		}

		for (LivingEntity le : w.getEntitiesByClass(LivingEntity.class, new Box(impact, impact).expand(13.0), Entity::isAlive)) {
			if (le == owner) continue;
			float amt = owner != null ? 55f + PowerManager.get(owner).masteryTier() * 10f : 50f;
			le.damage(ModDamage.of(w, ModDamage.BURN, owner), amt);
			le.setOnFireFor(6);
			Vec3d away = le.getPos().subtract(impact);
			if (away.lengthSquared() < 0.1) away = new Vec3d(0, 1, 0);
			Vec3d push = away.normalize().multiply(2.2).add(0, 0.95, 0);
			le.addVelocity(push.x, push.y, push.z);
			le.velocityModified = true;
		}

		sendFx(w, Payloads.GravityFx.KIND_METEOR_IMPACT, owner != null ? owner.getId() : -1,
			impact, impact.add(0, 16, 0), 11.5f, 2.2f, 40);
		w.spawnParticles(ParticleTypes.EXPLOSION_EMITTER, impact.x, impact.y, impact.z, 4, 2.0, 1.0, 2.0, 0);
		w.spawnParticles(ParticleTypes.LAVA, impact.x, impact.y, impact.z, 70, 3.2, 1.2, 3.2, 0.2);
		playSound(w, impact, SoundEvents.ENTITY_GENERIC_EXPLODE.value(), 2.4f, 0.55f);
		playSound(w, impact, SoundEvents.ENTITY_DRAGON_FIREBALL_EXPLODE, 2.0f, 0.48f);
		if (owner != null) {
			PowerManager.addBounty(owner, 900_000L);
			owner.sendMessage(Text.literal("§5☄ ¡Impacto de Tengai Shinsei! §7(Núcleo espacial depositado en el cráter)"), true);
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

		// Arranca una losa real 3×1×3 bajo tus pies y te sube DE PIE sobre ella: LA ROCA TE LLEVA (sin vuelo del jugador)
		BlockSet set = new BlockSet();
		BlockPos base = p.getBlockPos().down();
		for (int dx = -1; dx <= 1; dx++) {
			for (int dz = -1; dz <= 1; dz++) {
				BlockPos bp = base.add(dx, 0, dz);
				BlockState st = w.getBlockState(bp);
				BlockState sample = (!st.isAir() && !(st.getBlock() instanceof FluidBlock) && !st.hasBlockEntity() && st.getHardness(w, bp) >= 0)
					? st : ((dx + dz) % 2 == 0 ? Blocks.DEEPSLATE.getDefaultState() : Blocks.COBBLESTONE.getDefaultState());
				if (!st.isAir() && st == sample) {
					w.setBlockState(bp, Blocks.AIR.getDefaultState(), Block.NOTIFY_LISTENERS);
				}
				set.add(dx + 1, 0, dz + 1, sample, null);
			}
		}
		set.normalize();
		// Coloca la losa limpia por encima del suelo (o dentro del hueco recién arrancado si hay techo bajo) y monta a Fujitora de pie encima
		double spawnY = p.isOnGround() ? p.getY() + 0.08 : p.getY() - 0.85;
		Vec3d spawnPos = new Vec3d(p.getX(), spawnY, p.getZ());
		PieceEntity plat = PieceEntity.spawn(w, set, spawnPos.subtract(set.sx / 2.0, 0, set.sz / 2.0), base, p.getUuid());
		plat.setYaw(p.getYaw());
		plat.setGravitySurf(true);
		if (!w.isSpaceEmpty(plat) && p.isOnGround()) {
			plat.setPosition(p.getX(), base.getY() + 0.04, p.getZ());
		}
		plat.setFlag(PieceEntity.F_SEAM, true);
		plat.setVelocity(0.0, 0.28, 0.0);

		// Desactiva cualquier vuelo propio (incluso en creativo) para que sea la ROCA quien te lleve físicamente
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
		sendFx(w, Payloads.GravityFx.KIND_LIFT_PULSE, p.getId(), spawnPos, spawnPos.add(0, 3.5, 0), 2.8f, 1.0f, 20);
		playSound(w, spawnPos, SoundEvents.BLOCK_RESPAWN_ANCHOR_CHARGE, 0.9f, 0.7f);
		playSound(w, spawnPos, SoundEvents.BLOCK_AMETHYST_BLOCK_RESONATE, 1.2f, 0.6f);
		p.sendMessage(Text.literal("§5🪨 ¡La roca te eleva! §7(WASD dirigir · Espacio subir · Ctrl turbo · Mayús bajar · Alt lanzar)"), true);
		pw.cooldown(Tech.SHAMBLES);
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

	// ------------------------------------------------------------------ Tengai Shinsei (Z — Meteorito Real desde el Espacio)

	public static void tengaiShinsei(ServerPlayerEntity p, PlayerPower pw, boolean triple) {
		if (pw.onCooldown(Tech.RADIO_KNIFE)) return;
		equipYakuzaKasenSafely(p);
		ServerWorld w = p.getServerWorld();
		Vec3d baseAim = PowerManager.aimPoint(p, 85.0);
		int count = triple ? 3 : 1;

		for (int m = 0; m < count; m++) {
			double ox = m == 0 ? 0.0 : (m == 1 ? -10.0 : 10.0);
			double oz = m == 0 ? 0.0 : (m == 1 ? 7.0 : -7.0);
			Vec3d target = baseAim.add(ox, 0, oz);
			Vec3d portalPos = new Vec3d(target.x + (w.random.nextDouble() - 0.5) * 6.0,
				Math.min(w.getTopY() - 8.0, target.y + 52.0 + m * 6.0),
				target.z + (w.random.nextDouble() - 0.5) * 6.0);

			BlockSet set = buildMeteorBlockSet(w, m == 0 ? 2 : 1);
			PieceEntity meteor = PieceEntity.spawn(w, set, portalPos.subtract(set.sx / 2.0, set.sy / 2.0, set.sz / 2.0), null, p.getUuid());
			meteor.mode = PieceEntity.Mode.FREE;
			meteor.thrown = true;
			meteor.ownerGraceTicks = 200;
			Vec3d plunge = target.subtract(portalPos).normalize().multiply(2.25);
			meteor.setVelocity(plunge);
			meteor.velocityModified = true;

			sendFx(w, Payloads.GravityFx.KIND_METEOR_RIFT, p.getId(), p.getEyePos().add(p.getRotationVector()), portalPos, 8.5f, 1.8f, 64);
			METEORS.add(new ActiveMeteor(w.getRegistryKey(), meteor.getId(), p.getUuid(), target, portalPos, 0));
		}

		playSound(w, baseAim.add(0, 20, 0), SoundEvents.ENTITY_LIGHTNING_BOLT_THUNDER, 2.2f, 0.52f);
		playSound(w, p.getPos(), SoundEvents.BLOCK_RESPAWN_ANCHOR_DEPLETE.value(), 1.4f, 0.55f);
		p.sendMessage(Text.literal(triple
			? "§5☄☄☄ ¡LLUVIA DE METEORITOS ESPACIALES!"
			: "§5☄ ¡Tengai Shinsei: Grieta espacial abierta — meteorito cayendo!"), true);
		pw.cooldown(Tech.RADIO_KNIFE);
	}

	private static BlockSet buildMeteorBlockSet(ServerWorld w, int r) {
		BlockSet set = new BlockSet();
		for (int dx = -r; dx <= r; dx++) {
			for (int dy = -r; dy <= r; dy++) {
				for (int dz = -r; dz <= r; dz++) {
					double d2 = dx * dx + dy * dy + dz * dz;
					if (d2 > r * r + 1.0) continue;
					BlockState st;
					if (d2 <= 1.2) {
						st = (dx == 0 && dy == 0 && dz == 0) ? Blocks.ANCIENT_DEBRIS.getDefaultState() : Blocks.RAW_GOLD_BLOCK.getDefaultState();
					} else {
						int roll = Math.abs(dx * 17 + dy * 31 + dz * 13 + w.random.nextInt(5)) % 6;
						st = switch (roll) {
							case 0 -> Blocks.MAGMA_BLOCK.getDefaultState();
							case 1 -> Blocks.CRYING_OBSIDIAN.getDefaultState();
							case 2 -> Blocks.DEEPSLATE_DIAMOND_ORE.getDefaultState();
							case 3 -> Blocks.BASALT.getDefaultState();
							default -> Blocks.BLACKSTONE.getDefaultState();
						};
					}
					set.add(dx + r, dy + r, dz + r, st, null);
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
