package dev.opeope.client.render;

import dev.opeope.anatomy.Anatomy;
import dev.opeope.anatomy.AnatomyRegistry;
import dev.opeope.client.config.OpeSettings;
import net.minecraft.client.render.BufferBuilder;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.util.Arm;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;

/**
 * La radiografía de Law: el esqueleto y los órganos de un ser, vistos A TRAVÉS de la carne y de las paredes.
 * <p>
 * En el anime, Gamma Knife destroza los órganos por dentro sin dejar herida por fuera (se ve un corte en rayos X con los
 * órganos encendidos) y Scan atraviesa a la gente como rayos X para ver dónde lleva cada uno sus cosas. Aquí:
 * <ul>
 *   <li>{@link #anatomy}: un esqueleto estilizado (cráneo, columna, costillas, cinturas y huesos largos) y los órganos
 *   (el corazón con su latido doble, los pulmones, el estómago y el hígado), encajados en la caja interpolada del ser y
 *   girados con su cuerpo. Hay tres formas: erguida (personas, zombis, aldeanos, gólems, endermans), baja y larga
 *   (cerdos, vacas, lobos, arañas, slimes) y simplificada (muy pequeño o enorme: columna y corazón). Si muere, el
 *   esqueleto cae de lado con el cuerpo.</li>
 *   <li>{@link #heldMarker}: la marca de lo que lleva en las manos (lo que luego se le puede quitar con Shambles).</li>
 *   <li>{@link #rupture}: los órganos que revientan por dentro (Gamma Knife, Shock Wille).</li>
 * </ul>
 * Lo llama {@link LawFxClient} en su segunda pasada, SIN prueba de profundidad, con el mismo BufferBuilder ADITIVO
 * (QUADS, POSITION_COLOR): aquí no se abren ni se cierran buffers ni se toca RenderSystem; solo se usan los ayudantes de
 * geometría de {@link LawFxClient}. Con mezcla aditiva todo suma luz: las cuencas de los ojos son aros, no huecos.
 * Con «menos movimiento» el corazón late sin cambiar de brillo, la marca no late y no saltan chispas.
 */
public final class XRayFx {
	private XRayFx() {}

	private static final double TAU = Math.PI * 2.0;
	/** Las tres formas de cuerpo: erguido, bajo y largo, y simplificado (muy pequeño o enorme). */
	private static final int UPRIGHT = 0, LOW = 1, SIMPLE = 2;

	// El marco del ser que se está dibujando (solo se usa en el hilo de render, como la base u1/u2 de LawFxClient): los
	// pies (ox, oy, oz), su derecha (rx, 0, rz), su frente (fx, 0, fz) y cuánto se ha caído de lado al morir (coseno tc y
	// seno ts). Las medidas del cuerpo van en bloques: x hacia su derecha, y hacia arriba desde los pies, z hacia su frente.
	private static double ox, oy, oz, rx, rz, fx, fz, tc = 1.0, ts;
	/** Ancho de la caja (su lado mayor en horizontal), alto, medio ancho y medio grosor de los huesos. */
	private static double bw, bh, hw, thick;
	private static int shape;
	/** Qué miembros tiene: brazos (o alas), piernas o patas, ocho patas (araña), o es un bípedo tumbado (nadando, gateando). */
	private static boolean arms, legs, spider, lying;
	/** Cuerpo bajo: dónde acaban las patas y a qué altura va el espinazo. */
	private static double legTop, spineY;
	/** El cráneo (altura y avance de su centro, y radio) y el corazón (centro y radio), en el marco del ser. */
	private static double skullY, skullZ, skullR, heartX, heartY, heartZ, heartR;

	// ------------------------------------------------------------------ lo que se llama desde LawFxClient

	/**
	 * La anatomía de e vista por rayos X: los huesos en boneRgb con opacidad a (y un halo más suave) y los órganos en
	 * organRgb, que se encienden con glow (0..1): opacidad a·(0,35 + 0,65·glow); con glow por encima de 0,6 el corazón
	 * destella.
	 */
	static void anatomy(BufferBuilder b, Entity e, int boneRgb, float a, int organRgb, float glow, double now) {
		if (e == null || a <= 0.01f) return;
		frame(e);
		glow = MathHelper.clamp(glow, 0f, 1f);
		float oa = a * (0.35f + 0.65f * glow);
		switch (shape) {
			case UPRIGHT -> {
				uprightBones(b, boneRgb, a);
				uprightOrgans(b, organRgb, oa);
			}
			case LOW -> {
				lowBones(b, boneRgb, a);
				lowOrgans(b, organRgb, oa);
			}
			default -> simpleBones(b, boneRgb, a);
		}
		// cada ser late a su ritmo: con muchos a la vez (Scan) no laten todos juntos
		heart(b, organRgb, oa, glow, now + LawFxClient.hash01(e.getId()) * 0.85);
	}

