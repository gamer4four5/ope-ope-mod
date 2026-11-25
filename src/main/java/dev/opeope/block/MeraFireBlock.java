package dev.opeope.block;

import com.mojang.serialization.MapCodec;
import net.minecraft.block.AbstractBlock;
import net.minecraft.block.AbstractFireBlock;
import net.minecraft.block.Block;
import net.minecraft.block.BlockRenderType;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.TntBlock;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.registry.tag.BlockTags;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.state.StateManager;
import net.minecraft.state.property.IntProperty;
import net.minecraft.state.property.Properties;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.random.Random;
import net.minecraft.world.BlockView;
import net.minecraft.world.GameRules;
import net.minecraft.world.World;
import net.minecraft.world.WorldAccess;
import net.minecraft.world.WorldView;

import java.util.function.BiPredicate;

/**
 * El fuego que dejan las técnicas de Ace en el suelo. Quema como el de Minecraft (prende a quien lo pisa, alumbra, se
 * apaga a golpes o con agua, consume la madera, las hojas y la hierba y salta a lo que tiene al lado) pero no tiene
 * dibujo propio: el cliente lo pinta con llamas 3D que acaban en humo, en vez de las láminas pixeladas.
 * <p>
 * Sin nada que quemar dura unos segundos. Con algo que arde al lado sigue ardiendo, y lo que prende es siempre fuego de
 * Ace, cada vez más gastado: quema lo que hay alrededor del golpe, pero no un bosque entero.
 */
public class MeraFireBlock extends AbstractFireBlock {
	public static final MapCodec<MeraFireBlock> CODEC = createCodec(MeraFireBlock::new);
	/** Lo gastado que está (0 nuevo … 15 a punto de apagarse). Lo que prende nace más gastado que su padre. */
	public static final IntProperty AGE = Properties.AGE_15;
	/**
	 * Lo pone el cliente: ¿este bloque ya lo dibuja con una llama 3D? Si no (muy lejos o demasiados a la vez), salen
	 * llamitas de Minecraft para que no sea un fuego invisible.
	 */
	public static BiPredicate<World, BlockPos> drawn3D = (w, p) -> true;

	public MeraFireBlock(AbstractBlock.Settings settings) {
		super(settings, 1.0f);
		setDefaultState(getStateManager().getDefaultState().with(AGE, 0));
	}

	@Override
	public MapCodec<MeraFireBlock> getCodec() {
		return CODEC;
	}

	@Override
	protected void appendProperties(StateManager.Builder<Block, BlockState> builder) {
		builder.add(AGE);
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
		if (!oldState.isOf(this)) world.scheduleBlockTick(pos, this, tickDelay(world.random));
	}

	/** Entre uno y casi dos segundos (el de Minecraft, entre 1,5 y 2). */
	private static int tickDelay(Random random) {
		return 20 + random.nextInt(15);
	}

	@Override
	protected void scheduledTick(BlockState state, ServerWorld world, BlockPos pos, Random random) {
		if (!state.canPlaceAt(world, pos)) {
			world.removeBlock(pos, false);
			return;
		}
		int age = state.get(AGE);
		// la lluvia lo apaga enseguida
		if (rainAround(world, pos) && random.nextFloat() < 0.35f + age * 0.04f) {
			world.removeBlock(pos, false);
			return;
		}
		boolean fuel = burnableAround(world, pos);
		boolean spreads = world.getGameRules().getBoolean(GameRules.DO_FIRE_TICK);
		int next = Math.min(15, age + 1 + random.nextInt(2));
		// sin nada que quemar: unos segundos y se apaga (el cliente lo deja en humo). Sin propagación de fuego
		// (doFireTick), arde algo más junto a la madera, pero no la consume
		if (!fuel || !spreads) {
			if (next >= (fuel ? 12 : 3)) {
				world.removeBlock(pos, false);
				return;
			}
			world.setBlockState(pos, state.with(AGE, next), Block.NO_REDRAW);
			world.scheduleBlockTick(pos, this, tickDelay(random));
			return;
		}
		if (next == 15 && random.nextInt(3) == 0) {
			world.removeBlock(pos, false);
			return;
		}
		world.setBlockState(pos, state.with(AGE, next), Block.NO_REDRAW);
		world.scheduleBlockTick(pos, this, tickDelay(random));
		// lo que toca se va quemando: se consume o se vuelve fuego de Ace (como hace el fuego de Minecraft)
		for (Direction d : Direction.values()) {
			burnSide(world, pos.offset(d), d.getAxis() == Direction.Axis.Y ? 250 : 300, random, next);
		}
		// y salta a los huecos de alrededor que tocan algo que arde; ya muy gastado, no
		if (next < 11) spread(world, pos, random, next);
	}

