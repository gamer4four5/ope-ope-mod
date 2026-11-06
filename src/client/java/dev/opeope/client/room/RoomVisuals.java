package dev.opeope.client.room;

import dev.opeope.net.Payloads;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.util.Arm;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.Heightmap;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Lo que se dibuja de cada ROOM (spec §5.3): una {@link Vis} por dueño y slot, suavizada por ticks (el centro y el radio
 * se interpolan entre el tick anterior y el actual, sin dientes de sierra), y una lista de efectos cortos (ecos, ondas,
 * pliegue, cierre, anillos en el suelo, cometa, cuerda, destello). Lo alimentan los paquetes Room (dónde está y cuánto
 * mide) y los RoomFx (qué acaba de pasar). Todo vive en el hilo del cliente.
 */
public final class RoomVisuals {
	private RoomVisuals() {}

	/** Estados del servidor (Room.State.ordinal()). */
	public static final int GROWING = 0, FIXED = 1, FLYING = 2;
	/** Colores: el ROOM y el R-ROOM. */
	public static final int RGB_ROOM = 0x8FD8FF, RGB_RROOM = 0xB8F0FF;

	/** Un ROOM a la vista. Lo lee RoomRenderer; solo lo cambia esta clase. */
	public static final class Vis {
		public final String key;
		public final UUID owner;
		public final int slot;
		public final boolean mine;
		/** 1 el propio, 0,8 el de otros. */
		public final float strength;
		public Identifier dim;
		public double cx, cy, cz, pcx, pcy, pcz, tx, ty, tz;
		public float r, prevR, rTarget;
		public int state = FIXED;
		public float pulse, prevPulse;
		/** 1 normal; 0,55 el viejo mientras vuela el lanzado. */
		public float fade = 1f, prevFade = 1f;
		float fadeTarget = 1f;
		/** Lanzado nada más crearlo: no se ve hasta que aterriza. */
		public boolean hidden;
		int hideTicks;
		// florecer: de bloomFrom a rTarget en bloomLen ticks (y el centro desde from si bloomMove)
		int bloomTick = -1, bloomLen = 6;
		float bloomFrom;
		boolean bloomMove;
		double fromX, fromY, fromZ;
		// volviendo a ti
		boolean bringing;
		int bringStart, bringEnd;
		float lastTarget, growDelta;
		public final GroundLine ground = new GroundLine();

		Vis(String key, UUID owner, int slot, boolean mine) {
			this.key = key;
			this.owner = owner;
			this.slot = slot;
			this.mine = mine;
			this.strength = mine ? 1f : 0.8f;
		}

		void snap(double x, double y, double z, float radius) {
			cx = pcx = tx = x;
			cy = pcy = ty = y;
			cz = pcz = tz = z;
			r = prevR = rTarget = lastTarget = radius;
			bloomTick = -1;
			hideTicks = 0;
		}

		public double x(float td) {
			return pcx + (cx - pcx) * td;
		}

		public double y(float td) {
			return pcy + (cy - pcy) * td;
		}

		public double z(float td) {
			return pcz + (cz - pcz) * td;
		}

		public float radius(float td) {
			return prevR + (r - prevR) * td;
		}

		public float pulse(float td) {
			return prevPulse + (pulse - prevPulse) * td;
		}

		public float fade(float td) {
			return prevFade + (fade - prevFade) * td;
		}

		/** Se dibuja ahora (no escondido ni esperando a florecer). */
		public boolean shown() {
			return !hidden && hideTicks <= 0 && r > 0.01f;
		}

		public int rgb() {
			return slot == 1 ? RGB_RROOM : RGB_ROOM;
		}

