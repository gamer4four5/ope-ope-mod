package dev.opeope.client.room;

import dev.opeope.OpeOpeMod;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.sound.PositionedSoundInstance;
import net.minecraft.client.sound.SoundInstance;
import net.minecraft.client.sound.SoundManager;
import net.minecraft.sound.SoundCategory;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import org.jetbrains.annotations.Nullable;

import java.util.function.Supplier;

/**
 * Los sonidos del ROOM (spec §5.9 y §5.10). Todos son alias de sonidos de Minecraft en {@code sounds.json}: no se
 * registra ningún SoundEvent. Los del dueño suenan «en la cabeza» (sin posición); los de otros jugadores, en el punto
 * del mundo y un poco más bajos. Varios momentos son capas: un golpe, un tono grave y unas campanitas escalonadas.
 */
public final class RoomSounds {
	private RoomSounds() {}

	/** Los 33 sonidos del mod (sin el espacio de nombres). */
	public static final String[] IDS = {
		"room.seed", "room.seed_charge", "room.glint", "room.bloom", "room.bloom_whoom", "room.bloom_wind", "room.bloom_low",
		"room.grow", "room.grow_tone", "room.lock", "room.bell", "room.lock_low", "room.notch", "room.click",
		"room.stretch_up", "room.stretch_down", "room.limit", "room.limit_ring", "room.throw", "room.fly", "room.land",
		"room.land_glass", "room.land_crystal", "room.recall", "room.arrive", "room.close", "room.close_deplete",
		"room.close_pop", "room.enter", "room.exit", "room.hum", "ui.paso", "ui.denegado"
	};

	/** Los demás oyen lo de otro jugador algo más bajo. */
	private static final float OTHERS = 0.7f;

	private static final float[] BLOOM_GLINTS = {0.794f, 0.891f, 1.0f, 1.189f, 1.335f, 1.587f, 1.782f};
	private static final int[] BLOOM_DELAYS = {3, 6, 10, 15, 21, 27, 34};

	// bucles
	@Nullable private static LoopSound grow, growTone, hum, fly;
	private static int growRetry, toneRetry, humRetry;

	// muescas de la rueda
	private static long lastNotchAt, lastNotchSoundAt;
	private static boolean settlePending;

	// ------------------------------------------------------------------ básicos

	private static SoundManager manager() {
		return MinecraftClient.getInstance().getSoundManager();
	}

	/** Uno del dueño: sin posición, «en la cabeza». */
	static void own(String id, float pitch, float volume, int delayTicks) {
		play(OpeOpeMod.id(id), volume, pitch, delayTicks, true, 0, 0, 0);
	}

	static void own(String id, float pitch, float volume) {
		own(id, pitch, volume, 0);
	}

	/** Uno en un punto del mundo (lo de otros jugadores). */
	static void at(String id, double x, double y, double z, float pitch, float volume, int delayTicks) {
		play(OpeOpeMod.id(id), volume, pitch, delayTicks, false, x, y, z);
	}

	private static void play(Identifier id, float volume, float pitch, int delayTicks, boolean relative, double x, double y, double z) {
		if (volume <= 0.001f) return;
		SoundInstance.AttenuationType att = relative ? SoundInstance.AttenuationType.NONE : SoundInstance.AttenuationType.LINEAR;
		PositionedSoundInstance s = new PositionedSoundInstance(id, SoundCategory.PLAYERS, volume, MathHelper.clamp(pitch, 0.5f, 2f),
			SoundInstance.createRandom(), false, 0, att, x, y, z, relative);
		if (delayTicks > 0) manager().play(s, delayTicks);
		else manager().play(s);
	}

	/** El tono del candado y las campanas: más grave cuanto más grande. clamp(1,08 − 0,12·log2(R/10), 0,78, 1,1). */
	static float rate(float radius) {
		double r = Math.max(0.5, radius);
		return (float) MathHelper.clamp(1.08 - 0.12 * (Math.log(r / 10.0) / Math.log(2.0)), 0.78, 1.1);
	}

	// ------------------------------------------------------------------ momentos

	/** La semilla en la mano (al pulsar el ROOM sin tener uno). */
	static void seed() {
		own("room.seed", 1.0f, 0.7f);
		own("room.seed_charge", 1.4f, 0.4f);
		own("room.glint", 0.595f, 0.3f, 1);
		own("room.glint", 0.891f, 0.3f, 2);
		own("room.glint", 1.189f, 0.3f, 3);
	}

