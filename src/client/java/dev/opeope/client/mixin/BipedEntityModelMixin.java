package dev.opeope.client.mixin;

import dev.opeope.client.render.PlayerAnimClient;
import net.minecraft.client.render.entity.model.BipedEntityModel;
import net.minecraft.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Cinemática articulada en 3.ª persona para jugadores (porte de Kikoku, salto, columna, horizonte y gesto Shambles). */
@Mixin(BipedEntityModel.class)
public class BipedEntityModelMixin {
	@Inject(method = "setAngles(Lnet/minecraft/entity/LivingEntity;FFFFF)V", at = @At("TAIL"))
	private void opeope$playerKinematics(LivingEntity livingEntity, float f, float g, float h, float i, float j, CallbackInfo ci) {
		PlayerAnimClient.applyBipedAngles(livingEntity, (BipedEntityModel<?>) (Object) this, f, g, h, i, j);
	}
}
