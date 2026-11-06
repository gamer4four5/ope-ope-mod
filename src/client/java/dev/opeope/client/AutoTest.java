package dev.opeope.client;

import dev.opeope.OpeOpeMod;
import dev.opeope.client.config.OpeSettings;
import dev.opeope.client.hud.HudRoot;
import dev.opeope.client.hud.Icons;
import dev.opeope.client.input.Bisturi;
import dev.opeope.client.input.InputHandler;
import dev.opeope.client.input.Keys;
import dev.opeope.client.input.Onboarding;
import dev.opeope.client.room.RoomFxClient;
import dev.opeope.client.room.RoomSounds;
import dev.opeope.client.screen.KeysScreen;
import dev.opeope.client.screen.SettingsScreen;
import dev.opeope.power.PlayerPower;
import dev.opeope.power.PowerManager;
import dev.opeope.power.Room;
import dev.opeope.power.RoomOps;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.LeavesBlock;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.util.ScreenshotRecorder;
import net.minecraft.client.util.Window;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.passive.PigEntity;
import net.minecraft.server.integrated.IntegratedServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.GameMode;
import net.minecraft.world.Heightmap;
import org.lwjgl.glfw.GLFW;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
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

		// (k) Takt: coger el cerdo, el chip, la fila de R y soltar
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
		step(1, "takt: suelta R", () -> key(GLFW.GLFW_KEY_R, RELEASE));

		// (l) Bisturí: un ROOM nuevo, el panel, un corte y soltar
		step(10, "bisturí: ROOM", () -> key(Q, PRESS));
		step(12, "bisturí: ROOM suelta", () -> {
			key(Q, RELEASE);
			srv.execute(() -> look(srv, id, 0, 8));
		});
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
}
