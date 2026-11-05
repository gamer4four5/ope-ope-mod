package dev.opeope.registry;

import dev.opeope.OpeOpeMod;
import dev.opeope.entity.MarkerStoneEntity;
import dev.opeope.entity.PieceEntity;
import dev.opeope.entity.SterbenEntity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.SpawnGroup;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;

public final class ModEntities {
	private ModEntities() {}

	public static EntityType<PieceEntity> PIECE;
	public static EntityType<MarkerStoneEntity> MARKER_STONE;
	public static EntityType<SterbenEntity> STERBEN;

	public static void init() {
		PIECE = Registry.register(Registries.ENTITY_TYPE, OpeOpeMod.id("pieza"),
			EntityType.Builder.<PieceEntity>create(PieceEntity::new, SpawnGroup.MISC)
				.dimensions(1f, 1f).maxTrackingRange(16).trackingTickInterval(1).build("pieza"));
		MARKER_STONE = Registry.register(Registries.ENTITY_TYPE, OpeOpeMod.id("piedra_marcador"),
			EntityType.Builder.<MarkerStoneEntity>create(MarkerStoneEntity::new, SpawnGroup.MISC)
				.dimensions(0.3f, 0.3f).maxTrackingRange(16).trackingTickInterval(2).build("piedra_marcador"));
		STERBEN = Registry.register(Registries.ENTITY_TYPE, OpeOpeMod.id("sterben"),
			EntityType.Builder.<SterbenEntity>create(SterbenEntity::new, SpawnGroup.MISC)
				.dimensions(2.4f, 0.6f).maxTrackingRange(10).trackingTickInterval(1).build("sterben"));
	}
}
