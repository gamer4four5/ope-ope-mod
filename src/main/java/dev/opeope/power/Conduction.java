package dev.opeope.power;

import dev.opeope.OpeConfig;
import dev.opeope.entity.PieceEntity;
import dev.opeope.registry.ModDamage;
import dev.opeope.registry.ModEffects;
import dev.opeope.terrain.MaterialTrait;
import net.minecraft.block.Blocks;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.math.random.Random;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Red de conducción eléctrica para piezas y armas con trait CONDUCTIVE (cobre / hierro).
 * Actúa como pararrayos para Counter Shock, Shock Wille y golpes de armas conductoras.
 */
public final class Conduction {
	private Conduction() {}

	private record Node(int kind, Vec3d pos, @Nullable PieceEntity piece, @Nullable LivingEntity entity, int hop, int maxHops) {
		static final int SEED = 0, PIECE = 1, ENTITY = 2;
	}

	public static int chain(ServerPlayerEntity p, @Nullable PlayerPower pw, List<Vec3d> sourcePoints,
							Set<LivingEntity> alreadyHit, float baseDmg, int baseStun, boolean gammaFx) {
		return chainInternal(p, pw, sourcePoints, alreadyHit, baseDmg, baseStun, gammaFx, 0,
			OpeConfig.ARC_PICKUP, null, null, null);
	}

	public static int miniChainFromStrike(ServerPlayerEntity p, @Nullable PlayerPower pw, PieceEntity strikerWeapon,
											LivingEntity struckTarget, float baseDmg, int baseStun) {
		Set<LivingEntity> alreadyHit = new HashSet<>();
		alreadyHit.add(struckTarget);
		return chainInternal(p, pw, List.of(struckTarget.getBoundingBox().getCenter()), alreadyHit, baseDmg, baseStun,
			false, 2, OpeConfig.ARC_PIECE_LINK, null, null, strikerWeapon);
	}

	public static int chainAlongRay(ServerPlayerEntity p, @Nullable PlayerPower pw, List<Vec3d> sourcePoints,
									Set<LivingEntity> alreadyHit, float baseDmg, int baseStun, boolean gammaFx,
									Vec3d rayFrom, Vec3d rayTo) {
		return chainInternal(p, pw, sourcePoints, alreadyHit, baseDmg, baseStun, gammaFx, 0,
			OpeConfig.ARC_PICKUP, rayFrom, rayTo, null);
	}