		void tick(int clientTick) {
			pcx = cx;
			pcy = cy;
			pcz = cz;
			prevR = r;
			prevPulse = pulse;
			prevFade = fade;
			growDelta = rTarget - lastTarget;
			lastTarget = rTarget;
			if (hideTicks > 0) {
				// aterrizó: un tick sin nada (se ve el pliegue y el destello) y luego florece desde 0,2 R
				if (--hideTicks == 0) {
					r = prevR = bloomFrom;
					cx = pcx = tx;
					cy = pcy = ty;
					cz = pcz = tz;
				}
			} else if (bloomTick >= 0) {
				bloomTick++;
				float k = Math.min(1f, bloomTick / (float) bloomLen);
				float e = 1f - (1f - k) * (1f - k) * (1f - k);
				r = bloomFrom + (rTarget - bloomFrom) * e;
				if (bloomMove) {
					cx = fromX + (tx - fromX) * e;
					cy = fromY + (ty - fromY) * e;
					cz = fromZ + (tz - fromZ) * e;
				} else {
					cx = tx;
					cy = ty;
					cz = tz;
				}
				if (k >= 1f) bloomTick = -1;
			} else {
				if (state == GROWING) {
					r = rTarget;
				} else {
					r += (rTarget - r) * 0.5f;
					if (Math.abs(rTarget - r) < 0.01f) r = rTarget;
				}
				cx = tx;
				cy = ty;
				cz = tz;
			}
			pulse = Math.max(0f, pulse - 1.4f * 0.05f);
			if (mine && slot == 0 && state == GROWING) pulse = Math.max(pulse, 0.6f);
			fade += (fadeTarget - fade) * 0.5f;
			if (Math.abs(fadeTarget - fade) < 0.01f) fade = fadeTarget;
			if (bringing && clientTick > bringEnd) bringing = false;
		}
	}

	/** Un efecto corto. Lo lee RoomRenderer. */
	public static final class Fx {
		public static final int ECHO = 0, SHOCK = 1, FOLD = 2, CLOSE = 3, RING = 4, COMET = 5, TETHER = 6, FLASH = 7;
		public static final int RING_ANGLES = 64, RING_STEPS = 4;

		public final int type;
		public final String key;
		public final boolean own;
		public final int rgb;
		public final Identifier dim;
		public final double start;
		public double dur;
		public double ax, ay, az, bx, by, bz;
		/** Radio de partida (cáscaras), radio final (anillos) o radio del ROOM (cometa, destello). */
		public float r0;
		/** La altura del arco del cometa. */
		public float arc;
		public float alpha;
		/** Terminado antes de tiempo (el cometa al aterrizar, la cuerda al llegar). */
		public boolean ended;
		/** Anillos: la altura del suelo por ángulo y por cuarto de radio (NaN = no se dibuja ahí). */
		@Nullable public float[] heights;
		/** Cuerda: de quién tira. */
		@Nullable public UUID owner;

		Fx(int type, String key, boolean own, int rgb, Identifier dim, double start, double dur) {
			this.type = type;
			this.key = key;
			this.own = own;
			this.rgb = rgb;
			this.dim = dim;
			this.start = start;
			this.dur = dur;
		}

		/** De 0 a 1 a lo largo de su vida. */
		public double progress(double now) {
			return dur <= 0 ? 1 : Math.max(0, Math.min(1, (now - start) / dur));
		}

		/** Dónde va el cometa ahora: lerp(a, b, u) + arriba·arco·4u(1 − u). */
		public void cometPos(double now, double[] out) {
			double u = progress(now);
			double lift = arc * 4 * u * (1 - u);
			out[0] = ax + (bx - ax) * u;
			out[1] = ay + (by - ay) * u + lift;
			out[2] = az + (bz - az) * u;
		}

		/** El radio de una cáscara ahora y su brillo (0..1). */
		public float shellRadius(double now) {
			double k = progress(now);
			return switch (type) {
				case SHOCK -> (float) (r0 * (1 + 0.25 * k));
				case FOLD -> (float) (r0 * (1 - 0.98 * k * k));
				case CLOSE -> (float) (r0 * (1 - k * k));
				default -> r0;
			};
		}

		public float shellAlpha(double now) {
			double k = progress(now);
			return switch (type) {
				case SHOCK -> (float) (alpha * (1 - k));
				case FOLD, CLOSE -> (float) (alpha * (0.75 + 0.25 * k) * (1 - Math.pow(k, 6)));
				default -> (float) (alpha * (1 - k));
			};
		}
	}

	private static final List<Vis> VIS = new ArrayList<>();
	private static final Map<String, Vis> BY_KEY = new HashMap<>();
	private static final List<Fx> EFFECTS = new ArrayList<>();
	private static final int MAX_EFFECTS = 96;

	// la semilla de la mano (solo la propia)
	private static double seedStart = -1, seedFadeAt = -1, seedFadeDur = 0.06;
	private static int seedTicks;

	private static final double[] TMP = new double[3];

	// ------------------------------------------------------------------ lectura (renderer y HUD)

