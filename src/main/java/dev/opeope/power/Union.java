package dev.opeope.power;

import dev.opeope.entity.BlockSet;
import dev.opeope.entity.PieceEntity;
import dev.opeope.net.Payloads;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/** La Unión: lo cortado se vuelve a juntar cara con cara. Coser, reconstruir, rejuntar, unir todo y la Cirugía. */
public final class Union {
	private Union() {}

	/** Unir todo busca también las piezas que salieron volando del ROOM: hasta este margen alrededor de su caja. */
	private static final double HOME_MARGIN = 64;

	/**
	 * La regla de toda la Unión (elegir, coser, devolver y rejuntar): sin arma, sin estar posándose, sin estar ya
	 * volviendo o cosiéndose, y que no la lleve el Takt de otro.
	 */
	private static boolean unionOk(ServerPlayerEntity p, PieceEntity pe) {
		if (pe.weapon != null || pe.settling()) return false;
		if (pe.mode == PieceEntity.Mode.RETURNING || pe.mode == PieceEntity.Mode.SEWING) return false;
		UUID h = Takt.holder(pe.getId());
		return h == null || h.equals(p.getUuid());
	}

	/** Deja de ser de Takt, de suspendidas y de aparcadas de este jugador, y nadie la lleva ya. */
	private static void letGo(PlayerPower pw, PieceEntity pe) {
		int id = pe.getId();
		pw.takt.remove((Integer) id);
		pw.suspended.remove((Integer) id);
		pw.parked.remove(id);
		pw.taktOffsets.remove(id);
		pe.chimeraHostId = -1;
		Takt.forget(id);
	}

	public static void toggle(ServerPlayerEntity p, PlayerPower pw) {
		List<Entity> held = Takt.resolve(p, pw);
		boolean anyPiece = held.stream().anyMatch(e -> e instanceof PieceEntity);
		if (anyPiece) {
			// Con algo en Takt: lo que llevas se cose a lo que toca.
			Vec3d aim = PowerManager.aimPoint(p, 200);
			for (Entity e : held) {
				if (!(e instanceof PieceEntity pe)) continue;
				pw.takt.remove((Integer) pe.getId());
				pe.sewOnContact = true;
				pe.setFlag(PieceEntity.F_SEAM, true);
				Vec3d dir = aim.subtract(pe.center()).normalize().multiply(1.1);
				pe.release(dir, false);
			}
			pw.dirty = true;
			p.sendMessage(Text.translatable("opeope.msg.sew"), true);
			return;
		}
		pw.unionMode = !pw.unionMode;
		clearSelection(p, pw);
		pw.dirty = true;
		p.sendMessage(Text.translatable(pw.unionMode ? "opeope.msg.union_on" : "opeope.msg.union_off"), true);
	}

	private static void clearSelection(ServerPlayerEntity p, PlayerPower pw) {
		if (pw.unionSelected >= 0) {
			Entity e = p.getWorld().getEntityById(pw.unionSelected);
			if (e != null) e.setGlowing(false);
		}
		pw.unionSelected = -1;
	}

