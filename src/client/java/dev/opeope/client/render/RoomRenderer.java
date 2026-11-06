package dev.opeope.client.render;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import dev.opeope.client.config.OpeSettings;
import dev.opeope.client.input.Bisturi;
import dev.opeope.client.room.GroundLine;
import dev.opeope.client.room.RoomFxClient;
import dev.opeope.client.room.RoomVisuals;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.BufferBuilder;
import net.minecraft.client.render.BufferRenderer;
import net.minecraft.client.render.GameRenderer;
import net.minecraft.client.render.Tessellator;
import net.minecraft.client.render.VertexFormat;
import net.minecraft.client.render.VertexFormats;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.Vec3d;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import java.util.List;

/**
 * El ROOM en el mundo (spec §5.4) y la superficie del Bisturí. Tres pasadas:
 * <ol>
 * <li>Mezcla normal: la esfera (borde brillante tipo fresnel, más el suelo de la cúpula si estás dentro), la línea donde
 * toca el suelo, las ondas por el suelo y los planos del Bisturí.</li>
 * <li>Luz que se suma: el contorno de la esfera vista desde fuera, las cáscaras de los momentos (eco, onda, pliegue,
 * cierre), la semilla de la mano, el cometa con su estela y la cuerda que lo trae.</li>
 * <li>Luz que se suma y se ve a través de todo: el destello del aterrizaje.</li>
 * </ol>
 * Todo va relativo a la cámara y sin crear objetos por fotograma (tablas y arrays fijos).
 */
public final class RoomRenderer {
	private RoomRenderer() {}

	private static final int LAT = 28, LON = 56, ELAT = 14, ELON = 28;
	private static final int OUTLINE_SEG = 96, DISC_SEG = 16, ARC_SEG = 10, TRAIL = 28, TETHER_SEG = 16;
	private static final double TRAIL_SECONDS = 0.16;

	private static final int CORE_RGB = 0xBFF4FF, GLOW_RGB = 0x8FD8FF, GROUND_RGB = 0x9FE6FF;
	private static final int HI_RGB = 0xE8FBFF, ROOM_RGB = 0x8FE3FF, SOFT_RGB = 0x9FE6FF;

	/** Normales de la esfera unidad: (LAT + 1)·(LON + 1) puntos, 3 floats cada uno. */
	private static final float[] UNIT = sphereTable(LAT, LON);
	private static final float[] EUNIT = sphereTable(ELAT, ELON);
	/** La rejilla del fotograma: x, y, z y alfa de cada punto (se reutiliza para cada esfera). */
	private static final float[] GRID = new float[(LAT + 1) * (LON + 1) * 4];

	private static final float[] OCOS = new float[OUTLINE_SEG + 1], OSIN = new float[OUTLINE_SEG + 1];
	private static final float[] DCOS = new float[DISC_SEG + 1], DSIN = new float[DISC_SEG + 1];
	private static final float[] GCOS = new float[RoomVisuals.Fx.RING_ANGLES + 1], GSIN = new float[RoomVisuals.Fx.RING_ANGLES + 1];

	static {
		for (int i = 0; i <= OUTLINE_SEG; i++) {
			double a = Math.PI * 2 * i / OUTLINE_SEG;
			OCOS[i] = (float) Math.cos(a);
			OSIN[i] = (float) Math.sin(a);
		}
		for (int i = 0; i <= DISC_SEG; i++) {
			double a = Math.PI * 2 * i / DISC_SEG;
			DCOS[i] = (float) Math.cos(a);
			DSIN[i] = (float) Math.sin(a);
		}
		int n = RoomVisuals.Fx.RING_ANGLES;
		for (int i = 0; i <= n; i++) {
			double a = Math.PI * 2 * i / n;
			GCOS[i] = (float) Math.cos(a);
			GSIN[i] = (float) Math.sin(a);
		}
	}

	// la matriz base y su atajo: si la vista ya va en el modelo, los puntos van tal cual
	private static final Matrix4f BASE = new Matrix4f();
	private static boolean plain;
	private static final Vector3f TV = new Vector3f();

	// arrays de trabajo
	private static final double[] P = new double[3], Q = new double[3];
	private static final double[] TR = new double[TRAIL * 3];
	private static final double[] TE = new double[(TETHER_SEG + 1) * 3];
	// la base de un cartel que mira a la cámara: derecha y arriba
	private static double brx, bry, brz, bux, buy, buz;
	// los dos ejes de un plano (contorno)
	private static double e1x, e1y, e1z, e2x, e2y, e2z;

