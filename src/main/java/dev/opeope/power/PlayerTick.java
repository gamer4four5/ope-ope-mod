package dev.opeope.power;

import dev.opeope.registry.ModItems;
import net.minecraft.entity.Entity;
import net.minecraft.server.network.ServerPlayerEntity;

/** Lo que pasa cada tick para un usuario de la fruta. */
final class PlayerTick {
	private PlayerTick() {}

	static void tick(ServerPlayerEntity p, PlayerPower pw) {
		for (int i = 0; i < pw.cooldowns.length; i++) if (pw.cooldowns[i] > 0) pw.cooldowns[i]--;

		RoomOps.tick(p, pw);
		Takt.tick(p, pw);
		if (p.age % 20 == 0) Forge.weapons(p, pw);
		Terrain.tick(p, pw);
		Union.surgeryTick(p, pw);
		Techniques.dashTick(p, pw);
		Techniques.curtainTick(p, pw);
		Techniques.rainTick(p, pw);
		Techniques.silentTick(p, pw);
		Techniques.anesthesiaTick(p, pw);
		Takt.tickThrown(p.getServerWorld());

		if (pw.shamblesMarkTicks > 0 && --pw.shamblesMarkTicks == 0 && pw.shamblesMark >= 0) {
			Entity e = p.getWorld().getEntityById(pw.shamblesMark);
			if (e != null) e.setGlowing(false);
			pw.shamblesMark = -1;
		}
		if (pw.soulMarkTicks > 0 && --pw.soulMarkTicks == 0) pw.soulMark = -1;
		if (pw.scanTicks > 0 && --pw.scanTicks == 0) pw.dirty = true;

		if (pw.squeezing) {
			if (!p.getMainHandStack().isOf(ModItems.HEART)) pw.squeezing = false;
			else if (p.age % 8 == 0) Techniques.squeezeHeart(p, p.getMainHandStack());
		}

		// El mar: quien comió la fruta no flota y la fruta no funciona sumergido. El Despertar se pone en pausa.
		if (p.isSubmergedInWater() && !p.hasVehicle() && !p.isCreative() && !p.isSpectator()) {
			if (p.age % 2 == 0) {
				p.addVelocity(0, -0.05, 0);
				p.velocityModified = true;
			}
			if (pw.awakened) {
				pw.awakened = false;
				pw.dirty = true;
			}
			if (pw.floorOp != null) pw.floorOp = null;
		}
	}
}
