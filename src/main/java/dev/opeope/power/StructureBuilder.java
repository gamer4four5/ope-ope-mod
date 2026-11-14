package dev.opeope.power;

import dev.opeope.registry.ModItems;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.entity.mob.PillagerEntity;
import net.minecraft.entity.mob.VindicatorEntity;
import net.minecraft.inventory.Inventory;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.Hand;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

/**
 * Generador de Isla Bisturí (fusión): permite invocar la Casa de Isla Bisturí, Torre de Vigilancia,
 * Puente y Muralla, Formación Rocosa, Escuadrón Pirata, Barco de Guerra de la Marina destructible (17 bloques)
 * con Capitán Smoker y Puesto del Tablón de Misiones para probar todos los cortes y técnicas.
 */
public final class StructureBuilder {
	private StructureBuilder() {}

	public static BlockPos frontGroundPos(ServerWorld world, ServerPlayerEntity player, int forwardDist) {
		Vec3d look = player.getRotationVector();
		Vec3d flat = new Vec3d(look.x, 0, look.z);
		if (flat.lengthSquared() < 1e-4) flat = new Vec3d(0, 0, 1);
		else flat = flat.normalize();

		int cx = (int) Math.floor(player.getX() + flat.x * forwardDist);
		int cz = (int) Math.floor(player.getZ() + flat.z * forwardDist);
		int cy = player.getBlockY();

		for (int dy = 6; dy >= -8; dy--) {
			BlockPos check = new BlockPos(cx, cy + dy, cz);
			BlockPos below = check.down();
			if (world.getBlockState(check).isAir() && !world.getBlockState(below).isAir()) {
				return check;
			}
		}
		return new BlockPos(cx, cy, cz);
	}

	private static void ensureRoomAround(ServerPlayerEntity player, BlockPos targetPos, double minRadius) {
		PlayerPower pw = PowerManager.get(player);
		if (pw.room == null || pw.room.dim != player.getWorld().getRegistryKey()) {
			Room room = new Room(player.getWorld().getRegistryKey(), player.getPos().add(0, 1.0, 0), Math.max(minRadius, 28.0));
			room.state = Room.State.FIXED;
			pw.room = room;
			pw.lastRoomRadius = room.radius;
			PowerManager.broadcastRoom(player, 0, room);
		} else if (pw.room.radius < minRadius) {
			pw.room.radius = minRadius;
			pw.room.state = Room.State.FIXED;
			PowerManager.broadcastRoom(player, 0, pw.room);
		}
	}

	public static void buildBisturiHouse(ServerWorld world, ServerPlayerEntity player) {
		BlockPos origin = frontGroundPos(world, player, 10);
		ensureRoomAround(player, origin, 26.0);
		BlockState foundation = Blocks.STONE_BRICKS.getDefaultState();
		BlockState pillar = Blocks.SPRUCE_LOG.getDefaultState();
		BlockState wall = Blocks.BIRCH_PLANKS.getDefaultState();
		BlockState trim = Blocks.DARK_OAK_PLANKS.getDefaultState();
		BlockState glass = Blocks.CYAN_STAINED_GLASS.getDefaultState();
		BlockState roof = Blocks.CRIMSON_PLANKS.getDefaultState();
		BlockState roofEdge = Blocks.POLISHED_BLACKSTONE_BRICKS.getDefaultState();

		for (int dx = -3; dx <= 3; dx++) {
			for (int dz = -3; dz <= 3; dz++) {
				world.setBlockState(origin.add(dx, 0, dz), foundation);
				for (int dy = 1; dy <= 8; dy++) {
					world.setBlockState(origin.add(dx, dy, dz), Blocks.AIR.getDefaultState());
				}
			}
		}

		for (int dy = 1; dy <= 6; dy++) {
			for (int dx = -3; dx <= 3; dx++) {
				for (int dz = -3; dz <= 3; dz++) {
					boolean borderX = Math.abs(dx) == 3;
					boolean borderZ = Math.abs(dz) == 3;
					if (!borderX && !borderZ) {
						if (dy == 4) world.setBlockState(origin.add(dx, dy, dz), trim);
						continue;
					}
					if (dz == -3 && dx == 0 && (dy == 1 || dy == 2)) continue;
					if (borderX && borderZ) {
						world.setBlockState(origin.add(dx, dy, dz), pillar);
					} else if (dy == 4) {
						world.setBlockState(origin.add(dx, dy, dz), trim);
					} else if ((dy == 2 || dy == 5) && (Math.abs(dx) == 1 || Math.abs(dz) == 1) && !(dz == -3 && dy == 2)) {
						world.setBlockState(origin.add(dx, dy, dz), glass);
					} else {
						world.setBlockState(origin.add(dx, dy, dz), wall);
					}
				}
			}
		}

		for (int r = 0; r <= 3; r++) {
			int ry = 7 + r;
			int halfW = 3 - r;
			for (int dz = -3; dz <= 3; dz++) {
				for (int dx = -halfW; dx <= halfW; dx++) {
					boolean edge = Math.abs(dx) == halfW || Math.abs(dz) == 3;
					world.setBlockState(origin.add(dx, ry, dz), edge ? roofEdge : roof);
				}
			}
		}
		world.setBlockState(origin.add(0, 3, 0), Blocks.SEA_LANTERN.getDefaultState());
		world.setBlockState(origin.add(0, 6, 0), Blocks.SEA_LANTERN.getDefaultState());
		world.playSound(null, origin, SoundEvents.BLOCK_WOOD_PLACE, SoundCategory.BLOCKS, 1.2f, 0.9f);
		player.sendMessage(Text.literal("§bCasa de Isla Bisturí generada · ¡Córtala en dos o elévala con Takt [R]!"), true);
	}

