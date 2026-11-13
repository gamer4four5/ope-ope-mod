package dev.opeope.client.room;

import dev.opeope.OpeOpeMod;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.sound.PositionedSoundInstance;
import net.minecraft.client.sound.Sound;
import net.minecraft.client.sound.SoundInstance;
import net.minecraft.client.sound.SoundManager;
import net.minecraft.client.sound.WeightedSoundSet;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvent;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import org.jetbrains.annotations.Nullable;

/**
 * Los sonidos del corte y de Takt (diseño §6). Como los del ROOM, todos son alias de sonidos de Minecraft en
 * {@code sounds.json}: no se registra ningún SoundEvent. Hay dos maneras de sonar:
 * <ul>
 * <li>«propio»: el dueño lo oye «en la cabeza» (sin posición); los demás, en el punto del mundo y algo más bajo;</li>
 * <li>«en el punto»: todos lo oyen en el punto del mundo, también el dueño (lo que pasa en el suelo, no en la mano).</li>
 * </ul>
 * Lo pesado suena más grave ({@link #massRate}); un grupo grande suena más fuerte ({@link #group}).
 * <p>Un volumen de más de 1 solo alarga el alcance (Minecraft: 16 bloques × volumen): suena con volumen 1 y ese alcance
 * ({@link Ranged}), así el control de volumen «Jugadores» cuenta siempre (Minecraft multiplica y luego corta en 1).
 */
public final class TaktSounds {
	private TaktSounds() {}

	/** Los 28 sonidos del corte y de Takt (sin el espacio de nombres), en el orden de {@code sounds.json}. */
	public static final String[] IDS = {
		"cut.sweep", "cut.ring", "cut.glint", "cut.thump", "cut.split", "takt.reach", "takt.crack", "takt.tear",
		"takt.rumble", "takt.grab", "takt.grab_low", "takt.chord", "takt.shed", "takt.hold", "takt.throw",
		"takt.throw_big", "takt.flyby", "takt.impact", "takt.impact_far", "takt.setdown", "takt.land", "takt.seal",
		"takt.zone", "takt.rewind", "takt.tick", "takt.debris", "takt.rumble_far", "takt.fall"
	};

	/** Los demás oyen lo de otro jugador algo más bajo. */
	static final float OTHERS = 0.7f;
	/** Las notas de los acordes (zona, unir todo, sellos que llegan). */
	static final float[] CHORD = {0.595f, 0.749f, 0.891f, 1.189f};

	/** La rueda del tamaño: un tic cada 35 ms como mucho. */
	private static final long TICK_MIN_MS = 35;
	private static long lastTickAt;

	// ------------------------------------------------------------------ tonos y volúmenes

	/** El tono según el peso: clamp(1,2 − 0,12·log10(max(1, kg)), 0,68, 1,15). Lo pesado suena grave. */
	public static float massRate(double kg) {
		double k = Double.isNaN(kg) ? 1.0 : Math.max(1.0, kg);
		return (float) MathHelper.clamp(1.2 - 0.12 * Math.log10(k), 0.68, 1.15);
	}

	/** El volumen de un grupo de n: min(1,1, 0,6 + 0,1·log2(1 + n)). */
	static float group(int n) {
		return (float) Math.min(1.1, 0.6 + 0.1 * (Math.log(1.0 + Math.max(0, n)) / Math.log(2.0)));
	}

	// ------------------------------------------------------------------ básicos (copias de RoomSounds)

	/** Uno del dueño: sin posición, «en la cabeza». */
	static void own(String id, float pitch, float volume, int delayTicks) {
		play(OpeOpeMod.id(id), volume, pitch, delayTicks, true, 0, 0, 0);
	}

	/** Uno en un punto del mundo. */
	static void at(String id, double x, double y, double z, float pitch, float volume, int delayTicks) {
		play(OpeOpeMod.id(id), volume, pitch, delayTicks, false, x, y, z);
	}

	/** Un sonido de Minecraft tal cual, en un punto del mundo. */
	static void vanillaAt(@Nullable SoundEvent ev, double x, double y, double z, float pitch, float volume, int delayTicks) {
		if (ev == null || !(volume > 0.001f)) return;
		if (volume > 1f) {
			send(new Ranged(ev.getId(), 1f, MathHelper.clamp(pitch, 0.5f, 2f), x, y, z, 16 * volume), delayTicks);
			return;
		}
		// el constructor público de 8 argumentos (el de 11 con SoundEvent es privado en 1.21.1); equivale a
		// repeat = false, repeatDelay = 0, atenuación LINEAR
		PositionedSoundInstance s = new PositionedSoundInstance(ev, SoundCategory.PLAYERS, volume, MathHelper.clamp(pitch, 0.5f, 2f),
			SoundInstance.createRandom(), x, y, z);
		send(s, delayTicks);
	}

