package dev.opeope.client.mixin;

import dev.opeope.client.room.ScreenFx;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.hud.InGameHud;
import net.minecraft.client.render.RenderTickCounter;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** La pantalla del ROOM (spec §5.7): el tinte de los bordes, el destello y las rayas, debajo de todo el HUD. */
@Mixin(InGameHud.class)
public abstract class InGameHudMixin {
	@Inject(method = "render", at = @At("HEAD"))
	private void opeope$roomScreen(DrawContext context, RenderTickCounter tickCounter, CallbackInfo ci) {
		ScreenFx.render(context, tickCounter);
	}
}
