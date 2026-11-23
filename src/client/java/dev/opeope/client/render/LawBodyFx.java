package dev.opeope.client.render;

import dev.opeope.client.ClientState;
import dev.opeope.client.config.OpeSettings;
import dev.opeope.client.dismember.ClientDismember;
import dev.opeope.client.room.RoomFxClient;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.entity.LivingEntity;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.RotationAxis;

import java.util.ArrayList;

/**
 * Cuerpos que reaccionan a las técnicas de Law como en el anime: convulsiones con la descarga del Counter Shock y del
 * Radio Knife, un cuerpo que se hincha un instante cuando el Shock Wille o el Gamma Knife revientan dentro, y un cuerpo
 * que se dobla hacia atrás cuando el Injection Shot lo atraviesa.
 * <p>
 * Solo cambia la matriz con la que se dibuja el ser, al final de {@code LivingEntityRenderer.setupTransforms}: ahí el
 * origen está en los pies, +Y va hacia arriba (en bloques, ya multiplicado por la escala del ser) y todo está girado con
 * el cuerpo, así que +X local es la derecha del ser y +Z local su espalda (girar en POSITIVE_X con ángulo negativo lo
 * inclina hacia delante, como en {@link PlayerAnimClient#applyBodyTransforms}). No toca la entidad ni su caja.
 * <p>
 * Varias reacciones se suman en el mismo cuerpo. Con «menos movimiento» no hay temblores y la hinchazón y el retroceso
 * se quedan al 30 %. El tiempo es el de {@link RoomFxClient#time()} (se para en pausa).
 */
public final class LawBodyFx {
	private LawBodyFx() {}

	private static final int SHAKE = 0;
	private static final int BULGE = 1;
	private static final int RECOIL = 2;

	/** Como mucho tantos cuerpos reaccionando a la vez y tantas reacciones en cada uno. */
	private static final int MAX_BODIES = 64;
	private static final int MAX_PER_BODY = 8;
	private static final float MAX_STRENGTH = 2f;
	private static final double MIN_SECONDS = 0.05;
	private static final double MAX_SECONDS = 10.0;

	/** Convulsiones: hasta 6 cm de sacudida por unidad de fuerza y 6° de cabeceo y alabeo; la suma no pasa de aquí. */
	private static final float SHAKE_MOVE = 0.06f;
	private static final float SHAKE_MOVE_MAX = 0.10f;
	private static final float SHAKE_HOP_MAX = 0.04f;
	private static final float SHAKE_DEG = 6f;
	/** Hinchazón: +18 % de ancho por unidad de fuerza en ~0,08 s; lo alto crece menos que lo ancho. */
	private static final float BULGE_AMOUNT = 0.18f;
	private static final double BULGE_RISE = 0.08;
	private static final double BULGE_WOBBLE_HZ = 6.0;
	private static final float BULGE_Y = 0.45f;
	/** Retroceso: hasta 28° por unidad de fuerza, sobre los pies; llega en el primer 30 % del tiempo. */
	private static final float RECOIL_DEG = 28f;
	private static final float RECOIL_MAX_DEG = 40f;
	private static final double RECOIL_HIT = 0.3;
	/** Con «menos movimiento», la hinchazón y el retroceso se quedan en esto. */
	private static final float CALM = 0.3f;

	/** Una reacción en curso; (dx, dz) es hacia dónde empuja el golpe en el mundo (solo la usa el retroceso). */
	private record Fx(int kind, float strength, double start, double end, float dx, float dz, int seed) {}

	/** Las reacciones de un cuerpo; end = cuándo acaba la última. */
	private static final class Body {
		final ArrayList<Fx> list = new ArrayList<>(4);
		double end;
	}

	/** Por id de entidad. Solo se toca desde el hilo del cliente (paquetes y dibujo). */
	private static final Int2ObjectOpenHashMap<Body> BODIES = new Int2ObjectOpenHashMap<>();
	private static int serial;

	// ------------------------------------------------------------------ lo que piden los efectos

