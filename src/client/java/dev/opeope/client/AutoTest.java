package dev.opeope.client;

import dev.opeope.OpeConfig;
import dev.opeope.OpeOpeMod;
import dev.opeope.client.config.OpeSettings;
import dev.opeope.client.hud.HudRoot;
import dev.opeope.client.hud.Icons;
import dev.opeope.client.input.Bisturi;
import dev.opeope.client.input.InputHandler;
import dev.opeope.client.input.Keys;
import dev.opeope.client.input.Onboarding;
import dev.opeope.client.render.TerrainFx;
import dev.opeope.client.room.RoomFxClient;
import dev.opeope.client.room.RoomSounds;
import dev.opeope.client.room.TaktFxClient;
import dev.opeope.client.room.TaktSounds;
import dev.opeope.client.screen.KeysScreen;
import dev.opeope.client.screen.SettingsScreen;
import dev.opeope.entity.BlockSet;
import dev.opeope.entity.Crater;
import dev.opeope.entity.PieceEntity;
import dev.opeope.net.Payloads;
import dev.opeope.power.Actions;
import dev.opeope.power.CutEngine;
import dev.opeope.power.GroundCuts;
import dev.opeope.power.PlayerPower;
import dev.opeope.power.PowerManager;
import dev.opeope.power.Room;
import dev.opeope.power.RoomOps;
import dev.opeope.power.Takt;
import dev.opeope.terrain.ChunkPlan;
import dev.opeope.terrain.ChunkPlanner;
import dev.opeope.terrain.GroundCut;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.LeavesBlock;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.util.ScreenshotRecorder;
import net.minecraft.client.util.Window;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.ItemEntity;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.entity.passive.PigEntity;
import net.minecraft.server.integrated.IntegratedServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.GameMode;
import net.minecraft.world.Heightmap;
import org.lwjgl.glfw.GLFW;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.function.BiConsumer;
import java.util.function.BooleanSupplier;

