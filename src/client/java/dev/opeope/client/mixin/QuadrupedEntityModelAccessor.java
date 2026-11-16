package dev.opeope.client.mixin;

import net.minecraft.client.model.ModelPart;
import net.minecraft.client.render.entity.model.QuadrupedEntityModel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Las partes de un cuadrúpedo (vaca, cerdo, oveja…), que no son públicas. */
@Mixin(QuadrupedEntityModel.class)
public interface QuadrupedEntityModelAccessor {
	@Accessor("head")
	ModelPart opeope$head();

	@Accessor("body")
	ModelPart opeope$body();

	@Accessor("rightHindLeg")
	ModelPart opeope$rightHindLeg();

	@Accessor("leftHindLeg")
	ModelPart opeope$leftHindLeg();

	@Accessor("rightFrontLeg")
	ModelPart opeope$rightFrontLeg();

	@Accessor("leftFrontLeg")
	ModelPart opeope$leftFrontLeg();
}
