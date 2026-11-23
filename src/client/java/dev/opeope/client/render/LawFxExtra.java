package dev.opeope.client.render;

import dev.opeope.client.config.OpeSettings;
import dev.opeope.net.Payloads.LawFx;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.BufferBuilder;
import net.minecraft.entity.Entity;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;

import java.util.HashMap;
import java.util.Map;

/**
 * Tres efectos de Law que faltaban, sacados de la serie:
 * <ul>
 * <li>R-ROOM: Silent lanzado sobre otro ({@link LawFx#SILENT_TARGET}): la burbuja insonora que se le pega al objetivo y
 * le sigue (como a Big Mom, que se quedó sin la voz del Soul Pocus), con el cartel de «sin sonido» sobre la cabeza y
 * el sonido que intenta salir y se encoge hacia dentro.</li>
 * <li>Scan + Shambles ({@link LawFx#SCAN_STEAL}): lo que el escaneado llevaba en la mano vuela en arco hasta el pecho
 * de Law, y en su mano aparece una piedra (como los Den Den Mushi de los marines del G-5).</li>
 * <li>Cirugía ({@link LawFx#EXTRACT}): se abre una incisión cúbica en el pecho, sin sangre, y la impureza (veneno,
 * infección) sale, sube temblando y se deshace en chispitas; al curar a un aldeano zombi, además, un anillo cálido
 * de oro y verde alrededor del cuerpo.</li>
 * </ul>
 * Todo va dentro del BufferBuilder ADITIVO que ya abrió {@code LawFxClient.renderWorld} (no se abren ni cierran
 * buffers ni se toca RenderSystem); solo se usan los ayudantes de geometría de {@link LawFxClient}. Con la mezcla
 * aditiva no se puede oscurecer: la impureza es un violeta y un verde apagados a media luz.
 * Con «menos movimiento» no hay parpadeos, ni bamboleos, ni giros rápidos; los destellos siguen el nivel elegido.
 */
final class LawFxExtra {
	private LawFxExtra() {}

	private static final Vec3d UP = new Vec3d(0, 1, 0);
	/** La impureza: violeta apagado y verde enfermizo. */
	private static final int VIOLET = 0x8A4DFF;
	private static final int SICK = 0x9DFF4D;
	/** El verde cálido de la cura (con el oro de LawFxClient.GOLD). */
	private static final int WARM_GREEN = 0xB8FF5A;
	/** La piedra del intercambio: gris (con luz aditiva, un gris tenue). */
	private static final int PEBBLE = 0x9C9C9C;
	private static final int PEBBLE_EDGE = 0xD2D2D2;
	/** Cuándo se pega la burbuja al ser: lo que tarda en llegar la semilla de R-ROOM lanzada desde la palma (s). */
	private static final double STICK = 0.35;

	/** El Silent más nuevo de cada ser (id → cuándo empezó): si se renueva, solo se dibuja el último. */
	private static final Map<Integer, Double> NEWEST_SILENT = new HashMap<>();
	/** Dónde llevaba la cosa cada ser robado, respecto a sus pies (semilla del evento → desplazamiento). */
	private static final Map<Integer, Vec3d> HAND_OFFSET = new HashMap<>();

	static void draw(BufferBuilder b, MinecraftClient mc, LawFxClient.Ev e, double t, double age, double now, boolean calm) {
		switch (e.kind()) {
			case LawFx.SILENT_TARGET -> silentTarget(b, mc, e, age, now, calm);
			case LawFx.SCAN_STEAL -> scanSteal(b, mc, e, t, now, calm);
			case LawFx.EXTRACT -> extract(b, mc, e, t, now, calm);
			default -> {}
		}
	}

	// ------------------------------------------------------------------ R-ROOM: Silent sobre otro

