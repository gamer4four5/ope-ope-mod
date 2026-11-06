package dev.opeope.client.render;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import dev.opeope.client.ClientState;
import dev.opeope.client.config.OpeSettings;
import dev.opeope.client.input.InputHandler;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext;
import net.minecraft.block.BlockState;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.render.BufferBuilder;
import net.minecraft.client.render.BufferRenderer;
import net.minecraft.client.render.GameRenderer;
import net.minecraft.client.render.RenderTickCounter;
import net.minecraft.client.render.Tessellator;
import net.minecraft.client.render.VertexFormat;
import net.minecraft.client.render.VertexFormats;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.RotationAxis;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;
import org.joml.Matrix4f;

import java.util.ArrayList;
import java.util.List;

/**
 * El momento del corte, como en el juego: mientras trazas, una línea brillante sobre la superficie de todo lo que el
 * plano va a cortar; al soltar, una hoja de luz que barre el plano en la dirección del tajo (vista de canto, una franja en
 * pantalla) y la costura encendida sobre lo cortado, que se apaga despacio.
 */
public final class CutFx {
	private CutFx() {}

	private static final float[] EMPTY = new float[0];
	private static final long SWEEP_MS = 120, LIFE_MS = 1100, FLASH_MS = 320;

	/** Un corte: plano (n, p), dirección del tajo, zona cortada y los segmentos de la costura (9 floats cada uno). */
	private record Fx(Vec3d n, Vec3d p, Vec3d swipe, Vec3d c, double r, float[] seg, long start) {}

	/** La zona donde corta el servidor: el ROOM, el límite (Kikoku, solo el objetivo) y lo que está delante de los ojos. */
	public record Zone(Vec3d c, double r, Vec3d lc, double lr, Vec3d eye, Vec3d look) {
		boolean contains(double x, double y, double z) {
			double dx = x - c.x, dy = y - c.y, dz = z - c.z;
			if (dx * dx + dy * dy + dz * dz > r * r) return false;
			if (lc != null && lr > 0) {
				double ex = x - lc.x, ey = y - lc.y, ez = z - lc.z;
				if (ex * ex + ey * ey + ez * ez > lr * lr) return false;
			}
			if (eye != null && look != null) return (x - eye.x) * look.x + (y - eye.y) * look.y + (z - eye.z) * look.z > 0.2;
			return true;
		}

		Vec3d sampleC() {
			return lc != null && lr > 0 && lr < r ? lc : c;
		}

		double sampleR() {
			return lc != null && lr > 0 ? Math.min(lr, r) : r;
		}
	}

	private static final List<Fx> ACTIVE = new ArrayList<>();
	private static float[] preview = EMPTY;
	private static long flashAt;
	private static double flashX = 1, flashY;

	// ------------------------------------------------------------------ eventos

	/** El tajo propio: la franja de luz en pantalla (el plano pasa por los ojos y se ve de canto). */
	public static void slashFlash(double sx, double sy) {
		flashAt = System.currentTimeMillis();
		flashX = sx;
		flashY = sy;
	}

	/**
	 * Llega del servidor justo antes de que se separen los bloques: la costura se calcula sobre el mundo aún entero.
	 * Datos: c(3) r lc(3) lr eye(3) look(3), y por plano n(3) p(3) tajo(3).
	 */
	public static void receive(float[] d) {
		MinecraftClient mc = MinecraftClient.getInstance();
		if (mc.world == null || d.length < 14 + 9) return;
		Vec3d look = new Vec3d(d[11], d[12], d[13]);
		boolean eyed = look.lengthSquared() > 0.25;
		Zone z = new Zone(new Vec3d(d[0], d[1], d[2]), d[3], d[7] > 0 ? new Vec3d(d[4], d[5], d[6]) : null, d[7],
			eyed ? new Vec3d(d[8], d[9], d[10]) : null, eyed ? look : null);
		long now = System.currentTimeMillis();
		int budget = 9000;
		for (int i = 14; i + 8 < d.length; i += 9) {
			Vec3d n = new Vec3d(d[i], d[i + 1], d[i + 2]);
			Vec3d p = new Vec3d(d[i + 3], d[i + 4], d[i + 5]);
			Vec3d s = new Vec3d(d[i + 6], d[i + 7], d[i + 8]);
			float[] seg = budget > 0 ? segments(mc.world, n, p, z, budget, 64) : EMPTY;
			budget -= seg.length / 9;
			ACTIVE.add(new Fx(n, p, s, z.sampleC(), z.sampleR(), seg, now));
		}
		while (ACTIVE.size() > 32) ACTIVE.remove(0);
	}

