package dev.opeope.client.dismember;

import dev.opeope.OpeOpeMod;
import dev.opeope.client.mixin.ModelPartAccessor;
import dev.opeope.client.mixin.QuadrupedEntityModelAccessor;
import net.minecraft.client.model.ModelPart;
import net.minecraft.client.render.entity.model.BipedEntityModel;
import net.minecraft.client.render.entity.model.EntityModel;
import net.minecraft.client.render.entity.model.PlayerEntityModel;
import net.minecraft.client.render.entity.model.QuadrupedEntityModel;
import net.minecraft.client.render.entity.model.SinglePartEntityModel;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

/**
 * Encuentra los ModelPart de un modelo por su nombre ("head", "right_arm", o una ruta "head/jaw"). Cada nombre da la
 * cadena desde la parte de arriba hasta la buscada (para girar por los padres antes de dibujarla sola). Se calcula una
 * vez por modelo; nada de reflexión por fotograma.
 * <ul>
 * <li>SinglePartEntityModel: el árbol con sus nombres (aldeano, illager, creeper, gólem, araña…).</li>
 * <li>BipedEntityModel (zombi, esqueleto, jugador, piglin, enderman…): sus campos conocidos.</li>
 * <li>QuadrupedEntityModel (vaca, cerdo, oveja…): sus campos, con un accessor.</li>
 * </ul>
 * Si no sabe leer un modelo devuelve un mapa vacío (no se oculta ni dibuja nada; nunca rompe).
 */
public final class ModelParts {
	private ModelParts() {}

	private static final Map<EntityModel<?>, Map<String, List<ModelPart>>> CACHE = new WeakHashMap<>();
	private static final Set<Class<?>> WARNED = new HashSet<>();

	public static Map<String, List<ModelPart>> of(EntityModel<?> model) {
		Map<String, List<ModelPart>> m = CACHE.get(model);
		if (m != null) return m;
		m = new LinkedHashMap<>();
		try {
			Map<String, ModelPart> top = new LinkedHashMap<>();
			if (model instanceof SinglePartEntityModel<?> sp) {
				top.putAll(((ModelPartAccessor) (Object) sp.getPart()).opeope$children());
			} else if (model instanceof BipedEntityModel<?> b) {
				top.put("head", b.head);
				top.put("hat", b.hat);
				top.put("body", b.body);
				top.put("right_arm", b.rightArm);
				top.put("left_arm", b.leftArm);
				top.put("right_leg", b.rightLeg);
				top.put("left_leg", b.leftLeg);
				if (model instanceof PlayerEntityModel<?> p) {
					top.put("jacket", p.jacket);
					top.put("right_sleeve", p.rightSleeve);
					top.put("left_sleeve", p.leftSleeve);
					top.put("right_pants", p.rightPants);
					top.put("left_pants", p.leftPants);
				}
			} else if (model instanceof QuadrupedEntityModel<?> q) {
				QuadrupedEntityModelAccessor a = (QuadrupedEntityModelAccessor) q;
				top.put("head", a.opeope$head());
				top.put("body", a.opeope$body());
				top.put("right_hind_leg", a.opeope$rightHindLeg());
				top.put("left_hind_leg", a.opeope$leftHindLeg());
				top.put("right_front_leg", a.opeope$rightFrontLeg());
				top.put("left_front_leg", a.opeope$leftFrontLeg());
			}
			for (Map.Entry<String, ModelPart> e : top.entrySet()) {
				if (e.getValue() == null) continue;
				List<ModelPart> chain = List.of(e.getValue());
				m.put(e.getKey(), chain);
				walk(m, e.getKey(), chain, 0);
			}
		} catch (RuntimeException ex) {
			if (WARNED.add(model.getClass())) OpeOpeMod.LOG.warn("Desmembramiento: no se pudo leer el modelo {}: {}", model.getClass().getName(), ex.toString());
			m = new LinkedHashMap<>();
		}
		CACHE.put(model, m);
		return m;
	}

	/** Los hijos con su ruta ("head/nose") y, si no choca, también con su nombre solo ("nose"). */
	private static void walk(Map<String, List<ModelPart>> m, String path, List<ModelPart> chain, int depth) {
		if (depth > 8) return;
		ModelPart last = chain.get(chain.size() - 1);
		for (Map.Entry<String, ModelPart> c : ((ModelPartAccessor) (Object) last).opeope$children().entrySet()) {
			List<ModelPart> sub = new ArrayList<>(chain);
			sub.add(c.getValue());
			List<ModelPart> fixed = List.copyOf(sub);
			String p = path + "/" + c.getKey();
			m.put(p, fixed);
			m.putIfAbsent(c.getKey(), fixed);
			walk(m, p, fixed, depth + 1);
		}
	}

	public static List<ModelPart> chain(EntityModel<?> model, String name) {
		return of(model).get(name);
	}
}
