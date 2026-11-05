package dev.opeope.entity;

import dev.opeope.power.CutEngine;
import dev.opeope.power.PowerManager;
import dev.opeope.power.PlayerPower;
import dev.opeope.power.Room;
import dev.opeope.registry.ModEffects;
import dev.opeope.registry.ModEntities;
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
import net.minecraft.world.RaycastContext;
import net.minecraft.world.World;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Un trozo de mundo cortado: lo que separa Amputate, lo que arranca Takt y las armas que se forjan con ello.
 * Cae, choca, se lanza, se cose (vuelve a ser bloques) y vuelve a su sitio con la Unión.
 */
public class PieceEntity extends Entity {
	private static final TrackedData<NbtCompound> BLOCKS = DataTracker.registerData(PieceEntity.class, TrackedDataHandlerRegistry.NBT_COMPOUND);
	private static final TrackedData<Byte> FLAGS = DataTracker.registerData(PieceEntity.class, TrackedDataHandlerRegistry.BYTE);

	public static final int F_WEAPON = 1;     // pieza girada (lanza, hoja, martillo, estaca): caja pequeña en el centro
	public static final int F_SUSPENDED = 2;  // suspendida tras un corte
	public static final int F_SEAM = 4;       // brilla: elegida para coser o volviendo a su sitio

	public enum Mode { FREE, CONTROLLED, RETURNING, SEWING, STUCK }

