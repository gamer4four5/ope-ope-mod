package dev.opeope.client.mera;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import dev.opeope.OpeOpeMod;
import dev.opeope.client.config.OpeSettings;
import dev.opeope.client.render.LawHudFx;
import dev.opeope.client.room.CameraFx;
import dev.opeope.client.room.RoomFxClient;
import dev.opeope.net.MeraFx;
import dev.opeope.power.mera.MeraTechs;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.render.BufferBuilder;
import net.minecraft.client.render.BufferRenderer;
import net.minecraft.client.render.Camera;
import net.minecraft.client.render.GameRenderer;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.RenderTickCounter;
import net.minecraft.client.render.Tessellator;
import net.minecraft.client.render.VertexFormat;
import net.minecraft.client.render.VertexFormats;
import net.minecraft.client.texture.AbstractTexture;
import it.unimi.dsi.fastutil.longs.Long2DoubleMap;
import it.unimi.dsi.fastutil.longs.Long2DoubleOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongIterator;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import dev.opeope.registry.ModBlocks;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.util.math.Direction;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.world.RaycastContext;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * El fuego de la Mera Mera no Mi, dibujado con un motor de llamas propio (sin las partículas de Minecraft).
 * <p>
 * Cada llama es un «copo» con forma recortada de fuego (estilo cel del anime: dos tonos, borde limpio) que nace en
 * blanco-amarillo, pasa a naranja y muere en rojo oscuro mientras sube, se agita y crece. Encima va una capa aditiva de
 * resplandor para los núcleos (el puño de Hiken, el sol de Entei, las luciérnagas de Hotarubi). Cada técnica es un
 * emisor que va soltando llamas con su forma: el puño con sus nudillos, la cruz de Jūjika, el muro de Kyokaen, el cerco
 * de Enjōmō, la columna de Hibashira, las lanzas de Shiranui, el chorro de Kagerō y el sol de Entei.
 */
public final class MeraFxClient {
	private MeraFxClient() {}

	public static final Identifier ATLAS = OpeOpeMod.id("textures/fx/mera_flame.png");

	/** El color del humo y del vapor según la luz que haya: de día gris claro, de noche casi negro. */
	private static int smokeRGB = 0x9A938B, steamRGB = 0xE8ECF0;
	/** Con el fuego en 3D el humo ya lo pone el volumen: las nubes planas se bajan para que no se vean como discos. */
	private static float smokeMul = 1f;

	private static int mixRGB(int dark, int lit, float k) {
		float u = MathHelper.clamp(k, 0f, 1f);
		int r = (int) MathHelper.lerp(u, (dark >> 16) & 255, (lit >> 16) & 255);
		int g = (int) MathHelper.lerp(u, (dark >> 8) & 255, (lit >> 8) & 255);
		int b = (int) MathHelper.lerp(u, dark & 255, lit & 255);
		return (r << 16) | (g << 8) | b;
	}

	// ------------------------------------------------------------------ las llamas (estructura de arrays)

	private static final int MAX = 6500;
	static final byte FIRE = 0, GLOW = 1, FLY = 2, SMOKE = 3, STEAM = 4, SPARK = 5, GREEN = 6, TONGUE = 7, CORE = 8;
	private static final float[] PX = new float[MAX], PY = new float[MAX], PZ = new float[MAX];
	private static final float[] VX = new float[MAX], VY = new float[MAX], VZ = new float[MAX];
	private static final float[] AGE = new float[MAX], LIFE = new float[MAX], SIZE = new float[MAX], ROT = new float[MAX];
	private static final float[] SPIN = new float[MAX], DRAG = new float[MAX], LIFT = new float[MAX];
	private static final float[] CR = new float[MAX], CG = new float[MAX], CB = new float[MAX];
	private static final byte[] KIND = new byte[MAX], CELL = new byte[MAX];
	// posiciones relativas a un origen que se mueve con la cámara (floats con precisión cerca del jugador)
	private static double ox, oy, oz;
	private static boolean originSet;
	private static int count;

	private static final Random R = new Random();

	// ------------------------------------------------------------------ los efectos en marcha

	private static final class Fx {
		final int kind, userId, id, style;
		final Vec3d a, b;
		final float radius, power;
		final double start, dur;
		boolean ended;
		double endAt = -1;
		Vec3d endPos;
		float acc, acc2;
		Vec3d last;
		/** Estallidos: el punto de la pared o el suelo que tocan y la normal de esa cara (wallP null = al aire). */
		boolean wallChecked;
		Vec3d wallP, wallN;

		Fx(MeraFx p, double now) {
			kind = p.kind();
			userId = p.userId();
			id = p.id();
			style = p.style();
			a = new Vec3d(p.ax(), p.ay(), p.az());
			b = new Vec3d(p.bx(), p.by(), p.bz());
			radius = p.radius();
			power = p.power();
			start = now;
			dur = Math.max(0.05, p.ticks() / 20.0);
		}

		double age(double now) {
			return now - start;
		}
	}

	private static final List<Fx> FX = new ArrayList<>();
	/** Cuerpos de llamas a la vista: id del jugador → hasta cuándo. */
	private static final Map<Integer, Double> BODY = new HashMap<>();
	/** Grupos de luciérnagas (Hotarubi) por id, también un rato después de lanzarse (para Hidaruma). */
	private static final Map<Integer, Fx> FIREFLIES = new HashMap<>();

	/** Para las capturas de prueba: solo el fuego 3D, sin dibujar las llamas sueltas ni las chispas. */
	public static boolean hideFlames;

	private static double lastFrame = -1;
	private static double flashAt = -10, flashStrength;
	/**
	 * Lo más fuerte que piden los golpes de Ace a las líneas del manga (LawHudFx.impact): desde 0,6 sale además un
	 * fotograma en negativo, que con el fuego lo vuelve azul y morado.
	 */
	private static final float MERA_IMPACT_MAX = 0.55f;
	private static int flashRgb = 0xFFB040;

	public static void clear() {
		FX.clear();
		BODY.clear();
		FIREFLIES.clear();
		count = 0;
		originSet = false;
		MeraAnim.clear();
		MeraVolumes.clear();
		hideFlames = false;
	}

	/** Para las pruebas: cuántas llamas vivas y cuántos efectos. */
	public static String debug() {
		return "llamas=" + count + " efectos=" + FX.size() + " cuerpos=" + BODY.size()
			+ " volumenes=" + MeraVolumes.count() + " luces=" + MeraVolumes.lightCount() + (MeraVolumes.ready() ? "" : " (sin fuego 3D)");
	}

	public static int flameCount() {
		return count;
	}

	public static int effectCount() {
		return FX.size();
	}

	public static boolean bodyOn(int entityId) {
		Double u = BODY.get(entityId);
		return u != null && u > RoomFxClient.time();
	}

	// ------------------------------------------------------------------ llegada de un momento

	public static void receive(MeraFx p) {
		if (p == null) return;
		MinecraftClient mc = MinecraftClient.getInstance();
		if (mc.player == null) return;
		double now = RoomFxClient.time();
		boolean mine = p.userId() == mc.player.getId();
		int user = p.userId();
		Vec3d a = new Vec3d(p.ax(), p.ay(), p.az());
		double distSq = mc.player.squaredDistanceTo(a);

		switch (p.kind()) {
			case MeraFx.IMPACT, MeraFx.ENTEI_BLAST -> {
				for (Fx f : FX) {
					if (p.id() != 0 && f.id == p.id() && !f.ended && isShot(f.kind)) {
						f.ended = true;
						f.endAt = now;
						f.endPos = a;
					}
				}
				float r = p.radius();
				if (distSq < 120 * 120) {
					float prox = (float) MathHelper.clamp(1.0 - Math.sqrt(distSq) / (p.kind() == MeraFx.ENTEI_BLAST ? 120.0 : 40.0), 0.0, 1.0);
					float amp = p.kind() == MeraFx.ENTEI_BLAST ? 1.0f : MathHelper.clamp(r / 5f, 0.08f, 0.6f);
					if (prox > 0.01f && p.style() != MeraFx.STYLE_FIZZLE && p.style() != MeraFx.STYLE_STEAM) {
						CameraFx.trauma(Math.min(1f, amp * prox));
						if (p.kind() == MeraFx.ENTEI_BLAST) {
							CameraFx.kick(4.2f * prox, 0f, 2.6f * prox);
							CameraFx.fov(-8f * prox, 0.04f, 0.9f);
							// un fogonazo cálido y breve (la bola ya llena la vista: si el destello es fuerte la pantalla
							// queda lavada) y las líneas del golpe, sin el negativo del manga, que es cosa de Law y
							// volvía azul el fuego
							flash(0.4f * prox, 0xFFB45A);
							LawHudFx.impact(Math.min(MERA_IMPACT_MAX, prox), 0.5f, 0.5f);
						} else if (r > 3f) {
							flash(0.22f * prox, 0xFF9A3A);
						}
					}
				}
				FX.add(new Fx(p, now));
			}
			case MeraFx.ZONE_END -> {
				for (Fx f : FX) {
					if (f.id == p.id() && !f.ended && !isShot(f.kind)) {
						f.ended = true;
						f.endAt = now;
						// las luciérnagas lanzadas (Hidaruma) se apagan al instante: salen volando
						if (f.kind == MeraFx.HOTARUBI && p.style() == 1) f.endAt = now - 10;
					}
				}
			}
			case MeraFx.BODY -> {
				if (p.power() > 0.5f) {
					BODY.put(user, now + Math.max(1.2, p.ticks() / 20.0));
					if (p.style() == 1) {
						FX.add(new Fx(p, now));   // la llamarada al encenderse
						if (mine) LawHudFx.bannerText(MeraTechs.NAMES[12][0], MeraTechs.NAMES[12][1]);
					}
				} else {
					BODY.remove(user);
				}
			}
			case MeraFx.CHARGE_CANCEL -> {
				endUserFx(user, MeraFx.HIKEN_CHARGE, now);
				endUserFx(user, MeraFx.ENTEI_CHARGE, now);
				MeraAnim.release(user, MeraAnim.WINDUP);
				MeraAnim.release(user, MeraAnim.RAISE);
				FX.add(new Fx(p, now));
			}
			case MeraFx.KAGERO_END -> {
				endUserFx(user, MeraFx.KAGERO, now);
				MeraAnim.release(user, MeraAnim.PALM);
			}
			default -> {
				Fx f = new Fx(p, now);
				FX.add(f);
				if (p.kind() == MeraFx.HOTARUBI) FIREFLIES.put(p.id(), f);
			}
		}
		while (FX.size() > 160) FX.remove(0);

		// gestos, cámara y rótulo
		switch (p.kind()) {
			case MeraFx.HIKEN_CHARGE -> {
				MeraAnim.pose(user, MeraAnim.WINDUP, 9.0, true);
				if (mine) CameraFx.fov(-1.5f, 0.3f, 0.3f);
			}
			case MeraFx.HIKEN_SHOT -> {
				endUserFx(user, MeraFx.HIKEN_CHARGE, now);
				MeraAnim.pose(user, MeraAnim.PUNCH, 0.6, false);
				MeraAnim.fireArm(user, 0.55);
				if (mine) {
					float k = p.power();
					CameraFx.trauma(0.25f + 0.35f * k);
					CameraFx.kick(2.2f * k + 0.8f, 0f, 1.2f);
					CameraFx.fov(3.5f + 4f * k, 0.03f, 0.45f);
					LawHudFx.bannerText(MeraTechs.NAMES[0][0], MeraTechs.NAMES[0][1]);
					LawHudFx.impact(Math.min(MERA_IMPACT_MAX, 0.35f + 0.4f * k), 0.5f, 0.5f);
				}
			}
			case MeraFx.HIGAN_POSE -> {
				MeraAnim.pose(user, MeraAnim.FINGER_GUNS, p.ticks() / 20.0 + 0.25, true);
				if (mine) LawHudFx.bannerText(MeraTechs.NAMES[2][0], MeraTechs.NAMES[2][1]);
			}
			case MeraFx.HIGAN_BULLET -> {
				if (mine) CameraFx.kick(0.35f, (p.style() == 0 ? 0.2f : -0.2f), 0.1f);
			}
			case MeraFx.JUJIKA_FLASH -> {
				MeraAnim.pose(user, MeraAnim.CROSS, 0.85, false);
				if (mine) LawHudFx.bannerText(MeraTechs.NAMES[3][0], MeraTechs.NAMES[3][1]);
			}
			case MeraFx.JUJIKA_SHOT -> {
				if (mine) {
					CameraFx.trauma(0.3f);
					CameraFx.kick(1.4f, 0f, 0.8f);
				}
			}
			case MeraFx.ENTEI_CHARGE -> {
				MeraAnim.pose(user, MeraAnim.RAISE, 30.0, true);
				if (mine) LawHudFx.bannerText("大炎戒", "DAI ENKAI");
			}
			case MeraFx.ENTEI_SHOT -> {
				endUserFx(user, MeraFx.ENTEI_CHARGE, now);
				MeraAnim.pose(user, MeraAnim.THROW, 0.75, false);
				if (mine) {
					CameraFx.trauma(0.45f);
					CameraFx.kick(3f, 0f, 1.5f);
					CameraFx.fov(6f, 0.05f, 0.8f);
					LawHudFx.bannerText(MeraTechs.NAMES[1][0], MeraTechs.NAMES[1][1]);
				}
			}
			case MeraFx.ENKAI_GATHER -> {
				MeraAnim.pose(user, MeraAnim.SWEEP, 0.75, false);
				if (mine) LawHudFx.bannerText("炎戒", "ENKAI");
			}
			case MeraFx.HIBASHIRA -> {
				MeraAnim.pose(user, MeraAnim.THROW, 0.5, false);
				if (mine) {
					CameraFx.trauma(0.45f);
					LawHudFx.bannerText(MeraTechs.NAMES[9][0], MeraTechs.NAMES[9][1]);
				} else if (distSq < 40 * 40) {
					CameraFx.trauma(0.25f);
				}
			}
			case MeraFx.KAGERO -> {
				MeraAnim.pose(user, MeraAnim.PALM, p.ticks() / 20.0 + 0.3, true);
				if (mine) LawHudFx.bannerText(MeraTechs.NAMES[10][0], MeraTechs.NAMES[10][1]);
			}
			case MeraFx.DASH -> {
				MeraAnim.dash(user, p.ticks() / 20.0);
				if (mine) {
					CameraFx.fov(9f, 0.04f, 0.4f);
					CameraFx.trauma(0.15f);
				}
			}
			case MeraFx.HIKYAKU -> {
				MeraAnim.pose(user, MeraAnim.HOP, 0.45, false);
				if (mine) CameraFx.kick(-1.2f, 0f, 0.5f);
			}
			case MeraFx.SCATTER -> {
				Entity e = mc.world != null ? mc.world.getEntityById(user) : null;
				int parts = e != null ? partsAt(e, a, p.style() == 1) : MeraAnim.P_BODY;
				MeraAnim.hide(user, parts, p.style() == 1 ? 0.55 : 0.38);
			}
			case MeraFx.HOTARUBI -> {
				MeraAnim.pose(user, MeraAnim.PALM, 0.5, false);
				if (mine) LawHudFx.bannerText(MeraTechs.NAMES[4][0], MeraTechs.NAMES[4][1]);
			}
			case MeraFx.HIDARUMA -> {
				if (p.style() == 0) {
					MeraAnim.pose(user, MeraAnim.PUNCH, 0.45, false);
					if (mine) LawHudFx.bannerText(MeraTechs.NAMES[5][0], MeraTechs.NAMES[5][1]);
				}
			}
			case MeraFx.KYOKAEN -> {
				MeraAnim.pose(user, MeraAnim.PALM, 0.6, false);
				if (mine) LawHudFx.bannerText(MeraTechs.NAMES[6][0], MeraTechs.NAMES[6][1]);
			}
			case MeraFx.ENJOMO -> {
				MeraAnim.pose(user, MeraAnim.SWEEP, 0.7, false);
				if (mine) LawHudFx.bannerText(MeraTechs.NAMES[7][0], MeraTechs.NAMES[7][1]);
			}
			case MeraFx.SHIRANUI -> {
				if (p.style() == 0) {
					MeraAnim.pose(user, MeraAnim.THROW, 0.9, false);
					if (mine) LawHudFx.bannerText(MeraTechs.NAMES[8][0], MeraTechs.NAMES[8][1]);
				}
			}
			default -> {}
		}
	}

	private static boolean isShot(int kind) {
		return kind == MeraFx.HIKEN_SHOT || kind == MeraFx.HIGAN_BULLET || kind == MeraFx.JUJIKA_SHOT
			|| kind == MeraFx.SHIRANUI || kind == MeraFx.ENTEI_SHOT;
	}

	private static void endUserFx(int user, int kind, double now) {
		for (Fx f : FX) {
			if (f.userId == user && f.kind == kind && !f.ended) {
				f.ended = true;
				f.endAt = now;
			}
		}
	}

	private static void flash(float strength, int rgb) {
		if (OpeSettings.flashLevel() <= 0) return;
		flashAt = RoomFxClient.time();
		flashStrength = Math.max(strength, flashStrength * 0.5);
		flashRgb = rgb;
	}

	/** Qué partes atraviesa un golpe que entra por el punto at. */
	private static int partsAt(Entity e, Vec3d at, boolean blast) {
		if (blast) return MeraAnim.P_BODY | MeraAnim.P_RARM | MeraAnim.P_LARM | MeraAnim.P_HEAD;
		double y = at.y - e.getY();
		if (y > 1.45) return MeraAnim.P_HEAD;
		if (y > 0.72) {
			// por el lado: el brazo de ese lado y el torso
			float by = e instanceof LivingEntity le ? le.bodyYaw : e.getYaw();
			double rad = Math.toRadians(by);
			double rx = -Math.cos(rad), rz = -Math.sin(rad);
			double side = (at.x - e.getX()) * rx + (at.z - e.getZ()) * rz;
			if (side > 0.22) return MeraAnim.P_RARM | MeraAnim.P_BODY;
			if (side < -0.22) return MeraAnim.P_LARM | MeraAnim.P_BODY;
			return MeraAnim.P_BODY;
		}
		return MeraAnim.P_RLEG | MeraAnim.P_LLEG;
	}

	// ------------------------------------------------------------------ dibujar

	private static final Matrix4f BASE = new Matrix4f();
	private static boolean plain;
	private static final Vector3f TV = new Vector3f();
	// ejes de la cámara (para las llamas de cara a ella)
	private static float rx, ry, rz, ux, uy, uz, fx, fy, fz;
	private static double camX, camY, camZ;

