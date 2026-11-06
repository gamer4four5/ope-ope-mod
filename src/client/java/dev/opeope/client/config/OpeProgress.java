package dev.opeope.client.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.opeope.OpeOpeMod;
import net.fabricmc.loader.api.FabricLoader;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * Lo que el jugador ya ha visto (config/opeope-progreso.json): cuánto tiempo lleva cada pista en pantalla, los avisos
 * de una sola vez y los primeros pasos. Se guarda cada 5 s si cambió algo.
 */
public final class OpeProgress {
	private OpeProgress() {}

	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static final Map<String, Float> HINTS = new LinkedHashMap<>();
	private static final Set<String> TIPS = new LinkedHashSet<>();
	private static int mask;
	private static boolean finished;
	private static boolean dirty;
	private static int ticks;

	private static Path file() {
		return FabricLoader.getInstance().getConfigDir().resolve("opeope-progreso.json");
	}

	public static void load() {
		clearAll();
		Path path = file();
		if (Files.exists(path)) {
			try {
				JsonElement el = JsonParser.parseString(Files.readString(path, StandardCharsets.UTF_8));
				if (el == null || !el.isJsonObject()) throw new IllegalStateException("no es un objeto JSON");
				JsonObject o = el.getAsJsonObject();
				JsonElement h = o.get("hints");
				if (h != null && h.isJsonObject()) {
					for (Map.Entry<String, JsonElement> e : h.getAsJsonObject().entrySet()) {
						try {
							float s = e.getValue().getAsFloat();
							if (Float.isFinite(s) && s > 0) HINTS.put(e.getKey(), s);
						} catch (Exception ignored) {
							// un valor raro: esa pista vuelve a empezar
						}
					}
				}
				JsonElement t = o.get("tips");
				if (t != null && t.isJsonArray()) {
					for (JsonElement e : t.getAsJsonArray()) {
						if (e != null && e.isJsonPrimitive()) TIPS.add(e.getAsString());
					}
				}
				JsonElement ob = o.get("onboarding");
				if (ob != null && ob.isJsonObject()) {
					JsonObject b = ob.getAsJsonObject();
					try {
						if (b.has("mask")) mask = b.get("mask").getAsInt() & 0x3F;
					} catch (Exception ignored) {
						mask = 0;
					}
					try {
						if (b.has("finished")) finished = b.get("finished").getAsBoolean();
					} catch (Exception ignored) {
						finished = false;
					}
				}
			} catch (Exception e) {
				OpeOpeMod.LOG.warn("El progreso de la fruta ({}) estaba dañado: se guarda como .bak y se empieza de cero ({})", path, e.toString());
				OpeSettings.backup(path);
				clearAll();
			}
		}
		save();
	}

	private static void clearAll() {
		HINTS.clear();
		TIPS.clear();
		mask = 0;
		finished = false;
		dirty = false;
	}

	public static void save() {
		JsonObject o = new JsonObject();
		o.addProperty("version", 1);
		JsonObject h = new JsonObject();
		for (Map.Entry<String, Float> e : HINTS.entrySet()) h.addProperty(e.getKey(), Math.round(e.getValue() * 10f) / 10f);
		o.add("hints", h);
		JsonArray t = new JsonArray();
		for (String s : TIPS) t.add(s);
		o.add("tips", t);
		JsonObject b = new JsonObject();
		b.addProperty("mask", mask);
		b.addProperty("finished", finished);
		o.add("onboarding", b);
		try {
			OpeSettings.writeAtomic(file(), GSON.toJson(o));
			dirty = false;
		} catch (Exception e) {
			OpeOpeMod.LOG.warn("No se pudo guardar el progreso de la fruta: {}", e.toString());
		}
	}

	/** Desde InputHandler.tick: guarda cada 100 ticks (5 s) si algo cambió. */
	public static void tick() {
		if (++ticks < 100) return;
		ticks = 0;
		if (dirty) save();
	}

	public static float hintSeconds(String id) {
		Float s = HINTS.get(id);
		return s == null ? 0f : s;
	}

	public static void addHintSeconds(String id, float s) {
		if (id == null || !(s > 0) || !Float.isFinite(s)) return;
		HINTS.merge(id, s, Float::sum);
		dirty = true;
	}

	/** «Volver a mostrar las pistas»: las tarjetas y los avisos de una vez vuelven a salir. */
	public static void resetHints() {
		HINTS.clear();
		TIPS.clear();
		save();
	}

	public static boolean tipSeen(String id) {
		return TIPS.contains(id);
	}

	public static void markTip(String id) {
		if (id != null && TIPS.add(id)) dirty = true;
	}

	public static int onboardingMask() {
		return mask;
	}

	public static void setOnboardingMask(int m) {
		m &= 0x3F;
		if (m != mask) {
			mask = m;
			dirty = true;
		}
	}

	public static boolean onboardingFinished() {
		return finished;
	}

	public static void setOnboardingFinished(boolean b) {
		if (b != finished) {
			finished = b;
			dirty = true;
		}
	}
}
