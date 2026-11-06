package dev.opeope.client.screen;

import dev.opeope.client.hud.Ui;
import dev.opeope.client.input.KeyConflicts;
import dev.opeope.client.input.KeyLabels;
import dev.opeope.client.input.Keys;
import dev.opeope.client.input.OpeKeyBindings;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.Element;
import net.minecraft.client.gui.Selectable;
import net.minecraft.client.gui.navigation.GuiNavigation;
import net.minecraft.client.gui.navigation.GuiNavigationPath;
import net.minecraft.client.gui.screen.narration.NarrationMessageBuilder;
import net.minecraft.client.gui.screen.narration.NarrationPart;
import net.minecraft.client.gui.screen.option.KeybindsScreen;
import net.minecraft.client.gui.tooltip.Tooltip;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.ElementListWidget;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.text.Text;

import java.util.List;
import java.util.function.Supplier;

/** La lista de la pantalla «Teclas de la fruta»: una fila por tecla, con su icono, su choque y sus botones. */
public class KeysListWidget extends ElementListWidget<KeysListWidget.Entry> {
	private static final int TEXT = 0xFFEEF7FA, TEXT2 = 0xFFA9C2CC, TEXT3 = 0xFF6F8792;
	private static final int ROOM = 0xFF8FE3FF, SOFT = 0xFF9FE6FF, TAB = 0xFFC6B5FF, FORGE = 0xFFFFD9A0;
	private static final int OUT_WARN = 0xFFFFCF9A, DANGER = 0xFFFF6767;

	private final KeysScreen screen;

	public KeysListWidget(MinecraftClient client, int width, int height, int y, int itemHeight, KeysScreen screen) {
		super(client, width, height, y, itemHeight);
		this.screen = screen;
		addEntry(new CategoryEntry(() -> "Principal", ROOM));
		for (Keys k : Keys.values()) if (k.group() == Keys.Group.MAIN) addEntry(new KeyEntry(k));
		addEntry(new CategoryEntry(() -> "Segunda fila (con " + keyName(Keys.ROW2) + ")", TAB));
		for (Keys k : Keys.values()) if (k.group() == Keys.Group.ROW2) addEntry(new KeyEntry(k));
		addEntry(new CategoryEntry(() -> "Órdenes de Takt (con " + keyName(Keys.TAKT) + ")", SOFT));
		for (Keys k : Keys.values()) if (k.group() == Keys.Group.TAKT) addEntry(new KeyEntry(k));
		addEntry(new CategoryEntry(() -> "Libro y ajustes (Minecraft)", TEXT2));
		addEntry(new BindingEntry(OpeKeyBindings.LIBRO, "libro"));
		addEntry(new BindingEntry(OpeKeyBindings.AJUSTES, "engranaje"));
		addEntry(new ButtonEntry());
	}

	@Override
	public int getRowWidth() {
		return Math.min(400, this.width - 24);
	}

	@Override
	protected int getScrollbarX() {
		return Math.min(super.getScrollbarX(), this.getRight() - 6);
	}

	/** La tecla de la fila bajo el ratón, o null. */
	public Keys hoveredKey() {
		Entry e = getHoveredEntry();
		return e instanceof KeyEntry ke ? ke.k : null;
	}

	static String keyName(Keys k) {
		KeyLabels.Glyph g = KeyLabels.of(k);
		return g.icon() == null ? g.text() : KeyLabels.text(k.key);
	}

	static int levelColor(KeyConflicts.Level level) {
		return switch (level) {
			case NONE -> TEXT;
			case INFO -> TEXT2;
			case WARN -> OUT_WARN;
			case ERROR -> DANGER;
		};
	}

	private static int tint(Keys k) {
		if (k.name().startsWith("R_FORGE")) return FORGE;
		return switch (k.group()) {
			case MAIN -> TEXT;
			case ROW2 -> TAB;
			case TAKT -> SOFT;
		};
	}

	private static String ellipsize(TextRenderer tr, String s, int max) {
		if (max <= 0) return "";
		if (tr.getWidth(s) <= max) return s;
		return tr.trimToWidth(s, Math.max(0, max - tr.getWidth("…"))) + "…";
	}

	public abstract static class Entry extends ElementListWidget.Entry<Entry> {
	}

	/** Un título de grupo; no se puede seleccionar, pero el narrador lo lee. */
	class CategoryEntry extends Entry {
		private final Supplier<String> text;
		private final int color;

