package dev.opeope.client.input;

import dev.opeope.OpeOpeMod;
import dev.opeope.client.ClientState;
import dev.opeope.client.config.OpeProgress;
import dev.opeope.client.config.OpeSettings;
import dev.opeope.client.hud.Toasts;
import dev.opeope.client.render.CutFx;
import dev.opeope.client.room.RoomFxClient;
import dev.opeope.client.screen.GuideScreen;
import dev.opeope.client.screen.SettingsScreen;
import dev.opeope.power.Actions;
import dev.opeope.power.Tech;
import dev.opeope.registry.ModItems;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.block.BedBlock;
import net.minecraft.block.BlockState;
import net.minecraft.block.ButtonBlock;
import net.minecraft.block.DoorBlock;
import net.minecraft.block.FenceGateBlock;
import net.minecraft.block.LeverBlock;
import net.minecraft.block.TrapdoorBlock;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.option.GameOptions;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.sound.PositionedSoundInstance;
import net.minecraft.client.util.InputUtil;
import net.minecraft.item.ItemStack;
import net.minecraft.sound.SoundEvent;
import net.minecraft.text.Text;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.Vec3d;
import org.lwjgl.glfw.GLFW;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * El teclado y el ratón de la fruta, con el mismo esquema que el juego (versión 21):
 * una tecla, un poder; la segunda fila con Tab mantenido (o un botón lateral del ratón); las órdenes de Takt con R
 * mantenida. Cada tecla se queda con la fila con la que se pulsó. La R espera un poco (Ajustes: «Espera de R») antes de
 * contar como fila.
 * <p>
 * Modos (Ajustes → Controles): el ROOM, la segunda fila, las órdenes de Takt, el Bisturí y la Cirugía pueden quedarse
 * fijos con un toque en vez de mantener la tecla. Todo lo fijo se suelta con su tecla, con Esc, al abrir una pantalla,
 * al cambiar de ventana, tras 10 s sin usarlo (ROOM, Tab y R) o al perder la fruta.
 */
public final class InputHandler {
	private InputHandler() {}

	private static final int MOUSE = -100;
	private static final long ROOM_TAP_MS = 250;          // igual que ROOM_TAP_TICKS (5) del servidor
	private static final long BISTURI_TAP_MS = 250;
	private static final long ROW2_TAP_MS = 600;
	private static final long ROW2_STEP_MS = 300;
	private static final long PLAYER_LIST_MS = 600;
	private static final long LATCH_IDLE_MS = 10_000;

	public static boolean row2Down;
	public static boolean rDown;
	public static long rDownAt;
	private static boolean rUsed;
	private static long lastRTapAt, rDownTick, lastRTapTick;
	public static boolean roomDown;
	private static long clientTick;   // ticks del cliente: los toques también se miden así (con el juego a tirones)

	private static final Map<Integer, Runnable> RELEASES = new HashMap<>();
	private static final Set<Integer> CANCELLED = new HashSet<>();

	// ROOM
	private static boolean roomLatched;        // modo ROOM fijo (Alternar): rueda y clics van al ROOM
	private static boolean roomGrowLatched;    // Alternar sin ROOM: sigue creciendo hasta el próximo toque
	private static boolean roomUsed;
	private static long roomPressAt, lastRoomTapAt, roomLatchAt, roomPressTick, lastRoomTapTick;
	// segunda fila
	private static boolean row2Latched, row2OneKey, row2Used, row2ByKey, row2StepDone;
	private static long row2PressAt, row2LatchAt, row2PressTick;
	// órdenes de Takt
	private static boolean rowRLatched, rowROneKey;
	private static long rLatchAt;
	// Bisturí y Cirugía fijos, lista de jugadores
	private static boolean bisturiLatched;
	private static boolean surgeryLatched;
	private static boolean playerListShown;
	private static boolean wasFocused = true;
	private static long lastFruitInputAt;
	private static final long[] DENIED = new long[Tech.values().length];
	private static long lastDeniedSound;

	private static boolean leftHeld;
	private static int leftHeldTicks;
	private static boolean dragging;
	private static Vec3d dragLook = new Vec3d(0, 0, 1);
	private static double dragDX, dragDY;               // lo que se movió el ratón con la cámara quieta
	private static double lastSX = 1, lastSY = 0;       // el último tajo en pantalla (x a la derecha, y hacia arriba)
	private static final double DRAG_MIN = 10;           // menos que esto (≈1,5°) es un clic: repite el último tajo
	private static boolean unionCHeld;
	private static long unionCAt;
	private static boolean surgeryOn;
	private static int kikokuSwing;

	private static MinecraftClient mc() {
		return MinecraftClient.getInstance();
	}

	private static boolean active() {
		MinecraftClient mc = mc();
		return mc.player != null && mc.currentScreen == null && ClientState.hasFruit();
	}

	public static boolean rowR() {
		return rDown && (rowRLatched || rowROneKey || System.currentTimeMillis() - rDownAt >= OpeSettings.get().esperaR);
	}

	/** Rueda y clics van al ROOM: con la tecla mantenida o con el modo ROOM fijo. */
	private static boolean roomMode() {
		return roomDown || roomLatched || roomGrowLatched;
	}

