package dev.opeope.client.dismember;

import dev.opeope.anatomy.CutOp;
import dev.opeope.anatomy.CutVolume;
import net.minecraft.client.render.OverlayTexture;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.VertexFormat;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;

/**
 * Recorta los quads reales del modelo (en cualquier pose animada, incluidas armaduras e ítems) contra un
 * {@link CutVolume} CSG arbitrario sin asignaciones en el heap por vértice:
 * <ul>
 *   <li>Vía rápida de poliedro convexo ({@code CutVolume.isPureHalfSpaces()}): Sutherland–Hodgman en arrays reutilizables.</li>
 *   <li>Vía CSG general (esfera, caja, cilindro/túnel, prisma/lazo libre o múltiples huecos en {@code exclude}): recorte
 *   por semiespacios + subdivisión adaptativa + Marching Triangles con refinamiento por bisección sobre el SDF.</li>
 * </ul>
 * Registra los segmentos de borde en espacio de píxeles de modelo en {@link CapBuilder.Session} para que las tapas y
 * contornos sigan la postura exacta del mob frame a frame.
 */
public final class VolumeSliceConsumer implements VertexConsumer {
	private static final int STRIDE = 11; // vx, vy, vz, mx, my, mz, u, v, nx, ny, nz
	private static final int MAX_POLY = 20;

	private VertexConsumer delegate;
	private final VertexConsumerProvider rawProvider;
	private final RenderLayer layer;
	private final Matrix4f renderToModelPx;
	private final CutVolume volume;
	private final List<CutOp.HalfSpace> halfSpaces;
	private final List<CutOp.HalfSpace> allPlanes;
	private final List<CutOp> nonPlanar;
	private final boolean pureHalfSpaces;
	private final boolean active;
	private final CapBuilder.Session session;

	private float curX, curY, curZ, curU, curV;
	private int curColor = -1;
	private int curOverlay = OverlayTexture.DEFAULT_UV;
	private int curLight;
	private boolean hasPending;

	private record GlobalSlice(Matrix4f renderToModelPx, CutVolume volume, CapBuilder.Session session) {}
	private static final Deque<GlobalSlice> GLOBAL_STACK = new ArrayDeque<>();
	private static int internalDepth;

	private final float[] inF = new float[4 * STRIDE];
	private final int[] inI = new int[4 * 3]; // color, overlay, light
	private int count;

	private final float[] polyA = new float[MAX_POLY * STRIDE];
	private final float[] polyB = new float[MAX_POLY * STRIDE];
	private final float[] distA = new float[MAX_POLY];
	private final float[] cutMaskA = new float[MAX_POLY];
	private final int[] srcA = new int[MAX_POLY];
	private final int[] srcB = new int[MAX_POLY];
	private final Vector3f tmpPos = new Vector3f();

	private static final int MAX_G = 10;
	private final float[] gridF = new float[(MAX_G + 1) * (MAX_G + 1) * STRIDE];
	private final float[] gridD = new float[(MAX_G + 1) * (MAX_G + 1)];
	private final float[] edgeAB = new float[STRIDE];
	private final float[] edgeAC = new float[STRIDE];

	public VolumeSliceConsumer(VertexConsumer delegate, Matrix4f renderToModelPx, CutVolume volume, CapBuilder.Session session) {
		this(delegate, null, null, renderToModelPx, volume, session);
	}

	public VolumeSliceConsumer(VertexConsumer delegate, VertexConsumerProvider rawProvider, RenderLayer layer,
							   Matrix4f renderToModelPx, CutVolume volume, CapBuilder.Session session) {
		this.delegate = delegate;
		this.rawProvider = rawProvider;
		this.layer = layer;
		this.renderToModelPx = renderToModelPx;
		this.volume = volume;
		this.halfSpaces = volume.halfSpaces();
		this.allPlanes = session != null ? session.planes : volume.allPlanes();
		this.nonPlanar = volume.nonPlanarOps();
		this.pureHalfSpaces = volume.isPureHalfSpaces();
		this.active = !volume.isWhole();
		this.session = session;
	}

	public static void pushGlobalSlice(Matrix4f renderToModelPx, CutVolume volume, CapBuilder.Session session) {
		if (GLOBAL_STACK.size() > 8) GLOBAL_STACK.clear();
		GLOBAL_STACK.push(new GlobalSlice(renderToModelPx, volume, session));
	}

	public static void popGlobalSlice() {
		if (!GLOBAL_STACK.isEmpty()) GLOBAL_STACK.pop();
	}