	/** Todos los ROOMs a la vista (no modificar). */
	public static List<Vis> all() {
		return VIS;
	}

	/** Los efectos vivos (no modificar). */
	public static List<Fx> effects() {
		return EFFECTS;
	}

	/** El mundo en el que estás ahora. */
	@Nullable
	public static Identifier currentDim() {
		MinecraftClient mc = MinecraftClient.getInstance();
		return mc.world == null ? null : mc.world.getRegistryKey().getValue();
	}

	/** Tu ROOM (slot 0) en este mundo, o null. */
	@Nullable
	public static Vis own() {
		Identifier dim = currentDim();
		if (dim == null) return null;
		for (int i = 0; i < VIS.size(); i++) {
			Vis v = VIS.get(i);
			if (v.mine && v.slot == 0 && dim.equals(v.dim)) return v;
		}
		return null;
	}

	/** Hay algo del ROOM que dibujar en este mundo. */
	public static boolean anything(Identifier dim) {
		if (seedStart >= 0) return true;
		for (int i = 0; i < VIS.size(); i++) if (dim.equals(VIS.get(i).dim)) return true;
		for (int i = 0; i < EFFECTS.size(); i++) if (dim.equals(EFFECTS.get(i).dim)) return true;
		return false;
	}

	// ------------------------------------------------------------------ la semilla

	/** Empieza la semilla en la mano (al pulsar el ROOM sin tener uno). */
	static void startSeed() {
		seedStart = RoomFxClient.time();
		seedFadeAt = -1;
		seedTicks = 0;
	}

	/** La semilla se apaga: rápido si llegó el ROOM (60 ms), algo más despacio si caducó. */
	static void endSeed(boolean created) {
		if (seedStart < 0 || seedFadeAt >= 0) return;
		seedFadeAt = RoomFxClient.time();
		seedFadeDur = created ? 0.06 : 0.15;
	}

	/** Brillo de la semilla ahora (0 = no hay). */
	public static float seedAlpha(double now) {
		if (seedStart < 0) return 0f;
		float a = (float) Math.min(1.0, (now - seedStart) / 0.05);
		if (seedFadeAt >= 0) a *= (float) Math.max(0.0, 1.0 - (now - seedFadeAt) / seedFadeDur);
		return Math.max(0f, a);
	}

	/** Cuánto lleva la semilla encendida (s). */
	public static double seedAge(double now) {
		return seedStart < 0 ? 0 : now - seedStart;
	}

	/**
	 * Dónde está la mano: ojos + mirada·0,6 − 0,35 arriba + derecha·0,3 (a la izquierda si la mano buena es la
	 * izquierda). En out (x, y, z).
	 */
	public static void handPoint(PlayerEntity p, float td, double[] out) {
		Vec3d eye = p.getCameraPosVec(td);
		Vec3d look = p.getRotationVec(td);
		// derecha = mirada × arriba
		double rx = -look.z, rz = look.x;
		double rl = Math.sqrt(rx * rx + rz * rz);
		if (rl < 1e-4) {
			rx = 1;
			rz = 0;
		} else {
			rx /= rl;
			rz /= rl;
		}
		double side = 0.3;
		MinecraftClient mc = MinecraftClient.getInstance();
		try {
			if (mc.options.getMainArm().getValue() == Arm.LEFT) side = -0.3;
		} catch (RuntimeException ignored) {
			// sin opciones: mano derecha
		}
		out[0] = eye.x + look.x * 0.6 + rx * side;
		out[1] = eye.y + look.y * 0.6 - 0.35;
		out[2] = eye.z + look.z * 0.6 + rz * side;
	}

	// ------------------------------------------------------------------ paquetes Room

