package dev.opeope.power;

import dev.opeope.net.Payloads;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.entity.Entity;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.Vec3d;

/** Envía los efectos 3D de las técnicas de Law (ver {@link Payloads.LawFx}) a los jugadores que los pueden ver. */
public final class LawFx {
	private LawFx() {}

	private static final double RANGE = 200.0;

	public static void send(ServerWorld w, int kind, Entity user, Entity target, Vec3d a, Vec3d b, float radius, float power, int ticks) {
		Payloads.LawFx fx = new Payloads.LawFx(kind, user != null ? user.getId() : -1, target != null ? target.getId() : -1,
			a.x, a.y, a.z, b.x, b.y, b.z, radius, power, ticks);
		double r2 = RANGE * RANGE;
		for (ServerPlayerEntity sp : w.getPlayers()) {
			if (sp.squaredDistanceTo(a) <= r2 || sp.squaredDistanceTo(b) <= r2) ServerPlayNetworking.send(sp, fx);
		}
	}

	public static void send(ServerPlayerEntity p, int kind, Entity target, Vec3d a, Vec3d b, float radius, float power, int ticks) {
		send(p.getServerWorld(), kind, p, target, a, b, radius, power, ticks);
	}

	/** Donde está la mano que hace el gesto (algo delante y a la derecha de los ojos). */
	public static Vec3d hand(ServerPlayerEntity p) {
		Vec3d look = p.getRotationVector();
		Vec3d right = look.crossProduct(new Vec3d(0, 1, 0));
		if (right.lengthSquared() < 1e-4) right = new Vec3d(1, 0, 0);
		right = right.normalize();
		return p.getEyePos().add(look.multiply(0.7)).add(right.multiply(0.32)).add(0, -0.32, 0);
	}
}
