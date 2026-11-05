package dev.opeope.power;

import dev.opeope.OpeConfig;
import dev.opeope.entity.MarkerStoneEntity;
import dev.opeope.entity.PieceEntity;
import dev.opeope.entity.SterbenEntity;
import dev.opeope.item.HeartItem;
import dev.opeope.registry.ModAttachments;
import dev.opeope.registry.ModDamage;
import dev.opeope.registry.ModEffects;
import dev.opeope.registry.ModItems;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.entity.Entity;
import net.minecraft.entity.FallingBlockEntity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.boss.WitherEntity;
import net.minecraft.entity.boss.dragon.EnderDragonEntity;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.particle.DustParticleEffect;
import net.minecraft.particle.ParticleEffect;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.Hand;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Las técnicas de cirujano y las del Despertar. */
public final class Techniques {
	private Techniques() {}

	private static final DustParticleEffect GAMMA_DUST = new DustParticleEffect(new Vector3f(0.55f, 1f, 0.6f), 1.0f);
	private static final DustParticleEffect CURTAIN_DUST = new DustParticleEffect(new Vector3f(0.7f, 0.9f, 1f), 1.6f);

	private static float dmg(float gameDamage) {
		return gameDamage * OpeConfig.DMG;
	}

	public static void beam(ServerPlayerEntity p, Vec3d from, Vec3d to, ParticleEffect particle) {
		ServerWorld w = p.getServerWorld();
		Vec3d d = to.subtract(from);
		double len = d.length();
		int steps = (int) Math.min(120, len * 2);
		for (int i = 0; i <= steps; i++) {
			Vec3d at = from.add(d.multiply(i / (double) Math.max(1, steps)));
			w.spawnParticles(particle, at.x, at.y, at.z, 1, 0, 0, 0, 0);
		}
	}

	private static LivingEntity livingInSight(ServerPlayerEntity p, double range) {
		Entity e = PowerManager.raycastEntity(p, range, 0.4, ent -> ent instanceof LivingEntity && ent.isAlive());
		return e instanceof LivingEntity le ? le : null;
	}

	private static void sound(ServerPlayerEntity p, Vec3d at, net.minecraft.sound.SoundEvent s, float vol, float pitch) {
		p.getWorld().playSound(null, at.x, at.y, at.z, s, SoundCategory.PLAYERS, vol, pitch);
	}

	// ------------------------------------------------------------------ teclas sueltas

	public static void marker(ServerPlayerEntity p, PlayerPower pw) {
		if (pw.onCooldown(Tech.MARKER)) return;
		MarkerStoneEntity stone = new MarkerStoneEntity(p.getWorld(), p);
		stone.setItem(new ItemStack(ModItems.MARKER_STONE));
		stone.setVelocity(p, p.getPitch(), p.getYaw(), 0f, 2.4f, 0.3f);
		p.getWorld().spawnEntity(stone);
		sound(p, p.getPos(), SoundEvents.ENTITY_SNOWBALL_THROW, 1f, 0.6f);
		pw.cooldown(Tech.MARKER);
	}

	public static void scan(ServerPlayerEntity p, PlayerPower pw) {
		if (pw.onCooldown(Tech.SCAN)) return;
		pw.scanTicks = 160;
		pw.cooldown(Tech.SCAN);
		sound(p, p.getPos(), SoundEvents.BLOCK_BEACON_POWER_SELECT, 1f, 2f);
		p.sendMessage(Text.translatable("opeope.msg.scan"), true);
	}