	/**
	 * Lo que lleva en las manos: un rombo que late y un punto brillante donde va la mano (la principal en su lado, la otra
	 * en el contrario; los que van a cuatro patas, como el zorro, lo llevan en la boca), con una línea fina que sube hasta
	 * una raya de etiqueta por encima del hombro. Con las dos manos vacías no dibuja nada.
	 */
	static void heldMarker(BufferBuilder b, LivingEntity e, int rgb, float a, double now) {
		if (e == null || a <= 0.01f) return;
		boolean main = !e.getMainHandStack().isEmpty(), off = !e.getOffHandStack().isEmpty();
		if (!main && !off) return;
		frame(e);
		boolean calm = OpeSettings.reducedMotion();
		double side = e.getMainArm() == Arm.LEFT ? -1.0 : 1.0;
		if (main) mark(b, side, rgb, a, now, calm, 1.0);
		if (off) mark(b, -side, rgb, a * 0.85f, now + 0.35, calm, 0.8);
	}

	/**
	 * Los órganos que revientan (Gamma Knife, Shock Wille): de 10 a 14 chispas cortas y quebradas salen del corazón hasta
	 * el 60 % del medio cuerpo (sin llegar a la piel: no hay herida por fuera) mientras u va de 0 a 1, con opacidad
	 * (1 − u)², y dos ondas que se abren mirando a la cámara. Con «menos movimiento» (calm), solo las ondas.
	 */
	static void rupture(BufferBuilder b, Entity e, int rgb, float u, int seed, boolean calm) {
		if (e == null) return;
		u = MathHelper.clamp(u, 0f, 1f);
		float fade = (1f - u) * (1f - u);
		if (fade <= 0.01f) return;
		frame(e);
		Vec3d c = at(heartX, heartY, heartZ);
		int hot = LawFxClient.lerpRgb(rgb, LawFxClient.CORE, 0.55f);
		// las dos ondas, una tras otra
		double reach = 0.3 * Math.max(bw, bh);
		for (int k = 0; k < 2; k++) {
			double uk = MathHelper.clamp(u * 1.3 - k * 0.3, 0.0, 1.0);
			if (uk <= 0.0) continue;
			double r = reach * (0.12 + 0.88 * (1.0 - (1.0 - uk) * (1.0 - uk)));
			float ra = fade * (float) (1.0 - 0.5 * uk) * (calm ? 0.55f : 0.85f);
			LawFxClient.faceCam(c);
			LawFxClient.ring(b, c, r * 0.84, r, k == 0 ? hot : rgb, ra * 0.25f, ra);
		}
		if (calm) return;
		// el fogonazo del primer instante, en el corazón
		if (u < 0.2f) {
			float fl = (1f - u / 0.2f) * 0.8f * LawFxClient.flashScale();
			double fr = heartR * (1.5 + 8.0 * u);
			blob(b, c, fr, fr, hot, fl, 0f, 10);
		}
		// las chispas: cada una con su dirección, su velocidad y sus quiebros fijos (no titilan)
		int n = 10 + (int) (LawFxClient.hash01(seed * 31 + 7) * 4.999);
		double ex = 0.3 * bw, ey = 0.3 * bh;
		double ease = 1.0 - (1.0 - u) * (1.0 - u) * (1.0 - u);
		double w = Math.max(0.012, thick * 0.8);
		for (int k = 0; k < n; k++) {
			Vec3d d = LawFxClient.randDir(seed * 131 + k * 977 + 5);
			double sp = 0.7 + 0.3 * LawFxClient.hash01(seed * 17 + k * 389);
			double h1 = ease * sp, h0 = Math.max(0.0, h1 - 0.38);
			Vec3d p0 = c.add(d.x * ex * h0, d.y * ey * h0, d.z * ex * h0);
			Vec3d p1 = c.add(d.x * ex * h1, d.y * ey * h1, d.z * ex * h1);
			spark(b, p0, p1, w, rgb, hot, fade, seed * 7 + k * 53);
		}
	}

	// ------------------------------------------------------------------ el marco del ser

