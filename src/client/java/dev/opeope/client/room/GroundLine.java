package dev.opeope.client.room;

import net.minecraft.world.Heightmap;
import net.minecraft.world.World;

/**
 * La línea donde la cúpula toca el suelo (spec §5.5). Para cada ángulo busca la altura del terreno con el mapa de
 * alturas MOTION_BLOCKING (el único fiable en el cliente: también cuenta hojas y agua) y ajusta tres veces el radio a esa
 * altura. Un ángulo sin suelo dentro de la esfera (o en un trozo sin cargar) corta la línea.
 * <p>Se recalcula como mucho una vez por tick, y solo si el centro o el radio cambiaron más de 0,05 o cada 10 ticks. Al
 * dibujar se guarda la altura y el radio se vuelve a sacar del radio suavizado, así la línea no va a saltos al crecer.
 */
public final class GroundLine {
	public static final int MAX = 256;

	/** Altura del suelo por ángulo (NaN = ese ángulo no tiene suelo dentro de la esfera). */
	public final float[] h = new float[MAX];
	public final float[] cos = new float[MAX];
	public final float[] sin = new float[MAX];
	/** Ángulos en uso. */
	public int n;

	private double bx = Double.NaN, by, bz, br;
	private int builtTick = Integer.MIN_VALUE / 2;
	private World builtWorld;
	private int trigN = -1;

	/** Recalcula si toca. */
	public void update(World w, double cx, double cy, double cz, double r, int tick) {
		if (w == null) return;
		if (tick == builtTick) return;
		boolean changed = Double.isNaN(bx) || w != builtWorld
			|| Math.abs(cx - bx) > 0.05 || Math.abs(cy - by) > 0.05 || Math.abs(cz - bz) > 0.05 || Math.abs(r - br) > 0.05;
		if (!changed && tick - builtTick < 10) return;
		builtTick = tick;
		builtWorld = w;
		bx = cx;
		by = cy;
		bz = cz;
		br = r;
		rebuild(w, cx, cy, cz, r);
	}

	/** Olvida lo calculado (el mundo cambió o la esfera se fue). */
	public void reset() {
		bx = Double.NaN;
		builtWorld = null;
		n = 0;
	}

	private void rebuild(World w, double cx, double cy, double cz, double r) {
		int count = (int) Math.round(64 + 1.2 * r);
		count = Math.max(96, Math.min(MAX, count));
		if (count != trigN) {
			for (int i = 0; i < count; i++) {
				double a = Math.PI * 2 * i / count;
				cos[i] = (float) Math.cos(a);
				sin[i] = (float) Math.sin(a);
			}
			trigN = count;
		}
		n = count;
		int bottom = w.getBottomY();
		for (int i = 0; i < count; i++) {
			double rho = r;
			float found = Float.NaN;
			for (int it = 0; it < 3; it++) {
				int x = (int) Math.floor(cx + rho * cos[i]);
				int z = (int) Math.floor(cz + rho * sin[i]);
				int top = w.getTopY(Heightmap.Type.MOTION_BLOCKING, x, z);
				double dy = top - cy;
				if (top <= bottom || Math.abs(dy) > r) {
					found = Float.NaN;
					break;
				}
				found = top;
				rho = Math.sqrt(Math.max(0, r * r - dy * dy));
			}
			h[i] = found;
		}
	}

	/**
	 * El punto i con el centro y el radio de este fotograma, en out (x, y, z). false si ese ángulo no tiene suelo dentro
	 * de la esfera ahora mismo.
	 */
	public boolean point(int i, double cx, double cy, double cz, double r, double[] out) {
		float hh = h[i];
		if (Float.isNaN(hh)) return false;
		double dy = hh - cy;
		if (Math.abs(dy) > r) return false;
		double rho = Math.sqrt(Math.max(0, r * r - dy * dy));
		out[0] = cx + rho * cos[i];
		out[1] = hh + 0.03;
		out[2] = cz + rho * sin[i];
		return true;
	}
}