	private static boolean isIdentity(Matrix4f m) {
		float e = 1e-4f;
		return Math.abs(m.m00() - 1) < e && Math.abs(m.m11() - 1) < e && Math.abs(m.m22() - 1) < e
			&& Math.abs(m.m01()) < e && Math.abs(m.m02()) < e && Math.abs(m.m10()) < e
			&& Math.abs(m.m12()) < e && Math.abs(m.m20()) < e && Math.abs(m.m21()) < e;
	}

	private static float[] sphereTable(int lat, int lon) {
		float[] t = new float[(lat + 1) * (lon + 1) * 3];
		int o = 0;
		for (int i = 0; i <= lat; i++) {
			double th = Math.PI * i / lat;
			for (int j = 0; j <= lon; j++) {
				double ph = 2 * Math.PI * j / lon;
				t[o++] = (float) (Math.sin(th) * Math.cos(ph));
				t[o++] = (float) Math.cos(th);
				t[o++] = (float) (Math.sin(th) * Math.sin(ph));
			}
		}
		return t;
	}

	// ------------------------------------------------------------------ fotograma

	public static void render(WorldRenderContext ctx) {
		MinecraftClient mc = MinecraftClient.getInstance();
		ClientWorld world = mc.world;
		if (world == null) return;
		Identifier dim = world.getRegistryKey().getValue();
		boolean rooms = RoomVisuals.anything(dim);
		if (!rooms && !Bisturi.active) return;

		Vec3d cam = ctx.camera().getPos();
		// Según la versión de Fabric, la rotación de la cámara va en la matriz del modelo o no: se mira y se añade si falta.
		if (isIdentity(RenderSystem.getModelViewMatrix())) {
			BASE.set(ctx.positionMatrix());
			plain = false;
		} else {
			BASE.identity();
			plain = true;
		}
		float td = ctx.tickCounter().getTickDelta(true);
		double now = RoomFxClient.time();
		boolean calm = OpeSettings.reducedMotion();
		List<RoomVisuals.Vis> all = RoomVisuals.all();
		List<RoomVisuals.Fx> fx = RoomVisuals.effects();

		RenderSystem.enableBlend();
		RenderSystem.defaultBlendFunc();
		RenderSystem.disableCull();
		RenderSystem.enableDepthTest();
		RenderSystem.depthMask(false);
		RenderSystem.setShader(GameRenderer::getPositionColorProgram);

		// 1) mezcla normal
		BufferBuilder buf = Tessellator.getInstance().begin(VertexFormat.DrawMode.QUADS, VertexFormats.POSITION_COLOR);
		if (rooms) {
			for (int i = 0; i < all.size(); i++) {
				RoomVisuals.Vis v = all.get(i);
				if (!dim.equals(v.dim) || !v.shown()) continue;
				shell(buf, v, cam, td);
				ground(buf, v, cam, td);
			}
			for (int i = 0; i < fx.size(); i++) {
				RoomVisuals.Fx f = fx.get(i);
				if (f.type == RoomVisuals.Fx.RING && dim.equals(f.dim)) groundRing(buf, f, cam, now);
			}
		}
		if (Bisturi.active) {
			for (double[] pl : Bisturi.COMMITTED) plane(buf, pl, cam, 0x3FE8FBFF);
			double[] cur = Bisturi.current(td);
			if (cur != null) plane(buf, cur, cam, 0x70BFF4FF);
		}
		draw(buf);

		if (rooms) {
			// 2) luz que se suma
			RenderSystem.blendFunc(GlStateManager.SrcFactor.SRC_ALPHA, GlStateManager.DstFactor.ONE);
			buf = Tessellator.getInstance().begin(VertexFormat.DrawMode.QUADS, VertexFormats.POSITION_COLOR);
			boolean flashes = false;
			for (int i = 0; i < all.size(); i++) {
				RoomVisuals.Vis v = all.get(i);
				if (dim.equals(v.dim) && v.shown()) outline(buf, v, cam, td);
			}
			for (int i = 0; i < fx.size(); i++) {
				RoomVisuals.Fx f = fx.get(i);
				if (!dim.equals(f.dim)) continue;
				switch (f.type) {
					case RoomVisuals.Fx.ECHO, RoomVisuals.Fx.SHOCK, RoomVisuals.Fx.FOLD, RoomVisuals.Fx.CLOSE -> effectShell(buf, f, cam, now);
					case RoomVisuals.Fx.COMET -> comet(buf, f, cam, now, calm);
					case RoomVisuals.Fx.TETHER -> tether(buf, f, cam, td, now, calm, world);
					case RoomVisuals.Fx.FLASH -> flashes |= f.progress(now) < 1;
					default -> {
					}
				}
			}
			seed(buf, mc, cam, td, now, calm);
			draw(buf);

			// 3) el destello del aterrizaje, por encima de todo
			if (flashes) {
				RenderSystem.disableDepthTest();
				buf = Tessellator.getInstance().begin(VertexFormat.DrawMode.QUADS, VertexFormats.POSITION_COLOR);
				int level = OpeSettings.flashLevel();
				for (int i = 0; i < fx.size(); i++) {
					RoomVisuals.Fx f = fx.get(i);
					if (f.type == RoomVisuals.Fx.FLASH && dim.equals(f.dim)) landFlash(buf, f, cam, now, level);
				}
				draw(buf);
				RenderSystem.enableDepthTest();
			}
		}

		RenderSystem.defaultBlendFunc();
		RenderSystem.depthMask(true);
		RenderSystem.enableCull();
		RenderSystem.disableBlend();
	}

