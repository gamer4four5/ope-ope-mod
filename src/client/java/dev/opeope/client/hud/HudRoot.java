package dev.opeope.client.hud;

import dev.opeope.OpeOpeMod;
import dev.opeope.client.ClientState;
import dev.opeope.client.hud.HudCanvas.LayoutState;
import dev.opeope.client.hud.HudCanvas.Placed;
import dev.opeope.client.hud.HudCanvas.Rect;
import dev.opeope.client.input.Bisturi;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.render.RenderTickCounter;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.text.Text;
import net.minecraft.text.TranslatableTextContent;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * El HUD de la fruta (spec §3): calcula el lienzo, coloca cada módulo sin pisar a Minecraft ni a los demás, lo pinta
 * por fases y comprueba lo colocado (layoutProblems / layoutReport, para el AutoTest). También convierte los avisos
 * de la fruta que el servidor manda a la barra de acción en avisos propios (Toasts).
 */
public final class HudRoot {
	private HudRoot() {}

	private static final String MSG = "opeope.msg.";
	private static final Set<String> BAD = Set.of("sea", "no_room", "takt_full", "no_reattach", "nothing_cut",
		"need_material", "too_far", "no_awakening", "heart_far");
	private static final Set<String> GOLD = Set.of("forged", "awake_on", "united", "heart_back");
	/** Los módulos fijos que se comprueban también contra el chat. */
	private static final Set<String> CHAT_CHECKED = Set.of("tarjeta", "primeros pasos", "scan");

	private static Ui.Batch batch;
	private static boolean registered;
	private static List<String> problems = List.of();
	private static String report = "";
	private static String error;
	private static long errorAt, errorLoggedAt;

	/** Registra la intercepción de los avisos de la fruta (ClientReceiveMessageEvents.ALLOW_GAME). */
	public static void init() {
		if (registered) return;
		registered = true;
		ClientReceiveMessageEvents.ALLOW_GAME.register(HudRoot::onGameMessage);
	}

	/** Los opeope.msg.* que van a la barra de acción salen como avisos; se narran aquí si el narrador está activo. */
	private static boolean onGameMessage(Text message, boolean overlay) {
		if (!overlay || message == null) return true;
		if (!(message.getContent() instanceof TranslatableTextContent t)) return true;
		String key = t.getKey();
		if (key == null || !key.startsWith(MSG)) return true;
		Toasts.push(message, kindOf(key.substring(MSG.length())));
		MinecraftClient mc = MinecraftClient.getInstance();
		if (mc.getNarratorManager().isActive()) mc.getNarratorManager().narrateSystemMessage(message);
		return false;
	}

	static int kindOf(String id) {
		if (BAD.contains(id)) return Toasts.BAD;
		if (GOLD.contains(id)) return Toasts.GOLD;
		return Toasts.INFO;
	}

	/** Cada tick del cliente: relojes de los avisos, enfriamientos, chips, Scan, mira, aviso grande y pistas. */
	public static void tick(MinecraftClient mc) {
		long now = System.currentTimeMillis();
		boolean paused = mc.isPaused();
		boolean powers = mc.player != null && ClientState.hasFruit() && !mc.player.isSpectator();
		boolean strip = powers && ChordStrip.wanted();
		try {
			Toasts.tick(now, paused || strip);
			if (mc.player == null || mc.world == null) return;
			AbilityBar.tick(mc, now);
			StatusChips.tick(now);
			ScanList.tick(mc);
			CenterHud.tick(mc, now);
			Callout.tick(now);
			HintCard.tick(now, paused);
		} catch (RuntimeException e) {
			fail("tick", e);
		}
	}

	/** HudRenderCallback: pinta todo el HUD de la fruta. */
	public static void render(DrawContext ctx, RenderTickCounter counter) {
		MinecraftClient mc = MinecraftClient.getInstance();
		if (mc.player == null || mc.world == null || mc.options.hudHidden) {
			problems = List.of();
			return;
		}
		Theme.refresh();
		HudCanvas c;
		try {
			c = HudCanvas.compute(mc);
		} catch (RuntimeException e) {
			fail("lienzo", e);
			return;
		}
		if (batch == null) batch = new Ui.Batch(mc.textRenderer);

		LayoutState s = new LayoutState();
		boolean powers = ClientState.hasFruit() && !mc.player.isSpectator();
		boolean full = powers && !c.f3;
		s.stripOpen = powers && ChordStrip.wanted();
		s.bisturi = powers && Bisturi.active;
		s.centreOnly = powers && c.f3;

		MatrixStack m = ctx.getMatrices();
		m.push();
		try {
			m.scale(c.z, c.z, 1f);

			// Colocación: barra → medidor → píldora → chips → tira → centro → Scan → Bisturí → tarjeta → avisos
			if (full) {
				AbilityBar.layout(c, s);
				RoomMeter.layout(c, s);
				RoomPill.layout(c, s);
				StatusChips.layout(c, s);
			}
			if (powers) {
				ChordStrip.layout(c, s);
				CenterHud.layoutCentre(c, s);
				CenterHud.layoutTakt(c, s);
			}
			if (full) ScanList.layout(c, s);
			if (powers) CenterHud.layoutBisturi(c, s);
			boolean onboarding = false;
			if (full) {
				onboarding = OnboardingCard.layout(c, s);
				if (!onboarding) HintCard.layout(c, s);
			}
			Toasts.layout(c, s);
			if (powers) Callout.layout(c, s);

			// Dibujo por fases
			if (full) {
				AbilityBar.draw(batch);
				RoomMeter.draw(batch);
				RoomPill.draw(batch);
				StatusChips.draw(batch);
				ScanList.draw(batch);
				if (onboarding) OnboardingCard.draw(batch);
				else HintCard.draw(batch);
			}
			if (powers) {
				CenterHud.draw(batch);
				ChordStrip.draw(batch);
			}
			Toasts.draw(batch);
			if (powers) Callout.draw(batch);
			batch.flush(ctx);
		} catch (RuntimeException e) {
			batch.reset();
			fail("dibujo", e);
		} finally {
			m.pop();
		}
		validate(c, s);
	}

