package dev.opeope.client.render;

import dev.opeope.client.ClientState;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.entity.LivingEntity;
import net.minecraft.util.math.MathHelper;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;

/**
 * Seres vivos en piezas. Las caras del modelo se reparten según de qué lado de cada plano caen; el lado de arriba se
 * separa un poco y flota. Funciona con cualquier mob (y jugadores) porque trabaja sobre los vértices, no sobre el modelo.
 */
public final class CutRendering {
	private CutRendering() {}

	public static VertexConsumerProvider wrap(VertexConsumerProvider base, LivingEntity e, float tickDelta, MatrixStack ms) {
		float[] planes = ClientState.CUTS.get(e.getId());
		if (planes == null || planes.length < 5) return base;
		Matrix4f m = new Matrix4f(ms.peek().getPositionMatrix());
		Matrix4f inv = new Matrix4f(m).invert();
		float bodyYaw = MathHelper.lerpAngleDegrees(tickDelta, e.prevBodyYaw, e.bodyYaw);
		int n = planes.length / 5;
		float[] world = new float[n * 5];
		double a = Math.toRadians(-bodyYaw), c = Math.cos(a), s = Math.sin(a);
		for (int i = 0; i < n; i++) {
			float nx = planes[i * 5], ny = planes[i * 5 + 1], nz = planes[i * 5 + 2];
			world[i * 5] = (float) (nx * c + nz * s);
			world[i * 5 + 1] = ny;
			world[i * 5 + 2] = (float) (-nx * s + nz * c);
			world[i * 5 + 3] = planes[i * 5 + 3];
			world[i * 5 + 4] = planes[i * 5 + 4];
		}
		float time = e.age + tickDelta;
		return layer -> new CutConsumer(base.getBuffer(layer), m, inv, world, time);
	}

	static final class CutConsumer implements VertexConsumer {
		private final VertexConsumer d;
		private final Matrix4f m, inv;
		private final float[] planes;
		private final float time;
		private final float[] buf = new float[4 * 8];
		private final int[] ibuf = new int[4 * 3];
		private int count;

		CutConsumer(VertexConsumer delegate, Matrix4f m, Matrix4f inv, float[] planes, float time) {
			this.d = delegate;
			this.m = m;
			this.inv = inv;
			this.planes = planes;
			this.time = time;
		}

		@Override
		public void vertex(float x, float y, float z, int color, float u, float v, int overlay, int light, float nx, float ny, float nz) {
			int o = count * 8;
			buf[o] = x; buf[o + 1] = y; buf[o + 2] = z; buf[o + 3] = u; buf[o + 4] = v;
			buf[o + 5] = nx; buf[o + 6] = ny; buf[o + 7] = nz;
			ibuf[count * 3] = color; ibuf[count * 3 + 1] = overlay; ibuf[count * 3 + 2] = light;
			if (++count == 4) {
				emit();
				count = 0;
			}
		}

		private void emit() {
			float cx = 0, cy = 0, cz = 0;
			for (int i = 0; i < 4; i++) {
				cx += buf[i * 8];
				cy += buf[i * 8 + 1];
				cz += buf[i * 8 + 2];
			}
			Vector4f local = inv.transform(new Vector4f(cx / 4, cy / 4, cz / 4, 1));
			float ox = 0, oy = 0, oz = 0;
			int n = planes.length / 5;
			for (int i = 0; i < n; i++) {
				float px = planes[i * 5], py = planes[i * 5 + 1], pz = planes[i * 5 + 2], pd = planes[i * 5 + 3], gap = planes[i * 5 + 4];
				if (px * local.x + py * local.y + pz * local.z - pd > 0) {
					float g = gap + 0.04f * MathHelper.sin(time * 0.15f + i * 1.7f);
					ox += px * g;
					oy += py * g + 0.05f;
					oz += pz * g;
				}
			}
			Vector3f off = m.transformDirection(new Vector3f(ox, oy, oz));
			for (int i = 0; i < 4; i++) {
				int o = i * 8;
				d.vertex(buf[o] + off.x, buf[o + 1] + off.y, buf[o + 2] + off.z, ibuf[i * 3], buf[o + 3], buf[o + 4],
					ibuf[i * 3 + 1], ibuf[i * 3 + 2], buf[o + 5], buf[o + 6], buf[o + 7]);
			}
		}

		@Override
		public VertexConsumer vertex(float x, float y, float z) {
			d.vertex(x, y, z);
			return this;
		}

		@Override
		public VertexConsumer color(int r, int g, int b, int a) {
			d.color(r, g, b, a);
			return this;
		}

		@Override
		public VertexConsumer texture(float u, float v) {
			d.texture(u, v);
			return this;
		}

		@Override
		public VertexConsumer overlay(int u, int v) {
			d.overlay(u, v);
			return this;
		}

		@Override
		public VertexConsumer light(int u, int v) {
			d.light(u, v);
			return this;
		}

		@Override
		public VertexConsumer normal(float x, float y, float z) {
			d.normal(x, y, z);
			return this;
		}
	}
}
