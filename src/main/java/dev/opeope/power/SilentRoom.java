package dev.opeope.power;

import dev.opeope.net.Payloads;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.ai.brain.MemoryModuleType;
import net.minecraft.entity.mob.EvokerEntity;
import net.minecraft.entity.mob.IllusionerEntity;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.entity.mob.WardenEntity;
import net.minecraft.registry.RegistryKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.Unit;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;

/**
 * R-ROOM: Silent (サイレント) lanzado sobre otro. Law lanza desde la palma una esfera de ROOM que se le pega al
 * objetivo y lo vuelve insonoro: nada de lo que suene sale de ahí. Así anuló el Soul Pocus de Big Mom, que necesita
 * la voz.
 * <p>
 * En el juego el ser queda en silencio ({@link Entity#setSilent}) {@link #DURATION} ticks: ni pasos, ni gritos, ni
 * quejidos, ni sonido al morir. Los que lanzan hechizos con la voz (Evocador e Ilusionista) no pueden fijar objetivo y
 * el Warden no puede soltar su rugido sónico. Al acabar (o si muere) vuelve a sonar como antes.
 * <p>
 * Si se guarda mudo en disco (se descarga su chunk o se desconecta el jugador), lleva una marca: al volver a cargarse
 * recupera la voz. Al parar el servidor se le devuelve antes de guardar.
 */
public final class SilentRoom {
	private SilentRoom() {}

	/** Lo que dura la burbuja muda pegada al ser: 12 s. */
	public static final int DURATION = 20 * 12;

	/** La marca que se guarda con el ser mientras está mudo por esto (solo si antes no era silencioso). */
	private static final String TAG = "opeope_rroom_silent";

	/** Un ser mudo: en qué dimensión está, cuánto le queda y si ya era silencioso antes (para dejarlo como estaba). */
	private static final class Muted {
		RegistryKey<World> dim;
		int ticksLeft;
		final boolean wasSilent;

		Muted(RegistryKey<World> dim, int ticksLeft, boolean wasSilent) {
			this.dim = dim;
			this.ticksLeft = ticksLeft;
			this.wasSilent = wasSilent;
		}
	}

	private static final Map<UUID, Muted> MUTED = new HashMap<>();
	private static boolean hooked;

	/**
	 * Lanza el R-ROOM Silent sobre t. Devuelve false (y no hace nada) si Silent está en enfriamiento o t no vale.
	 * Otro Silent sobre el mismo ser renueva el tiempo.
	 */
	public static boolean apply(ServerPlayerEntity p, PlayerPower pw, LivingEntity t) {
		if (pw.onCooldown(Tech.SILENT)) return false;
		if (t == null || t == p || !t.isAlive()) return false;
		hook();
		UUID id = t.getUuid();
		Muted prev = MUTED.get(id);
		// renovado: sin olvidar cómo sonaba antes del primero
		boolean wasSilent = prev != null ? prev.wasSilent : t.isSilent();
		MUTED.put(id, new Muted(t.getWorld().getRegistryKey(), DURATION, wasSilent));
		t.setSilent(true);
		if (!wasSilent) t.addCommandTag(TAG);

		ServerWorld w = p.getServerWorld();
		Vec3d c = t.getBoundingBox().getCenter();
		Vec3d hand = LawFx.hand(p);
		// la semilla de R-ROOM sale de la palma hacia el objetivo y, al llegar, la burbuja se le pega y se agranda
		LawFx.send(p, Payloads.LawFx.RROOM_CAST, t, hand, c, 0.6f, 1f, 10);
		float r = (float) Math.max(1.2, t.getWidth() * 1.2 + 0.6);
		LawFx.send(p, Payloads.LawFx.SILENT_TARGET, t, c, c, r, 1f, DURATION);
		Techniques.ring(w, c, r, 24, CutEngine.ROOM_DUST);

		// el lanzamiento y, en el objetivo, el «chist» de un sonido que se apaga
		w.playSound(null, hand.x, hand.y, hand.z, SoundEvents.BLOCK_AMETHYST_BLOCK_CHIME, SoundCategory.PLAYERS, 0.9f, 1.6f);
		w.playSound(null, c.x, c.y, c.z, SoundEvents.BLOCK_SCULK_SENSOR_CLICKING_STOP, SoundCategory.PLAYERS, 1.2f, 0.5f);
		w.playSound(null, c.x, c.y, c.z, SoundEvents.BLOCK_BEACON_DEACTIVATE, SoundCategory.PLAYERS, 0.6f, 1.9f);

		p.sendMessage(Text.literal("§bR-ROOM: Silent §7· §f" + t.getDisplayName().getString() + " §bya no puede hacer ruido"), true);
		pw.cooldown(Tech.SILENT);
		return true;
	}