	/** Radio Knife: corte eléctrico. Hiere, corta en tres y lo cortado no se puede rearmar en 5 s. */
	public static void radioKnife(ServerPlayerEntity p, PlayerPower pw) {
		if (pw.onCooldown(Tech.RADIO_KNIFE)) return;
		LivingEntity t = livingInSight(p, 60);
		Vec3d point = t != null ? t.getBoundingBox().getCenter() : PowerManager.aimPoint(p, 60);
		Room room = PowerManager.roomAt(p, point);
		List<CutEngine.Plane> planes = new ArrayList<>();
		Vec3d look = p.getRotationVector();
		Vec3d right = look.crossProduct(new Vec3d(0, 1, 0));
		if (right.lengthSquared() < 1e-4) right = new Vec3d(1, 0, 0);
		right = right.normalize();
		Vec3d up = right.crossProduct(look).normalize();
		planes.add(new CutEngine.Plane(up, point));
		planes.add(new CutEngine.Plane(up.add(right).normalize(), point));
		planes.add(new CutEngine.Plane(up.subtract(right).normalize(), point));
		CutEngine.Region region = room != null
			? CutEngine.Region.of(room).withLimit(point, 3.4)
			: CutEngine.Region.sphere(point, 3.4);
		CutEngine.cut(p, planes, region, pw.suspend, OpeConfig.RADIO_KNIFE_NO_REATTACH, dmg(22), room != null);
		ServerWorld w = p.getServerWorld();
		w.spawnParticles(ParticleTypes.ELECTRIC_SPARK, point.x, point.y, point.z, 80, 1.5, 1.5, 1.5, 0.4);
		w.spawnParticles(ParticleTypes.SWEEP_ATTACK, point.x, point.y, point.z, 3, 0.8, 0.8, 0.8, 0);
		beam(p, p.getEyePos().add(look), point, ParticleTypes.ELECTRIC_SPARK);
		sound(p, point, SoundEvents.ENTITY_LIGHTNING_BOLT_IMPACT, 1f, 1.6f);
		sound(p, p.getPos(), SoundEvents.ENTITY_PLAYER_ATTACK_SWEEP, 1f, 0.8f);
		pw.cooldown(Tech.RADIO_KNIFE);
	}

	/** Injection Shot: embestida que dispara al clavarse. */
	public static void injection(ServerPlayerEntity p, PlayerPower pw) {
		if (pw.onCooldown(Tech.INJECTION)) return;
		pw.dashTicks = 7;
		pw.dashDir = p.getRotationVector();
		p.setVelocity(pw.dashDir.multiply(1.6));
		p.velocityModified = true;
		sound(p, p.getPos(), SoundEvents.ENTITY_PLAYER_ATTACK_KNOCKBACK, 1f, 0.7f);
		pw.cooldown(Tech.INJECTION);
	}

	static void dashTick(ServerPlayerEntity p, PlayerPower pw) {
		if (pw.dashTicks <= 0) return;
		pw.dashTicks--;
		p.setVelocity(pw.dashDir.multiply(1.6));
		p.velocityModified = true;
		p.fallDistance = 0;
		ServerWorld w = p.getServerWorld();
		w.spawnParticles(CutEngine.ROOM_DUST, p.getX(), p.getY() + 1, p.getZ(), 3, 0.2, 0.4, 0.2, 0);
		for (LivingEntity le : w.getEntitiesByClass(LivingEntity.class, p.getBoundingBox().expand(0.9), e -> e != p && e.isAlive())) {
			le.damage(w.getDamageSources().playerAttack(p), dmg(45));
			Vec3d push = pw.dashDir.multiply(1.8).add(0, 0.45, 0);
			le.addVelocity(push.x, push.y, push.z);
			le.velocityModified = true;
			Vec3d c = le.getBoundingBox().getCenter();
			w.spawnParticles(ParticleTypes.EXPLOSION, c.x, c.y, c.z, 2, 0.3, 0.3, 0.3, 0);
			w.spawnParticles(ParticleTypes.ELECTRIC_SPARK, c.x, c.y, c.z, 40, 0.5, 0.5, 0.5, 0.3);
			for (LivingEntity near : w.getEntitiesByClass(LivingEntity.class, le.getBoundingBox().expand(3), e -> e != p && e != le && e.isAlive())) {
				near.damage(w.getDamageSources().playerAttack(p), 3f);
				Vec3d away = near.getPos().subtract(c).normalize().multiply(0.8).add(0, 0.3, 0);
				near.addVelocity(away.x, away.y, away.z);
				near.velocityModified = true;
			}
			sound(p, c, SoundEvents.ENTITY_LIGHTNING_BOLT_IMPACT, 1f, 1.2f);
			pw.dashTicks = 0;
			p.setVelocity(pw.dashDir.multiply(-0.3));
			p.velocityModified = true;
			break;
		}
	}

