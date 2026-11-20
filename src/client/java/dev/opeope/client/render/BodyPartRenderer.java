package dev.opeope.client.render;

import dev.opeope.OpeOpeMod;
import dev.opeope.anatomy.Anatomy;
import dev.opeope.anatomy.AnatomyRegistry;
import dev.opeope.anatomy.CutOp;
import dev.opeope.anatomy.CutVolume;
import dev.opeope.client.dismember.CapBuilder;
import dev.opeope.client.dismember.ClientDismember;
import dev.opeope.client.dismember.ModelParts;
import dev.opeope.client.dismember.VolumeSliceConsumer;
import dev.opeope.entity.BodyPartEntity;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.model.ModelPart;
import net.minecraft.client.model.ModelTransform;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.entity.EntityRenderer;
import net.minecraft.client.render.entity.EntityRendererFactory;
import net.minecraft.client.render.entity.LivingEntityRenderer;
import net.minecraft.client.render.entity.model.EntityModel;
import net.minecraft.client.render.model.json.ModelTransformationMode;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.entity.LivingEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.util.Arm;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.RotationAxis;
import net.minecraft.util.math.Vec3d;
import org.joml.Matrix4f;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Dibuja una parte cortada con el modelo, la textura y la variante de verdad de su dueño (el skin del jugador, el color
 * de la oveja, el oficio del aldeano, paquetes CEM/FreshAnimations y shaders Iris/Sodium/Photon): conserva la jerarquía
 * animada cuando corresponde, aísla los cortes volumétricos/libres por CSG y sella con tapas anatómicas y borde azul.
 */
public class BodyPartRenderer extends EntityRenderer<BodyPartEntity> {
	private static final Set<String> WARNED = new HashSet<>();

	public BodyPartRenderer(EntityRendererFactory.Context ctx) {
		super(ctx);
		this.shadowRadius = 0.2f;
	}

	@Override
	public Identifier getTexture(BodyPartEntity entity) {
		return Identifier.ofVanilla("textures/misc/white.png");
	}