	/** Prepara el marco de e: su caja interpolada, el giro de su cuerpo, si se está cayendo al morir, su forma y sus medidas. */
	private static void frame(Entity e) {
		Box box = LawFxClient.lerpBox(e);
		ox = (box.minX + box.maxX) * 0.5;
		oy = box.minY;
		oz = (box.minZ + box.maxZ) * 0.5;
		bw = Math.max(0.05, Math.max(box.getLengthX(), box.getLengthZ()));
		bh = Math.max(0.05, box.getLengthY());
		hw = bw * 0.5;
		LivingEntity le = e instanceof LivingEntity l ? l : null;
		float yaw = le != null
			? MathHelper.lerpAngleDegrees(LawFxClient.td, le.prevBodyYaw, le.bodyYaw)
			: MathHelper.lerpAngleDegrees(LawFxClient.td, e.prevYaw, e.getYaw());
		double ya = Math.toRadians(yaw), sn = Math.sin(ya), cs = Math.cos(ya);
		// giro 0 = mirando a +Z; su derecha es frente × arriba (quien mira a +Z tiene la mano derecha hacia −X)
		fx = -sn;
		fz = cs;
		rx = -cs;
		rz = -sn;
		thick = MathHelper.clamp(0.022 * (bh / 1.8 + bw / 0.6) * 0.5, 0.0125, 0.045);

		Anatomy an = AnatomyRegistry.get(e.getType());
		int legCount = an != null ? an.legs : -1;
		if (bh < 0.35 || Math.max(bh, bw) > 6.0) shape = SIMPLE;
		// la vaca y el lobo son más altos que anchos, pero van a cuatro patas (el creeper, tan alto, va erguido)
		else if (bh >= 1.15 * bw && !(legCount >= 4 && bh < 2.2 * bw)) shape = UPRIGHT;
		else shape = LOW;
		arms = hasArms(an);
		spider = legCount >= 8;
		lying = shape == LOW && legCount == 2;

		// al morir, el cuerpo cae de lado (hacia su izquierda) girando sobre los pies, como lo dibuja LivingEntityRenderer
		double tilt = 0.0;
		if (le != null && le.deathTime > 0) {
			double f = Math.min(1.0, Math.sqrt(Math.max(0.0, (le.deathTime + LawFxClient.td - 1.0) / 20.0 * 1.6)));
			tilt = Math.toRadians(f * (spider ? 180.0 : 90.0));
		}
		tc = Math.cos(tilt);
		ts = Math.sin(tilt);

		if (shape == UPRIGHT) {
			legs = legCount != 0;   // el allay, el vex o el gólem de nieve no tienen piernas
			skullR = Math.min(0.075 * bh, 0.42 * bw);
			skullY = bh - skullR * 1.1;
			skullZ = 0.0;
			heartX = -0.3 * hw;   // a su izquierda
			heartY = 0.64 * bh;
			heartZ = 0.22 * hw;
			heartR = 0.11 * Math.min(bw, bh * 0.4);
		} else if (shape == LOW) {
			// sin anatomía conocida, un cubo (slime, cubo de magma, shulker) o algo que nada (un pez) no tiene patas
			legs = an != null ? an.legs > 0 : !(Math.abs(bh - bw) < 0.1 * bw || e.isSubmergedInWater());
			double frac = an != null && an.legs > 0 ? MathHelper.clamp(an.legHeight / an.height, 0.3, 0.55) : (legs ? 0.42 : 0.25);
			legTop = frac * bh;
			spineY = Math.min(legTop + (bh - legTop) * 0.55, 0.82 * bh);
			skullR = Math.min(0.18 * bh, 0.2 * bw);
			skullZ = hw - skullR;
			skullY = Math.min(bh - skullR, spineY + 0.12 * bh);
			heartX = -0.12 * hw;
			heartY = spineY - (spineY - legTop) * 0.55;
			heartZ = 0.0;
			heartR = 0.1 * Math.min(bw, bh);
		} else {
			legs = false;
			boolean tall = bh > 1.15 * bw;
			skullR = 0.2 * Math.min(bw, bh);
			skullY = tall ? bh - skullR : 0.55 * bh;
			skullZ = tall ? 0.0 : 0.85 * hw;
			heartX = -0.1 * hw;
			heartY = tall ? 0.62 * bh : 0.5 * bh;
			heartZ = tall ? 0.15 * hw : 0.2 * hw;
			heartR = MathHelper.clamp(0.12 * Math.min(bw, bh), 0.03, 0.6);
		}
	}

	/** ¿Tiene brazos (o alas) según su anatomía? Si no se conoce, se supone que sí. */
	private static boolean hasArms(Anatomy an) {
		if (an == null) return true;
		for (Anatomy.Segment s : an.segments.values()) {
			if (s.has("main_hand") || s.has("off_hand") || s.has("flight")) return true;
		}
		return false;
	}

	/** Un vector (no un punto) en el marco del ser: x a su derecha, y hacia arriba, z hacia su frente. */
	private static Vec3d dir(double x, double y, double z) {
		double sx = x * tc - y * ts, sy = x * ts + y * tc;
		return new Vec3d(rx * sx + fx * z, sy, rz * sx + fz * z);
	}

	/** Un punto del ser en su marco: x a su derecha, y hacia arriba desde los pies, z hacia su frente. */
	private static Vec3d at(double x, double y, double z) {
		double sx = x * tc - y * ts, sy = x * ts + y * tc;
		return new Vec3d(ox + rx * sx + fx * z, oy + sy, oz + rz * sx + fz * z);
	}

	// ------------------------------------------------------------------ los esqueletos

