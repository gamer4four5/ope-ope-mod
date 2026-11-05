package dev.opeope.power;

import dev.opeope.OpeConfig;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayList;
import java.util.List;

/** Q: abrir, agrandar, lanzar, traer y quitar el ROOM. Y el R-ROOM del Despertar. */
public final class RoomOps {
	private RoomOps() {}

	private static int now(ServerPlayerEntity p) {
		return p.getServer().getTicks();
	}

	public static void press(ServerPlayerEntity p, PlayerPower pw) {
		pw.roomKeyDown = true;
		pw.roomPressTick = now(p);
		pw.roomUsedDuringHold = false;
		pw.roomCreatedThisPress = false;
		if (pw.room == null || pw.room.dim != p.getWorld().getRegistryKey()) {
			create(p, pw);
		}
	}

	private static void create(ServerPlayerEntity p, PlayerPower pw) {
		pw.room = new Room(p.getWorld().getRegistryKey(), p.getPos().add(0, 1.0, 0), OpeConfig.ROOM_START_RADIUS);
		pw.room.state = Room.State.GROWING;
		pw.roomCreatedThisPress = true;
		p.getWorld().playSound(null, p.getX(), p.getY(), p.getZ(), SoundEvents.BLOCK_BEACON_ACTIVATE, SoundCategory.PLAYERS, 1.0f, 1.4f);
		p.getWorld().playSound(null, p.getX(), p.getY(), p.getZ(), SoundEvents.BLOCK_AMETHYST_BLOCK_RESONATE, SoundCategory.PLAYERS, 1.0f, 0.6f);
		p.sendMessage(Text.literal("ROOM").styled(s -> s.withColor(0x8fd8ff).withBold(true)), true);
		PowerManager.broadcastRoom(p, 0, pw.room);
	}

	public static void release(ServerPlayerEntity p, PlayerPower pw) {
		pw.roomKeyDown = false;
		if (pw.room == null) return;
		int held = now(p) - pw.roomPressTick;
		if (pw.roomCreatedThisPress) {
			if (pw.room.state == Room.State.GROWING) pw.room.state = Room.State.FIXED;
			p.getWorld().playSound(null, p.getX(), p.getY(), p.getZ(), SoundEvents.BLOCK_BEACON_POWER_SELECT, SoundCategory.PLAYERS, 0.8f, 1.5f);
			PowerManager.broadcastRoom(p, 0, pw.room);
		} else if (!pw.roomUsedDuringHold && held < OpeConfig.ROOM_TAP_TICKS) {
			remove(p, pw);
		}
	}

	public static void remove(ServerPlayerEntity p, PlayerPower pw) {
		if (pw.room != null) {
			pw.room = null;
			PowerManager.broadcastRoom(p, 0, null);
			p.getWorld().playSound(null, p.getX(), p.getY(), p.getZ(), SoundEvents.BLOCK_BEACON_DEACTIVATE, SoundCategory.PLAYERS, 0.8f, 1.4f);
		}
		if (pw.rroom != null) {
			pw.rroom = null;
			PowerManager.broadcastRoom(p, 1, null);
		}
	}

	public static void scroll(ServerPlayerEntity p, PlayerPower pw, int dir) {
		pw.roomUsedDuringHold = true;
		if (pw.room == null) return;
		double f = dir > 0 ? 1.15 : 1 / 1.15;
		pw.room.radius = MathHelper.clamp(pw.room.radius * f, OpeConfig.ROOM_MIN_RADIUS, OpeConfig.ROOM_MAX_RADIUS);
		if (pw.room.state == Room.State.GROWING) pw.room.state = Room.State.FIXED;
		PowerManager.broadcastRoom(p, 0, pw.room);
	}

	public static void launch(ServerPlayerEntity p, PlayerPower pw) {
		pw.roomUsedDuringHold = true;
		if (pw.room == null || pw.room.dim != p.getWorld().getRegistryKey()) create(p, pw);
		pw.room.flyTo = PowerManager.aimPoint(p, 300);
		pw.room.state = Room.State.FLYING;
		p.getWorld().playSound(null, p.getX(), p.getY(), p.getZ(), SoundEvents.ENTITY_ILLUSIONER_MIRROR_MOVE, SoundCategory.PLAYERS, 1f, 1.3f);
		PowerManager.broadcastRoom(p, 0, pw.room);
	}

	public static void bring(ServerPlayerEntity p, PlayerPower pw) {
		pw.roomUsedDuringHold = true;
		if (pw.room == null || pw.room.dim != p.getWorld().getRegistryKey()) return;
		pw.room.flyTo = p.getPos().add(0, 1, 0);
		pw.room.state = Room.State.FLYING;
		p.getWorld().playSound(null, p.getX(), p.getY(), p.getZ(), SoundEvents.ENTITY_ILLUSIONER_MIRROR_MOVE, SoundCategory.PLAYERS, 1f, 0.9f);
		PowerManager.broadcastRoom(p, 0, pw.room);
	}

