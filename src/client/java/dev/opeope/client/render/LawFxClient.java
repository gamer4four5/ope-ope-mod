package dev.opeope.client.render;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import dev.opeope.client.ClientState;
import dev.opeope.client.config.OpeSettings;
import dev.opeope.client.room.CameraFx;
import dev.opeope.client.room.RoomFxClient;
import dev.opeope.net.Payloads;
import dev.opeope.net.Payloads.LawFx;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.render.BufferBuilder;
import net.minecraft.client.render.BufferRenderer;
import net.minecraft.client.render.GameRenderer;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.RenderTickCounter;
import net.minecraft.client.render.Tessellator;
import net.minecraft.client.render.VertexFormat;
import net.minecraft.client.render.VertexFormats;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.ListIterator;

/**
 * Los efectos 3D de las técnicas de Law, sacados del anime: el tajo eléctrico del Radio Knife, la retícula y el
 * disparo de Injection Shot, la hoja corta de luz verde-amarilla de Gamma Knife que brilla dentro del cuerpo, el velo
 * quirúrgico del Curtain, el cubo de cristal de Mes, las palas del desfibrilador de Counter Shock, el barrido de
 * resonancia de Scan, las almas que se cruzan, el Despertar, la esfera de K-ROOM que envuelve la Kikoku, Anesthesia,
 * la onda interna de Shock Wille, la hoja colosal y la implosión de Puncture Wille, la burbuja muda de Silent, los
 * tajos de la Kikoku y el R-ROOM lanzado.
 * <p>
 * Todo son quads POSITION_COLOR aditivos en AFTER_TRANSLUCENT (mismo esquema que {@link GravityFxClient}); lo que pasa
 * dentro de un cuerpo (Gamma Knife, Shock Wille, Counter Shock, Anesthesia y los seres que barre el Scan) se dibuja
 * otra vez sin profundidad, como rayos X ({@link XRayFx}). La Kikoku gigante de K-ROOM la dibuja {@link KikokuBladeFx};
 * la «presentación anime» (rótulo con el nombre, fotograma de impacto, tajo en pantalla) {@link LawHudFx}, y los
 * cuerpos que reaccionan al golpe {@link LawBodyFx}.
 * Respeta «menos movimiento» (sin temblores ni parpadeos) y el nivel de destellos.
 */
public final class LawFxClient {
	private LawFxClient() {}

	// la paleta de Law: el celeste del ROOM, el blanco del filo, la electricidad, el verde-amarillo de Gamma Knife…
	static final int ROOM = 0x8FE3FF;
	static final int CORE = 0xEFFCFF;
	static final int DEEP = 0x2F8CFF;
	static final int ELEC = 0x9FDcFF;
	static final int ELEC_HOT = 0xF4FBFF;
	static final int GAMMA_G = 0x8DFF7A;
	static final int GAMMA_Y = 0xFFF06A;
	static final int HEART = 0xFF3A5C;
	static final int SOUL_A = 0xFF86D8;
	static final int SOUL_B = 0x86ECFF;
	static final int GOLD = 0xFFD966;
	static final int HUSH = 0x6474FF;
	static final int DRAPE = 0xD8F4FF;

	static final int SEG = 40;
	static final float[] COS = new float[SEG + 1];
	static final float[] SIN = new float[SEG + 1];

	static {
		for (int i = 0; i <= SEG; i++) {
			double a = Math.PI * 2.0 * i / SEG;
			COS[i] = (float) Math.cos(a);
			SIN[i] = (float) Math.sin(a);
		}
	}

	record Ev(int kind, int userId, int targetId, Vec3d a, Vec3d b, float radius, float power,
					  double duration, double start, int seed) {}

	static final List<Ev> EVENTS = new ArrayList<>();
	static final int MAX_EVENTS = 64;

	static double pulseStart = -100;
	static float pulseStrength;
	static int pulseRgb = ROOM;
	static double scanStart = -100;
	/** Los rayos X del Scan propio: desde y hasta cuándo, y el centro y el radio del barrido. */
	static double scanXrayStart = -100, scanXrayUntil = -100, scanXrayRadius;
	static Vec3d scanXrayCenter = Vec3d.ZERO;
	static final int SCAN_XRAY_MAX = 48;
	/** El último Anesthesia propio (el servidor lo reenvía cada segundo mientras se mantiene: un solo rótulo). */
	static double lastAnesthesia = -100;
	/** Lo larga que es la hoja de Gamma Knife (en bloques). */
	static final double GAMMA_LEN = 1.0;

	static final Matrix4f BASE = new Matrix4f();
	static boolean plain;
	static final Vector3f TV = new Vector3f();
	static double u1x, u1y, u1z, u2x, u2y, u2z;
	static Vec3d cam = Vec3d.ZERO;
	static float td;

	/** Cuánto dura cada efecto en segundos como mínimo (el servidor puede pedir más con ticks). */
	static final double[] MIN_SECONDS = {
		0.75, 0.45, 0.6, 0.95, 1.0, 1.15, 0.75, 1.5, 1.1, 0.45, 1.2, 0.95, 1.0, 1.3, 2.2, 1.0, 0.6, 0.4, 0.9, 0.6, 0.6,
		1.0, 1.2, 1.4
	};

	public static void clear() {
		EVENTS.clear();
		pulseStrength = 0f;
		scanStart = -100;
		scanXrayStart = -100;
		scanXrayUntil = -100;
		scanXrayRadius = 0;
		scanXrayCenter = Vec3d.ZERO;
		lastAnesthesia = -100;
		KikokuBladeFx.clear();
		LawHudFx.clear();
		LawBodyFx.clear();
	}

	public static void receive(LawFx p) {
		if (p == null || p.kind() < 0 || p.kind() >= LawFx.KINDS) return;
		double now = RoomFxClient.time();
		double dur = Math.max(MIN_SECONDS[p.kind()], p.ticks() / 20.0);
		int seed = (int) (Double.doubleToLongBits(p.ax() * 31 + p.bz() * 17) ^ System.nanoTime());
		EVENTS.add(new Ev(p.kind(), p.userId(), p.targetId(), new Vec3d(p.ax(), p.ay(), p.az()), new Vec3d(p.bx(), p.by(), p.bz()),
			p.radius(), p.power(), dur, now, seed));
		while (EVENTS.size() > MAX_EVENTS) EVENTS.remove(0);

		gesture(p);

		MinecraftClient mc = MinecraftClient.getInstance();
		if (mc.player == null) return;
		boolean mine = p.userId() == mc.player.getId();
		double dist = Math.sqrt(mc.player.squaredDistanceTo(p.bx(), p.by(), p.bz()));
		anime(p, mine, dist, now);
		switch (p.kind()) {
			case LawFx.RADIO_KNIFE -> {
				if (mine) {
					CameraFx.kick(-1.6f, 0.8f, 1.2f);
					CameraFx.trauma(0.22f);
					pulse(0.22f, ELEC);
				}
			}
			case LawFx.INJECTION_DASH -> {
				if (mine) {
					CameraFx.fov(7.5f, 0.03f, 0.40f);
					CameraFx.kick(1.2f, 0f, 0f);
				}
			}
			case LawFx.INJECTION_HIT -> {
				if (mine || dist < 24) {
					float k = mine ? 1f : (float) (1.0 - dist / 24.0);
					CameraFx.trauma(0.42f * k);
					CameraFx.kick(-2.6f * k, 0f, 0f);
					CameraFx.fov(-4.5f * k, 0.02f, 0.30f);
					pulse(0.30f * k, ROOM);
				}
			}
			case LawFx.GAMMA -> {
				if (mine) {
					CameraFx.kick(-1.0f, 0f, 0f);
					CameraFx.fov(-2.5f, 0.03f, 0.35f);
					pulse(0.22f, GAMMA_G);
				}
			}
			case LawFx.MES -> {
				if (mine) {
					CameraFx.kick(-0.8f, 0f, 0f);
					pulse(0.16f, HEART);
				}
			}
			case LawFx.COUNTER_SHOCK -> {
				if (mine) {
					CameraFx.trauma(0.34f);
					CameraFx.kick(-1.4f, 0f, 0f);
					pulse(0.30f, ELEC_HOT);
				}
			}
			case LawFx.SCAN -> {
				if (mine) {
					scanStart = now;
					// los rayos X duran lo que el Scan; ClientState.scanTicks puede llegar un tick después: 8 s como mínimo
					scanXrayStart = now;
					scanXrayUntil = now + Math.max(8.0, ClientState.scanTicks / 20.0);
					scanXrayCenter = new Vec3d(p.ax(), p.ay(), p.az());
					scanXrayRadius = Math.max(3.0, p.radius());
				}
			}
			case LawFx.AWAKEN -> {
				if (mine) {
					CameraFx.trauma(0.25f);
					CameraFx.fov(p.power() > 0.5f ? 5f : -3f, 0.06f, 0.6f);
					pulse(0.28f, p.power() > 0.5f ? GOLD : ROOM);
				}
			}
			case LawFx.KROOM -> {
				if (mine) {
					CameraFx.fov(3.5f, 0.05f, 0.45f);
					pulse(0.18f, ROOM);
				}
			}
			case LawFx.SHOCK_WILLE -> {
				if (mine || dist < 48) {
					float k = mine ? 1f : (float) (1.0 - dist / 48.0);
					CameraFx.trauma(0.6f * k);
					CameraFx.kick(-3.2f * k, 0f, 1.4f * k);
					CameraFx.fov(-6f * k, 0.03f, 0.55f);
					pulse(0.40f * k, ELEC_HOT);
				}
			}
			case LawFx.PUNCTURE -> {
				if (dist < 90) {
					float k = (float) MathHelper.clamp(1.0 - dist / 90.0, 0.2, 1.0);
					CameraFx.trauma(0.85f * k);
					CameraFx.kick(-4.0f * k, 0f, 2.0f * k);
					CameraFx.fov(-8f * k, 0.05f, 0.9f);
					pulse(0.45f * k, CORE);
				}
			}
			case LawFx.SILENT -> {
				if (mine) CameraFx.fov(-2.5f, 0.10f, 0.8f);
			}
			case LawFx.SWORD -> {
				if (mine) {
					CameraFx.kick(-1.2f, 0.6f, 1.6f);
					CameraFx.trauma(0.18f);
				}
			}
			case LawFx.RROOM_BURST -> {
				if (dist < 64) {
					float k = (float) (1.0 - dist / 64.0);
					CameraFx.trauma(0.45f * k);
					pulse(0.30f * k, ROOM);
				}
			}
			case LawFx.HEART_SQUEEZE -> {
				if (p.targetId() == mc.player.getId()) {
					CameraFx.trauma(0.4f);
					pulse(0.45f, HEART);
				}
			}
			default -> {}
		}
	}

	/** El gesto de Law en el jugador que usa la técnica (lo ven todos). */
	static void gesture(LawFx p) {
		if (p.userId() < 0) return;
		int g = switch (p.kind()) {
			case LawFx.RADIO_KNIFE, LawFx.SWORD, LawFx.SHOCK_WILLE, LawFx.RAIN_SLASH -> PlayerAnimClient.GESTURE_SLASH;
			case LawFx.INJECTION_DASH, LawFx.GAMMA, LawFx.MES, LawFx.ANESTHESIA -> PlayerAnimClient.GESTURE_THRUST;
			case LawFx.COUNTER_SHOCK -> PlayerAnimClient.GESTURE_TWO_PALMS;
			case LawFx.CURTAIN -> PlayerAnimClient.GESTURE_SWEEP;
			case LawFx.PUNCTURE -> PlayerAnimClient.GESTURE_OVERHEAD;
			case LawFx.KROOM, LawFx.RROOM_CAST, LawFx.AWAKEN, LawFx.SILENT, LawFx.SCAN -> PlayerAnimClient.GESTURE_PALM;
			case LawFx.SOULS, LawFx.STERBEN -> PlayerAnimClient.GESTURE_INDEX;
			default -> -1;
		};
		if (g >= 0 && !(p.kind() == LawFx.RAIN_SLASH)) PlayerAnimClient.triggerGesture(p.userId(), g);
	}

	static void pulse(float strength, int rgb) {
		if (strength <= 0.01f) return;
		pulseStart = RoomFxClient.time();
		pulseStrength = Math.min(0.6f, strength);
		pulseRgb = rgb;
	}

	/**
	 * La «presentación anime» de cada técnica: el rótulo con su nombre (solo las propias), el fotograma de impacto,
	 * el tajo que cruza la pantalla, el cuerpo del objetivo que reacciona y la Kikoku gigante de los demás Law.
	 */
	static void anime(LawFx p, boolean mine, double dist, double now) {
		int kind = p.kind();
		if (mine) banner(p, now);
		// el fotograma de impacto: técnicas propias, o de otro a menos de 24 bloques (más flojo cuanto más lejos)
		float impact = switch (kind) {
			case LawFx.SHOCK_WILLE, LawFx.PUNCTURE -> 1.0f;
			case LawFx.INJECTION_HIT -> 0.8f;
			case LawFx.GAMMA -> 0.7f;
			case LawFx.COUNTER_SHOCK, LawFx.RROOM_BURST -> 0.6f;
			case LawFx.RADIO_KNIFE -> 0.5f;
			case LawFx.MES -> 0.4f;
			default -> 0f;
		};
		if (impact > 0f && (mine || dist < 24)) {
			float k = mine ? 1f : (float) (1.0 - dist / 24.0);
			if (impact * k > 0.04f) LawHudFx.impact(impact * k, 0.5f, 0.5f);
		}
		// el tajo que cruza la pantalla: el Iai (Law ya está de espaldas al corte) y el Radio Knife
		if (mine && kind == LawFx.SWORD && p.targetId() < 0 && Math.round(p.power()) == LawFx.SWORD_IAI) {
			LawHudFx.slash(-28f, 1f);
		}
		if (mine && kind == LawFx.RADIO_KNIFE) LawHudFx.slash(35f, 0.6f);
		// el cuerpo del objetivo reacciona: se sacude, se hincha por dentro o sale despedido
		int id = p.targetId();
		if (id >= 0) {
			switch (kind) {
				case LawFx.COUNTER_SHOCK -> bodyShake(id, 1.0f, 0.7);
				case LawFx.RADIO_KNIFE -> bodyShake(id, 0.6f, 0.35);
				case LawFx.SHOCK_WILLE -> {
					LawBodyFx.bulge(id, 1.0f, 0.55);
					bodyShake(id, 0.8f, 0.6);
				}
				case LawFx.GAMMA -> {
					LawBodyFx.bulge(id, 0.5f, 0.45);
					bodyShake(id, 0.35f, 0.4);
				}
				// a = el centro del objetivo, b = a + la dirección de la embestida
				case LawFx.INJECTION_HIT -> bodyRecoil(id, p.bx() - p.ax(), p.bz() - p.az(), 1.0f, 0.45);
				// a = la mano de Law, b = el pecho del objetivo
				case LawFx.MES -> bodyRecoil(id, p.bx() - p.ax(), p.bz() - p.az(), 0.4f, 0.3);
				case LawFx.ANESTHESIA -> bodyShake(id, 0.2f, 0.3);
				default -> {}
			}
		}
		// la Kikoku gigante de otro Law: se ve mientras le dura la hoja en el servidor (la propia sale de ClientState)
		if (!mine && p.userId() >= 0) {
			double secs = switch (kind) {
				case LawFx.KROOM -> 14.0;
				case LawFx.ANESTHESIA, LawFx.SHOCK_WILLE -> 10.0;
				case LawFx.PUNCTURE -> 12.0;
				default -> 0.0;
			};
			if (secs > 0) KikokuBladeFx.noteBlade(p.userId(), now + secs);
		}
	}

