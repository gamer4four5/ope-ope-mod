package dev.opeope.client.render;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import dev.opeope.client.ClientState;
import dev.opeope.client.config.OpeSettings;
import dev.opeope.client.room.CameraFx;
import dev.opeope.client.room.RoomFxClient;
import dev.opeope.net.Payloads;
import dev.opeope.net.Payloads.LawFx;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.render.BufferBuilder;
import net.minecraft.client.render.BufferRenderer;
import net.minecraft.client.render.GameRenderer;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.RenderTickCounter;
import net.minecraft.client.render.Tessellator;
import net.minecraft.client.render.VertexFormat;
import net.minecraft.client.render.VertexFormats;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * Los efectos 3D de las técnicas de Law, sacados del anime: el tajo eléctrico del Radio Knife, la retícula y el
 * disparo de Injection Shot, la hoja corta de luz verde-amarilla de Gamma Knife que brilla dentro del cuerpo, el velo
 * quirúrgico del Curtain, el cubo de cristal de Mes, las palas del desfibrilador de Counter Shock, el barrido de
 * resonancia de Scan, las almas que se cruzan, el Despertar, la esfera de K-ROOM que envuelve la Kikoku, Anesthesia,
 * la onda interna de Shock Wille, la hoja colosal y la implosión de Puncture Wille, la burbuja muda de Silent, los
 * tajos de la Kikoku y el R-ROOM lanzado.
 * <p>
 * Todo son quads POSITION_COLOR aditivos en AFTER_TRANSLUCENT (mismo esquema que {@link GravityFxClient}); lo que pasa
 * dentro de un cuerpo (Gamma Knife, Shock Wille, Anesthesia) se dibuja otra vez sin profundidad, como rayos X.
 * Respeta «menos movimiento» (sin temblores ni parpadeos) y el nivel de destellos.
 */
public final class LawFxClient {
	private LawFxClient() {}

	// la paleta de Law: el celeste del ROOM, el blanco del filo, la electricidad, el verde-amarillo de Gamma Knife…
	private static final int ROOM = 0x8FE3FF;
	private static final int CORE = 0xEFFCFF;
	private static final int DEEP = 0x2F8CFF;
	private static final int ELEC = 0x9FDcFF;
	private static final int ELEC_HOT = 0xF4FBFF;
	private static final int GAMMA_G = 0x8DFF7A;
	private static final int GAMMA_Y = 0xFFF06A;
	private static final int HEART = 0xFF3A5C;
	private static final int SOUL_A = 0xFF86D8;
	private static final int SOUL_B = 0x86ECFF;
	private static final int GOLD = 0xFFD966;
	private static final int HUSH = 0x6474FF;
	private static final int DRAPE = 0xD8F4FF;

	private static final int SEG = 40;
	private static final float[] COS = new float[SEG + 1];
	private static final float[] SIN = new float[SEG + 1];

	static {
		for (int i = 0; i <= SEG; i++) {
			double a = Math.PI * 2.0 * i / SEG;
			COS[i] = (float) Math.cos(a);
			SIN[i] = (float) Math.sin(a);
		}
	}

	private record Ev(int kind, int userId, int targetId, Vec3d a, Vec3d b, float radius, float power,
					  double duration, double start, int seed) {}

	private static final List<Ev> EVENTS = new ArrayList<>();
	private static final int MAX_EVENTS = 64;

	private static double pulseStart = -100;
	private static float pulseStrength;
	private static int pulseRgb = ROOM;
	private static double scanStart = -100;

	private static final Matrix4f BASE = new Matrix4f();
	private static boolean plain;
	private static final Vector3f TV = new Vector3f();
	private static double u1x, u1y, u1z, u2x, u2y, u2z;
	private static Vec3d cam = Vec3d.ZERO;
	private static float td;

	/** Cuánto dura cada efecto en segundos como mínimo (el servidor puede pedir más con ticks). */
	private static final double[] MIN_SECONDS = {
		0.75, 0.45, 0.6, 0.95, 1.0, 1.15, 0.75, 1.5, 1.1, 0.45, 1.2, 0.95, 1.0, 1.3, 2.2, 1.0, 0.6, 0.4, 0.9, 0.6, 0.6
	};

	public static void clear() {
		EVENTS.clear();
		pulseStrength = 0f;
		scanStart = -100;
	}

	public static void receive(LawFx p) {
		if (p == null || p.kind() < 0 || p.kind() >= LawFx.KINDS) return;
		double now = RoomFxClient.time();
		double dur = Math.max(MIN_SECONDS[p.kind()], p.ticks() / 20.0);
		int seed = (int) (Double.doubleToLongBits(p.ax() * 31 + p.bz() * 17) ^ System.nanoTime());
		EVENTS.add(new Ev(p.kind(), p.userId(), p.targetId(), new Vec3d(p.ax(), p.ay(), p.az()), new Vec3d(p.bx(), p.by(), p.bz()),
			p.radius(), p.power(), dur, now, seed));
		while (EVENTS.size() > MAX_EVENTS) EVENTS.remove(0);

		gesture(p);

		MinecraftClient mc = MinecraftClient.getInstance();
		if (mc.player == null) return;
		boolean mine = p.userId() == mc.player.getId();
		double dist = Math.sqrt(mc.player.squaredDistanceTo(p.bx(), p.by(), p.bz()));
		switch (p.kind()) {
			case LawFx.RADIO_KNIFE -> {
				if (mine) {
					CameraFx.kick(-1.6f, 0.8f, 1.2f);
					CameraFx.trauma(0.22f);
					pulse(0.22f, ELEC);
				}
			}
			case LawFx.INJECTION_DASH -> {
				if (mine) {
					CameraFx.fov(7.5f, 0.03f, 0.40f);
					CameraFx.kick(1.2f, 0f, 0f);
				}
			}
			case LawFx.INJECTION_HIT -> {
				if (mine || dist < 24) {
					float k = mine ? 1f : (float) (1.0 - dist / 24.0);
					CameraFx.trauma(0.42f * k);
					CameraFx.kick(-2.6f * k, 0f, 0f);
					CameraFx.fov(-4.5f * k, 0.02f, 0.30f);
					pulse(0.30f * k, ROOM);
				}
			}
			case LawFx.GAMMA -> {
				if (mine) {
					CameraFx.kick(-1.0f, 0f, 0f);
					CameraFx.fov(-2.5f, 0.03f, 0.35f);
					pulse(0.22f, GAMMA_G);
				}
			}
			case LawFx.MES -> {
				if (mine) {
					CameraFx.kick(-0.8f, 0f, 0f);
					pulse(0.16f, HEART);
				}
			}
			case LawFx.COUNTER_SHOCK -> {
				if (mine) {
					CameraFx.trauma(0.34f);
					CameraFx.kick(-1.4f, 0f, 0f);
					pulse(0.30f, ELEC_HOT);
				}
			}
			case LawFx.SCAN -> {
				if (mine) scanStart = now;
			}
			case LawFx.AWAKEN -> {
				if (mine) {
					CameraFx.trauma(0.25f);
					CameraFx.fov(p.power() > 0.5f ? 5f : -3f, 0.06f, 0.6f);
					pulse(0.28f, p.power() > 0.5f ? GOLD : ROOM);
				}
			}
			case LawFx.KROOM -> {
				if (mine) {
					CameraFx.fov(3.5f, 0.05f, 0.45f);
					pulse(0.18f, ROOM);
				}
			}
			case LawFx.SHOCK_WILLE -> {
				if (mine || dist < 48) {
					float k = mine ? 1f : (float) (1.0 - dist / 48.0);
					CameraFx.trauma(0.6f * k);
					CameraFx.kick(-3.2f * k, 0f, 1.4f * k);
					CameraFx.fov(-6f * k, 0.03f, 0.55f);
					pulse(0.40f * k, ELEC_HOT);
				}
			}
			case LawFx.PUNCTURE -> {
				if (dist < 90) {
					float k = (float) MathHelper.clamp(1.0 - dist / 90.0, 0.2, 1.0);
					CameraFx.trauma(0.85f * k);
					CameraFx.kick(-4.0f * k, 0f, 2.0f * k);
					CameraFx.fov(-8f * k, 0.05f, 0.9f);
					pulse(0.45f * k, CORE);
				}
			}
			case LawFx.SILENT -> {
				if (mine) CameraFx.fov(-2.5f, 0.10f, 0.8f);
			}
			case LawFx.SWORD -> {
				if (mine) {
					CameraFx.kick(-1.2f, 0.6f, 1.6f);
					CameraFx.trauma(0.18f);
				}
			}
			case LawFx.RROOM_BURST -> {
				if (dist < 64) {
					float k = (float) (1.0 - dist / 64.0);
					CameraFx.trauma(0.45f * k);
					pulse(0.30f * k, ROOM);
				}
			}
			case LawFx.HEART_SQUEEZE -> {
				if (p.targetId() == mc.player.getId()) {
					CameraFx.trauma(0.4f);
					pulse(0.45f, HEART);
				}
			}
			default -> {}
		}
	}

	/** El gesto de Law en el jugador que usa la técnica (lo ven todos). */
	private static void gesture(LawFx p) {
		if (p.userId() < 0) return;
		int g = switch (p.kind()) {
			case LawFx.RADIO_KNIFE, LawFx.SWORD, LawFx.SHOCK_WILLE, LawFx.RAIN_SLASH -> PlayerAnimClient.GESTURE_SLASH;
			case LawFx.INJECTION_DASH, LawFx.GAMMA, LawFx.MES, LawFx.ANESTHESIA -> PlayerAnimClient.GESTURE_THRUST;
			case LawFx.COUNTER_SHOCK -> PlayerAnimClient.GESTURE_TWO_PALMS;
			case LawFx.CURTAIN -> PlayerAnimClient.GESTURE_SWEEP;
			case LawFx.PUNCTURE -> PlayerAnimClient.GESTURE_OVERHEAD;
			case LawFx.KROOM, LawFx.RROOM_CAST, LawFx.AWAKEN, LawFx.SILENT, LawFx.SCAN -> PlayerAnimClient.GESTURE_PALM;
			case LawFx.SOULS, LawFx.STERBEN -> PlayerAnimClient.GESTURE_INDEX;
			default -> -1;
		};
		if (g >= 0 && !(p.kind() == LawFx.RAIN_SLASH)) PlayerAnimClient.triggerGesture(p.userId(), g);
	}

	private static void pulse(float strength, int rgb) {
		if (strength <= 0.01f) return;
		pulseStart = RoomFxClient.time();
		pulseStrength = Math.min(0.6f, strength);
		pulseRgb = rgb;
	}

	// ------------------------------------------------------------------ dibujo en el mundo

