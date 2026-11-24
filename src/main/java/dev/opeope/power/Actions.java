package dev.opeope.power;

import dev.opeope.registry.ModAttachments;
import dev.opeope.registry.ModItems;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;

/** Las órdenes que manda el cliente. El cliente decide qué tecla es qué; el servidor solo ejecuta. */
public final class Actions {
	private Actions() {}

	public static final int ROOM_PRESS = 1, ROOM_RELEASE = 2, ROOM_SCROLL = 3, ROOM_LAUNCH = 4, ROOM_BRING = 5;
	public static final int SHAMBLES = 10, MARKER = 11, SHAMBLES_MODE = 12;
	public static final int TAKT_TAP = 20, TAKT_DOUBLE = 21, TAKT_ORDER = 22, TAKT_THROW_ONE = 23, TAKT_THROW_ALL = 24, TAKT_SCROLL = 25;
	public static final int SCAN = 30, RADIO_KNIFE = 31, INJECTION = 32, GAMMA = 33, AWAKEN = 34, CURTAIN = 35;
	public static final int STERBEN = 40, SOULS = 41, UNION = 42, SURGERY = 43, RAIN = 44;
	public static final int FLOOR = 50;   // arg: 1..4 empieza (pilar, muralla, sima, meseta), 0 para
	public static final int PATTERN = 60, REACH = 61, SUSPEND = 62, MES = 63, COUNTER = 64;
	public static final int BISTURI = 70; // arg 1 empieza, 0 termina
	public static final int UNION_CLICK = 80, UNION_RECONSTRUCT = 81, WEAPON_USE = 82;
	public static final int KROOM = 90, ANESTHESIA = 91, SHOCK_WILLE = 92, RROOM = 93, PUNCTURE = 94, SILENT = 95, SQUEEZE = 96;

	// Fusión: Estilos de Combate (1=Fruta, 2=Espada Kikoku, 3=Pelea), Mover ROOM y Panel Isla Bisturí [J]
	public static final int STYLE_SWITCH = 100, FIGHT_COMBO = 101, ROOM_MOVE = 102, ISLAND_ACTION = 103;
	// Acciones exclusivas de Gravity (Zushi Zushi no Mi — SIN ROOM ni Takt)
	public static final int GRAVITY_HOLD_START = 110, GRAVITY_HOLD_END = 111, GRAVITY_VECTOR = 112,
		GRAVITY_LIFT = 113, GRAVITY_THROW = 114, GRAVITY_SLAM = 115, GRAVITY_SCROLL = 116,
		GRAVITY_METEOR_START = 117, GRAVITY_METEOR_RELEASE = 118, GRAVITY_METEOR_SIZE = 119;

	// Órdenes de Takt (R mantenida + tecla)
	public static final int ORDER_ORBIT = 1, ORDER_PARK = 2, ORDER_SETDOWN = 3, ORDER_DROP = 4, ORDER_ZONE = 5,
		ORDER_CUT = 6, ORDER_GATHER = 7, ORDER_UNITE = 8, ORDER_THROW_ALL = 9, ORDER_FORGE = 10, ORDER_MIND_BREAK = 20; // + 0..4

