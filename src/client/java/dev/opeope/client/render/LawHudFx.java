package dev.opeope.client.render;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import dev.opeope.client.config.OpeSettings;
import dev.opeope.client.hud.Theme;
import dev.opeope.client.room.RoomFxClient;
import dev.opeope.net.Payloads.LawFx;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.render.BufferBuilder;
import net.minecraft.client.render.BufferRenderer;
import net.minecraft.client.render.GameRenderer;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.RenderTickCounter;
import net.minecraft.client.render.Tessellator;
import net.minecraft.client.render.VertexFormat;
import net.minecraft.client.render.VertexFormats;
import net.minecraft.client.util.math.MatrixStack;
import org.joml.Matrix4f;

import java.util.Random;

/**
 * La «presentación anime» de las técnicas de Law, pintada en el HUD al final de {@link LawFxClient#renderHud}:
 * <ul>
 * <li>el rótulo con el nombre de la técnica, como cuando Law la anuncia: una línea pequeña en japonés (kana/kanji)
 * encima del nombre en grande, con una franja oscura detrás (como el aviso del ROOM) y un subrayado celeste que entra
 * desde la izquierda;</li>
 * <li>el fotograma de impacto: un fotograma con los colores invertidos y las líneas de concentración del manga, cuñas
 * casi negras que van del borde de la pantalla hacia el golpe y dejan limpio el centro;</li>
 * <li>el tajo que cruza la pantalla: un trazo blanco con un halo celeste que la atraviesa y se apaga.</li>
 * </ul>
 * Orden de dibujo: líneas de concentración, tajo, rótulo y, lo último, la inversión (invierte todo lo que ya hay).
 * <p>
 * Ajustes: con «menos movimiento» no hay tajo, ni líneas, ni inversión, y el rótulo solo se funde (sin escala ni
 * deslizamiento); la inversión solo sale con todos los destellos (nivel 2) y como mucho dos veces por segundo; las
 * líneas con destellos suaves o todos (nivel 1 o 2); el rótulo solo con «Aviso grande del ROOM». Con F1 no se pinta nada.
 * Todo va en unidades escaladas del HUD, con el reloj de {@link RoomFxClient#time()} (se para en pausa).
 */
public final class LawHudFx {
	private LawHudFx() {}

	// los colores de Law (los mismos que en LawFxClient)
	private static final int ROOM = 0x8FE3FF, CORE = 0xEFFCFF, WHITE = 0xFFFFFF;
	/** La tinta de las líneas de concentración: casi negra, apenas azulada. */
	private static final int INK = 0x07090E;
	/** La franja oscura detrás del rótulo. */
	private static final int SHADE = 0x02060C;

	// ------------------------------------------------------------------ el rótulo

	/** Entra en 120 ms, se queda 0,9 s y se apaga en 0,35 s. */
	private static final float B_IN = 0.12f, B_HOLD = 0.9f, B_OUT = 0.35f, B_LIFE = B_IN + B_HOLD + B_OUT;
	/** El subrayado empieza a entrar a los 40 ms y tarda 260 ms en cruzar. */
	private static final float B_WIPE_DELAY = 0.04f, B_WIPE = 0.26f;
	private static final float NAME_SCALE = 2.6f, KANA_SCALE = 1.2f;
	/** El mismo rótulo otra vez antes de 2,5 s no se vuelve a anunciar. */
	private static final double REPEAT_SECONDS = 2.5;

	private static String bKana = "", bName = "";
	private static double bStart = -100;
	private static String lastKana = "", lastName = "";
	private static double lastAt = -100;

	// ------------------------------------------------------------------ el fotograma invertido

	private static final float INVERT_LIFE = 0.06f;
	/** Como mucho una inversión cada medio segundo: varias seguidas serían un parpadeo. */
	private static final double INVERT_GAP = 0.5;
	private static double invertStart = -100;

	// ------------------------------------------------------------------ las líneas de concentración

