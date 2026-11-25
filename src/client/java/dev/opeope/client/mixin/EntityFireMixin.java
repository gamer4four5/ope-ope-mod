package dev.opeope.client.mixin;

import dev.opeope.client.mera.MeraFxClient;
import net.minecraft.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Lo que arde cerca se dibuja con llamas 3D (MeraFxClient.emitBurning): a esos Minecraft no les pone encima sus dos
 * láminas de fuego. Solo en el mundo del cliente y solo mientras el fuego 3D funciona.
 */
@Mixin(Entity.class)
public abstract class EntityFireMixin {
	@Inject(method = "doesRenderOnFire", at = @At("HEAD"), cancellable = true)
	private void opeope$fire3D(CallbackInfoReturnable<Boolean> cir) {
		if (MeraFxClient.burns3D((Entity) (Object) this)) cir.setReturnValue(false);
	}
}
