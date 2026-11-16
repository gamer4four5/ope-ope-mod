package dev.opeope.client.dismember;

import dev.opeope.OpeConfig;
import dev.opeope.anatomy.Anatomy;
import dev.opeope.anatomy.AnatomyRegistry;
import dev.opeope.client.ClientState;
import dev.opeope.net.Payloads;
import dev.opeope.power.Dismember;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.model.ModelPart;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.RenderTickCounter;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.entity.model.EntityModel;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.util.Arm;
import net.minecraft.util.hit.EntityHitResult;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.RotationAxis;
import net.minecraft.util.math.Vec3d;
import org.joml.Matrix4f;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * El desmembramiento en el cliente: qué le falta a cada ser (lo dice el servidor), ocultar esas partes al dibujarlo,
 * las caras de corte limpias con su borde azul, y apuntar a una parte con Amputate (resaltarla y pedir el corte).
 */
public final class ClientDismember {
	private ClientDismember() {}

	public record Info(Set<String> detached, Set<String> locked, float drop) {}

	public static final Int2ObjectOpenHashMap<Info> STATE = new Int2ObjectOpenHashMap<>();

	public static void receive(Payloads.DismemberSync p) {
		if (p.detached().isEmpty()) {
			STATE.remove(p.entityId());
			Dismember.CLIENT_HEIGHT.remove(p.entityId());
		} else {
			STATE.put(p.entityId(), new Info(Set.copyOf(p.detached()), Set.copyOf(p.locked()), p.drop()));
			if (p.heightScale() < 0.999f) Dismember.CLIENT_HEIGHT.put(p.entityId(), p.heightScale());
			else Dismember.CLIENT_HEIGHT.remove(p.entityId());
		}
		MinecraftClient mc = MinecraftClient.getInstance();
		if (mc.world != null && mc.world.getEntityById(p.entityId()) instanceof LivingEntity le) le.calculateDimensions();
	}

	public static void clear() {
		STATE.clear();
		Dismember.CLIENT_HEIGHT.clear();
		aimEntity = -1;
		candidates.clear();
	}

	public static Info info(Entity e) {
		return STATE.get(e.getId());
	}

	public static boolean missing(Entity e, String segment) {
		Info i = STATE.get(e.getId());
		return i != null && i.detached.contains(segment);
	}

	/** ¿Le falta el brazo de ese lado (y con él lo que lleva en esa mano)? */
	public static boolean armless(LivingEntity e, Arm arm) {
		Info i = STATE.get(e.getId());
		if (i == null) return false;
		Anatomy a = AnatomyRegistry.get(e.getType());
		if (a == null) return false;
		String fn = arm == e.getMainArm() ? "main_hand" : "off_hand";
		for (String s : i.detached) {
			Anatomy.Segment seg = a.get(s);
			if (seg != null && seg.has(fn)) return true;
		}
		return false;
	}

	/** Los nombres de ModelPart que hay que ocultar en ese ser (los de sus partes cortadas). */
	public static Set<String> hiddenParts(Entity e) {
		Info i = STATE.get(e.getId());
		if (i == null) return Set.of();
		Anatomy a = AnatomyRegistry.get(e.getType());
		if (a == null) return Set.of();
		Set<String> out = new HashSet<>();
		for (String s : i.detached) {
			Anatomy.Segment seg = a.get(s);
			if (seg != null) out.addAll(seg.modelParts());
		}
		return out;
	}

	// ------------------------------------------------------------------ dibujar al ser sin sus partes

	private static final Deque<List<ModelPart>> HIDDEN = new ArrayDeque<>();
	private static final Deque<float[]> DROPS = new ArrayDeque<>();
	private static final Set<Object> CAP_WARNED = new HashSet<>();