/**
 * Solo con -Dopeope.autotest=true (la prueba de GitHub): monta una escena (torre, árbol, muro, un montículo y un cerdo),
 * abre un ROOM, traza tajos como lo haría el ratón y guarda capturas de cada momento en run/screenshots.
 * <p>
 * Fase A (ticks 0–1400): los cortes. Fase B (desde el tick 1460): el HUD, los controles y el ROOM paso a paso, con
 * comprobaciones («AUTOTEST ok …» / «AUTOTEST FALLO …»), un resumen y «AUTOTEST terminado»; 40 ticks después el juego
 * se cierra solo.
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
	private static final int PHASE_B = 1460;

	public static void tick(MinecraftClient mc) {
		if (!ON) return;
		IntegratedServer srv = mc.getServer();
		if (mc.player == null || mc.world == null || srv == null) return;
		t++;
		UUID id = mc.player.getUuid();
		if (t == 60) srv.execute(() -> setup(srv, id));
		if (t == 100) shot(mc, "0a-primeros-pasos");
		if (t == 101) Onboarding.suppressForTest(true);   // que las pruebas no marquen los primeros pasos (salvo en la fase B, paso o)
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
		// fase B: el HUD, los controles y el ROOM
		if (t == PHASE_B) {
			OpeOpeMod.LOG.info("AUTOTEST fase B");
			plan(mc, srv, id);
		}
		if (t >= PHASE_B) runPlan();
		if (stopAt >= 0 && t >= stopAt) {
			stopAt = -1;
			mc.scheduleStop();
		}
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
		gx0 = x0;
		gfy = fy;
		gz0 = z0;
	}

	private static void box(ServerWorld w, int x0, int y0, int z0, int x1, int y1, int z1, BlockState st) {
		BlockPos.Mutable m = new BlockPos.Mutable();
		for (int x = x0; x <= x1; x++)
			for (int y = y0; y <= y1; y++)
				for (int z = z0; z <= z1; z++) w.setBlockState(m.set(x, y, z), st, 2 | 16);
	}

	// ------------------------------------------------------------------ fase B: pasos con esperas

	private static final int CREATE = dev.opeope.net.Payloads.RoomFx.CREATE;
	private static final int FIX = dev.opeope.net.Payloads.RoomFx.FIX;
	private static final int NOTCH = dev.opeope.net.Payloads.RoomFx.NOTCH;
	private static final int LIMIT = dev.opeope.net.Payloads.RoomFx.LIMIT;
	private static final int LAND = dev.opeope.net.Payloads.RoomFx.LAND;
	private static final int ARRIVE = dev.opeope.net.Payloads.RoomFx.ARRIVE;
	private static final int CLOSE = dev.opeope.net.Payloads.RoomFx.CLOSE;
	private static final int KINDS = dev.opeope.net.Payloads.RoomFx.KINDS;

	/**
	 * Un paso: espera delay ticks desde el paso anterior; si tiene until, espera además a que se cumpla (haciendo each
	 * cada period ticks mientras tanto) y, si pasan timeout ticks, lo apunta como FALLO y sigue igual. Luego hace action.
	 */
	private record Step(String name, int delay, BooleanSupplier until, int timeout, String failMsg, int period, Runnable each,
						Runnable action) {}

	private static final List<Step> PLAN = new ArrayList<>();
	private static int planAt, planWaited;
	private static int oks, fails;
	private static int stopAt = -1;
	/** Cuántos eventos del ROOM propio había de cada tipo al empezar a esperar uno. */
	private static final int[] BASE = new int[KINDS];
	private static float r0;
	private static Vec3d c0;
	private static KeysScreen keysScreen;

	// (k2) Takt sobre lo cortado
	/** El origen de la escena (setup, en el hilo del servidor; se lee en los dos). */
	private static volatile int gx0, gfy, gz0;
	/** Resultados que se escriben en el hilo del servidor; −1 = todavía no. */
	private static volatile int srvA = -1, srvB = -1, srvC = -1;
	private static int solid0, items0, chunkId = -1, chunkB = -1;
	/** Solo en el hilo del servidor. */
	private static int dropped0, relocated0;
	private static Crater.Result crater0;
	/** Los rugidos de roca que cae que había antes de soltar la rocota (cliente). */
	private static int roars0;
	private static BlockPos ghost, ghostB;
	private static List<BlockSet.Entry> ghostSet = List.of(), ghostSetB = List.of();
	/** Solo en el hilo del servidor. */
	private static Room savedRoom;
	private static Vec3d savedPos;
	/** Cuántos avisos de Takt había de cada tipo al empezar a esperar uno. */
	private static final int[] TBASE = new int[Payloads.TaktFx.KINDS];

	private static void runPlan() {
		planWaited++;
		while (planAt < PLAN.size()) {
			Step s = PLAN.get(planAt);
			if (planWaited < s.delay()) return;
			if (s.until() != null && !holds(s)) {
				int over = planWaited - s.delay();
				if (over < s.timeout()) {
					if (s.each() != null && over % s.period() == 0) run(s.name(), s.each());
					return;
				}
				fallo(s.name() + ": " + (s.failMsg() != null ? s.failMsg() : "tiempo agotado"));
			}
			planAt++;
			planWaited = 0;
			run(s.name(), s.action());
		}
	}

	private static boolean holds(Step s) {
		try {
			return s.until().getAsBoolean();
		} catch (RuntimeException e) {
			fallo(s.name() + ": " + e);
			return true;
		}
	}

	private static void run(String name, Runnable r) {
		if (r == null) return;
		try {
			r.run();
		} catch (RuntimeException e) {
			OpeOpeMod.LOG.error("AUTOTEST error en el paso «{}»", name, e);
			fallo(name + ": " + e);
		}
	}

	/** Espera wait ticks y hace action. */
	private static void step(int wait, String name, Runnable action) {
		PLAN.add(new Step(name, wait, null, 0, null, 1, null, action));
	}

	/** Espera a que until se cumpla (como mucho timeout ticks; si no, FALLO con failMsg o «tiempo agotado») y hace action. */
	private static void waitFor(String name, int timeout, String failMsg, BooleanSupplier until, Runnable action) {
		PLAN.add(new Step(name, 0, until, timeout, failMsg, 1, null, action));
	}

	/** Hace each cada period ticks hasta que until se cumpla (como mucho timeout ticks). */
	private static void repeatUntil(String name, int period, int timeout, Runnable each, BooleanSupplier until) {
		PLAN.add(new Step(name, 0, until, timeout, null, period, each, null));
	}

	/** Captura n ticks del cliente después del último evento kind del ROOM propio. */
	private static void shotAfterEvent(MinecraftClient mc, int kind, int n, String name) {
		waitFor(name, n + 40, null, () -> RoomFxClient.clientTicks() - RoomFxClient.LAST_TICK[kind] >= n, () -> shot(mc, name));
	}

	private static void key(int code, int action) {
		InputHandler.onKey(code, action);
	}

	private static void mouse(int button, int action) {
		InputHandler.onMouse(button, action);
	}

	private static void wheel(int dir) {
		InputHandler.onScroll(dir);
	}

	private static int fx(int kind) {
		return RoomFxClient.COUNTS[kind];
	}

	private static void snap(int kind) {
		BASE[kind] = fx(kind);
	}

	/** ¿Ha llegado un evento kind desde el último snap(kind)? */
	private static boolean got(int kind) {
		return fx(kind) > BASE[kind];
	}

	private static ClientState.RoomInfo room() {
		return ClientState.ownRoom();
	}

	private static float radius() {
		ClientState.RoomInfo r = room();
		return r == null ? -1 : r.radius();
	}

	private static void ok(String what) {
		oks++;
		OpeOpeMod.LOG.info("AUTOTEST ok {}", what);
	}

	private static void fallo(String what) {
		fails++;
		OpeOpeMod.LOG.warn("AUTOTEST FALLO {}", what);
	}

	private static void check(boolean cond, String what, String detail) {
		if (cond) ok(what);
		else fallo(what + ": " + detail);
	}

	private static void aviso(String what) {
		OpeOpeMod.LOG.info("AUTOTEST aviso {}", what);
	}

	/** Lo que el validador del HUD vio en el último fotograma: «duro» es FALLO, «aviso» solo se apunta. */
	private static void layout(String shot) {
		boolean hard = false;
		for (String p : HudRoot.layoutProblems()) {
			if (p.startsWith("duro")) {
				hard = true;
				fallo("layout " + shot + ": " + p.substring(Math.min(p.length(), "duro: ".length())));
			} else {
				aviso("layout " + shot + ": " + (p.startsWith("aviso: ") ? p.substring("aviso: ".length()) : p));
			}
		}
		if (!hard) ok("layout " + shot);
		OpeOpeMod.LOG.info("AUTOTEST layout {}: {}", shot, HudRoot.layoutReport());
	}

	private static void window(MinecraftClient mc, String shot) {
		Window w = mc.getWindow();
		OpeOpeMod.LOG.info("AUTOTEST ventana {}: {}x{}, escala {}, lienzo {}x{}", shot, w.getFramebufferWidth(), w.getFramebufferHeight(),
			w.getScaleFactor(), w.getScaledWidth(), w.getScaledHeight());
		if (w.getFramebufferWidth() != 1280 || w.getFramebufferHeight() != 720) aviso("la ventana no pasó a 1280x720 (" + shot + ")");
	}

	/** En el hilo del servidor, con el jugador y sus poderes. */
	private static void server(IntegratedServer srv, UUID id, BiConsumer<ServerPlayerEntity, PlayerPower> job) {
		srv.execute(() -> {
			ServerPlayerEntity sp = srv.getPlayerManager().getPlayer(id);
			if (sp != null) job.accept(sp, PowerManager.get(sp));
		});
	}

	private static String readConfig(String name) {
		try {
			return Files.readString(FabricLoader.getInstance().getConfigDir().resolve(name), StandardCharsets.UTF_8);
		} catch (Exception e) {
			return "";
		}
	}

	private static String fmt(double v) {
		return String.format(Locale.ROOT, "%.2f", v);
	}

	// ------------------------------------------------------------------ fase B: el guion

	private static void plan(MinecraftClient mc, IntegratedServer srv, UUID id) {
		PLAN.clear();
		planAt = 0;
		planWaited = 0;
		OpeSettings s = OpeSettings.get();
		final int Q = GLFW.GLFW_KEY_Q, PRESS = GLFW.GLFW_PRESS, RELEASE = GLFW.GLFW_RELEASE;
		final int LEFT = GLFW.GLFW_MOUSE_BUTTON_LEFT, RIGHT = GLFW.GLFW_MOUSE_BUTTON_RIGHT;

		// (a) lo que trae el mod (sonidos e iconos) y el jugador en supervivencia, sin ROOM
		step(0, "sonidos", () -> {
			List<String> missing = new ArrayList<>();
			for (String n : RoomSounds.IDS) if (mc.getSoundManager().get(OpeOpeMod.id(n)) == null) missing.add(n);
			check(missing.isEmpty(), "sonidos (" + RoomSounds.IDS.length + ")", "faltan " + missing);
		});
		step(0, "sonidos takt", () -> {
			List<String> missing = new ArrayList<>();
			for (String n : TaktSounds.IDS) if (mc.getSoundManager().get(OpeOpeMod.id(n)) == null) missing.add(n);
			check(missing.isEmpty(), "sonidos de Takt (" + TaktSounds.IDS.length + ")", "faltan " + missing);
		});
		step(0, "iconos", () -> {
			List<String> missing = new ArrayList<>();
			for (String n : Icons.ALL) {
				if (!mc.getResourceManager().getResource(OpeOpeMod.id("textures/gui/sprites/hud/" + n + ".png")).isPresent()) missing.add(n);
			}
			check(missing.isEmpty(), "iconos (" + Icons.ALL.length + ")", "faltan " + missing);
		});
		step(0, "supervivencia", () -> server(srv, id, (sp, pw) -> {
			RoomOps.remove(sp, pw);
			sp.changeGameMode(GameMode.SURVIVAL);
			sp.setHealth(sp.getMaxHealth());
			sp.getHungerManager().setFoodLevel(20);
			sp.getHungerManager().setSaturationLevel(20f);
			sp.networkHandler.requestTeleport(sp.getX(), sp.getY(), sp.getZ(), 0f, 10f);
		}));

		// (b) Scan y el HUD en reposo
		step(10, "scan", () -> key(GLFW.GLFW_KEY_F, PRESS));
		step(1, "scan: suelta", () -> key(GLFW.GLFW_KEY_F, RELEASE));
		step(10, "7-hud-reposo", () -> {
			shot(mc, "7-hud-reposo");
			layout("7-hud-reposo");
		});
		step(120, "7-hud-calma", () -> shot(mc, "7-hud-calma"));

		// (c) crear: semilla, florece, crece y queda fijo
		step(0, "crear", () -> {
			snap(CREATE);
			key(Q, PRESS);
		});
		step(1, "8-room-a", () -> shot(mc, "8-room-a-semilla"));
		waitFor("crear: CREATE", 10, null, () -> got(CREATE), null);
		step(2, "8-room-b", () -> shot(mc, "8-room-b-florece"));
		step(8, "8-room-c", () -> shot(mc, "8-room-c-creciendo"));
		step(6, "crear: suelta", () -> {
			snap(FIX);
			key(Q, RELEASE);
		});
		waitFor("crear: FIX", 10, null, () -> got(FIX), () -> shot(mc, "8-room-d-fija"));
		step(20, "8-room-e", () -> {
			shot(mc, "8-room-e-reposo");
			ClientState.RoomInfo r = room();
			check(r != null && r.state() == 1 && r.radius() > 15 && r.radius() < 60, "crear el ROOM",
				r == null ? "no hay ROOM" : "estado " + r.state() + ", radio " + fmt(r.radius()));
		});

		// (d) tamaño: un paso de rueda, ida y vuelta, y hasta el mínimo
		step(0, "tamaño", () -> key(Q, PRESS));
		step(2, "tamaño: rueda", () -> {
			r0 = radius();
			snap(NOTCH);
			wheel(1);
		});
		waitFor("tamaño: NOTCH", 10, null, () -> got(NOTCH), null);
		step(1, "9-tamano-a", () -> {
			double want = Math.max(r0 * 1.14, r0 + 1.25);
			float r1 = radius();
			check(r0 > 0 && Math.abs(r1 - want) < 0.05, "un paso de rueda", fmt(r0) + " → " + fmt(r1) + " (se esperaba " + fmt(want) + ")");
			shot(mc, "9-tamano-a");
		});
		step(0, "tamaño: +", () -> wheel(1));
		step(3, "tamaño: -", () -> wheel(-1));
		step(3, "9-tamano-b", () -> {
			shot(mc, "9-tamano-b");
			snap(LIMIT);
		});
		repeatUntil("tamaño: hasta el mínimo", 2, 80, () -> wheel(-1), () -> got(LIMIT));
		shotAfterEvent(mc, LIMIT, 1, "9-tamano-c-limite");
		step(0, "tamaño: mínimo", () -> {
			float r = radius();
			check(Math.abs(r - 4) <= 0.01, "el ROOM llega al mínimo (4 m)", "radio " + fmt(r));
			key(Q, RELEASE);
		});
		step(6, "9-tamano-d", () -> shot(mc, "9-tamano-d"));

		// (e) agrandarlo otra vez
		step(0, "agrandar", () -> key(Q, PRESS));
		for (int i = 0; i < 12; i++) step(2, "agrandar: rueda", () -> wheel(1));
		step(2, "agrandar: suelta", () -> key(Q, RELEASE));

		// (f) lanzarlo: sale de la mano, vuela y aterriza
		step(10, "lanzar: mira", () -> srv.execute(() -> look(srv, id, 20, 12)));
		step(12, "lanzar", () -> key(Q, PRESS));
		step(2, "lanzar: clic", () -> {
			ClientState.RoomInfo r = room();
			c0 = r == null ? null : r.center();
			snap(LAND);
			mouse(LEFT, PRESS);
		});
		step(1, "10-lanzar-a", () -> {
			mouse(LEFT, RELEASE);
			shot(mc, "10-lanzar-a-sale");
		});
		step(3, "10-lanzar-b", () -> shot(mc, "10-lanzar-b-vuela"));
		waitFor("lanzar", 16, "no aterriza", () -> got(LAND), null);
		shotAfterEvent(mc, LAND, 1, "10-lanzar-c-aterriza");
		shotAfterEvent(mc, LAND, 6, "10-lanzar-d-aterriza-6t");
		shotAfterEvent(mc, LAND, 30, "10-lanzar-e-aterriza-30t");
		step(0, "lanzar: suelta", () -> {
			key(Q, RELEASE);
			ClientState.RoomInfo r = room();
			double moved = r == null || c0 == null ? -1 : r.center().distanceTo(c0);
			check(moved > 5, "lanzar el ROOM", "el centro se movió " + fmt(moved) + " bloques");
		});

		// (g) traerlo: vuelve contigo
		step(10, "traer", () -> key(Q, PRESS));
		step(2, "traer: clic derecho", () -> {
			snap(ARRIVE);
			mouse(RIGHT, PRESS);
		});
		step(1, "11-traer-a", () -> {
			mouse(RIGHT, RELEASE);
			shot(mc, "11-traer-a");
		});
		step(3, "11-traer-b", () -> shot(mc, "11-traer-b"));
		waitFor("traer: ARRIVE", 20, null, () -> got(ARRIVE), () -> shot(mc, "11-traer-c"));
		step(1, "traer: suelta", () -> {
			key(Q, RELEASE);
			ClientState.RoomInfo r = room();
			Vec3d to = mc.player.getPos().add(0, 1, 0);
			double d = r == null ? -1 : r.center().distanceTo(to);
			check(r != null && d < 1.5, "traer el ROOM", r == null ? "no hay ROOM" : "queda a " + fmt(d) + " bloques");
		});

		// (h) quitarlo con un toque
		step(10, "quitar", () -> key(Q, PRESS));
		step(2, "12-quitar-a", () -> {
			snap(CLOSE);
			key(Q, RELEASE);
			shot(mc, "12-quitar-a");
		});
		waitFor("quitar: CLOSE", 10, null, () -> got(CLOSE), null);
		step(2, "12-quitar-b", () -> shot(mc, "12-quitar-b"));
		step(8, "12-quitar-c", () -> {
			shot(mc, "12-quitar-c");
			check(room() == null, "quitar el ROOM con un toque", "sigue ahí");
		});

		// (i) «Quitar el ROOM: doble toque»: un toque no lo quita, dos seguidos sí
		step(0, "doble toque", () -> {
			s.quitarRoom = OpeSettings.Quitar.DOBLE_TOQUE;
			key(Q, PRESS);
		});
		step(12, "doble toque: crea", () -> key(Q, RELEASE));
		step(10, "doble toque: un toque", () -> key(Q, PRESS));
		step(2, "doble toque: un toque suelta", () -> key(Q, RELEASE));
		step(12, "doble toque: sigue", () -> {
			check(room() != null, "un toque no quita el ROOM (doble toque)", "se quitó");
			snap(CLOSE);
			key(Q, PRESS);
		});
		step(2, "doble toque: 1", () -> key(Q, RELEASE));
		step(3, "doble toque: 2", () -> key(Q, PRESS));
		step(2, "doble toque: 2 suelta", () -> key(Q, RELEASE));
		waitFor("doble toque: CLOSE", 12, null, () -> got(CLOSE), null);
		step(1, "doble toque: quitado", () -> {
			check(room() == null, "un doble toque quita el ROOM", "sigue ahí");
			s.quitarRoom = OpeSettings.Quitar.TOQUE;
		});

		// (j) la segunda fila (Tab mantenida)
		step(10, "tab", () -> key(GLFW.GLFW_KEY_TAB, PRESS));
		step(8, "13-fila-tab", () -> {
			shot(mc, "13-fila-tab");
			layout("13-fila-tab");
			key(GLFW.GLFW_KEY_TAB, RELEASE);
		});

		// (k) Takt: dentro de un ROOM (fuera no coge nada), coger el cerdo, el chip, la fila de R y soltar. Sin cortes
		// recordados de la fase A y con el trozo más pequeño, el toque arranca un trozo pequeño que ningún corte forma.
		step(10, "takt: ROOM", () -> {
			server(srv, id, (sp, pw) -> GroundCuts.clearOwner(sp.getUuid()));
			ClientState.chunkSize = OpeConfig.TAKT_CHUNK_MIN;
			key(Q, PRESS);
		});
		step(12, "takt: ROOM suelta", () -> key(Q, RELEASE));
		step(5, "takt: mira", () -> srv.execute(() -> look(srv, id, 32, 28)));
		step(12, "takt: toque", () -> key(GLFW.GLFW_KEY_R, PRESS));
		step(2, "takt: toque suelta", () -> key(GLFW.GLFW_KEY_R, RELEASE));
		step(10, "14-takt-chip", () -> {
			shot(mc, "14-takt-chip");
			OpeOpeMod.LOG.info("AUTOTEST takt lleva {}", ClientState.taktCount);
			key(GLFW.GLFW_KEY_R, PRESS);
		});
		step(12, "14-fila-r", () -> {
			shot(mc, "14-fila-r");
			key(GLFW.GLFW_KEY_B, PRESS);   // R + B: soltar
		});
		step(1, "takt: soltar", () -> key(GLFW.GLFW_KEY_B, RELEASE));
		step(1, "takt: suelta R", () -> {
			key(GLFW.GLFW_KEY_R, RELEASE);
			ClientState.chunkSize = OpeConfig.TAKT_CHUNK_DEFAULT;   // lo que espera la rueda de (k2)
		});

		// (k2) Takt sobre lo cortado
		planSuelo(mc, srv, id);

		// (l) Bisturí: en el ROOM de Takt, el panel, un corte y soltar
		step(10, "bisturí: mira", () -> srv.execute(() -> look(srv, id, 0, 8)));
		step(12, "bisturí", () -> mouse(RIGHT, PRESS));
		step(4, "15-bisturi-panel-a", () -> {
			shot(mc, "15-bisturi-panel-a");
			if (!Bisturi.active) aviso("el clic derecho no abrió el Bisturí");
			mouse(LEFT, PRESS);
		});
		step(1, "bisturí: clic suelta", () -> mouse(LEFT, RELEASE));
		step(3, "15-bisturi-panel-b", () -> {
			shot(mc, "15-bisturi-panel-b");
			mouse(RIGHT, RELEASE);
		});

		// (m) Ajustes
		step(10, "ajustes", () -> mc.setScreen(new SettingsScreen(null)));
		step(10, "16-ajustes", () -> {
			shot(mc, "16-ajustes");
			Screen sc = mc.currentScreen;
			if (sc != null) sc.mouseScrolled(sc.width / 2.0, sc.height / 2.0, 0, -10);
		});
		step(4, "16-ajustes-b", () -> {
			shot(mc, "16-ajustes-b");
			mc.setScreen(null);
		});

		// (n) Teclas: cambiar una, el choque y restablecer
		step(5, "teclas", () -> {
			keysScreen = new KeysScreen(null);
			mc.setScreen(keysScreen);
		});
		step(10, "17-teclas-a", () -> {
			shot(mc, "17-teclas-a");
			keysScreen.beginRebind(Keys.MARKER);
		});
		step(2, "17-teclas-b", () -> {
			shot(mc, "17-teclas-b-esperando");
			keysScreen.keyPressed(GLFW.GLFW_KEY_J, 0, 0);
		});
		step(2, "17-teclas-c", () -> {
			shot(mc, "17-teclas-c-cambiada");
			check(Keys.MARKER.key.getCode() == GLFW.GLFW_KEY_J, "cambiar una tecla", "la piedra marcador quedó en " + Keys.MARKER.key.getTranslationKey());
			check(readConfig("opeope-teclas.json").contains("key.keyboard.j"), "la tecla nueva se guarda", "config/opeope-teclas.json no tiene key.keyboard.j");
			keysScreen.beginRebind(Keys.MARKER);
			keysScreen.keyPressed(GLFW.GLFW_KEY_R, 0, 0);   // choca con Takt
		});
		step(2, "17-teclas-d", () -> {
			shot(mc, "17-teclas-d-choque");
			KeysScreen.resetAll();
			mc.setScreen(null);
			keysScreen = null;
			check(Keys.MARKER.key.getCode() == GLFW.GLFW_KEY_G, "restablecer las teclas", "la piedra marcador quedó en " + Keys.MARKER.key.getTranslationKey());
		});

		// (o) Primeros pasos: la tarjeta y el paso del ROOM
		step(5, "primeros pasos", () -> {
			Onboarding.restart();
			Onboarding.suppressForTest(false);
		});
		step(10, "18-primeros-pasos-a", () -> {
			shot(mc, "18-primeros-pasos-a");
			key(Q, PRESS);
		});
		step(12, "primeros pasos: suelta", () -> key(Q, RELEASE));
		step(10, "18-primeros-pasos-b", () -> {
			shot(mc, "18-primeros-pasos-b");
			if (!Onboarding.done(Onboarding.STEP_ROOM)) aviso("primeros pasos: el paso del ROOM no se marcó");
			Onboarding.suppressForTest(true);
		});

		// (p) 1280×720 con la GUI a 3 y a 2, y el HUD grande
		step(0, "1280x720", () -> mc.getWindow().setWindowedSize(1280, 720));
		step(15, "19-g3-hud", () -> {
			window(mc, "19-g3-hud");
			shot(mc, "19-g3-hud");
			layout("19-g3-hud");
			key(GLFW.GLFW_KEY_TAB, PRESS);
		});
		step(8, "19-g3-fila-tab", () -> {
			shot(mc, "19-g3-fila-tab");
			key(GLFW.GLFW_KEY_TAB, RELEASE);
			mc.options.getGuiScale().setValue(2);
			mc.onResolutionChanged();   // como hace la pantalla de vídeo: que la escala nueva se aplique ya
		});
		step(10, "19-g2-hud", () -> {
			window(mc, "19-g2-hud");
			shot(mc, "19-g2-hud");
			layout("19-g2-hud");
			s.tamano = 1;
		});
		step(5, "19-hud-grande", () -> {
			shot(mc, "19-hud-grande");
			layout("19-hud-grande");
			s.tamano = 0;
			mc.options.getGuiScale().setValue(0);
			mc.onResolutionChanged();
			mc.getWindow().setWindowedSize(854, 480);
		});

		// (q) accesibilidad: contraste alto y menos movimiento
		step(15, "contraste", () -> {
			s.contraste = OpeSettings.TriState.SI;
			s.menosMovimiento = true;
		});
		step(5, "20-contraste", () -> {
			shot(mc, "20-contraste");
			key(Q, PRESS);
		});
		step(4, "20-contraste-room", () -> {
			shot(mc, "20-contraste-room");
			key(Q, RELEASE);
		});
		step(10, "ajustes de fábrica", OpeSettings::reset);

		// (r) fin: resumen y cerrar el juego
		step(0, "fin", () -> {
			OpeOpeMod.LOG.info("AUTOTEST resumen ok={} fallos={}", oks, fails);
			OpeOpeMod.LOG.info("AUTOTEST terminado");
			stopAt = t + 40;
		});
	}

	// ------------------------------------------------------------------ fase B (k2): Takt sobre lo cortado

	/**
	 * (k2) Cortes recordados, vista previa, arrancar, R R, fantasma, posar, lanzar, cráter y cueva; y (k3) los casos que
	 * encontró la revisión: un trozo que sube aunque tenga algo encima, lo que pisas no se arranca, nadie queda encerrado
	 * al devolver un trozo y dos trozos posados a la vez. Rehace su propio trozo de suelo (x gx0−16..gx0+16,
	 * z gz0−34..gz0+2) con su propio ROOM (y, para los impactos, todo el campo de alrededor: x gx0−30..gx0+30,
	 * z gz0−62..gz0+2) y al final deja el ROOM de (k) como estaba, para (l). El jugador está en supervivencia desde (a).
	 */
	private static void planSuelo(MinecraftClient mc, IntegratedServer srv, UUID id) {
		final int R = GLFW.GLFW_KEY_R, V = GLFW.GLFW_KEY_V, PRESS = GLFW.GLFW_PRESS, RELEASE = GLFW.GLFW_RELEASE;
		final int LEFT = GLFW.GLFW_MOUSE_BUTTON_LEFT;
		// los avisos de Takt (aquí LAND es el de Takt, no el del ROOM)
		final int TEAR_START = Payloads.TaktFx.TEAR_START, DETACH = Payloads.TaktFx.DETACH, CANCEL = Payloads.TaktFx.CANCEL;
		final int LAND = Payloads.TaktFx.LAND, THROW = Payloads.TaktFx.THROW, IMPACT = Payloads.TaktFx.IMPACT;

		step(10, "suelo: preparar", () -> {
			srvA = -1;
			server(srv, id, (sp, pw) -> {
				ServerWorld w = sp.getServerWorld();
				savedRoom = pw.room;
				savedPos = sp.getPos();
				patch(w, gx0 - 16, gx0 + 16, gz0 - 34, gz0 + 2, gfy);
				for (Entity e : w.getEntitiesByClass(Entity.class, new Box(gx0 - 16, gfy - 14, gz0 - 34, gx0 + 17, gfy + 27, gz0 + 3),
					en -> en instanceof PieceEntity || en instanceof ItemEntity)) e.discard();
				pw.room = new Room(w.getRegistryKey(), new Vec3d(gx0 + 0.5, gfy + 3, gz0 - 9.5), 14);
				pw.room.state = Room.State.FIXED;
				PowerManager.broadcastRoom(sp, 0, pw.room);
				GroundCuts.clearOwner(sp.getUuid());   // (k) quitó los de la fase A; esto quita los que haya habido desde entonces
				sp.networkHandler.requestTeleport(gx0 + 0.5, gfy + 1, gz0 + 0.5, 180f, 15.1f);
				srvA = 1;
			});
		});
		waitFor("suelo: preparado", 40, "no se preparó el terreno", () -> srvA == 1 && room() != null && Math.abs(radius() - 14) < 0.01, null);

		step(2, "suelo: cortes", () -> {
			srvB = -1;
			server(srv, id, (sp, pw) -> {
				int cz = gz0 - 6;
				Vec3d nx = new Vec3d(1, 0, 0), nz = new Vec3d(0, 0, 1);
				Vec3d[][] pl = {{nx, new Vec3d(gx0 - 2, gfy, cz)}, {nx, new Vec3d(gx0 + 3, gfy, cz)},
					{nz, new Vec3d(gx0, gfy, cz - 2)}, {nz, new Vec3d(gx0, gfy, cz + 3)}};
				for (Vec3d[] q : pl) {
					CutEngine.cut(sp, List.of(new CutEngine.Plane(q[0], q[1])), CutEngine.Region.of(pw.room), false, 0, 0f, true, true);
				}
				srvB = GroundCuts.count(sp.getUuid());
			});
		});
		waitFor("suelo: cortes recordados", 40, "el cliente no tiene los 4 cortes",
			() -> srvB >= 0 && ClientState.groundCutCount() >= 4, () -> {
				check(srvB == 4, "el suelo recuerda los 4 cortes", "el servidor tiene " + srvB);
				shot(mc, "14b-suelo-a-cortes");
			});

		// el plan no depende del orden de los cortes (el mismo cálculo que la vista previa, en el cliente)
		step(1, "suelo: plan determinista", () -> {
			BlockPos hit = new BlockPos(gx0, gfy, gz0 - 6);
			BlockPos aimS = ChunkPlanner.surfaceAim(mc.world, hit);
			ClientState.RoomInfo r = ClientState.ownRoomAt(Vec3d.ofCenter(hit));
			List<GroundCut> near = aimS == null ? List.of() : ChunkPlanner.near(ClientState.ownGroundCuts(), mc.player.getUuid(),
				mc.world.getRegistryKey().getValue(), ChunkPlanner.aimPoint(aimS), mc.world.getTime());
			List<GroundCut> rev = new ArrayList<>(near);
			Collections.reverse(rev);
			ChunkPlan a = r == null ? ChunkPlan.EMPTY : ChunkPlanner.plan(mc.world, hit, 6, near, r.center(), (double) r.radius(), null);
			ChunkPlan b = r == null ? ChunkPlan.EMPTY : ChunkPlanner.plan(mc.world, hit, 6, rev, r.center(), (double) r.radius(), null);
			check(a.closed && !a.face && a.blocks.length == 30 && a.usedCuts.length == 4 && Arrays.equals(a.blocks, b.blocks)
					&& Arrays.equals(a.riders, b.riders) && Arrays.equals(a.usedCuts, b.usedCuts),
				"el plan es el mismo con los cortes en otro orden (30 bloques, 4 cortes)",
				a.blocks.length + "/" + b.blocks.length + " bloques, cortes " + Arrays.toString(a.usedCuts) + " / " + Arrays.toString(b.usedCuts));
		});

		// vista previa y tamaño (R mantenida, nada en Takt)
		step(10, "suelo: R", () -> key(R, PRESS));
		step(6, "suelo: rueda +", () -> wheel(1));
		step(2, "suelo: rueda + mira", () -> {
			check(Math.abs(ClientState.chunkSize - 3.5) < 1e-6, "R + rueda agranda el trozo", "tamaño " + fmt(ClientState.chunkSize));
			wheel(-1);
		});
		step(2, "suelo: rueda - mira", () -> check(Math.abs(ClientState.chunkSize - 3.0) < 1e-6, "R + rueda vuelve a 3",
			"tamaño " + fmt(ClientState.chunkSize)));
		waitFor("suelo: vista previa", 20, "no se ve la vista previa", TerrainFx::previewShown, () -> {
			check(TerrainFx.previewClosed() && TerrainFx.previewCount() == 30, "vista previa: el trozo cerrado, 30 bloques",
				"cerrado " + TerrainFx.previewClosed() + ", " + TerrainFx.previewCount() + " bloques");
			shot(mc, "14b-suelo-b-vista-previa");
		});
		step(1, "suelo: suelta R", () -> {
			tsnap(TEAR_START);
			key(R, RELEASE);   // la rueda marcó R como usada: soltar no arranca
		});
		step(6, "suelo: suelta R mira", () -> check(!tgot(TEAR_START), "soltar R después de la rueda no arranca nada", "llegó TEAR_START"));

		// arrancar: mantener R, leer la vista previa y soltar (ventana 0: se suelta a los 4 ticks)
		step(4, "suelo: arranca", () -> {
			tsnap(TEAR_START);
			tsnap(DETACH);
			key(R, PRESS);
		});
		step(14, "suelo: mantiene", () -> {});   // 700 ms > toqueR: soltar no es un toque rápido
		waitFor("suelo: vista previa 2", 20, "no se ve la vista previa", TerrainFx::previewShown, () -> key(R, RELEASE));
		waitFor("suelo: grieta", 10, "no llegó TEAR_START", () -> tgot(TEAR_START), () -> {
			int espera = ticksOf(TEAR_START);
			check(espera == OpeConfig.TAKT_TEAR_TICKS, "mantener R y soltar arranca lo que se ve (4 ticks de grieta)", "espera " + espera);
			shot(mc, "14b-suelo-c-grieta");
		});
		waitFor("suelo: se suelta", 20, "no llegó DETACH", () -> tgot(DETACH), () -> chunkId = entityOf(DETACH));
		step(2, "14b-suelo-d", () -> shot(mc, "14b-suelo-d-tiembla"));
		step(10, "14b-suelo-e", () -> shot(mc, "14b-suelo-e-sube"));
		BooleanSupplier inHand = () -> held(chunkId) && mc.world.getEntityById(chunkId) instanceof PieceEntity pe
			&& (pe.flags() & PieceEntity.F_CHUNK) != 0 && pe.blocks.size() == 30;
		waitFor("suelo: en la mano", 60, "el trozo no llegó a Takt", inHand, () -> {
			if (inHand.getAsBoolean()) ok("Takt arranca el trozo cerrado (30 bloques)");
		});
		step(0, "suelo: hueco", () -> {
			srvA = -1;
			srvB = -1;
			server(srv, id, (sp, pw) -> {
				srvB = GroundCuts.count(sp.getUuid());
				srvA = count(sp.getServerWorld(), gx0 - 2, gfy - 3, gz0 - 8, gx0 + 2, gfy, gz0 - 4, true);
			});
		});
		waitFor("suelo: hueco medido", 20, null, () -> srvA >= 0 && srvB >= 0, () -> {
			check(srvA == 30, "el hueco es el del trozo (30)", "aire " + srvA);
			check(srvB == 0, "el arranque gasta los 4 cortes que lo forman", "quedan " + srvB);
		});

		// R R: el primer toque empieza a arrancar; el segundo llega dentro de su ventana y lo deshace antes de que se
		// suelte, y solo levanta lo suelto
		step(2, "suelo: R R mira", () -> srv.execute(() -> look(srv, id, 180f, 5.8f)));
		step(10, "suelo: R R", () -> {
			tsnap(TEAR_START);
			tsnap(DETACH);
			tsnap(CANCEL);
			key(R, PRESS);
		});
		step(2, "suelo: R R 1", () -> key(R, RELEASE));
		step(2, "suelo: R R 2", () -> key(R, PRESS));
		step(2, "suelo: R R 2 suelta", () -> key(R, RELEASE));
		waitFor("suelo: R R deshace", 30, "R R no deshizo el arranque", () -> tgot(CANCEL), () ->
			check(tgot(TEAR_START) && !tgot(DETACH), "R R lo deshace antes de que se suelte",
				"TEAR_START " + tgot(TEAR_START) + ", DETACH " + tgot(DETACH)));
		step(20, "suelo: R R mide", () -> {
			srvA = -1;
			server(srv, id, (sp, pw) -> srvA = count(sp.getServerWorld(), gx0 - 5, gfy - 4, gz0 - 21, gx0 + 5, gfy, gz0 - 11, true));
		});
		waitFor("suelo: R R medido", 20, null, () -> srvA >= 0, () -> {
			check(srvA == 0, "R R deja el suelo como estaba", "aire " + srvA);
			check(ClientState.heldIds.length == 1, "R R no arranca suelo", "Takt lleva " + ClientState.heldIds.length);
		});

		// fantasma y posar
		step(2, "suelo: fantasma mira", () -> srv.execute(() -> look(srv, id, 225f, 20f)));
		waitFor("suelo: fantasma", 40, "no hay fantasma", () -> TerrainFx.ghostFor(chunkId) != null, null);
		step(15, "14b-suelo-f", () -> shot(mc, "14b-suelo-f-fantasma"));
		step(0, "suelo: posar R", () -> key(R, PRESS));
		step(5, "suelo: posar", () -> {
			ghost = TerrainFx.ghostFor(chunkId);
			ghostSet = mc.world.getEntityById(chunkId) instanceof PieceEntity pe ? List.copyOf(pe.blocks.entries) : List.of();
			tsnap(LAND);
			key(V, PRESS);
		});
		step(1, "suelo: posar suelta", () -> {
			key(V, RELEASE);
			key(R, RELEASE);
		});
		waitFor("suelo: posado", 100, "el trozo no se posó", () -> mc.world.getEntityById(chunkId) == null, () -> shot(mc, "14b-suelo-g-posado"));
		step(0, "suelo: posado mide", () -> {
			srvA = -1;
			BlockPos g = ghost;
			List<BlockSet.Entry> es = ghostSet;
			server(srv, id, (sp, pw) -> srvA = mismatches(sp.getServerWorld(), g, es));
		});
		waitFor("suelo: posado medido", 20, null, () -> srvA >= 0, () -> {
			Payloads.TaktFx f = TaktFxClient.LAST[LAND];
			boolean same = tgot(LAND) && f != null && ghost != null && f.x() == ghost.getX() && f.y() == ghost.getY() && f.z() == ghost.getZ();
			check(same && srvA == 0, "posar deja el trozo justo en su fantasma",
				"LAND " + (f == null ? "-" : fmt(f.x()) + " " + fmt(f.y()) + " " + fmt(f.z())) + ", fantasma " + ghost + ", "
					+ srvA + " bloques distintos");
		});

		// lanzar: cráter, sin objetos, sin perder bloques y con las cuentas del servidor. El cráter, su borde y los terrones
		// que salen volando llegan más allá de la parcela de (k2): se mide en todo el campo, rehecho llano alrededor
		step(5, "suelo: lanzar mide", () -> {
			srvA = -1;
			srvB = -1;
			server(srv, id, (sp, pw) -> {
				ServerWorld w = sp.getServerWorld();
				patchFieldRing(w);
				clearField(w, true);
				dropped0 = PieceEntity.DROPPED;
				crater0 = Crater.last;
				srvB = fieldItems(w);
				srvA = fieldCount(w);
			});
		});
		waitFor("suelo: lanzar medido", 40, null, () -> srvA >= 0 && srvB >= 0, () -> {
			solid0 = srvA;
			items0 = srvB;
		});
		step(0, "suelo: lanzar mira", () -> srv.execute(() -> look(srv, id, 162.9f, 6.8f)));
		step(10, "suelo: lanzar arranca", () -> {
			tsnap(TEAR_START);
			tsnap(DETACH);
			key(R, PRESS);
		});
		step(2, "suelo: lanzar toque", () -> key(R, RELEASE));
		waitFor("suelo: lanzar grieta", 10, "no llegó TEAR_START", () -> tgot(TEAR_START), () -> {
			int win = Math.max(OpeConfig.TAKT_TEAR_TICKS, (int) Math.ceil(OpeSettings.get().dobleToque / 50.0) + 1);
			int espera = ticksOf(TEAR_START);
			check(espera == win, "un toque espera la ventana del doble toque (" + win + " ticks)", "espera " + espera);
		});
		waitFor("suelo: lanzar se suelta", 30, "no llegó DETACH", () -> tgot(DETACH), () -> chunkId = entityOf(DETACH));
		waitFor("suelo: lanzar en la mano", 60, "el trozo no llegó a Takt", () -> held(chunkId), null);
		step(20, "suelo: lanzar apunta", () -> srv.execute(() -> look(srv, id, 171.0f, 4.8f)));
		step(12, "suelo: lanzar clic", () -> {
			tsnap(THROW);
			tsnap(IMPACT);
			mouse(LEFT, PRESS);
		});
		step(1, "suelo: lanzar suelta", () -> mouse(LEFT, RELEASE));   // suelto antes de la ráfaga de 6 ticks
		step(2, "14b-suelo-h", () -> shot(mc, "14b-suelo-h-vuela"));
		waitFor("suelo: impacto", 80, "el trozo no impactó", () -> tgot(IMPACT), () -> {
			Payloads.TaktFx f = TaktFxClient.LAST[IMPACT];
			if (!tgot(IMPACT) || f == null) return;   // el FALLO ya está apuntado
			OpeOpeMod.LOG.info("AUTOTEST suelo impacto nivel {} cráter {} en {} {} {} (desde la escena), arado {}", f.state(), fmt(f.a()),
				fmt(f.x() - gx0), fmt(f.y() - gfy), fmt(f.z() - gz0), fmt(f.datum(Payloads.TaktFx.I_PLOW, 0) / 100.0));
			check(tgot(THROW) && f.state() >= 2 && f.a() > 0, "un trozo lanzado abre un cráter", "nivel " + f.state() + ", radio " + fmt(f.a()));
		});
		step(4, "14b-suelo-i", () -> shot(mc, "14b-suelo-i-crater"));
		settleField(srv, id, "suelo: terrones");
		step(0, "suelo: cráter mide", () -> {
			srvA = -1;
			srvB = -1;
			srvC = -1;
			server(srv, id, (sp, pw) -> {
				ServerWorld w = sp.getServerWorld();
				Crater.Result c = Crater.last;
				srvC = (c != null && c != crater0 && c.balanced() ? 1 : 0) | (PieceEntity.DROPPED == dropped0 ? 2 : 0);
				logCrater(c);
				srvB = fieldItems(w);
				srvA = fieldCount(w);
			});
		});
		waitFor("suelo: cráter medido", 20, null, () -> srvA >= 0 && srvB >= 0 && srvC >= 0, () -> {
			shot(mc, "14b-suelo-j-crater-posado");
			check(srvB == items0, "el impacto no suelta objetos", (srvB - items0) + " objetos nuevos");
			check(srvA == solid0, "el impacto no pierde bloques", solid0 + " → " + srvA);
			check((srvC & 1) != 0, "el cráter amontona, devuelve o lanza todo lo que cava", "cuentas del cráter distintas");
			check((srvC & 2) != 0, "ningún bloque cae como objeto al asentarse", "PieceEntity.DROPPED cambió");
		});

		// cueva: con techo encima, el plan sigue el suelo de la cueva (solo el cálculo del cliente; no se arranca nada). La
		// parcela se rehace antes: el borde del cráter de antes puede llegar hasta aquí
		step(2, "suelo: cueva", () -> {
			srvA = -1;
			server(srv, id, (sp, pw) -> {
				ServerWorld w = sp.getServerWorld();
				patch(w, gx0 - 16, gx0 + 16, gz0 - 34, gz0 + 2, gfy);
				BlockPos.Mutable m = new BlockPos.Mutable();
				for (int x = gx0 + 4; x <= gx0 + 8; x++)
					for (int z = gz0 - 14; z <= gz0 - 10; z++)
						for (int y = gfy - 6; y <= gfy - 4; y++) w.setBlockState(m.set(x, y, z), Blocks.AIR.getDefaultState(), 2 | 16);
				srvA = 1;
			});
		});
		waitFor("suelo: cueva hecha", 40, "el cliente no ve la cueva",
			() -> srvA == 1 && mc.world.getBlockState(new BlockPos(gx0 + 6, gfy - 6, gz0 - 12)).isAir(), () -> {
				BlockPos hit = new BlockPos(gx0 + 6, gfy - 7, gz0 - 12);
				ClientState.RoomInfo r = ClientState.ownRoomAt(Vec3d.ofCenter(hit));
				ChunkPlan p = r == null ? ChunkPlan.EMPTY : ChunkPlanner.plan(mc.world, hit, 6, List.of(), r.center(), (double) r.radius(), null);
				int top = Integer.MIN_VALUE;
				for (long l : p.blocks) top = Math.max(top, BlockPos.unpackLongY(l));
				check(!p.isEmpty() && !p.face && top <= gfy - 7, "en una cueva Takt sigue el suelo que miras",
					p.size() + " bloques, cara " + p.face + ", el más alto en " + (p.blocks.length == 0 ? "-" : String.valueOf(top - gfy)));
			});

		planRocota(mc, srv, id);

		planRevision(mc, srv, id);

		// dejarlo como estaba para (l)
		step(5, "suelo: fin", () -> server(srv, id, (sp, pw) -> {
			pw.room = savedRoom;
			PowerManager.broadcastRoom(sp, 0, savedRoom);
			if (savedPos != null) sp.networkHandler.requestTeleport(savedPos.x, savedPos.y, savedPos.z, 0f, 10f);
		}));
	}

	/**
	 * (k2) La rocota: una roca de 4×4×4 de andesita pulida (166 t) que se suelta a 21 bloques del suelo cae sola, revienta
	 * (nivel 3), abre un cráter de su tamaño y se queda metida en él; lo cavado acaba en el borde, de vuelta o en los
	 * terrones que salen volando, y todo vuelve a ser bloques sin objetos. En el cliente, se la oye caer. Sobre el campo
	 * recién rehecho llano, mirándola desde 22 bloques.
	 */
	private static void planRocota(MinecraftClient mc, IntegratedServer srv, UUID id) {
		final int IMPACT = Payloads.TaktFx.IMPACT;
		// la esquina de la roca desde la escena: cae sobre x 0, z −30 desde y +22..+25, dentro del aire que rehace patch
		// (hasta y +26: más arriba puede haber una ladera o un árbol según el mundo)
		final int RX = -2, RY = 22, RZ = -32, SIDE = 4;
		step(5, "rocota: preparar", () -> {
			srvA = -1;
			srvB = -1;
			server(srv, id, (sp, pw) -> {
				ServerWorld w = sp.getServerWorld();
				Takt.dropAll(sp, pw, false);
				patch(w, gx0 - 16, gx0 + 16, gz0 - 34, gz0 + 2, gfy);
				patchFieldRing(w);
				clearField(w, true);
				sp.networkHandler.requestTeleport(gx0 + 0.5, gfy + 1, gz0 - 7.5, 180f, -8f);
				dropped0 = PieceEntity.DROPPED;
				crater0 = Crater.last;
				srvB = fieldItems(w);
				srvA = fieldCount(w);
			});
		});
		waitFor("rocota: preparado", 60, "el cliente no ve el campo rehecho", () -> srvA >= 0 && srvB >= 0
			&& mc.world.getBlockState(new BlockPos(gx0, gfy, gz0 - 30)).isOf(Blocks.GRASS_BLOCK) && mc.player.getZ() < gz0 - 6, () -> {
			solid0 = srvA + SIDE * SIDE * SIDE;   // la roca viene de fuera del campo
			items0 = srvB;
		});
		step(4, "rocota: suelta", () -> {
			tsnap(IMPACT);
			roars0 = TaktFxClient.ROARS;
			srvC = -1;
			server(srv, id, (sp, pw) -> {
				BlockSet set = new BlockSet();
				for (int x = 0; x < SIDE; x++)
					for (int y = 0; y < SIDE; y++)
						for (int z = 0; z < SIDE; z++) set.add(x, y, z, Blocks.POLISHED_ANDESITE.getDefaultState(), null);
				set.normalize();
				PieceEntity pe = PieceEntity.spawn(sp.getServerWorld(), set, new Vec3d(gx0 + RX, gfy + RY, gz0 + RZ), null, sp.getUuid());
				pe.release(Vec3d.ZERO, false);
				srvC = pe.getId();
			});
		});
		step(18, "14b-suelo-k", () -> shot(mc, "14b-suelo-k-rocota-cae"));
		waitFor("rocota: impacto", 100, "la rocota no reventó al caer", () -> tgot(IMPACT), () -> {
			Payloads.TaktFx f = TaktFxClient.LAST[IMPACT];
			if (!tgot(IMPACT) || f == null) return;
			OpeOpeMod.LOG.info("AUTOTEST rocota impacto nivel {} cráter {} en {} {} {} (desde la escena), energía {} MJ", f.state(), fmt(f.a()),
				fmt(f.x() - gx0), fmt(f.y() - gfy), fmt(f.z() - gz0), fmt(f.b()));
			check(f.entityId() == srvC && f.state() == 3 && f.a() >= 3, "una rocota soltada desde lo alto revienta y abre un cráter de su tamaño",
				"pieza " + f.entityId() + " (la rocota es " + srvC + "), nivel " + f.state() + ", radio " + fmt(f.a()));
			check(TaktFxClient.ROARS > roars0, "se oye caer la rocota (el rugido del aire)", "no empezó ningún rugido");
		});
		step(2, "14b-suelo-l", () -> shot(mc, "14b-suelo-l-rocota-golpe"));
		step(20, "14b-suelo-m", () -> shot(mc, "14b-suelo-m-rocota-nube"));
		settleField(srv, id, "rocota: terrones");
		step(0, "rocota: mide", () -> {
			srvA = -1;
			srvB = -1;
			srvC = -1;
			server(srv, id, (sp, pw) -> {
				ServerWorld w = sp.getServerWorld();
				Crater.Result c = Crater.last;
				logCrater(c);
				int sunk = 0;
				BlockPos.Mutable m = new BlockPos.Mutable();
				for (int x = gx0 - 8; x <= gx0 + 8; x++)
					for (int z = gz0 - 38; z <= gz0 - 22; z++)
						for (int y = gfy - 10; y <= gfy; y++) if (w.getBlockState(m.set(x, y, z)).isOf(Blocks.POLISHED_ANDESITE)) sunk++;
				OpeOpeMod.LOG.info("AUTOTEST rocota: {} bloques de la roca por debajo del suelo de antes", sunk);
				srvC = (c != null && c != crater0 && c.balanced() ? 1 : 0) | (PieceEntity.DROPPED == dropped0 ? 2 : 0) | (c != null && c.seated() ? 4 : 0)
					| (sunk >= SIDE * SIDE ? 8 : 0);
				srvB = fieldItems(w);
				srvA = fieldCount(w);
			});
		});
		waitFor("rocota: medido", 20, null, () -> srvA >= 0 && srvB >= 0 && srvC >= 0, () -> {
			shot(mc, "14b-suelo-n-rocota-crater");
			check(srvB == items0, "la rocota no suelta objetos", (srvB - items0) + " objetos nuevos");
			check(srvA == solid0, "la rocota no pierde ni inventa bloques", solid0 + " → " + srvA);
			check((srvC & 1) != 0, "su cráter amontona, devuelve o lanza todo lo que cava", "cuentas del cráter distintas");
			check((srvC & 2) != 0, "ningún bloque de la rocota ni del cráter cae como objeto", "PieceEntity.DROPPED cambió");
			check((srvC & 12) == 12, "la rocota se queda metida en su cráter", "asentada " + ((srvC & 4) != 0) + ", al menos una capa hundida "
				+ ((srvC & 8) != 0));
		});
	}

	/** El campo de los impactos: x gx0−30..gx0+30, z gz0−62..gz0+2, y como patch (gfy−14..gfy+26). */
	private static Box fieldBox() {
		return new Box(gx0 - 30, gfy - 14, gz0 - 62, gx0 + 31, gfy + 27, gz0 + 3);
	}

	/** En el hilo del servidor: los bloques que no son aire en el campo. */
	private static int fieldCount(ServerWorld w) {
		return count(w, gx0 - 30, gfy - 14, gz0 - 62, gx0 + 30, gfy + 26, gz0 + 2, false);
	}

	/** En el hilo del servidor: los objetos tirados en el campo. */
	private static int fieldItems(ServerWorld w) {
		return items(w, fieldBox());
	}

	/** En el hilo del servidor: rehace llano lo del campo que queda fuera de la parcela de (k2) (x ±16, z gz0−34..gz0+2). */
	private static void patchFieldRing(ServerWorld w) {
		patch(w, gx0 - 30, gx0 - 17, gz0 - 62, gz0 + 2, gfy);
		patch(w, gx0 + 17, gx0 + 30, gz0 - 62, gz0 + 2, gfy);
		patch(w, gx0 - 16, gx0 + 16, gz0 - 62, gz0 - 35, gfy);
	}

	/**
	 * En el hilo del servidor: fuera del campo los objetos y los animales (la onda del golpe no mata a nadie que suelte
	 * nada) y, con pieces, también las piezas sueltas.
	 */
	private static void clearField(ServerWorld w, boolean pieces) {
		for (Entity e : w.getEntitiesByClass(Entity.class, fieldBox(),
			en -> en instanceof ItemEntity || en instanceof MobEntity || (pieces && en instanceof PieceEntity))) e.discard();
	}

	/** Espera (como mucho 15 s) a que no quede ninguna pieza suelta en el campo: los terrones de un cráter aún vuelan. */
	private static void settleField(IntegratedServer srv, UUID id, String name) {
		step(0, name, () -> srvC = -1);
		repeatUntil(name + " posados", 5, 300, () -> server(srv, id, (sp, pw) ->
			srvC = sp.getServerWorld().getEntitiesByClass(PieceEntity.class, fieldBox(), e -> true).size()), () -> srvC == 0);
	}

	/** En el hilo del servidor: las cuentas del último cráter. */
	private static void logCrater(Crater.Result c) {
		if (c == null) return;
		OpeOpeMod.LOG.info("AUTOTEST cráter radio {} hondo {} arado {}: {} cavados = {} al borde + {} devueltos + {} bajo la roca + {} volando; asentada {}",
			fmt(c.radius()), fmt(c.depth()), fmt(c.plow()), c.removed(), c.piled(), c.putBack(), c.backfill(), c.ejected(), c.seated());
	}

	/**
	 * (k3) Lo que encontró la revisión, sobre terreno recién rehecho y con el ROOM de (k2): con un bloque flotando encima
	 * el trozo sube igual, lo que pisas al soltarse no se arranca, quien está en el hueco sube encima cuando el trozo
	 * vuelve y dos trozos posados a la vez quedan cada uno en su fantasma.
	 */
	private static void planRevision(MinecraftClient mc, IntegratedServer srv, UUID id) {
		final int R = GLFW.GLFW_KEY_R, V = GLFW.GLFW_KEY_V, PRESS = GLFW.GLFW_PRESS, RELEASE = GLFW.GLFW_RELEASE;
		final int TEAR_START = Payloads.TaktFx.TEAR_START, DETACH = Payloads.TaktFx.DETACH, HOME = Payloads.TaktFx.HOME;
		final int DENY = Payloads.TaktFx.DENY;

		// terreno nuevo y un tronco flotando sobre una columna que se va a arrancar (no toca el suelo: no va con el trozo)
		step(5, "revisión: preparar", () -> {
			srvA = -1;
			server(srv, id, (sp, pw) -> {
				ServerWorld w = sp.getServerWorld();
				Takt.dropAll(sp, pw, false);
				patch(w, gx0 - 16, gx0 + 16, gz0 - 34, gz0 + 2, gfy);
				for (Entity e : w.getEntitiesByClass(Entity.class, new Box(gx0 - 16, gfy - 14, gz0 - 34, gx0 + 17, gfy + 27, gz0 + 3),
					en -> en instanceof PieceEntity || en instanceof ItemEntity)) e.discard();
				GroundCuts.clearOwner(sp.getUuid());
				w.setBlockState(new BlockPos(gx0 + 2, gfy + 2, gz0 - 6), Blocks.OAK_LOG.getDefaultState(), 2 | 16);
				sp.networkHandler.requestTeleport(gx0 + 0.5, gfy + 1, gz0 + 0.5, 180f, 15.1f);
				srvA = 1;
			});
		});
		waitFor("revisión: preparado", 60, "el cliente no ve el terreno nuevo",
			() -> srvA == 1 && mc.world.getBlockState(new BlockPos(gx0 + 2, gfy + 2, gz0 - 6)).isOf(Blocks.OAK_LOG)
				&& mc.world.getBlockState(new BlockPos(gx0, gfy, gz0 - 6)).isOf(Blocks.GRASS_BLOCK)
				&& ClientState.heldIds.length == 0 && mc.player.getZ() > gz0, null);

		// un toque; mientras dura la grieta el jugador se pone encima de lo que se va a arrancar
		step(10, "revisión: toque", () -> {
			tsnap(TEAR_START);
			tsnap(DETACH);
			key(R, PRESS);
		});
		step(2, "revisión: toque suelta", () -> key(R, RELEASE));
		waitFor("revisión: grieta", 10, "no llegó TEAR_START", () -> tgot(TEAR_START), () ->
			server(srv, id, (sp, pw) -> sp.networkHandler.requestTeleport(gx0 + 0.5, gfy + 1, gz0 - 5.5, 180f, 15.1f)));
		waitFor("revisión: se suelta", 40, "no llegó DETACH", () -> tgot(DETACH), () -> chunkId = entityOf(DETACH));
		BooleanSupplier risen = () -> mc.world.getEntityById(chunkId) instanceof PieceEntity pe && pe.getY() >= gfy + 1.5;
		waitFor("revisión: sube", 60, "el trozo no subió: sigue en su hueco", risen, () -> {
			if (risen.getAsBoolean()) ok("el trozo sube aunque tenga un bloque encima");
			shot(mc, "14c-revision-a-sube");
		});
		step(0, "revisión: pies", () -> {
			srvA = -1;
			server(srv, id, (sp, pw) -> {
				ServerWorld w = sp.getServerWorld();
				boolean ground = !w.getBlockState(new BlockPos(gx0, gfy, gz0 - 6)).isAir();
				boolean stands = sp.getY() >= gfy + 0.99;
				boolean log = w.getBlockState(new BlockPos(gx0 + 2, gfy + 2, gz0 - 6)).isOf(Blocks.OAK_LOG);
				boolean under = w.getBlockState(new BlockPos(gx0 + 2, gfy, gz0 - 6)).isAir();
				OpeOpeMod.LOG.info("AUTOTEST revisión pies: suelo {}, jugador en y {}, tronco {}, arrancado debajo del tronco {}",
					ground, fmt(sp.getY() - gfy), log, under);
				srvA = (ground ? 1 : 0) | (stands ? 2 : 0) | (log ? 4 : 0) | (under ? 8 : 0);
			});
		});
		waitFor("revisión: pies medido", 20, null, () -> srvA >= 0, () -> {
			check((srvA & 3) == 3, "lo que pisas no se arranca aunque te muevas durante la grieta",
				"suelo " + ((srvA & 1) != 0) + ", de pie " + ((srvA & 2) != 0));
			check((srvA & 12) == 12, "el bloque de encima se queda y el suelo de debajo se va con el trozo",
				"tronco " + ((srvA & 4) != 0) + ", arrancado debajo " + ((srvA & 8) != 0));
		});
		step(60, "revisión: choca otra vez", () -> {
			srvB = -1;
			server(srv, id, (sp, pw) -> srvB = sp.getServerWorld().getEntityById(chunkId) instanceof PieceEntity pe ? (pe.noClip ? 0 : 1) : 2);
		});
		waitFor("revisión: choca medido", 20, null, () -> srvB >= 0, () ->
			check(srvB == 1, "fuera del hueco el trozo vuelve a chocar con el mundo", srvB == 0 ? "sigue atravesando bloques" : "ya no está"));

		// dentro del hueco cuando el trozo vuelve a su sitio (Unir todo): sube encima, no queda encerrado. El disco cambia en
		// cada partida (el mundo de la prueba es otro cada vez): a veces lo arrancado alrededor de los pies es todo de un
		// bloque de hondo, así que se baja al fondo de la columna más honda que haya (y, entre esas, la más cercana)
		step(2, "revisión: al hueco", () -> {
			srvA = -1;
			server(srv, id, (sp, pw) -> {
				ServerWorld w = sp.getServerWorld();
				dropped0 = PieceEntity.DROPPED;
				relocated0 = PieceEntity.RELOCATED;
				BlockSet.Entry best = null;
				double bd = Double.MAX_VALUE;
				if (w.getEntityById(chunkId) instanceof PieceEntity pe && pe.origin != null) {
					for (BlockSet.Entry e : pe.blocks.entries) {
						if (pe.blocks.contains(e.x(), e.y() - 1, e.z())) continue;   // solo el fondo de cada columna
						if (pe.origin.getX() + e.x() == gx0 + 2 && pe.origin.getZ() + e.z() == gz0 - 6) continue;   // la del tronco no
						double dx = pe.origin.getX() + e.x() + 0.5 - sp.getX(), dz = pe.origin.getZ() + e.z() + 0.5 - sp.getZ();
						double d = 1000.0 * e.y() + dx * dx + dz * dz;   // primero lo más hondo, luego lo más cerca
						if (d < bd) {
							bd = d;
							best = e;
						}
					}
					if (best != null) {
						int deep = 0;
						while (pe.blocks.contains(best.x(), best.y() + deep, best.z())) deep++;
						OpeOpeMod.LOG.info("AUTOTEST revisión al hueco: {} bloques, columna de {} de hondo", pe.blocks.size(), deep);
						sp.networkHandler.requestTeleport(pe.origin.getX() + best.x() + 0.5, pe.origin.getY() + best.y(),
							pe.origin.getZ() + best.z() + 0.5, 180f, 15.1f);
					}
				}
				srvA = best != null ? 1 : 0;
			});
		});
		waitFor("revisión: en el hueco", 40, "no se pudo bajar al hueco", () -> srvA == 1 && mc.player.getY() < gfy + 0.5, null);
		step(5, "revisión: unir", () -> {
			tsnap(HOME);
			server(srv, id, (sp, pw) -> Takt.order(sp, pw, Actions.ORDER_UNITE));
		});
		waitFor("revisión: vuelve", 100, "el trozo no volvió a su sitio", () -> tgot(HOME) && mc.world.getEntityById(chunkId) == null, null);
		step(5, "revisión: unir mide", () -> {
			srvA = -1;
			server(srv, id, (sp, pw) -> {
				ServerWorld w = sp.getServerWorld();
				int air = count(w, gx0 - 6, gfy - 4, gz0 - 12, gx0 + 6, gfy, gz0, true);
				boolean up = sp.getY() >= gfy + 0.99;
				int moved = (PieceEntity.RELOCATED - relocated0) + (PieceEntity.DROPPED - dropped0);
				OpeOpeMod.LOG.info("AUTOTEST revisión unir: aire {}, jugador en y {}, {} recolocados o soltados", air, fmt(sp.getY() - gfy), moved);
				srvA = (air == 0 ? 1 : 0) | (up ? 2 : 0) | (moved == 0 ? 4 : 0);
			});
		});
		waitFor("revisión: unir medido", 20, null, () -> srvA >= 0, () -> {
			check((srvA & 2) != 0, "quien está en el hueco sube encima cuando el trozo vuelve", "sigue dentro");
			check((srvA & 5) == 5, "el trozo vuelve entero a su sitio, sin bloques recolocados",
				"hueco cerrado " + ((srvA & 1) != 0) + ", nada recolocado " + ((srvA & 4) != 0));
		});

		// dos trozos a la vez: cada uno en su fantasma, sin pisarse
		step(5, "revisión: dos, preparar", () -> {
			srvA = -1;
			server(srv, id, (sp, pw) -> {
				sp.getServerWorld().setBlockState(new BlockPos(gx0 + 2, gfy + 2, gz0 - 6), Blocks.AIR.getDefaultState(), 2 | 16);
				sp.networkHandler.requestTeleport(gx0 + 0.5, gfy + 1, gz0 + 0.5, 180f, 15.1f);
				srvA = 1;
			});
		});
		waitFor("revisión: dos, preparado", 40, "el jugador no volvió a su sitio",
			() -> srvA == 1 && mc.player.getZ() > gz0 && ClientState.heldIds.length == 0, null);
		step(10, "revisión: dos, toque 1", () -> {
			tsnap(DETACH);
			key(R, PRESS);
		});
		step(2, "revisión: dos, toque 1 suelta", () -> key(R, RELEASE));
		waitFor("revisión: dos, suelto 1", 40, "no llegó DETACH", () -> tgot(DETACH), () -> chunkId = entityOf(DETACH));
		waitFor("revisión: dos, en la mano 1", 60, "el primer trozo no llegó a Takt", () -> held(chunkId), null);
		step(20, "revisión: dos, mira", () -> srv.execute(() -> look(srv, id, 180f, 6.5f)));
		step(10, "revisión: dos, toque 2", () -> {
			tsnap(DETACH);
			key(R, PRESS);
		});
		step(2, "revisión: dos, toque 2 suelta", () -> key(R, RELEASE));
		waitFor("revisión: dos, suelto 2", 40, "no llegó el segundo DETACH", () -> tgot(DETACH), () -> chunkB = entityOf(DETACH));
		waitFor("revisión: dos, en la mano 2", 60, "Takt no lleva los dos trozos", () -> held(chunkId) && held(chunkB), null);
		step(40, "revisión: dos, quietos", () -> {
			srvA = -1;
			server(srv, id, (sp, pw) -> {
				dropped0 = PieceEntity.DROPPED;
				relocated0 = PieceEntity.RELOCATED;
				srvA = 1;
			});
		});
		waitFor("revisión: dos, fantasmas", 80, "no hay dos fantasmas",
			() -> srvA == 1 && TerrainFx.ghostFor(chunkId) != null && TerrainFx.ghostFor(chunkB) != null,
			() -> shot(mc, "14c-revision-b-dos-fantasmas"));
		step(2, "revisión: dos, posar R", () -> key(R, PRESS));
		step(5, "revisión: dos, posar", () -> {
			ghost = TerrainFx.ghostFor(chunkId);
			ghostB = TerrainFx.ghostFor(chunkB);
			ghostSet = entries(mc, chunkId);
			ghostSetB = entries(mc, chunkB);
			check(ghost != null && ghostB != null && disjoint(ghost, ghostSet, ghostB, ghostSetB), "los fantasmas de dos trozos no se pisan",
				"fantasmas " + ghost + " y " + ghostB);
			key(V, PRESS);
		});
		step(1, "revisión: dos, posar suelta", () -> {
			key(V, RELEASE);
			key(R, RELEASE);
		});
		waitFor("revisión: dos, posados", 200, "los dos trozos no se posaron",
			() -> mc.world.getEntityById(chunkId) == null && mc.world.getEntityById(chunkB) == null, () -> shot(mc, "14c-revision-c-posados"));
		step(0, "revisión: dos, mide", () -> {
			srvA = -1;
			srvB = -1;
			BlockPos g1 = ghost, g2 = ghostB;
			List<BlockSet.Entry> e1 = ghostSet, e2 = ghostSetB;
			server(srv, id, (sp, pw) -> {
				ServerWorld w = sp.getServerWorld();
				int mis = mismatches(w, g1, e1) + mismatches(w, g2, e2);
				int moved = (PieceEntity.RELOCATED - relocated0) + (PieceEntity.DROPPED - dropped0);
				OpeOpeMod.LOG.info("AUTOTEST revisión dos trozos: {} y {} bloques, {} distintos, {} recolocados o soltados",
					e1.size(), e2.size(), mis, moved);
				srvB = moved;
				srvA = mis;
			});
		});
		waitFor("revisión: dos, medido", 20, null, () -> srvA >= 0 && srvB >= 0, () -> {
			check(srvA == 0, "dos trozos posados a la vez quedan cada uno en su fantasma", srvA + " bloques distintos");
			check(srvB == 0, "al posar dos trozos no se recoloca ni se suelta ningún bloque", srvB + " bloques");
		});

		// un túnel de 2 de alto: el trozo sale de su hueco y se queda en el túnel (no se mete en la roca del techo ni la
		// atraviesa hacia la mano); al posarlo no se pierde ni se suelta nada
		step(5, "revisión: túnel, preparar", () -> {
			srvA = -1;
			server(srv, id, (sp, pw) -> {
				ServerWorld w = sp.getServerWorld();
				Takt.dropAll(sp, pw, false);
				patch(w, gx0 - 16, gx0 + 16, gz0 - 34, gz0 + 2, gfy);
				for (Entity e : w.getEntitiesByClass(Entity.class, new Box(gx0 - 16, gfy - 14, gz0 - 34, gx0 + 17, gfy + 27, gz0 + 3),
					en -> en instanceof PieceEntity || en instanceof ItemEntity)) e.discard();
				GroundCuts.clearOwner(sp.getUuid());
				BlockPos.Mutable m = new BlockPos.Mutable();
				for (int x = gx0 - 5; x <= gx0 + 5; x++)
					for (int z = gz0 - 16; z <= gz0 - 2; z++)
						for (int y = gfy - 6; y <= gfy - 5; y++) w.setBlockState(m.set(x, y, z), Blocks.AIR.getDefaultState(), 2 | 16);
				sp.networkHandler.requestTeleport(gx0 + 0.5, gfy - 6, gz0 - 4.5, 180f, 22f);
				srvC = count(w, gx0 - 8, gfy - 13, gz0 - 20, gx0 + 8, gfy, gz0 + 1, false);
				dropped0 = PieceEntity.DROPPED;
				srvA = 1;
			});
		});
		waitFor("revisión: túnel, preparado", 60, "el cliente no ve el túnel",
			() -> srvA == 1 && mc.world.getBlockState(new BlockPos(gx0, gfy - 5, gz0 - 9)).isAir()
				&& mc.world.getBlockState(new BlockPos(gx0, gfy - 7, gz0 - 9)).isOf(Blocks.STONE)
				&& Math.abs(mc.player.getY() - (gfy - 6)) < 0.1 && ClientState.heldIds.length == 0, null);
		step(10, "revisión: túnel, toque", () -> {
			tsnap(DETACH);
			key(R, PRESS);
		});
		step(2, "revisión: túnel, toque suelta", () -> key(R, RELEASE));
		waitFor("revisión: túnel, suelto", 40, "no llegó DETACH en el túnel", () -> tgot(DETACH), () -> chunkId = entityOf(DETACH));
		step(60, "revisión: túnel, fuera del hueco", () -> {
			srvA = -1;
			server(srv, id, (sp, pw) -> {
				int r = 0;
				if (sp.getServerWorld().getEntityById(chunkId) instanceof PieceEntity pe) {
					Box b = pe.getBoundingBox();
					boolean in = b.minY >= gfy - 6 - 0.05 && b.maxY <= gfy - 4 + 0.05;
					OpeOpeMod.LOG.info("AUTOTEST revisión túnel: {} bloques, base en y {}, arriba en y {}, atraviesa {}", pe.blocks.size(),
						fmt(b.minY - gfy), fmt(b.maxY - gfy), pe.noClip);
					r = 4 | (in ? 1 : 0) | (pe.noClip ? 0 : 2);
				}
				srvA = r;
			});
		});
		waitFor("revisión: túnel, medido", 20, null, () -> srvA >= 0, () -> {
			shot(mc, "14c-revision-d-tunel");
			check(srvA == 7, "en un túnel bajito el trozo sale de su hueco y se queda en el túnel, sin meterse en la roca",
				(srvA & 4) == 0 ? "ya no está" : "dentro del túnel " + ((srvA & 1) != 0) + ", choca " + ((srvA & 2) != 0));
		});
		step(2, "revisión: túnel, posar R", () -> key(R, PRESS));
		step(5, "revisión: túnel, posar", () -> key(V, PRESS));
		step(1, "revisión: túnel, posar suelta", () -> {
			key(V, RELEASE);
			key(R, RELEASE);
		});
		waitFor("revisión: túnel, posado", 200, "el trozo no se posó en el túnel", () -> mc.world.getEntityById(chunkId) == null,
			() -> shot(mc, "14c-revision-e-tunel-posado"));
		step(5, "revisión: túnel, cuenta", () -> {
			srvA = -1;
			server(srv, id, (sp, pw) -> {
				ServerWorld w = sp.getServerWorld();
				int solid = count(w, gx0 - 8, gfy - 13, gz0 - 20, gx0 + 8, gfy, gz0 + 1, false);
				int loose = items(w, new Box(gx0 - 9, gfy - 14, gz0 - 21, gx0 + 9, gfy + 2, gz0 + 2));
				int dropped = PieceEntity.DROPPED - dropped0;
				OpeOpeMod.LOG.info("AUTOTEST revisión túnel posado: bloques {} y {}, {} objetos, {} soltados", srvC, solid, loose, dropped);
				srvA = (solid == srvC ? 1 : 0) | (loose == 0 ? 2 : 0) | (dropped == 0 ? 4 : 0);
			});
		});
		waitFor("revisión: túnel, contado", 20, null, () -> srvA >= 0, () ->
			check(srvA == 7, "al posarlo en el túnel no se pierde ni se suelta ningún bloque",
				"mismos bloques " + ((srvA & 1) != 0) + ", sin objetos " + ((srvA & 2) != 0) + ", nada soltado " + ((srvA & 4) != 0)));

		// el mismo túnel con una antorcha en el suelo: el trozo (con la antorcha encima) es más alto que el túnel; no cabe
		// por ningún lado, así que vuelve a su hueco con su antorcha y avisa, en vez de quedarse encajado en el techo
		step(5, "revisión: túnel alto, preparar", () -> {
			srvA = -1;
			server(srv, id, (sp, pw) -> {
				ServerWorld w = sp.getServerWorld();
				Takt.dropAll(sp, pw, false);
				patch(w, gx0 - 16, gx0 + 16, gz0 - 34, gz0 + 2, gfy);
				for (Entity e : w.getEntitiesByClass(Entity.class, new Box(gx0 - 16, gfy - 14, gz0 - 34, gx0 + 17, gfy + 27, gz0 + 3),
					en -> en instanceof PieceEntity || en instanceof ItemEntity)) e.discard();
				GroundCuts.clearOwner(sp.getUuid());
				BlockPos.Mutable m = new BlockPos.Mutable();
				for (int x = gx0 - 5; x <= gx0 + 5; x++)
					for (int z = gz0 - 16; z <= gz0 - 2; z++)
						for (int y = gfy - 6; y <= gfy - 5; y++) w.setBlockState(m.set(x, y, z), Blocks.AIR.getDefaultState(), 2 | 16);
				w.setBlockState(new BlockPos(gx0 + 1, gfy - 6, gz0 - 9), Blocks.TORCH.getDefaultState(), 2 | 16);
				sp.networkHandler.requestTeleport(gx0 + 0.5, gfy - 6, gz0 - 4.5, 180f, 22f);
				srvC = count(w, gx0 - 8, gfy - 13, gz0 - 20, gx0 + 8, gfy, gz0 + 1, false);
				dropped0 = PieceEntity.DROPPED;
				srvA = 1;
			});
		});
		waitFor("revisión: túnel alto, preparado", 60, "el cliente no ve la antorcha del túnel",
			() -> srvA == 1 && mc.world.getBlockState(new BlockPos(gx0 + 1, gfy - 6, gz0 - 9)).isOf(Blocks.TORCH)
				&& mc.world.getBlockState(new BlockPos(gx0, gfy - 7, gz0 - 9)).isOf(Blocks.STONE)
				&& Math.abs(mc.player.getY() - (gfy - 6)) < 0.1 && ClientState.heldIds.length == 0, null);
		step(10, "revisión: túnel alto, toque", () -> {
			tsnap(DENY);
			key(R, PRESS);
		});
		step(2, "revisión: túnel alto, toque suelta", () -> key(R, RELEASE));
		waitFor("revisión: túnel alto, no cabe", 120, "no llegó el aviso de que no cabe", () -> tgot(DENY), () -> {
			Payloads.TaktFx d = TaktFxClient.LAST[DENY];
			check(d != null && d.state() == Payloads.TaktFx.NO_SPACE, "si no hay sitio encima, Takt lo dice",
				"aviso " + (d == null ? "-" : String.valueOf(d.state())));
		});
		step(40, "revisión: túnel alto, mide", () -> {
			srvA = -1;
			server(srv, id, (sp, pw) -> {
				ServerWorld w = sp.getServerWorld();
				int solid = count(w, gx0 - 8, gfy - 13, gz0 - 20, gx0 + 8, gfy, gz0 + 1, false);
				Box area = new Box(gx0 - 9, gfy - 14, gz0 - 21, gx0 + 9, gfy + 2, gz0 + 2);
				int pieces = w.getEntitiesByClass(PieceEntity.class, area, e -> true).size();
				int loose = items(w, area);
				boolean torch = w.getBlockState(new BlockPos(gx0 + 1, gfy - 6, gz0 - 9)).isOf(Blocks.TORCH);
				int dropped = PieceEntity.DROPPED - dropped0;
				OpeOpeMod.LOG.info("AUTOTEST revisión túnel alto: bloques {} y {}, {} piezas, {} objetos, antorcha {}, {} soltados",
					srvC, solid, pieces, loose, torch, dropped);
				srvA = (solid == srvC ? 1 : 0) | (pieces == 0 ? 2 : 0) | (loose == 0 && dropped == 0 ? 4 : 0) | (torch ? 8 : 0);
			});
		});
		waitFor("revisión: túnel alto, medido", 20, null, () -> srvA >= 0, () ->
			check(srvA == 15, "un trozo más alto que el túnel vuelve a su hueco con su antorcha, sin encajarse en el techo",
				"mismos bloques " + ((srvA & 1) != 0) + ", sin piezas " + ((srvA & 2) != 0) + ", nada suelto " + ((srvA & 4) != 0)
					+ ", antorcha " + ((srvA & 8) != 0)));

		// el túnel de 2 de alto con una telaraña a la altura de la cabeza, justo encima de una columna del trozo (como una
		// antorcha en la pared): no se choca con ella, así que el trozo se queda en el túnel igual y nadie dice "no hay sitio"
		step(5, "revisión: telaraña, preparar", () -> {
			srvA = -1;
			server(srv, id, (sp, pw) -> {
				tunnelScene(sp, pw, gfy - 6);
				sp.getServerWorld().setBlockState(new BlockPos(gx0 + 1, gfy - 5, gz0 - 9), Blocks.COBWEB.getDefaultState(), 2 | 16);
				sp.networkHandler.requestTeleport(gx0 + 0.5, gfy - 6, gz0 - 4.5, 180f, 22f);
				srvA = 1;
			});
		});
		waitFor("revisión: telaraña, preparado", 60, "el cliente no ve la telaraña del túnel",
			() -> srvA == 1 && mc.world.getBlockState(new BlockPos(gx0 + 1, gfy - 5, gz0 - 9)).isOf(Blocks.COBWEB)
				&& mc.world.getBlockState(new BlockPos(gx0, gfy - 6, gz0 - 9)).isAir()
				&& Math.abs(mc.player.getY() - (gfy - 6)) < 0.1 && ClientState.heldIds.length == 0, null);
		step(10, "revisión: telaraña, toque", () -> {
			tsnap(DETACH);
			tsnap(DENY);
			key(R, PRESS);
		});
		step(2, "revisión: telaraña, toque suelta", () -> key(R, RELEASE));
		waitFor("revisión: telaraña, suelto", 40, "no llegó DETACH con la telaraña", () -> tgot(DETACH), () -> chunkId = entityOf(DETACH));
		step(60, "revisión: telaraña, fuera del hueco", () -> {
			srvA = -1;
			server(srv, id, (sp, pw) -> {
				int r = 0;
				if (sp.getServerWorld().getEntityById(chunkId) instanceof PieceEntity pe) {
					Box b = pe.getBoundingBox();
					boolean in = b.minY >= gfy - 6 - 0.05 && b.maxY <= gfy - 4 + 0.05;
					OpeOpeMod.LOG.info("AUTOTEST revisión telaraña: {} bloques, base en y {}, arriba en y {}, atraviesa {}", pe.blocks.size(),
						fmt(b.minY - gfy), fmt(b.maxY - gfy), pe.noClip);
					r = 4 | (in ? 1 : 0) | (pe.noClip ? 0 : 2);
				}
				srvA = r;
			});
		});
		waitFor("revisión: telaraña, medido", 20, null, () -> srvA >= 0, () -> {
			Payloads.TaktFx d = tgot(DENY) ? TaktFxClient.LAST[DENY] : null;
			boolean denied = d != null && d.state() == Payloads.TaktFx.NO_SPACE;
			check(srvA == 7 && !denied, "una telaraña o una antorcha en la pared del túnel no echan al trozo: se queda en el túnel",
				denied ? "dijo que no hay sitio" : (srvA & 4) == 0 ? "ya no está"
					: "dentro del túnel " + ((srvA & 1) != 0) + ", choca " + ((srvA & 2) != 0));
		});

		// un túnel justo debajo de la hierba (el techo es un solo bloque, con aire encima) y una antorcha en el suelo: el
		// trozo con la antorcha es más alto que el túnel y tampoco sale atravesando ese techo fino: vuelve a su hueco y avisa
		step(5, "revisión: techo fino, preparar", () -> {
			srvA = -1;
			server(srv, id, (sp, pw) -> {
				ServerWorld w = sp.getServerWorld();
				tunnelScene(sp, pw, gfy - 2);
				w.setBlockState(new BlockPos(gx0 + 1, gfy - 2, gz0 - 9), Blocks.TORCH.getDefaultState(), 2 | 16);
				sp.networkHandler.requestTeleport(gx0 + 0.5, gfy - 2, gz0 - 4.5, 180f, 22f);
				srvC = count(w, gx0 - 8, gfy - 13, gz0 - 20, gx0 + 8, gfy + 4, gz0 + 1, false);
				dropped0 = PieceEntity.DROPPED;
				srvA = 1;
			});
		});
		waitFor("revisión: techo fino, preparado", 60, "el cliente no ve el túnel de techo fino",
			() -> srvA == 1 && mc.world.getBlockState(new BlockPos(gx0 + 1, gfy - 2, gz0 - 9)).isOf(Blocks.TORCH)
				&& mc.world.getBlockState(new BlockPos(gx0, gfy - 1, gz0 - 9)).isAir()
				&& Math.abs(mc.player.getY() - (gfy - 2)) < 0.1 && ClientState.heldIds.length == 0, null);
		step(10, "revisión: techo fino, toque", () -> {
			tsnap(DENY);
			key(R, PRESS);
		});
		step(2, "revisión: techo fino, toque suelta", () -> key(R, RELEASE));
		waitFor("revisión: techo fino, no cabe", 120, "no llegó el aviso de que no cabe bajo el techo fino", () -> tgot(DENY), () -> {
			Payloads.TaktFx d = TaktFxClient.LAST[DENY];
			check(d != null && d.state() == Payloads.TaktFx.NO_SPACE, "bajo un techo de un solo bloque Takt tampoco lo saca atravesándolo",
				"aviso " + (d == null ? "-" : String.valueOf(d.state())));
		});
		step(40, "revisión: techo fino, mide", () -> {
			srvA = -1;
			server(srv, id, (sp, pw) -> {
				ServerWorld w = sp.getServerWorld();
				int solid = count(w, gx0 - 8, gfy - 13, gz0 - 20, gx0 + 8, gfy + 4, gz0 + 1, false);
				Box area = new Box(gx0 - 9, gfy - 14, gz0 - 21, gx0 + 9, gfy + 6, gz0 + 2);
				int pieces = w.getEntitiesByClass(PieceEntity.class, area, e -> true).size();
				int loose = items(w, area);
				boolean torch = w.getBlockState(new BlockPos(gx0 + 1, gfy - 2, gz0 - 9)).isOf(Blocks.TORCH);
				int dropped = PieceEntity.DROPPED - dropped0;
				OpeOpeMod.LOG.info("AUTOTEST revisión techo fino: bloques {} y {}, {} piezas, {} objetos, antorcha {}, {} soltados",
					srvC, solid, pieces, loose, torch, dropped);
				srvA = (solid == srvC ? 1 : 0) | (pieces == 0 ? 2 : 0) | (loose == 0 && dropped == 0 ? 4 : 0) | (torch ? 8 : 0);
			});
		});
		waitFor("revisión: techo fino, medido", 20, null, () -> srvA >= 0, () ->
			check(srvA == 15, "bajo un techo fino el trozo vuelve a su hueco con su antorcha, sin quedarse en el techo",
				"mismos bloques " + ((srvA & 1) != 0) + ", sin piezas " + ((srvA & 2) != 0) + ", nada suelto " + ((srvA & 4) != 0)
					+ ", antorcha " + ((srvA & 8) != 0)));
	}

	/**
	 * En el hilo del servidor: suelta lo que lleva Takt, rehace el terreno de la prueba sin trozos, objetos ni cortes y
	 * cava un túnel de 2 de alto (y0 e y0 + 1) en x gx0 − 5..gx0 + 5, z gz0 − 16..gz0 − 2.
	 */
	private static void tunnelScene(ServerPlayerEntity sp, PlayerPower pw, int y0) {
		ServerWorld w = sp.getServerWorld();
		Takt.dropAll(sp, pw, false);
		patch(w, gx0 - 16, gx0 + 16, gz0 - 34, gz0 + 2, gfy);
		for (Entity e : w.getEntitiesByClass(Entity.class, new Box(gx0 - 16, gfy - 14, gz0 - 34, gx0 + 17, gfy + 27, gz0 + 3),
			en -> en instanceof PieceEntity || en instanceof ItemEntity)) e.discard();
		GroundCuts.clearOwner(sp.getUuid());
		BlockPos.Mutable m = new BlockPos.Mutable();
		for (int x = gx0 - 5; x <= gx0 + 5; x++)
			for (int z = gz0 - 16; z <= gz0 - 2; z++)
				for (int y = y0; y <= y0 + 1; y++) w.setBlockState(m.set(x, y, z), Blocks.AIR.getDefaultState(), 2 | 16);
	}

	/** Los bloques de esa pieza, tal como los ve el cliente; vacío si no está. */
	private static List<BlockSet.Entry> entries(MinecraftClient mc, int entityId) {
		return mc.world.getEntityById(entityId) instanceof PieceEntity pe ? List.copyOf(pe.blocks.entries) : List.of();
	}

	/** ¿No comparten ninguna celda las dos piezas, cada una con su esquina mínima en su base? */
	private static boolean disjoint(BlockPos a, List<BlockSet.Entry> ea, BlockPos b, List<BlockSet.Entry> eb) {
		LongOpenHashSet cells = new LongOpenHashSet(ea.size() * 2);
		for (BlockSet.Entry e : ea) cells.add(BlockPos.asLong(a.getX() + e.x(), a.getY() + e.y(), a.getZ() + e.z()));
		for (BlockSet.Entry e : eb) {
			if (cells.contains(BlockPos.asLong(b.getX() + e.x(), b.getY() + e.y(), b.getZ() + e.z()))) return false;
		}
		return !ea.isEmpty() && !eb.isEmpty();
	}

	private static void tsnap(int k) {
		TBASE[k] = TaktFxClient.COUNTS[k];
	}

	/** ¿Ha llegado un aviso k de Takt desde el último tsnap(k)? */
	private static boolean tgot(int k) {
		return TaktFxClient.COUNTS[k] > TBASE[k];
	}

	/** ticks del último aviso k de Takt si llegó desde el último tsnap(k); −1 si no. */
	private static int ticksOf(int k) {
		Payloads.TaktFx f = TaktFxClient.LAST[k];
		return tgot(k) && f != null ? f.ticks() : -1;
	}

	/** La entidad del último aviso k de Takt si llegó desde el último tsnap(k); −1 si no. */
	private static int entityOf(int k) {
		Payloads.TaktFx f = TaktFxClient.LAST[k];
		return tgot(k) && f != null ? f.entityId() : -1;
	}

	/** ¿Lleva Takt esa entidad? */
	private static boolean held(int entityId) {
		for (int h : ClientState.heldIds) if (h == entityId) return true;
		return false;
	}

	/** En el hilo del servidor: el terreno de setup en x0..x1 × z0..z1 (piedra, tres de tierra, hierba en fy y aire encima). */
	private static void patch(ServerWorld w, int x0, int x1, int z0, int z1, int fy) {
		BlockState air = Blocks.AIR.getDefaultState(), dirt = Blocks.DIRT.getDefaultState(), grass = Blocks.GRASS_BLOCK.getDefaultState();
		BlockState stone = Blocks.STONE.getDefaultState();
		BlockPos.Mutable m = new BlockPos.Mutable();
		for (int x = x0; x <= x1; x++) {
			for (int z = z0; z <= z1; z++) {
				for (int y = fy - 14; y <= fy + 26; y++) {
					BlockState st = y > fy ? air : y == fy ? grass : y > fy - 4 ? dirt : stone;
					w.setBlockState(m.set(x, y, z), st, 2 | 16);
				}
			}
		}
	}

	/** En el hilo del servidor: cuántas celdas de la caja (con los dos extremos) son aire (air) o no lo son (!air). */
	private static int count(ServerWorld w, int x0, int y0, int z0, int x1, int y1, int z1, boolean air) {
		BlockPos.Mutable m = new BlockPos.Mutable();
		int n = 0;
		for (int x = x0; x <= x1; x++)
			for (int y = y0; y <= y1; y++)
				for (int z = z0; z <= z1; z++) if (w.getBlockState(m.set(x, y, z)).isAir() == air) n++;
		return n;
	}

	/** En el hilo del servidor: los objetos tirados en b. */
	private static int items(ServerWorld w, Box b) {
		return w.getEntitiesByClass(ItemEntity.class, b, e -> true).size();
	}

	/**
	 * En el hilo del servidor: cuántos bloques de es no están en base + su sitio; 9999 si no hay base o bloques. La
	 * hierba tapada pasa a tierra sola en cualquier momento: hierba y tierra cuentan como lo mismo.
	 */
	private static int mismatches(ServerWorld w, BlockPos base, List<BlockSet.Entry> es) {
		if (base == null || es == null || es.isEmpty()) return 9999;
		BlockPos.Mutable m = new BlockPos.Mutable();
		int n = 0;
		for (BlockSet.Entry e : es) {
			m.set(base.getX() + e.x(), base.getY() + e.y(), base.getZ() + e.z());
			if (soil(w.getBlockState(m)) != soil(e.state())) n++;
		}
		return n;
	}

	private static net.minecraft.block.Block soil(BlockState st) {
		return st.isOf(Blocks.GRASS_BLOCK) ? Blocks.DIRT : st.getBlock();
	}
}
