package dev.opeope.client.render;

import dev.opeope.client.config.OpeSettings;
import dev.opeope.registry.ModItems;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.AbstractClientPlayerEntity;
import net.minecraft.client.render.entity.model.BipedEntityModel;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.entity.EntityPose;
import net.minecraft.entity.LivingEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.SwordItem;
import net.minecraft.util.Arm;
import net.minecraft.util.Hand;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.RotationAxis;
import net.minecraft.util.math.Vec3d;

import java.util.HashMap;
import java.util.Map;

/**
 * Animaciones procedimentales de locomoción y combate en 3.ª y 1.ª persona para el personaje del jugador:
 * inclinación realista al esprintar, balanceo lateral en giros, absorción de aterrizaje, pose aérea atlética,
 * porte de Kikoku estilo espadachín al correr y chasquido de Shambles.
 */
public final class PlayerAnimClient {
	private PlayerAnimClient() {}

	private static final class State {
		double lastNanos;
		float fwdSpeed;
		float latSpeed;
		float turnRate;
		float sprintBlend;
		float airBlend;
		float fallSpeed;
		float landSquash;
		float shamblesGesture;
		float prevYaw;
		boolean wasInAir;
		double prevVy;
		float lastLeanDeg;
		float lastBankDeg;
		float stridePhase;
		/** Gesto de técnica de Law en curso (GESTURE_*), su avance 0→1 y cuánto dura en segundos. */
		int gestureKind = -1;
		float gestureT = 1f;
		float gestureDur = 0.5f;
		long gestureStartNanos;
		int lastApplyKind = -9;
	}

	private static final Map<Integer, State> STATES = new HashMap<>();

	/**
	 * Gestos de Law sacados del anime: la palma abierta del ROOM, el índice alzado de Takt, la estocada (Gamma Knife,
	 * Injection Shot, Mes), las dos palmas del Counter Shock, el tajo diagonal con Kikoku, el barrido del Curtain y la
	 * estocada hacia abajo de Puncture Wille.
	 */
	public static final int GESTURE_PALM = 0, GESTURE_INDEX = 1, GESTURE_THRUST = 2, GESTURE_TWO_PALMS = 3,
		GESTURE_SLASH = 4, GESTURE_SWEEP = 5, GESTURE_OVERHEAD = 6;
	private static final float[] GESTURE_SECONDS = {0.75f, 0.6f, 0.45f, 0.55f, 0.42f, 0.6f, 0.85f};

	public static void clear() {
		STATES.clear();
	}

	/** Arranca un gesto en cualquier jugador (también en los demás: el servidor manda quién usó la técnica). */
	public static void triggerGesture(int entityId, int kind) {
		if (kind < 0 || kind >= GESTURE_SECONDS.length) return;
		State st = STATES.computeIfAbsent(entityId, k -> new State());
		st.gestureKind = kind;
		st.gestureT = 0f;
		st.gestureDur = GESTURE_SECONDS[kind];
		st.gestureStartNanos = System.nanoTime();
	}

	/** El gesto avanza con el reloj (no con los fotogramas en que se dibuja el modelo: en 1.ª persona no se dibuja). */
	private static void advanceGesture(State st) {
		if (st.gestureKind < 0) return;
		st.gestureT = (float) Math.min(1.0, (System.nanoTime() - st.gestureStartNanos) / 1e9 / Math.max(0.1f, st.gestureDur));
		if (st.gestureT >= 1f) st.gestureKind = -1;
	}

	/** Para las pruebas: el gesto de un jugador ahora («tipo t peso» o «-»). */
	public static String gestureDebug(int entityId) {
		State st = STATES.get(entityId);
		if (st == null) return "sin estado";
		advanceGesture(st);
		return st.gestureKind < 0 ? "-" : st.gestureKind + " t=" + st.gestureT + " w=" + gestureWeight(st) + " lastApply=" + st.lastApplyKind;
	}

	public static void triggerGestureSelf(int kind) {
		MinecraftClient mc = MinecraftClient.getInstance();
		if (mc.player != null) triggerGesture(mc.player.getId(), kind);
	}

	/** Cuánto pesa el gesto ahora (sube rápido, se sostiene y baja), 0 si no hay. */
	private static float gestureWeight(State st) {
		if (st.gestureKind < 0 || st.gestureT >= 1f) return 0f;
		float t = st.gestureT;
		if (t < 0.18f) return smooth(t / 0.18f);
		if (t > 0.72f) return smooth((1f - t) / 0.28f);
		return 1f;
	}