	/**
	 * Convulsiones de la descarga (Counter Shock, Radio Knife): un temblor rápido, a espasmos, que se apaga hasta 0 al
	 * final. Fuerza 1 = hasta 6 cm de sacudida y 6° de cabeceo y alabeo. Con «menos movimiento» no hace nada.
	 */
	static void shake(int entityId, float strength, double seconds) {
		if (OpeSettings.reducedMotion()) return;
		add(entityId, SHAKE, strength, seconds, 0f, 0f);
	}

	/**
	 * El cuerpo se hincha desde dentro (Shock Wille, Gamma Knife): en ~0,08 s crece hasta 1 + 0,18 × fuerza de ancho
	 * (de alto, menos) alrededor de su centro y vuelve a su tamaño temblando como gelatina.
	 */
	static void bulge(int entityId, float strength, double seconds) {
		add(entityId, BULGE, strength, seconds, 0f, 0f);
	}

	/**
	 * El cuerpo se dobla sobre los pies (Injection Shot): (dirX, dirZ) es hacia dónde empuja el golpe en el mundo, de
	 * quien golpea hacia el objetivo, y la cabeza se va hacia ahí (si le dan de frente se dobla hacia atrás; de lado, se
	 * ladea). Hasta 28° × fuerza, con rebote al llegar, y luego vuelve. No hace falta normalizar la dirección.
	 */
	static void recoil(int entityId, double dirX, double dirZ, float strength, double seconds) {
		double l = Math.sqrt(dirX * dirX + dirZ * dirZ);
		if (!(l > 1e-6)) return;
		add(entityId, RECOIL, strength, seconds, (float) (dirX / l), (float) (dirZ / l));
	}

	/** Olvida todas las reacciones (al cambiar de mundo). */
	static void clear() {
		BODIES.clear();
	}

	private static void add(int id, int kind, float strength, double seconds, float dx, float dz) {
		if (id < 0 || !(strength > 0f) || !(seconds > 0.0)) return;
		float s = Math.min(strength, MAX_STRENGTH);
		double dur = Math.min(MAX_SECONDS, Math.max(MIN_SECONDS, seconds));
		double now = RoomFxClient.time();
		prune(now);
		Body b = BODIES.get(id);
		if (b == null) {
			if (BODIES.size() >= MAX_BODIES) evictSoonest();
			b = new Body();
			BODIES.put(id, b);
		} else if (b.list.size() >= MAX_PER_BODY) {
			// deja sitio la que antes iba a acabar
			int k = 0;
			for (int i = 1; i < b.list.size(); i++) {
				if (b.list.get(i).end() < b.list.get(k).end()) k = i;
			}
			b.list.remove(k);
		}
		serial++;
		int seed = id * 0x2C1B3C6D + serial * 0x297A2D39;
		b.list.add(new Fx(kind, s, now, now + dur, dx, dz, seed));
		b.end = Math.max(b.end, now + dur);
	}

	/** Quita los cuerpos cuyas reacciones ya acabaron aunque no se hayan vuelto a dibujar (muertos, lejos, fuera de vista). */
	private static void prune(double now) {
		if (BODIES.isEmpty()) return;
		for (int id : BODIES.keySet().toIntArray()) {
			Body b = BODIES.get(id);
			if (b == null || now >= b.end) BODIES.remove(id);
		}
	}

	/** Lleno: se va el cuerpo cuyas reacciones antes iban a acabar. */
	private static void evictSoonest() {
		int victim = 0;
		double soonest = 0.0;
		boolean found = false;
		for (int id : BODIES.keySet().toIntArray()) {
			Body b = BODIES.get(id);
			double end = b == null ? -1.0 : b.end;
			if (!found || end < soonest) {
				victim = id;
				soonest = end;
				found = true;
			}
		}
		if (found) BODIES.remove(victim);
	}

	// ------------------------------------------------------------------ al dibujar

