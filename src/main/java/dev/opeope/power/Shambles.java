package dev.opeope.power;

import dev.opeope.OpeConfig;
import dev.opeope.entity.MarkerStoneEntity;
import dev.opeope.entity.PieceEntity;
import dev.opeope.net.Payloads;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.TntEntity;
import net.minecraft.entity.boss.WitherEntity;
import net.minecraft.entity.boss.dragon.EnderDragonEntity;
import net.minecraft.entity.boss.dragon.EnderDragonPart;
import net.minecraft.entity.mob.HostileEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.projectile.ArrowEntity;
import net.minecraft.entity.projectile.ProjectileEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.network.packet.s2c.play.EntityVelocityUpdateS2CPacket;
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
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

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

		cleanTacticalQueue(p, pw);
		Entity marked = pw.shamblesMark >= 0 ? p.getWorld().getEntityById(pw.shamblesMark) : null;
		if (marked != null && (marked.isRemoved() || !marked.isAlive() || pw.shamblesMarkTicks <= 0)) marked = null;
		if (marked == null) pw.shamblesMark = -1;

		PieceEntity conePiece = findTacticalConePiece(p, pw);

		// Con Shambles sin marca previa: el cono de asistencia hacia un trozo en vuelo tiene prioridad en 1 toque
		if (marked == null && conePiece != null) {
			if (swapPiece(p, pw, p, conePiece)) {
				pw.cooldown(Tech.SHAMBLES);
			}
			return;
		}

		// Si hay proyectiles o TNT en vuelo dentro del ROOM y no tienes marca previa, redirígelos todos al objetivo en la mira
		if (marked == null && redirectAirborneProjectiles(p, pw)) {
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

		if (marked == null) {
			if (target instanceof MarkerStoneEntity) {
				swap(p, p, target);
			} else if (target instanceof PieceEntity pe && isTacticalPiece(p, pe)) {
				if (!swapPiece(p, pw, p, pe)) return;
			} else if (target != null) {
				pw.shamblesMark = target.getId();
				pw.shamblesMarkTicks = OpeConfig.SHAMBLES_MARK_TICKS;
				target.setGlowing(true);
				p.sendMessage(Text.translatable("opeope.msg.marked", target.getDisplayName()), true);
				p.getWorld().playSound(null, target.getX(), target.getY(), target.getZ(), SoundEvents.BLOCK_AMETHYST_BLOCK_CHIME, SoundCategory.PLAYERS, 1f, 1.8f);
				return;
			} else {
				PieceEntity fallback = findFallbackPiece(p, pw);
				if (fallback != null) {
					if (!swapPiece(p, pw, p, fallback)) return;
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
			}
		} else {
			marked.setGlowing(false);
			pw.shamblesMark = -1;
			// una parte cortada y su dueño (en cualquier orden): la parte vuelve a su sitio
			if (Dismember.shamblesPair(p, marked, target)) {
				pw.cooldown(Tech.SHAMBLES);
				return;
			}
			if (target instanceof PieceEntity pe && isTacticalPiece(p, pe)) {
				if (!swapPiece(p, pw, marked, pe)) return;
			} else if (target == marked) {
				swap(p, p, marked);
			} else if (target != null) {
				swap(p, marked, target);
			} else {
				PieceEntity piece = conePiece != null ? conePiece : findFallbackPiece(p, pw);
				if (piece != null) {
					if (!swapPiece(p, pw, marked, piece)) return;
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
		}
		pw.cooldown(Tech.SHAMBLES);
	}

	/** ¿Es este trozo elegible para Shambles Táctico en vuelo dentro del ROOM? */
	public static boolean isTacticalPiece(ServerPlayerEntity p, @Nullable PieceEntity pe) {
		if (pe == null || !pe.isAlive() || pe.isRemoved() || pe.weapon != null) return false;
		if (pe.mode != PieceEntity.Mode.FREE || !pe.thrown || pe.isOnGround()) return false;
		if (!OpeConfig.SHAMBLES_ALLOW_FOREIGN_PIECES && !p.getUuid().equals(pe.owner)) return false;
		if (pe.getVelocity().lengthSquared() <= OpeConfig.SHAMBLES_VEL_MIN * OpeConfig.SHAMBLES_VEL_MIN) return false;
		return PowerManager.inRoom(p, pe.center());
	}

	private static void cleanTacticalQueue(ServerPlayerEntity p, PlayerPower pw) {
		ServerWorld w = p.getServerWorld();
		Iterator<Integer> it = pw.recentThrownPieces.iterator();
		while (it.hasNext()) {
			Entity e = w.getEntityById(it.next());
			if (!(e instanceof PieceEntity pe) || !isTacticalPiece(p, pe)) it.remove();
		}
		if (pw.lastThrownPieceId >= 0) {
			Entity last = w.getEntityById(pw.lastThrownPieceId);
			if (!(last instanceof PieceEntity pe) || !isTacticalPiece(p, pe)) pw.lastThrownPieceId = -1;
		}
	}

	@Nullable
	private static PieceEntity findTacticalConePiece(ServerPlayerEntity p, PlayerPower pw) {
		ServerWorld w = p.getServerWorld();
		Vec3d eye = p.getEyePos();
		Vec3d look = p.getRotationVector();
		double maxCos = Math.cos(Math.toRadians(OpeConfig.SHAMBLES_ASSIST_ANGLE_DEG));
		Set<PieceEntity> candidates = new LinkedHashSet<>();
		for (int id : pw.recentThrownPieces) {
			if (w.getEntityById(id) instanceof PieceEntity pe && isTacticalPiece(p, pe)) candidates.add(pe);
		}
		Room active = pw.room != null && pw.room.dim == w.getRegistryKey() ? pw.room : pw.rroom;
		if (active != null && active.dim == w.getRegistryKey()) {
			Box box = new Box(active.center, active.center).expand(active.radius);
			for (PieceEntity pe : w.getEntitiesByClass(PieceEntity.class, box, piece -> isTacticalPiece(p, piece))) {
				candidates.add(pe);
			}
		}
		PieceEntity best = null;
		double bestDot = maxCos;
		double bestDist2 = Double.MAX_VALUE;
		for (PieceEntity pe : candidates) {
			Vec3d nowC = pe.center();
			Vec3d leadC = nowC.add(pe.getVelocity().multiply(OpeConfig.SHAMBLES_LEAD_TICKS));
			Vec3d toNow = nowC.subtract(eye);
			Vec3d toLead = leadC.subtract(eye);
			double dotNow = toNow.lengthSquared() > 1e-4 ? toNow.normalize().dotProduct(look) : -1;
			double dotLead = toLead.lengthSquared() > 1e-4 ? toLead.normalize().dotProduct(look) : -1;
			double dot = Math.max(dotNow, dotLead);
			double dist2 = eye.squaredDistanceTo(nowC);
			if (dot > bestDot + 1e-4 || (Math.abs(dot - bestDot) <= 1e-4 && dist2 < bestDist2)) {
				bestDot = dot;
				bestDist2 = dist2;
				best = pe;
			}
		}
		return best;
	}

	@Nullable
	private static PieceEntity findFallbackPiece(ServerPlayerEntity p, PlayerPower pw) {
		ServerWorld w = p.getServerWorld();
		long ageTicks = w.getTime() - pw.lastThrownTick;
		if (pw.lastThrownPieceId >= 0 && ageTicks <= OpeConfig.SHAMBLES_FALLBACK_WINDOW) {
			if (w.getEntityById(pw.lastThrownPieceId) instanceof PieceEntity pe && isTacticalPiece(p, pe)) return pe;
		}
		for (int id : pw.recentThrownPieces) {
			if (w.getEntityById(id) instanceof PieceEntity pe && isTacticalPiece(p, pe)) return pe;
		}
		return null;
	}

	private static boolean canSwapWithPiece(Entity e) {
		if (e == null || !e.isAlive() || e.isRemoved() || e.isSpectator()) return false;
		return !(e instanceof EnderDragonEntity || e instanceof EnderDragonPart || e instanceof WitherEntity);
	}

	@Nullable
	private static Vec3d findSafeEntityPos(ServerWorld w, Entity e, Vec3d basePos, double maxR) {
		Box baseBox = e.getBoundingBox().offset(basePos.subtract(e.getPos()));
		if (w.isSpaceEmpty(e, baseBox)) return basePos;
		for (double dy = 0.5; dy <= maxR; dy += 0.5) {
			if (w.isSpaceEmpty(e, baseBox.offset(0, dy, 0))) return basePos.add(0, dy, 0);
		}
		double step = 0.65;
		double[] ys = {0.0, 0.65, -0.65, 1.3, -1.3};
		for (double r = step; r <= maxR; r += step) {
			for (int a = 0; a < 8; a++) {
				double ang = a * (Math.PI / 4.0);
				double dx = Math.cos(ang) * r;
				double dz = Math.sin(ang) * r;
				for (double dy : ys) {
					if (dx * dx + dy * dy + dz * dz > maxR * maxR + 0.05) continue;
					if (w.isSpaceEmpty(e, baseBox.offset(dx, dy, dz))) return basePos.add(dx, dy, dz);
				}
			}
		}
		return null;
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
		teleport(e, pos, false);
	}

	public static void teleport(Entity e, Vec3d pos, boolean keepVelocity) {
		Vec3d prevVel = keepVelocity ? e.getVelocity() : Vec3d.ZERO;
		if (e instanceof PieceEntity pe) {
			pe.setPosition(pos);
			if (pe.mode == PieceEntity.Mode.CONTROLLED) pe.target = pe.center();
		} else {
			e.requestTeleport(pos.x, pos.y, pos.z);
		}
		e.setVelocity(prevVel);
		e.fallDistance = 0;
		e.velocityModified = true;
		if (e instanceof ServerPlayerEntity sp) {
			PlayerPower pw = PowerManager.get(sp);
			pw.fallImmunityTicks = 600;
			if (keepVelocity && sp.networkHandler != null) {
				sp.networkHandler.sendPacket(new EntityVelocityUpdateS2CPacket(sp.getId(), prevVel));
			}
		}
	}

	public static void swap(ServerPlayerEntity p, Entity a, Entity b) {
		Vec3d pa = a.getPos(), pb = b.getPos();
		teleport(a, pb);
		teleport(b, pa);
		fx(p, pa, pb);
	}

	/** Intercambio táctico centrado entre jugador/enemigo y un PieceEntity en vuelo, conservando impulso y abriendo ventana aérea. */
	public static boolean swapPiece(ServerPlayerEntity p, PlayerPower pw, Entity other, PieceEntity pe) {
		if (pe == null || !isTacticalPiece(p, pe) || !canSwapWithPiece(other)) return false;
		ServerWorld w = p.getServerWorld();
		boolean selfSwap = other == p;
		if (!selfSwap && other instanceof ServerPlayerEntity spTarget && w.getServer() != null
			&& (!w.getServer().isPvpEnabled() || spTarget.isCreative())) {
			return false;
		}
		if (selfSwap && !p.isOnGround() && pw.aerialChain >= OpeConfig.SHAMBLES_MAX_AIR_CHAIN) {
			p.sendMessage(Text.translatable("opeope.msg.air_chain_limit"), true);
			w.playSound(null, p.getX(), p.getY(), p.getZ(), SoundEvents.BLOCK_NOTE_BLOCK_BASS.value(), SoundCategory.PLAYERS, 0.8f, 0.6f);
			return false;
		}

		Vec3d cPiece = pe.center();
		Vec3d cOther = other.getBoundingBox().getCenter();
		Vec3d rawOtherPos = new Vec3d(cPiece.x, cPiece.y - other.getHeight() * 0.5, cPiece.z);
		Vec3d safeOtherPos = findSafeEntityPos(w, other, rawOtherPos, 2.0);
		if (safeOtherPos == null) {
			p.sendMessage(Text.translatable("opeope.msg.shambles_no_space"), true);
			w.playSound(null, p.getX(), p.getY(), p.getZ(), SoundEvents.BLOCK_NOTE_BLOCK_BASS.value(), SoundCategory.PLAYERS, 0.8f, 0.5f);
			return false;
		}

		Vec3d pieceCenterOffset = cPiece.subtract(pe.getPos());
		Vec3d rawPieceBase = cOther.subtract(pieceCenterOffset);
		if (rawPieceBase.y < w.getBottomY() + 1) rawPieceBase = new Vec3d(rawPieceBase.x, w.getBottomY() + 1.0, rawPieceBase.z);
		Vec3d safePieceBase = rawPieceBase;
		if (!OpeConfig.SHAMBLES_PIECE_CRUSH_ON_OVERLAP) {
			Box pBox = pe.getBoundingBox().offset(rawPieceBase.subtract(pe.getPos()));
			for (double dy = 0; dy <= 4.0; dy += 0.5) {
				if (w.isBlockSpaceEmpty(pe, pBox.offset(0, dy, 0))) {
					safePieceBase = rawPieceBase.add(0, dy, 0);
					break;
				}
			}
		}

		int ticksToImpact = pe.predictTicksToImpact(20);
		boolean perfect = selfSwap && ticksToImpact <= OpeConfig.SHAMBLES_PERFECT_TICKS;
		int finId = selfSwap ? findTrajectoryTarget(w, p, cPiece, pe.getVelocity()) : -1;

		Vec3d pieceVel = pe.getVelocity();
		double velMag = pieceVel.length();
		int velMode = selfSwap ? OpeConfig.SHAMBLES_PIECE_VEL_MODE_SELF : OpeConfig.SHAMBLES_PIECE_VEL_MODE_ENEMY;
		Vec3d newPieceVel = pieceVel;
		if (velMode == OpeConfig.SHAMBLES_VEL_REAIM) {
			Vec3d reaimTarget = selfSwap ? PowerManager.aimPoint(p, 120) : safeOtherPos.add(0, other.getHeight() * 0.5, 0);
			Vec3d diff = reaimTarget.subtract(cOther);
			if (diff.lengthSquared() > 1e-4) {
				newPieceVel = diff.normalize().multiply(Math.max(velMag, 0.85));
			}
		}

		if (pe.isRemoved() || !pe.isAlive()) return false;
		if (other.hasVehicle()) other.stopRiding();
		if (other.hasPassengers()) other.removeAllPassengers();

		pe.setPosition(safePieceBase);
		pe.setVelocity(newPieceVel);
		pe.thrown = true;
		pe.ownerGraceTicks = OpeConfig.SHAMBLES_OWNER_GRACE_TICKS;
		pe.velocityModified = true;

		if (selfSwap) {
			Vec3d inherit = pieceVel.multiply(OpeConfig.SHAMBLES_MOMENTUM_INHERIT);
			double cap = OpeConfig.SHAMBLES_MOMENTUM_CAP;
			if (inherit.lengthSquared() > cap * cap) inherit = inherit.multiply(cap / inherit.length());
			inherit = new Vec3d(inherit.x, Math.max(inherit.y, 0.14), inherit.z);
			p.requestTeleport(safeOtherPos.x, safeOtherPos.y, safeOtherPos.z);
			p.setOnGround(false);
			p.setVelocity(inherit);
			p.fallDistance = 0f;
			p.velocityModified = true;
			pw.fallImmunityTicks = 600;
			if (p.networkHandler != null) {
				p.networkHandler.sendPacket(new EntityVelocityUpdateS2CPacket(p.getId(), inherit));
			}

			pw.aerialChain++;
			int dur = perfect ? OpeConfig.SHAMBLES_AERIAL_WINDOW_PERFECT : OpeConfig.SHAMBLES_AERIAL_WINDOW;
			pw.aerialWindowTicks = dur;
			pw.aerialWindowMaxTicks = dur;
			pw.aerialPerfect = perfect;
			pw.finisherTargetId = finId;
			syncAerialWindow(p, pw);
			p.sendMessage(Text.translatable(perfect ? "opeope.msg.shambles_perfect" : "opeope.msg.shambles_tactical"), true);
		} else {
			teleport(other, safeOtherPos, false);
			p.sendMessage(Text.translatable("opeope.msg.shambles_enemy_piece", other.getDisplayName()), true);
		}

		tacticalFx(p, cOther, cPiece, perfect);
		return true;
	}

	private static int findTrajectoryTarget(ServerWorld w, ServerPlayerEntity p, Vec3d origin, Vec3d vel) {
		Vec3d dir = vel.lengthSquared() > 1e-4 ? vel.normalize() : p.getRotationVector();
		LivingEntity best = null;
		double bestScore = Double.MAX_VALUE;
		Box search = new Box(origin, origin.add(dir.multiply(24.0))).expand(7.5);
		for (LivingEntity le : w.getEntitiesByClass(LivingEntity.class, search,
			e -> e != p && e.isAlive() && !e.isSpectator() && PowerManager.inRoom(p, e.getBoundingBox().getCenter()))) {
			Vec3d to = le.getBoundingBox().getCenter().subtract(origin);
			double proj = to.dotProduct(dir);
			if (proj < -1.0 || proj > 26.0) continue;
			double perp2 = to.subtract(dir.multiply(Math.max(0.0, proj))).lengthSquared();
			if (perp2 > 36.0) continue;
			double score = perp2 + Math.abs(proj) * 0.25;
			if (score < bestScore) {
				bestScore = score;
				best = le;
			}
		}
		return best != null ? best.getId() : -1;
	}

	public static void tickAerialWindow(ServerPlayerEntity p, PlayerPower pw) {
		boolean sync = false;
		if (pw.aerialWindowTicks > 0) {
			p.fallDistance = 0f;
			pw.aerialWindowTicks--;
			int elapsed = pw.aerialWindowMaxTicks - pw.aerialWindowTicks;
			if (pw.aerialWindowTicks == 0 || (elapsed >= 4 && p.isOnGround())) {
				pw.clearAerialWindow();
				if (elapsed >= 4 && p.isOnGround()) pw.aerialChain = 0;
				sync = true;
			}
		} else if (pw.aerialChain > 0 && p.isOnGround()) {
			pw.aerialChain = 0;
		}
		if (!pw.recentThrownPieces.isEmpty() && p.age % 2 == 0) {
			int before = pw.recentThrownPieces.size();
			cleanTacticalQueue(p, pw);
			if (pw.recentThrownPieces.size() != before) sync = true;
		}
		if (sync) syncAerialWindow(p, pw);
	}

	public static void syncAerialWindow(ServerPlayerEntity p, PlayerPower pw) {
		if (p.networkHandler == null) return;
		cleanTacticalQueue(p, pw);
		int[] ids = new int[pw.recentThrownPieces.size()];
		for (int i = 0; i < ids.length; i++) ids[i] = pw.recentThrownPieces.get(i);
		ServerPlayNetworking.send(p, new Payloads.AerialWindow(
			pw.aerialWindowTicks, pw.aerialWindowMaxTicks, OpeConfig.SHAMBLES_AERIAL_GRAV_MULT,
			pw.aerialPerfect, pw.finisherTargetId, ids));
	}

	private static void tacticalFx(ServerPlayerEntity p, Vec3d cA, Vec3d cB, boolean perfect) {
		fx(p, cA.subtract(0, 1.0, 0), cB.subtract(0, 1.0, 0));
		ServerWorld w = p.getServerWorld();
		for (Vec3d v : new Vec3d[]{cA, cB}) {
			w.spawnParticles(ParticleTypes.ELECTRIC_SPARK, v.x, v.y, v.z, perfect ? 28 : 14, 0.45, 0.45, 0.45, 0.14);
			if (perfect) {
				w.spawnParticles(ParticleTypes.END_ROD, v.x, v.y, v.z, 18, 0.5, 0.5, 0.5, 0.08);
			}
		}
		w.playSound(null, cB.x, cB.y, cB.z, SoundEvents.BLOCK_AMETHYST_BLOCK_CHIME, SoundCategory.PLAYERS,
			1.2f, perfect ? 2.0f : 1.85f);
		if (perfect) {
			w.playSound(null, cB.x, cB.y, cB.z, SoundEvents.ENTITY_PLAYER_LEVELUP, SoundCategory.PLAYERS, 0.65f, 1.9f);
		}
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