	/** Olvida todo (al desconectar). */
	public static void clear() {
		AbilityBar.clear();
		RoomMeter.clear();
		RoomPill.clear();
		StatusChips.clear();
		ScanList.clear();
		HintCard.clear();
		OnboardingCard.clear();
		ChordStrip.clear();
		CenterHud.clear();
		Callout.clear();
		Toasts.clear();
		if (batch != null) batch.reset();
		problems = List.of();
		report = "";
		error = null;
		errorAt = 0;
	}

	/** Los problemas de colocación del último fotograma: «duro: …» (no debe pasar) o «aviso: …». */
	public static List<String> layoutProblems() {
		return problems;
	}

	/** Una línea con el lienzo y dónde ha quedado cada módulo en el último fotograma. */
	public static String layoutReport() {
		return report;
	}

	private static void fail(String where, RuntimeException e) {
		long now = System.currentTimeMillis();
		error = where + ": " + e;
		errorAt = now;
		if (now - errorLoggedAt > 10000) {
			errorLoggedAt = now;
			OpeOpeMod.LOG.error("HUD de la fruta: fallo en {}", where, e);
		}
	}

	// ------------------------------------------------------------------ validador

	private static void hard(List<String> out, Placed p, Rect zone, String what) {
		if (zone != null && p.r().overlaps(zone)) out.add("duro: " + p.name() + " pisa " + what + " " + p.r().fmt());
	}

	private static void validate(HudCanvas c, LayoutState s) {
		List<String> out = new ArrayList<>();
		List<Placed> fixed = new ArrayList<>();
		for (Placed p : s.placed) if (p.persistent()) fixed.add(p);
		for (Placed p : fixed) {
			Rect r = p.r();
			if (!r.inside(c.W, c.H)) out.add("duro: " + p.name() + " se sale del lienzo " + r.fmt());
			hard(out, p, c.hotbar, "la barra rápida");
			hard(out, p, c.health, "la barra de vida");
			hard(out, p, c.actionBar, "la barra de acción");
			hard(out, p, c.effBen, "los efectos");
			hard(out, p, c.effHarm, "los efectos");
			hard(out, p, c.boss, "las barras de jefe");
			if (c.subtitles != null && r.overlaps(c.subtitles)) out.add("aviso: " + p.name() + " pisa los subtítulos " + r.fmt());
			if (CHAT_CHECKED.contains(p.name()) && r.overlaps(c.chat)) out.add("aviso: " + p.name() + " pisa el chat " + r.fmt());
		}
		for (int i = 0; i < fixed.size(); i++) {
			for (int j = i + 1; j < fixed.size(); j++) {
				Placed a = fixed.get(i), b = fixed.get(j);
				if (a.r().overlaps(b.r())) out.add("duro: " + a.name() + " pisa " + b.name() + " " + a.r().fmt() + " " + b.r().fmt());
			}
		}
		if (error != null && c.now - errorAt < 5000) out.add("duro: error del HUD (" + error + ")");
		problems = List.copyOf(out);

		StringBuilder sb = new StringBuilder();
		sb.append("lienzo ").append(c.W).append('x').append(c.H).append(" gui=").append(c.gui).append(" px=").append(c.px)
			.append(" f=").append(String.format(Locale.ROOT, "%.2f", c.f).replace('.', ','));
		if (s.bar != null) {
			sb.append(" barra=").append(s.barVertical ? "vertical " : "").append(s.barTile).append("u ")
				.append(s.barRows).append(s.barRows == 1 ? " fila " : " filas ").append(s.bar.fmt());
		} else {
			sb.append(" barra=no");
		}
		sb.append(" tarjeta=").append(fmt(s.card));
		sb.append(" pildora=").append(fmt(s.pill));
		sb.append(" chips=").append(fmt(s.chips));
		sb.append(" scan=").append(fmt(s.scan));
		sb.append(" medidor=").append(fmt(s.meter));
		sb.append(" takt=").append(fmt(s.taktChip));
		sb.append(" bisturi=").append(fmt(s.bisturiPanel));
		sb.append(" tira=").append(fmt(s.strip));
		sb.append(" avisos=").append(fmt(s.toasts));
		if (c.f3) sb.append(" f3");
		sb.append(" problemas=").append(out.size());
		report = sb.toString();
	}

	private static String fmt(Rect r) {
		return r != null ? r.fmt() : "no";
	}
}