	public static void renderWorld(WorldRenderContext ctx) {
		MinecraftClient mc = MinecraftClient.getInstance();
		if (mc.world == null || mc.player == null) return;
		double now = RoomFxClient.time();
		double dt = lastFrame < 0 ? 0.016 : MathHelper.clamp(now - lastFrame, 0.0, 0.1);
		lastFrame = now;
		float td = ctx.tickCounter().getTickDelta(true);
		Camera cam = ctx.camera();
		Vec3d cp = cam.getPos();
		camX = cp.x;
		camY = cp.y;
		camZ = cp.z;
		cameraAxes(cam);
		if (!originSet || Math.abs(ox - camX) > 256 || Math.abs(oy - camY) > 256 || Math.abs(oz - camZ) > 256) rebase(camX, camY, camZ);

		// los efectos sueltan llamas
		boolean calm = OpeSettings.reducedMotion();
		float density = calm ? 0.5f : 1f;
		MeraVolumes.begin();
		boolean v3 = vol();
		Iterator<Fx> it = FX.iterator();
		while (it.hasNext()) {
			Fx f = it.next();
			thin = v3 && volumetric(f) ? THIN : 1f;
			boolean alive = emit(mc, f, now, dt, td, density);
			thin = 1f;
			if (!alive) it.remove();
		}
		FIREFLIES.values().removeIf(f -> f.ended && now - f.endAt > 3.0);
		// los cuerpos de llamas
		if (!BODY.isEmpty()) {
			BODY.values().removeIf(u -> u < now);
			for (Map.Entry<Integer, Double> en : BODY.entrySet()) {
				Entity e = mc.world.getEntityById(en.getKey());
				if (e instanceof PlayerEntity pl && pl.isAlive()) emitBody(mc, pl, td, dt, density, now);
			}
		}
		// las partes que un golpe atravesó: en su lugar, fuego
		for (PlayerEntity pl : mc.world.getPlayers()) {
			int hidden = MeraAnim.hiddenParts(pl.getId());
			if (hidden != 0 && !MeraAnim.dashing(pl.getId())) emitHiddenParts(mc, pl, hidden, td, dt, density);
		}
		// lo que arde (lo que quemó Ace, los zombis al sol): llamas en 3D en vez de las dos láminas de Minecraft
		if (v3) emitBurning(mc, td, dt);
		else burnIds = NO_IDS;
		// y los bloques de fuego cercanos: llamas 3D delante de sus láminas pixeladas
		if (v3) emitFireBlocks(mc);
		// el fuego que dejan las técnicas en el suelo (no tiene dibujo de bloque): llamas 3D que acaban en humo
		emitMeraFire(mc, now, dt, v3);

		simulate((float) dt);
		boolean vols = MeraVolumes.count() > 0;
		if (count == 0 && !vols) return;
		// el fuego 3D primero: las chispas y el humo van encima
		if (vols) MeraVolumes.render(ctx, camX, camY, camZ, (float) (now % 3600.0));
		particlesPending = false;
		if (count == 0 || hideFlames) return;
		// la profundidad de este fotograma (aunque no haya fuego 3D): el humo y las chispas se desvanecen contra el suelo
		MeraVolumes.grabSoftDepth();
		if (MeraVolumes.shaderPackInUse()) {
			// con un shaderpack las chispas, brasas y humo también van al final, sobre la imagen terminada: dentro
			// del mundo el shaderpack las metía en sus buffers y salían como discos planos de borde duro
			DEF_POS.set(ctx.positionMatrix());
			DEF_PROJ.set(ctx.projectionMatrix());
			defNow = now;
			particlesPending = true;
			return;
		}
		drawParticles(mc, ctx.positionMatrix(), now);
	}

	private static boolean particlesPending;
	private static final Matrix4f DEF_POS = new Matrix4f(), DEF_PROJ = new Matrix4f();
	private static double defNow;

	/** Justo después de dibujar el mundo (GameRendererMixin), tras el fuego 3D: con un shaderpack, las llamas sueltas. */
	public static void afterWorld() {
		if (!particlesPending) return;
		particlesPending = false;
		MinecraftClient mc = MinecraftClient.getInstance();
		if (mc.world == null || count == 0) return;
		Matrix4f prevProj = new Matrix4f(RenderSystem.getProjectionMatrix());
		com.mojang.blaze3d.systems.VertexSorter prevSort = RenderSystem.getVertexSorting();
		mc.getFramebuffer().beginWrite(true);
		// la profundidad del mundo (la copia que usa el fuego 3D): que lo de detrás de una pared siga tapado
		MeraVolumes.restoreDepth();
		RenderSystem.setProjectionMatrix(DEF_PROJ, com.mojang.blaze3d.systems.VertexSorter.BY_DISTANCE);
		try {
			drawParticles(mc, DEF_POS, defNow);
		} finally {
			RenderSystem.setProjectionMatrix(prevProj, prevSort);
		}
	}

	private static void drawParticles(MinecraftClient mc, Matrix4f positionMatrix, double now) {
		boolean embers = MeraVolumes.ready();

		Matrix4f mv = RenderSystem.getModelViewMatrix();
		if (Math.abs(mv.m00() - 1) < 1e-4f && Math.abs(mv.m11() - 1) < 1e-4f && Math.abs(mv.m22() - 1) < 1e-4f) {
			BASE.set(positionMatrix);
			plain = false;
		} else {
			BASE.identity();
			plain = true;
		}

		// con el fuego 3D el humo de las llamas sueltas es algo más suave, pero que se vea: el fuego acaba en humo
		smokeMul = embers ? 0.9f : 1f;
		float amb = MeraVolumes.skyAt(camX, camY + 1, camZ);
		smokeRGB = mixRGB(0x2B2724, 0x9A938B, amb);
		steamRGB = mixRGB(0x454B52, 0xE8ECF0, amb);

		AbstractTexture tex = mc.getTextureManager().getTexture(ATLAS);
		tex.setFilter(true, false);
		RenderSystem.enableBlend();
		RenderSystem.disableCull();
		RenderSystem.enableDepthTest();
		RenderSystem.depthMask(false);
		// (con el programa propio: el de Minecraft recorta lo tenue y deja discos con el borde duro)
		RenderSystem.setShader(MeraVolumes::spriteProgram);
		RenderSystem.setShaderTexture(0, ATLAS);
		int tex1 = RenderSystem.getShaderTexture(1);
		try {
			// 1) el fuego, en 3D: bolas y lenguas sólidas que escriben profundidad (se tapan entre ellas y con el mundo)
			RenderSystem.defaultBlendFunc();
			if (!embers) {
				RenderSystem.depthMask(true);
				RenderSystem.setShader(GameRenderer::getPositionColorProgram);
				Flame3D.begin(BASE, plain);
				flameTime = (float) (now % 1000.0);
				BufferBuilder s3 = Tessellator.getInstance().begin(VertexFormat.DrawMode.TRIANGLES, VertexFormats.POSITION_COLOR);
				for (int i = 0; i < count; i++) if (KIND[i] == FIRE || KIND[i] == GREEN || KIND[i] == TONGUE || KIND[i] == CORE) draw3D(s3, i);
				var built3 = s3.endNullable();
				if (built3 != null) BufferRenderer.drawWithGlobalProgram(built3);
				RenderSystem.depthMask(false);
			}

			// 2) humo y vapor (suaves, mezcla normal): se desvanecen a medio bloque del suelo o de una pared
			RenderSystem.setShader(MeraVolumes::spriteProgram);
			RenderSystem.setShaderTexture(0, ATLAS);
			MeraVolumes.spriteSoft(0.5f);
			BufferBuilder b = Tessellator.getInstance().begin(VertexFormat.DrawMode.QUADS, VertexFormats.POSITION_TEXTURE_COLOR);
			for (int i = 0; i < count; i++) if (KIND[i] == SMOKE || KIND[i] == STEAM) drawPuff(b, i);
			NEAR = 1f;
			var built = b.endNullable();
			if (built != null) BufferRenderer.drawWithGlobalProgram(built);

			RenderSystem.blendFunc(GlStateManager.SrcFactor.SRC_ALPHA, GlStateManager.DstFactor.ONE);
			// (los resplandores y las brasas, más cerca: son pequeños)
			MeraVolumes.spriteSoft(0.2f);
			BufferBuilder g = Tessellator.getInstance().begin(VertexFormat.DrawMode.QUADS, VertexFormats.POSITION_TEXTURE_COLOR);
			for (int i = 0; i < count; i++) {
				byte k = KIND[i];
				if (k == GLOW || k == FLY || k == SPARK) drawPuff(g, i);
				else if (embers) ember(g, i);   // con el fuego 3D, las llamas sueltas son brasas y lenguas pequeñas
				else if (k == CORE || (k == FIRE && SIZE[i] > 0.3f)) bloom(g, i);   // el calor alrededor del fuego
			}
			NEAR = 1f;
			var builtG = g.endNullable();
			if (builtG != null) BufferRenderer.drawWithGlobalProgram(builtG);
		} finally {
			RenderSystem.setShaderTexture(1, tex1);
			RenderSystem.defaultBlendFunc();
			RenderSystem.depthMask(true);
			RenderSystem.enableCull();
			RenderSystem.disableBlend();
		}
	}

	private static void cameraAxes(Camera cam) {
		double yaw = Math.toRadians(cam.getYaw()), pitch = Math.toRadians(cam.getPitch());
		fx = (float) (-Math.sin(yaw) * Math.cos(pitch));
		fy = (float) (-Math.sin(pitch));
		fz = (float) (Math.cos(yaw) * Math.cos(pitch));
		rx = (float) -Math.cos(yaw);
		ry = 0f;
		rz = (float) -Math.sin(yaw);
		// arriba = derecha × delante
		ux = ry * fz - rz * fy;
		uy = rz * fx - rx * fz;
		uz = rx * fy - ry * fx;
	}

	private static void rebase(double nx, double ny, double nz) {
		float dx = (float) (ox - nx), dy = (float) (oy - ny), dz = (float) (oz - nz);
		if (originSet) {
			for (int i = 0; i < count; i++) {
				PX[i] += dx;
				PY[i] += dy;
				PZ[i] += dz;
			}
		}
		ox = nx;
		oy = ny;
		oz = nz;
		originSet = true;
	}

	// ------------------------------------------------------------------ simulación

	private static void simulate(float dt) {
		if (dt <= 0f) return;
		double t = RoomFxClient.time();
		int i = 0;
		while (i < count) {
			AGE[i] += dt;
			if (AGE[i] >= LIFE[i]) {
				removeAt(i);
				continue;
			}
			float d = Math.max(0f, 1f - DRAG[i] * dt);
			VX[i] *= d;
			VY[i] *= d;
			VZ[i] *= d;
			VY[i] += LIFT[i] * dt;
			if (KIND[i] == FIRE || KIND[i] == TONGUE || KIND[i] == SMOKE) {
				// el fuego se agita: un remolino barato que depende del sitio y del tiempo
				float n = (float) (Math.sin(PX[i] * 1.7 + t * 3.1) + Math.cos(PZ[i] * 1.9 - t * 2.7));
				float m = (float) (Math.cos(PY[i] * 1.3 + t * 2.3) + Math.sin(PX[i] * 1.1 + PZ[i] * 0.7));
				float k = 1.6f * dt;
				VX[i] += n * k;
				VZ[i] += m * k;
			}
			PX[i] += VX[i] * dt;
			PY[i] += VY[i] * dt;
			PZ[i] += VZ[i] * dt;
			ROT[i] += SPIN[i] * dt;
			i++;
		}
	}

	private static void removeAt(int i) {
		int j = --count;
		if (i == j) return;
		PX[i] = PX[j]; PY[i] = PY[j]; PZ[i] = PZ[j];
		VX[i] = VX[j]; VY[i] = VY[j]; VZ[i] = VZ[j];
		AGE[i] = AGE[j]; LIFE[i] = LIFE[j]; SIZE[i] = SIZE[j]; ROT[i] = ROT[j];
		SPIN[i] = SPIN[j]; DRAG[i] = DRAG[j]; LIFT[i] = LIFT[j];
		CR[i] = CR[j]; CG[i] = CG[j]; CB[i] = CB[j];
		KIND[i] = KIND[j]; CELL[i] = CELL[j];
	}

	/** Suelta una llama. Devuelve su índice (−1 si no cabe). */
	private static int spawn(byte kind, double x, double y, double z, double vx, double vy, double vz, float size, float life) {
		int i;
		if (count < MAX) i = count++;
		else {
			// lleno: se reemplaza la más vieja de una muestra
			i = R.nextInt(MAX);
		}
		PX[i] = (float) (x - ox);
		PY[i] = (float) (y - oy);
		PZ[i] = (float) (z - oz);
		VX[i] = (float) vx;
		VY[i] = (float) vy;
		VZ[i] = (float) vz;
		AGE[i] = 0f;
		LIFE[i] = Math.max(0.05f, life);
		SIZE[i] = size;
		ROT[i] = R.nextFloat() * 6.2832f;
		SPIN[i] = (R.nextFloat() - 0.5f) * 3f;
		KIND[i] = kind;
		CELL[i] = (byte) (kind == TONGUE ? 2 : (kind == GLOW || kind == FLY || kind == SPARK || kind == SMOKE || kind == STEAM) ? 3 : R.nextInt(2));
		switch (kind) {
			case FIRE, GREEN, CORE -> {
				DRAG[i] = 2.2f;
				LIFT[i] = 3.2f;
			}
			case TONGUE -> {
				DRAG[i] = 1.6f;
				LIFT[i] = 4.5f;
				SPIN[i] = 0f;
			}
			case SMOKE -> {
				DRAG[i] = 1.2f;
				LIFT[i] = 1.6f;
				SPIN[i] *= 0.4f;
			}
			case STEAM -> {
				DRAG[i] = 1.4f;
				LIFT[i] = 2.4f;
			}
			case SPARK -> {
				DRAG[i] = 0.9f;
				LIFT[i] = -6f;
			}
			default -> {
				DRAG[i] = 2.5f;
				LIFT[i] = 0f;
			}
		}
		CR[i] = 1f;
		CG[i] = 0.6f;
		CB[i] = 0.2f;
		return i;
	}

	private static void fire(double x, double y, double z, double vx, double vy, double vz, float size, float life) {
		if (thin < 1f && R.nextFloat() > thin) return;
		spawn(FIRE, x, y, z, vx, vy, vz, size, life);
	}

	private static void tongue(double x, double y, double z, double vx, double vy, double vz, float size, float life) {
		if (thin < 1f && R.nextFloat() > thin) return;
		spawn(TONGUE, x, y, z, vx, vy, vz, size, life);
	}

	private static void core(double x, double y, double z, double vx, double vy, double vz, float size, float life) {
		if (thin < 1f && R.nextFloat() > thin) return;
		spawn(CORE, x, y, z, vx, vy, vz, size, life);
	}

	private static void glow(double x, double y, double z, double vx, double vy, double vz, float size, float life, float r, float g, float bl) {
		// con el fuego 3D el resplandor grande ya lo pone el shader: aquí solo quedan los pequeños
		if (thin < 1f && size > 0.9f) return;
		int i = spawn(GLOW, x, y, z, vx, vy, vz, size, life);
		if (i < 0) return;
		CR[i] = r;
		CG[i] = g;
		CB[i] = bl;
	}

	private static void spark(double x, double y, double z, double vx, double vy, double vz, float life) {
		int i = spawn(SPARK, x, y, z, vx, vy, vz, 0.08f + R.nextFloat() * 0.06f, life);
		if (i < 0) return;
		CR[i] = 1f;
		CG[i] = 0.75f;
		CB[i] = 0.3f;
	}

	private static void smoke(double x, double y, double z, double vx, double vy, double vz, float size, float life) {
		spawn(SMOKE, x, y, z, vx, vy, vz, size, life);
	}

	/** Humo más oscuro (tone 1 = el gris de siempre): el que sale de una nube de humo 3D, de su mismo gris. */
	private static void smoke(double x, double y, double z, double vx, double vy, double vz, float size, float life, float tone) {
		int i = spawn(SMOKE, x, y, z, vx, vy, vz, size, life);
		if (i >= 0) CR[i] = tone;
	}

	private static void steam(double x, double y, double z, double vx, double vy, double vz, float size, float life) {
		spawn(STEAM, x, y, z, vx, vy, vz, size, life);
	}

	private static float rs() {
		return R.nextFloat() * 2f - 1f;
	}

	private static int many(Fx f, float rate, double dt) {
		f.acc += (float) (rate * dt);
		int n = (int) f.acc;
		f.acc -= n;
		return n;
	}

	// ------------------------------------------------------------------ el fuego de Minecraft, con el fuego 3D

	private static final int[] NO_IDS = new int[0];
	/** Los seres que este fotograma arden con llamas 3D: a esos Minecraft no les pone sus láminas de fuego. */
	private static volatile int[] burnIds = NO_IDS;
	private static final int BURN_MAX = 8;
	private static final double BURN_RANGE = 32;
	private static final Entity[] BURN = new Entity[BURN_MAX];
	private static final double[] BURN_D = new double[BURN_MAX];

	/** Para el render de entidades (Entity#doesRenderOnFire): ¿este ya arde en 3D? */
	public static boolean burns3D(Entity e) {
		int[] ids = burnIds;
		if (ids.length == 0 || e.getWorld() == null || !e.getWorld().isClient()) return false;
		int id = e.getId();
		for (int k : ids) if (k == id) return true;
		return false;
	}

	/**
	 * El humo de los bloques de fuego (AbstractFireBlock#randomDisplayTick): Minecraft lo pinta con cuadraditos negros
	 * que suben; con el fuego 3D, humo suave del color que da la luz del sitio. Devuelve false si debe salir el de
	 * Minecraft.
	 */
	public static boolean fireSmoke(double x, double y, double z) {
		if (!vol()) return false;
		// algo más de la mitad de los soplos; y si hay muchas llamas en el aire, ninguno (antes que quitarle sitio a
		// una técnica)
		if (count > MAX * 0.6f || R.nextFloat() < 0.45f) return true;
		smoke(x, y, z, rs() * 0.12, 0.55 + R.nextFloat() * 0.35, rs() * 0.12, 0.32f + R.nextFloat() * 0.25f, 2.4f + R.nextFloat() * 1.4f);
		return true;
	}

