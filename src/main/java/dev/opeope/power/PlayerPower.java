package dev.opeope.power;

import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Todo lo que un usuario de la fruta tiene en marcha. Vive en el servidor mientras el jugador está conectado. */
public class PlayerPower {
	public enum TaktMode { HAND, ORBIT, PARK }

	public enum FloorOp { PILLAR, WALL, CHASM, PLATEAU }

	// ROOM
	public Room room;
	public Room rroom;
	public boolean roomKeyDown;
	public boolean roomCreatedThisPress;
	public boolean roomUsedDuringHold;
	public int roomPressTick;

	// Takt
	public final List<Integer> takt = new ArrayList<>();
	public TaktMode taktMode = TaktMode.HAND;
	public double taktDist = 4.5;
	public final Map<Integer, Vec3d> parked = new HashMap<>();
	public long lastTaktTap;
	public final List<Integer> suspended = new ArrayList<>();
	public final List<Integer> weapons = new ArrayList<>();
	public int weaponKind = -1;

	// Shambles y almas
	public int shamblesMark = -1;
	public int shamblesMarkTicks;
	public BlockPos shamblesBlock;
	public int soulMark = -1;
	public int soulMarkTicks;

	// Técnicas con duración
	public final int[] cooldowns = new int[Tech.values().length];
	public int scanTicks;
	public int curtainTicks;
	public int rainTicks;
	public int silentTicks;
	public int dashTicks;
	public Vec3d dashDir = Vec3d.ZERO;
	public boolean surgery;
	public int surgeryTicks;
	public boolean awakened;
	public boolean anesthesia;
	public int anesthesiaTarget = -1;
	public int anesthesiaTicks;
	public boolean rroomHeld;
	public int rroomTicks;
	public boolean squeezing;
	public int squeezeTicks;

	// Opciones de corte (Tab 1 2 3)
	public int pattern;          // 0 un tajo, 1 rodajas, 2 cruz, 3 dados
	public boolean reachTarget;  // false: todo lo que cruza el ROOM; true: solo el objetivo
	public boolean suspend;

	// Unión
	public boolean unionMode;
	public int unionSelected = -1;

	// El suelo del ROOM
	public FloorOp floorOp;
	public int floorTicks;
	public Vec3d floorHead;
	public BlockPos pillarBase;
	public int floorCount;

	public boolean bisturi;
	public boolean dirty = true;

	public boolean onCooldown(Tech t) {
		return cooldowns[t.ordinal()] > 0;
	}

	public void cooldown(Tech t) {
		cooldowns[t.ordinal()] = t.ticks;
		dirty = true;
	}
}