	/** La burbuja muda pegada al objetivo: aparece al llegarle el R-ROOM, le sigue y se apaga en los últimos 0,6 s. */
	private static void silentTarget(BufferBuilder b, MinecraftClient mc, LawFxClient.Ev e, double age, double now, boolean calm) {
		Entity target = LawFxClient.entity(mc, e.targetId());
		if (target == null) return;
		// renovado (otro R-ROOM Silent sobre el mismo ser): solo cuenta el más nuevo
		Double newest = NEWEST_SILENT.get(e.targetId());
		if (newest != null && newest > e.start()) return;
		if (newest == null || newest < e.start()) {
			if (NEWEST_SILENT.size() > 64) NEWEST_SILENT.clear();
			NEWEST_SILENT.put(e.targetId(), e.start());
		}
		double local = age - STICK;
		if (local <= 0) return;
		double out = MathHelper.clamp((e.duration() - age) / 0.6, 0.0, 1.0);
		float a = (float) (Math.min(1.0, local / 0.3) * out);
		if (a <= 0.01f) return;

		Box box = LawFxClient.lerpBox(target);
		Vec3d c = box.getCenter();
		double full = Math.max(e.radius(), box.getLengthY() * 0.55 + 0.15);
		// se pega y se agranda hasta envolverlo (frenando al final), y luego respira despacio
		double g = Math.min(1.0, local / 0.3);
		double grow = 1.0 - (1.0 - g) * (1.0 - g) * (1.0 - g);
		double r = full * (0.25 + 0.75 * grow) * (calm ? 1.0 : 1.0 + 0.03 * Math.sin(now * 1.6));
		// con la cámara dentro (el mudo es uno mismo), más tenue para no tapar la vista
		if (LawFxClient.cam.squaredDistanceTo(c) < r * r) a *= 0.5f;

		// 1) al pegarse: un aro de luz que se abre desde el ser
		if (local < 0.4) {
			double u = local / 0.4;
			float pa = (float) ((1.0 - u) * out) * flash();
			LawFxClient.faceCam(c);
			line(b, c, full * (0.35 + 0.95 * u), 0.06, LawFxClient.CORE, pa * 0.8f);
			LawFxClient.ring(b, c, full * (0.2 + 0.9 * u), full * (0.35 + 0.95 * u), LawFxClient.ROOM, 0f, pa * 0.35f);
		}

		// 2) la piel de la burbuja: solo se ve el borde celeste, como el ROOM…
		LawFxClient.faceCam(c);
		LawFxClient.ring(b, c, r * 0.86, r, LawFxClient.ROOM, 0f, a * 0.3f);
		line(b, c, r, 0.03, LawFxClient.CORE, a * 0.3f);
		// …con cinco paralelos y tres meridianos que giran despacio
		LawFxClient.axes(UP);
		for (int k = -2; k <= 2; k++) {
			double lat = k * Math.PI / 6;
			double rr = r * Math.cos(lat);
			LawFxClient.ring(b, c.add(0, r * Math.sin(lat), 0), rr - 0.05, rr, LawFxClient.HUSH, a * 0.42f, 0f);
		}
		double spin = now * (calm ? 0.12 : 0.35);
		for (int k = 0; k < 3; k++) {
			double ang = spin + k * Math.PI / 3;
			LawFxClient.axes(new Vec3d(Math.cos(ang), 0, Math.sin(ang)));
			LawFxClient.ring(b, c, r - 0.05, r, k == 0 ? LawFxClient.ROOM : LawFxClient.HUSH, a * 0.36f, 0f);
		}

		// 3) el sonido que intenta salir y se encoge hacia dentro (cada 1,2 s, dos ondas desfasadas)
		LawFxClient.faceCam(c);
		for (int k = 0; k < 2; k++) {
			double ph = (local / 1.2 + k * 0.5) % 1.0;
			double wr = r * (1.55 - 1.1 * ph);
			float wa = a * 0.5f * (float) Math.sin(ph * Math.PI);
			LawFxClient.ring(b, c, wr * 0.93, wr, LawFxClient.HUSH, 0f, wa);
			LawFxClient.ring(b, c, wr, wr * 1.02, LawFxClient.CORE, wa * 0.6f, 0f);
		}

		// 4) el cartel de «sin sonido» flotando sobre la cabeza, de cara a la cámara
		double bob = calm ? 0.0 : 0.05 * Math.sin(now * 2.2);
		Vec3d s = new Vec3d(c.x, box.maxY + 0.45 + bob, c.z);
		double sr = MathHelper.clamp(full * 0.17, 0.18, 0.45);
		float sa = a * nearFade(s);
		if (sa <= 0.01f) return;
		Vec3d[] ax = screenAxes(s);
		Vec3d rt = ax[0], up = ax[1];
		// el altavoz…
		LawFxClient.quad(b, pt(s, rt, up, -0.5 * sr, -0.17 * sr), pt(s, rt, up, -0.2 * sr, -0.17 * sr),
			pt(s, rt, up, -0.2 * sr, 0.17 * sr), pt(s, rt, up, -0.5 * sr, 0.17 * sr), LawFxClient.ROOM, sa * 0.5f, sa * 0.5f, sa * 0.5f, sa * 0.5f);
		LawFxClient.quad(b, pt(s, rt, up, -0.2 * sr, -0.17 * sr), pt(s, rt, up, 0.24 * sr, -0.42 * sr),
			pt(s, rt, up, 0.24 * sr, 0.42 * sr), pt(s, rt, up, -0.2 * sr, 0.17 * sr), LawFxClient.ROOM, sa * 0.5f, sa * 0.5f, sa * 0.5f, sa * 0.5f);
		// …dentro del aro…
		LawFxClient.faceCam(s);
		LawFxClient.ring(b, s, sr * 0.8, sr, LawFxClient.HUSH, sa * 0.85f, sa * 0.85f);
		LawFxClient.ring(b, s, sr, sr * 1.3, LawFxClient.HUSH, sa * 0.3f, 0f);
		line(b, s, sr * 0.9, 0.012, LawFxClient.CORE, sa * 0.6f);
		// …y tachado en diagonal
		Vec3d d0 = pt(s, rt, up, -0.57 * sr, 0.57 * sr), d1 = pt(s, rt, up, 0.57 * sr, -0.57 * sr);
		LawFxClient.ribbon(b, d0, d1, 0.1 * sr, 0.1 * sr, LawFxClient.HUSH, sa * 0.85f, sa * 0.85f);
		LawFxClient.ribbon(b, d0, d1, 0.035 * sr, 0.035 * sr, LawFxClient.CORE, sa * 0.7f, sa * 0.7f);
	}

