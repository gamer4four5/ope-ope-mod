package dev.opeope.client.hud;

import dev.opeope.client.ClientState;
import dev.opeope.client.config.OpeSettings;
import dev.opeope.client.hud.HudCanvas.LayoutState;
import dev.opeope.client.hud.HudCanvas.Rect;
import dev.opeope.client.input.InputHandler;
import dev.opeope.client.input.KeyLabels;
import dev.opeope.client.input.Keys;
import dev.opeope.client.room.RoomHudState;
import dev.opeope.net.Payloads;
import dev.opeope.power.Tech;
import dev.opeope.registry.ModItems;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;

/**
 * La barra de técnicas (spec §3.7): once casillas abajo a la derecha, con el enfriamiento como un velo que se retira
 * de abajo arriba, los segundos, la tecla de cada una, un destello al quedar lista y su nombre al usarla.
 */
public final class AbilityBar {
	private AbilityBar() {}

	static final int N = 11;
	private static final Keys[] KEYS = {Keys.ROOM, Keys.TAKT, Keys.SHAMBLES, Keys.ROW2, Keys.RADIO_KNIFE, Keys.INJECTION,
		Keys.GAMMA, Keys.SCAN, Keys.CURTAIN, Keys.MARKER, Keys.AWAKEN};

	/** Candidatas en orden de preferencia: tamaño de casilla y filas de abajo arriba. */
	private static final int[] SIZES = {18, 18, 16, 16, 16};
	private static final int[][][] ROWS = {
		{{0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10}},
		{{0, 1, 2, 3, 9, 10}, {4, 5, 6, 7, 8}},
		{{0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10}},
		{{0, 1, 2, 3, 9, 10}, {4, 5, 6, 7, 8}},
		{{0, 1, 2, 3}, {4, 5, 6, 7}, {8, 9, 10}}
	};
	/** La barra vertical: dos columnas, de arriba abajo. */
	private static final int[][] VERTICAL = {{0, 1}, {2, 3}, {4, 5}, {6, 7}, {8, 9}, {10}};
	/** Último recurso de la vertical cuando la columna de dos no cabe en alto. */
	private static final int[][] VERTICAL_WIDE = {{0, 1, 2}, {3, 4, 5}, {6, 7, 8}, {9, 10}};

	// Estado de cada casilla, recalculado en tick y al colocar
	private static final String[] icon = new String[N];
	private static final String[] name = new String[N];
	private static final String[] badge = new String[N];
	private static final int[] cd = new int[N], total = new int[N], accent = new int[N], badgeBg = new int[N], special = new int[N];
	private static final boolean[] lit = new boolean[N], locked = new boolean[N], hasTech = new boolean[N];
	private static final Tech[][] denyTechs = new Tech[N][];
	private static boolean underwater;

	// Transiciones
	private static final int[] prevCd = new int[N];
	private static final boolean[] prevLit = new boolean[N];
	private static final long[] flashAt = new long[N];
	private static int popTile = -1;
	private static String popName = "";
	private static long popAt;
	private static boolean primed;

	// Fundidos (calma y «Al usarla»)
	private static float alpha = 1f;
	private static long lastFrame;

	// Colocación de este fotograma
	private static boolean visible;
	private static int tile;
	private static final int[] tx = new int[N], ty = new int[N];
	private static final int[] relX = new int[N], relY = new int[N];
	private static HudCanvas canvas;

	private static int cooldown(Tech t) {
		int i = t.ordinal();
		return i < ClientState.cooldowns.length ? Math.max(0, ClientState.cooldowns[i]) : 0;
	}

	private static void tech(int i, String ic, Tech t) {
		icon[i] = ic;
		name[i] = t.label;
		cd[i] = cooldown(t);
		total[i] = t.ticks;
		hasTech[i] = true;
		denyTechs[i] = new Tech[]{t};
	}

	private static void plain(int i, String ic, String n, boolean on, int color) {
		icon[i] = ic;
		name[i] = n;
		cd[i] = 0;
		total[i] = 0;
		hasTech[i] = false;
		denyTechs[i] = new Tech[0];
		lit[i] = on;
		accent[i] = color;
	}

