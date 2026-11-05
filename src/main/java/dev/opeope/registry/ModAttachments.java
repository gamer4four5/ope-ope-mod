package dev.opeope.registry;

import com.mojang.serialization.Codec;
import dev.opeope.OpeOpeMod;
import net.fabricmc.fabric.api.attachment.v1.AttachmentRegistry;
import net.fabricmc.fabric.api.attachment.v1.AttachmentType;
import net.minecraft.entity.player.PlayerEntity;

@SuppressWarnings("UnstableApiUsage")
public final class ModAttachments {
	private ModAttachments() {}

	/** Si el jugador se comió la fruta. Se guarda con el mundo y sobrevive a la muerte. */
	public static final AttachmentType<Boolean> HAS_FRUIT = AttachmentRegistry.<Boolean>builder()
		.persistent(Codec.BOOL).copyOnDeath().buildAndRegister(OpeOpeMod.id("has_fruit"));

	/** Si ya ganó el Despertar. */
	public static final AttachmentType<Boolean> AWAKENING = AttachmentRegistry.<Boolean>builder()
		.persistent(Codec.BOOL).copyOnDeath().buildAndRegister(OpeOpeMod.id("awakening"));

	public static void init() {}

	public static boolean hasFruit(PlayerEntity p) {
		return Boolean.TRUE.equals(p.getAttached(HAS_FRUIT));
	}

	public static boolean hasAwakening(PlayerEntity p) {
		return Boolean.TRUE.equals(p.getAttached(AWAKENING));
	}
}
