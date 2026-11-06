package dev.opeope.client.room;

import dev.opeope.client.config.OpeSettings;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.render.RenderTickCounter;
import net.minecraft.util.math.RotationAxis;

/**
 * La pantalla del ROOM (spec §5.7): un tinte azul en los bordes, un destello y rayas de velocidad. Se dibuja al principio
 * del HUD de Minecraft ({@code InGameHudMixin}), debajo de todo lo demás, en unidades escaladas del juego.
 * <p>Destellos (Ajustes): 2 tal cual; 1 destello como mucho 0,05 y borde como mucho 0,2; 0 sin destello y borde como
 * mucho 0,2. Con F1 solo se dibuja el destello. Con «menos movimiento» no hay rayas.
 */
public final class ScreenFx {
	private ScreenFx() {}

	private static final int EDGE_RGB = 0x8FD8FF, FLASH_RGB = 0xE8FBFF, STREAK_RGB = 0xBFF4FF;
	private static final int MAX_PULSES = 4, STREAKS = 10;

	private static final float[] PULSE_PEAK = new float[MAX_PULSES];
	private static final double[] PULSE_START = new double[MAX_PULSES];
	private static final float[] PULSE_DUR = new float[MAX_PULSES];
	private static float steadyTarget, steady;
	private static double lastFrame = -1;

	private static float flashPeak;
	private static double flashStart = -100;
	private static float flashDur = 1;

	private static double streakStart = -100;
	private static final float[] STREAK_ANGLE = new float[STREAKS];
	private static final float[] STREAK_LEN = new float[STREAKS];
	private static final java.util.Random RNG = new java.util.Random();

	// ------------------------------------------------------------------ órdenes

	/** El borde se enciende a peak y se apaga en seconds. */
	public static void edgePulse(float peak, float seconds) {
		double now = RoomFxClient.time();
		int slot = 0;
		double oldest = Double.MAX_VALUE;
		for (int i = 0; i < MAX_PULSES; i++) {
			double end = PULSE_START[i] + PULSE_DUR[i];
			if (PULSE_PEAK[i] <= 0 || end <= now) {
				slot = i;
				break;
			}
			if (PULSE_START[i] < oldest) {
				oldest = PULSE_START[i];
				slot = i;
			}
		}
		PULSE_PEAK[slot] = peak;
		PULSE_START[slot] = now;
		PULSE_DUR[slot] = Math.max(0.05f, seconds);
	}

	/** El borde fijo (0,08 mientras estás dentro de tu ROOM); se acerca a ese valor poco a poco. */
	public static void steadyEdge(float value) {
		steadyTarget = value;
	}

	/** Un destello de pantalla completa que se apaga en línea recta en seconds. */
	public static void flash(float alpha, float seconds) {
		double now = RoomFxClient.time();
		float left = currentFlash(now);
		if (alpha < left) return;   // ya hay uno más fuerte
		flashPeak = alpha;
		flashStart = now;
		flashDur = Math.max(0.05f, seconds);
	}

	/** Diez rayas que salen del centro (al lanzarlo). */
	public static void streaks() {
		if (OpeSettings.reducedMotion()) return;
		streakStart = RoomFxClient.time();
		float base = RNG.nextFloat() * (float) (Math.PI * 2);
		for (int i = 0; i < STREAKS; i++) {
			STREAK_ANGLE[i] = base + (float) (Math.PI * 2 * i / STREAKS) + (RNG.nextFloat() - 0.5f) * 0.35f;
			STREAK_LEN[i] = 30 + RNG.nextFloat() * 30;
		}
	}

	public static void clear() {
		for (int i = 0; i < MAX_PULSES; i++) PULSE_PEAK[i] = 0;
		steadyTarget = 0;
		steady = 0;
		flashPeak = 0;
		flashStart = -100;
		streakStart = -100;
	}

	private static float currentFlash(double now) {
		double age = now - flashStart;
		if (flashPeak <= 0 || age < 0 || age >= flashDur) return 0f;
		return (float) (flashPeak * (1 - age / flashDur));
	}

	// ------------------------------------------------------------------ dibujo

