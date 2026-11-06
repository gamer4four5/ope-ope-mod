package dev.opeope.client.input;

import dev.opeope.OpeOpeMod;
import dev.opeope.client.ClientState;
import dev.opeope.client.config.OpeProgress;
import dev.opeope.client.config.OpeSettings;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.sound.PositionedSoundInstance;
import net.minecraft.sound.SoundEvent;

import java.util.Arrays;

/**
 * Los primeros pasos con la fruta: seis cosas que se aprenden haciéndolas (abrir el ROOM, cambiarle el tamaño, cortar,
 * Shambles, Takt y la segunda fila). Cada paso se marca solo cuando lo haces; el HUD dibuja la tarjeta.
 */
public final class Onboarding {
	private Onboarding() {}

	public static final int STEPS = 6;
	public static final int STEP_ROOM = 0, STEP_RESIZE = 1, STEP_CUT = 2, STEP_SHAMBLES = 3, STEP_TAKT = 4, STEP_ROW2 = 5;

	private static final long INTRO_MS = 10_000;
	private static final long DONE_MS = 6_000;

	private static final long[] DONE_AT = new long[STEPS];
	private static long finishedAt;
	private static long firstShownAt;
	private static boolean suppressed;
	// el paso del ROOM: pulsar la tecla y ver un ROOM nuevo ya fijo (uno que ya estaba fijo no cuenta)
	private static boolean roomKeyPressed;
	private static boolean fixedAtPress;
	private static boolean sawGrowing;

	/** Se ve mientras tienes la fruta, no la has terminado ni saltado y las pistas no están en «Nunca». */
	public static boolean showing() {
		if (suppressed || !ClientState.hasFruit() || OpeSettings.get().pistas == OpeSettings.Pistas.NUNCA) return false;
		return !OpeProgress.onboardingFinished() || finishedRecently();
	}

	/** La presentación: los 10 primeros segundos, o hasta que haces el primer paso. */
	public static boolean intro() {
		if (!showing() || OpeProgress.onboardingFinished() || done(STEP_ROOM)) return false;
		return firstShownAt == 0 || System.currentTimeMillis() - firstShownAt < INTRO_MS;
	}

	/** «¡LISTO!» durante 6 s tras el sexto paso. */
	public static boolean finishedRecently() {
		return finishedAt > 0 && System.currentTimeMillis() - finishedAt < DONE_MS;
	}

	public static boolean done(int step) {
		return step >= 0 && step < STEPS && (OpeProgress.onboardingMask() & (1 << step)) != 0;
	}

	/** Cuándo se hizo el paso (ms de reloj); 0 si no está hecho, 1 si se hizo en otra partida. */
	public static long doneAt(int step) {
		if (step < 0 || step >= STEPS) return 0;
		if (DONE_AT[step] != 0) return DONE_AT[step];
		return done(step) ? 1 : 0;
	}

	public static int doneCount() {
		return Integer.bitCount(OpeProgress.onboardingMask() & ((1 << STEPS) - 1));
	}

	/** El primer paso sin hacer, o STEPS si están todos. */
	public static int current() {
		for (int i = 0; i < STEPS; i++) if (!done(i)) return i;
		return STEPS;
	}

	public static void mark(int step) {
		if (step < 0 || step >= STEPS || done(step) || OpeProgress.onboardingFinished() || !showing()) return;
		long now = System.currentTimeMillis();
		OpeProgress.setOnboardingMask(OpeProgress.onboardingMask() | (1 << step));
		DONE_AT[step] = now;
		MinecraftClient mc = MinecraftClient.getInstance();
		mc.getSoundManager().play(PositionedSoundInstance.master(SoundEvent.of(OpeOpeMod.id("ui.paso")), 1.0f, 0.7f));
		if (doneCount() >= STEPS) {
			OpeProgress.setOnboardingFinished(true);
			finishedAt = now;
			OpeProgress.save();
		}
	}

	/** «Primeros pasos: repetir». */
	public static void restart() {
		OpeProgress.setOnboardingMask(0);
		OpeProgress.setOnboardingFinished(false);
		Arrays.fill(DONE_AT, 0);
		finishedAt = 0;
		firstShownAt = 0;
		roomKeyPressed = false;
		fixedAtPress = false;
		sawGrowing = false;
		OpeProgress.save();
	}

	/** «Saltar los primeros pasos». */
	public static void skip() {
		OpeProgress.setOnboardingFinished(true);
		finishedAt = 0;
		OpeProgress.save();
	}

	public static void suppressForTest(boolean on) {
		suppressed = on;
	}

	/** InputHandler avisa al pulsar la tecla del ROOM. */
	static void noteRoomKey() {
		ClientState.RoomInfo r = ClientState.ownRoom();
		roomKeyPressed = true;
		fixedAtPress = r != null && r.state() == 1;
		sawGrowing = false;
	}

	public static void tick() {
		MinecraftClient mc = MinecraftClient.getInstance();
		if (mc.player == null) return;
		boolean show = showing();
		if (show && firstShownAt == 0) firstShownAt = System.currentTimeMillis();
		if (!show || !roomKeyPressed || done(STEP_ROOM)) return;
		ClientState.RoomInfo r = ClientState.ownRoom();
		if (r == null) {
			fixedAtPress = false;
			return;
		}
		if (r.state() == 0) sawGrowing = true;
		if (r.state() == 1 && (!fixedAtPress || sawGrowing)) mark(STEP_ROOM);
	}
}
