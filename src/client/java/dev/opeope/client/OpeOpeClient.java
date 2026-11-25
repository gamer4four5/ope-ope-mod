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
import dev.opeope.client.render.TerrainFx;
import dev.opeope.client.room.RoomFxClient;
import dev.opeope.client.room.TaktFxClient;
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
		dev.opeope.client.mera.MeraVolumes.register();   // el shader del fuego 3D de la Mera Mera no Mi

		EntityRendererRegistry.register(ModEntities.PIECE, PieceRenderer::new);
		dev.opeope.entity.PieceEntity.CLIENT_SURF_TICK = dev.opeope.client.render.GravitySurfClient::tickRock;
		EntityRendererRegistry.register(ModEntities.STERBEN, SterbenRenderer::new);
		EntityRendererRegistry.register(ModEntities.MARKER_STONE, FlyingItemEntityRenderer::new);
		EntityRendererRegistry.register(ModEntities.BODY_PART, dev.opeope.client.render.BodyPartRenderer::new);
		ClientPlayNetworking.registerGlobalReceiver(Payloads.DismemberSync.ID, (payload, ctx) -> dev.opeope.client.dismember.ClientDismember.receive(payload));
		dev.opeope.client.dismember.AnatomyDump.register();

		ClientPlayNetworking.registerGlobalReceiver(Payloads.State.ID, (payload, ctx) -> ClientState.apply(payload));
		ClientPlayNetworking.registerGlobalReceiver(Payloads.Room.ID, (payload, ctx) -> ClientState.room(payload));
		ClientPlayNetworking.registerGlobalReceiver(Payloads.CutSync.ID, (payload, ctx) -> {
			if (payload.planes().length == 0) ClientState.CUTS.remove(payload.entityId());
			else ClientState.CUTS.put(payload.entityId(), payload.planes());
		});
		ClientPlayNetworking.registerGlobalReceiver(Payloads.CutFx.ID, (payload, ctx) -> CutFx.receive(payload));
		// Takt sobre el suelo: los cortes del suelo que recuerda el servidor y los momentos de Takt
		ClientPlayNetworking.registerGlobalReceiver(Payloads.GroundCutSync.ID, (payload, ctx) -> ClientState.groundCut(payload));
		ClientPlayNetworking.registerGlobalReceiver(Payloads.TaktFx.ID, (payload, ctx) -> TaktFxClient.receive(payload));
		ClientPlayNetworking.registerGlobalReceiver(Payloads.AerialWindow.ID, (payload, ctx) ->
			ctx.client().execute(() -> ClientState.applyAerial(payload)));
		ClientPlayNetworking.registerGlobalReceiver(Payloads.ShamblesFx.ID, (payload, ctx) ->
			ctx.client().execute(() -> dev.opeope.client.render.ShamblesFxClient.receive(payload)));
		ClientPlayNetworking.registerGlobalReceiver(Payloads.GravityFx.ID, (payload, ctx) ->
			ctx.client().execute(() -> dev.opeope.client.render.GravityFxClient.receive(payload)));
		ClientPlayNetworking.registerGlobalReceiver(Payloads.LawFx.ID, (payload, ctx) ->
			ctx.client().execute(() -> dev.opeope.client.render.LawFxClient.receive(payload)));
		ClientPlayNetworking.registerGlobalReceiver(dev.opeope.net.MeraFx.ID, (payload, ctx) ->
			ctx.client().execute(() -> dev.opeope.client.mera.MeraFxClient.receive(payload)));
		// Fabric puede avisar de la desconexión desde el hilo de red: lo de limpiar va al hilo del juego, que es el
		// que dibuja y lee todo esto (si no, una ConcurrentModificationException podía cortar el guardado del progreso)
		ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> client.execute(() -> {
			ClientState.clear();
			dev.opeope.client.dismember.ClientDismember.clear();
			dev.opeope.client.render.ShamblesFxClient.clear();
			dev.opeope.client.render.GravityFxClient.clear();
			dev.opeope.client.render.LawFxClient.clear();
			dev.opeope.client.render.PlayerAnimClient.clear();
			dev.opeope.client.mera.MeraFxClient.clear();
			dev.opeope.client.mera.MeraAnim.clear();
			dev.opeope.client.input.FreehandSlicer.cancel();
			dev.opeope.client.input.Bisturi.COMMITTED.clear();
			dev.opeope.client.input.Bisturi.COMMITTED_SHAPES.clear();
			CutFx.clear();
			TerrainFx.clear();
			TaktFxClient.clear();
			InputHandler.onDisconnect();
			HudRoot.clear();
		}));

		ClientTickEvents.END_CLIENT_TICK.register(client -> {
			ClientState.tick();
			InputHandler.tick(client);
			if (InputHandler.isDragging()) dev.opeope.client.dismember.ClientDismember.aimTick();
			dev.opeope.client.dismember.ClientDismember.tick(client);
			RoomFxClient.tick(client);
			TerrainFx.tick(client);
			TaktFxClient.tick(client);
			HudRoot.tick(client);
			CutFx.tick();
			AutoTest.tick(client);
		});
		WorldRenderEvents.AFTER_TRANSLUCENT.register(RoomRenderer::render);
		WorldRenderEvents.AFTER_TRANSLUCENT.register(CutFx::render);
		WorldRenderEvents.AFTER_TRANSLUCENT.register(dev.opeope.client.render.ShamblesFxClient::renderWorld);
		WorldRenderEvents.AFTER_TRANSLUCENT.register(dev.opeope.client.render.GravityFxClient::renderWorld);
		WorldRenderEvents.AFTER_TRANSLUCENT.register(dev.opeope.client.render.LawFxClient::renderWorld);
		WorldRenderEvents.AFTER_TRANSLUCENT.register(dev.opeope.client.mera.MeraFxClient::renderWorld);
		WorldRenderEvents.AFTER_TRANSLUCENT.register(dev.opeope.client.render.EntityCutPreview::render);
		WorldRenderEvents.AFTER_TRANSLUCENT.register(dev.opeope.client.dismember.ClientDismember::renderAim);
		WorldRenderEvents.START.register(ctx -> dev.opeope.client.dismember.ClientDismember.syncHeadCamera());
		WorldRenderEvents.AFTER_TRANSLUCENT.register(TerrainFx::render);   // justo después del corte (diseño §5.1)
		HudRenderCallback.EVENT.register(HudRoot::render);
		HudRenderCallback.EVENT.register(CutFx::renderHud);
		HudRenderCallback.EVENT.register(dev.opeope.client.render.ShamblesFxClient::renderHud);
		HudRenderCallback.EVENT.register(dev.opeope.client.render.GravityFxClient::renderHud);
		HudRenderCallback.EVENT.register(dev.opeope.client.render.LawFxClient::renderHud);
		HudRenderCallback.EVENT.register(dev.opeope.client.mera.MeraFxClient::renderHud);
		HudRenderCallback.EVENT.register(dev.opeope.client.render.EntityCutPreview::renderHud);
		HudRenderCallback.EVENT.register(dev.opeope.client.dismember.ClientDismember::renderHud);
		OpeOpeMod.LOG.info("Ope Ope no Mi: cliente listo.");
		if (Boolean.getBoolean("opeope.autotest")) {
			ProcessHandle.current().info().arguments().ifPresent(args -> OpeOpeMod.LOG.info("Autotest, argumentos del juego: {}",
				java.util.Arrays.stream(args).filter(a -> !a.contains("/") && !a.contains(";")).toList()));
		}
	}
}