	/** Florece: golpe, soplo, viento, grave y siete campanitas que suben. */
	static void bloom(boolean own, Vec3d at) {
		if (own) {
			own("room.bloom", 1.0f, 0.9f);
			own("room.bloom_whoom", 1.2f, 0.6f);
			own("room.bloom_wind", 1.1f, 0.5f);
			own("room.bloom_low", 0.7f, 0.6f);
			float v = 0.22f;
			for (int i = 0; i < BLOOM_GLINTS.length; i++) {
				own("room.glint", BLOOM_GLINTS[i], v, BLOOM_DELAYS[i]);
				v *= 0.9f;
			}
		} else {
			at("room.bloom", at.x, at.y, at.z, 1.0f, 0.9f * OTHERS, 0);
			at("room.bloom_whoom", at.x, at.y, at.z, 1.2f, 0.6f * OTHERS, 0);
		}
	}

	/** Queda fijo: cerrojo, campana doble y un grave; todo más grave cuanto más grande. */
	static void lock(boolean own, Vec3d at, float radius) {
		float rate = rate(radius);
		if (own) {
			own("room.lock", 1.5f, 0.8f);
			own("room.bell", 1.0f * rate, 0.5f, 0);
			own("room.bell", 1.335f * rate, 0.4f, 2);
			own("room.lock_low", 0.7f * rate, 0.6f);
		} else {
			at("room.lock", at.x, at.y, at.z, 1.5f, 0.8f * OTHERS, 0);
			at("room.bell", at.x, at.y, at.z, 1.0f * rate, 0.5f * OTHERS, 0);
		}
	}

	/** Una muesca de la rueda: tic y campanita, el primero de la ráfaga con un estirón, y al final un asiento grave. */
	static void notch(boolean grow) {
		long now = System.currentTimeMillis();
		boolean first = now - lastNotchAt > 600;
		if (now - lastNotchSoundAt >= 35) {
			lastNotchSoundAt = now;
			own("room.notch", grow ? 1.189f : 0.891f, 0.5f);
			own("room.click", grow ? 1.6f : 1.3f, 0.4f);
		}
		if (first) own(grow ? "room.stretch_up" : "room.stretch_down", 1.2f, 0.35f);
		lastNotchAt = now;
		settlePending = true;
	}

	/** Tope: golpe sordo y un anillo agudo. Centrado (ya está contigo): solo el anillo. */
	static void limit(boolean centred) {
		if (centred) {
			own("room.limit_ring", 1.5f, 0.4f);
		} else {
			own("room.limit", 0.7f, 0.7f);
			own("room.limit_ring", 1.782f, 0.5f);
		}
	}

	/** Lanzado: el tirón y un destello; el silbido sigue al cometa hasta que cae. */
	static void cast(boolean own, Vec3d from, @Nullable Supplier<Vec3d> comet) {
		if (own) {
			own("room.throw", 1.4f, 0.8f);
			own("room.glint", 1.8f, 0.5f);
			stopFly();
			if (comet != null) {
				LoopSound s = new LoopSound(OpeOpeMod.id("room.fly"), false, comet);
				s.set(0.6f, 1.2f);
				fly = s;
				manager().play(s);
			}
		} else {
			at("room.throw", from.x, from.y, from.z, 1.4f, 0.8f * OTHERS, 0);
		}
	}

	/**
	 * Aterriza: golpe de maza, cristal y cuarzo, y un tick después florece. El dueño lo oye más bajo cuanto más lejos
	 * cae (nunca menos de 0,35); los demás, en el punto, con más alcance cuanto más lejos están.
	 */
	static void land(boolean own, Vec3d at, float radius, double dist) {
		float rate = rate(radius);
		if (own) {
			stopFly();
			float v = (float) Math.max(0.35, 1.0 - dist / 200.0);
			own("room.land", 0.8f * rate, 0.9f * v);
			own("room.land_glass", 1.5f, 0.6f * v);
			own("room.land_crystal", 1.2f, 0.6f * v);
			own("room.bloom", 1.1f, 0.8f * v, 1);
		} else {
			float v = (float) Math.min(4.0, Math.max(1.0, dist / 16.0));
			at("room.land", at.x, at.y, at.z, 0.8f * rate, 0.9f * v, 0);
			at("room.land_glass", at.x, at.y, at.z, 1.5f, 0.6f * v, 0);
		}
	}