	/** Erguido: cráneo, cuello, columna, cinco pares de costillas, esternón, clavículas, brazos, pelvis y piernas. */
	private static void uprightBones(BufferBuilder b, int rgb, float a) {
		double h = bh, w = thick, rs = skullR;
		// el cráneo (un aro que mira a la cámara), la mandíbula que asoma debajo y las cuencas de los ojos
		hoop(b, at(0, skullY + 0.1 * rs, skullZ), rs * 0.9, rs * 0.85, w, rgb, a, 0.0, TAU, 12, true);
		hoop(b, at(0, skullY - 0.2 * rs, skullZ + 0.15 * rs), rs * 0.62, rs * 0.72, w * 0.9, rgb, a * 0.9f,
			Math.PI * 0.12, Math.PI * 0.76, 5, true);
		for (int s = -1; s <= 1; s += 2) {
			hoop(b, at(s * rs * 0.36, skullY + 0.02 * rs, skullZ + rs * 0.72), rs * 0.2, rs * 0.18, w * 0.6, rgb, a * 0.75f,
				0.0, TAU, 5, false);
		}
		// el cuello y la columna, hacia la espalda: vértebras sueltas, cada una con su apófisis de lado a lado
		double zs = -0.22 * hw, c7 = 0.775 * h, pelvis = 0.45 * h;
		double tick = Math.max(w * 2.0, 0.1 * hw);
		vertebrae(b, at(0, skullY - 0.8 * rs, zs * 0.4), at(0, c7, zs), 2, dir(tick * 0.7, 0, 0), w * 0.9, rgb, a);
		vertebrae(b, at(0, c7, zs), at(0, pelvis, zs), 7, dir(tick, 0, 0), w * 1.1, rgb, a);
		// la caja torácica: cinco pares de costillas que salen de la columna, rodean el pecho y bajan hacia el esternón
		double zf = 0.5 * hw;
		for (int i = 0; i < 5; i++) {
			double reach = hw * (0.5 + 0.2 * Math.min(1.0, (i + 1) / 3.0));
			ribPair(b, (0.745 - 0.043 * i) * h, zs, reach, zf - zs, 0.05 * h, w * 0.8, rgb, a);
		}
		bone(b, at(0, 0.735 * h, zf), at(0, 0.6 * h, zf), w * 0.9, rgb, a * 0.9f);
		// la cintura escapular (las clavículas) y los brazos: del hombro al codo y a la muñeca (~45 % de la altura)
		double sh = 0.765 * h;
		for (int s = -1; s <= 1; s += 2) {
			Vec3d shoulder = at(s * 0.9 * hw, sh, 0);
			bone(b, at(0, 0.752 * h, zf * 0.85), shoulder, w * 0.85, rgb, a);
			if (!arms) continue;
			Vec3d elbow = at(s * 0.97 * hw, 0.6 * h, 0.05 * hw), wrist = at(s * hw, 0.45 * h, 0.12 * hw);
			longBone(b, shoulder, elbow, w, rgb, a);
			longBone(b, elbow, wrist, w * 0.85, rgb, a);
			for (int f = -1; f <= 1; f += 2) {
				LawFxClient.ribbon(b, wrist, at(s * hw, 0.41 * h, (0.12 + 0.06 * f) * hw), w * 0.5, w * 0.3, rgb, a * 0.8f, a * 0.5f);
			}
		}
		// la pelvis, un cuenco en V bajo la columna, y las piernas: fémur, tibia y el pie hacia delante
		Vec3d sacrum = at(0, pelvis, zs), pubis = at(0, 0.37 * h, 0.3 * hw);
		for (int s = -1; s <= 1; s += 2) {
			Vec3d crest = at(s * 0.62 * hw, 0.475 * h, 0.05 * hw), hip = at(s * 0.45 * hw, 0.4 * h, 0.12 * hw);
			bone(b, sacrum, crest, w, rgb, a);
			bone(b, crest, hip, w, rgb, a);
			bone(b, hip, pubis, w * 0.9, rgb, a);
			if (!legs) continue;
			Vec3d knee = at(s * 0.4 * hw, 0.21 * h, 0.1 * hw), ankle = at(s * 0.38 * hw, 0.035 * h, 0);
			longBone(b, hip, knee, w * 1.15, rgb, a);
			longBone(b, knee, ankle, w, rgb, a);
			bone(b, ankle, at(s * 0.38 * hw, 0.012 * h, 0.5 * hw), w * 0.8, rgb, a * 0.85f);
		}
	}

