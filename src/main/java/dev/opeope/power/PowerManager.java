package dev.opeope.power;

import dev.opeope.OpeConfig;
import dev.opeope.net.Payloads;
import dev.opeope.registry.ModAttachments;
import dev.opeope.registry.ModItems;
import net.fabricmc.fabric.api.entity.event.v1.ServerEntityWorldChangeEvents;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.EntityTrackingEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.entity.Entity;
import net.minecraft.entity.ItemEntity;
import net.minecraft.entity.boss.WitherEntity;
import net.minecraft.entity.boss.dragon.EnderDragonEntity;
import net.minecraft.entity.mob.ElderGuardianEntity;
import net.minecraft.entity.mob.WardenEntity;
import net.minecraft.entity.projectile.ProjectileUtil;
import net.minecraft.item.ItemStack;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.EntityHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RaycastContext;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Predicate;

public final class PowerManager {
	private PowerManager() {}

	private static final Map<UUID, PlayerPower> POWERS = new HashMap<>();

	public static PlayerPower get(ServerPlayerEntity p) {
		return POWERS.computeIfAbsent(p.getUuid(), u -> new PlayerPower());
	}

	public static PlayerPower peek(UUID id) {
		return POWERS.get(id);
	}

	public static void init() {
		ServerTickEvents.END_SERVER_TICK.register(PowerManager::tick);
		ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
			ServerPlayerEntity p = handler.getPlayer();
			get(p).dirty = true;
			sendAllRooms(p);
		});
		ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> cleanup(handler.getPlayer()));
		ServerPlayerEvents.AFTER_RESPAWN.register((oldPlayer, newPlayer, alive) -> {
			PlayerPower pw = get(newPlayer);
			releaseAll(newPlayer, pw);
			pw.dirty = true;
			sendAllRooms(newPlayer);
		});
		ServerEntityWorldChangeEvents.AFTER_PLAYER_CHANGE_WORLD.register((player, origin, destination) -> {
			PlayerPower pw = get(player);
			releaseAll(player, pw);
			RoomOps.remove(player, pw);
			sendAllRooms(player);
		});
		EntityTrackingEvents.START_TRACKING.register((entity, player) -> CutStates.onStartTracking(entity, player));
		ServerLivingEntityEvents.AFTER_DEATH.register((entity, source) -> {
			Entity attacker = source.getAttacker();
			if (attacker instanceof ServerPlayerEntity sp && ModAttachments.hasFruit(sp)
				&& (entity instanceof EnderDragonEntity || entity instanceof WitherEntity)) {
				grantAwakening(sp);
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
		});
	}

	public static void grantFruit(ServerPlayerEntity p) {
		if (ModAttachments.hasFruit(p)) {
			p.sendMessage(Text.translatable("opeope.msg.already"), false);
			return;
		}
		p.setAttached(ModAttachments.HAS_FRUIT, true);
		get(p).dirty = true;
		p.sendMessage(Text.translatable("opeope.msg.ate"), false);
		p.sendMessage(Text.translatable("opeope.msg.ate_help"), false);
		p.getWorld().playSound(null, p.getX(), p.getY(), p.getZ(), SoundEvents.BLOCK_BEACON_ACTIVATE, SoundCategory.PLAYERS, 1f, 0.7f);
	}

	public static void removeFruit(ServerPlayerEntity p) {
		PlayerPower pw = get(p);
		releaseAll(p, pw);
		RoomOps.remove(p, pw);
		p.removeAttached(ModAttachments.HAS_FRUIT);
		p.removeAttached(ModAttachments.AWAKENING);
		pw.awakened = false;
		pw.dirty = true;
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

	/** Suelta todo lo de Takt, las armas y lo suspendido (al morir, cambiar de mundo o salir). */
	public static void releaseAll(ServerPlayerEntity p, PlayerPower pw) {
		Takt.dropAll(p, pw, false);
		Forge.dropWeapons(p, pw);
		Techniques.releaseSuspended(p, pw);
	}

	private static void tick(MinecraftServer server) {
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
		int weapon = pw.weapons.isEmpty() ? -1 : pw.weaponKind;
		ServerPlayNetworking.send(p, new Payloads.State(flags, pw.pattern, pw.takt.size(), pw.taktMode.ordinal(), weapon, pw.scanTicks, pw.cooldowns.clone()));
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

	public static BlockHitResult raycastBlock(ServerPlayerEntity p, double range) {
		Vec3d eye = p.getEyePos();
		Vec3d end = eye.add(p.getRotationVector().multiply(range));
		return p.getWorld().raycast(new RaycastContext(eye, end, RaycastContext.ShapeType.OUTLINE, RaycastContext.FluidHandling.NONE, p));
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
		if (kind == 1 && pw.bisturi) Techniques.bisturi(p, pw, false);
		Room room = pw.room;
		if (room == null || room.dim != p.getWorld().getRegistryKey()) {
			noRoom(p);
			return;
		}
		if (kind != 1 && pw.onCooldown(Tech.AMPUTATE)) return;
		List<CutEngine.Plane> planes = new ArrayList<>();
		for (int i = 0; i + 5 < raw.length; i += 6) {
			Vec3d n = new Vec3d(raw[i], raw[i + 1], raw[i + 2]);
			if (n.lengthSquared() < 1e-6 || !Double.isFinite(n.lengthSquared())) continue;
			Vec3d point = new Vec3d(raw[i + 3], raw[i + 4], raw[i + 5]);
			if (point.squaredDistanceTo(p.getEyePos()) > 400) point = p.getEyePos();
			planes.add(new CutEngine.Plane(n.normalize(), point));
		}
		if (planes.isEmpty()) return;
		if (kind == 0) planes = Patterns.expand(p, planes.get(0), pw.pattern);
		Vec3d eye = p.getEyePos();
		Vec3d look = p.getRotationVector();
		CutEngine.Region region = CutEngine.Region.of(room).withEye(eye, look);
		if (kind == 2) {
			region.withLimit(eye, 6.0);            // la Kikoku corta lo que alcanza el filo
		} else if (pw.reachTarget) {
			Entity target = raycastEntity(p, 200, 0.2, e -> e.isAlive());
			Vec3d at = target != null ? target.getBoundingBox().getCenter() : aimPoint(p, 200);
			region.withLimit(at, target != null ? Math.max(2.5, target.getBoundingBox().getAverageSideLength() * 1.5) : 4.0);
		}
		CutEngine.cut(p, planes, region, pw.suspend, 0, 0f);
		if (kind != 1) pw.cooldown(Tech.AMPUTATE);
		pw.roomUsedDuringHold = true;
	}

	public static int roomMaxRadius() {
		return (int) OpeConfig.ROOM_MAX_RADIUS;
	}
}