	private static final float FOCUS_LIFE = 0.28f;
	private static final int FOCUS_MAX = 48;
	private static final float[] F_COS = new float[FOCUS_MAX], F_SIN = new float[FOCUS_MAX];
	/** Medio ancho de la cuña en el borde (para una pantalla de 270 de lado corto; se escala con ella). */
	private static final float[] F_HALF = new float[FOCUS_MAX];
	/** Dónde acaba la punta, en fracción del lado corto de la pantalla medida desde el golpe. */
	private static final float[] F_INNER = new float[FOCUS_MAX];
	private static final float[] F_ALPHA = new float[FOCUS_MAX];
	private static int focusCount;
	private static double focusStart = -100;
	private static float focusStrength, focusX = 0.5f, focusY = 0.5f;

	// ------------------------------------------------------------------ el tajo

	/** Cruza la pantalla en 0,08 s y se apaga en 0,25 s. */
	private static final float SLASH_WIPE = 0.08f, SLASH_FADE = 0.25f;
	private static double slashStart = -100;
	private static float slashAngle, slashStrength;

	private static final Random RNG = new Random();

	// ------------------------------------------------------------------ órdenes

	/**
	 * El rótulo de una técnica: kind es un tipo de {@link LawFx}; variant es el tajo en SWORD (SWORD_IAI…SWORD_THRUSTS)
	 * y, en AWAKEN, 1 al despertar. Lo que no tiene nombre propio (los golpes sueltos, los reenvíos) no saca nada.
	 */
	public static void banner(int kind, int variant) {
		String[] t = switch (kind) {
			case LawFx.RADIO_KNIFE -> new String[] {"ラジオナイフ", "RADIO KNIFE"};
			case LawFx.INJECTION_DASH -> new String[] {"注射ショット", "INJECTION SHOT"};
			case LawFx.GAMMA -> new String[] {"ガンマナイフ", "GAMMA KNIFE"};
			case LawFx.CURTAIN -> new String[] {"抗菌武装", "CURTAIN"};
			case LawFx.MES -> new String[] {"メス", "MES"};
			case LawFx.COUNTER_SHOCK -> new String[] {"カウンターショック", "COUNTER SHOCK"};
			case LawFx.SCAN -> new String[] {"スキャン", "SCAN"};
			case LawFx.SOULS -> new String[] {"人格移植手術", "SHAMBLES"};
			case LawFx.AWAKEN -> variant == 1 ? new String[] {"覚醒", "DESPERTAR"} : null;
			case LawFx.KROOM -> new String[] {"クローム", "K-ROOM"};
			case LawFx.ANESTHESIA -> new String[] {"麻酔", "ANESTHESIA"};
			case LawFx.SHOCK_WILLE -> new String[] {"衝撃波動", "SHOCK WILLE"};
			case LawFx.PUNCTURE -> new String[] {"穿刺波動", "PUNCTURE WILLE"};
			case LawFx.SILENT -> new String[] {"サイレント", "SILENT"};
			case LawFx.SILENT_TARGET -> new String[] {"ルーム・サイレント", "R-ROOM · SILENT"};
			case LawFx.SCAN_STEAL -> new String[] {"シャンブルズ", "SHAMBLES"};
			case LawFx.EXTRACT -> new String[] {"手術", "CIRUGÍA"};
			case LawFx.RROOM_CAST -> new String[] {"ルーム", "R-ROOM"};
			case LawFx.STERBEN -> new String[] {"死の刀", "STERBEN"};
			case LawFx.SWORD -> switch (variant) {
				case LawFx.SWORD_IAI -> new String[] {"居合", "IAI"};
				case LawFx.SWORD_MOON -> new String[] {"", "LUNA DE ACERO"};
				case LawFx.SWORD_WHIRL -> new String[] {"", "TORBELLINO"};
				case LawFx.SWORD_THRUSTS -> new String[] {"", "MIL PUNTADAS"};
				default -> null;
			};
			default -> null;
		};
		if (t != null) bannerText(t[0], t[1]);
	}

