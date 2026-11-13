package dev.opeope.entity;

import dev.opeope.OpeConfig;
import dev.opeope.OpeOpeMod;
import dev.opeope.net.Payloads;
import dev.opeope.power.CutEngine;
import dev.opeope.power.PowerManager;
import dev.opeope.power.PlayerPower;
import dev.opeope.power.Room;
import dev.opeope.power.Takt;
import dev.opeope.registry.ModEffects;
import dev.opeope.registry.ModEntities;
import dev.opeope.terrain.GroundRules;
import dev.opeope.terrain.Landing;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.ItemEntity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.MovementType;
import net.minecraft.entity.damage.DamageSource;
import net.minecraft.entity.data.DataTracker;
import net.minecraft.entity.data.TrackedData;
import net.minecraft.entity.data.TrackedDataHandlerRegistry;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.projectile.ProjectileUtil;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.network.listener.ClientPlayPacketListener;
import net.minecraft.network.packet.Packet;
import net.minecraft.network.packet.s2c.play.EntitySpawnS2CPacket;
import net.minecraft.particle.BlockStateParticleEffect;
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
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Un trozo de mundo cortado: lo que separa Amputate, lo que arranca Takt y las armas que se forjan con ello.
 * Cae, choca, se lanza, se posa, se cose (vuelve a ser bloques) y vuelve a su sitio con la Unión.
 */
public class PieceEntity extends Entity {
	private static final TrackedData<NbtCompound> BLOCKS = DataTracker.registerData(PieceEntity.class, TrackedDataHandlerRegistry.NBT_COMPOUND);
	private static final TrackedData<Byte> FLAGS = DataTracker.registerData(PieceEntity.class, TrackedDataHandlerRegistry.BYTE);
	private static final TrackedData<Long> FX_TIME = DataTracker.registerData(PieceEntity.class, TrackedDataHandlerRegistry.LONG);

	public static final int F_WEAPON = 1;     // pieza girada (lanza, hoja, martillo, estaca): caja pequeña en el centro
	public static final int F_SUSPENDED = 2;  // suspendida tras un corte
	public static final int F_SEAM = 4;       // brilla: elegida para coser o volviendo a su sitio
	public static final int F_HELD = 8;       // la lleva el Takt de alguien (brillo del borde)
	public static final int F_TEAR = 16;      // arrancándose: quieta, el cliente la hace temblar
	public static final int F_FRESH = 32;     // sus caras cortadas brillan (desde fxTime); se apaga pasados TAKT_FRESH_TICKS
	public static final int F_LANDED = 64;    // posándose o ya posada: no se coge, sin borde, se dibuja a 0,998
	public static final int F_CHUNK = 128;    // arrancada del suelo con Takt

	/** SLIDING: resbala por el plano del corte. HOVER: no tiene por dónde resbalar y queda separada un momento. LANDING: se posa. */
	public enum Mode { FREE, CONTROLLED, RETURNING, SEWING, STUCK, SLIDING, HOVER, LANDING }

	public enum Weapon { LANCE, BLADE, HAMMER, STAKE, WALL }

	/** Qué muelle la lleva (lo pone Takt.tick): en la mano, en órbita o aparcada. */
	public static final int DRIVE_HAND = 0, DRIVE_ORBIT = 1, DRIVE_PARK = 2;

	/** Solo en el hilo del servidor, desde el arranque: bloques que place() soltó como objeto o puso en otra celda (AutoTest). */
	public static int DROPPED, RELOCATED;

	public BlockSet blocks = new BlockSet();
	public Mode mode = Mode.FREE;
	public Vec3d target;
	public BlockPos origin;
	public UUID owner;
	public boolean thrown;
	public int thrownTicks;
	public boolean slowFall;
	public boolean sewOnContact;
	public boolean solid;

