package dev.opeope.client.hud;

import dev.opeope.client.config.OpeSettings;

/** Curvas y pequeñas ayudas de animación. Los tiempos son de reloj (System.currentTimeMillis). */
public final class Anim {
	private Anim() {}

	public static float clamp01(float t) {
		return t < 0f ? 0f : (t > 1f ? 1f : t);
	}

	public static float lerp(float a, float b, float t) {
		return a + (b - a) * t;
	}

	public static float easeOutCubic(float t) {
		float u = 1f - clamp01(t);
		return 1f - u * u * u;
	}

	public static float smoothstep(float t) {
		t = clamp01(t);
		return t * t * (3f - 2f * t);
	}

	public static float easeOutBack(float t) {
		t = clamp01(t);
		float c1 = 1.70158f, c3 = c1 + 1f, u = t - 1f;
		return 1f + c3 * u * u * u + c1 * u * u;
	}

	/** Menos movimiento (Ajustes): sin sacudidas, deslizamientos ni escalados. */
	public static boolean reduced() {
		return OpeSettings.reducedMotion();
	}

	/** 0..1 de lo que ha pasado de una animación de durMs que empezó en start (1 si nunca empezó). */
	public static float progress(long now, long start, long durMs) {
		if (start <= 0 || durMs <= 0) return 1f;
		return clamp01((now - start) / (float) durMs);
	}

	/** ¿Sigue viva una animación de durMs que empezó en start? */
	public static boolean running(long now, long start, long durMs) {
		return start > 0 && now >= start && now - start < durMs;
	}

	/** Alfa de algo que vive lifeMs: entra en inMs y se apaga en los últimos outMs. */
	public static float envelope(long age, long lifeMs, long inMs, long outMs) {
		if (age < 0 || age >= lifeMs) return 0f;
		float a = 1f;
		if (inMs > 0 && age < inMs) a = Math.min(a, age / (float) inMs);
		if (outMs > 0 && age > lifeMs - outMs) a = Math.min(a, (lifeMs - age) / (float) outMs);
		return clamp01(a);
	}

	/** Acerca v a target como mucho step. */
	public static float approach(float v, float target, float step) {
		if (v < target) return Math.min(target, v + step);
		return Math.max(target, v - step);
	}

	/** Oscilación 0..1 a hz por segundo. */
	public static float pulse(long now, float hz) {
		return 0.5f + 0.5f * (float) Math.sin(now / 1000.0 * hz * 2.0 * Math.PI);
	}
}
