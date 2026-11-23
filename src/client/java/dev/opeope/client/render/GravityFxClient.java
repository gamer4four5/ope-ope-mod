package dev.opeope.client.render;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import dev.opeope.client.ClientState;
import dev.opeope.client.config.OpeSettings;
import dev.opeope.client.room.CameraFx;
import dev.opeope.client.room.RoomFxClient;
import dev.opeope.entity.PieceEntity;
import dev.opeope.net.Payloads;
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
import net.minecraft.entity.mob.HostileEntity;
import net.minecraft.entity.projectile.ProjectileEntity;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * Motor gráfico 3D + HUD exclusivo de la Fruta Gravity (Zushi Zushi no Mi — Almirante Fujitora).
 * <p>
 * 100 % independiente de Law y sin ninguna cúpula ROOM: renderiza anillos concéntricos de presión violeta-negro,
 * relámpagos gravitatorios, corredores horizontales de Moko, grietas cósmicas de Tengai Shinsei, anillos de surf
 * bajo la roca voladora, barrera giroscópica de repulsión y auras 3D a través de muros del Haki Ciego de Fujitora.
 */
public final class GravityFxClient {
	private GravityFxClient() {}

	private static final int RING_SEG = 32;
	private static final float[] COS = new float[RING_SEG + 1];
	private static final float[] SIN = new float[RING_SEG + 1];

	private static final int COL_NEON   = 0xB84DFF;
	private static final int COL_CORE   = 0xF3DBFF;
	private static final int COL_ABYSS  = 0x35085E;
	private static final int COL_LIFT   = 0xA888FF;
	private static final int COL_GOLD   = 0xFFD23F;
	private static final int COL_FIRE   = 0xFF6B22;
	private static final int COL_HOT    = 0xFFF2CC;

	static {
		for (int i = 0; i <= RING_SEG; i++) {
			double a = Math.PI * 2.0 * i / RING_SEG;
			COS[i] = (float) Math.cos(a);
			SIN[i] = (float) Math.sin(a);
		}
	}

	private record FxEvent(
		int kind, int userId,
		double ax, double ay, double az,
		double bx, double by, double bz,
		float radius, float power, double duration,
		double startTime, int seed
	) {}

	private static final List<FxEvent> EVENTS = new ArrayList<>();
	private static double screenPulseStart = -100;
	private static float screenPulseStrength = 0f;
	private static int screenPulseRgb = COL_NEON;

	private static final Matrix4f BASE = new Matrix4f();
	private static boolean plain;
	private static final Vector3f TV = new Vector3f();
	private static double ax1x, ax1y, ax1z, ax2x, ax2y, ax2z;

	public static void receive(Payloads.GravityFx p) {
		if (p == null) return;
		double now = RoomFxClient.time();
		double dur = Math.max(0.25, p.ticks() / 20.0);
		int seed = (int) (Float.floatToIntBits((float) p.ax()) * 31 + Float.floatToIntBits((float) p.bz()) * 17 + System.nanoTime());
		EVENTS.add(new FxEvent(
			p.kind(), p.userId(),
			p.ax(), p.ay(), p.az(),
			p.bx(), p.by(), p.bz(),
			Math.max(0.5f, p.radius()), Math.max(0.2f, p.power()), dur,
			now, seed
		));
		while (EVENTS.size() > 36) EVENTS.remove(0);

		MinecraftClient mc = MinecraftClient.getInstance();
		if (mc.player == null) return;
		boolean isUser = p.userId() == mc.player.getId();
		double distSq = mc.player.squaredDistanceTo(p.ax(), p.ay(), p.az());

		if (p.kind() == Payloads.GravityFx.KIND_METEOR_IMPACT && distSq < 380 * 380) {
			float prox = (float) MathHelper.clamp(1.0 - Math.sqrt(distSq) / 380.0, 0.25, 1.0);
			float amp = MathHelper.clamp(p.power() * 0.55f, 0.7f, 2.2f);
			CameraFx.trauma(Math.min(1.0f, 0.75f * prox * amp));
			CameraFx.kick(3.8f * prox * Math.min(1.6f, amp), 0f, 2.2f * prox);
			CameraFx.fov(-7.2f * prox, 0.04f, 0.65f);
			triggerPulse(Math.min(0.55f, 0.42f * prox * amp), COL_FIRE);
		} else if (isUser) {
			switch (p.kind()) {
				case Payloads.GravityFx.KIND_PRESS_COLUMN -> {
					PlayerAnimClient.triggerShamblesGesture();
					float k = MathHelper.clamp(p.power(), 0.5f, 2.2f);
					CameraFx.trauma(0.32f * k);
					CameraFx.kick(1.8f * k, 0f, 0.9f);
					CameraFx.fov(-2.8f * k, 0.03f, 0.28f);
					triggerPulse(0.24f * Math.min(1.5f, k), COL_NEON);
				}
				case Payloads.GravityFx.KIND_MOKO -> {
					PlayerAnimClient.triggerShamblesGesture();
					CameraFx.trauma(0.38f);
					CameraFx.kick(-1.4f, 0f, 1.6f);
					CameraFx.fov(4.5f, 0.04f, 0.30f);
					triggerPulse(0.26f, COL_NEON);
				}
				case Payloads.GravityFx.KIND_METEOR_RIFT -> {
					PlayerAnimClient.triggerShamblesGesture();
					CameraFx.trauma(0.30f);
					CameraFx.kick(-2.0f, 0f, 0f);
					CameraFx.fov(-3.0f, 0.06f, 0.40f);
					triggerPulse(0.22f, COL_NEON);
				}
				case Payloads.GravityFx.KIND_SHIELD_BURST -> {
					CameraFx.trauma(0.22f);
					CameraFx.fov(3.2f, 0.03f, 0.22f);
					triggerPulse(0.20f, COL_LIFT);
				}
				case Payloads.GravityFx.KIND_HYPER_PRESS -> {
					CameraFx.trauma(0.25f);
					CameraFx.kick(1.1f, 0f, 0.6f);
					triggerPulse(0.18f, COL_GOLD);
				}
				case Payloads.GravityFx.KIND_LIFT_PULSE -> {
					CameraFx.kick(-0.7f, 0f, 0f);
				}
				case Payloads.GravityFx.KIND_PULL_VORTEX -> {
					CameraFx.fov(-2.4f, 0.05f, 0.30f);
				}
				default -> {}
			}
		}
	}

	private static void triggerPulse(float strength, int rgb) {
		screenPulseStart = RoomFxClient.time();
		screenPulseStrength = Math.max(screenPulseStrength, MathHelper.clamp(strength, 0f, 0.55f));
		screenPulseRgb = rgb;
	}

	public static void clear() {
		EVENTS.clear();
		screenPulseStart = -100;
		screenPulseStrength = 0f;
		CameraFx.traumaFloor("meteor", 0f);
	}

