package dev.opeope.client.hud;

import dev.opeope.client.config.OpeSettings;

/**
 * Los colores del HUD (ARGB). Los fijos son iguales en los dos modos; los demás cambian con el alto contraste y los
 * fondos se escalan con la opacidad de los paneles de Ajustes. refresh() los recalcula (una vez por fotograma).
 */
public final class Theme {
	private Theme() {}

	// Acentos (iguales en alto contraste)
	public static final int ROOM = 0xFF8FE3FF, HI = 0xFFE8FBFF, SOFT = 0xFF9FE6FF;
	public static final int TAB = 0xFFC6B5FF, FORGE = 0xFFFFD9A0, AWAKE = 0xFF8CBCFF, GOLD = 0xFFF2C66D;
	public static final int BAD = 0xFFFFB4AB, OUT_WARN = 0xFFFFCF9A, DANGER = 0xFFFF6767, HEART = 0xFFFF9FB2, UNION = 0xFFC8F0D8;
	public static final int TAKT = 0xFFB9F0FF;
	public static final int METER_TRACK = 0x80000000, METER_FILL_A = 0xFF3AA6D6, METER_FILL_B = 0xFF9FE6FF, METER_MARK = 0xFFE8FBFF;
	public static final int SHADOW_1 = 0x40000000, SHADOW_2 = 0x20000000, KEYCAP_DEPTH = 0x80000000;
	public static final int BADGE_TEXT = 0xFF0A1318, PENDING = 0x30FFFFFF, WHITE = 0xFFFFFFFF;
	public static final int RETICLE_OUT = 0xC08FE3FF, RETICLE_IN = 0xD9E8FBFF, AMPUTATE_TRACK = 0x60000000;

	// Cambian con el alto contraste
	public static boolean hc;
	public static int PANEL_TOP, PANEL_BOTTOM, INK2, TILE_TOP, TILE_BOTTOM, HILITE, HAIRLINE;
	public static int TEXT = 0xFFEEF7FA, TEXT2 = 0xFFA9C2CC, TEXT3 = 0xFF6F8792;
	public static int KEYCAP_BG, KEYCAP_RING, COOLDOWN, ROOM_BORDER, GLOW_RING;
	public static int METER_TOP, METER_BOTTOM, METER_TICK, METER_LABEL;

	static {
		refresh();
	}

	public static void refresh() {
		boolean high = false;
		float opacity = 0.85f;
		try {
			high = OpeSettings.highContrast();
			opacity = OpeSettings.panelOpacity();
		} catch (RuntimeException e) {
			// sin ajustes cargados todavía: los valores por defecto
		}
		hc = high;
		float k = opacity / 0.85f;
		if (!high) {
			PANEL_TOP = panel(0xC2050D13, k);
			PANEL_BOTTOM = panel(0xA8050D13, k);
			INK2 = panel(0x8C050D13, k);
			TILE_TOP = panel(0xD60B1923, k);
			TILE_BOTTOM = panel(0xCC050D13, k);
			HILITE = 0x12FFFFFF;
			HAIRLINE = 0x1FBEE6FF;
			TEXT = 0xFFEEF7FA;
			TEXT2 = 0xFFA9C2CC;
			TEXT3 = 0xFF6F8792;
			KEYCAP_BG = 0xFF0A1318;
			KEYCAP_RING = 0x2EFFFFFF;
			COOLDOWN = 0xCC010508;
			ROOM_BORDER = 0x409FE6FF;
			GLOW_RING = 0x1C6FD0FF;
			METER_TOP = panel(0xD90A1E30, k);
			METER_BOTTOM = panel(0xD906121D, k);
			METER_TICK = 0x30FFFFFF;
			METER_LABEL = 0xFF7FA6B6;
		} else {
			PANEL_TOP = panel(0xF0050D13, k);
			PANEL_BOTTOM = PANEL_TOP;
			INK2 = panel(0xF0050D13, k);
			TILE_TOP = panel(0xF0050D13, k);
			TILE_BOTTOM = TILE_TOP;
			HILITE = 0;
			HAIRLINE = 0xFFFFFFFF;
			TEXT = 0xFFFFFFFF;
			TEXT2 = 0xFFE6F2F6;
			TEXT3 = 0xFFB8C8CF;
			KEYCAP_BG = 0xFF000000;
			KEYCAP_RING = 0xFFFFFFFF;
			COOLDOWN = 0xE6000000;
			ROOM_BORDER = 0xFFFFFFFF;
			GLOW_RING = 0;
			METER_TOP = panel(0xF00A1E30, k);
			METER_BOTTOM = panel(0xF006121D, k);
			METER_TICK = 0xFFFFFFFF;
			METER_LABEL = 0xFFE6F2F6;
		}
	}

	/** Alfa de un fondo × opacidad de Ajustes / 0,85, como mucho 255. */
	private static int panel(int argb, float k) {
		int a = Math.min(255, Math.round((argb >>> 24) * k));
		return (a << 24) | (argb & 0xFFFFFF);
	}

	public static int alpha(int argb) {
		return argb >>> 24;
	}

	/** El mismo color con alfa a (0..255). */
	public static int withAlpha(int argb, int a) {
		a = Math.max(0, Math.min(255, a));
		return (a << 24) | (argb & 0xFFFFFF);
	}

	/** El mismo color con su alfa multiplicado por m. */
	public static int mulAlpha(int argb, float m) {
		if (m >= 1f) return argb;
		if (m <= 0f) return argb & 0xFFFFFF;
		return withAlpha(argb, Math.round((argb >>> 24) * m));
	}

	/** Mezcla ARGB canal a canal (t = 0 da a, t = 1 da b). */
	public static int lerp(int a, int b, float t) {
		if (t <= 0f) return a;
		if (t >= 1f) return b;
		int aa = a >>> 24, ar = (a >> 16) & 0xFF, ag = (a >> 8) & 0xFF, ab = a & 0xFF;
		int ba = b >>> 24, br = (b >> 16) & 0xFF, bg = (b >> 8) & 0xFF, bb = b & 0xFF;
		return (Math.round(aa + (ba - aa) * t) << 24) | (Math.round(ar + (br - ar) * t) << 16)
			| (Math.round(ag + (bg - ag) * t) << 8) | Math.round(ab + (bb - ab) * t);
	}

	/** Sombra del texto que flota sobre el mundo: no en alto contraste. */
	public static boolean floatShadow() {
		return !hc;
	}
}
