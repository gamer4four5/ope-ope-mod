package dev.opeope.client.room;

import dev.opeope.OpeOpeMod;
import dev.opeope.client.AutoTest;
import dev.opeope.client.config.OpeSettings;
import dev.opeope.net.Payloads;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.MinecraftClient;
import net.minecraft.text.Text;
import net.minecraft.util.math.Vec3d;
import org.jetbrains.annotations.Nullable;

import java.util.Locale;
import java.util.function.Supplier;

/**
 * Los momentos del ROOM (spec §5.3 y §5.10). El servidor cuenta qué pasa (nace, se fija, cambia de tamaño, toca un
 * tope, sale volando, aterriza, vuelve, llega, se cierra) y aquí se reparte a quien lo hace notar: el mundo
 * ({@link RoomVisuals}, {@link RoomParticles}), el sonido ({@link RoomSounds}), la cámara ({@link CameraFx}), la
 * pantalla ({@link ScreenFx}), el HUD ({@link RoomHudState}) y el narrador.
 * <p>Lo de otros jugadores solo se ve y se oye en el mundo (sin cámara, pantalla, HUD ni voz) y solo si la cámara está
 * a menos de 160 + R del punto. Todo corre en el hilo del cliente.
 */
public final class RoomFxClient {
	private RoomFxClient() {}

	/** Momentos del ROOM propio (slot 0) recibidos, por tipo. */
	public static final int[] COUNTS = new int[Payloads.RoomFx.KINDS];
	/** {@link #clientTicks()} al recibir el último de cada tipo. */
	public static final int[] LAST_TICK = new int[Payloads.RoomFx.KINDS];

	private static int ticks;

	// el reloj de los efectos (s): avanza con el tiempo real, no en pausa y como mucho 0,05 s de golpe
	private static double clock;
	private static long clockNanos;

	// dentro o fuera del ROOM propio
	@Nullable private static RoomVisuals.Vis insideOf;
	private static boolean wasInside;
	private static int quietUntil;
	private static float growRate;

	private static final double[] HAND = new double[3];

	// ------------------------------------------------------------------ lo público

	/** Ticks del cliente contados aquí (no avanzan en pausa). */
	public static int clientTicks() {
		return ticks;
	}

	/** El reloj de los efectos del ROOM, en segundos. Se para con el juego en pausa. */
	public static double time() {
		long now = System.nanoTime();
		if (clockNanos != 0) {
			double dt = (now - clockNanos) / 1e9;
			if (dt > 0) {
				MinecraftClient mc = MinecraftClient.getInstance();
				if (mc == null || !mc.isPaused()) clock += Math.min(dt, 0.05);
			}
		}
		clockNanos = now;
		return clock;
	}

	public static void init() {
		ClientPlayNetworking.registerGlobalReceiver(Payloads.RoomFx.ID, (payload, ctx) -> receive(payload));
	}

	/** Pulsaste el ROOM sin tener uno: la semilla en la mano, un leve cabeceo y su sonido (antes de que conteste el servidor). */
	public static void onRoomKeyPressed() {
		MinecraftClient mc = MinecraftClient.getInstance();
		if (mc.player == null || mc.world == null) return;
		RoomVisuals.startSeed();
		CameraFx.kick(0.65f);
		RoomSounds.seed();
	}

	// ------------------------------------------------------------------ un momento

