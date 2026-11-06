package dev.opeope.client.room;

import net.minecraft.block.BlockState;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.particle.Particle;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.particle.BlockStateParticleEffect;
import net.minecraft.particle.DustParticleEffect;
import net.minecraft.particle.ParticleEffect;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.Heightmap;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Las partículas del ROOM (spec §5.8), todas por {@code particleManager.addParticle} en el cliente. Las cantidades se
 * escalan con la opción «Partículas» (todas, menos, mínimas: ×1, ×0,5, ×0,2, al menos 1) y las de otros jugadores van a
 * la mitad. Polvo y trozos de bloque reciben su velocidad después de nacer (si no, Minecraft la cambia por una al azar).
 */
public final class RoomParticles {
	private RoomParticles() {}

	static final DustParticleEffect DUST = new DustParticleEffect(new Vector3f(0.56f, 0.85f, 1f), 1.4f);
	private static final Random RNG = new Random();

	/** Ráfagas que salen un poco más tarde (el destello del cierre). */
	private static final class Pending {
		final double due;
		final double x, y, z;
		final float radius;
		final boolean own;

		Pending(double due, double x, double y, double z, float radius, boolean own) {
			this.due = due;
			this.x = x;
			this.y = y;
			this.z = z;
			this.radius = radius;
			this.own = own;
		}
	}

	private static final List<Pending> PENDING = new ArrayList<>();

	// ------------------------------------------------------------------ básicos

	/** n escalado por la opción de partículas (y a la mitad si es de otro). Nunca menos de 1. */
	static int count(int n, boolean own) {
		MinecraftClient mc = MinecraftClient.getInstance();
		int mode = 0;
		try {
			mode = mc.options.getParticles().getValue().ordinal();
		} catch (RuntimeException ignored) {
			// sin opciones: todas
		}
		float f = mode <= 0 ? 1f : mode == 1 ? 0.5f : 0.2f;
		if (!own) f *= 0.5f;
		return Math.max(1, Math.round(n * f));
	}

	private static Particle add(ParticleEffect effect, double x, double y, double z, double vx, double vy, double vz) {
		MinecraftClient mc = MinecraftClient.getInstance();
		if (mc.world == null || mc.particleManager == null) return null;
		Particle p = mc.particleManager.addParticle(effect, x, y, z, vx, vy, vz);
		if (p != null) p.setVelocity(vx, vy, vz);
		return p;
	}

	/** Un vector al azar de largo 1. */
	private static double[] dir(double[] out) {
		double x, y, z, l;
		do {
			x = RNG.nextDouble() * 2 - 1;
			y = RNG.nextDouble() * 2 - 1;
			z = RNG.nextDouble() * 2 - 1;
			l = x * x + y * y + z * z;
		} while (l > 1 || l < 1e-4);
		l = Math.sqrt(l);
		out[0] = x / l;
		out[1] = y / l;
		out[2] = z / l;
		return out;
	}

	private static double range(double a, double b) {
		return a + RNG.nextDouble() * (b - a);
	}

	// ------------------------------------------------------------------ momentos

	/** La semilla en la mano: chispas eléctricas muy cerca, casi quietas. */
	static void seed(Vec3d hand) {
		double[] d = new double[3];
		for (int i = 0; i < count(3, true); i++) {
			dir(d);
			double r = RNG.nextDouble() * 0.25;
			add(ParticleTypes.ELECTRIC_SPARK, hand.x + d[0] * r, hand.y + d[1] * r, hand.z + d[2] * r, d[0] * 0.02, d[1] * 0.02, d[2] * 0.02);
		}
	}

	/** Florece: polvo azul en una cáscara de 1,5 alrededor del centro y destellos desde la mano, todo hacia fuera. */
	static void bloom(Vec3d hand, Vec3d center, boolean own) {
		double[] d = new double[3];
		for (int i = 0, n = count(40, own); i < n; i++) {
			dir(d);
			double s = range(0.25, 0.45);
			add(DUST, center.x + d[0] * 1.5, center.y + d[1] * 1.5, center.z + d[2] * 1.5, d[0] * s, d[1] * s, d[2] * s);
		}
		for (int i = 0, n = count(24, own); i < n; i++) {
			dir(d);
			double s = range(0.15, 0.3);
			add(ParticleTypes.END_ROD, hand.x, hand.y, hand.z, d[0] * s, d[1] * s, d[2] * s);
		}
	}

	/** Mientras crece: polvo en puntos de la esfera a menos de 48 bloques de la cámara. */
	static void growing(double cx, double cy, double cz, double r, Vec3d cam, boolean own) {
		double[] d = new double[3];
		int n = count(6, own), placed = 0;
		for (int tries = 0; tries < n * 4 && placed < n; tries++) {
			dir(d);
			double x = cx + d[0] * r, y = cy + d[1] * r, z = cz + d[2] * r;
			double dx = x - cam.x, dy = y - cam.y, dz = z - cam.z;
			if (dx * dx + dy * dy + dz * dz > 48 * 48) continue;
			add(DUST, x, y, z, d[0] * 0.02, d[1] * 0.02, d[2] * 0.02);
			placed++;
		}
	}

