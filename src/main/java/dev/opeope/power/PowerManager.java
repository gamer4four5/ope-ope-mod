package dev.opeope.power;

import dev.opeope.entity.PieceEntity;

import dev.opeope.OpeConfig;
import dev.opeope.OpeOpeMod;
import dev.opeope.net.Payloads;
import dev.opeope.registry.ModAttachments;
import dev.opeope.registry.ModEffects;
import dev.opeope.registry.ModItems;
import net.fabricmc.fabric.api.entity.event.v1.ServerEntityWorldChangeEvents;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.EntityTrackingEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.block.BlockState;
import net.minecraft.entity.Entity;
import net.minecraft.entity.ExperienceOrbEntity;
import net.minecraft.entity.ItemEntity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.attribute.EntityAttribute;
import net.minecraft.entity.attribute.EntityAttributeInstance;
import net.minecraft.entity.attribute.EntityAttributeModifier;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.entity.boss.WitherEntity;
import net.minecraft.entity.boss.dragon.EnderDragonEntity;
import net.minecraft.entity.damage.DamageTypes;
import net.minecraft.entity.decoration.ArmorStandEntity;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.entity.mob.ElderGuardianEntity;
import net.minecraft.entity.mob.WardenEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.EntityHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.BlockView;
import net.minecraft.world.RaycastContext;
import net.minecraft.world.World;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Predicate;

public final class PowerManager {
	private PowerManager() {}

	private static final Map<UUID, PlayerPower> POWERS = new HashMap<>();
	private static final Identifier MASTERY_HEALTH_ID = OpeOpeMod.id("mastery_health");
	private static final Identifier MASTERY_SPEED_ID = OpeOpeMod.id("mastery_speed");
	private static final Identifier MASTERY_DAMAGE_ID = OpeOpeMod.id("mastery_damage");

	public static PlayerPower get(ServerPlayerEntity p) {
		return POWERS.computeIfAbsent(p.getUuid(), u -> new PlayerPower());
	}

	public static PlayerPower peek(UUID id) {
		return POWERS.get(id);
	}

