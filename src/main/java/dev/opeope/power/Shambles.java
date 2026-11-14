package dev.opeope.power;

import dev.opeope.OpeConfig;
import dev.opeope.entity.MarkerStoneEntity;
import dev.opeope.entity.PieceEntity;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.TntEntity;
import net.minecraft.entity.mob.HostileEntity;
import net.minecraft.entity.projectile.ArrowEntity;
import net.minecraft.entity.projectile.ProjectileEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;

import java.util.Comparator;
import java.util.List;

/**
 * Shambles: cambiar de sitio lo que quieras dentro del ROOM, tú incluido, además de proyectiles, flechas y TNT en vuelo.
 */
public final class Shambles {
	private Shambles() {}

	public static void use(ServerPlayerEntity p, PlayerPower pw, boolean sneaking) {
		if (pw.onCooldown(Tech.SHAMBLES)) return;
		if (pw.room == null && pw.rroom == null) {
			PowerManager.noRoom(p);
			return;
		}
		if (sneaking) {
			blocks(p, pw);
			return;
		}

		// Si hay proyectiles o TNT en vuelo dentro del ROOM y no tienes marca previa, redirígelos todos al objetivo en la mira
		if (pw.shamblesMark < 0 && redirectAirborneProjectiles(p, pw)) {
			pw.cooldown(Tech.SHAMBLES);
			return;
		}

		Entity target = PowerManager.raycastEntityLoose(p, 220, 2.0, e -> e != p && !e.isSpectator()
			&& !(e instanceof PieceEntity pe && pe.weapon != null));
		if (target != null && !PowerManager.inRoom(p, target.getBoundingBox().getCenter())) target = null;

		// Con Scan, Shambles roba lo que lleva en la mano.
		if (target instanceof LivingEntity le && pw.scanTicks > 0 && !le.getMainHandStack().isEmpty()) {
			ItemStack stolen = le.getMainHandStack().copy();
			le.setStackInHand(Hand.MAIN_HAND, ItemStack.EMPTY);
			p.getInventory().offerOrDrop(stolen);
			fx(p, le.getPos(), p.getPos());
			p.sendMessage(Text.translatable("opeope.msg.stolen", stolen.getName()), true);
			pw.cooldown(Tech.SHAMBLES);
			return;
		}

		Entity marked = pw.shamblesMark >= 0 ? p.getWorld().getEntityById(pw.shamblesMark) : null;
		if (marked != null && (marked.isRemoved() || pw.shamblesMarkTicks <= 0)) marked = null;

		if (marked == null) {
			if (target instanceof MarkerStoneEntity) {
				swap(p, p, target);
			} else if (target != null) {
				pw.shamblesMark = target.getId();
				pw.shamblesMarkTicks = OpeConfig.SHAMBLES_MARK_TICKS;
				target.setGlowing(true);
				p.sendMessage(Text.translatable("opeope.msg.marked", target.getDisplayName()), true);
				p.getWorld().playSound(null, target.getX(), target.getY(), target.getZ(), SoundEvents.BLOCK_AMETHYST_BLOCK_CHIME, SoundCategory.PLAYERS, 1f, 1.8f);
				return;
			} else {
				Vec3d dest = emptyPoint(p);
				if (dest == null) {
					PowerManager.noRoom(p);
					return;
				}
				Vec3d from = p.getPos();
				teleport(p, dest);
				fx(p, from, dest);
			}
		} else {
			marked.setGlowing(false);
			pw.shamblesMark = -1;
			if (target == marked) {
				swap(p, p, marked);
			} else if (target != null) {
				swap(p, marked, target);
			} else {
				Vec3d dest = emptyPoint(p);
				if (dest == null) {
					PowerManager.noRoom(p);
					return;
				}
				Vec3d from = marked.getPos();
				teleport(marked, dest);
				fx(p, from, dest);
			}
		}
		pw.cooldown(Tech.SHAMBLES);
	}

