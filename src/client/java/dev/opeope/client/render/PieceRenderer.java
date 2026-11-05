package dev.opeope.client.render;

import dev.opeope.entity.BlockSet;
import dev.opeope.entity.PieceEntity;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.OverlayTexture;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.block.BlockRenderManager;
import net.minecraft.client.render.entity.EntityRenderer;
import net.minecraft.client.render.entity.EntityRendererFactory;
import net.minecraft.client.texture.SpriteAtlasTexture;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.RotationAxis;

/** Dibuja una pieza cortada bloque a bloque (y girada si es un arma forjada). */
public class PieceRenderer extends EntityRenderer<PieceEntity> {
	public PieceRenderer(EntityRendererFactory.Context ctx) {
		super(ctx);
		this.shadowRadius = 0f;
	}

	@Override
	public void render(PieceEntity e, float yaw, float tickDelta, MatrixStack ms, VertexConsumerProvider vcp, int light) {
		BlockSet set = e.blocks;
		if (set == null || set.isEmpty()) return;
		BlockRenderManager brm = MinecraftClient.getInstance().getBlockRenderManager();
		ms.push();
		if (e.isWeaponShape()) {
			float y = MathHelper.lerpAngleDegrees(tickDelta, e.prevYaw, e.getYaw());
			float p = MathHelper.lerp(tickDelta, e.prevPitch, e.getPitch());
			ms.multiply(RotationAxis.POSITIVE_Y.rotationDegrees(-y));
			ms.multiply(RotationAxis.POSITIVE_X.rotationDegrees(p));
			ms.translate(-set.sx / 2.0, -set.sy / 2.0, -set.sz / 2.0);
		} else {
			double bob = (e.flags() & PieceEntity.F_SUSPENDED) != 0 ? Math.sin((e.age + tickDelta) * 0.12) * 0.025 : 0;
			ms.translate(-set.sx / 2.0, bob, -set.sz / 2.0);
		}
		for (BlockSet.Entry b : set.entries) {
			ms.push();
			ms.translate(b.x(), b.y(), b.z());
			brm.renderBlockAsEntity(b.state(), ms, vcp, light, OverlayTexture.DEFAULT_UV);
			ms.pop();
		}
		ms.pop();
		super.render(e, yaw, tickDelta, ms, vcp, light);
	}

	@Override
	public Identifier getTexture(PieceEntity entity) {
		return SpriteAtlasTexture.BLOCK_ATLAS_TEXTURE;
	}
}
