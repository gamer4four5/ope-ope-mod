package dev.opeope.item;

import dev.opeope.power.Techniques;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.NbtComponent;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.Hand;
import net.minecraft.util.TypedActionResult;
import net.minecraft.world.World;

import java.util.UUID;

/** Mes: el corazón de alguien dentro de un cubo. Clic derecho lo aprieta. */
public class HeartItem extends Item {
	public HeartItem(Settings settings) {
		super(settings);
	}

	public static UUID owner(ItemStack stack) {
		NbtComponent data = stack.get(DataComponentTypes.CUSTOM_DATA);
		if (data == null) return null;
		NbtCompound nbt = data.copyNbt();
		return nbt.containsUuid("owner") ? nbt.getUuid("owner") : null;
	}

	public static void setOwner(ItemStack stack, UUID owner) {
		NbtCompound nbt = new NbtCompound();
		nbt.putUuid("owner", owner);
		stack.set(DataComponentTypes.CUSTOM_DATA, NbtComponent.of(nbt));
	}

	@Override
	public TypedActionResult<ItemStack> use(World world, PlayerEntity user, Hand hand) {
		ItemStack stack = user.getStackInHand(hand);
		if (!world.isClient && user instanceof ServerPlayerEntity sp) {
			Techniques.squeezeHeart(sp, stack);
		}
		user.getItemCooldownManager().set(this, 8);
		return TypedActionResult.success(stack, world.isClient);
	}

	@Override
	public boolean hasGlint(ItemStack stack) {
		return true;
	}
}