	// ------------------------------------------------------------------ Scan + Shambles

	/** Lo robado vuela en arco hasta Law (t < 0,7) y en la mano del ser aparece una piedra (t ≈ 0,15). */
	private static void scanSteal(BufferBuilder b, MinecraftClient mc, LawFxClient.Ev e, double t, double now, boolean calm) {
		Entity mob = LawFxClient.entity(mc, e.targetId());
		Entity user = LawFxClient.entity(mc, e.userId());
		// la mano del ser: donde estaba al robarle; si se mueve, la piedra va con él
		Vec3d hand = e.a();
		if (mob != null) {
			Vec3d feet = LawFxClient.lerpPos(mob);
			Vec3d off = HAND_OFFSET.get(e.seed());
			if (off == null) {
				if (HAND_OFFSET.size() > 256) HAND_OFFSET.clear();
				off = e.a().subtract(feet);
				HAND_OFFSET.put(e.seed(), off);
			}
			hand = feet.add(off);
		}
		Vec3d to = user != null ? LawFxClient.lerpPos(user).add(0, user.getHeight() * 0.62, 0) : e.b();
		float fl = flash();
		boolean flicker = !calm && OpeSettings.flashLevel() >= 2;

		// 1) Shambles: el parpadeo celeste donde estaba la cosa
		if (t < 0.18) {
			double u = t / 0.18;
			float fa = (float) (1.0 - u) * fl;
			if (flicker) fa *= (float) (0.7 + 0.3 * Math.cos(u * Math.PI * 6.0));
			LawFxClient.disc(b, hand, 0.18 + 0.32 * u, LawFxClient.ROOM, fa * 0.75f, 0f);
			LawFxClient.faceCam(hand);
			line(b, hand, 0.15 + 0.45 * u, 0.03, LawFxClient.CORE, fa * 0.9f);
		}

		// 2) la cosa robada: un cubito brillante que vuela en arco hasta el pecho de Law, con su estela
		double dist = hand.distanceTo(to);
		Vec3d mid = hand.lerp(to, 0.5).add(0, 0.4 + Math.min(2.0, dist * 0.15), 0);
		if (t < 0.72) {
			double u = MathHelper.clamp(t / 0.7, 0.0, 1.0);
			double ease = u * u * (3 - 2 * u);
			Vec3d pos = LawFxClient.bezier(hand, mid, to, ease);
			float ca = (float) (Math.min(1.0, t / 0.04) * (t < 0.66 ? 1.0 : (0.72 - t) / 0.06)) * nearFade(pos);
			if (ca > 0.01f) {
				double spin = now * (calm ? 1.5 : 6.0) + e.seed() * 0.37;
				LawFxClient.cubeFaces(b, pos, 0.08, LawFxClient.ROOM, ca * 0.25f, spin);
				LawFxClient.cubeEdges(b, pos, 0.08, LawFxClient.CORE, ca * 0.95f, 0.012, spin);
				LawFxClient.disc(b, pos, 0.2, LawFxClient.ROOM, ca * 0.4f, 0f);
				// la estela: una cinta que se afina por detrás, siguiendo el arco
				Vec3d prev = pos;
				for (int k = 1; k <= 5; k++) {
					double uk = Math.max(0.0, ease - k * 0.04);
					Vec3d q = LawFxClient.bezier(hand, mid, to, uk);
					float ka = ca * 0.55f * (1f - k / 6f);
					LawFxClient.ribbon(b, prev, q, 0.045 * (1.0 - (k - 1) / 6.0), 0.045 * (1.0 - k / 6.0), LawFxClient.ROOM, ka, ka * 0.7f);
					prev = q;
				}
			}
		}

		// 3) la llegada: otro destello en el pecho de Law
		if (t >= 0.68 && t < 0.95) {
			double u = (t - 0.68) / 0.27;
			float fa = (float) ((1.0 - u) * (1.0 - u)) * fl * nearFade(to);
			LawFxClient.disc(b, to, 0.2 + 0.25 * u, LawFxClient.ROOM, fa * 0.6f, 0f);
			LawFxClient.faceCam(to);
			line(b, to, 0.2 + 0.55 * u, 0.03, LawFxClient.CORE, fa * 0.8f);
		}

		// 4) la piedra: aparece de golpe en la mano del ser (el otro lado del intercambio)
		if (t >= 0.15) {
			double u = Math.min(1.0, (t - 0.15) / 0.12);
			double sc = calm ? u * u * (3 - 2 * u) : backOut(u);
			float pa = (float) (Math.min(1.0, (t - 0.15) / 0.04) * (t > 0.85 ? (1.0 - t) / 0.15 : 1.0)) * nearFade(hand);
			double spin = e.seed() * 0.61;
			LawFxClient.cubeFaces(b, hand, 0.06 * sc, PEBBLE, pa * 0.35f, spin);
			LawFxClient.cubeEdges(b, hand, 0.06 * sc, PEBBLE_EDGE, pa * 0.7f, 0.008, spin);
			if (u < 1.0) {
				// un soplo al aparecer
				LawFxClient.faceCam(hand);
				line(b, hand, 0.08 + 0.2 * u, 0.02, PEBBLE_EDGE, (float) (1.0 - u) * 0.5f * fl);
			}
		}
	}