	/** El rótulo con el nombre de la técnica propia; no para los golpes sueltos ni para los reenvíos del servidor. */
	static void banner(LawFx p, double now) {
		int kind = p.kind();
		switch (kind) {
			case LawFx.INJECTION_HIT, LawFx.RROOM_BURST, LawFx.HEART_SQUEEZE, LawFx.RAIN_SLASH -> {
				return;
			}
			case LawFx.SWORD -> {
				if (p.targetId() >= 0) return;   // K-ROOM manda un SWORD por cada víctima
			}
			case LawFx.ANESTHESIA -> {
				// el servidor lo reenvía cada segundo mientras se mantiene: un rótulo por uso, no uno por reenvío
				double last = lastAnesthesia;
				lastAnesthesia = now;
				if (now - last < 3.0) return;
			}
			default -> {}
		}
		int variant = kind == LawFx.SWORD ? Math.round(p.power()) : kind == LawFx.AWAKEN ? (p.power() > 0.5f ? 1 : 0) : 0;
		LawHudFx.banner(kind, variant);
	}

	/** El cuerpo se sacude (nada con «menos movimiento»: sin temblores). */
	static void bodyShake(int id, float strength, double seconds) {
		if (!OpeSettings.reducedMotion()) LawBodyFx.shake(id, strength, seconds);
	}

	/** El cuerpo sale despedido hacia (dx, dz), en horizontal. */
	static void bodyRecoil(int id, double dx, double dz, float strength, double seconds) {
		double l = Math.sqrt(dx * dx + dz * dz);
		if (l < 1e-4) return;
		LawBodyFx.recoil(id, dx / l, dz / l, strength, seconds);
	}

	/** Cuánto pesan los fogonazos grandes según el nivel de destellos (2 = todos, 1 = suaves, 0 = casi nada). */
	static float flashScale() {
		int level = OpeSettings.flashLevel();
		return level >= 2 ? 1f : level == 1 ? 0.6f : 0.3f;
	}

	// ------------------------------------------------------------------ dibujo en el mundo

	public static void renderWorld(WorldRenderContext ctx) {
		MinecraftClient mc = MinecraftClient.getInstance();
		if (mc.world == null || mc.player == null) return;
		double now = RoomFxClient.time();
		int self = mc.player.getId();
		boolean silentSelf = ClientState.has(Payloads.State.SILENT);
		boolean curtainSelf = ClientState.has(Payloads.State.CURTAIN);
		ListIterator<Ev> it = EVENTS.listIterator();
		while (it.hasNext()) {
			Ev e = it.next();
			double age = now - e.start;
			if (age < 0 || age > e.duration) {
				it.remove();
				continue;
			}
			// Curtain y Silent propios: si el servidor ya quitó el estado (la técnica acabó), se apagan en medio segundo
			// en vez de quedarse pegados en pantalla hasta el final del efecto
			if (e.userId == self && age > 0.4 && e.duration - age > 0.55
				&& ((e.kind == LawFx.CURTAIN && !curtainSelf) || (e.kind == LawFx.SILENT && !silentSelf))) {
				it.set(new Ev(e.kind, e.userId, e.targetId, e.a, e.b, e.radius, e.power, age + 0.5, e.start, e.seed));
			}
		}
		// los rayos X del Scan: si el Scan dura más de lo previsto (ClientState.scanTicks llega un tick después), se alargan
		if (ClientState.scanTicks > 0 && now - scanXrayStart < 1.0) {
			scanXrayUntil = Math.max(scanXrayUntil, now + ClientState.scanTicks / 20.0);
		}
		float xray = scanXrayAlpha(now);
		boolean blades = KikokuBladeFx.anyActive(mc);
		if (EVENTS.isEmpty() && !silentSelf && !blades && xray <= 0.01f) return;

		cam = ctx.camera().getPos();
		td = ctx.tickCounter().getTickDelta(true);
		boolean calm = OpeSettings.reducedMotion();
		Matrix4f mv = RenderSystem.getModelViewMatrix();
		if (Math.abs(mv.m00() - 1) < 1e-4f && Math.abs(mv.m11() - 1) < 1e-4f && Math.abs(mv.m22() - 1) < 1e-4f) {
			BASE.set(ctx.positionMatrix());
			plain = false;
		} else {
			BASE.identity();
			plain = true;
		}

		RenderSystem.enableBlend();
		RenderSystem.blendFunc(GlStateManager.SrcFactor.SRC_ALPHA, GlStateManager.DstFactor.ONE);
		RenderSystem.disableCull();
		RenderSystem.enableDepthTest();
		RenderSystem.depthMask(false);
		RenderSystem.setShader(GameRenderer::getPositionColorProgram);
		try {
			BufferBuilder b = Tessellator.getInstance().begin(VertexFormat.DrawMode.QUADS, VertexFormats.POSITION_COLOR);
			for (Ev e : EVENTS) {
				double t = MathHelper.clamp((now - e.start) / e.duration, 0.0, 1.0);
				drawEvent(b, mc, e, t, now - e.start, now, calm);
			}
			// la Kikoku gigante (K-ROOM) de quien la tenga desenvainada
			KikokuBladeFx.draw(b, mc, now, calm);
			end(b);
			// dentro de los cuerpos, como rayos X: Gamma Knife, Shock Wille, Counter Shock y Anesthesia brillan a través
			// de la carne (solo con destellos); y mientras dura el Scan propio, la anatomía de los seres que barre y lo
			// que llevan en la mano (eso es información, no un destello: se ve con cualquier nivel)
			boolean inside = OpeSettings.flashLevel() > 0;
			if (inside || xray > 0.01f) {
				RenderSystem.disableDepthTest();
				BufferBuilder x = Tessellator.getInstance().begin(VertexFormat.DrawMode.QUADS, VertexFormats.POSITION_COLOR);
				if (inside) {
					for (Ev e : EVENTS) {
						double t = MathHelper.clamp((now - e.start) / e.duration, 0.0, 1.0);
						drawInside(x, mc, e, t, now, calm);
					}
				}
				if (xray > 0.01f) scanXray(x, mc, now, xray);
				end(x);
			}
		} finally {
			RenderSystem.enableDepthTest();
			RenderSystem.defaultBlendFunc();
			RenderSystem.depthMask(true);
			RenderSystem.enableCull();
			RenderSystem.disableBlend();
		}
	}

	static void end(BufferBuilder b) {
		var built = b.endNullable();
		if (built != null) BufferRenderer.drawWithGlobalProgram(built);
	}

	/**
	 * Lo visibles que son ahora los rayos X del Scan propio: entran en 0,3 s y se apagan en los últimos 0,6 s.
	 * Duran mientras el servidor diga que el Scan sigue (ClientState.scanTicks), o el primer medio segundo, que es
	 * lo que puede tardar en llegar ese estado.
	 */
	static float scanXrayAlpha(double now) {
		double since = now - scanXrayStart;
		if (since < 0 || now >= scanXrayUntil) return 0f;
		double left = scanXrayUntil - now;
		if (ClientState.scanTicks > 0) left = Math.min(left, ClientState.scanTicks / 20.0);
		else if (since >= 0.5) return 0f;
		return (float) (Math.min(1.0, since / 0.3) * Math.min(1.0, left / 0.6));
	}

	/**
	 * Scan: los seres vivos dentro del barrido se ven por dentro (esqueleto celeste y corazón) a través de las paredes,
	 * con una marca dorada en lo que llevan en la mano, lo que luego se les puede quitar con Shambles.
	 */
	static void scanXray(BufferBuilder b, MinecraftClient mc, double now, float a) {
		if (mc.world == null) return;
		final Vec3d c = scanXrayCenter;
		final double r = Math.max(3.0, scanXrayRadius);
		final double r2 = r * r;
		final Entity self = mc.player;
		List<LivingEntity> list = mc.world.getEntitiesByClass(LivingEntity.class,
			new Box(c.x - r, c.y - r, c.z - r, c.x + r, c.y + r, c.z + r),
			le -> le != self && le.isAlive() && le.squaredDistanceTo(c) <= r2);
		// si hay demasiados, los más cercanos a la cámara
		if (list.size() > SCAN_XRAY_MAX) list.sort(Comparator.comparingDouble(le -> le.squaredDistanceTo(cam)));
		int n = Math.min(SCAN_XRAY_MAX, list.size());
		for (int i = 0; i < n; i++) {
			LivingEntity being = list.get(i);
			XRayFx.anatomy(b, being, ROOM, a, HEART, 0.35f, now);
			XRayFx.heldMarker(b, being, GOLD, a, now);
		}
	}

	static void drawEvent(BufferBuilder b, MinecraftClient mc, Ev e, double t, double age, double now, boolean calm) {
		switch (e.kind) {
			case LawFx.RADIO_KNIFE -> radioKnife(b, e, t, now, calm);
			case LawFx.INJECTION_DASH -> injectionDash(b, mc, e, t, now, calm);
			case LawFx.INJECTION_HIT -> injectionHit(b, mc, e, t, now, calm);
			case LawFx.GAMMA -> gamma(b, mc, e, t, now, calm);
			case LawFx.CURTAIN -> curtain(b, mc, e, t, age, now, calm);
			case LawFx.MES -> mes(b, mc, e, t, now);
			case LawFx.COUNTER_SHOCK -> counterShock(b, mc, e, t, now, calm);
			case LawFx.SCAN -> scan(b, e, t, now);
			case LawFx.SOULS -> souls(b, e, t, now);
			case LawFx.RAIN_SLASH -> rainSlash(b, e, t);
			case LawFx.AWAKEN -> awaken(b, mc, e, t, now, calm);
			case LawFx.KROOM -> kroom(b, mc, e, t, now);
			case LawFx.ANESTHESIA -> anesthesia(b, mc, e, t, now, calm);
			case LawFx.SHOCK_WILLE -> shockWille(b, e, t, now, calm);
			case LawFx.PUNCTURE -> puncture(b, e, t, now, calm);
			case LawFx.SILENT -> silent(b, mc, e, t, age, now);
			case LawFx.SWORD -> sword(b, e, t, now);
			case LawFx.RROOM_CAST -> rroomCast(b, e, t, now);
			case LawFx.RROOM_BURST -> rroomBurst(b, e, t, now);
			case LawFx.STERBEN -> sterben(b, e, t, now);
			case LawFx.HEART_SQUEEZE -> heartSqueeze(b, mc, e, t, now);
			case LawFx.SILENT_TARGET, LawFx.SCAN_STEAL, LawFx.EXTRACT -> LawFxExtra.draw(b, mc, e, t, age, now, calm);
			default -> {}
		}
	}