	private static void draw(BufferBuilder buf) {
		var built = buf.endNullable();
		if (built != null) BufferRenderer.drawWithGlobalProgram(built);
	}

	// ------------------------------------------------------------------ vértices

	private static void vtx(BufferBuilder b, double x, double y, double z, int rgb, float a) {
		if (a < 0f) a = 0f;
		else if (a > 1f) a = 1f;
		float fx = (float) x, fy = (float) y, fz = (float) z;
		if (!plain) {
			BASE.transformPosition(fx, fy, fz, TV);
			fx = TV.x;
			fy = TV.y;
			fz = TV.z;
		}
		b.vertex(fx, fy, fz).color((rgb >> 16) & 255, (rgb >> 8) & 255, rgb & 255, (int) (a * 255f + 0.5f));
	}

	/** Un punto de la rejilla del fotograma. */
	private static void gv(BufferBuilder b, int g, int rgb) {
		vtx(b, GRID[g], GRID[g + 1], GRID[g + 2], rgb, GRID[g + 3]);
	}

	/** Llena la rejilla con la esfera (centro relativo a la cámara) y el alfa de borde de cada punto. */
	private static void fillGrid(float[] unit, int lat, int lon, double cx, double cy, double cz, double r,
			double base, double rim, double scale, boolean floor) {
		int o = 0, u = 0;
		int n = (lat + 1) * (lon + 1);
		for (int k = 0; k < n; k++) {
			float nx = unit[u++], ny = unit[u++], nz = unit[u++];
			double x = cx + nx * r, y = cy + ny * r, z = cz + nz * r;
			double len = Math.sqrt(x * x + y * y + z * z);
			double facing = len < 1e-4 ? 1 : Math.abs((nx * x + ny * y + nz * z) / len);
			double fres = 1 - facing;
			double a = base + rim * fres * fres * fres;
			if (floor && ny < 0) {
				double t = -ny;
				a += 0.10 * t * Math.sqrt(t);
			}
			GRID[o++] = (float) x;
			GRID[o++] = (float) y;
			GRID[o++] = (float) z;
			GRID[o++] = (float) (a * scale);
		}
	}

	private static void emitGrid(BufferBuilder b, int lat, int lon, int rgb) {
		int row = lon + 1;
		for (int i = 0; i < lat; i++) {
			for (int j = 0; j < lon; j++) {
				int g00 = (i * row + j) * 4, g10 = ((i + 1) * row + j) * 4;
				gv(b, g00, rgb);
				gv(b, g10, rgb);
				gv(b, g10 + 4, rgb);
				gv(b, g00 + 4, rgb);
			}
		}
	}