	public static void receive(Payloads.RoomFx fx) {
		int kind = fx.kind();
		if (kind < 0 || kind >= Payloads.RoomFx.KINDS) return;
		MinecraftClient mc = MinecraftClient.getInstance();
		boolean own = mc.player != null && fx.owner().equals(mc.player.getUuid());
		// me: el ROOM propio (slot 0). Solo él mueve la cámara, la pantalla, el HUD y la voz
		boolean me = own && fx.slot() == 0;
		if (me) {
			COUNTS[kind]++;
			LAST_TICK[kind] = ticks;
		}
		if (AutoTest.ON) OpeOpeMod.LOG.info(String.format(Locale.ROOT, "AUTOTEST fx %s r=%.1f", fx.name(), fx.radius()));
		if (mc.world == null) return;

		Vec3d a = fx.a(), b = fx.b();
		Vec3d cam = cameraPos(mc);
		if (!own) {
			double far = 160 + Math.max(fx.radius(), fx.radius2());
			if (cam.squaredDistanceTo(a) > far * far && cam.squaredDistanceTo(b) > far * far) return;
		}
		Vec3d eye = mc.player != null ? mc.player.getEyePos() : cam;
		// lo propio sale de tu mano tal como la ves (con el lado de la mano buena); lo de otros, del punto del servidor
		Vec3d hand = a;
		if (own && mc.player != null && (kind == Payloads.RoomFx.CREATE || kind == Payloads.RoomFx.CAST)) {
			RoomVisuals.handPoint(mc.player, 1f, HAND);
			hand = new Vec3d(HAND[0], HAND[1], HAND[2]);
		}
		if (me) {
			double d = kind == Payloads.RoomFx.CAST ? eye.distanceTo(b) : kind == Payloads.RoomFx.LAND ? eye.distanceTo(a) : 0;
			RoomHudState.onEvent(fx, d, ticks);
		}

		switch (kind) {
			case Payloads.RoomFx.CREATE -> {
				RoomVisuals.onCreate(fx, own, hand);
				RoomParticles.bloom(hand, b, own);
				RoomSounds.bloom(me, b);
				if (me) {
					RoomVisuals.endSeed(true);
					CameraFx.kick(1.8f);
					CameraFx.trauma(0.3f);
					CameraFx.fov(2f, 0.06f, 0.35f);
					ScreenFx.edgePulse(0.45f, 0.6f);
					ScreenFx.flash(0.12f, 0.25f);
					quietUntil = ticks + 10;
				}
			}
			case Payloads.RoomFx.FIX -> {
				RoomVisuals.onFix(fx);
				if (me) RoomSounds.stopGrow();
				RoomSounds.lock(me, a, fx.radius());
				if (me) {
					CameraFx.kick(-0.8f);
					ScreenFx.edgePulse(0.2f, 0.4f);
					narrate("ROOM fijo, " + metros(fx.radius()));
				}
			}
			case Payloads.RoomFx.NOTCH -> {
				RoomVisuals.onNotch(fx);
				if (me) RoomSounds.notch((fx.flags() & Payloads.RoomFx.F_SHRINK) == 0);
			}
			case Payloads.RoomFx.LIMIT -> {
				if (me) {
					CameraFx.kick(-0.45f);
					RoomSounds.limit(fx.flags() == Payloads.RoomFx.LIMIT_CENTRED);
				}
			}
			case Payloads.RoomFx.CAST -> {
				RoomVisuals.Fx comet = RoomVisuals.onCast(fx, own, hand);
				RoomParticles.cast(hand, b, own);
				if (me) {
					CameraFx.kick(1.2f);
					CameraFx.fov(3f, 0.06f, 0.30f);
					ScreenFx.streaks();
					RoomSounds.cast(true, hand, comet == null ? null : follow(comet));
				} else {
					RoomSounds.cast(false, a, null);
				}
			}
			case Payloads.RoomFx.LAND -> {
				RoomVisuals.onLand(fx, own);
				RoomParticles.land(a, fx.radius(), own);
				if (me) {
					double d = eye.distanceTo(a);
					float k = (float) Math.max(0.0, 1.0 - d / 120.0);
					CameraFx.kick(0.26f + 0.78f * k);
					CameraFx.trauma(0.08f + 0.25f * k);
					if (d < 60) ScreenFx.flash(0.08f + 0.1f * k, 0.25f);
					RoomSounds.land(true, a, fx.radius(), d);
					narrate("ROOM aterriza a " + metros((float) d));
					quietUntil = ticks + 10;
				} else {
					RoomSounds.land(false, a, fx.radius(), cam.distanceTo(a));
				}
			}
			case Payloads.RoomFx.BRING -> {
				RoomVisuals.onBring(fx, own, ticks);
				if (me) {
					CameraFx.kick(-0.65f);
					CameraFx.fov(-2.5f, 0.12f, 0.35f);
					RoomSounds.recall();
				}
			}
			case Payloads.RoomFx.ARRIVE -> {
				RoomVisuals.onArrive(fx, own);
				RoomParticles.arrive(a, own);
				RoomSounds.arrive(me, a);
				if (me) {
					CameraFx.kick(0.9f);
					CameraFx.trauma(0.15f);
					narrate("ROOM contigo");
					quietUntil = ticks + 10;
				}
			}
			case Payloads.RoomFx.CLOSE -> {
				// las luces del cierre solo si se veía aquí (el ROOM de otro mundo se cierra sin nada en este)
				boolean seen = RoomVisuals.visibleHere(fx.owner(), fx.slot());
				RoomVisuals.onClose(fx, own);
				if (seen) RoomParticles.close(a, fx.radius(), own);
				RoomSounds.close(me, a);
				if (me) narrate("ROOM cerrado");
			}
			default -> {
			}
		}
	}

