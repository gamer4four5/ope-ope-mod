package dev.opeope.power;

import dev.opeope.OpeOpeMod;
import dev.opeope.net.Payloads;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.math.Vec3d;
import org.jetbrains.annotations.Nullable;

/**
 * Los momentos del ROOM (nace, se fija, cambia de tamaño, vuela, aterriza, vuelve, se cierra) para que los clientes los
 * hagan notar. Le llegan al dueño y a quien esté en su mundo a menos de 256 + radio bloques del punto a o del b, si su
 * cliente sabe recibirlos (un cliente viejo no recibe nada y sigue como antes).
 * <p>Regla de orden: se manda ANTES del {@code PowerManager.broadcastRoom} que lo acompaña; por la misma conexión el
 * orden se mantiene.
 */
public final class RoomEvents {
	private RoomEvents() {}

	private static final double RANGE = 256;
	private static boolean warned;

	/** b puede ser null: entonces es igual que a. */
	public static void send(ServerPlayerEntity owner, int slot, int kind, Vec3d a, @Nullable Vec3d b, float radius, float radius2, int ticks, int flags) {
		if (owner == null || a == null) return;
		Vec3d bb = b != null ? b : a;
		Payloads.RoomFx fx = new Payloads.RoomFx(owner.getUuid(), slot, kind, a.x, a.y, a.z, bb.x, bb.y, bb.z, radius, radius2, ticks, flags);
		try {
			if (ServerPlayNetworking.canSend(owner, Payloads.RoomFx.ID)) ServerPlayNetworking.send(owner, fx);
			double reach = RANGE + Math.max(0.0, radius);
			double reach2 = reach * reach;
			for (ServerPlayerEntity sp : owner.getServerWorld().getPlayers()) {
				if (sp == owner) continue;
				Vec3d pos = sp.getPos();
				if (pos.squaredDistanceTo(a) > reach2 && pos.squaredDistanceTo(bb) > reach2) continue;
				if (!ServerPlayNetworking.canSend(sp, Payloads.RoomFx.ID)) continue;
				ServerPlayNetworking.send(sp, fx);
			}
		} catch (RuntimeException e) {
			// Solo son efectos: un fallo aquí nunca debe parar el ROOM.
			if (!warned) {
				warned = true;
				OpeOpeMod.LOG.warn("No se pudo enviar el efecto del ROOM {}", fx.name(), e);
			}
		}
	}
}
