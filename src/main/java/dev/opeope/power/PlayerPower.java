package dev.opeope.power;

import dev.opeope.OpeConfig;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

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
	public double lastRoomRadius;   // el último tamaño que dejó fijo: un ROOM lanzado recién creado aterriza con él

	// Takt
	public final List<Integer> takt = new ArrayList<>();
	public TaktMode taktMode = TaktMode.HAND;
	public double taktDist = 4.5;
	public final Map<Integer, Vec3d> parked = new HashMap<>();
	public final Map<Integer, Vec3d> taktOffsets = new HashMap<>();
	public long lastTaktTap;
	public final List<Integer> suspended = new ArrayList<>();
	public final List<Integer> bisturiHeld = new ArrayList<>();   // piezas de bloques cortadas con Bisturí, quietas hasta soltar
	public final List<Integer> bisturiHeldParts = new ArrayList<>(); // partes vivas cortadas con Bisturí, congeladas hasta soltar
	public final List<Integer> weapons = new ArrayList<>();
	public int weaponKind = -1;

	// Takt sobre el suelo
	public final List<Takt.Rip> rips = new ArrayList<>();   // arranques pendientes o recién sueltos (aún se pueden deshacer)
	public double orbitPhase;              // radianes; lo avanza Takt.tick
	public int lastTapRip = -1;            // el arranque que empezó el último toque; −1 si ese toque no empezó ninguno (R R deshace)
	public long lastTapTick = -1000;       // tiempo del mundo del último toque aceptado (límite de toques)
	public long lastZoneTick = -1000;      // tiempo del mundo del último R R / R + E aceptado (el mismo límite)
	public int ripBudget = OpeConfig.TAKT_RIP_BUDGET;   // lo gastan los arranques y lo recupera Takt.tick
	public int orbitalBarrageTicks = 0;    // bombardeo orbital en cadena activo (> 0 descarga una tras otra)
	public int orbitalShotIndex = 0;       // índice de disparo para dispersión en espiral cerrada

	// Shambles y almas
	public int shamblesMode = 0;   // 0 = Tú → Destino, 1 = Tú ↔ Entidad, 2 = Entidad A ↔ B
	public int shamblesMark = -1;
	public int shamblesMarkTicks;
	public BlockPos shamblesBlock;
	public int soulMark = -1;
	public int soulMarkTicks;

	// Shambles Táctico y Ventana Aérea de Remate
	public int lastThrownPieceId = -1;
	public long lastThrownTick = -1000;
	public final List<Integer> recentThrownPieces = new ArrayList<>(8);
	public int aerialWindowTicks = 0;
	public int aerialWindowMaxTicks = 0;
	public boolean aerialPerfect = false;
	public int finisherTargetId = -1;
	public int aerialChain = 0;
	public float aerialFinisherMult = 1.0f;

	public void recordThrownPiece(int pieceId, long worldTick) {
		lastThrownPieceId = pieceId;
		lastThrownTick = worldTick;
		recentThrownPieces.remove((Integer) pieceId);
		recentThrownPieces.add(0, pieceId);
		while (recentThrownPieces.size() > 8) {
			recentThrownPieces.remove(recentThrownPieces.size() - 1);
		}
	}

	public void clearAerialWindow() {
		aerialWindowTicks = 0;
		aerialWindowMaxTicks = 0;
		aerialPerfect = false;
		finisherTargetId = -1;
	}

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

	// Fusión: Estilos de combate (1=Fruta, 2=Espada Kikoku, 3=Pelea/Palma), Modo Libre y Protección de caída en el cielo
	public int combatStyle = 1;
	public boolean freeMode = false;
	public int fallImmunityTicks = 0;
	public int comboStep = 0;
	public long lastComboTick = 0;
	public int counterStanceTicks = 0;

	// Nuevas expansiones de la fruta: K-ROOM Espada Gigante 3D, Shambles de Proyectiles y Cirugía de Juventud Eterna
	public int kroomBladeTicks = 0;
	public int eternalYouthTicks = 0;
	public boolean projectileShambles = true;
	public int transplantedHearts = 0;

	// Gravedad Cero + Vuelo Quirúrgico en el ROOM y Control Mental (Kokoro Shambles)
	public boolean zeroGravityRoom = false;
	public boolean surgicalFlightGranted = false;
	public final List<UUID> controlledMobs = new ArrayList<>();

	public boolean bisturi;
	public boolean dirty = true;

	// Fruta activa (1 = Ope Ope no Mi, 2 = Gravity / Zushi Zushi no Mi) y estado propio de Fujitora (SIN ROOM)
	public int fruitKind = 1;
	public int gravityMountId = -1;
	public int gravityVector = 0; // 0 = Aplastar (+G), 1 = Antigravedad (-G), 2 = Repeler, 3 = Atraer
	public boolean gravityCharging = false;
	public int gravityChargeTicks = 0;
	public Vec3d gravityChargePos = Vec3d.ZERO;
	public final List<Integer> gravityHeld = new ArrayList<>();
	public double gravityHoldDist = 8.5;
	// Tengai Shinsei: selección en vivo de tamaño de meteorito (0=12m, 1=24m, 2=40m, 3=60m, 4=85m)
	public int meteorTier = 1;
	public boolean meteorCharging = false;
	public int meteorChargeTicks = 0;
	public Vec3d meteorAimPos = Vec3d.ZERO;

	public boolean isGravity() {
		return fruitKind == 2;
	}

	// Progreso de Recompensa Pirata y Maestría Quirúrgica
	public long bounty = 0L;
	public int killStreak = 0;
	public long lastKillTick = -1000L;

	public int masteryTier() {
		if (bounty >= 300_000_000L) return 4;
		if (bounty >= 100_000_000L) return 3;
		if (bounty >= 30_000_000L) return 2;
		if (bounty >= 5_000_000L) return 1;
		return 0;
	}

	public String masteryRankName() {
		return switch (masteryTier()) {
			case 1 -> "Cirujano de Combate";
			case 2 -> "Supernova";
			case 3 -> "Shichibukai";
			case 4 -> "Emperador Quirúrgico (Yonko)";
			default -> "Aprendiz";
		};
	}

	public boolean onCooldown(Tech t) {
		if (freeMode) return false;
		return cooldowns[t.ordinal()] > 0;
	}

	public void cooldown(Tech t) {
		if (freeMode) {
			cooldowns[t.ordinal()] = 0;
			return;
		}
		double mul = Math.max(0.55, 1.0 - 0.10 * masteryTier());
		cooldowns[t.ordinal()] = Math.max(1, (int) Math.round(t.ticks * mul));
		dirty = true;
	}
}