	private static float smooth(float x) {
		x = MathHelper.clamp(x, 0f, 1f);
		return x * x * (3f - 2f * x);
	}

	public static void triggerShamblesGesture() {
		MinecraftClient mc = MinecraftClient.getInstance();
		if (mc.player == null) return;
		State st = STATES.computeIfAbsent(mc.player.getId(), k -> new State());
		st.shamblesGesture = 1.0f;
	}

	private static boolean isGravitySurfing(AbstractClientPlayerEntity p) {
		return p.getVehicle() instanceof dev.opeope.entity.PieceEntity pe && pe.isGravitySurf();
	}

	private static boolean skipPlayerPose(AbstractClientPlayerEntity p) {
		return p.isSleeping() || p.isSwimming() || p.isFallFlying() || (p.hasVehicle() && !isGravitySurfing(p))
			|| p.isClimbing() || p.isInSneakingPose() || p.getPose() == EntityPose.DYING;
	}

	private static State update(AbstractClientPlayerEntity p, float tickDelta) {
		State st = STATES.computeIfAbsent(p.getId(), k -> {
			State s = new State();
			s.prevYaw = p.getYaw();
			return s;
		});
		long nowNanos = System.nanoTime();
		double dt = st.lastNanos <= 0 ? 0.016 : Math.min(0.05, Math.max(0.0005, (nowNanos - st.lastNanos) / 1e9));
		st.lastNanos = nowNanos;
		MinecraftClient mc = MinecraftClient.getInstance();
		if (mc != null && mc.isPaused()) return st;

		double yawRad = Math.toRadians(p.getBodyYaw());
		double fx = -Math.sin(yawRad), fz = Math.cos(yawRad);
		double rx = -fz, rz = fx;
		boolean surf = isGravitySurfing(p);
		Vec3d v = surf && p.getVehicle() != null ? p.getVehicle().getVelocity() : p.getVelocity();
		float rawFwd = (float) (v.x * fx + v.z * fz);
		float rawLat = (float) (v.x * rx + v.z * rz);
		float horiz = (float) Math.sqrt(v.x * v.x + v.z * v.z);

		float yawNow = p.getYaw();
		float dyaw = MathHelper.wrapDegrees(yawNow - st.prevYaw);
		st.prevYaw = yawNow;
		float instTurnRate = MathHelper.clamp((float) (dyaw / Math.max(0.016, dt)), -220f, 220f);

		float kMove = (float) (1.0 - Math.exp(-dt * 12.0));
		float kTurn = (float) (1.0 - Math.exp(-dt * 9.0));
		float kSprint = (float) (1.0 - Math.exp(-dt * 8.0));

		st.fwdSpeed += (rawFwd - st.fwdSpeed) * kMove;
		st.latSpeed += (rawLat - st.latSpeed) * kMove;
		st.turnRate += (instTurnRate - st.turnRate) * kTurn;

		boolean sprintCond = p.isSprinting() && rawFwd > 0.05f;
		st.sprintBlend += ((sprintCond ? 1f : 0f) - st.sprintBlend) * kSprint;

		boolean inAir = !surf && !p.isOnGround() && !p.getAbilities().flying;
		st.airBlend += ((inAir ? 1f : 0f) - st.airBlend) * (float) (1.0 - Math.exp(-dt * 10.0));
		if (inAir) {
			st.fallSpeed += ((float) MathHelper.clamp(-v.y, -1f, 1.4f) - st.fallSpeed) * 0.25f;
		} else {
			if (st.wasInAir && st.prevVy < -0.18) {
				st.landSquash = Math.min(1.0f, (float) (-st.prevVy * 1.35));
			}
			st.fallSpeed *= (float) Math.exp(-dt * 10.0);
		}
		st.wasInAir = inAir;
		st.prevVy = v.y;
		st.landSquash *= (float) Math.exp(-dt * 9.0);
		if (st.shamblesGesture > 0f) {
			st.shamblesGesture = Math.max(0f, st.shamblesGesture - (float) (dt / 0.36));
		}
		advanceGesture(st);

		float runFrac = MathHelper.clamp(horiz / 0.26f, 0f, 1.35f);
		if (p.isOnGround() && horiz > 0.02f) {
			st.stridePhase += (float) (dt * (7.5 + 8.5 * runFrac));
		}
		float targetLean = MathHelper.clamp(
			11.5f * st.sprintBlend + 3.8f * runFrac + 6.5f * st.landSquash - 3.0f * Math.max(0f, -st.fwdSpeed / 0.20f),
			-4f, 16f);
		float targetBank = MathHelper.clamp(
			st.turnRate * 0.042f + (st.latSpeed / 0.22f) * 7.5f,
			-9.5f, 9.5f);
		st.lastLeanDeg = targetLean;
		st.lastBankDeg = targetBank;
		return st;
	}

