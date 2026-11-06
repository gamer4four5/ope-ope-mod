package dev.opeope.power;

import dev.opeope.OpeConfig;
import dev.opeope.net.Payloads;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayList;
import java.util.List;

/**
 * Q: abrir, agrandar, lanzar, traer y quitar el ROOM. Y el R-ROOM del Despertar.
 * <p>Cada momento del ROOM se cuenta a los clientes con {@link RoomEvents} (luz, sonido, cámara y HUD los pone el
 * cliente), siempre antes del {@code broadcastRoom} que lo acompaña. El servidor ya no suena ni echa partículas por el
 * ROOM normal; el R-ROOM sigue como estaba.
 */
public final class RoomOps {
	private RoomOps() {}

	private static int now(ServerPlayerEntity p) {
		return p.getServer().getTicks();
	}

	/** Dónde está la mano: un poco delante de los ojos y por debajo. De ahí sale el ROOM al nacer y al lanzarlo. */
	private static Vec3d hand(ServerPlayerEntity p) {
		return p.getEyePos().add(p.getRotationVec(1f).multiply(0.6)).add(0, -0.35, 0);
	}

	public static void press(ServerPlayerEntity p, PlayerPower pw) {
		pw.roomKeyDown = true;
		pw.roomPressTick = now(p);
		pw.roomUsedDuringHold = false;
		pw.roomCreatedThisPress = false;
		if (pw.room == null || pw.room.dim != p.getWorld().getRegistryKey()) {
			create(p, pw, false);
		}
	}

	/** Un ROOM nuevo desde el cuerpo, creciendo. rebuilt: se rehízo manteniendo Q sobre uno que ya había. */
	private static void create(ServerPlayerEntity p, PlayerPower pw, boolean rebuilt) {
		Room room = new Room(p.getWorld().getRegistryKey(), p.getPos().add(0, 1.0, 0), OpeConfig.ROOM_START_RADIUS);
		room.state = Room.State.GROWING;
		room.limitSent = false;
		pw.room = room;
		pw.roomCreatedThisPress = true;
		RoomEvents.send(p, 0, Payloads.RoomFx.CREATE, hand(p), room.center, (float) room.radius, 0f, 0,
			rebuilt ? Payloads.RoomFx.F_REBUILT : 0);
		PowerManager.broadcastRoom(p, 0, room);
	}

	/** Deja de crecer: queda fijo con el tamaño que tenga. */
	private static void fix(ServerPlayerEntity p, PlayerPower pw) {
		Room room = pw.room;
		if (room == null || room.state != Room.State.GROWING) return;
		room.state = Room.State.FIXED;
		pw.lastRoomRadius = room.radius;
		RoomEvents.send(p, 0, Payloads.RoomFx.FIX, room.center, null, (float) room.radius, 0f, 0, 0);
		PowerManager.broadcastRoom(p, 0, room);
	}

	/**
	 * Al soltar Q. arg 0: la regla de siempre (un toque corto sin usar el ROOM lo quita); arg 1: nunca lo quita (el
	 * cliente decide, por ejemplo con «Quitar el ROOM: doble toque»).
	 */
	public static void release(ServerPlayerEntity p, PlayerPower pw, int arg) {
		pw.roomKeyDown = false;
		if (pw.room == null) return;
		int held = now(p) - pw.roomPressTick;
		if (pw.roomCreatedThisPress) {
			// recién creado: se fija; si ya va volando (lanzado nada más crearlo), sigue su vuelo
			if (pw.room.state == Room.State.GROWING) fix(p, pw);
		} else if (arg == 0 && !pw.roomUsedDuringHold && held < OpeConfig.ROOM_TAP_TICKS) {
			remove(p, pw);
		}
	}

	public static void remove(ServerPlayerEntity p, PlayerPower pw) {
		if (pw.room != null) {
			Room room = pw.room;
			RoomEvents.send(p, 0, Payloads.RoomFx.CLOSE, room.center, null, (float) room.radius, 0f, 0, 0);
			pw.room = null;
			PowerManager.broadcastRoom(p, 0, null);
		}
		if (pw.rroom != null) {
			pw.rroom = null;
			PowerManager.broadcastRoom(p, 1, null);
		}
	}

