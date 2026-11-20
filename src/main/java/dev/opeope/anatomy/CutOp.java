package dev.opeope.anatomy;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;

import java.util.Arrays;
import java.util.Locale;
import java.util.Objects;

/**
 * Primitiva CSG en coordenadas de píxeles de modelo (16 px = 1 bloque, Y hacia arriba desde los pies).
 * <p>
 * Convención SDF: {@code sdf(x, y, z) <= 0} significa DENTRO del volumen conservado; {@code > 0} significa FUERA.
 */
public sealed interface CutOp permits CutOp.HalfSpace, CutOp.BoxVolume, CutOp.SphereVolume, CutOp.CylinderVolume, CutOp.PrismVolume {

	Codec<CutOp> CODEC = Codec.STRING.comapFlatMap(s -> {
		CutOp op = decodeString(s);
		return op != null ? DataResult.success(op) : DataResult.error(() -> "Invalid CutOp: " + s);
	}, CutOp::encodeString);

	float sdf(float x, float y, float z);

	CutOp negated();

	/** AABB en píxeles de modelo {minX, minY, minZ, maxX, maxY, maxZ}, o null si es infinito (HalfSpace). */
	float[] bounds();

	default boolean isHalfSpace() {
		return this instanceof HalfSpace;
	}

	boolean isInverted();

	int stableHash();

	String encodeString();

	// ------------------------------------------------------------------ variantes

	/** Semiespacio n·P <= d (sdf = nx*x + ny*y + nz*z - d). */
	record HalfSpace(float nx, float ny, float nz, float d) implements CutOp {
		@Override
		public float sdf(float x, float y, float z) {
			return nx * x + ny * y + nz * z - d;
		}

		@Override
		public HalfSpace negated() {
			return new HalfSpace(-nx, -ny, -nz, -d);
		}

		@Override
		public float[] bounds() {
			return null;
		}

		@Override
		public boolean isInverted() {
			return false;
		}

		public float[] toFloatArray() {
			return new float[]{nx, ny, nz, d};
		}

		@Override
		public int stableHash() {
			return Objects.hash('H', q(nx), q(ny), q(nz), q(d));
		}

		@Override
		public String encodeString() {
			return "H:" + fmt(nx) + "," + fmt(ny) + "," + fmt(nz) + "," + fmt(d);
		}
	}

	/** Caja orientada (OBB) con base (r, u, f) y semiejes (hx, hy, hz). */
	record BoxVolume(
		float cx, float cy, float cz,
		float hx, float hy, float hz,
		float rx, float ry, float rz,
		float ux, float uy, float uz,
		float fx, float fy, float fz,
		boolean inverted
	) implements CutOp {
		@Override
		public float sdf(float x, float y, float z) {
			float dx = x - cx, dy = y - cy, dz = z - cz;
			float lx = dx * rx + dy * ry + dz * rz;
			float ly = dx * ux + dy * uy + dz * uz;
			float lz = dx * fx + dy * fy + dz * fz;
			float qx = Math.abs(lx) - hx;
			float qy = Math.abs(ly) - hy;
			float qz = Math.abs(lz) - hz;
			float mx = Math.max(qx, 0f), my = Math.max(qy, 0f), mz = Math.max(qz, 0f);
			float out = (float) Math.sqrt(mx * mx + my * my + mz * mz);
			float in = Math.min(Math.max(qx, Math.max(qy, qz)), 0f);
			float raw = out + in;
			return inverted ? -raw : raw;
		}

		@Override
		public BoxVolume negated() {
			return new BoxVolume(cx, cy, cz, hx, hy, hz, rx, ry, rz, ux, uy, uz, fx, fy, fz, !inverted);
		}

		@Override
		public float[] bounds() {
			float sx = Math.abs(rx) * hx + Math.abs(ux) * hy + Math.abs(fx) * hz + 0.25f;
			float sy = Math.abs(ry) * hx + Math.abs(uy) * hy + Math.abs(fy) * hz + 0.25f;
			float sz = Math.abs(rz) * hx + Math.abs(uz) * hy + Math.abs(fz) * hz + 0.25f;
			return new float[]{cx - sx, cy - sy, cz - sz, cx + sx, cy + sy, cz + sz};
		}

		@Override
		public boolean isInverted() {
			return inverted;
		}

		@Override
		public int stableHash() {
			return Objects.hash('B', q(cx), q(cy), q(cz), q(hx), q(hy), q(hz), q(rx), q(uy), q(fz), inverted);
		}

		@Override
		public String encodeString() {
			return "B:" + fmt(cx) + "," + fmt(cy) + "," + fmt(cz) + ","
				+ fmt(hx) + "," + fmt(hy) + "," + fmt(hz) + ","
				+ fmt(rx) + "," + fmt(ry) + "," + fmt(rz) + ","
				+ fmt(ux) + "," + fmt(uy) + "," + fmt(uz) + ","
				+ fmt(fx) + "," + fmt(fy) + "," + fmt(fz) + ","
				+ (inverted ? "1" : "0");
		}
	}