	/** Antes de dibujar un ser: oculta sus partes cortadas y, sin piernas, lo baja. Siempre emparejado con end(). */
	public static void begin(LivingEntity e, EntityModel<?> model, MatrixStack ms) {
		List<ModelPart> hid = new ArrayList<>();
		float drop = 0;
		Info i = STATE.get(e.getId());
		if (i != null && model != null) {
			for (String name : hiddenParts(e)) {
				List<ModelPart> chain = ModelParts.chain(model, name);
				if (chain == null) continue;
				ModelPart p = chain.get(chain.size() - 1);
				if (p.visible) {
					p.visible = false;
					hid.add(p);
				}
			}
			drop = i.drop;
		}
		HIDDEN.push(hid);
		DROPS.push(new float[]{drop});
		if (drop > 0) ms.translate(0, -drop, 0);
	}

	/** Después: lo vuelve a dejar todo como estaba y pinta las caras de corte del ser. */
	public static void end(LivingEntity e, MatrixStack ms, VertexConsumerProvider vcp, float tickDelta) {
		if (HIDDEN.isEmpty()) return;
		for (ModelPart p : HIDDEN.pop()) p.visible = true;
		float drop = DROPS.isEmpty() ? 0 : DROPS.pop()[0];
		try {
			Info i = STATE.get(e.getId());
			Anatomy a = i == null ? null : AnatomyRegistry.get(e.getType());
			if (a != null) {
				ms.push();
				try {
					float yaw = MathHelper.lerpAngleDegrees(tickDelta, e.prevBodyYaw, e.bodyYaw);
					ms.multiply(RotationAxis.POSITIVE_Y.rotationDegrees(180f - yaw));
					float s = e.getScale();
					ms.scale(s, s, s);
					float time = e.age + tickDelta;
					for (String seg : i.detached) {
						Anatomy.Segment sg = a.get(seg);
						if (sg == null || sg.parent() == null || i.detached.contains(sg.parent())) continue;
						cap(ms, vcp, a, sg, time, i.locked.contains(seg), false);
					}
				} catch (RuntimeException ex) {
					if (CAP_WARNED.add(e.getType())) dev.opeope.OpeOpeMod.LOG.warn("Desmembramiento: cara de corte de {}: {}", e.getType(), ex.toString());
				} finally {
					ms.pop();
				}
			}
		} finally {
			if (drop > 0) ms.translate(0, drop, 0);
		}
	}

	// ------------------------------------------------------------------ la cara de corte

	/**
	 * La tapa de un corte en una articulación: un rectángulo con la sección de la parte (color carne con un leve
	 * degradado y el hueso en el centro, sin sangre) y un borde fino azul del ROOM que late. El marco: un punto del JSON
	 * P va a (−P.x, P.y, P.z) / 16. partSide: la cara que mira hacia la parte (si no, hacia el núcleo).
	 */
	public static void cap(MatrixStack ms, VertexConsumerProvider vcp, Anatomy a, Anatomy.Segment sg, float time, boolean locked, boolean partSide) {
		// la cara está en el lado de la caja que mira al padre (el torso): el costado para un brazo, arriba para una pierna
		Anatomy.Segment par = sg.parent() == null ? null : a.get(sg.parent());
		Vec3d pc = par != null ? par.center() : a.get(a.core) != null ? a.get(a.core).center() : sg.jointVec();
		Vec3d d = sg.center().subtract(pc);
		double[] b = sg.box();
		int axis;
		double ax = Math.abs(d.x), ay = Math.abs(d.y), az = Math.abs(d.z);
		if (ay >= ax && ay >= az) axis = 1;
		else if (ax >= az) axis = 0;
		else axis = 2;
		int u = axis == 0 ? 1 : 0, v = axis == 2 ? 1 : 2;
		double cu = (b[u] + b[u + 3]) / 2, cv = (b[v] + b[v + 3]) / 2;
		double hu = (b[u + 3] - b[u]) / 2, hv = (b[v + 3] - b[v]) / 2;
		double sign = Math.signum(axis == 0 ? d.x : axis == 1 ? d.y : d.z);
		if (sign == 0) sign = 1;
		double plane = sign > 0 ? b[axis] : b[axis + 3];
		// un pelín hacia fuera del lado que se ve (el ser: hacia donde estaba la parte; la parte: hacia el ser)
		double out = partSide ? -sign : sign;
		plane += 0.02 * out;
		Matrix4f m = ms.peek().getPositionMatrix();
		int col = a.capColor;
		int r = (col >> 16) & 255, g = (col >> 8) & 255, bl = col & 255;
		VertexConsumer fill = vcp.getBuffer(RenderLayer.getDebugQuads());
		quad(fill, m, axis, u, v, plane, cu, cv, hu, hv, r, g, bl, 255, Math.min(255, r + 18), Math.min(255, g + 14), Math.min(255, bl + 10));
		// el hueso: un rectángulo claro más pequeño en el centro
		quad(fill, m, axis, u, v, plane + 0.004 * out, cu, cv, hu * 0.36, hv * 0.36, 242, 238, 228, 255, 250, 248, 240);
		// el borde que late (eléctrico si Radio Knife)
		float pulse = 0.6f + 0.4f * MathHelper.sin(time * 0.25f);
		int er = locked ? 255 : 143, eg = locked ? 227 : 216, eb = locked ? 106 : 255;
		VertexConsumer glow = vcp.getBuffer(RenderLayer.getLightning());
		double t = 0.45;   // grosor del borde, píxeles
		double pl = plane + 0.008 * out;
		int al = (int) (200 * pulse);
		quad(glow, m, axis, u, v, pl, cu, cv - hv + t / 2, hu, t / 2, er, eg, eb, al, er, eg, eb);
		quad(glow, m, axis, u, v, pl, cu, cv + hv - t / 2, hu, t / 2, er, eg, eb, al, er, eg, eb);
		quad(glow, m, axis, u, v, pl, cu - hu + t / 2, cv, t / 2, hv, er, eg, eb, al, er, eg, eb);
		quad(glow, m, axis, u, v, pl, cu + hu - t / 2, cv, t / 2, hv, er, eg, eb, al, er, eg, eb);
	}