	/**
	 * Q + rueda: cada paso multiplica o divide el radio por 1,14 y lo cambia al menos 1,25 bloques. En el tope avisa
	 * (LIMIT) sin cambiar nada. Un ROOM que crece queda fijo sin más aviso: el paso ya se nota.
	 */
	public static void scroll(ServerPlayerEntity p, PlayerPower pw, int dir) {
		pw.roomUsedDuringHold = true;
		Room room = pw.room;
		if (room == null || room.state == Room.State.FLYING || dir == 0) return;
		double r0 = room.radius;
		double r1 = dir > 0
			? Math.max(r0 * OpeConfig.ROOM_NOTCH, r0 + OpeConfig.ROOM_NOTCH_MIN_STEP)
			: Math.min(r0 / OpeConfig.ROOM_NOTCH, r0 - OpeConfig.ROOM_NOTCH_MIN_STEP);
		r1 = MathHelper.clamp(r1, OpeConfig.ROOM_MIN_RADIUS, OpeConfig.ROOM_MAX_RADIUS);
		if (Math.abs(r1 - r0) < 1e-6) {
			RoomEvents.send(p, 0, Payloads.RoomFx.LIMIT, room.center, null, (float) r0, 0f, 0,
				dir > 0 ? Payloads.RoomFx.LIMIT_MAX : Payloads.RoomFx.LIMIT_MIN);
			return;
		}
		room.radius = r1;
		if (room.state == Room.State.GROWING) room.state = Room.State.FIXED;
		pw.lastRoomRadius = r1;
		RoomEvents.send(p, 0, Payloads.RoomFx.NOTCH, room.center, null, (float) r1, (float) r0, 0,
			dir > 0 ? Payloads.RoomFx.F_GROW : Payloads.RoomFx.F_SHRINK);
		PowerManager.broadcastRoom(p, 0, room);
	}

	/**
	 * Q + clic: el ROOM sale de la mano como un cometa en arco y cae donde miras (encima del bloque, o pegado a la cara
	 * que tocas; si no tocas nada, a 48 bloques). El viejo se queda donde estaba hasta que aterriza el nuevo.
	 */
	public static void launch(ServerPlayerEntity p, PlayerPower pw) {
		pw.roomUsedDuringHold = true;
		if (pw.room == null || pw.room.dim != p.getWorld().getRegistryKey()) create(p, pw, false);
		Room room = pw.room;
		if (room.state == Room.State.FLYING) return;
		Vec3d eye = p.getEyePos();
		BlockHitResult hit = PowerManager.raycastBlock(p, OpeConfig.ROOM_CAST_RANGE);
		Vec3d target;
		if (hit.getType() == HitResult.Type.MISS) {
			target = eye.add(p.getRotationVector().multiply(OpeConfig.ROOM_CAST_MISS));
		} else if (hit.getSide() == Direction.UP) {
			target = hit.getPos().add(0, 1, 0);
		} else {
			target = hit.getPos().add(Vec3d.of(hit.getSide().getVector()).multiply(0.6));
		}
		double dist = target.distanceTo(eye);
		int ticks = MathHelper.clamp((int) Math.round(dist / OpeConfig.ROOM_CAST_SPEED * 20.0), 4, 14);
		double arc = Math.min(10.0, 0.1 * dist);
		// solo el primer lanzamiento de un ROOM recién creado con esta pulsación es «nuevo»; los siguientes mueven el que ya está
		boolean fresh = pw.roomCreatedThisPress;
		pw.roomCreatedThisPress = false;
		double size = fresh
			? Math.max(room.radius, pw.lastRoomRadius > 0 ? pw.lastRoomRadius : OpeConfig.ROOM_FIRST_RADIUS)
			: room.radius;
		room.castRadius = MathHelper.clamp(size, OpeConfig.ROOM_MIN_RADIUS, OpeConfig.ROOM_MAX_RADIUS);
		room.fresh = fresh;
		room.flyFrom = room.center;
		room.flyTo = target;
		room.flyTicks = ticks;
		room.flyTotal = ticks;
		room.bringing = false;
		room.state = Room.State.FLYING;
		RoomEvents.send(p, 0, Payloads.RoomFx.CAST, hand(p), target, (float) room.castRadius, (float) arc, ticks,
			fresh ? Payloads.RoomFx.F_FRESH : 0);
		PowerManager.broadcastRoom(p, 0, room);
	}

