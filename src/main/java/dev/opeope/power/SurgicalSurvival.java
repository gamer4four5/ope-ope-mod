package dev.opeope.power;

import dev.opeope.entity.BlockSet;
import dev.opeope.entity.PieceEntity;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.minecraft.block.AmethystClusterBlock;
import net.minecraft.block.BambooBlock;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.CocoaBlock;
import net.minecraft.block.CropBlock;
import net.minecraft.block.FluidBlock;
import net.minecraft.block.NetherWartBlock;
import net.minecraft.block.Oxidizable;
import net.minecraft.block.SugarCaneBlock;
import net.minecraft.block.entity.AbstractFurnaceBlockEntity;
import net.minecraft.block.entity.BlastFurnaceBlockEntity;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.block.entity.BrewingStandBlockEntity;
import net.minecraft.block.entity.CampfireBlockEntity;
import net.minecraft.block.entity.SmokerBlockEntity;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.NbtComponent;
import net.minecraft.enchantment.Enchantment;
import net.minecraft.enchantment.Enchantments;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.ExperienceOrbEntity;
import net.minecraft.entity.ItemEntity;
import net.minecraft.entity.LightningEntity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.mob.CreeperEntity;
import net.minecraft.inventory.Inventory;
import net.minecraft.item.BlockItem;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.particle.BlockStateParticleEffect;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.recipe.AbstractCookingRecipe;
import net.minecraft.recipe.CraftingRecipe;
import net.minecraft.recipe.Ingredient;
import net.minecraft.recipe.RecipeEntry;
import net.minecraft.recipe.RecipeType;
import net.minecraft.recipe.ShapedRecipe;
import net.minecraft.recipe.ShapelessRecipe;
import net.minecraft.recipe.input.CraftingRecipeInput;
import net.minecraft.recipe.input.SingleStackRecipeInput;
import net.minecraft.registry.Registries;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.registry.RegistryWrapper;
import net.minecraft.registry.tag.BlockTags;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvent;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.collection.DefaultedList;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Queue;
import java.util.Set;

/**
 * Traducción de las habilidades canon de Trafalgar Law (Ope Ope no Mi) a mecánicas de supervivencia de Minecraft:
 * <ul>
 *   <li><b>Scan + Shambles</b>: detecta vetas minerales tras la roca y las extrae con fortuna/toque de seda intercambiándolas por piedra.</li>
 *   <li><b>Mes en Bloques</b>: extirpa intactos Spawners, Amatista en Brotación, Colmenas y cofres conservando su NBT.</li>
 *   <li><b>Counter Shock</b>: desfibrila hornos/máquinas para fundir al instante, vitrifica arena/piedra y carga Creepers.</li>
 *   <li><b>Union Quirúrgica</b>: ensambla recetas de crafteo directamente con los ítems sueltos dentro del ROOM.</li>
 *   <li><b>Amputate (Cosecha Quirúrgica)</b>: recolecta cultivos maduros, caña, cacao y drusas de amatista al rebanar el área.</li>
 * </ul>
 */
public final class SurgicalSurvival {
	private SurgicalSurvival() {}

	private static final String CUSTOM_MES_KEY = "opeope_mes_be";

	private record PendingBeRestore(RegistryKey<World> dim, BlockPos pos1, BlockPos pos2, Block expectedBlock, NbtCompound nbt, int ticksLeft) {}

	private static final List<PendingBeRestore> PENDING_RESTORES = new ArrayList<>();
	private static boolean initialized = false;

	public static void init() {
		if (initialized) return;
		initialized = true;
		UseBlockCallback.EVENT.register((player, world, hand, hitResult) -> {
			if (world.isClient || !(world instanceof ServerWorld sw)) return ActionResult.PASS;
			ItemStack stack = player.getStackInHand(hand);
			if (stack.isEmpty() || !(stack.getItem() instanceof BlockItem bi)) return ActionResult.PASS;
			NbtComponent custom = stack.get(DataComponentTypes.CUSTOM_DATA);
			if (custom == null || custom.isEmpty()) return ActionResult.PASS;
			NbtCompound root = custom.copyNbt();
			if (!root.contains(CUSTOM_MES_KEY)) return ActionResult.PASS;
			BlockPos hitPos = hitResult.getBlockPos();
			BlockPos placePos = hitPos.offset(hitResult.getSide());
			PENDING_RESTORES.add(new PendingBeRestore(sw.getRegistryKey(), hitPos.toImmutable(), placePos.toImmutable(),
				bi.getBlock(), root.getCompound(CUSTOM_MES_KEY).copy(), 3));
			return ActionResult.PASS;
		});
	}

	public static void clearPending() {
		PENDING_RESTORES.clear();
	}

	public static void tickPendingRestores(MinecraftServer server) {
		if (PENDING_RESTORES.isEmpty()) return;
		Iterator<PendingBeRestore> it = PENDING_RESTORES.iterator();
		while (it.hasNext()) {
			PendingBeRestore pr = it.next();
			ServerWorld w = server.getWorld(pr.dim);
			if (w == null || pr.ticksLeft <= 0) {
				it.remove();
				continue;
			}
			BlockPos match = null;
			if (w.getBlockState(pr.pos1).isOf(pr.expectedBlock)) match = pr.pos1;
			else if (w.getBlockState(pr.pos2).isOf(pr.expectedBlock)) match = pr.pos2;
			if (match != null) {
				BlockEntity be = w.getBlockEntity(match);
				if (be != null) {
					be.read(pr.nbt, w.getRegistryManager());
					be.markDirty();
					BlockState st = w.getBlockState(match);
					w.updateListeners(match, st, st, Block.NOTIFY_ALL);
				}
				it.remove();
			} else {
				// Decrece contador en caso de que el clic no llegara a colocar el bloque
				int idx = PENDING_RESTORES.indexOf(pr);
				if (idx >= 0) {
					PENDING_RESTORES.set(idx, new PendingBeRestore(pr.dim, pr.pos1, pr.pos2, pr.expectedBlock, pr.nbt, pr.ticksLeft - 1));
				}
			}
		}
	}

	/** Detecta minerales vanilla y de cualquier mod instalado (Oritech, Tech Reborn, etc.). */
	public static boolean isOre(BlockState st) {
		if (st.isAir()) return false;
		if (st.isIn(BlockTags.COAL_ORES) || st.isIn(BlockTags.IRON_ORES) || st.isIn(BlockTags.GOLD_ORES)
			|| st.isIn(BlockTags.DIAMOND_ORES) || st.isIn(BlockTags.REDSTONE_ORES) || st.isIn(BlockTags.LAPIS_ORES)
			|| st.isIn(BlockTags.EMERALD_ORES) || st.isIn(BlockTags.COPPER_ORES)
			|| st.isOf(Blocks.NETHER_QUARTZ_ORE) || st.isOf(Blocks.NETHER_GOLD_ORE)
			|| st.isOf(Blocks.ANCIENT_DEBRIS) || st.isOf(Blocks.GILDED_BLACKSTONE)
			|| st.isOf(Blocks.AMETHYST_CLUSTER)) {
			return true;
		}
		String path = Registries.BLOCK.getId(st.getBlock()).getPath();
		return path.endsWith("_ore") || path.startsWith("ore_") || path.contains("_ore_")
			|| path.equals("ancient_debris") || path.endsWith("_debris");
	}

