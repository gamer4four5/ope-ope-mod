package dev.opeope.client.mixin;

import dev.opeope.client.dismember.ClientDismember;
import dev.opeope.client.render.CutRendering;
import net.minecraft.client.render.entity.model.EntityModel;
import org.spongepowered.asm.mixin.Shadow;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.entity.LivingEntityRenderer;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(LivingEntityRenderer.class)
public class LivingEntityRendererMixin {
	@Shadow
	protected EntityModel<?> model;

	/** Desmembramiento: oculta las partes cortadas (y baja el cuerpo sin piernas) mientras se dibuja este ser. */
	@Inject(
		method = "render(Lnet/minecraft/entity/LivingEntity;FFLnet/minecraft/client/util/math/MatrixStack;Lnet/minecraft/client/render/VertexConsumerProvider;I)V",
		at = @At("HEAD"))
	private void opeope$hideParts(LivingEntity entity, float yaw, float tickDelta, MatrixStack matrices, VertexConsumerProvider provider, int light, CallbackInfo ci) {
		ClientDismember.begin(entity, model, matrices);
	}

	@Inject(
		method = "render(Lnet/minecraft/entity/LivingEntity;FFLnet/minecraft/client/util/math/MatrixStack;Lnet/minecraft/client/render/VertexConsumerProvider;I)V",
		at = @At("RETURN"))
	private void opeope$showParts(LivingEntity entity, float yaw, float tickDelta, MatrixStack matrices, VertexConsumerProvider provider, int light, CallbackInfo ci) {
		ClientDismember.end(entity, matrices, provider, tickDelta);
	}
	@ModifyVariable(
		method = "render(Lnet/minecraft/entity/LivingEntity;FFLnet/minecraft/client/util/math/MatrixStack;Lnet/minecraft/client/render/VertexConsumerProvider;I)V",
		at = @At("HEAD"), argsOnly = true)
	private VertexConsumerProvider opeope$cut(VertexConsumerProvider provider, LivingEntity entity, float yaw, float tickDelta,
		MatrixStack matrices, VertexConsumerProvider original, int light) {
		return ClientDismember.wrapSlice(CutRendering.wrap(provider, entity, tickDelta, matrices), entity, tickDelta, matrices);
	}

	@Inject(
		method = "render(Lnet/minecraft/entity/LivingEntity;FFLnet/minecraft/client/util/math/MatrixStack;Lnet/minecraft/client/render/VertexConsumerProvider;I)V",
		at = @At("RETURN"))
	private void opeope$seams(LivingEntity entity, float yaw, float tickDelta, MatrixStack matrices, VertexConsumerProvider provider, int light, CallbackInfo ci) {
		CutRendering.flush();
	}
}
