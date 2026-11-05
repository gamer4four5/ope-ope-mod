package dev.opeope.client.input;

import dev.opeope.net.Payloads;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;

public final class Send {
	private Send() {}

	public static void action(int action, int arg) {
		if (ClientPlayNetworking.canSend(Payloads.Action.ID)) ClientPlayNetworking.send(new Payloads.Action(action, arg));
	}

	public static void action(int action) {
		action(action, 0);
	}

	public static void cut(int kind, float[] planes) {
		if (ClientPlayNetworking.canSend(Payloads.Cut.ID)) ClientPlayNetworking.send(new Payloads.Cut(kind, planes));
	}
}