	public static boolean isTreasureBlock(BlockState st) {
		return st.isOf(Blocks.SPAWNER) || st.isOf(Blocks.TRIAL_SPAWNER) || st.isOf(Blocks.VAULT)
			|| st.isOf(Blocks.CHEST) || st.isOf(Blocks.TRAPPED_CHEST) || st.isOf(Blocks.BARREL)
			|| st.isOf(Blocks.BUDDING_AMETHYST);
	}

	private static String oreFamilyKey(Block b) {
		String path = Registries.BLOCK.getId(b).getPath();
		return path.replace("deepslate_", "").replace("nether_", "").replace("end_", "")
			.replace("lit_", "").replace("glowing_", "");
	}

	private static BlockState hostReplacement(ServerWorld w, BlockPos pos, BlockState oreState) {
		if (oreState.isOf(Blocks.AMETHYST_CLUSTER)) return Blocks.AIR.getDefaultState();
		String path = Registries.BLOCK.getId(oreState.getBlock()).getPath();
		if (path.contains("deepslate") || (w.getRegistryKey() == World.OVERWORLD && pos.getY() < 0)) {
			return Blocks.COBBLED_DEEPSLATE.getDefaultState();
		}
		if (path.contains("nether") || oreState.isOf(Blocks.ANCIENT_DEBRIS)
			|| oreState.isOf(Blocks.GILDED_BLACKSTONE) || w.getRegistryKey() == World.NETHER) {
			return Blocks.NETHERRACK.getDefaultState();
		}
		if (w.getRegistryKey() == World.END || path.contains("end_")) {
			return Blocks.END_STONE.getDefaultState();
		}
		return Blocks.COBBLESTONE.getDefaultState();
	}

	// ------------------------------------------------------------------ Scan + Shambles minero

	public static void scanRoomReport(ServerPlayerEntity p, PlayerPower pw) {
		Room room = pw.room != null && pw.room.dim == p.getServerWorld().getRegistryKey() ? pw.room : pw.rroom;
		if (room == null) return;
		ServerWorld w = p.getServerWorld();
		int rad = Math.min(20, (int) Math.ceil(room.radius));
		BlockPos pc = p.getBlockPos();
		int minY = Math.max(w.getBottomY(), pc.getY() - rad);
		int maxY = Math.min(w.getTopY() - 1, pc.getY() + rad);

		Map<String, Integer> oreCounts = new LinkedHashMap<>();
		int spawners = 0, chests = 0;
		double r2 = room.radius * room.radius;
		BlockPos.Mutable mut = new BlockPos.Mutable();
		for (int dx = -rad; dx <= rad; dx++) {
			for (int dz = -rad; dz <= rad; dz++) {
				for (int dy = minY; dy <= maxY; dy++) {
					mut.set(pc.getX() + dx, dy, pc.getZ() + dz);
					if (room.center.squaredDistanceTo(mut.getX() + 0.5, dy + 0.5, mut.getZ() + 0.5) > r2) continue;
					BlockState st = w.getBlockState(mut);
					if (st.isAir()) continue;
					if (isOre(st)) {
						String name = shortOreName(st.getBlock().getName().getString());
						oreCounts.merge(name, 1, Integer::sum);
					} else if (st.isOf(Blocks.SPAWNER) || st.isOf(Blocks.TRIAL_SPAWNER)) {
						spawners++;
					} else if (st.isOf(Blocks.CHEST) || st.isOf(Blocks.TRAPPED_CHEST) || st.isOf(Blocks.BARREL)) {
						chests++;
					}
				}
			}
		}

		if (oreCounts.isEmpty() && spawners == 0 && chests == 0) return;

		List<Map.Entry<String, Integer>> sorted = new ArrayList<>(oreCounts.entrySet());
		sorted.sort((a, b) -> Integer.compare(b.getValue(), a.getValue()));
		StringBuilder sb = new StringBuilder();
		int shown = 0;
		for (var e : sorted) {
			if (shown > 0) sb.append(" · ");
			sb.append(e.getValue()).append("× ").append(e.getKey());
			if (++shown >= 3) break;
		}
		if (spawners > 0) sb.append(sb.isEmpty() ? "" : " · ").append("§d").append(spawners).append(" Spawner");
		if (chests > 0) sb.append(sb.isEmpty() ? "" : " · ").append("§6").append(chests).append(" Cofre(s)");

		p.sendMessage(Text.literal("§bScan Quirúrgico: §f" + sb + " §7| §ePulsa [Alt] para extraer veta"), true);
	}

	private static String shortOreName(String full) {
		return full.replace("Mena de ", "").replace("mena de ", "")
			.replace(" de pizarra profunda", "").replace(" del Nether", "").trim();
	}

	public static boolean tryExtractOreVein(ServerPlayerEntity p, PlayerPower pw, boolean silkTouch) {
		ServerWorld w = p.getServerWorld();
		Room room = pw.room != null && pw.room.dim == w.getRegistryKey() ? pw.room : pw.rroom;
		if (room == null) return false;

		BlockPos seed = null;
		BlockHitResult hit = PowerManager.raycastBlock(p, 120);
		if (hit.getType() == HitResult.Type.BLOCK && room.contains(hit.getPos())
			&& isOre(w.getBlockState(hit.getBlockPos()))) {
			seed = hit.getBlockPos().toImmutable();
		} else if (pw.scanTicks > 0 && !silkTouch) {
			seed = findScannedOreInCone(p, w, room);
		}
		if (seed == null) return false;

		BlockState seedState = w.getBlockState(seed);
		if (!isOre(seedState) || seedState.getHardness(w, seed) < 0) return false;
		String family = oreFamilyKey(seedState.getBlock());
		int maxBlocks = 12 + pw.masteryTier() * 5;

		// BFS conectado en 3x3x3 para extraer la veta completa de una operación
		List<BlockPos> vein = new ArrayList<>();
		Set<Long> visited = new HashSet<>();
		Queue<BlockPos> queue = new ArrayDeque<>();
		queue.add(seed);
		visited.add(seed.asLong());

		while (!queue.isEmpty() && vein.size() < maxBlocks) {
			BlockPos cur = queue.poll();
			BlockState st = w.getBlockState(cur);
			if (!isOre(st) || !oreFamilyKey(st.getBlock()).equals(family)) continue;
			if (!room.contains(Vec3d.ofCenter(cur))) continue;
			vein.add(cur);
			for (int dx = -1; dx <= 1; dx++) {
				for (int dy = -1; dy <= 1; dy++) {
					for (int dz = -1; dz <= 1; dz++) {
						if (dx == 0 && dy == 0 && dz == 0) continue;
						BlockPos nxt = cur.add(dx, dy, dz);
						long key = nxt.asLong();
						if (visited.add(key)) {
							BlockState nst = w.getBlockState(nxt);
							if (isOre(nst) && oreFamilyKey(nst.getBlock()).equals(family)) {
								queue.add(nxt);
							}
						}
					}
				}
			}
		}
		if (vein.isEmpty()) return false;

		ItemStack virtualTool = createVirtualPickaxe(w, silkTouch, Math.max(1, pw.masteryTier()));
		boolean autoSmelt = pw.kroomBladeTicks > 0 && !silkTouch;
		int totalItems = 0, smeltedCount = 0, totalXp = 0;

		for (BlockPos bp : vein) {
			BlockState st = w.getBlockState(bp);
			int[] yield = givePerfectOreYield(p, w, pw, bp, st, virtualTool, silkTouch, autoSmelt, false, null);
			totalItems += yield[0];
			smeltedCount += yield[1];
			BlockState replace = hostReplacement(w, bp, st);
			w.setBlockState(bp, replace, Block.NOTIFY_LISTENERS);
			w.spawnParticles(CutEngine.ROOM_DUST, bp.getX() + 0.5, bp.getY() + 0.5, bp.getZ() + 0.5, 4, 0.25, 0.25, 0.25, 0);
			if (!silkTouch) totalXp += xpForOre(st) * 2;
		}

		Vec3d center = Vec3d.ofCenter(seed);
		if (totalXp > 0) ExperienceOrbEntity.spawn(w, p.getPos().add(0, 0.4, 0), totalXp);
		Shambles.fx(p, center.subtract(0, 1.0, 0), p.getPos());
		w.playSound(null, p.getX(), p.getY(), p.getZ(), SoundEvents.ENTITY_ITEM_PICKUP, SoundCategory.PLAYERS, 0.9f, 1.45f);
		PowerManager.addBounty(p, 150_000L * vein.size());

		String oreName = shortOreName(seedState.getBlock().getName().getString());
		if (silkTouch) {
			p.sendMessage(Text.literal("§bShambles (Toque de Seda): §f¡Extraídos " + vein.size() + "× " + oreName + " intactos!"), true);
		} else if (autoSmelt && smeltedCount > 0) {
			p.sendMessage(Text.literal("§bShambles + K-ROOM: §f¡Separados y fundidos " + vein.size() + "× " + oreName + " → " + totalItems + " lingotes/gemas puros!"), true);
		} else {
			p.sendMessage(Text.literal("§bShambles (Separación Perfecta): §f¡" + vein.size() + "× " + oreName + " → " + totalItems + " minerales puros sin romper la roca!"), true);
		}
		return true;
	}

