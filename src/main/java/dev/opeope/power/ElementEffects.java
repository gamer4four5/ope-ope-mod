package dev.opeope.power;

import dev.opeope.OpeConfig;
import dev.opeope.entity.PieceEntity;
import dev.opeope.registry.ModDamage;
import dev.opeope.registry.ModEffects;
import dev.opeope.terrain.MaterialTrait;
import net.minecraft.block.AbstractFireBlock;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.entity.passive.TameableEntity;
import net.minecraft.fluid.Fluids;
import net.minecraft.particle.BlockStateParticleEffect;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.tag.BlockTags;
import net.minecraft.registry.tag.FluidTags;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Efectos elementales y físicos compartidos entre armas forjadas (Forge) y trozos cortados/lanzados (Takt).
 * Todo cálculo de estado corre en servidor; para MaterialTrait.NONE todos los hooks son no-op.
 */
public final class ElementEffects {
	private ElementEffects() {}

	private record EphemeralFire(RegistryKey<World> dim, BlockPos pos, long expireTick) {}
	private record ChillEntry(RegistryKey<World> dim, int remainingTicks) {}
	private record OverloadEntry(RegistryKey<World> dim, int remainingTicks) {}

	private static final List<EphemeralFire> EPHEMERAL_FIRES = new ArrayList<>();
	private static final Map<UUID, ChillEntry> CHILL = new HashMap<>();
	private static final Map<Integer, OverloadEntry> OVERLOADS = new HashMap<>();

	public static void clearAll() {
		EPHEMERAL_FIRES.clear();
		CHILL.clear();
		OVERLOADS.clear();
	}

	public static boolean spares(@Nullable Entity owner, @Nullable UUID ownerId, LivingEntity target) {
		if (target == null) return true;
		if (owner != null && target == owner) return true;
		if (ownerId != null && target.getUuid().equals(ownerId)) return true;
		return target instanceof TameableEntity tam && ownerId != null && ownerId.equals(tam.getOwnerUuid());
	}

	public static float flightDamageMult(PieceEntity pe, LivingEntity le) {
		return pe.trait() == MaterialTrait.HEAVY_PIERCING ? OpeConfig.HEAVY_DMG_MULT : 1.0f;
	}

	public static float weaponDamageMult(PieceEntity pe, LivingEntity le) {
		return switch (pe.trait()) {
			case CONDUCTIVE -> (le.isTouchingWaterOrRain() || le.isWet() || le.hasStatusEffect(ModEffects.STUN)) ? 1.2f : 1.0f;
			case HEAVY_PIERCING -> OpeConfig.HEAVY_DMG_MULT;
			default -> 1.0f;
		};
	}

	public static void onFlightHit(PieceEntity pe, LivingEntity le, float finalDmg, Vec3d vel) {
		MaterialTrait tr = pe.trait();
		if (tr == MaterialTrait.NONE || !(pe.getWorld() instanceof ServerWorld w)) return;
		Vec3d c = le.getBoundingBox().getCenter();
		switch (tr) {
			case CONDUCTIVE -> {
				le.addStatusEffect(new StatusEffectInstance(ModEffects.STUN, 10, 0, false, true));
				w.spawnParticles(ParticleTypes.ELECTRIC_SPARK, c.x, c.y, c.z, 22, 0.35, 0.45, 0.35, 0.35);
			}
			case IGNEOUS -> {
				if (OpeConfig.ELEMENT_INTERACTIONS && CHILL.remove(le.getUuid()) != null) {
					steamFx(w, c);
					return;
				}
				if (!le.isFireImmune()) le.setOnFireFor(4.0f);
				w.spawnParticles(ParticleTypes.FLAME, c.x, c.y, c.z, 16, 0.35, 0.45, 0.35, 0.08);
			}
			case CRYO -> {
				if (OpeConfig.ELEMENT_INTERACTIONS && le.isOnFire()) {
					le.extinguish();
					steamFx(w, c);
				}
				le.addStatusEffect(new StatusEffectInstance(StatusEffects.SLOWNESS, 60, 1));
				applyChill(le, 40);
				w.spawnParticles(ParticleTypes.SNOWFLAKE, c.x, c.y, c.z, 20, 0.35, 0.45, 0.35, 0.08);
			}
			case HEAVY_PIERCING -> {
				BlockState dom = pe.blocks != null ? pe.blocks.dominant() : Blocks.OBSIDIAN.getDefaultState();
				if (dom != null) {
					w.spawnParticles(new BlockStateParticleEffect(ParticleTypes.BLOCK, dom), c.x, c.y, c.z, 18, 0.3, 0.4, 0.3, 0.2);
				}
				w.playSound(null, c.x, c.y, c.z, SoundEvents.BLOCK_ANVIL_LAND, SoundCategory.PLAYERS, 0.65f, 0.55f);
			}
			case NONE -> {}
		}
	}

