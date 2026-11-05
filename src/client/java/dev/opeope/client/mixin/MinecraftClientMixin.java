package dev.opeope.client.mixin;

import dev.opeope.client.ClientState;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Scan: ves el contorno de los seres a través de las paredes, como rayos X. */
@Mixin(MinecraftClient.class)
public class MinecraftClientMixin {
	@Inject(method = "hasOutline", at = @At("HEAD"), cancellable = true)
	private void opeope$scan(Entity entity, CallbackInfoReturnable<Boolean> cir) {
		if (ClientState.scanTicks <= 0) return;
		MinecraftClient mc = (MinecraftClient) (Object) this;
		if (entity instanceof LivingEntity && entity != mc.player && mc.player != null && entity.squaredDistanceTo(mc.player) < 64 * 64) {
			cir.setReturnValue(true);
		}
	}
}
