package dev.opeope.client;

import dev.opeope.OpeOpeMod;
import dev.opeope.client.input.Bisturi;
import dev.opeope.client.input.InputHandler;
import dev.opeope.power.PlayerPower;
import dev.opeope.power.PowerManager;
import dev.opeope.power.Room;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.LeavesBlock;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.util.ScreenshotRecorder;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.passive.PigEntity;
import net.minecraft.server.integrated.IntegratedServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.GameMode;
import net.minecraft.world.Heightmap;

import java.util.UUID;

/**
 * Solo con -Dopeope.autotest=true (la prueba de GitHub): monta una escena (torre, árbol, muro, un montículo y un cerdo),
 * abre un ROOM, traza tajos como lo haría el ratón y guarda capturas de cada momento en run/screenshots.
 */
public final class AutoTest {
	private AutoTest() {}

	public static final boolean ON = Boolean.getBoolean("opeope.autotest");
	private static int t = -1;

	/** Un tajo: en qué tick empieza, hacia dónde mira, cómo se arrastra el ratón y cómo se llama. */
	private record Slash(int at, float yaw, float pitch, double dx, double dy, String name) {}

	private static final Slash[] SLASHES = {
		new Slash(160, 0, 0, 14, 7, "1-diagonal"),       // de arriba-izquierda a abajo-derecha, a la altura de los ojos
		new Slash(400, 0, -6, 16, 0, "2-horizontal"),     // horizontal un poco por encima de los ojos
		new Slash(640, 0, 32, 16, 0, "3-suelo"),          // mirando al suelo: corta el terreno
		new Slash(880, 25, 4, 0, 16, "4-vertical"),        // vertical
	};
	private static final int[] SHOTS_AFTER = {1, 4, 10, 24, 50, 110};
	private static final int BISTURI_AT = 1120;
	private static final int END = 1400;

	public static void tick(MinecraftClient mc) {
		if (!ON) return;
		IntegratedServer srv = mc.getServer();
		if (mc.player == null || mc.world == null || srv == null) return;
		t++;
		UUID id = mc.player.getUuid();
		if (t == 60) srv.execute(() -> setup(srv, id));
		if (t == 140) shot(mc, "0-escena");
		for (Slash s : SLASHES) {
			int r = t - s.at;
			if (r == -12) srv.execute(() -> look(srv, id, s.yaw, s.pitch));
			if (r == 0) InputHandler.beginDrag();
			if (r >= 1 && r <= 8) InputHandler.captureLook(s.dx, s.dy);
			if (r == 9) shot(mc, s.name + "-a-trazando");
			if (r == 10) InputHandler.endDrag();
			for (int k = 0; k < SHOTS_AFTER.length; k++) {
				if (r == 10 + SHOTS_AFTER[k]) shot(mc, s.name + "-" + (char) ('b' + k) + "-" + SHOTS_AFTER[k] + "t");
			}
		}
		// Bisturí: plano por donde apuntas (el montículo), dos clics que cortan ya y quedan quietos, y al soltar cae todo.
		int b = t - BISTURI_AT;
		if (b == -12) srv.execute(() -> look(srv, id, 0, 8));    // la base de la torre
		if (b == 0) Bisturi.start();
		if (b == 4) Bisturi.scroll(1, false, false);              // girar 15°
		if (b == 8) shot(mc, "5-bisturi-a-plano");
		if (b == 10) Bisturi.addPlane();
		if (b == 14) for (int i = 0; i < 4; i++) Bisturi.scroll(-1, false, false);   // girar -60°
		if (b == 18) Bisturi.addPlane();
		if (b == 22) shot(mc, "5-bisturi-b-sujeto");
		if (b == 26) shot(mc, "5-bisturi-c-sujeto");
		if (b == 30) Bisturi.commit();
		if (b == 34) shot(mc, "5-bisturi-d-soltar-4t");
		if (b == 44) shot(mc, "5-bisturi-e-soltar-14t");
		if (b == 70) shot(mc, "5-bisturi-f-soltar-40t");
		// el cerdo de cerca
		if (b == 100) srv.execute(() -> look(srv, id, 32, 28));
		if (b == 120) shot(mc, "6-cerdo");
		if (t == END) OpeOpeMod.LOG.info("AUTOTEST terminado");
	}

	private static void shot(MinecraftClient mc, String name) {
		ScreenshotRecorder.saveScreenshot(mc.runDirectory, "autotest-" + name + ".png", mc.getFramebuffer(), msg -> {});
		OpeOpeMod.LOG.info("AUTOTEST captura {}", name);
	}

	private static void look(IntegratedServer srv, UUID id, float yaw, float pitch) {
		ServerPlayerEntity sp = srv.getPlayerManager().getPlayer(id);
		if (sp == null) return;
		sp.networkHandler.requestTeleport(sp.getX(), sp.getY(), sp.getZ(), yaw, pitch);
	}