	/** Llamas 3D en los seres que arden cerca (los más cercanos), del tamaño de cada uno, y un hilo de humo encima. */
	private static void emitBurning(MinecraftClient mc, float td, double dt) {
		int found = 0;
		double r2 = BURN_RANGE * BURN_RANGE;
		Entity cam = mc.getCameraEntity();
		boolean firstPerson = mc.options.getPerspective().isFirstPerson();
		for (Entity e : mc.world.getEntities()) {
			if (!(e instanceof LivingEntity) || !e.isOnFire() || e.isSpectator() || e.isInvisible()) continue;
			if (e == cam && firstPerson) continue;
			double d2 = e.squaredDistanceTo(camX, camY, camZ);
			if (d2 > r2) continue;
			// los más cercanos primero (inserción en una lista corta)
			int k = Math.min(found, BURN_MAX - 1);
			if (found == BURN_MAX && d2 >= BURN_D[k]) continue;
			while (k > 0 && BURN_D[k - 1] > d2) {
				BURN[k] = BURN[k - 1];
				BURN_D[k] = BURN_D[k - 1];
				k--;
			}
			BURN[k] = e;
			BURN_D[k] = d2;
			if (found < BURN_MAX) found++;
		}
		if (found == 0) {
			burnIds = NO_IDS;
			return;
		}
		int[] ids = new int[found];
		for (int i = 0; i < found; i++) {
			Entity e = BURN[i];
			BURN[i] = null;
			ids[i] = e.getId();
			Vec3d p0 = e.getLerpedPos(td);
			float w = Math.max(0.3f, e.getWidth()), h = Math.max(0.3f, e.getHeight());
			int seed = e.getId() * 7919 + 17;
			float rad = MathHelper.clamp(w * 0.75f, 0.32f, 1.6f);
			// el cuerpo del ser tapa lo que arde dentro de él (está en la profundidad): las llamas van hacia el lado que
			// mira a la cámara, como las láminas de Minecraft, pero con volumen
			double tx = camX - p0.x, tz = camZ - p0.z, tl = Math.sqrt(tx * tx + tz * tz);
			if (tl < 1e-3) {
				tx = 0;
				tz = 1;
			} else {
				tx /= tl;
				tz /= tl;
			}
			Vec3d p = p0.add(tx * w * 0.55, 0, tz * w * 0.55);
			// la llama grande desde los pies, más alta que el ser
			MeraVolumes.add(MeraVolumes.FLAME, p, FWD, rad, h * 1.3f + 0.45f, 0, 0, seed, 0.92f);
			double a = (seed % 628) / 100.0;
			if (h > 1.2f) {
				// los altos (zombis, jugadores): otra lengua desde el pecho, un poco a un lado
				Vec3d q = p.add(Math.cos(a) * w * 0.3, h * 0.45, Math.sin(a) * w * 0.3);
				MeraVolumes.add(MeraVolumes.FLAME, q, FWD, rad * 0.72f, h * 0.85f, 0, 0, seed + 1, 0.85f);
			}
			if (w > 0.85f) {
				// los anchos (cerdos, vacas): dos lenguas más, a los lados
				double sx = -tz * w * 0.42, sz = tx * w * 0.42;
				MeraVolumes.add(MeraVolumes.FLAME, p.add(sx, h * 0.15, sz), FWD, rad * 0.62f, h * 1.1f + 0.25f, 0, 0, seed + 2, 0.8f);
				MeraVolumes.add(MeraVolumes.FLAME, p.add(-sx, h * 0.15, -sz), FWD, rad * 0.62f, h * 1.1f + 0.25f, 0, 0, seed + 3, 0.8f);
			}
			// y lo que arde echa humo
			if (R.nextFloat() < dt * 2.5) smoke(p.x + rs() * w * 0.3, p.y + h * 1.25, p.z + rs() * w * 0.3, rs() * 0.15, 0.9, rs() * 0.15, 0.3f + w * 0.3f, 2.2f);
		}
		burnIds = ids;
	}

	// ------------------------------------------------------------------ los bloques de fuego, con llamas 3D

	private static final int FB_MAX = 16, FB_RXZ = 14, FB_RY = 6, FB_SLICES = 6;
	private static final double FB_RANGE = 22;
	/** Los bloques de fuego vistos alrededor de la cámara (la última vuelta completa y lo que va saliendo). */
	private static final LongOpenHashSet FB_SEEN = new LongOpenHashSet(), FB_SCAN = new LongOpenHashSet();
	private static int fbSlice;
	private static final BlockPos.Mutable FB_POS = new BlockPos.Mutable();
	private static final long[] FB_PICK = new long[FB_MAX];
	private static final double[] FB_D = new double[FB_MAX];

	/**
	 * Los bloques de fuego de Minecraft (los que dejan las técnicas al quemar el suelo) son láminas pixeladas: los más
	 * cercanos llevan delante una llama 3D, del lado que mira a la cámara. Se buscan por partes (un trozo de la caja
	 * alrededor de la cámara en cada fotograma) y no dan luz propia: el bloque ya alumbra.
	 */
	private static void emitFireBlocks(MinecraftClient mc) {
		var w = mc.world;
		int bx = MathHelper.floor(camX), by = MathHelper.floor(camY), bz = MathHelper.floor(camZ);
		int span = 2 * FB_RXZ + 1;
		int x0 = bx - FB_RXZ + fbSlice * span / FB_SLICES, x1 = bx - FB_RXZ + (fbSlice + 1) * span / FB_SLICES;
		for (int x = x0; x < x1; x++) {
			for (int z = bz - FB_RXZ; z <= bz + FB_RXZ; z++) {
				for (int y = by - FB_RY; y <= by + FB_RY; y++) {
					FB_POS.set(x, y, z);
					BlockState st = w.getBlockState(FB_POS);
					if (st.isOf(Blocks.FIRE)) {
						long l = FB_POS.asLong();
						FB_SCAN.add(l);
						FB_SEEN.add(l);
					} else if (st.isOf(ModBlocks.MERA_FIRE)) {
						// por si llegó con el trozo de mundo (sin aviso de cambio de bloque)
						MF.putIfAbsent(FB_POS.asLong(), RoomFxClient.time() - 1.0);
					}
				}
			}
		}
		if (++fbSlice >= FB_SLICES) {
			fbSlice = 0;
			FB_SEEN.clear();
			FB_SEEN.addAll(FB_SCAN);
			FB_SCAN.clear();
		}
		if (FB_SEEN.isEmpty()) return;
		// los más cercanos que siguen ardiendo
		int found = 0;
		double r2 = FB_RANGE * FB_RANGE;
		LongIterator it = FB_SEEN.iterator();
		while (it.hasNext()) {
			long l = it.nextLong();
			FB_POS.set(l);
			if (!w.getBlockState(FB_POS).isOf(Blocks.FIRE)) {
				it.remove();
				continue;
			}
			double dx = FB_POS.getX() + 0.5 - camX, dy = FB_POS.getY() + 0.5 - camY, dz = FB_POS.getZ() + 0.5 - camZ;
			double d2 = dx * dx + dy * dy + dz * dz;
			if (d2 > r2) continue;
			int k = Math.min(found, FB_MAX - 1);
			if (found == FB_MAX && d2 >= FB_D[k]) continue;
			while (k > 0 && FB_D[k - 1] > d2) {
				FB_PICK[k] = FB_PICK[k - 1];
				FB_D[k] = FB_D[k - 1];
				k--;
			}
			FB_PICK[k] = l;
			FB_D[k] = d2;
			if (found < FB_MAX) found++;
		}
		for (int i = 0; i < found; i++) {
			long l = FB_PICK[i];
			FB_POS.set(l);
			int x = FB_POS.getX(), y = FB_POS.getY(), z = FB_POS.getZ();
			double cx = x + 0.5, cz = z + 0.5;
			double tx = camX - cx, tz = camZ - cz, tl = Math.sqrt(tx * tx + tz * tz);
			if (tl < 1e-3) {
				tx = 0;
				tz = 1;
			} else {
				tx /= tl;
				tz /= tl;
			}
			// en el suelo, desde abajo; colgado de una pared (sin suelo), un poco más arriba y más pequeña
			FB_POS.set(x, y - 1, z);
			boolean floor = !w.getBlockState(FB_POS).isAir();
			int seed = (int) (l ^ (l >>> 32)) * 31 + 7;
			float hsh = ((seed * 0x9E3779B9) >>> 8) / 16777216f;
			float hgt = (floor ? 1.1f : 0.8f) + 0.4f * hsh;
			MeraVolumes.addQuiet(MeraVolumes.FLAME, new Vec3d(cx + tx * 0.42, y + (floor ? 0.0 : 0.2), cz + tz * 0.42), FWD,
				floor ? 0.55f : 0.42f, hgt, floor ? 1f : 0f, 0, seed, 0.85f);
		}
	}

	// ------------------------------------------------------------------ el fuego de Ace en el suelo

	private static final int MF_MAX = 40;
	private static final double MF_RANGE = 48, MF_FORGET = 96, MF_OUT_T = 0.45;
	/** Los bloques de fuego de Ace (fuego_mera) conocidos: posición → cuándo prendieron (RoomFxClient.time). */
	private static final Long2DoubleOpenHashMap MF = new Long2DoubleOpenHashMap();
	/** Los que se acaban de apagar: posición → cuándo. La llama baja en un momento y queda el humo. */
	private static final Long2DoubleOpenHashMap MF_OUT = new Long2DoubleOpenHashMap();
	private static final long[] MF_PICK = new long[MF_MAX];
	private static final double[] MF_D = new double[MF_MAX];
	private static final BlockPos.Mutable MF_POS = new BlockPos.Mutable();
	private static final Direction[] SIDES = {Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST};
	private static ClientWorld mfWorld;
	/** Los que este fotograma lleva una llama 3D: a los demás (lejos, demasiados) el bloque les pone llamitas. */
	private static final LongOpenHashSet MF_DRAWN = new LongOpenHashSet();

	static {
		dev.opeope.block.MeraFireBlock.drawn3D = MeraFxClient::meraFireDrawn;
	}

	public static boolean meraFireDrawn(net.minecraft.world.World w, BlockPos pos) {
		return w == mfWorld && MF_DRAWN.contains(pos.asLong());
	}

	/**
	 * Desde ClientWorld#updateListeners (en el hilo del juego): un bloque cambió. Así se sabe al momento dónde prende y
	 * dónde se apaga el fuego de Ace, sin buscarlo por el mundo.
	 */
	public static void blockChanged(ClientWorld w, BlockPos pos, BlockState oldState, BlockState newState) {
		Block mera = ModBlocks.MERA_FIRE;
		if (mera == null) return;
		boolean was = oldState.isOf(mera), is = newState.isOf(mera);
		if (was == is) return;
		if (w != mfWorld) {
			MF.clear();
			MF_OUT.clear();
			mfWorld = w;
		}
		long l = pos.asLong();
		double now = RoomFxClient.time();
		if (is) {
			MF.put(l, now);
			MF_OUT.remove(l);
			return;
		}
		MF.remove(l);
		MF_OUT.put(l, now);
		// al apagarse queda humo (como el fuego de verdad: lo último que se ve es la columna de humo)
		if (!originSet) return;
		double x = pos.getX() + 0.5, y = pos.getY() + 0.35, z = pos.getZ() + 0.5;
		for (int i = 0; i < 2; i++) {
			smoke(x + rs() * 0.25, y + R.nextFloat() * 0.3, z + rs() * 0.25, rs() * 0.12, 0.55 + R.nextFloat() * 0.3, rs() * 0.12,
				0.45f + R.nextFloat() * 0.25f, 2.6f + R.nextFloat() * 1.2f);
		}
	}

	/**
	 * El fuego que dejan las técnicas de Ace en el suelo (el bloque fuego_mera no tiene dibujo): una llama 3D por bloque,
	 * los más cercanos a la cámara. Prende alta de golpe, se asienta y va bajando; al apagarse baja en un momento y
	 * deja humo. Sin fuego 3D (shader apagado), llamas sueltas.
	 */
	private static void emitMeraFire(MinecraftClient mc, double now, double dt, boolean v3) {
		ClientWorld w = mc.world;
		if (w != mfWorld) {
			MF.clear();
			MF_OUT.clear();
			mfWorld = w;
		}
		MF_DRAWN.clear();
		if (MF.isEmpty() && MF_OUT.isEmpty()) return;
		Block mera = ModBlocks.MERA_FIRE;
		int found = 0;
		double r2 = MF_RANGE * MF_RANGE, f2 = MF_FORGET * MF_FORGET;
		var it = MF.long2DoubleEntrySet().fastIterator();
		while (it.hasNext()) {
			Long2DoubleMap.Entry e = it.next();
			long l = e.getLongKey();
			MF_POS.set(l);
			double dx = MF_POS.getX() + 0.5 - camX, dy = MF_POS.getY() + 0.5 - camY, dz = MF_POS.getZ() + 0.5 - camZ;
			double d2 = dx * dx + dy * dy + dz * dz;
			if (d2 > f2) {
				// muy lejos: se olvida (si sigue ahí cuando vuelvas, la búsqueda de cerca lo encuentra)
				it.remove();
				continue;
			}
			if (d2 > r2) continue;
			if (!w.getBlockState(MF_POS).isOf(mera)) {
				it.remove();
				continue;
			}
			int k = Math.min(found, MF_MAX - 1);
			if (found == MF_MAX && d2 >= MF_D[k]) continue;
			while (k > 0 && MF_D[k - 1] > d2) {
				MF_PICK[k] = MF_PICK[k - 1];
				MF_D[k] = MF_D[k - 1];
				k--;
			}
			MF_PICK[k] = l;
			MF_D[k] = d2;
			if (found < MF_MAX) found++;
		}
		for (int i = 0; i < found; i++) {
			long l = MF_PICK[i];
			MF_DRAWN.add(l);
			meraFlame(w, l, (float) (now - MF.get(l)), 1f, v3, dt);
		}
		if (MF_OUT.isEmpty()) return;
		var out = MF_OUT.long2DoubleEntrySet().fastIterator();
		while (out.hasNext()) {
			Long2DoubleMap.Entry e = out.next();
			double u = (now - e.getDoubleValue()) / MF_OUT_T;
			if (u >= 1.0) {
				out.remove();
				continue;
			}
			MF_POS.set(e.getLongKey());
			double dx = MF_POS.getX() + 0.5 - camX, dy = MF_POS.getY() + 0.5 - camY, dz = MF_POS.getZ() + 0.5 - camZ;
			if (dx * dx + dy * dy + dz * dz > r2) continue;
			float fade = (float) (1.0 - u);
			meraFlame(w, e.getLongKey(), 3f, fade * fade, v3, dt);
		}
	}

	/** La llama de un bloque de fuego de Ace (age: segundos desde que prendió; fade: 1 → 0 al apagarse). */
	private static void meraFlame(ClientWorld w, long l, float age, float fade, boolean v3, double dt) {
		MF_POS.set(l);
		int x = MF_POS.getX(), y = MF_POS.getY(), z = MF_POS.getZ();
		int seed = (int) (l ^ (l >>> 32)) * 31 + 11;
		float hsh = ((seed * 0x9E3779B9) >>> 8) / 16777216f;
		// en el suelo, desde abajo; sin suelo (pegada a algo que arde), un poco más arriba y arrimada a ese lado
		MF_POS.set(x, y - 1, z);
		boolean floor = w.getBlockState(MF_POS).isSideSolidFullSquare(w, MF_POS, Direction.UP);
		double cx = x + 0.5, cy = y, cz = z + 0.5;
		if (!floor) {
			cy += 0.15;
			for (Direction d : SIDES) {
				MF_POS.set(x + d.getOffsetX(), y, z + d.getOffsetZ());
				if (w.getBlockState(MF_POS).isBurnable()) {
					cx += d.getOffsetX() * 0.3;
					cz += d.getOffsetZ() * 0.3;
					break;
				}
			}
		}
		// prende alta de golpe, se asienta y, con los segundos, va bajando
		float grow = smooth(age / 0.22f);
		float settle = 1f - 0.4f * smooth((age - 1.0f) / 4.0f);
		float h = (floor ? 1.45f : 1.0f) * (0.8f + 0.45f * hsh) * (0.35f + 0.65f * grow) * settle * fade;
		float rad = (floor ? 0.62f : 0.45f) * (0.85f + 0.3f * hsh) * (0.65f + 0.35f * grow);
		if (h < 0.06f) return;
		if (v3) {
			// (en el suelo, la base metida en él: la llama nace ancha del suelo)
			MeraVolumes.addQuiet(MeraVolumes.FLAME, new Vec3d(cx, cy, cz), FWD, rad, h, floor ? 1f : 0f, 0, seed, 0.92f * (0.35f + 0.65f * fade));
			return;
		}
		// sin fuego 3D: llamas sueltas que suben del bloque
		if (R.nextFloat() < dt * 16 * fade) {
			fire(cx + rs() * rad * 0.5, cy + 0.05, cz + rs() * rad * 0.5, rs() * 0.08, 1.4 + R.nextFloat() * 0.6, rs() * 0.08,
				0.3f + 0.15f * R.nextFloat(), 0.4f + 0.2f * R.nextFloat());
		}
	}

	// ------------------------------------------------------------------ el fuego 3D (volúmenes del shader)

	/**
	 * Qué parte de las llamas sueltas se siguen soltando mientras emite una técnica que ya dibuja su forma en 3D
	 * (1 = todas). Las que quedan son chispas y lenguas pequeñas alrededor del volumen.
	 */
	private static float thin = 1f;
	private static final float THIN = 0.1f;
	private static final Vec3d FWD = new Vec3d(0, 0, 1), DOWN = new Vec3d(0, -1, 0);

	private static boolean vol() {
		return MeraVolumes.ready();
	}

	/** Las técnicas que el fuego 3D dibuja como un volumen (las demás siguen con llamas sueltas). */
	private static boolean volumetric(Fx f) {
		return switch (f.kind) {
			case MeraFx.HIKEN_CHARGE, MeraFx.HIKEN_SHOT, MeraFx.HIGAN_BULLET, MeraFx.JUJIKA_FLASH, MeraFx.JUJIKA_SHOT,
				 MeraFx.SHIRANUI, MeraFx.ENTEI_CHARGE, MeraFx.ENTEI_SHOT, MeraFx.ENTEI_BLAST, MeraFx.KYOKAEN, MeraFx.ENJOMO,
				 MeraFx.ENKAI_GATHER, MeraFx.HIBASHIRA, MeraFx.KAGERO, MeraFx.DASH, MeraFx.SCATTER, MeraFx.BODY,
				 MeraFx.HIKYAKU, MeraFx.PUNCH, MeraFx.EMBER_HIT -> true;
			case MeraFx.IMPACT -> f.style != MeraFx.STYLE_STEAM;
			default -> false;
		};
	}

	private static void vol(int shape, Vec3d c, Vec3d dir, float p0, float p1, float p2, Fx f, float intensity) {
		MeraVolumes.add(shape, c, dir, p0, p1, p2, 0f, f.id * 31 + f.kind * 7 + f.userId, intensity);
	}

	/** 0 → 1 suave (smoothstep) entre 0 y 1. */
	private static float smooth(float x) {
		float v = MathHelper.clamp(x, 0f, 1f);
		return v * v * (3f - 2f * v);
	}

	/** Sube rápido y frena (para lo que crece al estallar). */
	private static float easeOut(double u) {
		double v = 1 - MathHelper.clamp(u, 0, 1);
		return (float) (1 - v * v * v);
	}

