package dev.opeope.power.mera;

import dev.opeope.OpeOpeMod;
import dev.opeope.net.MeraFx;
import dev.opeope.power.PlayerPower;
import dev.opeope.power.PowerManager;
import dev.opeope.power.Tech;
import dev.opeope.registry.ModAttachments;
import dev.opeope.registry.ModBlocks;
import dev.opeope.registry.ModDamage;
import dev.opeope.registry.ModItems;
import net.fabricmc.fabric.api.entity.event.v1.ServerEntityWorldChangeEvents;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents;
import net.fabricmc.fabric.api.event.player.AttackEntityCallback;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.ShapeContext;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.attribute.EntityAttributeInstance;
import net.minecraft.entity.attribute.EntityAttributeModifier;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.entity.boss.WitherEntity;
import net.minecraft.entity.boss.dragon.EnderDragonEntity;
import net.minecraft.entity.damage.DamageSource;
import net.minecraft.entity.damage.DamageTypes;
import net.minecraft.entity.decoration.ArmorStandEntity;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.entity.mob.ElderGuardianEntity;
import net.minecraft.entity.mob.WardenEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.projectile.ProjectileEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.tag.BlockTags;
import net.minecraft.registry.tag.DamageTypeTags;
import net.minecraft.registry.tag.FluidTags;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvent;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Identifier;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.GameRules;
import net.minecraft.world.RaycastContext;
import net.minecraft.world.World;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * La Mera Mera no Mi de Portgas D. Ace: una Logia de fuego.
 * <p>
 * <b>Logia:</b> los golpes, flechas, tridentes y explosiones atraviesan el cuerpo (se abre un hueco de llamas y se cierra);
 * el fuego, la lava y el hielo no le hacen nada. Lo que sí le toca: quien tiene Haki (el Warden, el Wither, el Dragón,
 * el Guardián Anciano, otro usuario de fruta o quien empuña Kikoku o Yakuza Kasen), el agua (mojado no hay Logia y
 * sumergido la fruta no funciona), el vacío, el hambre, el veneno y ahogarse. Las caídas no le rompen: cae hecho
 * llamas. Y el fuego de otro usuario de la Mera Mera no le quema.
 * <p>
 * <b>Técnicas (las del anime):</b> Hiken, Dai Enkai: Entei, Higan, Jūjika, Hotarubi → Hidaruma, Kyokaen, Enjōmō,
 * Shinka: Shiranui, Enkai: Hibashira, Kagerō, Hikyaku, el cuerpo hecho chorro de llamas y el Cuerpo de llamas.
 */
public final class MeraPowers {
	private MeraPowers() {}

	public static final int KIND = 3;

	static final int HIKEN_FULL = 30, ENTEI_FULL = 70, KAGERO_MAX = 34, DASH_TICKS = 6;
	private static final Identifier LOGIA_BLAST_ID = OpeOpeMod.id("mera_logia_blast");

	private static final Map<UUID, MeraState> STATES = new HashMap<>();
	private static final List<Shot> SHOTS = new ArrayList<>();
	private static final List<Zone> ZONES = new ArrayList<>();
	private static final List<Delayed> DELAYED = new ArrayList<>();
	/** Proyectiles que ya atravesaron a un Logia (para el efecto, una vez por proyectil). */
	private static final Map<Integer, Integer> PASSED = new HashMap<>();
	private static int nextId = 1;

	// ------------------------------------------------------------------ estado

	public static boolean isMera(PlayerEntity p) {
		return ModAttachments.hasFruit(p) && ModAttachments.getFruitKind(p) == KIND;
	}

	public static MeraState state(PlayerEntity p) {
		return STATES.computeIfAbsent(p.getUuid(), k -> new MeraState());
	}

	public static MeraState peek(PlayerEntity p) {
		return STATES.get(p.getUuid());
	}

	/** Logia activa ahora: comió la fruta y no está mojado. */
	public static boolean logia(ServerPlayerEntity p) {
		return isMera(p) && !p.isTouchingWater() && !p.isSubmergedInWater();
	}

	/** Quien tiene Haki (o lo que lo hace en Minecraft) sí golpea a un Logia. */
	public static boolean hasHaki(Entity e) {
		if (e == null) return false;
		if (e instanceof WardenEntity || e instanceof WitherEntity || e instanceof EnderDragonEntity || e instanceof ElderGuardianEntity) return true;
		if (e instanceof PlayerEntity pl) {
			if (ModAttachments.hasFruit(pl)) return true;
			ItemStack m = pl.getMainHandStack();
			return (ModItems.KIKOKU != null && m.isOf(ModItems.KIKOKU)) || (ModItems.YAKUZA_KASEN != null && m.isOf(ModItems.YAKUZA_KASEN));
		}
		if (e instanceof ProjectileEntity pr) return hasHaki(pr.getOwner());
		return false;
	}

	public static void clearAll() {
		STATES.clear();
		SHOTS.clear();
		ZONES.clear();
		DELAYED.clear();
		PASSED.clear();
	}

	/** Al comer la fruta. */
	public static void onGrant(ServerPlayerEntity p) {
		MeraState st = state(p);
		st.fireBody = false;
		st.hikenCharging = st.enteiCharging = false;
		st.kageroTicks = st.dashTicks = 0;
		p.extinguish();
		ServerWorld w = p.getServerWorld();
		Vec3d c = p.getPos().add(0, 1, 0);
		w.spawnParticles(ParticleTypes.FLAME, c.x, c.y, c.z, 80, 0.5, 0.9, 0.5, 0.08);
		w.spawnParticles(ParticleTypes.LAVA, c.x, c.y, c.z, 12, 0.4, 0.6, 0.4, 0.1);
		snd(w, c, SoundEvents.ENTITY_BLAZE_SHOOT, 1.2f, 0.6f);
		send(w, MeraFx.BODY, p.getId(), 0, c, Vec3d.ZERO, 1f, 1f, 50, 1);
		p.sendMessage(Text.literal("§6🔥 Has comido la Mera Mera no Mi — el poder de Portgas D. Ace."), false);
		p.sendMessage(Text.literal("§7Logia: los golpes te atraviesan. El agua y el Haki sí te tocan. §fH§7 abre el libro."), false);
	}

	/** Al dejar la fruta (cambiar a otra o quitarla). */
	public static void onRevoke(ServerPlayerEntity p) {
		quench(p, p.getServerWorld(), true);
		blastProof(p, false);
		STATES.remove(p.getUuid());
	}

	/**
	 * Las explosiones atraviesan a un Logia: tampoco lo lanzan por los aires (Minecraft empuja con la onda aunque el daño
	 * no entre). Mojado no hay Logia y la onda sí lo mueve.
	 */
	private static void blastProof(ServerPlayerEntity p, boolean on) {
		EntityAttributeInstance inst = p.getAttributeInstance(EntityAttributes.GENERIC_EXPLOSION_KNOCKBACK_RESISTANCE);
		if (inst == null || inst.hasModifier(LOGIA_BLAST_ID) == on) return;
		if (on) inst.addTemporaryModifier(new EntityAttributeModifier(LOGIA_BLAST_ID, 1.0, EntityAttributeModifier.Operation.ADD_VALUE));
		else inst.removeModifier(LOGIA_BLAST_ID);
	}

	/**
	 * Se apaga lo que Ace tiene en marcha: las cargas, el chorro, el impulso y las luciérnagas. Al morir, al salir del
	 * servidor o al dejar la fruta, también el Cuerpo de llamas (body); al cambiar de mundo el cuerpo sigue encendido.
	 * w es el mundo donde se veía (el que deja).
	 */
	private static void quench(ServerPlayerEntity p, ServerWorld w, boolean body) {
		MeraState st = STATES.get(p.getUuid());
		if (st == null) return;
		boolean changed = false;
		if (st.hikenCharging || st.enteiCharging) {
			st.hikenCharging = st.enteiCharging = false;
			send(w, MeraFx.CHARGE_CANCEL, p.getId(), 0, p.getPos(), Vec3d.ZERO, 0f, 0f, 0, 0);
			changed = true;
		}
		if (st.kageroTicks > 0) {
			st.kageroTicks = 0;
			send(w, MeraFx.KAGERO_END, p.getId(), 0, p.getPos(), Vec3d.ZERO, 0f, 0f, 0, 0);
		}
		st.dashTicks = 0;
		if (st.hotarubiId >= 0) {
			send(w, MeraFx.ZONE_END, p.getId(), st.hotarubiId, st.hotarubiCenter, Vec3d.ZERO, 0f, 0f, 0, 0);
			st.hotarubiId = -1;
			st.hotarubiTicks = 0;
		}
		if (body && st.fireBody) {
			st.fireBody = false;
			send(w, MeraFx.BODY, p.getId(), 0, p.getPos(), Vec3d.ZERO, 0f, 0f, 0, 0);
			changed = true;
		}
		if (changed) {
			PlayerPower pw = PowerManager.peek(p.getUuid());
			if (pw != null) pw.dirty = true;
		}
	}

	/** Para la galería de pruebas: fuera todo lo de la técnica anterior (cuerpo, cargas, disparos y zonas). */
	public static void resetForTest(ServerPlayerEntity p) {
		onRevoke(p);
		SHOTS.clear();
		ZONES.clear();
		DELAYED.clear();
		PASSED.clear();
		p.extinguish();
	}

	// ------------------------------------------------------------------ registro