	public static void tick() {
		MinecraftClient mc = MinecraftClient.getInstance();
		long now = System.currentTimeMillis();
		ACTIVE.removeIf(f -> now - f.start > LIFE_MS);
		double[] pl = InputHandler.dragPlane();
		if (pl == null && dev.opeope.client.input.Bisturi.active) pl = dev.opeope.client.input.Bisturi.current(1f);
		ClientState.RoomInfo room = ClientState.ownRoom();
		if (pl == null || room == null || mc.world == null || mc.player == null) {
			preview = EMPTY;
			return;
		}
		Zone z = new Zone(room.center(), room.radius(), null, 0, mc.player.getEyePos(), mc.player.getRotationVector());
		preview = segments(mc.world, new Vec3d(pl[0], pl[1], pl[2]), new Vec3d(pl[3], pl[4], pl[5]), z, 6000, 40);
	}

	public static void clear() {
		ACTIVE.clear();
		preview = EMPTY;
	}

	// ------------------------------------------------------------------ la costura: plano ∩ caras visibles

	private static boolean solid(World w, BlockPos.Mutable m) {
		BlockState st = w.getBlockState(m);
		return !st.isAir() && st.getFluidState().isEmpty() && !st.getCollisionShape(w, m).isEmpty();
	}

	private static boolean opaque(World w, BlockPos.Mutable m) {
		BlockState st = w.getBlockState(m);
		return st.isOpaqueFullCube(w, m);
	}

	private static final int[][] FACES = {{1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}};

	/**
	 * Los trozos de línea donde el plano corta las caras al aire de los bloques de la zona. Cada uno: a(3) b(3) normal de
	 * la cara(3). Recorre solo las columnas que atraviesa el plano (eje dominante de la normal).
	 */
	static float[] segments(World w, Vec3d n, Vec3d p, Zone z, int max, double maxR) {
		double ax = Math.abs(n.x), ay = Math.abs(n.y), az = Math.abs(n.z);
		int dom = ax >= ay && ax >= az ? 0 : (ay >= az ? 1 : 2);
		Vec3d sc = z.sampleC();
		double sr = Math.min(z.sampleR(), maxR);
		double[] nn = {n.x, n.y, n.z}, pp = {p.x, p.y, p.z}, cc = {sc.x, sc.y, sc.z};
		int u = (dom + 1) % 3, v = (dom + 2) % 3;
		int u0 = (int) Math.floor(cc[u] - sr), u1 = (int) Math.floor(cc[u] + sr);
		int v0 = (int) Math.floor(cc[v] - sr), v1 = (int) Math.floor(cc[v] + sr);
		float[] out = new float[Math.min(max, 4096) * 9];
		int count = 0;
		BlockPos.Mutable m = new BlockPos.Mutable(), q = new BlockPos.Mutable();
		int[] cell = new int[3];
		double[] corner = new double[3];
		for (int iu = u0; iu <= u1; iu++) {
			for (int iv = v0; iv <= v1; iv++) {
				// dónde cruza el plano esta columna
				double t = pp[dom] - (nn[u] * (iu + 0.5 - pp[u]) + nn[v] * (iv + 0.5 - pp[v])) / nn[dom];
				double spread = (Math.abs(nn[u]) + Math.abs(nn[v])) / (2 * Math.abs(nn[dom])) + 0.5;
				int d0 = (int) Math.floor(t - spread), d1 = (int) Math.floor(t + spread);
				for (int id = d0; id <= d1; id++) {
					cell[dom] = id;
					cell[u] = iu;
					cell[v] = iv;
					int x = cell[0], y = cell[1], zz = cell[2];
					if (!z.contains(x + 0.5, y + 0.5, zz + 0.5)) continue;
					// ¿el plano atraviesa el bloque?
					double dc = n.x * (x + 0.5 - p.x) + n.y * (y + 0.5 - p.y) + n.z * (zz + 0.5 - p.z);
					if (Math.abs(dc) > (ax + ay + az) * 0.5) continue;
					m.set(x, y, zz);
					if (!solid(w, m)) continue;
					for (int[] f : FACES) {
						q.set(x + f[0], y + f[1], zz + f[2]);
						if (opaque(w, q)) continue;
						// la cara: el cuadrado en el lado f del bloque
						int fa = f[0] != 0 ? 0 : (f[1] != 0 ? 1 : 2);
						double fixed = (fa == 0 ? x : fa == 1 ? y : zz) + (f[fa] > 0 ? 1 : 0);
						int a1 = (fa + 1) % 3, a2 = (fa + 2) % 3;
						double[] base = {x, y, zz};
						double[][] pts = new double[2][];
						int found = 0;
						// las cuatro aristas de la cara
						double[][] quad = new double[4][3];
						int[][] uv = {{0, 0}, {1, 0}, {1, 1}, {0, 1}};
						for (int k = 0; k < 4; k++) {
							quad[k][fa] = fixed;
							quad[k][a1] = base[a1] + uv[k][0];
							quad[k][a2] = base[a2] + uv[k][1];
						}
						for (int k = 0; k < 4 && found < 2; k++) {
							double[] A = quad[k], B = quad[(k + 1) % 4];
							double da = nn[0] * (A[0] - pp[0]) + nn[1] * (A[1] - pp[1]) + nn[2] * (A[2] - pp[2]);
							double db = nn[0] * (B[0] - pp[0]) + nn[1] * (B[1] - pp[1]) + nn[2] * (B[2] - pp[2]);
							if ((da > 0) == (db > 0)) continue;
							double tt = da / (da - db);
							pts[found++] = new double[]{A[0] + (B[0] - A[0]) * tt, A[1] + (B[1] - A[1]) * tt, A[2] + (B[2] - A[2]) * tt};
						}
						if (found < 2) continue;
						if (count * 9 + 9 > out.length) return trim(out, count);
						int o = count * 9;
						for (int k = 0; k < 3; k++) {
							corner[k] = f[k] * 0.004;
							out[o + k] = (float) (pts[0][k] + corner[k]);
							out[o + 3 + k] = (float) (pts[1][k] + corner[k]);
							out[o + 6 + k] = f[k];
						}
						count++;
					}
				}
			}
		}
		return trim(out, count);
	}

