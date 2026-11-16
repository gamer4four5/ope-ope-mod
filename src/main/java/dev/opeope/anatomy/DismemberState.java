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
	public UUID cutter;

	// solo en memoria
	public transient int missingTicks;

	public static final Codec<DismemberState> CODEC = RecordCodecBuilder.create(i -> i.group(
		Codec.unboundedMap(Codec.STRING, Uuids.CODEC).optionalFieldOf("detached", Map.of()).forGetter(s -> s.detached),
		Codec.unboundedMap(Codec.STRING, Codec.LONG).optionalFieldOf("locked", Map.of()).forGetter(s -> s.lockedUntil),
		Uuids.CODEC.optionalFieldOf("cutter").forGetter(s -> Optional.ofNullable(s.cutter))
	).apply(i, (d, l, c) -> {
		DismemberState s = new DismemberState();
		s.detached.putAll(d);
		s.lockedUntil.putAll(l);
		s.cutter = c.orElse(null);
		return s;
	}));

	public boolean isEmpty() {
		return detached.isEmpty();
	}
}