	/** Lee el estado de la fruta y rellena las casillas. */
	private static void compute(MinecraftClient mc) {
		boolean awake = ClientState.awakened();
		ClientPlayerEntity p = mc.player;
		for (int i = 0; i < N; i++) {
			lit[i] = false;
			locked[i] = false;
			badge[i] = null;
			special[i] = 0;
			accent[i] = Theme.HI;
		}
		underwater = p != null && p.isSubmergedInWater() && !p.hasVehicle() && !p.isCreative() && !p.isSpectator();

		plain(0, "room", "ROOM", ClientState.ownRoom() != null, Theme.ROOM);

		plain(1, "takt", "Takt", ClientState.taktCount > 0, Theme.SOFT);
		if (ClientState.taktCount > 0) {
			badge[1] = Integer.toString(ClientState.taktCount);
			badgeBg[1] = Theme.SOFT;
		}

		tech(2, "shambles", Tech.SHAMBLES);

		// Segunda fila: el enfriamiento más largo de las suyas y cuántas se enfrían
		Tech[] row2 = {Tech.MES, awake ? Tech.PUNCTURE : Tech.COUNTER, Tech.STERBEN, Tech.SOULS, Tech.RAIN};
		Tech longest = null;
		int cooling = 0;
		for (Tech t : row2) {
			int c = cooldown(t);
			if (c > 0) {
				cooling++;
				if (longest == null || c > cooldown(longest)) longest = t;
			}
		}
		icon[3] = "fila2";
		name[3] = "Segunda fila";
		cd[3] = longest != null ? cooldown(longest) : 0;
		total[3] = longest != null ? longest.ticks : 0;
		hasTech[3] = true;
		denyTechs[3] = row2;
		lit[3] = InputHandler.row2Down && !InputHandler.playerListShown();
		accent[3] = Theme.TAB;
		if (cooling >= 2) {
			badge[3] = Integer.toString(cooling);
			badgeBg[3] = Theme.TAB;
		}

		boolean heart = p != null && ModItems.HEART != null && p.getMainHandStack().isOf(ModItems.HEART);
		if (heart) {
			plain(4, "corazon", "Apretar", false, Theme.HEART);
			special[4] = Theme.HEART;
		} else {
			tech(4, awake ? "kroom" : "radio_knife", awake ? Tech.KROOM : Tech.RADIO_KNIFE);
		}

		tech(5, awake ? "anestesia" : "injection", awake ? Tech.ANESTHESIA : Tech.INJECTION);
		tech(6, awake ? "shock_wille" : "gamma", awake ? Tech.SHOCK_WILLE : Tech.GAMMA);
		tech(7, awake ? "rroom" : "scan", awake ? Tech.RROOM : Tech.SCAN);
		lit[7] = ClientState.scanTicks > 0;
		tech(8, awake ? "silent" : "curtain", awake ? Tech.SILENT : Tech.CURTAIN);
		lit[8] = ClientState.has(Payloads.State.CURTAIN) || ClientState.has(Payloads.State.SILENT);
		tech(9, "piedra", Tech.MARKER);

		plain(10, "despertar", "Despertar", awake, Theme.AWAKE);
		locked[10] = !ClientState.has(Payloads.State.AWAKEN_UNLOCKED);
	}

	/** Cada tick: enfriamientos que empiezan (nombre) o acaban (destello), y casillas que se encienden. */
	static void tick(MinecraftClient mc, long now) {
		if (mc.player == null || !ClientState.hasFruit()) {
			primed = false;
			return;
		}
		compute(mc);
		for (int i = 0; i < N; i++) {
			if (primed) {
				if (hasTech[i]) {
					if (prevCd[i] == 0 && cd[i] > 0) pop(i, now);
					if (prevCd[i] > 0 && cd[i] == 0) flashAt[i] = now;
				} else if (!prevLit[i] && lit[i]) {
					pop(i, now);
				}
			}
			prevCd[i] = cd[i];
			prevLit[i] = lit[i];
		}
		primed = true;
	}

	private static void pop(int i, long now) {
		popTile = i;
		popName = name[i];
		popAt = now;
	}

	static void clear() {
		primed = false;
		popTile = -1;
		popName = "";
		popAt = 0;
		alpha = 1f;
		lastFrame = 0;
		visible = false;
		for (int i = 0; i < N; i++) {
			prevCd[i] = 0;
			prevLit[i] = false;
			flashAt[i] = 0;
		}
	}

