package dev.opeope.anatomy;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.util.Uuids;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Lo que le falta a un ser vivo (Data Attachment persistente, se guarda con la entidad). Cada parte separada apunta a la
 * entidad BodyPartEntity que la lleva (varias partes pueden ir en la misma, como las dos piernas de la mitad de abajo).
 */
public final class DismemberState {
	public final Map<String, UUID> detached = new LinkedHashMap<>();
	public final Map<String, Long> lockedUntil = new HashMap<>();
	/** Partes de OTRO ser puestas en este (quimera): el sitio → la BodyPartEntity (de su dueño de verdad) que lo ocupa. */
	public final Map<String, UUID> foreign = new LinkedHashMap<>();
	/**
	 * Corte libre por plano (no por articulación): el plano en píxeles del JSON (nx, ny, nz, d). El ser se queda con el lado
	 * n·P &lt; d; la pieza «slice» (una BodyPartEntity) con el otro. null = sin corte libre.
	 */
	public float[] plane;
	public UUID slice;
	/** Partes atravesadas por el plano que se han quedado (casi) enteras al otro lado: su función se pierde. */
	public final java.util.Set<String> impaired = new java.util.LinkedHashSet<>();
	public UUID cutter;

	// solo en memoria
	public transient int missingTicks;

	public static final Codec<DismemberState> CODEC = RecordCodecBuilder.create(i -> i.group(
		Codec.unboundedMap(Codec.STRING, Uuids.CODEC).optionalFieldOf("detached", Map.of()).forGetter(s -> s.detached),
		Codec.unboundedMap(Codec.STRING, Codec.LONG).optionalFieldOf("locked", Map.of()).forGetter(s -> s.lockedUntil),
		Uuids.CODEC.optionalFieldOf("cutter").forGetter(s -> Optional.ofNullable(s.cutter)),
		Codec.unboundedMap(Codec.STRING, Uuids.CODEC).optionalFieldOf("foreign", Map.of()).forGetter(s -> s.foreign),
		Codec.FLOAT.listOf().optionalFieldOf("plane", java.util.List.of()).forGetter(s -> s.plane == null ? java.util.List.of()
			: java.util.List.of(s.plane[0], s.plane[1], s.plane[2], s.plane[3])),
		Uuids.CODEC.optionalFieldOf("slice").forGetter(s -> Optional.ofNullable(s.slice)),
		Codec.STRING.listOf().optionalFieldOf("impaired", java.util.List.of()).forGetter(s -> java.util.List.copyOf(s.impaired))
	).apply(i, (d, l, c, f, pl, sl, imp) -> {
		DismemberState s = new DismemberState();
		if (pl.size() == 4) s.plane = new float[]{pl.get(0), pl.get(1), pl.get(2), pl.get(3)};
		s.slice = sl.orElse(null);
		s.impaired.addAll(imp);
		s.detached.putAll(d);
		s.lockedUntil.putAll(l);
		s.foreign.putAll(f);
		s.cutter = c.orElse(null);
		return s;
	}));

	public boolean isEmpty() {
		return detached.isEmpty() && foreign.isEmpty() && plane == null;
	}
}