	/** La base de un cartel en el punto (relativo a la cámara): solo con la posición y el «arriba» del mundo. */
	private static boolean billboard(double x, double y, double z) {
		double l = Math.sqrt(x * x + y * y + z * z);
		if (l < 1e-5) return false;
		double fx = x / l, fy = y / l, fz = z / l;
		// derecha = adelante × arriba
		double rx = -fz, ry = 0, rz = fx;
		double rl = Math.sqrt(rx * rx + rz * rz);
		if (rl < 1e-4) {
			rx = 1;
			rz = 0;
			rl = 1;
		}
		brx = rx / rl;
		bry = ry;
		brz = rz / rl;
		// arriba = derecha × adelante
		bux = bry * fz - brz * fy;
		buy = brz * fx - brx * fz;
		buz = brx * fy - bry * fx;
		return true;
	}

	/** Un disco que mira a la cámara: del centro (rgbC, aC) al borde (rgbE, aE). */
	private static void disc(BufferBuilder b, double x, double y, double z, double r, int rgbC, float aC, int rgbE, float aE) {
		if (r <= 0 || (aC < 0.003f && aE < 0.003f)) return;
		for (int i = 0; i < DISC_SEG; i++) {
			double c0 = DCOS[i] * r, s0 = DSIN[i] * r, c1 = DCOS[i + 1] * r, s1 = DSIN[i + 1] * r;
			vtx(b, x, y, z, rgbC, aC);
			vtx(b, x + brx * c0 + bux * s0, y + bry * c0 + buy * s0, z + brz * c0 + buz * s0, rgbE, aE);
			vtx(b, x + brx * c1 + bux * s1, y + bry * c1 + buy * s1, z + brz * c1 + buz * s1, rgbE, aE);
			vtx(b, x, y, z, rgbC, aC);
		}
	}

	// ------------------------------------------------------------------ la esfera

	/** La esfera: alfa = (0,05 + 0,55·fres³)·fuerza·(1 + 1,2·pulso); dentro, el suelo de la cúpula un poco más. */
	private static void shell(BufferBuilder b, RoomVisuals.Vis v, Vec3d cam, float td) {
		float r = v.radius(td);
		if (r <= 0.01f) return;
		double cx = v.x(td) - cam.x, cy = v.y(td) - cam.y, cz = v.z(td) - cam.z;
		boolean inside = cx * cx + cy * cy + cz * cz < (double) r * r;
		double scale = v.strength * v.fade(td) * (1f + 1.2f * v.pulse(td));
		if (scale <= 0.003) return;
		fillGrid(UNIT, LAT, LON, cx, cy, cz, r, 0.05, 0.55, scale, inside);
		emitGrid(b, LAT, LON, v.rgb());
	}

	/** El contorno de la esfera vista desde fuera: un anillo fino y blanco y un halo azul a su alrededor. */
	private static void outline(BufferBuilder b, RoomVisuals.Vis v, Vec3d cam, float td) {
		double r = v.radius(td);
		if (r <= 0.05) return;
		double cx = v.x(td) - cam.x, cy = v.y(td) - cam.y, cz = v.z(td) - cam.z;
		double d2 = cx * cx + cy * cy + cz * cz;
		if (d2 <= r * r * 1.0004) return;
		double d = Math.sqrt(d2);
		float s = v.strength * v.fade(td);
		if (s <= 0.003f) return;
		// de c hacia la cámara
		double wx = -cx / d, wy = -cy / d, wz = -cz / d;
		double k = r * r / d;
		double ox = cx + wx * k, oy = cy + wy * k, oz = cz + wz * k;
		double rho = r * Math.sqrt(Math.max(0, 1 - r * r / d2));
		planeAxes(wx, wy, wz);
		float pulse = 1f + 0.5f * v.pulse(td);
		double glow = 0.010 * d, core = 0.003 * d;
		ringBand(b, ox, oy, oz, rho - glow / 2, rho, GLOW_RGB, 0f, 0.25f * s * pulse);
		ringBand(b, ox, oy, oz, rho, rho + glow / 2, GLOW_RGB, 0.25f * s * pulse, 0f);
		ringBand(b, ox, oy, oz, rho - core / 2, rho + core / 2, CORE_RGB, 0.9f * s, 0.9f * s);
	}

	/** Dos ejes perpendiculares a w (para el plano del contorno). */
	private static void planeAxes(double wx, double wy, double wz) {
		// un vector que no sea paralelo a w
		double ax = Math.abs(wy) < 0.9 ? 0 : 1, ay = Math.abs(wy) < 0.9 ? 1 : 0;
		// e1 = w × a
		double x = wy * 0 - wz * ay, y = wz * ax - wx * 0, z = wx * ay - wy * ax;
		double l = Math.sqrt(x * x + y * y + z * z);
		e1x = x / l;
		e1y = y / l;
		e1z = z / l;
		// e2 = w × e1
		e2x = wy * e1z - wz * e1y;
		e2y = wz * e1x - wx * e1z;
		e2z = wx * e1y - wy * e1x;
	}

