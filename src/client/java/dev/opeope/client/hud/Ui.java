package dev.opeope.client.hud;

import com.mojang.blaze3d.systems.RenderSystem;
import dev.opeope.client.input.KeyLabels;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.util.math.MatrixStack;

import java.util.ArrayList;
import java.util.List;

/**
 * Primitivas de dibujo del HUD (spec §3.2 y §3.4). Los módulos encolan en un Batch con cinco fases (fondos, iconos,
 * encima, iconos 2, texto) y HudRoot las vacía en orden: en 1.21.1 los rellenos y el texto se pintan al momento salvo
 * dentro de ctx.draw(Runnable), y drawGuiTexture se pinta al momento. Todo va a z = 0, así que manda el orden.
 */
public final class Ui {
	private Ui() {}

	public static final int BACK = 0, ICONS = 1, OVER = 2, ICONS2 = 3, TEXT = 4;

	/** Sangría de las filas de arriba de cada radio (las de abajo son su espejo). */
	private static final int[][] INSETS = {
		{}, {1}, {1, 0}, {2, 1, 0}, {2, 1, 1, 0}, {3, 2, 1, 0, 0}, {4, 2, 1, 1, 0, 0}, {5, 3, 2, 1, 1, 0, 0}
	};

	public static final KeyLabels.Glyph SHIFT = new KeyLabels.Glyph(null, "May");
	public static final KeyLabels.Glyph CTRL = new KeyLabels.Glyph(null, "Ctrl");

	// ------------------------------------------------------------------ formas

	private static int clampRadius(int r, int w, int h) {
		return Math.max(0, Math.min(Math.min(r, 7), Math.min(w, h) / 2));
	}

	/** Sangría de la fila j (0..h-1) de una forma de h filas y radio r. */
	public static int inset(int r, int h, int j) {
		if (r <= 0 || h <= 0 || j < 0 || j >= h) return 0;
		int[] ins = INSETS[Math.min(r, 7)];
		int k = Math.min(j, h - 1 - j);
		return k < ins.length ? ins[k] : 0;
	}

	/** Rectángulo redondeado con degradado vertical (top → bottom). */
	public static void roundRect(Batch b, int phase, int x, int y, int w, int h, int r, int top, int bottom) {
		if (w <= 0 || h <= 0) return;
		r = clampRadius(r, w, h);
		int[] ins = INSETS[r];
		int t = 0;
		while (t < ins.length && ins[t] > 0) t++;
		t = Math.min(t, h / 2);
		for (int j = 0; j < t; j++) {
			int in = ins[j];
			b.fill(phase, x + in, y + j, x + w - in, y + j + 1, rowColor(top, bottom, j, h));
			int jb = h - 1 - j;
			b.fill(phase, x + in, y + jb, x + w - in, y + jb + 1, rowColor(top, bottom, jb, h));
		}
		if (h - 2 * t > 0) {
			b.gradient(phase, x, y + t, x + w, y + h - t, Theme.lerp(top, bottom, t / (float) h), Theme.lerp(top, bottom, (h - t) / (float) h));
		}
	}

	private static int rowColor(int top, int bottom, int j, int h) {
		return top == bottom ? top : Theme.lerp(top, bottom, (j + 0.5f) / h);
	}

	/** Aro de 1 unidad: la forma menos la misma forma metida 1 (con radio r-1), fila a fila. */
	public static void roundRing(Batch b, int phase, int x, int y, int w, int h, int r, int argb) {
		if (w <= 0 || h <= 0 || (argb >>> 24) == 0) return;
		r = clampRadius(r, w, h);
		if (w <= 2 || h <= 2) {
			roundRect(b, phase, x, y, w, h, r, argb, argb);
			return;
		}
		int ri = Math.max(0, r - 1), hi = h - 2;
		b.fill(phase, x + inset(r, h, 0), y, x + w - inset(r, h, 0), y + 1, argb);
		b.fill(phase, x + inset(r, h, h - 1), y + h - 1, x + w - inset(r, h, h - 1), y + h, argb);
		int start = 1, curO = -1, curI = -1;
		for (int j = 1; j <= h - 1; j++) {
			int o = -1, i = -1;
			if (j < h - 1) {
				o = inset(r, h, j);
				i = 1 + inset(ri, hi, j - 1);
			}
			if (j == h - 1 || o != curO || i != curI) {
				if (curO >= 0 && curI > curO && j > start) {
					b.fill(phase, x + curO, y + start, x + curI, y + j, argb);
					b.fill(phase, x + w - curI, y + start, x + w - curO, y + j, argb);
				}
				start = j;
				curO = o;
				curI = i;
			}
		}
	}