	/**
	 * ¿Han pasado menos de ms desde (msAt, tickAt)? Vale el reloj o los ticks del juego (50 ms cada uno): si el juego va
	 * a tirones, un toque corto sigue siendo un toque, como lo cuenta el servidor.
	 */
	private static boolean before(long msAt, long tickAt, long ms) {
		return System.currentTimeMillis() - msAt < ms || (clientTick - tickAt) * 50L < ms;
	}

	// ------------------------------------------------------------------ estado para el HUD

	public static boolean row2Latched() {
		return row2Latched;
	}

	public static boolean rowRLatched() {
		return rowRLatched;
	}

	public static boolean roomLatched() {
		return roomLatched || roomGrowLatched;
	}

	public static boolean bisturiLatched() {
		return bisturiLatched;
	}

	public static boolean row2OneKey() {
		return row2OneKey;
	}

	public static boolean rowROneKey() {
		return rowROneKey;
	}

	public static boolean playerListShown() {
		return playerListShown;
	}

	public static long lastFruitInputAt() {
		return lastFruitInputAt;
	}

	/** Cuándo se pulsó por última vez una técnica que aún se estaba enfriando (ms de reloj; 0 = nunca). */
	public static long deniedAt(int techOrdinal) {
		return techOrdinal >= 0 && techOrdinal < DENIED.length ? DENIED[techOrdinal] : 0;
	}

	// ------------------------------------------------------------------ soltar teclas

	private static void mark(int code, Runnable onRelease) {
		RELEASES.put(code, onRelease);
		CANCELLED.add(code);
		lastFruitInputAt = System.currentTimeMillis();
	}

	private static boolean release(int code) {
		Runnable r = RELEASES.remove(code);
		boolean c = CANCELLED.remove(code);
		if (r != null) r.run();
		return c;
	}

	// ------------------------------------------------------------------ eventos

	public static boolean onKey(int key, int action) {
		if (key == InputUtil.UNKNOWN_KEY.getCode()) return false;
		if (action == GLFW.GLFW_REPEAT) return CANCELLED.contains(key);
		if (action == GLFW.GLFW_RELEASE) return release(key);
		if (action != GLFW.GLFW_PRESS) return false;
		if (key == GLFW.GLFW_KEY_ESCAPE && escapeLatches()) return true;
		if (!active()) return false;
		if (passthrough(key)) return false;
		return press(InputUtil.Type.KEYSYM, key, key);
	}

	public static boolean onMouse(int button, int action) {
		int code = MOUSE - button;
		if (action == GLFW.GLFW_RELEASE) return release(code);
		if (action != GLFW.GLFW_PRESS || !active()) return false;
		if (press(InputUtil.Type.MOUSE, button, code)) return true;
		if (Bisturi.active) {
			// el Bisturí manda sobre los clics, también agachado
			if (button == GLFW.GLFW_MOUSE_BUTTON_LEFT) {
				Bisturi.addPlane();
				mark(code, NOTHING);
				return true;
			}
			if (button == GLFW.GLFW_MOUSE_BUTTON_RIGHT) {
				bisturiLatched = false;
				Bisturi.commit();
				mark(code, NOTHING);
				return true;
			}
		}
		if (mc().player.isSneaking()) {
			endOneKeyR();   // agachado: el clic normal de Minecraft (y «una tecla» de Takt se cierra)
			return false;
		}
		if (button == GLFW.GLFW_MOUSE_BUTTON_LEFT) return leftPress(code);
		if (button == GLFW.GLFW_MOUSE_BUTTON_RIGHT) return rightPress(code);
		return false;
	}

	public static boolean onScroll(double vertical) {
		if (!active() || vertical == 0) return false;
		int dir = vertical > 0 ? 1 : -1;
		long now = System.currentTimeMillis();
		if (roomMode()) {
			roomUse();
			Send.action(Actions.ROOM_SCROLL, dir);
			Onboarding.mark(Onboarding.STEP_RESIZE);
			lastFruitInputAt = now;
			return true;
		}
		if (Bisturi.active) {
			long h = mc().getWindow().getHandle();
			boolean shift = InputUtil.isKeyPressed(h, GLFW.GLFW_KEY_LEFT_SHIFT) || InputUtil.isKeyPressed(h, GLFW.GLFW_KEY_RIGHT_SHIFT);
			boolean ctrl = InputUtil.isKeyPressed(h, GLFW.GLFW_KEY_LEFT_CONTROL) || InputUtil.isKeyPressed(h, GLFW.GLFW_KEY_RIGHT_CONTROL);
			Bisturi.scroll(dir, shift, ctrl);
			lastFruitInputAt = now;
			return true;
		}
		if (rDown) {
			Send.action(Actions.TAKT_SCROLL, dir);
			rUsed = true;
			rLatchAt = now;
			lastFruitInputAt = now;
			return true;
		}
		return false;
	}

	/** Teclas que siguen siendo de Minecraft aunque la fruta las use: F3 y sus combinaciones, Ctrl+B y Mayús+Q/F. */
	private static boolean passthrough(int key) {
		MinecraftClient mc = mc();
		if (InputUtil.isKeyPressed(mc.getWindow().getHandle(), GLFW.GLFW_KEY_F3)) return true;
		if (key == GLFW.GLFW_KEY_B && Screen.hasControlDown()) return true;
		GameOptions o = mc.options;
		boolean sneak = mc.player.isSneaking() || o.sneakKey.isPressed();
		if (!sneak) return false;
		InputUtil.Key k = InputUtil.Type.KEYSYM.createFromCode(key);
		return boundTo(k, o.dropKey) || boundTo(k, o.swapHandsKey);
	}

