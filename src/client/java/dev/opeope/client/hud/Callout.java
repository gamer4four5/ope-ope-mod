package dev.opeope.client.hud;

import dev.opeope.OpeConfig;
import dev.opeope.client.config.OpeSettings;
import dev.opeope.client.hud.HudCanvas.LayoutState;
import dev.opeope.client.hud.HudCanvas.Rect;
import dev.opeope.client.input.Bisturi;
import dev.opeope.client.room.RoomHudState;
import dev.opeope.net.Payloads;

/**
 * El aviso grande del ROOM (spec §3.7): «ROOM» al crearlo, «ROOM · 24 m» al fijarlo, «Aterriza · 37 m», «Contigo»,
 * «ROOM cerrado» y los topes de tamaño. Encima de la mira, entra en 120 ms y se apaga en los últimos 300.
 */
public final class Callout {
	private Callout() {}

	private static long seenAt;
	private static long startAt;
	private static long duration;
	private static String text = "";
	private static int color;
	private static boolean big;
	private static boolean visible;
	private static int scale;
	private static HudCanvas canvas;

	/** Cada tick: mira si el ROOM ha contado algo nuevo. */
	static void tick(long now) {
		long at = RoomHudState.lastEventAt();
		if (at <= 0 || at == seenAt) return;
		seenAt = at;
		if (!OpeSettings.get().avisoRoom || Bisturi.active) return;
		int kind = RoomHudState.lastEventKind();
		String t = null;
		int col = Theme.ROOM;
		long dur = 900;
		boolean b = false;
		if (kind == Payloads.RoomFx.CREATE) {
			t = "ROOM";
			col = Theme.HI;
			dur = 1100;
			b = true;
		} else if (kind == Payloads.RoomFx.FIX) {
			t = "ROOM · " + Txt.radius(RoomHudState.targetRadius() > 0 ? RoomHudState.targetRadius() : RoomHudState.drawnRadius());
		} else if (kind == Payloads.RoomFx.LAND) {
			t = "Aterriza · " + Txt.meters(RoomHudState.landDistance());
		} else if (kind == Payloads.RoomFx.ARRIVE) {
			t = "Contigo";
		} else if (kind == Payloads.RoomFx.CLOSE) {
			t = "ROOM cerrado";
			col = Theme.TEXT2;
		} else if (kind == Payloads.RoomFx.LIMIT) {
			int lk = RoomHudState.limitKind();
			if (lk == Payloads.RoomFx.LIMIT_MAX || lk == Payloads.RoomFx.LIMIT_MAX_GROWING) {
				t = "Máximo · " + Txt.meters(OpeConfig.ROOM_MAX_RADIUS);
				col = Theme.OUT_WARN;
			} else if (lk == Payloads.RoomFx.LIMIT_MIN) {
				t = "Mínimo · " + Txt.meters(OpeConfig.ROOM_MIN_RADIUS);
				col = Theme.OUT_WARN;
			}
		}
		if (t == null || now - at >= dur) return;
		text = t;
		color = col;
		duration = dur;
		big = b;
		startAt = at;
	}

	static void clear() {
		seenAt = 0;
		startAt = 0;
		text = "";
		visible = false;
	}

	static void layout(HudCanvas c, LayoutState s) {
		canvas = c;
		visible = false;
		if (text.isEmpty() || startAt <= 0 || s.bisturi) return;
		long age = c.now - startAt;
		if (age < 0 || age >= duration) return;
		scale = big ? (c.W < 400 ? 2 : 3) : 2;
		int tw = c.tr.getWidth(text) * scale;
		visible = true;
		s.callout = new Rect(c.cx - tw / 2, c.cy - 52, tw, 8 * scale);
		s.place("aviso", false, s.callout);
	}

	static void draw(Ui.Batch b) {
		if (!visible || canvas == null) return;
		HudCanvas c = canvas;
		long age = c.now - startAt;
		float a = Anim.envelope(age, duration, 120, 300);
		if (a <= 0.02f) return;
		float k = scale;
		if (!Anim.reduced() && age < 120) k = scale * Anim.lerp(0.9f, 1f, Anim.easeOutCubic(age / 120f));
		float tw = c.tr.getWidth(text) * k;
		float x = c.cx - tw / 2f;
		float y = c.cy - 52 + (8 * scale - 8 * k) / 2f;
		// una franja oscura con los bordes difuminados detrás: sobre el cielo o un destello el texto claro se perdía
		int band = Math.round((Theme.floatShadow() ? 0x78 : 0xC8) * a) << 24;
		int pad = 3 * scale, fade = 8 * scale;
		int bx0 = (int) Math.floor(x) - pad, bx1 = (int) Math.ceil(x + tw) + pad;
		int by0 = c.cy - 52 - pad, by1 = c.cy - 52 + 7 * scale + pad;
		b.hgradient(Ui.BACK, bx0 - fade, by0, bx0, by1, 0, band, scale * 2);
		b.fill(Ui.BACK, bx0, by0, bx1, by1, band);
		b.hgradient(Ui.BACK, bx1, by0, bx1 + fade, by1, band, 0, scale * 2);
		b.textScaled(text, x, y, Theme.mulAlpha(color, a), Theme.floatShadow(), k);
	}
}