	public static void onWeaponStrike(PieceEntity pe, LivingEntity le, float finalDmg) {
		MaterialTrait tr = pe.trait();
		if (tr == MaterialTrait.NONE || !(pe.getWorld() instanceof ServerWorld w)) return;
		Vec3d c = le.getBoundingBox().getCenter();
		switch (tr) {
			case CONDUCTIVE -> {
				le.addStatusEffect(new StatusEffectInstance(ModEffects.STUN, 15, 0, false, true));
				w.spawnParticles(ParticleTypes.ELECTRIC_SPARK, c.x, c.y, c.z, 28, 0.4, 0.5, 0.4, 0.4);
				ServerPlayerEntity owner = pe.ownerPlayer();
				if (owner != null && pe.age - pe.lastChainAge >= 20) {
					int hit = Conduction.miniChainFromStrike(owner, PowerManager.get(owner), pe, le, finalDmg * 0.4f, 20);
					if (hit > 0) pe.lastChainAge = pe.age;
				}
			}
			case IGNEOUS -> {
				if (OpeConfig.ELEMENT_INTERACTIONS && CHILL.remove(le.getUuid()) != null) {
					steamFx(w, c);
					return;
				}
				if (!le.isFireImmune()) le.setOnFireFor(4.0f);
				w.spawnParticles(ParticleTypes.FLAME, c.x, c.y, c.z, 18, 0.4, 0.5, 0.4, 0.08);
			}
			case CRYO -> {
				if (OpeConfig.ELEMENT_INTERACTIONS && le.isOnFire()) {
					le.extinguish();
					steamFx(w, c);
				}
				le.addStatusEffect(new StatusEffectInstance(StatusEffects.SLOWNESS, 60, 1));
				applyChill(le, 40);
				w.spawnParticles(ParticleTypes.SNOWFLAKE, c.x, c.y, c.z, 20, 0.4, 0.5, 0.4, 0.08);
			}
			case HEAVY_PIERCING -> {
				BlockState dom = pe.blocks != null ? pe.blocks.dominant() : Blocks.OBSIDIAN.getDefaultState();
				if (dom != null) {
					w.spawnParticles(new BlockStateParticleEffect(ParticleTypes.BLOCK, dom), c.x, c.y, c.z, 20, 0.35, 0.45, 0.35, 0.22);
				}
			}
			case NONE -> {}
		}
	}

	public static void onImpaled(PieceEntity pe, LivingEntity le, int stuckTicks) {
		MaterialTrait tr = pe.trait();
		if (tr == MaterialTrait.NONE || !(pe.getWorld() instanceof ServerWorld w)) return;
		switch (tr) {
			case IGNEOUS -> {
				if (stuckTicks % 40 == 0 && !le.isFireImmune()) {
					if (OpeConfig.ELEMENT_INTERACTIONS && CHILL.remove(le.getUuid()) != null) {
						steamFx(w, le.getBoundingBox().getCenter());
					} else {
						le.setOnFireFor(4.0f);
					}
				}
			}
			case CRYO -> {
				le.addStatusEffect(new StatusEffectInstance(StatusEffects.SLOWNESS, 20, 4, false, false));
				applyChill(le, 20);
			}
			default -> {}
		}
	}

