package dev.opeope.client.dismember;

import dev.opeope.OpeOpeMod;
import dev.opeope.anatomy.Anatomy;
import dev.opeope.anatomy.CutOp;
import dev.opeope.anatomy.CutVolume;
import net.minecraft.client.render.LightmapTextureManager;
import net.minecraft.client.render.OverlayTexture;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.MathHelper;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Construye tapas anatómicas reales en postura animada, paredes interiores de túneles/huecos CSG (cilindros, cajas,
 * esferas, lazos libres) y bordes luminosos compatibles con shaders Iris/Photon.
 */
public final class CapBuilder {
	private CapBuilder() {}

	public static final Identifier CUT_CAP_TEX = OpeOpeMod.id("textures/entity/cut_cap.png");
	public static final Identifier CUT_RIM_TEX = OpeOpeMod.id("textures/entity/cut_rim.png");

	private record WallKey(Identifier entity, int segHash, int volHash) {}

	/** Triángulos empaquetados (stride 12: ax,ay,az, bx,by,bz, cx,cy,cz, nx,ny,nz) en píxeles de modelo. */
	private static final Map<WallKey, float[]> WALL_CACHE = Collections.synchronizedMap(
		new LinkedHashMap<>(256, 0.75f, true) {
			@Override
			protected boolean removeEldestEntry(Map.Entry<WallKey, float[]> eldest) {
				return size() > 256;
			}
		});

	public static void clearCache() {
		WALL_CACHE.clear();
	}

	/** Sesión de dibujo para acumular segmentos de borde mientras se renderiza el modelo animado. */
	public static final class Session {
		public final CutVolume volume;
		public final List<CutOp.HalfSpace> planes;
		public final Anatomy anatomy;
		public final Collection<String> activeSegs;
		private final List<float[]>[] planeSegs;
		private final List<float[]> rimSegs = new ArrayList<>();

		@SuppressWarnings("unchecked")
		public Session(CutVolume volume, Anatomy anatomy, Collection<String> activeSegs) {
			this.volume = volume;
			this.planes = volume.allPlanes();
			this.anatomy = anatomy;
			this.activeSegs = activeSegs;
			this.planeSegs = new List[planes.size()];
			for (int i = 0; i < planes.size(); i++) planeSegs[i] = new ArrayList<>();
		}

		public void addPlaneSegment(int planeIdx, float ax, float ay, float az, float bx, float by, float bz) {
			if (planeIdx < 0 || planeIdx >= planeSegs.length) return;
			List<float[]> list = planeSegs[planeIdx];
			if (list.size() >= 512) return;
			float dx = bx - ax, dy = by - ay, dz = bz - az;
			if (dx * dx + dy * dy + dz * dz < 1e-4f) return;
			list.add(new float[]{ax, ay, az, bx, by, bz});
		}

		public void addRimSegment(float ax, float ay, float az, float bx, float by, float bz) {
			if (rimSegs.size() >= 1024) return;
			float dx = bx - ax, dy = by - ay, dz = bz - az;
			if (dx * dx + dy * dy + dz * dz < 1e-4f) return;
			rimSegs.add(new float[]{ax, ay, az, bx, by, bz});
		}

		/** Dibuja todas las tapas, paredes CSG y bordes luminosos sobre {@code modelPxToRender}. */
		public void flush(Matrix4f modelPxToRender, VertexConsumerProvider vcp, int light, int overlay,
						  float time, boolean locked, boolean frozen, boolean sliceSide) {
			if (anatomy == null || volume.isWhole()) return;
			int col = anatomy.capColor;
			int r = (col >> 16) & 255, g = (col >> 8) & 255, b = col & 255;

			VertexConsumer capVc = vcp.getBuffer(RenderLayer.getEntityCutoutNoCull(CUT_CAP_TEX));
			List<float[]> glowSegs = new ArrayList<>(rimSegs);
			Vector3f tmpA = new Vector3f(), tmpB = new Vector3f(), tmpC = new Vector3f(), tmpN = new Vector3f();

			// 1. Tapas planas (desde aristas animadas reales o respaldo Anatomy.section recortado)
			for (int pi = 0; pi < planes.size(); pi++) {
				CutOp.HalfSpace hs = planes.get(pi);
				List<float[]> segs = planeSegs[pi];
				int builtLoops = 0;
				if (segs.size() >= 3) {
					builtLoops = drawStitchedLoops(capVc, modelPxToRender, hs, segs, volume, r, g, b, overlay, light, sliceSide, glowSegs, tmpA, tmpB, tmpC, tmpN);
				}
				if (builtLoops == 0) {
					drawFallbackPlaneCaps(capVc, modelPxToRender, hs, anatomy, activeSegs, volume, r, g, b, overlay, light, sliceSide, glowSegs, tmpA, tmpB, tmpC, tmpN);
				}
			}

			// 2. Paredes de formas no planares (túnel cilíndrico, ventana caja, esfera, lazo libre)
			if (!volume.nonPlanarOps().isEmpty()) {
				float[] wallTris = cachedWalls(anatomy, activeSegs, volume);
				emitWallTriangles(capVc, modelPxToRender, wallTris, r, g, b, overlay, light, tmpA, tmpB, tmpC, tmpN);
			}

			// 3. Borde luminoso quirúrgico
			if (!glowSegs.isEmpty()) {
				float pulse = 0.65f + 0.35f * MathHelper.sin(time * (frozen ? 0.55f : 0.25f));
				int er = locked ? 255 : (frozen ? 205 : 143);
				int eg = locked ? 227 : (frozen ? 245 : 216);
				int eb = locked ? 106 : 255;
				int alpha = (int) ((frozen ? 235 : 200) * pulse);
				VertexConsumer rimVc = vcp.getBuffer(RenderLayer.getEntityTranslucentEmissive(CUT_RIM_TEX));
				Vector3f eyeDir = new Vector3f(0, 1, 0);
				for (float[] sg : glowSegs) {
					emitRimQuad(rimVc, modelPxToRender, sg[0], sg[1], sg[2], sg[3], sg[4], sg[5],
						0.38f, er, eg, eb, alpha, OverlayTexture.DEFAULT_UV, LightmapTextureManager.MAX_LIGHT_COORDINATE,
						tmpA, tmpB, tmpN, eyeDir);
				}
			}
		}
	}

	// ------------------------------------------------------------------ tapas en articulaciones