	/** Bajo y largo: espinazo horizontal, cráneo delante, costillas colgando en la mitad delantera, cinturas y patas. */
	private static void lowBones(BufferBuilder b, int rgb, float a) {
		double h = bh, w = thick, rs = skullR;
		double zTail = -0.92 * hw, zNeck = 0.3 * hw, zFront = 0.12 * hw, zHind = -0.66 * hw;
		// el espinazo, de la cola al cuello, con las apófisis hacia arriba
		Vec3d neck = at(0, spineY, zNeck);
		vertebrae(b, at(0, spineY, zTail), neck, 7, dir(0, Math.max(w * 2.0, 0.05 * h), 0), w * 1.1, rgb, a);
		// el cuello sube al cráneo, en la punta delantera; el hocico y la mandíbula van hacia delante
		bone(b, neck, at(0, skullY - rs * 0.5, skullZ - rs * 0.55), w, rgb, a);
		hoop(b, at(0, skullY, skullZ), rs, rs * 0.85, w, rgb, a, 0.0, TAU, 12, true);
		bone(b, at(0, skullY + rs * 0.15, skullZ + rs * 0.55), at(0, skullY - rs * 0.25, skullZ + rs * 1.15), w * 0.85, rgb, a * 0.9f);
		bone(b, at(0, skullY - rs * 0.75, skullZ - rs * 0.2), at(0, skullY - rs * 0.62, skullZ + rs * 1.05), w * 0.8, rgb, a * 0.85f);
		for (int s = -1; s <= 1; s += 2) {
			hoop(b, at(s * rs * 0.42, skullY + rs * 0.12, skullZ + rs * 0.45), rs * 0.2, rs * 0.2, w * 0.6, rgb, a * 0.75f,
				0.0, TAU, 5, false);
		}
		// las costillas: arcos verticales en la mitad delantera del tronco, del espinazo hacia el vientre y algo hacia atrás
		double depth = (spineY - legTop) * 0.92;
		for (int i = 0; i < 5; i++) {
			double reach = 0.6 * hw * (0.82 + 0.18 * Math.sin(Math.PI * (i + 0.5) / 5.0));
			ribPair(b, spineY, (0.2 - 0.12 * i) * hw, reach, -0.1 * hw, depth, w * 0.8, rgb, a);
		}
		if (!legs) return;
		if (spider) {
			// ocho patas abiertas: suben a la rodilla, por encima del cuerpo, y bajan lejos de él
			for (int s = -1; s <= 1; s += 2) {
				for (int j = 0; j < 4; j++) {
					double z = (0.3 - 0.2 * j) * hw;
					Vec3d root = at(s * 0.3 * hw, spineY - 0.05 * h, z);
					Vec3d knee = at(s * 0.7 * hw, Math.min(h, spineY + 0.2 * h), z + (0.15 - 0.1 * j) * hw);
					Vec3d foot = at(s * 0.98 * hw, 0.0, z + (0.3 - 0.2 * j) * hw);
					longBone(b, root, knee, w * 0.8, rgb, a);
					longBone(b, knee, foot, w * 0.7, rgb, a);
				}
			}
			return;
		}
		if (lying) {
			// un bípedo tumbado (nadando, gateando): los brazos hacia delante y las piernas hacia atrás
			for (int s = -1; s <= 1; s += 2) {
				double x = s * 0.45 * hw;
				longBone(b, at(x, spineY - 0.04 * h, zNeck - 0.1 * hw), at(x * 1.1, spineY - 0.02 * h, 0.98 * hw), w * 0.9, rgb, a);
				longBone(b, at(x * 0.7, spineY - 0.05 * h, zTail + 0.25 * hw), at(x * 0.8, spineY - 0.12 * h, -0.99 * hw), w, rgb, a);
			}
			return;
		}
		// la cintura escapular y la pelvis, y las cuatro patas: hombro → codo → pezuña, cadera → rodilla → pie
		Vec3d sacrum = at(0, spineY, zHind - 0.06 * hw);
		Vec3d shL = at(-0.42 * hw, spineY - 0.06 * h, zFront), shR = at(0.42 * hw, spineY - 0.06 * h, zFront);
		bone(b, shL, shR, w * 0.85, rgb, a);
		for (int s = -1; s <= 1; s += 2) {
			double x = s * 0.42 * hw;
			Vec3d shoulder = s < 0 ? shL : shR;
			Vec3d elbow = at(x * 1.05, legTop, zFront - 0.05 * hw);
			longBone(b, shoulder, elbow, w, rgb, a);
			longBone(b, elbow, at(x, 0.01 * h, zFront), w * 0.85, rgb, a);
			Vec3d hip = at(x, spineY - 0.08 * h, zHind), stifle = at(x * 1.05, legTop, zHind + 0.08 * hw);
			bone(b, sacrum, hip, w, rgb, a);
			longBone(b, hip, stifle, w * 1.1, rgb, a);
			longBone(b, stifle, at(x, 0.01 * h, zHind - 0.04 * hw), w * 0.9, rgb, a);
		}
	}

	/** Muy pequeño o enorme: solo la columna, a lo largo de su lado mayor (el corazón lo pone anatomy). */
	private static void simpleBones(BufferBuilder b, int rgb, float a) {
		double w = thick;
		if (bh > 1.15 * bw) {
			vertebrae(b, at(0, 0.92 * bh, -0.15 * hw), at(0, 0.3 * bh, -0.15 * hw), 6, dir(Math.max(w * 2.0, 0.12 * hw), 0, 0),
				w * 1.1, rgb, a);
		} else {
			vertebrae(b, at(0, 0.55 * bh, -0.85 * hw), at(0, 0.55 * bh, 0.85 * hw), 6, dir(0, Math.max(w * 2.0, 0.12 * bh), 0),
				w * 1.1, rgb, a);
		}
	}

