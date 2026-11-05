package dev.opeope.item;

import dev.opeope.power.PowerManager;
import net.minecraft.entity.LivingEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.world.World;

/** La Ope Ope no Mi. Al comerla, el jugador recibe el poder (para siempre, también tras morir). */
public class FruitItem extends Item {
	public FruitItem(Settings settings) {
		super(settings);
	}

	@Override
	public ItemStack finishUsing(ItemStack stack, World world, LivingEntity user) {
		ItemStack result = super.finishUsing(stack, world, user);
		if (!world.isClient && user instanceof ServerPlayerEntity player) {
			PowerManager.grantFruit(player);
		}
		return result;
	}

	@Override
	public boolean hasGlint(ItemStack stack) {
		return true;
	}
}