	// ------------------------------------------------------------------ cirugía: extraer la impureza

	/** La incisión cúbica, la impureza que sale y sube, el estallido en chispitas y, si cura, el anillo cálido. */
	private static void extract(BufferBuilder b, MinecraftClient mc, LawFxClient.Ev e, double t, double now, boolean calm) {
		Entity target = LawFxClient.entity(mc, e.targetId());
		Entity user = LawFxClient.entity(mc, e.userId());
		Vec3d rise = e.b().subtract(e.a());
		Vec3d chest = e.a();
		double half = Math.max(0.2, e.radius() * 0.5);
		if (target != null) {
			chest = LawFxClient.lerpPos(target).add(0, target.getHeight() * 0.62, 0);
			half = target.getWidth() * 0.5;
		}
		// la incisión va en la piel del lado de Law (o de la cámara, si se opera a sí mismo)
		Vec3d face = user != null && user != target ? LawFxClient.lerpPos(user).subtract(chest) : LawFxClient.cam.subtract(chest);
		face = new Vec3d(face.x, 0, face.z);
		if (face.lengthSquared() < 1e-4 && target != null) {
			Vec3d look = target.getRotationVec(LawFxClient.td);
			face = new Vec3d(look.x, 0, look.z);
		}
		face = face.lengthSquared() < 1e-4 ? new Vec3d(1, 0, 0) : face.normalize();
		Vec3d skin = chest.add(face.multiply(half + 0.05));
		Vec3d top = skin.add(rise);
		float fl = flash();

		// 1) la incisión cúbica (bordes del ROOM) que se abre al principio y se cierra al final, sin sangre
		double open = t < 0.14 ? easeOut(t / 0.14) : t > 0.74 ? Math.max(0.0, 1.0 - (t - 0.74) / 0.14) : 1.0;
		if (open > 0.01) {
			float ia = (float) open * nearFade(skin);
			double h = 0.15 * open;
			double spin = Math.atan2(face.x, face.z);
			LawFxClient.cubeFaces(b, skin, h, LawFxClient.CORE, ia * 0.1f, spin);
			LawFxClient.cubeEdges(b, skin, h, LawFxClient.ROOM, ia * 0.9f, 0.012, spin);
			LawFxClient.disc(b, skin, 0.12 + 0.05 * open, LawFxClient.CORE, ia * 0.35f, 0f);
		}

		// 2) la impureza (violeta apagado y verde enfermizo) sale y sube temblando hasta arriba
		if (t >= 0.1 && t < 0.64) {
			double u = (t - 0.1) / 0.54;
			double ease = u * u * (3 - 2 * u);
			Vec3d side = face.crossProduct(UP);
			double amp = (calm ? 0.03 : 0.09) * Math.sin(Math.PI * Math.min(1.0, u * 1.4));
			Vec3d pos = skin.lerp(top, ease)
				.add(side.multiply(amp * Math.sin(now * (calm ? 3.0 : 9.0) + e.seed())))
				.add(face.multiply(amp * 0.6 * Math.cos(now * (calm ? 2.5 : 7.0) + e.seed())));
			float ba = (float) Math.min(1.0, u * 5.0) * (t > 0.58 ? (float) ((0.64 - t) / 0.06) : 1f) * nearFade(pos);
			if (ba > 0.01f) {
				double size = 0.1 + 0.08 * Math.min(1.0, u * 3.0);
				double wob = calm ? 2.0 : 6.0;
				// la mancha: lóbulos que se mueven alrededor del centro
				for (int k = 0; k < 5; k++) {
					Vec3d off = LawFxClient.randDir(e.seed() + k * 13).multiply(size * (0.55 + 0.35 * Math.sin(now * wob + k * 1.7)));
					double lr = size * (0.75 + 0.2 * Math.sin(now * wob * 0.8 + k));
					LawFxClient.disc(b, pos.add(off), lr, (k & 1) == 0 ? VIOLET : SICK, ba * 0.42f, 0f);
				}
				LawFxClient.disc(b, pos, size * 0.7, VIOLET, ba * 0.6f, 0f);
				// el hilo que aún la une a la incisión, que se va soltando
				if (u < 0.75) {
					float ta = ba * 0.35f * (float) (1.0 - u / 0.75);
					LawFxClient.ribbon(b, skin, pos, 0.015, 0.035, SICK, ta * 0.6f, ta);
				}
			}
		}

		// 3) arriba estalla en chispitas y se deshace
		if (t >= 0.6) {
			double v = MathHelper.clamp((t - 0.6) / 0.4, 0.0, 1.0);
			double spread = 1.0 - (1.0 - v) * (1.0 - v);
			float sa = (float) ((1.0 - v) * (1.0 - v)) * nearFade(top);
			if (sa > 0.01f) {
				LawFxClient.faceCam(top);
				line(b, top, 0.12 + 0.8 * spread, 0.04, SICK, sa * 0.5f * fl);
				LawFxClient.disc(b, top, 0.25 * (1.0 - v) + 0.05, VIOLET, sa * 0.5f * fl, 0f);
				for (int k = 0; k < 14; k++) {
					Vec3d dir = LawFxClient.randDir(e.seed() * 31 + k * 7 + 3);
					double reach = (0.25 + 0.75 * spread) * (0.55 + 0.45 * LawFxClient.hash01(e.seed() + k * 11));
					Vec3d q = top.add(dir.multiply(reach)).add(0, -0.25 * v * v, 0);
					Vec3d tail = q.subtract(dir.multiply(0.12 * (1.0 - v) + 0.02));
					int col = k % 3 == 0 ? LawFxClient.CORE : (k & 1) == 0 ? VIOLET : SICK;
					LawFxClient.ribbon(b, tail, q, 0.0, 0.022 * (1.0 - v) + 0.006, col, 0f, sa * 0.9f);
				}
			}
		}

		// 4) curado un aldeano zombi: un anillo cálido de oro y verde que estalla alrededor del cuerpo
		if (e.power() > 0.5f && t >= 0.5) {
			double w = MathHelper.clamp((t - 0.5) / 0.5, 0.0, 1.0);
			double ew = 1.0 - (1.0 - w) * (1.0 - w);
			Box box = target != null ? LawFxClient.lerpBox(target)
				: new Box(chest.x - 0.3, chest.y - 1.2, chest.z - 0.3, chest.x + 0.3, chest.y + 0.75, chest.z + 0.3);
			Vec3d c = box.getCenter();
			double br = Math.max(0.45, box.getLengthX() * 0.75);
			float ga = (float) (1.0 - w);
			LawFxClient.axes(UP);
			Vec3d waist = new Vec3d(c.x, box.minY + box.getLengthY() * 0.45, c.z);
			line(b, waist, br + 1.5 * ew, 0.08, LawFxClient.GOLD, ga * 0.75f);
			line(b, waist.add(0, 0.12, 0), br + 1.1 * ew, 0.06, WARM_GREEN, ga * 0.6f);
			// el aro dorado que sube de los pies a la cabeza
			double y = box.minY + box.getLengthY() * Math.min(1.0, ew * 1.1);
			line(b, new Vec3d(c.x, y, c.z), br * 1.05, 0.05, LawFxClient.GOLD, (float) Math.sin(w * Math.PI) * 0.8f);
			// motas doradas y verdes que suben
			for (int k = 0; k < 10; k++) {
				double ang = k * Math.PI * 2 / 10 + LawFxClient.hash01(e.seed() + k) * 0.6;
				double rr = br * (0.8 + 0.4 * LawFxClient.hash01(e.seed() * 3 + k));
				double yy = box.minY + box.getLengthY() * (0.1 + 0.9 * LawFxClient.hash01(e.seed() * 7 + k)) + 0.8 * ew;
				Vec3d q = new Vec3d(c.x + Math.cos(ang) * rr, yy, c.z + Math.sin(ang) * rr);
				LawFxClient.disc(b, q, 0.05, (k & 1) == 0 ? LawFxClient.GOLD : WARM_GREEN, ga * 0.8f, 0f);
			}
			// un destello cálido en el pecho al curarse
			if (w < 0.3) {
				LawFxClient.disc(b, skin, 0.35 + 0.4 * w, LawFxClient.GOLD, (float) (1.0 - w / 0.3) * 0.5f * fl * nearFade(skin), 0f);
			}
		}
	}