	private static boolean boundTo(InputUtil.Key key, KeyBinding kb) {
		return key != null && kb != null && key.equals(KeyBindingHelper.getBoundKeyOf(kb));
	}

	private static boolean press(InputUtil.Type type, int code, int markCode) {
		long now = System.currentTimeMillis();
		boolean byKey = Keys.ROW2.matches(type, code);
		if (byKey || Keys.ROW2_MOUSE_A.matches(type, code) || Keys.ROW2_MOUSE_B.matches(type, code)) {
			if (row2Latched || row2OneKey) {
				// estaba fija: este toque la cierra
				row2Latched = row2OneKey = false;
				row2Down = false;
				mark(markCode, NOTHING);
				return true;
			}
			row2Down = true;
			row2Used = false;
			row2ByKey = byKey;
			row2PressAt = now;
			row2PressTick = clientTick;
			row2StepDone = false;
			if (byKey && playerListRule()) {
				tip("tab_lista", "Mantén " + KeyLabels.name(Keys.ROW2.key) + " sin pulsar nada más para ver la lista de jugadores");
			}
			mark(markCode, InputHandler::row2Release);
			return true;
		}
		if (row2Down) {
			for (Keys k : Keys.values()) {
				if (k.group() == Keys.Group.ROW2 && k.matches(type, code)) {
					Runnable rel = row2(k);
					mark(markCode, rel);
					row2Used = true;
					row2LatchAt = now;
					hidePlayerList();
					Onboarding.mark(Onboarding.STEP_ROW2);
					if (row2OneKey) {
						row2OneKey = false;
						row2Down = false;
					}
					return true;
				}
			}
			if (!row2OneKey) return false;
			// «una tecla»: otra tecla cancela la segunda fila y hace lo suyo
			row2OneKey = false;
			row2Down = false;
		}
		if (Keys.TAKT.matches(type, code)) {
			if (rowRLatched || rowROneKey) {
				rowRLatched = rowROneKey = false;
				rDown = false;
				mark(markCode, NOTHING);
				return true;
			}
			rDown = true;
			rDownAt = now;
			rDownTick = clientTick;
			rUsed = false;
			mark(markCode, InputHandler::taktRelease);
			return true;
		}
		if (rowR()) {
			for (Keys k : Keys.values()) {
				if (k.group() == Keys.Group.TAKT && k.matches(type, code)) {
					Send.action(Actions.TAKT_ORDER, order(k));
					rUsed = true;
					rLatchAt = now;
					endOneKeyR();
					mark(markCode, NOTHING);
					return true;
				}
			}
			// los clics izquierdo y derecho también son de la fila de Takt (lanzar uno, lanzar todo): «una tecla» la
			// cierran leftPress y rightPress después de usarlos; cualquier otra tecla la cierra ya y hace lo suyo
			boolean rowClick = type == InputUtil.Type.MOUSE && (code == GLFW.GLFW_MOUSE_BUTTON_LEFT || code == GLFW.GLFW_MOUSE_BUTTON_RIGHT);
			if (!rowClick) endOneKeyR();
		}
		for (Keys k : Keys.values()) {
			if (k.group() != Keys.Group.MAIN || k == Keys.TAKT || KeyConflicts.row2Activator(k)) continue;
			if (k.matches(type, code)) {
				Runnable rel = main(k);
				if (rel == null) return false;
				mark(markCode, rel);
				return true;
			}
		}
		return false;
	}

	/** «Una tecla» en las órdenes de Takt: tras una orden (o un clic) se cierran. */
	private static void endOneKeyR() {
		if (!rowROneKey) return;
		rowROneKey = false;
		rDown = false;
	}

	private static int order(Keys k) {
		return switch (k) {
			case R_ORBIT -> Actions.ORDER_ORBIT;
			case R_PARK -> Actions.ORDER_PARK;
			case R_SETDOWN -> Actions.ORDER_SETDOWN;
			case R_DROP -> Actions.ORDER_DROP;
			case R_ZONE -> Actions.ORDER_ZONE;
			case R_CUT -> Actions.ORDER_CUT;
			case R_GATHER -> Actions.ORDER_GATHER;
			case R_UNITE -> Actions.ORDER_UNITE;
			case R_FORGE1 -> Actions.ORDER_FORGE;
			case R_FORGE2 -> Actions.ORDER_FORGE + 1;
			case R_FORGE3 -> Actions.ORDER_FORGE + 2;
			case R_FORGE4 -> Actions.ORDER_FORGE + 3;
			case R_FORGE5 -> Actions.ORDER_FORGE + 4;
			default -> 0;
		};
	}

	private static final Runnable NOTHING = () -> {};

