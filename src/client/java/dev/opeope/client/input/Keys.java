package dev.opeope.client.input;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import dev.opeope.OpeOpeMod;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.util.InputUtil;

import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Las teclas de la fruta, las mismas que en el juego. No son KeyBindings de Minecraft a propósito: Minecraft solo deja
 * una acción por tecla y la Q, la F o el 1 dejarían de hacer lo suyo para quien no tiene la fruta. Aquí la tecla solo se
 * la queda la fruta si la has comido. Se cambian en config/opeope-teclas.json (nombres como key.keyboard.q o key.mouse.4).
 */
public enum Keys {
	ROOM("key.keyboard.q"),
	SHAMBLES("key.keyboard.left.alt"),
	TAKT("key.keyboard.r"),
	MARKER("key.keyboard.g"),
	SCAN("key.keyboard.f"),
	RADIO_KNIFE("key.keyboard.z"),
	INJECTION("key.keyboard.x"),
	GAMMA("key.keyboard.c"),
	AWAKEN("key.keyboard.v"),
	CURTAIN("key.keyboard.b"),
	GUIDE("key.keyboard.h"),
	ROW2("key.keyboard.tab"),
	ROW2_MOUSE_A("key.mouse.4"),
	ROW2_MOUSE_B("key.mouse.5"),

	T_STERBEN("key.keyboard.z"),
	T_SOULS("key.keyboard.x"),
	T_UNION("key.keyboard.c"),
	T_RAIN("key.keyboard.v"),
	T_PILLAR("key.keyboard.q"),
	T_WALL("key.keyboard.e"),
	T_CHASM("key.keyboard.r"),
	T_PLATEAU("key.keyboard.f"),
	T_PATTERN("key.keyboard.1"),
	T_REACH("key.keyboard.2"),
	T_SUSPEND("key.keyboard.3"),
	T_MES("key.keyboard.4"),
	T_COUNTER("key.keyboard.5"),

	R_ORBIT("key.keyboard.f"),
	R_PARK("key.keyboard.g"),
	R_SETDOWN("key.keyboard.v"),
	R_DROP("key.keyboard.b"),
	R_ZONE("key.keyboard.e"),
	R_CUT("key.keyboard.c"),
	R_GATHER("key.keyboard.z"),
	R_UNITE("key.keyboard.x"),
	R_FORGE1("key.keyboard.1"),
	R_FORGE2("key.keyboard.2"),
	R_FORGE3("key.keyboard.3"),
	R_FORGE4("key.keyboard.4"),
	R_FORGE5("key.keyboard.5");

	public final String def;
	public InputUtil.Key key;

	Keys(String def) {
		this.def = def;
		this.key = InputUtil.fromTranslationKey(def);
	}

	public boolean matches(InputUtil.Type type, int code) {
		return key != InputUtil.UNKNOWN_KEY && key.getCategory() == type && key.getCode() == code;
	}

	/** Nombre corto para el HUD: «Q», «Tab», «Alt izq.». */
	public String label() {
		return key.getLocalizedText().getString();
	}

	private static Path file() {
		return FabricLoader.getInstance().getConfigDir().resolve("opeope-teclas.json");
	}

	public static void load() {
		Gson gson = new GsonBuilder().setPrettyPrinting().create();
		Path path = file();
		try {
			if (Files.exists(path)) {
				try (Reader r = Files.newBufferedReader(path)) {
					JsonObject obj = gson.fromJson(r, JsonObject.class);
					for (Keys k : values()) {
						if (obj != null && obj.has(k.name())) {
							InputUtil.Key parsed = InputUtil.fromTranslationKey(obj.get(k.name()).getAsString());
							if (parsed != InputUtil.UNKNOWN_KEY) k.key = parsed;
						}
					}
				}
			}
			JsonObject out = new JsonObject();
			for (Keys k : values()) out.addProperty(k.name(), k.key.getTranslationKey());
			Files.createDirectories(path.getParent());
			try (Writer w = Files.newBufferedWriter(path)) {
				gson.toJson(out, w);
			}
		} catch (Exception e) {
			OpeOpeMod.LOG.warn("No se pudo leer {}: {}", path, e.toString());
		}
	}
}
