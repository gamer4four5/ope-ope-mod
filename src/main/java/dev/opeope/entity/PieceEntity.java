package dev.opeope.entity;

import dev.opeope.OpeConfig;
import dev.opeope.OpeOpeMod;
import dev.opeope.net.Payloads;
import dev.opeope.power.CutEngine;
import dev.opeope.power.ElementEffects;
import dev.opeope.power.PowerManager;
import dev.opeope.power.PlayerPower;
import dev.opeope.power.Room;
import dev.opeope.power.RoomEvents;
import dev.opeope.power.Takt;
import dev.opeope.registry.ModEffects;
import dev.opeope.registry.ModEntities;
import dev.opeope.terrain.GroundRules;
import dev.opeope.terrain.Landing;
import dev.opeope.terrain.MaterialTrait;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityDimensions;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.FallingBlockEntity;
import net.minecraft.entity.ItemEntity;
import net.minecraft.registry.tag.BlockTags;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.MovementType;
import net.minecraft.entity.damage.DamageSource;
import net.minecraft.entity.data.DataTracker;
import net.minecraft.entity.data.TrackedData;
import net.minecraft.entity.data.TrackedDataHandlerRegistry;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.entity.projectile.ProjectileUtil;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.network.listener.ClientPlayPacketListener;
import net.minecraft.network.packet.Packet;
import net.minecraft.network.packet.s2c.play.EntitySpawnS2CPacket;
import net.minecraft.particle.BlockStateParticleEffect;
import net.minecraft.particle.ParticleEffect;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.server.network.EntityTrackerEntry;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.EntityHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.BlockView;
import net.minecraft.world.RaycastContext;
import net.minecraft.world.World;
import net.minecraft.world.explosion.Explosion;
import net.minecraft.world.explosion.ExplosionBehavior;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Un trozo de mundo cortado: lo que separa Amputate, lo que arranca Takt y las armas que se forjan con ello.
 * Cae, choca, se lanza, se posa, se cose (vuelve a ser bloques) y vuelve a su sitio con la Unión.
 */
public class PieceEntity extends Entity {
	private static final TrackedData<NbtCompound> BLOCKS = DataTracker.registerData(PieceEntity.class, TrackedDataHandlerRegistry.NBT_COMPOUND);
	private static final TrackedData<Byte> FLAGS = DataTracker.registerData(PieceEntity.class, TrackedDataHandlerRegistry.BYTE);
	private static final TrackedData<Long> FX_TIME = DataTracker.registerData(PieceEntity.class, TrackedDataHandlerRegistry.LONG);
	private static final TrackedData<Byte> TRAIT = DataTracker.registerData(PieceEntity.class, TrackedDataHandlerRegistry.BYTE);
	private static final TrackedData<Boolean> GRAVITY_SURF = DataTracker.registerData(PieceEntity.class, TrackedDataHandlerRegistry.BOOLEAN);

	/** Gancho solo-cliente para pilotar la roca flotante de Fujitora sin lag ni vuelo creativo. */
	@Nullable public static Consumer<PieceEntity> CLIENT_SURF_TICK;

	public static final int F_WEAPON = 1;     // pieza girada (lanza, hoja, martillo, estaca): caja pequeña en el centro
	public static final int F_SUSPENDED = 2;  // suspendida tras un corte
	public static final int F_SEAM = 4;       // brilla: elegida para coser o volviendo a su sitio
	public static final int F_HELD = 8;       // la lleva el Takt de alguien (brillo del borde)
	public static final int F_TEAR = 16;      // arrancándose: quieta, el cliente la hace temblar
	public static final int F_FRESH = 32;     // sus caras cortadas brillan (desde fxTime); se apaga pasados TAKT_FRESH_TICKS
	public static final int F_LANDED = 64;    // posándose o ya posada: no se coge, sin borde, se dibuja a 0,998
	public static final int F_CHUNK = 128;    // arrancada del suelo con Takt

	/** SLIDING: resbala por el plano del corte. HOVER: queda separada un momento. LANDING: se posa. CHIMERA: parte cosida. SURF: roca voladora que transporta a Fujitora. */
	public enum Mode { FREE, CONTROLLED, RETURNING, SEWING, STUCK, SLIDING, HOVER, LANDING, CHIMERA, SURF }

	public enum Weapon { LANCE, BLADE, HAMMER, STAKE, WALL }

	/** Qué muelle la lleva (lo pone Takt.tick): en la mano, en órbita o aparcada. */
	public static final int DRIVE_HAND = 0, DRIVE_ORBIT = 1, DRIVE_PARK = 2;

	/** Solo en el hilo del servidor, desde el arranque: bloques que place() soltó como objeto, puso en otra celda o fracturó (AutoTest). */
	public static int DROPPED, RELOCATED, SHATTERED;

	public BlockSet blocks = new BlockSet();
	public Mode mode = Mode.FREE;
	public Vec3d target;
	public BlockPos origin;
	public UUID owner;
	public boolean thrown;
	public int thrownTicks;
	public int ownerGraceTicks;
	public boolean slowFall;
	public boolean sewOnContact;
	public boolean solid;

	public Vec3d targetVel = Vec3d.ZERO;   // velocidad del sitio que sigue, en bloques por segundo (la pone Takt.tick)
	public int drive = DRIVE_HAND;          // qué muelle (lo pone Takt.tick)
	public double gravityMul = 1.0;         // gravedad de más de lo grande en vuelo libre (heavyGravity al soltarlo; vuelve a 1 al posarse)
	public boolean debris;                  // un terrón que salió volando de un cráter: no revienta al caer y no da a su dueño
	@Nullable public Object renderState;    // solo en el cliente (C/render/PieceVisuals); el servidor nunca lo toca

	public Weapon weapon;
	public int wState;      // 0 quieta en la mano, 1 en uso, 2 clavada
	public int wTicks;
	public int wIndex, wCount;
	public Vec3d wVel = Vec3d.ZERO;
	public int wTarget = -1;
	public int impaled = -1;
	public int stuckTicks;

	private int restTicks, freeTicks;
	private int surfEmptyTicks;
	private int lastImpactAge = -1000;     // el último impacto (age) y su energía: no revienta dos veces seguidas
	private int wallGrazeAge = -1000;      // último roce contra muro/techo mientras cae hacia el piso
	private double lastImpactE;
	private final Map<Integer, Integer> hitCooldown = new HashMap<>();
	public int chimeraHostId = -1;
	private int fallSoundCd;
	private boolean fromCut;
	private double maxFallSpeed;
	private double lerpX, lerpY, lerpZ;
	private int lerpSteps;

	// El corte: plano (normal hacia la pieza) por el que resbala
	private Vec3d slideN = new Vec3d(0, 1, 0);
	private Vec3d slideP = Vec3d.ZERO;
	private int slideTicks;
	private double slid;
	private int hoverTicks;
	private int holdTicks;   // recién cortada: quieta un instante antes de moverse

	// Ya puso sus bloques: sigue unos ticks (la cola) para que no se vea un hueco, pero no se guarda ni se toca
	private boolean placed;
	private int landedTail;

	// Takt: el temblor del arranque, la subida recta y la altura sobre el suelo de los trozos grandes
	private int tearTicks, liftTicks, liftT;
	private double liftH, hover;
	private Vec3d liftStart = Vec3d.ZERO;
	// La subida sin choques (liftThrough): acaba cuando su base sale por encima de su hueco (clipTopY)
	private double clipTopY;
	private int clipTicks;
	@Nullable private UUID lastHolder;   // quién la llevó por última vez (dueño del aviso LAND)

	// Posar: la espera de su turno y la bajada hasta su sitio
	@Nullable private BlockPos landBase;
	private int landTicks, landWait, landMax, landRetry;
	private boolean landNear;
	private double landV;

	// Volver a su sitio: el arco de la Unión, o la vuelta callada de un arranque deshecho
	private int retDelay, retT, retDur;
	private Vec3d retFrom = Vec3d.ZERO;
	private double arcH;
	private boolean retQuiet;

	@Nullable private BlockSet massSet;
	private double massCache;
	private MaterialTrait trait = MaterialTrait.NONE;
	public int lastChainAge = -100;
	private final Set<Integer> piercedIds = new HashSet<>();

	private static final boolean AUTOTEST = Boolean.getBoolean("opeope.autotest");
	private static final UUID NOBODY = new UUID(0L, 0L);
	/** Las 124 celdas del cubo 5×5×5 alrededor (sin el centro): más cerca primero, luego más arriba, luego x, luego z. */
	private static final int[][] RELOCATE = relocateOffsets();
	/** bestFit: alturas que prueba (y lo que cuesta cada una) y desplazamientos en horizontal, en este orden. */
	private static final int[] FIT_DY = {0, -1, 1, 2}, FIT_DY_COST = {0, 1, 2, 4};
	private static final int[][] FIT_XZ = {{0, 0}, {1, 0}, {-1, 0}, {0, 1}, {0, -1}};

	public PieceEntity(EntityType<? extends PieceEntity> type, World world) {
		super(type, world);
	}

	public static PieceEntity spawn(ServerWorld world, BlockSet set, Vec3d corner, BlockPos origin, UUID owner) {
		PieceEntity e = new PieceEntity(ModEntities.PIECE, world);
		e.owner = owner;
		e.origin = origin;
		e.setBlocks(set);
		e.setPosition(corner.x + set.sx / 2.0, corner.y, corner.z + set.sz / 2.0);
		world.spawnEntity(e);
		return e;
	}

	// ------------------------------------------------------------------ datos

	@Override
	protected void initDataTracker(DataTracker.Builder builder) {
		builder.add(BLOCKS, new NbtCompound());
		builder.add(FLAGS, (byte) 0);
		builder.add(FX_TIME, 0L);
		builder.add(TRAIT, MaterialTrait.NONE.id);
		builder.add(GRAVITY_SURF, false);
	}

	public boolean isGravitySurf() {
		return this.dataTracker != null && this.dataTracker.get(GRAVITY_SURF);
	}

	public void setGravitySurf(boolean on) {
		if (this.dataTracker != null) {
			this.dataTracker.set(GRAVITY_SURF, on);
		}
		if (on) {
			this.mode = Mode.SURF;
			this.surfEmptyTicks = 0;
			this.solid = false;
			this.noClip = false;
			setNoGravity(true);
		}
		setPosition(getX(), getY(), getZ());
	}

	public void setBlocks(BlockSet set) {
		this.blocks = set;
		this.dataTracker.set(BLOCKS, set.toSyncNbt());
		refreshTrait();
		this.setPosition(getX(), getY(), getZ());
	}

	public MaterialTrait trait() {
		if (getWorld() != null && getWorld().isClient) {
			return MaterialTrait.byId(this.dataTracker.get(TRAIT));
		}
		return this.trait;
	}

	public void refreshTrait() {
		this.massSet = null;
		this.trait = (blocks == null || blocks.isEmpty()) ? MaterialTrait.NONE : blocks.trait();
		if (this.dataTracker != null && getWorld() != null && !getWorld().isClient) {
			this.dataTracker.set(TRAIT, this.trait.id);
		}
	}

	public int flags() {
		return this.dataTracker.get(FLAGS) & 0xFF;
	}

	public void setFlag(int flag, boolean on) {
		setFlags(on ? flag : 0, on ? 0 : flag);
	}

	/** Enciende y apaga varias marcas de una vez (off gana si una está en las dos). */
	private void setFlags(int on, int off) {
		int f = (flags() | on) & ~off;
		this.dataTracker.set(FLAGS, (byte) f);
		if (((on | off) & F_WEAPON) != 0) setPosition(getX(), getY(), getZ());
		if ((on & F_HELD) != 0 && (off & F_HELD) == 0 && !getWorld().isClient) noteHolder();
	}

	/** Recuerda quién la lleva ahora (si alguien la lleva). */
	private void noteHolder() {
		UUID h = Takt.holder(getId());
		if (h != null) lastHolder = h;
	}

	public boolean isWeaponShape() {
		return (flags() & F_WEAPON) != 0;
	}

	/** Tiempo del mundo del último momento que la hizo brillar (arranque o corte). */
	public long fxTime() {
		return this.dataTracker.get(FX_TIME);
	}

	public void markFx(long worldTime) {
		this.dataTracker.set(FX_TIME, worldTime);
	}

	/** Radio de la pieza: media diagonal de su caja (0,6 si es un arma). */
	public double radius() {
		if (isWeaponShape()) return 0.6;
		return 0.5 * Math.sqrt((double) blocks.sx * blocks.sx + (double) blocks.sy * blocks.sy + (double) blocks.sz * blocks.sz);
	}

	/** Del centro a la base. */
	public double below() {
		return isWeaponShape() ? 0.4 : blocks.sy / 2.0;
	}

	/** Masa en kg (GroundRules.mass), calculada una vez por BlockSet. */
	public double massKg() {
		if (massSet != blocks) {
			massSet = blocks;
			massCache = GroundRules.mass(blocks);
		}
		return massCache;
	}

	/** Posándose o ya posada: no se parte, no se absorbe, no se cose, no se coge ni se devuelve. */
	public boolean settling() {
		return mode == Mode.LANDING || placed;
	}

	/** Olvida el temblor, la subida y la altura sobre el suelo de quien la llevaba (4.5). */
	public void resetMotion() {
		tearTicks = 0;
		liftTicks = 0;
		liftT = 0;
		liftH = 0;
		hover = 0;
		setFlag(F_TEAR, false);
	}

	/** Recién arrancada del suelo (4.3): quieta temblando trembleTicks, con las caras cortadas brillando desde worldTime. */
	public void tearAt(long worldTime, int trembleTicks) {
		control(center());
		resetMotion();
		tearTicks = Math.max(0, trembleTicks);
		setFlags(F_FRESH | F_CHUNK | (tearTicks > 0 ? F_TEAR : 0), 0);
		liftStart = center();
		markFx(worldTime);
		setVelocity(Vec3d.ZERO);
	}

	/** Sube recta height bloques en liftTicks ticks (suave al principio y al final); empieza cuando acaba el temblor. */
	public void lift(int liftTicks, double height) {
		this.liftTicks = Math.max(0, liftTicks);
		this.liftH = height;
		this.liftT = 0;
		this.liftStart = center();
	}

	/**
	 * Como lift, pero sin chocar con el mundo hasta que su base sale por encima de su hueco (holeDepth bloques más
	 * arriba): un trozo recién arrancado sale aunque encima haya un árbol, una cuesta o la columna que pisa quien lo
	 * arranca. Mientras no choca sigue recta hacia arriba y nunca hacia la mano: no se mete en la roca. height tiene que
	 * ser mayor que holeDepth.
	 */
	public void liftThrough(int liftTicks, double height, double holeDepth) {
		lift(liftTicks, height);
		clipTopY = getY() + Math.max(0, holeDepth);
		clipTicks = 0;
		noClip = true;
	}

	/**
	 * Una parte de una pieza que aún salía de su hueco sin chocar (se cortó mientras subía): sigue la misma subida, en
	 * su sitio, y vuelve a chocar cuando su propia base sale por encima del hueco.
	 */
	public void leaveLike(PieceEntity from) {
		if (!from.noClip || isWeaponShape()) return;
		liftTicks = from.liftTicks;
		liftT = from.liftT;
		liftH = from.liftH;
		liftStart = from.liftStart.add(center().subtract(from.center()));
		clipTopY = from.clipTopY;
		clipTicks = from.clipTicks;
		noClip = true;
	}

	/**
	 * Tras cada paso de la subida sin choques: si su base ya salió por encima del hueco (o, por si acaso, pasado el
	 * tiempo de sobra), vuelve a chocar. Si al salir quedó metida en un techo que está justo encima, baja hasta enrasar
	 * con el hueco cuando ninguno de sus bloques cae en algo con lo que se choca (una cueva bajita: se queda en la cueva,
	 * no dentro de la roca; una antorcha o una telaraña en la pared no lo impiden). Si aun así no puede moverse hacia
	 * ningún lado (un techo más bajo que el trozo, como un túnel con una antorcha encima del trozo), vuelve a su hueco y
	 * quien lo arrancó lo sabe: nunca se queda encajado en la roca.
	 */
	private void endClip() {
		if (getY() < clipTopY - 1e-4 && ++clipTicks <= liftTicks + OpeConfig.TAKT_CLIP_SPARE_TICKS) return;
		noClip = false;
		World w = getWorld();
		if (w.isBlockSpaceEmpty(this, getBoundingBox())) return;
		double over = getY() - clipTopY;   // lo que se pasó en el último paso (menos de un bloque)
		if (over > 0 && over < 1) {
			Vec3d c = corner();
			BlockPos flush = BlockPos.ofFloored(c.x + 0.5, clipTopY + 0.5, c.z + 0.5);
			if (cellsClear(w, flush)) {
				setPosition(getX(), clipTopY, getZ());
				setVelocity(getVelocity().multiply(1, 0, 1));
				if (w.isBlockSpaceEmpty(this, getBoundingBox())) return;
			}
		}
		if (!canBudge()) Takt.trapped(this);
	}

	/** Ninguna celda del trozo, con su (0,0,0) en base, cae en algo con lo que se choca (una antorcha, una telaraña o un cartel no cuentan). */
	private boolean cellsClear(World w, BlockPos base) {
		BlockView v = GroundRules.loaded(w);
		BlockPos.Mutable m = new BlockPos.Mutable();
		for (BlockSet.Entry e : blocks.entries) {
			m.set(base.getX() + e.x(), base.getY() + e.y(), base.getZ() + e.z());
			if (m.getY() < w.getBottomY() || m.getY() >= w.getTopY()) return false;
			if (!v.getBlockState(m).getCollisionShape(v, m).isEmpty()) return false;
		}
		return true;
	}

