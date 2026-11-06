package dev.opeope.client.input;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.opeope.OpeOpeMod;
import dev.opeope.client.config.OpeSettings;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.util.InputUtil;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.EnumMap;
import java.util.Map;

/**
 * Las teclas de la fruta, las mismas que en el juego. No son KeyBindings de Minecraft a propósito: Minecraft solo deja
 * una acción por tecla y la Q, la F o el 1 dejarían de hacer lo suyo para quien no tiene la fruta. Aquí la tecla solo se
 * la queda la fruta si la has comido. Se cambian en la pantalla «Teclas» (o en config/opeope-teclas.json, con nombres
 * como key.keyboard.q o key.mouse.4). El libro (H) sí es una tecla de Minecraft: está en Opciones → Controles.
 */
public enum Keys {
	ROOM("key.keyboard.q", Group.MAIN, "ROOM", "room"),
	SHAMBLES("key.keyboard.left.alt", Group.MAIN, "Shambles", "shambles"),
	TAKT("key.keyboard.r", Group.MAIN, "Takt", "takt"),
	MARKER("key.keyboard.g", Group.MAIN, "Piedra marcador", "piedra"),
	SCAN("key.keyboard.f", Group.MAIN, "Scan · despierto: R-ROOM", "scan"),
	RADIO_KNIFE("key.keyboard.z", Group.MAIN, "Radio Knife · despierto: K-ROOM", "radio_knife"),
	INJECTION("key.keyboard.x", Group.MAIN, "Injection Shot · despierto: Anesthesia", "injection"),
	GAMMA("key.keyboard.c", Group.MAIN, "Gamma Knife · despierto: Shock Wille", "gamma"),
	AWAKEN("key.keyboard.v", Group.MAIN, "Despertar", "despertar"),
	CURTAIN("key.keyboard.b", Group.MAIN, "Curtain · despierto: Silent", "curtain"),
	ROW2("key.keyboard.tab", Group.MAIN, "Segunda fila", "fila2"),
	ROW2_MOUSE_A("key.mouse.4", Group.MAIN, "Segunda fila (botón del ratón A)", "fila2"),
	ROW2_MOUSE_B("key.mouse.5", Group.MAIN, "Segunda fila (botón del ratón B)", "fila2"),

	T_STERBEN("key.keyboard.z", Group.ROW2, "Sterben", "sterben"),
	T_SOULS("key.keyboard.x", Group.ROW2, "Cambio de almas", "almas"),
	T_UNION("key.keyboard.c", Group.ROW2, "Unión · mantener: Cirugía", "union"),
	T_RAIN("key.keyboard.v", Group.ROW2, "Lluvia", "lluvia"),
	T_PILLAR("key.keyboard.q", Group.ROW2, "Pilar", "pilar"),
	T_WALL("key.keyboard.e", Group.ROW2, "Muralla", "muralla"),
	T_CHASM("key.keyboard.r", Group.ROW2, "Sima", "sima"),
	T_PLATEAU("key.keyboard.f", Group.ROW2, "Meseta", "meseta"),
	T_PATTERN("key.keyboard.1", Group.ROW2, "Patrón de corte", "patron"),
	T_REACH("key.keyboard.2", Group.ROW2, "Alcance del corte", "alcance"),
	T_SUSPEND("key.keyboard.3", Group.ROW2, "Suspender lo cortado", "suspender"),
	T_MES("key.keyboard.4", Group.ROW2, "Mes", "mes"),
	T_COUNTER("key.keyboard.5", Group.ROW2, "Counter Shock · despierto: Puncture Wille", "counter"),

	R_ORBIT("key.keyboard.f", Group.TAKT, "Órbita / a la mano", "orbita"),
	R_PARK("key.keyboard.g", Group.TAKT, "Aparcar", "aparcar"),
	R_SETDOWN("key.keyboard.v", Group.TAKT, "Posar", "posar"),
	R_DROP("key.keyboard.b", Group.TAKT, "Soltar", "soltar"),
	R_ZONE("key.keyboard.e", Group.TAKT, "Zona", "zona"),
	R_CUT("key.keyboard.c", Group.TAKT, "Lo cortado", "lo_cortado"),
	R_GATHER("key.keyboard.z", Group.TAKT, "Rejuntar", "rejuntar"),
	R_UNITE("key.keyboard.x", Group.TAKT, "Unir todo", "unir"),
	R_FORGE1("key.keyboard.1", Group.TAKT, "Forjar: lanza", "lanza"),
	R_FORGE2("key.keyboard.2", Group.TAKT, "Forjar: hoja colosal", "hoja"),
	R_FORGE3("key.keyboard.3", Group.TAKT, "Forjar: martillo", "martillo"),
	R_FORGE4("key.keyboard.4", Group.TAKT, "Forjar: estacas", "estacas"),
	R_FORGE5("key.keyboard.5", Group.TAKT, "Forjar: muralla", "muralla_forja");