	/** Lo que asoma de la forma movida dy hacia abajo por debajo de la forma original. */
	private static void shapeBelow(Batch b, int phase, int x, int y, int w, int h, int r, int dy, int argb) {
		for (int k = dy; k < h + dy; k++) {
			int o1 = inset(r, h, k - dy);
			if (k >= h) {
				b.fill(phase, x + o1, y + k, x + w - o1, y + k + 1, argb);
			} else {
				int o2 = inset(r, h, k);
				if (o1 < o2) {
					b.fill(phase, x + o1, y + k, x + o2, y + k + 1, argb);
					b.fill(phase, x + w - o2, y + k, x + w - o1, y + k + 1, argb);
				}
			}
		}
	}

	/** Sombra (fase BACK, antes del panel): la misma forma 1 y 2 unidades más abajo. No en alto contraste. */
	public static void shadow(Batch b, int x, int y, int w, int h, int r) {
		if (Theme.hc || w <= 0 || h <= 0) return;
		r = clampRadius(r, w, h);
		shapeBelow(b, BACK, x, y, w, h, r, 2, Theme.SHADOW_2);
		shapeBelow(b, BACK, x, y, w, h, r, 1, Theme.SHADOW_1);
	}

	/** Brillo de la primera fila de dentro. No en alto contraste. */
	public static void hilite(Batch b, int phase, int x, int y, int w, int h, int r) {
		if (Theme.HILITE == 0 || w < 4 || h < 4) return;
		r = clampRadius(r, w, h);
		int in = 1 + inset(Math.max(0, r - 1), h - 2, 0);
		b.fill(phase, x + in, y + 1, x + w - in, y + 2, Theme.HILITE);
	}

	/** Las filas [from, to) del interior (la forma metida 1) de una caja: el velo de enfriamiento, el destello. */
	public static void interiorRows(Batch b, int phase, int x, int y, int w, int h, int r, int from, int to, int argb) {
		int ih = h - 2, iw = w - 2;
		if (ih <= 0 || iw <= 0) return;
		int ri = clampRadius(Math.max(0, r - 1), iw, ih);
		from = Math.max(0, from);
		to = Math.min(ih, to);
		int j = from;
		while (j < to) {
			int in = inset(ri, ih, j);
			int k = j + 1;
			while (k < to && inset(ri, ih, k) == in) k++;
			b.fill(phase, x + 1 + in, y + 1 + j, x + w - 1 - in, y + 1 + k, argb);
			j = k;
		}
	}

	/** Panel completo: sombra, degradado, brillo y aro (ring 0 = sin aro). */
	public static void panel(Batch b, int x, int y, int w, int h, int r, int top, int bottom, int ring) {
		shadow(b, x, y, w, h, r);
		roundRect(b, BACK, x, y, w, h, r, top, bottom);
		hilite(b, BACK, x, y, w, h, r);
		if (ring != 0) roundRing(b, BACK, x, y, w, h, r, ring);
	}

	/** Píldora de h de alto (14 → r7, 12 → r6, 11 → r5). */
	public static int pillRadius(int h) {
		return Math.min(7, h / 2);
	}

	// ------------------------------------------------------------------ teclas

	private static String glyphText(KeyLabels.Glyph g) {
		return g == null || g.text() == null ? "" : g.text();
	}

	private static boolean glyphIcon(KeyLabels.Glyph g) {
		return g != null && g.icon() != null && !g.icon().isEmpty();
	}

