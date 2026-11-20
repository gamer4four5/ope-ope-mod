package dev.opeope.client.mixin;

import dev.opeope.client.dismember.VolumeSliceConsumer;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.VertexConsumerProvider;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Envuelve las llamadas directas a {@link VertexConsumerProvider.Immediate#getBuffer} mientras hay un corte CSG activo,
 * para que las capas con textura anulada o emisivas (EMF/ETF) también queden recortadas por el volumen del corte.
 */
@Mixin(VertexConsumerProvider.Immediate.class)
public abstract class VertexConsumerProviderImmediateMixin {
	@Inject(method = "getBuffer", at = @At("RETURN"), cancellable = true, require = 0)
	private void opeope$wrapSliceBuffer(RenderLayer layer, CallbackInfoReturnable<VertexConsumer> cir) {
		VertexConsumer raw = cir.getReturnValue();
		VertexConsumer wrapped = VolumeSliceConsumer.wrapIfActive((VertexConsumerProvider) (Object) this, layer, raw);
		if (wrapped != raw) cir.setReturnValue(wrapped);
	}
}
