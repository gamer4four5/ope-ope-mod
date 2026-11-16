package dev.opeope.entity;

import dev.opeope.OpeConfig;
import dev.opeope.anatomy.Anatomy;
import dev.opeope.anatomy.AnatomyRegistry;
import dev.opeope.power.Dismember;
import dev.opeope.registry.ModEntities;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityDimensions;
import net.minecraft.entity.EntityPose;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.MovementType;
import net.minecraft.entity.damage.DamageSource;
import net.minecraft.entity.data.DataTracker;
import net.minecraft.entity.data.TrackedData;
import net.minecraft.entity.data.TrackedDataHandlerRegistry;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.network.listener.ClientPlayPacketListener;
import net.minecraft.network.packet.Packet;
import net.minecraft.network.packet.s2c.play.EntitySpawnS2CPacket;
import net.minecraft.server.network.EntityTrackerEntry;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;
import net.minecraft.world.explosion.Explosion;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

/**
 * Una parte cortada de un ser vivo (estilo Law): sigue viva y unida a su dueño. No tiene vida propia: lo que le pasa
 * (golpes, fuego) le pasa al dueño. El dueño conserva su entidad (el núcleo) y deja de dibujar estas partes; esta
 * entidad las dibuja con el modelo y la textura de verdad del dueño (cliente: BodyPartRenderer).
 */
public class BodyPartEntity extends Entity {
	private static final TrackedData<Integer> VICTIM = DataTracker.registerData(BodyPartEntity.class, TrackedDataHandlerRegistry.INTEGER);
	private static final TrackedData<String> SEGS = DataTracker.registerData(BodyPartEntity.class, TrackedDataHandlerRegistry.STRING);
	private static final TrackedData<String> TYPE = DataTracker.registerData(BodyPartEntity.class, TrackedDataHandlerRegistry.STRING);
	private static final TrackedData<Float> SCALE = DataTracker.registerData(BodyPartEntity.class, TrackedDataHandlerRegistry.FLOAT);
	private static final TrackedData<Float> ROLL = DataTracker.registerData(BodyPartEntity.class, TrackedDataHandlerRegistry.FLOAT);
	private static final TrackedData<Byte> FLAGS = DataTracker.registerData(BodyPartEntity.class, TrackedDataHandlerRegistry.BYTE);

	public static final int F_LOCKED = 1;   // Radio Knife: no se puede reunir aún (borde eléctrico)

	/** Cuántas hay vivas en el servidor (para el tope del mundo). */
	public static int LIVE;

	public UUID victimUuid;
	public UUID cutter;
	private List<String> segments = List.of();
	private EntityDimensions dims = EntityDimensions.changing(0.5f, 0.5f);
	private int orphanTicks;
	private int life;
	private float spinYaw, spinPitch, spinRoll;
	private boolean counted;

	public BodyPartEntity(EntityType<? extends BodyPartEntity> type, World world) {
		super(type, world);
	}

	public static BodyPartEntity create(ServerWorld w, LivingEntity victim, List<String> segs, UUID cutter) {
		BodyPartEntity e = new BodyPartEntity(ModEntities.BODY_PART, w);
		e.victimUuid = victim.getUuid();
		e.cutter = cutter;
		e.dataTracker.set(TYPE, EntityType.getId(victim.getType()).toString());
		e.dataTracker.set(SCALE, victim.getScale() * (victim.isBaby() ? 0.5f : 1f));
		e.dataTracker.set(VICTIM, victim.getId());
		e.setSegments(segs);
		float r = w.random.nextFloat() - 0.5f;
		e.spinYaw = r * 30f;
		e.spinPitch = (w.random.nextFloat() - 0.5f) * 30f;
		e.spinRoll = (w.random.nextFloat() - 0.5f) * 20f;
		e.setYaw(victim.getBodyYaw());
		return e;
	}

	// ------------------------------------------------------------------ datos

	@Override
	protected void initDataTracker(DataTracker.Builder builder) {
		builder.add(VICTIM, -1);
		builder.add(SEGS, "");
		builder.add(TYPE, "");
		builder.add(SCALE, 1f);
		builder.add(ROLL, 0f);
		builder.add(FLAGS, (byte) 0);
	}

	public void setSegments(List<String> segs) {
		this.segments = List.copyOf(segs);
		this.dataTracker.set(SEGS, String.join(",", segs));
		refreshDims();
	}

