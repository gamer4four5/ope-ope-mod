package dev.opeope.power;

import dev.opeope.net.Payloads;
import net.minecraft.advancement.criterion.Criteria;
import net.minecraft.entity.EntityInteraction;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.ItemEntity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.entity.mob.ZombieVillagerEntity;
import net.minecraft.entity.passive.VillagerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.particle.DustParticleEffect;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.Hand;
import net.minecraft.util.math.Vec3d;
import org.joml.Vector3f;

/**
 * La medicina de la Ope Ope no Mi: la fruta «cura cualquier enfermedad» sacando del cuerpo lo que sobra (bacterias,
 * virus, drogas, el plomo ámbar), sin sangre ni cicatriz. Aquí: la impureza que se ve salir del pecho en la cirugía
 * (Tab + C mantenido) y la cura de un aldeano zombi, al que se le extrae la infección.
 */
public final class SurgicalCure {
	private SurgicalCure() {}

	/** Lo que sale del cuerpo: un violeta apagado, como en el efecto del cliente. */
	private static final DustParticleEffect IMPURITY = new DustParticleEffect(new Vector3f(0.54f, 0.3f, 1.0f), 1.1f);
	/** El oro de la cura. */
	private static final DustParticleEffect CURE_GOLD = new DustParticleEffect(new Vector3f(1.0f, 0.85f, 0.4f), 1.2f);

	/**
	 * Le extrae la infección a un aldeano zombi: vuelve a ser aldeano con todo lo que tenía, como en la cura normal (su
	 * profesión, nivel y tipo, sus tratos, su experiencia y lo que se cuenta de cada jugador), mareado un rato y
	 * agradecido a Law. Devuelve el aldeano nuevo, o null si no se pudo (también si ya está muriendo: no se resucita).
	 */
	public static VillagerEntity cureZombieVillager(ServerPlayerEntity p, ZombieVillagerEntity zv) {
		if (zv == null || zv.isRemoved() || !zv.isAlive()) return null;
		var data = zv.getVillagerData();
		// lo que recuerda de cuando era aldeano (la cura normal también se lo devuelve)
		NbtCompound before = new NbtCompound();
		zv.writeCustomDataToNbt(before);
		// lo que llevaba en las manos (lo que recogió del suelo, una herramienta…) cae al suelo en vez de perderse;
		// la armadura pasa al aldeano con la conversión
		for (Hand hand : new Hand[]{Hand.MAIN_HAND, Hand.OFF_HAND}) {
			ItemStack held = zv.getStackInHand(hand);
			if (held.isEmpty()) continue;
			zv.getWorld().spawnEntity(new ItemEntity(zv.getWorld(), zv.getX(), zv.getY() + 0.6, zv.getZ(), held.copy()));
			zv.setStackInHand(hand, ItemStack.EMPTY);
		}
		VillagerEntity villager = zv.convertTo(EntityType.VILLAGER, true);
		if (villager == null) return null;
		villager.setVillagerData(data);
		// sus tratos, su experiencia y sus cotilleos vuelven con él; al leerlos, el aldeano vuelve a poder recoger comida
		// y su cerebro se rehace con su profesión (si no, uno de nivel 1 sin experiencia la perdería al momento)
		NbtCompound now = new NbtCompound();
		villager.writeCustomDataToNbt(now);
		for (String k : new String[]{"Offers", "Gossips", "Xp"}) {
			if (before.contains(k)) now.put(k, before.get(k));
		}
		villager.readCustomDataFromNbt(now);
		villager.setCanPickUpLoot(true);
		// se lo agradece a Law como a quien lo cura con la manzana dorada (precios mejores) y cuenta para el logro
		p.getServerWorld().handleInteraction(EntityInteraction.ZOMBIE_VILLAGER_CURED, p, villager);
		Criteria.CURED_ZOMBIE_VILLAGER.trigger(p, zv, villager);
		villager.addStatusEffect(new StatusEffectInstance(StatusEffects.NAUSEA, 200, 0));
		villager.getWorld().playSound(null, villager.getX(), villager.getY(), villager.getZ(),
			SoundEvents.ENTITY_ZOMBIE_VILLAGER_CONVERTED, SoundCategory.PLAYERS, 1.0f, 1.0f);
		extractFx(p, villager, true);
		p.sendMessage(Text.literal("§bCirugía: ¡infección extraída! §fEl aldeano está curado"), true);
		return villager;
	}

	/**
	 * Se ve cómo sale la impureza: se abre una incisión cúbica en el pecho y la mancha sube y se deshace en el aire.
	 * Con cure, además, el estallido dorado de la cura alrededor del cuerpo.
	 */
	public static void extractFx(ServerPlayerEntity p, LivingEntity target, boolean cure) {
		Vec3d a = target.getPos().add(0, target.getHeight() * 0.62, 0);
		Vec3d b = a.add(0, 1.6, 0);
		LawFx.send(p, Payloads.LawFx.EXTRACT, target, a, b, Math.max(0.4f, target.getWidth()), cure ? 1f : 0f, 28);
		ServerWorld w = p.getServerWorld();
		w.spawnParticles(IMPURITY, a.x, a.y, a.z, 8, 0.12, 0.12, 0.12, 0);
		w.spawnParticles(CutEngine.ROOM_DUST, a.x, a.y, a.z, 6, 0.15, 0.15, 0.15, 0);
		w.playSound(null, a.x, a.y, a.z, SoundEvents.BLOCK_AMETHYST_BLOCK_RESONATE, SoundCategory.PLAYERS, 0.45f, 1.8f);
		w.playSound(null, b.x, b.y, b.z, SoundEvents.BLOCK_FIRE_EXTINGUISH, SoundCategory.PLAYERS, 0.25f, 1.9f);
		if (cure) {
			Vec3d c = target.getBoundingBox().getCenter();
			double r = Math.max(0.4, target.getWidth() * 0.8);
			w.spawnParticles(CURE_GOLD, c.x, c.y, c.z, 24, r, target.getHeight() * 0.4, r, 0);
			w.spawnParticles(ParticleTypes.HAPPY_VILLAGER, c.x, c.y, c.z, 12, r, target.getHeight() * 0.4, r, 0);
			w.playSound(null, c.x, c.y, c.z, SoundEvents.BLOCK_AMETHYST_BLOCK_CHIME, SoundCategory.PLAYERS, 0.9f, 1.3f);
		}
	}
}
