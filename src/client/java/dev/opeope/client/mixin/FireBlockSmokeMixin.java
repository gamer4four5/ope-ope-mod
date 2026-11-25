package dev.opeope.client.mixin;

import dev.opeope.client.mera.MeraFxClient;
import net.minecraft.block.AbstractFireBlock;
import net.minecraft.particle.ParticleEffect;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * El humo de los bloques de fuego: Minecraft suelta cuadraditos negros que suben (se veían como manchones negros junto al
 * fuego 3D de la Mera Mera). Con el fuego 3D activo sale humo suave; sin él, el de siempre.
 */
@Mixin(AbstractFireBlock.class)
public abstract class FireBlockSmokeMixin {
	@Redirect(method = "randomDisplayTick", at = @At(value = "INVOKE",
		target = "Lnet/minecraft/world/World;addParticle(Lnet/minecraft/particle/ParticleEffect;DDDDDD)V"))
	private void opeope$softSmoke(World world, ParticleEffect effect, double x, double y, double z, double vx, double vy, double vz) {
		if (effect == ParticleTypes.LARGE_SMOKE && MeraFxClient.fireSmoke(x, y, z)) return;
		world.addParticle(effect, x, y, z, vx, vy, vz);
	}
}