	/** Transformaciones de cuerpo entero en 3.ª persona (inclinación de carrera, giro lateral, rebote y aterrizaje). */
	public static void applyBodyTransforms(AbstractClientPlayerEntity p, MatrixStack matrices, float tickDelta) {
		if (skipPlayerPose(p) || p.deathTime > 0) return;
		State st = update(p, tickDelta);
		float bounce = p.isOnGround() && Math.abs(st.fwdSpeed) + Math.abs(st.latSpeed) > 0.03f
			? (float) Math.abs(Math.sin(st.stridePhase)) * (0.035f * st.sprintBlend + 0.010f)
			: 0f;
		float yOff = bounce - st.landSquash * 0.11f;
		if (Math.abs(yOff) > 1e-4f) matrices.translate(0f, yOff, 0f);
		if (Math.abs(st.lastLeanDeg) > 0.05f) {
			matrices.multiply(RotationAxis.POSITIVE_X.rotationDegrees(-st.lastLeanDeg));
		}
		if (Math.abs(st.lastBankDeg) > 0.05f) {
			// En setupTransforms antes de scale(-1,-1,1), ángulo negativo inclina hacia la derecha del jugador (+X)
			matrices.multiply(RotationAxis.POSITIVE_Z.rotationDegrees(-st.lastBankDeg));
		}
	}

