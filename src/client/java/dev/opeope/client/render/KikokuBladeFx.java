package dev.opeope.client.render;

import dev.opeope.client.ClientState;
import dev.opeope.client.config.OpeSettings;
import dev.opeope.client.room.RoomFxClient;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.BufferBuilder;
import net.minecraft.entity.Entity;
import net.minecraft.particle.DustParticleEffect;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import org.joml.Vector3f;

import java.util.HashMap;
import java.util.Map;

/**
 * La Kikoku revestida de K-ROOM (クローム): la nodachi de Law cubierta por una membrana celeste translúcida de ROOM y
 * alargada hasta una hoja espectral gigante, la imagen más icónica de su Despertar (Anesthesia, Shock Wille, Puncture
 * Wille).
 * <p>
 * La silueta es la de una hoja japonesa larga: fina junto a la mano, se ensancha en el primer metro y medio, sigue con
 * una curvatura suave (sori, con el filo en el lado convexo) y acaba en una punta curva (kissaki) separada por la yokote.
 * Por capas, todas mirando a la cámara: el halo tenue de la membrana, el cuerpo translúcido (más luz en los bordes, como
 * el ROOM, del que solo se ve el borde), el filo blanco, el lomo más suave y el núcleo incandescente; bandas de membrana
 * que corren de la tsuba a la punta, la tsuba en cruz con su anillo, la bolita de K-ROOM en la mano y el destello de la
 * punta. Cerca de la cámara (primera persona) la hoja se estrecha y se desvanece para no tapar la pantalla.
 * <p>
 * Todo va dentro del BufferBuilder ADITIVO que ya abrió {@code LawFxClient.renderWorld}: aquí no se abren ni se cierran
 * buffers ni se toca RenderSystem; solo se usan los ayudantes de geometría de {@link LawFxClient}.
 * Con «menos movimiento» no hay bandas que corran, ni parpadeo del filo, ni destello que titile.
 */
public final class KikokuBladeFx {
	private KikokuBladeFx() {}

	/** Largo de la hoja revestida, en bloques (el mismo alcance que el de los anillos de K-ROOM). */
	static final double LEN = 26.0;
	/** Segundos que tarda en crecer (0 → 1, frenando al final) y en recogerse (1 → 0). */
	static final double GROW = 0.25;
	static final double RETRACT = 0.2;
	/** Dónde empieza la punta (kissaki): el último 6 % de la hoja. */
	static final double KISSAKI = 0.94;
	/** Cuánto se va la punta hacia el lomo (en medios anchos): el filo sube a buscarla, como en una katana. */
	static final double TIP_SHIFT = 0.35;
	/** Medio ancho de la hoja junto a la tsuba. */
	static final double W_HILT = 0.05;
	/** El cuello fino (la Kikoku de verdad) antes de que se hinche la membrana, en fracción del tramo que se ensancha. */
	static final double NECK = 0.2;

	// dónde se muestrea la hoja: la base que se ensancha, el tramo largo y la punta
	private static final double[] RAMP_STEPS = {NECK, 0.42, 0.62, 0.8, 1.0};
	private static final int MID = 10;
	private static final double[] TIP_STEPS = {0.3, 0.55, 0.75, 0.9, 1.0};
	/** La muestra de la yokote (la raya que separa la punta del resto de la hoja). */
	private static final int KI = RAMP_STEPS.length + MID;
	private static final int BANDS = 4;
	private static final int MAX = 32;
	/** Ranuras 26, 27 y 28 de las muestras: las bandas que corren por la hoja. */
	private static final int BAND = 26;

	// la hoja propia (la manda ClientState.kroomBlade()): cuándo se encendió y cuándo se apagó
	private static boolean ownOn;
	private static double ownStart = -100, ownEnd = -100;
	// las hojas de otros jugadores: id → {cuándo empezó a crecer, hasta cuándo dura}
	private static final Map<Integer, double[]> NOTED = new HashMap<>();
	/** Cuánta luz de la hoja de cada uno se llevan ahora sus técnicas (Anesthesia, Shock Wille, Puncture Wille). */
	private static final Map<Integer, Float> IN_USE = new HashMap<>();