	/** El silbido sigue al cometa: su punto en el arco a la hora del reloj de efectos. */
	private static Supplier<Vec3d> follow(RoomVisuals.Fx comet) {
		double[] p = new double[3];
		return () -> {
			comet.cometPos(time(), p);
			return new Vec3d(p[0], p[1], p[2]);
		};
	}

	// ------------------------------------------------------------------ cada tick

	/** Una vez por tick del cliente (nada en pausa). */
	public static void tick(MinecraftClient mc) {
		if (mc == null || mc.isPaused()) return;
		ticks++;
		RoomVisuals.tick(mc, ticks);
		RoomParticles.tick();

		RoomVisuals.Vis v = RoomVisuals.own();
		if (v != null && !v.shown()) v = null;   // escondido (lanzado nada más crearlo) o a punto de florecer
		boolean growing = v != null && v.state == RoomVisuals.GROWING;
		boolean in = false;
		float humVol = 0f;
		if (v != null && mc.player != null) {
			Vec3d eye = mc.player.getEyePos();
			double dx = eye.x - v.cx, dy = eye.y - v.cy, dz = eye.z - v.cz;
			double dist = Math.sqrt(dx * dx + dy * dy + dz * dz);
			in = dist <= v.r;
			// el murmullo de dentro: entero dentro, se apaga en los 40 bloques de fuera; no mientras crece (suena el crecer)
			if (!growing && dist < v.r + 40) {
				humVol = 0.25f * (in ? 1f : (float) (1.0 - (dist - v.r) / 40.0)) * v.fade;
			}
		}

		// entrar y salir: solo cruzando el borde del mismo ROOM; un ROOM nuevo o que desaparece no avisa
		if (v != insideOf) {
			insideOf = v;
			wasInside = in;
		} else if (v != null && in != wasInside) {
			wasInside = in;
			if (ticks >= quietUntil && !v.bringing) {
				ScreenFx.edgePulse(0.18f, 0.3f);
				RoomSounds.enter(in);
				narrate(in ? "Dentro del ROOM" : "Fuera del ROOM");
			}
		}
		ScreenFx.steadyEdge(in ? 0.08f : 0f);

		// lo que crece en este tick, suavizado (los paquetes y los ticks del cliente no van a la par)
		float dr = v != null && growing ? Math.max(0f, v.growDelta) : 0f;
		growRate += (dr - growRate) * 0.5f;
		if (!growing) growRate = 0f;
		RoomSounds.tick(growing, v != null ? v.rTarget : 0f, growRate, humVol, v != null ? v.r : 0f);
		CameraFx.traumaFloor(growing ? 0.22f : 0f);
		RoomHudState.tick(in, ticks);
	}

	/** Al desconectar: fuera efectos, sonidos y estado. Los contadores siguen (las pruebas comparan contra ellos). */
	public static void clear() {
		RoomVisuals.clear();
		RoomSounds.clear();
		RoomParticles.clear();
		CameraFx.clear();
		ScreenFx.clear();
		RoomHudState.clear();
		insideOf = null;
		wasInside = false;
		quietUntil = 0;
		growRate = 0f;
	}

	// ------------------------------------------------------------------ ayudas

	private static Vec3d cameraPos(MinecraftClient mc) {
		if (mc.gameRenderer != null && mc.gameRenderer.getCamera().isReady()) return mc.gameRenderer.getCamera().getPos();
		if (mc.player != null) return mc.player.getEyePos();
		return Vec3d.ZERO;
	}

	/** Lo dice el narrador (si está encendido y la opción «Narrar el ROOM» también). */
	private static void narrate(String s) {
		if (!OpeSettings.get().narrarRoom) return;
		MinecraftClient mc = MinecraftClient.getInstance();
		try {
			if (mc.getNarratorManager().isActive()) mc.getNarratorManager().narrateSystemMessage(Text.literal(s));
		} catch (RuntimeException e) {
			// sin narrador disponible: nada
		}
	}

	private static String metros(float d) {
		int m = Math.round(d);
		return m + (m == 1 ? " metro" : " metros");
	}
}