	// ------------------------------------------------------------------ los órganos

	/** Erguido: los pulmones a los lados del pecho y, bajo el diafragma, el estómago (a su izquierda) y el hígado. */
	private static void uprightOrgans(BufferBuilder b, int rgb, float oa) {
		if (oa <= 0.01f) return;
		double h = bh;
		for (int s = -1; s <= 1; s += 2) {
			Vec3d lung = at(s * 0.42 * hw, 0.665 * h, 0.02 * hw);
			double ra = 0.3 * hw, rb = 0.085 * h;
			blob(b, lung, ra, rb, rgb, oa * 0.32f, oa * 0.06f, 8);
			hoop(b, lung, ra, rb, thick * 0.45, rgb, oa * 0.45f, 0.0, TAU, 7, false);
		}
		blob(b, at(-0.3 * hw, 0.525 * h, 0.15 * hw), 0.3 * hw, 0.04 * h, rgb, oa * 0.3f, oa * 0.04f, 6);
		blob(b, at(0.3 * hw, 0.55 * h, 0.1 * hw), 0.38 * hw, 0.035 * h, rgb, oa * 0.3f, oa * 0.04f, 6);
	}

	/** Bajo y largo: los pulmones a los lados del pecho y la panza detrás. */
	private static void lowOrgans(BufferBuilder b, int rgb, float oa) {
		if (oa <= 0.01f) return;
		double depth = Math.max(0.05, spineY - legTop);
		for (int s = -1; s <= 1; s += 2) {
			blob(b, at(s * 0.32 * hw, spineY - depth * 0.38, -0.08 * hw), 0.26 * hw, depth * 0.3, rgb, oa * 0.3f, oa * 0.05f, 8);
		}
		blob(b, at(0.05 * hw, spineY - depth * 0.55, -0.5 * hw), 0.3 * hw, depth * 0.32, rgb, oa * 0.28f, oa * 0.04f, 8);
	}

	/**
	 * El corazón, con su latido doble («pum-pum»): crece y brilla en cada golpe (con «menos movimiento» apenas crece y no
	 * cambia de brillo). Con glow por encima de 0,6 destella, más o menos según el nivel de destellos.
	 */
	private static void heart(BufferBuilder b, int rgb, float oa, float glow, double now) {
		if (oa <= 0.01f) return;
		boolean calm = OpeSettings.reducedMotion();
		double v = beat(now);
		double r = heartR * (1.0 + (calm ? 0.05 : 0.18) * v);
		float k = calm ? 1f : (float) (0.8 + 0.35 * v);
		Vec3d c = at(heartX, heartY, heartZ);
		int hot = LawFxClient.lerpRgb(rgb, LawFxClient.CORE, 0.45f);
		blob(b, c, r, r * 1.15, rgb, oa * 0.75f * k, oa * 0.12f, 10);
		hoop(b, c, r, r * 1.15, thick * 0.5, rgb, oa * 0.7f * k, 0.0, TAU, 10, false);
		blob(b, c, r * 0.45, r * 0.5, hot, oa * 0.8f * k, 0f, 6);
		if (glow > 0.6f) {
			float f = (glow - 0.6f) / 0.4f * LawFxClient.flashScale();
			blob(b, c, r * 2.0, r * 2.0, hot, oa * 0.8f * f, 0f, 8);
		}
	}

	/** El latido doble: 0 en reposo, 1 en el primer golpe y 0,7 en el segundo, unas 70 veces por minuto. */
	private static double beat(double now) {
		double p = now / 0.85;
		p -= Math.floor(p);
		double d1 = (p - 0.12) / 0.05, d2 = (p - 0.32) / 0.055;
		return Math.max(Math.exp(-d1 * d1), 0.7 * Math.exp(-d2 * d2));
	}

	// ------------------------------------------------------------------ la marca de lo que lleva en la mano