	/** Tapa por corte en articulación con textura real, normales e iluminación compatible con Photon/Iris. */
	public static void drawJointCap(MatrixStack ms, VertexConsumerProvider vcp, Anatomy a, Anatomy.Segment sg,
									int light, int overlay, float time, boolean locked, boolean partSide) {
		Anatomy.Segment par = sg.parent() == null ? null : a.get(sg.parent());
		var pc = par != null ? par.center() : a.get(a.core) != null ? a.get(a.core).center() : sg.jointVec();
		var d = sg.center().subtract(pc);
		double[] b = sg.box();
		int axis;
		double ax = Math.abs(d.x), ay = Math.abs(d.y), az = Math.abs(d.z);
		if (ay >= ax && ay >= az) axis = 1;
		else if (ax >= az) axis = 0;
		else axis = 2;
		int u = axis == 0 ? 1 : 0, v = axis == 2 ? 1 : 2;
		double cu = (b[u] + b[u + 3]) / 2, cv = (b[v] + b[v + 3]) / 2;
		double hu = (b[u + 3] - b[u]) / 2, hv = (b[v + 3] - b[v]) / 2;
		double sign = Math.signum(axis == 0 ? d.x : axis == 1 ? d.y : d.z);
		if (sign == 0) sign = 1;
		double plane = sign > 0 ? b[axis] : b[axis + 3];
		double out = partSide ? -sign : sign;
		plane += 0.03 * out;

		Matrix4f mPx = new Matrix4f(ms.peek().getPositionMatrix()).scale(-1f / 16f, 1f / 16f, 1f / 16f);
		float nX = axis == 0 ? (float) out : 0f, nY = axis == 1 ? (float) out : 0f, nZ = axis == 2 ? (float) out : 0f;
		int col = a.capColor;
		int r = (col >> 16) & 255, g = (col >> 8) & 255, bl = col & 255;

		VertexConsumer fill = vcp.getBuffer(RenderLayer.getEntityCutoutNoCull(CUT_CAP_TEX));
		Vector3f ta = new Vector3f(), tb = new Vector3f(), tc = new Vector3f(), td = new Vector3f(), tn = new Vector3f();
		double[][] uv = {{cu - hu, cv - hv}, {cu + hu, cv - hv}, {cu + hu, cv + hv}, {cu - hu, cv + hv}};
		float[][] p4 = new float[4][3];
		for (int k = 0; k < 4; k++) {
			p4[k][axis] = (float) plane;
			p4[k][u] = (float) uv[k][0];
			p4[k][v] = (float) uv[k][1];
		}
		emitQuadLit(fill, mPx, p4[0], p4[1], p4[2], p4[3], nX, nY, nZ, r, g, bl, 255, overlay, light, ta, tb, tc, td, tn);

		// Hueso central claro
		double bpl = plane + 0.015 * out;
		double bhu = hu * 0.34, bhv = hv * 0.34;
		double[][] buv = {{cu - bhu, cv - bhv}, {cu + bhu, cv - bhv}, {cu + bhu, cv + bhv}, {cu - bhu, cv + bhv}};
		for (int k = 0; k < 4; k++) {
			p4[k][axis] = (float) bpl;
			p4[k][u] = (float) buv[k][0];
			p4[k][v] = (float) buv[k][1];
		}
		emitQuadLit(fill, mPx, p4[0], p4[1], p4[2], p4[3], nX, nY, nZ, 246, 242, 232, 255, overlay, light, ta, tb, tc, td, tn);

		// Borde luminoso azul/eléctrico
		float pulse = 0.65f + 0.35f * MathHelper.sin(time * 0.25f);
		int er = locked ? 255 : 143, eg = locked ? 227 : 216, eb = locked ? 106 : 255;
		int al = (int) (210 * pulse);
		VertexConsumer rim = vcp.getBuffer(RenderLayer.getEntityTranslucentEmissive(CUT_RIM_TEX));
		double rpl = plane + 0.025 * out;
		for (int k = 0; k < 4; k++) {
			p4[k][axis] = (float) rpl;
			p4[k][u] = (float) uv[k][0];
			p4[k][v] = (float) uv[k][1];
		}
		for (int k = 0; k < 4; k++) {
			float[] pa = p4[k], pb = p4[(k + 1) % 4];
			emitRimQuad(rim, mPx, pa[0], pa[1], pa[2], pb[0], pb[1], pb[2], 0.36f, er, eg, eb, al,
				OverlayTexture.DEFAULT_UV, LightmapTextureManager.MAX_LIGHT_COORDINATE, ta, tb, tn, new Vector3f(nX, nY, nZ));
		}
	}

	// ------------------------------------------------------------------ cosido de bucles y ear-clipping 2D