	/** Lanzado: un cono de destellos desde la mano hacia el destino y polvo en los dos primeros bloques. */
	static void cast(Vec3d hand, Vec3d target, boolean own) {
		Vec3d f = target.subtract(hand);
		double len = f.length();
		if (len < 1e-3) return;
		double fx = f.x / len, fy = f.y / len, fz = f.z / len;
		double[] d = new double[3];
		for (int i = 0, n = count(22, own); i < n; i++) {
			dir(d);
			// mezcla con la dirección: un cono de unos 35°
			double x = fx + d[0] * 0.35, y = fy + d[1] * 0.35, z = fz + d[2] * 0.35;
			double l = Math.sqrt(x * x + y * y + z * z);
			double s = range(0.1, 0.3) / l;
			add(ParticleTypes.END_ROD, hand.x, hand.y, hand.z, x * s, y * s, z * s);
		}
		for (int i = 0, n = count(12, own); i < n; i++) {
			double k = RNG.nextDouble() * Math.min(2.0, len);
			dir(d);
			add(DUST, hand.x + fx * k + d[0] * 0.1, hand.y + fy * k + d[1] * 0.1, hand.z + fz * k + d[2] * 0.1,
				d[0] * 0.03, d[1] * 0.03, d[2] * 0.03);
		}
	}

	/** El cometa en vuelo: dos destellos por tick que se quedan atrás. */
	static void comet(double x, double y, double z, boolean own) {
		double[] d = new double[3];
		for (int i = 0, n = count(2, own); i < n; i++) {
			dir(d);
			add(ParticleTypes.END_ROD, x + d[0] * 0.1, y + d[1] * 0.1, z + d[2] * 0.1, d[0] * 0.03, d[1] * 0.03, d[2] * 0.03);
		}
	}

	/**
	 * Aterriza: polvo y destellos hacia fuera, un fogonazo y trozos del suelo que saltan (del bloque bajo cada punto;
	 * el aire no suelta nada).
	 */
	static void land(Vec3d at, float radius, boolean own) {
		MinecraftClient mc = MinecraftClient.getInstance();
		ClientWorld w = mc.world;
		if (w == null) return;
		double[] d = new double[3];
		for (int i = 0, n = count(60, own); i < n; i++) {
			dir(d);
			double s = range(0.3, 0.6);
			add(DUST, at.x + d[0] * 0.5, at.y + d[1] * 0.5, at.z + d[2] * 0.5, d[0] * s, d[1] * s, d[2] * s);
		}
		for (int i = 0, n = count(30, own); i < n; i++) {
			dir(d);
			double s = range(0.3, 0.6);
			add(ParticleTypes.END_ROD, at.x, at.y, at.z, d[0] * s, d[1] * s, d[2] * s);
		}
		add(ParticleTypes.FLASH, at.x, at.y, at.z, 0, 0, 0);
		int bottom = w.getBottomY();
		for (int i = 0, n = count(18, own); i < n; i++) {
			double ang = RNG.nextDouble() * Math.PI * 2;
			double rr = 0.5 + RNG.nextDouble() * 2.5;
			double x = at.x + Math.cos(ang) * rr, z = at.z + Math.sin(ang) * rr;
			int h = w.getTopY(Heightmap.Type.MOTION_BLOCKING, (int) Math.floor(x), (int) Math.floor(z));
			if (h <= bottom || Math.abs(h - at.y) > 6) continue;
			BlockState st = w.getBlockState(BlockPos.ofFloored(x, h - 0.5, z));
			if (st.isAir()) continue;
			double s = range(0.15, 0.35);
			add(new BlockStateParticleEffect(ParticleTypes.BLOCK, st), x, h + 0.1, z,
				Math.cos(ang) * s, range(0.15, 0.35), Math.sin(ang) * s);
		}
	}

	/** Vuelve a ti: dos destellos por tick a lo largo de la cuerda. */
	static void tether(Vec3d a, Vec3d b, boolean own) {
		for (int i = 0, n = count(2, own); i < n; i++) {
			double k = RNG.nextDouble();
			add(ParticleTypes.END_ROD, a.x + (b.x - a.x) * k, a.y + (b.y - a.y) * k, a.z + (b.z - a.z) * k,
				(RNG.nextDouble() - 0.5) * 0.02, (RNG.nextDouble() - 0.5) * 0.02, (RNG.nextDouble() - 0.5) * 0.02);
		}
	}

	/** Llega contigo: un anillo de polvo a ras de los pies que se abre. */
	static void arrive(Vec3d center, boolean own) {
		int n = count(24, own);
		double y = center.y - 0.9;
		for (int i = 0; i < n; i++) {
			double ang = Math.PI * 2 * i / n;
			double c = Math.cos(ang), s = Math.sin(ang);
			add(DUST, center.x + c * 0.5, y, center.z + s * 0.5, c * 0.25, 0.01, s * 0.25);
		}
	}

	/** Se cierra: a los 0,26 s, un fogonazo y destellos que caen hacia el centro desde 0,6 R. */
	static void close(Vec3d center, float radius, boolean own) {
		PENDING.add(new Pending(RoomFxClient.time() + 0.26, center.x, center.y, center.z, radius, own));
		while (PENDING.size() > 16) PENDING.remove(0);
	}

	private static void closeBurst(Pending p) {
		add(ParticleTypes.FLASH, p.x, p.y, p.z, 0, 0, 0);
		double[] d = new double[3];
		double from = 0.6 * p.radius;
		double speed = Math.min(0.6, 0.05 * p.radius);
		for (int i = 0, n = count(20, p.own); i < n; i++) {
			dir(d);
			add(ParticleTypes.END_ROD, p.x + d[0] * from, p.y + d[1] * from, p.z + d[2] * from, -d[0] * speed, -d[1] * speed, -d[2] * speed);
		}
	}

	/** Cada tick: las ráfagas que tocan. */
	static void tick() {
		if (PENDING.isEmpty()) return;
		double now = RoomFxClient.time();
		for (int i = PENDING.size() - 1; i >= 0; i--) {
			Pending p = PENDING.get(i);
			if (now >= p.due) {
				PENDING.remove(i);
				closeBurst(p);
			}
		}
	}

	static void clear() {
		PENDING.clear();
	}
}
