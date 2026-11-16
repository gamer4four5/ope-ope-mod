package dev.opeope.client.dismember;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mojang.brigadier.arguments.StringArgumentType;
import dev.opeope.OpeOpeMod;
import dev.opeope.client.mixin.ModelPartAccessor;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.model.ModelPart;
import net.minecraft.client.render.entity.LivingEntityRenderer;
import net.minecraft.client.render.entity.model.EntityModel;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.LivingEntity;
import net.minecraft.registry.Registries;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Solo en desarrollo: /opeope_anatomia dump &lt;entidad|all&gt; recorre el modelo de verdad de la entidad, mide sus partes
 * (pivotes y cajas de los cubos, con la postura de reposo) y escribe una anatomía propuesta en run/anatomy/, para
 * ajustarla a mano y copiarla a data/opeope/anatomy/.
 */
public final class AnatomyDump {
	private AnatomyDump() {}

	public static void register() {
		if (!FabricLoader.getInstance().isDevelopmentEnvironment()) return;
		ClientCommandRegistrationCallback.EVENT.register((dispatcher, access) -> dispatcher.register(
			ClientCommandManager.literal("opeope_anatomia").then(ClientCommandManager.literal("dump")
				.then(ClientCommandManager.argument("entidad", StringArgumentType.greedyString()).executes(ctx -> {
					String arg = StringArgumentType.getString(ctx, "entidad").trim();
					int n = 0;
					if (arg.equals("all")) {
						for (EntityType<?> t : Registries.ENTITY_TYPE) if (dump(ctx.getSource(), t)) n++;
					} else {
						Identifier id = Identifier.tryParse(arg);
						if (id != null && Registries.ENTITY_TYPE.containsId(id) && dump(ctx.getSource(), Registries.ENTITY_TYPE.get(id))) n++;
					}
					ctx.getSource().sendFeedback(Text.literal("Anatomías escritas: " + n + " (run/anatomy/)"));
					return n;
				})))));
	}

	private static boolean dump(FabricClientCommandSource src, EntityType<?> type) {
		MinecraftClient mc = MinecraftClient.getInstance();
		if (mc.world == null) return false;
		try {
			Entity e = type.create(mc.world);
			if (!(e instanceof LivingEntity le)) return false;
			if (!(mc.getEntityRenderDispatcher().getRenderer(le) instanceof LivingEntityRenderer<?, ?> lr)) return false;
			EntityModel<?> model = lr.getModel();
			Map<String, List<ModelPart>> parts = ModelParts.of(model);
			JsonObject segs = new JsonObject();
			String core = parts.containsKey("body") ? "body" : null;
			for (Map.Entry<String, List<ModelPart>> en : parts.entrySet()) {
				String name = en.getKey();
				if (name.contains("/")) continue;
				if (en.getValue().size() > 1) continue;   // solo las de arriba; los hijos van con su padre
				float[] b = bounds(en.getValue());
				if (b == null) continue;
				if (core == null) core = name;
				JsonObject s = new JsonObject();
				if (!name.equals(core)) s.addProperty("parent", core);
				JsonArray mp = new JsonArray();
				mp.add(name);
				s.add("model_parts", mp);
				ModelPart p = en.getValue().get(0);
				JsonArray joint = new JsonArray();
				joint.add(r(p.getDefaultTransform().pivotX));
				joint.add(r(24 - p.getDefaultTransform().pivotY));
				joint.add(r(p.getDefaultTransform().pivotZ));
				s.add("joint", joint);
				JsonArray box = new JsonArray();
				for (float f : b) box.add(r(f));
				s.add("box", box);
				s.addProperty("mass", 0.1);
				JsonArray fn = new JsonArray();
				String n = name.toLowerCase(Locale.ROOT);
				if (n.contains("head")) {
					fn.add("vision");
					fn.add("ai");
					s.addProperty("vital", true);
				}
				if (n.contains("leg")) fn.add("locomotion");
				if (n.equals("right_arm")) fn.add("main_hand");
				if (n.equals("left_arm")) fn.add("off_hand");
				if (n.contains("arm")) fn.add("attack");
				if (n.contains("wing")) fn.add("flight");
				if (n.contains("tail")) fn.add("tail");
				if (!fn.isEmpty()) s.add("function", fn);
				segs.add(name, s);
			}
			if (segs.size() == 0) return false;
			JsonObject o = new JsonObject();
			o.addProperty("core", core);
			o.add("segments", segs);
			o.add("cut_groups", new JsonObject());
			o.addProperty("cap_color", "#E8B4A0");
			o.addProperty("excluded", false);
			Identifier id = Registries.ENTITY_TYPE.getId(type);
			Path out = mc.runDirectory.toPath().resolve("anatomy").resolve(id.getNamespace()).resolve(id.getPath() + ".json");
			Files.createDirectories(out.getParent());
			Files.writeString(out, new GsonBuilder().setPrettyPrinting().create().toJson(o), StandardCharsets.UTF_8);
			return true;
		} catch (Exception ex) {
			OpeOpeMod.LOG.warn("anatomy dump {}: {}", type, ex.toString());
			return false;
		}
	}

	private static double r(float f) {
		return Math.round(f * 100) / 100.0;
	}

	/** La caja (en el marco del JSON: y hacia arriba desde los pies, píxeles) de la parte y sus hijos, en reposo. */
	private static float[] bounds(List<ModelPart> chain) {
		float[] b = {Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE};
		MatrixStack ms = new MatrixStack();
		for (ModelPart p : chain) applyDefault(p, ms);
		walk(chain.get(chain.size() - 1), ms, b, 0);
		return b[0] > b[3] ? null : b;
	}

	private static void applyDefault(ModelPart p, MatrixStack ms) {
		var t = p.getTransform();
		p.resetTransform();
		p.rotate(ms);
		p.setTransform(t);
	}

	private static void walk(ModelPart p, MatrixStack ms, float[] b, int depth) {
		Matrix4f m = ms.peek().getPositionMatrix();
		for (ModelPart.Cuboid c : ((ModelPartAccessor) (Object) p).opeope$cuboids()) {
			for (int i = 0; i < 8; i++) {
				Vector3f v = m.transformPosition(new Vector3f(((i & 1) == 0 ? c.minX : c.maxX) / 16f, ((i & 2) == 0 ? c.minY : c.maxY) / 16f,
					((i & 4) == 0 ? c.minZ : c.maxZ) / 16f));
				float x = v.x * 16, y = 24 - v.y * 16, z = v.z * 16;
				b[0] = Math.min(b[0], x);
				b[1] = Math.min(b[1], y);
				b[2] = Math.min(b[2], z);
				b[3] = Math.max(b[3], x);
				b[4] = Math.max(b[4], y);
				b[5] = Math.max(b[5], z);
			}
		}
		if (depth > 6) return;
		for (ModelPart c : ((ModelPartAccessor) (Object) p).opeope$children().values()) {
			ms.push();
			applyDefault(c, ms);
			walk(c, ms, b, depth + 1);
			ms.pop();
		}
	}
}
