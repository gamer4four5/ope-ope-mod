package dev.opeope.client.mixin;

import dev.opeope.client.dismember.ClientDismember;
import dev.opeope.client.render.PlayerAnimClient;
import net.minecraft.client.network.AbstractClientPlayerEntity;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.item.HeldItemRenderer;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.item.ItemStack;
import net.minecraft.util.Arm;
import net.minecraft.util.Hand;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** En primera persona, sin ese brazo no se ve ni el brazo ni lo que llevaba en esa mano. */
@Mixin(HeldItemRenderer.class)
public abstract class FirstPersonArmMixin {
	@Inject(method = "renderFirstPersonItem", at = @At("HEAD"), cancellable = true, require = 0)
	private void opeope$noArm(AbstractClientPlayerEntity player, float tickDelta, float pitch, Hand hand, float swingProgress, ItemStack item,
							  float equipProgress, MatrixStack matrices, VertexConsumerProvider vertexConsumers, int light, CallbackInfo ci) {
		Arm arm = hand == Hand.MAIN_HAND ? player.getMainArm() : player.getMainArm().getOpposite();
		if (ClientDismember.armless(player, arm)) ci.cancel();
		else if (dev.opeope.client.mera.MeraAnim.hideFirstPerson(player, hand)) ci.cancel();
	}

	@Inject(method = "renderFirstPersonItem",
		at = @At(value = "INVOKE", target = "Lnet/minecraft/client/util/math/MatrixStack;push()V", shift = At.Shift.AFTER),
		require = 0)
	private void opeope$handMotion(AbstractClientPlayerEntity player, float tickDelta, float pitch, Hand hand, float swingProgress, ItemStack item,
								   float equipProgress, MatrixStack matrices, VertexConsumerProvider vertexConsumers, int light, CallbackInfo ci) {
		PlayerAnimClient.applyFirstPersonHand(player, tickDelta, hand, swingProgress, item, matrices);
		dev.opeope.client.mera.MeraAnim.firstPerson(player, hand, matrices);
	}
}