	@SuppressWarnings({"unchecked", "rawtypes"})
	@Override
	public void render(BodyPartEntity e, float yaw, float tickDelta, MatrixStack ms, VertexConsumerProvider vcp, int light) {
		MinecraftClient mc = MinecraftClient.getInstance();
		if (mc.world == null || !(mc.world.getEntityById(e.victimId()) instanceof LivingEntity v)) return;
		Anatomy a = e.anatomy();
		double[] box = e.modelBox();
		if (a == null || box == null) return;
		if (!(dispatcher.getRenderer(v) instanceof LivingEntityRenderer lr)) return;
		EntityModel model = lr.getModel();
		if (model == null) return;
		// la cabeza de un jugador sin cabeza es su cámara: desde dentro no se dibuja
		if (mc.getCameraEntity() == e && mc.options.getPerspective().isFirstPerson()) return;

		List<ModelParts.SavedPartState> snapshot = ModelParts.snapshot(model);
		boolean oldChild = model.child;
		boolean oldRiding = model.riding;
		float oldHandSwing = model.handSwingProgress;
		Object emfToken = ModelParts.mountEmfEntity(model, v);
		try {
			Identifier tex = ModelParts.resolveEmfTexture(model, lr.getTexture(v));
			int overlay = LivingEntityRenderer.getOverlay(v, 0f);
			float time = e.age + tickDelta;
			ms.push();
			try {
				LivingEntity host = e.mounted() ? e.host() : null;
				boolean caps = true;
				if (host != null) {
					// quimera: en el sitio de esa parte en su anfitrión, girada con él y del tamaño del hueco
					if (!toHostSlot(e, host, a, box, tickDelta, ms)) return;
					caps = false;
				} else {
					ms.translate(0, e.getHeight() / 2f, 0);
					// el mismo giro que el cuerpo del que salió (180 − yaw, como LivingEntityRenderer)
					ms.multiply(RotationAxis.POSITIVE_Y.rotationDegrees(180f - MathHelper.lerpAngleDegrees(tickDelta, e.prevYaw, e.getYaw())));
					ms.multiply(RotationAxis.POSITIVE_X.rotationDegrees(MathHelper.lerp(tickDelta, e.prevPitch, e.getPitch())));
					ms.multiply(RotationAxis.POSITIVE_Z.rotationDegrees(e.roll(tickDelta)));
					float s = e.victimScale();
					ms.scale(s, s, s);
				}
				double cx = (box[0] + box[3]) / 2, cy = (box[1] + box[4]) / 2, cz = (box[2] + box[5]) / 2;
				ms.translate(cx / 16.0, -cy / 16.0, -cz / 16.0);
				boolean locked = e.locked();
				boolean frozen = e.frozen();
				boolean still = host != null || frozen || e.pinned();
				boolean walking = e.autonomous() && (e.getVelocity().horizontalLengthSquared() > 2e-5 || !e.isOnGround());

				float limbPos = walking ? time * 0.6662f : 0f;
				float limbSpeed = walking ? 0.65f : 0f;
				model.child = v.isBaby();
				model.riding = false;
				model.handSwingProgress = 0f;
				try {
					model.animateModel(v, limbPos, limbSpeed, tickDelta);
					model.setAngles(v, limbPos, limbSpeed, time, 0f, 0f);
				} catch (RuntimeException ignored) {
				}
				ModelParts.animateEmfRoot(model);

				CutVolume vol = e.cutVolume();
				boolean origVolIsWhole = vol.isWhole();
				boolean syntheticVol = false;
				if (origVolIsWhole && !a.isSynthetic() && ModelParts.isEmfModel(model)) {
					CutVolume boxIso = geometricIsolationForUnisolatedCem(a, e.segments(), model);
					if (boxIso != null) {
						vol = boxIso;
						syntheticVol = true;
					}
				}

				if ((!vol.isWhole() || a.isSynthetic()) && !still && !walking) {
					float ph = e.getId() * 0.37f;
					ms.multiply(RotationAxis.POSITIVE_Z.rotationDegrees(1.2f * MathHelper.sin(time * 0.25f + ph)));
					ms.multiply(RotationAxis.POSITIVE_X.rotationDegrees(1.5f * MathHelper.sin(time * 0.32f + ph)));
				}

				Matrix4f modelPxToRender = new Matrix4f(ms.peek().getPositionMatrix()).scale(-1f / 16f, 1f / 16f, 1f / 16f);
				CapBuilder.Session capSession = caps && !origVolIsWhole ? new CapBuilder.Session(vol, a, e.segments()) : null;

				if (!vol.isWhole() || a.isSynthetic()) {
					Matrix4f renderToModelPx = new Matrix4f(modelPxToRender).invert();
					VertexConsumerProvider draw = VolumeSliceConsumer.wrapProvider(vcp, renderToModelPx, vol, capSession);
					List<ModelPart> hiddenParts = (!a.isSynthetic() && !syntheticVol)
						? collectExcludedParts(a, e.segments(), model)
						: List.of();
					if (!hiddenParts.isEmpty()) ClientDismember.pushHiddenParts(hiddenParts);
					VolumeSliceConsumer.pushGlobalSlice(renderToModelPx, vol, capSession);
					try {
						ms.push();
						try {
							ms.scale(-1f, -1f, 1f);
							ms.translate(0, -1.501f, 0);
							VertexConsumer vc = draw.getBuffer(model.getLayer(tex));
							model.render(ms, vc, light, overlay);
							if (!a.isSynthetic()) {
								renderVolumetricHeldItems(v, a, e.segments(), vol, model, ms, draw, light, time, still);
							}
						} finally {
							ms.pop();
						}
					} finally {
						VolumeSliceConsumer.popGlobalSlice();
						if (!hiddenParts.isEmpty()) ClientDismember.popHiddenParts();
					}
				} else {
					ms.push();
					try {
						ms.scale(-1f, -1f, 1f);
						ms.translate(0, -1.501f, 0);
						VertexConsumer vc = vcp.getBuffer(model.getLayer(tex));
						List<ChainEntry> chains = collectUniqueChains(a, e.segments(), model);
						Set<ModelPart> keepTerms = Collections.newSetFromMap(new IdentityHashMap<>());
						for (ChainEntry ce : chains) keepTerms.add(ce.chain.get(ce.chain.size() - 1));
						List<ModelPart> hiddenChildren = collectExcludedDescendantParts(a, e.segments(), model, keepTerms);
						if (!hiddenChildren.isEmpty()) ClientDismember.pushHiddenParts(hiddenChildren);
						try {
							int k = 0;
							for (ChainEntry ce : chains) {
								drawChain(ce.chain, ms, vc, light, overlay, time, ce.seg, k++, e.getId(), still, walking);
							}
							k = 0;
							for (ChainEntry ce : chains) {
								k++;
								if (!isArmName(ce.partName)) continue;
								heldItem(v, ce.chain, ce.partName, ms, vcp, light, time, ce.seg, k, e.getId(), still);
							}
						} finally {
							if (!hiddenChildren.isEmpty()) ClientDismember.popHiddenParts();
						}
					} finally {
						ms.pop();
					}
				}

				if (caps) {
					if (origVolIsWhole) {
						for (String seg : e.segments()) {
							Anatomy.Segment sg = a.get(seg);
							if (sg == null || sg.parent() == null || e.segments().contains(sg.parent())) continue;
							CapBuilder.drawJointCap(ms, vcp, a, sg, light, overlay, time, locked, true);
						}
					} else if (capSession != null) {
						capSession.flush(modelPxToRender, vcp, light, overlay, time, locked, frozen, true);
					}
				}
			} finally {
				ms.pop();
			}
		} catch (RuntimeException ex) {
			String key = String.valueOf(e.victimType());
			if (WARNED.add(key)) OpeOpeMod.LOG.warn("Desmembramiento: no se pudo dibujar una parte de {}: {}", key, ex.toString());
		} finally {
			model.child = oldChild;
			model.riding = oldRiding;
			model.handSwingProgress = oldHandSwing;
			ModelParts.unmountEmfEntity(emfToken);
			ModelParts.restore(snapshot);
		}
	}

