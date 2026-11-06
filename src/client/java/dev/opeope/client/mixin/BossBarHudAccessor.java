package dev.opeope.client.mixin;

import net.minecraft.client.gui.hud.BossBarHud;
import net.minecraft.client.gui.hud.ClientBossBar;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.Map;
import java.util.UUID;

/** Las barras de jefe a la vista, para que el HUD de la fruta no las pise. */
@Mixin(BossBarHud.class)
public interface BossBarHudAccessor {
	@Accessor("bossBars")
	Map<UUID, ClientBossBar> getOpeopeBossBars();
}