	public static void buildWatchtower(ServerWorld world, ServerPlayerEntity player) {
		BlockPos origin = frontGroundPos(world, player, 10);
		ensureRoomAround(player, origin, 28.0);
		BlockState brick = Blocks.STONE_BRICKS.getDefaultState();
		BlockState chiseled = Blocks.CHISELED_STONE_BRICKS.getDefaultState();
		BlockState pillar = Blocks.POLISHED_DEEPSLATE.getDefaultState();
		BlockState glass = Blocks.LIGHT_BLUE_STAINED_GLASS.getDefaultState();
		BlockState plank = Blocks.SPRUCE_PLANKS.getDefaultState();

		for (int dy = 0; dy <= 11; dy++) {
			int rad = (dy >= 9) ? 3 : 2;
			for (int dx = -rad; dx <= rad; dx++) {
				for (int dz = -rad; dz <= rad; dz++) {
					boolean border = Math.abs(dx) == rad || Math.abs(dz) == rad;
					boolean corner = Math.abs(dx) == rad && Math.abs(dz) == rad;
					BlockPos p = origin.add(dx, dy, dz);
					if (dy == 0 || dy == 4 || dy == 8 || dy == 9) {
						world.setBlockState(p, border ? pillar : plank);
					} else if (dy == 11) {
						if (border && ((dx + dz) % 2 == 0)) world.setBlockState(p, chiseled);
						else world.setBlockState(p, Blocks.AIR.getDefaultState());
					} else if (border) {
						if (corner) world.setBlockState(p, pillar);
						else if ((dy == 2 || dy == 6 || dy == 10) && (dx == 0 || dz == 0)) world.setBlockState(p, glass);
						else world.setBlockState(p, brick);
					} else {
						world.setBlockState(p, Blocks.AIR.getDefaultState());
					}
				}
			}
		}
		world.setBlockState(origin.add(0, 10, 0), Blocks.SEA_LANTERN.getDefaultState());
		world.playSound(null, origin, SoundEvents.BLOCK_STONE_PLACE, SoundCategory.BLOCKS, 1.2f, 0.8f);
		player.sendMessage(Text.literal("§bTorre de Vigilancia generada · ¡Pruébala con Rodajas [Tab+1] o Takt!"), true);
	}

	public static void buildBridgeAndWall(ServerWorld world, ServerPlayerEntity player) {
		BlockPos origin = frontGroundPos(world, player, 9);
		ensureRoomAround(player, origin, 26.0);
		BlockState stone = Blocks.STONE_BRICKS.getDefaultState();
		BlockState trim = Blocks.POLISHED_ANDESITE.getDefaultState();
		BlockState lantern = Blocks.SEA_LANTERN.getDefaultState();

		for (int dx = -5; dx <= 5; dx++) {
			int archH = Math.max(0, 3 - (Math.abs(dx) / 2));
			for (int dz = -1; dz <= 1; dz++) {
				for (int dy = 0; dy <= 5; dy++) {
					BlockPos p = origin.add(dx, dy, dz);
					if (dy < archH && Math.abs(dx) <= 3) {
						world.setBlockState(p, Blocks.AIR.getDefaultState());
					} else if (dy == 4) {
						world.setBlockState(p, trim);
					} else if (dy == 5) {
						if (Math.abs(dz) == 1) world.setBlockState(p, (Math.abs(dx) % 2 == 0) ? lantern : stone);
						else world.setBlockState(p, Blocks.AIR.getDefaultState());
					} else {
						world.setBlockState(p, stone);
					}
				}
			}
		}
		world.playSound(null, origin, SoundEvents.BLOCK_STONE_PLACE, SoundCategory.BLOCKS, 1.2f, 0.85f);
		player.sendMessage(Text.literal("§bPuente y Muralla de Isla Bisturí generados"), true);
	}

