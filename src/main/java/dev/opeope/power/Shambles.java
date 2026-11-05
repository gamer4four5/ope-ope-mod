package dev.opeope.power;

import dev.opeope.OpeConfig;
import dev.opeope.entity.MarkerStoneEntity;
import dev.opeope.entity.PieceEntity;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
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
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;

/**
 * Shambles: cambiar de sitio lo que quieras dentro del ROOM, tú incluido.
 * Con el aire te teletransportas; marca algo y luego lo mismo (te cambias), otro objetivo (se cambian) o un punto vacío.
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
		Entity target = PowerManager.raycastEntityLoose(p, 220, 2.0, e -> e != p && !e.isSpectator()
			&& !(e instanceof PieceEntity pe && pe.weapon != null));
		if (target != null && !PowerManager.inRoom(p, target.getBoundingBox().getCenter())) target = null;

		// Con Scan, E roba lo que lleva en la mano.
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
			w.spawnParticles(ParticleTypes.REVERSE_PORTAL, v.x, v.y + 1, v.z, 25, 0.3, 0.6, 0.3, 0.05);
			w.spawnParticles(CutEngine.ROOM_DUST, v.x, v.y + 1, v.z, 15, 0.4, 0.7, 0.4, 0);
			w.playSound(null, v.x, v.y, v.z, SoundEvents.ENTITY_ENDERMAN_TELEPORT, SoundCategory.PLAYERS, 0.7f, 1.6f);
		}
		w.playSound(null, p.getX(), p.getY(), p.getZ(), SoundEvents.BLOCK_AMETHYST_BLOCK_HIT, SoundCategory.PLAYERS, 1f, 2f);
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