	private record ChainEntry(List<ModelPart> chain, Anatomy.Segment seg, String partName) {}

	private static boolean isArmName(String name) {
		return name.equals("right_arm") || name.equals("left_arm")
			|| name.equals("EMF_right_arm2") || name.equals("EMF_left_arm2")
			|| name.equals("right_arm2") || name.equals("left_arm2");
	}

	private static CutVolume geometricIsolationForUnisolatedCem(Anatomy a, List<String> segments, EntityModel<?> model) {
		boolean missing = false;
		double minX = 1e9, minY = 1e9, minZ = 1e9, maxX = -1e9, maxY = -1e9, maxZ = -1e9;
		for (String segId : segments) {
			Anatomy.Segment sg = a.get(segId);
			if (sg == null) continue;
			if (sg.modelParts().isEmpty() || !ModelParts.hasAnyCuboidInChains(model, sg.modelParts())) missing = true;
			double[] b = sg.box();
			minX = Math.min(minX, b[0]); minY = Math.min(minY, b[1]); minZ = Math.min(minZ, b[2]);
			maxX = Math.max(maxX, b[3]); maxY = Math.max(maxY, b[4]); maxZ = Math.max(maxZ, b[5]);
		}
		if (!missing || minX > maxX) return null;
		float pad = 0.4f;
		float cx = (float) ((minX + maxX) * 0.5), cy = (float) ((minY + maxY) * 0.5), cz = (float) ((minZ + maxZ) * 0.5);
		float hx = (float) ((maxX - minX) * 0.5) + pad;
		float hy = (float) ((maxY - minY) * 0.5) + pad;
		float hz = (float) ((maxZ - minZ) * 0.5) + pad;
		return new CutVolume(List.of(new CutOp.BoxVolume(cx, cy, cz, hx, hy, hz, 1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f, false)), List.of());
	}