	public static void handle(ServerPlayerEntity p, int action, int arg) {
		if (action == ISLAND_ACTION && !ModAttachments.hasFruit(p)) {
			PowerManager.grantFruit(p);
		}
		if (!ModAttachments.hasFruit(p)) return;
		PlayerPower pw = PowerManager.get(p);
		boolean release = (action == ROOM_RELEASE) || (action == GRAVITY_HOLD_END) || (action == GRAVITY_METEOR_RELEASE)
			|| ((action == FLOOR || action == BISTURI || action == SURGERY
			|| action == ANESTHESIA || action == RROOM || action == SQUEEZE) && arg == 0)
			|| dev.opeope.power.mera.MeraActions.isRelease(action);
		if (!release && action != ISLAND_ACTION && !PowerManager.canUse(p)) {
			p.sendMessage(Text.translatable("opeope.msg.sea"), true);
			return;
		}
		if (pw.isMera()) {
			// Mera Mera no Mi: sus propias órdenes; de Law solo pasan el panel de la isla (cambiar de fruta) y el
			// Despertar (M), que en Ace es el Cuerpo de llamas
			if (action == AWAKEN) action = dev.opeope.power.mera.MeraActions.BODY;
			if (dev.opeope.power.mera.MeraPowers.handle(p, pw, action, arg)) return;
			if (action != ISLAND_ACTION && action != STYLE_SWITCH) return;
		}
		if (pw.isGravity() && handleGravityAction(p, pw, action, arg)) {
			return;
		}
		switch (action) {
			case ROOM_PRESS -> RoomOps.press(p, pw);
			case ROOM_RELEASE -> RoomOps.release(p, pw, arg);
			case ROOM_SCROLL -> RoomOps.scroll(p, pw, arg);
			case ROOM_LAUNCH -> RoomOps.launch(p, pw);
			case ROOM_BRING -> RoomOps.bring(p, pw);
			case SHAMBLES -> Shambles.use(p, pw, arg == 1);
			case SHAMBLES_MODE -> Shambles.cycleMode(p, pw, arg);
			case MARKER -> Techniques.marker(p, pw);
			case TAKT_TAP -> Takt.tap(p, pw);                    // el cliente ya manda TaktTap; esto queda por si acaso
			case TAKT_DOUBLE -> Takt.doubleTap(p, pw, null);
			case TAKT_ORDER -> Takt.order(p, pw, arg);
			case TAKT_THROW_ONE -> Takt.throwOne(p, pw);
			case TAKT_THROW_ALL -> Takt.throwAll(p, pw);
			case TAKT_SCROLL -> Takt.scroll(pw, arg);
			case SCAN -> Techniques.scan(p, pw);
			case RADIO_KNIFE -> {
				if (p.getMainHandStack().isOf(ModItems.HEART)) {
					Techniques.squeezeHeart(p, p.getMainHandStack());
					pw.squeezing = true;
				} else {
					Techniques.radioKnife(p, pw);
				}
			}
			case INJECTION -> Techniques.injection(p, pw);
			case GAMMA -> Techniques.gamma(p, pw);
			case AWAKEN -> Techniques.toggleAwaken(p, pw);
			case CURTAIN -> Techniques.curtain(p, pw);
			case STERBEN -> Techniques.sterben(p, pw);
			case SOULS -> Techniques.souls(p, pw);
			case UNION -> Union.toggle(p, pw);
			case SURGERY -> Union.surgery(p, pw, arg == 1);
			case RAIN -> Techniques.rain(p, pw);
			case FLOOR -> Terrain.floor(p, pw, arg);
			case PATTERN -> {
				pw.pattern = (pw.pattern + 1) % 4;
				pw.dirty = true;
			}
			case REACH -> {
				pw.reachTarget = !pw.reachTarget;
				pw.dirty = true;
			}
			case SUSPEND -> Techniques.toggleSuspend(p, pw);
			case MES -> Techniques.mes(p, pw);
			case COUNTER -> {
				if (pw.awakened) Techniques.puncture(p, pw);
				else Techniques.counterShock(p, pw);
			}
			case BISTURI -> Techniques.bisturi(p, pw, arg == 1);
			case UNION_CLICK -> Union.click(p, pw);
			case UNION_RECONSTRUCT -> Union.reconstruct(p, pw);
			case WEAPON_USE -> Forge.use(p, pw);
			case KROOM -> Techniques.kroom(p, pw);
			case ANESTHESIA -> Techniques.anesthesia(p, pw, arg == 1);
			case SHOCK_WILLE -> Techniques.shockWille(p, pw);
			case RROOM -> RoomOps.rroom(p, pw, arg == 1);
			case PUNCTURE -> Techniques.puncture(p, pw);
			case SILENT -> Techniques.silent(p, pw);
			case SQUEEZE -> pw.squeezing = arg == 1;
			case STYLE_SWITCH -> Techniques.setStyle(p, pw, arg);
			case FIGHT_COMBO -> Techniques.fightCombo(p, pw);
			case ROOM_MOVE -> Techniques.moveRoom(p, pw, arg);
			case ISLAND_ACTION -> Techniques.islandAction(p, pw, arg);
			default -> {}
		}
	}

