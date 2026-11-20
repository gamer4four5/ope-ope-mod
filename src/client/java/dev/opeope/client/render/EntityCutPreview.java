package dev.opeope.client.render;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import dev.opeope.client.input.Bisturi;
import dev.opeope.client.input.FreehandSlicer;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.render.BufferBuilder;
import net.minecraft.client.render.BufferRenderer;
import net.minecraft.client.render.GameRenderer;
import net.minecraft.client.render.RenderTickCounter;
import net.minecraft.client.render.Tessellator;
import net.minecraft.client.render.VertexFormat;
import net.minecraft.client.render.VertexFormats;
import net.minecraft.client.util.InputUtil;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import org.joml.Matrix4f;
import org.lwjgl.glfw.GLFW;

import java.util.List;

/**
 * Vista previa holográfica 3D para las formas del Bisturí (Cubo/Ventana, Cilindro/Túnel, Esfera, Cuña en V, Rodajas ×3,
 * Cruz ×4, cortes pendientes congelados) y lazo 2D en pantalla mientras dibujas a mano alzada.
 */
public final class EntityCutPreview {
	private EntityCutPreview() {}

	public static void render(WorldRenderContext ctx) {
		if (!Bisturi.active && Bisturi.COMMITTED_SHAPES.isEmpty()) return;
		MinecraftClient mc = MinecraftClient.getInstance();
		if (mc.player == null) return;

		Bisturi.AimFrame af = Bisturi.active && Bisturi.mode != Bisturi.Mode.PLANE && !FreehandSlicer.active()
			? Bisturi.aimFrame(ctx.tickCounter().getTickDelta(false)) : null;
		if (af == null && Bisturi.COMMITTED_SHAPES.isEmpty()) return;

		Vec3d cam = ctx.camera().getPos();
		Matrix4f m = CutFx.isIdentity(RenderSystem.getModelViewMatrix()) ? new Matrix4f(ctx.positionMatrix()) : new Matrix4f();

		RenderSystem.enableBlend();
		RenderSystem.blendFunc(GlStateManager.SrcFactor.SRC_ALPHA, GlStateManager.DstFactor.ONE);
		RenderSystem.disableCull();
		RenderSystem.enableDepthTest();
		RenderSystem.depthMask(false);
		RenderSystem.setShader(GameRenderer::getPositionColorProgram);

		BufferBuilder buf = Tessellator.getInstance().begin(VertexFormat.DrawMode.DEBUG_LINES, VertexFormats.POSITION_COLOR);
		try {
			float pulse = 0.78f + 0.22f * MathHelper.sin((mc.player.age + ctx.tickCounter().getTickDelta(false)) * 0.35f);
			int aLive = (int) (220 * pulse);

			for (Bisturi.CommittedShape cs : Bisturi.COMMITTED_SHAPES) {
				drawCommitted(buf, m, cam, cs, 185, 240, 255, 235);
			}
			if (af != null) {
				long h = mc.getWindow().getHandle();
				boolean shift = InputUtil.isKeyPressed(h, GLFW.GLFW_KEY_LEFT_SHIFT) || InputUtil.isKeyPressed(h, GLFW.GLFW_KEY_RIGHT_SHIFT);
				boolean thru = !shift;
				drawActiveShape(buf, m, cam, Bisturi.mode, af, Bisturi.shapeRadius, thru, 115, 225, 255, aLive);
			}
		} finally {
			var built = buf.endNullable();
			if (built != null) BufferRenderer.drawWithGlobalProgram(built);
			RenderSystem.depthMask(true);
			RenderSystem.enableCull();
			RenderSystem.defaultBlendFunc();
			RenderSystem.disableBlend();
		}
	}

