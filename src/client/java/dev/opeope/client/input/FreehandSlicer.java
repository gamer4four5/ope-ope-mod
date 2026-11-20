package dev.opeope.client.input;

import dev.opeope.net.Payloads;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.Entity;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Lazo a mano alzada sobre entidades en modo Bisturí: congela temporalmente la cámara mientras dibujas cualquier
 * silueta cerrada en 2D sobre la mira, la simplifica con Ramer-Douglas-Peucker a 4..24 vértices, proyecta al plano del
 * objetivo en coordenadas reales de mundo y envía {@link Payloads.CutShape} como prisma extruido.
 */
public final class FreehandSlicer {
	private FreehandSlicer() {}

	private static boolean active;
	private static int targetId = -1;
	private static Vec3d anchor = Vec3d.ZERO;
	private static Vec3d lookDir = new Vec3d(0, 0, 1);
	private static Vec3d rightDir = new Vec3d(1, 0, 0);
	private static Vec3d upDir = new Vec3d(0, 1, 0);
	private static double cursorYawDeg, cursorPitchDeg;
	private static final List<double[]> angularPts = new ArrayList<>(); // {yawDeg, pitchDeg}

	public static void begin(Entity target, Vec3d hitPos, Vec3d look, Vec3d right, Vec3d up) {
		active = true;
		targetId = target != null ? target.getId() : -1;
		anchor = hitPos;
		lookDir = look.normalize();
		rightDir = right.normalize();
		upDir = up.normalize();
		cursorYawDeg = 0;
		cursorPitchDeg = 0;
		angularPts.clear();
		angularPts.add(new double[]{0, 0});
	}

	public static boolean active() {
		return active;
	}

	/** Recibe deltas del ratón ya escalados por Minecraft (unidades de 0,15°). */
	public static void captureLook(double dx, double dy) {
		if (!active) return;
		// En changeLookDirection: +dx = derecha, -dy = arriba; 1 unidad = 0.15 grados
		cursorYawDeg = Math.max(-42, Math.min(42, cursorYawDeg + dx * 0.15));
		cursorPitchDeg = Math.max(-38, Math.min(38, cursorPitchDeg - dy * 0.15));
		double[] last = angularPts.get(angularPts.size() - 1);
		double d = Math.hypot(cursorYawDeg - last[0], cursorPitchDeg - last[1]);
		if (d >= 0.45 && angularPts.size() < 240) {
			angularPts.add(new double[]{cursorYawDeg, cursorPitchDeg});
		}
	}

	/** Puntos actuales del trazo en coordenadas de píxeles UI relativas al centro de la pantalla. */
	public static List<float[]> screenPoints(int screenW, int screenH) {
		MinecraftClient mc = MinecraftClient.getInstance();
		double fov = mc.options != null ? mc.options.getFov().getValue() : 70.0;
		double scaleY = (screenH * 0.5) / Math.tan(Math.toRadians(fov * 0.5));
		List<float[]> out = new ArrayList<>(angularPts.size());
		for (double[] a : angularPts) {
			float sx = (float) (Math.tan(Math.toRadians(a[0])) * scaleY);
			float sy = (float) (-Math.tan(Math.toRadians(a[1])) * scaleY);
			out.add(new float[]{sx, sy});
		}
		return out;
	}

	public static void cancel() {
		active = false;
		angularPts.clear();
	}