	private static int drawStitchedLoops(VertexConsumer vc, Matrix4f mPx, CutOp.HalfSpace hs, List<float[]> segs,
										 CutVolume volume, int r, int g, int b, int overlay, int light,
										 boolean sliceSide, List<float[]> outRimSegs,
										 Vector3f ta, Vector3f tb, Vector3f tc, Vector3f tn) {
		float[][] basis = planeBasis(hs.nx(), hs.ny(), hs.nz());
		float[] uAx = basis[0], vAx = basis[1];
		// Proyectar todos los segmentos a 2D y coser bucles
		int n = segs.size();
		boolean[] used = new boolean[n];
		float[][] s2 = new float[n][4]; // ax, ay, bx, by
		for (int i = 0; i < n; i++) {
			float[] s = segs.get(i);
			s2[i][0] = s[0] * uAx[0] + s[1] * uAx[1] + s[2] * uAx[2];
			s2[i][1] = s[0] * vAx[0] + s[1] * vAx[1] + s[2] * vAx[2];
			s2[i][2] = s[3] * uAx[0] + s[4] * uAx[1] + s[5] * uAx[2];
			s2[i][3] = s[3] * vAx[0] + s[4] * vAx[1] + s[5] * vAx[2];
		}

		int loopsBuilt = 0;
		for (int start = 0; start < n; start++) {
			if (used[start]) continue;
			used[start] = true;
			List<float[]> poly3 = new ArrayList<>();
			List<float[]> poly2 = new ArrayList<>();
			float[] first = segs.get(start);
			poly3.add(new float[]{first[0], first[1], first[2]});
			poly3.add(new float[]{first[3], first[4], first[5]});
			poly2.add(new float[]{s2[start][0], s2[start][1]});
			poly2.add(new float[]{s2[start][2], s2[start][3]});

			float sx = s2[start][0], sy = s2[start][1];
			float hx = s2[start][2], hy = s2[start][3];

			for (int iter = 0; iter < n; iter++) {
				if (poly2.size() >= 3 && dist2(hx, hy, sx, sy) < 0.25f) break;
				int best = -1;
				boolean reverse = false;
				float bestD = 0.36f; // 0.6 px max gap
				for (int j = 0; j < n; j++) {
					if (used[j]) continue;
					float d1 = dist2(hx, hy, s2[j][0], s2[j][1]);
					if (d1 < bestD) {
						bestD = d1;
						best = j;
						reverse = false;
					}
					float d2 = dist2(hx, hy, s2[j][2], s2[j][3]);
					if (d2 < bestD) {
						bestD = d2;
						best = j;
						reverse = true;
					}
				}
				if (best < 0) break;
				used[best] = true;
				float[] bs = segs.get(best);
				float nx3 = reverse ? bs[0] : bs[3], ny3 = reverse ? bs[1] : bs[4], nz3 = reverse ? bs[2] : bs[5];
				float nx2 = reverse ? s2[best][0] : s2[best][2], ny2 = reverse ? s2[best][1] : s2[best][3];
				// Si ya cierra al primer vértice, terminamos
				if (poly2.size() >= 3 && dist2(nx2, ny2, sx, sy) < 0.20f) break;
				// Saltar colineales o muy cercanos
				if (dist2(nx2, ny2, hx, hy) > 0.002f) {
					poly3.add(new float[]{nx3, ny3, nz3});
					poly2.add(new float[]{nx2, ny2});
					hx = nx2;
					hy = ny2;
				}
			}

			if (poly3.size() < 3 || dist2(hx, hy, sx, sy) > 1.2f) continue;
			float area = signedArea2D(poly2);
			if (Math.abs(area) < 0.35f) continue;
			if (area < 0) {
				Collections.reverse(poly3);
				Collections.reverse(poly2);
			}

			// Normal hacia el hueco exterior (donde sdf > 0)
			float cx = 0, cy = 0, cz = 0;
			for (float[] p : poly3) { cx += p[0]; cy += p[1]; cz += p[2]; }
			cx /= poly3.size(); cy /= poly3.size(); cz /= poly3.size();
			float nSign = volume.sdf(cx + hs.nx() * 0.15f, cy + hs.ny() * 0.15f, cz + hs.nz() * 0.15f) >= 0f ? 1f : -1f;
			float enx = hs.nx() * nSign, eny = hs.ny() * nSign, enz = hs.nz() * nSign;
			float off = 0.025f;
			for (float[] p : poly3) {
				p[0] += enx * off;
				p[1] += eny * off;
				p[2] += enz * off;
			}
			cx += enx * off; cy += eny * off; cz += enz * off;

			// Calcular bounds UV 2D
			float minU = Float.MAX_VALUE, minV = Float.MAX_VALUE, maxU = -Float.MAX_VALUE, maxV = -Float.MAX_VALUE;
			for (float[] q : poly2) {
				minU = Math.min(minU, q[0]); minV = Math.min(minV, q[1]);
				maxU = Math.max(maxU, q[0]); maxV = Math.max(maxV, q[1]);
			}
			float spanU = Math.max(0.5f, maxU - minU), spanV = Math.max(0.5f, maxV - minV);

			List<int[]> tris = earClip2D(poly2);
			if (tris.isEmpty()) continue;
			loopsBuilt++;

			for (int[] t : tris) {
				float[] p0 = poly3.get(t[0]), p1 = poly3.get(t[1]), p2 = poly3.get(t[2]);
				float[] q0 = poly2.get(t[0]), q1 = poly2.get(t[1]), q2 = poly2.get(t[2]);
				float u0 = 0.08f + 0.84f * ((q0[0] - minU) / spanU), v0 = 0.08f + 0.84f * ((q0[1] - minV) / spanV);
				float u1 = 0.08f + 0.84f * ((q1[0] - minU) / spanU), v1 = 0.08f + 0.84f * ((q1[1] - minV) / spanV);
				float u2 = 0.08f + 0.84f * ((q2[0] - minU) / spanU), v2 = 0.08f + 0.84f * ((q2[1] - minV) / spanV);
				emitTriLit(vc, mPx, p0, p1, p2, u0, v0, u1, v1, u2, v2, enx, eny, enz, r, g, b, 255, overlay, light, ta, tb, tc, tn);
			}

			// Núcleo óseo interior cuando el área es suficiente
			if (Math.abs(area) >= 6.0f && isConvexOrStarSimple(poly2, cx * uAx[0] + cy * uAx[1] + cz * uAx[2], cx * vAx[0] + cy * vAx[1] + cz * vAx[2])) {
				float[] cOff = {cx + enx * 0.02f, cy + eny * 0.02f, cz + enz * 0.02f};
				List<float[]> bonePoly = new ArrayList<>(poly3.size());
				for (float[] p : poly3) {
					bonePoly.add(new float[]{
						MathHelper.lerp(0.32f, cOff[0], p[0]),
						MathHelper.lerp(0.32f, cOff[1], p[1]),
						MathHelper.lerp(0.32f, cOff[2], p[2])
					});
				}
				for (int[] t : tris) {
					emitTriLit(vc, mPx, bonePoly.get(t[0]), bonePoly.get(t[1]), bonePoly.get(t[2]),
						0.35f, 0.35f, 0.65f, 0.35f, 0.5f, 0.65f,
						enx, eny, enz, 246, 242, 232, 255, overlay, light, ta, tb, tc, tn);
				}
			}

			for (int k = 0; k < poly3.size(); k++) {
				float[] a3 = poly3.get(k), b3 = poly3.get((k + 1) % poly3.size());
				outRimSegs.add(new float[]{a3[0] + enx * 0.03f, a3[1] + eny * 0.03f, a3[2] + enz * 0.03f,
					b3[0] + enx * 0.03f, b3[1] + eny * 0.03f, b3[2] + enz * 0.03f});
			}
		}
		return loopsBuilt;
	}

