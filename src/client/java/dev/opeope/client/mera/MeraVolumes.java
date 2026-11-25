package dev.opeope.client.mera;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import dev.opeope.OpeOpeMod;
import net.fabricmc.fabric.api.client.rendering.v1.CoreShaderRegistrationCallback;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gl.Framebuffer;
import net.minecraft.client.gl.GlUniform;
import net.minecraft.client.gl.ShaderProgram;
import net.minecraft.client.gl.SimpleFramebuffer;
import net.minecraft.client.render.BufferBuilder;
import net.minecraft.client.render.BufferRenderer;
import net.minecraft.client.render.Tessellator;
import net.minecraft.client.render.VertexFormat;
import net.minecraft.client.render.VertexFormats;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.LightType;
import org.joml.Matrix4f;
import org.joml.Vector4f;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL14;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL21;
import org.lwjgl.opengl.GL30;

import java.util.Locale;

/**
 * El fuego de Ace en 3D de verdad: cada técnica es un volumen de fuego que la tarjeta gráfica recorre rayo a rayo
 * (raymarching), como el humo y el fuego de Blender. Un shader propio (assets/opeope/shaders/core/mera_volume.*) mira,
 * para cada píxel, cuánto fuego atraviesa la mirada dentro de una forma (bola, puño, columna, cerco, muro, chorro, cruz,
 * lanza, llama), con ruido que se mueve y sube como el fuego. Se para donde está el mundo (copia del depth buffer), así
 * que el fuego entra en los bloques sin cortes rectos.
 * <p>
 * Cada fotograma MeraFxClient pide sus volúmenes con {@link #add} y luego los dibuja con {@link #render}: una caja que
 * envuelve la forma (solo sus caras de detrás, para que funcione también con la cámara dentro) y el shader hace el resto.
 * <p>
 * Cada volumen además ilumina lo que tiene cerca (mera_light): el suelo, las paredes y la gente se ven naranjas, sobre
 * todo de noche y en cuevas. Es luz de pantalla: no pone bloques ni toca el mundo.
 */
public final class MeraVolumes {
	private MeraVolumes() {}

	public static final int FIREBALL = 0, FIST = 1, PILLAR = 2, RING = 3, WALL = 4, JET = 5, CROSS = 6, LANCE = 7, FLAME = 8;

	private static ShaderProgram program, lightProgram;
	private static boolean failed;
	/** Respaldo: copia de la profundidad del framebuffer principal con lo que trae Minecraft. */
	private static SimpleFramebuffer depthCopy;
	/** La copia propia de la profundidad del framebuffer que se esté dibujando (el de Minecraft o el de un shaderpack). */
	private static int grabTex = -1, grabFbo = -1, grabFormat = -1, grabW, grabH;
	private static boolean grabBroken;
	/** La textura de profundidad que lee el shader este fotograma y su tamaño. */
	private static int depthTex = -1, viewW, viewH;
	/** Con un shaderpack (Iris) el fuego se dibuja cuando el shaderpack ya terminó la imagen. */
	private static boolean pending;
	private static double camX0, camY0, camZ0;
	private static float time0;

	private static final int MAXV = 160;
	private static final double[] CX = new double[MAXV], CY = new double[MAXV], CZ = new double[MAXV];
	/** Ejes locales X, Y, Z (unitarios) en coordenadas del mundo. */
	private static final float[][] AX = new float[MAXV][9];
	private static final float[][] PRM = new float[MAXV][4];
	/** forma, semilla, intensidad, (libre). */
	private static final float[][] INFO = new float[MAXV][4];
	/** Caja local que envuelve la forma: min x,y,z y max x,y,z. */
	private static final float[][] BOX = new float[MAXV][6];
	private static final double[] DIST = new double[MAXV];
	private static final int[] ORDER = new int[MAXV];
	private static int n;

	private static final Matrix4f VP = new Matrix4f(), INV = new Matrix4f();

	public static void register() {
		CoreShaderRegistrationCallback.EVENT.register(ctx -> {
			ctx.register(OpeOpeMod.id("mera_volume"), VertexFormats.POSITION, p -> {
				program = p;
				failed = false;
				OpeOpeMod.LOG.info("Mera: shader de fuego volumétrico cargado");
			});
			ctx.register(OpeOpeMod.id("mera_light"), VertexFormats.POSITION, p -> {
				lightProgram = p;
				OpeOpeMod.LOG.info("Mera: shader de luz del fuego cargado");
			});
		});
	}

	/** ¿Se puede dibujar el fuego volumétrico? (si el shader no cargó, el fuego vuelve a las llamas sueltas). */
	public static boolean ready() {
		return program != null && !failed && enabled;
	}

	/** Para comparar en pruebas: apaga el fuego volumétrico. */
	public static boolean enabled = !Boolean.getBoolean("opeope.mera.novolume");

	public static void begin() {
		n = 0;
		nl = 0;
		if (diagArm) lastDiag = "sin volúmenes";
	}

	public static int count() {
		return n;
	}

	/**
	 * Un volumen con su eje local +Z hacia {@code dir} y +Y lo más hacia arriba posible.
	 * Las formas «de pie» (columna, cerco, muro, llama, bola) usan dir horizontal: su +Y queda vertical.
	 */
	public static void add(int shape, Vec3d c, Vec3d dir, float p0, float p1, float p2, float p3, int seed, float intensity) {
		if (n >= MAXV || intensity <= 0.003f) return;
		double dx = dir.x, dy = dir.y, dz = dir.z;
		double l = Math.sqrt(dx * dx + dy * dy + dz * dz);
		if (l < 1e-6) {
			dx = 0;
			dy = 0;
			dz = 1;
		} else {
			dx /= l;
			dy /= l;
			dz /= l;
		}
		// X = arriba × Z ; Y = Z × X
		double ux = 0, uy = 1, uz = 0;
		if (Math.abs(dy) > 0.985) {
			ux = 1;
			uy = 0;
		}
		double xx = uy * dz - uz * dy, xy = uz * dx - ux * dz, xz = ux * dy - uy * dx;
		double xl = Math.sqrt(xx * xx + xy * xy + xz * xz);
		xx /= xl;
		xy /= xl;
		xz /= xl;
		double yx = dy * xz - dz * xy, yy = dz * xx - dx * xz, yz = dx * xy - dy * xx;
		int i = n++;
		CX[i] = c.x;
		CY[i] = c.y;
		CZ[i] = c.z;
		float[] a = AX[i];
		a[0] = (float) xx;
		a[1] = (float) xy;
		a[2] = (float) xz;
		a[3] = (float) yx;
		a[4] = (float) yy;
		a[5] = (float) yz;
		a[6] = (float) dx;
		a[7] = (float) dy;
		a[8] = (float) dz;
		float[] p = PRM[i];
		p[0] = p0;
		p[1] = p1;
		p[2] = p2;
		p[3] = p3;
		float[] in = INFO[i];
		in[0] = shape;
		in[1] = (float) ((seed * 0.6180339887) % 1.0) * 97.0f;
		in[2] = intensity;
		in[3] = 0f;
		bounds(shape, p0, p1, p2, p3, BOX[i]);
		autoLight(i, shape, p0, p1, p2, intensity);
	}