	/** Gamma Knife: destruye por dentro y no mira la armadura. */
	public static void gamma(ServerPlayerEntity p, PlayerPower pw) {
		if (pw.onCooldown(Tech.GAMMA)) return;
		LivingEntity t = livingInSight(p, 4.5);
		if (t == null) {
			p.sendMessage(Text.translatable("opeope.msg.too_far"), true);
			return;
		}
		t.damage(ModDamage.of(p.getWorld(), ModDamage.GAMMA, p), dmg(70));
		t.addStatusEffect(new StatusEffectInstance(StatusEffects.WITHER, 60, 0));
		ServerWorld w = p.getServerWorld();
		Box b = t.getBoundingBox();
		w.spawnParticles(GAMMA_DUST, b.getCenter().x, b.getCenter().y, b.getCenter().z, 60, b.getLengthX() / 3, b.getLengthY() / 3, b.getLengthZ() / 3, 0);
		w.spawnParticles(ParticleTypes.END_ROD, b.getCenter().x, b.getCenter().y, b.getCenter().z, 20, b.getLengthX() / 3, b.getLengthY() / 3, b.getLengthZ() / 3, 0.02);
		beam(p, p.getEyePos().add(p.getRotationVector()), b.getCenter(), GAMMA_DUST);
		sound(p, b.getCenter(), SoundEvents.BLOCK_BEACON_DEACTIVATE, 1.2f, 1.8f);
		sound(p, b.getCenter(), SoundEvents.ENTITY_WARDEN_SONIC_BOOM, 0.4f, 2f);
		pw.cooldown(Tech.GAMMA);
	}

	public static void toggleAwaken(ServerPlayerEntity p, PlayerPower pw) {
		if (!ModAttachments.hasAwakening(p)) {
			p.sendMessage(Text.translatable("opeope.msg.no_awakening"), true);
			return;
		}
		pw.awakened = !pw.awakened;
		pw.dirty = true;
		ServerWorld w = p.getServerWorld();
		w.spawnParticles(CutEngine.ROOM_DUST, p.getX(), p.getY() + 1, p.getZ(), 80, 1.2, 1.2, 1.2, 0);
		sound(p, p.getPos(), pw.awakened ? SoundEvents.BLOCK_CONDUIT_ACTIVATE : SoundEvents.BLOCK_CONDUIT_DEACTIVATE, 1.2f, 1.2f);
		p.sendMessage(Text.translatable(pw.awakened ? "opeope.msg.awake_on" : "opeope.msg.awake_off"), true);
	}

	/** Curtain: un velo delante que reduce el daño (60 %, como Resistencia III). */
	public static void curtain(ServerPlayerEntity p, PlayerPower pw) {
		if (pw.onCooldown(Tech.CURTAIN)) return;
		pw.curtainTicks = 120;
		p.addStatusEffect(new StatusEffectInstance(StatusEffects.RESISTANCE, 120, 2, false, false, true));
		sound(p, p.getPos(), SoundEvents.BLOCK_AMETHYST_BLOCK_RESONATE, 1.2f, 0.8f);
		pw.cooldown(Tech.CURTAIN);
	}

	static void curtainTick(ServerPlayerEntity p, PlayerPower pw) {
		if (pw.curtainTicks <= 0) return;
		if (--pw.curtainTicks == 0) pw.dirty = true;
		if (pw.curtainTicks % 2 != 0) return;
		Vec3d look = p.getRotationVector();
		Vec3d flat = new Vec3d(look.x, 0, look.z);
		if (flat.lengthSquared() < 1e-4) flat = new Vec3d(0, 0, 1);
		flat = flat.normalize();
		Vec3d side = new Vec3d(-flat.z, 0, flat.x);
		Vec3d c = p.getPos().add(flat.multiply(1.4));
		ServerWorld w = p.getServerWorld();
		for (int i = 0; i < 6; i++) {
			double a = (p.getRandom().nextDouble() - 0.5) * 2.4, h = p.getRandom().nextDouble() * 2.2;
			Vec3d at = c.add(side.multiply(a)).add(0, h, 0);
			w.spawnParticles(CURTAIN_DUST, at.x, at.y, at.z, 1, 0, 0, 0, 0);
		}
	}

	public static void sterben(ServerPlayerEntity p, PlayerPower pw) {
		if (pw.onCooldown(Tech.STERBEN)) return;
		SterbenEntity.launch(p);
		sound(p, p.getPos(), SoundEvents.ENTITY_PLAYER_ATTACK_SWEEP, 1.5f, 0.5f);
		pw.cooldown(Tech.STERBEN);
	}

