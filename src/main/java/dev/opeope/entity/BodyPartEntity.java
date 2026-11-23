package dev.opeope.entity;

import dev.opeope.OpeConfig;
import dev.opeope.anatomy.Anatomy;
import dev.opeope.anatomy.AnatomyRegistry;
import dev.opeope.anatomy.CutOp;
import dev.opeope.anatomy.CutVolume;
import dev.opeope.anatomy.VolumeBounds;
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
import net.minecraft.particle.ParticleTypes;
import net.minecraft.server.network.EntityTrackerEntry;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
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
	/** Plano legado (píxeles del JSON, «nx,ny,nz,d»); sincronizado desde {@link #VOLUME} cuando hay un semiespacio. */
	private static final TrackedData<String> PLANE = DataTracker.registerData(BodyPartEntity.class, TrackedDataHandlerRegistry.STRING);
	/** Volumen CSG completo de esta pieza (vacío = parte entera por articulación). */
	private static final TrackedData<String> VOLUME = DataTracker.registerData(BodyPartEntity.class, TrackedDataHandlerRegistry.STRING);

	public static final int F_LOCKED = 1;   // Radio Knife: no se puede reunir aún (borde eléctrico)
	public static final int F_FROZEN = 2;   // Bisturí: congelada hasta soltar el clic derecho
	public static final int F_PINNED = 4;   // Takt: clavada viva en pared/bloque sólido

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

	private enum GaitMode { NONE, BALANCED_WALK, HALF_QUAD_LIMP, SIDE_OR_ONE_LEG_HOP, ARM_CRAWL }
	private GaitMode gait = GaitMode.NONE;
	private int gaitFrontLegs, gaitHindLegs, gaitLeftLegs, gaitRightLegs;

	// Bisturí diferido + Takt clavado
	private Vec3d frozenOffset;
	private Vec3d frozenKick;
	private BlockPos pinSupport;
	private boolean taktThrown;
	private long taktThrownUntil;

	private float[] planeCache;
	private CutVolume volumeCache;

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

	/** Una pieza de un corte libre por plano único (compatibilidad). */
	public static BodyPartEntity createSlice(ServerWorld w, LivingEntity victim, List<String> segs, float[] plane, UUID cutter) {
		CutVolume vol = plane == null || plane.length != 4 ? CutVolume.WHOLE
			: new CutVolume(List.of(new CutOp.HalfSpace(-plane[0], -plane[1], -plane[2], -plane[3])), List.of());
		return createVolumeSlice(w, victim, segs, vol, cutter);
	}

	/** Una pieza arbitraria definida por un {@link CutVolume} en píxeles de modelo. */
	public static BodyPartEntity createVolumeSlice(ServerWorld w, LivingEntity victim, List<String> segs, CutVolume vol, UUID cutter) {
		BodyPartEntity e = new BodyPartEntity(ModEntities.BODY_PART, w);
		e.victimUuid = victim.getUuid();
		e.cutter = cutter;
		e.dataTracker.set(TYPE, EntityType.getId(victim.getType()).toString());
		e.dataTracker.set(SCALE, victim.getScale() * (victim.isBaby() ? 0.5f : 1f));
		e.dataTracker.set(VICTIM, victim.getId());
		e.setCutVolume(vol);
		e.setSegments(segs);
		e.spinYaw = (w.random.nextFloat() - 0.5f) * 10f;
		e.setYaw(victim.getBodyYaw());
		return e;
	}

	public boolean isSlice() {
		return !cutVolume().isWhole() || !dataTracker.get(PLANE).isEmpty();
	}

	public CutVolume cutVolume() {
		if (volumeCache != null) return volumeCache;
		String raw = dataTracker.get(VOLUME);
		if (!raw.isEmpty()) {
			CutVolume dec = CutVolume.decodeString(raw);
			if (dec != null) {
				volumeCache = dec;
				return dec;
			}
		}
		float[] pl = plane();
		if (pl != null) {
			volumeCache = new CutVolume(List.of(new CutOp.HalfSpace(-pl[0], -pl[1], -pl[2], -pl[3])), List.of());
			return volumeCache;
		}
		volumeCache = CutVolume.WHOLE;
		return volumeCache;
	}

	public void setCutVolume(CutVolume vol) {
		volumeCache = vol == null ? CutVolume.WHOLE : vol;
		String enc = volumeCache.isWhole() ? "" : volumeCache.encodeString();
		dataTracker.set(VOLUME, enc);
		float[] fp = null;
		if (!volumeCache.isWhole()) {
			List<CutOp.HalfSpace> hs = volumeCache.halfSpaces();
			if (!hs.isEmpty()) {
				CutOp.HalfSpace first = hs.get(0);
				// En el formato legado de BodyPartEntity.PLANE, n·P > d correspondía a HalfSpace(-n, -d)
				fp = new float[]{-first.nx(), -first.ny(), -first.nz(), -first.d()};
			} else {
				fp = new float[]{0f, -1f, 0f, -12f};
			}
		}
		setPlane(fp);
		refreshDims();
	}

	/** El plano legado del corte libre (o null). */
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
		builder.add(VOLUME, "");
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
		LivingEntity v = victim();
		if (v != null) return AnatomyRegistry.getOrSynthetic(v);
		Identifier t = victimType();
		return t == null ? null : AnatomyRegistry.getOrSynthetic(t);
	}

	public float victimScale() {
		return dataTracker.get(SCALE);
	}

	public float roll(float tickDelta) {
		return dataTracker.get(ROLL);
	}

	private boolean hasPartFlag(int bit) {
		return (dataTracker.get(FLAGS) & bit) != 0;
	}

	private void setPartFlag(int bit, boolean on) {
		byte f = dataTracker.get(FLAGS);
		dataTracker.set(FLAGS, (byte) (on ? f | bit : f & ~bit));
	}

	public boolean locked() {
		return hasPartFlag(F_LOCKED);
	}

	public void setLocked(boolean on) {
		setPartFlag(F_LOCKED, on);
	}

	public boolean frozen() {
		return hasPartFlag(F_FROZEN);
	}

	public boolean pinned() {
		return hasPartFlag(F_PINNED);
	}

	public void setFrozen(boolean on, LivingEntity owner, Vec3d worldPos, Vec3d kickVel) {
		setPartFlag(F_FROZEN, on);
		if (on) {
			this.frozenOffset = owner != null ? worldPos.subtract(owner.getPos()) : Vec3d.ZERO;
			this.frozenKick = kickVel;
			this.spinYaw = this.spinPitch = this.spinRoll = 0f;
			setNoGravity(true);
			setVelocity(Vec3d.ZERO);
		} else {
			this.frozenOffset = null;
			this.frozenKick = null;
			setNoGravity(false);
		}
	}

	public void releaseFrozen() {
		if (!frozen()) return;
		Vec3d kickVel = this.frozenKick;
		setFrozen(false, null, getPos(), null);
		if (kickVel != null) kick(kickVel);
		else kick(new Vec3d(0, 0.14, 0));
	}

	public void pin(BlockPos support) {
		setPartFlag(F_PINNED, true);
		this.pinSupport = support == null ? null : support.toImmutable();
		this.spinYaw = this.spinPitch = this.spinRoll = 0f;
		this.taktThrown = false;
		setNoGravity(true);
		setVelocity(Vec3d.ZERO);
		velocityModified = true;
	}

	public void unpin() {
		if (!pinned()) return;
		setPartFlag(F_PINNED, false);
		this.pinSupport = null;
		setNoGravity(false);
	}

	public void markTaktThrown() {
		this.taktThrown = true;
		this.taktThrownUntil = (getWorld() != null ? getWorld().getTime() : 0L) + 60L;
	}

	public void stopSpin() {
		this.spinYaw = 0f;
		this.spinPitch = 0f;
		this.spinRoll = 0f;
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
		CutVolume vol = cutVolume();
		for (String sg : segments) {
			Anatomy.Segment x = a.get(sg);
			if (x != null && x.has("vision")) {
				if (vol.isWhole() || VolumeBounds.remainingFraction(x.box(), vol) >= 0.35) return true;
			}
		}
		return false;
	}

	/** Patas o extremidades con autonomía propia (mitad de abajo, mitad delantera/trasera, mitad lateral, pata suelta o torso con brazos). */
	public boolean autonomous() {
		return auto && OpeConfig.LOWER_HALF_WANDERS && !mounted() && !pinned() && !frozen();
	}

	private boolean computeAuto() {
		gait = GaitMode.NONE;
		gaitFrontLegs = 0;
		gaitHindLegs = 0;
		gaitLeftLegs = 0;
		gaitRightLegs = 0;
		Anatomy a = anatomy();
		if (a == null) return false;
		CutVolume vol = cutVolume();
		Anatomy.Segment core = a.get(a.core);
		Vec3d coreC = core != null ? core.center() : Vec3d.ZERO;
		int legs = 0;
		boolean hasCore = false, hasArms = false;
		for (String sg : segments) {
			Anatomy.Segment x = a.get(sg);
			if (x == null) continue;
			double frac = vol.isWhole() ? 1.0 : VolumeBounds.remainingFraction(x.box(), vol);
			if (frac < 0.30) continue;
			if (sg.equals(a.core) && (!vol.isWhole() || isSlice() || segments.size() > 1)) hasCore = true;
			if (x.has("main_hand") || x.has("off_hand")) hasArms = true;
			if (x.has("locomotion")) {
				legs++;
				if (x.id().contains("left") || x.center().x > coreC.x + 0.5) gaitLeftLegs++;
				else if (x.id().contains("right") || x.center().x < coreC.x - 0.5) gaitRightLegs++;
				if (x.id().contains("front") || x.center().z < coreC.z - 0.5) gaitFrontLegs++;
				else if (x.id().contains("hind") || x.center().z > coreC.z + 0.5) gaitHindLegs++;
			}
		}
		if (legs >= 2) {
			if (a.legs >= 4 && (gaitLeftLegs == 0 || gaitRightLegs == 0)) gait = GaitMode.SIDE_OR_ONE_LEG_HOP;
			else if (a.legs >= 4 && legs < a.legs) gait = GaitMode.HALF_QUAD_LIMP;
			else gait = GaitMode.BALANCED_WALK;
			return true;
		}
		if (legs == 1) {
			gait = GaitMode.SIDE_OR_ONE_LEG_HOP;
			return true;
		}
		if (hasCore && hasArms) {
			gait = GaitMode.ARM_CRAWL;
			return true;
		}
		return false;
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
		unpin();
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

	/** La caja de sus partes, en píxeles del modelo (recortada según {@link #cutVolume()}). */
	public double[] modelBox() {
		Anatomy a = anatomy();
		if (a == null) return null;
		CutVolume vol = cutVolume();
		if (!vol.isWhole()) {
			double[] out = VolumeBounds.unionClippedBox(a, segments, vol);
			return out == null ? a.union(segments) : out;
		}
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
		} else if (VOLUME.equals(data)) {
			volumeCache = null;
			refreshDims();
		}
	}

	@Override
	protected void readCustomDataFromNbt(NbtCompound nbt) {
		if (nbt.containsUuid("victim")) victimUuid = nbt.getUuid("victim");
		if (nbt.containsUuid("cutter")) cutter = nbt.getUuid("cutter");
		dataTracker.set(TYPE, nbt.getString("type"));
		if (nbt.contains("plane")) setPlane(toPlane(nbt.getString("plane")));
		if (nbt.contains("volume")) {
			CutVolume v = CutVolume.decodeString(nbt.getString("volume"));
			if (v != null && !v.isWhole()) setCutVolume(v);
		}
		if (nbt.contains("scale")) dataTracker.set(SCALE, nbt.getFloat("scale"));
		String s = nbt.getString("segments");
		setSegments(s.isEmpty() ? List.of() : new ArrayList<>(List.of(s.split(","))));
		life = nbt.getInt("life");
		if (nbt.containsUuid("host")) {
			hostUuid = nbt.getUuid("host");
			dataTracker.set(HOST_SEG, nbt.getString("host_seg"));
			setNoGravity(true);
		}
		if (nbt.getBoolean("pinned") && nbt.contains("pin_support")) {
			pin(BlockPos.fromLong(nbt.getLong("pin_support")));
		}
	}

	@Override
	protected void writeCustomDataToNbt(NbtCompound nbt) {
		if (victimUuid != null) nbt.putUuid("victim", victimUuid);
		if (cutter != null) nbt.putUuid("cutter", cutter);
		nbt.putString("type", dataTracker.get(TYPE));
		if (!cutVolume().isWhole()) nbt.putString("volume", cutVolume().encodeString());
		else if (!dataTracker.get(PLANE).isEmpty()) nbt.putString("plane", dataTracker.get(PLANE));
		nbt.putFloat("scale", dataTracker.get(SCALE));
		nbt.putString("segments", String.join(",", segments));
		nbt.putInt("life", life);
		if (hostUuid != null) {
			nbt.putUuid("host", hostUuid);
			nbt.putString("host_seg", hostSegment());
		}
		if (pinned() && pinSupport != null) {
			nbt.putBoolean("pinned", true);
			nbt.putLong("pin_support", pinSupport.asLong());
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
			if (pinned()) unpin();
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
		if (frozen()) {
			if (held) {
				setFrozen(false, null, getPos(), null);
			} else {
				if (frozenOffset != null) {
					Vec3d targetPos = v.getPos().add(frozenOffset);
					setPosition(targetPos.x, targetPos.y, targetPos.z);
				}
				setYaw(v.getBodyYaw());
				setVelocity(Vec3d.ZERO);
				fallDistance = 0;
				if (age % 4 == 0) {
					Vec3d cc = getBoundingBox().getCenter();
					sw.spawnParticles(CutEngine.ROOM_DUST, cc.x, cc.y, cc.z, 2, 0.15, 0.15, 0.15, 0);
				}
				return;
			}
		}
		if (pinned()) {
			if (held || (pinSupport != null && sw.getBlockState(pinSupport).isAir())) {
				unpin();
			} else {
				setVelocity(Vec3d.ZERO);
				fallDistance = 0;
				if (hasVision()) headLife(sw, v);
				if (isOnFire() && age % 10 == 0) v.setOnFireFor(4f);
				return;
			}
		}
		// Takt la deja sin gravedad al cogerla; al soltarla (o lanzarla) vuelve a caer
		if (!held && hasNoGravity()) setNoGravity(false);
		boolean reclaimingTouch = OpeConfig.MOBS_RECLAIM_PARTS
			&& v instanceof net.minecraft.entity.mob.MobEntity mb
			&& !mb.isAiDisabled()
			&& mb.getBoundingBox().expand(0.8).intersects(getBoundingBox());
		if (!held && autonomous()) {
			if (life >= 12 && returning <= 0 && !reclaimingTouch) walk(sw);
			if (hasVision() && --soundCd <= 0 && v instanceof net.minecraft.entity.mob.MobEntity mob) {
				soundCd = 140 + random.nextInt(220);
				net.minecraft.sound.SoundEvent snd = ((dev.opeope.mixin.MobEntityInvoker) mob).opeope$ambientSound();
				if (snd != null && !mob.isSilent()) sw.playSound(null, getX(), getY(), getZ(), snd, mob.getSoundCategory(), 0.7f, 1.35f + random.nextFloat() * 0.2f);
			}
		} else if (!held && hasVision()) {
			headLife(sw, v);
		}
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
		if (pinned() || frozen()) return;
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

	/** Cada parte cortada se mueve según sus extremidades: marcha recta, cojera de medio cuerpo, salto lateral o arrastre con brazos. */
	private void walk(ServerWorld sw) {
		if (!isOnGround()) return;
		spinYaw = spinPitch = spinRoll = 0;
		if (gait == GaitMode.NONE) return;
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
		float scale = Math.max(0.5f, victimScale());
		double r = walkYaw * (Math.PI / 180);
		double dirX = -Math.sin(r), dirZ = Math.cos(r);
		setYaw(walkYaw);

		switch (gait) {
			case BALANCED_WALK -> {
				setPitch(0f);
				dataTracker.set(ROLL, 0f);
				double sp = 0.12 * scale;
				setVelocity(dirX * sp, vel.y, dirZ * sp);
			}
			case HALF_QUAD_LIMP -> {
				float basePitch = gaitFrontLegs > gaitHindLegs ? 16f : -16f;
				setPitch(basePitch + 12f * MathHelper.sin(age * 0.55f));
				dataTracker.set(ROLL, 9f * MathHelper.cos(age * 0.55f));
				double sp = 0.095 * scale;
				double vy = (age % 10 == 0) ? Math.max(vel.y, 0.12) : vel.y;
				setVelocity(dirX * sp, vy, dirZ * sp);
			}
			case SIDE_OR_ONE_LEG_HOP -> {
				float sideSign = (gaitRightLegs > gaitLeftLegs) ? 1f : -1f;
				setPitch(6f * MathHelper.sin(age * 0.45f));
				dataTracker.set(ROLL, sideSign * (68f + 14f * MathHelper.sin(age * 0.4f)));
				if (age % 13 == 0) {
					double sp = 0.135 * scale;
					setVelocity(dirX * sp, Math.max(vel.y, 0.22), dirZ * sp);
				} else {
					setVelocity(vel.x * 0.8, vel.y, vel.z * 0.8);
				}
			}
			case ARM_CRAWL -> {
				setPitch(55f + 8f * MathHelper.sin(age * 0.35f));
				dataTracker.set(ROLL, 6f * MathHelper.cos(age * 0.35f));
				if (age % 14 == 0) {
					double sp = 0.055 * scale;
					setVelocity(dirX * sp, Math.max(vel.y, 0.08), dirZ * sp);
				} else {
					setVelocity(vel.x * 0.75, vel.y, vel.z * 0.75);
				}
			}
			default -> {}
		}
	}

	/** Una cabeza suelta sigue viva: en el suelo se pone derecha, mira (al jugador más cercano, o a donde mira su dueño si es un jugador) y a veces habla o da un saltito. */
	private void headLife(ServerWorld sw, LivingEntity v) {
		if (!isOnGround() && !pinned()) return;
		spinYaw = spinPitch = spinRoll = 0;
		dataTracker.set(ROLL, MathHelper.lerp(0.3f, dataTracker.get(ROLL), 0f));
		if (v instanceof PlayerEntity pl) {
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
			if (isOnGround() && !pinned() && near.squaredDistanceTo(this) < 16.0 && age % 50 == 0) {
				Vec3d vel = getVelocity();
				setVelocity(vel.x, Math.max(vel.y, 0.13), vel.z);
				velocityModified = true;
			}
		} else if (age % 40 == 0) {
			setYaw(getYaw() + (random.nextFloat() - 0.5f) * 60f);
		}
		if (--soundCd <= 0 && v instanceof net.minecraft.entity.mob.MobEntity mob) {
			soundCd = 140 + random.nextInt(220);
			net.minecraft.sound.SoundEvent snd = ((dev.opeope.mixin.MobEntityInvoker) mob).opeope$ambientSound();
			if (snd != null && !mob.isSilent()) sw.playSound(null, getX(), getY(), getZ(), snd, mob.getSoundCategory(), 0.7f, 1.35f + random.nextFloat() * 0.2f);
		}
	}

	/** Lanzada (Takt) a toda velocidad: golpea a quien pilla, o se clava en la pared/bloque dentro del ROOM. */
	private void impact(ServerWorld sw, LivingEntity v) {
		Vec3d vel = getVelocity();
		double speed = vel.length();
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
				Vec3d push = vel.normalize().multiply(0.5);
				t.addVelocity(push.x, 0.2, push.z);
				t.velocityModified = true;
			}
		}
		if (!hitAny && taktThrown && sw.getTime() <= taktThrownUntil && (horizontalCollision || verticalCollision) && speed >= 0.72) {
			BlockPos support = findSupportBlock(sw, vel);
			if (support != null) {
				pin(support);
				Vec3d c = getBoundingBox().getCenter();
				sw.spawnParticles(ParticleTypes.END_ROD, c.x, c.y, c.z, 8, 0.15, 0.15, 0.15, 0.04);
				sw.spawnParticles(CutEngine.ROOM_DUST, c.x, c.y, c.z, 10, 0.2, 0.2, 0.2, 0);
				sw.playSound(null, c.x, c.y, c.z, SoundEvents.BLOCK_AMETHYST_BLOCK_HIT, SoundCategory.PLAYERS, 1f, 1.35f);
				return;
			}
		}
		if (hitAny || horizontalCollision) {
			v.damage(sw.getDamageSources().flyIntoWall(), dmg * OpeConfig.PART_DMG_SHARE * (hitAny ? 0.5f : 0.25f));
			setVelocity(vel.multiply(-0.3));
			velocityModified = true;
			impactCd = 10;
		}
	}

	private BlockPos findSupportBlock(ServerWorld sw, Vec3d dir) {
		Box b = getBoundingBox().expand(0.28);
		Vec3d c = b.getCenter();
		if (dir.lengthSquared() > 1e-4) {
			Vec3d probe = c.add(dir.normalize().multiply(Math.max(0.35, b.getAverageSideLength() * 0.55)));
			BlockPos bp = BlockPos.ofFloored(probe);
			if (!sw.getBlockState(bp).isAir()) return bp;
		}
		BlockPos.Mutable m = new BlockPos.Mutable();
		for (int x = MathHelper.floor(b.minX); x <= MathHelper.floor(b.maxX); x++) {
			for (int y = MathHelper.floor(b.minY); y <= MathHelper.floor(b.maxY); y++) {
				for (int z = MathHelper.floor(b.minZ); z <= MathHelper.floor(b.maxZ); z++) {
					m.set(x, y, z);
					if (!sw.getBlockState(m).isAir()) return m.toImmutable();
				}
			}
		}
		return null;
	}

	private void clientTick() {
		// la posición la interpola Entity; la «vida» y congelado/clavado los dibuja BodyPartRenderer
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
