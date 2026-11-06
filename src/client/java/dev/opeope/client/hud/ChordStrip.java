package dev.opeope.client.hud;

import dev.opeope.client.ClientState;
import dev.opeope.client.hud.HudCanvas.LayoutState;
import dev.opeope.client.hud.HudCanvas.Rect;
import dev.opeope.client.input.InputHandler;
import dev.opeope.client.input.KeyLabels;
import dev.opeope.client.input.Keys;
import dev.opeope.net.Payloads;
import dev.opeope.power.Patterns;
import dev.opeope.power.Tech;
import net.minecraft.client.MinecraftClient;

import java.util.ArrayList;
import java.util.List;

/**
 * Las tiras de acordes (spec §3.7): con Tab, la segunda fila; con R mantenida, las órdenes de Takt. Cada entrada es
 * su tecla, su icono y su nombre; lo que no se puede usar ahora sale apagado y con el porqué debajo.
 */
public final class ChordStrip {
	private ChordStrip() {}

	private record Entry(KeyLabels.Glyph key, String icon, int accent, String name, String cool, boolean disabled) {}

	private record Placed(Entry e, int x, int y) {}

	private static final List<Placed> ENTRIES = new ArrayList<>();
	private static final List<Ui.Line> REASONS = new ArrayList<>();
	private static Ui.Line header;
	private static boolean visible;
	private static int x, y, w, h, reasonsY;
	private static HudCanvas canvas;

	/** ¿Hay que enseñar una tira? (Tab o R mantenida, y no la lista de jugadores.) */
	static boolean wanted() {
		return (InputHandler.row2Down || InputHandler.rowR()) && !InputHandler.playerListShown();
	}

	static void clear() {
		visible = false;
		ENTRIES.clear();
		REASONS.clear();
		header = null;
	}

	private static String cool(Tech t) {
		int i = t.ordinal();
		int cd = i < ClientState.cooldowns.length ? ClientState.cooldowns[i] : 0;
		return cd > 0 ? Txt.secs(cd) : null;
	}

	private static Entry e(Keys k, String icon, int accent, String name, Tech t, boolean disabled) {
		return new Entry(KeyLabels.of(k), icon, accent, name, t != null ? cool(t) : null, disabled);
	}

	private static List<List<Entry>> tabRows(boolean shortNames, boolean room) {
		boolean awake = ClientState.awakened();
		boolean heart = Hints.heartInHand(MinecraftClient.getInstance());
		List<Entry> r1 = new ArrayList<>(), r2 = new ArrayList<>(), r3 = new ArrayList<>();
		int a = Theme.TAB;
		r1.add(e(Keys.T_STERBEN, "sterben", a, "Sterben", Tech.STERBEN, false));
		r1.add(e(Keys.T_SOULS, "almas", a, shortNames ? "Almas" : "Cambio de almas", Tech.SOULS, false));
		r1.add(e(Keys.T_UNION, "union", a, shortNames ? "Unión" : "Unión (mant.: Cirugía)", null, false));
		r1.add(e(Keys.T_RAIN, "lluvia", a, "Lluvia", Tech.RAIN, !room));
		r1.add(e(Keys.T_MES, "mes", a, heart ? "Devolver" : "Mes", Tech.MES, false));
		r1.add(awake ? e(Keys.T_COUNTER, "puncture", a, "Puncture Wille", Tech.PUNCTURE, false)
			: e(Keys.T_COUNTER, "counter", a, "Counter Shock", Tech.COUNTER, false));
		r2.add(e(Keys.T_PILLAR, "pilar", a, "Pilar", null, !room));
		r2.add(e(Keys.T_WALL, "muralla", a, "Muralla", null, !room));
		r2.add(e(Keys.T_CHASM, "sima", a, "Sima", null, !room));
		r2.add(e(Keys.T_PLATEAU, "meseta", a, "Meseta", null, !room));
		int pat = ClientState.pattern;
		String pattern = pat >= 0 && pat < Patterns.NAMES.length ? Patterns.NAMES[pat] : Patterns.NAMES[0];
		boolean target = ClientState.has(Payloads.State.REACH_TARGET);
		boolean suspend = ClientState.has(Payloads.State.SUSPEND);
		r3.add(e(Keys.T_PATTERN, "patron", a, "Patrón: " + pattern, null, false));
		r3.add(e(Keys.T_REACH, "alcance", a, shortNames ? (target ? "Objetivo" : "Todo")
			: (target ? "Alcance: solo el objetivo" : "Alcance: todo el ROOM"), null, false));
		r3.add(e(Keys.T_SUSPEND, "suspender", a, suspend ? "Suspender: sí" : "Suspender: no", null, false));
		return List.of(r1, r2, r3);
	}

