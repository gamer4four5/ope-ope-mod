package dev.opeope.client.render;

import com.mojang.blaze3d.systems.RenderSystem;
import dev.opeope.client.ClientState;
import dev.opeope.client.input.Bisturi;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.BufferBuilder;
import net.minecraft.client.render.BufferRenderer;
import net.minecraft.client.render.GameRenderer;
import net.minecraft.client.render.Tessellator;
import net.minecraft.client.render.VertexFormat;
import net.minecraft.client.render.VertexFormats;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import org.joml.Matrix4f;

/** La esfera del ROOM (con borde brillante, como el fresnel del juego) y la superficie del Bisturí. */
public final class RoomRenderer {
	private RoomRenderer() {}

	private static final int LAT = 28, LON = 56;

	private static boolean isIdentity(Matrix4f m) {
		float e = 1e-4f;
		return Math.abs(m.m00() - 1) < e && Math.abs(m.m11() - 1) < e && Math.abs(m.m22() - 1) < e
			&& Math.abs(m.m01()) < e && Math.abs(m.m02()) < e && Math.abs(m.m10()) < e
			&& Math.abs(m.m12()) < e && Math.abs(m.m20()) < e && Math.abs(m.m21()) < e;
	}

	public static void render(WorldRenderContext ctx) {
		MinecraftClient mc = MinecraftClient.getInstance();
		if (mc.world == null) return;
		boolean anyRoom = false;
		String dim = mc.world.getRegistryKey().getValue().toString();
		for (ClientState.RoomInfo r : ClientState.ROOMS.values()) if (r.dim().toString().equals(dim)) anyRoom = true;
		if (!anyRoom && !Bisturi.active) return;

		Vec3d cam = ctx.camera().getPos();
		// Según la versión de Fabric, la rotación de la cámara va en la matriz del modelo o no: se mira y se añade si falta.
		Matrix4f base = isIdentity(RenderSystem.getModelViewMatrix()) ? new Matrix4f(ctx.positionMatrix()) : new Matrix4f();
		float tickDelta = ctx.tickCounter().getTickDelta(true);

		RenderSystem.enableBlend();
		RenderSystem.defaultBlendFunc();
		RenderSystem.disableCull();
		RenderSystem.enableDepthTest();
		RenderSystem.depthMask(false);
		RenderSystem.setShader(GameRenderer::getPositionColorProgram);
		BufferBuilder buf = Tessellator.getInstance().begin(VertexFormat.DrawMode.QUADS, VertexFormats.POSITION_COLOR);
		boolean any = false;
		for (ClientState.RoomInfo r : ClientState.ROOMS.values()) {
			if (!r.dim().toString().equals(dim)) continue;
			float radius = MathHelper.lerp(tickDelta, r.prevRadius(), r.radius());
			boolean mine = mc.player != null && r.owner().equals(mc.player.getUuid());
			sphere(buf, base, r.center().subtract(cam), radius, r.slot() == 1 ? 0xB8F0FF : 0x8FD8FF, mine ? 1f : 0.8f);
			any = true;
		}
		if (Bisturi.active) {
			for (double[] pl : Bisturi.COMMITTED) plane(buf, base, pl, cam, 0x3FE8FBFF);
			double[] cur = Bisturi.current(tickDelta);
			if (cur != null) plane(buf, base, cur, cam, 0x70BFF4FF);
			any = true;
		}
		var built = buf.endNullable();
		if (any && built != null) BufferRenderer.drawWithGlobalProgram(built);
		RenderSystem.depthMask(true);
		RenderSystem.enableCull();
		RenderSystem.disableBlend();
	}

	private static void sphere(BufferBuilder buf, Matrix4f m, Vec3d c, float r, int rgb, float strength) {
		int red = (rgb >> 16) & 255, green = (rgb >> 8) & 255, blue = rgb & 255;
		for (int i = 0; i < LAT; i++) {
			double t0 = Math.PI * i / LAT, t1 = Math.PI * (i + 1) / LAT;
			for (int j = 0; j < LON; j++) {
				double p0 = 2 * Math.PI * j / LON, p1 = 2 * Math.PI * (j + 1) / LON;
				vtx(buf, m, c, r, t0, p0, red, green, blue, strength);
				vtx(buf, m, c, r, t1, p0, red, green, blue, strength);
				vtx(buf, m, c, r, t1, p1, red, green, blue, strength);
				vtx(buf, m, c, r, t0, p1, red, green, blue, strength);
			}
		}
	}

	private static void vtx(BufferBuilder buf, Matrix4f m, Vec3d c, float r, double theta, double phi, int red, int green, int blue, float strength) {
		double nx = Math.sin(theta) * Math.cos(phi), ny = Math.cos(theta), nz = Math.sin(theta) * Math.sin(phi);
		double x = c.x + nx * r, y = c.y + ny * r, z = c.z + nz * r;
		double len = Math.sqrt(x * x + y * y + z * z);
		double facing = len < 1e-4 ? 1 : Math.abs((nx * x + ny * y + nz * z) / len);
		double fres = 1 - facing;
		float alpha = (float) (0.05 + 0.55 * fres * fres * fres) * strength;
		buf.vertex(m, (float) x, (float) y, (float) z).color(red, green, blue, (int) (alpha * 255));
	}

	private static void plane(BufferBuilder buf, Matrix4f m, double[] pl, Vec3d cam, int argb) {
		Vec3d p = new Vec3d(pl[3], pl[4], pl[5]);
		Vec3d s = new Vec3d(pl[6], pl[7], pl[8]);
		Vec3d f = new Vec3d(pl[9], pl[10], pl[11]);
		Vec3d c = p.add(f.multiply(9)).subtract(cam);
		double a = 12, b = 8;
		int al = (argb >>> 24) & 255, red = (argb >> 16) & 255, green = (argb >> 8) & 255, blue = argb & 255;
		Vec3d[] q = {
			c.add(s.multiply(-a)).add(f.multiply(-b)), c.add(s.multiply(a)).add(f.multiply(-b)),
			c.add(s.multiply(a)).add(f.multiply(b)), c.add(s.multiply(-a)).add(f.multiply(b))};
		for (Vec3d v : q) buf.vertex(m, (float) v.x, (float) v.y, (float) v.z).color(red, green, blue, al);
	}
}
