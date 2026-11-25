package dev.opeope.client.mixin;

import dev.opeope.client.mera.MeraFxClient;
import net.minecraft.block.BlockState;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.util.math.BlockPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Cada cambio de bloque que llega al cliente: así el fuego 3D sabe al momento dónde prende y dónde se apaga el fuego de Ace. */
@Mixin(ClientWorld.class)
public abstract class ClientWorldBlockMixin {
	@Inject(method = "updateListeners", at = @At("HEAD"))
	private void opeope$blockChanged(BlockPos pos, BlockState oldState, BlockState newState, int flags, CallbackInfo ci) {
		MeraFxClient.blockChanged((ClientWorld) (Object) this, pos, oldState, newState);
	}
}
