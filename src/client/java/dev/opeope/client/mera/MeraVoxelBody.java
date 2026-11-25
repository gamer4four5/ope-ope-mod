package dev.opeope.client.mera;

import dev.opeope.OpeOpeMod;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.LightmapTextureManager;
import net.minecraft.client.render.OverlayTexture;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.texture.NativeImage;
import net.minecraft.client.texture.NativeImageBackedTexture;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import org.joml.Matrix4f;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * El cuerpo Logia hecho de cubitos. Cada cara que dibuja el modelo de Ace (piel, segunda capa y armadura) se parte en
 * sus píxeles: un cubito por píxel, con la misma posición y UV que da Minecraft. Donde algo lo atraviesa (una flecha, un
 * golpe, un tajo, una explosión, una caída) los cubitos se vuelven fuego: desaparecen de la piel, arden en su sitio como
 * cubitos de llamas que suben y se encogen, las paredes y el forro del hueco brillan como brasa (por el hueco no se ve la
 * piel por dentro: se ve fuego o, si lo atraviesa de lado a lado, el mundo), y el hueco se cierra desde el borde con el
 * mismo contorno irregular; cada cubito que vuelve brilla un instante.
 * <p>
 * Trabaja sobre los vértices que manda el modelo (como los cortes de Law): vale con cualquier skin, con skins slim, con
 * armadura y con modelos cambiados por otros mods. Sin huecos abiertos no envuelve nada (coste cero).
 */
public final class MeraVoxelBody {
	private MeraVoxelBody() {}

	/** Estilos del aviso SCATTER (los mismos que MeraPowers.HOLE_*). */
	public static final int HIT = 0, BLAST = 1, SHOT = 2, SLASH = 3, FALL = 4;

	/** Tiempos del hueco (segundos): se abre, aguanta abierto y se cierra desde el borde. */
	static final double OPEN = 0.06, HOLD = 0.35;
	/** Tope de cubitos de fuego (y de quads de pared y forro) por jugador y fotograma. */
	static final int MAX_FIRE = 420, MAX_SOLID = 2600;

	/**
	 * Un hueco: un segmento (a→b) con radio, en coordenadas del jugador sin girar (relativas a sus pies y deshecho el giro
	 * del cuerpo al recibir el golpe), así queda pegado al cuerpo aunque se mueva o gire.
	 */
	static final class Hole {
		final double ax, ay, az, bx, by, bz;
		final float radius;
		final double start, close;
		final int style;
		/** Lo último que se vio de él (mundo), para la llama 3D del centro. */
		double wx, wy, wz;
		float wr;
		double seenAt = -1;

		Hole(double ax, double ay, double az, double bx, double by, double bz, float radius, double start, double close, int style) {
			this.ax = ax; this.ay = ay; this.az = az;
			this.bx = bx; this.by = by; this.bz = bz;
			this.radius = radius;
			this.start = start;
			this.close = close;
			this.style = style;
		}

		double end() {
			return start + OPEN + HOLD + close;
		}
	}

	private static final Map<Integer, List<Hole>> HOLES = new HashMap<>();
	private static final List<float[]> SOLID = new ArrayList<>();
	private static final List<float[]> GLOW = new ArrayList<>();
	private static VertexConsumerProvider flushBase;
	private static boolean broken, warned;
	private static Identifier white;

	/** Para las pruebas: texels quitados y texels partidos en el último dibujo de cada jugador. */
	private static final Map<Integer, int[]> STATS = new HashMap<>();

	static double now() {
		return System.nanoTime() / 1e9;
	}

	/** Si algo falló al dibujar en cubitos: se vuelve al hueco de antes (la parte entera en llamas). */
	public static boolean broken() {
		return broken;
	}

	public static void clear() {
		HOLES.clear();
		SOLID.clear();
		GLOW.clear();
		STATS.clear();
	}

	public static boolean hasHoles(int entityId) {
		List<Hole> l = HOLES.get(entityId);
		return l != null && l.stream().anyMatch(h -> now() < h.end());
	}

