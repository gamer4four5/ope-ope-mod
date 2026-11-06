package dev.opeope.client.hud;

import dev.opeope.OpeOpeMod;
import net.minecraft.util.Identifier;

import java.util.HashMap;
import java.util.Map;

/**
 * Los iconos del HUD: sprites blancos del atlas de la interfaz (assets/opeope/textures/gui/sprites/hud/<nombre>.png,
 * generados por tools/iconos.py) que se tiñen al dibujarlos. 12×12, salvo el candado (8×8) y los ratones (7×9).
 */
public final class Icons {
	private Icons() {}

	public static final String[] ALL = {
		"room", "takt", "shambles", "piedra", "scan", "rroom", "curtain", "silent", "radio_knife", "kroom",
		"injection", "anestesia", "gamma", "shock_wille", "despertar", "fila2", "sterben", "almas", "union", "cirugia",
		"lluvia", "mes", "counter", "puncture", "pilar", "muralla", "sima", "meseta", "patron", "alcance",
		"suspender", "orbita", "aparcar", "posar", "soltar", "lanzar_todo", "zona", "lo_cortado", "rejuntar", "unir",
		"lanza", "hoja", "martillo", "estacas", "muralla_forja", "amputar", "bisturi", "corazon", "aviso", "check",
		"fruta", "libro", "engranaje", "teclado", "candado", "raton_izq", "raton_der", "raton_rueda", "raton_lado"
	};

	/** El icono de cada arma de Forge, en el orden de Forge.NAMES. */
	public static final String[] WEAPONS = {"lanza", "hoja", "martillo", "estacas", "muralla_forja"};

	private static final Map<String, Identifier> IDS = new HashMap<>();

	/** opeope:hud/<nombre>. */
	public static Identifier id(String name) {
		Identifier id = IDS.get(name);
		if (id == null) {
			id = OpeOpeMod.id("hud/" + name);
			IDS.put(name, id);
		}
		return id;
	}

	public static boolean mouse(String name) {
		return name != null && name.startsWith("raton_");
	}

	public static int width(String name) {
		if (mouse(name)) return 7;
		return "candado".equals(name) ? 8 : 12;
	}

	public static int height(String name) {
		if (mouse(name)) return 9;
		return "candado".equals(name) ? 8 : 12;
	}

	public static String weapon(int index) {
		return index >= 0 && index < WEAPONS.length ? WEAPONS[index] : "lanza";
	}
}
