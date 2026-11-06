package dev.opeope.client.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import dev.opeope.OpeOpeMod;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.GameOptions;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * Los ajustes de la fruta (config/opeope-ajustes.json): cómo se ve el HUD, la accesibilidad y cómo responden las teclas.
 * Hay una sola instancia, que se cambia en su sitio: quien guarde {@link #get()} siempre ve los valores de ahora.
 */
public final class OpeSettings {
	public enum Interfaz { COMPLETA, SENCILLA, MINIMA }
	public enum Barra { ABAJO, VERTICAL, AL_USARLA, OCULTA }
	public enum Pistas { SIEMPRE, NUEVO, NUNCA }
	public enum TriState { MINECRAFT, SI, NO }
	public enum Destellos { MINECRAFT, SI, SUAVES, NO }
	public enum ModoRoom { MANTENER, ALTERNAR }
	public enum Quitar { TOQUE, DOBLE_TOQUE }
	public enum ModoFila { MANTENER, ALTERNAR, UNA_TECLA }
	public enum Modo2 { MANTENER, ALTERNAR }

	public int version = 1;
	public Interfaz interfaz = Interfaz.COMPLETA;
	public int tamano = 0;                       // -1, 0, 1
	public float opacidad = 0.85f;               // 0.40..1.00
	public Barra barra = Barra.ABAJO;
	public boolean avisoRoom = true;
	public Pistas pistas = Pistas.NUEVO;
	public TriState contraste = TriState.MINECRAFT;
	public boolean menosMovimiento = false;
	public float sacudidas = 1.0f;               // 0..1
	public Destellos destellos = Destellos.MINECRAFT;
	public boolean narrarRoom = true;
	public ModoRoom modoRoom = ModoRoom.MANTENER;
	public Quitar quitarRoom = Quitar.TOQUE;
	public ModoFila modoTab = ModoFila.MANTENER;
	public ModoFila modoR = ModoFila.MANTENER;
	public Modo2 modoBisturi = Modo2.MANTENER;
	public Modo2 modoCirugia = Modo2.MANTENER;
	public boolean listaJugadoresTab = true;
	public int toqueR = 320, dobleToque = 350, esperaR = 140, esperaCirugia = 400;   // ms

	private static final OpeSettings INSTANCE = new OpeSettings();
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

	private OpeSettings() {}

	public static OpeSettings get() {
		return INSTANCE;
	}

	private static Path file() {
		return FabricLoader.getInstance().getConfigDir().resolve("opeope-ajustes.json");
	}

	// ------------------------------------------------------------------ leer y guardar

	public static void load() {
		OpeSettings read = new OpeSettings();
		Path path = file();
		if (Files.exists(path)) {
			try {
				JsonElement el = JsonParser.parseString(Files.readString(path, StandardCharsets.UTF_8));
				if (el == null || !el.isJsonObject()) throw new IllegalStateException("no es un objeto JSON");
				read.read(el.getAsJsonObject());
			} catch (Exception e) {
				OpeOpeMod.LOG.warn("Los ajustes de la fruta ({}) estaban dañados: se guardan como .bak y se usan los de fábrica ({})", path, e.toString());
				backup(path);
				read = new OpeSettings();
			}
		}
		INSTANCE.copyFrom(read);
		save();
	}

	public static void save() {
		try {
			writeAtomic(file(), GSON.toJson(INSTANCE.write()));
		} catch (Exception e) {
			OpeOpeMod.LOG.warn("No se pudieron guardar los ajustes de la fruta: {}", e.toString());
		}
	}

	/** Los de fábrica, y se guardan. */
	public static void reset() {
		INSTANCE.copyFrom(new OpeSettings());
		save();
	}

	/** Escribe el fichero entero sin dejarlo a medias: primero a «.tmp» y luego se mueve encima. */
	public static void writeAtomic(Path path, String content) throws IOException {
		Path dir = path.getParent();
		if (dir != null) Files.createDirectories(dir);
		Path tmp = path.resolveSibling(path.getFileName().toString() + ".tmp");
		Files.writeString(tmp, content, StandardCharsets.UTF_8);
		try {
			Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
		} catch (IOException e) {
			Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING);
		}
	}

	/** Un fichero dañado se aparta como «.bak» para no perderlo. */
	static void backup(Path path) {
		try {
			Files.move(path, path.resolveSibling(path.getFileName().toString() + ".bak"), StandardCopyOption.REPLACE_EXISTING);
		} catch (Exception e) {
			OpeOpeMod.LOG.warn("No se pudo apartar {}: {}", path, e.toString());
		}
	}

	private void copyFrom(OpeSettings o) {
		version = 1;
		interfaz = o.interfaz;
		tamano = o.tamano;
		opacidad = o.opacidad;
		barra = o.barra;
		avisoRoom = o.avisoRoom;
		pistas = o.pistas;
		contraste = o.contraste;
		menosMovimiento = o.menosMovimiento;
		sacudidas = o.sacudidas;
		destellos = o.destellos;
		narrarRoom = o.narrarRoom;
		modoRoom = o.modoRoom;
		quitarRoom = o.quitarRoom;
		modoTab = o.modoTab;
		modoR = o.modoR;
		modoBisturi = o.modoBisturi;
		modoCirugia = o.modoCirugia;
		listaJugadoresTab = o.listaJugadoresTab;
		toqueR = o.toqueR;
		dobleToque = o.dobleToque;
		esperaR = o.esperaR;
		esperaCirugia = o.esperaCirugia;
	}

	private void read(JsonObject o) {
		interfaz = en(o, "interfaz", Interfaz.class, interfaz);
		tamano = in(o, "tamano", tamano, -1, 1);
		opacidad = fl(o, "opacidad", opacidad, 0.4f, 1f);
		barra = en(o, "barra", Barra.class, barra);
		avisoRoom = bo(o, "avisoRoom", avisoRoom);
		pistas = en(o, "pistas", Pistas.class, pistas);
		contraste = en(o, "contraste", TriState.class, contraste);
		menosMovimiento = bo(o, "menosMovimiento", menosMovimiento);
		sacudidas = fl(o, "sacudidas", sacudidas, 0f, 1f);
		destellos = en(o, "destellos", Destellos.class, destellos);
		narrarRoom = bo(o, "narrarRoom", narrarRoom);
		modoRoom = en(o, "modoRoom", ModoRoom.class, modoRoom);
		quitarRoom = en(o, "quitarRoom", Quitar.class, quitarRoom);
		modoTab = en(o, "modoTab", ModoFila.class, modoTab);
		modoR = en(o, "modoR", ModoFila.class, modoR);
		modoBisturi = en(o, "modoBisturi", Modo2.class, modoBisturi);
		modoCirugia = en(o, "modoCirugia", Modo2.class, modoCirugia);
		listaJugadoresTab = bo(o, "listaJugadoresTab", listaJugadoresTab);
		toqueR = in(o, "toqueR", toqueR, 200, 600);
		dobleToque = in(o, "dobleToque", dobleToque, 200, 600);
		esperaR = in(o, "esperaR", esperaR, 80, 400);
		esperaCirugia = in(o, "esperaCirugia", esperaCirugia, 200, 1000);
	}

	private JsonObject write() {
		JsonObject o = new JsonObject();
		o.addProperty("version", 1);
		o.addProperty("interfaz", interfaz.name());
		o.addProperty("tamano", tamano);
		o.addProperty("opacidad", opacidad);
		o.addProperty("barra", barra.name());
		o.addProperty("avisoRoom", avisoRoom);
		o.addProperty("pistas", pistas.name());
		o.addProperty("contraste", contraste.name());
		o.addProperty("menosMovimiento", menosMovimiento);
		o.addProperty("sacudidas", sacudidas);
		o.addProperty("destellos", destellos.name());
		o.addProperty("narrarRoom", narrarRoom);
		o.addProperty("modoRoom", modoRoom.name());
		o.addProperty("quitarRoom", quitarRoom.name());
		o.addProperty("modoTab", modoTab.name());
		o.addProperty("modoR", modoR.name());
		o.addProperty("modoBisturi", modoBisturi.name());
		o.addProperty("modoCirugia", modoCirugia.name());
		o.addProperty("listaJugadoresTab", listaJugadoresTab);
		o.addProperty("toqueR", toqueR);
		o.addProperty("dobleToque", dobleToque);
		o.addProperty("esperaR", esperaR);
		o.addProperty("esperaCirugia", esperaCirugia);
		return o;
	}

	private static JsonPrimitive prim(JsonObject o, String k) {
		JsonElement e = o.get(k);
		return e != null && e.isJsonPrimitive() ? e.getAsJsonPrimitive() : null;
	}

	private static <E extends Enum<E>> E en(JsonObject o, String k, Class<E> type, E def) {
		JsonPrimitive p = prim(o, k);
		if (p == null || !p.isString()) return def;
		try {
			return Enum.valueOf(type, p.getAsString());
		} catch (IllegalArgumentException e) {
			return def;
		}
	}

	private static boolean bo(JsonObject o, String k, boolean def) {
		JsonPrimitive p = prim(o, k);
		return p != null && p.isBoolean() ? p.getAsBoolean() : def;
	}

	private static int in(JsonObject o, String k, int def, int min, int max) {
		JsonPrimitive p = prim(o, k);
		if (p == null || !p.isNumber()) return def;
		double d = p.getAsDouble();
		if (Double.isNaN(d) || Double.isInfinite(d)) return def;
		return (int) Math.max(min, Math.min(max, Math.round(d)));
	}

	private static float fl(JsonObject o, String k, float def, float min, float max) {
		JsonPrimitive p = prim(o, k);
		if (p == null || !p.isNumber()) return def;
		double d = p.getAsDouble();
		if (Double.isNaN(d) || Double.isInfinite(d)) return def;
		return (float) Math.max(min, Math.min(max, d));
	}

	// ------------------------------------------------------------------ valores derivados

	private static GameOptions options() {
		MinecraftClient mc = MinecraftClient.getInstance();
		return mc == null ? null : mc.options;
	}

	private static double num(Object v, double def) {
		return v instanceof Number n ? n.doubleValue() : def;
	}

	public static boolean highContrast() {
		return switch (INSTANCE.contraste) {
			case SI -> true;
			case NO -> false;
			case MINECRAFT -> {
				GameOptions o = options();
				Object v = o == null ? null : o.getHighContrast().getValue();
				yield Boolean.TRUE.equals(v);
			}
		};
	}

	public static boolean reducedMotion() {
		return INSTANCE.menosMovimiento;
	}

	public static float shakeScale() {
		if (reducedMotion()) return 0f;
		GameOptions o = options();
		double distortion = o == null ? 1.0 : num(o.getDistortionEffectScale().getValue(), 1.0);
		return (float) (INSTANCE.sacudidas * distortion);
	}

	public static float fovKickScale() {
		if (reducedMotion()) return 0f;
		GameOptions o = options();
		return o == null ? 1f : (float) num(o.getFovEffectScale().getValue(), 1.0);
	}

	public static int flashLevel() {
		return switch (INSTANCE.destellos) {
			case SI -> 2;
			case SUAVES -> 1;
			case NO -> 0;
			case MINECRAFT -> {
				GameOptions o = options();
				Object v = o == null ? null : o.getHideLightningFlashes().getValue();
				yield Boolean.TRUE.equals(v) ? 0 : 2;
			}
		};
	}

	public static float panelOpacity() {
		return highContrast() ? Math.max(0.94f, INSTANCE.opacidad) : INSTANCE.opacidad;
	}
}