	private static List<ChainEntry> collectUniqueChains(Anatomy a, List<String> segments, EntityModel<?> model) {
		List<ChainEntry> raw = new ArrayList<>();
		Set<ModelPart> seenTerms = Collections.newSetFromMap(new IdentityHashMap<>());
		for (String seg : segments) {
			Anatomy.Segment sg = a.get(seg);
			if (sg == null) continue;
			for (String name : sg.modelParts()) {
				for (List<ModelPart> ch : ModelParts.chainsFor(model, name)) {
					if (ch.isEmpty()) continue;
					ModelPart term = ch.get(ch.size() - 1);
					if (seenTerms.add(term)) raw.add(new ChainEntry(ch, sg, name));
				}
			}
		}
		if (raw.size() <= 1) return raw;
		List<ChainEntry> filtered = new ArrayList<>(raw.size());
		for (int i = 0; i < raw.size(); i++) {
			ChainEntry c = raw.get(i);
			ModelPart tc = c.chain.get(c.chain.size() - 1);
			boolean covered = false;
			for (int j = 0; j < raw.size(); j++) {
				if (i == j) continue;
				ModelPart to = raw.get(j).chain.get(raw.get(j).chain.size() - 1);
				if (ModelParts.isDescendant(to, tc)) {
					covered = true;
					break;
				}
			}
			if (!covered) filtered.add(c);
		}
		return filtered;
	}

	private static List<ModelPart> collectExcludedParts(Anatomy a, List<String> keptSegs, EntityModel<?> model) {
		Set<String> keptSet = new HashSet<>(keptSegs);
		Set<ModelPart> keepTerms = Collections.newSetFromMap(new IdentityHashMap<>());
		for (String seg : keptSegs) {
			Anatomy.Segment sg = a.get(seg);
			if (sg == null) continue;
			for (String name : sg.modelParts()) {
				for (List<ModelPart> ch : ModelParts.chainsFor(model, name)) {
					if (!ch.isEmpty()) keepTerms.add(ch.get(ch.size() - 1));
				}
			}
		}
		return collectExcludedDescendantParts(a, keptSet, model, keepTerms);
	}

	private static List<ModelPart> collectExcludedDescendantParts(Anatomy a, java.util.Collection<String> keptSegs,
																  EntityModel<?> model, Set<ModelPart> keepTerms) {
		Set<String> keptSet = keptSegs instanceof Set<String> s ? s : new HashSet<>(keptSegs);
		List<ModelPart> out = new ArrayList<>();
		Set<ModelPart> added = Collections.newSetFromMap(new IdentityHashMap<>());
		for (Map.Entry<String, Anatomy.Segment> en : a.segments.entrySet()) {
			if (keptSet.contains(en.getKey())) continue;
			for (String name : en.getValue().modelParts()) {
				for (List<ModelPart> ch : ModelParts.chainsFor(model, name)) {
					if (ch.isEmpty()) continue;
					ModelPart term = ch.get(ch.size() - 1);
					if (keepTerms.contains(term)) continue;
					boolean ancestorOfKept = false;
					for (ModelPart kp : keepTerms) {
						if (ModelParts.isDescendant(term, kp)) {
							ancestorOfKept = true;
							break;
						}
					}
					if (!ancestorOfKept && added.add(term)) out.add(term);
				}
			}
		}
		return out;
	}

	private static void renderVolumetricHeldItems(LivingEntity v, Anatomy a, List<String> segments, CutVolume vol,
												  EntityModel<?> model, MatrixStack ms, VertexConsumerProvider draw,
												  int light, float time, boolean still) {
		int k = 0;
		for (String seg : segments) {
			Anatomy.Segment sg = a.get(seg);
			if (sg == null) continue;
			for (String name : sg.modelParts()) {
				k++;
				if (!name.equals("right_arm") && !name.equals("left_arm")) continue;
				Vec3d c = sg.center();
				if (!vol.contains((float) c.x, (float) c.y, (float) c.z)) continue;
				for (List<ModelPart> ch : ModelParts.chainsFor(model, name)) {
					if (!ch.isEmpty()) heldItem(v, ch, name, ms, draw, light, time, sg, k, v.getId(), still);
				}
			}
		}
	}

