package dev.opeope.client.hud;

import net.minecraft.client.font.TextRenderer;
import net.minecraft.util.Formatting;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Textos del HUD: metros, segundos (con coma decimal) y recortes con «…». */
public final class Txt {
	private Txt() {}

	public static final String ELLIPSIS = "…";

	/** Un decimal con coma: 7,5. */
	public static String dec1(double v) {
		return String.format(Locale.ROOT, "%.1f", v).replace('.', ',');
	}

	/** «7,5 m» por debajo de 10 m; si no, «24 m». */
	public static String radius(float r) {
		if (r < 10f) return dec1(r) + " m";
		return Math.round(r) + " m";
	}

	/** Metros enteros: «160 m». */
	public static String meters(double m) {
		return Math.round(m) + " m";
	}

	/** «12 s» (redondeado hacia arriba) desde 1 s; «0,6 s» por debajo; nada con 0. */
	public static String secs(int ticks) {
		String s = secsShort(ticks);
		return s.isEmpty() ? s : s + " s";
	}

	/** Como secs, sin « s» (para las casillas). */
	public static String secsShort(int ticks) {
		if (ticks <= 0) return "";
		if (ticks >= 20) return Integer.toString((ticks + 19) / 20);
		return dec1(ticks / 20.0);
	}

	/** Milisegundos que quedan como segundos enteros hacia arriba: «7 s». */
	public static String secsMs(long ms) {
		if (ms <= 0) return "";
		return ((ms + 999) / 1000) + " s";
	}

	/** Quita los códigos de formato (§7…) que traen algunos mensajes. */
	public static String plain(String s) {
		if (s == null) return "";
		String t = Formatting.strip(s);
		return t == null ? "" : t;
	}

	/** El texto entero si cabe en maxW; si no, recortado y con «…». */
	public static String ellipsize(TextRenderer tr, String s, int maxW) {
		if (s == null || s.isEmpty() || maxW <= 0) return "";
		if (tr.getWidth(s) <= maxW) return s;
		int ew = tr.getWidth(ELLIPSIS);
		if (maxW < ew) return "";
		String t = tr.trimToWidth(s, maxW - ew);
		while (!t.isEmpty() && (t.endsWith(" ") || t.endsWith("·") || t.endsWith(":") || t.endsWith(","))) {
			t = t.substring(0, t.length() - 1);
		}
		return t + ELLIPSIS;
	}

	/** Parte un texto en líneas de como mucho maxW (por espacios; una palabra larga se recorta). */
	public static List<String> wrap(TextRenderer tr, String s, int maxW) {
		List<String> out = new ArrayList<>();
		if (s == null || s.isEmpty() || maxW <= 0) return out;
		StringBuilder line = new StringBuilder();
		for (String word : s.split(" ")) {
			if (word.isEmpty()) continue;
			String next = line.length() == 0 ? word : line + " " + word;
			if (tr.getWidth(next) <= maxW) {
				line.setLength(0);
				line.append(next);
				continue;
			}
			if (line.length() > 0) out.add(line.toString());
			line.setLength(0);
			line.append(tr.getWidth(word) <= maxW ? word : ellipsize(tr, word, maxW));
		}
		if (line.length() > 0) out.add(line.toString());
		return out;
	}
}
