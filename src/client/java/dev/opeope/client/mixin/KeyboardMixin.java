package dev.opeope.client.mixin;

import dev.opeope.client.input.InputHandler;
import net.minecraft.client.Keyboard;
import net.minecraft.client.MinecraftClient;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Keyboard.class)
public class KeyboardMixin {
	@Inject(method = "onKey", at = @At("HEAD"), cancellable = true)
	private void opeope$onKey(long window, int key, int scancode, int action, int modifiers, CallbackInfo ci) {
		if (window != MinecraftClient.getInstance().getWindow().getHandle()) return;
		if (InputHandler.onKey(key, action)) ci.cancel();
	}
}
