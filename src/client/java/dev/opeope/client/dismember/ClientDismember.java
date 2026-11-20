package dev.opeope.client.dismember;

import dev.opeope.OpeConfig;
import dev.opeope.anatomy.Anatomy;
import dev.opeope.anatomy.AnatomyRegistry;
import dev.opeope.anatomy.CutOp;
import dev.opeope.anatomy.CutVolume;
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
import net.minecraft.client.render.entity.LivingEntityRenderer;
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
import java.util.Collections;
import java.util.Deque;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * El desmembramiento en el cliente: qué le falta a cada ser (lo dice el servidor), ocultar esas partes al dibujarlo,
 * las caras de corte limpias con su borde azul, y apuntar a una parte con Amputate (resaltarla y pedir el corte).
 */
public final class ClientDismember {
	private ClientDismember() {}

	public record Info(Set<String> detached, Set<String> locked, float drop, Set<String> foreign, float[] plane, CutVolume volume) {}

	public static final Int2ObjectOpenHashMap<Info> STATE = new Int2ObjectOpenHashMap<>();

	public static void receive(Payloads.DismemberSync p) {
		CutVolume vol = p.volume() != null && !p.volume().isEmpty() ? CutVolume.decodeString(p.volume()) : null;
		if (vol == null && p.plane().length == 4) vol = CutVolume.ofSinglePlane(p.plane());
		if (vol != null && vol.isWhole()) vol = null;
		if (p.detached().isEmpty() && p.foreign().isEmpty() && p.plane().length != 4 && vol == null) {
			STATE.remove(p.entityId());
			Dismember.CLIENT_HEIGHT.remove(p.entityId());
		} else {
			STATE.put(p.entityId(), new Info(Set.copyOf(p.detached()), Set.copyOf(p.locked()), p.drop(), Set.copyOf(p.foreign()),
				p.plane().length == 4 ? p.plane() : null, vol));
			if (p.heightScale() < 0.999f) Dismember.CLIENT_HEIGHT.put(p.entityId(), p.heightScale());
			else Dismember.CLIENT_HEIGHT.remove(p.entityId());
		}
		MinecraftClient mc = MinecraftClient.getInstance();
		if (mc.world != null && mc.world.getEntityById(p.entityId()) instanceof LivingEntity le) le.calculateDimensions();
	}