	/** Una vez por tick del servidor: cuenta el tiempo, apaga los hechizos de voz y devuelve la voz al acabar. */
	public static void tick(MinecraftServer server) {
		hook();
		if (MUTED.isEmpty()) return;
		Iterator<Map.Entry<UUID, Muted>> it = MUTED.entrySet().iterator();
		while (it.hasNext()) {
			Map.Entry<UUID, Muted> en = it.next();
			Muted m = en.getValue();
			ServerWorld w = server.getWorld(m.dim);
			Entity e = w != null ? w.getEntity(en.getKey()) : null;
			if (e == null) {
				// se descargó, se desconectó o ya no está: si vuelve a cargarse, la marca le devuelve la voz
				it.remove();
				continue;
			}
			if (!e.isAlive() || --m.ticksLeft <= 0) {
				restore(e, m.wasSilent);
				it.remove();
				continue;
			}
			if (e instanceof MobEntity mob && mutesSpells(mob)) {
				// sin voz no hay conjuro: el Evocador y el Ilusionista no pueden fijar a nadie
				mob.setTarget(null);
			} else if (e instanceof WardenEntity warden) {
				// el rugido sónico es sonido: no puede soltarlo mientras está dentro
				warden.getBrain().remember(MemoryModuleType.SONIC_BOOM_COOLDOWN, Unit.INSTANCE, 40L);
			}
		}
	}

	/** Si ese ser está ahora dentro de un R-ROOM Silent. */
	public static boolean isMuted(Entity e) {
		return e != null && !e.isRemoved() && MUTED.containsKey(e.getUuid());
	}

	/**
	 * Si es un mob que conjura con la voz (Evocador, Ilusionista) y está mudo: no debería poder fijar objetivo.
	 * (Un mixin de {@code MobEntity.setTarget} puede usarlo para que ni sus TargetGoal se lo vuelvan a poner.)
	 */
	public static boolean mutesSpells(MobEntity mob) {
		return (mob instanceof EvokerEntity || mob instanceof IllusionerEntity) && isMuted(mob);
	}

	private static void restore(Entity e, boolean wasSilent) {
		e.setSilent(wasSilent);
		e.removeCommandTag(TAG);
	}

	/** Los avisos que necesita (una vez por partida del juego): carga de seres y parada del servidor. */
	private static void hook() {
		if (hooked) return;
		hooked = true;
		ServerEntityEvents.ENTITY_LOAD.register((entity, world) -> {
			Muted m = MUTED.get(entity.getUuid());
			if (m != null) {
				// se recargó su chunk o cambió de dimensión mientras dura: se le sigue buscando donde esté
				m.dim = world.getRegistryKey();
			} else if (entity.getCommandTags().contains(TAG)) {
				// se guardó mudo y su Silent ya no corre: vuelve a sonar
				restore(entity, false);
			}
		});
		ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
			// antes del último guardado: que nadie se quede mudo en el disco
			for (Map.Entry<UUID, Muted> en : MUTED.entrySet()) {
				ServerWorld w = server.getWorld(en.getValue().dim);
				Entity e = w != null ? w.getEntity(en.getKey()) : null;
				if (e != null) restore(e, en.getValue().wasSilent);
			}
			MUTED.clear();
		});
	}
}