	private static final Vec3d[] BUDGE = {new Vec3d(0, 2, 0), new Vec3d(1, 0, 0), new Vec3d(-1, 0, 0), new Vec3d(0, 0, 1), new Vec3d(0, 0, -1)};

	/**
	 * ¿Puede moverse chocando: subir 2 bloques y quedar libre, o ir 1 hacia algún lado sin que nada lo pare? Lo que ya
	 * cruza su caja (un tronco, un bloque suelto) no lo para de lado; un techo que la cruza entera lo para siempre, también
	 * hacia arriba aunque sea fino (al chocar, vanilla no para en lo que la caja ya cruza: lo atravesaría).
	 */
	private boolean canBudge() {
		Box b = getBoundingBox();
		World w = getWorld();
		for (Vec3d d : BUDGE) {
			if (Entity.adjustMovementForCollisions(this, d, b, w, List.of()).squaredDistanceTo(d) >= 1e-6) continue;
			if (d.y > 0 && !w.isBlockSpaceEmpty(this, b.offset(d))) continue;
			return true;
		}
		return false;
	}

	@Override
	public void onTrackedDataSet(TrackedData<?> data) {
		super.onTrackedDataSet(data);
		if (blocks == null) return;
		if (BLOCKS.equals(data) && getWorld().isClient) {
			blocks = BlockSet.fromSyncNbt(this.dataTracker.get(BLOCKS));
			refreshTrait();
			setPosition(getX(), getY(), getZ());
		} else if (FLAGS.equals(data) || GRAVITY_SURF.equals(data)) {
			setPosition(getX(), getY(), getZ());
		}
	}

	@Override
	protected Box calculateBoundingBox() {
		if (blocks == null || this.dataTracker == null) return super.calculateBoundingBox();
		double x = getX(), y = getY(), z = getZ();
		if (isWeaponShape()) return new Box(x - 0.4, y - 0.4, z - 0.4, x + 0.4, y + 0.4, z + 0.4);
		if (isGravitySurf()) {
			double hx = Math.min(0.85, Math.max(0.45, blocks.sx / 2.0 - 0.05));
			double hz = Math.min(0.85, Math.max(0.45, blocks.sz / 2.0 - 0.05));
			return new Box(x - hx, y, z - hz, x + hx, y + Math.max(0.8, blocks.sy) - 0.01, z + hz);
		}
		double hx = blocks.sx / 2.0 - 0.01, hz = blocks.sz / 2.0 - 0.01;
		return new Box(x - hx, y, z - hz, x + hx, y + blocks.sy - 0.01, z + hz);
	}

	public Vec3d center() {
		if (isWeaponShape()) return getPos();
		return getPos().add(0, blocks.sy / 2.0, 0);
	}

	/** Pone el centro de la pieza en un punto. */
	public void setCenter(Vec3d c) {
		if (isWeaponShape()) setPosition(c);
		else setPosition(c.x, c.y - blocks.sy / 2.0, c.z);
	}

	/** Esquina mínima en el mundo (sin girar). */
	public Vec3d corner() {
		return new Vec3d(getX() - blocks.sx / 2.0, getY(), getZ() - blocks.sz / 2.0);
	}

	@Override
	protected void readCustomDataFromNbt(NbtCompound nbt) {
		if (nbt.contains("blocks")) setBlocks(BlockSet.fromSaveNbt(nbt.getCompound("blocks")));
		if (nbt.contains("origin")) origin = BlockPos.fromLong(nbt.getLong("origin"));
		if (nbt.containsUuid("owner")) owner = nbt.getUuid("owner");
		debris = nbt.getBoolean("debris");
	}

	@Override
	protected void writeCustomDataToNbt(NbtCompound nbt) {
		// Ya posada: sus bloques están en el mundo y no deben volver con la entidad.
		nbt.put("blocks", (placed ? new BlockSet() : blocks).toSaveNbt());
		if (origin != null) nbt.putLong("origin", origin.asLong());
		if (owner != null) nbt.putUuid("owner", owner);
		if (debris) nbt.putBoolean("debris", true);
	}

	/**
	 * Por debajo del mundo (Entity.baseTick, antes que tick() de aquí): un terrón de un cráter que se fue al vacío (el
	 * borde de una isla) vuelve a su celda cavada y su bloque no se pierde; lo demás desaparece como siempre.
	 */
	@Override
	protected void tickInVoid() {
		if (!getWorld().isClient && debris && origin != null && !placed) {
			settleAt(origin, false);
			return;
		}
		super.tickInVoid();
	}

	/** Ya posada no se guarda: un guardado durante la cola no puede traer sus bloques otra vez. */
	@Override
	public boolean shouldSave() {
		return !placed && super.shouldSave();
	}

	public Packet<ClientPlayPacketListener> createSpawnPacket(EntityTrackerEntry entry) {
		return new EntitySpawnS2CPacket(this, entry);
	}

	@Override
	public boolean damage(DamageSource source, float amount) {
		return false;
	}

	@Override
	public boolean isImmuneToExplosion(Explosion explosion) {
		return true;
	}

	@Override
	public boolean isAttackable() {
		return false;
	}

	@Override
	public boolean canHit() {
		return !isRemoved() && (flags() & F_LANDED) == 0;
	}

	@Override
	public boolean isCollidable() {
		return solid;
	}

	@Override
	@Nullable
	public LivingEntity getControllingPassenger() {
		return isGravitySurf() && getFirstPassenger() instanceof LivingEntity le ? le : null;
	}

	@Override
	protected Vec3d getPassengerAttachmentPos(Entity passenger, EntityDimensions dimensions, float scaleFactor) {
		double topY = blocks != null && blocks.sy > 0 ? blocks.sy : 1.0;
		return new Vec3d(0.0, topY, 0.0);
	}

	@Override
	protected void updatePassengerPosition(Entity passenger, PositionUpdater positionUpdater) {
		if (!hasPassenger(passenger)) return;
		double topY = getY() + (blocks != null && blocks.sy > 0 ? blocks.sy : 1.0);
		positionUpdater.accept(passenger, getX(), topY, getZ());
	}

	@Override
	public Vec3d updatePassengerForDismount(LivingEntity passenger) {
		double topY = getY() + (blocks != null && blocks.sy > 0 ? blocks.sy : 1.0) + 0.12;
		return new Vec3d(getX(), topY, getZ());
	}

	@Override
	public boolean shouldDismountUnderwater() {
		return !isGravitySurf();
	}

	@Override
	public void updateTrackedPositionAndAngles(double x, double y, double z, float yaw, float pitch, int interpolationSteps) {
		if (isGravitySurf() && isLogicalSideForUpdatingMovement()) return;
		this.lerpX = x;
		this.lerpY = y;
		this.lerpZ = z;
		this.lerpSteps = Math.max(1, interpolationSteps);
	}

	@Override
	public boolean shouldRender(double distance) {
		double d = Math.max(getBoundingBox().getAverageSideLength(), 1.5) * 128.0;
		return distance < d * d;
	}

	// ------------------------------------------------------------------ tick

	@Override
	public void tick() {
		// setFlag(int, boolean) también sobrescribe el Entity.setFlag(índice, valor) de vanilla, y Entity.baseTick llama en
		// cada tick a updateSwimming → setSwimming(false) → setFlag(4, false), que apagaría F_SEAM (= 4). Lo que
		// super.tick() cambie en las marcas no cuenta.
		int keep = flags();
		super.tick();
		if (flags() != keep) this.dataTracker.set(FLAGS, (byte) keep);
		if (getWorld().isClient) {
			if (isGravitySurf() && isLogicalSideForUpdatingMovement()) {
				lerpSteps = 0;
				if (CLIENT_SURF_TICK != null) CLIENT_SURF_TICK.accept(this);
			} else if (lerpSteps > 0) {
				setPosition(getX() + (lerpX - getX()) / lerpSteps, getY() + (lerpY - getY()) / lerpSteps,
					getZ() + (lerpZ - getZ()) / lerpSteps);
				lerpSteps--;
			}
			return;
		}
		if (landedTail > 0) {
			if (--landedTail == 0) discard();
			return;
		}
		if (placed || blocks.isEmpty()) {
			discard();
			return;
		}
		if (getY() < getWorld().getBottomY() - 64) {
			discard();
			return;
		}
		if (age % 10 == 0 && mode == Mode.CONTROLLED && weapon == null) watchdog();
		if ((flags() & F_FRESH) != 0 && getWorld().getTime() - fxTime() > OpeConfig.TAKT_FRESH_TICKS) setFlag(F_FRESH, false);
		if (weapon != null) {
			weaponTick();
			return;
		}
		if (holdTicks > 0 && (mode == Mode.FREE || mode == Mode.SLIDING)) {
			holdTicks--;
			return;
		}
		switch (mode) {
			case CONTROLLED -> controlledTick();
			case RETURNING -> returningTick();
			case SEWING -> sewingTick();
			case STUCK -> setVelocity(Vec3d.ZERO);
			case FREE -> freeTick();
			case SLIDING -> slideTick();
			case HOVER -> hoverTick();
			case LANDING -> landingTick();
			case CHIMERA -> chimeraTick();
			case SURF -> surfTick();
		}
	}

	/** La roca levitada por Fujitora lo transporta como vehículo real; al bajarse, cae por gravedad. */
	private void surfTick() {
		setNoGravity(true);
		noClip = false;
		solid = false;
		fallDistance = 0f;
		Entity rider = getFirstPassenger();
		if (rider == null || !rider.isAlive() || rider.isRemoved()) {
			if (++surfEmptyTicks > 3) {
				setGravitySurf(false);
				release(Vec3d.ZERO, false);
			}
			return;
		}
		surfEmptyTicks = 0;
		rider.fallDistance = 0f;
		if (rider instanceof ServerPlayerEntity sp && !PowerManager.canUse(sp)) {
			sp.stopRiding();
			setGravitySurf(false);
			release(Vec3d.ZERO, false);
		}
	}

	private void chimeraTick() {
		if (!(getWorld() instanceof ServerWorld sw)) return;
		Entity host = chimeraHostId >= 0 ? sw.getEntityById(chimeraHostId) : null;
		if (!(host instanceof LivingEntity le) || !le.isAlive() || le.isRemoved()) {
			chimeraHostId = -1;
			mode = Mode.FREE;
			setNoGravity(false);
			noClip = false;
			setFlag(F_SEAM, false);
			return;
		}
		Vec3d back = Vec3d.fromPolar(0, le.bodyYaw).multiply(-0.35);
		setPosition(le.getPos().add(back.x, Math.max(0.45, le.getHeight() * 0.55), back.z));
		setVelocity(le.getVelocity());
		setNoGravity(true);
		noClip = true;
		fallDistance = 0;
	}

	/**
	 * Vigía (4.4): una pieza llevada cuyo dueño de Takt ya no está (desconectado, en otra dimensión o muerto) se suelta,
	 * y una suspendida cuyo dueño no está se suelta también. Así nada se queda flotando para siempre.
	 */
	private void watchdog() {
		if (!(getWorld() instanceof ServerWorld sw)) return;
		int f = flags();
		if ((f & F_HELD) != 0) {
			UUID h = Takt.holder(getId());
			ServerPlayerEntity hp = h == null ? null : sw.getServer().getPlayerManager().getPlayer(h);
			if (hp == null || hp.getServerWorld() != sw || !hp.isAlive()) {
				Takt.forget(getId());
				release(Vec3d.ZERO, false);
			} else {
				lastHolder = h;
			}
		} else if ((f & F_SUSPENDED) != 0) {
			ServerPlayerEntity op = ownerPlayer();
			if (op == null || op.getServerWorld() != sw) release(Vec3d.ZERO, false);
		}
	}

	// ------------------------------------------------------------------ resbalar por el corte

	/**
	 * Lo que queda encima de un corte resbala por el plano: cuesta abajo por la gravedad y hacia donde fue el tajo.
	 * Mientras resbala no choca con la "piel" del corte (los bloques escalonados justo al otro lado del plano),
	 * así el plano se comporta como una superficie lisa y no como una escalera de bloques.
	 */
	/** Quieta unos ticks (el instante del corte), luego sigue con lo suyo. */
	public void hold(int ticks) {
		holdTicks = ticks;
	}

	public int holdTicks() {
		return holdTicks;
	}

	/** El Bisturí la sujeta en su sitio hasta que sueltas el clic derecho. */
	public static final int HOLD_BISTURI = 1_000_000;

	public void startSlide(Vec3d n, Vec3d planePoint, Vec3d push) {
		mode = Mode.SLIDING;
		fromCut = true;
		slideN = n.normalize();
		slideP = planePoint;
		slideTicks = 0;
		slid = 0;
		thrown = false;
		setVelocity(push);
	}

	private void slideTick() {
		slideTicks++;
		Vec3d g = new Vec3d(0, -0.06, 0);
		boolean verticalCut = Math.abs(slideN.y) < 0.35;
		Vec3d v = getVelocity().add(g.subtract(slideN.multiply(g.dotProduct(slideN))));
		v = v.subtract(slideN.multiply(v.dotProduct(slideN))).multiply(verticalCut ? 0.985 : 0.96);
		double maxSlide = verticalCut ? 2.1 : 1.5;
		if (v.length() > maxSlide) v = v.normalize().multiply(maxSlide);
		int steps = Math.max(1, (int) Math.ceil(v.length() / 0.2));
		Vec3d step = v.multiply(1.0 / steps);
		boolean blocked = false;
		for (int i = 0; i < steps; i++) {
			Vec3d next = getPos().add(step);
			if (slideTicks > 4 && collidesAt(next)) {
				blocked = true;
				break;
			}
			setPosition(next);
			slid += step.length();
		}
		if (v.length() > 0.5) hitEntities(v, false);
		if (slideTicks % 3 == 0 && v.length() > 0.05) slideDust();
		boolean stopped = slideTicks > 18 && v.lengthSquared() < 1e-4;
		if (blocked || stopped || slideTicks > 120) {
			if (slid < 0.75) {
				// Encajada. Si es pequeña, se separa y flota un rato (para agarrarla con Takt); si es un pedazo de terreno
				// enorme que no tiene adónde ir (un corte mirando al suelo), vuelve a su sitio sin más.
				if (blocks.size() > OpeConfig.CUT_HOVER_MAX_BLOCKS && origin != null) {
					setPosition(home());
					settleAt(origin, false);
					return;
				}
				startHover();
				return;
			}
			mode = Mode.FREE;
			fromCut = true;
			restTicks = 0;
			freeTicks = 0;
			setVelocity(blocked ? Vec3d.ZERO : v);
			return;
		}
		setVelocity(v);
	}

	/** ¿Chocaría algún bloque de la pieza con el mundo si su posición fuera esta? (sin contar la piel del corte) */
	private boolean collidesAt(Vec3d pos) {
		World w = getWorld();
		double cx = pos.x - blocks.sx / 2.0, cy = pos.y, cz = pos.z - blocks.sz / 2.0;
		BlockPos.Mutable m = new BlockPos.Mutable();
		final double e = 0.03;
		for (BlockSet.Entry b : blocks.surface()) {
			double x0 = cx + b.x() + e, y0 = cy + b.y() + e, z0 = cz + b.z() + e;
			int ix0 = MathHelper.floor(x0), iy0 = MathHelper.floor(y0), iz0 = MathHelper.floor(z0);
			int ix1 = MathHelper.floor(x0 + 1 - 2 * e), iy1 = MathHelper.floor(y0 + 1 - 2 * e), iz1 = MathHelper.floor(z0 + 1 - 2 * e);
			for (int x = ix0; x <= ix1; x++) {
				for (int y = iy0; y <= iy1; y++) {
					for (int z = iz0; z <= iz1; z++) {
						double d = slideN.x * (x + 0.5 - slideP.x) + slideN.y * (y + 0.5 - slideP.y) + slideN.z * (z + 0.5 - slideP.z);
						if (d > -1.35 && d <= 0.25) continue;   // la piel del corte
						m.set(x, y, z);
						if (w.isOutOfHeightLimit(m)) continue;
						BlockState st = w.getBlockState(m);
						if (st.isAir() || st.isReplaceable()) continue;
						if (!st.getCollisionShape(w, m).isEmpty()) return true;
					}
				}
			}
		}
		return false;
	}

	private void slideDust() {
		if (!(getWorld() instanceof ServerWorld sw)) return;
		BlockState mat = blocks.dominant();
		if (mat == null) return;
		Box b = getBoundingBox();
		sw.spawnParticles(new BlockStateParticleEffect(ParticleTypes.BLOCK, mat), b.getCenter().x, b.minY + 0.1, b.getCenter().z,
			Math.min(30, 4 + blocks.size() / 40), b.getLengthX() / 3, 0.1, b.getLengthZ() / 3, 0.05);
	}

	/** No tiene por dónde resbalar (sigue encajada en el terreno de fuera del ROOM): se separa por el corte y espera. */
	private void startHover() {
		mode = Mode.HOVER;
		fromCut = true;
		hoverTicks = 0;
		setVelocity(Vec3d.ZERO);
		setFlag(F_SUSPENDED, true);
	}

	private Vec3d home() {
		if (origin == null) return getPos();
		return new Vec3d(origin.getX() + blocks.sx / 2.0, origin.getY(), origin.getZ() + blocks.sz / 2.0);
	}