	private static List<List<Entry>> taktRows(boolean none) {
		List<Entry> r1 = new ArrayList<>(), r2 = new ArrayList<>(), r3 = new ArrayList<>();
		int a = Theme.SOFT, f = Theme.FORGE;
		r1.add(e(Keys.R_ORBIT, "orbita", a, ClientState.taktMode == 1 ? "A la mano" : "Órbita", null, none));
		r1.add(e(Keys.R_PARK, "aparcar", a, "Aparcar", null, none));
		r1.add(e(Keys.R_SETDOWN, "posar", a, "Posar", null, none));
		r1.add(e(Keys.R_DROP, "soltar", a, "Soltar", null, none));
		r1.add(new Entry(KeyLabels.mouseRight(), "lanzar_todo", a, "Lanzar todo", null, none));
		r1.add(new Entry(KeyLabels.wheel(), "alcance", a, "Distancia", null, none));
		r2.add(e(Keys.R_ZONE, "zona", a, "Zona", null, none));
		r2.add(e(Keys.R_CUT, "lo_cortado", a, "Lo cortado", null, none));
		r2.add(e(Keys.R_GATHER, "rejuntar", a, "Rejuntar", null, none));
		r2.add(e(Keys.R_UNITE, "unir", a, "Unir todo", null, none));
		Keys[] forge = {Keys.R_FORGE1, Keys.R_FORGE2, Keys.R_FORGE3, Keys.R_FORGE4, Keys.R_FORGE5};
		String[] names = {"Lanza", "Hoja colosal", "Martillo", "Estacas", "Muralla"};
		for (int i = 0; i < forge.length; i++) r3.add(e(forge[i], Icons.WEAPONS[i], f, names[i], null, none));
		return List.of(r1, r2, r3);
	}

	private static int entryWidth(HudCanvas c, Entry en) {
		int w = Ui.keycapWidth(c.tr, en.key()) + 2 + 12 + 3 + c.tr.getWidth(en.name());
		if (en.cool() != null) w += 4 + c.tr.getWidth(en.cool());
		return w;
	}

	private static Ui.Line reason(String before, KeyLabels.Glyph key, String after) {
		Ui.Line l = new Ui.Line().icon("aviso", Theme.OUT_WARN).text(before, Theme.OUT_WARN);
		if (key != null) l.key(key);
		if (after != null) l.text(after, Theme.OUT_WARN);
		return l;
	}

