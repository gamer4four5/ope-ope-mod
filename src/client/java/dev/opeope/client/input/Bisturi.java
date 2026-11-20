package dev.opeope.client.input;

import dev.opeope.OpeConfig;
import dev.opeope.net.Payloads;
import dev.opeope.power.Actions;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RaycastContext;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * El Bisturí (clic derecho mantenido con la mano vacía y un ROOM):
 * <ul>
 *   <li>Ralentiza el tiempo al 12 % (en un jugador) y permite marcar múltiples cortes antes de soltar.</li>
 *   <li>Alt + Rueda / Clic Medio cambia la forma: Plano, Cuña V, Cubo/Ventana, Cilindro/Túnel, Esfera, Rodajas ×3, Cruz ×4, Lazo Libre.</li>
 *   <li>Rueda gira el eje, Mayús + Rueda ladea (o alterna pasante vs hueco finito), Ctrl + Rueda cambia profundidad/tamaño.</li>
 * </ul>
 */
public final class Bisturi {
	private Bisturi() {}

	public enum Mode {
		PLANE("Plano"),
		WEDGE("Cuña en V"),
		BOX("Cubo / Ventana"),
		CYLINDER("Túnel cilíndrico"),
		SPHERE("Esfera"),
		SLICES_3("Rodajas ×3"),
		CROSS_4("Cruz ×4"),
		FREEHAND("Lazo Libre");

		public final String label;

		Mode(String label) {
			this.label = label;
		}
	}

	public record AimFrame(Entity entity, Vec3d pos, Vec3d normal, Vec3d right, Vec3d up, Vec3d look) {}

	public record CommittedShape(Mode mode, Vec3d center, Vec3d r, Vec3d u, Vec3d f, float radius, boolean thru, List<Vec3d> freePoly) {}

	public static boolean active;
	public static Mode mode = Mode.PLANE;
	public static float shapeRadius = 0.32f;
	public static float roll, tilt;
	public static double offset;
	public static final List<double[]> COMMITTED = new ArrayList<>();
	public static final List<CommittedShape> COMMITTED_SHAPES = new ArrayList<>();

	public static void start() {
		active = true;
		roll = 0;
		tilt = 0;
		offset = 0;
		COMMITTED.clear();
		COMMITTED_SHAPES.clear();
		FreehandSlicer.cancel();
		Send.action(Actions.BISTURI, 1);
	}

	public static void cycleMode(int dir) {
		Mode[] vals = Mode.values();
		int next = Math.floorMod(mode.ordinal() + dir, vals.length);
		mode = vals[next];
		MinecraftClient mc = MinecraftClient.getInstance();
		if (mc.player != null) {
			mc.player.playSound(SoundEvents.BLOCK_AMETHYST_BLOCK_CHIME, 0.8f, 1.1f + next * 0.1f);
		}
	}

	/** Raycast combinado (entidad viva o BodyPartEntity primero, luego bloque hasta 96m o 8m delante). */
	public static AimFrame aimFrame(float tickDelta) {
		MinecraftClient mc = MinecraftClient.getInstance();
		if (mc.player == null) return null;
		Vec3d eye = mc.player.getCameraPosVec(tickDelta);
		Vec3d look = mc.player.getRotationVec(tickDelta);
		Vec3d right0 = look.crossProduct(new Vec3d(0, 1, 0));
		if (right0.lengthSquared() < 1e-4) right0 = new Vec3d(1, 0, 0);
		right0 = right0.normalize();
		Vec3d up0 = right0.crossProduct(look).normalize();

		double rRad = Math.toRadians(roll), tRad = Math.toRadians(tilt);
		Vec3d s = right0.multiply(Math.cos(rRad)).add(up0.multiply(Math.sin(rRad))).normalize();
		Vec3d uRot = look.crossProduct(s).normalize();
		Vec3d n = uRot.multiply(Math.cos(tRad)).add(look.multiply(Math.sin(tRad))).normalize();

		double blockDist = 96.0;
		Vec3d pos = eye.add(look.multiply(8));
		if (mc.world != null) {
			Vec3d end = eye.add(look.multiply(96));
			BlockHitResult hit = mc.world.raycast(new RaycastContext(eye, end, RaycastContext.ShapeType.OUTLINE, RaycastContext.FluidHandling.NONE, mc.player));
			if (hit != null && hit.getType() != HitResult.Type.MISS) {
				pos = hit.getPos();
				blockDist = eye.distanceTo(pos);
			}
			double entMax = Math.min(OpeConfig.CUT_RANGE, blockDist + 0.35);
			Vec3d entEnd = eye.add(look.multiply(entMax));
			Box sweepBox = mc.player.getBoundingBox().stretch(look.multiply(entMax)).expand(1.2);
			Entity bestEnt = null;
			Vec3d bestHit = null;
			double bestD2 = entMax * entMax;
			for (Entity e : mc.world.getOtherEntities(mc.player, sweepBox, en ->
				en.isAlive() && !en.isSpectator() && (en instanceof LivingEntity || en instanceof dev.opeope.entity.BodyPartEntity))) {
				Box eb = e.getBoundingBox().expand(0.15);
				Optional<Vec3d> opt = eb.contains(eye) ? Optional.of(eye) : eb.raycast(eye, entEnd);
				if (opt.isPresent()) {
					double d2 = eye.squaredDistanceTo(opt.get());
					if (d2 < bestD2) {
						bestD2 = d2;
						bestEnt = e;
						bestHit = opt.get();
					}
				}
			}
			if (bestEnt != null) {
				// Posición en el centro a lo largo de la profundidad sobre el punto impactado
				Vec3d p = bestHit.add(n.multiply(mode == Mode.PLANE ? offset : 0));
				return new AimFrame(bestEnt, p, n, s, uRot, look);
			}
		}
		return new AimFrame(null, pos.add(n.multiply(offset)), n, s, uRot, look);
	}

