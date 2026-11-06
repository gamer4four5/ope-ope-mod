package dev.opeope.client.input;

import dev.opeope.client.screen.SettingsScreen;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.Screens;
import net.minecraft.client.gui.screen.option.ControlsOptionsScreen;
import net.minecraft.client.gui.tooltip.Tooltip;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.ClickableWidget;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.text.Text;
import org.lwjgl.glfw.GLFW;

import java.util.List;

/**
 * Las dos teclas de la fruta que sí son de Minecraft (Opciones → Controles → Ope Ope no Mi): el libro (H) y los
 * ajustes (sin tecla). Funcionan aunque no tengas la fruta. Además, Opciones → Controles gana un botón «Ope Ope no Mi…».
 */
public final class OpeKeyBindings {
	private OpeKeyBindings() {}

	public static final KeyBinding LIBRO = new KeyBinding("key.opeope.libro", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_H, "key.categories.opeope");
	public static final KeyBinding AJUSTES = new KeyBinding("key.opeope.ajustes", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_UNKNOWN, "key.categories.opeope");

	private static final String BUTTON = "Ope Ope no Mi…";
	private static boolean registered;

	public static void register() {
		if (registered) return;
		registered = true;
		KeyBindingHelper.registerKeyBinding(LIBRO);
		KeyBindingHelper.registerKeyBinding(AJUSTES);
		ScreenEvents.AFTER_INIT.register((client, screen, scaledWidth, scaledHeight) -> {
			if (!(screen instanceof ControlsOptionsScreen)) return;
			List<ClickableWidget> buttons = Screens.getButtons(screen);
			// la pantalla de Controles no se rehace al volver a ella ni al cambiar el tamaño: el botón ya puede estar
			for (ClickableWidget w : buttons) {
				if (w instanceof ButtonWidget && BUTTON.equals(w.getMessage().getString())) {
					w.setPosition(scaledWidth - 104, 6);
					return;
				}
			}
			buttons.add(ButtonWidget.builder(Text.literal(BUTTON), b -> client.setScreen(new SettingsScreen(screen)))
				.dimensions(scaledWidth - 104, 6, 98, 20)
				.tooltip(Tooltip.of(Text.literal("Ajustes, teclas y modos de la fruta")))
				.build());
		});
	}
}
