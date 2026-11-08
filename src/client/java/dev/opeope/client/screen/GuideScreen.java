package dev.opeope.client.screen;

import dev.opeope.client.ClientState;
import dev.opeope.client.config.OpeSettings;
import dev.opeope.client.hud.Ui;
import dev.opeope.client.input.KeyLabels;
import dev.opeope.client.input.Keys;
import dev.opeope.client.input.OpeKeyBindings;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.tooltip.Tooltip;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.option.GameOptions;
import net.minecraft.client.util.InputUtil;
import net.minecraft.text.OrderedText;
import net.minecraft.text.Text;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;

/**
 * El libro de la fruta (H): todo lo que hace cada tecla, con las teclas de verdad dibujadas y según los modos de
 * Ajustes. Se baja con la rueda, las flechas, Re Pág / Av Pág e Inicio / Fin. Arriba, «Ajustes» y «Teclas».
 */
public class GuideScreen extends Screen {
	private static final int TEXT = 0xFFEEF7FA, TEXT2 = 0xFFA9C2CC, TEXT3 = 0xFF6F8792, ROOM = 0xFF8FE3FF;
	private static final int TITLE = 0, KEYS = 1, NOTE = 2;
	private static final int TOP = 30, BOTTOM_MARGIN = 8;

	/** Una fila: un título, una nota o teclas (KeyLabels.Glyph o palabras sueltas como «+») con lo que hacen. */
	private record Row(int kind, Object[] keys, String text) {}

	private record Laid(Row row, int y, int h, List<OrderedText> lines, boolean keysAbove) {}

	private final List<Row> rows = new ArrayList<>();
	private final List<Laid> laid = new ArrayList<>();
	private double scroll;
	private int contentH, keyCol, x0, cw;

	public GuideScreen() {
		super(Text.literal("Ope Ope no Mi"));
	}

	@Override
	protected void init() {
		int bw = 60;
		addDrawableChild(ButtonWidget.builder(Text.literal("Ajustes"), b -> this.client.setScreen(new SettingsScreen(this)))
			.dimensions(this.width - 6 - bw * 2 - 4, 5, bw, 20)
			.tooltip(Tooltip.of(Text.literal("El HUD, la accesibilidad y los modos de las teclas.")))
			.build());
		addDrawableChild(ButtonWidget.builder(Text.literal("Teclas"), b -> this.client.setScreen(new KeysScreen(this)))
			.dimensions(this.width - 6 - bw, 5, bw, 20)
			.tooltip(Tooltip.of(Text.literal("Cambia las teclas de la fruta.")))
			.build());
		build();
		layout();
	}

	// ------------------------------------------------------------------ contenido

	private void title(String s) {
		rows.add(new Row(TITLE, new Object[0], s));
	}

	private void note(String s) {
		rows.add(new Row(NOTE, new Object[0], s));
	}

	private void row(String what, Object... keys) {
		rows.add(new Row(KEYS, keys, what));
	}

	private static KeyLabels.Glyph g(Keys k) {
		return KeyLabels.of(k);
	}

	private static KeyLabels.Glyph g(String translationKey) {
		return KeyLabels.of(InputUtil.fromTranslationKey(translationKey));
	}

	/** El nombre corto de una tecla para meterlo en una frase: «Q», «Tab», «Botón lateral 4». */
	private static String k(Keys key) {
		KeyLabels.Glyph gl = KeyLabels.of(key);
		return gl.icon() == null ? gl.text() : KeyLabels.text(key.key);
	}