	/** Dónde está y cuánto mide (llega cada tick mientras crece o vuelve; radio 0 = quitado). */
	public static void onRoomPacket(Payloads.Room p) {
		MinecraftClient mc = MinecraftClient.getInstance();
		String key = p.owner() + ":" + p.slot();
		Vis v = BY_KEY.get(key);
		if (p.radius() <= 0) {
			if (v != null) {
				removeVis(v);
				// sin aviso de cierre (servidor viejo o R-ROOM): se cierra igual, con su cáscara
				if (v.shown() && v.dim.equals(currentDim())) {
					Fx f = shell(Fx.CLOSE, v, v.cx, v.cy, v.cz, v.r, 0.26, 0.8f);
					f.alpha *= v.strength;
				}
			}
			return;
		}
		if (v == null) {
			// sin RoomFx delante: aparece entero, sin animación (ROOM puesto a mano, servidor viejo, recién llegado)
			v = addVis(p.owner(), p.slot(), key, mc);
			v.dim = p.dim();
			v.snap(p.x(), p.y(), p.z(), p.radius());
			v.state = p.state();
			return;
		}
		if (v.dim == null || !v.dim.equals(p.dim())) {
			v.dim = p.dim();
			v.snap(p.x(), p.y(), p.z(), p.radius());
			v.hidden = false;
			v.state = p.state();
			v.ground.reset();
			return;
		}
		v.tx = p.x();
		v.ty = p.y();
		v.tz = p.z();
		v.rTarget = p.radius();
		v.state = p.state();
		if (v.state != FLYING) {
			v.hidden = false;
			v.fadeTarget = 1f;
		}
	}

	private static Vis addVis(UUID owner, int slot, String key, MinecraftClient mc) {
		boolean mine = mc.player != null && owner.equals(mc.player.getUuid());
		Vis v = new Vis(key, owner, slot, mine);
		VIS.add(v);
		BY_KEY.put(key, v);
		return v;
	}

	private static void removeVis(Vis v) {
		VIS.remove(v);
		BY_KEY.remove(v.key);
	}

	@Nullable
	static Vis get(UUID owner, int slot) {
		return BY_KEY.get(owner + ":" + slot);
	}

	/** El ROOM de esta clave ("dueño:slot"), o null (la cuerda lo busca así). */
	@Nullable
	public static Vis byKey(String key) {
		return BY_KEY.get(key);
	}

	// ------------------------------------------------------------------ momentos (RoomFx)

	/**
	 * Nace: la esfera florece desde la mano (hand: la del propio jugador calculada aquí, o el punto a del servidor) hasta
	 * el centro en 6 ticks, con una onda; pulso al máximo.
	 */
	static void onCreate(Payloads.RoomFx fx, boolean own, Vec3d hand) {
		MinecraftClient mc = MinecraftClient.getInstance();
		Identifier dim = currentDim();
		if (dim == null) return;
		String key = fx.owner() + ":" + fx.slot();
		Vis old = BY_KEY.get(key);
		if (old != null) removeVis(old);
		Vis v = addVis(fx.owner(), fx.slot(), key, mc);
		v.dim = dim;
		v.snap(fx.bx(), fx.by(), fx.bz(), fx.radius());
		v.state = GROWING;
		v.bloomFrom = 0.3f;
		v.r = v.prevR = 0.3f;
		v.bloomLen = 6;
		v.bloomTick = 0;
		v.bloomMove = true;
		v.fromX = hand.x;
		v.fromY = hand.y;
		v.fromZ = hand.z;
		v.cx = v.pcx = hand.x;
		v.cy = v.pcy = hand.y;
		v.cz = v.pcz = hand.z;
		v.pulse = v.prevPulse = 1f;
		shell(Fx.SHOCK, v, fx.bx(), fx.by(), fx.bz(), fx.radius(), 0.35, 0.6f * v.strength);
	}

	/** Queda fijo: un pulso. */
	static void onFix(Payloads.RoomFx fx) {
		Vis v = BY_KEY.get(fx.owner() + ":" + fx.slot());
		if (v == null) return;
		if (v.state == GROWING) v.state = FIXED;
		v.rTarget = fx.radius();
		v.pulse = Math.min(1.6f, v.pulse + 0.5f);
	}

	/** Una muesca: eco en el radio de antes; la esfera va al nuevo con suavidad. */
	static void onNotch(Payloads.RoomFx fx) {
		Vis v = BY_KEY.get(fx.owner() + ":" + fx.slot());
		if (v == null) return;
		if (v.state == GROWING) v.state = FIXED;
		v.rTarget = fx.radius();
		v.pulse = Math.min(1.6f, v.pulse + 0.3f);
		if (v.shown() && Math.abs(fx.radius2() - fx.radius()) > 0.05f) {
			shell(Fx.ECHO, v, v.tx, v.ty, v.tz, fx.radius2(), 0.5, 0.55f * v.strength);
		}
	}