	public static void renderWorld(WorldRenderContext ctx) {
		MinecraftClient mc = MinecraftClient.getInstance();
		if (mc.world == null || mc.player == null) return;
		double now = RoomFxClient.time();
		Iterator<Ev> it = EVENTS.iterator();
		while (it.hasNext()) {
			Ev e = it.next();
			double age = now - e.start;
			if (age < 0 || age > e.duration) it.remove();
		}
		boolean silentSelf = ClientState.has(Payloads.State.SILENT);
		if (EVENTS.isEmpty() && !silentSelf) return;

		cam = ctx.camera().getPos();
		td = ctx.tickCounter().getTickDelta(true);
		boolean calm = OpeSettings.reducedMotion();
		Matrix4f mv = RenderSystem.getModelViewMatrix();
		if (Math.abs(mv.m00() - 1) < 1e-4f && Math.abs(mv.m11() - 1) < 1e-4f && Math.abs(mv.m22() - 1) < 1e-4f) {
			BASE.set(ctx.positionMatrix());
			plain = false;
		} else {
			BASE.identity();
			plain = true;
		}

		RenderSystem.enableBlend();
		RenderSystem.blendFunc(GlStateManager.SrcFactor.SRC_ALPHA, GlStateManager.DstFactor.ONE);
		RenderSystem.disableCull();
		RenderSystem.enableDepthTest();
		RenderSystem.depthMask(false);
		RenderSystem.setShader(GameRenderer::getPositionColorProgram);
		try {
			BufferBuilder b = Tessellator.getInstance().begin(VertexFormat.DrawMode.QUADS, VertexFormats.POSITION_COLOR);
			for (Ev e : EVENTS) {
				double t = MathHelper.clamp((now - e.start) / e.duration, 0.0, 1.0);
				drawEvent(b, mc, e, t, now - e.start, now, calm);
			}
			end(b);
			// dentro de los cuerpos: Gamma Knife, Shock Wille y Anesthesia brillan a través de la carne, como rayos X
			if (OpeSettings.flashLevel() > 0) {
				RenderSystem.disableDepthTest();
				BufferBuilder x = Tessellator.getInstance().begin(VertexFormat.DrawMode.QUADS, VertexFormats.POSITION_COLOR);
				for (Ev e : EVENTS) {
					double t = MathHelper.clamp((now - e.start) / e.duration, 0.0, 1.0);
					drawInside(x, mc, e, t, now, calm);
				}
				end(x);
			}
		} finally {
			RenderSystem.enableDepthTest();
			RenderSystem.defaultBlendFunc();
			RenderSystem.depthMask(true);
			RenderSystem.enableCull();
			RenderSystem.disableBlend();
		}
	}

	private static void end(BufferBuilder b) {
		var built = b.endNullable();
		if (built != null) BufferRenderer.drawWithGlobalProgram(built);
	}

	private static void drawEvent(BufferBuilder b, MinecraftClient mc, Ev e, double t, double age, double now, boolean calm) {
		switch (e.kind) {
			case LawFx.RADIO_KNIFE -> radioKnife(b, e, t, now, calm);
			case LawFx.INJECTION_DASH -> injectionDash(b, mc, e, t, now, calm);
			case LawFx.INJECTION_HIT -> injectionHit(b, e, t, now, calm);
			case LawFx.GAMMA -> gamma(b, mc, e, t, now);
			case LawFx.CURTAIN -> curtain(b, mc, e, t, age, now, calm);
			case LawFx.MES -> mes(b, mc, e, t, now);
			case LawFx.COUNTER_SHOCK -> counterShock(b, mc, e, t, now, calm);
			case LawFx.SCAN -> scan(b, e, t, now);
			case LawFx.SOULS -> souls(b, e, t, now);
			case LawFx.RAIN_SLASH -> rainSlash(b, e, t);
			case LawFx.AWAKEN -> awaken(b, mc, e, t, now, calm);
			case LawFx.KROOM -> kroom(b, mc, e, t, now);
			case LawFx.ANESTHESIA -> anesthesia(b, mc, e, t, now);
			case LawFx.SHOCK_WILLE -> shockWille(b, e, t, now, calm);
			case LawFx.PUNCTURE -> puncture(b, e, t, now, calm);
			case LawFx.SILENT -> silent(b, mc, e, t, age, now);
			case LawFx.SWORD -> sword(b, e, t, now);
			case LawFx.RROOM_CAST -> rroomCast(b, e, t, now);
			case LawFx.RROOM_BURST -> rroomBurst(b, e, t, now);
			case LawFx.STERBEN -> sterben(b, e, t, now);
			case LawFx.HEART_SQUEEZE -> heartSqueeze(b, mc, e, t, now);
			default -> {}
		}
	}

	private static void drawInside(BufferBuilder b, MinecraftClient mc, Ev e, double t, double now, boolean calm) {
		switch (e.kind) {
			case LawFx.GAMMA -> {
				Entity target = entity(mc, e.targetId);
				if (target == null || t < 0.12) return;
				Box box = lerpBox(target);
				Vec3d c = box.getCenter();
				double u = (t - 0.12) / 0.88;
				float a = (float) (Math.sin(Math.min(1.0, u * 1.6) * Math.PI * 0.5) * (1.0 - u * u));
				double beat = 0.5 + 0.5 * Math.sin(now * 18.0);
				// los órganos: un núcleo verde que late y se vuelve amarillo al destruirse
				int col = lerpRgb(GAMMA_G, GAMMA_Y, (float) u);
				disc(b, c, (0.22 + 0.16 * beat) * Math.min(1.6, box.getLengthY() * 0.5), col, a * 0.85f, 0f);
				disc(b, c, 0.10 + 0.05 * beat, CORE, a, 0.2f * a);
				// la silueta del cuerpo en verde, como una radiografía
				boxEdges(b, box.expand(0.02), GAMMA_G, a * 0.45f, 0.022);
				// ondas que convergen hacia dentro (radiación concentrada)
				for (int k = 0; k < 3; k++) {
					double ph = ((u * 2.2 + k / 3.0) % 1.0);
					double rr = (1.0 - ph) * box.getLengthY() * 0.65 + 0.08;
					faceCam(c);
					ring(b, c, rr * 0.86, rr, col, a * 0.55f * (float) ph, 0f);
				}
			}
			case LawFx.SHOCK_WILLE -> {
				Entity target = entity(mc, e.targetId);
				if (target == null || t < 0.22 || t > 0.75) return;
				double u = (t - 0.22) / 0.53;
				Box box = lerpBox(target);
				Vec3d c = box.getCenter();
				float a = (float) Math.sin(u * Math.PI);
				disc(b, c, box.getLengthY() * (0.35 + 0.5 * u), ELEC_HOT, a * 0.9f, 0f);
				boxEdges(b, box.expand(0.03), ELEC, a * 0.6f, 0.026);
				if (!calm) {
					int flick = (int) (now * 30);
					for (int k = 0; k < 5; k++) {
						Vec3d q = pointOnBox(box, e.seed + k * 97 + flick * 13);
						bolt(b, c, q, 0.06, 0.02, ELEC, ELEC_HOT, a, e.seed + k * 31 + flick, 6, 0.18);
					}
				}
			}
			case LawFx.ANESTHESIA -> {
				Entity target = entity(mc, e.targetId);
				if (target == null) return;
				Box box = lerpBox(target);
				float a = (float) (0.5 * (1.0 - t) + 0.25);
				boxEdges(b, box.expand(0.04), ROOM, a * 0.55f, 0.02);
				Vec3d c = box.getCenter();
				disc(b, c, 0.18 + 0.06 * Math.sin(now * 9.0), CORE, a * 0.6f, 0f);
			}
			default -> {}
		}
	}

	// ------------------------------------------------------------------ cada técnica

	/** Radio Knife: tres tajos cruzados y electrificados en el sitio, con la descarga saltando por las costuras. */
	private static void radioKnife(BufferBuilder b, Ev e, double t, double now, boolean calm) {
		Vec3d dir = e.b.subtract(e.a);
		if (dir.lengthSquared() < 1e-4) dir = new Vec3d(0, 0, 1);
		dir = dir.normalize();
		Vec3d right = dir.crossProduct(new Vec3d(0, 1, 0));
		if (right.lengthSquared() < 1e-4) right = new Vec3d(1, 0, 0);
		right = right.normalize();
		Vec3d up = right.crossProduct(dir).normalize();
		double r = Math.max(2.5, e.radius);
		Vec3d[] normals = {up, up.add(right).normalize(), up.subtract(right).normalize()};
		// 1) el arco de la hoja desde la mano (los primeros instantes)
		if (t < 0.3) {
			float a = (float) (1.0 - t / 0.3);
			ribbon(b, e.a, e.b, 0.10, 0.22, ELEC, a * 0.6f, a * 0.25f);
			ribbon(b, e.a, e.b, 0.03, 0.06, ELEC_HOT, a, a * 0.6f);
		}
		// 2) tres hojas de corte que barren su plano, una tras otra
		int flick = (int) (now * 26);
		for (int i = 0; i < 3; i++) {
			double local = MathHelper.clamp((t - i * 0.07) / 0.55, 0.0, 1.0);
			if (local <= 0 || local >= 1) continue;
			Vec3d n = normals[i];
			Vec3d s = n.crossProduct(dir).normalize();   // la dirección del tajo dentro de su plano
			double sweep = Math.min(1.0, local * 3.2);
			float a = (float) ((1.0 - local) * (1.0 - local));
			Vec3d p0 = e.b.subtract(s.multiply(r * sweep)), p1 = e.b.add(s.multiply(r * sweep));
			// la hoja de luz: un abanico fino en el plano del corte
			fan(b, e.b, s, dir, r * sweep, 0.55, ROOM, a * 0.22f);
			// la costura brillante
			ribbon(b, p0, p1, 0.16 * (1.0 - local * 0.6), 0.16 * (1.0 - local * 0.6), ELEC, a * 0.75f, a * 0.75f);
			ribbon(b, p0, p1, 0.045, 0.045, ELEC_HOT, a, a);
			// la descarga saltando por la costura
			if (!calm && local < 0.7) {
				bolt(b, p0, p1, 0.09, 0.03, ELEC, ELEC_HOT, a, e.seed + i * 53 + flick * 7, 12, 0.28);
			}
		}
		// 3) chispazos radiales y anillo de choque
		double wave = r * (0.3 + t * 1.2);
		float wa = (float) (0.85 * (1.0 - t) * (1.0 - t));
		axes(dir);
		ring(b, e.b, wave * 0.82, wave, ELEC, wa, 0f);
		ring(b, e.b, wave * 0.6, wave * 0.66, ELEC_HOT, wa * 0.6f, 0f);
		if (!calm && t < 0.5) {
			for (int k = 0; k < 7; k++) {
				Vec3d d = randDir(e.seed + k * 211 + flick);
				bolt(b, e.b, e.b.add(d.multiply(r * (0.5 + 0.5 * hash01(e.seed + k)))), 0.07, 0.022, ELEC, ELEC_HOT,
					(float) (1.0 - t / 0.5), e.seed + k * 7 + flick * 3, 7, 0.35);
			}
		}
	}