	@SuppressWarnings("deprecation")
	public static void render(DrawContext ctx, RenderTickCounter tickCounter) {
		MinecraftClient mc = MinecraftClient.getInstance();
		if (mc.player == null || mc.world == null) return;
		double now = RoomFxClient.time();
		double dt = lastFrame < 0 ? 0 : Math.max(0, Math.min(0.1, now - lastFrame));
		lastFrame = now;
		// el borde fijo se acerca a su valor a 4/s
		float stepK = (float) Math.min(1.0, dt * 4.0);
		steady += (steadyTarget - steady) * stepK;

		int level = OpeSettings.flashLevel();
		int w = ctx.getScaledWindowWidth(), h = ctx.getScaledWindowHeight();
		float flash = currentFlash(now);
		if (level == 1) flash = Math.min(flash, 0.05f);
		else if (level == 0) flash = 0f;

		float edge = steady;
		for (int i = 0; i < MAX_PULSES; i++) {
			if (PULSE_PEAK[i] <= 0) continue;
			double age = now - PULSE_START[i];
			if (age < 0) continue;
			if (age >= PULSE_DUR[i]) {
				PULSE_PEAK[i] = 0;
				continue;
			}
			edge += (float) (PULSE_PEAK[i] * (1 - age / PULSE_DUR[i]));
		}
		edge = Math.min(edge, level >= 2 ? 0.6f : 0.2f);

		double streakAge = now - streakStart;
		boolean streaksOn = streakAge >= 0 && streakAge < 0.3 && !OpeSettings.reducedMotion();
		boolean hud = !mc.options.hudHidden;

		if (flash < 0.004f && (!hud || (edge < 0.004f && !streaksOn))) return;
		final float fFlash = flash, fEdge = hud ? edge : 0f;
		final boolean fStreaks = hud && streaksOn;
		final float fStreakAge = (float) streakAge;
		ctx.draw(() -> {
			if (fEdge >= 0.004f) edges(ctx, w, h, fEdge);
			if (fStreaks) streakLines(ctx, w, h, fStreakAge);
			if (fFlash >= 0.004f) ctx.fill(0, 0, w, h, argb(fFlash, FLASH_RGB));
		});
	}

	/** El tinte del borde: arriba y abajo con degradados, a los lados en franjas de 1; cae como (1 − i/T)². */
	private static void edges(DrawContext ctx, int w, int h, float e) {
		int band = Math.max(12, Math.round(Math.min(w, h) * 0.10f));
		// arriba y abajo: tres tramos lineales que siguen la curva (1 − i/T)²
		int t1 = band / 3, t2 = 2 * band / 3;
		int a0 = argb(e, EDGE_RGB), a1 = argb(e * 0.444f, EDGE_RGB), a2 = argb(e * 0.111f, EDGE_RGB), a3 = argb(0f, EDGE_RGB);
		ctx.fillGradient(0, 0, w, t1, a0, a1);
		ctx.fillGradient(0, t1, w, t2, a1, a2);
		ctx.fillGradient(0, t2, w, band, a2, a3);
		ctx.fillGradient(0, h - band, w, h - t2, a3, a2);
		ctx.fillGradient(0, h - t2, w, h - t1, a2, a1);
		ctx.fillGradient(0, h - t1, w, h, a1, a0);
		// los lados, sin pisar las esquinas ya pintadas
		for (int i = 0; i < band; i++) {
			float k = 1f - i / (float) band;
			int c = argb(e * k * k, EDGE_RGB);
			if ((c >>> 24) == 0) break;
			ctx.fill(i, band, i + 1, h - band, c);
			ctx.fill(w - i - 1, band, w - i, h - band, c);
		}
	}

	/** Rayas de velocidad: salen del centro hacia fuera en 0,3 s y se apagan. */
	private static void streakLines(DrawContext ctx, int w, int h, float age) {
		float u = Math.max(0f, Math.min(1f, age / 0.3f));
		float cx = w / 2f, cy = h / 2f;
		float reach = (float) Math.sqrt(cx * cx + cy * cy);
		int col = argb(0.35f * (1f - u), STREAK_RGB);
		if ((col >>> 24) == 0) return;
		for (int i = 0; i < STREAKS; i++) {
			float len = STREAK_LEN[i];
			int x0 = Math.round(18 + (reach - 18) * (0.15f + 0.85f * u) - len);
			ctx.getMatrices().push();
			try {
				ctx.getMatrices().translate(cx, cy, 0);
				ctx.getMatrices().multiply(RotationAxis.POSITIVE_Z.rotation(STREAK_ANGLE[i]));
				ctx.fill(Math.max(12, x0), 0, Math.max(13, x0 + Math.round(len)), 1, col);
			} finally {
				ctx.getMatrices().pop();
			}
		}
	}

	private static int argb(float alpha, int rgb) {
		int a = Math.round(Math.max(0f, Math.min(1f, alpha)) * 255f);
		return (a << 24) | (rgb & 0xFFFFFF);
	}
}