	private static float[] trim(float[] a, int count) {
		float[] r = new float[count * 9];
		System.arraycopy(a, 0, r, 0, r.length);
		return r;
	}

	// ------------------------------------------------------------------ dibujo en el mundo

	private static boolean isIdentity(Matrix4f m) {
		float e = 1e-4f;
		return Math.abs(m.m00() - 1) < e && Math.abs(m.m11() - 1) < e && Math.abs(m.m22() - 1) < e
			&& Math.abs(m.m01()) < e && Math.abs(m.m02()) < e && Math.abs(m.m10()) < e
			&& Math.abs(m.m12()) < e && Math.abs(m.m20()) < e && Math.abs(m.m21()) < e;
	}

	public static void render(WorldRenderContext ctx) {
		if (ACTIVE.isEmpty() && preview.length == 0) return;
		Vec3d cam = ctx.camera().getPos();
		Matrix4f m = isIdentity(RenderSystem.getModelViewMatrix()) ? new Matrix4f(ctx.positionMatrix()) : new Matrix4f();
		long now = System.currentTimeMillis();

		RenderSystem.enableBlend();
		RenderSystem.blendFunc(GlStateManager.SrcFactor.SRC_ALPHA, GlStateManager.DstFactor.ONE);   // luz: se suma
		RenderSystem.disableCull();
		RenderSystem.enableDepthTest();
		RenderSystem.depthMask(false);
		RenderSystem.setShader(GameRenderer::getPositionColorProgram);
		BufferBuilder buf = Tessellator.getInstance().begin(VertexFormat.DrawMode.QUADS, VertexFormats.POSITION_COLOR);

		// la vista previa: late despacio, está viva
		if (preview.length > 0) {
			float pulse = 0.85f + 0.15f * (float) Math.sin(now / 1000.0 * 6);
			lines(buf, m, cam, preview, 0.05, 115, 217, 255, (int) (230 * pulse));
			lines(buf, m, cam, preview, 0.16, 115, 217, 255, (int) (70 * pulse));
		}
		for (Fx f : ACTIVE) {
			double age = now - f.start;
			// la hoja de luz que barre el plano
			if (age < SWEEP_MS + 260) sheet(buf, m, cam, f, age);
			// la costura: blanca al principio, luego azul, y se apaga
			double k = age / LIFE_MS;
			float a = (float) Math.max(0, 1 - k * k);
			int r = (int) (255 - 130 * Math.min(1, k * 3)), g = (int) (255 - 40 * Math.min(1, k * 3));
			lines(buf, m, cam, f.seg, 0.06, r, g, 255, (int) (255 * a));
			lines(buf, m, cam, f.seg, 0.2, 140, 220, 255, (int) (90 * a));
		}
		var built = buf.endNullable();
		if (built != null) BufferRenderer.drawWithGlobalProgram(built);
		RenderSystem.depthMask(true);
		RenderSystem.enableCull();
		RenderSystem.defaultBlendFunc();
		RenderSystem.disableBlend();
	}

