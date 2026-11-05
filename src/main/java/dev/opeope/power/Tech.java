package dev.opeope.power;

/** Las técnicas con tiempo de espera (los mismos segundos que en el juego). */
public enum Tech {
	AMPUTATE("Amputate", 12),
	SHAMBLES("Shambles", 7),
	MARKER("Piedra marcador", 7),
	RADIO_KNIFE("Radio Knife", 120),
	INJECTION("Injection Shot", 140),
	GAMMA("Gamma Knife", 400),
	SCAN("Scan", 200),
	MES("Mes", 240),
	COUNTER("Counter Shock", 100),
	CURTAIN("Curtain", 160),
	STERBEN("Sterben", 240),
	SOULS("Cambio de almas", 600),
	RAIN("Lluvia", 200),
	KROOM("K-ROOM", 20),
	ANESTHESIA("Anesthesia", 10),
	SHOCK_WILLE("Shock Wille", 60),
	RROOM("R-ROOM", 30),
	PUNCTURE("Puncture Wille", 120),
	SILENT("Silent", 10);

	public final String label;
	public final int ticks;

	Tech(String label, int ticks) {
		this.label = label;
		this.ticks = ticks;
	}
}