	/** Plano actual compatible con CutFx: n (3), punto (3), eje del tajo (3), mirada en el plano (3). */
	public static double[] current(float tickDelta) {
		MinecraftClient mc = MinecraftClient.getInstance();
		if (mc.player == null) return null;
		Vec3d eye = mc.player.getCameraPosVec(tickDelta);
		Vec3d look = mc.player.getRotationVec(tickDelta);
		Vec3d right = look.crossProduct(new Vec3d(0, 1, 0));
		if (right.lengthSquared() < 1e-4) right = new Vec3d(1, 0, 0);
		right = right.normalize();
		Vec3d up = right.crossProduct(look).normalize();
		double r = Math.toRadians(roll), t = Math.toRadians(tilt);
		Vec3d s = right.multiply(Math.cos(r)).add(up.multiply(Math.sin(r)));
		Vec3d n0 = look.crossProduct(s).normalize();
		Vec3d n = n0.multiply(Math.cos(t)).add(s.crossProduct(n0).multiply(Math.sin(t))).normalize();
		AimFrame af = aimFrame(tickDelta);
		Vec3d p = af != null ? af.pos() : eye.add(look.multiply(8)).add(n.multiply(offset));
		Vec3d inPlane = look.subtract(n.multiply(look.dotProduct(n)));
		inPlane = inPlane.lengthSquared() < 1e-6 ? right : inPlane.normalize();
		return new double[]{n.x, n.y, n.z, p.x, p.y, p.z, s.x, s.y, s.z, inPlane.x, inPlane.y, inPlane.z};
	}

	public static boolean onLeftPress() {
		if (!active) return false;
		if (mode == Mode.PLANE) {
			addPlane();
			return true;
		}
		if (mode == Mode.FREEHAND) {
			AimFrame af = aimFrame(1f);
			if (af != null) {
				MinecraftClient mc = MinecraftClient.getInstance();
				Vec3d look = mc.player != null ? mc.player.getRotationVec(1f) : af.look();
				Vec3d r0 = look.crossProduct(new Vec3d(0, 1, 0));
				if (r0.lengthSquared() < 1e-4) r0 = new Vec3d(1, 0, 0);
				r0 = r0.normalize();
				Vec3d u0 = r0.crossProduct(look).normalize();
				FreehandSlicer.begin(af.entity(), af.pos(), look, r0, u0);
			}
			return true;
		}
		commitShapeCut(true);
		return true;
	}

	public static void onLeftRelease() {
		if (mode == Mode.FREEHAND && FreehandSlicer.active()) {
			long h = MinecraftClient.getInstance().getWindow().getHandle();
			boolean shift = net.minecraft.client.util.InputUtil.isKeyPressed(h, org.lwjgl.glfw.GLFW.GLFW_KEY_LEFT_SHIFT)
				|| net.minecraft.client.util.InputUtil.isKeyPressed(h, org.lwjgl.glfw.GLFW.GLFW_KEY_RIGHT_SHIFT);
			FreehandSlicer.finish(!shift);
		}
	}

	/** Cada clic corta ya: lo cortado se queda quieto en su sitio hasta que sueltas el clic derecho. */
	public static void addPlane() {
		if (COMMITTED.size() >= 16) return;
		double[] c = current(1f);
		if (c == null) return;
		COMMITTED.add(c);
		Send.cut(3, new float[]{(float) c[0], (float) c[1], (float) c[2], (float) c[3], (float) c[4], (float) c[5]});
		MinecraftClient mc = MinecraftClient.getInstance();
		if (mc.player != null) mc.player.playSound(SoundEvents.BLOCK_AMETHYST_BLOCK_HIT, 1f, 1.6f + COMMITTED.size() * 0.03f);
	}