	/**
	 * Separa molecularmente el mineral de su roca huésped dentro del ROOM: rinde 2×–4× de mineral puro + subproductos
	 * cristalizados y, si se indica, devuelve también la roca limpia o los apila en {@code bag}.
	 */
	public static int[] givePerfectOreYield(ServerPlayerEntity p, ServerWorld w, PlayerPower pw, BlockPos pos, BlockState oreState,
											  ItemStack tool, boolean silkTouch, boolean autoSmelt, boolean includeHostRock,
											  @Nullable Map<Item, Integer> bag) {
		int totalItems = 0, smeltedCount = 0;
		BlockEntity be = w.isChunkLoaded(pos) ? w.getBlockEntity(pos) : null;
		List<ItemStack> drops = Block.getDroppedStacks(oreState, w, pos, be, p, tool);
		int tier = pw.masteryTier();
		int purityMult = silkTouch ? 1 : (2 + (tier >= 2 ? 1 : 0) + (tier >= 1 && w.random.nextFloat() < 0.35f ? 1 : 0));

		for (ItemStack drop : drops) {
			if (drop.isEmpty()) continue;
			ItemStack finalStack = drop.copy();
			if (!silkTouch) {
				finalStack.setCount(drop.getCount() * purityMult);
			}
			if (autoSmelt && !silkTouch) {
				Optional<RecipeEntry<AbstractCookingRecipe>> smelted = w.getRecipeManager()
					.getFirstMatch(RecipeType.SMELTING, new SingleStackRecipeInput(finalStack), w)
					.map(e -> (RecipeEntry<AbstractCookingRecipe>) (RecipeEntry<?>) e);
				if (smelted.isPresent()) {
					ItemStack res = smelted.get().value().getResult(w.getRegistryManager()).copy();
					if (!res.isEmpty()) {
						res.setCount(Math.max(1, res.getCount()) * finalStack.getCount());
						finalStack = res;
						smeltedCount += finalStack.getCount();
					}
				}
			}
			totalItems += finalStack.getCount();
			deliverStack(p, finalStack, bag);
		}

		if (!silkTouch) {
			List<ItemStack> extras = rollSurgicalByproduct(w, oreState);
			for (ItemStack ex : extras) {
				totalItems += ex.getCount();
				deliverStack(p, ex, bag);
			}
		}

		if (includeHostRock) {
			BlockState host = hostReplacement(w, pos, oreState);
			if (!host.isAir() && host.getBlock().asItem() != Items.AIR) {
				deliverStack(p, new ItemStack(host.getBlock().asItem(), 1), bag);
			}
		}
		return new int[]{totalItems, smeltedCount};
	}

	private static List<ItemStack> rollSurgicalByproduct(ServerWorld w, BlockState st) {
		List<ItemStack> extras = new ArrayList<>();
		String path = Registries.BLOCK.getId(st.getBlock()).getPath();
		boolean deepslate = path.contains("deepslate");
		if (st.isIn(BlockTags.COAL_ORES)) {
			if (w.random.nextFloat() < 0.20f) extras.add(new ItemStack(Items.DIAMOND, 1));
			else if (w.random.nextFloat() < 0.30f) extras.add(new ItemStack(Items.QUARTZ, 2));
		} else if (st.isIn(BlockTags.IRON_ORES) || st.isIn(BlockTags.COPPER_ORES)) {
			extras.add(new ItemStack(Items.IRON_NUGGET, 2 + w.random.nextInt(3)));
			if (w.random.nextFloat() < 0.25f) extras.add(new ItemStack(Items.RAW_GOLD, 1));
		} else if (st.isIn(BlockTags.GOLD_ORES) || st.isIn(BlockTags.REDSTONE_ORES) || st.isIn(BlockTags.LAPIS_ORES)) {
			extras.add(new ItemStack(Items.GOLD_NUGGET, 2 + w.random.nextInt(3)));
			if (deepslate && w.random.nextFloat() < 0.18f) extras.add(new ItemStack(Items.DIAMOND, 1));
			else if (w.random.nextFloat() < 0.22f) extras.add(new ItemStack(Items.EMERALD, 1));
		} else if (st.isIn(BlockTags.DIAMOND_ORES) || st.isIn(BlockTags.EMERALD_ORES) || st.isOf(Blocks.ANCIENT_DEBRIS)) {
			extras.add(new ItemStack(st.isOf(Blocks.ANCIENT_DEBRIS) ? Items.NETHERITE_SCRAP
				: (st.isIn(BlockTags.EMERALD_ORES) ? Items.EMERALD : Items.DIAMOND), 1));
		} else if (w.random.nextFloat() < 0.35f) {
			extras.add(new ItemStack(Items.QUARTZ, 2));
		}
		return extras;
	}

	private static void deliverStack(ServerPlayerEntity p, ItemStack st, @Nullable Map<Item, Integer> bag) {
		if (st.isEmpty()) return;
		if (bag != null && st.getComponents().isEmpty()) {
			bag.merge(st.getItem(), st.getCount(), Integer::sum);
			return;
		}
		int remaining = st.getCount();
		int max = st.getMaxCount();
		while (remaining > 0) {
			int take = Math.min(max, remaining);
			p.getInventory().offerOrDrop(st.copyWithCount(take));
			remaining -= take;
		}
	}

	private static void flushBag(ServerPlayerEntity p, Map<Item, Integer> bag) {
		for (var e : bag.entrySet()) {
			int remaining = e.getValue();
			int max = e.getKey().getMaxCount();
			while (remaining > 0) {
				int take = Math.min(max, remaining);
				p.getInventory().offerOrDrop(new ItemStack(e.getKey(), take));
				remaining -= take;
			}
		}
		bag.clear();
	}