	/** Lanzado: el cometa sale de la mano (hand); el viejo queda al 55 % (o no se ve si se acaba de crear). */
	@Nullable
	static Fx onCast(Payloads.RoomFx fx, boolean own, Vec3d hand) {
		Identifier dim = currentDim();
		if (dim == null) return null;
		String key = fx.owner() + ":" + fx.slot();
		Vis v = BY_KEY.get(key);
		if (v != null) {
			v.state = FLYING;
			if ((fx.flags() & Payloads.RoomFx.F_FRESH) != 0) v.hidden = true;
			else v.fadeTarget = 0.55f;
		}
		endEffects(key, Fx.COMET);
		Fx c = add(new Fx(Fx.COMET, key, own, fx.slot() == 1 ? RGB_RROOM : RGB_ROOM, dim, RoomFxClient.time(), Math.max(1, fx.ticks()) / 20.0));
		c.ax = hand.x;
		c.ay = hand.y;
		c.az = hand.z;
		c.bx = fx.bx();
		c.by = fx.by();
		c.bz = fx.bz();
		c.arc = fx.radius2();
		c.r0 = fx.radius();
		c.alpha = own ? 1f : 0.8f;
		return c;
	}

	/**
	 * Aterriza: el viejo se pliega, el nuevo aparece un tick después en el punto y florece de 0,2 R a R en 6 ticks; un
	 * destello y una onda por el suelo.
	 */
	static void onLand(Payloads.RoomFx fx, boolean own) {
		MinecraftClient mc = MinecraftClient.getInstance();
		Identifier dim = currentDim();
		if (dim == null) return;
		String key = fx.owner() + ":" + fx.slot();
		endEffects(key, Fx.COMET);
		Vis v = BY_KEY.get(key);
		int rgb = fx.slot() == 1 ? RGB_RROOM : RGB_ROOM;
		float strength = own ? 1f : 0.8f;
		if ((fx.flags() & Payloads.RoomFx.F_OLD) != 0 && fx.radius2() > 0.05f) {
			Fx f = add(new Fx(Fx.FOLD, key, own, rgb, dim, RoomFxClient.time(), 0.34));
			f.ax = fx.bx();
			f.ay = fx.by();
			f.az = fx.bz();
			f.r0 = fx.radius2();
			f.alpha = 0.85f * strength;
		}
		if (v == null) v = addVis(fx.owner(), fx.slot(), key, mc);
		v.dim = dim;
		v.snap(fx.ax(), fx.ay(), fx.az(), fx.radius());
		v.state = FIXED;
		v.hidden = false;
		v.fade = v.prevFade = v.fadeTarget = 1f;
		v.bringing = false;
		v.bloomFrom = 0.2f * fx.radius();
		v.bloomLen = 6;
		v.bloomMove = false;
		v.bloomTick = 0;
		v.hideTicks = 1;
		v.r = v.prevR = 0f;
		v.pulse = v.prevPulse = 1f;
		v.ground.reset();

		Fx flash = add(new Fx(Fx.FLASH, key, own, rgb, dim, RoomFxClient.time(), 0.25));
		flash.ax = fx.ax();
		flash.ay = fx.ay();
		flash.az = fx.az();
		flash.r0 = fx.radius();
		flash.alpha = 0.9f;
		ring(key, own, rgb, dim, fx.ax(), fx.ay(), fx.az(), Math.min(40f, 0.55f * fx.radius()));
	}

	/** Vuelve a ti: la cuerda hasta tu pecho y ecos cada 2 ticks por el camino. */
	static void onBring(Payloads.RoomFx fx, boolean own, int clientTick) {
		Identifier dim = currentDim();
		if (dim == null) return;
		String key = fx.owner() + ":" + fx.slot();
		Vis v = BY_KEY.get(key);
		if (v != null) {
			v.state = FLYING;
			v.bringing = true;
			v.bringStart = clientTick;
			v.bringEnd = clientTick + Math.max(1, fx.ticks()) + 20;
		}
		endEffects(key, Fx.TETHER);
		Fx t = add(new Fx(Fx.TETHER, key, own, fx.slot() == 1 ? RGB_RROOM : RGB_ROOM, dim, RoomFxClient.time(),
			Math.max(1, fx.ticks()) / 20.0 + 1.0));
		t.ax = fx.ax();
		t.ay = fx.ay();
		t.az = fx.az();
		t.bx = fx.bx();
		t.by = fx.by();
		t.bz = fx.bz();
		t.owner = fx.owner();
		t.alpha = own ? 0.5f : 0.4f;
	}

