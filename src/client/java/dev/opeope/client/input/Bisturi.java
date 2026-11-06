package dev.opeope.client.input;

import dev.opeope.power.Actions;
import net.minecraft.client.MinecraftClient;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RaycastContext;

import java.util.ArrayList;
import java.util.List;

/**
 * El Bisturí (clic derecho mantenido con la mano vacía y un ROOM): el tiempo va al 12 % y una superficie marca el plano.
 * El plano pasa por el punto que señalas. Rueda lo gira, Mayús + rueda lo ladea, Ctrl + rueda lo mueve. Cada clic
 * izquierdo corta ya (hasta 16 planos) y lo cortado se queda quieto; al soltar, tras 0,3 s, cae todo a la vez.
 */
public final class Bisturi {
	private Bisturi() {}

	public static boolean active;
	public static float roll, tilt;
	public static double offset;
	public static final List<double[]> COMMITTED = new ArrayList<>();

	public static void start() {
		active = true;
		roll = 0;
		tilt = 0;
		offset = 0;
		COMMITTED.clear();
		Send.action(Actions.BISTURI, 1);
	}

	/** n (3), punto (3), eje del tajo (3), mirada en el plano (3). */
	public static double[] current(float tickDelta) {
		MinecraftClient mc = MinecraftClient.getInstance();
		if (mc.player == null) return null;
		Vec3d eye = mc.player.getCameraPosVec(tickDelta);
		Vec3d look = mc.player.getRotationVec(tickDelta);
		Vec3d right = look.crossProduct(new Vec3d(0, 1, 0));
		if (right.lengthSquared() < 1e-4) right = new Vec3d(1, 0, 0);
		right = right.normalize();
		Vec3d up = right.crossProduct(look).normalize();
		double r = Math.toRadians(roll), t = Math.toRadians(tilt);
		Vec3d s = right.multiply(Math.cos(r)).add(up.multiply(Math.sin(r)));
		Vec3d n0 = look.crossProduct(s).normalize();
		Vec3d n = n0.multiply(Math.cos(t)).add(s.crossProduct(n0).multiply(Math.sin(t))).normalize();
		// el plano pasa por el punto que señalas (ladearlo gira alrededor de ese punto)
		Vec3d p = aim(mc, eye, look).add(n.multiply(offset));
		Vec3d inPlane = look.subtract(n.multiply(look.dotProduct(n))).normalize();
		return new double[]{n.x, n.y, n.z, p.x, p.y, p.z, s.x, s.y, s.z, inPlane.x, inPlane.y, inPlane.z};
	}

	/** Lo que señalas: el primer bloque en la mira (hasta 96 m) o, si no hay, 8 m delante. */
	private static Vec3d aim(MinecraftClient mc, Vec3d eye, Vec3d look) {
		if (mc.world == null || mc.player == null) return eye.add(look.multiply(8));
		Vec3d end = eye.add(look.multiply(96));
		BlockHitResult hit = mc.world.raycast(new RaycastContext(eye, end, RaycastContext.ShapeType.OUTLINE, RaycastContext.FluidHandling.NONE, mc.player));
		if (hit == null || hit.getType() == HitResult.Type.MISS) return eye.add(look.multiply(8));
		return hit.getPos();
	}

	/** Cada clic corta ya: lo cortado se queda quieto en su sitio hasta que sueltas el clic derecho. */
	public static void addPlane() {
		if (COMMITTED.size() >= 16) return;
		double[] c = current(1f);
		if (c == null) return;
		COMMITTED.add(c);
		Send.cut(3, new float[]{(float) c[0], (float) c[1], (float) c[2], (float) c[3], (float) c[4], (float) c[5]});
		MinecraftClient mc = MinecraftClient.getInstance();
		if (mc.player != null) mc.player.playSound(SoundEvents.BLOCK_AMETHYST_BLOCK_HIT, 1f, 1.6f + COMMITTED.size() * 0.03f);
	}

	public static void scroll(int dir, boolean shift, boolean ctrl) {
		if (ctrl) offset = Math.max(-12, Math.min(12, offset + dir * 0.5));
		else if (shift) tilt = (tilt + dir * 15) % 360;
		else roll = (roll + dir * 15) % 360;
	}

	public static void commit() {
		if (!active) return;
		active = false;
		boolean cutAlready = !COMMITTED.isEmpty();
		COMMITTED.clear();
		if (!cutAlready) {
			// sin clics: al soltar corta el plano que tienes puesto
			double[] c = current(1f);
			if (c != null) Send.cut(1, new float[]{(float) c[0], (float) c[1], (float) c[2], (float) c[3], (float) c[4], (float) c[5]});
		}
		Send.action(Actions.BISTURI, 0);   // y lo que estaba quieto cae todo a la vez
	}

	public static void cancel() {
		if (!active) return;
		active = false;
		COMMITTED.clear();
		Send.action(Actions.BISTURI, 0);
	}
}