	/**
	 * Shambles Reactivo de Proyectiles y TNT (cada tick dentro del ROOM):
	 * Cualquier flecha, tridente, bola de fuego, proyectil o TNT encendido hostil que vuele hacia ti dentro del ROOM
	 * es interceptado con Shambles y teletransportado de vuelta contra el tirador o enemigo más cercano.
	 */
	public static void tickProjectiles(ServerPlayerEntity p, PlayerPower pw) {
		if (!pw.projectileShambles) return;
		Room room = pw.room != null ? pw.room : pw.rroom;
		if (room == null || room.dim != p.getWorld().getRegistryKey()) return;
		ServerWorld w = p.getServerWorld();
		Box roomBox = new Box(room.center, room.center).expand(Math.min(room.radius, 64.0));

		// 1) Interceptar proyectiles hostiles en vuelo
		List<ProjectileEntity> projectiles = w.getEntitiesByClass(ProjectileEntity.class, roomBox,
			proj -> proj.isAlive() && !proj.isOnGround() && !(proj instanceof MarkerStoneEntity)
				&& !proj.getCommandTags().contains("opeope_redirected")
				&& room.contains(proj.getPos()));

		for (ProjectileEntity proj : projectiles) {
			Entity owner = proj.getOwner();
			if (owner == p) continue;
			// Si fue disparado por un enemigo o se acerca al jugador
			boolean headingToPlayer = proj.squaredDistanceTo(p) < 49.0
				|| proj.getVelocity().dotProduct(p.getEyePos().subtract(proj.getPos()).normalize()) > 0.25;
			if (!headingToPlayer && owner == null) continue;

			LivingEntity victim = null;
			if (owner instanceof LivingEntity le && le != p && le.isAlive() && room.contains(le.getPos())) {
				victim = le;
			} else {
				victim = w.getEntitiesByClass(LivingEntity.class, roomBox,
						e -> e != p && e.isAlive() && room.contains(e.getPos()))
					.stream()
					.min(Comparator.comparingDouble(e -> e.squaredDistanceTo(p)))
					.orElse(null);
			}

			Vec3d oldPos = proj.getPos();
			if (victim != null) {
				Vec3d aim = victim.getBoundingBox().getCenter();
				Vec3d dir = aim.subtract(p.getEyePos());
				if (dir.lengthSquared() < 1e-4) dir = p.getRotationVector();
				else dir = dir.normalize();
				Vec3d newPos = aim.subtract(dir.multiply(2.2));
				proj.refreshPositionAndAngles(newPos.x, newPos.y, newPos.z, proj.getYaw(), proj.getPitch());
				double speed = Math.max(1.8, proj.getVelocity().length() * 1.6);
				proj.setVelocity(dir.multiply(speed));
				proj.velocityModified = true;
				proj.setOwner(p);
				proj.addCommandTag("opeope_redirected");
				fx(p, oldPos, newPos);
				p.sendMessage(Text.literal("§bShambles: ¡Proyectil devuelto a " + victim.getDisplayName().getString() + "!"), true);
			} else {
				// Si no hay enemigo, lo desvía lejos detrás de ti
				Vec3d look = p.getRotationVector();
				Vec3d newPos = p.getEyePos().add(look.multiply(3.5));
				proj.refreshPositionAndAngles(newPos.x, newPos.y, newPos.z, p.getYaw(), p.getPitch());
				proj.setVelocity(look.multiply(2.2));
				proj.velocityModified = true;
				proj.setOwner(p);
				proj.addCommandTag("opeope_redirected");
				fx(p, oldPos, newPos);
				p.sendMessage(Text.literal("§bShambles: ¡Proyectil redirigido!"), true);
			}
		}

		// 2) Interceptar TNT encendido cercano al jugador y enviarlo sobre el enemigo más cercano
		List<TntEntity> tnts = w.getEntitiesByClass(TntEntity.class, p.getBoundingBox().expand(8.0),
			tnt -> tnt.isAlive() && !tnt.getCommandTags().contains("opeope_redirected") && room.contains(tnt.getPos()));
		for (TntEntity tnt : tnts) {
			LivingEntity victim = w.getEntitiesByClass(LivingEntity.class, roomBox,
					e -> e != p && e.isAlive() && (e instanceof HostileEntity || e.squaredDistanceTo(p) > 36.0) && room.contains(e.getPos()))
				.stream()
				.min(Comparator.comparingDouble(e -> e.squaredDistanceTo(p)))
				.orElse(null);
			if (victim != null) {
				Vec3d oldPos = tnt.getPos();
				Vec3d newPos = victim.getPos().add(0, victim.getHeight() + 0.5, 0);
				tnt.refreshPositionAndAngles(newPos.x, newPos.y, newPos.z, 0, 0);
				tnt.setVelocity(0, -0.2, 0);
				tnt.velocityModified = true;
				tnt.addCommandTag("opeope_redirected");
				fx(p, oldPos, newPos);
				p.sendMessage(Text.literal("§bShambles: ¡Explosivo enviado a " + victim.getDisplayName().getString() + "!"), true);
			}
		}
	}

