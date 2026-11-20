package dev.opeope.client.dismember;

import dev.opeope.OpeOpeMod;
import dev.opeope.client.mixin.ModelPartAccessor;
import dev.opeope.client.mixin.QuadrupedEntityModelAccessor;
import net.minecraft.client.model.ModelPart;
import net.minecraft.client.model.ModelTransform;
import net.minecraft.client.render.entity.model.BipedEntityModel;
import net.minecraft.client.render.entity.model.EntityModel;
import net.minecraft.client.render.entity.model.PlayerEntityModel;
import net.minecraft.client.render.entity.model.QuadrupedEntityModel;
import net.minecraft.client.render.entity.model.SinglePartEntityModel;
import net.minecraft.entity.LivingEntity;
import net.minecraft.util.Identifier;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

/**
 * Encuentra los {@link ModelPart} de un modelo por nombre (incluidos huesos reparentados de OptiFine CEM / EMF como
 * {@code EMF_head2}), saca copias de estado/postura y gestiona de forma opcional y sin dependencia rígida el contexto de
 * animación de Entity Model Features (EMF) / Entity Texture Features (ETF).
 */
public final class ModelParts {
	private ModelParts() {}

	private static final Map<EntityModel<?>, Map<String, List<ModelPart>>> CACHE = new WeakHashMap<>();
	private static final Set<Class<?>> WARNED = new HashSet<>();
	/** Cada ModelPart creado para un modelo de entidad -> la raíz de su árbol (lo apunta EntityModelLoaderMixin). */
	private static final Map<ModelPart, ModelPart> ROOT_OF = Collections.synchronizedMap(new IdentityHashMap<>());
	/** Por clase de modelo: sus campos de tipo ModelPart (se buscan una vez). */
	private static final Map<Class<?>, List<Field>> FIELDS = new java.util.concurrent.ConcurrentHashMap<>();

	private static final Map<String, List<String>> CEM_ALIASES = Map.ofEntries(
		Map.entry("head", List.of(
			"head", "EMF_head2", "head2", "EMF_head",
			"EMF_neck2", "neck2", "EMF_mane2", "EMF_mane_shake",
			"EMF_crest", "EMF_wattle", "EMF_bill", "EMF_chin",
			"EMF_face", "EMF_nose2", "EMF_mouth", "EMF_brow",
			"EMF_ears", "EMF_ear2", "EMF_left_ear2", "EMF_right_ear2",
			"EMF_horn2", "EMF_horns", "EMF_snout", "EMF_beak", "EMF_trunk"
		)),
		Map.entry("right_arm", List.of("right_arm", "EMF_right_arm2", "right_arm2")),
		Map.entry("left_arm", List.of("left_arm", "EMF_left_arm2", "left_arm2")),
		Map.entry("left_leg", List.of("left_leg", "EMF_left_leg2", "left_leg2")),
		Map.entry("right_hind_leg", List.of("right_hind_leg", "leg1", "EMF_leg1", "EMF_back_right_leg", "back_right_leg")),
		Map.entry("left_hind_leg", List.of("left_hind_leg", "leg2", "EMF_leg2", "EMF_back_left_leg", "back_left_leg")),
		Map.entry("right_front_leg", List.of("right_front_leg", "leg3", "EMF_leg3", "EMF_front_right_leg", "front_right_leg")),
		Map.entry("left_front_leg", List.of("left_front_leg", "leg4", "EMF_leg4", "EMF_front_left_leg", "front_left_leg")),
		Map.entry("right_wing", List.of("right_wing", "EMF_right_wing2", "right_wing2", "wing0")),
		Map.entry("left_wing", List.of("left_wing", "EMF_left_wing2", "left_wing2", "wing1")),
		Map.entry("tail", List.of("tail", "EMF_tail2", "tail2")),
		Map.entry("body1", List.of("body1", "EMF_abdomen", "abdomen", "EMF_body2"))
	);

	public static void registerRoot(ModelPart root) {
		if (root == null) return;
		mark(root, root, 0);
	}

