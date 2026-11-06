package dev.opeope.client.hud;

import dev.opeope.client.ClientState;
import dev.opeope.client.hud.HudCanvas.LayoutState;
import dev.opeope.client.hud.HudCanvas.Rect;
import dev.opeope.client.room.RoomHudState;
import dev.opeope.net.Payloads;
import net.minecraft.client.MinecraftClient;

/** La píldora del ROOM arriba a la izquierda (spec §3.7): si tienes ROOM, cuánto mide y si estás dentro. */
public final class RoomPill {
	private RoomPill() {}

	private static final int[] FLASH = {Payloads.RoomFx.CREATE, Payloads.RoomFx.FIX, Payloads.RoomFx.LAND, Payloads.RoomFx.ARRIVE};

	private static boolean visible, hasRoom;
	private static int x, y, w;
	private static Ui.Line line;
	private static HudCanvas canvas;

	/** El R-ROOM (slot 1) propio, en este mundo. */
	static boolean hasRRoom(MinecraftClient mc) {
		if (mc.player == null || mc.world == null) return false;
		ClientState.RoomInfo r = ClientState.ROOMS.get(mc.player.getUuid() + ":1");
		return r != null && r.dim().equals(mc.world.getRegistryKey().getValue());
	}

	static void layout(HudCanvas c, LayoutState s) {
		canvas = c;
		visible = false;
		MinecraftClient mc = MinecraftClient.getInstance();
		ClientState.RoomInfo own = ClientState.ownRoom();
		hasRoom = own != null || RoomHudState.casting() || RoomHudState.recalling();
		float r = RoomHudState.drawnRadius();
		if (r <= 0f && own != null) r = own.radius();

		line = new Ui.Line();
		if (RoomHudState.casting()) {
			line.text("ROOM → ", Theme.TEXT).text(Txt.meters(RoomHudState.castDistance()), Theme.HI);
		} else if (RoomHudState.recalling()) {
			line.text("ROOM ← ", Theme.TEXT).text("contigo", Theme.HI);
		} else if (own == null) {
			line.text("Sin ROOM", Theme.TEXT2);
		} else if (own.state() == 0 || RoomHudState.growing()) {
			line.text("ROOM ", Theme.TEXT).text(Txt.radius(r), Theme.HI).text(" · creciendo", Theme.TEXT2);
		} else {
			boolean inside = RoomHudState.inside();
			line.text("ROOM ", Theme.TEXT).text(Txt.radius(r), Theme.HI)
				.text(inside ? " · dentro" : " · fuera", inside ? Theme.SOFT : Theme.OUT_WARN);
		}
		if (hasRRoom(mc)) line.text(" · R-ROOM", Theme.AWAKE);

		int maxRight = Math.min(c.leftMax(), c.effectsLeft(c.M, c.M + 14) - 4);
		int maxW = Math.max(40, maxRight - c.M);
		w = Math.min(maxW, 16 + line.width(c.tr) + 6);
		x = c.M;
		y = c.M;
		visible = true;
		s.pill = new Rect(x, y, w, 14);
		s.leftBottom = Math.max(s.leftBottom, y + 14);
		s.leftRight = Math.max(s.leftRight, x + w);
		s.place("píldora", true, s.pill);
	}

	static void clear() {
		visible = false;
	}

	static void draw(Ui.Batch b) {
		if (!visible || canvas == null) return;
		long now = canvas.now;
		Ui.panel(b, x, y, w, 14, 7, Theme.PANEL_TOP, Theme.PANEL_BOTTOM, hasRoom ? Theme.ROOM_BORDER : Theme.HAIRLINE);
		long last = 0;
		for (int k : FLASH) last = Math.max(last, RoomHudState.lastEventMs(k));
		if (last > 0 && now >= last && now - last < 600) {
			Ui.roundRing(b, Ui.OVER, x, y, w, 14, 7, Theme.mulAlpha(Theme.HI, 1f - (now - last) / 600f));
		}
		Ui.icon(b, Ui.ICONS, "room", x + 2, y + 1, hasRoom ? Theme.ROOM : Theme.TEXT3);
		line.draw(b, x + 16, y + 1, w - 16 - 5);
	}
}