	/** Q + clic derecho: el ROOM vuelve a ti, rápido al principio y frenando al llegar. Si ya está centrado, avisa. */
	public static void bring(ServerPlayerEntity p, PlayerPower pw) {
		pw.roomUsedDuringHold = true;
		Room room = pw.room;
		if (room == null || room.dim != p.getWorld().getRegistryKey() || room.state == Room.State.FLYING) return;
		Vec3d to = p.getPos().add(0, 1, 0);
		double d = room.center.distanceTo(to);
		if (d < 0.6) {
			RoomEvents.send(p, 0, Payloads.RoomFx.LIMIT, room.center, null, (float) room.radius, 0f, 0, Payloads.RoomFx.LIMIT_CENTRED);
			return;
		}
		double seconds = MathHelper.clamp(0.42 * (0.55 + 0.45 * Math.min(1.0, d / 60.0)), 0.24, 0.6);
		int ticks = MathHelper.clamp((int) Math.round(20.0 * seconds), 5, 12);
		room.flyFrom = room.center;
		room.flyTo = to;
		room.flyTicks = ticks;
		room.flyTotal = ticks;
		room.bringing = true;
		room.state = Room.State.FLYING;
		RoomEvents.send(p, 0, Payloads.RoomFx.BRING, room.center, to, (float) room.radius, 0f, ticks, 0);
		PowerManager.broadcastRoom(p, 0, room);
	}

	public static void tick(ServerPlayerEntity p, PlayerPower pw) {
		Room room = pw.room;
		if (room != null) {
			// Mantener Q con un ROOM ya hecho (sin rueda ni clic) lo rehace desde el cuerpo: se cierra el viejo y nace otro.
			if (pw.roomKeyDown && !pw.roomCreatedThisPress && !pw.roomUsedDuringHold && now(p) - pw.roomPressTick >= 8) {
				RoomEvents.send(p, 0, Payloads.RoomFx.CLOSE, room.center, null, (float) room.radius, 0f, 0, 0);
				create(p, pw, true);
				room = pw.room;
			}
			if (room.state == Room.State.GROWING) {
				if (!pw.roomKeyDown) {
					fix(p, pw);
				} else {
					room.radius = Math.min(OpeConfig.ROOM_MAX_RADIUS, room.radius + (OpeConfig.ROOM_GROW_SPEED + OpeConfig.ROOM_GROW_ACCEL * room.radius) / 20.0);
					if (room.radius >= OpeConfig.ROOM_MAX_RADIUS && !room.limitSent) {
						room.limitSent = true;
						RoomEvents.send(p, 0, Payloads.RoomFx.LIMIT, room.center, null, (float) room.radius, 0f, 0, Payloads.RoomFx.LIMIT_MAX_GROWING);
					}
					PowerManager.broadcastRoom(p, 0, room);
				}
			} else if (room.state == Room.State.FLYING) {
				if (room.bringing) {
					// vuelve a ti: rápido al salir y frenando al llegar (sigue al jugador si se mueve)
					room.flyTicks--;
					Vec3d to = p.getPos().add(0, 1, 0);
					Vec3d from = room.flyFrom != null ? room.flyFrom : room.center;
					double u = room.flyTotal > 0 ? 1.0 - Math.max(0, room.flyTicks) / (double) room.flyTotal : 1.0;
					double e = 1.0 - Math.pow(1.0 - u, 3);
					room.center = from.lerp(to, e);
					if (room.flyTicks <= 0) {
						room.center = to;
						room.state = Room.State.FIXED;
						room.bringing = false;
						pw.lastRoomRadius = room.radius;
						RoomEvents.send(p, 0, Payloads.RoomFx.ARRIVE, room.center, null, (float) room.radius, 0f, 0, 0);
					}
					PowerManager.broadcastRoom(p, 0, room);
				} else if (--room.flyTicks <= 0) {
					// lanzado: en vuelo no se manda nada (el cometa lo dibuja el cliente); al caer, el ROOM salta allí
					Vec3d old = room.center;
					double oldR = room.radius;
					room.center = room.flyTo != null ? room.flyTo : old;
					if (room.castRadius > 0) room.radius = room.castRadius;
					room.state = Room.State.FIXED;
					pw.lastRoomRadius = room.radius;
					RoomEvents.send(p, 0, Payloads.RoomFx.LAND, room.center, old, (float) room.radius,
						room.fresh ? 0f : (float) oldR, 0, room.fresh ? 0 : Payloads.RoomFx.F_OLD);
					room.fresh = false;
					PowerManager.broadcastRoom(p, 0, room);
				}
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