	/** Llega contigo: la cuerda se suelta y una onda corre por el suelo. */
	static void onArrive(Payloads.RoomFx fx, boolean own) {
		Identifier dim = currentDim();
		if (dim == null) return;
		String key = fx.owner() + ":" + fx.slot();
		endEffects(key, Fx.TETHER);
		Vis v = BY_KEY.get(key);
		if (v != null) {
			v.bringing = false;
			v.state = FIXED;
			v.tx = fx.ax();
			v.ty = fx.ay();
			v.tz = fx.az();
			v.rTarget = fx.radius();
			v.pulse = Math.min(1.6f, v.pulse + 0.6f);
		}
		ring(key, own, fx.slot() == 1 ? RGB_RROOM : RGB_ROOM, dim, fx.ax(), fx.ay(), fx.az(), Math.min(30f, 0.4f * fx.radius()));
	}

	/** Se cierra: la esfera se encoge hasta nada en 0,26 s (y luego el fogonazo, en partículas). */
	static void onClose(Payloads.RoomFx fx, boolean own) {
		String key = fx.owner() + ":" + fx.slot();
		endEffects(key, Fx.COMET);
		endEffects(key, Fx.TETHER);
		Vis v = BY_KEY.get(key);
		Identifier dim = currentDim();
		if (v != null) {
			removeVis(v);
			if (v.shown() && v.dim != null && v.dim.equals(dim)) {
				shell(Fx.CLOSE, v, v.cx, v.cy, v.cz, v.r, 0.26, 0.9f * v.strength);
			}
		}
	}

	/** El ROOM de esta clave existe aquí y se ve (para decidir si un cierre lleva luces en el mundo). */
	static boolean visibleHere(UUID owner, int slot) {
		Vis v = BY_KEY.get(owner + ":" + slot);
		Identifier dim = currentDim();
		return v != null && v.shown() && dim != null && dim.equals(v.dim);
	}

	// ------------------------------------------------------------------ efectos

	private static Fx add(Fx f) {
		EFFECTS.add(f);
		while (EFFECTS.size() > MAX_EFFECTS) EFFECTS.remove(0);
		return f;
	}

	private static Fx shell(int type, Vis v, double x, double y, double z, float r0, double dur, float alpha) {
		Identifier dim = v.dim != null ? v.dim : currentDim();
		Fx f = add(new Fx(type, v.key, v.mine, v.rgb(), dim, RoomFxClient.time(), dur));
		f.ax = x;
		f.ay = y;
		f.az = z;
		f.r0 = r0;
		f.alpha = alpha;
		return f;
	}

	private static void endEffects(String key, int type) {
		for (int i = 0; i < EFFECTS.size(); i++) {
			Fx f = EFFECTS.get(i);
			if (f.type == type && f.key.equals(key)) f.ended = true;
		}
	}

	/** Busca el cometa vivo de esta clave (para el sonido que lo sigue). */
	@Nullable
	static Fx comet(String key) {
		for (int i = EFFECTS.size() - 1; i >= 0; i--) {
			Fx f = EFFECTS.get(i);
			if (f.type == Fx.COMET && !f.ended && f.key.equals(key)) return f;
		}
		return null;
	}

	/**
	 * Una onda por el suelo: de 0 a rmax en 0,6 s. Guarda la altura del terreno en 64 ángulos y 4 radios; donde el suelo
	 * se aleja más de 4 bloques del suelo de referencia (acantilado, tejado) la onda no se dibuja.
	 */
	private static void ring(String key, boolean own, int rgb, Identifier dim, double cx, double cy, double cz, float rmax) {
		if (rmax < 0.5f) return;
		MinecraftClient mc = MinecraftClient.getInstance();
		ClientWorld w = mc.world;
		if (w == null) return;
		Fx f = add(new Fx(Fx.RING, key, own, rgb, dim, RoomFxClient.time(), 0.6));
		f.ax = cx;
		f.az = cz;
		f.r0 = rmax;
		f.alpha = own ? 0.8f : 0.6f;
		int bottom = w.getBottomY();
		double ref = cy - 1;
		int top = w.getTopY(Heightmap.Type.MOTION_BLOCKING, (int) Math.floor(cx), (int) Math.floor(cz));
		if (top > bottom && top <= cy + 1.5 && top >= cy - 12) ref = top;
		f.ay = ref;
		float[] hs = new float[Fx.RING_ANGLES * Fx.RING_STEPS];
		for (int i = 0; i < Fx.RING_ANGLES; i++) {
			double a = Math.PI * 2 * i / Fx.RING_ANGLES;
			double c = Math.cos(a), s = Math.sin(a);
			for (int j = 0; j < Fx.RING_STEPS; j++) {
				double rr = rmax * (j + 1) / Fx.RING_STEPS;
				int h = w.getTopY(Heightmap.Type.MOTION_BLOCKING, (int) Math.floor(cx + c * rr), (int) Math.floor(cz + s * rr));
				hs[i * Fx.RING_STEPS + j] = h > bottom && Math.abs(h - ref) <= 4 ? h : Float.NaN;
			}
		}
		f.heights = hs;
	}