	/** Un rectángulo en el plano axis = plane (coordenadas del JSON), por las dos caras. */
	private static void quad(VertexConsumer vc, Matrix4f m, int axis, int u, int v, double plane, double cu, double cv, double hu, double hv,
							 int r, int g, int b, int a, int r2, int g2, int b2) {
		double[][] uv = {{cu - hu, cv - hv}, {cu + hu, cv - hv}, {cu + hu, cv + hv}, {cu - hu, cv + hv}};
		float[][] pts = new float[4][];
		for (int k = 0; k < 4; k++) {
			double[] p = new double[3];
			p[axis] = plane;
			p[u] = uv[k][0];
			p[v] = uv[k][1];
			pts[k] = new float[]{(float) (-p[0] / 16.0), (float) (p[1] / 16.0), (float) (p[2] / 16.0)};
		}
		for (int k = 0; k < 4; k++) {
			boolean hi = k >= 2;
			vc.vertex(m, pts[k][0], pts[k][1], pts[k][2]).color(hi ? r2 : r, hi ? g2 : g, hi ? b2 : b, a);
		}
		for (int k = 3; k >= 0; k--) {
			boolean hi = k >= 2;
			vc.vertex(m, pts[k][0], pts[k][1], pts[k][2]).color(hi ? r2 : r, hi ? g2 : g, hi ? b2 : b, a);
		}
	}

	// ------------------------------------------------------------------ Amputate dirigido: apuntar a una parte

	private static int aimEntity = -1;
	private static final List<String> candidates = new ArrayList<>();
	private static int aimIndex;

	/** Mientras se traza el tajo: qué parte del ser bajo la mira se cortaría con un clic sin trazo. */
	public static void aimTick() {
		MinecraftClient mc = MinecraftClient.getInstance();
		if (mc.player == null || mc.world == null || !(mc.crosshairTarget instanceof EntityHitResult ehr)
			|| !(ehr.getEntity() instanceof LivingEntity le) || !le.isAlive() || ClientState.ownRoomAt(le.getBoundingBox().getCenter()) == null) {
			resetAim();
			return;
		}
		Anatomy a = AnatomyRegistry.get(le.getType());
		if (a == null || a.excluded || le.squaredDistanceTo(mc.player) > OpeConfig.CUT_RANGE * OpeConfig.CUT_RANGE) {
			resetAim();
			return;
		}
		List<String> found = pick(le, a, mc.player.getEyePos(), mc.player.getRotationVec(1f));
		if (le.getId() != aimEntity || !found.equals(candidates)) {
			String keep = selected();
			aimEntity = le.getId();
			candidates.clear();
			candidates.addAll(found);
			aimIndex = keep != null && candidates.contains(keep) ? candidates.indexOf(keep) : 0;
		}
	}