	/** Separa el mineral puro de una mena in-situ sin destruir su roca huésped. */
	public static int extractOreFromBlockInPlace(ServerPlayerEntity p, ServerWorld w, PlayerPower pw, BlockPos pos, BlockState st, ItemStack fortuneTool) {
		int[] y = givePerfectOreYield(p, w, pw, pos, st, fortuneTool, false, pw.kroomBladeTicks > 0, false, null);
		w.setBlockState(pos, hostReplacement(w, pos, st), Block.NOTIFY_LISTENERS);
		w.spawnParticles(CutEngine.ROOM_DUST, pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5, 6, 0.25, 0.25, 0.25, 0);
		return y[0];
	}

	// ------------------------------------------------------------------ Takt Telequinético: Romper / Excavar / Pulverizar con la Mente

	/**
	 * Romper bloques con la mente (`Shift + R` / Orden Takt):
	 * - Si llevas trozos/montañas flotando en Takt: los comprime y pulveriza en el aire, separando todos sus minerales (2×–4×).
	 * - Si apuntas a un árbol: tala el árbol entero mentalmente.
	 * - Si apuntas a una mena: extrae toda la veta conectada con separación quirúrgica perfecta.
	 * - Si apuntas a pared/suelo dentro del ROOM: excava un volumen 3×3×3 con la mente sin usar pico.
	 */
	public static void telekineticMindBreak(ServerPlayerEntity p, PlayerPower pw) {
		ServerWorld w = p.getServerWorld();
		List<Entity> held = Takt.resolve(p, pw);
		if (!held.isEmpty() && held.stream().anyMatch(e -> e instanceof PieceEntity pe && pe.weapon == null)) {
			crushHeldTaktPieces(p, pw, w, held);
			return;
		}

		Room room = pw.room != null && pw.room.dim == w.getRegistryKey() ? pw.room : pw.rroom;
		if (room == null) {
			PowerManager.noRoom(p);
			return;
		}

		BlockHitResult hit = PowerManager.raycastBlock(p, 45.0);
		if (hit.getType() != HitResult.Type.BLOCK || !room.contains(hit.getPos())) {
			p.sendMessage(Text.literal("§7Apunta a bloques dentro de tu ROOM para romperlos con la mente"), true);
			return;
		}

		BlockPos seed = hit.getBlockPos();
		BlockState seedState = w.getBlockState(seed);
		if (seedState.isAir()) return;

		if (isOre(seedState)) {
			tryExtractOreVein(p, pw, false);
			return;
		}

		if (seedState.isIn(BlockTags.LOGS)) {
			mindChopTree(p, pw, w, room, seed);
			return;
		}

		ItemStack fortuneTool = createVirtualPickaxe(w, false, Math.max(1, pw.masteryTier()));
		boolean autoSmelt = pw.kroomBladeTicks > 0;
		Map<Item, Integer> bag = new LinkedHashMap<>();
		int broken = 0, oresYield = 0;
		BlockPos underFeet = p.getBlockPos().down();

		for (int dx = -1; dx <= 1; dx++) {
			for (int dy = -1; dy <= 1; dy++) {
				for (int dz = -1; dz <= 1; dz++) {
					BlockPos bp = seed.add(dx, dy, dz);
					if (bp.getY() < w.getBottomY() || bp.getY() >= w.getTopY()) continue;
					if (p.isOnGround() && bp.equals(underFeet)) continue;
					Vec3d bc = Vec3d.ofCenter(bp);
					if (!room.contains(bc)) continue;
					BlockState st = w.getBlockState(bp);
					if (st.isAir() || st.getBlock() instanceof FluidBlock || st.hasBlockEntity() || st.getHardness(w, bp) < 0) continue;

					if (isOre(st)) {
						int[] res = givePerfectOreYield(p, w, pw, bp, st, fortuneTool, false, autoSmelt, true, bag);
						oresYield += res[0];
					} else {
						for (ItemStack drop : Block.getDroppedStacks(st, w, bp, null, p, fortuneTool)) {
							deliverStack(p, drop, bag);
						}
					}
					w.setBlockState(bp, Blocks.AIR.getDefaultState(), Block.NOTIFY_LISTENERS);
					if (broken < 18) {
						w.spawnParticles(new BlockStateParticleEffect(ParticleTypes.BLOCK, st), bc.x, bc.y, bc.z, 6, 0.25, 0.25, 0.25, 0.12);
						w.spawnParticles(CutEngine.ROOM_DUST, bc.x, bc.y, bc.z, 3, 0.25, 0.25, 0.25, 0);
					}
					broken++;
				}
			}
		}

		if (broken == 0) return;
		flushBag(p, bag);
		Vec3d c = Vec3d.ofCenter(seed);
		Techniques.beam(p, p.getEyePos().add(p.getRotationVector()), c, CutEngine.ROOM_DUST);
		playSound(w, c, SoundEvents.ENTITY_GENERIC_EXPLODE.value(), 0.7f, 1.45f);
		playSound(w, c, SoundEvents.BLOCK_AMETHYST_BLOCK_BREAK, 1.1f, 0.8f);
		if (oresYield > 0) PowerManager.addBounty(p, 80_000L * Math.max(1, oresYield / 3));

		p.sendMessage(Text.literal(oresYield > 0
			? "§b🧠 Takt (Excavación Mental): §f¡" + broken + " bloques fracturados · " + oresYield + " minerales puros separados!"
			: "§b🧠 Takt (Excavación Mental): §f¡" + broken + " bloques fracturados con la mente!"), true);
	}

	private static void crushHeldTaktPieces(ServerPlayerEntity p, PlayerPower pw, ServerWorld w, List<Entity> held) {
		ItemStack fortuneTool = createVirtualPickaxe(w, false, Math.max(1, pw.masteryTier()));
		boolean autoSmelt = pw.kroomBladeTicks > 0;
		Map<Item, Integer> bag = new LinkedHashMap<>();
		int brokenBlocks = 0, oresYield = 0, crushedPieces = 0;

		for (Entity e : held) {
			if (!(e instanceof PieceEntity pe) || pe.weapon != null || pe.blocks == null) continue;
			Vec3d center = pe.center();
			BlockPos refPos = BlockPos.ofFloored(center);
			for (BlockSet.Entry entry : pe.blocks.entries) {
				BlockState st = entry.state();
				if (st.isAir() || st.getBlock() instanceof FluidBlock) continue;
				if (isOre(st)) {
					int[] res = givePerfectOreYield(p, w, pw, refPos, st, fortuneTool, false, autoSmelt, true, bag);
					oresYield += res[0];
				} else {
					List<ItemStack> drops = Block.getDroppedStacks(st, w, refPos, null, p, fortuneTool);
					if (drops.isEmpty() && st.getBlock().asItem() != Items.AIR && w.random.nextFloat() < 0.7f) {
						deliverStack(p, new ItemStack(st.getBlock().asItem(), 1), bag);
					} else {
						for (ItemStack d : drops) deliverStack(p, d, bag);
					}
				}
				brokenBlocks++;
			}

			int pid = pe.getId();
			pw.takt.remove((Integer) pid);
			pw.parked.remove(pid);
			pw.taktOffsets.remove(pid);
			Takt.forget(pid);
			pw.rips.removeIf(r -> {
				if (r.entityId == pid) {
					GroundCuts.forgetRip(r.id);
					return true;
				}
				return false;
			});
			pe.discard();
			crushedPieces++;

			w.spawnParticles(ParticleTypes.EXPLOSION, center.x, center.y, center.z, 2, 0.5, 0.5, 0.5, 0);
			w.spawnParticles(CutEngine.ROOM_DUST, center.x, center.y, center.z, 45, 1.2, 1.2, 1.2, 0.04);
			Techniques.beam(p, p.getEyePos().add(p.getRotationVector()), center, CutEngine.ROOM_DUST);
			playSound(w, center, SoundEvents.ENTITY_GENERIC_EXPLODE.value(), 0.9f, 1.25f);
			playSound(w, center, SoundEvents.BLOCK_AMETHYST_BLOCK_CHIME, 1.1f, 1.7f);
		}

		if (crushedPieces == 0) return;
		pw.dirty = true;
		flushBag(p, bag);
		PowerManager.addBounty(p, 50_000L * crushedPieces + 40_000L * oresYield);
		p.sendMessage(Text.literal(oresYield > 0
			? "§b🧠 Takt (Pulverización Mental): §f¡" + brokenBlocks + " bloques desintegrados → " + oresYield + " minerales puros separados!"
			: "§b🧠 Takt (Pulverización Mental): §f¡" + brokenBlocks + " bloques pulverizados en tu inventario!"), true);
	}