	/** La fila de arriba. Devuelve lo que pasa al soltar (o null si la tecla no es de la fruta). */
	private static Runnable main(Keys k) {
		ClientPlayerEntity p = mc().player;
		boolean awake = ClientState.awakened();
		switch (k) {
			case ROOM -> {
				return roomPress(p);
			}
			case SHAMBLES -> {
				deny(Tech.SHAMBLES);
				Send.action(Actions.SHAMBLES, p.isSneaking() ? 1 : 0);
				Onboarding.mark(Onboarding.STEP_SHAMBLES);
				return NOTHING;
			}
			case MARKER -> {
				deny(Tech.MARKER);
				Send.action(Actions.MARKER);
				p.swingHand(Hand.MAIN_HAND);
				return NOTHING;
			}
			case SCAN -> {
				if (boundTo(Keys.SCAN.key, mc().options.swapHandsKey) && (!p.getMainHandStack().isEmpty() || !p.getOffHandStack().isEmpty())) {
					tip("f_mano", "Para cambiar de mano: " + sneakName() + " + " + KeyLabels.name(KeyBindingHelper.getBoundKeyOf(mc().options.swapHandsKey)));
				}
				if (awake) {
					deny(Tech.RROOM);
					Send.action(Actions.RROOM, 1);
					return () -> Send.action(Actions.RROOM, 0);
				}
				deny(Tech.SCAN);
				Send.action(Actions.SCAN);
				return NOTHING;
			}
			case RADIO_KNIFE -> {
				boolean heart = p.getMainHandStack().isOf(ModItems.HEART);
				if (!heart) deny(awake ? Tech.KROOM : Tech.RADIO_KNIFE);   // con el corazón aprieta: no se enfría
				Send.action(awake && !heart ? Actions.KROOM : Actions.RADIO_KNIFE);
				p.swingHand(Hand.MAIN_HAND);
				return () -> Send.action(Actions.SQUEEZE, 0);
			}
			case INJECTION -> {
				if (awake) {
					deny(Tech.ANESTHESIA);
					Send.action(Actions.ANESTHESIA, 1);
					return () -> Send.action(Actions.ANESTHESIA, 0);
				}
				deny(Tech.INJECTION);
				Send.action(Actions.INJECTION);
				return NOTHING;
			}
			case GAMMA -> {
				deny(awake ? Tech.SHOCK_WILLE : Tech.GAMMA);
				Send.action(awake ? Actions.SHOCK_WILLE : Actions.GAMMA);
				p.swingHand(Hand.MAIN_HAND);
				return NOTHING;
			}
			case AWAKEN -> {
				Send.action(Actions.AWAKEN);
				return NOTHING;
			}
			case CURTAIN -> {
				deny(awake ? Tech.SILENT : Tech.CURTAIN);
				Send.action(awake ? Actions.SILENT : Actions.CURTAIN);
				return NOTHING;
			}
			default -> {
				return null;
			}
		}
	}

	// ------------------------------------------------------------------ ROOM

	/**
	 * La tecla del ROOM. Mantener (de fábrica): mantén para abrirlo y suelta para fijarlo; un toque lo quita (o un doble
	 * toque, si «Quitar el ROOM» es «Doble toque»). Alternar: un toque sin ROOM lo abre y lo deja creciendo hasta el
	 * siguiente toque; un toque con ROOM entra en modo ROOM (rueda y clics sin mantener) y otro toque sale; un doble
	 * toque lo quita. El servidor: ROOM_RELEASE 0 quita el ROOM tras un toque sin usar (&lt; 5 ticks); 1 nunca lo quita.
	 */
	private static Runnable roomPress(ClientPlayerEntity p) {
		long now = System.currentTimeMillis();
		OpeSettings s = OpeSettings.get();
		Onboarding.noteRoomKey();
		if (roomGrowLatched) {
			roomGrowLatched = false;
			roomDown = false;
			Send.action(Actions.ROOM_RELEASE, 1);
			return NOTHING;
		}
		if (roomLatched) {
			roomLatched = false;
			if (lastRoomTapAt != 0 && before(lastRoomTapAt, lastRoomTapTick, s.dobleToque + 1L)) {
				Send.action(Actions.ROOM_PRESS);
				Send.action(Actions.ROOM_RELEASE, 0);
			}
			lastRoomTapAt = 0;
			return NOTHING;
		}
		boolean hadRoom = ClientState.ownRoom() != null;
		if (!hadRoom) {
			RoomFxClient.onRoomKeyPressed();
			if (!p.getMainHandStack().isEmpty() && boundTo(Keys.ROOM.key, mc().options.dropKey)) {
				tip("q_tirar", "Para tirar lo que llevas: " + sneakName() + " + " + KeyLabels.name(Keys.ROOM.key));
			}
		}
		roomDown = true;
		roomUsed = false;
		roomPressAt = now;
		roomPressTick = clientTick;
		Send.action(Actions.ROOM_PRESS);
		return () -> onRoomRelease(hadRoom);
	}

	private static void onRoomRelease(boolean hadRoom) {
		long now = System.currentTimeMillis();
		OpeSettings s = OpeSettings.get();
		roomDown = false;
		boolean tap = !roomUsed && before(roomPressAt, roomPressTick, ROOM_TAP_MS);
		if (s.modoRoom == OpeSettings.ModoRoom.ALTERNAR && tap) {
			if (!hadRoom) {
				// sigue creciendo (el servidor aún cree la tecla pulsada) hasta el próximo toque
				roomGrowLatched = true;
				roomDown = true;
				roomLatchAt = now;
				return;
			}
			Send.action(Actions.ROOM_RELEASE, 1);
			roomLatched = true;
			lastRoomTapAt = now;
			lastRoomTapTick = clientTick;
			roomLatchAt = now;
			return;
		}
		if (s.quitarRoom == OpeSettings.Quitar.DOBLE_TOQUE && hadRoom) {
			// con «Doble toque» una pulsación sola nunca quita el ROOM: solo el segundo toque seguido
			if (tap && lastRoomTapAt != 0 && before(lastRoomTapAt, lastRoomTapTick, s.dobleToque + 1L)) {
				Send.action(Actions.ROOM_RELEASE, 0);
				lastRoomTapAt = 0;
			} else {
				Send.action(Actions.ROOM_RELEASE, 1);
				lastRoomTapAt = tap ? now : 0;
				lastRoomTapTick = clientTick;
			}
			return;
		}
		Send.action(Actions.ROOM_RELEASE, 0);
	}