	/** [texels quitados, texels partidos] del último dibujo de ese jugador (0, 0 si no hubo huecos). */
	public static int[] stats(int entityId) {
		int[] s = STATS.get(entityId);
		return s != null ? s.clone() : new int[2];
	}

	// ------------------------------------------------------------------ abrir huecos

	/**
	 * Aviso SCATTER: at = donde entra (mundo), dir = por dónde (o el eje del tajo), radius en bloques, length = largo del
	 * túnel o del tajo, style = HIT/BLAST/SHOT/SLASH/FALL.
	 */
	public static void onScatter(Entity e, Vec3d at, Vec3d dir, float radius, float length, int style) {
		if (!(e instanceof LivingEntity le)) return;
		double t = now();
		Vec3d d = dir.lengthSquared() > 1e-8 ? dir.normalize() : new Vec3d(0, 0, 1);
		// el punto del golpe llega en el borde de la caja (0.3 del eje), pero la piel del pecho está a ~0.13: se mete hacia
		// el eje para que el hueco caiga en el cuerpo y no en el aire de delante
		if (style == HIT || style == SLASH) {
			double hx = at.x - le.getX(), hz = at.z - le.getZ();
			double hl = Math.sqrt(hx * hx + hz * hz);
			double keep = 0.1;
			if (hl > keep) at = new Vec3d(le.getX() + hx / hl * keep, at.y, le.getZ() + hz / hl * keep);
		}
		Vec3d a = at, b = at;
		float r = radius;
		double close = 0.75;
		switch (style) {
			case SHOT -> {
				// túnel de lado a lado: de un poco antes de la entrada a la salida
				a = at.subtract(d.multiply(0.08));
				b = at.add(d.multiply(Math.max(0.5, length)));
				close = 0.7;
			}
			case SLASH -> {
				a = at.subtract(d.multiply(length * 0.5));
				b = at.add(d.multiply(length * 0.5));
				close = 0.85;
			}
			case BLAST -> {
				// todo lo que mira a la explosión: una esfera grande que llega al cuerpo desde donde estalló
				Vec3d c = le.getPos().add(0, le.getHeight() * 0.5, 0);
				Vec3d toward = c.subtract(at);
				double dist = toward.length();
				Vec3d n = dist > 1e-4 ? toward.multiply(1 / dist) : new Vec3d(0, 0, 1);
				// el lado del cuerpo que mira a la explosión se deshace en llamas; el resto aguanta
				a = c.subtract(n.multiply(0.3));
				b = a;
				r = Math.max(0.32f, Math.min(0.5f, radius * 0.4f));
				close = 0.9;
			}
			case FALL -> {
				a = le.getPos().add(0, 0.02, 0);
				b = a.add(0, r * 1.6, 0);
				close = 0.8;
			}
			default -> close = 0.7;
		}
		add(le, a, b, r, t, close, style);
	}

	private static void add(LivingEntity e, Vec3d a, Vec3d b, float r, double t, double close, int style) {
		double yaw = Math.toRadians(e.bodyYaw);
		double c = Math.cos(yaw), s = Math.sin(yaw);
		Vec3d p = e.getPos();
		double[] ra = unrotate(a.x - p.x, a.z - p.z, c, s), rb = unrotate(b.x - p.x, b.z - p.z, c, s);
		List<Hole> l = HOLES.computeIfAbsent(e.getId(), k -> new ArrayList<>());
		if (l.size() >= 16) l.remove(0);
		l.add(new Hole(ra[0], a.y - p.y, ra[1], rb[0], b.y - p.y, rb[1], r, t, close, style));
	}

	/** Deshace el giro del cuerpo (yaw): x' = x cos + z sin, z' = -x sin + z cos. */
	private static double[] unrotate(double x, double z, double c, double s) {
		return new double[]{x * c + z * s, -x * s + z * c};
	}

	/** Cuánto mide el hueco ahora (0 si ya se cerró): se abre de golpe, aguanta y se cierra desde el borde. */
	static float radiusNow(Hole h, double t) {
		double k = t - h.start;
		if (k < 0) return 0f;
		if (k < OPEN) return (float) (h.radius * (0.4 + 0.6 * k / OPEN));
		k -= OPEN;
		if (k < HOLD) return h.radius;
		k -= HOLD;
		if (k >= h.close) return 0f;
		double f = 1.0 - k / h.close;
		return (float) (h.radius * Math.sqrt(f));
	}