	static void drawInside(BufferBuilder b, MinecraftClient mc, Ev e, double t, double now, boolean calm) {
		switch (e.kind) {
			case LawFx.GAMMA -> {
				Entity target = body(mc, e.targetId);
				if (target == null) return;
				GammaPose g = gammaPose(mc, e, t);
				// la radiografía se enciende cuando la hoja toca la piel y se apaga con ella
				float a = (float) (MathHelper.clamp((t - g.contact + 0.05) / 0.12, 0.0, 1.0) * (t < 0.8 ? 1.0 : (1.0 - t) / 0.2));
				if (a <= 0.01f) return;
				Box box = lerpBox(target);
				Vec3d c = box.getCenter();
				double u = MathHelper.clamp((t - 0.3) / 0.6, 0.0, 1.0);   // 0 al clavarse la hoja, 1 con los órganos deshechos
				float glow = (float) MathHelper.clamp((t - 0.15) / 0.6, 0.0, 1.0);
				// el esqueleto en verde y los órganos, que se encienden y viran a amarillo al destruirse
				int col = lerpRgb(GAMMA_G, GAMMA_Y, (float) u);
				XRayFx.anatomy(b, target, GAMMA_G, a * 0.85f, col, glow, now);
				// con la hoja ya dentro, los órganos revientan sin herida por fuera
				if (t >= 0.3) XRayFx.rupture(b, target, GAMMA_Y, (float) u, e.seed, calm);
				// la parte de la hoja que queda dentro del cuerpo
				if (g.tipDist > g.skin + 0.02) {
					Vec3d in = g.hand.add(g.dir.multiply(Math.max(g.skin, g.tipDist - g.len)));
					ribbon(b, in, g.tip, 0.06, 0.012, col, a * 0.45f, a * 0.6f);
					ribbon(b, in, g.tip, 0.02, 0.004, CORE, a * 0.7f, a * 0.8f);
				}
				// el núcleo que late y las ondas que convergen hacia dentro (radiación concentrada)
				double beat = 0.5 + 0.5 * Math.sin(now * 18.0);
				disc(b, c, 0.10 + 0.05 * beat, CORE, a * 0.8f, 0.15f * a);
				for (int k = 0; k < 3; k++) {
					double ph = ((Math.max(0.0, t - 0.1) * 2.4 + k / 3.0) % 1.0);
					double rr = (1.0 - ph) * box.getLengthY() * 0.65 + 0.08;
					faceCam(c);
					ring(b, c, rr * 0.86, rr, col, a * 0.4f * (float) ph, 0f);
				}
			}
			case LawFx.SHOCK_WILLE -> {
				Entity target = body(mc, e.targetId);
				if (target == null || t < 0.22 || t > 0.75) return;
				double u = (t - 0.22) / 0.53;
				Box box = lerpBox(target);
				Vec3d c = box.getCenter();
				float a = (float) Math.sin(u * Math.PI);
				// la onda eléctrica estalla dentro: el esqueleto y los órganos en blanco eléctrico, que revientan
				XRayFx.anatomy(b, target, ELEC, a * 0.9f, ELEC_HOT, (float) Math.min(1.0, u * 2.0), now);
				XRayFx.rupture(b, target, ELEC_HOT, (float) u, e.seed, calm);
				disc(b, c, box.getLengthY() * (0.35 + 0.5 * u), ELEC_HOT, a * 0.6f, 0f);
				if (!calm) {
					int flick = (int) (now * 30);
					for (int k = 0; k < 5; k++) {
						Vec3d q = pointOnBox(box, e.seed + k * 97 + flick * 13);
						bolt(b, c, q, 0.06, 0.02, ELEC, ELEC_HOT, a, e.seed + k * 31 + flick, 6, 0.18);
					}
				}
			}
			case LawFx.COUNTER_SHOCK -> {
				// la radiografía tenue del cuerpo, con el corazón que se enciende con cada descarga («ba-dum»)
				Entity target = body(mc, e.targetId);
				if (target == null || t >= 0.6) return;
				float a = (float) (Math.min(1.0, t / 0.05) * (t < 0.45 ? 1.0 : (0.6 - t) / 0.15)) * 0.4f;
				double glow;
				if (calm) {
					glow = 0.7 * (1.0 - t / 0.6);   // sin parpadeo: se apaga despacio
				} else {
					glow = 0;
					for (int i = 0; i < 2; i++) {
						double dt = t - i * 0.18;
						if (dt >= 0) glow = Math.max(glow, Math.exp(-dt * 12.0));
					}
				}
				XRayFx.anatomy(b, target, ELEC, a, HEART, (float) glow, now);
			}
			case LawFx.ANESTHESIA -> {
				Entity target = entity(mc, e.targetId);
				if (target == null) return;
				Box box = lerpBox(target);
				float a = (float) (0.5 * (1.0 - t) + 0.25);
				boxEdges(b, box.expand(0.04), ROOM, a * 0.55f, 0.02);
				Vec3d c = box.getCenter();
				disc(b, c, 0.18 + 0.06 * Math.sin(now * 9.0), CORE, a * 0.6f, 0f);
			}
			default -> {}
		}
	}

	// ------------------------------------------------------------------ cada técnica

	/** Radio Knife: tres tajos cruzados y electrificados en el sitio, con la descarga saltando por las costuras. */
	static void radioKnife(BufferBuilder b, Ev e, double t, double now, boolean calm) {
		Vec3d dir = e.b.subtract(e.a);
		if (dir.lengthSquared() < 1e-4) dir = new Vec3d(0, 0, 1);
		dir = dir.normalize();
		Vec3d right = dir.crossProduct(new Vec3d(0, 1, 0));
		if (right.lengthSquared() < 1e-4) right = new Vec3d(1, 0, 0);
		right = right.normalize();
		Vec3d up = right.crossProduct(dir).normalize();
		double r = Math.max(2.5, e.radius);
		Vec3d[] normals = {up, up.add(right).normalize(), up.subtract(right).normalize()};
		// 1) el arco de la hoja desde la mano (los primeros instantes)
		if (t < 0.3) {
			float a = (float) (1.0 - t / 0.3);
			ribbon(b, e.a, e.b, 0.10, 0.22, ELEC, a * 0.6f, a * 0.25f);
			ribbon(b, e.a, e.b, 0.03, 0.06, ELEC_HOT, a, a * 0.6f);
		}
		// 2) tres hojas de corte que barren su plano, una tras otra
		int flick = (int) (now * 26);
		for (int i = 0; i < 3; i++) {
			double local = MathHelper.clamp((t - i * 0.07) / 0.55, 0.0, 1.0);
			if (local <= 0 || local >= 1) continue;
			Vec3d n = normals[i];
			Vec3d s = n.crossProduct(dir).normalize();   // la dirección del tajo dentro de su plano
			double sweep = Math.min(1.0, local * 3.2);
			float a = (float) ((1.0 - local) * (1.0 - local));
			Vec3d p0 = e.b.subtract(s.multiply(r * sweep)), p1 = e.b.add(s.multiply(r * sweep));
			// la hoja de luz: un abanico fino en el plano del corte
			fan(b, e.b, s, dir, r * sweep, 0.55, ROOM, a * 0.22f);
			// la costura brillante
			ribbon(b, p0, p1, 0.16 * (1.0 - local * 0.6), 0.16 * (1.0 - local * 0.6), ELEC, a * 0.75f, a * 0.75f);
			ribbon(b, p0, p1, 0.045, 0.045, ELEC_HOT, a, a);
			// la descarga saltando por la costura
			if (!calm && local < 0.7) {
				bolt(b, p0, p1, 0.09, 0.03, ELEC, ELEC_HOT, a, e.seed + i * 53 + flick * 7, 12, 0.28);
			}
		}
		// 3) chispazos radiales y anillo de choque
		double wave = r * (0.3 + t * 1.2);
		float wa = (float) (0.85 * (1.0 - t) * (1.0 - t));
		axes(dir);
		ring(b, e.b, wave * 0.82, wave, ELEC, wa, 0f);
		ring(b, e.b, wave * 0.6, wave * 0.66, ELEC_HOT, wa * 0.6f, 0f);
		if (!calm && t < 0.5) {
			for (int k = 0; k < 7; k++) {
				Vec3d d = randDir(e.seed + k * 211 + flick);
				bolt(b, e.b, e.b.add(d.multiply(r * (0.5 + 0.5 * hash01(e.seed + k)))), 0.07, 0.022, ELEC, ELEC_HOT,
					(float) (1.0 - t / 0.5), e.seed + k * 7 + flick * 3, 7, 0.35);
			}
		}
	}

	/** Injection Shot: la retícula sobre el objetivo y la punta de lanza de luz delante de Law mientras embiste. */
	static void injectionDash(BufferBuilder b, MinecraftClient mc, Ev e, double t, double now, boolean calm) {
		Entity user = entity(mc, e.userId);
		Vec3d dir = e.b.subtract(e.a);
		if (dir.lengthSquared() < 1e-4) dir = new Vec3d(0, 0, 1);
		dir = dir.normalize();
		float a = (float) (1.0 - t * t);
		if (user != null) {
			Vec3d tip0 = lerpPos(user).add(0, user.getHeight() * 0.62, 0).add(dir.multiply(0.6));
			Vec3d tip = tip0.add(dir.multiply(2.6));
			// la «aguja»: anillos que se estrechan hacia la punta y giran como un taladro
			axes(dir);
			for (int i = 0; i < 6; i++) {
				double u = i / 5.0;
				Vec3d c = tip0.add(dir.multiply(2.6 * u));
				double rr = 0.55 * (1.0 - u) + 0.05;
				ring(b, c, rr * 0.7, rr, ROOM, a * 0.55f * (float) (1.0 - u * 0.5), 0f);
			}
			ribbon(b, tip0, tip, 0.30, 0.02, ROOM, a * 0.6f, a);
			ribbon(b, tip0, tip, 0.09, 0.01, CORE, a, a);
			// líneas de velocidad detrás
			if (!calm) {
				for (int k = 0; k < 6; k++) {
					double ang = k * Math.PI / 3 + now * 6;
					Vec3d off = new Vec3d(u1x * Math.cos(ang) + u2x * Math.sin(ang), u1y * Math.cos(ang) + u2y * Math.sin(ang),
						u1z * Math.cos(ang) + u2z * Math.sin(ang)).multiply(0.6);
					Vec3d s0 = tip0.add(off), s1 = s0.subtract(dir.multiply(2.5 + hash01(e.seed + k) * 2));
					ribbon(b, s0, s1, 0.03, 0.0, CORE, a * 0.7f, 0f);
				}
			}
		}
		// la retícula sobre el objetivo (como la del Scan)
		Entity target = entity(mc, e.targetId);
		if (target != null) reticle(b, lerpBox(target).getCenter(), Math.max(0.7, target.getHeight() * 0.6), now, a);
	}

	/**
	 * Injection Shot: al clavarse la hoja «dispara» como un arma: un fogonazo grande en el punto de contacto y el
	 * disparo que SALE POR LA ESPALDA del objetivo (un cono de luz y cuatro anillos de choque a lo largo de la
	 * embestida). Va pegado al objetivo, que sale despedido, para que se lea también desde los ojos de Law.
	 */
	static void injectionHit(BufferBuilder b, MinecraftClient mc, Ev e, double t, double now, boolean calm) {
		Vec3d dir = e.b.subtract(e.a);
		if (dir.lengthSquared() < 1e-4) dir = new Vec3d(0, 0, 1);
		dir = dir.normalize();
		float a = (float) ((1.0 - t) * (1.0 - t));
		float fl = flashScale();
		// el objetivo aunque esté muriendo (sigue en el mundo durante la animación de muerte)
		Entity target = body(mc, e.targetId);
		Vec3d c = target != null ? lerpBox(target).getCenter() : e.a;
		double half = target != null ? Math.max(0.2, target.getWidth() * 0.5) : 0.35;
		Vec3d contact = c.subtract(dir.multiply(half + 0.08));
		// 1) el fogonazo en el punto de contacto: grande y blanco
		if (t < 0.2) {
			float f = (float) (1.0 - t / 0.2);
			disc(b, contact, 0.45 + 1.5 * f, CORE, f * fl, 0f);
			disc(b, contact, 1.1 + 2.2 * f, ROOM, f * 0.4f * fl, 0f);
			axes(dir);
			double rr = 0.35 + (1.0 - f) * 1.6;
			ring(b, contact, rr * 0.8, rr, CORE, f * 0.9f * fl, 0f);
		}
		// 2) el disparo sale por la espalda: un cono de luz a lo largo de la embestida, desde 0,8 detrás del centro
		Vec3d back = c.add(dir.multiply(0.8));
		if (t < 0.7) {
			double g = Math.min(1.0, t / 0.3);
			double ease = 1.0 - (1.0 - g) * (1.0 - g);
			double reach = 0.6 + 6.4 * ease;
			double r1 = 0.6 + 2.2 * ease;
			float ca = (float) (t < 0.25 ? 1.0 : Math.max(0.0, 1.0 - (t - 0.25) / 0.45)) * 0.5f;
			cone(b, back, dir, reach, 0.3, r1, ROOM, ca, 0f);
			cone(b, back, dir, reach * 0.8, 0.15, r1 * 0.35, CORE, ca * 0.9f, 0f);
			ribbon(b, back, back.add(dir.multiply(reach)), 0.16, 0.02, CORE, Math.min(1f, ca * 1.6f), 0f);
		}
		// 3) el chorro: rayas que salen hacia delante desde la espalda (fijas por semilla, sin parpadeo)
		if (t < 0.25) {
			float f = (float) (1.0 - t / 0.25);
			axes(dir);
			double ex = u1x, ey = u1y, ez = u1z, fx = u2x, fy = u2y, fz = u2z;
			for (int k = 0; k < 10; k++) {
				double ang = k * Math.PI * 2 / 10 + e.seed;
				double ca = Math.cos(ang), sa = Math.sin(ang);
				Vec3d side = new Vec3d(ex * ca + fx * sa, ey * ca + fy * sa, ez * ca + fz * sa);
				Vec3d tip = back.add(dir.multiply(3.5 + 1.5 * hash01(e.seed + k))).add(side.multiply(1.1));
				ribbon(b, back, tip, 0.12, 0.0, ROOM, f * 0.9f, 0f);
			}
		}
		// 4) cuatro anillos de choque perpendiculares al disparo: nacen detrás del objetivo y se alejan hasta ~7 bloques
		for (int i = 0; i < 4; i++) {
			double v = MathHelper.clamp((t - i * 0.07) / 0.55, 0.0, 1.0);
			if (v <= 0 || v >= 1) continue;
			double ease = 1.0 - (1.0 - v) * (1.0 - v);
			Vec3d rc = c.add(dir.multiply(0.8 + 6.2 * ease));
			double rr = 0.8 + 2.2 * ease;
			float ra = (float) ((1.0 - v) * 0.95);
			axes(dir);
			ring(b, rc, rr * 0.78, rr, ROOM, ra, 0f);
			ring(b, rc, rr * 0.9, rr * 0.96, CORE, ra, 0f);
		}
		// 5) la onda horizontal en el sitio del golpe
		axes(new Vec3d(0, 1, 0));
		double wave = 0.6 + t * 4.5;
		ring(b, e.a, wave * 0.85, wave, ELEC, a * 0.6f, 0f);
	}

	/** Un cono hueco de luz a lo largo de dir: radio r0 en c y r1 a la distancia len (alfa a0 → a1). */
	static void cone(BufferBuilder b, Vec3d c, Vec3d dir, double len, double r0, double r1, int rgb, float a0, float a1) {
		if (a0 <= 0.003f && a1 <= 0.003f) return;
		axes(dir);
		Vec3d f = c.add(dir.multiply(len));
		int n = 20;
		for (int i = 0; i < n; i++) {
			int i0 = i * SEG / n, i1 = (i + 1) * SEG / n;
			double c0 = COS[i0], s0 = SIN[i0], c1 = COS[i1], s1 = SIN[i1];
			double x0 = u1x * c0 + u2x * s0, y0 = u1y * c0 + u2y * s0, z0 = u1z * c0 + u2z * s0;
			double x1 = u1x * c1 + u2x * s1, y1 = u1y * c1 + u2y * s1, z1 = u1z * c1 + u2z * s1;
			vtx(b, c.x + x0 * r0, c.y + y0 * r0, c.z + z0 * r0, rgb, a0);
			vtx(b, c.x + x1 * r0, c.y + y1 * r0, c.z + z1 * r0, rgb, a0);
			vtx(b, f.x + x1 * r1, f.y + y1 * r1, f.z + z1 * r1, rgb, a1);
			vtx(b, f.x + x0 * r1, f.y + y0 * r1, f.z + z0 * r1, rgb, a1);
		}
	}

