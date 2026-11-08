package dev.opeope.power;

import dev.opeope.OpeConfig;
import dev.opeope.OpeOpeMod;
import dev.opeope.entity.BlockSet;
import dev.opeope.entity.PieceEntity;
import dev.opeope.net.Payloads;
import dev.opeope.net.Payloads.TaktFx;
import dev.opeope.registry.ModAttachments;
import dev.opeope.terrain.ChunkPlan;
import dev.opeope.terrain.ChunkPlanner;
import dev.opeope.terrain.GroundCut;
import dev.opeope.terrain.GroundRules;
import dev.opeope.terrain.Landing;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.fabricmc.fabric.api.networking.v1.PlayerLookup;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.inventory.Inventory;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.particle.BlockStateParticleEffect;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.registry.RegistryKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.BlockView;
import net.minecraft.world.World;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Takt: lo que señalas se levanta y se queda contigo sin mantener nada. Lo que pesa sigue pesando, pero nada es
 * demasiado pesado. Sobre el suelo de tu ROOM arranca un trozo que sigue tus cortes: primero se agrieta (espera un
 * posible doble toque, que lo deshace), luego se suelta, tiembla y sube a tu mano. Lanzar uno, todos, órbita, aparcar,
 * posar en su silueta, soltar, zona y lo cortado.
 */
public final class Takt {
	private Takt() {}

	/** Seres lanzados con Takt: se hacen daño al chocar. id → ticks que le quedan para contar. */
	private static final Map<Integer, Integer> THROWN = new HashMap<>();
	private static final Map<Integer, Double> THROWN_SPEED = new HashMap<>();
	/** Quién lleva cada cosa en su Takt (id de entidad → jugador): nadie le quita a otro lo que lleva. */
	private static final Map<Integer, UUID> HOLDERS = new HashMap<>();
	/** Las celdas que tienen apartadas los arranques pendientes, por dimensión (BlockPos.asLong). */
	private static final Map<RegistryKey<World>, LongOpenHashSet> RIPPING = new HashMap<>();
	/** Lo que una zona o "lo cortado" coge poco a poco (la oleada). */
	private static final List<Wave> WAVES = new ArrayList<>();
	private static int nextRipId = 1;
	private static int serverTicks;

	/** El bit de borde (columna) o de cara al aire (celda) de ChunkPlan.columns/cells. */
	private static final int EDGE = 1 << 30;
	private static final int[] NONE = new int[0];

	/** Una cosa que la oleada coge cuando llega su momento. */
	private record Wave(UUID owner, int id, long due, int delay, int flags) {}

	/** Lo que se mira en el suelo: el bloque y el punto exacto. */
	private record Aim(BlockPos pos, Vec3d point) {}

	/** Un arranque pendiente o recién suelto (aún se puede deshacer). Vive en PlayerPower.rips. */
	public static final class Rip {
		public final int id;                  // único en el servidor, de un contador que empieza en 1
		public final ChunkPlan plan;
		public final RegistryKey<World> dim;
		public final long startTick;          // tiempo del mundo del toque
		public final long detachAt;           // startTick + max(TAKT_TEAR_TICKS, ventana)
		public final BlockPos aimSurface;     // plan.aim: el bloque de la superficie apuntada, o el tocado en un plan de cara
		public final int[] cuts;              // ids de los GroundCut apartados (= plan.usedCuts)
		public int entityId = -1;             // la pieza, una vez suelto
		public boolean detached;

		Rip(int id, ChunkPlan plan, RegistryKey<World> dim, long startTick, long detachAt, BlockPos aimSurface, int[] cuts) {
			this.id = id;
			this.plan = plan;
			this.dim = dim;
			this.startTick = startTick;
			this.detachAt = detachAt;
			this.aimSurface = aimSurface;
			this.cuts = cuts;
		}
	}

	// ------------------------------------------------------------------ quién puede coger qué

	public static boolean canGrab(ServerPlayerEntity p, Entity e) {
		if (e == p || e.isSpectator() || !e.isAlive()) return false;
		if (e instanceof PlayerEntity pl && pl.isCreative()) return false;
		if (e instanceof PieceEntity pe && pe.weapon != null
			&& !(pe.owner != null && pe.owner.equals(p.getUuid()) && pe.weapon == PieceEntity.Weapon.LANCE)) return false;
		UUID h = HOLDERS.get(e.getId());
		if (h != null && !h.equals(p.getUuid())) return false;   // lo lleva otro: no hay tira y afloja
		return !(e instanceof PieceEntity pe && (pe.mode == PieceEntity.Mode.RETURNING || pe.mode == PieceEntity.Mode.SEWING
			|| pe.settling() || (pe.flags() & PieceEntity.F_LANDED) != 0));
	}

	public static List<Entity> resolve(ServerPlayerEntity p, PlayerPower pw) {
		return resolve(p, pw, p.getServerWorld());
	}

	/**
	 * Lo que lleva de verdad, buscado en w. Se caen de la lista las cosas que ya no están, las piezas que dejaron de
	 * estar controladas y las que lleva otro jugador (esas no se tocan: siguen siendo suyas).
	 */
	public static List<Entity> resolve(ServerPlayerEntity p, PlayerPower pw, ServerWorld w) {
		List<Entity> out = new ArrayList<>();
		UUID me = p.getUuid();
		boolean changed = false;
		Iterator<Integer> it = pw.takt.iterator();
		while (it.hasNext()) {
			int id = it.next();
			UUID h = HOLDERS.get(id);
			boolean mine = h == null || h.equals(me);
			Entity e = w.getEntityById(id);
			if (!mine || e == null || e.isRemoved() || !e.isAlive() || (e instanceof PieceEntity pe && pe.mode != PieceEntity.Mode.CONTROLLED)) {
				it.remove();
				pw.parked.remove(id);
				if (mine) HOLDERS.remove(id);   // lo de otro sigue siendo suyo: ni se olvida ni se le apaga el borde
				changed = true;
				continue;
			}
			out.add(e);
		}
		if (changed) pw.dirty = true;
		return out;
	}

	@Nullable
	public static UUID holder(int entityId) {
		return HOLDERS.get(entityId);
	}

	public static void forget(int entityId) {
		HOLDERS.remove(entityId);
	}

	/** Los arranques que aún no se han soltado. */
	public static int pendingRips(PlayerPower pw) {
		int n = 0;
		for (Rip r : pw.rips) {
			if (!r.detached) n++;
		}
		return n;
	}

	/** Pasa a llevarla sin más (un trozo recién suelto o los pedazos de una pieza que llevaba): sin subida ni aviso. */
	static void adopt(PlayerPower pw, UUID holder, PieceEntity pe) {
		if (!pw.takt.contains(pe.getId())) pw.takt.add(pe.getId());
		HOLDERS.put(pe.getId(), holder);
		THROWN.remove(pe.getId());
		pe.setFlag(PieceEntity.F_HELD, true);
		pw.dirty = true;
	}

