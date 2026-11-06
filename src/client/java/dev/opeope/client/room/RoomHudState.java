package dev.opeope.client.room;

import dev.opeope.net.Payloads;
import net.minecraft.client.MinecraftClient;

/**
 * Lo que el HUD necesita saber del ROOM propio (spec §5.3): el último momento de cada tipo, el radio que se ve, si va
 * volando o volviendo, a qué distancia, si está creciendo y si estás dentro. Solo cuenta el ROOM propio (slot 0).
 * Los tiempos son de reloj (System.currentTimeMillis()); 0 = nunca.
 */
public final class RoomHudState {
	private RoomHudState() {}

	private static final long[] LAST = new long[Payloads.RoomFx.KINDS];
	private static int lastKind = -1;
	private static long lastAt;
	private static boolean casting, recalling, inside;
	private static float castDist, landDist;
	private static int resizeDir, limitKind = -1;
	private static int castDeadline, recallDeadline;

	// ------------------------------------------------------------------ lo que lee el HUD

	public static long lastEventMs(int kind) {
		return kind >= 0 && kind < LAST.length ? LAST[kind] : 0L;
	}

	/** El tipo del último momento (−1 si aún no hubo ninguno). */
	public static int lastEventKind() {
		return lastKind;
	}

	public static long lastEventAt() {
		return lastAt;
	}

	/** El radio que se dibuja ahora mismo (suavizado entre ticks); 0 sin ROOM propio a la vista. */
	public static float drawnRadius() {
		RoomVisuals.Vis v = RoomVisuals.own();
		if (v == null) return 0f;
		float td = 1f;
		try {
			td = MinecraftClient.getInstance().getRenderTickCounter().getTickDelta(true);
		} catch (RuntimeException ignored) {
			// sin contador todavía: vale el radio del tick
		}
		return Math.max(0f, v.radius(td));
	}

	/** El radio al que va (el del servidor). */
	public static float targetRadius() {
		RoomVisuals.Vis v = RoomVisuals.own();
		return v == null ? 0f : v.rTarget;
	}

	/** Lanzado y aún en el aire (de CAST a LAND). */
	public static boolean casting() {
		return casting;
	}

	/** Volviendo a ti (de BRING a ARRIVE). */
	public static boolean recalling() {
		return recalling;
	}

	/** Lo lejos que cae, medido desde los ojos al lanzarlo. */
	public static float castDistance() {
		return castDist;
	}

	/** Lo lejos que cayó, medido desde los ojos al aterrizar. */
	public static float landDistance() {
		return landDist;
	}

	/** +1 la última muesca lo agrandó, −1 lo encogió, 0 nada que contar. */
	public static int resizeDir() {
		return resizeDir;
	}

	/** El último tope (LIMIT_MAX, LIMIT_MIN, LIMIT_CENTRED, LIMIT_MAX_GROWING) o −1. */
	public static int limitKind() {
		return limitKind;
	}

	/** Los ojos dentro del radio que se ve. */
	public static boolean inside() {
		return inside;
	}

	public static boolean growing() {
		RoomVisuals.Vis v = RoomVisuals.own();
		return v != null && v.state == RoomVisuals.GROWING && !v.hidden;
	}

	// ------------------------------------------------------------------ lo escribe RoomFxClient

	/** Un momento propio acaba de llegar. */
	static void onEvent(Payloads.RoomFx fx, double eyeDist, int clientTick) {
		int kind = fx.kind();
		long now = System.currentTimeMillis();
		if (kind >= 0 && kind < LAST.length) LAST[kind] = now;
		lastKind = kind;
		lastAt = now;
		switch (kind) {
			case Payloads.RoomFx.NOTCH -> resizeDir = (fx.flags() & Payloads.RoomFx.F_SHRINK) != 0 ? -1 : 1;
			case Payloads.RoomFx.LIMIT -> limitKind = fx.flags();
			case Payloads.RoomFx.CAST -> {
				resizeDir = 0;
				casting = true;
				recalling = false;
				castDist = (float) eyeDist;
				castDeadline = clientTick + Math.max(1, fx.ticks()) + 40;
			}
			case Payloads.RoomFx.LAND -> {
				resizeDir = 0;
				casting = false;
				landDist = (float) eyeDist;
			}
			case Payloads.RoomFx.BRING -> {
				resizeDir = 0;
				recalling = true;
				casting = false;
				recallDeadline = clientTick + Math.max(1, fx.ticks()) + 40;
			}
			case Payloads.RoomFx.ARRIVE -> {
				resizeDir = 0;
				recalling = false;
			}
			case Payloads.RoomFx.CLOSE -> {
				resizeDir = 0;
				casting = false;
				recalling = false;
			}
			default -> resizeDir = 0;   // CREATE, FIX
		}
	}

	/** Cada tick: si estás dentro, y que «volando» o «volviendo» no se queden colgados si algo se perdió. */
	static void tick(boolean in, int clientTick) {
		inside = in;
		if (casting && clientTick > castDeadline) casting = false;
		if (recalling && clientTick > recallDeadline) recalling = false;
	}

	static void clear() {
		java.util.Arrays.fill(LAST, 0L);
		lastKind = -1;
		lastAt = 0;
		casting = false;
		recalling = false;
		inside = false;
		castDist = 0;
		landDist = 0;
		resizeDir = 0;
		limitKind = -1;
	}
}
