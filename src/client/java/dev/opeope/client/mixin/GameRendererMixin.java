package dev.opeope.client.mixin;

import dev.opeope.client.room.CameraFx;
import net.minecraft.client.render.Camera;
import net.minecraft.client.render.GameRenderer;
import net.minecraft.client.util.math.MatrixStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * La cámara del ROOM (spec §5.6): los golpes de campo de visión se suman al del juego (solo en la vista del mundo, no en
 * la de la mano) y el cabeceo y el temblor giran la proyección, así que la mira no se mueve.
 */
@Mixin(GameRenderer.class)
public abstract class GameRendererMixin {
	@Inject(method = "getFov", at = @At("RETURN"), cancellable = true)
	private void opeope$roomFov(Camera camera, float tickDelta, boolean changingFov, CallbackInfoReturnable<Double> cir) {
		if (!changingFov) return;
		double off = CameraFx.fovOffset(tickDelta);
		if (off != 0.0) cir.setReturnValue(cir.getReturnValue() + off);
	}

	@Inject(method = "tiltViewWhenHurt", at = @At("HEAD"))
	private void opeope$roomShake(MatrixStack matrices, float tickDelta, CallbackInfo ci) {
		CameraFx.apply(matrices, tickDelta);
	}
}