	/** Redirige todos los proyectiles en vuelo dentro del ROOM hacia el punto o enemigo al que apuntas al pulsar Shambles. */
	public static boolean redirectAirborneProjectiles(ServerPlayerEntity p, PlayerPower pw) {
		Room room = pw.room != null ? pw.room : pw.rroom;
		if (room == null || room.dim != p.getWorld().getRegistryKey()) return false;
		ServerWorld w = p.getServerWorld();
		Box roomBox = new Box(room.center, room.center).expand(Math.min(room.radius, 64.0));
		List<ProjectileEntity> airborne = w.getEntitiesByClass(ProjectileEntity.class, roomBox,
			proj -> proj.isAlive() && !proj.isOnGround() && !(proj instanceof MarkerStoneEntity) && room.contains(proj.getPos()));
		if (airborne.isEmpty()) return false;

		Entity aimed = PowerManager.raycastEntityLoose(p, 160, 4.0, e -> e != p && e instanceof LivingEntity le && le.isAlive());
		Vec3d targetPos = aimed != null ? aimed.getBoundingBox().getCenter() : PowerManager.aimPoint(p, 80);

		int count = 0;
		for (ProjectileEntity proj : airborne) {
			Vec3d oldPos = proj.getPos();
			double angle = (count * Math.PI * 2.0) / Math.max(1, airborne.size());
			Vec3d offset = new Vec3d(Math.cos(angle) * 2.2, 0.8 + (count % 3) * 0.4, Math.sin(angle) * 2.2);
			Vec3d newPos = targetPos.add(offset);
			Vec3d dir = targetPos.subtract(newPos).normalize();
			proj.refreshPositionAndAngles(newPos.x, newPos.y, newPos.z, proj.getYaw(), proj.getPitch());
			proj.setVelocity(dir.multiply(2.6));
			proj.velocityModified = true;
			proj.setOwner(p);
			proj.addCommandTag("opeope_redirected");
			fx(p, oldPos, newPos);
			count++;
		}
		p.sendMessage(Text.literal("§bShambles: ¡" + count + " proyectiles teletransportados al objetivo!"), true);
		return count > 0;
	}

	/** Desata una lluvia táctica de proyectiles quirúrgicos teletransportados con Shambles alrededor del objetivo en la mira. */
	public static void projectileBarrage(ServerPlayerEntity p, PlayerPower pw) {
		Room room = pw.room != null ? pw.room : pw.rroom;
		if (room == null) {
			PowerManager.noRoom(p);
			return;
		}
		ServerWorld w = p.getServerWorld();
		Entity aimed = PowerManager.raycastEntityLoose(p, 120, 5.0, e -> e != p && e instanceof LivingEntity le && le.isAlive());
		Vec3d targetPos = aimed != null ? aimed.getBoundingBox().getCenter() : PowerManager.aimPoint(p, 50);
		int count = 10;
		for (int i = 0; i < count; i++) {
			double a = (Math.PI * 2.0 * i) / count;
			Vec3d spawnPos = targetPos.add(Math.cos(a) * 4.2, 2.2 + (i % 2) * 0.8, Math.sin(a) * 4.2);
			Vec3d dir = targetPos.subtract(spawnPos).normalize();
			ArrowEntity arrow = new ArrowEntity(w, spawnPos.x, spawnPos.y, spawnPos.z, new ItemStack( net.minecraft.item.Items.ARROW ), null);
			arrow.setOwner(p);
			arrow.setCritical(true);
			arrow.setDamage(6.0);
			arrow.setVelocity(dir.x, dir.y, dir.z, 2.8f, 0.0f);
			arrow.addCommandTag("opeope_redirected");
			w.spawnEntity(arrow);
			w.spawnParticles(ParticleTypes.REVERSE_PORTAL, spawnPos.x, spawnPos.y, spawnPos.z, 10, 0.2, 0.2, 0.2, 0.02);
			w.spawnParticles(CutEngine.ROOM_DUST, spawnPos.x, spawnPos.y, spawnPos.z, 8, 0.2, 0.2, 0.2, 0);
		}
		w.playSound(null, targetPos.x, targetPos.y, targetPos.z, SoundEvents.ENTITY_ENDERMAN_TELEPORT, SoundCategory.PLAYERS, 1.2f, 1.7f);
		w.playSound(null, targetPos.x, targetPos.y, targetPos.z, SoundEvents.BLOCK_AMETHYST_BLOCK_HIT, SoundCategory.PLAYERS, 1.2f, 1.9f);
		p.sendMessage(Text.literal("§bShambles: ¡Ráfaga de Proyectiles Teletransportados!"), true);
	}