	/** MAIN: la fila de arriba (con los que abren las otras filas); ROW2: con Tab; TAKT: las órdenes con R. */
	public enum Group { MAIN, ROW2, TAKT }

	private static final String UNKNOWN_NAME = "key.keyboard.unknown";
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

	public final String def;
	public InputUtil.Key key;
	private final Group group;
	private final String nombre;
	private final String icono;

	Keys(String def, Group group, String nombre, String icono) {
		this.def = def;
		this.key = InputUtil.fromTranslationKey(def);
		this.group = group;
		this.nombre = nombre;
		this.icono = icono;
	}

	public boolean matches(InputUtil.Type type, int code) {
		return key != InputUtil.UNKNOWN_KEY && key.getCategory() == type && key.getCode() == code;
	}

	/** Nombre corto para el HUD: «Q», «Tab», «Alt izq.». */
	public String label() {
		return key.getLocalizedText().getString();
	}

	public Group group() {
		return group;
	}

	/** El nombre en español para las pantallas: «Piedra marcador», «Forjar: lanza». */
	public String nombre() {
		return nombre;
	}

	/** El icono del HUD (opeope:hud/&lt;icono&gt;). */
	public String icono() {
		return icono;
	}

	/** Vuelve a la tecla de fábrica (no guarda). */
	public void reset() {
		key = InputUtil.fromTranslationKey(def);
	}

	private static Path file() {
		return FabricLoader.getInstance().getConfigDir().resolve("opeope-teclas.json");
	}

	public static void load() {
		Path path = file();
		if (Files.exists(path)) {
			try {
				JsonElement el = JsonParser.parseString(Files.readString(path, StandardCharsets.UTF_8));
				if (el == null || !el.isJsonObject()) throw new IllegalStateException("no es un objeto JSON");
				JsonObject obj = el.getAsJsonObject();
				for (Keys k : values()) {
					JsonElement v = obj.get(k.name());
					if (v == null || !v.isJsonPrimitive()) continue;
					String name = v.getAsString();
					try {
						// «sin tecla» se guarda como key.keyboard.unknown y así se queda
						k.key = UNKNOWN_NAME.equals(name) ? InputUtil.UNKNOWN_KEY : InputUtil.fromTranslationKey(name);
					} catch (Exception e) {
						OpeOpeMod.LOG.warn("Tecla desconocida para {} en {}: {}", k.name(), path, name);
					}
				}
			} catch (Exception e) {
				OpeOpeMod.LOG.warn("No se pudo leer {} ({}): se guarda como .bak y se usan las teclas de fábrica", path, e.toString());
				try {
					Files.move(path, path.resolveSibling(path.getFileName().toString() + ".bak"), StandardCopyOption.REPLACE_EXISTING);
				} catch (Exception ignored) {
					// se sobrescribe abajo
				}
			}
		}
		save();   // reescribe el fichero: entradas nuevas con su valor y fuera las que ya no existen (GUIDE)
	}

	/** Guarda todas las teclas en el formato de siempre (nombre → key.keyboard.x). */
	public static void save() {
		JsonObject out = new JsonObject();
		for (Keys k : values()) out.addProperty(k.name(), k.key.getTranslationKey());
		try {
			OpeSettings.writeAtomic(file(), GSON.toJson(out));
		} catch (Exception e) {
			OpeOpeMod.LOG.warn("No se pudieron guardar las teclas de la fruta: {}", e.toString());
		}
	}

	public static Map<Keys, InputUtil.Key> snapshot() {
		Map<Keys, InputUtil.Key> m = new EnumMap<>(Keys.class);
		for (Keys k : values()) m.put(k, k.key);
		return m;
	}

	/** Vuelve a poner las teclas de una copia de {@link #snapshot()} y las guarda. */
	public static void restore(Map<Keys, InputUtil.Key> m) {
		if (m == null) return;
		for (Keys k : values()) {
			InputUtil.Key v = m.get(k);
			if (v != null) k.key = v;
		}
		save();
	}
}
