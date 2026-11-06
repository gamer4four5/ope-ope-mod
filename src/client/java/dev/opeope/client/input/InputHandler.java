package dev.opeope.client.input;

import dev.opeope.client.ClientState;
import dev.opeope.client.render.CutFx;
import dev.opeope.client.screen.GuideScreen;
import dev.opeope.power.Actions;
import dev.opeope.registry.ModItems;
import net.minecraft.block.BedBlock;
import net.minecraft.block.BlockState;
import net.minecraft.block.ButtonBlock;
import net.minecraft.block.DoorBlock;
import net.minecraft.block.FenceGateBlock;
import net.minecraft.block.LeverBlock;
import net.minecraft.block.TrapdoorBlock;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.util.InputUtil;
import net.minecraft.item.ItemStack;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import org.lwjgl.glfw.GLFW;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * El teclado y el ratón de la fruta, con el mismo esquema que el juego (versión 21):
 * una tecla, un poder; la segunda fila con Tab mantenido (o un botón lateral del ratón); las órdenes de Takt con R
 * mantenida. Cada tecla se queda con la fila con la que se pulsó. La R espera 0,14 s antes de contar como fila.
 */
public final class InputHandler {
	private InputHandler() {}

	private static final int MOUSE = -100;
	private static final long R_ROW_DELAY = 140;

	public static boolean row2Down;
	public static boolean rDown;
	public static long rDownAt;
	private static boolean rUsed;
	private static long lastRTapAt;
	public static boolean roomDown;

	private static final Map<Integer, Runnable> RELEASES = new HashMap<>();
	private static final Set<Integer> CANCELLED = new HashSet<>();

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
	private static boolean openGuide;
	private static int kikokuSwing;

	private static MinecraftClient mc() {
		return MinecraftClient.getInstance();
	}

	private static boolean active() {
		MinecraftClient mc = mc();
		return mc.player != null && mc.currentScreen == null && ClientState.hasFruit();
	}

	public static boolean rowR() {
		return rDown && System.currentTimeMillis() - rDownAt >= R_ROW_DELAY;
	}

	private static void mark(int code, Runnable onRelease) {
		RELEASES.put(code, onRelease);
		CANCELLED.add(code);
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
		if (!active()) return false;
		return press(InputUtil.Type.KEYSYM, key, key);
	}

	public static boolean onMouse(int button, int action) {
		int code = MOUSE - button;
		if (action == GLFW.GLFW_RELEASE) return release(code);
		if (action != GLFW.GLFW_PRESS || !active()) return false;
		if (press(InputUtil.Type.MOUSE, button, code)) return true;
		if (mc().player.isSneaking()) return false;   // agachado: el clic normal de Minecraft
		if (button == GLFW.GLFW_MOUSE_BUTTON_LEFT) return leftPress(code);
		if (button == GLFW.GLFW_MOUSE_BUTTON_RIGHT) return rightPress(code);
		return false;
	}

	public static boolean onScroll(double vertical) {
		if (!active() || vertical == 0) return false;
		int dir = vertical > 0 ? 1 : -1;
		if (roomDown) {
			Send.action(Actions.ROOM_SCROLL, dir);
			return true;
		}
		if (Bisturi.active) {
			long h = mc().getWindow().getHandle();
			boolean shift = InputUtil.isKeyPressed(h, GLFW.GLFW_KEY_LEFT_SHIFT) || InputUtil.isKeyPressed(h, GLFW.GLFW_KEY_RIGHT_SHIFT);
			boolean ctrl = InputUtil.isKeyPressed(h, GLFW.GLFW_KEY_LEFT_CONTROL) || InputUtil.isKeyPressed(h, GLFW.GLFW_KEY_RIGHT_CONTROL);
			Bisturi.scroll(dir, shift, ctrl);
			return true;
		}
		if (rDown) {
			Send.action(Actions.TAKT_SCROLL, dir);
			rUsed = true;
			return true;
		}
		return false;
	}

