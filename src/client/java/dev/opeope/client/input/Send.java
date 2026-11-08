package dev.opeope.client.input;

import dev.opeope.client.ClientState;
import dev.opeope.client.render.TerrainFx;
import dev.opeope.client.room.TaktSounds;
import dev.opeope.net.Payloads;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;

import java.util.Arrays;

public final class Send {
	private Send() {}

	/** Lo más que cabe en un TaktPlace (su CAP en la red). */
	private static final int PLACE_CAP = 32;

	public static void action(int action, int arg) {
		if (ClientPlayNetworking.canSend(Payloads.Action.ID)) ClientPlayNetworking.send(new Payloads.Action(action, arg));
	}

	public static void action(int action) {
		action(action, 0);
	}

	public static void cut(int kind, float[] planes) {
		if (ClientPlayNetworking.canSend(Payloads.Cut.ID)) ClientPlayNetworking.send(new Payloads.Cut(kind, planes));
	}

	/**
	 * Toque de Takt con lo que mira el cliente (el bloque y el punto exacto, o nada), el tamaño del trozo y cuántos ticks
	 * debe esperar el arranque a un segundo toque (window).
	 */
	public static void taktTap(int kind, int window) {
		if (!ClientPlayNetworking.canSend(Payloads.TaktTap.ID)) return;
		BlockHitResult hit = TerrainFx.aimGround();
		long aim = 0;
		int side = Payloads.TaktTap.NO_SIDE;
		float hx = 0, hy = 0, hz = 0;
		if (hit != null) {
			aim = hit.getBlockPos().asLong();
			side = hit.getSide().ordinal();
			hx = (float) hit.getPos().x;
			hy = (float) hit.getPos().y;
			hz = (float) hit.getPos().z;
		}
		ClientPlayNetworking.send(new Payloads.TaktTap(kind, ClientState.chunkSize2(), aim, side, hx, hy, hz, window));
	}

	/**
	 * Posar (R + V): cada pieza que llevas baja a su silueta. Va la base del fantasma de cada una; las que no tienen
	 * fantasma no van (el servidor busca su sitio).
	 */
	public static void taktPlace() {
		if (!ClientPlayNetworking.canSend(Payloads.TaktPlace.ID)) return;
		int[] held = ClientState.heldIds;
		int[] ids = new int[Math.min(held.length, PLACE_CAP)];
		long[] bases = new long[ids.length];
		int n = 0;
		for (int id : held) {
			if (n >= ids.length) break;
			BlockPos g = TerrainFx.ghostFor(id);
			if (g == null) continue;
			ids[n] = id;
			bases[n] = g.asLong();
			n++;
		}
		ClientPlayNetworking.send(new Payloads.TaktPlace(Arrays.copyOf(ids, n), Arrays.copyOf(bases, n)));
		if (held.length > 0) TaktSounds.setDownStart();
	}
}
