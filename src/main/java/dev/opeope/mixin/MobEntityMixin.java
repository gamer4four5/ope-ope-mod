package dev.opeope.mixin;

import dev.opeope.power.Dismember;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.mob.MobEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Un mob sin cabeza no ve: no puede fijar un objetivo (lo intenten sus TargetGoal o lo que sea). */
@Mixin(MobEntity.class)
public class MobEntityMixin {
	@Inject(method = "setTarget", at = @At("HEAD"), cancellable = true)
	private void opeope$blind(LivingEntity target, CallbackInfo ci) {
		if (target != null && Dismember.blind((MobEntity) (Object) this)) ci.cancel();
	}
}