	public static void onWallPlantedTick(PieceEntity pe, ServerPlayerEntity owner) {
		MaterialTrait tr = pe.trait();
		if (tr != MaterialTrait.IGNEOUS && tr != MaterialTrait.CRYO) return;
		if (pe.age % 20 != 0 || !(pe.getWorld() instanceof ServerWorld w)) return;
		double exp = tr == MaterialTrait.CRYO ? 2.0 : 0.65;
		for (LivingEntity le : w.getEntitiesByClass(LivingEntity.class, pe.getBoundingBox().expand(exp),
			e -> e.isAlive() && !e.isSpectator() && !spares(owner, pe.owner, e))) {
			if (tr == MaterialTrait.IGNEOUS) {
				if (!le.isFireImmune()) le.setOnFireFor(2.0f);
			} else {
				le.addStatusEffect(new StatusEffectInstance(StatusEffects.SLOWNESS, 35, 0));
				applyChill(le, 25);
			}
		}
		if (tr == MaterialTrait.CRYO) {
			Box b = pe.getBoundingBox().expand(1.5, 0.5, 1.5);
			int minX = MathHelper.floor(b.minX), maxX = MathHelper.floor(b.maxX);
			int minY = MathHelper.floor(b.minY) - 1, maxY = MathHelper.floor(b.minY) + 1;
			int minZ = MathHelper.floor(b.minZ), maxZ = MathHelper.floor(b.maxZ);
			int count = 0;
			BlockPos.Mutable m = new BlockPos.Mutable();
			for (int x = minX; x <= maxX && count < 16; x++) {
				for (int y = minY; y <= maxY && count < 16; y++) {
					for (int z = minZ; z <= maxZ && count < 16; z++) {
						m.set(x, y, z);
						if (freezeWaterCell(w, m)) count++;
					}
				}
			}
		}
	}

	public static void onHammerSlam(PieceEntity pe, ServerWorld w, Vec3d hit, double r) {
		MaterialTrait tr = pe.trait();
		if (tr == MaterialTrait.IGNEOUS) {
			double Rt = MathHelper.clamp(r * 0.5, 2.0, 9.0);
			igneousBlast(pe, w, hit, 0.55, Rt, false);
		} else if (tr == MaterialTrait.CRYO) {
			double Rc = MathHelper.clamp(r * 0.6, 2.5, 10.0);
			cryoBlast(pe, w, hit, 0.55, Rc);
		}
	}

	public static void onImpact(PieceEntity pe, ServerWorld w, Vec3d contact, int tier, double k, double r) {
		int cnt = pe.blocks != null ? pe.blocks.size() : 1;
		onImpact(pe, w, pe.trait(), cnt, contact, tier, k, r);
	}

	public static void onImpact(PieceEntity pe, ServerWorld w, MaterialTrait tr, int blockCount, Vec3d contact, int tier, double k, double r) {
		if (tr == null || tr == MaterialTrait.NONE) return;
		int n = Math.max(1, blockCount);
		if (tr == MaterialTrait.IGNEOUS) {
			double Rt = MathHelper.clamp(2.0 + 2.5 * k + 0.5 * Math.cbrt(n), 2.0, 9.0);
			igneousBlast(pe, w, contact, k, Rt, OpeConfig.IGNEOUS_PLACE_FIRE);
		} else if (tr == MaterialTrait.CRYO) {
			double Rc = MathHelper.clamp(2.5 + 3.0 * k + 0.5 * Math.cbrt(n), 2.5, 10.0);
			cryoBlast(pe, w, contact, k, Rc);
		} else if (tr == MaterialTrait.CONDUCTIVE) {
			Vec3d c = pe.center();
			w.spawnParticles(ParticleTypes.ELECTRIC_SPARK, c.x, c.y, c.z, 35, Math.max(0.5, r * 0.4), 0.5, Math.max(0.5, r * 0.4), 0.35);
		}
	}