	/**
	 * Vista desde fuera (tercera persona, o la de otro), la hoja va en guardia: alzada GUARD_RAISE sobre la mirada y
	 * abierta GUARD_OPEN hacia su derecha, en diagonal sobre el hombro. Apuntando justo a donde mira se vería de punta
	 * (un palito desde detrás y un fogonazo blanco desde delante) y, desde detrás, la taparía lo que tiene delante.
	 * En primera persona sí apunta a la mira: así se ve hasta dónde llega.
	 */
	static final double GUARD_RAISE = Math.toRadians(30.0);
	static final double GUARD_OPEN = Math.toRadians(18.0);
	static final double GUARD_MAX = Math.toRadians(72.0);

	/** El polvo de ROOM que va soltando la hoja (el color del K-ROOM), cada 4 ticks, a lo largo de la hoja que se ve. */
	private static final DustParticleEffect DUST = new DustParticleEffect(new Vector3f(0.65f, 0.96f, 1.0f), 1.4f);
	private static long dustSlot = Long.MIN_VALUE;

	// la hoja que se está dibujando ahora (el render va en un solo hilo)
	private static double hx, hy, hz;          // la tsuba
	private static double dx, dy, dz;          // hacia la punta (unitaria)
	private static double bx, by, bz;          // hacia el filo: el lado convexo del sori
	private static double camX, camY, camZ;
	private static double curL, curW, sori, ramp, sigma, guard, edgeW;

	// las muestras: centro, lado hacia el filo (mirando a cámara), bordes (lomo < 0 < filo), grosor de las líneas, atenuación
	private static final double[] S = new double[MAX];
	private static final double[] PX = new double[MAX], PY = new double[MAX], PZ = new double[MAX];
	private static final double[] EX = new double[MAX], EY = new double[MAX], EZ = new double[MAX];
	private static final double[] LO = new double[MAX], HI = new double[MAX];
	private static final double[] LW = new double[MAX], FA = new double[MAX];

	// ------------------------------------------------------------------ estado

	/**
	 * Otro jugador tiene la Kikoku revestida fuera hasta {@code until} (segundos de {@link RoomFxClient#time()}).
	 * Mientras la tiene fuera se queda con el final más tardío, como el servidor (que alarga la hoja con el máximo).
	 */
	public static void noteBlade(int userId, double until) {
		double now = RoomFxClient.time();
		double[] s = NOTED.get(userId);
		if (s == null) {
			if (until > now) NOTED.put(userId, new double[] {now, until});
			return;
		}
		if (now < s[1]) {
			s[1] = Math.max(s[1], until);
			return;
		}
		// se estaba recogiendo (o ya se había recogido): vuelve a crecer desde donde iba, sin saltos
		if (until <= now) return;
		s[0] = now - restart(extendOf(s[0], s[1], now));
		s[1] = until;
	}

	/**
	 * Una técnica de {@code userId} dibuja ahora su Kikoku con la luz {@code a}: es la misma hoja, así que la de reposo
	 * se apaga en esa medida (nunca dos hojas a la vez, y sin saltos cuando la técnica acaba). Vale para un fotograma:
	 * las técnicas se dibujan antes que {@link #draw}.
	 */
	public static void inUse(int userId, float a) {
		if (userId < 0 || !(a > 0f)) return;
		IN_USE.merge(userId, Math.min(1f, a), Math::max);
	}

	public static void clear() {
		NOTED.clear();
		IN_USE.clear();
		ownOn = false;
		ownStart = -100;
		ownEnd = -100;
	}

	/** ¿Hay alguna hoja fuera o recogiéndose (la propia o la de otro)? */
	public static boolean anyActive(MinecraftClient mc) {
		if (mc == null || mc.player == null) return false;
		double now = RoomFxClient.time();
		trackOwn(mc, now);
		if (ownOn || ownExtend(now) > 0.0) return true;
		purge(now);
		return !NOTED.isEmpty();
	}