	private static void setup(IntegratedServer srv, UUID id) {
		ServerPlayerEntity sp = srv.getPlayerManager().getPlayer(id);
		if (sp == null) return;
		ServerWorld w = sp.getServerWorld();
		PowerManager.grantFruit(sp);
		sp.changeGameMode(GameMode.CREATIVE);
		w.setTimeOfDay(6000);
		w.setWeather(20 * 600, 0, false, false);
		w.getGameRules().get(net.minecraft.world.GameRules.DO_DAYLIGHT_CYCLE).set(false, srv);
		w.getGameRules().get(net.minecraft.world.GameRules.DO_MOB_SPAWNING).set(false, srv);

		int x0 = sp.getBlockX(), z0 = sp.getBlockZ();
		int fy = w.getTopY(Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, x0, z0) - 1;   // el suelo donde estás
		fy = Math.max(w.getBottomY() + 20, Math.min(fy, w.getTopY() - 40));
		BlockState air = Blocks.AIR.getDefaultState(), dirt = Blocks.DIRT.getDefaultState(), grass = Blocks.GRASS_BLOCK.getDefaultState();
		BlockState stone = Blocks.STONE.getDefaultState();
		BlockPos.Mutable m = new BlockPos.Mutable();
		// Un terreno llano: 14 de tierra y piedra hacia abajo (pegado al mundo de verdad), aire encima.
		for (int x = x0 - 18; x <= x0 + 18; x++) {
			for (int z = z0 - 10; z <= z0 + 26; z++) {
				for (int y = fy - 14; y <= fy + 26; y++) {
					BlockState st = y > fy ? air : y == fy ? grass : y > fy - 4 ? dirt : stone;
					w.setBlockState(m.set(x, y, z), st, 2 | 16);
				}
			}
		}
		// Torre de tablones 3×8×3 delante.
		box(w, x0 - 1, fy + 1, z0 + 6, x0 + 1, fy + 8, z0 + 8, Blocks.OAK_PLANKS.getDefaultState());
		// Muro de ladrillo de piedra a la izquierda (vista: +X queda a la izquierda mirando al sur).
		box(w, x0 + 3, fy + 1, z0 + 8, x0 + 8, fy + 5, z0 + 8, Blocks.STONE_BRICKS.getDefaultState());
		// Árbol a la derecha.
		BlockState leaves = Blocks.OAK_LEAVES.getDefaultState().with(LeavesBlock.PERSISTENT, true);
		for (int dx = -2; dx <= 2; dx++)
			for (int dy = -1; dy <= 2; dy++)
				for (int dz = -2; dz <= 2; dz++)
					if (dx * dx + dy * dy + dz * dz <= 6) w.setBlockState(m.set(x0 - 5 + dx, fy + 6 + dy, z0 + 7 + dz), leaves, 2 | 16);
		box(w, x0 - 5, fy + 1, z0 + 7, x0 - 5, fy + 6, z0 + 7, Blocks.OAK_LOG.getDefaultState());
		// Un montículo de piedra más lejos.
		for (int h = 0; h < 4; h++) box(w, x0 - 4 + h, fy + 1 + h, z0 + 13 + h, x0 + 4 - h, fy + 1 + h, z0 + 19 - h, Blocks.COBBLESTONE.getDefaultState());
		// Un cerdo quieto delante, un poco a la derecha.
		PigEntity pig = EntityType.PIG.create(w);
		if (pig != null) {
			pig.refreshPositionAndAngles(x0 - 2.5, fy + 1, z0 + 4.5, 0, 0);
			pig.setAiDisabled(true);
			pig.setInvulnerable(true);
			w.spawnEntity(pig);
		}
		sp.networkHandler.requestTeleport(x0 + 0.5, fy + 1, z0 + 0.5, 0f, 0f);
		PlayerPower pw = PowerManager.get(sp);
		pw.room = new Room(w.getRegistryKey(), new Vec3d(x0 + 0.5, fy + 3, z0 + 8.5), 11);
		pw.room.state = Room.State.FIXED;
		PowerManager.broadcastRoom(sp, 0, pw.room);
		OpeOpeMod.LOG.info("AUTOTEST escena lista en {} {} {}", x0, fy, z0);
	}

	private static void box(ServerWorld w, int x0, int y0, int z0, int x1, int y1, int z1, BlockState st) {
		BlockPos.Mutable m = new BlockPos.Mutable();
		for (int x = x0; x <= x1; x++)
			for (int y = y0; y <= y1; y++)
				for (int z = z0; z <= z1; z++) w.setBlockState(m.set(x, y, z), st, 2 | 16);
	}
}