	/** Una banda del anillo en el plano (e1, e2), de rIn a rOut, con alfa aIn → aOut. */
	private static void ringBand(BufferBuilder b, double ox, double oy, double oz, double rIn, double rOut, int rgb, float aIn, float aOut) {
		if (rOut <= 0 || (aIn < 0.003f && aOut < 0.003f)) return;
		rIn = Math.max(0, rIn);
		for (int i = 0; i < OUTLINE_SEG; i++) {
			double dx0 = e1x * OCOS[i] + e2x * OSIN[i], dy0 = e1y * OCOS[i] + e2y * OSIN[i], dz0 = e1z * OCOS[i] + e2z * OSIN[i];
			double dx1 = e1x * OCOS[i + 1] + e2x * OSIN[i + 1], dy1 = e1y * OCOS[i + 1] + e2y * OSIN[i + 1], dz1 = e1z * OCOS[i + 1] + e2z * OSIN[i + 1];
			vtx(b, ox + dx0 * rIn, oy + dy0 * rIn, oz + dz0 * rIn, rgb, aIn);
			vtx(b, ox + dx0 * rOut, oy + dy0 * rOut, oz + dz0 * rOut, rgb, aOut);
			vtx(b, ox + dx1 * rOut, oy + dy1 * rOut, oz + dz1 * rOut, rgb, aOut);
			vtx(b, ox + dx1 * rIn, oy + dy1 * rIn, oz + dz1 * rIn, rgb, aIn);
		}
	}

	/**
	 * La línea donde la cúpula toca el suelo: una cinta que sale del suelo hacia la cámara, de ancho max(0,08, 0,004·d),
	 * más brillante abajo. Se corta donde no hay suelo dentro de la esfera o donde el terreno salta.
	 */
	private static void ground(BufferBuilder b, RoomVisuals.Vis v, Vec3d cam, float td) {
		GroundLine g = v.ground;
		int n = g.n;
		if (n < 3) return;
		double wx = v.x(td), wy = v.y(td), wz = v.z(td);
		double r = v.radius(td);
		float a = (v.mine ? 0.55f : 0.275f) * v.fade(td);
		if (a <= 0.003f) return;
		float aTop = a * 0.3f;
		boolean prev = false;
		double px = 0, py = 0, pz = 0, qx = 0, qy = 0, qz = 0;
		for (int i = 0; i <= n; i++) {
			int ii = i == n ? 0 : i;
			if (!g.point(ii, wx, wy, wz, r, P)) {
				prev = false;
				continue;
			}
			double x = P[0] - cam.x, y = P[1] - cam.y, z = P[2] - cam.z;
			double dist = Math.sqrt(x * x + y * y + z * z);
			if (dist < 1e-3) {
				prev = false;
				continue;
			}
			double w = Math.max(0.08, 0.004 * dist);
			// ancho = tangente × (hacia la cámara), siempre hacia arriba
			double tx = -g.sin[ii], tz = g.cos[ii];
			double vx = -x / dist, vy = -y / dist, vz = -z / dist;
			double ux = -tz * vy, uy = tz * vx - tx * vz, uz = tx * vy;
			double ul = Math.sqrt(ux * ux + uy * uy + uz * uz);
			if (ul < 1e-5) {
				ux = 0;
				uy = 1;
				uz = 0;
				ul = 1;
			}
			if (uy < 0) ul = -ul;
			ux = ux / ul * w;
			uy = uy / ul * w;
			uz = uz / ul * w;
			double tx2 = x + ux, ty2 = y + uy, tz2 = z + uz;
			if (prev && Math.abs(y - py) < 2.0) {
				vtx(b, px, py, pz, GROUND_RGB, a);
				vtx(b, qx, qy, qz, GROUND_RGB, aTop);
				vtx(b, tx2, ty2, tz2, GROUND_RGB, aTop);
				vtx(b, x, y, z, GROUND_RGB, a);
			}
			px = x;
			py = y;
			pz = z;
			qx = tx2;
			qy = ty2;
			qz = tz2;
			prev = true;
		}
	}