	/** Dibuja la hoja propia (mientras dure K-ROOM) y la de cada jugador anotado, con su crecer y su recogerse. */
	public static void draw(BufferBuilder b, MinecraftClient mc, double now, boolean calm) {
		try {
			if (b == null || mc == null || mc.world == null || mc.player == null) return;
			trackOwn(mc, now);
			boolean dust = dustDue(mc);
			double own = ownExtend(now);
			if (own > 0.002) bladeOf(b, mc, mc.player, own, now, calm, dust);
			purge(now);
			if (NOTED.isEmpty()) return;
			int self = mc.player.getId();
			for (Map.Entry<Integer, double[]> en : NOTED.entrySet()) {
				int id = en.getKey();
				if (id == self) continue;   // la propia la manda ClientState
				double[] s = en.getValue();
				double ext = extendOf(s[0], s[1], now);
				if (ext <= 0.002) continue;
				Entity user = LawFxClient.entity(mc, id);
				if (user != null) bladeOf(b, mc, user, ext, now, calm, dust);
			}
		} finally {
			IN_USE.clear();
		}
	}

	private static void bladeOf(BufferBuilder b, MinecraftClient mc, Entity user, double ext, double now, boolean calm, boolean dust) {
		float a = 1f - IN_USE.getOrDefault(user.getId(), 0f);
		if (!(a > 0.01f)) return;
		Vec3d hilt = LawFxClient.handOf(user);
		Vec3d dir = dirOf(mc, user);
		blade(b, hilt, dir, LEN, ext, a, now, calm);
		if (dust && ext > 0.6 && a > 0.5f) dust(mc, hilt, dir, LEN * ext);
	}

	/** Hacia dónde va la hoja de reposo de {@code user}: a la mira en la primera persona propia; si no, en guardia. */
	static Vec3d dirOf(MinecraftClient mc, Entity user) {
		boolean aim = user == mc.player && mc.options.getPerspective().isFirstPerson();
		return aim ? user.getRotationVec(LawFxClient.td) : guardDir(user);
	}

	/** La dirección de la hoja en guardia (ver {@link #GUARD_RAISE}); mirando en vertical, la mirada tal cual. */
	static Vec3d guardDir(Entity user) {
		Vec3d look = user.getRotationVec(LawFxClient.td);
		double h = Math.sqrt(look.x * look.x + look.z * look.z);
		if (h < 1e-3) return look;
		double fx = look.x / h, fz = look.z / h;   // adelante, en horizontal
		double rx = -fz, rz = fx;                   // su derecha
		double co = Math.cos(GUARD_OPEN), so = Math.sin(GUARD_OPEN);
		double ox = fx * co + rx * so, oz = fz * co + rz * so;
		double pitch = Math.min(GUARD_MAX, Math.atan2(look.y, h) + GUARD_RAISE);
		double cp = Math.cos(pitch);
		return new Vec3d(ox * cp, Math.sin(pitch), oz * cp);
	}

	/** Una vez cada 4 ticks del mundo (como lo soltaba el servidor), sea cual sea la velocidad de fotogramas. */
	private static boolean dustDue(MinecraftClient mc) {
		long slot = mc.world.getTime() >> 2;
		if (slot == dustSlot) return false;
		dustSlot = slot;
		return true;
	}

	/**
	 * Polvo de ROOM cada 4 bloques de la hoja, desde los 3 primeros hasta casi la punta; no a menos de 3 bloques de la
	 * cámara (ahí cada mota se vería como un borrón de píxeles enorme).
	 */
	private static void dust(MinecraftClient mc, Vec3d hilt, Vec3d dir, double len) {
		double l = dir.length();
		if (!(l > 1e-6)) return;
		double ux = dir.x / l, uy = dir.y / l, uz = dir.z / l;
		Vec3d cam = LawFxClient.cam;
		var rnd = mc.world.random;
		for (double s = 3.0; s <= len - 1.0; s += 4.0) {
			double x = hilt.x + ux * s, y = hilt.y + uy * s, z = hilt.z + uz * s;
			if (cam.squaredDistanceTo(x, y, z) < 9.0) continue;
			mc.world.addParticle(DUST, x + rnd.nextGaussian() * 0.08, y + rnd.nextGaussian() * 0.08, z + rnd.nextGaussian() * 0.08,
				0.0, 0.0, 0.0);
		}
	}