	private static void mindChopTree(ServerPlayerEntity p, PlayerPower pw, ServerWorld w, Room room, BlockPos seed) {
		ItemStack tool = createVirtualPickaxe(w, false, Math.max(1, pw.masteryTier()));
		List<BlockPos> targets = new ArrayList<>();
		Set<Long> visited = new HashSet<>();
		Queue<BlockPos> queue = new ArrayDeque<>();
		queue.add(seed);
		visited.add(seed.asLong());

		while (!queue.isEmpty() && targets.size() < 120) {
			BlockPos cur = queue.poll();
			BlockState st = w.getBlockState(cur);
			if (!st.isIn(BlockTags.LOGS) && !st.isIn(BlockTags.LEAVES)) continue;
			if (!room.contains(Vec3d.ofCenter(cur))) continue;
			targets.add(cur);
			for (int dx = -1; dx <= 1; dx++) {
				for (int dy = 0; dy <= 1; dy++) {
					for (int dz = -1; dz <= 1; dz++) {
						BlockPos nxt = cur.add(dx, dy, dz);
						if (visited.add(nxt.asLong())) {
							BlockState nst = w.getBlockState(nxt);
							if (nst.isIn(BlockTags.LOGS) || nst.isIn(BlockTags.LEAVES)) queue.add(nxt);
						}
					}
				}
			}
		}

		Map<Item, Integer> bag = new LinkedHashMap<>();
		int logs = 0;
		for (BlockPos bp : targets) {
			BlockState st = w.getBlockState(bp);
			if (st.isIn(BlockTags.LOGS)) logs++;
			for (ItemStack d : Block.getDroppedStacks(st, w, bp, null, p, tool)) {
				deliverStack(p, d, bag);
			}
			w.setBlockState(bp, Blocks.AIR.getDefaultState(), Block.NOTIFY_LISTENERS);
			if (logs <= 14) {
				w.spawnParticles(CutEngine.ROOM_DUST, bp.getX() + 0.5, bp.getY() + 0.5, bp.getZ() + 0.5, 3, 0.25, 0.25, 0.25, 0);
			}
		}
		flushBag(p, bag);
		Vec3d c = Vec3d.ofCenter(seed);
		Techniques.beam(p, p.getEyePos().add(p.getRotationVector()), c, CutEngine.ROOM_DUST);
		playSound(w, c, SoundEvents.BLOCK_WOOD_BREAK, 1.3f, 0.85f);
		p.sendMessage(Text.literal("§a🧠 Takt (Tala Mental): §f¡Árbol entero de " + logs + " troncos recolectado con la mente!"), true);
	}

	@Nullable
	private static BlockPos findScannedOreInCone(ServerPlayerEntity p, ServerWorld w, Room room) {
		Vec3d eye = p.getEyePos();
		Vec3d look = p.getRotationVector();
		int rad = Math.min(20, (int) Math.ceil(room.radius));
		BlockPos pc = p.getBlockPos();
		BlockPos best = null;
		double bestScore = -1.0;
		BlockPos.Mutable mut = new BlockPos.Mutable();
		for (int dx = -rad; dx <= rad; dx++) {
			for (int dy = -rad; dy <= rad; dy++) {
				for (int dz = -rad; dz <= rad; dz++) {
					mut.set(pc.getX() + dx, pc.getY() + dy, pc.getZ() + dz);
					if (mut.getY() < w.getBottomY() || mut.getY() >= w.getTopY()) continue;
					Vec3d c = Vec3d.ofCenter(mut);
					if (!room.contains(c)) continue;
					Vec3d diff = c.subtract(eye);
					double dist2 = diff.lengthSquared();
					if (dist2 < 0.5 || dist2 > 22 * 22) continue;
					double dot = diff.normalize().dotProduct(look);
					if (dot < 0.90) continue;
					BlockState st = w.getBlockState(mut);
					if (!isOre(st)) continue;
					double score = dot * 10.0 - Math.sqrt(dist2) * 0.08;
					if (score > bestScore) {
						bestScore = score;
						best = mut.toImmutable();
					}
				}
			}
		}
		return best;
	}

	public static ItemStack createPickaxe(ServerWorld w, int fortuneLevel) {
		return createVirtualPickaxe(w, false, fortuneLevel);
	}

	private static ItemStack createVirtualPickaxe(ServerWorld w, boolean silkTouch, int fortuneLevel) {
		ItemStack pick = new ItemStack(Items.NETHERITE_PICKAXE);
		RegistryWrapper.Impl<Enchantment> enchReg = w.getRegistryManager().getWrapperOrThrow(RegistryKeys.ENCHANTMENT);
		if (silkTouch) {
			enchReg.getOptional(Enchantments.SILK_TOUCH).ifPresent(e -> pick.addEnchantment(e, 1));
		} else if (fortuneLevel > 0) {
			enchReg.getOptional(Enchantments.FORTUNE).ifPresent(e -> pick.addEnchantment(e, Math.min(3, fortuneLevel)));
		}
		return pick;
	}

	private static int xpForOre(BlockState st) {
		if (st.isIn(BlockTags.DIAMOND_ORES) || st.isIn(BlockTags.EMERALD_ORES) || st.isOf(Blocks.ANCIENT_DEBRIS)) return 6;
		if (st.isIn(BlockTags.REDSTONE_ORES) || st.isIn(BlockTags.LAPIS_ORES) || st.isOf(Blocks.NETHER_QUARTZ_ORE)) return 3;
		if (st.isIn(BlockTags.COAL_ORES)) return 2;
		return 3;
	}

	// ------------------------------------------------------------------ Mes en Bloques (Spawners, Amatista, Colmenas, Contenedores)

