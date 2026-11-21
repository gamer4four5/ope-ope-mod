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

	/** Lo que le falta a un ser vivo cortado por partes (estilo Law). Se guarda con la entidad. */
	public static final AttachmentType<dev.opeope.anatomy.DismemberState> DISMEMBER = AttachmentRegistry.<dev.opeope.anatomy.DismemberState>builder()
		.persistent(dev.opeope.anatomy.DismemberState.CODEC).buildAndRegister(OpeOpeMod.id("dismember"));

	/** Recompensa pirata en Berries (progreso quirúrgico acumulado). */
	public static final AttachmentType<Long> BOUNTY = AttachmentRegistry.<Long>builder()
		.persistent(Codec.LONG).copyOnDeath().buildAndRegister(OpeOpeMod.id("bounty"));

	public static void init() {}

	public static boolean hasFruit(PlayerEntity p) {
		return Boolean.TRUE.equals(p.getAttached(HAS_FRUIT));
	}

	public static boolean hasAwakening(PlayerEntity p) {
		return Boolean.TRUE.equals(p.getAttached(AWAKENING));
	}

	public static long getBounty(PlayerEntity p) {
		Long v = p.getAttached(BOUNTY);
		return v == null ? 0L : Math.max(0L, v);
	}

	public static void setBounty(PlayerEntity p, long val) {
		p.setAttached(BOUNTY, Math.max(0L, val));
	}
}