	/**
	 * Un rótulo con cualquier texto: kana es la línea pequeña de arriba (puede ir vacía o null) y name el nombre en
	 * grande. Solo con «Aviso grande del ROOM»; el mismo rótulo repetido antes de 2,5 s no se vuelve a anunciar.
	 */
	public static void bannerText(String kana, String name) {
		if (name == null || name.isEmpty()) return;
		if (!OpeSettings.get().avisoRoom) return;
		String k = kana == null ? "" : kana;
		double now = RoomFxClient.time();
		if (name.equals(lastName) && k.equals(lastKana) && now - lastAt < REPEAT_SECONDS) return;
		lastKana = k;
		lastName = name;
		lastAt = now;
		bKana = k;
		bName = name;
		bStart = now;
	}

	/**
	 * El fotograma de impacto, con fuerza strength (0..1) y el golpe en (sx, sy) en fracciones de la pantalla
	 * (0,5 y 0,5 = el centro): (a) un fotograma con los colores invertidos durante 0,06 s, solo si strength ≥ 0,6 con
	 * todos los destellos; (b) las líneas de concentración del manga durante 0,28 s, con destellos suaves o todos.
	 * Nada con «menos movimiento».
	 */
	public static void impact(float strength, float sx, float sy) {
		if (!(strength > 0.01f)) return;   // también descarta NaN
		if (OpeSettings.reducedMotion()) return;
		int level = OpeSettings.flashLevel();
		if (level < 1) return;
		float k = Math.min(1f, strength);
		double now = RoomFxClient.time();
		// (a) la inversión: solo los golpes fuertes, con todos los destellos, y no más de dos por segundo
		if (level == 2 && k >= 0.6f && now - invertStart >= INVERT_GAP) invertStart = now;
		// (b) las líneas: si las que hay en pantalla aún pesan más que este golpe, se quedan esas
		if (k < focusLeft(now)) return;
		focusStart = now;
		focusStrength = k;
		focusX = clamp01(sx);
		focusY = clamp01(sy);
		int n = 36 + RNG.nextInt(13);   // de 36 a 48
		float base = RNG.nextFloat() * (float) (Math.PI * 2);
		for (int i = 0; i < n; i++) {
			// repartidas por la vuelta con algo de desorden, para que no se amontonen ni parezcan una rueda
			float ang = base + (float) (Math.PI * 2) * (i + (RNG.nextFloat() - 0.5f) * 0.8f) / n;
			F_COS[i] = (float) Math.cos(ang);
			F_SIN[i] = (float) Math.sin(ang);
			// casi todas finas, alguna gruesa, como el trazo a plumilla
			F_HALF[i] = RNG.nextFloat() < 0.25f ? 1.6f + RNG.nextFloat() * 1.2f : 0.5f + RNG.nextFloat() * 0.9f;
			// la punta a un 22-30 % del lado corto desde el golpe; un tercio se quedan más lejos (largos distintos)
			float inner = 0.22f + 0.08f * RNG.nextFloat();
			if (RNG.nextFloat() < 0.33f) inner += 0.05f + 0.12f * RNG.nextFloat();
			F_INNER[i] = inner;
			F_ALPHA[i] = 0.55f + 0.45f * RNG.nextFloat();
		}
		focusCount = n;
	}

	/**
	 * Un tajo que cruza toda la pantalla por el centro con ese ángulo (grados; 0 = horizontal, positivo = gira hacia
	 * abajo a la derecha): entra en 0,08 s y se apaga en 0,25 s. Nada con «menos movimiento».
	 */
	public static void slash(float angleDeg, float strength) {
		if (!(strength > 0.01f)) return;
		if (OpeSettings.reducedMotion()) return;
		slashStart = RoomFxClient.time();
		slashAngle = angleDeg;
		slashStrength = Math.min(1f, strength);
	}

	public static void clear() {
		bKana = "";
		bName = "";
		bStart = -100;
		lastKana = "";
		lastName = "";
		lastAt = -100;
		invertStart = -100;
		focusStart = -100;
		focusStrength = 0f;
		focusCount = 0;
		slashStart = -100;
		slashStrength = 0f;
	}

	// ------------------------------------------------------------------ dibujo

