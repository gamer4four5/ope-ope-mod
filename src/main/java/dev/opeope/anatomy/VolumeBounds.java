package dev.opeope.anatomy;

import java.util.Collection;

/**
 * Métricas espaciales (fracción restante, volumen aproximado en px³ y AABB recortada) de una caja de modelo bajo un
 * {@link CutVolume}. Todo opera en píxeles del JSON de {@link Anatomy}.
 */
public final class VolumeBounds {
	private VolumeBounds() {}

	/** Fracción [0..1] de {@code box} que queda dentro de {@code vol}, muestreada en rejilla 6×6×6. */
	public static double remainingFraction(double[] box, CutVolume vol) {
		if (box == null) return 0;
		if (vol == null || vol.isWhole()) return 1;
		final int N = 6;
		double dx = box[3] - box[0], dy = box[4] - box[1], dz = box[5] - box[2];
		if (dx <= 1e-4 || dy <= 1e-4 || dz <= 1e-4) return 0;
		int in = 0;
		int total = N * N * N;
		for (int ix = 0; ix < N; ix++) {
			float x = (float) (box[0] + dx * (ix + 0.5) / N);
			for (int iy = 0; iy < N; iy++) {
				float y = (float) (box[1] + dy * (iy + 0.5) / N);
				for (int iz = 0; iz < N; iz++) {
					float z = (float) (box[2] + dz * (iz + 0.5) / N);
					if (vol.contains(x, y, z)) in++;
				}
			}
		}
		return in / (double) total;
	}

	/**
	 * AABB recortada de {@code box} tras aplicar {@code vol}, en píxeles de modelo (null si no queda volumen).
	 * Si solo hay semiespacios, recorta analíticamente por cada plano; con ops cerradas/huecos combina muestreo 8×8×8.
	 */
	public static double[] clippedBox(double[] box, CutVolume vol) {
		if (box == null) return null;
		if (vol == null || vol.isWhole()) return box.clone();
		double[] b = box.clone();
		if (vol.exclude.isEmpty()) {
			for (CutOp op : vol.include) {
				if (op instanceof CutOp.HalfSpace hs) {
					b = Anatomy.clipBox(b, hs.toFloatArray(), false);
					if (b == null) return null;
				} else if (!op.isInverted()) {
					float[] ob = op.bounds();
					if (ob != null) {
						b[0] = Math.max(b[0], ob[0]);
						b[1] = Math.max(b[1], ob[1]);
						b[2] = Math.max(b[2], ob[2]);
						b[3] = Math.min(b[3], ob[3]);
						b[4] = Math.min(b[4], ob[4]);
						b[5] = Math.min(b[5], ob[5]);
						if (b[0] >= b[3] || b[1] >= b[4] || b[2] >= b[5]) return null;
					}
				}
			}
			if (vol.isPureHalfSpaces()) return b;
		} else {
			for (CutOp op : vol.include) {
				if (op instanceof CutOp.HalfSpace hs) {
					b = Anatomy.clipBox(b, hs.toFloatArray(), false);
					if (b == null) return null;
				}
			}
		}
		final int N = 8;
		double dx = b[3] - b[0], dy = b[4] - b[1], dz = b[5] - b[2];
		if (dx <= 1e-4 || dy <= 1e-4 || dz <= 1e-4) return null;
		double stepX = dx / N, stepY = dy / N, stepZ = dz / N;
		double x0 = Double.MAX_VALUE, y0 = Double.MAX_VALUE, z0 = Double.MAX_VALUE;
		double x1 = -Double.MAX_VALUE, y1 = -Double.MAX_VALUE, z1 = -Double.MAX_VALUE;
		boolean any = false;
		for (int ix = 0; ix < N; ix++) {
			double cx = b[0] + dx * (ix + 0.5) / N;
			for (int iy = 0; iy < N; iy++) {
				double cy = b[1] + dy * (iy + 0.5) / N;
				for (int iz = 0; iz < N; iz++) {
					double cz = b[2] + dz * (iz + 0.5) / N;
					if (vol.contains((float) cx, (float) cy, (float) cz)) {
						any = true;
						x0 = Math.min(x0, cx - stepX * 0.5);
						y0 = Math.min(y0, cy - stepY * 0.5);
						z0 = Math.min(z0, cz - stepZ * 0.5);
						x1 = Math.max(x1, cx + stepX * 0.5);
						y1 = Math.max(y1, cy + stepY * 0.5);
						z1 = Math.max(z1, cz + stepZ * 0.5);
					}
				}
			}
		}
		if (!any) return null;
		return new double[]{
			Math.max(b[0], x0), Math.max(b[1], y0), Math.max(b[2], z0),
			Math.min(b[3], x1), Math.min(b[4], y1), Math.min(b[5], z1)
		};
	}

	/** Caja unión recortada de todos esos segmentos bajo {@code vol}, en píxeles de modelo. */
	public static double[] unionClippedBox(Anatomy a, Collection<String> segs, CutVolume vol) {
		if (a == null || segs == null || segs.isEmpty()) return null;
		double[] out = null;
		for (String id : segs) {
			Anatomy.Segment sg = a.get(id);
			if (sg == null) continue;
			double[] c = clippedBox(sg.box(), vol);
			if (c == null) continue;
			if (out == null) out = c;
			else {
				out[0] = Math.min(out[0], c[0]);
				out[1] = Math.min(out[1], c[1]);
				out[2] = Math.min(out[2], c[2]);
				out[3] = Math.max(out[3], c[3]);
				out[4] = Math.max(out[4], c[4]);
				out[5] = Math.max(out[5], c[5]);
			}
		}
		return out;
	}

	/** Volumen total aproximado (px³) conservado por {@code vol} en esos segmentos. */
	public static double estimateVolumePx3(Anatomy a, Collection<String> segs, CutVolume vol) {
		if (a == null || segs == null || segs.isEmpty()) return 0;
		double total = 0;
		for (String id : segs) {
			Anatomy.Segment sg = a.get(id);
			if (sg == null) continue;
			double v = sg.volume();
			if (v > 0) total += v * remainingFraction(sg.box(), vol);
		}
		return total;
	}
}