	private static void commitShapeCut(boolean delayed) {
		if (COMMITTED_SHAPES.size() >= 16) return;
		AimFrame af = aimFrame(1f);
		if (af == null) return;
		MinecraftClient mc = MinecraftClient.getInstance();
		long h = mc.getWindow().getHandle();
		boolean shift = net.minecraft.client.util.InputUtil.isKeyPressed(h, org.lwjgl.glfw.GLFW.GLFW_KEY_LEFT_SHIFT)
			|| net.minecraft.client.util.InputUtil.isKeyPressed(h, org.lwjgl.glfw.GLFW.GLFW_KEY_RIGHT_SHIFT);
		boolean thru = !shift;
		int targetId = af.entity() != null ? af.entity().getId() : -1;
		Vec3d c = af.pos();
		Vec3d r = af.right(), u = af.up(), f = af.look();

		float[] params;
		int payloadMode = mode.ordinal();
		switch (mode) {
			case WEDGE, BOX -> params = new float[]{
				(float) c.x, (float) c.y, (float) c.z,
				(float) r.x, (float) r.y, (float) r.z,
				(float) u.x, (float) u.y, (float) u.z,
				(float) f.x, (float) f.y, (float) f.z,
				shapeRadius
			};
			case CYLINDER -> params = new float[]{
				(float) c.x, (float) c.y, (float) c.z,
				(float) f.x, (float) f.y, (float) f.z,
				shapeRadius
			};
			case SPHERE -> params = new float[]{
				(float) c.x, (float) c.y, (float) c.z,
				shapeRadius
			};
			case SLICES_3 -> params = new float[]{
				(float) c.x, (float) c.y, (float) c.z,
				(float) af.normal().x, (float) af.normal().y, (float) af.normal().z,
				Math.max(0.20f, shapeRadius * 0.85f)
			};
			case CROSS_4 -> {
				Vec3d n1 = af.normal();
				Vec3d n2 = r.multiply(Math.sin(Math.toRadians(roll + 90))).add(u.multiply(Math.cos(Math.toRadians(roll + 90)))).normalize();
				params = new float[]{
					(float) c.x, (float) c.y, (float) c.z,
					(float) n1.x, (float) n1.y, (float) n1.z,
					(float) n2.x, (float) n2.y, (float) n2.z
				};
			}
			default -> {
				return;
			}
		}

		if (delayed) {
			COMMITTED_SHAPES.add(new CommittedShape(mode, c, r, u, f, shapeRadius, thru, null));
		}
		Send.cutShape(targetId, payloadMode, delayed, thru, params);
		if (mc.player != null) {
			mc.player.playSound(SoundEvents.BLOCK_AMETHYST_BLOCK_HIT, 1f, 1.55f + COMMITTED_SHAPES.size() * 0.04f);
		}
	}

	public static void scroll(int dir, boolean shift, boolean ctrl) {
		scroll(dir, shift, ctrl, false);
	}

	public static void scroll(int dir, boolean shift, boolean ctrl, boolean alt) {
		if (alt) {
			cycleMode(dir);
			return;
		}
		if (ctrl) {
			if (mode == Mode.PLANE) {
				offset = MathHelper.clamp(offset + dir * 0.5, -12, 12);
			} else {
				shapeRadius = MathHelper.clamp(shapeRadius + dir * 0.04f, 0.14f, 1.15f);
			}
		} else if (shift) {
			tilt = (tilt + dir * 15) % 360;
		} else {
			roll = (roll + dir * 15) % 360;
		}
	}

	public static void commit() {
		if (!active) return;
		if (FreehandSlicer.active()) onLeftRelease();
		active = false;
		boolean cutAlready = !COMMITTED.isEmpty() || !COMMITTED_SHAPES.isEmpty();
		COMMITTED.clear();
		COMMITTED_SHAPES.clear();
		FreehandSlicer.cancel();
		if (!cutAlready) {
			if (mode == Mode.PLANE) {
				double[] c = current(1f);
				if (c != null) Send.cut(1, new float[]{(float) c[0], (float) c[1], (float) c[2], (float) c[3], (float) c[4], (float) c[5]});
			} else if (mode != Mode.FREEHAND) {
				commitShapeCut(false);
			}
		}
		Send.action(Actions.BISTURI, 0);   // suelta todas las piezas congeladas a la vez
	}

	public static void cancel() {
		if (!active) return;
		active = false;
		COMMITTED.clear();
		COMMITTED_SHAPES.clear();
		FreehandSlicer.cancel();
		Send.action(Actions.BISTURI, 0);
	}
}