	/**
	 * Lleva la matriz del centro de la entidad (donde la dibuja el dispatcher) al centro del sitio de la parte en el
	 * anfitrión, en el marco de este (girado con su cuerpo, a su escala), con la parte escalada al tamaño del hueco. Una
	 * cabeza sigue además el giro de la cabeza del anfitrión.
	 */
	private static boolean toHostSlot(BodyPartEntity e, LivingEntity host, Anatomy a, double[] box, float td, MatrixStack ms) {
		Anatomy ha = AnatomyRegistry.get(host.getType());
		String seg = e.hostSegment();
		Anatomy.Segment slot = ha == null ? null : ha.get(seg);
		if (slot == null) return false;
		Vec3d hp = host.getLerpedPos(td), pp = e.getLerpedPos(td);
		ClientDismember.Info info = ClientDismember.info(host);
		float drop = info == null ? 0 : info.drop();
		ms.translate(hp.x - pp.x, hp.y - pp.y - drop, hp.z - pp.z);
		float bodyYaw = MathHelper.lerpAngleDegrees(td, host.prevBodyYaw, host.bodyYaw);
		ms.multiply(RotationAxis.POSITIVE_Y.rotationDegrees(180f - bodyYaw));
		float hs = host.getScale() * (host.isBaby() ? 0.5f : 1f);
		ms.scale(hs, hs, hs);
		Vec3d c = slot.center();
		ms.translate(-c.x / 16.0, c.y / 16.0, c.z / 16.0);
		if (slot.has("vision")) {
			float headYaw = MathHelper.lerpAngleDegrees(td, host.prevHeadYaw, host.headYaw) - bodyYaw;
			float pitch = MathHelper.lerp(td, host.prevPitch, host.getPitch());
			ms.multiply(RotationAxis.POSITIVE_Y.rotationDegrees(-MathHelper.wrapDegrees(headYaw)));
			ms.multiply(RotationAxis.POSITIVE_X.rotationDegrees(pitch));
		}
		double[] sb = slot.box();
		double k = 1;
		int n = 0;
		double sum = 0;
		for (int i = 0; i < 3; i++) {
			double want = sb[i + 3] - sb[i], have = box[i + 3] - box[i];
			if (want > 0.01 && have > 0.01) {
				sum += want / have;
				n++;
			}
		}
		if (n > 0) k = MathHelper.clamp(sum / n, 0.35, 3.0);
		float s = (float) k * e.victimScale() / Math.max(0.05f, e.victimScale());
		ms.scale(s, s, s);
		return true;
	}

	/** La parte con sus padres girados como están; si es hueso EMF conserva su transformación .jem/.jpm intacta. */
	private static void drawChain(List<ModelPart> chain, MatrixStack ms, VertexConsumer vc, int light, int overlay, float time,
								  Anatomy.Segment sg, int k, int seed, boolean still, boolean walking) {
		ModelPart last = chain.get(chain.size() - 1);
		if (!last.visible) return;   // capa apagada (las del skin del jugador, etc.)
		boolean emf = false;
		for (int i = 0; i < chain.size(); i++) {
			if (ModelParts.isEmfPart(chain.get(i))) {
				emf = true;
				break;
			}
		}
		ms.push();
		ModelTransform saved = last.getTransform();
		try {
			for (int i = 0; i < chain.size() - 1; i++) chain.get(i).rotate(ms);
			if (emf) {
				if (!still) applyAliveMatrix(ms, last, time, sg, k, seed, walking);
			} else {
				last.resetTransform();
				if (!still) alive(last, time, sg, k, seed, walking);
			}
			last.render(ms, vc, light, overlay);
		} finally {
			last.setTransform(saved);
			ms.pop();
		}
	}

