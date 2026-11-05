package dev.opeope.power;

import dev.opeope.entity.BlockSet;
import dev.opeope.entity.PieceEntity;
import dev.opeope.registry.ModEntities;
import net.minecraft.block.BlockState;
import net.minecraft.entity.Entity;
import net.minecraft.particle.BlockStateParticleEffect;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayList;
import java.util.List;

/**
 * Armas del entorno: el material de Takt se junta delante, se talla y se sella.
 * R 1 lanza, R 2 hoja colosal, R 3 martillo, R 4 estacas, R 5 muralla. Luego, clic para usarla.
 */
public final class Forge {
	private Forge() {}

	public static final String[] NAMES = {"Lanza", "Hoja colosal", "Martillo", "Estacas", "Muralla"};

	public static void forge(ServerPlayerEntity p, PlayerPower pw, int kind) {
		List<PieceEntity> pool = new ArrayList<>();
		int n = 0;
		for (Entity e : Takt.resolve(p, pw)) {
			if (e instanceof PieceEntity pe && pe.weapon == null) {
				pool.add(pe);
				n += pe.blocks.size();
			}
		}
		if (n < 3) {
			p.sendMessage(Text.translatable("opeope.msg.need_material"), true);
			return;
		}
		List<BlockSet.Entry> material = new ArrayList<>();
		for (PieceEntity pe : pool) material.addAll(pe.blocks.entries);
		BlockSet all = new BlockSet();
		for (BlockSet.Entry e : material) all.add(e.x(), e.y(), e.z(), e.state(), null);
		BlockState mat = all.dominant();
		if (mat == null) return;

		dropWeapons(p, pw);
		ServerWorld w = p.getServerWorld();
		Vec3d forgeAt = p.getEyePos().add(p.getRotationVector().multiply(3));
		for (PieceEntity pe : pool) {
			Techniques.beam(p, pe.center(), forgeAt, CutEngine.ROOM_DUST);
			pw.takt.remove((Integer) pe.getId());
			pe.discard();
		}

		int used;
		PieceEntity.Weapon weapon = PieceEntity.Weapon.values()[kind];
		List<PieceEntity> made = new ArrayList<>();
		switch (weapon) {
			case LANCE -> {
				int len = MathHelper.clamp(n, 4, 16);
				BlockSet s = new BlockSet();
				for (int z = 0; z < len; z++) s.add(0, 0, z, mat, null);
				made.add(weaponPiece(w, p, s, weapon));
				used = len;
			}
			case BLADE -> {
				int len = MathHelper.clamp(n / 2, 4, 20);
				BlockSet s = new BlockSet();
				for (int z = 0; z < len; z++) for (int x = 0; x < 2; x++) s.add(x, 0, z, mat, null);
				made.add(weaponPiece(w, p, s, weapon));
				used = len * 2;
			}
			case HAMMER -> {
				int head = n >= 31 ? 3 : n >= 12 ? 2 : 1;
				int handle = 4;
				BlockSet s = new BlockSet();
				int mid = head / 2;
				for (int z = 0; z < handle; z++) s.add(mid, mid, z, mat, null);
				for (int x = 0; x < head; x++) for (int y = 0; y < head; y++) for (int z = 0; z < head; z++) s.add(x, y, handle + z, mat, null);
				made.add(weaponPiece(w, p, s, weapon));
				used = handle + head * head * head;
			}
			case STAKE -> {
				int k = MathHelper.clamp(n / 3, 1, 12);
				for (int i = 0; i < k; i++) {
					BlockSet s = new BlockSet();
					for (int z = 0; z < 3; z++) s.add(0, 0, z, mat, null);
					PieceEntity pe = weaponPiece(w, p, s, weapon);
					pe.wIndex = i;
					pe.wCount = k;
					made.add(pe);
				}
				used = k * 3;
			}
			default -> {
				int wd = MathHelper.clamp((int) Math.sqrt(n * 2.0), 3, 16);
				int h = MathHelper.clamp(n / wd, 2, 10);
				Vec3d look = p.getRotationVector();
				boolean alongZ = Math.abs(look.x) > Math.abs(look.z);
				BlockSet s = new BlockSet();
				for (int a = 0; a < wd; a++) for (int y = 0; y < h; y++) {
					if (alongZ) s.add(0, y, a, mat, null);
					else s.add(a, y, 0, mat, null);
				}
				s.normalize();
				Vec3d flat = new Vec3d(look.x, 0, look.z);
				if (flat.lengthSquared() < 1e-4) flat = new Vec3d(0, 0, 1);
				Vec3d at = p.getPos().add(flat.normalize().multiply(3.5));
				PieceEntity pe = PieceEntity.spawn(w, s, new Vec3d(Math.floor(at.x - s.sx / 2.0 + 0.5), Math.floor(p.getY()), Math.floor(at.z - s.sz / 2.0 + 0.5)), null, p.getUuid());
				pe.weapon = weapon;
				pe.mode = PieceEntity.Mode.STUCK;
				pe.solid = true;
				made.add(pe);
				used = wd * h;
			}
		}
		for (PieceEntity pe : made) pw.weapons.add(pe.getId());
		pw.weaponKind = kind;

		// Lo que sobra espera en alto a tu izquierda.
		int left = n - used;
		if (left > 0) {
			List<BlockSet.Entry> rest = material.subList(Math.max(0, material.size() - left), material.size());
			BlockSet ball = Union.ball(new ArrayList<>(rest));
			Vec3d look = p.getRotationVector();
			Vec3d leftSide = new Vec3d(0, 1, 0).crossProduct(look);
			if (leftSide.lengthSquared() < 1e-4) leftSide = new Vec3d(1, 0, 0);
			Vec3d at = p.getEyePos().add(leftSide.normalize().multiply(2.5)).add(0, 1.5, 0);
			PieceEntity spare = PieceEntity.spawn(w, ball, at.subtract(ball.sx / 2.0, ball.sy / 2.0, ball.sz / 2.0), null, p.getUuid());
			pw.takt.add(spare.getId());
			spare.control(at);
			pw.taktMode = PlayerPower.TaktMode.PARK;
			pw.parked.put(spare.getId(), at);
		}

		w.spawnParticles(new BlockStateParticleEffect(ParticleTypes.BLOCK, mat), forgeAt.x, forgeAt.y, forgeAt.z, 60, 0.8, 0.8, 0.8, 0.1);
		w.spawnParticles(ParticleTypes.END_ROD, forgeAt.x, forgeAt.y, forgeAt.z, 30, 0.8, 0.8, 0.8, 0.05);
		w.playSound(null, p.getX(), p.getY(), p.getZ(), SoundEvents.BLOCK_ANVIL_USE, SoundCategory.PLAYERS, 1f, 0.7f);
		w.playSound(null, p.getX(), p.getY(), p.getZ(), SoundEvents.BLOCK_AMETHYST_BLOCK_RESONATE, SoundCategory.PLAYERS, 1f, 1.2f);
		p.sendMessage(Text.translatable("opeope.msg.forged", Text.translatable("opeope.weapon." + kind)), true);
		pw.dirty = true;
	}