	/** Cinemática articulada sobre BipedEntityModel: estabilización de cabeza, torso unido, porte de Kikoku, salto y chasquido. */
	public static void applyBipedAngles(LivingEntity entity, BipedEntityModel<?> model, float limbAngle, float limbDistance,
										float animationProgress, float headYaw, float headPitch) {
		if (!(entity instanceof AbstractClientPlayerEntity p)) return;
		// Garantizar siempre que body.roll no se acumule entre fotogramas (vanilla no lo reinicia)
		model.body.roll = 0.0f;
		if (skipPlayerPose(p) || p.deathTime > 0) return;
		State st = STATES.get(p.getId());
		if (st == null) st = update(p, 0f);

		float runBlend = MathHelper.clamp(limbDistance * 1.45f, 0f, 1f);
		float sprint = st.sprintBlend;
		float squash = st.landSquash;
		float air = st.airBlend;
		boolean swinging = model.handSwingProgress > 0f;

		// 1) Torsión natural de hombros al correr manteniendo los pivotes unidos al torso
		if (!swinging) {
			float spineYaw = MathHelper.sin(limbAngle * 0.6662f) * (0.09f * runBlend + 0.07f * sprint);
			model.body.yaw = spineYaw;
			model.rightArm.pivotZ = MathHelper.sin(spineYaw) * 5.0f;
			model.rightArm.pivotX = -MathHelper.cos(spineYaw) * 5.0f;
			model.leftArm.pivotZ = -MathHelper.sin(spineYaw) * 5.0f;
			model.leftArm.pivotX = MathHelper.cos(spineYaw) * 5.0f;
		}
		float breath = MathHelper.sin(animationProgress * 0.067f) * 0.025f;
		model.rightArm.roll += breath;
		model.leftArm.roll -= breath;

		// 2) Estabilización del horizonte en cabeza al inclinarse corriendo o aterrizando
		model.head.pitch -= (float) Math.toRadians(st.lastLeanDeg * 0.60f);

		// 3) Porte de Kikoku / espada estilo Law al esprintar + drive atlético sin arma
		ItemStack main = p.getMainHandStack();
		boolean hasSword = main.isOf(ModItems.KIKOKU) || main.getItem() instanceof SwordItem;
		Arm mainArm = p.getMainArm();
		if (!swinging && sprint > 0.05f) {
			if (hasSword) {
				var swordArm = mainArm == Arm.RIGHT ? model.rightArm : model.leftArm;
				var freeArm = mainArm == Arm.RIGHT ? model.leftArm : model.rightArm;
				float sideSign = mainArm == Arm.RIGHT ? 1f : -1f;
				float carryPitch = 0.65f + 0.10f * MathHelper.sin(limbAngle * 0.6662f);
				swordArm.pitch = MathHelper.lerp(sprint, swordArm.pitch, carryPitch);
				swordArm.yaw = MathHelper.lerp(sprint, swordArm.yaw, -0.16f * sideSign);
				swordArm.roll = MathHelper.lerp(sprint, swordArm.roll, 0.18f * sideSign);
				freeArm.pitch = MathHelper.lerp(sprint, freeArm.pitch, -0.68f + MathHelper.sin(limbAngle * 0.6662f) * 0.48f);
				freeArm.yaw = MathHelper.lerp(sprint, freeArm.yaw, 0.20f * sideSign);
				freeArm.roll = MathHelper.lerp(sprint, freeArm.roll, -0.16f * sideSign);
			} else {
				model.rightArm.pitch = model.rightArm.pitch * (1f + 0.20f * sprint) - 0.12f * sprint;
				model.leftArm.pitch = model.leftArm.pitch * (1f + 0.20f * sprint) - 0.12f * sprint;
				model.rightArm.yaw -= 0.14f * sprint;
				model.leftArm.yaw += 0.14f * sprint;
				model.rightArm.roll += 0.10f * sprint;
				model.leftArm.roll -= 0.10f * sprint;
			}
		}

		// 4) Zancada expresiva al esprintar + absorción de rodillas en aire / aterrizaje
		if (sprint > 0.05f && air < 0.35f) {
			model.rightLeg.pitch *= (1f + 0.15f * sprint);
			model.leftLeg.pitch *= (1f + 0.15f * sprint);
		}
		if (air > 0.05f) {
			float armFlare = 0.26f * air + Math.max(0f, st.fallSpeed) * 0.18f;
			model.rightArm.roll += armFlare;
			model.leftArm.roll -= armFlare;
			if (!swinging) {
				model.rightArm.pitch = MathHelper.lerp(air, model.rightArm.pitch, -0.18f - Math.max(0f, st.fallSpeed) * 0.22f);
				model.leftArm.pitch = MathHelper.lerp(air, model.leftArm.pitch, -0.12f - Math.max(0f, st.fallSpeed) * 0.22f);
			}
			model.rightLeg.pitch = MathHelper.lerp(air * 0.55f, model.rightLeg.pitch, -0.35f);
			model.leftLeg.pitch = MathHelper.lerp(air * 0.55f, model.leftLeg.pitch, 0.24f);
		}
		if (squash > 0.02f) {
			model.rightLeg.pitch -= 0.26f * squash;
			model.leftLeg.pitch -= 0.14f * squash;
			model.rightArm.roll += 0.18f * squash;
			model.leftArm.roll -= 0.18f * squash;
		}

		// Postura firme de pie sobre la roca gravitatoria de Fujitora (pies plantados, nunca sentado)
		if (isGravitySurfing(p)) {
			model.rightLeg.pitch = 0.08f;
			model.leftLeg.pitch = -0.10f;
			model.rightLeg.roll = 0.09f;
			model.leftLeg.roll = -0.09f;
		}

		// 5) Chasquido de dedos para Shambles con la mano libre (o la principal si está vacía)
		if (st.shamblesGesture > 0.01f) {
			float g = st.shamblesGesture;
			float snap = MathHelper.sin(g * (float) Math.PI);
			boolean useMain = main.isEmpty();
			Arm gArm = useMain ? mainArm : mainArm.getOpposite();
			var snapArm = gArm == Arm.RIGHT ? model.rightArm : model.leftArm;
			float sgn = gArm == Arm.RIGHT ? 1f : -1f;
			snapArm.pitch = MathHelper.lerp(snap, snapArm.pitch, -1.42f);
			snapArm.yaw = MathHelper.lerp(snap, snapArm.yaw, -0.26f * sgn);
			snapArm.roll = MathHelper.lerp(snap, snapArm.roll, 0.42f * sgn * (1f - g));
		}

		// 6) Gestos de las técnicas de Law
		applyLawGesture(st, model, p.getMainArm());

		model.hat.copyTransform(model.head);
	}

