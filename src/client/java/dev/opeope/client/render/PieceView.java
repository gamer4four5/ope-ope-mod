package dev.opeope.client.render;

import dev.opeope.entity.BlockSet;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.fluid.FluidState;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.BlockRenderView;
import net.minecraft.world.LightType;
import net.minecraft.world.biome.ColorResolver;
import net.minecraft.world.chunk.light.LightingProvider;
import org.jetbrains.annotations.Nullable;

/**
 * El mundo que ve el dibujo de los bloques de una pieza: solo sus propios bloques (así se tapan las caras que se tocan
 * dentro de ella y las esquinas se sombrean como en el suelo) y aire alrededor. La luz y el color del bioma se toman del
 * sitio donde está la pieza ahora; la luz, la mayor entre ese sitio y justo encima de la pieza (la regla de siempre,
 * ahora cara a cara). Sus coordenadas son seedBase + la posición local del bloque.
 */
final class PieceView implements BlockRenderView {
	private static final BlockState AIR = Blocks.AIR.getDefaultState();

	private final BlockRenderView world;
	private final BlockPos base;
	private final Long2ObjectOpenHashMap<BlockState> states;
	// solo se usan durante una llamada al mundo, en el hilo que dibuja
	private final BlockPos.Mutable at = new BlockPos.Mutable();
	private final BlockPos.Mutable top = new BlockPos.Mutable();
	private int shx, shy, shz;

	PieceView(BlockRenderView world, BlockSet set, BlockPos seedBase) {
		this.world = world;
		this.base = seedBase;
		this.states = new Long2ObjectOpenHashMap<>(Math.max(16, set.entries.size() * 2));
		for (BlockSet.Entry e : set.entries) {
			states.put(BlockPos.asLong(seedBase.getX() + e.x(), seedBase.getY() + e.y(), seedBase.getZ() + e.z()), e.state());
		}
		top.set(seedBase.getX(), seedBase.getY() + set.sy, seedBase.getZ());
	}

	/** Pone la vista donde está la pieza ahora (pos: la posición de la entidad, abajo en el centro). */
	void moveTo(Vec3d pos, BlockSet set) {
		shx = (int) Math.floor(pos.x - set.sx / 2.0 + 0.5) - base.getX();
		shy = (int) Math.floor(pos.y + 0.5) - base.getY();
		shz = (int) Math.floor(pos.z - set.sz / 2.0 + 0.5) - base.getZ();
		top.set((int) Math.floor(pos.x), (int) Math.floor(pos.y + set.sy + 0.5), (int) Math.floor(pos.z));
	}

	/** La celda del mundo donde está ahora la celda p de la vista. */
	private BlockPos shifted(BlockPos p) {
		return at.set(p.getX() + shx, p.getY() + shy, p.getZ() + shz);
	}

	@Override
	public BlockState getBlockState(BlockPos pos) {
		BlockState st = states.get(pos.asLong());
		return st != null ? st : AIR;
	}

	@Override
	public FluidState getFluidState(BlockPos pos) {
		return getBlockState(pos).getFluidState();
	}

	@Nullable
	@Override
	public BlockEntity getBlockEntity(BlockPos pos) {
		return null;
	}

	@Override
	public int getHeight() {
		return world.getHeight();
	}

	@Override
	public int getBottomY() {
		return world.getBottomY();
	}

	@Override
	public float getBrightness(Direction direction, boolean shaded) {
		return world.getBrightness(direction, shaded);
	}

	@Override
	public LightingProvider getLightingProvider() {
		return world.getLightingProvider();
	}

	/** El tinte del bioma donde está la pieza ahora (la hierba se ve como la de alrededor). */
	@Override
	public int getColor(BlockPos pos, ColorResolver colorResolver) {
		return world.getColor(shifted(pos), colorResolver);
	}

	@Override
	public int getLightLevel(LightType type, BlockPos pos) {
		int here = world.getLightLevel(type, shifted(pos));
		return Math.max(here, world.getLightLevel(type, top));
	}

	@Override
	public int getBaseLightLevel(BlockPos pos, int ambientDarkness) {
		int here = world.getBaseLightLevel(shifted(pos), ambientDarkness);
		return Math.max(here, world.getBaseLightLevel(top, ambientDarkness));
	}
}