	/** Cada fotograma, desde {@link LawFxClient#renderHud} (después de lo suyo). */
	@SuppressWarnings("deprecation")
	public static void render(DrawContext ctx, RenderTickCounter tc) {
		MinecraftClient mc = MinecraftClient.getInstance();
		if (mc.player == null || mc.options.hudHidden) return;
		int w = ctx.getScaledWindowWidth(), h = ctx.getScaledWindowHeight();
		if (w <= 0 || h <= 0) return;
		double now = RoomFxClient.time();
		boolean calm = OpeSettings.reducedMotion();
		int level = OpeSettings.flashLevel();

		float fAge = (float) (now - focusStart);
		boolean focusOn = !calm && level >= 1 && focusCount > 0 && fAge >= 0f && fAge < FOCUS_LIFE;
		float sAge = (float) (now - slashStart);
		boolean slashOn = !calm && level >= 1 && slashStrength > 0f && sAge >= 0f && sAge < SLASH_WIPE + SLASH_FADE;
		float bAge = (float) (now - bStart);
		boolean bannerOn = !bName.isEmpty() && bAge >= 0f && bAge < B_LIFE && OpeSettings.get().avisoRoom;
		float iAge = (float) (now - invertStart);
		boolean invertOn = !calm && level == 2 && iAge >= 0f && iAge < INVERT_LIFE;
		if (!focusOn && !slashOn && !bannerOn && !invertOn) return;

		// 1) y 2) las líneas de concentración y el tajo, con mezcla normal (la tinta oscura no se puede sumar)
		if (focusOn || slashOn) {
			ctx.draw();
			Matrix4f m = ctx.getMatrices().peek().getPositionMatrix();
			RenderSystem.enableBlend();
			RenderSystem.defaultBlendFunc();
			RenderSystem.disableDepthTest();
			RenderSystem.disableCull();
			RenderSystem.setShader(GameRenderer::getPositionColorProgram);
			BufferBuilder bb = Tessellator.getInstance().begin(VertexFormat.DrawMode.QUADS, VertexFormats.POSITION_COLOR);
			try {
				if (focusOn) focusLines(bb, m, w, h, fAge, level);
				if (slashOn) slashStreak(bb, m, w, h, sAge, level);
			} finally {
				end(bb);
				RenderSystem.enableCull();
				RenderSystem.enableDepthTest();
				RenderSystem.disableBlend();
			}
		}

		// 3) el rótulo (franja, subrayado y textos en un solo lote)
		if (bannerOn) {
			TextRenderer tr = mc.textRenderer;
			ctx.draw(() -> drawBanner(ctx, tr, w, h, bAge, calm));
		}

		// 4) la inversión, lo último: un cuadro blanco con mezcla (1 − destino, 0) deja la pantalla en negativo
		if (invertOn) {
			ctx.draw();
			Matrix4f m = ctx.getMatrices().peek().getPositionMatrix();
			RenderSystem.enableBlend();
			RenderSystem.blendFunc(GlStateManager.SrcFactor.ONE_MINUS_DST_COLOR, GlStateManager.DstFactor.ZERO);
			RenderSystem.disableDepthTest();
			RenderSystem.disableCull();
			RenderSystem.setShader(GameRenderer::getPositionColorProgram);
			BufferBuilder bb = Tessellator.getInstance().begin(VertexFormat.DrawMode.QUADS, VertexFormats.POSITION_COLOR);
			try {
				v(bb, m, 0f, 0f, WHITE, 1f);
				v(bb, m, 0f, h, WHITE, 1f);
				v(bb, m, w, h, WHITE, 1f);
				v(bb, m, w, 0f, WHITE, 1f);
			} finally {
				end(bb);
				RenderSystem.defaultBlendFunc();
				RenderSystem.enableCull();
				RenderSystem.enableDepthTest();
				RenderSystem.disableBlend();
			}
		}
	}