	/** La marca de una mano (side: +1 su derecha, −1 su izquierda): el rombo que late, el punto brillante y la etiqueta. */
	private static void mark(BufferBuilder b, double side, int rgb, float a, double now, boolean calm, double size) {
		Vec3d p;
		double top;
		if (shape == UPRIGHT) {
			p = at(side * hw, 0.55 * bh, 0.3 * hw);
			top = 0.9 * bh;
		} else if (shape == LOW) {
			// a cuatro patas (el zorro, el delfín) lo llevan en la boca
			p = at(side * skullR * 0.3, skullY - skullR * 0.45, skullZ + skullR * 0.95);
			top = bh + 0.2;
		} else {
			p = at(side * hw * 0.8, 0.6 * bh, 0.2 * hw);
			top = bh + 0.2;
		}
		double s = MathHelper.clamp(bh / 1.8, 0.45, 1.6) * size;
		double pulse = calm ? 0.5 : 0.5 + 0.5 * Math.sin(now * 5.5);
		double r = (0.075 + 0.03 * pulse) * s, lw = 0.012 * s;
		float pa = a * (float) (0.7 + 0.3 * pulse);
		// el rombo, mirando a la cámara: arriba, derecha, abajo e izquierda de la pantalla
		LawFxClient.faceCam(p);
		double ux = LawFxClient.u1x, uy = LawFxClient.u1y, uz = LawFxClient.u1z;
		double vx = LawFxClient.u2x, vy = LawFxClient.u2y, vz = LawFxClient.u2z;
		Vec3d pn = p.add(-vx * r, -vy * r, -vz * r), pe = p.add(ux * r, uy * r, uz * r);
		Vec3d ps = p.add(vx * r, vy * r, vz * r), pw = p.add(-ux * r, -uy * r, -uz * r);
		LawFxClient.ribbon(b, pn, pe, lw, lw, rgb, pa, pa);
		LawFxClient.ribbon(b, pe, ps, lw, lw, rgb, pa, pa);
		LawFxClient.ribbon(b, ps, pw, lw, lw, rgb, pa, pa);
		LawFxClient.ribbon(b, pw, pn, lw, lw, rgb, pa, pa);
		// el punto brillante del centro
		blob(b, p, 0.035 * s, 0.035 * s, LawFxClient.lerpRgb(rgb, LawFxClient.CORE, 0.55f), pa, 0f, 8);
		// la línea fina que sube hasta la etiqueta, por encima del hombro, y la raya de la etiqueta hacia fuera del cuerpo
		// (en pantalla, para que se lea desde cualquier lado)
		Vec3d up = new Vec3d(p.x, oy + Math.max(top, p.y - oy + 0.15), p.z);
		LawFxClient.ribbon(b, pn, up, lw * 0.5, lw * 0.5, rgb, a * 0.55f, a * 0.35f);
		double out = (up.x - ox) * ux + (up.z - oz) * uz >= 0.0 ? 1.0 : -1.0;
		double len = 0.22 * s * out;
		Vec3d tip = up.add(ux * len, uy * len, uz * len);
		LawFxClient.ribbon(b, up, tip, lw * 0.9, lw * 0.9, rgb, a * 0.8f, a * 0.8f);
		blob(b, tip, 0.02 * s, 0.02 * s, rgb, a * 0.8f, 0f, 6);
	}

	// ------------------------------------------------------------------ primitivas

	/** Un hueso: el trazo fino y su halo, más ancho y suave. */
	private static void bone(BufferBuilder b, Vec3d p0, Vec3d p1, double w, int rgb, float a) {
		LawFxClient.ribbon(b, p0, p1, w * 2.6, w * 2.6, rgb, a * 0.25f, a * 0.25f);
		LawFxClient.ribbon(b, p0, p1, w, w, rgb, a, a);
	}

	/** Un hueso largo (húmero, fémur…): más grueso en las puntas que en el medio, con su halo. */
	private static void longBone(BufferBuilder b, Vec3d p0, Vec3d p1, double w, int rgb, float a) {
		Vec3d m = p0.add(p1).multiply(0.5);
		LawFxClient.ribbon(b, p0, p1, w * 2.6, w * 2.6, rgb, a * 0.25f, a * 0.25f);
		LawFxClient.ribbon(b, p0, m, w * 1.3, w * 0.7, rgb, a, a * 0.85f);
		LawFxClient.ribbon(b, m, p1, w * 0.7, w * 1.3, rgb, a * 0.85f, a);
	}

	/** Una columna de p a q: n vértebras con huecos entre ellas, cada una con su apófisis (de −tick a +tick), y un halo seguido. */
	private static void vertebrae(BufferBuilder b, Vec3d p, Vec3d q, int n, Vec3d tick, double w, int rgb, float a) {
		LawFxClient.ribbon(b, p, q, w * 2.6, w * 2.6, rgb, a * 0.25f, a * 0.25f);
		Vec3d d = q.subtract(p);
		for (int i = 0; i < n; i++) {
			Vec3d v0 = p.add(d.multiply((i + 0.12) / n)), v1 = p.add(d.multiply((i + 0.88) / n));
			Vec3d m = p.add(d.multiply((i + 0.5) / n));
			LawFxClient.ribbon(b, v0, v1, w, w, rgb, a, a);
			LawFxClient.ribbon(b, m.subtract(tick), m.add(tick), w * 0.55, w * 0.55, rgb, a * 0.8f, a * 0.8f);
		}
	}

	/**
	 * Un par de costillas: salen de la columna en (0, y, z), se abren hasta reach a cada lado y, mientras rodean el cuerpo,
	 * avanzan dz y bajan dy (en un ser erguido van de la espalda al esternón; a cuatro patas, del espinazo al vientre).
	 * De frente se ven como los arcos de una radiografía de tórax.
	 */
	private static void ribPair(BufferBuilder b, double y, double z, double reach, double dz, double dy, double w, int rgb, float a) {
		for (int s = -1; s <= 1; s += 2) {
			Vec3d prev = null;
			for (int i = 0; i <= 3; i++) {
				double th = Math.PI * (0.07 + 0.27 * i), k = (1.0 - Math.cos(th)) * 0.5;
				Vec3d p = at(s * reach * Math.sin(th), y - dy * k, z + dz * k);
				if (prev != null) bone(b, prev, p, w, rgb, a);
				prev = p;
			}
		}
	}