	// ------------------------------------------------------------------ los toques que llegan del cliente

	public static void handleTap(ServerPlayerEntity p, Payloads.TaktTap tap) {
		if (!ModAttachments.hasFruit(p)) return;
		if (!PowerManager.canUse(p)) {
			p.sendMessage(Text.translatable("opeope.msg.sea"), true);
			return;
		}
		PlayerPower pw = PowerManager.get(p);
		if (tap.kind() == Payloads.TaktTap.KIND_DOUBLE) doubleTap(p, pw, tap);
		else tapChecked(p, pw, tap);
	}

	public static void handlePlace(ServerPlayerEntity p, Payloads.TaktPlace place) {
		if (!ModAttachments.hasFruit(p)) return;
		if (!PowerManager.canUse(p)) {
			p.sendMessage(Text.translatable("opeope.msg.sea"), true);
			return;
		}
		setDown(p, PowerManager.get(p), place);
	}

	/** Un toque apuntado por el servidor, con la ventana de doble toque por defecto (Actions.TAKT_TAP). */
	public static void tap(ServerPlayerEntity p, PlayerPower pw) {
		tapChecked(p, pw, null);
	}

	private static void tapChecked(ServerPlayerEntity p, PlayerPower pw, @Nullable Payloads.TaktTap tap) {
		pw.lastTapRip = -1;   // R R solo deshace lo que empezó el toque justo anterior
		long now = p.getServerWorld().getTime();
		if (now - pw.lastTapTick < OpeConfig.TAKT_TAP_MIN_TICKS) return;   // demasiado seguido: no cuenta, sin aviso
		pw.lastTapTick = now;
		tapAt(p, pw, tap);
	}

	/** Un toque: coge lo que mira; si mira el suelo de su ROOM, empieza a arrancar un trozo. */
	private static void tapAt(ServerPlayerEntity p, PlayerPower pw, @Nullable Payloads.TaktTap tap) {
		Entity e = PowerManager.raycastEntity(p, OpeConfig.TAKT_RANGE, 0.3, ent -> canGrab(p, ent) && !pw.takt.contains(ent.getId()));
		if (e != null) {
			Vec3d c = e.getBoundingBox().getCenter();
			if (!PowerManager.inRoom(p, c)) {
				PowerManager.noRoom(p);
				deny(p, TaktFx.NO_ROOM, c);
				return;
			}
			grab(p, pw, e);
			return;
		}
		Aim aim = groundAim(p, tap);
		if (aim == null) return;
		Room room = PowerManager.roomAt(p, aim.point());
		if (room == null) {
			PowerManager.noRoom(p);
			deny(p, TaktFx.NO_ROOM, aim.point());
			return;
		}
		if (pw.takt.size() + pendingRips(pw) >= OpeConfig.TAKT_MAX) {
			msg(p, "takt_full");
			deny(p, TaktFx.FULL, aim.point());
			return;
		}
		ServerWorld w = p.getServerWorld();
		BlockView view = GroundRules.loaded(w);
		BlockPos aimS = ChunkPlanner.surfaceAim(view, aim.pos());
		List<GroundCut> cuts = aimS == null ? List.of() : GroundCuts.usable(p.getUuid(), w, ChunkPlanner.aimPoint(aimS));
		int size2 = tap != null ? MathHelper.clamp(tap.size2(), 3, 18) : 6;
		ChunkPlan plan = ChunkPlanner.plan(view, aim.pos(), size2, cuts, room.center, (double) (float) room.radius, p.getBoundingBox());
		if (plan.isEmpty()) {
			msg(p, "no_ground");
			deny(p, TaktFx.NO_GROUND, aim.point());
			return;
		}
		RegistryKey<World> dim = w.getRegistryKey();
		if (plan.size() > pw.ripBudget || overlaps(dim, plan)) {
			msg(p, "takt_busy");
			deny(p, TaktFx.BUSY, aim.point());
			return;
		}
		int window = tap != null ? MathHelper.clamp(tap.window(), 0, OpeConfig.TAKT_TAP_WINDOW_MAX) : OpeConfig.TAKT_TAP_WINDOW_DEFAULT;
		long now = w.getTime();
		BlockPos aimSurface = plan.aim != null ? plan.aim : aim.pos();
		Rip rip = new Rip(nextRipId++, plan, dim, now, now + Math.max(OpeConfig.TAKT_TEAR_TICKS, window), aimSurface, plan.usedCuts);
		pw.ripBudget -= plan.size();
		GroundCuts.reserve(plan.usedCuts, rip.id);
		mark(rip);
		pw.rips.add(rip);
		pw.lastTapRip = rip.id;
		pw.dirty = true;
		sendTearStart(p, w, rip);
		if (CutEngine.AUTOTEST) {
			OpeOpeMod.LOG.info("AUTOTEST takt plan {} bloques, cerrado {}, cara {}, cortes {}, espera {}",
				plan.size(), plan.closed, plan.face, plan.usedCuts.length, rip.detachAt - rip.startTick);
		}
	}

	/**
	 * El bloque y el punto que mira en el suelo. Vale el del cliente (el que veía en la vista previa) si cae a 2,5 bloques
	 * del rayo del servidor y al alcance; si no, el del servidor. Null si no mira ningún bloque.
	 */
	@Nullable
	private static Aim groundAim(ServerPlayerEntity p, @Nullable Payloads.TaktTap tap) {
		BlockHitResult sh = PowerManager.raycastBlock(p, OpeConfig.TAKT_RANGE);
		boolean hit = sh.getType() == HitResult.Type.BLOCK;
		if (tap != null && tap.side() != Payloads.TaktTap.NO_SIDE && hit) {
			Vec3d h = new Vec3d(tap.hx(), tap.hy(), tap.hz());
			BlockPos pos = BlockPos.fromLong(tap.aim());
			if (sh.getPos().distanceTo(h) <= 2.5 && p.getEyePos().distanceTo(h) <= OpeConfig.TAKT_RANGE + 1
				&& Vec3d.ofCenter(pos).squaredDistanceTo(h) <= 3.0) {
				return new Aim(pos, h);
			}
		}
		return hit ? new Aim(sh.getBlockPos(), sh.getPos()) : null;
	}

	// ------------------------------------------------------------------ arrancar el suelo

	/** ¿Alguna celda del plan la tiene ya apartada otro arranque en esa dimensión? */
	private static boolean overlaps(RegistryKey<World> dim, ChunkPlan plan) {
		LongOpenHashSet cells = RIPPING.get(dim);
		if (cells == null || cells.isEmpty()) return false;
		for (long l : plan.blocks) {
			if (cells.contains(l)) return true;
		}
		for (long l : plan.riders) {
			if (cells.contains(l)) return true;
		}
		return false;
	}

	private static void mark(Rip rip) {
		LongOpenHashSet cells = RIPPING.computeIfAbsent(rip.dim, k -> new LongOpenHashSet());
		for (long l : rip.plan.blocks) cells.add(l);
		for (long l : rip.plan.riders) cells.add(l);
	}