	/** Finaliza el trazo, simplifica, proyecta y envía el corte poligonal al servidor. */
	public static boolean finish(boolean thru) {
		if (!active) return false;
		active = false;
		MinecraftClient mc = MinecraftClient.getInstance();
		if (mc.player == null || angularPts.size() < 4) {
			angularPts.clear();
			return false;
		}
		double dist = Math.max(1.2, mc.player.getCameraPosVec(1f).distanceTo(anchor));
		List<double[]> rawM = new ArrayList<>(angularPts.size());
		for (double[] a : angularPts) {
			double mx = dist * Math.tan(Math.toRadians(a[0]));
			double my = dist * Math.tan(Math.toRadians(a[1]));
			rawM.add(new double[]{mx, my});
		}
		angularPts.clear();

		// Eliminar último punto si casi cierra al primero para RDP limpio
		if (rawM.size() >= 4 && Math.hypot(rawM.get(rawM.size() - 1)[0] - rawM.get(0)[0], rawM.get(rawM.size() - 1)[1] - rawM.get(0)[1]) < 0.08) {
			rawM.remove(rawM.size() - 1);
		}
		List<double[]> simp = simplifyClosed(rawM, 24);
		if (simp.size() < 3) return false;

		double area = signedArea(simp);
		if (Math.abs(area) < 0.015) return false;
		if (area < 0) Collections.reverse(simp);

		// Centrar el prisma en el centroide del polígono proyectado sobre el plano anchor
		double cx = 0, cy = 0;
		for (double[] p : simp) { cx += p[0]; cy += p[1]; }
		cx /= simp.size(); cy /= simp.size();
		Vec3d center = anchor.add(rightDir.multiply(cx)).add(upDir.multiply(cy));

		int n = simp.size();
		float[] params = new float[13 + n * 2];
		params[0] = (float) center.x; params[1] = (float) center.y; params[2] = (float) center.z;
		params[3] = (float) rightDir.x; params[4] = (float) rightDir.y; params[5] = (float) rightDir.z;
		params[6] = (float) upDir.x; params[7] = (float) upDir.y; params[8] = (float) upDir.z;
		params[9] = (float) lookDir.x; params[10] = (float) lookDir.y; params[11] = (float) lookDir.z;
		params[12] = n;
		List<Vec3d> worldPoly = new ArrayList<>(n);
		for (int i = 0; i < n; i++) {
			float lx = (float) (simp.get(i)[0] - cx);
			float ly = (float) (simp.get(i)[1] - cy);
			params[13 + i * 2] = lx;
			params[13 + i * 2 + 1] = ly;
			worldPoly.add(center.add(rightDir.multiply(lx)).add(upDir.multiply(ly)));
		}

		Bisturi.COMMITTED_SHAPES.add(new Bisturi.CommittedShape(
			Bisturi.Mode.FREEHAND, center, rightDir, upDir, lookDir, 0.35f, thru, worldPoly));
		Send.cutShape(targetId, Payloads.CutShape.MODE_FREEHAND, true, thru, params);
		mc.player.playSound(SoundEvents.BLOCK_AMETHYST_BLOCK_HIT, 1f, 1.85f);
		return true;
	}

	private static List<double[]> simplifyClosed(List<double[]> pts, int maxPts) {
		double minX = Double.MAX_VALUE, maxX = -Double.MAX_VALUE, minY = Double.MAX_VALUE, maxY = -Double.MAX_VALUE;
		for (double[] p : pts) {
			minX = Math.min(minX, p[0]); maxX = Math.max(maxX, p[0]);
			minY = Math.min(minY, p[1]); maxY = Math.max(maxY, p[1]);
		}
		double span = Math.max(maxX - minX, maxY - minY);
		double eps = Math.max(0.03, span * 0.035);
		List<double[]> res = rdpOpen(pts, eps);
		while (res.size() > maxPts && eps < span) {
			eps *= 1.35;
			res = rdpOpen(pts, eps);
		}
		return res;
	}

	private static List<double[]> rdpOpen(List<double[]> pts, double eps) {
		if (pts.size() <= 3) return new ArrayList<>(pts);
		double maxD = 0;
		int idx = 0;
		double[] a = pts.get(0), b = pts.get(pts.size() - 1);
		for (int i = 1; i < pts.size() - 1; i++) {
			double d = perpDist(pts.get(i), a, b);
			if (d > maxD) {
				maxD = d;
				idx = i;
			}
		}
		if (maxD > eps) {
			List<double[]> left = rdpOpen(pts.subList(0, idx + 1), eps);
			List<double[]> right = rdpOpen(pts.subList(idx, pts.size()), eps);
			List<double[]> out = new ArrayList<>(left.size() + right.size() - 1);
			out.addAll(left.subList(0, left.size() - 1));
			out.addAll(right);
			return out;
		}
		List<double[]> out = new ArrayList<>(2);
		out.add(a);
		out.add(b);
		return out;
	}

	private static double perpDist(double[] p, double[] a, double[] b) {
		double dx = b[0] - a[0], dy = b[1] - a[1];
		double len = Math.hypot(dx, dy);
		if (len < 1e-6) return Math.hypot(p[0] - a[0], p[1] - a[1]);
		return Math.abs(dy * p[0] - dx * p[1] + b[0] * a[1] - b[1] * a[0]) / len;
	}

	private static double signedArea(List<double[]> pts) {
		double a = 0;
		for (int i = 0; i < pts.size(); i++) {
			double[] p = pts.get(i), q = pts.get((i + 1) % pts.size());
			a += p[0] * q[1] - q[0] * p[1];
		}
		return a * 0.5;
	}
}