	/** Rueda o clic en modo ROOM: si crecía fijo, primero se fija y pasa a modo ROOM; cuenta como uso. */
	private static void roomUse() {
		if (roomGrowLatched) {
			roomGrowLatched = false;
			roomDown = false;
			Send.action(Actions.ROOM_RELEASE, 1);
			roomLatched = true;
		}
		roomUsed = true;
		roomLatchAt = System.currentTimeMillis();
		lastRoomTapAt = 0;
	}

	// ------------------------------------------------------------------ segunda fila y Takt

	/** La segunda fila (Tab mantenido, fija o «una tecla»). */
	private static Runnable row2(Keys k) {
		ClientPlayerEntity p = mc().player;
		boolean awake = ClientState.awakened();
		switch (k) {
			case T_STERBEN -> {
				deny(Tech.STERBEN);
				Send.action(Actions.STERBEN);
			}
			case T_SOULS -> {
				deny(Tech.SOULS);
				Send.action(Actions.SOULS);
			}
			case T_UNION -> {
				if (surgeryLatched) {
					// Cirugía fija: este toque la para
					surgeryLatched = false;
					Send.action(Actions.SURGERY, 0);
					return NOTHING;
				}
				unionCHeld = true;
				unionCAt = System.currentTimeMillis();
				surgeryOn = false;
				return () -> {
					unionCHeld = false;
					if (surgeryOn) {
						if (OpeSettings.get().modoCirugia == OpeSettings.Modo2.ALTERNAR) surgeryLatched = true;
						else Send.action(Actions.SURGERY, 0);
					} else {
						Send.action(Actions.UNION);
					}
					surgeryOn = false;
				};
			}
			case T_RAIN -> {
				deny(Tech.RAIN);
				Send.action(Actions.RAIN);
			}
			case T_PILLAR, T_WALL, T_CHASM, T_PLATEAU -> {
				int arg = k == Keys.T_PILLAR ? 1 : k == Keys.T_WALL ? 2 : k == Keys.T_CHASM ? 3 : 4;
				Send.action(Actions.FLOOR, arg);
				return () -> Send.action(Actions.FLOOR, 0);
			}
			case T_PATTERN -> Send.action(Actions.PATTERN);
			case T_REACH -> Send.action(Actions.REACH);
			case T_SUSPEND -> Send.action(Actions.SUSPEND);
			case T_MES -> {
				if (p == null || !p.getMainHandStack().isOf(ModItems.HEART)) deny(Tech.MES);   // devolver el corazón no se enfría
				Send.action(Actions.MES);
			}
			case T_COUNTER -> {
				deny(awake ? Tech.PUNCTURE : Tech.COUNTER);
				Send.action(Actions.COUNTER);
			}
			default -> {}
		}
		return NOTHING;
	}

	private static void row2Release() {
		long now = System.currentTimeMillis();
		OpeSettings s = OpeSettings.get();
		boolean listWasShown = playerListShown;
		hidePlayerList();
		// Minecraft no ve soltar la tecla (es de la fruta): que la lista no se quede puesta (p. ej. tras cerrar una pantalla)
		MinecraftClient mc = mc();
		if (row2ByKey && mc.options != null && boundTo(Keys.ROW2.key, mc.options.playerListKey)) mc.options.playerListKey.setPressed(false);
		if (!row2Down) return;
		if (!row2Used && !listWasShown) {
			if (s.modoTab == OpeSettings.ModoFila.ALTERNAR && before(row2PressAt, row2PressTick, ROW2_TAP_MS)) {
				row2Latched = true;   // row2Down sigue: la fila queda abierta
				row2LatchAt = now;
				return;
			}
			if (s.modoTab == OpeSettings.ModoFila.UNA_TECLA) {
				row2OneKey = true;
				row2LatchAt = now;
				return;
			}
		}
		row2Down = false;
	}

	private static void taktRelease() {
		long now = System.currentTimeMillis();
		OpeSettings s = OpeSettings.get();
		if (!rUsed && before(rDownAt, rDownTick, s.toqueR)) {
			if (lastRTapAt != 0 && before(lastRTapAt, lastRTapTick, s.dobleToque)) {
				Send.action(Actions.TAKT_DOUBLE);
				lastRTapAt = 0;
			} else {
				Send.action(Actions.TAKT_TAP);
				lastRTapAt = now;
				lastRTapTick = clientTick;
			}
			Onboarding.mark(Onboarding.STEP_TAKT);
			rDown = false;
			return;
		}
		if (!rUsed && s.modoR != OpeSettings.ModoFila.MANTENER) {
			// mantenida y suelta sin usar: las órdenes quedan abiertas (rDown sigue)
			if (s.modoR == OpeSettings.ModoFila.ALTERNAR) rowRLatched = true;
			else rowROneKey = true;
			rLatchAt = now;
			return;
		}
		rDown = false;
	}