	private void hoverTick() {
		hoverTicks++;
		double t = Math.min(1.0, hoverTicks / 6.0);
		boolean large = blocks != null && blocks.size() > 500;
		double gap = (large ? 1.85 : 1.25) * t;
		double lift = (large ? 0.55 : 0.35) * t + Math.sin(hoverTicks * 0.12) * 0.04;
		setPosition(home().add(slideN.multiply(gap)).add(0, lift, 0));
		setVelocity(Vec3d.ZERO);
		if (hoverTicks > OpeConfig.CUT_HOVER_TICKS) {
			setFlag(F_SUSPENDED, false);
			fromCut = false;
			if (origin != null) {
				setPosition(home());
				settleAt(origin, false);
			} else {
				settle(false);
			}
		}
	}

	/** Si se lanza mientras aún sube sin colisiones desde su hueco, lo saca limpio por arriba si hay espacio. */
	public void clearLiftHole() {
		if (!noClip || clipTopY <= getY()) return;
		double dy = Math.min(clipTopY - getY() + 0.08, 3.0);
		if (dy > 0.01 && getWorld().isBlockSpaceEmpty(this, getBoundingBox().offset(0, dy, 0))) {
			setPosition(getX(), getY() + dy, getZ());
		}
	}

	/** Suelta la pieza: vuelve a caer con la gravedad. Nadie la lleva ya. */
	public void release(Vec3d velocity, boolean asThrow) {
		if (isGravitySurf()) {
			if (this.dataTracker != null) this.dataTracker.set(GRAVITY_SURF, false);
			removeAllPassengers();
		}
		mode = Mode.FREE;
		chimeraHostId = -1;
		fromCut = false;
		fallSoundCd = 0;
		maxFallSpeed = 0;
		solid = false;
		target = null;
		targetVel = Vec3d.ZERO;
		setVelocity(velocity);
		thrown = asThrow;
		if (asThrow) slowFall = false;
		thrownTicks = 0;
		restTicks = 0;
		freeTicks = 0;
		lastImpactAge = -1000;
		wallGrazeAge = -1000;
		noClip = false;
		setNoGravity(false);
		gravityMul = heavyGravity(radius());
		resetMotion();
		setFlags(0, F_SUSPENDED | F_HELD);
		if (!getWorld().isClient) Takt.forget(getId());
	}

	public void control(Vec3d centerTarget) {
		mode = Mode.CONTROLLED;
		chimeraHostId = -1;
		fromCut = false;
		holdTicks = 0;
		target = centerTarget;
		targetVel = Vec3d.ZERO;
		drive = DRIVE_HAND;
		slowFall = false;
		noClip = false;
		setNoGravity(false);
		gravityMul = 1.0;
		debris = false;
	}

	/**
	 * El muelle de Takt (4.5): tras el temblor sube recta, luego sigue su sitio sin tirones. Los trozos grandes (y los
	 * arrancados del suelo) que se llevan en la mano o en órbita flotan a una altura segura sobre el suelo de debajo.
	 */
	private void controlledTick() {
		if (tearTicks > 0) {
			setVelocity(Vec3d.ZERO);
			if (--tearTicks == 0) {
				setFlag(F_TEAR, false);
				liftStart = center();
				liftT = 0;
			}
			return;
		}
		int f = flags();
		boolean held = (f & F_HELD) != 0;
		if (held) noteHolder();
		if (target == null) target = center();
		Vec3d t = target;
		Vec3d vt = targetVel != null ? targetVel : Vec3d.ZERO;
		double r = radius();
		if (liftT < liftTicks) {
			liftT++;
			t = liftStart.add(0, liftH * smoothstep((double) liftT / liftTicks), 0);
			vt = Vec3d.ZERO;
		} else if (noClip) {
			// aún dentro de su hueco sin chocar (liftThrough): sigue recta hacia arriba, nunca hacia la mano
			t = liftStart.add(0, liftH, 0);
			vt = Vec3d.ZERO;
		} else if (held && drive != DRIVE_PARK && (r > 2.5 || (f & F_CHUNK) != 0)) {
			double need = groundBelow(t, r) + below() + 1.6 + 0.22 * r - t.y;
			hover += (Math.max(0, need) - hover) * 0.32;
			t = t.add(0, hover, 0);
		}
		spring(t, vt, r);
		if (noClip && mode == Mode.CONTROLLED && !placed && !isRemoved()) endClip();
	}

	/**
	 * La cara de arriba del suelo más alto bajo 7 puntos (t y 6 alrededor a distancia r), mirando 24 bloques hacia abajo;
	 * −∞ si no hay. Si un punto cae dentro del suelo (la mano apunta a una ladera o a una pared), primero sale hacia arriba.
	 */
	private double groundBelow(Vec3d t, double r) {
		BlockView view = GroundRules.loaded(getWorld());
		BlockPos.Mutable m = new BlockPos.Mutable();
		double g = Double.NEGATIVE_INFINITY;
		int yStart = MathHelper.floor(t.y);
		for (int k = -1; k < 6; k++) {
			double px = t.x, pz = t.z;
			if (k >= 0) {
				px += r * Math.cos(k * Math.PI / 3);
				pz += r * Math.sin(k * Math.PI / 3);
			}
			int x = MathHelper.floor(px), z = MathHelper.floor(pz);
			int y0 = yStart;
			if (solidAt(view, m, x, y0, z)) {
				int up = 1;
				while (up <= 24 && solidAt(view, m, x, y0 + up, z)) up++;
				if (up > 24) continue;
				y0 += up;
			}
			for (int y = y0 - 1; y >= y0 - 24; y--) {
				if (solidAt(view, m, x, y, z)) {
					g = Math.max(g, y + 1);
					break;
				}
			}
		}
		return g;
	}

	private static boolean solidAt(BlockView view, BlockPos.Mutable m, int x, int y, int z) {
		m.set(x, y, z);
		return GroundRules.solid(view.getBlockState(m), view, m);
	}

	private static boolean floorSolidAt(BlockView view, BlockPos.Mutable m, int x, int y, int z) {
		m.set(x, y, z);
		BlockState st = view.getBlockState(m);
		return !isNaturalTreeOrCanopy(st) && GroundRules.solid(st, view, m);
	}

	/**
	 * Un paso del muelle amortiguado hacia t (velocidad del sitio vt, en b/s), en 3 subpasos de 1/60 s y con la
	 * aceleración limitada; los trozos grandes son más lentos y pesados. Se mueve chocando con el mundo.
	 */
	private void spring(Vec3d t, Vec3d vt, double r) {
		double s = Math.max(1, r / 2.5);
		double kHand = s <= 1 ? 85 : Math.max(24, 85 / Math.pow(s, 0.75));
		double aHand = s <= 1 ? 260 : Math.max(85, 260 / Math.pow(s, 0.45));
		double k, c, maxA;
		if (drive == DRIVE_PARK) {
			k = 22 / Math.sqrt(s);
			c = 9 / Math.pow(s, 0.25);
			maxA = 0.6 * aHand;
		} else {
			k = kHand;
			c = s <= 1 ? 18 : 2 * Math.sqrt(kHand) * 0.96;
			maxA = aHand;
		}
		final double h = 1 / 60.0;
		Vec3d v = getVelocity().multiply(20);
		Vec3d x = center();
		Vec3d total = Vec3d.ZERO;
		for (int i = 0; i < 3; i++) {
			Vec3d acc = t.subtract(x).multiply(k).add(vt.subtract(v).multiply(c));
			double a = acc.length();
			if (a > maxA) acc = acc.multiply(maxA / a);
			v = v.add(acc.multiply(h));
			Vec3d step = v.multiply(h);
			x = x.add(step);
			total = total.add(step);
		}
		Vec3d before = getPos();
		move(MovementType.SELF, total);
		Vec3d moved = getPos().subtract(before);
		v = new Vec3d(Math.abs(moved.x - total.x) > 1e-4 ? 0 : v.x, Math.abs(moved.y - total.y) > 1e-4 ? 0 : v.y,
			Math.abs(moved.z - total.z) > 1e-4 ? 0 : v.z);
		Vec3d perTick = v.multiply(1 / 20.0);
		setVelocity(perTick);
		if (sewOnContact && (horizontalCollision || verticalCollision)) {
			settle(true);
			return;
		}
		if (perTick.length() > 0.7) hitEntities(perTick, true);
		if (!noClip && !placed && drive == DRIVE_HAND && blocks.size() >= 80
			&& perTick.lengthSquared() > 1.0 && getWorld() instanceof ServerWorld sw) {
			plowTreeBlocks(sw, moved, false, true);
		}
	}

	/**
	 * En vuelo libre (lanzada, soltada o cayendo sola): la gravedad (más fuerte en lo enorme), el aire (que casi no frena
	 * lo grande) y, al chocar, el impacto si lo hay (strikes); si no, roce con el suelo hasta pararse y posarse.
	 */
	private void freeTick() {
		freeTicks++;
		thrownTicks++;
		if (ownerGraceTicks > 0) ownerGraceTicks--;
		Vec3d v = getVelocity();
		if (!hasNoGravity()) v = v.add(0, slowFall ? -0.012 : -0.06 * gravityMul, 0);
		if (slowFall && v.y < -0.12) v = new Vec3d(v.x, -0.12, v.z);
		double max = OpeConfig.FALL_MAX_SPEED;
		if (v.lengthSquared() > max * max) v = v.multiply(max / v.length());
		double speedBefore = v.length();
		maxFallSpeed = Math.max(maxFallSpeed, speedBefore);
		if (getWorld() instanceof ServerWorld sw) {
			spawnFallTrail(sw, v, speedBefore);
			if (fallSoundCd > 0) fallSoundCd--;
			if (!debris && !slowFall && blocks.size() >= 18 && speedBefore > 0.95 && fallSoundCd == 0) {
				sw.playSound(null, getX(), getY(), getZ(), SoundEvents.ENTITY_BREEZE_WIND_BURST, SoundCategory.BLOCKS,
					(float) Math.min(2.2, 0.5 + blocks.size() / 80.0), 0.55f);
				fallSoundCd = 14;
			}
			if (!debris && !slowFall && (thrown || blocks.size() >= 12) && (speedBefore > 0.45 || isOnGround())) {
				Vec3d plowVec = v.lengthSquared() > 0.25 ? v : new Vec3d(0, -1.2, 0);
				boolean chopWood = blocks.size() >= 12 || (thrown && blocks.size() >= 6 && speedBefore > 1.1);
				plowTreeBlocks(sw, plowVec, false, chopWood);
			}
		}
		setVelocity(v);
		move(MovementType.SELF, v);
		boolean collided = horizontalCollision || verticalCollision;
		if (v.length() > 0.5) hitEntities(v, debris);
		if (collided) {
			if (sewOnContact) {
				settle(true);
				return;
			}
			FloorHit hit = inspectFloor(GroundRules.loaded(getWorld()), v, false);
			if (hit.properFloor() && strikes(v, true)) {
				impact(v, hit);
				return;
			}
			if ((thrown || (!debris && !slowFall && blocks.size() >= 18 && maxFallSpeed > 0.8)) && !hit.properFloor()) {
				deflectMidAir(v);
				return;
			}
		}
		Vec3d after = getVelocity();
		double air = airDrag(radius());
		double f = isOnGround() ? 0.55 : air;
		setVelocity(after.x * f, after.y * air, after.z * f);
		if (isOnGround() && getVelocity().lengthSquared() < 0.004) restTicks++;
		else restTicks = 0;
		if (isOnGround() && restTicks >= (thrown ? 1 : 2) && lastImpactAge < 0 && !debris && !slowFall
			&& (thrown || (maxFallSpeed > OpeConfig.IMPACT_FALL_SPEED && massKg() >= OpeConfig.IMPACT_FALL_KG))) {
			Vec3d synthVel = new Vec3d(v.x, -Math.max(0.65, maxFallSpeed), v.z);
			FloorHit hit = inspectFloor(GroundRules.loaded(getWorld()), synthVel, thrown);
			if (hit.properFloor() && strikes(synthVel, true)) {
				impact(synthVel, hit);
				return;
			}
		}
		boolean keepCut = fromCut && !thrown && !slowFall && lastImpactAge < 0;
		int maxRest = keepCut ? 20 * 25 : 6;
		if (restTicks > maxRest || freeTicks > 20 * 30) {
			if (thrown && lastImpactAge < 0 && !debris && !slowFall) {
				Vec3d synthVel = new Vec3d(v.x, -Math.max(0.85, maxFallSpeed), v.z);
				FloorHit hit = inspectFloor(GroundRules.loaded(getWorld()), synthVel, true);
				if (hit.properFloor() || isOnGround()) {
					impact(synthVel, hit.properFloor() ? hit : new FloorHit(true, hit.floor(), getPos()));
					return;
				}
			}
			settle(false);
		}
	}

	// ------------------------------------------------------------------ posar (4.6)

	/** Baja hasta base (la esquina mínima donde quedará) y allí vuelve a ser bloques, sellada. */
	public void startLanding(BlockPos base) {
		startLanding(base, 0);
	}

	/**
	 * Lo mismo, pero antes espera quieta waitTicks: varias piezas posadas a la vez llegan en orden, cada una después de
	 * las que le quedan debajo, sin cruzarse.
	 */
	public void startLanding(BlockPos base, int waitTicks) {
		mode = Mode.LANDING;
		landBase = base.toImmutable();
		landTicks = 0;
		landWait = Math.max(0, waitTicks);
		landRetry = 0;
		landNear = false;
		landMax = landBudget(getY() - base.getY());
		landV = 0.11;
		target = null;
		thrown = false;
		resetMotion();
		setFlags(F_LANDED, F_HELD);
		noClip = false;
		setNoGravity(false);
		setVelocity(Vec3d.ZERO);
		if (!getWorld().isClient) Takt.forget(getId());
	}

	/** El tope de ticks de una bajada de drop bloques: una red, nunca el final normal (a 0,45 b/t llega antes). */
	private static int landBudget(double drop) {
		return Math.max(OpeConfig.TAKT_LAND_MAX_TICKS, 20 + (int) Math.ceil(Math.abs(drop) / 0.3));
	}

	/**
	 * Los ticks que tarda en posarse una pieza a drop bloques por encima de su sitio (negativo: por debajo) y desviada
	 * dx, dz en horizontal: la misma cuenta que landingTick.
	 */
	public static int landingTicks(double drop, double dx, double dz) {
		double v = 0.11, down = Math.max(0, drop), up = Math.max(0, -drop), ax = Math.abs(dx), az = Math.abs(dz);
		int n = 0;
		while (n < 2000) {
			n++;
			v = Math.min(0.45, v + 0.02);
			double hx = Math.min(0.3, ax * 0.35), hz = Math.min(0.3, az * 0.35);
			ax -= hx;
			az -= hz;
			if (down > 0) down = Math.max(0, down - v);
			else up = Math.max(0, up - 0.3);
			if (down + up < 1e-3 && hx + hz < 1e-3) break;
		}
		return n;
	}

	/**
	 * Espera su turno; luego cae cada vez más deprisa y se centra sobre su sitio, sin choques: la columna se comprobó
	 * libre al elegirlo. Justo antes de entrar y al llegar lo vuelve a mirar: si mientras tanto apareció algo ahí (otra
	 * pieza posada, un bloque puesto), busca sitio otra vez desde donde está y se queda encima.
	 */
	private void landingTick() {
		if (landBase == null || placed) return;
		if (landWait > 0) {
			landWait--;
			setVelocity(Vec3d.ZERO);
			return;
		}
		BlockPos b = landBase;
		Vec3d goal = new Vec3d(b.getX() + blocks.sx / 2.0, b.getY(), b.getZ() + blocks.sz / 2.0);
		landTicks++;
		landV = Math.min(0.45, landV + 0.02);
		double hx = MathHelper.clamp((goal.x - getX()) * 0.35, -0.3, 0.3);
		double hz = MathHelper.clamp((goal.z - getZ()) * 0.35, -0.3, 0.3);
		double y = getY();
		double ny = y > goal.y ? Math.max(goal.y, y - landV) : Math.min(goal.y, y + 0.3);
		setPosition(getX() + hx, ny, getZ() + hz);
		boolean arrived = (Math.abs(ny - goal.y) < 1e-3 && Math.abs(hx) + Math.abs(hz) < 1e-3) || landTicks >= landMax;
		boolean near = !landNear && ny - goal.y <= 1.2;
		if ((arrived || near) && landRetry < 3 && getWorld() instanceof ServerWorld sw) {
			landNear = true;
			BlockView view = GroundRules.loaded(sw);
			if (!Landing.fits(view, blocks, b, List.of())) {
				landRetry++;
				BlockPos nb = Landing.find(view, blocks, corner(), List.of());
				if (nb != null && !nb.equals(b)) {
					landBase = nb;
					landTicks = 0;
					landNear = false;
					landMax = landBudget(getY() - nb.getY());
					return;
				}
			}
		}
		if (arrived) {
			setPosition(goal);
			if (getWorld() instanceof ServerWorld w) {
				List<BlockSet.Entry> under = blocks.underside();
				int[] data = new int[Math.min(512, under.size())];
				for (int i = 0; i < data.length; i++) {
					BlockSet.Entry e = under.get(i);
					data[i] = Payloads.TaktFx.pack(e.x(), e.z(), e.y());
				}
				sendFx(w, Payloads.TaktFx.LAND, b.getX(), b.getY(), b.getZ(), (float) radius(), tonnes(), chunkFx(), blocks.size(), data,
					lastHolder != null ? lastHolder : ownerOr());
			}
			place(b, true, false, false);
		}
	}