	private static void igneousBlast(PieceEntity pe, ServerWorld w, Vec3d contact, double k, double Rt, boolean placeFire) {
		BlockPos cb = BlockPos.ofFloored(contact);
		int waterCells = 0;
		BlockPos.Mutable m = new BlockPos.Mutable();
		for (int dx = -2; dx <= 2; dx++) {
			for (int dy = -1; dy <= 1; dy++) {
				for (int dz = -2; dz <= 2; dz++) {
					m.set(cb.getX() + dx, cb.getY() + dy, cb.getZ() + dz);
					if (w.getFluidState(m).isIn(FluidTags.WATER)) waterCells++;
				}
			}
		}
		boolean wet = waterCells >= 3;
		float wetMult = wet ? 0.5f : 1.0f;
		if (wet) {
			steamFx(w, contact);
			placeFire = false;
		}

		ServerPlayerEntity owner = pe.ownerPlayer();
		Entity attacker = owner != null ? owner : pe;
		UUID ownerId = pe.mover() != null ? pe.mover() : pe.owner;
		for (LivingEntity le : w.getEntitiesByClass(LivingEntity.class, new Box(contact, contact).expand(Rt),
			e -> e.isAlive() && !e.isSpectator() && !spares(owner, ownerId, e))) {
			double d = le.getPos().distanceTo(contact);
			if (d > Rt) continue;
			if (OpeConfig.ELEMENT_INTERACTIONS && CHILL.remove(le.getUuid()) != null) {
				steamFx(w, le.getBoundingBox().getCenter());
				continue;
			}
			if (le.isFireImmune()) continue;
			float fireSec = (float) ((3.0 + 5.0 * k) * wetMult);
			le.setOnFireFor(fireSec);
			double falloff = Math.max(0.0, 1.0 - d / Rt);
			float burnDmg = (float) ((4.0 + 8.0 * k) * falloff * wetMult);
			if (burnDmg > 0.5f) {
				le.timeUntilRegen = 0;
				le.damage(ModDamage.of(w, ModDamage.BURN, attacker), burnDmg);
			}
		}

		// FX térmico
		w.spawnParticles(ParticleTypes.FLAME, contact.x, contact.y + 0.4, contact.z, 55, Rt * 0.45, 0.35, Rt * 0.45, 0.14);
		w.spawnParticles(ParticleTypes.LAVA, contact.x, contact.y + 0.5, contact.z, 24, Rt * 0.35, 0.3, Rt * 0.35, 0.08);
		w.spawnParticles(ParticleTypes.LARGE_SMOKE, contact.x, contact.y + 0.6, contact.z, 28, Rt * 0.4, 0.4, Rt * 0.4, 0.05);
		w.spawnParticles(ParticleTypes.SMALL_FLAME, contact.x, contact.y + 0.3, contact.z, 40, Rt * 0.5, 0.25, Rt * 0.5, 0.08);
		w.spawnParticles(ParticleTypes.FLASH, contact.x, contact.y + 0.6, contact.z, 2, 0, 0, 0, 0);
		w.playSound(null, contact.x, contact.y, contact.z, SoundEvents.ENTITY_GENERIC_EXPLODE.value(), SoundCategory.BLOCKS, 1.4f, 0.62f);
		w.playSound(null, contact.x, contact.y, contact.z, SoundEvents.BLOCK_FIRE_AMBIENT, SoundCategory.BLOCKS, 1.2f, 0.9f);

		if (placeFire) {
			double rf = Rt * 0.6;
			int ri = MathHelper.ceil(rf);
			List<BlockPos> candidates = new ArrayList<>();
			for (int dx = -ri; dx <= ri; dx++) {
				for (int dz = -ri; dz <= ri; dz++) {
					if (dx * dx + dz * dz > rf * rf) continue;
					for (int dy = -2; dy <= 3; dy++) {
						BlockPos pos = cb.add(dx, dy, dz);
						if (!w.getBlockState(pos).isAir()) continue;
						BlockPos down = pos.down();
						if (!w.getBlockState(down).isSideSolidFullSquare(w, down, Direction.UP)) continue;
						if (hasBurnableNear(w, pos)) continue;
						candidates.add(pos);
						break;
					}
				}
			}
			Collections.shuffle(candidates);
			int placed = 0;
			long exp = w.getTime() + OpeConfig.IGNEOUS_FIRE_TTL;
			for (BlockPos pos : candidates) {
				if (placed >= OpeConfig.IGNEOUS_MAX_FIRES) break;
				BlockState fireState = AbstractFireBlock.getState(w, pos);
				if (fireState.canPlaceAt(w, pos)) {
					w.setBlockState(pos, fireState, Block.NOTIFY_ALL | Block.SKIP_DROPS);
					EPHEMERAL_FIRES.add(new EphemeralFire(w.getRegistryKey(), pos, exp));
					placed++;
				}
			}
		}
	}