	public static void buildRockFormation(ServerWorld world, ServerPlayerEntity player) {
		BlockPos center = frontGroundPos(world, player, 9);
		ensureRoomAround(player, center, 24.0);
		int[][] offsets = {{-4, 0, -2}, {4, 0, -2}, {-2, 0, 3}, {3, 0, 3}, {0, 0, 0}};
		BlockState[] mats = {
			Blocks.COBBLESTONE.getDefaultState(),
			Blocks.MOSSY_COBBLESTONE.getDefaultState(),
			Blocks.ANDESITE.getDefaultState(),
			Blocks.TUFF.getDefaultState(),
			Blocks.DEEPSLATE_BRICKS.getDefaultState()
		};
		for (int i = 0; i < offsets.length; i++) {
			BlockPos base = center.add(offsets[i][0], 0, offsets[i][2]);
			int h = (i == 4) ? 5 : 3;
			for (int dy = 0; dy < h; dy++) {
				for (int dx = -1; dx <= 1; dx++) {
					for (int dz = -1; dz <= 1; dz++) {
						if (Math.abs(dx) + Math.abs(dz) <= 2) {
							world.setBlockState(base.add(dx, dy, dz), mats[(i + dy) % mats.length]);
						}
					}
				}
			}
		}
		world.playSound(null, center, SoundEvents.BLOCK_DEEPSLATE_PLACE, SoundCategory.BLOCKS, 1.2f, 0.8f);
		player.sendMessage(Text.literal("§bFormación Rocosa generada · ¡Ideal para Takt y Forjar Armas [R + 1..5]!"), true);
	}

	public static void spawnPirateSquad(ServerWorld world, ServerPlayerEntity player) {
		BlockPos center = frontGroundPos(world, player, 8);
		ensureRoomAround(player, center, 26.0);

		VindicatorEntity boss = EntityType.VINDICATOR.create(world);
		if (boss != null) {
			boss.refreshPositionAndAngles(center.getX() + 0.5, center.getY() + 0.2, center.getZ() + 0.5, 0f, 0f);
			boss.setCustomName(Text.literal("§c§lCapitán de Isla Bisturí"));
			boss.setCustomNameVisible(true);
			boss.setStackInHand(Hand.MAIN_HAND, new ItemStack(Items.IRON_SWORD));
			boss.equipStack(EquipmentSlot.CHEST, new ItemStack(Items.IRON_CHESTPLATE));
			var maxHp = boss.getAttributeInstance(EntityAttributes.GENERIC_MAX_HEALTH);
			if (maxHp != null) maxHp.setBaseValue(120.0);
			boss.setHealth(120.0f);
			boss.setPersistent();
			world.spawnEntity(boss);
		}

		String[] names = {"§ePirata de Guardia", "§bTirador de Kairoseki", "§6Corsario del Puerto"};
		double[][] posOff = {{-2.5, 0, -1.5}, {2.5, 0, -1.5}, {0, 0, 2.5}};
		for (int i = 0; i < 3; i++) {
			if (i == 1) {
				PillagerEntity rifleman = EntityType.PILLAGER.create(world);
				if (rifleman != null) {
					rifleman.refreshPositionAndAngles(center.getX() + posOff[i][0], center.getY() + 0.2, center.getZ() + posOff[i][2], 0f, 0f);
					rifleman.setCustomName(Text.literal(names[i]));
					rifleman.setCustomNameVisible(true);
					rifleman.setStackInHand(Hand.MAIN_HAND, new ItemStack(Items.CROSSBOW));
					var maxHp = rifleman.getAttributeInstance(EntityAttributes.GENERIC_MAX_HEALTH);
					if (maxHp != null) maxHp.setBaseValue(50.0);
					rifleman.setHealth(50.0f);
					rifleman.setPersistent();
					world.spawnEntity(rifleman);
				}
			} else {
				VindicatorEntity crew = EntityType.VINDICATOR.create(world);
				if (crew != null) {
					crew.refreshPositionAndAngles(center.getX() + posOff[i][0], center.getY() + 0.2, center.getZ() + posOff[i][2], 0f, 0f);
					crew.setCustomName(Text.literal(names[i]));
					crew.setCustomNameVisible(true);
					crew.setStackInHand(Hand.MAIN_HAND, new ItemStack(Items.IRON_AXE));
					var maxHp = crew.getAttributeInstance(EntityAttributes.GENERIC_MAX_HEALTH);
					if (maxHp != null) maxHp.setBaseValue(55.0);
					crew.setHealth(55.0f);
					crew.setPersistent();
					world.spawnEntity(crew);
				}
			}
		}
		world.playSound(null, center, SoundEvents.EVENT_RAID_HORN.value(), SoundCategory.HOSTILE, 1.0f, 1.1f);
		player.sendMessage(Text.literal("§c¡Escuadrón Pirata de Isla Bisturí desplegado!"), true);
	}

