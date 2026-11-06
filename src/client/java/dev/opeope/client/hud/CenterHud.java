package dev.opeope.client.hud;

import dev.opeope.client.ClientState;
import dev.opeope.client.config.OpeSettings;
import dev.opeope.client.hud.HudCanvas.LayoutState;
import dev.opeope.client.hud.HudCanvas.Rect;
import dev.opeope.client.input.Bisturi;
import dev.opeope.client.input.KeyLabels;
import dev.opeope.power.Forge;
import dev.opeope.power.Tech;
import net.minecraft.client.MinecraftClient;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RaycastContext;

/**
 * El centro (spec §3.7): la mira del ROOM (esquinas fuera, rombo dentro), la barrita de Amputate, el chip de Takt
 * bajo la mira y el panel del Bisturí arriba.
 */
public final class CenterHud {
	private CenterHud() {}

	private static final String[] TAKT_MODES = {"a la mano", "órbita", "aparcado"};

	private static boolean aimValid, aimInside;
	private static long aimChangedAt;

	private static boolean reticle, amputate, takt, bisturi;
	private static int taktX, taktY, taktW;
	private static Ui.Line taktLine;
	private static int bx, by, bw, bh;
	private static boolean splitRows;
	private static HudCanvas canvas;

	/** Cada tick: ¿lo que miras (hasta 200 m) cae dentro de tu ROOM? */
	static void tick(MinecraftClient mc, long now) {
		ClientState.RoomInfo own = ClientState.ownRoom();
		if (own == null || mc.player == null || mc.world == null || mc.gameRenderer.getCamera().isThirdPerson()) {
			aimValid = false;
			return;
		}
		Vec3d eye = mc.player.getEyePos();
		Vec3d look = mc.player.getRotationVec(1f);
		Vec3d centre = own.center();
		double r = own.radius();
		BlockHitResult hit = mc.world.raycast(new RaycastContext(eye, eye.add(look.multiply(200)),
			RaycastContext.ShapeType.OUTLINE, RaycastContext.FluidHandling.NONE, mc.player));
		Vec3d aim;
		if (hit != null && hit.getType() != HitResult.Type.MISS) aim = hit.getPos();
		else aim = eye.add(look.multiply(Math.min(200.0, eye.distanceTo(centre) + r)));
		boolean inside = aim.squaredDistanceTo(centre) <= r * r;
		if (!aimValid || inside != aimInside) {
			if (aimValid) aimChangedAt = now;
			aimInside = inside;
		}
		aimValid = true;
	}

	static void clear() {
		aimValid = false;
		aimInside = false;
		aimChangedAt = 0;
		reticle = amputate = takt = bisturi = false;
	}

	private static int cooldown(Tech t) {
		int i = t.ordinal();
		return i < ClientState.cooldowns.length ? ClientState.cooldowns[i] : 0;
	}

	/** La mira y la barrita de Amputate (no ocupan sitio). */
	static void layoutCentre(HudCanvas c, LayoutState s) {
		canvas = c;
		MinecraftClient mc = MinecraftClient.getInstance();
		boolean own = ClientState.ownRoom() != null;
		reticle = own && aimValid && !mc.gameRenderer.getCamera().isThirdPerson();
		amputate = own && cooldown(Tech.AMPUTATE) > 0;
	}

	/** El chip de Takt: bajo la mira mientras controlas algo (no con una tira abierta ni con el Bisturí). */
	static void layoutTakt(HudCanvas c, LayoutState s) {
		canvas = c;
		takt = false;
		int n = ClientState.taktCount;
		if (n <= 0 || s.stripOpen || s.bisturi) return;
		int mode = ClientState.taktMode;
		String m = mode >= 0 && mode < TAKT_MODES.length ? TAKT_MODES[mode] : TAKT_MODES[0];
		taktLine = new Ui.Line().text("×" + n, Theme.HI).text(" · " + m, Theme.TEXT);
		int wp = ClientState.weapon;
		if (wp >= 0 && wp < Forge.NAMES.length) taktLine.text(" · " + Forge.NAMES[wp], Theme.FORGE);
		int max = Math.min(c.W - 2 * c.M, 200);
		taktW = Math.min(max, 2 + 12 + 3 + taktLine.width(c.tr) + 6);
		taktX = c.cx - taktW / 2;
		taktY = c.cy + 14;
		takt = true;
		s.taktChip = new Rect(taktX, taktY, taktW, 12);
		s.place("takt", true, s.taktChip);
	}