	private static boolean hasBurnableNear(ServerWorld w, BlockPos center) {
		BlockPos.Mutable m = new BlockPos.Mutable();
		for (int dx = -1; dx <= 1; dx++) {
			for (int dy = -1; dy <= 1; dy++) {
				for (int dz = -1; dz <= 1; dz++) {
					m.set(center.getX() + dx, center.getY() + dy, center.getZ() + dz);
					if (w.getBlockState(m).isBurnable()) return true;
				}
			}
		}
		return false;
	}

	private static void cryoBlast(PieceEntity pe, ServerWorld w, Vec3d contact, double k, double Rc) {
		BlockPos cb = BlockPos.ofFloored(contact);
		int ri = MathHelper.ceil(Rc);
		int frozen = 0;
		BlockPos.Mutable m = new BlockPos.Mutable();
		for (int dx = -ri; dx <= ri; dx++) {
			for (int dz = -ri; dz <= ri; dz++) {
				for (int dy = -ri; dy <= ri; dy++) {
					if (dx * dx + dy * dy + dz * dz > Rc * Rc) continue;
					m.set(cb.getX() + dx, cb.getY() + dy, cb.getZ() + dz);
					BlockState st = w.getBlockState(m);
					if (st.isIn(BlockTags.FIRE)) {
						w.setBlockState(m, Blocks.AIR.getDefaultState(), Block.NOTIFY_ALL | Block.SKIP_DROPS);
						continue;
					}
					if (frozen < OpeConfig.CRYO_MAX_FREEZE && freezeWaterCell(w, m)) {
						frozen++;
					}
				}
			}
		}

		ServerPlayerEntity owner = pe.ownerPlayer();
		Entity attacker = owner != null ? owner : pe;
		UUID ownerId = pe.mover() != null ? pe.mover() : pe.owner;
		for (LivingEntity le : w.getEntitiesByClass(LivingEntity.class, new Box(contact, contact).expand(Rc),
			e -> e.isAlive() && !e.isSpectator() && !spares(owner, ownerId, e))) {
			double d = le.getPos().distanceTo(contact);
			if (d > Rc) continue;
			if (le.isOnFire()) {
				le.extinguish();
				if (OpeConfig.ELEMENT_INTERACTIONS) steamFx(w, le.getBoundingBox().getCenter());
			}
			int slowDur = (int) Math.round(60 + 60 * k);
			le.addStatusEffect(new StatusEffectInstance(StatusEffects.SLOWNESS, slowDur, 3));
			int chillDur = (int) Math.round(40 + 60 * k);
			applyChill(le, chillDur);
			double falloff = Math.max(0.0, 1.0 - d / Rc);
			float frostDmg = (float) ((3.0 + 6.0 * k) * falloff);
			if (frostDmg > 0.5f) {
				le.timeUntilRegen = 0;
				le.damage(ModDamage.of(w, ModDamage.FROST, attacker), frostDmg);
			}
		}

		w.spawnParticles(ParticleTypes.SNOWFLAKE, contact.x, contact.y + 0.5, contact.z, 80, Rc * 0.5, 0.5, Rc * 0.5, 0.08);
		w.spawnParticles(ParticleTypes.WHITE_ASH, contact.x, contact.y + 0.6, contact.z, 60, Rc * 0.5, 0.45, Rc * 0.5, 0.04);
		w.spawnParticles(ParticleTypes.ITEM_SNOWBALL, contact.x, contact.y + 0.5, contact.z, 40, Rc * 0.45, 0.4, Rc * 0.45, 0.25);
		w.playSound(null, contact.x, contact.y, contact.z, SoundEvents.BLOCK_GLASS_BREAK, SoundCategory.BLOCKS, 1.4f, 0.85f);
		w.playSound(null, contact.x, contact.y, contact.z, SoundEvents.BLOCK_POWDER_SNOW_BREAK, SoundCategory.BLOCKS, 1.4f, 0.8f);
	}