	// ------------------------------------------------------------------ lista de jugadores (multijugador)

	private static boolean playerListRule() {
		MinecraftClient mc = mc();
		return OpeSettings.get().listaJugadoresTab && !mc.isInSingleplayer() && mc.options != null
			&& boundTo(Keys.ROW2.key, mc.options.playerListKey);
	}

	private static void hidePlayerList() {
		if (!playerListShown) return;
		playerListShown = false;
		MinecraftClient mc = mc();
		if (mc.options != null) mc.options.playerListKey.setPressed(false);
	}

	// ------------------------------------------------------------------ fijos, avisos y técnicas en espera

	private static boolean anyLatch() {
		return roomLatched || roomGrowLatched || row2Latched || row2OneKey || rowRLatched || rowROneKey || bisturiLatched || surgeryLatched;
	}

	/** Suelta todo lo fijo. Con send, avisa al servidor (un ROOM que crecía se fija; el Bisturí y la Cirugía paran). */
	private static void clearLatches(boolean send) {
		if (roomGrowLatched) {
			roomGrowLatched = false;
			roomDown = false;
			if (send) Send.action(Actions.ROOM_RELEASE, 1);
		}
		roomLatched = false;
		if (row2Latched || row2OneKey) {
			row2Latched = row2OneKey = false;
			row2Down = false;
		}
		if (rowRLatched || rowROneKey) {
			rowRLatched = rowROneKey = false;
			rDown = false;
		}
		if (bisturiLatched) {
			bisturiLatched = false;
			if (send) {
				Bisturi.cancel();
			} else {
				Bisturi.active = false;
				Bisturi.COMMITTED.clear();
			}
		}
		if (surgeryLatched) {
			surgeryLatched = false;
			if (send) Send.action(Actions.SURGERY, 0);
		}
	}

	/** Esc con algo fijo: lo suelta y no abre el menú (solo esa vez). */
	private static boolean escapeLatches() {
		MinecraftClient mc = mc();
		if (mc.player == null || mc.currentScreen != null || !anyLatch()) return false;
		clearLatches(true);
		CANCELLED.add(GLFW.GLFW_KEY_ESCAPE);
		lastFruitInputAt = System.currentTimeMillis();
		return true;
	}

	/** La técnica aún se enfría: el HUD sacude su casilla y suena un «no» corto. La acción se envía igual. */
	private static void deny(Tech t) {
		int o = t.ordinal();
		int[] cd = ClientState.cooldowns;
		if (o >= cd.length || cd[o] <= 2) return;
		long now = System.currentTimeMillis();
		DENIED[o] = now;
		if (now - lastDeniedSound < 250) return;
		lastDeniedSound = now;
		mc().getSoundManager().play(PositionedSoundInstance.master(SoundEvent.of(OpeOpeMod.id("ui.denegado")), 0.8f, 0.35f));
	}

	/** Un aviso que sale una sola vez (se recuerda en config/opeope-progreso.json). */
	private static void tip(String id, String text) {
		if (OpeProgress.tipSeen(id)) return;
		OpeProgress.markTip(id);
		Toasts.push(Text.literal(text), Toasts.INFO);
	}

	private static String sneakName() {
		return KeyLabels.name(KeyBindingHelper.getBoundKeyOf(mc().options.sneakKey));
	}

	// ------------------------------------------------------------------ ratón

	private static boolean leftPress(int code) {
		ClientPlayerEntity p = mc().player;
		ItemStack main = p.getMainHandStack();
		boolean empty = main.isEmpty();
		boolean kikoku = main.isOf(ModItems.KIKOKU);
		boolean free = empty || kikoku;
		if (roomMode()) {
			roomUse();
			Send.action(Actions.ROOM_LAUNCH);
			mark(code, NOTHING);
			return true;
		}
		if (rDown) {
			Send.action(Actions.TAKT_THROW_ONE);
			rUsed = true;
			rLatchAt = System.currentTimeMillis();
			endOneKeyR();
			mark(code, NOTHING);
			return true;
		}
		if (ClientState.weapon >= 0 && free) {
			Send.action(Actions.WEAPON_USE);
			p.swingHand(Hand.MAIN_HAND);
			mark(code, NOTHING);
			return true;
		}
		if (ClientState.taktCount > 0 && free) {
			Send.action(Actions.TAKT_THROW_ONE);
			p.swingHand(Hand.MAIN_HAND);
			leftHeld = true;
			leftHeldTicks = 0;
			mark(code, () -> leftHeld = false);
			return true;
		}
		if (ClientState.union() && empty) {
			Send.action(Actions.UNION_CLICK);
			p.swingHand(Hand.MAIN_HAND);
			mark(code, NOTHING);
			return true;
		}
		if (empty && ClientState.ownRoom() != null) {
			beginDrag();
			mark(code, InputHandler::endDrag);
			return true;
		}
		if (kikoku && ClientState.ownRoom() != null && ClientState.inOwnRoom(p.getEyePos())) {
			kikokuCut(p);
			return false;   // el golpe normal de la espada también ocurre
		}
		return false;
	}