	private void build() {
		rows.clear();
		OpeSettings s = OpeSettings.get();
		GameOptions o = this.client.options;
		KeyLabels.Glyph q = g(Keys.ROOM), alt = g(Keys.SHAMBLES), r = g(Keys.TAKT), tab = g(Keys.ROW2);
		KeyLabels.Glyph clic = KeyLabels.mouseLeft(), der = KeyLabels.mouseRight(), rueda = KeyLabels.wheel();
		KeyLabels.Glyph may = KeyLabels.of(KeyBindingHelper.getBoundKeyOf(o.sneakKey));
		boolean roomAlt = s.modoRoom == OpeSettings.ModoRoom.ALTERNAR;
		boolean hasFruit = ClientState.hasFruit();

		if (!hasFruit) {
			title("Conseguir la fruta");
			note("Aún no tienes la fruta. A veces la suelta el Guardián Anciano o el Warden. En creativo, en la pestaña Ope Ope no Mi. Con trucos: /opeope fruta.");
		}

		title("Antes de nada");
		note("La fruta es siempre tuya: funciona con la mano vacía (Fruta), con la Kikoku (Espada) o con cualquier cosa en la mano.");
		note("Agachado, los clics vuelven a ser los de Minecraft. En el agua la fruta no funciona y te hundes.");
		note("Las teclas se cambian en «Teclas» (arriba) o en Opciones → Controles → Ope Ope no Mi.");

		title("ROOM");
		if (!roomAlt) {
			row("mantener: lo abres desde el cuerpo; crece cada vez más rápido, hasta 160 bloques; al soltar se fija", q);
			row("lanzarlo: vuela a la mira y florece allí con su tamaño", q, "+", clic);
			row("agrandarlo o encogerlo", q, "+", rueda);
			row("traerlo hasta ti", q, "+", der);
		} else {
			row("toque sin ROOM: lo abres y sigue creciendo; otro toque lo fija (mantener y soltar también vale)", q);
			row("toque con ROOM: modo ROOM, sin mantener nada; otro toque (o Esc) sale", q);
			row("en modo ROOM: lanzarlo a la mira", clic);
			row("en modo ROOM: agrandarlo o encogerlo", rueda);
			row("en modo ROOM: traerlo hasta ti", der);
		}
		if (roomAlt || s.quitarRoom == OpeSettings.Quitar.DOBLE_TOQUE) row("doble toque: quitarlo", q, q);
		else row("toque: quitarlo", q);
		note("Desde fuera también funciona: si miras dentro, Amputate, Shambles, Takt y el suelo actúan dentro.");

		title("Cortes (mano vacía dentro del ROOM)");
		row("Amputate: con el clic pulsado la cámara se queda quieta; arrastra el ratón y trazas el tajo (una línea marca lo que vas a cortar); al soltar, corta. Un clic sin arrastrar repite el último tajo", clic);
		if (s.modoBisturi == OpeSettings.Modo2.ALTERNAR) {
			row("toque: Bisturí; el tiempo va al 12 % (un jugador) y el plano pasa por donde apuntas; otro clic derecho corta y Esc lo cancela", der);
		} else {
			row("mantener: Bisturí; el tiempo va al 12 % (un jugador) y el plano pasa por donde apuntas; al soltar, corta", der);
		}
		row("con el Bisturí: girar, ladear o mover el plano", rueda, "·", may, "+", rueda, "·", g("key.keyboard.left.control"), "+", rueda);
		row("con el Bisturí: corta ya (hasta 16); lo cortado se queda quieto y cae todo a la vez al final", clic);
		row("patrón: un tajo, rodajas, cruz o dados", tab, "+", g(Keys.T_PATTERN));
		row("alcance: todo lo que cruza el ROOM o solo el objetivo", tab, "+", g(Keys.T_REACH));
		row("suspender: lo cortado queda flotando hasta que lo pulses otra vez", tab, "+", g(Keys.T_SUSPEND));
		note("Lo que queda encima del corte resbala por el plano hacia donde fue el tajo; el borde del ROOM también corta. Si está encajado, se separa y flota unos segundos (agárralo con Takt); una cuña muy grande se queda en su sitio. Los cortes en el suelo quedan marcados tres minutos y Takt arranca justo lo que encierran. Un mob cortado no pierde vida: queda en piezas vivas.");
		note("Con la Kikoku dentro del ROOM, cada tajo de la espada corta de verdad lo que alcanza el filo.");

		title("Shambles");
		row("con el aire: apareces donde apuntas dentro del ROOM", alt);
		row("marca algo; luego lo mismo (te cambias), otro objetivo (se cambian) o un punto vacío", alt, alt);
		row("agachado: cambia bloques de sitio (marca uno y luego el otro)", may, "+", alt);
		row("piedra marcador: lánzala y haz Shambles con ella", g(Keys.MARKER));
		row("Scan y luego robar lo que lleva en la mano", g(Keys.SCAN), "y", alt);

		title("Takt");
		row("toque: coger lo que miras (seres, objetos o piezas); sobre el suelo, arranca un trozo que sigue tus cortes", r);
		row("doble toque: levantar todo lo suelto alrededor del punto (no arranca suelo); justo después de un arranque, lo deshace", r, r);
		row("lanzar el de delante; mantener: ráfaga", clic);
		row("mantener y soltar: lo lanza todo junto", der);
		switch (s.modoR) {
			case ALTERNAR -> note("Las órdenes, con " + k(Keys.TAKT) + " mantenida. Si la sueltas sin pulsar nada, se quedan abiertas hasta otro toque de " + k(Keys.TAKT) + " (o Esc).");
			case UNA_TECLA -> note("Las órdenes, con " + k(Keys.TAKT) + " mantenida. Si la sueltas sin pulsar nada, la próxima tecla es una orden.");
			default -> note("Las órdenes, con " + k(Keys.TAKT) + " mantenida:");
		}
		row("órbita o a la mano", r, "+", g(Keys.R_ORBIT));
		row("aparcar", r, "+", g(Keys.R_PARK));
		row("posar: baja recto a su silueta y queda en bloques", r, "+", g(Keys.R_SETDOWN));
		row("soltar", r, "+", g(Keys.R_DROP));
		row("con algo en Takt: más cerca o más lejos; sin nada: el tamaño del trozo de suelo", r, "+", rueda);
		row("zona", r, "+", g(Keys.R_ZONE));
		row("lo cortado", r, "+", g(Keys.R_CUT));
		row("rejuntar", r, "+", g(Keys.R_GATHER));
		row("unir todo", r, "+", g(Keys.R_UNITE));
		row("lanzar todo", r, "+", der);

		title("Moldear el suelo con Takt");
		row("corta el suelo dentro del ROOM: cada corte queda marcado unos minutos con una línea tenue", clic);
		if (s.modoR == OpeSettings.ModoFila.MANTENER) {
			row("mantenida y mirando al suelo: se ve el trozo exacto; al soltarla, lo arrancas", r);
		} else {
			// Alternar y Una tecla: al soltar R las órdenes quedan abiertas; el toque que las cierra arranca el trozo
			row("mantenida y mirando al suelo: se ve el trozo exacto; al soltarla sigue a la vista y otro toque lo arranca", r);
		}
		row("el tamaño del trozo (de 3 a 18 bloques de ancho)", r, "+", rueda);
		row("toque: el trozo se agrieta, tiembla, se suelta y sube a tu mano", r);
		row("posar: baja recto a su silueta y queda en bloques", r, "+", g(Keys.R_SETDOWN));
		row("lanzarlo: abre un cráter y se queda clavado (no explota)", clic);
		row("unir todo: cada trozo vuelve a su hueco y se sella", r, "+", g(Keys.R_UNITE));
		note("Si tres cortes o más encierran una zona, Takt arranca justo esa forma, y su contorno late cuando la miras. Sin cortes, arranca un trozo redondo del tamaño elegido. En una cueva sigue el suelo de la cueva; en una pared o un techo arranca un pedazo tosco. Un doble toque justo después deshace el arranque.");

		title("Armas del entorno (con material en Takt)");
		row("lanza: atraviesa y ensarta al primero; " + k(Keys.TAKT) + " sobre ella la recupera", r, "+", g(Keys.R_FORGE1));
		row("hoja colosal: un tajo de lado a lado; dentro del ROOM corta como Amputate", r, "+", g(Keys.R_FORGE2));
		row("martillo: lo alzas y lo descargas; la onda tumba a todos", r, "+", g(Keys.R_FORGE3));
		row("estacas: suben en corona y llueven; la mitad busca a los enemigos", r, "+", g(Keys.R_FORGE4));
		row("muralla: queda de pie delante y para las flechas; clic, empujón", r, "+", g(Keys.R_FORGE5));

		title("Segunda fila");
		switch (s.modoTab) {
			case ALTERNAR -> row("toque: la segunda fila se queda abierta hasta otro toque (o Esc); arriba ves qué hace cada tecla", tab);
			case UNA_TECLA -> row("toque: la próxima tecla es de la segunda fila; arriba ves qué hace cada tecla", tab);
			default -> row("mantener: la segunda fila; arriba ves qué hace cada tecla", tab);
		}
		if (!InputUtil.UNKNOWN_KEY.equals(Keys.ROW2_MOUSE_A.key) || !InputUtil.UNKNOWN_KEY.equals(Keys.ROW2_MOUSE_B.key)) {
			row("también abren la segunda fila", g(Keys.ROW2_MOUSE_A), g(Keys.ROW2_MOUSE_B));
		}

		title("Unión");
		row("toque: modo Unión; clic en una pieza y clic en el destino: vuela y queda cosida", tab, "+", g(Keys.T_UNION));
		row("en modo Unión: reconstruir; la pieza vuelve a su sitio y se sella", der);
		if (s.modoCirugia == OpeSettings.Modo2.ALTERNAR) {
			row("mantener: Cirugía; te curas o curas a quien miras, y recompone lo cortado; sigue sola hasta otro " + k(Keys.ROW2) + " + " + k(Keys.T_UNION), tab, "+", g(Keys.T_UNION));
		} else {
			row("mantener: Cirugía; te curas o curas a quien miras, y recompone lo cortado", tab, "+", g(Keys.T_UNION));
		}
		row("con Takt: lo que llevas se cose a lo que toca", tab, "+", g(Keys.T_UNION));

		title("El suelo del ROOM");
		row("pilar (mantener: sigue subiendo y te sube)", tab, "+", g(Keys.T_PILLAR));
		row("muralla (mantener: sigue tu mirada como una ola)", tab, "+", g(Keys.T_WALL));
		row("sima (mantener: una zanja que sigue tu mirada)", tab, "+", g(Keys.T_CHASM));
		row("meseta (alza el suelo con todo lo que tiene encima)", tab, "+", g(Keys.T_PLATEAU));

		title("Técnicas");
		row("Radio Knife: corte eléctrico; lo cortado no se rearma en 5 s", g(Keys.RADIO_KNIFE));
		row("Injection Shot: embestida que dispara al clavarse", g(Keys.INJECTION));
		row("Gamma Knife: destruye por dentro, no mira la armadura", g(Keys.GAMMA));
		row("Scan: ves a todos a través de las paredes y lo que llevan", g(Keys.SCAN));
		row("Curtain: un velo delante que reduce el daño", g(Keys.CURTAIN));
		row("Mes: le sacas el corazón; " + k(Keys.RADIO_KNIFE) + " o clic derecho lo aprieta; " + k(Keys.ROW2) + " + " + k(Keys.T_MES) + " lo devuelve o lo suelta", tab, "+", g(Keys.T_MES));
		row("Counter Shock: descarga con las palmas", tab, "+", g(Keys.T_COUNTER));
		row("Sterben: la nodachi gira como una sierra", tab, "+", g(Keys.T_STERBEN));
		row("Cambio de almas: marca a uno y luego a otro", tab, "+", g(Keys.T_SOULS));
		row("Lluvia: llueven cortes sobre todo lo que vive en el ROOM", tab, "+", g(Keys.T_RAIN));
		note("Si pulsas una técnica que aún se enfría, su casilla tiembla y suena un «no» corto.");

		title("Despertar");
		note("Se gana venciendo al Wither o al Dragón del End con la fruta.");
		row("lo enciende y lo apaga", g(Keys.AWAKEN));
		row("K-ROOM", g(Keys.RADIO_KNIFE));
		row("mantener: Anesthesia", g(Keys.INJECTION));
		row("Shock Wille", g(Keys.GAMMA));
		row("mantener: R-ROOM; crece donde miras y al soltar estalla en cortes", g(Keys.SCAN));
		row("Puncture Wille", tab, "+", g(Keys.T_COUNTER));
		row("Silent", g(Keys.CURTAIN));

		title("Minecraft y la fruta");
		row("agachado, los clics son los de Minecraft (abrir, colocar, golpear)", may, "+", clic);
		row("tirar lo que llevas", may, "+", KeyLabels.of(KeyBindingHelper.getBoundKeyOf(o.dropKey)));
		row("cambiar de mano", may, "+", KeyLabels.of(KeyBindingHelper.getBoundKeyOf(o.swapHandsKey)));
		row("F3 y sus combinaciones siguen siendo de Minecraft", g("key.keyboard.f3"));
		row("el narrador", g("key.keyboard.left.control"), "+", g("key.keyboard.b"));
		if (s.listaJugadoresTab && Keys.ROW2.key.equals(KeyBindingHelper.getBoundKeyOf(o.playerListKey))) {
			row("en multijugador, mantenla sin pulsar nada más: la lista de jugadores", tab);
		}
		row("suelta lo que esté fijo (ROOM, filas, Bisturí, Cirugía) antes de abrir el menú", g("key.keyboard.escape"));
		row("este libro (se cambia en Controles)", KeyLabels.of(KeyBindingHelper.getBoundKeyOf(OpeKeyBindings.LIBRO)));
		InputUtil.Key ajustes = KeyBindingHelper.getBoundKeyOf(OpeKeyBindings.AJUSTES);
		if (!InputUtil.UNKNOWN_KEY.equals(ajustes)) row("los ajustes de la fruta", KeyLabels.of(ajustes));

		if (hasFruit) {
			title("Conseguir la fruta");
			note("A veces la suelta el Guardián Anciano o el Warden. En creativo, en la pestaña Ope Ope no Mi. Con trucos: /opeope fruta.");
		}
	}