	/** Una chispa corta y quebrada de p0 a p1: tres tramos con quiebros fijos, halo del color y núcleo casi blanco. */
	private static void spark(BufferBuilder b, Vec3d p0, Vec3d p1, double w, int rgb, int hot, float a, int seed) {
		Vec3d d = p1.subtract(p0);
		double len = d.length();
		if (len < 0.01 || a <= 0.01f) return;
		// dos perpendiculares a la chispa, para los quiebros
		Vec3d s1 = Math.abs(d.y) < 0.9 * len ? new Vec3d(-d.z, 0, d.x).normalize() : new Vec3d(1, 0, 0);
		Vec3d s2 = d.crossProduct(s1).normalize();
		Vec3d prev = p0;
		for (int i = 1; i <= 3; i++) {
			Vec3d next = p1;
			if (i < 3) {
				double j1 = LawFxClient.hashSigned(seed + i * 41) * 0.22 * len, j2 = LawFxClient.hashSigned(seed + i * 73) * 0.22 * len;
				next = p0.add(d.multiply(i / 3.0)).add(s1.multiply(j1)).add(s2.multiply(j2));
			}
			LawFxClient.ribbon(b, prev, next, w * 2.2, w * 2.2, rgb, a * 0.4f, a * 0.4f);
			LawFxClient.ribbon(b, prev, next, w * 0.8, w * 0.5, hot, a, a * 0.85f);
			prev = next;
		}
	}

	/**
	 * Un arco de elipse que mira a la cámara, de radios ra (en horizontal de pantalla) y rb (en vertical), con medio grosor
	 * w, desde el ángulo start (0 = derecha de la pantalla, π/2 = abajo) con la amplitud span; con glow, además el halo
	 * (más ancho y a un cuarto de opacidad).
	 */
	private static void hoop(BufferBuilder b, Vec3d c, double ra, double rb, double w, int rgb, float a, double start, double span,
							 int segs, boolean glow) {
		if (a <= 0.003f || segs <= 0) return;
		LawFxClient.faceCam(c);
		int passes = glow ? 2 : 1;
		for (int pass = 0; pass < passes; pass++) {
			double ww = pass == 0 ? w : w * 2.6;
			float aa = pass == 0 ? a : a * 0.25f;
			double ia = Math.max(0.0, ra - ww), ib = Math.max(0.0, rb - ww), oa = ra + ww, ob = rb + ww;
			for (int i = 0; i < segs; i++) {
				double t0 = start + span * i / segs, t1 = start + span * (i + 1) / segs;
				double c0 = Math.cos(t0), s0 = Math.sin(t0), c1 = Math.cos(t1), s1 = Math.sin(t1);
				vtxAt(b, c, ia * c0, ib * s0, rgb, aa);
				vtxAt(b, c, oa * c0, ob * s0, rgb, aa);
				vtxAt(b, c, oa * c1, ob * s1, rgb, aa);
				vtxAt(b, c, ia * c1, ib * s1, rgb, aa);
			}
		}
	}

	/** Una mancha de luz que mira a la cámara: elipse de radios ra (horizontal) y rb (vertical), aIn en el centro y aOut en el borde. */
	private static void blob(BufferBuilder b, Vec3d c, double ra, double rb, int rgb, float aIn, float aOut, int segs) {
		if ((aIn <= 0.003f && aOut <= 0.003f) || ra <= 0.0 || rb <= 0.0 || segs <= 0) return;
		LawFxClient.faceCam(c);
		for (int i = 0; i < segs; i++) {
			double t0 = TAU * i / segs, t1 = TAU * (i + 1) / segs;
			vtxAt(b, c, 0.0, 0.0, rgb, aIn);
			vtxAt(b, c, ra * Math.cos(t0), rb * Math.sin(t0), rgb, aOut);
			vtxAt(b, c, ra * Math.cos(t1), rb * Math.sin(t1), rgb, aOut);
			vtxAt(b, c, 0.0, 0.0, rgb, aIn);
		}
	}

	/** Un vértice en c + u1·p + u2·q (u1 y u2, los ejes de pantalla que acaba de fijar faceCam). */
	private static void vtxAt(BufferBuilder b, Vec3d c, double p, double q, int rgb, float a) {
		LawFxClient.vtx(b, c.x + LawFxClient.u1x * p + LawFxClient.u2x * q, c.y + LawFxClient.u1y * p + LawFxClient.u2y * q,
			c.z + LawFxClient.u1z * p + LawFxClient.u2z * q, rgb, a);
	}
}
