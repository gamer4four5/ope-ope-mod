package dev.opeope.net;

import dev.opeope.OpeOpeMod;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;

/**
 * Servidor → cliente: los momentos de la Mera Mera no Mi (Portgas D. Ace). El cliente dibuja el fuego con su propio
 * motor de llamas (MeraFxClient): el servidor solo cuenta qué pasa, dónde y durante cuánto.
 * <p>
 * Los disparos (Hiken, Higan, Jūjika, Shiranui, Entei) viajan en línea recta: a = salida, b = velocidad por tick,
 * ticks = vida máxima; el cliente los mueve igual que el servidor y los apaga cuando llega su IMPACT con el mismo id.
 * userId = quien usa la técnica (para el gesto y para pegar el fuego a su mano), −1 si nadie.
 */
public record MeraFx(int kind, int userId, int id,
					 double ax, double ay, double az,
					 double bx, double by, double bz,
					 float radius, float power, int ticks, int style) implements CustomPayload {

	public static final int HIKEN_CHARGE = 0, HIKEN_SHOT = 1, IMPACT = 2, HIGAN_BULLET = 3, JUJIKA_FLASH = 4,
		JUJIKA_SHOT = 5, HOTARUBI = 6, HIDARUMA = 7, KYOKAEN = 8, ENJOMO = 9, SHIRANUI = 10, ENKAI_GATHER = 11,
		HIBASHIRA = 12, KAGERO = 13, KAGERO_END = 14, ENTEI_CHARGE = 15, ENTEI_SHOT = 16, ENTEI_BLAST = 17, DASH = 18,
		SCATTER = 19, BODY = 20, HIKYAKU = 21, STEAM = 22, CHARGE_CANCEL = 23, EMBER_HIT = 24, PUNCH = 25,
		HAKI_HIT = 26, ZONE_END = 27, HIGAN_POSE = 28;

	/** IMPACT: style 0 = estallido redondo, 1 = cruz (Jūjika), 2 = lanza que revienta, 3 = se apaga en el aire, 4 = vapor. */
	public static final int STYLE_ROUND = 0, STYLE_CROSS = 1, STYLE_LANCE = 2, STYLE_FIZZLE = 3, STYLE_STEAM = 4;

	public static final Id<MeraFx> ID = new Id<>(OpeOpeMod.id("mera_fx"));
	public static final PacketCodec<RegistryByteBuf, MeraFx> CODEC = PacketCodec.of(MeraFx::write, MeraFx::read);

	static MeraFx read(RegistryByteBuf buf) {
		return new MeraFx(buf.readVarInt(), buf.readVarInt(), buf.readVarInt(),
			buf.readDouble(), buf.readDouble(), buf.readDouble(),
			buf.readDouble(), buf.readDouble(), buf.readDouble(),
			buf.readFloat(), buf.readFloat(), buf.readVarInt(), buf.readVarInt());
	}

	public void write(RegistryByteBuf buf) {
		buf.writeVarInt(kind);
		buf.writeVarInt(userId);
		buf.writeVarInt(id);
		buf.writeDouble(ax);
		buf.writeDouble(ay);
		buf.writeDouble(az);
		buf.writeDouble(bx);
		buf.writeDouble(by);
		buf.writeDouble(bz);
		buf.writeFloat(radius);
		buf.writeFloat(power);
		buf.writeVarInt(ticks);
		buf.writeVarInt(style);
	}

	@Override
	public Id<? extends CustomPayload> getId() {
		return ID;
	}
}