	public static void click(ServerPlayerEntity p, PlayerPower pw) {
		// Si ya hay una pieza seleccionada y se apunta a un ser vivo en el ROOM, injértala como Quimera Quirúrgica
		if (pw.unionSelected >= 0) {
			Entity sel = p.getWorld().getEntityById(pw.unionSelected);
			Entity aimedLiving = PowerManager.raycastEntity(p, 200, 0.3, ent -> ent instanceof LivingEntity le && le != p && le.isAlive());
			if (sel instanceof PieceEntity pe && !pe.isRemoved() && unionOk(p, pe) && aimedLiving instanceof LivingEntity le && PowerManager.inRoom(p, le.getPos())) {
				clearSelection(p, pw);
				pw.dirty = true;
				letGo(pw, pe);
				pe.setFlag(PieceEntity.F_HELD, false);
				pe.resetMotion();
				pe.mode = PieceEntity.Mode.CHIMERA;
				pe.chimeraHostId = le.getId();
				pe.setFlag(PieceEntity.F_SEAM, true);
				pe.setFlag(PieceEntity.F_SUSPENDED, false);
				le.setCustomName(Text.literal("§b[Quimera: " + le.getType().getName().getString() + "]"));
				le.setCustomNameVisible(true);
				le.addStatusEffect(new StatusEffectInstance(StatusEffects.RESISTANCE, 20 * 60, 1, false, true));
				Techniques.beam(p, pe.center(), le.getPos().add(0, le.getHeight(), 0), CutEngine.ROOM_DUST);
				p.getWorld().playSound(null, le.getX(), le.getY(), le.getZ(), SoundEvents.BLOCK_AMETHYST_BLOCK_CHIME, SoundCategory.PLAYERS, 1.2f, 1.3f);
				p.sendMessage(Text.literal("§bQuimera Quirúrgica creada sobre " + le.getType().getName().getString()), true);
				return;
			}
		}

		Entity e = PowerManager.raycastEntity(p, 200, 0.2, ent -> (ent instanceof PieceEntity pe && unionOk(p, pe))
			|| (ent instanceof LivingEntity le && CutStates.isCut(le)));
		if (e instanceof LivingEntity le) {
			if (!CutStates.reattach(le, false)) p.sendMessage(Text.translatable("opeope.msg.no_reattach"), true);
			return;
		}
		if (e instanceof PieceEntity pe && pe.getId() != pw.unionSelected) {
			clearSelection(p, pw);
			pw.unionSelected = pe.getId();
			pe.setGlowing(true);
			pw.dirty = true;
			p.getWorld().playSound(null, pe.getX(), pe.getY(), pe.getZ(), SoundEvents.BLOCK_AMETHYST_BLOCK_CHIME, SoundCategory.PLAYERS, 1f, 1.5f);
			return;
		}
		if (pw.unionSelected < 0) return;
		Entity sel = p.getWorld().getEntityById(pw.unionSelected);
		clearSelection(p, pw);
		pw.dirty = true;
		if (!(sel instanceof PieceEntity pe) || pe.isRemoved() || !unionOk(p, pe)) return;
		BlockHitResult hit = PowerManager.raycastBlock(p, 200);
		Vec3d target;
		if (hit.getType() == HitResult.Type.BLOCK) {
			Direction side = hit.getSide();
			Vec3d n = Vec3d.of(side.getVector());
			double half = Math.abs(n.x) * pe.blocks.sx / 2.0 + Math.abs(n.y) * pe.blocks.sy / 2.0 + Math.abs(n.z) * pe.blocks.sz / 2.0;
			target = hit.getPos().add(n.multiply(half + 0.02));
		} else {
			target = PowerManager.aimPoint(p, 30);
		}
		letGo(pw, pe);
		pe.setFlag(PieceEntity.F_HELD, false);
		pe.resetMotion();
		pe.mode = PieceEntity.Mode.SEWING;
		pe.target = target;
		pe.setFlag(PieceEntity.F_SEAM, true);
	}

	public static void reconstruct(ServerPlayerEntity p, PlayerPower pw) {
		Entity e = PowerManager.raycastEntity(p, 200, 0.2, ent -> (ent instanceof PieceEntity pe && pe.weapon == null)
			|| (ent instanceof LivingEntity le && CutStates.isCut(le)));
		if (e instanceof LivingEntity le) {
			CutStates.reattach(le, false);
			return;
		}
		if (e instanceof PieceEntity pe) {
			sendHome(p, pw, pe, 0);
			return;
		}
		uniteAll(p, pw);
	}