	/** Injection Shot: la retícula sobre el objetivo y la punta de lanza de luz delante de Law mientras embiste. */
	private static void injectionDash(BufferBuilder b, MinecraftClient mc, Ev e, double t, double now, boolean calm) {
		Entity user = entity(mc, e.userId);
		Vec3d dir = e.b.subtract(e.a);
		if (dir.lengthSquared() < 1e-4) dir = new Vec3d(0, 0, 1);
		dir = dir.normalize();
		float a = (float) (1.0 - t * t);
		if (user != null) {
			Vec3d tip0 = lerpPos(user).add(0, user.getHeight() * 0.62, 0).add(dir.multiply(0.6));
			Vec3d tip = tip0.add(dir.multiply(2.6));
			// la «aguja»: anillos que se estrechan hacia la punta y giran como un taladro
			axes(dir);
			for (int i = 0; i < 6; i++) {
				double u = i / 5.0;
				Vec3d c = tip0.add(dir.multiply(2.6 * u));
				double rr = 0.55 * (1.0 - u) + 0.05;
				ring(b, c, rr * 0.7, rr, ROOM, a * 0.55f * (float) (1.0 - u * 0.5), 0f);
			}
			ribbon(b, tip0, tip, 0.30, 0.02, ROOM, a * 0.6f, a);
			ribbon(b, tip0, tip, 0.09, 0.01, CORE, a, a);
			// líneas de velocidad detrás
			if (!calm) {
				for (int k = 0; k < 6; k++) {
					double ang = k * Math.PI / 3 + now * 6;
					Vec3d off = new Vec3d(u1x * Math.cos(ang) + u2x * Math.sin(ang), u1y * Math.cos(ang) + u2y * Math.sin(ang),
						u1z * Math.cos(ang) + u2z * Math.sin(ang)).multiply(0.6);
					Vec3d s0 = tip0.add(off), s1 = s0.subtract(dir.multiply(2.5 + hash01(e.seed + k) * 2));
					ribbon(b, s0, s1, 0.03, 0.0, CORE, a * 0.7f, 0f);
				}
			}
		}
		// la retícula sobre el objetivo (como la del Scan)
		Entity target = entity(mc, e.targetId);
		if (target != null) reticle(b, lerpBox(target).getCenter(), Math.max(0.7, target.getHeight() * 0.6), now, a);
	}

	/** Injection Shot: al clavarse la hoja dispara: fogonazo hacia delante y anillos de choque. */
	private static void injectionHit(BufferBuilder b, Ev e, double t, double now, boolean calm) {
		Vec3d dir = e.b.subtract(e.a);
		if (dir.lengthSquared() < 1e-4) dir = new Vec3d(0, 0, 1);
		dir = dir.normalize();
		float a = (float) ((1.0 - t) * (1.0 - t));
		// fogonazo
		if (t < 0.25) {
			float f = (float) (1.0 - t / 0.25);
			disc(b, e.a, 1.4 * f + 0.3, CORE, f, 0f);
			axes(dir);
			for (int k = 0; k < 10; k++) {
				double ang = k * Math.PI * 2 / 10 + e.seed;
				Vec3d side = new Vec3d(u1x * Math.cos(ang) + u2x * Math.sin(ang), u1y * Math.cos(ang) + u2y * Math.sin(ang),
					u1z * Math.cos(ang) + u2z * Math.sin(ang));
				Vec3d tip = e.a.add(dir.multiply(3.5 + 1.5 * hash01(e.seed + k))).add(side.multiply(0.9));
				ribbon(b, e.a, tip, 0.14, 0.0, ROOM, f * 0.9f, 0f);
			}
		}
		// anillos de choque que se abren a lo largo del disparo
		axes(dir);
		for (int i = 0; i < 4; i++) {
			double local = MathHelper.clamp(t * 1.4 - i * 0.12, 0, 1);
			if (local <= 0 || local >= 1) continue;
			Vec3d c = e.a.add(dir.multiply(0.8 + i * 1.3 + local * 2.0));
			double rr = 0.5 + local * (1.6 + i * 0.5);
			float ra = (float) ((1.0 - local) * 0.9);
			ring(b, c, rr * 0.78, rr, ROOM, ra, 0f);
			ring(b, c, rr * 0.9, rr * 0.95, CORE, ra, 0f);
		}
		// la onda horizontal en el sitio
		axes(new Vec3d(0, 1, 0));
		double wave = 0.6 + t * 4.5;
		ring(b, e.a, wave * 0.85, wave, ELEC, a * 0.6f, 0f);
	}

	/** Gamma Knife: una hoja corta de energía (verde que vira a amarillo) sale de la mano y se hunde en el cuerpo. */
	private static void gamma(BufferBuilder b, MinecraftClient mc, Ev e, double t, double now) {
		Entity target = entity(mc, e.targetId);
		Entity user = entity(mc, e.userId);
		Vec3d to = target != null ? lerpBox(target).getCenter() : e.b;
		Vec3d from = user != null ? handOf(user) : e.a;
		float a = t < 0.65 ? 1f : (float) (1.0 - (t - 0.65) / 0.35);
		double grow = Math.min(1.0, t / 0.12);
		Vec3d d = to.subtract(from);
		Vec3d tip = from.add(d.multiply(grow * 1.08));
		int col = lerpRgb(GAMMA_G, GAMMA_Y, (float) Math.min(1.0, t * 1.4));
		// la hoja: ancha en la mano, fina en la punta, con un filo blanco
		ribbon(b, from, tip, 0.20, 0.03, col, a * 0.55f, a * 0.8f);
		ribbon(b, from, tip, 0.06, 0.012, CORE, a, a);
		// el resplandor en la mano
		disc(b, from, 0.28 + 0.06 * Math.sin(now * 20), col, a * 0.7f, 0f);
		// la entrada: anillo pequeño en la piel, sin herida
		if (t > 0.1) {
			Vec3d dn = d.lengthSquared() > 1e-4 ? d.normalize() : new Vec3d(0, 0, 1);
			axes(dn);
			double rr = 0.25 + 0.35 * Math.min(1.0, (t - 0.1) * 4);
			Vec3d entry = to.subtract(dn.multiply(target != null ? target.getWidth() * 0.5 : 0.3));
			ring(b, entry, rr * 0.7, rr, col, a * 0.8f, 0f);
		}
	}

	/** Curtain: el velo quirúrgico que Law tira del aire con el brazo; ondula delante de él mientras dura. */
	private static void curtain(BufferBuilder b, MinecraftClient mc, Ev e, double t, double age, double now, boolean calm) {
		Entity user = entity(mc, e.userId);
		if (user == null) return;
		float yaw = user.getYaw(td);
		double rad = Math.toRadians(yaw);
		Vec3d fwd = new Vec3d(-Math.sin(rad), 0, Math.cos(rad));
		Vec3d side = new Vec3d(-fwd.z, 0, fwd.x);
		Vec3d base = lerpPos(user).add(fwd.multiply(1.35));
		double unfurl = Math.min(1.0, age / 0.38);
		double out = e.duration - age < 0.5 ? (e.duration - age) / 0.5 : 1.0;
		float a = (float) (Math.max(0, out));
		double width = 2.8, height = 2.5;
		int cols = 16, rows = 8;
		double half = width * 0.5;
		double startX = -half + width * (1.0 - unfurl);   // se despliega desde el lado del brazo
		for (int i = 0; i < cols; i++) {
			double x0 = -half + width * i / cols, x1 = -half + width * (i + 1) / cols;
			if (x1 < startX) continue;
			x0 = Math.max(x0, startX);
			for (int j = 0; j < rows; j++) {
				double y0 = height * j / rows, y1 = height * (j + 1) / rows;
				Vec3d p00 = drape(base, fwd, side, x0, y0, now, calm);
				Vec3d p10 = drape(base, fwd, side, x1, y0, now, calm);
				Vec3d p11 = drape(base, fwd, side, x1, y1, now, calm);
				Vec3d p01 = drape(base, fwd, side, x0, y1, now, calm);
				double fold = 0.5 + 0.5 * Math.sin((x0 + x1) * 3.4 + now * (calm ? 0 : 1.7));
				float fa = (float) ((0.13 + 0.16 * fold) * a);
				float fb = fa * (float) (0.55 + 0.45 * j / rows);   // más denso arriba, como un paño colgado
				quad(b, p00, p10, p11, p01, DRAPE, fb, fb, fa, fa);
			}
		}
		// las crestas de los pliegues, verticales y brillantes
		for (int i = 1; i < cols; i += 2) {
			double x = -half + width * i / cols;
			if (x < startX) continue;
			Vec3d p0 = drape(base, fwd, side, x, 0.1, now, calm), p1 = drape(base, fwd, side, x, height, now, calm);
			ribbon(b, p0, p1, 0.02, 0.02, CORE, a * 0.25f, a * 0.45f);
		}
		// el dobladillo y los bordes, que brillan más
		Vec3d prevTop = null, prevBot = null;
		for (int i = 0; i <= cols; i++) {
			double x = Math.max(startX, -half + width * i / cols);
			Vec3d top = drape(base, fwd, side, x, height, now, calm), bot = drape(base, fwd, side, x, 0.05, now, calm);
			if (prevTop != null) {
				ribbon(b, prevTop, top, 0.035, 0.035, CORE, a * 0.8f, a * 0.8f);
				ribbon(b, prevBot, bot, 0.025, 0.025, ROOM, a * 0.6f, a * 0.6f);
			}
			prevTop = top;
			prevBot = bot;
		}
		for (double x : new double[]{startX, half}) {
			Vec3d p0 = drape(base, fwd, side, x, 0.05, now, calm), p1 = drape(base, fwd, side, x, height, now, calm);
			ribbon(b, p0, p1, 0.03, 0.03, CORE, a * 0.75f, a * 0.75f);
		}
		// la cruz quirúrgica en el centro del paño, tenue
		Vec3d cc = drape(base, fwd, side, 0, height * 0.55, now, calm);
		Vec3d hx = side.multiply(0.32), hy = new Vec3d(0, 0.32, 0);
		ribbon(b, cc.subtract(hx), cc.add(hx), 0.05, 0.05, ROOM, a * 0.35f, a * 0.35f);
		ribbon(b, cc.subtract(hy), cc.add(hy), 0.05, 0.05, ROOM, a * 0.35f, a * 0.35f);
	}