	// ------------------------------------------------------------------ colocación

	private static int gapFor(int s) {
		return s == 18 ? 3 : 2;
	}

	/** Coloca filas (de abajo arriba, o de arriba abajo si topDown) pegadas a la derecha; devuelve {ancho, alto}. */
	private static int[] arrange(int s, int[][] rows, boolean topDown, int[] kw) {
		int g = gapFor(s), rg = g + 2;
		boolean oneRow = rows.length == 1;
		int minX = 0;
		for (int r = 0; r < rows.length; r++) {
			int[] row = rows[r];
			int level = topDown ? rows.length - 1 - r : r;
			int yTop = -2 - s - level * (s + rg);
			int x = -2;
			for (int k = row.length - 1; k >= 0; k--) {
				int idx = row[k];
				relX[idx] = x - s;
				relY[idx] = yTop;
				minX = Math.min(minX, Math.min(x - s, x + 2 - kw[idx]));
				if (k > 0) {
					int left = row[k - 1];
					int gap = g + (oneRow && (left == 3 || left == 8) ? 4 : 0);
					// que la tecla de esta casilla no pise la de la de su izquierda
					gap = Math.max(gap, kw[idx] - s + 1);
					x = x - s - gap;
				}
			}
		}
		return new int[]{-minX, rows.length * s + (rows.length - 1) * rg + 2};
	}

	/** La parte de arriba de una zona de Minecraft si cae en la franja [x1, x2); si no, Integer.MAX_VALUE. */
	private static int topIn(Rect zone, int x1, int x2) {
		return zone != null && zone.overlapsX(x1, x2) ? zone.y() : Integer.MAX_VALUE;
	}

	private static boolean free(HudCanvas c, Rect r) {
		if (!r.inside(c.W, c.H)) return false;
		if (r.overlaps(c.hotbar) || r.overlaps(c.health) || r.overlaps(c.actionBar)) return false;
		if (r.overlaps(c.effBen) || r.overlaps(c.effHarm) || r.overlaps(c.boss)) return false;
		return !r.overlaps(c.subtitles);
	}