	public List<String> segments() {
		return segments;
	}

	public int victimId() {
		return dataTracker.get(VICTIM);
	}

	public Identifier victimType() {
		return Identifier.tryParse(dataTracker.get(TYPE));
	}

	public Anatomy anatomy() {
		Identifier t = victimType();
		return t == null ? null : AnatomyRegistry.get(t);
	}

	public float victimScale() {
		return dataTracker.get(SCALE);
	}

	public float roll(float tickDelta) {
		return dataTracker.get(ROLL);
	}

	public boolean locked() {
		return (dataTracker.get(FLAGS) & F_LOCKED) != 0;
	}

	public void setLocked(boolean on) {
		byte f = dataTracker.get(FLAGS);
		dataTracker.set(FLAGS, (byte) (on ? f | F_LOCKED : f & ~F_LOCKED));
	}

	/** La caja de sus partes, en píxeles del modelo. */
	public double[] modelBox() {
		Anatomy a = anatomy();
		return a == null ? null : a.union(segments);
	}

	private void refreshDims() {
		double[] b = modelBox();
		if (b == null) {
			dims = EntityDimensions.changing(0.4f, 0.4f);
		} else {
			double s = victimScale() / 16.0;
			double[] e = {(b[3] - b[0]) * s, (b[4] - b[1]) * s, (b[5] - b[2]) * s};
			Arrays.sort(e);
			// tumbada: lo más corto hacia arriba; de ancho, la media de los otros dos (gira mientras cae)
			float w = (float) MathHelper.clamp((e[1] + e[2]) / 2, 0.2, 3.0);
			float h = (float) MathHelper.clamp(Math.max(e[0], e[1] * 0.6), 0.15, 3.0);
			dims = EntityDimensions.changing(w, h);
		}
		calculateDimensions();
	}

	@Override
	public EntityDimensions getDimensions(EntityPose pose) {
		return dims == null ? super.getDimensions(pose) : dims;
	}

	@Override
	public void onTrackedDataSet(TrackedData<?> data) {
		super.onTrackedDataSet(data);
		if (SEGS.equals(data)) {
			String s = dataTracker.get(SEGS);
			segments = s.isEmpty() ? List.of() : List.of(s.split(","));
			refreshDims();
		} else if (TYPE.equals(data) || SCALE.equals(data)) {
			refreshDims();
		}
	}

	@Override
	protected void readCustomDataFromNbt(NbtCompound nbt) {
		if (nbt.containsUuid("victim")) victimUuid = nbt.getUuid("victim");
		if (nbt.containsUuid("cutter")) cutter = nbt.getUuid("cutter");
		dataTracker.set(TYPE, nbt.getString("type"));
		if (nbt.contains("scale")) dataTracker.set(SCALE, nbt.getFloat("scale"));
		String s = nbt.getString("segments");
		setSegments(s.isEmpty() ? List.of() : new ArrayList<>(List.of(s.split(","))));
		life = nbt.getInt("life");
	}

	@Override
	protected void writeCustomDataToNbt(NbtCompound nbt) {
		if (victimUuid != null) nbt.putUuid("victim", victimUuid);
		if (cutter != null) nbt.putUuid("cutter", cutter);
		nbt.putString("type", dataTracker.get(TYPE));
		nbt.putFloat("scale", dataTracker.get(SCALE));
		nbt.putString("segments", String.join(",", segments));
		nbt.putInt("life", life);
	}

	public Packet<ClientPlayPacketListener> createSpawnPacket(EntityTrackerEntry entry) {
		return new EntitySpawnS2CPacket(this, entry);
	}

	// ------------------------------------------------------------------ el dueño

	/** El dueño, si está cargado en este mundo. */
	public LivingEntity victim() {
		if (getWorld() instanceof ServerWorld sw) {
			return victimUuid != null && sw.getEntity(victimUuid) instanceof LivingEntity le ? le : null;
		}
		return getWorld().getEntityById(victimId()) instanceof LivingEntity le ? le : null;
	}