	public static void buildMarineWarship(ServerWorld world, ServerPlayerEntity player) {
		BlockPos origin = frontGroundPos(world, player, 15);
		ensureRoomAround(player, origin, 34.0);

		BlockState keelMat = Blocks.DARK_OAK_LOG.getDefaultState();
		BlockState hullMat = Blocks.DARK_OAK_PLANKS.getDefaultState();
		BlockState gunwaleMat = Blocks.STRIPPED_DARK_OAK_LOG.getDefaultState();
		BlockState deckMat = Blocks.SPRUCE_PLANKS.getDefaultState();
		BlockState mastMat = Blocks.SPRUCE_LOG.getDefaultState();
		BlockState yardMat = Blocks.SPRUCE_FENCE.getDefaultState();
		BlockState sailWhite = Blocks.WHITE_WOOL.getDefaultState();
		BlockState sailBlue = Blocks.BLUE_WOOL.getDefaultState();
		BlockState cannonMat = Blocks.POLISHED_BLACKSTONE.getDefaultState();
		BlockState goldMat = Blocks.GOLD_BLOCK.getDefaultState();
		BlockState glassMat = Blocks.CYAN_STAINED_GLASS.getDefaultState();
		BlockState lanternMat = Blocks.SEA_LANTERN.getDefaultState();

		for (int dx = -5; dx <= 5; dx++) {
			for (int dz = -10; dz <= 10; dz++) {
				for (int dy = 0; dy <= 16; dy++) {
					world.setBlockState(origin.add(dx, dy, dz), Blocks.AIR.getDefaultState());
				}
			}
		}

		for (int dz = -8; dz <= 8; dz++) {
			world.setBlockState(origin.add(0, 0, dz), keelMat);
		}

		for (int dz = -7; dz <= 7; dz++) {
			int maxW = (Math.abs(dz) >= 6) ? 1 : ((Math.abs(dz) >= 4) ? 2 : 3);
			for (int dy = 1; dy <= 3; dy++) {
				int curW = Math.min(maxW, dy);
				for (int dx = -curW; dx <= curW; dx++) {
					boolean outer = Math.abs(dx) == curW || Math.abs(dz) == 7;
					if (dy == 3) world.setBlockState(origin.add(dx, 3, dz), deckMat);
					else if (outer) world.setBlockState(origin.add(dx, dy, dz), hullMat);
					else world.setBlockState(origin.add(dx, dy, dz), Blocks.AIR.getDefaultState());
				}
			}
			world.setBlockState(origin.add(-maxW, 4, dz), gunwaleMat);
			world.setBlockState(origin.add(maxW, 4, dz), gunwaleMat);
		}

		for (int dz = 4; dz <= 7; dz++) {
			for (int dx = -2; dx <= 2; dx++) {
				for (int dy = 4; dy <= 6; dy++) {
					boolean wall = Math.abs(dx) == 2 || dz == 7 || dy == 6;
					if (wall) {
						if (dy == 5 && Math.abs(dx) == 2) world.setBlockState(origin.add(dx, dy, dz), glassMat);
						else if (dy == 6 && Math.abs(dx) == 2) world.setBlockState(origin.add(dx, dy, dz), goldMat);
						else world.setBlockState(origin.add(dx, dy, dz), hullMat);
					} else {
						world.setBlockState(origin.add(dx, dy, dz), Blocks.AIR.getDefaultState());
					}
				}
			}
		}
		world.setBlockState(origin.add(0, 5, 4), Blocks.AIR.getDefaultState());
		world.setBlockState(origin.add(0, 7, 7), lanternMat);

		for (int dy = 4; dy <= 13; dy++) {
			world.setBlockState(origin.add(0, dy, -3), mastMat);
		}
		for (int dx = -2; dx <= 2; dx++) {
			world.setBlockState(origin.add(dx, 8, -3), yardMat);
			world.setBlockState(origin.add(dx, 11, -3), yardMat);
			world.setBlockState(origin.add(dx, 9, -3), (dx == 0) ? sailBlue : sailWhite);
			world.setBlockState(origin.add(dx, 10, -3), sailWhite);
			world.setBlockState(origin.add(dx, 12, -3), sailWhite);
		}

		for (int dy = 4; dy <= 15; dy++) {
			world.setBlockState(origin.add(0, dy, 2), mastMat);
		}
		for (int dx = -3; dx <= 3; dx++) {
			world.setBlockState(origin.add(dx, 9, 2), yardMat);
			world.setBlockState(origin.add(dx, 13, 2), yardMat);
			world.setBlockState(origin.add(dx, 10, 2), sailWhite);
			world.setBlockState(origin.add(dx, 11, 2), (Math.abs(dx) <= 1) ? sailBlue : sailWhite);
			world.setBlockState(origin.add(dx, 12, 2), sailWhite);
			if (Math.abs(dx) <= 2) world.setBlockState(origin.add(dx, 14, 2), sailWhite);
		}
		world.setBlockState(origin.add(1, 14, 2), deckMat);
		world.setBlockState(origin.add(-1, 14, 2), deckMat);

		for (int i = 1; i <= 4; i++) {
			world.setBlockState(origin.add(0, 4 + (i / 2), -7 - i), yardMat);
		}

		world.setBlockState(origin.add(-3, 4, -1), cannonMat);
		world.setBlockState(origin.add(-3, 4, 1), cannonMat);
		world.setBlockState(origin.add(3, 4, -1), cannonMat);
		world.setBlockState(origin.add(3, 4, 1), cannonMat);

		VindicatorEntity capt = EntityType.VINDICATOR.create(world);
		if (capt != null) {
			capt.refreshPositionAndAngles(origin.getX() + 0.5, origin.getY() + 4.2, origin.getZ() + 5.5, 0f, 0f);
			capt.setCustomName(Text.literal("§b§lCapitán Smoker (Marina)"));
			capt.setCustomNameVisible(true);
			capt.setStackInHand(Hand.MAIN_HAND, new ItemStack(Items.MACE));
			capt.equipStack(EquipmentSlot.CHEST, new ItemStack(Items.NETHERITE_CHESTPLATE));
			capt.equipStack(EquipmentSlot.HEAD, new ItemStack(Items.IRON_HELMET));
			var hp = capt.getAttributeInstance(EntityAttributes.GENERIC_MAX_HEALTH);
			if (hp != null) hp.setBaseValue(160.0);
			capt.setHealth(160.0f);
			capt.setPersistent();
			world.spawnEntity(capt);
		}

		for (int i = 0; i < 4; i++) {
			VindicatorEntity marine = EntityType.VINDICATOR.create(world);
			if (marine != null) {
				double zOff = -5.0 + i * 2.8;
				double xOff = (i % 2 == 0) ? -1.5 : 1.5;
				marine.refreshPositionAndAngles(origin.getX() + xOff, origin.getY() + 4.2, origin.getZ() + zOff, 0f, 0f);
				marine.setCustomName(Text.literal("§f§lMarinero de la Marina"));
				marine.setCustomNameVisible(true);
				marine.setStackInHand(Hand.MAIN_HAND, new ItemStack(Items.IRON_SWORD));
				marine.equipStack(EquipmentSlot.HEAD, new ItemStack(Items.IRON_HELMET));
				var hp = marine.getAttributeInstance(EntityAttributes.GENERIC_MAX_HEALTH);
				if (hp != null) hp.setBaseValue(50.0);
				marine.setHealth(50.0f);
				marine.setPersistent();
				world.spawnEntity(marine);
			}
		}

		world.playSound(null, origin, SoundEvents.EVENT_RAID_HORN.value(), SoundCategory.HOSTILE, 1.2f, 0.85f);
		player.sendMessage(Text.literal("§b§l[MARINA] §f¡Barco de Guerra destructible (17 bloques) avistado! Pártelo en dos con tu ROOM"), true);
	}

