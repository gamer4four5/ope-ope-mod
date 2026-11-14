package dev.opeope.client.render;

import dev.opeope.entity.SterbenEntity;
import dev.opeope.registry.ModItems;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.OverlayTexture;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.util.math.MathHelper;
import net.minecraft.client.render.entity.EntityRenderer;
import net.minecraft.client.render.entity.EntityRendererFactory;
import net.minecraft.client.render.model.json.ModelTransformationMode;
import net.minecraft.client.texture.SpriteAtlasTexture;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.item.ItemStack;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.RotationAxis;

/** Sterben (Hoja de la Muerte): Sierra circular 3D de doble hoja Kikoku girando a altísima velocidad. */
public class SterbenRenderer extends EntityRenderer<SterbenEntity> {
	private final ItemStack sword = new ItemStack(ModItems.KIKOKU);

	public SterbenRenderer(EntityRendererFactory.Context ctx) {
		super(ctx);
	}

	@Override
	public void render(SterbenEntity e, float yaw, float tickDelta, MatrixStack ms, VertexConsumerProvider vcp, int light) {
		float spin = (e.age + tickDelta) * 68f;
		var ir = MinecraftClient.getInstance().getItemRenderer();
		for (int i = 0; i < 2; i++) {
			ms.push();
			ms.translate(0, 0.35, 0);
			float wobble = MathHelper.sin((e.age + tickDelta) * 0.45f) * 8f;
			ms.multiply(RotationAxis.POSITIVE_Z.rotationDegrees(wobble));
			ms.multiply(RotationAxis.POSITIVE_Y.rotationDegrees(spin + i * 90f));
			ms.multiply(RotationAxis.POSITIVE_X.rotationDegrees(90f));
			ms.scale(4.5f, 4.5f, 4.5f);
			ir.renderItem(sword, ModelTransformationMode.FIXED, 0xF000F0,
				OverlayTexture.DEFAULT_UV, ms, vcp, e.getWorld(), e.getId() + i);
			ms.pop();
		}
		// Halo sierra luminoso cian-blanco giratorio alrededor de la doble Kikoku
		ms.push();
		ms.translate(0, 0.35, 0);
		ms.multiply(RotationAxis.POSITIVE_Z.rotationDegrees(MathHelper.sin((e.age + tickDelta) * 0.45f) * 8f));
		VertexConsumer vc = vcp.getBuffer(RenderLayer.getLightning());
		var m = ms.peek().getPositionMatrix();
		int segs = 28;
		float rIn = 1.65f, rOut = 2.45f;
		float base = (float) Math.toRadians(spin * 0.8f);
		for (int i = 0; i < segs; i++) {
			float a0 = base + (float) (Math.PI * 2 * i / segs);
			float a1 = base + (float) (Math.PI * 2 * (i + 1) / segs);
			int alpha = (i % 4 < 2) ? 145 : 40;
			vc.vertex(m, (float) Math.cos(a0) * rIn, 0, (float) Math.sin(a0) * rIn).color(230, 250, 255, alpha);
			vc.vertex(m, (float) Math.cos(a1) * rIn, 0, (float) Math.sin(a1) * rIn).color(230, 250, 255, alpha);
			vc.vertex(m, (float) Math.cos(a1) * rOut, 0, (float) Math.sin(a1) * rOut).color(95, 220, 255, alpha / 2);
			vc.vertex(m, (float) Math.cos(a0) * rOut, 0, (float) Math.sin(a0) * rOut).color(95, 220, 255, alpha / 2);
		}
		ms.pop();
	}


	@Override
	public Identifier getTexture(SterbenEntity entity) {
		return SpriteAtlasTexture.BLOCK_ATLAS_TEXTURE;
	}
}
