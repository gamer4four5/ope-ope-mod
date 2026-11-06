package dev.opeope.client.room;

import dev.opeope.client.config.OpeSettings;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.math.RotationAxis;

/**
 * La cámara del ROOM (spec §5.6): un cabeceo con muelle (positivo = la vista baja), un temblor que se apaga solo y
 * golpes de campo de visión. Entra por {@code GameRendererMixin}: el giro va a la proyección (la mira no se mueve) y el
 * campo de visión se suma al del juego.
 * <p>Se integra con el tiempo real de cada llamada (0 en pausa, como mucho 0,05 s de golpe): las dos llamadas de un
 * mismo fotograma (mundo y mano) solo se reparten el paso. Cabeceo y temblor se multiplican por
 * {@link OpeSettings#shakeScale()}; el campo de visión, por {@link OpeSettings#fovKickScale()}. Con «menos
 * movimiento» las dos escalas valen 0.
 */
public final class CameraFx {
	private CameraFx() {}

	private static final double K = 65, D = 9, IMPULSE = 15.6;
	private static final int MAX_FOV = 6;

	private static double x, v;              // el cabeceo (grados) y su velocidad
	private static double trauma, traumaFloor;
	private static double t;                 // reloj del temblor
	private static long lastNanos;

	private static final float[] FOV_AMP = new float[MAX_FOV];
	private static final float[] FOV_RISE = new float[MAX_FOV];
	private static final float[] FOV_FALL = new float[MAX_FOV];
	private static final double[] FOV_AGE = new double[MAX_FOV];
	private static final boolean[] FOV_ON = new boolean[MAX_FOV];

	// ------------------------------------------------------------------ órdenes

	/** Un golpe de cabeceo con pico de unos peakDeg grados a los 0,15 s (positivo = la vista baja). */
	public static void kick(float peakDeg) {
		step();
		v += IMPULSE * peakDeg;
	}

	/** Suma temblor (0..1). */
	public static void trauma(float add) {
		step();
		trauma = Math.min(1.0, trauma + add);
	}

	/** Un mínimo de temblor mientras dure (el ROOM propio creciendo: 0,22). */
	public static void traumaFloor(float floor) {
		traumaFloor = Math.max(0, Math.min(1, floor));
	}

	/** Un golpe de campo de visión: sube en rise s (easeOutCubic) y vuelve en fall s (smoothstep). */
	public static void fov(float deg, float rise, float fall) {
		step();
		int slot = -1;
		double oldest = -1;
		for (int i = 0; i < MAX_FOV; i++) {
			if (!FOV_ON[i]) {
				slot = i;
				break;
			}
			if (FOV_AGE[i] > oldest) {
				oldest = FOV_AGE[i];
				slot = i;
			}
		}
		FOV_ON[slot] = true;
		FOV_AMP[slot] = deg;
		FOV_RISE[slot] = Math.max(0.01f, rise);
		FOV_FALL[slot] = Math.max(0.01f, fall);
		FOV_AGE[slot] = 0;
	}

	public static void clear() {
		x = 0;
		v = 0;
		trauma = 0;
		traumaFloor = 0;
		for (int i = 0; i < MAX_FOV; i++) FOV_ON[i] = false;
	}

	// ------------------------------------------------------------------ lo que lee el mixin

	/** Lo que se suma al campo de visión del juego (grados). */
	public static double fovOffset(float tickDelta) {
		step();
		float scale = OpeSettings.fovKickScale();
		if (scale <= 0f) return 0.0;
		double sum = 0;
		for (int i = 0; i < MAX_FOV; i++) {
			if (!FOV_ON[i]) continue;
			double age = FOV_AGE[i];
			float rise = FOV_RISE[i], fall = FOV_FALL[i];
			double k;
			if (age < rise) {
				double u = age / rise;
				k = 1 - (1 - u) * (1 - u) * (1 - u);
			} else {
				double u = Math.min(1.0, (age - rise) / fall);
				k = 1 - u * u * (3 - 2 * u);
			}
			sum += FOV_AMP[i] * k;
		}
		return sum * scale;
	}

	/** Gira la vista: cabeceo y temblor (X), guiñada (Y) y alabeo (Z), en grados. */
	public static void apply(MatrixStack matrices, float tickDelta) {
		step();
		float scale = OpeSettings.shakeScale();
		if (scale <= 0f) return;
		double tt = trauma * trauma;
		double tau = 2 * Math.PI;
		double pitch = x, yaw = 0, roll = 0;
		if (tt > 1e-5) {
			pitch += 2.5 * tt * (0.6 * Math.sin(tau * 7.3 * t) + 0.4 * Math.sin(tau * 13.1 * t + 1.3));
			yaw = 2.5 * tt * (0.6 * Math.sin(tau * 6.1 * t + 0.7) + 0.4 * Math.sin(tau * 11.7 * t));
			roll = 4.0 * tt * (0.6 * Math.sin(tau * 4.7 * t + 2.1) + 0.4 * Math.sin(tau * 9.9 * t));
		}
		pitch *= scale;
		yaw *= scale;
		roll *= scale;
		if (Math.abs(pitch) > 1e-4) matrices.multiply(RotationAxis.POSITIVE_X.rotationDegrees((float) pitch));
		if (Math.abs(yaw) > 1e-4) matrices.multiply(RotationAxis.POSITIVE_Y.rotationDegrees((float) yaw));
		if (Math.abs(roll) > 1e-4) matrices.multiply(RotationAxis.POSITIVE_Z.rotationDegrees((float) roll));
	}

	// ------------------------------------------------------------------ integración

	/** Avanza con el tiempo real desde la última llamada (0 en pausa, como mucho 0,05 s). */
	static void step() {
		long now = System.nanoTime();
		double dt = lastNanos == 0 ? 0 : (now - lastNanos) / 1e9;
		lastNanos = now;
		if (dt <= 0) return;
		if (dt > 0.05) dt = 0.05;
		MinecraftClient mc = MinecraftClient.getInstance();
		if (mc == null || mc.isPaused()) return;
		// el muelle, en pasos pequeños para que sea estable
		int n = (int) Math.ceil(dt / 0.004);
		double h = dt / n;
		for (int i = 0; i < n; i++) {
			double a = -K * x - D * v;
			v += a * h;
			x += v * h;
		}
		if (Math.abs(x) < 1e-5 && Math.abs(v) < 1e-4) {
			x = 0;
			v = 0;
		}
		trauma = Math.max(traumaFloor, trauma - 1.3 * dt);
		if (trauma < 1e-4) trauma = 0;
		t += dt;
		if (t > 1000) t -= 1000;
		for (int i = 0; i < MAX_FOV; i++) {
			if (!FOV_ON[i]) continue;
			FOV_AGE[i] += dt;
			if (FOV_AGE[i] >= FOV_RISE[i] + FOV_FALL[i]) FOV_ON[i] = false;
		}
	}
}