	/** Gamma Knife en el instante t: la mano, el centro del objetivo, la dirección, la piel, la hoja y cuándo entra. */
	record GammaPose(Vec3d hand, Vec3d center, Vec3d dir, double dist, double skin, double len, double tipDist,
					 Vec3d hilt, Vec3d tip, double contact) {}

	/**
	 * Dónde está la hoja de Gamma Knife: se forma en la mano (t < 0,12), se lanza por la línea mano → objetivo
	 * (0,12–0,3) y se queda clavada con la punta 0,35 bloques más allá del centro, con parte de la hoja aún fuera.
	 */
	static GammaPose gammaPose(MinecraftClient mc, Ev e, double t) {
		Entity target = body(mc, e.targetId);   // aunque muera, la hoja sigue clavada mientras cae
		Entity user = entity(mc, e.userId);
		Vec3d to = target != null ? lerpBox(target).getCenter() : e.b;
		Vec3d from = user != null ? handOf(user) : e.a;
		Vec3d d = to.subtract(from);
		double dist = d.length();
		Vec3d dn = dist > 1e-3 ? d.multiply(1.0 / dist) : new Vec3d(0, 0, 1);
		double skin = Math.max(0.0, dist - (target != null ? target.getWidth() * 0.5 : 0.3));
		// la punta acaba 0,35 más allá del centro, pero con algo de hoja fuera aunque el ser sea muy ancho
		double end = Math.max(GAMMA_LEN + 0.05, Math.min(dist + 0.35, skin + 0.65 * GAMMA_LEN));
		double len = GAMMA_LEN, tipDist;
		if (t < 0.12) {
			double f = t / 0.12;
			len = GAMMA_LEN * f * f * (3 - 2 * f);
			tipDist = len;
		} else if (t < 0.3) {
			// la estocada: rápida al salir, frena al clavarse
			double u = (t - 0.12) / 0.18;
			tipDist = GAMMA_LEN + (end - GAMMA_LEN) * (1.0 - (1.0 - u) * (1.0 - u));
		} else {
			tipDist = end;
		}
		// cuándo toca la punta la piel (para el anillo de entrada y la radiografía)
		double contact;
		if (skin <= GAMMA_LEN) {
			contact = 0.12 * skin / GAMMA_LEN;
		} else {
			double frac = MathHelper.clamp((skin - GAMMA_LEN) / Math.max(1e-3, end - GAMMA_LEN), 0.0, 1.0);
			contact = 0.12 + 0.18 * (1.0 - Math.sqrt(1.0 - frac));
		}
		Vec3d tip = from.add(dn.multiply(tipDist));
		Vec3d hilt = from.add(dn.multiply(Math.max(0.0, tipDist - len)));
		return new GammaPose(from, to, dn, dist, skin, len, tipDist, hilt, tip, contact);
	}

	/**
	 * Gamma Knife: una hoja CORTA de energía verde pálida (un bloque) se forma en la mano, se lanza y se clava en el
	 * cuerpo, y allí late hasta apagarse; lo de dentro (los órganos que se deshacen) va en {@link #drawInside}. Si el
	 * objetivo está lejos (el ROOM le da el alcance), solo una guía discontinua tenue une la mano con él.
	 */
	static void gamma(BufferBuilder b, MinecraftClient mc, Ev e, double t, double now, boolean calm) {
		GammaPose g = gammaPose(mc, e, t);
		float a = t < 0.75 ? 1f : (float) (1.0 - (t - 0.75) / 0.25);
		int col = lerpRgb(GAMMA_G, CORE, 0.3f);   // verde pálido
		// clavada, la hoja late
		float glow = t < 0.3 ? 1f : (float) (calm ? 0.85 : 0.8 + 0.2 * Math.sin(now * 16.0));
		// 1) objetivo lejos: una guía discontinua tenue, celeste, entre la mano y la piel
		if (g.dist > 1.6) {
			float ga = a * 0.22f * (float) Math.min(1.0, t / 0.08);
			dashed(b, g.hand, g.hand.add(g.dir.multiply(g.skin)), 0.22, 0.16, 0.018, ROOM, ga, calm ? 0.0 : now * 0.9);
		}
		// 2) el resplandor en la mano mientras se forma la hoja
		if (t < 0.4) {
			float ha = a * (float) (t < 0.12 ? t / 0.12 : Math.max(0.0, 1.0 - (t - 0.12) / 0.28));
			disc(b, g.hand, 0.2 + 0.05 * Math.sin(now * 20), col, ha * 0.7f, 0f);
		}
		// 3) la estela de la estocada, detrás de la empuñadura
		if (t > 0.12 && t < 0.36) {
			float sa = a * 0.3f * (float) (t < 0.3 ? 1.0 : (0.36 - t) / 0.06);
			double hd = Math.max(0.0, g.tipDist - g.len);
			if (hd > 0.05) {
				Vec3d tail = g.hand.add(g.dir.multiply(Math.max(0.0, hd - 1.5)));
				ribbon(b, tail, g.hilt, 0.0, 0.06, col, 0f, sa);
			}
		}
		// 4) la hoja: halo suave, cuerpo verde pálido y filo blanco; ancha (0,16) en la empuñadura, fina en la punta
		if (g.len > 0.02) {
			ribbon(b, g.hilt, g.tip, 0.17, 0.05, col, a * 0.16f * glow, a * 0.22f * glow);
			ribbon(b, g.hilt, g.tip, 0.08, 0.014, col, a * 0.7f * glow, a * 0.9f);
			ribbon(b, g.hilt, g.tip, 0.028, 0.005, CORE, a, a);
			disc(b, g.hilt, 0.13, col, a * 0.45f * glow, 0f);
		}
		// 5) la entrada: un anillo pequeño en la piel, sin herida, y un chispazo al tocarla
		if (t > g.contact) {
			double k = t - g.contact;
			Vec3d entry = g.hand.add(g.dir.multiply(g.skin));
			axes(g.dir);
			double rr = 0.14 + 0.26 * Math.min(1.0, k * 5);
			ring(b, entry, rr * 0.7, rr, col, a * 0.8f, 0f);
			if (k < 0.1) disc(b, entry, 0.12 + 0.35 * (1.0 - k / 0.1), CORE, a * (float) (1.0 - k / 0.1), 0f);
		}
	}

	/** Una línea discontinua de p0 a p1 (trazos de dash con huecos de gap) que avanza con shift. */
	static void dashed(BufferBuilder b, Vec3d p0, Vec3d p1, double dash, double gap, double w, int rgb, float a, double shift) {
		Vec3d d = p1.subtract(p0);
		double len = d.length();
		if (len < 1e-3 || a <= 0.003f) return;
		Vec3d dn = d.multiply(1.0 / len);
		double period = dash + gap;
		double off = shift % period;
		if (off < 0) off += period;
		int n = 0;
		for (double s = off - period; s < len && n < 200; s += period, n++) {
			double s0 = Math.max(0.0, s), s1 = Math.min(len, s + dash);
			if (s1 - s0 < 0.02) continue;
			ribbon(b, p0.add(dn.multiply(s0)), p0.add(dn.multiply(s1)), w, w, rgb, a, a);
		}
	}

	/**
	 * Curtain: el paño quirúrgico que Law tira del aire con el brazo: un velo translúcido y suave delante de él, con
	 * tres pliegues apenas marcados, el borde de arriba y el dobladillo claros y un brillo lento que lo cruza.
	 */
	static void curtain(BufferBuilder b, MinecraftClient mc, Ev e, double t, double age, double now, boolean calm) {
		Entity user = entity(mc, e.userId);
		if (user == null) return;
		float yaw = user.getYaw(td);
		double rad = Math.toRadians(yaw);
		Vec3d fwd = new Vec3d(-Math.sin(rad), 0, Math.cos(rad));
		Vec3d side = new Vec3d(-fwd.z, 0, fwd.x);
		Vec3d base = lerpPos(user).add(fwd.multiply(1.35));
		double unfurl = Math.min(1.0, age / 0.38);
		double out = e.duration - age < 0.5 ? (e.duration - age) / 0.5 : 1.0;
		float a = (float) (Math.max(0, out));
		if (a <= 0.003f) return;
		double width = 2.8, height = 2.5;
		int cols = 16, rows = 8;
		double half = width * 0.5;
		double startX = -half + width * (1.0 - unfurl);   // se despliega desde el lado del brazo
		// el brillo que cruza el paño despacio, de un lado a otro (no con «menos movimiento»)
		double sheen = calm ? Double.NaN : -half - 0.8 + (width + 1.6) * ((now * 0.22) % 1.0);
		// 1) el paño: muy translúcido (alfa 0,06–0,12), algo más denso arriba; el alfa va por vértice, sin escalones
		for (int i = 0; i < cols; i++) {
			double x0 = -half + width * i / cols, x1 = -half + width * (i + 1) / cols;
			if (x1 < startX) continue;
			x0 = Math.max(x0, startX);
			for (int j = 0; j < rows; j++) {
				double y0 = height * j / rows, y1 = height * (j + 1) / rows;
				Vec3d p00 = drape(base, fwd, side, x0, y0, now, calm);
				Vec3d p10 = drape(base, fwd, side, x1, y0, now, calm);
				Vec3d p11 = drape(base, fwd, side, x1, y1, now, calm);
				Vec3d p01 = drape(base, fwd, side, x0, y1, now, calm);
				quad(b, p00, p10, p11, p01, DRAPE,
					a * veil(x0, y0, height, now, calm, sheen), a * veil(x1, y0, height, now, calm, sheen),
					a * veil(x1, y1, height, now, calm, sheen), a * veil(x0, y1, height, now, calm, sheen));
			}
		}
		// 2) solo tres pliegues, muy tenues (alfa ≤ 0,15), que siguen la tela
		for (int i = 1; i <= 3; i++) {
			double x = -half + width * i / 4.0;
			if (x < startX) continue;
			Vec3d prev = null;
			for (int j = 0; j <= rows; j++) {
				double y = 0.08 + (height - 0.16) * j / rows;
				Vec3d q = drape(base, fwd, side, x, y, now, calm);
				if (prev != null) {
					float f0 = a * (float) (0.05 + 0.10 * (j - 1) / rows), f1 = a * (float) (0.05 + 0.10 * j / rows);
					ribbon(b, prev, q, 0.04, 0.04, CORE, f0, f1);
				}
				prev = q;
			}
		}
		// 3) el borde de arriba y el dobladillo: una línea clara con un halo suave
		Vec3d prevTop = null, prevBot = null;
		for (int i = 0; i <= cols; i++) {
			double x = Math.max(startX, -half + width * i / cols);
			Vec3d top = drape(base, fwd, side, x, height, now, calm), bot = drape(base, fwd, side, x, 0.05, now, calm);
			if (prevTop != null) {
				ribbon(b, prevTop, top, 0.09, 0.09, DRAPE, a * 0.12f, a * 0.12f);
				ribbon(b, prevTop, top, 0.022, 0.022, CORE, a * 0.55f, a * 0.55f);
				ribbon(b, prevBot, bot, 0.08, 0.08, DRAPE, a * 0.10f, a * 0.10f);
				ribbon(b, prevBot, bot, 0.018, 0.018, ROOM, a * 0.45f, a * 0.45f);
			}
			prevTop = top;
			prevBot = bot;
		}
		// los lados, apenas un halo (nada de barras brillantes)
		for (double x : new double[]{startX, half}) {
			Vec3d p0 = drape(base, fwd, side, x, 0.05, now, calm), p1 = drape(base, fwd, side, x, height, now, calm);
			ribbon(b, p0, p1, 0.05, 0.05, DRAPE, a * 0.08f, a * 0.12f);
		}
		// la cruz quirúrgica en el centro del paño, tenue (cuando el paño ya llega hasta ella)
		if (startX <= -0.35) {
			Vec3d cc = drape(base, fwd, side, 0, height * 0.55, now, calm);
			Vec3d hx = side.multiply(0.32), hy = new Vec3d(0, 0.32, 0);
			ribbon(b, cc.subtract(hx), cc.add(hx), 0.05, 0.05, ROOM, a * 0.25f, a * 0.25f);
			ribbon(b, cc.subtract(hy), cc.add(hy), 0.05, 0.05, ROOM, a * 0.25f, a * 0.25f);
		}
	}

	/** Lo opaco que es el velo en (x, y): entre 0,06 y 0,12, más denso arriba, más el brillo que lo cruza. */
	static float veil(double x, double y, double height, double now, boolean calm, double sheen) {
		double fold = 0.5 + 0.5 * Math.cos(x * 3.4 + now * (calm ? 0 : 1.7));   // los pliegues de drape()
		double v = (0.075 + 0.04 * fold) * (0.8 + 0.2 * y / height);
		if (!Double.isNaN(sheen)) {
			double dx = (x + 0.25 * (y - height * 0.5) - sheen) / 0.35;   // un poco inclinado, como la luz en la tela
			v += 0.05 * Math.exp(-dx * dx);
		}
		return (float) v;
	}

	static Vec3d drape(Vec3d base, Vec3d fwd, Vec3d side, double x, double y, double now, boolean calm) {
		double wave = calm ? 0.0 : Math.sin(x * 3.4 + now * 1.7) * 0.10 + Math.sin(y * 2.1 + now * 2.3 + x) * 0.05;
		double bow = -0.18 * (x * x);   // un poco curvado alrededor de Law
		return base.add(side.multiply(x)).add(fwd.multiply(wave + bow)).add(0, y, 0);
	}