	private static boolean freezeWaterCell(ServerWorld w, BlockPos pos) {
		BlockState st = w.getBlockState(pos);
		if (!st.isOf(Blocks.WATER) || !st.getFluidState().isStill() || !st.getFluidState().isOf(Fluids.WATER)) return false;
		if (!w.getBlockState(pos.up()).isAir()) return false;
		BlockState ice = OpeConfig.CRYO_PERMANENT_ICE ? Blocks.ICE.getDefaultState() : Blocks.FROSTED_ICE.getDefaultState();
		w.setBlockState(pos, ice, Block.NOTIFY_ALL | Block.SKIP_DROPS);
		if (!OpeConfig.CRYO_PERMANENT_ICE) {
			w.scheduleBlockTick(pos, Blocks.FROSTED_ICE, MathHelper.nextBetween(w.getRandom(), 60, 120));
		}
		return true;
	}

	private static void steamFx(ServerWorld w, Vec3d pos) {
		w.spawnParticles(ParticleTypes.CLOUD, pos.x, pos.y + 0.5, pos.z, 26, 0.7, 0.5, 0.7, 0.05);
		w.playSound(null, pos.x, pos.y, pos.z, SoundEvents.BLOCK_FIRE_EXTINGUISH, SoundCategory.BLOCKS, 0.9f, 1.1f);
	}

	public static void applyChill(LivingEntity le, int durationTicks) {
		if (!le.isAlive() || durationTicks <= 0) return;
		int minFreeze = le.getMinFreezeDamageTicks() + 2;
		if (le.canFreeze()) {
			le.setFrozenTicks(Math.max(le.getFrozenTicks(), minFreeze));
		}
		if (le.getWorld() instanceof ServerWorld sw) {
			CHILL.merge(le.getUuid(), new ChillEntry(sw.getRegistryKey(), durationTicks),
				(a, b) -> new ChillEntry(b.dim(), Math.max(a.remainingTicks(), b.remainingTicks())));
		}
	}

	public static void markConductiveOverload(PieceEntity pe, int ticks) {
		if (pe.getWorld() instanceof ServerWorld sw) {
			OVERLOADS.put(pe.getId(), new OverloadEntry(sw.getRegistryKey(), ticks));
		}
	}

	public static void trailFx(PieceEntity pe, ServerWorld sw, Vec3d v, double speed) {
		MaterialTrait tr = pe.trait();
		if (tr == MaterialTrait.NONE || pe.debris || pe.slowFall || speed < 0.35) return;
		Vec3d c = pe.center();
		double spread = Math.min(2.5, Math.max(0.3, pe.radius() * 0.45));
		switch (tr) {
			case CONDUCTIVE -> {
				if (pe.age % 2 == 0) sw.spawnParticles(ParticleTypes.ELECTRIC_SPARK, c.x, c.y, c.z, 3, spread, 0.25, spread, 0.08);
			}
			case IGNEOUS -> {
				sw.spawnParticles(ParticleTypes.FLAME, c.x, c.y, c.z, 4, spread, 0.25, spread, 0.03);
				if (pe.age % 2 == 0) sw.spawnParticles(ParticleTypes.LARGE_SMOKE, c.x, c.y, c.z, 2, spread, 0.2, spread, 0.02);
			}
			case CRYO -> sw.spawnParticles(ParticleTypes.SNOWFLAKE, c.x, c.y, c.z, 4, spread, 0.25, spread, 0.03);
			case HEAVY_PIERCING -> {
				BlockState dom = pe.blocks != null ? pe.blocks.dominant() : Blocks.OBSIDIAN.getDefaultState();
				if (dom != null && pe.age % 2 == 0) {
					sw.spawnParticles(new BlockStateParticleEffect(ParticleTypes.BLOCK, dom), c.x, c.y, c.z, 4, spread, 0.3, spread, 0.14);
				}
			}
			case NONE -> {}
		}
	}