	/** Cambio de almas: marca a uno y luego a otro; se cambian el alma (nombres, vida y torpeza). */
	public static void souls(ServerPlayerEntity p, PlayerPower pw) {
		if (pw.onCooldown(Tech.SOULS)) return;
		LivingEntity t = livingInSight(p, 60);
		if (t == null) return;
		if (!PowerManager.inRoom(p, t.getBoundingBox().getCenter())) {
			PowerManager.noRoom(p);
			return;
		}
		Entity marked = pw.soulMark >= 0 ? p.getWorld().getEntityById(pw.soulMark) : null;
		if (!(marked instanceof LivingEntity a) || !a.isAlive() || pw.soulMarkTicks <= 0) {
			pw.soulMark = t.getId();
			pw.soulMarkTicks = OpeConfig.SOUL_MARK_TICKS;
			p.getServerWorld().spawnParticles(ParticleTypes.SOUL, t.getX(), t.getY() + t.getHeight(), t.getZ(), 10, 0.3, 0.3, 0.3, 0.02);
			p.sendMessage(Text.translatable("opeope.msg.marked", t.getDisplayName()), true);
			return;
		}
		pw.soulMark = -1;
		if (a == t) return;
		LivingEntity b = t;
		Text na = a.getDisplayName(), nb = b.getDisplayName();
		float fa = a.getHealth() / a.getMaxHealth(), fb = b.getHealth() / b.getMaxHealth();
		if (!(a instanceof ServerPlayerEntity)) {
			a.setCustomName(Text.translatable("opeope.soul_of", nb));
			a.setCustomNameVisible(true);
		}
		if (!(b instanceof ServerPlayerEntity)) {
			b.setCustomName(Text.translatable("opeope.soul_of", na));
			b.setCustomNameVisible(true);
		}
		a.setHealth(Math.max(1, fb * a.getMaxHealth()));
		b.setHealth(Math.max(1, fa * b.getMaxHealth()));
		for (LivingEntity le : new LivingEntity[]{a, b}) {
			le.addStatusEffect(new StatusEffectInstance(StatusEffects.NAUSEA, 500, 0));
			le.addStatusEffect(new StatusEffectInstance(StatusEffects.SLOWNESS, 500, 1));
			le.addStatusEffect(new StatusEffectInstance(StatusEffects.MINING_FATIGUE, 500, 1));
		}
		if (a instanceof MobEntity ma) ma.setTarget(b);
		if (b instanceof MobEntity mb) mb.setTarget(a);
		beam(p, a.getBoundingBox().getCenter(), b.getBoundingBox().getCenter(), ParticleTypes.SOUL);
		sound(p, a.getPos(), SoundEvents.ENTITY_EVOKER_CAST_SPELL, 1f, 1.4f);
		sound(p, b.getPos(), SoundEvents.ENTITY_EVOKER_CAST_SPELL, 1f, 0.8f);
		pw.cooldown(Tech.SOULS);
	}

	/** Lluvia: llueven cortes sobre todo lo que vive dentro del ROOM. */
	public static void rain(ServerPlayerEntity p, PlayerPower pw) {
		if (pw.onCooldown(Tech.RAIN)) return;
		if (pw.room == null) {
			PowerManager.noRoom(p);
			return;
		}
		pw.rainTicks = 60;
		sound(p, p.getPos(), SoundEvents.WEATHER_RAIN_ABOVE, 1.5f, 1.5f);
		pw.cooldown(Tech.RAIN);
	}

