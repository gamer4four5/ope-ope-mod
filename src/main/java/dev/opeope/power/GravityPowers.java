package dev.opeope.power;

import dev.opeope.entity.BlockSet;
import dev.opeope.entity.PieceEntity;
import dev.opeope.net.Payloads;
import dev.opeope.registry.ModDamage;
import dev.opeope.registry.ModEffects;
import dev.opeope.registry.ModItems;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.FluidBlock;
import net.minecraft.entity.Entity;
import net.minecraft.entity.ExperienceOrbEntity;
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

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Poderes canon y mecánicas de supervivencia de la Fruta Gravity (Zushi Zushi no Mi — Almirante Issho / Fujitora).
 */
public final class GravityPowers {
	private GravityPowers() {}

	public static final DustParticleEffect GRAVITY_DUST = new DustParticleEffect(new Vector3f(0.68f, 0.22f, 1.0f), 1.35f);
	public static final DustParticleEffect ABYSS_DUST = new DustParticleEffect(new Vector3f(0.24f, 0.05f, 0.42f), 1.6f);
	public static final DustParticleEffect ASTRAL_DUST = new DustParticleEffect(new Vector3f(0.92f, 0.72f, 1.0f), 1.1f);

	private record ActiveMeteor(RegistryKey<World> dim, int entityId, UUID ownerId, Vec3d target, Vec3d portalPos, int age) {}

	private static final List<ActiveMeteor> METEORS = new ArrayList<>();
	private static List<Block> cachedRareModOres = null;

