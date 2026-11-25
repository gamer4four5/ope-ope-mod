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
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL14;
import org.lwjgl.opengl.GL30;

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
	/** Fuerza de la luz a oscuras (de día se queda en ~1/8). */
	private static final float LIGHT_BASE = 6f;
	private static final int MAXL = 24;
	/** Punto A y B de cada luz (mundo), eje del aro, y alcance, fuerza, semilla, radio del aro (0 = segmento), alto del aro. */
	private static final double[] LA = new double[MAXL * 3], LB = new double[MAXL * 3];
	private static final float[] LAXIS = new float[MAXL * 3], LINFO = new float[MAXL * 4], LRINGH = new float[MAXL];
	private static int nl;
	private static final BlockPos.Mutable LPOS = new BlockPos.Mutable();

	/** La luz que da cada forma: un segmento (o un aro) en sus coordenadas locales, con su alcance y su fuerza. */
	private static void autoLight(int i, int shape, float p0, float p1, float p2, float intensity) {
		if (!lights || nl >= MAXL) return;
		float ax = 0, ay = 0, az = 0, bx = 0, by = 0, bz = 0, ring = 0, ringH = 0, range = 0, power = 0;
		switch (shape) {
			case FIREBALL -> {
				float r = Math.max(p0, 0.05f), cool = MathHelper.clamp(p1, 0f, 1f), mv = MathHelper.clamp(p2, 0f, 1f);
				ay = by = cool * cool * r * 0.55f;
				bz = -0.6f * r * mv;
				range = r * 3f + 5f;
				power = Math.min(1.3f, 0.75f + 0.12f * r) * (1f - 0.75f * cool);
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

	/** Lo oscuro que está alrededor (0,12 a pleno sol … 1 en una cueva): el fuego ilumina más cuanto más oscuro. */
	private static float darkness(double x, double y, double z) {
		ClientWorld w = MinecraftClient.getInstance().world;
		if (w == null) return 1f;
		LPOS.set(x, y, z);
		float sky = w.getLightLevel(LightType.SKY, LPOS) / 15f * w.getSkyBrightness(1f);
		float blk = w.getLightLevel(LightType.BLOCK, LPOS) / 15f;
		float d = 1f - MathHelper.clamp(Math.max(sky, blk), 0f, 1f);
		return 0.12f + 0.88f * d * (float) Math.sqrt(d);
	}

	public static int lightCount() {
		return nl;
	}

	/** La luz de cada fuego sobre lo que ya está dibujado: multiplica el mundo por (1 + luz). */
	private static void drawLights(double camX, double camY, double camZ) {
		ShaderProgram lp = lightProgram;
		if (lp == null || nl == 0 || !lights) return;
		RenderSystem.setShader(() -> lp);
		RenderSystem.setShaderTexture(0, depthTex);
		RenderSystem.blendFuncSeparate(GlStateManager.SrcFactor.DST_COLOR, GlStateManager.DstFactor.ONE,
			GlStateManager.SrcFactor.ZERO, GlStateManager.DstFactor.ONE);
		mat(lp, "ViewProj", VP);
		mat(lp, "InvViewProj", INV);
		vec(lp, "ViewSize", viewW, viewH);
		vec(lp, "VolTime", time0);
		vec(lp, "LightColor", 1f, 0.55f, 0.22f);
		for (int k = 0; k < nl; k++) {
			float ax = (float) (LA[k * 3] - camX), ay = (float) (LA[k * 3 + 1] - camY), az = (float) (LA[k * 3 + 2] - camZ);
			float bx = (float) (LB[k * 3] - camX), by = (float) (LB[k * 3 + 1] - camY), bz = (float) (LB[k * 3 + 2] - camZ);
			float range = LINFO[k * 4], ring = LINFO[k * 4 + 3];
			vec(lp, "LightA", ax, ay, az);
			if (ring > 0) vec(lp, "LightB", LRINGH[k], 0f, 0f);
			else vec(lp, "LightB", bx, by, bz);
			vec(lp, "LightAxis", LAXIS[k * 3], LAXIS[k * 3 + 1], LAXIS[k * 3 + 2]);
			vec4(lp, "LightInfo", range, LINFO[k * 4 + 1], LINFO[k * 4 + 2], ring);
			float x0, y0, z0, x1, y1, z1;
			if (ring > 0) {
				float e = ring + LRINGH[k] + range;
				x0 = ax - e;
				y0 = ay - e;
				z0 = az - e;
				x1 = ax + e;
				y1 = ay + e;
				z1 = az + e;
			} else {
				x0 = Math.min(ax, bx) - range;
				y0 = Math.min(ay, by) - range;
				z0 = Math.min(az, bz) - range;
				x1 = Math.max(ax, bx) + range;
				y1 = Math.max(ay, by) + range;
				z1 = Math.max(az, bz) + range;
			}
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
		try {
			// primero la luz sobre el mundo, luego el fuego encima
			drawLights(camX, camY, camZ);
			RenderSystem.setShader(() -> sp);
			RenderSystem.setShaderTexture(0, depthTex);
			RenderSystem.blendFuncSeparate(GlStateManager.SrcFactor.ONE, GlStateManager.DstFactor.ONE_MINUS_SRC_ALPHA,
				GlStateManager.SrcFactor.ZERO, GlStateManager.DstFactor.ONE);
			mat(sp, "ViewProj", VP);
			mat(sp, "InvViewProj", INV);
			vec(sp, "ViewSize", w, h);
			vec(sp, "VolTime", time0);
			for (int o = 0; o < n; o++) drawOne(sp, ORDER[o], camX, camY, camZ);
		} finally {
			RenderSystem.enableDepthTest();
			RenderSystem.depthMask(true);
			RenderSystem.enableCull();
			RenderSystem.defaultBlendFunc();
			RenderSystem.disableBlend();
		}
	}

	private static void drawOne(ShaderProgram sp, int i, double camX, double camY, double camZ) {
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
}
