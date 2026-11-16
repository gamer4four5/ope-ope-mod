package dev.opeope.client.mixin;

import dev.opeope.client.dismember.ClientDismember;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.entity.feature.HeadFeatureRenderer;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Sin cabeza no hay calabaza ni cráneo encima del cuello. */
@Mixin(HeadFeatureRenderer.class)
public class HeadFeatureRendererMixin {
	@Inject(method = "render(Lnet/minecraft/client/util/math/MatrixStack;Lnet/minecraft/client/render/VertexConsumerProvider;ILnet/minecraft/entity/LivingEntity;FFFFFF)V",
		at = @At("HEAD"), cancellable = true, require = 0)
	private void opeope$headless(MatrixStack matrices, VertexConsumerProvider vcp, int light, LivingEntity entity, float limbAngle,
								 float limbDistance, float tickDelta, float animationProgress, float headYaw, float headPitch, CallbackInfo ci) {
		if (ClientDismember.missing(entity, "head")) ci.cancel();
	}
}
