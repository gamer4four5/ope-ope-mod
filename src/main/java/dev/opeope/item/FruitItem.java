package dev.opeope.item;

import dev.opeope.power.PowerManager;
import net.minecraft.entity.LivingEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.world.World;

/** Fruta del diablo (Ope Ope no Mi o Gravity / Zushi Zushi no Mi). Al comerla, el jugador activa ese poder. */
public class FruitItem extends Item {
	private final int fruitKind;

	public FruitItem(Settings settings) {
		this(1, settings);
	}

	public FruitItem(int fruitKind, Settings settings) {
		super(settings);
		this.fruitKind = fruitKind;
	}

	@Override
	public ItemStack finishUsing(ItemStack stack, World world, LivingEntity user) {
		ItemStack result = super.finishUsing(stack, world, user);
		if (!world.isClient && user instanceof ServerPlayerEntity player) {
			PowerManager.grantFruit(player, fruitKind);
		}
		return result;
	}

	@Override
	public boolean hasGlint(ItemStack stack) {
		return true;
	}
}