	/**
	 * Llamado para CADA ser vivo que se dibuja, al final de {@code LivingEntityRenderer.setupTransforms} (desde
	 * LivingEntityRendererMixin). Sin reacciones en curso solo mira el mapa y vuelve.
	 */
	public static void apply(LivingEntity e, MatrixStack matrices, float tickDelta) {
		if (BODIES.isEmpty()) return;
		int id = e.getId();
		Body b = BODIES.get(id);
		if (b == null) return;
		// cortado por K-ROOM o con miembros fuera: sus tapas y sus costuras se dibujan sin esta matriz y se saldrían del
		// cuerpo (el muñón quedaría flotando donde estaba el hombro)
		if (ClientState.CUTS.containsKey(id) || ClientDismember.STATE.containsKey(id)) return;
		double now = RoomFxClient.time();
		boolean calm = OpeSettings.reducedMotion();
		float amp = calm ? CALM : 1f;
		float tx = 0f, ty = 0f, tz = 0f, pitch = 0f, roll = 0f;   // convulsiones
		float swell = 0f;                                         // hinchazón
		float bendX = 0f, bendZ = 0f;                             // retroceso: grados hacia donde se va la cabeza (local)
		boolean haveYaw = false;
		double cos = 0.0, sin = 0.0;
		ArrayList<Fx> list = b.list;
		for (int i = list.size() - 1; i >= 0; i--) {
			Fx f = list.get(i);
			if (now >= f.end()) {
				list.remove(i);
				continue;
			}
			double age = Math.max(0.0, now - f.start());
			double dur = f.end() - f.start();
			double u = age / dur;
			if (f.kind() == SHAKE) {
				if (calm) continue;
				// se apaga hasta 0 al final, a espasmos (unos 8 por segundo) sobre el zumbido rápido de la corriente
				float env = (float) ((1.0 - u) * (1.0 - u)) * (0.55f + 0.45f * (float) Math.abs(Math.sin(age * 25.0)));
				float move = SHAKE_MOVE * f.strength() * env;
				float turn = SHAKE_DEG * Math.min(1f, f.strength()) * env;
				int sd = f.seed();
				tx += move * jitter(now, sd);
				tz += move * jitter(now, sd + 1);
				ty += move * 0.35f * Math.abs(jitter(now, sd + 2));
				pitch += turn * jitter(now, sd + 3);
				roll += turn * jitter(now, sd + 4);
			} else if (f.kind() == BULGE) {
				swell += BULGE_AMOUNT * f.strength() * amp * bulgeCurve(age, dur);
			} else {
				if (!haveYaw) {
					double yaw = Math.toRadians(MathHelper.lerpAngleDegrees(tickDelta, e.prevBodyYaw, e.bodyYaw));
					cos = Math.cos(yaw);
					sin = Math.sin(yaw);
					haveYaw = true;
				}
				// del mundo al cuerpo (girado 180° − yaw): la derecha del ser es (−cos, −sin) y su espalda (sin, −cos)
				float deg = RECOIL_DEG * f.strength() * amp * recoilCurve(u);
				bendX += deg * (float) (-f.dx() * cos - f.dz() * sin);
				bendZ += deg * (float) (f.dx() * sin - f.dz() * cos);
			}
		}
		if (list.isEmpty()) {
			BODIES.remove(id);
			return;
		}
		// el centro del cuerpo en las unidades de la matriz (que ya va multiplicada por la escala del ser)
		float mid = (float) (e.getHeight() * 0.5 / Math.max(0.0625f, e.getScale()));

		// 1) el retroceso: todo el cuerpo gira sobre los pies hacia donde empuja el golpe (se apaga si cae muerto)
		if (e.deathTime > 0) {
			float fade = Math.max(0f, 1f - (e.deathTime + tickDelta) / 10f);
			bendX *= fade;
			bendZ *= fade;
		}
		float bend = (float) Math.sqrt(bendX * bendX + bendZ * bendZ);
		if (bend > 0.05f) {
			// gira para que +Z local mire hacia el empujón, inclina ahí (POSITIVE_X positivo = cabeza hacia +Z) y deshace el giro
			float heading = (float) Math.atan2(bendX, bendZ);
			matrices.multiply(RotationAxis.POSITIVE_Y.rotation(heading));
			matrices.multiply(RotationAxis.POSITIVE_X.rotationDegrees(Math.min(bend, RECOIL_MAX_DEG)));
			matrices.multiply(RotationAxis.POSITIVE_Y.rotation(-heading));
		}

		// 2) las convulsiones: sacudida en el sitio, y cabeceo y alabeo alrededor del centro del cuerpo
		if (!calm && (tx != 0f || ty != 0f || tz != 0f || pitch != 0f || roll != 0f)) {
			matrices.translate(
				MathHelper.clamp(tx, -SHAKE_MOVE_MAX, SHAKE_MOVE_MAX),
				MathHelper.clamp(ty, 0f, SHAKE_HOP_MAX) + mid,
				MathHelper.clamp(tz, -SHAKE_MOVE_MAX, SHAKE_MOVE_MAX));
			matrices.multiply(RotationAxis.POSITIVE_X.rotationDegrees(MathHelper.clamp(pitch, -SHAKE_DEG, SHAKE_DEG)));
			matrices.multiply(RotationAxis.POSITIVE_Z.rotationDegrees(MathHelper.clamp(roll, -SHAKE_DEG, SHAKE_DEG)));
			matrices.translate(0f, -mid, 0f);
		}

		// 3) la hinchazón: escala alrededor del centro del cuerpo; lo ancho crece más que lo alto
		if (swell > 1e-4f || swell < -1e-4f) {
			float k = MathHelper.clamp(swell, -0.25f, 0.45f);
			matrices.translate(0f, mid, 0f);
			matrices.scale(1f + k, 1f + BULGE_Y * k, 1f + k);
			matrices.translate(0f, -mid, 0f);
		}
	}