	/** «Propio»: sin posición para el dueño; en el punto y más bajo (× OTHERS) para los demás. */
	static void ownOr(boolean own, String id, Vec3d at, float pitch, float volume, int delayTicks) {
		if (own) own(id, pitch, volume, delayTicks);
		else at(id, at.x, at.y, at.z, pitch, volume * OTHERS, delayTicks);
	}

	/** «En el punto»: con posición para todos, también para el dueño. */
	static void point(String id, Vec3d at, float pitch, float volume, int delayTicks) {
		at(id, at.x, at.y, at.z, pitch, volume, delayTicks);
	}

	private static void play(Identifier id, float volume, float pitch, int delayTicks, boolean relative, double x, double y, double z) {
		if (!(volume > 0.001f)) return;
		float p = MathHelper.clamp(pitch, 0.5f, 2f);
		if (!relative && volume > 1f) {
			send(new Ranged(id, 1f, p, x, y, z, 16 * volume), delayTicks);
			return;
		}
		// «en la cabeza» no hay alcance: más de 1 sonaría igual con el volumen al máximo y más fuerte que el control
		SoundInstance.AttenuationType att = relative ? SoundInstance.AttenuationType.NONE : SoundInstance.AttenuationType.LINEAR;
		PositionedSoundInstance s = new PositionedSoundInstance(id, SoundCategory.PLAYERS, Math.min(1f, volume), p,
			SoundInstance.createRandom(), false, 0, att, x, y, z, relative);
		send(s, delayTicks);
	}

	/**
	 * Un sonido en un punto que se oye hasta range bloques (el de sounds.json llega a 16): al elegir su variante, se queda
	 * con una copia de ella con ese alcance. El volumen (como mucho 1) es el de cerca.
	 */
	static final class Ranged extends PositionedSoundInstance {
		private final int range;

		Ranged(Identifier id, float volume, float pitch, double x, double y, double z, double range) {
			super(id, SoundCategory.PLAYERS, Math.min(1f, volume), pitch, SoundInstance.createRandom(), false, 0,
				SoundInstance.AttenuationType.LINEAR, x, y, z, false);
			this.range = (int) Math.ceil(MathHelper.clamp(range, 1, 2048));
		}

		@Override
		public WeightedSoundSet getSoundSet(SoundManager manager) {
			WeightedSoundSet set = super.getSoundSet(manager);
			this.sound = withRange(this.sound, range);
			return set;
		}
	}

	/** Una copia de s que se oye hasta range bloques (la misma variante, volumen y tono); s si no es un sonido de verdad. */
	static Sound withRange(@Nullable Sound s, int range) {
		if (s == null || s == SoundManager.MISSING_SOUND || s == SoundManager.INTENTIONALLY_EMPTY_SOUND || s.getAttenuation() == range) return s;
		return new Sound(s.getIdentifier(), s.getVolume(), s.getPitch(), s.getWeight(), s.getRegistrationType(), s.isStreamed(),
			s.isPreloaded(), range);
	}

	private static void send(SoundInstance s, int delayTicks) {
		SoundManager m = MinecraftClient.getInstance().getSoundManager();
		if (m == null) return;
		if (delayTicks > 0) m.play(s, delayTicks);
		else m.play(s);
	}

	// ------------------------------------------------------------------ momentos de la entrada

	/** R + rueda sin nada en Takt: el tamaño del trozo cambia. Un tic más agudo cuanto más grande (cada 35 ms como mucho). */
	public static void chunkSizeTick(double size) {
		long now = System.currentTimeMillis();
		if (now - lastTickAt < TICK_MIN_MS) return;
		lastTickAt = now;
		own("takt.tick", (float) (1 + 0.04 * (size - 3)), 0.35f, 0);
	}

	/** R + V enviado: los trozos empiezan a bajar a su silueta. */
	public static void setDownStart() {
		own("takt.setdown", 1.0f, 0.5f, 0);
	}
}
