package dev.opeope.net;

import dev.opeope.power.Actions;
import dev.opeope.power.PowerManager;
import dev.opeope.power.Takt;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;

public final class Net {
	private Net() {}

	public static void init() {
		PayloadTypeRegistry.playC2S().register(Payloads.Action.ID, Payloads.Action.CODEC);
		PayloadTypeRegistry.playC2S().register(Payloads.Cut.ID, Payloads.Cut.CODEC);
		PayloadTypeRegistry.playC2S().register(Payloads.TaktTap.ID, Payloads.TaktTap.CODEC);
		PayloadTypeRegistry.playC2S().register(Payloads.TaktPlace.ID, Payloads.TaktPlace.CODEC);
		PayloadTypeRegistry.playS2C().register(Payloads.Room.ID, Payloads.Room.CODEC);
		PayloadTypeRegistry.playS2C().register(Payloads.CutSync.ID, Payloads.CutSync.CODEC);
		PayloadTypeRegistry.playS2C().register(Payloads.CutFx.ID, Payloads.CutFx.CODEC);
		PayloadTypeRegistry.playS2C().register(Payloads.State.ID, Payloads.State.CODEC);
		PayloadTypeRegistry.playS2C().register(Payloads.RoomFx.ID, Payloads.RoomFx.CODEC);
		PayloadTypeRegistry.playS2C().register(Payloads.GroundCutSync.ID, Payloads.GroundCutSync.CODEC);
		PayloadTypeRegistry.playS2C().register(Payloads.TaktFx.ID, Payloads.TaktFx.CODEC);

		ServerPlayNetworking.registerGlobalReceiver(Payloads.Action.ID, (payload, ctx) ->
			ctx.server().execute(() -> Actions.handle(ctx.player(), payload.action(), payload.arg())));
		ServerPlayNetworking.registerGlobalReceiver(Payloads.Cut.ID, (payload, ctx) ->
			ctx.server().execute(() -> PowerManager.handleCut(ctx.player(), payload.kind(), payload.planes())));
		ServerPlayNetworking.registerGlobalReceiver(Payloads.TaktTap.ID, (payload, ctx) ->
			ctx.server().execute(() -> Takt.handleTap(ctx.player(), payload)));
		ServerPlayNetworking.registerGlobalReceiver(Payloads.TaktPlace.ID, (payload, ctx) ->
			ctx.server().execute(() -> Takt.handlePlace(ctx.player(), payload)));
	}
}
