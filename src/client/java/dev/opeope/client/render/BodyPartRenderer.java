package dev.opeope.client.render;

import dev.opeope.OpeOpeMod;
import dev.opeope.anatomy.Anatomy;
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
		try {
			Identifier tex = lr.getTexture(v);
			int overlay = LivingEntityRenderer.getOverlay(v, 0f);
			float time = e.age + tickDelta;
			ms.push();
			try {
			ms.translate(0, e.getHeight() / 2f, 0);
			ms.multiply(RotationAxis.POSITIVE_Y.rotationDegrees(-MathHelper.lerpAngleDegrees(tickDelta, e.prevYaw, e.getYaw())));
			ms.multiply(RotationAxis.POSITIVE_X.rotationDegrees(MathHelper.lerp(tickDelta, e.prevPitch, e.getPitch())));
			ms.multiply(RotationAxis.POSITIVE_Z.rotationDegrees(e.roll(tickDelta)));
			float s = e.victimScale();
			ms.scale(s, s, s);
			double cx = (box[0] + box[3]) / 2, cy = (box[1] + box[4]) / 2, cz = (box[2] + box[5]) / 2;
			ms.translate(cx / 16.0, -cy / 16.0, -cz / 16.0);
			// aquí un punto P del JSON está en (−P.x, P.y, P.z) / 16: las caras de corte
			boolean locked = e.locked();
			for (String seg : e.segments()) {
				Anatomy.Segment sg = a.get(seg);
				if (sg == null || sg.parent() == null || e.segments().contains(sg.parent())) continue;
				ClientDismember.cap(ms, vcp, a, sg, time, locked, true);
			}
			// el marco del modelo (el mismo que LivingEntityRenderer)
			ms.scale(-1f, -1f, 1f);
			ms.translate(0, -1.501f, 0);
			// el buffer del modelo se pide después de las caras (cada getBuffer de otra capa cierra el anterior)
			VertexConsumer vc = vcp.getBuffer(model.getLayer(tex));
			int k = 0;
			for (String seg : e.segments()) {
				Anatomy.Segment sg = a.get(seg);
				if (sg == null) continue;
				for (String name : sg.modelParts()) {
					List<ModelPart> chain = ModelParts.chain(model, name);
					if (chain == null) continue;
					drawChain(chain, ms, vc, light, overlay, time, sg, k++, e.getId());
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
					if (chain != null) heldItem(v, chain, name, ms, vcp, light, time, sg, k, e.getId());
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

	/** La parte con sus padres girados como están, en su postura de reposo y con un temblor de vida. */
	private static void drawChain(List<ModelPart> chain, MatrixStack ms, VertexConsumer vc, int light, int overlay, float time,
								  Anatomy.Segment sg, int k, int seed) {
		ModelPart last = chain.get(chain.size() - 1);
		if (!last.visible) return;   // capa apagada (las del skin del jugador, etc.)
		ms.push();
		ModelTransform saved = last.getTransform();
		try {
			for (int i = 0; i < chain.size() - 1; i++) chain.get(i).rotate(ms);
			last.resetTransform();
			alive(last, time, sg, k, seed);
			last.render(ms, vc, light, overlay);
		} finally {
			last.setTransform(saved);
			ms.pop();
		}
	}

	/** Sigue viva: las piernas patalean, la mano se abre y se cierra, la cabeza mira y se mueve. */
	private static void alive(ModelPart p, float time, Anatomy.Segment sg, int k, int seed) {
		float ph = seed * 0.37f + k * 0.11f;
		if (sg.has("locomotion")) {
			p.pitch += 0.45f * MathHelper.sin(time * 0.55f + ph);
		} else if (sg.has("vision")) {
			p.yaw += 0.35f * MathHelper.sin(time * 0.06f + ph);
			p.pitch += 0.06f * MathHelper.sin(time * 0.9f + ph) * (MathHelper.sin(time * 0.05f) > 0.6f ? 1 : 0);
		} else {
			p.pitch += 0.10f * MathHelper.sin(time * 0.3f + ph);
			p.roll += 0.06f * MathHelper.sin(time * 0.21f + ph);
		}
	}

	/** Lo que llevaba en esa mano sigue en la mano del brazo cortado (es del dueño, pero no lo puede usar). */
	private static void heldItem(LivingEntity v, List<ModelPart> chain, String name, MatrixStack ms, VertexConsumerProvider vcp, int light,
								 float time, Anatomy.Segment sg, int k, int seed) {
		Arm arm = name.startsWith("right") ? Arm.RIGHT : Arm.LEFT;
		ItemStack stack = arm == v.getMainArm() ? v.getMainHandStack() : v.getOffHandStack();
		if (stack.isEmpty()) return;
		ModelPart last = chain.get(chain.size() - 1);
		ms.push();
		try {
		for (int i = 0; i < chain.size() - 1; i++) chain.get(i).rotate(ms);
		ModelTransform saved = last.getTransform();
		last.resetTransform();
		alive(last, time, sg, k - 1, seed);
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