	// ------------------------------------------------------------------ curvas y ruido

	/** La hinchazón: sube en ~0,08 s hasta 1 y vuelve temblando (6 Hz, amortiguado) hasta 0 justo al final. */
	private static float bulgeCurve(double age, double dur) {
		double rise = Math.min(BULGE_RISE, dur * 0.3);
		if (age < rise) return (float) Math.sin(age / rise * (Math.PI * 0.5));
		double tail = dur - rise;
		if (tail <= 1e-6) return 0f;
		double tau = age - rise;
		double x = Math.min(1.0, tau / tail);
		return (float) (Math.cos(tau * (Math.PI * 2.0 * BULGE_WOBBLE_HZ)) * Math.exp(-3.0 * x) * (1.0 - x));
	}

	/** El retroceso (u = 0..1): llega con rebote (ease-out-back) en el primer 30 % del tiempo y vuelve suave hasta 0. */
	private static float recoilCurve(double u) {
		if (u <= 0.0 || u >= 1.0) return 0f;
		if (u < RECOIL_HIT) {
			double x = u / RECOIL_HIT - 1.0;
			return (float) (1.0 + 2.70158 * x * x * x + 1.70158 * x * x);
		}
		double x = (u - RECOIL_HIT) / (1.0 - RECOIL_HIT);
		return (float) (1.0 - x * x * (3.0 - 2.0 * x));
	}

	/** Temblor en [-1, 1]: un zumbido rápido (34 Hz) y un espasmo más lento (11 Hz), por hash del tiempo, sin Random. */
	private static float jitter(double t, int seed) {
		return 0.7f * noise(t * 34.0, seed) + 0.3f * noise(t * 11.0, seed ^ 0x5BD1E995);
	}

	/** Ruido de valores: un número fijo por cada entero de x (hash de x y la semilla), interpolado con suavidad. */
	private static float noise(double x, int seed) {
		double fl = Math.floor(x);
		long i = (long) fl;
		double fr = x - fl;
		float a = hash(i, seed);
		float b = hash(i + 1L, seed);
		float s = (float) (fr * fr * (3.0 - 2.0 * fr));
		return a + (b - a) * s;
	}

	/** Un número fijo en [-1, 1) para (i, semilla), mezclado como splitmix64. */
	private static float hash(long i, int seed) {
		long z = i * 0x9E3779B97F4A7C15L + seed * 0xD1B54A32D192ED03L;
		z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
		z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
		z ^= z >>> 31;
		return (float) ((z >>> 40) / 16777216.0) * 2f - 1f;
	}
}
