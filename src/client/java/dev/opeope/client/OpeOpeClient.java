package dev.opeope.client;

import dev.opeope.client.input.InputHandler;
import dev.opeope.client.input.Keys;
import dev.opeope.client.render.Hud;
import dev.opeope.client.render.PieceRenderer;
import dev.opeope.client.render.RoomRenderer;
import dev.opeope.client.render.SterbenRenderer;
import dev.opeope.net.Payloads;
import dev.opeope.registry.ModEntities;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.EntityRendererRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
import net.minecraft.client.render.entity.FlyingItemEntityRenderer;
import dev.opeope.OpeOpeMod;

public class OpeOpeClient implements ClientModInitializer {
	@Override
	public void onInitializeClient() {
		Keys.load();
		EntityRendererRegistry.register(ModEntities.PIECE, PieceRenderer::new);
		EntityRendererRegistry.register(ModEntities.STERBEN, SterbenRenderer::new);
		EntityRendererRegistry.register(ModEntities.MARKER_STONE, FlyingItemEntityRenderer::new);

		ClientPlayNetworking.registerGlobalReceiver(Payloads.State.ID, (payload, ctx) -> ClientState.apply(payload));
		ClientPlayNetworking.registerGlobalReceiver(Payloads.Room.ID, (payload, ctx) -> ClientState.room(payload));
		ClientPlayNetworking.registerGlobalReceiver(Payloads.CutSync.ID, (payload, ctx) -> {
			if (payload.planes().length == 0) ClientState.CUTS.remove(payload.entityId());
			else ClientState.CUTS.put(payload.entityId(), payload.planes());
		});
		ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> ClientState.clear());

		ClientTickEvents.END_CLIENT_TICK.register(client -> {
			ClientState.tick();
			InputHandler.tick(client);
		});
		WorldRenderEvents.AFTER_TRANSLUCENT.register(RoomRenderer::render);
		HudRenderCallback.EVENT.register(Hud::render);
		OpeOpeMod.LOG.info("Ope Ope no Mi: cliente listo.");
		if (Boolean.getBoolean("opeope.autotest")) {
			ProcessHandle.current().info().arguments().ifPresent(args -> OpeOpeMod.LOG.info("Autotest, argumentos del juego: {}",
				java.util.Arrays.stream(args).filter(a -> !a.startsWith("-") && !a.contains("/") && !a.contains(".")).toList()));
		}
	}
}