	private static void drawFallbackPlaneCaps(VertexConsumer vc, Matrix4f mPx, CutOp.HalfSpace hs, Anatomy a,
											  Collection<String> activeSegs, CutVolume volume, int r, int g, int b,
											  int overlay, int light, boolean sliceSide, List<float[]> outRimSegs,
											  Vector3f ta, Vector3f tb, Vector3f tc, Vector3f tn) {
		float[] pl = hs.toFloatArray();
		for (String id : activeSegs) {
			Anatomy.Segment sg = a.get(id);
			if (sg == null) continue;
			List<double[]> poly = Anatomy.section(sg.box(), pl);
			if (poly.size() < 3) continue;
			List<float[]> clipped = clipPolygonByVolume(poly, volume, hs);
			if (clipped.size() < 3) continue;
			float cx = 0, cy = 0, cz = 0;
			for (float[] p : clipped) { cx += p[0]; cy += p[1]; cz += p[2]; }
			cx /= clipped.size(); cy /= clipped.size(); cz /= clipped.size();
			float nSign = volume.sdf(cx + hs.nx() * 0.15f, cy + hs.ny() * 0.15f, cz + hs.nz() * 0.15f) >= 0f ? 1f : -1f;
			float enx = hs.nx() * nSign, eny = hs.ny() * nSign, enz = hs.nz() * nSign;
			float off = 0.025f;
			for (float[] p : clipped) { p[0] += enx * off; p[1] += eny * off; p[2] += enz * off; }
			float[] c3 = {cx + enx * off, cy + eny * off, cz + enz * off};
			for (int k = 0; k < clipped.size(); k++) {
				float[] p = clipped.get(k), q = clipped.get((k + 1) % clipped.size());
				emitTriLit(vc, mPx, c3, p, q, 0.5f, 0.5f, 0.15f, 0.25f, 0.85f, 0.75f,
					enx, eny, enz, r, g, b, 255, overlay, light, ta, tb, tc, tn);
				outRimSegs.add(new float[]{p[0] + enx * 0.03f, p[1] + eny * 0.03f, p[2] + enz * 0.03f,
					q[0] + enx * 0.03f, q[1] + eny * 0.03f, q[2] + enz * 0.03f});
			}
			float[] cBone = {c3[0] + enx * 0.02f, c3[1] + eny * 0.02f, c3[2] + enz * 0.02f};
			for (int k = 0; k < clipped.size(); k++) {
				float[] p = lerp3(cBone, clipped.get(k), 0.33f), q = lerp3(cBone, clipped.get((k + 1) % clipped.size()), 0.33f);
				emitTriLit(vc, mPx, cBone, p, q, 0.5f, 0.5f, 0.35f, 0.35f, 0.65f, 0.65f,
					enx, eny, enz, 246, 242, 232, 255, overlay, light, ta, tb, tc, tn);
			}
		}
	}

	private static List<float[]> clipPolygonByVolume(List<double[]> polyD, CutVolume vol, CutOp.HalfSpace self) {
		List<float[]> poly = new ArrayList<>(polyD.size());
		for (double[] d : polyD) poly.add(new float[]{(float) d[0], (float) d[1], (float) d[2]});
		for (CutOp op : vol.include) {
			if (op == self || !(op instanceof CutOp.HalfSpace other)) continue;
			if (Math.abs(other.nx() * self.nx() + other.ny() * self.ny() + other.nz() * self.nz()) > 0.999f) continue;
			List<float[]> next = new ArrayList<>();
			for (int i = 0; i < poly.size(); i++) {
				float[] a = poly.get(i), b = poly.get((i + 1) % poly.size());
				float da = other.sdf(a[0], a[1], a[2]), db = other.sdf(b[0], b[1], b[2]);
				if (da <= 0f) next.add(a);
				if ((da <= 0f) != (db <= 0f)) {
					float t = da / (da - db);
					next.add(new float[]{a[0] + (b[0] - a[0]) * t, a[1] + (b[1] - a[1]) * t, a[2] + (b[2] - a[2]) * t});
				}
			}
			poly = next;
			if (poly.size() < 3) return List.of();
		}
		return poly;
	}

	// ------------------------------------------------------------------ malla CSG para túneles y formas no planares (con caché)

	private static float[] cachedWalls(Anatomy a, Collection<String> activeSegs, CutVolume vol) {
		WallKey key = new WallKey(a.entity, activeSegs.hashCode(), vol.volumeHash());
		float[] cached = WALL_CACHE.get(key);
		if (cached != null) return cached;

		List<double[]> segBoxes = new ArrayList<>();
		double[] u = {Double.MAX_VALUE, Double.MAX_VALUE, Double.MAX_VALUE, -Double.MAX_VALUE, -Double.MAX_VALUE, -Double.MAX_VALUE};
		for (String id : activeSegs) {
			Anatomy.Segment sg = a.get(id);
			if (sg == null) continue;
			segBoxes.add(sg.box());
			for (int k = 0; k < 3; k++) {
				u[k] = Math.min(u[k], sg.box()[k]);
				u[k + 3] = Math.max(u[k + 3], sg.box()[k + 3]);
			}
		}
		if (segBoxes.isEmpty()) {
			WALL_CACHE.put(key, new float[0]);
			return new float[0];
		}

		List<Float> tris = new ArrayList<>();
		for (CutOp op : vol.include) {
			if (op.isHalfSpace()) continue;
			boolean invertNormals = op.isInverted();
			generateOpWalls(op, invertNormals, vol, op, u, segBoxes, tris);
		}
		for (List<CutOp> g : vol.exclude) {
			for (CutOp op : g) {
				if (op.isHalfSpace()) continue;
				boolean invertNormals = !op.isInverted();
				generateOpWalls(op, invertNormals, vol, op, u, segBoxes, tris);
			}
		}

		float[] out = new float[tris.size()];
		for (int i = 0; i < out.length; i++) out[i] = tris.get(i);
		WALL_CACHE.put(key, out);
		return out;
	}

	private static void generateOpWalls(CutOp op, boolean inward, CutVolume vol, CutOp selfOp, double[] u,
										List<double[]> segBoxes, List<Float> out) {
		if (op instanceof CutOp.SphereVolume s) {
			buildSphereWalls(s, inward, vol, selfOp, segBoxes, out);
		} else if (op instanceof CutOp.CylinderVolume c) {
			buildCylinderWalls(c, inward, vol, selfOp, u, segBoxes, out);
		} else if (op instanceof CutOp.BoxVolume b) {
			buildBoxWalls(b, inward, vol, selfOp, u, segBoxes, out);
		} else if (op instanceof CutOp.PrismVolume p) {
			buildPrismWalls(p, inward, vol, selfOp, u, segBoxes, out);
		}
	}

