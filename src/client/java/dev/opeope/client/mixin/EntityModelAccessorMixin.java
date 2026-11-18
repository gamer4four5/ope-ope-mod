package dev.opeope.client.mixin;

import dev.opeope.client.dismember.ClientDismember;
import net.minecraft.client.render.entity.model.EntityModel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Las capas que copian la postura del modelo principal (la lana de la oveja, la ropa del aldeano, la capa exterior del
 * ahogado…) ocultan también las partes cortadas: copyStateTo es justo el paso en el que una capa toma el estado del
 * modelo del ser.
 */
@Mixin(EntityModel.class)
public abstract class EntityModelAccessorMixin {
	@Inject(method = "copyStateTo", at = @At("RETURN"), require = 0)
	private void opeope$mirrorHidden(EntityModel<?> copy, CallbackInfo ci) {
		ClientDismember.mirrorHidden(copy);
	}
}