	private static boolean selfView(MinecraftClient mc, Entity e) {
		return e != null && e == mc.getCameraEntity() && mc.options.getPerspective().isFirstPerson();
	}

	/**
	 * Un estallido contra una pared o el suelo sale hacia fuera de esa cara: centrado en el punto del golpe, media bola
	 * quedaba dentro del bloque y se veía un disco plano pegado a la pared. Al aire (o contra alguien), donde fue.
	 */
	private static Vec3d outOfWall(Fx f, Vec3d d, float radiusNow) {
		if (!f.wallChecked) {
			f.wallChecked = true;
			MinecraftClient mc = MinecraftClient.getInstance();
			if (mc.world != null && mc.player != null) {
				// sin dirección (golpes desde arriba), hacia abajo: el suelo
				Vec3d dir = d.lengthSquared() > 1e-4 && f.b.lengthSquared() > 1e-4 ? d.normalize() : new Vec3d(0, -1, 0);
				BlockHitResult hit = mc.world.raycast(new RaycastContext(f.a.subtract(dir.multiply(1.5)), f.a.add(dir.multiply(0.8)),
					RaycastContext.ShapeType.COLLIDER, RaycastContext.FluidHandling.NONE, mc.player));
				if (hit.getType() == HitResult.Type.BLOCK) {
					Direction side = hit.getSide();
					f.wallP = hit.getPos();
					f.wallN = new Vec3d(side.getOffsetX(), side.getOffsetY(), side.getOffsetZ());
				}
			}
		}
		if (f.wallP == null) return f.a;
		return f.wallP.add(f.wallN.multiply(radiusNow * 0.6));
	}

	/** Los estallidos (IMPACT) en 3D: la bola de fuego que se abre, o la cruz que se abre (Jūjika). */
	private static boolean impactVolume(Fx f, double age) {
		float r = f.radius;
		Vec3d d = f.b.lengthSquared() > 1e-4 ? f.b : FWD;
		switch (f.style) {
			case MeraFx.STYLE_CROSS -> {
				double T = 0.65;
				if (age > T) return false;
				float u = (float) (age / T), e = easeOut(u * 1.4);
				vol(MeraVolumes.CROSS, f.a, d, r * (0.35f + 0.65f * e), r * (0.12f + 0.08f * e), 0, f, (float) Math.pow(1 - u, 1.3) * 1.15f);
			}
			case MeraFx.STYLE_LANCE -> {
				double T = 0.5;
				if (age > T) return false;
				float u = (float) (age / T), e = easeOut(u * 1.3);
				float R = r * (0.25f + 0.45f * e);
				vol(MeraVolumes.FIREBALL, outOfWall(f, d, R), d, R, 0.6f * u, 0, f, (float) Math.pow(1 - u, 1.5) * (1f + 0.3f * Math.max(0f, 1f - u / 0.15f)));
			}
			case MeraFx.STYLE_FIZZLE -> {
				double T = 0.45;
				if (age > T) return false;
				float u = (float) (age / T), e = easeOut(u);
				vol(MeraVolumes.FIREBALL, f.a, d, r * (0.18f + 0.22f * e), 0.8f * u, 0, f, 0.6f * (float) Math.pow(1 - u, 1.5));
			}
			default -> {
				// como una explosión de verdad: destello, se abre de golpe, hierve y sube como un hongo mientras se
				// enfría de fuera hacia dentro y acaba en humo
				// los pequeños (las balas de Higan) duran menos: un fogonazo y una bocanada de humo que se va enseguida;
				// si no, muchos seguidos en el mismo sitio se juntaban en una bola marrón quieta
				boolean small = r < 1.2f;
				double T = (small ? 0.55 : 0.95) + 0.1 * r;
				// los grandes dejan después una nube de humo que sube, se abre y se deshace despacio (el fuego de verdad
				// acaba casi todo en humo); los pequeños, solo la bocanada
				double S = small ? 0 : 1.6 + 0.35 * r;
				if (age > T + S) return false;
				if (age > T) {
					float w = (float) ((age - T) / S);
					float R1 = r * 0.83f;
					float Rs = R1 * (1f + 0.35f * easeOut(w));
					Vec3d c0 = outOfWall(f, d, R1);
					Vec3d cs = c0.add(0, R1 * (0.25f + 0.9f * w) * w, 0);
					vol(MeraVolumes.FIREBALL, cs, FWD, Rs, 1f, 0, f, 1f - smooth((w - 0.2f) / 0.8f));
					return true;
				}
				float u = (float) (age / T), e = easeOut(u * 1.6);
				float cool = smooth((u - 0.12f) / 0.88f);
				float inten = 1f + 0.6f * Math.max(0f, 1f - u / 0.1f);
				if (small) inten *= (1f - smooth((u - 0.72f) / 0.28f)) * (1f - 0.5f * smooth((u - 0.35f) / 0.65f));
				float R = r * (0.28f + 0.55f * e);
				// (los pequeños acaban en una bocanada de humo gris claro y fino: oscuro parecía un agujero quemado)
				MeraVolumes.add(MeraVolumes.FIREBALL, outOfWall(f, d, R), FWD, R, cool, 0, small ? 1f : 0f, f.id * 31 + f.kind * 7 + f.userId, inten);
			}
		}
		return true;
	}

	// ------------------------------------------------------------------ dibujar una llama

	private static float flameTime;

	/** Tamaño de una llama 3D a lo largo de su vida: nace rápido, crece y al final se encoge hasta desaparecer. */
	private static float life3D(int i, float t) {
		float g = t < 0.07f ? t / 0.07f : 1f;
		float shrink = t > 0.45f ? 1f - (t - 0.45f) / 0.55f : 1f;
		return SIZE[i] * (0.6f + 0.6f * (float) Math.sqrt(t)) * g * (float) Math.sqrt(Math.max(0f, shrink));
	}

	// la paleta del fuego de Ace en 3D: núcleo casi blanco, cuerpo naranja-amarillo, borde naranja rojizo
	private static int in3(float t) {
		return Flame3D.lerp(0xFFF7CC, 0xFFCF55, t);
	}

	private static int mid3(float t) {
		return Flame3D.lerp(0xFFB12C, 0xF2701C, t);
	}

	private static int out3(float t) {
		return Flame3D.lerp(0xF0561A, 0xA82410, t);
	}

	/** Una llama como cuerpo 3D (bola en punta o lengua que se retuerce). */
	private static void draw3D(BufferBuilder b, int i) {
		float t = AGE[i] / LIFE[i];
		byte k = KIND[i];
		float x = (float) (PX[i] + ox - camX), y = (float) (PY[i] + oy - camY), z = (float) (PZ[i] + oz - camZ);
		float s = life3D(i, t);
		if (s < 0.012f) return;
		float d = (float) Math.sqrt(x * x + y * y + z * z);
		// detrás de la cámara o pegada a ella: nada (el fuego no tapa la pantalla aunque estés dentro del sol)
		if (x * fx + y * fy + z * fz < -s - 0.5f) return;
		if (d < 2.0f + s * 0.6f) return;
		if (k == CORE && d < 2.6f + s) return;   // dentro del sol de Entei: hueco, se sigue viendo alrededor
		if (d < 4.5f) s *= MathHelper.clamp((d - 2.0f - s * 0.6f) / 2.5f, 0f, 1f) * 0.65f + 0.35f;
		float vx = VX[i], vy = VY[i], vz = VZ[i];
		float sp = (float) Math.sqrt(vx * vx + vy * vy + vz * vz);
		float seed = ROT[i] * 3.1f;
		boolean hi = s > 0.1f;
		switch (k) {
			case TONGUE -> {
				float len = s * (2.1f + Math.min(1.6f, sp * 0.12f));
				Flame3D.tongue(b, x, y, z, s * 0.55f, len, vx, vy + 2.2f, vz, seed, flameTime, in3(t), mid3(t), out3(t));
			}
			case CORE -> Flame3D.blob(b, x, y, z, s * 0.7f, vx, vy + 1.5f, vz, 0.25f, 0.1f, seed, flameTime, hi,
				0xFFFFF0, in3(t), mid3(t));
			case GREEN -> Flame3D.blob(b, x, y, z, s * 0.65f, vx, vy + 1.5f, vz, 0.6f, 0.16f, seed, flameTime, hi,
				greenInner(t), Flame3D.lerp(greenInner(t), greenOuter(t), 0.55f), greenOuter(t));
			default -> {   // FIRE
				float stretch = 0.45f + Math.min(0.45f, sp * 0.05f);
				Flame3D.blob(b, x, y, z, s * 0.6f, vx, vy + 2.4f, vz, stretch, 0.12f, seed, flameTime, hi,
					in3(t), mid3(t), out3(t));
			}
		}
	}

	/** Un resplandor suave y aditivo alrededor de las llamas grandes: el calor que las rodea. */
	private static void bloom(BufferBuilder b, int i) {
		float t = AGE[i] / LIFE[i];
		float x = (float) (PX[i] + ox - camX), y = (float) (PY[i] + oy - camY), z = (float) (PZ[i] + oz - camZ);
		float s = life3D(i, t) * (KIND[i] == CORE ? 2.6f : 2.0f);
		if (s < 0.02f) return;
		if (x * fx + y * fy + z * fz < -0.5f) return;
		float d = (float) Math.sqrt(x * x + y * y + z * z);
		NEAR = MathHelper.clamp((d - s) / (s + 1f), 0f, 1f);   // dentro del resplandor no se ve: no ciega
		if (NEAR <= 0.01f) return;
		quad(b, x, y, z, s, 0f, (byte) 3, KIND[i] == CORE ? 0xFFB040 : 0xFF6A18, (KIND[i] == CORE ? 0.3f : 0.14f) * (1f - t));
	}

	private static void drawPuff(BufferBuilder b, int i) {
		float t = AGE[i] / LIFE[i];
		byte k = KIND[i];
		float x = (float) (PX[i] + ox - camX), y = (float) (PY[i] + oy - camY), z = (float) (PZ[i] + oz - camZ);
		// pegado a la cámara una llama tapa la pantalla entera: se desvanece al acercarse (y detrás no se dibuja)
		float d = (float) Math.sqrt(x * x + y * y + z * z);
		if (x * fx + y * fy + z * fz < -0.5f) return;
		NEAR = MathHelper.clamp((d - 0.35f) / 0.9f, 0f, 1f);
		if (NEAR <= 0.01f) return;
		float s;
		float alpha;
		switch (k) {
			case FIRE, GREEN, CORE -> {
				s = SIZE[i] * (0.55f + 0.75f * (float) Math.sqrt(t));
				if (t > 0.65f) s *= 1f - (t - 0.65f) / 0.35f * 0.7f;
				alpha = t < 0.06f ? t / 0.06f : (t > 0.55f ? Math.max(0f, 1f - (t - 0.55f) / 0.45f) : 1f);
				// dos tonos: el borde (oscuro) y el núcleo (claro) encima, un poco más pequeño
				int outer = k == GREEN ? greenOuter(t) : fireOuter(t);
				int inner = k == GREEN ? greenInner(t) : k == CORE ? hotInner(t) : fireInner(t);
				quad(b, x, y, z, s, ROT[i], CELL[i], outer, alpha * 0.95f);
				quad(b, x, y, z, s * (k == CORE ? 0.7f : 0.58f), ROT[i] + 0.6f, CELL[i], inner, alpha);
			}
			case TONGUE -> {
				s = SIZE[i] * (0.7f + 0.5f * t);
				if (t > 0.6f) s *= 1f - (t - 0.6f) / 0.4f * 0.8f;
				alpha = t < 0.08f ? t / 0.08f : (t > 0.6f ? Math.max(0f, 1f - (t - 0.6f) / 0.4f) : 1f);
				tongueQuad(b, i, x, y, z, s, fireOuter(t), alpha * 0.95f, 1f);
				tongueQuad(b, i, x, y, z, s * 0.6f, fireInner(t), alpha, 0.85f);
			}
			case SMOKE -> {
				s = SIZE[i] * (0.6f + 1.4f * t);
				alpha = (t < 0.15f ? t / 0.15f : 1f) * (1f - t) * 0.46f;
				// (CR: lo oscuro que es este humo; junto a una nube 3D, del mismo gris que ella)
				quad(b, x, y, z, s * 1.25f, ROT[i], CELL[i], CR[i] < 0.99f ? mixRGB(0, smokeRGB, CR[i]) : smokeRGB, alpha * smokeMul);
			}
			case STEAM -> {
				s = SIZE[i] * (0.6f + 1.6f * t);
				alpha = (t < 0.1f ? t / 0.1f : 1f) * (1f - t) * 0.42f;
				quad(b, x, y, z, s * 1.2f, ROT[i], CELL[i], steamRGB, alpha * smokeMul);
			}
			case SPARK -> {
				s = SIZE[i];
				alpha = 1f - t;
				int rgb = rgb(CR[i], CG[i] * (1f - 0.4f * t), CB[i] * (1f - t));
				streak(b, i, x, y, z, s, rgb, alpha);
			}
			default -> {   // GLOW, FLY
				s = SIZE[i] * (k == FLY ? 1f : (0.8f + 0.5f * t));
				if (k == GLOW) NEAR = Math.min(NEAR, MathHelper.clamp((d - s * 0.8f) / (s + 1f), 0f, 1f));
				alpha = (t < 0.1f ? t / 0.1f : 1f) * (1f - t * t);
				quad(b, x, y, z, s, 0f, (byte) 3, rgb(CR[i], CG[i], CB[i]), alpha * 0.85f);
			}
		}
	}

	/** Una llama suelta junto al fuego 3D: una brasa brillante y pequeña que sube (aditiva). */
	private static void ember(BufferBuilder b, int i) {
		byte k = KIND[i];
		if (k != FIRE && k != TONGUE && k != CORE && k != GREEN) return;
		float t = AGE[i] / LIFE[i];
		float x = (float) (PX[i] + ox - camX), y = (float) (PY[i] + oy - camY), z = (float) (PZ[i] + oz - camZ);
		if (x * fx + y * fy + z * fz < -0.5f) return;
		float d = (float) Math.sqrt(x * x + y * y + z * z);
		NEAR = MathHelper.clamp((d - 0.4f) / 1.2f, 0f, 1f);
		if (NEAR <= 0.01f) return;
		float s = MathHelper.clamp(SIZE[i] * 0.28f, 0.035f, 0.11f) * (1f - 0.5f * t);
		float alpha = (t < 0.08f ? t / 0.08f : 1f) * (1f - t);
		int rgb = k == GREEN ? greenInner(t) : (t < 0.4f ? fireInner(t) : fireOuter(t));
		quad(b, x, y, z, s * 2.4f, 0f, (byte) 3, rgb, alpha * 0.35f);
		quad(b, x, y, z, s, 0f, (byte) 3, rgb, alpha);
	}

	/** Rampa del borde: naranja → rojo anaranjado → rojo oscuro. */
	private static int fireOuter(float t) {
		if (t < 0.35f) return lerpRgb(0xFF8A1E, 0xF2521A, t / 0.35f);
		if (t < 0.7f) return lerpRgb(0xF2521A, 0xC0260E, (t - 0.35f) / 0.35f);
		return lerpRgb(0xC0260E, 0x701008, (t - 0.7f) / 0.3f);
	}

	/** Rampa del núcleo: blanco cálido → amarillo → naranja claro. */
	private static int fireInner(float t) {
		if (t < 0.2f) return lerpRgb(0xFFF8E0, 0xFFE15A, t / 0.2f);
		if (t < 0.55f) return lerpRgb(0xFFE15A, 0xFFB030, (t - 0.2f) / 0.35f);
		return lerpRgb(0xFFB030, 0xFF7A20, (t - 0.55f) / 0.45f);
	}

	private static int hotInner(float t) {
		return lerpRgb(0xFFFFF4, 0xFFE890, Math.min(1f, t * 1.4f));
	}

	/** Hotarubi: fuego amarillo verdoso. */
	private static int greenOuter(float t) {
		return lerpRgb(0x9BE83A, 0x5E9E1E, t);
	}

	private static int greenInner(float t) {
		return lerpRgb(0xF4FFB8, 0xD8F070, t);
	}

	private static void quad(BufferBuilder b, float x, float y, float z, float s, float rot, byte cell, int rgb, float a) {
		if (a <= 0.004f || s <= 0.001f) return;
		float c = MathHelper.cos(rot) * s, sn = MathHelper.sin(rot) * s;
		// ejes girados en el plano de la cámara
		float ax = rx * c + ux * sn, ay = ry * c + uy * sn, az = rz * c + uz * sn;
		float bx = -rx * sn + ux * c, by = -ry * sn + uy * c, bz = -rz * sn + uz * c;
		float u0 = (cell & 1) * 0.5f, v0 = (cell >> 1) * 0.5f;
		float u1 = u0 + 0.5f, v1 = v0 + 0.5f;
		vtx(b, x - ax - bx, y - ay - by, z - az - bz, u0, v1, rgb, a);
		vtx(b, x + ax - bx, y + ay - by, z + az - bz, u1, v1, rgb, a);
		vtx(b, x + ax + bx, y + ay + by, z + az + bz, u1, v0, rgb, a);
		vtx(b, x - ax + bx, y - ay + by, z - az + bz, u0, v0, rgb, a);
	}

	/** Lengua de fuego: la punta sigue a la velocidad (en pantalla), la base es redonda. */
	private static void tongueQuad(BufferBuilder b, int i, float x, float y, float z, float s, int rgb, float a, float stretch) {
		if (a <= 0.004f) return;
		float vx = VX[i], vy = VY[i] + 0.8f, vz = VZ[i];
		float along = vx * fx + vy * fy + vz * fz;
		vx -= fx * along;
		vy -= fy * along;
		vz -= fz * along;
		float l = (float) Math.sqrt(vx * vx + vy * vy + vz * vz);
		if (l < 1e-3f) {
			vx = ux;
			vy = uy;
			vz = uz;
			l = 1f;
		}
		vx /= l;
		vy /= l;
		vz /= l;
		// lado = delante × arriba
		float sx = fy * vz - fz * vy, sy = fz * vx - fx * vz, sz = fx * vy - fy * vx;
		float h = s * (1.4f + Math.min(1.2f, l * 0.08f)) * stretch, w = s * 0.75f;
		float bx = x - vx * h * 0.35f, by = y - vy * h * 0.35f, bz = z - vz * h * 0.35f;
		float tx = x + vx * h, ty = y + vy * h, tz = z + vz * h;
		vtx(b, bx - sx * w, by - sy * w, bz - sz * w, 0f, 1f, rgb, a);
		vtx(b, bx + sx * w, by + sy * w, bz + sz * w, 0.5f, 1f, rgb, a);
		vtx(b, tx + sx * w, ty + sy * w, tz + sz * w, 0.5f, 0.5f, rgb, a);
		vtx(b, tx - sx * w, ty - sy * w, tz - sz * w, 0f, 0.5f, rgb, a);
	}

