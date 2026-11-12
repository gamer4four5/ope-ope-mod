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

	// Takt sobre el suelo
	public static final double TAKT_CHUNK_DEFAULT = 3.0, TAKT_CHUNK_MIN = 1.5, TAKT_CHUNK_MAX = 9.0, TAKT_CHUNK_STEP = 0.5;
	public static final int TAKT_CHUNK_MAX_BLOCKS = 4000;  // terreno + lo que va encima, en un arranque
	public static final int TAKT_RIDER_MAX = 600;          // lo que va encima, en un arranque
	public static final double TAKT_CUT_REACH = 18.0;      // un corte cuenta si su plano está a menos de esto de la mira (y la mira en su caja ± esto)
	public static final double TAKT_CLOSED_HALF = 11.25;   // medio lado del cuadrado de la prueba de "cerrado" (9 × 1,25)
	public static final int TAKT_STEP_MAX = 3;             // la superficie de una columna se busca a ± esto de la de su vecina (4.2.3)
	public static final int TAKT_AIM_DOWN = 12;            // mira en una planta, un tronco o una pared: el suelo se busca hasta esto por debajo
	public static final int TAKT_TEAR_TICKS = 4;           // el arranque más corto: del toque a soltarse
	public static final int TAKT_TAP_WINDOW_DEFAULT = 8;   // espera de un toque apuntado por el servidor (Actions.TAKT_TAP): ceil(350 / 50) + 1
	public static final int TAKT_TAP_WINDOW_MAX = 13;      // la espera más larga que se acepta de un cliente: ceil(600 / 50) + 1
	public static final int TAKT_TREMBLE_TICKS = 5, TAKT_TREMBLE_TICKS_BIG = 8;
	public static final double TAKT_BIG_RADIUS = 6.0;      // PieceEntity.radius() por encima de esto = trozo grande
	public static final int TAKT_UNDO_MAX_TICKS = 40;      // R R deshace el arranque del toque anterior si empezó hace como mucho esto
	public static final int TAKT_TAP_MIN_TICKS = 2;        // los toques de un jugador más seguidos que esto no cuentan
	public static final int TAKT_RIP_BUDGET = 8000;        // bloques que un jugador puede empezar a arrancar de golpe...
	public static final int TAKT_RIP_REFILL = 400;         // ...que se recuperan a este ritmo por tick (8000 por segundo)
	public static final double TAKT_SLIDE_PROBE = 0.6;     // regla de la cuña: cuánto se prueba a bajar por el plano una loncha grande, bloques
	public static final int TAKT_FRESH_TICKS = 240;        // F_FRESH se apaga pasado esto
	public static final double TAKT_THROW_ALL_SPEED = 2.4; // bloques/tick (TAKT_LAUNCH 2,1 sigue para lanzar uno)
	public static final int TAKT_LAND_MAX_TICKS = 80;      // posar: tiempo máximo de una bajada corta (una larga tiene los ticks que necesita)
	public static final int TAKT_LANDED_TAIL = 3;          // ticks que la entidad sigue ahí después de poner sus bloques
	public static final int TAKT_LANDING_SEARCH = 48;      // Landing.find: cuántos pasos baja como mucho
	public static final int TAKT_LANDING_OVER = 24;        // Landing.find: cuánto sube como mucho para salir de los sitios que apartaron otras piezas
	public static final int TAKT_PLACE_TOLERANCE = 2;      // el fantasma del cliente vale si está a esto del del servidor (en cada eje)
	public static final int TAKT_WAVE_MAX_TICKS = 20;      // tope de la oleada de zona y de "lo cortado"
	// Cortes que el suelo recuerda
	public static final int GROUND_CUT_TICKS = 3600;       // 180 s
	public static final int GROUND_CUT_MAX = 24;           // por jugador; se van los más viejos
	public static final int GROUND_CUT_MAX_PER_CUT = 9;    // planos que un solo corte puede dejar recordados (una rejilla del R-ROOM)
	public static final int GROUND_CUT_FADE_TICKS = 200;   // cliente: las marcas se apagan en sus últimos 10 s
	// Impactos
	public static final double IMPACT_TIER1 = 6e4, IMPACT_TIER2 = 1.2e6, IMPACT_TIER3 = 1.2e7;   // julios
	public static final int CRATER_MAX_BLOCKS = 600;

	// Shambles
	public static final int SHAMBLES_MARK_TICKS = 20 * 6;
	public static final int SOUL_MARK_TICKS = 20 * 8;

	// Técnicas con duración (el HUD cuenta los segundos con estos mismos números)
	public static final int SCAN_TICKS = 160;
	public static final int CURTAIN_TICKS = 120;
	public static final int SILENT_TICKS = 200;
}