	/** La parte no tiene vida: el golpe se lo lleva el dueño (con sus propios tiempos de invulnerabilidad). */
	@Override
	public boolean damage(DamageSource source, float amount) {
		if (getWorld().isClient || isRemoved() || isInvulnerableTo(source)) return false;
		LivingEntity v = victim();
		if (v == null || !v.isAlive()) return false;
		if (source.getAttacker() == v) return false;   // un mob no se hace daño con su propia parte
		boolean hit = v.damage(source, amount * OpeConfig.PART_DMG_SHARE);
		if (hit) {
			Vec3d push = source.getPosition() == null ? Vec3d.ZERO : getPos().subtract(source.getPosition()).normalize().multiply(0.25);
			setVelocity(getVelocity().add(push.x, 0.15, push.z));
			velocityModified = true;
		}
		return hit;
	}

	@Override
	public boolean isAttackable() {
		return true;
	}

	@Override
	public boolean canHit() {
		return !isRemoved();
	}

	@Override
	public boolean isImmuneToExplosion(Explosion explosion) {
		return false;
	}

	@Override
	public boolean shouldRender(double distance) {
		return distance < 96 * 96;
	}

	// ------------------------------------------------------------------ tick

	@Override
	public void tick() {
		super.tick();
		if (getWorld().isClient) {
			clientTick();
			return;
		}
		if (!counted) {
			counted = true;
			LIVE++;
		}
		ServerWorld sw = (ServerWorld) getWorld();
		LivingEntity v = victim();
		if (v == null) {
			// el dueño no está cargado (u otra dimensión): quieta; si no vuelve, se va (el dueño la recupera solo)
			if (++orphanTicks > OpeConfig.PART_ORPHAN_TICKS) discard();
			return;
		}
		orphanTicks = 0;
		if (dataTracker.get(VICTIM) != v.getId()) dataTracker.set(VICTIM, v.getId());
		if (!v.isAlive()) {
			Dismember.vanishFx(sw, this);
			discard();
			return;
		}
		if (!Dismember.owns(v, this)) {
			// el dueño ya no la cuenta como suya (reunida por otro camino): sobra
			discard();
			return;
		}
		life++;
		if (age % 10 == 0) {
			boolean lk = Dismember.isLocked(v, this);
			if (lk != locked()) setLocked(lk);
		}
		if (OpeConfig.PART_LIFETIME > 0 && !(v instanceof PlayerEntity) && life > OpeConfig.PART_LIFETIME && !locked()) {
			Dismember.reattach(v, this, true);
			return;
		}
		physics();
		if (isOnFire() && age % 10 == 0) v.setOnFireFor(4f);
		if (isInLava()) {
			v.setOnFireFor(8f);
			setFireTicks(0);
		}
	}

	private void physics() {
		Vec3d vel = getVelocity();
		if (!hasNoGravity()) vel = vel.add(0, -0.04, 0);
		double preY = vel.y;
		move(MovementType.SELF, vel);
		vel = getVelocity();
		if (isOnGround()) {
			if (preY < -0.18) vel = new Vec3d(vel.x * 0.7, -preY * 0.3, vel.z * 0.7);   // rebote leve
			vel = new Vec3d(vel.x * 0.6, vel.y, vel.z * 0.6);
			spinYaw *= 0.6f;
			spinPitch *= 0.6f;
			spinRoll *= 0.6f;
		}
		setVelocity(vel.multiply(0.98));
		if (Math.abs(spinYaw) + Math.abs(spinPitch) + Math.abs(spinRoll) > 0.05f) {
			setYaw(getYaw() + spinYaw);
			setPitch(MathHelper.wrapDegrees(getPitch() + spinPitch));
			dataTracker.set(ROLL, MathHelper.wrapDegrees(dataTracker.get(ROLL) + spinRoll));
		}
	}

	private void clientTick() {
		// la posición la interpola Entity; nada más que hacer: la «vida» (temblor, parpadeo) la pone el renderer
	}

	/** Bajo el mundo: vuelve a su dueño en vez de perderse. */
	@Override
	protected void tickInVoid() {
		if (!getWorld().isClient) {
			LivingEntity v = victim();
			if (v != null && v.isAlive()) {
				Dismember.reattach(v, this, true);
				return;
			}
		}
		super.tickInVoid();
	}

	@Override
	public void remove(RemovalReason reason) {
		if (counted) {
			counted = false;
			LIVE = Math.max(0, LIVE - 1);
		}
		super.remove(reason);
	}

	public void kick(Vec3d vel) {
		setVelocity(vel);
		velocityModified = true;
	}
}