	/** Cada segmento como una cinta plana pegada a su cara. */
	private static void lines(BufferBuilder buf, Matrix4f m, Vec3d cam, float[] s, double width, int r, int g, int b, int a) {
		if (a <= 0) return;
		a = Math.min(255, a);
		for (int o = 0; o + 8 < s.length; o += 9) {
			double ax = s[o] - cam.x, ay = s[o + 1] - cam.y, az = s[o + 2] - cam.z;
			double bx = s[o + 3] - cam.x, by = s[o + 4] - cam.y, bz = s[o + 5] - cam.z;
			double dx = bx - ax, dy = by - ay, dz = bz - az;
			double fx = s[o + 6], fy = s[o + 7], fz = s[o + 8];
			// perpendicular dentro de la cara
			double px = dy * fz - dz * fy, py = dz * fx - dx * fz, pz = dx * fy - dy * fx;
			double len = Math.sqrt(px * px + py * py + pz * pz);
			if (len < 1e-6) continue;
			double h = width / 2 / len;
			px *= h;
			py *= h;
			pz *= h;
			// se alarga un poco por los extremos para que los trozos se unan
			double dl = Math.sqrt(dx * dx + dy * dy + dz * dz);
			double ex = dl > 1e-6 ? dx / dl * width * 0.5 : 0, ey = dl > 1e-6 ? dy / dl * width * 0.5 : 0, ez = dl > 1e-6 ? dz / dl * width * 0.5 : 0;
			double lift = width > 0.1 ? 0.006 : 0.004;
			ax -= ex - fx * lift;
			ay -= ey - fy * lift;
			az -= ez - fz * lift;
			bx += ex + fx * lift;
			by += ey + fy * lift;
			bz += ez + fz * lift;
			buf.vertex(m, (float) (ax - px), (float) (ay - py), (float) (az - pz)).color(r, g, b, a);
			buf.vertex(m, (float) (bx - px), (float) (by - py), (float) (bz - pz)).color(r, g, b, a);
			buf.vertex(m, (float) (bx + px), (float) (by + py), (float) (bz + pz)).color(r, g, b, a);
			buf.vertex(m, (float) (ax + px), (float) (ay + py), (float) (az + pz)).color(r, g, b, a);
		}
	}

	/** La hoja: el disco del plano dentro de la zona; un filo brillante lo cruza en la dirección del tajo y deja un velo que se apaga. */
	private static void sheet(BufferBuilder buf, Matrix4f m, Vec3d cam, Fx f, double age) {
		Vec3d n = f.n.normalize();
		double dc = n.dotProduct(f.c.subtract(f.p));
		if (Math.abs(dc) >= f.r) return;
		double rr = Math.sqrt(f.r * f.r - dc * dc);
		Vec3d q = f.c.subtract(n.multiply(dc));
		Vec3d s = f.swipe.subtract(n.multiply(f.swipe.dotProduct(n)));
		if (s.lengthSquared() < 1e-6) s = n.crossProduct(new Vec3d(0, 1, 0));
		if (s.lengthSquared() < 1e-6) s = new Vec3d(1, 0, 0);
		s = s.normalize();
		Vec3d o = n.crossProduct(s).normalize();
		double prog = Math.min(1, age / SWEEP_MS);
		double front = -rr + 2 * rr * prog;          // por dónde va el filo
		double fade = age < SWEEP_MS ? 1 : Math.max(0, 1 - (age - SWEEP_MS) / 260.0);
		// el velo ya barrido, en franjas
		int strips = 16;
		for (int i = 0; i < strips; i++) {
			double x0 = -rr + 2 * rr * i / strips, x1 = -rr + 2 * rr * (i + 1) / strips;
			if (x0 >= front) break;
			x1 = Math.min(x1, front);
			double h0 = Math.sqrt(Math.max(0, rr * rr - x0 * x0)), h1 = Math.sqrt(Math.max(0, rr * rr - x1 * x1));
			double behind = (front - x1) / (2 * rr);          // lo más lejano al filo ya se está apagando
			int a = (int) (60 * fade * Math.max(0, 1 - behind * 1.6));
			if (a <= 0) continue;
			quad(buf, m, cam, q, s, o, x0, -h0, x1, -h1, x1, h1, x0, h0, 190, 240, 255, a);
		}
		// el filo
		if (age < SWEEP_MS + 40) {
			double w = Math.max(0.12, rr * 0.03);
			double xa = front - w, xb = front;
			double ha = Math.sqrt(Math.max(0, rr * rr - xa * xa)), hb = Math.sqrt(Math.max(0, rr * rr - xb * xb));
			quad(buf, m, cam, q, s, o, xa, -ha, xb, -hb, xb, hb, xa, ha, 255, 255, 255, (int) (200 * fade));
		}
	}

