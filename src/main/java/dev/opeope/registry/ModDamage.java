package dev.opeope.registry;

import dev.opeope.OpeOpeMod;
import net.minecraft.entity.Entity;
import net.minecraft.entity.damage.DamageSource;
import net.minecraft.entity.damage.DamageType;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.world.World;

public final class ModDamage {
	private ModDamage() {}

	/** Gamma Knife y Shock Wille: dañan por dentro y no miran la armadura (tag bypasses_armor). */
	public static final RegistryKey<DamageType> GAMMA = RegistryKey.of(RegistryKeys.DAMAGE_TYPE, OpeOpeMod.id("gamma_knife"));
	public static final RegistryKey<DamageType> SURGEON = RegistryKey.of(RegistryKeys.DAMAGE_TYPE, OpeOpeMod.id("surgeon"));

	public static void init() {}

	public static DamageSource of(World world, RegistryKey<DamageType> key, Entity attacker) {
		return new DamageSource(world.getRegistryManager().get(RegistryKeys.DAMAGE_TYPE).entryOf(key), attacker);
	}
}