	private static void unmark(Rip rip) {
		LongOpenHashSet cells = RIPPING.get(rip.dim);
		if (cells == null) return;
		for (long l : rip.plan.blocks) cells.remove(l);
		for (long l : rip.plan.riders) cells.remove(l);
		if (cells.isEmpty()) RIPPING.remove(rip.dim);
	}

	/** Cada tick: los arranques que toca soltar se sueltan; los que ya no se pueden deshacer se olvidan. */
	private static void tickRips(ServerPlayerEntity p, PlayerPower pw) {
		if (pw.rips.isEmpty()) return;
		ServerWorld w = p.getServerWorld();
		long now = w.getTime();
		for (Rip rip : new ArrayList<>(pw.rips)) {
			if (rip.dim != w.getRegistryKey()) {   // por si acaso: releaseAll ya lo hizo al cambiar de mundo
				cancel(p, pw, rip);
				continue;
			}
			if (!rip.detached && now >= rip.detachAt) detachRip(p, pw, rip);
			if (rip.detached && now - rip.startTick > OpeConfig.TAKT_UNDO_MAX_TICKS) {
				GroundCuts.forgetRip(rip.id);
				pw.rips.remove(rip);
			}
		}
	}

	/** La grieta termina: el trozo se suelta del suelo, tiembla, sube y pasa a la mano. */
	private static void detachRip(ServerPlayerEntity p, PlayerPower pw, Rip rip) {
		ServerWorld w = p.getServerWorld();
		unmark(rip);
		BlockView view = GroundRules.loaded(w);
		List<BlockPos> list = new ArrayList<>(rip.plan.size());
		for (long l : rip.plan.blocks) addCuttable(view, l, list);
		for (long l : rip.plan.riders) addCuttable(view, l, list);
		boolean full = pw.takt.size() >= OpeConfig.TAKT_MAX;
		if (list.isEmpty() || full) {
			GroundCuts.restore(rip.id);
			pw.rips.remove(rip);
			if (pw.lastTapRip == rip.id) pw.lastTapRip = -1;
			pw.dirty = true;
			Vec3d at = tearPoint(rip);
			sendFx(w, fx(TaktFx.CANCEL, rip.id, -1, p.getUuid(), at, 0f, 0f, 0, 0, 0, NONE));
			if (full) {
				msg(p, "takt_full");
				deny(p, TaktFx.FULL, at);
			}
			return;
		}
		BlockState aimState = view.getBlockState(rip.aimSurface);
		PieceEntity pe = detach(p, list, false);
		GroundCuts.consume(rip.id);
		rip.detached = true;
		rip.entityId = pe.getId();
		long now = w.getTime();
		double r = pe.radius();
		boolean big = r > OpeConfig.TAKT_BIG_RADIUS;
		int tremble = big ? OpeConfig.TAKT_TREMBLE_TICKS_BIG : OpeConfig.TAKT_TREMBLE_TICKS;
		pe.tearAt(now, tremble);
		if (!rip.plan.face) {   // un pedazo de pared o techo no sube: el muelle lo saca hacia la mano
			double s = Math.max(1, r / 2.5);
			int ticks = (int) Math.round(20 * Math.min(1.44, 0.45 + 0.3 * log2(s)));
			pe.lift(ticks, Math.max(2.6, (rip.plan.topMax + 1 - pe.corner().y) + 1.4));
		}
		adopt(pw, p.getUuid(), pe);
		pw.dirty = true;
		BlockState dom = pe.blocks.dominant();
		int[] data = {dom == null ? -1 : Block.getRawIdFromState(dom), aimState.isAir() ? -1 : Block.getRawIdFromState(aimState)};
		int flags = TaktFx.F_CHUNK | (big ? TaktFx.F_BIG : 0);
		sendFx(w, fx(TaktFx.DETACH, rip.id, pe.getId(), p.getUuid(), pe.center(), (float) r, (float) (pe.massKg() / 1000.0), tremble, flags,
			pe.blocks.size(), data));
		if (CutEngine.AUTOTEST) OpeOpeMod.LOG.info("AUTOTEST takt arranca {} bloques", pe.blocks.size());
	}

	private static void addCuttable(BlockView view, long l, List<BlockPos> out) {
		BlockPos pos = BlockPos.fromLong(l);
		if (GroundRules.cuttable(view.getBlockState(pos), view, pos)) out.add(pos);
	}

	/**
	 * Deshace un arranque, siempre en su propio mundo. Pendiente: el suelo no se ha tocado y basta con olvidarlo. Suelto y
	 * aún en la mano: vuelve a su hueco y sus cortes vuelven. Si ya no se puede poner (lanzado, soltado, lo lleva otro),
	 * sus cortes quedan gastados.
	 */
	private static void cancel(ServerPlayerEntity p, PlayerPower pw, Rip rip) {
		MinecraftServer server = p.getServer();
		ServerWorld rw = server == null ? null : server.getWorld(rip.dim);
		boolean sinks = false;
		if (!rip.detached) {
			unmark(rip);
			GroundCuts.restore(rip.id);
		} else {
			pw.takt.remove((Integer) rip.entityId);
			pw.parked.remove(rip.entityId);
			UUID h = HOLDERS.get(rip.entityId);
			boolean mine = h == null || h.equals(p.getUuid());
			if (mine) forget(rip.entityId);
			Entity e = rw == null ? null : rw.getEntityById(rip.entityId);
			if (mine && e instanceof PieceEntity pe && !pe.isRemoved() && pe.mode == PieceEntity.Mode.CONTROLLED && !pe.settling()) {
				pe.restoreHome();
				GroundCuts.restore(rip.id);
				sinks = true;
			} else {
				GroundCuts.forgetRip(rip.id);
			}
		}
		pw.rips.remove(rip);
		if (pw.lastTapRip == rip.id) pw.lastTapRip = -1;
		pw.dirty = true;
		if (rw != null) sendFx(rw, fx(TaktFx.CANCEL, rip.id, rip.entityId, p.getUuid(), tearPoint(rip), sinks ? 1f : 0f, 0f, 0, 0, 0, NONE));
		if (CutEngine.AUTOTEST) OpeOpeMod.LOG.info("AUTOTEST takt deshace {}", rip.id);
	}

	/** R R: deshace el arranque del toque justo anterior (si es reciente) y levanta lo suelto alrededor. Nunca arranca suelo. */
	public static void doubleTap(ServerPlayerEntity p, PlayerPower pw, @Nullable Payloads.TaktTap tap) {
		Rip rip = null;
		for (Rip r : pw.rips) {
			if (r.id == pw.lastTapRip) {
				rip = r;
				break;
			}
		}
		pw.lastTapRip = -1;
		long now = p.getServerWorld().getTime();
		if (rip != null && now - rip.startTick <= OpeConfig.TAKT_UNDO_MAX_TICKS && (!rip.detached || pw.takt.contains(rip.entityId))) {
			cancel(p, pw, rip);
		}
		Aim aim = tap != null ? groundAim(p, tap) : null;
		zoneAt(p, pw, aim != null ? aim.point() : PowerManager.aimPoint(p, OpeConfig.TAKT_RANGE));
	}