	// ------------------------------------------------------------------ volver a su sitio (4.9, 4.3)

	/** La Unión: tras delayTicks vuela en arco a su sitio y allí se sella. */
	public void startReturn(int delayTicks) {
		mode = Mode.RETURNING;
		retDelay = Math.max(0, delayTicks);
		retT = 0;
		retFrom = getPos();
		retQuiet = false;
		double d = home().distanceTo(retFrom);
		retDur = Math.max(4, (int) Math.round(20 * 1.15 * (0.8 + Math.min(0.6, d / 60))));
		arcH = Math.min(6, 0.4 + 0.12 * d);
		target = null;
		thrown = false;
		resetMotion();
		setFlags(F_SEAM, F_HELD | F_SUSPENDED);
		noClip = false;
		setNoGravity(false);
		setVelocity(Vec3d.ZERO);
		if (!getWorld().isClient) Takt.forget(getId());
	}

	/**
	 * Deshacer un arranque ya suelto: vuelve a su hueco sin ruido. Si no se movió, se pone ahí mismo (la cola la sigue
	 * dibujando, así no se ve un hueco); si se movió, se hunde recta en su sitio en 0,2 a 0,6 s, sin HOME ni campanilla.
	 */
	public void restoreHome() {
		resetMotion();
		setFlag(F_HELD, false);
		if (!getWorld().isClient) Takt.forget(getId());
		noClip = false;
		setNoGravity(false);
		setVelocity(Vec3d.ZERO);
		if (origin == null) {
			release(Vec3d.ZERO, false);
			return;
		}
		Vec3d home = home();
		double d = getPos().distanceTo(home);
		if (d < 0.05) {
			setPosition(home);
			place(origin, true, false, true);
			return;
		}
		mode = Mode.RETURNING;
		target = null;
		thrown = false;
		retDelay = 0;
		retT = 0;
		retFrom = getPos();
		arcH = 0;
		retDur = MathHelper.clamp((int) Math.round(3 + 2 * d), 4, 12);
		retQuiet = true;
		setFlags(F_LANDED, F_SUSPENDED | F_SEAM);
	}

	/** El vuelo de vuelta con su propio reloj (nunca la edad de la pieza): espera, arco suave y sellado al llegar. */
	private void returningTick() {
		if (origin == null) {
			release(Vec3d.ZERO, false);
			return;
		}
		if (retDelay > 0) {
			retDelay--;
			return;
		}
		retT++;
		double u = Math.min(1, (double) retT / Math.max(1, retDur));
		double e = easeInOutCubic(u);
		Vec3d home = home();
		setPosition(retFrom.lerp(home, e).add(0, Math.sin(Math.PI * e) * arcH, 0));
		if (!retQuiet && retT % 2 == 0) sparkle(1);
		if (u >= 1 || retT > retDur + 40) {
			setPosition(home);
			setFlag(F_SEAM, false);
			if (!retQuiet && getWorld() instanceof ServerWorld w) {
				sendFx(w, Payloads.TaktFx.HOME, home.x, home.y + blocks.sy / 2.0, home.z, (float) radius(), tonnes(), chunkFx(), 0,
					new int[0], ownerOr());
			}
			place(origin, true, false, retQuiet);
		}
	}

	private void sewingTick() {
		if (target == null) {
			release(Vec3d.ZERO, false);
			return;
		}
		Vec3d d = target.subtract(center());
		if (d.length() < 0.3) {
			setCenter(target);
			settle(true);
			return;
		}
		Vec3d v = d.multiply(0.25);
		if (v.length() > 2.0) v = v.normalize().multiply(2.0);
		if (v.length() < 0.15) v = d.normalize().multiply(Math.min(0.15, d.length()));
		setPosition(getPos().add(v));
		sparkle(2);
	}

	private void sparkle(int n) {
		if (getWorld() instanceof ServerWorld sw) {
			Box b = getBoundingBox();
			sw.spawnParticles(ParticleTypes.END_ROD, b.getCenter().x, b.getCenter().y, b.getCenter().z, n,
				b.getLengthX() / 3, b.getLengthY() / 3, b.getLengthZ() / 3, 0.01);
		}
	}

	private DamageSource damageSource() {
		UUID mv = mover();
		ServerPlayerEntity p = mv == null || !(getWorld() instanceof ServerWorld sw) ? null : sw.getServer().getPlayerManager().getPlayer(mv);
		return p != null ? getDamageSources().thrown(this, p) : getDamageSources().fallingBlock(this);
	}

	/**
	 * Quién la movió por última vez: quien la llevó con Takt y la soltó o la lanzó (lastHolder) o, si nadie la llevó,
	 * su dueño (quien la cortó o la arrancó). Su golpe no le hace daño y a él se le cuentan las bajas.
	 */
	@Nullable
	public UUID mover() {
		return lastHolder != null ? lastHolder : owner;
	}

	/** ¿Es le quien la movió por última vez (mover)? A ese no le hace daño su golpe; a quien la cortó y no la llevó, sí. */
	private boolean spares(Entity le) {
		return le.getUuid().equals(mover());
	}

	public ServerPlayerEntity ownerPlayer() {
		if (owner == null || !(getWorld() instanceof ServerWorld sw)) return null;
		return sw.getServer().getPlayerManager().getPlayer(owner);
	}

	private void hitEntities(Vec3d v, boolean sparingOwner) {
		double speed = v.length();
		boolean heavy = trait() == MaterialTrait.HEAVY_PIERCING;
		List<LivingEntity> list = getWorld().getEntitiesByClass(LivingEntity.class, getBoundingBox().expand(0.25),
			e -> e.isAlive() && !e.isSpectator() && !(spares(e) && (sparingOwner || thrownTicks < 12 || ownerGraceTicks > 0)));
		for (LivingEntity le : list) {
			Integer until = hitCooldown.get(le.getId());
			if (until != null && until > age) continue;
			float cap = heavy ? OpeConfig.HEAVY_DMG_CAP : 30.0f;
			float dmg = (float) Math.min(cap, (2 + speed * 3.5 * Math.cbrt(blocks.size())) * ElementEffects.flightDamageMult(this, le));
			le.damage(damageSource(), dmg);
			ElementEffects.onFlightHit(this, le, dmg, v);
			double kbScale = heavy ? 1.3 : 1.0;
			Vec3d push = v.normalize().multiply(Math.min(2.5 * kbScale, speed * 0.6 * kbScale)).add(0, 0.25, 0);
			le.addVelocity(push.x, push.y, push.z);
			le.velocityModified = true;
			hitCooldown.put(le.getId(), age + 10);
		}
	}

	// ------------------------------------------------------------------ impacto (4.8)

	/** El aire frena lo pequeño y casi nada lo grande (el área crece con r², la masa con r³). Multiplica la velocidad cada tick. */
	public static double airDrag(double r) {
		return 1 - 0.02 * Math.min(1.0, Math.pow(1.2 / Math.max(r, 0.1), 1.5));
	}

	/** La gravedad de más de lo enorme en vuelo, como en el juego («lo enorme no flota»): ×1 hasta radio 2,2 y ×1,55 desde 10,2. */
	public static double heavyGravity(double r) {
		return 1 + 0.55 * MathHelper.clamp((r - 2.2) / 8, 0, 1);
	}

	/**
	 * El nivel de un golpe de e julios (0 nada, 1 muro, 2 casa, 3 colosal), con el tope del juego por tamaño y masa: lo
	 * pequeño y ligero no revienta como lo colosal, pero lo que pesa sí, aunque sea compacto.
	 */
	public static int tierOf(double e, double r, double kg) {
		int t = e >= OpeConfig.IMPACT_TIER3 ? 3 : e >= OpeConfig.IMPACT_TIER2 ? 2 : e >= OpeConfig.IMPACT_TIER1 ? 1 : 0;
		if (r < 1.2 && kg < 8000) return Math.min(t, 1);
		if (r < 3.0 && kg < 30000) return Math.min(t, 2);
		return t;
	}

	/** La medida continua del golpe (0 a 1) que escala todo dentro de su nivel (kOf del juego). */
	public static double kOf(double e) {
		double top = OpeConfig.IMPACT_TIER3 * 12 / OpeConfig.IMPACT_TIER1;
		return MathHelper.clamp(Math.log10(Math.max(1, e / OpeConfig.IMPACT_TIER1)) / Math.log10(top), 0, 1);
	}

	/** Lo que el suelo deja seguir de la velocidad de lado al clavarse (el juego: lo que pesa más de 160 t casi no sigue). */
	private double keepOnGround() {
		return MathHelper.clamp(1.05 - massKg() / 1.6e5, 0.15, 1);
	}

	/**
	 * La energía del golpe (julios) de un choque a velocidad v (b/t). Contra el suelo se clava: pierde toda la de caer y
	 * casi toda la de lado (lo pesado o lanzado con fuerza). Contra una pared o un techo, lo que pierde al pararse.
	 */
	private double blowEnergy(Vec3d v) {
		double m = massKg();
		if (verticalCollision && v.y < 0 || thrown) {
			double kh = thrown ? Math.min(keepOnGround(), 0.55) : keepOnGround();
			double h2 = v.x * v.x + v.z * v.z;
			return 0.5 * m * 400 * (v.y * v.y + h2 * (1 - kh * kh));
		}
		return 0.5 * m * 400 * Math.max(0, v.lengthSquared() - getVelocity().lengthSquared());
	}

	/**
	 * ¿Es un impacto este choque contra el piso? Requiere tocar bien el piso (properFloor). Lo lanzado impacta incluso a
	 * quemarropa (thrownTicks >= 1); lo que cae solo necesita masa y velocidad mínima.
	 */
	private boolean strikes(Vec3d v, boolean properFloor) {
		if (sewOnContact || !properFloor || debris || slowFall) return false;
		double s = v.length();
		if (thrown) return thrownTicks >= 1 && (s > 0.35 || maxFallSpeed > 0.55);
		if (s < OpeConfig.IMPACT_FALL_SPEED || massKg() < OpeConfig.IMPACT_FALL_KG) return false;
		double e = blowEnergy(v);
		if (age - lastImpactAge < 9 && e < 3 * lastImpactE) return false;
		return tierOf(e, radius(), massKg()) >= 1;
	}

	private record FloorHit(boolean properFloor, BlockState floor, Vec3d contact) {}

	/**
	 * Exige contacto contra piso o ladera cercana (solidUnder > 0 dentro de dy <= 2), ignorando muros o techos
	 * flotantes en pleno aire y detectando el terreno real incluso bajo nieve fina, hojarasca o vegetación superficial.
	 */
	private FloorHit inspectFloor(BlockView view, Vec3d moveVel, boolean forceResting) {
		BlockState defaultFloor = view.getBlockState(BlockPos.ofFloored(getX(), getY() - 0.5, getZ()));
		if (!forceResting && verticalCollision && moveVel.y > 0.08 && !isOnGround()) {
			return new FloorHit(false, defaultFloor, new Vec3d(getX(), getY() + blocks.sy, getZ()));
		}
		boolean downHit = verticalCollision && moveVel.y <= 0.08;
		boolean stepOrSlopeHit = horizontalCollision && moveVel.y <= 0.22;
		boolean restingHit = forceResting || (isOnGround() && (restTicks >= 1 || thrown));
		if (!downHit && !stepOrSlopeHit && !restingHit) {
			return new FloorHit(false, defaultFloor, getPos());
		}

		double insetHalfX = blocks.sx <= 1 ? 0.22 : Math.max(0.24, blocks.sx * 0.5 - 0.16);
		double insetHalfZ = blocks.sz <= 1 ? 0.22 : Math.max(0.24, blocks.sz * 0.5 - 0.16);
		int yBase = MathHelper.floor(getY() - 0.08);
		int solidNear = 0;
		int solidUnder = 0;
		boolean centerSolid = false;
		BlockState bestTerrain = null;
		int bestTerrainY = Integer.MIN_VALUE;
		double bestDist2 = Double.MAX_VALUE;
		BlockState bestSolid = null;
		int bestSolidY = Integer.MIN_VALUE;
		double bestSolidDist2 = Double.MAX_VALUE;
		BlockPos.Mutable m = new BlockPos.Mutable();

		for (int gx = -1; gx <= 1; gx++) {
			double sx = getX() + gx * insetHalfX;
			int ix = MathHelper.floor(sx);
			for (int gz = -1; gz <= 1; gz++) {
				double sz = getZ() + gz * insetHalfZ;
				int iz = MathHelper.floor(sz);
				boolean near = false;
				boolean under = false;
				for (int dy = 0; dy <= 2; dy++) {
					if (floorSolidAt(view, m, ix, yBase - dy, iz)) {
						if (dy <= 1) near = true;
						under = true;
						break;
					}
				}
				if (near) solidNear++;
				if (under) {
					solidUnder++;
					if (gx == 0 && gz == 0) centerSolid = true;
				}

				for (int dy = 0; dy <= 3; dy++) {
					int ty = yBase - dy;
					BlockState st = view.getBlockState(m.set(ix, ty, iz));
					if (GroundRules.terrain(st)) {
						double d2 = (sx - getX()) * (sx - getX()) + (sz - getZ()) * (sz - getZ()) + dy * 0.25;
						if (d2 < bestDist2) {
							bestDist2 = d2;
							bestTerrain = st;
							bestTerrainY = ty;
						}
						break;
					}
					if (!isNaturalTreeOrCanopy(st) && GroundRules.solid(st, view, m)) {
						double d2 = (sx - getX()) * (sx - getX()) + (sz - getZ()) * (sz - getZ()) + dy * 0.25;
						if (d2 < bestSolidDist2) {
							bestSolidDist2 = d2;
							bestSolid = st;
							bestSolidY = ty;
						}
						break;
					}
					if (!st.isAir() && !st.isReplaceable() && !st.isOf(Blocks.SNOW)
						&& !st.isOf(Blocks.MOSS_CARPET) && !isNaturalTreeOrCanopy(st) && !GroundRules.plantLike(st, view, m)) {
						break;
					}
				}
			}
		}

		if (bestTerrain == null && (blocks.sx > 4 || blocks.sz > 4)) {
			for (double sx = getX() - insetHalfX; sx <= getX() + insetHalfX + 0.01; sx += 2.0) {
				int ix = MathHelper.floor(sx);
				for (double sz = getZ() - insetHalfZ; sz <= getZ() + insetHalfZ + 0.01; sz += 2.0) {
					int iz = MathHelper.floor(sz);
					boolean near = false, under = false;
					for (int dy = 0; dy <= 4; dy++) {
						int ty = yBase - dy;
						BlockState st = view.getBlockState(m.set(ix, ty, iz));
						if (!isNaturalTreeOrCanopy(st) && GroundRules.solid(st, view, m)) {
							if (dy <= 1) near = true;
							under = true;
						}
						if (GroundRules.terrain(st)) {
							double d2 = (sx - getX()) * (sx - getX()) + (sz - getZ()) * (sz - getZ()) + dy * 0.25;
							if (d2 < bestDist2) {
								bestDist2 = d2;
								bestTerrain = st;
								bestTerrainY = ty;
							}
							break;
						}
					}
					if (near) solidNear++;
					if (under) solidUnder++;
				}
			}
		}

		if (bestTerrain == null && horizontalCollision && solidUnder > 0) {
			double vh = Math.hypot(moveVel.x, moveVel.z);
			if (vh > 1e-4) {
				int iax = MathHelper.floor(getX() + (moveVel.x / vh) * (blocks.sx * 0.5 + 0.55));
				int iaz = MathHelper.floor(getZ() + (moveVel.z / vh) * (blocks.sz * 0.5 + 0.55));
				for (int dy = -1; dy <= 2; dy++) {
					int ty = yBase - dy;
					BlockState st = view.getBlockState(m.set(iax, ty, iaz));
					if (GroundRules.terrain(st)) {
						bestTerrain = st;
						bestTerrainY = ty;
						break;
					}
					if (bestSolid == null && !isNaturalTreeOrCanopy(st) && GroundRules.solid(st, view, m)) {
						bestSolid = st;
						bestSolidY = ty;
					}
				}
			}
		}

		boolean smallFoot = blocks.size() <= 4 || Math.max(blocks.sx, blocks.sz) <= 2;
		boolean properFloor;
		if (forceResting) {
			properFloor = solidUnder > 0 || isOnGround();
		} else if (downHit || restingHit) {
			properFloor = solidNear >= 2 || (solidUnder >= 2 && isOnGround()) || centerSolid || (smallFoot && solidUnder >= 1);
		} else {
			properFloor = solidUnder >= 2 || centerSolid || (smallFoot && solidUnder >= 1);
		}
		BlockState floor = bestTerrain != null ? bestTerrain : (bestSolid != null ? bestSolid : defaultFloor);
		double contactY = bestTerrain != null ? MathHelper.clamp(bestTerrainY + 1.0, getY() - 1.5, getY() + 0.5)
			: (bestSolid != null ? MathHelper.clamp(bestSolidY + 1.0, getY() - 1.5, getY() + 0.5) : getY());
		return new FloorHit(properFloor, floor, new Vec3d(getX(), contactY, getZ()));
	}

