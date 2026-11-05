package dev.opeope.client.mixin;

import dev.opeope.client.input.InputHandler;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.Mouse;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Mouse.class)
public class MouseMixin {
	@Inject(method = "onMouseButton", at = @At("HEAD"), cancellable = true)
	private void opeope$onButton(long window, int button, int action, int mods, CallbackInfo ci) {
		if (window != MinecraftClient.getInstance().getWindow().getHandle()) return;
		if (InputHandler.onMouse(button, action)) ci.cancel();
	}

	@Inject(method = "onMouseScroll", at = @At("HEAD"), cancellable = true)
	private void opeope$onScroll(long window, double horizontal, double vertical, CallbackInfo ci) {
		if (window != MinecraftClient.getInstance().getWindow().getHandle()) return;
		if (MinecraftClient.getInstance().currentScreen != null) return;
		if (InputHandler.onScroll(vertical)) ci.cancel();
	}
}
