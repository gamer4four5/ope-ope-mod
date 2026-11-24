package dev.opeope.power.mera;

import dev.opeope.power.Tech;

/**
 * Las técnicas de Ace y la casilla de recarga que usa cada una. La Mera Mera no Mi no tiene Tech propias: reutiliza
 * los huecos de recarga de Law (el HUD ya sabe dibujarlos), con sus propios tiempos.
 */
public final class MeraTechs {
	private MeraTechs() {}

	public static final Tech HIKEN = Tech.MES;
	public static final Tech ENTEI = Tech.RADIO_KNIFE;
	public static final Tech HIGAN = Tech.INJECTION;
	public static final Tech JUJIKA = Tech.GAMMA;
	public static final Tech HOTARUBI = Tech.SCAN;
	public static final Tech KYOKAEN = Tech.CURTAIN;
	public static final Tech HIKYAKU = Tech.MARKER;
	public static final Tech DASH = Tech.SHAMBLES;
	public static final Tech KAGERO = Tech.COUNTER;
	public static final Tech ENJOMO = Tech.STERBEN;
	public static final Tech SHIRANUI = Tech.SOULS;
	public static final Tech HIBASHIRA = Tech.RAIN;

	public static final int HIKEN_CD = 50, ENTEI_CD = 600, HIGAN_CD = 70, JUJIKA_CD = 160, HOTARUBI_CD = 220,
		KYOKAEN_CD = 240, HIKYAKU_CD = 10, DASH_CD = 28, KAGERO_CD = 80, ENJOMO_CD = 400, SHIRANUI_CD = 140,
		HIBASHIRA_CD = 260;

	/** Ticks de recarga completos de una casilla para la Mera Mera (para el anillo del HUD). */
	public static int total(Tech t) {
		if (t == HIKEN) return HIKEN_CD;
		if (t == ENTEI) return ENTEI_CD;
		if (t == HIGAN) return HIGAN_CD;
		if (t == JUJIKA) return JUJIKA_CD;
		if (t == HOTARUBI) return HOTARUBI_CD;
		if (t == KYOKAEN) return KYOKAEN_CD;
		if (t == HIKYAKU) return HIKYAKU_CD;
		if (t == DASH) return DASH_CD;
		if (t == KAGERO) return KAGERO_CD;
		if (t == ENJOMO) return ENJOMO_CD;
		if (t == SHIRANUI) return SHIRANUI_CD;
		if (t == HIBASHIRA) return HIBASHIRA_CD;
		return t.ticks;
	}

	/** Nombre en pantalla: kanji + lectura y nombre. */
	public static final String[][] NAMES = {
		{"火拳", "HIKEN"},                       // 0
		{"大炎戒 · 炎帝", "DAI ENKAI: ENTEI"},  // 1
		{"火銃", "HIGAN"},                       // 2
		{"十字火", "JŪJIKA"},                    // 3
		{"蛍火", "HOTARUBI"},                    // 4
		{"火達磨", "HIDARUMA"},                  // 5
		{"鏡火炎", "KYOKAEN"},                   // 6
		{"炎上網", "ENJŌMŌ"},                    // 7
		{"神火 · 不知火", "SHINKA: SHIRANUI"},   // 8
		{"炎戒 · 火柱", "ENKAI: HIBASHIRA"},     // 9
		{"陽炎", "KAGERŌ"},                      // 10
		{"火脚", "HIKYAKU"},                     // 11
		{"メラメラの実", "CUERPO DE LLAMAS"}     // 12
	};
}