	/** El panel del Bisturí, arriba y centrado, por debajo de las barras de jefe y de la columna izquierda. */
	static void layoutBisturi(HudCanvas c, LayoutState s) {
		canvas = c;
		bisturi = false;
		if (!s.bisturi) return;
		placeBisturi(c, s, Math.min(c.W - 2 * c.M, 260));
		// En lienzos estrechos la barra vertical puede quedar a su lado: el panel se estrecha para no pisarla
		if (s.bar != null && s.bar.overlaps(new Rect(bx, by, bw, bh))) {
			int room = 2 * (s.bar.x() - 4 - c.cx);
			if (room >= 160 && room < bw) placeBisturi(c, s, room);
		}
		bisturi = true;
		s.bisturiPanel = new Rect(bx, by, bw, bh);
		s.place("bisturí", true, s.bisturiPanel);
	}

	private static void placeBisturi(HudCanvas c, LayoutState s, int width) {
		bw = width;
		bx = c.cx - bw / 2;
		splitRows = c.W - 2 * c.M < 220 || turnLine(true).width(c.tr) > bw - 10;
		bh = 4 + 12 + 2 + (splitRows ? 3 : 2) * 12 + 10 + 4;
		int top = c.M + c.bossBottom;
		if (s.leftRight > bx && s.leftBottom > 0) top = Math.max(top, s.leftBottom + 4);
		if (c.effectsLeft(0, c.effectsBottom) < bx + bw) top = Math.max(top, c.effectsBottom + 4);
		by = top;
	}

	/** «[rueda] girar · [Mayús]+[rueda] ladear» y, en una sola fila, también «· [Ctrl]+[rueda] mover». */
	private static Ui.Line turnLine(boolean withMove) {
		KeyLabels.Glyph wheel = KeyLabels.wheel();
		Ui.Line l = new Ui.Line().key(wheel).action("girar").text(" · ", Theme.TEXT3)
			.key(Ui.SHIFT).plus().key(wheel).action("ladear");
		if (withMove) l.text(" · ", Theme.TEXT3).key(Ui.CTRL).plus().key(wheel).action("mover");
		return l;
	}