	public static void tick(ServerPlayerEntity p, PlayerPower pw) {
		Room room = pw.room;
		if (room != null) {
			// Mantener Q con un ROOM ya hecho (sin rueda ni clic) lo rehace desde el cuerpo.
			if (pw.roomKeyDown && !pw.roomCreatedThisPress && !pw.roomUsedDuringHold && now(p) - pw.roomPressTick >= 8) {
				create(p, pw);
				room = pw.room;
			}
			if (room.state == Room.State.GROWING) {
				if (!pw.roomKeyDown) {
					room.state = Room.State.FIXED;
				} else {
					room.radius = Math.min(OpeConfig.ROOM_MAX_RADIUS, room.radius + (OpeConfig.ROOM_GROW_SPEED + OpeConfig.ROOM_GROW_ACCEL * room.radius) / 20.0);
				}
				PowerManager.broadcastRoom(p, 0, room);
			} else if (room.state == Room.State.FLYING && room.flyTo != null) {
				Vec3d d = room.flyTo.subtract(room.center);
				double len = d.length();
				if (len <= OpeConfig.ROOM_FLY_SPEED) {
					room.center = room.flyTo;
					room.state = Room.State.FIXED;
					p.getWorld().playSound(null, room.center.x, room.center.y, room.center.z, SoundEvents.BLOCK_BEACON_ACTIVATE, SoundCategory.PLAYERS, 1.2f, 1.6f);
				} else {
					room.center = room.center.add(d.multiply(OpeConfig.ROOM_FLY_SPEED / len));
				}
				if (p.getWorld() instanceof ServerWorld sw) sw.spawnParticles(CutEngine.ROOM_DUST, room.center.x, room.center.y, room.center.z, 6, 0.3, 0.3, 0.3, 0);
				PowerManager.broadcastRoom(p, 0, room);
			}
		}
		Room rr = pw.rroom;
		if (rr != null) {
			if (pw.rroomHeld) {
				if (rr.radius < rr.maxRadius) {
					rr.radius = Math.min(rr.maxRadius, rr.radius + 7 / 20.0);
					PowerManager.broadcastRoom(p, 1, rr);
				}
				if (++pw.rroomTicks > 20 * 8) rroom(p, pw, false);
			} else if (--pw.rroomTicks <= 0) {
				pw.rroom = null;
				PowerManager.broadcastRoom(p, 1, null);
			}
		}
	}

	/** R-ROOM (despierto, F): una semilla de ROOM vuela a la mira, crece mientras mantienes y al soltar estalla en cortes. */
	public static void rroom(ServerPlayerEntity p, PlayerPower pw, boolean start) {
		if (start) {
			if (pw.onCooldown(Tech.RROOM) || pw.rroom != null) return;
			Vec3d point = PowerManager.aimPoint(p, 140);
			pw.rroom = new Room(p.getWorld().getRegistryKey(), point, 4);
			pw.rroom.maxRadius = 18;
			pw.rroom.state = Room.State.GROWING;
			pw.rroomHeld = true;
			pw.rroomTicks = 0;
			Techniques.beam(p, p.getEyePos(), point, CutEngine.ROOM_DUST);
			p.getWorld().playSound(null, point.x, point.y, point.z, SoundEvents.BLOCK_BEACON_ACTIVATE, SoundCategory.PLAYERS, 1.5f, 1.8f);
			PowerManager.broadcastRoom(p, 1, pw.rroom);
			return;
		}
		if (!pw.rroomHeld || pw.rroom == null) return;
		pw.rroomHeld = false;
		pw.rroomTicks = 20;
		Room rr = pw.rroom;
		rr.state = Room.State.FIXED;
		List<CutEngine.Plane> planes = new ArrayList<>();
		double step = Math.max(2.5, rr.radius / 3);
		for (Vec3d axis : new Vec3d[]{new Vec3d(1, 0, 0), new Vec3d(0, 1, 0), new Vec3d(0, 0, 1)}) {
			for (int k = -1; k <= 1; k++) planes.add(new CutEngine.Plane(axis, rr.center.add(axis.multiply(k * step))));
		}
		CutEngine.cut(p, planes, CutEngine.Region.sphere(rr.center, rr.radius), false, 0, 6f);
		if (p.getWorld() instanceof ServerWorld sw) {
			sw.spawnParticles(ParticleTypes.FLASH, rr.center.x, rr.center.y, rr.center.z, 1, 0, 0, 0, 0);
			sw.spawnParticles(CutEngine.ROOM_DUST, rr.center.x, rr.center.y, rr.center.z, 200, rr.radius / 2, rr.radius / 2, rr.radius / 2, 0);
		}
		p.getWorld().playSound(null, rr.center.x, rr.center.y, rr.center.z, SoundEvents.ENTITY_WARDEN_SONIC_BOOM, SoundCategory.PLAYERS, 1.5f, 1.4f);
		pw.cooldown(Tech.RROOM);
		PowerManager.broadcastRoom(p, 1, rr);
	}
}
