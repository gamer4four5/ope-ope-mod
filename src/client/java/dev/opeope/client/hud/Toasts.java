package dev.opeope.client.hud;

import dev.opeope.client.hud.HudCanvas.LayoutState;
import dev.opeope.client.hud.HudCanvas.Rect;
import net.minecraft.client.MinecraftClient;
import net.minecraft.text.Text;

import java.util.ArrayList;
import java.util.List;

/**
 * Los avisos cortos de la fruta (spec §3.7): píldoras apiladas encima de la barra rápida, la más nueva abajo. Viven
 * (2,5 s + 0,04 s por letra) × el tiempo de notificaciones de Minecraft; el mismo texto en 1,5 s se junta en «×2».
 * Con una tira abierta se esconden y su reloj se para.
 */
public final class Toasts {
	private Toasts() {}

	public static final int INFO = 0, BAD = 1, GOLD = 2;

	private static final long ENTER = 120, EXIT = 300, MERGE = 1500;

	private static final class Toast {
		String text;
		int kind, count = 1;
		long age, life, lastPush;
	}

	private record Spot(Toast t, int x, int y, int w, String text) {}

	private static final List<Toast> LIST = new ArrayList<>();
	private static final List<Spot> SPOTS = new ArrayList<>();
	private static long lastTick;
	private static boolean paused;
	private static HudCanvas canvas;

	/** Un aviso nuevo. No lo narra: quien lo intercepta del servidor ya lo hace. */
	public static void push(Text msg, int kind) {
		if (msg == null) return;
		String s = Txt.plain(msg.getString()).trim();
		if (s.isEmpty()) return;
		long now = System.currentTimeMillis();
		for (Toast t : LIST) {
			if (t.text.equals(s) && now - t.lastPush < MERGE) {
				t.count++;
				t.lastPush = now;
				t.age = Math.min(t.age, ENTER);
				return;
			}
		}
		Toast t = new Toast();
		t.text = s;
		t.kind = kind == BAD || kind == GOLD ? kind : INFO;
		t.lastPush = now;
		t.life = Math.round((2500 + 40.0 * s.length()) * displayTime());
		LIST.add(t);
		while (LIST.size() > 8) LIST.remove(0);
	}

	private static double displayTime() {
		MinecraftClient mc = MinecraftClient.getInstance();
		if (mc == null || mc.options == null) return 1.0;
		Double v = mc.options.getNotificationDisplayTime().getValue();
		return v != null && v > 0 ? v : 1.0;
	}

	/** Cada tick: avanza 50 ms salvo con el juego en pausa o una tira abierta. */
	static void tick(long now, boolean pause) {
		paused = pause;
		lastTick = now;
		if (pause) return;
		for (int i = LIST.size() - 1; i >= 0; i--) {
			Toast t = LIST.get(i);
			t.age += 50;
			if (t.age >= t.life) LIST.remove(i);
		}
	}

	static void clear() {
		LIST.clear();
		SPOTS.clear();
	}

	private static long ageNow(Toast t, long now) {
		if (paused) return t.age;
		return t.age + Math.max(0, Math.min(50, now - lastTick));
	}

	private static String label(Toast t) {
		return t.count > 1 ? t.text + " ×" + t.count : t.text;
	}

	static void layout(HudCanvas c, LayoutState s) {
		canvas = c;
		SPOTS.clear();
		if (s.stripOpen || LIST.isEmpty()) return;
		int max = c.H < 300 ? 2 : 3;
		int bottom = c.H - c.v(76);
		int top = c.cy + 9;
		if (s.taktChip != null) top = Math.max(top, s.taktChip.bottom() + 3);
		int fit = Math.max(1, (bottom - top + 3) / 17);
		int n = Math.min(Math.min(max, fit), LIST.size());
		int stackTop = bottom - n * 14 - (n - 1) * 3;

		int maxW = Math.min(c.W - 2 * c.M, 260);
		int rightMost = Integer.MAX_VALUE;
		if (s.bar != null && s.bar.overlapsY(stackTop, bottom)) rightMost = Math.min(rightMost, s.bar.x());
		if (s.meter != null && s.meter.overlapsY(stackTop, bottom)) rightMost = Math.min(rightMost, s.meter.x());
		if (rightMost != Integer.MAX_VALUE) maxW = Math.min(maxW, 2 * (rightMost - c.cx - 4));
		if (maxW < 40) return;

		int y = bottom - 14;
		int left = c.W, right = 0;
		for (int i = LIST.size() - 1; i >= LIST.size() - n; i--) {
			Toast t = LIST.get(i);
			String text = Txt.ellipsize(c.tr, label(t), maxW - 16 - 6);
			int w = 16 + c.tr.getWidth(text) + 6;
			int x = c.cx - w / 2;
			SPOTS.add(new Spot(t, x, y, w, text));
			left = Math.min(left, x);
			right = Math.max(right, x + w);
			y -= 17;
		}
		s.toasts = Rect.ltrb(left, stackTop, right, bottom);
		s.place("avisos", false, s.toasts);
	}

	static void draw(Ui.Batch b) {
		if (canvas == null || SPOTS.isEmpty()) return;
		long now = canvas.now;
		float old = b.alpha;
		try {
			for (Spot sp : SPOTS) {
				Toast t = sp.t();
				long age = ageNow(t, now);
				float a = Anim.envelope(age, t.life, ENTER, EXIT);
				if (a <= 0.02f) continue;
				int dy = !Anim.reduced() && age < ENTER ? Math.round(4 * (1f - age / (float) ENTER)) : 0;
				int x = sp.x(), y = sp.y() + dy, w = sp.w();
				int ring, icon, tint;
				if (t.kind == BAD) {
					ring = Theme.withAlpha(Theme.BAD, 0x80);
					icon = 1;
					tint = Theme.BAD;
				} else if (t.kind == GOLD) {
					ring = Theme.withAlpha(Theme.GOLD, 0x80);
					icon = 2;
					tint = Theme.GOLD;
				} else {
					ring = Theme.ROOM_BORDER;
					icon = 0;
					tint = Theme.SOFT;
				}
				b.alpha = old * a;
				Ui.panel(b, x, y, w, 14, 7, Theme.INK2, Theme.INK2, ring);
				Ui.icon(b, Ui.ICONS, icon == 1 ? "aviso" : icon == 2 ? "fruta" : "check", x + 2, y + 1, tint);
				b.text(sp.text(), x + 16, y + 3, Theme.TEXT, false);
			}
		} finally {
			b.alpha = old;
		}
	}
}