	/** Mes: la palma golpea el pecho, el corazón sale en un cubo de cristal y vuela a la mano de Law. */
	static void mes(BufferBuilder b, MinecraftClient mc, Ev e, double t, double now) {
		Entity target = entity(mc, e.targetId);
		Entity user = entity(mc, e.userId);
		Vec3d chest = target != null ? lerpPos(target).add(0, target.getHeight() * 0.66, 0) : e.b;
		Vec3d hand = user != null ? handOf(user) : e.a;
		// 1) la onda del golpe de palma
		if (t < 0.35) {
			double u = t / 0.35;
			float a = (float) (1.0 - u);
			Vec3d dn = chest.subtract(hand);
			axes(dn.lengthSquared() > 1e-4 ? dn : new Vec3d(0, 0, 1));
			ring(b, chest, 0.2 + u * 1.2, 0.3 + u * 1.4, ROOM, a * 0.8f, 0f);
			ring(b, chest, 0.1 + u * 0.6, 0.15 + u * 0.7, CORE, a, 0f);
		}
		// 2) el hueco cúbico en el pecho
		if (t < 0.8) {
			float a = (float) (t < 0.1 ? t / 0.1 : 1.0 - (t - 0.1) / 0.7);
			cubeEdges(b, chest, 0.24, ROOM, a * 0.9f, 0.025, 0);
		}
		// 3) el cubo de cristal con el corazón dentro, en arco hasta la mano
		if (t > 0.15) {
			double u = MathHelper.clamp((t - 0.15) / 0.55, 0, 1);
			double ease = u * u * (3 - 2 * u);
			Vec3d mid = chest.lerp(hand, 0.5).add(0, 0.9, 0);
			Vec3d pos = bezier(chest, mid, hand, ease);
			float a = (float) (u < 0.95 ? 1.0 : (1.0 - u) / 0.05) * (t > 0.85 ? (float) ((1.0 - t) / 0.15) : 1f);
			double spin = now * 3.0;
			cubeFaces(b, pos, 0.22, ROOM, a * 0.12f, spin);
			cubeEdges(b, pos, 0.22, CORE, a * 0.85f, 0.018, spin);
			double beat = Math.pow(Math.max(0, Math.sin(now * 7.5)), 6);
			disc(b, pos, 0.09 + 0.05 * beat, HEART, a * 0.95f, 0f);
			disc(b, pos, 0.2 + 0.08 * beat, HEART, a * 0.35f, 0f);
			// la estela
			for (int k = 1; k <= 6; k++) {
				double uk = Math.max(0, ease - k * 0.04);
				Vec3d q = bezier(chest, mid, hand, uk);
				disc(b, q, 0.12 * (1 - k / 7.0), ROOM, a * 0.3f * (1f - k / 7f), 0f);
			}
		}
	}

	/** Counter Shock: dos palas de desfibrilador en las palmas, la descarga salta al cuerpo y lo recorre entero. */
	static void counterShock(BufferBuilder b, MinecraftClient mc, Ev e, double t, double now, boolean calm) {
		Entity user = entity(mc, e.userId);
		Entity target = entity(mc, e.targetId);
		Vec3d look = e.b.subtract(e.a);
		if (look.lengthSquared() < 1e-4) look = new Vec3d(0, 0, 1);
		look = look.normalize();
		Vec3d center = user != null ? lerpPos(user).add(0, user.getHeight() * 0.62, 0).add(look.multiply(0.7)) : e.a;
		Vec3d right = look.crossProduct(new Vec3d(0, 1, 0));
		if (right.lengthSquared() < 1e-4) right = new Vec3d(1, 0, 0);
		right = right.normalize();
		Vec3d padL = center.subtract(right.multiply(0.28)), padR = center.add(right.multiply(0.28));
		float a = (float) (t < 0.6 ? 1.0 : (1.0 - t) / 0.4);
		int flick = calm ? 0 : (int) (now * 28);
		// las palas en las manos
		axes(look);
		for (Vec3d pad : new Vec3d[]{padL, padR}) {
			ring(b, pad, 0.10, 0.20, ELEC, a * 0.9f, a * 0.2f);
			disc(b, pad, 0.16 + 0.05 * Math.sin(now * 40), ELEC_HOT, a, 0f);
		}
		Box box = target != null ? lerpBox(target) : null;
		Vec3d tc = box != null ? box.getCenter() : e.b;
		// la descarga de las palas al cuerpo
		if (t < 0.55) {
			for (int k = 0; k < 3; k++) {
				bolt(b, padL, tc, 0.08, 0.025, ELEC, ELEC_HOT, a, e.seed + k * 17 + flick * 5, 9, 0.22);
				bolt(b, padR, tc, 0.08, 0.025, ELEC, ELEC_HOT, a, e.seed + k * 29 + flick * 3, 9, 0.22);
			}
		}
		// el cuerpo se ilumina y la descarga lo recorre por fuera
		if (box != null) {
			boxEdges(b, box.expand(0.04), ELEC, a * 0.5f, 0.02);
			if (!calm) {
				for (int k = 0; k < 6; k++) {
					Vec3d p0 = pointOnBox(box, e.seed + k * 13 + flick * 7), p1 = pointOnBox(box, e.seed + k * 41 + flick * 11);
					bolt(b, p0, p1, 0.06, 0.02, ELEC, ELEC_HOT, a * 0.9f, e.seed + k + flick, 6, 0.15);
				}
			}
		}
		// el doble latido «ba-dum»: dos anillos que se abren desde el pecho
		axes(new Vec3d(0, 1, 0));
		for (int i = 0; i < 2; i++) {
			double u = MathHelper.clamp((t - i * 0.18) / 0.5, 0, 1);
			if (u <= 0 || u >= 1) continue;
			double rr = 0.4 + u * 3.2;
			ring(b, tc, rr * 0.86, rr, ELEC, (float) (1 - u) * 0.85f, 0f);
		}
	}

	/** Scan: un plano de resonancia recorre el ROOM de abajo arriba, y un meridiano gira alrededor. */
	static void scan(BufferBuilder b, Ev e, double t, double now) {
		double r = Math.max(3.0, e.radius);
		Vec3d c = e.a;
		// ×1,5 respecto a antes para que el barrido se vea también de día (vtx recorta el alfa a 1)
		float a = 1.5f * (float) (t < 0.85 ? 1.0 : (1.0 - t) / 0.15);
		double sweep = MathHelper.clamp(t / 0.85, 0, 1);
		double h = -r + 2 * r * sweep;
		double ringR = Math.sqrt(Math.max(0.0, r * r - h * h));
		Vec3d sc = c.add(0, h, 0);
		axes(new Vec3d(0, 1, 0));
		if (ringR > 0.05) {
			double edge = Math.max(0.12, ringR * 0.03);
			ring(b, sc, ringR - edge, ringR, CORE, a, a * 0.4f);
			ring(b, sc, ringR * 0.72, ringR - edge, ROOM, a * 0.12f, a * 0.6f);
			ring(b, sc.add(0, -0.6, 0), ringR * 0.9, ringR * 1.0, ROOM, 0f, a * 0.3f);   // la estela del barrido
			// una pared de luz baja alrededor del anillo: el plano del escáner se ve también de canto
			for (int i = 0; i < SEG; i++) {
				Vec3d p0 = sc.add(COS[i] * ringR, 0, SIN[i] * ringR), p1 = sc.add(COS[i + 1] * ringR, 0, SIN[i + 1] * ringR);
				quad(b, p0.add(0, -1.1, 0), p1.add(0, -1.1, 0), p1, p0, ROOM, 0f, 0f, a * 0.55f, a * 0.55f);
				quad(b, p0, p1, p1.add(0, 0.25, 0), p0.add(0, 0.25, 0), CORE, a * 0.7f, a * 0.7f, 0f, 0f);
			}
			// la rejilla del escáner dentro del disco
			for (int k = 1; k <= 4; k++) {
				double rk = ringR * k / 5.0;
				ring(b, sc, rk - 0.06, rk, ROOM, a * 0.5f, a * 0.5f);
			}
			for (int k = 0; k < 6; k++) {
				double ang = k * Math.PI / 6 + now * 0.4;
				Vec3d d = new Vec3d(Math.cos(ang), 0, Math.sin(ang)).multiply(ringR);
				ribbon(b, sc.subtract(d), sc.add(d), 0.04, 0.04, ROOM, a * 0.45f, a * 0.45f);
			}
		}
		// el meridiano que gira
		double ang = now * 2.2;
		axes(new Vec3d(Math.cos(ang), 0, Math.sin(ang)));
		ring(b, c, r * 0.985, r, ROOM, a * 0.6f, 0f);
	}

	/** Cambio de almas: dos almas (rosa y celeste) salen de cada cuerpo y se cruzan en arco hasta el otro. */
	static void souls(BufferBuilder b, Ev e, double t, double now) {
		Vec3d pa = e.a, pb = e.b;
		Vec3d mid = pa.lerp(pb, 0.5);
		Vec3d d = pb.subtract(pa);
		Vec3d side = d.crossProduct(new Vec3d(0, 1, 0));
		if (side.lengthSquared() < 1e-4) side = new Vec3d(1, 0, 0);
		side = side.normalize().multiply(Math.max(1.0, d.length() * 0.35));
		Vec3d ctrlA = mid.add(side).add(0, 1.6, 0), ctrlB = mid.subtract(side).add(0, 1.0, 0);
		double u = MathHelper.clamp(t / 0.8, 0, 1);
		double ease = u * u * (3 - 2 * u);
		float a = (float) (t < 0.85 ? 1.0 : (1.0 - t) / 0.15);
		// el hilo que une a los dos
		ribbon(b, pa, pb, 0.02, 0.02, GOLD, a * 0.35f, a * 0.35f);
		for (int s = 0; s < 2; s++) {
			Vec3d from = s == 0 ? pa : pb, to = s == 0 ? pb : pa, ctrl = s == 0 ? ctrlA : ctrlB;
			int col = s == 0 ? SOUL_A : SOUL_B;
			Vec3d pos = bezier(from, ctrl, to, ease);
			disc(b, pos, 0.32 + 0.06 * Math.sin(now * 12 + s), col, a * 0.85f, 0f);
			disc(b, pos, 0.12, CORE, a, 0f);
			Vec3d prev = pos;
			for (int k = 1; k <= 10; k++) {
				Vec3d q = bezier(from, ctrl, to, Math.max(0, ease - k * 0.035));
				ribbon(b, prev, q, 0.16 * (1 - k / 11.0), 0.14 * (1 - k / 11.0), col, a * 0.6f * (1f - k / 11f), a * 0.5f * (1f - k / 11f));
				prev = q;
			}
		}
		// al llegar: un destello en cada cuerpo
		if (t > 0.7) {
			double v = (t - 0.7) / 0.3;
			axes(new Vec3d(0, 1, 0));
			ring(b, pa, 0.3 + v * 1.5, 0.45 + v * 1.7, SOUL_B, (float) (1 - v), 0f);
			ring(b, pb, 0.3 + v * 1.5, 0.45 + v * 1.7, SOUL_A, (float) (1 - v), 0f);
		}
	}

	/** Lluvia de cortes: un tajo cae del cielo sobre el ser y lo cruza. */
	static void rainSlash(BufferBuilder b, Ev e, double t) {
		Vec3d c = e.a;
		Vec3d top = c.add(0, 7, 0);
		if (t < 0.3) {
			double u = t / 0.3;
			Vec3d head = top.lerp(c, u);
			ribbon(b, top, head, 0.01, 0.10, ROOM, 0.2f, 0.9f);
			ribbon(b, top, head, 0.0, 0.035, CORE, 0.3f, 1f);
		} else {
			double u = (t - 0.3) / 0.7;
			float a = (float) (1 - u);
			double s = 0.6 + 0.4 * u;
			double ang = e.seed;
			Vec3d d1 = new Vec3d(Math.cos(ang), 0.7, Math.sin(ang)).normalize().multiply(s);
			Vec3d d2 = new Vec3d(-Math.sin(ang), -0.5, Math.cos(ang)).normalize().multiply(s);
			ribbon(b, c.subtract(d1), c.add(d1), 0.05, 0.05, CORE, a, a);
			ribbon(b, c.subtract(d2), c.add(d2), 0.05, 0.05, CORE, a, a);
			disc(b, c, 0.35 * (1 - u), ROOM, a * 0.7f, 0f);
		}
	}

	/** Despertar: columnas de luz celeste y dorada suben en espiral alrededor de Law (o se apagan hacia dentro). */
	static void awaken(BufferBuilder b, MinecraftClient mc, Ev e, double t, double now, boolean calm) {
		Entity user = entity(mc, e.userId);
		Vec3d feet = user != null ? lerpPos(user) : e.a;
		boolean on = e.power > 0.5f;
		float a = (float) (1.0 - t) * (on ? 1f : 0.8f);
		double grow = on ? t : 1 - t;
		axes(new Vec3d(0, 1, 0));
		// anillos en el suelo
		for (int i = 0; i < 3; i++) {
			double u = MathHelper.clamp(on ? t * 1.3 - i * 0.12 : 1 - (t * 1.3 - i * 0.12), 0, 1);
			double rr = 0.6 + u * 4.5;
			ring(b, feet.add(0, 0.05 + i * 0.02, 0), rr * 0.9, rr, i == 1 ? GOLD : ROOM, a * 0.8f, 0f);
		}
		// columnas en espiral
		int n = calm ? 4 : 8;
		for (int k = 0; k < n; k++) {
			double ang = k * Math.PI * 2 / n + now * (calm ? 0 : 1.6);
			double rr = 1.1 + 0.4 * Math.sin(now * 2 + k);
			Vec3d p0 = feet.add(Math.cos(ang) * rr, 0, Math.sin(ang) * rr);
			Vec3d p1 = feet.add(Math.cos(ang + 0.8) * rr * 0.6, 2.2 + 3.5 * grow, Math.sin(ang + 0.8) * rr * 0.6);
			ribbon(b, p0, p1, 0.07, 0.0, k % 2 == 0 ? ROOM : GOLD, a * 0.85f, 0f);
		}
		disc(b, feet.add(0, 1.0, 0), 1.2 + 1.5 * grow, on ? GOLD : ROOM, a * 0.25f, 0f);
	}

	/** K-ROOM: una esfera de ROOM diminuta se forma en la palma y se desliza recubriendo la Kikoku. */
	static void kroom(BufferBuilder b, MinecraftClient mc, Ev e, double t, double now) {
		Entity user = entity(mc, e.userId);
		Vec3d hand = user != null ? handOf(user) : e.a;
		Vec3d dir = user != null ? user.getRotationVec(td) : e.b.subtract(e.a).normalize();
		float a = (float) (t < 0.8 ? 1.0 : (1.0 - t) / 0.2);
		if (t < 0.45) {
			// la esfera de ROOM en la palma, que no crece: se apaga al pasar a revestir la hoja
			double u = t / 0.45;
			double rr = 0.12 + 0.28 * Math.sin(u * Math.PI * 0.5);
			float pa = a * (float) (u < 0.8 ? 1.0 : (1.0 - u) / 0.2);
			KikokuBladeFx.palmSphere(b, hand, rr, pa, now);
			disc(b, hand, rr * 1.4, ROOM, pa * 0.3f, 0f);
		}
		// dos anillos recorren la hoja hasta la punta (la hoja gigante ya enseña los 26 m)
		if (t > 0.3) {
			double u = (t - 0.3) / 0.7;
			axes(dir);
			for (int i = 0; i < 2; i++) {
				double v = MathHelper.clamp(u * 1.4 - i * 0.12, 0, 1);
				if (v <= 0 || v >= 1) continue;
				Vec3d c = hand.add(dir.multiply(1.0 + v * 25.0));
				double rr = 0.45 + 0.25 * Math.sin(v * Math.PI);
				ring(b, c, rr * 0.75, rr, ROOM, a * (float) (1 - v) * 0.9f, 0f);
			}
		}
	}