	private static void buildSphereWalls(CutOp.SphereVolume s, boolean inward, CutVolume vol, CutOp selfOp,
										 List<double[]> segBoxes, List<Float> out) {
		int stacks = 10, slices = 16;
		float[][] ringA = new float[slices + 1][3], ringB = new float[slices + 1][3];
		for (int i = 0; i < stacks; i++) {
			float phi0 = (float) (-Math.PI / 2 + Math.PI * i / stacks);
			float phi1 = (float) (-Math.PI / 2 + Math.PI * (i + 1) / stacks);
			for (int j = 0; j <= slices; j++) {
				float th = (float) (2 * Math.PI * j / slices);
				float c0 = MathHelper.cos(phi0), s0 = MathHelper.sin(phi0);
				float c1 = MathHelper.cos(phi1), s1 = MathHelper.sin(phi1);
				float dx = MathHelper.cos(th), dz = MathHelper.sin(th);
				ringA[j][0] = s.cx() + s.radius() * c0 * dx;
				ringA[j][1] = s.cy() + s.radius() * s0;
				ringA[j][2] = s.cz() + s.radius() * c0 * dz;
				ringB[j][0] = s.cx() + s.radius() * c1 * dx;
				ringB[j][1] = s.cy() + s.radius() * s1;
				ringB[j][2] = s.cz() + s.radius() * c1 * dz;
			}
			for (int j = 0; j < slices; j++) {
				float[] a = ringA[j], b = ringA[j + 1], c = ringB[j + 1], d = ringB[j];
				float nx = ((a[0] + c[0]) * 0.5f - s.cx()), ny = ((a[1] + c[1]) * 0.5f - s.cy()), nz = ((a[2] + c[2]) * 0.5f - s.cz());
				float nl = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
				if (nl > 1e-5f) { nx /= nl; ny /= nl; nz /= nl; }
				if (inward) { nx = -nx; ny = -ny; nz = -nz; }
				tryAddWallTri(a, b, c, nx, ny, nz, vol, selfOp, segBoxes, out);
				tryAddWallTri(a, c, d, nx, ny, nz, vol, selfOp, segBoxes, out);
			}
		}
	}

	private static void buildCylinderWalls(CutOp.CylinderVolume c, boolean inward, CutVolume vol, CutOp selfOp,
										   double[] u, List<double[]> segBoxes, List<Float> out) {
		float[][] basis = planeBasis(c.ax(), c.ay(), c.az());
		float[] rx = basis[0], ry = basis[1];
		float[] rng = axisRange(u, c.cx(), c.cy(), c.cz(), c.ax(), c.ay(), c.az(), c.halfLen());
		if (rng == null) return;
		float tMin = rng[0], tMax = rng[1];
		int rings = Math.max(4, Math.min(12, (int) Math.ceil((tMax - tMin) / 1.5f)));
		int sides = 24;
		for (int i = 0; i < rings; i++) {
			float t0 = tMin + (tMax - tMin) * i / rings;
			float t1 = tMin + (tMax - tMin) * (i + 1) / rings;
			for (int j = 0; j < sides; j++) {
				float th0 = (float) (2 * Math.PI * j / sides);
				float th1 = (float) (2 * Math.PI * (j + 1) / sides);
				float c0 = MathHelper.cos(th0), s0 = MathHelper.sin(th0);
				float c1 = MathHelper.cos(th1), s1 = MathHelper.sin(th1);
				float[] p00 = cylPt(c, rx, ry, t0, c0, s0);
				float[] p01 = cylPt(c, rx, ry, t0, c1, s1);
				float[] p11 = cylPt(c, rx, ry, t1, c1, s1);
				float[] p10 = cylPt(c, rx, ry, t1, c0, s0);
				float cm = (c0 + c1) * 0.5f, sm = (s0 + s1) * 0.5f;
				float nx = rx[0] * cm + ry[0] * sm, ny = rx[1] * cm + ry[1] * sm, nz = rx[2] * cm + ry[2] * sm;
				if (inward) { nx = -nx; ny = -ny; nz = -nz; }
				tryAddWallTri(p00, p01, p11, nx, ny, nz, vol, selfOp, segBoxes, out);
				tryAddWallTri(p00, p11, p10, nx, ny, nz, vol, selfOp, segBoxes, out);
			}
		}
		if (c.halfLen() < 24f) {
			for (int side = -1; side <= 1; side += 2) {
				float t = side * c.halfLen();
				float[] center = {c.cx() + c.ax() * t, c.cy() + c.ay() * t, c.cz() + c.az() * t};
				float nx = c.ax() * side * (inward ? -1 : 1), ny = c.ay() * side * (inward ? -1 : 1), nz = c.az() * side * (inward ? -1 : 1);
				for (int j = 0; j < sides; j++) {
					float th0 = (float) (2 * Math.PI * j / sides), th1 = (float) (2 * Math.PI * (j + 1) / sides);
					float[] pa = cylPt(c, rx, ry, t, MathHelper.cos(th0), MathHelper.sin(th0));
					float[] pb = cylPt(c, rx, ry, t, MathHelper.cos(th1), MathHelper.sin(th1));
					tryAddWallTri(center, pa, pb, nx, ny, nz, vol, selfOp, segBoxes, out);
				}
			}
		}
	}

	private static float[] cylPt(CutOp.CylinderVolume c, float[] rx, float[] ry, float t, float cos, float sin) {
		return new float[]{
			c.cx() + c.ax() * t + (rx[0] * cos + ry[0] * sin) * c.radius(),
			c.cy() + c.ay() * t + (rx[1] * cos + ry[1] * sin) * c.radius(),
			c.cz() + c.az() * t + (rx[2] * cos + ry[2] * sin) * c.radius()
		};
	}

	private static void buildBoxWalls(CutOp.BoxVolume b, boolean inward, CutVolume vol, CutOp selfOp,
									  double[] u, List<double[]> segBoxes, List<Float> out) {
		float cap = (float) Math.max(16.0, (u[3] - u[0]) + (u[4] - u[1]) + (u[5] - u[2]));
		float hx = Math.min(b.hx(), cap), hy = Math.min(b.hy(), cap), hz = Math.min(b.hz(), cap);
		float[][] axes = {{b.rx(), b.ry(), b.rz()}, {b.ux(), b.uy(), b.uz()}, {b.fx(), b.fy(), b.fz()}};
		float[] half = {hx, hy, hz};
		int grid = 6;
		for (int ax = 0; ax < 3; ax++) {
			int uIdx = (ax + 1) % 3, vIdx = (ax + 2) % 3;
			for (int sgn = -1; sgn <= 1; sgn += 2) {
				if (half[ax] >= cap - 0.1f) continue; // cara lejana infinita en modo pasante
				float nx = axes[ax][0] * sgn * (inward ? -1 : 1);
				float ny = axes[ax][1] * sgn * (inward ? -1 : 1);
				float nz = axes[ax][2] * sgn * (inward ? -1 : 1);
				for (int iu = 0; iu < grid; iu++) {
					float ua0 = -half[uIdx] + 2f * half[uIdx] * iu / grid;
					float ua1 = -half[uIdx] + 2f * half[uIdx] * (iu + 1) / grid;
					for (int iv = 0; iv < grid; iv++) {
						float va0 = -half[vIdx] + 2f * half[vIdx] * iv / grid;
						float va1 = -half[vIdx] + 2f * half[vIdx] * (iv + 1) / grid;
						float[] p00 = boxPt(b, axes, ax, sgn * half[ax], uIdx, ua0, vIdx, va0);
						float[] p10 = boxPt(b, axes, ax, sgn * half[ax], uIdx, ua1, vIdx, va0);
						float[] p11 = boxPt(b, axes, ax, sgn * half[ax], uIdx, ua1, vIdx, va1);
						float[] p01 = boxPt(b, axes, ax, sgn * half[ax], uIdx, ua0, vIdx, va1);
						tryAddWallTri(p00, p10, p11, nx, ny, nz, vol, selfOp, segBoxes, out);
						tryAddWallTri(p00, p11, p01, nx, ny, nz, vol, selfOp, segBoxes, out);
					}
				}
			}
		}
	}