	private static void drawActiveShape(BufferBuilder buf, Matrix4f m, Vec3d cam, Bisturi.Mode mode, Bisturi.AimFrame af,
										float rad, boolean thru, int r, int g, int b, int a) {
		Vec3d c = af.pos(), rx = af.right(), uy = af.up(), fz = af.look();
		switch (mode) {
			case BOX -> drawBox(buf, m, cam, c, rx, uy, fz, rad, rad, thru ? 1.5f : rad, r, g, b, a);
			case CYLINDER -> drawCylinder(buf, m, cam, c, fz, rx, uy, rad, thru ? 1.5f : rad * 1.3f, r, g, b, a);
			case SPHERE -> drawSphere(buf, m, cam, c, rad, r, g, b, a);
			case WEDGE -> drawWedge(buf, m, cam, c, rx, uy, fz, rad, 1.5f, r, g, b, a);
			case SLICES_3 -> {
				double sp = Math.max(0.20f, rad * 0.85f);
				Vec3d u1 = fz, u2 = rx;
				drawPlaneQuad(buf, m, cam, c.subtract(af.normal().multiply(sp)), u1, u2, 0.9f, r, g, b, a);
				drawPlaneQuad(buf, m, cam, c, u1, u2, 0.9f, r, g, b, a);
				drawPlaneQuad(buf, m, cam, c.add(af.normal().multiply(sp)), u1, u2, 0.9f, r, g, b, a);
			}
			case CROSS_4 -> {
				Vec3d n1 = af.normal();
				Vec3d e1 = fz, e2 = rx.crossProduct(n1).lengthSquared() > 1e-4 ? n1.crossProduct(fz).normalize() : uy;
				drawPlaneQuad(buf, m, cam, c, e1, e2, 0.95f, r, g, b, a);
				drawPlaneQuad(buf, m, cam, c, e1, n1, 0.95f, r, g, b, a);
			}
			default -> {}
		}
	}

	private static void drawCommitted(BufferBuilder buf, Matrix4f m, Vec3d cam, Bisturi.CommittedShape cs, int r, int g, int b, int a) {
		switch (cs.mode()) {
			case BOX -> drawBox(buf, m, cam, cs.center(), cs.r(), cs.u(), cs.f(), cs.radius(), cs.radius(), cs.thru() ? 1.5f : cs.radius(), r, g, b, a);
			case CYLINDER -> drawCylinder(buf, m, cam, cs.center(), cs.f(), cs.r(), cs.u(), cs.radius(), cs.thru() ? 1.5f : cs.radius() * 1.3f, r, g, b, a);
			case SPHERE -> drawSphere(buf, m, cam, cs.center(), cs.radius(), r, g, b, a);
			case WEDGE -> drawWedge(buf, m, cam, cs.center(), cs.r(), cs.u(), cs.f(), cs.radius(), 1.5f, r, g, b, a);
			case FREEHAND -> {
				List<Vec3d> poly = cs.freePoly();
				if (poly != null && poly.size() >= 3) {
					for (int i = 0; i < poly.size(); i++) {
						line(buf, m, cam, poly.get(i), poly.get((i + 1) % poly.size()), r, g, b, a);
					}
				}
			}
			default -> drawBox(buf, m, cam, cs.center(), cs.r(), cs.u(), cs.f(), 0.7f, 0.7f, 0.7f, r, g, b, a);
		}
	}

	private static void drawBox(BufferBuilder buf, Matrix4f m, Vec3d cam, Vec3d c, Vec3d rx, Vec3d uy, Vec3d fz,
								float hx, float hy, float hz, int r, int g, int b, int a) {
		Vec3d[] pts = new Vec3d[8];
		for (int i = 0; i < 8; i++) {
			double sx = (i & 1) == 0 ? -hx : hx;
			double sy = (i & 2) == 0 ? -hy : hy;
			double sz = (i & 4) == 0 ? -hz : hz;
			pts[i] = c.add(rx.multiply(sx)).add(uy.multiply(sy)).add(fz.multiply(sz));
		}
		int[][] edges = {{0, 1}, {2, 3}, {4, 5}, {6, 7}, {0, 2}, {1, 3}, {4, 6}, {5, 7}, {0, 4}, {1, 5}, {2, 6}, {3, 7}};
		for (int[] e : edges) line(buf, m, cam, pts[e[0]], pts[e[1]], r, g, b, a);
	}

