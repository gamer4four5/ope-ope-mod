package dev.opeope.client.input;

import dev.opeope.power.Actions;
import net.minecraft.client.MinecraftClient;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayList;
import java.util.List;

/**
 * El Bisturí (clic derecho mantenido con la mano vacía y un ROOM): el tiempo va al 12 % y una superficie marca el plano.
 * Rueda lo gira, Mayús + rueda lo ladea, Ctrl + rueda lo mueve. Cada clic izquierdo encadena un plano (hasta 16);
 * al soltar, cae todo a la vez.
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
		Vec3d p = eye.add(n.multiply(offset));
		Vec3d inPlane = look.subtract(n.multiply(look.dotProduct(n))).normalize();
		return new double[]{n.x, n.y, n.z, p.x, p.y, p.z, s.x, s.y, s.z, inPlane.x, inPlane.y, inPlane.z};
	}

	public static void addPlane() {
		if (COMMITTED.size() >= 16) return;
		double[] c = current(1f);
		if (c == null) return;
		COMMITTED.add(c);
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
		List<double[]> planes = new ArrayList<>(COMMITTED);
		if (planes.isEmpty()) {
			double[] c = current(1f);
			if (c != null) planes.add(c);
		}
		COMMITTED.clear();
		float[] out = new float[planes.size() * 6];
		for (int i = 0; i < planes.size(); i++) for (int k = 0; k < 6; k++) out[i * 6 + k] = (float) planes.get(i)[k];
		Send.cut(1, out);
		Send.action(Actions.BISTURI, 0);
	}

	public static void cancel() {
		if (!active) return;
		active = false;
		COMMITTED.clear();
		Send.action(Actions.BISTURI, 0);
	}
}