	private static void trackOwn(MinecraftClient mc, double now) {
		boolean on = mc.player != null && mc.player.isAlive() && ClientState.kroomBlade();
		if (on && !ownOn) {
			ownStart = now - restart(extendOf(ownStart, ownEnd, now));
			ownOn = true;
		} else if (!on && ownOn) {
			ownEnd = now;
			ownOn = false;
		}
	}

	private static double ownExtend(double now) {
		return ownOn ? grow(now - ownStart) : extendOf(ownStart, ownEnd, now);
	}

	private static void purge(double now) {
		NOTED.values().removeIf(s -> now > s[1] + RETRACT + 0.05);
	}

	/** Cuánto ha crecido la hoja tras {@code age} segundos: de 0 a 1 en GROW, frenando al final. */
	private static double grow(double age) {
		if (age <= 0.0) return 0.0;
		if (age >= GROW) return 1.0;
		double k = 1.0 - age / GROW;
		return 1.0 - k * k * k;
	}

	/** El tiempo de crecimiento que corresponde a la extensión {@code e} (la inversa de grow). */
	private static double restart(double e) {
		e = MathHelper.clamp(e, 0.0, 1.0);
		return GROW * (1.0 - Math.cbrt(1.0 - e));
	}

	/** La extensión de una hoja que empezó a crecer en {@code start} y dura hasta {@code until}; después se recoge. */
	private static double extendOf(double start, double until, double now) {
		if (now < start) return 0.0;
		if (now < until) return grow(now - start);
		double v = (now - until) / RETRACT;
		if (v >= 1.0) return 0.0;
		return grow(until - start) * (1.0 - v * v * (3.0 - 2.0 * v));
	}

	// ------------------------------------------------------------------ la hoja