	/** Un bloque que toca el fuego: a veces se quema (se vuelve fuego más gastado, o desaparece). La dinamita, prende. */
	private void burnSide(ServerWorld world, BlockPos p, int factor, Random random, int age) {
		BlockState s = world.getBlockState(p);
		int chance = spreadChance(s);
		if (chance <= 0 || random.nextInt(factor) >= chance) return;
		if (random.nextInt(age + 10) < 5 && !world.hasRain(p)) {
			world.setBlockState(p, getDefaultState().with(AGE, Math.min(15, age + 2 + random.nextInt(2))), Block.NOTIFY_ALL);
		} else {
			world.removeBlock(p, false);
		}
		if (s.getBlock() instanceof TntBlock) TntBlock.primeTnt(world, p);
	}

	/** Prende en los huecos de alrededor (hasta 4 bloques por encima: el fuego sube) que tocan algo que arde. */
	private void spread(ServerWorld world, BlockPos pos, Random random, int age) {
		BlockPos.Mutable m = new BlockPos.Mutable();
		int difficulty = world.getDifficulty().getId();
		for (int dx = -1; dx <= 1; dx++) {
			for (int dz = -1; dz <= 1; dz++) {
				for (int dy = -1; dy <= 4; dy++) {
					if (dx == 0 && dy == 0 && dz == 0) continue;
					m.set(pos, dx, dy, dz);
					int burn = burnChanceAround(world, m);
					if (burn <= 0) continue;
					int odds = 100 + (dy > 1 ? (dy - 1) * 100 : 0);
					int q = (burn + 40 + difficulty * 7) / (age + 30);
					if (q > 0 && random.nextInt(odds) <= q && !world.hasRain(m)) {
						world.setBlockState(m, getDefaultState().with(AGE, Math.min(15, age + 2 + random.nextInt(3))), Block.NOTIFY_ALL);
					}
				}
			}
		}
	}

	/** Lo rápido que prende algo que toca el fuego (como en Minecraft: hierba y flores, hojas y lana, tablas, troncos). */
	private static int spreadChance(BlockState s) {
		if (!s.isBurnable()) return 0;
		if (s.isIn(BlockTags.LOGS_THAT_BURN)) return 5;
		if (s.isIn(BlockTags.LEAVES) || s.isIn(BlockTags.WOOL)) return 60;
		if (s.isReplaceable() || s.isIn(BlockTags.FLOWERS)) return 100;
		return 20;
	}

	/** Lo fácil que el fuego salta a un hueco junto a esto. */
	private static int burnChance(BlockState s) {
		if (!s.isBurnable()) return 0;
		if (s.isIn(BlockTags.LOGS_THAT_BURN) || s.isIn(BlockTags.PLANKS)) return 5;
		if (s.isIn(BlockTags.LEAVES) || s.isIn(BlockTags.WOOL)) return 30;
		if (s.isReplaceable() || s.isIn(BlockTags.FLOWERS)) return 60;
		return 5;
	}

	private static int burnChanceAround(WorldView world, BlockPos pos) {
		if (!world.isAir(pos)) return 0;
		int best = 0;
		for (Direction d : Direction.values()) best = Math.max(best, burnChance(world.getBlockState(pos.offset(d))));
		return best;
	}

	private static boolean rainAround(World world, BlockPos pos) {
		return world.hasRain(pos) || world.hasRain(pos.west()) || world.hasRain(pos.east()) || world.hasRain(pos.north())
			|| world.hasRain(pos.south());
	}

	/** El humo y el sonido del fuego de Minecraft; y si el cliente no lo dibuja en 3D, también sus llamitas. */
	@Override
	public void randomDisplayTick(BlockState state, World world, BlockPos pos, Random random) {
		super.randomDisplayTick(state, world, pos, random);
		if (drawn3D.test(world, pos)) return;
		for (int i = 0; i < 3; i++) {
			world.addParticle(ParticleTypes.FLAME, pos.getX() + 0.15 + random.nextDouble() * 0.7, pos.getY() + random.nextDouble() * 0.5,
				pos.getZ() + 0.15 + random.nextDouble() * 0.7, 0.0, 0.02 + random.nextDouble() * 0.03, 0.0);
		}
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
