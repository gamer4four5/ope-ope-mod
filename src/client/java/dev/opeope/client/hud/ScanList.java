package dev.opeope.client.hud;

import dev.opeope.client.ClientState;
import dev.opeope.client.config.OpeSettings;
import dev.opeope.client.hud.HudCanvas.LayoutState;
import dev.opeope.client.hud.HudCanvas.Rect;
import dev.opeope.power.SurgicalSurvival;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.LivingEntity;
import net.minecraft.registry.Registries;
import net.minecraft.util.math.BlockPos;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Lo que ve el Scan (spec §3.7): seres vivos cercanos y radar X-Ray de vetas minerales / tesoros del ROOM. */
public final class ScanList {
	private ScanList() {}

	public record ScannedBlock(BlockPos pos, int rgb, boolean treasure) {}

	public static volatile List<ScannedBlock> SCANNED_BLOCKS = List.of();

	private static final List<String> ROWS = new ArrayList<>();
	private static final List<String> ORE_ROWS = new ArrayList<>();
	private static int age;
	private static boolean visible;
	private static int x, y, w, n;
	private static HudCanvas canvas;

	/** Cada tick (cada 5/10 mientras dura el Scan): rehace lista de vivos y vetas minerales/tesoros cercanos. */
	static void tick(MinecraftClient mc) {
		if (ClientState.scanTicks <= 0 || mc.player == null || mc.world == null) {
			ROWS.clear();
			ORE_ROWS.clear();
			SCANNED_BLOCKS = List.of();
			age = 0;
			return;
		}
		int tick = age++;
		if (tick % 10 == 0 || (SCANNED_BLOCKS.isEmpty() && tick < 2)) {
			scanBlocks(mc);
		}
		if (tick % 5 != 0 && !ROWS.isEmpty()) return;
		ROWS.clear();
		int livingCap = ORE_ROWS.isEmpty() ? 7 : Math.max(2, 7 - ORE_ROWS.size());
		List<LivingEntity> list = mc.world.getEntitiesByClass(LivingEntity.class, mc.player.getBoundingBox().expand(48),
			e -> e != mc.player && e.isAlive());
		list.sort(Comparator.comparingDouble(e -> e.squaredDistanceTo(mc.player)));
		for (LivingEntity e : list) {
			if (ROWS.size() >= livingCap) break;
			String held = e.getMainHandStack().isEmpty() ? "nada en la mano" : e.getMainHandStack().getName().getString();
			ROWS.add(e.getDisplayName().getString() + " · " + Math.round(e.getHealth()) + " ♥ · " + held);
		}
		ROWS.addAll(ORE_ROWS);
	}

	private static void scanBlocks(MinecraftClient mc) {
		ORE_ROWS.clear();
		BlockPos pc = mc.player.getBlockPos();
		ClientState.RoomInfo room = ClientState.ownRoom();
		int rad = room != null ? Math.min(18, (int) Math.ceil(room.radius())) : 15;
		int minY = Math.max(mc.world.getBottomY(), pc.getY() - rad);
		int maxY = Math.min(mc.world.getTopY() - 1, pc.getY() + rad);

		List<ScannedBlock> found = new ArrayList<>();
		Map<String, Integer> oreCounts = new LinkedHashMap<>();
		int spawners = 0, chests = 0;
		BlockPos.Mutable mut = new BlockPos.Mutable();

		double r2 = room != null ? (double) room.radius() * room.radius() : 0.0;
		for (int dx = -rad; dx <= rad; dx++) {
			for (int dz = -rad; dz <= rad; dz++) {
				for (int dy = minY; dy <= maxY; dy++) {
					mut.set(pc.getX() + dx, dy, pc.getZ() + dz);
					if (room != null && room.center().squaredDistanceTo(mut.getX() + 0.5, dy + 0.5, mut.getZ() + 0.5) > r2) continue;
					BlockState st = mc.world.getBlockState(mut);
					if (st.isAir()) continue;

					boolean ore = SurgicalSurvival.isOre(st);
					boolean treasure = !ore && SurgicalSurvival.isTreasureBlock(st);
					if (!ore && !treasure) continue;

					if (found.size() < 160) {
						found.add(new ScannedBlock(mut.toImmutable(), colorForBlock(st, treasure), treasure));
					}
					if (ore) {
						String shortName = st.getBlock().getName().getString()
							.replace("Mena de ", "").replace("mena de ", "")
							.replace(" de pizarra profunda", "").replace(" del Nether", "").trim();
						oreCounts.merge(shortName, 1, Integer::sum);
					} else if (st.isOf(Blocks.SPAWNER) || st.isOf(Blocks.TRIAL_SPAWNER)) {
						spawners++;
					} else {
						chests++;
					}
				}
			}
		}

		found.sort(Comparator.comparingDouble(sb -> sb.pos().getSquaredDistance(pc)));
		SCANNED_BLOCKS = found.size() > 48 ? List.copyOf(found.subList(0, 48)) : List.copyOf(found);

		if (!oreCounts.isEmpty()) {
			List<Map.Entry<String, Integer>> sorted = new ArrayList<>(oreCounts.entrySet());
			sorted.sort((a, b) -> Integer.compare(b.getValue(), a.getValue()));
			StringBuilder sb = new StringBuilder("⛏ ");
			int shown = 0;
			for (var e : sorted) {
				if (shown > 0) sb.append(" · ");
				sb.append(e.getValue()).append("× ").append(e.getKey());
				if (++shown >= 2) break;
			}
			sb.append(" [Alt]");
			ORE_ROWS.add(sb.toString());
		}
		if (spawners > 0 || chests > 0) {
			StringBuilder sb = new StringBuilder("✦ ");
			if (spawners > 0) sb.append(spawners).append(" Spawner");
			if (chests > 0) sb.append(spawners > 0 ? " · " : "").append(chests).append(" Cofre");
			sb.append(" [Mes]");
			ORE_ROWS.add(sb.toString());
		}
	}

