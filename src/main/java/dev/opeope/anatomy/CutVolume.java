package dev.opeope.anatomy;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;

/**
 * Volumen CSG exacto de una entidad o de una {@link dev.opeope.entity.BodyPartEntity} cortada, en píxeles de modelo:
 * región conservada = {@code (AND include) AND NOT (OR_{ex in exclude} (AND ex))}.
 * <p>
 * Si {@code exclude} está vacío y todos los elementos de {@code include} son {@link CutOp.HalfSpace}, es un poliedro
 * convexo puro (vía rápida de recorte exacto poligonal).
 */
public final class CutVolume {
	public static final CutVolume WHOLE = new CutVolume(List.of(), List.of());

	public static final Codec<CutVolume> CODEC = Codec.STRING.comapFlatMap(s -> {
		CutVolume v = decodeString(s);
		return v != null ? DataResult.success(v) : DataResult.error(() -> "Invalid CutVolume: " + s);
	}, CutVolume::encodeString);

	public final List<CutOp> include;
	public final List<List<CutOp>> exclude;
	private final int hash;

	public CutVolume(List<CutOp> include, List<List<CutOp>> exclude) {
		this.include = List.copyOf(include);
		List<List<CutOp>> ex = new ArrayList<>(exclude.size());
		for (List<CutOp> g : exclude) {
			if (!g.isEmpty()) ex.add(List.copyOf(g));
		}
		this.exclude = List.copyOf(ex);
		int h = 1;
		for (CutOp op : this.include) h = 31 * h + op.stableHash();
		h = 31 * h + 7;
		for (List<CutOp> g : this.exclude) {
			int gh = 1;
			for (CutOp op : g) gh = 31 * gh + op.stableHash();
			h = 31 * h + gh;
		}
		this.hash = h;
	}

	public boolean isWhole() {
		return include.isEmpty() && exclude.isEmpty();
	}

	public boolean isPureHalfSpaces() {
		if (!exclude.isEmpty()) return false;
		for (CutOp op : include) {
			if (!(op instanceof CutOp.HalfSpace)) return false;
		}
		return true;
	}

	public List<CutOp.HalfSpace> halfSpaces() {
		List<CutOp.HalfSpace> out = new ArrayList<>();
		for (CutOp op : include) {
			if (op instanceof CutOp.HalfSpace hs) out.add(hs);
		}
		return out;
	}

	/** Todos los semiespacios únicos (de {@code include} y {@code exclude}), deduplicados por normal/distancia. */
	public List<CutOp.HalfSpace> allPlanes() {
		List<CutOp.HalfSpace> out = new ArrayList<>();
		addUniquePlanes(include, out);
		for (List<CutOp> g : exclude) addUniquePlanes(g, out);
		return out;
	}

	private static void addUniquePlanes(List<CutOp> src, List<CutOp.HalfSpace> dst) {
		for (CutOp op : src) {
			if (!(op instanceof CutOp.HalfSpace hs)) continue;
			boolean dup = false;
			for (CutOp.HalfSpace e : dst) {
				float dot = hs.nx() * e.nx() + hs.ny() * e.ny() + hs.nz() * e.nz();
				if ((dot > 0.995f && Math.abs(hs.d() - e.d()) < 0.08f)
					|| (dot < -0.995f && Math.abs(hs.d() + e.d()) < 0.08f)) {
					dup = true;
					break;
				}
			}
			if (!dup) dst.add(hs);
		}
	}

	/** Primitivas cerradas (o huecos) que definen caras no planares / intersección para subdivisión y túneles. */
	public List<CutOp> nonPlanarOps() {
		List<CutOp> out = new ArrayList<>();
		for (CutOp op : include) {
			if (!op.isHalfSpace()) out.add(op);
		}
		for (List<CutOp> g : exclude) {
			for (CutOp op : g) {
				if (!op.isHalfSpace()) out.add(op);
			}
		}
		return out;
	}

	public int opCount() {
		int n = include.size();
		for (List<CutOp> g : exclude) n += g.size();
		return n;
	}

	/** Distancia con signo (<= 0 dentro del volumen conservado, > 0 fuera). */
	public float sdf(float x, float y, float z) {
		if (isWhole()) return -1e6f;
		float d = include.isEmpty() ? -1e6f : -1e6f;
		if (!include.isEmpty()) {
			d = include.get(0).sdf(x, y, z);
			for (int i = 1; i < include.size(); i++) {
				d = Math.max(d, include.get(i).sdf(x, y, z));
			}
		}
		for (List<CutOp> g : exclude) {
			float ed = g.get(0).sdf(x, y, z);
			for (int i = 1; i < g.size(); i++) {
				ed = Math.max(ed, g.get(i).sdf(x, y, z));
			}
			// fuera de g equivale a -ed <= 0
			d = Math.max(d, -ed);
		}
		return d;
	}

