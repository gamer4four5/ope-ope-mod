package dev.opeope.client.mixin;

import dev.opeope.client.dismember.ClientDismember;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.entity.feature.HeldItemFeatureRenderer;
import net.minecraft.client.render.model.json.ModelTransformationMode;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.entity.LivingEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.util.Arm;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Sin el brazo, lo que lleva en esa mano no se dibuja en el cuerpo (va con el brazo cortado). */
@Mixin(HeldItemFeatureRenderer.class)
public class HeldItemFeatureRendererMixin {
	@Inject(method = "renderItem", at = @At("HEAD"), cancellable = true, require = 0)
	private void opeope$armless(LivingEntity entity, ItemStack stack, ModelTransformationMode mode, Arm arm, MatrixStack matrices,
								VertexConsumerProvider vcp, int light, CallbackInfo ci) {
		if (ClientDismember.armless(entity, arm)) ci.cancel();
	}
}
