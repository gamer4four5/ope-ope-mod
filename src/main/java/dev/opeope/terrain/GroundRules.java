package dev.opeope.terrain;

import dev.opeope.entity.BlockSet;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.FluidBlock;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.fluid.FluidState;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.registry.tag.TagKey;
import net.minecraft.sound.BlockSoundGroup;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.BlockView;
import net.minecraft.world.EmptyBlockView;
import net.minecraft.world.World;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * Las reglas del suelo, iguales en el servidor y en el cliente: qué se puede cortar, qué es terreno, dónde está la
 * superficie, de qué material es un bloque y cuánto pesa.
 * <p>
 * Solo lee el mundo a través de un BlockView y no guarda nada mutable: el hilo del cliente (la vista previa) y el del
 * servidor integrado (el arranque) lo usan a la vez.
 */
public final class GroundRules {
	private GroundRules() {}

	public static final int SOIL = 0, STONE = 1, WOOD = 2, GLASS = 3, METAL = 4, SAND = 5, SNOW = 6, PLANT = 7, OTHER = 8;
	/** surfaceNear no encontró superficie. */
	public static final int NONE = Integer.MIN_VALUE;

	/** Las etiquetas de bloque que cuentan como terreno: piedra, tierra, arena, grava, menas… */
	private static final List<TagKey<Block>> TERRAIN_TAGS = List.of(tag("sculk_replaceable"), tag("coal_ores"),
		tag("iron_ores"), tag("copper_ores"), tag("gold_ores"), tag("redstone_ores"), tag("lapis_ores"),
		tag("diamond_ores"), tag("emerald_ores"));
	private static final TagKey<Block> DIRT = tag("dirt");

	private static TagKey<Block> tag(String name) {
		return TagKey.of(RegistryKeys.BLOCK, Identifier.ofVanilla(name));
	}

	/** Lo que el corte puede separar (la regla de siempre de CutEngine): no aire, no un fluido, no irrompible. */
	public static boolean cuttable(BlockState st, BlockView v, BlockPos pos) {
		if (st.isAir()) return false;
		if (st.getBlock() instanceof FluidBlock) return false;
		return st.getHardness(v, pos) >= 0;
	}

	/**
	 * ¿Es suelo? Piedra, tierra, arena, grava, terracota, arcilla, rocas del Nether y del End, las menas, y además nieve
	 * en bloque, hielo, caminos, tierra de cultivo, barro compacto y magma. Los adoquines, los tablones, los troncos, el
	 * cristal, la lana o las hojas no lo son: van encima.
	 */
	public static boolean terrain(BlockState st) {
		for (TagKey<Block> t : TERRAIN_TAGS) if (st.isIn(t)) return true;
		Block b = st.getBlock();
		return b == Blocks.SNOW_BLOCK || b == Blocks.ICE || b == Blocks.PACKED_ICE || b == Blocks.BLUE_ICE
			|| b == Blocks.DIRT_PATH || b == Blocks.FARMLAND || b == Blocks.PACKED_MUD || b == Blocks.MAGMA_BLOCK
			|| b == Blocks.NETHER_QUARTZ_ORE;
	}

	/** Aire o algo que se reemplaza al poner un bloque (hierba corta, agua, nieve fina…). */
	public static boolean replaceable(BlockState st) {
		return st.isAir() || st.isReplaceable();
	}

	/** Un bloque que sostiene: no se reemplaza y tiene forma de choque. */
	public static boolean solid(BlockState st, BlockView v, BlockPos pos) {
		return !replaceable(st) && !st.getCollisionShape(v, pos).isEmpty();
	}

	/** Una planta: se puede cortar, no es terreno y no tiene forma de choque (flores, hierba, setas, retoños…). */
	public static boolean plantLike(BlockState st, BlockView v, BlockPos pos) {
		return cuttable(st, v, pos) && !terrain(st) && st.getCollisionShape(v, pos).isEmpty();
	}