	/** Al morir, cambiar de mundo o salir: los arranques pendientes se deshacen; los sueltos se dan por buenos. */
	public static void cancelPending(ServerPlayerEntity p, PlayerPower pw) {
		for (Rip rip : new ArrayList<>(pw.rips)) {
			if (!rip.detached) {
				cancel(p, pw, rip);
			} else {
				GroundCuts.forgetRip(rip.id);   // su pieza la suelta dropAll como cualquier otra
				pw.rips.remove(rip);
			}
		}
		pw.lastTapRip = -1;
		UUID me = p.getUuid();
		WAVES.removeIf(wv -> wv.owner().equals(me));
	}

	/** El punto de la grieta: el centro de la cara de arriba del bloque apuntado, o el centro del bloque tocado (plan de cara). */
	private static Vec3d tearPoint(Rip rip) {
		BlockPos a = rip.aimSurface;
		return rip.plan.face ? Vec3d.ofCenter(a) : new Vec3d(a.getX() + 0.5, a.getY() + 1, a.getZ() + 0.5);
	}

	private static void sendTearStart(ServerPlayerEntity p, ServerWorld w, Rip rip) {
		ChunkPlan plan = rip.plan;
		Vec3d at = tearPoint(rip);
		int flags = 0;
		if (plan.closed) flags |= TaktFx.F_CLOSED;
		if (plan.face) flags |= TaktFx.F_FACE;
		if (bigPlan(plan)) flags |= TaktFx.F_BIG;
		float angle = (float) Math.atan2(p.getZ() - at.z, p.getX() - at.x);
		sendFx(w, fx(TaktFx.TEAR_START, rip.id, -1, p.getUuid(), at, (float) plan.radius, angle, (int) (rip.detachAt - rip.startTick),
			flags, plan.size(), tearData(plan)));
	}

	/** ¿Será un trozo grande? El mismo radio que tendrá la pieza: medio diagonal de la caja de sus celdas. */
	private static boolean bigPlan(ChunkPlan plan) {
		if (plan.isEmpty()) return false;
		int x0 = Integer.MAX_VALUE, y0 = Integer.MAX_VALUE, z0 = Integer.MAX_VALUE;
		int x1 = Integer.MIN_VALUE, y1 = Integer.MIN_VALUE, z1 = Integer.MIN_VALUE;
		for (int k = 0; k < 2; k++) {
			for (long l : k == 0 ? plan.blocks : plan.riders) {
				BlockPos b = BlockPos.fromLong(l);
				x0 = Math.min(x0, b.getX()); y0 = Math.min(y0, b.getY()); z0 = Math.min(z0, b.getZ());
				x1 = Math.max(x1, b.getX()); y1 = Math.max(y1, b.getY()); z1 = Math.max(z1, b.getZ());
			}
		}
		double sx = x1 - x0 + 1, sy = y1 - y0 + 1, sz = z1 - z0 + 1;
		return 0.5 * Math.sqrt(sx * sx + sy * sy + sz * sz) > OpeConfig.TAKT_BIG_RADIUS;
	}

	/** Las columnas (o celdas) de la grieta; si pasan de 1024, primero las del borde y luego el resto, en el orden del plan. */
	private static int[] tearData(ChunkPlan plan) {
		int[] src = plan.face ? plan.cells : plan.columns;
		if (src.length <= TaktFx.DATA_CAP) return src.clone();
		int[] out = new int[TaktFx.DATA_CAP];
		int k = 0;
		for (int v : src) {
			if ((v & EDGE) != 0 && k < out.length) out[k++] = v;
		}
		for (int v : src) {
			if ((v & EDGE) == 0 && k < out.length) out[k++] = v;
		}
		return out;
	}

	/** Convierte una lista de bloques en una pieza (quitándolos del mundo). Las caras que tocaban suelo quedan marcadas. */
	public static PieceEntity detach(ServerPlayerEntity p, List<BlockPos> list, boolean fx) {
		ServerWorld w = p.getServerWorld();
		int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
		for (BlockPos bp : list) {
			minX = Math.min(minX, bp.getX());
			minY = Math.min(minY, bp.getY());
			minZ = Math.min(minZ, bp.getZ());
		}
		BlockSet set = new BlockSet();
		BlockState sample = null;
		for (BlockPos bp : list) {
			BlockState st = w.getBlockState(bp);
			NbtCompound nbt = null;
			BlockEntity be = w.getBlockEntity(bp);
			if (be != null) nbt = be.createNbtWithIdentifyingData(w.getRegistryManager());
			set.add(bp.getX() - minX, bp.getY() - minY, bp.getZ() - minZ, st, nbt);
			if (sample == null) sample = st;
		}
		BlockPos min = new BlockPos(minX, minY, minZ);
		set.cutFaceMask(GroundRules.loaded(w), min);   // con los bloques aún en el mundo
		for (BlockPos bp : list) {
			BlockEntity be = w.getBlockEntity(bp);
			if (be instanceof Inventory inv) inv.clear();
			w.setBlockState(bp, Blocks.AIR.getDefaultState(), Block.NOTIFY_LISTENERS | Block.FORCE_STATE | Block.SKIP_DROPS);
		}
		for (BlockPos bp : list) w.updateNeighbors(bp, Blocks.AIR);
		set.normalize();
		PieceEntity pe = PieceEntity.spawn(w, set, new Vec3d(minX, minY, minZ), min, p.getUuid());
		if (fx && sample != null) {
			Vec3d c = pe.center();
			w.spawnParticles(new BlockStateParticleEffect(ParticleTypes.BLOCK, sample), c.x, c.y, c.z, 30, 0.8, 0.5, 0.8, 0.1);
		}
		return pe;
	}

	// ------------------------------------------------------------------ coger

	public static void grab(ServerPlayerEntity p, PlayerPower pw, Entity e) {
		grab(p, pw, e, 0, 0);
	}

