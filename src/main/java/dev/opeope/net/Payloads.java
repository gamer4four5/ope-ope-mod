package dev.opeope.net;

import dev.opeope.OpeOpeMod;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.Vec3d;

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

	/**
	 * Servidor → cliente: un momento del ROOM (nace, se fija, cambia de tamaño, toca un límite, sale volando, aterriza,
	 * vuelve, llega, se cierra), para que el cliente lo haga notar con luz, sonido, cámara y HUD. Siempre llega ANTES del
	 * {@link Room} que lo acompaña. Qué lleva cada campo según {@code kind}:
	 * <ul>
	 * <li>CREATE: a = la mano, b = el centro, radius = el radio inicial; flags F_REBUILT si se rehízo manteniendo Q.</li>
	 * <li>FIX / ARRIVE / CLOSE: a = b = el centro, radius.</li>
	 * <li>NOTCH: a = b = el centro, radius = el nuevo, radius2 = el anterior; flags F_GROW o F_SHRINK.</li>
	 * <li>LIMIT: a = b = el centro, radius; flags = LIMIT_MAX, LIMIT_MIN, LIMIT_CENTRED o LIMIT_MAX_GROWING (un valor).</li>
	 * <li>CAST: a = la mano, b = dónde caerá, radius = con qué radio, radius2 = la altura del arco, ticks = el vuelo;
	 * flags F_FRESH si el ROOM se creó con esta misma pulsación.</li>
	 * <li>LAND: a = el centro nuevo, b = el viejo, radius, radius2 = el radio viejo (o 0); flags F_OLD si había uno viejo.</li>
	 * <li>BRING: a = el centro de partida, b = el jugador + (0, 1, 0), radius, ticks = el viaje.</li>
	 * </ul>
	 */
	public record RoomFx(UUID owner, int slot, int kind, double ax, double ay, double az, double bx, double by, double bz,
						 float radius, float radius2, int ticks, int flags) implements CustomPayload {
		public static final int CREATE = 0, FIX = 1, NOTCH = 2, LIMIT = 3, CAST = 4, LAND = 5, BRING = 6, ARRIVE = 7, CLOSE = 8, KINDS = 9;
		public static final String[] NAMES = {"CREATE", "FIX", "NOTCH", "LIMIT", "CAST", "LAND", "BRING", "ARRIVE", "CLOSE"};
		public static final int F_REBUILT = 1;                  // CREATE
		public static final int F_GROW = 1, F_SHRINK = 2;       // NOTCH
		public static final int F_FRESH = 2;                    // CAST
		public static final int F_OLD = 1;                      // LAND
		public static final int LIMIT_MAX = 0, LIMIT_MIN = 1, LIMIT_CENTRED = 2, LIMIT_MAX_GROWING = 3;   // LIMIT: el valor va en flags
		public static final Id<RoomFx> ID = new Id<>(OpeOpeMod.id("room_fx"));
		public static final PacketCodec<RegistryByteBuf, RoomFx> CODEC = PacketCodec.of(RoomFx::write, RoomFx::read);

		static RoomFx read(RegistryByteBuf buf) {
			return new RoomFx(buf.readUuid(), buf.readVarInt(), buf.readVarInt(),
				buf.readDouble(), buf.readDouble(), buf.readDouble(), buf.readDouble(), buf.readDouble(), buf.readDouble(),
				buf.readFloat(), buf.readFloat(), buf.readVarInt(), buf.readVarInt());
		}

		public void write(RegistryByteBuf buf) {
			buf.writeUuid(owner);
			buf.writeVarInt(slot);
			buf.writeVarInt(kind);
			buf.writeDouble(ax);
			buf.writeDouble(ay);
			buf.writeDouble(az);
			buf.writeDouble(bx);
			buf.writeDouble(by);
			buf.writeDouble(bz);
			buf.writeFloat(radius);
			buf.writeFloat(radius2);
			buf.writeVarInt(ticks);
			buf.writeVarInt(flags);
		}

		/** El punto a como vector. */
		public Vec3d a() {
			return new Vec3d(ax, ay, az);
		}

		/** El punto b como vector. */
		public Vec3d b() {
			return new Vec3d(bx, by, bz);
		}

		/** El nombre del tipo, para los registros ("?" si no es uno conocido). */
		public String name() {
			return kind >= 0 && kind < NAMES.length ? NAMES[kind] : "?";
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

	/** Servidor → cliente: el momento de un corte (zona y planos) para la hoja de luz y la costura. Ver CutFx en el cliente. */
	public record CutFx(float[] data) implements CustomPayload {
		public static final Id<CutFx> ID = new Id<>(OpeOpeMod.id("cut_fx"));
		public static final PacketCodec<RegistryByteBuf, CutFx> CODEC = PacketCodec.of(CutFx::write, CutFx::read);

		static CutFx read(RegistryByteBuf buf) {
			int n = Math.min(buf.readVarInt(), 14 + 16 * 9);
			float[] p = new float[n];
			for (int i = 0; i < n; i++) p[i] = buf.readFloat();
			return new CutFx(p);
		}

		public void write(RegistryByteBuf buf) {
			buf.writeVarInt(data.length);
			for (float f : data) buf.writeFloat(f);
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