	/** Una onda por el suelo: una cinta plana de 0,35 a ras de suelo que se abre en 0,6 s y se apaga. */
	private static void groundRing(BufferBuilder b, RoomVisuals.Fx f, Vec3d cam, double now) {
		float[] hs = f.heights;
		if (hs == null) return;
		double u = f.progress(now);
		float a = (float) (f.alpha * (1 - u));
		if (a <= 0.003f) return;
		double rr = f.r0 * (1 - Math.pow(1 - u, 2.4));
		if (rr < 0.2) return;
		int steps = RoomVisuals.Fx.RING_STEPS, n = RoomVisuals.Fx.RING_ANGLES;
		int step = Math.max(0, Math.min(steps - 1, (int) Math.round(rr / f.r0 * steps) - 1));
		double rIn = Math.max(0, rr - 0.175), rOut = rr + 0.175;
		double cx = f.ax - cam.x, cz = f.az - cam.z;
		for (int i = 0; i < n; i++) {
			int i1 = i + 1 == n ? 0 : i + 1;
			float h0 = hs[i * steps + step], h1 = hs[i1 * steps + step];
			if (Float.isNaN(h0) || Float.isNaN(h1) || Math.abs(h0 - h1) > 1.5f) continue;
			double y0 = h0 + 0.05 - cam.y, y1 = h1 + 0.05 - cam.y;
			vtx(b, cx + GCOS[i] * rIn, y0, cz + GSIN[i] * rIn, f.rgb, a * 0.25f);
			vtx(b, cx + GCOS[i] * rOut, y0, cz + GSIN[i] * rOut, f.rgb, a);
			vtx(b, cx + GCOS[i + 1] * rOut, y1, cz + GSIN[i + 1] * rOut, f.rgb, a);
			vtx(b, cx + GCOS[i + 1] * rIn, y1, cz + GSIN[i + 1] * rIn, f.rgb, a * 0.25f);
		}
	}

	// ------------------------------------------------------------------ momentos

	/** Eco, onda, pliegue y cierre: una esfera más sencilla, sobre todo borde. */
	private static void effectShell(BufferBuilder b, RoomVisuals.Fx f, Vec3d cam, double now) {
		float r = f.shellRadius(now);
		float a = f.shellAlpha(now);
		if (r < 0.05f || a < 0.004f) return;
		double cx = f.ax - cam.x, cy = f.ay - cam.y, cz = f.az - cam.z;
		fillGrid(EUNIT, ELAT, ELON, cx, cy, cz, r, 0.15, 0.85, a, false);
		emitGrid(b, ELAT, ELON, f.rgb);
	}

	/** El cometa: una luz con su estela de 28 puntos que se apaga hacia atrás. */
	private static void comet(BufferBuilder b, RoomVisuals.Fx f, Vec3d cam, double now, boolean calm) {
		if (f.ended || now < f.start) return;
		float a = f.alpha;
		// la estela: dónde estuvo hace un poco
		for (int k = 0; k < TRAIL; k++) {
			f.cometPos(now - k * (TRAIL_SECONDS / (TRAIL - 1)), P);
			TR[k * 3] = P[0] - cam.x;
			TR[k * 3 + 1] = P[1] - cam.y;
			TR[k * 3 + 2] = P[2] - cam.z;
		}
		for (int k = 0; k < TRAIL - 1; k++) {
			int o0 = k * 3, o1 = o0 + 3;
			double sx = TR[o1] - TR[o0], sy = TR[o1 + 1] - TR[o0 + 1], sz = TR[o1 + 2] - TR[o0 + 2];
			double sl = Math.sqrt(sx * sx + sy * sy + sz * sz);
			if (sl < 1e-5) continue;
			float k0 = 1f - k / (float) (TRAIL - 1), k1 = 1f - (k + 1) / (float) (TRAIL - 1);
			ribbon(b, TR[o0], TR[o0 + 1], TR[o0 + 2], TR[o1], TR[o1 + 1], TR[o1 + 2], 0.2 * k0, 0.2 * k1,
				ROOM_RGB, 0.7f * a * k0 * k0, 0.7f * a * k1 * k1);
		}
		glow(b, TR[0], TR[1], TR[2], a, now, calm);
	}