	public static boolean trySurgicalBlockMes(ServerPlayerEntity p, PlayerPower pw) {
		BlockHitResult hit = PowerManager.raycastBlock(p, 10.0);
		if (hit.getType() != HitResult.Type.BLOCK) return false;
		ServerWorld w = p.getServerWorld();
		BlockPos pos = hit.getBlockPos();
		BlockState st = w.getBlockState(pos);

		boolean specialBlock = st.isOf(Blocks.SPAWNER) || st.isOf(Blocks.TRIAL_SPAWNER)
			|| st.isOf(Blocks.BUDDING_AMETHYST) || st.isOf(Blocks.REINFORCED_DEEPSLATE)
			|| st.isOf(Blocks.BEEHIVE) || st.isOf(Blocks.BEE_NEST);
		boolean sneakingContainer = p.isSneaking() && st.hasBlockEntity() && st.getHardness(w, pos) >= 0;
		if (!specialBlock && !sneakingContainer) return false;

		if (!PowerManager.inRoom(p, Vec3d.ofCenter(pos))) {
			PowerManager.noRoom(p);
			return true;
		}
		if (st.getBlock().asItem() == Items.AIR) return false;

		ItemStack out = new ItemStack(st.getBlock().asItem());
		BlockEntity be = w.getBlockEntity(pos);
		if (be != null) {
			be.setStackNbt(out, w.getRegistryManager());
			out.applyComponentsFrom(be.createComponentMap());
			if (be.copyItemDataRequiresOperator()) {
				NbtCompound beNbt = be.createComponentlessNbtWithIdentifyingData(w.getRegistryManager());
				NbtCompound custom = out.getOrDefault(DataComponentTypes.CUSTOM_DATA, NbtComponent.DEFAULT).copyNbt();
				custom.put(CUSTOM_MES_KEY, beNbt);
				out.set(DataComponentTypes.CUSTOM_DATA, NbtComponent.of(custom));
			}
			if (be instanceof Inventory inv) inv.clear();
			w.removeBlockEntity(pos);
		}
		w.setBlockState(pos, Blocks.AIR.getDefaultState(), Block.NOTIFY_LISTENERS);
		p.getInventory().offerOrDrop(out);

		Vec3d c = Vec3d.ofCenter(pos);
		w.spawnParticles(CutEngine.ROOM_DUST, c.x, c.y, c.z, 35, 0.35, 0.35, 0.35, 0);
		w.spawnParticles(ParticleTypes.END_ROD, c.x, c.y, c.z, 12, 0.25, 0.25, 0.25, 0.03);
		playSound(w, c, SoundEvents.BLOCK_AMETHYST_BLOCK_CHIME, 1.2f, 1.8f);
		playSound(w, c, SoundEvents.ENTITY_WARDEN_HEARTBEAT, 1.4f, 1.25f);
		PowerManager.addBounty(p, 750_000L);
		p.sendMessage(Text.literal("§d✦ Mes Quirúrgico: §f¡" + st.getBlock().getName().getString() + " extirpado intacto!"), true);
		return true;
	}

	// ------------------------------------------------------------------ Counter Shock (Hornos, Creepers y Vitrificación)

	public static void onCounterShockBlocksAndMobs(ServerPlayerEntity p, PlayerPower pw, List<LivingEntity> hit) {
		ServerWorld w = p.getServerWorld();
		int chargedCreepers = 0;
		for (LivingEntity le : hit) {
			if (le instanceof CreeperEntity creeper && !creeper.shouldRenderOverlay()) {
				LightningEntity bolt = new LightningEntity(EntityType.LIGHTNING_BOLT, w);
				bolt.setCosmetic(true);
				creeper.onStruckByLightning(w, bolt);
				chargedCreepers++;
			}
		}

		Room room = pw.room != null && pw.room.dim == w.getRegistryKey() ? pw.room : pw.rroom;
		if (room == null) return;

		Vec3d aim = PowerManager.aimPoint(p, 7.5);
		BlockPos center = BlockPos.ofFloored(aim);
		int furnacesSurged = 0, vitrified = 0, deoxidized = 0;

		for (int dx = -1; dx <= 1; dx++) {
			for (int dy = -1; dy <= 1; dy++) {
				for (int dz = -1; dz <= 1; dz++) {
					BlockPos bp = center.add(dx, dy, dz);
					if (!room.contains(Vec3d.ofCenter(bp))) continue;
					BlockState st = w.getBlockState(bp);
					if (st.isAir()) continue;

					BlockEntity be = w.getBlockEntity(bp);
					if (be instanceof AbstractFurnaceBlockEntity furnace && surgeFurnace(w, bp, st, furnace, p)) {
						furnacesSurged++;
						w.spawnParticles(ParticleTypes.ELECTRIC_SPARK, bp.getX() + 0.5, bp.getY() + 0.7, bp.getZ() + 0.5, 18, 0.35, 0.35, 0.35, 0.1);
						continue;
					}
					if (be instanceof CampfireBlockEntity campfire && surgeCampfire(w, bp, campfire, p)) {
						furnacesSurged++;
						continue;
					}
					if (be instanceof BrewingStandBlockEntity brewing && surgeBrewingStand(w, bp, st, brewing)) {
						furnacesSurged++;
						continue;
					}

					// Limpiar óxido del cobre con descarga eléctrica
					Optional<BlockState> lessOxidized = Oxidizable.getDecreasedOxidationState(st);
					if (lessOxidized.isPresent()) {
						w.setBlockState(bp, lessOxidized.get(), Block.NOTIFY_LISTENERS);
						w.spawnParticles(ParticleTypes.ELECTRIC_SPARK, bp.getX() + 0.5, bp.getY() + 0.5, bp.getZ() + 0.5, 10, 0.3, 0.3, 0.3, 0.08);
						deoxidized++;
						continue;
					}

					// Vitrificación / fusión instantánea térmica
					BlockState fused = vitrifiedResult(st);
					if (fused != null && vitrified < 9) {
						w.setBlockState(bp, fused, Block.NOTIFY_LISTENERS);
						w.spawnParticles(ParticleTypes.ELECTRIC_SPARK, bp.getX() + 0.5, bp.getY() + 0.5, bp.getZ() + 0.5, 8, 0.25, 0.25, 0.25, 0.06);
						vitrified++;
					}
				}
			}
		}

		if (furnacesSurged > 0 || chargedCreepers > 0 || vitrified > 0 || deoxidized > 0) {
			List<String> parts = new ArrayList<>();
			if (furnacesSurged > 0) parts.add("§f" + furnacesSurged + " horno/estación sobrecargada");
			if (vitrified > 0) parts.add("§f" + vitrified + " bloques vitrificados");
			if (deoxidized > 0) parts.add("§f" + deoxidized + " cobre purificado");
			if (chargedCreepers > 0) parts.add("§f" + chargedCreepers + " Creeper cargado");
			p.sendMessage(Text.literal("§b⚡ Counter Shock: " + String.join(" · ", parts)), true);
		}
	}

	private static boolean surgeFurnace(ServerWorld w, BlockPos bp, BlockState st, AbstractFurnaceBlockEntity furnace, ServerPlayerEntity p) {
		ItemStack in = furnace.getStack(0);
		if (in.isEmpty()) return false;
		RecipeType<? extends AbstractCookingRecipe> type = RecipeType.SMELTING;
		if (furnace instanceof BlastFurnaceBlockEntity) type = RecipeType.BLASTING;
		else if (furnace instanceof SmokerBlockEntity) type = RecipeType.SMOKING;

		Optional<? extends RecipeEntry<? extends AbstractCookingRecipe>> match = w.getRecipeManager()
			.getFirstMatch(type, new SingleStackRecipeInput(in), w);
		if (match.isEmpty()) return false;

		ItemStack resultTemplate = match.get().value().getResult(w.getRegistryManager());
		if (resultTemplate.isEmpty()) return false;

		int perCraft = resultTemplate.getCount();
		ItemStack out = furnace.getStack(2);
		int space = out.isEmpty() ? resultTemplate.getMaxCount()
			: (ItemStack.areItemsAndComponentsEqual(out, resultTemplate) ? out.getMaxCount() - out.getCount() : 0);
		int count = Math.min(16, Math.min(in.getCount(), space / Math.max(1, perCraft)));
		if (count <= 0) return false;

		in.decrement(count);
		int produced = count * perCraft;
		if (out.isEmpty()) {
			ItemStack res = resultTemplate.copy();
			res.setCount(produced);
			furnace.setStack(2, res);
		} else {
			out.increment(produced);
		}

		// Mantiene combustión eléctrica activa si quedan ítems
		NbtCompound nbt = furnace.createNbtWithId(w.getRegistryManager());
		nbt.putShort("BurnTime", (short) 600);
		furnace.read(nbt, w.getRegistryManager());
		furnace.markDirty();
		w.updateListeners(bp, st, st, Block.NOTIFY_ALL);
		ExperienceOrbEntity.spawn(w, Vec3d.ofCenter(bp), Math.max(1, count / 2));
		return true;
	}

