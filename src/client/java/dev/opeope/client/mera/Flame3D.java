package dev.opeope.client.mera;

import net.minecraft.client.render.BufferBuilder;
import net.minecraft.util.math.MathHelper;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Las llamas de Ace como cuerpos 3D de verdad (no imágenes que miran a la cámara).
 * <p>
 * Dos piezas: la «bola» (una esfera de triángulos que se deforma con ruido, se estira hacia donde sube el fuego y
 * acaba en punta) y la «lengua» (un tubo que se retuerce y se afila hasta la punta). El color sale de cómo mira cada
 * punto a la cámara: el centro, de frente, en blanco-amarillo; los bordes en naranja y rojo. Así cada llama tiene
 * volumen desde cualquier lado y el conjunto se ve sólido, con el corte en bandas del anime.
 * <p>
 * Todo en coordenadas relativas a la cámara y triángulos POSITION_COLOR.
 */
final class Flame3D {
	private Flame3D() {}

	// ------------------------------------------------------------------ mallas

	/** Icosaedro (12 vértices, 20 caras) y su subdivisión (42 vértices, 80 caras), en la esfera unidad. */
	private static final float[] LO_V, HI_V;
	private static final int[] LO_F, HI_F;

	static {
		float p = (1f + (float) Math.sqrt(5)) / 2f;
		float[][] v0 = {
			{-1, p, 0}, {1, p, 0}, {-1, -p, 0}, {1, -p, 0},
			{0, -1, p}, {0, 1, p}, {0, -1, -p}, {0, 1, -p},
			{p, 0, -1}, {p, 0, 1}, {-p, 0, -1}, {-p, 0, 1}};
		int[][] f0 = {
			{0, 11, 5}, {0, 5, 1}, {0, 1, 7}, {0, 7, 10}, {0, 10, 11},
			{1, 5, 9}, {5, 11, 4}, {11, 10, 2}, {10, 7, 6}, {7, 1, 8},
			{3, 9, 4}, {3, 4, 2}, {3, 2, 6}, {3, 6, 8}, {3, 8, 9},
			{4, 9, 5}, {2, 4, 11}, {6, 2, 10}, {8, 6, 7}, {9, 8, 1}};
		List<float[]> verts = new ArrayList<>();
		for (float[] v : v0) verts.add(norm(v));
		LO_V = flat(verts);
		LO_F = flatF(f0);
		// una subdivisión: cada cara en cuatro
		Map<Long, Integer> mid = new HashMap<>();
		List<int[]> f1 = new ArrayList<>();
		for (int[] f : f0) {
			int a = midpoint(verts, mid, f[0], f[1]);
			int b = midpoint(verts, mid, f[1], f[2]);
			int c = midpoint(verts, mid, f[2], f[0]);
			f1.add(new int[]{f[0], a, c});
			f1.add(new int[]{f[1], b, a});
			f1.add(new int[]{f[2], c, b});
			f1.add(new int[]{a, b, c});
		}
		HI_V = flat(verts);
		HI_F = flatF(f1.toArray(new int[0][]));
	}