	/** Chispa: un trazo corto en la dirección en que vuela. */
	private static void streak(BufferBuilder b, int i, float x, float y, float z, float s, int rgb, float a) {
		float vx = VX[i] * 0.045f, vy = VY[i] * 0.045f, vz = VZ[i] * 0.045f;
		float sx = fy * vz - fz * vy, sy = fz * vx - fx * vz, sz = fx * vy - fy * vx;
		float l = (float) Math.sqrt(sx * sx + sy * sy + sz * sz);
		if (l < 1e-5f) {
			quad(b, x, y, z, s, 0f, (byte) 3, rgb, a);
			return;
		}
		sx = sx / l * s * 0.5f;
		sy = sy / l * s * 0.5f;
		sz = sz / l * s * 0.5f;
		vtx(b, x - vx - sx, y - vy - sy, z - vz - sz, 0.5f, 1f, rgb, a);
		vtx(b, x - vx + sx, y - vy + sy, z - vz + sz, 1f, 1f, rgb, a);
		vtx(b, x + vx + sx, y + vy + sy, z + vz + sz, 1f, 0.5f, rgb, a);
		vtx(b, x + vx - sx, y + vy - sy, z + vz - sz, 0.5f, 0.5f, rgb, a);
	}

	/** Lo que se desvanece la llama que se está dibujando por estar cerca de la cámara (1 = nada). */
	private static float NEAR = 1f;

	private static void vtx(BufferBuilder b, float x, float y, float z, float u, float v, int rgb, float a) {
		a = MathHelper.clamp(a * NEAR, 0f, 1f);
		if (!plain) {
			BASE.transformPosition(x, y, z, TV);
			x = TV.x;
			y = TV.y;
			z = TV.z;
		}
		b.vertex(x, y, z).texture(u, v).color((rgb >> 16) & 255, (rgb >> 8) & 255, rgb & 255, Math.round(a * 255f));
	}

	private static int rgb(float r, float g, float b) {
		return (Math.round(MathHelper.clamp(r, 0f, 1f) * 255) << 16) | (Math.round(MathHelper.clamp(g, 0f, 1f) * 255) << 8)
			| Math.round(MathHelper.clamp(b, 0f, 1f) * 255);
	}

	private static int lerpRgb(int c0, int c1, float t) {
		t = MathHelper.clamp(t, 0f, 1f);
		int r = Math.round(((c0 >> 16) & 255) + (((c1 >> 16) & 255) - ((c0 >> 16) & 255)) * t);
		int g = Math.round(((c0 >> 8) & 255) + (((c1 >> 8) & 255) - ((c0 >> 8) & 255)) * t);
		int bl = Math.round((c0 & 255) + ((c1 & 255) - (c0 & 255)) * t);
		return (r << 16) | (g << 8) | bl;
	}

	// ------------------------------------------------------------------ dónde está la mano de alguien

	/** La mano (side 1 = principal) de un jugador ahora, en 1.ª persona donde se ve la mano. */
	static Vec3d hand(MinecraftClient mc, Entity e, int side, float td) {
		if (e == mc.getCameraEntity() && mc.options.getPerspective().isFirstPerson()) {
			return new Vec3d(camX + fx * 0.62 + rx * 0.30 * side - ux * 0.24,
				camY + fy * 0.62 + ry * 0.30 * side - uy * 0.24,
				camZ + fz * 0.62 + rz * 0.30 * side - uz * 0.24);
		}
		Vec3d base = e.getLerpedPos(td);
		float by = e instanceof LivingEntity le ? MathHelper.lerp(td, le.prevBodyYaw, le.bodyYaw) : e.getYaw();
		double yr = Math.toRadians(by);
		double rX = -Math.cos(yr), rZ = -Math.sin(yr);
		double fX = -Math.sin(yr), fZ = Math.cos(yr);
		double sh = e.isInSneakingPose() ? 1.12 : 1.38;
		Vec3d shoulder = base.add(rX * 0.36 * side, sh, rZ * 0.36 * side);
		int pose = MeraAnim.currentPose(e.getId());
		Vec3d dir;
		if (pose == MeraAnim.RAISE) dir = new Vec3d(0, 1, 0);
		else if (pose == MeraAnim.WINDUP) dir = new Vec3d(-fX * 0.55 + rX * 0.2 * side, -0.6, -fZ * 0.55 + rZ * 0.2 * side).normalize();
		else if (pose == MeraAnim.NONE || pose == MeraAnim.HOP) dir = new Vec3d(0, -1, 0);
		else dir = e.getRotationVec(td);
		return shoulder.add(dir.multiply(0.68));
	}

	static Vec3d shoulder(Entity e, int side, float td) {
		Vec3d base = e.getLerpedPos(td);
		float by = e instanceof LivingEntity le ? MathHelper.lerp(td, le.prevBodyYaw, le.bodyYaw) : e.getYaw();
		double yr = Math.toRadians(by);
		return base.add(-Math.cos(yr) * 0.36 * side, e.isInSneakingPose() ? 1.12 : 1.38, -Math.sin(yr) * 0.36 * side);
	}

	// ------------------------------------------------------------------ los emisores

	/** Suelta las llamas de un efecto. Devuelve false cuando ya no le queda nada. */
	private static boolean emit(MinecraftClient mc, Fx f, double now, double dt, float td, float density) {
		double age = f.age(now);
		Entity user = f.userId >= 0 ? mc.world.getEntityById(f.userId) : null;
		switch (f.kind) {
			case MeraFx.HIKEN_CHARGE -> {
				if (f.ended || age > f.dur || user == null) return false;
				// el puño se envuelve en fuego que crece con la carga
				float k = (float) Math.min(1.0, age / 1.5);
				Vec3d h = hand(mc, user, 1, td);
				Vec3d sh = shoulder(user, 1, td);
				int n = many(f, (90 + 320 * k) * density, dt);
				for (int i = 0; i < n; i++) {
					double r = 0.12 + 0.28 * k;
					tongue(h.x + rs() * r, h.y + rs() * r, h.z + rs() * r, rs() * 0.4, 0.8 + R.nextFloat() * 1.2, rs() * 0.4,
						0.12f + 0.16f * k, 0.25f + R.nextFloat() * 0.2f);
				}
				// el antebrazo también arde
				int n2 = many2(f, 60 * k * density, dt);
				for (int i = 0; i < n2; i++) {
					double u = R.nextDouble();
					fire(MathHelper.lerp(u, sh.x, h.x), MathHelper.lerp(u, sh.y, h.y), MathHelper.lerp(u, sh.z, h.z),
						rs() * 0.3, 0.6, rs() * 0.3, 0.09f + 0.06f * k, 0.3f);
				}
				if (R.nextFloat() < dt * 25 * k) glow(h.x, h.y, h.z, 0, 0, 0, 0.6f + 0.6f * k, 0.25f, 1f, 0.6f, 0.2f);
				if (R.nextFloat() < dt * 10 * k) spark(h.x, h.y, h.z, rs() * 3, 2 + R.nextFloat() * 3, rs() * 3, 0.5f);
				if (vol()) {
					// el puño envuelto en una llama que sube, y el antebrazo ardiendo
					vol(MeraVolumes.FLAME, h.add(0, -0.1 - 0.1 * k, 0), FWD, 0.14f + 0.2f * k, 0.5f + 0.9f * k, 0, f, 0.55f + 0.45f * k);
					if (!selfView(mc, user)) {
						Vec3d arm = h.subtract(sh);
						vol(MeraVolumes.JET, sh, arm, (float) arm.length(), 0.1f + 0.12f * k, 0, f, 0.3f + 0.45f * k);
					}
				}
				return true;
			}
			case MeraFx.HIKEN_SHOT -> {
				return emitHiken(mc, f, user, now, age, dt, td, density);
			}
			case MeraFx.IMPACT -> {
				if (f.last == null) {
					burst(f, f.a, f.b, f.radius, f.power, f.style);
					f.last = f.a;
				}
				if (vol() && f.style != MeraFx.STYLE_STEAM) return impactVolume(f, age);
				return age < 0.1;
			}
			case MeraFx.HIGAN_BULLET -> {
				Vec3d head = shotHead(f, now, 0);
				if (head == null) return false;
				if (f.last == null) {
					// fogonazo en la punta del dedo
					for (int i = 0; i < 6; i++) fire(f.a.x, f.a.y, f.a.z, f.b.x * 4 + rs(), f.b.y * 4 + rs(), f.b.z * 4 + rs(), 0.12f, 0.12f);
					glow(f.a.x, f.a.y, f.a.z, 0, 0, 0, 0.5f, 0.1f, 1f, 0.8f, 0.4f);
					f.last = f.a;
				}
				trail(f, head, 0.09f, 0.18f, 18, dt, density, true);
				core(head.x, head.y, head.z, 0, 0, 0, 0.16f, 0.06f);
				// la lanza va centrada: la punta queda en la cabeza de la bala
				if (vol()) vol(MeraVolumes.LANCE, head.subtract(f.b.normalize().multiply(0.6)), f.b, 0.8f, 0.075f, 0, f, 1f);
				glow(head.x, head.y, head.z, 0, 0, 0, 0.45f, 0.05f, 1f, 0.7f, 0.3f);
				f.last = head;
				return true;
			}
			case MeraFx.JUJIKA_FLASH -> {
				if (age > f.dur + 0.1) return false;
				// la cruz de luz delante de las manos antes de la cruz de fuego
				Vec3d dir = f.b.normalize();
				Vec3d[] ax = axes(dir);
				float u = (float) MathHelper.clamp(age / f.dur, 0, 1);
				float len = 0.4f + 1.6f * u;
				if (vol()) {
					vol(MeraVolumes.CROSS, f.a, dir, len * 0.9f, 0.06f + 0.03f * u, 0, f, 1.3f);
					return true;
				}
				int n = many(f, 260 * density, dt);
				for (int i = 0; i < n; i++) {
					boolean vert = R.nextBoolean();
					double s = rs() * len;
					Vec3d p = f.a.add((vert ? ax[1] : ax[0]).multiply(s));
					glow(p.x, p.y, p.z, 0, 0, 0, 0.18f + 0.1f * (1 - Math.abs((float) s) / len), 0.12f, 1f, 0.95f, 0.75f);
				}
				return true;
			}
			case MeraFx.JUJIKA_SHOT -> {
				Vec3d head = shotHead(f, now, 0);
				if (head == null) return false;
				Vec3d dir = f.b.normalize();
				Vec3d[] ax = axes(dir);
				float r = f.radius;
				int n = many(f, 900 * density, dt);
				for (int i = 0; i < n; i++) {
					boolean vert = R.nextBoolean();
					double s = rs() * r * 1.5;
					Vec3d p = head.add((vert ? ax[1] : ax[0]).multiply(s)).add(dir.multiply(-R.nextDouble() * 1.2));
					double w = 0.18;
					fire(p.x + rs() * w, p.y + rs() * w, p.z + rs() * w, -dir.x * 3 + rs() * 0.5, -dir.y * 3 + rs() * 0.5, -dir.z * 3 + rs() * 0.5,
						0.32f + R.nextFloat() * 0.2f, 0.28f + R.nextFloat() * 0.15f);
				}
				for (int i = 0; i < 2; i++) {
					core(head.x, head.y, head.z, 0, 0, 0, r * 0.6f, 0.08f);
				}
				if (vol()) vol(MeraVolumes.CROSS, head, dir, r * 1.5f, r * 0.3f, 0, f, 1f);
				if (R.nextFloat() < dt * 30) glow(head.x, head.y, head.z, 0, 0, 0, r * 2.2f, 0.12f, 1f, 0.65f, 0.25f);
				return true;
			}
			case MeraFx.SHIRANUI -> {
				return emitLance(mc, f, user, now, age, dt, td, density);
			}
			case MeraFx.ENTEI_CHARGE -> {
				if (f.ended || age > f.dur || user == null) return false;
				float r = 1f + 5f * (float) Math.min(1.0, age / 3.5);
				Vec3d base = user.getLerpedPos(td);
				// la misma altura que MeraPowers.sunPos
				Vec3d sun = base.add(0, user.getHeight() + 1.6 + r * 1.15, 0);
				sun(f, sun, r, dt, density, now, false);
				if (vol()) {
					vol(MeraVolumes.FIREBALL, sun, FWD, r, 0, 0, f, 1f);
					// en primera persona, las llamas que cuelgan del sol no se pegan a la cara
					if (selfView(mc, user)) MeraVolumes.nearFade(2.2f);
					Vec3d hh = hand(mc, user, 1, td);
					Vec3d to = sun.subtract(hh);
					double L = to.length() - r * 0.7;
					if (L > 0.6 && !selfView(mc, user)) vol(MeraVolumes.JET, hh, to, (float) L, 0.3f + 0.08f * r, 0, f, 0.75f);
				}
				// los hilos de fuego que suben de la mano al sol y de alrededor hacia él
				Vec3d h = hand(mc, user, 1, td);
				int n = many2(f, 140 * density, dt);
				for (int i = 0; i < n; i++) {
					if (R.nextFloat() < 0.45f) {
						double u = R.nextDouble();
						Vec3d p = h.lerp(sun, u);
						fire(p.x + rs() * 0.2, p.y, p.z + rs() * 0.2, rs() * 0.5, 3.5, rs() * 0.5, 0.16f, 0.35f);
					} else {
						// de lejos, en espiral hacia el sol
						double ang = R.nextDouble() * Math.PI * 2, dist = r + 4 + R.nextDouble() * 6;
						Vec3d p = sun.add(Math.cos(ang) * dist, rs() * 3 - 2, Math.sin(ang) * dist);
						Vec3d to = sun.subtract(p).normalize().multiply(7 + dist * 0.5);
						tongue(p.x, p.y, p.z, to.x - Math.sin(ang) * 3, to.y, to.z + Math.cos(ang) * 3, 0.22f, (float) (dist / (7 + dist * 0.5)) * 0.9f);
					}
				}
				return true;
			}
			case MeraFx.ENTEI_SHOT -> {
				Vec3d head = shotHead(f, now, 0);
				if (head == null) return false;
				sun(f, head, f.radius, dt, density, now, true);
				// lanzado: las llamas se van hacia atrás, como una bola de fuego que vuela
				if (vol()) {
					vol(MeraVolumes.FIREBALL, head, f.b, f.radius, 0, 0.85f, f, 1f);
					// en primera persona el sol sale por encima de la cara: lo que pasa pegado a la cámara se aclara (se
					// ve el sol alejándose, no la pantalla lavada de naranja)
					if (selfView(mc, user)) MeraVolumes.nearFade(Math.max(2.2f, f.radius * 1.1f));
				}
				return true;
			}
			case MeraFx.ENTEI_BLAST -> {
				return emitEnteiBlast(f, age, dt, density);
			}
			case MeraFx.HOTARUBI -> {
				return emitFireflies(mc, f, now, age, dt, density);
			}
			case MeraFx.HIDARUMA -> {
				double travel = f.dur;
				if (age > travel + 0.05) return false;
				Fx group = FIREFLIES.get(f.id);
				Vec3d from = group != null ? fireflyPos(group, f.style, f.endAt > 0 ? f.endAt : f.start) : f.a;
				// acelera hacia el blanco, con estela verde que se vuelve naranja
				double u = MathHelper.clamp(age / travel, 0, 1);
				double e = u * u * (3 - 2 * u);
				Vec3d mid = from.lerp(f.b, 0.5).add(0, 1.2, 0);
				Vec3d p1 = from.lerp(mid, e), p2 = mid.lerp(f.b, e);
				Vec3d p = p1.lerp(p2, e);
				int n = many(f, 140 * density, dt);
				for (int i = 0; i < n; i++) {
					spawn(u < 0.6 ? GREEN : FIRE, p.x + rs() * 0.06, p.y + rs() * 0.06, p.z + rs() * 0.06, rs() * 0.3, 0.3, rs() * 0.3, 0.13f, 0.22f);
				}
				glow(p.x, p.y, p.z, 0, 0, 0, 0.5f, 0.06f, 0.85f, 1f, 0.4f);
				return true;
			}
			case MeraFx.KYOKAEN -> {
				return emitWall(f, now, age, dt, density);
			}
			case MeraFx.ENJOMO -> {
				return emitRing(f, now, age, dt, density);
			}
			case MeraFx.ENKAI_GATHER -> {
				if (age > f.dur || user == null) return false;
				// un remolino de llamas que gira alrededor de Ace y se va cerrando
				Vec3d base = user.getLerpedPos(td);
				float u = (float) (age / f.dur);
				// en primera persona el remolino va por la cintura y no se cierra del todo: a la altura de los ojos y a
				// un bloque de la cara, el fuego se veía borroso y tapaba casi toda la pantalla
				boolean self = selfView(mc, user);
				float ringR = (float) (f.radius * (1.2 - 0.8 * u));
				if (self) ringR = Math.max(ringR, 2.1f);
				if (vol()) {
					vol(MeraVolumes.RING, base, FWD, ringR, self ? 0.95f : 2.6f, self ? 0.4f : 0.5f, f, self ? 0.75f : 0.85f);
					if (self) MeraVolumes.nearFade(1.4f);
				}
				int n = many(f, (self ? 300 : 520) * density, dt);
				for (int i = 0; i < n; i++) {
					double ang = R.nextDouble() * Math.PI * 2 + age * 9;
					double rad = ringR + rs() * 0.3;
					double y = R.nextDouble() * (self ? 1.2 : 2.4);
					double tx = -Math.sin(ang) * 6, tz = Math.cos(ang) * 6;
					tongue(base.x + Math.cos(ang) * rad, base.y + y, base.z + Math.sin(ang) * rad, tx - Math.cos(ang) * 2, 0.6, tz - Math.sin(ang) * 2,
						0.2f + 0.1f * u, 0.3f);
				}
				return true;
			}
			case MeraFx.HIBASHIRA -> {
				return emitPillar(f, age, dt, density, selfView(mc, user));
			}
			case MeraFx.KAGERO -> {
				if (f.ended || age > f.dur + 0.1 || user == null) return false;
				Vec3d h = hand(mc, user, 1, td);
				Vec3d dir = user.getRotationVec(td);
				if (vol()) {
					float grow = easeOut(age / 0.18);
					if (selfView(mc, user)) {
						// en primera persona el chorro sale de abajo a la derecha y va hacia donde apuntas: cruza la
						// pantalla en diagonal como un lanzallamas (si fuese recto delante de los ojos se vería como una bola)
						Vec3d start = new Vec3d(camX + fx * 0.85 + rx * 0.42 - ux * 0.40, camY + fy * 0.85 + ry * 0.42 - uy * 0.40,
							camZ + fz * 0.85 + rz * 0.42 - uz * 0.40);
						Vec3d aim = new Vec3d(camX, camY, camZ).add(dir.multiply(0.6 + 8.8 * grow));
						Vec3d jd = aim.subtract(start);
						vol(MeraVolumes.JET, start, jd, (float) jd.length(), 1.9f, 0, f, 1f);
						MeraVolumes.nearFade(0.9f);
					} else {
						vol(MeraVolumes.JET, h, dir, 9.5f * grow, 1.9f, 0, f, 1f);
					}
				}
				int n = many(f, 620 * density, dt);
				for (int i = 0; i < n; i++) {
					double spread = 0.16;
					Vec3d v = dir.add(rs() * spread, rs() * spread, rs() * spread).normalize().multiply(14 + R.nextDouble() * 6);
					double o = R.nextDouble() * 0.4;
					float life = 0.42f + R.nextFloat() * 0.25f;
					if (R.nextFloat() < 0.6f) fire(h.x + v.x * o * 0.05, h.y + v.y * o * 0.05, h.z + v.z * o * 0.05, v.x, v.y, v.z, 0.16f + R.nextFloat() * 0.14f, life);
					else tongue(h.x, h.y, h.z, v.x, v.y, v.z, 0.2f, life * 0.9f);
				}
				if (R.nextFloat() < dt * 40) glow(h.x, h.y, h.z, 0, 0, 0, 0.7f, 0.12f, 1f, 0.75f, 0.35f);
				if (R.nextFloat() < dt * 25) smoke(h.x + dir.x * 8, h.y + dir.y * 8 + 0.5, h.z + dir.z * 8, dir.x * 2, 1, dir.z * 2, 0.6f, 1.4f);
				return true;
			}
			case MeraFx.DASH -> {
				if (age > f.dur + 0.35 || user == null) return false;
				Vec3d pos = user.getLerpedPos(td).add(0, 0.9, 0);
				// la estela de fuego por donde pasó (desde donde arrancó, hasta 6 bloques) y que se apaga al acabar:
				// antes era solo el tramo del último fotograma y con muchos FPS casi no se veía
				if (vol() && !selfView(mc, user)) {
					Vec3d back = f.a.add(0, 0.9, 0).subtract(pos);
					float bl = (float) back.length();
					if (bl > 0.6f) {
						float fade = age <= f.dur ? 1f : (float) Math.max(0.0, 1.0 - (age - f.dur) / 0.35);
						MeraVolumes.add(MeraVolumes.JET, pos, back.multiply(1.0 / bl), Math.min(bl, 6f), 0.45f, 0, 0, f.id * 31 + 5, 0.7f * fade);
					}
				}
				if (age <= f.dur) {
					// el cuerpo es un cometa de fuego
					Vec3d dir = f.b.subtract(f.a);
					dir = dir.lengthSquared() < 1e-4 ? new Vec3d(0, 0, 1) : dir.normalize();
					Vec3d from = f.last != null ? f.last : pos;
					int n = many(f, 900 * density, dt);
					for (int i = 0; i < n; i++) {
						Vec3d p = from.lerp(pos, R.nextDouble());
						double r = 0.35;
						if (R.nextFloat() < 0.6f) fire(p.x + rs() * r, p.y + rs() * r * 1.6, p.z + rs() * r, -dir.x * 4 + rs(), -dir.y * 4 + rs(), -dir.z * 4 + rs(), 0.32f, 0.35f);
						else tongue(p.x + rs() * r, p.y + rs() * r, p.z + rs() * r, -dir.x * 6, -dir.y * 6, -dir.z * 6, 0.3f, 0.3f);
					}
					core(pos.x, pos.y, pos.z, 0, 0, 0, 0.55f, 0.06f);
					if (vol() && !selfView(mc, user)) vol(MeraVolumes.FIST, pos, dir, 0.55f, 2.8f, 0, f, 1f);
					f.last = pos;
				} else if (f.acc2 == 0) {
					// vuelve a tomar forma: las llamas se juntan en el cuerpo
					f.acc2 = 1;
					for (int i = 0; i < 70 * density; i++) {
						double ang = R.nextDouble() * Math.PI * 2;
						double y = R.nextDouble() * 1.9 - 0.9;
						Vec3d p = pos.add(Math.cos(ang) * 1.1, y, Math.sin(ang) * 1.1);
						fire(p.x, p.y, p.z, -Math.cos(ang) * 4, 0.5, -Math.sin(ang) * 4, 0.22f, 0.25f);
					}
				}
				return true;
			}
			case MeraFx.SCATTER -> {
				if (f.last == null) {
					f.last = f.a;
					scatterBurst(f.a, f.b, f.radius, density);
				}
				if (vol()) vol(MeraVolumes.FIREBALL, f.a, FWD, f.radius * (0.35f + 0.45f * easeOut(age / 0.3)), (float) (age / 0.3) * 0.7f, 0, f, 0.85f * (float) (1 - age / 0.3));
				// las llamas que vuelven a cerrar el hueco
				if (age > 0.12 && f.acc2 == 0) {
					f.acc2 = 1;
					for (int i = 0; i < 26 * density; i++) {
						Vec3d off = new Vec3d(rs(), rs(), rs()).normalize().multiply(0.6 * f.radius + 0.3);
						Vec3d p = f.a.add(off);
						fire(p.x, p.y, p.z, -off.x * 4, -off.y * 4, -off.z * 4, 0.16f, 0.2f);
					}
				}
				return age < 0.3;
			}
			case MeraFx.BODY -> {
				// la llamarada al encenderse el cuerpo de llamas
				if (f.last == null) {
					f.last = f.a;
					Entity e = user;
					Vec3d c = e != null ? e.getLerpedPos(td) : f.a;
					// en 1.ª persona la llamarada sale a ras de suelo y hacia fuera: se ve el anillo, no una pared de fuego
					boolean self = e == mc.getCameraEntity() && mc.options.getPerspective().isFirstPerson();
					for (int i = 0; i < (self ? 70 : 160) * density; i++) {
						double ang = R.nextDouble() * Math.PI * 2;
						double sp = (self ? 4 : 2) + R.nextDouble() * 4;
						tongue(c.x + Math.cos(ang) * (self ? 0.9 : 0.3), c.y + R.nextDouble() * (self ? 0.5 : 1.8), c.z + Math.sin(ang) * (self ? 0.9 : 0.3),
							Math.cos(ang) * sp, self ? 0.6 + R.nextDouble() : 2 + R.nextDouble() * 3, Math.sin(ang) * sp, 0.3f, self ? 0.35f : 0.5f);
					}
					glow(c.x, c.y + 1, c.z, 0, 0, 0, 3.2f, 0.4f, 1f, 0.55f, 0.15f);
				}
				if (vol() && age < 0.5) {
					Vec3d c = user != null ? user.getLerpedPos(td) : f.a;
					float u = (float) (age / 0.5);
					vol(MeraVolumes.RING, c, FWD, 0.5f + 2.6f * easeOut(u), 1.8f * (1 - u) + 0.4f, 0.35f, f, (selfView(mc, user) ? 0.55f : 1f) * (1 - u));
					return true;
				}
				return false;
			}
			case MeraFx.HIKYAKU -> {
				if (f.last == null) {
					f.last = f.a;
					Vec3d c = f.a;
					for (int i = 0; i < 90 * density; i++) {
						double ang = R.nextDouble() * Math.PI * 2;
						double sp = 3 + R.nextDouble() * 4;
						fire(c.x + Math.cos(ang) * 0.3, c.y + 0.1, c.z + Math.sin(ang) * 0.3, Math.cos(ang) * sp, R.nextDouble() * 0.6, Math.sin(ang) * sp, 0.3f, 0.35f);
					}
					for (int i = 0; i < 30 * density; i++) fire(c.x + rs() * 0.2, c.y, c.z + rs() * 0.2, rs() * 1.5, -2 - R.nextDouble() * 4, rs() * 1.5, 0.25f, 0.3f);
					glow(c.x, c.y + 0.2, c.z, 0, 0, 0, 2.2f, 0.25f, 1f, 0.6f, 0.2f);
				}
				if (vol() && age < 0.4) {
					float u = (float) (age / 0.4);
					vol(MeraVolumes.RING, f.a, FWD, 0.3f + 2.2f * easeOut(u), 0.9f * (1 - u) + 0.3f, 0.3f, f, 1 - u);
				}
				// fuego en los pies mientras sube
				if (age < 0.5 && user != null) {
					Vec3d feet = user.getLerpedPos(td);
					if (vol()) vol(MeraVolumes.JET, feet.add(0, 0.1, 0), DOWN, 1.7f, 0.5f, 0, f, (float) (1 - age / 0.5));
					int n = many(f, 160 * density, dt);
					for (int i = 0; i < n; i++) fire(feet.x + rs() * 0.25, feet.y + 0.05, feet.z + rs() * 0.25, rs() * 0.6, -3 - R.nextDouble() * 2, rs() * 0.6, 0.18f, 0.3f);
				}
				return age < 0.5;
			}
			case MeraFx.STEAM -> {
				if (f.last == null) {
					f.last = f.a;
					int n = (int) (30 * f.radius * density) + 6;
					for (int i = 0; i < n; i++) {
						steam(f.a.x + rs() * f.radius * 0.5, f.a.y + rs() * 0.5, f.a.z + rs() * f.radius * 0.5, rs() * 0.6, 1 + R.nextDouble() * 1.5, rs() * 0.6,
							0.35f + f.radius * 0.15f, 1.2f + R.nextFloat());
					}
				}
				return false;
			}
			case MeraFx.EMBER_HIT, MeraFx.PUNCH -> {
				if (f.last == null) {
					f.last = f.a;
					int n = (int) ((f.kind == MeraFx.PUNCH ? 30 : 22) * f.radius * density) + 4;
					Vec3d d = f.b.lengthSquared() > 1e-4 ? f.b.normalize() : Vec3d.ZERO;
					for (int i = 0; i < n; i++) {
						fire(f.a.x + rs() * 0.25, f.a.y + rs() * 0.3, f.a.z + rs() * 0.25, d.x * 3 + rs() * 2, d.y * 3 + rs() * 2 + 1, d.z * 3 + rs() * 2,
							0.16f + 0.1f * f.radius, 0.3f);
					}
					for (int i = 0; i < 6; i++) spark(f.a.x, f.a.y, f.a.z, rs() * 5 + d.x * 4, R.nextDouble() * 4, rs() * 5 + d.z * 4, 0.45f);
				}
				if (vol() && age < 0.28) {
					float u = (float) (age / 0.28);
					vol(MeraVolumes.FIREBALL, f.a, FWD, (0.25f + 0.2f * f.radius) * (0.6f + 0.6f * easeOut(u)), 0.5f * u, 0, f,
						(1f + 0.35f * Math.max(0f, 1f - u / 0.25f)) * (1 - u));
					return true;
				}
				return false;
			}
			case MeraFx.HAKI_HIT -> {
				if (f.last == null) {
					f.last = f.a;
					// el Haki aplasta las llamas: un destello oscuro y chispas
					for (int i = 0; i < 18; i++) smoke(f.a.x + rs() * 0.2, f.a.y + rs() * 0.2, f.a.z + rs() * 0.2, rs() * 2, rs() * 2, rs() * 2, 0.25f, 0.4f);
					for (int i = 0; i < 10; i++) spark(f.a.x, f.a.y, f.a.z, rs() * 6, rs() * 6, rs() * 6, 0.3f);
				}
				return false;
			}
			case MeraFx.CHARGE_CANCEL -> {
				if (f.last == null && user != null) {
					f.last = f.a;
					Vec3d c = user.getLerpedPos(td).add(0, f.style == 1 ? user.getHeight() + 2 : 1.2, 0);
					for (int i = 0; i < 20; i++) smoke(c.x + rs() * 0.4, c.y + rs() * 0.4, c.z + rs() * 0.4, rs(), 1.2, rs(), 0.4f, 1.2f);
				}
				return false;
			}
			default -> {
				return false;
			}
		}
	}