	/**
	 * Las líneas de concentración: cada una es una cuña casi negra, ancha en el borde de la pantalla y en punta hacia el
	 * golpe, que acaba antes de llegar (el centro queda limpio). Las puntas se cierran hacia dentro en 0,06 s y todo se
	 * apaga deprisa.
	 */
	private static void focusLines(BufferBuilder bb, Matrix4f m, int w, int h, float age, int level) {
		float u = age / FOCUS_LIFE;
		float peak = focusStrength * 0.55f * (1f - u) * (1f - u) * (level >= 2 ? 1f : 0.75f);
		if (peak < 0.01f) return;
		float px = focusX * w, py = focusY * h;
		float minDim = Math.min(w, h);
		float size = Math.max(0.75f, Math.min(3f, minDim / 270f));
		// las puntas entran un 25 % más lejos y se cierran hacia el golpe
		float pull = 1f + 0.25f * (1f - easeOutCubic(age / 0.06f));
		for (int i = 0; i < focusCount; i++) {
			float c = F_COS[i], s = F_SIN[i];
			// dónde sale el rayo de la pantalla
			float edge = Float.MAX_VALUE;
			if (c > 1e-4f) edge = Math.min(edge, (w - px) / c);
			else if (c < -1e-4f) edge = Math.min(edge, -px / c);
			if (s > 1e-4f) edge = Math.min(edge, (h - py) / s);
			else if (s < -1e-4f) edge = Math.min(edge, -py / s);
			if (edge == Float.MAX_VALUE) continue;
			float rIn = F_INNER[i] * minDim * pull;
			float rOut = edge + 4f;
			if (rOut - rIn < 8f) continue;
			float half = F_HALF[i] * size;
			float a = peak * F_ALPHA[i];
			float bx = px + c * rOut, by = py + s * rOut;
			float tx = px + c * rIn, ty = py + s * rIn;
			// un quad con los dos últimos vértices en la punta es un triángulo
			v(bb, m, bx - s * half, by + c * half, INK, a);
			v(bb, m, bx + s * half, by - c * half, INK, a);
			v(bb, m, tx, ty, INK, a * 0.6f);
			v(bb, m, tx, ty, INK, a * 0.6f);
		}
	}

	/**
	 * El tajo: una recta por el centro de la pantalla, más larga que la diagonal. Mientras cruza, la punta avanza de un
	 * extremo al otro y la cola es más tenue; luego se adelgaza y se apaga. Halo celeste a los dos lados y núcleo blanco
	 * de 2-3 de grueso encima.
	 */
	private static void slashStreak(BufferBuilder bb, Matrix4f m, int w, int h, float age, int level) {
		float k = slashStrength;
		float fade = age <= SLASH_WIPE ? 1f : 1f - (age - SLASH_WIPE) / SLASH_FADE;
		if (fade <= 0f) return;
		float cx = w * 0.5f, cy = h * 0.5f;
		double r = Math.toRadians(slashAngle);
		float dx = (float) Math.cos(r), dy = (float) Math.sin(r);
		float nx = -dy, ny = dx;
		float half = (float) Math.sqrt(cx * cx + cy * cy) + 16f;
		float wipe = easeOutCubic(age / SLASH_WIPE);
		float t0 = -half, t1 = -half + 2f * half * wipe;
		if (t1 - t0 < 1f) return;
		float x0 = cx + dx * t0, y0 = cy + dy * t0;
		float x1 = cx + dx * t1, y1 = cy + dy * t1;
		float tail = 0.35f + 0.65f * wipe;
		float core = (1f + 0.5f * k) * (0.5f + 0.5f * fade);   // medio grueso: 1 a 1,5 (2-3 en total)
		float glow = (3f + 7f * k) * (0.7f + 0.3f * fade);
		// como el tajo de CutFx: entero con destellos, apenas un rastro con «suaves»
		float soft = level >= 2 ? 1f : 0.05f;
		float aGlow = 0.5f * k * fade * fade * soft;
		float aCore = Math.min(1f, 0.7f + 0.3f * k) * fade * soft;
		// el halo celeste, del filo hacia fuera, a cada lado
		strip(bb, m, x0, y0, x1, y1, nx, ny, core * 0.5f, core + glow, ROOM, aGlow, 0f, tail);
		strip(bb, m, x0, y0, x1, y1, nx, ny, -core * 0.5f, -(core + glow), ROOM, aGlow, 0f, tail);
		// el núcleo blanco, encima
		strip(bb, m, x0, y0, x1, y1, nx, ny, -core, core, WHITE, aCore, aCore, tail);
	}

