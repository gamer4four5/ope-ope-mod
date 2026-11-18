package dev.opeope.client.render;

import dev.opeope.OpeOpeMod;
import dev.opeope.anatomy.Anatomy;
import dev.opeope.anatomy.AnatomyRegistry;
import dev.opeope.client.dismember.ClientDismember;
import dev.opeope.client.dismember.ModelParts;
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

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Dibuja una parte cortada con el modelo, la textura y la variante de verdad de su dueño (el skin del jugador, el color
 * de la oveja, el oficio del aldeano…): solo los ModelPart de esa parte, girando sobre su centro, con un poco de «vida»
 * (tiembla, patalea, la cabeza mira) y la cara de corte limpia con su borde azul.
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
		try {
			Identifier tex = lr.getTexture(v);
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
				// aquí un punto P del JSON está en (−P.x, P.y, P.z) / 16: las caras de corte
				boolean locked = e.locked();
				float[] pl = e.plane();
				if (caps) {
					for (String seg : e.segments()) {
						Anatomy.Segment sg = a.get(seg);
						if (sg == null || sg.parent() == null || e.segments().contains(sg.parent())) continue;
						ClientDismember.cap(ms, vcp, a, sg, time, locked, true);
					}
					if (pl != null) {
						java.util.Set<String> skip = new java.util.HashSet<>(a.segments.keySet());
						skip.removeAll(e.segments());
						ClientDismember.planeCaps(ms, vcp, a, skip, pl, time, locked, true);
					}
				}
				// corte libre: de cada parte solo lo que queda de este lado del plano (aquí P del JSON está en (−P.x, P.y, P.z)/16)
				VertexConsumerProvider draw = vcp;
				if (pl != null) {
					org.joml.Matrix4f m = new org.joml.Matrix4f(ms.peek().getPositionMatrix());
					final float fx = -pl[0], fy = pl[1], fz = pl[2], fd = pl[3] / 16f;
					draw = layer -> new dev.opeope.client.dismember.SliceConsumer(vcp.getBuffer(layer), m, fx, fy, fz, fd);
				}
				// el marco del modelo (el mismo que LivingEntityRenderer)
				ms.scale(-1f, -1f, 1f);
				ms.translate(0, -1.501f, 0);
				boolean walking = e.autonomous() && e.getVelocity().horizontalLengthSquared() > 1e-4;
				// el buffer del modelo se pide después de las caras (cada getBuffer de otra capa cierra el anterior)
				VertexConsumer vc = draw.getBuffer(model.getLayer(tex));
				int k = 0;
				for (String seg : e.segments()) {
					Anatomy.Segment sg = a.get(seg);
					if (sg == null) continue;
					for (String name : sg.modelParts()) {
						List<ModelPart> chain = ModelParts.chain(model, name);
						if (chain == null) continue;
						drawChain(chain, ms, vc, light, overlay, time, sg, k++, e.getId(), host != null, walking);
					}
				}
				// y lo que lleva en la mano, al final (usa sus propias capas)
				k = 0;
				for (String seg : e.segments()) {
					Anatomy.Segment sg = a.get(seg);
					if (sg == null) continue;
					for (String name : sg.modelParts()) {
						k++;
						if (!name.equals("right_arm") && !name.equals("left_arm")) continue;
						List<ModelPart> chain = ModelParts.chain(model, name);
						if (chain != null) heldItem(v, chain, name, ms, draw, light, time, sg, k, e.getId(), host != null);
					}
				}
			} finally {
				ms.pop();
			}
		} catch (RuntimeException ex) {
			String key = String.valueOf(e.victimType());
			if (WARNED.add(key)) OpeOpeMod.LOG.warn("Desmembramiento: no se pudo dibujar una parte de {}: {}", key, ex.toString());
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

	/** La parte con sus padres girados como están, en su postura de reposo y con un temblor de vida. */
	private static void drawChain(List<ModelPart> chain, MatrixStack ms, VertexConsumer vc, int light, int overlay, float time,
								  Anatomy.Segment sg, int k, int seed, boolean still, boolean walking) {
		ModelPart last = chain.get(chain.size() - 1);
		if (!last.visible) return;   // capa apagada (las del skin del jugador, etc.)
		ms.push();
		ModelTransform saved = last.getTransform();
		try {
			for (int i = 0; i < chain.size() - 1; i++) chain.get(i).rotate(ms);
			last.resetTransform();
			if (!still) alive(last, time, sg, k, seed, walking);
			last.render(ms, vc, light, overlay);
		} finally {
			last.setTransform(saved);
			ms.pop();
		}
	}

	/** Sigue viva: las piernas patalean (o andan, si van solas), la mano se abre y se cierra, la cabeza mira y se mueve. */
	private static void alive(ModelPart p, float time, Anatomy.Segment sg, int k, int seed, boolean walking) {
		float ph = seed * 0.37f + k * 0.11f;
		if (walking && sg.has("locomotion")) {
			// el paso de verdad: una pierna adelante y la otra atrás (las de la derecha y la izquierda, a contratiempo)
			boolean right = sg.id().startsWith("right");
			boolean hind = sg.id().contains("hind");
			float phase = (right ? 0f : MathHelper.PI) + (hind ? MathHelper.PI : 0f);
			p.pitch += 0.9f * MathHelper.cos(time * 0.6662f + phase);
		} else if (sg.has("vision")) {
			// la que mira de verdad la gira la entidad (headLife); aquí solo un parpadeo de vida
			p.pitch += 0.05f * MathHelper.sin(time * 0.9f + ph) * (MathHelper.sin(time * 0.05f) > 0.7f ? 1 : 0);
		} else if (sg.has("locomotion")) {
			p.pitch += 0.45f * MathHelper.sin(time * 0.55f + ph);
		} else {
			p.pitch += 0.10f * MathHelper.sin(time * 0.3f + ph);
			p.roll += 0.06f * MathHelper.sin(time * 0.21f + ph);
		}
	}

	/** Lo que llevaba en esa mano sigue en la mano del brazo cortado (es del dueño, pero no lo puede usar). */
	private static void heldItem(LivingEntity v, List<ModelPart> chain, String name, MatrixStack ms, VertexConsumerProvider vcp, int light,
								 float time, Anatomy.Segment sg, int k, int seed, boolean still) {
		Arm arm = name.startsWith("right") ? Arm.RIGHT : Arm.LEFT;
		ItemStack stack = arm == v.getMainArm() ? v.getMainHandStack() : v.getOffHandStack();
		if (stack.isEmpty()) return;
		ModelPart last = chain.get(chain.size() - 1);
		ms.push();
		try {
		for (int i = 0; i < chain.size() - 1; i++) chain.get(i).rotate(ms);
		ModelTransform saved = last.getTransform();
		last.resetTransform();
		if (!still) alive(last, time, sg, k - 1, seed, false);
		last.rotate(ms);
		last.setTransform(saved);
		ms.multiply(RotationAxis.POSITIVE_X.rotationDegrees(-90f));
		ms.multiply(RotationAxis.POSITIVE_Y.rotationDegrees(180f));
		boolean left = arm == Arm.LEFT;
		ms.translate((left ? -1 : 1) / 16f, 0.125f, -0.625f);
		MinecraftClient.getInstance().getEntityRenderDispatcher().getHeldItemRenderer().renderItem(v, stack,
			left ? ModelTransformationMode.THIRD_PERSON_LEFT_HAND : ModelTransformationMode.THIRD_PERSON_RIGHT_HAND, left, ms, vcp, light);
		} finally {
			ms.pop();
		}
	}
}