	private static boolean press(InputUtil.Type type, int code, int markCode) {
		if (Keys.ROW2.matches(type, code) || Keys.ROW2_MOUSE_A.matches(type, code) || Keys.ROW2_MOUSE_B.matches(type, code)) {
			row2Down = true;
			mark(markCode, () -> row2Down = false);
			return true;
		}
		if (row2Down) {
			for (Keys k : Keys.values()) {
				if (k.name().startsWith("T_") && k.matches(type, code)) {
					Runnable rel = row2(k);
					mark(markCode, rel);
					return true;
				}
			}
			return false;
		}
		if (Keys.TAKT.matches(type, code)) {
			rDown = true;
			rDownAt = System.currentTimeMillis();
			rUsed = false;
			mark(markCode, InputHandler::taktRelease);
			return true;
		}
		if (rowR()) {
			for (Keys k : Keys.values()) {
				if (k.name().startsWith("R_") && k.matches(type, code)) {
					Send.action(Actions.TAKT_ORDER, order(k));
					rUsed = true;
					mark(markCode, () -> {});
					return true;
				}
			}
		}
		for (Keys k : Keys.values()) {
			if (k.name().startsWith("T_") || k.name().startsWith("R_") || k == Keys.TAKT) continue;
			if (k.matches(type, code)) {
				Runnable rel = main(k);
				if (rel == null) return false;
				mark(markCode, rel);
				return true;
			}
		}
		return false;
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
				roomDown = true;
				Send.action(Actions.ROOM_PRESS);
				return () -> {
					roomDown = false;
					Send.action(Actions.ROOM_RELEASE);
				};
			}
			case SHAMBLES -> {
				Send.action(Actions.SHAMBLES, p.isSneaking() ? 1 : 0);
				return NOTHING;
			}
			case MARKER -> {
				Send.action(Actions.MARKER);
				p.swingHand(Hand.MAIN_HAND);
				return NOTHING;
			}
			case SCAN -> {
				if (awake) {
					Send.action(Actions.RROOM, 1);
					return () -> Send.action(Actions.RROOM, 0);
				}
				Send.action(Actions.SCAN);
				return NOTHING;
			}
			case RADIO_KNIFE -> {
				boolean heart = p.getMainHandStack().isOf(ModItems.HEART);
				Send.action(awake && !heart ? Actions.KROOM : Actions.RADIO_KNIFE);
				p.swingHand(Hand.MAIN_HAND);
				return () -> Send.action(Actions.SQUEEZE, 0);
			}
			case INJECTION -> {
				if (awake) {
					Send.action(Actions.ANESTHESIA, 1);
					return () -> Send.action(Actions.ANESTHESIA, 0);
				}
				Send.action(Actions.INJECTION);
				return NOTHING;
			}
			case GAMMA -> {
				Send.action(awake ? Actions.SHOCK_WILLE : Actions.GAMMA);
				p.swingHand(Hand.MAIN_HAND);
				return NOTHING;
			}
			case AWAKEN -> {
				Send.action(Actions.AWAKEN);
				return NOTHING;
			}
			case CURTAIN -> {
				Send.action(awake ? Actions.SILENT : Actions.CURTAIN);
				return NOTHING;
			}
			case GUIDE -> {
				openGuide = true;
				return NOTHING;
			}
			default -> {
				return null;
			}
		}
	}

	/** La segunda fila (Tab mantenido). */
	private static Runnable row2(Keys k) {
		switch (k) {
			case T_STERBEN -> Send.action(Actions.STERBEN);
			case T_SOULS -> Send.action(Actions.SOULS);
			case T_UNION -> {
				unionCHeld = true;
				unionCAt = System.currentTimeMillis();
				surgeryOn = false;
				return () -> {
					unionCHeld = false;
					if (surgeryOn) Send.action(Actions.SURGERY, 0);
					else Send.action(Actions.UNION);
					surgeryOn = false;
				};
			}
			case T_RAIN -> Send.action(Actions.RAIN);
			case T_PILLAR, T_WALL, T_CHASM, T_PLATEAU -> {
				int arg = k == Keys.T_PILLAR ? 1 : k == Keys.T_WALL ? 2 : k == Keys.T_CHASM ? 3 : 4;
				Send.action(Actions.FLOOR, arg);
				return () -> Send.action(Actions.FLOOR, 0);
			}
			case T_PATTERN -> Send.action(Actions.PATTERN);
			case T_REACH -> Send.action(Actions.REACH);
			case T_SUSPEND -> Send.action(Actions.SUSPEND);
			case T_MES -> Send.action(Actions.MES);
			case T_COUNTER -> Send.action(Actions.COUNTER);
			default -> {}
		}
		return NOTHING;
	}

	private static void taktRelease() {
		long now = System.currentTimeMillis();
		if (!rUsed && now - rDownAt < 320) {
			if (now - lastRTapAt < 350) {
				Send.action(Actions.TAKT_DOUBLE);
				lastRTapAt = 0;
			} else {
				Send.action(Actions.TAKT_TAP);
				lastRTapAt = now;
			}
		}
		rDown = false;
	}

	// ------------------------------------------------------------------ ratón

	private static boolean leftPress(int code) {
		ClientPlayerEntity p = mc().player;
		ItemStack main = p.getMainHandStack();
		boolean empty = main.isEmpty();
		boolean kikoku = main.isOf(ModItems.KIKOKU);
		boolean free = empty || kikoku;
		if (roomDown) {
			Send.action(Actions.ROOM_LAUNCH);
			mark(code, NOTHING);
			return true;
		}
		if (rDown) {
			Send.action(Actions.TAKT_THROW_ONE);
			rUsed = true;
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
		if (Bisturi.active) {
			Bisturi.addPlane();
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
		if (roomDown) {
			Send.action(Actions.ROOM_BRING);
			mark(code, NOTHING);
			return true;
		}
		if (rDown) {
			Send.action(Actions.TAKT_ORDER, Actions.ORDER_THROW_ALL);
			rUsed = true;
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
			mark(code, Bisturi::commit);
			return true;
		}
		return false;
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
		Send.cut(0, new float[]{(float) n.x, (float) n.y, (float) n.z, (float) eye.x, (float) eye.y, (float) eye.z});
		CutFx.slashFlash(s[0], s[1]);
		p.swingHand(Hand.MAIN_HAND);
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
	}

	// ------------------------------------------------------------------ tick

	public static void tick(MinecraftClient mc) {
		if (openGuide) {
			openGuide = false;
			mc.setScreen(new GuideScreen());
		}
		if (dragging && (mc.currentScreen != null || ClientState.ownRoom() == null)) cancelDrag();
		if (mc.player == null || !ClientState.hasFruit()) {
			row2Down = rDown = roomDown = leftHeld = dragging = false;
			if (Bisturi.active) Bisturi.cancel();
			return;
		}
		if (leftHeld && ClientState.taktCount > 0) {
			leftHeldTicks++;
			if (leftHeldTicks > 6 && leftHeldTicks % 3 == 0) Send.action(Actions.TAKT_THROW_ONE);
		}
		if (unionCHeld && !surgeryOn && System.currentTimeMillis() - unionCAt > 400) {
			surgeryOn = true;
			Send.action(Actions.SURGERY, 1);
		}
	}
}
