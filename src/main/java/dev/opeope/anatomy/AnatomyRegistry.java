package dev.opeope.anatomy;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.opeope.OpeOpeMod;
import net.fabricmc.fabric.api.resource.ResourceManagerHelper;
import net.fabricmc.fabric.api.resource.SimpleSynchronousResourceReloadListener;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;
import net.minecraft.entity.EntityType;
import net.minecraft.registry.Registries;
import net.minecraft.resource.Resource;
import net.minecraft.resource.ResourceManager;
import net.minecraft.resource.ResourceType;
import net.minecraft.util.Identifier;

import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * Las anatomías. Las de serie se leen del propio jar al arrancar (las usan el servidor y el cliente, también en un
 * servidor dedicado donde el cliente no tiene los datos del mundo); un datapack puede cambiarlas o añadir otras con
 * data/&lt;ns&gt;/anatomy/&lt;ns de la entidad&gt;/&lt;entidad&gt;.json, y esas ganan en el servidor.
 */
public final class AnatomyRegistry {
	private AnatomyRegistry() {}

	private static final Map<Identifier, Anatomy> BUILTIN = new HashMap<>();
	private static final Map<Identifier, Anatomy> DATA = new HashMap<>();

	public static void init() {
		loadBuiltin();
		ResourceManagerHelper.get(ResourceType.SERVER_DATA).registerReloadListener(new SimpleSynchronousResourceReloadListener() {
			@Override
			public Identifier getFabricId() {
				return OpeOpeMod.id("anatomy");
			}

			@Override
			public void reload(ResourceManager manager) {
				DATA.clear();
				for (Map.Entry<Identifier, Resource> e : manager.findResources("anatomy", id -> id.getPath().endsWith(".json")).entrySet()) {
					Identifier entity = entityId(e.getKey().getPath().substring("anatomy/".length()));
					if (entity == null) continue;
					try (Reader r = e.getValue().getReader()) {
						DATA.put(entity, Anatomy.parse(entity, JsonParser.parseReader(r).getAsJsonObject()));
					} catch (Exception ex) {
						OpeOpeMod.LOG.warn("Anatomía {} no válida: {}", e.getKey(), ex.toString());
					}
				}
				OpeOpeMod.LOG.info("Anatomías: {} de serie, {} de datos", BUILTIN.size(), DATA.size());
			}
		});
	}

	/** "minecraft/zombie.json" → minecraft:zombie. */
	private static Identifier entityId(String rel) {
		int slash = rel.indexOf('/');
		if (slash <= 0 || !rel.endsWith(".json")) return null;
		return Identifier.tryParse(rel.substring(0, slash) + ":" + rel.substring(slash + 1, rel.length() - 5));
	}

	private static void loadBuiltin() {
		Optional<ModContainer> mod = FabricLoader.getInstance().getModContainer(OpeOpeMod.MOD_ID);
		if (mod.isEmpty()) return;
		Optional<Path> root = mod.get().findPath("data/" + OpeOpeMod.MOD_ID + "/anatomy");
		if (root.isEmpty()) return;
		try (Stream<Path> files = Files.walk(root.get())) {
			files.filter(p -> p.toString().endsWith(".json")).forEach(p -> {
				String rel = root.get().relativize(p).toString().replace('\\', '/');
				Identifier entity = entityId(rel);
				if (entity == null) return;
				try {
					JsonObject o = JsonParser.parseString(Files.readString(p, StandardCharsets.UTF_8)).getAsJsonObject();
					BUILTIN.put(entity, Anatomy.parse(entity, o));
				} catch (Exception ex) {
					OpeOpeMod.LOG.warn("Anatomía {} no válida: {}", rel, ex.toString());
				}
			});
		} catch (Exception ex) {
			OpeOpeMod.LOG.warn("No se pudieron leer las anatomías de serie: {}", ex.toString());
		}
	}

	public static Anatomy get(Identifier entity) {
		Anatomy a = DATA.get(entity);
		return a != null ? a : BUILTIN.get(entity);
	}

	public static Anatomy get(EntityType<?> type) {
		return get(Registries.ENTITY_TYPE.getId(type));
	}

	/** Devuelve la anatomía del JSON o genera una {@link SyntheticAnatomy} universal (sin ensuciar {@link #get}). */
	public static Anatomy getOrSynthetic(net.minecraft.entity.LivingEntity le) {
		if (le == null) return null;
		Anatomy a = get(le.getType());
		if (a != null) return a;
		return dev.opeope.OpeConfig.SYNTHETIC_ANATOMY ? SyntheticAnatomy.getOrCreate(le) : null;
	}

	public static Anatomy getOrSynthetic(Identifier entityId) {
		if (entityId == null) return null;
		Anatomy a = get(entityId);
		if (a != null) return a;
		if (!dev.opeope.OpeConfig.SYNTHETIC_ANATOMY) return null;
		EntityType<?> type = Registries.ENTITY_TYPE.get(entityId);
		return SyntheticAnatomy.getOrCreate(type);
	}

	public static int count() {
		return BUILTIN.size();
	}
}
