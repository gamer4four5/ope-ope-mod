package dev.opeope.power;

import dev.opeope.entity.BlockSet;
import dev.opeope.entity.PieceEntity;
import dev.opeope.net.Payloads;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.registry.tag.BlockTags;
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
	 * R Z (Rejuntar): une todas las piezas cortadas del ROOM en una esfera densa y compacta (núcleo sólido por dentro,
	 * follaje recortado si excede 2400 bloques) lista para lanzar y abrir cráter sin atorarse en los árboles.
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
		ServerWorld w = p.getServerWorld();
		List<BlockSet.Entry> raw = new ArrayList<>();
		for (PieceEntity pe : pieces) {
			if (pe.blocks == null || pe.blocks.isEmpty()) continue;
			raw.addAll(pe.blocks.entries);
		}
		if (raw.isEmpty()) {
			p.sendMessage(Text.translatable("opeope.msg.nothing_cut"), true);
			return;
		}
		raw.sort((a, b) -> {
			boolean aLeaf = a.state().isIn(BlockTags.LEAVES);
			boolean bLeaf = b.state().isIn(BlockTags.LEAVES);
			if (aLeaf != bLeaf) return Boolean.compare(aLeaf, bLeaf);
			return Double.compare(dev.opeope.terrain.GroundRules.density(b.state()), dev.opeope.terrain.GroundRules.density(a.state()));
		});
		while (raw.size() > 2400 && raw.get(raw.size() - 1).state().isIn(BlockTags.LEAVES)) {
			raw.remove(raw.size() - 1);
		}
		if (raw.size() > 48000) {
			raw = new ArrayList<>(raw.subList(0, 48000));
		}

		BlockSet assembled = ball(raw);
		Vec3d gc = p.getEyePos()
			.add(p.getRotationVector().multiply(Math.max(5.0, Math.min(pw.taktDist, 24.0))))
			.add(0, Math.max(1.5, assembled.sy * 0.5), 0);

		for (PieceEntity pe : pieces) {
			Techniques.beam(p, pe.center(), gc, CutEngine.ROOM_DUST);
			letGo(pw, pe);
			pe.discard();
		}

		PieceEntity mass = PieceEntity.spawn(w, assembled, gc.subtract(assembled.sx / 2.0, assembled.sy / 2.0, assembled.sz / 2.0), null, p.getUuid());
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
