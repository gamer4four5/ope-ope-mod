package dev.opeope.anatomy;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * La anatomía de un tipo de ser vivo, leída de data/&lt;ns&gt;/anatomy/&lt;ns de la entidad&gt;/&lt;entidad&gt;.json. El servidor no
 * tiene modelos: con esto sabe qué partes hay, de quién cuelga cada una, su caja aproximada, su masa y para qué sirve.
 * <p>
 * Coordenadas en píxeles de modelo (1/16 de bloque): x e z como en ModelPart, y hacia arriba desde los pies
 * (y = 24 − y del modelo para un modelo de 24 px de alto, el de casi todos los mobs). Así una caja del JSON es lo que se
 * ve, sin girar.
 */
public final class Anatomy {
	/** Una parte del cuerpo. box = {x0, y0, z0, x1, y1, z1}; joint = dónde se une a su padre. */
	public record Segment(String id, String parent, List<String> modelParts, double[] joint, double[] box, double mass,
						  Set<String> functions, boolean vital) {
		public boolean has(String function) {
			return functions.contains(function);
		}

		public double volume() {
			return Math.max(0, box[3] - box[0]) * Math.max(0, box[4] - box[1]) * Math.max(0, box[5] - box[2]);
		}

		public Vec3d center() {
			return new Vec3d((box[0] + box[3]) / 2, (box[1] + box[4]) / 2, (box[2] + box[5]) / 2);
		}

		public Vec3d jointVec() {
			return joint == null ? center() : new Vec3d(joint[0], joint[1], joint[2]);
		}
	}

	public final Identifier entity;
	public final String core;
	public final Map<String, Segment> segments;
	public final Map<String, List<String>> cutGroups;
	public final int capColor;
	public final boolean excluded;
	/** Partes con "locomotion" (piernas o patas). */
	public final int legs;
	/** Altura de las piernas (lo que baja el cuerpo si se pierden todas), en píxeles. */
	public final double legHeight;
	/** Altura total del modelo, en píxeles. */
	public final double height;

	private Anatomy(Identifier entity, String core, Map<String, Segment> segments, Map<String, List<String>> cutGroups,
					int capColor, boolean excluded) {
		this.entity = entity;
		this.core = core;
		this.segments = Collections.unmodifiableMap(segments);
		this.cutGroups = Collections.unmodifiableMap(cutGroups);
		this.capColor = capColor;
		this.excluded = excluded;
		int n = 0;
		double lh = 0, h = 0;
		for (Segment s : segments.values()) {
			h = Math.max(h, s.box[4]);
			if (s.has("locomotion")) {
				n++;
				lh = Math.max(lh, s.box[4]);
			}
		}
		this.legs = n;
		this.legHeight = lh;
		this.height = Math.max(1, h);
	}

	public Segment get(String id) {
		return segments.get(id);
	}

	/** Bípedo: exactamente dos piernas. */
	public boolean biped() {
		return legs == 2;
	}

	public List<String> children(String id) {
		List<String> out = new ArrayList<>();
		for (Segment s : segments.values()) if (id.equals(s.parent)) out.add(s.id);
		return out;
	}

	/** La parte y todo lo que cuelga de ella (en el orden del JSON). */
	public Set<String> subtree(String id) {
		Set<String> out = new LinkedHashSet<>();
		if (!segments.containsKey(id)) return out;
		out.add(id);
		boolean grew = true;
		while (grew) {
			grew = false;
			for (Segment s : segments.values()) {
				if (s.parent != null && out.contains(s.parent) && out.add(s.id)) grew = true;
			}
		}
		return out;
	}

	/** ¿Está esta parte unida al núcleo (ni ella ni ninguno de sus padres está en detached)? */
	public boolean attached(String id, Collection<String> detached) {
		Set<String> seen = new HashSet<>();
		String cur = id;
		while (cur != null && seen.add(cur)) {
			if (detached.contains(cur)) return false;
			Segment s = segments.get(cur);
			cur = s == null ? null : s.parent;
		}
		return true;
	}

