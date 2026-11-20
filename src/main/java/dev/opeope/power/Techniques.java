package dev.opeope.power;

import dev.opeope.OpeConfig;
import dev.opeope.entity.MarkerStoneEntity;
import dev.opeope.entity.PieceEntity;
import dev.opeope.entity.SterbenEntity;
import dev.opeope.item.HeartItem;
import dev.opeope.net.Payloads;
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
import net.minecraft.entity.attribute.EntityAttributeInstance;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.entity.boss.WitherEntity;
import net.minecraft.entity.boss.dragon.EnderDragonEntity;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.network.packet.s2c.play.EntityVelocityUpdateS2CPacket;
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
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** Las técnicas de cirujano, las del Despertar y las técnicas de Espada (Kikoku) y Pelea (Palma Quirúrgica). */
public final class Techniques {
	private Techniques() {}

	public static final DustParticleEffect GAMMA_DUST = new DustParticleEffect(new Vector3f(0.55f, 1f, 0.6f), 1.0f);
	private static final DustParticleEffect CURTAIN_DUST = new DustParticleEffect(new Vector3f(0.7f, 0.9f, 1f), 1.6f);
	public static final DustParticleEffect KROOM_DUST = new DustParticleEffect(new Vector3f(0.65f, 0.96f, 1.0f), 1.4f);
	public static final DustParticleEffect YOUTH_DUST = new DustParticleEffect(new Vector3f(1.0f, 0.35f, 0.55f), 1.5f);
	public static final DustParticleEffect GOLD_DUST = new DustParticleEffect(new Vector3f(1.0f, 0.85f, 0.3f), 1.4f);

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
		Entity e = PowerManager.raycastEntity(p, range, 0.4, ent -> (ent instanceof LivingEntity && ent.isAlive())
			|| ent instanceof dev.opeope.entity.BodyPartEntity);
		if (e instanceof dev.opeope.entity.BodyPartEntity part) {
			// una parte cortada sigue unida a su dueño: lo que se le hace a ella (Gamma, Counter Shock, Mes…) le llega a él
			LivingEntity v = part.victim();
			if (v == null || !v.isAlive() || v == p) return null;
			Vec3d a = part.getBoundingBox().getCenter(), b = v.getBoundingBox().getCenter();
			ServerWorld w = p.getServerWorld();
			double len = a.distanceTo(b);
			for (double t = 0; t <= len; t += 0.4) {
				Vec3d q = a.add(b.subtract(a).multiply(t / Math.max(1e-3, len)));
				w.spawnParticles(ParticleTypes.ELECTRIC_SPARK, q.x, q.y, q.z, 1, 0.02, 0.02, 0.02, 0);
			}
			return v;
		}
		return e instanceof LivingEntity le ? le : null;
	}

	private static LivingEntity resolveAerialTarget(ServerPlayerEntity p, PlayerPower pw, double range) {
		LivingEntity t = livingInSight(p, range);
		if (t != null) return t;
		if (pw.aerialWindowTicks > 0 && pw.finisherTargetId >= 0
			&& p.getWorld().getEntityById(pw.finisherTargetId) instanceof LivingEntity fin
			&& fin != p && fin.isAlive()) {
			Vec3d to = fin.getBoundingBox().getCenter().subtract(p.getEyePos());
			if (to.lengthSquared() <= range * range && (to.lengthSquared() < 1e-4 || to.normalize().dotProduct(p.getRotationVector()) > 0.25)) {
				return fin;
			}
		}
		return null;
	}

	private static float consumeAerialWindow(ServerPlayerEntity p, PlayerPower pw) {
		if (pw.aerialWindowTicks <= 0) return 1.0f;
		float mult = pw.aerialPerfect ? OpeConfig.SHAMBLES_AERIAL_DMG_MULT_PERFECT : OpeConfig.SHAMBLES_AERIAL_DMG_MULT;
		pw.clearAerialWindow();
		Shambles.syncAerialWindow(p, pw);
		return mult;
	}

	private static void sound(ServerPlayerEntity p, Vec3d at, net.minecraft.sound.SoundEvent s, float vol, float pitch) {
		p.getWorld().playSound(null, at.x, at.y, at.z, s, SoundCategory.PLAYERS, vol, pitch);
	}

	/** Anillo horizontal de partículas 3D para ondas de choque o resonancias quirúrgicas. */
	public static void ring(ServerWorld w, Vec3d c, double radius, int pts, ParticleEffect p) {
		for (int i = 0; i < pts; i++) {
			double ang = Math.PI * 2 * i / pts;
			w.spawnParticles(p, c.x + Math.cos(ang) * radius, c.y, c.z + Math.sin(ang) * radius, 1, 0, 0, 0, 0);
		}
	}

	/** Hélice 3D giratoria a lo largo de un rayo entre dos puntos. */
	public static void helix(ServerWorld w, Vec3d from, Vec3d to, double radius, double turns, ParticleEffect p) {
		Vec3d diff = to.subtract(from);
		double len = diff.length();
		if (len < 0.25) return;
		Vec3d dir = diff.normalize();
		Vec3d side = dir.crossProduct(new Vec3d(0, 1, 0));
		if (side.lengthSquared() < 1e-4) side = new Vec3d(1, 0, 0);
		side = side.normalize();
		Vec3d up = side.crossProduct(dir).normalize();
		int steps = (int) Math.min(90, Math.max(14, len * 3.2));
		for (int i = 0; i <= steps; i++) {
			double u = i / (double) steps;
			double ang = u * Math.PI * 2.0 * turns;
			Vec3d pt = from.add(diff.multiply(u)).add(side.multiply(Math.cos(ang) * radius)).add(up.multiply(Math.sin(ang) * radius));
			w.spawnParticles(p, pt.x, pt.y, pt.z, 1, 0, 0, 0, 0);
		}
	}

	// ------------------------------------------------------------------ Estilos de Combate (Fruta, Espada Kikoku, Pelea)

	public static void setStyle(ServerPlayerEntity p, PlayerPower pw, int style) {
		if (style <= 0) style = (pw.combatStyle % 3) + 1;
		pw.combatStyle = Math.max(1, Math.min(3, style));
		pw.dirty = true;
		if (pw.combatStyle == 2) {
			equipKikokuSafely(p);
			// las teclas de fábrica; el cliente pone las de verdad (HudRoot.styleText)
			p.sendMessage(Text.translatable("opeope.msg.estilo2", "Z", "X", "C", "M"), true);
		} else if (pw.combatStyle == 3) {
			unequipKikokuSafely(p);
			p.sendMessage(Text.translatable("opeope.msg.estilo3", "Z", "X", "C", "V"), true);
		} else {
			p.sendMessage(Text.translatable("opeope.msg.estilo1"), true);
		}
		sound(p, p.getPos(), SoundEvents.ITEM_ARMOR_EQUIP_NETHERITE.value(), 1.0f, 1.2f);
	}

	public static void equipKikokuSafely(ServerPlayerEntity p) {
		ItemStack main = p.getMainHandStack();
		if (main.isOf(ModItems.KIKOKU)) return;
		var inv = p.getInventory();
		int kikokuSlot = -1;
		for (int i = 0; i < inv.main.size(); i++) {
			if (inv.main.get(i).isOf(ModItems.KIKOKU)) {
				kikokuSlot = i;
				break;
			}
		}
		if (kikokuSlot >= 0) {
			ItemStack kStack = inv.main.get(kikokuSlot);
			inv.main.set(kikokuSlot, main);
			p.setStackInHand(Hand.MAIN_HAND, kStack);
		} else {
			if (!main.isEmpty()) inv.offerOrDrop(main.copy());
			p.setStackInHand(Hand.MAIN_HAND, new ItemStack(ModItems.KIKOKU));
		}
	}

	private static void unequipKikokuSafely(ServerPlayerEntity p) {
		ItemStack main = p.getMainHandStack();
		if (!main.isOf(ModItems.KIKOKU)) return;
		var inv = p.getInventory();
		for (int i = 9; i < inv.main.size(); i++) {
			if (inv.main.get(i).isEmpty()) {
				inv.main.set(i, main);
				p.setStackInHand(Hand.MAIN_HAND, ItemStack.EMPTY);
				return;
			}
		}
	}

	/** Iai: Incisión Relámpago (Estilo 2 - Z): paso relámpago de 8.5 bloques que corta estructuras y enemigos con CutFx. */
	public static void swordIai(ServerPlayerEntity p, PlayerPower pw) {
		if (pw.onCooldown(Tech.RADIO_KNIFE)) return;
		Vec3d start = p.getPos();
		Vec3d look = p.getRotationVector();
		Vec3d end = start.add(look.multiply(8.5));
		beam(p, start.add(0, 1, 0), end.add(0, 1, 0), CutEngine.ROOM_DUST);
		Shambles.teleport(p, end);
		Vec3d mid = start.lerp(end, 0.5).add(0, 1.0, 0);
		Room room = PowerManager.roomAt(p, mid);
		Vec3d right = look.crossProduct(new Vec3d(0, 1, 0));
		if (right.lengthSquared() < 1e-4) right = new Vec3d(1, 0, 0);
		right = right.normalize();
		Vec3d up = right.crossProduct(look).normalize();
		List<CutEngine.Plane> planes = List.of(new CutEngine.Plane(up.add(right.multiply(0.35)).normalize(), mid));
		CutEngine.Region region = room != null
			? CutEngine.Region.of(room).withLimit(mid, 7.5)
			: CutEngine.Region.sphere(mid, 5.5);
		CutEngine.cut(p, planes, region, pw.suspend, 0, dmg(35), room != null);
		sound(p, end, SoundEvents.ENTITY_LIGHTNING_BOLT_THUNDER, 0.8f, 1.7f);
		p.sendMessage(Text.literal("§bIai: Incisión Relámpago"), true);
		pw.cooldown(Tech.RADIO_KNIFE);
	}

	/** Luna de Acero (Estilo 2 - X): onda cortante de largo alcance que parte edificios y enemigos en la mira. */
	public static void swordMoonSlash(ServerPlayerEntity p, PlayerPower pw) {
		if (pw.onCooldown(Tech.INJECTION)) return;
		Vec3d eye = p.getEyePos();
		Vec3d look = p.getRotationVector();
		Vec3d point = PowerManager.aimPoint(p, 40);
		Room room = PowerManager.roomAt(p, point);
		if (room == null) room = pw.room;
		Vec3d right = look.crossProduct(new Vec3d(0, 1, 0));
		if (right.lengthSquared() < 1e-4) right = new Vec3d(1, 0, 0);
		right = right.normalize();
		Vec3d up = right.crossProduct(look).normalize();
		List<CutEngine.Plane> planes = List.of(
			new CutEngine.Plane(up.add(right.multiply(0.25)).normalize(), eye),
			new CutEngine.Plane(right, point)
		);
		CutEngine.Region region = room != null
			? CutEngine.Region.of(room).withEye(eye, look)
			: CutEngine.Region.sphere(point, 5.0);
		CutEngine.cut(p, planes, region, pw.suspend, 0, dmg(32), room != null);
		beam(p, eye.add(look), point, ParticleTypes.SWEEP_ATTACK);
		sound(p, p.getPos(), SoundEvents.ENTITY_PLAYER_ATTACK_SWEEP, 1.4f, 0.7f);
		p.sendMessage(Text.literal("§bKikoku: Luna de Acero"), true);
		pw.cooldown(Tech.INJECTION);
	}

	/** Torbellino Cortante 360° (Estilo 2 - C): doble plano cruzado rotatorio alrededor del jugador. */
	public static void swordWhirlwind(ServerPlayerEntity p, PlayerPower pw) {
		if (pw.onCooldown(Tech.GAMMA)) return;
		Vec3d center = p.getPos().add(0, 1.1, 0);
		Room room = pw.room;
		List<CutEngine.Plane> planes = List.of(
			new CutEngine.Plane(new Vec3d(0, 1, 0.18).normalize(), center),
			new CutEngine.Plane(new Vec3d(0.18, 1, 0).normalize(), center.add(0, 0.6, 0))
		);
		CutEngine.Region region = room != null
			? CutEngine.Region.of(room).withLimit(center, Math.min(room.radius, 11.0))
			: CutEngine.Region.sphere(center, 6.5);
		CutEngine.cut(p, planes, region, pw.suspend, 0, dmg(40), room != null);
		p.getServerWorld().spawnParticles(ParticleTypes.SWEEP_ATTACK, center.x, center.y, center.z, 12, 2.5, 0.5, 2.5, 0);
		sound(p, center, SoundEvents.ENTITY_PLAYER_ATTACK_SWEEP, 1.5f, 0.6f);
		p.sendMessage(Text.literal("§bKikoku: Torbellino Cortante 360°"), true);
		pw.cooldown(Tech.GAMMA);
	}

	/** Mil Puntadas (Estilo 2 - V): corta el objetivo en Dados 3D al instante. */
	public static void swordThousandThrusts(ServerPlayerEntity p, PlayerPower pw) {
		if (pw.room == null) {
			PowerManager.noRoom(p);
			return;
		}
		Vec3d eye = p.getEyePos();
		Vec3d look = p.getRotationVector();
		Vec3d right = look.crossProduct(new Vec3d(0, 1, 0));
		if (right.lengthSquared() < 1e-4) right = new Vec3d(1, 0, 0);
		right = right.normalize();
		Vec3d up = right.crossProduct(look).normalize();
		List<CutEngine.Plane> planes = Patterns.expand(p, new CutEngine.Plane(up, eye), 3); // 3 = Dados 3D
		CutEngine.Region region = CutEngine.Region.of(pw.room).withEye(eye, look);
		CutEngine.cut(p, planes, region, pw.suspend, 0, dmg(38), true);
		sound(p, eye, SoundEvents.ENTITY_WARDEN_SONIC_BOOM, 1.0f, 1.5f);
		p.sendMessage(Text.literal("§bKikoku: ¡Mil Puntadas (Dados 3D)!"), true);
	}

	/** Combo de Artes Marciales y Palma Quirúrgica (Estilo 3). */
	public static void fightCombo(ServerPlayerEntity p, PlayerPower pw) {
		ServerWorld w = p.getServerWorld();
		long now = w.getTime();
		if (now - pw.lastComboTick > 24) pw.comboStep = 0;
		pw.lastComboTick = now;
		int step = pw.comboStep++ % 4;
		String[] names = {"Jab Rápido", "Directo al Pecho", "Codazo Giratorio", "Patada Circular Quirúrgica"};
		Vec3d look = p.getRotationVector();
		Vec3d front = p.getEyePos().add(look.multiply(2.0));
		w.spawnParticles(CutEngine.ROOM_DUST, front.x, front.y, front.z, 15, 0.4, 0.4, 0.4, 0.02);
		for (LivingEntity le : w.getEntitiesByClass(LivingEntity.class, p.getBoundingBox().expand(3.6), e -> e != p && e.isAlive())) {
			le.damage(w.getDamageSources().playerAttack(p), 5.0f + step * 2.0f);
			if (step == 3) {
				le.addVelocity(look.x * 1.2, 0.45, look.z * 1.2);
				le.velocityModified = true;
				if (pw.room != null && pw.room.contains(le.getPos())) {
					CutStates.cutLiving(le, List.of(new CutEngine.Plane(new Vec3d(0, 1, 0), le.getBoundingBox().getCenter())), OpeConfig.CUT_LIVING_TICKS / 2, 0);
				}
			}
		}
		sound(p, p.getPos(), SoundEvents.ENTITY_PLAYER_ATTACK_STRONG, 1.0f, 0.9f + step * 0.12f);
		p.sendMessage(Text.literal("§6Pelea (" + (step + 1) + "/4): " + names[step]), true);
	}

	/** Trueque Quirúrgico / Luxación (Estilo 3 - C): cambia de lugar con el enemigo o aparece a su espalda y lo derriba. */
	public static void fightTrueque(ServerPlayerEntity p, PlayerPower pw) {
		LivingEntity t = livingInSight(p, 16);
		if (t == null) {
			p.sendMessage(Text.translatable("opeope.msg.too_far"), true);
			return;
		}
		Vec3d behind = t.getPos().subtract(t.getRotationVector().multiply(1.5));
		Shambles.teleport(p, behind);
		Shambles.fx(p, p.getPos(), t.getPos());
		t.damage(p.getServerWorld().getDamageSources().playerAttack(p), dmg(50));
		t.addVelocity(0, -1.1, 0);
		t.velocityModified = true;
		t.addStatusEffect(new StatusEffectInstance(ModEffects.STUN, 40, 0, false, true));
		p.sendMessage(Text.literal("§6Palma Quirúrgica: Trueque y Luxación"), true);
	}

	/** Gancho Ascendente / Palma Takt (Estilo 3 - F): lanza al enemigo al aire y lo captura en Takt. */
	public static void fightUppercutTakt(ServerPlayerEntity p, PlayerPower pw) {
		LivingEntity t = livingInSight(p, 6.5);
		if (t != null) {
			t.damage(p.getServerWorld().getDamageSources().playerAttack(p), dmg(35));
			t.addVelocity(0, 0.95, 0);
			t.velocityModified = true;
			if (pw.room != null && pw.room.contains(t.getPos())) {
				Takt.grab(p, pw, t);
			}
			sound(p, t.getPos(), SoundEvents.ENTITY_IRON_GOLEM_ATTACK, 1.0f, 1.2f);
			p.sendMessage(Text.literal("§6Palma Ascendente: objetivo elevado en Takt"), true);
		} else {
			p.addVelocity(0, 0.65, 0);
			p.velocityModified = true;
			pw.fallImmunityTicks = 200;
		}
	}

	/** Mover el ROOM activo arriba/abajo/horizontalmente con las flechas. */
	public static void moveRoom(ServerPlayerEntity p, PlayerPower pw, int dirCode) {
		Room room = pw.room;
		if (room == null || room.dim != p.getWorld().getRegistryKey()) return;
		Vec3d look = p.getRotationVector();
		Vec3d flat = new Vec3d(look.x, 0, look.z);
		if (flat.lengthSquared() < 1e-4) flat = new Vec3d(0, 0, 1);
		else flat = flat.normalize();
		Vec3d right = new Vec3d(-flat.z, 0, flat.x);
		Vec3d delta = switch (dirCode) {
			case 1 -> new Vec3d(0, 2.5, 0);     // Subir ROOM (Flecha Arriba)
			case 2 -> new Vec3d(0, -2.5, 0);    // Bajar ROOM (Flecha Abajo)
			case 3 -> right.multiply(-2.5);     // Izquierda (Flecha Izquierda)
			case 4 -> right.multiply(2.5);      // Derecha (Flecha Derecha)
			case 5 -> flat.multiply(3.0);       // Adelante
			default -> Vec3d.ZERO;
		};
		room.center = room.center.add(delta);
		room.state = Room.State.FIXED;
		PowerManager.broadcastRoom(p, 0, room);
	}

	/** Acciones del panel de Isla Bisturí [J]: generar estructuras, barco de la Marina, misiones, Modo Libre y nuevas técnicas definitivas. */
	public static void islandAction(ServerPlayerEntity p, PlayerPower pw, int id) {
		ServerWorld w = p.getServerWorld();
		switch (id) {
			case 1 -> StructureBuilder.buildBisturiHouse(w, p);
			case 2 -> StructureBuilder.buildWatchtower(w, p);
			case 3 -> StructureBuilder.buildBridgeAndWall(w, p);
			case 4 -> StructureBuilder.buildRockFormation(w, p);
			case 5 -> StructureBuilder.spawnPirateSquad(w, p);
			case 6 -> StructureBuilder.buildMarineWarship(w, p);
			case 7 -> StructureBuilder.buildMissionOutpost(w, p);
			case 8 -> {
				pw.freeMode = !pw.freeMode;
				if (pw.freeMode) {
					for (int i = 0; i < pw.cooldowns.length; i++) pw.cooldowns[i] = 0;
				}
				pw.dirty = true;
				p.sendMessage(Text.literal("§eModo Libre (Sin Enfriamientos): " + (pw.freeMode ? "§aACTIVADO" : "§cDESACTIVADO")), true);
			}
			case 9 -> {
				if (pw.room != null) {
					pw.room.radius = Math.min(OpeConfig.ROOM_MAX_RADIUS, pw.room.radius + 12.0);
					pw.room.state = Room.State.FIXED;
					pw.lastRoomRadius = pw.room.radius;
					RoomEvents.send(p, 0, Payloads.RoomFx.NOTCH, pw.room.center, null, (float) pw.room.radius, (float) (pw.room.radius - 12.0), 0, Payloads.RoomFx.F_GROW);
					PowerManager.broadcastRoom(p, 0, pw.room);
					p.sendMessage(Text.literal("§bRadio del ROOM ampliado a " + (int) pw.room.radius + " bloques"), true);
				} else {
					Room room = new Room(w.getRegistryKey(), p.getPos().add(0, 1.0, 0), 36.0);
					room.state = Room.State.FIXED;
					pw.room = room;
					pw.lastRoomRadius = 36.0;
					PowerManager.broadcastRoom(p, 0, room);
					p.sendMessage(Text.literal("§bROOM amplio (36 bloques) desplegado"), true);
				}
			}
			case 10 -> {
				if (!ModAttachments.hasAwakening(p)) {
					PowerManager.grantAwakening(p);
				}
				toggleAwaken(p, pw);
			}
			case 11 -> {
				if (!ModAttachments.hasAwakening(p)) PowerManager.grantAwakening(p);
				pw.awakened = true;
				kroom(p, pw);
			}
			case 12 -> eternalYouthSurgery(p, pw);
			case 13 -> {
				if (pw.room == null) {
					Room room = new Room(w.getRegistryKey(), p.getPos().add(0, 1.0, 0), 32.0);
					room.state = Room.State.FIXED;
					pw.room = room;
					pw.lastRoomRadius = 32.0;
					PowerManager.broadcastRoom(p, 0, room);
				}
				Shambles.projectileBarrage(p, pw);
			}
			case 14 -> puncture(p, pw);
			case 15 -> toggleZeroGravityRoom(p, pw);
			case 16 -> colossalTakt(p, pw);
			case 17 -> sterben(p, pw);
			case 18 -> souls(p, pw);
		}
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
		if (pw.combatStyle == 3) {
			fightUppercutTakt(p, pw);
			return;
		}
		if (pw.onCooldown(Tech.SCAN)) return;
		pw.scanTicks = OpeConfig.SCAN_TICKS;
		pw.cooldown(Tech.SCAN);
		sound(p, p.getPos(), SoundEvents.BLOCK_BEACON_POWER_SELECT, 1f, 2f);
		p.sendMessage(Text.translatable("opeope.msg.scan"), true);
	}

	/** Radio Knife: corte eléctrico. Hiere, corta en tres y lo cortado no se puede rearmar en 5 s. */
	public static void radioKnife(ServerPlayerEntity p, PlayerPower pw) {
		if (pw.combatStyle == 2) {
			swordIai(p, pw);
			return;
		}
		if (pw.combatStyle == 3) {
			mes(p, pw);
			return;
		}
		boolean aerial = pw.aerialWindowTicks > 0;
		if (!aerial && pw.onCooldown(Tech.RADIO_KNIFE)) return;
		LivingEntity t = resolveAerialTarget(p, pw, 60);
		Vec3d point = t != null ? t.getBoundingBox().getCenter() : PowerManager.aimPoint(p, 60);
		float aerialMult = consumeAerialWindow(p, pw);
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
			? CutEngine.Region.of(room).withLimit(point, 4.5)
			: CutEngine.Region.sphere(point, 4.5);
		CutEngine.cut(p, planes, region, pw.suspend, OpeConfig.RADIO_KNIFE_NO_REATTACH, dmg(22 * aerialMult), room != null);
		ServerWorld w = p.getServerWorld();
		w.spawnParticles(ParticleTypes.ELECTRIC_SPARK, point.x, point.y, point.z, 80, 1.5, 1.5, 1.5, 0.4);
		w.spawnParticles(ParticleTypes.SWEEP_ATTACK, point.x, point.y, point.z, 3, 0.8, 0.8, 0.8, 0);
		beam(p, p.getEyePos().add(look), point, ParticleTypes.ELECTRIC_SPARK);
		helix(w, p.getEyePos().add(look), point, 0.35, 4.0, CutEngine.ROOM_DUST);
		ring(w, point, 2.4, 24, ParticleTypes.ELECTRIC_SPARK);
		ring(w, point, 3.6, 30, CutEngine.ROOM_DUST);
		sound(p, point, SoundEvents.BLOCK_CONDUIT_ATTACK_TARGET, 1.1f, 1.65f);
		sound(p, point, SoundEvents.ITEM_TRIDENT_THROW.value(), 1.0f, 1.85f);
		sound(p, point, SoundEvents.ENTITY_LIGHTNING_BOLT_IMPACT, 1f, 1.6f);
		sound(p, p.getPos(), SoundEvents.ENTITY_PLAYER_ATTACK_SWEEP, 1f, 0.8f);
		if (!aerial) pw.cooldown(Tech.RADIO_KNIFE);
	}

	/** Injection Shot: embestida que dispara al clavarse. */
	public static void injection(ServerPlayerEntity p, PlayerPower pw) {
		if (pw.combatStyle == 2) {
			swordMoonSlash(p, pw);
			return;
		}
		if (pw.combatStyle == 3) {
			counterShock(p, pw);
			return;
		}
		if (pw.onCooldown(Tech.INJECTION)) return;
		Vec3d dashDir = p.getRotationVector();
		LivingEntity aimTarget = resolveAerialTarget(p, pw, 24.0);
		if (aimTarget != null && pw.aerialWindowTicks > 0) {
			Vec3d to = aimTarget.getBoundingBox().getCenter().subtract(p.getEyePos());
			if (to.lengthSquared() > 1e-4) dashDir = to.normalize();
		}
		pw.aerialFinisherMult = consumeAerialWindow(p, pw);
		pw.dashTicks = 7;
		pw.dashDir = dashDir;
		p.setVelocity(pw.dashDir.multiply(1.6));
		p.velocityModified = true;
		if (p.networkHandler != null) {
			p.networkHandler.sendPacket(new EntityVelocityUpdateS2CPacket(p.getId(), p.getVelocity()));
		}
		ring(p.getServerWorld(), p.getPos().add(0, 1.0, 0), 1.4, 20, CutEngine.ROOM_DUST);
		sound(p, p.getPos(), SoundEvents.ITEM_TRIDENT_RIPTIDE_3.value(), 1.0f, 1.45f);
		sound(p, p.getPos(), SoundEvents.ENTITY_BREEZE_WIND_BURST.value(), 1.0f, 1.25f);
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
		w.spawnParticles(CutEngine.ROOM_DUST, p.getX(), p.getY() + 1, p.getZ(), 5, 0.25, 0.4, 0.25, 0);
		w.spawnParticles(ParticleTypes.END_ROD, p.getX(), p.getY() + 1, p.getZ(), 2, 0.15, 0.3, 0.15, 0.03);
		for (LivingEntity le : w.getEntitiesByClass(LivingEntity.class, p.getBoundingBox().expand(0.9), e -> e != p && e.isAlive())) {
			le.damage(w.getDamageSources().playerAttack(p), dmg(45 * pw.aerialFinisherMult));
			pw.aerialFinisherMult = 1.0f;
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
			ring(w, c, 2.2, 24, CutEngine.ROOM_DUST);
			ring(w, c, 3.0, 28, ParticleTypes.ELECTRIC_SPARK);
			sound(p, c, SoundEvents.ENTITY_WARDEN_SONIC_BOOM, 1.1f, 1.6f);
			sound(p, c, SoundEvents.BLOCK_AMETHYST_BLOCK_RESONATE, 1.1f, 1.5f);
			sound(p, c, SoundEvents.ENTITY_LIGHTNING_BOLT_IMPACT, 1f, 1.2f);
			pw.dashTicks = 0;
			p.setVelocity(pw.dashDir.multiply(-0.3));
			p.velocityModified = true;
			break;
		}
		if (pw.dashTicks == 0) pw.aerialFinisherMult = 1.0f;
	}

	/** Gamma Knife: destruye por dentro y no mira la armadura. */
	public static void gamma(ServerPlayerEntity p, PlayerPower pw) {
		if (pw.combatStyle == 2) {
			swordWhirlwind(p, pw);
			return;
		}
		if (pw.combatStyle == 3) {
			fightTrueque(p, pw);
			return;
		}
		if (pw.onCooldown(Tech.GAMMA)) return;
		boolean aerial = pw.aerialWindowTicks > 0;
		double range = aerial ? OpeConfig.SHAMBLES_GAMMA_AERIAL_RANGE : 4.5;
		LivingEntity t = resolveAerialTarget(p, pw, range);
		if (t == null) {
			p.sendMessage(Text.translatable("opeope.msg.too_far"), true);
			return;
		}
		if (aerial && t.getBoundingBox().getCenter().squaredDistanceTo(p.getEyePos()) > 4.5 * 4.5) {
			Vec3d toTarget = t.getBoundingBox().getCenter().subtract(p.getEyePos());
			Vec3d dir = toTarget.lengthSquared() > 1e-4 ? toTarget.normalize() : p.getRotationVector();
			Vec3d startEye = p.getEyePos();
			Vec3d lungePos = t.getBoundingBox().getCenter().subtract(dir.multiply(2.1));
			if (p.getWorld().isSpaceEmpty(p, p.getBoundingBox().offset(lungePos.subtract(p.getPos())))) {
				p.requestTeleport(lungePos.x, lungePos.y, lungePos.z);
			}
			p.setVelocity(dir.multiply(1.4));
			p.velocityModified = true;
			p.fallDistance = 0f;
			pw.fallImmunityTicks = Math.max(pw.fallImmunityTicks, 100);
			if (p.networkHandler != null) {
				p.networkHandler.sendPacket(new EntityVelocityUpdateS2CPacket(p.getId(), p.getVelocity()));
			}
			beam(p, startEye, t.getBoundingBox().getCenter(), GAMMA_DUST);
		}
		float aerialMult = consumeAerialWindow(p, pw);
		t.damage(ModDamage.of(p.getWorld(), ModDamage.GAMMA, p), dmg(70 * aerialMult));
		t.addStatusEffect(new StatusEffectInstance(StatusEffects.WITHER, 60, 0));
		ServerWorld w = p.getServerWorld();
		Box b = t.getBoundingBox();
		w.spawnParticles(GAMMA_DUST, b.getCenter().x, b.getCenter().y, b.getCenter().z, 60, b.getLengthX() / 3, b.getLengthY() / 3, b.getLengthZ() / 3, 0);
		w.spawnParticles(ParticleTypes.END_ROD, b.getCenter().x, b.getCenter().y, b.getCenter().z, 20, b.getLengthX() / 3, b.getLengthY() / 3, b.getLengthZ() / 3, 0.02);
		beam(p, p.getEyePos().add(p.getRotationVector()), b.getCenter(), GAMMA_DUST);
		helix(w, p.getEyePos().add(p.getRotationVector()), b.getCenter(), 0.28, 3.5, ParticleTypes.END_ROD);
		ring(w, b.getCenter(), 1.8, 22, GAMMA_DUST);
		sound(p, b.getCenter(), SoundEvents.BLOCK_CONDUIT_ATTACK_TARGET, 1.2f, 1.75f);
		sound(p, b.getCenter(), SoundEvents.BLOCK_AMETHYST_BLOCK_CHIME, 1.1f, 1.95f);
		sound(p, b.getCenter(), SoundEvents.BLOCK_BEACON_DEACTIVATE, 1.2f, 1.8f);
		sound(p, b.getCenter(), SoundEvents.ENTITY_WARDEN_SONIC_BOOM, 0.4f, 2f);
		pw.cooldown(Tech.GAMMA);
	}

	public static void toggleAwaken(ServerPlayerEntity p, PlayerPower pw) {
		if (pw.combatStyle == 2) {
			swordThousandThrusts(p, pw);
			return;
		}
		if (!ModAttachments.hasAwakening(p)) {
			p.sendMessage(Text.translatable("opeope.msg.no_awakening"), true);
			return;
		}
		pw.awakened = !pw.awakened;
		pw.dirty = true;
		ServerWorld w = p.getServerWorld();
		w.spawnParticles(CutEngine.ROOM_DUST, p.getX(), p.getY() + 1, p.getZ(), 80, 1.2, 1.2, 1.2, 0);
		ring(w, p.getPos().add(0, 0.5, 0), 2.5, 28, CutEngine.ROOM_DUST);
		ring(w, p.getPos().add(0, 1.2, 0), 1.8, 24, GOLD_DUST);
		sound(p, p.getPos(), SoundEvents.BLOCK_AMETHYST_BLOCK_CHIME, 1.1f, 1.5f);
		sound(p, p.getPos(), pw.awakened ? SoundEvents.BLOCK_CONDUIT_ACTIVATE : SoundEvents.BLOCK_CONDUIT_DEACTIVATE, 1.2f, 1.2f);
		p.sendMessage(Text.translatable(pw.awakened ? "opeope.msg.awake_on" : "opeope.msg.awake_off"), true);
	}

	/** Curtain: un velo delante que reduce el daño (60 %, como Resistencia III). */
	public static void curtain(ServerPlayerEntity p, PlayerPower pw) {
		if (pw.onCooldown(Tech.CURTAIN)) return;
		pw.curtainTicks = OpeConfig.CURTAIN_TICKS;
		p.addStatusEffect(new StatusEffectInstance(StatusEffects.RESISTANCE, OpeConfig.CURTAIN_TICKS, 2, false, false, true));
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
		if (p.isSneaking()) {
			colossalTakt(p, pw);
			return;
		}
		if (pw.onCooldown(Tech.STERBEN)) return;
		SterbenEntity.launch(p);
		sound(p, p.getPos(), SoundEvents.ENTITY_PLAYER_ATTACK_SWEEP, 1.5f, 0.5f);
		ring(p.getServerWorld(), p.getPos().add(0, 1.0, 0), 1.8, 22, CutEngine.ROOM_DUST);
		sound(p, p.getPos(), SoundEvents.ITEM_TRIDENT_RIPTIDE_3.value(), 1.2f, 1.3f);
		sound(p, p.getPos(), SoundEvents.BLOCK_AMETHYST_BLOCK_CHIME, 1.1f, 1.8f);
		p.sendMessage(Text.literal("§b⚙ Sterben: ¡Hoja de la Muerte (Sierra Kikoku 3D)!"), true);
		pw.cooldown(Tech.STERBEN);
	}

	/**
	 * Takt Colosal: Arranca o condensa un Meteorito Quirúrgico Gigante de roca, pizarra profunda, magma y obsidiana llorosa,
	 * y lo pone inmediatamente bajo tu control de Takt sobre tu cabeza para que lo dirijas con la mirada y lo lances con Clic Izquierdo.
	 */
	public static void colossalTakt(ServerPlayerEntity p, PlayerPower pw) {
		ServerWorld w = p.getServerWorld();
		if (pw.room == null || pw.room.dim != w.getRegistryKey()) {
			Room room = new Room(w.getRegistryKey(), p.getPos().add(0, 1.0, 0), 36.0);
			room.state = Room.State.FIXED;
			pw.room = room;
			pw.lastRoomRadius = 36.0;
			PowerManager.broadcastRoom(p, 0, room);
		}
		dev.opeope.entity.BlockSet set = new dev.opeope.entity.BlockSet();
		int r = 3;
		for (int dx = -r; dx <= r; dx++) {
			for (int dy = -r; dy <= r; dy++) {
				for (int dz = -r; dz <= r; dz++) {
					double d2 = dx * dx + dy * dy * 1.15 + dz * dz;
					if (d2 > r * r + 1.5) continue;
					BlockState st;
					int roll = Math.abs((dx * 31 + dy * 17 + dz * 13)) % 10;
					if (d2 <= 2.2) st = Blocks.MAGMA_BLOCK.getDefaultState();
					else if (roll == 0) st = Blocks.CRYING_OBSIDIAN.getDefaultState();
					else if (roll <= 3) st = Blocks.COBBLED_DEEPSLATE.getDefaultState();
					else if (roll <= 6) st = Blocks.DEEPSLATE.getDefaultState();
					else if (roll == 7) st = Blocks.GILDED_BLACKSTONE.getDefaultState();
					else st = Blocks.STONE.getDefaultState();
					set.add(dx + r, dy + r, dz + r, st, null);
				}
			}
		}
		set.normalize();
		Vec3d spawnPos = p.getEyePos().add(p.getRotationVector().multiply(11.0)).add(0, 5.0, 0);
		PieceEntity meteor = PieceEntity.spawn(w, set, spawnPos.subtract(set.sx / 2.0, set.sy / 2.0, set.sz / 2.0), BlockPos.ofFloored(spawnPos), p.getUuid());
		Takt.captureCutGroup(p, pw, List.of(meteor));
		w.spawnParticles(ParticleTypes.EXPLOSION_EMITTER, spawnPos.x, spawnPos.y, spawnPos.z, 3, 1.5, 1.5, 1.5, 0);
		w.spawnParticles(CutEngine.ROOM_DUST, spawnPos.x, spawnPos.y, spawnPos.z, 80, 2.5, 2.5, 2.5, 0.05);
		sound(p, p.getPos(), SoundEvents.ENTITY_WARDEN_SONIC_CHARGE, 1.5f, 0.7f);
		ring(w, spawnPos, 4.5, 36, CutEngine.ROOM_DUST);
		ring(w, p.getPos().add(0, 0.3, 0), 3.0, 26, CutEngine.ROOM_DUST);
		sound(p, p.getPos(), SoundEvents.BLOCK_BEACON_POWER_SELECT, 1.3f, 0.65f);
		sound(p, spawnPos, SoundEvents.ENTITY_WARDEN_SONIC_BOOM, 1.1f, 0.6f);
		sound(p, spawnPos, SoundEvents.BLOCK_ANVIL_PLACE, 1.2f, 0.5f);
		p.sendMessage(Text.literal("§b☄ Takt Colosal: ¡Meteorito Quirúrgico bajo tu control! (Clic Izq. para estrellarlo)"), true);
	}

	/**
	 * Kokoro Shambles (Intercambio de Almas y Cirugía de Control Mental - Tab+X o desde [J]):
	 * - Si hay 2 o más seres en el ROOM, intercambia sus almas, cuerpos, porcentajes de vida y posiciones al instante,
	 *   haciendo que se ataquen entre sí.
	 * - Además, implanta tu Voluntad Quirúrgica en el objetivo, convirtiéndolo en tu aliado y guardaespaldas personal.
	 */
	public static void souls(ServerPlayerEntity p, PlayerPower pw) {
		if (pw.onCooldown(Tech.SOULS)) return;
		ServerWorld w = p.getServerWorld();
		if (pw.room == null || pw.room.dim != w.getRegistryKey()) {
			Room room = new Room(w.getRegistryKey(), p.getPos().add(0, 1.0, 0), 28.0);
			room.state = Room.State.FIXED;
			pw.room = room;
			pw.lastRoomRadius = 28.0;
			PowerManager.broadcastRoom(p, 0, room);
		}
		Room room = pw.room;
		LivingEntity target = livingInSight(p, 60);
		List<LivingEntity> candidates = w.getEntitiesByClass(LivingEntity.class,
			new Box(room.center, room.center).expand(room.radius),
			e -> e != p && e.isAlive() && room.contains(e.getPos()));
		if (target == null && !candidates.isEmpty()) {
			target = candidates.get(0);
		}
		if (target == null) {
			p.sendMessage(Text.translatable("opeope.msg.too_far"), true);
			return;
		}

		// Busca un segundo ser en el ROOM (ya sea marcado previamente o el más cercano al objetivo) para intercambiar almas
		LivingEntity second = null;
		if (pw.soulMark >= 0 && pw.soulMarkTicks > 0) {
			Entity marked = w.getEntityById(pw.soulMark);
			if (marked instanceof LivingEntity le && le.isAlive() && le != target) second = le;
		}
		if (second == null) {
			double bestD = Double.MAX_VALUE;
			for (LivingEntity cand : candidates) {
				if (cand == target) continue;
				double d = cand.squaredDistanceTo(target);
				if (d < bestD) {
					bestD = d;
					second = cand;
				}
			}
		}
		pw.soulMark = -1;

		if (second != null) {
			LivingEntity a = target;
			LivingEntity b = second;
			Text na = a.getDisplayName(), nb = b.getDisplayName();
			float fa = a.getHealth() / a.getMaxHealth(), fb = b.getHealth() / b.getMaxHealth();
			Vec3d posA = a.getPos(), posB = b.getPos();
			a.requestTeleport(posB.x, posB.y, posB.z);
			b.requestTeleport(posA.x, posA.y, posA.z);
			helix(w, posA.add(0, 1.0, 0), posB.add(0, 1.0, 0), 0.55, 3.0, ParticleTypes.SOUL_FIRE_FLAME);
			ring(w, posA.add(0, 1.0, 0), 1.6, 20, ParticleTypes.END_ROD);
			ring(w, posB.add(0, 1.0, 0), 1.6, 20, ParticleTypes.END_ROD);
			sound(p, posA, SoundEvents.BLOCK_CONDUIT_ATTACK_TARGET, 1.0f, 1.7f);
			sound(p, posB, SoundEvents.BLOCK_AMETHYST_BLOCK_CHIME, 1.1f, 1.85f);
			a.setHealth(Math.max(1f, fb * a.getMaxHealth()));
			b.setHealth(Math.max(1f, fa * b.getMaxHealth()));
			if (!(a instanceof ServerPlayerEntity)) {
				a.setCustomName(Text.literal("§d✦ Alma de " + nb.getString() + " §b[Cirugía de Law]"));
				a.setCustomNameVisible(true);
			}
			if (!(b instanceof ServerPlayerEntity)) {
				b.setCustomName(Text.literal("§d✦ Alma de " + na.getString()));
				b.setCustomNameVisible(true);
			}
			if (a instanceof MobEntity ma) ma.setTarget(b);
			if (b instanceof MobEntity mb) mb.setTarget(a);
			beam(p, posA.add(0, 1.0, 0), posB.add(0, 1.0, 0), ParticleTypes.SOUL);
			beam(p, posA.add(0, 1.2, 0), posB.add(0, 1.2, 0), YOUTH_DUST);
			sound(p, posA, SoundEvents.ENTITY_EVOKER_CAST_SPELL, 1.2f, 1.4f);
			sound(p, posB, SoundEvents.ENTITY_WARDEN_HEARTBEAT, 1.5f, 1.2f);
		}

		// Control Quirúrgico: el objetivo pasa a ser tu aliado y guardaespaldas personal
		if (!(target instanceof ServerPlayerEntity)) {
			if (!pw.controlledMobs.contains(target.getUuid())) {
				pw.controlledMobs.add(target.getUuid());
			}
			if (second == null) {
				target.setCustomName(Text.literal("§b✦ [Cirugía de Law] §f" + target.getType().getName().getString()));
				target.setCustomNameVisible(true);
			}
			target.setHealth(target.getMaxHealth());
			target.addStatusEffect(new StatusEffectInstance(StatusEffects.STRENGTH, 20 * 180, 1, false, true));
			target.addStatusEffect(new StatusEffectInstance(StatusEffects.SPEED, 20 * 180, 1, false, true));
			target.addStatusEffect(new StatusEffectInstance(StatusEffects.REGENERATION, 20 * 180, 1, false, true));
			if (target instanceof MobEntity mob && mob.getTarget() == p) {
				mob.setTarget(second);
			}
			Vec3d tc = target.getBoundingBox().getCenter();
			w.spawnParticles(ParticleTypes.HEART, tc.x, tc.y + 0.5, tc.z, 10, 0.4, 0.4, 0.4, 0.02);
			w.spawnParticles(ParticleTypes.SOUL, tc.x, tc.y, tc.z, 20, 0.4, 0.5, 0.4, 0.03);
		}
		p.sendMessage(Text.literal(second != null
			? "§d🧠 Kokoro Shambles: ¡Almas intercambiadas y voluntad quirúrgica implantada!"
			: "§b🧠 Kokoro Shambles: ¡" + target.getType().getName().getString() + " ahora lucha bajo tu Control Quirúrgico!"), true);
		pw.cooldown(Tech.SOULS);
	}

	/** Tick de los seres bajo Control Quirúrgico (Kokoro Shambles) y Gravedad Cero + Vuelo Quirúrgico en el ROOM. */
	static void surgicalDomainTick(ServerPlayerEntity p, PlayerPower pw) {
		ServerWorld w = p.getServerWorld();
		Room room = (pw.room != null && pw.room.dim == w.getRegistryKey()) ? pw.room : null;
		boolean insideOwnRoom = room != null && room.contains(p.getPos());

		// 1. Vuelo Quirúrgico dentro del ROOM: mientras estás dentro de tu ROOM o con Gravedad Cero activa, puedes caminar/volar por el aire
		if (!p.isCreative() && !p.isSpectator()) {
			if (insideOwnRoom || pw.zeroGravityRoom) {
				if (!p.getAbilities().allowFlying) {
					p.getAbilities().allowFlying = true;
					p.sendAbilitiesUpdate();
				}
				pw.surgicalFlightGranted = true;
				p.fallDistance = 0f;
			} else if (pw.surgicalFlightGranted) {
				pw.surgicalFlightGranted = false;
				p.getAbilities().allowFlying = false;
				p.getAbilities().flying = false;
				p.sendAbilitiesUpdate();
				pw.fallImmunityTicks = Math.max(pw.fallImmunityTicks, 120);
			}
		}

		// 2. Gravedad Cero en el ROOM: todos los enemigos, piezas y objetos dentro de la cúpula flotan sin gravedad
		if (pw.zeroGravityRoom && room != null) {
			Box box = new Box(room.center, room.center).expand(room.radius);
			for (Entity e : w.getOtherEntities(p, box, ent -> ent.isAlive() && room.contains(ent.getPos()))) {
				if (pw.takt.contains(e.getId())) continue;
				if (e instanceof PieceEntity pe && pe.weapon != null) continue;
				if (e instanceof dev.opeope.entity.BodyPartEntity bp && bp.returning()) continue;
				e.fallDistance = 0f;
				Vec3d v = e.getVelocity();
				double targetVy = 0.035 + 0.025 * Math.sin((p.age + e.getId() * 7) * 0.12);
				if (e.getY() < room.center.y + 1.5) targetVy = 0.09;
				else if (e.getY() > room.center.y + room.radius * 0.65) targetVy = -0.04;
				e.setVelocity(v.x * 0.88, targetVy, v.z * 0.88);
				e.velocityModified = true;
				if (p.age % 6 == 0) {
					w.spawnParticles(CutEngine.ROOM_DUST, e.getX(), e.getY() + 0.2, e.getZ(), 2, 0.3, 0.2, 0.3, 0);
				}
			}
			if (p.age % 4 == 0) {
				for (int i = 0; i < 6; i++) {
					double a = p.getRandom().nextDouble() * Math.PI * 2;
					double r = p.getRandom().nextDouble() * room.radius * 0.85;
					double px = room.center.x + Math.cos(a) * r;
					double pz = room.center.z + Math.sin(a) * r;
					double py = room.center.y - 1.0 + p.getRandom().nextDouble() * (room.radius * 0.6);
					w.spawnParticles( ParticleTypes.END_ROD, px, py, pz, 1, 0, 0.08, 0, 0.02);
				}
			}
		}

		// 3. Aliados bajo Control Quirúrgico (Kokoro Shambles): te defienden y atacan a los enemigos cercanos
		if (!pw.controlledMobs.isEmpty() && p.age % 10 == 0) {
			var it = pw.controlledMobs.iterator();
			while (it.hasNext()) {
				UUID id = it.next();
				Entity ent = w.getEntity(id);
				if (!(ent instanceof MobEntity ally) || !ally.isAlive()) {
					it.remove();
					continue;
				}
				if (ally.getTarget() == p || (ally.getTarget() != null && pw.controlledMobs.contains(ally.getTarget().getUuid()))) {
					ally.setTarget(null);
				}
				if (ally.getTarget() == null || !ally.getTarget().isAlive()) {
					LivingEntity enemy = null;
					double bestD = 24.0 * 24.0;
					for (LivingEntity cand : w.getEntitiesByClass(LivingEntity.class, p.getBoundingBox().expand(24.0),
						e -> e != p && e != ally && e.isAlive() && !pw.controlledMobs.contains(e.getUuid())
							&& (e instanceof net.minecraft.entity.mob.Monster || (e instanceof MobEntity m && m.getTarget() == p)))) {
						double d = cand.squaredDistanceTo(ally);
						if (d < bestD) {
							bestD = d;
							enemy = cand;
						}
					}
					if (enemy != null) ally.setTarget(enemy);
				}
			}
		}
	}

	/** Activa o desactiva el Dominio de Gravedad Cero en el ROOM + Vuelo Quirúrgico. */
	public static void toggleZeroGravityRoom(ServerPlayerEntity p, PlayerPower pw) {
		ServerWorld w = p.getServerWorld();
		if (pw.room == null || pw.room.dim != w.getRegistryKey()) {
			Room room = new Room(w.getRegistryKey(), p.getPos().add(0, 1.0, 0), 28.0);
			room.state = Room.State.FIXED;
			pw.room = room;
			pw.lastRoomRadius = 28.0;
			PowerManager.broadcastRoom(p, 0, room);
		}
		pw.zeroGravityRoom = !pw.zeroGravityRoom;
		pw.dirty = true;
		if (!pw.zeroGravityRoom && pw.room != null) {
			// Al desactivar la Gravedad Cero, todo lo que flotaba cae de golpe contra el suelo
			Box box = new Box(pw.room.center, pw.room.center).expand(pw.room.radius);
			for (Entity e : w.getOtherEntities(p, box, ent -> ent.isAlive() && pw.room.contains(ent.getPos()))) {
				e.setVelocity(e.getVelocity().x, -1.1, e.getVelocity().z);
				e.velocityModified = true;
			}
			sound(p, p.getPos(), SoundEvents.BLOCK_BEACON_DEACTIVATE, 1.2f, 0.9f);
			p.sendMessage(Text.literal("§7🌌 Gravedad del ROOM restaurada: ¡Impacto descendente!"), true);
		} else {
			sound(p, p.getPos(), SoundEvents.BLOCK_BEACON_ACTIVATE, 1.4f, 1.5f);
			p.sendMessage(Text.literal("§b🌌 Gravedad Cero ROOM ACTIVADA: Todo levita en la cúpula y tienes Vuelo Quirúrgico (Doble Espacio)"), true);
		}
	}

	/** Lluvia (Tab + V): si estás agachado, desata la Cirugía de Juventud Eterna; si no, llueven cortes en el ROOM. */
	public static void rain(ServerPlayerEntity p, PlayerPower pw) {
		if (p.isSneaking()) {
			eternalYouthSurgery(p, pw);
			return;
		}
		if (pw.onCooldown(Tech.RAIN)) return;
		if (pw.room == null) {
			PowerManager.noRoom(p);
			return;
		}
		pw.rainTicks = 60;
		if (!pw.takt.isEmpty() || !pw.rips.isEmpty()) Takt.startOrbitalBarrage(p, pw);
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
		if (p.isSneaking()) {
			toggleZeroGravityRoom(p, pw);
			return;
		}
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

	// ------------------------------------------------------------------ Mes, Trasplante de Corazón y Cirugía de Juventud Eterna

	/** Mes: le sacas el corazón en un cubo. Con un corazón en la mano: devolverlo (mirando al dueño) o soltarlo. */
	public static void mes(ServerPlayerEntity p, PlayerPower pw) {
		ItemStack hand = p.getMainHandStack();
		if (hand.isOf(ModItems.HEART)) {
			if (p.isSneaking()) {
				transplantHeart(p, pw, Hand.MAIN_HAND);
				return;
			}
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
		if (OpeConfig.EXCLUDED_ENTITIES.contains(net.minecraft.entity.EntityType.getId(t.getType()).toString())) {
			p.sendMessage(Text.translatable("opeope.msg.part_refused", t.getDisplayName()), true);
			return;
		}
		Dismember.DEAD_HEARTS.remove(t.getUuid());   // un jugador que murió y volvió tiene corazón nuevo
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
		ring(w, c, 1.2, 18, YOUTH_DUST);
		sound(p, c, SoundEvents.BLOCK_AMETHYST_BLOCK_CHIME, 1.1f, 1.75f);
		sound(p, c, SoundEvents.BLOCK_GLASS_BREAK, 0.7f, 1.8f);
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
		float dmg = OpeConfig.MES_SQUEEZE_DMG;
		if (!OpeConfig.MES_CAN_KILL) dmg = Math.min(dmg, Math.max(0f, le.getHealth() - 1f));   // apretar duele, no mata
		if (dmg > 0) {
			le.timeUntilRegen = 0;   // un apretón no espera a los ticks de invulnerabilidad del último golpe
			le.damage(p.getWorld().getDamageSources().indirectMagic(p, p), dmg);
		}
		le.addStatusEffect(new StatusEffectInstance(ModEffects.STUN, 40, 0, false, true));
		if (le instanceof ServerPlayerEntity victim) victim.sendMessage(Text.translatable("opeope.msg.heart_squeezed"), true);
		p.getServerWorld().spawnParticles(ParticleTypes.DAMAGE_INDICATOR, le.getX(), le.getY() + le.getHeight() / 2, le.getZ(), 4, 0.3, 0.3, 0.3, 0.1);
		sound(p, p.getPos(), SoundEvents.ENTITY_WARDEN_HEARTBEAT, 1.5f, 1.6f);
		sound(p, le.getPos(), SoundEvents.ENTITY_WARDEN_HEARTBEAT, 1.5f, 1.6f);
	}

	/**
	 * Trasplante Quirúrgico de Corazón (Shift + Clic Derecho con un Corazón en la mano):
	 * Te injerta quirúrgicamente la vitalidad del corazón extraído, otorgándote +2 corazones permanentes de vida máxima
	 * (hasta +20 corazones extra), Absorción IV, Fuerza II y Regeneración III.
	 */
	public static void transplantHeart(ServerPlayerEntity p, PlayerPower pw, Hand hand) {
		ItemStack stack = p.getStackInHand(hand);
		if (!stack.isOf(ModItems.HEART)) return;
		stack.decrement(1);
		pw.transplantedHearts = Math.min(10, pw.transplantedHearts + 1);
		EntityAttributeInstance maxHp = p.getAttributeInstance(EntityAttributes.GENERIC_MAX_HEALTH);
		if (maxHp != null) {
			maxHp.setBaseValue(20.0 + pw.transplantedHearts * 4.0);
		}
		p.heal(8.0f);
		p.addStatusEffect(new StatusEffectInstance(StatusEffects.REGENERATION, 20 * 90, 2, false, true, true));
		p.addStatusEffect(new StatusEffectInstance(StatusEffects.ABSORPTION, 20 * 300, 3, false, true, true));
		p.addStatusEffect(new StatusEffectInstance(StatusEffects.STRENGTH, 20 * 240, 1, false, true, true));
		p.addStatusEffect(new StatusEffectInstance(StatusEffects.RESISTANCE, 20 * 240, 1, false, true, true));
		ServerWorld w = p.getServerWorld();
		w.spawnParticles(ParticleTypes.HEART, p.getX(), p.getY() + 1.2, p.getZ(), 18, 0.6, 0.6, 0.6, 0.05);
		w.spawnParticles(YOUTH_DUST, p.getX(), p.getY() + 1.0, p.getZ(), 45, 0.7, 0.9, 0.7, 0.02);
		sound(p, p.getPos(), SoundEvents.ENTITY_WARDEN_HEARTBEAT, 2.0f, 1.0f);
		ring(w, p.getPos().add(0, 1.0, 0), 1.6, 20, YOUTH_DUST);
		sound(p, p.getPos(), SoundEvents.BLOCK_AMETHYST_BLOCK_CHIME, 1.2f, 1.6f);
		sound(p, p.getPos(), SoundEvents.BLOCK_BEACON_POWER_SELECT, 1.4f, 1.6f);
		p.sendMessage(Text.literal("§d❤ Trasplante Quirúrgico completado: +" + (pw.transplantedHearts * 2) + " Corazones Permanentes y Fuerza Quirúrgica"), true);
	}

	/**
	 * Operación de Juventud Eterna (Furo Shujutsu — Tab+V agachado o desde [J]):
	 * La técnica secreta suprema de la Ope Ope no Mi. Despliega el Sello de Juventud Eterna durante 90 segundos:
	 * - Inmortalidad Quirúrgica Activa (ningún golpe puede matarte; si un ataque fuera letal, renaces al 100% con onda expansiva).
	 * - Purga todos los efectos negativos, restaura vida/hambre al máximo y otorga Regeneración IV, Fuerza III y Resistencia III.
	 */
	public static void eternalYouthSurgery(ServerPlayerEntity p, PlayerPower pw) {
		pw.eternalYouthTicks = 20 * 90;
		pw.dirty = true;
		p.setHealth(p.getMaxHealth());
		p.getHungerManager().setFoodLevel(20);
		p.getHungerManager().setSaturationLevel(20f);
		CutStates.reattach(p, false);
		p.removeStatusEffect(StatusEffects.POISON);
		p.removeStatusEffect(StatusEffects.WITHER);
		p.removeStatusEffect(StatusEffects.WEAKNESS);
		p.removeStatusEffect(StatusEffects.SLOWNESS);
		p.removeStatusEffect(StatusEffects.BLINDNESS);
		p.removeStatusEffect(StatusEffects.DARKNESS);
		p.removeStatusEffect(StatusEffects.NAUSEA);
		p.addStatusEffect(new StatusEffectInstance(StatusEffects.REGENERATION, 20 * 90, 3, false, false, true));
		p.addStatusEffect(new StatusEffectInstance(StatusEffects.ABSORPTION, 20 * 90, 4, false, false, true));
		p.addStatusEffect(new StatusEffectInstance(StatusEffects.RESISTANCE, 20 * 90, 2, false, false, true));
		p.addStatusEffect(new StatusEffectInstance(StatusEffects.STRENGTH, 20 * 90, 2, false, false, true));
		p.addStatusEffect(new StatusEffectInstance(StatusEffects.FIRE_RESISTANCE, 20 * 90, 0, false, false, true));

		ServerWorld w = p.getServerWorld();
		Vec3d c = p.getPos().add(0, 1.0, 0);
		w.spawnParticles(ParticleTypes.TOTEM_OF_UNDYING, c.x, c.y, c.z, 80, 1.2, 1.5, 1.2, 0.25);
		w.spawnParticles(YOUTH_DUST, c.x, c.y, c.z, 90, 1.8, 1.2, 1.8, 0.02);
		w.spawnParticles(GOLD_DUST, c.x, c.y, c.z, 90, 1.8, 1.2, 1.8, 0.02);
		sound(p, c, SoundEvents.ITEM_TOTEM_USE, 1.2f, 1.25f);
		ring(w, c, 2.2, 28, GOLD_DUST);
		ring(w, c, 3.4, 36, YOUTH_DUST);
		helix(w, p.getPos(), p.getPos().add(0, 4.5, 0), 1.5, 2.5, GOLD_DUST);
		sound(p, c, SoundEvents.BLOCK_AMETHYST_BLOCK_RESONATE, 1.4f, 1.45f);
		sound(p, c, SoundEvents.BLOCK_AMETHYST_BLOCK_CHIME, 1.2f, 1.8f);
		sound(p, c, SoundEvents.BLOCK_BEACON_ACTIVATE, 1.5f, 1.6f);
		p.sendMessage(Text.literal("§6✦ Operación de Juventud Eterna (Furo Shujutsu): §dInmortalidad Quirúrgica Activa (90s)"), true);
	}

	static void eternalYouthTick(ServerPlayerEntity p, PlayerPower pw) {
		if (pw.eternalYouthTicks <= 0) return;
		if (--pw.eternalYouthTicks == 0) pw.dirty = true;
		if (pw.eternalYouthTicks % 10 == 0) {
			if (p.getHealth() < p.getMaxHealth()) p.heal(1.5f);
			p.extinguish();
			ServerWorld w = p.getServerWorld();
			double a = (p.age * 0.25);
			for (int k = 0; k < 2; k++) {
				double ang = a + k * Math.PI;
				double rx = p.getX() + Math.cos(ang) * 1.1;
				double rz = p.getZ() + Math.sin(ang) * 1.1;
				double ry = p.getY() + 0.9 + 0.4 * Math.sin(a * 1.5 + k);
				w.spawnParticles(k == 0 ? YOUTH_DUST : GOLD_DUST, rx, ry, rz, 1, 0, 0, 0, 0);
			}
		}
	}

	// ------------------------------------------------------------------ Counter Shock y Bisturí

	public static void counterShock(ServerPlayerEntity p, PlayerPower pw) {
		if (pw.onCooldown(Tech.COUNTER)) return;
		ServerWorld w = p.getServerWorld();
		boolean aerial = pw.aerialWindowTicks > 0;
		LivingEntity t = resolveAerialTarget(p, pw, aerial ? 5.5 : 3.5);
		List<LivingEntity> hit = new ArrayList<>();
		if (t != null) hit.add(t);
		else hit.addAll(w.getEntitiesByClass(LivingEntity.class, p.getBoundingBox().expand(aerial ? 4.0 : 2.5), e -> e != p && e.isAlive()));
		float aerialMult = consumeAerialWindow(p, pw);
		for (LivingEntity le : hit) {
			float wetMult = (aerial && (le.isWet() || le.isTouchingWaterOrRain())) ? 1.35f : 1.0f;
			le.damage(w.getDamageSources().playerAttack(p), (t != null ? dmg(30) : 3f) * aerialMult * wetMult);
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
		ring(w, p.getPos().add(0, 1.0, 0), 2.8, 28, ParticleTypes.ELECTRIC_SPARK);
		ring(w, p.getPos().add(0, 1.0, 0), 2.2, 22, CutEngine.ROOM_DUST);
		sound(p, p.getPos(), SoundEvents.BLOCK_CONDUIT_ATTACK_TARGET, 1.2f, 1.85f);
		sound(p, p.getPos(), SoundEvents.ENTITY_LIGHTNING_BOLT_IMPACT, 1f, 1.6f);

		List<Vec3d> origins = new ArrayList<>();
		if (t != null) {
			origins.add(t.getBoundingBox().getCenter());
		} else {
			origins.add(p.getPos().add(0, 1.0, 0));
			for (LivingEntity le : hit) origins.add(le.getBoundingBox().getCenter());
		}
		if (hasConductiveInHandOrNear(p, pw)) origins.add(p.getPos().add(0, 1.0, 0));
		Set<LivingEntity> already = new HashSet<>(hit);
		Conduction.chain(p, pw, origins, already, dmg(30) * 0.6f * aerialMult, 60, false);

		pw.cooldown(Tech.COUNTER);
	}

	private static boolean hasConductiveInHandOrNear(ServerPlayerEntity p, PlayerPower pw) {
		for (int id : pw.weapons) {
			if (p.getWorld().getEntityById(id) instanceof PieceEntity pe && pe.trait() == dev.opeope.terrain.MaterialTrait.CONDUCTIVE) return true;
		}
		for (int id : pw.takt) {
			if (p.getWorld().getEntityById(id) instanceof PieceEntity pe && pe.trait() == dev.opeope.terrain.MaterialTrait.CONDUCTIVE) return true;
		}
		return !p.getWorld().getEntitiesByClass(PieceEntity.class, p.getBoundingBox().expand(2.5),
			pe -> pe.isAlive() && pe.trait() == dev.opeope.terrain.MaterialTrait.CONDUCTIVE).isEmpty();
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
			for (int id : pw.bisturiHeld) {
				if (p.getServerWorld().getEntityById(id) instanceof PieceEntity pe && !pe.isRemoved()) pe.hold(6);
			}
			pw.bisturiHeld.clear();
			ServerWorld sw = p.getServerWorld();
			int released = 0;
			for (int id : pw.bisturiHeldParts) {
				if (sw.getEntityById(id) instanceof dev.opeope.entity.BodyPartEntity bp && !bp.isRemoved()) {
					bp.releaseFrozen();
					Vec3d bc = bp.getBoundingBox().getCenter();
					sw.spawnParticles(ParticleTypes.END_ROD, bc.x, bc.y, bc.z, 8, 0.15, 0.15, 0.15, 0.04);
					released++;
				}
			}
			pw.bisturiHeldParts.clear();
			if (released > 0) {
				sound(p, p.getPos(), SoundEvents.BLOCK_AMETHYST_BLOCK_CHIME, 1.1f, 1.9f);
				sound(p, p.getPos(), SoundEvents.ENTITY_PLAYER_ATTACK_SWEEP, 0.9f, 1.8f);
			}
		}
		pw.dirty = true;
	}

	// ------------------------------------------------------------------ Despertar: K-ROOM Espada Gigante 3D, Shock Wille y Puncture Wille

	/**
	 * K-ROOM: recubre tu espada Kikoku con un ROOM quirúrgico concentrado y extiende su hoja físicamente 26 bloques en 3D.
	 * Todo lo que atraviesa la hoja colosal queda seccionado internamente y preparado para Shock Wille o Puncture Wille.
	 */
	public static void kroom(ServerPlayerEntity p, PlayerPower pw) {
		if (pw.onCooldown(Tech.KROOM)) return;
		equipKikokuSafely(p);
		pw.kroomBladeTicks = 20 * 14; // 14 segundos de Espada Gigante 3D K-ROOM activa
		pw.dirty = true;

		ServerWorld w = p.getServerWorld();
		Vec3d eye = p.getEyePos();
		Vec3d look = p.getRotationVector();
		Vec3d tip = eye.add(look.multiply(26.0));
		beam(p, eye.add(look.multiply(1.2)), tip, KROOM_DUST);
		helix(w, eye.add(look.multiply(1.2)), tip, 0.45, 5.0, ParticleTypes.ELECTRIC_SPARK);
		ring(w, p.getPos().add(0, 0.8, 0), 2.4, 24, KROOM_DUST);
		sound(p, p.getPos(), SoundEvents.BLOCK_CONDUIT_ATTACK_TARGET, 1.2f, 1.55f);
		sound(p, p.getPos(), SoundEvents.ITEM_TRIDENT_RIPTIDE_3.value(), 1.1f, 1.35f);

		// Corta en piezas y golpea a todos los seres vivos a lo largo de los 26 metros de la hoja K-ROOM
		Box sweepBox = p.getBoundingBox().stretch(look.multiply(26.0)).expand(2.0);
		for (LivingEntity t : w.getEntitiesByClass(LivingEntity.class, sweepBox, e -> e != p && e.isAlive())) {
			Vec3d c = t.getBoundingBox().getCenter();
			Vec3d to = c.subtract(eye);
			double proj = to.dotProduct(look);
			if (proj < 0 || proj > 27.0) continue;
			Vec3d closest = eye.add(look.multiply(proj));
			if (closest.squaredDistanceTo(c) > 6.25) continue;

			List<CutEngine.Plane> planes = List.of(
				new CutEngine.Plane(new Vec3d(0, 1, 0), c),
				new CutEngine.Plane(new Vec3d(1, 0.4, 0).normalize(), c),
				new CutEngine.Plane(new Vec3d(0, 0.4, 1).normalize(), c));
			CutStates.cutLiving(t, planes, OpeConfig.CUT_LIVING_TICKS, 0);
			t.damage(w.getDamageSources().playerAttack(p), dmg(55));
			t.addStatusEffect(new StatusEffectInstance(ModEffects.STUN, 50, 0, false, true));
			w.spawnParticles(CutEngine.ROOM_DUST, c.x, c.y, c.z, 50, 0.9, 0.9, 0.9, 0);
			w.spawnParticles(ParticleTypes.SWEEP_ATTACK, c.x, c.y, c.z, 4, 0.5, 0.5, 0.5, 0);
		}
		sound(p, p.getPos(), SoundEvents.BLOCK_BEACON_ACTIVATE, 1.3f, 1.9f);
		sound(p, p.getPos(), SoundEvents.ENTITY_PLAYER_ATTACK_SWEEP, 1.3f, 0.6f);
		p.sendMessage(Text.literal("§b✦ K-ROOM: ¡Kikoku Gigante de 26m revestida! (Usa [C] Shock Wille o [Tab+5] Puncture Wille)"), true);
		pw.cooldown(Tech.KROOM);
	}

	/** Mantiene activa la hoja colosal de 26 metros de K-ROOM, electrificando y paralizando lo que atraviesa. */
	static void kroomBladeTick(ServerPlayerEntity p, PlayerPower pw) {
		if (pw.kroomBladeTicks <= 0) return;
		if (--pw.kroomBladeTicks == 0) {
			pw.dirty = true;
			return;
		}
		if (pw.kroomBladeTicks % 4 != 0) return;
		ServerWorld w = p.getServerWorld();
		Vec3d eye = p.getEyePos();
		Vec3d look = p.getRotationVector();
		for (int d = 3; d <= 25; d += 4) {
			Vec3d pt = eye.add(look.multiply(d));
			w.spawnParticles(KROOM_DUST, pt.x, pt.y, pt.z, 1, 0.08, 0.08, 0.08, 0);
		}
	}

	/** Anesthesia (mantener): la hoja K-ROOM de 26m atraviesa al objetivo sin resistencia y lo anestesia desde dentro. */
	public static void anesthesia(ServerPlayerEntity p, PlayerPower pw, boolean start) {
		if (!start) {
			pw.anesthesia = false;
			return;
		}
		if (pw.onCooldown(Tech.ANESTHESIA)) return;
		equipKikokuSafely(p);
		pw.kroomBladeTicks = Math.max(pw.kroomBladeTicks, 20 * 10);
		pw.dirty = true;
		LivingEntity t = livingInSight(p, 26);
		if (t == null) {
			p.sendMessage(Text.literal("§bK-ROOM Anesthesia: Hoja quirúrgica de 26m extendida"), true);
			pw.cooldown(Tech.ANESTHESIA);
			return;
		}
		pw.anesthesia = true;
		pw.anesthesiaTarget = t.getId();
		pw.anesthesiaTicks = 0;
		sound(p, t.getPos(), SoundEvents.ENTITY_ARROW_HIT_PLAYER, 1f, 0.5f);
		p.sendMessage(Text.literal("§bK-ROOM Anesthesia: ¡Objetivo atravesado a " + (int) p.distanceTo(t) + "m!"), true);
		pw.cooldown(Tech.ANESTHESIA);
	}

	static void anesthesiaTick(ServerPlayerEntity p, PlayerPower pw) {
		if (!pw.anesthesia) return;
		Entity e = p.getWorld().getEntityById(pw.anesthesiaTarget);
		if (!(e instanceof LivingEntity le) || !le.isAlive() || le.squaredDistanceTo(p) > 28 * 28 || ++pw.anesthesiaTicks > 140) {
			pw.anesthesia = false;
			return;
		}
		Vec3d hand = p.getEyePos().add(p.getRotationVector().multiply(0.6)).subtract(0, 0.3, 0);
		if (pw.anesthesiaTicks % 2 == 0) beam(p, hand, le.getBoundingBox().getCenter(), CutEngine.ROOM_DUST);
		if (pw.anesthesiaTicks % 8 == 0) {
			le.damage(p.getWorld().getDamageSources().indirectMagic(p, p), 2.5f);
			le.addStatusEffect(new StatusEffectInstance(ModEffects.STUN, 25, 0, false, true));
		}
	}

	/** Shock Wille: descarga interna devastadora a lo largo de la hoja K-ROOM que estalla dentro de los órganos del enemigo. */
	public static void shockWille(ServerPlayerEntity p, PlayerPower pw) {
		if (pw.onCooldown(Tech.SHOCK_WILLE)) return;
		equipKikokuSafely(p);
		pw.kroomBladeTicks = Math.max(pw.kroomBladeTicks, 20 * 10);
		pw.dirty = true;

		ServerWorld w = p.getServerWorld();
		Vec3d eye = p.getEyePos();
		Vec3d look = p.getRotationVector();
		LivingEntity mainTarget = livingInSight(p, 60);
		Vec3d endPoint = mainTarget != null ? mainTarget.getBoundingBox().getCenter() : eye.add(look.multiply(26.0));

		beam(p, eye.add(look), endPoint, ParticleTypes.ELECTRIC_SPARK);
		beam(p, eye.add(look), endPoint, GAMMA_DUST);
		helix(w, eye.add(look), endPoint, 0.55, 4.5, ParticleTypes.ELECTRIC_SPARK);
		ring(w, endPoint, 3.8, 34, GAMMA_DUST);
		ring(w, endPoint, 5.2, 40, ParticleTypes.ELECTRIC_SPARK);
		sound(p, endPoint, SoundEvents.BLOCK_CONDUIT_ATTACK_TARGET, 1.4f, 1.6f);
		sound(p, endPoint, SoundEvents.ITEM_TRIDENT_RIPTIDE_3.value(), 1.2f, 0.8f);

		Box lineBox = new Box(eye, endPoint).expand(3.2);
		List<LivingEntity> victims = new ArrayList<>();
		if (mainTarget != null) victims.add(mainTarget);
		for (LivingEntity le : w.getEntitiesByClass(LivingEntity.class, lineBox, e -> e != p && e.isAlive() && !victims.contains(e))) {
			victims.add(le);
		}

		for (LivingEntity t : victims) {
			t.damage(ModDamage.of(p.getWorld(), ModDamage.GAMMA, p), dmg(200));
			t.addStatusEffect(new StatusEffectInstance(ModEffects.STUN, 60, 0, false, true));
			Vec3d push = look.multiply(2.6).add(0, 0.75, 0);
			t.addVelocity(push.x, push.y, push.z);
			t.velocityModified = true;
			Vec3d c = t.getBoundingBox().getCenter();
			w.spawnParticles(ParticleTypes.FLASH, c.x, c.y, c.z, 2, 0, 0, 0, 0);
			w.spawnParticles(ParticleTypes.ELECTRIC_SPARK, c.x, c.y, c.z, 150, 1.1, 1.3, 1.1, 0.9);
			w.spawnParticles(GAMMA_DUST, c.x, c.y, c.z, 80, 0.9, 1.1, 0.9, 0.05);
			sound(p, c, SoundEvents.ENTITY_LIGHTNING_BOLT_THUNDER, 1.6f, 1.3f);
			sound(p, c, SoundEvents.ENTITY_WARDEN_SONIC_BOOM, 1.2f, 1.5f);
		}
		List<Vec3d> origins = new ArrayList<>();
		for (LivingEntity t : victims) origins.add(t.getBoundingBox().getCenter());
		Vec3d rayVec = endPoint.subtract(eye);
		double rayLen = rayVec.length();
		int samples = Math.max(2, (int) Math.ceil(rayLen / 2.0));
		for (int i = 1; i <= samples; i++) {
			origins.add(eye.add(rayVec.multiply(i / (double) samples)));
		}
		Conduction.chainAlongRay(p, pw, origins, new HashSet<>(victims), dmg(200) * 0.35f, 60, true, eye, endPoint);

		p.sendMessage(Text.literal("§b⚡ K-ROOM: ¡SHOCK WILLE!"), true);
		pw.cooldown(Tech.SHOCK_WILLE);
	}

	/**
	 * Puncture Wille: la técnica más destructiva del Despertar de Trafalgar Law.
	 * Extiende la Kikoku gigante con K-ROOM hasta el objetivo (hasta 45 bloques), perfora profundamente el terreno
	 * abriendo un cráter abismal real de 9 bloques de radio y 16 bloques de profundidad con columna de luz quirúrgica.
	 */
	public static void puncture(ServerPlayerEntity p, PlayerPower pw) {
		if (pw.onCooldown(Tech.PUNCTURE)) return;
		equipKikokuSafely(p);
		pw.kroomBladeTicks = Math.max(pw.kroomBladeTicks, 20 * 12);
		pw.dirty = true;

		ServerWorld w = p.getServerWorld();
		Vec3d c = PowerManager.aimPoint(p, 45.0);
		beam(p, p.getEyePos().add(p.getRotationVector()), c, KROOM_DUST);
		beam(p, p.getEyePos().add(p.getRotationVector()), c, ParticleTypes.ELECTRIC_SPARK);

		double R = 9.0;
		int maxDepth = 16;
		BlockPos cb = BlockPos.ofFloored(c);
		int ri = (int) Math.ceil(R);

		// Perfora un cráter abismal cónico/cilíndrico profundo como en Onigashima/Wano
		for (int dy = 2; dy >= -maxDepth; dy--) {
			double depthRatio = Math.max(0.0, (-dy) / (double) maxDepth);
			double layerR = R * (1.0 - 0.42 * depthRatio);
			double layerR2 = layerR * layerR;
			for (int dx = -ri; dx <= ri; dx++) {
				for (int dz = -ri; dz <= ri; dz++) {
					double d2 = (dx + 0.5) * (dx + 0.5) + (dz + 0.5) * (dz + 0.5);
					if (d2 > layerR2) continue;
					BlockPos bp = cb.add(dx, dy, dz);
					BlockState s = w.getBlockState(bp);
					if (s.isAir() || s.hasBlockEntity() || s.getHardness(w, bp) < 0) continue;
					if (dy >= -2 && p.getRandom().nextInt(7) == 0) {
						FallingBlockEntity fb = FallingBlockEntity.spawnFromBlock(w, bp.toImmutable(), s);
						Vec3d out = new Vec3d(dx, 2.2, dz).normalize().multiply(0.85);
						fb.setVelocity(out);
						fb.velocityModified = true;
					} else {
						w.setBlockState(bp, Blocks.AIR.getDefaultState(), 3);
					}
				}
			}
		}

		// Columna vertical de luz quirúrgica y relámpagos desde el fondo del abismo
		for (int h = -maxDepth; h <= 14; h++) {
			w.spawnParticles(KROOM_DUST, c.x, c.y + h, c.z, 12, 1.2, 0.5, 1.2, 0.05);
			w.spawnParticles(ParticleTypes.ELECTRIC_SPARK, c.x, c.y + h, c.z, 10, 0.9, 0.5, 0.9, 0.4);
		}
		w.spawnParticles(ParticleTypes.EXPLOSION_EMITTER, c.x, c.y, c.z, 3, 2.0, 1.0, 2.0, 0);
		w.spawnParticles(CutEngine.ROOM_DUST, c.x, c.y, c.z, 260, 5.5, 2.0, 5.5, 0.05);

		// Onda de choque masiva contra todos los enemigos en el radio del cráter
		for (LivingEntity le : w.getEntitiesByClass(LivingEntity.class, new Box(c, c).expand(12.0), e -> e != p && e.isAlive())) {
			le.damage(ModDamage.of(w, ModDamage.GAMMA, p), dmg(220));
			le.addStatusEffect(new StatusEffectInstance(ModEffects.STUN, 80, 0, false, true));
			Vec3d in = c.subtract(le.getPos()).normalize().multiply(0.8).add(0, -1.2, 0);
			le.addVelocity(in.x, in.y, in.z);
			le.velocityModified = true;
		}

		sound(p, c, SoundEvents.ENTITY_WARDEN_SONIC_BOOM, 2.0f, 0.55f);
		sound(p, c, SoundEvents.ENTITY_DRAGON_FIREBALL_EXPLODE, 2.0f, 0.55f);
		helix(w, c.add(0, -maxDepth, 0), c.add(0, 14, 0), 1.9, 5.0, ParticleTypes.END_ROD);
		ring(w, c.add(0, 0.5, 0), 9.0, 56, KROOM_DUST);
		ring(w, c.add(0, 1.5, 0), 11.5, 64, ParticleTypes.ELECTRIC_SPARK);
		sound(p, c, SoundEvents.BLOCK_CONDUIT_ATTACK_TARGET, 1.8f, 0.7f);
		sound(p, c, SoundEvents.ENTITY_LIGHTNING_BOLT_THUNDER, 2.0f, 0.8f);
		p.sendMessage(Text.literal("§b⚡ K-ROOM: ¡PUNCTURE WILLE! (Cráter Abismal)"), true);
		pw.cooldown(Tech.PUNCTURE);
	}

	/** Silent: nadie cerca te oye ni te sigue un rato. */
	public static void silent(ServerPlayerEntity p, PlayerPower pw) {
		if (pw.onCooldown(Tech.SILENT)) return;
		pw.silentTicks = OpeConfig.SILENT_TICKS;
		p.addStatusEffect(new StatusEffectInstance(StatusEffects.INVISIBILITY, OpeConfig.SILENT_TICKS, 0, false, false, true));
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
