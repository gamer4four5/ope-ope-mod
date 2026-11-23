package dev.opeope.mixin;

import dev.opeope.power.Dismember;
import dev.opeope.power.SilentRoom;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.mob.MobEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Un mob sin cabeza no ve: no puede fijar un objetivo (lo intenten sus TargetGoal o lo que sea). Tampoco el Evocador o
 * el Ilusionista dentro de un R-ROOM Silent: sin voz no hay conjuro (ni aunque le hayan pegado y quiera vengarse).
 */
@Mixin(MobEntity.class)
public class MobEntityMixin {
	@Inject(method = "setTarget", at = @At("HEAD"), cancellable = true)
	private void opeope$blind(LivingEntity target, CallbackInfo ci) {
		if (target == null) return;
		MobEntity self = (MobEntity) (Object) this;
		if (Dismember.blind(self) || (!self.getWorld().isClient && SilentRoom.mutesSpells(self))) ci.cancel();
	}
}