	public static void clearGlobalSlice() {
		GLOBAL_STACK.clear();
		internalDepth = 0;
	}

	public static VertexConsumer getRawBuffer(VertexConsumerProvider provider, RenderLayer layer) {
		internalDepth++;
		try {
			return provider.getBuffer(layer);
		} finally {
			internalDepth--;
		}
	}

	public static VertexConsumerProvider wrapProvider(VertexConsumerProvider base, Matrix4f renderToModelPx,
													  CutVolume volume, CapBuilder.Session session) {
		return layer -> {
			VertexConsumer raw = getRawBuffer(base, layer);
			if (raw instanceof VolumeSliceConsumer) return raw;
			return new VolumeSliceConsumer(raw, base, layer, renderToModelPx, volume, session);
		};
	}

	public static VertexConsumer wrapIfActive(VertexConsumerProvider provider, RenderLayer layer, VertexConsumer vc) {
		if (internalDepth > 0 || GLOBAL_STACK.isEmpty() || vc == null || vc instanceof VolumeSliceConsumer || layer == null) {
			return vc;
		}
		if (layer.getDrawMode() != VertexFormat.DrawMode.QUADS) return vc;
		String name = layer.toString();
		if (name.contains("cut_cap") || name.contains("cut_rim") || name.contains("lightning")
			|| name.contains("outline") || name.contains("debug")) {
			return vc;
		}
		GlobalSlice gs = GLOBAL_STACK.peek();
		return new VolumeSliceConsumer(vc, provider, layer, gs.renderToModelPx, gs.volume, gs.session);
	}

	@Override
	public void vertex(float x, float y, float z, int color, float u, float v, int overlay, int light, float nx, float ny, float nz) {
		int o = count * STRIDE;
		renderToModelPx.transformPosition(x, y, z, tmpPos);
		inF[o] = x;
		inF[o + 1] = y;
		inF[o + 2] = z;
		inF[o + 3] = tmpPos.x;
		inF[o + 4] = tmpPos.y;
		inF[o + 5] = tmpPos.z;
		inF[o + 6] = u;
		inF[o + 7] = v;
		inF[o + 8] = nx;
		inF[o + 9] = ny;
		inF[o + 10] = nz;
		int io = count * 3;
		inI[io] = color;
		inI[io + 1] = overlay;
		inI[io + 2] = light;
		if (++count == 4) {
			emit();
			count = 0;
		}
	}

	private void emit() {
		if (!active) {
			emitRawQuad();
			return;
		}

		float qMinX = Float.MAX_VALUE, qMinY = Float.MAX_VALUE, qMinZ = Float.MAX_VALUE;
		float qMaxX = -Float.MAX_VALUE, qMaxY = -Float.MAX_VALUE, qMaxZ = -Float.MAX_VALUE;
		boolean anyIn = false, allClear = true;
		for (int i = 0; i < 4; i++) {
			int o = i * STRIDE;
			float mx = inF[o + 3], my = inF[o + 4], mz = inF[o + 5];
			if (mx < qMinX) qMinX = mx;
			if (my < qMinY) qMinY = my;
			if (mz < qMinZ) qMinZ = mz;
			if (mx > qMaxX) qMaxX = mx;
			if (my > qMaxY) qMaxY = my;
			if (mz > qMaxZ) qMaxZ = mz;
			float d = volume.sdf(mx, my, mz);
			distA[i] = d;
			if (d <= 0f) anyIn = true;
			if (d > -0.05f) allClear = false;
		}

		boolean overlapsCsg = overlapsGeneralCsg(qMinX, qMinY, qMinZ, qMaxX, qMaxY, qMaxZ);
		if (allClear && !overlapsCsg) {
			emitRawQuad();
			return;
		}

		System.arraycopy(inF, 0, polyA, 0, 4 * STRIDE);
		for (int i = 0; i < 4; i++) srcA[i] = i;
		int nv = 4;

		boolean clippedByPlane = false;
		int hsCount = halfSpaces.size();
		for (int hi = 0; hi < hsCount && nv >= 3; hi++) {
			CutOp.HalfSpace hs = halfSpaces.get(hi);
			boolean pAllIn = true, pAllOut = true;
			for (int i = 0; i < nv; i++) {
				int o = i * STRIDE;
				float d = hs.sdf(polyA[o + 3], polyA[o + 4], polyA[o + 5]);
				cutMaskA[i] = d;
				if (d > 1e-4f) pAllIn = false;
				if (d <= -1e-4f) pAllOut = false;
			}
			if (pAllOut && !pAllIn) return;
			if (pAllIn) continue;

			clippedByPlane = true;
			int nOut = 0;
			for (int i = 0; i < nv && nOut < MAX_POLY - 1; i++) {
				int j = (i + 1) % nv;
				float da = cutMaskA[i], db = cutMaskA[j];
				boolean ina = da <= 0f, inb = db <= 0f;
				if (ina) {
					System.arraycopy(polyA, i * STRIDE, polyB, nOut * STRIDE, STRIDE);
					srcB[nOut++] = srcA[i];
				}
				if (ina != inb && nOut < MAX_POLY - 1) {
					float t = da / (da - db);
					int ao = i * STRIDE, bo = j * STRIDE, co = nOut * STRIDE;
					for (int k = 0; k < STRIDE; k++) {
						polyB[co + k] = polyA[ao + k] + (polyA[bo + k] - polyA[ao + k]) * t;
					}
					srcB[nOut++] = ina ? srcA[i] : srcA[j];
				}
			}
			nv = nOut;
			if (nv < 3) return;
			System.arraycopy(polyB, 0, polyA, 0, nv * STRIDE);
			System.arraycopy(srcB, 0, srcA, 0, nv);
		}

		if (clippedByPlane && session != null && !overlapsCsg) {
			recordFinalPlaneEdges(polyA, nv);
		}

		if (pureHalfSpaces || !overlapsCsg) {
			if (!overlapsCsg && !anyIn && !clippedByPlane) return;
			emitPolygonFan(polyA, srcA, nv);
			return;
		}

		float span = Math.max(qMaxX - qMinX, Math.max(qMaxY - qMinY, qMaxZ - qMinZ));
		int g = span >= 5.5f ? 10 : 6;
		for (int i = 1; i + 1 < nv; i++) {
			emitSubdividedTriangle(polyA, 0, i, i + 1, srcA[0], srcA[i], srcA[i + 1], g);
		}
	}

