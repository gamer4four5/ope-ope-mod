package dev.opeope.entity;

import dev.opeope.OpeConfig;
import dev.opeope.anatomy.Anatomy;
import dev.opeope.anatomy.AnatomyRegistry;
import dev.opeope.power.CutEngine;
import dev.opeope.power.Dismember;
import dev.opeope.power.Takt;
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
	private static final TrackedData<Integer> HOST = DataTracker.registerData(BodyPartEntity.class, TrackedDataHandlerRegistry.INTEGER);
	private static final TrackedData<String> HOST_SEG = DataTracker.registerData(BodyPartEntity.class, TrackedDataHandlerRegistry.STRING);
	/** Corte libre: el plano (píxeles del JSON, «nx,ny,nz,d»); esta pieza es lo que queda del lado n·P &gt; d. */
	private static final TrackedData<String> PLANE = DataTracker.registerData(BodyPartEntity.class, TrackedDataHandlerRegistry.STRING);

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
	/** Quimera: el ser en el que está puesta (servidor) y en qué sitio. */
	public UUID hostUuid;
	private float walkYaw;
	private int returning;
	private Vec3d returnTo;
	private int turnTicks, impactCd, soundCd = 80;
	private boolean auto;

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

	/** Una pieza de un corte libre: las partes del otro lado del plano y la otra mitad de las atravesadas. */
	public static BodyPartEntity createSlice(ServerWorld w, LivingEntity victim, List<String> segs, float[] plane, UUID cutter) {
		BodyPartEntity e = new BodyPartEntity(ModEntities.BODY_PART, w);
		e.victimUuid = victim.getUuid();
		e.cutter = cutter;
		e.dataTracker.set(TYPE, EntityType.getId(victim.getType()).toString());
		e.dataTracker.set(SCALE, victim.getScale() * (victim.isBaby() ? 0.5f : 1f));
		e.dataTracker.set(VICTIM, victim.getId());
		e.setPlane(plane);
		e.setSegments(segs);
		e.spinYaw = (w.random.nextFloat() - 0.5f) * 10f;
		e.setYaw(victim.getBodyYaw());
		return e;
	}

	private float[] planeCache;

	public boolean isSlice() {
		return !dataTracker.get(PLANE).isEmpty();
	}

	/** El plano del corte libre (o null). */
	public float[] plane() {
		if (planeCache != null) return planeCache;
		String s = dataTracker.get(PLANE);
		if (s.isEmpty()) return null;
		String[] f = s.split(",");
		if (f.length != 4) return null;
		try {
			planeCache = new float[]{Float.parseFloat(f[0]), Float.parseFloat(f[1]), Float.parseFloat(f[2]), Float.parseFloat(f[3])};
		} catch (NumberFormatException ex) {
			return null;
		}
		return planeCache;
	}

	public void setPlane(float[] p) {
		planeCache = null;
		dataTracker.set(PLANE, p == null ? "" : p[0] + "," + p[1] + "," + p[2] + "," + p[3]);
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
		builder.add(HOST, -1);
		builder.add(HOST_SEG, "");
		builder.add(PLANE, "");
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

	/** La parte de arriba del grupo (la que se corta de su padre): la cabeza, el brazo… null si hay varias. */
	public String rootSegment() {
		Anatomy a = anatomy();
		if (a == null) return null;
		String root = null;
		for (String sg : segments) {
			Anatomy.Segment x = a.get(sg);
			if (x == null) continue;
			if (x.parent() == null || !segments.contains(x.parent())) {
				if (root != null) return null;
				root = sg;
			}
		}
		return root;
	}

	/** ¿Lleva algo que ve (una cabeza)? */
	public boolean hasVision() {
		Anatomy a = anatomy();
		if (a == null) return false;
		for (String sg : segments) {
			Anatomy.Segment x = a.get(sg);
			if (x != null && x.has("vision")) return true;
		}
		return false;
	}

	/** Patas sueltas que andan solas (la mitad de abajo, o dos patas de un cuadrúpedo): sin cabeza y con al menos dos patas. */
	public boolean autonomous() {
		return auto && OpeConfig.LOWER_HALF_WANDERS && !mounted();
	}

	private boolean computeAuto() {
		Anatomy a = anatomy();
		if (a == null) return false;
		int legs = 0;
		for (String sg : segments) {
			Anatomy.Segment x = a.get(sg);
			if (x == null) continue;
			if (x.has("vision") || (sg.equals(a.core) && !isSlice())) return false;
			if (x.has("locomotion")) legs++;
		}
		return legs >= 2;
	}

	// ------------------------------------------------------------------ quimera: puesta en otro ser

	public boolean mounted() {
		return dataTracker.get(HOST) >= 0 || hostUuid != null;
	}

	public String hostSegment() {
		return dataTracker.get(HOST_SEG);
	}

	public int hostId() {
		return dataTracker.get(HOST);
	}

	public LivingEntity host() {
		if (getWorld() instanceof ServerWorld sw) return hostUuid != null && sw.getEntity(hostUuid) instanceof LivingEntity le ? le : null;
		int id = dataTracker.get(HOST);
		return id >= 0 && getWorld().getEntityById(id) instanceof LivingEntity le ? le : null;
	}

	public void mount(LivingEntity host, String seg) {
		hostUuid = host.getUuid();
		dataTracker.set(HOST, host.getId());
		dataTracker.set(HOST_SEG, seg);
		setVelocity(Vec3d.ZERO);
		spinYaw = spinPitch = spinRoll = 0;
		dataTracker.set(ROLL, 0f);
		setPitch(0);
		setNoGravity(true);
		calculateDimensions();
	}

	/** Se suelta del anfitrión (y este deja de contarla). */
	public void unmount() {
		LivingEntity h = host();
		unmountQuiet();
		if (h != null) Dismember.releaseForeign(h, this);
	}

	/** Se suelta sin avisar al anfitrión (quien llama ya lo ha quitado de su estado). */
	public void unmountQuiet() {
		hostUuid = null;
		dataTracker.set(HOST, -1);
		dataTracker.set(HOST_SEG, "");
		setNoGravity(false);
		kick(new Vec3d((random.nextDouble() - 0.5) * 0.2, 0.25, (random.nextDouble() - 0.5) * 0.2));
		spinYaw = (random.nextFloat() - 0.5f) * 30f;
		spinPitch = (random.nextFloat() - 0.5f) * 30f;
	}

	/** La caja de sus partes, en píxeles del modelo (en una pieza de corte libre, solo lo de su lado del plano). */
	public double[] modelBox() {
		Anatomy a = anatomy();
		if (a == null) return null;
		float[] pl = plane();
		if (pl == null) return a.union(segments);
		double[] out = null;
		for (String sg : segments) {
			Anatomy.Segment x = a.get(sg);
			if (x == null) continue;
			double[] c = Anatomy.clipBox(x.box(), pl, true);
			if (c == null) continue;
			if (out == null) out = c.clone();
			else for (int k = 0; k < 3; k++) {
				out[k] = Math.min(out[k], c[k]);
				out[k + 3] = Math.max(out[k + 3], c[k + 3]);
			}
		}
		return out == null ? a.union(segments) : out;
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
			auto = computeAuto();
			if (auto) {
				// de pie, como cuando estaban puestas: alto lo que miden de alto
				w = (float) MathHelper.clamp(Math.max(b[3] - b[0], b[5] - b[2]) * s, 0.2, 3.0);
				h = (float) MathHelper.clamp((b[4] - b[1]) * s, 0.2, 3.0);
			}
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
		} else if (PLANE.equals(data)) {
			planeCache = null;
			refreshDims();
		}
	}

	@Override
	protected void readCustomDataFromNbt(NbtCompound nbt) {
		if (nbt.containsUuid("victim")) victimUuid = nbt.getUuid("victim");
		if (nbt.containsUuid("cutter")) cutter = nbt.getUuid("cutter");
		dataTracker.set(TYPE, nbt.getString("type"));
		if (nbt.contains("plane")) setPlane(toPlane(nbt.getString("plane")));
		if (nbt.contains("scale")) dataTracker.set(SCALE, nbt.getFloat("scale"));
		String s = nbt.getString("segments");
		setSegments(s.isEmpty() ? List.of() : new ArrayList<>(List.of(s.split(","))));
		life = nbt.getInt("life");
		if (nbt.containsUuid("host")) {
			hostUuid = nbt.getUuid("host");
			dataTracker.set(HOST_SEG, nbt.getString("host_seg"));
			setNoGravity(true);
		}
	}

	@Override
	protected void writeCustomDataToNbt(NbtCompound nbt) {
		if (victimUuid != null) nbt.putUuid("victim", victimUuid);
		if (cutter != null) nbt.putUuid("cutter", cutter);
		nbt.putString("type", dataTracker.get(TYPE));
		if (isSlice()) nbt.putString("plane", dataTracker.get(PLANE));
		nbt.putFloat("scale", dataTracker.get(SCALE));
		nbt.putString("segments", String.join(",", segments));
		nbt.putInt("life", life);
		if (hostUuid != null) {
			nbt.putUuid("host", hostUuid);
			nbt.putString("host_seg", hostSegment());
		}
	}

	private static float[] toPlane(String s) {
		String[] f = s.split(",");
		if (f.length != 4) return null;
		try {
			return new float[]{Float.parseFloat(f[0]), Float.parseFloat(f[1]), Float.parseFloat(f[2]), Float.parseFloat(f[3])};
		} catch (NumberFormatException ex) {
			return null;
		}
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
		if (hostUuid != null) {
			tickMounted();
			return;
		}
		if (OpeConfig.PART_LIFETIME > 0 && !(v instanceof PlayerEntity) && life > OpeConfig.PART_LIFETIME && !locked()) {
			Dismember.reattach(v, this, true);
			return;
		}
		boolean held = Takt.held(getId());
		// Takt la deja sin gravedad al cogerla; al soltarla (o lanzarla) vuelve a caer
		if (!held && hasNoGravity()) setNoGravity(false);
		if (!held && autonomous()) walk(sw);
		else if (!held && hasVision()) headLife(sw, v);
		physics();
		if (!held) impact(sw, v);
		if (impactCd > 0) impactCd--;
		if (isOnFire() && age % 10 == 0) v.setOnFireFor(4f);
		if (isInLava()) {
			v.setOnFireFor(8f);
			setFireTicks(0);
		}
	}

	/** Durante unos ticks va derecha (flotando) hacia ese punto: su dueño la llama y no llega. */
	public void pullTo(Vec3d to) {
		returnTo = to;
		returning = 4;
	}

	/** Está volviendo sola a su dueño (la Gravedad Cero del ROOM no la sujeta). */
	public boolean returning() {
		return returning > 0;
	}

	private void physics() {
		Vec3d vel = getVelocity();
		if (returning > 0 && returnTo != null) {
			returning--;
			Vec3d d = returnTo.subtract(getBoundingBox().getCenter());
			Vec3d want = d.lengthSquared() < 1e-4 ? Vec3d.ZERO : d.normalize().multiply(Math.min(0.22, d.length()));
			vel = vel.multiply(0.5).add(want.multiply(0.5));
			noClip = true;   // atraviesa lo que haya en medio (como los hilos de la ROOM)
			move(MovementType.SELF, vel);
			noClip = false;
			setVelocity(vel);
			if (age % 4 == 0 && getWorld() instanceof ServerWorld sw)
				sw.spawnParticles(CutEngine.ROOM_DUST, getX(), getY() + getHeight() / 2, getZ(), 1, 0.1, 0.1, 0.1, 0);
			return;
		}
		if (!hasNoGravity()) vel = vel.add(0, -0.04, 0);
		double preY = vel.y;
		// la velocidad (con la gravedad) va al ser antes de moverse: move() solo la corta al chocar
		setVelocity(vel);
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

	/** Puesta en otro ser: va con él, en su sitio, mirando a donde mira su cuerpo. */
	private void tickMounted() {
		LivingEntity h = host();
		if (h == null) {
			// el anfitrión no está cargado: se queda quieta; si no vuelve, se suelta
			if (++orphanTicks > OpeConfig.PART_ORPHAN_TICKS) unmountQuiet();
			return;
		}
		if (!h.isAlive() || !Dismember.hosts(h, this)) {
			unmountQuiet();
			return;
		}
		if (dataTracker.get(HOST) != h.getId()) dataTracker.set(HOST, h.getId());
		Vec3d c = Dismember.slotCenter(h, hostSegment());
		setPosition(c.x, c.y - getHeight() / 2, c.z);
		setVelocity(Vec3d.ZERO);
		setYaw(h.getBodyYaw());
		setPitch(0);
		fallDistance = 0;
	}

	/** Las piernas sueltas andan solas: dan vueltas, saltan lo que les corta el paso y huyen de quien las cortó. */
	private void walk(ServerWorld sw) {
		if (!isOnGround()) return;
		spinYaw = spinPitch = spinRoll = 0;
		setPitch(0);
		dataTracker.set(ROLL, 0f);
		if (--turnTicks <= 0) {
			walkYaw += (random.nextFloat() - 0.5f) * 140f;
			turnTicks = 30 + random.nextInt(50);
		}
		PlayerEntity c = cutter == null ? null : sw.getPlayerByUuid(cutter);
		if (c != null && c.squaredDistanceTo(this) < 8 * 8) {
			Vec3d away = getPos().subtract(c.getPos());
			if (away.horizontalLengthSquared() > 1e-4) walkYaw = (float) (MathHelper.atan2(away.z, away.x) * (180 / Math.PI)) - 90f;
		}
		Vec3d vel = getVelocity();
		if (horizontalCollision) {
			if (random.nextInt(3) > 0) vel = new Vec3d(vel.x, 0.42, vel.z);
			else walkYaw += 150f;
		}
		double sp = 0.12 * Math.max(0.5, victimScale());
		double r = walkYaw * (Math.PI / 180);
		setVelocity(-Math.sin(r) * sp, vel.y, Math.cos(r) * sp);
		setYaw(walkYaw);
	}

	/** Una cabeza suelta sigue viva: en el suelo se pone derecha, mira (al jugador más cercano, o a donde mira su dueño si es un jugador) y a veces habla. */
	private void headLife(ServerWorld sw, LivingEntity v) {
		if (!isOnGround()) return;
		spinYaw = spinPitch = spinRoll = 0;
		dataTracker.set(ROLL, MathHelper.lerp(0.3f, dataTracker.get(ROLL), 0f));
		if (v instanceof PlayerEntity pl) {
			// la cámara del jugador sin cabeza está aquí: mira a donde él mueve el ratón
			setYaw(pl.getHeadYaw());
			setPitch(pl.getPitch());
			return;
		}
		setPitch(MathHelper.lerp(0.3f, getPitch(), 0f));
		PlayerEntity near = sw.getClosestPlayer(this, 12);
		if (near != null) {
			Vec3d d = near.getEyePos().subtract(getPos());
			float want = (float) (MathHelper.atan2(d.z, d.x) * (180 / Math.PI)) - 90f;
			setYaw(getYaw() + MathHelper.clamp(MathHelper.wrapDegrees(want - getYaw()), -12f, 12f));
		} else if (age % 40 == 0) {
			setYaw(getYaw() + (random.nextFloat() - 0.5f) * 60f);
		}
		if (--soundCd <= 0 && v instanceof net.minecraft.entity.mob.MobEntity mob) {
			soundCd = 140 + random.nextInt(220);
			net.minecraft.sound.SoundEvent snd = ((dev.opeope.mixin.MobEntityInvoker) mob).opeope$ambientSound();
			if (snd != null) sw.playSound(null, getX(), getY(), getZ(), snd, mob.getSoundCategory(), 0.7f, 1.35f + random.nextFloat() * 0.2f);
		}
	}

	/** Lanzada (Takt) a toda velocidad: golpea a quien pilla, y a su dueño también le duele (la mitad). */
	private void impact(ServerWorld sw, LivingEntity v) {
		double speed = getVelocity().length();
		if (speed < 0.6 || impactCd > 0) return;
		Anatomy a = anatomy();
		double mass = 0;
		if (a != null) for (String sg : segments) if (a.get(sg) != null) mass += a.get(sg).mass();
		float dmg = (float) MathHelper.clamp(speed * OpeConfig.PART_IMPACT_DMG * (0.6 + mass) * victimScale(), 1, 14);
		Entity by = cutter == null ? null : sw.getEntity(cutter);
		boolean hitAny = false;
		for (LivingEntity t : sw.getEntitiesByClass(LivingEntity.class, getBoundingBox().expand(0.2),
			e -> e != v && e != by && e.isAlive() && !e.isSpectator())) {
			if (t.damage(sw.getDamageSources().thrown(this, by), dmg)) {
				hitAny = true;
				Vec3d push = getVelocity().normalize().multiply(0.5);
				t.addVelocity(push.x, 0.2, push.z);
				t.velocityModified = true;
			}
		}
		if (hitAny || horizontalCollision) {
			v.damage(sw.getDamageSources().flyIntoWall(), dmg * OpeConfig.PART_DMG_SHARE * (hitAny ? 0.5f : 0.25f));
			setVelocity(getVelocity().multiply(-0.3));
			velocityModified = true;
			impactCd = 10;
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
