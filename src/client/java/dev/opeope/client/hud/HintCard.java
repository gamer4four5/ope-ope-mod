package dev.opeope.client.hud;

import dev.opeope.client.config.OpeProgress;
import dev.opeope.client.config.OpeSettings;
import dev.opeope.client.hud.HudCanvas.LayoutState;
import dev.opeope.client.hud.HudCanvas.Rect;
import net.minecraft.client.MinecraftClient;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * La tarjeta de pistas de arriba a la derecha (spec §3.7). Con Pistas «Solo al empezar» se retira sola: la de inicio
 * desaparece a los 150 s de verla y las demás se quedan en el título a los 45 s (salvo 6 s al volver a su contexto).
 */
public final class HintCard {
	private HintCard() {}

	/** El hueco de la tarjeta (también lo usa la de primeros pasos): x, y, ancho, límite de abajo; narrow = solo título. */
	record Slot(int x, int y, int w, int limit, boolean narrow) {}

	private static final int NONE = 0, CARD = 1, PILL = 2;

	private static int mode = NONE;
	private static int x, y, w, h;
	private static Hints.Context context;
	private static final List<Ui.Line> ROWS = new ArrayList<>();
	private static String lastId;
	private static long enteredAt;
	private static String fullId;
	private static long fullAt;
	private static HudCanvas canvas;

	static Slot slot(HudCanvas c, LayoutState s) {
		int cw = Math.max(120, Math.min(176, Math.round(0.34f * c.W)));
		boolean narrow = false;
		int bossEdge = c.cx + c.v(91) + 4;
		if (c.boss != null && c.W - c.M - cw < bossEdge) {
			cw = c.W - c.M - bossEdge;
			if (cw < 100) narrow = true;
		}
		int sx = c.W - c.M - cw;
		int sy = Math.max(c.M, c.effectsBottom + 4);
		if (narrow) sy = Math.max(sy, c.bossBottom + 4);
		int limit = c.H - c.M;
		if (sx < c.chatRight) limit = Math.min(limit, c.chatTop - 4);
		if (c.subtitlesOn && c.subtitles != null) limit = Math.min(limit, c.subtitles.y() - 4);
		if (s.bar != null && s.bar.overlapsX(sx, sx + cw)) limit = Math.min(limit, s.bar.y() - 4);
		return new Slot(sx, sy, cw, limit, narrow);
	}

	/** Cada tick: cuenta el tiempo que se ve la tarjeta entera (para retirarla con Pistas «Solo al empezar»). */
	static void tick(long now, boolean paused) {
		if (paused || fullId == null || now - fullAt > 250) return;
		OpeProgress.addHintSeconds(fullId, 0.05f);
	}

	static void clear() {
		mode = NONE;
		context = null;
		ROWS.clear();
		lastId = null;
		enteredAt = 0;
		fullId = null;
		fullAt = 0;
	}

	static void layout(HudCanvas c, LayoutState s) {
		canvas = c;
		mode = NONE;
		MinecraftClient mc = MinecraftClient.getInstance();
		OpeSettings st = OpeSettings.get();
		String id = Hints.currentId(mc);
		if (!Objects.equals(id, lastId)) {
			lastId = id;
			enteredAt = c.now;
		}
		if (id == null || s.stripOpen || s.bisturi || c.f3) return;
		if (st.interfaz != OpeSettings.Interfaz.COMPLETA || st.pistas == OpeSettings.Pistas.NUNCA) return;
		boolean full = true;
		if (st.pistas == OpeSettings.Pistas.NUEVO) {
			float secs = OpeProgress.hintSeconds(id);
			if ("inicio".equals(id)) {
				if (secs >= 150f) return;
			} else if (secs >= 45f && c.now - enteredAt > 6000) {
				full = false;
			}
		}
		context = Hints.build(id, mc);
		if (context == null) return;
		Slot slot = slot(c, s);
		if (!full || slot.narrow()) {
			pill(c, s, slot);
			return;
		}

		int avail = slot.limit() - slot.y();
		List<Ui.Line> rows = context.rows();
		int n = rows.size();
		ROWS.clear();
		int fullH = 23 + 12 * n + (context.footer() ? 12 : 0) + 4;
		if (fullH <= avail) {
			ROWS.addAll(rows);
			if (context.footer()) ROWS.add(Hints.footer());
			h = fullH;
		} else if (27 + 12 * n <= avail) {
			ROWS.addAll(rows);
			h = 27 + 12 * n;
		} else {
			int k = Math.min(n, (avail - 27) / 12);
			if (k < 1) {
				pill(c, s, slot);
				return;
			}
			for (int i = 0; i < k - 1; i++) ROWS.add(rows.get(i));
			ROWS.add(Hints.more());
			h = 27 + 12 * k;
		}
		Rect r = new Rect(slot.x(), slot.y(), slot.w(), h);
		if (s.meter != null && s.meter.overlaps(r)) {
			pill(c, s, slot);
			return;
		}
		x = r.x();
		y = r.y();
		w = r.w();
		mode = CARD;
		fullId = id;
		fullAt = c.now;
		s.card = r;
		s.place("tarjeta", true, r);
	}

	/** Solo el título, en una píldora pegada a la derecha. */
	private static void pill(HudCanvas c, LayoutState s, Slot slot) {
		int max = c.W - 2 * c.M;
		if (c.boss != null && slot.y() < c.bossBottom) max = c.W - c.M - (c.cx + c.v(91) + 4);
		w = Math.min(max, 16 + c.tr.getWidth(context.title()) + 6);
		if (w < 40) return;
		h = 14;
		x = c.W - c.M - w;
		y = slot.y();
		Rect r = new Rect(x, y, w, h);
		if (y + h > slot.limit() && s.bar != null && s.bar.overlaps(r)) return;
		mode = PILL;
		s.card = r;
		s.place("tarjeta", true, r);
	}

	static void draw(Ui.Batch b) {
		if (mode == NONE || context == null || canvas == null) return;
		int accent = context.accent();
		if (mode == PILL) {
			Ui.panel(b, x, y, w, 14, 7, Theme.PANEL_TOP, Theme.PANEL_BOTTOM, Theme.HAIRLINE);
			Ui.icon(b, Ui.ICONS, context.icon(), x + 2, y + 1, accent);
			Ui.text(b, context.title(), x + 16, y + 3, w - 16 - 5, accent, false);
			return;
		}
		Ui.panel(b, x, y, w, h, 4, Theme.PANEL_TOP, Theme.PANEL_BOTTOM, Theme.HAIRLINE);
		Ui.icon(b, Ui.ICONS, context.icon(), x + 5, y + 6, accent);
		Ui.text(b, context.title(), x + 20, y + 8, w - 25, accent, false);
		b.fill(Ui.BACK, x + 5, y + 20, x + w - 5, y + 21, Theme.HAIRLINE);
		int ry = y + 23;
		for (Ui.Line line : ROWS) {
			line.draw(b, x + 5, ry, w - 10);
			ry += 12;
		}
	}
}