	/** ¿Se está cerrando? (los cubitos que vuelven brillan como brasa) */
	static boolean closing(Hole h, double t) {
		return t - h.start > OPEN + HOLD;
	}

	// ------------------------------------------------------------------ dibujo

	/**
	 * Al empezar a dibujar al ser (con la matriz del principio, que solo lleva la posición del ser respecto a la cámara del
	 * pase que sea, también el de sombras de Iris): si tiene huecos, su dibujo pasa por los cubitos.
	 */
	public static VertexConsumerProvider wrap(VertexConsumerProvider base, LivingEntity e, float tickDelta, MatrixStack ms) {
		if (broken) return base;
		List<Hole> l = HOLES.get(e.getId());
		if (l == null || l.isEmpty()) return base;
		try {
			double t = now();
			l.removeIf(h -> t >= h.end());
			if (l.isEmpty()) {
				HOLES.remove(e.getId());
				STATS.remove(e.getId());
				return base;
			}
			Matrix4f m = ms.peek().getPositionMatrix();
			// el origen del ser (sus pies) en el espacio del dibujo
			float ox = m.m30(), oy = m.m31(), oz = m.m32();
			double yaw = Math.toRadians(MathHelper.lerpAngleDegrees(tickDelta, e.prevBodyYaw, e.bodyYaw));
			double c = Math.cos(yaw), s = Math.sin(yaw);
			Vec3d pos = e.getLerpedPos(tickDelta);
			int n = l.size();
			// por hueco: ax ay az bx by bz r closing
			float[] holes = new float[n * 8];
			for (int i = 0; i < n; i++) {
				Hole h = l.get(i);
				float r = radiusNow(h, t);
				// x = x' cos − z' sin, z = x' sin + z' cos
				double ax = h.ax * c - h.az * s, az = h.ax * s + h.az * c, bx = h.bx * c - h.bz * s, bz = h.bx * s + h.bz * c;
				holes[i * 8] = (float) (ox + ax);
				holes[i * 8 + 1] = (float) (oy + h.ay);
				holes[i * 8 + 2] = (float) (oz + az);
				holes[i * 8 + 3] = (float) (ox + bx);
				holes[i * 8 + 4] = (float) (oy + h.by);
				holes[i * 8 + 5] = (float) (oz + bz);
				holes[i * 8 + 6] = r;
				holes[i * 8 + 7] = closing(h, t) ? 1f : 0f;
				h.wx = pos.x + (ax + bx) * 0.5;
				h.wy = pos.y + (h.ay + h.by) * 0.5;
				h.wz = pos.z + (az + bz) * 0.5;
				h.wr = r;
				h.seenAt = t;
			}
			flushBase = base;
			SOLID.clear();
			GLOW.clear();
			int[] stats = STATS.computeIfAbsent(e.getId(), k -> new int[2]);
			stats[0] = 0;
			stats[1] = 0;
			float time = (float) (t % 1000.0);
			return layer -> new VoxelConsumer(base.getBuffer(layer), holes, time, stats);
		} catch (RuntimeException ex) {
			fail(ex);
			return base;
		}
	}

	/** Al terminar de dibujar al ser: el forro y las paredes (brasa sólida) y los cubitos de fuego (con luz propia). */
	public static void flush() {
		VertexConsumerProvider base = flushBase;
		flushBase = null;
		if (base == null) return;
		try {
			if (!SOLID.isEmpty() || !GLOW.isEmpty()) {
				Identifier tex = white();
				if (!SOLID.isEmpty()) draw(base.getBuffer(RenderLayer.getEntitySolid(tex)), SOLID);
				if (!GLOW.isEmpty()) draw(base.getBuffer(RenderLayer.getEntityTranslucentEmissive(tex)), GLOW);
			}
		} catch (RuntimeException ex) {
			fail(ex);
		} finally {
			SOLID.clear();
			GLOW.clear();
		}
	}