	public static void clear() {
		STATE.clear();
		Dismember.CLIENT_HEIGHT.clear();
		PASSES.clear();
		HIDDEN.clear();
		NAMES.clear();
		PUSHED_TRANSFORM.clear();
		VolumeSliceConsumer.clearGlobalSlice();
		CapBuilder.clearCache();
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
			if (i.foreign.contains(s)) continue;   // un brazo de otro puesto ahí sí sirve
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

	public record HiddenEntry(ModelPart part, boolean wasVisible) {}

	private static final Deque<List<HiddenEntry>> HIDDEN = new ArrayDeque<>();
	private static final Deque<Boolean> PUSHED_TRANSFORM = new ArrayDeque<>();
	private record RenderPass(int entityId, Matrix4f modelPxToRender, VertexConsumerProvider rawVcp, CapBuilder.Session session, boolean globalPushed) {}
	private static final Deque<RenderPass> PASSES = new ArrayDeque<>();
	/** Los nombres ocultos del ser que se está dibujando (para las capas que copian su postura). */
	private static final Deque<Set<String>> NAMES = new ArrayDeque<>();
	private static final Set<Object> CAP_WARNED = new HashSet<>();

	private static int begun = Integer.MIN_VALUE;

	/** Postura biomecánica (caída de costado, cojera o inclinación) al perder extremidades. */
	public record Posture(float pitchDeg, float rollDeg, float extraDrop) {
		public static final Posture NONE = new Posture(0f, 0f, 0f);
		public boolean isFlat() { return Math.abs(pitchDeg) < 0.05f && Math.abs(rollDeg) < 0.05f && Math.abs(extraDrop) < 1e-4f; }
	}

	public static Posture computePosture(LivingEntity e, Info i, Anatomy a, float tickDelta) {
		if (e == null || i == null || a == null || a.legs <= 0) return Posture.NONE;
		CutVolume vol = i.volume != null ? i.volume : (i.plane != null ? CutVolume.ofSinglePlane(i.plane) : CutVolume.WHOLE);
		Anatomy.Segment core = a.get(a.core);
		Vec3d coreC = core != null ? core.center() : Vec3d.ZERO;
		int missing = 0, keptLegs = 0;
		int leftKept = 0, rightKept = 0, frontKept = 0, hindKept = 0;
		int leftMiss = 0, rightMiss = 0, frontMiss = 0, hindMiss = 0;
		for (Anatomy.Segment s : a.segments.values()) {
			if (!s.has("locomotion")) continue;
			boolean isForeign = i.foreign.contains(s.id());
			boolean intact = isForeign || (!i.detached.contains(s.id()) && (vol.isWhole() || dev.opeope.anatomy.VolumeBounds.remainingFraction(s.box(), vol) >= 0.45));
			boolean isL = s.id().contains("left") || s.center().x > coreC.x + 0.5;
			boolean isR = s.id().contains("right") || s.center().x < coreC.x - 0.5;
			boolean isF = s.id().contains("front") || s.center().z < coreC.z - 0.5;
			boolean isH = s.id().contains("hind") || s.center().z > coreC.z + 0.5;
			if (intact) {
				keptLegs++;
				if (isL) leftKept++; else if (isR) rightKept++;
				if (isF) frontKept++; else if (isH) hindKept++;
			} else {
				missing++;
				if (isL) leftMiss++; else if (isR) rightMiss++;
				if (isF) frontMiss++; else if (isH) hindMiss++;
			}
		}
		if (missing == 0) return Posture.NONE;
		float limbPos = e.limbAnimator.getPos(tickDelta);
		float limbSpd = Math.min(1f, e.limbAnimator.getSpeed(tickDelta));
		float idle = e.age + tickDelta;
		float bob = MathHelper.sin(limbPos * 0.75f);
		float bob2 = MathHelper.cos(limbPos * 0.75f);

		if (a.legs >= 4) {
			if (keptLegs == 0) {
				// Sin ninguna pata: tumbado levemente de costado sobre el vientre
				return new Posture(4f * MathHelper.sin(idle * 0.14f), 14f, 0f);
			}
			if (keptLegs == 1 || (leftKept == 0 && rightKept > 0) || (rightKept == 0 && leftKept > 0)) {
				// Partido longitudinalmente o con 1 pata: cae de costado sobre el lado sin apoyo
				float sideSign = (leftKept == 0 && rightKept > 0) || (leftMiss > rightMiss) ? -1f : 1f;
				float roll = sideSign * (76f + 5f * MathHelper.sin(idle * 0.25f) + 12f * limbSpd * bob);
				float pitch = 6f * limbSpd * bob2;
				return new Posture(pitch, roll, 0f);
			}
			if (hindKept == 0 && frontKept > 0) {
				// Partido por la mitad sin patas traseras: la parte trasera cae hacia atrás (-Z baja => pitch < 0)
				float pitch = -24f + 15f * limbSpd * bob;
				float roll = 10f * limbSpd * bob2;
				return new Posture(pitch, roll, 0f);
			}
			if (frontKept == 0 && hindKept > 0) {
				// Sin patas delanteras: cae de pecho hacia delante (+Z baja => pitch > 0)
				float pitch = 28f + 15f * limbSpd * bob;
				float roll = 10f * limbSpd * bob2;
				return new Posture(pitch, roll, 0f);
			}
			// Falta 1 pata o 2 diagonales: cojera asimétrica pronunciada al caminar
			float pBias = frontMiss > hindMiss ? 11f : -9f;
			float rBias = leftMiss > rightMiss ? -13f : 13f;
			return new Posture(pBias + 12f * limbSpd * bob, rBias + 11f * limbSpd * bob2, 0f);
		}
		if (a.legs == 2) {
			if (keptLegs == 0) {
				return new Posture(6f * MathHelper.sin(idle * 0.14f), 0f, 0f);
			}
			// Bípedo con 1 pierna: salta y se inclina al lado cojo
			float sideSign = leftMiss > rightMiss ? -1f : 1f;
			return new Posture(8f * limbSpd * Math.abs(bob2), sideSign * (15f + 13f * limbSpd * bob), 0f);
		}
		return Posture.NONE;
	}

	private static float posturePivotY(LivingEntity e, Anatomy a) {
		double s = e.getScale() * (e.isBaby() ? 0.5 : 1);
		double px = a.legHeight > 0 ? a.legHeight * 0.65 : a.height * 0.35;
		return (float) (px * s / 16.0);
	}

	private static void applyPostureToMatrix(MatrixStack ms, LivingEntity e, Info i, Anatomy a, float tickDelta) {
		float drop = i != null ? i.drop : 0f;
		if (drop > 0) ms.translate(0, -drop, 0);
		Posture pos = computePosture(e, i, a, tickDelta);
		if (!pos.isFlat()) {
			float yaw = MathHelper.lerpAngleDegrees(tickDelta, e.prevBodyYaw, e.bodyYaw);
			float py = posturePivotY(e, a);
			ms.multiply(RotationAxis.POSITIVE_Y.rotationDegrees(180f - yaw));
			ms.translate(0f, py - pos.extraDrop, 0f);
			if (Math.abs(pos.rollDeg) > 0.05f) ms.multiply(RotationAxis.POSITIVE_Z.rotationDegrees(pos.rollDeg));
			if (Math.abs(pos.pitchDeg) > 0.05f) ms.multiply(RotationAxis.POSITIVE_X.rotationDegrees(pos.pitchDeg));
			ms.translate(0f, -py, 0f);
			ms.multiply(RotationAxis.POSITIVE_Y.rotationDegrees(-(180f - yaw)));
		}
	}

	private static void applyPostureToMatrix4f(Matrix4f m, LivingEntity e, Info i, Anatomy a, float tickDelta) {
		float drop = i != null ? i.drop : 0f;
		if (drop > 0) m.translate(0f, -drop, 0f);
		Posture pos = computePosture(e, i, a, tickDelta);
		if (!pos.isFlat()) {
			float yaw = MathHelper.lerpAngleDegrees(tickDelta, e.prevBodyYaw, e.bodyYaw);
			float py = posturePivotY(e, a);
			m.rotateY((float) Math.toRadians(180f - yaw));
			m.translate(0f, py - pos.extraDrop, 0f);
			if (Math.abs(pos.rollDeg) > 0.05f) m.rotateZ((float) Math.toRadians(pos.rollDeg));
			if (Math.abs(pos.pitchDeg) > 0.05f) m.rotateX((float) Math.toRadians(pos.pitchDeg));
			m.translate(0f, -py, 0f);
			m.rotateY((float) Math.toRadians(-(180f - yaw)));
		}
	}

	/**
	 * Re-oculta todas las partes desmembradas del render actual (se llama en {@code ModelPart.rotate} para que
	 * animaciones externas de EMF / FreshAnimations no las vuelvan visibles a mitad del render).
	 */
	public static void rehideActive() {
		if (HIDDEN.isEmpty()) return;
		List<HiddenEntry> top = HIDDEN.peek();
		if (top == null || top.isEmpty()) return;
		for (int i = 0, n = top.size(); i < n; i++) top.get(i).part.visible = false;
	}

	/** Empuja partes ocultas temporales durante el render de una parte suelta (y para tests), protegidas contra {@code root.animate()}. */
	public static void pushHiddenParts(List<ModelPart> parts) {
		List<HiddenEntry> list = new ArrayList<>(parts.size());
		for (ModelPart p : parts) {
			list.add(new HiddenEntry(p, p.visible));
			p.visible = false;
		}
		HIDDEN.push(list);
	}

	public static void popHiddenParts() {
		if (HIDDEN.isEmpty()) return;
		for (HiddenEntry he : HIDDEN.pop()) he.part.visible = he.wasVisible;
	}

	private static CutVolume subtractUnisolatedJoints(CutVolume vol, Anatomy a, Info i, EntityModel<?> model) {
		if (model == null || i.detached.isEmpty()) return vol;
		boolean emfOrSynth = ModelParts.isEmfModel(model) || a.isSynthetic();
		if (!emfOrSynth) return vol;
		for (String segId : i.detached) {
			Anatomy.Segment sg = a.get(segId);
			if (sg == null) continue;
			if (!sg.modelParts().isEmpty() && ModelParts.hasAnyCuboidInChains(model, sg.modelParts())) continue;
			double[] b = sg.box();
			float pad = 0.35f;
			float cx = (float) ((b[0] + b[3]) * 0.5);
			float cy = (float) ((b[1] + b[4]) * 0.5);
			float cz = (float) ((b[2] + b[5]) * 0.5);
			float hx = (float) ((b[3] - b[0]) * 0.5) + pad;
			float hy = (float) ((b[4] - b[1]) * 0.5) + pad;
			float hz = (float) ((b[5] - b[2]) * 0.5) + pad;
			vol = vol.subtractOp(new CutOp.BoxVolume(cx, cy, cz, hx, hy, hz, 1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f, false));
		}
		return vol;
	}

	/**
	 * Corte libre y volumétrico CSG: envuelve los vértices con {@link VolumeSliceConsumer} y recoge los bordes reales
	 * en postura animada para dibujar las tapas y paredes internas con {@link CapBuilder}.
	 */
	public static VertexConsumerProvider wrapSlice(VertexConsumerProvider base, LivingEntity e, float tickDelta, MatrixStack ms) {
		Info i = STATE.get(e.getId());
		if (i == null) return base;
		Anatomy a = AnatomyRegistry.getOrSynthetic(e);
		if (a == null) return base;
		CutVolume origVol = i.volume != null ? i.volume : (i.plane != null ? CutVolume.ofSinglePlane(i.plane) : CutVolume.WHOLE);
		boolean hasOrigVol = !origVol.isWhole();
		EntityModel<?> model = MinecraftClient.getInstance().getEntityRenderDispatcher().getRenderer(e) instanceof LivingEntityRenderer<?, ?> lr ? lr.getModel() : null;
		CutVolume vol = subtractUnisolatedJoints(origVol, a, i, model);
		if (vol.isWhole()) return base;

		float yaw = MathHelper.lerpAngleDegrees(tickDelta, e.prevBodyYaw, e.bodyYaw);
		double s = e.getScale() * (e.isBaby() ? 0.5 : 1);
		Matrix4f modelPxToRender = new Matrix4f(ms.peek().getPositionMatrix());
		if (begun != e.getId()) applyPostureToMatrix4f(modelPxToRender, e, i, a, tickDelta);
		modelPxToRender.rotateY((float) Math.toRadians(180f - yaw)).scale((float) (-s / 16.0), (float) (s / 16.0), (float) (s / 16.0));
		Matrix4f renderToModelPx = new Matrix4f(modelPxToRender).invert();

		CapBuilder.Session session = null;
		if (hasOrigVol) {
			List<String> activeSegs = new ArrayList<>();
			for (String sgId : a.segments.keySet()) {
				if (a.attached(sgId, i.detached)) activeSegs.add(sgId);
			}
			session = new CapBuilder.Session(origVol, a, activeSegs);
		}
		PASSES.push(new RenderPass(e.getId(), modelPxToRender, base, session, true));
		VolumeSliceConsumer.pushGlobalSlice(renderToModelPx, vol, session);
		return VolumeSliceConsumer.wrapProvider(base, renderToModelPx, vol, session);
	}

	/** Antes de dibujar un ser: oculta sus partes cortadas y aplica caída/cojera realista. Siempre emparejado con end(). */
	public static void begin(LivingEntity e, EntityModel<?> model, MatrixStack ms) {
		List<HiddenEntry> hid = new ArrayList<>();
		Info i = STATE.get(e.getId());
		if (i != null && model != null) {
			Set<ModelPart> seen = Collections.newSetFromMap(new IdentityHashMap<>());
			for (String name : hiddenParts(e)) {
				for (List<ModelPart> chain : ModelParts.chainsFor(model, name)) {
					if (chain.isEmpty()) continue;
					ModelPart p = chain.get(chain.size() - 1);
					if (!seen.add(p)) continue;
					hid.add(new HiddenEntry(p, p.visible));
					p.visible = false;
				}
			}
		}
		begun = e.getId();
		HIDDEN.push(hid);
		NAMES.push(i == null ? Set.of() : hiddenParts(e));
		boolean pushed = false;
		if (i != null) {
			Anatomy a = AnatomyRegistry.getOrSynthetic(e);
			MinecraftClient mc = MinecraftClient.getInstance();
			float td = mc.getRenderTickCounter().getTickDelta(false);
			ms.push();
			pushed = true;
			applyPostureToMatrix(ms, e, i, a, td);
		}
		PUSHED_TRANSFORM.push(pushed);
	}

	/** Una capa (lana, ropa, capa exterior) acaba de copiar la postura del ser: oculta en ella las mismas partes. */
	public static void mirrorHidden(EntityModel<?> copy) {
		if (NAMES.isEmpty() || HIDDEN.isEmpty() || copy == null) return;
		Set<String> names = NAMES.peek();
		if (names.isEmpty()) return;
		try {
			List<HiddenEntry> hid = HIDDEN.peek();
			Set<ModelPart> seen = Collections.newSetFromMap(new IdentityHashMap<>());
			for (HiddenEntry he : hid) seen.add(he.part);
			for (String name : names) {
				for (List<ModelPart> chain : ModelParts.chainsFor(copy, name)) {
					if (chain.isEmpty()) continue;
					ModelPart p = chain.get(chain.size() - 1);
					if (!seen.add(p)) continue;
					hid.add(new HiddenEntry(p, p.visible));
					p.visible = false;
				}
			}
		} catch (RuntimeException ignored) {
			// una capa que no se deja leer se dibuja entera; nunca rompe
		}
	}

	/** Después: lo vuelve a dejar todo como estaba y pinta las caras de corte del ser con luz y shaders reales. */
	public static void end(LivingEntity e, MatrixStack ms, VertexConsumerProvider vcp, float tickDelta, int light) {
		if (HIDDEN.isEmpty()) return;
		for (HiddenEntry he : HIDDEN.pop()) he.part.visible = he.wasVisible;
		begun = Integer.MIN_VALUE;
		if (!NAMES.isEmpty()) NAMES.pop();
		boolean pushed = !PUSHED_TRANSFORM.isEmpty() && PUSHED_TRANSFORM.pop();
		RenderPass pass = !PASSES.isEmpty() && PASSES.peek().entityId == e.getId() ? PASSES.pop() : null;
		if (pass != null && pass.globalPushed) VolumeSliceConsumer.popGlobalSlice();
		VertexConsumerProvider outVcp = pass != null ? pass.rawVcp : vcp;
		try {
			Info i = STATE.get(e.getId());
			Anatomy a = i == null ? null : AnatomyRegistry.getOrSynthetic(e);
			int overlay = LivingEntityRenderer.getOverlay(e, 0f);
			float time = e.age + tickDelta;
			if (a != null && i != null) {
				ms.push();
				try {
					float yaw = MathHelper.lerpAngleDegrees(tickDelta, e.prevBodyYaw, e.bodyYaw);
					ms.multiply(RotationAxis.POSITIVE_Y.rotationDegrees(180f - yaw));
					float s = e.getScale() * (e.isBaby() ? 0.5f : 1f);
					ms.scale(s, s, s);
					for (String seg : i.detached) {
						Anatomy.Segment sg = a.get(seg);
						if (sg == null || sg.parent() == null || i.detached.contains(sg.parent()) || i.foreign.contains(seg)) continue;
						if (i.plane != null && Anatomy.classify(sg.box(), i.plane) >= 0) continue;
						CapBuilder.drawJointCap(ms, outVcp, a, sg, light, overlay, time, i.locked.contains(seg), false);
					}
				} catch (RuntimeException ex) {
					if (CAP_WARNED.add(e.getType())) dev.opeope.OpeOpeMod.LOG.warn("Desmembramiento: cara de corte de {}: {}", e.getType(), ex.toString());
				} finally {
					ms.pop();
				}
			}
			if (pass != null && pass.session != null && i != null) {
				boolean locked = i.locked.contains("#slice") || i.locked.stream().anyMatch(k -> k.startsWith("#vol:"));
				try {
					pass.session.flush(pass.modelPxToRender, pass.rawVcp, light, overlay, time, locked, false, false);
				} catch (RuntimeException ex) {
					if (CAP_WARNED.add(e.getType())) dev.opeope.OpeOpeMod.LOG.warn("Desmembramiento CSG cap {}: {}", e.getType(), ex.toString());
				}
			}
		} finally {
			if (pushed) ms.pop();
		}
	}

	public static void cap(MatrixStack ms, VertexConsumerProvider vcp, Anatomy a, Anatomy.Segment sg, float time, boolean locked, boolean partSide) {
		CapBuilder.drawJointCap(ms, vcp, a, sg, net.minecraft.client.render.LightmapTextureManager.MAX_LIGHT_COORDINATE,
			net.minecraft.client.render.OverlayTexture.DEFAULT_UV, time, locked, partSide);
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
				Vec3d hp = o.add(d.multiply(t));
				List<String> low = a.cutGroups.get("lower_half");
				boolean hasLow = low != null && low.stream().anyMatch(x -> a.attached(x, gone));
				if (a.legs >= 4) {
					name = hp.z >= sg.center().z ? "back_half" : "front_half";
				} else if (hasLow && hp.y <= sg.center().y) {
					name = "lower_half";
				} else {
					name = "waist_half";
				}
			}
			if (names.contains(name)) continue;
			int k = 0;
			while (k < hits.size() && hits.get(k)[0] <= t) k++;
			hits.add(k, new double[]{t});
			names.add(k, name);
			if (("back_half".equals(name) || "front_half".equals(name)) && !names.contains("lower_half")) {
				List<String> low = a.cutGroups.get("lower_half");
				if (low != null && low.stream().anyMatch(x -> a.attached(x, gone))) {
					hits.add(new double[]{t + 1.0});
					names.add("lower_half");
				}
			}
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
		float td = ctx.tickCounter().getTickDelta(false);
		Vec3d cam = ctx.camera().getPos();
		Vec3d pos = le.getLerpedPos(td);
		Info i = STATE.get(le.getId());
		MatrixStack ms = ctx.matrixStack();
		ms.push();
		ms.translate(pos.x - cam.x, pos.y - cam.y, pos.z - cam.z);
		applyPostureToMatrix(ms, le, i, a, td);
		ms.multiply(RotationAxis.POSITIVE_Y.rotationDegrees(180f - MathHelper.lerpAngleDegrees(td, le.prevBodyYaw, le.bodyYaw)));
		float s = le.getScale();
		ms.scale(s, s, s);
		Matrix4f m = ms.peek().getPositionMatrix();
		VertexConsumer vc = ctx.consumers().getBuffer(RenderLayer.getDebugQuads());
		float pulse = 0.5f + 0.5f * MathHelper.sin((le.age + td) * 0.4f);
		int al = (int) (60 + 50 * pulse);
		Anatomy.Segment core = a.get(a.core);
		if ("back_half".equals(sel) || "front_half".equals(sel) || "waist_half".equals(sel)) {
			if (core != null) {
				double[] cb = core.box().clone();
				if ("back_half".equals(sel)) cb[2] = core.center().z;
				else if ("front_half".equals(sel)) cb[5] = core.center().z;
				else cb[4] = cb[1] + (cb[4] - cb[1]) * 0.38;
				for (int k = 0; k < 3; k++) { cb[k] -= 0.3; cb[k + 3] += 0.3; }
				box(vc, m, cb, 143, 216, 255, al);
				for (Anatomy.Segment seg : a.segments.values()) {
					if (seg == core) continue;
					boolean match = "back_half".equals(sel) ? seg.center().z >= core.center().z
						: "front_half".equals(sel) ? seg.center().z < core.center().z
						: seg.has("locomotion");
					if (!match) continue;
					double[] sb = seg.box().clone();
					for (int k = 0; k < 3; k++) { sb[k] -= 0.3; sb[k + 3] += 0.3; }
					box(vc, m, sb, 143, 216, 255, al);
				}
			}
		} else {
			List<String> segs = a.cutGroups.containsKey(sel) ? a.cutGroups.get(sel) : List.of(sel);
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

	// ------------------------------------------------------------------ cada tick: Scan, la cámara en la cabeza

	/**
	 * Con Scan activo, un hilo azul tenue une cada parte suelta con su dueño (se ve de quién es cada cosa). Un jugador
	 * sin cabeza ve desde su cabeza (PLAYER_HEAD_CAMERA) y sigue moviendo su cuerpo a ciegas.
	 */
	public static void tick(MinecraftClient mc) {
		if (mc.world == null || mc.player == null) return;
		if (ClientState.scanTicks > 0 && mc.world.getTime() % 3 == 0) {
			for (Entity en : mc.world.getEntities()) {
				if (!(en instanceof dev.opeope.entity.BodyPartEntity part) || part.squaredDistanceTo(mc.player) > 48 * 48) continue;
				LivingEntity v = part.mounted() ? null : part.victim();
				if (v == null) continue;
				Vec3d a = part.getBoundingBox().getCenter(), b = v.getBoundingBox().getCenter();
				double len = a.distanceTo(b);
				int n = (int) Math.min(40, len / 0.5);
				for (int k = 0; k <= n; k++) {
					Vec3d q = a.add(b.subtract(a).multiply(k / (double) Math.max(1, n)));
					mc.world.addParticle(dev.opeope.power.CutEngine.ROOM_DUST, q.x, q.y, q.z, 0, 0, 0);
				}
			}
		}
		headCamera(mc);
		syncHeadCamera();
	}

	private static void headCamera(MinecraftClient mc) {
		Entity cam = mc.getCameraEntity();
		dev.opeope.entity.BodyPartEntity head = OpeConfig.PLAYER_HEAD_CAMERA ? ownHead(mc) : null;
		if (head != null) {
			if (cam != head) mc.setCameraEntity(head);
		} else if (cam instanceof dev.opeope.entity.BodyPartEntity) {
			mc.setCameraEntity(mc.player);
		}
	}

	/** La cabeza suelta del propio jugador (si le falta y está cerca). */
	private static dev.opeope.entity.BodyPartEntity ownHead(MinecraftClient mc) {
		Info i = STATE.get(mc.player.getId());
		if (i == null) return null;
		Anatomy a = AnatomyRegistry.get(mc.player.getType());
		if (a == null) return null;
		boolean blind = false;
		for (String s : i.detached) {
			Anatomy.Segment sg = a.get(s);
			if (sg != null && sg.has("vision") && !i.foreign.contains(s)) blind = true;
		}
		if (!blind) return null;
		for (Entity en : mc.world.getEntities()) {
			if (en instanceof dev.opeope.entity.BodyPartEntity part && part.victimId() == mc.player.getId() && !part.mounted()
				&& part.hasVision() && part.squaredDistanceTo(mc.player) < 64 * 64) return part;
		}
		return null;
	}

	/** Al empezar cada fotograma: la cabeza-cámara mira a donde el jugador mueve el ratón (sin esperar al servidor). */
	public static void syncHeadCamera() {
		MinecraftClient mc = MinecraftClient.getInstance();
		if (mc.player != null && mc.getCameraEntity() instanceof dev.opeope.entity.BodyPartEntity head) {
			head.setYaw(mc.player.getYaw());
			head.setPitch(mc.player.getPitch());
			head.prevYaw = mc.player.prevYaw;
			head.prevPitch = mc.player.prevPitch;
		}
	}

	/** El nombre de la parte resaltada bajo la mira, mientras se apunta; y, si al jugador le faltan partes, cuáles. */
	public static void renderHud(DrawContext ctx, RenderTickCounter tc) {
		MinecraftClient mc = MinecraftClient.getInstance();
		if (mc.player != null) {
			Info mine = STATE.get(mc.player.getId());
			if (mine != null && !mine.detached.isEmpty()) {
				Anatomy a = AnatomyRegistry.get(mc.player.getType());
				List<String> names = new ArrayList<>();
				for (String sgn : mine.detached) {
					Anatomy.Segment sg = a == null ? null : a.get(sgn);
					if (mine.foreign.contains(sgn) || (sg != null && sg.parent() != null && mine.detached.contains(sg.parent()))) continue;
					names.add(name(sgn));
				}
				if (!names.isEmpty()) {
					String t = net.minecraft.text.Text.translatable("opeope.hud.missing", String.join(", ", names)).getString();
					ctx.drawTextWithShadow(mc.textRenderer, "✂ " + t, 8, ctx.getScaledWindowHeight() / 2 - 40, 0xFF8FD8FF);
				}
			}
		}
		String sel = selected();
		if (sel == null) return;
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
			case "back_half" -> "mitad trasera";
			case "front_half" -> "mitad delantera";
			case "waist_half" -> "partir por la cintura";
			case "right_hind_leg" -> "pata trasera derecha";
			case "left_hind_leg" -> "pata trasera izquierda";
			case "right_front_leg" -> "pata delantera derecha";
			case "left_front_leg" -> "pata delantera izquierda";
			default -> seg.replace('_', ' ');
		};
	}
}