	static void rainTick(ServerPlayerEntity p, PlayerPower pw) {
		if (pw.rainTicks <= 0) return;
		pw.rainTicks--;
		Room room = pw.room;
		if (room == null || pw.rainTicks % 3 != 0) return;
		ServerWorld w = p.getServerWorld();
		Box box = new Box(room.center, room.center).expand(Math.min(room.radius, 64));
		List<LivingEntity> list = w.getEntitiesByClass(LivingEntity.class, box, e -> e != p && e.isAlive() && room.contains(e.getPos()));
		if (list.isEmpty()) return;
		LivingEntity t = list.get(p.getRandom().nextInt(list.size()));
		Vec3d c = t.getBoundingBox().getCenter();
		Vec3d n = new Vec3d(p.getRandom().nextDouble() - 0.5, 0.6, p.getRandom().nextDouble() - 0.5).normalize();
		CutStates.cutLiving(t, List.of(new CutEngine.Plane(n, c)), OpeConfig.CUT_LIVING_TICKS / 2, 0);
		t.damage(w.getDamageSources().playerAttack(p), 2f);
		for (int i = 0; i < 8; i++) w.spawnParticles(ParticleTypes.END_ROD, c.x, c.y + 6 - i * 0.7, c.z, 1, 0.05, 0.05, 0.05, 0);
		sound(p, c, SoundEvents.ENTITY_PLAYER_ATTACK_SWEEP, 0.6f, 1.8f);
	}

	public static void toggleSuspend(ServerPlayerEntity p, PlayerPower pw) {
		if (pw.suspend) releaseSuspended(p, pw);
		pw.suspend = !pw.suspend;
		pw.dirty = true;
		p.sendMessage(Text.translatable(pw.suspend ? "opeope.msg.suspend_on" : "opeope.msg.suspend_off"), true);
	}

	public static void releaseSuspended(ServerPlayerEntity p, PlayerPower pw) {
		for (int id : pw.suspended) {
			Entity e = p.getWorld().getEntityById(id);
			if (e instanceof PieceEntity pe && !pe.isRemoved() && !pw.takt.contains(id) && pe.mode == PieceEntity.Mode.CONTROLLED) {
				pe.release(Vec3d.ZERO, false);
			}
		}
		pw.suspended.clear();
	}

	// ------------------------------------------------------------------ Mes

	/** Mes: le sacas el corazón en un cubo. Con un corazón en la mano: devolverlo (mirando al dueño) o soltarlo. */
	public static void mes(ServerPlayerEntity p, PlayerPower pw) {
		ItemStack hand = p.getMainHandStack();
		if (hand.isOf(ModItems.HEART)) {
			UUID owner = HeartItem.owner(hand);
			LivingEntity t = livingInSight(p, 8);
			if (t != null && t.getUuid().equals(owner)) {
				t.removeStatusEffect(StatusEffects.WEAKNESS);
				t.removeStatusEffect(StatusEffects.SLOWNESS);
				p.setStackInHand(Hand.MAIN_HAND, ItemStack.EMPTY);
				p.getServerWorld().spawnParticles(ParticleTypes.HEART, t.getX(), t.getY() + t.getHeight() / 2, t.getZ(), 6, 0.3, 0.3, 0.3, 0);
				sound(p, t.getPos(), SoundEvents.BLOCK_AMETHYST_BLOCK_CHIME, 1f, 1f);
				p.sendMessage(Text.translatable("opeope.msg.heart_back"), true);
			} else {
				p.dropItem(hand.copy(), true, false);
				p.setStackInHand(Hand.MAIN_HAND, ItemStack.EMPTY);
			}
			return;
		}
		if (pw.onCooldown(Tech.MES)) return;
		LivingEntity t = livingInSight(p, 6);
		if (t == null || t instanceof EnderDragonEntity || t instanceof WitherEntity) {
			p.sendMessage(Text.translatable("opeope.msg.too_far"), true);
			return;
		}
		ItemStack heart = new ItemStack(ModItems.HEART);
		HeartItem.setOwner(heart, t.getUuid());
		heart.set(DataComponentTypes.CUSTOM_NAME, Text.translatable("opeope.heart_of", t.getDisplayName()));
		p.getInventory().offerOrDrop(heart);
		t.addStatusEffect(new StatusEffectInstance(StatusEffects.WEAKNESS, 20 * 300, 9, false, true));
		t.addStatusEffect(new StatusEffectInstance(StatusEffects.SLOWNESS, 20 * 300, 0, false, true));
		ServerWorld w = p.getServerWorld();
		Vec3d c = t.getBoundingBox().getCenter();
		w.spawnParticles(CutEngine.ROOM_DUST, c.x, c.y, c.z, 30, 0.3, 0.3, 0.3, 0);
		w.spawnParticles(ParticleTypes.HEART, c.x, c.y + 0.3, c.z, 3, 0.2, 0.2, 0.2, 0);
		sound(p, c, SoundEvents.ENTITY_WARDEN_HEARTBEAT, 2f, 1.2f);
		sound(p, c, SoundEvents.BLOCK_AMETHYST_BLOCK_BREAK, 1f, 1.5f);
		pw.cooldown(Tech.MES);
	}

