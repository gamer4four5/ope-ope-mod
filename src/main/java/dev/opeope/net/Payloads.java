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
		public static final int CREATE = 0, FIX = 1, NOTCH = 2, LIMIT = 3, CAST = 4, LAND = 5, BRING = 6, ARRIVE = 7, CLOSE = 8, IMPACT = 9, KINDS = 10;
		public static final String[] NAMES = {"CREATE", "FIX", "NOTCH", "LIMIT", "CAST", "LAND", "BRING", "ARRIVE", "CLOSE", "IMPACT"};
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
			UNION = 32, BISTURI = 64, UNION_SELECTED = 128, CURTAIN = 256, SILENT = 512,
			STYLE_SWORD = 1024, STYLE_FIGHT = 2048, FREE_MODE = 4096,
			KROOM_BLADE = 8192, ETERNAL_YOUTH = 16384, ZERO_G = 32768,
			SHAMBLES_MODE_SWAP = 65536, SHAMBLES_MODE_ENTITIES = 131072, SHAMBLES_MARKED = 262144,
			GRAVITY_FRUIT = 524288, GRAVITY_VEC_BIT0 = 1048576, GRAVITY_VEC_BIT1 = 2097152,
			GRAVITY_RIDING = 4194304, GRAVITY_CHARGING = 8388608,
			GRAVITY_METEOR_CHARGING = 16777216, GRAVITY_METEOR_B0 = 33554432,
			GRAVITY_METEOR_B1 = 67108864, GRAVITY_METEOR_B2 = 134217728;
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
	public record TaktTap(int kind, int size2, int depth, int riderMode, long aim, int side, float hx, float hy, float hz, int window) implements CustomPayload {
		public static final int KIND_TAP = 0, KIND_DOUBLE = 1;
		public static final int NO_SIDE = -1;
		public static final Id<TaktTap> ID = new Id<>(OpeOpeMod.id("takt_tap"));
		public static final PacketCodec<RegistryByteBuf, TaktTap> CODEC = PacketCodec.of(TaktTap::write, TaktTap::read);

		static TaktTap read(RegistryByteBuf buf) {
			int kind = buf.readVarInt(), size2 = buf.readVarInt(), depth = buf.readVarInt(), riderMode = buf.readVarInt();
			long aim = buf.readLong();
			int side = buf.readVarInt();
			float hx = buf.readFloat(), hy = buf.readFloat(), hz = buf.readFloat();
			int window = buf.readVarInt();
			return new TaktTap(kind, size2, depth, riderMode, aim, side, hx, hy, hz, window);
		}

		public void write(RegistryByteBuf buf) {
			buf.writeVarInt(kind);
			buf.writeVarInt(size2);
			buf.writeVarInt(depth);
			buf.writeVarInt(riderMode);
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
		public static final int NO_ROOM = 0, FULL = 1, NO_GROUND = 2, NOTHING_LOOSE = 3, NOTHING_CUT = 4, BUSY = 5, NO_SPACE = 6;   // DENY: el motivo (state)
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

		/**
		 * Los datos de IMPACT: el radio del trozo y los metros arados (por 100), hacia dónde iba (x, z por 1000), la medida
		 * del golpe k (por 1000), el bloque del suelo (su id crudo) y los bloques que cavó el cráter. Se leen con los I_*.
		 */
		public static int[] impactData(double radius, Vec3d dir, double k, int groundId, double plow, int dug) {
			return new int[]{(int) Math.round(radius * 100), (int) Math.round(dir.x * 1000), (int) Math.round(dir.z * 1000),
				(int) Math.round(k * 1000), groundId, (int) Math.round(plow * 100), dug};
		}

		public static final int I_RADIUS = 0, I_DIRX = 1, I_DIRZ = 2, I_K = 3, I_GROUND = 4, I_PLOW = 5, I_DUG = 6;

		/** El dato i de IMPACT, o def si no viene (un servidor más viejo). */
		public int datum(int i, int def) {
			return data != null && i >= 0 && i < data.length ? data[i] : def;
		}

		@Override
		public Id<? extends CustomPayload> getId() {
			return ID;
		}
	}

	/** Servidor → cliente: ventana aérea de remate tras Shambles táctico y lista de piezas tácticas propias en vuelo. */
	public record AerialWindow(int ticks, int maxTicks, float gravMult, boolean perfect, int finisherTargetId, int[] tacticalIds) implements CustomPayload {
		private static final int CAP = 16;
		public static final Id<AerialWindow> ID = new Id<>(OpeOpeMod.id("aerial_window"));
		public static final PacketCodec<RegistryByteBuf, AerialWindow> CODEC = PacketCodec.of(AerialWindow::write, AerialWindow::read);

		static AerialWindow read(RegistryByteBuf buf) {
			int ticks = buf.readVarInt(), max = buf.readVarInt();
			float grav = buf.readFloat();
			boolean perf = buf.readBoolean();
			int fin = buf.readVarInt();
			int n = len(buf.readVarInt(), CAP);
			int[] ids = new int[n];
			for (int i = 0; i < n; i++) ids[i] = buf.readVarInt();
			return new AerialWindow(ticks, max, grav, perf, fin, ids);
		}

		public void write(RegistryByteBuf buf) {
			buf.writeVarInt(ticks);
			buf.writeVarInt(maxTicks);
			buf.writeFloat(gravMult);
			buf.writeBoolean(perfect);
			buf.writeVarInt(finisherTargetId);
			int n = tacticalIds == null ? 0 : Math.min(tacticalIds.length, CAP);
			buf.writeVarInt(n);
			for (int i = 0; i < n; i++) buf.writeVarInt(tacticalIds[i]);
		}

		@Override
		public Id<? extends CustomPayload> getId() {
			return ID;
		}
	}

	/** Cliente → servidor: cortar una parte (Amputate dirigido). segment = id de la parte o de un grupo (lower_half…). */
	public record CutPart(int entityId, String segment) implements CustomPayload {
		public static final Id<CutPart> ID = new Id<>(OpeOpeMod.id("cut_part"));
		public static final PacketCodec<RegistryByteBuf, CutPart> CODEC = PacketCodec.of(CutPart::write, CutPart::read);

		static CutPart read(RegistryByteBuf buf) {
			return new CutPart(buf.readVarInt(), buf.readString(64));
		}

		public void write(RegistryByteBuf buf) {
			buf.writeVarInt(entityId);
			buf.writeString(segment, 64);
		}

		@Override
		public Id<? extends CustomPayload> getId() {
			return ID;
		}
	}

	/**
	 * Cliente → servidor: corte libre de forma o lazo a mano alzada sobre entidades / BodyPartEntity en Bisturí.
	 * mode: 0=PLANE, 1=WEDGE, 2=BOX, 3=CYLINDER, 4=SPHERE, 5=SLICES_3, 6=CROSS_4, 7=FREEHAND.
	 */
	public record CutShape(int targetId, int mode, boolean delayed, boolean thru, float[] params) implements CustomPayload {
		public static final int MODE_PLANE = 0, MODE_WEDGE = 1, MODE_BOX = 2, MODE_CYLINDER = 3,
			MODE_SPHERE = 4, MODE_SLICES_3 = 5, MODE_CROSS_4 = 6, MODE_FREEHAND = 7;
		private static final int PARAM_CAP = 96;
		public static final Id<CutShape> ID = new Id<>(OpeOpeMod.id("cut_shape"));
		public static final PacketCodec<RegistryByteBuf, CutShape> CODEC = PacketCodec.of(CutShape::write, CutShape::read);

		static CutShape read(RegistryByteBuf buf) {
			int target = buf.readVarInt(), mode = buf.readVarInt();
			boolean delayed = buf.readBoolean(), thru = buf.readBoolean();
			int n = len(buf.readVarInt(), PARAM_CAP);
			float[] p = new float[n];
			for (int i = 0; i < n; i++) p[i] = buf.readFloat();
			return new CutShape(target, mode, delayed, thru, p);
		}

		public void write(RegistryByteBuf buf) {
			buf.writeVarInt(targetId);
			buf.writeVarInt(mode);
			buf.writeBoolean(delayed);
			buf.writeBoolean(thru);
			int n = params == null ? 0 : Math.min(params.length, PARAM_CAP);
			buf.writeVarInt(n);
			for (int i = 0; i < n; i++) buf.writeFloat(params[i]);
		}

		@Override
		public Id<? extends CustomPayload> getId() {
			return ID;
		}
	}

	/**
	 * Servidor → cliente: lo que le falta a un ser vivo (vacío = entero). locked: partes que no se pueden reunir aún.
	 * drop: cuánto baja el cuerpo al dibujarlo (bloques; sin piernas); heightScale: la altura de su caja (1 = normal);
	 * volume: codificación CSG completa {@link dev.opeope.anatomy.CutVolume}.
	 */
	public record DismemberSync(int entityId, java.util.List<String> detached, java.util.List<String> locked, float drop,
								float heightScale, java.util.List<String> foreign, float[] plane, String volume) implements CustomPayload {
		public DismemberSync(int entityId, java.util.List<String> detached, java.util.List<String> locked, float drop,
							 float heightScale, java.util.List<String> foreign, float[] plane) {
			this(entityId, detached, locked, drop, heightScale, foreign, plane, "");
		}

		public static final Id<DismemberSync> ID = new Id<>(OpeOpeMod.id("dismember_sync"));
		public static final PacketCodec<RegistryByteBuf, DismemberSync> CODEC = PacketCodec.of(DismemberSync::write, DismemberSync::read);
		private static final int CAP = 64;

		static DismemberSync read(RegistryByteBuf buf) {
			int id = buf.readVarInt();
			java.util.List<String> d = strings(buf);
			java.util.List<String> l = strings(buf);
			float drop = buf.readFloat(), hs = buf.readFloat();
			java.util.List<String> f = strings(buf);
			int np = Math.min(4, buf.readVarInt());
			float[] pl = new float[np];
			for (int i = 0; i < np; i++) pl[i] = buf.readFloat();
			String vol = buf.readString(8192);
			return new DismemberSync(id, d, l, drop, hs, f, pl, vol);
		}

		private static java.util.List<String> strings(RegistryByteBuf buf) {
			int n = len(buf.readVarInt(), CAP);
			java.util.List<String> out = new java.util.ArrayList<>(n);
			for (int i = 0; i < n; i++) out.add(buf.readString(64));
			return out;
		}

		private static void strings(RegistryByteBuf buf, java.util.List<String> list) {
			int n = Math.min(list.size(), CAP);
			buf.writeVarInt(n);
			for (int i = 0; i < n; i++) buf.writeString(list.get(i), 64);
		}

		public void write(RegistryByteBuf buf) {
			buf.writeVarInt(entityId);
			strings(buf, detached);
			strings(buf, locked);
			buf.writeFloat(drop);
			buf.writeFloat(heightScale);
			strings(buf, foreign);
			int np = plane == null ? 0 : Math.min(4, plane.length);
			buf.writeVarInt(np);
			for (int i = 0; i < np; i++) buf.writeFloat(plane[i]);
			buf.writeString(volume == null ? "" : volume, 8192);
		}

		@Override
		public Id<? extends CustomPayload> getId() {
			return ID;
		}
	}

	/** Servidor → cliente: teletransporte o trueque espacial de Shambles (orbe/silueta/relámpagos entre origen y destino). */
	public record ShamblesFx(
		int kind, int userId, int entityA, int entityB,
		double ax, double ay, double az, float aw, float ah, float ayaw,
		double bx, double by, double bz, float bw, float bh, float byaw
	) implements CustomPayload {
		public static final int KIND_SELF_TP = 0, KIND_SELF_SWAP = 1, KIND_ENTITY_SWAP = 2,
			KIND_TACTICAL = 3, KIND_MARK = 4, KIND_CLEAR_MARK = 5;
		public static final Id<ShamblesFx> ID = new Id<>(OpeOpeMod.id("shambles_fx"));
		public static final PacketCodec<RegistryByteBuf, ShamblesFx> CODEC = PacketCodec.of(ShamblesFx::write, ShamblesFx::read);

		static ShamblesFx read(RegistryByteBuf buf) {
			return new ShamblesFx(
				buf.readVarInt(), buf.readVarInt(), buf.readVarInt(), buf.readVarInt(),
				buf.readDouble(), buf.readDouble(), buf.readDouble(), buf.readFloat(), buf.readFloat(), buf.readFloat(),
				buf.readDouble(), buf.readDouble(), buf.readDouble(), buf.readFloat(), buf.readFloat(), buf.readFloat()
			);
		}

		public void write(RegistryByteBuf buf) {
			buf.writeVarInt(kind);
			buf.writeVarInt(userId);
			buf.writeVarInt(entityA);
			buf.writeVarInt(entityB);
			buf.writeDouble(ax);
			buf.writeDouble(ay);
			buf.writeDouble(az);
			buf.writeFloat(aw);
			buf.writeFloat(ah);
			buf.writeFloat(ayaw);
			buf.writeDouble(bx);
			buf.writeDouble(by);
			buf.writeDouble(bz);
			buf.writeFloat(bw);
			buf.writeFloat(bh);
			buf.writeFloat(byaw);
		}

		@Override
		public Id<? extends CustomPayload> getId() {
			return ID;
		}
	}

	/** Servidor → cliente: efectos 3D exclusivos de la fruta Gravity (Zushi Zushi no Mi) — sin relación con ROOM. */
	public record GravityFx(
		int kind, int userId,
		double ax, double ay, double az,
		double bx, double by, double bz,
		float radius, float power, int ticks
	) implements CustomPayload {
		public static final int KIND_PRESS_COLUMN = 0, KIND_MOKO = 1, KIND_METEOR_RIFT = 2,
			KIND_LIFT_PULSE = 3, KIND_SHIELD_BURST = 4, KIND_CHARGE_RING = 5, KIND_HYPER_PRESS = 6,
			KIND_METEOR_IMPACT = 7, KIND_PULL_VORTEX = 8;
		public static final Id<GravityFx> ID = new Id<>(OpeOpeMod.id("gravity_fx"));
		public static final PacketCodec<RegistryByteBuf, GravityFx> CODEC = PacketCodec.of(GravityFx::write, GravityFx::read);

		static GravityFx read(RegistryByteBuf buf) {
			return new GravityFx(
				buf.readVarInt(), buf.readVarInt(),
				buf.readDouble(), buf.readDouble(), buf.readDouble(),
				buf.readDouble(), buf.readDouble(), buf.readDouble(),
				buf.readFloat(), buf.readFloat(), buf.readVarInt()
			);
		}

		public void write(RegistryByteBuf buf) {
			buf.writeVarInt(kind);
			buf.writeVarInt(userId);
			buf.writeDouble(ax);
			buf.writeDouble(ay);
			buf.writeDouble(az);
			buf.writeDouble(bx);
			buf.writeDouble(by);
			buf.writeDouble(bz);
			buf.writeFloat(radius);
			buf.writeFloat(power);
			buf.writeVarInt(ticks);
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