	private static int many2(Fx f, float rate, double dt) {
		f.acc2 += (float) (rate * dt);
		int n = (int) f.acc2;
		f.acc2 -= n;
		return n;
	}

	/** Dónde va la cabeza de un disparo ahora (o null si ya se acabó y se apagó). */
	private static Vec3d shotHead(Fx f, double now, int delayTicks) {
		if (f.ended) return null;
		double ticks = (now - f.start) * 20.0 - delayTicks;
		if (ticks > f.dur * 20.0 + 1) return null;
		ticks = Math.max(0, Math.min(ticks, f.dur * 20.0));
		return f.a.add(f.b.multiply(ticks));
	}

	/** La estela entre la última posición y la de ahora. */
	private static void trail(Fx f, Vec3d head, float size, float life, float rate, double dt, float density, boolean fast) {
		Vec3d from = f.last != null ? f.last : f.a;
		double len = from.distanceTo(head);
		int n = (int) Math.max(1, len * rate * density);
		for (int i = 0; i < n; i++) {
			Vec3d p = from.lerp(head, R.nextDouble());
			fire(p.x + rs() * 0.05, p.y + rs() * 0.05, p.z + rs() * 0.05, rs() * 0.4, 0.3, rs() * 0.4, size * (0.8f + R.nextFloat() * 0.4f), life);
		}
	}

	/** Dos ejes perpendiculares a una dirección: [0] horizontal, [1] «arriba». */
	private static Vec3d[] axes(Vec3d dir) {
		Vec3d side = dir.crossProduct(new Vec3d(0, 1, 0));
		if (side.lengthSquared() < 1e-4) side = new Vec3d(1, 0, 0);
		side = side.normalize();
		Vec3d up = side.crossProduct(dir).normalize();
		return new Vec3d[]{side, up};
	}

	// --- Hiken

	private static boolean emitHiken(MinecraftClient mc, Fx f, Entity user, double now, double age, double dt, float td, float density) {
		Vec3d head = shotHead(f, now, 0);
		Vec3d dir = f.b.normalize();
		float r = f.radius;
		if (head == null) {
			// el impacto ya llegó: la columna que une el brazo con el golpe se deshace un instante
			return f.endAt > 0 && now - f.endAt < 0.25 && emitHikenColumn(mc, f, user, f.endPos != null ? f.endPos : f.a, dir, r, dt, td, density * 0.5f, age);
		}
		Vec3d[] ax = axes(dir);
		Vec3d side = ax[0], up = ax[1];
		// el puño: la palma (un elipsoide), cuatro nudillos delante y el pulgar
		int n = many(f, (1100 + 600 * f.power) * density, dt);
		for (int i = 0; i < n; i++) {
			int part = R.nextInt(10);
			Vec3d p;
			float size;
			if (part < 5) {
				// palma
				p = head.add(dir.multiply(-0.25 * r + rs() * 0.35 * r)).add(side.multiply(rs() * 0.55 * r)).add(up.multiply(rs() * 0.45 * r));
				size = 0.30f * r;
			} else if (part < 9) {
				// nudillos
				int k = R.nextInt(4);
				double s = (-0.45 + 0.3 * k) * r;
				p = head.add(dir.multiply(0.35 * r)).add(side.multiply(s)).add(up.multiply(0.22 * r)).add(new Vec3d(rs(), rs(), rs()).multiply(0.12 * r));
				size = 0.2f * r;
			} else {
				// pulgar
				p = head.add(dir.multiply(0.1 * r)).add(side.multiply(0.55 * r)).add(up.multiply(-0.2 * r)).add(new Vec3d(rs(), rs(), rs()).multiply(0.1 * r));
				size = 0.18f * r;
			}
			// las llamas se quedan atrás: el puño deja una columna de fuego
			double back = 6 + R.nextDouble() * 4;
			if (R.nextFloat() < 0.75f) fire(p.x, p.y, p.z, -dir.x * back + rs() * 1.5, -dir.y * back + rs() * 1.5 + 0.5, -dir.z * back + rs() * 1.5,
				size * (0.7f + R.nextFloat() * 0.6f), 0.28f + R.nextFloat() * 0.22f);
			else tongue(p.x, p.y, p.z, -dir.x * back * 1.3 + rs(), -dir.y * back * 1.3 + rs(), -dir.z * back * 1.3 + rs(), size, 0.3f);
		}
		if (vol()) {
			// el puño de fuego en 3D, con la cola de llamas que deja detrás
			float tail = (float) Math.min(head.distanceTo(f.a) + 0.8, 2.4 * r);
			vol(MeraVolumes.FIST, head, dir, 0.72f * r, tail, 0, f, 1f);
		}
		// núcleo blanco y resplandor
		core(head.x, head.y, head.z, 0, 0, 0, 0.55f * r, 0.05f);
		if (R.nextFloat() < dt * 40) glow(head.x, head.y, head.z, 0, 0, 0, r * 2.6f, 0.15f, 1f, 0.55f, 0.18f);
		if (R.nextFloat() < dt * 30) spark(head.x, head.y, head.z, rs() * 6 - dir.x * 8, rs() * 6, rs() * 6 - dir.z * 8, 0.5f);
		// humo que se queda en el camino
		if (R.nextFloat() < dt * 18 * density) smoke(head.x - dir.x * r, head.y - dir.y * r + 0.3, head.z - dir.z * r, rs(), 1.2, rs(), r * 0.6f, 1.6f);
		// la columna que sigue unida al brazo los primeros instantes, como en el anime
		if (age < 0.45) emitHikenColumn(mc, f, user, head, dir, r, dt, td, density, age);
		f.last = head;
		return true;
	}

	private static boolean emitHikenColumn(MinecraftClient mc, Fx f, Entity user, Vec3d head, Vec3d dir, float r, double dt, float td, float density, double age) {
		if (user == null) return false;
		Vec3d h = hand(mc, user, 1, td);
		double len = h.distanceTo(head);
		if (len < 0.3) return true;
		float fade = (float) MathHelper.clamp(1.0 - age / 0.45, 0.2, 1.0);
		if (vol()) {
			float gone = f.endAt > 0 ? (float) MathHelper.clamp(1 - (RoomFxClient.time() - f.endAt) / 0.25, 0, 1) : 1f;
			if (selfView(mc, user)) {
				// en primera persona la columna sale de abajo a la derecha hacia el puño (recta delante de los ojos
				// taparía el puño como una bola) y se aclara junto a la cámara
				Vec3d start = new Vec3d(camX + fx * 0.85 + rx * 0.42 - ux * 0.40, camY + fy * 0.85 + ry * 0.42 - uy * 0.40,
					camZ + fz * 0.85 + rz * 0.42 - uz * 0.40);
				Vec3d cd = head.subtract(start);
				if (cd.lengthSquared() > 0.25) {
					vol(MeraVolumes.JET, start, cd, (float) cd.length(), r * 0.42f, 0.35f, f, 0.85f * fade * gone);
					MeraVolumes.nearFade(0.9f);
				}
			} else {
				// la columna del Hiken es casi igual de gruesa en todo el largo (no un chorro que se abre)
				vol(MeraVolumes.JET, h, head.subtract(h), (float) len, r * 0.42f, 0.6f, f, 0.85f * fade * gone);
			}
		}
		int n = many2(f, (float) (len * 60 * density * fade), dt);
		for (int i = 0; i < n; i++) {
			double u = R.nextDouble();
			Vec3d p = h.lerp(head, u);
			double w = r * (0.25 + 0.5 * u);
			fire(p.x + rs() * w, p.y + rs() * w, p.z + rs() * w, rs() * 0.8, 0.8, rs() * 0.8, (float) (0.18 + 0.35 * u * r), 0.25f);
		}
		return true;
	}