	/** Si tocó una pared o un techo en pleno vuelo, rebota/resbala sin perder la picada hacia el suelo. */
	private void deflectMidAir(Vec3d moveVel) {
		Vec3d after = getVelocity();
		boolean hitCeiling = verticalCollision && moveVel.y > 0;
		double downKick = Math.max(0.55, Math.min(1.15, moveVel.length() * 0.50));
		double ny = hitCeiling ? Math.min(after.y, -Math.max(0.65, downKick)) : Math.min(after.y, -downKick);
		setVelocity(after.x * 0.78, ny, after.z * 0.78);
		restTicks = 0;
		if (getWorld() instanceof ServerWorld sw && age - wallGrazeAge >= 5 && moveVel.length() > 0.75) {
			wallGrazeAge = age;
			Vec3d c = center();
			BlockState mat = blocks != null && blocks.dominant() != null ? blocks.dominant() : Blocks.STONE.getDefaultState();
			sw.spawnParticles(new BlockStateParticleEffect(ParticleTypes.BLOCK, mat), c.x, c.y, c.z, 18,
				Math.max(0.4, blocks.sx * 0.35), Math.max(0.4, blocks.sy * 0.35), Math.max(0.4, blocks.sz * 0.35), 0.14);
			sw.playSound(null, c.x, c.y, c.z, mat.getSoundGroup().getBreakSound(), SoundCategory.BLOCKS, 1.15f, 0.75f);
		}
	}

	/**
	 * Impacto contra el piso: explota, fractura/daña la propia pieza lanzada sin perder bloques ni soltar objetos,
	 * abre un cráter proporcional (Crater.dig) si hay terreno debajo, y lanza la onda expansiva.
	 */
	private void impact(Vec3d vel, FloorHit hit) {
		boolean wasThrown = thrown;
		thrown = false;
		if (!(getWorld() instanceof ServerWorld w)) return;
		BlockView view = GroundRules.loaded(w);
		Vec3d effVel = vel.lengthSquared() < 0.64 && maxFallSpeed > 0.9
			? (vel.lengthSquared() > 1e-6 ? vel.normalize().multiply(maxFallSpeed * 0.85) : new Vec3d(0, -maxFallSpeed * 0.85, 0))
			: vel;
		double speed = Math.max(effVel.length(), wasThrown ? maxFallSpeed * 0.85 : 0);
		double m = massKg(), r = radius();
		double e = blowEnergy(effVel);
		int tier = tierOf(e, r, m);
		if (wasThrown && (speed >= 0.85 || maxFallSpeed >= 1.05)) tier = Math.max(tier, 2);
		double k = kOf(e);
		lastImpactAge = age;
		lastImpactE = e;
		boolean ground = verticalCollision && vel.y < 0;
		Vec3d contact = hit.contact();
		BlockState floor = hit.floor();
		double vh = Math.hypot(effVel.x, effVel.z);
		Vec3d dir = vh > 1e-3 ? new Vec3d(effVel.x / vh, 0, effVel.z / vh) : Vec3d.ZERO;
		double rf = 0.5 * Math.sqrt((double) blocks.sx * blocks.sx + (double) blocks.sz * blocks.sz);
		double R = 0, plow = 0, depth = 0;
		MaterialTrait impactTrait = trait();
		boolean heavy = impactTrait == MaterialTrait.HEAVY_PIERCING;
		BlockPos floorPos = BlockPos.ofFloored(contact.x, contact.y - 0.5, contact.z);
		if (tier >= 2 && !GroundRules.terrain(floor)
			&& (isNaturalTreeOrCanopy(floor) || !GroundRules.solid(floor, view, floorPos))) {
			int span = Math.max(10, blocks.sy + 8);
			int yRef = MathHelper.floor(getY());
			int bestY = GroundRules.NONE;
			BlockPos bestSurf = null;
			for (double dx = -rf; dx <= rf + 0.01; dx += 2.0) {
				int ix = MathHelper.floor(getX() + dx);
				for (double dz = -rf; dz <= rf + 0.01; dz += 2.0) {
					int iz = MathHelper.floor(getZ() + dz);
					int sy = GroundRules.surfaceNear(view, ix, iz, yRef, span);
					if (sy != GroundRules.NONE && (bestSurf == null || sy > bestY)) {
						bestY = sy;
						bestSurf = new BlockPos(ix, sy, iz);
					}
				}
			}
			if (bestSurf != null) {
				floor = view.getBlockState(bestSurf);
				contact = new Vec3d(getX(), bestY + 1.0, getZ());
				floorPos = bestSurf;
			}
		}
		boolean canCrater = tier >= 2 && (GroundRules.terrain(floor) || (heavy && GroundRules.fracturable(floor, view, floorPos, k)));
		if (canCrater) {
			R = MathHelper.clamp(rf * (tier == 2 ? 1.0 + 0.4 * k : 1.15 + 0.6 * k) * (heavy ? 1.1 : 1.0), 1.2, OpeConfig.CRATER_MAX_RADIUS);
			double maxDepth = heavy ? 14.0 : 10.0;
			depth = MathHelper.clamp(R * (tier == 2 ? 0.3 : 0.36) * (0.75 + 0.5 * k) * (heavy ? OpeConfig.HEAVY_DEPTH_MULT : 1.0), 1, maxDepth);
			if (ground && vh > 0.3 * Math.abs(effVel.y)) {
				// rasante: lo que el suelo le deja seguir lo frena el roce (μ 0,6) en unos metros
				double sv = keepOnGround() * vh * 20;
				plow = Math.min(sv * sv / (2 * 0.6 * 9.81), 1.5 * R);
				if (heavy) plow = Math.min(plow * 1.3, 1.5 * R);
			}
		}
		if (!debris && (tier >= 1 || wasThrown)) {
			plowTreeBlocks(w, effVel, blocks.size() >= 18, true);
			clearTreeCanopyAbove(w, BlockPos.ofFloored(contact), Math.max(2.0, R > 0 ? R : r), Math.max(4, blocks.sy + 4), 10);
		}
		DamageResult dmg = null;
		if (!debris && (tier >= 1 || wasThrown)) {
			Vec3d impactDir = effVel.lengthSquared() > 1e-6 ? effVel.normalize() : new Vec3d(0, -1, 0);
			dmg = takeImpactDamage(impactDir, speed, tier, wasThrown);
		}
		if (tier >= 1 || wasThrown) {
			triggerExplosion(w, contact, Math.max(R, r), Math.max(1, tier));
		}
		PieceEntity craterRock = (placed || isRemoved() || blocks == null || blocks.isEmpty()) ? null : this;
		Crater.Result dug = null;
		if (canCrater) {
			dug = Crater.dig(w, craterRock, contact, dir, plow, R, depth, tier, new Crater.CarveRules(heavy, k));
			R = dug.radius();
			plow = dug.plow();
		}
		double fxRad = Math.max(R, r);
		if (dmg != null && !dmg.broken().isEmpty()) {
			spawnDamageDebris(w, dmg, contact, fxRad, effVel);
		}
		if (tier >= 1) shock(w, contact, k, tier, r);
		int prevBlocks = dmg != null ? dmg.prevCount() : blocks.size();
		boolean big = prevBlocks >= 18 || tier >= 2 || r > OpeConfig.TAKT_BIG_RADIUS;
		spawnImpactFx(w, contact, fxRad, speed, floor, big, tier >= 3, impactTrait);
		ElementEffects.onImpact(this, w, impactTrait, prevBlocks, contact, tier, k, fxRad);
		RoomEvents.sendWorldImpact(w, mover(), contact, (float) fxRad,
			(float) MathHelper.clamp(speed * (tier >= 3 ? 1.1 : big ? 0.7 : 0.35), 0.25, 2.5));
		int flags = chunkFx() | (r > OpeConfig.TAKT_BIG_RADIUS ? Payloads.TaktFx.F_BIG : 0);
		int[] data = Payloads.TaktFx.impactData(r, dir, k, Block.getRawIdFromState(floor), plow, dug == null ? 0 : dug.removed());
		UUID mv = mover();
		sendFx(w, Payloads.TaktFx.IMPACT, contact.x, contact.y, contact.z, (float) R, (float) (e / 1e6), flags, tier, data,
			mv != null ? mv : NOBODY);
		if (AUTOTEST) {
			OpeOpeMod.LOG.info("AUTOTEST takt impacto nivel {} cráter {} en {} {} {}, {} t, energía {} MJ, arado {}", tier,
				String.format(Locale.ROOT, "%.2f", R), MathHelper.floor(contact.x), MathHelper.floor(contact.y), MathHelper.floor(contact.z),
				String.format(Locale.ROOT, "%.1f", m / 1000), String.format(Locale.ROOT, "%.1f", e / 1e6), String.format(Locale.ROOT, "%.1f", plow));
		}
		if (craterRock == null) {
			discard();
			return;
		}
		if (dug != null && dug.seated()) return;
		if (dug != null && dug.removed() > 0) {
			// el cráter no la pudo posar: baja lo que le deje el hueco de debajo (nunca dentro de tierra sin cavar; a quien
			// esté debajo place() lo sube encima) y se posa ahí
			BlockPos b = Landing.find(view, blocks, corner(), List.of());
			if (b != null && b.getY() < getY()) setPosition(b.getX() + blocks.sx / 2.0, b.getY(), b.getZ() + blocks.sz / 2.0);
		}
		settle(false);
	}

	/** El cráter le hizo sitio: queda posada con su esquina mínima en base (sus celdas ya están libres). */
	public void seat(BlockPos base) {
		setPosition(base.getX() + blocks.sx / 2.0, base.getY(), base.getZ() + blocks.sz / 2.0);
		setVelocity(Vec3d.ZERO);
		place(base, false, true, false);
	}

	/** Radio de la onda y de lo que tumba, por nivel (el juego: shockRadius y knockdown). */
	private static final double[] SHOCK_R = {0, 5, 11, 26}, KNOCK_R = {0, 0, 6, 13};

	/**
	 * La onda del golpe (blast del juego), más grande cuanto más grande el trozo (r): empuja hacia fuera y hacia arriba a
	 * lo vivo de alrededor y le hace daño, más cerca y más debajo de lo que cae; a los muy cercanos los deja tumbados un
	 * rato (lentitud). A quien lo lanzó no le hace daño: solo lo aparta si está muy cerca.
	 */
	private void shock(ServerWorld w, Vec3d at, double k, int tier, double r) {
		double s = Math.sqrt(MathHelper.clamp(r / 3, 0.5, 4));
		double reach = SHOCK_R[tier] * (0.8 + 0.25 * s), knock = KNOCK_R[tier] * (0.8 + 0.25 * s);
		double dmax = (6 + 80 * Math.pow(k, 1.3)) * OpeConfig.DMG;
		DamageSource src = damageSource();
		for (LivingEntity le : w.getEntitiesByClass(LivingEntity.class, new Box(at, at).expand(reach),
			e -> e.isAlive() && !e.isSpectator())) {
			double dx = le.getX() - at.x, dy = le.getY() - at.y, dz = le.getZ() - at.z;
			double d = Math.sqrt(dx * dx + dy * dy + dz * dz);
			if (d > reach) continue;
			double f = 1 - d / reach, fk = knock > 0 ? Math.max(0, 1 - d / knock) : 0;
			double h = Math.max(0.3, Math.hypot(dx, dz));
			boolean own = spares(le);
			double dv, up;
			if (own) {
				if (fk <= 0) continue;
				dv = 0.3 * fk;
				up = 0.15 * fk;
			} else {
				dv = ((1.5 + 3 * tier) * f * f + 5 * fk) / 20;
				up = (0.7 + fk) * dv * 0.6;
			}
			Vec3d before = le.getVelocity();
			if (!own) {
				double hurt = Math.max(fk, f * 0.3);
				boolean crush = Math.hypot(dx, dz) < r * 0.9 && dy > -1.5 && dy < r * 1.6;
				float dmg = (float) Math.min(30, dmax * hurt * (crush ? 1.6 : 1));
				if (dmg > 0.5f) le.damage(src, dmg);
				if (fk > 0.25) le.addStatusEffect(new StatusEffectInstance(StatusEffects.SLOWNESS, (int) (20 * (0.8 + 1.6 * fk)), 3));
			}
			// el empujón es el de la onda, después del daño y en lugar del retroceso normal del golpe (que lo cambiaría:
			// lo reduce a la mitad y le pone su propio salto), igual con daño que sin él
			le.setVelocity(before.add(dx / h * dv, up, dz / h * dv));
			le.velocityModified = true;
		}
	}

	// ------------------------------------------------------------------ volver a ser bloques (4.7)

	/** Vuelve a ser bloques donde está (redondeando a la rejilla). */
	public void settle(boolean sealed) {
		if (placed || isRemoved() || !(getWorld() instanceof ServerWorld w)) return;
		BlockPos base = BlockPos.ofFloored(getX() - blocks.sx / 2.0 + 0.5, getY() + 0.5, getZ() - blocks.sz / 2.0 + 0.5);
		if (!sealed) base = bestFit(w, base);
		Vec3d c = center();
		sendFx(w, Payloads.TaktFx.SETTLE, c.x, c.y, c.z, (float) radius(), tonnes(),
			(sealed ? Payloads.TaktFx.F_SEALED : 0) | chunkFx(), blocks.size(), new int[0], ownerOr());
		place(base, sealed, true, false);
	}

	/**
	 * Al redondear a la rejilla, elige entre 20 huecos cercanos el que menos bloques de la pieza deja sin sitio (sin
	 * bloque y sin nadie de pie), mejor apoyado en algo, y luego el más cercano; con empate gana el primero probado.
	 */
	private BlockPos bestFit(ServerWorld w, BlockPos b0) {
		BlockView view = GroundRules.loaded(w);
		List<Box> bl = Landing.blockers(w, cellsBox(b0).expand(3));
		BlockPos.Mutable m = new BlockPos.Mutable();
		BlockPos best = b0;
		int bestScore = Integer.MAX_VALUE;
		for (int i = 0; i < FIT_DY.length; i++) {
			for (int[] xz : FIT_XZ) {
				int score = FIT_DY_COST[i] + Math.abs(xz[0]) + Math.abs(xz[1]);
				if (score >= bestScore) continue;
				BlockPos b = b0.add(xz[0], FIT_DY[i], xz[1]);
				if (!Landing.supported(view, blocks, b)) score += 50;
				if (score >= bestScore) continue;
				for (BlockSet.Entry e : blocks.entries) {
					m.set(b.getX() + e.x(), b.getY() + e.y(), b.getZ() + e.z());
					if (Landing.free(view, m, bl)) continue;
					score += 100;
					if (score >= bestScore) break;
				}
				if (score < bestScore) {
					bestScore = score;
					best = b;
				}
			}
		}
		return best;
	}

	public void settleAt(BlockPos base, boolean sealed) {
		place(base, sealed, true, false);
	}

	/**
	 * Pone los bloques con su esquina mínima en base, una sola vez. Lo que no cabe en su celda (hay un bloque o alguien
	 * de pie) va a la celda libre más cercana; solo si no hay ninguna en 2 bloques cae como objeto. Lo vivo que quede
	 * dentro de un bloque sube al primer hueco. La entidad sigue TAKT_LANDED_TAIL ticks (la cola) y desaparece.
	 * legacy: las partículas de siempre (sellada: destellos y campanilla; si no, polvo). quiet: ni sonido ni partículas.
	 */
	private void place(BlockPos base, boolean sealed, boolean legacy, boolean quiet) {
		if (placed || isRemoved() || !(getWorld() instanceof ServerWorld w)) return;
		placed = true;
		BlockView view = GroundRules.loaded(w);
		liftLiving(w, base);   // antes de mirar quién estorba: quien esté donde va la pieza sube encima y no queda encerrado
		List<Box> bl = Landing.blockers(w, cellsBox(base).expand(3));
		int top = base.getY() + blocks.sy;
		LongOpenHashSet start = new LongOpenHashSet(blocks.size() * 2);
		LongOpenHashSet taken = new LongOpenHashSet(blocks.size() * 2);
		BlockPos.Mutable m = new BlockPos.Mutable();
		for (BlockSet.Entry e : blocks.entries) {
			m.set(base.getX() + e.x(), base.getY() + e.y(), base.getZ() + e.z());
			if (!Landing.free(view, m, bl)) continue;
			start.add(m.asLong());
			taken.add(m.asLong());
		}
		List<BlockSet.Entry> sorted = new ArrayList<>(blocks.entries);
		sorted.sort(Comparator.comparingInt(BlockSet.Entry::y));
		LongOpenHashSet hard = new LongOpenHashSet();
		int x0 = Integer.MAX_VALUE, y0 = Integer.MAX_VALUE, z0 = Integer.MAX_VALUE;
		int x1 = Integer.MIN_VALUE, y1 = Integer.MIN_VALUE, z1 = Integer.MIN_VALUE;
		BlockState sample = null;
		for (BlockSet.Entry e : sorted) {
			BlockPos cell = base.add(e.x(), e.y(), e.z());
			BlockPos put = start.contains(cell.asLong()) ? cell : relocate(view, cell, bl, taken, top);
			if (put == null) {
				Block.dropStacks(e.state(), w, cell);   // último recurso: no hay ni una celda libre cerca
				DROPPED++;
				continue;
			}
			if (!sealed && lastImpactAge >= 0) {
				BlockPos down = put;
				for (int step = 0; step < 24 && down.getY() > w.getBottomY() + 1; step++) {
					BlockPos below = down.down();
					if (taken.contains(below.asLong()) || !freeForImpactSettle(view, below, bl)) break;
					down = below;
				}
				if (!down.equals(put)) {
					taken.remove(put.asLong());
					taken.add(down.asLong());
					put = down;
				}
			}
			w.setBlockState(put, e.state(), Block.NOTIFY_ALL);
			if (e.be() != null) {
				BlockEntity be = w.getBlockEntity(put);
				if (be != null) {
					be.read(e.be(), w.getRegistryManager());
					be.markDirty();
				}
			}
			if (sample == null) sample = e.state();
			if (!w.getBlockState(put).getCollisionShape(w, put).isEmpty()) {
				hard.add(put.asLong());
				x0 = Math.min(x0, put.getX());
				y0 = Math.min(y0, put.getY());
				z0 = Math.min(z0, put.getZ());
				x1 = Math.max(x1, put.getX());
				y1 = Math.max(y1, put.getY());
				z1 = Math.max(z1, put.getZ());
			}
		}
		if (!quiet) placeFx(w, sample, sealed, legacy);
		if (!hard.isEmpty()) pushOut(w, hard, new Box(x0, y0, z0, x1 + 1, y1 + 1, z1 + 1));
		gravityMul = 1.0;
		resetMotion();
		solid = false;
		target = null;
		setVelocity(Vec3d.ZERO);
		setFlags(F_LANDED, F_HELD);
		landedTail = OpeConfig.TAKT_LANDED_TAIL;
		mode = Mode.LANDING;
		Takt.forget(getId());
	}

