package dev.opeope.item;

import dev.opeope.power.PowerManager;
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

/**
 * Mes: el corazón de alguien dentro de un cubo quirúrgico.
 * - Clic derecho: aprieta el corazón y daña/aturde a su dueño.
 * - Shift + Clic derecho (agachado): realiza un Trasplante Quirúrgico de Corazón, otorgándote vida máxima y fuerza permanentes.
 */
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
			if (sp.isSneaking()) {
				Techniques.transplantHeart(sp, PowerManager.get(sp), hand);
				return TypedActionResult.consume(user.getStackInHand(hand));
			}
			Techniques.squeezeHeart(sp, stack);
		}
		user.getItemCooldownManager().set(this, 8);
		return TypedActionResult.success(stack, world.isClient);
	}

	/** Si su dueño muere (por lo que sea), el corazón deja de latir y el cubo desaparece. */
	@Override
	public void inventoryTick(ItemStack stack, World world, net.minecraft.entity.Entity entity, int slot, boolean selected) {
		if (world.isClient || world.getTime() % 20 != 0) return;
		UUID o = owner(stack);
		if (o != null && dev.opeope.power.Dismember.DEAD_HEARTS.contains(o)) stack.setCount(0);
	}

	@Override
	public boolean hasGlint(ItemStack stack) {
		return true;
	}
}