	/** Ancho de la tecla normal (11 de alto): solo texto max(11, tw+6); solo dibujo 11; dibujo y texto 2+7+1+tw+3. */
	public static int keycapWidth(TextRenderer tr, KeyLabels.Glyph g) {
		String t = glyphText(g);
		int tw = t.isEmpty() ? 0 : tr.getWidth(t);
		if (glyphIcon(g)) return t.isEmpty() ? 11 : 2 + 7 + 1 + tw + 3;
		return Math.max(11, tw + 6);
	}

	/** Tecla normal: fondo, aro (ring 0 = el de siempre), fila de profundidad, dibujo del ratón y texto. */
	public static int keycap(Batch b, int x, int y, KeyLabels.Glyph g, int ring) {
		int w = keycapWidth(b.tr, g);
		roundRect(b, OVER, x, y, w, 11, 2, Theme.KEYCAP_BG, Theme.KEYCAP_BG);
		roundRing(b, OVER, x, y, w, 11, 2, ring != 0 ? ring : Theme.KEYCAP_RING);
		b.fill(OVER, x + 1, y + 11, x + w - 1, y + 12, Theme.KEYCAP_DEPTH);
		String t = glyphText(g);
		if (glyphIcon(g)) {
			b.icon(ICONS2, g.icon(), x + 2, y + 1, 7, 9, Theme.TEXT);
			if (!t.isEmpty()) b.text(t, x + 10, y + 2, Theme.TEXT, false);
		} else if (!t.isEmpty()) {
			b.text(t, x + (w - b.tr.getWidth(t) + 1) / 2, y + 2, Theme.TEXT, false);
		}
		return w;
	}

	/** Ancho de la tecla pequeña de las casillas (9 de alto). */
	public static int miniKeycapWidth(TextRenderer tr, KeyLabels.Glyph g) {
		String t = glyphText(g);
		int tw = t.isEmpty() ? 0 : tr.getWidth(t);
		if (glyphIcon(g)) return t.isEmpty() ? 9 : 1 + 7 + 1 + tw + 2;
		return Math.max(9, tw + 4);
	}

	/** Tecla pequeña (casillas de la barra): 9 de alto, sin fila de profundidad. */
	public static int miniKeycap(Batch b, int x, int y, KeyLabels.Glyph g) {
		int w = miniKeycapWidth(b.tr, g);
		roundRect(b, OVER, x, y, w, 9, 2, Theme.KEYCAP_BG, Theme.KEYCAP_BG);
		roundRing(b, OVER, x, y, w, 9, 2, Theme.KEYCAP_RING);
		String t = glyphText(g);
		if (glyphIcon(g)) {
			b.icon(ICONS2, g.icon(), x + 1, y, 7, 9, Theme.TEXT);
			if (!t.isEmpty()) b.text(t, x + 9, y + 1, Theme.TEXT, false);
		} else if (!t.isEmpty()) {
			b.text(t, x + (w - b.tr.getWidth(t) + 1) / 2, y + 1, Theme.TEXT, false);
		}
		return w;
	}

	// ------------------------------------------------------------------ iconos y texto

	/** Un icono a su tamaño natural (12×12; candado 8×8; ratones 7×9). */
	public static void icon(Batch b, int phase, String name, int x, int y, int argb) {
		b.icon(phase, name, x, y, Icons.width(name), Icons.height(name), argb);
	}

	/** Texto recortado con «…» a maxW; devuelve el ancho dibujado. */
	public static int text(Batch b, String s, int x, int y, int maxW, int argb, boolean shadow) {
		String t = Txt.ellipsize(b.tr, s, maxW);
		if (t.isEmpty()) return 0;
		b.text(t, x, y, argb, shadow);
		return b.tr.getWidth(t);
	}

	/** Texto a escala entera (×2, ×3), con su propia matriz. */
	public static void textScaled(Batch b, String s, int x, int y, int argb, boolean shadow, int scale) {
		b.textScaled(s, x, y, argb, shadow, Math.max(1, scale));
	}

	// ------------------------------------------------------------------ dibujo inmediato (pantallas)