	/** Anesthesia: la hoja de K-ROOM atraviesa al objetivo sin cortarlo; ondas de fase donde entra. */
	static void anesthesia(BufferBuilder b, MinecraftClient mc, Ev e, double t, double now, boolean calm) {
		Entity user = entity(mc, e.userId);
		Entity target = entity(mc, e.targetId);
		if (user == null || target == null) return;
		Vec3d hand = handOf(user);
		Vec3d tc = lerpBox(target).getCenter();
		Vec3d d = tc.subtract(hand);
		if (d.lengthSquared() < 1e-4) return;
		double dist = d.length();
		Vec3d dn = d.multiply(1.0 / dist);
		Vec3d past = tc.add(dn.multiply(3.0));   // la hoja sigue por detrás: lo ha atravesado
		float a = (float) (t < 0.85 ? 1.0 : (1.0 - t) / 0.15);
		// la Kikoku revestida de K-ROOM lo atraviesa entero y sale 3 bloques por detrás
		KikokuBladeFx.blade(b, hand, dn, dist + 3.0, 1.0, a * 0.9f, now, calm);
		axes(dn);
		for (int i = 0; i < 3; i++) {
			double ph = ((now * 1.4 + i / 3.0) % 1.0);
			double rr = 0.2 + ph * Math.max(0.8, target.getWidth() * 1.2);
			Vec3d entry = tc.subtract(dn.multiply(target.getWidth() * 0.5));
			ring(b, entry, rr * 0.85, rr, ROOM, a * (float) (1 - ph) * 0.8f, 0f);
		}
		// pulsos de fase que viajan por la hoja
		for (int i = 0; i < 4; i++) {
			double ph = ((now * 0.9 + i / 4.0) % 1.0);
			Vec3d q = hand.lerp(past, ph);
			ring(b, q, 0.10, 0.22, CORE, a * 0.7f, 0f);
		}
	}

	/** Shock Wille: la hoja atraviesa, un pulso viaja por ella y estalla dentro del cuerpo como una onda. */
	static void shockWille(BufferBuilder b, Ev e, double t, double now, boolean calm) {
		Vec3d a0 = e.a, p = e.b;
		Vec3d d = p.subtract(a0);
		if (d.lengthSquared() < 1e-4) d = new Vec3d(0, 0, 1);
		double dist = d.length();
		Vec3d dn = d.normalize();
		double r = Math.max(3.0, e.radius);
		int flick = calm ? 0 : (int) (now * 24);
		// 1) la Kikoku de K-ROOM lo atraviesa y asoma 2,5 bloques por detrás
		if (t < 0.55) {
			float a = (float) (t < 0.4 ? 1.0 : (0.55 - t) / 0.15);
			KikokuBladeFx.blade(b, a0, dn, dist + 2.5, 1.0, a, now, calm);
		}
		// 2) el pulso que viaja por la hoja
		if (t > 0.05 && t < 0.3) {
			double u = (t - 0.05) / 0.25;
			Vec3d q = a0.lerp(p, u * u);
			disc(b, q, 0.6, ELEC_HOT, 1f, 0f);
			axes(dn);
			ring(b, q, 0.35, 0.6, ELEC, 0.9f, 0f);
		}
		// 3) la detonación interna: núcleo, esfera que se abre, rayos y disco de choque
		if (t > 0.25) {
			double u = (t - 0.25) / 0.75;
			float a = (float) ((1 - u) * (1 - u));
			disc(b, p, 2.2 * (1 - u) + 0.4, ELEC_HOT, a, 0f);
			double sr = 0.8 + u * r * 1.4;
			for (int i = 0; i < 3; i++) {
				axes(i == 0 ? new Vec3d(0, 1, 0) : i == 1 ? dn : dn.crossProduct(new Vec3d(0, 1, 0)).add(1e-3, 0, 0));
				ring(b, p, sr * 0.93, sr, i == 0 ? ELEC : ROOM, a * 0.85f, 0f);
			}
			axes(dn);
			double wr = 1.0 + u * r * 2.4;
			ring(b, p, wr * 0.82, wr, ROOM, a * 0.75f, 0f);
			ring(b, p, wr * 0.95, wr * 1.0, CORE, a, 0f);
			if (!calm && u < 0.6) {
				for (int k = 0; k < 12; k++) {
					Vec3d dd = randDir(e.seed + k * 37 + flick);
					bolt(b, p, p.add(dd.multiply(r * (0.6 + 0.6 * hash01(e.seed + k * 5)))), 0.10, 0.03, ELEC, ELEC_HOT,
						(float) (1 - u / 0.6), e.seed + k * 13 + flick, 8, 0.4);
				}
			}
		}
	}

	/** Puncture Wille: la Kikoku colosal se clava desde el cielo, implosiona bajo tierra y sube una columna de luz. */
	static void puncture(BufferBuilder b, Ev e, double t, double now, boolean calm) {
		Vec3d c = e.b;
		double R = Math.max(4.0, e.radius);
		double depth = Math.max(6.0, e.power);
		Vec3d sky = c.add(0, 40, 0);
		int flick = calm ? 0 : (int) (now * 20);
		// 1) la Kikoku colosal baja del cielo, se clava y sigue alargándose bajo tierra
		if (t < 0.55) {
			float a = (float) (t < 0.4 ? 1.0 : (0.55 - t) / 0.15);
			KikokuBladeFx.blade(b, sky, new Vec3d(0, -1, 0), 40.0 + depth, Math.min(1.0, t / 0.16), a, now, calm);
		}
		axes(new Vec3d(0, 1, 0));   // los anillos de abajo van en horizontal (la hoja puede haber cambiado la base)
		// 2) la implosión bajo tierra: anillos que se cierran a distintas profundidades
		if (t > 0.12 && t < 0.6) {
			double u = (t - 0.12) / 0.48;
			for (int i = 0; i < 6; i++) {
				double v = MathHelper.clamp(u * 1.5 - i * 0.08, 0, 1);
				if (v <= 0 || v >= 1) continue;
				Vec3d cc = c.add(0, -depth * (i + 1) / 6.0, 0);
				double rr = R * 1.5 * (1 - v) + 0.2;
				ring(b, cc, rr * 0.85, rr, ROOM, (float) Math.sin(v * Math.PI) * 0.9f, 0f);
				ring(b, cc, rr * 0.95, rr * 1.0, CORE, (float) Math.sin(v * Math.PI), 0f);
			}
		}
		// 3) la columna de luz, la cúpula de la onda y las grietas del suelo
		if (t > 0.35) {
			double u = (t - 0.35) / 0.65;
			float a = (float) ((1 - u) * (1 - u));
			double cw = R * 0.45 * (1 - u * 0.6);
			ribbon(b, c.add(0, -depth, 0), c.add(0, 34, 0), cw, cw * 0.4, ROOM, a * 0.55f, 0f);
			ribbon(b, c.add(0, -depth, 0), c.add(0, 30, 0), cw * 0.35, cw * 0.12, CORE, a, 0f);
			double wr = R * (0.6 + u * 1.8);
			ring(b, c.add(0, 0.2, 0), wr * 0.85, wr, ROOM, a, 0f);
			ring(b, c.add(0, 0.25, 0), wr * 0.96, wr * 1.01, CORE, a, 0f);
			// la cúpula: tres paralelos de una media esfera que se abre
			for (int k = 1; k <= 3; k++) {
				double lat = k * Math.PI / 8;
				double rr = wr * Math.cos(lat);
				ring(b, c.add(0, wr * Math.sin(lat), 0), rr * 0.97, rr, ROOM, a * 0.5f, 0f);
			}
			// grietas radiales
			for (int k = 0; k < 12; k++) {
				double ang = k * Math.PI / 6 + hash01(e.seed + k) * 0.4;
				Vec3d end = c.add(Math.cos(ang) * R * 1.6, 0.15, Math.sin(ang) * R * 1.6);
				bolt(b, c.add(0, 0.15, 0), end, 0.16, 0.05, ROOM, CORE, a * 0.9f, e.seed + k * 7, 10, 0.9);
			}
			if (!calm && u < 0.5) {
				for (int k = 0; k < 8; k++) {
					Vec3d dd = randDir(e.seed + k * 41 + flick);
					bolt(b, c, c.add(Math.abs(dd.x) * 0 + dd.x * R, Math.abs(dd.y) * R * 1.4, dd.z * R), 0.12, 0.04, ELEC, ELEC_HOT,
						(float) (1 - u / 0.5), e.seed + k * 3 + flick, 9, 0.6);
				}
			}
		}
	}

	/** Silent: una burbuja muda rodea a Law; los sonidos se encogen hacia dentro y desaparecen. */
	static void silent(BufferBuilder b, MinecraftClient mc, Ev e, double t, double age, double now) {
		Entity user = entity(mc, e.userId);
		if (user == null) return;
		Vec3d c = lerpPos(user).add(0, user.getHeight() * 0.5, 0);
		double out = e.duration - age < 0.6 ? (e.duration - age) / 0.6 : 1.0;
		float a = (float) Math.max(0, Math.min(1.0, age / 0.4) * out);
		double rr = 2.3 + 0.12 * Math.sin(now * 1.6);
		// la burbuja: paralelos y meridianos tenues
		axes(new Vec3d(0, 1, 0));
		for (int k = -2; k <= 2; k++) {
			double lat = k * Math.PI / 6;
			double r2 = rr * Math.cos(lat);
			ring(b, c.add(0, rr * Math.sin(lat), 0), r2 * 0.985, r2, HUSH, a * 0.35f, 0f);
		}
		for (int k = 0; k < 3; k++) {
			double ang = k * Math.PI / 3 + now * 0.25;
			axes(new Vec3d(Math.cos(ang), 0, Math.sin(ang)));
			ring(b, c, rr * 0.985, rr, HUSH, a * 0.3f, 0f);
		}
		// ondas de sonido que se encogen hacia Law (cada 1,4 s)
		axes(new Vec3d(0, 1, 0));
		double ph = (age / 1.4) % 1.0;
		double sr = rr * 1.8 * (1 - ph) + 0.2;
		ring(b, c, sr * 0.9, sr, HUSH, a * 0.6f * (float) ph, 0f);
	}

	/** Los tajos de la Kikoku (estilo Espada): Iai, Luna de Acero, Torbellino y Mil Puntadas. */
	static void sword(BufferBuilder b, Ev e, double t, double now) {
		int variant = Math.round(e.power);
		Vec3d d = e.b.subtract(e.a);
		if (d.lengthSquared() < 1e-4) d = new Vec3d(0, 0, 1);
		double pathLen = d.length();
		Vec3d dn = d.normalize();
		Vec3d right = dn.crossProduct(new Vec3d(0, 1, 0));
		if (right.lengthSquared() < 1e-4) right = new Vec3d(1, 0, 0);
		right = right.normalize();
		Vec3d up = right.crossProduct(dn).normalize();
		float a = (float) ((1 - t) * (1 - t));
		switch (variant) {
			case LawFx.SWORD_IAI -> {
				// la estela del paso relámpago
				Vec3d p0 = e.a.add(0, 1.1, 0), p1 = e.b.add(0, 1.1, 0);
				ribbon(b, p0, p1, 0.02, 0.45, ROOM, a * 0.25f, a * 0.6f);
				ribbon(b, p0, p1, 0.005, 0.12, CORE, a * 0.5f, a);
				float fl = flashScale();
				// el envainado: un destello pequeño y brillante en el punto final, justo antes de que se abra el corte
				// (pegado a la cámara, en primera persona, no se dibuja)
				if (t > 0.12 && t < 0.36) {
					float f = (float) Math.sin((t - 0.12) / 0.24 * Math.PI);
					float fa = f * fl * (float) MathHelper.clamp((p1.distanceTo(cam) - 1.0) / 1.5, 0.0, 1.0);
					if (fa > 0.01f) {
						disc(b, p1, 0.12 + 0.3 * f, CORE, fa, 0f);
						disc(b, p1, 0.25 + 0.5 * f, ROOM, fa * 0.35f, 0f);
						// el brillo del filo al entrar en la vaina: una raya corta horizontal
						Vec3d gl = right.multiply(0.25 + 0.55 * f);
						ribbon(b, p1.subtract(gl), p1, 0.0, 0.025, CORE, 0f, fa);
						ribbon(b, p1, p1.add(gl), 0.025, 0.0, CORE, fa, 0f);
					}
				}
				// el corte, cuando Law ya ha pasado: un tajo diagonal grande (3 bloques) en mitad del camino, a la
				// altura del pecho, que se enciende en blanco y se apaga
				if (t > 0.25) {
					double u = (t - 0.25) / 0.75;
					Vec3d m = p0.lerp(p1, 0.5);
					Vec3d s = right.multiply(0.88).subtract(up.multiply(0.47)).normalize().multiply(1.5 * Math.min(1.0, u * 8.0));
					float ca = (float) ((1 - u) * (1 - u));
					float flare = (float) Math.max(0.0, 1.0 - u * 3.5) * fl;
					for (int sgn = -1; sgn <= 1; sgn += 2) {
						Vec3d tipEnd = m.add(s.multiply(sgn));
						ribbon(b, m, tipEnd, 0.42, 0.0, ROOM, ca * 0.2f, 0f);
						ribbon(b, m, tipEnd, 0.16, 0.0, ROOM, ca * 0.6f, ca * 0.1f);
						ribbon(b, m, tipEnd, 0.06 + 0.06 * flare, 0.0, CORE, Math.min(1f, ca + flare), ca * 0.3f);
					}
					if (flare > 0.01f) disc(b, m, 0.4 + 1.1 * flare, CORE, flare * 0.6f, 0f);
				}
			}
			case LawFx.SWORD_MOON -> {
				// una media luna de luz grande que vuela hasta la mira, con tres estelas que se apagan detrás
				double rr = 3.4, thick = 1.0;
				double u = Math.min(1.0, t / 0.45);
				double travel = u * pathLen;
				Vec3d n = up.add(right.multiply(0.25)).normalize();
				float am = (float) (t < 0.45 ? 1.0 : Math.max(0.0, 1.0 - (t - 0.45) / 0.55));
				float trail = (float) (t < 0.45 ? 1.0 : Math.max(0.0, 1.0 - (t - 0.45) / 0.2));
				for (int k = 3; k >= 1; k--) {
					double back = travel - k * 1.6;
					if (back < 0) continue;
					// el centro de la media luna va por detrás del punto, para que el filo pase por él
					Vec3d ck = e.a.add(dn.multiply(back - rr * 0.6));
					moonWave(b, ck, n, dn, rr * (1.0 - 0.06 * k), thick * (1.0 - 0.15 * k), am * trail * (0.55f - 0.13f * k));
				}
				moonWave(b, e.a.add(dn.multiply(travel - rr * 0.6)), n, dn, rr, thick, am);
			}
			case LawFx.SWORD_WHIRL -> {
				// dos medias lunas girando alrededor de Law a la vez que se abren
				double rr = 1.5 + t * Math.max(4.0, e.radius);
				for (int k = 0; k < 2; k++) {
					double spin = now * 9 + k * Math.PI;
					Vec3d tilt = k == 0 ? new Vec3d(0, 1, 0.18) : new Vec3d(0.18, 1, 0);
					arc(b, e.a.add(0, k * 0.6, 0), tilt.normalize(), spin, Math.PI * 1.1, rr, 0.35, a);
				}
			}
			case LawFx.SWORD_THRUSTS -> {
				// la red de mil puntadas: tres familias de cortes paralelos que cuadriculan en dados
				double r = Math.max(2.0, e.radius);
				for (int fam = 0; fam < 3; fam++) {
					Vec3d axis = fam == 0 ? up : fam == 1 ? right : dn;
					Vec3d along = fam == 0 ? right : fam == 1 ? up : right;
					for (int k = -2; k <= 2; k++) {
						double local = MathHelper.clamp(t * 3 - (fam * 5 + k + 2) * 0.06, 0, 1);
						if (local <= 0 || local >= 1) continue;
						Vec3d c = e.b.add(axis.multiply(k * r / 3));
						Vec3d s = along.multiply(r * Math.min(1, local * 3));
						float la = (float) (1 - local);
						ribbon(b, c.subtract(s), c.add(s), 0.05, 0.05, CORE, la, la);
						ribbon(b, c.subtract(s), c.add(s), 0.14, 0.14, ROOM, la * 0.4f, la * 0.4f);
					}
				}
			}
			default -> {}
		}
	}