	/** Esfera centrada en (cx, cy, cz) de radio r. */
	record SphereVolume(float cx, float cy, float cz, float radius, boolean inverted) implements CutOp {
		@Override
		public float sdf(float x, float y, float z) {
			float dx = x - cx, dy = y - cy, dz = z - cz;
			float raw = (float) Math.sqrt(dx * dx + dy * dy + dz * dz) - radius;
			return inverted ? -raw : raw;
		}

		@Override
		public SphereVolume negated() {
			return new SphereVolume(cx, cy, cz, radius, !inverted);
		}

		@Override
		public float[] bounds() {
			float r = radius + 0.25f;
			return new float[]{cx - r, cy - r, cz - r, cx + r, cy + r, cz + r};
		}

		@Override
		public boolean isInverted() {
			return inverted;
		}

		@Override
		public int stableHash() {
			return Objects.hash('S', q(cx), q(cy), q(cz), q(radius), inverted);
		}

		@Override
		public String encodeString() {
			return "S:" + fmt(cx) + "," + fmt(cy) + "," + fmt(cz) + "," + fmt(radius) + "," + (inverted ? "1" : "0");
		}
	}

	/** Cilindro finito a lo largo del eje unitario a = (ax, ay, az), semilongitud halfLen y radio radius. */
	record CylinderVolume(
		float cx, float cy, float cz,
		float ax, float ay, float az,
		float radius, float halfLen, boolean inverted
	) implements CutOp {
		@Override
		public float sdf(float x, float y, float z) {
			float dx = x - cx, dy = y - cy, dz = z - cz;
			float proj = dx * ax + dy * ay + dz * az;
			float px = dx - ax * proj, py = dy - ay * proj, pz = dz - az * proj;
			float dr = (float) Math.sqrt(px * px + py * py + pz * pz) - radius;
			float dl = Math.abs(proj) - halfLen;
			float mr = Math.max(dr, 0f), ml = Math.max(dl, 0f);
			float raw = (float) Math.sqrt(mr * mr + ml * ml) + Math.min(Math.max(dr, dl), 0f);
			return inverted ? -raw : raw;
		}

		@Override
		public CylinderVolume negated() {
			return new CylinderVolume(cx, cy, cz, ax, ay, az, radius, halfLen, !inverted);
		}

		@Override
		public float[] bounds() {
			float rx = radius + 0.25f;
			float px = Math.abs(ax) * halfLen + (float) Math.sqrt(Math.max(0, 1 - ax * ax)) * rx + 0.25f;
			float py = Math.abs(ay) * halfLen + (float) Math.sqrt(Math.max(0, 1 - ay * ay)) * rx + 0.25f;
			float pz = Math.abs(az) * halfLen + (float) Math.sqrt(Math.max(0, 1 - az * az)) * rx + 0.25f;
			return new float[]{cx - px, cy - py, cz - pz, cx + px, cy + py, cz + pz};
		}

		@Override
		public boolean isInverted() {
			return inverted;
		}

		@Override
		public int stableHash() {
			return Objects.hash('C', q(cx), q(cy), q(cz), q(ax), q(ay), q(az), q(radius), q(halfLen), inverted);
		}

		@Override
		public String encodeString() {
			return "C:" + fmt(cx) + "," + fmt(cy) + "," + fmt(cz) + ","
				+ fmt(ax) + "," + fmt(ay) + "," + fmt(az) + ","
				+ fmt(radius) + "," + fmt(halfLen) + "," + (inverted ? "1" : "0");
		}
	}

