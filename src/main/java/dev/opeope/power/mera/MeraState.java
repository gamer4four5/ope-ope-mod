package dev.opeope.power.mera;

import net.minecraft.util.math.Vec3d;

/** Lo que recuerda la Mera Mera no Mi de cada jugador que la comió (solo en el servidor). */
public final class MeraState {
	/** Cuerpo de llamas (M): el cuerpo entero arde, quema a quien te toca y derrite la nieve. */
	public boolean fireBody;
	public int bodyBroadcastTicks;

	/** Hiken: mantener la G carga el puño de fuego (0..HIKEN_FULL ticks). */
	public boolean hikenCharging;
	public int hikenCharge;

	/** Dai Enkai: Entei: mantener la Z junta el sol encima de la mano (0..ENTEI_FULL ticks). */
	public boolean enteiCharging;
	public int enteiCharge;

	/** Kagerō: el chorro de fuego de la palma (ticks que quedan). */
	public int kageroTicks;

	/** El cuerpo hecho un chorro de llamas (Alt): ticks que quedan, hacia dónde y por dónde va. */
	public int dashTicks;
	public Vec3d dashDir = Vec3d.ZERO;

	/** Hikyaku: impulsos de fuego en los pies que quedan antes de tocar suelo. */
	public int hikyakuCharges = 2;
	/** Ticks sin daño por caída (tras el chorro de llamas o Hikyaku). */
	public int fallSafeTicks;

	/** Hotarubi: las luciérnagas que flotan (id del grupo, dónde, cuántas, ticks que les quedan). */
	public int hotarubiId = -1;
	public Vec3d hotarubiCenter = Vec3d.ZERO;
	public int hotarubiCount;
	public int hotarubiTicks;

	/** Avisos y efectos que no deben repetirse cada tick. */
	public int scatterCooldown;
	public int wetNoticeCooldown;
	public int logiaNoticeCooldown;
	public boolean wasWet;
}