	static void layout(HudCanvas c, LayoutState s) {
		canvas = c;
		visible = false;
		MinecraftClient mc = MinecraftClient.getInstance();
		OpeSettings st = OpeSettings.get();
		if (st.interfaz == OpeSettings.Interfaz.MINIMA || st.barra == OpeSettings.Barra.OCULTA || mc.player == null) return;
		compute(mc);
		lit[3] = s.stripOpen && InputHandler.row2Down;

		int[] kw = new int[N];
		for (int i = 0; i < N; i++) kw[i] = Ui.miniKeycapWidth(c.tr, KeyLabels.of(KEYS[i]));

		boolean placed = false;
		if (st.barra != OpeSettings.Barra.VERTICAL) {
			int xMin = c.hotbarRight() + 6;
			int[][] regions;
			if (c.subtitlesOn && c.subtitles != null) {
				regions = new int[][]{
					{xMin, c.subtitles.x() - 4, c.H - c.M},
					{c.cx + c.v(91) + 6, c.W - c.M, c.subtitles.y() - 4}
				};
			} else {
				regions = new int[][]{{xMin, c.W - c.M, c.H - c.M}};
			}
			int minTop = c.H / 2 + 24;
			for (int[] reg : regions) {
				for (int k = 0; k < SIZES.length && !placed; k++) {
					int[] wh = arrange(SIZES[k], ROWS[k], false, kw);
					int w = wh[0], h = wh[1];
					int x = reg[1] - w, y = reg[2] - h;
					if (x < reg[0] || y < minTop) continue;
					Rect r = new Rect(x, y, w, h);
					if (!free(c, r)) continue;
					commit(s, r, SIZES[k], ROWS[k].length, k + 1, false);
					placed = true;
				}
				if (placed) break;
			}
		}
		if (!placed) {
			int size = c.H >= 300 ? 18 : 16;
			// debajo de los efectos y con sitio para el título de la tarjeta de pistas
			int minTop = Math.max(c.M, c.effectsBottom + 4) + 14 + 4;
			// la columna de dos; si no cabe (lienzo 4:3 pequeño con efectos), la de tres
			int[][][] layouts = {VERTICAL, VERTICAL_WIDE};
			for (int k = 0; k < layouts.length && !placed; k++) {
				int[] wh = arrange(size, layouts[k], true, kw);
				int w = wh[0], h = wh[1];
				int x = c.W - c.M - w;
				int top = Math.round(c.H / 2f + 8 - h / 2f);
				int limit = c.subtitlesOn && c.subtitles != null ? c.subtitles.y() - 4 : c.H - c.M;
				// en lienzos estrechos (4:3) la barra de acción, la vida o la barra rápida pueden caer en su columna
				limit = Math.min(limit, topIn(c.actionBar, x, x + w) - 2);
				limit = Math.min(limit, topIn(c.health, x, x + w) - 2);
				limit = Math.min(limit, topIn(c.hotbar, x, x + w) - 2);
				if (top + h > limit) top = limit - h;
				if (top < minTop) {
					if (k + 1 < layouts.length) continue;
					top = minTop;
				}
				commit(s, new Rect(x, top, w, h), size, layouts[k].length, 6 + k, true);
				placed = true;
			}
		}

		// Calma (Abajo / Vertical) y «Al usarla»
		long now = c.now;
		long active = Math.max(InputHandler.lastFruitInputAt(), RoomHudState.lastEventAt());
		boolean busy = s.stripOpen;
		for (int i = 0; i < N; i++) {
			active = Math.max(active, flashAt[i]);
			if (cd[i] > 0) busy = true;
		}
		if (busy) active = now;
		float low = st.barra == OpeSettings.Barra.AL_USARLA ? 0f : 0.55f;
		long wait = st.barra == OpeSettings.Barra.AL_USARLA ? 4000 : 5000;
		float target = now - active > wait ? low : 1f;
		float dt = lastFrame == 0 ? 1f : Math.max(0f, Math.min(0.05f, (now - lastFrame) / 1000f));
		lastFrame = now;
		alpha = Anim.approach(alpha, target, dt * (1f - low) / 0.15f);
	}

	private static void commit(LayoutState s, Rect r, int size, int rows, int candidate, boolean vertical) {
		tile = size;
		for (int i = 0; i < N; i++) {
			tx[i] = r.right() + relX[i];
			ty[i] = r.bottom() + relY[i];
		}
		visible = true;
		s.bar = r;
		s.barVertical = vertical;
		s.barTile = size;
		s.barRows = rows;
		s.barCandidate = candidate;
		s.place("barra", true, r);
	}

	// ------------------------------------------------------------------ dibujo

	static void draw(Ui.Batch b) {
		if (!visible || canvas == null || alpha <= 0.01f) return;
		long now = canvas.now;
		b.alpha = alpha;
		try {
			for (int i = 0; i < N; i++) drawTile(b, i, now);
			drawPop(b, now);
		} finally {
			b.alpha = 1f;
		}
	}

	private static long deniedAt(int i) {
		long at = 0;
		Tech[] ts = denyTechs[i];
		if (ts == null) return 0;
		for (Tech t : ts) at = Math.max(at, InputHandler.deniedAt(t.ordinal()));
		return at;
	}