	/**
	 * Prisma extruido desde un polígono simple 2D (3..24 puntos) en el marco ortonormal (u, v) centrado en o,
	 * extruido en [-halfDepth, +halfDepth] a lo largo de a. Sirve para Lazo Libre, Cuñas en V, etc.
	 */
	record PrismVolume(
		float ox, float oy, float oz,
		float ux, float uy, float uz,
		float vx, float vy, float vz,
		float ax, float ay, float az,
		float halfDepth,
		float[] polyX,
		float[] polyY,
		boolean inverted
	) implements CutOp {
		@Override
		public float sdf(float x, float y, float z) {
			float dx = x - ox, dy = y - oy, dz = z - oz;
			float pu = dx * ux + dy * uy + dz * uz;
			float pv = dx * vx + dy * vy + dz * vz;
			float pa = dx * ax + dy * ay + dz * az;
			float d2 = signedDist2D(pu, pv, polyX, polyY);
			float dl = Math.abs(pa) - halfDepth;
			float m2 = Math.max(d2, 0f), ml = Math.max(dl, 0f);
			float raw = (float) Math.sqrt(m2 * m2 + ml * ml) + Math.min(Math.max(d2, dl), 0f);
			return inverted ? -raw : raw;
		}

		/** Distancia con signo al polígono 2D (negativa dentro, positiva fuera). */
		public static float signedDist2D(float px, float py, float[] xs, float[] ys) {
			int n = xs.length;
			if (n < 3) return 1e6f;
			float d2 = Float.MAX_VALUE;
			boolean inside = false;
			for (int i = 0, j = n - 1; i < n; j = i++) {
				float xi = xs[i], yi = ys[i], xj = xs[j], yj = ys[j];
				float ex = xj - xi, ey = yj - yi;
				float wx = px - xi, wy = py - yi;
				float ee = ex * ex + ey * ey;
				float t = ee > 1e-8f ? Math.max(0f, Math.min(1f, (wx * ex + wy * ey) / ee)) : 0f;
				float bx = wx - ex * t, by = wy - ey * t;
				d2 = Math.min(d2, bx * bx + by * by);
				if ((yi > py) != (yj > py) && (px < (xj - xi) * (py - yi) / (yj - yi + 1e-12f) + xi)) {
					inside = !inside;
				}
			}
			float dist = (float) Math.sqrt(d2);
			return inside ? -dist : dist;
		}

		@Override
		public PrismVolume negated() {
			return new PrismVolume(ox, oy, oz, ux, uy, uz, vx, vy, vz, ax, ay, az, halfDepth, polyX, polyY, !inverted);
		}

		@Override
		public float[] bounds() {
			float minX = Float.MAX_VALUE, minY = Float.MAX_VALUE, minZ = Float.MAX_VALUE;
			float maxX = -Float.MAX_VALUE, maxY = -Float.MAX_VALUE, maxZ = -Float.MAX_VALUE;
			for (int i = 0; i < polyX.length; i++) {
				float px = polyX[i], py = polyY[i];
				for (int s = -1; s <= 1; s += 2) {
					float d = s * halfDepth;
					float wx = ox + ux * px + vx * py + ax * d;
					float wy = oy + uy * px + vy * py + ay * d;
					float wz = oz + uz * px + vz * py + az * d;
					minX = Math.min(minX, wx); minY = Math.min(minY, wy); minZ = Math.min(minZ, wz);
					maxX = Math.max(maxX, wx); maxY = Math.max(maxY, wy); maxZ = Math.max(maxZ, wz);
				}
			}
			return new float[]{minX - 0.25f, minY - 0.25f, minZ - 0.25f, maxX + 0.25f, maxY + 0.25f, maxZ + 0.25f};
		}

		@Override
		public boolean isInverted() {
			return inverted;
		}

		@Override
		public int stableHash() {
			int h = Objects.hash('P', q(ox), q(oy), q(oz), q(halfDepth), polyX.length, inverted);
			for (int i = 0; i < polyX.length; i++) h = 31 * h + q(polyX[i]) * 17 + q(polyY[i]);
			return h;
		}

		@Override
		public String encodeString() {
			StringBuilder sb = new StringBuilder("P:");
			sb.append(fmt(ox)).append(',').append(fmt(oy)).append(',').append(fmt(oz)).append(',')
				.append(fmt(ux)).append(',').append(fmt(uy)).append(',').append(fmt(uz)).append(',')
				.append(fmt(vx)).append(',').append(fmt(vy)).append(',').append(fmt(vz)).append(',')
				.append(fmt(ax)).append(',').append(fmt(ay)).append(',').append(fmt(az)).append(',')
				.append(fmt(halfDepth)).append(',').append(inverted ? '1' : '0').append(',').append(polyX.length);
			for (int i = 0; i < polyX.length; i++) {
				sb.append(',').append(fmt(polyX[i])).append(',').append(fmt(polyY[i]));
			}
			return sb.toString();
		}

		@Override
		public boolean equals(Object o) {
			if (this == o) return true;
			if (!(o instanceof PrismVolume p)) return false;
			return Float.compare(ox, p.ox) == 0 && Float.compare(oy, p.oy) == 0 && Float.compare(oz, p.oz) == 0
				&& Float.compare(ux, p.ux) == 0 && Float.compare(uy, p.uy) == 0 && Float.compare(uz, p.uz) == 0
				&& Float.compare(vx, p.vx) == 0 && Float.compare(vy, p.vy) == 0 && Float.compare(vz, p.vz) == 0
				&& Float.compare(ax, p.ax) == 0 && Float.compare(ay, p.ay) == 0 && Float.compare(az, p.az) == 0
				&& Float.compare(halfDepth, p.halfDepth) == 0 && inverted == p.inverted
				&& Arrays.equals(polyX, p.polyX) && Arrays.equals(polyY, p.polyY);
		}

		@Override
		public int hashCode() {
			return stableHash();
		}
	}

