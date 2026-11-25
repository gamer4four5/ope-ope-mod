package dev.opeope.client.mera;

import dev.opeope.client.config.OpeSettings;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.model.ModelPart;
import net.minecraft.client.network.AbstractClientPlayerEntity;
import net.minecraft.client.render.entity.model.BipedEntityModel;
import net.minecraft.client.render.entity.model.PlayerEntityModel;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.entity.LivingEntity;
import net.minecraft.util.Arm;
import net.minecraft.util.Hand;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.RotationAxis;

import java.util.HashMap;
import java.util.Map;

/**
 * Los gestos de Ace (Mera Mera no Mi) en 3.ª y 1.ª persona, y las partes del cuerpo que se vuelven fuego.
 * <p>
 * Poses sacadas del anime: el puño echado atrás que se carga de fuego y el puñetazo de Hiken (el brazo entero se
 * vuelve llamas y sale disparado), los dedos en pistola de Higan, los índices cruzados de Jūjika, el brazo alzado que
 * sostiene el sol de Entei y su lanzamiento, el barrido de Enkai, la palma abierta de Kagerō y el salto de Hikyaku.
 * Logia: cuando un golpe atraviesa a Ace, esa parte del cuerpo desaparece un instante y en su lugar hay fuego.
 */
public final class MeraAnim {
	private MeraAnim() {}

	public static final int NONE = -1, WINDUP = 0, PUNCH = 1, FINGER_GUNS = 2, CROSS = 3, RAISE = 4, THROW = 5,
		SWEEP = 6, PALM = 7, HOP = 8;

	/** Partes que se vuelven fuego: bits. */
	public static final int P_HEAD = 1, P_BODY = 2, P_RARM = 4, P_LARM = 8, P_RLEG = 16, P_LLEG = 32, P_ALL = 63;

	private static final class St {
		int pose = NONE;
		double start, dur;
		boolean hold;
		int hidden;
		double hiddenUntil;
		/** El brazo de Hiken hecho fuego (hasta cuándo). */
		double fireArmUntil;
		/** El cuerpo entero hecho chorro de llamas. */
		double dashUntil;
	}

	private static final Map<Integer, St> STATES = new HashMap<>();

	public static void clear() {
		STATES.clear();
	}

	private static St st(int id) {
		return STATES.computeIfAbsent(id, k -> new St());
	}

	private static double now() {
		return dev.opeope.client.room.RoomFxClient.time();
	}

	/** Una pose: hold = se queda hasta release() o hasta dur segundos. */
	public static void pose(int entityId, int pose, double seconds, boolean hold) {
		St s = st(entityId);
		s.pose = pose;
		s.start = now();
		s.dur = Math.max(0.1, seconds);
		s.hold = hold;
	}

	public static void release(int entityId, int pose) {
		St s = STATES.get(entityId);
		if (s != null && s.pose == pose) s.pose = NONE;
	}

	public static int currentPose(int entityId) {
		St s = STATES.get(entityId);
		if (s == null || s.pose == NONE) return NONE;
		if (now() - s.start > s.dur) {
			s.pose = NONE;
			return NONE;
		}
		return s.pose;
	}

	/** Esas partes se vuelven fuego unos segundos (un golpe las atravesó). */
	public static void hide(int entityId, int parts, double seconds) {
		St s = st(entityId);
		double until = now() + seconds;
		if (s.hiddenUntil < now()) s.hidden = 0;
		s.hidden |= parts;
		s.hiddenUntil = Math.max(s.hiddenUntil, until);
	}

	public static void fireArm(int entityId, double seconds) {
		st(entityId).fireArmUntil = now() + seconds;
	}

	public static void dash(int entityId, double seconds) {
		st(entityId).dashUntil = now() + seconds;
	}

	public static boolean dashing(int entityId) {
		St s = STATES.get(entityId);
		return s != null && s.dashUntil > now();
	}

	public static boolean fireArmActive(int entityId) {
		St s = STATES.get(entityId);
		return s != null && s.fireArmUntil > now();
	}

	/** Partes hechas fuego ahora (bits P_*). */
	public static int hiddenParts(int entityId) {
		St s = STATES.get(entityId);
		if (s == null) return 0;
		double t = now();
		int bits = s.hiddenUntil > t ? s.hidden : 0;
		if (s.fireArmUntil > t) bits |= P_RARM;
		if (s.dashUntil > t) bits |= P_ALL;
		return bits;
	}

	/** Avance de la pose actual 0..1 y su peso (sube rápido, se mantiene, baja). */
	private static float weight(St s, double t) {
		double age = t - s.start;
		if (age < 0) return 0f;
		double in = Math.min(0.09, s.dur * 0.25);
		if (age < in) return smooth((float) (age / in));
		if (!s.hold && age > s.dur - 0.14) return smooth((float) ((s.dur - age) / 0.14));
		if (s.hold && age > s.dur - 0.1) return smooth((float) ((s.dur - age) / 0.1));
		return 1f;
	}