		CategoryEntry(Supplier<String> text, int color) {
			this.text = text;
			this.color = color;
		}

		@Override
		public void render(DrawContext ctx, int index, int y, int x, int entryWidth, int entryHeight, int mouseX, int mouseY, boolean hovered, float tickDelta) {
			TextRenderer tr = KeysListWidget.this.client.textRenderer;
			String s = text.get();
			int w = tr.getWidth(s);
			int cx = x + entryWidth / 2;
			int ty = y + entryHeight - 9 - 1;
			ctx.drawTextWithShadow(tr, s, cx - w / 2, ty, color);
			int line = (color & 0x00FFFFFF) | 0x40000000;
			if (cx - w / 2 - 8 > x + 4) ctx.fill(x + 4, ty + 4, cx - w / 2 - 6, ty + 5, line);
			if (cx + w / 2 + 8 < x + entryWidth - 4) ctx.fill(cx + w / 2 + 6, ty + 4, x + entryWidth - 4, ty + 5, line);
		}

		@Override
		public GuiNavigationPath getNavigationPath(GuiNavigation navigation) {
			return null;
		}

		@Override
		public List<? extends Element> children() {
			return List.of();
		}

		@Override
		public List<? extends Selectable> selectableChildren() {
			return List.of(new Selectable() {
				@Override
				public Selectable.SelectionType getType() {
					return Selectable.SelectionType.HOVERED;
				}

				@Override
				public void appendNarrations(NarrationMessageBuilder builder) {
					builder.put(NarrationPart.TITLE, Text.literal(text.get()));
				}
			});
		}
	}

	/** Una tecla de la fruta: icono, nombre, choque, la tecla (clic para cambiarla) y «Restablecer». */
	class KeyEntry extends Entry {
		final Keys k;
		private final ButtonWidget keyButton;
		private final ButtonWidget resetButton;
		private String lastTip = "";

		KeyEntry(Keys k) {
			this.k = k;
			this.keyButton = ButtonWidget.builder(Text.empty(), b -> screen.beginRebind(k))
				.dimensions(0, 0, 90, 20)
				.narrationSupplier(text -> Text.literal(k.nombre() + ": " + KeyLabels.text(k.key)))
				.build();
			this.resetButton = ButtonWidget.builder(Text.literal("Restablecer"), b -> {
					k.reset();
					Keys.save();
				})
				.dimensions(0, 0, 64, 20)
				.narrationSupplier(text -> Text.literal("Restablecer " + k.nombre()))
				.build();
		}

		@Override
		public void render(DrawContext ctx, int index, int y, int x, int entryWidth, int entryHeight, int mouseX, int mouseY, boolean hovered, float tickDelta) {
			TextRenderer tr = KeysListWidget.this.client.textRenderer;
			KeyConflicts.Conflict c = KeyConflicts.of(k);
			boolean waiting = screen.waiting == k;
			if (waiting || screen.selected == k) ctx.fill(x - 2, y - 2, x + entryWidth - 2, y + entryHeight + 2, 0x22FFFFFF);
			else if (hovered) ctx.fill(x - 2, y - 2, x + entryWidth - 2, y + entryHeight + 2, 0x12FFFFFF);

			int right = x + entryWidth - 4;
			resetButton.setPosition(right - 64, y - 1);
			keyButton.setPosition(right - 64 - 4 - 90, y - 1);
			resetButton.active = !k.key.equals(InputUtil.fromTranslationKey(k.def));

			Ui.drawIconNow(ctx, k.icono(), x + 2, y + (entryHeight - 12) / 2, 12, tint(k));
			int nameX = x + 18;
			int markerX = keyButton.getX() - 12;
			String name = ellipsize(tr, k.nombre(), markerX - 4 - nameX);
			ctx.drawTextWithShadow(tr, name, nameX, y + (entryHeight - 8) / 2, TEXT);

			int my = y + (entryHeight - 12) / 2;
			switch (c.level()) {
				case INFO -> ctx.fill(markerX + 4, my + 4, markerX + 8, my + 8, TEXT3);
				case WARN -> Ui.drawIconNow(ctx, "aviso", markerX, my, 12, OUT_WARN);
				case ERROR -> {
					Ui.drawIconNow(ctx, "aviso", markerX, my, 12, DANGER);
					ctx.fill(markerX - 4, my + 4, markerX - 1, my + 8, DANGER);
				}
				default -> {}
			}

			String label = KeyLabels.text(k.key);
			if (waiting) {
				keyButton.setMessage(Text.literal("> " + label + " <").withColor(0xFFFF55));
			} else {
				keyButton.setMessage(Text.literal(label).withColor(levelColor(c.level()) & 0xFFFFFF));
			}
			String tip = c.level() == KeyConflicts.Level.NONE ? "" : c.message();
			if (!tip.equals(lastTip)) {
				lastTip = tip;
				keyButton.setTooltip(tip.isEmpty() ? null : Tooltip.of(Text.literal(tip)));
			}
			keyButton.render(ctx, mouseX, mouseY, tickDelta);
			resetButton.render(ctx, mouseX, mouseY, tickDelta);
		}