	private static void applyLawGesture(State st, BipedEntityModel<?> model, Arm mainArm) {
		advanceGesture(st);
		float w = gestureWeight(st);
		if (w <= 0.001f) return;
		st.lastApplyKind = st.gestureKind;
		float t = st.gestureT;
		var arm = mainArm == Arm.RIGHT ? model.rightArm : model.leftArm;
		var other = mainArm == Arm.RIGHT ? model.leftArm : model.rightArm;
		float sgn = mainArm == Arm.RIGHT ? 1f : -1f;
		switch (st.gestureKind) {
			case GESTURE_PALM -> {
				// ROOM: brazo extendido hacia delante y arriba, palma abierta hacia el cielo
				arm.pitch = MathHelper.lerp(w, arm.pitch, -1.62f);
				arm.yaw = MathHelper.lerp(w, arm.yaw, -0.22f * sgn);
				arm.roll = MathHelper.lerp(w, arm.roll, 0.10f * sgn);
				model.body.yaw = MathHelper.lerp(w, model.body.yaw, -0.12f * sgn);
			}
			case GESTURE_INDEX -> {
				// Takt: el índice hacia arriba, el brazo casi vertical
				arm.pitch = MathHelper.lerp(w, arm.pitch, -2.72f);
				arm.yaw = MathHelper.lerp(w, arm.yaw, 0.08f * sgn);
				arm.roll = MathHelper.lerp(w, arm.roll, -0.18f * sgn);
			}
			case GESTURE_THRUST -> {
				// Estocada: atrás un instante y disparo hacia delante con el torso
				float wind = t < 0.28f ? smooth(t / 0.28f) : 0f;
				float drive = t >= 0.22f ? smooth((t - 0.22f) / 0.18f) : 0f;
				float pitch = MathHelper.lerp(drive, -0.35f + 0.45f * wind, -1.60f);
				arm.pitch = MathHelper.lerp(w, arm.pitch, pitch);
				arm.yaw = MathHelper.lerp(w, arm.yaw, -0.05f * sgn);
				arm.roll = MathHelper.lerp(w, arm.roll, 0f);
				model.body.yaw = MathHelper.lerp(w, model.body.yaw, (-0.32f * drive + 0.20f * wind) * sgn);
				other.pitch = MathHelper.lerp(w, other.pitch, 0.35f);
			}
			case GESTURE_TWO_PALMS -> {
				// Counter Shock: las dos palmas al frente, como las palas de un desfibrilador
				arm.pitch = MathHelper.lerp(w, arm.pitch, -1.48f);
				other.pitch = MathHelper.lerp(w, other.pitch, -1.48f);
				arm.yaw = MathHelper.lerp(w, arm.yaw, 0.30f * sgn);
				other.yaw = MathHelper.lerp(w, other.yaw, -0.30f * sgn);
				arm.roll = MathHelper.lerp(w, arm.roll, 0f);
				other.roll = MathHelper.lerp(w, other.roll, 0f);
			}
			case GESTURE_SLASH -> {
				// Tajo diagonal con Kikoku: de arriba-atrás a abajo-cruzado
				float s = smooth(MathHelper.clamp((t - 0.08f) / 0.30f, 0f, 1f));
				arm.pitch = MathHelper.lerp(w, arm.pitch, MathHelper.lerp(s, -2.75f, -0.55f));
				arm.yaw = MathHelper.lerp(w, arm.yaw, MathHelper.lerp(s, -0.55f, 0.75f) * sgn);
				arm.roll = MathHelper.lerp(w, arm.roll, MathHelper.lerp(s, 0.35f, -0.15f) * sgn);
				model.body.yaw = MathHelper.lerp(w, model.body.yaw, MathHelper.lerp(s, -0.30f, 0.38f) * sgn);
			}
			case GESTURE_SWEEP -> {
				// Curtain: el brazo horizontal barre el aire y tira del velo
				float s = smooth(MathHelper.clamp((t - 0.1f) / 0.45f, 0f, 1f));
				arm.pitch = MathHelper.lerp(w, arm.pitch, -1.52f);
				arm.yaw = MathHelper.lerp(w, arm.yaw, MathHelper.lerp(s, -1.05f, 0.85f) * sgn);
				arm.roll = MathHelper.lerp(w, arm.roll, 0f);
				model.body.yaw = MathHelper.lerp(w, model.body.yaw, MathHelper.lerp(s, -0.25f, 0.22f) * sgn);
			}
			case GESTURE_OVERHEAD -> {
				// Puncture Wille: la Kikoku alzada con las dos manos y clavada hacia abajo
				float s = smooth(MathHelper.clamp((t - 0.30f) / 0.18f, 0f, 1f));
				float pitch = MathHelper.lerp(s, -3.05f, -0.55f);
				arm.pitch = MathHelper.lerp(w, arm.pitch, pitch);
				other.pitch = MathHelper.lerp(w, other.pitch, pitch);
				arm.yaw = MathHelper.lerp(w, arm.yaw, 0.18f * sgn);
				other.yaw = MathHelper.lerp(w, other.yaw, -0.18f * sgn);
				model.head.pitch = MathHelper.lerp(w * s, model.head.pitch, 0.45f);
			}
			default -> {}
		}
	}

