package dev.opeope.mixin;

import dev.opeope.power.mera.MeraPowers;
import net.minecraft.entity.Entity;
import net.minecraft.entity.projectile.ProjectileEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Logia (Mera Mera no Mi): las flechas, tridentes y bolas atraviesan el cuerpo de fuego en vez de clavarse. */
@Mixin(ProjectileEntity.class)
public abstract class ProjectileEntityMixin {
	@Inject(method = "canHit(Lnet/minecraft/entity/Entity;)Z", at = @At("HEAD"), cancellable = true, require = 0)
	private void opeope$logiaPassThrough(Entity entity, CallbackInfoReturnable<Boolean> cir) {
		ProjectileEntity self = (ProjectileEntity) (Object) this;
		if (!self.getWorld().isClient && MeraPowers.projectilePasses(self, entity)) cir.setReturnValue(false);
	}
}