	public enum Weapon { LANCE, BLADE, HAMMER, STAKE, WALL }

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
	}

	public void setBlocks(BlockSet set) {
		this.blocks = set;
		this.dataTracker.set(BLOCKS, set.toSyncNbt());
		this.setPosition(getX(), getY(), getZ());
	}

	public int flags() {
		return this.dataTracker.get(FLAGS);
	}

	public void setFlag(int flag, boolean on) {
		int f = flags();
		f = on ? (f | flag) : (f & ~flag);
		this.dataTracker.set(FLAGS, (byte) f);
		if (flag == F_WEAPON) setPosition(getX(), getY(), getZ());
	}

	public boolean isWeaponShape() {
		return (flags() & F_WEAPON) != 0;
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
		nbt.put("blocks", blocks.toSaveNbt());
		if (origin != null) nbt.putLong("origin", origin.asLong());
		if (owner != null) nbt.putUuid("owner", owner);
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
		return !isRemoved();
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
		super.tick();
		if (getWorld().isClient) return;
		if (blocks.isEmpty()) {
			discard();
			return;
		}
		if (getY() < getWorld().getBottomY() - 64) {
			discard();
			return;
		}
		if (weapon != null) {
			weaponTick();
			return;
		}
		switch (mode) {
			case CONTROLLED -> controlledTick();
			case RETURNING -> returningTick();
			case SEWING -> sewingTick();
			case STUCK -> setVelocity(Vec3d.ZERO);
			case FREE -> freeTick();
		}
	}

	/** Suelta la pieza: vuelve a caer con la gravedad. */
	public void release(Vec3d velocity, boolean asThrow) {
		mode = Mode.FREE;
		solid = false;
		target = null;
		setVelocity(velocity);
		thrown = asThrow;
		thrownTicks = 0;
		restTicks = 0;
		freeTicks = 0;
		setFlag(F_SUSPENDED, false);
	}

	public void control(Vec3d centerTarget) {
		mode = Mode.CONTROLLED;
		target = centerTarget;
		slowFall = false;
	}

	private void controlledTick() {
		if (target == null) target = center();
		Vec3d c = center();
		Vec3d v = target.subtract(c).multiply(0.35);
		if (v.length() > 2.5) v = v.normalize().multiply(2.5);
		setVelocity(v);
		move(MovementType.SELF, v);
		if (sewOnContact && (horizontalCollision || verticalCollision)) {
			settle(true);
			return;
		}
		if (v.length() > 0.7) hitEntities(v, true);
	}

	private void freeTick() {
		freeTicks++;
		thrownTicks++;
		Vec3d v = getVelocity();
		double speedBefore = v.length();
		if (!hasNoGravity()) v = v.add(0, slowFall ? -0.012 : -0.06, 0);
		if (slowFall && v.y < -0.12) v = new Vec3d(v.x, -0.12, v.z);
		setVelocity(v);
		move(MovementType.SELF, v);
		boolean collided = horizontalCollision || verticalCollision;
		if (speedBefore > 0.5) hitEntities(v, false);
		if (thrown && collided && speedBefore > 1.0 && thrownTicks > 2) impact(speedBefore);
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

	private void returningTick() {
		if (origin == null) {
			release(Vec3d.ZERO, false);
			return;
		}
		Vec3d goal = new Vec3d(origin.getX() + blocks.sx / 2.0, origin.getY(), origin.getZ() + blocks.sz / 2.0);
		Vec3d d = goal.subtract(getPos());
		if (d.length() < 0.2 || age > 20 * 60) {
			setPosition(goal);
			settleAt(origin, true);
			return;
		}
		Vec3d v = d.multiply(0.22);
		if (v.length() > 2.0) v = v.normalize().multiply(2.0);
		if (v.length() < 0.15) v = d.normalize().multiply(Math.min(0.15, d.length()));
		setPosition(getPos().add(v));
		sparkle(2);
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

	private void impact(double speed) {
		thrown = false;
		float power = (float) MathHelper.clamp(0.4 + Math.cbrt(blocks.size()) * speed * 0.55, 1.0, 6.0);
		Vec3d c = center();
		getWorld().createExplosion(this, c.x, c.y, c.z, power, World.ExplosionSourceType.TNT);
	}

	/** Vuelve a ser bloques donde está (redondeando a la rejilla). */
	public void settle(boolean sealed) {
		BlockPos base = BlockPos.ofFloored(getX() - blocks.sx / 2.0 + 0.5, getY() + 0.5, getZ() - blocks.sz / 2.0 + 0.5);
		settleAt(base, sealed);
	}

	public void settleAt(BlockPos base, boolean sealed) {
		if (!(getWorld() instanceof ServerWorld w)) return;
		List<BlockSet.Entry> sorted = new ArrayList<>(blocks.entries);
		sorted.sort(Comparator.comparingInt(BlockSet.Entry::y));
		BlockState sample = null;
		for (BlockSet.Entry e : sorted) {
			BlockPos p = base.add(e.x(), e.y(), e.z());
			if (w.isOutOfHeightLimit(p)) continue;
			BlockState cur = w.getBlockState(p);
			if (cur.isAir() || cur.isReplaceable()) {
				w.setBlockState(p, e.state(), Block.NOTIFY_ALL);
				if (e.be() != null) {
					BlockEntity be = w.getBlockEntity(p);
					if (be != null) {
						be.read(e.be(), w.getRegistryManager());
						be.markDirty();
					}
				}
			} else {
				Block.dropStacks(e.state(), w, p);
			}
			if (sample == null) sample = e.state();
		}
		Vec3d c = center();
		Box b = getBoundingBox();
		if (sealed) {
			w.spawnParticles(ParticleTypes.END_ROD, c.x, c.y, c.z, 30, b.getLengthX() / 2.5, b.getLengthY() / 2.5, b.getLengthZ() / 2.5, 0.02);
			w.playSound(null, c.x, c.y, c.z, SoundEvents.BLOCK_AMETHYST_BLOCK_CHIME, SoundCategory.PLAYERS, 1.0f, 0.8f);
		} else if (sample != null) {
			w.spawnParticles(new BlockStateParticleEffect(ParticleTypes.BLOCK, sample), c.x, getY(), c.z, 40,
				b.getLengthX() / 3, 0.2, b.getLengthZ() / 3, 0.1);
			w.playSound(null, c.x, c.y, c.z, sample.getSoundGroup().getPlaceSound(), SoundCategory.BLOCKS, 1.0f, 0.7f);
		}
		discard();
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
