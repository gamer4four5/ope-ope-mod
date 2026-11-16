package dev.opeope.terrain;

/**
 * Rasgo elemental o físico dominante de un bloque o pieza.
 */
public enum MaterialTrait {
	NONE((byte) 0),
	CONDUCTIVE((byte) 1),
	IGNEOUS((byte) 2),
	CRYO((byte) 3),
	HEAVY_PIERCING((byte) 4);

	public final byte id;

	MaterialTrait(byte id) {
		this.id = id;
	}

	public static MaterialTrait byId(byte id) {
		return switch (id) {
			case 1 -> CONDUCTIVE;
			case 2 -> IGNEOUS;
			case 3 -> CRYO;
			case 4 -> HEAVY_PIERCING;
			default -> NONE;
		};
	}
}