	/** El punto vacío del ROOM al que apuntas: encima de lo que miras, o el aire antes del borde. */
	public static Vec3d emptyPoint(ServerPlayerEntity p) {
		BlockHitResult hit = PowerManager.raycastBlock(p, 220);
		Vec3d eye = p.getEyePos();
		Vec3d look = p.getRotationVector();
		Vec3d dest;
		if (hit.getType() == HitResult.Type.BLOCK) {
			Direction side = hit.getSide();
			if (side == Direction.UP) {
				dest = new Vec3d(hit.getPos().x, hit.getPos().y + 0.01, hit.getPos().z);
			} else if (side == Direction.DOWN) {
				dest = hit.getPos().subtract(0, p.getHeight() + 0.1, 0);
			} else {
				dest = hit.getPos().add(Vec3d.of(side.getVector()).multiply(0.45)).subtract(0, p.getHeight() / 2, 0);
			}
		} else {
			Room room = PowerManager.get(p).room;
			if (room == null) return null;
			// Donde el rayo sale del ROOM, un poco antes.
			Vec3d oc = eye.subtract(room.center);
			double b = oc.dotProduct(look);
			double c = oc.lengthSquared() - room.radius * room.radius;
			double disc = b * b - c;
			if (disc < 0) return null;
			double t = -b + Math.sqrt(disc) - 1.5;
			if (t <= 1) return null;
			dest = eye.add(look.multiply(Math.min(t, 220)));
		}
		if (!PowerManager.inRoom(p, dest.add(0, 0.5, 0))) return null;
		return dest;
	}

	public static void teleport(Entity e, Vec3d pos) {
		if (e instanceof PieceEntity pe) {
			pe.setPosition(pos);
			if (pe.mode == PieceEntity.Mode.CONTROLLED) pe.target = pe.center();
		} else {
			e.requestTeleport(pos.x, pos.y, pos.z);
		}
		e.setVelocity(Vec3d.ZERO);
		e.fallDistance = 0;
		e.velocityModified = true;
		if (e instanceof ServerPlayerEntity sp) {
			PlayerPower pw = PowerManager.get(sp);
			pw.fallImmunityTicks = 600;
		}
	}

	public static void swap(ServerPlayerEntity p, Entity a, Entity b) {
		Vec3d pa = a.getPos(), pb = b.getPos();
		teleport(a, pb);
		teleport(b, pa);
		fx(p, pa, pb);
	}