	private static Vec3d drape(Vec3d base, Vec3d fwd, Vec3d side, double x, double y, double now, boolean calm) {
		double wave = calm ? 0.0 : Math.sin(x * 3.4 + now * 1.7) * 0.10 + Math.sin(y * 2.1 + now * 2.3 + x) * 0.05;
		double bow = -0.18 * (x * x);   // un poco curvado alrededor de Law
		return base.add(side.multiply(x)).add(fwd.multiply(wave + bow)).add(0, y, 0);
	}

	/** Mes: la palma golpea el pecho, el corazón sale en un cubo de cristal y vuela a la mano de Law. */
	private static void mes(BufferBuilder b, MinecraftClient mc, Ev e, double t, double now) {
		Entity target = entity(mc, e.targetId);
		Entity user = entity(mc, e.userId);
		Vec3d chest = target != null ? lerpPos(target).add(0, target.getHeight() * 0.66, 0) : e.b;
		Vec3d hand = user != null ? handOf(user) : e.a;
		// 1) la onda del golpe de palma
		if (t < 0.35) {
			double u = t / 0.35;
			float a = (float) (1.0 - u);
			Vec3d dn = chest.subtract(hand);
			axes(dn.lengthSquared() > 1e-4 ? dn : new Vec3d(0, 0, 1));
			ring(b, chest, 0.2 + u * 1.2, 0.3 + u * 1.4, ROOM, a * 0.8f, 0f);
			ring(b, chest, 0.1 + u * 0.6, 0.15 + u * 0.7, CORE, a, 0f);
		}
		// 2) el hueco cúbico en el pecho
		if (t < 0.8) {
			float a = (float) (t < 0.1 ? t / 0.1 : 1.0 - (t - 0.1) / 0.7);
			cubeEdges(b, chest, 0.24, ROOM, a * 0.9f, 0.025, 0);
		}
		// 3) el cubo de cristal con el corazón dentro, en arco hasta la mano
		if (t > 0.15) {
			double u = MathHelper.clamp((t - 0.15) / 0.55, 0, 1);
			double ease = u * u * (3 - 2 * u);
			Vec3d mid = chest.lerp(hand, 0.5).add(0, 0.9, 0);
			Vec3d pos = bezier(chest, mid, hand, ease);
			float a = (float) (u < 0.95 ? 1.0 : (1.0 - u) / 0.05) * (t > 0.85 ? (float) ((1.0 - t) / 0.15) : 1f);
			double spin = now * 3.0;
			cubeFaces(b, pos, 0.22, ROOM, a * 0.12f, spin);
			cubeEdges(b, pos, 0.22, CORE, a * 0.85f, 0.018, spin);
			double beat = Math.pow(Math.max(0, Math.sin(now * 7.5)), 6);
			disc(b, pos, 0.09 + 0.05 * beat, HEART, a * 0.95f, 0f);
			disc(b, pos, 0.2 + 0.08 * beat, HEART, a * 0.35f, 0f);
			// la estela
			for (int k = 1; k <= 6; k++) {
				double uk = Math.max(0, ease - k * 0.04);
				Vec3d q = bezier(chest, mid, hand, uk);
				disc(b, q, 0.12 * (1 - k / 7.0), ROOM, a * 0.3f * (1f - k / 7f), 0f);
			}
		}
	}

	/** Counter Shock: dos palas de desfibrilador en las palmas, la descarga salta al cuerpo y lo recorre entero. */
	private static void counterShock(BufferBuilder b, MinecraftClient mc, Ev e, double t, double now, boolean calm) {
		Entity user = entity(mc, e.userId);
		Entity target = entity(mc, e.targetId);
		Vec3d look = e.b.subtract(e.a);
		if (look.lengthSquared() < 1e-4) look = new Vec3d(0, 0, 1);
		look = look.normalize();
		Vec3d center = user != null ? lerpPos(user).add(0, user.getHeight() * 0.62, 0).add(look.multiply(0.7)) : e.a;
		Vec3d right = look.crossProduct(new Vec3d(0, 1, 0));
		if (right.lengthSquared() < 1e-4) right = new Vec3d(1, 0, 0);
		right = right.normalize();
		Vec3d padL = center.subtract(right.multiply(0.28)), padR = center.add(right.multiply(0.28));
		float a = (float) (t < 0.6 ? 1.0 : (1.0 - t) / 0.4);
		int flick = calm ? 0 : (int) (now * 28);
		// las palas en las manos
		axes(look);
		for (Vec3d pad : new Vec3d[]{padL, padR}) {
			ring(b, pad, 0.10, 0.20, ELEC, a * 0.9f, a * 0.2f);
			disc(b, pad, 0.16 + 0.05 * Math.sin(now * 40), ELEC_HOT, a, 0f);
		}
		Box box = target != null ? lerpBox(target) : null;
		Vec3d tc = box != null ? box.getCenter() : e.b;
		// la descarga de las palas al cuerpo
		if (t < 0.55) {
			for (int k = 0; k < 3; k++) {
				bolt(b, padL, tc, 0.08, 0.025, ELEC, ELEC_HOT, a, e.seed + k * 17 + flick * 5, 9, 0.22);
				bolt(b, padR, tc, 0.08, 0.025, ELEC, ELEC_HOT, a, e.seed + k * 29 + flick * 3, 9, 0.22);
			}
		}
		// el cuerpo se ilumina y la descarga lo recorre por fuera
		if (box != null) {
			boxEdges(b, box.expand(0.04), ELEC, a * 0.5f, 0.02);
			if (!calm) {
				for (int k = 0; k < 6; k++) {
					Vec3d p0 = pointOnBox(box, e.seed + k * 13 + flick * 7), p1 = pointOnBox(box, e.seed + k * 41 + flick * 11);
					bolt(b, p0, p1, 0.06, 0.02, ELEC, ELEC_HOT, a * 0.9f, e.seed + k + flick, 6, 0.15);
				}
			}
		}
		// el doble latido «ba-dum»: dos anillos que se abren desde el pecho
		axes(new Vec3d(0, 1, 0));
		for (int i = 0; i < 2; i++) {
			double u = MathHelper.clamp((t - i * 0.18) / 0.5, 0, 1);
			if (u <= 0 || u >= 1) continue;
			double rr = 0.4 + u * 3.2;
			ring(b, tc, rr * 0.86, rr, ELEC, (float) (1 - u) * 0.85f, 0f);
		}
	}

	/** Scan: un plano de resonancia recorre el ROOM de abajo arriba, y un meridiano gira alrededor. */
	private static void scan(BufferBuilder b, Ev e, double t, double now) {
		double r = Math.max(3.0, e.radius);
		Vec3d c = e.a;
		float a = (float) (t < 0.85 ? 1.0 : (1.0 - t) / 0.15);
		double sweep = MathHelper.clamp(t / 0.85, 0, 1);
		double h = -r + 2 * r * sweep;
		double ringR = Math.sqrt(Math.max(0.0, r * r - h * h));
		Vec3d sc = c.add(0, h, 0);
		axes(new Vec3d(0, 1, 0));
		if (ringR > 0.05) {
			double edge = Math.max(0.12, ringR * 0.03);
			ring(b, sc, ringR - edge, ringR, CORE, a, a * 0.4f);
			ring(b, sc, ringR * 0.72, ringR - edge, ROOM, a * 0.12f, a * 0.6f);
			ring(b, sc.add(0, -0.6, 0), ringR * 0.9, ringR * 1.0, ROOM, 0f, a * 0.3f);   // la estela del barrido
			// una pared de luz baja alrededor del anillo: el plano del escáner se ve también de canto
			for (int i = 0; i < SEG; i++) {
				Vec3d p0 = sc.add(COS[i] * ringR, 0, SIN[i] * ringR), p1 = sc.add(COS[i + 1] * ringR, 0, SIN[i + 1] * ringR);
				quad(b, p0.add(0, -1.1, 0), p1.add(0, -1.1, 0), p1, p0, ROOM, 0f, 0f, a * 0.55f, a * 0.55f);
				quad(b, p0, p1, p1.add(0, 0.25, 0), p0.add(0, 0.25, 0), CORE, a * 0.7f, a * 0.7f, 0f, 0f);
			}
			// la rejilla del escáner dentro del disco
			for (int k = 1; k <= 4; k++) {
				double rk = ringR * k / 5.0;
				ring(b, sc, rk - 0.06, rk, ROOM, a * 0.5f, a * 0.5f);
			}
			for (int k = 0; k < 6; k++) {
				double ang = k * Math.PI / 6 + now * 0.4;
				Vec3d d = new Vec3d(Math.cos(ang), 0, Math.sin(ang)).multiply(ringR);
				ribbon(b, sc.subtract(d), sc.add(d), 0.04, 0.04, ROOM, a * 0.45f, a * 0.45f);
			}
		}
		// el meridiano que gira
		double ang = now * 2.2;
		axes(new Vec3d(Math.cos(ang), 0, Math.sin(ang)));
		ring(b, c, r * 0.985, r, ROOM, a * 0.6f, 0f);
	}

	/** Cambio de almas: dos almas (rosa y celeste) salen de cada cuerpo y se cruzan en arco hasta el otro. */
	private static void souls(BufferBuilder b, Ev e, double t, double now) {
		Vec3d pa = e.a, pb = e.b;
		Vec3d mid = pa.lerp(pb, 0.5);
		Vec3d d = pb.subtract(pa);
		Vec3d side = d.crossProduct(new Vec3d(0, 1, 0));
		if (side.lengthSquared() < 1e-4) side = new Vec3d(1, 0, 0);
		side = side.normalize().multiply(Math.max(1.0, d.length() * 0.35));
		Vec3d ctrlA = mid.add(side).add(0, 1.6, 0), ctrlB = mid.subtract(side).add(0, 1.0, 0);
		double u = MathHelper.clamp(t / 0.8, 0, 1);
		double ease = u * u * (3 - 2 * u);
		float a = (float) (t < 0.85 ? 1.0 : (1.0 - t) / 0.15);
		// el hilo que une a los dos
		ribbon(b, pa, pb, 0.02, 0.02, GOLD, a * 0.35f, a * 0.35f);
		for (int s = 0; s < 2; s++) {
			Vec3d from = s == 0 ? pa : pb, to = s == 0 ? pb : pa, ctrl = s == 0 ? ctrlA : ctrlB;
			int col = s == 0 ? SOUL_A : SOUL_B;
			Vec3d pos = bezier(from, ctrl, to, ease);
			disc(b, pos, 0.32 + 0.06 * Math.sin(now * 12 + s), col, a * 0.85f, 0f);
			disc(b, pos, 0.12, CORE, a, 0f);
			Vec3d prev = pos;
			for (int k = 1; k <= 10; k++) {
				Vec3d q = bezier(from, ctrl, to, Math.max(0, ease - k * 0.035));
				ribbon(b, prev, q, 0.16 * (1 - k / 11.0), 0.14 * (1 - k / 11.0), col, a * 0.6f * (1f - k / 11f), a * 0.5f * (1f - k / 11f));
				prev = q;
			}
		}
		// al llegar: un destello en cada cuerpo
		if (t > 0.7) {
			double v = (t - 0.7) / 0.3;
			axes(new Vec3d(0, 1, 0));
			ring(b, pa, 0.3 + v * 1.5, 0.45 + v * 1.7, SOUL_B, (float) (1 - v), 0f);
			ring(b, pb, 0.3 + v * 1.5, 0.45 + v * 1.7, SOUL_A, (float) (1 - v), 0f);
		}
	}