	/**
	 * Una Kikoku espectral desde {@code hilt} a lo largo de {@code dir}; mide {@code len * extend}.
	 * {@code a} multiplica toda su luz.
	 */
	public static void blade(BufferBuilder b, Vec3d hilt, Vec3d dir, double len, double extend, float a, double now, boolean calm) {
		if (b == null || hilt == null || dir == null || !(a > 0.01f) || !(extend > 0.002) || !(len > 0.05)) return;
		double l = Math.sqrt(dir.x * dir.x + dir.y * dir.y + dir.z * dir.z);
		if (!(l > 1e-6)) return;
		double ext = Math.min(1.0, extend);
		hx = hilt.x;
		hy = hilt.y;
		hz = hilt.z;
		dx = dir.x / l;
		dy = dir.y / l;
		dz = dir.z / l;
		Vec3d cam = LawFxClient.cam;
		camX = cam.x;
		camY = cam.y;
		camZ = cam.z;

		// el filo mira hacia abajo, en el plano de la hoja y la vertical (si apunta en vertical, hacia +X)
		double horiz = Math.sqrt(Math.max(0.0, 1.0 - dy * dy));
		if (horiz > 1e-4) {
			bx = dx * dy / horiz;
			by = (dy * dy - 1.0) / horiz;
			bz = dz * dy / horiz;
		} else {
			bx = 1.0 - dx * dx;
			by = -dy * dx;
			bz = -dz * dx;
			double bl = Math.sqrt(bx * bx + by * by + bz * bz);
			bx /= bl;
			by /= bl;
			bz /= bl;
		}
		// de qué lado del plano de la hoja está la cámara: así el filo cae siempre del lado convexo
		double qx = by * dz - bz * dy, qy = bz * dx - bx * dz, qz = bx * dy - by * dx;
		double ox = hx - camX, oy = hy - camY, oz = hz - camZ;
		sigma = ox * qx + oy * qy + oz * qz >= 0.0 ? 1.0 : -1.0;
		// cerca de la cámara la hoja no pasa de unos 10° de medio ancho en pantalla; con la cámara pegada a la tsuba
		// (primera persona) de unos 3,4°, para no tapar la vista
		double hc = Math.sqrt(ox * ox + oy * oy + oz * oz);
		double near = 1.0 - smooth((hc - 1.2) / 1.8);
		guard = 0.18 + (0.06 - 0.18) * near;

		double wFull = MathHelper.clamp(len * 0.035, 0.22, 1.1);
		curL = len * ext;
		curW = wFull * (0.3 + 0.7 * ext);
		sori = 0.015 * curL * smooth(horiz / 0.08);
		ramp = Math.min(1.5, 0.3 * curL);
		edgeW = MathHelper.clamp(wFull * 0.06, 0.035, 0.06);
		int n = fill();

		// 1) la hoja, tramo a tramo
		float aGlow = a * 0.06f, aRim = a * 0.22f, aMid = a * 0.13f, aEdge = a * 0.85f, aSpine = a * 0.5f, aCore = a * 0.5f;
		int tick = (int) Math.floor(now * 20.0);
		for (int j = 0; j < n - 1; j++) {
			int k = j + 1;
			double mj = (LO[j] + HI[j]) * 0.5, hj = (HI[j] - LO[j]) * 0.5;
			double mk = (LO[k] + HI[k]) * 0.5, hk = (HI[k] - LO[k]) * 0.5;
			float fj = (float) FA[j], fk = (float) FA[k];
			// el halo tenue de la membrana, el doble de ancho que la hoja
			seg(b, j, k, mj - hj * 2.2, mj, mk - hk * 2.2, mk, LawFxClient.ROOM, 0f, aGlow * fj, 0f, aGlow * fk);
			seg(b, j, k, mj, mj + hj * 2.2, mk, mk + hk * 2.2, LawFxClient.ROOM, aGlow * fj, 0f, aGlow * fk, 0f);
			// el cuerpo translúcido: más luz en los bordes, como una burbuja vista de canto
			seg(b, j, k, LO[j], mj, LO[k], mk, LawFxClient.ROOM, aRim * fj, aMid * fj, aRim * fk, aMid * fk);
			seg(b, j, k, mj, HI[j], mk, HI[k], LawFxClient.ROOM, aMid * fj, aRim * fj, aMid * fk, aRim * fk);
			// el filo: una línea blanca que tiembla un poco, con su resplandor por fuera
			float ej = aEdge * fj * flick(calm, tick, j), ek = aEdge * fk * flick(calm, tick, k);
			seg(b, j, k, HI[j] - LW[j], HI[j] + LW[j], HI[k] - LW[k], HI[k] + LW[k], LawFxClient.CORE, ej, ej, ek, ek);
			seg(b, j, k, HI[j] + LW[j], HI[j] + LW[j] * 3.5, HI[k] + LW[k], HI[k] + LW[k] * 3.5, LawFxClient.ROOM,
				ej * 0.35f, 0f, ek * 0.35f, 0f);
			// el lomo, más suave
			double sj = LW[j] * 0.8, sk = LW[k] * 0.8;
			seg(b, j, k, LO[j] - sj, LO[j] + sj, LO[k] - sk, LO[k] + sk, LawFxClient.ROOM, aSpine * fj, aSpine * fj,
				aSpine * fk, aSpine * fk);
			// el núcleo incandescente: la Kikoku de verdad dentro de la membrana
			double cj = LW[j] * 0.6, ck = LW[k] * 0.6;
			seg(b, j, k, mj - cj, mj + cj, mk - ck, mk + ck, LawFxClient.ELEC_HOT, aCore * fj, aCore * fj, aCore * fk,
				aCore * fk);
		}

		// 2) la yokote: la raya que separa la punta del resto de la hoja
		if (curL > 3.0) {
			float ay = a * 0.45f * (float) FA[KI];
			double yw = LW[KI] * 0.7;
			LawFxClient.ribbon(b, at(KI, LO[KI]), at(KI, HI[KI]), yw, yw, LawFxClient.CORE, ay, ay);
		}

		// 3) bandas de membrana que corren de la tsuba a la punta
		if (!calm) {
			double hb = Math.min(0.06, (0.3 + 0.018 * curL) / curL);
			for (int i = 0; i < BANDS; i++) {
				double ph = frac(now * 0.6 + i / (double) BANDS);
				double env = Math.sin(Math.PI * ph);
				float pk = (float) (a * 0.2 * env), pe = (float) (a * 0.5 * env);
				if (pk < 0.004f) continue;
				double sb = 0.03 + 0.9 * ph;
				sample(BAND, Math.max(0.0, sb - hb));
				sample(BAND + 1, sb);
				sample(BAND + 2, Math.min(1.0, sb + hb));
				for (int q = 0; q < 2; q++) {
					int j = BAND + q, k = j + 1;
					// la banda abraza la hoja (un poco más ancha que ella), sube hasta su centro y vuelve a bajar
					double mj = (LO[j] + HI[j]) * 0.5, hj = (HI[j] - LO[j]) * 0.575;
					double mk = (LO[k] + HI[k]) * 0.5, hk = (HI[k] - LO[k]) * 0.575;
					float fj = q == 0 ? 0f : (float) FA[j], fk = q == 0 ? (float) FA[k] : 0f;
					seg(b, j, k, mj - hj, mj + hj, mk - hk, mk + hk, LawFxClient.ROOM, pk * fj, pk * fj, pk * fk, pk * fk);
					// el filo se enciende al paso de la banda
					seg(b, j, k, HI[j] - LW[j] * 1.4, HI[j] + LW[j] * 1.4, HI[k] - LW[k] * 1.4, HI[k] + LW[k] * 1.4,
						LawFxClient.CORE, pe * fj, pe * fj, pe * fk, pe * fk);
				}
			}
		}

		// 4) la tsuba: un anillo con la cruz de la Kikoku, y la bolita de K-ROOM en la mano (más discretas en primera persona)
		float aT = a * (float) Math.min(1.0, ext * 3.0) * (float) (1.0 - 0.7 * near);
		if (aT > 0.01f) {
			double rT = 0.12 * (1.0 - 0.4 * near);
			LawFxClient.axes(new Vec3d(dx, dy, dz));
			double ux = LawFxClient.u1x, uy = LawFxClient.u1y, uz = LawFxClient.u1z;
			double vx = LawFxClient.u2x, vy = LawFxClient.u2y, vz = LawFxClient.u2z;
			LawFxClient.ring(b, hilt, rT * 0.62, rT, LawFxClient.ROOM, aT * 0.55f, aT * 0.2f);
			// la cruz no se dibuja en primera persona: ahí ya está la Kikoku que se ve en la mano
			float aX = aT * 0.6f * (float) (1.0 - near);
			double rc = rT * 1.3;
			LawFxClient.ribbon(b, hilt.add(-ux * rc, -uy * rc, -uz * rc), hilt.add(ux * rc, uy * rc, uz * rc), 0.014, 0.014,
				LawFxClient.CORE, aX, aX);
			LawFxClient.ribbon(b, hilt.add(-vx * rc, -vy * rc, -vz * rc), hilt.add(vx * rc, vy * rc, vz * rc), 0.014, 0.014,
				LawFxClient.CORE, aX, aX);
			LawFxClient.disc(b, hilt, 0.2 * (1.0 - 0.5 * near), LawFxClient.ROOM, aT * 0.3f, 0f);
			LawFxClient.disc(b, hilt, 0.06 * (1.0 - 0.4 * near), LawFxClient.CORE, aT * 0.55f, 0f);
		}

		// 5) el destello de la punta (más grande mientras la hoja sale o se recoge)
		int last = n - 1;
		Vec3d tip = at(last, (LO[last] + HI[last]) * 0.5);
		float ag = a * (float) FA[last] * (float) (1.0 - 0.6 * near) * (OpeSettings.flashLevel() == 0 ? 0.5f : 0.9f);
		if (ag > 0.01f) {
			double tw = calm ? 1.0 : 0.85 + 0.15 * Math.sin(now * 11.0);
			double rg = (0.16 + 0.011 * curL) * tw * (1.0 + 0.6 * (1.0 - ext));
			LawFxClient.disc(b, tip, rg, LawFxClient.CORE, ag, 0f);
			// cuatro rayos finos, dos largos y dos cortos, que giran despacio
			LawFxClient.faceCam(tip);
			double rot = calm ? 0.0 : now * 0.8;
			double rw = 0.02 + 0.0022 * curL;
			for (int i = 0; i < 4; i++) {
				double ang = rot + i * Math.PI * 0.5;
				double cs = Math.cos(ang), sn = Math.sin(ang);
				double rl = rg * (i % 2 == 0 ? 2.6 : 1.6);
				double rx = (LawFxClient.u1x * cs + LawFxClient.u2x * sn) * rl;
				double ry = (LawFxClient.u1y * cs + LawFxClient.u2y * sn) * rl;
				double rz = (LawFxClient.u1z * cs + LawFxClient.u2z * sn) * rl;
				LawFxClient.ribbon(b, tip, tip.add(rx, ry, rz), rw, 0.0, LawFxClient.CORE, ag * 0.8f, 0f);
			}
		}
	}