	/** Apretar un corazón: le duele a su dueño, esté donde esté (en este mundo). */
	public static void squeezeHeart(ServerPlayerEntity p, ItemStack stack) {
		UUID owner = HeartItem.owner(stack);
		if (owner == null) return;
		Entity e = p.getServerWorld().getEntity(owner);
		if (!(e instanceof LivingEntity le) || !le.isAlive()) {
			p.sendMessage(Text.translatable("opeope.msg.heart_far"), true);
			return;
		}
		le.damage(p.getWorld().getDamageSources().indirectMagic(p, p), 2f);
		le.addStatusEffect(new StatusEffectInstance(ModEffects.STUN, 40, 0, false, true));
		p.getServerWorld().spawnParticles(ParticleTypes.DAMAGE_INDICATOR, le.getX(), le.getY() + le.getHeight() / 2, le.getZ(), 4, 0.3, 0.3, 0.3, 0.1);
		sound(p, p.getPos(), SoundEvents.ENTITY_WARDEN_HEARTBEAT, 1.5f, 1.6f);
		sound(p, le.getPos(), SoundEvents.ENTITY_WARDEN_HEARTBEAT, 1.5f, 1.6f);
	}

	// ------------------------------------------------------------------ Counter Shock y Bisturí

	public static void counterShock(ServerPlayerEntity p, PlayerPower pw) {
		if (pw.onCooldown(Tech.COUNTER)) return;
		ServerWorld w = p.getServerWorld();
		LivingEntity t = livingInSight(p, 3.5);
		List<LivingEntity> hit = new ArrayList<>();
		if (t != null) hit.add(t);
		else hit.addAll(w.getEntitiesByClass(LivingEntity.class, p.getBoundingBox().expand(2.5), e -> e != p && e.isAlive()));
		for (LivingEntity le : hit) {
			le.damage(w.getDamageSources().playerAttack(p), t != null ? dmg(30) : 3f);
			le.addStatusEffect(new StatusEffectInstance(ModEffects.STUN, t != null ? 60 : 30, 0, false, true));
			Vec3d push = le.getPos().subtract(p.getPos()).normalize().multiply(t != null ? 2.1 : 1.0).add(0, 0.4, 0);
			le.addVelocity(push.x, push.y, push.z);
			le.velocityModified = true;
			Vec3d c = le.getBoundingBox().getCenter();
			w.spawnParticles(ParticleTypes.ELECTRIC_SPARK, c.x, c.y, c.z, 50, 0.4, 0.6, 0.4, 0.5);
		}
		Vec3d hands = p.getEyePos().add(p.getRotationVector().multiply(0.8));
		w.spawnParticles(ParticleTypes.ELECTRIC_SPARK, hands.x, hands.y, hands.z, 30, 0.2, 0.2, 0.2, 0.4);
		sound(p, p.getPos(), SoundEvents.ENTITY_LIGHTNING_BOLT_THUNDER, 0.4f, 2f);
		sound(p, p.getPos(), SoundEvents.ENTITY_LIGHTNING_BOLT_IMPACT, 1f, 1.6f);
		pw.cooldown(Tech.COUNTER);
	}

	/** Bisturí: casi para el tiempo (solo en una partida de un jugador) mientras eliges el plano exacto. */
	public static void bisturi(ServerPlayerEntity p, PlayerPower pw, boolean start) {
		var server = p.getServer();
		if (server == null) return;
		if (start) {
			if (pw.room == null) {
				PowerManager.noRoom(p);
				return;
			}
			pw.bisturi = true;
			if (server.isSingleplayer()) server.getTickManager().setTickRate(20f * 0.12f);
			sound(p, p.getPos(), SoundEvents.BLOCK_BEACON_AMBIENT, 1f, 2f);
		} else if (pw.bisturi) {
			pw.bisturi = false;
			if (server.isSingleplayer()) server.getTickManager().setTickRate(20f);
		}
		pw.dirty = true;
	}

	// ------------------------------------------------------------------ Despertar