	/** Una tecla dibujada al momento (pantallas de Ajustes y Teclas). 11 de alto; devuelve el ancho. */
	public static int drawKeycapNow(DrawContext ctx, TextRenderer tr, int x, int y, KeyLabels.Glyph g, int ringArgb) {
		Theme.refresh();
		Batch b = new Batch(tr);
		int w = keycap(b, x, y, g, ringArgb);
		b.flush(ctx);
		return w;
	}

	/** Un icono dibujado al momento: size × size (los ratones siempre 7×9 y el candado 8×8). */
	public static void drawIconNow(DrawContext ctx, String icon, int x, int y, int size, int argb) {
		if (icon == null || icon.isEmpty() || (argb >>> 24) == 0) return;
		boolean fixed = Icons.mouse(icon) || "candado".equals(icon);
		int w = fixed ? Icons.width(icon) : size, h = fixed ? Icons.height(icon) : size;
		RenderSystem.enableBlend();
		RenderSystem.defaultBlendFunc();
		try {
			ctx.setShaderColor(((argb >> 16) & 0xFF) / 255f, ((argb >> 8) & 0xFF) / 255f, (argb & 0xFF) / 255f, (argb >>> 24) / 255f);
			ctx.drawGuiTexture(Icons.id(icon), x, y, w, h);
		} finally {
			ctx.setShaderColor(1f, 1f, 1f, 1f);
			RenderSystem.disableBlend();
		}
	}

	// ------------------------------------------------------------------ lote por fases

	private record Fill(int x1, int y1, int x2, int y2, int top, int bottom) {}

	private record IconOp(String name, int x, int y, int w, int h, int argb) {}

	private record TextOp(String s, float x, float y, int argb, boolean shadow, float scale) {}

	/** Las operaciones de un fotograma, por fases. alpha multiplica todo lo que se encola (fundidos de módulo). */
	public static final class Batch {
		public final TextRenderer tr;
		public float alpha = 1f;
		private final List<Fill> back = new ArrayList<>(), over = new ArrayList<>();
		private final List<IconOp> icons = new ArrayList<>(), icons2 = new ArrayList<>();
		private final List<TextOp> text = new ArrayList<>();

		public Batch(TextRenderer tr) {
			this.tr = tr;
		}

		private int a(int argb) {
			return alpha >= 1f ? argb : Theme.mulAlpha(argb, alpha);
		}

		private List<Fill> fills(int phase) {
			return phase == BACK ? back : over;
		}

		public void fill(int phase, int x1, int y1, int x2, int y2, int argb) {
			if (x2 <= x1 || y2 <= y1) return;
			int c = a(argb);
			if ((c >>> 24) == 0) return;
			fills(phase).add(new Fill(x1, y1, x2, y2, c, c));
		}

		/** Degradado vertical. */
		public void gradient(int phase, int x1, int y1, int x2, int y2, int top, int bottom) {
			if (x2 <= x1 || y2 <= y1) return;
			int t = a(top), d = a(bottom);
			if ((t >>> 24) == 0 && (d >>> 24) == 0) return;
			fills(phase).add(new Fill(x1, y1, x2, y2, t, d));
		}

		/** Degradado horizontal hecho de tiras de step unidades. */
		public void hgradient(int phase, int x1, int y1, int x2, int y2, int left, int right, int step) {
			int w = x2 - x1;
			if (w <= 0 || y2 <= y1) return;
			step = Math.max(1, step);
			for (int x = x1; x < x2; x += step) {
				int e = Math.min(x2, x + step);
				fill(phase, x, y1, e, y2, Theme.lerp(left, right, ((x + e) * 0.5f - x1) / w));
			}
		}

		public void icon(int phase, String name, int x, int y, int w, int h, int argb) {
			if (name == null || name.isEmpty()) return;
			int c = a(argb);
			if ((c >>> 24) == 0) return;
			(phase == ICONS2 ? icons2 : icons).add(new IconOp(name, x, y, w, h, c));
		}

		public void text(String s, int x, int y, int argb, boolean shadow) {
			if (s == null || s.isEmpty()) return;
			int c = a(argb);
			// el texto con alfa < 4 Minecraft lo pinta opaco
			if ((c >>> 24) < 4) return;
			text.add(new TextOp(s, x, y, c, shadow, 1f));
		}