	/** La esfera pequeña de K-ROOM sobre la palma: tres anillos giroscópicos, un disco tenue y un centro brillante. */
	public static void palmSphere(BufferBuilder b, Vec3d c, double r, float a, double now) {
		if (b == null || c == null || !(r > 0.0) || !(a > 0.01f)) return;
		double spin = OpeSettings.reducedMotion() ? 0.35 : 1.0;
		// cada anillo gira alrededor de un eje distinto, como un giroscopio
		for (int i = 0; i < 3; i++) {
			double ang = now * (2.2 + 0.9 * i) * spin + i * 2.1;
			double cs = Math.cos(ang), sn = Math.sin(ang);
			Vec3d axis = i == 0 ? new Vec3d(cs, sn, 0.35) : i == 1 ? new Vec3d(0.35, cs, sn) : new Vec3d(sn, 0.35, cs);
			LawFxClient.axes(axis);
			double rr = r * (1.0 - 0.06 * i);
			LawFxClient.ring(b, c, rr * 0.88, rr, i == 0 ? LawFxClient.CORE : LawFxClient.ROOM, a * 0.85f, a * 0.3f);
		}
		// la esfera: un disco tenue mirando a la cámara con el borde algo más vivo (del ROOM solo se ve el borde)
		LawFxClient.disc(b, c, r * 1.04, LawFxClient.ROOM, a * 0.1f, a * 0.3f);
		// el centro brillante
		LawFxClient.disc(b, c, r * 0.3, LawFxClient.CORE, a * 0.95f, 0f);
	}