	/**
	 * Una franja a lo largo del segmento (x0,y0)→(x1,y1), entre las distancias o0 y o1 sobre la normal (nx,ny): alfa aIn
	 * en o0 y aOut en o1; tail multiplica el alfa del extremo de salida (x0,y0).
	 */
	private static void strip(BufferBuilder bb, Matrix4f m, float x0, float y0, float x1, float y1, float nx, float ny,
							  float o0, float o1, int rgb, float aIn, float aOut, float tail) {
		v(bb, m, x0 + nx * o0, y0 + ny * o0, rgb, aIn * tail);
		v(bb, m, x0 + nx * o1, y0 + ny * o1, rgb, aOut * tail);
		v(bb, m, x1 + nx * o1, y1 + ny * o1, rgb, aOut);
		v(bb, m, x1 + nx * o0, y1 + ny * o0, rgb, aIn);
	}

	/**
	 * El rótulo, centrado a un 22 % de la altura: la franja oscura, el subrayado celeste que entra desde la izquierda,
	 * la línea en japonés (celeste, 1,2×) y el nombre en grande (casi blanco, 2,6×, menos si no cabe con 16 de margen).
	 * Entra en 120 ms (el nombre de 1,12× a 1× y la línea japonesa deslizándose; con «menos movimiento» solo el
	 * fundido), se queda 0,9 s y se apaga en 0,35 s.
	 */
	private static void drawBanner(DrawContext ctx, TextRenderer tr, int w, int h, float age, boolean calm) {
		float a;
		if (age < B_IN) a = age / B_IN;
		else if (age > B_IN + B_HOLD) a = 1f - (age - B_IN - B_HOLD) / B_OUT;
		else a = 1f;
		a = clamp01(a);
		if (a < 0.03f) return;
		float e = calm ? 1f : easeOutCubic(age / B_IN);

		int nameW = Math.max(1, tr.getWidth(bName));
		int kanaW = bKana.isEmpty() ? 0 : tr.getWidth(bKana);
		// tiene que caber con 16 de margen a cada lado: en pantallas estrechas se encoge
		float fit = Math.max(32f, w - 32f);
		float nk = Math.max(0.5f, Math.min(NAME_SCALE, fit / nameW));
		float kk = kanaW > 0 ? Math.max(0.5f, Math.min(KANA_SCALE, fit / kanaW)) : 0f;
		float cx = w * 0.5f;
		float nameTop = h * 0.22f - 4f * nk;
		float kanaTop = nameTop - 3f - 8f * kk;
		float lineY = nameTop + 8f * nk + 2f;
		int lineH = nk >= 2f ? 2 : 1;
		float nameHalf = nameW * nk * 0.5f;
		float textHalf = Math.max(nameHalf, kanaW * kk * 0.5f);
		boolean shadow = Theme.floatShadow();

		// la franja oscura con los extremos difuminados: sobre el cielo o un destello el texto claro se perdía
		int shade = Math.round((shadow ? 0x78 : 0xC8) * a);
		int bx0 = (int) Math.floor(cx - textHalf) - 10, bx1 = (int) Math.ceil(cx + textHalf) + 10;
		int by0 = (int) Math.floor(kanaW > 0 ? kanaTop : nameTop) - 4;
		int by1 = (int) Math.ceil(lineY) + lineH + 4;
		shadeBand(ctx, bx0, by0, bx1, by1, 28, shade);

		// el subrayado celeste, con una chispa blanca en la punta mientras entra
		float u = calm ? 1f : easeOutCubic((age - B_WIPE_DELAY) / B_WIPE);
		int lx0 = Math.round(cx - nameHalf) - 4, lx1 = Math.round(cx + nameHalf) + 4;
		int lxe = lx0 + Math.round((lx1 - lx0) * u);
		int ly = Math.round(lineY);
		if (lxe > lx0) {
			ctx.fill(RenderLayer.getGuiOverlay(), lx0, ly, lxe, ly + lineH, argb(a * 0.95f, ROOM));
			if (u < 1f) ctx.fill(RenderLayer.getGuiOverlay(), Math.max(lx0, lxe - 4), ly, lxe, ly + lineH, argb(a, CORE));
		}

		// la línea en japonés, encima, entrando desde la izquierda
		if (kanaW > 0) {
			float kx = cx - kanaW * kk * 0.5f - (1f - e) * 10f;
			text(ctx, tr, bKana, kx, kanaTop, kk, ROOM, a, shadow);
		}
		// el nombre en grande: de 1,12× a 1× alrededor de su centro
		float k = nk * (1.12f - 0.12f * e);
		float nx = cx - nameW * k * 0.5f;
		float ny = nameTop + 4f * nk - 4f * k;
		text(ctx, tr, bName, nx, ny, k, CORE, a, shadow);
	}

