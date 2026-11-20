package dev.opeope.anatomy;

import net.minecraft.entity.EntityDimensions;
import net.minecraft.entity.EntityPose;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.LivingEntity;
import net.minecraft.registry.Registries;
import net.minecraft.util.Identifier;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Anatomía universal de respaldo para mobs sin JSON en {@code data/opeope/anatomy/}. Permite cortar cualquier mob
 * (incluidos mods o mobs sin definición escrita) con el sistema libre de planos y volúmenes CSG.
 */
public final class SyntheticAnatomy {
	private SyntheticAnatomy() {}

	private static final Map<String, Anatomy> CACHE = new ConcurrentHashMap<>();

	public static Anatomy getOrCreate(LivingEntity le) {
		EntityType<?> type = le.getType();
		Identifier id = Registries.ENTITY_TYPE.getId(type);
		float scale = Math.max(0.05f, le.getScale() * (le.isBaby() ? 0.5f : 1f));
		EntityDimensions dim = le.getDimensions(EntityPose.STANDING);
		if (dim.width() <= 0 || dim.height() <= 0) dim = type.getDimensions();
		double wPx = Math.max(4.0, (dim.width() / scale) * 16.0);
		double hPx = Math.max(4.0, (dim.height() / scale) * 16.0);
		String key = id + "|" + Math.round(wPx * 10) + "|" + Math.round(hPx * 10);
		final double fw = wPx, fh = hPx;
		return CACHE.computeIfAbsent(key, k -> Anatomy.synthetic(id, fw, fh, defaultCapColor(id.getPath())));
	}

	public static Anatomy getOrCreate(EntityType<?> type) {
		if (type == null) return null;
		Identifier id = Registries.ENTITY_TYPE.getId(type);
		EntityDimensions dim = type.getDimensions();
		double wPx = Math.max(4.0, dim.width() * 16.0);
		double hPx = Math.max(4.0, dim.height() * 16.0);
		String key = id + "|" + Math.round(wPx * 10) + "|" + Math.round(hPx * 10);
		return CACHE.computeIfAbsent(key, k -> Anatomy.synthetic(id, wPx, hPx, defaultCapColor(id.getPath())));
	}

	private static int defaultCapColor(String path) {
		if (path.contains("blaze") || path.contains("magma")) return 0xE67828;
		if (path.contains("breeze")) return 0x8CD8E8;
		if (path.contains("slime")) return 0x74C258;
		if (path.contains("skeleton") || path.contains("stray") || path.contains("bogged")) return 0xD8D4C4;
		if (path.contains("enderman") || path.contains("endermite")) return 0x363042;
		if (path.contains("shulker")) return 0x9C6EA8;
		if (path.contains("ghast")) return 0xE6E6E6;
		return 0xC86464;
	}
}