	private static void applyLawGestureFirstPerson(State st, float side, boolean mainHand, MatrixStack matrices) {
		advanceGesture(st);
		float w = gestureWeight(st);
		if (w <= 0.001f) return;
		float t = st.gestureT;
		boolean both = st.gestureKind == GESTURE_TWO_PALMS || st.gestureKind == GESTURE_OVERHEAD;
		if (!mainHand && !both) return;
		switch (st.gestureKind) {
			case GESTURE_PALM -> {
				matrices.translate(-0.06f * side * w, 0.16f * w, -0.10f * w);
				matrices.multiply(RotationAxis.POSITIVE_X.rotationDegrees(32f * w));
				matrices.multiply(RotationAxis.POSITIVE_Z.rotationDegrees(-10f * side * w));
			}
			case GESTURE_INDEX -> {
				matrices.translate(0.02f * side * w, 0.30f * w, -0.06f * w);
				matrices.multiply(RotationAxis.POSITIVE_X.rotationDegrees(55f * w));
			}
			case GESTURE_THRUST -> {
				float drive = t >= 0.22f ? smooth((t - 0.22f) / 0.18f) : 0f;
				float wind = t < 0.28f ? smooth(t / 0.28f) : 0f;
				matrices.translate(-0.10f * side * drive * w, 0.04f * w, (0.10f * wind - 0.34f * drive) * w);
				matrices.multiply(RotationAxis.POSITIVE_X.rotationDegrees(12f * drive * w));
			}
			case GESTURE_TWO_PALMS -> {
				matrices.translate(-0.12f * side * w, 0.14f * w, -0.22f * w);
				matrices.multiply(RotationAxis.POSITIVE_X.rotationDegrees(28f * w));
			}
			case GESTURE_SLASH -> {
				float s = smooth(MathHelper.clamp((t - 0.08f) / 0.30f, 0f, 1f));
				matrices.translate(MathHelper.lerp(s, 0.10f, -0.22f) * side * w, MathHelper.lerp(s, 0.22f, -0.10f) * w, -0.08f * w);
				matrices.multiply(RotationAxis.POSITIVE_Z.rotationDegrees(MathHelper.lerp(s, -35f, 40f) * side * w));
			}
			case GESTURE_SWEEP -> {
				float s = smooth(MathHelper.clamp((t - 0.1f) / 0.45f, 0f, 1f));
				matrices.translate(MathHelper.lerp(s, 0.18f, -0.30f) * side * w, 0.10f * w, -0.06f * w);
				matrices.multiply(RotationAxis.POSITIVE_Y.rotationDegrees(MathHelper.lerp(s, -30f, 30f) * side * w));
			}
			case GESTURE_OVERHEAD -> {
				float s = smooth(MathHelper.clamp((t - 0.30f) / 0.18f, 0f, 1f));
				matrices.translate(-0.08f * side * w, MathHelper.lerp(s, 0.36f, -0.18f) * w, -0.10f * w);
				matrices.multiply(RotationAxis.POSITIVE_X.rotationDegrees(MathHelper.lerp(s, 60f, -25f) * w));
			}
			default -> {}
		}
	}