	/** Lluvia de cortes: un tajo cae del cielo sobre el ser y lo cruza. */
	private static void rainSlash(BufferBuilder b, Ev e, double t) {
		Vec3d c = e.a;
		Vec3d top = c.add(0, 7, 0);
		if (t < 0.3) {
			double u = t / 0.3;
			Vec3d head = top.lerp(c, u);
			ribbon(b, top, head, 0.01, 0.10, ROOM, 0.2f, 0.9f);
			ribbon(b, top, head, 0.0, 0.035, CORE, 0.3f, 1f);
		} else {
			double u = (t - 0.3) / 0.7;
			float a = (float) (1 - u);
			double s = 0.6 + 0.4 * u;
			double ang = e.seed;
			Vec3d d1 = new Vec3d(Math.cos(ang), 0.7, Math.sin(ang)).normalize().multiply(s);
			Vec3d d2 = new Vec3d(-Math.sin(ang), -0.5, Math.cos(ang)).normalize().multiply(s);
			ribbon(b, c.subtract(d1), c.add(d1), 0.05, 0.05, CORE, a, a);
			ribbon(b, c.subtract(d2), c.add(d2), 0.05, 0.05, CORE, a, a);
			disc(b, c, 0.35 * (1 - u), ROOM, a * 0.7f, 0f);
		}
	}

	/** Despertar: columnas de luz celeste y dorada suben en espiral alrededor de Law (o se apagan hacia dentro). */
	private static void awaken(BufferBuilder b, MinecraftClient mc, Ev e, double t, double now, boolean calm) {
		Entity user = entity(mc, e.userId);
		Vec3d feet = user != null ? lerpPos(user) : e.a;
		boolean on = e.power > 0.5f;
		float a = (float) (1.0 - t) * (on ? 1f : 0.8f);
		double grow = on ? t : 1 - t;
		axes(new Vec3d(0, 1, 0));
		// anillos en el suelo
		for (int i = 0; i < 3; i++) {
			double u = MathHelper.clamp(on ? t * 1.3 - i * 0.12 : 1 - (t * 1.3 - i * 0.12), 0, 1);
			double rr = 0.6 + u * 4.5;
			ring(b, feet.add(0, 0.05 + i * 0.02, 0), rr * 0.9, rr, i == 1 ? GOLD : ROOM, a * 0.8f, 0f);
		}
		// columnas en espiral
		int n = calm ? 4 : 8;
		for (int k = 0; k < n; k++) {
			double ang = k * Math.PI * 2 / n + now * (calm ? 0 : 1.6);
			double rr = 1.1 + 0.4 * Math.sin(now * 2 + k);
			Vec3d p0 = feet.add(Math.cos(ang) * rr, 0, Math.sin(ang) * rr);
			Vec3d p1 = feet.add(Math.cos(ang + 0.8) * rr * 0.6, 2.2 + 3.5 * grow, Math.sin(ang + 0.8) * rr * 0.6);
			ribbon(b, p0, p1, 0.07, 0.0, k % 2 == 0 ? ROOM : GOLD, a * 0.85f, 0f);
		}
		disc(b, feet.add(0, 1.0, 0), 1.2 + 1.5 * grow, on ? GOLD : ROOM, a * 0.25f, 0f);
	}

	/** K-ROOM: una esfera de ROOM diminuta se forma en la palma y se desliza recubriendo la Kikoku. */
	private static void kroom(BufferBuilder b, MinecraftClient mc, Ev e, double t, double now) {
		Entity user = entity(mc, e.userId);
		Vec3d hand = user != null ? handOf(user) : e.a;
		Vec3d dir = user != null ? user.getRotationVec(td) : e.b.subtract(e.a).normalize();
		float a = (float) (t < 0.8 ? 1.0 : (1.0 - t) / 0.2);
		if (t < 0.45) {
			// la esfera giroscópica en la palma
			double u = t / 0.45;
			double rr = 0.12 + 0.28 * Math.sin(u * Math.PI * 0.5);
			for (int i = 0; i < 3; i++) {
				double ang = now * (2.5 + i) + i;
				axes(new Vec3d(Math.cos(ang), Math.sin(ang * 0.7), Math.sin(ang)));
				ring(b, hand, rr * 0.9, rr, i == 0 ? CORE : ROOM, a, a * 0.3f);
			}
			disc(b, hand, rr * 1.4, ROOM, a * 0.35f, 0f);
		}
		// los anillos que recorren la hoja hasta la punta (26 m)
		if (t > 0.3) {
			double u = (t - 0.3) / 0.7;
			axes(dir);
			for (int i = 0; i < 5; i++) {
				double v = MathHelper.clamp(u * 1.4 - i * 0.09, 0, 1);
				if (v <= 0 || v >= 1) continue;
				Vec3d c = hand.add(dir.multiply(1.0 + v * 25.0));
				double rr = 0.45 + 0.25 * Math.sin(v * Math.PI);
				ring(b, c, rr * 0.75, rr, ROOM, a * (float) (1 - v) * 0.9f, 0f);
			}
		}
	}

	/** Anesthesia: la hoja de K-ROOM atraviesa al objetivo sin cortarlo; ondas de fase donde entra. */
	private static void anesthesia(BufferBuilder b, MinecraftClient mc, Ev e, double t, double now) {
		Entity user = entity(mc, e.userId);
		Entity target = entity(mc, e.targetId);
		if (user == null || target == null) return;
		Vec3d hand = handOf(user);
		Vec3d tc = lerpBox(target).getCenter();
		Vec3d d = tc.subtract(hand);
		if (d.lengthSquared() < 1e-4) return;
		Vec3d dn = d.normalize();
		Vec3d past = tc.add(dn.multiply(3.0));   // la hoja sigue por detrás: lo ha atravesado
		float a = (float) (t < 0.85 ? 1.0 : (1.0 - t) / 0.15);
		ribbon(b, hand, past, 0.16, 0.05, ROOM, a * 0.4f, a * 0.25f);
		ribbon(b, hand, past, 0.04, 0.015, CORE, a * 0.9f, a * 0.7f);
		axes(dn);
		for (int i = 0; i < 3; i++) {
			double ph = ((now * 1.4 + i / 3.0) % 1.0);
			double rr = 0.2 + ph * Math.max(0.8, target.getWidth() * 1.2);
			Vec3d entry = tc.subtract(dn.multiply(target.getWidth() * 0.5));
			ring(b, entry, rr * 0.85, rr, ROOM, a * (float) (1 - ph) * 0.8f, 0f);
		}
		// pulsos de fase que viajan por la hoja
		for (int i = 0; i < 4; i++) {
			double ph = ((now * 0.9 + i / 4.0) % 1.0);
			Vec3d q = hand.lerp(past, ph);
			ring(b, q, 0.10, 0.22, CORE, a * 0.7f, 0f);
		}
	}

	/** Shock Wille: la hoja atraviesa, un pulso viaja por ella y estalla dentro del cuerpo como una onda. */
	private static void shockWille(BufferBuilder b, Ev e, double t, double now, boolean calm) {
		Vec3d a0 = e.a, p = e.b;
		Vec3d d = p.subtract(a0);
		if (d.lengthSquared() < 1e-4) d = new Vec3d(0, 0, 1);
		Vec3d dn = d.normalize();
		double r = Math.max(3.0, e.radius);
		int flick = calm ? 0 : (int) (now * 24);
		// 1) la hoja
		if (t < 0.55) {
			float a = (float) (t < 0.4 ? 1.0 : (0.55 - t) / 0.15);
			ribbon(b, a0, p.add(dn.multiply(2.5)), 0.22, 0.10, ROOM, a * 0.5f, a * 0.3f);
			ribbon(b, a0, p.add(dn.multiply(2.5)), 0.06, 0.03, CORE, a, a * 0.8f);
		}
		// 2) el pulso que viaja por la hoja
		if (t > 0.05 && t < 0.3) {
			double u = (t - 0.05) / 0.25;
			Vec3d q = a0.lerp(p, u * u);
			disc(b, q, 0.6, ELEC_HOT, 1f, 0f);
			axes(dn);
			ring(b, q, 0.35, 0.6, ELEC, 0.9f, 0f);
		}
		// 3) la detonación interna: núcleo, esfera que se abre, rayos y disco de choque
		if (t > 0.25) {
			double u = (t - 0.25) / 0.75;
			float a = (float) ((1 - u) * (1 - u));
			disc(b, p, 2.2 * (1 - u) + 0.4, ELEC_HOT, a, 0f);
			double sr = 0.8 + u * r * 1.4;
			for (int i = 0; i < 3; i++) {
				axes(i == 0 ? new Vec3d(0, 1, 0) : i == 1 ? dn : dn.crossProduct(new Vec3d(0, 1, 0)).add(1e-3, 0, 0));
				ring(b, p, sr * 0.93, sr, i == 0 ? ELEC : ROOM, a * 0.85f, 0f);
			}
			axes(dn);
			double wr = 1.0 + u * r * 2.4;
			ring(b, p, wr * 0.82, wr, ROOM, a * 0.75f, 0f);
			ring(b, p, wr * 0.95, wr * 1.0, CORE, a, 0f);
			if (!calm && u < 0.6) {
				for (int k = 0; k < 12; k++) {
					Vec3d dd = randDir(e.seed + k * 37 + flick);
					bolt(b, p, p.add(dd.multiply(r * (0.6 + 0.6 * hash01(e.seed + k * 5)))), 0.10, 0.03, ELEC, ELEC_HOT,
						(float) (1 - u / 0.6), e.seed + k * 13 + flick, 8, 0.4);
				}
			}
		}
	}