	/**
	 * Para el último volumen añadido: a menos de {@code blocks} bloques de la cámara el fuego se aclara (el fuego que
	 * sale de la mano en primera persona no tapa la vista).
	 */
	public static void nearFade(float blocks) {
		if (n > 0) INFO[n - 1][3] = Math.max(0f, blocks);
	}

	/**
	 * El radio de la esfera que envuelve cada forma, con margen para las lenguas de fuego y el resplandor. Es el mismo
	 * cálculo que mfBound() del shader: la caja que se dibuja es el cubo que envuelve esa esfera.
	 */
	static float bound(int shape, float p0, float p1, float p2) {
		return switch (shape) {
			case FIREBALL -> Math.max(p0, 0.05f) * (1.75f + 0.6f * MathHelper.clamp(p2, 0f, 1f) + 0.45f * MathHelper.clamp(p1, 0f, 1f)) + 0.3f;
			case FIST -> Math.max(p0, 0.2f) * 0.95f + Math.max(p1, 0f) * 1.1f + 0.3f;
			case PILLAR -> {
				float r = Math.max(p0, 0.1f), h = Math.max(p1, 0.5f);
				yield (float) Math.hypot(h * 1.25f + r, r * 2.2f) + 0.5f;
			}
			case RING -> {
				float th = p2 > 0.01f ? p2 : 0.5f;
				yield (float) Math.hypot(Math.max(p0, 0.5f) + th + 1.5f, Math.max(p1, 0.3f) * 1.8f + 0.5f);
			}
			case WALL -> {
				float a = Math.max(p0, 0.5f) + 1.5f, b = Math.max(p1, 0.5f) * 1.5f + 0.5f, c = Math.max(p2, 0.1f) + 1.5f;
				yield (float) Math.sqrt(a * a + b * b + c * c);
			}
			case JET -> Math.max(p0, 1f) * 1.15f + Math.max(p1, 0.3f) * 1.6f + 0.3f;
			case CROSS -> {
				float th = p1 > 0.01f ? p1 : 0.3f;
				yield Math.max(p0, 0.3f) * 1.6f + th * 3f + 0.3f;
			}
			case LANCE -> Math.max(p0, 0.5f) * 1.15f + Math.max(p1, 0.05f) * 3f + 0.3f;
			default -> (float) Math.hypot(Math.max(p1, 0.1f) * 1.5f, Math.max(p0, 0.03f) * 2.5f) + 0.15f;
		};
	}

	static void bounds(int shape, float p0, float p1, float p2, float p3, float[] b) {
		float r = bound(shape, p0, p1, p2);
		box(b, -r, -r, -r, r, r, r);
	}

	private static void box(float[] b, float x0, float y0, float z0, float x1, float y1, float z1) {
		b[0] = x0;
		b[1] = y0;
		b[2] = z0;
		b[3] = x1;
		b[4] = y1;
		b[5] = z1;
	}

	// ------------------------------------------------------------------ la luz del fuego sobre el mundo

	/** Para comparar en pruebas: apaga la luz del fuego. */
	public static boolean lights = !Boolean.getBoolean("opeope.mera.nolight");
	/** Fuerza de la luz a oscuras (de día se queda en ~1/20). */
	private static final float LIGHT_BASE = 12f;
	private static final int MAXL = 24;
	/** Punto A y B de cada luz (mundo), eje del aro, y alcance, fuerza, semilla, radio del aro (0 = segmento), alto del aro. */
	private static final double[] LA = new double[MAXL * 3], LB = new double[MAXL * 3];
	private static final float[] LAXIS = new float[MAXL * 3], LINFO = new float[MAXL * 4], LRINGH = new float[MAXL];
	private static int nl;
	private static final BlockPos.Mutable LPOS = new BlockPos.Mutable();

	/** La luz que da cada forma: un segmento (o un aro) en sus coordenadas locales, con su alcance y su fuerza. */
	private static boolean quiet;

	/** Como {@link #add}, pero sin luz propia: para lo que Minecraft ya alumbra (un bloque de fuego). */
	public static void addQuiet(int shape, Vec3d c, Vec3d dir, float p0, float p1, float p2, float p3, int seed, float intensity) {
		quiet = true;
		try {
			add(shape, c, dir, p0, p1, p2, p3, seed, intensity);
		} finally {
			quiet = false;
		}
	}