	private static boolean interactable() {
		MinecraftClient mc = mc();
		if (!(mc.crosshairTarget instanceof BlockHitResult bhr) || bhr.getType() != HitResult.Type.BLOCK || mc.world == null) return false;
		BlockState st = mc.world.getBlockState(bhr.getBlockPos());
		return st.hasBlockEntity() || st.createScreenHandlerFactory(mc.world, bhr.getBlockPos()) != null
			|| st.getBlock() instanceof DoorBlock || st.getBlock() instanceof TrapdoorBlock || st.getBlock() instanceof FenceGateBlock
			|| st.getBlock() instanceof ButtonBlock || st.getBlock() instanceof LeverBlock || st.getBlock() instanceof BedBlock;
	}

	private static boolean rightPress(int code) {
		ClientPlayerEntity p = mc().player;
		ItemStack main = p.getMainHandStack();
		boolean empty = main.isEmpty();
		boolean free = empty || main.isOf(ModItems.KIKOKU);
		if (roomMode()) {
			roomUse();
			Send.action(Actions.ROOM_BRING);
			mark(code, NOTHING);
			return true;
		}
		if (rDown) {
			Send.action(Actions.TAKT_ORDER, Actions.ORDER_THROW_ALL);
			rUsed = true;
			rLatchAt = System.currentTimeMillis();
			endOneKeyR();
			mark(code, NOTHING);
			return true;
		}
		if (ClientState.taktCount > 0 && free) {
			mark(code, () -> {
				Send.action(Actions.TAKT_THROW_ALL);
				if (mc().player != null) mc().player.swingHand(Hand.MAIN_HAND);
			});
			return true;
		}
		if (interactable()) return false;
		if (ClientState.union() && empty) {
			Send.action(Actions.UNION_RECONSTRUCT);
			mark(code, NOTHING);
			return true;
		}
		if (empty && ClientState.ownRoom() != null) {
			Bisturi.start();
			long at = System.currentTimeMillis(), atTick = clientTick;
			mark(code, () -> bisturiRelease(at, atTick));
			return true;
		}
		return false;
	}

	/** Mantener: al soltar, corta. Alternar: un toque corto lo deja puesto y el próximo clic derecho corta. */
	private static void bisturiRelease(long pressedAt, long pressedTick) {
		if (!Bisturi.active) return;
		if (OpeSettings.get().modoBisturi == OpeSettings.Modo2.ALTERNAR && before(pressedAt, pressedTick, BISTURI_TAP_MS)) {
			bisturiLatched = true;
			return;
		}
		bisturiLatched = false;
		Bisturi.commit();
	}

	// ------------------------------------------------------------------ Amputate: trazar el tajo

	/**
	 * Como en el juego: al pulsar, la cámara se queda quieta y el ratón traza el tajo en la pantalla. El plano del
	 * corte pasa por tus ojos y contiene la dirección a la que miras y la del trazo. Un clic sin trazo repite el último.
	 */
	public static void beginDrag() {
		ClientPlayerEntity p = mc().player;
		if (p == null) return;
		dragging = true;
		dragLook = p.getRotationVector();
		dragDX = dragDY = 0;
	}

	/** El ratón mientras trazas: no gira la cámara, dibuja el tajo. */
	public static boolean captureLook(double dx, double dy) {
		if (!dragging) return false;
		dragDX += dx;
		dragDY += dy;
		return true;
	}

	public static boolean isDragging() {
		return dragging;
	}

	/** El trazo en pantalla en unidades del ratón (x a la derecha, y hacia arriba). */
	public static double dragX() {
		return dragDX;
	}

	public static double dragY() {
		return -dragDY;
	}

	/** La dirección del tajo en pantalla (unitaria): la del trazo, o la del último tajo si apenas se movió. */
	public static double[] swipeScreen() {
		double len = Math.sqrt(dragDX * dragDX + dragDY * dragDY);
		if (len < DRAG_MIN) return new double[]{lastSX, lastSY};
		return new double[]{dragDX / len, -dragDY / len};
	}

	/** El plano del tajo que se está trazando: {nx, ny, nz, px, py, pz} (pasa por los ojos). */
	public static double[] dragPlane() {
		ClientPlayerEntity p = mc().player;
		if (p == null || !dragging) return null;
		double[] s = swipeScreen();
		Vec3d n = slashNormal(dragLook, s[0], s[1]);
		Vec3d eye = p.getEyePos();
		return new double[]{n.x, n.y, n.z, eye.x, eye.y, eye.z};
	}

	private static Vec3d[] screenAxes(Vec3d look) {
		Vec3d right = look.crossProduct(new Vec3d(0, 1, 0));
		if (right.lengthSquared() < 1e-4) right = new Vec3d(1, 0, 0);
		right = right.normalize();
		Vec3d up = right.crossProduct(look).normalize();
		return new Vec3d[]{right, up};
	}

	/** Normal del plano que contiene la mirada y el trazo (sx a la derecha, sy hacia arriba). */
	public static Vec3d slashNormal(Vec3d look, double sx, double sy) {
		Vec3d[] ax = screenAxes(look);
		Vec3d swipe = ax[0].multiply(sx).add(ax[1].multiply(sy)).normalize();
		return look.crossProduct(swipe).normalize();
	}