	private static void drawCylinder(BufferBuilder buf, Matrix4f m, Vec3d cam, Vec3d c, Vec3d ax, Vec3d rx, Vec3d uy,
									 float rad, float hl, int r, int g, int b, int a) {
		int sides = 20;
		for (int s = -1; s <= 1; s += 2) {
			Vec3d cc = c.add(ax.multiply(s * hl));
			for (int i = 0; i < sides; i++) {
				double t0 = 2 * Math.PI * i / sides, t1 = 2 * Math.PI * (i + 1) / sides;
				Vec3d p0 = cc.add(rx.multiply(Math.cos(t0) * rad)).add(uy.multiply(Math.sin(t0) * rad));
				Vec3d p1 = cc.add(rx.multiply(Math.cos(t1) * rad)).add(uy.multiply(Math.sin(t1) * rad));
				line(buf, m, cam, p0, p1, r, g, b, a);
			}
		}
		for (int i = 0; i < 4; i++) {
			double th = Math.PI * 0.5 * i;
			Vec3d off = rx.multiply(Math.cos(th) * rad).add(uy.multiply(Math.sin(th) * rad));
			line(buf, m, cam, c.add(off).subtract(ax.multiply(hl)), c.add(off).add(ax.multiply(hl)), r, g, b, a);
		}
	}

	private static void drawSphere(BufferBuilder buf, Matrix4f m, Vec3d cam, Vec3d c, float rad, int r, int g, int b, int a) {
		int segs = 24;
		for (int i = 0; i < segs; i++) {
			double t0 = 2 * Math.PI * i / segs, t1 = 2 * Math.PI * (i + 1) / segs;
			double c0 = Math.cos(t0) * rad, s0 = Math.sin(t0) * rad, c1 = Math.cos(t1) * rad, s1 = Math.sin(t1) * rad;
			line(buf, m, cam, c.add(c0, 0, s0), c.add(c1, 0, s1), r, g, b, a);
			line(buf, m, cam, c.add(c0, s0, 0), c.add(c1, s1, 0), r, g, b, a);
			line(buf, m, cam, c.add(0, c0, s0), c.add(0, c1, s1), r, g, b, a);
		}
	}

	private static void drawWedge(BufferBuilder buf, Matrix4f m, Vec3d cam, Vec3d c, Vec3d rx, Vec3d uy, Vec3d fz,
								  float rad, float hl, int r, int g, int b, int a) {
		float w = rad * 2.2f, h = rad * 1.3f;
		for (int s = -1; s <= 1; s += 2) {
			Vec3d p0 = c.add(fz.multiply(s * hl));
			Vec3d p1 = p0.add(rx.multiply(w)).add(uy.multiply(-h));
			Vec3d p2 = p0.add(rx.multiply(w)).add(uy.multiply(h));
			line(buf, m, cam, p0, p1, r, g, b, a);
			line(buf, m, cam, p1, p2, r, g, b, a);
			line(buf, m, cam, p2, p0, r, g, b, a);
		}
		Vec3d d = fz.multiply(hl);
		line(buf, m, cam, c.subtract(d), c.add(d), r, g, b, a);
		Vec3d b1 = c.add(rx.multiply(w)).add(uy.multiply(-h));
		Vec3d b2 = c.add(rx.multiply(w)).add(uy.multiply(h));
		line(buf, m, cam, b1.subtract(d), b1.add(d), r, g, b, a);
		line(buf, m, cam, b2.subtract(d), b2.add(d), r, g, b, a);
	}

	private static void drawPlaneQuad(BufferBuilder buf, Matrix4f m, Vec3d cam, Vec3d c, Vec3d u, Vec3d v, float h,
									  int r, int g, int b, int a) {
		Vec3d p0 = c.subtract(u.multiply(h)).subtract(v.multiply(h));
		Vec3d p1 = c.add(u.multiply(h)).subtract(v.multiply(h));
		Vec3d p2 = c.add(u.multiply(h)).add(v.multiply(h));
		Vec3d p3 = c.subtract(u.multiply(h)).add(v.multiply(h));
		line(buf, m, cam, p0, p1, r, g, b, a);
		line(buf, m, cam, p1, p2, r, g, b, a);
		line(buf, m, cam, p2, p3, r, g, b, a);
		line(buf, m, cam, p3, p0, r, g, b, a);
	}