	/** Un tramo de cinta que mira a la cámara, de a (ancho wa, alfa aa) a b (wb, ab). */
	private static void ribbon(BufferBuilder b, double ax, double ay, double az, double bx, double by, double bz,
			double wa, double wb, int rgb, float aa, float ab) {
		double sx = bx - ax, sy = by - ay, sz = bz - az;
		// el punto medio hacia la cámara (que está en el origen)
		double mx = -(ax + bx) / 2, my = -(ay + by) / 2, mz = -(az + bz) / 2;
		double px = sy * mz - sz * my, py = sz * mx - sx * mz, pz = sx * my - sy * mx;
		double pl = Math.sqrt(px * px + py * py + pz * pz);
		if (pl < 1e-7) return;
		px /= pl;
		py /= pl;
		pz /= pl;
		double ha = wa / 2, hb = wb / 2;
		vtx(b, ax - px * ha, ay - py * ha, az - pz * ha, rgb, aa);
		vtx(b, bx - px * hb, by - py * hb, bz - pz * hb, rgb, ab);
		vtx(b, bx + px * hb, by + py * hb, bz + pz * hb, rgb, ab);
		vtx(b, ax + px * ha, ay + py * ha, az + pz * ha, rgb, aa);
	}

	/**
	 * La luz de la semilla y del cometa: núcleo blanco (0,10), anillo azul (0,25), halo suave (0,6) y tres arcos de 100°
	 * a 0,4 que giran a 7 rad/s; parpadea un poco. Con «menos movimiento», ni giro ni parpadeo.
	 */
	private static void glow(BufferBuilder b, double x, double y, double z, float a, double now, boolean calm) {
		if (a <= 0.003f || !billboard(x, y, z)) return;
		if (!calm) a *= (float) (0.85 + 0.15 * Math.sin(Math.PI * 2 * 40 * now));
		disc(b, x, y, z, 0.6, SOFT_RGB, 0.18f * a, SOFT_RGB, 0f);
		disc(b, x, y, z, 0.25, ROOM_RGB, 0.5f * a, ROOM_RGB, 0f);
		disc(b, x, y, z, 0.10, HI_RGB, a, HI_RGB, 0.5f * a);
		double spin = calm ? 0 : 7 * now;
		double span = Math.toRadians(100), in = 0.38, out = 0.42;
		float aa = 0.7f * a;
		for (int k = 0; k < 3; k++) {
			double start = spin + k * Math.PI * 2 / 3;
			for (int i = 0; i < ARC_SEG; i++) {
				double t0 = start + span * i / ARC_SEG, t1 = start + span * (i + 1) / ARC_SEG;
				double c0 = Math.cos(t0), s0 = Math.sin(t0), c1 = Math.cos(t1), s1 = Math.sin(t1);
				// las puntas del arco se apagan
				float f0 = arcFade(i / (float) ARC_SEG), f1 = arcFade((i + 1) / (float) ARC_SEG);
				vtx(b, x + (brx * c0 + bux * s0) * in, y + (bry * c0 + buy * s0) * in, z + (brz * c0 + buz * s0) * in, ROOM_RGB, aa * f0);
				vtx(b, x + (brx * c0 + bux * s0) * out, y + (bry * c0 + buy * s0) * out, z + (brz * c0 + buz * s0) * out, ROOM_RGB, aa * f0);
				vtx(b, x + (brx * c1 + bux * s1) * out, y + (bry * c1 + buy * s1) * out, z + (brz * c1 + buz * s1) * out, ROOM_RGB, aa * f1);
				vtx(b, x + (brx * c1 + bux * s1) * in, y + (bry * c1 + buy * s1) * in, z + (brz * c1 + buz * s1) * in, ROOM_RGB, aa * f1);
			}
		}
	}

	private static float arcFade(float t) {
		return Math.min(1f, Math.min(t, 1f - t) * 5f);
	}

	/** La semilla en tu mano mientras el servidor contesta. */
	private static void seed(BufferBuilder b, MinecraftClient mc, Vec3d cam, float td, double now, boolean calm) {
		float a = RoomVisuals.seedAlpha(now);
		if (a <= 0.003f || mc.player == null) return;
		RoomVisuals.handPoint(mc.player, td, P);
		glow(b, P[0] - cam.x, P[1] - cam.y, P[2] - cam.z, a, now, calm);
	}

