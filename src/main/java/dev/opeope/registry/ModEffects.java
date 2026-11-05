package dev.opeope.registry;

import dev.opeope.OpeOpeMod;
import net.minecraft.entity.attribute.EntityAttributeModifier;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.entity.effect.StatusEffect;
import net.minecraft.entity.effect.StatusEffectCategory;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.registry.entry.RegistryEntry;

public final class ModEffects {
	private ModEffects() {}

	public static RegistryEntry<StatusEffect> STUN;   // Aturdido: Counter Shock, Shock Wille, apretar un corazón
	public static RegistryEntry<StatusEffect> CUT;    // Cortado: piezas vivas, no se mueven ni atacan

	static class SimpleEffect extends StatusEffect {
		SimpleEffect(StatusEffectCategory category, int color) {
			super(category, color);
		}
	}

	private static StatusEffect frozen(String name, int color) {
		return new SimpleEffect(StatusEffectCategory.HARMFUL, color)
			.addAttributeModifier(EntityAttributes.GENERIC_MOVEMENT_SPEED, OpeOpeMod.id(name + "_speed"), -1.0, EntityAttributeModifier.Operation.ADD_MULTIPLIED_TOTAL)
			.addAttributeModifier(EntityAttributes.GENERIC_ATTACK_DAMAGE, OpeOpeMod.id(name + "_damage"), -1.0, EntityAttributeModifier.Operation.ADD_MULTIPLIED_TOTAL)
			.addAttributeModifier(EntityAttributes.GENERIC_JUMP_STRENGTH, OpeOpeMod.id(name + "_jump"), -1.0, EntityAttributeModifier.Operation.ADD_MULTIPLIED_TOTAL)
			.addAttributeModifier(EntityAttributes.GENERIC_FLYING_SPEED, OpeOpeMod.id(name + "_fly"), -1.0, EntityAttributeModifier.Operation.ADD_MULTIPLIED_TOTAL);
	}

	public static void init() {
		STUN = Registry.registerReference(Registries.STATUS_EFFECT, OpeOpeMod.id("aturdido"), frozen("aturdido", 0x8fd8ff));
		CUT = Registry.registerReference(Registries.STATUS_EFFECT, OpeOpeMod.id("cortado"), frozen("cortado", 0xd8606e));
	}
}