	// ------------------------------------------------------------------ cada tick

	/** Avanza un tick (no se llama en pausa). */
	static void tick(MinecraftClient mc, int clientTick) {
		ClientWorld w = mc.world;
		Identifier dim = currentDim();
		double now = RoomFxClient.time();
		Vec3d cam = mc.gameRenderer != null && mc.gameRenderer.getCamera().isReady()
			? mc.gameRenderer.getCamera().getPos()
			: (mc.player != null ? mc.player.getEyePos() : Vec3d.ZERO);

		for (int i = 0; i < VIS.size(); i++) {
			Vis v = VIS.get(i);
			v.tick(clientTick);
			if (dim == null || !dim.equals(v.dim) || !v.shown()) continue;
			double dx = v.cx - cam.x, dy = v.cy - cam.y, dz = v.cz - cam.z;
			double far = 256 + v.r;
			boolean near = dx * dx + dy * dy + dz * dz < far * far;
			if (near && w != null) v.ground.update(w, v.cx, v.cy, v.cz, v.r, clientTick);
			if (near && v.state == GROWING) RoomParticles.growing(v.cx, v.cy, v.cz, v.r, cam, v.mine);
			if (v.bringing && ((clientTick - v.bringStart) & 1) == 0) {
				Fx e = shell(Fx.ECHO, v, v.cx, v.cy, v.cz, v.r, 0.3, 0.3f * v.strength);
				e.alpha = 0.3f * v.strength;
			}
		}

		// efectos: los terminados fuera; el cometa y la cuerda echan chispas
		for (int i = EFFECTS.size() - 1; i >= 0; i--) {
			Fx f = EFFECTS.get(i);
			double age = now - f.start;
			boolean dead = f.ended || switch (f.type) {
				case Fx.COMET -> age > f.dur + 1.5;     // espera en el destino a que llegue el aterrizaje
				case Fx.TETHER -> age > f.dur + 1.0;
				default -> age > f.dur;
			};
			if (dead) {
				EFFECTS.remove(i);
				continue;
			}
			if (dim == null || !dim.equals(f.dim)) continue;
			if (f.type == Fx.COMET && age <= f.dur) {
				f.cometPos(now, TMP);
				RoomParticles.comet(TMP[0], TMP[1], TMP[2], f.own);
			} else if (f.type == Fx.TETHER) {
				Vis v = BY_KEY.get(f.key);
				PlayerEntity p = f.owner != null && w != null ? w.getPlayerByUuid(f.owner) : null;
				if (v != null && p != null) {
					RoomParticles.tether(new Vec3d(v.cx, v.cy, v.cz), p.getPos().add(0, 1.1, 0), f.own);
				}
			}
		}

		// la semilla: chispas en la mano; caduca a los 10 ticks si el ROOM no llega
		if (seedStart >= 0) {
			seedTicks++;
			if (seedFadeAt < 0 && seedTicks > 10) endSeed(false);
			if (seedFadeAt >= 0 && now - seedFadeAt > seedFadeDur) {
				seedStart = -1;
				seedFadeAt = -1;
			} else if (seedFadeAt < 0 && mc.player != null) {
				handPoint(mc.player, 1f, TMP);
				RoomParticles.seed(new Vec3d(TMP[0], TMP[1], TMP[2]));
			}
		}
	}

	static void clear() {
		VIS.clear();
		BY_KEY.clear();
		EFFECTS.clear();
		seedStart = -1;
		seedFadeAt = -1;
	}
}