	private static void autoLight(int i, int shape, float p0, float p1, float p2, float intensity) {
		if (!lights || quiet || nl >= MAXL) return;
		float ax = 0, ay = 0, az = 0, bx = 0, by = 0, bz = 0, ring = 0, ringH = 0, range = 0, power = 0;
		switch (shape) {
			case FIREBALL -> {
				float r = Math.max(p0, 0.05f), cool = MathHelper.clamp(p1, 0f, 1f), mv = MathHelper.clamp(p2, 0f, 1f);
				ay = by = cool * cool * r * 0.55f;
				bz = -0.6f * r * mv;
				range = r * 3f + 5f;
				// una bola pequeña (una bala del Higan) da poca luz; el sol del Entei, mucha
				power = MathHelper.clamp(0.25f + 0.5f * r, 0.25f, 1.3f) * (1f - 0.75f * cool);
			}
			case FIST -> {
				float sz = Math.max(p0, 0.2f);
				az = -Math.max(p1, 0f) * 0.6f;
				bz = 0.2f * sz;
				range = sz * 2.5f + 6f;
				power = 1f;
			}
			case PILLAR -> {
				float r = Math.max(p0, 0.1f), h = Math.max(p1, 0.5f);
				ay = 0.3f;
				by = h * 0.75f;
				range = r * 2f + 7f + h * 0.25f;
				power = 1.1f;
			}
			case RING -> {
				ring = Math.max(p0, 0.5f);
				ringH = Math.max(p1, 0.3f) * 0.6f;
				range = 5f + Math.max(p1, 0.3f) + ring * 0.3f;
				power = 0.9f;
			}
			case WALL -> {
				float hw = Math.max(p0, 0.5f), h = Math.max(p1, 0.5f);
				ax = -hw;
				bx = hw;
				ay = by = h * 0.35f;
				range = 5f + h * 0.8f;
				power = 1f;
			}
			case JET -> {
				float l = Math.max(p0, 1f), re = Math.max(p1, 0.3f);
				az = 0.4f;
				bz = l * 0.85f;
				range = re * 2.5f + 5f;
				power = MathHelper.clamp(re / 1.2f, 0.25f, 1f);
			}
			case CROSS -> {
				float arm = Math.max(p0, 0.3f);
				ay = -0.9f * arm;
				by = arm;
				range = arm * 2f + 4f;
				power = 0.8f;
			}
			case LANCE -> {
				float l = Math.max(p0, 0.5f);
				az = -0.6f * l;
				bz = 0.7f * l;
				range = 3.5f + l * 0.5f;
				power = 0.55f;
			}
			default -> {
				float r = Math.max(p0, 0.03f), h = Math.max(p1, 0.1f);
				ay = r;
				by = h * 0.6f;
				range = 2f + h * 2.5f;
				power = 0.45f;
			}
		}
		float[] a = AX[i];
		double wax = CX[i] + a[0] * ax + a[3] * ay + a[6] * az, way = CY[i] + a[1] * ax + a[4] * ay + a[7] * az, waz = CZ[i] + a[2] * ax + a[5] * ay + a[8] * az;
		double wbx = CX[i] + a[0] * bx + a[3] * by + a[6] * bz, wby = CY[i] + a[1] * bx + a[4] * by + a[7] * bz, wbz = CZ[i] + a[2] * bx + a[5] * by + a[8] * bz;
		float strength = power * Math.min(intensity, 1.3f) * LIGHT_BASE * darkness((wax + wbx) * 0.5, (way + wby) * 0.5 + ringH * 0.5, (waz + wbz) * 0.5);
		if (strength < 0.04f) return;
		int k = nl++;
		LA[k * 3] = wax;
		LA[k * 3 + 1] = way;
		LA[k * 3 + 2] = waz;
		LB[k * 3] = wbx;
		LB[k * 3 + 1] = wby;
		LB[k * 3 + 2] = wbz;
		LAXIS[k * 3] = a[3];
		LAXIS[k * 3 + 1] = a[4];
		LAXIS[k * 3 + 2] = a[5];
		LINFO[k * 4] = range;
		LINFO[k * 4 + 1] = strength;
		LINFO[k * 4 + 2] = INFO[i][1];
		LINFO[k * 4 + 3] = ring;
		LRINGH[k] = ringH;
	}

	/** Lo oscuro que está alrededor (0,06 a pleno sol … 1 en una cueva): el fuego ilumina más cuanto más oscuro. */
	private static float darkness(double x, double y, double z) {
		ClientWorld w = MinecraftClient.getInstance().world;
		if (w == null) return 1f;
		// se mira alrededor, no solo el punto: un fuego pegado a una pared cae dentro del bloque, donde no entra
		// la luz, y si no se mirara al lado se encendería como si fuera de noche (pintaba el suelo de amarillo)
		float lit = light(w, x, y, z);
		if (lit < 0.999f) {
			for (int i = 0; i < 6 && lit < 0.999f; i++) {
				double dx = i == 0 ? 1 : i == 1 ? -1 : 0, dy = i == 4 ? 1 : i == 5 ? 2 : 0, dz = i == 2 ? 1 : i == 3 ? -1 : 0;
				lit = Math.max(lit, light(w, x + dx, y + dy, z + dz));
			}
		}
		float d = 1f - MathHelper.clamp(lit, 0f, 1f);
		return 0.06f + 0.94f * d * d;
	}

	private static float light(ClientWorld w, double x, double y, double z) {
		if (w == null) return 1f;
		LPOS.set(x, y, z);
		float sky = w.getLightLevel(LightType.SKY, LPOS) / 15f * w.getSkyBrightness(1f);
		float blk = w.getLightLevel(LightType.BLOCK, LPOS) / 15f;
		return Math.max(sky, blk);
	}

	public static int lightCount() {
		return nl;
	}

	/** La luz que hay en un punto (0 a oscuras, 1 a pleno sol): el humo se ve con ella, no brilla solo. */
	public static float skyAt(double x, double y, double z) {
		return light(MinecraftClient.getInstance().world, x, y, z);
	}

	/** Las luces van al shader como arrays, directamente con OpenGL (Minecraft no maneja arrays de uniforms). */
	private static final java.nio.FloatBuffer LBUF = org.lwjgl.BufferUtils.createFloatBuffer(MAXL * 4);
	private static int lightRef = -1, locCount, locA, locB, locX, locSeed;