	private static float[] norm(float[] v) {
		float l = (float) Math.sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2]);
		return new float[]{v[0] / l, v[1] / l, v[2] / l};
	}

	private static int midpoint(List<float[]> verts, Map<Long, Integer> mid, int i, int j) {
		long key = i < j ? ((long) i << 32) | j : ((long) j << 32) | i;
		Integer k = mid.get(key);
		if (k != null) return k;
		float[] a = verts.get(i), b = verts.get(j);
		verts.add(norm(new float[]{(a[0] + b[0]) / 2, (a[1] + b[1]) / 2, (a[2] + b[2]) / 2}));
		mid.put(key, verts.size() - 1);
		return verts.size() - 1;
	}

	private static float[] flat(List<float[]> verts) {
		float[] o = new float[verts.size() * 3];
		for (int i = 0; i < verts.size(); i++) {
			o[i * 3] = verts.get(i)[0];
			o[i * 3 + 1] = verts.get(i)[1];
			o[i * 3 + 2] = verts.get(i)[2];
		}
		return o;
	}

	private static int[] flatF(int[][] f) {
		int[] o = new int[f.length * 3];
		for (int i = 0; i < f.length; i++) {
			o[i * 3] = f[i][0];
			o[i * 3 + 1] = f[i][1];
			o[i * 3 + 2] = f[i][2];
		}
		return o;
	}

	// ------------------------------------------------------------------ salida

	private static final Matrix4f BASE = new Matrix4f();
	private static boolean plain = true;
	private static final Vector3f TV = new Vector3f();
	// buffers de trabajo: posición y color de cada vértice de la malla deformada
	private static final float[] WX = new float[64], WY = new float[64], WZ = new float[64];
	private static final int[] WC = new int[64];

	static void begin(Matrix4f base, boolean isPlain) {
		BASE.set(base);
		plain = isPlain;
	}

	private static void v(BufferBuilder b, float x, float y, float z, int rgb) {
		if (!plain) {
			BASE.transformPosition(x, y, z, TV);
			x = TV.x;
			y = TV.y;
			z = TV.z;
		}
		b.vertex(x, y, z).color((rgb >> 16) & 255, (rgb >> 8) & 255, rgb & 255, 255);
	}

	/** Banda de color según lo que el punto mira a la cámara (1 = de frente) y lo cerca que está de la punta. */
	private static int band(float facing, float tip, int inner, int mid, int outer) {
		float f = facing - tip * 0.45f;
		if (f > 0.62f) return inner;
		if (f > 0.5f) return lerp(inner, mid, (0.62f - f) / 0.12f);
		if (f > 0.3f) return mid;
		if (f > 0.2f) return lerp(mid, outer, (0.3f - f) / 0.1f);
		return outer;
	}

	static int lerp(int c0, int c1, float t) {
		t = MathHelper.clamp(t, 0f, 1f);
		int r = Math.round(((c0 >> 16) & 255) + (((c1 >> 16) & 255) - ((c0 >> 16) & 255)) * t);
		int g = Math.round(((c0 >> 8) & 255) + (((c1 >> 8) & 255) - ((c0 >> 8) & 255)) * t);
		int bl = Math.round((c0 & 255) + ((c1 & 255) - (c0 & 255)) * t);
		return (r << 16) | (g << 8) | bl;
	}

	// ------------------------------------------------------------------ la bola de fuego

	/**
	 * Una llama redonda que se estira en punta hacia (dx, dy, dz).
	 *
	 * @param s       radio
	 * @param stretch cuánto se alarga la punta (0 = bola, 1.5 = lengua gorda)
	 * @param wobble  cuánto se deforma con el ruido (0.15 tranquila, 0.35 rabiosa)
	 */
	static void blob(BufferBuilder b, float x, float y, float z, float s, float dx, float dy, float dz, float stretch,
					 float wobble, float seed, float time, boolean hi, int inner, int mid, int outer) {
		float[] mv = hi ? HI_V : LO_V;
		int[] mf = hi ? HI_F : LO_F;
		int n = mv.length / 3;
		float dl = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
		if (dl < 1e-4f) {
			dx = 0;
			dy = 1;
			dz = 0;
		} else {
			dx /= dl;
			dy /= dl;
			dz /= dl;
		}
		float t1 = time * 6.3f + seed, t2 = time * -8.1f + seed * 1.7f, t3 = time * 5.2f + seed * 2.3f;
		for (int k = 0; k < n; k++) {
			float ux = mv[k * 3], uy = mv[k * 3 + 1], uz = mv[k * 3 + 2];
			float noise = 0.5f * MathHelper.sin(ux * 4.1f + uy * 1.3f + t1)
				+ 0.35f * MathHelper.sin(uy * 5.3f - uz * 1.1f + t2)
				+ 0.25f * MathHelper.sin(uz * 3.7f + ux * 2.2f + t3);
			float along = ux * dx + uy * dy + uz * dz;
			float r = s * (1f + wobble * noise) * (along < 0 ? 1f + 0.12f * along : 1f);
			float pull = along > 0 ? s * stretch * along * along * (1f + 0.25f * noise) : 0f;
			float px = x + ux * r + dx * pull, py = y + uy * r + dy * pull, pz = z + uz * r + dz * pull;
			WX[k] = px;
			WY[k] = py;
			WZ[k] = pz;
			// lo que mira a la cámara (que está en el origen): la normal aproximada es la dirección del vértice
			float vl = (float) Math.sqrt(px * px + py * py + pz * pz);
			float facing = vl < 1e-4f ? 1f : Math.abs(ux * px + uy * py + uz * pz) / vl;
			float tip = along > 0 ? along * Math.min(1f, stretch) : 0f;
			WC[k] = band(facing, tip, inner, mid, outer);
		}
		for (int f = 0; f < mf.length; f += 3) {
			int a = mf[f], c = mf[f + 1], d = mf[f + 2];
			v(b, WX[a], WY[a], WZ[a], WC[a]);
			v(b, WX[c], WY[c], WZ[c], WC[c]);
			v(b, WX[d], WY[d], WZ[d], WC[d]);
		}
	}

	// ------------------------------------------------------------------ la lengua de fuego

	private static final int SIDES = 6, SEGS = 5;
	private static final float[] RX = new float[(SEGS + 1) * SIDES], RY = new float[(SEGS + 1) * SIDES], RZ = new float[(SEGS + 1) * SIDES];
	private static final int[] RC = new int[(SEGS + 1) * SIDES];

	/** Un tubo que nace redondo en (x, y, z), sube hacia (dx, dy, dz) retorciéndose y se afila en punta. */
	static void tongue(BufferBuilder b, float x, float y, float z, float s, float len, float dx, float dy, float dz,
					   float seed, float time, int inner, int mid, int outer) {
		float dl = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
		if (dl < 1e-4f) {
			dx = 0;
			dy = 1;
			dz = 0;
		} else {
			dx /= dl;
			dy /= dl;
			dz /= dl;
		}
		// dos perpendiculares a la dirección
		float ax, ay, az;
		if (Math.abs(dy) < 0.9f) {
			ax = dz;
			ay = 0;
			az = -dx;
		} else {
			ax = 1;
			ay = 0;
			az = 0;
		}
		float al = (float) Math.sqrt(ax * ax + ay * ay + az * az);
		ax /= al;
		ay /= al;
		az /= al;
		float bx = dy * az - dz * ay, by = dz * ax - dx * az, bz = dx * ay - dy * ax;

		for (int k = 0; k <= SEGS; k++) {
			float u = k / (float) SEGS;
			float wob1 = MathHelper.sin(u * 2.6f + time * 9f + seed) * s * 0.55f * u;
			float wob2 = MathHelper.cos(u * 2.1f + time * 7f + seed * 1.7f) * s * 0.45f * u;
			float cx = x + dx * len * u + ax * wob1 + bx * wob2;
			float cy = y + dy * len * u + ay * wob1 + by * wob2;
			float cz = z + dz * len * u + az * wob1 + bz * wob2;
			float r = s * (u < 0.2f ? 0.7f + 1.5f * u : 1f) * (float) Math.pow(1f - u, 0.85f);
			for (int j = 0; j < SIDES; j++) {
				float ang = (float) (j * Math.PI * 2 / SIDES) + seed + u * 0.8f;
				float c = MathHelper.cos(ang), sn = MathHelper.sin(ang);
				float nx = ax * c + bx * sn, ny = ay * c + by * sn, nz = az * c + bz * sn;
				int idx = k * SIDES + j;
				float px = cx + nx * r, py = cy + ny * r, pz = cz + nz * r;
				RX[idx] = px;
				RY[idx] = py;
				RZ[idx] = pz;
				float vl = (float) Math.sqrt(px * px + py * py + pz * pz);
				float facing = vl < 1e-4f ? 1f : Math.abs(nx * px + ny * py + nz * pz) / vl;
				RC[idx] = band(facing, u, inner, mid, outer);
			}
		}
		for (int k = 0; k < SEGS; k++) {
			for (int j = 0; j < SIDES; j++) {
				int a = k * SIDES + j, c = k * SIDES + (j + 1) % SIDES;
				int d = a + SIDES, e = c + SIDES;
				v(b, RX[a], RY[a], RZ[a], RC[a]);
				v(b, RX[c], RY[c], RZ[c], RC[c]);
				v(b, RX[e], RY[e], RZ[e], RC[e]);
				v(b, RX[a], RY[a], RZ[a], RC[a]);
				v(b, RX[e], RY[e], RZ[e], RC[e]);
				v(b, RX[d], RY[d], RZ[d], RC[d]);
			}
		}
		// la base, cerrada con una cúpula baja
		float bx0 = x - dx * s * 0.45f, by0 = y - dy * s * 0.45f, bz0 = z - dz * s * 0.45f;
		for (int j = 0; j < SIDES; j++) {
			int a = j, c = (j + 1) % SIDES;
			v(b, bx0, by0, bz0, outer);
			v(b, RX[c], RY[c], RZ[c], RC[c]);
			v(b, RX[a], RY[a], RZ[a], RC[a]);
		}
	}
}