	private static void resetAim() {
		aimEntity = -1;
		candidates.clear();
		aimIndex = 0;
	}

	public static String selected() {
		return candidates.isEmpty() ? null : candidates.get(Math.floorMod(aimIndex, candidates.size()));
	}

	public static boolean aiming() {
		return aimEntity >= 0 && !candidates.isEmpty();
	}

	/** Rueda con varias partes bajo la mira: cambia la resaltada. */
	public static boolean scroll(int dir) {
		if (candidates.size() < 2) return false;
		aimIndex = Math.floorMod(aimIndex - dir, candidates.size());
		return true;
	}

	/** Al soltar sin trazo: pide el corte de la parte resaltada. true si lo pidió (y no hay que cortar por plano). */
	public static boolean commit() {
		String s = selected();
		int id = aimEntity;
		resetAim();
		if (s == null || id < 0) return false;
		if (ClientPlayNetworking.canSend(Payloads.CutPart.ID)) ClientPlayNetworking.send(new Payloads.CutPart(id, s));
		return true;
	}

	public static void cancelAim() {
		resetAim();
	}

	/** Las partes que atraviesa el rayo, de la más cercana a la más lejana (cajas del JSON giradas con el cuerpo). */
	public static List<String> pick(LivingEntity le, Anatomy a, Vec3d eye, Vec3d look) {
		Info i = STATE.get(le.getId());
		Set<String> gone = i == null ? Set.of() : i.detached;
		float yaw = le.getBodyYaw();
		double s = le.getScale();
		Vec3d feet = le.getPos();
		if (i != null && i.drop > 0) feet = feet.add(0, -i.drop, 0);
		Vec3d o = Anatomy.toModel(eye.subtract(feet), yaw, s);
		Vec3d d = Anatomy.toModel(look, yaw, s);
		List<double[]> hits = new ArrayList<>();
		List<String> names = new ArrayList<>();
		for (Anatomy.Segment sg : a.segments.values()) {
			if (!a.attached(sg.id(), gone)) continue;
			double t = ray(o, d, sg.box());
			if (t < 0) continue;
			String name = sg.id();
			if (name.equals(a.core)) {
				// el núcleo: si se apunta a su mitad de abajo, la mitad de abajo entera (si el ser tiene ese grupo)
				Vec3d hp = o.add(d.multiply(t));
				List<String> low = a.cutGroups.get("lower_half");
				if (low == null || hp.y > sg.center().y || low.stream().noneMatch(x -> a.attached(x, gone))) continue;
				name = "lower_half";
			}
			if (names.contains(name)) continue;
			int k = 0;
			while (k < hits.size() && hits.get(k)[0] <= t) k++;
			hits.add(k, new double[]{t});
			names.add(k, name);
		}
		return names;
	}

	/** Rayo contra caja (método de las losas): la distancia de entrada (en unidades de d), o −1. */
	private static double ray(Vec3d o, Vec3d d, double[] b) {
		double t0 = 0, t1 = 1e9;
		double[] oo = {o.x, o.y, o.z}, dd = {d.x, d.y, d.z};
		for (int k = 0; k < 3; k++) {
			if (Math.abs(dd[k]) < 1e-9) {
				if (oo[k] < b[k] || oo[k] > b[k + 3]) return -1;
				continue;
			}
			double ta = (b[k] - oo[k]) / dd[k], tb = (b[k + 3] - oo[k]) / dd[k];
			if (ta > tb) {
				double x = ta;
				ta = tb;
				tb = x;
			}
			t0 = Math.max(t0, ta);
			t1 = Math.min(t1, tb);
			if (t0 > t1) return -1;
		}
		return t0;
	}