	// ------------------------------------------------------------------ ayudantes

	/** Cuánto pesan los destellos según el nivel elegido (2 = todos, 1 = suaves, 0 = casi nada). */
	private static float flash() {
		int level = OpeSettings.flashLevel();
		return level >= 2 ? 1f : level == 1 ? 0.6f : 0.3f;
	}

	/** Se apaga lo que pasa pegado a la cámara (lo propio en primera persona), para no tapar la pantalla. */
	private static float nearFade(Vec3d p) {
		double d = p.distanceTo(LawFxClient.cam);
		return (float) MathHelper.clamp((d - 0.35) / 0.9, 0.0, 1.0);
	}

	/** Una línea circular suave de radio r y medio grosor w en el plano (u1, u2) actual: brilla en r y se apaga a los lados. */
	private static void line(BufferBuilder b, Vec3d c, double r, double w, int rgb, float a) {
		if (a <= 0.005f || r <= 0) return;
		LawFxClient.ring(b, c, Math.max(0.0, r - w), r, rgb, 0f, a);
		LawFxClient.ring(b, c, r, r + w, rgb, a, 0f);
	}

	/** La derecha y el arriba de la pantalla en ese punto (para dibujar de cara a la cámara y sin girar). */
	private static Vec3d[] screenAxes(Vec3d p) {
		Vec3d view = p.subtract(LawFxClient.cam);
		Vec3d right = view.crossProduct(UP);
		right = right.lengthSquared() < 1e-6 ? new Vec3d(1, 0, 0) : right.normalize();
		Vec3d up = right.crossProduct(view);
		up = up.lengthSquared() < 1e-6 ? UP : up.normalize();
		return new Vec3d[]{right, up};
	}

	/** El punto (x, y) en esa base de pantalla alrededor de s. */
	private static Vec3d pt(Vec3d s, Vec3d right, Vec3d up, double x, double y) {
		return s.add(right.multiply(x)).add(up.multiply(y));
	}

	private static double easeOut(double u) {
		double k = 1.0 - MathHelper.clamp(u, 0.0, 1.0);
		return 1.0 - k * k * k;
	}

	/** Sale pasándose un poco y vuelve (como algo que aparece de golpe). */
	private static double backOut(double u) {
		double c1 = 1.70158, c3 = c1 + 1.0, k = u - 1.0;
		return 1.0 + c3 * k * k * k + c1 * k * k;
	}
}
