package dev.opeope.client.hud;

import dev.opeope.client.config.OpeSettings;
import dev.opeope.client.hud.HudCanvas.LayoutState;
import dev.opeope.client.hud.HudCanvas.Rect;
import dev.opeope.client.input.KeyLabels;
import dev.opeope.client.input.Keys;
import dev.opeope.client.input.Onboarding;

import java.util.ArrayList;
import java.util.List;

/**
 * Los primeros pasos (spec §3.7): seis pasos con su barra de progreso en el hueco de la tarjeta de pistas. Cada paso
 * hecho destella y se tacha; al acabar, «¡LISTO!» durante 6 s.
 */
public final class OnboardingCard {
	private OnboardingCard() {}

	private static final int NONE = 0, CARD = 1, PILL = 2, DONE = 3;

	private static int mode = NONE;
	private static int x, y, w, h;
	private static boolean showIntro;
	private static final List<String> INTRO = new ArrayList<>();
	private static final List<Integer> STEPS = new ArrayList<>();
	private static HudCanvas canvas;

	static void clear() {
		mode = NONE;
		INTRO.clear();
		STEPS.clear();
	}

	/** Coloca la tarjeta; devuelve true si los primeros pasos se quedan el hueco (aunque ahora estén ocultos). */
	static boolean layout(HudCanvas c, LayoutState s) {
		canvas = c;
		mode = NONE;
		OpeSettings st = OpeSettings.get();
		if (st.pistas == OpeSettings.Pistas.NUNCA) return false;
		boolean finished = Onboarding.finishedRecently();
		if (!Onboarding.showing() && !finished) return false;
		if (s.stripOpen || s.bisturi || c.f3) return true;

		HintCard.Slot slot = HintCard.slot(c, s);
		x = slot.x();
		y = slot.y();
		w = slot.w();
		int avail = slot.limit() - slot.y();
		if (slot.narrow()) return pill(c, s, slot, finished);

		if (finished) {
			h = 53;
			if (h > avail) return pill(c, s, slot, true);
			return commit(s, DONE);
		}

		int inner = w - 10;
		List<String> intro = new ArrayList<>();
		if (Onboarding.intro()) {
			intro.addAll(Txt.wrap(c.tr, "Seis pasos para dominar la fruta.", inner));
			intro.addAll(Txt.wrap(c.tr, "Se ocultan solos; puedes saltarlos en Ajustes.", inner));
		}
		int cur = Onboarding.current();
		List<Integer> all = new ArrayList<>();
		for (int i = 0; i < Onboarding.STEPS; i++) all.add(i);
		List<Integer> two = new ArrayList<>();
		if (cur < Onboarding.STEPS) two.add(cur);
		if (cur + 1 < Onboarding.STEPS) two.add(cur + 1);
		List<Integer> one = new ArrayList<>();
		if (cur < Onboarding.STEPS) one.add(cur);

		Object[][] tries = {{true, all}, {true, two}, {false, all}, {false, two}, {false, one}};
		for (Object[] t : tries) {
			boolean withIntro = (Boolean) t[0] && !intro.isEmpty();
			@SuppressWarnings("unchecked")
			List<Integer> steps = (List<Integer>) t[1];
			int hh = 28 + (withIntro ? intro.size() * 10 + 2 : 0) + steps.size() * 12 + 4;
			if (hh <= avail) {
				h = hh;
				showIntro = withIntro;
				INTRO.clear();
				if (withIntro) INTRO.addAll(intro);
				STEPS.clear();
				STEPS.addAll(steps);
				return commit(s, CARD);
			}
		}
		return pill(c, s, slot, false);
	}

	private static boolean commit(LayoutState s, int m) {
		Rect r = new Rect(x, y, w, h);
		if (s.meter != null && s.meter.overlaps(r)) {
			h = 14;
			r = new Rect(x, y, w, h);
			m = PILL;
		}
		mode = m;
		s.card = r;
		s.place("primeros pasos", true, r);
		return true;
	}

	private static boolean pill(HudCanvas c, LayoutState s, HintCard.Slot slot, boolean finished) {
		String title = finished ? "¡LISTO!" : "PRIMEROS PASOS " + Onboarding.doneCount() + "/" + Onboarding.STEPS;
		int max = c.W - 2 * c.M;
		if (c.boss != null && slot.y() < c.bossBottom) max = c.W - c.M - (c.cx + c.v(91) + 4);
		w = Math.min(max, 16 + c.tr.getWidth(title) + 6);
		if (w < 40) return true;
		h = 14;
		x = c.W - c.M - w;
		y = slot.y();
		mode = PILL;
		Rect r = new Rect(x, y, w, h);
		s.card = r;
		s.place("primeros pasos", true, r);
		return true;
	}