	static void draw(Ui.Batch b) {
		if (canvas == null) return;
		HudCanvas c = canvas;
		long now = c.now;
		int cx = c.cx, cy = c.cy;
		if (reticle) {
			float t = aimChangedAt > 0 ? Anim.clamp01((now - aimChangedAt) / 120f) : 1f;
			float in = aimInside ? t : 1f - t;
			float out = 1f - in;
			if (out > 0.01f) {
				int col = Theme.mulAlpha(Theme.RETICLE_OUT, out);
				int l = cx - 9, r = cx + 9, tp = cy - 9, bt = cy + 9;
				b.fill(Ui.OVER, l, tp, l + 3, tp + 1, col);
				b.fill(Ui.OVER, l, tp + 1, l + 1, tp + 3, col);
				b.fill(Ui.OVER, r - 3, tp, r, tp + 1, col);
				b.fill(Ui.OVER, r - 1, tp + 1, r, tp + 3, col);
				b.fill(Ui.OVER, l, bt - 1, l + 3, bt, col);
				b.fill(Ui.OVER, l, bt - 3, l + 1, bt - 1, col);
				b.fill(Ui.OVER, r - 3, bt - 1, r, bt, col);
				b.fill(Ui.OVER, r - 1, bt - 3, r, bt - 1, col);
			}
			if (in > 0.01f) {
				int col = Theme.mulAlpha(Theme.RETICLE_IN, in);
				for (int d = -5; d <= 5; d++) {
					int e = 5 - Math.abs(d);
					b.fill(Ui.OVER, cx + d, cy - e, cx + d + 1, cy - e + 1, col);
					if (e != 0) b.fill(Ui.OVER, cx + d, cy + e, cx + d + 1, cy + e + 1, col);
				}
			}
		}
		if (amputate) {
			int cd = cooldown(Tech.AMPUTATE);
			float f = 1f - Math.min(1f, cd / (float) Tech.AMPUTATE.ticks);
			b.fill(Ui.OVER, cx - 6, cy + 11, cx + 7, cy + 13, Theme.AMPUTATE_TRACK);
			int len = Math.round(13 * f);
			if (len > 0) b.fill(Ui.OVER, cx - 6, cy + 11, cx - 6 + len, cy + 13, Theme.HI);
		}
		if (takt && taktLine != null) {
			Ui.panel(b, taktX, taktY, taktW, 12, 6, Theme.PANEL_TOP, Theme.PANEL_BOTTOM, Theme.HAIRLINE);
			Ui.icon(b, Ui.ICONS, "takt", taktX + 2, taktY, Theme.SOFT);
			taktLine.draw(b, taktX + 17, taktY, taktW - 17 - 4);
		}
		if (bisturi) drawBisturi(b);
	}

	private static void drawBisturi(Ui.Batch b) {
		int x = bx, y = by, w = bw;
		Ui.panel(b, x, y, w, bh, 5, Theme.PANEL_TOP, Theme.PANEL_BOTTOM, Theme.HAIRLINE);
		Ui.icon(b, Ui.ICONS, "bisturi", x + 4, y + 4, Theme.HI);
		b.text("BISTURÍ", x + 19, y + 6, Theme.HI, false);
		int cuts = Bisturi.COMMITTED.size();
		String count = cuts + "/16";
		b.text(count, x + w - 5 - b.tr.getWidth(count), y + 6, cuts >= 16 ? Theme.BAD : Theme.TEXT2, false);

		KeyLabels.Glyph wheel = KeyLabels.wheel(), click = KeyLabels.mouseLeft(), right = KeyLabels.mouseRight();
		int ry = y + 4 + 12 + 2;
		int inner = w - 10;
		if (splitRows) {
			turnLine(false).draw(b, x + 5, ry, inner);
			ry += 12;
			new Ui.Line().key(Ui.CTRL).plus().key(wheel).action("mover").draw(b, x + 5, ry, inner);
		} else {
			turnLine(true).draw(b, x + 5, ry, inner);
		}
		ry += 12;
		boolean toggle = OpeSettings.get().modoBisturi == OpeSettings.Modo2.ALTERNAR;
		Ui.Line cut;
		if (cuts >= 16) {
			cut = new Ui.Line().text("Máximo de 16 cortes", Theme.BAD).text(" · ", Theme.TEXT3);
			if (toggle) cut.key(right).action("otra vez: cae todo");
			else cut.text("suelta", Theme.TEXT).key(right);
		} else {
			cut = new Ui.Line().key(click).action("cortar aquí").text(" · ", Theme.TEXT3);
			if (toggle) cut.key(right).action("otra vez: cae todo");
			else cut.text("suelta", Theme.TEXT2).key(right).action("cae todo");
		}
		cut.draw(b, x + 5, ry, inner);
		ry += 12;
		String info = "giro " + Math.round(Bisturi.roll) + "° · ladeo " + Math.round(Bisturi.tilt) + "° · " + Txt.dec1(Bisturi.offset) + " m";
		Ui.text(b, info, x + 5, ry + 1, inner, Theme.TEXT3, false);
	}
}