	/** Vuelve a ti: una inspiración y el estirón hacia abajo. */
	static void recall() {
		own("room.recall", 1.1f, 0.7f);
		own("room.stretch_down", 1.5f, 0.4f);
	}

	/** Llega contigo: ráfaga y campana doble. */
	static void arrive(boolean own, Vec3d at) {
		if (own) {
			own("room.arrive", 0.8f, 0.7f);
			own("room.bell", 1.0f, 0.4f, 0);
			own("room.bell", 1.335f, 0.35f, 2);
		} else {
			at("room.arrive", at.x, at.y, at.z, 0.8f, 0.7f * OTHERS, 0);
		}
	}

	/** Se cierra: se apaga, se vacía, un chasquido de cristal y dos campanitas que bajan. */
	static void close(boolean own, Vec3d at) {
		if (own) {
			stopLoops();
			own("room.close", 1.1f, 0.8f);
			own("room.close_deplete", 1.3f, 0.5f);
			own("room.close_pop", 1.6f, 0.5f, 5);
			own("room.glint", 1.189f, 0.25f, 6);
			own("room.glint", 0.891f, 0.25f, 8);
		} else {
			at("room.close", at.x, at.y, at.z, 1.1f, 0.8f * OTHERS, 0);
		}
	}

	static void enter(boolean inside) {
		if (inside) own("room.enter", 1.5f, 0.4f);
		else own("room.exit", 1.1f, 0.4f);
	}

	// ------------------------------------------------------------------ bucles

	/**
	 * Cada tick. growing: el ROOM propio está creciendo (r el radio, dr lo que creció este tick). humVol: el volumen del
	 * murmullo de dentro (0 = sin murmullo); su tono sale del radio.
	 */
	static void tick(boolean growing, float r, float dr, float humVol, float humRadius) {
		long now = System.currentTimeMillis();
		if (settlePending && now - lastNotchAt >= 320) {
			settlePending = false;
			own("room.lock_low", 1.4f, 0.3f);
		}
		if (growing) {
			double u = MathHelper.clamp(Math.log(Math.max(4.0, r) / 4.0) / Math.log(40.0), 0.0, 1.0);
			float w = Math.min(1f, Math.max(0f, dr) / 3f);
			grow = keep(grow, "room.grow", 0, 0.2f + 0.4f * w, (float) (0.6 + 0.6 * u));
			growTone = keep(growTone, "room.grow_tone", 1, 0.25f, (float) (0.6 + 0.9 * u));
		} else {
			stopGrow();
		}
		if (humVol > 0.001f) {
			hum = keep(hum, "room.hum", 2, humVol, 0.55f * (1f + Math.min(20f, 0.08f * humRadius) / 52f));
		} else if (hum != null) {
			hum.stop();
			hum = null;
		}
		if (fly != null && fly.isDone()) fly = null;
	}

	/**
	 * El bucle sigue sonando con este volumen y tono, o se vuelve a lanzar. Si Minecraft lo paró (cambio de mundo,
	 * volumen a cero, sonido que no carga) se reintenta como mucho cada 2 s.
	 */
	private static LoopSound keep(@Nullable LoopSound s, String id, int slot, float volume, float pitch) {
		SoundManager sm = manager();
		if (s != null && !s.stopping()) {
			if (sm.isPlaying(s)) {
				s.set(volume, pitch);
				return s;
			}
			int retry = slot == 0 ? growRetry : slot == 1 ? toneRetry : humRetry;
			if (retry > 0) {
				setRetry(slot, retry - 1);
				s.set(volume, pitch);
				return s;
			}
		}
		LoopSound n = new LoopSound(OpeOpeMod.id(id), true, null);
		n.set(volume, pitch);
		sm.play(n);
		setRetry(slot, 40);
		return n;
	}

	private static void setRetry(int slot, int v) {
		if (slot == 0) growRetry = v;
		else if (slot == 1) toneRetry = v;
		else humRetry = v;
	}

	static void stopGrow() {
		if (grow != null) grow.stop();
		if (growTone != null) growTone.stop();
		grow = null;
		growTone = null;
	}

	static void stopFly() {
		if (fly != null) fly.stop();
		fly = null;
	}

	/** Para todos los bucles (al cerrar el ROOM o desconectar). */
	static void stopLoops() {
		stopGrow();
		stopFly();
		if (hum != null) hum.stop();
		hum = null;
	}

	static void clear() {
		stopLoops();
		settlePending = false;
		lastNotchAt = 0;
		lastNotchSoundAt = 0;
	}
}