	/** Puncture Wille: la Kikoku colosal se clava desde el cielo, implosiona bajo tierra y sube una columna de luz. */
	private static void puncture(BufferBuilder b, Ev e, double t, double now, boolean calm) {
		Vec3d c = e.b;
		double R = Math.max(4.0, e.radius);
		double depth = Math.max(6.0, e.power);
		Vec3d sky = c.add(0, 42, 0);
		int flick = calm ? 0 : (int) (now * 20);
		axes(new Vec3d(0, 1, 0));
		// 1) la hoja colosal baja del cielo y se hunde
		if (t < 0.55) {
			double u = MathHelper.clamp(t / 0.16, 0, 1);
			float a = (float) (t < 0.4 ? 1.0 : (0.55 - t) / 0.15);
			Vec3d tip = sky.lerp(c.add(0, -depth, 0), u);
			ribbon(b, sky, tip, 1.6, 0.25, ROOM, a * 0.55f, a * 0.8f);
			ribbon(b, sky, tip, 0.45, 0.08, CORE, a, a);
		}
		// 2) la implosión bajo tierra: anillos que se cierran a distintas profundidades
		if (t > 0.12 && t < 0.6) {
			double u = (t - 0.12) / 0.48;
			for (int i = 0; i < 6; i++) {
				double v = MathHelper.clamp(u * 1.5 - i * 0.08, 0, 1);
				if (v <= 0 || v >= 1) continue;
				Vec3d cc = c.add(0, -depth * (i + 1) / 6.0, 0);
				double rr = R * 1.5 * (1 - v) + 0.2;
				ring(b, cc, rr * 0.85, rr, ROOM, (float) Math.sin(v * Math.PI) * 0.9f, 0f);
				ring(b, cc, rr * 0.95, rr * 1.0, CORE, (float) Math.sin(v * Math.PI), 0f);
			}
		}
		// 3) la columna de luz, la cúpula de la onda y las grietas del suelo
		if (t > 0.35) {
			double u = (t - 0.35) / 0.65;
			float a = (float) ((1 - u) * (1 - u));
			double cw = R * 0.45 * (1 - u * 0.6);
			ribbon(b, c.add(0, -depth, 0), c.add(0, 34, 0), cw, cw * 0.4, ROOM, a * 0.55f, 0f);
			ribbon(b, c.add(0, -depth, 0), c.add(0, 30, 0), cw * 0.35, cw * 0.12, CORE, a, 0f);
			double wr = R * (0.6 + u * 1.8);
			ring(b, c.add(0, 0.2, 0), wr * 0.85, wr, ROOM, a, 0f);
			ring(b, c.add(0, 0.25, 0), wr * 0.96, wr * 1.01, CORE, a, 0f);
			// la cúpula: tres paralelos de una media esfera que se abre
			for (int k = 1; k <= 3; k++) {
				double lat = k * Math.PI / 8;
				double rr = wr * Math.cos(lat);
				ring(b, c.add(0, wr * Math.sin(lat), 0), rr * 0.97, rr, ROOM, a * 0.5f, 0f);
			}
			// grietas radiales
			for (int k = 0; k < 12; k++) {
				double ang = k * Math.PI / 6 + hash01(e.seed + k) * 0.4;
				Vec3d end = c.add(Math.cos(ang) * R * 1.6, 0.15, Math.sin(ang) * R * 1.6);
				bolt(b, c.add(0, 0.15, 0), end, 0.16, 0.05, ROOM, CORE, a * 0.9f, e.seed + k * 7, 10, 0.9);
			}
			if (!calm && u < 0.5) {
				for (int k = 0; k < 8; k++) {
					Vec3d dd = randDir(e.seed + k * 41 + flick);
					bolt(b, c, c.add(Math.abs(dd.x) * 0 + dd.x * R, Math.abs(dd.y) * R * 1.4, dd.z * R), 0.12, 0.04, ELEC, ELEC_HOT,
						(float) (1 - u / 0.5), e.seed + k * 3 + flick, 9, 0.6);
				}
			}
		}
	}

	/** Silent: una burbuja muda rodea a Law; los sonidos se encogen hacia dentro y desaparecen. */
	private static void silent(BufferBuilder b, MinecraftClient mc, Ev e, double t, double age, double now) {
		Entity user = entity(mc, e.userId);
		if (user == null) return;
		Vec3d c = lerpPos(user).add(0, user.getHeight() * 0.5, 0);
		double out = e.duration - age < 0.6 ? (e.duration - age) / 0.6 : 1.0;
		float a = (float) Math.max(0, Math.min(1.0, age / 0.4) * out);
		double rr = 2.3 + 0.12 * Math.sin(now * 1.6);
		// la burbuja: paralelos y meridianos tenues
		axes(new Vec3d(0, 1, 0));
		for (int k = -2; k <= 2; k++) {
			double lat = k * Math.PI / 6;
			double r2 = rr * Math.cos(lat);
			ring(b, c.add(0, rr * Math.sin(lat), 0), r2 * 0.985, r2, HUSH, a * 0.35f, 0f);
		}
		for (int k = 0; k < 3; k++) {
			double ang = k * Math.PI / 3 + now * 0.25;
			axes(new Vec3d(Math.cos(ang), 0, Math.sin(ang)));
			ring(b, c, rr * 0.985, rr, HUSH, a * 0.3f, 0f);
		}
		// ondas de sonido que se encogen hacia Law (cada 1,4 s)
		axes(new Vec3d(0, 1, 0));
		double ph = (age / 1.4) % 1.0;
		double sr = rr * 1.8 * (1 - ph) + 0.2;
		ring(b, c, sr * 0.9, sr, HUSH, a * 0.6f * (float) ph, 0f);
	}

	/** Los tajos de la Kikoku (estilo Espada): Iai, Luna de Acero, Torbellino y Mil Puntadas. */
	private static void sword(BufferBuilder b, Ev e, double t, double now) {
		int variant = Math.round(e.power);
		Vec3d d = e.b.subtract(e.a);
		if (d.lengthSquared() < 1e-4) d = new Vec3d(0, 0, 1);
		Vec3d dn = d.normalize();
		Vec3d right = dn.crossProduct(new Vec3d(0, 1, 0));
		if (right.lengthSquared() < 1e-4) right = new Vec3d(1, 0, 0);
		right = right.normalize();
		Vec3d up = right.crossProduct(dn).normalize();
		float a = (float) ((1 - t) * (1 - t));
		switch (variant) {
			case LawFx.SWORD_IAI -> {
				// la estela del paso relámpago y el corte diagonal que aparece después
				Vec3d p0 = e.a.add(0, 1.1, 0), p1 = e.b.add(0, 1.1, 0);
				ribbon(b, p0, p1, 0.02, 0.45, ROOM, a * 0.25f, a * 0.6f);
				ribbon(b, p0, p1, 0.005, 0.12, CORE, a * 0.5f, a);
				if (t > 0.12) {
					double u = MathHelper.clamp((t - 0.12) / 0.2, 0, 1);
					Vec3d m = p0.lerp(p1, 0.5);
					Vec3d s = up.add(right.multiply(0.35)).normalize().multiply(2.6 * u);
					ribbon(b, m.subtract(s), m.add(s), 0.09, 0.09, CORE, a, a);
					ribbon(b, m.subtract(s), m.add(s), 0.25, 0.25, ROOM, a * 0.5f, a * 0.5f);
				}
			}
			case LawFx.SWORD_MOON -> {
				// una media luna de luz que vuela hasta la mira
				double u = Math.min(1.0, t / 0.45);
				Vec3d c = e.a.lerp(e.b, u);
				crescent(b, c, up.add(right.multiply(0.25)).normalize(), dn, 2.6, 0.55, a);
			}
			case LawFx.SWORD_WHIRL -> {
				// dos medias lunas girando alrededor de Law a la vez que se abren
				double rr = 1.5 + t * Math.max(4.0, e.radius);
				for (int k = 0; k < 2; k++) {
					double spin = now * 9 + k * Math.PI;
					Vec3d tilt = k == 0 ? new Vec3d(0, 1, 0.18) : new Vec3d(0.18, 1, 0);
					arc(b, e.a.add(0, k * 0.6, 0), tilt.normalize(), spin, Math.PI * 1.1, rr, 0.35, a);
				}
			}
			case LawFx.SWORD_THRUSTS -> {
				// la red de mil puntadas: tres familias de cortes paralelos que cuadriculan en dados
				double r = Math.max(2.0, e.radius);
				for (int fam = 0; fam < 3; fam++) {
					Vec3d axis = fam == 0 ? up : fam == 1 ? right : dn;
					Vec3d along = fam == 0 ? right : fam == 1 ? up : right;
					for (int k = -2; k <= 2; k++) {
						double local = MathHelper.clamp(t * 3 - (fam * 5 + k + 2) * 0.06, 0, 1);
						if (local <= 0 || local >= 1) continue;
						Vec3d c = e.b.add(axis.multiply(k * r / 3));
						Vec3d s = along.multiply(r * Math.min(1, local * 3));
						float la = (float) (1 - local);
						ribbon(b, c.subtract(s), c.add(s), 0.05, 0.05, CORE, la, la);
						ribbon(b, c.subtract(s), c.add(s), 0.14, 0.14, ROOM, la * 0.4f, la * 0.4f);
					}
				}
			}
			default -> {}
		}
	}

	/** R-ROOM: una semilla de ROOM sale de la palma como un cometa hacia la mira. */
	private static void rroomCast(BufferBuilder b, Ev e, double t, double now) {
		double u = Math.min(1.0, t / 0.8);
		Vec3d pos = e.a.lerp(e.b, u * u * (3 - 2 * u));
		float a = (float) (t < 0.85 ? 1.0 : (1 - t) / 0.15);
		disc(b, pos, 0.45, ROOM, a * 0.7f, 0f);
		disc(b, pos, 0.18, CORE, a, 0f);
		for (int i = 0; i < 2; i++) {
			axes(new Vec3d(Math.cos(now * 5 + i), Math.sin(now * 4), Math.sin(now * 5 + i)));
			ring(b, pos, 0.34, 0.42, ROOM, a * 0.8f, 0f);
		}
		ribbon(b, e.a.lerp(e.b, Math.max(0, u - 0.25)), pos, 0.0, 0.3, ROOM, 0f, a * 0.6f);
	}

	/** R-ROOM al soltar: la esfera estalla en una cuadrícula de nueve cortes. */
	private static void rroomBurst(BufferBuilder b, Ev e, double t, double now) {
		Vec3d c = e.b;
		double r = Math.max(2.0, e.radius);
		float a = (float) ((1 - t) * (1 - t));
		double step = Math.max(2.5, r / 3);
		Vec3d[] axesV = {new Vec3d(1, 0, 0), new Vec3d(0, 1, 0), new Vec3d(0, 0, 1)};
		for (int ai = 0; ai < 3; ai++) {
			Vec3d n = axesV[ai];
			Vec3d s = axesV[(ai + 1) % 3];
			for (int k = -1; k <= 1; k++) {
				double off = k * step;
				if (Math.abs(off) >= r) continue;
				double half = Math.sqrt(r * r - off * off) * Math.min(1, t * 5);
				Vec3d cc = c.add(n.multiply(off));
				ribbon(b, cc.subtract(s.multiply(half)), cc.add(s.multiply(half)), 0.06, 0.06, CORE, a, a);
			}
		}
		disc(b, c, r * (0.2 + 0.5 * (1 - t)), ROOM, a * 0.5f, 0f);
		for (int i = 0; i < 3; i++) {
			axes(axesV[i]);
			double rr = r * (1 + t * 0.4);
			ring(b, c, rr * 0.95, rr, ROOM, a, 0f);
		}
	}

