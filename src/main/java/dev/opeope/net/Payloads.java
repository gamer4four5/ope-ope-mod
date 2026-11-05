package dev.opeope.net;

import dev.opeope.OpeOpeMod;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

import java.util.UUID;

public final class Payloads {
	private Payloads() {}

	/** Cliente → servidor: una tecla de poder (acción + argumento). */
	public record Action(int action, int arg) implements CustomPayload {
		public static final Id<Action> ID = new Id<>(OpeOpeMod.id("action"));
		public static final PacketCodec<RegistryByteBuf, Action> CODEC = PacketCodec.of(Action::write, Action::new);

		public Action(RegistryByteBuf buf) {
			this(buf.readVarInt(), buf.readVarInt());
		}

		public void write(RegistryByteBuf buf) {
			buf.writeVarInt(action);
			buf.writeVarInt(arg);
		}

		@Override
		public Id<? extends CustomPayload> getId() {
			return ID;
		}
	}

	/** Cliente → servidor: planos de corte (Amputate, Kikoku y Bisturí). 6 floats por plano: normal y un punto. */
	public record Cut(int kind, float[] planes) implements CustomPayload {
		public static final Id<Cut> ID = new Id<>(OpeOpeMod.id("cut"));
		public static final PacketCodec<RegistryByteBuf, Cut> CODEC = PacketCodec.of(Cut::write, Cut::read);

		static Cut read(RegistryByteBuf buf) {
			int kind = buf.readVarInt();
			int n = Math.min(buf.readVarInt(), 16 * 6);
			float[] p = new float[n];
			for (int i = 0; i < n; i++) p[i] = buf.readFloat();
			return new Cut(kind, p);
		}

		public void write(RegistryByteBuf buf) {
			buf.writeVarInt(kind);
			buf.writeVarInt(planes.length);
			for (float f : planes) buf.writeFloat(f);
		}

		@Override
		public Id<? extends CustomPayload> getId() {
			return ID;
		}
	}

	/** Servidor → cliente: un ROOM (slot 0 el normal, 1 el R-ROOM). radius 0 = quitado. */
	public record Room(UUID owner, int slot, Identifier dim, double x, double y, double z, float radius, int state) implements CustomPayload {
		public static final Id<Room> ID = new Id<>(OpeOpeMod.id("room"));
		public static final PacketCodec<RegistryByteBuf, Room> CODEC = PacketCodec.of(Room::write, Room::read);

		static Room read(RegistryByteBuf buf) {
			return new Room(buf.readUuid(), buf.readVarInt(), buf.readIdentifier(), buf.readDouble(), buf.readDouble(), buf.readDouble(), buf.readFloat(), buf.readVarInt());
		}

		public void write(RegistryByteBuf buf) {
			buf.writeUuid(owner);
			buf.writeVarInt(slot);
			buf.writeIdentifier(dim);
			buf.writeDouble(x);
			buf.writeDouble(y);
			buf.writeDouble(z);
			buf.writeFloat(radius);
			buf.writeVarInt(state);
		}

		@Override
		public Id<? extends CustomPayload> getId() {
			return ID;
		}
	}

	/** Servidor → cliente: los planos con los que está cortado un ser vivo (5 floats por plano: normal local, d, separación). */
	public record CutSync(int entityId, float[] planes) implements CustomPayload {
		public static final Id<CutSync> ID = new Id<>(OpeOpeMod.id("cut_sync"));
		public static final PacketCodec<RegistryByteBuf, CutSync> CODEC = PacketCodec.of(CutSync::write, CutSync::read);

		static CutSync read(RegistryByteBuf buf) {
			int id = buf.readVarInt();
			int n = Math.min(buf.readVarInt(), 8 * 5);
			float[] p = new float[n];
			for (int i = 0; i < n; i++) p[i] = buf.readFloat();
			return new CutSync(id, p);
		}

		public void write(RegistryByteBuf buf) {
			buf.writeVarInt(entityId);
			buf.writeVarInt(planes.length);
			for (float f : planes) buf.writeFloat(f);
		}

		@Override
		public Id<? extends CustomPayload> getId() {
			return ID;
		}
	}

	/** Servidor → cliente: el estado de los poderes del jugador, para el HUD y para saber qué hace cada clic. */
	public record State(int flags, int pattern, int taktCount, int taktMode, int weapon, int scanTicks, int[] cooldowns) implements CustomPayload {
		public static final int HAS_FRUIT = 1, AWAKEN_UNLOCKED = 2, AWAKENED = 4, REACH_TARGET = 8, SUSPEND = 16,
			UNION = 32, BISTURI = 64, UNION_SELECTED = 128, CURTAIN = 256, SILENT = 512;
		public static final Id<State> ID = new Id<>(OpeOpeMod.id("state"));
		public static final PacketCodec<RegistryByteBuf, State> CODEC = PacketCodec.of(State::write, State::read);

		static State read(RegistryByteBuf buf) {
			int flags = buf.readVarInt(), pattern = buf.readVarInt(), takt = buf.readVarInt(), mode = buf.readVarInt(),
				weapon = buf.readVarInt(), scan = buf.readVarInt();
			int n = Math.min(buf.readVarInt(), 64);
			int[] cd = new int[n];
			for (int i = 0; i < n; i++) cd[i] = buf.readVarInt();
			return new State(flags, pattern, takt, mode, weapon, scan, cd);
		}

		public void write(RegistryByteBuf buf) {
			buf.writeVarInt(flags);
			buf.writeVarInt(pattern);
			buf.writeVarInt(taktCount);
			buf.writeVarInt(taktMode);
			buf.writeVarInt(weapon);
			buf.writeVarInt(scanTicks);
			buf.writeVarInt(cooldowns.length);
			for (int c : cooldowns) buf.writeVarInt(c);
		}

		@Override
		public Id<? extends CustomPayload> getId() {
			return ID;
		}
	}
}
