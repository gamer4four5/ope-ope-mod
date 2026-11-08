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

	/**
	 * Servidor → cliente: el momento de un corte (zona y planos) para la hoja de luz y la costura. Ver CutFx en el cliente.
	 * data: c(3) r lc(3) lr eye(3) look(3) y por plano n(3) p(3) swipe(3). cutterId: el jugador que corta; material: el
	 * estado más repetido de lo cortado (Block.getRawIdFromState) o −1; pieces y living: lo que soltó el corte.
	 */
	public record CutFx(float[] data, int cutterId, int pieces, int living, int material, int flags) implements CustomPayload {
		public static final int F_BLOCKS = 1;      // cortó bloques: la costura se puede dibujar sobre el mundo
		public static final int F_TERRAIN = 2;     // alguna semilla es suelo (GroundRules.terrain)
		public static final int F_SUSPEND = 4;     // el corte deja suspendido lo que suelta
		public static final int F_REMEMBERED = 8;  // algún plano quedó recordado en GroundCuts
		private static final int CAP = 14 + 16 * 9;
		public static final Id<CutFx> ID = new Id<>(OpeOpeMod.id("cut_fx"));
		public static final PacketCodec<RegistryByteBuf, CutFx> CODEC = PacketCodec.of(CutFx::write, CutFx::read);

		static CutFx read(RegistryByteBuf buf) {
			int n = len(buf.readVarInt(), CAP);
			float[] p = new float[n];
			for (int i = 0; i < n; i++) p[i] = buf.readFloat();
			int cutter = buf.readVarInt(), pieces = buf.readVarInt(), living = buf.readVarInt(), material = buf.readVarInt(),
				flags = buf.readVarInt();
			return new CutFx(p, cutter, pieces, living, material, flags);
		}

		public void write(RegistryByteBuf buf) {
			int n = Math.min(data.length, CAP);
			buf.writeVarInt(n);
			for (int i = 0; i < n; i++) buf.writeFloat(data[i]);
			buf.writeVarInt(cutterId);
			buf.writeVarInt(pieces);
			buf.writeVarInt(living);
			buf.writeVarInt(material);
			buf.writeVarInt(flags);
		}

		@Override
		public Id<? extends CustomPayload> getId() {
			return ID;
		}
	}

	/** Servidor → cliente: el estado de los poderes del jugador, para el HUD y para saber qué hace cada clic. held: los ids de Takt, en orden. */
	public record State(int flags, int pattern, int taktCount, int taktMode, int weapon, int scanTicks, int[] cooldowns, int[] held) implements CustomPayload {
		public static final int HAS_FRUIT = 1, AWAKEN_UNLOCKED = 2, AWAKENED = 4, REACH_TARGET = 8, SUSPEND = 16,
			UNION = 32, BISTURI = 64, UNION_SELECTED = 128, CURTAIN = 256, SILENT = 512;
		private static final int HELD_CAP = 64;
		public static final Id<State> ID = new Id<>(OpeOpeMod.id("state"));
		public static final PacketCodec<RegistryByteBuf, State> CODEC = PacketCodec.of(State::write, State::read);

		static State read(RegistryByteBuf buf) {
			int flags = buf.readVarInt(), pattern = buf.readVarInt(), takt = buf.readVarInt(), mode = buf.readVarInt(),
				weapon = buf.readVarInt(), scan = buf.readVarInt();
			int n = len(buf.readVarInt(), 64);
			int[] cd = new int[n];
			for (int i = 0; i < n; i++) cd[i] = buf.readVarInt();
			int h = len(buf.readVarInt(), HELD_CAP);
			int[] held = new int[h];
			for (int i = 0; i < h; i++) held[i] = buf.readVarInt();
			return new State(flags, pattern, takt, mode, weapon, scan, cd, held);
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
			int h = Math.min(held.length, HELD_CAP);
			buf.writeVarInt(h);
			for (int i = 0; i < h; i++) buf.writeVarInt(held[i]);
		}

		@Override
		public Id<? extends CustomPayload> getId() {
			return ID;
		}
	}

	/**
	 * Cliente → servidor: un toque de Takt (R). size2: el ancho del trozo × 2 (3..18). aim: el bloque que mira el cliente
	 * (BlockPos.asLong), side: la cara tocada (Direction.ordinal) o NO_SIDE si no toca nada (entonces aim = 0 y h = 0);
	 * h: el punto exacto. window: cuántos ticks espera la grieta a un segundo toque (0 al soltar sobre la vista previa).
	 */
	public record TaktTap(int kind, int size2, long aim, int side, float hx, float hy, float hz, int window) implements CustomPayload {
		public static final int KIND_TAP = 0, KIND_DOUBLE = 1;
		public static final int NO_SIDE = -1;
		public static final Id<TaktTap> ID = new Id<>(OpeOpeMod.id("takt_tap"));
		public static final PacketCodec<RegistryByteBuf, TaktTap> CODEC = PacketCodec.of(TaktTap::write, TaktTap::read);

		static TaktTap read(RegistryByteBuf buf) {
			int kind = buf.readVarInt(), size2 = buf.readVarInt();
			long aim = buf.readLong();
			int side = buf.readVarInt();
			float hx = buf.readFloat(), hy = buf.readFloat(), hz = buf.readFloat();
			int window = buf.readVarInt();
			return new TaktTap(kind, size2, aim, side, hx, hy, hz, window);
		}

		public void write(RegistryByteBuf buf) {
			buf.writeVarInt(kind);
			buf.writeVarInt(size2);
			buf.writeLong(aim);
			buf.writeVarInt(side);
			buf.writeFloat(hx);
			buf.writeFloat(hy);
			buf.writeFloat(hz);
			buf.writeVarInt(window);
		}

		@Override
		public Id<? extends CustomPayload> getId() {
			return ID;
		}
	}

	/** Cliente → servidor: posar (R + V). Por cada pieza de Takt con fantasma, su id y la esquina mínima del fantasma (BlockPos.asLong). */
	public record TaktPlace(int[] ids, long[] bases) implements CustomPayload {
		private static final int CAP = 32;
		public static final Id<TaktPlace> ID = new Id<>(OpeOpeMod.id("takt_place"));
		public static final PacketCodec<RegistryByteBuf, TaktPlace> CODEC = PacketCodec.of(TaktPlace::write, TaktPlace::read);

		static TaktPlace read(RegistryByteBuf buf) {
			int n = len(buf.readVarInt(), CAP);
			int[] ids = new int[n];
			long[] bases = new long[n];
			for (int i = 0; i < n; i++) {
				ids[i] = buf.readVarInt();
				bases[i] = buf.readLong();
			}
			return new TaktPlace(ids, bases);
		}

		public void write(RegistryByteBuf buf) {
			int n = Math.min(CAP, Math.min(ids.length, bases.length));
			buf.writeVarInt(n);
			for (int i = 0; i < n; i++) {
				buf.writeVarInt(ids[i]);
				buf.writeLong(bases[i]);
			}
		}

		@Override
		public Id<? extends CustomPayload> getId() {
			return ID;
		}
	}

	/**
	 * Servidor → cliente: un corte que el suelo recuerda. ADD: todos los campos, data = GroundCut.toFloats() (26 floats).
	 * REMOVE: id, owner, dim y created de ese corte, data vacío. CLEAR: quita todos los cortes de owner (o de todos si
	 * owner es ALL); id 0, dim el Overworld (no cuenta), created 0, data vacío.
	 */
	public record GroundCutSync(int op, int id, UUID owner, Identifier dim, long created, float[] data) implements CustomPayload {
		public static final int ADD = 0, REMOVE = 1, CLEAR = 2;
		public static final UUID ALL = new UUID(0L, 0L);
		private static final int CAP = 32;
		public static final Id<GroundCutSync> ID = new Id<>(OpeOpeMod.id("ground_cut"));
		public static final PacketCodec<RegistryByteBuf, GroundCutSync> CODEC = PacketCodec.of(GroundCutSync::write, GroundCutSync::read);

		static GroundCutSync read(RegistryByteBuf buf) {
			int op = buf.readVarInt(), id = buf.readVarInt();
			UUID owner = buf.readUuid();
			Identifier dim = buf.readIdentifier();
			long created = buf.readLong();
			int n = len(buf.readVarInt(), CAP);
			float[] d = new float[n];
			for (int i = 0; i < n; i++) d[i] = buf.readFloat();
			return new GroundCutSync(op, id, owner, dim, created, d);
		}

		public void write(RegistryByteBuf buf) {
			buf.writeVarInt(op);
			buf.writeVarInt(id);
			buf.writeUuid(owner);
			buf.writeIdentifier(dim);
			buf.writeLong(created);
			int n = Math.min(data.length, CAP);
			buf.writeVarInt(n);
			for (int i = 0; i < n; i++) buf.writeFloat(data[i]);
		}

		@Override
		public Id<? extends CustomPayload> getId() {
			return ID;
		}
	}

	/**
	 * Servidor → cliente: un momento de Takt (grieta, se suelta, se deshace, coge, zona, lanza, posa, impacto...), para que
	 * el cliente lo haga notar con luz, partículas, sonido y cámara. owner: el jugador que lo causó. Qué lleva cada campo
	 * según kind está en la tabla 3.1 del diseño; lo que no se usa va a 0, entityId a −1 y data vacío. DENY solo le llega
	 * a quien lo pidió; el resto, a los jugadores a 160 bloques (Takt.sendFx).
	 */
	public record TaktFx(int kind, int id, int entityId, UUID owner, double x, double y, double z, float a, float b,
						 int ticks, int flags, int state, int[] data) implements CustomPayload {
		public static final int TEAR_START = 0, DETACH = 1, CANCEL = 2, GRAB = 3, ZONE = 4, SWEEP = 5, THROW = 6, LAND = 7,
			SETTLE = 8, IMPACT = 9, REWIND = 10, HOME = 11, DENY = 12, KINDS = 13;
		public static final String[] NAMES = {"TEAR_START", "DETACH", "CANCEL", "GRAB", "ZONE", "SWEEP", "THROW", "LAND",
			"SETTLE", "IMPACT", "REWIND", "HOME", "DENY"};
		public static final int F_CLOSED = 1, F_BIG = 2, F_AREA = 4, F_CHUNK = 8, F_ALL = 16, F_SEALED = 32, F_FACE = 64;
		public static final int NO_ROOM = 0, FULL = 1, NO_GROUND = 2, NOTHING_LOOSE = 3, NOTHING_CUT = 4, BUSY = 5;   // DENY: el motivo (state)
		public static final int DATA_CAP = 1024;
		public static final Id<TaktFx> ID = new Id<>(OpeOpeMod.id("takt_fx"));
		public static final PacketCodec<RegistryByteBuf, TaktFx> CODEC = PacketCodec.of(TaktFx::write, TaktFx::read);

		static TaktFx read(RegistryByteBuf buf) {
			int kind = buf.readVarInt(), id = buf.readVarInt(), entityId = buf.readVarInt();
			UUID owner = buf.readUuid();
			double x = buf.readDouble(), y = buf.readDouble(), z = buf.readDouble();
			float a = buf.readFloat(), b = buf.readFloat();
			int ticks = buf.readVarInt(), flags = buf.readVarInt(), state = buf.readVarInt();
			int n = len(buf.readVarInt(), DATA_CAP);
			int[] data = new int[n];
			for (int i = 0; i < n; i++) data[i] = buf.readInt();
			return new TaktFx(kind, id, entityId, owner, x, y, z, a, b, ticks, flags, state, data);
		}

		public void write(RegistryByteBuf buf) {
			buf.writeVarInt(kind);
			buf.writeVarInt(id);
			buf.writeVarInt(entityId);
			buf.writeUuid(owner != null ? owner : GroundCutSync.ALL);
			buf.writeDouble(x);
			buf.writeDouble(y);
			buf.writeDouble(z);
			buf.writeFloat(a);
			buf.writeFloat(b);
			buf.writeVarInt(ticks);
			buf.writeVarInt(flags);
			buf.writeVarInt(state);
			int n = data == null ? 0 : Math.min(data.length, DATA_CAP);
			buf.writeVarInt(n);
			for (int i = 0; i < n; i++) buf.writeInt(data[i]);
		}

		/** El punto del momento como vector. */
		public Vec3d pos() {
			return new Vec3d(x, y, z);
		}

		/** El nombre del tipo, para los registros ("?" si no es uno conocido). */
		public String name() {
			return kind >= 0 && kind < NAMES.length ? NAMES[kind] : "?";
		}

		/** Empaqueta el desplazamiento de una columna o celda: ((dx+512)&1023) | ((dz+512)&1023)<<10 | ((dy+512)&1023)<<20 (| 1<<30 = borde). */
		public static int pack(int dx, int dz, int dy) {
			return ((dx + 512) & 1023) | (((dz + 512) & 1023) << 10) | (((dy + 512) & 1023) << 20);
		}

		public static int dx(int p) {
			return (p & 1023) - 512;
		}

		public static int dz(int p) {
			return ((p >> 10) & 1023) - 512;
		}

		public static int dy(int p) {
			return ((p >> 20) & 1023) - 512;
		}

		@Override
		public Id<? extends CustomPayload> getId() {
			return ID;
		}
	}

	/** Cuántos elementos leer de un array: el largo que llega, entre 0 y su tope (como Cut.read, sin negativos). */
	private static int len(int n, int cap) {
		return Math.max(0, Math.min(n, cap));
	}
}
