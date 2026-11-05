package dev.opeope.registry;

import dev.opeope.power.PowerManager;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;

public final class ModCommands {
	private ModCommands() {}

	public static void init() {
		CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
			dispatcher.register(CommandManager.literal("opeope")
				.requires(src -> src.hasPermissionLevel(2))
				.then(CommandManager.literal("fruta").executes(ctx -> {
					ServerPlayerEntity p = ctx.getSource().getPlayerOrThrow();
					PowerManager.grantFruit(p);
					return 1;
				}))
				.then(CommandManager.literal("quitar").executes(ctx -> {
					ServerPlayerEntity p = ctx.getSource().getPlayerOrThrow();
					PowerManager.removeFruit(p);
					ctx.getSource().sendFeedback(() -> Text.translatable("opeope.msg.fruit_removed"), false);
					return 1;
				}))
				.then(CommandManager.literal("despertar").executes(ctx -> {
					ServerPlayerEntity p = ctx.getSource().getPlayerOrThrow();
					PowerManager.grantAwakening(p);
					return 1;
				}))));
	}
}
