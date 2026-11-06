package dev.opeope.client.input;

import net.minecraft.client.util.InputUtil;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Cómo se llama cada tecla en español: el texto corto de las teclas dibujadas (como mucho 4 letras) y el nombre largo
 * para los botones y los avisos. Los botones del ratón se dibujan con un icono.
 */
public final class KeyLabels {
	private KeyLabels() {}

	/** icon: null o raton_izq | raton_der | raton_rueda | raton_lado; text: como mucho 4 letras. */
	public record Glyph(String icon, String text) {}

	private static final Glyph MOUSE_LEFT = new Glyph("raton_izq", "");
	private static final Glyph MOUSE_RIGHT = new Glyph("raton_der", "");
	private static final Glyph WHEEL = new Glyph("raton_rueda", "");
	private static final Glyph NONE = new Glyph(null, "—");

	private static final Map<String, String> SHORT = new HashMap<>();
	private static final Map<String, String> LONG = new HashMap<>();

	private static void put(String id, String shortText, String longText) {
		SHORT.put("key.keyboard." + id, shortText);
		LONG.put("key.keyboard." + id, longText);
	}

	static {
		put("left.alt", "Alt", "Alt izquierda");
		put("right.alt", "AltG", "Alt Gr");
		put("left.shift", "May", "Mayús");
		put("right.shift", "May", "Mayús derecha");
		put("left.control", "Ctrl", "Control");
		put("right.control", "Ctrl", "Control derecho");
		put("tab", "Tab", "Tabulador");
		put("space", "Esp", "Espacio");
		put("enter", "Ent", "Intro");
		put("escape", "Esc", "Escape");
		put("backspace", "Borr", "Retroceso");
		put("delete", "Supr", "Suprimir");
		put("insert", "Ins", "Insertar");
		put("home", "Ini", "Inicio");
		put("end", "Fin", "Fin");
		put("page.up", "RePg", "Re Pág");
		put("page.down", "AvPg", "Av Pág");
		put("up", "↑", "Flecha arriba");
		put("down", "↓", "Flecha abajo");
		put("left", "←", "Flecha izquierda");
		put("right", "→", "Flecha derecha");
		put("caps.lock", "Bloq", "Bloq Mayús");
		put("left.win", "Win", "Tecla Windows");
		put("right.win", "Win", "Tecla Windows derecha");
		put("menu", "Menú", "Menú");
		put("num.lock", "BNum", "Bloq Num");
		put("scroll.lock", "BDes", "Bloq Despl");
		put("print.screen", "ImPt", "Impr Pant");
		put("pause", "Paus", "Pausa");
		put("keypad.add", "Num+", "Teclado numérico +");
		put("keypad.subtract", "Num-", "Teclado numérico -");
		put("keypad.multiply", "Num*", "Teclado numérico *");
		put("keypad.divide", "Num/", "Teclado numérico /");
		put("keypad.decimal", "Num.", "Teclado numérico ,");
		put("keypad.enter", "NumE", "Intro del teclado numérico");
		put("keypad.equal", "Num=", "Teclado numérico =");
		for (int i = 0; i <= 9; i++) put("keypad." + i, "Num" + i, "Teclado numérico " + i);
		for (int i = 1; i <= 25; i++) put("f" + i, "F" + i, "F" + i);
	}

	private static boolean unbound(InputUtil.Key key) {
		return key == null || InputUtil.UNKNOWN_KEY.equals(key) || key.getCode() == -1;
	}

	public static Glyph of(InputUtil.Key key) {
		if (unbound(key)) return NONE;
		if (key.getCategory() == InputUtil.Type.MOUSE) {
			int b = key.getCode();
			return switch (b) {
				case 0 -> MOUSE_LEFT;
				case 1 -> MOUSE_RIGHT;
				case 2 -> WHEEL;
				default -> new Glyph("raton_lado", String.valueOf(b + 1));
			};
		}
		String s = SHORT.get(key.getTranslationKey());
		if (s != null) return new Glyph(null, s);
		String loc = key.getLocalizedText().getString().toUpperCase(Locale.ROOT);
		if (loc.isEmpty()) loc = "?";
		if (loc.length() > 4) loc = loc.substring(0, 4);
		return new Glyph(null, loc);
	}

	public static Glyph of(Keys k) {
		return of(k.key);
	}

	public static Glyph mouseLeft() {
		return MOUSE_LEFT;
	}

	public static Glyph mouseRight() {
		return MOUSE_RIGHT;
	}

	public static Glyph wheel() {
		return WHEEL;
	}

	/** El nombre largo, para botones y avisos: «Alt izquierda», «Botón lateral 4», «Q». */
	public static String text(InputUtil.Key key) {
		if (unbound(key)) return "Sin tecla";
		if (key.getCategory() == InputUtil.Type.MOUSE) {
			int b = key.getCode();
			return switch (b) {
				case 0 -> "Botón izquierdo";
				case 1 -> "Botón derecho";
				case 2 -> "Rueda (botón central)";
				case 3, 4 -> "Botón lateral " + (b + 1);
				default -> "Botón " + (b + 1) + " del ratón";
			};
		}
		String s = LONG.get(key.getTranslationKey());
		if (s != null) return s;
		String loc = key.getLocalizedText().getString();
		return loc.length() <= 2 ? loc.toUpperCase(Locale.ROOT) : loc;
	}

	/** Para frases: el nombre largo si es corto («Mayús», «Q»), si no el de la tecla dibujada («Tab», «Alt»). */
	static String name(InputUtil.Key key) {
		String t = text(key);
		if (key != null && key.getCategory() == InputUtil.Type.MOUSE) return t;
		if (t.length() <= 7) return t;
		Glyph g = of(key);
		return g.icon() == null && !g.text().isEmpty() ? g.text() : t;
	}
}