	/** La caja unión de esas partes, en píxeles (null si no hay ninguna). */
	public double[] union(Collection<String> ids) {
		double[] u = null;
		for (String id : ids) {
			Segment s = segments.get(id);
			if (s == null) continue;
			if (u == null) u = s.box.clone();
			else {
				for (int i = 0; i < 3; i++) u[i] = Math.min(u[i], s.box[i]);
				for (int i = 3; i < 6; i++) u[i] = Math.max(u[i], s.box[i]);
			}
		}
		return u;
	}

	public double mass(Collection<String> ids) {
		double m = 0;
		for (String id : ids) {
			Segment s = segments.get(id);
			if (s != null) m += s.mass;
		}
		return m;
	}

	// ------------------------------------------------------------------ del espacio del modelo al mundo

	/**
	 * Un punto del JSON (píxeles) → desplazamiento desde los pies de la entidad, en bloques, con el giro del cuerpo y la
	 * escala. Es la misma cuenta que hace LivingEntityRenderer (giro 180 − bodyYaw y escala −1, −1, 1).
	 */
	public static Vec3d toWorld(double x, double y, double z, float bodyYaw, double scale) {
		double lx = -x / 16.0 * scale, ly = y / 16.0 * scale, lz = z / 16.0 * scale;
		double a = Math.toRadians(180 - bodyYaw), c = Math.cos(a), s = Math.sin(a);
		return new Vec3d(lx * c + lz * s, ly, -lx * s + lz * c);
	}

	public static Vec3d toWorld(Vec3d p, float bodyYaw, double scale) {
		return toWorld(p.x, p.y, p.z, bodyYaw, scale);
	}

	// ------------------------------------------------------------------ corte libre por plano (n·P = d, en píxeles del JSON)

	private static final int[][] EDGES = {{0, 1}, {2, 3}, {4, 5}, {6, 7}, {0, 2}, {1, 3}, {4, 6}, {5, 7}, {0, 4}, {1, 5}, {2, 6}, {3, 7}};

	private static double[] corner(double[] b, int i) {
		return new double[]{(i & 1) == 0 ? b[0] : b[3], (i & 2) == 0 ? b[1] : b[4], (i & 4) == 0 ? b[2] : b[5]};
	}

	private static double side(float[] pl, double[] p) {
		return pl[0] * p[0] + pl[1] * p[1] + pl[2] * p[2] - pl[3];
	}

	/** −1 la caja está entera del lado n·P &lt; d, +1 entera del otro, 0 la atraviesa. */
	public static int classify(double[] b, float[] pl) {
		boolean pos = false, neg = false;
		for (int i = 0; i < 8; i++) {
			double s = side(pl, corner(b, i));
			if (s > 0.01) pos = true;
			else if (s < -0.01) neg = true;
		}
		return pos && neg ? 0 : pos ? 1 : -1;
	}

	/** Qué parte (0..1) de la caja queda del lado positivo (muestreo de 4×4×4 puntos). */
	public static double positiveFraction(double[] b, float[] pl) {
		int n = 0, in = 0;
		for (int x = 0; x < 4; x++)
			for (int y = 0; y < 4; y++)
				for (int z = 0; z < 4; z++) {
					double[] p = {b[0] + (b[3] - b[0]) * (x + 0.5) / 4, b[1] + (b[4] - b[1]) * (y + 0.5) / 4, b[2] + (b[5] - b[2]) * (z + 0.5) / 4};
					n++;
					if (side(pl, p) > 0) in++;
				}
		return in / (double) n;
	}

	/** La caja de lo que queda de b del lado positivo (null si nada). */
	public static double[] clipBox(double[] b, float[] pl, boolean positive) {
		double[] out = {Double.MAX_VALUE, Double.MAX_VALUE, Double.MAX_VALUE, -Double.MAX_VALUE, -Double.MAX_VALUE, -Double.MAX_VALUE};
		boolean any = false;
		for (int i = 0; i < 8; i++) {
			double[] c = corner(b, i);
			double s = side(pl, c);
			if (positive ? s >= 0 : s <= 0) {
				grow(out, c);
				any = true;
			}
		}
		for (double[] c : section(b, pl)) {
			grow(out, c);
			any = true;
		}
		return any ? out : null;
	}

	private static void grow(double[] o, double[] c) {
		for (int k = 0; k < 3; k++) {
			o[k] = Math.min(o[k], c[k]);
			o[k + 3] = Math.max(o[k + 3], c[k]);
		}
	}

