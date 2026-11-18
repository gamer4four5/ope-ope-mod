package dev.opeope.mixin;

import dev.opeope.power.Dismember;
import net.minecraft.entity.EntityDimensions;
import net.minecraft.entity.EntityPose;
import net.minecraft.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Sin piernas la caja del ser es más baja (el cuerpo baja la longitud de la pierna). En los dos lados. */
@Mixin(LivingEntity.class)
public class LivingEntityMixin {
	@Inject(method = "getDimensions", at = @At("RETURN"), cancellable = true)
	private void opeope$legless(EntityPose pose, CallbackInfoReturnable<EntityDimensions> cir) {
		float s = Dismember.heightScale((LivingEntity) (Object) this);
		if (s < 0.999f) cir.setReturnValue(cir.getReturnValue().scaled(1f, s));
	}
}