	/** La cuerda que trae el ROOM: del centro a tu pecho, con una leve ondulación. */
	private static void tether(BufferBuilder b, RoomVisuals.Fx f, Vec3d cam, float td, double now, boolean calm, ClientWorld world) {
		if (f.ended || f.owner == null) return;
		RoomVisuals.Vis v = RoomVisuals.byKey(f.key);
		PlayerEntity p = world.getPlayerByUuid(f.owner);
		if (v == null || p == null) return;
		Vec3d pp = p.getLerpedPos(td);
		double ax = v.x(td) - cam.x, ay = v.y(td) - cam.y, az = v.z(td) - cam.z;
		double bx = pp.x - cam.x, by = pp.y + 1.1 - cam.y, bz = pp.z - cam.z;
		float a = f.alpha * (float) Math.min(1.0, (now - f.start) / 0.1);
		if (a <= 0.003f) return;
		for (int i = 0; i <= TETHER_SEG; i++) {
			double s = i / (double) TETHER_SEG;
			double sag = calm ? 0 : 0.15 * Math.sin(Math.PI * s) * Math.sin(Math.PI * 2 * 3 * now - 6 * s);
			TE[i * 3] = ax + (bx - ax) * s;
			TE[i * 3 + 1] = ay + (by - ay) * s + sag;
			TE[i * 3 + 2] = az + (bz - az) * s;
		}
		for (int i = 0; i < TETHER_SEG; i++) {
			int o0 = i * 3, o1 = o0 + 3;
			double d0 = Math.sqrt(TE[o0] * TE[o0] + TE[o0 + 1] * TE[o0 + 1] + TE[o0 + 2] * TE[o0 + 2]);
			double d1 = Math.sqrt(TE[o1] * TE[o1] + TE[o1 + 1] * TE[o1 + 1] + TE[o1 + 2] * TE[o1 + 2]);
			double w0 = Math.max(0.05, 0.004 * d0), w1 = Math.max(0.05, 0.004 * d1);
			ribbon(b, TE[o0], TE[o0 + 1], TE[o0 + 2], TE[o1], TE[o1 + 1], TE[o1 + 2], w0 * 3, w1 * 3, GLOW_RGB, a * 0.35f, a * 0.35f);
			ribbon(b, TE[o0], TE[o0 + 1], TE[o0 + 2], TE[o1], TE[o1 + 1], TE[o1 + 2], w0, w1, CORE_RGB, a, a);
		}
	}

	/** El destello del aterrizaje: un disco que crece de 0,7 a 1,5 veces (5 + 0,1·R) en 0,25 s y se apaga. */
	private static void landFlash(BufferBuilder b, RoomVisuals.Fx f, Vec3d cam, double now, int level) {
		double u = f.progress(now);
		if (u >= 1) return;
		float a = (float) (f.alpha * (1 - u));
		if (level == 0) a *= 0.3f;
		if (a <= 0.003f) return;
		double x = f.ax - cam.x, y = f.ay - cam.y, z = f.az - cam.z;
		if (!billboard(x, y, z)) return;
		double size = (5 + 0.1 * f.r0) * (0.7 + 0.8 * u);
		disc(b, x, y, z, size, HI_RGB, a, ROOM_RGB, 0f);
		disc(b, x, y, z, size * 0.35, HI_RGB, a, HI_RGB, a * 0.4f);
	}

	// ------------------------------------------------------------------ Bisturí

	/** El plano del Bisturí: un rectángulo de 24 × 16 centrado en el punto que señalas (como antes). */
	private static void plane(BufferBuilder buf, double[] pl, Vec3d cam, int argb) {
		double px = pl[3] - cam.x, py = pl[4] - cam.y, pz = pl[5] - cam.z;
		double sx = pl[6], sy = pl[7], sz = pl[8];
		double fx = pl[9], fy = pl[10], fz = pl[11];
		double a = 12, b = 8;
		int rgb = argb & 0xFFFFFF;
		float al = ((argb >>> 24) & 255) / 255f;
		vtx(buf, px - sx * a - fx * b, py - sy * a - fy * b, pz - sz * a - fz * b, rgb, al);
		vtx(buf, px + sx * a - fx * b, py + sy * a - fy * b, pz + sz * a - fz * b, rgb, al);
		vtx(buf, px + sx * a + fx * b, py + sy * a + fy * b, pz + sz * a + fz * b, rgb, al);
		vtx(buf, px - sx * a + fx * b, py - sy * a + fy * b, pz - sz * a + fz * b, rgb, al);
	}
}