	public static void fx(ServerPlayerEntity p, Vec3d a, Vec3d b) {
		ServerWorld w = p.getServerWorld();
		for (Vec3d v : new Vec3d[]{a, b}) {
			w.spawnParticles(ParticleTypes.REVERSE_PORTAL, v.x, v.y + 1, v.z, 26, 0.35, 0.7, 0.35, 0.06);
			w.spawnParticles(CutEngine.ROOM_DUST, v.x, v.y + 1, v.z, 20, 0.45, 0.75, 0.45, 0);
			w.spawnParticles(ParticleTypes.END_ROD, v.x, v.y + 1.1, v.z, 8, 0.25, 0.5, 0.25, 0.05);
			for (int i = 0; i < 16; i++) {
				double ang = Math.PI * 2 * i / 16.0;
				w.spawnParticles(CutEngine.ROOM_DUST, v.x + Math.cos(ang) * 0.9, v.y + 0.15, v.z + Math.sin(ang) * 0.9, 1, 0, 0, 0, 0);
			}
			w.playSound(null, v.x, v.y, v.z, SoundEvents.ENTITY_BREEZE_WIND_BURST.value(), SoundCategory.PLAYERS, 0.55f, 1.75f);
			w.playSound(null, v.x, v.y, v.z, SoundEvents.ENTITY_ENDERMAN_TELEPORT, SoundCategory.PLAYERS, 0.32f, 1.85f);
		}
		// Hélice luminosa de trueque conectando ambos puntos del Shambles
		Vec3d pa = a.add(0, 1.0, 0), pb = b.add(0, 1.0, 0);
		Vec3d diff = pb.subtract(pa);
		double len = diff.length();
		if (len > 0.5 && len < 140) {
			Vec3d dir = diff.normalize();
			Vec3d side = dir.crossProduct(new Vec3d(0, 1, 0));
			if (side.lengthSquared() < 1e-4) side = new Vec3d(1, 0, 0);
			side = side.normalize();
			Vec3d up = side.crossProduct(dir).normalize();
			int steps = Math.min(48, Math.max(10, (int) (len * 1.4)));
			for (int i = 0; i <= steps; i++) {
				double u = i / (double) steps;
				double ang = u * Math.PI * 6.0;
				double rad = Math.sin(u * Math.PI) * 0.45;
				Vec3d pt = pa.add(diff.multiply(u)).add(side.multiply(Math.cos(ang) * rad)).add(up.multiply(Math.sin(ang) * rad));
				w.spawnParticles(CutEngine.ROOM_DUST, pt.x, pt.y, pt.z, 1, 0, 0, 0, 0);
				if (i % 3 == 0) w.spawnParticles(ParticleTypes.ELECTRIC_SPARK, pt.x, pt.y, pt.z, 1, 0.02, 0.02, 0.02, 0.02);
			}
		}
		w.playSound(null, p.getX(), p.getY(), p.getZ(), SoundEvents.BLOCK_AMETHYST_BLOCK_CHIME, SoundCategory.PLAYERS, 1.0f, 1.95f);
		w.playSound(null, p.getX(), p.getY(), p.getZ(), SoundEvents.BLOCK_AMETHYST_BLOCK_RESONATE, SoundCategory.PLAYERS, 0.85f, 1.55f);
		w.playSound(null, p.getX(), p.getY(), p.getZ(), SoundEvents.BLOCK_NOTE_BLOCK_CHIME.value(), SoundCategory.PLAYERS, 0.7f, 1.88f);
	}


	/** Shambles agachado: cambia bloques de sitio (marca uno y luego el otro). */
	private static void blocks(ServerPlayerEntity p, PlayerPower pw) {
		BlockHitResult hit = PowerManager.raycastBlock(p, 220);
		if (hit.getType() != HitResult.Type.BLOCK || !PowerManager.inRoom(p, hit.getPos())) {
			PowerManager.noRoom(p);
			return;
		}
		ServerWorld w = p.getServerWorld();
		BlockPos pos = hit.getBlockPos();
		BlockState st = w.getBlockState(pos);
		if (st.hasBlockEntity() || st.getHardness(w, pos) < 0) return;
		if (pw.shamblesBlock == null) {
			pw.shamblesBlock = pos;
			w.spawnParticles(CutEngine.ROOM_DUST, pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5, 30, 0.35, 0.35, 0.35, 0);
			p.sendMessage(Text.translatable("opeope.msg.marked", st.getBlock().getName()), true);
			return;
		}
		BlockPos other = pw.shamblesBlock;
		pw.shamblesBlock = null;
		if (other.equals(pos)) return;
		BlockState so = w.getBlockState(other);
		if (so.hasBlockEntity()) return;
		w.setBlockState(other, st, Block.NOTIFY_LISTENERS | Block.FORCE_STATE);
		w.setBlockState(pos, so, Block.NOTIFY_LISTENERS | Block.FORCE_STATE);
		w.updateNeighbors(other, st.getBlock());
		w.updateNeighbors(pos, so.getBlock());
		fx(p, Vec3d.ofCenter(other).subtract(0, 1, 0), Vec3d.ofCenter(pos).subtract(0, 1, 0));
		pw.cooldown(Tech.SHAMBLES);
	}
}
