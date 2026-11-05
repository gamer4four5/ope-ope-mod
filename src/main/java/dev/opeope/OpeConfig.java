package dev.opeope;

/**
 * Todos los números de balance del mod. Copiados del juego (src/config.js) y pasados a bloques y ticks:
 * 1 m = 1 bloque, 1 s = 20 ticks, y el daño del juego (vida 100) multiplicado por 0,2 (vida 20).
 */
public final class OpeConfig {
	private OpeConfig() {}

	public static final float DMG = 0.2f;

	// ROOM
	public static final double ROOM_START_RADIUS = 5;
	public static final double ROOM_MIN_RADIUS = 4;
	public static final double ROOM_MAX_RADIUS = 160;
	public static final double ROOM_GROW_SPEED = 14;      // bloques/s
	public static final double ROOM_GROW_ACCEL = 1.1;     // + radio × esto por segundo
	public static final double ROOM_FLY_SPEED = 6;        // bloques/tick al lanzarlo o traerlo
	public static final int ROOM_TAP_TICKS = 5;           // un toque de Q más corto que esto quita el ROOM

	// Cortes
	public static final int CUT_MAX_PIECE_BLOCKS = 4000;  // más grande que esto se considera anclado (terreno)
	public static final int CUT_MAX_PIECES = 48;
	public static final int CUT_MAX_SAMPLES = 420_000;
	public static final int CUT_LIVING_TICKS = 20 * 30;   // lo cortado de un ser vivo se recompone solo a los 30 s
	public static final int RADIO_KNIFE_NO_REATTACH = 20 * 5;

	// Takt
	public static final int TAKT_MAX = 32;
	public static final double TAKT_LAUNCH = 2.1;         // 42 m/s
	public static final double TAKT_RANGE = 200;
	public static final double TAKT_ZONE_RADIUS = 6;

	// Shambles
	public static final int SHAMBLES_MARK_TICKS = 20 * 6;
	public static final int SOUL_MARK_TICKS = 20 * 8;
}