	private static void applyAliveMatrix(MatrixStack ms, ModelPart p, float time, Anatomy.Segment sg, int k, int seed, boolean walking) {
		float[] d = aliveDelta(time, sg, k, seed, walking);
		if (d[0] == 0f && d[1] == 0f) return;
		float px = p.pivotX / 16f, py = p.pivotY / 16f, pz = p.pivotZ / 16f;
		ms.translate(px, py, pz);
		if (d[1] != 0f) ms.multiply(RotationAxis.POSITIVE_Z.rotation(d[1]));
		if (d[0] != 0f) ms.multiply(RotationAxis.POSITIVE_X.rotation(d[0]));
		ms.translate(-px, -py, -pz);
	}

	/** Sigue viva: las piernas patalean (o andan, si van solas), la mano se abre y se cierra, la cabeza mira y se mueve. */
	private static void alive(ModelPart p, float time, Anatomy.Segment sg, int k, int seed, boolean walking) {
		float[] d = aliveDelta(time, sg, k, seed, walking);
		p.pitch += d[0];
		p.roll += d[1];
	}

	private static float[] aliveDelta(float time, Anatomy.Segment sg, int k, int seed, boolean walking) {
		float ph = seed * 0.37f + k * 0.11f;
		if (walking && sg.has("locomotion")) {
			boolean right = sg.id().startsWith("right");
			boolean hind = sg.id().contains("hind");
			float phase = (right ? 0f : MathHelper.PI) + (hind ? MathHelper.PI : 0f);
			return new float[]{0.9f * MathHelper.cos(time * 0.6662f + phase), 0f};
		} else if (sg.has("vision")) {
			return new float[]{0.05f * MathHelper.sin(time * 0.9f + ph) * (MathHelper.sin(time * 0.05f) > 0.7f ? 1f : 0f), 0f};
		} else if (sg.has("locomotion")) {
			return new float[]{0.45f * MathHelper.sin(time * 0.55f + ph), 0f};
		} else {
			return new float[]{0.10f * MathHelper.sin(time * 0.3f + ph), 0.06f * MathHelper.sin(time * 0.21f + ph)};
		}
	}

	/** Lo que llevaba en esa mano sigue en la mano del brazo cortado (es del dueño, pero no lo puede usar). */
	private static void heldItem(LivingEntity v, List<ModelPart> chain, String name, MatrixStack ms, VertexConsumerProvider vcp, int light,
								 float time, Anatomy.Segment sg, int k, int seed, boolean still) {
		Arm arm = name.contains("right") ? Arm.RIGHT : Arm.LEFT;
		ItemStack stack = arm == v.getMainArm() ? v.getMainHandStack() : v.getOffHandStack();
		if (stack.isEmpty()) return;
		ModelPart last = chain.get(chain.size() - 1);
		boolean emf = ModelParts.isEmfPart(last);
		ms.push();
		ModelTransform saved = last.getTransform();
		try {
			for (int i = 0; i < chain.size() - 1; i++) chain.get(i).rotate(ms);
			if (!emf) last.resetTransform();
			if (!still) alive(last, time, sg, k - 1, seed, false);
			last.rotate(ms);
			ms.multiply(RotationAxis.POSITIVE_X.rotationDegrees(-90f));
			ms.multiply(RotationAxis.POSITIVE_Y.rotationDegrees(180f));
			boolean left = arm == Arm.LEFT;
			ms.translate((left ? -1 : 1) / 16f, 0.125f, -0.625f);
			MinecraftClient.getInstance().getEntityRenderDispatcher().getHeldItemRenderer().renderItem(v, stack,
				left ? ModelTransformationMode.THIRD_PERSON_LEFT_HAND : ModelTransformationMode.THIRD_PERSON_RIGHT_HAND, left, ms, vcp, light);
		} finally {
			last.setTransform(saved);
			ms.pop();
		}
	}
}
