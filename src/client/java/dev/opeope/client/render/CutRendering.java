package dev.opeope.client.render;

import dev.opeope.client.ClientState;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.entity.LivingEntity;
import net.minecraft.util.math.MathHelper;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;

import java.util.ArrayList;
import java.util.List;

/**
 * Seres vivos en piezas. Cada cara del modelo se recorta por los planos y cada trozo va con su lado; el lado de arriba se
 * separa un poco y flota, y el borde del corte brilla. Funciona con cualquier mob (y jugadores) porque trabaja sobre los vértices, no sobre el modelo.
 */
public final class CutRendering {
	private CutRendering() {}

	// La costura de los seres cortados: se junta mientras se dibuja el modelo y se pinta al final (otra capa).
	private static VertexConsumerProvider seamBase;
	private static final List<float[]> SEAMS = new ArrayList<>();

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
		seamBase = base;
		SEAMS.clear();
		return layer -> new CutConsumer(base.getBuffer(layer), m, inv, world, time);
	}

	/** Al terminar de dibujar el ser: la costura brillante en el borde de cada parte. */
	public static void flush() {
		VertexConsumerProvider base = seamBase;
		seamBase = null;
		if (base == null || SEAMS.isEmpty()) {
			SEAMS.clear();
			return;
		}
		VertexConsumer vc = base.getBuffer(RenderLayer.getLightning());
		for (float[] q : SEAMS) {
			for (int i = 0; i < 4; i++) vc.vertex(q[i * 3], q[i * 3 + 1], q[i * 3 + 2]).color((int) q[12], (int) q[13], (int) q[14], (int) q[15]);
		}
		SEAMS.clear();
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

		// Un vértice de polígono: x y z (dibujo), lx ly lz (cuerpo), u v, nx ny nz
		private static final int V = 11;

		/** Lo que se aparta cada lado: por cada plano del que está en el lado de arriba, su separación. */
		private Vector3f offset(int mask) {
			float ox = 0, oy = 0, oz = 0;
			int n = planes.length / 5;
			for (int i = 0; i < n; i++) {
				if (((mask >> i) & 1) == 0) continue;
				float px = planes[i * 5], py = planes[i * 5 + 1], pz = planes[i * 5 + 2], gap = planes[i * 5 + 4];
				float g = gap + 0.04f * MathHelper.sin(time * 0.15f + i * 1.7f);
				ox += px * g;
				oy += py * g + 0.05f;
				oz += pz * g;
			}
			return m.transformDirection(new Vector3f(ox, oy, oz));
		}

		private float side(float[] vtx, int o, int i) {
			return planes[i * 5] * vtx[o + 3] + planes[i * 5 + 1] * vtx[o + 4] + planes[i * 5 + 2] * vtx[o + 5] - planes[i * 5 + 3];
		}

		/**
		 * Cada cara del modelo se recorta por los planos (no se mueve entera): cada trozo va con su lado, así el corte es
		 * limpio y recto aunque cruce un brazo o la cabeza. Donde el plano corta la cara queda una costura que brilla.
		 */
		private void emit() {
			float[] quad = new float[4 * V];
			for (int i = 0; i < 4; i++) {
				int o = i * 8, q = i * V;
				Vector4f l = inv.transform(new Vector4f(buf[o], buf[o + 1], buf[o + 2], 1));
				quad[q] = buf[o]; quad[q + 1] = buf[o + 1]; quad[q + 2] = buf[o + 2];
				quad[q + 3] = l.x; quad[q + 4] = l.y; quad[q + 5] = l.z;
				quad[q + 6] = buf[o + 3]; quad[q + 7] = buf[o + 4];
				quad[q + 8] = buf[o + 5]; quad[q + 9] = buf[o + 6]; quad[q + 10] = buf[o + 7];
			}
			List<float[]> polys = new ArrayList<>();
			List<Integer> masks = new ArrayList<>();
			polys.add(quad);
			masks.add(0);
			int np = planes.length / 5;
			for (int pi = 0; pi < np; pi++) {
				List<float[]> nextP = new ArrayList<>();
				List<Integer> nextM = new ArrayList<>();
				for (int k = 0; k < polys.size(); k++) {
					float[] poly = polys.get(k);
					int mask = masks.get(k);
					int nv = poly.length / V;
					boolean anyPos = false, anyNeg = false;
					float[] ds = new float[nv];
					for (int j = 0; j < nv; j++) {
						ds[j] = side(poly, j * V, pi);
						if (ds[j] > 1e-5f) anyPos = true;
						else if (ds[j] < -1e-5f) anyNeg = true;
					}
					if (!anyNeg) {
						nextP.add(poly);
						nextM.add(anyPos ? mask | (1 << pi) : mask);
						continue;
					}
					if (!anyPos) {
						nextP.add(poly);
						nextM.add(mask);
						continue;
					}
					// partir el polígono (Sutherland–Hodgman) y guardar los dos puntos de la costura
					float[] pos = new float[(nv + 2) * V], neg = new float[(nv + 2) * V];
					int cp = 0, cn = 0;
					float[] cut = new float[2 * V];
					int cc = 0;
					for (int j = 0; j < nv; j++) {
						int a = j * V, b = ((j + 1) % nv) * V;
						float da = ds[j], db = ds[(j + 1) % nv];
						if (da >= 0) System.arraycopy(poly, a, pos, (cp++) * V, V);
						if (da <= 0) System.arraycopy(poly, a, neg, (cn++) * V, V);
						if ((da > 0 && db < 0) || (da < 0 && db > 0)) {
							float t = da / (da - db);
							float[] x = new float[V];
							for (int c = 0; c < V; c++) x[c] = poly[a + c] + (poly[b + c] - poly[a + c]) * t;
							System.arraycopy(x, 0, pos, (cp++) * V, V);
							System.arraycopy(x, 0, neg, (cn++) * V, V);
							if (cc < 2) System.arraycopy(x, 0, cut, (cc++) * V, V);
						}
					}
					if (cp >= 3) {
						float[] r = new float[cp * V];
						System.arraycopy(pos, 0, r, 0, r.length);
						nextP.add(r);
						nextM.add(mask | (1 << pi));
					}
					if (cn >= 3) {
						float[] r = new float[cn * V];
						System.arraycopy(neg, 0, r, 0, r.length);
						nextP.add(r);
						nextM.add(mask);
					}
					if (cc == 2) seam(cut, mask, pi, poly);
				}
				polys = nextP;
				masks = nextM;
			}
			for (int k = 0; k < polys.size(); k++) {
				float[] poly = polys.get(k);
				Vector3f off = offset(masks.get(k));
				int nv = poly.length / V;
				// en abanico de cuadriláteros (un triángulo es un cuadrilátero con el último vértice repetido)
				for (int j = 1; j + 1 < nv; j += 2) {
					int c = Math.min(j + 2, nv - 1);
					put(poly, 0, off);
					put(poly, j, off);
					put(poly, j + 1, off);
					put(poly, c, off);
				}
			}
		}

		private void put(float[] poly, int j, Vector3f off) {
			int o = j * V;
			d.vertex(poly[o] + off.x, poly[o + 1] + off.y, poly[o + 2] + off.z, ibuf[0], poly[o + 6], poly[o + 7],
				ibuf[1], ibuf[2], poly[o + 8], poly[o + 9], poly[o + 10]);
		}

		/** Una cinta fina de luz en el borde del corte, a los dos lados (cada uno con su separación). */
		private void seam(float[] cut, int mask, int pi, float[] poly) {
			if (SEAMS.size() > 4000) return;
			float ax = cut[0], ay = cut[1], az = cut[2], bx = cut[V], by = cut[V + 1], bz = cut[V + 2];
			// la normal de la cara (en el espacio de dibujo) para que la cinta quede pegada a ella
			float fx = poly[8], fy = poly[9], fz = poly[10];
			Vector3f fn = m.transformDirection(new Vector3f(fx, fy, fz));
			if (fn.lengthSquared() < 1e-8f) return;
			fn.normalize();
			float dx = bx - ax, dy = by - ay, dz = bz - az;
			float px = dy * fn.z - dz * fn.y, py = dz * fn.x - dx * fn.z, pz = dx * fn.y - dy * fn.x;
			float len = (float) Math.sqrt(px * px + py * py + pz * pz);
			if (len < 1e-6f) return;
			float w = 0.018f / len;
			px *= w;
			py *= w;
			pz *= w;
			float lift = 0.004f;
			for (int sideBit = 0; sideBit < 2; sideBit++) {
				Vector3f off = offset(sideBit == 1 ? mask | (1 << pi) : mask);
				float ox = off.x + fn.x * lift, oy = off.y + fn.y * lift, oz = off.z + fn.z * lift;
				SEAMS.add(new float[]{
					ax - px + ox, ay - py + oy, az - pz + oz,
					bx - px + ox, by - py + oy, bz - pz + oz,
					bx + px + ox, by + py + oy, bz + pz + oz,
					ax + px + ox, ay + py + oy, az + pz + oz,
					170, 235, 255, 230});
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