	private static void line(BufferBuilder buf, Matrix4f m, Vec3d cam, Vec3d a, Vec3d b, int r, int g, int bl, int al) {
		buf.vertex(m, (float) (a.x - cam.x), (float) (a.y - cam.y), (float) (a.z - cam.z)).color(r, g, bl, al);
		buf.vertex(m, (float) (b.x - cam.x), (float) (b.y - cam.y), (float) (b.z - cam.z)).color(r, g, bl, al);
	}

	// ------------------------------------------------------------------ HUD: modo de Bisturí + Lazo Libre 2D

	public static void renderHud(DrawContext ctx, RenderTickCounter tc) {
		MinecraftClient mc = MinecraftClient.getInstance();
		if (mc.player == null || mc.options.hudHidden) return;
		int w = ctx.getScaledWindowWidth(), h = ctx.getScaledWindowHeight();
		int cx = w / 2, cy = h / 2;

		if (FreehandSlicer.active()) {
			List<float[]> pts = FreehandSlicer.screenPoints(w, h);
			for (int i = 0; i + 1 < pts.size(); i++) {
				drawLine2D(ctx, cx + pts.get(i)[0], cy + pts.get(i)[1], cx + pts.get(i + 1)[0], cy + pts.get(i + 1)[1], 0xFF8FD8FF);
			}
			if (pts.size() >= 3) {
				float[] first = pts.get(0), last = pts.get(pts.size() - 1);
				drawLine2D(ctx, cx + last[0], cy + last[1], cx + first[0], cy + first[1], 0x80BFEFFF);
			}
			for (float[] p : pts) {
				int px = Math.round(cx + p[0]), py = Math.round(cy + p[1]);
				ctx.fill(px - 1, py - 1, px + 2, py + 2, 0xFFFFFFFF);
			}
		}

		if (Bisturi.active) {
			int pending = Bisturi.COMMITTED.size() + Bisturi.COMMITTED_SHAPES.size();
			String radiusStr = Bisturi.mode == Bisturi.Mode.PLANE ? ""
				: String.format(java.util.Locale.ROOT, " · Radio %.2fm", Bisturi.shapeRadius);
			String title = "✂ BISTURÍ · " + Bisturi.mode.label + radiusStr + (pending > 0 ? " · Cortes: " + pending : "");
			String help = "Izq: Cortar · Alt+Rueda/Medio: Forma · Rueda: Girar · Ctrl+Rueda: Tamaño · Soltar Der: Ejecutar";
			int tw = mc.textRenderer.getWidth(title);
			int hw = mc.textRenderer.getWidth(help);
			int boxW = Math.max(tw, hw) + 14;
			int bx = cx - boxW / 2;
			int by = Math.max(8, h - 68);
			ctx.fill(bx, by, bx + boxW, by + 26, 0x990A141E);
			ctx.fill(bx, by, bx + boxW, by + 1, 0xAA8FD8FF);
			ctx.drawTextWithShadow(mc.textRenderer, title, cx - tw / 2, by + 4, 0xFF8FD8FF);
			ctx.drawTextWithShadow(mc.textRenderer, help, cx - hw / 2, by + 15, 0xFFBFD4E0);
		}
	}

	private static void drawLine2D(DrawContext ctx, float x0, float y0, float x1, float y1, int color) {
		float dx = x1 - x0, dy = y1 - y0;
		int steps = Math.max(1, (int) Math.ceil(Math.hypot(dx, dy) / 2.0));
		for (int i = 0; i <= steps; i++) {
			float t = i / (float) steps;
			int px = Math.round(x0 + dx * t), py = Math.round(y0 + dy * t);
			ctx.fill(px - 1, py - 1, px + 1, py + 1, color);
		}
	}
}