	public static void weaponTrailFx(PieceEntity pe, ServerWorld sw) {
		MaterialTrait tr = pe.trait();
		if (tr == MaterialTrait.NONE) return;
		Vec3d c = pe.center();
		switch (tr) {
			case CONDUCTIVE -> sw.spawnParticles(ParticleTypes.ELECTRIC_SPARK, c.x, c.y, c.z, 3, 0.25, 0.25, 0.25, 0.1);
			case IGNEOUS -> sw.spawnParticles(ParticleTypes.FLAME, c.x, c.y, c.z, 3, 0.25, 0.25, 0.25, 0.03);
			case CRYO -> sw.spawnParticles(ParticleTypes.SNOWFLAKE, c.x, c.y, c.z, 3, 0.25, 0.25, 0.25, 0.03);
			case HEAVY_PIERCING -> sw.spawnParticles(ParticleTypes.ASH, c.x, c.y, c.z, 3, 0.25, 0.25, 0.25, 0.02);
			case NONE -> {}
		}
	}

	public static void serverTick(MinecraftServer server) {
		if (!EPHEMERAL_FIRES.isEmpty()) {
			Iterator<EphemeralFire> it = EPHEMERAL_FIRES.iterator();
			while (it.hasNext()) {
				EphemeralFire ef = it.next();
				ServerWorld w = server.getWorld(ef.dim());
				if (w == null) {
					it.remove();
					continue;
				}
				if (w.getTime() >= ef.expireTick()) {
					if (w.isChunkLoaded(ef.pos()) && w.getBlockState(ef.pos()).isIn(BlockTags.FIRE)) {
						w.setBlockState(ef.pos(), Blocks.AIR.getDefaultState(), Block.NOTIFY_ALL | Block.SKIP_DROPS);
					}
					it.remove();
				}
			}
		}

		if (!CHILL.isEmpty()) {
			Iterator<Map.Entry<UUID, ChillEntry>> it = CHILL.entrySet().iterator();
			while (it.hasNext()) {
				Map.Entry<UUID, ChillEntry> en = it.next();
				ChillEntry ce = en.getValue();
				ServerWorld w = server.getWorld(ce.dim());
				Entity e = w != null ? w.getEntity(en.getKey()) : null;
				if (!(e instanceof LivingEntity le) || !le.isAlive() || le.isRemoved() || ce.remainingTicks() <= 1) {
					it.remove();
					continue;
				}
				if (le.canFreeze()) {
					int minFreeze = le.getMinFreezeDamageTicks() + 2;
					le.setFrozenTicks(Math.max(le.getFrozenTicks(), minFreeze));
				}
				en.setValue(new ChillEntry(ce.dim(), ce.remainingTicks() - 1));
			}
		}

		if (!OVERLOADS.isEmpty()) {
			Iterator<Map.Entry<Integer, OverloadEntry>> it = OVERLOADS.entrySet().iterator();
			while (it.hasNext()) {
				Map.Entry<Integer, OverloadEntry> en = it.next();
				OverloadEntry oe = en.getValue();
				ServerWorld w = server.getWorld(oe.dim());
				Entity e = w != null ? w.getEntityById(en.getKey()) : null;
				if (!(e instanceof PieceEntity pe) || pe.isRemoved() || oe.remainingTicks() <= 0) {
					it.remove();
					continue;
				}
				if (pe.age % 2 == 0) {
					Vec3d c = pe.center();
					w.spawnParticles(ParticleTypes.ELECTRIC_SPARK, c.x, c.y, c.z, 4, 0.35, 0.35, 0.35, 0.25);
				}
				en.setValue(new OverloadEntry(oe.dim(), oe.remainingTicks() - 1));
			}
		}
	}
}