	/** Coger una cosa. waveDelay y fxFlags solo cambian el aviso a los clientes (oleada de zona o de lo cortado). */
	private static void grab(ServerPlayerEntity p, PlayerPower pw, Entity e, int waveDelay, int fxFlags) {
		if (e instanceof PieceEntity pe && pe.weapon == PieceEntity.Weapon.LANCE) {
			// R sobre la lanza clavada: vuelve a la mano.
			pe.wState = 0;
			pe.impaled = -1;
			if (!pw.weapons.contains(pe.getId())) {
				Forge.dropWeapons(p, pw);
				pw.weapons.add(pe.getId());
				pw.weaponKind = PieceEntity.Weapon.LANCE.ordinal();
			}
			pw.dirty = true;
			return;
		}
		if (!canGrab(p, e) || pw.takt.contains(e.getId())) return;
		Vec3d c = e.getBoundingBox().getCenter();
		if (pw.takt.size() + pendingRips(pw) >= OpeConfig.TAKT_MAX) {
			msg(p, "takt_full");
			deny(p, TaktFx.FULL, c);
			return;
		}
		pw.takt.add(e.getId());
		HOLDERS.put(e.getId(), p.getUuid());
		THROWN.remove(e.getId());
		THROWN_SPEED.remove(e.getId());
		pw.dirty = true;
		ServerWorld w = p.getServerWorld();
		int flags = fxFlags;
		float a, b = 0f;
		if (e instanceof PieceEntity pe) {
			pe.control(pe.center());
			pe.resetMotion();   // sin la subida ni el flote de quien la llevara antes
			pe.setFlag(PieceEntity.F_SUSPENDED, false);
			pe.setFlag(PieceEntity.F_HELD, true);
			pe.sewOnContact = false;
			pw.suspended.remove((Integer) pe.getId());
			double r = pe.radius();
			double s = Math.max(1, r / 2.5);
			if (!airborne(w, pe)) {
				int ticks = s <= 1 ? 7 : (int) Math.round(20 * Math.min(1.44, 0.35 + 0.3 * log2(s)));
				pe.lift(ticks, s <= 1 ? 1.6 : Math.max(2.6, pe.below() + 0.9));
			}
			if ((pe.flags() & PieceEntity.F_CHUNK) != 0) flags |= TaktFx.F_CHUNK;
			a = (float) r;
			b = (float) (pe.massKg() / 1000.0);
		} else {
			Box bb = e.getBoundingBox();
			double dx = bb.maxX - bb.minX, dy = bb.maxY - bb.minY, dz = bb.maxZ - bb.minZ;
			a = (float) (0.5 * Math.sqrt(dx * dx + dy * dy + dz * dz));
		}
		w.spawnParticles(CutEngine.ROOM_DUST, c.x, c.y, c.z, 12, 0.5, 0.5, 0.5, 0);
		sendFx(w, fx(TaktFx.GRAB, 0, e.getId(), p.getUuid(), c, a, b, waveDelay, flags, 0, NONE));
	}

	/** ¿Está en el aire? Cae, o no hay nada sólido en las tres celdas bajo el centro de su base. */
	private static boolean airborne(ServerWorld w, PieceEntity pe) {
		if (pe.getVelocity().y < -0.1) return true;
		BlockView view = GroundRules.loaded(w);
		int x = MathHelper.floor(pe.getX()), y = MathHelper.floor(pe.getY() - 0.01), z = MathHelper.floor(pe.getZ());
		for (int k = 0; k < 3; k++) {
			BlockPos pos = new BlockPos(x, y - k, z);
			if (GroundRules.solid(view.getBlockState(pos), view, pos)) return false;
		}
		return true;
	}

	/** R R (o R + E): levanta de una vez todo lo suelto alrededor del punto. */
	public static void zone(ServerPlayerEntity p, PlayerPower pw) {
		zoneAt(p, pw, PowerManager.aimPoint(p, OpeConfig.TAKT_RANGE));
	}

	private static void zoneAt(ServerPlayerEntity p, PlayerPower pw, Vec3d point) {
		if (!PowerManager.inRoom(p, point)) {
			PowerManager.noRoom(p);
			deny(p, TaktFx.NO_ROOM, point);
			return;
		}
		ServerWorld w = p.getServerWorld();
		double r = OpeConfig.TAKT_ZONE_RADIUS;
		List<Entity> cand = new ArrayList<>(w.getOtherEntities(p, new Box(point, point).expand(r),
			e -> canGrab(p, e) && !pw.takt.contains(e.getId()) && e.getPos().distanceTo(point) <= r
				&& PowerManager.inRoom(p, e.getBoundingBox().getCenter())));
		if (cand.isEmpty()) {
			msg(p, "zone_empty");
			deny(p, TaktFx.NOTHING_LOOSE, point);
			return;
		}
		int free = OpeConfig.TAKT_MAX - pw.takt.size() - pendingRips(pw);
		if (free <= 0) {
			msg(p, "takt_full");
			deny(p, TaktFx.FULL, point);
			return;
		}
		cand.sort(Comparator.comparingDouble((Entity e) -> e.getPos().squaredDistanceTo(point)).thenComparingInt(Entity::getId));
		if (cand.size() > free) cand = cand.subList(0, free);
		long now = w.getTime();
		int longest = 0;
		for (Entity e : cand) {
			int delay = Math.min(OpeConfig.TAKT_WAVE_MAX_TICKS, (int) Math.round(1.2 * e.getPos().distanceTo(point)));
			WAVES.add(new Wave(p.getUuid(), e.getId(), now + delay, delay, TaktFx.F_AREA));
			longest = Math.max(longest, delay);
		}
		sendFx(w, fx(TaktFx.ZONE, 0, -1, p.getUuid(), point, (float) r, 0f, longest, 0, cand.size(), NONE));
	}

	/** R + C: todo lo cortado y suelto de tus ROOMs, en oleada desde ti. */
	public static void grabCut(ServerPlayerEntity p, PlayerPower pw) {
		ServerWorld w = p.getServerWorld();
		List<Room> rooms = new ArrayList<>(2);
		if (pw.room != null && pw.room.dim == w.getRegistryKey()) rooms.add(pw.room);
		if (pw.rroom != null && pw.rroom.dim == w.getRegistryKey()) rooms.add(pw.rroom);
		Vec3d eye = p.getEyePos();
		if (rooms.isEmpty()) {
			PowerManager.noRoom(p);
			deny(p, TaktFx.NO_ROOM, eye);
			return;
		}
		UUID me = p.getUuid();
		List<PieceEntity> cand = new ArrayList<>();
		Set<Integer> seen = new HashSet<>();
		for (Room room : rooms) {
			Box box = new Box(room.center, room.center).expand(room.radius);
			for (PieceEntity pe : w.getEntitiesByClass(PieceEntity.class, box, e -> looseCut(pw, me, room, e))) {
				if (seen.add(pe.getId())) cand.add(pe);
			}
		}
		if (cand.isEmpty()) {
			msg(p, "nothing_cut");
			deny(p, TaktFx.NOTHING_CUT, eye);
			return;
		}
		int free = OpeConfig.TAKT_MAX - pw.takt.size() - pendingRips(pw);
		if (free <= 0) {
			msg(p, "takt_full");
			deny(p, TaktFx.FULL, eye);
			return;
		}
		cand.sort(Comparator.comparingDouble((PieceEntity e) -> e.center().squaredDistanceTo(eye)).thenComparingInt(PieceEntity::getId));
		if (cand.size() > free) cand = cand.subList(0, free);
		long now = w.getTime();
		int longest = 0;
		for (PieceEntity pe : cand) {
			int delay = Math.min(OpeConfig.TAKT_WAVE_MAX_TICKS, (int) Math.round(0.6 * pe.center().distanceTo(eye)));
			WAVES.add(new Wave(me, pe.getId(), now + delay, delay, TaktFx.F_AREA));
			longest = Math.max(longest, delay);
		}
		Room first = rooms.get(0);
		sendFx(w, fx(TaktFx.SWEEP, 0, -1, me, first.center, (float) first.radius, 0f, longest, 0, cand.size(), NONE));
	}