	/**
	 * La luz de todos los fuegos sobre lo que ya está dibujado, en un solo dibujo: multiplica el mundo por un factor que
	 * lo aclara con el color del fuego. Las luces suman entre ellas (no se multiplican, que pintaba el suelo de
	 * amarillo). Cada dibujo aclara como mucho ×2: si hay mucha luz (de noche) se repite para llegar a ×4 o ×8.
	 */
	private static void drawLights(double camX, double camY, double camZ) {
		ShaderProgram lp = lightProgram;
		if (lp == null || nl == 0 || !lights) return;
		int prog = lp.getGlRef();
		if (prog != lightRef) {
			lightRef = prog;
			locCount = GL20.glGetUniformLocation(prog, "LightCount");
			locA = GL20.glGetUniformLocation(prog, "LightsA");
			locB = GL20.glGetUniformLocation(prog, "LightsB");
			locX = GL20.glGetUniformLocation(prog, "LightsX");
			locSeed = GL20.glGetUniformLocation(prog, "LightsSeed");
		}
		if (locCount < 0 || locA < 0 || locB < 0 || locX < 0) return;
		// la caja que envuelve todas las luces (relativa a la cámara) y lo más que puede aclarar su suma
		float x0 = Float.MAX_VALUE, y0 = Float.MAX_VALUE, z0 = Float.MAX_VALUE;
		float x1 = -Float.MAX_VALUE, y1 = -Float.MAX_VALUE, z1 = -Float.MAX_VALUE;
		double e2 = 0;
		for (int k = 0; k < nl; k++) {
			float ax = (float) (LA[k * 3] - camX), ay = (float) (LA[k * 3 + 1] - camY), az = (float) (LA[k * 3 + 2] - camZ);
			float bx = (float) (LB[k * 3] - camX), by = (float) (LB[k * 3 + 1] - camY), bz = (float) (LB[k * 3 + 2] - camZ);
			float range = LINFO[k * 4], ring = LINFO[k * 4 + 3];
			if (ring > 0) {
				float e = ring + LRINGH[k] + range;
				x0 = Math.min(x0, ax - e); y0 = Math.min(y0, ay - e); z0 = Math.min(z0, az - e);
				x1 = Math.max(x1, ax + e); y1 = Math.max(y1, ay + e); z1 = Math.max(z1, az + e);
			} else {
				x0 = Math.min(x0, Math.min(ax, bx) - range); y0 = Math.min(y0, Math.min(ay, by) - range); z0 = Math.min(z0, Math.min(az, bz) - range);
				x1 = Math.max(x1, Math.max(ax, bx) + range); y1 = Math.max(y1, Math.max(ay, by) + range); z1 = Math.max(z1, Math.max(az, bz) + range);
			}
			e2 += (double) LINFO[k * 4 + 1] * LINFO[k * 4 + 1];
		}
		GlStateManager._glUseProgram(prog);
		GL20.glUniform1i(locCount, nl);
		LBUF.clear();
		for (int k = 0; k < nl; k++) {
			LBUF.put((float) (LA[k * 3] - camX)).put((float) (LA[k * 3 + 1] - camY)).put((float) (LA[k * 3 + 2] - camZ)).put(LINFO[k * 4]);
		}
		GL20.glUniform4fv(locA, LBUF.flip());
		LBUF.clear();
		for (int k = 0; k < nl; k++) {
			if (LINFO[k * 4 + 3] > 0) LBUF.put(LRINGH[k]).put(0f).put(0f);
			else LBUF.put((float) (LB[k * 3] - camX)).put((float) (LB[k * 3 + 1] - camY)).put((float) (LB[k * 3 + 2] - camZ));
			LBUF.put(LINFO[k * 4 + 1]);
		}
		GL20.glUniform4fv(locB, LBUF.flip());
		LBUF.clear();
		for (int k = 0; k < nl; k++) LBUF.put(LAXIS[k * 3]).put(LAXIS[k * 3 + 1]).put(LAXIS[k * 3 + 2]).put(LINFO[k * 4 + 3]);
		GL20.glUniform4fv(locX, LBUF.flip());
		if (locSeed >= 0) {
			LBUF.clear();
			for (int k = 0; k < nl; k++) LBUF.put(LINFO[k * 4 + 2]);
			GL20.glUniform1fv(locSeed, LBUF.flip());
		}
		GlStateManager._glUseProgram(0);

		RenderSystem.setShader(() -> lp);
		RenderSystem.setShaderTexture(0, depthTex);
		// color = mundo × 2·salida (la salida va de 0,5 a 1): la luz aclara lo que ya está dibujado con su color
		RenderSystem.blendFuncSeparate(GlStateManager.SrcFactor.DST_COLOR, GlStateManager.DstFactor.SRC_COLOR,
			GlStateManager.SrcFactor.ZERO, GlStateManager.DstFactor.ONE);
		mat(lp, "ViewProj", VP);
		mat(lp, "InvViewProj", INV);
		vec(lp, "ViewSize", viewW, viewH);
		vec(lp, "VolTime", time0);
		vec(lp, "LightColor", 1f, 0.42f, 0.12f);
		double e = Math.sqrt(e2);
		double most = 1 + e / (1 + 0.12 * e);
		int passes = most <= 2.02 ? 1 : most <= 4.04 ? 2 : 3;
		// y si hay bastante luz, el resplandor que se suma (de noche)
		boolean glow = e > 0.6;
		for (int p = 0; p < passes + (glow ? 1 : 0); p++) {
			if (p == passes) {
				RenderSystem.blendFuncSeparate(GlStateManager.SrcFactor.ONE, GlStateManager.DstFactor.ONE,
					GlStateManager.SrcFactor.ZERO, GlStateManager.DstFactor.ONE);
			}
			vec(lp, "LightPass", p == passes ? 3 : p);
			BufferBuilder bb = Tessellator.getInstance().begin(VertexFormat.DrawMode.QUADS, VertexFormats.POSITION);
			// solo las caras «de detrás» de la caja (la cámara está en el origen): cada píxel, una sola vez
			if (0 < x1) quad(bb, x1, y0, z0, x1, y1, z0, x1, y1, z1, x1, y0, z1);
			if (0 > x0) quad(bb, x0, y0, z0, x0, y0, z1, x0, y1, z1, x0, y1, z0);
			if (0 < y1) quad(bb, x0, y1, z0, x0, y1, z1, x1, y1, z1, x1, y1, z0);
			if (0 > y0) quad(bb, x0, y0, z0, x1, y0, z0, x1, y0, z1, x0, y0, z1);
			if (0 < z1) quad(bb, x0, y0, z1, x1, y0, z1, x1, y1, z1, x0, y1, z1);
			if (0 > z0) quad(bb, x0, y0, z0, x0, y1, z0, x1, y1, z0, x1, y0, z0);
			var built = bb.endNullable();
			if (built != null) BufferRenderer.drawWithGlobalProgram(built);
		}
	}

	private static void quad(BufferBuilder bb, float ax, float ay, float az, float bx, float by, float bz,
							 float cx, float cy, float cz, float dx, float dy, float dz) {
		bb.vertex(ax, ay, az);
		bb.vertex(bx, by, bz);
		bb.vertex(cx, cy, cz);
		bb.vertex(dx, dy, dz);
	}

	// ------------------------------------------------------------------ la profundidad del mundo