	private static boolean surgeCampfire(ServerWorld w, BlockPos bp, CampfireBlockEntity campfire, ServerPlayerEntity p) {
		DefaultedList<ItemStack> items = campfire.getItemsBeingCooked();
		boolean cooked = false;
		for (int i = 0; i < items.size(); i++) {
			ItemStack stack = items.get(i);
			if (stack.isEmpty()) continue;
			var match = campfire.getRecipeFor(stack);
			if (match.isEmpty()) continue;
			ItemStack res = match.get().value().getResult(w.getRegistryManager()).copy();
			if (!res.isEmpty()) {
				p.getInventory().offerOrDrop(res);
				items.set(i, ItemStack.EMPTY);
				cooked = true;
			}
		}
		if (cooked) campfire.markDirty();
		return cooked;
	}

	private static boolean surgeBrewingStand(ServerWorld w, BlockPos bp, BlockState st, BrewingStandBlockEntity brewing) {
		NbtCompound nbt = brewing.createNbtWithId(w.getRegistryManager());
		short brewTime = nbt.getShort("BrewTime");
		byte fuel = nbt.getByte("Fuel");
		if (brewTime > 1 || fuel < 10) {
			nbt.putByte("Fuel", (byte) 20);
			if (brewTime > 1) nbt.putShort("BrewTime", (short) 1);
			brewing.read(nbt, w.getRegistryManager());
			brewing.markDirty();
			w.updateListeners(bp, st, st, Block.NOTIFY_ALL);
			return true;
		}
		return false;
	}

	@Nullable
	private static BlockState vitrifiedResult(BlockState st) {
		if (st.isOf(Blocks.SAND)) return Blocks.GLASS.getDefaultState();
		if (st.isOf(Blocks.RED_SAND)) return Blocks.TINTED_GLASS.getDefaultState();
		if (st.isOf(Blocks.CLAY)) return Blocks.TERRACOTTA.getDefaultState();
		if (st.isOf(Blocks.COBBLESTONE)) return Blocks.SMOOTH_STONE.getDefaultState();
		if (st.isOf(Blocks.COBBLED_DEEPSLATE)) return Blocks.DEEPSLATE.getDefaultState();
		return null;
	}

	// ------------------------------------------------------------------ Union Quirúrgica (Ensamblaje de Recetas en el ROOM)

	public static boolean trySurgicalAssembly(ServerPlayerEntity p, PlayerPower pw) {
		ServerWorld w = p.getServerWorld();
		Room room = pw.room != null && pw.room.dim == w.getRegistryKey() ? pw.room : pw.rroom;
		if (room == null) return false;

		Vec3d aim = PowerManager.aimPoint(p, 24.0);
		Box search = new Box(aim, aim).expand(4.5);
		List<ItemEntity> piles = w.getEntitiesByClass(ItemEntity.class, search,
			ie -> ie.isAlive() && !ie.getStack().isEmpty() && room.contains(ie.getPos()));
		if (piles.isEmpty()) return false;

		int totalUnits = 0;
		for (ItemEntity ie : piles) totalUnits += ie.getStack().getCount();
		if (totalUnits < 2) return false;

		List<RecipeEntry<CraftingRecipe>> candidates = new ArrayList<>();
		for (RecipeEntry<CraftingRecipe> entry : w.getRecipeManager().listAllOfType(RecipeType.CRAFTING)) {
			CraftingRecipe r = entry.value();
			int nonEmpty = 0;
			for (Ingredient ing : r.getIngredients()) if (!ing.isEmpty()) nonEmpty++;
			if (nonEmpty >= 2 && !r.getResult(w.getRegistryManager()).isEmpty()) {
				candidates.add(entry);
			}
		}
		// Prioriza las recetas que combinan más ingredientes (ej. bloque de 9 antes que pepitas, herramientas antes que palos)
		candidates.sort(Comparator.comparingInt((RecipeEntry<CraftingRecipe> e) -> {
			int c = 0;
			for (Ingredient ing : e.value().getIngredients()) if (!ing.isEmpty()) c++;
			return c;
		}).reversed());

		RecipeEntry<CraftingRecipe> chosen = null;
		for (RecipeEntry<CraftingRecipe> entry : candidates) {
			if (canMatchOne(w, entry.value(), piles) != null) {
				chosen = entry;
				break;
			}
		}
		if (chosen == null) return false;

		CraftingRecipe recipe = chosen.value();
		int crafts = 0;
		ItemStack sampleOut = ItemStack.EMPTY;
		List<Vec3d> beamOrigins = new ArrayList<>();

		while (crafts < 16) {
			List<ItemStack> grid = canMatchOne(w, recipe, piles);
			if (grid == null) break;

			CraftingRecipeInput input = buildCraftingInput(recipe, grid);
			if (!recipe.matches(input, w)) break;
			ItemStack result = recipe.craft(input, w.getRegistryManager());
			if (result.isEmpty()) break;

			consumeMatchedFromPiles(piles, grid, beamOrigins);
			if (sampleOut.isEmpty()) sampleOut = result.copy();
			p.getInventory().offerOrDrop(result);
			crafts++;
		}
		if (crafts == 0) return false;

		Vec3d stitchPoint = piles.get(0).getPos().add(0, 0.6, 0);
		for (Vec3d src : beamOrigins) {
			Techniques.beam(p, src, stitchPoint, CutEngine.ROOM_DUST);
		}
		w.spawnParticles(ParticleTypes.END_ROD, stitchPoint.x, stitchPoint.y, stitchPoint.z, 18, 0.35, 0.35, 0.35, 0.04);
		playSound(w, stitchPoint, SoundEvents.BLOCK_AMETHYST_BLOCK_CHIME, 1.2f, 1.75f);
		playSound(w, stitchPoint, SoundEvents.BLOCK_SMITHING_TABLE_USE, 0.85f, 1.4f);

		int totalOut = sampleOut.getCount() * crafts;
		p.sendMessage(Text.literal("§b✦ Union Quirúrgica: §f¡Ensamblado " + totalOut + "× " + sampleOut.getName().getString() + "!"), true);
		return true;
	}

	@Nullable
	private static List<ItemStack> canMatchOne(ServerWorld w, CraftingRecipe recipe, List<ItemEntity> piles) {
		DefaultedList<Ingredient> ings = recipe.getIngredients();
		int[] available = new int[piles.size()];
		for (int i = 0; i < piles.size(); i++) {
			available[i] = piles.get(i).isAlive() ? piles.get(i).getStack().getCount() : 0;
		}
		List<ItemStack> chosen = new ArrayList<>(ings.size());
		for (Ingredient ing : ings) {
			if (ing.isEmpty()) {
				chosen.add(ItemStack.EMPTY);
				continue;
			}
			boolean found = false;
			for (int i = 0; i < piles.size(); i++) {
				if (available[i] <= 0) continue;
				ItemStack st = piles.get(i).getStack();
				if (ing.test(st)) {
					available[i]--;
					chosen.add(st.copyWithCount(1));
					found = true;
					break;
				}
			}
			if (!found) return null;
		}
		CraftingRecipeInput testIn = buildCraftingInput(recipe, chosen);
		return recipe.matches(testIn, w) ? chosen : null;
	}

