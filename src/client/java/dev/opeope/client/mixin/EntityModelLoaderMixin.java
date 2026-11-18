package dev.opeope.client.mixin;

import dev.opeope.client.dismember.ModelParts;
import net.minecraft.client.model.ModelPart;
import net.minecraft.client.render.entity.model.EntityModelLayer;
import net.minecraft.client.render.entity.model.EntityModelLoader;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Cada árbol de ModelPart que se crea para un modelo de entidad: se apunta qué parte pertenece a qué raíz, para poder
 * leer por nombre cualquier modelo (también los que guardan sus partes en campos sin nombre, como el pollo o el lobo).
 */
@Mixin(EntityModelLoader.class)
public abstract class EntityModelLoaderMixin {
	@Inject(method = "getModelPart", at = @At("RETURN"), require = 0)
	private void opeope$rememberRoot(EntityModelLayer layer, CallbackInfoReturnable<ModelPart> cir) {
		ModelParts.registerRoot(cir.getReturnValue());
	}
}
