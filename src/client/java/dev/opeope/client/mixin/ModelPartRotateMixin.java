package dev.opeope.client.mixin;

import dev.opeope.client.dismember.ClientDismember;
import net.minecraft.client.model.ModelPart;
import net.minecraft.client.util.math.MatrixStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Re-oculta las partes desmembradas justo cuando cada {@link ModelPart} empieza a aplicarse, evitando que scripts
 * de animación externos (EMF/FreshAnimations {@code root.animate()}) vuelvan visible un hueso cortado a mitad del render.
 */
@Mixin(ModelPart.class)
public abstract class ModelPartRotateMixin {
	@Inject(method = "rotate(Lnet/minecraft/client/util/math/MatrixStack;)V", at = @At("HEAD"), require = 0)
	private void opeope$rehideCutParts(MatrixStack matrices, CallbackInfo ci) {
		ClientDismember.rehideActive();
	}
}