	private static CraftingRecipeInput buildCraftingInput(CraftingRecipe recipe, List<ItemStack> grid) {
		if (recipe instanceof ShapedRecipe shaped) {
			return CraftingRecipeInput.create(shaped.getWidth(), shaped.getHeight(), grid);
		}
		if (recipe instanceof ShapelessRecipe) {
			List<ItemStack> nonEmpty = new ArrayList<>();
			for (ItemStack s : grid) if (!s.isEmpty()) nonEmpty.add(s);
			return CraftingRecipeInput.create(nonEmpty.size(), 1, nonEmpty);
		}
		int side = Math.min(3, Math.max(1, (int) Math.ceil(Math.sqrt(grid.size()))));
		List<ItemStack> padded = new ArrayList<>(side * side);
		for (int i = 0; i < side * side; i++) {
			padded.add(i < grid.size() ? grid.get(i) : ItemStack.EMPTY);
		}
		return CraftingRecipeInput.create(side, side, padded);
	}

	private static void consumeMatchedFromPiles(List<ItemEntity> piles, List<ItemStack> needed, List<Vec3d> beamOrigins) {
		for (ItemStack req : needed) {
			if (req.isEmpty()) continue;
			for (ItemEntity ie : piles) {
				if (!ie.isAlive()) continue;
				ItemStack st = ie.getStack();
				if (!st.isEmpty() && ItemStack.areItemsAndComponentsEqual(st, req)) {
					if (beamOrigins.size() < 12) beamOrigins.add(ie.getPos().add(0, 0.25, 0));
					st.decrement(1);
					if (st.isEmpty()) ie.discard();
					break;
				}
			}
		}
	}

	// ------------------------------------------------------------------ Amputate / Cosecha Quirúrgica

	public static void harvestSlicedResources(ServerPlayerEntity p, ServerWorld w, Vec3d aim, List<CutEngine.Plane> planes, CutEngine.Region region) {
		if (p == null || planes.isEmpty() || region == null) return;
		PlayerPower pw = PowerManager.get(p);
		ItemStack fortuneTool = createVirtualPickaxe(w, false, Math.max(1, pw.masteryTier()));
		BlockPos center = BlockPos.ofFloored(aim);
		int rx = 7, ry = 5;
		int harvested = 0, slicedOres = 0, oreBlocks = 0;

		for (int dx = -rx; dx <= rx; dx++) {
			for (int dz = -rx; dz <= rx; dz++) {
				for (int dy = ry; dy >= -ry; dy--) {
					BlockPos pos = center.add(dx, dy, dz);
					if (pos.getY() < w.getBottomY() || pos.getY() >= w.getTopY()) continue;
					double cx = pos.getX() + 0.5, cy = pos.getY() + 0.5, cz = pos.getZ() + 0.5;
					if (!region.contains(cx, cy, cz)) continue;
					if (!nearAnyPlane(planes, cx, cy, cz, 1.35)) continue;

					BlockState st = w.getBlockState(pos);
					if (st.isAir()) continue;

					if (isOre(st) && !st.isOf(Blocks.AMETHYST_CLUSTER) && oreBlocks < 24) {
						slicedOres += extractOreFromBlockInPlace(p, w, pw, pos, st, fortuneTool);
						oreBlocks++;
					} else if (st.getBlock() instanceof CropBlock crop && crop.isMature(st)) {
						harvestWithReplant(p, w, pos, st, crop.withAge(0), fortuneTool);
						harvested++;
					} else if (st.isOf(Blocks.NETHER_WART) && st.get(NetherWartBlock.AGE) >= NetherWartBlock.MAX_AGE) {
						harvestWithReplant(p, w, pos, st, st.with(NetherWartBlock.AGE, 0), fortuneTool);
						harvested++;
					} else if (st.isOf(Blocks.SWEET_BERRY_BUSH) && st.get(SweetBerryBushBlockAccess.AGE) >= 2) {
						int berries = st.get(SweetBerryBushBlockAccess.AGE) >= 3 ? 3 : 2;
						w.setBlockState(pos, st.with(SweetBerryBushBlockAccess.AGE, 1), Block.NOTIFY_LISTENERS);
						p.getInventory().offerOrDrop(new ItemStack(Items.SWEET_BERRIES, berries));
						harvested++;
					} else if (st.isOf(Blocks.COCOA) && st.get(CocoaBlock.AGE) >= CocoaBlock.MAX_AGE) {
						harvestWithReplant(p, w, pos, st, st.with(CocoaBlock.AGE, 0), fortuneTool);
						harvested++;
					} else if (st.getBlock() instanceof AmethystClusterBlock && st.isOf(Blocks.AMETHYST_CLUSTER)) {
						harvestWithReplant(p, w, pos, st, Blocks.AIR.getDefaultState(), fortuneTool);
						harvested++;
					} else if ((st.getBlock() instanceof SugarCaneBlock || st.getBlock() instanceof BambooBlock)
						&& w.getBlockState(pos.down()).isOf(st.getBlock())) {
						harvestWithReplant(p, w, pos, st, Blocks.AIR.getDefaultState(), fortuneTool);
						harvested++;
					}
				}
			}
		}

		if (harvested > 0 || slicedOres > 0) {
			if (harvested > 0) {
				playSound(w, aim, SoundEvents.ITEM_CROP_PLANT, 0.9f, 1.25f);
				p.sendMessage(Text.literal("§a✦ Amputate Quirúrgico: §f¡" + harvested + " cultivos/amatistas cosechados y replantados!"), true);
			} else {
				playSound(w, aim, SoundEvents.ENTITY_ITEM_PICKUP, 0.9f, 1.45f);
				p.sendMessage(Text.literal("§b✦ Corte Quirúrgico: §f¡" + slicedOres + " minerales puros separados al cortar!"), true);
			}
		}
	}

	private static void playSound(ServerWorld w, Vec3d pos, SoundEvent sound, float volume, float pitch) {
		w.playSound(null, pos.x, pos.y, pos.z, sound, SoundCategory.PLAYERS, volume, pitch);
	}

	private static boolean nearAnyPlane(List<CutEngine.Plane> planes, double x, double y, double z, double maxDist) {
		for (CutEngine.Plane pl : planes) {
			if (Math.abs(pl.dist(x, y, z)) <= maxDist) return true;
		}
		return false;
	}

	private static void harvestWithReplant(ServerPlayerEntity p, ServerWorld w, BlockPos pos, BlockState oldState, BlockState nextState, ItemStack tool) {
		List<ItemStack> drops = Block.getDroppedStacks(oldState, w, pos, null, p, tool);
		w.setBlockState(pos, nextState, Block.NOTIFY_LISTENERS);
		w.spawnParticles(CutEngine.ROOM_DUST, pos.getX() + 0.5, pos.getY() + 0.4, pos.getZ() + 0.5, 4, 0.25, 0.2, 0.25, 0);
		for (ItemStack d : drops) {
			if (!d.isEmpty()) p.getInventory().offerOrDrop(d);
		}
	}

	/** Evita conflicto de importación de {@link net.minecraft.block.SweetBerryBushBlock}. */
	private static final class SweetBerryBushBlockAccess {
		static final net.minecraft.state.property.IntProperty AGE = net.minecraft.block.SweetBerryBushBlock.AGE;
	}
}