	// --- Shiranui

	private static boolean emitLance(MinecraftClient mc, Fx f, Entity user, double now, double age, double dt, float td, float density) {
		int delay = Math.round(f.power);
		Vec3d dir = f.b.normalize();
		double flyTicks = (now - f.start) * 20.0 - delay;
		Vec3d head;
		float form;
		if (flyTicks < 0) {
			head = f.a;
			form = (float) MathHelper.clamp((now - f.start) * 20.0 / Math.max(1, delay), 0, 1);
		} else {
			head = shotHead(f, now, delay);
			form = 1f;
		}
		if (head == null) return false;
		double len = 3.6 * form;
		// la lanza: un asta de fuego con punta, que se forma desde el centro
		int n = many(f, 520 * density, dt);
		for (int i = 0; i < n; i++) {
			double u = R.nextDouble();
			Vec3d p = head.add(dir.multiply((u - 0.75) * len));
			double w = 0.08 + 0.14 * (1 - Math.abs(u - 0.45) * 1.6);
			float life = flyTicks < 0 ? 0.18f : 0.24f;
			double back = flyTicks < 0 ? 0 : 5;
			fire(p.x + rs() * w, p.y + rs() * w, p.z + rs() * w, -dir.x * back + rs() * 0.4, -dir.y * back + rs() * 0.4 + 0.3, -dir.z * back + rs() * 0.4,
				(float) (0.14 + w * 0.6), life);
		}
		Vec3d tip = head.add(dir.multiply(0.25 * len));
		core(tip.x, tip.y, tip.z, 0, 0, 0, 0.22f, 0.05f);
		// la lanza 3D va centrada (punta en +0,775·largo): mismo sitio y largo que las llamas de la lanza
		if (vol() && len > 0.2) vol(MeraVolumes.LANCE, head.add(dir.multiply(-0.25 * len)), dir, (float) (len * 0.645), 0.19f, 0, f, 0.6f + 0.4f * form);
		if (R.nextFloat() < dt * 30) glow(head.x, head.y, head.z, 0, 0, 0, 1.4f, 0.12f, 1f, 0.6f, 0.2f);
		if (flyTicks >= 0) trail(f, head, 0.18f, 0.3f, 10, dt, density, false);
		f.last = head;
		return true;
	}

	// --- Entei: el sol

	private static void sun(Fx f, Vec3d c, float r, double dt, float density, double now, boolean moving) {
		// la superficie: llamas que giran sobre la esfera (la masa en espiral del anime)
		int n = many(f, (300 + 160 * r) * density, dt);
		for (int i = 0; i < n; i++) {
			Vec3d nrm = new Vec3d(rs(), rs(), rs());
			if (nrm.lengthSquared() < 1e-3) continue;
			nrm = nrm.normalize();
			Vec3d p = c.add(nrm.multiply(r * (0.85 + R.nextDouble() * 0.2)));
			// tangente: gira alrededor del eje vertical
			Vec3d tan = new Vec3d(-nrm.z, 0, nrm.x).multiply(r * 1.6);
			double out = 1.2 + R.nextDouble() * 1.5;
			if (R.nextFloat() < 0.55f) fire(p.x, p.y, p.z, tan.x + nrm.x * out, tan.y + nrm.y * out, tan.z + nrm.z * out, 0.22f * r + 0.2f, 0.35f + R.nextFloat() * 0.25f);
			else tongue(p.x, p.y, p.z, tan.x + nrm.x * out * 2, nrm.y * out * 2, tan.z + nrm.z * out * 2, 0.16f * r + 0.15f, 0.35f);
		}
		// el interior: núcleos blancos y un gran resplandor
		int m = many2(f, 50 * density, dt);
		for (int i = 0; i < m; i++) {
			Vec3d p = c.add(rs() * r * 0.5, rs() * r * 0.5, rs() * r * 0.5);
			core(p.x, p.y, p.z, rs() * 0.3, rs() * 0.3, rs() * 0.3, 0.5f * r, 0.2f);
		}
		if (R.nextFloat() < dt * 30) glow(c.x, c.y, c.z, 0, 0, 0, r * 3.6f, 0.2f, 1f, 0.55f, 0.15f);
		if (R.nextFloat() < dt * 20) glow(c.x, c.y, c.z, 0, 0, 0, r * 1.8f, 0.15f, 1f, 0.9f, 0.6f);
		if (R.nextFloat() < dt * 25) spark(c.x + rs() * r, c.y + rs() * r, c.z + rs() * r, rs() * 8, rs() * 8, rs() * 8, 0.8f);
		if (moving && R.nextFloat() < dt * 16 * density) smoke(c.x, c.y + r * 0.6, c.z, rs(), 1.5, rs(), r * 0.8f, 2.2f);
	}

	private static boolean emitEnteiBlast(Fx f, double age, double dt, float density) {
		Vec3d c = f.a;
		float area = f.radius;
		if (vol() && age < 2.4 + ENTEI_SMOKE) {
			// la bola de fuego que se abre de golpe, hierve y sube como un hongo enfriándose en humo, y el anillo que
			// corre por el suelo. Luego queda la nube de humo, que sigue subiendo y abriéndose unos segundos
			Vec3d cb = c;
			float rb, cool, inten;
			if (age < 2.4) {
				float u = (float) (age / 2.4), e = easeOut(u * 1.7);
				cool = smooth((u - 0.1f) / 0.9f);
				inten = 1f + 0.6f * Math.max(0f, 1f - u / 0.06f);
				rb = area * (0.22f + 0.5f * e);
			} else {
				float w = (float) ((age - 2.4) / ENTEI_SMOKE);
				float r1 = area * 0.72f;
				cool = 1f;
				inten = 1f - smooth((w - 0.25f) / 0.75f);
				rb = r1 * (1f + 0.3f * easeOut(w));
				cb = c.add(0, r1 * (0.2f + 0.8f * w) * w, 0);
			}
			vol(MeraVolumes.FIREBALL, cb, FWD, rb, cool, 0, f, inten);
			// si la cámara queda dentro del estallido (el sol cayó cerca), el fuego pegado a ella se aclara: se ve la bola
			// alrededor, no la pantalla entera lavada de naranja
			double cdx = camX - c.x, cdz = camZ - c.z;
			double bx = camX - cb.x, by = camY - cb.y, bz = camZ - cb.z;
			double cd = Math.sqrt(bx * bx + by * by + bz * bz);
			if (cd < rb * 1.6) MeraVolumes.nearFade(MathHelper.clamp(rb * 0.45f, 2.2f, 5f));
			float uw = (float) (age / 1.6);
			if (uw < 0.75f) {
				float w = uw / 0.75f;
				float rr = area * (0.3f + 0.95f * easeOut(w));
				vol(MeraVolumes.RING, c.add(0, -f.power * 0.5, 0), FWD, rr, 1.6f * (1 - w) + 0.4f, 0.7f, f, (1 - w) * (1 - w));
				// el anillo de choque que pasa por donde estás, igual
				if (Math.abs(Math.sqrt(cdx * cdx + cdz * cdz) - rr) < 3.0) MeraVolumes.nearFade(2.2f);
			}
		}
		if (f.last == null) {
			f.last = c;
			// el destello y la bola de fuego que se abre
			glow(c.x, c.y, c.z, 0, 0, 0, area * 2.6f, 0.5f, 1f, 0.95f, 0.8f);
			glow(c.x, c.y, c.z, 0, 0, 0, area * 4f, 0.9f, 1f, 0.5f, 0.15f);
			int n = (int) (900 * density);
			for (int i = 0; i < n; i++) {
				Vec3d d = new Vec3d(rs(), rs() * 0.8 + 0.2, rs());
				if (d.lengthSquared() < 1e-3) continue;
				d = d.normalize();
				double sp = area * (2.0 + R.nextDouble() * 2.5);
				if (R.nextFloat() < 0.7f) fire(c.x, c.y, c.z, d.x * sp, d.y * sp, d.z * sp, 0.5f + area * 0.12f, 0.7f + R.nextFloat() * 0.5f);
				else core(c.x, c.y, c.z, d.x * sp * 0.6, d.y * sp * 0.6, d.z * sp * 0.6, 0.6f + area * 0.1f, 0.4f);
			}
			// el anillo de choque por el suelo
			for (int i = 0; i < 220 * density; i++) {
				double ang = R.nextDouble() * Math.PI * 2;
				double sp = area * 3.2;
				fire(c.x, c.y - f.power * 0.5, c.z, Math.cos(ang) * sp, 0.4, Math.sin(ang) * sp, 0.6f, 0.6f);
			}
			for (int i = 0; i < 80; i++) spark(c.x, c.y, c.z, rs() * 25, R.nextDouble() * 22, rs() * 25, 1.4f);
		}
		// el hongo de humo y fuego que sube; luego solo humo, cada vez menos
		double smokeEnd = 2.4 + ENTEI_SMOKE * 0.8;
		if (age < smokeEnd) {
			boolean fireToo = age < 2.2;
			float k = fireToo ? (float) (1 - age / 2.2) * 0.8f + 0.2f : 0.2f * (float) (1 - (age - 2.2) / (smokeEnd - 2.2));
			// con el fuego 3D la nube ya es el volumen: aquí, menos humo suelto y de su mismo gris (el gris claro de
			// siempre, encima de la nube oscura, se veía como gotas claras)
			boolean v3 = vol();
			int n = many(f, 260 * k * density * (v3 ? 0.45f : 1f), dt);
			double y = Math.min(age, 2.2) * area * 1.2;
			for (int i = 0; i < n; i++) {
				double ang = R.nextDouble() * Math.PI * 2;
				double rad = R.nextDouble() * area * 0.5;
				if (!fireToo || R.nextFloat() < 0.5f) smoke(c.x + Math.cos(ang) * rad, c.y + y, c.z + Math.sin(ang) * rad, Math.cos(ang) * 2, 3 + area * 0.3, Math.sin(ang) * 2, area * 0.35f, 3.2f, v3 ? 0.55f : 1f);
				else fire(c.x + Math.cos(ang) * rad, c.y + y * 0.6, c.z + Math.sin(ang) * rad, Math.cos(ang), 4 + area * 0.2, Math.sin(ang), area * 0.18f + 0.3f, 0.7f);
			}
			return true;
		}
		return age < 2.4 + ENTEI_SMOKE;
	}

	/** Lo que dura la nube de humo que deja el estallido de Entei. */
	private static final double ENTEI_SMOKE = 5.0;

	// --- Hotarubi

	/** Dónde está la luciérnaga i de un grupo en el instante t. */
	private static Vec3d fireflyPos(Fx g, int i, double t) {
		double age = t - g.start;
		int seed = g.id * 977 + i * 131;
		double h1 = hash(seed), h2 = hash(seed + 1), h3 = hash(seed + 2);
		double rad = g.radius * (0.35 + 0.65 * Math.sqrt(h1));
		double ang = h2 * Math.PI * 2 + age * (0.35 + 0.4 * h3) * (h1 > 0.5 ? 1 : -1);
		double y = (h3 - 0.5) * g.radius * 0.7 + Math.sin(age * (1.3 + h1) + i) * 0.35;
		Vec3d spot = g.a.add(Math.cos(ang) * rad, y, Math.sin(ang) * rad);
		// salen de la mano y se reparten durante el primer segundo
		double u = MathHelper.clamp(age / (0.6 + 0.5 * h2), 0, 1);
		double e = 1 - (1 - u) * (1 - u);
		return g.b.lerp(spot, e);
	}

	private static double hash(int x) {
		x = ((x >>> 16) ^ x) * 0x45d9f3b;
		x = ((x >>> 16) ^ x) * 0x45d9f3b;
		x = (x >>> 16) ^ x;
		return (x & 0xFFFF) / 65535.0;
	}

	private static boolean emitFireflies(MinecraftClient mc, Fx f, double now, double age, double dt, float density) {
		if (f.ended && now - f.endAt > 0.6) return false;
		if (age > f.dur + 0.6) return false;
		int nflies = Math.max(1, Math.round(f.power));
		float fade = f.ended ? (float) MathHelper.clamp(1 - (now - f.endAt) / 0.6, 0, 1) : (float) MathHelper.clamp((f.dur - age) / 1.5, 0, 1);
		for (int i = 0; i < nflies; i++) {
			Vec3d p = fireflyPos(f, i, now);
			// el parpadeo de una luciérnaga
			float blink = 0.55f + 0.45f * (float) Math.sin(now * (5 + i % 4) + i * 1.7);
			int fi = spawn(FLY, p.x, p.y, p.z, 0, 0, 0, 0.32f * (0.7f + 0.6f * blink), 0.05f);
			if (fi >= 0) {
				CR[fi] = 0.75f * fade;
				CG[fi] = 1f * fade;
				CB[fi] = 0.3f * fade;
			}
			if (R.nextFloat() < dt * 22 * density * fade) {
				spawn(GREEN, p.x + rs() * 0.04, p.y, p.z + rs() * 0.04, rs() * 0.2, 0.25, rs() * 0.2, 0.11f * (0.8f + blink * 0.4f), 0.35f);
			}
			int ci = spawn(GREEN, p.x, p.y, p.z, 0, 0, 0, 0.12f * (0.8f + 0.4f * blink) * Math.max(0.2f, fade), 0.04f);
			if (ci >= 0) LIFT[ci] = 0f;
		}
		return true;
	}

	// --- Kyokaen, Enjōmō, Hibashira

	private static float zoneFade(Fx f, double now, double age) {
		float in = (float) MathHelper.clamp(age / 0.35, 0, 1);
		float out = f.ended ? (float) MathHelper.clamp(1 - (now - f.endAt) / 0.5, 0, 1) : (float) MathHelper.clamp((f.dur - age) / 0.5, 0, 1);
		return in * out;
	}

	private static boolean emitWall(Fx f, double now, double age, double dt, float density) {
		if ((f.ended && now - f.endAt > 0.5) || age > f.dur + 0.5) return false;
		float k = zoneFade(f, now, age);
		Vec3d n = f.b.normalize();
		Vec3d side = new Vec3d(-n.z, 0, n.x);
		double half = f.radius, height = f.power * (0.3 + 0.7 * k);
		if (vol()) vol(MeraVolumes.WALL, f.a, n, (float) half, (float) height, 0.35f, f, k);
		int cnt = many(f, (float) (half * 2 * 85 * density * k), dt);
		for (int i = 0; i < cnt; i++) {
			double s = rs() * half;
			Vec3d p = f.a.add(side.multiply(s)).add(n.multiply(rs() * 0.25));
			double up = height * (1.6 + R.nextDouble() * 0.8);
			float life = (float) (height / up) * 0.9f + 0.1f;
			if (R.nextFloat() < 0.5f) tongue(p.x, p.y, p.z, rs() * 0.4, up, rs() * 0.4, 0.42f + R.nextFloat() * 0.25f, life);
			else fire(p.x, p.y + R.nextDouble() * height * 0.3, p.z, rs() * 0.3, up * 0.8, rs() * 0.3, 0.5f + R.nextFloat() * 0.3f, life);
		}
		if (R.nextFloat() < dt * 8 * density * k) smoke(f.a.x + side.x * rs() * half, f.a.y + height, f.a.z + side.z * rs() * half, rs(), 1.5, rs(), 0.9f, 2f);
		return true;
	}

	private static boolean emitRing(Fx f, double now, double age, double dt, float density) {
		if ((f.ended && now - f.endAt > 0.5) || age > f.dur + 0.5) return false;
		float k = zoneFade(f, now, age);
		double r = f.radius, height = f.power * (0.3 + 0.7 * k);
		// (w = 1: el cerco como una cortina de lenguas que suben, igual que el muro de Kyokaen)
		if (vol()) MeraVolumes.add(MeraVolumes.RING, f.a, FWD, (float) r, (float) height, 0.42f, 1f, f.id * 31 + f.kind * 7 + f.userId, k);
		int cnt = many(f, (float) (2 * Math.PI * r * 45 * density * k), dt);
		for (int i = 0; i < cnt; i++) {
			double ang = R.nextDouble() * Math.PI * 2;
			double x = f.a.x + Math.cos(ang) * r, z = f.a.z + Math.sin(ang) * r;
			double up = height * (1.7 + R.nextDouble() * 0.8);
			float life = (float) (height / up) * 0.9f + 0.1f;
			if (R.nextFloat() < 0.55f) tongue(x, f.a.y, z, rs() * 0.3, up, rs() * 0.3, 0.38f + R.nextFloat() * 0.2f, life);
			else fire(x, f.a.y + R.nextDouble() * 0.4, z, rs() * 0.3, up * 0.7, rs() * 0.3, 0.42f, life);
		}
		return true;
	}