	private static int chainInternal(ServerPlayerEntity p, @Nullable PlayerPower pw, List<Vec3d> sourcePoints,
									 Set<LivingEntity> alreadyHit, float baseDmg, int baseStun, boolean gammaFx,
									 int maxHopsOverride, double initialPickupDist,
									 @Nullable Vec3d rayFrom, @Nullable Vec3d rayTo, @Nullable PieceEntity excludePiece) {
		ServerWorld w = p.getServerWorld();
		Box searchBox = roomBox(p, pw);
		List<PieceEntity> pieces = new ArrayList<>();
		for (PieceEntity pe : w.getEntitiesByClass(PieceEntity.class, searchBox,
			e -> e != excludePiece && e.isAlive() && !e.isRemoved()
				&& (e.flags() & PieceEntity.F_LANDED) == 0 && e.trait() == MaterialTrait.CONDUCTIVE)) {
			if (inScope(p, pw, pe.center())) pieces.add(pe);
		}
		if (pieces.isEmpty()) return 0;

		List<LivingEntity> candidates = new ArrayList<>();
		for (LivingEntity le : w.getEntitiesByClass(LivingEntity.class, searchBox,
			e -> e.isAlive() && !e.isSpectator() && !ElementEffects.spares(p, p.getUuid(), e))) {
			if (inScope(p, pw, le.getBoundingBox().getCenter())) candidates.add(le);
		}

		Set<Integer> visitedPieces = new HashSet<>();
		Set<Integer> visitedEntities = new HashSet<>();
		Set<Integer> damagedEntities = new HashSet<>();
		for (LivingEntity le : alreadyHit) {
			if (le != null) {
				damagedEntities.add(le.getId());
				visitedEntities.add(le.getId());
			}
		}

		int baseMaxHops = maxHopsOverride > 0 ? maxHopsOverride : OpeConfig.CHAIN_MAX_HOPS;
		ArrayDeque<Node> q = new ArrayDeque<>();
		for (Vec3d sp : sourcePoints) {
			q.add(new Node(Node.SEED, sp, null, null, 0, baseMaxHops));
		}
		for (LivingEntity le : alreadyHit) {
			if (le != null && le.isAlive()) {
				q.add(new Node(Node.ENTITY, le.getBoundingBox().getCenter(), null, le, 0, baseMaxHops));
			}
		}

		// Cualquier pieza conductora cercana al rayo de Shock Wille entra como nodo directo
		if (rayFrom != null && rayTo != null) {
			for (PieceEntity pe : pieces) {
				if (visitedPieces.contains(pe.getId())) continue;
				Vec3d pc = pe.center();
				Vec3d closest = closestOnSegment(pc, rayFrom, rayTo);
				if (pc.distanceTo(closest) <= 2.5) {
					visitPiece(w, pe, closest, pc, 1, baseMaxHops, gammaFx, visitedPieces, q);
				}
			}
		}

		int chainedTargets = 0;
		while (!q.isEmpty() && chainedTargets < OpeConfig.CHAIN_MAX_TARGETS) {
			Node cur = q.poll();
			if (cur.hop >= cur.maxHops) continue;

			if (cur.kind == Node.SEED || cur.kind == Node.ENTITY) {
				double pickup = cur.hop == 0 ? initialPickupDist : OpeConfig.ARC_PICKUP;
				for (PieceEntity pe : pieces) {
					if (visitedPieces.contains(pe.getId())) continue;
					boolean impaledHere = cur.entity != null && pe.impaled == cur.entity.getId();
					double d = impaledHere ? 0.0 : cur.pos.distanceTo(pe.center());
					if (d <= pickup) {
						int nextHop = cur.hop + 1;
						if (nextHop <= cur.maxHops) {
							visitPiece(w, pe, cur.pos, pe.center(), nextHop, cur.maxHops, gammaFx, visitedPieces, q);
						}
					}
				}
			} else if (cur.kind == Node.PIECE && cur.piece != null) {
				PieceEntity pe = cur.piece;
				boolean rod = pe.blocks != null && pe.blocks.hasBlock(Blocks.LIGHTNING_ROD);
				int branchMaxHops = maxHopsOverride > 0 ? maxHopsOverride : Math.max(cur.maxHops, OpeConfig.CHAIN_MAX_HOPS + (rod ? 1 : 0));
				if (cur.hop >= branchMaxHops) continue;

				// Pieza -> Pieza (excepto si es el mini-salto de 1 salto de arma)
				if (maxHopsOverride <= 0) {
					for (PieceEntity other : pieces) {
						if (visitedPieces.contains(other.getId())) continue;
						if (cur.pos.distanceTo(other.center()) <= OpeConfig.ARC_PIECE_LINK) {
							visitPiece(w, other, cur.pos, other.center(), cur.hop + 1, branchMaxHops, gammaFx, visitedPieces, q);
						}
					}
				}

				// Pieza -> Entidad
				double jumpRange = OpeConfig.ARC_JUMP + (rod ? 2.0 : 0.0);
				for (LivingEntity le : candidates) {
					if (visitedEntities.contains(le.getId())) continue;
					boolean impaledHere = pe.impaled == le.getId();
					Vec3d lc = le.getBoundingBox().getCenter();
					if (!impaledHere && cur.pos.distanceTo(lc) > jumpRange) continue;

					visitedEntities.add(le.getId());
					int nextHop = cur.hop + 1;
					if (!impaledHere) drawZigzagArc(w, cur.pos, lc, gammaFx);

					if (!damagedEntities.contains(le.getId())) {
						damagedEntities.add(le.getId());
						chainedTargets++;
						float factor = (float) Math.pow(OpeConfig.CHAIN_FALLOFF, Math.max(0, nextHop - 1));
						float dmg = baseDmg * factor;
						if (le.isTouchingWaterOrRain() || le.isWet()) dmg *= 1.5f;
						if (le.hasStatusEffect(ModEffects.STUN)) dmg *= 1.25f;
						le.timeUntilRegen = 0;
						le.damage(ModDamage.of(w, gammaFx ? ModDamage.GAMMA : ModDamage.SHOCK, p), dmg);
						int stun = Math.max(15, (int) Math.round(baseStun * Math.pow(0.75, Math.max(0, nextHop - 1))));
						le.addStatusEffect(new StatusEffectInstance(ModEffects.STUN, stun, 0, false, true));
						w.spawnParticles(ParticleTypes.ELECTRIC_SPARK, lc.x, lc.y, lc.z, 26, 0.35, 0.45, 0.35, 0.35);
						if (gammaFx) w.spawnParticles(Techniques.GAMMA_DUST, lc.x, lc.y, lc.z, 14, 0.35, 0.45, 0.35, 0.02);
						if (chainedTargets >= OpeConfig.CHAIN_MAX_TARGETS) break;
					}
					if (nextHop < branchMaxHops) {
						q.add(new Node(Node.ENTITY, lc, null, le, nextHop, branchMaxHops));
					}
				}
			}
		}
		return chainedTargets;
	}