	/** El resaltado de la parte que se cortaría: su caja en azul translúcido, girada con el cuerpo. */
	public static void renderAim(WorldRenderContext ctx) {
		String sel = selected();
		MinecraftClient mc = MinecraftClient.getInstance();
		if (sel == null || mc.world == null || !(mc.world.getEntityById(aimEntity) instanceof LivingEntity le)) return;
		Anatomy a = AnatomyRegistry.get(le.getType());
		if (a == null || ctx.consumers() == null) return;
		List<String> segs = a.cutGroups.containsKey(sel) ? a.cutGroups.get(sel) : List.of(sel);
		float td = ctx.tickCounter().getTickDelta(false);
		Vec3d cam = ctx.camera().getPos();
		Vec3d pos = le.getLerpedPos(td);
		Info i = STATE.get(le.getId());
		if (i != null && i.drop > 0) pos = pos.add(0, -i.drop, 0);
		MatrixStack ms = ctx.matrixStack();
		ms.push();
		ms.translate(pos.x - cam.x, pos.y - cam.y, pos.z - cam.z);
		ms.multiply(RotationAxis.POSITIVE_Y.rotationDegrees(180f - MathHelper.lerpAngleDegrees(td, le.prevBodyYaw, le.bodyYaw)));
		float s = le.getScale();
		ms.scale(s, s, s);
		Matrix4f m = ms.peek().getPositionMatrix();
		VertexConsumer vc = ctx.consumers().getBuffer(RenderLayer.getDebugQuads());
		float pulse = 0.5f + 0.5f * MathHelper.sin((le.age + td) * 0.4f);
		int al = (int) (60 + 50 * pulse);
		for (String sg : segs) {
			Anatomy.Segment seg = a.get(sg);
			if (seg == null) continue;
			double[] b = seg.box().clone();
			for (int k = 0; k < 3; k++) {
				b[k] -= 0.3;
				b[k + 3] += 0.3;
			}
			box(vc, m, b, 143, 216, 255, al);
		}
		ms.pop();
	}

	private static void box(VertexConsumer vc, Matrix4f m, double[] b, int r, int g, int bl, int a) {
		for (int axis = 0; axis < 3; axis++) {
			int u = axis == 0 ? 1 : 0, v = axis == 2 ? 1 : 2;
			double cu = (b[u] + b[u + 3]) / 2, cv = (b[v] + b[v + 3]) / 2, hu = (b[u + 3] - b[u]) / 2, hv = (b[v + 3] - b[v]) / 2;
			quad(vc, m, axis, u, v, b[axis], cu, cv, hu, hv, r, g, bl, a, r, g, bl);
			quad(vc, m, axis, u, v, b[axis + 3], cu, cv, hu, hv, r, g, bl, a, r, g, bl);
		}
	}

	/** El nombre de la parte resaltada bajo la mira, mientras se apunta. */
	public static void renderHud(DrawContext ctx, RenderTickCounter tc) {
		String sel = selected();
		if (sel == null) return;
		MinecraftClient mc = MinecraftClient.getInstance();
		String label = "✂ " + name(sel) + (candidates.size() > 1 ? "  (" + (Math.floorMod(aimIndex, candidates.size()) + 1) + "/" + candidates.size() + ")" : "");
		int w = mc.textRenderer.getWidth(label);
		int x = ctx.getScaledWindowWidth() / 2 - w / 2, y = ctx.getScaledWindowHeight() / 2 + 14;
		ctx.drawTextWithShadow(mc.textRenderer, label, x, y, 0xFF8FD8FF);
	}

	public static String name(String seg) {
		return switch (seg) {
			case "head" -> "cabeza";
			case "body" -> "torso";
			case "neck" -> "cuello";
			case "right_arm" -> "brazo derecho";
			case "left_arm" -> "brazo izquierdo";
			case "arms" -> "brazos";
			case "right_leg" -> "pierna derecha";
			case "left_leg" -> "pierna izquierda";
			case "lower_half" -> "mitad de abajo";
			case "upper_half" -> "mitad de arriba";
			case "right_hind_leg" -> "pata trasera derecha";
			case "left_hind_leg" -> "pata trasera izquierda";
			case "right_front_leg" -> "pata delantera derecha";
			case "left_front_leg" -> "pata delantera izquierda";
			default -> seg.replace('_', ' ');
		};
	}
}