	public static void renderWorld(WorldRenderContext ctx) {
		MinecraftClient mc = MinecraftClient.getInstance();
		if (mc.world == null || mc.player == null) return;
		double now = RoomFxClient.time();

		Iterator<FxEvent> it = EVENTS.iterator();
		while (it.hasNext()) {
			FxEvent ev = it.next();
			double age = now - ev.startTime;
			if (age < 0 || age > ev.duration) it.remove();
		}

		// Recolecta todos los meteoritos reales cayendo en el cielo para envolverlos en bola de fuego y cola de plasma
		List<PieceEntity> fallingMeteors = null;
		float approachTremor = 0f;
		for (Entity ent : mc.world.getEntities()) {
			if (ent instanceof PieceEntity pe && pe.isAlive() && pe.isMeteor()) {
				if (fallingMeteors == null) fallingMeteors = new ArrayList<>(4);
				fallingMeteors.add(pe);
				double d = Math.sqrt(pe.squaredDistanceTo(mc.player));
				if (d < 240.0) {
					float t = (float) MathHelper.clamp((1.0 - d / 240.0) * (0.14 + pe.meteorScale() * 0.055), 0.0, 0.48);
					approachTremor = Math.max(approachTremor, t);
				}
			}
		}
		CameraFx.traumaFloor("meteor", approachTremor);

		boolean isGrav = ClientState.gravityFruit();
		boolean riding = mc.player.getVehicle() instanceof PieceEntity sr && sr.isGravitySurf();
		boolean barrier = isGrav && ClientState.has(Payloads.State.CURTAIN);
		boolean charging = isGrav && ClientState.gravityCharging();
		boolean meteorCharging = isGrav && ClientState.gravityMeteorCharging();
		boolean blindHaki = isGrav && ClientState.scanTicks > 0;
		boolean hasMeteors = fallingMeteors != null && !fallingMeteors.isEmpty();

		if (EVENTS.isEmpty() && !riding && !barrier && !charging && !meteorCharging && !hasMeteors && !blindHaki) return;

		Vec3d cam = ctx.camera().getPos();
		float td = ctx.tickCounter().getTickDelta(true);
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
			// Pasada 1 (profundidad activada): anillos 3D de gravedad, relámpagos, bolas de fuego de meteorito y estados en vivo
			if (!EVENTS.isEmpty() || riding || barrier || charging || meteorCharging || hasMeteors) {
				BufferBuilder b = Tessellator.getInstance().begin(VertexFormat.DrawMode.QUADS, VertexFormats.POSITION_COLOR);
				for (FxEvent ev : EVENTS) {
					drawEvent(b, ev, cam, now, calm);
				}
				if (hasMeteors) drawFallingMeteors(b, fallingMeteors, cam, td, now, calm);
				if (riding) drawRockSurfRings(b, mc, cam, td, now, calm);
				if (barrier) drawRepulsionBarrier(b, mc, cam, td, now, calm);
				if (charging) drawLiveChargeAim(b, mc, cam, td, now, calm);
				if (meteorCharging) drawLiveMeteorCharge(b, mc, cam, td, now, calm);
				var built = b.endNullable();
				if (built != null) BufferRenderer.drawWithGlobalProgram(built);
			}

			// Pasada 2 (sin profundidad): Haki de Observación Ciego de Fujitora — auras 3D visibles a través de todo muro
			if (blindHaki) {
				RenderSystem.disableDepthTest();
				BufferBuilder hb = Tessellator.getInstance().begin(VertexFormat.DrawMode.QUADS, VertexFormats.POSITION_COLOR);
				drawBlindHakiAuras(hb, mc, cam, td, now, calm);
				var builtH = hb.endNullable();
				if (builtH != null) BufferRenderer.drawWithGlobalProgram(builtH);
			}
		} finally {
			RenderSystem.enableDepthTest();
			RenderSystem.defaultBlendFunc();
			RenderSystem.depthMask(true);
			RenderSystem.enableCull();
			RenderSystem.disableBlend();
		}
	}

	// ------------------------------------------------------------------ eventos 3D exclusivos de Gravity

	private static void drawEvent(BufferBuilder b, FxEvent ev, Vec3d cam, double now, boolean calm) {
		double t = MathHelper.clamp((now - ev.startTime) / ev.duration, 0.0, 1.0);
		double fade = 1.0 - t * t;
		double ax = ev.ax - cam.x, ay = ev.ay - cam.y, az = ev.az - cam.z;
		double bx = ev.bx - cam.x, by = ev.by - cam.y, bz = ev.bz - cam.z;
		double r = ev.radius;

		switch (ev.kind) {
			case Payloads.GravityFx.KIND_PRESS_COLUMN -> drawPressColumn(b, ax, ay, az, bx, by, bz, r, t, fade, now, ev.seed, calm);
			case Payloads.GravityFx.KIND_MOKO -> drawMokoCorridor(b, ax, ay, az, bx, by, bz, r, t, fade, now, ev.seed, calm);
			case Payloads.GravityFx.KIND_METEOR_RIFT -> drawMeteorRift(b, ax, ay, az, bx, by, bz, r, t, fade, now, ev.seed, calm);
			case Payloads.GravityFx.KIND_METEOR_IMPACT -> drawMeteorImpact(b, ax, ay, az, bx, by, bz, r, t, fade, now, ev.seed, calm);
			case Payloads.GravityFx.KIND_LIFT_PULSE -> drawLiftPulse(b, ax, ay, az, bx, by, bz, r, t, fade, now, calm);
			case Payloads.GravityFx.KIND_SHIELD_BURST -> drawShieldBurst(b, ax, ay, az, r, t, fade, now);
			case Payloads.GravityFx.KIND_CHARGE_RING -> drawChargeRing(b, ax, ay, az, bx, by, bz, r, t, fade, now);
			case Payloads.GravityFx.KIND_HYPER_PRESS -> drawHyperPress(b, ax, ay, az, bx, by, bz, r, t, fade, now);
			case Payloads.GravityFx.KIND_PULL_VORTEX -> drawPullVortex(b, ax, ay, az, r, t, fade, now, calm);
			default -> {}
		}
	}

	/** Cascada vertical de 8 anillos concéntricos violeta-negro + 4 rayos + onda de choque al suelo (Gravito +G / Jigoku Tabi). */
	private static void drawPressColumn(BufferBuilder b, double ax, double ay, double az, double bx, double by, double bz,
										double r, double t, double fade, double now, int seed, boolean calm) {
		axesFrom(0, 1, 0);
		int rings = calm ? 5 : 8;
		double colH = Math.max(4.0, ay - by);
		for (int i = 0; i < rings; i++) {
			double phase = ((t * 1.9 + i / (double) rings) % 1.0);
			double y = ay - phase * colH;
			double ringR = r * (0.48 + 0.62 * phase);
			double thick = Math.max(0.22, ringR * 0.14);
			float a = (float) (Math.sin(phase * Math.PI) * 0.85 * fade);
			ringBand(b, bx, y, bz, Math.max(0.08, ringR - thick), ringR, COL_NEON, a * 0.85f, 0.08f * a);
			ringBand(b, bx, y + 0.04, bz, Math.max(0.05, ringR - thick * 0.35), ringR - thick * 0.08, COL_CORE, a * 0.9f, a * 0.2f);
			ringBand(b, bx, y - 0.06, bz, Math.max(0.04, ringR * 0.65), ringR * 0.88, COL_ABYSS, a * 0.65f, 0f);
		}
		// Onda expansiva aplastante en el suelo (B)
		double shockR = r * (0.35 + t * 1.65);
		float sa = (float) (0.90 * fade);
		ringBand(b, bx, by + 0.08, bz, Math.max(0.1, shockR * 0.72), shockR, COL_NEON, sa, 0f);
		ringBand(b, bx, by + 0.10, bz, Math.max(0.1, shockR * 0.88), shockR * 0.96, COL_CORE, sa * 0.85f, 0f);

		// 4 relámpagos gravitatorios verticales quebrados bajando por la columna
		if (!calm && t < 0.78) {
			float ba = (float) ((1.0 - t / 0.78) * 0.90);
			int flicker = (int) (now * 20.0);
			for (int k = 0; k < 4; k++) {
				double ang = k * (Math.PI * 0.5) + seed * 0.3 + t * 1.2;
				double ox = Math.cos(ang) * r * 0.68;
				double oz = Math.sin(ang) * r * 0.68;
				drawJaggedBolt(b, bx + ox, ay, bz + oz, bx + ox * 0.75, by + 0.15, bz + oz * 0.75,
					0.16, 0.05, COL_NEON, COL_CORE, ba, seed + k * 41 + flicker * 7, 9, 0.65);
			}
		}
	}

	/** Corredor horizontal de 10 anillos gravitatorios + rayos violeta rasgando el aire (Moko — Tigre Feroz). */
	private static void drawMokoCorridor(BufferBuilder b, double ax, double ay, double az, double bx, double by, double bz,
										 double r, double t, double fade, double now, int seed, boolean calm) {
		double dx = bx - ax, dy = by - ay, dz = bz - az;
		double len = Math.sqrt(dx * dx + dy * dy + dz * dz);
		if (len < 0.3) return;
		double fx = dx / len, fy = dy / len, fz = dz / len;
		axesFrom(fx, fy, fz);

		int rings = calm ? 6 : 10;
		for (int i = 0; i < rings; i++) {
			double stagger = i * 0.055;
			double u = MathHelper.clamp((t * 1.65) - stagger, 0.0, 1.0);
			if (u <= 0.001 || u >= 0.999) continue;
			double rx = ax + dx * u, ry = ay + dy * u, rz = az + dz * u;
			double rr = r * (0.35 + 0.95 * u);
			double thick = Math.max(0.18, rr * 0.16);
			float a = (float) (Math.sin(u * Math.PI) * 0.88 * fade);
			ringBand(b, rx, ry, rz, Math.max(0.06, rr - thick), rr, COL_NEON, a * 0.85f, 0.05f);
			ringBand(b, rx, ry, rz, Math.max(0.04, rr - thick * 0.35), rr - thick * 0.08, COL_CORE, a * 0.95f, 0.1f);
		}

		// Haz central + 4 rayos quebrados a lo largo del túnel de Moko
		double tipU = Math.min(1.0, t * 2.2);
		double tx = ax + dx * tipU, ty = ay + dy * tipU, tz = az + dz * tipU;
		float ba = (float) (0.75 * fade);
		ribbon(b, ax, ay, az, tx, ty, tz, r * 0.34, r * 0.10, COL_NEON, ba * 0.42f, ba * 0.15f);
		ribbon(b, ax, ay, az, tx, ty, tz, r * 0.11, r * 0.04, COL_CORE, ba * 0.85f, ba * 0.35f);

		if (!calm) {
			int flicker = (int) (now * 24.0);
			for (int k = 0; k < 4; k++) {
				double ang = k * (Math.PI * 0.5) + now * 4.5 + seed;
				double ox = (ax1x * Math.cos(ang) + ax2x * Math.sin(ang)) * r * 0.45;
				double oy = (ax1y * Math.cos(ang) + ax2y * Math.sin(ang)) * r * 0.45;
				double oz = (ax1z * Math.cos(ang) + ax2z * Math.sin(ang)) * r * 0.45;
				drawJaggedBolt(b, ax + ox * 0.4, ay + oy * 0.4, az + oz * 0.4, tx + ox, ty + oy, tz + oz,
					0.14, 0.045, COL_NEON, COL_CORE, ba * 0.95f, seed + k * 71 + flicker * 13, 10, 0.75);
			}
		}
	}

	/** Haz gravitatorio ascendente desde la espada hacia el cielo + grieta cósmica giratoria en las nubes (Tengai Shinsei). */
	private static void drawMeteorRift(BufferBuilder b, double ax, double ay, double az, double bx, double by, double bz,
									   double r, double t, double fade, double now, int seed, boolean calm) {
		// 1) Haz vertical púrpura-blanco disparado al cielo en los primeros instantes (escalado al tamaño del meteorito)
		if (t < 0.45) {
			double u = t / 0.45;
			float ba = (float) (Math.sin(u * Math.PI) * 0.94);
			double beamW = Math.max(0.65, r * 0.055) * (1.0 - 0.30 * u);
			ribbon(b, ax, ay, az, bx, by, bz, beamW * 2.2, beamW * 4.2, COL_NEON, ba * 0.50f, ba * 0.28f);
			ribbon(b, ax, ay, az, bx, by, bz, beamW * 0.75, beamW * 1.35, COL_CORE, ba * 0.96f, ba * 0.72f);
		}

		// 2) Portal cósmico en el cielo (B): 3 capas concéntricas + brazos espirales girando
		double open = Math.min(1.0, t * 4.0);
		double close = t > 0.75 ? 1.0 - (t - 0.75) / 0.25 : 1.0;
		double scale = open * close;
		if (scale <= 0.02) return;
		double pr = r * (0.65 + 0.55 * scale);
		float pa = (float) (0.90 * scale);

		axesFrom(0, 1, 0);
		ringBand(b, bx, by, bz, pr * 0.72, pr * 1.15, COL_NEON, pa * 0.85f, 0f);
		ringBand(b, bx, by - 0.15, bz, pr * 0.42, pr * 0.78, COL_ABYSS, pa * 0.90f, pa * 0.45f);
		ringBand(b, bx, by + 0.10, bz, pr * 0.82, pr * 0.94, COL_CORE, pa * 0.95f, 0.1f * pa);
		ringBand(b, bx, by - 0.45, bz, pr * 0.20, pr * 0.48, COL_FIRE, pa * 0.75f, 0f);

		// 6 brazos espirales y relámpagos rodeando la grieta espacial
		if (!calm) {
			double spin = now * 2.6 + seed;
			for (int arm = 0; arm < 6; arm++) {
				double baseAng = spin + arm * (Math.PI / 3.0);
				double px = bx + Math.cos(baseAng) * pr * 0.28;
				double pz = bz + Math.sin(baseAng) * pr * 0.28;
				for (int s = 1; s <= 5; s++) {
					double u = s / 5.0;
					double ang = baseAng + u * 1.35;
					double rr = pr * (0.28 + u * 0.88);
					double nx = bx + Math.cos(ang) * rr;
					double nz = bz + Math.sin(ang) * rr;
					ribbon(b, px, by - u * 0.6, pz, nx, by - (u + 0.15) * 0.6, nz,
						0.38 * (1.0 - u * 0.5), 0.18, arm % 2 == 0 ? COL_NEON : COL_LIFT, pa * 0.75f, pa * 0.20f);
					px = nx;
					pz = nz;
				}
			}
		}
	}

	/** Impacto masivo de meteorito: anillos dobles fuego-púrpura en el cráter + columna de plasma + rayos al cielo. */
	private static void drawMeteorImpact(BufferBuilder b, double ax, double ay, double az, double bx, double by, double bz,
										 double r, double t, double fade, double now, int seed, boolean calm) {
		axesFrom(0, 1, 0);
		double wave1 = r * (0.25 + t * 2.1);
		double wave2 = r * (0.15 + Math.pow(t, 0.72) * 1.45);
		float a = (float) (0.95 * fade);

		// Doble onda expansiva en el suelo: llama exterior + anillo gravitatorio púrpura + núcleo incandescente
		ringBand(b, ax, ay + 0.12, az, Math.max(0.2, wave1 * 0.76), wave1, COL_FIRE, a * 0.90f, 0f);
		ringBand(b, ax, ay + 0.18, az, Math.max(0.2, wave1 * 0.88), wave1 * 0.96, COL_HOT, a, 0f);
		ringBand(b, ax, ay + 0.35, az, Math.max(0.2, wave2 * 0.70), wave2, COL_NEON, a * 0.88f, 0f);
		ringBand(b, ax, ay + 0.42, az, Math.max(0.2, wave2 * 0.86), wave2 * 0.95, COL_CORE, a * 0.80f, 0f);

		// Columna de plasma ascendente en el punto de impacto
		if (t < 0.70) {
			double u = t / 0.70;
			float ca = (float) ((1.0 - u) * 0.78);
			double topY = ay + (by - ay) * (0.45 + 0.55 * u);
			drawCylinder(b, ax, ay, az, topY, r * (0.34 + 0.18 * u), COL_FIRE, ca * 0.65f);
			drawCylinder(b, ax, ay, az, topY * 0.92 + ay * 0.08, r * 0.18, COL_HOT, ca * 0.90f);
			drawCylinder(b, ax, ay, az, topY * 1.05 - ay * 0.05, r * 0.48, COL_NEON, ca * 0.35f);
		}

		// 8 arcos colosales de energía brotando del cráter hacia el cielo
		if (!calm && t < 0.72) {
			float ba = (float) ((1.0 - t / 0.72) * 0.95);
			int flicker = (int) (now * 18.0);
			double boltW = Math.min(0.85, 0.24 + r * 0.012);
			for (int k = 0; k < 8; k++) {
				double ang = k * (Math.PI * 0.25) + seed * 0.7;
				double ox = Math.cos(ang) * r * 0.90;
				double oz = Math.sin(ang) * r * 0.90;
				drawJaggedBolt(b, ax, ay + 0.4, az, ax + ox, ay + (12.0 + r * 0.65) * (0.5 + t), az + oz,
					boltW, boltW * 0.34, k % 2 == 0 ? COL_NEON : COL_FIRE, COL_HOT, ba, seed + k * 59 + flicker * 9, 9, Math.min(2.6, 0.8 + r * 0.04));
			}
		}
	}

	/**
	 * Reentrada atmosférica real en 3D para cada meteorito cayendo del cielo:
	 * onda de choque frontal blanco-incandescente (Mach bow shock), cola de plasma ardiente de hasta 145 m,
	 * cintas de fuego-violeta azotando hacia atrás y anillos sónicos expandiéndose por la estela.
	 */
	private static void drawFallingMeteors(BufferBuilder b, List<PieceEntity> meteors, Vec3d cam, float td, double now, boolean calm) {
		int flicker = (int) (now * 24.0);
		for (PieceEntity pe : meteors) {
			float sc = Math.max(1.0f, pe.meteorScale());
			double r = 5.4 * sc;
			Vec3d cWorld = pe.getLerpedPos(td).add(0.0, (pe.blocks != null ? pe.blocks.sy : 10) * 0.5, 0.0);
			double cx = cWorld.x - cam.x, cy = cWorld.y - cam.y, cz = cWorld.z - cam.z;

			Vec3d vel = pe.getVelocity();
			Vec3d fwd = vel.lengthSquared() > 1e-4 ? vel.normalize() : new Vec3d(0.0, -1.0, 0.0);
			Vec3d back = fwd.multiply(-1.0);
			axesFrom(fwd.x, fwd.y, fwd.z);

			// 1) Frente de choque térmico (bow shock blanco-oro-naranja abrazando la cara frontal del asteroide)
			double[][] bowLayers = {
				{r * 1.05, r * 0.15, r * 0.62, COL_HOT,  0.96},
				{r * 0.82, r * 0.48, r * 1.08, COL_FIRE, 0.90},
				{r * 0.45, r * 0.82, r * 1.32, COL_FIRE, 0.72},
				{r * 0.12, r * 0.95, r * 1.52, COL_NEON, 0.56}
			};
			for (double[] l : bowLayers) {
				double lx = cx + fwd.x * l[0], ly = cy + fwd.y * l[0], lz = cz + fwd.z * l[0];
				ringBand(b, lx, ly, lz, l[1], l[2], (int) l[3], (float) l[4], 0.06f);
			}

			// 2) Cola colosal de fuego y plasma gravitatorio (35 m .. 145 m de largo hacia la grieta del cielo!)
			double tailLen = Math.min(150.0, r * 3.8 + 28.0);
			double tx = cx + back.x * tailLen, ty = cy + back.y * tailLen, tz = cz + back.z * tailLen;
			double mx = cx + back.x * (tailLen * 0.45), my = cy + back.y * (tailLen * 0.45), mz = cz + back.z * (tailLen * 0.45);
			// Núcleo cegador blanco-amarillo + llama naranja exterior + estela violeta gravitatoria
			ribbon(b, cx + fwd.x * r * 0.4, cy + fwd.y * r * 0.4, cz + fwd.z * r * 0.4, mx, my, mz, r * 0.55, r * 0.20, COL_HOT, 0.95f, 0.35f);
			ribbon(b, cx, cy, cz, tx, ty, tz, r * 1.15, r * 0.25, COL_FIRE, 0.85f, 0.05f);
			ribbon(b, cx, cy, cz, tx, ty, tz, r * 1.55, r * 0.42, COL_NEON, 0.45f, 0.02f);

			// 3) 5 anillos de compresión sónica deslizándose hacia atrás por la cola ardiente
			int rings = calm ? 3 : 5;
			for (int k = 0; k < rings; k++) {
				double phase = ((now * 2.8 + k / (double) rings) % 1.0);
				double dist = phase * tailLen * 0.72;
				double rx = cx + back.x * dist, ry = cy + back.y * dist, rz = cz + back.z * dist;
				double rr = r * (0.95 + phase * 0.75);
				float ra = (float) ((1.0 - phase) * 0.75);
				ringBand(b, rx, ry, rz, rr * 0.72, rr, k % 2 == 0 ? COL_FIRE : COL_NEON, ra, 0f);
			}

			// 4) 6 lenguas de fuego y rayos violeta desprendiéndose de los costados del asteroide
			if (!calm) {
				for (int s = 0; s < 6; s++) {
					double ang = s * (Math.PI / 3.0) + now * 3.2 + pe.getId();
					double ox = (ax1x * Math.cos(ang) + ax2x * Math.sin(ang)) * r * 0.82;
					double oy = (ax1y * Math.cos(ang) + ax2y * Math.sin(ang)) * r * 0.82;
					double oz = (ax1z * Math.cos(ang) + ax2z * Math.sin(ang)) * r * 0.82;
					double ex = cx + ox * 0.35 + back.x * (tailLen * (0.55 + 0.12 * (s % 3)));
					double ey = cy + oy * 0.35 + back.y * (tailLen * (0.55 + 0.12 * (s % 3)));
					double ez = cz + oz * 0.35 + back.z * (tailLen * (0.55 + 0.12 * (s % 3)));
					double bw = Math.min(1.4, 0.28 + sc * 0.14);
					drawJaggedBolt(b, cx + ox, cy + oy, cz + oz, ex, ey, ez,
						bw, bw * 0.35, s % 2 == 0 ? COL_FIRE : COL_NEON, COL_HOT, 0.82f,
						pe.getId() * 17 + s * 43 + flicker * 7, 7, Math.min(3.5, r * 0.12));
				}
			}
		}
	}

	/**
	 * Vista previa en vivo mientras mantienes Z: muestra el radio EXACTO del cráter (hasta 116 m de ancho!),
	 * un haz vertical al cielo y la grieta cósmica abriéndose en las alturas al agrandar el meteorito.
	 */
	private static void drawLiveMeteorCharge(BufferBuilder b, MinecraftClient mc, Vec3d cam, float td, double now, boolean calm) {
		Vec3d eye = mc.player.getEyePos();
		Vec3d look = mc.player.getRotationVec(td);
		HitResult hr = mc.player.raycast(200.0, td, false);
		Vec3d hit;
		if (hr != null && hr.getType() != HitResult.Type.MISS) {
			hit = hr.getPos();
		} else {
			Vec3d far = eye.add(look.multiply(115.0));
			int topY = mc.world.getTopY(net.minecraft.world.Heightmap.Type.MOTION_BLOCKING_NO_LEAVES,
				MathHelper.floor(far.x), MathHelper.floor(far.z));
			hit = new Vec3d(far.x, topY > mc.world.getBottomY() + 1 ? topY : mc.player.getY(), far.z);
		}

		int tier = ClientState.gravityMeteorTier();
		double craterR = dev.opeope.power.GravityPowers.METEOR_CRATERS[tier];
		double dropH = Math.min(190.0, dev.opeope.power.GravityPowers.METEOR_HEIGHTS[tier] * 0.78);
		double sc = dev.opeope.power.GravityPowers.METEOR_SCALES[tier];

		double cx = hit.x - cam.x, cy = hit.y - cam.y + 0.15, cz = hit.z - cam.z;
		double pulse = calm ? 1.0 : 0.96 + 0.04 * Math.sin(now * 8.0);
		double r = craterR * pulse;
		double thick = Math.max(0.55, r * 0.055);

		// Anillo colosal en tierra marcando exactamente lo que va a borrar el cráter
		axesFrom(0, 1, 0);
		ringBand(b, cx, cy, cz, Math.max(0.5, r - thick * 1.8), r, COL_FIRE, 0.88f, 0.06f);
		ringBand(b, cx, cy + 0.06, cz, Math.max(0.5, r - thick * 0.55), r - thick * 0.12, COL_HOT, 0.95f, 0.15f);
		ringBand(b, cx, cy + 0.12, cz, Math.max(0.4, r * 0.52 - thick), r * 0.52, COL_NEON, 0.72f, 0.05f);
		ringBand(b, cx, cy + 0.18, cz, 0.4, Math.max(2.0, r * 0.14), COL_CORE, 0.80f, 0f);

		// Haz vertical apuntando al cielo + grieta cósmica creciendo arriba según el nivel elegido
		double skyY = cy + dropH;
		ribbon(b, cx, cy, cz, cx, skyY, cz, Math.max(0.4, sc * 0.45), Math.max(0.9, sc * 0.95), COL_NEON, 0.55f, 0.25f);
		ribbon(b, cx, cy, cz, cx, skyY, cz, Math.max(0.16, sc * 0.18), Math.max(0.32, sc * 0.36), COL_HOT, 0.85f, 0.45f);

		double riftR = 9.0 * sc * pulse;
		ringBand(b, cx, skyY, cz, riftR * 0.68, riftR * 1.15, COL_NEON, 0.88f, 0f);
		ringBand(b, cx, skyY - 0.3, cz, riftR * 0.35, riftR * 0.72, COL_FIRE, 0.78f, 0.15f);
		ringBand(b, cx, skyY + 0.2, cz, riftR * 0.82, riftR * 0.95, COL_HOT, 0.92f, 0.1f);
	}

	/** Anillos ascendentes en espiral (Antigravedad -G, levitación libre, despegue en roca, Escombros de Dressrosa). */
	private static void drawLiftPulse(BufferBuilder b, double ax, double ay, double az, double bx, double by, double bz,
									  double r, double t, double fade, double now, boolean calm) {
		axesFrom(0, 1, 0);
		double riseH = Math.max(3.0, by - ay);
		int rings = calm ? 4 : 6;
		for (int i = 0; i < rings; i++) {
			double phase = ((t * 1.55 + i / (double) rings) % 1.0);
			double y = ay + phase * riseH;
			double rr = r * (0.55 + 0.45 * Math.sin(phase * Math.PI));
			double thick = Math.max(0.16, rr * 0.14);
			float a = (float) (Math.sin(phase * Math.PI) * 0.82 * fade);
			ringBand(b, ax, y, az, Math.max(0.05, rr - thick), rr, COL_LIFT, a, 0.05f);
			ringBand(b, ax, y + 0.02, az, Math.max(0.04, rr - thick * 0.35), rr - thick * 0.08, COL_CORE, a * 0.85f, 0f);
		}
	}

	/** Onda rápida de anillos multi-eje de repulsión (solo anillos, jamás una cúpula). */
	private static void drawShieldBurst(BufferBuilder b, double ax, double ay, double az, double r, double t, double fade, double now) {
		double rr = r * (0.25 + t * 1.15);
		double thick = Math.max(0.18, rr * 0.14);
		float a = (float) (0.90 * fade);
		double[][] norms = {
			{0, 1, 0},
			{Math.cos(now * 3.0) * 0.72, 0.68, Math.sin(now * 3.0) * 0.72},
			{-Math.sin(now * 2.5) * 0.85, 0.52, Math.cos(now * 2.5) * 0.85}
		};
		for (int k = 0; k < norms.length; k++) {
			axesFrom(norms[k][0], norms[k][1], norms[k][2]);
			ringBand(b, ax, ay, az, Math.max(0.06, rr - thick), rr, k == 0 ? COL_NEON : COL_LIFT, a * (0.9f - k * 0.15f), 0f);
			ringBand(b, ax, ay, az, Math.max(0.04, rr - thick * 0.3), rr - thick * 0.05, COL_CORE, a * 0.75f, 0f);
		}
	}

	/** Anillos concéntricos cerrándose sobre el objetivo mientras se carga G. */
	private static void drawChargeRing(BufferBuilder b, double ax, double ay, double az, double bx, double by, double bz,
									   double r, double t, double fade, double now) {
		boolean upward = by > ay;
		axesFrom(0, 1, 0);
		double rr = r * (1.15 - 0.65 * t);
		double y = ay + (upward ? t * 2.4 : (1.0 - t) * 2.8);
		float a = (float) (0.78 * fade);
		int col = upward ? COL_LIFT : COL_NEON;
		ringBand(b, ax, y, az, Math.max(0.08, rr - 0.32), rr, col, a, 0.08f * a);
		ringBand(b, ax, ay + 0.06, az, Math.max(0.08, r * 0.85), r, col, a * 0.65f, 0f);
	}

	/** Anillos implosivos púrpura-oro para Prensa Minera ×100G, Atracción de Veta y Cristalización de Diamantes. */
	private static void drawHyperPress(BufferBuilder b, double ax, double ay, double az, double bx, double by, double bz,
									   double r, double t, double fade, double now) {
		double rr = Math.max(0.18, r * (1.15 - t * 1.05));
		float a = (float) (0.92 * fade);
		for (int k = 0; k < 3; k++) {
			double ang = now * 4.0 + k * 1.05;
			axesFrom(Math.cos(ang) * 0.65, 0.75 - k * 0.2, Math.sin(ang) * 0.65);
			ringBand(b, ax, ay, az, Math.max(0.05, rr * 0.76), rr, k == 0 ? COL_GOLD : COL_NEON, a * 0.9f, 0.1f * a);
		}
		// Si A != B (extracción de veta hacia el jugador), dibuja el rayo de atracción mineral
		double d2 = (bx - ax) * (bx - ax) + (by - ay) * (by - ay) + (bz - az) * (bz - az);
		if (d2 > 1.0) {
			ribbon(b, ax, ay, az, bx, by, bz, 0.24, 0.08, COL_GOLD, a * 0.75f, a * 0.35f);
			ribbon(b, ax, ay, az, bx, by, bz, 0.10, 0.03, COL_CORE, a * 0.95f, a * 0.65f);
		}
	}

	/** Vórtice gravitatorio giratorio que succiona en espiral hacia el centro. */
	private static void drawPullVortex(BufferBuilder b, double ax, double ay, double az, double r, double t, double fade,
									   double now, boolean calm) {
		axesFrom(0, 1, 0);
		float a = (float) (0.85 * fade);
		for (int k = 0; k < 4; k++) {
			double phase = ((t * 1.8 + k * 0.25) % 1.0);
			double rr = Math.max(0.25, r * (1.0 - phase * 0.82));
			float ra = (float) (Math.sin(phase * Math.PI) * a);
			ringBand(b, ax, ay + 0.15 + k * 0.35, az, Math.max(0.06, rr - 0.35), rr, k % 2 == 0 ? COL_NEON : COL_ABYSS, ra, 0.05f);
		}
		if (!calm) {
			for (int arm = 0; arm < 4; arm++) {
				double base = now * 4.5 + arm * (Math.PI * 0.5);
				double px = ax + Math.cos(base) * r * 0.95;
				double pz = az + Math.sin(base) * r * 0.95;
				for (int s = 1; s <= 5; s++) {
					double u = s / 5.0;
					double ang = base + u * 2.1;
					double rr = r * 0.95 * (1.0 - u * 0.85);
					double nx = ax + Math.cos(ang) * rr;
					double nz = az + Math.sin(ang) * rr;
					ribbon(b, px, ay + 0.4 + u * 0.8, pz, nx, ay + 0.4 + (u + 0.15) * 0.8, nz,
						0.18 * (1.0 - u * 0.5), 0.08, COL_LIFT, a * 0.7f, a * 0.25f);
					px = nx;
					pz = nz;
				}
			}
		}
	}

	// ------------------------------------------------------------------ efectos persistentes en vivo de Fujitora

	/** 2 anillos gravitatorios púrpura anclados directamente bajo la losa de roca real que lleva a Fujitora. */
	private static void drawRockSurfRings(BufferBuilder b, MinecraftClient mc, Vec3d cam, float td, double now, boolean calm) {
		if (!(mc.player.getVehicle() instanceof PieceEntity rock) || !rock.isGravitySurf()) return;
		Vec3d pos = rock.getLerpedPos(td);
		double cx = pos.x - cam.x, cy = pos.y - cam.y, cz = pos.z - cam.z;
		double pulse = calm ? 0.0 : 0.12 * Math.sin(now * 6.5);
		double spin = calm ? 0.0 : now * 3.8;

		// Anillo principal justo bajo la base de la losa 3×1×3
		axesFrom(Math.cos(spin) * 0.06, 0.998, Math.sin(spin) * 0.06);
		ringBand(b, cx, cy - 0.04, cz, 1.38 + pulse, 1.98 + pulse, COL_NEON, 0.80f, 0.05f);
		ringBand(b, cx, cy - 0.02, cz, 1.58 + pulse, 1.76 + pulse, COL_CORE, 0.88f, 0.15f);

		// Segundo anillo inferior de sustentación gravitatoria
		axesFrom(-Math.sin(spin * 1.4) * 0.08, 0.996, Math.cos(spin * 1.4) * 0.08);
		ringBand(b, cx, cy - 0.36, cz, 0.78 + pulse * 0.6, 1.36 + pulse * 0.6, COL_LIFT, 0.70f, 0f);
		ringBand(b, cx, cy - 0.62, cz, 0.35, 0.82, COL_ABYSS, 0.58f, 0f);
	}

	/** 3 anillos giroscópicos de Repulsión Orbital alrededor del cuerpo de Fujitora (tecla B). */
	private static void drawRepulsionBarrier(BufferBuilder b, MinecraftClient mc, Vec3d cam, float td, double now, boolean calm) {
		Vec3d pos = mc.player.getLerpedPos(td);
		double cx = pos.x - cam.x, cy = pos.y - cam.y + 1.05, cz = pos.z - cam.z;
		double spin = calm ? 0.5 : now * 3.2;
		double[][] axes = {
			{Math.cos(spin) * 0.42, 0.90, Math.sin(spin) * 0.42},
			{-Math.sin(spin * 1.3 + 1.0) * 0.78, 0.62, Math.cos(spin * 1.3 + 1.0) * 0.78},
			{Math.cos(-spin * 0.85 + 2.2) * 0.92, 0.36, Math.sin(-spin * 0.85 + 2.2) * 0.92}
		};
		int[] cols = {COL_NEON, COL_LIFT, COL_CORE};
		for (int k = 0; k < 3; k++) {
			axesFrom(axes[k][0], axes[k][1], axes[k][2]);
			double rr = 2.05 + k * 0.22;
			ringBand(b, cx, cy, cz, rr - 0.14, rr, cols[k], 0.62f - k * 0.10f, 0.08f);
		}
	}

	/** Anillo guía en vivo sobre el punto apuntado mientras mantienes G para canalizar presión gravitatoria. */
	private static void drawLiveChargeAim(BufferBuilder b, MinecraftClient mc, Vec3d cam, float td, double now, boolean calm) {
		Vec3d eye = mc.player.getEyePos();
		Vec3d look = mc.player.getRotationVec(td);
		HitResult hr = mc.player.raycast(52.0, td, false);
		Vec3d hit = hr != null && hr.getType() != HitResult.Type.MISS ? hr.getPos() : eye.add(look.multiply(26.0));
		double cx = hit.x - cam.x, cy = hit.y - cam.y + 0.08, cz = hit.z - cam.z;
		int vec = ClientState.gravityVector();
		int col = switch (vec) {
			case 1 -> COL_LIFT;
			case 2 -> COL_GOLD;
			case 3 -> 0xE030FF;
			default -> COL_NEON;
		};
		double pulse = calm ? 4.2 : 3.8 + 0.6 * Math.sin(now * 9.0);
		axesFrom(0, 1, 0);
		ringBand(b, cx, cy, cz, pulse - 0.32, pulse, col, 0.80f, 0.05f);
		ringBand(b, cx, cy + 0.04, cz, pulse * 0.45, pulse * 0.56, COL_CORE, 0.72f, 0f);
		double yOff = vec == 1 ? ((now * 4.0) % 4.0) : (4.0 - ((now * 4.0) % 4.0));
		ringBand(b, cx, cy + yOff, cz, pulse * 0.65, pulse * 0.82, col, 0.55f, 0f);
	}

	// ------------------------------------------------------------------ Haki de Observación Ciego (V — Pasada 2 sin profundidad)

	/**
	 * Visión del Almirante ciego Fujitora: auras 3D de seres vivos y proyectiles visibles a través de muros.
	 * Rojo = hostil · Oro = jefe (vida máx >= 80) · Celeste-Blanco = pasivo/aliado · Violeta eléctrico = proyectil.
	 */
	private static void drawBlindHakiAuras(BufferBuilder b, MinecraftClient mc, Vec3d cam, float td, double now, boolean calm) {
		Vec3d pPos = mc.player.getPos();
		Box area = new Box(pPos, pPos).expand(64.0);
		float pulse = calm ? 0.72f : (float) (0.62 + 0.22 * Math.sin(now * 5.5));
		double spin = calm ? 0.0 : now * 2.8;

		for (Entity ent : mc.world.getOtherEntities(mc.player, area, e -> e.isAlive() && !e.isSpectator())) {
			if (ent instanceof LivingEntity le) {
				int rgb;
				if (le.getMaxHealth() >= 80f) rgb = COL_GOLD;
				else if (le instanceof HostileEntity) rgb = 0xFF3344;
				else rgb = 0xA8E6FF;

				Vec3d pos = le.getLerpedPos(td);
				double cx = pos.x - cam.x;
				double cy = pos.y - cam.y;
				double cz = pos.z - cam.z;
				double hw = Math.max(0.32, le.getWidth() * 0.55);
				double h = Math.max(0.65, le.getHeight());

				// Núcleo luminoso de silueta + marco 3D visible tras paredes
				drawBoxOutline(b, cx, cy + h * 0.5, cz, hw, h * 0.5, rgb, pulse * 0.85f);
				drawBoxFill(b, cx, cy + h * 0.5, cz, hw * 0.72, h * 0.42, rgb, pulse * 0.24f);
				// Halo del alma en la base y corona
				axesFrom(0, 1, 0);
				ringBand(b, cx, cy + 0.06, cz, hw * 0.95, hw * 1.45, rgb, pulse * 0.75f, 0f);
				axesFrom(Math.cos(spin) * 0.35, 0.94, Math.sin(spin) * 0.35);
				ringBand(b, cx, cy + h * 0.55, cz, hw * 1.05, hw * 1.25, COL_CORE, pulse * 0.55f, 0f);
			} else if (ent instanceof ProjectileEntity pr && !pr.isOnGround()) {
				Vec3d pos = pr.getLerpedPos(td);
				double cx = pos.x - cam.x, cy = pos.y - cam.y, cz = pos.z - cam.z;
				drawBoxFill(b, cx, cy, cz, 0.22, 0.22, 0xD866FF, 0.75f);
				axesFrom(0, 1, 0);
				ringBand(b, cx, cy, cz, 0.28, 0.52, 0xF5CCFF, 0.82f, 0f);
			}
		}
	}

	// ------------------------------------------------------------------ Viñeta de Visión Ciega + Pulso Gravitatorio en HUD

	@SuppressWarnings("deprecation")
	public static void renderHud(DrawContext ctx, RenderTickCounter tc) {
		MinecraftClient mc = MinecraftClient.getInstance();
		if (mc.player == null || mc.options.hudHidden || !ClientState.gravityFruit()) return;
		int w = ctx.getScaledWindowWidth(), h = ctx.getScaledWindowHeight();

		// 1) Viñeta cósmica oscura alrededor de la pantalla mientras el Haki Ciego está activo
		if (ClientState.scanTicks > 0) {
			float fadeIn = MathHelper.clamp(ClientState.scanTicks / 20.0f, 0.15f, 1.0f);
			int aEdge = Math.round(145 * fadeIn);
			int cEdge = (aEdge << 24) | 0x10021E;
			int cMid  = ((aEdge / 3) << 24) | 0x1E0436;
			int cZero = 0x0010021E;
			int vBand = Math.max(24, h / 5);
			int hBand = Math.max(24, w / 6);

			ctx.fillGradient(RenderLayer.getGuiOverlay(), 0, 0, w, vBand / 2, cEdge, cMid, 0);
			ctx.fillGradient(RenderLayer.getGuiOverlay(), 0, vBand / 2, w, vBand, cMid, cZero, 0);
			ctx.fillGradient(RenderLayer.getGuiOverlay(), 0, h - vBand, w, h - vBand / 2, cZero, cMid, 0);
			ctx.fillGradient(RenderLayer.getGuiOverlay(), 0, h - vBand / 2, w, h, cMid, cEdge, 0);

			for (int i = 0; i < hBand; i += 2) {
				float k = 1f - i / (float) hBand;
				int a = Math.round(aEdge * k * k * 0.85f);
				if (a <= 2) break;
				int col = (a << 24) | 0x140324;
				ctx.fill(RenderLayer.getGuiOverlay(), i, vBand / 2, i + 2, h - vBand / 2, col);
				ctx.fill(RenderLayer.getGuiOverlay(), w - i - 2, vBand / 2, w - i, h - vBand / 2, col);
			}
		}

		// 2) Pulso púrpura/fuego en los bordes tras impactos gravitatorios fuertes
		int level = OpeSettings.flashLevel();
		double age = RoomFxClient.time() - screenPulseStart;
		if (level > 0 && age >= 0 && age < 0.34 && screenPulseStrength > 0.01f) {
			float u = (float) (age / 0.34);
			float alpha = screenPulseStrength * (1f - u) * (1f - u) * (level >= 2 ? 1f : 0.45f);
			int band = Math.max(14, Math.min(w, h) / 9);
			int cTop = (Math.round(alpha * 255f) << 24) | (screenPulseRgb & 0xFFFFFF);
			int cZero = screenPulseRgb & 0xFFFFFF;
			ctx.fillGradient(RenderLayer.getGuiOverlay(), 0, 0, w, band, cTop, cZero, 0);
			ctx.fillGradient(RenderLayer.getGuiOverlay(), 0, h - band, w, h, cZero, cTop, 0);
		} else if (age >= 0.34) {
			screenPulseStrength = 0f;
		}
	}

	// ------------------------------------------------------------------ geometría auxiliar

	private static void drawJaggedBolt(BufferBuilder b, double ax, double ay, double az, double bx, double by, double bz,
									   double outerW, double innerW, int outerRgb, int innerRgb, float alpha,
									   int seed, int segs, double jitter) {
		double dx = bx - ax, dy = by - ay, dz = bz - az;
		double len = Math.sqrt(dx * dx + dy * dy + dz * dz);
		if (len < 0.2) return;
		axesFrom(dx / len, dy / len, dz / len);
		double lx = ax, ly = ay, lz = az;
		for (int i = 1; i <= segs; i++) {
			double s = i / (double) segs;
			double env = Math.sin(s * Math.PI);
			double rx = hashSigned(seed + i * 19) * jitter * env;
			double ry = hashSigned(seed + i * 37) * jitter * env;
			double nx = i == segs ? bx : ax + dx * s + ax1x * rx + ax2x * ry;
			double ny = i == segs ? by : ay + dy * s + ax1y * rx + ax2y * ry;
			double nz = i == segs ? bz : az + dz * s + ax1z * rx + ax2z * ry;
			ribbon(b, lx, ly, lz, nx, ny, nz, outerW, outerW, outerRgb, alpha * 0.55f, alpha * 0.55f);
			ribbon(b, lx, ly, lz, nx, ny, nz, innerW, innerW, innerRgb, alpha, alpha);
			lx = nx; ly = ny; lz = nz;
		}
	}

	private static void drawCylinder(BufferBuilder b, double cx, double y0, double cz, double y1, double r, int rgb, float a) {
		int sides = 12;
		for (int i = 0; i < sides; i++) {
			double a0 = Math.PI * 2.0 * i / sides, a1 = Math.PI * 2.0 * (i + 1) / sides;
			double x0 = cx + Math.cos(a0) * r, z0 = cz + Math.sin(a0) * r;
			double x1 = cx + Math.cos(a1) * r, z1 = cz + Math.sin(a1) * r;
			vtx(b, x0, y0, z0, rgb, a);
			vtx(b, x1, y0, z1, rgb, a);
			vtx(b, x1, y1, z1, rgb, 0f);
			vtx(b, x0, y1, z0, rgb, 0f);
		}
	}

	private static void drawBoxFill(BufferBuilder b, double cx, double cy, double cz, double hw, double hh, int rgb, float a) {
		double x0 = cx - hw, x1 = cx + hw, y0 = cy - hh, y1 = cy + hh, z0 = cz - hw, z1 = cz + hw;
		vtx(b, x0, y0, z1, rgb, a); vtx(b, x1, y0, z1, rgb, a); vtx(b, x1, y1, z1, rgb, a); vtx(b, x0, y1, z1, rgb, a);
		vtx(b, x1, y0, z0, rgb, a); vtx(b, x0, y0, z0, rgb, a); vtx(b, x0, y1, z0, rgb, a); vtx(b, x1, y1, z0, rgb, a);
		vtx(b, x0, y0, z0, rgb, a); vtx(b, x0, y0, z1, rgb, a); vtx(b, x0, y1, z1, rgb, a); vtx(b, x0, y1, z0, rgb, a);
		vtx(b, x1, y0, z1, rgb, a); vtx(b, x1, y0, z0, rgb, a); vtx(b, x1, y1, z0, rgb, a); vtx(b, x1, y1, z1, rgb, a);
		vtx(b, x0, y1, z1, rgb, a); vtx(b, x1, y1, z1, rgb, a); vtx(b, x1, y1, z0, rgb, a); vtx(b, x0, y1, z0, rgb, a);
		vtx(b, x0, y0, z0, rgb, a); vtx(b, x1, y0, z0, rgb, a); vtx(b, x1, y0, z1, rgb, a); vtx(b, x0, y0, z1, rgb, a);
	}

	private static void drawBoxOutline(BufferBuilder b, double cx, double cy, double cz, double hw, double hh, int rgb, float a) {
		double t = 0.032;
		double x0 = cx - hw, x1 = cx + hw, y0 = cy - hh, y1 = cy + hh, z0 = cz - hw, z1 = cz + hw;
		// 4 aristas verticales
		ribbon(b, x0, y0, z0, x0, y1, z0, t, t, rgb, a, a);
		ribbon(b, x1, y0, z0, x1, y1, z0, t, t, rgb, a, a);
		ribbon(b, x0, y0, z1, x0, y1, z1, t, t, rgb, a, a);
		ribbon(b, x1, y0, z1, x1, y1, z1, t, t, rgb, a, a);
		// 4 aristas superiores + 4 inferiores
		ribbon(b, x0, y1, z0, x1, y1, z0, t, t, rgb, a, a);
		ribbon(b, x1, y1, z0, x1, y1, z1, t, t, rgb, a, a);
		ribbon(b, x1, y1, z1, x0, y1, z1, t, t, rgb, a, a);
		ribbon(b, x0, y1, z1, x0, y1, z0, t, t, rgb, a, a);
		ribbon(b, x0, y0, z0, x1, y0, z0, t, t, rgb, a, a);
		ribbon(b, x1, y0, z0, x1, y0, z1, t, t, rgb, a, a);
		ribbon(b, x1, y0, z1, x0, y0, z1, t, t, rgb, a, a);
		ribbon(b, x0, y0, z1, x0, y0, z0, t, t, rgb, a, a);
	}

	private static void axesFrom(double nx, double ny, double nz) {
		double l = Math.sqrt(nx * nx + ny * ny + nz * nz);
		if (l < 1e-6) {
			nx = 0; ny = 1; nz = 0;
		} else {
			nx /= l; ny /= l; nz /= l;
		}
		double rx = Math.abs(ny) < 0.9 ? 0 : 1, ry = Math.abs(ny) < 0.9 ? 1 : 0, rz = 0;
		double cx = ny * rz - nz * ry, cy = nz * rx - nx * rz, cz = nx * ry - ny * rx;
		double cl = Math.sqrt(cx * cx + cy * cy + cz * cz);
		if (cl < 1e-6) {
			ax1x = 1; ax1y = 0; ax1z = 0;
		} else {
			ax1x = cx / cl; ax1y = cy / cl; ax1z = cz / cl;
		}
		ax2x = ny * ax1z - nz * ax1y;
		ax2y = nz * ax1x - nx * ax1z;
		ax2z = nx * ax1y - ny * ax1x;
	}

	private static void ringBand(BufferBuilder b, double cx, double cy, double cz, double rIn, double rOut,
								 int rgb, float aIn, float aOut) {
		for (int i = 0; i < RING_SEG; i++) {
			double c0 = COS[i], s0 = SIN[i], c1 = COS[i + 1], s1 = SIN[i + 1];
			vtx(b, cx + (ax1x * c0 + ax2x * s0) * rIn, cy + (ax1y * c0 + ax2y * s0) * rIn, cz + (ax1z * c0 + ax2z * s0) * rIn, rgb, aIn);
			vtx(b, cx + (ax1x * c0 + ax2x * s0) * rOut, cy + (ax1y * c0 + ax2y * s0) * rOut, cz + (ax1z * c0 + ax2z * s0) * rOut, rgb, aOut);
			vtx(b, cx + (ax1x * c1 + ax2x * s1) * rOut, cy + (ax1y * c1 + ax2y * s1) * rOut, cz + (ax1z * c1 + ax2z * s1) * rOut, rgb, aOut);
			vtx(b, cx + (ax1x * c1 + ax2x * s1) * rIn, cy + (ax1y * c1 + ax2y * s1) * rIn, cz + (ax1z * c1 + ax2z * s1) * rIn, rgb, aIn);
		}
	}

	private static void ribbon(BufferBuilder b, double ax, double ay, double az, double bx, double by, double bz,
							   double w0, double w1, int rgb, float a0, float a1) {
		double mx = (ax + bx) * 0.5, my = (ay + by) * 0.5, mz = (az + bz) * 0.5;
		double dx = bx - ax, dy = by - ay, dz = bz - az;
		double px = dy * mz - dz * my, py = dz * mx - dx * mz, pz = dx * my - dy * mx;
		double pl = Math.sqrt(px * px + py * py + pz * pz);
		if (pl < 1e-6) return;
		px /= pl; py /= pl; pz /= pl;
		vtx(b, ax - px * w0, ay - py * w0, az - pz * w0, rgb, a0);
		vtx(b, ax + px * w0, ay + py * w0, az + pz * w0, rgb, a0);
		vtx(b, bx + px * w1, by + py * w1, bz + pz * w1, rgb, a1);
		vtx(b, bx - px * w1, by - py * w1, bz - pz * w1, rgb, a1);
	}

	private static double hashSigned(int x) {
		x = ((x >>> 16) ^ x) * 0x45d9f3b;
		x = ((x >>> 16) ^ x) * 0x45d9f3b;
		x = (x >>> 16) ^ x;
		return ((x & 0xFFFF) / 32767.5) - 1.0;
	}

	private static void vtx(BufferBuilder b, double x, double y, double z, int rgb, float a) {
		a = MathHelper.clamp(a, 0f, 1f);
		float fx = (float) x, fy = (float) y, fz = (float) z;
		if (!plain) {
			BASE.transformPosition(fx, fy, fz, TV);
			fx = TV.x; fy = TV.y; fz = TV.z;
		}
		b.vertex(fx, fy, fz).color((rgb >> 16) & 255, (rgb >> 8) & 255, rgb & 255, Math.round(a * 255f));
	}
}