	/** Un quad: 4×(x y z), color argb, normal. */
	private static void draw(VertexConsumer vc, List<float[]> quads) {
		for (float[] q : quads) {
			int color = (int) q[12] << 24 | (int) q[13] << 16 | (int) q[14] << 8 | (int) q[15];
			for (int i = 0; i < 4; i++) {
				vc.vertex(q[i * 3], q[i * 3 + 1], q[i * 3 + 2], color, 0.5f, 0.5f, OverlayTexture.DEFAULT_UV,
					LightmapTextureManager.MAX_LIGHT_COORDINATE, q[16], q[17], q[18]);
			}
		}
	}

	/** Una textura blanca hecha por código (sin PNG): el color va en cada vértice. */
	private static Identifier white() {
		if (white == null) {
			NativeImage img = new NativeImage(4, 4, false);
			img.fillRect(0, 0, 4, 4, 0xFFFFFFFF);
			white = OpeOpeMod.id("dinamico/mera_blanco");
			MinecraftClient.getInstance().getTextureManager().registerTexture(white, new NativeImageBackedTexture(img));
		}
		return white;
	}

	private static void fail(RuntimeException ex) {
		broken = true;
		if (!warned) {
			warned = true;
			OpeOpeMod.LOG.warn("Mera: el cuerpo de cubitos falló y se vuelve a la parte entera en llamas", ex);
		}
	}

	static float hash(float a, float b) {
		float h = MathHelper.sin(a * 127.1f + b * 311.7f) * 43758.547f;
		return h - (float) Math.floor(h);
	}