	public static void buildMissionOutpost(ServerWorld world, ServerPlayerEntity player) {
		BlockPos origin = frontGroundPos(world, player, 10);
		ensureRoomAround(player, origin, 26.0);

		BlockState floorMat = Blocks.STONE_BRICKS.getDefaultState();
		BlockState pillarMat = Blocks.SPRUCE_LOG.getDefaultState();
		BlockState fenceMat = Blocks.SPRUCE_FENCE.getDefaultState();
		BlockState plankMat = Blocks.SPRUCE_PLANKS.getDefaultState();
		BlockState lanternMat = Blocks.SEA_LANTERN.getDefaultState();

		for (int dx = -3; dx <= 3; dx++) {
			for (int dz = -3; dz <= 3; dz++) {
				world.setBlockState(origin.add(dx, 0, dz), floorMat);
				for (int dy = 1; dy <= 5; dy++) {
					world.setBlockState(origin.add(dx, dy, dz), Blocks.AIR.getDefaultState());
				}
			}
		}

		for (int[] corner : new int[][]{{-3, -3}, {3, -3}, {-3, 3}, {3, 3}}) {
			for (int dy = 1; dy <= 4; dy++) {
				world.setBlockState(origin.add(corner[0], dy, corner[1]), pillarMat);
			}
		}
		for (int dx = -3; dx <= 3; dx++) {
			for (int dz = -3; dz <= 3; dz++) {
				world.setBlockState(origin.add(dx, 5, dz), plankMat);
			}
		}
		world.setBlockState(origin.add(0, 4, 0), lanternMat);

		world.setBlockState(origin.add(0, 1, 2), fenceMat);
		world.setBlockState(origin.add(0, 2, 2), Blocks.DARK_OAK_HANGING_SIGN.getDefaultState());
		world.setBlockState(origin.add(-1, 1, 2), fenceMat);
		world.setBlockState(origin.add(1, 1, 2), fenceMat);

		BlockPos chestPos = origin.add(2, 1, 0);
		world.setBlockState(chestPos, Blocks.CHEST.getDefaultState());
		BlockEntity be = world.getBlockEntity(chestPos);
		if (be instanceof Inventory inv) {
			inv.setStack(0, new ItemStack(ModItems.FRUIT, 1));
			inv.setStack(1, new ItemStack(ModItems.KIKOKU, 1));
			inv.setStack(2, new ItemStack(ModItems.MARKER_STONE, 16));
			inv.setStack(3, new ItemStack(Items.EMERALD, 32));
		}

		for (int i = 0; i < 2; i++) {
			VindicatorEntity guard = EntityType.VINDICATOR.create(world);
			if (guard != null) {
				guard.refreshPositionAndAngles(origin.getX() + (i == 0 ? -1.5 : 1.5), origin.getY() + 1.2, origin.getZ() - 2.0, 0f, 0f);
				guard.setCustomName(Text.literal("§9§lGuardia de Kairoseki"));
				guard.setCustomNameVisible(true);
				guard.setStackInHand(Hand.MAIN_HAND, new ItemStack(Items.IRON_SWORD));
				guard.equipStack(EquipmentSlot.HEAD, new ItemStack(Items.IRON_HELMET));
				guard.equipStack(EquipmentSlot.CHEST, new ItemStack(Items.CHAINMAIL_CHESTPLATE));
				var hp = guard.getAttributeInstance(EntityAttributes.GENERIC_MAX_HEALTH);
				if (hp != null) hp.setBaseValue(65.0);
				guard.setHealth(65.0f);
				guard.setPersistent();
				world.spawnEntity(guard);
			}
		}

		player.sendMessage(Text.literal("§6§l[TABLÓN DE MISIONES] §ePuesto de la Marina generado con suministros y misiones:"), false);
		player.sendMessage(Text.literal(" §c1. Partir en dos el Barco de Guerra de la Marina"), false);
		player.sendMessage(Text.literal(" §b2. Derrotar al Capitán Smoker y extraer su corazón con Mes [Tab+4]"), false);
		player.sendMessage(Text.literal(" §d3. Modo Unión [Tab+C]: crear una Quimera Quirúrgica"), false);
	}
}