	/** K-ROOM: un ROOM ceñido al puño; lo que golpeas queda cortado en piezas. */
	public static void kroom(ServerPlayerEntity p, PlayerPower pw) {
		if (pw.onCooldown(Tech.KROOM)) return;
		LivingEntity t = livingInSight(p, 5);
		if (t == null) return;
		Vec3d c = t.getBoundingBox().getCenter();
		List<CutEngine.Plane> planes = List.of(
			new CutEngine.Plane(new Vec3d(0, 1, 0), c),
			new CutEngine.Plane(new Vec3d(1, 0.4, 0).normalize(), c),
			new CutEngine.Plane(new Vec3d(0, 0.4, 1).normalize(), c));
		CutStates.cutLiving(t, planes, OpeConfig.CUT_LIVING_TICKS, 0);
		ServerWorld w = p.getServerWorld();
		t.damage(w.getDamageSources().playerAttack(p), 8f);
		Vec3d push = p.getRotationVector().multiply(1.2).add(0, 0.3, 0);
		t.addVelocity(push.x, push.y, push.z);
		t.velocityModified = true;
		w.spawnParticles(CutEngine.ROOM_DUST, c.x, c.y, c.z, 80, 1.2, 1.2, 1.2, 0);
		w.spawnParticles(ParticleTypes.SWEEP_ATTACK, c.x, c.y, c.z, 4, 0.5, 0.5, 0.5, 0);
		sound(p, c, SoundEvents.BLOCK_BEACON_ACTIVATE, 1f, 2f);
		sound(p, c, SoundEvents.ENTITY_PLAYER_ATTACK_STRONG, 1f, 0.7f);
		pw.cooldown(Tech.KROOM);
	}

	/** Anesthesia (mantener): el brazo se alarga con el ROOM y atraviesa; mientras mantienes, duele y paraliza. */
	public static void anesthesia(ServerPlayerEntity p, PlayerPower pw, boolean start) {
		if (!start) {
			pw.anesthesia = false;
			return;
		}
		if (pw.onCooldown(Tech.ANESTHESIA)) return;
		LivingEntity t = livingInSight(p, 14);
		if (t == null) return;
		pw.anesthesia = true;
		pw.anesthesiaTarget = t.getId();
		pw.anesthesiaTicks = 0;
		sound(p, t.getPos(), SoundEvents.ENTITY_ARROW_HIT_PLAYER, 1f, 0.5f);
		pw.cooldown(Tech.ANESTHESIA);
	}

	static void anesthesiaTick(ServerPlayerEntity p, PlayerPower pw) {
		if (!pw.anesthesia) return;
		Entity e = p.getWorld().getEntityById(pw.anesthesiaTarget);
		if (!(e instanceof LivingEntity le) || !le.isAlive() || le.squaredDistanceTo(p) > 18 * 18 || ++pw.anesthesiaTicks > 120) {
			pw.anesthesia = false;
			return;
		}
		Vec3d hand = p.getEyePos().add(p.getRotationVector().multiply(0.6)).subtract(0, 0.3, 0);
		if (pw.anesthesiaTicks % 2 == 0) beam(p, hand, le.getBoundingBox().getCenter(), CutEngine.ROOM_DUST);
		if (pw.anesthesiaTicks % 10 == 0) {
			le.damage(p.getWorld().getDamageSources().indirectMagic(p, p), 1.5f);
			le.addStatusEffect(new StatusEffectInstance(ModEffects.STUN, 20, 0, false, true));
		}
	}

	/** Shock Wille: una descarga enorme a distancia que no mira la armadura. */
	public static void shockWille(ServerPlayerEntity p, PlayerPower pw) {
		if (pw.onCooldown(Tech.SHOCK_WILLE)) return;
		LivingEntity t = livingInSight(p, 60);
		if (t == null) return;
		t.damage(ModDamage.of(p.getWorld(), ModDamage.GAMMA, p), dmg(180));
		t.addStatusEffect(new StatusEffectInstance(ModEffects.STUN, 40, 0, false, true));
		Vec3d push = p.getRotationVector().multiply(2.5).add(0, 0.6, 0);
		t.addVelocity(push.x, push.y, push.z);
		t.velocityModified = true;
		ServerWorld w = p.getServerWorld();
		Vec3d c = t.getBoundingBox().getCenter();
		w.spawnParticles(ParticleTypes.FLASH, c.x, c.y, c.z, 1, 0, 0, 0, 0);
		w.spawnParticles(ParticleTypes.ELECTRIC_SPARK, c.x, c.y, c.z, 120, 0.8, 1, 0.8, 0.8);
		beam(p, p.getEyePos().add(p.getRotationVector()), c, ParticleTypes.ELECTRIC_SPARK);
		sound(p, c, SoundEvents.ENTITY_LIGHTNING_BOLT_THUNDER, 1.2f, 1.4f);
		pw.cooldown(Tech.SHOCK_WILLE);
	}