	/**
	 * Copia la profundidad del framebuffer que se está dibujando ahora (el principal de Minecraft o el del shaderpack)
	 * a una textura propia del mismo formato, para que el shader sepa dónde paran los rayos. Si algo falla, usa la copia
	 * del framebuffer principal que trae Minecraft.
	 */
	private static boolean grabDepth() {
		int src = GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);
		if (src != 0 && !grabBroken) {
			try {
				if (grabFrom(src)) return true;
			} catch (RuntimeException e) {
				OpeOpeMod.LOG.warn("Mera: no pude leer la profundidad del framebuffer {}; uso la del principal", src, e);
			}
			grabBroken = true;
		}
		return grabMain();
	}

	private static boolean grabFrom(int src) {
		int prevRead = GL11.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING);
		int type = GL30.glGetFramebufferAttachmentParameteri(GL30.GL_DRAW_FRAMEBUFFER, GL30.GL_DEPTH_ATTACHMENT, GL30.GL_FRAMEBUFFER_ATTACHMENT_OBJECT_TYPE);
		if (type != GL11.GL_TEXTURE && type != GL30.GL_RENDERBUFFER) return false;
		int name = GL30.glGetFramebufferAttachmentParameteri(GL30.GL_DRAW_FRAMEBUFFER, GL30.GL_DEPTH_ATTACHMENT, GL30.GL_FRAMEBUFFER_ATTACHMENT_OBJECT_NAME);
		int fmt, w, h;
		if (type == GL11.GL_TEXTURE) {
			int prevTex = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
			GlStateManager._bindTexture(name);
			fmt = GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D, 0, GL11.GL_TEXTURE_INTERNAL_FORMAT);
			w = GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D, 0, GL11.GL_TEXTURE_WIDTH);
			h = GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D, 0, GL11.GL_TEXTURE_HEIGHT);
			GlStateManager._bindTexture(prevTex);
		} else {
			int prevRb = GL11.glGetInteger(GL30.GL_RENDERBUFFER_BINDING);
			GL30.glBindRenderbuffer(GL30.GL_RENDERBUFFER, name);
			fmt = GL30.glGetRenderbufferParameteri(GL30.GL_RENDERBUFFER, GL30.GL_RENDERBUFFER_INTERNAL_FORMAT);
			w = GL30.glGetRenderbufferParameteri(GL30.GL_RENDERBUFFER, GL30.GL_RENDERBUFFER_WIDTH);
			h = GL30.glGetRenderbufferParameteri(GL30.GL_RENDERBUFFER, GL30.GL_RENDERBUFFER_HEIGHT);
			GL30.glBindRenderbuffer(GL30.GL_RENDERBUFFER, prevRb);
		}
		if (w <= 0 || h <= 0) return false;
		if (grabTex < 0 || fmt != grabFormat || w != grabW || h != grabH) {
			if (!allocate(fmt, w, h)) {
				GlStateManager._glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, prevRead);
				GlStateManager._glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, src);
				return false;
			}
		}
		for (int k = 0; k < 8 && GL11.glGetError() != GL11.GL_NO_ERROR; k++) {
			// errores viejos de otros: que no se confundan con el de la copia
		}
		GlStateManager._glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, src);
		GlStateManager._glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, grabFbo);
		GL30.glBlitFramebuffer(0, 0, w, h, 0, 0, w, h, GL11.GL_DEPTH_BUFFER_BIT, GL11.GL_NEAREST);
		int err = GL11.glGetError();
		GlStateManager._glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, prevRead);
		GlStateManager._glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, src);
		if (err != GL11.GL_NO_ERROR) {
			OpeOpeMod.LOG.warn("Mera: la copia de profundidad dio el error GL {} (formato {}); uso la del principal", err, fmt);
			return false;
		}
		depthTex = grabTex;
		viewW = w;
		viewH = h;
		if (DIAG) diagPath = "copia fbo=" + src + (type == GL11.GL_TEXTURE ? " tex=" : " rb=") + name + " fmt=0x" + Integer.toHexString(fmt) + " " + w + "x" + h;
		return true;
	}

	/** Una textura de profundidad con el mismo formato que la del framebuffer de origen, y su framebuffer. */
	private static boolean allocate(int fmt, int w, int h) {
		boolean stencil = fmt == GL30.GL_DEPTH24_STENCIL8 || fmt == GL30.GL_DEPTH32F_STENCIL8 || fmt == GL30.GL_DEPTH_STENCIL;
		int format = stencil ? GL30.GL_DEPTH_STENCIL : GL11.GL_DEPTH_COMPONENT;
		int dtype = !stencil ? GL11.GL_FLOAT : fmt == GL30.GL_DEPTH32F_STENCIL8 ? GL30.GL_FLOAT_32_UNSIGNED_INT_24_8_REV : GL30.GL_UNSIGNED_INT_24_8;
		int prevTex = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
		if (grabTex < 0) grabTex = GlStateManager._genTexture();
		GlStateManager._bindTexture(grabTex);
		GlStateManager._texParameter(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
		GlStateManager._texParameter(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
		GlStateManager._texParameter(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
		GlStateManager._texParameter(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);
		GlStateManager._texParameter(GL11.GL_TEXTURE_2D, GL14.GL_TEXTURE_COMPARE_MODE, GL11.GL_NONE);
		GlStateManager._texImage2D(GL11.GL_TEXTURE_2D, 0, fmt, w, h, 0, format, dtype, null);
		GlStateManager._bindTexture(prevTex);
		if (grabFbo < 0) grabFbo = GlStateManager.glGenFramebuffers();
		GlStateManager._glBindFramebuffer(GL30.GL_FRAMEBUFFER, grabFbo);
		GlStateManager._glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_DEPTH_STENCIL_ATTACHMENT, GL11.GL_TEXTURE_2D, 0, 0);
		GlStateManager._glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, stencil ? GL30.GL_DEPTH_STENCIL_ATTACHMENT : GL30.GL_DEPTH_ATTACHMENT,
			GL11.GL_TEXTURE_2D, grabTex, 0);
		GL11.glDrawBuffer(GL11.GL_NONE);
		GL11.glReadBuffer(GL11.GL_NONE);
		int status = GlStateManager.glCheckFramebufferStatus(GL30.GL_FRAMEBUFFER);
		if (status != GL30.GL_FRAMEBUFFER_COMPLETE) {
			OpeOpeMod.LOG.warn("Mera: el framebuffer de profundidad no quedó completo ({}), formato {}", status, fmt);
			grabFormat = -1;
			return false;
		}
		grabFormat = fmt;
		grabW = w;
		grabH = h;
		return true;
	}

	/** El respaldo de siempre: la profundidad del framebuffer principal, copiada con lo que trae Minecraft. */
	private static boolean grabMain() {
		MinecraftClient mc = MinecraftClient.getInstance();
		Framebuffer main = mc.getFramebuffer();
		int w = main.textureWidth, h = main.textureHeight;
		if (w <= 0 || h <= 0) return false;
		int prevDraw = GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);
		int prevRead = GL11.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING);
		try {
			if (depthCopy == null) depthCopy = new SimpleFramebuffer(w, h, true, MinecraftClient.IS_SYSTEM_MAC);
			else if (depthCopy.textureWidth != w || depthCopy.textureHeight != h) depthCopy.resize(w, h, MinecraftClient.IS_SYSTEM_MAC);
			depthCopy.copyDepthFrom(main);
		} catch (RuntimeException e) {
			OpeOpeMod.LOG.error("Mera: no se pudo copiar la profundidad; vuelvo a las llamas sueltas", e);
			failed = true;
			return false;
		} finally {
			GlStateManager._glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, prevRead);
			GlStateManager._glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, prevDraw);
			RenderSystem.viewport(0, 0, w, h);
		}
		depthTex = depthCopy.getDepthAttachment();
		viewW = w;
		viewH = h;
		if (DIAG) diagPath = "principal fbo=" + main.fbo + " copia=" + depthCopy.fbo + " " + w + "x" + h;
		return true;
	}

	// ------------------------------------------------------------------ ¿hay un shaderpack?

	private static java.lang.reflect.Method irisInUse;
	private static Object irisApi;
	private static boolean irisChecked;

	/** Iris con un shaderpack activo (Photon, Complementary…): se mira por reflexión, sin depender de Iris. */
	static boolean shaderPackInUse() {
		if (!irisChecked) {
			irisChecked = true;
			try {
				Class<?> c = Class.forName("net.irisshaders.iris.api.v0.IrisApi");
				irisApi = c.getMethod("getInstance").invoke(null);
				irisInUse = c.getMethod("isShaderPackInUse");
			} catch (Throwable t) {
				irisApi = null;
				irisInUse = null;
			}
		}
		if (irisInUse == null) return false;
		try {
			return (Boolean) irisInUse.invoke(irisApi);
		} catch (Throwable t) {
			return false;
		}
	}

	// ------------------------------------------------------------------ dibujo

	/**
	 * Después de lo translúcido (WorldRenderEvents.AFTER_TRANSLUCENT): guarda la profundidad del mundo y las matrices.
	 * Sin shaderpack dibuja ya; con un shaderpack (Iris) espera a {@link #afterWorld()}, cuando la imagen está terminada,
	 * para que el fuego no se meta en los buffers internos del shaderpack.
	 */
	public static void render(WorldRenderContext ctx, double camX, double camY, double camZ, float time) {
		pending = false;
		if (n == 0 || !ready()) return;
		if (!grabDepth()) return;
		VP.set(ctx.projectionMatrix()).mul(ctx.positionMatrix());
		INV.set(VP).invert();
		camX0 = camX;
		camY0 = camY;
		camZ0 = camZ;
		time0 = time;
		if (shaderPackInUse()) {
			pending = true;
			return;
		}
		draw();
	}

	/** Justo después de dibujar el mundo (GameRendererMixin): con un shaderpack, aquí ya está la imagen final. */
	public static void afterWorld() {
		if (!pending) return;
		pending = false;
		if (n == 0 || !ready()) return;
		Framebuffer main = MinecraftClient.getInstance().getFramebuffer();
		main.beginWrite(true);
		draw();
	}

	private static void draw() {
		double camX = camX0, camY = camY0, camZ = camZ0;
		int w = viewW, h = viewH;

		// de lejos a cerca (mezcla con alfa premultiplicado)
		for (int i = 0; i < n; i++) {
			double dx = CX[i] - camX, dy = CY[i] - camY, dz = CZ[i] - camZ;
			DIST[i] = dx * dx + dy * dy + dz * dz;
			ORDER[i] = i;
		}
		for (int a = 1; a < n; a++) {
			int v = ORDER[a];
			int b = a - 1;
			while (b >= 0 && DIST[ORDER[b]] < DIST[v]) {
				ORDER[b + 1] = ORDER[b];
				b--;
			}
			ORDER[b + 1] = v;
		}

		ShaderProgram sp = program;
		RenderSystem.enableBlend();
		RenderSystem.disableDepthTest();
		RenderSystem.depthMask(false);
		RenderSystem.disableCull();
		boolean diag = DIAG && diagArm;
		try {
			if (diag) diagBefore(camX, camY, camZ);
			// primero la luz sobre el mundo, luego el fuego encima
			drawLights(camX, camY, camZ);
			if (diag) diagColors(DC1);
			RenderSystem.setShader(() -> sp);
			RenderSystem.setShaderTexture(0, depthTex);
			RenderSystem.blendFuncSeparate(GlStateManager.SrcFactor.ONE, GlStateManager.DstFactor.ONE_MINUS_SRC_ALPHA,
				GlStateManager.SrcFactor.ZERO, GlStateManager.DstFactor.ONE);
			mat(sp, "ViewProj", VP);
			mat(sp, "InvViewProj", INV);
			vec(sp, "ViewSize", w, h);
			vec(sp, "VolTime", time0);
			// la luz que hay alrededor de la cámara: el humo no brilla solo, de noche casi no se ve
			vec(sp, "VolSky", light(MinecraftClient.getInstance().world, camX, camY + 1, camZ));
			for (int o = 0; o < n; o++) drawOne(sp, ORDER[o], camX, camY, camZ);
			if (diag) diagAfter(sp, camX, camY, camZ);
		} catch (RuntimeException e) {
			if (!diag) throw e;
			lastDiag = "error en el diagnóstico: " + e;
		} finally {
			RenderSystem.enableDepthTest();
			RenderSystem.depthMask(true);
			RenderSystem.enableCull();
			RenderSystem.defaultBlendFunc();
			RenderSystem.disableBlend();
		}
	}

	private static void volumeUniforms(ShaderProgram sp, int i, double camX, double camY, double camZ) {
		float cx = (float) (CX[i] - camX), cy = (float) (CY[i] - camY), cz = (float) (CZ[i] - camZ);
		float[] a = AX[i], b = BOX[i], p = PRM[i], in = INFO[i];
		vec(sp, "VolCenter", cx, cy, cz);
		vec(sp, "VolX", a[0], a[1], a[2]);
		vec(sp, "VolY", a[3], a[4], a[5]);
		vec(sp, "VolZ", a[6], a[7], a[8]);
		vec(sp, "VolMin", b[0], b[1], b[2]);
		vec(sp, "VolMax", b[3], b[4], b[5]);
		vec4(sp, "VolPrm", p[0], p[1], p[2], p[3]);
		vec4(sp, "VolInfo", in[0], in[1], in[2], in[3]);
	}

	private static void drawOne(ShaderProgram sp, int i, double camX, double camY, double camZ) {
		float cx = (float) (CX[i] - camX), cy = (float) (CY[i] - camY), cz = (float) (CZ[i] - camZ);
		float[] a = AX[i], b = BOX[i];
		volumeUniforms(sp, i, camX, camY, camZ);

		// la cámara en coordenadas locales: decide qué caras de la caja son «de detrás»
		float lx = -(cx * a[0] + cy * a[1] + cz * a[2]);
		float ly = -(cx * a[3] + cy * a[4] + cz * a[5]);
		float lz = -(cx * a[6] + cy * a[7] + cz * a[8]);
		BufferBuilder bb = Tessellator.getInstance().begin(VertexFormat.DrawMode.QUADS, VertexFormats.POSITION);
		// cara +X / −X
		if (lx < b[3]) face(bb, i, cx, cy, cz, 0, true);
		if (lx > b[0]) face(bb, i, cx, cy, cz, 0, false);
		if (ly < b[4]) face(bb, i, cx, cy, cz, 1, true);
		if (ly > b[1]) face(bb, i, cx, cy, cz, 1, false);
		if (lz < b[5]) face(bb, i, cx, cy, cz, 2, true);
		if (lz > b[2]) face(bb, i, cx, cy, cz, 2, false);
		var built = bb.endNullable();
		if (built != null) BufferRenderer.drawWithGlobalProgram(built);
	}

	/** Una cara de la caja: eje 0/1/2, lado max (true) o min (false). */
	private static void face(BufferBuilder bb, int i, float cx, float cy, float cz, int axis, boolean max) {
		float[] b = BOX[i];
		float fixed = max ? b[3 + axis] : b[axis];
		int u = (axis + 1) % 3, v = (axis + 2) % 3;
		float[][] q = {{b[u], b[v]}, {b[3 + u], b[v]}, {b[3 + u], b[3 + v]}, {b[u], b[3 + v]}};
		float[] l = new float[3];
		for (float[] c : q) {
			l[axis] = fixed;
			l[u] = c[0];
			l[v] = c[1];
			vertex(bb, i, cx, cy, cz, l[0], l[1], l[2]);
		}
	}

	private static void vertex(BufferBuilder bb, int i, float cx, float cy, float cz, float x, float y, float z) {
		float[] a = AX[i];
		bb.vertex(cx + a[0] * x + a[3] * y + a[6] * z, cy + a[1] * x + a[4] * y + a[7] * z, cz + a[2] * x + a[5] * y + a[8] * z);
	}

	private static void mat(ShaderProgram sp, String name, Matrix4f m) {
		GlUniform u = sp.getUniform(name);
		if (u != null) u.set(m);
	}

	private static void vec(ShaderProgram sp, String name, float x) {
		GlUniform u = sp.getUniform(name);
		if (u != null) u.set(x);
	}

	private static void vec(ShaderProgram sp, String name, float x, float y) {
		GlUniform u = sp.getUniform(name);
		if (u != null) u.set(x, y);
	}

	private static void vec(ShaderProgram sp, String name, float x, float y, float z) {
		GlUniform u = sp.getUniform(name);
		if (u != null) u.set(x, y, z);
	}

	private static void vec4(ShaderProgram sp, String name, float x, float y, float z, float w) {
		GlUniform u = sp.getUniform(name);
		if (u != null) u.set(x, y, z, w);
	}

	public static void clear() {
		n = 0;
		nl = 0;
	}

	// ------------------------------------------------------------------ diagnóstico (galería de la CI)

	/** En la galería de pruebas: qué pasó en el fotograma con fuego antes de cada captura (estado de GL y píxeles). */
	static final boolean DIAG = Boolean.getBoolean("opeope.gallery.mera") || Boolean.getBoolean("opeope.mera.diag");
	/** La galería lo pide mientras espera el fotograma de una captura. */
	public static boolean diagArm;
	private static String lastDiag = "sin fuego 3D";
	private static String diagPath = "";
	private static int diagSrc, diagFrame;
	private static int probeFbo = -1, probeTex = -1, probeW, probeH;
	private static final int MAXPX = 6;
	private static final int[] PXX = new int[MAXPX], PXY = new int[MAXPX];
	private static int npx;
	private static final float[][] DC0 = new float[MAXPX][4], DC1 = new float[MAXPX][4], DC2 = new float[MAXPX][4], DPR = new float[MAXPX][4];
	private static final float[] DDC = new float[MAXPX], DDM = new float[MAXPX];
	private static String glName;

	/** Lo que vio el último fotograma con fuego (para el registro de la galería). */
	public static String diag() {
		return lastDiag;
	}

	/** Los píxeles que se miran (el centro de la pantalla y el centro de los primeros volúmenes) y su color de antes. */
	private static void diagBefore(double camX, double camY, double camZ) {
		diagSrc = GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);
		npx = 0;
		addPx(viewW / 2, viewH / 2);
		for (int i = 0; i < n && npx < MAXPX; i++) {
			Vector4f v = new Vector4f((float) (CX[i] - camX), (float) (CY[i] - camY), (float) (CZ[i] - camZ), 1f).mul(VP);
			if (v.w <= 1e-3f) continue;
			addPx((int) ((v.x / v.w * 0.5f + 0.5f) * viewW), (int) ((v.y / v.w * 0.5f + 0.5f) * viewH));
		}
		diagColors(DC0);
	}

	private static void addPx(int x, int y) {
		PXX[npx] = MathHelper.clamp(x, 0, Math.max(0, viewW - 1));
		PXY[npx] = MathHelper.clamp(y, 0, Math.max(0, viewH - 1));
		npx++;
	}

	private static void diagColors(float[][] out) {
		int prevRead = GL11.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING);
		int pbo = GL11.glGetInteger(GL21.GL_PIXEL_PACK_BUFFER_BINDING);
		if (pbo != 0) GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, 0);
		GlStateManager._glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, diagSrc);
		for (int k = 0; k < npx; k++) GL11.glReadPixels(PXX[k], PXY[k], 1, 1, GL11.GL_RGBA, GL11.GL_FLOAT, out[k]);
		GlStateManager._glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, prevRead);
		if (pbo != 0) GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, pbo);
	}

	private static void diagDepths(int fbo, float[] out) {
		int prevRead = GL11.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING);
		int pbo = GL11.glGetInteger(GL21.GL_PIXEL_PACK_BUFFER_BINDING);
		if (pbo != 0) GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, 0);
		GlStateManager._glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, fbo);
		float[] d = new float[1];
		for (int k = 0; k < npx; k++) {
			d[0] = -9f;
			GL11.glReadPixels(PXX[k], PXY[k], 1, 1, GL11.GL_DEPTH_COMPONENT, GL11.GL_FLOAT, d);
			out[k] = d[0];
		}
		GlStateManager._glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, prevRead);
		if (pbo != 0) GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, pbo);
	}

	/** Después de dibujar: el estado de GL, los colores, la profundidad copiada y lo que calcula el shader en cada píxel. */
	private static void diagAfter(ShaderProgram sp, double camX, double camY, double camZ) {
		int err = GL11.glGetError();
		boolean blend = GL11.glIsEnabled(GL11.GL_BLEND), depthTest = GL11.glIsEnabled(GL11.GL_DEPTH_TEST);
		boolean cull = GL11.glIsEnabled(GL11.GL_CULL_FACE), scissor = GL11.glIsEnabled(GL11.GL_SCISSOR_TEST);
		int bs = GL11.glGetInteger(GL14.GL_BLEND_SRC_RGB), bd = GL11.glGetInteger(GL14.GL_BLEND_DST_RGB);
		int[] vp = new int[4];
		GL11.glGetIntegerv(GL11.GL_VIEWPORT, vp);
		diagColors(DC2);
		MinecraftClient mc = MinecraftClient.getInstance();
		Framebuffer main = mc.getFramebuffer();
		int copyFbo = depthTex == grabTex ? grabFbo : depthCopy != null ? depthCopy.fbo : -1;
		if (copyFbo >= 0) diagDepths(copyFbo, DDC);
		diagDepths(diagSrc, DDM);
		// la sonda: el mismo shader en modo diagnóstico, a pantalla completa, en un framebuffer aparte
		int probed = -1;
		for (int i = 0; i < n; i++) {
			if (INFO[i][2] > 0.05f) {
				probed = i;
				break;
			}
		}
		if (probed >= 0) diagProbe(sp, probed, camX, camY, camZ);
		if (glName == null) glName = GL11.glGetString(GL11.GL_RENDERER) + " | " + GL11.glGetString(GL11.GL_VERSION);

		StringBuilder s = new StringBuilder(1024);
		s.append("fotograma=").append(++diagFrame).append(" volumenes=").append(n).append(" luces=").append(nl)
			.append(" dibujo=").append(diagSrc).append(" principal=").append(main.fbo).append(' ').append(main.textureWidth).append('x').append(main.textureHeight)
			.append(" | ").append(diagPath).append(" | depthTex=").append(depthTex)
			.append(" vista=").append(viewW).append('x').append(viewH).append(" viewport=").append(vp[0]).append(',').append(vp[1]).append(',').append(vp[2]).append(',').append(vp[3])
			.append(" blend=").append(blend).append(" (").append(bs).append(',').append(bd).append(") depthTest=").append(depthTest)
			.append(" cull=").append(cull).append(" scissor=").append(scissor).append(" glError=").append(err)
			.append(" gl=").append(glName);
		s.append("\n  VP=").append(mrow(VP, 0)).append(' ').append(mrow(VP, 1)).append(' ').append(mrow(VP, 2)).append(' ').append(mrow(VP, 3));
		for (int k = 0; k < npx; k++) {
			s.append("\n  px").append(k).append('=').append(PXX[k]).append(',').append(PXY[k])
				.append(" antes=").append(rgb(DC0[k])).append(" luz=").append(rgb(DC1[k])).append(" fuego=").append(rgb(DC2[k]))
				.append(String.format(Locale.ROOT, " prof(copia)=%.6f prof(mundo)=%.6f", DDC[k], DDM[k]));
			if (probed >= 0) s.append(String.format(Locale.ROOT, " sonda[v%d]: prof=%.6f tMundo=%.3f tn=%.3f tf=%.3f", probed, DPR[k][0], DPR[k][1], DPR[k][2], DPR[k][3]));
		}
		for (int i = 0; i < Math.min(n, 8); i++) {
			float cx = (float) (CX[i] - camX), cy = (float) (CY[i] - camY), cz = (float) (CZ[i] - camZ);
			float[] a = AX[i];
			float lx = -(cx * a[0] + cy * a[1] + cz * a[2]), ly = -(cx * a[3] + cy * a[4] + cz * a[5]), lz = -(cx * a[6] + cy * a[7] + cz * a[8]);
			s.append(String.format(Locale.ROOT, "\n  v%d forma=%d centro=(%.2f,%.2f,%.2f) Z=(%.2f,%.2f,%.2f) prm=(%.3f,%.3f,%.3f,%.3f) info=(%.0f,%.2f,%.3f,%.2f) radio=%.2f camLocal=(%.2f,%.2f,%.2f) dentro=%b",
				i, (int) INFO[i][0], cx, cy, cz, a[6], a[7], a[8], PRM[i][0], PRM[i][1], PRM[i][2], PRM[i][3],
				INFO[i][0], INFO[i][1], INFO[i][2], INFO[i][3], BOX[i][3], lx, ly, lz, lx * lx + ly * ly + lz * lz < BOX[i][3] * BOX[i][3]));
		}
		lastDiag = s.toString();
		diagArm = false;
	}

	private static void diagProbe(ShaderProgram sp, int i, double camX, double camY, double camZ) {
		int w = viewW, h = viewH;
		int prevDraw = GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING), prevRead = GL11.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING);
		if (probeTex < 0) probeTex = GlStateManager._genTexture();
		if (probeFbo < 0) probeFbo = GlStateManager.glGenFramebuffers();
		if (probeW != w || probeH != h) {
			int prevTex = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
			GlStateManager._bindTexture(probeTex);
			GlStateManager._texParameter(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
			GlStateManager._texParameter(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
			GlStateManager._texImage2D(GL11.GL_TEXTURE_2D, 0, GL30.GL_RGBA32F, w, h, 0, GL11.GL_RGBA, GL11.GL_FLOAT, null);
			GlStateManager._bindTexture(prevTex);
			GlStateManager._glBindFramebuffer(GL30.GL_FRAMEBUFFER, probeFbo);
			GlStateManager._glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0, GL11.GL_TEXTURE_2D, probeTex, 0);
			probeW = w;
			probeH = h;
		}
		GlStateManager._glBindFramebuffer(GL30.GL_FRAMEBUFFER, probeFbo);
		float[] cc = new float[4];
		GL11.glGetFloatv(GL11.GL_COLOR_CLEAR_VALUE, cc);
		GL11.glClearColor(-2f, -2f, -2f, -2f);
		GL11.glClear(GL11.GL_COLOR_BUFFER_BIT);
		GL11.glClearColor(cc[0], cc[1], cc[2], cc[3]);
		RenderSystem.disableBlend();
		RenderSystem.setShader(() -> sp);
		RenderSystem.setShaderTexture(0, depthTex);
		mat(sp, "ViewProj", new Matrix4f());
		volumeUniforms(sp, i, camX, camY, camZ);
		vec4(sp, "VolInfo", INFO[i][0], INFO[i][1], INFO[i][2], -1f);
		BufferBuilder bb = Tessellator.getInstance().begin(VertexFormat.DrawMode.QUADS, VertexFormats.POSITION);
		bb.vertex(-1f, -1f, 0f);
		bb.vertex(1f, -1f, 0f);
		bb.vertex(1f, 1f, 0f);
		bb.vertex(-1f, 1f, 0f);
		BufferRenderer.drawWithGlobalProgram(bb.end());
		GlStateManager._glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, probeFbo);
		int pbo = GL11.glGetInteger(GL21.GL_PIXEL_PACK_BUFFER_BINDING);
		if (pbo != 0) GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, 0);
		for (int k = 0; k < npx; k++) GL11.glReadPixels(PXX[k], PXY[k], 1, 1, GL11.GL_RGBA, GL11.GL_FLOAT, DPR[k]);
		if (pbo != 0) GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, pbo);
		GlStateManager._glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, prevRead);
		GlStateManager._glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, prevDraw);
		mat(sp, "ViewProj", VP);
		RenderSystem.enableBlend();
	}

	private static String rgb(float[] c) {
		return String.format(Locale.ROOT, "(%.3f,%.3f,%.3f)", c[0], c[1], c[2]);
	}

	private static String mrow(Matrix4f m, int r) {
		return String.format(Locale.ROOT, "[%.4f %.4f %.4f %.4f]", m.get(0, r), m.get(1, r), m.get(2, r), m.get(3, r));
	}
}
