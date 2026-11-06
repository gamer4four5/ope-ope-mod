package dev.opeope.client.input;

import dev.opeope.client.config.OpeSettings;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.GameOptions;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.text.Text;

/**
 * Los choques de cada tecla de la fruta. ERROR (rojo): una de las dos nunca funcionaría. WARN (ámbar): Minecraft usa la
 * misma tecla y, con la fruta, gana la fruta. INFO (gris): está bien así, pero conviene saberlo.
 */
public final class KeyConflicts {
	private KeyConflicts() {}

	public enum Level { NONE, INFO, WARN, ERROR }

	public record Conflict(Level level, String message) {}

	private static final Conflict NONE = new Conflict(Level.NONE, "");

	static boolean row2Activator(Keys k) {
		return k == Keys.ROW2 || k == Keys.ROW2_MOUSE_A || k == Keys.ROW2_MOUSE_B;
	}

	private static boolean unbound(InputUtil.Key key) {
		return key == null || InputUtil.UNKNOWN_KEY.equals(key);
	}

	private static String what(Keys k) {
		return switch (k) {
			case ROOM -> "el ROOM";
			case TAKT -> "Takt y sus órdenes";
			default -> "la segunda fila";
		};
	}

	public static Conflict of(Keys k) {
		InputUtil.Key key = k.key;
		if (unbound(key)) {
			if (k == Keys.ROOM || k == Keys.TAKT || k == Keys.ROW2) return new Conflict(Level.ERROR, "Sin tecla: hace falta una para " + what(k) + ".");
			return new Conflict(Level.INFO, "Sin tecla.");
		}
		String name = KeyLabels.name(key);

		// ERROR: dos acciones de la misma fila con la misma tecla
		for (Keys o : Keys.values()) {
			if (o == k || o.group() != k.group() || !key.equals(o.key)) continue;
			if (row2Activator(k) && row2Activator(o)) continue;   // las dos abren la segunda fila: no estorba
			if (row2Activator(o)) return new Conflict(Level.ERROR, name + " ya abre la segunda fila: aquí nunca llegaría.");
			if (o == Keys.TAKT) return new Conflict(Level.ERROR, name + " ya es Takt: aquí nunca llegaría.");
			return new Conflict(Level.ERROR, "Choca con «" + o.nombre() + "»: las dos usan " + name + ".");
		}
		if (k.group() == Keys.Group.ROW2) {
			for (Keys a : new Keys[]{Keys.ROW2, Keys.ROW2_MOUSE_A, Keys.ROW2_MOUSE_B}) {
				if (key.equals(a.key)) return new Conflict(Level.ERROR, name + " abre la segunda fila: con ella nunca llegaría aquí.");
			}
		}
		if (k.group() == Keys.Group.TAKT) {
			if (key.equals(Keys.TAKT.key)) return new Conflict(Level.ERROR, name + " es Takt: la orden nunca llegaría.");
			for (Keys a : new Keys[]{Keys.ROW2, Keys.ROW2_MOUSE_A, Keys.ROW2_MOUSE_B}) {
				if (key.equals(a.key)) return new Conflict(Level.ERROR, name + " abre la segunda fila: la orden nunca llegaría.");
			}
		}

		MinecraftClient mc = MinecraftClient.getInstance();
		GameOptions opt = mc == null ? null : mc.options;
		if (k.group() == Keys.Group.MAIN) {
			if (opt != null) {
				String sneak = KeyLabels.name(KeyBindingHelper.getBoundKeyOf(opt.sneakKey));
				if (key.equals(KeyBindingHelper.getBoundKeyOf(opt.dropKey)) || key.equals(KeyBindingHelper.getBoundKeyOf(opt.swapHandsKey))) {
					KeyBinding v = key.equals(KeyBindingHelper.getBoundKeyOf(opt.dropKey)) ? opt.dropKey : opt.swapHandsKey;
					return new Conflict(Level.INFO, "Con " + sneak + " hace lo de Minecraft («" + vanillaName(v) + "»).");
				}
				if (k == Keys.ROW2 && OpeSettings.get().listaJugadoresTab && key.equals(KeyBindingHelper.getBoundKeyOf(opt.playerListKey))) {
					return new Conflict(Level.INFO, "En multijugador, mantenida sin pulsar nada más enseña la lista de jugadores.");
				}
				for (KeyBinding kb : opt.allKeys) {
					if (kb == null || !key.equals(KeyBindingHelper.getBoundKeyOf(kb))) continue;
					return new Conflict(Level.WARN, "Minecraft la usa para «" + vanillaName(kb) + "»: con la fruta gana la fruta.");
				}
			}
			return NONE;
		}

		// ROW2 y TAKT: van en capa, solo con su tecla mantenida (o fija)
		Keys activator = k.group() == Keys.Group.ROW2 ? Keys.ROW2 : Keys.TAKT;
		String act = KeyLabels.name(activator.key);
		for (Keys o : Keys.values()) {
			if (o.group() == Keys.Group.MAIN && key.equals(o.key)) {
				return new Conflict(Level.INFO, "Solo con " + act + " mantenida: sin ella es «" + o.nombre() + "».");
			}
		}
		if (opt != null) {
			for (KeyBinding kb : opt.allKeys) {
				if (kb == null || !key.equals(KeyBindingHelper.getBoundKeyOf(kb))) continue;
				return new Conflict(Level.INFO, "Solo con " + act + " mantenida: sin ella es «" + vanillaName(kb) + "» de Minecraft.");
			}
		}
		return NONE;
	}

	private static String vanillaName(KeyBinding kb) {
		return Text.translatable(kb.getTranslationKey()).getString();
	}
}
