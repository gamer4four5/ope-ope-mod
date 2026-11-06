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
	public static final double ROOM_NOTCH = 1.14;         // cada paso de la rueda multiplica (o divide) el radio por esto...
	public static final double ROOM_NOTCH_MIN_STEP = 1.25; // ...y como mínimo lo cambia en tantos bloques
	public static final double ROOM_CAST_RANGE = 150;     // lanzarlo: hasta dónde busca dónde caer
	public static final double ROOM_CAST_MISS = 48;       // si la mira no toca nada, cae a esta distancia
	public static final double ROOM_CAST_SPEED = 95;      // bloques/s del vuelo (de 4 a 14 ticks)
	public static final double ROOM_FIRST_RADIUS = 24;    // el tamaño de un ROOM lanzado sin haber fijado ninguno antes

	// Cortes
	public static final int CUT_MAX_PIECE_BLOCKS = 4000;  // más grande que esto se considera anclado (terreno)
	public static final int CUT_MAX_SLICE_BLOCKS = 12000; // lo que el borde del ROOM recorta del terreno (la cima de un monte)
	public static final int CUT_MAX_SLICE_VISIT = 60000; // tope de bloques recorridos buscando esos recortes
	public static final double CUT_SLIDE_MIN_UP = 0.3;    // lo que queda encima de un corte con al menos esta inclinación resbala
	public static final double CUT_SLIDE_PUSH = 0.16;     // empujón inicial en la dirección del tajo (bloques/tick)
	public static final int CUT_HOVER_TICKS = 20 * 8;     // si no puede resbalar, queda separada flotando este rato
	public static final int CUT_HOVER_MAX_BLOCKS = 400;   // más grande que esto, si está encajada vuelve a su sitio en vez de flotar
	public static final int CUT_HOLD_TICKS = 3;           // lo cortado se queda quieto un instante con la costura encendida (0,15 s)
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

	// Técnicas con duración (el HUD cuenta los segundos con estos mismos números)
	public static final int SCAN_TICKS = 160;
	public static final int CURTAIN_TICKS = 120;
	public static final int SILENT_TICKS = 200;
}
