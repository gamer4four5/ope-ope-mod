package dev.opeope.mixin;

import net.minecraft.entity.mob.MobEntity;
import net.minecraft.sound.SoundEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/** El sonido de ambiente de un mob (para que su cabeza cortada siga «hablando»). */
@Mixin(MobEntity.class)
public interface MobEntityInvoker {
	@Invoker("getAmbientSound")
	SoundEvent opeope$ambientSound();
}