	/**
	 * Manda una pieza de vuelta a su sitio tras delay ticks (vuela en arco y se sella al llegar). Quien la manda pasa a
	 * ser su dueño, así su aviso HOME es del mismo jugador que el REWIND de Unir todo.
	 */
	private static boolean sendHome(ServerPlayerEntity p, PlayerPower pw, PieceEntity pe, int delay) {
		if (pe.origin == null || !unionOk(p, pe)) return false;
		letGo(pw, pe);
		pe.owner = p.getUuid();
		pe.startReturn(delay);
		pw.dirty = true;
		return true;
	}

	/**
	 * R X: todo lo cortado vuelve volando a su sitio y se sella, lo más reciente primero y de abajo arriba, en una ola
	 * de 1,1 s; los seres cortados se recomponen. Cuenta las piezas que están en el ROOM o cuyo sitio está en él.
	 */
	public static void uniteAll(ServerPlayerEntity p, PlayerPower pw) {
		Room room = pw.room;
		if (room == null) {
			PowerManager.noRoom(p);
			return;
		}
		Box box = new Box(room.center, room.center).expand(room.radius);
		List<PieceEntity> pieces = new ArrayList<>(p.getWorld().getEntitiesByClass(PieceEntity.class, box.expand(HOME_MARGIN),
			e -> e.origin != null && (e.flags() & PieceEntity.F_LANDED) == 0 && unionOk(p, e)
				&& (room.contains(e.center()) || room.contains(homeCenter(e)))));
		pieces.sort((a, b) -> {
			int c = Long.compare(b.fxTime(), a.fxTime());
			if (c == 0) c = Integer.compare(a.origin.getY(), b.origin.getY());
			return c != 0 ? c : Integer.compare(a.getId(), b.getId());
		});
		int n = pieces.size(), sent = 0, longest = 0;
		for (int i = 0; i < n; i++) {
			PieceEntity pe = pieces.get(i);
			int flight = flight(pe);
			if (sendHome(p, pw, pe, n > 1 ? (int) Math.round(22.0 * i / (n - 1)) : 0)) {
				sent++;
				longest = Math.max(longest, flight);
			}
		}
		int living = 0;
		for (LivingEntity le : p.getWorld().getEntitiesByClass(LivingEntity.class, box, e -> CutStates.isCut(e) && room.contains(e.getPos()))) {
			if (CutStates.reattach(le, false)) living++;
		}
		p.sendMessage(Text.translatable("opeope.msg.united", sent + living), true);
		Takt.sendFx(p.getServerWorld(), new Payloads.TaktFx(Payloads.TaktFx.REWIND, 0, -1, p.getUuid(), room.center.x, room.center.y,
			room.center.z, (float) room.radius, 0f, 22 + longest, 0, sent, new int[0]));
	}

	/** El centro de la pieza cuando esté en su sitio. */
	private static Vec3d homeCenter(PieceEntity pe) {
		return new Vec3d(pe.origin.getX() + pe.blocks.sx / 2.0, pe.origin.getY() + pe.blocks.sy / 2.0, pe.origin.getZ() + pe.blocks.sz / 2.0);
	}

	/** Lo que tardará su vuelo de vuelta, en ticks (la misma cuenta que PieceEntity.startReturn). */
	private static int flight(PieceEntity pe) {
		Vec3d home = new Vec3d(pe.origin.getX() + pe.blocks.sx / 2.0, pe.origin.getY(), pe.origin.getZ() + pe.blocks.sz / 2.0);
		double d = home.distanceTo(pe.getPos());
		return Math.max(4, (int) Math.round(20 * 1.15 * (0.8 + Math.min(0.6, d / 60))));
	}