	/** La sección del plano en la caja: un polígono convexo, ordenado alrededor de su centro (vacío si no la corta). */
	public static List<double[]> section(double[] b, float[] pl) {
		List<double[]> pts = new ArrayList<>();
		for (int[] e : EDGES) {
			double[] a = corner(b, e[0]), c = corner(b, e[1]);
			double sa = side(pl, a), sc = side(pl, c);
			if ((sa > 0) == (sc > 0) || Math.abs(sa - sc) < 1e-9) continue;
			double t = sa / (sa - sc);
			pts.add(new double[]{a[0] + (c[0] - a[0]) * t, a[1] + (c[1] - a[1]) * t, a[2] + (c[2] - a[2]) * t});
		}
		if (pts.size() < 3) return List.of();
		double cx = 0, cy = 0, cz = 0;
		for (double[] p : pts) {
			cx += p[0];
			cy += p[1];
			cz += p[2];
		}
		cx /= pts.size();
		cy /= pts.size();
		cz /= pts.size();
		// una base en el plano para ordenar por ángulo
		double nx = pl[0], ny = pl[1], nz = pl[2];
		double ux, uy, uz;
		if (Math.abs(nx) < 0.9) {
			ux = 0;
			uy = nz;
			uz = -ny;
		} else {
			ux = -nz;
			uy = 0;
			uz = nx;
		}
		double ul = Math.sqrt(ux * ux + uy * uy + uz * uz);
		ux /= ul;
		uy /= ul;
		uz /= ul;
		double vx = ny * uz - nz * uy, vy = nz * ux - nx * uz, vz = nx * uy - ny * ux;
		final double fcx = cx, fcy = cy, fcz = cz, fux = ux, fuy = uy, fuz = uz, fvx = vx, fvy = vy, fvz = vz;
		pts.sort((p, q) -> Double.compare(
			Math.atan2((p[0] - fcx) * fvx + (p[1] - fcy) * fvy + (p[2] - fcz) * fvz, (p[0] - fcx) * fux + (p[1] - fcy) * fuy + (p[2] - fcz) * fuz),
			Math.atan2((q[0] - fcx) * fvx + (q[1] - fcy) * fvy + (q[2] - fcz) * fvz, (q[0] - fcx) * fux + (q[1] - fcy) * fuy + (q[2] - fcz) * fuz)));
		// quitar puntos repetidos (el plano por una esquina da dos iguales)
		List<double[]> out = new ArrayList<>();
		for (double[] p : pts) {
			if (!out.isEmpty()) {
				double[] l = out.get(out.size() - 1);
				if (Math.abs(l[0] - p[0]) + Math.abs(l[1] - p[1]) + Math.abs(l[2] - p[2]) < 1e-4) continue;
			}
			out.add(p);
		}
		return out.size() < 3 ? List.of() : out;
	}

	/** Lo contrario de toWorld: un desplazamiento desde los pies (bloques) → píxeles del JSON. */
	public static Vec3d toModel(Vec3d off, float bodyYaw, double scale) {
		double a = Math.toRadians(180 - bodyYaw), c = Math.cos(a), s = Math.sin(a);
		double lx = off.x * c - off.z * s, lz = off.x * s + off.z * c;
		return new Vec3d(-lx * 16.0 / scale, off.y * 16.0 / scale, lz * 16.0 / scale);
	}

	/** La caja del mundo (sin girar: la que envuelve la caja girada) de unas coordenadas del JSON. */
	public static Box worldBox(double[] b, Vec3d feet, float bodyYaw, double scale) {
		double x0 = Double.MAX_VALUE, y0 = Double.MAX_VALUE, z0 = Double.MAX_VALUE, x1 = -Double.MAX_VALUE, y1 = -Double.MAX_VALUE, z1 = -Double.MAX_VALUE;
		for (int i = 0; i < 8; i++) {
			Vec3d p = toWorld((i & 1) == 0 ? b[0] : b[3], (i & 2) == 0 ? b[1] : b[4], (i & 4) == 0 ? b[2] : b[5], bodyYaw, scale);
			x0 = Math.min(x0, p.x); y0 = Math.min(y0, p.y); z0 = Math.min(z0, p.z);
			x1 = Math.max(x1, p.x); y1 = Math.max(y1, p.y); z1 = Math.max(z1, p.z);
		}
		return new Box(x0, y0, z0, x1, y1, z1).offset(feet);
	}