	/**
	 * Devuelve true si algún op no planar (de include o exclude) O algún grupo de exclude (incluso de HalfSpaces) puede
	 * cortar el interior de este quad.
	 */
	private boolean overlapsGeneralCsg(float x0, float y0, float z0, float x1, float y1, float z1) {
		for (int i = 0; i < nonPlanar.size(); i++) {
			float[] b = nonPlanar.get(i).bounds();
			if (b == null || (x1 >= b[0] && x0 <= b[3] && y1 >= b[1] && y0 <= b[4] && z1 >= b[2] && z0 <= b[5])) {
				return true;
			}
		}
		for (int gi = 0; gi < volume.exclude.size(); gi++) {
			List<CutOp> g = volume.exclude.get(gi);
			boolean eliminated = false;
			for (int oi = 0; oi < g.size(); oi++) {
				CutOp op = g.get(oi);
				float[] b = op.bounds();
				if (b != null) {
					if (x1 < b[0] || x0 > b[3] || y1 < b[1] || y0 > b[4] || z1 < b[2] || z0 > b[5]) {
						eliminated = true;
						break;
					}
				} else if (op instanceof CutOp.HalfSpace hs) {
					boolean allQuadOutOfOp = true;
					for (int vi = 0; vi < 4; vi++) {
						int o = vi * STRIDE;
						if (hs.sdf(inF[o + 3], inF[o + 4], inF[o + 5]) <= 0.05f) {
							allQuadOutOfOp = false;
							break;
						}
					}
					if (allQuadOutOfOp) {
						eliminated = true;
						break;
					}
				}
			}
			if (!eliminated) return true;
		}
		return false;
	}

	private void recordFinalPlaneEdges(float[] poly, int nv) {
		int pCount = allPlanes.size();
		for (int pi = 0; pi < pCount; pi++) {
			CutOp.HalfSpace hs = allPlanes.get(pi);
			boolean hadOut = false, hadIn = false;
			for (int k = 0; k < 4; k++) {
				int o = k * STRIDE;
				float d = hs.sdf(inF[o + 3], inF[o + 4], inF[o + 5]);
				if (d > 0.03f) hadOut = true;
				else if (d < -0.03f) hadIn = true;
			}
			if (!hadOut || !hadIn) continue;
			for (int i = 0; i < nv; i++) {
				int j = (i + 1) % nv;
				int ao = i * STRIDE, bo = j * STRIDE;
				float da = Math.abs(hs.sdf(poly[ao + 3], poly[ao + 4], poly[ao + 5]));
				float db = Math.abs(hs.sdf(poly[bo + 3], poly[bo + 4], poly[bo + 5]));
				if (da < 0.04f && db < 0.04f) {
					session.addPlaneSegment(pi,
						poly[ao + 3], poly[ao + 4], poly[ao + 5],
						poly[bo + 3], poly[bo + 4], poly[bo + 5]);
				}
			}
		}
	}