	// ------------------------------------------------------------------ medidas

	private int capWidth(KeyLabels.Glyph gl) {
		TextRenderer tr = this.textRenderer;
		String t = gl.text() == null ? "" : gl.text();
		if (gl.icon() == null) return Math.max(11, tr.getWidth(t) + 6);
		return t.isEmpty() ? 11 : 13 + tr.getWidth(t);
	}

	private int partsWidth(Object[] parts) {
		int w = 0;
		for (Object p : parts) {
			if (p instanceof KeyLabels.Glyph gl) w += capWidth(gl) + 2;
			else w += this.textRenderer.getWidth(String.valueOf(p)) + 2;
		}
		return Math.max(0, w - 2);
	}

	private void layout() {
		laid.clear();
		TextRenderer tr = this.textRenderer;
		cw = Math.max(80, Math.min(this.width - 32, 460));
		x0 = (this.width - cw) / 2;
		int widest = 0;
		for (Row r : rows) if (r.kind() == KEYS) widest = Math.max(widest, partsWidth(r.keys()));
		keyCol = Math.min(widest, (int) (cw * 0.42f));
		int y = 0;
		for (Row r : rows) {
			switch (r.kind()) {
				case TITLE -> {
					if (!laid.isEmpty()) y += 8;
					laid.add(new Laid(r, y, 12, List.of(), false));
					y += 15;
				}
				case NOTE -> {
					List<OrderedText> lines = tr.wrapLines(Text.literal(r.text()), cw);
					laid.add(new Laid(r, y, lines.size() * 10, lines, false));
					y += lines.size() * 10 + 4;
				}
				default -> {
					boolean above = partsWidth(r.keys()) > keyCol;
					int tx = above ? 12 : keyCol + 8;
					List<OrderedText> lines = tr.wrapLines(Text.literal(r.text()), Math.max(40, cw - tx));
					int h = above ? 14 + lines.size() * 10 : Math.max(12, 2 + lines.size() * 10);
					laid.add(new Laid(r, y, h, lines, above));
					y += h + 3;
				}
			}
		}
		contentH = y;
		clampScroll();
	}