	private static float[] boxPt(CutOp.BoxVolume b, float[][] axes, int ax, float fa, int u, float fu, int v, float fv) {
		return new float[]{
			b.cx() + axes[ax][0] * fa + axes[u][0] * fu + axes[v][0] * fv,
			b.cy() + axes[ax][1] * fa + axes[u][1] * fu + axes[v][1] * fv,
			b.cz() + axes[ax][2] * fa + axes[u][2] * fu + axes[v][2] * fv
		};
	}

	private static void buildPrismWalls(CutOp.PrismVolume p, boolean inward, CutVolume vol, CutOp selfOp,
										double[] u, List<double[]> segBoxes, List<Float> out) {
		float[] rng = axisRange(u, p.ox(), p.oy(), p.oz(), p.ax(), p.ay(), p.az(), p.halfDepth());
		if (rng == null) return;
		float dMin = rng[0], dMax = rng[1];
		int rings = Math.max(4, Math.min(12, (int) Math.ceil((dMax - dMin) / 1.5f)));
		int nv = p.polyX().length;
		for (int e = 0; e < nv; e++) {
			int next = (e + 1) % nv;
			float ex = p.polyX()[next] - p.polyX()[e], ey = p.polyY()[next] - p.polyY()[e];
			float len = (float) Math.sqrt(ex * ex + ey * ey);
			if (len < 1e-4f) continue;
			// Normal 2D perpendicular en plano (u, v) asumiendo lazo CCW -> (ey, -ex) apunta hacia fuera
			float nu = ey / len, nvc = -ex / len;
			float nx = (p.ux() * nu + p.vx() * nvc) * (inward ? -1 : 1);
			float ny = (p.uy() * nu + p.vy() * nvc) * (inward ? -1 : 1);
			float nz = (p.uz() * nu + p.vz() * nvc) * (inward ? -1 : 1);
			int sub = Math.max(1, Math.min(4, (int) Math.ceil(len / 2.2f)));
			for (int s = 0; s < sub; s++) {
				float f0 = s / (float) sub, f1 = (s + 1) / (float) sub;
				float x0 = MathHelper.lerp(f0, p.polyX()[e], p.polyX()[next]);
				float y0 = MathHelper.lerp(f0, p.polyY()[e], p.polyY()[next]);
				float x1 = MathHelper.lerp(f1, p.polyX()[e], p.polyX()[next]);
				float y1 = MathHelper.lerp(f1, p.polyY()[e], p.polyY()[next]);
				for (int i = 0; i < rings; i++) {
					float d0 = dMin + (dMax - dMin) * i / rings;
					float d1 = dMin + (dMax - dMin) * (i + 1) / rings;
					float[] p00 = prismPt(p, x0, y0, d0);
					float[] p01 = prismPt(p, x1, y1, d0);
					float[] p11 = prismPt(p, x1, y1, d1);
					float[] p10 = prismPt(p, x0, y0, d1);
					tryAddWallTri(p00, p01, p11, nx, ny, nz, vol, selfOp, segBoxes, out);
					tryAddWallTri(p00, p11, p10, nx, ny, nz, vol, selfOp, segBoxes, out);
				}
			}
		}
		if (p.halfDepth() < 24f) {
			List<float[]> poly2 = new ArrayList<>(nv);
			for (int i = 0; i < nv; i++) poly2.add(new float[]{p.polyX()[i], p.polyY()[i]});
			if (signedArea2D(poly2) < 0) Collections.reverse(poly2);
			List<int[]> tris = earClip2D(poly2);
			for (int side = -1; side <= 1; side += 2) {
				float dd = side * p.halfDepth();
				float nx = p.ax() * side * (inward ? -1 : 1);
				float ny = p.ay() * side * (inward ? -1 : 1);
				float nz = p.az() * side * (inward ? -1 : 1);
				for (int[] t : tris) {
					float[] a2 = poly2.get(t[0]), b2 = poly2.get(t[1]), c2 = poly2.get(t[2]);
					tryAddWallTri(prismPt(p, a2[0], a2[1], dd), prismPt(p, b2[0], b2[1], dd), prismPt(p, c2[0], c2[1], dd),
						nx, ny, nz, vol, selfOp, segBoxes, out);
				}
			}
		}
	}

	private static float[] prismPt(CutOp.PrismVolume p, float px, float py, float pd) {
		return new float[]{
			p.ox() + p.ux() * px + p.vx() * py + p.ax() * pd,
			p.oy() + p.uy() * px + p.vy() * py + p.ay() * pd,
			p.oz() + p.uz() * px + p.vz() * py + p.az() * pd
		};
	}

	private static float[] axisRange(double[] u, float ox, float oy, float oz, float ax, float ay, float az, float limit) {
		float len = (float) Math.sqrt(ax * ax + ay * ay + az * az);
		if (len < 1e-6f) return null;
		float ux = ax / len, uy = ay / len, uz = az / len;
		float tMin = Float.MAX_VALUE, tMax = -Float.MAX_VALUE;
		for (int ix = 0; ix <= 3; ix += 3) {
			for (int iy = 1; iy <= 4; iy += 3) {
				for (int iz = 2; iz <= 5; iz += 3) {
					float t = (float) ((u[ix] - ox) * ux + (u[iy] - oy) * uy + (u[iz] - oz) * uz);
					if (t < tMin) tMin = t;
					if (t > tMax) tMax = t;
				}
			}
		}
		tMin = Math.max(tMin, -limit);
		tMax = Math.min(tMax, limit);
		return tMin >= tMax - 1e-3f ? null : new float[]{tMin, tMax};
	}

