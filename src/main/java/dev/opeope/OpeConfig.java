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
	public static final int CUT_MAX_PIECE_BLOCKS = 8000;  // más grande que esto se considera anclado (terreno)
	public static final int CUT_MAX_SLICE_BLOCKS = 380_000; // lo que el borde del ROOM recorta del terreno (la cima o ladera completa de un monte)
	public static final int CUT_MAX_SLICE_VISIT = 1_500_000; // tope de bloques recorridos buscando esos recortes
	public static final double CUT_SLIDE_MIN_UP = 0.22;   // lo que queda encima de un corte con al menos esta inclinación resbala
	public static final double CUT_SLIDE_PUSH = 0.42;     // empujón inicial en la dirección del tajo (bloques/tick)
	public static final int CUT_HOVER_TICKS = 20 * 10;    // si no puede resbalar, queda separada flotando 10 s para Takt/Rejuntar/Unión
	public static final int CUT_HOVER_MAX_BLOCKS = 400;   // si la pieza es una masa gigante apoyada (> 400), se ancla en vez de flotar entera
	public static final int CUT_HOLD_TICKS = 4;           // lo cortado se queda quieto un instante con la costura encendida (0,20 s)
	public static final int CUT_MAX_PIECES = 192;
	public static final int CUT_MAX_SAMPLES = 900_000;
	public static final int CUT_LIVING_TICKS = 20 * 30;   // lo cortado de un ser vivo se recompone solo a los 30 s
	public static final int RADIO_KNIFE_NO_REATTACH = 20 * 5;

	// Takt
	public static final int TAKT_MAX = 48;
	public static final double TAKT_LAUNCH = 2.1;         // 42 m/s
	public static final double TAKT_RANGE = 200;
	public static final double TAKT_ZONE_RADIUS = 6;

	// Takt sobre el suelo
	public static final double TAKT_CHUNK_DEFAULT = 3.0, TAKT_CHUNK_MIN = 0.5, TAKT_CHUNK_MAX = 14.0, TAKT_CHUNK_STEP = 0.5;
	public static final int[] TAKT_DEPTH_STEPS = { 0, 1, 2, 3, 4, 5, 6, 8, 10, 12, 16 }; // 0 = auto (forma natural)
	public static final int TAKT_DEPTH_MAX = 16;
	public static final int TAKT_CHUNK_MAX_BLOCKS = 5000;  // terreno + lo que va encima, en un arranque
	public static final int TAKT_RIDER_MAX = 600;          // encima en modo normal
	public static final int TAKT_TREE_MAX = 3500;          // arbol completo / estructura en modos de arbol
	public static final double TAKT_CUT_REACH = 18.0;      // un corte cuenta si su plano está a menos de esto de la mira (y la mira en su caja ± esto)
	public static final double TAKT_CLOSED_HALF = 11.25;   // medio lado del cuadrado de la prueba de "cerrado" (9 × 1,25)
	public static final int TAKT_STEP_MAX = 3;             // la superficie de una columna se busca a ± esto de la de su vecina (4.2.3)
	public static final int TAKT_AIM_DOWN = 12;            // mira en una planta, un tronco o una pared: el suelo se busca hasta esto por debajo
	public static final int TAKT_TEAR_TICKS = 4;           // el arranque más corto: del toque a soltarse
	public static final int TAKT_TAP_WINDOW_DEFAULT = 8;   // espera de un toque apuntado por el servidor (Actions.TAKT_TAP): ceil(350 / 50) + 1
	public static final int TAKT_TAP_WINDOW_MAX = 13;      // la espera más larga que se acepta de un cliente: ceil(600 / 50) + 1
	public static final int TAKT_TREMBLE_TICKS = 5, TAKT_TREMBLE_TICKS_BIG = 8;
	public static final int TAKT_CLIP_SPARE_TICKS = 40;   // la subida sin choques dura como mucho su subida más esto (red: sale antes)
	public static final double TAKT_BIG_RADIUS = 6.0;      // PieceEntity.radius() por encima de esto = trozo grande
	public static final int TAKT_UNDO_MAX_TICKS = 40;      // R R deshace el arranque del toque anterior si empezó hace como mucho esto
	public static final int TAKT_TAP_MIN_TICKS = 2;        // los toques de un jugador más seguidos que esto no cuentan
	public static final int TAKT_RIP_BUDGET = 10000;       // bloques que un jugador puede empezar a arrancar de golpe...
	public static final int TAKT_RIP_REFILL = 500;         // ...que se recuperan a este ritmo por tick (10000 por segundo)
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
	public static final double FALL_MAX_SPEED = 5.0;       // b/t: tope de la velocidad en vuelo libre (100 m/s)
	public static final double IMPACT_FALL_SPEED = 0.8;    // b/t: lo que cae solo (sin lanzar) revienta desde esto (unos 5 bloques de caída)...
	public static final double IMPACT_FALL_KG = 8000;      // ...si pesa al menos esto (tres bloques de roca)
	public static final int CRATER_MAX_BLOCKS = 3000;      // lo que un cráter cava como mucho
	public static final int CRATER_TICK_BUDGET = 9000;     // lo que cavan entre todos los cráteres de un mismo tick
	public static final double CRATER_MAX_RADIUS = 22;
	public static final int EJECTA_MAX = 36;               // terrones que salen volando de un cráter
	public static final int EJECTA_LIVE_MAX = 120;         // terrones en el aire a la vez, cerca del golpe

	// Shambles
	public static final int SHAMBLES_MARK_TICKS = 20 * 6;
	public static final int SOUL_MARK_TICKS = 20 * 8;

	// Shambles Táctico con trozos en vuelo (PieceEntity)
	public static final double SHAMBLES_VEL_MIN = 0.25;            // b/t mínima para considerar que la pieza sigue en vuelo táctico
	public static final double SHAMBLES_ASSIST_ANGLE_DEG = 11.5;   // grados máximos del cono de asistencia hacia una pieza en vuelo
	public static final int SHAMBLES_LEAD_TICKS = 3;               // ticks de predicción (center + vel * LEAD_TICKS) para el cono
	public static final int SHAMBLES_FALLBACK_WINDOW = 30;         // ticks (1.5 s) para usar la última pieza lanzada si no apuntas a otra entidad
	public static final double SHAMBLES_MOMENTUM_INHERIT = 0.35;   // fracción del impulso de la pieza que hereda el jugador
	public static final double SHAMBLES_MOMENTUM_CAP = 1.15;       // tope de velocidad heredada por el jugador (b/t)
	public static final int SHAMBLES_VEL_KEEP = 0, SHAMBLES_VEL_REAIM = 1;
	public static final int SHAMBLES_PIECE_VEL_MODE_SELF = SHAMBLES_VEL_KEEP;   // jugador ↔ pieza: mantiene dirección
	public static final int SHAMBLES_PIECE_VEL_MODE_ENEMY = SHAMBLES_VEL_REAIM; // enemigo ↔ pieza: redirige hacia el enemigo en el aire
	public static final int SHAMBLES_OWNER_GRACE_TICKS = 6;        // ticks tras el swap en que la pieza no golpea a su dueño
	public static final boolean SHAMBLES_PIECE_CRUSH_ON_OVERLAP = true; // si la pieza solapa bloques en destino, impacta en el siguiente tick
	public static final int SHAMBLES_AERIAL_WINDOW = 16;           // duración de la ventana aérea de remate (ticks)
	public static final int SHAMBLES_AERIAL_WINDOW_PERFECT = 24;   // duración de la ventana aérea en Shambles Perfecto (ticks)
	public static final float SHAMBLES_AERIAL_GRAV_MULT = 0.28f;   // amortiguación de caída en cliente durante la ventana aérea
	public static final double SHAMBLES_GAMMA_AERIAL_RANGE = 8.0;  // alcance de Gamma Knife durante la ventana aérea (con lunge si > 4.5)
	public static final float SHAMBLES_AERIAL_DMG_MULT = 1.5f;     // multiplicador de daño de remate en ventana aérea
	public static final float SHAMBLES_AERIAL_DMG_MULT_PERFECT = 2.0f; // multiplicador de daño en Shambles Perfecto
	public static final int SHAMBLES_PERFECT_TICKS = 5;            // ticks restantes hasta el choque para activar Shambles Perfecto
	public static final int SHAMBLES_MAX_AIR_CHAIN = 2;            // máximo de Shambles tácticos seguidos en el aire sin tocar suelo
	public static final boolean SHAMBLES_ALLOW_FOREIGN_PIECES = false; // permitir usar piezas lanzadas por otros jugadores

	// Efectos elementales y físicos por bloque (MaterialTrait)
	public static final double TRAIT_MIN_SHARE_DOMINANT = 0.35; // proporción mínima si dominant() tiene trait != NONE
	public static final double TRAIT_MIN_SHARE = 0.50;          // proporción mínima por mayoría si dominant() es NONE
	public static final int CHAIN_MAX_HOPS = 6;                 // saltos máximos en cadena eléctrica (BFS)
	public static final int CHAIN_MAX_TARGETS = 12;             // entidades máximas alcanzadas por una descarga en cadena
	public static final double ARC_PICKUP = 5.0;                // distancia máx. origen/entidad → pieza conductora
	public static final double ARC_PIECE_LINK = 9.0;            // distancia máx. pieza conductora ↔ pieza conductora
	public static final double ARC_JUMP = 5.5;                  // distancia máx. pieza conductora → enemigo
	public static final float CHAIN_FALLOFF = 0.80f;            // multiplicador de daño por cada salto en cadena
	public static final boolean IGNEOUS_PLACE_FIRE = true;      // colocar fuego efímero sobre superficies no inflamables
	public static final int IGNEOUS_MAX_FIRES = 10;             // máximo de fuegos efímeros por impacto ígneo
	public static final int IGNEOUS_FIRE_TTL = 60;              // ticks hasta apagar los fuegos efímeros (3 s)
	public static final int CRYO_MAX_FREEZE = 96;               // máximo de celdas de agua congeladas por impacto cryo
	public static final boolean CRYO_PERMANENT_ICE = false;     // si true congela en ICE en lugar de FROSTED_ICE
	public static final boolean CRYO_LAVA_TO_OBSIDIAN = false;  // si true solidifica lava (false por conservación de bloques)
	public static final boolean ELEMENT_INTERACTIONS = true;    // ígneo vs cryo generan vapor y se cancelan
	public static final float HEAVY_DMG_MULT = 1.3f;            // multiplicador de daño de impacto/armas heavy
	public static final float HEAVY_DMG_CAP = 40.0f;            // tope de daño en vuelo libre para heavy
	public static final int HEAVY_PIERCE_COUNT = 3;             // objetivos perforables antes de detenerse/ensartar
	public static final double HEAVY_FRACTURE_MULT = 0.25;      // fracción de auto-fractura en impacto para heavy
	public static final double HEAVY_DEPTH_MULT = 1.45;         // multiplicador de profundidad del cráter para heavy
	public static final double HEAVY_OBSIDIAN_K = 0.85;         // intensidad k mínima de heavy para fracturar obsidiana
	public static final boolean HAMMER_CRATER_ALL = false;      // si true todos los mazos abren cráter (false = solo heavy)


	// Desmembramiento estilo Law (Amputate por partes). Las partes cortadas siguen vivas y unidas a su dueño.
	public enum OnEnd { KEEP, REATTACH }
	public static final double CUT_RANGE = 48;               // bloques: hasta dónde se puede cortar una parte (además de estar en tu ROOM)
	public static final float AMPUTATE_DAMAGE = 0f;          // daño del corte a un ser normal (el anime: ninguno)
	public static final float AMPUTATE_BOSS_DAMAGE = 2f;     // daño leve a un jefe no excluido
	public static final int AMPUTATE_COOLDOWN = 8;           // ticks entre cortes dirigidos (aparte del de Amputate por plano)
	public static final float PART_DMG_SHARE = 1.0f;         // el daño a una parte pasa a su dueño multiplicado por esto
	public static final int PART_LIFETIME = 20 * 60 * 5;     // ticks hasta que un mob recupera sola su parte (0 = nunca)
	public static final int PART_ORPHAN_TICKS = 20 * 10;     // una parte sin dueño (o un dueño sin su parte) se resuelve tras esto
	public static final int MAX_PARTS_WORLD = 300;           // partes sueltas en todo el mundo
	public static final int MAX_PARTS_PER_ENTITY = 24;       // partes sueltas de un mismo ser
	public static final boolean ALLOW_PLAYERS = true;        // cortar jugadores (PvP)
	public static final boolean ALLOW_VILLAGERS = true;      // cortar aldeanos y vendedores
	public static final boolean ALLOW_PETS = true;           // cortar mascotas domesticadas
	public static final java.util.Set<String> EXCLUDED_ENTITIES = java.util.Set.of(   // nunca se cortan por partes
		"minecraft:ender_dragon", "minecraft:wither", "minecraft:warden", "minecraft:elder_guardian");
	public static boolean PLAYER_HEAD_CAMERA = true;         // un jugador sin cabeza ve desde su cabeza (y sigue moviendo el cuerpo)
	public static boolean MOBS_RECLAIM_PARTS = true;         // los mobs van a por sus partes y se las vuelven a pegar
	public static final int RECLAIM_TOUCH_TICKS = 40;        // ticks tocando su parte para volver a pegársela
	/** Si un ser no alcanza su parte en este tiempo (ticks), la parte va flotando hacia él. */
	public static int RECLAIM_PULL_TICKS = 80;
	public static final double RECLAIM_RANGE = 16;           // bloques: hasta dónde busca un mob sus partes (sin cabeza, solo a tientas: 3)
	public static boolean LOWER_HALF_WANDERS = true;         // la mitad de abajo (o unas patas sueltas) camina sola y huye de quien cortó
	public static final boolean HEAD_CONTROLS_AI = true;     // con una cabeza trasplantada, el cuerpo persigue a quien persigue el dueño de la cabeza
	public static final boolean PLAYER_SOUL_SWAP = false;    // (fase 4) cambio de cuerpos entre jugadores
	public static final boolean MES_CAN_KILL = false;        // apretar el corazón puede matar (si no, deja a su dueño con medio corazón)
	public static final float MES_SQUEEZE_DMG = 2f;          // daño de un apretón del corazón (Mes)
	public static final float PART_IMPACT_DMG = 5f;          // daño de una parte lanzada (Takt) por bloque/tick de velocidad; su dueño se lleva la mitad
	public static final boolean ALLOW_TRANSPLANT = true;     // Shambles parte de A → B pone la parte de A en el sitio de B (quimeras)
	public static final int RADIO_LOCK_TICKS = 20 * 30;      // lo cortado con Radio Knife no se puede reunir en este tiempo
	public static boolean FREE_PLANE_CUTS = true;            // un tajo que atraviesa el torso (no una articulación) parte el cuerpo por ese plano, en cualquier ángulo
	public static final int MAX_PLANES_PER_PIECE = 6;        // máximo de operaciones/cortes simultáneos por entidad
	public static final int MAX_OPS_PER_VOLUME = 6;          // tope de operaciones CSG por volumen
	public static final double MIN_SLICE_VOLUME_PX3 = 8.0;   // volumen mínimo en px³ para separar una pieza válida
	public static final boolean SYNTHETIC_ANATOMY = true;    // genera anatomía universal si un mob no tiene JSON propio
	public static final boolean SLIME_SPLIT_NATIVE = true;   // (fase 4) los slimes se dividen a su manera
	public static final boolean HEADLESS_CREEPER_EXPLODES = false; // un creeper sin cabeza no puede encenderse
	public static final OnEnd ON_ROOM_END = OnEnd.KEEP;      // al cerrar el ROOM: las partes siguen sueltas o vuelven
	public static final OnEnd ON_CUTTER_DEATH = OnEnd.KEEP;  // al morir o salir quien cortó

	// Técnicas con duración (el HUD cuenta los segundos con estos mismos números)
	public static final int SCAN_TICKS = 160;
	public static final int CURTAIN_TICKS = 120;
	public static final int SILENT_TICKS = 200;
}