	/** R-ROOM: una semilla de ROOM sale de la palma como un cometa hacia la mira. */
	static void rroomCast(BufferBuilder b, Ev e, double t, double now) {
		double u = Math.min(1.0, t / 0.8);
		Vec3d pos = e.a.lerp(e.b, u * u * (3 - 2 * u));
		float a = (float) (t < 0.85 ? 1.0 : (1 - t) / 0.15);
		disc(b, pos, 0.45, ROOM, a * 0.7f, 0f);
		disc(b, pos, 0.18, CORE, a, 0f);
		for (int i = 0; i < 2; i++) {
			axes(new Vec3d(Math.cos(now * 5 + i), Math.sin(now * 4), Math.sin(now * 5 + i)));
			ring(b, pos, 0.34, 0.42, ROOM, a * 0.8f, 0f);
		}
		ribbon(b, e.a.lerp(e.b, Math.max(0, u - 0.25)), pos, 0.0, 0.3, ROOM, 0f, a * 0.6f);
	}

	/** R-ROOM al soltar: la esfera estalla en una cuadrícula de nueve cortes. */
	static void rroomBurst(BufferBuilder b, Ev e, double t, double now) {
		Vec3d c = e.b;
		double r = Math.max(2.0, e.radius);
		float a = (float) ((1 - t) * (1 - t));
		double step = Math.max(2.5, r / 3);
		Vec3d[] axesV = {new Vec3d(1, 0, 0), new Vec3d(0, 1, 0), new Vec3d(0, 0, 1)};
		for (int ai = 0; ai < 3; ai++) {
			Vec3d n = axesV[ai];
			Vec3d s = axesV[(ai + 1) % 3];
			for (int k = -1; k <= 1; k++) {
				double off = k * step;
				if (Math.abs(off) >= r) continue;
				double half = Math.sqrt(r * r - off * off) * Math.min(1, t * 5);
				Vec3d cc = c.add(n.multiply(off));
				ribbon(b, cc.subtract(s.multiply(half)), cc.add(s.multiply(half)), 0.06, 0.06, CORE, a, a);
			}
		}
		disc(b, c, r * (0.2 + 0.5 * (1 - t)), ROOM, a * 0.5f, 0f);
		for (int i = 0; i < 3; i++) {
			axes(axesV[i]);
			double rr = r * (1 + t * 0.4);
			ring(b, c, rr * 0.95, rr, ROOM, a, 0f);
		}
	}

	/** Sterben: el brazo con la Kikoku sale girando; un disco de sierra en el sitio donde se lanza. */
	static void sterben(BufferBuilder b, Ev e, double t, double now) {
		Vec3d d = e.b.subtract(e.a);
		if (d.lengthSquared() < 1e-4) d = new Vec3d(0, 0, 1);
		Vec3d dn = d.normalize();
		Vec3d right = dn.crossProduct(new Vec3d(0, 1, 0));
		if (right.lengthSquared() < 1e-4) right = new Vec3d(1, 0, 0);
		float a = (float) (1 - t);
		arc(b, e.a, right.normalize(), now * 14, Math.PI * 1.6, 1.4 + t * 1.5, 0.3, a);
	}

	/** Apretar el corazón: anillos rojos que se cierran sobre el pecho del dueño. */
	static void heartSqueeze(BufferBuilder b, MinecraftClient mc, Ev e, double t, double now) {
		Entity target = entity(mc, e.targetId);
		Vec3d chest = target != null ? lerpPos(target).add(0, target.getHeight() * 0.66, 0) : e.b;
		faceCam(chest);
		for (int i = 0; i < 2; i++) {
			double u = MathHelper.clamp(t * 1.6 - i * 0.3, 0, 1);
			if (u <= 0 || u >= 1) continue;
			double rr = 1.4 * (1 - u) + 0.1;
			ring(b, chest, rr * 0.8, rr, HEART, (float) Math.sin(u * Math.PI), 0f);
		}
		disc(b, chest, 0.25 + 0.1 * Math.sin(now * 20), HEART, (float) (1 - t) * 0.7f, 0f);
	}

	// ------------------------------------------------------------------ HUD

	public static void renderHud(DrawContext ctx, RenderTickCounter tc) {
		MinecraftClient mc = MinecraftClient.getInstance();
		if (mc.player == null || mc.options.hudHidden) return;
		int w = ctx.getScaledWindowWidth(), h = ctx.getScaledWindowHeight();
		int level = OpeSettings.flashLevel();
		double now = RoomFxClient.time();

		// Silent: los bordes se apagan hacia un índigo mudo
		if (ClientState.has(Payloads.State.SILENT)) {
			int edge = 0x55141A3A, zero = 0x00141A3A;
			int band = Math.max(20, h / 6);
			ctx.fillGradient(RenderLayer.getGuiOverlay(), 0, 0, w, band, edge, zero, 0);
			ctx.fillGradient(RenderLayer.getGuiOverlay(), 0, h - band, w, h, zero, edge, 0);
		}
		// Curtain: un velo muy tenue delante, como mirar a través de la gasa
		if (ClientState.has(Payloads.State.CURTAIN)) {
			ctx.fill(RenderLayer.getGuiOverlay(), 0, 0, w, h, 0x0CD8F4FF);
		}
		// Scan: la línea del escáner baja por la pantalla
		double sa = now - scanStart;
		if (level > 0 && sa >= 0 && sa < 0.7) {
			float u = (float) (sa / 0.7);
			int y = Math.round(u * h);
			int alpha = Math.round((1f - u) * (level >= 2 ? 160 : 70));
			int col = (alpha << 24) | 0x9FF0FF;
			ctx.fillGradient(RenderLayer.getGuiOverlay(), 0, Math.max(0, y - h / 8), w, y, 0x009FF0FF, (alpha / 3 << 24) | 0x9FF0FF, 0);
			ctx.fill(RenderLayer.getGuiOverlay(), 0, y, w, Math.min(h, y + 2), col);
		}
		// el pulso de color en los bordes tras una técnica fuerte
		double age = now - pulseStart;
		if (level > 0 && age >= 0 && age < 0.36 && pulseStrength > 0.01f) {
			float u = (float) (age / 0.36);
			float alpha = pulseStrength * (1f - u) * (1f - u) * (level >= 2 ? 1f : 0.45f);
			int band = Math.max(14, Math.min(w, h) / 8);
			int cTop = (Math.round(alpha * 255f) << 24) | (pulseRgb & 0xFFFFFF);
			int cZero = pulseRgb & 0xFFFFFF;
			ctx.fillGradient(RenderLayer.getGuiOverlay(), 0, 0, w, band, cTop, cZero, 0);
			ctx.fillGradient(RenderLayer.getGuiOverlay(), 0, h - band, w, h, cZero, cTop, 0);
		}
		// la presentación anime: el rótulo con el nombre de la técnica, el fotograma de impacto y el tajo en pantalla
		LawHudFx.render(ctx, tc);
	}

	// ------------------------------------------------------------------ geometría

	static Entity entity(MinecraftClient mc, int id) {
		if (id < 0 || mc.world == null) return null;
		Entity e = mc.world.getEntityById(id);
		return e != null && e.isAlive() ? e : null;
	}

	/**
	 * El ser aunque se esté muriendo: sigue en el mundo mientras dura la animación de muerte, y es justo entonces
	 * cuando se ve lo de dentro (Gamma Knife y Shock Wille suelen matar al momento).
	 */
	static Entity body(MinecraftClient mc, int id) {
		if (id < 0 || mc.world == null) return null;
		Entity e = mc.world.getEntityById(id);
		return e != null && !e.isRemoved() ? e : null;
	}

	static Vec3d lerpPos(Entity e) {
		return e.getLerpedPos(td);
	}

	static Box lerpBox(Entity e) {
		Vec3d p = e.getLerpedPos(td);
		return e.getBoundingBox().offset(p.subtract(e.getPos()));
	}

	/** La mano derecha de un jugador (o el centro delante del pecho de otro ser). */
	static Vec3d handOf(Entity e) {
		Vec3d look = e.getRotationVec(td);
		Vec3d right = look.crossProduct(new Vec3d(0, 1, 0));
		if (right.lengthSquared() < 1e-4) right = new Vec3d(1, 0, 0);
		right = right.normalize();
		Vec3d eye = e.getCameraPosVec(td);
		return eye.add(look.multiply(0.7)).add(right.multiply(0.32)).add(0, -0.32, 0);
	}

	static void fan(BufferBuilder b, Vec3d c, Vec3d s, Vec3d dir, double reach, double depth, int rgb, float a) {
		// un abanico fino: el plano que barre la hoja entre −s y +s, un poco hacia delante y hacia atrás
		int n = 10;
		for (int i = 0; i < n; i++) {
			double u0 = -1 + 2.0 * i / n, u1 = -1 + 2.0 * (i + 1) / n;
			Vec3d p0 = c.add(s.multiply(u0 * reach)), p1 = c.add(s.multiply(u1 * reach));
			Vec3d f = dir.multiply(depth);
			float e0 = (float) (1 - Math.abs(u0)), e1 = (float) (1 - Math.abs(u1));
			quad(b, p0.subtract(f), p1.subtract(f), p1.add(f), p0.add(f), rgb, 0f, 0f, a * e1, a * e0);
		}
	}

	static void crescent(BufferBuilder b, Vec3d c, Vec3d n, Vec3d fwd, double r, double thick, float a) {
		Vec3d s = n.crossProduct(fwd);
		if (s.lengthSquared() < 1e-4) s = new Vec3d(1, 0, 0);
		s = s.normalize();
		int k = 18;
		Vec3d prevO = null, prevI = null;
		for (int i = 0; i <= k; i++) {
			double ang = -Math.PI * 0.42 + Math.PI * 0.84 * i / k;
			double env = Math.sin(Math.PI * i / k);
			Vec3d dir = s.multiply(Math.sin(ang)).add(fwd.multiply(Math.cos(ang)));
			Vec3d o = c.add(dir.multiply(r)), in = c.add(dir.multiply(r - thick * env));
			if (prevO != null) {
				quad(b, prevI, prevO, o, in, ROOM, a * 0.35f, a * 0.9f, a * 0.9f, a * 0.35f);
				ribbon(b, prevO, o, 0.03, 0.03, CORE, a, a);
			}
			prevO = o;
			prevI = in;
		}
	}

	/**
	 * Luna de Acero: una media luna grande y brillante. Además de la de su plano ({@link #crescent}) lleva grosor a lo
	 * largo de n (una banda que brilla en el filo y se apaga hacia fuera), para que no quede como una raya fina cuando
	 * se ve de canto, que es justo como la ve Law desde detrás.
	 */
	static void moonWave(BufferBuilder b, Vec3d c, Vec3d n, Vec3d fwd, double r, double thick, float a) {
		if (a <= 0.01f) return;
		crescent(b, c, n, fwd, r, thick, a);
		Vec3d s = n.crossProduct(fwd);
		if (s.lengthSquared() < 1e-4) s = new Vec3d(1, 0, 0);
		s = s.normalize();
		int k = 22;
		Vec3d prev = null;
		double prevH = 0, prevEnv = 0;
		for (int i = 0; i <= k; i++) {
			double ang = -Math.PI * 0.42 + Math.PI * 0.84 * i / k;
			double env = Math.sin(Math.PI * i / k);
			Vec3d dir = s.multiply(Math.sin(ang)).add(fwd.multiply(Math.cos(ang)));
			Vec3d o = c.add(dir.multiply(r));
			double h = 0.5 * thick * env;
			if (prev != null) {
				Vec3d pu = prev.add(n.multiply(prevH)), pd = prev.subtract(n.multiply(prevH));
				Vec3d ou = o.add(n.multiply(h)), od = o.subtract(n.multiply(h));
				quad(b, pd, od, o, prev, ROOM, 0f, 0f, a * 0.7f, a * 0.7f);
				quad(b, prev, o, ou, pu, ROOM, a * 0.7f, a * 0.7f, 0f, 0f);
				// el filo, blanco y más grueso en el centro
				ribbon(b, prev, o, 0.03 + 0.07 * prevEnv, 0.03 + 0.07 * env, CORE, a, a);
			}
			prev = o;
			prevH = h;
			prevEnv = env;
		}
	}