	private static void tryAddWallTri(float[] a, float[] b, float[] c, float nx, float ny, float nz,
									  CutVolume vol, CutOp selfOp, List<double[]> segBoxes, List<Float> out) {
		float tMinX = Math.min(a[0], Math.min(b[0], c[0])), tMaxX = Math.max(a[0], Math.max(b[0], c[0]));
		float tMinY = Math.min(a[1], Math.min(b[1], c[1])), tMaxY = Math.max(a[1], Math.max(b[1], c[1]));
		float tMinZ = Math.min(a[2], Math.min(b[2], c[2])), tMaxZ = Math.max(a[2], Math.max(b[2], c[2]));
		for (double[] box : segBoxes) {
			if (tMaxX < box[0] || tMinX > box[3] || tMaxY < box[1] || tMinY > box[4] || tMaxZ < box[2] || tMinZ > box[5]) {
				continue;
			}
			List<float[]> poly = clipTriToBox(a, b, c, box);
			for (int k = 1; k + 1 < poly.size(); k++) {
				float[] p0 = poly.get(0), p1 = poly.get(k), p2 = poly.get(k + 1);
				float ux = p1[0] - p0[0], uy = p1[1] - p0[1], uz = p1[2] - p0[2];
				float vx = p2[0] - p0[0], vy = p2[1] - p0[1], vz = p2[2] - p0[2];
				float cx = uy * vz - uz * vy, cy = uz * vx - ux * vz, cz = ux * vy - uy * vx;
				if (cx * cx + cy * cy + cz * cz < 1e-6f) continue;
				float mx = (p0[0] + p1[0] + p2[0]) / 3f;
				float my = (p0[1] + p1[1] + p2[1]) / 3f;
				float mz = (p0[2] + p1[2] + p2[2]) / 3f;
				if (Math.abs(vol.sdf(mx, my, mz)) > 0.45f) continue;
				out.add(p0[0]); out.add(p0[1]); out.add(p0[2]);
				out.add(p1[0]); out.add(p1[1]); out.add(p1[2]);
				out.add(p2[0]); out.add(p2[1]); out.add(p2[2]);
				out.add(nx); out.add(ny); out.add(nz);
			}
		}
	}

	private static List<float[]> clipTriToBox(float[] a, float[] b, float[] c, double[] box) {
		List<float[]> poly = new ArrayList<>(6);
		poly.add(a);
		poly.add(b);
		poly.add(c);
		for (int axis = 0; axis < 3; axis++) {
			poly = clipPlaneAABB(poly, axis, (float) box[axis], true);
			if (poly.size() < 3) return poly;
			poly = clipPlaneAABB(poly, axis, (float) box[axis + 3], false);
			if (poly.size() < 3) return poly;
		}
		return poly;
	}

	private static List<float[]> clipPlaneAABB(List<float[]> poly, int axis, float bound, boolean keepGreater) {
		List<float[]> out = new ArrayList<>(poly.size() + 1);
		int n = poly.size();
		for (int i = 0; i < n; i++) {
			float[] pa = poly.get(i), pb = poly.get((i + 1) % n);
			float da = keepGreater ? (pa[axis] - bound) : (bound - pa[axis]);
			float db = keepGreater ? (pb[axis] - bound) : (bound - pb[axis]);
			boolean ina = da >= 0f, inb = db >= 0f;
			if (ina) out.add(pa);
			if (ina != inb) {
				float t = da / (da - db);
				float[] hit = {
					pa[0] + (pb[0] - pa[0]) * t,
					pa[1] + (pb[1] - pa[1]) * t,
					pa[2] + (pb[2] - pa[2]) * t
				};
				hit[axis] = bound;
				out.add(hit);
			}
		}
		return out;
	}

	private static void emitWallTriangles(VertexConsumer vc, Matrix4f mPx, float[] tris, int r, int g, int b,
										  int overlay, int light, Vector3f ta, Vector3f tb, Vector3f tc, Vector3f tn) {
		float[] p0 = new float[3], p1 = new float[3], p2 = new float[3];
		for (int i = 0; i + 11 < tris.length; i += 12) {
			p0[0] = tris[i]; p0[1] = tris[i + 1]; p0[2] = tris[i + 2];
			p1[0] = tris[i + 3]; p1[1] = tris[i + 4]; p1[2] = tris[i + 5];
			p2[0] = tris[i + 6]; p2[1] = tris[i + 7]; p2[2] = tris[i + 8];
			emitTriLit(vc, mPx, p0, p1, p2, 0.2f, 0.2f, 0.8f, 0.2f, 0.5f, 0.8f,
				tris[i + 9], tris[i + 10], tris[i + 11], r, g, b, 255, overlay, light, ta, tb, tc, tn);
		}
	}

	// ------------------------------------------------------------------ geometría 2D y emisión VertexConsumer

	private static void emitTriLit(VertexConsumer vc, Matrix4f mPx, float[] p0, float[] p1, float[] p2,
								   float u0, float v0, float u1, float v1, float u2, float v2,
								   float nx, float ny, float nz, int r, int g, int b, int a, int overlay, int light,
								   Vector3f ta, Vector3f tb, Vector3f tc, Vector3f tn) {
		mPx.transformPosition(p0[0], p0[1], p0[2], ta);
		mPx.transformPosition(p1[0], p1[1], p1[2], tb);
		mPx.transformPosition(p2[0], p2[1], p2[2], tc);
		mPx.transformDirection(nx, ny, nz, tn).normalize();
		int argb = ((a & 255) << 24) | ((r & 255) << 16) | ((g & 255) << 8) | (b & 255);
		vc.vertex(ta.x, ta.y, ta.z, argb, u0, v0, overlay, light, tn.x, tn.y, tn.z);
		vc.vertex(tb.x, tb.y, tb.z, argb, u1, v1, overlay, light, tn.x, tn.y, tn.z);
		vc.vertex(tc.x, tc.y, tc.z, argb, u2, v2, overlay, light, tn.x, tn.y, tn.z);
		vc.vertex(tc.x, tc.y, tc.z, argb, u2, v2, overlay, light, tn.x, tn.y, tn.z);
	}

	private static void emitQuadLit(VertexConsumer vc, Matrix4f mPx, float[] p0, float[] p1, float[] p2, float[] p3,
									float nx, float ny, float nz, int r, int g, int b, int a, int overlay, int light,
									Vector3f ta, Vector3f tb, Vector3f tc, Vector3f td, Vector3f tn) {
		mPx.transformPosition(p0[0], p0[1], p0[2], ta);
		mPx.transformPosition(p1[0], p1[1], p1[2], tb);
		mPx.transformPosition(p2[0], p2[1], p2[2], tc);
		mPx.transformPosition(p3[0], p3[1], p3[2], td);
		mPx.transformDirection(nx, ny, nz, tn).normalize();
		int argb = ((a & 255) << 24) | ((r & 255) << 16) | ((g & 255) << 8) | (b & 255);
		vc.vertex(ta.x, ta.y, ta.z, argb, 0.08f, 0.08f, overlay, light, tn.x, tn.y, tn.z);
		vc.vertex(tb.x, tb.y, tb.z, argb, 0.92f, 0.08f, overlay, light, tn.x, tn.y, tn.z);
		vc.vertex(tc.x, tc.y, tc.z, argb, 0.92f, 0.92f, overlay, light, tn.x, tn.y, tn.z);
		vc.vertex(td.x, td.y, td.z, argb, 0.08f, 0.92f, overlay, light, tn.x, tn.y, tn.z);
	}