	// ------------------------------------------------------------------ geometría de la hoja

	/** Reparte las muestras a lo largo de la hoja y las calcula; devuelve cuántas hay. */
	private static int fill() {
		int n = 0;
		S[n++] = 0.0;
		double sr = ramp / curL;
		for (double f : RAMP_STEPS) S[n++] = sr * f;
		for (int i = 1; i <= MID; i++) S[n++] = sr + (KISSAKI - sr) * i / MID;
		for (double f : TIP_STEPS) S[n++] = KISSAKI + (1.0 - KISSAKI) * f;
		for (int j = 0; j < n; j++) sample(j, S[j]);
		return n;
	}

	/** Calcula la muestra {@code k} en la fracción {@code s} de la hoja (0 = tsuba, 1 = punta). */
	private static void sample(int k, double s) {
		double d = s * curL;
		// el centro, con la curvatura (sori) hacia el filo
		double bow = sori * 4.0 * s * (1.0 - s);
		double x = hx + dx * d + bx * bow, y = hy + dy * d + by * bow, z = hz + dz * d + bz * bow;
		// la tangente (sin normalizar: solo hace falta su dirección)
		double slope = sori * 4.0 * (1.0 - 2.0 * s);
		double tx = dx * curL + bx * slope, ty = dy * curL + by * slope, tz = dz * curL + bz * slope;
		// el lado que mira a la cámara, siempre hacia el filo
		double mx = x - camX, my = y - camY, mz = z - camZ;
		double sx = ty * mz - tz * my, sy = tz * mx - tx * mz, sz = tx * my - ty * mx;
		double sl = Math.sqrt(sx * sx + sy * sy + sz * sz);
		double dc = Math.sqrt(mx * mx + my * my + mz * mz);
		double tl = Math.sqrt(tx * tx + ty * ty + tz * tz);
		// vista casi de punta (a lo largo de la hoja): una cinta de cara a la cámara sería una hoja ancha y corta,
		// así que se afila y sigue leyéndose como una hoja larga que se aleja
		double sinA = sl / Math.max(tl * dc, 1e-9);
		double along = 0.15 + 0.85 * smooth(sinA / 0.28);
		if (sl < 1e-9) {
			sx = bx;
			sy = by;
			sz = bz;
		} else {
			double inv = sigma / sl;
			sx *= inv;
			sy *= inv;
			sz *= inv;
		}
		// el perfil: fina en la mano, se ensancha en ~1,5 m, ancho constante y la punta curva (kissaki)
		double w = curW;
		if (d < ramp) {
			// el cuello: la Kikoku de verdad sale fina de la tsuba y la membrana se hincha en un hombro suave
			w = W_HILT + (curW - W_HILT) * smooth((d / ramp - NECK) / (1.0 - NECK));
		}
		double lo = -w, hi = w;
		if (s > KISSAKI) {
			double u = Math.min(1.0, (s - KISSAKI) / (1.0 - KISSAKI));
			double tip = -TIP_SHIFT * w;
			hi = tip + (w - tip) * Math.sqrt(Math.max(0.0, 1.0 - u * u));   // el filo se curva hacia el lomo (fukura)
			lo = -w + (w + tip) * u * u;                                     // el lomo apenas se inclina
		}
		// cerca de la cámara se estrecha: en pantalla nunca pasa de «guard» radianes de medio ancho
		double r = w / Math.max(guard * dc, 1e-6), r2 = r * r;
		double f = along / Math.sqrt(Math.sqrt(1.0 + r2 * r2));
		lo *= f;
		hi *= f;
		PX[k] = x;
		PY[k] = y;
		PZ[k] = z;
		EX[k] = sx;
		EY[k] = sy;
		EZ[k] = sz;
		LO[k] = lo;
		HI[k] = hi;
		LW[k] = Math.min(edgeW, Math.max(0.008, (hi - lo) * 0.5 * 0.35));
		// y se desvanece al pasar junto a la cámara
		FA[k] = smooth((dc - 0.3) / 1.7);
	}

