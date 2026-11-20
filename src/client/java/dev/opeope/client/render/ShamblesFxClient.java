package dev.opeope.client.render;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import dev.opeope.client.ClientState;
import dev.opeope.client.config.OpeSettings;
import dev.opeope.client.input.InputHandler;
import dev.opeope.client.room.CameraFx;
import dev.opeope.client.room.RoomFxClient;
import dev.opeope.client.room.ScreenFx;
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
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.EntityHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.RotationAxis;
import net.minecraft.util.math.Vec3d;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * Efecto espacial 3D + pantalla para {@code Shambles}:
 * <ul>
 *   <li>Silueta holográfica en el origen que se disgrega en lonchas de fase viajando por un arco curvo hacia el destino.</li>
 *   <li>Portales giroscópicos dobles, columna de luz de materialización y onda expansiva en el suelo.</li>
 *   <li>Hélice doble luminosa + arcos eléctricos quebrados conectando origen y destino.</li>
 *   <li>Vista previa espacial 3D del objetivo marcado o punto de aterrizaje en el ROOM y túnel iris en 1.ª persona.</li>
 * </ul>
 */
public final class ShamblesFxClient {
	private ShamblesFxClient() {}

	private static final double WARP_DUR = 0.65;
	private static final double HUD_DUR = 0.28;
	private static final int RING_SEG = 28;
	private static final float[] COS = new float[RING_SEG + 1], SIN = new float[RING_SEG + 1];

	static {
		for (int i = 0; i <= RING_SEG; i++) {
			double a = Math.PI * 2.0 * i / RING_SEG;
			COS[i] = (float) Math.cos(a);
			SIN[i] = (float) Math.sin(a);
		}
	}

	private record WarpEvent(
		int kind, int userId, int entityA, int entityB,
		double ax, double ay, double az, float aw, float ah, float ayaw,
		double bx, double by, double bz, float bw, float bh, float byaw,
		double startTime, int seed
	) {}

	private static final List<WarpEvent> WARPS = new ArrayList<>();
	private static int markedEntityId = -1;
	private static double markStartTime = -100;
	private static double selfWarpStart = -100;

	private static final Matrix4f BASE = new Matrix4f();
	private static boolean plain;
	private static final Vector3f TV = new Vector3f();
	private static double ax1x, ax1y, ax1z, ax2x, ax2y, ax2z;

	public static void receive(Payloads.ShamblesFx p) {
		if (p == null) return;
		double now = RoomFxClient.time();
		if (p.kind() == Payloads.ShamblesFx.KIND_CLEAR_MARK) {
			markedEntityId = -1;
			return;
		}
		if (p.kind() == Payloads.ShamblesFx.KIND_MARK) {
			markedEntityId = p.entityA();
			markStartTime = now;
			return;
		}
		int seed = (int) (Float.floatToIntBits((float) p.ax()) * 31 + Float.floatToIntBits((float) p.bz()) * 17 + System.nanoTime());
		WARPS.add(new WarpEvent(
			p.kind(), p.userId(), p.entityA(), p.entityB(),
			p.ax(), p.ay(), p.az(), p.aw(), p.ah(), p.ayaw(),
			p.bx(), p.by(), p.bz(), p.bw(), p.bh(), p.byaw(),
			now, seed
		));
		while (WARPS.size() > 16) WARPS.remove(0);

		MinecraftClient mc = MinecraftClient.getInstance();
		if (mc.player != null && p.userId() == mc.player.getId()) {
			PlayerAnimClient.triggerShamblesGesture();
			if (p.kind() == Payloads.ShamblesFx.KIND_SELF_TP
				|| p.kind() == Payloads.ShamblesFx.KIND_SELF_SWAP
				|| p.kind() == Payloads.ShamblesFx.KIND_TACTICAL) {
				selfWarpStart = now;
				CameraFx.fov(-6.2f, 0.035f, 0.28f);
				CameraFx.kick(-1.1f, 0f, 2.1f);
				ScreenFx.edgePulse(0.36f, 0.32f);
				ScreenFx.flash(0.08f, 0.16f);
				ScreenFx.streaks();
			} else {
				CameraFx.fov(-2.2f, 0.04f, 0.20f);
				ScreenFx.edgePulse(0.22f, 0.22f);
			}
		}
	}

	public static void clear() {
		WARPS.clear();
		markedEntityId = -1;
		markStartTime = -100;
		selfWarpStart = -100;
	}

