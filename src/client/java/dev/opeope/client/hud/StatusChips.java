package dev.opeope.client.hud;

import dev.opeope.OpeConfig;
import dev.opeope.client.ClientState;
import dev.opeope.client.config.OpeSettings;
import dev.opeope.client.hud.HudCanvas.LayoutState;
import dev.opeope.client.hud.HudCanvas.Rect;
import dev.opeope.net.Payloads;
import dev.opeope.power.Forge;
import dev.opeope.power.Patterns;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayList;
import java.util.List;

/**
 * Los chips de estado bajo la píldora (spec §3.7): Despertar, Scan, Curtain, Silent, el arma forjada, Unión, las
 * opciones de corte que no son las de siempre y si estás dentro del ROOM de otro jugador.
 */
public final class StatusChips {
	private StatusChips() {}

	private record Chip(String icon, int iconTint, String text, int color, float bar) {}

	private record Spot(Chip chip, int x, int y, int w, String text) {}

	private static final List<Spot> SPOTS = new ArrayList<>();
	private static boolean prevCurtain, prevSilent;
	private static long curtainAt, silentAt;
	private static HudCanvas canvas;

	/** Cada tick: el flanco de subida de Curtain y Silent arranca su cuenta atrás local. */
	static void tick(long now) {
		boolean c = ClientState.has(Payloads.State.CURTAIN);
		boolean s = ClientState.has(Payloads.State.SILENT);
		if (c && !prevCurtain) curtainAt = now;
		if (s && !prevSilent) silentAt = now;
		prevCurtain = c;
		prevSilent = s;
	}

	static void clear() {
		SPOTS.clear();
		prevCurtain = false;
		prevSilent = false;
		curtainAt = 0;
		silentAt = 0;
	}

	private static String countdown(String name, long since, int ticks, long now) {
		if (since <= 0) return name;
		long left = ticks * 50L - (now - since);
		return left > 0 ? name + " " + Txt.secsMs(left) : name;
	}

	/** El jugador en cuyo ROOM (slot 0) tienes los ojos, o null. */
	private static String otherRoomOwner(MinecraftClient mc) {
		if (mc.player == null || mc.world == null) return null;
		Vec3d eye = mc.player.getEyePos();
		for (ClientState.RoomInfo r : ClientState.ROOMS.values()) {
			if (r.slot() != 0 || r.owner().equals(mc.player.getUuid())) continue;
			if (!r.dim().equals(mc.world.getRegistryKey().getValue())) continue;
			if (eye.squaredDistanceTo(r.center()) > (double) r.radius() * r.radius()) continue;
			PlayerEntity owner = mc.world.getPlayerByUuid(r.owner());
			return owner != null ? owner.getName().getString() : "otro jugador";
		}
		return null;
	}

	private static List<Chip> chips(MinecraftClient mc, OpeSettings.Interfaz mode, long now) {
		List<Chip> out = new ArrayList<>();
		boolean simple = mode == OpeSettings.Interfaz.SENCILLA;
		if (ClientState.awakened()) out.add(new Chip("despertar", Theme.AWAKE, "Despertar", Theme.AWAKE, -1f));
		if (ClientState.scanTicks > 0) {
			float f = Anim.clamp01(ClientState.scanTicks / (float) OpeConfig.SCAN_TICKS);
			out.add(new Chip("scan", Theme.HI, "Scan " + Txt.secs(ClientState.scanTicks), Theme.HI, f));
		}
		if (!simple) {
			if (ClientState.has(Payloads.State.CURTAIN)) {
				out.add(new Chip("curtain", Theme.HI, countdown("Curtain", curtainAt, OpeConfig.CURTAIN_TICKS, now), Theme.HI, -1f));
			}
			if (ClientState.has(Payloads.State.SILENT)) {
				out.add(new Chip("silent", Theme.HI, countdown("Silent", silentAt, OpeConfig.SILENT_TICKS, now), Theme.HI, -1f));
			}
			int wp = ClientState.weapon;
			if (wp >= 0 && wp < Forge.NAMES.length) {
				out.add(new Chip(Icons.weapon(wp), Theme.FORGE, "Arma: " + Forge.NAMES[wp], Theme.FORGE, -1f));
			}
			if (ClientState.union()) out.add(new Chip("union", Theme.UNION, "Unión", Theme.UNION, -1f));
			int pat = ClientState.pattern;
			if (pat > 0 && pat < Patterns.NAMES.length) out.add(new Chip("patron", Theme.TEXT2, Patterns.NAMES[pat], Theme.TEXT, -1f));
			if (ClientState.has(Payloads.State.REACH_TARGET)) out.add(new Chip("alcance", Theme.TEXT2, "Solo el objetivo", Theme.TEXT, -1f));
			if (ClientState.has(Payloads.State.SUSPEND)) out.add(new Chip("suspender", Theme.TEXT2, "Suspender", Theme.TEXT, -1f));
		}
		String other = otherRoomOwner(mc);
		if (other != null) out.add(new Chip("room", Theme.SOFT, "Dentro del ROOM de " + other, Theme.SOFT, -1f));
		return out;
	}