	private int viewHeight() {
		return Math.max(10, this.height - BOTTOM_MARGIN - TOP);
	}

	private int maxScroll() {
		return Math.max(0, contentH - viewHeight());
	}

	private void clampScroll() {
		scroll = Math.max(0, Math.min(maxScroll(), scroll));
	}

	// ------------------------------------------------------------------ entrada

	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
		scroll -= verticalAmount * 24;
		clampScroll();
		return true;
	}

	@Override
	public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
		int page = Math.max(20, viewHeight() - 20);
		switch (keyCode) {
			case GLFW.GLFW_KEY_UP -> scroll -= 12;
			case GLFW.GLFW_KEY_DOWN -> scroll += 12;
			case GLFW.GLFW_KEY_PAGE_UP -> scroll -= page;
			case GLFW.GLFW_KEY_PAGE_DOWN -> scroll += page;
			case GLFW.GLFW_KEY_HOME -> scroll = 0;
			case GLFW.GLFW_KEY_END -> scroll = maxScroll();
			default -> {
				if (OpeKeyBindings.LIBRO.matchesKey(keyCode, scanCode)) {
					close();   // la misma tecla que lo abre lo cierra
					return true;
				}
				return super.keyPressed(keyCode, scanCode, modifiers);
			}
		}
		clampScroll();
		return true;
	}

	// ------------------------------------------------------------------ dibujo

	private void drawParts(DrawContext ctx, Object[] parts, int x, int y) {
		int cx = x;
		for (Object p : parts) {
			if (p instanceof KeyLabels.Glyph gl) {
				cx += Ui.drawKeycapNow(ctx, this.textRenderer, cx, y, gl, 0) + 2;
			} else {
				String s = String.valueOf(p);
				ctx.drawText(this.textRenderer, s, cx, y + 2, TEXT3, false);
				cx += this.textRenderer.getWidth(s) + 2;
			}
		}
	}

	@Override
	public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
		super.render(ctx, mouseX, mouseY, delta);
		TextRenderer tr = this.textRenderer;
		ctx.drawTextWithShadow(tr, "Ope Ope no Mi · libro", x0, 11, ROOM);
		clampScroll();
		int top = TOP, bottom = this.height - BOTTOM_MARGIN;
		ctx.enableScissor(0, top, this.width, bottom);
		int base = top - (int) scroll;
		for (Laid l : laid) {
			int y = base + l.y();
			if (y + l.h() < top || y > bottom) continue;
			Row r = l.row();
			switch (r.kind()) {
				case TITLE -> {
					ctx.drawTextWithShadow(tr, r.text(), x0, y + 2, ROOM);
					int tw = tr.getWidth(r.text());
					if (x0 + tw + 8 < x0 + cw) ctx.fill(x0 + tw + 6, y + 6, x0 + cw, y + 7, 0x408FE3FF);
				}
				case NOTE -> {
					int ly = y;
					for (OrderedText line : l.lines()) {
						ctx.drawTextWithShadow(tr, line, x0, ly, TEXT2);
						ly += 10;
					}
				}
				default -> {
					drawParts(ctx, r.keys(), x0, y);
					int tx = l.keysAbove() ? x0 + 12 : x0 + keyCol + 8;
					int ly = l.keysAbove() ? y + 14 : y + 2;
					for (OrderedText line : l.lines()) {
						ctx.drawTextWithShadow(tr, line, tx, ly, TEXT);
						ly += 10;
					}
				}
			}
		}
		ctx.disableScissor();
		int viewH = bottom - top;
		if (contentH > viewH && maxScroll() > 0) {
			int sx = Math.min(x0 + cw + 6, this.width - 4);
			ctx.fill(sx, top, sx + 3, bottom, 0x30FFFFFF);
			int th = Math.max(16, viewH * viewH / contentH);
			int ty = top + (int) ((viewH - th) * (scroll / maxScroll()));
			ctx.fill(sx, ty, sx + 3, ty + th, 0xC08FE3FF);
		}
	}

	@Override
	public boolean shouldPause() {
		return true;
	}
}