	public static int activeCount() {
		double now = RoomFxClient.time();
		WARPS.removeIf(w -> now - w.startTime > WARP_DUR || now < w.startTime - 1.0);
		return WARPS.size();
	}

	public static void renderWorld(WorldRenderContext ctx) {
		MinecraftClient mc = MinecraftClient.getInstance();
		if (mc.world == null || mc.player == null) return;
		double now = RoomFxClient.time();
		Iterator<WarpEvent> it = WARPS.iterator();
		while (it.hasNext()) {
			double age = now - it.next().startTime;
			if (age < 0 || age > WARP_DUR) it.remove();
		}
		boolean showPreview = ClientState.ownRoom() != null && (markedEntityId >= 0 || InputHandler.isShamblesDown());
		if (WARPS.isEmpty() && !showPreview) return;

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
			BufferBuilder b = Tessellator.getInstance().begin(VertexFormat.DrawMode.QUADS, VertexFormats.POSITION_COLOR);
			for (WarpEvent w : WARPS) {
				drawWarp(b, mc, w, cam, now, calm);
			}
			if (showPreview) {
				drawLivePreview(b, mc, cam, td, now);
			}
			var built = b.endNullable();
			if (built != null) BufferRenderer.drawWithGlobalProgram(built);
		} finally {
			RenderSystem.defaultBlendFunc();
			RenderSystem.depthMask(true);
			RenderSystem.enableCull();
			RenderSystem.disableBlend();
		}
	}

	private static void drawWarp(BufferBuilder b, MinecraftClient mc, WarpEvent w, Vec3d cam, double now, boolean calm) {
		double t = MathHelper.clamp((now - w.startTime) / WARP_DUR, 0.0, 1.0);
		double fade = 1.0 - t * t;
		boolean swap = w.kind == Payloads.ShamblesFx.KIND_SELF_SWAP
			|| w.kind == Payloads.ShamblesFx.KIND_ENTITY_SWAP
			|| w.kind == Payloads.ShamblesFx.KIND_TACTICAL;
		double ax = w.ax - cam.x, ay = w.ay - cam.y, az = w.az - cam.z;
		double bx = w.bx - cam.x, by = w.by - cam.y, bz = w.bz - cam.z;
		double hA = Math.max(0.7, w.ah), wA = Math.max(0.45, w.aw);
		double hB = Math.max(0.7, w.bh), wB = Math.max(0.45, w.bw);

		// 1) Silueta holográfica en el origen (se disgrega) y destello de llegada
		if (t < 0.45) {
			float sAlpha = (float) (0.58 * (1.0 - t / 0.45));
			double expand = 1.0 + t * 0.25;
			drawSilhouette(b, ax, ay, az, wA * expand, hA, w.ayaw, 0x3FD8FF, sAlpha * 0.85f);
			drawSilhouette(b, ax, ay, az, wA * (1.0 + t * 0.08), hA, w.ayaw, 0xE8FFFF, sAlpha * 0.45f);
			if (swap) {
				boolean localFirstPersonAtB = mc.player != null && w.userId == mc.player.getId()
					&& mc.options.getPerspective().isFirstPerson();
				if (!localFirstPersonAtB) {
					drawSilhouette(b, bx, by, bz, wB * expand, hB, w.byaw, 0x6FE6FF, sAlpha * 0.55f);
				}
			}
		}

		// 2) Lonchas espaciales de fase viajando por arco Bézier de A -> B
		if (!calm && t < 0.72) {
			Vec3d pA = new Vec3d(ax, ay + hA * 0.5, az);
			Vec3d pB = new Vec3d(bx, by + hB * 0.5, bz);
			Vec3d diff = pB.subtract(pA);
			double dist = diff.length();
			if (dist > 0.25 && dist < 160) {
				Vec3d up = new Vec3d(0, 1, 0);
				Vec3d side = diff.crossProduct(up);
				if (side.lengthSquared() < 1e-4) side = new Vec3d(1, 0, 0);
				side = side.normalize();
				float sign = (w.seed & 1) == 0 ? 1f : -1f;
				Vec3d ctrl = pA.add(pB).multiply(0.5).add(0, 0.7 + Math.min(4.5, dist * 0.14), 0)
					.add(side.multiply(sign * Math.min(2.6, dist * 0.12)));

				int slices = 7;
				for (int s = 0; s < slices; s++) {
					double uRaw = (t * 1.65) - s * 0.032;
					if (uRaw <= 0 || uRaw >= 1) continue;
					double u = uRaw * uRaw * (3.0 - 2.0 * uRaw);
					Vec3d cur = bezier(pA, ctrl, pB, u);
					double uPrev = Math.max(0.0, u - 0.11);
					Vec3d prev = bezier(pA, ctrl, pB, uPrev);
					float sAlpha = (float) (Math.sin(uRaw * Math.PI) * 0.85 * fade);
					double halfS = (0.28 - 0.12 * Math.abs(u - 0.5)) * (wA + 0.35);
					int col = (s % 2 == 0) ? 0x6FE6FF : 0xFFFFFF;
					ribbon(b, prev.x, prev.y, prev.z, cur.x, cur.y, cur.z, halfS * 0.9, halfS * 0.28, col, sAlpha * 0.35f, sAlpha);
					axesFrom(0, 1, 0);
					ringBand(b, cur.x, cur.y + (s - 3) * (hA * 0.09), cur.z, halfS * 0.45, halfS * 0.95, 0xBFF4FF, sAlpha * 0.7f, 0f);
				}
			}
		}

		// 3) Portales giroscópicos dobles en A (implosión) y B (expansión) + onda y columna en B
		drawPortal(b, ax, ay, az, wA, hA, t, fade, false, w.seed, calm);
		drawPortal(b, bx, by, bz, wB, hB, t, fade, true, w.seed + 19, calm);

		if (t < 0.55) {
			double uCol = t / 0.55;
			float cAlpha = (float) (Math.sin(uCol * Math.PI) * 0.52);
			double rCol = (0.38 + 0.26 * uCol) * Math.max(0.75, wB);
			double topY = by + hB * (1.25 + 0.85 * uCol);
			drawColumn(b, bx, by, bz, topY, rCol, 0x44DDFF, cAlpha * 0.6f);
			drawColumn(b, bx, by, bz, topY * 0.92 + by * 0.08, rCol * 0.45, 0xE8FFFF, cAlpha * 0.85f);
		}

		// 4) Hélice doble luminosa + 2 rayos eléctricos quebrados entre origen y destino
		if (t < 0.70) {
			double len = Math.sqrt((bx - ax) * (bx - ax) + (by - ay) * (by - ay) + (bz - az) * (bz - az));
			if (len > 0.4 && len < 160) {
				double uConn = 1.0 - t / 0.70;
				drawDoubleHelixAndBolts(b, ax, ay + hA * 0.5, az, bx, by + hB * 0.5, bz, uConn, now, w.seed, calm);
			}
		}
	}

	private static void drawPortal(BufferBuilder b, double cx, double cy, double cz, double ew, double eh,
								   double t, double fade, boolean arriving, int seed, boolean calm) {
		double rBase = Math.max(0.7, ew * 1.15);
		// Onda expansiva en el suelo
		double shockR = arriving ? (0.35 + t * 2.4) * rBase : (1.2 - t * 0.85) * rBase;
		if (shockR > 0.12) {
			axesFrom(0, 1, 0);
			float sa = (float) (0.72 * fade * (arriving ? 1.0 : 0.7));
			ringBand(b, cx, cy + 0.04, cz, Math.max(0.05, shockR * 0.78), shockR, 0x77EEFF, sa, 0f);
			ringBand(b, cx, cy + 0.05, cz, Math.max(0.05, shockR * 0.90), shockR * 0.98, 0xFFFFFF, sa * 0.8f, 0f);
		}
		// 3 anillos giroscópicos inclinados alrededor del centro de la entidad
		double spin = t * (calm ? 1.2 : 7.5);
		double pcY = cy + eh * 0.52;
		double rScale = arriving ? (0.45 + 0.85 * Math.min(1.0, t * 2.5)) : (1.15 - 0.75 * t);
		double r1 = Math.max(0.25, rBase * 0.85 * rScale);
		double[][] axes = {
			{Math.cos(spin + seed) * 0.48, 0.88, Math.sin(spin + seed) * 0.48},
			{-Math.sin(spin * 1.3 + 1.1) * 0.75, 0.55, Math.cos(spin * 1.3 + 1.1) * 0.75},
			{Math.cos(-spin * 0.9 + 2.3) * 0.92, 0.25, Math.sin(-spin * 0.9 + 2.3) * 0.92}
		};
		int[] cols = {0x3FD8FF, 0xBFF4FF, 0xFFFFFF};
		for (int k = 0; k < 3; k++) {
			axesFrom(axes[k][0], axes[k][1], axes[k][2]);
			float ra = (float) ((0.65 - 0.12 * k) * fade);
			double rr = r1 * (1.0 + k * 0.16);
			ringBand(b, cx, pcY, cz, Math.max(0.05, rr - 0.11), rr, cols[k], ra, ra * 0.15f);
		}
	}

	private static void drawDoubleHelixAndBolts(BufferBuilder b, double ax, double ay, double az,
												 double bx, double by, double bz, double uFade,
												 double now, int seed, boolean calm) {
		double dx = bx - ax, dy = by - ay, dz = bz - az;
		double len = Math.sqrt(dx * dx + dy * dy + dz * dz);
		double fx = dx / len, fy = dy / len, fz = dz / len;
		axesFrom(fx, fy, fz);

		int segs = calm ? 12 : 24;
		double p1x = ax, p1y = ay, p1z = az;
		double p2x = ax, p2y = ay, p2z = az;
		float alpha = (float) (0.85 * uFade);
		for (int i = 1; i <= segs; i++) {
			double s = i / (double) segs;
			double ang = s * Math.PI * 5.0 - now * 10.0 + seed;
			double rad = Math.sin(s * Math.PI) * Math.min(0.68, 0.28 + len * 0.035);
			double ox = (ax1x * Math.cos(ang) + ax2x * Math.sin(ang)) * rad;
			double oy = (ax1y * Math.cos(ang) + ax2y * Math.sin(ang)) * rad;
			double oz = (ax1z * Math.cos(ang) + ax2z * Math.sin(ang)) * rad;
			double q1x = ax + dx * s + ox, q1y = ay + dy * s + oy, q1z = az + dz * s + oz;
			double q2x = ax + dx * s - ox, q2y = ay + dy * s - oy, q2z = az + dz * s - oz;
			double hw = 0.065 * Math.max(0.35, Math.sin(s * Math.PI));
			ribbon(b, p1x, p1y, p1z, q1x, q1y, q1z, hw * 2.2, hw * 2.2, 0x38D8FF, alpha * 0.45f, alpha * 0.45f);
			ribbon(b, p1x, p1y, p1z, q1x, q1y, q1z, hw, hw, 0xFFFFFF, alpha, alpha);
			ribbon(b, p2x, p2y, p2z, q2x, q2y, q2z, hw * 1.8, hw * 1.8, 0x9F7BFF, alpha * 0.40f, alpha * 0.40f);
			p1x = q1x; p1y = q1y; p1z = q1z;
			p2x = q2x; p2y = q2y; p2z = q2z;
		}

		// 2 relámpagos espaciales quebrados
		int flicker = (int) (now * 24.0);
		int bSegs = 11;
		for (int bolt = 0; bolt < 2; bolt++) {
			double lx = ax, ly = ay, lz = az;
			for (int i = 1; i <= bSegs; i++) {
				double s = i / (double) bSegs;
				double env = Math.sin(s * Math.PI);
				double jScale = env * Math.min(0.95, 0.25 + len * 0.06);
				double rx = hashSigned(seed + bolt * 97 + i * 13 + flicker * 7) * jScale;
				double ry = hashSigned(seed + bolt * 53 + i * 29 + flicker * 11) * jScale;
				double nx = i == bSegs ? bx : ax + dx * s + ax1x * rx + ax2x * ry;
				double ny = i == bSegs ? by : ay + dy * s + ax1y * rx + ax2y * ry;
				double nz = i == bSegs ? bz : az + dz * s + ax1z * rx + ax2z * ry;
				ribbon(b, lx, ly, lz, nx, ny, nz, 0.11, 0.11, 0x44EEFF, alpha * 0.45f, alpha * 0.45f);
				ribbon(b, lx, ly, lz, nx, ny, nz, 0.036, 0.036, 0xFFFFFF, alpha * 0.95f, alpha * 0.95f);
				lx = nx; ly = ny; lz = nz;
			}
		}
	}

	private static void drawLivePreview(BufferBuilder b, MinecraftClient mc, Vec3d cam, float td, double now) {
		ClientState.RoomInfo room = ClientState.ownRoom();
		if (room == null || mc.player == null || mc.world == null) return;
		Vec3d eye = mc.player.getEyePos();
		Vec3d look = mc.player.getRotationVec(td);

		Vec3d markCenter = null;
		if (markedEntityId >= 0) {
			Entity me = mc.world.getEntityById(markedEntityId);
			if (me != null && me.isAlive() && ClientState.inOwnRoom(me.getBoundingBox().getCenter())) {
				Vec3d p = me.getLerpedPos(td);
				double cx = p.x - cam.x, cy = p.y - cam.y + me.getHeight() * 0.5, cz = p.z - cam.z;
				markCenter = new Vec3d(cx, cy, cz);
				double rad = Math.max(0.65, me.getWidth() * 0.95);
				double spin = now * 3.4;
				axesFrom(Math.cos(spin) * 0.4, 0.92, Math.sin(spin) * 0.4);
				ringBand(b, cx, cy, cz, rad, rad + 0.09, 0xFFD966, 0.72f, 0.15f);
				axesFrom(0, 1, 0);
				ringBand(b, cx, p.y - cam.y + 0.04, cz, rad * 0.85, rad * 1.15, 0x55E6FF, 0.65f, 0f);
			}
		}

		if (InputHandler.isShamblesDown()) {
			Vec3d destWorld = estimatePreviewPoint(mc, room, eye, look, td);
			if (destWorld != null) {
				double cx = destWorld.x - cam.x, cy = destWorld.y - cam.y + 0.05, cz = destWorld.z - cam.z;
				axesFrom(0, 1, 0);
				double pulse = 0.62 + 0.10 * Math.sin(now * 9.0);
				ringBand(b, cx, cy, cz, pulse * 0.72, pulse, 0x6FE6FF, 0.78f, 0.1f);
				ringBand(b, cx, cy + 0.02, cz, 0.10, pulse * 0.45, 0xFFFFFF, 0.55f, 0f);
				if (markCenter != null) {
					double hw = 0.045;
					ribbon(b, markCenter.x, markCenter.y, markCenter.z, cx, cy + 0.8, cz, hw, hw, 0x80F0FF, 0.55f, 0.55f);
				}
			}
		}
	}

	private static Vec3d estimatePreviewPoint(MinecraftClient mc, ClientState.RoomInfo room, Vec3d eye, Vec3d look, float td) {
		if (mc.crosshairTarget instanceof EntityHitResult ehr && ClientState.shamblesMode() != 0) {
			Vec3d c = ehr.getEntity().getBoundingBox().getCenter();
			if (ClientState.inOwnRoom(c)) return ehr.getEntity().getLerpedPos(td);
		}
		if (mc.crosshairTarget instanceof BlockHitResult bhr && bhr.getType() == HitResult.Type.BLOCK
			&& ClientState.inOwnRoom(bhr.getPos())) {
			return bhr.getPos();
		}
		double r = Math.max(0.5, room.radius() - 0.65);
		Vec3d oc = eye.subtract(room.center());
		double b = oc.dotProduct(look);
		double c = oc.lengthSquared() - r * r;
		double disc = b * b - c;
		if (disc < 0) return null;
		double t = Math.min(120.0, -b + Math.sqrt(disc));
		return t > 0.5 ? eye.add(look.multiply(t)) : null;
	}

	@SuppressWarnings("deprecation")
	public static void renderHud(DrawContext ctx, RenderTickCounter tc) {
		MinecraftClient mc = MinecraftClient.getInstance();
		if (mc.player == null || mc.options.hudHidden) return;
		double age = RoomFxClient.time() - selfWarpStart;
		if (age < 0 || age >= HUD_DUR || OpeSettings.reducedMotion()) return;
		int level = OpeSettings.flashLevel();
		if (level <= 0) return;

		double u = age / HUD_DUR;
		double fade = (1.0 - u) * (1.0 - u);
		float soft = level >= 2 ? 1.0f : 0.35f;
		int w = ctx.getScaledWindowWidth(), h = ctx.getScaledWindowHeight();
		float cx = w / 2f, cy = h / 2f;
		float rad = Math.min(w, h) * (float) (0.14 + 0.44 * u);
		int ringAlpha = (int) MathHelper.clamp(190 * fade * soft, 0, 255);
		int streakAlpha = (int) MathHelper.clamp(155 * fade * soft, 0, 255);
		if (ringAlpha <= 2 && streakAlpha <= 2) return;

		int segs = 16;
		int colRing = (ringAlpha << 24) | 0xA8F4FF;
		int colCore = (Math.min(255, (int) (ringAlpha * 1.25f)) << 24) | 0xFFFFFF;
		int colStreak = (streakAlpha << 24) | 0x6CE6FF;

		for (int i = 0; i < segs; i++) {
			float a0 = (float) (Math.PI * 2.0 * i / segs + u * 0.45);
			ctx.getMatrices().push();
			try {
				ctx.getMatrices().translate(cx, cy, 0);
				ctx.getMatrices().multiply(RotationAxis.POSITIVE_Z.rotation(a0));
				int segLen = Math.max(8, Math.round(rad * 0.42f));
				ctx.fill(RenderLayer.getGuiOverlay(), Math.round(rad), -1, Math.round(rad) + segLen, 2, colRing);
				ctx.fill(RenderLayer.getGuiOverlay(), Math.round(rad) + 2, 0, Math.round(rad) + segLen - 2, 1, colCore);
				int s0 = Math.round(14 + rad * 0.35f);
				int s1 = s0 + (int) Math.round(18 + 38 * (1.0 - u));
				ctx.fill(RenderLayer.getGuiOverlay(), s0, 0, s1, 1, colStreak);
			} finally {
				ctx.getMatrices().pop();
			}
		}
	}

	// ------------------------------------------------------------------ geometría auxiliar

	private static void drawSilhouette(BufferBuilder b, double fx, double fy, double fz, double w, double h, float yawDeg, int rgb, float alpha) {
		if (alpha <= 0.01f) return;
		double s = h / 1.80;
		double rad = Math.toRadians(180f - yawDeg);
		double cos = Math.cos(rad), sin = Math.sin(rad);
		double rw = Math.max(0.7, w / 0.6);
		// Piernas, torso, brazos y cabeza estilizados como holograma hueco
		drawRotBox(b, fx, fy, fz, cos, sin, -0.11 * s * rw, 0.0, -0.10 * s, 0.09 * s * rw, 0.72 * s, 0.10 * s, rgb, alpha * 0.85f);
		drawRotBox(b, fx, fy, fz, cos, sin, 0.02 * s * rw, 0.0, -0.10 * s, 0.21 * s * rw, 0.72 * s, 0.10 * s, rgb, alpha * 0.85f);
		drawRotBox(b, fx, fy, fz, cos, sin, -0.22 * s * rw, 0.72 * s, -0.11 * s, 0.22 * s * rw, 1.38 * s, 0.11 * s, rgb, alpha);
		drawRotBox(b, fx, fy, fz, cos, sin, -0.40 * s * rw, 0.75 * s, -0.09 * s, -0.23 * s * rw, 1.38 * s, 0.09 * s, rgb, alpha * 0.8f);
		drawRotBox(b, fx, fy, fz, cos, sin, 0.23 * s * rw, 0.75 * s, -0.09 * s, 0.40 * s * rw, 1.38 * s, 0.09 * s, rgb, alpha * 0.8f);
		drawRotBox(b, fx, fy, fz, cos, sin, -0.19 * s * rw, 1.39 * s, -0.19 * s, 0.19 * s * rw, 1.78 * s, 0.19 * s, rgb, alpha * 1.1f);
	}

	private static void drawRotBox(BufferBuilder b, double cx, double cy, double cz, double cos, double sin,
								   double x0, double y0, double z0, double x1, double y1, double z1, int rgb, float a) {
		double[][] c = new double[8][3];
		for (int i = 0; i < 8; i++) {
			double lx = (i & 1) == 0 ? x0 : x1;
			double ly = (i & 2) == 0 ? y0 : y1;
			double lz = (i & 4) == 0 ? z0 : z1;
			c[i][0] = cx + lx * cos + lz * sin;
			c[i][1] = cy + ly;
			c[i][2] = cz - lx * sin + lz * cos;
		}
		int[][] faces = {{0, 1, 3, 2}, {5, 4, 6, 7}, {4, 0, 2, 6}, {1, 5, 7, 3}, {2, 3, 7, 6}, {4, 5, 1, 0}};
		for (int[] f : faces) {
			vtx(b, c[f[0]][0], c[f[0]][1], c[f[0]][2], rgb, a);
			vtx(b, c[f[1]][0], c[f[1]][1], c[f[1]][2], rgb, a);
			vtx(b, c[f[2]][0], c[f[2]][1], c[f[2]][2], rgb, a);
			vtx(b, c[f[3]][0], c[f[3]][1], c[f[3]][2], rgb, a);
		}
	}

	private static void drawColumn(BufferBuilder b, double cx, double y0, double cz, double y1, double r, int rgb, float a) {
		int sides = 10;
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

	private static Vec3d bezier(Vec3d a, Vec3d c, Vec3d b, double u) {
		double m = 1.0 - u;
		return a.multiply(m * m).add(c.multiply(2.0 * m * u)).add(b.multiply(u * u));
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
