package dev.opeope.client.hud;

import dev.opeope.client.ClientState;
import dev.opeope.client.hud.HudCanvas.LayoutState;
import dev.opeope.client.hud.HudCanvas.Rect;
import dev.opeope.client.input.InputHandler;
import dev.opeope.client.room.RoomHudState;
import dev.opeope.net.Payloads;

/**
 * El medidor del ROOM (spec §3.7): sale mientras crece, mientras mantienes la tecla del ROOM y un momento después de
 * cada muesca, tope, fijado o aterrizaje. Escala logarítmica de 4 a 160 m con marcas en 10, 25, 50, 100 y 160.
 */
public final class RoomMeter {
	private RoomMeter() {}

	private static final float[] TICKS = {10, 25, 50, 100, 160};
	private static final String[] LABELS = {"10", "25", "50", "100", "160"};
	private static final int[] RECENT = {Payloads.RoomFx.NOTCH, Payloads.RoomFx.LIMIT, Payloads.RoomFx.FIX, Payloads.RoomFx.LAND};

	private static boolean visible;
	private static int x, y, w, h;
	private static HudCanvas canvas;

	static boolean wanted(long now) {
		ClientState.RoomInfo own = ClientState.ownRoom();
		if (own != null && own.state() == 0) return true;
		if (RoomHudState.growing()) return true;
		if (InputHandler.roomDown || InputHandler.roomLatched()) return true;
		for (int k : RECENT) {
			long t = RoomHudState.lastEventMs(k);
			if (t > 0 && now >= t && now - t < 1500) return true;
		}
		return false;
	}

	static void layout(HudCanvas c, LayoutState s) {
		canvas = c;
		visible = false;
		if (!wanted(c.now)) return;
		w = Math.max(100, Math.min(150, Math.round(0.26f * c.W)));
		h = w >= 130 ? 30 : 22;
		int bottom;
		if (s.bar != null && !s.barVertical) {
			x = s.bar.right() - w;
			bottom = s.bar.y() - 4;
		} else {
			x = c.W - c.M - w;
			bottom = c.subtitlesOn && c.subtitles != null && !s.barVertical ? c.subtitles.y() - 4 : c.H - c.M;
		}
		y = bottom - h;
		visible = true;
		s.meter = new Rect(x, y, w, h);
		s.place("medidor", false, s.meter);
	}

	static void clear() {
		visible = false;
	}

	private static float radius() {
		float r = RoomHudState.drawnRadius();
		if (r <= 0f) {
			ClientState.RoomInfo own = ClientState.ownRoom();
			if (own != null) r = own.radius();
		}
		return r;
	}

	private static float position(float r) {
		if (r <= 4f) return 0f;
		return Anim.clamp01((float) (Math.log(r / 4.0) / Math.log(40.0)));
	}

	static void draw(Ui.Batch b) {
		if (!visible || canvas == null) return;
		long now = canvas.now;
		long lim = RoomHudState.lastEventMs(Payloads.RoomFx.LIMIT);
		long limAge = lim > 0 ? now - lim : Long.MAX_VALUE;
		int dx = 0;
		if (limAge >= 0 && limAge < 300 && !Anim.reduced()) {
			float t = limAge / 1000f;
			dx = (int) Math.round(2 * Math.sin(2 * Math.PI * 20 * t) * (1 - t / 0.3f));
		}
		int x0 = x + dx;
		Ui.panel(b, x0, y, w, h, 4, Theme.METER_TOP, Theme.METER_BOTTOM, Theme.HAIRLINE);

		// Fila 1: «ROOM» y el radio
		float r = radius();
		ClientState.RoomInfo own = ClientState.ownRoom();
		boolean growing = RoomHudState.growing() || (own != null && own.state() == 0);
		String rs = r > 0f ? Txt.radius(r) : "…";
		if (growing) rs += " ↑";
		else if (RoomHudState.resizeDir() > 0 && limAge > 1500) rs += " ↑";
		else if (RoomHudState.resizeDir() < 0 && limAge > 1500) rs += " ↓";
		b.text("ROOM", x0 + 5, y + 4, Theme.TEXT2, false);
		b.text(rs, x0 + w - 5 - b.tr.getWidth(rs), y + 4, Theme.HI, false);

		// Fila 2: la pista
		int t0 = x0 + 5, t1 = x0 + w - 5, ty = y + 14, tw = t1 - t0;
		b.fill(Ui.BACK, t0, ty, t1, ty + 4, Theme.METER_TRACK);
		float t = position(r);
		int fx = t0 + Math.round(t * tw);
		if (fx > t0) b.hgradient(Ui.BACK, t0, ty, fx, ty + 4, Theme.METER_FILL_A, Theme.lerp(Theme.METER_FILL_A, Theme.METER_FILL_B, t), 2);
		for (float tick : TICKS) {
			int px = t0 + Math.round(position(tick) * tw);
			b.fill(Ui.BACK, Math.min(px, t1 - 1), ty + 5, Math.min(px, t1 - 1) + 1, ty + 7, Theme.METER_TICK);
		}
		int mx = Math.min(fx, t1 - 1);
		b.fill(Ui.OVER, mx, ty - 1, mx + 1, ty + 5, Theme.METER_MARK);

		// Tope: el extremo parpadea en naranja
		if (limAge >= 0 && limAge < 400 && (limAge / 50) % 2 == 0) {
			int kind = RoomHudState.limitKind();
			if (kind == Payloads.RoomFx.LIMIT_MIN) b.fill(Ui.OVER, t0, ty - 1, t0 + 2, ty + 5, Theme.OUT_WARN);
			else if (kind != Payloads.RoomFx.LIMIT_CENTRED) b.fill(Ui.OVER, t1 - 2, ty - 1, t1, ty + 5, Theme.OUT_WARN);
		}

		// Fila 3 (si cabe): las cifras de las marcas, de derecha a izquierda sin pisarse
		if (h >= 30) {
			int limitRight = x0 + w - 2;
			for (int i = LABELS.length - 1; i >= 0; i--) {
				int lw = b.tr.getWidth(LABELS[i]);
				int px = t0 + Math.round(position(TICKS[i]) * tw);
				int lx = px - lw / 2;
				int right = Math.min(lx + lw, limitRight);
				// la última («160») se arrima al borde; las demás, si tendrían que moverse más de 4, no se ponen
				if (i < LABELS.length - 1 && lx + lw - right > 4) continue;
				lx = right - lw;
				b.text(LABELS[i], lx, y + 21, Theme.METER_LABEL, false);
				limitRight = lx - 2;
			}
		}
	}
}
