package dev.opeope.client.room;

import net.minecraft.client.sound.AbstractSoundInstance;
import net.minecraft.client.sound.SoundInstance;
import net.minecraft.client.sound.SoundManager;
import net.minecraft.client.sound.TickableSoundInstance;
import net.minecraft.client.sound.WeightedSoundSet;
import net.minecraft.sound.SoundCategory;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import org.jetbrains.annotations.Nullable;

import java.util.function.Supplier;

/**
 * Un sonido largo del ROOM (el zumbido mientras crece, el tono, el murmullo de dentro, el silbido del cometa). Su dueño
 * le pone volumen y tono cada tick; al pararlo se apaga en 150 ms en vez de cortarse en seco.
 * <p>Sin posición suena «en la cabeza» (relativo, sin atenuar); con posición sigue ese punto cada tick y se oye hasta
 * 16 bloques o hasta el alcance que se le dé con {@link #range} (sin subir el volumen: el primer tick no da un golpe).
 */
public final class LoopSound extends AbstractSoundInstance implements TickableSoundInstance {
	private static final long FADE_MS = 150;

	@Nullable
	private final Supplier<Vec3d> pos;
	private boolean done;
	private long stopAt;
	private float fadeFrom;
	private float wantVolume, wantPitch = 1f;
	private int range;   // 0: el de sounds.json (16 bloques)

	public LoopSound(Identifier id, boolean repeat, @Nullable Supplier<Vec3d> pos) {
		super(id, SoundCategory.PLAYERS, SoundInstance.createRandom());
		this.pos = pos;
		this.repeat = repeat;
		this.repeatDelay = 0;
		if (pos == null) {
			this.relative = true;
			this.attenuationType = SoundInstance.AttenuationType.NONE;
			this.x = 0;
			this.y = 0;
			this.z = 0;
		} else {
			this.relative = false;
			this.attenuationType = SoundInstance.AttenuationType.LINEAR;
			Vec3d p = pos.get();
			if (p != null) {
				this.x = p.x;
				this.y = p.y;
				this.z = p.z;
			}
		}
		this.volume = 0f;
		this.pitch = 1f;
	}

	/** Que se oiga hasta blocks bloques (con posición). Antes de tocarlo: el alcance se fija al empezar. */
	public LoopSound range(double blocks) {
		this.range = (int) Math.ceil(MathHelper.clamp(blocks, 1, 2048));
		return this;
	}

	@Override
	public WeightedSoundSet getSoundSet(SoundManager manager) {
		WeightedSoundSet set = super.getSoundSet(manager);
		if (range > 0 && pos != null) this.sound = TaktSounds.withRange(this.sound, range);
		return set;
	}

	/** Lo que debe sonar ahora (lo aplica el siguiente tick del sistema de sonido). Tono siempre entre 0,5 y 2. */
	public void set(float volume, float pitch) {
		this.wantVolume = Math.max(0f, volume);
		this.wantPitch = MathHelper.clamp(pitch, 0.5f, 2f);
		if (stopAt == 0) {
			this.volume = this.wantVolume;
			this.pitch = this.wantPitch;
		}
	}

	/** Se apaga en 150 ms y luego avisa de que ha terminado. */
	public void stop() {
		if (stopAt == 0 && !done) {
			stopAt = System.currentTimeMillis();
			fadeFrom = volume;
		}
	}

	public boolean stopping() {
		return stopAt != 0 || done;
	}

	@Override
	public void tick() {
		if (pos != null) {
			Vec3d p = pos.get();
			if (p != null) {
				this.x = p.x;
				this.y = p.y;
				this.z = p.z;
			}
		}
		if (stopAt != 0) {
			long t = System.currentTimeMillis() - stopAt;
			if (t >= FADE_MS) {
				this.volume = 0f;
				this.done = true;
			} else {
				this.volume = fadeFrom * (1f - t / (float) FADE_MS);
			}
		} else {
			this.volume = wantVolume;
			this.pitch = wantPitch;
		}
	}

	@Override
	public boolean isDone() {
		return done;
	}

	@Override
	public boolean shouldAlwaysPlay() {
		return true;
	}
}