	/** Dinámica táctil en 1.ª persona: balanceo de carrera, inercia al girar la mira, amortiguación y gesto Shambles. */
	public static void applyFirstPersonHand(AbstractClientPlayerEntity p, float tickDelta, Hand hand, float swingProgress,
											  ItemStack item, MatrixStack matrices) {
		State st = STATES.get(p.getId());
		if (st == null) st = update(p, tickDelta);
		boolean main = hand == Hand.MAIN_HAND;
		float side = (main ? (p.getMainArm() == Arm.RIGHT ? 1f : -1f) : (p.getMainArm() == Arm.RIGHT ? -1f : 1f));

		float damp = 1.0f - MathHelper.clamp(swingProgress * 1.4f, 0f, 1f);
		float sprint = st.sprintBlend * damp;
		if (sprint > 0.01f && !OpeSettings.reducedMotion()) {
			float ph = st.stridePhase;
			float dx = (float) Math.cos(ph) * 0.032f * sprint * side - 0.045f * sprint * side;
			float dy = (float) Math.abs(Math.sin(ph)) * 0.028f * sprint - 0.055f * sprint;
			matrices.translate(dx, dy, -0.025f * sprint);
			matrices.multiply(RotationAxis.POSITIVE_Z.rotationDegrees(14f * sprint * side));
			matrices.multiply(RotationAxis.POSITIVE_Y.rotationDegrees(18f * sprint * side));
			matrices.multiply(RotationAxis.POSITIVE_X.rotationDegrees(-12f * sprint));
		}

		// Inercia por giro rápido de cámara + salto / aterrizaje
		float lagX = MathHelper.clamp(-st.turnRate * 0.00028f, -0.045f, 0.045f);
		float lagY = MathHelper.clamp(st.fallSpeed * 0.022f - st.landSquash * 0.055f, -0.06f, 0.04f);
		if (Math.abs(lagX) > 1e-4f || Math.abs(lagY) > 1e-4f) {
			matrices.translate(lagX, lagY, 0f);
			matrices.multiply(RotationAxis.POSITIVE_Z.rotationDegrees(MathHelper.clamp(-st.turnRate * 0.035f, -5.5f, 5.5f)));
		}

		// Gestos de las técnicas de Law en 1.ª persona
		applyLawGestureFirstPerson(st, side, main, matrices);

		// Gesto de Shambles en 1.ª persona
		if (st.shamblesGesture > 0.01f) {
			float snap = MathHelper.sin(st.shamblesGesture * (float) Math.PI);
			float w = !main || p.getOffHandStack().isEmpty() ? (main && !item.isEmpty() ? 0.38f : 1.0f) : 0f;
			if (w > 0.01f) {
				matrices.translate(0.08f * snap * side * w, 0.11f * snap * w, -0.10f * snap * w);
				matrices.multiply(RotationAxis.POSITIVE_Z.rotationDegrees(-28f * snap * side * w));
				matrices.multiply(RotationAxis.POSITIVE_X.rotationDegrees(22f * snap * w));
			}
		}
	}

	/** Cabeceo e inclinación sutiles de cámara en 1.ª persona durante carrera, giros rápidos y aterrizajes. */
	public static float firstPersonCameraRoll(float tickDelta) {
		MinecraftClient mc = MinecraftClient.getInstance();
		if (mc.player == null || OpeSettings.reducedMotion()) return 0f;
		State st = STATES.get(mc.player.getId());
		if (st == null) return 0f;
		return MathHelper.clamp(st.turnRate * 0.010f + (st.latSpeed / 0.22f) * 1.15f, -2.4f, 2.4f);
	}

	public static float firstPersonCameraPitch(float tickDelta) {
		MinecraftClient mc = MinecraftClient.getInstance();
		if (mc.player == null || OpeSettings.reducedMotion()) return 0f;
		State st = STATES.get(mc.player.getId());
		if (st == null) return 0f;
		float strideBob = mc.player.isOnGround() && st.sprintBlend > 0.1f
			? MathHelper.sin(st.stridePhase * 2f) * 0.28f * st.sprintBlend : 0f;
		return st.landSquash * 1.65f + strideBob;
	}

	/** Métricas deterministas para pruebas automatizadas (AutoTest E10e). */
	public static float[] computeTestMetrics(float fwdSpeed, float latSpeed, float yawRate, boolean sprinting, float shamblesGesture) {
		float runFrac = MathHelper.clamp((float) Math.sqrt(fwdSpeed * fwdSpeed + latSpeed * latSpeed) / 0.26f, 0f, 1.35f);
		float sprint = sprinting && fwdSpeed > 0.05f ? 1f : 0f;
		float lean = MathHelper.clamp(11.5f * sprint + 3.8f * runFrac, -4f, 16f);
		float bank = MathHelper.clamp(yawRate * 0.042f + (latSpeed / 0.22f) * 7.5f, -9.5f, 9.5f);
		float gest = MathHelper.clamp(shamblesGesture, 0f, 1f);
		return new float[]{lean, bank, gest};
	}
}