	public static void init() {
		SurgicalSurvival.init();
		ServerTickEvents.END_SERVER_TICK.register(PowerManager::tick);
		ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
			ServerPlayerEntity p = handler.getPlayer();
			if (!Boolean.getBoolean("opeope.autotest")) {
				boolean hasOpe = ModAttachments.hasFruit(p);
				boolean hasOpeItem = false, hasGravityItem = false, hasYakuza = false;
				for (int i = 0; i < p.getInventory().size(); i++) {
					ItemStack s = p.getInventory().getStack(i);
					if (s.isOf(ModItems.FRUIT)) hasOpeItem = true;
					else if (s.isOf(ModItems.GRAVITY_FRUIT)) hasGravityItem = true;
					else if (s.isOf(ModItems.YAKUZA_KASEN)) hasYakuza = true;
				}
				if (!hasOpe && !hasOpeItem) {
					p.getInventory().offerOrDrop(new ItemStack(ModItems.FRUIT));
					p.getInventory().offerOrDrop(new ItemStack(ModItems.KIKOKU));
				}
				if (!hasGravityItem && !hasYakuza && ModAttachments.getFruitKind(p) != 2) {
					p.getInventory().offerOrDrop(new ItemStack(ModItems.GRAVITY_FRUIT));
					p.getInventory().offerOrDrop(new ItemStack(ModItems.YAKUZA_KASEN));
				}
			}
			PlayerPower pw = get(p);
			pw.bounty = ModAttachments.getBounty(p);
			pw.fruitKind = ModAttachments.hasFruit(p) ? ModAttachments.getFruitKind(p) : 1;
			applyMasteryAttributes(p, pw);
			pw.dirty = true;
			sendAllRooms(p);
			GroundCuts.sendAll(p);
		});
		ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> cleanup(handler.getPlayer()));
		ServerPlayerEvents.AFTER_RESPAWN.register((oldPlayer, newPlayer, alive) -> {
			PlayerPower pw = get(newPlayer);
			pw.bounty = ModAttachments.getBounty(newPlayer);
			applyMasteryAttributes(newPlayer, pw);
			releaseAll(newPlayer, pw, oldPlayer.getServerWorld());   // lo que llevaba se quedó en el mundo donde murió
			pw.dirty = true;
			sendAllRooms(newPlayer);
		});
		ServerEntityWorldChangeEvents.AFTER_PLAYER_CHANGE_WORLD.register((player, origin, destination) -> {
			PlayerPower pw = get(player);
			releaseAll(player, pw, origin);   // lo que llevaba sigue en el mundo del que sale
			RoomOps.remove(player, pw);
			sendAllRooms(player);
		});
		EntityTrackingEvents.START_TRACKING.register((entity, player) -> CutStates.onStartTracking(entity, player));
		// Protección total contra daño por caída y Sello de Inmortalidad de la Operación de Juventud Eterna (Furo Shujutsu)
		ServerLivingEntityEvents.ALLOW_DAMAGE.register((entity, source, amount) -> {
			if (entity instanceof ServerPlayerEntity sp && ModAttachments.hasFruit(sp)) {
				PlayerPower pw = get(sp);
				if (source.isOf(DamageTypes.FALL) && (pw.room != null || pw.rroom != null || pw.fallImmunityTicks > 0 || (pw.isGravity() && pw.gravityMountId >= 0))) {
					sp.fallDistance = 0f;
					return false;
				}
				// Haki de Observación Ciego de Fujitora: 45% de esquiva instintiva mientras está activo
				if (pw.isGravity() && pw.scanTicks > 0 && !source.isOf(DamageTypes.OUT_OF_WORLD) && sp.getRandom().nextFloat() < 0.45f) {
					ServerWorld sw = sp.getServerWorld();
					sw.spawnParticles(GravityPowers.ASTRAL_DUST, sp.getX(), sp.getY() + 1.0, sp.getZ(), 18, 0.4, 0.6, 0.4, 0.04);
					sw.playSound(null, sp.getX(), sp.getY(), sp.getZ(), SoundEvents.ENTITY_PLAYER_ATTACK_SWEEP, SoundCategory.PLAYERS, 0.8f, 1.8f);
					sp.sendMessage(Text.literal("§d👁 Haki de Observación: ¡Ataque esquivado!"), true);
					return false;
				}
				// Si un golpe fuera a matarte mientras tienes activa la Operación de Juventud Eterna, anula la muerte
				if (pw.eternalYouthTicks > 0 && sp.getHealth() - amount <= 1.0f) {
					sp.setHealth(sp.getMaxHealth());
					sp.clearStatusEffects();
					sp.addStatusEffect(new StatusEffectInstance(StatusEffects.REGENERATION, 200, 3, false, false, true));
					sp.addStatusEffect(new StatusEffectInstance(StatusEffects.ABSORPTION, 400, 4, false, false, true));
					sp.addStatusEffect(new StatusEffectInstance(StatusEffects.RESISTANCE, 200, 3, false, false, true));
					ServerWorld w = sp.getServerWorld();
					w.spawnParticles(ParticleTypes.TOTEM_OF_UNDYING, sp.getX(), sp.getY() + 1.0, sp.getZ(), 60, 1.0, 1.2, 1.0, 0.2);
					w.spawnParticles(Techniques.YOUTH_DUST, sp.getX(), sp.getY() + 1.0, sp.getZ(), 60, 1.5, 1.0, 1.5, 0.05);
					w.playSound(null, sp.getX(), sp.getY(), sp.getZ(), SoundEvents.ITEM_TOTEM_USE, SoundCategory.PLAYERS, 1.0f, 1.4f);
					for (LivingEntity near : w.getEntitiesByClass(LivingEntity.class, sp.getBoundingBox().expand(6.0), e -> e != sp && e.isAlive())) {
						near.addStatusEffect(new StatusEffectInstance(ModEffects.STUN, 60, 0, false, true));
						Vec3d push = near.getPos().subtract(sp.getPos()).normalize().multiply(1.6).add(0, 0.5, 0);
						near.addVelocity(push.x, push.y, push.z);
						near.velocityModified = true;
					}
					sp.sendMessage(Text.literal("§6✦ Sello de Juventud Eterna: ¡Daño letal anulado y órganos reconstruidos!"), true);
					return false;
				}
			}
			return true;
		});
		ServerLivingEntityEvents.AFTER_DEATH.register((entity, source) -> {
			// Quien muere suelta lo que lleva y sus arranques pendientes se deshacen (sigue haciendo tick hasta reaparecer).
			if (entity instanceof ServerPlayerEntity sp) {
				PlayerPower dead = peek(sp.getUuid());
				if (dead != null) releaseAll(sp, dead, sp.getServerWorld());
			}
			Entity attacker = source.getAttacker();
			if (attacker instanceof ServerPlayerEntity sp && ModAttachments.hasFruit(sp)) {
				if (entity instanceof EnderDragonEntity || entity instanceof WitherEntity) {
					grantAwakening(sp);
				}
				if (entity != sp && !(entity instanceof ArmorStandEntity) && entity.getWorld() instanceof ServerWorld sw) {
					onSurgicalKill(sp, sw, entity);
				}
			}
			// La fruta aparece de vez en cuando: el Guardián Anciano (el mar) y el Warden la llevan dentro.
			if (attacker instanceof ServerPlayerEntity && !entity.getWorld().isClient) {
				float chance = entity instanceof ElderGuardianEntity ? 0.35f : entity instanceof WardenEntity ? 0.5f : 0f;
				if (chance > 0 && entity.getRandom().nextFloat() < chance) {
					entity.getWorld().spawnEntity(new ItemEntity(entity.getWorld(), entity.getX(), entity.getY() + 1, entity.getZ(), new ItemStack(ModItems.FRUIT)));
				}
			}
		});
		ServerLifecycleEvents.SERVER_STOPPED.register(server -> {
			POWERS.clear();
			CutStates.clearAll();
			GroundCuts.clearAll();
			Takt.clearAll();
			ElementEffects.clearAll();
			GravityPowers.clearAll();
			SurgicalSurvival.clearPending();
		});
	}

	public static void grantFruit(ServerPlayerEntity p) {
		grantFruit(p, 1);
	}

	public static void grantFruit(ServerPlayerEntity p, int kind) {
		int target = kind == 2 ? 2 : 1;
		boolean already = ModAttachments.hasFruit(p);
		PlayerPower pw = get(p);
		if (already && ModAttachments.getFruitKind(p) == target) {
			if (target == 2) {
				p.sendMessage(Text.literal("§5Ya llevas activa la Fruta Gravity (Zushi Zushi no Mi)."), false);
			} else {
				p.sendMessage(Text.translatable("opeope.msg.already"), false);
			}
			return;
		}
		if (already) {
			releaseAll(p, pw);
			RoomOps.remove(p, pw);
		}
		p.setAttached(ModAttachments.HAS_FRUIT, true);
		ModAttachments.setFruitKind(p, target);
		pw.fruitKind = target;
		pw.gravityMountId = -1;
		pw.bounty = ModAttachments.getBounty(p);
		applyMasteryAttributes(p, pw);
		pw.dirty = true;
		if (target == 2) {
			GravityPowers.equipYakuzaKasenSafely(p);
			p.sendMessage(Text.literal("§5✦ Has activado la Fruta Gravity (Zushi Zushi no Mi) — Almirante Fujitora."), false);
			p.sendMessage(Text.literal("§7Controles Gravity (sin ROOM): §f[G]§7 Gravito (Mayús+G vector) · §f[R]§7 Levitar roca/mob · §f[Alt]§7 Vuelo en roca · §f[Z/X/C/V/B]§7 Técnicas · §f[J]§7 Cambiar fruta"), false);
		} else {
			p.sendMessage(Text.translatable("opeope.msg.ate"), false);
			p.sendMessage(Text.translatable("opeope.msg.ate_help", Text.keybind("key.opeope.libro")), false);
		}
		p.getWorld().playSound(null, p.getX(), p.getY(), p.getZ(), SoundEvents.BLOCK_BEACON_ACTIVATE, SoundCategory.PLAYERS, 1f, target == 2 ? 0.55f : 0.7f);
	}

	public static void switchFruit(ServerPlayerEntity p) {
		if (!ModAttachments.hasFruit(p)) {
			grantFruit(p, 2);
			return;
		}
		int next = ModAttachments.getFruitKind(p) == 2 ? 1 : 2;
		grantFruit(p, next);
	}

	public static void removeFruit(ServerPlayerEntity p) {
		PlayerPower pw = get(p);
		releaseAll(p, pw);
		RoomOps.remove(p, pw);
		p.removeAttached(ModAttachments.HAS_FRUIT);
		p.removeAttached(ModAttachments.AWAKENING);
		pw.awakened = false;
		applyMasteryAttributes(p, pw);
		pw.dirty = true;
	}

	private static void onSurgicalKill(ServerPlayerEntity sp, ServerWorld sw, LivingEntity victim) {
		PlayerPower pw = get(sp);
		int prevTier = pw.masteryTier();
		float maxHp = Math.max(1f, victim.getMaxHealth());
		long base;
		if (victim instanceof EnderDragonEntity || victim instanceof WitherEntity || maxHp >= 150f) {
			base = Math.min(60_000_000L, Math.max(25_000_000L, 25_000_000L + (long) ((maxHp - 150f) * 200_000L)));
		} else if (maxHp >= 50f) {
			base = Math.min(12_000_000L, 4_000_000L + (long) ((maxHp - 50f) * 80_000L));
		} else {
			base = 1_000_000L + (long) (maxHp * 45_000L);
		}
		long now = sw.getTime();
		if (now - pw.lastKillTick <= 240L) pw.killStreak++;
		else pw.killStreak = 1;
		pw.lastKillTick = now;

		double mult = 1.0 + Math.min(2.0, (pw.killStreak - 1) * 0.25);
		long gain = Math.round(base * mult);
		pw.bounty += gain;
		ModAttachments.setBounty(sp, pw.bounty);

		boolean inside = inRoom(sp, victim.getPos());
		boolean shamblesReset = false;
		if (inside || prevTier >= 4) {
			sp.heal(Math.min(6.0f, 1.5f + 0.5f * prevTier + (pw.killStreak >= 3 ? 1.5f : 0f)));
			if (pw.cooldowns[Tech.SHAMBLES.ordinal()] > 0) {
				pw.cooldowns[Tech.SHAMBLES.ordinal()] = 0;
				pw.dirty = true;
				shamblesReset = true;
			}
		}
		if (pw.killStreak >= 3) {
			ExperienceOrbEntity.spawn(sw, victim.getPos().add(0, 0.5, 0), 6 + pw.killStreak * 2);
		}

		if (!promoteIfTierUp(sp, sw, pw, prevTier) && (base >= 4_000_000L || pw.killStreak >= 3)) {
			sp.sendMessage(Text.literal("§b+฿" + formatBerries(gain) + " §7(Total: §f฿" + formatBerries(pw.bounty)
				+ "§7) §eRacha ×" + pw.killStreak + (shamblesReset ? " · ¡Shambles recargado!" : "")), true);
		}
	}

	/** Otorga Berries de progreso por cirugías/extracciones quirúrgicas y actualiza rango + atributos. */
	public static void addBounty(ServerPlayerEntity sp, long gain) {
		if (gain <= 0 || !ModAttachments.hasFruit(sp)) return;
		PlayerPower pw = get(sp);
		int prevTier = pw.masteryTier();
		pw.bounty += gain;
		ModAttachments.setBounty(sp, pw.bounty);
		promoteIfTierUp(sp, sp.getServerWorld(), pw, prevTier);
	}

	private static boolean promoteIfTierUp(ServerPlayerEntity sp, ServerWorld sw, PlayerPower pw, int prevTier) {
		int afterTier = pw.masteryTier();
		if (afterTier <= prevTier) return false;
		applyMasteryAttributes(sp, pw);
		sw.playSound(null, sp.getX(), sp.getY(), sp.getZ(), SoundEvents.ENTITY_PLAYER_LEVELUP, SoundCategory.PLAYERS, 1.0f, 1.15f);
		sw.playSound(null, sp.getX(), sp.getY(), sp.getZ(), SoundEvents.BLOCK_BEACON_ACTIVATE, SoundCategory.PLAYERS, 0.9f, 1.4f);
		sw.spawnParticles(ParticleTypes.TOTEM_OF_UNDYING, sp.getX(), sp.getY() + 1.0, sp.getZ(), 32, 0.6, 0.8, 0.6, 0.15);
		sp.sendMessage(Text.literal("§6★ ¡NUEVO RANGO PIRATA: " + pw.masteryRankName()
			+ " §f(฿" + formatBerries(pw.bounty) + ")! §bEnfriamientos -" + (afterTier * 10) + "%"), false);
		return true;
	}

	private static String formatBerries(long v) {
		return java.text.NumberFormat.getIntegerInstance(new java.util.Locale("es", "ES")).format(v);
	}

	private static void applyMasteryAttributes(ServerPlayerEntity p, PlayerPower pw) {
		int tier = ModAttachments.hasFruit(p) ? pw.masteryTier() : 0;
		double hpBonus = switch (tier) { case 1 -> 2.0; case 2 -> 6.0; case 3 -> 10.0; case 4 -> 16.0; default -> 0.0; };
		double spdBonus = switch (tier) { case 1 -> 0.08; case 2 -> 0.14; case 3 -> 0.20; case 4 -> 0.25; default -> 0.0; };
		double dmgBonus = switch (tier) { case 2 -> 0.15; case 3 -> 0.28; case 4 -> 0.45; default -> 0.0; };
		setMasteryMod(p, EntityAttributes.GENERIC_MAX_HEALTH, MASTERY_HEALTH_ID, hpBonus, EntityAttributeModifier.Operation.ADD_VALUE);
		setMasteryMod(p, EntityAttributes.GENERIC_MOVEMENT_SPEED, MASTERY_SPEED_ID, spdBonus, EntityAttributeModifier.Operation.ADD_MULTIPLIED_TOTAL);
		setMasteryMod(p, EntityAttributes.GENERIC_ATTACK_DAMAGE, MASTERY_DAMAGE_ID, dmgBonus, EntityAttributeModifier.Operation.ADD_MULTIPLIED_TOTAL);
		if (p.getHealth() > p.getMaxHealth()) p.setHealth(p.getMaxHealth());
	}

	private static void setMasteryMod(ServerPlayerEntity p, RegistryEntry<EntityAttribute> attr, Identifier id,
									  double val, EntityAttributeModifier.Operation op) {
		EntityAttributeInstance inst = p.getAttributeInstance(attr);
		if (inst == null) return;
		inst.removeModifier(id);
		if (val > 1e-4) inst.addTemporaryModifier(new EntityAttributeModifier(id, val, op));
	}

	public static void grantAwakening(ServerPlayerEntity p) {
		if (ModAttachments.hasAwakening(p)) return;
		p.setAttached(ModAttachments.AWAKENING, true);
		get(p).dirty = true;
		p.sendMessage(Text.translatable("opeope.msg.awakening"), false);
		p.getWorld().playSound(null, p.getX(), p.getY(), p.getZ(), SoundEvents.UI_TOAST_CHALLENGE_COMPLETE, SoundCategory.PLAYERS, 1f, 1f);
	}

	/** En el agua (sumergido) la fruta no funciona, como con el mar en el juego. */
	public static boolean canUse(ServerPlayerEntity p) {
		return ModAttachments.hasFruit(p) && !p.isSubmergedInWater();
	}

	private static void cleanup(ServerPlayerEntity p) {
		PlayerPower pw = POWERS.get(p.getUuid());
		if (pw == null) return;
		releaseAll(p, pw);
		RoomOps.remove(p, pw);
		if (pw.bisturi) Techniques.bisturi(p, pw, false);
		POWERS.remove(p.getUuid());
	}

	/** Suelta todo lo de Takt, las armas y lo suspendido (al salir o perder la fruta), en el mundo donde está. */
	public static void releaseAll(ServerPlayerEntity p, PlayerPower pw) {
		releaseAll(p, pw, p.getServerWorld());
	}

	/**
	 * Lo mismo, buscando lo que lleva en from: el mundo que deja al cambiar de mundo o al reaparecer. Primero se deshacen
	 * los arranques pendientes (su suelo no se ha tocado); luego se suelta lo que lleva.
	 */
	public static void releaseAll(ServerPlayerEntity p, PlayerPower pw, ServerWorld from) {
		Takt.cancelPending(p, pw);
		Takt.dropAll(p, pw, false, from);
		GravityPowers.releaseGravityHeld(p, pw, from);
		pw.gravityCharging = false;
		pw.gravityChargeTicks = 0;
		pw.meteorCharging = false;
		pw.meteorChargeTicks = 0;
		if (p.getVehicle() instanceof PieceEntity pe && pe.isGravitySurf()) {
			p.stopRiding();
			pe.release(Vec3d.ZERO, false);
		}
		pw.gravityMountId = -1;
		Forge.dropWeapons(p, pw);
		Techniques.releaseSuspended(p, pw);
		pw.clearAerialWindow();
		pw.aerialChain = 0;
		pw.recentThrownPieces.clear();
		pw.lastThrownPieceId = -1;
		pw.aerialFinisherMult = 1.0f;
		Shambles.syncAerialWindow(p, pw);
	}

	private static void tick(MinecraftServer server) {
		GroundCuts.tick(server);
		for (ServerPlayerEntity p : server.getPlayerManager().getPlayerList()) {
			PlayerPower pw = POWERS.get(p.getUuid());
			if (pw == null) {
				if (!ModAttachments.hasFruit(p)) continue;
				pw = get(p);
			}
			PlayerTick.tick(p, pw);
			if (pw.dirty) {
				pw.dirty = false;
				syncState(p, pw);
			}
		}
		CutStates.tick(server);
		Takt.serverTick(server);
		ElementEffects.serverTick(server);
		GravityPowers.tickServer(server);
		SurgicalSurvival.tickPendingRestores(server);
		SilentRoom.tick(server);
	}

	public static void syncState(ServerPlayerEntity p, PlayerPower pw) {
		int flags = 0;
		if (ModAttachments.hasFruit(p)) flags |= Payloads.State.HAS_FRUIT;
		if (ModAttachments.hasAwakening(p)) flags |= Payloads.State.AWAKEN_UNLOCKED;
		if (pw.awakened) flags |= Payloads.State.AWAKENED;
		if (pw.reachTarget) flags |= Payloads.State.REACH_TARGET;
		if (pw.suspend) flags |= Payloads.State.SUSPEND;
		if (pw.unionMode) flags |= Payloads.State.UNION;
		if (pw.bisturi) flags |= Payloads.State.BISTURI;
		if (pw.unionSelected >= 0) flags |= Payloads.State.UNION_SELECTED;
		if (pw.curtainTicks > 0) flags |= Payloads.State.CURTAIN;
		if (pw.silentTicks > 0) flags |= Payloads.State.SILENT;
		if (pw.combatStyle == 2) flags |= Payloads.State.STYLE_SWORD;
		if (pw.combatStyle == 3) flags |= Payloads.State.STYLE_FIGHT;
		if (pw.freeMode) flags |= Payloads.State.FREE_MODE;
		if (pw.kroomBladeTicks > 0 || pw.anesthesia) flags |= Payloads.State.KROOM_BLADE;
		if (pw.eternalYouthTicks > 0) flags |= Payloads.State.ETERNAL_YOUTH;
		if (pw.zeroGravityRoom) flags |= Payloads.State.ZERO_G;
		if (pw.shamblesMode == 1) flags |= Payloads.State.SHAMBLES_MODE_SWAP;
		else if (pw.shamblesMode == 2) flags |= Payloads.State.SHAMBLES_MODE_ENTITIES;
		if (pw.shamblesMark >= 0 && pw.shamblesMarkTicks > 0) flags |= Payloads.State.SHAMBLES_MARKED;
		if (pw.isGravity()) {
			flags |= Payloads.State.GRAVITY_FRUIT;
			if ((pw.gravityVector & 1) != 0) flags |= Payloads.State.GRAVITY_VEC_BIT0;
			if ((pw.gravityVector & 2) != 0) flags |= Payloads.State.GRAVITY_VEC_BIT1;
			if (p.getVehicle() instanceof PieceEntity sr && sr.isGravitySurf()) flags |= Payloads.State.GRAVITY_RIDING;
			if (pw.gravityCharging) flags |= Payloads.State.GRAVITY_CHARGING;
			if (pw.meteorCharging) flags |= Payloads.State.GRAVITY_METEOR_CHARGING;
			if ((pw.meteorTier & 1) != 0) flags |= Payloads.State.GRAVITY_METEOR_B0;
			if ((pw.meteorTier & 2) != 0) flags |= Payloads.State.GRAVITY_METEOR_B1;
			if ((pw.meteorTier & 4) != 0) flags |= Payloads.State.GRAVITY_METEOR_B2;
		}
		int weapon = pw.weapons.isEmpty() ? -1 : pw.weaponKind;
		List<Integer> heldSrc = pw.isGravity() ? pw.gravityHeld : pw.takt;
		int[] held = new int[Math.min(heldSrc.size(), 64)];
		for (int i = 0; i < held.length; i++) held[i] = heldSrc.get(i);
		ServerPlayNetworking.send(p, new Payloads.State(flags, pw.pattern, heldSrc.size(), pw.taktMode.ordinal(), weapon, pw.scanTicks,
			pw.cooldowns.clone(), held));
	}

	// ------------------------------------------------------------------ ROOMs a los clientes

	public static void broadcastRoom(ServerPlayerEntity owner, int slot, Room room) {
		Payloads.Room payload = roomPayload(owner.getUuid(), slot, room, owner);
		for (ServerPlayerEntity sp : owner.getServer().getPlayerManager().getPlayerList()) {
			ServerPlayNetworking.send(sp, payload);
		}
	}

	private static Payloads.Room roomPayload(UUID owner, int slot, Room room, ServerPlayerEntity fallback) {
		if (room == null) {
			return new Payloads.Room(owner, slot, fallback.getWorld().getRegistryKey().getValue(), 0, 0, 0, 0, 0);
		}
		return new Payloads.Room(owner, slot, room.dim.getValue(), room.center.x, room.center.y, room.center.z, (float) room.radius, room.state.ordinal());
	}

	private static void sendAllRooms(ServerPlayerEntity to) {
		for (Map.Entry<UUID, PlayerPower> e : POWERS.entrySet()) {
			if (e.getValue().room != null) ServerPlayNetworking.send(to, roomPayload(e.getKey(), 0, e.getValue().room, to));
			if (e.getValue().rroom != null) ServerPlayNetworking.send(to, roomPayload(e.getKey(), 1, e.getValue().rroom, to));
		}
	}

	// ------------------------------------------------------------------ apuntar

	/**
	 * El primer bloque en la mira hasta range. Un rayo nunca carga ni genera terreno: un trozo de mundo sin cargar cuenta
	 * como que no toca nada (si no, apuntar al horizonte paraba el servidor mientras cargaba o creaba esos chunks).
	 */
	public static BlockHitResult raycastBlock(ServerPlayerEntity p, double range) {
		World w = p.getWorld();
		Vec3d eye = p.getEyePos();
		Vec3d end = eye.add(p.getRotationVector().multiply(range));
		Vec3d back = eye.subtract(end);
		BlockHitResult miss = BlockHitResult.createMissed(end, Direction.getFacing(back.x, back.y, back.z), BlockPos.ofFloored(end));
		RaycastContext ctx = new RaycastContext(eye, end, RaycastContext.ShapeType.OUTLINE, RaycastContext.FluidHandling.NONE, p);
		return BlockView.raycast(eye, end, ctx, (c, pos) -> {
			if (!w.isChunkLoaded(pos)) return miss;
			BlockState st = w.getBlockState(pos);
			return w.raycastBlock(eye, end, pos, c.getBlockShape(st, w, pos), st);
		}, c -> miss);
	}

	/** El punto donde mira: lo que toca, o el punto a esa distancia si no toca nada. */
	public static Vec3d aimPoint(ServerPlayerEntity p, double range) {
		BlockHitResult hit = raycastBlock(p, range);
		if (hit.getType() != HitResult.Type.MISS) return hit.getPos();
		return p.getEyePos().add(p.getRotationVector().multiply(range));
	}

	/** El ser o cosa que tiene en la mira (con un margen para objetos pequeños), si no hay un bloque delante. */
	public static Entity raycastEntity(ServerPlayerEntity p, double range, double margin, Predicate<Entity> filter) {
		Vec3d eye = p.getEyePos();
		Vec3d look = p.getRotationVector();
		BlockHitResult block = raycastBlock(p, range);
		double maxD = block.getType() == HitResult.Type.MISS ? range : block.getPos().distanceTo(eye) + 0.5;
		Vec3d end = eye.add(look.multiply(maxD));
		Box box = p.getBoundingBox().stretch(look.multiply(maxD)).expand(1 + margin);
		Entity best = null;
		double bestD = Double.MAX_VALUE;
		for (Entity e : p.getWorld().getOtherEntities(p, box, e -> filter.test(e) && !e.isSpectator())) {
			Box eb = e.getBoundingBox().expand(margin + e.getTargetingMargin());
			var hit = eb.raycast(eye, end);
			double d;
			if (eb.contains(eye)) d = 0;
			else if (hit.isPresent()) d = eye.squaredDistanceTo(hit.get());
			else continue;
			if (d < bestD) {
				bestD = d;
				best = e;
			}
		}
		return best;
	}

	/** Igual, pero también acepta objetos casi en la mira (unos grados), para la piedra marcador lejana. */
	public static Entity raycastEntityLoose(ServerPlayerEntity p, double range, double degrees, Predicate<Entity> filter) {
		Entity direct = raycastEntity(p, range, 0.3, filter);
		if (direct != null) return direct;
		Vec3d eye = p.getEyePos();
		Vec3d look = p.getRotationVector();
		double cos = Math.cos(Math.toRadians(degrees));
		Entity best = null;
		double bestDot = cos;
		for (Entity e : p.getWorld().getOtherEntities(p, p.getBoundingBox().expand(range), filter)) {
			Vec3d to = e.getBoundingBox().getCenter().subtract(eye);
			double len = to.length();
			if (len > range || len < 0.5) continue;
			double dot = to.dotProduct(look) / len;
			if (dot > bestDot) {
				bestDot = dot;
				best = e;
			}
		}
		return best;
	}

	/** Un ROOM tuyo que contenga ese punto. */
	public static Room roomAt(ServerPlayerEntity p, Vec3d pos) {
		PlayerPower pw = get(p);
		if (pw.room != null && pw.room.dim == p.getWorld().getRegistryKey() && pw.room.contains(pos)) return pw.room;
		if (pw.rroom != null && pw.rroom.dim == p.getWorld().getRegistryKey() && pw.rroom.contains(pos)) return pw.rroom;
		return null;
	}

	public static boolean inRoom(ServerPlayerEntity p, Vec3d pos) {
		return roomAt(p, pos) != null;
	}

	public static void noRoom(ServerPlayerEntity p) {
		p.sendMessage(Text.translatable("opeope.msg.no_room"), true);
	}

	// ------------------------------------------------------------------ cortes que llegan del cliente

	public static void handleCut(ServerPlayerEntity p, int kind, float[] raw) {
		if (!canUse(p)) return;
		PlayerPower pw = get(p);
		if (pw.isGravity()) {
			GravityPowers.jigokuTabi(p, pw);
			return;
		}
		if (kind == 1 && pw.bisturi) Techniques.bisturi(p, pw, false);
		Room room = pw.room;
		if (room == null || room.dim != p.getWorld().getRegistryKey()) {
			// Si tienes la hoja gigante de K-ROOM activa, puedes cortar a 26m incluso sin ROOM externo
			if (kind == 2 && pw.kroomBladeTicks > 0) {
				Vec3d eye = p.getEyePos();
				Vec3d look = p.getRotationVector();
				List<CutEngine.Plane> kPlanes = new ArrayList<>();
				for (int i = 0; i + 5 < raw.length; i += 6) {
					Vec3d n = new Vec3d(raw[i], raw[i + 1], raw[i + 2]);
					if (n.lengthSquared() > 1e-6) kPlanes.add(new CutEngine.Plane(n.normalize(), eye));
				}
				if (!kPlanes.isEmpty()) {
					kPlanes = Patterns.expand(p, kPlanes.get(0), pw.pattern);
					CutEngine.cut(p, kPlanes, CutEngine.Region.sphere(eye.add(look.multiply(12.0)), 14.0).withEye(eye, look), pw.suspend, 0, 18f, false, false);
					pw.cooldown(Tech.AMPUTATE);
				}
				return;
			}
			noRoom(p);
			return;
		}
		if (kind == 3 && !pw.bisturi) return;
		if ((kind == 0 || kind == 2) && pw.onCooldown(Tech.AMPUTATE)) return;
		List<CutEngine.Plane> planes = new ArrayList<>();
		for (int i = 0; i + 5 < raw.length; i += 6) {
			Vec3d n = new Vec3d(raw[i], raw[i + 1], raw[i + 2]);
			if (n.lengthSquared() < 1e-6 || !Double.isFinite(n.lengthSquared())) continue;
			Vec3d point = new Vec3d(raw[i + 3], raw[i + 4], raw[i + 5]);
			if (point.squaredDistanceTo(p.getEyePos()) > 400 && !room.contains(point)) point = p.getEyePos();
			planes.add(new CutEngine.Plane(n.normalize(), point));
		}
		if (planes.isEmpty()) return;
		if (kind == 0 || kind == 2) planes = Patterns.expand(p, planes.get(0), pw.pattern);
		Vec3d eye = p.getEyePos();
		Vec3d look = p.getRotationVector();
		CutEngine.Region region = CutEngine.Region.of(room).withEye(eye, look);
		if (kind == 2) {
			double maxK = pw.kroomBladeTicks > 0 ? Math.min(room.radius, 36.0) : Math.min(room.radius, 24.0);
			region.withLimit(eye, maxK);
		} else if (pw.reachTarget) {
			Entity target = raycastEntity(p, 200, 0.2, e -> e.isAlive());
			Vec3d at = target != null ? target.getBoundingBox().getCenter() : aimPoint(p, 200);
			region.withLimit(at, target != null ? Math.max(2.5, target.getBoundingBox().getAverageSideLength() * 1.5) : 4.0);
		}
		// El suelo recuerda el corte salvo si llega solo hasta un objetivo (Kikoku, "Alcance: solo el objetivo").
		CutEngine.cut(p, planes, region, pw.suspend && kind != 3, 0, 0f, true, region.lc == null, kind == 3);
		if (kind == 3) {
			// Bisturí: cada clic corta ya, pero lo cortado se queda quieto con la costura encendida hasta que sueltas.
			for (PieceEntity pe : CutEngine.SPAWNED) {
				if (pe.isRemoved()) continue;
				pe.hold(PieceEntity.HOLD_BISTURI);
				pw.bisturiHeld.add(pe.getId());
			}
		}
		if (kind == 0 || kind == 2) pw.cooldown(Tech.AMPUTATE);
		pw.roomUsedDuringHold = true;
	}

	public static int roomMaxRadius() {
		return (int) OpeConfig.ROOM_MAX_RADIUS;
	}
}
