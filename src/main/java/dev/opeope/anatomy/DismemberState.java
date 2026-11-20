package dev.opeope.anatomy;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.util.Uuids;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Lo que le falta a un ser vivo (Data Attachment persistente, se guarda con la entidad). Cada parte separada apunta a la
 * entidad BodyPartEntity que la lleva; las piezas de corte libre/volumétrico se describen con {@link CutVolume}.
 */
public final class DismemberState {
	public final Map<String, UUID> detached = new LinkedHashMap<>();
	public final Map<String, Long> lockedUntil = new HashMap<>();
	/** Partes de OTRO ser puestas en este (quimera): el sitio → la BodyPartEntity (de su dueño de verdad) que lo ocupa. */
	public final Map<String, UUID> foreign = new LinkedHashMap<>();
	/** Primer plano legado (para compatibilidad/tests); sincronizado automáticamente desde {@link #sliceVolumes}. */
	public float[] plane;
	public UUID slice;
	/** Piezas volumétricas o multi-plano activas: UUID de la BodyPartEntity → su volumen en píxeles de modelo. */
	public final Map<UUID, CutVolume> sliceVolumes = new LinkedHashMap<>();
	/** Partes cortadas por articulación que además se rebanaron recursivamente: UUID de sub-pieza → su {@link CutVolume}. */
	public final Map<UUID, CutVolume> splitParts = new LinkedHashMap<>();
	/** Partes atravesadas cuya función se pierde por volumen perdido. */
	public final java.util.Set<String> impaired = new java.util.LinkedHashSet<>();
	public UUID cutter;

	// solo en memoria
	public transient int missingTicks;

	public static final Codec<DismemberState> CODEC = RecordCodecBuilder.create(i -> i.group(
		Codec.unboundedMap(Codec.STRING, Uuids.CODEC).optionalFieldOf("detached", Map.of()).forGetter(s -> s.detached),
		Codec.unboundedMap(Codec.STRING, Codec.LONG).optionalFieldOf("locked", Map.of()).forGetter(s -> s.lockedUntil),
		Uuids.CODEC.optionalFieldOf("cutter").forGetter(s -> Optional.ofNullable(s.cutter)),
		Codec.unboundedMap(Codec.STRING, Uuids.CODEC).optionalFieldOf("foreign", Map.of()).forGetter(s -> s.foreign),
		Codec.FLOAT.listOf().optionalFieldOf("plane", List.of()).forGetter(s -> s.plane == null ? List.of()
			: List.of(s.plane[0], s.plane[1], s.plane[2], s.plane[3])),
		Uuids.CODEC.optionalFieldOf("slice").forGetter(s -> Optional.ofNullable(s.slice)),
		Codec.STRING.listOf().optionalFieldOf("impaired", List.of()).forGetter(s -> List.copyOf(s.impaired)),
		Codec.unboundedMap(Codec.STRING, CutVolume.CODEC).optionalFieldOf("slice_volumes", Map.of()).forGetter(s -> toStringMap(s.sliceVolumes)),
		Codec.unboundedMap(Codec.STRING, CutVolume.CODEC).optionalFieldOf("split_parts", Map.of()).forGetter(s -> toStringMap(s.splitParts))
	).apply(i, (d, l, c, f, pl, sl, imp, sv, sp) -> {
		DismemberState s = new DismemberState();
		if (pl.size() == 4) s.plane = new float[]{pl.get(0), pl.get(1), pl.get(2), pl.get(3)};
		s.slice = sl.orElse(null);
		s.impaired.addAll(imp);
		s.detached.putAll(d);
		s.lockedUntil.putAll(l);
		s.foreign.putAll(f);
		s.cutter = c.orElse(null);
		fromStringMap(sv, s.sliceVolumes);
		fromStringMap(sp, s.splitParts);
		// migración de guardados antiguos (solo plane + slice)
		if (s.sliceVolumes.isEmpty() && s.slice != null && s.plane != null) {
			s.sliceVolumes.put(s.slice, CutVolume.ofSinglePlane(new float[]{-s.plane[0], -s.plane[1], -s.plane[2], -s.plane[3]}));
		}
		return s;
	}));

	private static Map<String, CutVolume> toStringMap(Map<UUID, CutVolume> src) {
		if (src.isEmpty()) return Map.of();
		Map<String, CutVolume> out = new LinkedHashMap<>();
		for (Map.Entry<UUID, CutVolume> e : src.entrySet()) out.put(e.getKey().toString(), e.getValue());
		return out;
	}

	private static void fromStringMap(Map<String, CutVolume> src, Map<UUID, CutVolume> dst) {
		for (Map.Entry<String, CutVolume> e : src.entrySet()) {
			try {
				dst.put(UUID.fromString(e.getKey()), e.getValue());
			} catch (IllegalArgumentException ignored) {
			}
		}
	}

	/** Volumen restante conservado en la entidad principal (WHOLE \ piezas en sliceVolumes). */
	public CutVolume remainingVolume() {
		if (!sliceVolumes.isEmpty()) return CutVolume.complementOfAll(sliceVolumes.values());
		if (plane != null) return CutVolume.ofSinglePlane(plane);
		return CutVolume.WHOLE;
	}

	/** Mantiene actualizados los campos legados {@link #plane} y {@link #slice} a partir de {@link #sliceVolumes}. */
	public void refreshLegacyPlane() {
		if (sliceVolumes.isEmpty()) {
			plane = null;
			slice = null;
			return;
		}
		slice = sliceVolumes.keySet().iterator().next();
		float[] fp = remainingVolume().firstPlane();
		plane = fp != null ? fp : new float[]{0f, 1f, 0f, 0f};
	}

	public boolean isEmpty() {
		return detached.isEmpty() && foreign.isEmpty() && sliceVolumes.isEmpty() && splitParts.isEmpty() && plane == null;
	}
}