	private static float smooth(float x) {
		x = MathHelper.clamp(x, 0f, 1f);
		return x * x * (3f - 2f * x);
	}

	// ------------------------------------------------------------------ 3.ª persona

	/** Se llama al final de BipedEntityModel.setAngles (después de las animaciones de Law). */
	public static void apply(LivingEntity entity, BipedEntityModel<?> model) {
		if (!(entity instanceof AbstractClientPlayerEntity p)) return;
		St s = STATES.get(p.getId());
		if (s == null) return;
		double t = now();
		int pose = currentPose(p.getId());
		if (pose != NONE) applyPose(s, pose, t, model, p.getMainArm(), model.head.pitch);
		int hidden = hiddenParts(p.getId());
		if (hidden != 0) hideParts(model, hidden, p.getMainArm());
	}

	private static void applyPose(St s, int pose, double t, BipedEntityModel<?> model, Arm mainArm, float lookPitch) {
		float w = weight(s, t);
		if (w <= 0.001f) return;
		float u = (float) MathHelper.clamp((t - s.start) / s.dur, 0, 1);
		ModelPart arm = mainArm == Arm.RIGHT ? model.rightArm : model.leftArm;
		ModelPart other = mainArm == Arm.RIGHT ? model.leftArm : model.rightArm;
		float sgn = mainArm == Arm.RIGHT ? 1f : -1f;
		float aim = -MathHelper.HALF_PI + lookPitch;   // el brazo apunta adonde mira
		boolean calm = OpeSettings.reducedMotion();
		switch (pose) {
			case WINDUP -> {
				// el puño atrás, el cuerpo girado y el otro brazo delante en guardia; tiembla de la carga
				float shake = calm ? 0f : MathHelper.sin((float) (t * 55.0)) * 0.03f;
				arm.pitch = MathHelper.lerp(w, arm.pitch, 0.55f + shake);
				arm.yaw = MathHelper.lerp(w, arm.yaw, 0.35f * sgn);
				arm.roll = MathHelper.lerp(w, arm.roll, 0.25f * sgn);
				other.pitch = MathHelper.lerp(w, other.pitch, aim * 0.65f);
				other.yaw = MathHelper.lerp(w, other.yaw, -0.35f * sgn);
				model.body.yaw = MathHelper.lerp(w, model.body.yaw, 0.42f * sgn);
				model.rightLeg.pitch = MathHelper.lerp(w, model.rightLeg.pitch, 0.30f * sgn);
				model.leftLeg.pitch = MathHelper.lerp(w, model.leftLeg.pitch, -0.30f * sgn);
			}
			case PUNCH -> {
				// el golpe sale en el primer cuarto y se queda estirado
				float drive = smooth(u / 0.22f);
				arm.pitch = MathHelper.lerp(w, arm.pitch, MathHelper.lerp(drive, 0.5f, aim));
				arm.yaw = MathHelper.lerp(w, arm.yaw, MathHelper.lerp(drive, 0.35f, -0.08f) * sgn);
				arm.roll = MathHelper.lerp(w, arm.roll, 0f);
				other.pitch = MathHelper.lerp(w, other.pitch, 0.45f);
				other.yaw = MathHelper.lerp(w, other.yaw, 0.1f * sgn);
				model.body.yaw = MathHelper.lerp(w, model.body.yaw, MathHelper.lerp(drive, 0.42f, -0.38f) * sgn);
				model.rightLeg.pitch = MathHelper.lerp(w, model.rightLeg.pitch, -0.35f * sgn);
				model.leftLeg.pitch = MathHelper.lerp(w, model.leftLeg.pitch, 0.35f * sgn);
			}
			case FINGER_GUNS -> {
				// los dos brazos delante, dedos en pistola, retroceso alterno
				float kick = calm ? 0f : (float) Math.max(0, Math.sin(t * 62.8)) * 0.12f;
				float kick2 = calm ? 0f : (float) Math.max(0, -Math.sin(t * 62.8)) * 0.12f;
				arm.pitch = MathHelper.lerp(w, arm.pitch, aim - kick);
				other.pitch = MathHelper.lerp(w, other.pitch, aim - kick2);
				arm.yaw = MathHelper.lerp(w, arm.yaw, -0.12f * sgn);
				other.yaw = MathHelper.lerp(w, other.yaw, 0.12f * sgn);
				arm.roll = MathHelper.lerp(w, arm.roll, 0f);
				other.roll = MathHelper.lerp(w, other.roll, 0f);
			}
			case CROSS -> {
				// índices cruzados delante de la cara
				arm.pitch = MathHelper.lerp(w, arm.pitch, aim + 0.1f);
				other.pitch = MathHelper.lerp(w, other.pitch, aim + 0.1f);
				arm.yaw = MathHelper.lerp(w, arm.yaw, 0.52f * sgn);
				other.yaw = MathHelper.lerp(w, other.yaw, -0.52f * sgn);
				arm.roll = MathHelper.lerp(w, arm.roll, 0f);
				other.roll = MathHelper.lerp(w, other.roll, 0f);
			}
			case RAISE -> {
				// el brazo alzado sosteniendo el sol; el cuerpo se arquea un poco
				float sway = calm ? 0f : MathHelper.sin((float) (t * 4.0)) * 0.04f;
				arm.pitch = MathHelper.lerp(w, arm.pitch, -2.95f + sway);
				arm.yaw = MathHelper.lerp(w, arm.yaw, 0.05f * sgn);
				arm.roll = MathHelper.lerp(w, arm.roll, -0.12f * sgn);
				other.pitch = MathHelper.lerp(w, other.pitch, 0.2f);
				other.roll = MathHelper.lerp(w, other.roll, -0.35f * sgn);
				model.head.pitch = MathHelper.lerp(w * 0.6f, model.head.pitch, -0.35f);
			}
			case THROW -> {
				// del brazo alzado al frente: lanza el sol
				float drive = smooth(u / 0.35f);
				arm.pitch = MathHelper.lerp(w, arm.pitch, MathHelper.lerp(drive, -2.95f, aim));
				arm.yaw = MathHelper.lerp(w, arm.yaw, 0f);
				model.body.yaw = MathHelper.lerp(w, model.body.yaw, MathHelper.lerp(drive, 0.1f, -0.3f) * sgn);
				other.pitch = MathHelper.lerp(w, other.pitch, 0.4f);
			}
			case SWEEP -> {
				// Enkai: el brazo extendido barre a su alrededor y junta las llamas
				float a = MathHelper.lerp(smooth(u), -1.3f, 1.25f);
				arm.pitch = MathHelper.lerp(w, arm.pitch, -1.45f);
				arm.yaw = MathHelper.lerp(w, arm.yaw, a * sgn);
				model.body.yaw = MathHelper.lerp(w, model.body.yaw, a * 0.35f * sgn);
				other.pitch = MathHelper.lerp(w, other.pitch, -0.3f);
			}
			case PALM -> {
				// Kagerō: palma abierta al frente, el brazo tiembla con el chorro
				float jit = calm ? 0f : (MathHelper.sin((float) (t * 47.0)) * 0.035f);
				arm.pitch = MathHelper.lerp(w, arm.pitch, aim + jit);
				arm.yaw = MathHelper.lerp(w, arm.yaw, -0.05f * sgn + jit);
				arm.roll = MathHelper.lerp(w, arm.roll, 0f);
				other.pitch = MathHelper.lerp(w, other.pitch, aim * 0.4f);
				other.yaw = MathHelper.lerp(w, other.yaw, 0.45f * sgn);
				model.body.yaw = MathHelper.lerp(w, model.body.yaw, -0.18f * sgn);
			}
			case HOP -> {
				arm.pitch = MathHelper.lerp(w, arm.pitch, -0.6f);
				other.pitch = MathHelper.lerp(w, other.pitch, -0.6f);
				arm.roll = MathHelper.lerp(w, arm.roll, 0.5f * sgn);
				other.roll = MathHelper.lerp(w, other.roll, -0.5f * sgn);
				model.rightLeg.pitch = MathHelper.lerp(w, model.rightLeg.pitch, -0.7f);
				model.leftLeg.pitch = MathHelper.lerp(w, model.leftLeg.pitch, 0.25f);
			}
			default -> {}
		}
	}