	/** Sterben: el brazo con la Kikoku sale girando; un disco de sierra en el sitio donde se lanza. */
	private static void sterben(BufferBuilder b, Ev e, double t, double now) {
		Vec3d d = e.b.subtract(e.a);
		if (d.lengthSquared() < 1e-4) d = new Vec3d(0, 0, 1);
		Vec3d dn = d.normalize();
		Vec3d right = dn.crossProduct(new Vec3d(0, 1, 0));
		if (right.lengthSquared() < 1e-4) right = new Vec3d(1, 0, 0);
		float a = (float) (1 - t);
		arc(b, e.a, right.normalize(), now * 14, Math.PI * 1.6, 1.4 + t * 1.5, 0.3, a);
	}

	/** Apretar el corazón: anillos rojos que se cierran sobre el pecho del dueño. */
	private static void heartSqueeze(BufferBuilder b, MinecraftClient mc, Ev e, double t, double now) {
		Entity target = entity(mc, e.targetId);
		Vec3d chest = target != null ? lerpPos(target).add(0, target.getHeight() * 0.66, 0) : e.b;
		faceCam(chest);
		for (int i = 0; i < 2; i++) {
			double u = MathHelper.clamp(t * 1.6 - i * 0.3, 0, 1);
			if (u <= 0 || u >= 1) continue;
			double rr = 1.4 * (1 - u) + 0.1;
			ring(b, chest, rr * 0.8, rr, HEART, (float) Math.sin(u * Math.PI), 0f);
		}
		disc(b, chest, 0.25 + 0.1 * Math.sin(now * 20), HEART, (float) (1 - t) * 0.7f, 0f);
	}

	// ------------------------------------------------------------------ HUD

	public static void renderHud(DrawContext ctx, RenderTickCounter tc) {
		MinecraftClient mc = MinecraftClient.getInstance();
		if (mc.player == null || mc.options.hudHidden) return;
		int w = ctx.getScaledWindowWidth(), h = ctx.getScaledWindowHeight();
		int level = OpeSettings.flashLevel();
		double now = RoomFxClient.time();

		// Silent: los bordes se apagan hacia un índigo mudo
		if (ClientState.has(Payloads.State.SILENT)) {
			int edge = 0x55141A3A, zero = 0x00141A3A;
			int band = Math.max(20, h / 6);
			ctx.fillGradient(RenderLayer.getGuiOverlay(), 0, 0, w, band, edge, zero, 0);
			ctx.fillGradient(RenderLayer.getGuiOverlay(), 0, h - band, w, h, zero, edge, 0);
		}
		// Curtain: un velo muy tenue delante, como mirar a través de la gasa
		if (ClientState.has(Payloads.State.CURTAIN)) {
			ctx.fill(RenderLayer.getGuiOverlay(), 0, 0, w, h, 0x0CD8F4FF);
		}
		// Scan: la línea del escáner baja por la pantalla
		double sa = now - scanStart;
		if (level > 0 && sa >= 0 && sa < 0.7) {
			float u = (float) (sa / 0.7);
			int y = Math.round(u * h);
			int alpha = Math.round((1f - u) * (level >= 2 ? 160 : 70));
			int col = (alpha << 24) | 0x9FF0FF;
			ctx.fillGradient(RenderLayer.getGuiOverlay(), 0, Math.max(0, y - h / 8), w, y, 0x009FF0FF, (alpha / 3 << 24) | 0x9FF0FF, 0);
			ctx.fill(RenderLayer.getGuiOverlay(), 0, y, w, Math.min(h, y + 2), col);
		}
		// el pulso de color en los bordes tras una técnica fuerte
		double age = now - pulseStart;
		if (level > 0 && age >= 0 && age < 0.36 && pulseStrength > 0.01f) {
			float u = (float) (age / 0.36);
			float alpha = pulseStrength * (1f - u) * (1f - u) * (level >= 2 ? 1f : 0.45f);
			int band = Math.max(14, Math.min(w, h) / 8);
			int cTop = (Math.round(alpha * 255f) << 24) | (pulseRgb & 0xFFFFFF);
			int cZero = pulseRgb & 0xFFFFFF;
			ctx.fillGradient(RenderLayer.getGuiOverlay(), 0, 0, w, band, cTop, cZero, 0);
			ctx.fillGradient(RenderLayer.getGuiOverlay(), 0, h - band, w, h, cZero, cTop, 0);
		}
	}

	// ------------------------------------------------------------------ geometría

	private static Entity entity(MinecraftClient mc, int id) {
		if (id < 0 || mc.world == null) return null;
		Entity e = mc.world.getEntityById(id);
		return e != null && e.isAlive() ? e : null;
	}

	private static Vec3d lerpPos(Entity e) {
		return e.getLerpedPos(td);
	}

	private static Box lerpBox(Entity e) {
		Vec3d p = e.getLerpedPos(td);
		return e.getBoundingBox().offset(p.subtract(e.getPos()));
	}

	/** La mano derecha de un jugador (o el centro delante del pecho de otro ser). */
	private static Vec3d handOf(Entity e) {
		Vec3d look = e.getRotationVec(td);
		Vec3d right = look.crossProduct(new Vec3d(0, 1, 0));
		if (right.lengthSquared() < 1e-4) right = new Vec3d(1, 0, 0);
		right = right.normalize();
		Vec3d eye = e.getCameraPosVec(td);
		return eye.add(look.multiply(0.7)).add(right.multiply(0.32)).add(0, -0.32, 0);
	}

	private static void fan(BufferBuilder b, Vec3d c, Vec3d s, Vec3d dir, double reach, double depth, int rgb, float a) {
		// un abanico fino: el plano que barre la hoja entre −s y +s, un poco hacia delante y hacia atrás
		int n = 10;
		for (int i = 0; i < n; i++) {
			double u0 = -1 + 2.0 * i / n, u1 = -1 + 2.0 * (i + 1) / n;
			Vec3d p0 = c.add(s.multiply(u0 * reach)), p1 = c.add(s.multiply(u1 * reach));
			Vec3d f = dir.multiply(depth);
			float e0 = (float) (1 - Math.abs(u0)), e1 = (float) (1 - Math.abs(u1));
			quad(b, p0.subtract(f), p1.subtract(f), p1.add(f), p0.add(f), rgb, 0f, 0f, a * e1, a * e0);
		}
	}

	private static void crescent(BufferBuilder b, Vec3d c, Vec3d n, Vec3d fwd, double r, double thick, float a) {
		Vec3d s = n.crossProduct(fwd);
		if (s.lengthSquared() < 1e-4) s = new Vec3d(1, 0, 0);
		s = s.normalize();
		int k = 18;
		Vec3d prevO = null, prevI = null;
		for (int i = 0; i <= k; i++) {
			double ang = -Math.PI * 0.42 + Math.PI * 0.84 * i / k;
			double env = Math.sin(Math.PI * i / k);
			Vec3d dir = s.multiply(Math.sin(ang)).add(fwd.multiply(Math.cos(ang)));
			Vec3d o = c.add(dir.multiply(r)), in = c.add(dir.multiply(r - thick * env));
			if (prevO != null) {
				quad(b, prevI, prevO, o, in, ROOM, a * 0.35f, a * 0.9f, a * 0.9f, a * 0.35f);
				ribbon(b, prevO, o, 0.03, 0.03, CORE, a, a);
			}
			prevO = o;
			prevI = in;
		}
	}

	/** Un arco de corte en el plano de normal n, desde el ángulo start y con la amplitud span. */
	private static void arc(BufferBuilder b, Vec3d c, Vec3d n, double start, double span, double r, double thick, float a) {
		axes(n);
		int k = 22;
		Vec3d prevO = null, prevI = null;
		for (int i = 0; i <= k; i++) {
			double u = i / (double) k;
			double ang = start + span * u;
			double env = Math.sin(Math.PI * u);
			double cx = u1x * Math.cos(ang) + u2x * Math.sin(ang), cy = u1y * Math.cos(ang) + u2y * Math.sin(ang),
				cz = u1z * Math.cos(ang) + u2z * Math.sin(ang);
			Vec3d o = c.add(cx * r, cy * r, cz * r), in = c.add(cx * (r - thick * env), cy * (r - thick * env), cz * (r - thick * env));
			if (prevO != null) {
				float aa = (float) (a * u);
				quad(b, prevI, prevO, o, in, ROOM, aa * 0.3f, aa, aa, aa * 0.3f);
				ribbon(b, prevO, o, 0.025, 0.025, CORE, aa, aa);
			}
			prevO = o;
			prevI = in;
		}
	}

	private static void reticle(BufferBuilder b, Vec3d c, double r, double now, float a) {
		faceCam(c);
		ring(b, c, r * 0.92, r, ROOM, a, a * 0.3f);
		ring(b, c, r * 0.45, r * 0.5, CORE, a * 0.8f, 0f);
		double spin = now * 2.0;
		for (int k = 0; k < 4; k++) {
			double ang = spin + k * Math.PI / 2;
			double cx = u1x * Math.cos(ang) + u2x * Math.sin(ang), cy = u1y * Math.cos(ang) + u2y * Math.sin(ang),
				cz = u1z * Math.cos(ang) + u2z * Math.sin(ang);
			Vec3d p0 = c.add(cx * r * 1.05, cy * r * 1.05, cz * r * 1.05), p1 = c.add(cx * r * 1.45, cy * r * 1.45, cz * r * 1.45);
			ribbon(b, p0, p1, 0.035, 0.035, CORE, a, a * 0.6f);
		}
	}

	private static void cubeEdges(BufferBuilder b, Vec3d c, double h, int rgb, float a, double w, double spin) {
		Vec3d[] v = cubeVerts(c, h, spin);
		int[][] edges = {{0, 1}, {1, 2}, {2, 3}, {3, 0}, {4, 5}, {5, 6}, {6, 7}, {7, 4}, {0, 4}, {1, 5}, {2, 6}, {3, 7}};
		for (int[] ed : edges) ribbon(b, v[ed[0]], v[ed[1]], w, w, rgb, a, a);
	}

	private static void cubeFaces(BufferBuilder b, Vec3d c, double h, int rgb, float a, double spin) {
		Vec3d[] v = cubeVerts(c, h, spin);
		int[][] faces = {{0, 1, 2, 3}, {4, 5, 6, 7}, {0, 1, 5, 4}, {1, 2, 6, 5}, {2, 3, 7, 6}, {3, 0, 4, 7}};
		for (int[] f : faces) quad(b, v[f[0]], v[f[1]], v[f[2]], v[f[3]], rgb, a, a, a, a);
	}