	private static void visitPiece(ServerWorld w, PieceEntity pe, Vec3d fromPos, Vec3d toPos,
								   int hop, int maxHops, boolean gammaFx, Set<Integer> visitedPieces, ArrayDeque<Node> q) {
		visitedPieces.add(pe.getId());
		ElementEffects.markConductiveOverload(pe, 10);
		if (fromPos.squaredDistanceTo(toPos) > 0.04) drawZigzagArc(w, fromPos, toPos, gammaFx);
		w.spawnParticles(ParticleTypes.ELECTRIC_SPARK, toPos.x, toPos.y, toPos.z, 14, 0.3, 0.3, 0.3, 0.2);
		w.playSound(null, toPos.x, toPos.y, toPos.z, SoundEvents.ENTITY_LIGHTNING_BOLT_IMPACT, SoundCategory.PLAYERS, 0.45f, 1.6f);
		boolean rod = pe.blocks != null && pe.blocks.hasBlock(Blocks.LIGHTNING_ROD);
		int branchMax = rod ? Math.max(maxHops, OpeConfig.CHAIN_MAX_HOPS + 1) : maxHops;
		q.add(new Node(Node.PIECE, toPos, pe, null, hop, branchMax));
	}

	private static void drawZigzagArc(ServerWorld w, Vec3d from, Vec3d to, boolean gammaFx) {
		Random rnd = w.getRandom();
		Vec3d diff = to.subtract(from);
		double len = diff.length();
		if (len < 0.05) return;
		int segs = 4;
		double jitter = Math.min(0.45, Math.max(0.12, len * 0.07));
		Vec3d prev = from;
		for (int i = 1; i <= segs; i++) {
			double u = i / (double) segs;
			Vec3d pt = from.add(diff.multiply(u));
			if (i < segs) {
				pt = pt.add((rnd.nextDouble() - 0.5) * 2.0 * jitter,
					(rnd.nextDouble() - 0.5) * 2.0 * jitter,
					(rnd.nextDouble() - 0.5) * 2.0 * jitter);
			}
			Vec3d seg = pt.subtract(prev);
			double segLen = seg.length();
			int steps = Math.max(2, (int) Math.min(24, segLen * 3.0));
			for (int s = 0; s <= steps; s++) {
				Vec3d p = prev.add(seg.multiply(s / (double) steps));
				w.spawnParticles(ParticleTypes.ELECTRIC_SPARK, p.x, p.y, p.z, 1, 0, 0, 0, 0.01);
				if (gammaFx && s % 2 == 0) w.spawnParticles(Techniques.GAMMA_DUST, p.x, p.y, p.z, 1, 0, 0, 0, 0);
			}
			prev = pt;
		}
	}

	private static Vec3d closestOnSegment(Vec3d p, Vec3d a, Vec3d b) {
		Vec3d ab = b.subtract(a);
		double len2 = ab.lengthSquared();
		if (len2 < 1e-6) return a;
		double t = MathHelper.clamp(p.subtract(a).dotProduct(ab) / len2, 0.0, 1.0);
		return a.add(ab.multiply(t));
	}

	private static Box roomBox(ServerPlayerEntity p, @Nullable PlayerPower pw) {
		if (pw != null) {
			Box b = null;
			var dim = p.getWorld().getRegistryKey();
			if (pw.room != null && pw.room.dim == dim) {
				b = new Box(pw.room.center, pw.room.center).expand(pw.room.radius + 4);
			}
			if (pw.rroom != null && pw.rroom.dim == dim) {
				Box rb = new Box(pw.rroom.center, pw.rroom.center).expand(pw.rroom.radius + 4);
				b = b == null ? rb : b.union(rb);
			}
			if (b != null) return b;
		}
		return p.getBoundingBox().expand(36.0);
	}

	private static boolean inScope(ServerPlayerEntity p, @Nullable PlayerPower pw, Vec3d pos) {
		if (pw != null && (pw.room != null || pw.rroom != null)) {
			return PowerManager.inRoom(p, pos);
		}
		return pos.squaredDistanceTo(p.getPos()) <= 36.0 * 36.0;
	}
}