		@Override
		public List<? extends Element> children() {
			return List.of(keyButton, resetButton);
		}

		@Override
		public List<? extends Selectable> selectableChildren() {
			return List.of(keyButton, resetButton);
		}
	}

	/** El libro y los ajustes son teclas de Minecraft: aquí solo se ven; se cambian en Controles. */
	class BindingEntry extends Entry {
		private final KeyBinding binding;
		private final String icon;

		BindingEntry(KeyBinding binding, String icon) {
			this.binding = binding;
			this.icon = icon;
		}

		@Override
		public void render(DrawContext ctx, int index, int y, int x, int entryWidth, int entryHeight, int mouseX, int mouseY, boolean hovered, float tickDelta) {
			TextRenderer tr = KeysListWidget.this.client.textRenderer;
			Ui.drawIconNow(ctx, icon, x + 2, y + (entryHeight - 12) / 2, 12, TEXT2);
			String key = KeyLabels.text(KeyBindingHelper.getBoundKeyOf(binding));
			int right = x + entryWidth - 4;
			int kw = tr.getWidth(key);
			int keyX = right - 64 - 4 - 45 - kw / 2;
			String name = ellipsize(tr, Text.translatable(binding.getTranslationKey()).getString(), keyX - 8 - (x + 18));
			ctx.drawTextWithShadow(tr, name, x + 18, y + (entryHeight - 8) / 2, TEXT2);
			ctx.drawTextWithShadow(tr, key, keyX, y + (entryHeight - 8) / 2, TEXT);
			ctx.drawTextWithShadow(tr, "en Controles", right - 64 + 32 - tr.getWidth("en Controles") / 2, y + (entryHeight - 8) / 2, TEXT3);
		}

		@Override
		public GuiNavigationPath getNavigationPath(GuiNavigation navigation) {
			return null;
		}

		@Override
		public List<? extends Element> children() {
			return List.of();
		}

		@Override
		public List<? extends Selectable> selectableChildren() {
			return List.of(new Selectable() {
				@Override
				public Selectable.SelectionType getType() {
					return Selectable.SelectionType.HOVERED;
				}

				@Override
				public void appendNarrations(NarrationMessageBuilder builder) {
					builder.put(NarrationPart.TITLE, Text.translatable(binding.getTranslationKey()).append(": " + KeyLabels.text(KeyBindingHelper.getBoundKeyOf(binding))));
				}
			});
		}
	}

	/** «Abrir controles de Minecraft…» */
	class ButtonEntry extends Entry {
		private final ButtonWidget button;

		ButtonEntry() {
			this.button = ButtonWidget.builder(Text.literal("Abrir controles de Minecraft…"),
					b -> KeysListWidget.this.client.setScreen(new KeybindsScreen(screen, KeysListWidget.this.client.options)))
				.dimensions(0, 0, 200, 20)
				.tooltip(Tooltip.of(Text.literal("Las teclas de Minecraft, el libro y los ajustes de la fruta se cambian ahí.")))
				.build();
		}

		@Override
		public void render(DrawContext ctx, int index, int y, int x, int entryWidth, int entryHeight, int mouseX, int mouseY, boolean hovered, float tickDelta) {
			button.setPosition(x + entryWidth / 2 - 100, y - 1);
			button.render(ctx, mouseX, mouseY, tickDelta);
		}

		@Override
		public List<? extends Element> children() {
			return List.of(button);
		}

		@Override
		public List<? extends Selectable> selectableChildren() {
			return List.of(button);
		}
	}
}