	/** Al recargar los modelos (F3+T, paquetes de recursos) se olvidan los árboles viejos. */
	public static void clearRoots() {
		ROOT_OF.clear();
		CACHE.clear();
	}

	public static int rootsKnown() {
		return ROOT_OF.size();
	}

	private static void mark(ModelPart p, ModelPart root, int depth) {
		ROOT_OF.put(p, root);
		if (depth > 10) return;
		for (ModelPart c : ((ModelPartAccessor) (Object) p).opeope$children().values()) mark(c, root, depth + 1);
	}

	public static boolean isEmfPart(ModelPart p) {
		return p != null && p.getClass().getName().startsWith("traben.entity_model_features.");
	}

	public static boolean isEmfModel(EntityModel<?> model) {
		if (model == null) return false;
		if (EmfCompat.isEmfModel(model)) return true;
		for (List<ModelPart> chain : of(model).values()) {
			if (!chain.isEmpty() && isEmfPart(chain.get(chain.size() - 1))) return true;
		}
		return false;
	}

	public static boolean hasDirectCuboids(ModelPart p) {
		return p != null && !((ModelPartAccessor) (Object) p).opeope$cuboids().isEmpty();
	}

	public static boolean hasCuboidsDeep(ModelPart p) {
		return p != null && hasCuboidsDeep(p, 0, Collections.newSetFromMap(new IdentityHashMap<>()));
	}

	private static boolean hasCuboidsDeep(ModelPart p, int depth, Set<ModelPart> seen) {
		if (p == null || depth > 8 || !seen.add(p)) return false;
		if (hasDirectCuboids(p)) return true;
		for (ModelPart child : ((ModelPartAccessor) (Object) p).opeope$children().values()) {
			if (hasCuboidsDeep(child, depth + 1, seen)) return true;
		}
		return false;
	}

	public static boolean isDescendant(ModelPart ancestor, ModelPart target) {
		if (ancestor == null || target == null || ancestor == target) return false;
		return isDescendant(ancestor, target, 0, Collections.newSetFromMap(new IdentityHashMap<>()));
	}

	private static boolean isDescendant(ModelPart cur, ModelPart target, int depth, Set<ModelPart> seen) {
		if (cur == null || depth > 10 || !seen.add(cur)) return false;
		for (ModelPart child : ((ModelPartAccessor) (Object) cur).opeope$children().values()) {
			if (child == target || isDescendant(child, target, depth + 1, seen)) return true;
		}
		return false;
	}

	/** Último recurso: la raíz del árbol de cualquier campo ModelPart del modelo (pollo, lobo, caballo, zorro…). */
	private static ModelPart rootByFields(EntityModel<?> model) {
		List<Field> fs = FIELDS.computeIfAbsent(model.getClass(), k -> {
			List<Field> out = new ArrayList<>();
			for (Class<?> c = k; c != null && c != Object.class; c = c.getSuperclass()) {
				for (Field f : c.getDeclaredFields()) {
					if (java.lang.reflect.Modifier.isStatic(f.getModifiers()) || f.getType() != ModelPart.class) continue;
					try {
						f.setAccessible(true);
						out.add(f);
					} catch (RuntimeException ignored) {
					}
				}
			}
			return out;
		});
		for (Field f : fs) {
			try {
				Object v = f.get(model);
				if (!(v instanceof ModelPart mp)) continue;
				ModelPart root = ROOT_OF.get(mp);
				if (root != null) return root;
			} catch (IllegalAccessException ignored) {
			}
		}
		return null;
	}