	/** El remolino de Hibashira. selfView: Ace lo ve desde el hueco de dentro (primera persona). */
	private static boolean emitPillar(Fx f, double age, double dt, float density, boolean selfView) {
		if (age > f.dur + 0.6) return false;
		boolean down = f.style == 1;
		Vec3d base = f.a, top = f.b;
		double h = Math.abs(top.y - base.y);
		float k = (float) MathHelper.clamp(1 - Math.max(0, age - f.dur * 0.7) / (f.dur * 0.3 + 0.6), 0, 1);
		if (f.last == null) {
			f.last = base;
			// el estallido de salida en el suelo
			Vec3d g = down ? base : base;
			for (int i = 0; i < 200 * density; i++) {
				double ang = R.nextDouble() * Math.PI * 2;
				double sp = 5 + R.nextDouble() * 5;
				fire(g.x, g.y + 0.2, g.z, Math.cos(ang) * sp, 0.5 + R.nextDouble(), Math.sin(ang) * sp, 0.45f, 0.45f);
			}
			glow(g.x, g.y + 1, g.z, 0, 0, 0, f.radius * 3, 0.35f, 1f, 0.6f, 0.2f);
		}
		if (vol()) {
			// la columna crece desde el suelo (o baja del cielo) en un instante
			float grow = easeOut(age / 0.3);
			Vec3d foot = down ? new Vec3d(base.x, top.y - h * grow, base.z) : base;
			// un poco más estrecho que la zona que quema: así en tercera persona la cámara queda fuera del remolino.
			// Desde dentro (primera persona) las bandas dejan huecos más anchos: se ven las cintas de fuego subiendo
			// alrededor y el mundo entre ellas, no una pantalla llena de fuego
			vol(MeraVolumes.PILLAR, foot, FWD, f.radius * 0.8f, (float) (h * grow), selfView ? 1f : 0f, f, k);
		}
		double sp = down ? -h * 2.4 : h * 2.4;
		float life = (float) (h / Math.abs(sp));
		int n = many(f, (float) (700 * density * k), dt);
		// las llamas suben por la pared del remolino, no por el hueco de dentro (ahí está Ace: en primera persona le
		// pasaban por delante de los ojos y la pantalla se llenaba de amarillo)
		if (selfView) n = n * 2 / 3;
		for (int i = 0; i < n; i++) {
			double ang = R.nextDouble() * Math.PI * 2;
			double rad = (0.42 + 0.52 * Math.sqrt(R.nextDouble())) * f.radius;
			Vec3d start = down ? top : base;
			double x = start.x + Math.cos(ang) * rad, z = start.z + Math.sin(ang) * rad;
			// gira mientras sube, como el remolino de Enkai
			double tx = -Math.sin(ang) * 3, tz = Math.cos(ang) * 3;
			if (R.nextFloat() < 0.6f) fire(x, start.y, z, tx, sp, tz, (float) (0.5 + (f.radius - rad) * 0.25), life);
			else tongue(x, start.y, z, tx, sp, tz, 0.5f, life);
		}
		if (R.nextFloat() < dt * 25 * k) {
			double y = base.y + R.nextDouble() * h * (down ? -1 : 1) * 0 + (down ? 0 : R.nextDouble() * h);
			glow(base.x, y, base.z, 0, 0, 0, f.radius * 2.2f, 0.2f, 1f, 0.6f, 0.2f);
		}
		if (R.nextFloat() < dt * 12 * k) smoke(base.x + rs() * f.radius, Math.max(base.y, top.y), base.z + rs() * f.radius, rs(), 2, rs(), 1.2f, 2.5f);
		return true;
	}

	// --- estallidos

	private static void burst(Fx f, Vec3d c, Vec3d dir, float r, float power, int style) {
		float d = OpeSettings.reducedMotion() ? 0.5f : 1f;
		switch (style) {
			case MeraFx.STYLE_STEAM -> {
				for (int i = 0; i < 40 * d; i++) steam(c.x + rs() * r * 0.4, c.y + rs() * 0.3, c.z + rs() * r * 0.4, rs() * 1.2, 1.2 + R.nextDouble() * 2, rs() * 1.2, 0.5f + r * 0.2f, 1.6f);
			}
			case MeraFx.STYLE_FIZZLE -> {
				for (int i = 0; i < 26 * d * Math.max(0.5f, r); i++) fire(c.x + rs() * r * 0.5, c.y + rs() * r * 0.5, c.z + rs() * r * 0.5, rs() * 1.5, 1 + R.nextDouble() * 1.5, rs() * 1.5, 0.18f + r * 0.12f, 0.45f);
				for (int i = 0; i < 8 * d; i++) smoke(c.x + rs() * r * 0.4, c.y, c.z + rs() * r * 0.4, rs(), 1, rs(), 0.3f + r * 0.2f, 1.4f);
			}
			case MeraFx.STYLE_CROSS -> {
				Vec3d dd = dir.lengthSquared() > 1e-4 ? dir.normalize() : new Vec3d(0, 0, 1);
				Vec3d[] ax = axes(dd);
				for (int i = 0; i < 520 * d; i++) {
					boolean vert = R.nextBoolean();
					Vec3d a = vert ? ax[1] : ax[0];
					double sgn = R.nextBoolean() ? 1 : -1;
					double sp = r * (2.5 + R.nextDouble() * 3);
					fire(c.x + rs() * 0.3, c.y + rs() * 0.3, c.z + rs() * 0.3, a.x * sp * sgn + rs(), a.y * sp * sgn + rs(), a.z * sp * sgn + rs(), 0.45f, 0.5f);
				}
				glow(c.x, c.y, c.z, 0, 0, 0, r * 3, 0.3f, 1f, 0.9f, 0.6f);
				for (int i = 0; i < 20 * d; i++) smoke(c.x + rs() * r * 0.5, c.y + rs() * 0.5, c.z + rs() * r * 0.5, rs(), 1.5, rs(), 0.9f, 2f);
			}
			case MeraFx.STYLE_LANCE -> {
				for (int i = 0; i < 90 * d; i++) {
					Vec3d v = new Vec3d(rs(), R.nextDouble(), rs()).normalize().multiply(3 + R.nextDouble() * 4);
					fire(c.x, c.y, c.z, v.x, v.y, v.z, 0.3f, 0.4f);
				}
				for (int i = 0; i < 14; i++) spark(c.x, c.y, c.z, rs() * 7, R.nextDouble() * 6, rs() * 7, 0.6f);
				glow(c.x, c.y, c.z, 0, 0, 0, r * 2.4f, 0.25f, 1f, 0.7f, 0.3f);
			}
			default -> {
				// estallido redondo: destello, bola de fuego que se abre, lenguas, chispas y humo
				int n = (int) ((60 + 140 * r) * d);
				for (int i = 0; i < n; i++) {
					Vec3d v = new Vec3d(rs(), rs() * 0.7 + 0.3, rs());
					if (v.lengthSquared() < 1e-3) continue;
					v = v.normalize().multiply(r * (1.8 + R.nextDouble() * 2.2));
					if (R.nextFloat() < 0.75f) fire(c.x, c.y, c.z, v.x, v.y, v.z, 0.25f + r * 0.13f, 0.45f + R.nextFloat() * 0.35f);
					else tongue(c.x, c.y, c.z, v.x * 1.2, v.y * 1.2, v.z * 1.2, 0.2f + r * 0.1f, 0.4f);
				}
				for (int i = 0; i < (int) (5 + 4 * r); i++) core(c.x + rs() * r * 0.3, c.y + rs() * r * 0.3, c.z + rs() * r * 0.3, rs(), rs() + 1, rs(), 0.3f + r * 0.18f, 0.25f);
				glow(c.x, c.y, c.z, 0, 0, 0, r * 2.8f, 0.3f, 1f, 0.85f, 0.5f);
				glow(c.x, c.y, c.z, 0, 0, 0, r * 4.2f, 0.55f, 1f, 0.45f, 0.12f);
				for (int i = 0; i < (int) (8 + 8 * r); i++) spark(c.x, c.y, c.z, rs() * 4 * r, R.nextDouble() * 4 * r, rs() * 4 * r, 0.9f);
				// con el fuego 3D los estallidos grandes acaban en una nube de humo oscura: las bocanadas, de su mismo
				// gris (si no, quedaba una bruma gris clara por encima de la nube); los pequeños, gris claro
				float tone = vol() && r >= 1.2f ? 0.55f : 1f;
				for (int i = 0; i < (int) (6 + 6 * r) * d; i++) smoke(c.x + rs() * r * 0.5, c.y + rs() * r * 0.3, c.z + rs() * r * 0.5, rs() * 1.5, 1.2 + R.nextDouble(), rs() * 1.5, 0.4f + r * 0.25f, 2.2f, tone);
			}
		}
	}

	/** El golpe que atraviesa un Logia: el fuego sale por el hueco y por detrás. */
	private static void scatterBurst(Vec3d c, Vec3d dir, float r, float density) {
		Vec3d d = dir.lengthSquared() > 1e-4 ? dir.normalize() : new Vec3d(0, 0, 1);
		Vec3d[] ax = axes(d);
		int n = (int) (70 * r * density) + 10;
		for (int i = 0; i < n; i++) {
			double ang = R.nextDouble() * Math.PI * 2;
			Vec3d radial = ax[0].multiply(Math.cos(ang)).add(ax[1].multiply(Math.sin(ang)));
			double sp = 2.5 + R.nextDouble() * 3;
			Vec3d v = radial.multiply(sp).add(d.multiply(R.nextDouble() * 4));
			if (R.nextFloat() < 0.6f) fire(c.x, c.y, c.z, v.x, v.y, v.z, 0.18f + 0.06f * r, 0.3f);
			else tongue(c.x, c.y, c.z, v.x, v.y, v.z, 0.16f, 0.28f);
		}
		glow(c.x, c.y, c.z, 0, 0, 0, 0.9f + r, 0.18f, 1f, 0.7f, 0.3f);
		for (int i = 0; i < 6; i++) spark(c.x, c.y, c.z, d.x * 6 + rs() * 3, d.y * 6 + rs() * 3, d.z * 6 + rs() * 3, 0.4f);
	}

	// --- el cuerpo de llamas

	private static void emitBody(MinecraftClient mc, PlayerEntity pl, float td, double dt, float density, double now) {
		boolean self = pl == mc.getCameraEntity() && mc.options.getPerspective().isFirstPerson();
		Vec3d base = pl.getLerpedPos(td);
		float by = MathHelper.lerp(td, pl.prevBodyYaw, pl.bodyYaw);
		double yr = Math.toRadians(by);
		double rX = -Math.cos(yr), rZ = -Math.sin(yr);
		double fX = -Math.sin(yr), fZ = Math.cos(yr);
		if (vol() && !self) {
			// Ace ardiendo: el cuerpo entero envuelto, no unas velas encima. Una llama grande cubre el tronco y
			// otra el pecho; encima, hombros, brazos, espalda y cabeza, que se juntan con ellas en una sola llama
			// con el cuerpo visible por dentro. Las alturas van desde los pies (agachado, todo baja un poco).
			int id = pl.getId() * 13;
			double sc = pl.isInSneakingPose() ? 0.82 : 1.0;
			double x = base.x, z = base.z;
			MeraVolumes.add(MeraVolumes.FLAME, new Vec3d(x, base.y + 0.86 * sc, z), FWD, 0.46f, (float) (2.10 * sc), 0, 0, id + 1, 0.8f);
			MeraVolumes.add(MeraVolumes.FLAME, new Vec3d(x, base.y + 1.30 * sc, z), FWD, 0.34f, (float) (1.30 * sc), 0, 0, id + 2, 0.7f);
			for (int sd = -1; sd <= 1; sd += 2) {
				Vec3d sh = new Vec3d(x + rX * 0.32 * sd, base.y + 1.00 * sc, z + rZ * 0.32 * sd);
				MeraVolumes.add(MeraVolumes.FLAME, sh, FWD, 0.26f, (float) (1.45 * sc), 0, 0, id + 3 + sd, 0.95f);
				Vec3d arm = new Vec3d(x + rX * 0.40 * sd, base.y + 0.60 * sc, z + rZ * 0.40 * sd);
				MeraVolumes.add(MeraVolumes.FLAME, arm, FWD, 0.19f, (float) (0.95 * sc), 0, 0, id + 6 + sd, 0.8f);
			}
			Vec3d back = new Vec3d(x - fX * 0.20, base.y + 1.10 * sc, z - fZ * 0.20);
			MeraVolumes.add(MeraVolumes.FLAME, back, FWD, 0.30f, (float) (1.60 * sc), 0, 0, id + 8, 0.85f);
			Vec3d head = new Vec3d(x, base.y + 1.66 * sc, z);
			MeraVolumes.add(MeraVolumes.FLAME, head, FWD, 0.20f, (float) (0.90 * sc), 0, 0, id + 9, 0.85f);
			thin = 0.3f;
		}
		float rate = (self ? 50 : 260) * density;
		int n = (int) (rate * dt + R.nextFloat());
		for (int i = 0; i < n; i++) {
			int part = R.nextInt(10);
			double lx, ly, lz;
			float size = 0.16f;
			if (part < 3) {          // hombros y brazos (Ace arde sobre todo por arriba)
				double side = R.nextBoolean() ? 1 : -1;
				lx = side * (0.34 + rs() * 0.08);
				ly = 0.85 + R.nextDouble() * 0.6;
				lz = rs() * 0.12;
				size = 0.2f;
			} else if (part < 6) {   // torso
				lx = rs() * 0.24;
				ly = 0.8 + R.nextDouble() * 0.65;
				lz = rs() * 0.14;
			} else if (part < 7) {   // cabeza
				lx = rs() * 0.2;
				ly = 1.5 + R.nextDouble() * 0.4;
				lz = rs() * 0.2;
				size = 0.14f;
			} else {                 // piernas
				lx = rs() * 0.2;
				ly = R.nextDouble() * 0.75;
				lz = rs() * 0.12;
				size = 0.13f;
			}
			if (self && (ly > 1.1 || part < 3)) continue;   // en 1.ª persona no se tapa la vista (los brazos arden en la mano)
			double x = base.x + rX * lx + fX * lz, z = base.z + rZ * lx + fZ * lz, y = base.y + ly;
			if (R.nextFloat() < 0.55f) tongue(x, y, z, rs() * 0.3, 1.2 + R.nextDouble(), rs() * 0.3, size + 0.08f, 0.32f + R.nextFloat() * 0.15f);
			else fire(x, y, z, rs() * 0.3, 0.9, rs() * 0.3, size, 0.3f);
		}
		// al correr deja una estela
		Vec3d v = pl.getVelocity();
		if (v.horizontalLengthSquared() > 0.01 && R.nextFloat() < dt * 30 * density) {
			fire(base.x + rs() * 0.2, base.y + 0.15, base.z + rs() * 0.2, -v.x * 4, 0.4, -v.z * 4, 0.22f, 0.5f);
		}
		if (!self && R.nextFloat() < dt * 6) glow(base.x, base.y + 1, base.z, 0, 0, 0, 1.6f, 0.3f, 1f, 0.5f, 0.15f);
		thin = 1f;
	}

	private static void emitHiddenParts(MinecraftClient mc, PlayerEntity pl, int bits, float td, double dt, float density) {
		if (pl == mc.getCameraEntity() && mc.options.getPerspective().isFirstPerson()) {
			// en 1.ª persona: el brazo de Hiken es fuego delante de la cámara
			if ((bits & MeraAnim.P_RARM) != 0 && MeraAnim.fireArmActive(pl.getId())) {
				Vec3d h = hand(mc, pl, 1, td);
				int n = (int) (220 * density * dt + R.nextFloat());
				for (int i = 0; i < n; i++) fire(h.x + rs() * 0.12, h.y + rs() * 0.12, h.z + rs() * 0.12, fx * 3 + rs() * 0.5, fy * 3 + 0.5, fz * 3 + rs() * 0.5, 0.12f, 0.15f);
			}
			return;
		}
		Vec3d base = pl.getLerpedPos(td);
		float by = MathHelper.lerp(td, pl.prevBodyYaw, pl.bodyYaw);
		double yr = Math.toRadians(by);
		double rX = -Math.cos(yr), rZ = -Math.sin(yr);
		double fX = -Math.sin(yr), fZ = Math.cos(yr);
		int n = (int) (420 * density * dt + R.nextFloat());
		for (int i = 0; i < n; i++) {
			int part = R.nextInt(6);
			int bit = 1 << part;
			if ((bits & bit) == 0) continue;
			double lx, ly, lz;
			switch (bit) {
				case MeraAnim.P_HEAD -> { lx = rs() * 0.24; ly = 1.5 + R.nextDouble() * 0.48; lz = rs() * 0.24; }
				case MeraAnim.P_BODY -> { lx = rs() * 0.25; ly = 0.75 + R.nextDouble() * 0.72; lz = rs() * 0.13; }
				case MeraAnim.P_RARM -> {
					// el brazo de Hiken: del hombro a la mano, siguiendo la pose
					if (MeraAnim.fireArmActive(pl.getId())) {
						Vec3d sh = shoulder(pl, 1, td), h = hand(mc, pl, 1, td);
						Vec3d p = sh.lerp(h, R.nextDouble());
						fire(p.x + rs() * 0.12, p.y + rs() * 0.12, p.z + rs() * 0.12, rs() * 0.5, 0.8, rs() * 0.5, 0.17f, 0.2f);
						continue;
					}
					lx = 0.37 + rs() * 0.12; ly = 0.75 + R.nextDouble() * 0.68; lz = rs() * 0.12;
				}
				case MeraAnim.P_LARM -> { lx = -0.37 + rs() * 0.12; ly = 0.75 + R.nextDouble() * 0.68; lz = rs() * 0.12; }
				case MeraAnim.P_RLEG -> { lx = 0.12 + rs() * 0.12; ly = R.nextDouble() * 0.75; lz = rs() * 0.12; }
				default -> { lx = -0.12 + rs() * 0.12; ly = R.nextDouble() * 0.75; lz = rs() * 0.12; }
			}
			double x = base.x + rX * lx + fX * lz, z = base.z + rZ * lx + fZ * lz, y = base.y + ly;
			fire(x, y, z, rs() * 0.4, 0.8, rs() * 0.4, 0.17f, 0.22f);
		}
	}

	// ------------------------------------------------------------------ HUD: el calor en la pantalla

	public static void renderHud(DrawContext ctx, RenderTickCounter tc) {
		MinecraftClient mc = MinecraftClient.getInstance();
		if (mc.player == null || mc.options.hudHidden) return;
		int w = ctx.getScaledWindowWidth(), h = ctx.getScaledWindowHeight();
		double now = RoomFxClient.time();
		// el resplandor del sol de Entei mientras lo cargas
		float heat = 0f;
		for (Fx f : FX) {
			if (f.kind == MeraFx.ENTEI_CHARGE && !f.ended && f.userId == mc.player.getId()) {
				heat = Math.max(heat, (float) Math.min(1.0, f.age(now) / 3.5));
			}
		}
		if (heat > 0.02f) {
			int a = Math.round(70 * heat);
			int top = (a << 24) | 0xFF7A1E, zero = 0x00FF7A1E;
			int band = Math.max(20, h / 4);
			ctx.fillGradient(RenderLayer.getGuiOverlay(), 0, 0, w, band, top, zero, 0);
			ctx.fillGradient(RenderLayer.getGuiOverlay(), 0, h - band / 2, w, h, zero, (a / 2 << 24) | 0xFF5010, 0);
		}
		double age = now - flashAt;
		if (age >= 0 && age < 0.5 && flashStrength > 0.01) {
			float u = (float) (age / 0.5);
			float alpha = (float) flashStrength * (1 - u) * (1 - u) * (OpeSettings.flashLevel() >= 2 ? 1f : 0.45f);
			int c = (Math.round(alpha * 255) << 24) | (flashRgb & 0xFFFFFF);
			ctx.fill(RenderLayer.getGuiOverlay(), 0, 0, w, h, c);
		} else if (age >= 0.5) {
			flashStrength = 0;
		}
	}
}