	private static PieceEntity weaponPiece(ServerWorld w, ServerPlayerEntity p, BlockSet s, PieceEntity.Weapon weapon) {
		s.normalize();
		PieceEntity pe = new PieceEntity(ModEntities.PIECE, w);
		pe.owner = p.getUuid();
		pe.weapon = weapon;
		pe.setBlocks(s);
		pe.setFlag(PieceEntity.F_WEAPON, true);
		pe.setPosition(p.getEyePos().add(p.getRotationVector().multiply(2)));
		pe.setYaw(p.getYaw());
		pe.setPitch(p.getPitch());
		pe.noClip = true;
		pe.setNoGravity(true);
		w.spawnEntity(pe);
		return pe;
	}

	public static List<PieceEntity> weapons(ServerPlayerEntity p, PlayerPower pw) {
		List<PieceEntity> out = new ArrayList<>();
		pw.weapons.removeIf(id -> {
			Entity e = p.getWorld().getEntityById(id);
			if (e instanceof PieceEntity pe && !pe.isRemoved() && pe.weapon != null) {
				out.add(pe);
				return false;
			}
			return true;
		});
		if (out.isEmpty() && pw.weaponKind >= 0) {
			pw.weaponKind = -1;
			pw.dirty = true;
		}
		return out;
	}

	public static void use(ServerPlayerEntity p, PlayerPower pw) {
		List<PieceEntity> list = weapons(p, pw);
		if (list.isEmpty()) return;
		PieceEntity.Weapon kind = list.get(0).weapon;
		if (kind == PieceEntity.Weapon.STAKE) {
			for (PieceEntity pe : list) pe.use(p);
			pw.weapons.clear();
		} else {
			list.get(0).use(p);
			if (kind == PieceEntity.Weapon.LANCE) pw.weapons.clear();
		}
		pw.dirty = true;
	}

	public static void dropWeapons(ServerPlayerEntity p, PlayerPower pw) {
		for (PieceEntity pe : weapons(p, pw)) {
			if (pe.weapon == PieceEntity.Weapon.WALL) continue;
			if (pe.wState == 0) pe.becomePlain();
		}
		pw.weapons.clear();
		pw.weaponKind = -1;
		pw.dirty = true;
	}
}
