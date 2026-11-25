package dev.opeope.block;

import com.mojang.serialization.MapCodec;
import net.minecraft.block.AbstractBlock;
import net.minecraft.block.AbstractFireBlock;
import net.minecraft.block.Block;
import net.minecraft.block.BlockRenderType;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.random.Random;
import net.minecraft.world.BlockView;
import net.minecraft.world.World;
import net.minecraft.world.WorldAccess;
import net.minecraft.world.WorldView;

/**
 * El fuego que dejan las técnicas de Ace en el suelo. Quema como el de Minecraft (prende a quien lo pisa, alumbra, se
 * apaga a golpes o con agua) pero no tiene dibujo propio: el cliente lo pinta con llamas 3D que acaban en humo, en vez
 * de las láminas pixeladas. Dura unos segundos; si al lado hay algo que arde (madera, lana, hojas…), al apagarse deja
 * ahí el fuego normal de Minecraft, que sigue quemándolo y extendiéndose como siempre.
 */
public class MeraFireBlock extends AbstractFireBlock {
	public static final MapCodec<MeraFireBlock> CODEC = createCodec(MeraFireBlock::new);

	public MeraFireBlock(AbstractBlock.Settings settings) {
		super(settings, 1.0f);
	}

	@Override
	public MapCodec<MeraFireBlock> getCodec() {
		return CODEC;
	}

	/** Sin dibujo de bloque (tampoco con Sodium): las llamas las pone el fuego 3D del cliente. */
	@Override
	protected BlockRenderType getRenderType(BlockState state) {
		return BlockRenderType.INVISIBLE;
	}

	@Override
	protected boolean isFlammable(BlockState state) {
		return state.isBurnable();
	}

	/** Sobre suelo firme, o pegado a algo que arde. */
	@Override
	protected boolean canPlaceAt(BlockState state, WorldView world, BlockPos pos) {
		BlockPos down = pos.down();
		return world.getBlockState(down).isSideSolidFullSquare(world, down, Direction.UP) || burnableAround(world, pos);
	}

	@Override
	protected BlockState getStateForNeighborUpdate(BlockState state, Direction direction, BlockState neighborState, WorldAccess world,
		BlockPos pos, BlockPos neighborPos) {
		return canPlaceAt(state, world, pos) ? state : Blocks.AIR.getDefaultState();
	}

	@Override
	protected void onBlockAdded(BlockState state, World world, BlockPos pos, BlockState oldState, boolean notify) {
		super.onBlockAdded(state, world, pos, oldState, notify);
		// unos segundos; bajo la lluvia, menos
		int life = world.hasRain(pos) ? 20 + world.random.nextInt(20) : 60 + world.random.nextInt(50);
		world.scheduleBlockTick(pos, this, life);
	}

	@Override
	protected void scheduledTick(BlockState state, ServerWorld world, BlockPos pos, Random random) {
		// al lado hay algo que arde (y no llueve): sigue ardiendo con el fuego de Minecraft
		if (!world.hasRain(pos) && burnableAround(world, pos)) {
			BlockState fire = AbstractFireBlock.getState(world, pos);
			if (fire.canPlaceAt(world, pos)) {
				world.setBlockState(pos, fire, Block.NOTIFY_ALL);
				return;
			}
		}
		world.removeBlock(pos, false);
	}

	/** ¿Hay al lado algo que arde (lo que prende la lava: madera, lana, hojas…)? */
	public static boolean burnableAround(BlockView world, BlockPos pos) {
		BlockPos.Mutable m = new BlockPos.Mutable();
		for (Direction d : Direction.values()) {
			m.set(pos.getX() + d.getOffsetX(), pos.getY() + d.getOffsetY(), pos.getZ() + d.getOffsetZ());
			if (world.getBlockState(m).isBurnable()) return true;
		}
		return false;
	}
}
