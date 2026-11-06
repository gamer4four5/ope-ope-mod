package dev.opeope.client.hud;

import dev.opeope.client.config.OpeSettings;
import dev.opeope.client.mixin.BossBarHudAccessor;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.hud.ChatHud;
import net.minecraft.client.option.AttackIndicator;
import net.minecraft.client.util.Window;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.util.Arm;

import java.util.ArrayList;
import java.util.List;

/**
 * El lienzo del HUD de este fotograma (spec §3.1) y las zonas de Minecraft que no hay que pisar (§3.6), todo en
 * unidades del lienzo: una unidad son px píxeles; las unidades de Minecraft (escala de la interfaz) se pasan con f.
 */
public final class HudCanvas {
	/** Escala de la interfaz de Minecraft, píxeles por unidad del lienzo, márgenes y centro. */
	public int gui, px, W, H, M, cx, cy;
	/** z: escala de la matriz (px / gui). f: unidades de Minecraft → unidades del lienzo (gui / px). */
	public float z, f;
	public long now;
	public TextRenderer tr;

	public Rect hotbar, health, actionBar, effBen, effHarm, boss, subtitles, chat;
	public int effectsBottom, bossBottom, bossCount, chatTop, chatRight;
	public boolean survival, subtitlesOn, f3;

	public static HudCanvas compute(MinecraftClient mc) {
		HudCanvas c = new HudCanvas();
		Window win = mc.getWindow();
		int fbW = Math.max(1, win.getFramebufferWidth()), fbH = Math.max(1, win.getFramebufferHeight());
		c.gui = Math.max(1, (int) win.getScaleFactor());
		int t = Math.max(-1, Math.min(1, OpeSettings.get().tamano));
		c.px = Math.max(1, c.gui + t);
		if (t == 1 && (fbW / c.px < 400 || fbH / c.px < 225)) c.px = c.gui;
		c.z = c.px / (float) c.gui;
		c.f = c.gui / (float) c.px;
		c.W = (int) Math.ceil(fbW / (double) c.px);
		c.H = (int) Math.ceil(fbH / (double) c.px);
		c.M = c.W >= 560 ? 6 : 4;
		c.cx = c.W / 2;
		c.cy = c.H / 2;
		c.now = System.currentTimeMillis();
		c.tr = mc.textRenderer;
		c.zones(mc, win);
		return c;
	}

	private void zones(MinecraftClient mc, Window win) {
		int sw = win.getScaledWidth(), sh = win.getScaledHeight();
		PlayerEntity p = mc.player;
		f3 = mc.inGameHud.getDebugHud().shouldShowDebugHud();

		// Barra rápida, con la ranura de la otra mano y el indicador de ataque
		Arm main = p != null ? p.getMainArm() : Arm.RIGHT;
		boolean offhand = p != null && !p.getOffHandStack().isEmpty();
		boolean indicator = mc.options.getAttackIndicator().getValue() == AttackIndicator.HOTBAR;
		int hl = sw / 2 - 91 - (main == Arm.RIGHT && offhand ? 29 : 0) - (indicator && main == Arm.LEFT ? 24 : 0);
		int hr = sw / 2 + 91 + (main == Arm.LEFT && offhand ? 29 : 0) + (indicator && main == Arm.RIGHT ? 24 : 0);
		hotbar = box(hl, sh - 23, hr, sh);

		// Vida, comida, armadura y experiencia
		survival = p != null && !p.isCreative() && !p.isSpectator();
		health = survival ? box(sw / 2 - 91, sh - 69, sw / 2 + 91, sh - 22) : null;
		actionBar = box(sw / 2 - 120, sh - 72, sw / 2 + 120, sh - 60);

		// Efectos (arriba a la derecha): beneficiosos en la primera fila, perjudiciales en la segunda
		int nb = 0, nh = 0;
		if (p != null) {
			for (StatusEffectInstance e : p.getStatusEffects()) {
				if (!e.shouldShowIcon()) continue;
				if (e.getEffectType().value().isBeneficial()) nb++;
				else nh++;
			}
		}
		effBen = nb > 0 ? box(sw - 25 * nb, 1, sw, 26) : null;
		effHarm = nh > 0 ? box(sw - 25 * nh, 27, sw, 52) : null;
		int eb = nh > 0 ? 52 : (nb > 0 ? 26 : 0);
		effectsBottom = eb > 0 ? (int) Math.ceil(eb * f) : 0;

		// Barras de jefe
		int count = 0;
		try {
			count = ((BossBarHudAccessor) mc.inGameHud.getBossBarHud()).getOpeopeBossBars().size();
		} catch (RuntimeException e) {
			count = 0;
		}
		int n = count > 0 ? Math.min(count, Math.max(1, Math.floorDiv(sh / 3 - 12, 19) + 1)) : 0;
		bossCount = n;
		int bb = n > 0 ? 19 * n + 2 : 0;
		boss = n > 0 ? box(sw / 2 - 91, 0, sw / 2 + 91, bb) : null;
		bossBottom = n > 0 ? (int) Math.ceil(bb * f) : 0;

		// Subtítulos
		subtitlesOn = mc.options.getShowSubtitles().getValue();
		subtitles = subtitlesOn ? box(sw - 150, sh - 95, sw, sh - 25) : null;

		// Chat
		ChatHud chatHud = mc.inGameHud.getChatHud();
		double s = chatHud.getChatScale();
		double right = chatHud.getWidth() * s + 12;
		double top = sh - (48 + chatHud.getHeight() * s);
		chat = box(0, top, right, sh - 40);
		chatTop = chat.y();
		chatRight = chat.right();
	}

