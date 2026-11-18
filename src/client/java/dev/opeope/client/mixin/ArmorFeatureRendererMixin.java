package dev.opeope.client.mixin;

import dev.opeope.client.dismember.ClientDismember;
import dev.opeope.client.dismember.ModelParts;
import net.minecraft.client.model.ModelPart;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.entity.feature.ArmorFeatureRenderer;
import net.minecraft.client.render.entity.model.BipedEntityModel;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;

/** La armadura de una parte cortada (el casco con la cabeza, las botas con las piernas) no se dibuja en el cuerpo. */
@Mixin(ArmorFeatureRenderer.class)
public class ArmorFeatureRendererMixin {
	private static LivingEntity opeope$current;

	@Inject(method = "renderArmor", at = @At("HEAD"), require = 0)
	private void opeope$who(MatrixStack matrices, VertexConsumerProvider vcp, LivingEntity entity, EquipmentSlot slot, int light,
							BipedEntityModel<?> model, CallbackInfo ci) {
		opeope$current = entity;
	}

	@Inject(method = "setVisible", at = @At("TAIL"), require = 0)
	private void opeope$hide(BipedEntityModel<?> model, EquipmentSlot slot, CallbackInfo ci) {
		LivingEntity e = opeope$current;
		if (e == null || ClientDismember.info(e) == null) return;
		for (String name : ClientDismember.hiddenParts(e)) {
			List<ModelPart> chain = ModelParts.chain(model, name);
			if (chain != null) chain.get(chain.size() - 1).visible = false;
		}
	}
}