	public static void endDrag() {
		if (!dragging) return;
		ClientPlayerEntity p = mc().player;
		double[] s = swipeScreen();
		dragging = false;
		dragDX = dragDY = 0;
		if (p == null) return;
		lastSX = s[0];
		lastSY = s[1];
		Vec3d n = slashNormal(dragLook, s[0], s[1]);
		Vec3d eye = p.getEyePos();
		deny(Tech.AMPUTATE);
		Send.cut(0, new float[]{(float) n.x, (float) n.y, (float) n.z, (float) eye.x, (float) eye.y, (float) eye.z});
		CutFx.slashFlash(s[0], s[1]);
		p.swingHand(Hand.MAIN_HAND);
		Onboarding.mark(Onboarding.STEP_CUT);
	}

	public static void cancelDrag() {
		dragging = false;
		dragDX = dragDY = 0;
	}

	/** Con la Kikoku dentro del ROOM, cada tajo corta de verdad lo que alcanza (alternando diagonales). */
	private static void kikokuCut(ClientPlayerEntity p) {
		Vec3d look = p.getRotationVector();
		Vec3d right = look.crossProduct(new Vec3d(0, 1, 0));
		if (right.lengthSquared() < 1e-4) right = new Vec3d(1, 0, 0);
		right = right.normalize();
		Vec3d up = right.crossProduct(look).normalize();
		double a = Math.toRadians((kikokuSwing++ % 2 == 0) ? 30 : -30);
		Vec3d swipe = right.multiply(Math.cos(a)).add(up.multiply(Math.sin(a)));
		Vec3d n = look.crossProduct(swipe).normalize();
		Vec3d eye = p.getEyePos();
		Send.cut(2, new float[]{(float) n.x, (float) n.y, (float) n.z, (float) eye.x, (float) eye.y, (float) eye.z});
		Onboarding.mark(Onboarding.STEP_CUT);
	}

	// ------------------------------------------------------------------ tick

	public static void tick(MinecraftClient mc) {
		clientTick++;
		OpeProgress.tick();
		Onboarding.tick();
		if (mc.currentScreen == null) {
			// el libro y los ajustes son teclas de Minecraft: funcionan también sin la fruta
			boolean libro = false, ajustes = false;
			while (OpeKeyBindings.LIBRO.wasPressed()) libro = true;
			while (OpeKeyBindings.AJUSTES.wasPressed()) ajustes = true;
			if (ajustes) mc.setScreen(new SettingsScreen(null));
			else if (libro) mc.setScreen(new GuideScreen());
		}
		boolean focused = mc.isWindowFocused();
		if (wasFocused && !focused) clearLatches(mc.player != null);   // solo el flanco: Xvfb puede decir «sin foco» siempre
		wasFocused = focused;
		if (mc.currentScreen != null && anyLatch()) clearLatches(mc.player != null);
		if (dragging && (mc.currentScreen != null || ClientState.ownRoom() == null)) cancelDrag();
		if (mc.player == null || !ClientState.hasFruit()) {
			clearLatches(mc.player != null);
			hidePlayerList();
			row2Down = rDown = roomDown = leftHeld = dragging = false;
			if (Bisturi.active) Bisturi.cancel();
			return;
		}
		long now = System.currentTimeMillis();
		OpeSettings s = OpeSettings.get();
		if (leftHeld && ClientState.taktCount > 0) {
			leftHeldTicks++;
			if (leftHeldTicks > 6 && leftHeldTicks % 3 == 0) Send.action(Actions.TAKT_THROW_ONE);
		}
		if (unionCHeld && !surgeryOn && now - unionCAt > s.esperaCirugia) {
			surgeryOn = true;
			Send.action(Actions.SURGERY, 1);
		}
		// lo fijo sin usar se suelta solo a los 10 s
		if ((roomLatched || roomGrowLatched) && now - roomLatchAt > LATCH_IDLE_MS) {
			if (roomGrowLatched) {
				roomGrowLatched = false;
				roomDown = false;
				Send.action(Actions.ROOM_RELEASE, 1);
			}
			roomLatched = false;
			lastRoomTapAt = 0;
		}
		if ((row2Latched || row2OneKey) && now - row2LatchAt > LATCH_IDLE_MS) {
			row2Latched = row2OneKey = false;
			row2Down = false;
		}
		if ((rowRLatched || rowROneKey) && now - rLatchAt > LATCH_IDLE_MS) {
			rowRLatched = rowROneKey = false;
			rDown = false;
		}
		// multijugador: Tab mantenida sin nada más enseña la lista de jugadores
		if (!playerListShown && row2Down && row2ByKey && !row2Used && !row2Latched && !row2OneKey
			&& now - row2PressAt >= PLAYER_LIST_MS && playerListRule()) {
			mc.options.playerListKey.setPressed(true);
			playerListShown = true;
		}
		if (row2Down && !row2StepDone && now - row2PressAt >= ROW2_STEP_MS) {
			row2StepDone = true;
			Onboarding.mark(Onboarding.STEP_ROW2);
		}
	}

	/** Al desconectar: se suelta todo sin avisar al servidor (ya no hay conexión). */
	public static void onDisconnect() {
		clearLatches(false);
		hidePlayerList();
		row2Down = rDown = roomDown = leftHeld = false;
		rUsed = roomUsed = row2Used = false;
		unionCHeld = surgeryOn = false;
		lastRTapAt = lastRoomTapAt = 0;
		cancelDrag();
		if (Bisturi.active) {
			Bisturi.active = false;
			Bisturi.COMMITTED.clear();
		}
		RELEASES.clear();
		CANCELLED.clear();
		OpeProgress.save();
	}
}
