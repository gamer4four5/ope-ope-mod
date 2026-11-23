package dev.opeope.client;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.opeope.OpeConfig;
import dev.opeope.OpeOpeMod;
import dev.opeope.client.config.OpeSettings;
import dev.opeope.client.hud.HudRoot;
import dev.opeope.client.hud.Icons;
import dev.opeope.client.input.Bisturi;
import dev.opeope.client.input.InputHandler;
import dev.opeope.client.input.KeyLabels;
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
import dev.opeope.power.Shambles;
import dev.opeope.power.Takt;
import dev.opeope.power.Tech;
import dev.opeope.power.Techniques;
import dev.opeope.power.Forge;
import dev.opeope.registry.ModEffects;
import dev.opeope.terrain.ChunkPlan;
import dev.opeope.terrain.ChunkPlanner;
import dev.opeope.terrain.GroundCut;
import dev.opeope.terrain.GroundRules;
import dev.opeope.terrain.MaterialTrait;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.LeavesBlock;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.option.GameOptions;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.client.util.ScreenshotRecorder;
import net.minecraft.client.util.Window;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.ItemEntity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.attribute.EntityAttributeInstance;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.entity.mob.ZombieEntity;
import net.minecraft.entity.passive.PigEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.tag.BlockTags;
import net.minecraft.server.integrated.IntegratedServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.Hand;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
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
import java.util.Map;
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
	/** Con -Dopeope.gallery=true (./gradlew runGalleryClient): solo la galería de técnicas de Law, con capturas. */
	public static final boolean GALLERY = Boolean.getBoolean("opeope.gallery");
	private static int t = -1;

	// ------------------------------------------------------------------ galería de técnicas de Law (efectos LawFx)

	/** Una técnica de la galería: qué hacer en el servidor y en qué ticks (desde que empieza) sacar capturas. */
	private record Shot(String name, BiConsumer<ServerPlayerEntity, PlayerPower> use, float pitch, int[] at, int[] third) {}

	private static final int GALLERY_STEP = 64;
	private static ZombieEntity galleryZombie;

	private static List<Shot> galleryShots() {
		List<Shot> l = new ArrayList<>();
		int[] std = {3, 9, 18};
		int[] th = {6};
		l.add(new Shot("radio-knife", (sp, pw) -> Techniques.radioKnife(sp, pw), 8, std, th));
		l.add(new Shot("injection", (sp, pw) -> Techniques.injection(sp, pw), 8, new int[]{2, 5, 9, 14}, new int[]{}));
		l.add(new Shot("gamma", (sp, pw) -> Techniques.gamma(sp, pw), 8, new int[]{2, 6, 12}, th));
		l.add(new Shot("curtain", (sp, pw) -> Techniques.curtain(sp, pw), 0, new int[]{4, 14}, new int[]{9}));
		l.add(new Shot("mes", (sp, pw) -> Techniques.mes(sp, pw), 8, new int[]{3, 8, 14}, th));
		l.add(new Shot("counter-shock", (sp, pw) -> Techniques.counterShock(sp, pw), 8, new int[]{2, 6, 11}, new int[]{4}));
		l.add(new Shot("scan", (sp, pw) -> Techniques.scan(sp, pw), 4, new int[]{6, 14, 24}, new int[]{}));
		l.add(new Shot("souls", (sp, pw) -> Techniques.souls(sp, pw), 8, new int[]{5, 11, 17}, new int[]{}));
		l.add(new Shot("rain", (sp, pw) -> Techniques.rain(sp, pw), 0, new int[]{10, 16, 22}, new int[]{}));
		l.add(new Shot("awaken", (sp, pw) -> {
			sp.setAttached(dev.opeope.registry.ModAttachments.AWAKENING, true);
			pw.awakened = false;
			Techniques.toggleAwaken(sp, pw);
		}, 10, new int[]{4, 10}, new int[]{7}));
		l.add(new Shot("k-room", (sp, pw) -> Techniques.kroom(sp, pw), 8, new int[]{4, 9, 16}, th));
		l.add(new Shot("anesthesia", (sp, pw) -> Techniques.anesthesia(sp, pw, true), 8, new int[]{5, 14}, new int[]{9}));
		l.add(new Shot("shock-wille", (sp, pw) -> {
			pw.anesthesia = false;
			Techniques.shockWille(sp, pw);
		}, 8, new int[]{2, 6, 10, 16}, new int[]{}));
		l.add(new Shot("silent", (sp, pw) -> Techniques.silent(sp, pw), 10, new int[]{6}, new int[]{12}));
		l.add(new Shot("iai", (sp, pw) -> {
			pw.combatStyle = 2;
			Techniques.radioKnife(sp, pw);
		}, 4, new int[]{2, 5}, new int[]{}));
		l.add(new Shot("luna", (sp, pw) -> {
			pw.combatStyle = 2;
			Techniques.injection(sp, pw);
		}, 4, new int[]{2, 5, 8}, new int[]{}));
		l.add(new Shot("torbellino", (sp, pw) -> {
			pw.combatStyle = 2;
			Techniques.gamma(sp, pw);
		}, 10, new int[]{3, 7}, new int[]{5}));
		l.add(new Shot("mil-puntadas", (sp, pw) -> {
			pw.combatStyle = 2;
			Techniques.toggleAwaken(sp, pw);
		}, 8, new int[]{3, 7, 12}, new int[]{}));
		l.add(new Shot("r-room", (sp, pw) -> {
			pw.combatStyle = 1;
			RoomOps.rroom(sp, pw, true);
		}, 8, new int[]{3, 8, 26, 31, 38}, new int[]{}));
		l.add(new Shot("sterben", (sp, pw) -> Techniques.sterben(sp, pw), 6, new int[]{3, 8}, new int[]{}));
		l.add(new Shot("puncture", (sp, pw) -> Techniques.puncture(sp, pw), 7, new int[]{2, 5, 9, 15, 24, 36}, new int[]{12}));
		return l;
	}

	private static List<Shot> gallery;

	private static void gallery(MinecraftClient mc, IntegratedServer srv, UUID id, int g) {
		if (gallery == null) gallery = galleryShots();
		if (g == 0) {
			mc.getWindow().setWindowedSize(1280, 720);
			srv.execute(() -> {
				ServerPlayerEntity sp = srv.getPlayerManager().getPlayer(id);
				if (sp == null) return;
				galleryZombie = spawnDummyZombie(sp.getServerWorld(), gx0 + 0.5, gfy + 1, gz0 + 4.5);
				if (galleryZombie != null) galleryZombie.setInvulnerable(true);
			});
		}
		int idx = (g - 20) / GALLERY_STEP;
		int r = (g - 20) % GALLERY_STEP;
		if (g < 20) return;
		if (idx >= gallery.size()) {
			if (r == 10) {
				OpeOpeMod.LOG.info("AUTOTEST galería terminada");
				mc.scheduleStop();
			}
			return;
		}
		Shot s = gallery.get(idx);
		if (r == 0) {
			// todo a su sitio, sin enfriamientos ni restos de la técnica anterior
			mc.options.setPerspective(net.minecraft.client.option.Perspective.FIRST_PERSON);
			boolean north = s.name().equals("injection") || s.name().equals("iai") || s.name().equals("luna");
			server(srv, id, (sp, pw) -> {
				// la escena de nuevo (lo cortado por la técnica anterior fuera) y un zombi quieto delante
				ServerWorld w = sp.getServerWorld();
				Box area = new Box(gx0 - 40, gfy - 30, gz0 - 40, gx0 + 40, gfy + 60, gz0 + 50);
				for (Entity e : w.getOtherEntities(sp, area, e -> !(e instanceof ServerPlayerEntity))) e.discard();
				setup(srv, id);
				java.util.Arrays.fill(pw.cooldowns, 0);
				pw.anesthesia = false;
				pw.combatStyle = 1;
				pw.silentTicks = 0;
				pw.curtainTicks = 0;
				pw.kroomBladeTicks = 0;
				sp.removeStatusEffect(StatusEffects.INVISIBILITY);
				sp.setStackInHand(Hand.MAIN_HAND, ItemStack.EMPTY);
				sp.changeGameMode(GameMode.SURVIVAL);
				sp.getAbilities().invulnerable = true;
				double zz = north ? gz0 - 4.5 : gz0 + 4.5;
				galleryZombie = spawnDummyZombie(w, gx0 + 0.5, gfy + 1, zz);
				if (galleryZombie != null) {
					galleryZombie.setInvulnerable(true);
					galleryZombie.equipStack(net.minecraft.entity.EquipmentSlot.HEAD, new ItemStack(Items.LEATHER_HELMET));   // que no arda al sol
					galleryZombie.refreshPositionAndAngles(gx0 + 0.5, gfy + 1, zz, north ? 0f : 180f, 0f);
				}
				sp.networkHandler.requestTeleport(gx0 + 0.5, gfy + 1, gz0 + 0.5, north ? 180f : 0f, s.pitch());
				sp.setVelocity(Vec3d.ZERO);
			});
		}
		if (r == 8) {
			OpeOpeMod.LOG.info("AUTOTEST galería {}", s.name());
			server(srv, id, (sp, pw) -> {
				try {
					s.use().accept(sp, pw);
				} catch (RuntimeException e) {
					OpeOpeMod.LOG.error("AUTOTEST galería: falló {}", s.name(), e);
				}
			});
		}
		if (s.name().equals("r-room") && r == 8 + 22) server(srv, id, (sp, pw) -> RoomOps.rroom(sp, pw, false));
		for (int k = 0; k < s.at().length; k++) {
			if (r == 8 + s.at()[k]) shot(mc, "law-" + String.format("%02d", idx + 1) + "-" + s.name() + "-" + (char) ('a' + k));
		}
		for (int k = 0; k < s.third().length; k++) {
			int at = 8 + s.third()[k];
			if (r == at - 1) mc.options.setPerspective(net.minecraft.client.option.Perspective.THIRD_PERSON_BACK);
			if (r == at) {
				shot(mc, "law-" + String.format("%02d", idx + 1) + "-" + s.name() + "-3p" + (k == 0 ? "" : String.valueOf(k)));
				OpeOpeMod.LOG.info("AUTOTEST galería gesto {}: {}", s.name(), dev.opeope.client.render.PlayerAnimClient.gestureDebug(mc.player.getId()));
			}
			if (r == at + 1) mc.options.setPerspective(net.minecraft.client.option.Perspective.FIRST_PERSON);
		}
	}

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
		if (GALLERY) {
			if (t == 101) Onboarding.suppressForTest(true);
			if (t >= 110) gallery(mc, srv, id, t - 110);
			return;
		}
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
		w.getGameRules().get(net.minecraft.world.GameRules.DO_WEATHER_CYCLE).set(false, srv);
		w.getGameRules().get(net.minecraft.world.GameRules.DO_MOB_SPAWNING).set(false, srv);
		w.getGameRules().get(net.minecraft.world.GameRules.RANDOM_TICK_SPEED).set(0, srv);

		int x0 = sp.getBlockX(), z0 = sp.getBlockZ();
		int fy = w.getTopY(Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, x0, z0) - 1;   // el suelo donde estás
		fy = Math.max(w.getBottomY() + 20, Math.min(fy, w.getTopY() - 40));
		BlockState air = Blocks.AIR.getDefaultState(), dirt = Blocks.DIRT.getDefaultState(), grass = Blocks.GRASS_BLOCK.getDefaultState();
		BlockState stone = Blocks.STONE.getDefaultState();
		BlockPos.Mutable m = new BlockPos.Mutable();
		// Un terreno llano: 14 de tierra y piedra hacia abajo (pegado al mundo de verdad), aire encima.
		for (int x = x0 - 18; x <= x0 + 18; x++) {
			for (int z = z0 - 10; z <= z0 + 26; z++) {
				for (int y = fy - 14; y <= fy + 45; y++) {
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
	private static volatile int elemZ1 = -1, elemZ2 = -1, elemZ3 = -1;
	private static volatile double srvD1 = 0, srvD2 = 0;
	private static volatile String srvMsg = "";
	private static int solid0, items0, chunkId = -1, chunkB = -1;
	/** Solo en el hilo del servidor. */
	private static int dropped0, relocated0, shattered0;
	private static Crater.Result crater0;
	/** Los rugidos de roca que cae que había antes de soltar la rocota (cliente). */
	private static int roars0;
	private static BlockPos ghost, ghostB;
	private static List<BlockSet.Entry> ghostSet = List.of(), ghostSetB = List.of();
	/** Solo en el hilo del servidor. */
	private static Room savedRoom;
	/** Solo en el hilo del servidor: lo que el jugador llevaba en las manos antes de (a2). */
	private static ItemStack savedMain = ItemStack.EMPTY, savedOff = ItemStack.EMPTY;
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

	private static void writeConfig(String name, String text) {
		try {
			Files.writeString(FabricLoader.getInstance().getConfigDir().resolve(name), text, StandardCharsets.UTF_8);
		} catch (Exception e) {
			fallo("no se pudo escribir config/" + name + ": " + e);
		}
	}

	/** La «version» de config/opeope-teclas.json (-1 si no tiene). */
	private static int keysFileVersion() {
		try {
			JsonElement v = JsonParser.parseString(readConfig("opeope-teclas.json")).getAsJsonObject().get("version");
			return v != null && v.isJsonPrimitive() ? v.getAsInt() : -1;
		} catch (Exception e) {
			return -1;
		}
	}

	/** Las teclas de la fruta que importan en (n2), como «ROOM=key.keyboard.g». */
	private static String keysNow() {
		List<String> out = new ArrayList<>();
		for (Keys k : new Keys[]{Keys.ROOM, Keys.SCAN, Keys.MARKER, Keys.AWAKEN, Keys.CURTAIN}) out.add(k.name() + "=" + k.key.getTranslationKey());
		return String.join(", ", out);
	}

	private static boolean on(Keys k, String name) {
		return name.equals(k.key.getTranslationKey());
	}

	/**
	 * Una tecla de Minecraft por el teclado de verdad (Keyboard.onKey), como si la pulsaras: primero la ve la fruta
	 * (KeyboardMixin) y, si no es suya, le llega a Minecraft.
	 */
	private static void vanillaKey(MinecraftClient mc, KeyBinding kb, int action) {
		mc.keyboard.onKey(mc.getWindow().getHandle(), KeyBindingHelper.getBoundKeyOf(kb).getCode(), 0, action, 0);
	}

	private static String label(KeyBinding kb) {
		return KeyBindingHelper.getBoundKeyOf(kb).getLocalizedText().getString();
	}

	/** Solo en el hilo del servidor: la tierra suelta cerca del jugador (la de (a2)). */
	private static List<ItemEntity> nearDirt(ServerPlayerEntity sp) {
		return sp.getServerWorld().getEntitiesByClass(ItemEntity.class, sp.getBoundingBox().expand(8), e -> e.getStack().isOf(Items.DIRT));
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
		// el ROOM y Scan con sus teclas de fábrica (G y V): la Q y la F son de Minecraft
		final int ROOMK = Keys.ROOM.key.getCode(), SCANK = Keys.SCAN.key.getCode(), PRESS = GLFW.GLFW_PRESS, RELEASE = GLFW.GLFW_RELEASE;
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

		// (a1) de fábrica, la fruta no tapa ninguna tecla de Minecraft. Solo comparten tecla la segunda fila (Tab, que tiene
		// su ajuste para la lista de jugadores) y Gamma Knife e Injection Shot (C y X: en creativo, agachado, siguen
		// guardando y cargando barras de objetos)
		step(0, "teclas de fábrica", () -> {
			GameOptions o = mc.options;
			List<String> clash = new ArrayList<>();
			for (Keys k : Keys.values()) {
				if (k.group() != Keys.Group.MAIN || k == Keys.ROW2 || k == Keys.ROW2_MOUSE_A || k == Keys.ROW2_MOUSE_B) continue;
				InputUtil.Key def = InputUtil.fromTranslationKey(k.def);
				for (KeyBinding kb : o.allKeys) {
					if (kb == o.saveToolbarActivatorKey || kb == o.loadToolbarActivatorKey) continue;
					if (def.equals(kb.getDefaultKey())) clash.add(k.name() + " y " + kb.getTranslationKey() + " en " + k.def);
				}
			}
			check(clash.isEmpty(), "las teclas de fábrica de la fruta no tapan las de Minecraft", "chocan " + clash);
		});
		step(0, "estilos: teclas", () -> {
			// las técnicas de los estilos 2 y 3 van en las teclas de la fruta: su aviso dice las de verdad
			String s2 = HudRoot.styleText("estilo2").getString(), s3 = HudRoot.styleText("estilo3").getString();
			check(s2.contains("[" + KeyLabels.name(Keys.AWAKEN.key) + "]") && s3.contains("[" + KeyLabels.name(Keys.SCAN.key) + "]")
					&& !(s2 + s3).contains("%s") && !s2.startsWith("opeope.") && !s3.startsWith("opeope."),
				"los avisos de los estilos dicen las teclas de verdad", s2 + " | " + s3);
		});

		// (a2) con la fruta comida, la tecla de botar (Q) bota uno y la de cambiar de mano (F) cambia de mano, como siempre
		step(0, "botar: preparar", () -> {
			srvA = -1;
			server(srv, id, (sp, pw) -> {
				savedMain = sp.getMainHandStack().copy();
				savedOff = sp.getOffHandStack().copy();
				sp.setStackInHand(Hand.MAIN_HAND, new ItemStack(Items.DIRT, 5));
				sp.setStackInHand(Hand.OFF_HAND, ItemStack.EMPTY);
				for (ItemEntity e : nearDirt(sp)) e.discard();
				srvA = 1;
			});
		});
		waitFor("botar: preparado", 20, "no se preparó", () -> srvA == 1, null);
		step(5, "botar", () -> {
			check(ClientState.hasFruit(), "botar: el jugador tiene la fruta", "sin la fruta esta prueba no dice nada");
			vanillaKey(mc, mc.options.dropKey, PRESS);
		});
		step(1, "botar: suelta", () -> vanillaKey(mc, mc.options.dropKey, RELEASE));
		step(5, "botar: mide", () -> {
			srvA = -1;
			server(srv, id, (sp, pw) -> {
				srvB = sp.getMainHandStack().isOf(Items.DIRT) ? sp.getMainHandStack().getCount() : 0;
				srvC = nearDirt(sp).size();
				srvA = 1;
			});
		});
		waitFor("botar: medido", 20, null, () -> srvA == 1, () -> check(srvB == 4 && srvC == 1,
			"con la fruta, " + label(mc.options.dropKey) + " bota uno como en Minecraft", "en la mano quedan " + srvB + " de 5 y en el suelo hay " + srvC));
		step(0, "cambiar de mano", () -> vanillaKey(mc, mc.options.swapHandsKey, PRESS));
		step(1, "cambiar de mano: suelta", () -> vanillaKey(mc, mc.options.swapHandsKey, RELEASE));
		step(5, "cambiar de mano: mide", () -> {
			srvA = -1;
			server(srv, id, (sp, pw) -> {
				srvB = sp.getMainHandStack().getCount();
				srvC = sp.getOffHandStack().isOf(Items.DIRT) ? sp.getOffHandStack().getCount() : 0;
				srvA = 1;
			});
		});
		waitFor("cambiar de mano: medido", 20, null, () -> srvA == 1, () -> check(srvB == 0 && srvC == 4,
			"con la fruta, " + label(mc.options.swapHandsKey) + " cambia de mano como en Minecraft", "en la mano " + srvB + " y en la otra " + srvC + " de tierra"));
		step(0, "botar: recoger", () -> server(srv, id, (sp, pw) -> {
			for (ItemEntity e : nearDirt(sp)) e.discard();
			sp.setStackInHand(Hand.MAIN_HAND, savedMain);
			sp.setStackInHand(Hand.OFF_HAND, savedOff);
		}));

		// (b) Scan y el HUD en reposo
		step(10, "scan", () -> key(SCANK, PRESS));
		step(1, "scan: suelta", () -> key(SCANK, RELEASE));
		step(10, "7-hud-reposo", () -> {
			shot(mc, "7-hud-reposo");
			layout("7-hud-reposo");
		});
		step(120, "7-hud-calma", () -> shot(mc, "7-hud-calma"));

		// (c) crear: semilla, florece, crece y queda fijo
		step(0, "crear", () -> {
			snap(CREATE);
			key(ROOMK, PRESS);
		});
		step(1, "8-room-a", () -> shot(mc, "8-room-a-semilla"));
		waitFor("crear: CREATE", 10, null, () -> got(CREATE), null);
		step(2, "8-room-b", () -> shot(mc, "8-room-b-florece"));
		step(8, "8-room-c", () -> shot(mc, "8-room-c-creciendo"));
		step(6, "crear: suelta", () -> {
			snap(FIX);
			key(ROOMK, RELEASE);
		});
		waitFor("crear: FIX", 10, null, () -> got(FIX), () -> shot(mc, "8-room-d-fija"));
		step(20, "8-room-e", () -> {
			shot(mc, "8-room-e-reposo");
			ClientState.RoomInfo r = room();
			check(r != null && r.state() == 1 && r.radius() > 15 && r.radius() < 60, "crear el ROOM",
				r == null ? "no hay ROOM" : "estado " + r.state() + ", radio " + fmt(r.radius()));
		});

		// (d) tamaño: un paso de rueda, ida y vuelta, y hasta el mínimo
		step(0, "tamaño", () -> key(ROOMK, PRESS));
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
			key(ROOMK, RELEASE);
		});
		step(6, "9-tamano-d", () -> shot(mc, "9-tamano-d"));

		// (e) agrandarlo otra vez
		step(0, "agrandar", () -> key(ROOMK, PRESS));
		for (int i = 0; i < 12; i++) step(2, "agrandar: rueda", () -> wheel(1));
		step(2, "agrandar: suelta", () -> key(ROOMK, RELEASE));

		// (f) lanzarlo: sale de la mano, vuela y aterriza
		step(10, "lanzar: mira", () -> srv.execute(() -> look(srv, id, 20, 12)));
		step(12, "lanzar", () -> key(ROOMK, PRESS));
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
			key(ROOMK, RELEASE);
			ClientState.RoomInfo r = room();
			double moved = r == null || c0 == null ? -1 : r.center().distanceTo(c0);
			check(moved > 5, "lanzar el ROOM", "el centro se movió " + fmt(moved) + " bloques");
		});

		// (g) traerlo: vuelve contigo
		step(10, "traer", () -> key(ROOMK, PRESS));
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
			key(ROOMK, RELEASE);
			ClientState.RoomInfo r = room();
			Vec3d to = mc.player.getPos().add(0, 1, 0);
			double d = r == null ? -1 : r.center().distanceTo(to);
			check(r != null && d < 1.5, "traer el ROOM", r == null ? "no hay ROOM" : "queda a " + fmt(d) + " bloques");
		});

		// (h) quitarlo con un toque
		step(10, "quitar", () -> key(ROOMK, PRESS));
		step(2, "12-quitar-a", () -> {
			snap(CLOSE);
			key(ROOMK, RELEASE);
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
			key(ROOMK, PRESS);
		});
		step(12, "doble toque: crea", () -> key(ROOMK, RELEASE));
		step(10, "doble toque: un toque", () -> key(ROOMK, PRESS));
		step(2, "doble toque: un toque suelta", () -> key(ROOMK, RELEASE));
		step(12, "doble toque: sigue", () -> {
			check(room() != null, "un toque no quita el ROOM (doble toque)", "se quitó");
			snap(CLOSE);
			key(ROOMK, PRESS);
		});
		step(2, "doble toque: 1", () -> key(ROOMK, RELEASE));
		step(3, "doble toque: 2", () -> key(ROOMK, PRESS));
		step(2, "doble toque: 2 suelta", () -> key(ROOMK, RELEASE));
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
			key(ROOMK, PRESS);
		});
		step(12, "takt: ROOM suelta", () -> key(ROOMK, RELEASE));
		// R + G a la vez (la G de fábrica es el ROOM y Aparcar), antes de que se abra la fila de R: es Aparcar y el ROOM
		// sigue ahí. Sin la orden temprana, el toque de G quitaría el ROOM y al soltar R se cogería lo que miras
		step(5, "takt: R+G rápido", () -> {
			key(GLFW.GLFW_KEY_R, PRESS);
			key(ROOMK, PRESS);
		});
		step(1, "takt: R+G rápido, suelta G", () -> key(ROOMK, RELEASE));
		step(1, "takt: R+G rápido, suelta R", () -> key(GLFW.GLFW_KEY_R, RELEASE));
		step(10, "takt: R+G rápido, mira", () -> {
			check(ClientState.ownRoom() != null && ClientState.heldIds.length == 0, "un R + G rápido aparca: ni quita el ROOM ni coge nada",
				"ROOM " + (ClientState.ownRoom() != null) + ", Takt lleva " + ClientState.heldIds.length);
			server(srv, id, (sp, pw) -> {   // como antes: a la mano y nada aparcado
				pw.taktMode = PlayerPower.TaktMode.HAND;
				pw.parked.clear();
			});
		});
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
			ClientState.chunkDepth = 0;
			ClientState.chunkRiderMode = ChunkPlanner.RIDER_AUTO;
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
			check(Keys.MARKER.key.getTranslationKey().equals(Keys.MARKER.def), "restablecer las teclas", "la piedra marcador quedó en " + Keys.MARKER.key.getTranslationKey());
		});

		// (n2) un opeope-teclas.json de antes (sin «version»): lo que seguía en la Q, la F, la G o la V pasa a las teclas
		// nuevas sin pisar lo que cambiaste tú; uno de ahora se queda tal cual. Al final, las teclas como estaban
		step(2, "teclas: config vieja", () -> {
			Map<Keys, InputUtil.Key> before = Keys.snapshot();
			try {
				JsonObject v1 = new JsonObject();
				for (Keys k : Keys.values()) v1.addProperty(k.name(), Keys.V1.getOrDefault(k, k.def));
				writeConfig("opeope-teclas.json", v1.toString());
				Keys.load();
				check(on(Keys.ROOM, "key.keyboard.g") && on(Keys.SCAN, "key.keyboard.v") && on(Keys.MARKER, "key.keyboard.n") && on(Keys.AWAKEN, "key.keyboard.m"),
					"una config vieja pasa a las teclas nuevas", keysNow());
				check(keysFileVersion() == 2, "la config pasada se guarda con «version» 2", "version " + keysFileVersion());

				v1.addProperty(Keys.CURTAIN.name(), "key.keyboard.n");   // Curtain en la N: la piedra marcador no tiene adónde ir
				writeConfig("opeope-teclas.json", v1.toString());
				Keys.load();
				check(on(Keys.CURTAIN, "key.keyboard.n") && on(Keys.MARKER, "key.keyboard.g") && on(Keys.ROOM, "key.keyboard.q")
						&& on(Keys.SCAN, "key.keyboard.v") && on(Keys.AWAKEN, "key.keyboard.m"),
					"al pasar una config vieja no se pisa una tecla que cambiaste tú", keysNow());

				JsonObject v2 = new JsonObject();
				v2.addProperty("version", 2);
				v2.addProperty(Keys.ROOM.name(), "key.keyboard.q");
				writeConfig("opeope-teclas.json", v2.toString());
				Keys.load();
				check(on(Keys.ROOM, "key.keyboard.q") && on(Keys.SCAN, Keys.SCAN.def), "una config de ahora no se toca", keysNow());
			} finally {
				Keys.restore(before);
			}
			check(Keys.snapshot().equals(before) && keysFileVersion() == 2, "las teclas vuelven a estar como antes de (n2)", keysNow());
		});

		// (o) Primeros pasos: la tarjeta y el paso del ROOM
		step(5, "primeros pasos", () -> {
			Onboarding.restart();
			Onboarding.suppressForTest(false);
		});
		step(10, "18-primeros-pasos-a", () -> {
			shot(mc, "18-primeros-pasos-a");
			key(ROOMK, PRESS);
		});
		step(12, "primeros pasos: suelta", () -> key(ROOMK, RELEASE));
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
			key(ROOMK, PRESS);
		});
		step(4, "20-contraste-room", () -> {
			shot(mc, "20-contraste-room");
			key(ROOMK, RELEASE);
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
				Takt.dropAll(sp, pw, false);
				clearField(w, true);
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
			if (r != null) {
				ChunkPlan d1 = ChunkPlanner.plan(mc.world, hit, 6, 1, ChunkPlanner.RIDER_AUTO, Direction.UP, near, r.center(), (double) r.radius(), null);
				ChunkPlan d4 = ChunkPlanner.plan(mc.world, hit, 6, 4, ChunkPlanner.RIDER_AUTO, Direction.UP, near, r.center(), (double) r.radius(), null);
				boolean oneDeep = !d1.isEmpty() && d1.blocks.length < d4.blocks.length && d4.blocks.length > a.blocks.length;
				for (long l : d1.blocks) if (BlockPos.unpackLongY(l) != gfy) { oneDeep = false; break; }
				check(oneDeep && d1.depth == 1 && d4.depth == 4, "el grosor explícito ajusta la profundidad (1 vs 4)",
					d1.blocks.length + " vs " + d4.blocks.length);
			}
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
				shattered0 = PieceEntity.SHATTERED;
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

		planPisoYDanio(mc, srv, id);

		planTerrenoIrregular(mc, srv, id);

		planBombardeoOrbital(mc, srv, id);

		planShamblesTactico(mc, srv, id);

		planRevision(mc, srv, id);

		planEfectosElementales(mc, srv, id);

		planDesmembramiento(mc, srv, id);

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
				shattered0 = PieceEntity.SHATTERED;
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

	/**
	 * Verifica que una pieza mediana (< 18 bloques) lanzada contra un obstáculo flotante en el aire no gaste el
	 * impacto/cráter en el aire, sino que al caer y tocar bien el piso explote, abra cráter y se dañe a sí misma
	 * conservando todos los bloques y sin soltar objetos.
	 */
	private static void planPisoYDanio(MinecraftClient mc, IntegratedServer srv, UUID id) {
		final int IMPACT = Payloads.TaktFx.IMPACT;
		step(4, "pisoYDano: preparar", () -> {
			srvA = -1;
			srvB = -1;
			server(srv, id, (sp, pw) -> {
				ServerWorld w = sp.getServerWorld();
				Takt.dropAll(sp, pw, false);
				patch(w, gx0 - 16, gx0 + 16, gz0 - 34, gz0 + 2, gfy);
				patchFieldRing(w);
				clearField(w, true);
				BlockPos.Mutable m = new BlockPos.Mutable();
				for (int x = gx0 - 2; x <= gx0 + 2; x++)
					for (int y = gfy + 4; y <= gfy + 8; y++)
						for (int z = gz0 - 16; z <= gz0 - 15; z++) w.setBlockState(m.set(x, y, z), Blocks.STONE.getDefaultState(), 2 | 16);
				sp.networkHandler.requestTeleport(gx0 + 0.5, gfy + 1, gz0 - 4.5, 180f, 0f);
				dropped0 = PieceEntity.DROPPED;
				shattered0 = PieceEntity.SHATTERED;
				crater0 = Crater.last;
				srvB = fieldItems(w);
				srvA = fieldCount(w);
			});
		});
		waitFor("pisoYDano: preparado", 60, "el cliente no ve el obstáculo", () -> srvA >= 0 && srvB >= 0
			&& mc.world.getBlockState(new BlockPos(gx0, gfy + 5, gz0 - 15)).isOf(Blocks.STONE), () -> {
			solid0 = srvA + 12;   // se lanza una pieza de 12 bloques de adoquín
			items0 = srvB;
		});
		step(2, "pisoYDano: lanzar contra muro alto", () -> {
			tsnap(IMPACT);
			srvC = -1;
			server(srv, id, (sp, pw) -> {
				BlockSet set = new BlockSet();
				for (int x = 0; x < 3; x++)
					for (int y = 0; y < 2; y++)
						for (int z = 0; z < 2; z++) set.add(x, y, z, Blocks.COBBLESTONE.getDefaultState(), null);
				set.normalize();
				PieceEntity pe = PieceEntity.spawn(sp.getServerWorld(), set, new Vec3d(gx0 - 1, gfy + 5, gz0 - 10), null, sp.getUuid());
				pe.release(new Vec3d(0, -0.05, -1.8), true);
				srvC = pe.getId();
			});
		});
		waitFor("pisoYDano: impacto en piso", 100, "la pieza no llegó a explotar en el piso", () -> tgot(IMPACT), () -> {
			Payloads.TaktFx f = TaktFxClient.LAST[IMPACT];
			if (!tgot(IMPACT) || f == null) return;
			check(f.y() <= gfy + 2.2 && f.state() >= 2 && f.a() > 0, "espera a tocar bien el piso para explotar y abrir cráter",
				"y=" + fmt(f.y() - gfy) + " sobre piso, nivel " + f.state() + ", radio " + fmt(f.a()));
		});
		settleField(srv, id, "pisoYDano: terrones");
		step(0, "pisoYDano: mide", () -> {
			srvA = -1;
			srvB = -1;
			srvC = -1;
			server(srv, id, (sp, pw) -> {
				ServerWorld w = sp.getServerWorld();
				Crater.Result c = Crater.last;
				int broken = PieceEntity.SHATTERED - shattered0;
				OpeOpeMod.LOG.info("AUTOTEST pisoYDano: fracturados {} bloques de la pieza lanzada", broken);
				srvC = (c != null && c != crater0 && c.balanced() ? 1 : 0) | (PieceEntity.DROPPED == dropped0 ? 2 : 0)
					| (broken > 0 ? 4 : 0);
				logCrater(c);
				srvB = fieldItems(w);
				srvA = fieldCount(w);
			});
		});
		waitFor("pisoYDano: medido", 20, null, () -> srvA >= 0 && srvB >= 0 && srvC >= 0, () -> {
			check(srvB == items0, "el impacto tras rozar muro no suelta objetos", (srvB - items0) + " objetos nuevos");
			check(srvA == solid0, "la fractura conserva el 100% de bloques", solid0 + " → " + srvA);
			check((srvC & 4) != 0, "la pieza mediana lanzada (< 18 bloques) también recibe daño propio", "fracturados " + (srvC & 4));
			check((srvC & 3) == 3, "cráter equilibrado y sin PieceEntity.DROPPED", "flags " + srvC);
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
		patch(w, gx0 - 32, gx0 - 17, gz0 - 64, gz0 + 4, gfy);
		patch(w, gx0 + 17, gx0 + 32, gz0 - 64, gz0 + 4, gfy);
		patch(w, gx0 - 16, gx0 + 16, gz0 - 64, gz0 - 35, gfy);
		patch(w, gx0 - 16, gx0 + 16, gz0 + 3, gz0 + 4, gfy);
	}

	/**
	 * En el hilo del servidor: fuera del campo los objetos y los animales (la onda del golpe no mata a nadie que suelte
	 * nada) y, con pieces, también las piezas sueltas.
	 */
	/**
	 * (k2c) Terreno irregular (escalón de hierba + hojas) con pieza pequeña lanzada rasante: no debe perder impulso,
	 * ni posarse intacta sin explotar, debe impactar (nivel >= 2), abrir cráter y conservar el 100% de los bloques.
	 */
	private static void planTerrenoIrregular(MinecraftClient mc, IntegratedServer srv, UUID id) {
		final int IMPACT = Payloads.TaktFx.IMPACT;
		step(2, "ladera: preparar escalón", () -> {
			srvA = -1;
			srvB = -1;
			server(srv, id, (sp, pw) -> {
				ServerWorld w = sp.getServerWorld();
				clearField(w, true);
				patchFieldRing(w);
				patch(w, gx0 - 16, gx0 + 16, gz0 - 34, gz0 + 2, gfy);
				clearField(w, true);
				BlockPos.Mutable m = new BlockPos.Mutable();
				for (int x = gx0 - 6; x <= gx0 + 6; x++) {
					for (int z = gz0 - 22; z <= gz0 - 15; z++) {
						w.setBlockState(m.set(x, gfy + 1, z), (z <= gz0 - 17 ? Blocks.DIRT : Blocks.GRASS_BLOCK).getDefaultState(), 2 | 16);
						if (z <= gz0 - 17) {
							w.setBlockState(m.set(x, gfy + 2, z), Blocks.GRASS_BLOCK.getDefaultState(), 2 | 16);
						}
					}
				}
				sp.networkHandler.requestTeleport(gx0 + 0.5, gfy + 1, gz0 - 7.5, 180f, 0f);
				dropped0 = PieceEntity.DROPPED;
				crater0 = Crater.last;
				srvB = fieldItems(w);
				srvA = fieldCount(w);
			});
		});
		waitFor("ladera: preparado", 60, "el cliente no ve la ladera", () -> srvA >= 0 && srvB >= 0
			&& mc.world.getBlockState(new BlockPos(gx0, gfy + 1, gz0 - 15)).isOf(Blocks.GRASS_BLOCK), () -> {
			solid0 = srvA + 2;
			items0 = srvB;
		});
		step(4, "ladera: lanza pieza pequeña", () -> {
			tsnap(IMPACT);
			srvC = -1;
			server(srv, id, (sp, pw) -> {
				BlockSet set = new BlockSet();
				set.add(0, 0, 0, Blocks.ANDESITE.getDefaultState(), null);
				set.add(1, 0, 0, Blocks.ANDESITE.getDefaultState(), null);
				set.normalize();
				PieceEntity pe = PieceEntity.spawn(sp.getServerWorld(), set, new Vec3d(gx0 - 0.5, gfy + 1.15, gz0 - 8.5), null, sp.getUuid());
				pe.release(new Vec3d(0, 0.08, -1.7), true);
				srvC = pe.getId();
			});
		});
		waitFor("ladera: impacto", 70, "la pieza lanzada contra escalón no explotó", () -> tgot(IMPACT), () -> {
			Payloads.TaktFx f = TaktFxClient.LAST[IMPACT];
			if (!tgot(IMPACT) || f == null) return;
			check(f.entityId() == srvC && f.state() >= 2 && f.a() > 0,
				"pieza pequeña lanzada contra escalón explota y abre cráter en la ladera",
				"pieza " + f.entityId() + " (esperada " + srvC + "), nivel " + f.state() + ", radio " + fmt(f.a())
					+ " en dy=" + fmt(f.y() - gfy));
		});
		settleField(srv, id, "ladera");
		step(2, "ladera: cuentas", () -> {
			srvA = -1;
			srvB = -1;
			srvC = 0;
			server(srv, id, (sp, pw) -> {
				ServerWorld w = sp.getServerWorld();
				Crater.Result c = Crater.last;
				logCrater(c);
				srvC = (c != null && c != crater0 && c.balanced() ? 1 : 0) | (PieceEntity.DROPPED == dropped0 ? 2 : 0);
				srvB = fieldItems(w);
				srvA = fieldCount(w);
			});
		});
		waitFor("ladera: conservado", 20, "el servidor no devolvió las cuentas de la ladera", () -> srvA >= 0 && srvB >= 0, () -> {
			check((srvC & 1) != 0, "el cráter en la ladera devuelve todos los bloques que quita", "no hay cráter equilibrado");
			check((srvC & 2) != 0 && srvB == items0, "ningún bloque cae como objeto en la ladera",
				"DROPPED " + dropped0 + " -> " + PieceEntity.DROPPED + ", objetos " + items0 + " -> " + srvB);
			check(srvA == solid0, "conservación de bloques en la ladera", "antes " + solid0 + ", después " + srvA);
		});
	}

	/**
	 * (k2d) Bombardeo orbital (Takt Orbital): con R+F sin piezas en mano arranca automáticamente un anillo de rocas,
	 * lo eleva al techo del ROOM y con un segundo R+F las descarga en picada abriendo cráteres en cadena con 100% de
	 * conservación de bloques.
	 */
	private static void planBombardeoOrbital(MinecraftClient mc, IntegratedServer srv, UUID id) {
		final int IMPACT = Payloads.TaktFx.IMPACT;
		step(2, "orbital: preparar campo", () -> {
			srvA = -1;
			srvB = -1;
			server(srv, id, (sp, pw) -> {
				ServerWorld w = sp.getServerWorld();
				Takt.dropAll(sp, pw, false);
				Takt.cancelPending(sp, pw);
				clearField(w, true);
				patch(w, gx0 - 16, gx0 + 16, gz0 - 34, gz0 + 2, gfy);
				GroundCuts.clearOwner(sp.getUuid());
				pw.ripBudget = OpeConfig.TAKT_RIP_BUDGET;
				sp.networkHandler.requestTeleport(gx0 + 0.5, gfy + 1, gz0 - 14.5, 180f, 20f);
				sp.setYaw(180f);
				sp.setPitch(20f);
				dropped0 = PieceEntity.DROPPED;
				crater0 = Crater.last;
				srvB = fieldItems(w);
				srvA = fieldCount(w);
			});
		});
		waitFor("orbital: preparado", 60, "el cliente no ve el campo orbital", () -> srvA >= 0 && srvB >= 0
			&& mc.player.getZ() < gz0 - 13 && Math.abs(mc.player.getPitch() - 20f) < 5f, () -> {
			solid0 = srvA;
			items0 = srvB;
		});
		step(2, "orbital: elevar anillo", () -> {
			srvA = 0;
			srvB = 0;
			srvC = 0;
			server(srv, id, (sp, pw) -> {
				Takt.order(sp, pw, Actions.ORDER_ORBIT);
				srvC = pw.taktMode == PlayerPower.TaktMode.ORBIT && (pw.takt.size() + Takt.pendingRips(pw)) >= 3 ? 1 : 0;
			});
		});
		waitFor("orbital: anillo invocado", 10, "R+F sin piezas no invocó el anillo orbital", () -> srvC == 1, () -> {
			check(srvC == 1, "R+F sin piezas arranca un anillo orbital en el ROOM", "srvC=" + srvC);
		});
		repeatUntil("orbital: anillo elevado", 4, 60, () -> server(srv, id, (sp, pw) -> {
			ServerWorld w = sp.getServerWorld();
			int maxY = Integer.MIN_VALUE;
			for (Entity e : Takt.resolve(sp, pw)) maxY = Math.max(maxY, (int) Math.floor(e.getY()));
			srvA = pw.takt.size();
			srvB = Takt.pendingRips(pw);
			srvC = maxY;
		}), () -> srvB == 0 && srvA >= 3 && srvC >= gfy + 5);
		waitFor("orbital: verifica altura", 10, "el anillo orbital no se elevó sobre el ROOM",
			() -> srvB == 0 && srvA >= 3 && srvC >= gfy + 5, () -> {
			check(srvA >= 3 && srvC >= gfy + 5,
				"el anillo orbital eleva al menos 3 trozos a gran altura en el ROOM",
				"piezas=" + srvA + " pendientes=" + srvB + " maxY=" + srvC + " suelo=" + gfy);
		});
		step(2, "orbital: descargar bombardeo", () -> {
			tsnap(IMPACT);
			srvC = 0;
			server(srv, id, (sp, pw) -> {
				sp.setYaw(180f);
				sp.setPitch(20f);
				Takt.order(sp, pw, Actions.ORDER_ORBIT);
				srvC = pw.orbitalBarrageTicks > 0 ? 1 : 0;
			});
		});
		waitFor("orbital: impacto", 100, "el bombardeo orbital no impactó contra el suelo", () -> srvC == 1 && tgot(IMPACT), () -> {
			Payloads.TaktFx f = TaktFxClient.LAST[IMPACT];
			check(srvC == 1 && f != null && f.state() >= 2 && f.a() > 0,
				"el bombardeo orbital descarga piezas en picada y abre cráteres",
				"barrage=" + srvC + (f == null ? ", sin impacto" : ", nivel=" + f.state() + ", radio=" + fmt(f.a())));
		});
		settleField(srv, id, "orbital");
		step(2, "orbital: cuentas", () -> {
			srvA = -1;
			srvB = -1;
			srvC = 0;
			server(srv, id, (sp, pw) -> {
				ServerWorld w = sp.getServerWorld();
				Crater.Result c = Crater.last;
				logCrater(c);
				srvC = (c != null && c != crater0 && c.balanced() ? 1 : 0)
					| (PieceEntity.DROPPED == dropped0 ? 2 : 0)
					| (pw.orbitalBarrageTicks == 0 ? 4 : 0);
				pw.taktMode = PlayerPower.TaktMode.HAND;
				pw.orbitalBarrageTicks = 0;
				pw.dirty = true;
				srvB = fieldItems(w);
				srvA = fieldCount(w);
			});
		});
		waitFor("orbital: conservado", 20, "el servidor no devolvió las cuentas orbitales", () -> srvA >= 0 && srvB >= 0, () -> {
			check((srvC & 1) != 0, "los cráteres orbitales devuelven todos los bloques que quitan", "cráter no equilibrado");
			check((srvC & 2) != 0 && srvB == items0, "el bombardeo orbital no suelta objetos",
				"DROPPED " + dropped0 + " -> " + PieceEntity.DROPPED + ", objetos " + items0 + " -> " + srvB);
			check(srvA == solid0, "conservación de bloques en el bombardeo orbital", "antes " + solid0 + ", después " + srvA);
		});
	}

	/**
	 * (k2e) Shambles Táctico con tus propios trozos en vuelo:
	 * 1) Al lanzar una pieza y usar Shambles en 1 toque, el jugador pasa a su centro con impulso/ventana aérea y la roca
	 *    conserva velocidad en la posición anterior del jugador sin dañarlo.
	 * 2) Con un enemigo marcado, usar Shambles tras lanzar un trozo cambia al enemigo por la roca y la redirige contra él.
	 */
	private static void planShamblesTactico(MinecraftClient mc, IntegratedServer srv, UUID id) {
		step(2, "shamblesT: preparar", () -> {
			srvA = 0;
			srvB = -1;
			srvC = -1;
			server(srv, id, (sp, pw) -> {
				ServerWorld w = sp.getServerWorld();
				Takt.dropAll(sp, pw, false);
				Takt.cancelPending(sp, pw);
				clearField(w, true);
				patch(w, gx0 - 16, gx0 + 16, gz0 - 34, gz0 + 2, gfy);
				pw.clearAerialWindow();
				pw.aerialChain = 0;
				pw.recentThrownPieces.clear();
				pw.lastThrownPieceId = -1;
				pw.shamblesMark = -1;
				pw.cooldowns[Tech.SHAMBLES.ordinal()] = 0;
				sp.networkHandler.requestTeleport(gx0 + 0.5, gfy + 1, gz0 - 8.5, 180f, -15f);
				sp.setYaw(180f);
				sp.setPitch(-15f);
				srvA = 1;
			});
		});
		waitFor("shamblesT: preparado", 40, "no se preparó el campo de Shambles táctico",
			() -> srvA == 1 && mc.player.getZ() < gz0 - 7 && mc.player.getZ() > gz0 - 10, null);
		step(2, "shamblesT: lanza pieza", () -> server(srv, id, (sp, pw) -> {
			ServerWorld w = sp.getServerWorld();
			BlockSet set = new BlockSet();
			for (int x = 0; x < 2; x++)
				for (int y = 0; y < 2; y++)
					for (int z = 0; z < 2; z++) set.add(x, y, z, Blocks.ANDESITE.getDefaultState(), null);
			set.normalize();
			PieceEntity pe = PieceEntity.spawn(w, set, new Vec3d(gx0 - 0.5, gfy + 2.0, gz0 - 10.5), null, sp.getUuid());
			pe.release(new Vec3d(0, 0.32, -1.75), true);
			pw.recordThrownPiece(pe.getId(), w.getTime());
			srvB = pe.getId();
		}));
		step(2, "shamblesT: swap jugador", () -> {
			srvA = -1;
			server(srv, id, (sp, pw) -> {
				sp.setYaw(180f);
				sp.setPitch(-8f);
				Shambles.use(sp, pw, false);
				Entity ent = sp.getServerWorld().getEntityById(srvB);
				boolean swappedPlayer = sp.getY() >= gfy + 2.0 && sp.getZ() <= gz0 - 11.0;
				boolean rockKept = ent instanceof PieceEntity pe && pe.thrown && pe.getVelocity().length() > 0.5
					&& pe.ownerGraceTicks > 0 && pe.getZ() > gz0 - 10.0;
				boolean aerialOpen = pw.aerialWindowTicks > 0 && pw.aerialChain >= 1;
				srvA = (swappedPlayer ? 1 : 0) | (rockKept ? 2 : 0) | (aerialOpen ? 4 : 0);
				if (ent != null) ent.discard();
				pw.clearAerialWindow();
				pw.aerialChain = 0;
				pw.cooldowns[Tech.SHAMBLES.ordinal()] = 0;
			});
		});
		waitFor("shamblesT: verifica jugador", 20, "falló Shambles táctico jugador <-> trozo", () -> srvA >= 0, () -> {
			check((srvA & 1) != 0, "Shambles 1 toque lleva al jugador al centro de la pieza en vuelo", "srvA=" + srvA);
			check((srvA & 2) != 0, "el trozo conserva su impulso y gracia de dueño en la posición previa del jugador", "srvA=" + srvA);
			check((srvA & 4) != 0, "se abre la ventana aérea de remate tras el intercambio", "srvA=" + srvA);
		});
		step(2, "shamblesT: preparar enemigo", () -> {
			srvA = -1;
			srvB = -1;
			srvC = -1;
			server(srv, id, (sp, pw) -> {
				ServerWorld w = sp.getServerWorld();
				pw.clearAerialWindow();
				pw.aerialChain = 0;
				pw.recentThrownPieces.clear();
				pw.lastThrownPieceId = -1;
				pw.shamblesMark = -1;
				pw.cooldowns[Tech.SHAMBLES.ordinal()] = 0;
				sp.networkHandler.requestTeleport(gx0 + 0.5, gfy + 1, gz0 - 6.5, 180f, -20f);
				sp.setYaw(180f);
				sp.setPitch(-20f);
				Shambles.syncAerialWindow(sp, pw);
				PigEntity pig = EntityType.PIG.create(w);
				if (pig != null) {
					pig.refreshPositionAndAngles(gx0 + 0.5, gfy + 1, gz0 - 10.5, 0f, 0f);
					w.spawnEntity(pig);
					pw.shamblesMark = pig.getId();
					pw.shamblesMarkTicks = OpeConfig.SHAMBLES_MARK_TICKS;
					srvC = pig.getId();
				}
				srvA = 1;
			});
		});
		waitFor("shamblesT: enemigo listo", 40, "no se preparó el enemigo táctico",
			() -> srvA == 1 && mc.player.getY() < gfy + 1.5, null);
		step(1, "shamblesT: lanza roca enemigo", () -> server(srv, id, (sp, pw) -> {
			ServerWorld w = sp.getServerWorld();
			BlockSet set = new BlockSet();
			for (int x = 0; x < 2; x++)
				for (int y = 0; y < 2; y++)
					for (int z = 0; z < 2; z++) set.add(x, y, z, Blocks.ANDESITE.getDefaultState(), null);
			set.normalize();
			PieceEntity pe = PieceEntity.spawn(w, set, new Vec3d(gx0 - 0.5, gfy + 5.5, gz0 - 15.5), null, sp.getUuid());
			pe.release(new Vec3d(0, 0.12, -1.2), true);
			pw.recordThrownPiece(pe.getId(), w.getTime());
			srvB = pe.getId();
		}));
		step(1, "shamblesT: swap enemigo", () -> {
			srvA = -1;
			server(srv, id, (sp, pw) -> {
				ServerWorld w = sp.getServerWorld();
				sp.setYaw(180f);
				sp.setPitch(-35f);   // no apunta al cerdo: el trozo en vuelo tiene prioridad frente a emptyPoint
				Shambles.use(sp, pw, false);
				Entity enemy = w.getEntityById(srvC);
				Entity rock = w.getEntityById(srvB);
				boolean enemyLifted = enemy != null && enemy.getY() >= gfy + 4.0 && enemy.getZ() <= gz0 - 13.5;
				boolean rockReaimed = rock instanceof PieceEntity pe && pe.getZ() >= gz0 - 12.5 && pe.getVelocity().z < -0.4;
				srvA = (enemyLifted ? 1 : 0) | (rockReaimed ? 2 : 0);
				if (enemy != null) enemy.discard();
				if (rock != null) rock.discard();
				clearField(w, true);
				patch(w, gx0 - 16, gx0 + 16, gz0 - 34, gz0 + 2, gfy);
				pw.clearAerialWindow();
				pw.aerialChain = 0;
				pw.recentThrownPieces.clear();
				pw.lastThrownPieceId = -1;
				pw.shamblesMark = -1;
				pw.cooldowns[Tech.SHAMBLES.ordinal()] = 0;
			});
		});
		waitFor("shamblesT: verifica enemigo", 20, "falló Shambles táctico enemigo <-> trozo", () -> srvA >= 0, () -> {
			check((srvA & 1) != 0, "enemigo marcado pasa al aire en lugar de la roca lanzada", "srvA=" + srvA);
			check((srvA & 2) != 0, "la roca redirige su velocidad hacia el enemigo intercambiado", "srvA=" + srvA);
		});
	}

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

	private static BlockSet uniformCube(int side, BlockState st) {
		BlockSet set = new BlockSet();
		for (int x = 0; x < side; x++)
			for (int y = 0; y < side; y++)
				for (int z = 0; z < side; z++) set.add(x, y, z, st, null);
		set.normalize();
		return set;
	}

	private static void patchElemArena(ServerWorld w, int ax0, int ay0, int az0) {
		BlockState air = Blocks.AIR.getDefaultState(), stone = Blocks.STONE.getDefaultState();
		BlockPos.Mutable m = new BlockPos.Mutable();
		for (int x = ax0 - 26; x <= ax0 + 26; x++) {
			for (int z = az0 - 26; z <= az0 + 12; z++) {
				for (int y = ay0 - 14; y <= ay0 + 22; y++) {
					w.setBlockState(m.set(x, y, z), y <= ay0 ? stone : air, 2 | 16);
				}
			}
		}
	}

	private static void clearElemArena(ServerWorld w, int ax0, int ay0, int az0) {
		Box b = new Box(ax0 - 28, ay0 - 16, az0 - 28, ax0 + 29, ay0 + 24, az0 + 14);
		for (Entity e : w.getEntitiesByClass(Entity.class, b,
			en -> en instanceof ItemEntity || en instanceof MobEntity || en instanceof PieceEntity)) e.discard();
	}

	private static ZombieEntity spawnDummyZombie(ServerWorld w, double x, double y, double z) {
		ZombieEntity zmb = EntityType.ZOMBIE.create(w);
		if (zmb != null) {
			zmb.refreshPositionAndAngles(x, y, z, 0f, 0f);
			zmb.setAiDisabled(true);
			zmb.setPersistent();
			// Muñeco: con 200 de vida ninguna prueba elemental (T1-T4) lo mata. Si muriera (fuego, terrones), soltaría su
			// botín al azar (carne podrida 0-2) dentro de la caja donde se cuentan los objetos.
			EntityAttributeInstance maxHp = zmb.getAttributeInstance(EntityAttributes.GENERIC_MAX_HEALTH);
			if (maxHp != null) maxHp.setBaseValue(200.0);
			zmb.setHealth(zmb.getMaxHealth());
			w.spawnEntity(zmb);
		}
		return zmb;
	}

	/**
	 * (k2f) Efectos elementales y físicos por bloque (T0-T4): clasificación por tags/densidad/BlockSet,
	 * conducción eléctrica (Counter Shock / Shock Wille), explosión ígnea + fuego efímero, ráfaga cryo +
	 * ChillTracker, y fractura/perforación heavy (Obsidiana/Deepslate) con 100% de conservación y 0 ítems.
	 */
	private static void planEfectosElementales(MinecraftClient mc, IntegratedServer srv, UUID id) {
		final int DX = 80;

		// Preparar y teletransportar jugador a la arena aislada (x = gx0 + 80, noche para que no ardan zombies)
		step(2, "elem: preparar", () -> {
			srvA = -1;
			server(srv, id, (sp, pw) -> {
				ServerWorld w = sp.getServerWorld();
				int ax0 = gx0 + DX, ay0 = gfy, az0 = gz0;
				Takt.dropAll(sp, pw, false);
				Forge.dropWeapons(sp, pw);
				w.setTimeOfDay(18000);
				pw.room = new Room(w.getRegistryKey(), new Vec3d(ax0 + 0.5, ay0 + 2, az0 - 6.0), 28);
				pw.room.state = Room.State.FIXED;
				PowerManager.broadcastRoom(sp, 0, pw.room);
				patchElemArena(w, ax0, ay0, az0);
				clearElemArena(w, ax0, ay0, az0);
				sp.networkHandler.requestTeleport(ax0 + 0.5, ay0 + 1, az0 + 0.5, 180f, 0f);
				sp.setYaw(180f);
				sp.setPitch(0f);
				dropped0 = PieceEntity.DROPPED;
				srvA = 1;
			});
		});
		waitFor("elem: arena lista", 60, "el jugador no llegó a la arena elemental",
			() -> srvA == 1 && mc.player.getX() > gx0 + 60, null);

		// T0: Clasificación por tags, densidad, masa y BlockSet mixto
		step(1, "elemT0: clasificacion", () -> {
			srvA = -1;
			srvMsg = "";
			server(srv, id, (sp, pw) -> {
				StringBuilder err = new StringBuilder();
				if (GroundRules.trait(Blocks.WAXED_OXIDIZED_CUT_COPPER.getDefaultState()) != MaterialTrait.CONDUCTIVE) err.append("waxedCopper ");
				if (GroundRules.trait(Blocks.IRON_BARS.getDefaultState()) != MaterialTrait.CONDUCTIVE) err.append("ironBars ");
				if (GroundRules.trait(Blocks.MAGMA_BLOCK.getDefaultState()) != MaterialTrait.IGNEOUS) err.append("magma ");
				if (GroundRules.trait(Blocks.PACKED_ICE.getDefaultState()) != MaterialTrait.CRYO) err.append("packedIce ");
				if (GroundRules.trait(Blocks.DEEPSLATE_TILES.getDefaultState()) != MaterialTrait.HEAVY_PIERCING) err.append("deepslateTiles ");
				if (GroundRules.trait(Blocks.NETHERITE_BLOCK.getDefaultState()) != MaterialTrait.HEAVY_PIERCING) err.append("netherite ");
				if (GroundRules.trait(Blocks.STONE.getDefaultState()) != MaterialTrait.NONE) err.append("stone ");
				if (GroundRules.trait(Blocks.ANDESITE.getDefaultState()) != MaterialTrait.NONE) err.append("andesite ");
				if (!(GroundRules.density(Blocks.OBSIDIAN.getDefaultState()) > GroundRules.density(Blocks.STONE.getDefaultState()))) err.append("densObs ");
				if (!(GroundRules.density(Blocks.ICE.getDefaultState()) < 1000.0)) err.append("densIce ");

				PieceEntity obsP = new PieceEntity(dev.opeope.registry.ModEntities.PIECE, sp.getServerWorld());
				obsP.setBlocks(uniformCube(2, Blocks.OBSIDIAN.getDefaultState()));
				PieceEntity stnP = new PieceEntity(dev.opeope.registry.ModEntities.PIECE, sp.getServerWorld());
				stnP.setBlocks(uniformCube(2, Blocks.STONE.getDefaultState()));
				if (!(obsP.massKg() > stnP.massKg())) err.append("massKg ");

				BlockSet mix30 = new BlockSet();
				for (int i = 0; i < 7; i++) mix30.add(i, 0, 0, Blocks.STONE.getDefaultState(), null);
				for (int i = 7; i < 10; i++) mix30.add(i, 0, 0, Blocks.COPPER_BLOCK.getDefaultState(), null);
				if (mix30.trait() != MaterialTrait.NONE) err.append("mix30=" + mix30.trait() + " ");

				BlockSet mix60 = new BlockSet();
				for (int i = 0; i < 6; i++) mix60.add(i, 0, 0, Blocks.COPPER_BLOCK.getDefaultState(), null);
				for (int i = 6; i < 10; i++) mix60.add(i, 0, 0, Blocks.STONE.getDefaultState(), null);
				if (mix60.trait() != MaterialTrait.CONDUCTIVE) err.append("mix60=" + mix60.trait() + " ");

				srvMsg = err.toString();
				srvA = err.isEmpty() ? 1 : 0;
			});
		});
		waitFor("elemT0: verificado", 20, "T0 no devolvió resultado", () -> srvA >= 0,
			() -> check(srvA == 1, "T0 clasificación MaterialTrait, densidades, masa y umbrales de BlockSet", srvMsg));

		// T1a: Cadena eléctrica en Counter Shock (cobre vs control piedra)
		step(2, "elemT1: counterShock", () -> {
			srvA = -1;
			srvMsg = "";
			server(srv, id, (sp, pw) -> {
				ServerWorld w = sp.getServerWorld();
				int ax0 = gx0 + DX, ay0 = gfy, az0 = gz0;
				clearElemArena(w, ax0, ay0, az0);
				sp.networkHandler.requestTeleport(ax0 + 0.5, ay0 + 1, az0 + 0.5, 180f, 0f);
				sp.setYaw(180f);
				sp.setPitch(0f);

				ZombieEntity zA = spawnDummyZombie(w, ax0 + 0.5, ay0 + 1, az0 - 2.5);
				ZombieEntity zB = spawnDummyZombie(w, ax0 + 0.5, ay0 + 1, az0 - 9.5);
				PieceEntity copperStake = PieceEntity.spawn(w, uniformCube(1, Blocks.COPPER_BLOCK.getDefaultState()),
					new Vec3d(ax0, ay0 + 1.0, az0 - 6.5), null, sp.getUuid());
				copperStake.weapon = PieceEntity.Weapon.STAKE;
				copperStake.wState = 2;
				copperStake.setFlag(PieceEntity.F_WEAPON, true);

				pw.cooldowns[Tech.COUNTER.ordinal()] = 0;
				Techniques.counterShock(sp, pw);
				boolean bHit = zB != null && zB.getHealth() < zB.getMaxHealth();
				boolean bStun = zB != null && zB.hasStatusEffect(ModEffects.STUN);

				// Control con estaca de piedra
				clearElemArena(w, ax0, ay0, az0);
				ZombieEntity cA = spawnDummyZombie(w, ax0 + 0.5, ay0 + 1, az0 - 2.5);
				ZombieEntity cB = spawnDummyZombie(w, ax0 + 0.5, ay0 + 1, az0 - 9.5);
				PieceEntity stoneStake = PieceEntity.spawn(w, uniformCube(1, Blocks.STONE.getDefaultState()),
					new Vec3d(ax0, ay0 + 1.0, az0 - 6.5), null, sp.getUuid());
				stoneStake.weapon = PieceEntity.Weapon.STAKE;
				stoneStake.wState = 2;
				stoneStake.setFlag(PieceEntity.F_WEAPON, true);

				pw.cooldowns[Tech.COUNTER.ordinal()] = 0;
				Techniques.counterShock(sp, pw);
				boolean ctrlIntact = cB != null && cB.getHealth() >= cB.getMaxHealth() && !cB.hasStatusEffect(ModEffects.STUN);

				// Shock Wille con estaca de hierro a <= 2.5m del rayo y zombie C fuera del lineBox (dx = 6.7 > 3.2)
				clearElemArena(w, ax0, ay0, az0);
				ZombieEntity zC = spawnDummyZombie(w, ax0 + 7.2, ay0 + 1, az0 - 10.0);
				PieceEntity ironStake = PieceEntity.spawn(w, uniformCube(1, Blocks.IRON_BLOCK.getDefaultState()),
					new Vec3d(ax0 + 2.0, ay0 + 1.5, az0 - 10.5), null, sp.getUuid());
				ironStake.weapon = PieceEntity.Weapon.STAKE;
				ironStake.wState = 2;
				ironStake.setFlag(PieceEntity.F_WEAPON, true);

				pw.cooldowns[Tech.SHOCK_WILLE.ordinal()] = 0;
				Techniques.shockWille(sp, pw);
				boolean cHit = zC != null && zC.getHealth() < zC.getMaxHealth() && zC.hasStatusEffect(ModEffects.STUN);
				int items = w.getEntitiesByClass(ItemEntity.class, new Box(ax0 - 22, ay0 - 16, az0 - 26, ax0 + 23, ay0 + 24, az0 + 14), e -> true).size();
				clearElemArena(w, ax0, ay0, az0);

				srvMsg = "bHit=" + bHit + " bStun=" + bStun + " ctrl=" + ctrlIntact + " shockWille=" + cHit + " items=" + items;
				srvA = (bHit && bStun ? 1 : 0) | (ctrlIntact ? 2 : 0) | (cHit ? 4 : 0)
					| (items == 0 && PieceEntity.DROPPED == dropped0 ? 8 : 0);
			});
		});
		waitFor("elemT1: verificado", 20, "T1 no devolvió resultado", () -> srvA >= 0, () -> {
			check((srvA & 1) != 0, "T1 Counter Shock encadena daño + STUN a través de pieza conductora", srvMsg);
			check((srvA & 2) != 0, "T1 control con estaca de piedra NO encadena descarga", srvMsg);
			check((srvA & 4) != 0, "T1 Shock Wille encadena descarga por estaca de hierro cercana al rayo", srvMsg);
			check((srvA & 8) != 0, "T1 cadena eléctrica sin objetos dropeados", srvMsg);
		});

		// T2: Explosión incendiaria con Magma vs control Piedra + TTL de fuegos efímeros
		step(2, "elemT2: lanzar magma", () -> {
			srvA = -1;
			elemZ1 = -1;
			elemZ2 = -1;
			server(srv, id, (sp, pw) -> {
				ServerWorld w = sp.getServerWorld();
				int ax0 = gx0 + DX, ay0 = gfy, az0 = gz0;
				patchElemArena(w, ax0, ay0, az0);
				clearElemArena(w, ax0, ay0, az0);

				ZombieEntity zMagma = spawnDummyZombie(w, ax0 + 0.5, ay0 + 1, az0 - 7.5);
				ZombieEntity zCtrl = spawnDummyZombie(w, ax0 + 9.5, ay0 + 1, az0 - 7.5);
				elemZ1 = zMagma != null ? zMagma.getId() : -1;
				elemZ2 = zCtrl != null ? zCtrl.getId() : -1;

				PieceEntity pMagma = PieceEntity.spawn(w, uniformCube(2, Blocks.MAGMA_BLOCK.getDefaultState()),
					new Vec3d(ax0 - 0.5, ay0 + 3.0, az0 - 5.0), null, sp.getUuid());
				pMagma.release(new Vec3d(0, -0.35, -1.35), true);

				PieceEntity pStone = PieceEntity.spawn(w, uniformCube(2, Blocks.STONE.getDefaultState()),
					new Vec3d(ax0 + 8.5, ay0 + 3.0, az0 - 5.0), null, sp.getUuid());
				pStone.release(new Vec3d(0, -0.35, -1.35), true);
			});
		});
		step(12, "elemT2: comprobar fuego", () -> {
			srvA = -1;
			server(srv, id, (sp, pw) -> {
				ServerWorld w = sp.getServerWorld();
				Entity e1 = w.getEntityById(elemZ1), e2 = w.getEntityById(elemZ2);
				boolean onFire = e1 instanceof ZombieEntity z1 && z1.isOnFire();
				boolean ctrlClean = e2 instanceof ZombieEntity z2 && !z2.isOnFire();
				srvMsg = "magmaOnFire=" + onFire + " ctrlClean=" + ctrlClean;
				srvA = (onFire ? 1 : 0) | (ctrlClean ? 2 : 0);
			});
		});
		waitFor("elemT2: check encendido", 20, "T2 fallo comprobando fuego", () -> srvA >= 0, () -> {
			check((srvA & 1) != 0, "T2 impacto de magma prende fuego al enemigo", srvMsg);
			check((srvA & 2) != 0, "T2 impacto de piedra no prende fuego al control", srvMsg);
		});
		step(OpeConfig.IGNEOUS_FIRE_TTL + 12, "elemT2: comprobar TTL fuego", () -> {
			srvB = -1;
			server(srv, id, (sp, pw) -> {
				ServerWorld w = sp.getServerWorld();
				int ax0 = gx0 + DX, ay0 = gfy, az0 = gz0;
				int fires = 0;
				BlockPos.Mutable m = new BlockPos.Mutable();
				for (int x = ax0 - 16; x <= ax0 + 16; x++)
					for (int y = ay0 - 4; y <= ay0 + 10; y++)
						for (int z = az0 - 20; z <= az0 + 4; z++)
							if (w.getBlockState(m.set(x, y, z)).isIn(BlockTags.FIRE)) fires++;
				int items = w.getEntitiesByClass(ItemEntity.class, new Box(ax0 - 22, ay0 - 16, az0 - 26, ax0 + 23, ay0 + 24, az0 + 14), e -> true).size();
				srvMsg = "fires=" + fires + " items=" + items;
				srvB = (fires == 0 && items == 0 && PieceEntity.DROPPED == dropped0) ? 1 : 0;
			});
		});
		waitFor("elemT2: check TTL", 20, "T2 TTL no devuelto", () -> srvB >= 0,
			() -> check(srvB == 1, "T2 los fuegos efímeros se apagan solos tras IGNEOUS_FIRE_TTL sin drops", srvMsg));

		// T3: Cryo (piscina 5x5 de agua fuente, control piedra vs PACKED_ICE, SLOWNESS y ChillTracker 20 ticks)
		step(2, "elemT3: piscina y control piedra", () -> {
			srvA = -1;
			server(srv, id, (sp, pw) -> {
				ServerWorld w = sp.getServerWorld();
				int ax0 = gx0 + DX, ay0 = gfy, az0 = gz0;
				patchElemArena(w, ax0, ay0, az0);
				clearElemArena(w, ax0, ay0, az0);
				BlockPos.Mutable m = new BlockPos.Mutable();
				for (int x = ax0 - 3; x <= ax0 + 3; x++) {
					for (int z = az0 - 11; z <= az0 - 5; z++) {
						boolean inPool = x >= ax0 - 2 && x <= ax0 + 2 && z >= az0 - 10 && z <= az0 - 6;
						w.setBlockState(m.set(x, ay0 - 3, z), Blocks.STONE.getDefaultState(), 2 | 16);
						for (int y = ay0 - 2; y <= ay0; y++) {
							w.setBlockState(m.set(x, y, z), (inPool ? Blocks.WATER : Blocks.STONE).getDefaultState(), 2 | 16);
						}
					}
				}
				PieceEntity pCtrl = PieceEntity.spawn(w, uniformCube(2, Blocks.STONE.getDefaultState()),
					new Vec3d(ax0 - 0.5, ay0 + 4.0, az0 - 6.0), null, sp.getUuid());
				pCtrl.release(new Vec3d(0, -0.8, -0.7), true);
			});
		});
		step(12, "elemT3: check control piedra", () -> {
			srvA = -1;
			elemZ1 = -1;
			server(srv, id, (sp, pw) -> {
				ServerWorld w = sp.getServerWorld();
				int ax0 = gx0 + DX, ay0 = gfy, az0 = gz0;
				int water = 0;
				BlockPos.Mutable m = new BlockPos.Mutable();
				for (int x = ax0 - 2; x <= ax0 + 2; x++)
					for (int z = az0 - 10; z <= az0 - 6; z++)
						if (w.getBlockState(m.set(x, ay0, z)).isOf(Blocks.WATER) && w.getFluidState(m).isStill()) water++;
				srvMsg = "ctrlWater=" + water;
				srvA = water == 25 ? 1 : 0;

				// Limpiar piezas del control, asegurar piscina 3 de hondo intacta y lanzar PACKED_ICE con Zombie al borde
				clearElemArena(w, ax0, ay0, az0);
				patchElemArena(w, ax0, ay0, az0);
				for (int x = ax0 - 3; x <= ax0 + 3; x++) {
					for (int z = az0 - 11; z <= az0 - 5; z++) {
						boolean inPool = x >= ax0 - 2 && x <= ax0 + 2 && z >= az0 - 10 && z <= az0 - 6;
						w.setBlockState(m.set(x, ay0 - 3, z), Blocks.STONE.getDefaultState(), 2 | 16);
						for (int y = ay0 - 2; y <= ay0; y++) {
							w.setBlockState(m.set(x, y, z), (inPool ? Blocks.WATER : Blocks.STONE).getDefaultState(), 2 | 16);
						}
					}
				}
				ZombieEntity zF = spawnDummyZombie(w, ax0 + 0.5, ay0 + 1, az0 - 10.5);
				elemZ1 = zF != null ? zF.getId() : -1;
				PieceEntity pIce = PieceEntity.spawn(w, uniformCube(2, Blocks.PACKED_ICE.getDefaultState()),
					new Vec3d(ax0 - 0.5, ay0 + 4.0, az0 - 6.0), null, sp.getUuid());
				pIce.release(new Vec3d(0, -1.15, -0.75), true);
			});
		});
		waitFor("elemT3: verificado control", 20, "T3 control no devuelto", () -> srvA >= 0,
			() -> check(srvA == 1, "T3 control piedra mantiene intacta la piscina 5x5 de agua", srvMsg));
		step(12, "elemT3: check cryo inicio", () -> {
			srvA = -1;
			server(srv, id, (sp, pw) -> {
				ServerWorld w = sp.getServerWorld();
				int ax0 = gx0 + DX, ay0 = gfy, az0 = gz0;
				int water = 0, frosted = 0;
				BlockPos.Mutable m = new BlockPos.Mutable();
				for (int x = ax0 - 3; x <= ax0 + 3; x++) {
					for (int z = az0 - 11; z <= az0 - 5; z++) {
						BlockState st = w.getBlockState(m.set(x, ay0, z));
						if (st.isOf(Blocks.WATER) && w.getFluidState(m).isStill()) water++;
						if (st.isOf(Blocks.FROSTED_ICE) || st.isOf(Blocks.ICE)) frosted++;
					}
				}
				Entity ez = w.getEntityById(elemZ1);
				boolean slow = ez instanceof ZombieEntity z && z.hasStatusEffect(StatusEffects.SLOWNESS);
				boolean frz = ez instanceof ZombieEntity z && z.getFrozenTicks() > 0;
				srvMsg = "water=" + water + " frosted=" + frosted + " slow=" + slow + " frz=" + frz;
				srvA = (water < 25 && frosted > 0 ? 1 : 0) | (slow && frz ? 2 : 0);
			});
		});
		step(20, "elemT3: check ChillTracker 20t", () -> {
			srvB = -1;
			server(srv, id, (sp, pw) -> {
				ServerWorld w = sp.getServerWorld();
				int ax0 = gx0 + DX, ay0 = gfy, az0 = gz0;
				Entity ez = w.getEntityById(elemZ1);
				boolean frz20 = ez instanceof ZombieEntity z && z.getFrozenTicks() > 0;
				int items = w.getEntitiesByClass(ItemEntity.class, new Box(ax0 - 22, ay0 - 16, az0 - 26, ax0 + 23, ay0 + 24, az0 + 14), e -> true).size();
				srvMsg = "frz20=" + frz20 + " items=" + items + " (" + srvMsg + ")";
				srvB = (frz20 && items == 0 && PieceEntity.DROPPED == dropped0) ? 1 : 0;
			});
		});
		waitFor("elemT3: verificado cryo", 20, "T3 cryo no devuelto", () -> srvA >= 0 && srvB >= 0, () -> {
			check((srvA & 1) != 0, "T3 hielo compacto congela el agua en área (FROSTED_ICE > 0)", srvMsg);
			check((srvA & 2) != 0 && srvB == 1, "T3 ráfaga cryo aplica SLOWNESS y ChillTracker mantiene frozenTicks > 0 durante 20 ticks", srvMsg);
		});

		// T4a & T4c: Estructura dura STONE_BRICKS (Piedra vs Obsidiana) + protección Cofre/Bedrock + Conservación 100%
		step(2, "elemT4ac: preparar suelos STONE_BRICKS", () -> {
			srvA = -1;
			server(srv, id, (sp, pw) -> {
				ServerWorld w = sp.getServerWorld();
				int ax0 = gx0 + DX, ay0 = gfy, az0 = gz0;
				patchElemArena(w, ax0, ay0, az0);
				clearElemArena(w, ax0, ay0, az0);
				BlockPos.Mutable m = new BlockPos.Mutable();
				// Floor 1 (Stone) en [ax0-18 .. ax0-8], [az0-13 .. az0-3], y = ay0-2 .. ay0
				for (int x = ax0 - 18; x <= ax0 - 8; x++)
					for (int z = az0 - 13; z <= az0 - 3; z++)
						for (int y = ay0 - 2; y <= ay0; y++)
							w.setBlockState(m.set(x, y, z), Blocks.STONE_BRICKS.getDefaultState(), 2 | 16);
				// Floor 2 (Obsidian) en [ax0+6 .. ax0+16], [az0-13 .. az0-3], y = ay0-2 .. ay0
				for (int x = ax0 + 6; x <= ax0 + 16; x++)
					for (int z = az0 - 13; z <= az0 - 3; z++)
						for (int y = ay0 - 2; y <= ay0; y++)
							w.setBlockState(m.set(x, y, z), Blocks.STONE_BRICKS.getDefaultState(), 2 | 16);
				// Bedrock en el centro bajo la capa de bricks y Cofre encima del borde
				w.setBlockState(new BlockPos(ax0 + 11, ay0 - 2, az0 - 8), Blocks.BEDROCK.getDefaultState(), 2 | 16);
				w.setBlockState(new BlockPos(ax0 + 13, ay0 + 1, az0 - 8), Blocks.CHEST.getDefaultState(), 2 | 16);

				// Sólidos iniciales en la arena completa ([ax0-26..ax0+26, ay0-14..ay0+22, az0-26..az0+12]) + 54 bloques (27 piedra + 27 obsidiana).
				srvB = count(w, ax0 - 26, ay0 - 14, az0 - 26, ax0 + 26, ay0 + 22, az0 + 12, false) + 54;

				PieceEntity pStone = PieceEntity.spawn(w, uniformCube(3, Blocks.STONE.getDefaultState()),
					new Vec3d(ax0 - 14.5, ay0 + 10.0, az0 - 9.5), null, sp.getUuid());
				pStone.release(new Vec3d(0, -1.9, 0), true);

				PieceEntity pObs = PieceEntity.spawn(w, uniformCube(3, Blocks.OBSIDIAN.getDefaultState()),
					new Vec3d(ax0 + 9.5, ay0 + 10.0, az0 - 9.5), null, sp.getUuid());
				pObs.release(new Vec3d(0, -1.9, 0), true);
				srvA = 1;
			});
		});
		step(6, "elemT4ac: esperar posado", () -> srvC = -1);
		repeatUntil("elemT4ac: posar todo", 5, 220, () -> server(srv, id, (sp, pw) -> {
			int ax0 = gx0 + DX, ay0 = gfy, az0 = gz0;
			srvC = sp.getServerWorld().getEntitiesByClass(PieceEntity.class,
				new Box(ax0 - 28, ay0 - 16, az0 - 28, ax0 + 29, ay0 + 24, az0 + 14), e -> true).size();
		}), () -> srvC == 0);
		step(2, "elemT4ac: medir", () -> {
			srvA = -1;
			srvMsg = "";
			server(srv, id, (sp, pw) -> {
				ServerWorld w = sp.getServerWorld();
				int ax0 = gx0 + DX, ay0 = gfy, az0 = gz0;
				BlockPos.Mutable m = new BlockPos.Mutable();
				int stoneLeft = 0, obsLeft = 0;
				for (int x = ax0 - 18; x <= ax0 - 8; x++)
					for (int z = az0 - 13; z <= az0 - 3; z++)
						for (int y = ay0 - 2; y <= ay0; y++)
							if (w.getBlockState(m.set(x, y, z)).isOf(Blocks.STONE_BRICKS)) stoneLeft++;
				for (int x = ax0 + 6; x <= ax0 + 16; x++)
					for (int z = az0 - 13; z <= az0 - 3; z++)
						for (int y = ay0 - 2; y <= ay0; y++)
							if (w.getBlockState(m.set(x, y, z)).isOf(Blocks.STONE_BRICKS)) obsLeft++;

				boolean bedrockOk = w.getBlockState(new BlockPos(ax0 + 11, ay0 - 2, az0 - 8)).isOf(Blocks.BEDROCK);
				boolean chestOk = w.getBlockState(new BlockPos(ax0 + 13, ay0 + 1, az0 - 8)).isOf(Blocks.CHEST);

				Box rightBox = new Box(ax0 - 26, ay0 - 14, az0 - 26, ax0 + 27, ay0 + 23, az0 + 13);
				int pieceBlocks = 0;
				for (PieceEntity pe : w.getEntitiesByClass(PieceEntity.class, rightBox, e -> true)) {
					pieceBlocks += pe.blocks != null ? pe.blocks.size() : 0;
				}
				int solidAfter = count(w, ax0 - 26, ay0 - 14, az0 - 26, ax0 + 26, ay0 + 22, az0 + 12, false);
				int items = w.getEntitiesByClass(ItemEntity.class, new Box(ax0 - 28, ay0 - 16, az0 - 28, ax0 + 29, ay0 + 24, az0 + 14), e -> true).size();

				int expectedStone = 11 * 11 * 3;
				int expectedObsInit = 11 * 11 * 3 - 1;
				int obsDug = expectedObsInit - obsLeft;
				srvMsg = "stoneLeft=" + stoneLeft + "/" + expectedStone + " obsDug=" + obsDug + " bedrock=" + bedrockOk
					+ " chest=" + chestOk + " solid=" + srvB + "->" + (solidAfter + pieceBlocks) + " items=" + items
					+ " soltados=" + (PieceEntity.DROPPED - dropped0);
				srvA = (stoneLeft == expectedStone ? 1 : 0) | (obsDug > 0 ? 2 : 0) | (bedrockOk && chestOk ? 4 : 0)
					| (solidAfter + pieceBlocks == srvB && items == 0 && PieceEntity.DROPPED == dropped0 ? 8 : 0);
			});
		});
		waitFor("elemT4ac: verificado", 20, "T4ac no devolvió resultado", () -> srvA >= 0, () -> {
			check((srvA & 1) != 0, "T4a piedra no excava estructura dura STONE_BRICKS (0 bloques)", srvMsg);
			check((srvA & 2) != 0, "T4a obsidiana fractura y excava estructura dura STONE_BRICKS (> 0 bloques)", srvMsg);
			check((srvA & 4) != 0, "T4a cofre (BlockEntity) y bedrock intactos en impacto heavy", srvMsg);
			check((srvA & 8) != 0, "T4c conservación del 100% de bloques y 0 ítems en fractura heavy", srvMsg);
		});

		// T4b: Cráter más profundo con DEEPSLATE vs STONE del mismo tamaño y velocidad sobre terreno estándar
		step(2, "elemT4b: lanza piedra", () -> {
			srvA = -1;
			srvB = -1;
			srvD1 = -1;
			srvD2 = -1;
			server(srv, id, (sp, pw) -> {
				ServerWorld w = sp.getServerWorld();
				int ax0 = gx0 + DX, ay0 = gfy, az0 = gz0;
				patchElemArena(w, ax0, ay0, az0);
				clearElemArena(w, ax0, ay0, az0);
				Crater.last = new Crater.Result(0, 0, 0, 0, 0, false, 0, 0, 0);
				PieceEntity pStone = PieceEntity.spawn(w, uniformCube(3, Blocks.STONE.getDefaultState()),
					new Vec3d(ax0 - 9.5, ay0 + 9.0, az0 - 9.5), null, sp.getUuid());
				pStone.release(new Vec3d(0, -1.65, 0), true);
				srvA = 1;
			});
		});
		waitFor("elemT4b: cráter piedra", 80, "T4b piedra no abrió cráter",
			() -> srvA == 1 && Crater.last != null && Crater.last.depth() > 0 && Crater.last.removed() > 0, () -> srvD1 = Crater.last.depth());
		step(4, "elemT4b: lanza deepslate", () -> {
			srvB = -1;
			server(srv, id, (sp, pw) -> {
				ServerWorld w = sp.getServerWorld();
				int ax0 = gx0 + DX, ay0 = gfy, az0 = gz0;
				patchElemArena(w, ax0, ay0, az0);
				clearElemArena(w, ax0, ay0, az0);
				Crater.last = new Crater.Result(0, 0, 0, 0, 0, false, 0, 0, 0);
				PieceEntity pDeep = PieceEntity.spawn(w, uniformCube(3, Blocks.DEEPSLATE.getDefaultState()),
					new Vec3d(ax0 + 6.5, ay0 + 9.0, az0 - 9.5), null, sp.getUuid());
				pDeep.release(new Vec3d(0, -1.65, 0), true);
				srvB = 1;
			});
		});
		waitFor("elemT4b: cráter deepslate", 80, "T4b deepslate no abrió cráter",
			() -> srvB == 1 && Crater.last != null && Crater.last.depth() > 0 && Crater.last.removed() > 0 && srvD1 > 0, () -> {
			srvD2 = Crater.last.depth();
			check(srvD2 > srvD1, "T4b profundidad cráter de DEEPSLATE > PIEDRA (mismo tamaño y velocidad)",
				"stoneDepth=" + fmt(srvD1) + " deepslateDepth=" + fmt(srvD2));
		});

		// T4d: LANCE de obsidiana atraviesa 3 zombies en fila
		step(2, "elemT4d: teleport", () -> {
			srvA = -1;
			server(srv, id, (sp, pw) -> {
				int ax0 = gx0 + DX, ay0 = gfy, az0 = gz0;
				sp.networkHandler.requestTeleport(ax0 + 0.5, ay0 + 1, az0 + 0.5, 180f, 0f);
				srvA = 1;
			});
		});
		waitFor("elemT4d: listo", 40, "jugador no giró para T4d",
			() -> srvA == 1 && Math.abs(mc.player.getPitch()) < 3f, null);
		step(2, "elemT4d: lanza de obsidiana", () -> {
			srvA = -1;
			elemZ1 = -1;
			elemZ2 = -1;
			elemZ3 = -1;
			server(srv, id, (sp, pw) -> {
				ServerWorld w = sp.getServerWorld();
				int ax0 = gx0 + DX, ay0 = gfy, az0 = gz0;
				patchElemArena(w, ax0, ay0, az0);
				clearElemArena(w, ax0, ay0, az0);
				for (int x = ax0 - 2; x <= ax0 + 2; x++)
					for (int y = ay0 + 1; y <= ay0 + 5; y++)
						w.setBlockState(new BlockPos(x, y, az0 - 15), Blocks.STONE.getDefaultState(), 2 | 16);

				sp.setYaw(180f);
				sp.setPitch(0f);
				ZombieEntity z1 = spawnDummyZombie(w, ax0 + 0.5, ay0 + 1, az0 - 5.5);
				ZombieEntity z2 = spawnDummyZombie(w, ax0 + 0.5, ay0 + 1, az0 - 8.5);
				ZombieEntity z3 = spawnDummyZombie(w, ax0 + 0.5, ay0 + 1, az0 - 11.5);
				elemZ1 = z1 != null ? z1.getId() : -1;
				elemZ2 = z2 != null ? z2.getId() : -1;
				elemZ3 = z3 != null ? z3.getId() : -1;

				BlockSet matSet = new BlockSet();
				for (int i = 0; i < 6; i++) matSet.add(i, 0, 0, Blocks.OBSIDIAN.getDefaultState(), null);
				matSet.normalize();
				PieceEntity mat = PieceEntity.spawn(w, matSet, new Vec3d(ax0 + 0.5, ay0 + 2.0, az0 + 2.0), null, sp.getUuid());
				Takt.grab(sp, pw, mat);
				Forge.forge(sp, pw, 0);
				Forge.use(sp, pw);
			});
		});
		step(15, "elemT4d: verificar brocheta", () -> {
			srvA = -1;
			server(srv, id, (sp, pw) -> {
				ServerWorld w = sp.getServerWorld();
				int ax0 = gx0 + DX, ay0 = gfy, az0 = gz0;
				Entity e1 = w.getEntityById(elemZ1), e2 = w.getEntityById(elemZ2), e3 = w.getEntityById(elemZ3);
				boolean h1 = e1 instanceof ZombieEntity z && z.getHealth() < z.getMaxHealth();
				boolean h2 = e2 instanceof ZombieEntity z && z.getHealth() < z.getMaxHealth();
				boolean h3 = e3 instanceof ZombieEntity z && z.getHealth() < z.getMaxHealth();
				srvMsg = "z1=" + h1 + " z2=" + h2 + " z3=" + h3;
				srvA = (h1 && h2 && h3) ? 1 : 0;

				// Limpiar arena y restaurar hora del día y ROOM de pruebas anteriores
				clearElemArena(w, ax0, ay0, az0);
				patchElemArena(w, ax0, ay0, az0);
				w.setTimeOfDay(6000);
				Forge.dropWeapons(sp, pw);
				Takt.dropAll(sp, pw, false);
				pw.room = new Room(w.getRegistryKey(), new Vec3d(gx0 + 0.5, gfy + 3, gz0 - 9.5), 14);
				pw.room.state = Room.State.FIXED;
				PowerManager.broadcastRoom(sp, 0, pw.room);
				sp.networkHandler.requestTeleport(gx0 + 0.5, gfy + 1, gz0 + 0.5, 180f, 15.1f);
			});
		});
		waitFor("elemT4d: verificado", 20, "T4d no devuelto", () -> srvA >= 0,
			() -> check(srvA == 1, "T4d LANCE de obsidiana atraviesa y daña a 3 zombies en fila", srvMsg));
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

		// prueba de árboles adyacentes (+Árbol / Solo árbol / Solo piso) y grosor en pared antes de limpiar la escena
		step(2, "rodaja: preparar árboles y muro", () -> {
			srvA = -1;
			server(srv, id, (sp, pw) -> {
				ServerWorld w = sp.getServerWorld();
				Takt.dropAll(sp, pw, false);
				patch(w, gx0 - 16, gx0 + 16, gz0 - 34, gz0 + 2, gfy);
				GroundCuts.clearOwner(sp.getUuid());
				BlockPos.Mutable m = new BlockPos.Mutable();
				// Árbol A en (gx0 - 2, gz0 - 6) y árbol B en (gx0 + 2, gz0 - 6) con copas que se tocan en el medio
				for (int y = 1; y <= 4; y++) w.setBlockState(m.set(gx0 - 2, gfy + y, gz0 - 6), Blocks.OAK_LOG.getDefaultState(), 2 | 16);
				for (int y = 1; y <= 4; y++) w.setBlockState(m.set(gx0 + 2, gfy + y, gz0 - 6), Blocks.OAK_LOG.getDefaultState(), 2 | 16);
				BlockState leaf = Blocks.OAK_LEAVES.getDefaultState().with(LeavesBlock.PERSISTENT, true);
				for (int dx = -2; dx <= 2; dx++) {
					for (int dz = -2; dz <= 2; dz++) {
						for (int dy = 3; dy <= 5; dy++) {
							BlockPos pA = new BlockPos(gx0 - 2 + dx, gfy + dy, gz0 - 6 + dz);
							if (w.getBlockState(pA).isAir()) w.setBlockState(pA, leaf, 2 | 16);
							BlockPos pB = new BlockPos(gx0 + 2 + dx, gfy + dy, gz0 - 6 + dz);
							if (w.getBlockState(pB).isAir()) w.setBlockState(pB, leaf, 2 | 16);
						}
					}
				}
				// Muro grueso de piedra al fondo
				for (int x = gx0 - 2; x <= gx0 + 2; x++)
					for (int z = gz0 - 14; z <= gz0 - 10; z++)
						for (int y = gfy + 1; y <= gfy + 3; y++) w.setBlockState(m.set(x, y, z), Blocks.STONE.getDefaultState(), 2 | 16);

				Vec3d rc = new Vec3d(gx0, gfy + 1, gz0 - 6);
				double rr = 14.0;
				BlockPos trunkA = new BlockPos(gx0 - 2, gfy + 2, gz0 - 6);
				BlockPos groundA = new BlockPos(gx0 - 2, gfy, gz0 - 6);
				BlockPos wallHit = new BlockPos(gx0, gfy + 2, gz0 - 10);

				ChunkPlan wall1 = ChunkPlanner.plan(w, wallHit, 6, 1, ChunkPlanner.RIDER_AUTO, Direction.SOUTH, List.of(), rc, rr, null);
				ChunkPlan wall4 = ChunkPlanner.plan(w, wallHit, 6, 4, ChunkPlanner.RIDER_AUTO, Direction.SOUTH, List.of(), rc, rr, null);
				boolean wallOk = wall1.face && wall4.face && !wall1.isEmpty() && wall1.blocks.length < wall4.blocks.length;

				ChunkPlan treeOnly = ChunkPlanner.plan(w, trunkA, 6, 0, ChunkPlanner.RIDER_TREE_ONLY, null, List.of(), rc, rr, null);
				LongOpenHashSet treeSet = new LongOpenHashSet(treeOnly.blocks);
				boolean hasA = true, noB = true;
				for (int y = 1; y <= 4; y++) {
					if (!treeSet.contains(BlockPos.asLong(gx0 - 2, gfy + y, gz0 - 6))) hasA = false;
					if (treeSet.contains(BlockPos.asLong(gx0 + 2, gfy + y, gz0 - 6))) noB = false;
				}
				ChunkPlan withTrees = ChunkPlanner.plan(w, groundA, 6, 1, ChunkPlanner.RIDER_WITH_TREES, Direction.UP, List.of(), rc, rr, null);
				LongOpenHashSet wtRiders = new LongOpenHashSet(withTrees.riders);
				boolean wtHasA = wtRiders.contains(BlockPos.asLong(gx0 - 2, gfy + 4, gz0 - 6));
				boolean wtNoB = !wtRiders.contains(BlockPos.asLong(gx0 + 2, gfy + 2, gz0 - 6));
				ChunkPlan groundOnly = ChunkPlanner.plan(w, groundA, 6, 1, ChunkPlanner.RIDER_NONE, Direction.UP, List.of(), rc, rr, null);

				OpeOpeMod.LOG.info("AUTOTEST rodaja/árboles: muro 1 vs 4 = {}/{}, soloÁrbol={} (tree={}, hasA={}, noB={}), +Árbol riders={} (hasA={}, noB={}), soloPiso riders={}",
					wall1.blocks.length, wall4.blocks.length, treeOnly.blocks.length, treeOnly.tree, hasA, noB,
					withTrees.riders.length, wtHasA, wtNoB, groundOnly.riders.length);
				srvA = (wallOk ? 1 : 0) | (treeOnly.tree && hasA && noB && treeOnly.blocks.length > 20 ? 2 : 0)
					| (wtHasA && wtNoB && !withTrees.isEmpty() ? 4 : 0) | (groundOnly.riders.length == 0 && !groundOnly.isEmpty() ? 8 : 0);
			});
		});
		waitFor("rodaja: medido", 20, "faltó resultado de rodaja/árboles", () -> srvA >= 0, () -> {
			check((srvA & 1) != 0, "grosor en pared/cara orientado por normal (1 vs 4 bloques)", "fallo pared");
			check((srvA & 2) != 0, "Solo árbol extrae el árbol completo sin robar el tronco vecino", "fallo solo árbol");
			check((srvA & 4) != 0, "+Árbol incluye suelo y el árbol entero sin el árbol vecino", "fallo +árbol");
			check((srvA & 8) != 0, "Solo piso deja las plantas y árboles en pie", "fallo solo piso");
		});

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

	// ------------------------------------------------------------------ fase B (k2g): desmembramiento estilo Law

	/** Resultados que calcula el hilo del servidor para las pruebas D (nombre, null = bien o el problema). */
	private static final java.util.concurrent.ConcurrentLinkedQueue<String[]> DRES = new java.util.concurrent.ConcurrentLinkedQueue<>();
	private static volatile int dZ2 = -1, dZ3 = -1, dZ4 = -1, dZ8 = -1, dDone = 0;
	private static volatile double dHp = 0, dX = 0, dZ = 0, dH = 0;
	private static volatile boolean dSeen = false;
	private static int dDropped0, dItems0;

	private static void dres(String name, String err) {
		DRES.add(new String[]{name, err});
	}

	/** En el cliente: lo que haya llegado del servidor, como ok o FALLO. */
	private static void drain() {
		String[] r;
		while ((r = DRES.poll()) != null) check(r[1] == null, r[0], r[1] == null ? "" : r[1]);
	}

	private static List<dev.opeope.entity.BodyPartEntity> partsOf(ServerWorld w, Entity victim) {
		return w.getEntitiesByClass(dev.opeope.entity.BodyPartEntity.class, victim.getBoundingBox().expand(64),
			p -> victim.getUuid().equals(p.victimUuid) && !p.isRemoved());
	}

	private static int d12Ticks;

	private static ZombieEntity spawnZombie(ServerWorld w, double x, double y, double z, float yaw, boolean ai) {
		ZombieEntity zmb = EntityType.ZOMBIE.create(w);
		if (zmb == null) return null;
		zmb.refreshPositionAndAngles(x, y, z, yaw, 0f);
		zmb.setBodyYaw(yaw);
		zmb.setHeadYaw(yaw);
		zmb.setAiDisabled(!ai);
		zmb.setPersistent();
		zmb.equipStack(net.minecraft.entity.EquipmentSlot.MAINHAND, ItemStack.EMPTY);
		zmb.addStatusEffect(new net.minecraft.entity.effect.StatusEffectInstance(StatusEffects.FIRE_RESISTANCE, -1, 0, false, false));
		w.spawnEntity(zmb);
		return zmb;
	}

	/**
	 * (k2g) Desmembramiento (D1–D6, D8 y el barrido): en una arena propia (x gx0+80 ± 12, z gz0+50 ± 12) con su ROOM.
	 * Cortar partes no crea objetos ni piezas de bloques; matar al cortado da su botín una sola vez.
	 */
	private static void planDesmembramiento(MinecraftClient mc, IntegratedServer srv, UUID id) {
		final int DX = 80, DZ = 50;
		step(1, "d: medir modelos", () -> OpeOpeMod.LOG.info("AUTOTEST anatomías medidas: {}", dev.opeope.client.dismember.AnatomyDump.logAll()));
		step(2, "d: preparar", () -> {
			srvA = -1;
			DRES.clear();
			server(srv, id, (sp, pw) -> {
				ServerWorld w = sp.getServerWorld();
				int ax0 = gx0 + DX, ay0 = gfy, az0 = gz0 + DZ;
				Takt.dropAll(sp, pw, false);
				Forge.dropWeapons(sp, pw);
				patch(w, ax0 - 12, ax0 + 12, az0 - 12, az0 + 12, ay0);
				Box arena = new Box(ax0 - 14, ay0 - 4, az0 - 14, ax0 + 15, ay0 + 20, az0 + 15);
				for (Entity e : w.getEntitiesByClass(Entity.class, arena, en -> en instanceof ItemEntity || en instanceof MobEntity
					|| en instanceof PieceEntity || en instanceof dev.opeope.entity.BodyPartEntity)) e.discard();
				w.setTimeOfDay(6000);   // de día, para las capturas (los zombis llevan resistencia al fuego)
				pw.room = new Room(w.getRegistryKey(), new Vec3d(ax0 + 0.5, ay0 + 2, az0 - 3.0), 14);
				pw.room.state = Room.State.FIXED;
				PowerManager.broadcastRoom(sp, 0, pw.room);
				sp.changeGameMode(GameMode.SURVIVAL);
				pw.zeroGravityRoom = false;   // con Gravedad Cero las partes flotarían (como todo en el ROOM)
				OpeConfig.MOBS_RECLAIM_PARTS = false;   // D2–D6 miran al ser sin sus partes; la IA que las recoge se prueba aparte (D12)
				pw.dirty = true;
				sp.networkHandler.requestTeleport(ax0 + 0.5, ay0 + 1, az0 + 0.5, 180f, 10f);
				sp.setHealth(sp.getMaxHealth());
				dDropped0 = PieceEntity.DROPPED;
				dItems0 = items(w, arena);
				srvA = 1;
			});
		});
		waitFor("d: arena lista", 60, "el jugador no llegó a la arena del desmembramiento",
			() -> srvA == 1 && mc.player.getZ() > gz0 + DZ - 2 && mc.player.getX() > gx0 + DX - 2, null);

		// D1: las anatomías de serie se leen y su forma es buena
		step(1, "D1 anatomía", () -> {
			for (String n : new String[]{"zombie", "cow", "player", "villager"}) {
				dev.opeope.anatomy.Anatomy a = dev.opeope.anatomy.AnatomyRegistry.get(net.minecraft.util.Identifier.ofVanilla(n));
				check(a != null && a.problems().isEmpty() && a.segments.size() >= 5, "D1 anatomía de " + n,
					a == null ? "no hay JSON" : a.problems() + ", " + a.segments.size() + " partes");
			}
		});

		// D1b: todas las anatomías de serie (las escritas a mano y las medidas de los modelos) están bien formadas
		step(1, "D1 todas", () -> {
			int n = 0;
			List<String> bad = new java.util.ArrayList<>();
			for (EntityType<?> t : net.minecraft.registry.Registries.ENTITY_TYPE) {
				dev.opeope.anatomy.Anatomy a = dev.opeope.anatomy.AnatomyRegistry.get(t);
				if (a == null) continue;
				n++;
				if (!a.problems().isEmpty()) bad.add(EntityType.getId(t).getPath() + ": " + a.problems());
			}
			check(n >= 60 && bad.isEmpty(), "D1 anatomías de serie bien formadas (" + n + ")", bad.toString());
		});

		// D2: cortar el brazo derecho de un zombi (pedido como lo pide el cliente) → una parte suya; a 1,5 bloques no pega
		step(1, "D2 brazo: cortar", () -> server(srv, id, (sp, pw) -> {
			ServerWorld w = sp.getServerWorld();
			ZombieEntity z = spawnZombie(w, gx0 + DX + 0.5, gfy + 1, gz0 + DZ - 1.0, 0f, true);
			if (z == null) {
				dres("D2 brazo", "no se pudo crear el zombi");
				return;
			}
			dZ2 = z.getId();
			dev.opeope.power.Dismember.handleCutRequest(sp, z.getId(), "right_arm");
			List<dev.opeope.entity.BodyPartEntity> ps = partsOf(w, z);
			boolean ok = ps.size() == 1 && ps.get(0).segments().contains("right_arm") && z.getUuid().equals(ps.get(0).victimUuid);
			dres("D2 brazo: una parte viva del zombi", ok ? null : ps.size() + " partes " + (ps.isEmpty() ? "" : ps.get(0).segments()));
			dres("D2 brazo: el zombi sigue vivo y entero de vida", z.isAlive() && z.getHealth() >= z.getMaxHealth() - 0.01f ? null : "vida " + z.getHealth());
			sp.setHealth(sp.getMaxHealth());
			dHp = sp.getHealth();
		}));
		step(8, "D2: captura", () -> {
			drain();
			shot(mc, "d2-zombi-sin-brazo");
		});
		step(92, "D2 brazo: no pega", () -> server(srv, id, (sp, pw) -> {
			Entity z = sp.getServerWorld().getEntityById(dZ2);
			double d = z == null ? -1 : Math.sqrt(z.squaredDistanceTo(sp));
			dres("D2 brazo: sin el brazo del arma no hace daño en 100 ticks", sp.getHealth() >= dHp - 0.01 ? null
				: "vida " + fmt(sp.getHealth()) + " de " + fmt(dHp) + ", zombi a " + fmt(d));
		}));

		// D3: sin las dos piernas: velocidad ≈ 0, no se mueve, caja más baja
		step(2, "D3 piernas: cortar", () -> {
			drain();
			server(srv, id, (sp, pw) -> {
				ServerWorld w = sp.getServerWorld();
				ZombieEntity z = spawnZombie(w, gx0 + DX + 4.5, gfy + 1, gz0 + DZ - 6.0, 0f, true);
				if (z == null) return;
				dZ3 = z.getId();
				dH = z.getHeight();
				dev.opeope.anatomy.Anatomy a = dev.opeope.power.Dismember.anatomy(z);
				dev.opeope.power.Dismember.cut(z, dev.opeope.power.Dismember.resolve(a, dev.opeope.power.Dismember.state(z), "lower_half"), sp);
				List<dev.opeope.entity.BodyPartEntity> ps = partsOf(w, z);
				dres("D3 piernas: las dos en una sola parte (la mitad de abajo)", ps.size() == 1 && ps.get(0).segments().size() == 2 ? null : ps.size() + " partes");
				double sp0 = z.getAttributeValue(EntityAttributes.GENERIC_MOVEMENT_SPEED);
				dres("D3 piernas: velocidad ≈ 0", sp0 < 0.02 ? null : "velocidad " + fmt(sp0));
				dres("D3 piernas: la caja es más baja", z.getHeight() < dH - 0.4 ? null : fmt(dH) + " → " + fmt(z.getHeight()));
				dX = z.getX();
				dZ = z.getZ();
			});
		});
		step(100, "D3 piernas: no anda", () -> server(srv, id, (sp, pw) -> {
			Entity z = sp.getServerWorld().getEntityById(dZ3);
			double moved = z == null ? 99 : Math.hypot(z.getX() - dX, z.getZ() - dZ);
			dres("D3 piernas: se mueve < 0,5 bloques en 100 ticks", moved < 0.5 ? null : "se movió " + fmt(moved));
		}));

		// D4: sin cabeza no fija objetivo en 100 ticks con el jugador delante
		step(2, "D4 cabeza: cortar", () -> {
			drain();
			dSeen = false;
			server(srv, id, (sp, pw) -> {
				ServerWorld w = sp.getServerWorld();
				ZombieEntity z = spawnZombie(w, gx0 + DX - 3.5, gfy + 1, gz0 + DZ - 4.0, 0f, true);
				if (z == null) return;
				dZ4 = z.getId();
				dev.opeope.power.Dismember.cut(z, List.of("head"), sp);
				dres("D4 cabeza: separada", partsOf(w, z).size() == 1 ? null : partsOf(w, z).size() + " partes");
			});
		});
		for (int i = 0; i < 10; i++) {
			step(10, "D4 cabeza: mira", () -> server(srv, id, (sp, pw) -> {
				if (sp.getServerWorld().getEntityById(dZ4) instanceof MobEntity m && m.getTarget() != null) dSeen = true;
			}));
		}
		step(2, "D4 cabeza: resultado", () -> {
			drain();
			check(!dSeen, "D4 cabeza: sin cabeza no fija objetivo en 100 ticks", "fijó al jugador");
			shot(mc, "d4-zombi-sin-cabeza");
		});

		// D5: daño compartido: 4 a la parte = 4 al dueño
		step(1, "D5 daño compartido", () -> server(srv, id, (sp, pw) -> {
			ServerWorld w = sp.getServerWorld();
			ZombieEntity z = spawnZombie(w, gx0 + DX + 6.5, gfy + 1, gz0 + DZ - 2.0, 0f, false);
			if (z == null) return;
			dev.opeope.entity.BodyPartEntity part = dev.opeope.power.Dismember.cut(z, List.of("left_arm"), sp);
			float h0 = z.getHealth();
			boolean hit = part != null && part.damage(w.getDamageSources().magic(), 4f);
			float lost = h0 - z.getHealth();
			dres("D5 daño compartido: 4 a la parte = 4 al zombi", hit && Math.abs(lost - 4f) < 0.05f ? null : "golpe " + hit + ", perdió " + fmt(lost));
			if (part != null) part.discard();
			z.discard();
		}));

		// D6: Shambles sobre la parte y su dueño: vuelve, y con ella la velocidad, la altura y el objetivo
		step(2, "D6 reunir", () -> {
			drain();
			server(srv, id, (sp, pw) -> {
				ServerWorld w = sp.getServerWorld();
				if (!(w.getEntityById(dZ3) instanceof ZombieEntity z3) || !(w.getEntityById(dZ4) instanceof ZombieEntity z4)) {
					dres("D6 reunir", "faltan los zombis de D3/D4");
					return;
				}
				for (dev.opeope.entity.BodyPartEntity p : partsOf(w, z3)) dev.opeope.power.Dismember.shamblesPair(sp, p, z3);
				for (dev.opeope.entity.BodyPartEntity p : partsOf(w, z4)) dev.opeope.power.Dismember.shamblesPair(sp, z4, p);
				double v = z3.getAttributeValue(EntityAttributes.GENERIC_MOVEMENT_SPEED);
				dres("D6 reunir: las partes ya no están", partsOf(w, z3).isEmpty() && partsOf(w, z4).isEmpty() ? null : "siguen ahí");
				dres("D6 reunir: velocidad y altura normales", v > 0.2 && Math.abs(z3.getHeight() - dH) < 0.01 ? null
					: "velocidad " + fmt(v) + ", altura " + fmt(z3.getHeight()));
				z3.discard();   // que no vaya a por el jugador mientras se espera a z4
			});
		});
		waitFor("D6 reunir: vuelve a fijar objetivo", 160, "no volvió a ver al jugador",
			() -> {
				drain();
				return srv.getPlayerManager().getPlayer(id) != null
					&& srv.getPlayerManager().getPlayer(id).getServerWorld().getEntityById(dZ4) instanceof MobEntity m && m.getTarget() != null;
			},
			() -> ok("D6 reunir: con la cabeza vuelve a fijar objetivo"));

		// barrido: el tajo de Amputate por las rodillas separa la mitad de abajo
		step(2, "D barrido", () -> server(srv, id, (sp, pw) -> {
			ServerWorld w = sp.getServerWorld();
			ZombieEntity z = spawnZombie(w, gx0 + DX - 6.5, gfy + 1, gz0 + DZ - 2.0, 0f, false);
			if (z == null) return;
			boolean did = dev.opeope.power.Dismember.sweep(sp, z,
				List.of(new CutEngine.Plane(new Vec3d(0, 1, 0), z.getPos().add(0, 0.55, 0))), false);
			List<dev.opeope.entity.BodyPartEntity> ps = partsOf(w, z);
			dres("D barrido: un tajo por las rodillas separa la mitad de abajo", did && ps.size() == 1 && ps.get(0).segments().contains("left_leg")
				&& ps.get(0).segments().contains("right_leg") ? null : "cortó " + did + ", " + ps.size() + " partes");
			z.discard();
		}));

		// capturas para mirar a ojo: un zombi, una oveja, un aldeano y una vaca cortados, delante del jugador
		step(2, "D fotos: preparar", () -> {
			drain();
			server(srv, id, (sp, pw) -> {
				ServerWorld w = sp.getServerWorld();
				for (int zid : new int[]{dZ2, dZ4}) {
					Entity e = w.getEntityById(zid);
					if (e instanceof LivingEntity le) {
						for (dev.opeope.entity.BodyPartEntity p : partsOf(w, le)) p.discard();
						e.discard();
					}
				}
				double bx = gx0 + DX + 0.5, by = gfy + 1, bz = gz0 + DZ - 5.5;
				net.minecraft.entity.mob.HuskEntity z = EntityType.HUSK.create(w);   // un husk no arde de día
				if (z != null) {
					z.refreshPositionAndAngles(bx - 1.2, by, bz, 20f, 0f);
					z.setBodyYaw(20f);
					z.setHeadYaw(20f);
					z.setAiDisabled(true);
					z.setPersistent();
					w.spawnEntity(z);
					z.equipStack(net.minecraft.entity.EquipmentSlot.MAINHAND, new ItemStack(Items.IRON_SWORD));
					dev.opeope.power.Dismember.cut(z, List.of("right_arm"), sp);
					dev.opeope.power.Dismember.cut(z, List.of("head"), sp);
				}
				for (EntityType<? extends MobEntity> t : List.<EntityType<? extends MobEntity>>of(EntityType.SHEEP, EntityType.VILLAGER, EntityType.COW)) {
					MobEntity m = t.create(w);
					if (m == null) continue;
					double ox = t == EntityType.SHEEP ? 1.6 : t == EntityType.VILLAGER ? -3.6 : 3.8;
					m.refreshPositionAndAngles(bx + ox, by, bz - (t == EntityType.COW ? 1.5 : 0), t == EntityType.COW ? 90f : -20f, 0f);
					m.setBodyYaw(m.getYaw());
					m.setAiDisabled(true);
					m.setPersistent();
					w.spawnEntity(m);
					if (t == EntityType.SHEEP) dev.opeope.power.Dismember.cut(m, List.of("head"), sp);
					if (t == EntityType.VILLAGER) dev.opeope.power.Dismember.cut(m, List.of("arms"), sp);
					if (t == EntityType.COW) dev.opeope.power.Dismember.cut(m, List.of("right_front_leg"), sp);
				}
				sp.networkHandler.requestTeleport(gx0 + DX + 0.5, gfy + 1, gz0 + DZ + 0.5, 180f, 18f);
			});
		});
		step(40, "D fotos", () -> {
			shot(mc, "d-partes-vivas");
			server(srv, id, (sp, pw) -> {
				for (dev.opeope.entity.BodyPartEntity p : sp.getServerWorld().getEntitiesByClass(dev.opeope.entity.BodyPartEntity.class,
					sp.getBoundingBox().expand(16), x -> true)) {
					Entity v = p.victim();
					OpeOpeMod.LOG.info("AUTOTEST parte {} de {} en {} (dueño en {}), suelo={}, caja={}x{}", p.segments(), p.victimType(),
						p.getPos(), v == null ? "-" : v.getPos(), p.isOnGround(), fmt(p.getWidth()), fmt(p.getHeight()));
				}
			});
		});
		step(2, "D fotos: cerca", () -> server(srv, id, (sp, pw) ->
			sp.networkHandler.requestTeleport(gx0 + DX - 0.2, gfy + 1, gz0 + DZ - 2.6, 180f, 35f)));
		step(10, "D fotos: cerca captura", () -> shot(mc, "d-partes-vivas-cerca"));
		step(1, "D fotos: limpiar", () -> server(srv, id, (sp, pw) -> {
			ServerWorld w = sp.getServerWorld();
			Box arena = new Box(gx0 + DX - 14, gfy - 4, gz0 + DZ - 14, gx0 + DX + 15, gfy + 20, gz0 + DZ + 15);
			for (Entity e : w.getEntitiesByClass(Entity.class, arena, en -> en instanceof MobEntity || en instanceof dev.opeope.entity.BodyPartEntity
				|| en instanceof ItemEntity)) e.discard();
		}));

		// D8: matar al cortado: sus partes se van y el botín sale una vez
		step(2, "D8 muerte", () -> {
			drain();
			server(srv, id, (sp, pw) -> {
				ServerWorld w = sp.getServerWorld();
				for (int zid : new int[]{dZ2, dZ3, dZ4}) {
					Entity e = w.getEntityById(zid);
					if (e instanceof LivingEntity le) {
						for (dev.opeope.entity.BodyPartEntity p : partsOf(w, le)) p.discard();
						e.discard();
					}
				}
				Box arena = new Box(gx0 + DX - 14, gfy - 4, gz0 + DZ - 14, gx0 + DX + 15, gfy + 20, gz0 + DZ + 15);
				int before = items(w, arena);
				ZombieEntity z = spawnZombie(w, gx0 + DX + 0.5, gfy + 1, gz0 + DZ - 8.0, 0f, false);
				if (z == null) return;
				dev.opeope.power.Dismember.cut(z, List.of("right_arm"), sp);
				dev.opeope.power.Dismember.cut(z, List.of("head"), sp);
				int parts = partsOf(w, z).size();
				z.damage(w.getDamageSources().playerAttack(sp), 1000f);
				int left = w.getEntitiesByClass(dev.opeope.entity.BodyPartEntity.class, arena, p -> z.getUuid().equals(p.victimUuid) && !p.isRemoved()).size();
				int loot = items(w, arena) - before;
				dres("D8 muerte: las 2 partes se van con él", parts == 2 && left == 0 && !z.isAlive() ? null : parts + " partes, quedan " + left);
				dres("D8 muerte: botín una sola vez (carne podrida 0–2 y como mucho una rareza)", loot >= 0 && loot <= 3 ? null : loot + " objetos");
				dres("D sin piezas de bloques", PieceEntity.DROPPED == dDropped0 ? null : (PieceEntity.DROPPED - dDropped0) + " piezas soltadas");
				for (Entity e : w.getEntitiesByClass(ItemEntity.class, arena, en -> true)) e.discard();
				for (Entity e : w.getEntitiesByClass(dev.opeope.entity.BodyPartEntity.class, arena, en -> true)) e.discard();
				sp.setHealth(sp.getMaxHealth());
				dDone = 1;
			});
		});
		waitFor("d: fin", 40, "el servidor no terminó D8", () -> dDone == 1, AutoTest::drain);
		planDesmembramiento2(mc, srv, id);
	}

	private static volatile int dA = -1, dB = -1, dP = -1;
	private static volatile double dPx, dPz;

	/** (fases 2–4) D7 Radio Knife, D9 guardar y cargar, D10 Mes, D11 piernas que andan, D12 la IA recoge, D13 quimera, D14 parte lanzada, cámara en la cabeza. */
	private static void planDesmembramiento2(MinecraftClient mc, IntegratedServer srv, UUID id) {
		final int DX = 80, DZ = 50;
		// el lector genérico de modelos: el pollo y el lobo (sin campos con nombre) se leen por su árbol
		step(1, "D modelos", () -> {
			for (EntityType<?> t : List.<EntityType<?>>of(EntityType.CHICKEN, EntityType.WOLF, EntityType.HORSE, EntityType.FOX)) {
				Entity e = t.create(mc.world);
				boolean ok = e instanceof LivingEntity le && mc.getEntityRenderDispatcher().getRenderer(le) instanceof net.minecraft.client.render.entity.LivingEntityRenderer<?, ?> lr
					&& dev.opeope.client.dismember.ModelParts.of(lr.getModel()).keySet().stream().anyMatch(k -> k.contains("head"));
				check(ok, "D modelos: se lee el modelo de " + EntityType.getId(t).getPath(), "no se encontró su cabeza");
			}
		});
		// D7: Radio Knife: lo cortado no se puede reunir hasta que pasa el bloqueo
		step(2, "D7 radio", () -> server(srv, id, (sp, pw) -> {
			ServerWorld w = sp.getServerWorld();
			ZombieEntity z = spawnZombie(w, gx0 + DX + 3.5, gfy + 1, gz0 + DZ - 3.0, 0f, false);
			if (z == null) return;
			dA = z.getId();
			dev.opeope.power.Dismember.sweep(sp, z, List.of(new CutEngine.Plane(new Vec3d(0, 1, 0), z.getPos().add(0, 0.55, 0))), true);
			List<dev.opeope.entity.BodyPartEntity> ps = partsOf(w, z);
			if (ps.size() != 1) {
				dres("D7 radio: corta", ps.size() + " partes");
				return;
			}
			dev.opeope.power.Dismember.shamblesPair(sp, ps.get(0), z);
			dres("D7 radio: bloqueada no se reúne", partsOf(w, z).size() == 1 && ps.get(0).locked() ? null : "se reunió o no quedó bloqueada");
			dev.opeope.anatomy.DismemberState st = dev.opeope.power.Dismember.state(z);
			long soon = w.getTime() + 10;
			st.lockedUntil.replaceAll((k, v) -> soon);
		}));
		step(20, "D7 radio: pasa el bloqueo", () -> server(srv, id, (sp, pw) -> {
			ServerWorld w = sp.getServerWorld();
			if (!(w.getEntityById(dA) instanceof ZombieEntity z)) return;
			for (dev.opeope.entity.BodyPartEntity p : partsOf(w, z)) dev.opeope.power.Dismember.shamblesPair(sp, p, z);
			dres("D7 radio: pasado el bloqueo, sí se reúne", partsOf(w, z).isEmpty() ? null : "sigue suelta");
			z.discard();
		}));

		// D9: guardar y cargar: el ser recuerda qué le falta y la parte a quién pertenece
		step(2, "D9 persistencia", () -> {
			drain();
			server(srv, id, (sp, pw) -> {
				ServerWorld w = sp.getServerWorld();
				ZombieEntity z = spawnZombie(w, gx0 + DX - 3.5, gfy + 1, gz0 + DZ - 3.0, 0f, false);
				if (z == null) return;
				dev.opeope.entity.BodyPartEntity part = dev.opeope.power.Dismember.cut(z, List.of("right_arm"), sp);
				net.minecraft.nbt.NbtCompound zn = new net.minecraft.nbt.NbtCompound();
				z.writeNbt(zn);
				ZombieEntity z2 = EntityType.ZOMBIE.create(w);
				z2.readNbt(zn);
				dev.opeope.anatomy.DismemberState st2 = z2.getAttached(dev.opeope.registry.ModAttachments.DISMEMBER);
				dres("D9 persistencia: el ser guarda qué le falta", st2 != null && part != null && part.getUuid().equals(st2.detached.get("right_arm")) ? null
					: "estado leído " + (st2 == null ? "nulo" : st2.detached));
				net.minecraft.nbt.NbtCompound pn = new net.minecraft.nbt.NbtCompound();
				if (part != null) part.writeNbt(pn);
				dev.opeope.entity.BodyPartEntity p2 = new dev.opeope.entity.BodyPartEntity(dev.opeope.registry.ModEntities.BODY_PART, w);
				p2.readNbt(pn);
				dres("D9 persistencia: la parte guarda su dueño y sus partes", z.getUuid().equals(p2.victimUuid) && p2.segments().equals(List.of("right_arm")) ? null
					: p2.victimUuid + " " + p2.segments());
				if (part != null) part.discard();
				z.discard();
			});
		});

		// D10: Mes: apretar el corazón duele y aturde; si su dueño muere, el cubo deja de valer
		step(2, "D10 mes", () -> {
			drain();
			server(srv, id, (sp, pw) -> {
				ServerWorld w = sp.getServerWorld();
				ZombieEntity z = spawnZombie(w, gx0 + DX - 5.5, gfy + 1, gz0 + DZ - 3.0, 0f, false);
				if (z == null) return;
				ItemStack heart = new ItemStack(dev.opeope.registry.ModItems.HEART);
				dev.opeope.item.HeartItem.setOwner(heart, z.getUuid());
				float h0 = z.getHealth();
				Techniques.squeezeHeart(sp, heart);
				dres("D10 mes: apretar le quita vida y lo aturde", z.getHealth() < h0 - 0.5f && z.hasStatusEffect(ModEffects.STUN) ? null
					: "vida " + fmt(h0) + " → " + fmt(z.getHealth()) + ", aturdido " + z.hasStatusEffect(ModEffects.STUN));
				z.setHealth(1.5f);
				Techniques.squeezeHeart(sp, heart);
				dres("D10 mes: apretar no mata (MES_CAN_KILL = false)", z.isAlive() ? null : "murió");
				z.damage(w.getDamageSources().magic(), 100f);
				dres("D10 mes: si su dueño muere, el corazón se apaga", dev.opeope.power.Dismember.DEAD_HEARTS.contains(z.getUuid()) ? null : "no se apuntó");
				for (Entity e : w.getEntitiesByClass(ItemEntity.class, z.getBoundingBox().expand(4), en -> true)) e.discard();
			});
		});

		// D11: la mitad de abajo anda sola (y huye de quien la cortó)
		step(2, "D11 piernas solas", () -> {
			drain();
			server(srv, id, (sp, pw) -> {
				ServerWorld w = sp.getServerWorld();
				ZombieEntity z = spawnZombie(w, gx0 + DX + 0.5, gfy + 1, gz0 + DZ - 6.0, 0f, false);
				if (z == null) return;
				dB = z.getId();
				dev.opeope.entity.BodyPartEntity part = dev.opeope.power.Dismember.cut(z, dev.opeope.power.Dismember.resolve(
					dev.opeope.power.Dismember.anatomy(z), dev.opeope.power.Dismember.state(z), "lower_half"), sp);
				dP = part == null ? -1 : part.getId();
				dres("D11 piernas solas: van solas", part != null && part.autonomous() ? null : "no son autónomas");
				if (part != null) {
					dPx = part.getX();
					dPz = part.getZ();
				}
			});
		});
		step(30, "D11 piernas: captura", () -> {
			drain();
			shot(mc, "d11-piernas-andando");
		});
		step(170, "D11 piernas: cuánto anduvieron", () -> server(srv, id, (sp, pw) -> {
			ServerWorld w = sp.getServerWorld();
			Entity p = w.getEntityById(dP);
			double moved = p == null ? -1 : Math.hypot(p.getX() - dPx, p.getZ() - dPz);
			dres("D11 piernas solas: se mueven > 2 bloques en 200 ticks", moved > 2 ? null : "se movieron " + fmt(moved));
			if (w.getEntityById(dB) instanceof LivingEntity z) {
				for (dev.opeope.entity.BodyPartEntity bp : partsOf(w, z)) bp.discard();
				z.discard();
			}
		}));

		// D12: la IA: un zombi sin brazo va a por él y se lo vuelve a pegar
		step(2, "D12 recoger", () -> {
			drain();
			d12Ticks = 0;
			server(srv, id, (sp, pw) -> {
				OpeConfig.MOBS_RECLAIM_PARTS = true;
				// con Gravedad Cero el brazo flotaría arriba del ROOM, fuera de su alcance
				pw.zeroGravityRoom = false;
				pw.dirty = true;
				ServerWorld w = sp.getServerWorld();
				ZombieEntity z = spawnZombie(w, gx0 + DX + 5.5, gfy + 1, gz0 + DZ - 7.0, 0f, true);
				if (z == null) return;
				dA = z.getId();
				dev.opeope.entity.BodyPartEntity part = dev.opeope.power.Dismember.cut(z, List.of("right_arm"), sp);
				if (part != null) part.kick(new Vec3d(0.35, 0.2, 0));   // que tenga que ir a por él
			});
		});
		waitFor("D12 recoger: se lo vuelve a pegar", 400, "no recogió su brazo en 20 s", () -> {
			ServerPlayerEntity sp = srv.getPlayerManager().getPlayer(id);
			if (sp != null && sp.getServerWorld().getEntityById(dA) instanceof ZombieEntity z && (++d12Ticks % 60 == 0 || (d12Ticks <= 40 && d12Ticks % 5 == 0))) {
				// diagnóstico: dónde están el zombi y su brazo y qué hace
				List<dev.opeope.entity.BodyPartEntity> ps = partsOf(sp.getServerWorld(), z);
				OpeOpeMod.LOG.info("AUTOTEST D12 diag t={} zombi={} objetivo={} navInactiva={} partes={}", d12Ticks, z.getPos(), z.getTarget(),
					z.getNavigation().isIdle(), ps.stream().map(q -> q.getPos() + " v=" + q.getVelocity() + " suelo=" + q.isOnGround() + " d=" + fmt((float) q.distanceTo(z)) + " bloq=" + dev.opeope.power.Dismember.isLocked(z, q)
					+ " zeroG=" + dev.opeope.power.PowerManager.get(sp).zeroGravityRoom + " sinGrav=" + q.hasNoGravity() + " edad=" + q.age + " montada=" + q.mounted()).toList());
			}
			return sp != null && sp.getServerWorld().getEntityById(dA) instanceof ZombieEntity z
				&& dev.opeope.power.Dismember.state(z) == null && partsOf(sp.getServerWorld(), z).isEmpty();
		}, () -> {
			ok("D12 recoger: el zombi va a por su brazo y se lo pega");
			server(srv, id, (sp, pw) -> {
				if (sp.getServerWorld().getEntityById(dA) instanceof ZombieEntity z) z.discard();
				OpeConfig.MOBS_RECLAIM_PARTS = false;
			});
		});

		// D13: quimera: la cabeza de un zombi en un husk (el husk ve; el daño a esa cabeza va al zombi) y vuelta a su sitio
		step(2, "D13 quimera", () -> server(srv, id, (sp, pw) -> {
			OpeConfig.MOBS_RECLAIM_PARTS = false;
			ServerWorld w = sp.getServerWorld();
			ZombieEntity a = spawnZombie(w, gx0 + DX - 1.2, gfy + 1, gz0 + DZ - 4.5, 20f, false);
			net.minecraft.entity.mob.HuskEntity b = EntityType.HUSK.create(w);
			if (a == null || b == null) return;
			b.refreshPositionAndAngles(gx0 + DX + 1.8, gfy + 1, gz0 + DZ - 4.5, -20f, 0f);
			b.setBodyYaw(-20f);
			b.setHeadYaw(-20f);
			b.setAiDisabled(true);
			b.setPersistent();
			w.spawnEntity(b);
			dA = a.getId();
			dB = b.getId();
			dev.opeope.entity.BodyPartEntity head = dev.opeope.power.Dismember.cut(a, List.of("head"), sp);
			if (head == null) {
				dres("D13 quimera", "no se cortó la cabeza");
				return;
			}
			dP = head.getId();
			dev.opeope.power.Dismember.shamblesPair(sp, head, b);
			dev.opeope.anatomy.DismemberState sb = dev.opeope.power.Dismember.state(b);
			dres("D13 quimera: la cabeza del zombi está puesta en el husk", head.mounted() && sb != null && head.getUuid().equals(sb.foreign.get("head")) ? null
				: "montada " + head.mounted() + ", estado " + (sb == null ? "-" : sb.foreign));
			dres("D13 quimera: la cabeza del husk sale suelta", partsOf(w, b).size() == 1 ? null : partsOf(w, b).size() + " partes del husk");
			dres("D13 quimera: con esa cabeza el husk ve", !dev.opeope.power.Dismember.caps(b).blind() ? null : "sigue ciego");
			float h0 = a.getHealth();
			head.damage(w.getDamageSources().magic(), 3f);
			dres("D13 quimera: el daño a esa cabeza le duele a su dueño (el zombi)", a.getHealth() < h0 - 2.5f ? null : "vida " + fmt(h0) + " → " + fmt(a.getHealth()));
			sp.networkHandler.requestTeleport(gx0 + DX + 0.3, gfy + 1, gz0 + DZ - 0.5, 180f, 15f);
		}));
		step(30, "D13 quimera: captura", () -> {
			drain();
			shot(mc, "d13-quimera");
		});
		step(2, "D13 quimera: devolver", () -> server(srv, id, (sp, pw) -> {
			ServerWorld w = sp.getServerWorld();
			if (!(w.getEntityById(dA) instanceof ZombieEntity a) || !(w.getEntityById(dB) instanceof LivingEntity b)
				|| !(w.getEntityById(dP) instanceof dev.opeope.entity.BodyPartEntity head)) {
				dres("D13 quimera: devolver", "faltan entidades");
				return;
			}
			dev.opeope.power.Dismember.shamblesPair(sp, head, a);
			dev.opeope.anatomy.DismemberState sb = dev.opeope.power.Dismember.state(b);
			dres("D13 quimera: vuelve a su dueño y el husk queda sin cabeza", head.isRemoved() && dev.opeope.power.Dismember.state(a) == null
				&& (sb == null || sb.foreign.isEmpty()) && dev.opeope.power.Dismember.caps(b).blind() ? null
				: "quitada " + head.isRemoved() + ", husk " + (sb == null ? "-" : sb.foreign) + " ciego " + dev.opeope.power.Dismember.caps(b).blind());
			for (dev.opeope.entity.BodyPartEntity bp : partsOf(w, b)) dev.opeope.power.Dismember.shamblesPair(sp, bp, b);
			dres("D13 quimera: el husk recupera su cabeza", partsOf(w, b).isEmpty() && dev.opeope.power.Dismember.state(b) == null ? null : "no");
			a.discard();
			b.discard();
		}));

		// D14: una parte lanzada golpea a otro, y a su dueño le duele la mitad
		step(2, "D14 parte lanzada", () -> {
			drain();
			server(srv, id, (sp, pw) -> {
				ServerWorld w = sp.getServerWorld();
				ZombieEntity owner = spawnZombie(w, gx0 + DX - 8.5, gfy + 1, gz0 + DZ - 9.0, 0f, false);
				net.minecraft.entity.passive.PigEntity pig = EntityType.PIG.create(w);
				if (owner == null || pig == null) return;
				pig.refreshPositionAndAngles(gx0 + DX + 6.5, gfy + 1, gz0 + DZ - 9.0, 0f, 0f);
				pig.setAiDisabled(true);
				w.spawnEntity(pig);
				dev.opeope.entity.BodyPartEntity part = dev.opeope.power.Dismember.cut(owner, List.of("left_arm"), sp);
				if (part == null) return;
				part.setPosition(pig.getX() - 1.5, pig.getY() + 0.3, pig.getZ());
				part.kick(new Vec3d(1.0, 0.05, 0));
				dA = owner.getId();
				dB = pig.getId();
			});
		});
		step(12, "D14 parte lanzada: golpe", () -> server(srv, id, (sp, pw) -> {
			ServerWorld w = sp.getServerWorld();
			if (!(w.getEntityById(dA) instanceof LivingEntity owner) || !(w.getEntityById(dB) instanceof LivingEntity pig)) {
				dres("D14 parte lanzada", "faltan entidades");
				return;
			}
			dres("D14 parte lanzada: golpea al cerdo", pig.getHealth() < pig.getMaxHealth() ? null : "el cerdo sigue entero");
			dres("D14 parte lanzada: al dueño le duele", owner.getHealth() < owner.getMaxHealth() ? null : "el dueño sigue entero");
			for (dev.opeope.entity.BodyPartEntity bp : partsOf(w, owner)) bp.discard();
			owner.discard();
			pig.discard();
		}));

		// D15: corte libre por el torso (no por una articulación): un zombi partido por la cintura y una vaca en diagonal
		step(2, "D15 corte libre", () -> {
			drain();
			server(srv, id, (sp, pw) -> {
				OpeConfig.FREE_PLANE_CUTS = true;
				ServerWorld w = sp.getServerWorld();
				ZombieEntity z = spawnZombie(w, gx0 + DX - 1.0, gfy + 1, gz0 + DZ - 4.0, 20f, false);
				net.minecraft.entity.passive.CowEntity cow = EntityType.COW.create(w);
				if (z == null || cow == null) return;
				cow.refreshPositionAndAngles(gx0 + DX + 2.5, gfy + 1, gz0 + DZ - 4.5, 70f, 0f);
				cow.setBodyYaw(70f);
				cow.setAiDisabled(true);
				cow.setPersistent();
				w.spawnEntity(cow);
				dA = z.getId();
				dB = cow.getId();
				boolean did = dev.opeope.power.Dismember.sweep(sp, z,
					List.of(new CutEngine.Plane(new Vec3d(0.15, 1, 0).normalize(), z.getPos().add(0, 1.08, 0))), false);
				List<dev.opeope.entity.BodyPartEntity> ps = partsOf(w, z);
				dev.opeope.anatomy.DismemberState st = dev.opeope.power.Dismember.state(z);
				dres("D15 corte libre: un tajo por la cintura parte el torso en dos", did && ps.size() == 1 && ps.get(0).isSlice() && st != null && st.plane != null ? null
					: "cortó " + did + ", " + ps.size() + " piezas" + (ps.isEmpty() ? "" : " slice=" + ps.get(0).isSlice()));
				dres("D15 corte libre: el zombi se queda con la cabeza y sin piernas (no anda)",
					st != null && !st.detached.containsKey("head") && z.getAttributeValue(EntityAttributes.GENERIC_MOVEMENT_SPEED) < 0.02 ? null
						: "estado " + (st == null ? "-" : st.detached.keySet()) + ", velocidad " + fmt(z.getAttributeValue(EntityAttributes.GENERIC_MOVEMENT_SPEED)));
				dres("D15 corte libre: la mitad de abajo anda sola", !ps.isEmpty() && ps.get(0).autonomous() ? null : "no es autónoma");
				dev.opeope.anatomy.Anatomy ca = dev.opeope.power.Dismember.anatomy(cow);
				Vec3d cc = cow.getPos().add(dev.opeope.anatomy.Anatomy.toWorld(ca.get(ca.core).center(), cow.getBodyYaw(), cow.getScale()));
				boolean did2 = dev.opeope.power.Dismember.sweep(sp, cow, List.of(new CutEngine.Plane(new Vec3d(1, 1.2, 0.3).normalize(), cc)), false);
				dres("D15 corte libre: una vaca en diagonal", did2 && partsOf(w, cow).size() == 1 && partsOf(w, cow).get(0).isSlice() ? null
					: "cortó " + did2 + ", " + partsOf(w, cow).size() + " piezas");
				sp.networkHandler.requestTeleport(gx0 + DX + 0.5, gfy + 1, gz0 + DZ + 0.5, 180f, 20f);
			});
		});
		step(20, "D15 corte libre: captura", () -> {
			drain();
			shot(mc, "d15-corte-libre");
		});
		step(2, "D15 corte libre: cerca", () -> server(srv, id, (sp, pw) ->
			sp.networkHandler.requestTeleport(gx0 + DX + 0.6, gfy + 1, gz0 + DZ - 1.6, 180f, 30f)));
		step(8, "D15 corte libre: captura cerca", () -> shot(mc, "d15-corte-libre-cerca"));
		step(2, "D15 corte libre: reunir", () -> server(srv, id, (sp, pw) -> {
			ServerWorld w = sp.getServerWorld();
			for (int eid : new int[]{dA, dB}) {
				if (!(w.getEntityById(eid) instanceof LivingEntity le)) continue;
				for (dev.opeope.entity.BodyPartEntity bp : partsOf(w, le)) dev.opeope.power.Dismember.shamblesPair(sp, bp, le);
				dres("D15 corte libre: Shambles vuelve a unir la pieza (" + EntityType.getId(le.getType()).getPath() + ")",
					dev.opeope.power.Dismember.state(le) == null && partsOf(w, le).isEmpty() ? null : "sigue partido");
				le.discard();
			}
		}));

		// la cámara en la cabeza: al jugador sin cabeza la vista se le va a su cabeza; al reunirla, vuelve
		step(2, "D cámara: cortar", () -> {
			drain();
			server(srv, id, (sp, pw) -> {
				sp.networkHandler.requestTeleport(gx0 + DX + 0.5, gfy + 1, gz0 + DZ + 0.5, 180f, 10f);
				dev.opeope.power.Dismember.cut(sp, List.of("head"), sp);
			});
		});
		waitFor("D cámara: ve desde su cabeza", 60, "la cámara no pasó a la cabeza",
			() -> mc.getCameraEntity() instanceof dev.opeope.entity.BodyPartEntity, () -> ok("D cámara: un jugador sin cabeza ve desde su cabeza"));
		step(25, "D cámara: captura", () -> shot(mc, "d-camara-cabeza"));
		step(2, "D cámara: reunir", () -> server(srv, id, (sp, pw) -> dev.opeope.power.Dismember.reattachAll(sp, true)));
		waitFor("D cámara: vuelve", 60, "la cámara no volvió al jugador", () -> mc.getCameraEntity() == mc.player,
			() -> ok("D cámara: al reunirla vuelve a su cuerpo"));

		step(2, "D fin 2", () -> server(srv, id, (sp, pw) -> {
			ServerWorld w = sp.getServerWorld();
			Box arena = new Box(gx0 + DX - 30, gfy - 4, gz0 + DZ - 30, gx0 + DX + 31, gfy + 20, gz0 + DZ + 31);
			for (Entity e : w.getEntitiesByClass(Entity.class, arena, en -> en instanceof MobEntity || en instanceof dev.opeope.entity.BodyPartEntity
				|| en instanceof ItemEntity)) e.discard();
			OpeConfig.MOBS_RECLAIM_PARTS = true;
			sp.setHealth(sp.getMaxHealth());
			dDone = 2;
		}));
		waitFor("d: fin 2", 40, "el servidor no terminó las pruebas D de las fases 2–4", () -> dDone == 2, AutoTest::drain);
		planCorteLibre(mc, srv, id);
	}

	private static volatile int eDone = 0, ePinId = -1, eReclaimId = -1;
	private static volatile double ePinY = 0;

	/**
	 * (fase libre E1–E8): multi-plano (Cruz ×4), corte recursivo sobre BodyPartEntity, CSG (caja, cilindro, esfera,
	 * prisma libre), anatomía sintética sin JSON, recomposición parcial/total, Bisturí diferido + clavado y capturas.
	 */
	private static void planCorteLibre(MinecraftClient mc, IntegratedServer srv, UUID id) {
		final int DX = 80, DZ = 50;

		step(2, "E preparar y E1–E4 servidor", () -> {
			eDone = 0;
			DRES.clear();
			server(srv, id, (sp, pw) -> {
				ServerWorld w = sp.getServerWorld();
				int ax0 = gx0 + DX, ay0 = gfy, az0 = gz0 + DZ;
				OpeConfig.MOBS_RECLAIM_PARTS = false;
				OpeConfig.FREE_PLANE_CUTS = true;
				pw.zeroGravityRoom = false;
				if (pw.room == null || pw.room.dim != w.getRegistryKey()) {
					pw.room = new Room(w.getRegistryKey(), new Vec3d(ax0 + 0.5, ay0 + 2, az0 - 3.0), 14);
					pw.room.state = Room.State.FIXED;
					PowerManager.broadcastRoom(sp, 0, pw.room);
				}
				long drop0 = PieceEntity.DROPPED;

				// E1: Cruz ×4 (dos planos perpendiculares) → >= 3 piezas vivas sueltas
				ZombieEntity z1 = spawnZombie(w, ax0 - 4.0, ay0 + 1, az0 - 4.0, 0f, false);
				if (z1 != null) {
					Vec3d c1 = z1.getBoundingBox().getCenter();
					boolean okCross = dev.opeope.power.Dismember.sweep(sp, z1, List.of(
						new CutEngine.Plane(new Vec3d(1, 0, 0), c1),
						new CutEngine.Plane(new Vec3d(0, 1, 0), c1)), false);
					List<dev.opeope.entity.BodyPartEntity> ps1 = partsOf(w, z1);
					dev.opeope.anatomy.DismemberState st1 = dev.opeope.power.Dismember.state(z1);
					dres("E1 multi-plano cruz: >= 3 piezas vivas",
						okCross && ps1.size() >= 3 && st1 != null && st1.sliceVolumes.size() >= 3 ? null
							: "ok=" + okCross + " parts=" + ps1.size());

					// E6a: Recomposición parcial y total de z1
					dev.opeope.entity.BodyPartEntity first = ps1.isEmpty() ? null : ps1.get(0);
					if (first != null) {
						dev.opeope.power.Dismember.shamblesPair(sp, first, z1);
						int afterOne = partsOf(w, z1).size();
						for (dev.opeope.entity.BodyPartEntity rest : partsOf(w, z1)) dev.opeope.power.Dismember.shamblesPair(sp, rest, z1);
						int afterAll = partsOf(w, z1).size();
						dres("E6 recomposición parcial y total multi-corte",
							afterOne >= 2 && afterAll == 0 && dev.opeope.power.Dismember.state(z1) == null ? null
								: "tras1=" + afterOne + " trasTodo=" + afterAll);
					}
					z1.discard();
				}

				// E1b: corte en esquina superior/diagonal fuera del centro del torso
				ZombieEntity zCorner = spawnZombie(w, ax0 - 6.0, ay0 + 1, az0 - 6.0, 0f, false);
				if (zCorner != null) {
					Vec3d cornerPt = zCorner.getPos().add(0, 1.78, 0);
					boolean okCorner = dev.opeope.power.Dismember.sweep(sp, zCorner,
						List.of(new CutEngine.Plane(new Vec3d(0.6, 1.0, 0.2).normalize(), cornerPt)), false);
					dres("E1b corte diagonal en cabeza sin tocar centro del torso",
						okCorner && partsOf(w, zCorner).size() == 1 ? null : "ok=" + okCorner + " parts=" + partsOf(w, zCorner).size());
					for (dev.opeope.entity.BodyPartEntity bp : partsOf(w, zCorner)) bp.discard();
					zCorner.discard();
				}

				// E2: corte recursivo sobre BodyPartEntity + recomposición parcial
				ZombieEntity zRec = spawnZombie(w, ax0 + 6.0, ay0 + 1, az0 - 4.0, 0f, false);
				if (zRec != null) {
					dev.opeope.entity.BodyPartEntity head = dev.opeope.power.Dismember.cut(zRec, List.of("head"), sp);
					dev.opeope.entity.BodyPartEntity headHalf = head == null ? null
						: dev.opeope.power.Dismember.splitBodyPart(sp, head,
						new CutEngine.Plane(new Vec3d(1, 0.2, 0).normalize(), head.getBoundingBox().getCenter()), false, false);
					List<dev.opeope.entity.BodyPartEntity> recParts = partsOf(w, zRec);
					dres("E2 corte recursivo sobre BodyPartEntity",
						headHalf != null && recParts.size() == 2 && dev.opeope.power.Dismember.owns(zRec, headHalf) ? null
							: "headHalf=" + headHalf + " parts=" + recParts.size());
					if (headHalf != null) {
						dev.opeope.power.Dismember.shamblesPair(sp, headHalf, zRec);
						int mid = partsOf(w, zRec).size();
						for (dev.opeope.entity.BodyPartEntity bp : partsOf(w, zRec)) dev.opeope.power.Dismember.shamblesPair(sp, bp, zRec);
						dres("E6b recomposición parcial de cabeza partida recursivamente",
							mid == 1 && partsOf(w, zRec).isEmpty() && dev.opeope.power.Dismember.state(zRec) == null ? null
								: "mid=" + mid + " final=" + partsOf(w, zRec).size());
					}
					zRec.discard();
				}

				// E3: formas volumétricas CSG (Caja/Ventana, Cilindro/Túnel, Esfera)
				net.minecraft.entity.mob.HuskEntity hBox = EntityType.HUSK.create(w);
				net.minecraft.entity.passive.CowEntity cowCyl = EntityType.COW.create(w);
				PigEntity pigSph = EntityType.PIG.create(w);
				if (hBox != null && cowCyl != null && pigSph != null) {
					hBox.refreshPositionAndAngles(ax0 - 2.2, ay0 + 1, az0 - 5.5, 0f, 0f);
					cowCyl.refreshPositionAndAngles(ax0 + 0.5, ay0 + 1, az0 - 5.5, 0f, 0f);
					pigSph.refreshPositionAndAngles(ax0 + 3.2, ay0 + 1, az0 - 5.5, 0f, 0f);
					for (MobEntity m : new MobEntity[]{hBox, cowCyl, pigSph}) {
						m.setBodyYaw(0f); m.setAiDisabled(true); m.setPersistent(); w.spawnEntity(m);
					}
					dev.opeope.entity.BodyPartEntity bpBox = dev.opeope.power.Dismember.extractShape(sp, hBox,
						new dev.opeope.anatomy.CutOp.BoxVolume(0f, 18f, 0f, 2.2f, 2.2f, 48f, 1, 0, 0, 0, 1, 0, 0, 0, 1, false), false, false);
					dev.opeope.entity.BodyPartEntity bpCyl = dev.opeope.power.Dismember.extractShape(sp, cowCyl,
						new dev.opeope.anatomy.CutOp.CylinderVolume(0f, 14f, 0f, 0, 0, 1, 3.2f, 48f, false), false, false);
					dev.opeope.entity.BodyPartEntity bpSph = dev.opeope.power.Dismember.extractShape(sp, pigSph,
						new dev.opeope.anatomy.CutOp.SphereVolume(0f, 11f, 0f, 4.2f, false), false, false);
					var stBox = dev.opeope.power.Dismember.state(hBox);
					var stCyl = dev.opeope.power.Dismember.state(cowCyl);
					var stSph = dev.opeope.power.Dismember.state(pigSph);
					boolean okCsg = bpBox != null && bpCyl != null && bpSph != null
						&& stBox != null && !stBox.remainingVolume().contains(0f, 18f, 0f)
						&& stCyl != null && !stCyl.remainingVolume().contains(0f, 14f, 0f)
						&& stSph != null && !stSph.remainingVolume().contains(0f, 11f, 0f);
					dres("E3 CSG Box/Cylinder/Sphere dejan hueco real y pieza sólida", okCsg ? null
						: "box=" + (bpBox != null) + " cyl=" + (bpCyl != null) + " sph=" + (bpSph != null));
					for (MobEntity m : new MobEntity[]{hBox, cowCyl, pigSph}) {
						for (dev.opeope.entity.BodyPartEntity bp : partsOf(w, m)) bp.discard();
						m.discard();
					}
				}

				// E4: Lazo Libre (PrismVolume hexagonal)
				ZombieEntity zHex = spawnZombie(w, ax0 + 2.0, ay0 + 1, az0 - 7.0, 0f, false);
				if (zHex != null) {
					float[] hx = new float[6], hy = new float[6];
					for (int k = 0; k < 6; k++) {
						double a = Math.PI * 2 * k / 6.0;
						hx[k] = (float) (Math.cos(a) * 2.6);
						hy[k] = (float) (Math.sin(a) * 2.6);
					}
					var prism = new dev.opeope.anatomy.CutOp.PrismVolume(0f, 18f, 0f, 1, 0, 0, 0, 1, 0, 0, 0, 1, 48f, hx, hy, false);
					dev.opeope.entity.BodyPartEntity bpHex = dev.opeope.power.Dismember.extractShape(sp, zHex, prism, false, false);
					var stHex = dev.opeope.power.Dismember.state(zHex);
					dres("E4 Lazo Libre (PrismVolume) extrae silueta poligonal",
						bpHex != null && stHex != null && !stHex.remainingVolume().contains(0f, 18f, 0f) ? null : "no extrajo");
					if (bpHex != null) bpHex.discard();
					zHex.discard();
				}

				// E5: Mob sin JSON de anatomía (SyntheticAnatomy sobre BlazeEntity)
				net.minecraft.entity.mob.BlazeEntity blaze = EntityType.BLAZE.create(w);
				if (blaze != null) {
					blaze.refreshPositionAndAngles(ax0 - 4.0, ay0 + 1, az0 - 7.0, 0f, 0f);
					blaze.setAiDisabled(true); blaze.setPersistent();
					w.spawnEntity(blaze);
					boolean noJson = dev.opeope.anatomy.AnatomyRegistry.get(EntityType.BLAZE) == null;
					boolean okSyn = dev.opeope.power.Dismember.sweep(sp, blaze,
						List.of(new CutEngine.Plane(new Vec3d(0, 1, 0), blaze.getBoundingBox().getCenter())), false);
					List<dev.opeope.entity.BodyPartEntity> bps = partsOf(w, blaze);
					if (!bps.isEmpty()) dev.opeope.power.Dismember.shamblesPair(sp, bps.get(0), blaze);
					dres("E5 SyntheticAnatomy corta y reúne mob sin JSON (Blaze)",
						noJson && okSyn && bps.size() == 1 && partsOf(w, blaze).isEmpty() ? null
							: "noJson=" + noJson + " okSyn=" + okSyn + " parts=" + bps.size());
					blaze.discard();
				}

				dres("E sin piezas ni ítems soltados", PieceEntity.DROPPED == drop0 ? null : "dropped=" + (PieceEntity.DROPPED - drop0));
			});
		});

		// E7: Bisturí diferido + clavado (F_PINNED) y escena visual E8
		step(4, "E7 bisturi diferido y clavado", () -> {
			drain();
			server(srv, id, (sp, pw) -> {
				ServerWorld w = sp.getServerWorld();
				int ax0 = gx0 + DX, ay0 = gfy, az0 = gz0 + DZ;
				Techniques.bisturi(sp, pw, true);
				ZombieEntity zBis = spawnZombie(w, ax0 + 0.5, ay0 + 1, az0 - 8.5, 0f, false);
				if (zBis != null) {
					Vec3d c = zBis.getBoundingBox().getCenter();
					dev.opeope.entity.BodyPartEntity p1 = dev.opeope.power.Dismember.planeCut(sp, zBis,
						new CutEngine.Plane(new Vec3d(0, 1, 0), c), false, true);
					dev.opeope.entity.BodyPartEntity p2 = dev.opeope.power.Dismember.extractShape(sp, zBis,
						new dev.opeope.anatomy.CutOp.SphereVolume(0f, 27f, 0f, 2.5f, false), false, true);
					boolean froze = p1 != null && p1.frozen() && p2 != null && p2.frozen() && pw.bisturiHeldParts.size() == 2;
					Techniques.bisturi(sp, pw, false);
					boolean released = froze && !p1.frozen() && !p2.frozen() && pw.bisturiHeldParts.isEmpty();
					dres("E7a Bisturí congeló 2 piezas y las liberó juntas", released ? null
						: "froze=" + froze + " held=" + pw.bisturiHeldParts.size());
					if (p1 != null) {
						p1.setPosition(ax0 + 0.5, ay0 + 2.0, az0 - 8.5);
						p1.pin(new BlockPos(ax0, ay0, az0 - 8));
						ePinId = p1.getId();
						ePinY = p1.getY();
					}
				}
			});
		});
		step(8, "E7b comprobar clavado y preparar E7c", () -> {
			drain();
			server(srv, id, (sp, pw) -> {
				ServerWorld w = sp.getServerWorld();
				Entity pe = w.getEntityById(ePinId);
				boolean pinOk = pe instanceof dev.opeope.entity.BodyPartEntity bp && bp.pinned() && Math.abs(bp.getY() - ePinY) < 0.05;
				dres("E7b pieza clavada con Takt no cae por gravedad", pinOk ? null : "pe=" + pe);
				Box arena = new Box(gx0 + DX - 20, gfy - 4, gz0 + DZ - 20, gx0 + DX + 21, gfy + 20, gz0 + DZ + 21);
				for (Entity e : w.getEntitiesByClass(Entity.class, arena, en -> en instanceof MobEntity || en instanceof dev.opeope.entity.BodyPartEntity)) e.discard();

				// E7c: Regresión ConcurrentModificationException: >= 2 mobs cortados en ACTIVE mientras uno recoge su mitad
				OpeConfig.MOBS_RECLAIM_PARTS = true;
				eReclaimId = -1;
				PigEntity p1 = EntityType.PIG.create(w);
				PigEntity p2 = EntityType.PIG.create(w);
				if (p1 != null && p2 != null) {
					p1.refreshPositionAndAngles(gx0 + DX - 2.0, gfy + 1, gz0 + DZ - 5.0, 0f, 0f);
					p2.refreshPositionAndAngles(gx0 + DX + 4.0, gfy + 1, gz0 + DZ - 5.0, 0f, 0f);
					for (PigEntity pig : new PigEntity[]{p1, p2}) {
						pig.setAiDisabled(false);
						pig.setPersistent();
						w.spawnEntity(pig);
						dev.opeope.power.Dismember.planeCut(sp, pig,
							new CutEngine.Plane(new Vec3d(1, 0, 0), pig.getBoundingBox().getCenter()), false, false);
					}
					for (dev.opeope.entity.BodyPartEntity bp : partsOf(w, p1)) {
						bp.setPosition(p1.getX(), p1.getY(), p1.getZ());
						bp.setVelocity(Vec3d.ZERO);
					}
					p1.setVelocity(Vec3d.ZERO);
					eReclaimId = p1.getId();
				}
			});
		});
		step(65, "E7c verificar reclaim sin CME y preparar escena E8", () -> {
			server(srv, id, (sp, pw) -> {
				ServerWorld w = sp.getServerWorld();
				Entity pe = w.getEntityById(eReclaimId);
				boolean okReclaim = pe instanceof PigEntity p1 && p1.isAlive()
					&& partsOf(w, p1).isEmpty() && dev.opeope.power.Dismember.state(p1) == null;
				dres("E7c cerdo partido por la mitad recoge su parte sin CME", okReclaim ? null : "pe=" + pe);
				OpeConfig.MOBS_RECLAIM_PARTS = false;
				Box arena = new Box(gx0 + DX - 20, gfy - 4, gz0 + DZ - 20, gx0 + DX + 21, gfy + 20, gz0 + DZ + 21);
				for (Entity e : w.getEntitiesByClass(Entity.class, arena, en -> en instanceof MobEntity || en instanceof dev.opeope.entity.BodyPartEntity)) e.discard();

				// E8: Escena de capturas visuales — túnel cilíndrico en husk, vaca en cruz ×4 y lazo hexagonal en zombi
				double bx = gx0 + DX + 0.5, by = gfy + 1, bz = gz0 + DZ - 5.0;
				net.minecraft.entity.mob.HuskEntity husk = EntityType.HUSK.create(w);
				if (husk != null) {
					husk.refreshPositionAndAngles(bx - 1.6, by, bz, 15f, 0f);
					husk.setBodyYaw(15f); husk.setAiDisabled(true); husk.setPersistent(); w.spawnEntity(husk);
					dev.opeope.power.Dismember.extractShape(sp, husk,
						new dev.opeope.anatomy.CutOp.CylinderVolume(0f, 18f, 0f, 0, 0, 1, 2.7f, 48f, false), false, false);
				}
				net.minecraft.entity.passive.CowEntity cow = EntityType.COW.create(w);
				if (cow != null) {
					cow.refreshPositionAndAngles(bx + 0.6, by, bz - 0.5, -20f, 0f);
					cow.setBodyYaw(-20f); cow.setAiDisabled(true); cow.setPersistent(); w.spawnEntity(cow);
					Vec3d cc = cow.getBoundingBox().getCenter();
					dev.opeope.power.Dismember.sweep(sp, cow, List.of(
						new CutEngine.Plane(new Vec3d(1, 0.3, 0).normalize(), cc),
						new CutEngine.Plane(new Vec3d(0, 1, 0.25).normalize(), cc)), false);
				}
				net.minecraft.entity.mob.HuskEntity zHexShot = EntityType.HUSK.create(w);
				if (zHexShot != null) {
					zHexShot.refreshPositionAndAngles(bx + 2.4, by, bz, -25f, 0f);
					zHexShot.setBodyYaw(-25f); zHexShot.setAiDisabled(true); zHexShot.setPersistent(); w.spawnEntity(zHexShot);
					float[] hx = new float[6], hy = new float[6];
					for (int k = 0; k < 6; k++) {
						double a = Math.PI * 2 * k / 6.0;
						hx[k] = (float) (Math.cos(a) * 2.5);
						hy[k] = (float) (Math.sin(a) * 2.5);
					}
					dev.opeope.power.Dismember.extractShape(sp, zHexShot,
						new dev.opeope.anatomy.CutOp.PrismVolume(0f, 18f, 0f, 1, 0, 0, 0, 1, 0, 0, 0, 1, 48f, hx, hy, false), false, false);
				}
				sp.networkHandler.requestTeleport(bx + 0.4, by, bz + 4.2, 180f, 16f);
			});
		});
		step(20, "E8 captura general", () -> {
			drain();
			shot(mc, "e-corte-volumetrico");
		});
		step(2, "E8 acercar cámara", () -> server(srv, id, (sp, pw) ->
			sp.networkHandler.requestTeleport(gx0 + DX - 0.9, gfy + 1, gz0 + DZ - 3.2, 180f, 14f)));
		step(10, "E8 captura cerca", () -> shot(mc, "e-corte-volumetrico-cerca"));
		step(2, "E9 shaders y CEM", () -> {
			// E9a: vértices encadenados de 3 args (shaders / 3dSkinLayers / features) recortados por VolumeSliceConsumer
			dev.opeope.anatomy.CutVolume vol = dev.opeope.anatomy.CutVolume.ofSinglePlane(new float[]{0f, 1f, 0f, 12f});
			dev.opeope.anatomy.Anatomy zAnat = dev.opeope.anatomy.AnatomyRegistry.get(EntityType.ZOMBIE);
			dev.opeope.client.dismember.CapBuilder.Session ses = zAnat == null ? null
				: new dev.opeope.client.dismember.CapBuilder.Session(vol, zAnat, List.of("body"));
			List<float[]> outVerts = new ArrayList<>();
			net.minecraft.client.render.VertexConsumer sink = new net.minecraft.client.render.VertexConsumer() {
				private float vx, vy, vz;
				@Override public net.minecraft.client.render.VertexConsumer vertex(float x, float y, float z) { vx = x; vy = y; vz = z; return this; }
				@Override public net.minecraft.client.render.VertexConsumer color(int r, int g, int b, int a) { return this; }
				@Override public net.minecraft.client.render.VertexConsumer texture(float u, float v) { return this; }
				@Override public net.minecraft.client.render.VertexConsumer overlay(int u, int v) { return this; }
				@Override public net.minecraft.client.render.VertexConsumer light(int u, int v) { return this; }
				@Override public net.minecraft.client.render.VertexConsumer normal(float x, float y, float z) {
					outVerts.add(new float[]{vx, vy, vz});
					return this;
				}
			};
			dev.opeope.client.dismember.VolumeSliceConsumer slicer = new dev.opeope.client.dismember.VolumeSliceConsumer(
				sink, new org.joml.Matrix4f(), vol, ses);
			float[][] quad = {{0f, 4f, 0f}, {8f, 4f, 0f}, {8f, 20f, 0f}, {0f, 20f, 0f}};
			for (int qi = 0; qi < 4; qi++) {
				slicer.vertex(quad[qi][0], quad[qi][1], quad[qi][2])
					.color(255, 255, 255, 255).texture(0f, 0f).overlay(0, 10).light(240, 240).normal(0f, 0f, 1f);
			}
			boolean maxYOk = outVerts.size() == 4;
			for (float[] vtx : outVerts) if (vtx[1] > 12.01f) maxYOk = false;
			int segCnt = ses == null ? 0 : ses.recordedSegmentCount();
			check(maxYOk && segCnt >= 1, "E9a vértices encadenados recortados con borde de tapa",
				"verts=" + outVerts.size() + " segs=" + segCnt);

			// E9b: hueso reparentado EMF_head2 dentro de body + ModelPartRotateMixin mantiene invisible tras animate()
			net.minecraft.client.model.ModelData md = new net.minecraft.client.model.ModelData();
			net.minecraft.client.model.ModelPartData rootData = md.getRoot();
			rootData.addChild("head", net.minecraft.client.model.ModelPartBuilder.create(), net.minecraft.client.model.ModelTransform.NONE);
			net.minecraft.client.model.ModelPartData bodyData = rootData.addChild("body",
				net.minecraft.client.model.ModelPartBuilder.create().uv(0, 0).cuboid(-4f, 0f, -2f, 8f, 12f, 4f),
				net.minecraft.client.model.ModelTransform.NONE);
			bodyData.addChild("EMF_head2",
				net.minecraft.client.model.ModelPartBuilder.create().uv(0, 0).cuboid(-4f, -8f, -4f, 8f, 8f, 8f),
				net.minecraft.client.model.ModelTransform.NONE);
			net.minecraft.client.model.ModelPart cemRoot = net.minecraft.client.model.TexturedModelData.of(md, 64, 64).createModel();
			net.minecraft.client.render.entity.model.SinglePartEntityModel<LivingEntity> cemModel =
				new net.minecraft.client.render.entity.model.SinglePartEntityModel<>() {
					@Override public net.minecraft.client.model.ModelPart getPart() { return cemRoot; }
					@Override public void setAngles(LivingEntity entity, float limbAngle, float limbDistance, float animationProgress, float headYaw, float headPitch) {}
				};
			List<List<net.minecraft.client.model.ModelPart>> headChains = dev.opeope.client.dismember.ModelParts.chainsFor(cemModel, "head");
			net.minecraft.client.model.ModelPart emfHead = cemRoot.getChild("body").getChild("EMF_head2");
			boolean resolvedEmf = headChains.stream().anyMatch(ch -> !ch.isEmpty() && ch.get(ch.size() - 1) == emfHead)
				&& !dev.opeope.client.dismember.ModelParts.hasCuboidsDeep(cemRoot.getChild("head"))
				&& dev.opeope.client.dismember.ModelParts.hasCuboidsDeep(emfHead);
			dev.opeope.client.dismember.ClientDismember.pushHiddenParts(List.of(emfHead));
			boolean stayedHidden = false;
			try {
				emfHead.visible = true; // simula root.animate() de EMF encendiendo la visibilidad del hueso
				cemRoot.getChild("body").rotate(new net.minecraft.client.util.math.MatrixStack());
				stayedHidden = !emfHead.visible;
			} finally {
				dev.opeope.client.dismember.ClientDismember.popHiddenParts();
			}
			check(resolvedEmf && stayedHidden, "E9b FreshAnimations/CEM EMF_head2 resuelto y re-ocultado por rotate()",
				"resolved=" + resolvedEmf + " hidden=" + stayedHidden);
		});
		final int[] e10Ids = {-1, -1};
		final java.util.concurrent.atomic.AtomicBoolean e10SrvDone = new java.util.concurrent.atomic.AtomicBoolean(false);
		step(2, "E10 servidor", () -> server(srv, id, (sp, pw) -> {
			ServerWorld w = sp.getServerWorld();
			int ax0 = gx0 + DX, ay0 = gfy, az0 = gz0 + DZ;
			if (pw.room == null || pw.room.dim != w.getRegistryKey()) {
				pw.room = new Room(w.getRegistryKey(), new Vec3d(ax0 + 0.5, ay0 + 2, az0 - 3.0), 14);
				pw.room.state = Room.State.FIXED;
				PowerManager.broadcastRoom(sp, 0, pw.room);
			}

			// E10a: corte rasante transversal auto-centrado produce verdadera bisección autónoma en oveja
			net.minecraft.entity.passive.SheepEntity s1 = EntityType.SHEEP.create(w);
			if (s1 != null) {
				s1.refreshPositionAndAngles(ax0 - 4.0, ay0 + 1, az0 - 8.0, 0f, 0f);
				s1.setBodyYaw(0f);
				s1.setAiDisabled(true);
				s1.setPersistent();
				w.spawnEntity(s1);
				e10Ids[0] = s1.getId();
				dev.opeope.anatomy.Anatomy shAnat = dev.opeope.anatomy.AnatomyRegistry.getOrSynthetic(s1);
				double totalVol = dev.opeope.anatomy.VolumeBounds.estimateVolumePx3(
					shAnat, shAnat.segments.keySet(), dev.opeope.anatomy.CutVolume.WHOLE);
				dev.opeope.power.Dismember.sweep(sp, s1, List.of(
					new CutEngine.Plane(new Vec3d(0, 0, 1), s1.getPos().add(0, 0.7, -0.45))), false);
				List<dev.opeope.entity.BodyPartEntity> pts1 = partsOf(w, s1);
				dev.opeope.entity.BodyPartEntity bp1 = pts1.size() == 1 ? pts1.get(0) : null;
				double pieceVol = bp1 == null ? 0.0
					: dev.opeope.anatomy.VolumeBounds.estimateVolumePx3(shAnat, bp1.segments(), bp1.cutVolume());
				dev.opeope.power.Dismember.Caps c1 = dev.opeope.power.Dismember.caps(s1);
				boolean ok10a = bp1 != null && bp1.autonomous() && totalVol > 0
					&& pieceVol > 0.22 * totalVol && c1 != null
					&& c1.legsMissing() == 2 && c1.speedMul() < 0.65 && c1.drop() > 0.05f;
				dres("E10a corte rasante auto-centrado en oveja con parte autónoma", ok10a ? null
					: "parts=" + pts1.size() + " auto=" + (bp1 != null && bp1.autonomous())
					+ " volRatio=" + (totalVol > 0 ? pieceVol / totalVol : 0)
					+ " legsMiss=" + (c1 == null ? -1 : c1.legsMissing())
					+ " speed=" + (c1 == null ? -1 : c1.speedMul())
					+ " drop=" + (c1 == null ? -1 : c1.drop()));
			} else {
				dres("E10a corte rasante auto-centrado en oveja con parte autónoma", "sheep create null");
			}

			// E10b: bisección longitudinal en oveja da caída lateral y parte autónoma
			net.minecraft.entity.passive.SheepEntity s2 = EntityType.SHEEP.create(w);
			if (s2 != null) {
				s2.refreshPositionAndAngles(ax0 + 4.0, ay0 + 1, az0 - 8.0, 0f, 0f);
				s2.setBodyYaw(0f);
				s2.setAiDisabled(true);
				s2.setPersistent();
				w.spawnEntity(s2);
				e10Ids[1] = s2.getId();
				dev.opeope.power.Dismember.sweep(sp, s2, List.of(
					new CutEngine.Plane(new Vec3d(1, 0, 0), s2.getBoundingBox().getCenter())), false);
				List<dev.opeope.entity.BodyPartEntity> pts2 = partsOf(w, s2);
				dev.opeope.entity.BodyPartEntity bp2 = pts2.size() == 1 ? pts2.get(0) : null;
				dev.opeope.power.Dismember.Caps c2 = dev.opeope.power.Dismember.caps(s2);
				boolean ok10b = bp2 != null && bp2.autonomous() && c2 != null
					&& c2.legsMissing() == 2 && c2.drop() > 0.15f;
				dres("E10b bisección longitudinal en oveja caída + parte autónoma", ok10b ? null
					: "parts=" + pts2.size() + " auto=" + (bp2 != null && bp2.autonomous())
					+ " legsMiss=" + (c2 == null ? -1 : c2.legsMissing())
					+ " drop=" + (c2 == null ? -1 : c2.drop()));
			} else {
				dres("E10b bisección longitudinal en oveja caída + parte autónoma", "sheep create null");
			}

			// E10c: Shambles apuntando más allá del borde del ROOM recorta el destino dentro del ROOM
			Vec3d prevPos = sp.getPos();
			float prevYaw = sp.getYaw(), prevPitch = sp.getPitch();
			Vec3d rc = pw.room.center;
			sp.setPosition(rc.x, ay0 + 1.0, rc.z);
			sp.setYaw(0f);
			sp.setPitch(0f);
			Vec3d dest = Shambles.emptyPoint(sp);
			boolean ok10c = dest != null && PowerManager.inRoom(sp, dest.add(0, 0.5, 0));
			sp.setPosition(prevPos.x, prevPos.y, prevPos.z);
			sp.setYaw(prevYaw);
			sp.setPitch(prevPitch);
			dres("E10c Shambles al vacío recorta destino dentro del ROOM", ok10c ? null
				: "dest=" + dest);

			// E10d: selector de modo Shambles (0=Tú->Destino, 1=Tú<->Entidad, 2=Entidad A<->B)
			int prevShMode = pw.shamblesMode;
			pw.shamblesMode = 0;
			pw.shamblesMark = -1;
			pw.cooldowns[Tech.SHAMBLES.ordinal()] = 0;
			Actions.handle(sp, Actions.SHAMBLES_MODE, 1);
			int m1 = pw.shamblesMode;
			Actions.handle(sp, Actions.SHAMBLES_MODE, 1);
			int m2 = pw.shamblesMode;
			Actions.handle(sp, Actions.SHAMBLES_MODE, 1);
			int m3 = pw.shamblesMode;
			boolean cycleOk = m1 == 1 && m2 == 2 && m3 == 0;

			net.minecraft.entity.passive.SheepEntity sTp = EntityType.SHEEP.create(w);
			boolean mode0TpOk = false, mode1SwapOk = false;
			if (sTp != null) {
				sTp.refreshPositionAndAngles(ax0 + 0.5, ay0 + 1.0, az0 - 1.0, 0f, 0f);
				sTp.setAiDisabled(true);
				sTp.setPersistent();
				w.spawnEntity(sTp);
				// Modo 0 (Tú -> Destino): mirando directo a la oveja dentro del ROOM, teletransporta sin marcarla
				sp.setPosition(ax0 + 0.5, ay0 + 1.0, az0 - 5.0);
				sp.setYaw(0f);
				sp.setPitch(13.6f);
				pw.shamblesMode = Shambles.MODE_SELF_TP;
				pw.cooldowns[Tech.SHAMBLES.ordinal()] = 0;
				pw.shamblesMark = -1;
				Shambles.use(sp, pw, false);
				mode0TpOk = sp.getZ() > az0 - 4.0 && pw.shamblesMark == -1;

				// Modo 1 (Tú <-> Entidad): mirando a la oveja intercambia en 1 toque
				sp.setPosition(ax0 + 0.5, ay0 + 1.0, az0 - 5.0);
				sp.setYaw(0f);
				sp.setPitch(13.6f);
				sTp.setPosition(ax0 + 0.5, ay0 + 1.0, az0 - 1.0);
				pw.shamblesMode = Shambles.MODE_SELF_SWAP;
				pw.cooldowns[Tech.SHAMBLES.ordinal()] = 0;
				pw.shamblesMark = -1;
				Shambles.use(sp, pw, false);
				mode1SwapOk = sp.getZ() > az0 - 2.2 && sTp.getZ() < az0 - 3.8 && pw.shamblesMark == -1;
				sTp.discard();
			}
			pw.shamblesMode = prevShMode;
			pw.shamblesMark = -1;
			pw.cooldowns[Tech.SHAMBLES.ordinal()] = 0;
			sp.setPosition(prevPos.x, prevPos.y, prevPos.z);
			sp.setYaw(prevYaw);
			sp.setPitch(prevPitch);
			dres("E10d selector modos Shambles (Tú->Destino vs Tú<->Entidad)",
				(cycleOk && mode0TpOk && mode1SwapOk) ? null
					: "cycle=" + cycleOk + " m=" + m1 + "," + m2 + "," + m3
					+ " tp0=" + mode0TpOk + " swap1=" + mode1SwapOk);

			e10SrvDone.set(true);
		}));
		waitFor("E10 servidor listo", 40, "E10 servidor no terminó", e10SrvDone::get, AutoTest::drain);
		step(4, "E10b postura cliente y E10e", () -> {
			drain();
			Entity ce = mc.world == null ? null : mc.world.getEntityById(e10Ids[1]);
			dev.opeope.client.dismember.ClientDismember.Info ci = ce instanceof LivingEntity le
				? dev.opeope.client.dismember.ClientDismember.info(le) : null;
			float roll = ce instanceof LivingEntity le && ci != null
				? Math.abs(dev.opeope.client.dismember.ClientDismember.computePosture(
					le, ci, dev.opeope.anatomy.AnatomyRegistry.getOrSynthetic(le), 0f).rollDeg())
				: 0f;
			check(roll > 55f, "E10b postura caída lateral (>55°) en oveja partida",
				"entity=" + ce + " roll=" + roll);

			// E10e: FX espacial ShamblesFxClient + métricas procedimentales de PlayerAnimClient
			dev.opeope.client.render.ShamblesFxClient.clear();
			int pid = mc.player == null ? 0 : mc.player.getId();
			dev.opeope.client.render.ShamblesFxClient.receive(new Payloads.ShamblesFx(
				Payloads.ShamblesFx.KIND_SELF_TP, pid, pid, -1,
				0.0, 64.0, 0.0, 0.6f, 1.8f, 0f,
				0.0, 64.0, 8.0, 0.6f, 1.8f, 0f));
			int fxCount = dev.opeope.client.render.ShamblesFxClient.activeCount();
			dev.opeope.client.render.ShamblesFxClient.clear();
			float[] anim = dev.opeope.client.render.PlayerAnimClient.computeTestMetrics(0.28f, 0.12f, 95f, true, 0.85f);
			boolean animOk = fxCount >= 1 && anim[0] > 10f && anim[1] > 3f && anim[2] > 0.5f;
			check(animOk, "E10e animación Shambles 3D y cinemática de personaje",
				"fx=" + fxCount + " lean=" + anim[0] + " bank=" + anim[1] + " gest=" + anim[2]);
		});
		step(2, "E fin", () -> server(srv, id, (sp, pw) -> {
			ServerWorld w = sp.getServerWorld();
			Box arena = new Box(gx0 + DX - 30, gfy - 4, gz0 + DZ - 30, gx0 + DX + 31, gfy + 20, gz0 + DZ + 31);
			for (Entity e : w.getEntitiesByClass(Entity.class, arena, en -> en instanceof MobEntity || en instanceof dev.opeope.entity.BodyPartEntity
				|| en instanceof ItemEntity)) e.discard();
			OpeConfig.MOBS_RECLAIM_PARTS = true;
			sp.setHealth(sp.getMaxHealth());
			eDone = 1;
		}));
		waitFor("E: fin", 40, "el servidor no terminó las pruebas E", () -> eDone == 1, AutoTest::drain);
	}

	/** En el hilo del servidor: el terreno de setup en x0..x1 × z0..z1 (piedra, tres de tierra, hierba en fy y aire encima). */
	private static void patch(ServerWorld w, int x0, int x1, int z0, int z1, int fy) {
		BlockState air = Blocks.AIR.getDefaultState(), dirt = Blocks.DIRT.getDefaultState(), grass = Blocks.GRASS_BLOCK.getDefaultState();
		BlockState stone = Blocks.STONE.getDefaultState();
		BlockPos.Mutable m = new BlockPos.Mutable();
		for (int x = x0; x <= x1; x++) {
			for (int z = z0; z <= z1; z++) {
				for (int y = fy - 14; y <= fy + 45; y++) {
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