	/**
	 * La celda libre más cercana a cell (orden RELOCATE) que nadie más va a ocupar. Si no hay ninguna en 2 bloques, la
	 * primera libre de su columna por encima de la pieza (desde top, hasta 16 más arriba, sin atravesar un techo); null
	 * si tampoco.
	 */
	@Nullable
	private static BlockPos relocate(BlockView view, BlockPos cell, List<Box> bl, LongOpenHashSet taken, int top) {
		BlockPos.Mutable q = new BlockPos.Mutable();
		for (int[] o : RELOCATE) {
			q.set(cell.getX() + o[0], cell.getY() + o[1], cell.getZ() + o[2]);
			long key = q.asLong();
			if (taken.contains(key) || !Landing.free(view, q, bl)) continue;
			taken.add(key);
			RELOCATED++;
			return q.toImmutable();
		}
		for (int y = Math.max(cell.getY() + 3, top); y <= top + 16; y++) {
			q.set(cell.getX(), y, cell.getZ());
			long key = q.asLong();
			if (taken.contains(key)) continue;
			if (!GroundRules.replaceable(view.getBlockState(q))) break;   // un techo: más arriba ya es otro sitio
			if (!Landing.free(view, q, bl)) continue;
			taken.add(key);
			RELOCATED++;
			return q.toImmutable();
		}
		return null;
	}

	private static boolean freeForImpactSettle(BlockView view, BlockPos cell, List<Box> bl) {
		int x = cell.getX(), y = cell.getY(), z = cell.getZ();
		if (y < view.getBottomY() || y >= view.getTopY()) return false;
		BlockState st = view.getBlockState(cell);
		if (!GroundRules.replaceable(st) && !isNaturalTreeOrCanopy(st)) return false;
		for (Box b : bl) {
			if (b.intersects(x + 0.01, y + 0.01, z + 0.01, x + 0.99, y + 0.99, z + 0.99)) return false;
		}
		return true;
	}

	/**
	 * Lo vivo que está donde va a quedar la pieza sube encima de ella antes de poner los bloques (hasta 8 bloques por
	 * encima de su cara de arriba), con quien lo monte: nadie queda encerrado en un hueco, ni se queda sin sitio el bloque
	 * de su celda. Si arriba no hay dónde, o va montado en algo (una barca, un carrito: su montura lo volvería a poner en
	 * su asiento), se queda donde está y sus celdas se quedan sin bloque, como siempre.
	 */
	private void liftLiving(ServerWorld w, BlockPos base) {
		List<LivingEntity> in = w.getEntitiesByClass(LivingEntity.class, cellsBox(base), e -> e.isAlive() && !e.isSpectator());
		if (in.isEmpty()) return;
		LongOpenHashSet foot = new LongOpenHashSet(blocks.size() * 2);
		for (BlockSet.Entry e : blocks.entries) foot.add(BlockPos.asLong(base.getX() + e.x(), base.getY() + e.y(), base.getZ() + e.z()));
		int top = base.getY() + blocks.sy;
		for (LivingEntity le : in) {
			if (le.hasVehicle()) continue;
			Box bb = le.getBoundingBox().expand(-1e-4);
			if (!overlaps(bb, foot)) continue;
			for (int y2 = MathHelper.floor(le.getY()) + 1; y2 <= top + 8; y2++) {
				double dy = y2 - le.getY();
				Box at = bb.offset(0, dy, 0);
				if (overlaps(at, foot) || !w.isSpaceEmpty(le, at) || !ridersFit(w, le, dy, foot)) continue;
				le.requestTeleport(le.getX(), y2, le.getZ());   // lleva consigo a quien lo monta
				le.fallDistance = 0;
				for (Entity r : le.getPassengersDeep()) r.fallDistance = 0;
				break;
			}
		}
	}

	/** ¿Caben quienes montan a e (y quienes montan a esos) subidos dy: fuera de la pieza y sin bloques? */
	private static boolean ridersFit(ServerWorld w, Entity e, double dy, LongOpenHashSet foot) {
		for (Entity r : e.getPassengersDeep()) {
			Box b = r.getBoundingBox().expand(-1e-4).offset(0, dy, 0);
			if (overlaps(b, foot) || !w.isSpaceEmpty(r, b)) return false;
		}
		return true;
	}

	/** Sonido del material y, en los asentamientos de siempre, sus partículas. */
	private void placeFx(ServerWorld w, @Nullable BlockState mat, boolean sealed, boolean legacy) {
		Vec3d c = center();
		Box b = getBoundingBox();
		if (mat != null) w.playSound(null, c.x, c.y, c.z, mat.getSoundGroup().getPlaceSound(), SoundCategory.BLOCKS, 1.0f, 0.7f);
		if (!legacy) return;
		if (sealed) {
			w.spawnParticles(ParticleTypes.END_ROD, c.x, c.y, c.z, 30, b.getLengthX() / 2.5, b.getLengthY() / 2.5, b.getLengthZ() / 2.5, 0.02);
			w.playSound(null, c.x, c.y, c.z, SoundEvents.BLOCK_AMETHYST_BLOCK_CHIME, SoundCategory.PLAYERS, 1.0f, 0.8f);
		} else if (mat != null) {
			w.spawnParticles(new BlockStateParticleEffect(ParticleTypes.BLOCK, mat), c.x, getY(), c.z, 40,
				b.getLengthX() / 3, 0.2, b.getLengthZ() / 3, 0.1);
		}
	}

	/** Lo vivo que quedó dentro de un bloque recién puesto sube al primer hueco libre (como mucho 8 bloques más arriba). */
	private static void pushOut(ServerWorld w, LongOpenHashSet hard, Box area) {
		for (LivingEntity le : w.getEntitiesByClass(LivingEntity.class, area, e -> e.isAlive() && !e.isSpectator())) {
			if (!overlaps(le.getBoundingBox().expand(-1e-4), hard)) continue;
			for (int k = 1; k <= 8; k++) {
				double y2 = Math.floor(le.getY()) + k;
				if (w.isSpaceEmpty(le, le.getBoundingBox().offset(new Vec3d(0, y2 - le.getY(), 0)))) {
					le.requestTeleport(le.getX(), y2, le.getZ());
					break;
				}
			}
		}
	}

	/** ¿Toca la caja alguna de esas celdas? */
	private static boolean overlaps(Box b, LongOpenHashSet cells) {
		int x0 = MathHelper.floor(b.minX), x1 = MathHelper.floor(b.maxX);
		int y0 = MathHelper.floor(b.minY), y1 = MathHelper.floor(b.maxY);
		int z0 = MathHelper.floor(b.minZ), z1 = MathHelper.floor(b.maxZ);
		for (int x = x0; x <= x1; x++) {
			for (int y = y0; y <= y1; y++) {
				for (int z = z0; z <= z1; z++) {
					if (cells.contains(BlockPos.asLong(x, y, z))) return true;
				}
			}
		}
		return false;
	}

	/** La caja de las celdas de la pieza con su esquina mínima en base. */
	private Box cellsBox(BlockPos base) {
		return new Box(base.getX(), base.getY(), base.getZ(), base.getX() + blocks.sx, base.getY() + blocks.sy, base.getZ() + blocks.sz);
	}

	private static int[][] relocateOffsets() {
		List<int[]> out = new ArrayList<>(124);
		for (int x = -2; x <= 2; x++) {
			for (int y = -2; y <= 2; y++) {
				for (int z = -2; z <= 2; z++) {
					if (x != 0 || y != 0 || z != 0) out.add(new int[]{x, y, z});
				}
			}
		}
		out.sort(Comparator.<int[]>comparingInt(o -> o[0] * o[0] + o[1] * o[1] + o[2] * o[2])
			.thenComparingInt(o -> -o[1]).thenComparingInt(o -> o[0]).thenComparingInt(o -> o[2]));
		return out.toArray(new int[0][]);
	}

	// ------------------------------------------------------------------ avisos a los clientes

	private void sendFx(ServerWorld w, int kind, double x, double y, double z, float a, float b, int flags, int state, int[] data, UUID who) {
		Takt.sendFx(w, new Payloads.TaktFx(kind, 0, getId(), who, x, y, z, a, b, 0, flags, state, data));
	}

	private UUID ownerOr() {
		return owner != null ? owner : NOBODY;
	}

	/** F_CHUNK del aviso si la pieza se arrancó del suelo. */
	private int chunkFx() {
		return (flags() & F_CHUNK) != 0 ? Payloads.TaktFx.F_CHUNK : 0;
	}

	private float tonnes() {
		return (float) (massKg() / 1000.0);
	}

	private static double smoothstep(double u) {
		u = MathHelper.clamp(u, 0, 1);
		return u * u * (3 - 2 * u);
	}

	private static double easeInOutCubic(double k) {
		return k < 0.5 ? 4 * k * k * k : 1 - Math.pow(-2 * k + 2, 3) / 2;
	}

	// ------------------------------------------------------------------ armas forjadas

	private void weaponTick() {
		ServerPlayerEntity p = ownerPlayer();
		if (p == null || p.getWorld() != getWorld()) {
			if (wState == 0 || weapon == Weapon.WALL) becomePlain();
			else if (++wTicks > 200) becomePlain();
			return;
		}
		Vec3d eye = p.getEyePos();
		Vec3d look = p.getRotationVector();
		Vec3d right = look.crossProduct(new Vec3d(0, 1, 0));
		if (right.lengthSquared() < 1e-4) right = new Vec3d(1, 0, 0);
		right = right.normalize();
		Vec3d up = right.crossProduct(look).normalize();
		double len = blocks.sz;
		switch (weapon) {
			case LANCE -> lanceTick(p, eye, look, right, up, len);
			case BLADE -> bladeTick(p, eye, look, right, up, len);
			case HAMMER -> hammerTick(p, eye, look, right, up, len);
			case STAKE -> stakeTick(p, eye, len);
			case WALL -> wallTick(p, look);
		}
		if (wState == 1 && getWorld() instanceof ServerWorld sw) {
			ElementEffects.weaponTrailFx(this, sw);
		}
	}

	/** Deja de ser un arma: cae y se asienta como bloques. */
	public void becomePlain() {
		weapon = null;
		impaled = -1;
		piercedIds.clear();
		setFlag(F_WEAPON, false);
		setYaw(0);
		setPitch(0);
		Vec3d c = getPos();
		setPosition(c.x, c.y - blocks.sy / 2.0, c.z);
		noClip = false;
		setNoGravity(false);
		resetMotion();
		release(Vec3d.ZERO, false);
	}

	public void use(ServerPlayerEntity p) {
		if (wState == 1) return;
		if (weapon == Weapon.STAKE) {
			wState = 1;
			wTicks = 0;
			impaled = -1;
			piercedIds.clear();
			hitCooldown.clear();
			LivingEntity foe = nearestFoe(p, 30);
			if (foe != null && wIndex % 2 == 0) {
				wTarget = foe.getId();
			} else {
				Vec3d aim = PowerManager.aimPoint(p, 80);
				double spread = 3;
				Vec3d off = new Vec3d((random.nextDouble() - 0.5) * spread, 0, (random.nextDouble() - 0.5) * spread);
				wVel = aim.add(off).subtract(getPos()).normalize().multiply(2.6);
				wTarget = -1;
			}
			return;
		}
		if (weapon == Weapon.LANCE) {
			wVel = p.getRotationVector().multiply(3.2);
			getWorld().playSound(null, getX(), getY(), getZ(), SoundEvents.ENTITY_SNOWBALL_THROW, SoundCategory.PLAYERS, 1.2f, 0.5f);
		}
		if (weapon == Weapon.WALL) {
			Vec3d look = p.getRotationVector();
			Vec3d flat = new Vec3d(look.x, 0, look.z);
			if (flat.lengthSquared() < 1e-4) flat = new Vec3d(0, 0, 1);
			wVel = flat.normalize().multiply(0.9);
			solid = false;
		}
		wState = 1;
		wTicks = 0;
		impaled = -1;
		piercedIds.clear();
		hitCooldown.clear();
	}

	private LivingEntity nearestFoe(ServerPlayerEntity p, double r) {
		LivingEntity best = null;
		double bestD = r * r;
		for (LivingEntity le : getWorld().getEntitiesByClass(LivingEntity.class, p.getBoundingBox().expand(r),
			e -> e.isAlive() && e != p && e instanceof net.minecraft.entity.mob.Monster)) {
			double d = le.squaredDistanceTo(p);
			if (d < bestD) {
				bestD = d;
				best = le;
			}
		}
		return best;
	}

	private void face(Vec3d dir) {
		double h = Math.sqrt(dir.x * dir.x + dir.z * dir.z);
		setYaw((float) (MathHelper.atan2(-dir.x, dir.z) * MathHelper.DEGREES_PER_RADIAN));
		setPitch((float) (MathHelper.atan2(-dir.y, h) * MathHelper.DEGREES_PER_RADIAN));
	}

	private boolean strike(LivingEntity le, float dmg, Vec3d push) {
		Integer until = hitCooldown.get(le.getId());
		if (until != null && until > age) return false;
		float finalDmg = dmg * ElementEffects.weaponDamageMult(this, le);
		le.damage(damageSource(), finalDmg);
		ElementEffects.onWeaponStrike(this, le, finalDmg);
		double kbScale = trait() == MaterialTrait.HEAVY_PIERCING ? 1.3 : 1.0;
		le.addVelocity(push.x * kbScale, push.y * kbScale, push.z * kbScale);
		le.velocityModified = true;
		hitCooldown.put(le.getId(), age + 20);
		return true;
	}

	private void lanceTick(ServerPlayerEntity p, Vec3d eye, Vec3d look, Vec3d right, Vec3d up, double len) {
		if (wState == 0) {
			setPosition(eye.add(look.multiply(len / 2 + 0.6)).add(right.multiply(0.8)).subtract(up.multiply(0.35)));
			setYaw(p.getYaw());
			setPitch(p.getPitch());
			return;
		}
		if (wState == 2) {
			pinImpaled(look);
			if (++stuckTicks > 20 * 30) becomePlain();
			return;
		}
		wTicks++;
		Vec3d dir = wVel.normalize();
		face(dir);
		Vec3d tip = getPos().add(dir.multiply(len / 2));
		Vec3d next = tip.add(wVel);
		BlockHitResult bhr = getWorld().raycast(new RaycastContext(tip, next, RaycastContext.ShapeType.COLLIDER, RaycastContext.FluidHandling.NONE, this));
		Vec3d end = bhr.getType() == HitResult.Type.MISS ? next : bhr.getPos();
		if (trait() == MaterialTrait.HEAVY_PIERCING && impaled == -1) {
			List<LivingEntity> hits = getWorld().getEntitiesByClass(LivingEntity.class, new Box(tip, end).expand(1.1),
				e -> e.isAlive() && e != p && !piercedIds.contains(e.getId()));
			hits.sort(Comparator.comparingDouble(e -> e.squaredDistanceTo(tip)));
			for (LivingEntity le : hits) {
				if (le.getBoundingBox().expand(0.95).raycast(tip, end).isEmpty() && !le.getBoundingBox().expand(1.0).contains(tip)) continue;
				piercedIds.add(le.getId());
				strike(le, 14f, dir.multiply(0.5));
				getWorld().playSound(null, le.getX(), le.getY(), le.getZ(), SoundEvents.ENTITY_ARROW_HIT_PLAYER, SoundCategory.PLAYERS, 1f, 0.55f);
				if (piercedIds.size() >= OpeConfig.HEAVY_PIERCE_COUNT) {
					impaled = le.getId();
					break;
				}
			}
		} else {
			EntityHitResult ehr = ProjectileUtil.getEntityCollision(getWorld(), this, tip, end, new Box(tip, end).expand(1.0),
				e -> e instanceof LivingEntity && e.isAlive() && e != p && e.getId() != impaled && !piercedIds.contains(e.getId()));
			if (ehr != null && impaled == -1 && ehr.getEntity() instanceof LivingEntity le) {
				piercedIds.add(le.getId());
				strike(le, 14f, dir.multiply(0.5));
				impaled = le.getId();
				getWorld().playSound(null, le.getX(), le.getY(), le.getZ(), SoundEvents.ENTITY_ARROW_HIT_PLAYER, SoundCategory.PLAYERS, 1f, 0.6f);
			}
		}
		setPosition(end.subtract(dir.multiply(len / 2)));
		pinImpaled(dir);
		if (bhr.getType() != HitResult.Type.MISS) {
			wState = 2;
			stuckTicks = 0;
			getWorld().playSound(null, end.x, end.y, end.z, SoundEvents.BLOCK_ANVIL_LAND, SoundCategory.PLAYERS, 0.7f, 1.4f);
		} else if (wTicks > 60) {
			becomePlain();
		}
	}

