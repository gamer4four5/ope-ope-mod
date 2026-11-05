package dev.opeope.client.render;

import dev.opeope.entity.SterbenEntity;
import dev.opeope.registry.ModItems;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.OverlayTexture;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.entity.EntityRenderer;
import net.minecraft.client.render.entity.EntityRendererFactory;
import net.minecraft.client.render.model.json.ModelTransformationMode;
import net.minecraft.client.texture.SpriteAtlasTexture;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.item.ItemStack;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.RotationAxis;

/** Sterben: la Kikoku girando como una sierra. */
public class SterbenRenderer extends EntityRenderer<SterbenEntity> {
	private final ItemStack sword = new ItemStack(ModItems.KIKOKU);

	public SterbenRenderer(EntityRendererFactory.Context ctx) {
		super(ctx);
	}

	@Override
	public void render(SterbenEntity e, float yaw, float tickDelta, MatrixStack ms, VertexConsumerProvider vcp, int light) {
		ms.push();
		ms.translate(0, 0.3, 0);
		ms.multiply(RotationAxis.POSITIVE_Y.rotationDegrees((e.age + tickDelta) * 47f));
		ms.multiply(RotationAxis.POSITIVE_X.rotationDegrees(90));
		ms.scale(3.2f, 3.2f, 3.2f);
		MinecraftClient.getInstance().getItemRenderer().renderItem(sword, ModelTransformationMode.FIXED, 0xF000F0,
			OverlayTexture.DEFAULT_UV, ms, vcp, e.getWorld(), e.getId());
		ms.pop();
	}

	@Override
	public Identifier getTexture(SterbenEntity entity) {
		return SpriteAtlasTexture.BLOCK_ATLAS_TEXTURE;
	}
}
