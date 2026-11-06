package dev.opeope.net;

import dev.opeope.power.Actions;
import dev.opeope.power.PowerManager;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;

public final class Net {
	private Net() {}

	public static void init() {
		PayloadTypeRegistry.playC2S().register(Payloads.Action.ID, Payloads.Action.CODEC);
		PayloadTypeRegistry.playC2S().register(Payloads.Cut.ID, Payloads.Cut.CODEC);
		PayloadTypeRegistry.playS2C().register(Payloads.Room.ID, Payloads.Room.CODEC);
		PayloadTypeRegistry.playS2C().register(Payloads.CutSync.ID, Payloads.CutSync.CODEC);
		PayloadTypeRegistry.playS2C().register(Payloads.CutFx.ID, Payloads.CutFx.CODEC);
		PayloadTypeRegistry.playS2C().register(Payloads.State.ID, Payloads.State.CODEC);
		PayloadTypeRegistry.playS2C().register(Payloads.RoomFx.ID, Payloads.RoomFx.CODEC);

		ServerPlayNetworking.registerGlobalReceiver(Payloads.Action.ID, (payload, ctx) ->
			ctx.server().execute(() -> Actions.handle(ctx.player(), payload.action(), payload.arg())));
		ServerPlayNetworking.registerGlobalReceiver(Payloads.Cut.ID, (payload, ctx) ->
			ctx.server().execute(() -> PowerManager.handleCut(ctx.player(), payload.kind(), payload.planes())));
	}
}