	static void layout(HudCanvas c, LayoutState s) {
		canvas = c;
		visible = false;
		ENTRIES.clear();
		REASONS.clear();
		if (!s.stripOpen) return;
		boolean tab = InputHandler.row2Down;
		boolean shortNames = c.W < 520;
		List<List<Entry>> rows;
		if (tab) {
			boolean room = ClientState.ownRoom() != null;
			rows = tabRows(shortNames, room);
			if (!room) {
				REASONS.add(reason("El suelo se trabaja dentro de un ROOM (", KeyLabels.of(Keys.ROOM), ")"));
				REASONS.add(reason("Lluvia necesita un ROOM", null, null));
			}
		} else {
			boolean none = ClientState.taktCount <= 0;
			rows = taktRows(none);
			if (none) {
				REASONS.add(reason("No controlas nada:", KeyLabels.of(Keys.TAKT), "sobre algo para cogerlo"));
				REASONS.add(reason("Forjar necesita material en Takt", null, null));
			}
		}

		// Cabecera: título y cómo se cierra
		Keys activator = tab ? Keys.ROW2 : Keys.TAKT;
		boolean latched = tab ? InputHandler.row2Latched() : InputHandler.rowRLatched();
		boolean oneKey = tab ? InputHandler.row2OneKey() : InputHandler.rowROneKey();
		header = new Ui.Line().text(tab ? "SEGUNDA FILA" : "ÓRDENES DE TAKT", tab ? Theme.TAB : Theme.SOFT).gap(6);
		if (oneKey) header.text("· para la próxima tecla", Theme.TEXT3);
		else if (latched) header.text("· fija:", Theme.TEXT3).key(KeyLabels.of(activator)).text("la cierra", Theme.TEXT3);
		else header.text("· sin soltar", Theme.TEXT3).key(KeyLabels.of(activator));

		// Entradas: cada fila lógica centrada, partida si no cabe
		int maxInner = Math.min(c.W - 12, 420) - 8;
		List<List<Entry>> lines = new ArrayList<>();
		List<Integer> widths = new ArrayList<>();
		for (List<Entry> row : rows) {
			List<Entry> line = new ArrayList<>();
			int lw = 0;
			for (Entry en : row) {
				int ew = entryWidth(c, en);
				if (!line.isEmpty() && lw + 8 + ew > maxInner) {
					lines.add(line);
					widths.add(lw);
					line = new ArrayList<>();
					lw = 0;
				}
				lw += (line.isEmpty() ? 0 : 8) + ew;
				line.add(en);
			}
			if (!line.isEmpty()) {
				lines.add(line);
				widths.add(lw);
			}
		}
		int inner = header.width(c.tr);
		for (int lw : widths) inner = Math.max(inner, lw);
		for (Ui.Line r : REASONS) inner = Math.max(inner, r.width(c.tr));
		inner = Math.min(inner, maxInner);
		w = inner + 8;
		h = 4 + 12 + 3 + lines.size() * 12 + Math.max(0, lines.size() - 1) * 3 + REASONS.size() * 15 + 4;
		x = c.cx - w / 2;

		// Debajo de la mira; si se mete en la barra de acción o la de vida, encima
		int top = c.cy + 14;
		int limit = Math.min(c.actionBar.y(), c.health != null ? c.health.y() : c.H) - 2;
		if (top + h > limit) top = c.cy - 14 - h;
		if (top < c.M) top = c.M;
		y = top;
		// Si arriba cae sobre la píldora o los chips, se aparta a la derecha cuando cabe
		if (s.leftBottom > y && s.leftRight > x) {
			if (s.leftRight + 4 + w <= c.W - c.M) x = s.leftRight + 4;
			// no cabe al lado (la de R es muy ancha): la tira manda y la píldora y los chips esperan a que se cierre
			else s.leftHidden = true;
		}

		int ly = y + 4 + 12 + 3;
		for (int i = 0; i < lines.size(); i++) {
			int lx = x + 4 + (inner - Math.min(inner, widths.get(i))) / 2;
			for (Entry en : lines.get(i)) {
				ENTRIES.add(new Placed(en, lx, ly));
				lx += entryWidth(c, en) + 8;
			}
			ly += 15;
		}
		reasonsY = ly;
		visible = true;
		s.strip = new Rect(x, y, w, h);
		s.place("tira", false, s.strip);
	}

	static void draw(Ui.Batch b) {
		if (!visible || canvas == null || header == null) return;
		Ui.panel(b, x, y, w, h, 5, Theme.PANEL_TOP, Theme.PANEL_BOTTOM, Theme.HAIRLINE);
		header.draw(b, x + 4, y + 4, w - 8);
		float old = b.alpha;
		for (Placed p : ENTRIES) {
			Entry en = p.e();
			b.alpha = en.disabled() ? old * 0.45f : old;
			int ex = p.x(), ey = p.y();
			int kw = Ui.keycap(b, ex, ey, en.key(), 0);
			Ui.icon(b, Ui.ICONS, en.icon(), ex + kw + 2, ey, en.accent());
			int tx = ex + kw + 2 + 12 + 3;
			b.text(en.name(), tx, ey + 2, Theme.TEXT, false);
			if (en.cool() != null) b.text(en.cool(), tx + b.tr.getWidth(en.name()) + 4, ey + 2, Theme.TEXT3, false);
		}
		b.alpha = old;
		int ry = reasonsY;
		for (Ui.Line r : REASONS) {
			r.draw(b, x + 4, ry, w - 8);
			ry += 15;
		}
	}
}