	private void pinImpaled(Vec3d dir) {
		if (impaled < 0) return;
		Entity e = getWorld().getEntityById(impaled);
		if (!(e instanceof LivingEntity le) || !le.isAlive() || stuckTicks > 20 * 8) {
			impaled = -1;
			return;
		}
		Vec3d tip = getPos().add(dir.multiply(blocks.sz / 2.0 - 0.6));
		le.requestTeleport(tip.x, tip.y - le.getHeight() / 2, tip.z);
		le.setVelocity(Vec3d.ZERO);
		le.addStatusEffect(new StatusEffectInstance(ModEffects.STUN, 10, 0, false, false));
		ElementEffects.onImpaled(this, le, stuckTicks);
	}

	private void bladeTick(ServerPlayerEntity p, Vec3d eye, Vec3d look, Vec3d right, Vec3d up, double len) {
		if (wState == 0) {
			setPosition(eye.add(look.multiply(len / 2 + 0.3)).add(right.multiply(1.4)).subtract(up.multiply(0.25)));
			setYaw(p.getYaw() + 25);
			setPitch(p.getPitch());
			return;
		}
		wTicks++;
		float a = -80 + 160f * Math.min(wTicks, 8) / 8f;
		float yaw = p.getYaw() + a;
		Vec3d dir = Vec3d.fromPolar(0, yaw);
		setPosition(eye.add(dir.multiply(len / 2 + 0.5)).subtract(0, 0.3, 0));
		setYaw(yaw);
		setPitch(0);
		double reach = len + 1.5;
		for (LivingEntity le : getWorld().getEntitiesByClass(LivingEntity.class, p.getBoundingBox().expand(reach),
			e -> e.isAlive() && e != p)) {
			Vec3d to = le.getPos().subtract(p.getPos());
			if (to.horizontalLength() > reach) continue;
			float ang = (float) (MathHelper.atan2(-to.x, to.z) * MathHelper.DEGREES_PER_RADIAN);
			if (Math.abs(MathHelper.wrapDegrees(ang - yaw)) < 22) strike(le, 10f, dir.multiply(1.2).add(0, 0.3, 0));
		}
		if (wTicks == 4) {
			PlayerPower pw = PowerManager.get(p);
			Room room = pw.room;
			if (room != null && room.contains(p.getPos())) {
				Vec3d point = eye.subtract(0, 0.3, 0);
				CutEngine.Region region = CutEngine.Region.of(room).withEye(eye, look).withLimit(eye, reach + 1);
				CutEngine.cut(p, List.of(new CutEngine.Plane(new Vec3d(0, 1, 0), point)), region, pw.suspend, 0, 0f);
			}
			getWorld().playSound(null, eye.x, eye.y, eye.z, SoundEvents.ENTITY_PLAYER_ATTACK_SWEEP, SoundCategory.PLAYERS, 1.5f, 0.5f);
		}
		if (wTicks >= 9) wState = 0;
	}

	private void hammerTick(ServerPlayerEntity p, Vec3d eye, Vec3d look, Vec3d right, Vec3d up, double len) {
		Vec3d shoulder = eye.add(right.multiply(0.9)).subtract(0, 0.2, 0);
		float pitch;
		if (wState == 0) {
			pitch = -60;
		} else {
			wTicks++;
			if (wTicks <= 6) pitch = -60 - 30f * wTicks / 6f;
			else pitch = -90 + 165f * Math.min(1f, (wTicks - 6) / 4f);
		}
		Vec3d dir = Vec3d.fromPolar(pitch, p.getYaw());
		setPosition(shoulder.add(dir.multiply(len / 2)));
		setYaw(p.getYaw());
		setPitch(pitch);
		if (wState == 1 && wTicks == 10) {
			Vec3d flat = new Vec3d(look.x, 0, look.z);
			if (flat.lengthSquared() < 1e-4) flat = new Vec3d(0, 0, 1);
			Vec3d hit = p.getPos().add(flat.normalize().multiply(len));
			ServerWorld w = (ServerWorld) getWorld();
			BlockState mat = blocks.dominant();
			if (mat != null) w.spawnParticles(new BlockStateParticleEffect(ParticleTypes.BLOCK, mat), hit.x, hit.y + 0.2, hit.z, 120, 2.5, 0.3, 2.5, 0.25);
			w.spawnParticles(ParticleTypes.EXPLOSION, hit.x, hit.y + 0.5, hit.z, 3, 1.5, 0.2, 1.5, 0);
			w.playSound(null, hit.x, hit.y, hit.z, SoundEvents.BLOCK_ANVIL_LAND, SoundCategory.PLAYERS, 2f, 0.5f);
			w.playSound(null, hit.x, hit.y, hit.z, SoundEvents.ENTITY_IRON_GOLEM_ATTACK, SoundCategory.PLAYERS, 2f, 0.5f);
			double r = 6 + Math.cbrt(blocks.size());
			for (Entity e : w.getOtherEntities(p, new Box(hit, hit).expand(r, 3, r))) {
				if (e == this) continue;
				double d = e.getPos().distanceTo(hit);
				if (d > r) continue;
				double k = 1 - d / r;
				if (e instanceof LivingEntity le) {
					strike(le, (float) (6 + 10 * k), e.getPos().subtract(hit).normalize().multiply(0.6 * k).add(0, 0.5 + 0.6 * k, 0));
				} else if (e instanceof ItemEntity || e instanceof PieceEntity pe && pe.mode == Mode.FREE) {
					e.addVelocity(0, 0.6 * k + 0.2, 0);
					e.velocityModified = true;
				}
			}
			ElementEffects.onHammerSlam(this, w, hit, r);
			boolean heavy = trait() == MaterialTrait.HEAVY_PIERCING;
			if (heavy || OpeConfig.HAMMER_CRATER_ALL) {
				BlockHitResult gb = w.raycast(new RaycastContext(hit.add(0, 2.0, 0), hit.add(0, -6.0, 0),
					RaycastContext.ShapeType.COLLIDER, RaycastContext.FluidHandling.NONE, this));
				Vec3d contact = gb.getType() != HitResult.Type.MISS ? gb.getPos() : hit;
				double kHit = 0.65;
				double hR = MathHelper.clamp(1.2 + 0.8 * Math.cbrt(blocks.size()), 1.2, 5.0);
				double hDepth = MathHelper.clamp(hR * 0.30 * (0.75 + 0.5 * kHit) * (heavy ? OpeConfig.HEAVY_DEPTH_MULT : 1.0),
					1.0, heavy ? 14.0 : 10.0);
				Crater.dig(w, null, contact, Vec3d.ZERO, 0.0, hR, hDepth, 2, new Crater.CarveRules(heavy, kHit));
			}
		}
		if (wState == 1 && wTicks >= 14) wState = 0;
	}

	private void stakeTick(ServerPlayerEntity p, Vec3d eye, double len) {
		if (wState == 0) {
			double ang = age * 0.06 + wIndex * Math.PI * 2 / Math.max(1, wCount);
			setPosition(p.getPos().add(Math.cos(ang) * 1.6, 3.4 + len / 2, Math.sin(ang) * 1.6));
			setYaw(0);
			setPitch(90);
			return;
		}
		if (wState == 2) {
			if (impaled >= 0) {
				Entity e = getWorld().getEntityById(impaled);
				if (e instanceof LivingEntity le && le.isAlive()) {
					setPosition(le.getBoundingBox().getCenter());
					ElementEffects.onImpaled(this, le, stuckTicks);
				} else {
					impaled = -1;
				}
			}
			if (++stuckTicks > 20 * 10) {
				if (getWorld() instanceof ServerWorld w && blocks.dominant() != null)
					w.spawnParticles(new BlockStateParticleEffect(ParticleTypes.BLOCK, blocks.dominant()), getX(), getY(), getZ(), 20, 0.3, 0.6, 0.3, 0.1);
				discard();
			}
			return;
		}
		wTicks++;
		if (wTarget >= 0) {
			Entity t = getWorld().getEntityById(wTarget);
			if (t != null && t.isAlive()) wVel = t.getBoundingBox().getCenter().subtract(getPos()).normalize().multiply(2.6);
		}
		Vec3d dir = wVel.normalize();
		face(dir);
		Vec3d tip = getPos().add(dir.multiply(len / 2));
		Vec3d next = tip.add(wVel);
		BlockHitResult bhr = getWorld().raycast(new RaycastContext(tip, next, RaycastContext.ShapeType.COLLIDER, RaycastContext.FluidHandling.NONE, this));
		Vec3d end = bhr.getType() == HitResult.Type.MISS ? next : bhr.getPos();
		EntityHitResult ehr = ProjectileUtil.getEntityCollision(getWorld(), this, tip, end, new Box(tip, end).expand(0.8),
			e -> e instanceof LivingEntity && e.isAlive() && e != p && !piercedIds.contains(e.getId()));
		setPosition(end.subtract(dir.multiply(len / 2)));
		if (ehr != null && ehr.getEntity() instanceof LivingEntity le) {
			piercedIds.add(le.getId());
			strike(le, 8f, dir.multiply(0.4));
			boolean keepFlying = trait() == MaterialTrait.HEAVY_PIERCING && piercedIds.size() < 2;
			if (keepFlying) {
				wTarget = -1;
			} else {
				impaled = le.getId();
				wState = 2;
				stuckTicks = 0;
			}
		} else if (bhr.getType() != HitResult.Type.MISS || wTicks > 50) {
			wState = 2;
			stuckTicks = 0;
		}
	}

	private void wallTick(ServerPlayerEntity p, Vec3d look) {
		if (wState == 0) {
			solid = true;
			setVelocity(Vec3d.ZERO);
			ElementEffects.onWallPlantedTick(this, p);
			if (++stuckTicks > 20 * 60) {
				weapon = null;
				settle(true);
			}
			return;
		}
		wTicks++;
		solid = false;
		Vec3d before = getPos();
		move(MovementType.SELF, wVel);
		Box sweep = getBoundingBox().union(getBoundingBox().offset(wVel.multiply(1.5)));
		for (LivingEntity le : getWorld().getEntitiesByClass(LivingEntity.class, sweep, e -> e.isAlive() && e != p)) {
			strike(le, 4f, wVel.multiply(2.2).add(0, 0.5, 0));
		}
		if (wTicks >= 12 || getPos().squaredDistanceTo(before) < 0.01) {
			wState = 0;
			stuckTicks = 0;
			solid = true;
		}
	}

	// ------------------------------------------------------------------ meteorito / árboles / daño propio

	private static boolean isWoodBlock(BlockState st) {
		return st.isIn(BlockTags.LOGS) || st.isIn(BlockTags.PLANKS) || st.isIn(BlockTags.FENCES)
			|| st.isIn(BlockTags.FENCE_GATES) || st.isIn(BlockTags.WOODEN_DOORS)
			|| st.isOf(Blocks.MANGROVE_ROOTS) || st.isOf(Blocks.MUDDY_MANGROVE_ROOTS) || st.isOf(Blocks.BEE_NEST);
	}

	private static boolean isFoliageBlock(BlockState st) {
		return st.isIn(BlockTags.LEAVES) || st.isIn(BlockTags.SAPLINGS) || st.isIn(BlockTags.FLOWERS)
			|| st.isOf(Blocks.BAMBOO) || st.isOf(Blocks.VINE) || st.isOf(Blocks.CAVE_VINES) || st.isOf(Blocks.CAVE_VINES_PLANT)
			|| st.isOf(Blocks.WEEPING_VINES) || st.isOf(Blocks.WEEPING_VINES_PLANT) || st.isOf(Blocks.TWISTING_VINES)
			|| st.isOf(Blocks.TWISTING_VINES_PLANT) || st.isOf(Blocks.BROWN_MUSHROOM_BLOCK)
			|| st.isOf(Blocks.RED_MUSHROOM_BLOCK) || st.isOf(Blocks.MUSHROOM_STEM) || st.isOf(Blocks.COCOA)
			|| st.isOf(Blocks.SUGAR_CANE) || st.isOf(Blocks.CACTUS) || st.isOf(Blocks.AZALEA)
			|| st.isOf(Blocks.FLOWERING_AZALEA) || st.isOf(Blocks.MOSS_CARPET) || st.isOf(Blocks.BIG_DRIPLEAF)
			|| st.isOf(Blocks.BIG_DRIPLEAF_STEM) || st.isOf(Blocks.SMALL_DRIPLEAF);
	}

	private static boolean isNaturalTreeOrCanopy(BlockState st) {
		return isFoliageBlock(st) || st.isIn(BlockTags.LOGS) || st.isOf(Blocks.MANGROVE_ROOTS)
			|| st.isOf(Blocks.MUDDY_MANGROVE_ROOTS) || st.isOf(Blocks.BEE_NEST);
	}

	private static boolean isTreeBlock(BlockState st) {
		return isWoodBlock(st) || isFoliageBlock(st);
	}

	private void plowTreeBlocks(ServerWorld sw, Vec3d moveVec, boolean spawnFalling, boolean includeWood) {
		Vec3d stepVec = moveVec.length() > 4.5 ? moveVec.normalize().multiply(4.5) : moveVec;
		Box scan = getBoundingBox().stretch(stepVec).expand(0.5, 0.8, 0.5);
		int minX = MathHelper.floor(scan.minX), maxX = MathHelper.floor(scan.maxX);
		int minY = Math.max(sw.getBottomY(), MathHelper.floor(scan.minY));
		int maxY = Math.min(sw.getTopY() - 1, MathHelper.floor(scan.maxY));
		int minZ = MathHelper.floor(scan.minZ), maxZ = MathHelper.floor(scan.maxZ);
		int broken = 0, fxCount = 0;
		BlockPos.Mutable m = new BlockPos.Mutable();
		for (int y = minY; y <= maxY; y++) {
			for (int x = minX; x <= maxX; x++) {
				for (int z = minZ; z <= maxZ; z++) {
					m.set(x, y, z);
					BlockState st = sw.getBlockState(m);
					if (st.hasBlockEntity()) continue;
					if (!(isFoliageBlock(st) || (includeWood && isNaturalTreeOrCanopy(st)))) continue;
					broken++;
					BlockPos pos = m.toImmutable();
					if (fxCount < 22) {
						fxCount++;
						sw.spawnParticles(new BlockStateParticleEffect(ParticleTypes.BLOCK, st),
							pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5, 4, 0.25, 0.25, 0.25, 0.14);
					}
					sw.setBlockState(pos, Blocks.AIR.getDefaultState(), Block.NOTIFY_LISTENERS | Block.SKIP_DROPS);
				}
			}
		}
		if (broken > 0) {
			sw.playSound(null, getX(), getY(), getZ(), SoundEvents.BLOCK_WOOD_BREAK, SoundCategory.BLOCKS,
				Math.min(1.6f, 0.55f + broken * 0.04f), 0.78f + sw.random.nextFloat() * 0.25f);
		}
	}

	private void clearTreeCanopyAbove(ServerWorld sw, BlockPos centerPos, double rad, int maxRise, int maxLogsFalling) {
		int r = MathHelper.clamp((int) Math.ceil(rad + 2), 2, 20);
		int rise = MathHelper.clamp(maxRise, 4, 24);
		int minY = Math.max(sw.getBottomY(), centerPos.getY() - 1);
		int maxY = Math.min(sw.getTopY() - 1, centerPos.getY() + rise);
		int broken = 0, fxCount = 0;
		BlockPos.Mutable m = new BlockPos.Mutable();
		for (int x = centerPos.getX() - r; x <= centerPos.getX() + r; x++) {
			for (int z = centerPos.getZ() - r; z <= centerPos.getZ() + r; z++) {
				int dx = x - centerPos.getX(), dz = z - centerPos.getZ();
				if (dx * dx + dz * dz > r * r) continue;
				for (int y = minY; y <= maxY; y++) {
					m.set(x, y, z);
					BlockState st = sw.getBlockState(m);
					if (!isNaturalTreeOrCanopy(st) || st.hasBlockEntity()) continue;
					broken++;
					BlockPos pos = m.toImmutable();
					if (fxCount < 24) {
						fxCount++;
						sw.spawnParticles(new BlockStateParticleEffect(ParticleTypes.BLOCK, st),
							pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5, 3, 0.2, 0.2, 0.2, 0.12);
					}
					sw.setBlockState(pos, Blocks.AIR.getDefaultState(), Block.NOTIFY_LISTENERS | Block.SKIP_DROPS);
				}
			}
		}
		if (broken > 0) {
			sw.playSound(null, centerPos.getX() + 0.5, centerPos.getY() + 0.5, centerPos.getZ() + 0.5,
				SoundEvents.BLOCK_WOOD_BREAK, SoundCategory.BLOCKS, 1.1f, 0.85f);
		}
	}

	private record DamageResult(List<BlockSet.Entry> broken, int prevCount, double cornerX, double cornerY, double cornerZ,
								int prevSx, int prevSy, int prevSz) {}

