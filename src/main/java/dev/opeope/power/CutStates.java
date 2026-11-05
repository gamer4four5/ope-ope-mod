package dev.opeope.power;

import dev.opeope.net.Payloads;
import dev.opeope.registry.ModEffects;
import net.fabricmc.fabric.api.networking.v1.PlayerLookup;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * Seres vivos en piezas: «a un enemigo cortado no le quitas vida: queda en piezas vivas».
 * Los planos se guardan en el marco del cuerpo (girados por el yaw del cuerpo), así el corte gira con él.
 */
public final class CutStates {
	private CutStates() {}

	public static final int MAX_PLANES = 4;

	static final class State {
		final List<float[]> planes = new ArrayList<>(); // nx, ny, nz, d, gap (marco del cuerpo)
		int ticks;
		int noReattach;
		final ServerWorld world;
		final int entityId;

		State(ServerWorld world, int entityId) {
			this.world = world;
			this.entityId = entityId;
		}
	}

	private static final Map<Integer, State> STATES = new HashMap<>();

	/** Gira un vector alrededor del eje Y (grados, mano derecha). */
	public static Vec3d rotY(Vec3d v, double deg) {
		double a = Math.toRadians(deg), c = Math.cos(a), s = Math.sin(a);
		return new Vec3d(v.x * c + v.z * s, v.y, -v.x * s + v.z * c);
	}

	public static boolean isCut(LivingEntity le) {
		return STATES.containsKey(le.getId());
	}

	public static void cutLiving(LivingEntity le, List<CutEngine.Plane> planes, int ticks, int noReattach) {
		if (!(le.getWorld() instanceof ServerWorld w)) return;
		State st = STATES.computeIfAbsent(le.getId(), id -> new State(w, id));
		Vec3d feet = le.getPos();
		for (CutEngine.Plane pl : planes) {
			Vec3d n = pl.n();
			double d = n.dotProduct(pl.p().subtract(feet));
			if (n.y < 0) {
				n = n.multiply(-1);
				d = -d;
			}
			Vec3d nb = rotY(n, le.getBodyYaw());
			float gap = 0.28f + w.random.nextFloat() * 0.18f;
			st.planes.add(new float[]{(float) nb.x, (float) nb.y, (float) nb.z, (float) d, gap});
			while (st.planes.size() > MAX_PLANES) st.planes.remove(0);
		}
		st.ticks = Math.max(st.ticks, ticks);
		st.noReattach = Math.max(st.noReattach, noReattach);
		le.addStatusEffect(new StatusEffectInstance(ModEffects.CUT, st.ticks, 0, false, false, true));
		le.setVelocity(Vec3d.ZERO);
		le.velocityModified = true;
		sync(le, st);
	}

	/** Vuelve a unir un ser vivo cortado. force: también durante el Radio Knife. */
	public static boolean reattach(LivingEntity le, boolean force) {
		State st = STATES.get(le.getId());
		if (st == null) return false;
		if (st.noReattach > 0 && !force) return false;
		STATES.remove(le.getId());
		le.removeStatusEffect(ModEffects.CUT);
		sync(le, null);
		if (le.getWorld() instanceof ServerWorld w) {
			Box b = le.getBoundingBox();
			w.spawnParticles(ParticleTypes.END_ROD, b.getCenter().x, b.getCenter().y, b.getCenter().z, 20, b.getLengthX() / 2, b.getLengthY() / 2, b.getLengthZ() / 2, 0.01);
			w.playSound(null, le.getX(), le.getY(), le.getZ(), SoundEvents.BLOCK_AMETHYST_BLOCK_CHIME, SoundCategory.PLAYERS, 1f, 1.2f);
		}
		return true;
	}

	public static int noReattachLeft(LivingEntity le) {
		State st = STATES.get(le.getId());
		return st == null ? 0 : st.noReattach;
	}

	private static float[] flat(State st) {
		if (st == null) return new float[0];
		float[] out = new float[st.planes.size() * 5];
		for (int i = 0; i < st.planes.size(); i++) System.arraycopy(st.planes.get(i), 0, out, i * 5, 5);
		return out;
	}

	private static void sync(LivingEntity le, State st) {
		Payloads.CutSync payload = new Payloads.CutSync(le.getId(), flat(st));
		for (ServerPlayerEntity sp : PlayerLookup.tracking(le)) ServerPlayNetworking.send(sp, payload);
		if (le instanceof ServerPlayerEntity self) ServerPlayNetworking.send(self, payload);
	}

	public static void onStartTracking(Entity e, ServerPlayerEntity player) {
		State st = STATES.get(e.getId());
		if (st != null) ServerPlayNetworking.send(player, new Payloads.CutSync(e.getId(), flat(st)));
	}

	public static void tick(MinecraftServer server) {
		Iterator<Map.Entry<Integer, State>> it = STATES.entrySet().iterator();
		List<LivingEntity> rejoin = new ArrayList<>();
		while (it.hasNext()) {
			State st = it.next().getValue();
			Entity e = st.world.getEntityById(st.entityId);
			if (!(e instanceof LivingEntity le) || !le.isAlive()) {
				it.remove();
				continue;
			}
			if (st.noReattach > 0) st.noReattach--;
			if (--st.ticks <= 0) rejoin.add(le);
			else if (le.age % 10 == 0 && st.noReattach > 0) {
				Box b = le.getBoundingBox();
				st.world.spawnParticles(ParticleTypes.ELECTRIC_SPARK, b.getCenter().x, b.getCenter().y, b.getCenter().z, 3, b.getLengthX() / 2, b.getLengthY() / 2, b.getLengthZ() / 2, 0.05);
			}
		}
		for (LivingEntity le : rejoin) reattach(le, true);
	}

	public static void clearAll() {
		STATES.clear();
	}
}