	/** Un arco de corte en el plano de normal n, desde el ángulo start y con la amplitud span. */
	static void arc(BufferBuilder b, Vec3d c, Vec3d n, double start, double span, double r, double thick, float a) {
		axes(n);
		int k = 22;
		Vec3d prevO = null, prevI = null;
		for (int i = 0; i <= k; i++) {
			double u = i / (double) k;
			double ang = start + span * u;
			double env = Math.sin(Math.PI * u);
			double cx = u1x * Math.cos(ang) + u2x * Math.sin(ang), cy = u1y * Math.cos(ang) + u2y * Math.sin(ang),
				cz = u1z * Math.cos(ang) + u2z * Math.sin(ang);
			Vec3d o = c.add(cx * r, cy * r, cz * r), in = c.add(cx * (r - thick * env), cy * (r - thick * env), cz * (r - thick * env));
			if (prevO != null) {
				float aa = (float) (a * u);
				quad(b, prevI, prevO, o, in, ROOM, aa * 0.3f, aa, aa, aa * 0.3f);
				ribbon(b, prevO, o, 0.025, 0.025, CORE, aa, aa);
			}
			prevO = o;
			prevI = in;
		}
	}

	static void reticle(BufferBuilder b, Vec3d c, double r, double now, float a) {
		faceCam(c);
		ring(b, c, r * 0.92, r, ROOM, a, a * 0.3f);
		ring(b, c, r * 0.45, r * 0.5, CORE, a * 0.8f, 0f);
		double spin = now * 2.0;
		for (int k = 0; k < 4; k++) {
			double ang = spin + k * Math.PI / 2;
			double cx = u1x * Math.cos(ang) + u2x * Math.sin(ang), cy = u1y * Math.cos(ang) + u2y * Math.sin(ang),
				cz = u1z * Math.cos(ang) + u2z * Math.sin(ang);
			Vec3d p0 = c.add(cx * r * 1.05, cy * r * 1.05, cz * r * 1.05), p1 = c.add(cx * r * 1.45, cy * r * 1.45, cz * r * 1.45);
			ribbon(b, p0, p1, 0.035, 0.035, CORE, a, a * 0.6f);
		}
	}

	static void cubeEdges(BufferBuilder b, Vec3d c, double h, int rgb, float a, double w, double spin) {
		Vec3d[] v = cubeVerts(c, h, spin);
		int[][] edges = {{0, 1}, {1, 2}, {2, 3}, {3, 0}, {4, 5}, {5, 6}, {6, 7}, {7, 4}, {0, 4}, {1, 5}, {2, 6}, {3, 7}};
		for (int[] ed : edges) ribbon(b, v[ed[0]], v[ed[1]], w, w, rgb, a, a);
	}

	static void cubeFaces(BufferBuilder b, Vec3d c, double h, int rgb, float a, double spin) {
		Vec3d[] v = cubeVerts(c, h, spin);
		int[][] faces = {{0, 1, 2, 3}, {4, 5, 6, 7}, {0, 1, 5, 4}, {1, 2, 6, 5}, {2, 3, 7, 6}, {3, 0, 4, 7}};
		for (int[] f : faces) quad(b, v[f[0]], v[f[1]], v[f[2]], v[f[3]], rgb, a, a, a, a);
	}

	static Vec3d[] cubeVerts(Vec3d c, double h, double spin) {
		double cs = Math.cos(spin), sn = Math.sin(spin), tilt = 0.45;
		double ct = Math.cos(tilt), st = Math.sin(tilt);
		Vec3d[] v = new Vec3d[8];
		int i = 0;
		for (int y = -1; y <= 1; y += 2) {
			double[][] xz = {{-1, -1}, {1, -1}, {1, 1}, {-1, 1}};
			for (double[] q : xz) {
				double x = q[0] * h, z = q[1] * h, yy = y * h;
				// inclinado y girando, como en el anime
				double y2 = yy * ct - z * st, z2 = yy * st + z * ct;
				double x3 = x * cs - z2 * sn, z3 = x * sn + z2 * cs;
				v[i++] = c.add(x3, y2, z3);
			}
		}
		return v;
	}

	static void boxEdges(BufferBuilder b, Box box, int rgb, float a, double w) {
		double x0 = box.minX, y0 = box.minY, z0 = box.minZ, x1 = box.maxX, y1 = box.maxY, z1 = box.maxZ;
		Vec3d[] v = {new Vec3d(x0, y0, z0), new Vec3d(x1, y0, z0), new Vec3d(x1, y0, z1), new Vec3d(x0, y0, z1),
			new Vec3d(x0, y1, z0), new Vec3d(x1, y1, z0), new Vec3d(x1, y1, z1), new Vec3d(x0, y1, z1)};
		int[][] edges = {{0, 1}, {1, 2}, {2, 3}, {3, 0}, {4, 5}, {5, 6}, {6, 7}, {7, 4}, {0, 4}, {1, 5}, {2, 6}, {3, 7}};
		for (int[] ed : edges) ribbon(b, v[ed[0]], v[ed[1]], w, w, rgb, a, a);
	}

	static Vec3d pointOnBox(Box box, int seed) {
		double fx = hash01(seed), fy = hash01(seed * 7 + 3), fz = hash01(seed * 13 + 5);
		int face = (int) (hash01(seed * 17 + 11) * 6) % 6;
		if (face == 0) fx = 0; else if (face == 1) fx = 1; else if (face == 2) fy = 0; else if (face == 3) fy = 1; else if (face == 4) fz = 0; else fz = 1;
		return new Vec3d(MathHelper.lerp(fx, box.minX, box.maxX), MathHelper.lerp(fy, box.minY, box.maxY), MathHelper.lerp(fz, box.minZ, box.maxZ));
	}

	static Vec3d bezier(Vec3d p0, Vec3d p1, Vec3d p2, double u) {
		double k = 1 - u;
		return p0.multiply(k * k).add(p1.multiply(2 * k * u)).add(p2.multiply(u * u));
	}

	static Vec3d randDir(int seed) {
		double x = hashSigned(seed), y = hashSigned(seed * 3 + 1), z = hashSigned(seed * 7 + 2);
		Vec3d v = new Vec3d(x, y, z);
		return v.lengthSquared() < 1e-4 ? new Vec3d(0, 1, 0) : v.normalize();
	}

	static void bolt(BufferBuilder b, Vec3d a0, Vec3d b0, double outerW, double innerW, int outer, int inner,
							 float alpha, int seed, int segs, double jitter) {
		Vec3d d = b0.subtract(a0);
		double len = d.length();
		if (len < 0.15 || alpha <= 0.01f) return;
		axes(d);
		double ex = u1x, ey = u1y, ez = u1z, fx = u2x, fy = u2y, fz = u2z;
		Vec3d last = a0;
		for (int i = 1; i <= segs; i++) {
			double s = i / (double) segs;
			double env = Math.sin(s * Math.PI);
			double rx = hashSigned(seed + i * 19) * jitter * env * Math.min(1.0, len * 0.4);
			double ry = hashSigned(seed + i * 37) * jitter * env * Math.min(1.0, len * 0.4);
			Vec3d next = i == segs ? b0 : a0.add(d.multiply(s)).add(ex * rx + fx * ry, ey * rx + fy * ry, ez * rx + fz * ry);
			ribbon(b, last, next, outerW, outerW, outer, alpha * 0.5f, alpha * 0.5f);
			ribbon(b, last, next, innerW, innerW, inner, alpha, alpha);
			last = next;
		}
	}

	/** Base (u1, u2) perpendicular a n. */
	static void axes(Vec3d n) {
		double nx = n.x, ny = n.y, nz = n.z;
		double l = Math.sqrt(nx * nx + ny * ny + nz * nz);
		if (l < 1e-6) {
			nx = 0; ny = 1; nz = 0;
		} else {
			nx /= l; ny /= l; nz /= l;
		}
		double rx = Math.abs(ny) < 0.9 ? 0 : 1, ry = Math.abs(ny) < 0.9 ? 1 : 0;
		double cx = ny * 0 - nz * ry, cy = nz * rx - nx * 0, cz = nx * ry - ny * rx;
		double cl = Math.sqrt(cx * cx + cy * cy + cz * cz);
		if (cl < 1e-6) {
			u1x = 1; u1y = 0; u1z = 0;
		} else {
			u1x = cx / cl; u1y = cy / cl; u1z = cz / cl;
		}
		u2x = ny * u1z - nz * u1y;
		u2y = nz * u1x - nx * u1z;
		u2z = nx * u1y - ny * u1x;
	}

	static void faceCam(Vec3d c) {
		axes(c.subtract(cam));
	}

	/** Un anillo en el plano (u1, u2) actual. */
	static void ring(BufferBuilder b, Vec3d c, double rIn, double rOut, int rgb, float aIn, float aOut) {
		if (rOut <= 0 || (aIn <= 0.003f && aOut <= 0.003f)) return;
		rIn = Math.max(0, rIn);
		for (int i = 0; i < SEG; i++) {
			double c0 = COS[i], s0 = SIN[i], c1 = COS[i + 1], s1 = SIN[i + 1];
			vtx(b, c.x + (u1x * c0 + u2x * s0) * rIn, c.y + (u1y * c0 + u2y * s0) * rIn, c.z + (u1z * c0 + u2z * s0) * rIn, rgb, aIn);
			vtx(b, c.x + (u1x * c0 + u2x * s0) * rOut, c.y + (u1y * c0 + u2y * s0) * rOut, c.z + (u1z * c0 + u2z * s0) * rOut, rgb, aOut);
			vtx(b, c.x + (u1x * c1 + u2x * s1) * rOut, c.y + (u1y * c1 + u2y * s1) * rOut, c.z + (u1z * c1 + u2z * s1) * rOut, rgb, aOut);
			vtx(b, c.x + (u1x * c1 + u2x * s1) * rIn, c.y + (u1y * c1 + u2y * s1) * rIn, c.z + (u1z * c1 + u2z * s1) * rIn, rgb, aIn);
		}
	}

	/** Un disco de luz que mira a la cámara (centro brillante, borde transparente). */
	static void disc(BufferBuilder b, Vec3d c, double r, int rgb, float aCenter, float aEdge) {
		faceCam(c);
		ring(b, c, 0, r, rgb, aCenter, aEdge);
	}

	static void quad(BufferBuilder b, Vec3d p0, Vec3d p1, Vec3d p2, Vec3d p3, int rgb, float a0, float a1, float a2, float a3) {
		vtx(b, p0.x, p0.y, p0.z, rgb, a0);
		vtx(b, p1.x, p1.y, p1.z, rgb, a1);
		vtx(b, p2.x, p2.y, p2.z, rgb, a2);
		vtx(b, p3.x, p3.y, p3.z, rgb, a3);
	}

	/** Una cinta entre dos puntos que siempre da la cara a la cámara. */
	static void ribbon(BufferBuilder b, Vec3d p0, Vec3d p1, double w0, double w1, int rgb, float a0, float a1) {
		double ax = p0.x - cam.x, ay = p0.y - cam.y, az = p0.z - cam.z;
		double bx = p1.x - cam.x, by = p1.y - cam.y, bz = p1.z - cam.z;
		double mx = (ax + bx) * 0.5, my = (ay + by) * 0.5, mz = (az + bz) * 0.5;
		double dx = bx - ax, dy = by - ay, dz = bz - az;
		double px = dy * mz - dz * my, py = dz * mx - dx * mz, pz = dx * my - dy * mx;
		double pl = Math.sqrt(px * px + py * py + pz * pz);
		if (pl < 1e-9) return;
		px /= pl; py /= pl; pz /= pl;
		vtx(b, p0.x - px * w0, p0.y - py * w0, p0.z - pz * w0, rgb, a0);
		vtx(b, p0.x + px * w0, p0.y + py * w0, p0.z + pz * w0, rgb, a0);
		vtx(b, p1.x + px * w1, p1.y + py * w1, p1.z + pz * w1, rgb, a1);
		vtx(b, p1.x - px * w1, p1.y - py * w1, p1.z - pz * w1, rgb, a1);
	}

	/** Un vértice en coordenadas del mundo (se pasa a relativas a la cámara aquí). */
	static void vtx(BufferBuilder b, double x, double y, double z, int rgb, float a) {
		a = MathHelper.clamp(a, 0f, 1f);
		float fx = (float) (x - cam.x), fy = (float) (y - cam.y), fz = (float) (z - cam.z);
		if (!plain) {
			BASE.transformPosition(fx, fy, fz, TV);
			fx = TV.x;
			fy = TV.y;
			fz = TV.z;
		}
		b.vertex(fx, fy, fz).color((rgb >> 16) & 255, (rgb >> 8) & 255, rgb & 255, Math.round(a * 255f));
	}

	static int lerpRgb(int c0, int c1, float u) {
		u = MathHelper.clamp(u, 0f, 1f);
		int r = Math.round(((c0 >> 16) & 255) + (((c1 >> 16) & 255) - ((c0 >> 16) & 255)) * u);
		int g = Math.round(((c0 >> 8) & 255) + (((c1 >> 8) & 255) - ((c0 >> 8) & 255)) * u);
		int bl = Math.round((c0 & 255) + ((c1 & 255) - (c0 & 255)) * u);
		return (r << 16) | (g << 8) | bl;
	}

	static double hash01(int x) {
		return (hashSigned(x) + 1.0) * 0.5;
	}

	static double hashSigned(int x) {
		x = ((x >>> 16) ^ x) * 0x45d9f3b;
		x = ((x >>> 16) ^ x) * 0x45d9f3b;
		x = (x >>> 16) ^ x;
		return ((x & 0xFFFF) / 32767.5) - 1.0;
	}
}
