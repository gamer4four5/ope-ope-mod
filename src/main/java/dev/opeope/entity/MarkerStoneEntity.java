package dev.opeope.entity;

import dev.opeope.registry.ModEntities;
import dev.opeope.registry.ModItems;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.projectile.thrown.ThrownItemEntity;
import net.minecraft.item.Item;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.EntityHitResult;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;

/** G: la piedra marcador. Se queda donde cae y sirve de destino para Shambles. */
public class MarkerStoneEntity extends ThrownItemEntity {
	private int landedTicks = -1;

	public MarkerStoneEntity(EntityType<? extends MarkerStoneEntity> type, World world) {
		super(type, world);
	}

	public MarkerStoneEntity(World world, LivingEntity owner) {
		super(ModEntities.MARKER_STONE, owner, world);
	}

	@Override
	protected Item getDefaultItem() {
		return ModItems.MARKER_STONE;
	}

	private void land(Vec3d at) {
		setVelocity(Vec3d.ZERO);
		setNoGravity(true);
		setPosition(at);
		if (landedTicks < 0) landedTicks = 0;
	}

	@Override
	protected void onBlockHit(BlockHitResult hit) {
		land(hit.getPos().add(Vec3d.of(hit.getSide().getVector()).multiply(0.15)));
	}

	@Override
	protected void onEntityHit(EntityHitResult hit) {
		land(getPos());
	}

	@Override
	public void tick() {
		super.tick();
		if (!getWorld().isClient) {
			if (landedTicks >= 0 && ++landedTicks > 20 * 45) discard();
			if (age > 20 * 90) discard();
		}
	}
}