	/** Una pieza cortada y suelta dentro de ese ROOM, que no lleva nadie más (ni se está posando, volviendo o cosiendo). */
	private static boolean looseCut(PlayerPower pw, UUID me, Room room, PieceEntity pe) {
		if (pe.weapon != null || !room.contains(pe.center()) || pe.settling() || (pe.flags() & PieceEntity.F_LANDED) != 0) return false;
		PieceEntity.Mode m = pe.mode;
		boolean loose = m == PieceEntity.Mode.FREE || m == PieceEntity.Mode.SLIDING || m == PieceEntity.Mode.HOVER
			|| m == PieceEntity.Mode.STUCK || (m == PieceEntity.Mode.CONTROLLED && pw.suspended.contains(pe.getId()));
		if (!loose) return false;
		UUID h = HOLDERS.get(pe.getId());
		return (h == null || h.equals(me)) && !pw.takt.contains(pe.getId());
	}

	/** La oleada: coge lo que ya toca, si sigue suelto, dentro de un ROOM y cabe. */
	private static void tickWaves(ServerPlayerEntity p, PlayerPower pw) {
		if (WAVES.isEmpty()) return;
		ServerWorld w = p.getServerWorld();
		long now = w.getTime();
		UUID me = p.getUuid();
		List<Wave> due = null;
		Iterator<Wave> it = WAVES.iterator();
		while (it.hasNext()) {
			Wave wv = it.next();
			if (!wv.owner().equals(me) || wv.due() > now) continue;
			it.remove();
			if (due == null) due = new ArrayList<>();
			due.add(wv);
		}
		if (due == null) return;
		for (Wave wv : due) {
			Entity e = w.getEntityById(wv.id());
			if (e == null || e.isRemoved() || pw.takt.contains(wv.id()) || !canGrab(p, e)) continue;
			if (!PowerManager.inRoom(p, e.getBoundingBox().getCenter())) continue;
			if (pw.takt.size() + pendingRips(pw) >= OpeConfig.TAKT_MAX) break;
			grab(p, pw, e, wv.delay(), wv.flags());
		}
	}

	// ------------------------------------------------------------------ órdenes

	public static void order(ServerPlayerEntity p, PlayerPower pw, int order) {
		switch (order) {
			case Actions.ORDER_ORBIT -> {
				pw.taktMode = pw.taktMode == PlayerPower.TaktMode.ORBIT ? PlayerPower.TaktMode.HAND : PlayerPower.TaktMode.ORBIT;
				p.sendMessage(Text.translatable(pw.taktMode == PlayerPower.TaktMode.ORBIT ? "opeope.msg.orbit" : "opeope.msg.hand"), true);
			}
			case Actions.ORDER_PARK -> {
				pw.taktMode = PlayerPower.TaktMode.PARK;
				pw.parked.clear();
				for (Entity e : resolve(p, pw)) pw.parked.put(e.getId(), e.getBoundingBox().getCenter());
				p.sendMessage(Text.translatable("opeope.msg.park"), true);
			}
			case Actions.ORDER_SETDOWN -> setDown(p, pw, null);
			case Actions.ORDER_DROP -> dropAll(p, pw, false);
			case Actions.ORDER_ZONE -> zone(p, pw);
			case Actions.ORDER_CUT -> grabCut(p, pw);
			case Actions.ORDER_GATHER -> Union.gather(p, pw);
			case Actions.ORDER_UNITE -> Union.uniteAll(p, pw);
			case Actions.ORDER_THROW_ALL -> throwAll(p, pw);
			default -> {
				if (order >= Actions.ORDER_FORGE && order < Actions.ORDER_FORGE + 5) Forge.forge(p, pw, order - Actions.ORDER_FORGE);
			}
		}
		pw.dirty = true;
	}

	public static void scroll(PlayerPower pw, int dir) {
		pw.taktDist = MathHelper.clamp(pw.taktDist + dir * 1.0, 2.0, 40.0);
		pw.dirty = true;
	}

	/**
	 * Posar: cada pieza baja recta a su silueta y queda en bloques. Vale la silueta que propone el cliente (su fantasma)
	 * si cabe, tiene apoyo y está a TAKT_PLACE_TOLERANCE de la del servidor; si no, la del servidor. Sin sitio, cae despacio.
	 */
	public static void setDown(ServerPlayerEntity p, PlayerPower pw, @Nullable Payloads.TaktPlace place) {
		Map<Integer, Long> props = new HashMap<>();
		if (place != null) {
			int n = Math.min(place.ids().length, place.bases().length);
			for (int i = 0; i < n; i++) props.put(place.ids()[i], place.bases()[i]);
		}
		ServerWorld w = p.getServerWorld();
		for (Entity e : resolve(p, pw)) {
			int id = e.getId();
			pw.takt.remove((Integer) id);
			pw.parked.remove(id);
			forget(id);
			if (e instanceof PieceEntity pe && pe.weapon == null) {
				BlockPos base = landingBase(w, pe, props.get(id));
				if (base != null) {
					pe.startLanding(base);
				} else {
					pe.release(Vec3d.ZERO, false);
					pe.slowFall = true;
				}
				if (CutEngine.AUTOTEST) OpeOpeMod.LOG.info("AUTOTEST takt posa {} en {}", id, base);
			} else if (e instanceof PieceEntity pe) {
				pe.release(Vec3d.ZERO, false);
				pe.slowFall = true;
			} else {
				e.setVelocity(Vec3d.ZERO);
				e.velocityModified = true;
				if (e instanceof LivingEntity le) le.addStatusEffect(new StatusEffectInstance(StatusEffects.SLOW_FALLING, 60, 0));
			}
		}
		pw.takt.clear();
		pw.parked.clear();
		if (pw.taktMode == PlayerPower.TaktMode.PARK) pw.taktMode = PlayerPower.TaktMode.HAND;
		pw.dirty = true;
	}

	/** Dónde se posa una pieza: la propuesta del cliente si vale, si no la del servidor; null si no hay sitio. */
	@Nullable
	private static BlockPos landingBase(ServerWorld w, PieceEntity pe, @Nullable Long proposal) {
		BlockView view = GroundRules.loaded(w);
		Vec3d corner = pe.corner();
		List<Box> bl = Landing.blockers(w, Landing.region(pe.blocks, corner));
		BlockPos server = Landing.find(view, pe.blocks, corner, bl);
		if (proposal != null) {
			BlockPos q = BlockPos.fromLong(proposal);
			BlockPos ref = server != null ? server : Landing.snap(corner);
			int tol = OpeConfig.TAKT_PLACE_TOLERANCE;
			if (Math.abs(q.getX() - ref.getX()) <= tol && Math.abs(q.getY() - ref.getY()) <= tol && Math.abs(q.getZ() - ref.getZ()) <= tol
				&& Landing.fits(view, pe.blocks, q, bl) && Landing.supported(view, pe.blocks, q)) {
				return q;
			}
		}
		return server;
	}

