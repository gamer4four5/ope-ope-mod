package dev.opeope.power;

import dev.opeope.registry.ModAttachments;
import dev.opeope.registry.ModItems;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;

/** Las órdenes que manda el cliente. El cliente decide qué tecla es qué; el servidor solo ejecuta. */
public final class Actions {
	private Actions() {}

	public static final int ROOM_PRESS = 1, ROOM_RELEASE = 2, ROOM_SCROLL = 3, ROOM_LAUNCH = 4, ROOM_BRING = 5;
	public static final int SHAMBLES = 10, MARKER = 11;
	public static final int TAKT_TAP = 20, TAKT_DOUBLE = 21, TAKT_ORDER = 22, TAKT_THROW_ONE = 23, TAKT_THROW_ALL = 24, TAKT_SCROLL = 25;
	public static final int SCAN = 30, RADIO_KNIFE = 31, INJECTION = 32, GAMMA = 33, AWAKEN = 34, CURTAIN = 35;
	public static final int STERBEN = 40, SOULS = 41, UNION = 42, SURGERY = 43, RAIN = 44;
	public static final int FLOOR = 50;   // arg: 1..4 empieza (pilar, muralla, sima, meseta), 0 para
	public static final int PATTERN = 60, REACH = 61, SUSPEND = 62, MES = 63, COUNTER = 64;
	public static final int BISTURI = 70; // arg 1 empieza, 0 termina
	public static final int UNION_CLICK = 80, UNION_RECONSTRUCT = 81, WEAPON_USE = 82;
	public static final int KROOM = 90, ANESTHESIA = 91, SHOCK_WILLE = 92, RROOM = 93, PUNCTURE = 94, SILENT = 95, SQUEEZE = 96;

	// Órdenes de Takt (R mantenida + tecla)
	public static final int ORDER_ORBIT = 1, ORDER_PARK = 2, ORDER_SETDOWN = 3, ORDER_DROP = 4, ORDER_ZONE = 5,
		ORDER_CUT = 6, ORDER_GATHER = 7, ORDER_UNITE = 8, ORDER_THROW_ALL = 9, ORDER_FORGE = 10; // + 0..4

	public static void handle(ServerPlayerEntity p, int action, int arg) {
		if (!ModAttachments.hasFruit(p)) return;
		PlayerPower pw = PowerManager.get(p);
		boolean release = (action == ROOM_RELEASE) || ((action == FLOOR || action == BISTURI || action == SURGERY
			|| action == ANESTHESIA || action == RROOM || action == SQUEEZE) && arg == 0);
		if (!release && !PowerManager.canUse(p)) {
			p.sendMessage(Text.translatable("opeope.msg.sea"), true);
			return;
		}
		switch (action) {
			case ROOM_PRESS -> RoomOps.press(p, pw);
			case ROOM_RELEASE -> RoomOps.release(p, pw, arg);
			case ROOM_SCROLL -> RoomOps.scroll(p, pw, arg);
			case ROOM_LAUNCH -> RoomOps.launch(p, pw);
			case ROOM_BRING -> RoomOps.bring(p, pw);
			case SHAMBLES -> Shambles.use(p, pw, arg == 1);
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
			default -> {}
		}
	}
}
