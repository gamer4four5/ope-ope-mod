package dev.opeope.power;

import dev.opeope.OpeConfig;
import dev.opeope.OpeOpeMod;
import dev.opeope.anatomy.Anatomy;
import dev.opeope.anatomy.AnatomyRegistry;
import dev.opeope.anatomy.CutOp;
import dev.opeope.anatomy.CutVolume;
import dev.opeope.anatomy.DismemberState;
import dev.opeope.anatomy.VolumeBounds;
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
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Amputate estilo Law: cortar a un ser vivo en partes que siguen vivas (articulaciones, multi-plano, formas CSG y
 * cortes recursivos sobre partes ya sueltas). El estado vive en el propio ser ({@link ModAttachments#DISMEMBER}); cada
 * parte separada es una {@link BodyPartEntity}.
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

	private static final Map<UUID, Caps> CAPS = new HashMap<>();
	private static final Map<UUID, LivingEntity> ACTIVE = new HashMap<>();
	private static final Map<UUID, Integer> MISSING = new HashMap<>();
	private static final Map<UUID, Long> LAST_CUT = new HashMap<>();
	public static final Map<Integer, Float> CLIENT_HEIGHT = new ConcurrentHashMap<>();

	public static int cutsDone, reattachDone, transplantsDone, reclaimsDone, planeCutsDone;
	private static final Map<UUID, Integer> TOUCH = new HashMap<>();
	private static final Map<UUID, Integer> SEEK = new HashMap<>();
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
				pr.discard();
			}
		});
		ServerLivingEntityEvents.ALLOW_DAMAGE.register((entity, source, amount) -> {
			Entity a = source.getAttacker();
			if (a instanceof LivingEntity la && a == source.getSource() && !caps(la).mainHand) return false;
			return true;
		});
		ServerLivingEntityEvents.AFTER_DEATH.register((entity, source) -> DEAD_HEARTS.add(entity.getUuid()));
		ServerLivingEntityEvents.AFTER_DEATH.register((entity, source) -> {
			DismemberState st = entity.getAttached(ModAttachments.DISMEMBER);
			if (st == null || st.isEmpty() || !(entity.getWorld() instanceof ServerWorld w)) return;
			Set<UUID> mine = allPartUuids(st);
			for (UUID id : mine) {
				if (w.getEntity(id) instanceof BodyPartEntity part) {
					vanishFx(w, part);
					part.discard();
				}
			}
			for (UUID id : new HashSet<>(st.foreign.values())) {
				if (w.getEntity(id) instanceof BodyPartEntity part) part.unmount();
			}
			clearState(st);
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
			ServerPlayerEntity p = handler.getPlayer();
			DismemberState st = p.getAttached(ModAttachments.DISMEMBER);
			if (st != null && !st.isEmpty()) {
				clearState(st);
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
			SEEK.clear();
			DEAD_HEARTS.clear();
			BodyPartEntity.LIVE = 0;
		});
	}

	private static void clearState(DismemberState st) {
		st.detached.clear();
		st.lockedUntil.clear();
		st.foreign.clear();
		st.sliceVolumes.clear();
		st.splitParts.clear();
		st.plane = null;
		st.slice = null;
		st.impaired.clear();
	}

	private static Set<UUID> allPartUuids(DismemberState st) {
		Set<UUID> mine = new HashSet<>(st.detached.values());
		mine.addAll(st.sliceVolumes.keySet());
		mine.addAll(st.splitParts.keySet());
		if (st.slice != null) mine.add(st.slice);
		return mine;
	}

	// ------------------------------------------------------------------ preguntas

	public static DismemberState state(LivingEntity le) {
		return le.getAttached(ModAttachments.DISMEMBER);
	}

	public static Caps caps(LivingEntity le) {
		Caps c = CAPS.get(le.getUuid());
		return c == null ? Caps.WHOLE : c;
	}

	public static float heightScale(Entity e) {
		if (e.getWorld() != null && e.getWorld().isClient) return CLIENT_HEIGHT.getOrDefault(e.getId(), 1f);
		Caps c = CAPS.get(e.getUuid());
		return c == null ? 1f : c.heightScale;
	}

	public static boolean blind(Entity e) {
		if (e.getWorld() == null || e.getWorld().isClient) return false;
		Caps c = CAPS.get(e.getUuid());
		return c != null && c.blind;
	}

	public static boolean owns(LivingEntity v, BodyPartEntity part) {
		DismemberState st = state(v);
		if (st == null) return false;
		UUID id = part.getUuid();
		return st.detached.containsValue(id) || st.sliceVolumes.containsKey(id)
			|| st.splitParts.containsKey(id) || id.equals(st.slice);
	}

	public static boolean hosts(LivingEntity host, BodyPartEntity part) {
		DismemberState st = state(host);
		return st != null && st.foreign.containsValue(part.getUuid());
	}

	public static Set<String> gone(DismemberState st) {
		if (st == null) return Set.of();
		if (st.foreign.isEmpty()) return st.detached.keySet();
		Set<String> g = new HashSet<>(st.detached.keySet());
		g.removeAll(st.foreign.keySet());
		return g;
	}

	public static Vec3d slotCenter(LivingEntity le, String seg) {
		Anatomy a = anatomy(le);
		Anatomy.Segment s = a == null ? null : a.get(seg);
		if (s == null) return le.getBoundingBox().getCenter();
		Vec3d feet = le.getPos().add(0, -caps(le).drop, 0);
		return feet.add(Anatomy.toWorld(s.center(), le.getBodyYaw(), le.getScale() * (le.isBaby() ? 0.5 : 1)));
	}

	public static Anatomy anatomy(LivingEntity le) {
		return AnatomyRegistry.getOrSynthetic(le);
	}

	/** ¿Se puede cortar por partes o formas? null = sí; si no, por qué. */
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
		return st == null ? 0 : allPartUuids(st).size();
	}

	// ------------------------------------------------------------------ cortar por articulación

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
		if ("back_half".equals(segment) || "front_half".equals(segment) || "waist_half".equals(segment)) {
			Anatomy.Segment core = a.get(a.core);
			if (core != null) {
				float yaw = le.getBodyYaw();
				double scale = Math.max(0.05, le.getScale() * (le.isBaby() ? 0.5 : 1));
				Vec3d feet = le.getPos().add(0, -caps(le).drop, 0);
				Vec3d worldCenter = feet.add(Anatomy.toWorld(core.center(), yaw, scale));
				Vec3d fwd = Anatomy.toWorld(new Vec3d(0, 0, -1), yaw, 16);
				if (fwd.lengthSquared() < 1e-6) fwd = Vec3d.fromPolar(0, yaw);
				fwd = fwd.normalize();
				Vec3d n = "waist_half".equals(segment)
					? new Vec3d(0, 1, 0)
					: ("front_half".equals(segment) ? fwd.multiply(-1) : fwd);
				Vec3d pt = "waist_half".equals(segment)
					? new Vec3d(worldCenter.x, feet.y + (core.box()[1] + (core.box()[4] - core.box()[1]) * 0.38) * scale / 16.0, worldCenter.z)
					: worldCenter;
				if (planeCut(p, le, new CutEngine.Plane(n, pt), false, false, true) != null) {
					LAST_CUT.put(p.getUuid(), now);
					PowerManager.get(p).roomUsedDuringHold = true;
				}
			}
			return;
		}
		List<String> segs = resolve(a, state(le), segment);
		if (segs.isEmpty()) return;
		if (cut(le, segs, p) != null) {
			LAST_CUT.put(p.getUuid(), now);
			PowerManager.get(p).roomUsedDuringHold = true;
		}
	}

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

	// ------------------------------------------------------------------ cortar por planos libres y formas CSG

	public static boolean sweep(ServerPlayerEntity cutter, LivingEntity le, List<CutEngine.Plane> planes, boolean radio) {
		return sweep(cutter, le, planes, radio, false);
	}

	/**
	 * Aplica uno o más planos sobre {@code le}. Con {@link OpeConfig#FREE_PLANE_CUTS}, cada plano parte exactamente por
	 * ese ángulo y posición (sin limitarse al centro del torso ni a 1 plano) y los planos siguientes de un patrón (Cruz,
	 * Rodajas, Dados) vuelven a rebanar también las piezas recién creadas → multi-partición instantánea.
	 */
	public static boolean sweep(ServerPlayerEntity cutter, LivingEntity le, List<CutEngine.Plane> planes, boolean radio, boolean delayed) {
		if (refusal(le) != null || !le.isAlive() || planes.isEmpty()) return false;
		if (OpeConfig.FREE_PLANE_CUTS) {
			List<BodyPartEntity> createdInSweep = new ArrayList<>();
			boolean anyFree = false;
			for (CutEngine.Plane pl : planes) {
				// 1) Si el plano cruza piezas recién nacidas en este barrido (p. ej. Cruz ×4), divídelas también
				List<BodyPartEntity> newlySplit = new ArrayList<>();
				for (BodyPartEntity bp : new ArrayList<>(createdInSweep)) {
					if (bp.isRemoved() || !pl.crosses(bp.getBoundingBox())) continue;
					BodyPartEntity child = splitBodyPart(cutter, bp, pl, radio, delayed);
					if (child != null) newlySplit.add(child);
				}
				createdInSweep.addAll(newlySplit);
				// 2) Corta lo que aún queda en el ser vivo
				BodyPartEntity made = planeCut(cutter, le, pl, radio, delayed, true);
				if (made != null) {
					createdInSweep.add(made);
					anyFree = true;
				}
			}
			if (anyFree || !createdInSweep.isEmpty()) return true;
		}
		// Respaldo clásico por articulaciones si no dividió volumen
		Anatomy a = anatomy(le);
		Anatomy.Segment core = a.get(a.core);
		if (core == null) return false;
		DismemberState st = state(le);
		Set<String> gone = new HashSet<>(st == null ? Set.of() : st.detached.keySet());
		Vec3d feet = le.getPos();
		float yaw = le.getBodyYaw();
		double scale = le.getScale() * (le.isBaby() ? 0.5 : 1);
		Vec3d coreC = feet.add(Anatomy.toWorld(core.center(), yaw, scale));
		Set<String> picked = new LinkedHashSet<>();
		for (CutEngine.Plane pl : planes) {
			double dc = pl.n().dotProduct(coreC.subtract(pl.p()));
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

	public static boolean planeCut(ServerPlayerEntity cutter, LivingEntity le, CutEngine.Plane wp, boolean radio) {
		return planeCut(cutter, le, wp, radio, false, false) != null;
	}

	public static BodyPartEntity planeCut(ServerPlayerEntity cutter, LivingEntity le, CutEngine.Plane wp, boolean radio, boolean delayed) {
		return planeCut(cutter, le, wp, radio, delayed, false);
	}

	private static BodyPartEntity planeCut(ServerPlayerEntity cutter, LivingEntity le, CutEngine.Plane wp, boolean radio, boolean delayed, boolean anyPart) {
		if (!(le.getWorld() instanceof ServerWorld w) || refusal(le) != null || !le.isAlive()) return null;
		Anatomy a = anatomy(le);
		if (a == null || a.get(a.core) == null) return null;
		DismemberState st = state(le);
		if (st != null && st.sliceVolumes.size() >= OpeConfig.MAX_OPS_PER_VOLUME) return null;
		if (partCount(le) >= OpeConfig.MAX_PARTS_PER_ENTITY || BodyPartEntity.LIVE >= OpeConfig.MAX_PARTS_WORLD) return null;

		CutOp.HalfSpace rawH = toModelPlane(le, wp);
		if (rawH == null) return null;
		Set<String> gone = gone(st);
		List<String> attached = attachedSegIds(a, gone);
		if (attached.isEmpty()) return null;

		CutVolume baseVol = st == null ? CutVolume.WHOLE : st.remainingVolume();
		CutOp.HalfSpace posH = rawH;               // lado n·P <= d
		CutOp.HalfSpace negH = rawH.negated();     // lado n·P >= d

		// Anti-corte de "1 píxel": si un plano rápido roza solo el borde de la hitbox (<20% de masa y sin partir ninguna extremidad),
		// se centra hacia el cuerpo para dividir la entidad de verdad a lo largo del plano trazado.
		if (!delayed) {
			CutVolume vPos = baseVol.intersect(posH), vNeg = baseVol.intersect(negH);
			double estPos = VolumeBounds.estimateVolumePx3(a, attached, vPos);
			double estNeg = VolumeBounds.estimateVolumePx3(a, attached, vNeg);
			double totVol = estPos + estNeg;
			double minFrac = totVol > 1e-4 ? Math.min(estPos, estNeg) / totVol : 0.0;
			double maxSegMinFrac = 0.0;
			for (String sid : attached) {
				if (sid.equals(a.core)) continue;
				Anatomy.Segment sg = a.get(sid);
				if (sg == null) continue;
				double fp = VolumeBounds.remainingFraction(sg.box(), vPos);
				double fn = VolumeBounds.remainingFraction(sg.box(), vNeg);
				maxSegMinFrac = Math.max(maxSegMinFrac, Math.min(fp, fn));
			}
			Anatomy.Segment coreSeg = a.get(a.core);
			boolean grazesCore = false;
			if (coreSeg != null) {
				double coreMinFrac = Math.min(
					VolumeBounds.remainingFraction(coreSeg.box(), vPos),
					VolumeBounds.remainingFraction(coreSeg.box(), vNeg));
				grazesCore = Anatomy.classify(coreSeg.box(), rawH.toFloatArray()) == 0
					&& coreMinFrac < 0.20 && minFrac < 0.25;
			}
			if ((minFrac < 0.20 && maxSegMinFrac < 0.28) || grazesCore) {
				Vec3d centerPx = VolumeBounds.estimateCenterPx(a, attached, baseVol);
				float centerD = (float) (rawH.nx() * centerPx.x + rawH.ny() * centerPx.y + rawH.nz() * centerPx.z);
				float shiftedD = centerD + MathHelper.clamp(rawH.d() - centerD, -2.2f, 2.2f);
				rawH = new CutOp.HalfSpace(rawH.nx(), rawH.ny(), rawH.nz(), shiftedD);
				posH = rawH;
				negH = rawH.negated();
			}
		}

		// ¿Qué lado conserva la víctima? Si un lado tiene >25% más núcleo (decapitaciones/extremidades), conserva el torso;
		// en bisecciones reales del cuerpo prioriza visión, luego núcleo y finalmente volumen total.
		boolean keepNeg = chooseVictimSide(a, attached, baseVol, posH, negH);
		CutOp.HalfSpace keepPlane = keepNeg ? negH : posH;
		CutOp.HalfSpace piecePlane = keepPlane.negated();

		return applyVolumeExtraction(cutter, w, le, a, st, attached, baseVol, keepPlane, piecePlane, radio, delayed);
	}

	private static boolean chooseVictimSide(Anatomy a, List<String> attached, CutVolume baseVol, CutOp.HalfSpace hPos, CutOp.HalfSpace hNeg) {
		CutVolume vPos = baseVol.intersect(hPos), vNeg = baseVol.intersect(hNeg);
		Anatomy.Segment core = a.get(a.core);
		double cPos = core != null ? VolumeBounds.remainingFraction(core.box(), vPos) : 0;
		double cNeg = core != null ? VolumeBounds.remainingFraction(core.box(), vNeg) : 0;
		if (Math.abs(cPos - cNeg) > 0.25) return cNeg > cPos;

		double visPos = 0, visNeg = 0;
		for (String id : attached) {
			Anatomy.Segment sg = a.get(id);
			if (sg == null || !sg.has("vision")) continue;
			visPos += sg.volume() * VolumeBounds.remainingFraction(sg.box(), vPos);
			visNeg += sg.volume() * VolumeBounds.remainingFraction(sg.box(), vNeg);
		}
		if (Math.abs(visPos - visNeg) > 0.5) return visNeg > visPos;
		if (Math.abs(cPos - cNeg) > 0.03) return cNeg > cPos;

		double tPos = VolumeBounds.estimateVolumePx3(a, attached, vPos);
		double tNeg = VolumeBounds.estimateVolumePx3(a, attached, vNeg);
		return tNeg > tPos;
	}

	/** Extrae una forma CSG {@code shape} (o un semiespacio) de {@code le}, dejando un hueco real y creando una BodyPartEntity viva. */
	public static BodyPartEntity extractShape(ServerPlayerEntity cutter, LivingEntity le, CutOp shape, boolean radio, boolean delayed) {
		if (!(le.getWorld() instanceof ServerWorld w) || refusal(le) != null || !le.isAlive()) return null;
		Anatomy a = anatomy(le);
		if (a == null || a.get(a.core) == null) return null;
		DismemberState st = state(le);
		if (st != null && st.sliceVolumes.size() >= OpeConfig.MAX_OPS_PER_VOLUME) return null;
		if (partCount(le) >= OpeConfig.MAX_PARTS_PER_ENTITY || BodyPartEntity.LIVE >= OpeConfig.MAX_PARTS_WORLD) return null;

		Set<String> gone = gone(st);
		List<String> attached = attachedSegIds(a, gone);
		if (attached.isEmpty()) return null;
		CutVolume baseVol = st == null ? CutVolume.WHOLE : st.remainingVolume();
		return applyVolumeExtraction(cutter, w, le, a, st, attached, baseVol, null, shape, radio, delayed);
	}

	private static BodyPartEntity applyVolumeExtraction(
		ServerPlayerEntity cutter, ServerWorld w, LivingEntity le, Anatomy a, DismemberState st,
		List<String> attached, CutVolume baseVol, CutOp keepOpOrNull, CutOp pieceOp, boolean radio, boolean delayed
	) {
		CutVolume pieceVol = baseVol.intersect(pieceOp);
		CutVolume afterVol = keepOpOrNull != null ? baseVol.intersect(keepOpOrNull) : baseVol.subtractOp(pieceOp);

		double volPiece = VolumeBounds.estimateVolumePx3(a, attached, pieceVol);
		double volRemain = VolumeBounds.estimateVolumePx3(a, attached, afterVol);
		if (volPiece < OpeConfig.MIN_SLICE_VOLUME_PX3 || volRemain < OpeConfig.MIN_SLICE_VOLUME_PX3) return null;

		List<String> sliceSegs = new ArrayList<>();
		List<String> farSegs = new ArrayList<>();
		Set<String> newImpaired = new LinkedHashSet<>();
		for (String id : attached) {
			Anatomy.Segment sg = a.get(id);
			if (sg == null) continue;
			double pFrac = VolumeBounds.remainingFraction(sg.box(), pieceVol);
			double rFrac = VolumeBounds.remainingFraction(sg.box(), afterVol);
			if (pFrac > 0.01) sliceSegs.add(id);
			if (!id.equals(a.core)) {
				if (rFrac < 0.05 && pFrac > 0.05) farSegs.add(id);
				else if (!sg.functions().isEmpty() && rFrac < 0.45) newImpaired.add(id);
			}
		}
		if (sliceSegs.isEmpty()) return null;

		BodyPartEntity part = BodyPartEntity.createVolumeSlice(w, le, sliceSegs, pieceVol, cutter == null ? null : cutter.getUuid());
		double[] box = part.modelBox();
		if (box == null) return null;

		float yaw = le.getBodyYaw();
		double scale = part.victimScale();
		Vec3d feet = le.getPos().add(0, -caps(le).drop, 0);
		Vec3d mc = new Vec3d((box[0] + box[3]) / 2, (box[1] + box[4]) / 2, (box[2] + box[5]) / 2);
		Vec3d center = feet.add(Anatomy.toWorld(mc, yaw, scale));
		Vec3d spawnPos = new Vec3d(center.x, Math.max(le.getY(), center.y - part.getHeight() / 2), center.z);
		part.setPosition(spawnPos.x, spawnPos.y, spawnPos.z);

		Vec3d outDir;
		Vec3d kickDir;
		if (pieceOp instanceof CutOp.HalfSpace hs) {
			Vec3d out = Anatomy.toWorld(new Vec3d(-hs.nx(), -hs.ny(), -hs.nz()), yaw, 16);
			outDir = out.lengthSquared() > 1e-6 ? out.normalize() : new Vec3d(0, 1, 0);
			kickDir = new Vec3d(outDir.x * 0.26, 0.16 + Math.max(0, outDir.y) * 0.16, outDir.z * 0.26);
		} else {
			Vec3d out = center.subtract(le.getBoundingBox().getCenter());
			outDir = out.lengthSquared() > 1e-4 ? out.normalize() : Vec3d.fromPolar(0, yaw);
			kickDir = new Vec3d(outDir.x * 0.24, 0.18, outDir.z * 0.24);
		}

		if (delayed && cutter != null) {
			part.setFrozen(true, le, spawnPos, kickDir);
			PowerManager.get(cutter).bisturiHeldParts.add(part.getId());
		} else {
			part.kick(kickDir);
			le.addVelocity(-outDir.x * 0.14, 0.08, -outDir.z * 0.14);
			le.velocityModified = true;
		}

		w.spawnEntity(part);
		if (st == null) st = new DismemberState();
		for (String sg : farSegs) st.detached.put(sg, part.getUuid());
		st.sliceVolumes.put(part.getUuid(), pieceVol);
		st.refreshLegacyPlane();
		st.impaired.clear();
		st.impaired.addAll(newImpaired);
		if (cutter != null) st.cutter = cutter.getUuid();
		le.setAttached(ModAttachments.DISMEMBER, st);
		ACTIVE.put(le.getUuid(), le);

		boolean legs = false;
		for (String sg : farSegs) if (a.get(sg).has("locomotion")) legs = true;
		if (legs) {
			le.stopRiding();
			le.removeAllPassengers();
		}
		recompute(le);
		sync(le);
		cutFx(w, center, le);
		if (radio) lock(le, part, OpeConfig.RADIO_LOCK_TICKS);
		planeCutsDone++;
		cutsDone++;
		return part;
	}

	// ------------------------------------------------------------------ corte recursivo sobre BodyPartEntity

	/** Corta una {@link BodyPartEntity} ya separada a lo largo de varios planos. */
	public static boolean splitBodyPart(ServerPlayerEntity cutter, BodyPartEntity bp, List<CutEngine.Plane> planes, boolean radio, boolean delayed) {
		boolean any = false;
		List<BodyPartEntity> pool = new ArrayList<>();
		pool.add(bp);
		for (CutEngine.Plane pl : planes) {
			List<BodyPartEntity> next = new ArrayList<>();
			for (BodyPartEntity cur : pool) {
				if (cur.isRemoved() || !pl.crosses(cur.getBoundingBox())) continue;
				BodyPartEntity child = splitBodyPart(cutter, cur, pl, radio, delayed);
				if (child != null) {
					next.add(child);
					any = true;
				}
			}
			pool.addAll(next);
		}
		return any;
	}

	/** Parte {@code bp} en dos {@link BodyPartEntity} vivas siguiendo exactamente el plano {@code wp}. */
	public static BodyPartEntity splitBodyPart(ServerPlayerEntity cutter, BodyPartEntity bp, CutEngine.Plane wp, boolean radio, boolean delayed) {
		if (!(bp.getWorld() instanceof ServerWorld w) || bp.isRemoved() || bp.mounted()) return null;
		LivingEntity victim = bp.victim();
		if (victim == null || !victim.isAlive()) return null;
		DismemberState st = state(victim);
		if (st == null || partCount(victim) >= OpeConfig.MAX_PARTS_PER_ENTITY || BodyPartEntity.LIVE >= OpeConfig.MAX_PARTS_WORLD) return null;

		CutOp.HalfSpace hp = toBodyPartPlane(bp, wp);
		if (hp == null) return null;
		return splitBodyPartByOp(cutter, w, victim, st, bp, hp, hp.negated(), radio, delayed);
	}

	/** Extrae una forma CSG {@code shape} (en píxeles de modelo de {@code bp}) fuera de una {@link BodyPartEntity}. */
	public static BodyPartEntity extractShapeFromBodyPart(ServerPlayerEntity cutter, BodyPartEntity bp, CutOp shape, boolean radio, boolean delayed) {
		if (!(bp.getWorld() instanceof ServerWorld w) || bp.isRemoved() || bp.mounted()) return null;
		LivingEntity victim = bp.victim();
		if (victim == null || !victim.isAlive()) return null;
		DismemberState st = state(victim);
		if (st == null || partCount(victim) >= OpeConfig.MAX_PARTS_PER_ENTITY || BodyPartEntity.LIVE >= OpeConfig.MAX_PARTS_WORLD) return null;
		return splitBodyPartByOp(cutter, w, victim, st, bp, null, shape, radio, delayed);
	}

	private static BodyPartEntity splitBodyPartByOp(
		ServerPlayerEntity cutter, ServerWorld w, LivingEntity victim, DismemberState st,
		BodyPartEntity bp, CutOp keepOpOrNull, CutOp extractOp, boolean radio, boolean delayed
	) {
		Anatomy a = bp.anatomy();
		if (a == null) return null;
		Matrix4f m2w = bodyPartModelToWorld(bp);
		if (m2w == null) return null;

		CutVolume base = bp.cutVolume();
		if (base.opCount() >= OpeConfig.MAX_OPS_PER_VOLUME) return null;

		CutVolume volA = keepOpOrNull != null ? base.intersect(keepOpOrNull) : base.subtractOp(extractOp);
		CutVolume volB = base.intersect(extractOp);

		double pxA = VolumeBounds.estimateVolumePx3(a, bp.segments(), volA);
		double pxB = VolumeBounds.estimateVolumePx3(a, bp.segments(), volB);
		if (pxA < OpeConfig.MIN_SLICE_VOLUME_PX3 || pxB < OpeConfig.MIN_SLICE_VOLUME_PX3) return null;

		List<String> segsA = filterSegments(a, bp.segments(), volA);
		List<String> segsB = filterSegments(a, bp.segments(), volB);
		if (segsA.isEmpty() || segsB.isEmpty()) return null;

		double[] boxA = VolumeBounds.unionClippedBox(a, segsA, volA);
		double[] boxB = VolumeBounds.unionClippedBox(a, segsB, volB);
		if (boxA == null || boxB == null) return null;

		Vector3f wcA = m2w.transformPosition((float) ((boxA[0] + boxA[3]) * 0.5), (float) ((boxA[1] + boxA[4]) * 0.5), (float) ((boxA[2] + boxA[5]) * 0.5), new Vector3f());
		Vector3f wcB = m2w.transformPosition((float) ((boxB[0] + boxB[3]) * 0.5), (float) ((boxB[1] + boxB[4]) * 0.5), (float) ((boxB[2] + boxB[5]) * 0.5), new Vector3f());

		boolean wasLocked = bp.locked() || isLocked(victim, bp);
		UUID oldId = bp.getUuid();
		boolean inSliceMap = st.sliceVolumes.containsKey(oldId);

		// Crear nueva pieza B
		BodyPartEntity partB = BodyPartEntity.createVolumeSlice(w, victim, segsB, volB, cutter == null ? bp.cutter : cutter.getUuid());
		partB.setYaw(bp.getYaw());
		partB.setPitch(bp.getPitch());

		// Actualizar pieza existente A en su nuevo volumen y caja
		bp.setCutVolume(volA);
		bp.setSegments(segsA);
		bp.stopSpin();
		bp.setPosition(wcA.x, wcA.y - bp.getHeight() * 0.5, wcA.z);
		partB.setPosition(wcB.x, wcB.y - partB.getHeight() * 0.5, wcB.z);

		Vec3d sep = new Vec3d(wcB.x - wcA.x, 0, wcB.z - wcA.z);
		sep = sep.lengthSquared() > 1e-4 ? sep.normalize().multiply(0.14) : new Vec3d(0.12, 0, 0);
		if (delayed && cutter != null) {
			partB.setFrozen(true, victim, partB.getPos(), new Vec3d(sep.x, 0.14, sep.z));
			PowerManager.get(cutter).bisturiHeldParts.add(partB.getId());
		} else {
			bp.kick(new Vec3d(-sep.x * 0.7, 0.12, -sep.z * 0.7));
			partB.kick(new Vec3d(sep.x, 0.16, sep.z));
		}
		w.spawnEntity(partB);

		// Registrar en DismemberState
		if (inSliceMap) {
			st.sliceVolumes.put(oldId, volA);
			st.sliceVolumes.put(partB.getUuid(), volB);
			// Si detached tenía un segmento apuntando a oldId y ya se partió en 2, deja que VolumeSliceConsumer gestione ese segmento o déjalo solo si A/B no lo cubren aparte
			st.detached.values().removeIf(oldId::equals);
			st.refreshLegacyPlane();
		} else {
			// Era una parte cortada por articulación: registrar ambas sub-piezas en splitParts
			st.splitParts.put(oldId, volA);
			st.splitParts.put(partB.getUuid(), volB);
		}
		if (wasLocked || radio) {
			long ticks = radio ? OpeConfig.RADIO_LOCK_TICKS : 20 * 15;
			lock(victim, bp, (int) ticks);
			lock(victim, partB, (int) ticks);
		}
		if (cutter != null) st.cutter = cutter.getUuid();
		victim.setAttached(ModAttachments.DISMEMBER, st);
		recompute(victim);
		sync(victim);
		cutFx(w, new Vec3d((wcA.x + wcB.x) * 0.5, (wcA.y + wcB.y) * 0.5, (wcA.z + wcB.z) * 0.5), victim);
		cutsDone++;
		return partB;
	}

	private static List<String> filterSegments(Anatomy a, List<String> src, CutVolume vol) {
		List<String> out = new ArrayList<>();
		for (String id : src) {
			Anatomy.Segment sg = a.get(id);
			if (sg != null && VolumeBounds.remainingFraction(sg.box(), vol) > 0.01) out.add(id);
		}
		return out.isEmpty() ? List.copyOf(src) : out;
	}

	// ------------------------------------------------------------------ petición de forma C2S (Bisturí creativo + Lazo Libre)

	/** Maneja {@link Payloads.CutShape} desde el cliente (Wedge, Box, Cylinder, Sphere, Slices×3, Cross×4, Freehand Prism). */
	public static void handleShapeCut(ServerPlayerEntity p, Payloads.CutShape payload) {
		if (!PowerManager.canUse(p)) return;
		PlayerPower pw = PowerManager.get(p);
		Room room = pw.room;
		if (room == null || room.dim != p.getWorld().getRegistryKey()) {
			PowerManager.noRoom(p);
			return;
		}
		ServerWorld w = p.getServerWorld();
		float[] pr = payload.params();
		if (pr == null || pr.length < 3) return;
		boolean delayed = payload.delayed() && pw.bisturi;
		Entity target = payload.targetId() >= 0 ? w.getEntityById(payload.targetId()) : null;
		if (target == null) {
			Vec3d at = new Vec3d(pr[0], pr[1], pr[2]);
			if (!PowerManager.inRoom(p, at) || p.getEyePos().distanceTo(at) > OpeConfig.CUT_RANGE) return;
			double rad = Math.max(1.5, (pr.length > 12 ? pr[12] : (pr.length == 4 ? pr[3] : 1.0)) + 1.0);
			Box pickBox = new Box(at, at).expand(rad);
			List<Entity> candidates = w.getOtherEntities(p, pickBox,
				e -> e.isAlive() && PowerManager.inRoom(p, e.getBoundingBox().getCenter())
					&& ((e instanceof LivingEntity le && refusal(le) == null) || (e instanceof BodyPartEntity bp && !bp.mounted() && !bp.frozen())));
			if (candidates.isEmpty()) return;
			target = candidates.get(0);
		}
		if (!target.isAlive() || target == p) return;
		Vec3d center = target.getBoundingBox().getCenter();
		if (!PowerManager.inRoom(p, center) || target.squaredDistanceTo(p) > OpeConfig.CUT_RANGE * OpeConfig.CUT_RANGE) return;

		if (target instanceof BodyPartEntity bp) {
			applyShapeToBodyPart(p, bp, payload.mode(), delayed, payload.thru(), pr);
		} else if (target instanceof LivingEntity le) {
			if (refusal(le) != null) return;
			applyShapeToLiving(p, le, payload.mode(), delayed, payload.thru(), pr);
		}
		pw.roomUsedDuringHold = true;
	}

	private static void applyShapeToLiving(ServerPlayerEntity p, LivingEntity le, int mode, boolean delayed, boolean thru, float[] pr) {
		switch (mode) {
			case Payloads.CutShape.MODE_WEDGE -> {
				// pr: cx,cy,cz, rx,ry,rz, ux,uy,uz, fx,fy,fz, rad
				if (pr.length < 13) return;
				CutOp wedge = buildWedgeLiving(le, pr);
				if (wedge != null) extractShape(p, le, wedge, false, delayed);
			}
			case Payloads.CutShape.MODE_BOX -> {
				if (pr.length < 13) return;
				CutOp box = buildBoxLiving(le, pr, thru);
				if (box != null) extractShape(p, le, box, false, delayed);
			}
			case Payloads.CutShape.MODE_CYLINDER -> {
				// pr: cx,cy,cz, ax,ay,az, rad
				if (pr.length < 7) return;
				CutOp cyl = buildCylinderLiving(le, pr, thru);
				if (cyl != null) extractShape(p, le, cyl, false, delayed);
			}
			case Payloads.CutShape.MODE_SPHERE -> {
				// pr: cx,cy,cz, rad
				if (pr.length < 4) return;
				float scale = Math.max(0.05f, le.getScale() * (le.isBaby() ? 0.5f : 1f));
				Vec3d cPx = toModelPoint(le, new Vec3d(pr[0], pr[1], pr[2]));
				float rPx = Math.max(1.5f, (float) (pr[3] * 16.0 / scale));
				extractShape(p, le, new CutOp.SphereVolume((float) cPx.x, (float) cPx.y, (float) cPx.z, rPx, false), false, delayed);
			}
			case Payloads.CutShape.MODE_SLICES_3 -> {
				// pr: cx,cy,cz, nx,ny,nz, spacing
				if (pr.length < 7) return;
				Vec3d n = new Vec3d(pr[3], pr[4], pr[5]);
				if (n.lengthSquared() < 1e-6) return;
				n = n.normalize();
				Vec3d c = new Vec3d(pr[0], pr[1], pr[2]);
				double sp = Math.max(0.18, pr[6]);
				List<CutEngine.Plane> pls = List.of(
					new CutEngine.Plane(n, c.subtract(n.multiply(sp))),
					new CutEngine.Plane(n, c),
					new CutEngine.Plane(n, c.add(n.multiply(sp)))
				);
				sweep(p, le, pls, false, delayed);
			}
			case Payloads.CutShape.MODE_CROSS_4 -> {
				// pr: cx,cy,cz, n1x,n1y,n1z, n2x,n2y,n2z
				if (pr.length < 9) return;
				Vec3d n1 = new Vec3d(pr[3], pr[4], pr[5]);
				Vec3d n2 = new Vec3d(pr[6], pr[7], pr[8]);
				if (n1.lengthSquared() < 1e-6 || n2.lengthSquared() < 1e-6) return;
				Vec3d c = new Vec3d(pr[0], pr[1], pr[2]);
				sweep(p, le, List.of(new CutEngine.Plane(n1.normalize(), c), new CutEngine.Plane(n2.normalize(), c)), false, delayed);
			}
			case Payloads.CutShape.MODE_FREEHAND -> {
				// pr: ox,oy,oz, rx,ry,rz, ux,uy,uz, ax,ay,az, nPts, x0,y0, x1,y1...
				CutOp prism = buildPrismLiving(le, pr);
				if (prism != null) extractShape(p, le, prism, false, delayed);
			}
			default -> {
				if (pr.length >= 6) {
					planeCut(p, le, new CutEngine.Plane(new Vec3d(pr[3], pr[4], pr[5]).normalize(), new Vec3d(pr[0], pr[1], pr[2])), false, delayed);
				}
			}
		}
	}

	private static void applyShapeToBodyPart(ServerPlayerEntity p, BodyPartEntity bp, int mode, boolean delayed, boolean thru, float[] pr) {
		Matrix4f m2w = bodyPartModelToWorld(bp);
		if (m2w == null) return;
		Matrix4f inv = new Matrix4f(m2w).invert();
		float scale = Math.max(0.05f, bp.victimScale());
		switch (mode) {
			case Payloads.CutShape.MODE_BOX -> {
				if (pr.length < 13) return;
				CutOp box = buildBoxMatrix(inv, scale, pr, thru);
				if (box != null) extractShapeFromBodyPart(p, bp, box, false, delayed);
			}
			case Payloads.CutShape.MODE_CYLINDER -> {
				if (pr.length < 7) return;
				CutOp cyl = buildCylinderMatrix(inv, scale, pr, thru);
				if (cyl != null) extractShapeFromBodyPart(p, bp, cyl, false, delayed);
			}
			case Payloads.CutShape.MODE_SPHERE -> {
				if (pr.length < 4) return;
				Vector3f c = inv.transformPosition(pr[0], pr[1], pr[2], new Vector3f());
				float rPx = Math.max(1.5f, pr[3] * 16f / scale);
				extractShapeFromBodyPart(p, bp, new CutOp.SphereVolume(c.x, c.y, c.z, rPx, false), false, delayed);
			}
			case Payloads.CutShape.MODE_WEDGE -> {
				if (pr.length < 13) return;
				CutOp wedge = buildWedgeMatrix(inv, scale, pr);
				if (wedge != null) extractShapeFromBodyPart(p, bp, wedge, false, delayed);
			}
			case Payloads.CutShape.MODE_FREEHAND -> {
				CutOp prism = buildPrismMatrix(inv, scale, pr);
				if (prism != null) extractShapeFromBodyPart(p, bp, prism, false, delayed);
			}
			case Payloads.CutShape.MODE_SLICES_3 -> {
				if (pr.length < 7) return;
				Vec3d n = new Vec3d(pr[3], pr[4], pr[5]);
				if (n.lengthSquared() < 1e-6) return;
				n = n.normalize();
				Vec3d c = new Vec3d(pr[0], pr[1], pr[2]);
				double sp = Math.max(0.18, pr[6]);
				splitBodyPart(p, bp, List.of(
					new CutEngine.Plane(n, c.subtract(n.multiply(sp))),
					new CutEngine.Plane(n, c),
					new CutEngine.Plane(n, c.add(n.multiply(sp)))
				), false, delayed);
			}
			case Payloads.CutShape.MODE_CROSS_4 -> {
				if (pr.length < 9) return;
				Vec3d n1 = new Vec3d(pr[3], pr[4], pr[5]), n2 = new Vec3d(pr[6], pr[7], pr[8]);
				if (n1.lengthSquared() < 1e-6 || n2.lengthSquared() < 1e-6) return;
				Vec3d c = new Vec3d(pr[0], pr[1], pr[2]);
				splitBodyPart(p, bp, List.of(new CutEngine.Plane(n1.normalize(), c), new CutEngine.Plane(n2.normalize(), c)), false, delayed);
			}
			default -> {}
		}
	}

	// ------------------------------------------------------------------ bloqueo Radio Knife

	public static void lock(LivingEntity le, BodyPartEntity part, int ticks) {
		DismemberState st = state(le);
		if (st == null) return;
		long until = le.getWorld().getTime() + ticks;
		for (Map.Entry<String, UUID> e : st.detached.entrySet()) if (e.getValue().equals(part.getUuid())) st.lockedUntil.put(e.getKey(), until);
		if (st.sliceVolumes.containsKey(part.getUuid()) || st.splitParts.containsKey(part.getUuid())) {
			st.lockedUntil.put("#vol:" + part.getUuid(), until);
		}
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
		Long vu = st.lockedUntil.get("#vol:" + part.getUuid());
		if (vu != null && vu > now) return true;
		for (Map.Entry<String, UUID> e : st.detached.entrySet()) {
			if (!e.getValue().equals(part.getUuid())) continue;
			Long until = st.lockedUntil.get(e.getKey());
			if (until != null && until > now) return true;
		}
		return false;
	}

	// ------------------------------------------------------------------ reunir

	public static boolean reattach(LivingEntity le, BodyPartEntity part, boolean force) {
		DismemberState st = state(le);
		if (st == null) {
			part.discard();
			return false;
		}
		UUID id = part.getUuid();
		if (!force && isLocked(le, part)) return false;
		Vec3d at = part.getBoundingBox().getCenter();
		if (part.mounted()) part.unmount();

		if (!st.foreign.isEmpty() && le.getWorld() instanceof ServerWorld sw0) {
			for (String seg : part.segments()) {
				UUID f = st.foreign.remove(seg);
				if (f != null && sw0.getEntity(f) instanceof BodyPartEntity fp) fp.unmountQuiet();
			}
		}

		boolean wasSlice = st.sliceVolumes.remove(id) != null;
		boolean wasSplitPart = st.splitParts.remove(id) != null;

		if (wasSplitPart && le.getWorld() instanceof ServerWorld sw) {
			Anatomy a = anatomy(le);
			// Si quedan sub-piezas de los mismos segmentos cortados por articulación, pasar las restantes a sliceVolumes acotadas a su segmento
			List<BodyPartEntity> siblings = findSiblings(sw, st, part.segments(), id);
			if (siblings.isEmpty()) {
				for (String sg : part.segments()) st.detached.remove(sg);
			} else if (a != null) {
				CutOp.BoxVolume bound = segmentBoundBox(a, part.segments());
				for (BodyPartEntity sib : siblings) {
					CutVolume sv = st.splitParts.remove(sib.getUuid());
					if (sv == null) sv = sib.cutVolume();
					CutVolume bounded = bound != null ? sv.intersect(bound) : sv;
					st.sliceVolumes.put(sib.getUuid(), bounded);
				}
				for (String sg : part.segments()) st.detached.remove(sg);
			}
		} else {
			st.detached.values().removeIf(id::equals);
		}

		if (wasSlice) {
			st.refreshLegacyPlane();
			if (st.sliceVolumes.isEmpty()) st.impaired.clear();
		} else if (id.equals(st.slice)) {
			st.slice = null;
			st.plane = null;
			st.impaired.clear();
			st.lockedUntil.remove("#slice");
		}
		st.lockedUntil.remove("#vol:" + id);
		st.lockedUntil.keySet().removeIf(k -> !k.equals("#slice") && !k.startsWith("#vol:") && !st.detached.containsKey(k));
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

	private static List<BodyPartEntity> findSiblings(ServerWorld w, DismemberState st, List<String> segs, UUID skipId) {
		List<BodyPartEntity> out = new ArrayList<>();
		for (UUID sid : new ArrayList<>(st.splitParts.keySet())) {
			if (sid.equals(skipId)) continue;
			if (w.getEntity(sid) instanceof BodyPartEntity bp && !bp.isRemoved()) {
				for (String s : bp.segments()) {
					if (segs.contains(s)) {
						out.add(bp);
						break;
					}
				}
			}
		}
		return out;
	}

	private static CutOp.BoxVolume segmentBoundBox(Anatomy a, List<String> segs) {
		double[] b = a.union(segs);
		if (b == null) return null;
		float cx = (float) ((b[0] + b[3]) * 0.5);
		float cy = (float) ((b[1] + b[4]) * 0.5);
		float cz = (float) ((b[2] + b[5]) * 0.5);
		float hx = (float) ((b[3] - b[0]) * 0.5 + 0.05);
		float hy = (float) ((b[4] - b[1]) * 0.5 + 0.05);
		float hz = (float) ((b[5] - b[2]) * 0.5 + 0.05);
		return new CutOp.BoxVolume(cx, cy, cz, hx, hy, hz, 1, 0, 0, 0, 1, 0, 0, 0, 1, false);
	}

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

	public static void releaseForeign(LivingEntity host, BodyPartEntity part) {
		DismemberState st = state(host);
		if (st == null) return;
		if (st.foreign.values().removeIf(part.getUuid()::equals)) after(host, st);
	}

	public static void reattachAll(LivingEntity le, boolean force) {
		DismemberState st = state(le);
		if (st == null || !(le.getWorld() instanceof ServerWorld w)) return;
		Set<UUID> mine = allPartUuids(st);
		for (UUID id : mine) {
			if (w.getEntity(id) instanceof BodyPartEntity part) reattach(le, part, force);
			else if (force) {
				st.detached.values().removeIf(id::equals);
				st.sliceVolumes.remove(id);
				st.splitParts.remove(id);
				if (id.equals(st.slice)) {
					st.slice = null;
					st.plane = null;
				}
			}
		}
		st.refreshLegacyPlane();
		if (st.sliceVolumes.isEmpty()) st.impaired.clear();
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
		CutVolume rem = st.remainingVolume();
		Set<String> gone = new HashSet<>(gone(st));
		gone.addAll(st.impaired);
		if (!rem.isWhole()) {
			for (Anatomy.Segment s : a.segments.values()) {
				if (s.id().equals(a.core)) continue;
				if (!st.foreign.containsKey(s.id()) && VolumeBounds.remainingFraction(s.box(), rem) < 0.45) {
					gone.add(s.id());
				}
			}
		}
		Anatomy.Segment coreSeg = a.get(a.core);
		Vec3d coreC = coreSeg != null ? coreSeg.center() : Vec3d.ZERO;
		int legs = a.legs, missing = 0, keptLegs = 0;
		int leftKept = 0, rightKept = 0, frontKept = 0, hindKept = 0;
		boolean main = true, off = true, vision = false, visionLeft = false, arms = false;
		for (Anatomy.Segment s : a.segments.values()) {
			boolean on = a.attached(s.id(), gone);
			if (s.has("locomotion")) {
				if (!on) {
					missing++;
				} else {
					keptLegs++;
					if (s.id().contains("left") || s.center().x > coreC.x + 0.5) leftKept++;
					else if (s.id().contains("right") || s.center().x < coreC.x - 0.5) rightKept++;
					if (s.id().contains("front") || s.center().z < coreC.z - 0.5) frontKept++;
					else if (s.id().contains("hind") || s.center().z > coreC.z + 0.5) hindKept++;
				}
			}
			if (s.has("main_hand") && !on) main = false;
			if (s.has("off_hand") && !on) off = false;
			if (s.has("vision")) {
				vision = true;
				if (on) visionLeft = true;
			}
			if ((s.has("main_hand") || s.has("off_hand")) && on) arms = true;
		}
		double speed = 1, jump = 1;
		float legH = (float) (a.legHeight / 16.0 * le.getScale());
		float drop = 0, hs = 1;
		if (legs > 0 && missing > 0) {
			if (missing >= legs) {
				speed = 0;
				jump = 0;
				drop = legH;
				hs = (float) Math.max(0.2, (a.height - a.legHeight) / a.height);
			} else if (legs == 2) {
				speed = missing == 1 ? 0.35 : (arms ? 0.03 : 0);
				jump = missing == 1 ? 0.5 : 0;
				if (missing >= 2) {
					drop = legH;
					hs = (float) Math.max(0.2, (a.height - a.legHeight) / a.height);
				} else {
					drop = 0.12f * legH;
					hs = (float) Math.max(0.70, (a.height - 0.12 * a.legHeight) / a.height);
				}
			} else if (legs >= 4) {
				if (keptLegs == 1 || (leftKept == 0 && rightKept > 0) || (rightKept == 0 && leftKept > 0)) {
					// Caído de costado: sin apoyo en un lado o con 1 sola pata
					speed = 0.22;
					jump = 0;
					drop = 0.58f * legH;
					hs = (float) Math.max(0.35, (a.height - 0.58 * a.legHeight) / a.height);
				} else if (frontKept == 0 || hindKept == 0) {
					// Partido por la mitad: solo patas delanteras o solo traseras
					speed = 0.42;
					jump = 0.35;
					drop = 0.28f * legH;
					hs = (float) Math.max(0.45, (a.height - 0.28 * a.legHeight) / a.height);
				} else {
					// Cojea con 3 patas o 2 diagonales
					speed = 0.62;
					jump = 0.55;
					drop = 0.12f * legH;
					hs = (float) Math.max(0.65, (a.height - 0.12 * a.legHeight) / a.height);
				}
			} else {
				speed = Math.max(0, 1 - 0.22 * missing * (4.0 / legs));
				jump = Math.max(0, 1 - 0.25 * missing * (4.0 / legs));
			}
		} else if (!rem.isWhole() && a.isSynthetic()) {
			// Para mobs sin anatomía JSON: pérdida de >40% del cuerpo ralentiza proporcionalmente
			double frac = VolumeBounds.remainingFraction(a.get(a.core).box(), rem);
			if (frac < 0.6) speed = Math.max(0.25, frac);
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
		List<LivingEntity> resync = new ArrayList<>();
		for (Map.Entry<UUID, LivingEntity> entry : new ArrayList<>(ACTIVE.entrySet())) {
			UUID key = entry.getKey();
			LivingEntity le = entry.getValue();
			if (le.isRemoved() || !(le.getWorld() instanceof ServerWorld w)) {
				ACTIVE.remove(key);
				CAPS.remove(le.getUuid());
				continue;
			}
			DismemberState st = state(le);
			if (st == null || st.isEmpty()) {
				ACTIVE.remove(key);
				continue;
			}
			boolean changed = false;
			Set<UUID> mine = allPartUuids(st);
			for (UUID id : mine) {
				if (w.getEntity(id) instanceof BodyPartEntity) {
					MISSING.remove(id);
					continue;
				}
				int n = MISSING.merge(id, 1, Integer::sum);
				if (n > OpeConfig.PART_ORPHAN_TICKS) {
					MISSING.remove(id);
					st.detached.values().removeIf(id::equals);
					st.sliceVolumes.remove(id);
					st.splitParts.remove(id);
					if (id.equals(st.slice)) {
						st.slice = null;
						st.plane = null;
						st.impaired.clear();
					}
					st.refreshLegacyPlane();
					changed = true;
				}
			}
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
				st.lockedUntil.keySet().removeIf(k -> !k.equals("#slice") && !k.startsWith("#vol:") && !st.detached.containsKey(k));
				resync.add(le);
			}
			Caps c = caps(le);
			if (le instanceof MobEntity mob && (!st.detached.isEmpty() || !st.sliceVolumes.isEmpty() || !st.splitParts.isEmpty())) {
				reclaim(w, mob, st, c);
				if (state(le) == null) continue;
				c = caps(le);
			}
			if (OpeConfig.HEAD_CONTROLS_AI && le instanceof MobEntity mob && le.age % 10 == 0) headControl(w, mob, st);
			if (c.blind && le instanceof MobEntity mob) {
				mob.setTarget(null);
				if (le.age % 15 == 0) mob.setHeadYaw(mob.getHeadYaw() + (w.random.nextFloat() - 0.5f) * 120f);
			}
			// Cojera física al caminar sin todas las patas
			if (le instanceof MobEntity mob && !mob.isAiDisabled() && c.legs > 0 && c.legsMissing > 0 && c.legsMissing < c.legs
				&& mob.isOnGround() && mob.getVelocity().horizontalLengthSquared() > 4e-4 && le.age % 11 == 0) {
				Vec3d mv = mob.getVelocity();
				double hop = (c.legs - c.legsMissing <= 1) ? 0.14 : 0.09;
				mob.setVelocity(mv.x * 0.85, Math.max(mv.y, hop), mv.z * 0.85);
				mob.velocityModified = true;
			}
		}
		for (LivingEntity le : resync) {
			DismemberState st = state(le);
			if (st != null) after(le, st);
		}
	}

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
			if (!(w.getEntity(e.getValue()) instanceof BodyPartEntity part) || part.mounted() || part.frozen() || part.pinned() || Takt.held(part.getId())) continue;
			double d = part.squaredDistanceTo(mob);
			if (d < bd) {
				bd = d;
				best = part;
			}
		}
		for (Set<UUID> volKeys : List.of(st.sliceVolumes.keySet(), st.splitParts.keySet())) {
			for (UUID id : volKeys) {
				Long until = st.lockedUntil.get("#vol:" + id);
				if (until != null && until > now) continue;
				if (!(w.getEntity(id) instanceof BodyPartEntity part) || part.mounted() || part.frozen() || part.pinned() || Takt.held(part.getId())) continue;
				double d = part.squaredDistanceTo(mob);
				if (d < bd) {
					bd = d;
					best = part;
				}
			}
		}
		UUID id = mob.getUuid();
		if (best == null) {
			TOUCH.remove(id);
			SEEK.remove(id);
			return;
		}
		boolean distracted = mob.getTarget() != null;
		if (distracted) mob.setTarget(null);
		boolean touching = mob.getBoundingBox().expand(0.6).intersects(best.getBoundingBox());
		if (touching) {
			if (SEEK.getOrDefault(id, 0) > OpeConfig.RECLAIM_PULL_TICKS) best.pullTo(mob.getBoundingBox().getCenter());
			int t = TOUCH.merge(id, 1, Integer::sum);
			mob.getNavigation().stop();
			mob.getLookControl().lookAt(best, 30f, 30f);
			if (t % 8 == 0) {
				Vec3d pc = best.getBoundingBox().getCenter();
				w.spawnParticles(CutEngine.ROOM_DUST, pc.x, pc.y, pc.z, 3, 0.12, 0.12, 0.12, 0);
			}
			if (t >= OpeConfig.RECLAIM_TOUCH_TICKS) {
				TOUCH.remove(id);
				SEEK.remove(id);
				if (reattach(mob, best, false)) reclaimsDone++;
			}
		} else {
			TOUCH.remove(id);
			if (SEEK.merge(id, 1, Integer::sum) > OpeConfig.RECLAIM_PULL_TICKS) best.pullTo(mob.getBoundingBox().getCenter());
			if (bd < 3 * 3) {
				mob.getNavigation().stop();
				mob.getMoveControl().moveTo(best.getX(), best.getY(), best.getZ(), 1.1);
				mob.getLookControl().lookAt(best, 30f, 30f);
			} else if (distracted || mob.age % 5 == 0 || mob.getNavigation().isIdle()) {
				mob.getNavigation().startMovingTo(best.getX(), best.getY(), best.getZ(), 1.1);
			}
		}
	}

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

	// ------------------------------------------------------------------ geometría helper

	private static List<String> attachedSegIds(Anatomy a, Collection<String> gone) {
		List<String> out = new ArrayList<>();
		for (Anatomy.Segment sg : a.segments.values()) {
			if (a.attached(sg.id(), gone)) out.add(sg.id());
		}
		return out;
	}

	private static CutOp.HalfSpace toModelPlane(LivingEntity le, CutEngine.Plane wp) {
		float yaw = le.getBodyYaw();
		double scale = Math.max(0.05, le.getScale() * (le.isBaby() ? 0.5 : 1));
		Vec3d feet = le.getPos().add(0, -caps(le).drop, 0);
		Vec3d nj = Anatomy.toModel(wp.n(), yaw, scale);
		if (nj.lengthSquared() < 1e-9) return null;
		nj = nj.normalize();
		Vec3d pj = Anatomy.toModel(wp.p().subtract(feet), yaw, scale);
		return new CutOp.HalfSpace((float) nj.x, (float) nj.y, (float) nj.z, (float) nj.dotProduct(pj));
	}

	private static Vec3d toModelPoint(LivingEntity le, Vec3d wp) {
		float yaw = le.getBodyYaw();
		double scale = Math.max(0.05, le.getScale() * (le.isBaby() ? 0.5 : 1));
		Vec3d feet = le.getPos().add(0, -caps(le).drop, 0);
		return Anatomy.toModel(wp.subtract(feet), yaw, scale);
	}

	private static Vec3d toModelDir(LivingEntity le, Vec3d wn) {
		float yaw = le.getBodyYaw();
		double scale = Math.max(0.05, le.getScale() * (le.isBaby() ? 0.5 : 1));
		Vec3d d = Anatomy.toModel(wn, yaw, scale);
		return d.lengthSquared() < 1e-9 ? new Vec3d(1, 0, 0) : d.normalize();
	}

	private static Matrix4f bodyPartModelToWorld(BodyPartEntity bp) {
		double[] box = bp.modelBox();
		if (box == null) return null;
		double cx = (box[0] + box[3]) / 2.0, cy = (box[1] + box[4]) / 2.0, cz = (box[2] + box[5]) / 2.0;
		float s = Math.max(0.05f, bp.victimScale());
		return new Matrix4f()
			.translate((float) bp.getX(), (float) (bp.getY() + bp.getHeight() * 0.5), (float) bp.getZ())
			.rotateY((float) Math.toRadians(180f - bp.getYaw()))
			.rotateX((float) Math.toRadians(bp.getPitch()))
			.rotateZ((float) Math.toRadians(bp.roll(1f)))
			.scale(s, s, s)
			.translate((float) (cx / 16.0), (float) (-cy / 16.0), (float) (-cz / 16.0))
			.scale(-1f / 16f, 1f / 16f, 1f / 16f);
	}

	private static CutOp.HalfSpace toBodyPartPlane(BodyPartEntity bp, CutEngine.Plane wp) {
		Matrix4f m2w = bodyPartModelToWorld(bp);
		if (m2w == null) return null;
		Matrix4f inv = new Matrix4f(m2w).invert();
		Vector3f p0 = inv.transformPosition((float) wp.p().x, (float) wp.p().y, (float) wp.p().z, new Vector3f());
		Vector3f p1 = inv.transformPosition((float) (wp.p().x + wp.n().x), (float) (wp.p().y + wp.n().y), (float) (wp.p().z + wp.n().z), new Vector3f());
		Vector3f n = p1.sub(p0);
		if (n.lengthSquared() < 1e-9f) return null;
		n.normalize();
		return new CutOp.HalfSpace(n.x, n.y, n.z, n.dot(p0));
	}

	private static CutOp buildBoxLiving(LivingEntity le, float[] pr, boolean thru) {
		float scale = (float) Math.max(0.05, le.getScale() * (le.isBaby() ? 0.5 : 1));
		Vec3d c = toModelPoint(le, new Vec3d(pr[0], pr[1], pr[2]));
		Vec3d r = toModelDir(le, new Vec3d(pr[3], pr[4], pr[5]));
		Vec3d u = toModelDir(le, new Vec3d(pr[6], pr[7], pr[8]));
		Vec3d f = toModelDir(le, new Vec3d(pr[9], pr[10], pr[11]));
		float h = Math.max(1.5f, pr[12] * 16f / scale);
		return new CutOp.BoxVolume((float) c.x, (float) c.y, (float) c.z, h, h, thru ? 48f : h,
			(float) r.x, (float) r.y, (float) r.z, (float) u.x, (float) u.y, (float) u.z, (float) f.x, (float) f.y, (float) f.z, false);
	}

	private static CutOp buildCylinderLiving(LivingEntity le, float[] pr, boolean thru) {
		float scale = (float) Math.max(0.05, le.getScale() * (le.isBaby() ? 0.5 : 1));
		Vec3d c = toModelPoint(le, new Vec3d(pr[0], pr[1], pr[2]));
		Vec3d ax = toModelDir(le, new Vec3d(pr[3], pr[4], pr[5]));
		float rad = Math.max(1.2f, pr[6] * 16f / scale);
		return new CutOp.CylinderVolume((float) c.x, (float) c.y, (float) c.z, (float) ax.x, (float) ax.y, (float) ax.z, rad, thru ? 48f : rad * 1.3f, false);
	}

	private static CutOp buildWedgeLiving(LivingEntity le, float[] pr) {
		float scale = (float) Math.max(0.05, le.getScale() * (le.isBaby() ? 0.5 : 1));
		Vec3d c = toModelPoint(le, new Vec3d(pr[0], pr[1], pr[2]));
		Vec3d r = toModelDir(le, new Vec3d(pr[3], pr[4], pr[5]));
		Vec3d u = toModelDir(le, new Vec3d(pr[6], pr[7], pr[8]));
		Vec3d f = toModelDir(le, new Vec3d(pr[9], pr[10], pr[11]));
		float rad = Math.max(2f, pr[12] * 16f / scale);
		// Cuña en V apuntando hacia r = 0, abriendo hacia +r
		float[] px = {0f, rad * 2.4f, rad * 2.4f};
		float[] py = {0f, -rad * 1.4f, rad * 1.4f};
		return new CutOp.PrismVolume((float) c.x, (float) c.y, (float) c.z,
			(float) r.x, (float) r.y, (float) r.z,
			(float) u.x, (float) u.y, (float) u.z,
			(float) f.x, (float) f.y, (float) f.z,
			48f, px, py, false);
	}

	private static CutOp buildPrismLiving(LivingEntity le, float[] pr) {
		if (pr.length < 13) return null;
		int n = Math.min(24, (int) pr[12]);
		if (n < 3 || pr.length < 13 + n * 2) return null;
		float scale = (float) Math.max(0.05, le.getScale() * (le.isBaby() ? 0.5 : 1));
		float pxPerBlock = 16f / scale;
		Vec3d o = toModelPoint(le, new Vec3d(pr[0], pr[1], pr[2]));
		Vec3d u = toModelDir(le, new Vec3d(pr[3], pr[4], pr[5]));
		Vec3d v = toModelDir(le, new Vec3d(pr[6], pr[7], pr[8]));
		Vec3d a = toModelDir(le, new Vec3d(pr[9], pr[10], pr[11]));
		float[] px = new float[n], py = new float[n];
		for (int i = 0; i < n; i++) {
			px[i] = pr[13 + i * 2] * pxPerBlock;
			py[i] = pr[13 + i * 2 + 1] * pxPerBlock;
		}
		return new CutOp.PrismVolume((float) o.x, (float) o.y, (float) o.z,
			(float) u.x, (float) u.y, (float) u.z, (float) v.x, (float) v.y, (float) v.z, (float) a.x, (float) a.y, (float) a.z,
			48f, px, py, false);
	}

	private static Vector3f txDir(Matrix4f inv, Vector3f o, float wx, float wy, float wz, float dx, float dy, float dz) {
		Vector3f p = inv.transformPosition(wx + dx, wy + dy, wz + dz, new Vector3f()).sub(o);
		return p.lengthSquared() < 1e-8f ? new Vector3f(1, 0, 0) : p.normalize();
	}

	private static CutOp buildBoxMatrix(Matrix4f inv, float scale, float[] pr, boolean thru) {
		Vector3f c = inv.transformPosition(pr[0], pr[1], pr[2], new Vector3f());
		Vector3f r = txDir(inv, c, pr[0], pr[1], pr[2], pr[3], pr[4], pr[5]);
		Vector3f u = txDir(inv, c, pr[0], pr[1], pr[2], pr[6], pr[7], pr[8]);
		Vector3f f = txDir(inv, c, pr[0], pr[1], pr[2], pr[9], pr[10], pr[11]);
		float h = Math.max(1.5f, pr[12] * 16f / scale);
		return new CutOp.BoxVolume(c.x, c.y, c.z, h, h, thru ? 48f : h, r.x, r.y, r.z, u.x, u.y, u.z, f.x, f.y, f.z, false);
	}

	private static CutOp buildCylinderMatrix(Matrix4f inv, float scale, float[] pr, boolean thru) {
		Vector3f c = inv.transformPosition(pr[0], pr[1], pr[2], new Vector3f());
		Vector3f a = txDir(inv, c, pr[0], pr[1], pr[2], pr[3], pr[4], pr[5]);
		float rad = Math.max(1.2f, pr[6] * 16f / scale);
		return new CutOp.CylinderVolume(c.x, c.y, c.z, a.x, a.y, a.z, rad, thru ? 48f : rad * 1.3f, false);
	}

	private static CutOp buildWedgeMatrix(Matrix4f inv, float scale, float[] pr) {
		Vector3f c = inv.transformPosition(pr[0], pr[1], pr[2], new Vector3f());
		Vector3f r = txDir(inv, c, pr[0], pr[1], pr[2], pr[3], pr[4], pr[5]);
		Vector3f u = txDir(inv, c, pr[0], pr[1], pr[2], pr[6], pr[7], pr[8]);
		Vector3f f = txDir(inv, c, pr[0], pr[1], pr[2], pr[9], pr[10], pr[11]);
		float rad = Math.max(2f, pr[12] * 16f / scale);
		float[] px = {0f, rad * 2.4f, rad * 2.4f};
		float[] py = {0f, -rad * 1.4f, rad * 1.4f};
		return new CutOp.PrismVolume(c.x, c.y, c.z, r.x, r.y, r.z, u.x, u.y, u.z, f.x, f.y, f.z, 48f, px, py, false);
	}

	private static CutOp buildPrismMatrix(Matrix4f inv, float scale, float[] pr) {
		if (pr.length < 13) return null;
		int n = Math.min(24, (int) pr[12]);
		if (n < 3 || pr.length < 13 + n * 2) return null;
		float pxPerBlock = 16f / scale;
		Vector3f o = inv.transformPosition(pr[0], pr[1], pr[2], new Vector3f());
		Vector3f u = txDir(inv, o, pr[0], pr[1], pr[2], pr[3], pr[4], pr[5]);
		Vector3f v = txDir(inv, o, pr[0], pr[1], pr[2], pr[6], pr[7], pr[8]);
		Vector3f a = txDir(inv, o, pr[0], pr[1], pr[2], pr[9], pr[10], pr[11]);
		float[] px = new float[n], py = new float[n];
		for (int i = 0; i < n; i++) {
			px[i] = pr[13 + i * 2] * pxPerBlock;
			py[i] = pr[13 + i * 2 + 1] * pxPerBlock;
		}
		return new CutOp.PrismVolume(o.x, o.y, o.z, u.x, u.y, u.z, v.x, v.y, v.z, a.x, a.y, a.z, 48f, px, py, false);
	}

	// ------------------------------------------------------------------ red y efectos

	public static Payloads.DismemberSync payload(LivingEntity le) {
		DismemberState st = state(le);
		Caps c = caps(le);
		if (st == null || st.isEmpty()) {
			return new Payloads.DismemberSync(le.getId(), List.of(), List.of(), 0f, 1f, List.of(), new float[0], "");
		}
		CutVolume rem = st.remainingVolume();
		float[] pl = rem.isWhole() ? new float[0] : rem.firstPlane();
		if (pl == null) pl = new float[0];
		return new Payloads.DismemberSync(le.getId(), new ArrayList<>(st.detached.keySet()), new ArrayList<>(st.lockedUntil.keySet()),
			c.drop, c.heightScale, new ArrayList<>(st.foreign.keySet()), pl, rem.isWhole() ? "" : rem.encodeString());
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
