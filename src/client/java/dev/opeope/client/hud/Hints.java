package dev.opeope.client.hud;

import dev.opeope.client.ClientState;
import dev.opeope.client.config.OpeSettings;
import dev.opeope.client.input.KeyLabels;
import dev.opeope.client.input.Keys;
import dev.opeope.client.input.OpeKeyBindings;
import dev.opeope.registry.ModItems;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.MinecraftClient;

import java.util.ArrayList;
import java.util.List;

/**
 * Las pistas de la tarjeta de arriba a la derecha (spec §3.7), según lo que estás haciendo. Las teclas son las que
 * tienes puestas, y la redacción cambia con los modos de Ajustes («mantén» o «toca»).
 */
public final class Hints {
	private Hints() {}

	public record Context(String id, String title, String icon, int accent, List<Ui.Line> rows, boolean footer) {}

	static KeyLabels.Glyph key(Keys k) {
		return KeyLabels.of(k);
	}

	static KeyLabels.Glyph book() {
		return KeyLabels.of(KeyBindingHelper.getBoundKeyOf(OpeKeyBindings.LIBRO));
	}

	static KeyLabels.Glyph sneak(MinecraftClient mc) {
		return KeyLabels.of(KeyBindingHelper.getBoundKeyOf(mc.options.sneakKey));
	}

	static boolean heartInHand(MinecraftClient mc) {
		return mc.player != null && ModItems.HEART != null && mc.player.getMainHandStack().isOf(ModItems.HEART);
	}

	/** El contexto de ahora (sin contar los primeros pasos), o null si no hay fruta. */
	public static String currentId(MinecraftClient mc) {
		if (mc.player == null || !ClientState.hasFruit()) return null;
		if (ClientState.gravityFruit()) {
			if (ClientState.taktCount > 0) return "grav_lev";
			if (ClientState.gravityRiding()) return "grav_roca";
			return "grav_inicio";
		}
		if (heartInHand(mc)) return "corazon";
		if (ClientState.union()) return "union";
		if (ClientState.taktCount > 0) return "takt";
		if (ClientState.ownRoom() != null) return "room";
		return "inicio";
	}

	private static Ui.Line row(KeyLabels.Glyph g, String text) {
		return new Ui.Line().key(g).action(text);
	}

	private static Ui.Line chord(KeyLabels.Glyph a, KeyLabels.Glyph b, String text) {
		return new Ui.Line().key(a).plus().key(b).action(text);
	}

	/** «[libro] todo en el libro», al pie. */
	public static Ui.Line footer() {
		return new Ui.Line().key(book()).text("todo en el libro", Theme.TEXT3);
	}

	/** La última fila cuando no caben todas: «… [libro] más en el libro». */
	public static Ui.Line more() {
		return new Ui.Line().text("…", Theme.TEXT3).key(book()).text("más en el libro", Theme.TEXT3);
	}

