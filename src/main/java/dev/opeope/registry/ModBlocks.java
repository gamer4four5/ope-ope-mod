package dev.opeope.registry;

import dev.opeope.OpeOpeMod;
import dev.opeope.block.MeraFireBlock;
import net.minecraft.block.AbstractBlock;
import net.minecraft.block.Block;
import net.minecraft.block.MapColor;
import net.minecraft.block.piston.PistonBehavior;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.sound.BlockSoundGroup;

public final class ModBlocks {
	private ModBlocks() {}

	/** El fuego de Ace en el suelo (sin dibujo de bloque: lo pinta el fuego 3D del cliente). */
	public static Block MERA_FIRE;

	public static void init() {
		MERA_FIRE = Registry.register(Registries.BLOCK, OpeOpeMod.id("fuego_mera"), new MeraFireBlock(AbstractBlock.Settings.create()
			.mapColor(MapColor.BRIGHT_RED)
			.replaceable()
			.noCollision()
			.breakInstantly()
			.luminance(state -> 15)
			.sounds(BlockSoundGroup.WOOL)
			.pistonBehavior(PistonBehavior.DESTROY)
			.dropsNothing()));
	}
}
