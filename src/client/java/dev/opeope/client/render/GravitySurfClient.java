package dev.opeope.client.render;

import dev.opeope.entity.PieceEntity;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.option.GameOptions;
import net.minecraft.entity.MovementType;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;

/**
 * Piloto en el cliente de la roca voladora de Fujitora ({@link PieceEntity.Mode#SURF}).
 * <p>
 * El jugador jamás usa vuelo creativo: pilota físicamente la roca como vehículo con predicción local (0 lag),
 * colisión real con el terreno y sincronización nativa mediante {@code VehicleMoveC2SPacket}.
 */
public final class GravitySurfClient {
	private GravitySurfClient() {}

	public static void tickRock(PieceEntity rock) {
		MinecraftClient mc = MinecraftClient.getInstance();
		ClientPlayerEntity p = mc.player;
		if (p == null || rock.getControllingPassenger() != p) return;

		rock.setNoGravity(true);
		rock.noClip = false;
		rock.solid = false;
		rock.fallDistance = 0f;
		p.fallDistance = 0f;

		float fwd = 0f, strafe = 0f;
		boolean jump = false, boost = false;
		if (mc.currentScreen == null) {
			GameOptions opt = mc.options;
			if (opt.forwardKey.isPressed()) fwd += 1f;
			if (opt.backKey.isPressed()) fwd -= 1f;
			if (opt.leftKey.isPressed()) strafe += 1f;
			if (opt.rightKey.isPressed()) strafe -= 1f;
			jump = opt.jumpKey.isPressed();
			boost = opt.sprintKey.isPressed();
		}

		Vec3d look = p.getRotationVec(1.0f);
		Vec3d flatFwd = new Vec3d(look.x, 0.0, look.z);
		if (flatFwd.lengthSquared() < 1e-5) {
			double yawRad = Math.toRadians(p.getYaw());
			flatFwd = new Vec3d(-Math.sin(yawRad), 0.0, Math.cos(yawRad));
		} else {
			flatFwd = flatFwd.normalize();
		}
		// Izquierda = arriba (0,1,0) × flatFwd = (flatFwd.z, 0, -flatFwd.x)
		Vec3d leftDir = new Vec3d(flatFwd.z, 0.0, -flatFwd.x);

		Vec3d wish = Vec3d.ZERO;
		if (fwd > 0f) {
			// Avanzar sigue la mirada 3D (mira arriba para ascender, abajo para picar, recto para planear)
			wish = wish.add(look.multiply(fwd));
		} else if (fwd < 0f) {
			// Retroceder se mantiene horizontal para no enterrarte al mirar arriba
			wish = wish.add(flatFwd.multiply(fwd));
		}
		if (Math.abs(strafe) > 0.01f) {
			wish = wish.add(leftDir.multiply(strafe));
		}
		if (jump) {
			wish = wish.add(0.0, 0.85, 0.0);
		}

		double maxSpeed = boost ? 1.15 : 0.68;
		Vec3d cur = rock.getVelocity();
		Vec3d next;
		if (wish.lengthSquared() > 1e-5) {
			Vec3d targetVel = wish.normalize().multiply(maxSpeed);
			next = cur.lerp(targetVel, 0.30);
		} else {
			// Flotación firme y silenciosa en el aire cuando no pulsas movimiento
			next = cur.multiply(0.74);
			if (next.lengthSquared() < 0.0004) next = Vec3d.ZERO;
		}

		// Límite de seguridad por debajo del tope anti-velocidad del servidor
		double len = next.length();
		if (len > 1.38) next = next.multiply(1.38 / len);

		// Evita aplastar la cabeza de Fujitora contra un techo al subir en cuevas
		if (next.y > 0.01 && mc.world != null) {
			Box headBox = p.getBoundingBox().offset(0.0, next.y + 0.08, 0.0);
			if (!mc.world.isSpaceEmpty(p, headBox)) {
				next = new Vec3d(next.x, 0.0, next.z);
			}
		}

		rock.setYaw(MathHelper.wrapDegrees(p.getYaw()));
		rock.setVelocity(next);
		if (next.lengthSquared() > 1e-7) {
			rock.move(MovementType.SELF, next);
			// Si chocó de lleno contra un muro o suelo, ajusta la velocidad real para no vibrar
			if (rock.horizontalCollision) {
				rock.setVelocity(rock.getVelocity().multiply(0.35, 1.0, 0.35));
			}
			if (rock.verticalCollision) {
				rock.setVelocity(rock.getVelocity().multiply(1.0, 0.0, 1.0));
			}
		}
	}
}