	public static Context build(String id, MinecraftClient mc) {
		if (id == null) return null;
		OpeSettings st = OpeSettings.get();
		boolean roomToggle = st.modoRoom == OpeSettings.ModoRoom.ALTERNAR;
		boolean tabToggle = st.modoTab == OpeSettings.ModoFila.ALTERNAR;
		boolean bisturiToggle = st.modoBisturi == OpeSettings.Modo2.ALTERNAR;
		KeyLabels.Glyph click = KeyLabels.mouseLeft(), right = KeyLabels.mouseRight(), wheel = KeyLabels.wheel();
		List<Ui.Line> rows = new ArrayList<>();
		if ("grav_inicio".equals(id)) {
			rows.add(row(key(Keys.ROOM), "mantén: Gravito · Mayús: vector"));
			rows.add(row(key(Keys.TAKT), "levitar roca/mob · Mayús: prensa"));
			rows.add(row(key(Keys.SHAMBLES), "surfear en roca · Mayús: veta"));
			rows.add(row(key(Keys.RADIO_KNIFE), "Tengai Shinsei (meteorito)"));
			rows.add(row(key(Keys.INJECTION), "Moko · " + KeyLabels.text(Keys.GAMMA.key) + " Jigoku Tabi"));
			rows.add(row(key(Keys.SCAN), "Haki Ciego · " + KeyLabels.text(Keys.CURTAIN.key) + " Repulsión"));
			rows.add(row(key(Keys.ROW2), "Tab: Dressrosa / Cristalizar"));
			return new Context(id, "GRAVITY · FUJITORA", "room", Theme.ROOM, rows, false);
		}
		if ("grav_roca".equals(id)) {
			rows.add(row(key(Keys.SHAMBLES), "saltar y lanzar la roca"));
			rows.add(row(sneak(mc), "bajarse de la roca"));
			rows.add(new Ui.Line().text("WASD ", Theme.HI).action("dirigir roca · ").text("Espacio ", Theme.HI).action("subir · ").text("Ctrl ", Theme.HI).action("turbo"));
			rows.add(row(key(Keys.RADIO_KNIFE), "Tengai Shinsei desde la roca"));
			rows.add(row(key(Keys.ROOM), "mantén: presión gravitatoria"));
			return new Context(id, "ROCA GRAVITATORIA", "shambles", Theme.ROOM, rows, false);
		}
		if ("grav_lev".equals(id)) {
			rows.add(row(click, "lanzar a velocidad hipersónica"));
			rows.add(row(right, "estampar contra el suelo (+G)"));
			rows.add(chord(sneak(mc), key(Keys.TAKT), "prensar rocas (minerales)"));
			rows.add(row(wheel, "acercar o alejar masas"));
			return new Context(id, "LEVITACIÓN", "takt", Theme.SOFT, rows, false);
		}
		if ("inicio".equals(id)) {
			rows.add(row(key(Keys.ROOM), roomToggle ? "toca: abre el ROOM" : "mantén: abre el ROOM"));
			rows.add(row(key(Keys.TAKT), "Takt: coge lo que miras"));
			rows.add(row(key(Keys.SHAMBLES), "Shambles: intercambia"));
			rows.add(row(key(Keys.ROW2), tabToggle ? "toca: segunda fila" : "mantén: segunda fila"));
			rows.add(row(book(), "libro, ajustes y teclas"));
			return new Context(id, "PARA EMPEZAR", "fruta", Theme.SOFT, rows, false);
		}
		if ("room".equals(id)) {
			boolean kikoku = mc.player != null && ModItems.KIKOKU != null && mc.player.getMainHandStack().isOf(ModItems.KIKOKU);
			rows.add(row(click, kikoku ? "cada tajo corta" : "arrastra: Amputate"));
			rows.add(row(right, bisturiToggle ? "toca: Bisturí" : "mantén: Bisturí"));
			rows.add(row(key(Keys.SHAMBLES), "Shambles: ir o cambiar"));
			rows.add(row(key(Keys.TAKT), "Takt: arranca el suelo"));
			rows.add(chord(key(Keys.TAKT), wheel, "tamaño del trozo"));
			if (roomToggle) {
				rows.add(row(key(Keys.ROOM), "toca: modo ROOM"));
				rows.add(new Ui.Line().key(wheel).action("tamaño").text(" · ", Theme.TEXT3)
					.key(click).action("lanzar").text(" · ", Theme.TEXT3).key(right).action("traer"));
			} else {
				rows.add(chord(key(Keys.ROOM), wheel, "cambia el tamaño"));
				rows.add(chord(key(Keys.ROOM), click, "lanzarlo a la mira"));
				rows.add(chord(key(Keys.ROOM), right, "traerlo hasta ti"));
			}
			rows.add(row(sneak(mc), "clics de Minecraft"));
			return new Context(id, "EN EL ROOM", "room", Theme.ROOM, rows, true);
		}
		if ("takt".equals(id)) {
			rows.add(row(click, ClientState.weapon >= 0 ? "usar el arma" : "lanzar · mantén: ráfaga"));
			rows.add(row(right, "mantén: todo a la vez"));
			rows.add(row(key(Keys.TAKT), "otro · mantén: órdenes"));
			rows.add(new Ui.Line().key(key(Keys.TAKT)).key(key(Keys.TAKT)).action("la zona cercana"));
			rows.add(chord(key(Keys.TAKT), key(Keys.R_SETDOWN), "posar en su silueta"));
			rows.add(chord(key(Keys.TAKT), wheel, "acercar o alejar"));
			rows.add(chord(key(Keys.ROW2), key(Keys.T_UNION), "coserlo a algo"));
			return new Context(id, "TAKT", "takt", Theme.TAKT, rows, true);
		}
		if ("corazon".equals(id)) {
			rows.add(row(key(Keys.RADIO_KNIFE), "apretar · mantén: aplastar"));
			rows.add(chord(key(Keys.ROW2), key(Keys.T_MES), "devolver o soltar"));
			return new Context(id, "CORAZÓN", "corazon", Theme.HEART, rows, true);
		}
		if ("union".equals(id)) {
			rows.add(row(click, "pieza y luego destino"));
			rows.add(row(right, "reconstruir"));
			rows.add(chord(key(Keys.ROW2), key(Keys.T_UNION), "salir · mantén: Cirugía"));
			return new Context(id, "UNIÓN", "union", Theme.UNION, rows, true);
		}
		return null;
	}
}