	// ------------------------------------------------------------------ JSON

	public static Anatomy parse(Identifier entity, JsonObject o) {
		String core = o.has("core") && !o.get("core").isJsonNull() ? o.get("core").getAsString() : "body";
		Map<String, Segment> segs = new LinkedHashMap<>();
		JsonObject so = o.getAsJsonObject("segments");
		if (so != null) {
			for (Map.Entry<String, JsonElement> e : so.entrySet()) {
				JsonObject s = e.getValue().getAsJsonObject();
				String parent = s.has("parent") && !s.get("parent").isJsonNull() ? s.get("parent").getAsString() : null;
				List<String> parts = new ArrayList<>();
				if (s.has("model_parts")) for (JsonElement p : s.getAsJsonArray("model_parts")) parts.add(p.getAsString());
				else parts.add(e.getKey());
				double[] box = nums(s.getAsJsonArray("box"), 6);
				if (box == null) box = new double[]{-2, 0, -2, 2, 4, 2};
				for (int i = 0; i < 3; i++) {
					if (box[i] > box[i + 3]) {
						double t = box[i];
						box[i] = box[i + 3];
						box[i + 3] = t;
					}
				}
				double[] joint = nums(s.getAsJsonArray("joint"), 3);
				double mass = s.has("mass") ? s.get("mass").getAsDouble() : 0.1;
				Set<String> fn = new LinkedHashSet<>();
				if (s.has("function")) {
					JsonElement f = s.get("function");
					if (f.isJsonArray()) for (JsonElement x : f.getAsJsonArray()) fn.add(x.getAsString());
					else fn.add(f.getAsString());
				}
				boolean vital = s.has("vital") && s.get("vital").getAsBoolean();
				segs.put(e.getKey(), new Segment(e.getKey(), parent, List.copyOf(parts), joint, box, mass, Collections.unmodifiableSet(fn), vital));
			}
		}
		Map<String, List<String>> groups = new LinkedHashMap<>();
		JsonObject go = o.getAsJsonObject("cut_groups");
		if (go != null) {
			for (Map.Entry<String, JsonElement> e : go.entrySet()) {
				List<String> l = new ArrayList<>();
				for (JsonElement x : e.getValue().getAsJsonArray()) if (segs.containsKey(x.getAsString())) l.add(x.getAsString());
				if (!l.isEmpty()) groups.put(e.getKey(), List.copyOf(l));
			}
		}
		int cap = 0xE8B4A0;
		if (o.has("cap_color")) {
			String c = o.get("cap_color").getAsString().replace("#", "");
			try {
				cap = Integer.parseInt(c, 16);
			} catch (NumberFormatException ignored) {
			}
		}
		boolean excluded = o.has("excluded") && o.get("excluded").getAsBoolean();
		return new Anatomy(entity, core, segs, groups, cap, excluded);
	}

	private static double[] nums(JsonArray a, int n) {
		if (a == null || a.size() < n) return null;
		double[] out = new double[n];
		for (int i = 0; i < n; i++) out[i] = a.get(i).getAsDouble();
		return out;
	}

	/** Problemas de forma (D1): padres que no existen, partes sin camino al núcleo. Vacío = bien. */
	public List<String> problems() {
		List<String> out = new ArrayList<>();
		if (!segments.containsKey(core)) out.add("el núcleo «" + core + "» no es una parte");
		for (Segment s : segments.values()) {
			if (s.id.equals(core)) continue;
			if (s.parent == null) out.add(s.id + " no tiene padre");
			else if (!segments.containsKey(s.parent)) out.add(s.id + ": padre «" + s.parent + "» no existe");
			else {
				Set<String> seen = new HashSet<>();
				String cur = s.id;
				while (cur != null && !cur.equals(core) && seen.add(cur)) {
					Segment x = segments.get(cur);
					cur = x == null ? null : x.parent;
				}
				if (!core.equals(cur)) out.add(s.id + " no llega al núcleo");
			}
		}
		return out;
	}
}