	private void recordBoundarySegment(float[] a, float[] b) {
		if (session == null) return;
		float mx = (a[3] + b[3]) * 0.5f, my = (a[4] + b[4]) * 0.5f, mz = (a[5] + b[5]) * 0.5f;
		int matched = -1;
		for (int pi = 0; pi < allPlanes.size(); pi++) {
			CutOp.HalfSpace hs = allPlanes.get(pi);
			if (Math.abs(hs.sdf(mx, my, mz)) < 0.08f
				&& Math.abs(hs.sdf(a[3], a[4], a[5])) < 0.12f
				&& Math.abs(hs.sdf(b[3], b[4], b[5])) < 0.12f) {
				matched = pi;
				break;
			}
		}
		if (matched >= 0) {
			session.addPlaneSegment(matched, a[3], a[4], a[5], b[3], b[4], b[5]);
		} else {
			session.addRimSegment(a[3], a[4], a[5], b[3], b[4], b[5]);
		}
	}

	private void emitSubdividedTriangle(float[] poly, int i0, int i1, int i2, int s0, int s1, int s2, int g) {
		int a0 = i0 * STRIDE, a1 = i1 * STRIDE, a2 = i2 * STRIDE;
		int rowStride = g + 1;
		for (int r = 0; r <= g; r++) {
			float v = r / (float) g;
			for (int c = 0; c <= g - r; c++) {
				float w = c / (float) g;
				float u = 1f - v - w;
				int idx = r * rowStride + c;
				int vo = idx * STRIDE;
				for (int k = 0; k < STRIDE; k++) {
					gridF[vo + k] = u * poly[a0 + k] + v * poly[a1 + k] + w * poly[a2 + k];
				}
				gridD[idx] = volume.sdf(gridF[vo + 3], gridF[vo + 4], gridF[vo + 5]);
			}
		}
		for (int r = 0; r < g; r++) {
			for (int c = 0; c < g - r; c++) {
				int p00 = r * rowStride + c;
				int p10 = (r + 1) * rowStride + c;
				int p01 = r * rowStride + (c + 1);
				clipMicroTri(p00, p01, p10, s0);
				if (c + 1 < g - r) {
					int p11 = (r + 1) * rowStride + (c + 1);
					clipMicroTri(p01, p11, p10, s0);
				}
			}
		}
	}

	private void clipMicroTri(int ia, int ib, int ic, int srcIdx) {
		float da = gridD[ia], db = gridD[ib], dc = gridD[ic];
		boolean ina = da <= 0f, inb = db <= 0f, inc = dc <= 0f;
		int cnt = (ina ? 1 : 0) + (inb ? 1 : 0) + (inc ? 1 : 0);
		if (cnt == 0) return;
		if (cnt == 3) {
			putGrid(ia, srcIdx);
			putGrid(ib, srcIdx);
			putGrid(ic, srcIdx);
			putGrid(ic, srcIdx);
			return;
		}
		int a = ia, b = ib, c = ic;
		float aD = da, bD = db, cD = dc;
		if (cnt == 1) {
			if (inb) { a = ib; b = ic; c = ia; aD = db; bD = dc; cD = da; }
			else if (inc) { a = ic; b = ia; c = ib; aD = dc; bD = da; cD = db; }
			bisectEdge(a, b, aD, bD, edgeAB);
			bisectEdge(a, c, aD, cD, edgeAC);
			putGrid(a, srcIdx);
			putArr(edgeAB, 0, srcIdx);
			putArr(edgeAC, 0, srcIdx);
			putArr(edgeAC, 0, srcIdx);
			recordBoundarySegment(edgeAB, edgeAC);
		} else {
			if (!inb) { a = ib; b = ic; c = ia; aD = db; bD = dc; cD = da; }
			else if (!inc) { a = ic; b = ia; c = ib; aD = dc; bD = da; cD = db; }
			bisectEdge(a, b, aD, bD, edgeAB);
			bisectEdge(a, c, aD, cD, edgeAC);
			putGrid(b, srcIdx);
			putGrid(c, srcIdx);
			putArr(edgeAC, 0, srcIdx);
			putArr(edgeAB, 0, srcIdx);
			recordBoundarySegment(edgeAB, edgeAC);
		}
	}