	private static void emitRimQuad(VertexConsumer vc, Matrix4f mPx,
									float ax, float ay, float az, float bx, float by, float bz,
									float widthPx, int r, int g, int b, int a, int overlay, int light,
									Vector3f ta, Vector3f tb, Vector3f tn, Vector3f fallbackUp) {
		float dx = bx - ax, dy = by - ay, dz = bz - az;
		float len = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
		if (len < 1e-4f) return;
		dx /= len; dy /= len; dz /= len;
		// Dos normales perpendiculares cruzadas para visibilidad en cualquier ángulo
		float px = dy * 1f - dz * 0f, py = dz * 0f - dx * 1f, pz = dx * 0f - dy * 0f;
		if (Math.abs(dy) > 0.9f) { px = dz; py = 0f; pz = -dx; }
		float pl = (float) Math.sqrt(px * px + py * py + pz * pz);
		if (pl < 1e-4f) {
			px = fallbackUp.y * dz - fallbackUp.z * dy;
			py = fallbackUp.z * dx - fallbackUp.x * dz;
			pz = fallbackUp.x * dy - fallbackUp.y * dx;
			pl = (float) Math.sqrt(px * px + py * py + pz * pz);
			if (pl < 1e-4f) return;
		}
		float hw = (widthPx * 0.5f) / pl;
		px *= hw; py *= hw; pz *= hw;

		mPx.transformPosition(ax - px, ay - py, az - pz, ta);
		mPx.transformPosition(bx - px, by - py, bz - pz, tb);
		mPx.transformDirection(0, 1, 0, tn).normalize();
		int argb = ((a & 255) << 24) | ((r & 255) << 16) | ((g & 255) << 8) | (b & 255);
		vc.vertex(ta.x, ta.y, ta.z, argb, 0f, 0f, overlay, light, tn.x, tn.y, tn.z);
		vc.vertex(tb.x, tb.y, tb.z, argb, 1f, 0f, overlay, light, tn.x, tn.y, tn.z);
		mPx.transformPosition(bx + px, by + py, bz + pz, tb);
		vc.vertex(tb.x, tb.y, tb.z, argb, 1f, 1f, overlay, light, tn.x, tn.y, tn.z);
		mPx.transformPosition(ax + px, ay + py, az + pz, ta);
		vc.vertex(ta.x, ta.y, ta.z, argb, 0f, 1f, overlay, light, tn.x, tn.y, tn.z);
	}

	private static float[][] planeBasis(float nx, float ny, float nz) {
		float ux, uy, uz;
		if (Math.abs(nx) < 0.85f) { ux = 0f; uy = nz; uz = -ny; }
		else { ux = -nz; uy = 0f; uz = nx; }
		float ul = (float) Math.sqrt(ux * ux + uy * uy + uz * uz);
		ux /= ul; uy /= ul; uz /= ul;
		float vx = ny * uz - nz * uy, vy = nz * ux - nx * uz, vz = nx * uy - ny * ux;
		return new float[][]{{ux, uy, uz}, {vx, vy, vz}};
	}

	private static float dist2(float ax, float ay, float bx, float by) {
		float dx = bx - ax, dy = by - ay;
		return dx * dx + dy * dy;
	}

	private static float[] lerp3(float[] a, float[] b, float t) {
		return new float[]{a[0] + (b[0] - a[0]) * t, a[1] + (b[1] - a[1]) * t, a[2] + (b[2] - a[2]) * t};
	}

	public static float signedArea2D(List<float[]> poly) {
		float a = 0;
		for (int i = 0; i < poly.size(); i++) {
			float[] p = poly.get(i), q = poly.get((i + 1) % poly.size());
			a += p[0] * q[1] - q[0] * p[1];
		}
		return a * 0.5f;
	}

	private static boolean isConvexOrStarSimple(List<float[]> poly, float cu, float cv) {
		for (int i = 0; i < poly.size(); i++) {
			float[] p = poly.get(i), q = poly.get((i + 1) % poly.size());
			if ((q[0] - p[0]) * (cv - p[1]) - (q[1] - p[1]) * (cu - p[0]) <= 0f) return false;
		}
		return true;
	}

	/** Triangulación Ear-Clipping 2D (polígonos CCW simples, incluidos cóncavos). */
	public static List<int[]> earClip2D(List<float[]> poly) {
		int n = poly.size();
		List<int[]> out = new ArrayList<>();
		if (n < 3) return out;
		if (n == 3) {
			out.add(new int[]{0, 1, 2});
			return out;
		}
		List<Integer> idx = new ArrayList<>(n);
		for (int i = 0; i < n; i++) idx.add(i);
		int guard = n * n + 10;
		while (idx.size() > 3 && --guard > 0) {
			boolean clipped = false;
			int m = idx.size();
			for (int i = 0; i < m; i++) {
				int prev = idx.get((i + m - 1) % m);
				int curr = idx.get(i);
				int next = idx.get((i + 1) % m);
				float[] a = poly.get(prev), b = poly.get(curr), c = poly.get(next);
				if (cross2(a, b, c) <= 1e-4f) continue;
				boolean contains = false;
				for (int j = 0; j < m; j++) {
					int k = idx.get(j);
					if (k == prev || k == curr || k == next) continue;
					if (pointInTri(poly.get(k), a, b, c)) {
						contains = true;
						break;
					}
				}
				if (!contains) {
					out.add(new int[]{prev, curr, next});
					idx.remove(i);
					clipped = true;
					break;
				}
			}
			if (!clipped) break;
		}
		if (idx.size() == 3) out.add(new int[]{idx.get(0), idx.get(1), idx.get(2)});
		return out;
	}

	private static float cross2(float[] a, float[] b, float[] c) {
		return (b[0] - a[0]) * (c[1] - a[1]) - (b[1] - a[1]) * (c[0] - a[0]);
	}

	private static boolean pointInTri(float[] p, float[] a, float[] b, float[] c) {
		float c1 = cross2(a, b, p), c2 = cross2(b, c, p), c3 = cross2(c, a, p);
		return c1 >= -1e-4f && c2 >= -1e-4f && c3 >= -1e-4f;
	}
}