	public static Map<String, List<ModelPart>> of(EntityModel<?> model) {
		if (model == null) return Map.of();
		Map<String, List<ModelPart>> cached = CACHE.get(model);
		boolean forceDynamic = EmfCompat.isEmfModel(model);
		if (cached != null && !forceDynamic) {
			boolean anyEmf = false;
			for (List<ModelPart> c : cached.values()) {
				if (!c.isEmpty() && isEmfPart(c.get(c.size() - 1))) {
					anyEmf = true;
					break;
				}
			}
			if (!anyEmf) return cached;
		}

		Map<String, List<ModelPart>> m = new LinkedHashMap<>();
		boolean emfDetected = forceDynamic;
		try {
			Map<String, ModelPart> top = new LinkedHashMap<>();
			ModelPart base = null;   // la raíz que se dibuja con su propio giro/desplazamiento (la rana la sube 24 píxeles)
			if (model instanceof SinglePartEntityModel<?> sp) {
				base = sp.getPart();
				top.putAll(((ModelPartAccessor) (Object) base).opeope$children());
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
			} else {
				ModelPart root = EmfCompat.getRoot(model);
				if (root == null) root = rootByFields(model);
				if (root != null) {
					if (isEmfPart(root)) base = root;
					top.putAll(((ModelPartAccessor) (Object) root).opeope$children());
				}
			}
			ModelPart extraEmfRoot = EmfCompat.getRoot(model);
			if (extraEmfRoot != null && !(model instanceof SinglePartEntityModel<?>)) {
				for (Map.Entry<String, ModelPart> ce : ((ModelPartAccessor) (Object) extraEmfRoot).opeope$children().entrySet()) {
					top.putIfAbsent(ce.getKey(), ce.getValue());
				}
			}
			if (isEmfPart(base)) emfDetected = true;
			for (Map.Entry<String, ModelPart> e : top.entrySet()) {
				ModelPart mp = e.getValue();
				if (mp == null) continue;
				if (isEmfPart(mp)) emfDetected = true;
				List<ModelPart> chain = base == null ? List.of(mp) : List.of(base, mp);
				m.put(e.getKey(), chain);
				walk(m, e.getKey(), chain, 0);
			}
		} catch (RuntimeException ex) {
			if (WARNED.add(model.getClass())) OpeOpeMod.LOG.warn("Desmembramiento: no se pudo leer el modelo {}: {}", model.getClass().getName(), ex.toString());
			m = new LinkedHashMap<>();
		}
		if (!emfDetected) CACHE.put(model, m);
		else CACHE.remove(model);
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

	/**
	 * Devuelve todas las cadenas de {@link ModelPart} correspondientes a {@code name}, incluidos los huesos reparentados
	 * o renombrados por OptiFine CEM / FreshAnimations ({@code body/EMF_head2}, {@code headwear}, {@code EMF_left_arm2}, etc.).
	 */
	public static List<List<ModelPart>> chainsFor(EntityModel<?> model, String name) {
		Map<String, List<ModelPart>> map = of(model);
		if (map.isEmpty()) return List.of();
		List<List<ModelPart>> found = new ArrayList<>();
		List<String> candidates;
		if ("right_leg".equals(name)) {
			candidates = map.containsKey("right_leg/EMF_left_leg2")
				? List.of("EMF_right_leg2", "right_leg2")
				: List.of("right_leg", "EMF_right_leg2", "right_leg2");
		} else {
			candidates = CEM_ALIASES.getOrDefault(name, List.of(name));
		}
		for (String cand : candidates) {
			List<ModelPart> ch = map.get(cand);
			if (ch != null && !ch.isEmpty()) found.add(ch);
		}
		if ("head".equals(name)) {
			List<ModelPart> hc = map.get("head");
			if (hc != null && !hc.isEmpty() && !hasCuboidsDeep(hc.get(hc.size() - 1))) {
				for (String extra : List.of("hat", "headwear", "nose")) {
					List<ModelPart> ech = map.get(extra);
					if (ech != null && !ech.isEmpty() && hasCuboidsDeep(ech.get(ech.size() - 1))) {
						found.add(ech);
					}
				}
			}
		}
		return deduplicateChains(found);
	}

	/** Elimina duplicados y cadenas cuyo nodo terminal ya es descendiente de otra cadena seleccionada. */
	public static List<List<ModelPart>> deduplicateChains(List<List<ModelPart>> raw) {
		if (raw.size() <= 1) return raw;
		List<List<ModelPart>> out = new ArrayList<>(raw.size());
		Set<ModelPart> terminals = Collections.newSetFromMap(new IdentityHashMap<>());
		for (List<ModelPart> ch : raw) {
			if (ch == null || ch.isEmpty()) continue;
			ModelPart term = ch.get(ch.size() - 1);
			if (!terminals.add(term)) continue;
			out.add(ch);
		}
		if (out.size() <= 1) return out;
		List<List<ModelPart>> filtered = new ArrayList<>(out.size());
		for (int i = 0; i < out.size(); i++) {
			List<ModelPart> cand = out.get(i);
			ModelPart tCand = cand.get(cand.size() - 1);
			boolean covered = false;
			for (int j = 0; j < out.size(); j++) {
				if (i == j) continue;
				ModelPart tOther = out.get(j).get(out.get(j).size() - 1);
				if (isDescendant(tOther, tCand)) {
					covered = true;
					break;
				}
			}
			if (!covered) filtered.add(cand);
		}
		return filtered;
	}

	public static boolean hasAnyCuboidInChains(EntityModel<?> model, Iterable<String> partNames) {
		for (String name : partNames) {
			for (List<ModelPart> ch : chainsFor(model, name)) {
				if (!ch.isEmpty() && hasCuboidsDeep(ch.get(ch.size() - 1))) return true;
			}
		}
		return false;
	}

	// ------------------------------------------------------------------ Snapshot & Restore de postura

	public record SavedPartState(ModelPart part, ModelTransform transform, float xScale, float yScale, float zScale, boolean visible) {}

	public static List<SavedPartState> snapshot(EntityModel<?> model) {
		if (model == null) return List.of();
		List<SavedPartState> out = new ArrayList<>();
		Set<ModelPart> seen = Collections.newSetFromMap(new IdentityHashMap<>());
		if (model instanceof SinglePartEntityModel<?> sp) collectSnapshot(sp.getPart(), out, seen, 0);
		ModelPart emfRoot = EmfCompat.getRoot(model);
		if (emfRoot != null) collectSnapshot(emfRoot, out, seen, 0);
		for (List<ModelPart> ch : of(model).values()) {
			if (!ch.isEmpty()) collectSnapshot(ch.get(ch.size() - 1), out, seen, 0);
		}
		return out;
	}

	private static void collectSnapshot(ModelPart p, List<SavedPartState> out, Set<ModelPart> seen, int depth) {
		if (p == null || depth > 12 || !seen.add(p)) return;
		out.add(new SavedPartState(p, p.getTransform(), p.xScale, p.yScale, p.zScale, p.visible));
		for (ModelPart child : ((ModelPartAccessor) (Object) p).opeope$children().values()) {
			collectSnapshot(child, out, seen, depth + 1);
		}
	}

	public static void restore(List<SavedPartState> saved) {
		if (saved == null) return;
		for (int i = saved.size() - 1; i >= 0; i--) {
			SavedPartState s = saved.get(i);
			s.part.setTransform(s.transform);
			s.part.xScale = s.xScale;
			s.part.yScale = s.yScale;
			s.part.zScale = s.zScale;
			s.part.visible = s.visible;
		}
	}

	// ------------------------------------------------------------------ Puente opcional con EMF / ETF sin dependencia de compilación

	public static Object mountEmfEntity(EntityModel<?> model, LivingEntity victim) {
		return EmfCompat.mount(model, victim);
	}

	public static void unmountEmfEntity(Object token) {
		EmfCompat.unmount(token);
	}

	public static void animateEmfRoot(EntityModel<?> model) {
		EmfCompat.animateRoot(model);
	}

	public static Identifier resolveEmfTexture(EntityModel<?> model, Identifier fallback) {
		return EmfCompat.resolveTexture(model, fallback);
	}

	private static final class EmfCompat {
		private static final boolean AVAILABLE;
		private static final Method ETF_MOUNT;
		private static final Method ETF_UNMOUNT;
		private static final Method ETF_FOR_ENTITY;
		private static final Class<?> ETF_ENTITY_CLASS;
		private static final Method EMF_MANAGER_GET;
		private static final Field EMF_RENDER_COUNT;
		private static final Method EMF_CAPTURE_STATICS;
		private static final Method EMF_RESTORE_STATICS;
		private static final Field EMF_IS_LAYER_PHASE;
		private static final Field EMF_IS_MAIN_PHASE;
		private static final Class<?> IEMF_MODEL_CLASS;
		private static final Method IEMF_IS_EMF;
		private static final Method IEMF_GET_ROOT;
		private static final Field EMF_ROOT_IS_MAIN_MODEL;
		private static final Method EMF_ROOT_ONE_TIME;
		private static final Method EMF_ROOT_ANIMATE;
		private static final Method EMF_ROOT_TEX;

		static {
			boolean ok = false;
			Method etfMount = null, etfUnmount = null, etfForEntity = null;
			Class<?> etfEntity = null;
			Method emfGet = null, emfCapture = null, emfRestore = null;
			Field emfCount = null, emfLayerPhase = null, emfMainPhase = null;
			Class<?> iEmf = null;
			Method iEmfCheck = null, iEmfRoot = null;
			Field rootIsMain = null;
			Method rootOneTime = null, rootAnimate = null, rootTex = null;
			try {
				Class<?> etfState = Class.forName("traben.entity_texture_features.features.state.ETFState");
				Class<?> etfRenderState = Class.forName("traben.entity_texture_features.features.state.ETFEntityRenderState");
				etfEntity = Class.forName("traben.entity_texture_features.utils.ETFEntity");
				etfMount = etfState.getMethod("mount", etfRenderState);
				etfUnmount = etfState.getMethod("unMount");
				etfForEntity = etfRenderState.getMethod("forEntity", etfEntity);

				Class<?> emfMgr = Class.forName("traben.entity_model_features.EMFManager");
				emfGet = emfMgr.getMethod("getInstance");
				emfCount = emfMgr.getField("entityRenderCount");

				Class<?> emfState = Class.forName("traben.entity_model_features.models.animation.state.EMFState");
				Class<?> snap = Class.forName("traben.entity_model_features.models.animation.state.EMFState$EMFStateStaticSnapshot");
				emfCapture = emfState.getMethod("captureStatics");
				emfRestore = snap.getMethod("restoreStatics");
				emfLayerPhase = emfState.getField("isLayerPhase");
				emfMainPhase = emfState.getField("isMainPhase");

				iEmf = Class.forName("traben.entity_model_features.models.IEMFModel");
				iEmfCheck = iEmf.getMethod("emf$isEMFModel");
				iEmfRoot = iEmf.getMethod("emf$getEMFRootModel");

				Class<?> rootCls = Class.forName("traben.entity_model_features.models.parts.EMFModelPartRoot");
				rootIsMain = rootCls.getField("isMainModel");
				rootOneTime = rootCls.getMethod("oneTimeRunnable");
				rootAnimate = rootCls.getMethod("animate");
				rootTex = rootCls.getMethod("getTopLevelJemTexture");
				ok = true;
			} catch (Throwable ignored) {
			}
			AVAILABLE = ok;
			ETF_MOUNT = etfMount;
			ETF_UNMOUNT = etfUnmount;
			ETF_FOR_ENTITY = etfForEntity;
			ETF_ENTITY_CLASS = etfEntity;
			EMF_MANAGER_GET = emfGet;
			EMF_RENDER_COUNT = emfCount;
			EMF_CAPTURE_STATICS = emfCapture;
			EMF_RESTORE_STATICS = emfRestore;
			EMF_IS_LAYER_PHASE = emfLayerPhase;
			EMF_IS_MAIN_PHASE = emfMainPhase;
			IEMF_MODEL_CLASS = iEmf;
			IEMF_IS_EMF = iEmfCheck;
			IEMF_GET_ROOT = iEmfRoot;
			EMF_ROOT_IS_MAIN_MODEL = rootIsMain;
			EMF_ROOT_ONE_TIME = rootOneTime;
			EMF_ROOT_ANIMATE = rootAnimate;
			EMF_ROOT_TEX = rootTex;
		}

		private record MountToken(Object statics, boolean prevLayer, boolean prevMain, boolean mountedEtf, Object root, boolean prevRootMain) {}

		static boolean isEmfModel(EntityModel<?> model) {
			if (!AVAILABLE || model == null || !IEMF_MODEL_CLASS.isInstance(model)) return false;
			try {
				return (boolean) IEMF_IS_EMF.invoke(model);
			} catch (Throwable ignored) {
				return false;
			}
		}

		static ModelPart getRoot(EntityModel<?> model) {
			if (!isEmfModel(model)) return null;
			try {
				return (ModelPart) IEMF_GET_ROOT.invoke(model);
			} catch (Throwable ignored) {
				return null;
			}
		}

		static void animateRoot(EntityModel<?> model) {
			ModelPart root = getRoot(model);
			if (root == null) return;
			try {
				EMF_ROOT_ONE_TIME.invoke(root);
				EMF_ROOT_ANIMATE.invoke(root);
			} catch (Throwable ignored) {
			}
		}

		static Identifier resolveTexture(EntityModel<?> model, Identifier fallback) {
			ModelPart root = getRoot(model);
			if (root == null) return fallback;
			try {
				Object tex = EMF_ROOT_TEX.invoke(root);
				return tex instanceof Identifier id ? id : fallback;
			} catch (Throwable ignored) {
				return fallback;
			}
		}

		static Object mount(EntityModel<?> model, LivingEntity victim) {
			if (!AVAILABLE || victim == null || !ETF_ENTITY_CLASS.isInstance(victim)) return null;
			Object statics = null;
			boolean prevLayer = false, prevMain = false, mounted = false, prevRootMain = false;
			Object root = null;
			try {
				statics = EMF_CAPTURE_STATICS.invoke(null);
				prevLayer = EMF_IS_LAYER_PHASE.getBoolean(null);
				prevMain = EMF_IS_MAIN_PHASE.getBoolean(null);
				EMF_IS_LAYER_PHASE.setBoolean(null, false);
				EMF_IS_MAIN_PHASE.setBoolean(null, true);
				Object mgr = EMF_MANAGER_GET.invoke(null);
				if (mgr != null) EMF_RENDER_COUNT.setLong(mgr, EMF_RENDER_COUNT.getLong(mgr) + 1L);
				root = getRoot(model);
				if (root != null) {
					prevRootMain = EMF_ROOT_IS_MAIN_MODEL.getBoolean(root);
					EMF_ROOT_IS_MAIN_MODEL.setBoolean(root, true);
				}
				Object rs = ETF_FOR_ENTITY.invoke(null, victim);
				if (rs != null) {
					ETF_MOUNT.invoke(null, rs);
					mounted = true;
				}
			} catch (Throwable ignored) {
			}
			return new MountToken(statics, prevLayer, prevMain, mounted, root, prevRootMain);
		}

		static void unmount(Object tokObj) {
			if (!AVAILABLE || !(tokObj instanceof MountToken tok)) return;
			try {
				if (tok.mountedEtf) ETF_UNMOUNT.invoke(null);
			} catch (Throwable ignored) {
			}
			try {
				if (tok.root != null) EMF_ROOT_IS_MAIN_MODEL.setBoolean(tok.root, tok.prevRootMain);
				EMF_IS_LAYER_PHASE.setBoolean(null, tok.prevLayer);
				EMF_IS_MAIN_PHASE.setBoolean(null, tok.prevMain);
				if (tok.statics != null) EMF_RESTORE_STATICS.invoke(tok.statics);
			} catch (Throwable ignored) {
			}
		}
	}
}