	private static Vec3d[] cubeVerts(Vec3d c, double h, double spin) {
		double cs = Math.cos(spin), sn = Math.sin(spin), tilt = 0.45;
		double ct = Math.cos(tilt), st = Math.sin(tilt);
		Vec3d[] v = new Vec3d[8];
		int i = 0;
		for (int y = -1; y <= 1; y += 2) {
			double[][] xz = {{-1, -1}, {1, -1}, {1, 1}, {-1, 1}};
			for (double[] q : xz) {
				double x = q[0] * h, z = q[1] * h, yy = y * h;
				// inclinado y girando, como en el anime
				double y2 = yy * ct - z * st, z2 = yy * st + z * ct;
				double x3 = x * cs - z2 * sn, z3 = x * sn + z2 * cs;
				v[i++] = c.add(x3, y2, z3);
			}
		}
		return v;
	}

	private static void boxEdges(BufferBuilder b, Box box, int rgb, float a, double w) {
		double x0 = box.minX, y0 = box.minY, z0 = box.minZ, x1 = box.maxX, y1 = box.maxY, z1 = box.maxZ;
		Vec3d[] v = {new Vec3d(x0, y0, z0), new Vec3d(x1, y0, z0), new Vec3d(x1, y0, z1), new Vec3d(x0, y0, z1),
			new Vec3d(x0, y1, z0), new Vec3d(x1, y1, z0), new Vec3d(x1, y1, z1), new Vec3d(x0, y1, z1)};
		int[][] edges = {{0, 1}, {1, 2}, {2, 3}, {3, 0}, {4, 5}, {5, 6}, {6, 7}, {7, 4}, {0, 4}, {1, 5}, {2, 6}, {3, 7}};
		for (int[] ed : edges) ribbon(b, v[ed[0]], v[ed[1]], w, w, rgb, a, a);
	}

	private static Vec3d pointOnBox(Box box, int seed) {
		double fx = hash01(seed), fy = hash01(seed * 7 + 3), fz = hash01(seed * 13 + 5);
		int face = (int) (hash01(seed * 17 + 11) * 6) % 6;
		if (face == 0) fx = 0; else if (face == 1) fx = 1; else if (face == 2) fy = 0; else if (face == 3) fy = 1; else if (face == 4) fz = 0; else fz = 1;
		return new Vec3d(MathHelper.lerp(fx, box.minX, box.maxX), MathHelper.lerp(fy, box.minY, box.maxY), MathHelper.lerp(fz, box.minZ, box.maxZ));
	}

	private static Vec3d bezier(Vec3d p0, Vec3d p1, Vec3d p2, double u) {
		double k = 1 - u;
		return p0.multiply(k * k).add(p1.multiply(2 * k * u)).add(p2.multiply(u * u));
	}

	private static Vec3d randDir(int seed) {
		double x = hashSigned(seed), y = hashSigned(seed * 3 + 1), z = hashSigned(seed * 7 + 2);
		Vec3d v = new Vec3d(x, y, z);
		return v.lengthSquared() < 1e-4 ? new Vec3d(0, 1, 0) : v.normalize();
	}

	private static void bolt(BufferBuilder b, Vec3d a0, Vec3d b0, double outerW, double innerW, int outer, int inner,
							 float alpha, int seed, int segs, double jitter) {
		Vec3d d = b0.subtract(a0);
		double len = d.length();
		if (len < 0.15 || alpha <= 0.01f) return;
		axes(d);
		double ex = u1x, ey = u1y, ez = u1z, fx = u2x, fy = u2y, fz = u2z;
		Vec3d last = a0;
		for (int i = 1; i <= segs; i++) {
			double s = i / (double) segs;
			double env = Math.sin(s * Math.PI);
			double rx = hashSigned(seed + i * 19) * jitter * env * Math.min(1.0, len * 0.4);
			double ry = hashSigned(seed + i * 37) * jitter * env * Math.min(1.0, len * 0.4);
			Vec3d next = i == segs ? b0 : a0.add(d.multiply(s)).add(ex * rx + fx * ry, ey * rx + fy * ry, ez * rx + fz * ry);
			ribbon(b, last, next, outerW, outerW, outer, alpha * 0.5f, alpha * 0.5f);
			ribbon(b, last, next, innerW, innerW, inner, alpha, alpha);
			last = next;
		}
	}

	/** Base (u1, u2) perpendicular a n. */
	private static void axes(Vec3d n) {
		double nx = n.x, ny = n.y, nz = n.z;
		double l = Math.sqrt(nx * nx + ny * ny + nz * nz);
		if (l < 1e-6) {
			nx = 0; ny = 1; nz = 0;
		} else {
			nx /= l; ny /= l; nz /= l;
		}
		double rx = Math.abs(ny) < 0.9 ? 0 : 1, ry = Math.abs(ny) < 0.9 ? 1 : 0;
		double cx = ny * 0 - nz * ry, cy = nz * rx - nx * 0, cz = nx * ry - ny * rx;
		double cl = Math.sqrt(cx * cx + cy * cy + cz * cz);
		if (cl < 1e-6) {
			u1x = 1; u1y = 0; u1z = 0;
		} else {
			u1x = cx / cl; u1y = cy / cl; u1z = cz / cl;
		}
		u2x = ny * u1z - nz * u1y;
		u2y = nz * u1x - nx * u1z;
		u2z = nx * u1y - ny * u1x;
	}

	private static void faceCam(Vec3d c) {
		axes(c.subtract(cam));
	}

	/** Un anillo en el plano (u1, u2) actual. */
	private static void ring(BufferBuilder b, Vec3d c, double rIn, double rOut, int rgb, float aIn, float aOut) {
		if (rOut <= 0 || (aIn <= 0.003f && aOut <= 0.003f)) return;
		rIn = Math.max(0, rIn);
		for (int i = 0; i < SEG; i++) {
			double c0 = COS[i], s0 = SIN[i], c1 = COS[i + 1], s1 = SIN[i + 1];
			vtx(b, c.x + (u1x * c0 + u2x * s0) * rIn, c.y + (u1y * c0 + u2y * s0) * rIn, c.z + (u1z * c0 + u2z * s0) * rIn, rgb, aIn);
			vtx(b, c.x + (u1x * c0 + u2x * s0) * rOut, c.y + (u1y * c0 + u2y * s0) * rOut, c.z + (u1z * c0 + u2z * s0) * rOut, rgb, aOut);
			vtx(b, c.x + (u1x * c1 + u2x * s1) * rOut, c.y + (u1y * c1 + u2y * s1) * rOut, c.z + (u1z * c1 + u2z * s1) * rOut, rgb, aOut);
			vtx(b, c.x + (u1x * c1 + u2x * s1) * rIn, c.y + (u1y * c1 + u2y * s1) * rIn, c.z + (u1z * c1 + u2z * s1) * rIn, rgb, aIn);
		}
	}

	/** Un disco de luz que mira a la cámara (centro brillante, borde transparente). */
	private static void disc(BufferBuilder b, Vec3d c, double r, int rgb, float aCenter, float aEdge) {
		faceCam(c);
		ring(b, c, 0, r, rgb, aCenter, aEdge);
	}

	private static void quad(BufferBuilder b, Vec3d p0, Vec3d p1, Vec3d p2, Vec3d p3, int rgb, float a0, float a1, float a2, float a3) {
		vtx(b, p0.x, p0.y, p0.z, rgb, a0);
		vtx(b, p1.x, p1.y, p1.z, rgb, a1);
		vtx(b, p2.x, p2.y, p2.z, rgb, a2);
		vtx(b, p3.x, p3.y, p3.z, rgb, a3);
	}

	/** Una cinta entre dos puntos que siempre da la cara a la cámara. */
	private static void ribbon(BufferBuilder b, Vec3d p0, Vec3d p1, double w0, double w1, int rgb, float a0, float a1) {
		double ax = p0.x - cam.x, ay = p0.y - cam.y, az = p0.z - cam.z;
		double bx = p1.x - cam.x, by = p1.y - cam.y, bz = p1.z - cam.z;
		double mx = (ax + bx) * 0.5, my = (ay + by) * 0.5, mz = (az + bz) * 0.5;
		double dx = bx - ax, dy = by - ay, dz = bz - az;
		double px = dy * mz - dz * my, py = dz * mx - dx * mz, pz = dx * my - dy * mx;
		double pl = Math.sqrt(px * px + py * py + pz * pz);
		if (pl < 1e-9) return;
		px /= pl; py /= pl; pz /= pl;
		vtx(b, p0.x - px * w0, p0.y - py * w0, p0.z - pz * w0, rgb, a0);
		vtx(b, p0.x + px * w0, p0.y + py * w0, p0.z + pz * w0, rgb, a0);
		vtx(b, p1.x + px * w1, p1.y + py * w1, p1.z + pz * w1, rgb, a1);
		vtx(b, p1.x - px * w1, p1.y - py * w1, p1.z - pz * w1, rgb, a1);
	}

	/** Un vértice en coordenadas del mundo (se pasa a relativas a la cámara aquí). */
	private static void vtx(BufferBuilder b, double x, double y, double z, int rgb, float a) {
		a = MathHelper.clamp(a, 0f, 1f);
		float fx = (float) (x - cam.x), fy = (float) (y - cam.y), fz = (float) (z - cam.z);
		if (!plain) {
			BASE.transformPosition(fx, fy, fz, TV);
			fx = TV.x;
			fy = TV.y;
			fz = TV.z;
		}
		b.vertex(fx, fy, fz).color((rgb >> 16) & 255, (rgb >> 8) & 255, rgb & 255, Math.round(a * 255f));
	}

	private static int lerpRgb(int c0, int c1, float u) {
		u = MathHelper.clamp(u, 0f, 1f);
		int r = Math.round(((c0 >> 16) & 255) + (((c1 >> 16) & 255) - ((c0 >> 16) & 255)) * u);
		int g = Math.round(((c0 >> 8) & 255) + (((c1 >> 8) & 255) - ((c0 >> 8) & 255)) * u);
		int bl = Math.round((c0 & 255) + ((c1 & 255) - (c0 & 255)) * u);
		return (r << 16) | (g << 8) | bl;
	}

	private static double hash01(int x) {
		return (hashSigned(x) + 1.0) * 0.5;
	}

	private static double hashSigned(int x) {
		x = ((x >>> 16) ^ x) * 0x45d9f3b;
		x = ((x >>> 16) ^ x) * 0x45d9f3b;
		x = (x >>> 16) ^ x;
		return ((x & 0xFFFF) / 32767.5) - 1.0;
	}
}
