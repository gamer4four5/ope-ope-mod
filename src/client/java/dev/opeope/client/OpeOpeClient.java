package dev.opeope.client;

import dev.opeope.client.config.OpeProgress;
import dev.opeope.client.config.OpeSettings;
import dev.opeope.client.hud.HudRoot;
import dev.opeope.client.input.InputHandler;
import dev.opeope.client.input.Keys;
import dev.opeope.client.input.OpeKeyBindings;
import dev.opeope.client.render.CutFx;
import dev.opeope.client.render.PieceRenderer;
import dev.opeope.client.render.RoomRenderer;
import dev.opeope.client.render.SterbenRenderer;
import dev.opeope.client.room.RoomFxClient;
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
		// ajustes, progreso y teclas antes que nada: el HUD, la entrada y el ROOM los leen desde el primer tick
		OpeSettings.load();
		OpeProgress.load();
		Keys.load();
		OpeKeyBindings.register();   // el libro (H) y los ajustes como teclas de Minecraft, y el botón en Controles
		HudRoot.init();              // los avisos de la fruta en la barra de acción pasan a ser avisos del HUD
		RoomFxClient.init();         // los momentos del ROOM (crear, fijar, lanzar…) que cuenta el servidor

		EntityRendererRegistry.register(ModEntities.PIECE, PieceRenderer::new);
		EntityRendererRegistry.register(ModEntities.STERBEN, SterbenRenderer::new);
		EntityRendererRegistry.register(ModEntities.MARKER_STONE, FlyingItemEntityRenderer::new);

		ClientPlayNetworking.registerGlobalReceiver(Payloads.State.ID, (payload, ctx) -> ClientState.apply(payload));
		ClientPlayNetworking.registerGlobalReceiver(Payloads.Room.ID, (payload, ctx) -> ClientState.room(payload));
		ClientPlayNetworking.registerGlobalReceiver(Payloads.CutSync.ID, (payload, ctx) -> {
			if (payload.planes().length == 0) ClientState.CUTS.remove(payload.entityId());
			else ClientState.CUTS.put(payload.entityId(), payload.planes());
		});
		ClientPlayNetworking.registerGlobalReceiver(Payloads.CutFx.ID, (payload, ctx) -> CutFx.receive(payload.data()));
		ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
			ClientState.clear();
			CutFx.clear();
			InputHandler.onDisconnect();
			HudRoot.clear();
		});

		ClientTickEvents.END_CLIENT_TICK.register(client -> {
			ClientState.tick();
			InputHandler.tick(client);
			RoomFxClient.tick(client);
			HudRoot.tick(client);
			CutFx.tick();
			AutoTest.tick(client);
		});
		WorldRenderEvents.AFTER_TRANSLUCENT.register(RoomRenderer::render);
		WorldRenderEvents.AFTER_TRANSLUCENT.register(CutFx::render);
		HudRenderCallback.EVENT.register(HudRoot::render);
		HudRenderCallback.EVENT.register(CutFx::renderHud);
		OpeOpeMod.LOG.info("Ope Ope no Mi: cliente listo.");
		if (Boolean.getBoolean("opeope.autotest")) {
			ProcessHandle.current().info().arguments().ifPresent(args -> OpeOpeMod.LOG.info("Autotest, argumentos del juego: {}",
				java.util.Arrays.stream(args).filter(a -> !a.contains("/") && !a.contains(";")).toList()));
		}
	}
}
