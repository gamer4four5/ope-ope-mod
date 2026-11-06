package dev.opeope.client.hud;

import dev.opeope.client.ClientState;
import dev.opeope.client.config.OpeSettings;
import dev.opeope.client.hud.HudCanvas.LayoutState;
import dev.opeope.client.hud.HudCanvas.Rect;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.LivingEntity;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Lo que ve el Scan (spec §3.7): los seres vivos cercanos, su vida y lo que llevan en la mano. */
public final class ScanList {
	private ScanList() {}

	private static final List<String> ROWS = new ArrayList<>();
	private static int age;
	private static boolean visible;
	private static int x, y, w, n;
	private static HudCanvas canvas;

	/** Cada tick (cada 5 mientras dura el Scan): rehace la lista, de más cerca a más lejos. */
	static void tick(MinecraftClient mc) {
		if (ClientState.scanTicks <= 0 || mc.player == null || mc.world == null) {
			ROWS.clear();
			age = 0;
			return;
		}
		if (age++ % 5 != 0 && !ROWS.isEmpty()) return;
		ROWS.clear();
		List<LivingEntity> list = mc.world.getEntitiesByClass(LivingEntity.class, mc.player.getBoundingBox().expand(48),
			e -> e != mc.player && e.isAlive());
		list.sort(Comparator.comparingDouble(e -> e.squaredDistanceTo(mc.player)));
		for (LivingEntity e : list) {
			if (ROWS.size() >= 7) break;
			String held = e.getMainHandStack().isEmpty() ? "nada en la mano" : e.getMainHandStack().getName().getString();
			ROWS.add(e.getDisplayName().getString() + " · " + Math.round(e.getHealth()) + " ♥ · " + held);
		}
	}

	static void clear() {
		ROWS.clear();
		age = 0;
		visible = false;
	}

	private static int limitFor(HudCanvas c, int x1, int x2) {
		int limit = c.H - c.M;
		if (x1 < c.chatRight) limit = Math.min(limit, c.chatTop - 4);
		if (c.health != null && c.health.overlapsX(x1, x2)) limit = Math.min(limit, c.health.y() - 4);
		if (c.actionBar.overlapsX(x1, x2)) limit = Math.min(limit, c.actionBar.y() - 4);
		if (c.hotbar.overlapsX(x1, x2)) limit = Math.min(limit, c.hotbar.y() - 4);
		return limit;
	}

	static void layout(HudCanvas c, LayoutState s) {
		canvas = c;
		visible = false;
		OpeSettings.Interfaz mode = OpeSettings.get().interfaz;
		if (ClientState.scanTicks <= 0 || s.stripOpen || mode == OpeSettings.Interfaz.MINIMA) return;
		x = c.M;
		y = s.leftBottom + 4;
		w = Math.min(Math.round(0.42f * c.W), 200);
		if (c.boss != null && y < c.bossBottom) w = Math.min(w, c.cx - c.v(91) - 4 - c.M);
		int maxRows = mode == OpeSettings.Interfaz.SENCILLA ? 4 : 7;
		int limit = limitFor(c, x, x + w);
		int want = Math.max(1, Math.min(maxRows, ROWS.size()));
		int fit = (limit - y - 21) / 10;
		if (fit < 0) return;
		n = Math.min(want, fit);
		w = Math.min(w, c.effectsLeft(y, y + 21 + 10 * n) - 4 - c.M);
		if (w < 60) return;
		visible = true;
		s.scan = new Rect(x, y, w, 21 + 10 * n);
		s.leftBottom = Math.max(s.leftBottom, s.scan.bottom());
		s.leftRight = Math.max(s.leftRight, s.scan.right());
		s.place("scan", true, s.scan);
	}

	static void draw(Ui.Batch b) {
		if (!visible || canvas == null) return;
		int h = 21 + 10 * n;
		Ui.panel(b, x, y, w, h, 4, Theme.PANEL_TOP, Theme.PANEL_BOTTOM, Theme.HAIRLINE);
		Ui.icon(b, Ui.ICONS, "scan", x + 4, y + 4, Theme.HI);
		b.text("Scan", x + 19, y + 6, Theme.TEXT, false);
		String sec = Txt.secs(ClientState.scanTicks);
		b.text(sec, x + w - 4 - b.tr.getWidth(sec), y + 6, Theme.TEXT2, false);
		int ry = y + 18;
		if (ROWS.isEmpty()) {
			Ui.text(b, "Nada vivo cerca", x + 4, ry + 1, w - 8, Theme.TEXT3, false);
			return;
		}
		for (int i = 0; i < n && i < ROWS.size(); i++) {
			Ui.text(b, ROWS.get(i), x + 4, ry + 1 + i * 10, w - 8, Theme.TEXT, false);
		}
	}
}