	/** Soltar todo. gently: cae despacio. */
	public static void dropAll(ServerPlayerEntity p, PlayerPower pw, boolean gently) {
		dropAll(p, pw, gently, p.getServerWorld());
	}

	/** Soltar todo lo que lleva, buscado en from (el mundo del que sale, al cambiar de mundo o reaparecer). */
	public static void dropAll(ServerPlayerEntity p, PlayerPower pw, boolean gently, ServerWorld from) {
		for (Entity e : resolve(p, pw, from)) {
			if (e instanceof PieceEntity pe) {
				pe.release(Vec3d.ZERO, false);
				pe.slowFall = gently;
			} else {
				e.setVelocity(Vec3d.ZERO);
				e.velocityModified = true;
				if (gently && e instanceof LivingEntity le) le.addStatusEffect(new StatusEffectInstance(StatusEffects.SLOW_FALLING, 60, 0));
			}
			forget(e.getId());
		}
		pw.takt.clear();
		pw.parked.clear();
		if (pw.taktMode == PlayerPower.TaktMode.PARK) pw.taktMode = PlayerPower.TaktMode.HAND;
		pw.dirty = true;
	}

	// ------------------------------------------------------------------ lanzar

	public static void throwOne(ServerPlayerEntity p, PlayerPower pw) {
		List<Entity> list = resolve(p, pw);
		if (list.isEmpty()) return;
		Vec3d eye = p.getEyePos();
		Vec3d look = p.getRotationVector();
		Entity best = null;
		double bestDot = -2;
		for (Entity e : list) {
			Vec3d to = e.getBoundingBox().getCenter().subtract(eye).normalize();
			double d = to.dotProduct(look);
			if (d > bestDot) {
				bestDot = d;
				best = e;
			}
		}
		launch(p, pw, best, PowerManager.aimPoint(p, 200), OpeConfig.TAKT_LAUNCH, 0, 0);
	}

	/** Todo a la vez, repartido en un girasol alrededor del punto que mira. */
	public static void throwAll(ServerPlayerEntity p, PlayerPower pw) {
		List<Entity> list = resolve(p, pw);
		if (list.isEmpty()) return;
		Vec3d aim = PowerManager.aimPoint(p, 200);
		Vec3d look = p.getRotationVector();
		Vec3d right = look.crossProduct(new Vec3d(0, 1, 0));
		if (right.lengthSquared() < 1e-4) right = new Vec3d(1, 0, 0);
		right = right.normalize();
		Vec3d up = right.crossProduct(look).normalize();
		int n = list.size();
		double spread = Math.min(6, 0.6 + 0.5 * Math.sqrt(n));
		for (int i = 0; i < n; i++) {
			double rho = Math.sqrt((i + 0.5) / n), th = 2.39996 * i;
			Vec3d to = aim.add(right.multiply(rho * Math.cos(th) * spread)).add(up.multiply(rho * Math.sin(th) * 0.5 * spread));
			launch(p, pw, list.get(i), to, OpeConfig.TAKT_THROW_ALL_SPEED, i, TaktFx.F_ALL);
		}
	}

	/** Lanza una cosa hacia aim. Una pieza apunta un poco más alto para caer donde miras, con el roce del aire y su peso. */
	private static void launch(ServerPlayerEntity p, PlayerPower pw, Entity e, Vec3d aim, double speed, int index, int fxFlags) {
		pw.takt.remove((Integer) e.getId());
		pw.parked.remove(e.getId());
		forget(e.getId());
		Vec3d c = e.getBoundingBox().getCenter();
		int flags = fxFlags;
		float mass = 0f;
		if (e instanceof PieceEntity pe) {
			double r = pe.radius();
			double gmul = 1 + 0.55 * MathHelper.clamp((r - 2.2) / 8, 0, 1);
			double g = 0.06 * gmul;
			Vec3d aimC = aim;
			for (int k = 0; k < 2; k++) {
				double dist = aimC.distanceTo(c);
				double q = 0.02 * dist / speed;
				double t = q < 0.9 ? Math.log(1 - q) / Math.log(0.98) : dist / speed;   // ticks, con el roce del aire
				aimC = aim.add(0, Math.min(0.5 * g * t * t, 0.3 * dist), 0);
			}
			Vec3d dir = aimC.subtract(c);
			if (dir.lengthSquared() < 1e-6) dir = p.getRotationVector();
			pe.owner = p.getUuid();
			pe.release(dir.normalize().multiply(speed), true);
			pe.gravityMul = gmul;
			if (r > OpeConfig.TAKT_BIG_RADIUS) flags |= TaktFx.F_BIG;
			if ((pe.flags() & PieceEntity.F_CHUNK) != 0) flags |= TaktFx.F_CHUNK;
			mass = (float) (pe.massKg() / 1000.0);
		} else {
			Vec3d dir = aim.subtract(c);
			if (dir.lengthSquared() < 1e-3) dir = p.getRotationVector();
			dir = dir.normalize().multiply(speed);
			e.setVelocity(dir);
			e.velocityModified = true;
			if (e instanceof LivingEntity) {
				THROWN.put(e.getId(), 40);
				THROWN_SPEED.put(e.getId(), dir.length());
			}
		}
		pw.dirty = true;
		sendFx(p.getServerWorld(), fx(TaktFx.THROW, 0, e.getId(), p.getUuid(), c, (float) speed, mass, 0, flags, index, NONE));
	}

	// ------------------------------------------------------------------ cada tick

	public static void tick(ServerPlayerEntity p, PlayerPower pw) {
		pw.ripBudget = Math.min(OpeConfig.TAKT_RIP_BUDGET, pw.ripBudget + OpeConfig.TAKT_RIP_REFILL);
		tickRips(p, pw);
		tickWaves(p, pw);
		if (pw.takt.isEmpty()) return;
		List<Entity> list = resolve(p, pw);
		if (list.isEmpty()) return;
		formation(p, pw, list);
	}