	private static void quad(BufferBuilder buf, Matrix4f m, Vec3d cam, Vec3d q, Vec3d s, Vec3d o,
			double x0, double y0, double x1, double y1, double x2, double y2, double x3, double y3, int r, int g, int b, int a) {
		double[][] pts = {{x0, y0}, {x1, y1}, {x2, y2}, {x3, y3}};
		for (double[] p : pts) {
			Vec3d v = q.add(s.multiply(p[0])).add(o.multiply(p[1])).subtract(cam);
			buf.vertex(m, (float) v.x, (float) v.y, (float) v.z).color(r, g, b, a);
		}
	}

	// ------------------------------------------------------------------ en pantalla

	/**
	 * El trazo mientras arrastras y la franja de luz del tajo propio. La franja obedece a «Destellos» (spec §5.11): entera,
	 * suave (su pico baja a 0,05) o ninguna.
	 */
	@SuppressWarnings("deprecation")
	public static void renderHud(DrawContext ctx, RenderTickCounter tc) {
		MinecraftClient mc = MinecraftClient.getInstance();
		if (mc.player == null || mc.options.hudHidden) return;
		int w = ctx.getScaledWindowWidth(), h = ctx.getScaledWindowHeight();
		float cx = w / 2f, cy = h / 2f;
		double diag = Math.sqrt(w * w + h * h);
		if (InputHandler.isDragging()) {
			double[] s = InputHandler.swipeScreen();
			float ang = (float) Math.atan2(-s[1], s[0]);
			// la recta del plano en pantalla, fina y a trazos
			ctx.getMatrices().push();
			ctx.getMatrices().translate(cx, cy, 0);
			ctx.getMatrices().multiply(RotationAxis.POSITIVE_Z.rotation(ang));
			int half = (int) (diag / 2);
			// los ~50 trazos en una sola tanda
			ctx.draw(() -> {
				for (int x = -half; x < half; x += 10) ctx.fill(x, 0, x + 5, 1, 0x668FD8FF);
			});
			// el trazo: lo que llevas arrastrado
			double scale = mc.getWindow().getScaleFactor();
			double len = Math.sqrt(InputHandler.dragX() * InputHandler.dragX() + InputHandler.dragY() * InputHandler.dragY()) / scale * 0.9;
			int l = (int) Math.min(half, Math.max(6, len));
			ctx.fill(-l, -2, l, 3, 0x408FD8FF);
			ctx.fill(-l, 0, l, 1, 0xF0FFFFFF);
			ctx.getMatrices().pop();
		}
		long age = System.currentTimeMillis() - flashAt;
		int level = OpeSettings.flashLevel();
		if (level > 0 && age >= 0 && age < FLASH_MS) {
			double k = age / (double) FLASH_MS;
			// destellos suaves: alfa × min(1, 0,05 / pico); el pico es la línea blanca (1)
			double soft = level >= 2 ? 1.0 : Math.min(1.0, 0.05 / 1.0);
			float ang = (float) Math.atan2(-flashY, flashX);
			int half = (int) (diag / 2 * Math.min(1, age / 90.0 + 0.25));
			int a = (int) (255 * (1 - k) * soft);
			int glow = (int) (90 * (1 - k) * soft);
			ctx.getMatrices().push();
			ctx.getMatrices().translate(cx, cy, 0);
			ctx.getMatrices().multiply(RotationAxis.POSITIVE_Z.rotation(ang));
			ctx.fill(-half, -6, half, 7, (glow << 24) | 0x8FD8FF);
			ctx.fill(-half, -2, half, 3, (Math.min(255, glow * 2) << 24) | 0xBFEFFF);
			ctx.fill(-half, 0, half, 1, (a << 24) | 0xFFFFFF);
			ctx.getMatrices().pop();
		}
	}
}
