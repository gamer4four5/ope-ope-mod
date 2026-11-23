package dev.opeope.power;

import dev.opeope.net.Payloads;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.entity.passive.MerchantEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.Hand;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;

import java.util.Comparator;
import java.util.List;

/**
 * Scan + Shambles en masa (スキャン → シャンブルズ). Con el Scan Law ve como por rayos X lo que lleva encima cada uno;
 * si vuelve a pulsar Scan mientras dura, un Shambles le trae todo lo que llevan en las manos los escaneados y les deja
 * una piedra en su lugar. Así dejó a los marines del G-5 sin sus Den Den Mushi.
 * <p>
 * Escaneados son los mobs (no los jugadores) dentro del ROOM propio en esta dimensión o, sin ROOM, a menos de
 * {@link #RANGE} bloques de donde se lanzó el Scan (la esfera que se ve con rayos X); no cuentan los que controla Law
 * ni los comerciantes. La piedra no se suelta al morir (probabilidad 0), para que no salgan piedras de la nada.
 */
public final class ScanSteal {
	private ScanSteal() {}

	/** Sin ROOM, el radio de la esfera del Scan (en bloques): la de sus rayos X y la de este robo. */
	public static final double RANGE = 8.0;
	/** Lo más que se roba de una vez. */
	private static final int MAX_ITEMS = 24;
	/** Cuántos seres suenan a la vez como mucho (que no sea una ametralladora de sonidos). */
	private static final int MAX_SOUNDS = 8;
	private static final Hand[] HANDS = {Hand.MAIN_HAND, Hand.OFF_HAND};

	/**
	 * Le quita a cada escaneado lo que lleva en las manos (los más cercanos primero, {@link #MAX_ITEMS} cosas como
	 * mucho), se lo da a Law y le pone una piedra en esa mano. Acaba el Scan. Devuelve cuántas cosas robó.
	 */
	public static int steal(ServerPlayerEntity p, PlayerPower pw) {
		ServerWorld w = p.getServerWorld();
		Room room = pw.room != null && pw.room.dim == w.getRegistryKey() ? pw.room
			: pw.rroom != null && pw.rroom.dim == w.getRegistryKey() ? pw.rroom : null;
		Vec3d me = p.getPos();
		// sin ROOM, la esfera donde se lanzó el Scan (si no se sabe, alrededor de Law)
		Vec3d at = pw.scanCenter != null ? pw.scanCenter : me.add(0, 1, 0);
		Box area = room != null ? new Box(room.center, room.center).expand(room.radius) : new Box(at, at).expand(RANGE);
		// ni a los suyos (Kokoro Shambles) ni a los comerciantes: lo que enseña un aldeano al comerciar es una copia
		// de lo que vende, y robarlo sería comerciar gratis
		List<MobEntity> mobs = w.getEntitiesByClass(MobEntity.class, area, m -> m.isAlive()
			&& !(m instanceof MerchantEntity) && !pw.controlledMobs.contains(m.getUuid())
			&& (room != null ? room.contains(m.getBoundingBox().getCenter())
				: m.getBoundingBox().getCenter().squaredDistanceTo(at) <= RANGE * RANGE));
		mobs.sort(Comparator.comparingDouble(m -> m.squaredDistanceTo(me)));

		Vec3d chest = me.add(0, p.getHeight() * 0.62, 0);
		int count = 0, voices = 0;
		for (MobEntity m : mobs) {
			if (count >= MAX_ITEMS) break;
			boolean took = false;
			for (Hand hand : HANDS) {
				if (count >= MAX_ITEMS) break;
				ItemStack held = m.getStackInHand(hand);
				// la piedra que ya le dejó otro Shambles no se roba (no saldría nada nuevo de ahí)
				if (held.isEmpty() || held.isOf(Items.COBBLESTONE)) continue;
				ItemStack stolen = held.copy();
				Vec3d from = handPos(m, hand);
				// el intercambio: la cosa para Law y la piedra para él, que no la suelta al morir
				m.setStackInHand(hand, new ItemStack(Items.COBBLESTONE));
				m.setEquipmentDropChance(hand == Hand.MAIN_HAND ? EquipmentSlot.MAINHAND : EquipmentSlot.OFFHAND, 0f);
				p.getInventory().offerOrDrop(stolen);
				LawFx.send(p, Payloads.LawFx.SCAN_STEAL, m, from, chest, 0.3f, 1f, 24);
				w.spawnParticles(ParticleTypes.REVERSE_PORTAL, from.x, from.y, from.z, 8, 0.08, 0.08, 0.08, 0.03);
				w.spawnParticles(CutEngine.ROOM_DUST, from.x, from.y, from.z, 5, 0.1, 0.1, 0.1, 0);
				count++;
				took = true;
			}
			if (took && voices < MAX_SOUNDS) {
				voices++;
				w.playSound(null, m.getX(), m.getY(), m.getZ(), SoundEvents.ENTITY_ENDERMAN_TELEPORT, SoundCategory.PLAYERS, 0.35f, 1.9f);
			}
		}

		// el Scan se gasta con el Shambles
		pw.scanTicks = 0;
		pw.scanCenter = null;
		pw.dirty = true;
		if (count > 0) {
			w.playSound(null, me.x, me.y, me.z, SoundEvents.BLOCK_AMETHYST_BLOCK_CHIME, SoundCategory.PLAYERS, 1.0f, 1.95f);
			w.playSound(null, me.x, me.y, me.z, SoundEvents.BLOCK_AMETHYST_BLOCK_RESONATE, SoundCategory.PLAYERS, 0.8f, 1.55f);
			w.playSound(null, me.x, me.y, me.z, SoundEvents.BLOCK_NOTE_BLOCK_CHIME.value(), SoundCategory.PLAYERS, 0.7f, 1.88f);
			w.playSound(null, me.x, me.y, me.z, SoundEvents.ENTITY_ITEM_PICKUP, SoundCategory.PLAYERS, 0.9f, 1.45f);
			// lo robado llega como polvo de ROOM a la cintura (más arriba taparía la vista en primera persona)
			w.spawnParticles(CutEngine.ROOM_DUST, me.x, me.y + 0.9, me.z, 8, 0.35, 0.25, 0.35, 0);
			p.sendMessage(Text.literal("§bShambles: " + count + (count == 1 ? " cosa robada" : " cosas robadas")
				+ " §7(cambiadas por piedras)"), true);
		} else {
			w.playSound(null, me.x, me.y, me.z, SoundEvents.BLOCK_NOTE_BLOCK_BASS.value(), SoundCategory.PLAYERS, 0.8f, 0.6f);
			p.sendMessage(Text.literal("§7Scan: nadie lleva nada en las manos"), true);
		}
		return count;
	}

	/** Dónde lleva esa mano (más o menos): algo por encima del centro, a su derecha o a su izquierda y un poco delante. */
	private static Vec3d handPos(MobEntity m, Hand hand) {
		Vec3d c = m.getBoundingBox().getCenter().add(0, Math.min(0.3, m.getHeight() * 0.2), 0);
		Vec3d look = m.getRotationVector();
		Vec3d right = look.crossProduct(new Vec3d(0, 1, 0));
		if (right.lengthSquared() < 1e-4) return c;
		double side = m.getWidth() * 0.45 * (hand == Hand.MAIN_HAND ? 1 : -1);
		Vec3d flat = new Vec3d(look.x, 0, look.z);
		Vec3d front = flat.lengthSquared() < 1e-4 ? Vec3d.ZERO : flat.normalize().multiply(m.getWidth() * 0.3);
		return c.add(right.normalize().multiply(side)).add(front);
	}
}