	private void bisectEdge(int i0, int i1, float d0, float d1, float[] dst) {
		float tLo = 0f, tHi = 1f;
		float fLo = d0;
		float t = (d0 == d1) ? 0.5f : Math.max(0f, Math.min(1f, d0 / (d0 - d1)));
		int o0 = i0 * STRIDE, o1 = i1 * STRIDE;
		for (int step = 0; step < 2; step++) {
			float mx = gridF[o0 + 3] + (gridF[o1 + 3] - gridF[o0 + 3]) * t;
			float my = gridF[o0 + 4] + (gridF[o1 + 4] - gridF[o0 + 4]) * t;
			float mz = gridF[o0 + 5] + (gridF[o1 + 5] - gridF[o0 + 5]) * t;
			float dm = volume.sdf(mx, my, mz);
			if ((dm <= 0f) == (fLo <= 0f)) {
				tLo = t;
				fLo = dm;
			} else {
				tHi = t;
			}
			t = 0.5f * (tLo + tHi);
		}
		for (int k = 0; k < STRIDE; k++) {
			dst[k] = gridF[o0 + k] + (gridF[o1 + k] - gridF[o0 + k]) * t;
		}
	}

	private void emitRawQuad() {
		for (int i = 0; i < 4; i++) putArr(inF, i * STRIDE, i);
	}

	private void emitPolygonFan(float[] poly, int[] src, int nv) {
		for (int j = 1; j + 1 < nv; j += 2) {
			int c = Math.min(j + 2, nv - 1);
			putArr(poly, 0, src[0]);
			putArr(poly, j * STRIDE, src[j]);
			putArr(poly, (j + 1) * STRIDE, src[j + 1]);
			putArr(poly, c * STRIDE, src[c]);
		}
	}

	private void putGrid(int idx, int k) {
		putArr(gridF, idx * STRIDE, k);
	}

	private void putArr(float[] arr, int o, int k) {
		int io = (k & 3) * 3;
		float x = arr[o], y = arr[o + 1], z = arr[o + 2];
		int color = inI[io];
		float u = arr[o + 6], v = arr[o + 7];
		int overlay = inI[io + 1], light = inI[io + 2];
		float nx = arr[o + 8], ny = arr[o + 9], nz = arr[o + 10];
		try {
			delegate.vertex(x, y, z, color, u, v, overlay, light, nx, ny, nz);
		} catch (IllegalStateException ex) {
			if (rawProvider != null && layer != null) {
				VertexConsumer refreshed = getRawBuffer(rawProvider, layer);
				if (refreshed != null && refreshed != this) {
					if (refreshed instanceof VolumeSliceConsumer vsc) refreshed = vsc.delegate;
					delegate = refreshed;
					delegate.vertex(x, y, z, color, u, v, overlay, light, nx, ny, nz);
					return;
				}
			}
			throw ex;
		}
	}

	@Override
	public VertexConsumer vertex(float x, float y, float z) {
		if (hasPending) {
			hasPending = false;
			vertex(curX, curY, curZ, curColor, curU, curV, curOverlay, curLight, 0f, 1f, 0f);
		}
		curX = x;
		curY = y;
		curZ = z;
		curU = 0f;
		curV = 0f;
		curColor = -1;
		curOverlay = OverlayTexture.DEFAULT_UV;
		curLight = 0;
		hasPending = true;
		return this;
	}

	@Override
	public VertexConsumer color(int r, int g, int b, int a) {
		curColor = ((a & 255) << 24) | ((r & 255) << 16) | ((g & 255) << 8) | (b & 255);
		return this;
	}

	@Override
	public VertexConsumer texture(float u, float v) {
		curU = u;
		curV = v;
		return this;
	}

	@Override
	public VertexConsumer overlay(int u, int v) {
		curOverlay = (u & 0xFFFF) | ((v & 0xFFFF) << 16);
		return this;
	}

	@Override
	public VertexConsumer light(int u, int v) {
		curLight = (u & 0xFFFF) | ((v & 0xFFFF) << 16);
		return this;
	}

	@Override
	public VertexConsumer normal(float x, float y, float z) {
		if (hasPending) {
			hasPending = false;
			vertex(curX, curY, curZ, curColor, curU, curV, curOverlay, curLight, x, y, z);
		} else {
			delegate.normal(x, y, z);
		}
		return this;
	}
}