	/** Distancia de p al segmento a-b del hueco i. */
	static float segDist(float[] holes, int i, float x, float y, float z) {
		float ax = holes[i], ay = holes[i + 1], az = holes[i + 2];
		float abx = holes[i + 3] - ax, aby = holes[i + 4] - ay, abz = holes[i + 5] - az;
		float apx = x - ax, apy = y - ay, apz = z - az;
		float ll = abx * abx + aby * aby + abz * abz;
		float t = ll < 1e-8f ? 0f : MathHelper.clamp((apx * abx + apy * aby + apz * abz) / ll, 0f, 1f);
		float dx = apx - abx * t, dy = apy - aby * t, dz = apz - abz * t;
		return (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
	}

	static final class VoxelConsumer implements VertexConsumer {
		private final VertexConsumer d;
		private final float[] holes;
		private final float time;
		private final int[] stats;
		private final float[] buf = new float[4 * 8];
		private final int[] ibuf = new int[4 * 3];
		private int count;
		private float lastK;
		private boolean lastClosing;

		VoxelConsumer(VertexConsumer delegate, float[] holes, float time, int[] stats) {
			this.d = delegate;
			this.holes = holes;
			this.time = time;
			this.stats = stats;
		}

		@Override
		public void vertex(float x, float y, float z, int color, float u, float v, int overlay, int light, float nx, float ny, float nz) {
			int o = count * 8;
			buf[o] = x; buf[o + 1] = y; buf[o + 2] = z; buf[o + 3] = u; buf[o + 4] = v;
			buf[o + 5] = nx; buf[o + 6] = ny; buf[o + 7] = nz;
			ibuf[count * 3] = color; ibuf[count * 3 + 1] = overlay; ibuf[count * 3 + 2] = light;
			if (++count == 4) {
				count = 0;
				if (broken) {
					for (int i = 0; i < 4; i++) put(i);
					return;
				}
				try {
					emit();
				} catch (RuntimeException ex) {
					fail(ex);
					for (int i = 0; i < 4; i++) put(i);
				}
			}
		}

		/** ¿Toca algún hueco esta cara (con margen)? Si no, va entera, tal cual. */
		private boolean nearAny(float minX, float minY, float minZ, float maxX, float maxY, float maxZ) {
			for (int i = 0; i < holes.length; i += 8) {
				float r = holes[i + 6];
				if (r <= 0.005f) continue;
				float m = r * 1.6f + 0.06f;
				float lx = Math.min(holes[i], holes[i + 3]) - m, hx = Math.max(holes[i], holes[i + 3]) + m;
				float ly = Math.min(holes[i + 1], holes[i + 4]) - m, hy = Math.max(holes[i + 1], holes[i + 4]) + m;
				float lz = Math.min(holes[i + 2], holes[i + 5]) - m, hz = Math.max(holes[i + 2], holes[i + 5]) + m;
				if (maxX >= lx && minX <= hx && maxY >= ly && minY <= hy && maxZ >= lz && minZ <= hz) return true;
			}
			return false;
		}

		/**
		 * Dentro del hueco: menor que 1. El radio de cada texel lleva un ruido (hash de su sitio) para que el contorno
		 * sea de cubitos quemándose y no un círculo; al cerrarse, el frente avanza con ese mismo ruido.
		 */
		private float inside(float x, float y, float z, float jit) {
			float best = 99f;
			boolean cl = false;
			for (int i = 0; i < holes.length; i += 8) {
				float r = holes[i + 6];
				if (r <= 0.005f) continue;
				float k = segDist(holes, i, x, y, z) / (r * (0.72f + 0.56f * jit));
				if (k < best) {
					best = k;
					cl = holes[i + 7] > 0.5f;
				}
			}
			lastClosing = cl;
			return best;
		}

		private void emit() {
			float minX = Float.MAX_VALUE, minY = Float.MAX_VALUE, minZ = Float.MAX_VALUE;
			float maxX = -Float.MAX_VALUE, maxY = -Float.MAX_VALUE, maxZ = -Float.MAX_VALUE;
			for (int i = 0; i < 4; i++) {
				int o = i * 8;
				minX = Math.min(minX, buf[o]); maxX = Math.max(maxX, buf[o]);
				minY = Math.min(minY, buf[o + 1]); maxY = Math.max(maxY, buf[o + 1]);
				minZ = Math.min(minZ, buf[o + 2]); maxZ = Math.max(maxZ, buf[o + 2]);
			}
			if (!nearAny(minX, minY, minZ, maxX, maxY, maxZ)) {
				for (int i = 0; i < 4; i++) put(i);
				return;
			}
			// un cubito por píxel de la textura (64 de ancho): cuántos píxeles mide la cara en cada lado
			float du1 = Math.abs(buf[8 + 3] - buf[3]) + Math.abs(buf[8 + 4] - buf[4]);
			float du2 = Math.abs(buf[24 + 3] - buf[3]) + Math.abs(buf[24 + 4] - buf[4]);
			int na = MathHelper.clamp(Math.round(du1 * 64f), 1, 16);
			int nb = MathHelper.clamp(Math.round(du2 * 64f), 1, 16);
			// el tamaño de un cubito (para el fondo de las paredes y los cubos de fuego)
			float ex = buf[8] - buf[0], ey = buf[9] - buf[1], ez = buf[10] - buf[2];
			float cell = (float) Math.sqrt(ex * ex + ey * ey + ez * ez) / na;
			float nx = buf[5], ny = buf[6], nz = buf[7];
			// la normal de la cara en el espacio del dibujo (la que manda el modelo puede venir girada por la vista)
			float fx, fy, fz;
			{
				float e2x = buf[24] - buf[0], e2y = buf[25] - buf[1], e2z = buf[26] - buf[2];
				fx = ey * e2z - ez * e2y;
				fy = ez * e2x - ex * e2z;
				fz = ex * e2y - ey * e2x;
				float fl = (float) Math.sqrt(fx * fx + fy * fy + fz * fz);
				if (fl < 1e-9f) {
					fx = nx; fy = ny; fz = nz;
				} else {
					fx /= fl; fy /= fl; fz /= fl;
					// del mismo lado que la normal de la luz (fuera del cuerpo)
					if (fx * nx + fy * ny + fz * nz < 0 && (nx != 0 || ny != 0 || nz != 0)) {
						fx = -fx; fy = -fy; fz = -fz;
					}
				}
			}
			boolean[] gone = new boolean[na * nb];
			float[] ks = new float[na * nb];
			for (int a = 0; a < na; a++) {
				for (int b = 0; b < nb; b++) {
					float am = (a + 0.5f) / na, bm = (b + 0.5f) / nb;
					float cu = lerp2(3, am, bm), cv = lerp2(4, am, bm);
					float jit = hash(cu * 64f + 0.37f, cv * 64f + 0.71f);
					float k = inside(lerp2(0, am, bm), lerp2(1, am, bm), lerp2(2, am, bm), jit);
					ks[a * nb + b] = k;
					gone[a * nb + b] = k < 1f;
				}
			}
			stats[1] += na * nb;
			for (int a = 0; a < na; a++) {
				for (int b = 0; b < nb; b++) {
					float a0 = a / (float) na, a1 = (a + 1) / (float) na, b0 = b / (float) nb, b1 = (b + 1) / (float) nb;
					float k = ks[a * nb + b];
					inside(lerp2(0, (a0 + a1) * 0.5f, (b0 + b1) * 0.5f), lerp2(1, (a0 + a1) * 0.5f, (b0 + b1) * 0.5f),
						lerp2(2, (a0 + a1) * 0.5f, (b0 + b1) * 0.5f), hash(lerp2(3, (a0 + a1) * 0.5f, (b0 + b1) * 0.5f) * 64f + 0.37f,
							lerp2(4, (a0 + a1) * 0.5f, (b0 + b1) * 0.5f) * 64f + 0.71f));
					boolean cl = lastClosing;
					if (gone[a * nb + b]) {
						stats[0]++;
						fireCube(a0, a1, b0, b1, cell, fx, fy, fz, k);
						// paredes de brasa hacia dentro, donde el vecino sigue entero
						if (a == 0 || !gone[(a - 1) * nb + b]) wall(a0, b0, a0, b1, cell, fx, fy, fz, k);
						if (a == na - 1 || !gone[(a + 1) * nb + b]) wall(a1, b1, a1, b0, cell, fx, fy, fz, k);
						if (b == 0 || !gone[a * nb + b - 1]) wall(a1, b0, a0, b0, cell, fx, fy, fz, k);
						if (b == nb - 1 || !gone[a * nb + b + 1]) wall(a0, b1, a1, b1, cell, fx, fy, fz, k);
						continue;
					}
					// el borde del hueco (y el cubito que acaba de volver): al rojo, con luz propia
					float heat = k < 1.1f ? 0.85f : k < 1.22f ? 0.35f : 0f;
					if (cl && k < 1.3f) heat = Math.max(heat, 0.7f);
					cell(a0, a1, b0, b1, heat);
					// el forro: el mismo texel metido un pelo hacia dentro y del revés, color brasa oscura. Desde fuera
					// lo tapa la piel; mirando por el hueco a la pared de enfrente se ve fuego, no piel por dentro.
					if (k < 3.2f) lining(a0, a1, b0, b1, cell, fx, fy, fz, k);
				}
			}
		}

		/** Interpolación bilineal del dato c de los cuatro vértices: 0→1 por el lado 0-1 (a) y 0→3 (b). */
		private float lerp2(int c, float a, float b) {
			float v0 = buf[c], v1 = buf[8 + c], v2 = buf[16 + c], v3 = buf[24 + c];
			float top = v0 + (v1 - v0) * a, bot = v3 + (v2 - v3) * a;
			return top + (bot - top) * b;
		}

		private void cell(float a0, float a1, float b0, float b1, float heat) {
			int color = ibuf[0];
			int light = ibuf[2];
			if (heat > 0f) {
				int r = (color >> 16) & 255, g = (color >> 8) & 255, b = color & 255;
				r = (int) (r + (255 - r) * heat);
				g = (int) (g + (140 - g) * heat * 0.85f);
				b = (int) (b * (1f - heat * 0.9f));
				color = (color & 0xFF000000) | (MathHelper.clamp(r, 0, 255) << 16) | (MathHelper.clamp(g, 0, 255) << 8) | MathHelper.clamp(b, 0, 255);
				light = LightmapTextureManager.MAX_LIGHT_COORDINATE;
			}
			corner(a0, b0, color, light);
			corner(a1, b0, color, light);
			corner(a1, b1, color, light);
			corner(a0, b1, color, light);
		}

		private void corner(float a, float b, int color, int light) {
			d.vertex(lerp2(0, a, b), lerp2(1, a, b), lerp2(2, a, b), color, lerp2(3, a, b), lerp2(4, a, b), ibuf[1], light, buf[5], buf[6], buf[7]);
		}

		/** Color de brasa: casi blanco junto al borde recién abierto, luego naranja y rojo oscuro. */
		private static int[] ember(float k, float dim) {
			float h = MathHelper.clamp(1.25f - k * 0.55f, 0f, 1f) * dim;
			int r = (int) (110 + 145 * Math.min(1f, h * 1.6f));
			int g = (int) (20 + 200 * h * h);
			int b = (int) (8 + 150 * h * h * h);
			return new int[]{255, MathHelper.clamp(r, 0, 255), MathHelper.clamp(g, 0, 255), MathHelper.clamp(b, 0, 255)};
		}

		private void lining(float a0, float a1, float b0, float b1, float cell, float fx, float fy, float fz, float k) {
			if (SOLID.size() >= MAX_SOLID) return;
			float in = cell * 0.35f;
			int[] c = ember(k * 1.4f + 0.6f, 0.55f);
			float[] q = new float[19];
			// orden invertido: mira hacia dentro del cuerpo
			float[][] cs = {{a0, b1}, {a1, b1}, {a1, b0}, {a0, b0}};
			for (int i = 0; i < 4; i++) {
				q[i * 3] = lerp2(0, cs[i][0], cs[i][1]) - fx * in;
				q[i * 3 + 1] = lerp2(1, cs[i][0], cs[i][1]) - fy * in;
				q[i * 3 + 2] = lerp2(2, cs[i][0], cs[i][1]) - fz * in;
			}
			q[12] = c[0]; q[13] = c[1]; q[14] = c[2]; q[15] = c[3];
			q[16] = -buf[5]; q[17] = -buf[6]; q[18] = -buf[7];
			SOLID.add(q);
		}

		/** Una pared del cubito quitado: del borde de la piel hacia dentro, un cubito de fondo. */
		private void wall(float pa, float pb, float qa, float qb, float cell, float fx, float fy, float fz, float k) {
			if (SOLID.size() >= MAX_SOLID) return;
			float px = lerp2(0, pa, pb), py = lerp2(1, pa, pb), pz = lerp2(2, pa, pb);
			float qx = lerp2(0, qa, qb), qy = lerp2(1, qa, qb), qz = lerp2(2, qa, qb);
			float dx = fx * cell, dy = fy * cell, dz = fz * cell;
			int[] c = ember(k, 1f);
			float[] q = {px, py, pz, qx, qy, qz, qx - dx, qy - dy, qz - dz, px - dx, py - dy, pz - dz,
				c[0], c[1], c[2], c[3], buf[5], buf[6], buf[7]};
			SOLID.add(q);
		}

		/** El cubito que se ha vuelto fuego: un cubo de verdad, a plena luz, que tiembla, sube y se encoge. */
		private void fireCube(float a0, float a1, float b0, float b1, float cell, float fx, float fy, float fz, float k) {
			if (GLOW.size() >= MAX_FIRE * 6) return;
			float am = (a0 + a1) * 0.5f, bm = (b0 + b1) * 0.5f;
			float cx = lerp2(0, am, bm), cy = lerp2(1, am, bm), cz = lerp2(2, am, bm);
			float jit = hash(cx * 91.3f, cz * 57.9f + cy * 13.1f);
			float flick = 0.8f + 0.2f * MathHelper.sin(time * 19f + jit * 40f);
			// más pequeño y más rojo cuanto más hacia el borde; sube un poco y sale hacia fuera
			float size = cell * (0.35f + 0.5f * MathHelper.clamp(1f - k, 0f, 1f)) * flick;
			float lift = cell * (0.4f + 1.2f * jit) * (0.6f + 0.4f * MathHelper.sin(time * 6f + jit * 9f));
			cx += fx * cell * 0.3f;
			cy += fy * cell * 0.3f + lift;
			cz += fz * cell * 0.3f;
			float hot = MathHelper.clamp(1f - k, 0f, 1f);
			int a = (int) (215 * flick);
			int r = 255, g = (int) (95 + 150 * hot), b = (int) (15 + 120 * hot * hot);
			cube(cx, cy, cz, size * 0.5f, a, r, g, b);
		}

		private static void cube(float cx, float cy, float cz, float h, int a, int r, int g, int b) {
			float[][] v = {
				{cx - h, cy - h, cz - h}, {cx + h, cy - h, cz - h}, {cx + h, cy + h, cz - h}, {cx - h, cy + h, cz - h},
				{cx - h, cy - h, cz + h}, {cx + h, cy - h, cz + h}, {cx + h, cy + h, cz + h}, {cx - h, cy + h, cz + h}};
			int[][] f = {{0, 3, 2, 1}, {4, 5, 6, 7}, {0, 1, 5, 4}, {3, 7, 6, 2}, {0, 4, 7, 3}, {1, 2, 6, 5}};
			float[][] nrm = {{0, 0, -1}, {0, 0, 1}, {0, -1, 0}, {0, 1, 0}, {-1, 0, 0}, {1, 0, 0}};
			for (int i = 0; i < 6; i++) {
				float[] q = new float[19];
				for (int j = 0; j < 4; j++) {
					float[] p = v[f[i][j]];
					q[j * 3] = p[0];
					q[j * 3 + 1] = p[1];
					q[j * 3 + 2] = p[2];
				}
				q[12] = a; q[13] = r; q[14] = g; q[15] = b;
				q[16] = nrm[i][0]; q[17] = nrm[i][1]; q[18] = nrm[i][2];
				GLOW.add(q);
			}
		}

		private void put(int i) {
			int o = i * 8;
			d.vertex(buf[o], buf[o + 1], buf[o + 2], ibuf[i * 3], buf[o + 3], buf[o + 4], ibuf[i * 3 + 1], ibuf[i * 3 + 2], buf[o + 5], buf[o + 6], buf[o + 7]);
		}

		@Override
		public VertexConsumer vertex(float x, float y, float z) {
			d.vertex(x, y, z);
			return this;
		}

		@Override
		public VertexConsumer color(int red, int green, int blue, int alpha) {
			d.color(red, green, blue, alpha);
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

	// ------------------------------------------------------------------ fuego 3D en los huecos

	/**
	 * En el dibujo de los efectos (después de los seres): una llama pequeña de MeraVolumes en el centro de cada hueco
	 * abierto, chispas del borde y humo fino al cerrarse. at(x,y,z) son los puntos del último dibujo del jugador.
	 */
	public static void volumes(double frameNow, java.util.function.Consumer<double[]> sparks) {
		if (HOLES.isEmpty()) return;
		double t = now();
		int seed = 0;
		// en primera persona tus propios huecos quedan justo debajo de la cámara (con sombras de Iris el cuerpo se dibuja
		// igual): su llama te subiría por la cara. Ahí la llamarada la pone MeraFxClient, abajo en la pantalla
		MinecraftClient mc = MinecraftClient.getInstance();
		Entity cam = mc.getCameraEntity();
		int self = cam != null && mc.options.getPerspective().isFirstPerson() ? cam.getId() : Integer.MIN_VALUE;
		for (Map.Entry<Integer, List<Hole>> en : HOLES.entrySet()) {
			boolean own = en.getKey() == self;
			for (Hole h : en.getValue()) {
				seed++;
				if (own || h.seenAt < 0 || t - h.seenAt > 0.25) continue;
				float r = radiusNow(h, t);
				if (r <= 0.01f) continue;
				Vec3d c = new Vec3d(h.wx, h.wy, h.wz);
				float open = closing(h, t) ? r / h.radius : 1f;
				MeraVolumes.add(MeraVolumes.FLAME, c, new Vec3d(0, 0, 1), Math.max(0.08f, r * 0.75f), r * 2.4f + 0.12f, 0, 0,
					en.getKey() * 31 + seed, 0.85f * open);
				// chispas y humo: el que dibuja los efectos decide cuántos (aquí solo se le dice dónde y cuánto)
				sparks.accept(new double[]{c.x, c.y, c.z, r, closing(h, t) ? 1 : 0});
			}
		}
	}
}