	/** La línea de un paso: hecho (marca y gris), el de ahora («▶», teclas y blanco) o pendiente. */
	private static Ui.Line step(int i, int state) {
		OpeSettings st = OpeSettings.get();
		boolean roomToggle = st.modoRoom == OpeSettings.ModoRoom.ALTERNAR;
		boolean tabToggle = st.modoTab == OpeSettings.ModoFila.ALTERNAR;
		KeyLabels.Glyph a, b = null;
		String text;
		if (i == Onboarding.STEP_ROOM) {
			a = KeyLabels.of(Keys.ROOM);
			text = roomToggle ? "toca y vuelve a tocar: abre el ROOM" : "mantén y suelta: abre el ROOM";
		} else if (i == Onboarding.STEP_RESIZE) {
			a = KeyLabels.of(Keys.ROOM);
			b = KeyLabels.wheel();
			text = "cambia su tamaño";
		} else if (i == Onboarding.STEP_CUT) {
			a = KeyLabels.mouseLeft();
			text = "arrastra dentro: Amputate";
		} else if (i == Onboarding.STEP_SHAMBLES) {
			a = KeyLabels.of(Keys.SHAMBLES);
			text = "Shambles, cambia de sitio";
		} else if (i == Onboarding.STEP_TAKT) {
			a = KeyLabels.of(Keys.TAKT);
			text = "sobre algo: Takt lo coge";
		} else {
			a = KeyLabels.of(Keys.ROW2);
			text = tabToggle ? "toca: la segunda fila" : "mantén: la segunda fila";
		}
		Ui.Line line = new Ui.Line();
		if (state == 0) return line.icon("check", Theme.SOFT).text(text, Theme.TEXT3);
		if (state == 1) line.text("▶", Theme.HI);
		line.key(a);
		if (b != null) line.plus().key(b);
		return line.text(text, state == 1 ? Theme.TEXT : Theme.TEXT2);
	}

	static void draw(Ui.Batch bt) {
		if (mode == NONE || canvas == null) return;
		long now = canvas.now;
		if (mode == PILL) {
			boolean finished = Onboarding.finishedRecently();
			String title = finished ? "¡LISTO!" : "PRIMEROS PASOS " + Onboarding.doneCount() + "/" + Onboarding.STEPS;
			Ui.panel(bt, x, y, w, 14, 7, Theme.PANEL_TOP, Theme.PANEL_BOTTOM, Theme.HAIRLINE);
			Ui.icon(bt, Ui.ICONS, finished ? "check" : "libro", x + 2, y + 1, Theme.SOFT);
			Ui.text(bt, title, x + 16, y + 3, w - 16 - 5, Theme.SOFT, false);
			return;
		}
		Ui.panel(bt, x, y, w, h, 4, Theme.PANEL_TOP, Theme.PANEL_BOTTOM, Theme.HAIRLINE);
		if (mode == DONE) {
			Ui.textScaled(bt, "¡LISTO!", x + 5, y + 5, Theme.HI, false, 2);
			new Ui.Line().text("Ya sabes lo básico.", Theme.TEXT).draw(bt, x + 5, y + 25, w - 10);
			new Ui.Line().key(Hints.book()).text("tiene todo lo demás.", Theme.TEXT).draw(bt, x + 5, y + 37, w - 10);
			return;
		}

		// Cabecera y progreso
		bt.text("PRIMEROS PASOS", x + 5, y + 8, Theme.SOFT, false);
		String count = Onboarding.doneCount() + "/" + Onboarding.STEPS;
		bt.text(count, x + w - 5 - bt.tr.getWidth(count), y + 8, Theme.TEXT2, false);
		int segW = (w - 20) / 6;
		int cur = Onboarding.current();
		float pulse = Anim.reduced() ? 1f : 0.6f + 0.4f * Anim.pulse(now, 1f);
		for (int i = 0; i < Onboarding.STEPS; i++) {
			int sx = x + 5 + i * (segW + 2);
			int color = Onboarding.done(i) ? Theme.SOFT : (i == cur ? Theme.mulAlpha(Theme.HI, pulse) : Theme.PENDING);
			bt.fill(Ui.BACK, sx, y + 21, sx + segW, y + 24, color);
		}

		int ry = y + 28;
		if (showIntro) {
			for (String line : INTRO) {
				bt.text(line, x + 5, ry, Theme.TEXT2, false);
				ry += 10;
			}
			ry += 2;
		}
		for (int i : STEPS) {
			long at = Onboarding.doneAt(i);
			if (at > 0 && now >= at && now - at < 600) {
				float t = (now - at) / 600f;
				bt.fill(Ui.BACK, x + 3, ry - 1, x + w - 3, ry + 12, Theme.withAlpha(Theme.HI, Math.round(0x40 * (1 - t))));
			}
			int state = Onboarding.done(i) ? 0 : (i == cur ? 1 : 2);
			step(i, state).draw(bt, x + 5, ry, w - 10);
			ry += 12;
		}
	}
}