	public static void clearAll() {
		METEORS.clear();
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

	// ------------------------------------------------------------------ Tick de Campo Gravito, Vuelo en Roca y Meteoritos

	public static void tickFieldAndFlight(ServerPlayerEntity p, PlayerPower pw) {
		ServerWorld w = p.getServerWorld();
		Room field = pw.room != null && pw.room.dim == w.getRegistryKey() ? pw.room : null;

		// 1) Campo Gravito activo (+100G aplastante por defecto, o -G Antigravedad si está activado)
		if (field != null) {
			Box box = new Box(field.center, field.center).expand(field.radius);
			if (pw.zeroGravityRoom) {
				for (Entity e : w.getOtherEntities(p, box, ent -> ent.isAlive() && field.contains(ent.getPos()))) {
					if (pw.takt.contains(e.getId()) || e.getId() == pw.gravityMountId) continue;
					if (e instanceof PieceEntity pe && pe.weapon != null) continue;
					e.fallDistance = 0f;
					Vec3d v = e.getVelocity();
					double targetVy = e.getY() < field.center.y + 3.0 ? 0.10 : 0.02 * Math.sin((p.age + e.getId()) * 0.15);
					e.setVelocity(v.x * 0.88, targetVy, v.z * 0.88);
					e.velocityModified = true;
				}
			} else {
				// Modo +100G: enemigos pesados contra el suelo y proyectiles hostiles clavados en tierra
				for (LivingEntity le : w.getEntitiesByClass(LivingEntity.class, box, e -> e != p && e.isAlive() && field.contains(e.getPos()))) {
					Vec3d v = le.getVelocity();
					if (v.y > -0.8) {
						le.setVelocity(v.x * 0.65, Math.min(-0.22, v.y - 0.18), v.z * 0.65);
						le.velocityModified = true;
					}
					if (p.age % 12 == 0) {
						le.addStatusEffect(new StatusEffectInstance(StatusEffects.SLOWNESS, 25, 1, false, false, true));
						w.spawnParticles(GRAVITY_DUST, le.getX(), le.getY() + le.getHeight() + 0.5, le.getZ(), 2, 0.25, 0.2, 0.25, 0);
					}
					if (p.age % 20 == 0 && (le instanceof HostileEntity || (le instanceof net.minecraft.entity.mob.MobEntity mob && mob.getTarget() == p))) {
						le.damage(ModDamage.of(w, ModDamage.GAMMA, p), 2.5f + pw.masteryTier() * 0.8f);
					}
				}
				for (ProjectileEntity proj : w.getEntitiesByClass(ProjectileEntity.class, box, pr -> pr.isAlive() && !pr.isOnGround() && field.contains(pr.getPos()))) {
					if (proj.getOwner() == p) continue;
					proj.setVelocity(proj.getVelocity().x * 0.2, -1.6, proj.getVelocity().z * 0.2);
					proj.velocityModified = true;
					if (p.age % 3 == 0) {
						w.spawnParticles(GRAVITY_DUST, proj.getX(), proj.getY(), proj.getZ(), 2, 0.1, 0.1, 0.1, 0);
					}
				}
			}
		}

		// 2) Escudo de Repulsión Orbital (Tecla B / Curtain)
		if (pw.curtainTicks > 0) {
			for (Entity e : w.getOtherEntities(p, p.getBoundingBox().expand(4.2), ent -> ent.isAlive() && !ent.isSpectator() && ent.getId() != pw.gravityMountId)) {
				if (e instanceof ProjectileEntity pr && pr.getOwner() == p) continue;
				if (e instanceof PieceEntity pe && (pe.weapon != null || pw.takt.contains(pe.getId()))) continue;
				Vec3d push = e.getPos().subtract(p.getPos());
				if (push.lengthSquared() < 0.05) push = new Vec3d(0, 0.4, 1);
				Vec3d dir = push.normalize();
				e.setVelocity(dir.x * 1.45, Math.max(0.35, dir.y * 0.8 + 0.25), dir.z * 1.45);
				e.velocityModified = true;
			}
			if (p.age % 4 == 0) {
				ring(w, p.getPos().add(0, 1.0, 0), 3.4, 24, GRAVITY_DUST);
			}
		}

		// 3) Vuelo Canon de Fujitora sobre plataforma rocosa (SOLO vuela si la roca existe bajo sus pies)
		if (!p.isCreative() && !p.isSpectator()) {
			Entity mount = pw.gravityMountId >= 0 ? w.getEntityById(pw.gravityMountId) : null;
			if (!(mount instanceof PieceEntity pe) || pe.isRemoved() || !pe.isAlive() || pw.takt.contains(pw.gravityMountId)) {
				if (pw.gravityMountId >= 0) {
					pw.gravityMountId = -1;
					pw.dirty = true;
				}
				if (pw.surgicalFlightGranted) {
					pw.surgicalFlightGranted = false;
					p.getAbilities().allowFlying = false;
					p.getAbilities().flying = false;
					p.sendAbilitiesUpdate();
					pw.fallImmunityTicks = Math.max(pw.fallImmunityTicks, 120);
				}
			} else {
				// Mantiene el vuelo mientras surfeas sobre tu roca flotante
				if (!p.getAbilities().allowFlying) {
					p.getAbilities().allowFlying = true;
					p.getAbilities().flying = true;
					p.sendAbilitiesUpdate();
				}
				pw.surgicalFlightGranted = true;
				pw.fallImmunityTicks = 200;
				p.fallDistance = 0f;

				pe.mode = PieceEntity.Mode.STUCK;
				pe.noClip = true;
				pe.solid = false;
				pe.setFlag(PieceEntity.F_SEAM, true);
				pe.setPosition(p.getX(), p.getY() - 1.02, p.getZ());
				pe.setVelocity(p.getVelocity());

				if (p.age % 2 == 0) {
					double rAng = p.age * 0.45;
					for (int k = 0; k < 2; k++) {
						double a = rAng + k * Math.PI;
						w.spawnParticles(GRAVITY_DUST, p.getX() + Math.cos(a) * 1.5, p.getY() - 0.55, p.getZ() + Math.sin(a) * 1.5, 1, 0, 0, 0, 0);
					}
					w.spawnParticles(ABYSS_DUST, p.getX(), p.getY() - 0.9, p.getZ(), 2, 0.9, 0.05, 0.9, 0);
				}
			}
		}
	}

	public static void tickMeteors(MinecraftServer server) {
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
			w.spawnParticles(ParticleTypes.FLAME, mc.x, mc.y, mc.z, 18, 1.4, 1.4, 1.4, 0.08);
			w.spawnParticles(ParticleTypes.LARGE_SMOKE, mc.x, mc.y, mc.z, 10, 1.2, 1.2, 1.2, 0.05);
			w.spawnParticles(GRAVITY_DUST, mc.x, mc.y, mc.z, 12, 1.6, 1.6, 1.6, 0.02);
			if (am.age % 2 == 0) {
				ring(w, am.portalPos, 6.0, 28, GRAVITY_DUST);
			}

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
		double R = 6.2;
		int ri = (int) Math.ceil(R);

		// Cono de repulsión protector de Fujitora: su posición exacta y el pilar 2x2 bajo él quedan intactos
		BlockPos safeCenter = owner != null ? owner.getBlockPos() : null;

		for (int dx = -ri; dx <= ri; dx++) {
			for (int dy = 3; dy >= -4; dy--) {
				for (int dz = -ri; dz <= ri; dz++) {
					double d2 = dx * dx + dy * dy * 1.4 + dz * dz;
					if (d2 > R * R) continue;
					BlockPos bp = cb.add(dx, dy, dz);
					if (bp.getY() < w.getBottomY() + 2 || bp.getY() >= w.getTopY()) continue;
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
					w.setBlockState(bp, Blocks.AIR.getDefaultState(), Block.NOTIFY_LISTENERS);
				}
			}
		}

		// Deposita un núcleo espacial caliente con menas raras en el fondo del cráter
		BlockPos craterFloor = cb.down(3);
		List<Block> rareOres = rareModOres();
		BlockPos[] coreOffsets = {
			craterFloor, craterFloor.east(), craterFloor.west(), craterFloor.north(), craterFloor.south(),
			craterFloor.up()
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

		// Onda de choque masiva (Fujitora queda inmune gracias al cono de repulsión)
		for (LivingEntity le : w.getEntitiesByClass(LivingEntity.class, new Box(impact, impact).expand(12.0), Entity::isAlive)) {
			if (le == owner) continue;
			float amt = owner != null ? 52f + PowerManager.get(owner).masteryTier() * 10f : 48f;
			le.damage(ModDamage.of(w, ModDamage.BURN, owner), amt);
			le.setOnFireFor(6);
			Vec3d away = le.getPos().subtract(impact);
			if (away.lengthSquared() < 0.1) away = new Vec3d(0, 1, 0);
			Vec3d push = away.normalize().multiply(2.1).add(0, 0.9, 0);
			le.addVelocity(push.x, push.y, push.z);
			le.velocityModified = true;
		}

		w.spawnParticles(ParticleTypes.EXPLOSION_EMITTER, impact.x, impact.y, impact.z, 4, 2.0, 1.0, 2.0, 0);
		w.spawnParticles(ParticleTypes.LAVA, impact.x, impact.y, impact.z, 60, 3.0, 1.2, 3.0, 0.2);
		w.spawnParticles(GRAVITY_DUST, impact.x, impact.y + 0.5, impact.z, 120, 4.5, 2.0, 4.5, 0.04);
		ring(w, impact.add(0, 0.5, 0), 7.5, 42, GRAVITY_DUST);
		ring(w, impact.add(0, 1.2, 0), 9.5, 48, ParticleTypes.FLAME);
		playSound(w, impact, SoundEvents.ENTITY_GENERIC_EXPLODE.value(), 2.2f, 0.6f);
		playSound(w, impact, SoundEvents.ENTITY_DRAGON_FIREBALL_EXPLODE, 2.0f, 0.5f);
		if (owner != null) {
			PowerManager.addBounty(owner, 900_000L);
			owner.sendMessage(Text.literal("§5☄ ¡Impacto de Tengai Shinsei! §7(Núcleo de minerales espaciales depositado en el cráter)"), true);
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

	// ------------------------------------------------------------------ Alt izq.: Plataforma de Roca (Vuelo Canon) / Atracción Mineral

	public static void rockFlightToggle(ServerPlayerEntity p, PlayerPower pw) {
		ServerWorld w = p.getServerWorld();
		// Shift + Alt apuntando a mena: atracción gravitatoria pura de la veta mineral
		if (p.isSneaking() && SurgicalSurvival.tryExtractOreVein(p, pw, false)) {
			pw.cooldown(Tech.SHAMBLES);
			return;
		}
		if (pw.onCooldown(Tech.SHAMBLES)) return;

		Entity curMount = pw.gravityMountId >= 0 ? w.getEntityById(pw.gravityMountId) : null;
		if (curMount instanceof PieceEntity pe && !pe.isRemoved() && pe.isAlive()) {
			// Saltar de la roca y dispararla como proyectil contra donde miras
			Vec3d look = p.getRotationVector();
			Vec3d originPos = pe.center();
			pw.gravityMountId = -1;
			if (pw.surgicalFlightGranted) {
				pw.surgicalFlightGranted = false;
				p.getAbilities().allowFlying = false;
				p.getAbilities().flying = false;
				p.sendAbilitiesUpdate();
			}
			pw.fallImmunityTicks = 240;
			p.setVelocity(look.x * -0.35, 0.55, look.z * -0.35);
			p.velocityModified = true;
			if (p.networkHandler != null) p.networkHandler.sendPacket(new EntityVelocityUpdateS2CPacket(p.getId(), p.getVelocity()));

			pe.noClip = false;
			pe.setFlag(PieceEntity.F_SEAM, false);
			pe.release(look.multiply(2.85), true);
			ring(w, originPos, 2.2, 20, GRAVITY_DUST);
			playSound(w, originPos, SoundEvents.ENTITY_BREEZE_WIND_BURST.value(), 1.2f, 0.8f);
			p.sendMessage(Text.literal("§5🪨 ¡Plataforma gravitatoria lanzada!"), true);
			pw.cooldown(Tech.SHAMBLES);
			return;
		}

		// Eleva una placa de roca 3×1×3 desde el suelo bajo tus pies (o condensada del entorno)
		BlockSet set = new BlockSet();
		BlockPos base = p.getBlockPos().down();
		for (int dx = -1; dx <= 1; dx++) {
			for (int dz = -1; dz <= 1; dz++) {
				BlockPos bp = base.add(dx, 0, dz);
				BlockState st = w.getBlockState(bp);
				BlockState sample = (!st.isAir() && !(st.getBlock() instanceof FluidBlock) && !st.hasBlockEntity() && st.getHardness(w, bp) >= 0)
					? st : ((dx + dz) % 2 == 0 ? Blocks.DEEPSLATE.getDefaultState() : Blocks.COBBLESTONE.getDefaultState());
				if (!st.isAir() && st == sample && p.getRandom().nextBoolean()) {
					w.setBlockState(bp, Blocks.AIR.getDefaultState(), Block.NOTIFY_LISTENERS);
				}
				set.add(dx + 1, 0, dz + 1, sample, null);
			}
		}
		set.normalize();
		Vec3d spawnPos = new Vec3d(p.getX(), p.getY() + 0.15, p.getZ());
		PieceEntity plat = PieceEntity.spawn(w, set, spawnPos.subtract(set.sx / 2.0, 0, set.sz / 2.0), base, p.getUuid());
		plat.mode = PieceEntity.Mode.STUCK;
		plat.noClip = true;
		plat.solid = false;
		plat.setFlag(PieceEntity.F_SEAM, true);

		pw.gravityMountId = plat.getId();
		pw.fallImmunityTicks = 300;
		p.requestTeleport(p.getX(), p.getY() + 1.25, p.getZ());
		p.setVelocity(0, 0.25, 0);
		p.velocityModified = true;
		if (!p.isCreative() && !p.isSpectator()) {
			p.getAbilities().allowFlying = true;
			p.getAbilities().flying = true;
			p.sendAbilitiesUpdate();
			pw.surgicalFlightGranted = true;
		}
		pw.dirty = true;
		ring(w, spawnPos, 2.5, 24, GRAVITY_DUST);
		playSound(w, spawnPos, SoundEvents.BLOCK_RESPAWN_ANCHOR_CHARGE, 0.9f, 0.7f);
		playSound(w, spawnPos, SoundEvents.BLOCK_AMETHYST_BLOCK_RESONATE, 1.2f, 0.6f);
		p.sendMessage(Text.literal("§5🪨 Vuelo Canon de Fujitora: ¡Surfea en tu roca! §7([Alt] otra vez para lanzarla)"), true);
		pw.cooldown(Tech.SHAMBLES);
	}

	// ------------------------------------------------------------------ Jigoku Tabi (Viaje al Infierno — Cráter de Gravedad Aplastante)

	public static void jigokuTabi(ServerPlayerEntity p, PlayerPower pw) {
		if (pw.onCooldown(Tech.GAMMA)) return;
		equipYakuzaKasenSafely(p);
		ServerWorld w = p.getServerWorld();
		Vec3d aim = PowerManager.aimPoint(p, 36.0);
		BlockPos cb = BlockPos.ofFloored(aim);
		BlockPos safePlayer = p.getBlockPos();

		double R = 4.5;
		int ri = (int) Math.ceil(R);
		int oresCount = 0;
		ItemStack fortuneTool = SurgicalSurvival.createPickaxe(w, Math.max(1, pw.masteryTier()));

		for (int dx = -ri; dx <= ri; dx++) {
			for (int dy = 2; dy >= -4; dy--) {
				for (int dz = -ri; dz <= ri; dz++) {
					if (dx * dx + dz * dz > R * R) continue;
					BlockPos bp = cb.add(dx, dy, dz);
					if (bp.getY() < w.getBottomY() + 1 || bp.getY() >= w.getTopY()) continue;
					if (Math.abs(bp.getX() - safePlayer.getX()) <= 1 && Math.abs(bp.getZ() - safePlayer.getZ()) <= 1) continue;
					BlockState st = w.getBlockState(bp);
					if (st.isAir() || st.hasBlockEntity() || st.getHardness(w, bp) < 0) continue;

					if (SurgicalSurvival.isOre(st)) {
						int[] y = SurgicalSurvival.givePerfectOreYield(p, w, pw, bp, st, fortuneTool, false, pw.kroomBladeTicks > 0, false, null);
						oresCount += y[0];
					}
					w.setBlockState(bp, Blocks.AIR.getDefaultState(), Block.NOTIFY_LISTENERS);
				}
			}
		}

		for (LivingEntity le : w.getEntitiesByClass(LivingEntity.class, new Box(aim, aim).expand(6.5), e -> e != p && e.isAlive())) {
			le.damage(ModDamage.of(w, ModDamage.GAMMA, p), 42f + pw.masteryTier() * 8f);
			le.addStatusEffect(new StatusEffectInstance(ModEffects.STUN, 65, 0, false, true));
			le.addVelocity(0, -2.6, 0);
			le.velocityModified = true;
		}

		for (int h = 0; h <= 6; h += 2) {
			ring(w, aim.add(0, 4.0 - h, 0), R * (1.0 - h * 0.08), 28, GRAVITY_DUST);
		}
		ring(w, aim.add(0, 0.2, 0), R + 1.2, 34, ABYSS_DUST);
		w.spawnParticles(ParticleTypes.EXPLOSION, aim.x, aim.y, aim.z, 2, 1.5, 0.5, 1.5, 0);
		playSound(w, aim, SoundEvents.BLOCK_ANVIL_LAND, 1.4f, 0.45f);
		playSound(w, aim, SoundEvents.ENTITY_WARDEN_SONIC_BOOM, 1.1f, 0.55f);
		PowerManager.addBounty(p, 350_000L + oresCount * 30_000L);
		p.sendMessage(Text.literal(oresCount > 0
			? "§5⬇ Gravito: ¡Jigoku Tabi! §7(" + oresCount + " minerales puros separados en el cráter)"
			: "§5⬇ Gravito: ¡Jigoku Tabi! (Envainado mortal)"), true);
		pw.cooldown(Tech.GAMMA);
	}

	// ------------------------------------------------------------------ Moko (Tigre Feroz — Onda de Gravedad Horizontal)

	public static void moko(ServerPlayerEntity p, PlayerPower pw) {
		if (pw.onCooldown(Tech.INJECTION)) return;
		equipYakuzaKasenSafely(p);
		ServerWorld w = p.getServerWorld();
		Vec3d eye = p.getEyePos();
		Vec3d look = p.getRotationVector();
		Vec3d side = look.crossProduct(new Vec3d(0, 1, 0));
		if (side.lengthSquared() < 1e-4) side = new Vec3d(1, 0, 0);
		else side = side.normalize();
		Vec3d up = side.crossProduct(look).normalize();

		int hitCount = 0;
		List<LivingEntity> knocked = new ArrayList<>();

		for (double d = 2.0; d <= 24.0; d += 2.0) {
			Vec3d pt = eye.add(look.multiply(d));
			// Anillos verticales de Tigerslash horizontal con partículas púrpuras + chispas oscuras
			for (int k = 0; k < 20; k++) {
				double ang = Math.PI * 2.0 * k / 20.0;
				double rad = 1.6 + d * 0.08;
				Vec3d rp = pt.add(side.multiply(Math.cos(ang) * rad)).add(up.multiply(Math.sin(ang) * rad));
				w.spawnParticles(k % 3 == 0 ? ABYSS_DUST : GRAVITY_DUST, rp.x, rp.y, rp.z, 1, 0, 0, 0, 0);
			}
			w.spawnParticles(ParticleTypes.ELECTRIC_SPARK, pt.x, pt.y, pt.z, 4, 0.9, 0.9, 0.9, 0.2);

			// Rompe obstáculos débiles (hojas, matorrales, madera fina, vidrios)
			BlockPos bp = BlockPos.ofFloored(pt);
			for (int dx = -1; dx <= 1; dx++) {
				for (int dy = -1; dy <= 1; dy++) {
					for (int dz = -1; dz <= 1; dz++) {
						BlockPos q = bp.add(dx, dy, dz);
						BlockState st = w.getBlockState(q);
						if (st.isAir() || st.hasBlockEntity()) continue;
						if (st.isIn(BlockTags.LEAVES) || st.isIn(BlockTags.LOGS) || st.getHardness(w, q) <= 1.2f) {
							if (st.getHardness(w, q) >= 0) {
								w.breakBlock(q, st.isIn(BlockTags.LOGS), p);
							}
						}
					}
				}
			}

			for (LivingEntity le : w.getEntitiesByClass(LivingEntity.class, new Box(pt, pt).expand(2.6),
				e -> e != p && e.isAlive() && !knocked.contains(e))) {
				knocked.add(le);
				le.damage(ModDamage.of(w, ModDamage.GAMMA, p), 36f + pw.masteryTier() * 7f);
				le.addVelocity(look.x * 3.1, 0.65 + look.y * 1.4, look.z * 3.1);
				le.velocityModified = true;
				le.addStatusEffect(new StatusEffectInstance(ModEffects.STUN, 45, 0, false, true));
				hitCount++;
			}
		}

		playSound(w, eye, SoundEvents.ENTITY_WARDEN_SONIC_BOOM, 1.4f, 0.8f);
		playSound(w, eye, SoundEvents.ENTITY_RAVAGER_ROAR, 1.1f, 0.65f);
		p.sendMessage(Text.literal("§5🐅 Gravito: ¡MOKO (Tigre Feroz)! §7(" + hitCount + " impactados)"), true);
		pw.cooldown(Tech.INJECTION);
	}

	// ------------------------------------------------------------------ Tengai Shinsei (Meteorito Real del Espacio)

	public static void tengaiShinsei(ServerPlayerEntity p, PlayerPower pw, boolean triple) {
		if (pw.onCooldown(Tech.RADIO_KNIFE)) return;
		equipYakuzaKasenSafely(p);
		ServerWorld w = p.getServerWorld();
		Vec3d baseAim = PowerManager.aimPoint(p, 75.0);
		int count = triple ? 3 : 1;

		for (int m = 0; m < count; m++) {
			double ox = m == 0 ? 0.0 : (m == 1 ? -9.0 : 9.0);
			double oz = m == 0 ? 0.0 : (m == 1 ? 6.0 : -6.0);
			Vec3d target = baseAim.add(ox, 0, oz);
			Vec3d portalPos = new Vec3d(target.x + (w.random.nextDouble() - 0.5) * 6.0,
				Math.min(w.getTopY() - 8.0, target.y + 42.0 + m * 6.0),
				target.z + (w.random.nextDouble() - 0.5) * 6.0);

			BlockSet set = buildMeteorBlockSet(w, m == 0 ? 2 : 1);
			PieceEntity meteor = PieceEntity.spawn(w, set, portalPos.subtract(set.sx / 2.0, set.sy / 2.0, set.sz / 2.0), null, p.getUuid());
			meteor.mode = PieceEntity.Mode.FREE;
			meteor.thrown = true;
			meteor.ownerGraceTicks = 200;
			Vec3d plunge = target.subtract(portalPos).normalize().multiply(2.15);
			meteor.setVelocity(plunge);
			meteor.velocityModified = true;

			ring(w, portalPos, 7.0, 36, GRAVITY_DUST);
			ring(w, portalPos.add(0, -1.5, 0), 5.0, 30, ABYSS_DUST);
			METEORS.add(new ActiveMeteor(w.getRegistryKey(), meteor.getId(), p.getUuid(), target, portalPos, 0));
		}

		playSound(w, baseAim.add(0, 18, 0), SoundEvents.ENTITY_LIGHTNING_BOLT_THUNDER, 2.0f, 0.55f);
		playSound(w, p.getPos(), SoundEvents.BLOCK_RESPAWN_ANCHOR_DEPLETE.value(), 1.3f, 0.6f);
		p.sendMessage(Text.literal(triple
			? "§5☄☄☄ ¡LLUVIA DE METEORITOS ESPACIALES!"
			: "§5☄ ¡Tengai Shinsei: Meteorito atraído desde el espacio!"), true);
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

	// ------------------------------------------------------------------ Haki de Observación Ciego (V), Repulsión (B) y Escombros de Dressrosa (Tab+V)

	public static void blindObservationHaki(ServerPlayerEntity p, PlayerPower pw) {
		if (pw.onCooldown(Tech.SCAN)) return;
		pw.scanTicks = 280;
		p.addStatusEffect(new StatusEffectInstance(StatusEffects.NIGHT_VISION, 280, 0, false, false, true));
		p.addStatusEffect(new StatusEffectInstance(StatusEffects.SPEED, 280, 1, false, false, true));
		p.addStatusEffect(new StatusEffectInstance(StatusEffects.RESISTANCE, 280, 0, false, false, true));
		playSound(p.getServerWorld(), p.getPos(), SoundEvents.BLOCK_AMETHYST_BLOCK_RESONATE, 1.3f, 1.6f);
		ring(p.getServerWorld(), p.getPos().add(0, 1.0, 0), 3.0, 26, ASTRAL_DUST);
		p.sendMessage(Text.literal("§d👁 Haki de Observación Ciego de Fujitora: ¡Auras 3D, vetas y esquiva activas!"), true);
		SurgicalSurvival.scanRoomReport(p, pw);
		pw.cooldown(Tech.SCAN);
	}

	public static void repulsionShield(ServerPlayerEntity p, PlayerPower pw) {
		if (pw.onCooldown(Tech.CURTAIN)) return;
		pw.curtainTicks = 180;
		pw.dirty = true;
		p.addStatusEffect(new StatusEffectInstance(StatusEffects.RESISTANCE, 180, 2, false, false, true));
		playSound(p.getServerWorld(), p.getPos(), SoundEvents.BLOCK_BEACON_ACTIVATE, 1.2f, 0.7f);
		ring(p.getServerWorld(), p.getPos().add(0, 1.0, 0), 3.8, 30, GRAVITY_DUST);
		p.sendMessage(Text.literal("§5🛡 Escudo de Repulsión Gravitatoria activo (9s)"), true);
		pw.cooldown(Tech.CURTAIN);
	}

	public static void dressrosaRubble(ServerPlayerEntity p, PlayerPower pw) {
		if (pw.onCooldown(Tech.RAIN)) return;
		equipYakuzaKasenSafely(p);
		ServerWorld w = p.getServerWorld();
		Room room = pw.room != null && pw.room.dim == w.getRegistryKey() ? pw.room : null;
		if (room == null) {
			room = new Room(w.getRegistryKey(), p.getPos().add(0, 1.0, 0), 34.0);
			room.state = Room.State.FIXED;
			pw.room = room;
			pw.lastRoomRadius = 34.0;
			PowerManager.broadcastRoom(p, 0, room);
		}
		if (!Takt.summonOrbitalRing(p, pw)) return;
		pw.taktMode = PlayerPower.TaktMode.ORBIT;
		pw.dirty = true;
		ring(w, p.getPos().add(0, 2.0, 0), 6.0, 32, GRAVITY_DUST);
		playSound(w, p.getPos(), SoundEvents.ENTITY_WITHER_SPAWN, 0.7f, 0.65f);
		p.sendMessage(Text.literal("§5🌋 ¡Escombros de Dressrosa suspendidos en el cielo! (Clic Izq. para bombardear)"), true);
		pw.cooldown(Tech.RAIN);
	}

	// ------------------------------------------------------------------ Supervivencia de Fujitora: Prensa de Minas (Shift+R) y Cristalización (Tab+C)

	/**
	 * Prensa Gravitatoria de Minas (Shift+R):
	 * - Si llevas rocas flotando en Takt: las comprime pulverizando la roca estéril, separando minerales 3×–4×
	 *   y cristalizando diamantes/obsidiana por presión extrema.
	 * - Si apuntas a una cueva/pared: aplasta un volumen 4×4×4 de roca estéril extrayendo las menas puras 3×–4×.
	 */
	public static void gravityMinePress(ServerPlayerEntity p, PlayerPower pw) {
		ServerWorld w = p.getServerWorld();
		List<Entity> held = Takt.resolve(p, pw);
		if (!held.isEmpty() && held.stream().anyMatch(e -> e instanceof PieceEntity pe && pe.weapon == null)) {
			// Reutiliza la pulverización de Takt y además añade cristalización extra por gravedad
			SurgicalSurvival.telekineticMindBreak(p, pw);
			if (w.random.nextFloat() < 0.65f) {
				p.getInventory().offerOrDrop(new ItemStack(Items.OBSIDIAN, 2));
				p.getInventory().offerOrDrop(new ItemStack(Items.DIAMOND, 1));
				p.sendMessage(Text.literal("§5💎 ¡La hiper-gravedad cristalizó carbono y roca en Diamante + Obsidiana!"), true);
			}
			return;
		}

		Room room = pw.room != null && pw.room.dim == w.getRegistryKey() ? pw.room : pw.rroom;
		BlockHitResult hit = PowerManager.raycastBlock(p, 40.0);
		if (hit.getType() != HitResult.Type.BLOCK) {
			p.sendMessage(Text.literal("§7Apunta a piedra o una mena para prensarla con Gravedad"), true);
			return;
		}
		if (room != null && !room.contains(hit.getPos())) {
			PowerManager.noRoom(p);
			return;
		}

		BlockPos seed = hit.getBlockPos();
		ItemStack fortuneTool = SurgicalSurvival.createPickaxe(w, Math.max(2, pw.masteryTier()));
		boolean autoSmelt = pw.kroomBladeTicks > 0;
		BlockPos feetBelow = p.getBlockPos().down();
		int crushed = 0, oresYield = 0, diamonds = 0;

		for (int dx = -2; dx <= 2; dx++) {
			for (int dy = -2; dy <= 2; dy++) {
				for (int dz = -2; dz <= 2; dz++) {
					if (dx * dx + dy * dy + dz * dz > 6) continue;
					BlockPos bp = seed.add(dx, dy, dz);
					if (bp.getY() < w.getBottomY() + 1 || bp.getY() >= w.getTopY()) continue;
					if (p.isOnGround() && bp.equals(feetBelow)) continue;
					if (room != null && !room.contains(Vec3d.ofCenter(bp))) continue;
					BlockState st = w.getBlockState(bp);
					if (st.isAir() || st.getBlock() instanceof FluidBlock || st.hasBlockEntity() || st.getHardness(w, bp) < 0) continue;

					if (SurgicalSurvival.isOre(st)) {
						int[] y = SurgicalSurvival.givePerfectOreYield(p, w, pw, bp, st, fortuneTool, false, autoSmelt, false, null);
						oresYield += y[0];
						if (st.isIn(BlockTags.COAL_ORES) && w.random.nextFloat() < 0.35f) {
							p.getInventory().offerOrDrop(new ItemStack(Items.DIAMOND, 1));
							diamonds++;
						}
					} else if (crushed < 24 && (st.isOf(Blocks.STONE) || st.isOf(Blocks.COBBLESTONE) || st.isOf(Blocks.DEEPSLATE))) {
						// Solo recoge parte de la roca estéril comprimida para no saturar inventario
						if (w.random.nextInt(4) == 0) {
							p.getInventory().offerOrDrop(new ItemStack(st.getBlock().asItem(), 1));
						}
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
		ring(w, c, 2.8, 28, GRAVITY_DUST);
		w.spawnParticles(ABYSS_DUST, c.x, c.y, c.z, 35, 1.2, 1.2, 1.2, 0.05);
		playSound(w, c, SoundEvents.BLOCK_ANVIL_LAND, 1.0f, 0.55f);
		if (oresYield > 0) ExperienceOrbEntity.spawn(w, p.getPos().add(0, 0.5, 0), Math.max(2, oresYield / 2));

		p.sendMessage(Text.literal("§5⚙ Prensa Gravitatoria ×100G: §f¡" + crushed + " bloques aplastados · "
			+ oresYield + " minerales concentrados" + (diamonds > 0 ? " + " + diamonds + " Diamantes cristalizados!" : "!")), true);
	}

	/**
	 * Compresión y Cristalización por Hiper-Gravedad (Tab + C sobre ítems sueltos en el suelo):
	 * - 8× Carbón / 1× Bloque de Carbón -> 1× Diamante Puro.
	 * - 8× Adoquín / Piedra -> 1× Obsidiana.
	 * - Menas Crudas (Hierro/Oro/Cobre) -> Lingotes fundidos por presión adiabática.
	 */
	public static boolean gravityHyperCompression(ServerPlayerEntity p, PlayerPower pw) {
		ServerWorld w = p.getServerWorld();
		Vec3d aim = PowerManager.aimPoint(p, 24.0);
		List<ItemEntity> piles = w.getEntitiesByClass(ItemEntity.class, new Box(aim, aim).expand(4.5),
			ie -> ie.isAlive() && !ie.getStack().isEmpty());
		if (piles.isEmpty()) {
			return SurgicalSurvival.trySurgicalAssembly(p, pw);
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
				// Fusión adiabática por presión en lingotes para menas crudas
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
			ring(w, center, 2.2, 24, GRAVITY_DUST);
			w.spawnParticles(ASTRAL_DUST, center.x, center.y, center.z, 25, 0.4, 0.4, 0.4, 0.04);
			playSound(w, center, SoundEvents.BLOCK_AMETHYST_BLOCK_CHIME, 1.3f, 1.8f);
			playSound(w, center, SoundEvents.BLOCK_ANVIL_USE, 0.9f, 1.4f);
			List<String> msg = new ArrayList<>();
			if (diamonds > 0) msg.add(diamonds + " Diamante(s)");
			if (obsidian > 0) msg.add(obsidian + " Obsidiana");
			if (ingots > 0) msg.add(ingots + " Lingotes");
			p.sendMessage(Text.literal("§5💎 Cristalización por Hiper-Gravedad: §f¡Producido " + String.join(" · ", msg) + "!"), true);
			return true;
		}
		return SurgicalSurvival.trySurgicalAssembly(p, pw);
	}

	private static void ring(ServerWorld w, Vec3d c, double radius, int pts, net.minecraft.particle.ParticleEffect p) {
		for (int i = 0; i < pts; i++) {
			double a = Math.PI * 2.0 * i / pts;
			w.spawnParticles(p, c.x + Math.cos(a) * radius, c.y, c.z + Math.sin(a) * radius, 1, 0, 0, 0, 0);
		}
	}

	private static void playSound(ServerWorld w, Vec3d pos, SoundEvent sound, float volume, float pitch) {
		w.playSound(null, pos.x, pos.y, pos.z, sound, SoundCategory.PLAYERS, volume, pitch);
	}
}
