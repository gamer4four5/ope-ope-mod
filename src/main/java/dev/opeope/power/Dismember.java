package dev.opeope.power;

import dev.opeope.OpeConfig;
import dev.opeope.OpeOpeMod;
import dev.opeope.anatomy.Anatomy;
import dev.opeope.anatomy.AnatomyRegistry;
import dev.opeope.anatomy.DismemberState;
import dev.opeope.entity.BodyPartEntity;
import dev.opeope.net.Payloads;
import dev.opeope.registry.ModAttachments;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.EntityTrackingEvents;
import net.fabricmc.fabric.api.networking.v1.PlayerLookup;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.attribute.EntityAttribute;
import net.minecraft.entity.attribute.EntityAttributeInstance;
import net.minecraft.entity.attribute.EntityAttributeModifier;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.entity.boss.WitherEntity;
import net.minecraft.entity.boss.dragon.EnderDragonEntity;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.entity.passive.MerchantEntity;
import net.minecraft.entity.passive.TameableEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.projectile.ProjectileEntity;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Amputate estilo Law: cortar a un ser vivo en partes que siguen vivas. El estado vive en el propio ser
 * ({@link ModAttachments#DISMEMBER}); cada parte separada es una {@link BodyPartEntity}. Aquí: cortar, reunir, lo que
 * el ser deja de poder hacer (andar, saltar, atacar, ver) y la sincronización con los clientes.
 */
public final class Dismember {
	private Dismember() {}

	private static final Identifier SPEED_ID = OpeOpeMod.id("dismember_speed");
	private static final Identifier JUMP_ID = OpeOpeMod.id("dismember_jump");

	/** Lo que puede hacer un ser al que le faltan partes (se recalcula al cambiar). */
	public record Caps(int legs, int legsMissing, boolean mainHand, boolean offHand, boolean blind, double speedMul,
					   double jumpMul, float drop, float heightScale) {
		static final Caps WHOLE = new Caps(0, 0, true, true, false, 1, 1, 0, 1);
	}

	/** Servidor: por UUID del ser. */
	private static final Map<UUID, Caps> CAPS = new HashMap<>();
	/** Servidor: seres con partes fuera (cargados). */
	private static final Map<UUID, LivingEntity> ACTIVE = new HashMap<>();
	/** Servidor: cuánto lleva sin encontrarse cada parte (UUID de la BodyPartEntity). */
	private static final Map<UUID, Integer> MISSING = new HashMap<>();
	/** Servidor: el último corte dirigido de cada jugador (tiempo del mundo). */
	private static final Map<UUID, Long> LAST_CUT = new HashMap<>();
	/** Cliente: la altura de la caja de cada ser (por id de red), la rellena el cliente al llegar DismemberSync. */
	public static final Map<Integer, Float> CLIENT_HEIGHT = new ConcurrentHashMap<>();

	public static int cutsDone, reattachDone, transplantsDone, reclaimsDone, planeCutsDone;
	/** Servidor: cuánto lleva cada mob tocando su parte (para volver a pegársela). */
	private static final Map<UUID, Integer> TOUCH = new HashMap<>();
	/** Servidor: dueños de corazones (Mes) que han muerto: su cubo desaparece. */
	public static final Set<UUID> DEAD_HEARTS = java.util.Collections.newSetFromMap(new java.util.LinkedHashMap<>() {
		@Override
		protected boolean removeEldestEntry(Map.Entry<UUID, Boolean> e) {
			return size() > 512;
		}
	});

	public static void init() {
		ServerTickEvents.END_SERVER_TICK.register(Dismember::tick);
		ServerEntityEvents.ENTITY_LOAD.register((entity, world) -> {
			if (entity instanceof LivingEntity le) {
				DismemberState st = le.getAttached(ModAttachments.DISMEMBER);
				if (st != null && !st.isEmpty()) {
					ACTIVE.put(le.getUuid(), le);
					recompute(le);
				}
			} else if (entity instanceof ProjectileEntity pr && pr.getOwner() instanceof LivingEntity owner && !(owner instanceof PlayerEntity)
				&& !caps(owner).mainHand) {
				// sin la mano del arma no hay flecha (arco o ballesta de un esqueleto, un saqueador…)
				pr.discard();
			}
		});
		ServerLivingEntityEvents.ALLOW_DAMAGE.register((entity, source, amount) -> {
			// sin la mano del arma no hay golpe cuerpo a cuerpo (el que pega es directo: atacante = fuente)
			Entity a = source.getAttacker();
			if (a instanceof LivingEntity la && a == source.getSource() && !caps(la).mainHand) return false;
			return true;
		});
		ServerLivingEntityEvents.AFTER_DEATH.register((entity, source) -> DEAD_HEARTS.add(entity.getUuid()));
		ServerLivingEntityEvents.AFTER_DEATH.register((entity, source) -> {
			DismemberState st = entity.getAttached(ModAttachments.DISMEMBER);
			if (st == null || st.isEmpty() || !(entity.getWorld() instanceof ServerWorld w)) return;
			// el botín sale una sola vez, del ser; sus partes se van con un destello azul
			Set<UUID> mine = new HashSet<>(st.detached.values());
			if (st.slice != null) mine.add(st.slice);
			for (UUID id : mine) {
				if (w.getEntity(id) instanceof BodyPartEntity part) {
					vanishFx(w, part);
					part.discard();
				}
			}
			// lo que llevaba de otros (quimera) se suelta: sigue siendo de su dueño
			for (UUID id : new HashSet<>(st.foreign.values())) {
				if (w.getEntity(id) instanceof BodyPartEntity part) part.unmount();
			}
			st.detached.clear();
			st.lockedUntil.clear();
			st.foreign.clear();
			st.plane = null;
			st.slice = null;
			entity.removeAttached(ModAttachments.DISMEMBER);
			ACTIVE.remove(entity.getUuid());
			CAPS.remove(entity.getUuid());
			sync(entity);
		});
		EntityTrackingEvents.START_TRACKING.register((entity, player) -> {
			if (entity instanceof LivingEntity le) {
				DismemberState st = le.getAttached(ModAttachments.DISMEMBER);
				if (st != null && !st.isEmpty()) ServerPlayNetworking.send(player, payload(le));
			}
		});
		ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
			// un jugador que vuelve lo hace entero (sus partes ya no están)
			ServerPlayerEntity p = handler.getPlayer();
			DismemberState st = p.getAttached(ModAttachments.DISMEMBER);
			if (st != null && !st.isEmpty()) {
				st.detached.clear();
				st.lockedUntil.clear();
				st.foreign.clear();
				st.plane = null;
				st.slice = null;
				st.impaired.clear();
				p.removeAttached(ModAttachments.DISMEMBER);
				recompute(p);
			}
		});
		ServerLifecycleEvents.SERVER_STOPPED.register(server -> {
			CAPS.clear();
			ACTIVE.clear();
			MISSING.clear();
			LAST_CUT.clear();
			TOUCH.clear();
			DEAD_HEARTS.clear();
			BodyPartEntity.LIVE = 0;
		});
	}

	// ------------------------------------------------------------------ preguntas

	public static DismemberState state(LivingEntity le) {
		return le.getAttached(ModAttachments.DISMEMBER);
	}

	/** Lo que puede hacer (servidor). Un ser entero lo puede todo. */
	public static Caps caps(LivingEntity le) {
		Caps c = CAPS.get(le.getUuid());
		return c == null ? Caps.WHOLE : c;
	}

	/** Para los mixins de las dimensiones (los dos lados). */
	public static float heightScale(Entity e) {
		if (e.getWorld() != null && e.getWorld().isClient) return CLIENT_HEIGHT.getOrDefault(e.getId(), 1f);
		Caps c = CAPS.get(e.getUuid());
		return c == null ? 1f : c.heightScale;
	}

	/** ¿Ha perdido la vista (la cabeza)? Para el mixin de MobEntity.setTarget. */
	public static boolean blind(Entity e) {
		if (e.getWorld() == null || e.getWorld().isClient) return false;
		Caps c = CAPS.get(e.getUuid());
		return c != null && c.blind;
	}

	public static boolean owns(LivingEntity v, BodyPartEntity part) {
		DismemberState st = state(v);
		return st != null && (st.detached.containsValue(part.getUuid()) || part.getUuid().equals(st.slice));
	}

	/** ¿Lleva ese ser puesta esa parte de otro (quimera)? */
	public static boolean hosts(LivingEntity host, BodyPartEntity part) {
		DismemberState st = state(host);
		return st != null && st.foreign.containsValue(part.getUuid());
	}

	/** Lo que de verdad le falta (lo cortado, menos los sitios que ocupa una parte de otro). */
	public static Set<String> gone(DismemberState st) {
		if (st == null) return Set.of();
		if (st.foreign.isEmpty()) return st.detached.keySet();
		Set<String> g = new HashSet<>(st.detached.keySet());
		g.removeAll(st.foreign.keySet());
		return g;
	}

	/** El centro del sitio de esa parte en el ser, en el mundo (para colocar una parte trasplantada). */
	public static Vec3d slotCenter(LivingEntity le, String seg) {
		Anatomy a = anatomy(le);
		Anatomy.Segment s = a == null ? null : a.get(seg);
		if (s == null) return le.getBoundingBox().getCenter();
		Vec3d feet = le.getPos().add(0, -caps(le).drop, 0);
		return feet.add(Anatomy.toWorld(s.center(), le.getBodyYaw(), le.getScale() * (le.isBaby() ? 0.5 : 1)));
	}

	public static Anatomy anatomy(LivingEntity le) {
		return AnatomyRegistry.get(le.getType());
	}

	/** ¿Se puede cortar por partes? null = sí; si no, por qué. */
	public static String refusal(LivingEntity le) {
		Anatomy a = anatomy(le);
		if (a == null) return "sin anatomía";
		if (a.excluded || OpeConfig.EXCLUDED_ENTITIES.contains(EntityType.getId(le.getType()).toString())) return "excluido";
		if (le instanceof EnderDragonEntity || le instanceof WitherEntity) return "jefe";
		if (le.isInvulnerable()) return "invulnerable";
		if (le instanceof PlayerEntity pl && (!OpeConfig.ALLOW_PLAYERS || pl.isCreative() || pl.isSpectator())) return "jugador";
		if (le instanceof MerchantEntity && !OpeConfig.ALLOW_VILLAGERS) return "aldeano";
		if (le instanceof TameableEntity t && t.isTamed() && !OpeConfig.ALLOW_PETS) return "mascota";
		return null;
	}

	public static int partCount(LivingEntity le) {
		DismemberState st = state(le);
		if (st == null) return 0;
		Set<UUID> mine = new HashSet<>(st.detached.values());
		if (st.slice != null) mine.add(st.slice);
		return mine.size();
	}

	// ------------------------------------------------------------------ cortar

	/** Lo que pide el cliente (Amputate dirigido): el servidor lo comprueba todo. */
	public static void handleCutRequest(ServerPlayerEntity p, int entityId, String segment) {
		if (!PowerManager.canUse(p)) return;
		Entity e = p.getServerWorld().getEntityById(entityId);
		if (!(e instanceof LivingEntity le) || !le.isAlive() || le == p) return;
		if (!PowerManager.inRoom(p, le.getBoundingBox().getCenter())) {
			PowerManager.noRoom(p);
			return;
		}
		if (le.squaredDistanceTo(p) > OpeConfig.CUT_RANGE * OpeConfig.CUT_RANGE) return;
		long now = p.getServerWorld().getTime();
		Long last = LAST_CUT.get(p.getUuid());
		if (last != null && now - last < OpeConfig.AMPUTATE_COOLDOWN && now >= last) return;
		String why = refusal(le);
		if (why != null) {
			p.sendMessage(Text.translatable("opeope.msg.part_refused", le.getDisplayName()), true);
			return;
		}
		Anatomy a = anatomy(le);
		List<String> segs = resolve(a, state(le), segment);
		if (segs.isEmpty()) return;
		if (cut(le, segs, p) != null) {
			LAST_CUT.put(p.getUuid(), now);
			PowerManager.get(p).roomUsedDuringHold = true;
		}
	}

	/** Qué partes se van si se pide «segment»: la parte con lo que cuelga de ella, o un grupo (lower_half…). */
	public static List<String> resolve(Anatomy a, DismemberState st, String segment) {
		Collection<String> gone = st == null ? Set.of() : st.detached.keySet();
		Set<String> out = new LinkedHashSet<>();
		List<String> group = a.cutGroups.get(segment);
		if (group != null) {
			for (String m : group) if (!m.equals(a.core) && a.attached(m, gone)) out.addAll(a.subtree(m));
		} else if (a.segments.containsKey(segment) && !segment.equals(a.core) && a.attached(segment, gone)) {
			out.addAll(a.subtree(segment));
		}
		out.removeIf(s -> gone.contains(s) || s.equals(a.core));
		return new ArrayList<>(out);
	}

	/**
	 * Separa esas partes en una BodyPartEntity (una sola, aunque sean varias: la mitad de abajo son las dos piernas). Sin
	 * daño (el anime: el cortado no muere por el corte). null si no se pudo (topes, nada que cortar).
	 */
	public static BodyPartEntity cut(LivingEntity le, List<String> segs, ServerPlayerEntity cutter) {
		if (!(le.getWorld() instanceof ServerWorld w) || !le.isAlive()) return null;
		Anatomy a = anatomy(le);
		if (a == null) return null;
		DismemberState st = state(le);
		if (st == null) st = new DismemberState();
		final DismemberState fst = st;
		List<String> list = new ArrayList<>();
		for (String s : segs) if (a.segments.containsKey(s) && !s.equals(a.core) && !fst.detached.containsKey(s)) list.add(s);
		if (list.isEmpty()) return null;
		if (partCount(le) >= OpeConfig.MAX_PARTS_PER_ENTITY || BodyPartEntity.LIVE >= OpeConfig.MAX_PARTS_WORLD) {
			if (cutter != null) cutter.sendMessage(Text.translatable("opeope.msg.part_limit"), true);
			return null;
		}
		BodyPartEntity part = BodyPartEntity.create(w, le, list, cutter == null ? null : cutter.getUuid());
		double scale = part.victimScale();
		float yaw = le.getBodyYaw();
		double[] box = a.union(list);
		Vec3d mc = new Vec3d((box[0] + box[3]) / 2, (box[1] + box[4]) / 2, (box[2] + box[5]) / 2);
		Vec3d center = le.getPos().add(Anatomy.toWorld(mc, yaw, scale));
		Anatomy.Segment root = a.get(list.get(0));
		Vec3d joint = le.getPos().add(Anatomy.toWorld(root.jointVec(), yaw, scale));
		part.setPosition(center.x, Math.max(le.getY(), center.y - part.getHeight() / 2), center.z);
		Vec3d out = center.subtract(le.getBoundingBox().getCenter());
		out = new Vec3d(out.x, 0, out.z);
		if (out.lengthSquared() < 1e-4) out = Vec3d.fromPolar(0, yaw + 90);
		out = out.normalize().multiply(0.16);
		part.kick(new Vec3d(out.x, 0.2, out.z));
		w.spawnEntity(part);
		for (String s : list) fst.detached.put(s, part.getUuid());
		if (cutter != null) fst.cutter = cutter.getUuid();
		le.setAttached(ModAttachments.DISMEMBER, fst);
		ACTIVE.put(le.getUuid(), le);
		// sin piernas no se va montado ni lleva a nadie encima
		boolean legs = false;
		for (String s : list) if (a.get(s).has("locomotion")) legs = true;
		if (legs) {
			le.stopRiding();
			le.removeAllPassengers();
		}
		recompute(le);
		sync(le);
		cutFx(w, joint, le);
		cutsDone++;
		return part;
	}

	/**
	 * El barrido de Amputate (el tajo por plano) atraviesa un ser con anatomía: lo que queda al otro lado de una
	 * articulación se separa. Si el plano parte el núcleo (el torso) en vez de pasar por una articulación, devuelve false
	 * y el ser se corta como antes (piezas que flotan y se recomponen).
	 */
	public static boolean sweep(ServerPlayerEntity cutter, LivingEntity le, List<CutEngine.Plane> planes, boolean radio) {
		if (refusal(le) != null || !le.isAlive()) return false;
		Anatomy a = anatomy(le);
		Anatomy.Segment core = a.get(a.core);
		if (core == null) return false;
		DismemberState st = state(le);
		Set<String> gone = new HashSet<>(st == null ? Set.of() : st.detached.keySet());
		Vec3d feet = le.getPos();
		float yaw = le.getBodyYaw();
		double scale = le.getScale();
		Vec3d coreC = feet.add(Anatomy.toWorld(core.center(), yaw, scale));
		double coreMin = Math.min(core.box()[3] - core.box()[0], Math.min(core.box()[4] - core.box()[1], core.box()[5] - core.box()[2])) / 16.0 * scale;
		double thr = Math.max(0.06, coreMin * 0.3);
		Set<String> picked = new LinkedHashSet<>();
		for (CutEngine.Plane pl : planes) {
			double dc = pl.n().dotProduct(coreC.subtract(pl.p()));
			if (Math.abs(dc) < thr) {
				// por el medio del torso: corte libre por ese plano (si se puede); si no, se cortará como siempre
				if (OpeConfig.FREE_PLANE_CUTS && picked.isEmpty() && (st == null || st.plane == null) && planeCut(cutter, le, pl, radio)) return true;
				continue;
			}
			for (Anatomy.Segment s : a.segments.values()) {
				if (s.id().equals(a.core) || picked.contains(s.id())) continue;
				Set<String> both = new HashSet<>(gone);
				both.addAll(picked);
				if (!a.attached(s.id(), both)) continue;
				Vec3d c = feet.add(Anatomy.toWorld(s.center(), yaw, scale));
				double ds = pl.n().dotProduct(c.subtract(pl.p()));
				if (Math.abs(ds) < 0.02 || Math.signum(ds) == Math.signum(dc)) continue;
				picked.addAll(a.subtree(s.id()));
			}
		}
		picked.removeAll(gone);
		if (picked.isEmpty()) return false;
		// raíces: lo cortado cuyo padre sigue unido; un grupo (lower_half) con todas sus raíces cortadas va en una pieza
		List<String> roots = new ArrayList<>();
		for (String s : picked) {
			String par = a.get(s).parent();
			if (par == null || !picked.contains(par)) roots.add(s);
		}
		List<List<String>> parts = new ArrayList<>();
		Set<String> used = new HashSet<>();
		for (List<String> g : a.cutGroups.values()) {
			if (g.size() < 2 || !roots.containsAll(g) || used.stream().anyMatch(g::contains)) continue;
			List<String> one = new ArrayList<>();
			for (String r : g) one.addAll(a.subtree(r));
			one.removeIf(gone::contains);
			parts.add(one);
			used.addAll(g);
		}
		for (String r : roots) {
			if (used.contains(r)) continue;
			List<String> one = new ArrayList<>(a.subtree(r));
			one.removeIf(gone::contains);
			parts.add(one);
		}
		boolean any = false;
		for (List<String> p : parts) {
			BodyPartEntity part = cut(le, p, cutter);
			if (part == null) continue;
			any = true;
			if (radio) lock(le, part, OpeConfig.RADIO_LOCK_TICKS);
		}
		return any;
	}

	/**
	 * Corte libre (estilo Law, en cualquier ángulo): el plano parte el cuerpo. El ser se queda con el lado de su cabeza (o
	 * del centro del torso) y lo demás sale como una pieza viva que lleva las partes enteras del otro lado y la otra mitad
	 * de las atravesadas. Las caras de corte siguen la forma de cada parte cortada.
	 */
	public static boolean planeCut(ServerPlayerEntity cutter, LivingEntity le, CutEngine.Plane wp, boolean radio) {
		if (!(le.getWorld() instanceof ServerWorld w) || refusal(le) != null || !le.isAlive()) return false;
		Anatomy a = anatomy(le);
		if (a == null || a.get(a.core) == null) return false;
		DismemberState st = state(le);
		if (st != null && st.plane != null) return false;
		if (partCount(le) >= OpeConfig.MAX_PARTS_PER_ENTITY || BodyPartEntity.LIVE >= OpeConfig.MAX_PARTS_WORLD) return false;
		float yaw = le.getBodyYaw();
		double scale = le.getScale() * (le.isBaby() ? 0.5 : 1);
		Vec3d feet = le.getPos().add(0, -caps(le).drop, 0);
		Vec3d nj = Anatomy.toModel(wp.n(), yaw, scale);
		if (nj.lengthSquared() < 1e-9) return false;
		nj = nj.normalize();
		Vec3d pj = Anatomy.toModel(wp.p().subtract(feet), yaw, scale);
		float[] pl = {(float) nj.x, (float) nj.y, (float) nj.z, (float) nj.dotProduct(pj)};
		Set<String> gone = gone(st);
		if (Anatomy.classify(a.get(a.core).box(), pl) != 0) return false;
		// ¿con qué lado se queda el ser? con el de la cabeza; si no se decide, con el del centro del torso
		int visPos = 0, visNeg = 0;
		for (Anatomy.Segment sg : a.segments.values()) {
			if (!sg.has("vision") || !a.attached(sg.id(), gone)) continue;
			if (Anatomy.positiveFraction(sg.box(), pl) >= 0.5) visPos++;
			else visNeg++;
		}
		Vec3d cc = a.get(a.core).center();
		boolean flip = visPos != visNeg ? visPos > visNeg : pl[0] * cc.x + pl[1] * cc.y + pl[2] * cc.z - pl[3] > 0;
		if (flip) pl = new float[]{-pl[0], -pl[1], -pl[2], -pl[3]};
		List<String> slice = new ArrayList<>();
		List<String> far = new ArrayList<>();
		Set<String> impaired = new LinkedHashSet<>();
		for (Anatomy.Segment sg : a.segments.values()) {
			if (!a.attached(sg.id(), gone)) continue;
			int c = Anatomy.classify(sg.box(), pl);
			if (c > 0 && !sg.id().equals(a.core)) {
				slice.add(sg.id());
				far.add(sg.id());
			} else if (c == 0 || sg.id().equals(a.core)) {
				slice.add(sg.id());
				if (!sg.id().equals(a.core) && !sg.functions().isEmpty() && Anatomy.positiveFraction(sg.box(), pl) >= 0.5) impaired.add(sg.id());
			}
		}
		if (slice.size() < 1) return false;
		BodyPartEntity part = BodyPartEntity.createSlice(w, le, slice, pl, cutter == null ? null : cutter.getUuid());
		double[] box = part.modelBox();
		if (box == null) return false;
		Vec3d mc = new Vec3d((box[0] + box[3]) / 2, (box[1] + box[4]) / 2, (box[2] + box[5]) / 2);
		Vec3d center = feet.add(Anatomy.toWorld(mc, yaw, scale));
		part.setPosition(center.x, Math.max(le.getY(), center.y - part.getHeight() / 2), center.z);
		Vec3d out = Anatomy.toWorld(new Vec3d(pl[0], pl[1], pl[2]), yaw, 16).normalize();
		part.kick(new Vec3d(out.x * 0.18, 0.12 + Math.max(0, out.y) * 0.15, out.z * 0.18));
		w.spawnEntity(part);
		if (st == null) st = new DismemberState();
		for (String sg : far) st.detached.put(sg, part.getUuid());
		st.plane = pl;
		st.slice = part.getUuid();
		st.impaired.clear();
		st.impaired.addAll(impaired);
		if (cutter != null) st.cutter = cutter.getUuid();
		le.setAttached(ModAttachments.DISMEMBER, st);
		ACTIVE.put(le.getUuid(), le);
		boolean legs = false;
		for (String sg : far) if (a.get(sg).has("locomotion")) legs = true;
		if (legs) {
			le.stopRiding();
			le.removeAllPassengers();
		}
		recompute(le);
		sync(le);
		cutFx(w, le.getBoundingBox().getCenter(), le);
		if (radio) lock(le, part, OpeConfig.RADIO_LOCK_TICKS);
		planeCutsDone++;
		cutsDone++;
		return true;
	}

	/** Radio Knife: esas partes no se pueden reunir (ni Shambles, ni la IA, ni el tiempo) hasta que pase ticks. */
	public static void lock(LivingEntity le, BodyPartEntity part, int ticks) {
		DismemberState st = state(le);
		if (st == null) return;
		long until = le.getWorld().getTime() + ticks;
		for (Map.Entry<String, UUID> e : st.detached.entrySet()) if (e.getValue().equals(part.getUuid())) st.lockedUntil.put(e.getKey(), until);
		if (part.getUuid().equals(st.slice)) st.lockedUntil.put("#slice", until);
		part.setLocked(true);
		sync(le);
	}

	public static boolean isLocked(LivingEntity le, BodyPartEntity part) {
		DismemberState st = state(le);
		if (st == null) return false;
		long now = le.getWorld().getTime();
		if (part.getUuid().equals(st.slice)) {
			Long su = st.lockedUntil.get("#slice");
			if (su != null && su > now) return true;
		}
		for (Map.Entry<String, UUID> e : st.detached.entrySet()) {
			if (!e.getValue().equals(part.getUuid())) continue;
			Long until = st.lockedUntil.get(e.getKey());
			if (until != null && until > now) return true;
		}
		return false;
	}

	// ------------------------------------------------------------------ reunir

	/** Vuelve a unir esa parte a su dueño. force: aunque esté bloqueada (Radio Knife) — el tiempo de vida, el vacío. */
	public static boolean reattach(LivingEntity le, BodyPartEntity part, boolean force) {
		DismemberState st = state(le);
		if (st == null) {
			part.discard();
			return false;
		}
		UUID id = part.getUuid();
		long now = le.getWorld().getTime();
		if (!force && isLocked(le, part)) return false;
		Vec3d at = part.getBoundingBox().getCenter();
		if (part.mounted()) part.unmount();
		// el sitio lo ocupaba una parte de otro: esa se suelta (sigue siendo de su dueño)
		if (!st.foreign.isEmpty() && le.getWorld() instanceof ServerWorld sw0) {
			for (String seg : part.segments()) {
				UUID f = st.foreign.remove(seg);
				if (f != null && sw0.getEntity(f) instanceof BodyPartEntity fp) fp.unmountQuiet();
			}
		}
		st.detached.values().removeIf(id::equals);
		if (id.equals(st.slice)) {
			st.slice = null;
			st.plane = null;
			st.impaired.clear();
			st.lockedUntil.remove("#slice");
		}
		st.lockedUntil.keySet().removeIf(k -> !k.equals("#slice") && !st.detached.containsKey(k));
		part.discard();
		after(le, st);
		if (le.getWorld() instanceof ServerWorld w) {
			Box b = le.getBoundingBox();
			w.spawnParticles(CutEngine.ROOM_DUST, at.x, at.y, at.z, 10, 0.15, 0.15, 0.15, 0);
			w.spawnParticles(ParticleTypes.END_ROD, b.getCenter().x, b.getCenter().y, b.getCenter().z, 12, b.getLengthX() / 2, b.getLengthY() / 3, b.getLengthZ() / 2, 0.01);
			w.playSound(null, le.getX(), le.getY(), le.getZ(), SoundEvents.BLOCK_AMETHYST_BLOCK_CHIME, SoundCategory.PLAYERS, 1f, 1.25f);
		}
		reattachDone++;
		return true;
	}

	/** Shambles sobre una parte y luego sobre su dueño (o al revés): la reúne. true si era ese caso (aunque esté bloqueada). */
	public static boolean shamblesPair(ServerPlayerEntity p, Entity a, Entity b) {
		BodyPartEntity part = a instanceof BodyPartEntity x ? x : b instanceof BodyPartEntity y ? y : null;
		Entity other = part == a ? b : a;
		if (part == null || !(other instanceof LivingEntity le)) return false;
		if (!owns(le, part)) return OpeConfig.ALLOW_TRANSPLANT && transplant(p, part, le);
		if (!reattach(le, part, false)) {
			p.sendMessage(Text.translatable("opeope.msg.part_locked"), true);
			if (le.getWorld() instanceof ServerWorld w) {
				Vec3d c = part.getBoundingBox().getCenter();
				w.spawnParticles(ParticleTypes.ELECTRIC_SPARK, c.x, c.y, c.z, 8, 0.15, 0.15, 0.15, 0.05);
			}
		}
		return true;
	}

	/**
	 * Quimera / trasplante (Shambles parte de A → B): la parte de A ocupa en B el sitio del mismo nombre (cabeza por
	 * cabeza, brazo por brazo). Lo que B tenía ahí sale como parte suelta suya. La parte sigue siendo de A (el daño va a
	 * A), pero B gana su función (una cabeza le da vista). true si era un caso de trasplante (aunque se rechace).
	 */
	public static boolean transplant(ServerPlayerEntity p, BodyPartEntity part, LivingEntity host) {
		LivingEntity owner = part.victim();
		if (owner == null || owner == host || !host.isAlive() || part.mounted()) return false;
		String seg = part.rootSegment();
		Anatomy ha = anatomy(host);
		if (seg == null || ha == null || !ha.segments.containsKey(seg) || seg.equals(ha.core)) {
			if (p != null) p.sendMessage(Text.translatable("opeope.msg.part_no_slot", host.getDisplayName()), true);
			return true;
		}
		if (refusal(host) != null) {
			if (p != null) p.sendMessage(Text.translatable("opeope.msg.part_refused", host.getDisplayName()), true);
			return true;
		}
		DismemberState hs = state(host);
		if (hs != null && hs.foreign.containsKey(seg)) {
			if (p != null) p.sendMessage(Text.translatable("opeope.msg.part_no_slot", host.getDisplayName()), true);
			return true;
		}
		// lo de B en ese sitio sale fuera (si aún lo tiene)
		if (hs == null || !hs.detached.containsKey(seg)) {
			List<String> own = resolve(ha, hs, seg);
			if (!own.isEmpty() && cut(host, own, p) == null) return true;
		}
		hs = state(host);
		if (hs == null) hs = new DismemberState();
		hs.foreign.put(seg, part.getUuid());
		host.setAttached(ModAttachments.DISMEMBER, hs);
		ACTIVE.put(host.getUuid(), host);
		part.mount(host, seg);
		recompute(host);
		sync(host);
		if (host.getWorld() instanceof ServerWorld w) {
			Vec3d c = slotCenter(host, seg);
			w.spawnParticles(CutEngine.ROOM_DUST, c.x, c.y, c.z, 14, 0.15, 0.15, 0.15, 0);
			w.spawnParticles(ParticleTypes.END_ROD, c.x, c.y, c.z, 8, 0.1, 0.1, 0.1, 0.02);
			w.playSound(null, c.x, c.y, c.z, SoundEvents.BLOCK_AMETHYST_BLOCK_CHIME, SoundCategory.PLAYERS, 1f, 0.9f);
			w.playSound(null, c.x, c.y, c.z, SoundEvents.BLOCK_BEACON_POWER_SELECT, SoundCategory.PLAYERS, 0.6f, 1.8f);
		}
		transplantsDone++;
		return true;
	}

	/** La parte trasplantada se va del sitio de su anfitrión (vuelve con su dueño, o el anfitrión murió). */
	public static void releaseForeign(LivingEntity host, BodyPartEntity part) {
		DismemberState st = state(host);
		if (st == null) return;
		if (st.foreign.values().removeIf(part.getUuid()::equals)) after(host, st);
	}

	/** Lo une todo (al reconectar, al cerrar el ROOM si así se pide…). */
	public static void reattachAll(LivingEntity le, boolean force) {
		DismemberState st = state(le);
		if (st == null || !(le.getWorld() instanceof ServerWorld w)) return;
		Set<UUID> mine = new HashSet<>(st.detached.values());
		if (st.slice != null) mine.add(st.slice);
		for (UUID id : mine) {
			if (w.getEntity(id) instanceof BodyPartEntity part) reattach(le, part, force);
			else if (force) {
				st.detached.values().removeIf(id::equals);
				if (id.equals(st.slice)) {
					st.slice = null;
					st.plane = null;
					st.impaired.clear();
				}
			}
		}
		after(le, st);
	}

	private static void after(LivingEntity le, DismemberState st) {
		if (st.isEmpty()) {
			le.removeAttached(ModAttachments.DISMEMBER);
			ACTIVE.remove(le.getUuid());
		} else {
			le.setAttached(ModAttachments.DISMEMBER, st);
		}
		recompute(le);
		sync(le);
	}

	// ------------------------------------------------------------------ lo que deja de poder hacer

	public static void recompute(LivingEntity le) {
		DismemberState st = state(le);
		Anatomy a = anatomy(le);
		if (st == null || st.isEmpty() || a == null) {
			CAPS.remove(le.getUuid());
			setModifier(le, EntityAttributes.GENERIC_MOVEMENT_SPEED, SPEED_ID, 1);
			setModifier(le, EntityAttributes.GENERIC_JUMP_STRENGTH, JUMP_ID, 1);
			le.calculateDimensions();
			return;
		}
		Set<String> gone = new HashSet<>(gone(st));
		gone.addAll(st.impaired);
		int legs = a.legs, missing = 0;
		boolean main = true, off = true, vision = false, visionLeft = false, arms = false;
		for (Anatomy.Segment s : a.segments.values()) {
			boolean on = a.attached(s.id(), gone);
			if (s.has("locomotion") && !on) missing++;
			if (s.has("main_hand") && !on) main = false;
			if (s.has("off_hand") && !on) off = false;
			if (s.has("vision")) {
				vision = true;
				if (on) visionLeft = true;
			}
			if ((s.has("main_hand") || s.has("off_hand")) && on) arms = true;
		}
		double speed = 1, jump = 1;
		if (legs > 0 && missing > 0) {
			if (legs == 2) {
				speed = missing == 1 ? 0.35 : arms ? 0.03 : 0;   // se arrastra con los brazos
				jump = missing == 1 ? 0.5 : 0;
			} else if (missing >= legs) {
				speed = 0;
				jump = 0;
			} else {
				speed = Math.max(0, 1 - 0.22 * missing * (4.0 / legs));
				jump = Math.max(0, 1 - 0.25 * missing * (4.0 / legs));
			}
		}
		float drop = 0, hs = 1;
		if (legs > 0 && missing >= legs) {
			drop = (float) (a.legHeight / 16.0 * le.getScale());
			hs = (float) Math.max(0.2, (a.height - a.legHeight) / a.height);
		}
		Caps c = new Caps(legs, missing, main, off, vision && !visionLeft, speed, jump, drop, hs);
		CAPS.put(le.getUuid(), c);
		setModifier(le, EntityAttributes.GENERIC_MOVEMENT_SPEED, SPEED_ID, speed);
		setModifier(le, EntityAttributes.GENERIC_JUMP_STRENGTH, JUMP_ID, jump);
		le.calculateDimensions();
		if (c.blind && le instanceof MobEntity mob) mob.setTarget(null);
	}

	private static void setModifier(LivingEntity le, RegistryEntry<EntityAttribute> attr, Identifier id, double mul) {
		EntityAttributeInstance inst = le.getAttributeInstance(attr);
		if (inst == null) return;
		inst.removeModifier(id);
		if (mul < 0.9999) inst.addTemporaryModifier(new EntityAttributeModifier(id, mul - 1, EntityAttributeModifier.Operation.ADD_MULTIPLIED_TOTAL));
	}

	// ------------------------------------------------------------------ cada tick

	private static void tick(MinecraftServer server) {
		Iterator<Map.Entry<UUID, LivingEntity>> it = ACTIVE.entrySet().iterator();
		List<LivingEntity> resync = new ArrayList<>();
		while (it.hasNext()) {
			LivingEntity le = it.next().getValue();
			if (le.isRemoved() || !(le.getWorld() instanceof ServerWorld w)) {
				it.remove();
				CAPS.remove(le.getUuid());
				continue;
			}
			DismemberState st = state(le);
			if (st == null || st.isEmpty()) {
				it.remove();
				continue;
			}
			boolean changed = false;
			// una parte que ya no está (otra dimensión, perdida): pasado un rato vuelve a su sitio
			Set<UUID> mine = new HashSet<>(st.detached.values());
			if (st.slice != null) mine.add(st.slice);
			for (UUID id : mine) {
				if (w.getEntity(id) instanceof BodyPartEntity) {
					MISSING.remove(id);
					continue;
				}
				int n = MISSING.merge(id, 1, Integer::sum);
				if (n > OpeConfig.PART_ORPHAN_TICKS) {
					MISSING.remove(id);
					st.detached.values().removeIf(id::equals);
					if (id.equals(st.slice)) {
						st.slice = null;
						st.plane = null;
						st.impaired.clear();
					}
					changed = true;
				}
			}
			// una parte de otro puesta aquí que ya no existe (su dueño murió): el sitio vuelve a estar vacío
			for (UUID id : new HashSet<>(st.foreign.values())) {
				if (!(w.getEntity(id) instanceof BodyPartEntity fp) || fp.isRemoved()) {
					int n = MISSING.merge(id, 1, Integer::sum);
					if (n > 40) {
						MISSING.remove(id);
						st.foreign.values().removeIf(id::equals);
						changed = true;
					}
				}
			}
			long now = w.getTime();
			if (st.lockedUntil.values().removeIf(t -> t <= now)) changed = true;
			if (changed) {
				st.lockedUntil.keySet().removeIf(k -> !k.equals("#slice") && !st.detached.containsKey(k));
				resync.add(le);
			}
			Caps c = caps(le);
			if (le instanceof MobEntity mob && !st.detached.isEmpty()) reclaim(w, mob, st, c);
			if (OpeConfig.HEAD_CONTROLS_AI && le instanceof MobEntity mob && le.age % 10 == 0) headControl(w, mob, st);
			if (c.blind && le instanceof MobEntity mob) {
				mob.setTarget(null);
				// a ciegas: gira la vista al azar de vez en cuando
				if (le.age % 15 == 0) mob.setHeadYaw(mob.getHeadYaw() + (w.random.nextFloat() - 0.5f) * 120f);
			}
		}
		for (LivingEntity le : resync) {
			DismemberState st = state(le);
			if (st != null) after(le, st);
		}
	}

	/**
	 * La IA de quien ha perdido partes: va a por la más cercana (sin cabeza, solo si la tiene al lado: a tientas) y se la
	 * vuelve a pegar tras tocarla un rato. Mientras tanto no persigue a nadie (primero, recomponerse).
	 */
	private static void reclaim(ServerWorld w, MobEntity mob, DismemberState st, Caps c) {
		if (!OpeConfig.MOBS_RECLAIM_PARTS || mob.isAiDisabled() || c.speedMul <= 0) return;
		double range = c.blind ? 3 : OpeConfig.RECLAIM_RANGE;
		BodyPartEntity best = null;
		double bd = range * range;
		long now = w.getTime();
		for (Map.Entry<String, UUID> e : st.detached.entrySet()) {
			if (st.foreign.containsKey(e.getKey())) continue;
			Long until = st.lockedUntil.get(e.getKey());
			if (until != null && until > now) continue;
			if (!(w.getEntity(e.getValue()) instanceof BodyPartEntity part) || part.mounted() || Takt.held(part.getId())) continue;
			double d = part.squaredDistanceTo(mob);
			if (d < bd) {
				bd = d;
				best = part;
			}
		}
		UUID id = mob.getUuid();
		if (best == null) {
			TOUCH.remove(id);
			return;
		}
		boolean distracted = mob.getTarget() != null;
		if (distracted) mob.setTarget(null);
		boolean touching = mob.getBoundingBox().expand(0.6).intersects(best.getBoundingBox());
		if (touching) {
			int t = TOUCH.merge(id, 1, Integer::sum);
			mob.getNavigation().stop();
			mob.getLookControl().lookAt(best, 30f, 30f);
			if (t % 8 == 0) {
				Vec3d pc = best.getBoundingBox().getCenter();
				w.spawnParticles(CutEngine.ROOM_DUST, pc.x, pc.y, pc.z, 3, 0.12, 0.12, 0.12, 0);
			}
			if (t >= OpeConfig.RECLAIM_TOUCH_TICKS) {
				TOUCH.remove(id);
				if (reattach(mob, best, false)) reclaimsDone++;
			}
		} else {
			TOUCH.remove(id);
			if (bd < 3 * 3) {
				// ya está al lado: derecho a por ella (el camino del navegador a veces no llega al último medio bloque)
				mob.getNavigation().stop();
				mob.getMoveControl().moveTo(best.getX(), best.getY(), best.getZ(), 1.1);
				mob.getLookControl().lookAt(best, 30f, 30f);
			} else if (distracted || mob.age % 5 == 0 || mob.getNavigation().isIdle()) {
				// si otra meta (atacar) le cambió el camino, se lo vuelve a poner hacia su parte
				mob.getNavigation().startMovingTo(best.getX(), best.getY(), best.getZ(), 1.1);
			}
		}
	}

	/** Con una cabeza de otro puesta, persigue a quien persigue el dueño de esa cabeza. */
	private static void headControl(ServerWorld w, MobEntity mob, DismemberState st) {
		if (st.foreign.isEmpty()) return;
		Anatomy a = anatomy(mob);
		if (a == null) return;
		for (Map.Entry<String, UUID> e : st.foreign.entrySet()) {
			Anatomy.Segment s = a.get(e.getKey());
			if (s == null || !s.has("vision")) continue;
			if (!(w.getEntity(e.getValue()) instanceof BodyPartEntity part) || !(part.victim() instanceof MobEntity owner)) continue;
			LivingEntity t = owner.getTarget();
			if (t != null && t != mob && t.isAlive() && mob.getTarget() != t) mob.setTarget(t);
		}
	}

	// ------------------------------------------------------------------ red y efectos

	public static Payloads.DismemberSync payload(LivingEntity le) {
		DismemberState st = state(le);
		Caps c = caps(le);
		if (st == null || st.isEmpty()) return new Payloads.DismemberSync(le.getId(), List.of(), List.of(), 0f, 1f, List.of(), new float[0]);
		return new Payloads.DismemberSync(le.getId(), new ArrayList<>(st.detached.keySet()), new ArrayList<>(st.lockedUntil.keySet()),
			c.drop, c.heightScale, new ArrayList<>(st.foreign.keySet()), st.plane == null ? new float[0] : st.plane.clone());
	}

	public static void sync(LivingEntity le) {
		Payloads.DismemberSync p = payload(le);
		for (ServerPlayerEntity sp : PlayerLookup.tracking(le)) ServerPlayNetworking.send(sp, p);
		if (le instanceof ServerPlayerEntity self) ServerPlayNetworking.send(self, p);
	}

	private static void cutFx(ServerWorld w, Vec3d joint, LivingEntity le) {
		w.spawnParticles(CutEngine.ROOM_DUST, joint.x, joint.y, joint.z, 16, 0.12, 0.12, 0.12, 0);
		w.spawnParticles(ParticleTypes.END_ROD, joint.x, joint.y, joint.z, 6, 0.05, 0.05, 0.05, 0.03);
		w.playSound(null, le.getX(), le.getY(), le.getZ(), SoundEvents.ENTITY_PLAYER_ATTACK_SWEEP, SoundCategory.PLAYERS, 0.8f, 1.7f);
		w.playSound(null, le.getX(), le.getY(), le.getZ(), SoundEvents.BLOCK_AMETHYST_BLOCK_CHIME, SoundCategory.PLAYERS, 0.9f, 1.8f);
	}

	public static void vanishFx(ServerWorld w, BodyPartEntity part) {
		Vec3d c = part.getBoundingBox().getCenter();
		w.spawnParticles(ParticleTypes.END_ROD, c.x, c.y, c.z, 10, 0.15, 0.15, 0.15, 0.04);
		w.spawnParticles(CutEngine.ROOM_DUST, c.x, c.y, c.z, 12, 0.2, 0.2, 0.2, 0);
	}
}
