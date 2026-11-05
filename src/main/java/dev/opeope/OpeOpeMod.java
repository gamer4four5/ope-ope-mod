package dev.opeope;

import dev.opeope.net.Net;
import dev.opeope.power.PowerManager;
import dev.opeope.registry.ModAttachments;
import dev.opeope.registry.ModCommands;
import dev.opeope.registry.ModDamage;
import dev.opeope.registry.ModEffects;
import dev.opeope.registry.ModEntities;
import dev.opeope.registry.ModItems;
import net.fabricmc.api.ModInitializer;
import net.minecraft.util.Identifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class OpeOpeMod implements ModInitializer {
	public static final String MOD_ID = "opeope";
	public static final Logger LOG = LoggerFactory.getLogger(MOD_ID);

	public static Identifier id(String path) {
		return Identifier.of(MOD_ID, path);
	}

	@Override
	public void onInitialize() {
		ModAttachments.init();
		ModEffects.init();
		ModItems.init();
		ModEntities.init();
		ModDamage.init();
		Net.init();
		PowerManager.init();
		ModCommands.init();
		LOG.info("Ope Ope no Mi: ROOM listo.");
	}
}