	/** Puncture Wille: un cráter delante que se traga todo hacia dentro. */
	public static void puncture(ServerPlayerEntity p, PlayerPower pw) {
		if (pw.onCooldown(Tech.PUNCTURE)) return;
		ServerWorld w = p.getServerWorld();
		Vec3d c = PowerManager.aimPoint(p, 4.5);
		double R = 4.5, D = 2.6;
		BlockPos cb = BlockPos.ofFloored(c);
		int ri = (int) Math.ceil(R);
		for (BlockPos bp : BlockPos.iterate(cb.add(-ri, -3, -ri), cb.add(ri, 1, ri))) {
			double dx = bp.getX() + 0.5 - c.x, dy = bp.getY() + 0.5 - c.y, dz = bp.getZ() + 0.5 - c.z;
			if (dy > 0.6) continue;
			if ((dx * dx + dz * dz) / (R * R) + (dy * dy) / (D * D) > 1) continue;
			BlockState s = w.getBlockState(bp);
			if (s.isAir() || s.hasBlockEntity() || s.getHardness(w, bp) < 0) continue;
			if (p.getRandom().nextInt(5) == 0) {
				FallingBlockEntity fb = FallingBlockEntity.spawnFromBlock(w, bp.toImmutable(), s);
				Vec3d out = new Vec3d(dx, 1.5, dz).normalize().multiply(0.6);
				fb.setVelocity(out);
				fb.velocityModified = true;
			} else {
				w.setBlockState(bp, Blocks.AIR.getDefaultState(), 3);
			}
		}
		for (LivingEntity le : w.getEntitiesByClass(LivingEntity.class, new Box(c, c).expand(5.5), e -> e != p && e.isAlive())) {
			le.damage(w.getDamageSources().playerAttack(p), dmg(150));
			Vec3d in = c.subtract(le.getPos()).normalize().multiply(0.6).add(0, 0.8, 0);
			le.addVelocity(in.x, in.y, in.z);
			le.velocityModified = true;
		}
		w.spawnParticles(ParticleTypes.EXPLOSION_EMITTER, c.x, c.y, c.z, 1, 0, 0, 0, 0);
		w.spawnParticles(CutEngine.ROOM_DUST, c.x, c.y, c.z, 150, 3, 1, 3, 0);
		sound(p, c, SoundEvents.ENTITY_WARDEN_SONIC_BOOM, 1.5f, 0.6f);
		sound(p, c, SoundEvents.ENTITY_DRAGON_FIREBALL_EXPLODE, 1.5f, 0.6f);
		pw.cooldown(Tech.PUNCTURE);
	}

	/** Silent: nadie cerca te oye ni te sigue un rato. */
	public static void silent(ServerPlayerEntity p, PlayerPower pw) {
		if (pw.onCooldown(Tech.SILENT)) return;
		pw.silentTicks = 200;
		p.addStatusEffect(new StatusEffectInstance(StatusEffects.INVISIBILITY, 200, 0, false, false, true));
		sound(p, p.getPos(), SoundEvents.BLOCK_SCULK_SENSOR_CLICKING_STOP, 1f, 0.5f);
		pw.cooldown(Tech.SILENT);
		pw.dirty = true;
	}

	static void silentTick(ServerPlayerEntity p, PlayerPower pw) {
		if (pw.silentTicks <= 0) return;
		if (--pw.silentTicks == 0) pw.dirty = true;
		if (pw.silentTicks % 5 != 0) return;
		for (MobEntity m : p.getWorld().getEntitiesByClass(MobEntity.class, p.getBoundingBox().expand(24), e -> e.getTarget() == p)) {
			m.setTarget(null);
			m.getNavigation().stop();
		}
	}
}
