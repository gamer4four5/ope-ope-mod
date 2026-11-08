package dev.opeope.terrain;

import dev.opeope.OpeConfig;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;

/**
 * Un corte que el suelo recuerda: el plano, la región del corte (la esfera del ROOM, el alcance y el semiespacio de los
 * ojos, para dibujar la marca) y la caja de sus semillas.
 * <p>
 * Todos los números se redondean a float al crearlo (q). El servidor lo crea así y el cliente lo recibe en floats, de
 * modo que los dos planean con los mismos valores exactos. Es inmutable: se puede leer desde cualquier hilo.
 */
public final class GroundCut {
	public final int id;
	public final UUID owner;
	public final Identifier dim;
	/** World.getTime() cuando se recordó. */
	public final long created;
	/** El plano: normal unitaria (normalizada y después redondeada, sin volver a normalizar) y un punto. */
	public final Vec3d n, p;
	/** La esfera del ROOM en el momento del corte. */
	public final Vec3d c;
	public final double r;
	/** La esfera del alcance; lc null y lr 0 = sin alcance. */
	@Nullable public final Vec3d lc;
	public final double lr;
	/** El semiespacio de los ojos; los dos null = ninguno. */
	@Nullable public final Vec3d eye, look;
	/** La caja de las semillas del plano, con la esquina máxima exclusiva (+1). */
	public final Box box;
	/** Cuántos floats ocupa en la red: n(3) p(3) c(3) r lc(3) lr eye(3) look(3) cajaMín(3) cajaMáx(3). */
	public static final int FLOATS = 26;

	/** n·p, calculado una vez con los valores ya redondeados. */
	private final double d;

	private GroundCut(int id, UUID owner, Identifier dim, long created, Vec3d n, Vec3d p, Vec3d c, double r,
			@Nullable Vec3d lc, double lr, @Nullable Vec3d eye, @Nullable Vec3d look, Box box) {
		this.id = id;
		this.owner = owner;
		this.dim = dim;
		this.created = created;
		this.n = n;
		this.p = p;
		this.c = c;
		this.r = r;
		this.lc = lc;
		this.lr = lc == null ? 0 : lr;
		this.eye = eye;
		this.look = look;
		this.box = box;
		this.d = n.x * p.x + n.y * p.y + n.z * p.z;
	}

	/** Redondea a float (y vuelve a double): lo que el cliente recibirá exactamente. */
	public static double q(double v) {
		return (double) (float) v;
	}

	private static Vec3d q(Vec3d v) {
		return new Vec3d(q(v.x), q(v.y), q(v.z));
	}

	/**
	 * Crea el corte con todas las coordenadas redondeadas con q. La normal se normaliza primero y después se redondea
	 * (no se vuelve a normalizar). Un alcance con radio que no sea positivo, o unos ojos sin mirada de largo mayor que
	 * 0,5, cuentan como ausentes, igual que en fromFloats.
	 */
	public static GroundCut create(int id, UUID owner, Identifier dim, long created, Vec3d n, Vec3d p, Vec3d c, double r,
			@Nullable Vec3d lc, double lr, @Nullable Vec3d eye, @Nullable Vec3d look, Box box) {
		double len = Math.sqrt(n.x * n.x + n.y * n.y + n.z * n.z);
		Vec3d nn = len > 1e-12 ? new Vec3d(q(n.x / len), q(n.y / len), q(n.z / len)) : new Vec3d(0, 0, 0);
		double qlr = q(lr);
		Vec3d qlc = lc != null && qlr > 0 ? q(lc) : null;
		Vec3d qlook = look != null ? q(look) : null;
		boolean hasEye = eye != null && qlook != null && qlook.lengthSquared() > 0.25;
		Box qbox = new Box(q(box.minX), q(box.minY), q(box.minZ), q(box.maxX), q(box.maxY), q(box.maxZ));
		return new GroundCut(id, owner, dim, created, nn, q(p), q(c), q(r), qlc, qlr,
			hasEye ? q(eye) : null, hasEye ? qlook : null, qbox);
	}

	/** n(3) p(3) c(3) r lc(3) lr eye(3) look(3) cajaMín(3) cajaMáx(3); lo que falta va a ceros. */
	public float[] toFloats() {
		float[] f = new float[FLOATS];
		put(f, 0, n);
		put(f, 3, p);
		put(f, 6, c);
		f[9] = (float) r;
		if (lc != null) {
			put(f, 10, lc);
			f[13] = (float) lr;
		}
		if (eye != null && look != null) {
			put(f, 14, eye);
			put(f, 17, look);
		}
		f[20] = (float) box.minX;
		f[21] = (float) box.minY;
		f[22] = (float) box.minZ;
		f[23] = (float) box.maxX;
		f[24] = (float) box.maxY;
		f[25] = (float) box.maxZ;
		return f;
	}

	private static void put(float[] f, int at, Vec3d v) {
		f[at] = (float) v.x;
		f[at + 1] = (float) v.y;
		f[at + 2] = (float) v.z;
	}

	private static Vec3d get(float[] f, int at) {
		return new Vec3d(f[at], f[at + 1], f[at + 2]);
	}

	/**
	 * El corte que llegó por la red. Null cuando faltan floats, o la normal no es finita o mide menos de 0,5.
	 * lr &gt; 0 quiere decir que hay alcance; una mirada de largo mayor que 0,5, que hay ojos y mirada.
	 */
	@Nullable
	public static GroundCut fromFloats(int id, UUID owner, Identifier dim, long created, float[] f) {
		if (f == null || f.length < FLOATS) return null;
		if (!Float.isFinite(f[0]) || !Float.isFinite(f[1]) || !Float.isFinite(f[2])) return null;
		Vec3d n = get(f, 0);
		if (n.lengthSquared() < 0.25) return null;
		double lr = f[13];
		Vec3d lc = lr > 0 ? get(f, 10) : null;
		Vec3d look = get(f, 17);
		boolean hasEye = look.lengthSquared() > 0.25;
		Box box = new Box(f[20], f[21], f[22], f[23], f[24], f[25]);
		return new GroundCut(id, owner, dim, created, n, get(f, 3), get(f, 6), f[9], lc, lc != null ? lr : 0,
			hasEye ? get(f, 14) : null, hasEye ? look : null, box);
	}

	/** n·p: el plano es n·x = d(). */
	public double d() {
		return d;
	}

	/** Distancia con signo de a al plano: n·a − d(). */
	public double dist(Vec3d a) {
		return n.x * a.x + n.y * a.y + n.z * a.z - d;
	}

	/** ¿Ha pasado su tiempo (OpeConfig.GROUND_CUT_TICKS)? */
	public boolean expired(long now) {
		return now - created > OpeConfig.GROUND_CUT_TICKS;
	}

	@Override
	public String toString() {
		return "GroundCut#" + id + "(n " + n + ", d " + d + ")";
	}
}