	static void layout(HudCanvas c, LayoutState s) {
		canvas = c;
		SPOTS.clear();
		s.chips = null;
		OpeSettings.Interfaz mode = OpeSettings.get().interfaz;
		if (mode == OpeSettings.Interfaz.MINIMA) return;
		List<Chip> all = chips(MinecraftClient.getInstance(), mode, c.now);
		if (all.isEmpty()) return;

		List<Chip> show = new ArrayList<>(all.subList(0, Math.min(5, all.size())));
		if (all.size() > 5) show.add(new Chip(null, 0, "+" + (all.size() - 5), Theme.TEXT2, -1f));

		int x = c.M, y = c.M + 17;
		int left = c.M, right = c.M, bottom = y;
		for (Chip ch : show) {
			int maxRight = Math.min(c.leftMax(), c.effectsLeft(y, y + 14) - 4);
			int pad = ch.icon() != null ? 16 : 6;
			String text = ch.text();
			int w = pad + c.tr.getWidth(text) + 6;
			if (x > c.M && x + w > maxRight) {
				if (s.stripOpen) break;
				x = c.M;
				y += 17;
				maxRight = Math.min(c.leftMax(), c.effectsLeft(y, y + 14) - 4);
			}
			if (x + w > maxRight) {
				text = Txt.ellipsize(c.tr, text, Math.max(0, maxRight - x - pad - 6));
				if (text.isEmpty()) break;
				w = pad + c.tr.getWidth(text) + 6;
			}
			SPOTS.add(new Spot(ch, x, y, w, text));
			right = Math.max(right, x + w);
			bottom = Math.max(bottom, y + 14);
			x += w + 3;
		}
		if (SPOTS.isEmpty()) return;
		s.chips = Rect.ltrb(left, c.M + 17, right, bottom);
		s.leftBottom = Math.max(s.leftBottom, bottom);
		s.leftRight = Math.max(s.leftRight, right);
		s.place("chips", true, s.chips);
	}

	static void draw(Ui.Batch b) {
		if (canvas == null) return;
		for (Spot sp : SPOTS) {
			Chip ch = sp.chip();
			int x = sp.x(), y = sp.y(), w = sp.w();
			Ui.panel(b, x, y, w, 14, 7, Theme.PANEL_TOP, Theme.PANEL_BOTTOM, Theme.HAIRLINE);
			int tx = x + 6;
			if (ch.icon() != null) {
				Ui.icon(b, Ui.ICONS, ch.icon(), x + 2, y + 1, ch.iconTint());
				tx = x + 16;
			}
			b.text(sp.text(), tx, y + 3, ch.color(), false);
			if (ch.bar() >= 0f) {
				int full = w - 14;
				int len = Math.round(full * ch.bar());
				if (len > 0) b.fill(Ui.OVER, x + 7, y + 12, x + 7 + len, y + 13, ch.color());
			}
		}
	}
}
