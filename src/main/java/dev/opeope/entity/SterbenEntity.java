package dev.opeope.entity;

import dev.opeope.power.CutEngine;
import dev.opeope.power.CutStates;
import dev.opeope.power.PlayerPower;
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
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Sterben (Hoja de la Muerte): la Kikoku gira a altísima velocidad como una sierra circular quirúrgica 3D que corta enemigos y estructuras. */
public class SterbenEntity extends Entity {
	public static final double SPEED = 1.25;
	public static final double RANGE = 32;
	private UUID owner;
	private Vec3d dir = new Vec3d(0, 0, 1);
	private double traveled;
	private boolean cutStructureDone = false;
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
		Vec3d start = p.getEyePos().add(look.multiply(1.5)).subtract(0, 0.3, 0);
		e.setPosition(start);
		e.setYaw(p.getYaw());
		e.setPitch(p.getPitch());
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

		// Anillo de partículas 3D de la sierra giratoria
		Vec3d right = dir.crossProduct(new Vec3d(0, 1, 0));
		if (right.lengthSquared() < 1e-4) right = new Vec3d(1, 0, 0);
		right = right.normalize();
		Vec3d up = right.crossProduct(dir).normalize();
		for (int i = 0; i < 6; i++) {
			double ang = (age * 0.9) + i * Math.PI / 3.0;
			Vec3d rim = getPos().add(right.multiply(Math.cos(ang) * 2.1)).add(up.multiply(Math.sin(ang) * 2.1));
			w.spawnParticles(CutEngine.ROOM_DUST, rim.x, rim.y, rim.z, 1, 0.05, 0.05, 0.05, 0);
		}
		w.spawnParticles(ParticleTypes.SWEEP_ATTACK, getX(), getY(), getZ(), 2, 0.7, 0.2, 0.7, 0);
		w.spawnParticles(ParticleTypes.END_ROD, getX(), getY(), getZ(), 4, 1.1, 0.2, 1.1, 0.03);
		if (age % 3 == 0) {
			w.playSound(null, getX(), getY(), getZ(), SoundEvents.ENTITY_PLAYER_ATTACK_SWEEP, SoundCategory.PLAYERS, 1.0f, 1.7f);
		}

		for (LivingEntity le : w.getEntitiesByClass(LivingEntity.class, getBoundingBox().expand(2.2), e -> e.isAlive() && e != p)) {
			Integer until = hit.get(le.getId());
			if (until != null && until > age) continue;
			hit.put(le.getId(), age + 6);
			DamageSource src = p != null ? w.getDamageSources().playerAttack(p) : w.getDamageSources().magic();
			le.damage(src, 9f);
			le.addVelocity(dir.x * 0.45, 0.18, dir.z * 0.45);
			le.velocityModified = true;
			CutStates.cutLiving(le, List.of(
				new CutEngine.Plane(new Vec3d(0, 1, 0), getPos()),
				new CutEngine.Plane(right, getPos())
			), 20 * 14, 0);
		}

		// Dentro del ROOM la sierra corta estructuras sólidas (casas, árboles, torres) y tritura obstáculos blandos
		if (p != null) {
			PlayerPower pw = PowerManager.get(p);
			Room room = pw.room;
			if (room != null && room.contains(getPos())) {
				BlockPos c = BlockPos.ofFloored(getPos());
				boolean hitSolid = false;
				for (BlockPos bp : BlockPos.iterate(c.add(-1, -1, -1), c.add(1, 1, 1))) {
					BlockState s = w.getBlockState(bp);
					if (s.isAir()) continue;
					float h = s.getHardness(w, bp);
					if (h >= 0 && h < 0.8f) {
						w.breakBlock(bp, true, p);
					} else if (h >= 0.8f) {
						hitSolid = true;
					}
				}
				if (hitSolid && !cutStructureDone) {
					cutStructureDone = true;
					CutEngine.Region reg = CutEngine.Region.of(room).withLimit(getPos(), 7.5);
					CutEngine.cut(p, List.of(new CutEngine.Plane(new Vec3d(0, 1, 0), getPos())), reg, pw.suspend, 0, 12f, true);
				}
			}
		}
		if (traveled > RANGE) discard();
	}
}
