package dev.opeope.entity;

import dev.opeope.power.CutStates;
import dev.opeope.power.PowerManager;
import dev.opeope.power.Room;
import dev.opeope.registry.ModEntities;
import net.minecraft.block.BlockState;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.damage.DamageSource;
import net.minecraft.entity.data.DataTracker;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.network.listener.ClientPlayPacketListener;
import net.minecraft.network.packet.Packet;
import net.minecraft.network.packet.s2c.play.EntitySpawnS2CPacket;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.server.network.EntityTrackerEntry;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** Sterben: la nodachi gira como una sierra y corta todo lo que cruza. */
public class SterbenEntity extends Entity {
	public static final double SPEED = 1.1;
	public static final double RANGE = 24;
	private UUID owner;
	private Vec3d dir = new Vec3d(0, 0, 1);
	private double traveled;
	private final Map<Integer, Integer> hit = new HashMap<>();

	public SterbenEntity(EntityType<? extends SterbenEntity> type, World world) {
		super(type, world);
		this.noClip = true;
	}

	public static SterbenEntity launch(ServerPlayerEntity p) {
		SterbenEntity e = new SterbenEntity(ModEntities.STERBEN, p.getWorld());
		e.owner = p.getUuid();
		Vec3d look = p.getRotationVector();
		e.dir = look;
		Vec3d start = p.getEyePos().add(look.multiply(1.5)).subtract(0, 0.4, 0);
		e.setPosition(start);
		e.setYaw(p.getYaw());
		p.getWorld().spawnEntity(e);
		return e;
	}

	@Override
	protected void initDataTracker(DataTracker.Builder builder) {}

	@Override
	protected void readCustomDataFromNbt(NbtCompound nbt) {}

	@Override
	protected void writeCustomDataToNbt(NbtCompound nbt) {}

	@Override
	public boolean shouldSave() {
		return false;
	}

	public Packet<ClientPlayPacketListener> createSpawnPacket(EntityTrackerEntry entry) {
		return new EntitySpawnS2CPacket(this, entry);
	}

	@Override
	public boolean damage(DamageSource source, float amount) {
		return false;
	}

	@Override
	public void tick() {
		super.tick();
		if (getWorld().isClient) return;
		ServerWorld w = (ServerWorld) getWorld();
		ServerPlayerEntity p = owner == null ? null : w.getServer().getPlayerManager().getPlayer(owner);
		Vec3d next = getPos().add(dir.multiply(SPEED));
		setPosition(next);
		traveled += SPEED;
		w.spawnParticles(ParticleTypes.SWEEP_ATTACK, getX(), getY(), getZ(), 1, 0.4, 0.1, 0.4, 0);
		w.spawnParticles(ParticleTypes.END_ROD, getX(), getY(), getZ(), 3, 0.8, 0.1, 0.8, 0.02);
		if (age % 4 == 0) w.playSound(null, getX(), getY(), getZ(), SoundEvents.ENTITY_PLAYER_ATTACK_SWEEP, SoundCategory.PLAYERS, 0.8f, 1.6f);

		for (LivingEntity le : w.getEntitiesByClass(LivingEntity.class, getBoundingBox().expand(1.0), e -> e.isAlive() && e != p)) {
			Integer until = hit.get(le.getId());
			if (until != null && until > age) continue;
			hit.put(le.getId(), age + 12);
			DamageSource src = p != null ? w.getDamageSources().playerAttack(p) : w.getDamageSources().magic();
			le.damage(src, 5f);
			Vec3d n = dir.crossProduct(new Vec3d(0, 1, 0));
			if (n.lengthSquared() < 1e-3) n = new Vec3d(1, 0, 0);
			CutStates.cutLiving(le, java.util.List.of(new dev.opeope.power.CutEngine.Plane(new Vec3d(0, 1, 0), getPos())), 20 * 12, 0);
		}

		// Dentro del ROOM la sierra raja lo blando que encuentra (hojas, plantas, cristal).
		Room room = p != null ? PowerManager.get(p).room : null;
		if (room != null && room.contains(getPos())) {
			BlockPos c = BlockPos.ofFloored(getPos());
			for (BlockPos bp : BlockPos.iterate(c.add(-1, -1, -1), c.add(1, 1, 1))) {
				BlockState s = w.getBlockState(bp);
				if (s.isAir()) continue;
				float h = s.getHardness(w, bp);
				if (h >= 0 && h < 0.8f) w.breakBlock(bp, true, p);
			}
		}
		if (traveled > RANGE) discard();
	}
}
