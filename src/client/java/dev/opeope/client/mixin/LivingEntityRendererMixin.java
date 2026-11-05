package dev.opeope.client.mixin;

import dev.opeope.client.render.CutRendering;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.entity.LivingEntityRenderer;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

@Mixin(LivingEntityRenderer.class)
public class LivingEntityRendererMixin {
	@ModifyVariable(
		method = "render(Lnet/minecraft/entity/LivingEntity;FFLnet/minecraft/client/util/math/MatrixStack;Lnet/minecraft/client/render/VertexConsumerProvider;I)V",
		at = @At("HEAD"), argsOnly = true)
	private VertexConsumerProvider opeope$cut(VertexConsumerProvider provider, LivingEntity entity, float yaw, float tickDelta,
		MatrixStack matrices, VertexConsumerProvider original, int light) {
		return CutRendering.wrap(provider, entity, tickDelta, matrices);
	}
}