	/**
	 * Fractura y desprende bloques de la propia pieza lanzada o pesada al golpear contra el piso, según su velocidad,
	 * dureza y frente de impacto. Conserva el 100% de los bloques (devuelve los rotos para lanzarlos como escombros).
	 */
	private DamageResult takeImpactDamage(Vec3d dir, double speed, int tier, boolean wasThrown) {
		int total = blocks != null ? blocks.size() : 0;
		double oldCornerX = getX() - blocks.sx * 0.5;
		double oldCornerY = getY();
		double oldCornerZ = getZ() - blocks.sz * 0.5;
		int prevSx = blocks.sx, prevSy = blocks.sy, prevSz = blocks.sz;
		if (total == 0) return new DamageResult(List.of(), 0, oldCornerX, oldCornerY, oldCornerZ, prevSx, prevSy, prevSz);
		if (total == 1) {
			if (wasThrown) debris = true;
			return new DamageResult(List.of(), 1, oldCornerX, oldCornerY, oldCornerZ, prevSx, prevSy, prevSz);
		}

		boolean soft = massKg() / total < 1200.0;
		double imp = MathHelper.clamp(speed * (wasThrown ? 0.68 : 0.48) + Math.max(0, tier - 1) * 0.14, 0.35, 1.85);
		double cx = (prevSx - 1) * 0.5, cy = (prevSy - 1) * 0.5, cz = (prevSz - 1) * 0.5;
		double hx = Math.max(0.5, prevSx * 0.5), hy = Math.max(0.5, prevSy * 0.5), hz = Math.max(0.5, prevSz * 0.5);

		record Scored(BlockSet.Entry e, double score) {}
		List<Scored> scored = new ArrayList<>(total);
		for (BlockSet.Entry e : blocks.entries) {
			double lx = (e.x() - cx) / hx, ly = (e.y() - cy) / hy, lz = (e.z() - cz) / hz;
			double front = lx * dir.x + ly * dir.y + lz * dir.z;
			double edge = Math.sqrt(lx * lx + ly * ly + lz * lz) / 1.732;
			float hard = Math.max(0.2f, e.state().getBlock().getHardness());
			double topBias = (e.y() == prevSy - 1) ? 0.10 : 0.0;
			double coreProtect = (!wasThrown && total >= 32 && e.y() == 0 && edge < 0.72) ? -0.35 : 0.0;
			double frag = (0.48 * imp + 0.36 * front + 0.34 * edge + topBias + coreProtect
				+ (random.nextDouble() - 0.5) * 0.16) / Math.pow(hard, 0.40);
			scored.add(new Scored(e, frag));
		}
		scored.sort((a, b) -> Double.compare(b.score(), a.score()));

		int maxBreak;
		if (wasThrown && total <= 10) {
			maxBreak = total;
		} else if (!wasThrown && total >= 40) {
			maxBreak = Math.max(4, (int) Math.floor(total * 0.42));
		} else if (wasThrown) {
			maxBreak = Math.max(2, (int) Math.floor(total * (soft ? 0.72 : 0.60)));
		} else {
			maxBreak = Math.max(1, (int) Math.floor(total * 0.40));
		}
		if (trait() == MaterialTrait.HEAVY_PIERCING) {
			maxBreak = (int) Math.floor(maxBreak * OpeConfig.HEAVY_FRACTURE_MULT);
		}

		List<BlockSet.Entry> broken = new ArrayList<>();
		Set<Long> brokenKeys = new HashSet<>();
		for (Scored s : scored) {
			if (broken.size() >= maxBreak) break;
			if (s.score() <= 0.72) break;
			broken.add(s.e());
			brokenKeys.add(BlockPos.asLong(s.e().x(), s.e().y(), s.e().z()));
		}
		if (wasThrown && broken.isEmpty() && maxBreak > 0 && speed >= 0.9 && !scored.isEmpty() && scored.get(0).score() > 0.35) {
			double frac = trait() == MaterialTrait.HEAVY_PIERCING ? 0.18 * OpeConfig.HEAVY_FRACTURE_MULT : 0.18;
			int minChip = Math.min(maxBreak, Math.max(1, (int) Math.round(total * frac)));
			for (int i = 0; i < minChip; i++) {
				BlockSet.Entry e = scored.get(i).e();
				broken.add(e);
				brokenKeys.add(BlockPos.asLong(e.x(), e.y(), e.z()));
			}
		}
		if (broken.isEmpty()) {
			return new DamageResult(List.of(), total, oldCornerX, oldCornerY, oldCornerZ, prevSx, prevSy, prevSz);
		}
		SHATTERED += broken.size();

		BlockSet kept = new BlockSet();
		for (BlockSet.Entry e : blocks.entries) {
			if (!brokenKeys.contains(BlockPos.asLong(e.x(), e.y(), e.z()))) {
				kept.add(e.x(), e.y(), e.z(), e.state(), e.be(), e.faces());
			}
		}
		if (kept.isEmpty()) {
			this.blocks = new BlockSet();
			refreshTrait();
			return new DamageResult(broken, total, oldCornerX, oldCornerY, oldCornerZ, prevSx, prevSy, prevSz);
		}
		BlockPos shift = kept.normalize();
		setBlocks(kept);
		setPosition(oldCornerX + shift.getX() + kept.sx * 0.5, oldCornerY + shift.getY(), oldCornerZ + shift.getZ() + kept.sz * 0.5);
		return new DamageResult(broken, total, oldCornerX, oldCornerY, oldCornerZ, prevSx, prevSy, prevSz);
	}

	private void spawnDamageDebris(ServerWorld sw, DamageResult dmg, Vec3d contact, double craterR, Vec3d moveVel) {
		List<BlockSet.Entry> broken = dmg.broken();
		if (broken.isEmpty()) return;
		List<BlockSet> clusters = groupDebrisClusters(broken, dmg.prevSx(), dmg.prevSy(), dmg.prevSz());
		UUID mv = mover();
		double spd = moveVel.length();
		double spawnY = Math.max(contact.y + 0.65, dmg.cornerY() + 0.5);
		int count = clusters.size();
		for (int i = 0; i < count; i++) {
			BlockSet shardSet = clusters.get(i);
			double ang = (Math.PI * 2.0 * i / Math.max(1, count)) + (sw.random.nextDouble() - 0.5) * 0.45;
			double off = Math.min(2.0, Math.max(0.4, craterR * 0.25));
			double px = contact.x + Math.cos(ang) * off - shardSet.sx * 0.5;
			double pz = contact.z + Math.sin(ang) * off - shardSet.sz * 0.5;
			double py = spawnY + sw.random.nextDouble() * 0.6;
			PieceEntity shard = spawn(sw, shardSet, new Vec3d(px, py, pz), null, mv);
			shard.debris = true;
			double hSp = Math.min(0.46, 0.18 + sw.random.nextDouble() * 0.22 + spd * 0.04);
			double vSp = 0.28 + sw.random.nextDouble() * 0.26;
			shard.release(new Vec3d(Math.cos(ang) * hSp, vSp, Math.sin(ang) * hSp), false);
		}
		sw.playSound(null, contact.x, contact.y, contact.z, SoundEvents.BLOCK_STONE_BREAK, SoundCategory.BLOCKS, 1.5f, 0.70f);
	}

	private static List<BlockSet> groupDebrisClusters(List<BlockSet.Entry> broken, int sx, int sy, int sz) {
		if (broken.size() <= 14) {
			List<BlockSet> out = new ArrayList<>(broken.size());
			for (BlockSet.Entry e : broken) {
				BlockSet s = new BlockSet();
				s.add(0, 0, 0, e.state(), e.be(), 0x3F);
				out.add(s);
			}
			return out;
		}
		BlockSet[][][] grid = new BlockSet[3][2][3];
		for (BlockSet.Entry e : broken) {
			int gx = MathHelper.clamp(e.x() * 3 / Math.max(1, sx), 0, 2);
			int gy = MathHelper.clamp(e.y() * 2 / Math.max(1, sy), 0, 1);
			int gz = MathHelper.clamp(e.z() * 3 / Math.max(1, sz), 0, 2);
			if (grid[gx][gy][gz] == null) grid[gx][gy][gz] = new BlockSet();
			grid[gx][gy][gz].add(e.x(), e.y(), e.z(), e.state(), e.be(), 0x3F);
		}
		List<BlockSet> out = new ArrayList<>(18);
		for (int gx = 0; gx < 3; gx++) {
			for (int gy = 0; gy < 2; gy++) {
				for (int gz = 0; gz < 3; gz++) {
					BlockSet s = grid[gx][gy][gz];
					if (s == null || s.isEmpty()) continue;
					s.normalize();
					out.add(s);
				}
			}
		}
		return out;
	}

	/** Explosión real al tocar el piso: onda visual/sonora y empuje a entidades sin romper bloques aleatorios ni dañar al lanzador. */
	private void triggerExplosion(ServerWorld sw, Vec3d contact, double rad, int tier) {
		float power = (float) MathHelper.clamp(1.3 + Math.max(rad, 1.0) * 0.42 + (tier >= 3 ? 1.2 : 0), 1.4, 5.5);
		ExplosionBehavior behavior = new ExplosionBehavior() {
			@Override
			public boolean canDestroyBlock(Explosion explosion, BlockView bv, BlockPos pos, BlockState state, float pwr) {
				return false;
			}

			@Override
			public boolean shouldDamage(Explosion explosion, Entity entity) {
				return !(entity instanceof PieceEntity) && !(entity instanceof ItemEntity) && !spares(entity);
			}

			@Override
			public float getKnockbackModifier(Entity entity) {
				return (entity instanceof PieceEntity) || (entity instanceof ItemEntity) || spares(entity)
					? 0.0f
					: super.getKnockbackModifier(entity);
			}
		};
		sw.createExplosion(this, damageSource(), behavior, contact.x, contact.y + 0.35, contact.z,
			power, false, World.ExplosionSourceType.NONE);
	}

	private void spawnFallTrail(ServerWorld sw, Vec3d v, double speed) {
		ElementEffects.trailFx(this, sw, v, speed);
		if (debris || slowFall || blocks == null || blocks.size() < 18 || speed < 0.45) return;
		BlockState mat = blocks.dominant();
		Vec3d c = center();
		double spread = Math.min(3.5, Math.max(0.5, radius() * 0.55));
		int puffs = blocks.size() >= 64 ? 3 : 1;
		sw.spawnParticles(ParticleTypes.CLOUD, c.x, c.y - blocks.sy * 0.3, c.z, puffs, spread * 0.5, 0.2, spread * 0.5, 0.04);
		if (speed > 0.85) {
			sw.spawnParticles(ParticleTypes.LARGE_SMOKE, c.x, c.y, c.z, puffs, spread * 0.45, 0.25, spread * 0.45, 0.03);
		}
		if (mat != null && age % 2 == 0) {
			sw.spawnParticles(new BlockStateParticleEffect(ParticleTypes.BLOCK, mat), c.x, c.y, c.z,
				Math.min(8, 2 + blocks.size() / 40), spread * 0.6, 0.3, spread * 0.6, 0.12);
		}
	}

	private static void emitFx(ServerWorld sw, ParticleEffect effect, double x, double y, double z,
			int count, double dx, double dy, double dz, double speed) {
		for (ServerPlayerEntity sp : sw.getPlayers()) {
			if (sp.squaredDistanceTo(x, y, z) <= 192.0 * 192.0) {
				sw.spawnParticles(sp, effect, true, x, y, z, count, dx, dy, dz, speed);
			}
		}
	}

	private void spawnImpactFx(ServerWorld sw, Vec3d contact, double rad, double speed, BlockState floor, boolean big, boolean colossal, MaterialTrait trait) {
		double r = Math.max(1.2, rad);
		BlockState rockMat = blocks != null && blocks.dominant() != null ? blocks.dominant() : floor;
		int debrisCount = MathHelper.clamp((int) (18 + r * (colossal ? 26 : big ? 16 : 9)), 18, 220);
		if (!floor.isAir()) {
			emitFx(sw, new BlockStateParticleEffect(ParticleTypes.BLOCK, floor), contact.x, contact.y + 0.35, contact.z,
				debrisCount / 2, r * 0.55, 0.4, r * 0.55, Math.min(0.95, 0.35 + speed * 0.25));
		}
		if (rockMat != null && !rockMat.isAir()) {
			emitFx(sw, new BlockStateParticleEffect(ParticleTypes.BLOCK, rockMat), contact.x, contact.y + 0.6, contact.z,
				debrisCount / 2, r * 0.45, 0.5, r * 0.45, Math.min(1.0, 0.3 + speed * 0.22));
		}
		if (big || colossal) {
			emitFx(sw, ParticleTypes.EXPLOSION_EMITTER, contact.x, contact.y + 0.6, contact.z,
				colossal ? 3 : 2, r * 0.22, 0.4, r * 0.22, 0);
			emitFx(sw, ParticleTypes.FLAME, contact.x, contact.y + 0.4, contact.z,
				colossal ? 38 : 18, r * 0.4, 0.35, r * 0.4, 0.16);
			sw.playSound(null, contact.x, contact.y, contact.z, SoundEvents.ENTITY_DRAGON_FIREBALL_EXPLODE, SoundCategory.BLOCKS,
				colossal ? 2.6f : 1.75f, trait == MaterialTrait.HEAVY_PIERCING ? 0.38f : 0.55f);
			if (colossal) {
				sw.playSound(null, contact.x, contact.y, contact.z, SoundEvents.ENTITY_WARDEN_SONIC_BOOM, SoundCategory.BLOCKS,
					1.9f, trait == MaterialTrait.HEAVY_PIERCING ? 0.35f : 0.48f);
			}
		} else {
			emitFx(sw, ParticleTypes.EXPLOSION, contact.x, contact.y + 0.4, contact.z, 5, r * 0.35, 0.3, r * 0.35, 0);
		}
		emitFx(sw, ParticleTypes.POOF, contact.x, contact.y + 0.3, contact.z,
			colossal ? 48 : big ? 28 : 12, r * 0.6, 0.25, r * 0.6, 0.16);
		emitFx(sw, ParticleTypes.CAMPFIRE_COSY_SMOKE, contact.x, contact.y + 0.5, contact.z,
			colossal ? 34 : big ? 18 : 6, r * 0.5, 0.35, r * 0.5, 0.06);
		if (big || r >= 3.5) {
			for (int i = 0; i < 8; i++) {
				double ang = i * (Math.PI * 2.0 / 8.0);
				double px = contact.x + Math.cos(ang) * r * 0.65;
				double pz = contact.z + Math.sin(ang) * r * 0.65;
				emitFx(sw, ParticleTypes.EXPLOSION_EMITTER, px, contact.y + 1.2, pz, 1, 0.2, 0.2, 0.2, 0);
				emitFx(sw, ParticleTypes.FLAME, px, contact.y + 1.2, pz, 6, 0.4, 0.4, 0.4, 0.14);
				emitFx(sw, ParticleTypes.CAMPFIRE_COSY_SMOKE, px, contact.y + 1.2, pz, 5, 0.4, 0.4, 0.4, 0.05);
			}
		}
		if (big || trait == MaterialTrait.HEAVY_PIERCING) {
			emitFx(sw, ParticleTypes.ASH, contact.x, contact.y + 0.8, contact.z,
				colossal || trait == MaterialTrait.HEAVY_PIERCING ? 60 : 28, r * 0.85, 0.8, r * 0.85, 0.08);
		}
		float pitch = trait == MaterialTrait.HEAVY_PIERCING ? 0.42f : 0.6f;
		sw.playSound(null, contact.x, contact.y, contact.z, SoundEvents.BLOCK_DEEPSLATE_BREAK, SoundCategory.BLOCKS,
			colossal ? 2.4f : big ? 1.8f : 1.1f, pitch);
	}

	/** Predice cuántos ticks faltan (1..maxTicks) para que esta pieza en vuelo choque contra el suelo, un bloque o un ser vivo. */
	public int predictTicksToImpact(int maxTicks) {
		World w = getWorld();
		Vec3d cPos = center();
		Vec3d bPos = getPos();
		Vec3d v = getVelocity();
		double r = radius();
		double air = airDrag(r);
		double g = hasNoGravity() ? 0.0 : (slowFall ? -0.012 : -0.06 * gravityMul);
		double max = OpeConfig.FALL_MAX_SPEED;
		BlockView view = GroundRules.loaded(w);
		BlockPos.Mutable m = new BlockPos.Mutable();
		for (int t = 1; t <= maxTicks; t++) {
			v = v.add(0, g, 0);
			if (slowFall && v.y < -0.12) v = new Vec3d(v.x, -0.12, v.z);
			if (v.lengthSquared() > max * max) v = v.multiply(max / v.length());
			Vec3d nextC = cPos.add(v);
			Vec3d nextB = bPos.add(v);
			BlockHitResult bhr = w.raycast(new net.minecraft.world.RaycastContext(
				cPos, nextC, net.minecraft.world.RaycastContext.ShapeType.COLLIDER, net.minecraft.world.RaycastContext.FluidHandling.NONE, this));
			if (bhr.getType() != HitResult.Type.MISS) return t;
			int ix = MathHelper.floor(nextB.x), iy = MathHelper.floor(nextB.y - 0.08), iz = MathHelper.floor(nextB.z);
			if (solidAt(view, m, ix, iy, iz)) return t;
			Box stepBox = getBoundingBox().offset(nextB.subtract(getPos())).expand(0.2);
			if (!w.getEntitiesByClass(LivingEntity.class, stepBox, e -> e.isAlive() && !e.isSpectator() && !spares(e)).isEmpty()) {
				return t;
			}
			v = new Vec3d(v.x * air, v.y * air, v.z * air);
			cPos = nextC;
			bPos = nextB;
		}
		return Integer.MAX_VALUE;
	}
}