	// ------------------------------------------------------------------ codificación

	private static int q(float f) {
		return Math.round(f * 1000f);
	}

	private static String fmt(float f) {
		float r = Math.round(f * 1000f) / 1000f;
		if (r == (int) r) return Integer.toString((int) r);
		String s = String.format(Locale.ROOT, "%.3f", r);
		int end = s.length();
		while (end > 0 && s.charAt(end - 1) == '0') end--;
		if (end > 0 && s.charAt(end - 1) == '.') end--;
		return s.substring(0, end);
	}

	static CutOp decodeString(String s) {
		if (s == null || s.length() < 3 || s.charAt(1) != ':') return null;
		char type = s.charAt(0);
		String[] p = s.substring(2).split(",");
		try {
			return switch (type) {
				case 'H' -> {
					if (p.length != 4) yield null;
					yield new HalfSpace(Float.parseFloat(p[0]), Float.parseFloat(p[1]), Float.parseFloat(p[2]), Float.parseFloat(p[3]));
				}
				case 'B' -> {
					if (p.length != 16) yield null;
					yield new BoxVolume(
						Float.parseFloat(p[0]), Float.parseFloat(p[1]), Float.parseFloat(p[2]),
						Float.parseFloat(p[3]), Float.parseFloat(p[4]), Float.parseFloat(p[5]),
						Float.parseFloat(p[6]), Float.parseFloat(p[7]), Float.parseFloat(p[8]),
						Float.parseFloat(p[9]), Float.parseFloat(p[10]), Float.parseFloat(p[11]),
						Float.parseFloat(p[12]), Float.parseFloat(p[13]), Float.parseFloat(p[14]),
						"1".equals(p[15]));
				}
				case 'S' -> {
					if (p.length != 5) yield null;
					yield new SphereVolume(
						Float.parseFloat(p[0]), Float.parseFloat(p[1]), Float.parseFloat(p[2]),
						Float.parseFloat(p[3]), "1".equals(p[4]));
				}
				case 'C' -> {
					if (p.length != 9) yield null;
					yield new CylinderVolume(
						Float.parseFloat(p[0]), Float.parseFloat(p[1]), Float.parseFloat(p[2]),
						Float.parseFloat(p[3]), Float.parseFloat(p[4]), Float.parseFloat(p[5]),
						Float.parseFloat(p[6]), Float.parseFloat(p[7]), "1".equals(p[8]));
				}
				case 'P' -> {
					if (p.length < 15) yield null;
					int n = Integer.parseInt(p[14]);
					if (n < 3 || n > 24 || p.length != 15 + 2 * n) yield null;
					float[] px = new float[n], py = new float[n];
					for (int i = 0; i < n; i++) {
						px[i] = Float.parseFloat(p[15 + i * 2]);
						py[i] = Float.parseFloat(p[15 + i * 2 + 1]);
					}
					yield new PrismVolume(
						Float.parseFloat(p[0]), Float.parseFloat(p[1]), Float.parseFloat(p[2]),
						Float.parseFloat(p[3]), Float.parseFloat(p[4]), Float.parseFloat(p[5]),
						Float.parseFloat(p[6]), Float.parseFloat(p[7]), Float.parseFloat(p[8]),
						Float.parseFloat(p[9]), Float.parseFloat(p[10]), Float.parseFloat(p[11]),
						Float.parseFloat(p[12]), px, py, "1".equals(p[13]));
				}
				default -> null;
			};
		} catch (NumberFormatException ex) {
			return null;
		}
	}
}