	public static void init() {
		ServerLivingEntityEvents.ALLOW_DAMAGE.register(MeraPowers::allowDamage);
		// las llamas mueren con él: nada de puños ni soles que salen de un cuerpo ya caído
		ServerLivingEntityEvents.AFTER_DEATH.register((entity, source) -> {
			if (entity instanceof ServerPlayerEntity sp) quench(sp, sp.getServerWorld(), true);
		});
		// lo que tenía en marcha se queda en el mundo del que sale (las luciérnagas tienen un sitio en ese mundo)
		ServerEntityWorldChangeEvents.AFTER_PLAYER_CHANGE_WORLD.register((player, origin, destination) -> quench(player, origin, false));
		ServerPlayerEvents.AFTER_RESPAWN.register((oldPlayer, newPlayer, alive) -> quench(newPlayer, oldPlayer.getServerWorld(), !alive));
		// si se va con un puño a medio cargar, al volver no se dispara solo
		ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
			ServerPlayerEntity sp = handler.getPlayer();
			quench(sp, sp.getServerWorld(), true);
			STATES.remove(sp.getUuid());
		});
		AttackEntityCallback.EVENT.register((player, world, hand, entity, hit) -> {
			if (!world.isClient && player instanceof ServerPlayerEntity sp && isMera(sp) && !sp.isSpectator()
				&& sp.getMainHandStack().isEmpty() && entity instanceof LivingEntity le && le.isAlive()) {
				MeraState st = state(sp);
				// los puños de Ace arden: el golpe normal prende al que lo recibe
				if (!sp.isTouchingWater()) {
					le.setOnFireFor(st.fireBody ? 6f : 3f);
					Vec3d at = le.getPos().add(0, le.getHeight() * 0.6, 0);
					send(sp.getServerWorld(), MeraFx.PUNCH, sp.getId(), 0, at, sp.getRotationVec(1f), 0.8f, st.fireBody ? 1.4f : 1f, 8, 0);
					snd(sp.getServerWorld(), at, SoundEvents.ITEM_FIRECHARGE_USE, 0.5f, 1.5f);
				}
			}
			return ActionResult.PASS;
		});
	}

	private static boolean allowDamage(LivingEntity entity, DamageSource source, float amount) {
		if (!(entity instanceof ServerPlayerEntity sp) || !isMera(sp)) return true;
		MeraState st = state(sp);
		if (source.isIn(DamageTypeTags.BYPASSES_INVULNERABILITY)) return true;
		// el fuego no quema al fuego (tampoco el de otro usuario de la Mera Mera), y las llamas deshacen el hielo
		if (source.isIn(DamageTypeTags.IS_FIRE) || source.isOf(DamageTypes.HOT_FLOOR) || source.isIn(DamageTypeTags.IS_FREEZING)
			|| source.isOf(ModDamage.MERA)) {
			if (sp.isOnFire()) sp.extinguish();
			return false;
		}
		if (source.isIn(DamageTypeTags.IS_FALL)) {
			if (st.fallSafeTicks > 0) {
				sp.fallDistance = 0f;
				return false;
			}
			if (sp.isTouchingWater() || sp.isSubmergedInWater()) return true;   // mojado: sin Logia
			// un Logia no se rompe al caer: las piernas se deshacen en llamas contra el suelo y vuelven a formarse
			land(sp, st, amount);
			return false;
		}
		// hecho un chorro de llamas no hay cuerpo que golpear
		if (st.dashTicks > 0 && !source.isIn(DamageTypeTags.IS_DROWNING) && !source.isIn(DamageTypeTags.IS_FALL)) return false;
		if (sp.isTouchingWater() || sp.isSubmergedInWater()) return true;   // mojado: sin Logia
		Entity attacker = source.getAttacker();
		Entity direct = source.getSource();
		// lo que provoca él mismo (su dinamita, su flecha que vuelve a caer) no lleva Haki contra él
		boolean own = attacker == sp || (attacker == null && direct == sp);
		if (!own && (hasHaki(attacker) || hasHaki(direct))) {
			// Haki: el golpe encuentra el cuerpo. Las llamas parpadean y el daño entra.
			Vec3d at = hitPoint(sp, attacker != null ? attacker : direct);
			send(sp.getServerWorld(), MeraFx.HAKI_HIT, sp.getId(), 0, at, Vec3d.ZERO, 0.8f, 1f, 10, 0);
			if (st.logiaNoticeCooldown <= 0) {
				sp.sendMessage(Text.literal("§c✖ ¡Haki! Ese golpe sí toca tu cuerpo."), true);
				st.logiaNoticeCooldown = 60;
			}
			return true;
		}
		boolean physical = attacker != null || direct != null || source.isIn(DamageTypeTags.IS_EXPLOSION)
			|| source.isIn(DamageTypeTags.IS_PROJECTILE) || source.isOf(DamageTypes.FALLING_ANVIL)
			|| source.isOf(DamageTypes.FALLING_BLOCK) || source.isOf(DamageTypes.FALLING_STALACTITE)
			|| source.isOf(DamageTypes.STALAGMITE) || source.isOf(DamageTypes.CACTUS)
			|| source.isOf(DamageTypes.SWEET_BERRY_BUSH) || source.isOf(DamageTypes.GENERIC)
			|| source.isOf(DamageTypes.FLY_INTO_WALL) || source.isOf(DamageTypes.LIGHTNING_BOLT);
		if (!physical) return true;
		scatter(sp, st, attacker != null && attacker != sp ? attacker : direct, source.isIn(DamageTypeTags.IS_EXPLOSION));
		// quien golpea con la mano a un cuerpo de fuego se quema
		if (attacker instanceof LivingEntity le && attacker != sp && attacker == direct && le.squaredDistanceTo(sp) < 25) {
			le.setOnFireFor(st.fireBody ? 6f : 2f);
			if (st.fireBody) {
				le.timeUntilRegen = 0;
				le.damage(burn(sp), 2.5f);
			}
		}
		return false;
	}

	/** El golpe atraviesa: se abre un hueco de llamas donde pegó y vuelve a cerrarse. */
	private static void scatter(ServerPlayerEntity sp, MeraState st, Entity from, boolean blast) {
		if (st.scatterCooldown > 0) return;
		st.scatterCooldown = 3;
		Vec3d at = hitPoint(sp, from);
		Vec3d dir = from != null ? sp.getPos().add(0, 1, 0).subtract(from.getPos().add(0, from.getHeight() * 0.5, 0)) : new Vec3d(0, 0, 1);
		if (dir.lengthSquared() < 1e-6) dir = new Vec3d(0, 0, 1);
		dir = dir.normalize();
		ServerWorld w = sp.getServerWorld();
		send(w, MeraFx.SCATTER, sp.getId(), 0, at, dir, blast ? 1.6f : 0.75f, 1f, blast ? 16 : 12, blast ? 1 : 0);
		snd(w, at, SoundEvents.ENTITY_BLAZE_SHOOT, 0.55f, 1.6f + w.random.nextFloat() * 0.2f);
		snd(w, at, SoundEvents.BLOCK_FIRE_AMBIENT, 0.9f, 1.4f);
		if (st.logiaNoticeCooldown <= 0) {
			sp.sendMessage(Text.literal("§6🔥 Logia: el golpe atraviesa tus llamas"), true);
			st.logiaNoticeCooldown = 80;
		}
	}

	/** Cae desde alto: en vez de romperse, las piernas estallan en llamas contra el suelo y se rehacen. */
	private static void land(ServerPlayerEntity sp, MeraState st, float amount) {
		sp.fallDistance = 0f;
		if (st.scatterCooldown > 0) return;
		st.scatterCooldown = 6;
		ServerWorld w = sp.getServerWorld();
		Vec3d at = sp.getPos().add(0, 0.35, 0);
		send(w, MeraFx.SCATTER, sp.getId(), 0, at, new Vec3d(0, 1, 0), Math.min(1.4f, 0.7f + amount * 0.04f), 1f, 12, 0);
		snd(w, at, SoundEvents.ENTITY_BLAZE_SHOOT, 0.45f, 1.7f);
		snd(w, at, SoundEvents.BLOCK_FIRE_AMBIENT, 0.9f, 1.3f);
	}

	private static Vec3d hitPoint(ServerPlayerEntity sp, Entity from) {
		Vec3d mid = sp.getPos().add(0, 1.05, 0);
		if (from == null) return mid;
		Vec3d d = from.getPos().add(0, from.getHeight() * 0.6, 0).subtract(mid);
		double y = MathHelper.clamp(from.getEyeY() - sp.getY(), 0.3, 1.7);
		Vec3d flat = new Vec3d(d.x, 0, d.z);
		if (flat.lengthSquared() > 1e-6) flat = flat.normalize().multiply(0.3);
		return new Vec3d(sp.getX() + flat.x, sp.getY() + y, sp.getZ() + flat.z);
	}

	/** Para el mixin de proyectiles: ¿este proyectil atraviesa a ese jugador? */
	public static boolean projectilePasses(ProjectileEntity pr, Entity target) {
		if (!(target instanceof ServerPlayerEntity sp) || !logia(sp)) return false;
		if (pr.getOwner() == sp) return true;   // lo suyo no lleva Haki contra él
		if (hasHaki(pr.getOwner()) || hasHaki(pr)) return false;
		Integer seen = PASSED.get(pr.getId());
		if (seen == null || seen != sp.getId()) {
			PASSED.put(pr.getId(), sp.getId());
			scatter(sp, state(sp), pr, false);
		}
		return true;
	}

	// ------------------------------------------------------------------ teclas

	/** Las órdenes del cliente para la Mera Mera. Devuelve true si la orden era de la fruta (no sigue a Law). */
	public static boolean handle(ServerPlayerEntity p, PlayerPower pw, int action, int arg) {
		// ni muerto ni de espectador (al morir ya se apagó lo que tenía en marcha: soltar la tecla no dispara nada)
		if (!p.isAlive() || p.isSpectator()) return action >= MeraActions.HIKEN_START && action <= MeraActions.BODY;
		MeraState st = state(p);
		switch (action) {
			case MeraActions.HIKEN_START -> hikenStart(p, pw, st);
			case MeraActions.HIKEN_RELEASE -> hikenRelease(p, pw, st);
			case MeraActions.ENTEI_START -> enteiStart(p, pw, st);
			case MeraActions.ENTEI_RELEASE -> enteiRelease(p, pw, st);
			case MeraActions.KAGERO_START -> kageroStart(p, pw, st);
			case MeraActions.KAGERO_END -> kageroEnd(p, pw, st);
			case MeraActions.DASH -> dash(p, pw, st);
			case MeraActions.HIKYAKU -> hikyaku(p, pw, st);
			case MeraActions.HIGAN -> higan(p, pw, st);
			case MeraActions.JUJIKA -> jujika(p, pw, st);
			case MeraActions.HOTARUBI -> hotarubi(p, pw, st, false);
			case MeraActions.HIDARUMA -> hotarubi(p, pw, st, true);
			case MeraActions.KYOKAEN -> kyokaen(p, pw, st);
			case MeraActions.ENJOMO -> enjomo(p, pw, st);
			case MeraActions.SHIRANUI -> shiranui(p, pw, st);
			case MeraActions.HIBASHIRA -> hibashira(p, pw, st);
			case MeraActions.BODY -> toggleBody(p, pw, st);
			default -> {
				return false;
			}
		}
		return true;
	}

	private static boolean ready(ServerPlayerEntity p, PlayerPower pw, Tech t, String name) {
		if (pw.freeMode) return true;
		int cd = pw.cooldowns[t.ordinal()];
		if (cd <= 0) return true;
		p.sendMessage(Text.literal("§6" + name + " §7aún se aviva… §f" + String.format("%.1f", cd / 20.0) + " s"), true);
		return false;
	}

	private static void cool(PlayerPower pw, Tech t, int ticks) {
		pw.cooldowns[t.ordinal()] = pw.freeMode ? 0 : ticks;
		pw.dirty = true;
	}

	private static boolean wetBlock(ServerPlayerEntity p, MeraState st) {
		if (!p.isTouchingWater()) return false;
		if (st.wetNoticeCooldown <= 0) {
			p.sendMessage(Text.literal("§b💧 Estás mojado: tus llamas no prenden"), true);
			st.wetNoticeCooldown = 40;
		}
		snd(p.getServerWorld(), p.getPos(), SoundEvents.BLOCK_FIRE_EXTINGUISH, 0.6f, 1.2f);
		send(p.getServerWorld(), MeraFx.STEAM, p.getId(), 0, p.getPos().add(0, 1, 0), Vec3d.ZERO, 1f, 1f, 20, 0);
		return true;
	}

	// --- Hiken: el puño de fuego

	private static void hikenStart(ServerPlayerEntity p, PlayerPower pw, MeraState st) {
		if (st.hikenCharging || st.enteiCharging || wetBlock(p, st)) return;
		if (!ready(p, pw, MeraTechs.HIKEN, "Hiken")) return;
		st.hikenCharging = true;
		st.hikenCharge = 0;
		pw.dirty = true;
		send(p.getServerWorld(), MeraFx.HIKEN_CHARGE, p.getId(), 0, hand(p, 1), Vec3d.ZERO, 0.5f, 0f, 120, 0);
		snd(p.getServerWorld(), p.getPos(), SoundEvents.ITEM_FIRECHARGE_USE, 0.8f, 0.7f);
	}

	private static void hikenRelease(ServerPlayerEntity p, PlayerPower pw, MeraState st) {
		if (!st.hikenCharging) return;
		st.hikenCharging = false;
		pw.dirty = true;
		ServerWorld w = p.getServerWorld();
		if (p.isTouchingWater()) {
			send(w, MeraFx.CHARGE_CANCEL, p.getId(), 0, p.getPos(), Vec3d.ZERO, 0f, 0f, 0, 0);
			return;
		}
		float k = 0.35f + 0.65f * Math.min(1f, st.hikenCharge / (float) HIKEN_FULL);
		Vec3d dir = p.getRotationVec(1f);
		Vec3d start = hand(p, 1).add(dir.multiply(0.6));
		double radius = 1.0 + 1.7 * k;
		double speed = 2.0 + 0.9 * k;
		int life = (int) (22 + 20 * k);
		Shot s = new Shot(Shot.HIKEN, p, start, dir.multiply(speed), radius, 8f + 14f * k, life);
		s.power = k;
		s.pierce = 99;
		SHOTS.add(s);
		send(w, MeraFx.HIKEN_SHOT, p.getId(), s.id, start, s.vel, (float) radius, k, life, 0);
		snd(w, start, SoundEvents.ENTITY_BLAZE_SHOOT, 1.3f, 0.55f);
		snd(w, start, SoundEvents.ENTITY_GHAST_SHOOT, 0.9f, 0.6f);
		snd(w, start, SoundEvents.ITEM_FIRECHARGE_USE, 1.2f, 0.5f);
		// el retroceso del puñetazo
		p.addVelocity(-dir.x * 0.25 * k, 0.05, -dir.z * 0.25 * k);
		p.velocityModified = true;
		cool(pw, MeraTechs.HIKEN, MeraTechs.HIKEN_CD);
	}

	// --- Dai Enkai: Entei

	private static void enteiStart(ServerPlayerEntity p, PlayerPower pw, MeraState st) {
		if (st.enteiCharging || st.hikenCharging || wetBlock(p, st)) return;
		if (!ready(p, pw, MeraTechs.ENTEI, "Dai Enkai: Entei")) return;
		st.enteiCharging = true;
		st.enteiCharge = 0;
		pw.dirty = true;
		send(p.getServerWorld(), MeraFx.ENTEI_CHARGE, p.getId(), 0, sunPos(p, 0f), Vec3d.ZERO, 0.6f, 0f, ENTEI_FULL + 200, 0);
		snd(p.getServerWorld(), p.getPos(), SoundEvents.BLOCK_RESPAWN_ANCHOR_CHARGE, 1f, 0.5f);
		p.sendMessage(Text.literal("§6☀ Dai Enkai… §7suelta para lanzar el sol"), true);
	}

	static float sunRadius(int charge) {
		float k = Math.min(1f, charge / (float) ENTEI_FULL);
		return 1.0f + 5.0f * k;
	}

	/** Dónde flota el sol mientras se carga: bien alto, para que su borde de llamas no le tape la vista a Ace. */
	static Vec3d sunPos(ServerPlayerEntity p, float radius) {
		return p.getPos().add(0, p.getHeight() + 1.6 + radius * 1.15, 0);
	}

	private static void enteiRelease(ServerPlayerEntity p, PlayerPower pw, MeraState st) {
		if (!st.enteiCharging) return;
		st.enteiCharging = false;
		pw.dirty = true;
		ServerWorld w = p.getServerWorld();
		if (st.enteiCharge < 12 || p.isTouchingWater()) {
			send(w, MeraFx.CHARGE_CANCEL, p.getId(), 0, p.getPos(), Vec3d.ZERO, 0f, 0f, 0, 1);
			snd(w, p.getPos(), SoundEvents.BLOCK_FIRE_EXTINGUISH, 0.7f, 0.8f);
			return;
		}
		float r = sunRadius(st.enteiCharge);
		Vec3d from = sunPos(p, r);
		Vec3d target = aimPoint(p, 90);
		Vec3d dir = target.subtract(from);
		if (dir.lengthSquared() < 1e-4) dir = p.getRotationVec(1f);
		dir = dir.normalize();
		// el sol sale ya por delante de Ace (no le pasa por encima de la cabeza tapándole la vista)
		from = from.add(dir.multiply(r * 0.8 + 1.0));
		double speed = 1.25;
		Shot s = new Shot(Shot.ENTEI, p, from, dir.multiply(speed), r, 16f + 4.5f * r, 110);
		s.power = r;
		s.pierce = 0;
		SHOTS.add(s);
		send(w, MeraFx.ENTEI_SHOT, p.getId(), s.id, from, s.vel, r, Math.min(1f, st.enteiCharge / (float) ENTEI_FULL), 110, 0);
		snd(w, from, SoundEvents.ENTITY_BLAZE_SHOOT, 2f, 0.4f);
		snd(w, from, SoundEvents.ENTITY_GHAST_SHOOT, 1.6f, 0.4f);
		cool(pw, MeraTechs.ENTEI, MeraTechs.ENTEI_CD);
	}

	// --- Kagerō: el chorro de fuego de la palma

	private static void kageroStart(ServerPlayerEntity p, PlayerPower pw, MeraState st) {
		if (st.kageroTicks > 0 || wetBlock(p, st)) return;
		if (!ready(p, pw, MeraTechs.KAGERO, "Kagerō")) return;
		st.kageroTicks = KAGERO_MAX;
		send(p.getServerWorld(), MeraFx.KAGERO, p.getId(), 0, hand(p, 1), p.getRotationVec(1f), 1f, 1f, KAGERO_MAX, 0);
		snd(p.getServerWorld(), p.getPos(), SoundEvents.ENTITY_BLAZE_SHOOT, 1.1f, 0.7f);
	}

	private static void kageroEnd(ServerPlayerEntity p, PlayerPower pw, MeraState st) {
		if (st.kageroTicks <= 0) return;
		// al menos un chorro corto
		if (KAGERO_MAX - st.kageroTicks < 8) {
			st.kageroTicks = Math.min(st.kageroTicks, 8 - (KAGERO_MAX - st.kageroTicks));
			return;
		}
		stopKagero(p, pw, st);
	}

	private static void stopKagero(ServerPlayerEntity p, PlayerPower pw, MeraState st) {
		st.kageroTicks = 0;
		send(p.getServerWorld(), MeraFx.KAGERO_END, p.getId(), 0, p.getPos(), Vec3d.ZERO, 0f, 0f, 0, 0);
		cool(pw, MeraTechs.KAGERO, MeraTechs.KAGERO_CD);
	}

	private static void kageroTick(ServerPlayerEntity p, PlayerPower pw, MeraState st) {
		if (st.kageroTicks <= 0) return;
		if (p.isTouchingWater()) {
			stopKagero(p, pw, st);
			return;
		}
		st.kageroTicks--;
		ServerWorld w = p.getServerWorld();
		Vec3d dir = p.getRotationVec(1f);
		Vec3d origin = hand(p, 1);
		double len = 11.0, cos = Math.cos(Math.toRadians(24));
		boolean hitTick = st.kageroTicks % 4 == 0;
		for (Entity e : w.getOtherEntities(p, new Box(origin, origin).expand(len + 1))) {
			Vec3d c = e.getPos().add(0, e.getHeight() * 0.5, 0);
			Vec3d d = c.subtract(origin);
			double dist = d.length();
			if (dist > len || dist < 1e-3 || d.multiply(1 / dist).dotProduct(dir) < cos) continue;
			if (e instanceof ProjectileEntity pr && pr.getOwner() != p) {
				// el chorro quema lo que vuela hacia ti
				send(w, MeraFx.EMBER_HIT, p.getId(), 0, pr.getPos(), Vec3d.ZERO, 0.6f, 0.6f, 8, 0);
				pr.discard();
				continue;
			}
			if (!(e instanceof LivingEntity le) || !le.isAlive() || e instanceof ArmorStandEntity) continue;
			if (hitTick) {
				le.setOnFireFor(5f);
				le.timeUntilRegen = 0;
				le.damage(burn(p), le.isFireImmune() ? 1.0f : 2.5f);
				le.addVelocity(dir.x * 0.28, 0.06, dir.z * 0.28);
				le.velocityModified = true;
				send(w, MeraFx.EMBER_HIT, p.getId(), 0, c, dir, 0.7f, 0.8f, 10, 0);
			}
		}
		// lo que toca al final del chorro se prende
		if (st.kageroTicks % 6 == 0) {
			BlockHitResult bh = rayBlocks(w, p.getEyePos(), p.getEyePos().add(dir.multiply(len)));
			if (bh.getType() == HitResult.Type.BLOCK) {
				igniteAround(w, Vec3d.ofCenter(bh.getBlockPos().offset(bh.getSide())), 1.2, 0.35f);
				meltAround(w, bh.getBlockPos(), 1);
			}
		}
		if (st.kageroTicks % 4 == 0) {
			snd(w, origin, SoundEvents.ENTITY_BLAZE_SHOOT, 0.45f, 0.8f + w.random.nextFloat() * 0.3f);
			snd(w, origin, SoundEvents.BLOCK_FIRE_AMBIENT, 1f, 0.8f);
		}
		if (st.kageroTicks == 0) stopKagero(p, pw, st);
	}

	// --- el cuerpo hecho chorro de llamas (Alt)

	private static void dash(ServerPlayerEntity p, PlayerPower pw, MeraState st) {
		if (st.dashTicks > 0 || wetBlock(p, st)) return;
		if (!ready(p, pw, MeraTechs.DASH, "Llamarada")) return;
		Vec3d dir = p.getRotationVec(1f);
		if (p.isOnGround() && dir.y < 0) dir = new Vec3d(dir.x, 0, dir.z);
		if (dir.lengthSquared() < 1e-4) dir = new Vec3d(0, 0, 1);
		st.dashDir = dir.normalize();
		st.dashTicks = DASH_TICKS;
		st.fallSafeTicks = Math.max(st.fallSafeTicks, 50);
		ServerWorld w = p.getServerWorld();
		Vec3d from = p.getPos();
		Vec3d to = from.add(st.dashDir.multiply(1.7 * DASH_TICKS));
		send(w, MeraFx.DASH, p.getId(), 0, from, to, 1f, 1f, DASH_TICKS + 4, 0);
		snd(w, from, SoundEvents.ENTITY_BLAZE_SHOOT, 1f, 1.2f);
		snd(w, from, SoundEvents.ITEM_FIRECHARGE_USE, 0.7f, 1.6f);
		cool(pw, MeraTechs.DASH, MeraTechs.DASH_CD);
	}

	private static void dashTick(ServerPlayerEntity p, MeraState st) {
		if (st.dashTicks <= 0) return;
		st.dashTicks--;
		Vec3d v = st.dashDir.multiply(st.dashTicks > 0 ? 1.7 : 0.45);
		p.setVelocity(v);
		p.velocityModified = true;
		p.fallDistance = 0f;
		ServerWorld w = p.getServerWorld();
		for (Entity e : w.getOtherEntities(p, p.getBoundingBox().expand(0.9), e -> e instanceof LivingEntity le && le.isAlive() && !(e instanceof ArmorStandEntity))) {
			LivingEntity le = (LivingEntity) e;
			if (le.getFireTicks() > 40) continue;
			le.setOnFireFor(4f);
			le.damage(burn(p), 3f);
			send(w, MeraFx.EMBER_HIT, p.getId(), 0, le.getPos().add(0, le.getHeight() * 0.5, 0), st.dashDir, 0.6f, 0.8f, 10, 0);
		}
	}

	// --- Hikyaku: fuego en los pies

	private static void hikyaku(ServerPlayerEntity p, PlayerPower pw, MeraState st) {
		if (wetBlock(p, st)) return;
		if (!p.isOnGround() && st.hikyakuCharges <= 0) {
			p.sendMessage(Text.literal("§7Hikyaku: toca el suelo para volver a impulsarte"), true);
			return;
		}
		if (!ready(p, pw, MeraTechs.HIKYAKU, "Hikyaku")) return;
		if (!p.isOnGround()) st.hikyakuCharges--;
		Vec3d look = p.getRotationVec(1f);
		Vec3d flat = new Vec3d(look.x, 0, look.z);
		if (flat.lengthSquared() > 1e-4) flat = flat.normalize();
		double up = 0.95 + Math.max(0, -look.y) * 0.4;
		p.setVelocity(flat.x * 1.05, up, flat.z * 1.05);
		p.velocityModified = true;
		p.fallDistance = 0f;
		st.fallSafeTicks = Math.max(st.fallSafeTicks, 70);
		ServerWorld w = p.getServerWorld();
		send(w, MeraFx.HIKYAKU, p.getId(), 0, p.getPos(), new Vec3d(flat.x, up, flat.z), 1f, 1f, 14, 0);
		snd(w, p.getPos(), SoundEvents.ENTITY_BLAZE_SHOOT, 0.9f, 1.35f);
		snd(w, p.getPos(), SoundEvents.ENTITY_GENERIC_EXPLODE.value(), 0.35f, 1.8f);
		// el estallido bajo los pies empuja y prende lo de alrededor
		for (Entity e : w.getOtherEntities(p, p.getBoundingBox().expand(2.2, 0.6, 2.2), e -> e instanceof LivingEntity le && le.isAlive())) {
			((LivingEntity) e).setOnFireFor(3f);
			Vec3d push = e.getPos().subtract(p.getPos()).multiply(1, 0, 1);
			if (push.lengthSquared() > 1e-4) push = push.normalize().multiply(0.6);
			e.addVelocity(push.x, 0.25, push.z);
			e.velocityModified = true;
		}
		cool(pw, MeraTechs.HIKYAKU, MeraTechs.HIKYAKU_CD);
	}

	// --- Higan: balas de fuego de los dedos

	private static void higan(ServerPlayerEntity p, PlayerPower pw, MeraState st) {
		if (wetBlock(p, st)) return;
		if (!ready(p, pw, MeraTechs.HIGAN, "Higan")) return;
		ServerWorld w = p.getServerWorld();
		send(w, MeraFx.HIGAN_POSE, p.getId(), 0, p.getPos(), Vec3d.ZERO, 0f, 0f, 18, 0);
		cool(pw, MeraTechs.HIGAN, MeraTechs.HIGAN_CD);
		for (int i = 0; i < 12; i++) {
			final int n = i;
			DELAYED.add(new Delayed(3 + i, () -> {
				if (!p.isAlive() || !isMera(p) || p.isTouchingWater()) return;
				int side = (n % 2 == 0) ? 1 : -1;
				Vec3d dir = p.getRotationVec(1f);
				// pequeña dispersión, como la ráfaga del manga
				dir = dir.add((w.random.nextDouble() - 0.5) * 0.035, (w.random.nextDouble() - 0.5) * 0.035, (w.random.nextDouble() - 0.5) * 0.035).normalize();
				Vec3d start = hand(p, side).add(dir.multiply(0.5));
				Shot s = new Shot(Shot.HIGAN, p, start, dir.multiply(3.4), 0.45, 3.5f, 24);
				SHOTS.add(s);
				send(w, MeraFx.HIGAN_BULLET, p.getId(), s.id, start, s.vel, 0.45f, 1f, 24, side > 0 ? 0 : 1);
				snd(w, start, SoundEvents.ITEM_FIRECHARGE_USE, 0.55f, 1.6f + w.random.nextFloat() * 0.3f);
			}));
		}
	}

	// --- Jūjika: la cruz de fuego

	private static void jujika(ServerPlayerEntity p, PlayerPower pw, MeraState st) {
		if (wetBlock(p, st)) return;
		if (!ready(p, pw, MeraTechs.JUJIKA, "Jūjika")) return;
		ServerWorld w = p.getServerWorld();
		Vec3d dir0 = p.getRotationVec(1f);
		Vec3d at = p.getEyePos().add(dir0.multiply(0.9)).add(0, -0.25, 0);
		send(w, MeraFx.JUJIKA_FLASH, p.getId(), 0, at, dir0, 0.9f, 1f, 10, 0);
		snd(w, at, SoundEvents.BLOCK_RESPAWN_ANCHOR_CHARGE, 0.9f, 1.6f);
		cool(pw, MeraTechs.JUJIKA, MeraTechs.JUJIKA_CD);
		DELAYED.add(new Delayed(9, () -> {
			if (!p.isAlive() || !isMera(p)) return;
			Vec3d dir = p.getRotationVec(1f);
			Vec3d start = p.getEyePos().add(dir.multiply(1.1)).add(0, -0.25, 0);
			Shot s = new Shot(Shot.JUJIKA, p, start, dir.multiply(2.0), 1.6, 14f, 40);
			s.pierce = 99;
			SHOTS.add(s);
			send(w, MeraFx.JUJIKA_SHOT, p.getId(), s.id, start, s.vel, 1.6f, 1f, 40, 0);
			snd(w, start, SoundEvents.ENTITY_BLAZE_SHOOT, 1.2f, 0.8f);
			snd(w, start, SoundEvents.ENTITY_GHAST_SHOOT, 0.8f, 0.9f);
		}));
	}

	// --- Hotarubi → Hidaruma

	private static void hotarubi(ServerPlayerEntity p, PlayerPower pw, MeraState st, boolean detonateOnly) {
		ServerWorld w = p.getServerWorld();
		if (st.hotarubiId >= 0 && st.hotarubiTicks > 0) {
			hidaruma(p, pw, st);
			return;
		}
		if (detonateOnly) {
			p.sendMessage(Text.literal("§7Hidaruma necesita luciérnagas: primero Hotarubi"), true);
			return;
		}
		if (wetBlock(p, st)) return;
		if (!ready(p, pw, MeraTechs.HOTARUBI, "Hotarubi")) return;
		Vec3d center;
		Entity target = aimEntity(p, 26);
		if (target != null) center = target.getPos().add(0, target.getHeight() * 0.5, 0);
		else {
			Vec3d aim = aimPoint(p, 18);
			center = aim.add(0, 1.2, 0);
		}
		st.hotarubiId = nextId++;
		st.hotarubiCenter = center;
		st.hotarubiCount = 22;
		st.hotarubiTicks = 300;
		send(w, MeraFx.HOTARUBI, p.getId(), st.hotarubiId, center, hand(p, 1), 5.5f, st.hotarubiCount, st.hotarubiTicks, 0);
		snd(w, center, SoundEvents.BLOCK_AMETHYST_BLOCK_CHIME, 1f, 1.6f);
		snd(w, p.getPos(), SoundEvents.ITEM_FIRECHARGE_USE, 0.5f, 1.9f);
		p.sendMessage(Text.literal("§e✦ Hotarubi §7— pulsa otra vez para §6Hidaruma"), true);
		cool(pw, MeraTechs.HOTARUBI, 20);
	}

	private static void hidaruma(ServerPlayerEntity p, PlayerPower pw, MeraState st) {
		ServerWorld w = p.getServerWorld();
		Vec3d c = st.hotarubiCenter;
		int group = st.hotarubiId;
		int count = st.hotarubiCount;
		List<LivingEntity> found = new ArrayList<>(w.getEntitiesByClass(LivingEntity.class, new Box(c, c).expand(9),
			e -> e != p && e.isAlive() && !(e instanceof ArmorStandEntity) && !(e instanceof PlayerEntity pl && (pl.isCreative() || pl.isSpectator()))));
		found.sort((a, b) -> Double.compare(a.squaredDistanceTo(c), b.squaredDistanceTo(c)));
		final List<LivingEntity> targets = found.size() > 8 ? new ArrayList<>(found.subList(0, 8)) : found;
		send(w, MeraFx.ZONE_END, p.getId(), group, c, Vec3d.ZERO, 0f, 1f, 0, 1);
		// una bola de fuego por víctima, más grande cuantas más luciérnagas le llegan (antes, una por luciérnaga: unas
		// once bolas iguales una encima de otra en cada víctima, que se quemaban a blanco y llenaban las luces)
		Map<LivingEntity, Integer> hits = new HashMap<>();
		if (!targets.isEmpty()) for (int i = 0; i < count; i++) hits.merge(targets.get(i % targets.size()), 1, Integer::sum);
		Set<LivingEntity> shown = new HashSet<>();
		for (int i = 0; i < count; i++) {
			LivingEntity t = targets.isEmpty() ? null : targets.get(i % targets.size());
			Vec3d to = t != null ? t.getPos().add(0, t.getHeight() * (0.3 + 0.5 * ((i * 37) % 10) / 10.0), 0)
				: c.add(((i * 53) % 11 - 5) * 0.5, -1.0, ((i * 29) % 11 - 5) * 0.5);
			int travel = 6 + (i % 5);
			send(w, MeraFx.HIDARUMA, p.getId(), group, c, to, 0.4f, 1f, travel, i);
			final LivingEntity target = t;
			final Vec3d impact = to;
			// (sin víctimas, al suelo: una de cada dos, que están repartidas)
			final boolean show = t != null ? shown.add(t) : i % 2 == 0;
			final float rad = t != null ? Math.min(1.0f + 0.07f * hits.getOrDefault(t, 1), 1.8f) : 1.0f;
			DELAYED.add(new Delayed(travel, () -> {
				if (target != null && target.isAlive()) {
					target.timeUntilRegen = 0;
					target.damage(burn(p), target.isFireImmune() ? 1.5f : 4.5f);
					target.setOnFireFor(7f);
				}
				if (!show) return;
				Vec3d at = target != null ? target.getPos().add(0, target.getHeight() * 0.5, 0) : impact;
				send(w, MeraFx.IMPACT, p.getId(), 0, at, Vec3d.ZERO, rad, 0.5f, 10, MeraFx.STYLE_ROUND);
			}));
		}
		DELAYED.add(new Delayed(8, () -> {
			snd(w, c, SoundEvents.ENTITY_GENERIC_EXPLODE.value(), 1.0f, 1.3f);
			snd(w, c, SoundEvents.ENTITY_BLAZE_SHOOT, 1.0f, 0.9f);
			if (targets.isEmpty()) igniteAround(w, c.add(0, -1.5, 0), 2.5, 0.3f);
		}));
		snd(w, c, SoundEvents.ENTITY_BLAZE_SHOOT, 1.1f, 1.5f);
		st.hotarubiId = -1;
		st.hotarubiTicks = 0;
		cool(pw, MeraTechs.HOTARUBI, MeraTechs.HOTARUBI_CD);
	}

	// --- Kyokaen: muro de fuego

	private static void kyokaen(ServerPlayerEntity p, PlayerPower pw, MeraState st) {
		if (wetBlock(p, st)) return;
		if (!ready(p, pw, MeraTechs.KYOKAEN, "Kyokaen")) return;
		Vec3d look = p.getRotationVec(1f);
		Vec3d flat = new Vec3d(look.x, 0, look.z);
		flat = flat.lengthSquared() < 1e-4 ? new Vec3d(0, 0, 1) : flat.normalize();
		Vec3d center = p.getPos().add(flat.multiply(3.2));
		Zone z = new Zone(Zone.WALL, p, center, 150);
		z.normal = flat;
		z.halfW = 4.5;
		z.height = 5.5;
		ZONES.add(z);
		ServerWorld w = p.getServerWorld();
		send(w, MeraFx.KYOKAEN, p.getId(), z.id, center, flat, (float) z.halfW, (float) z.height, z.ticks, 0);
		snd(w, center, SoundEvents.ENTITY_BLAZE_SHOOT, 1.3f, 0.5f);
		snd(w, center, SoundEvents.ITEM_FIRECHARGE_USE, 1.2f, 0.6f);
		cool(pw, MeraTechs.KYOKAEN, MeraTechs.KYOKAEN_CD);
	}

	// --- Enjōmō: el cerco de fuego

	private static void enjomo(ServerPlayerEntity p, PlayerPower pw, MeraState st) {
		if (wetBlock(p, st)) return;
		if (!ready(p, pw, MeraTechs.ENJOMO, "Enjōmō")) return;
		Entity target = aimEntity(p, 24);
		Vec3d center = target != null ? target.getPos() : p.getPos();
		double radius = target != null ? 5.5 : 8.0;
		Zone z = new Zone(Zone.RING, p, center, 220);
		z.radius = radius;
		z.height = 3.2;
		ServerWorld w = p.getServerWorld();
		for (LivingEntity le : w.getEntitiesByClass(LivingEntity.class, new Box(center, center).expand(radius + 2), e -> e.isAlive())) {
			Vec3d d = le.getPos().subtract(center);
			if (d.x * d.x + d.z * d.z < radius * radius) z.inside.add(le.getId());
		}
		ZONES.add(z);
		send(w, MeraFx.ENJOMO, p.getId(), z.id, center, Vec3d.ZERO, (float) radius, (float) z.height, z.ticks, 0);
		snd(w, center, SoundEvents.ENTITY_BLAZE_SHOOT, 1.4f, 0.45f);
		snd(w, center, SoundEvents.BLOCK_FIRE_AMBIENT, 2f, 0.6f);
		cool(pw, MeraTechs.ENJOMO, MeraTechs.ENJOMO_CD);
	}

	// --- Shinka: Shiranui: dos lanzas de fuego

	private static void shiranui(ServerPlayerEntity p, PlayerPower pw, MeraState st) {
		if (wetBlock(p, st)) return;
		if (!ready(p, pw, MeraTechs.SHIRANUI, "Shinka: Shiranui")) return;
		ServerWorld w = p.getServerWorld();
		Vec3d aim = aimPoint(p, 70);
		float yaw = p.getYaw() * MathHelper.RADIANS_PER_DEGREE;
		Vec3d right = new Vec3d(-MathHelper.cos(yaw), 0, -MathHelper.sin(yaw));
		for (int side = -1; side <= 1; side += 2) {
			Vec3d start = p.getEyePos().add(right.multiply(1.35 * side)).add(0, 0.35, 0).add(p.getRotationVec(1f).multiply(0.4));
			Vec3d dir = aim.subtract(start);
			dir = dir.lengthSquared() < 1e-4 ? p.getRotationVec(1f) : dir.normalize();
			Shot s = new Shot(Shot.SHIRANUI, p, start, dir.multiply(3.1), 0.75, 11f, 36);
			s.delay = 10;
			s.pierce = 6;
			SHOTS.add(s);
			send(w, MeraFx.SHIRANUI, p.getId(), s.id, start, s.vel, 0.75f, s.delay, 36, side > 0 ? 0 : 1);
		}
		snd(w, p.getPos(), SoundEvents.ITEM_FIRECHARGE_USE, 1f, 0.8f);
		DELAYED.add(new Delayed(10, () -> {
			snd(w, p.getPos(), SoundEvents.ITEM_TRIDENT_THROW.value(), 1.2f, 0.7f);
			snd(w, p.getPos(), SoundEvents.ENTITY_BLAZE_SHOOT, 1.1f, 1.1f);
		}));
		cool(pw, MeraTechs.SHIRANUI, MeraTechs.SHIRANUI_CD);
	}

	// --- Enkai: Hibashira: la columna de fuego

	private static void hibashira(ServerPlayerEntity p, PlayerPower pw, MeraState st) {
		if (wetBlock(p, st)) return;
		if (!ready(p, pw, MeraTechs.HIBASHIRA, "Enkai: Hibashira")) return;
		ServerWorld w = p.getServerWorld();
		send(w, MeraFx.ENKAI_GATHER, p.getId(), 0, p.getPos(), Vec3d.ZERO, 3f, 1f, 14, 0);
		snd(w, p.getPos(), SoundEvents.BLOCK_FIRE_AMBIENT, 2f, 0.5f);
		snd(w, p.getPos(), SoundEvents.ENTITY_BLAZE_AMBIENT, 1f, 0.6f);
		cool(pw, MeraTechs.HIBASHIRA, MeraTechs.HIBASHIRA_CD);
		DELAYED.add(new Delayed(14, () -> {
			if (!p.isAlive() || !isMera(p)) return;
			boolean down = !p.isOnGround() && p.getPitch() > 35;
			Vec3d base, top;
			if (down) {
				BlockHitResult bh = rayBlocks(w, p.getPos(), p.getPos().add(0, -40, 0));
				Vec3d ground = bh.getType() == HitResult.Type.BLOCK ? bh.getPos() : p.getPos().add(0, -40, 0);
				base = ground;
				top = p.getPos();
			} else {
				base = p.getPos();
				top = p.getPos().add(0, 17, 0);
			}
			Zone z = new Zone(Zone.PILLAR, p, base, 34);
			z.top = top;
			z.radius = 3.3;
			z.down = down;
			ZONES.add(z);
			send(w, MeraFx.HIBASHIRA, p.getId(), z.id, base, top, (float) z.radius, down ? 1f : 0f, z.ticks, down ? 1 : 0);
			snd(w, base, SoundEvents.ENTITY_GENERIC_EXPLODE.value(), 1.3f, 0.7f);
			snd(w, base, SoundEvents.ENTITY_BLAZE_SHOOT, 1.6f, 0.45f);
			if (down) igniteAround(w, base.add(0, 0.5, 0), 3.0, 0.35f);
		}));
	}

	// --- Cuerpo de llamas (M)

	private static void toggleBody(ServerPlayerEntity p, PlayerPower pw, MeraState st) {
		ServerWorld w = p.getServerWorld();
		if (!st.fireBody && wetBlock(p, st)) return;
		st.fireBody = !st.fireBody;
		pw.dirty = true;
		st.bodyBroadcastTicks = 0;
		if (st.fireBody) {
			snd(w, p.getPos(), SoundEvents.ENTITY_BLAZE_SHOOT, 1.2f, 0.5f);
			snd(w, p.getPos(), SoundEvents.ITEM_FIRECHARGE_USE, 1f, 0.6f);
			send(w, MeraFx.BODY, p.getId(), 0, p.getPos(), Vec3d.ZERO, 1f, 1f, 40, 1);
			p.sendMessage(Text.literal("§6🔥 Cuerpo de llamas §7— quemas lo que te toca y corres más"), true);
		} else {
			snd(w, p.getPos(), SoundEvents.BLOCK_FIRE_EXTINGUISH, 0.5f, 1.3f);
			send(w, MeraFx.BODY, p.getId(), 0, p.getPos(), Vec3d.ZERO, 0f, 0f, 0, 0);
			p.sendMessage(Text.literal("§7Llamas recogidas"), true);
		}
	}

	// ------------------------------------------------------------------ ticks

	/** Cada tick, para cada jugador con la Mera Mera. */
	public static void tickPlayer(ServerPlayerEntity p, PlayerPower pw) {
		MeraState st = state(p);
		ServerWorld w = p.getServerWorld();
		if (st.scatterCooldown > 0) st.scatterCooldown--;
		if (st.wetNoticeCooldown > 0) st.wetNoticeCooldown--;
		if (st.logiaNoticeCooldown > 0) st.logiaNoticeCooldown--;
		if (!p.isAlive()) return;
		if (st.fallSafeTicks > 0) {
			st.fallSafeTicks--;
			p.fallDistance = 0f;
		}
		if (p.isOnGround()) st.hikyakuCharges = 2;
		// es fuego: no arde
		if (p.isOnFire() && !p.isTouchingWater()) p.extinguish();

		boolean wet = p.isTouchingWater();
		blastProof(p, !wet);
		if (wet && !st.wasWet) {
			snd(w, p.getPos(), SoundEvents.BLOCK_FIRE_EXTINGUISH, 1f, 0.9f);
			send(w, MeraFx.STEAM, p.getId(), 0, p.getPos().add(0, 1, 0), Vec3d.ZERO, 1.4f, 1f, 30, 0);
			if (st.fireBody) {
				st.fireBody = false;
				pw.dirty = true;
				send(w, MeraFx.BODY, p.getId(), 0, p.getPos(), Vec3d.ZERO, 0f, 0f, 0, 0);
			}
			if (st.hikenCharging || st.enteiCharging) {
				st.hikenCharging = st.enteiCharging = false;
				pw.dirty = true;
				send(w, MeraFx.CHARGE_CANCEL, p.getId(), 0, p.getPos(), Vec3d.ZERO, 0f, 0f, 0, 0);
			}
			p.sendMessage(Text.literal("§b💧 El agua apaga tus llamas: sin Logia mientras estés mojado"), true);
		}
		st.wasWet = wet;

		if (st.hikenCharging) {
			st.hikenCharge++;
			if (st.hikenCharge % 6 == 0 && st.hikenCharge <= HIKEN_FULL) {
				snd(w, p.getPos(), SoundEvents.BLOCK_FIRE_AMBIENT, 1.2f, 0.7f + st.hikenCharge / (float) HIKEN_FULL * 0.6f);
			}
			if (st.hikenCharge == HIKEN_FULL) snd(w, p.getPos(), SoundEvents.ITEM_FIRECHARGE_USE, 1f, 1.3f);
			if (st.hikenCharge > 160) hikenRelease(p, pw, st);   // nadie aguanta tanto el puño cargado
		}
		if (st.enteiCharging) {
			st.enteiCharge++;
			float r = sunRadius(st.enteiCharge);
			Vec3d sun = sunPos(p, r);
			if (st.enteiCharge <= ENTEI_FULL && st.enteiCharge % 8 == 0) {
				snd(w, sun, SoundEvents.BLOCK_FIRE_AMBIENT, 2f, 0.4f + st.enteiCharge / (float) ENTEI_FULL * 0.4f);
				snd(w, sun, SoundEvents.BLOCK_RESPAWN_ANCHOR_CHARGE, 0.6f, 0.5f + st.enteiCharge / (float) ENTEI_FULL * 0.6f);
			}
			if (st.enteiCharge == ENTEI_FULL) {
				snd(w, sun, SoundEvents.ENTITY_BLAZE_AMBIENT, 1.5f, 0.4f);
				p.sendMessage(Text.literal("§6☀ ¡ENTEI! §7suelta para lanzarlo"), true);
			}
			// el sol quema lo que tiene cerca
			if (st.enteiCharge % 10 == 0) {
				for (Entity e : w.getOtherEntities(p, new Box(sun, sun).expand(r + 1.5), e -> e instanceof LivingEntity le && le.isAlive())) {
					if (e.getPos().add(0, e.getHeight() * 0.5, 0).distanceTo(sun) < r + 1.5) e.setOnFireFor(4f);
				}
			}
			p.addStatusEffect(new StatusEffectInstance(StatusEffects.SLOWNESS, 6, 1, false, false, false));
			if (st.enteiCharge > ENTEI_FULL + 200) enteiRelease(p, pw, st);
		}
		kageroTick(p, pw, st);
		dashTick(p, st);

		if (st.hotarubiTicks > 0 && --st.hotarubiTicks == 0) {
			send(w, MeraFx.ZONE_END, p.getId(), st.hotarubiId, st.hotarubiCenter, Vec3d.ZERO, 0f, 0f, 0, 0);
			st.hotarubiId = -1;
		}

		if (st.fireBody && !p.isSpectator()) bodyTick(p, st, w);
	}

	private static void bodyTick(ServerPlayerEntity p, MeraState st, ServerWorld w) {
		if (--st.bodyBroadcastTicks <= 0) {
			st.bodyBroadcastTicks = 20;
			send(w, MeraFx.BODY, p.getId(), 0, p.getPos(), Vec3d.ZERO, 1f, 1f, 40, 0);
		}
		p.addStatusEffect(new StatusEffectInstance(StatusEffects.SPEED, 30, 0, false, false, false));
		p.addStatusEffect(new StatusEffectInstance(StatusEffects.NIGHT_VISION, 260, 0, false, false, false));
		if (p.age % 10 == 0) {
			for (Entity e : w.getOtherEntities(p, p.getBoundingBox().expand(0.9), e -> e instanceof LivingEntity le && le.isAlive() && !(e instanceof ArmorStandEntity))) {
				if (e.getFireTicks() < 20) e.setOnFireFor(3f);
			}
			meltAround(w, p.getBlockPos(), 2);
		}
		if (p.age % 7 == 0 && w.random.nextFloat() < 0.6f) {
			w.spawnParticles(ParticleTypes.SMALL_FLAME, p.getX(), p.getY() + 0.1, p.getZ(), 2, 0.25, 0.05, 0.25, 0.01);
		}
		// la lluvia le saca vapor
		if (p.age % 12 == 0 && w.hasRain(p.getBlockPos().up())) {
			send(w, MeraFx.STEAM, p.getId(), 0, p.getPos().add(0, 1.4, 0), Vec3d.ZERO, 0.6f, 0.5f, 16, 0);
			snd(w, p.getPos(), SoundEvents.BLOCK_FIRE_EXTINGUISH, 0.15f, 1.6f);
		}
	}

	/** Cada tick del servidor: los disparos, los muros, los cercos y lo programado. */
	public static void tickServer(MinecraftServer server) {
		if (!DELAYED.isEmpty()) {
			List<Delayed> due = new ArrayList<>();
			Iterator<Delayed> it = DELAYED.iterator();
			while (it.hasNext()) {
				Delayed d = it.next();
				if (--d.ticks <= 0) {
					due.add(d);
					it.remove();
				}
			}
			for (Delayed d : due) d.run.run();
		}
		if (!SHOTS.isEmpty()) {
			List<Shot> snapshot = new ArrayList<>(SHOTS);
			for (Shot s : snapshot) {
				if (!tickShot(server, s)) SHOTS.remove(s);
			}
		}
		if (!ZONES.isEmpty()) {
			Iterator<Zone> it = ZONES.iterator();
			while (it.hasNext()) {
				Zone z = it.next();
				if (!tickZone(server, z)) it.remove();
			}
		}
		if (server.getTicks() % 100 == 0 && !PASSED.isEmpty()) PASSED.clear();
	}

	// ------------------------------------------------------------------ disparos

	static final class Shot {
		static final int HIKEN = 0, HIGAN = 1, JUJIKA = 2, SHIRANUI = 3, ENTEI = 4;
		final int id = nextId++;
		final int kind;
		final ServerPlayerEntity owner;
		final RegistryKey<World> dim;
		Vec3d pos, vel;
		final double radius;
		final float damage;
		final int life;
		int age, delay, pierce;
		float power = 1f;
		final Set<Integer> hit = new HashSet<>();

		Shot(int kind, ServerPlayerEntity owner, Vec3d pos, Vec3d vel, double radius, float damage, int life) {
			this.kind = kind;
			this.owner = owner;
			this.dim = owner.getServerWorld().getRegistryKey();
			this.pos = pos;
			this.vel = vel;
			this.radius = radius;
			this.damage = damage;
			this.life = life;
		}
	}

	/** Mueve un disparo un tick. Devuelve false cuando se acaba. */
	private static boolean tickShot(MinecraftServer server, Shot s) {
		ServerWorld w = server.getWorld(s.dim);
		if (w == null) return false;
		if (s.delay > 0) {
			s.delay--;
			// mientras se forma, sigue al dueño (las lanzas de Shiranui nacen a sus lados)
			return true;
		}
		Vec3d next = s.pos.add(s.vel);
		Vec3d dir = s.vel.normalize();

		// el agua apaga (salvo Entei, que la hace hervir y estalla)
		if (w.getFluidState(BlockPos.ofFloored(next)).isIn(FluidTags.WATER)) {
			if (s.kind == Shot.ENTEI) {
				boilWater(w, next, s.radius);
				enteiBlast(w, s, next);
			} else {
				send(w, MeraFx.IMPACT, s.owner.getId(), s.id, next, dir, (float) Math.max(1.0, s.radius), 0.4f, 20, MeraFx.STYLE_STEAM);
				snd(w, next, SoundEvents.BLOCK_FIRE_EXTINGUISH, 1f, 0.8f);
			}
			return false;
		}

		// bloques: las hojas arden y se apartan; lo sólido detiene el fuego
		BlockHitResult bh = rayBlocks(w, s.pos, next);
		Vec3d end = next;
		boolean blocked = false;
		if (bh.getType() == HitResult.Type.BLOCK) {
			BlockState bs = w.getBlockState(bh.getBlockPos());
			if ((s.kind == Shot.HIKEN || s.kind == Shot.ENTEI || s.kind == Shot.JUJIKA) && (bs.isIn(BlockTags.LEAVES) || bs.isIn(BlockTags.WOOL))
				&& griefing(w)) {
				w.breakBlock(bh.getBlockPos(), false);
				w.spawnParticles(ParticleTypes.FLAME, bh.getPos().x, bh.getPos().y, bh.getPos().z, 6, 0.3, 0.3, 0.3, 0.02);
			} else {
				end = bh.getPos();
				blocked = true;
			}
		}

		// seres en el camino
		Box sweep = new Box(s.pos, end).expand(s.radius + 1.0);
		for (Entity e : w.getOtherEntities(s.owner, sweep, e -> e instanceof LivingEntity le && le.isAlive() && !(e instanceof ArmorStandEntity))) {
			if (s.hit.contains(e.getId())) continue;
			if (e instanceof PlayerEntity pl && (pl.isCreative() || pl.isSpectator())) continue;
			Vec3d c = e.getPos().add(0, e.getHeight() * 0.5, 0);
			double reach = s.radius + Math.max(e.getWidth(), e.getHeight()) * 0.45;
			if (distToSegment(c, s.pos, end) > reach) continue;
			LivingEntity le = (LivingEntity) e;
			if (s.kind == Shot.ENTEI) {
				enteiBlast(w, s, c);
				return false;
			}
			s.hit.add(e.getId());
			le.timeUntilRegen = 0;
			float dmg = le.isFireImmune() ? s.damage * 0.4f : s.damage;
			le.damage(burn(s.owner), dmg);
			le.setOnFireFor(s.kind == Shot.HIGAN ? 3f : 8f);
			double kb = switch (s.kind) {
				case Shot.HIKEN -> 1.1 + 0.9 * s.power;
				case Shot.JUJIKA -> 1.0;
				case Shot.SHIRANUI -> 0.55;
				default -> 0.18;
			};
			le.addVelocity(dir.x * kb, 0.2 + kb * 0.25, dir.z * kb);
			le.velocityModified = true;
			send(w, MeraFx.EMBER_HIT, s.owner.getId(), s.id, c, dir, (float) Math.min(2.0, 0.6 + s.radius * 0.4), s.power, 12, 0);
			if (s.pierce-- <= 0) {
				impact(w, s, c, dir);
				return false;
			}
		}

		if (blocked) {
			impact(w, s, end, dir);
			return false;
		}
		s.pos = next;
		s.age++;
		if (s.kind == Shot.ENTEI) {
			// el sol pasa y lo de debajo se prende
			if (s.age % 4 == 0) {
				for (Entity e : w.getOtherEntities(s.owner, new Box(next, next).expand(s.radius + 3), e -> e instanceof LivingEntity le && le.isAlive())) {
					e.setOnFireFor(5f);
				}
			}
			if (s.age % 6 == 0) snd(w, next, SoundEvents.BLOCK_FIRE_AMBIENT, 2.5f, 0.4f);
		}
		if (s.age >= s.life) {
			if (s.kind == Shot.ENTEI) enteiBlast(w, s, next);
			else send(w, MeraFx.IMPACT, s.owner.getId(), s.id, next, dir, (float) s.radius, s.power * 0.5f, 14, MeraFx.STYLE_FIZZLE);
			return false;
		}
		return true;
	}

	private static void impact(ServerWorld w, Shot s, Vec3d at, Vec3d dir) {
		switch (s.kind) {
			case Shot.HIKEN -> {
				double r = 2.4 + 2.2 * s.power;
				areaBurn(w, s.owner, at, r, s.damage * 0.55f, 1.0 + s.power, s.hit);
				igniteAround(w, at, r * 0.7, 0.28f);
				send(w, MeraFx.IMPACT, s.owner.getId(), s.id, at, dir, (float) r, s.power, 26, MeraFx.STYLE_ROUND);
				snd(w, at, SoundEvents.ENTITY_GENERIC_EXPLODE.value(), 1.6f, 0.85f);
				snd(w, at, SoundEvents.ENTITY_BLAZE_SHOOT, 1.4f, 0.5f);
			}
			case Shot.JUJIKA -> {
				areaBurn(w, s.owner, at, 3.6, 9f, 1.0, s.hit);
				igniteAround(w, at, 2.2, 0.3f);
				send(w, MeraFx.IMPACT, s.owner.getId(), s.id, at, dir, 3.6f, 1f, 24, MeraFx.STYLE_CROSS);
				snd(w, at, SoundEvents.ENTITY_GENERIC_EXPLODE.value(), 1.3f, 1.05f);
			}
			case Shot.SHIRANUI -> {
				areaBurn(w, s.owner, at, 1.8, 4f, 0.4, s.hit);
				igniteAround(w, at, 1.2, 0.4f);
				send(w, MeraFx.IMPACT, s.owner.getId(), s.id, at, dir, 1.6f, 0.6f, 18, MeraFx.STYLE_LANCE);
				snd(w, at, SoundEvents.ITEM_FIRECHARGE_USE, 1f, 0.7f);
			}
			case Shot.ENTEI -> enteiBlast(w, s, at);
			default -> {
				send(w, MeraFx.IMPACT, s.owner.getId(), s.id, at, dir, 0.7f, 0.3f, 10, MeraFx.STYLE_ROUND);
				if (w.random.nextFloat() < 0.25f) igniteAround(w, at, 0.8, 0.5f);
				snd(w, at, SoundEvents.ITEM_FIRECHARGE_USE, 0.35f, 1.8f);
			}
		}
	}

	private static void enteiBlast(ServerWorld w, Shot s, Vec3d at) {
		double r = s.radius;
		double area = r * 2.3 + 2;
		areaBurn(w, s.owner, at, area, s.damage, 1.6 + r * 0.2, s.hit);
		send(w, MeraFx.ENTEI_BLAST, s.owner.getId(), s.id, at, Vec3d.ZERO, (float) area, (float) r, 50, 0);
		snd(w, at, SoundEvents.ENTITY_GENERIC_EXPLODE.value(), 3f, 0.55f);
		snd(w, at, SoundEvents.ENTITY_DRAGON_FIREBALL_EXPLODE, 2.5f, 0.6f);
		float power = (float) Math.min(7.0, 2.0 + r * 0.9);
		// la explosión de verdad: respeta mobGriefing (sin él no rompe bloques). El fuego lo pone igniteAround: el de Ace,
		// en 3D, en vez de las láminas pixeladas que deja una explosión de Minecraft
		w.createExplosion(s.owner, burn(s.owner), null, at.x, at.y, at.z, power, false, World.ExplosionSourceType.MOB);
		igniteAround(w, at, area * 0.6, 0.18f);
	}

	private static void areaBurn(ServerWorld w, ServerPlayerEntity owner, Vec3d at, double r, float dmg, double kb, Set<Integer> skip) {
		for (Entity e : w.getOtherEntities(owner, new Box(at, at).expand(r), e -> e instanceof LivingEntity le && le.isAlive() && !(e instanceof ArmorStandEntity))) {
			if (skip.contains(e.getId())) continue;
			if (e instanceof PlayerEntity pl && (pl.isCreative() || pl.isSpectator())) continue;
			Vec3d c = e.getPos().add(0, e.getHeight() * 0.5, 0);
			double d = c.distanceTo(at);
			if (d > r) continue;
			LivingEntity le = (LivingEntity) e;
			float f = (float) (1.0 - 0.6 * d / r);
			le.timeUntilRegen = 0;
			le.damage(burn(owner), (le.isFireImmune() ? 0.4f : 1f) * dmg * f);
			le.setOnFireFor(8f);
			Vec3d push = c.subtract(at);
			push = push.lengthSquared() < 1e-4 ? new Vec3d(0, 1, 0) : push.normalize();
			le.addVelocity(push.x * kb * f, 0.35 * kb * f + 0.15, push.z * kb * f);
			le.velocityModified = true;
		}
	}

	// ------------------------------------------------------------------ muros, cercos y columnas

	static final class Zone {
		static final int WALL = 0, RING = 1, PILLAR = 2;
		final int id = nextId++;
		final int kind;
		final ServerPlayerEntity owner;
		final RegistryKey<World> dim;
		final Vec3d center;
		Vec3d normal = Vec3d.ZERO, top = Vec3d.ZERO;
		double halfW, height, radius;
		boolean down;
		int ticks, age;
		final Set<Integer> inside = new HashSet<>();

		Zone(int kind, ServerPlayerEntity owner, Vec3d center, int ticks) {
			this.kind = kind;
			this.owner = owner;
			this.dim = owner.getServerWorld().getRegistryKey();
			this.center = center;
			this.ticks = ticks;
		}
	}

	private static boolean tickZone(MinecraftServer server, Zone z) {
		ServerWorld w = server.getWorld(z.dim);
		if (w == null) return false;
		if (!z.owner.isAlive()) {
			// el muro, el cerco y la columna se apagan con quien los encendió (y dejan de verse, no solo de quemar)
			send(w, MeraFx.ZONE_END, z.owner.getId(), z.id, z.center, Vec3d.ZERO, 0f, 0f, 0, 0);
			return false;
		}
		z.age++;
		boolean burnTick = z.age % 10 == 0;
		switch (z.kind) {
			case Zone.WALL -> {
				Vec3d side = new Vec3d(-z.normal.z, 0, z.normal.x);
				Box box = new Box(z.center, z.center).expand(z.halfW + 1, z.height + 1, z.halfW + 1).offset(0, z.height * 0.5, 0);
				for (Entity e : w.getOtherEntities(z.owner, box)) {
					Vec3d d = e.getPos().subtract(z.center);
					double n = d.dotProduct(z.normal), lat = d.dotProduct(side);
					if (Math.abs(lat) > z.halfW || e.getY() > z.center.y + z.height || e.getY() + e.getHeight() < z.center.y - 0.5) continue;
					if (e instanceof ProjectileEntity pr) {
						if (pr.getOwner() == z.owner || Math.abs(n) > 1.3) continue;
						// el muro devora lo que lo atraviesa
						send(w, MeraFx.EMBER_HIT, z.owner.getId(), z.id, pr.getPos(), z.normal, 0.8f, 0.6f, 10, 0);
						snd(w, pr.getPos(), SoundEvents.BLOCK_FIRE_EXTINGUISH, 0.5f, 1.5f);
						pr.discard();
						continue;
					}
					if (!(e instanceof LivingEntity le) || !le.isAlive() || Math.abs(n) > 1.2) continue;
					double sgn = n >= 0 ? 1 : -1;
					le.addVelocity(z.normal.x * 0.45 * sgn, 0.12, z.normal.z * 0.45 * sgn);
					le.velocityModified = true;
					if (burnTick) {
						le.setOnFireFor(5f);
						le.damage(burn(z.owner), le.isFireImmune() ? 0.5f : 2f);
					}
				}
				if (z.age % 8 == 0) snd(w, z.center, SoundEvents.BLOCK_FIRE_AMBIENT, 1.6f, 0.7f);
			}
			case Zone.RING -> {
				Box box = new Box(z.center, z.center).expand(z.radius + 3, z.height + 2, z.radius + 3);
				for (Entity e : w.getOtherEntities(z.owner, box)) {
					Vec3d d = e.getPos().subtract(z.center);
					double dist = Math.sqrt(d.x * d.x + d.z * d.z);
					if (e.getY() > z.center.y + z.height + 1) continue;
					if (e instanceof ProjectileEntity pr) {
						if (pr.getOwner() == z.owner || Math.abs(dist - z.radius) > 0.9) continue;
						send(w, MeraFx.EMBER_HIT, z.owner.getId(), z.id, pr.getPos(), Vec3d.ZERO, 0.7f, 0.6f, 10, 0);
						pr.discard();
						continue;
					}
					if (!(e instanceof LivingEntity le) || !le.isAlive() || dist < 1e-3) continue;
					boolean in = z.inside.contains(e.getId());
					double edge = in ? z.radius - 0.7 : z.radius + 0.7;
					boolean crossing = in ? dist > edge : dist < edge;
					if (!crossing) continue;
					// las llamas no dejan pasar ni entrar ni salir
					double sgn = in ? -1 : 1;
					le.addVelocity(d.x / dist * 0.55 * sgn, 0.15, d.z / dist * 0.55 * sgn);
					le.velocityModified = true;
					if (burnTick) {
						le.setOnFireFor(5f);
						le.damage(burn(z.owner), le.isFireImmune() ? 0.5f : 2f);
					}
				}
				if (z.age % 8 == 0) snd(w, z.center, SoundEvents.BLOCK_FIRE_AMBIENT, 2f, 0.6f);
			}
			case Zone.PILLAR -> {
				double y0 = Math.min(z.center.y, z.top.y) - 0.5, y1 = Math.max(z.center.y, z.top.y) + 1;
				Box box = new Box(z.center.x - z.radius, y0, z.center.z - z.radius, z.center.x + z.radius, y1, z.center.z + z.radius);
				boolean hitTick = z.age % 5 == 1;
				for (Entity e : w.getOtherEntities(z.owner, box, e -> e instanceof LivingEntity le && le.isAlive() && !(e instanceof ArmorStandEntity))) {
					Vec3d d = e.getPos().subtract(z.center);
					if (d.x * d.x + d.z * d.z > z.radius * z.radius) continue;
					LivingEntity le = (LivingEntity) e;
					if (z.age <= 3 && !z.inside.contains(e.getId())) {
						z.inside.add(e.getId());
						le.addVelocity(0, z.down ? -0.8 : 1.25, 0);
						le.velocityModified = true;
					}
					if (hitTick) {
						le.timeUntilRegen = 0;
						le.damage(burn(z.owner), le.isFireImmune() ? 1f : 4f);
						le.setOnFireFor(8f);
					}
				}
				if (z.age % 6 == 0) snd(w, z.center, SoundEvents.BLOCK_FIRE_AMBIENT, 2.5f, 0.5f);
			}
			default -> {}
		}
		if (z.age >= z.ticks) {
			send(w, MeraFx.ZONE_END, z.owner.getId(), z.id, z.center, Vec3d.ZERO, 0f, 0f, 0, 0);
			return false;
		}
		return true;
	}

	// ------------------------------------------------------------------ ayudas

	static final class Delayed {
		int ticks;
		final Runnable run;

		Delayed(int ticks, Runnable run) {
			this.ticks = ticks;
			this.run = run;
		}
	}

	public static DamageSource burn(Entity attacker) {
		return ModDamage.of(attacker.getWorld(), ModDamage.MERA, attacker);
	}

	/** La mano (1 = la derecha, −1 = la izquierda), algo delante del pecho. */
	public static Vec3d hand(PlayerEntity p, int side) {
		float yaw = p.getYaw() * MathHelper.RADIANS_PER_DEGREE;
		Vec3d right = new Vec3d(-MathHelper.cos(yaw), 0, -MathHelper.sin(yaw));
		Vec3d look = p.getRotationVec(1f);
		return p.getEyePos().add(look.multiply(0.55)).add(right.multiply(0.36 * side)).add(0, -0.32, 0);
	}

	private static Vec3d aimPoint(ServerPlayerEntity p, double range) {
		Vec3d eye = p.getEyePos();
		Vec3d end = eye.add(p.getRotationVec(1f).multiply(range));
		Entity e = aimEntity(p, range);
		if (e != null) return e.getPos().add(0, e.getHeight() * 0.5, 0);
		BlockHitResult bh = rayBlocks(p.getServerWorld(), eye, end);
		return bh.getType() == HitResult.Type.BLOCK ? bh.getPos() : end;
	}

	private static Entity aimEntity(ServerPlayerEntity p, double range) {
		Vec3d eye = p.getEyePos();
		Vec3d look = p.getRotationVec(1f);
		Vec3d end = eye.add(look.multiply(range));
		BlockHitResult bh = rayBlocks(p.getServerWorld(), eye, end);
		double max = bh.getType() == HitResult.Type.BLOCK ? bh.getPos().distanceTo(eye) : range;
		Entity best = null;
		double bestD = Double.MAX_VALUE;
		for (Entity e : p.getServerWorld().getOtherEntities(p, new Box(eye, end).expand(1.5),
			e -> e instanceof LivingEntity le && le.isAlive() && !(e instanceof ArmorStandEntity))) {
			Box b = e.getBoundingBox().expand(0.6);
			var hit = b.raycast(eye, end);
			if (hit.isEmpty()) continue;
			double d = hit.get().distanceTo(eye);
			if (d < bestD && d <= max) {
				bestD = d;
				best = e;
			}
		}
		return best;
	}

	private static BlockHitResult rayBlocks(World w, Vec3d from, Vec3d to) {
		return w.raycast(new RaycastContext(from, to, RaycastContext.ShapeType.COLLIDER, RaycastContext.FluidHandling.NONE, ShapeContext.absent()));
	}

	private static double distToSegment(Vec3d p, Vec3d a, Vec3d b) {
		Vec3d ab = b.subtract(a);
		double len2 = ab.lengthSquared();
		if (len2 < 1e-9) return p.distanceTo(a);
		double t = MathHelper.clamp(p.subtract(a).dotProduct(ab) / len2, 0.0, 1.0);
		return p.distanceTo(a.add(ab.multiply(t)));
	}

	private static boolean griefing(ServerWorld w) {
		return w.getGameRules().getBoolean(GameRules.DO_MOB_GRIEFING);
	}

	/**
	 * Prende fuego por encima del suelo alrededor de c (solo con mobGriefing): el fuego de Ace, que el cliente dibuja en
	 * 3D y se apaga solo en unos segundos (lo que arde al lado sigue con el fuego de Minecraft).
	 */
	private static void igniteAround(ServerWorld w, Vec3d c, double r, float chance) {
		if (!griefing(w)) return;
		int ir = (int) Math.ceil(r);
		BlockPos base = BlockPos.ofFloored(c);
		BlockState fire = ModBlocks.MERA_FIRE.getDefaultState();
		int placed = 0;
		for (BlockPos bp : BlockPos.iterate(base.add(-ir, -ir, -ir), base.add(ir, ir, ir))) {
			if (placed > 24) break;
			if (bp.getSquaredDistance(c) > r * r || w.random.nextFloat() > chance) continue;
			if (!w.getBlockState(bp).isAir() || !fire.canPlaceAt(w, bp)) continue;
			w.setBlockState(bp, fire, 11);
			placed++;
		}
	}

	/** Derrite la nieve y el hielo cercanos (el calor del cuerpo de Ace). */
	private static void meltAround(ServerWorld w, BlockPos c, int r) {
		for (BlockPos bp : BlockPos.iterate(c.add(-r, -1, -r), c.add(r, 1, r))) {
			BlockState bs = w.getBlockState(bp);
			if (bs.isOf(Blocks.SNOW) || bs.isOf(Blocks.POWDER_SNOW)) {
				w.setBlockState(bp, Blocks.AIR.getDefaultState(), 3);
			} else if (bs.isOf(Blocks.ICE) || bs.isOf(Blocks.FROSTED_ICE)) {
				w.setBlockState(bp, w.getDimension().ultrawarm() ? Blocks.AIR.getDefaultState() : Blocks.WATER.getDefaultState(), 3);
			}
		}
	}

	/** El sol de Entei hace hervir el agua que toca. */
	private static void boilWater(ServerWorld w, Vec3d c, double r) {
		send(w, MeraFx.STEAM, -1, 0, c, Vec3d.ZERO, (float) (r * 1.5), 1f, 60, 1);
		snd(w, c, SoundEvents.BLOCK_FIRE_EXTINGUISH, 3f, 0.5f);
		if (!griefing(w)) return;
		int ir = (int) Math.ceil(Math.min(r, 5));
		BlockPos base = BlockPos.ofFloored(c);
		for (BlockPos bp : BlockPos.iterate(base.add(-ir, -ir, -ir), base.add(ir, ir, ir))) {
			if (bp.getSquaredDistance(c) > r * r) continue;
			if (w.getBlockState(bp).isOf(Blocks.WATER)) w.setBlockState(bp, Blocks.AIR.getDefaultState(), 3);
		}
	}

	static void snd(ServerWorld w, Vec3d at, SoundEvent s, float vol, float pitch) {
		w.playSound(null, at.x, at.y, at.z, s, SoundCategory.PLAYERS, vol, pitch);
	}

	static void send(ServerWorld w, int kind, int userId, int id, Vec3d a, Vec3d b, float radius, float power, int ticks, int style) {
		MeraFx fx = new MeraFx(kind, userId, id, a.x, a.y, a.z, b.x, b.y, b.z, radius, power, ticks, style);
		for (ServerPlayerEntity sp : w.getPlayers()) {
			if (sp.squaredDistanceTo(a) <= 300 * 300 || sp.getId() == userId) ServerPlayNetworking.send(sp, fx);
		}
	}
}