	private static void hideParts(BipedEntityModel<?> model, int bits, Arm mainArm) {
		// el brazo «derecho» de los bits es el brazo principal
		int r = mainArm == Arm.RIGHT ? P_RARM : P_LARM;
		int l = mainArm == Arm.RIGHT ? P_LARM : P_RARM;
		int rb = ((bits & P_RARM) != 0 ? r : 0) | ((bits & P_LARM) != 0 ? l : 0);
		bits = (bits & ~(P_RARM | P_LARM)) | rb;
		if ((bits & P_HEAD) != 0) {
			model.head.visible = false;
			model.hat.visible = false;
		}
		if ((bits & P_BODY) != 0) model.body.visible = false;
		if ((bits & P_RARM) != 0) model.rightArm.visible = false;
		if ((bits & P_LARM) != 0) model.leftArm.visible = false;
		if ((bits & P_RLEG) != 0) model.rightLeg.visible = false;
		if ((bits & P_LLEG) != 0) model.leftLeg.visible = false;
		if (model instanceof PlayerEntityModel<?> pm) {
			if ((bits & P_BODY) != 0) pm.jacket.visible = false;
			if ((bits & P_RARM) != 0) pm.rightSleeve.visible = false;
			if ((bits & P_LARM) != 0) pm.leftSleeve.visible = false;
			if ((bits & P_RLEG) != 0) pm.rightPants.visible = false;
			if ((bits & P_LLEG) != 0) pm.leftPants.visible = false;
		}
	}