	private static boolean handleGravityAction(ServerPlayerEntity p, PlayerPower pw, int action, int arg) {
		switch (action) {
			// G: NUNCA crea ROOM; canaliza presión gravitatoria direccional o cambia de vector
			case ROOM_PRESS, GRAVITY_HOLD_START -> GravityPowers.startGravityCharge(p, pw);
			case ROOM_RELEASE, GRAVITY_HOLD_END -> GravityPowers.releaseGravityCharge(p, pw);
			case ROOM_SCROLL, GRAVITY_VECTOR -> GravityPowers.cycleVector(p, pw, arg >= 0 ? 1 : -1);
			case ROOM_LAUNCH, ROOM_BRING -> GravityPowers.cycleVector(p, pw, 1);

			// R y clics: levitación gravitatoria libre en cualquier lugar del mundo (SIN ROOM)
			case GRAVITY_LIFT, TAKT_TAP -> GravityPowers.levitateTap(p, pw);
			case GRAVITY_THROW, TAKT_THROW_ONE -> GravityPowers.throwGravityHeld(p, pw, false);
			case TAKT_THROW_ALL -> GravityPowers.throwGravityHeld(p, pw, true);
			case GRAVITY_SLAM -> GravityPowers.slamGravityHeld(p, pw);
			case GRAVITY_SCROLL, TAKT_SCROLL -> GravityPowers.scrollHoldDist(pw, arg);
			case TAKT_DOUBLE -> GravityPowers.releaseGravityHeld(p, pw);
			case TAKT_ORDER -> {
				if (arg == ORDER_MIND_BREAK) GravityPowers.gravityMinePress(p, pw);
				else if (arg == ORDER_DROP) GravityPowers.releaseGravityHeld(p, pw);
				else if (arg == ORDER_THROW_ALL) GravityPowers.throwGravityHeld(p, pw, true);
				else if (arg == ORDER_ORBIT || arg == ORDER_ZONE || arg == ORDER_CUT) GravityPowers.dressrosaRubble(p, pw);
				else GravityPowers.levitateTap(p, pw);
			}

			// Técnicas canon de Fujitora (Yakuza Kasen + Zushi Zushi no Mi)
			case SHAMBLES -> GravityPowers.rockFlightToggle(p, pw);
			case GRAVITY_METEOR_START -> GravityPowers.startMeteorCharge(p, pw);
			case GRAVITY_METEOR_RELEASE -> GravityPowers.releaseMeteorCharge(p, pw, arg == 1 || p.isSneaking() || pw.awakened);
			case GRAVITY_METEOR_SIZE -> GravityPowers.adjustMeteorSize(p, pw, arg >= 0 ? 1 : -1);
			case RADIO_KNIFE, KROOM -> GravityPowers.tengaiShinsei(p, pw, p.isSneaking() || pw.awakened);
			case INJECTION -> GravityPowers.moko(p, pw);
			case ANESTHESIA -> {
				if (arg == 1) GravityPowers.moko(p, pw);
			}
			case GAMMA, SHOCK_WILLE -> GravityPowers.jigokuTabi(p, pw);
			case SCAN -> GravityPowers.blindObservationHaki(p, pw);
			case RROOM -> {
				if (arg == 1) GravityPowers.blindObservationHaki(p, pw);
			}
			case CURTAIN, SILENT -> GravityPowers.repulsionShield(p, pw);
			case RAIN, STERBEN, SOULS -> GravityPowers.dressrosaRubble(p, pw);
			case COUNTER, PUNCTURE -> GravityPowers.tengaiShinsei(p, pw, true);
			case UNION -> GravityPowers.gravityHyperCompression(p, pw);
			case AWAKEN -> Techniques.toggleAwaken(p, pw);
			case SUSPEND -> GravityPowers.cycleVector(p, pw, 1);
			case MES, MARKER -> GravityPowers.gravityPullVortex(p, pw);
			case ISLAND_ACTION -> {
				return false;
			}
			// Bloquea cualquier acción residual de Law (ROOM, Bisturí, Estilo Kikoku, etc.) cuando Gravity está activa
			default -> {
				return true;
			}
		}
		return true;
	}
}
