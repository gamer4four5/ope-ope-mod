package dev.opeope.client.screen;

import dev.opeope.client.input.KeyConflicts;
import dev.opeope.client.input.Keys;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.ConfirmScreen;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.tooltip.Tooltip;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.option.GameOptions;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.text.Text;
import org.lwjgl.glfw.GLFW;

/**
 * «Teclas de la fruta»: cada tecla con su choque (rojo: nunca funcionaría; ámbar: Minecraft también la usa; gris: está
 * bien, pero conviene saberlo). Clic en la tecla y pulsa la nueva; Esc la deja sin tecla. Se guarda al momento.
 */
public class KeysScreen extends Screen {
	private static final int TEXT2 = 0xFFA9C2CC, BAD = 0xFFFFB4AB, YELLOW = 0xFFFFFF55;

	private final Screen parent;
	private KeysListWidget list;
	private ButtonWidget resetAllButton;
	Keys waiting;
	Keys selected;
	private String refusedMsg = "";
	private long refusedAt;
	private double scroll;

	public KeysScreen(Screen parent) {
		super(Text.literal("Teclas de la fruta"));
		this.parent = parent;
	}

	@Override
	protected void init() {
		if (list != null) scroll = list.getScrollAmount();
		list = new KeysListWidget(this.client, this.width, this.height - 76, 32, 22, this);
		addDrawableChild(list);
		list.setScrollAmount(scroll);
		int bw = 100, gap = 4;
		int x = this.width / 2 - (bw * 3 + gap * 2) / 2;
		int y = this.height - 28;
		resetAllButton = addDrawableChild(ButtonWidget.builder(Text.literal("Restablecer todo"), b -> resetAll())
			.dimensions(x, y, bw, 20)
			.tooltip(Tooltip.of(Text.literal("Todas las teclas de la fruta vuelven a las de fábrica.")))
			.build());
		addDrawableChild(ButtonWidget.builder(Text.literal("Como el juego"), b -> likeTheGame())
			.dimensions(x + bw + gap, y, bw, 20)
			.tooltip(Tooltip.of(Text.literal("Las teclas del juego original: las de fábrica, pero Shambles en la E.")))
			.build());
		addDrawableChild(ButtonWidget.builder(Text.literal("Hecho"), b -> close())
			.dimensions(x + 2 * (bw + gap), y, bw, 20)
			.build());
	}

	/** Espera la próxima tecla (o botón lateral del ratón) para k. */
	public void beginRebind(Keys k) {
		waiting = k;
		selected = k;
	}

	/** Todas las teclas de fábrica, guardadas. Si la pantalla está abierta, deja de esperar. */
	public static void resetAll() {
		for (Keys k : Keys.values()) k.reset();
		Keys.save();
		if (MinecraftClient.getInstance().currentScreen instanceof KeysScreen ks) ks.waiting = null;
	}

	private void likeTheGame() {
		waiting = null;
		for (Keys k : Keys.values()) k.reset();
		Keys.SHAMBLES.key = InputUtil.fromTranslationKey("key.keyboard.e");
		Keys.save();
		GameOptions o = this.client.options;
		if (Keys.SHAMBLES.key.equals(KeyBindingHelper.getBoundKeyOf(o.inventoryKey))) {
			this.client.setScreen(new ConfirmScreen(yes -> {
				if (yes) {
					o.inventoryKey.setBoundKey(InputUtil.fromTranslationKey("key.keyboard.i"));
					KeyBinding.updateKeysByCode();
					o.write();
				}
				this.client.setScreen(this);
			}, Text.literal("Como el juego"), Text.literal("El inventario de Minecraft también usa la E. ¿Pasarlo a la I?")));
		}
	}

	private void refuse(String msg) {
		refusedMsg = msg;
		refusedAt = System.currentTimeMillis();
	}

	private void bind(InputUtil.Key key) {
		Keys k = waiting;
		waiting = null;
		if (k == null) return;
		k.key = key;
		selected = k;
		Keys.save();
	}

	@Override
	public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
		if (waiting != null) {
			if (keyCode == GLFW.GLFW_KEY_ESCAPE) {
				bind(InputUtil.UNKNOWN_KEY);
			} else if (keyCode == GLFW.GLFW_KEY_UNKNOWN) {
				refuse("Esa tecla no se puede usar: prueba con otra");
			} else {
				bind(InputUtil.fromKeyCode(keyCode, scanCode));
			}
			return true;
		}
		return super.keyPressed(keyCode, scanCode, modifiers);
	}

	@Override
	public boolean mouseClicked(double mouseX, double mouseY, int button) {
		if (waiting != null) {
			if (button == GLFW.GLFW_MOUSE_BUTTON_LEFT || button == GLFW.GLFW_MOUSE_BUTTON_RIGHT) {
				refuse("Los clics ya son de la fruta y de Minecraft");
			} else {
				bind(InputUtil.Type.MOUSE.createFromCode(button));
			}
			return true;
		}
		return super.mouseClicked(mouseX, mouseY, button);
	}

	@Override
	public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
		boolean anyChanged = false;
		for (Keys k : Keys.values()) {
			if (!k.key.equals(InputUtil.fromTranslationKey(k.def))) {
				anyChanged = true;
				break;
			}
		}
		resetAllButton.active = anyChanged;
		super.render(ctx, mouseX, mouseY, delta);
		ctx.drawCenteredTextWithShadow(this.textRenderer, this.title, this.width / 2, 12, 0xFFFFFFFF);

		String info;
		int color;
		if (!refusedMsg.isEmpty() && System.currentTimeMillis() - refusedAt < 3000) {
			info = refusedMsg;
			color = BAD;
		} else if (waiting != null) {
			info = "Pulsa la nueva tecla para «" + waiting.nombre() + "» · Esc: sin tecla";
			color = YELLOW;
		} else {
			Keys focus = list.hoveredKey();
			if (focus == null) focus = selected;
			KeyConflicts.Conflict c = focus == null ? null : KeyConflicts.of(focus);
			if (c != null && c.level() != KeyConflicts.Level.NONE) {
				info = focus.nombre() + ": " + c.message();
				color = KeysListWidget.levelColor(c.level());
			} else {
				info = "Alt+Tab cambia de ventana en Windows: si molesta, cambia Shambles o la segunda fila";
				color = TEXT2;
			}
		}
		int max = this.width - 16;
		if (this.textRenderer.getWidth(info) > max) info = this.textRenderer.trimToWidth(info, max - this.textRenderer.getWidth("…")) + "…";
		ctx.drawCenteredTextWithShadow(this.textRenderer, info, this.width / 2, this.height - 40, color);
	}

	@Override
	public void close() {
		this.client.setScreen(parent);
	}

	@Override
	public boolean shouldPause() {
		return true;
	}
}