	private static int colorForBlock(BlockState st, boolean treasure) {
		if (treasure) {
			if (st.isOf(Blocks.SPAWNER) || st.isOf(Blocks.TRIAL_SPAWNER) || st.isOf(Blocks.VAULT)) return 0xFF48FF;
			return 0xFFB840;
		}
		String path = Registries.BLOCK.getId(st.getBlock()).getPath();
		if (path.contains("diamond")) return 0x38F2FF;
		if (path.contains("emerald") || path.contains("uranium") || path.contains("plutonium")) return 0x40FF78;
		if (path.contains("gold") || path.contains("debris") || path.contains("electrum")) return 0xFFD93D;
		if (path.contains("redstone") || path.contains("ruby") || path.contains("cinnabar")) return 0xFF4555;
		if (path.contains("lapis") || path.contains("sapphire") || path.contains("platinum")) return 0x5588FF;
		if (path.contains("copper")) return 0xFF8C42;
		if (path.contains("coal")) return 0x99A4B8;
		return 0xE6F4FF;
	}

	static void clear() {
		ROWS.clear();
		ORE_ROWS.clear();
		SCANNED_BLOCKS = List.of();
		age = 0;
		visible = false;
	}

	private static int limitFor(HudCanvas c, int x1, int x2) {
		int limit = c.H - c.M;
		if (x1 < c.chatRight) limit = Math.min(limit, c.chatTop - 4);
		if (c.health != null && c.health.overlapsX(x1, x2)) limit = Math.min(limit, c.health.y() - 4);
		if (c.actionBar.overlapsX(x1, x2)) limit = Math.min(limit, c.actionBar.y() - 4);
		if (c.hotbar.overlapsX(x1, x2)) limit = Math.min(limit, c.hotbar.y() - 4);
		return limit;
	}

	static void layout(HudCanvas c, LayoutState s) {
		canvas = c;
		visible = false;
		OpeSettings.Interfaz mode = OpeSettings.get().interfaz;
		if (ClientState.scanTicks <= 0 || s.stripOpen || mode == OpeSettings.Interfaz.MINIMA) return;
		x = c.M;
		y = s.leftBottom + 4;
		w = Math.min(Math.round(0.42f * c.W), 200);
		if (c.boss != null && y < c.bossBottom) w = Math.min(w, c.cx - c.v(91) - 4 - c.M);
		int maxRows = mode == OpeSettings.Interfaz.SENCILLA ? 4 : 7;
		int limit = limitFor(c, x, x + w);
		int want = Math.max(1, Math.min(maxRows, ROWS.size()));
		int fit = (limit - y - 21) / 10;
		if (fit < 0) return;
		n = Math.min(want, fit);
		w = Math.min(w, c.effectsLeft(y, y + 21 + 10 * n) - 4 - c.M);
		if (w < 60) return;
		visible = true;
		s.scan = new Rect(x, y, w, 21 + 10 * n);
		s.leftBottom = Math.max(s.leftBottom, s.scan.bottom());
		s.leftRight = Math.max(s.leftRight, s.scan.right());
		s.place("scan", true, s.scan);
	}

	static void draw(Ui.Batch b) {
		if (!visible || canvas == null) return;
		int h = 21 + 10 * n;
		Ui.panel(b, x, y, w, h, 4, Theme.PANEL_TOP, Theme.PANEL_BOTTOM, Theme.HAIRLINE);
		Ui.icon(b, Ui.ICONS, "scan", x + 4, y + 4, Theme.HI);
		b.text("Scan", x + 19, y + 6, Theme.TEXT, false);
		String sec = Txt.secs(ClientState.scanTicks);
		b.text(sec, x + w - 4 - b.tr.getWidth(sec), y + 6, Theme.TEXT2, false);
		int ry = y + 18;
		if (ROWS.isEmpty()) {
			Ui.text(b, "Nada vivo cerca", x + 4, ry + 1, w - 8, Theme.TEXT3, false);
			return;
		}
		for (int i = 0; i < n && i < ROWS.size(); i++) {
			Ui.text(b, ROWS.get(i), x + 4, ry + 1 + i * 10, w - 8, Theme.TEXT, false);
		}
	}
}