	/**
	 * R Z (Rejuntar): une todas las piezas cortadas del ROOM conservando la forma exacta en que fueron cortadas
	 * (por ejemplo, si cortaste un árbol o una casa, se reensambla manteniendo su estructura original intacta) y las compacta en 3D.
	 */
	public static void gather(ServerPlayerEntity p, PlayerPower pw) {
		Room room = pw.room;
		if (room == null) {
			PowerManager.noRoom(p);
			return;
		}
		Box box = new Box(room.center, room.center).expand(room.radius);
		List<PieceEntity> pieces = p.getWorld().getEntitiesByClass(PieceEntity.class, box, e -> unionOk(p, e) && room.contains(e.center()));
		if (pieces.isEmpty()) {
			p.sendMessage(Text.translatable("opeope.msg.nothing_cut"), true);
			return;
		}
		Vec3d gc = p.getEyePos().add(p.getRotationVector().multiply(Math.max(5.0, Math.min(pw.taktDist, 24.0)))).add(0, 1.0, 0);
		ServerWorld w = p.getServerWorld();

		// 1. Agrupamos piezas con origen original (origin != null) por proximidad estructural
		List<PieceEntity> withOrigin = new ArrayList<>();
		List<PieceEntity> noOrigin = new ArrayList<>();
		for (PieceEntity pe : pieces) {
			if (pe.origin != null) withOrigin.add(pe);
			else noOrigin.add(pe);
		}

		List<List<PieceEntity>> clusters = new ArrayList<>();
		boolean[] used = new boolean[withOrigin.size()];
		for (int i = 0; i < withOrigin.size(); i++) {
			if (used[i]) continue;
			used[i] = true;
			List<PieceEntity> cluster = new ArrayList<>();
			cluster.add(withOrigin.get(i));
			boolean grew = true;
			while (grew) {
				grew = false;
				int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
				int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
				for (PieceEntity cp : cluster) {
					minX = Math.min(minX, cp.origin.getX());
					minY = Math.min(minY, cp.origin.getY());
					minZ = Math.min(minZ, cp.origin.getZ());
					maxX = Math.max(maxX, cp.origin.getX() + cp.blocks.sx);
					maxY = Math.max(maxY, cp.origin.getY() + cp.blocks.sy);
					maxZ = Math.max(maxZ, cp.origin.getZ() + cp.blocks.sz);
				}
				for (int j = 0; j < withOrigin.size(); j++) {
					if (used[j]) continue;
					PieceEntity cand = withOrigin.get(j);
					int cMinX = cand.origin.getX(), cMinY = cand.origin.getY(), cMinZ = cand.origin.getZ();
					int cMaxX = cMinX + cand.blocks.sx, cMaxY = cMinY + cand.blocks.sy, cMaxZ = cMinZ + cand.blocks.sz;
					boolean near = cMaxX >= minX - 2 && cMinX <= maxX + 2
						&& cMaxY >= minY - 2 && cMinY <= maxY + 2
						&& cMaxZ >= minZ - 2 && cMinZ <= maxZ + 2;
					if (near) {
						used[j] = true;
						cluster.add(cand);
						grew = true;
					}
				}
			}
			clusters.add(cluster);
		}
		for (PieceEntity np : noOrigin) clusters.add(List.of(np));
		clusters.sort((a, b) -> Integer.compare(
			b.stream().mapToInt(pe -> pe.blocks.size()).sum(),
			a.stream().mapToInt(pe -> pe.blocks.size()).sum()
		));

		// 2. Construimos el BlockSet intacto de cada grupo estructural (preservando faces cortadas)
		List<BlockSet> shapes = new ArrayList<>(clusters.size());
		net.minecraft.util.math.BlockPos singleOriginBase = null;
		boolean preserveOrigin = (clusters.size() == 1 && !withOrigin.isEmpty() && noOrigin.isEmpty());
		int totalEntries = 0;

		for (int ci = 0; ci < clusters.size(); ci++) {
			List<PieceEntity> cluster = clusters.get(ci);
			int clusterBlocks = 0;
			for (PieceEntity pe : cluster) clusterBlocks += pe.blocks.size();
			if (!shapes.isEmpty() && totalEntries + clusterBlocks > 48000) continue;
			if (shapes.isEmpty() && clusterBlocks > 48000) continue;

			int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
			boolean hasOrig = cluster.get(0).origin != null;
			for (PieceEntity pe : cluster) {
				int ox = hasOrig ? pe.origin.getX() : (int) Math.round(pe.corner().x);
				int oy = hasOrig ? pe.origin.getY() : (int) Math.round(pe.corner().y);
				int oz = hasOrig ? pe.origin.getZ() : (int) Math.round(pe.corner().z);
				minX = Math.min(minX, ox);
				minY = Math.min(minY, oy);
				minZ = Math.min(minZ, oz);
			}
			if (preserveOrigin && ci == 0) singleOriginBase = new net.minecraft.util.math.BlockPos(minX, minY, minZ);
			BlockSet cSet = new BlockSet();
			for (PieceEntity pe : cluster) {
				int ox = (hasOrig ? pe.origin.getX() : (int) Math.round(pe.corner().x)) - minX;
				int oy = (hasOrig ? pe.origin.getY() : (int) Math.round(pe.corner().y)) - minY;
				int oz = (hasOrig ? pe.origin.getZ() : (int) Math.round(pe.corner().z)) - minZ;
				for (BlockSet.Entry entry : pe.blocks.entries) {
					cSet.add(ox + entry.x(), oy + entry.y(), oz + entry.z(), entry.state(), entry.be(), entry.faces());
					totalEntries++;
				}
				Techniques.beam(p, pe.center(), gc, CutEngine.ROOM_DUST);
				letGo(pw, pe);
				pe.discard();
			}
			if (!cSet.isEmpty()) {
				cSet.normalize();
				shapes.add(cSet);
			}
		}
		if (shapes.isEmpty()) {
			p.sendMessage(Text.translatable("opeope.msg.nothing_cut"), true);
			return;
		}

		// 3. Empaquetado 3D esférico Fibonacci alrededor del núcleo mayor conservando la forma de cada estructura
		java.util.Map<net.minecraft.util.math.BlockPos, BlockSet.Entry> placed = new java.util.LinkedHashMap<>();
		it.unimi.dsi.fastutil.longs.LongOpenHashSet occupied = new it.unimi.dsi.fastutil.longs.LongOpenHashSet();
		{
			BlockSet core = shapes.get(0);
			int cx0 = -core.sx / 2, cy0 = -core.sy / 2, cz0 = -core.sz / 2;
			int occMinX = cx0, occMaxX = cx0 + core.sx;
			int occMinY = cy0, occMaxY = cy0 + core.sy;
			int occMinZ = cz0, occMaxZ = cz0 + core.sz;
			for (BlockSet.Entry e : core.entries) {
				int px = cx0 + e.x(), py = cy0 + e.y(), pz = cz0 + e.z();
				net.minecraft.util.math.BlockPos pos = new net.minecraft.util.math.BlockPos(px, py, pz);
				if (placed.putIfAbsent(pos, e) == null) occupied.add(pos.asLong());
			}
			final int dirs = 72;
			final double golden = Math.PI * (3.0 - Math.sqrt(5.0));
			for (int si = 1; si < shapes.size(); si++) {
				BlockSet sh = shapes.get(si);
				List<BlockSet.Entry> surf = sh.surface();
				int occSpan = Math.max(occMaxX - occMinX, Math.max(occMaxY - occMinY, occMaxZ - occMinZ));
				int shSpan = Math.max(sh.sx, Math.max(sh.sy, sh.sz));
				double maxD = Math.max(140.0, occSpan + shSpan + 24.0);
				double bestD2 = Double.MAX_VALUE;
				int bestX = occMaxX, bestY = 0, bestZ = 0;
				double phase = si * 1.13;
				for (int di = 0; di < dirs; di++) {
					double y = 1.0 - (2.0 * (di + 0.5)) / dirs;
					double rad = Math.sqrt(Math.max(0.0, 1.0 - y * y));
					double theta = golden * di + phase;
					double dx = Math.cos(theta) * rad;
					double dz = Math.sin(theta) * rad;
					double minRadius = Math.max(1.0, Math.min(sh.sx, Math.min(sh.sy, sh.sz)) * 0.25);
					for (double d = minRadius; d <= maxD; d += 1.0) {
						int sx = (int) Math.round(dx * d) - sh.sx / 2;
						int sy = (int) Math.round(y * d) - sh.sy / 2;
						int sz = (int) Math.round(dz * d) - sh.sz / 2;
						boolean collides = false;
						boolean boxOverlap = sx + sh.sx > occMinX && sx < occMaxX
							&& sy + sh.sy > occMinY && sy < occMaxY
							&& sz + sh.sz > occMinZ && sz < occMaxZ;
						if (boxOverlap) {
							for (BlockSet.Entry e : surf) {
								if (occupied.contains(net.minecraft.util.math.BlockPos.asLong(sx + e.x(), sy + e.y(), sz + e.z()))) {
									collides = true;
									break;
								}
							}
						}
						if (!collides) {
							double mx = sx + sh.sx * 0.5, my = sy + sh.sy * 0.5, mz = sz + sh.sz * 0.5;
							double d2 = mx * mx + my * my + mz * mz;
							if (d2 < bestD2) {
								bestD2 = d2;
								bestX = sx;
								bestY = sy;
								bestZ = sz;
							}
							break;
						}
					}
				}
				occMinX = Math.min(occMinX, bestX);
				occMaxX = Math.max(occMaxX, bestX + sh.sx);
				occMinY = Math.min(occMinY, bestY);
				occMaxY = Math.max(occMaxY, bestY + sh.sy);
				occMinZ = Math.min(occMinZ, bestZ);
				occMaxZ = Math.max(occMaxZ, bestZ + sh.sz);
				for (BlockSet.Entry e : sh.entries) {
					int px = bestX + e.x(), py = bestY + e.y(), pz = bestZ + e.z();
					net.minecraft.util.math.BlockPos pos = new net.minecraft.util.math.BlockPos(px, py, pz);
					if (placed.putIfAbsent(pos, e) == null) occupied.add(pos.asLong());
				}
			}
		}

		BlockSet assembled = new BlockSet();
		for (var e : placed.entrySet()) {
			net.minecraft.util.math.BlockPos pos = e.getKey();
			BlockSet.Entry be = e.getValue();
			assembled.add(pos.getX(), pos.getY(), pos.getZ(), be.state(), be.be(), be.faces());
		}
		assembled.normalize();
		PieceEntity mass = PieceEntity.spawn(w, assembled, gc.subtract(assembled.sx / 2.0, assembled.sy / 2.0, assembled.sz / 2.0), singleOriginBase, p.getUuid());
		mass.setFlag(PieceEntity.F_SEAM, true);
		Takt.grab(p, pw, mass);
		w.spawnParticles(ParticleTypes.END_ROD, gc.x, gc.y, gc.z, 60, assembled.sx / 2.0, assembled.sy / 2.0, assembled.sz / 2.0, 0.05);
		w.playSound(null, gc.x, gc.y, gc.z, SoundEvents.BLOCK_AMETHYST_BLOCK_CHIME, SoundCategory.PLAYERS, 2f, 0.5f);
	}

