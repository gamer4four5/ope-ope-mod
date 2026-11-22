package dev.opeope.registry;

import dev.opeope.OpeOpeMod;
import dev.opeope.item.FruitItem;
import dev.opeope.item.HeartItem;
import net.fabricmc.fabric.api.itemgroup.v1.FabricItemGroup;
import net.minecraft.component.type.FoodComponent;
import net.minecraft.item.Item;
import net.minecraft.item.ItemGroup;
import net.minecraft.item.ItemStack;
import net.minecraft.item.SwordItem;
import net.minecraft.item.ToolMaterials;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.text.Text;
import net.minecraft.util.Rarity;

public final class ModItems {
	private ModItems() {}

	public static Item FRUIT;
	public static Item GRAVITY_FRUIT;
	public static Item KIKOKU;
	public static Item YAKUZA_KASEN;
	public static Item HEART;
	public static Item MARKER_STONE;

	private static <T extends Item> T register(String name, T item) {
		return Registry.register(Registries.ITEM, OpeOpeMod.id(name), item);
	}

	public static void init() {
		FRUIT = register("ope_ope_no_mi", new FruitItem(1, new Item.Settings().maxCount(1).rarity(Rarity.EPIC)
			.food(new FoodComponent.Builder().nutrition(2).saturationModifier(0.1f).alwaysEdible().build())));
		GRAVITY_FRUIT = register("gravity_fruit", new FruitItem(2, new Item.Settings().maxCount(1).rarity(Rarity.EPIC)
			.food(new FoodComponent.Builder().nutrition(2).saturationModifier(0.1f).alwaysEdible().build())));
		KIKOKU = register("kikoku", new SwordItem(ToolMaterials.NETHERITE, new Item.Settings().fireproof().rarity(Rarity.EPIC)
			.attributeModifiers(SwordItem.createAttributeModifiers(ToolMaterials.NETHERITE, 5, -2.6f))));
		YAKUZA_KASEN = register("yakuza_kasen", new SwordItem(ToolMaterials.NETHERITE, new Item.Settings().fireproof().rarity(Rarity.EPIC)
			.attributeModifiers(SwordItem.createAttributeModifiers(ToolMaterials.NETHERITE, 6, -2.4f))));
		HEART = register("corazon", new HeartItem(new Item.Settings().maxCount(1).rarity(Rarity.RARE)));
		MARKER_STONE = register("piedra_marcador", new Item(new Item.Settings()));

		ItemGroup group = FabricItemGroup.builder()
			.icon(() -> new ItemStack(FRUIT))
			.displayName(Text.translatable("itemGroup.opeope"))
			.entries((ctx, entries) -> {
				entries.add(FRUIT);
				entries.add(GRAVITY_FRUIT);
				entries.add(KIKOKU);
				entries.add(YAKUZA_KASEN);
				entries.add(MARKER_STONE);
			})
			.build();
		Registry.register(Registries.ITEM_GROUP, OpeOpeMod.id("opeope"), group);
	}
}