	// ------------------------------------------------------------------ 1.ª persona

	/** ¿Se esconde la mano en 1.ª persona? (el brazo de Hiken es fuego, o todo el cuerpo es un chorro de llamas). */
	public static boolean hideFirstPerson(AbstractClientPlayerEntity p, Hand hand) {
		if (hand != Hand.MAIN_HAND) return dashing(p.getId());
		return dashing(p.getId()) || fireArmActive(p.getId());
	}

	public static void firstPerson(AbstractClientPlayerEntity p, Hand hand, MatrixStack matrices) {
		if (hand != Hand.MAIN_HAND) return;
		St s = STATES.get(p.getId());
		if (s == null) return;
		int pose = currentPose(p.getId());
		if (pose == NONE) return;
		double t = now();
		float w = weight(s, t);
		if (w <= 0.001f) return;
		float u = (float) MathHelper.clamp((t - s.start) / s.dur, 0, 1);
		float side = p.getMainArm() == Arm.RIGHT ? 1f : -1f;
		boolean calm = OpeSettings.reducedMotion();
		switch (pose) {
			case WINDUP -> {
				float shake = calm ? 0f : MathHelper.sin((float) (t * 55.0)) * 0.008f;
				matrices.translate(0.10f * side * w + shake, -0.10f * w, 0.18f * w);
				matrices.multiply(RotationAxis.POSITIVE_X.rotationDegrees(-18f * w));
				matrices.multiply(RotationAxis.POSITIVE_Y.rotationDegrees(22f * side * w));
			}
			case PUNCH -> {
				float drive = smooth(u / 0.22f);
				matrices.translate(-0.16f * side * drive * w, 0.06f * w, (0.18f - 0.55f * drive) * w);
				matrices.multiply(RotationAxis.POSITIVE_X.rotationDegrees(8f * drive * w));
			}
			case FINGER_GUNS -> {
				float kick = calm ? 0f : (float) Math.max(0, Math.sin(t * 62.8)) * 0.05f;
				matrices.translate(-0.08f * side * w, 0.10f * w, (-0.20f + kick) * w);
				matrices.multiply(RotationAxis.POSITIVE_X.rotationDegrees(10f * w));
			}
			case CROSS -> {
				matrices.translate(-0.22f * side * w, 0.16f * w, -0.18f * w);
				matrices.multiply(RotationAxis.POSITIVE_Z.rotationDegrees(-32f * side * w));
			}
			case RAISE -> {
				matrices.translate(0.02f * side * w, 0.55f * w, -0.10f * w);
				matrices.multiply(RotationAxis.POSITIVE_X.rotationDegrees(70f * w));
			}
			case THROW -> {
				float drive = smooth(u / 0.35f);
				matrices.translate(-0.06f * side * w, MathHelper.lerp(drive, 0.55f, 0.02f) * w, (-0.10f - 0.30f * drive) * w);
				matrices.multiply(RotationAxis.POSITIVE_X.rotationDegrees(MathHelper.lerp(drive, 70f, 0f) * w));
			}
			case SWEEP -> {
				float a = MathHelper.lerp(smooth(u), -1f, 1f);
				matrices.translate(a * -0.28f * side * w, 0.12f * w, -0.10f * w);
				matrices.multiply(RotationAxis.POSITIVE_Y.rotationDegrees(-a * 30f * side * w));
			}
			case PALM -> {
				float jit = calm ? 0f : MathHelper.sin((float) (t * 47.0)) * 0.012f;
				matrices.translate(-0.12f * side * w + jit, 0.12f * w + jit, -0.22f * w);
				matrices.multiply(RotationAxis.POSITIVE_X.rotationDegrees(22f * w));
			}
			case HOP -> matrices.translate(0f, -0.12f * w, 0.05f * w);
			default -> {}
		}
	}

	/** Para las pruebas automáticas. */
	public static String debug(int entityId) {
		return "pose=" + currentPose(entityId) + " ocultas=" + hiddenParts(entityId);
	}

	static boolean firstPersonView(LivingEntity e) {
		MinecraftClient mc = MinecraftClient.getInstance();
		return e == mc.getCameraEntity() && mc.options.getPerspective().isFirstPerson();
	}
}