	/** Coloca bloques en una bola compacta (para Rejuntar y lo que sobra al forjar). */
	public static BlockSet ball(List<BlockSet.Entry> entries) {
		int n = entries.size();
		int r = (int) Math.ceil(Math.cbrt(3.0 * n / (4 * Math.PI))) + 1;
		List<int[]> cells = new ArrayList<>();
		for (int x = -r; x <= r; x++) for (int y = -r; y <= r; y++) for (int z = -r; z <= r; z++) cells.add(new int[]{x, y, z});
		cells.sort(Comparator.comparingInt(c -> c[0] * c[0] + c[1] * c[1] + c[2] * c[2]));
		BlockSet set = new BlockSet();
		for (int i = 0; i < n && i < cells.size(); i++) {
			BlockSet.Entry e = entries.get(i);
			int[] c = cells.get(i);
			set.add(c[0] + r, c[1] + r, c[2] + r, e.state(), e.be(), e.faces());
		}
		set.normalize();
		return set;
	}

	public static void surgery(ServerPlayerEntity p, PlayerPower pw, boolean start) {
		pw.surgery = start;
		pw.surgeryTicks = 0;
		if (start) p.sendMessage(Text.translatable("opeope.msg.surgery"), true);
	}

	/**
	 * Autocirugía y Cirugía de Reconstrucción Avanzada (Tab + C mantenido):
	 * Cura órganos internos, reimplanta extremidades cortadas, extrae todos los venenos/efectos negativos,
	 * restaura hambre/saturación y envuelve al paciente en una doble hélice quirúrgica 3D.
	 */
	public static void surgeryTick(ServerPlayerEntity p, PlayerPower pw) {
		if (!pw.surgery) return;
		pw.surgeryTicks++;
		Entity t = PowerManager.raycastEntity(p, 5.5, 0.3, e -> e instanceof LivingEntity);
		LivingEntity target = t instanceof LivingEntity le ? le : p;
		ServerWorld w = p.getServerWorld();

		// Doble hélice quirúrgica alrededor del paciente cada 2 ticks
		if (pw.surgeryTicks % 2 == 0) {
			double a = pw.surgeryTicks * 0.35;
			double h = (pw.surgeryTicks % 24) / 24.0 * target.getHeight();
			for (int k = 0; k < 2; k++) {
				double ang = a + k * Math.PI;
				double rx = target.getX() + Math.cos(ang) * 0.85;
				double rz = target.getZ() + Math.sin(ang) * 0.85;
				w.spawnParticles(CutEngine.ROOM_DUST, rx, target.getY() + h, rz, 1, 0, 0, 0, 0);
			}
		}

		if (pw.surgeryTicks % 8 != 0) return;
		target.heal(2.2f);
		CutStates.reattach(target, false);
		target.removeStatusEffect(StatusEffects.POISON);
		target.removeStatusEffect(StatusEffects.WITHER);
		target.removeStatusEffect(StatusEffects.SLOWNESS);
		target.removeStatusEffect(StatusEffects.WEAKNESS);
		target.removeStatusEffect(StatusEffects.BLINDNESS);
		target.removeStatusEffect(StatusEffects.DARKNESS);
		target.removeStatusEffect(StatusEffects.NAUSEA);
		target.removeStatusEffect(StatusEffects.MINING_FATIGUE);
		target.removeStatusEffect(StatusEffects.HUNGER);
		if (target instanceof ServerPlayerEntity sp) {
			sp.getHungerManager().add(1, 0.5f);
		}
		Box b = target.getBoundingBox();
		w.spawnParticles(ParticleTypes.HAPPY_VILLAGER, b.getCenter().x, b.getCenter().y, b.getCenter().z, 6, b.getLengthX() / 2, b.getLengthY() / 2, b.getLengthZ() / 2, 0);
		w.spawnParticles(CutEngine.ROOM_DUST, b.getCenter().x, b.getCenter().y, b.getCenter().z, 6, b.getLengthX() / 2, b.getLengthY() / 2, b.getLengthZ() / 2, 0);
		if (pw.surgeryTicks % 32 == 0) {
			target.addStatusEffect(new StatusEffectInstance(StatusEffects.REGENERATION, 60, 1, false, false, true));
			w.playSound(null, target.getX(), target.getY(), target.getZ(), SoundEvents.BLOCK_AMETHYST_BLOCK_CHIME, SoundCategory.PLAYERS, 0.7f, 1.5f);
		}
	}
}