	private static void drawTile(Ui.Batch b, int i, long now) {
		int s = tile, r = s == 18 ? 3 : 2;
		int x = tx[i], y = ty[i];
		long den = deniedAt(i);
		boolean denied = den > 0 && now >= den && now - den < 300;
		if (denied && !Anim.reduced()) {
			float t = (now - den) / 1000f;
			x += (int) Math.round(2 * Math.sin(2 * Math.PI * 12 * t) * (1 - t / 0.3f));
		}

		// Fondo
		Ui.shadow(b, x, y, s, s, r);
		Ui.roundRect(b, Ui.BACK, x, y, s, s, r, Theme.TILE_TOP, Theme.TILE_BOTTOM);
		Ui.hilite(b, Ui.BACK, x, y, s, s, r);
		Ui.roundRing(b, Ui.BACK, x, y, s, s, r, Theme.HAIRLINE);
		if (lit[i]) {
			Ui.roundRing(b, Ui.BACK, x, y, s, s, r, Theme.withAlpha(accent[i], 0x99));
			if (Theme.GLOW_RING != 0) Ui.roundRing(b, Ui.BACK, x - 1, y - 1, s + 2, s + 2, r + 1, Theme.GLOW_RING);
		}

		// Icono, teñido según el estado
		int tint;
		if (locked[i]) tint = Theme.mulAlpha(Theme.TEXT3, 0.45f);
		else if (underwater) tint = Theme.TEXT3;
		else if (lit[i]) tint = accent[i];
		else if (cd[i] > 0) tint = Theme.TEXT3;
		else if (special[i] != 0) tint = special[i];
		else tint = Theme.TEXT;
		int io = (s - 12) / 2;
		Ui.icon(b, Ui.ICONS, icon[i], x + io, y + io, tint);

		// Velo del enfriamiento: cubre las filas de arriba y se retira hacia arriba
		if (cd[i] > 0 && total[i] > 0) {
			int rows = Math.round((s - 2) * Math.min(1f, cd[i] / (float) total[i]));
			Ui.interiorRows(b, Ui.OVER, x, y, s, s, r, 0, Math.max(1, rows), Theme.COOLDOWN);
		}
		if (underwater) {
			for (int k = 0; k < s - 2; k++) b.fill(Ui.OVER, x + s - 2 - k, y + 1 + k, x + s - 1 - k, y + 2 + k, Theme.BAD);
		}
		// Destello al quedar lista
		if (flashAt[i] > 0 && now - flashAt[i] < 450) {
			float t = (now - flashAt[i]) / 450f;
			Ui.interiorRows(b, Ui.OVER, x, y, s, s, r, 0, s - 2, Theme.withAlpha(Theme.WHITE, Math.round(255 * 0.35f * (1 - t))));
			Ui.roundRing(b, Ui.OVER, x, y, s, s, r, Theme.mulAlpha(Theme.HI, 1 - t));
		}
		if (denied) Ui.roundRing(b, Ui.OVER, x, y, s, s, r, Theme.BAD);
		if (badge[i] != null) {
			int tw = b.tr.getWidth(badge[i]);
			int bw = Math.max(9, tw + 3);
			int bx = x + s - bw;
			Ui.roundRect(b, Ui.OVER, bx, y, bw, 9, 2, badgeBg[i], badgeBg[i]);
			b.text(badge[i], bx + (bw - tw + 1) / 2, y + 1, Theme.BADGE_TEXT, false);
		}
		if (locked[i]) Ui.icon(b, Ui.ICONS2, "candado", x + s - 8, y, Theme.TEXT);

		// Segundos sobre el velo
		if (cd[i] > 0) {
			String sec = Txt.secsShort(cd[i]);
			int tw = b.tr.getWidth(sec);
			b.text(sec, x + (s - tw + 1) / 2, y + (s - 10) / 2, Theme.TEXT, Theme.floatShadow());
		}

		// Su tecla, abajo a la derecha
		KeyLabels.Glyph g = KeyLabels.of(KEYS[i]);
		int kw = Ui.miniKeycapWidth(b.tr, g);
		Ui.miniKeycap(b, x + s + 2 - kw, y + s + 2 - 9, g);
	}

	private static void drawPop(Ui.Batch b, long now) {
		if (popTile < 0 || popName == null || popName.isEmpty()) return;
		long age = now - popAt;
		if (age < 0 || age >= 1300) return;
		float a = age > 1000 ? (1300 - age) / 300f : 1f;
		HudCanvas c = canvas;
		int tw = b.tr.getWidth(popName);
		int w = tw + 10, h = 11;
		int x = tx[popTile] + tile / 2 - w / 2;
		x = Math.max(c.M, Math.min(c.W - c.M - w, x));
		int y = ty[popTile] - h - 3;
		float old = b.alpha;
		b.alpha = old * a;
		try {
			Ui.roundRect(b, Ui.OVER, x, y, w, h, 5, Theme.PANEL_TOP, Theme.PANEL_BOTTOM);
			Ui.roundRing(b, Ui.OVER, x, y, w, h, 5, Theme.HAIRLINE);
			b.text(popName, x + 5, y + 2, Theme.TEXT, false);
		} finally {
			b.alpha = old;
		}
	}
}