		public void textScaled(String s, float x, float y, int argb, boolean shadow, float scale) {
			if (s == null || s.isEmpty() || scale <= 0f) return;
			int c = a(argb);
			if ((c >>> 24) < 4) return;
			text.add(new TextOp(s, x, y, c, shadow, scale));
		}

		public boolean isEmpty() {
			return back.isEmpty() && over.isEmpty() && icons.isEmpty() && icons2.isEmpty() && text.isEmpty();
		}

		public void reset() {
			back.clear();
			over.clear();
			icons.clear();
			icons2.clear();
			text.clear();
			alpha = 1f;
		}

		/** Pinta las cinco fases en orden y vacía el lote. */
		@SuppressWarnings("deprecation")
		public void flush(DrawContext ctx) {
			try {
				if (!back.isEmpty()) ctx.draw(() -> drawFills(ctx, back));
				drawIcons(ctx, icons);
				if (!over.isEmpty()) ctx.draw(() -> drawFills(ctx, over));
				drawIcons(ctx, icons2);
				if (!text.isEmpty()) ctx.draw(() -> drawTexts(ctx, tr, text));
			} finally {
				reset();
			}
		}
	}

	private static void drawFills(DrawContext ctx, List<Fill> list) {
		for (Fill f : list) {
			if (f.top() == f.bottom()) ctx.fill(f.x1(), f.y1(), f.x2(), f.y2(), f.top());
			else ctx.fillGradient(f.x1(), f.y1(), f.x2(), f.y2(), f.top(), f.bottom());
		}
	}

	private static void drawIcons(DrawContext ctx, List<IconOp> list) {
		if (list.isEmpty()) return;
		RenderSystem.enableBlend();
		RenderSystem.defaultBlendFunc();
		try {
			for (IconOp o : list) {
				int c = o.argb();
				ctx.setShaderColor(((c >> 16) & 0xFF) / 255f, ((c >> 8) & 0xFF) / 255f, (c & 0xFF) / 255f, (c >>> 24) / 255f);
				ctx.drawGuiTexture(Icons.id(o.name()), o.x(), o.y(), o.w(), o.h());
			}
		} finally {
			ctx.setShaderColor(1f, 1f, 1f, 1f);
			RenderSystem.disableBlend();
		}
	}

	private static void drawTexts(DrawContext ctx, TextRenderer tr, List<TextOp> list) {
		MatrixStack m = ctx.getMatrices();
		for (TextOp t : list) {
			if (t.scale() == 1f) {
				ctx.drawText(tr, t.s(), Math.round(t.x()), Math.round(t.y()), t.argb(), t.shadow());
				continue;
			}
			m.push();
			try {
				m.translate(t.x(), t.y(), 0f);
				m.scale(t.scale(), t.scale(), 1f);
				ctx.drawText(tr, t.s(), 0, 0, t.argb(), t.shadow());
			} finally {
				m.pop();
			}
		}
	}

	// ------------------------------------------------------------------ líneas con teclas

	/**
	 * Una línea de 12 de alto con teclas, «+», iconos y trozos de texto: [ROOM]+[rueda] más grande o más pequeño.
	 * Las teclas van arriba (11 + 1 de profundidad) y el texto 2 más abajo.
	 */
	public static final class Line {
		private record Seg(String s, int argb) {}

		private record Key(KeyLabels.Glyph g, int ring) {}

		private record Ico(String name, int argb) {}

		private record Gap(int w) {}

		private static final Object PLUS = new Object();

		private final List<Object> parts = new ArrayList<>();

		public Line text(String s, int argb) {
			if (s != null && !s.isEmpty()) parts.add(new Seg(s, argb));
			return this;
		}

		public Line key(KeyLabels.Glyph g) {
			parts.add(new Key(g, 0));
			return this;
		}

		public Line key(KeyLabels.Glyph g, int ring) {
			parts.add(new Key(g, ring));
			return this;
		}

		public Line plus() {
			parts.add(PLUS);
			return this;
		}