	/**
	 * Dónde va cada cosa: en la mano (la primera delante, las demás en un anillo que gira despacio), en órbita o aparcada.
	 * A las piezas les da el punto, su velocidad (para que el muelle no se quede atrás) y qué muelle usar.
	 */
	private static void formation(ServerPlayerEntity p, PlayerPower pw, List<Entity> list) {
		int n = list.size();
		Vec3d eye = p.getEyePos();
		Vec3d look = p.getRotationVector();
		Vec3d right = look.crossProduct(new Vec3d(0, 1, 0));
		if (right.lengthSquared() < 1e-4) right = new Vec3d(1, 0, 0);
		right = right.normalize();
		Vec3d up = right.crossProduct(look).normalize();
		double biggest = 0, maxS = 1;
		for (Entity e : list) {
			biggest = Math.max(biggest, e.getBoundingBox().getAverageSideLength());
			if (e instanceof PieceEntity pe) maxS = Math.max(maxS, pe.radius() / 2.5);
		}
		double time = p.age;
		PlayerPower.TaktMode mode = pw.taktMode;
		double orbitR = pw.taktDist + biggest * 0.5;
		double omega = 1.35 * Math.sqrt(3.25 / orbitR) / Math.sqrt(maxS);   // rad/s
		double ring = 1.0 + biggest * 0.7 + (n > 9 ? 0.8 : 0);
		Vec3d anchor = Vec3d.ZERO;
		if (mode == PlayerPower.TaktMode.HAND) {
			double d = pw.taktDist + biggest * 0.6;
			if (n == 1 && list.get(0) instanceof PieceEntity only && only.radius() / 2.5 > 1) {
				d = Math.max(d, Math.min(1.7 * only.radius() + 4, 24));   // un trozo grande se lleva más lejos para verlo entero
			}
			anchor = eye.add(look.multiply(d)).add(up.multiply(0.6));
		}
		for (int i = 0; i < n; i++) {
			Entity e = list.get(i);
			Vec3d target, vel;
			int drive;
			if (mode == PlayerPower.TaktMode.PARK) {
				Vec3d base = pw.parked.getOrDefault(e.getId(), e.getBoundingBox().getCenter());
				pw.parked.putIfAbsent(e.getId(), base);
				double phase = 1.1 * time / 20 + 2 * Math.PI * frac(e.getId() * 0.618034);
				target = base.add(0, 0.06 * Math.sin(phase), 0);
				vel = new Vec3d(0, 0.066 * Math.cos(phase), 0);
				drive = PieceEntity.DRIVE_PARK;
			} else if (mode == PlayerPower.TaktMode.ORBIT) {
				double beta = pw.orbitPhase + i * Math.PI * 2 / n;
				target = p.getPos().add(Math.cos(beta) * orbitR, 1.3 + Math.sin(time * 0.05 + i) * 0.4, Math.sin(beta) * orbitR);
				vel = new Vec3d(-Math.sin(beta), 0, Math.cos(beta)).multiply(orbitR * omega);
				drive = PieceEntity.DRIVE_ORBIT;
			} else if (i == 0) {
				target = anchor;
				vel = Vec3d.ZERO;
				drive = PieceEntity.DRIVE_HAND;
			} else {
				double alpha = (i - 1) * Math.PI * 2 / Math.max(1, n - 1) + time * 0.011;
				target = anchor.add(right.multiply(Math.cos(alpha) * ring)).add(up.multiply(Math.sin(alpha) * ring));
				vel = right.multiply(-Math.sin(alpha)).add(up.multiply(Math.cos(alpha))).multiply(ring * 0.22);
				drive = PieceEntity.DRIVE_HAND;
			}
			if (e instanceof PieceEntity pe) {
				pe.target = target;
				pe.targetVel = vel;
				pe.drive = drive;
			} else {
				Vec3d c = e.getBoundingBox().getCenter();
				Vec3d v = target.subtract(c).multiply(0.3);
				if (v.length() > 1.6) v = v.normalize().multiply(1.6);
				e.setVelocity(v.add(0, 0.08, 0));
				e.fallDistance = 0;
				e.velocityModified = true;
			}
		}
		if (mode == PlayerPower.TaktMode.ORBIT) {
			pw.orbitPhase += omega / 20;
			if (pw.orbitPhase > Math.PI * 2) pw.orbitPhase -= Math.PI * 2;
		}
	}

	/** Daño de los seres lanzados al chocar. */
	public static void tickThrown(ServerWorld w) {
		if (THROWN.isEmpty()) return;
		Iterator<Map.Entry<Integer, Integer>> it = THROWN.entrySet().iterator();
		while (it.hasNext()) {
			Map.Entry<Integer, Integer> en = it.next();
			Entity e = w.getEntityById(en.getKey());
			if (e == null) continue;
			if (!(e instanceof LivingEntity le) || !le.isAlive()) {
				it.remove();
				THROWN_SPEED.remove(en.getKey());
				continue;
			}
			int left = en.getValue() - 1;
			double speed = THROWN_SPEED.getOrDefault(en.getKey(), 0.0);
			if ((le.horizontalCollision || le.isOnGround()) && left < 38) {
				float dmg = (float) Math.min(20, speed * 6);
				if (dmg > 1) le.damage(w.getDamageSources().flyIntoWall(), dmg);
				it.remove();
				THROWN_SPEED.remove(en.getKey());
				continue;
			}
			if (left <= 0) {
				it.remove();
				THROWN_SPEED.remove(en.getKey());
			} else {
				en.setValue(left);
			}
		}
	}

	/** Cada tick del servidor; trabaja uno de cada 20: olvida quién llevaba lo que ya no lleva y las oleadas de quien se fue. */
	public static void serverTick(MinecraftServer server) {
		if (++serverTicks % 20 != 0) return;
		if (!HOLDERS.isEmpty()) {
			HOLDERS.entrySet().removeIf(en -> {
				PlayerPower hp = PowerManager.peek(en.getValue());
				return hp == null || !hp.takt.contains(en.getKey());
			});
		}
		if (!WAVES.isEmpty()) WAVES.removeIf(wv -> server.getPlayerManager().getPlayer(wv.owner()) == null);
	}

	/** El servidor se para: todo fuera. */
	public static void clearAll() {
		RIPPING.clear();
		HOLDERS.clear();
		WAVES.clear();
		THROWN.clear();
		THROWN_SPEED.clear();
		nextRipId = 1;
		serverTicks = 0;
	}

	// ------------------------------------------------------------------ avisos

	/** A los jugadores a 160 bloques del punto del momento. */
	public static void sendFx(ServerWorld w, TaktFx fx) {
		for (ServerPlayerEntity sp : PlayerLookup.around(w, fx.pos(), 160)) ServerPlayNetworking.send(sp, fx);
	}

	private static TaktFx fx(int kind, int id, int entityId, UUID owner, Vec3d at, float a, float b, int ticks, int flags, int state, int[] data) {
		return new TaktFx(kind, id, entityId, owner, at.x, at.y, at.z, a, b, ticks, flags, state, data);
	}

	/** Un "no" solo para quien lo pidió, con el punto al que se refiere. */
	private static void deny(ServerPlayerEntity p, int reason, Vec3d at) {
		ServerPlayNetworking.send(p, fx(TaktFx.DENY, 0, -1, p.getUuid(), at, 0f, 0f, 0, 0, reason, NONE));
	}

	private static void msg(ServerPlayerEntity p, String key) {
		p.sendMessage(Text.translatable("opeope.msg." + key), true);
	}

	private static double log2(double v) {
		return Math.log(v) / Math.log(2);
	}

	private static double frac(double v) {
		return v - Math.floor(v);
	}
}