	public boolean contains(float x, float y, float z) {
		return sdf(x, y, z) <= 0f;
	}

	public CutVolume intersect(CutOp op) {
		if (op == null) return this;
		List<CutOp> inc = new ArrayList<>(include.size() + 1);
		inc.addAll(include);
		inc.add(op);
		return new CutVolume(inc, exclude);
	}

	public CutVolume subtractOp(CutOp op) {
		if (op == null) return this;
		if (op instanceof CutOp.HalfSpace hs) return intersect(hs.negated());
		List<List<CutOp>> ex = new ArrayList<>(exclude.size() + 1);
		ex.addAll(exclude);
		ex.add(List.of(op));
		return new CutVolume(include, ex);
	}

	/**
	 * Construye el volumen restante del ser como {@code WHOLE \ union(detachedPieces)}, manteniendo planos simples en
	 * {@code include} para conservar la vía rápida siempre que no haya formas cerradas ni sub-cortes.
	 */
	public static CutVolume complementOfAll(Collection<CutVolume> detachedPieces) {
		if (detachedPieces == null || detachedPieces.isEmpty()) return WHOLE;
		List<CutOp> inc = new ArrayList<>();
		List<List<CutOp>> ex = new ArrayList<>();
		for (CutVolume p : detachedPieces) {
			if (p == null || p.isWhole()) continue;
			if (p.exclude.isEmpty() && p.include.size() == 1 && p.include.get(0) instanceof CutOp.HalfSpace hs) {
				inc.add(hs.negated());
				continue;
			}
			List<CutOp> flattened = new ArrayList<>(p.include);
			for (List<CutOp> g : p.exclude) {
				if (g.size() == 1) flattened.add(g.get(0).negated());
			}
			if (!flattened.isEmpty()) ex.add(flattened);
		}
		return inc.isEmpty() && ex.isEmpty() ? WHOLE : new CutVolume(inc, ex);
	}

	/** Primer plano en formato clásico {nx, ny, nz, d} para compatibilidad de red/tests, o null si no hay ninguno. */
	public float[] firstPlane() {
		for (CutOp op : include) {
			if (op instanceof CutOp.HalfSpace hs) return hs.toFloatArray();
		}
		for (List<CutOp> g : exclude) {
			if (g.size() == 1 && g.get(0) instanceof CutOp.HalfSpace hs) return hs.negated().toFloatArray();
		}
		return null;
	}

	public static CutVolume ofSinglePlane(float[] pl) {
		if (pl == null || pl.length != 4) return WHOLE;
		return new CutVolume(List.of(new CutOp.HalfSpace(pl[0], pl[1], pl[2], pl[3])), List.of());
	}

	public int volumeHash() {
		return hash;
	}

	@Override
	public int hashCode() {
		return hash;
	}

	@Override
	public boolean equals(Object obj) {
		if (this == obj) return true;
		if (!(obj instanceof CutVolume o)) return false;
		return hash == o.hash && Objects.equals(include, o.include) && Objects.equals(exclude, o.exclude);
	}

	// ------------------------------------------------------------------ codificación compacta

	public String encodeString() {
		if (isWhole()) return "";
		StringBuilder sb = new StringBuilder();
		for (int i = 0; i < include.size(); i++) {
			if (i > 0) sb.append(';');
			sb.append(include.get(i).encodeString());
		}
		for (List<CutOp> g : exclude) {
			sb.append('~');
			for (int i = 0; i < g.size(); i++) {
				if (i > 0) sb.append(';');
				sb.append(g.get(i).encodeString());
			}
		}
		return sb.toString();
	}

	public static CutVolume decodeString(String s) {
		if (s == null || s.isEmpty()) return WHOLE;
		String[] parts = s.split("~", -1);
		List<CutOp> inc = new ArrayList<>();
		if (!parts[0].isEmpty()) {
			for (String tok : parts[0].split(";")) {
				if (tok.isEmpty()) continue;
				CutOp op = CutOp.decodeString(tok);
				if (op == null) return null;
				inc.add(op);
			}
		}
		List<List<CutOp>> ex = new ArrayList<>();
		for (int k = 1; k < parts.length; k++) {
			if (parts[k].isEmpty()) continue;
			List<CutOp> g = new ArrayList<>();
			for (String tok : parts[k].split(";")) {
				if (tok.isEmpty()) continue;
				CutOp op = CutOp.decodeString(tok);
				if (op == null) return null;
				g.add(op);
			}
			if (!g.isEmpty()) ex.add(g);
		}
		return inc.isEmpty() && ex.isEmpty() ? WHOLE : new CutVolume(inc, ex);
	}
}