	public Vec3d targetVel = Vec3d.ZERO;   // velocidad del sitio que sigue, en bloques por segundo (la pone Takt.tick)
	public int drive = DRIVE_HAND;          // qué muelle (lo pone Takt.tick)
	public double gravityMul = 1.0;         // gravedad de más de los trozos grandes lanzados (Takt al lanzar; vuelve a 1 al posarse)
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
	private final Map<Integer, Integer> hitCooldown = new HashMap<>();

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
	}

	public void setBlocks(BlockSet set) {
		this.blocks = set;
		this.dataTracker.set(BLOCKS, set.toSyncNbt());
		this.setPosition(getX(), getY(), getZ());
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
			setPosition(getX(), getY(), getZ());
		} else if (FLAGS.equals(data)) {
			setPosition(getX(), getY(), getZ());
		}
	}

	@Override
	protected Box calculateBoundingBox() {
		if (blocks == null || this.dataTracker == null) return super.calculateBoundingBox();
		double x = getX(), y = getY(), z = getZ();
		if (isWeaponShape()) return new Box(x - 0.4, y - 0.4, z - 0.4, x + 0.4, y + 0.4, z + 0.4);
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
	}

	@Override
	protected void writeCustomDataToNbt(NbtCompound nbt) {
		// Ya posada: sus bloques están en el mundo y no deben volver con la entidad.
		nbt.put("blocks", (placed ? new BlockSet() : blocks).toSaveNbt());
		if (origin != null) nbt.putLong("origin", origin.asLong());
		if (owner != null) nbt.putUuid("owner", owner);
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
		if (getWorld().isClient) return;
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
		}
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
		Vec3d v = getVelocity().add(g.subtract(slideN.multiply(g.dotProduct(slideN))));
		v = v.subtract(slideN.multiply(v.dotProduct(slideN))).multiply(0.96);
		if (v.length() > 1.5) v = v.normalize().multiply(1.5);
		int steps = Math.max(1, (int) Math.ceil(v.length() / 0.2));
		Vec3d step = v.multiply(1.0 / steps);
		boolean blocked = false;
		for (int i = 0; i < steps; i++) {
			Vec3d next = getPos().add(step);
			if (collidesAt(next)) {
				blocked = true;
				break;
			}
			setPosition(next);
			slid += step.length();
		}
		if (v.length() > 0.5) hitEntities(v, false);
		if (slideTicks % 3 == 0 && v.length() > 0.05) slideDust();
		boolean stopped = slideTicks > 15 && v.lengthSquared() < 1e-4;
		if (blocked || stopped || slideTicks > 120) {
			if (slid < 0.6) {
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
						if (d > -1.0 && d <= 0.05) continue;   // la piel del corte
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
		double gap = 0.4 * Math.min(1, hoverTicks / 5.0);
		setPosition(home().add(slideN.multiply(gap)));
		setVelocity(Vec3d.ZERO);
		if (hoverTicks > OpeConfig.CUT_HOVER_TICKS) {
			setFlag(F_SUSPENDED, false);
			if (origin != null) {
				setPosition(home());
				settleAt(origin, false);
			} else {
				settle(false);
			}
		}
	}

	/** Suelta la pieza: vuelve a caer con la gravedad. Nadie la lleva ya. */
	public void release(Vec3d velocity, boolean asThrow) {
		mode = Mode.FREE;
		solid = false;
		target = null;
		targetVel = Vec3d.ZERO;
		setVelocity(velocity);
		thrown = asThrow;
		thrownTicks = 0;
		restTicks = 0;
		freeTicks = 0;
		noClip = false;
		setNoGravity(false);
		resetMotion();
		setFlags(0, F_SUSPENDED | F_HELD);
		if (!getWorld().isClient) Takt.forget(getId());
	}

	public void control(Vec3d centerTarget) {
		mode = Mode.CONTROLLED;
		target = centerTarget;
		targetVel = Vec3d.ZERO;
		drive = DRIVE_HAND;
		slowFall = false;
		noClip = false;
		setNoGravity(false);
		gravityMul = 1.0;
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

	/**
	 * Un paso del muelle amortiguado hacia t (velocidad del sitio vt, en b/s), en 3 subpasos de 1/60 s y con la
	 * aceleración limitada; los trozos grandes son más lentos y pesados. Se mueve chocando con el mundo.
	 */
	private void spring(Vec3d t, Vec3d vt, double r) {
		double s = Math.max(1, r / 2.5);
		double kHand = s <= 1 ? 34 : Math.max(7, 34 / Math.pow(s, 0.8));
		double aHand = s <= 1 ? 90 : Math.max(22, 90 / Math.pow(s, 0.55));
		double k, c, maxA;
		if (drive == DRIVE_PARK) {
			k = 22 / Math.sqrt(s);
			c = 9 / Math.pow(s, 0.25);
			maxA = 0.6 * aHand;
		} else {
			k = kHand;
			c = s <= 1 ? 11 : 2 * Math.sqrt(kHand) * 0.92;
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
	}

	private void freeTick() {
		freeTicks++;
		thrownTicks++;
		Vec3d v = getVelocity();
		double speedBefore = v.length();
		if (!hasNoGravity()) v = v.add(0, slowFall ? -0.012 : -0.06 * gravityMul, 0);
		if (slowFall && v.y < -0.12) v = new Vec3d(v.x, -0.12, v.z);
		setVelocity(v);
		move(MovementType.SELF, v);
		boolean collided = horizontalCollision || verticalCollision;
		if (speedBefore > 0.5) hitEntities(v, false);
		if (thrown && collided && speedBefore > 1.0 && thrownTicks > 2) {
			impact(speedBefore, v);
			return;
		}
		if (sewOnContact && collided) {
			settle(true);
			return;
		}
		Vec3d after = getVelocity();
		double f = isOnGround() ? 0.55 : 0.98;
		setVelocity(after.x * f, after.y * 0.98, after.z * f);
		if (isOnGround() && getVelocity().lengthSquared() < 0.004) restTicks++;
		else restTicks = 0;
		if (restTicks > 6 || freeTicks > 20 * 30) settle(false);
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
		ServerPlayerEntity p = ownerPlayer();
		return p != null ? getDamageSources().thrown(this, p) : getDamageSources().fallingBlock(this);
	}

	public ServerPlayerEntity ownerPlayer() {
		if (owner == null || !(getWorld() instanceof ServerWorld sw)) return null;
		return sw.getServer().getPlayerManager().getPlayer(owner);
	}

	private void hitEntities(Vec3d v, boolean sparingOwner) {
		double speed = v.length();
		List<LivingEntity> list = getWorld().getEntitiesByClass(LivingEntity.class, getBoundingBox().expand(0.25),
			e -> e.isAlive() && !(e.getUuid().equals(owner) && (sparingOwner || thrownTicks < 12)));
		for (LivingEntity le : list) {
			Integer until = hitCooldown.get(le.getId());
			if (until != null && until > age) continue;
			float dmg = (float) Math.min(30, 2 + speed * 3.5 * Math.cbrt(blocks.size()));
			le.damage(damageSource(), dmg);
			Vec3d push = v.normalize().multiply(Math.min(2.5, speed * 0.6)).add(0, 0.25, 0);
			le.addVelocity(push.x, push.y, push.z);
			le.velocityModified = true;
			hitCooldown.put(le.getId(), age + 10);
		}
	}

	// ------------------------------------------------------------------ impacto (4.8)

	/**
	 * Un trozo lanzado que choca (vel: su velocidad justo antes del choque). Con suelo debajo y energía de nivel 2 o más
	 * abre un cráter y se hunde en él; contra una pared, un tronco o un techo no cava nada: pierde casi toda la velocidad
	 * y cae al suelo. Desde el nivel 1 hace daño y empuja a lo que haya cerca. Nunca explota ni suelta objetos.
	 */
	private void impact(double speed, Vec3d vel) {
		thrown = false;
		if (!(getWorld() instanceof ServerWorld w)) return;
		double energy = 0.5 * massKg() * (20 * speed) * (20 * speed);
		int tier = energy >= OpeConfig.IMPACT_TIER3 ? 3 : energy >= OpeConfig.IMPACT_TIER2 ? 2 : energy >= OpeConfig.IMPACT_TIER1 ? 1 : 0;
		boolean ceiling = verticalCollision && vel.y > 0;
		Vec3d contact;
		if (ceiling) contact = new Vec3d(getX(), getY() + blocks.sy, getZ());
		else if (verticalCollision) contact = getPos();
		else contact = center().add(vel.normalize().multiply(0.5 * Math.min(blocks.sx, blocks.sz)));
		boolean side = ceiling || (horizontalCollision && !verticalCollision && !groundUnder(GroundRules.loaded(w)));
		double R = 0, k = 0;
		if (!side && tier >= 2) {
			double lo = tier == 2 ? OpeConfig.IMPACT_TIER2 : OpeConfig.IMPACT_TIER3;
			double hi = tier == 2 ? OpeConfig.IMPACT_TIER3 : 10 * OpeConfig.IMPACT_TIER3;
			k = MathHelper.clamp(Math.log10(energy / lo) / Math.log10(hi / lo), 0, 1);
			double r = radius();
			R = tier == 2 ? MathHelper.clamp(0.55 * r, 1.4, 4) : MathHelper.clamp(0.85 * r, 3, 9);
			double depth = (tier == 2 ? 0.35 + 0.15 * R : 0.9 + 0.22 * R) * (0.7 + 0.6 * k);
			Crater.Result dug = Crater.dig(w, contact, R, depth, OpeConfig.CRATER_MAX_BLOCKS);
			if (dug.removed() > 0) setPosition(getX(), getY() - Math.min(0.6 * depth, blocks.sy / 2.0), getZ());   // se hunde en el cráter
		}
		if (tier >= 1) shock(w, contact, R, k, tier);
		sendFx(w, Payloads.TaktFx.IMPACT, contact.x, contact.y, contact.z, (float) R, (float) (energy / 1e6), chunkFx(), tier,
			new int[0], ownerOr());
		if (AUTOTEST) OpeOpeMod.LOG.info("AUTOTEST takt impacto nivel {} cráter {}", tier, String.format(Locale.ROOT, "%.2f", R));
		if (side) {
			// Sin cráter ni bloques pegados a la pared: una pieza suelta normal que cae y se posa en el suelo de abajo.
			Vec3d v = getVelocity();
			setVelocity(v.x * 0.3, Math.min(v.y, 0), v.z * 0.3);
			return;
		}
		settle(false);
	}

	/** ¿Hay suelo sólido justo debajo de la pieza (una o dos celdas por debajo de su base)? */
	private boolean groundUnder(BlockView view) {
		BlockPos.Mutable m = new BlockPos.Mutable();
		int x = MathHelper.floor(getX()), y = MathHelper.floor(getY()), z = MathHelper.floor(getZ());
		return solidAt(view, m, x, y - 1, z) || solidAt(view, m, x, y - 2, z);
	}

	/** La onda del golpe: daño y empujón a lo vivo a menos de R + 2 del punto de contacto (no al dueño). */
	private void shock(ServerWorld w, Vec3d contact, double R, double k, int tier) {
		double reach = R + 2;
		DamageSource src = damageSource();
		for (LivingEntity le : w.getEntitiesByClass(LivingEntity.class, new Box(contact, contact).expand(reach),
			e -> e.isAlive() && !e.isSpectator() && !e.getUuid().equals(owner))) {
			double d = le.getPos().distanceTo(contact);
			if (d > reach) continue;
			double f = 1 - d / reach;
			le.damage(src, (float) Math.min(24, (2 + 3 * tier) * f * (0.6 + 0.4 * k)));
			Vec3d push = le.getPos().subtract(contact).normalize().multiply((0.6 + 0.4 * tier) * f).add(0, 0.35, 0);
			le.addVelocity(push.x, push.y, push.z);
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
	}

	/** Deja de ser un arma: cae y se asienta como bloques. */
	public void becomePlain() {
		weapon = null;
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
		le.damage(damageSource(), dmg);
		le.addVelocity(push.x, push.y, push.z);
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
		EntityHitResult ehr = ProjectileUtil.getEntityCollision(getWorld(), this, tip, end, new Box(tip, end).expand(1.0),
			e -> e instanceof LivingEntity && e.isAlive() && e != p && e.getId() != impaled);
		if (ehr != null && impaled == -1 && ehr.getEntity() instanceof LivingEntity le) {
			strike(le, 14f, dir.multiply(0.5));
			impaled = le.getId();
			getWorld().playSound(null, le.getX(), le.getY(), le.getZ(), SoundEvents.ENTITY_ARROW_HIT_PLAYER, SoundCategory.PLAYERS, 1f, 0.6f);
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
			e -> e instanceof LivingEntity && e.isAlive() && e != p);
		setPosition(end.subtract(dir.multiply(len / 2)));
		if (ehr != null && ehr.getEntity() instanceof LivingEntity le) {
			strike(le, 8f, dir.multiply(0.4));
			wState = 2;
			stuckTicks = 0;
		} else if (bhr.getType() != HitResult.Type.MISS || wTicks > 50) {
			wState = 2;
			stuckTicks = 0;
		}
	}

	private void wallTick(ServerPlayerEntity p, Vec3d look) {
		if (wState == 0) {
			solid = true;
			setVelocity(Vec3d.ZERO);
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
}