		public Line icon(String name, int argb) {
			parts.add(new Ico(name, argb));
			return this;
		}

		public Line gap(int w) {
			parts.add(new Gap(w));
			return this;
		}

		/** Texto de acción: lo que va antes de «: » en TEXT2, lo de después en TEXT; los « · » en TEXT3. */
		public Line action(String s) {
			String[] bits = s.split(" · ");
			for (int i = 0; i < bits.length; i++) {
				if (i > 0) text(" · ", Theme.TEXT3);
				String bit = bits[i];
				int colon = bit.indexOf(": ");
				if (colon >= 0) {
					text(bit.substring(0, colon + 2), Theme.TEXT2);
					text(bit.substring(colon + 2), Theme.TEXT);
				} else {
					text(bit, Theme.TEXT);
				}
			}
			return this;
		}

		public boolean isEmpty() {
			return parts.isEmpty();
		}

		private static boolean boxy(Object o) {
			return o instanceof Key || o instanceof Ico || o == PLUS;
		}

		/** Hueco entre dos piezas seguidas: 2 alrededor de «+» y entre teclas, 3 entre tecla o icono y texto. */
		private static int spacing(Object prev, Object cur) {
			if (prev == null || prev instanceof Gap || cur instanceof Gap) return 0;
			if (prev == PLUS || cur == PLUS) return 2;
			if (prev instanceof Key && cur instanceof Key) return 2;
			if (boxy(prev) || boxy(cur)) return 3;
			return 0;
		}

		private static int partWidth(TextRenderer tr, Object o) {
			if (o instanceof Seg s) return tr.getWidth(s.s());
			if (o instanceof Key k) return keycapWidth(tr, k.g());
			if (o instanceof Ico i) return Icons.width(i.name());
			if (o instanceof Gap g) return g.w();
			return tr.getWidth("+");
		}

		public int width(TextRenderer tr) {
			int w = 0;
			Object prev = null;
			for (Object o : parts) {
				w += spacing(prev, o) + partWidth(tr, o);
				prev = o;
			}
			return w;
		}

		/** Dibuja la línea con la parte de arriba en y; si no cabe en maxW, se corta con «…». Devuelve el ancho. */
		public int draw(Batch b, int x, int y, int maxW) {
			TextRenderer tr = b.tr;
			boolean fits = width(tr) <= maxW;
			int ew = tr.getWidth(Txt.ELLIPSIS);
			int cx = x;
			Object prev = null;
			for (int n = 0; n < parts.size(); n++) {
				Object o = parts.get(n);
				int sp = spacing(prev, o);
				int pw = partWidth(tr, o);
				int room = x + maxW - (cx + sp);
				if (!fits && pw > room - ew) {
					if (o instanceof Seg s) {
						String t;
						if (n == parts.size() - 1) {
							t = Txt.ellipsize(tr, s.s(), room);
						} else {
							String cut = room - ew > 0 ? tr.trimToWidth(s.s(), room - ew).stripTrailing() : "";
							t = cut.isEmpty() ? (room >= ew ? Txt.ELLIPSIS : "") : cut + Txt.ELLIPSIS;
						}
						if (!t.isEmpty()) {
							b.text(t, cx + sp, y + 2, s.argb(), false);
							cx += sp + tr.getWidth(t);
						}
					} else if (room >= ew) {
						b.text(Txt.ELLIPSIS, cx + sp, y + 2, Theme.TEXT3, false);
						cx += sp + ew;
					}
					return cx - x;
				}
				cx += sp;
				if (o instanceof Seg s) {
					b.text(s.s(), cx, y + 2, s.argb(), false);
				} else if (o instanceof Key k) {
					keycap(b, cx, y, k.g(), k.ring());
				} else if (o instanceof Ico i) {
					b.icon(ICONS, i.name(), cx, y, Icons.width(i.name()), Icons.height(i.name()), i.argb());
				} else if (o == PLUS) {
					b.text("+", cx, y + 2, Theme.TEXT3, false);
				}
				cx += pw;
				prev = o;
			}
			return cx - x;
		}
	}
}