	/** Un rectángulo en unidades de Minecraft (izq., arriba, der., abajo) pasado al lienzo, redondeado hacia fuera. */
	private Rect box(double l, double t, double r, double b) {
		int x1 = (int) Math.floor(l * f), y1 = (int) Math.floor(t * f);
		int x2 = (int) Math.ceil(r * f), y2 = (int) Math.ceil(b * f);
		return new Rect(x1, y1, Math.max(0, x2 - x1), Math.max(0, y2 - y1));
	}

	/** Unidades de Minecraft → lienzo. */
	public int v(double vanilla) {
		return (int) Math.round(vanilla * f);
	}

	/** Borde derecho de la barra rápida (con la otra mano y el indicador). */
	public int hotbarRight() {
		return hotbar.right();
	}

	/** El borde izquierdo de los efectos que caen en la franja [y1, y2), o W si no hay. */
	public int effectsLeft(int y1, int y2) {
		int left = W;
		if (effBen != null && effBen.overlapsY(y1, y2)) left = Math.min(left, effBen.x());
		if (effHarm != null && effHarm.overlapsY(y1, y2)) left = Math.min(left, effHarm.x());
		return left;
	}

	/** Límite derecho de la columna izquierda (píldora, chips): antes de las barras de jefe si las hay. */
	public int leftMax() {
		int max = boss != null ? cx - v(91) - 4 : M + Math.min(Math.round(0.45f * W), 220);
		return Math.min(max, W - M);
	}

	/** Un rectángulo del lienzo. */
	public record Rect(int x, int y, int w, int h) {
		public int right() {
			return x + w;
		}

		public int bottom() {
			return y + h;
		}

		public boolean empty() {
			return w <= 0 || h <= 0;
		}

		public boolean overlaps(Rect o) {
			return o != null && !empty() && !o.empty() && x < o.x + o.w && o.x < x + w && y < o.y + o.h && o.y < y + h;
		}

		public boolean overlapsX(int x1, int x2) {
			return x < x2 && x1 < x + w;
		}

		public boolean overlapsY(int y1, int y2) {
			return y < y2 && y1 < y + h;
		}

		public boolean inside(int width, int height) {
			return x >= 0 && y >= 0 && x + w <= width && y + h <= height;
		}

		public Rect shift(int dx, int dy) {
			return new Rect(x + dx, y + dy, w, h);
		}

		public String fmt() {
			return "(" + x + "," + y + "," + w + "," + h + ")";
		}

		public static Rect ltrb(int l, int t, int r, int b) {
			return new Rect(l, t, Math.max(0, r - l), Math.max(0, b - t));
		}
	}

	/** Lo que un módulo dejó en el lienzo, para el validador (persistente = siempre a la vista). */
	public record Placed(String name, boolean persistent, Rect r) {}

	/** Lo que el paso de colocación va sabiendo, en el orden barra → medidor → píldora → … → aviso grande. */
	public static final class LayoutState {
		public boolean stripOpen, bisturi, centreOnly;
		public Rect bar, meter, pill, chips, strip, bisturiPanel, card, scan, toasts, callout, taktChip;
		public boolean barVertical;
		public int barTile, barRows, barCandidate;
		/** Lo más bajo de la columna izquierda (píldora, chips, Scan) y su borde derecho. */
		public int leftBottom, leftRight;
		public final List<Placed> placed = new ArrayList<>();

		public void place(String name, boolean persistent, Rect r) {
			if (r != null && !r.empty()) placed.add(new Placed(name, persistent, r));
		}

		/** La parte de arriba de la barra si está a la vista y cae en [x1, x2); si no, Integer.MAX_VALUE. */
		public int barTopIn(int x1, int x2) {
			return bar != null && bar.overlapsX(x1, x2) ? bar.y() : Integer.MAX_VALUE;
		}
	}
}