	/** Una franja SHADE con alfa alpha (0..255) y los extremos izquierdo y derecho fundidos en fade unidades. */
	private static void shadeBand(DrawContext ctx, int x0, int y0, int x1, int y1, int fade, int alpha) {
		if (alpha <= 0 || x1 <= x0 || y1 <= y0) return;
		ctx.fill(RenderLayer.getGuiOverlay(), x0, y0, x1, y1, (alpha << 24) | SHADE);
		for (int i = 0; i < fade; i += 2) {
			float k = 1f - (i + 1f) / fade;
			int ai = Math.round(alpha * k * k);
			if (ai <= 0) break;
			int c = (ai << 24) | SHADE;
			ctx.fill(RenderLayer.getGuiOverlay(), x0 - i - 2, y0, x0 - i, y1, c);
			ctx.fill(RenderLayer.getGuiOverlay(), x1 + i, y0, x1 + i + 2, y1, c);
		}
	}

	/** Un texto escalado con su esquina en (x, y). */
	private static void text(DrawContext ctx, TextRenderer tr, String s, float x, float y, float scale, int rgb, float a,
							 boolean shadow) {
		// con alfa < 4/255 Minecraft pinta el texto opaco: por debajo de 0,03 no se dibuja
		if (a < 0.03f || s.isEmpty() || scale <= 0f) return;
		int col = argb(a, rgb);
		MatrixStack ms = ctx.getMatrices();
		ms.push();
		try {
			ms.translate(x, y, 0f);
			ms.scale(scale, scale, 1f);
			ctx.drawText(tr, s, 0, 0, col, shadow);
		} finally {
			ms.pop();
		}
	}

	// ------------------------------------------------------------------ utilidades

	/** Cuánto pesan aún las líneas de concentración que hay en pantalla (0 si no hay). */
	private static float focusLeft(double now) {
		double age = now - focusStart;
		if (focusCount <= 0 || age < 0 || age >= FOCUS_LIFE) return 0f;
		float u = (float) (age / FOCUS_LIFE);
		return focusStrength * (1f - u) * (1f - u);
	}

	/** Un vértice del HUD (z = 0) con la matriz del DrawContext. */
	private static void v(BufferBuilder bb, Matrix4f m, float x, float y, int rgb, float a) {
		int al = Math.round(clamp01(a) * 255f);
		bb.vertex(m, x, y, 0f).color((rgb >> 16) & 255, (rgb >> 8) & 255, rgb & 255, al);
	}

	private static void end(BufferBuilder bb) {
		var built = bb.endNullable();
		if (built != null) BufferRenderer.drawWithGlobalProgram(built);
	}

	private static int argb(float a, int rgb) {
		return (Math.round(clamp01(a) * 255f) << 24) | (rgb & 0xFFFFFF);
	}

	private static float clamp01(float t) {
		return t < 0f ? 0f : (t > 1f ? 1f : t);
	}

	private static float easeOutCubic(float t) {
		float u = 1f - clamp01(t);
		return 1f - u * u * u;
	}
}