	/**
	 * La superficie de la columna (x, z) más cercana a yRef, a ± span: la y cuyo bloque es terreno y el de encima no.
	 * Se prueba en el orden yRef, yRef−1, yRef+1, yRef−2, yRef+2… y solo con bottomY ≤ y &lt; topY − 1. NONE si no hay.
	 * Así el suelo de una cueva o un túnel es suelo como cualquier otro, y un techo encima no lo tapa.
	 */
	public static int surfaceNear(BlockView v, int x, int z, int yRef, int span) {
		int bottom = v.getBottomY(), top = v.getTopY();
		BlockPos.Mutable m = new BlockPos.Mutable();
		for (int k = 0; k <= 2 * span; k++) {
			int y = yRef + (k == 0 ? 0 : (k & 1) == 1 ? -(k + 1) / 2 : k / 2);
			if (y < bottom || y >= top - 1) continue;
			if (!terrain(v.getBlockState(m.set(x, y, z)))) continue;
			if (!terrain(v.getBlockState(m.set(x, y + 1, z)))) return y;
		}
		return NONE;
	}

	/** La familia del material (para el peso, las partículas y los sonidos). La primera regla que encaja gana. */
	public static int family(BlockState st) {
		BlockSoundGroup g = st.getSoundGroup();
		if (g == BlockSoundGroup.GLASS) return GLASS;
		if (g == BlockSoundGroup.WOOD || g == BlockSoundGroup.NETHER_WOOD || g == BlockSoundGroup.CHERRY_WOOD
			|| g == BlockSoundGroup.BAMBOO_WOOD) return WOOD;
		if (g == BlockSoundGroup.METAL || g == BlockSoundGroup.COPPER || g == BlockSoundGroup.ANVIL
			|| g == BlockSoundGroup.CHAIN || g == BlockSoundGroup.NETHERITE) return METAL;
		if (g == BlockSoundGroup.SNOW || g == BlockSoundGroup.POWDER_SNOW) return SNOW;
		if (g == BlockSoundGroup.SAND || g == BlockSoundGroup.GRAVEL || g == BlockSoundGroup.SUSPICIOUS_SAND
			|| g == BlockSoundGroup.SUSPICIOUS_GRAVEL) return SAND;
		if ((g == BlockSoundGroup.GRASS && !emptyShape(st)) || g == BlockSoundGroup.ROOTED_DIRT || g == BlockSoundGroup.MUD
			|| g == BlockSoundGroup.PACKED_MUD || g == BlockSoundGroup.MOSS_BLOCK || st.isIn(DIRT)) return SOIL;
		if (g == BlockSoundGroup.STONE || g == BlockSoundGroup.DEEPSLATE || g == BlockSoundGroup.TUFF
			|| g == BlockSoundGroup.CALCITE || g == BlockSoundGroup.BASALT || g == BlockSoundGroup.NETHERRACK
			|| terrain(st)) return STONE;
		if (emptyShape(st)) return PLANT;
		return OTHER;
	}

	/** Sin forma de choque, mirado fuera del mundo (para quien no tiene una vista a mano). */
	private static boolean emptyShape(BlockState st) {
		return st.getCollisionShape(EmptyBlockView.INSTANCE, BlockPos.ORIGIN).isEmpty();
	}

	/** Kilos por bloque según la familia. */
	public static double density(BlockState st) {
		return switch (family(st)) {
			case SOIL -> 1900;
			case STONE -> 2600;
			case SAND -> 1600;
			case WOOD -> 700;
			case GLASS -> 2500;
			case METAL -> 7800;
			case SNOW -> 400;
			case PLANT -> 300;
			default -> 1500;
		};
	}

	/** El peso de una pieza en kilos: la suma de sus bloques. */
	public static double mass(BlockSet set) {
		double kg = 0;
		for (BlockSet.Entry e : set.entries) kg += density(e.state());
		return kg;
	}

	/**
	 * Una vista del mundo para el servidor que nunca carga nada: donde el chunk no está cargado lee aire, igual que el
	 * cliente. El fluido es el del estado. Un objeto por llamada, sin nada mutable dentro.
	 */
	public static BlockView loaded(World w) {
		return new Loaded(w);
	}

	private record Loaded(World w) implements BlockView {
		@Nullable
		@Override
		public BlockEntity getBlockEntity(BlockPos pos) {
			return w.isChunkLoaded(pos) ? w.getBlockEntity(pos) : null;
		}

		@Override
		public BlockState getBlockState(BlockPos pos) {
			return w.isChunkLoaded(pos) ? w.getBlockState(pos) : Blocks.AIR.getDefaultState();
		}

		@Override
		public FluidState getFluidState(BlockPos pos) {
			return getBlockState(pos).getFluidState();
		}

		@Override
		public int getHeight() {
			return w.getHeight();
		}

		@Override
		public int getBottomY() {
			return w.getBottomY();
		}
	}
}
