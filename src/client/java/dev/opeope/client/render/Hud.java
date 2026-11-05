package dev.opeope.client.render;

import dev.opeope.client.ClientState;
import dev.opeope.client.input.Bisturi;
import dev.opeope.client.input.InputHandler;
import dev.opeope.client.input.Keys;
import dev.opeope.net.Payloads;
import dev.opeope.power.Forge;
import dev.opeope.power.Patterns;
import dev.opeope.power.Tech;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.render.RenderTickCounter;
import net.minecraft.entity.LivingEntity;
import net.minecraft.text.Text;
import net.minecraft.util.math.Box;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** El HUD: estado a la derecha, las tiras de Tab y de R bajo la mira, y lo que ves con Scan. */
public final class Hud {
	private Hud() {}

	private static final int CYAN = 0xFF8FD8FF, WHITE = 0xFFFFFFFF, GRAY = 0xFF9AA7B0, PINK = 0xFFFFB6C6;

	public static void render(DrawContext ctx, RenderTickCounter tc) {
		MinecraftClient mc = MinecraftClient.getInstance();
		if (mc.player == null || mc.options.hudHidden || !ClientState.hasFruit()) return;
		TextRenderer tr = mc.textRenderer;
		int w = ctx.getScaledWindowWidth(), h = ctx.getScaledWindowHeight();

		List<Text> lines = new ArrayList<>();
		List<Integer> colors = new ArrayList<>();
		ClientState.RoomInfo room = ClientState.ownRoom();
		add(lines, colors, room != null ? "ROOM " + Math.round(room.radius()) + " m" : "ROOM —", room != null ? CYAN : GRAY);
		if (ClientState.awakened()) add(lines, colors, "Despertar", 0xFFBFE9FF);
		if (ClientState.taktCount > 0) {
			String mode = switch (ClientState.taktMode) {
				case 1 -> "órbita";
				case 2 -> "aparcado";
				default -> "a la mano";
			};
			add(lines, colors, "Takt " + ClientState.taktCount + " (" + mode + ")", 0xFFB9F0FF);
		}
		if (ClientState.weapon >= 0 && ClientState.weapon < Forge.NAMES.length) add(lines, colors, "Arma: " + Forge.NAMES[ClientState.weapon], 0xFFFFD9A0);
		String cut = Patterns.NAMES[Math.max(0, Math.min(3, ClientState.pattern))] + " · "
			+ (ClientState.has(Payloads.State.REACH_TARGET) ? "solo el objetivo" : "todo el ROOM")
			+ (ClientState.has(Payloads.State.SUSPEND) ? " · suspender" : "");
		add(lines, colors, cut, GRAY);
		if (ClientState.union()) add(lines, colors, "Unión: clic pieza, clic destino · clic der. reconstruir", 0xFFC8F0D8);
		if (ClientState.scanTicks > 0) add(lines, colors, "Scan " + (ClientState.scanTicks / 20 + 1) + " s · " + Keys.SHAMBLES.label() + " roba", 0xFFE8FBFF);
		if (ClientState.has(Payloads.State.CURTAIN)) add(lines, colors, "Curtain", 0xFFB3E5FF);
		if (ClientState.has(Payloads.State.SILENT)) add(lines, colors, "Silent", 0xFFB3E5FF);
		Tech[] techs = Tech.values();
		for (int i = 0; i < ClientState.cooldowns.length && i < techs.length; i++) {
			int cd = ClientState.cooldowns[i];
			if (cd > 10) add(lines, colors, techs[i].label + " " + (cd / 20 + 1) + " s", PINK);
		}
		int y = h - 30 - lines.size() * 10;
		for (int i = 0; i < lines.size(); i++) {
			Text t = lines.get(i);
			ctx.drawTextWithShadow(tr, t, w - 6 - tr.getWidth(t), y + i * 10, colors.get(i));
		}

		int cy = h / 2 + 14;
		if (InputHandler.row2Down) {
			String l1 = k(Keys.T_STERBEN) + " Sterben · " + k(Keys.T_SOULS) + " Almas · " + k(Keys.T_UNION) + " Unión (mantener: Cirugía) · "
				+ k(Keys.T_RAIN) + " Lluvia · " + k(Keys.T_MES) + " Mes · " + k(Keys.T_COUNTER) + (ClientState.awakened() ? " Puncture Wille" : " Counter Shock");
			String l2 = k(Keys.T_PILLAR) + " Pilar · " + k(Keys.T_WALL) + " Muralla · " + k(Keys.T_CHASM) + " Sima · " + k(Keys.T_PLATEAU) + " Meseta · "
				+ k(Keys.T_PATTERN) + " " + Patterns.NAMES[Math.max(0, Math.min(3, ClientState.pattern))] + " · "
				+ k(Keys.T_REACH) + (ClientState.has(Payloads.State.REACH_TARGET) ? " Solo objetivo" : " Todo") + " · "
				+ k(Keys.T_SUSPEND) + (ClientState.has(Payloads.State.SUSPEND) ? " Suspender: sí" : " Suspender: no");
			strip(ctx, tr, w, cy, "Segunda fila", l1, l2);
		} else if (InputHandler.rowR()) {
			String l1 = k(Keys.R_ORBIT) + " Órbita/mano · " + k(Keys.R_PARK) + " Aparcar · " + k(Keys.R_SETDOWN) + " Posar · " + k(Keys.R_DROP) + " Soltar · "
				+ "Clic der. Lanzar todo · Rueda distancia";
			String l2 = k(Keys.R_ZONE) + " Zona · " + k(Keys.R_CUT) + " Lo cortado · " + k(Keys.R_GATHER) + " Rejuntar · " + k(Keys.R_UNITE) + " Unir todo · "
				+ "1 Lanza · 2 Hoja · 3 Martillo · 4 Estacas · 5 Muralla";
			strip(ctx, tr, w, cy, "Takt", l1, l2);
		} else if (Bisturi.active) {
			strip(ctx, tr, w, cy, "Bisturí", "Rueda gira · Mayús + rueda ladea · Ctrl + rueda mueve",
				"Clic encadena (" + Bisturi.COMMITTED.size() + "/16) · al soltar cae todo a la vez");
		} else if (InputHandler.roomDown) {
			strip(ctx, tr, w, cy, "ROOM", "Rueda: agrandar o encoger · Clic: lanzarlo a la mira · Clic der.: traerlo", "Un toque: quitarlo");
		}

		if (ClientState.scanTicks > 0 && mc.world != null) {
			Box box = mc.player.getBoundingBox().expand(48);
			List<LivingEntity> seen = mc.world.getEntitiesByClass(LivingEntity.class, box, e -> e != mc.player && e.isAlive());
			seen.sort(Comparator.comparingDouble(e -> e.squaredDistanceTo(mc.player)));
			int sy = 36;
			ctx.drawTextWithShadow(tr, Text.literal("Scan"), 6, sy, CYAN);
			for (int i = 0; i < Math.min(7, seen.size()); i++) {
				LivingEntity e = seen.get(i);
				String held = e.getMainHandStack().isEmpty() ? "nada en la mano" : e.getMainHandStack().getName().getString();
				String line = e.getDisplayName().getString() + " · " + Math.round(e.getHealth()) + " ♥ · " + held;
				ctx.drawTextWithShadow(tr, Text.literal(line), 6, sy + 11 + i * 10, WHITE);
			}
		}
	}

	private static String k(Keys key) {
		return "[" + key.label().toUpperCase() + "]";
	}

	private static void add(List<Text> lines, List<Integer> colors, String s, int color) {
		lines.add(Text.literal(s));
		colors.add(color);
	}

	private static void strip(DrawContext ctx, TextRenderer tr, int w, int y, String title, String l1, String l2) {
		int width = Math.max(tr.getWidth(l1), tr.getWidth(l2)) + 12;
		int x = (w - width) / 2;
		ctx.fill(x, y, x + width, y + 34, 0xAA0B1A24);
		ctx.fill(x, y, x + width, y + 1, CYAN);
		ctx.drawTextWithShadow(tr, Text.literal(title), x + 6, y + 3, CYAN);
		ctx.drawTextWithShadow(tr, Text.literal(l1), x + 6, y + 13, WHITE);
		ctx.drawTextWithShadow(tr, Text.literal(l2), x + 6, y + 23, WHITE);
	}
}