	/** Un quad entre las muestras j y k, de los desplazamientos o0 a o1 (hacia el filo), con su alfa en cada esquina. */
	private static void seg(BufferBuilder b, int j, int k, double o0j, double o1j, double o0k, double o1k, int rgb,
			float a0j, float a1j, float a0k, float a1k) {
		if (a0j <= 0.002f && a1j <= 0.002f && a0k <= 0.002f && a1k <= 0.002f) return;
		LawFxClient.vtx(b, PX[j] + EX[j] * o0j, PY[j] + EY[j] * o0j, PZ[j] + EZ[j] * o0j, rgb, a0j);
		LawFxClient.vtx(b, PX[j] + EX[j] * o1j, PY[j] + EY[j] * o1j, PZ[j] + EZ[j] * o1j, rgb, a1j);
		LawFxClient.vtx(b, PX[k] + EX[k] * o1k, PY[k] + EY[k] * o1k, PZ[k] + EZ[k] * o1k, rgb, a1k);
		LawFxClient.vtx(b, PX[k] + EX[k] * o0k, PY[k] + EY[k] * o0k, PZ[k] + EZ[k] * o0k, rgb, a0k);
	}

	/** El punto de la muestra k desplazado {@code o} hacia el filo. */
	private static Vec3d at(int k, double o) {
		return new Vec3d(PX[k] + EX[k] * o, PY[k] + EY[k] * o, PZ[k] + EZ[k] * o);
	}

	/** El leve parpadeo del filo (nada con «menos movimiento»). */
	private static float flick(boolean calm, int tick, int j) {
		return calm ? 1f : (float) (0.82 + 0.18 * LawFxClient.hash01(tick * 7919 + j * 131));
	}

	private static double smooth(double x) {
		x = MathHelper.clamp(x, 0.0, 1.0);
		return x * x * (3.0 - 2.0 * x);
	}

	private static double frac(double x) {
		return x - Math.floor(x);
	}
}
