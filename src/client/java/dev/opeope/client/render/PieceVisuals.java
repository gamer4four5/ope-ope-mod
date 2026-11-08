package dev.opeope.client.render;

import dev.opeope.client.room.RoomFxClient;
import dev.opeope.entity.BlockSet;
import dev.opeope.entity.PieceEntity;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import net.minecraft.block.BlockRenderType;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.RenderLayers;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.math.random.Random;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix4f;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Lo que el cliente recuerda de cada pieza para dibujarla (guardado en PieceEntity.renderState): sus bloques agrupados
 * por capa de dibujo, sus caras de fuera y las cortadas, la vista de mundo que usa el dibujo de bloques, y el estado de
 * la inclinación y el aplastón. Se rehace cuando cambia el BlockSet (otra instancia) o el modo de gráficos.
 */
final class PieceVisuals {
	/** PieceView falló una vez: el resto de la sesión, el dibujo de siempre. */
	static boolean broken;
	/** Las piezas dibujadas este fotograma (las rellena PieceRenderer, las usa y vacía TerrainFx). */
	static final List<PieceVisuals> DRAWN = new ArrayList<>();
	/** Número de fotograma de DRAWN: TerrainFx lo sube al vaciarla (una pieza entra una sola vez por fotograma). */
	static int stamp;

	/** Desplazamiento de cada cara, por Direction.ordinal(): DOWN, UP, NORTH, SOUTH, WEST, EAST. */
	static final int[] DX = {0, 0, 0, 0, -1, 1}, DY = {-1, 1, 0, 0, 0, 0}, DZ = {0, 0, -1, 1, 0, 0};
	static final int RIM_MAX = 1500, CUT_MAX = 2000;

	record Group(RenderLayer layer, List<BlockSet.Entry> entries) {}

	final PieceEntity e;
	final BlockSet set;
	final PieceView view;
	final BlockPos seedBase;
	final Random random = Random.create();
	final boolean fancy;
	/** Los bloques MODEL de set.visible(), por capa (en el orden en que aparece cada capa). */
	final List<Group> groups;
	/** Los de set.visible() que no son MODEL (cofres, camas...): por el camino de siempre. */
	final List<BlockSet.Entry> others;
	/** Caras de fuera: (índice en set.entries << 3) | dirección, cuando la celda vecina no es de la pieza. */
	final int[] ext;
	/** Las de ext que son cara cortada (bit de entry.faces()). */
	final int[] cut;

	// el último fotograma
	Matrix4f local = new Matrix4f();
	Vec3d pos = Vec3d.ZERO;
	float td;
	int drawnStamp = -1;

	double lean, kx, kz, squashAge = Double.POSITIVE_INFINITY, sy = 1, sxz = 1;

	private Vec3d lastP;
	private Vec3d lastV = Vec3d.ZERO;
	private double ax, az;
	private long lastNanos;
	private boolean lastHeld;
	private double squashStart = Double.NEGATIVE_INFINITY;
	private int[] rim, cutDrawn;

	private PieceVisuals(PieceEntity e, BlockSet set, boolean fancy, @Nullable PieceVisuals old) {
		this.e = e;
		this.set = set;
		this.fancy = fancy;
		// el mismo bloque de siempre (solo cambió el modo de gráficos o llegó otra copia igual): la misma semilla
		boolean same = old != null && (old.set == set || (old.set.sx == set.sx && old.set.sy == set.sy && old.set.sz == set.sz));
		this.seedBase = same ? old.seedBase : cell(e.getPos(), set);
		this.view = new PieceView(e.getWorld(), set, seedBase);

		List<Group> gs = new ArrayList<>(4);
		List<BlockSet.Entry> oth = new ArrayList<>();
		for (BlockSet.Entry b : set.visible()) {
			if (b.state().getRenderType() != BlockRenderType.MODEL) {
				oth.add(b);
				continue;
			}
			RenderLayer layer = RenderLayers.getMovingBlockLayer(b.state());
			List<BlockSet.Entry> into = null;
			for (Group g : gs) {
				if (g.layer() == layer) {
					into = g.entries();
					break;
				}
			}
			if (into == null) {
				into = new ArrayList<>();
				gs.add(new Group(layer, into));
			}
			into.add(b);
		}
		this.groups = gs;
		this.others = oth;
		this.ext = exterior(set);
		this.cut = cutOf(set, ext);

		if (old != null) {
			lastP = old.lastP;
			lastV = old.lastV;
			ax = old.ax;
			az = old.az;
			lastNanos = old.lastNanos;
			lastHeld = old.lastHeld;
			squashStart = old.squashStart;
			lean = old.lean;
			kx = old.kx;
			kz = old.kz;
			squashAge = old.squashAge;
			sy = old.sy;
			sxz = old.sxz;
		} else {
			// una pieza que se ve por primera vez ya en la mano no se aplasta
			lastHeld = held(e.flags());
		}
	}

	/** Los datos de dibujo de la pieza: los guardados, o unos nuevos si cambió su BlockSet o el modo de gráficos. */
	static PieceVisuals of(PieceEntity e) {
		boolean fancy = MinecraftClient.isFancyGraphicsOrBetter();
		PieceVisuals old = e.renderState instanceof PieceVisuals v ? v : null;
		if (old != null && old.set == e.blocks && old.fancy == fancy) return old;
		PieceVisuals v = new PieceVisuals(e, e.blocks, fancy, old);
		e.renderState = v;
		return v;
	}

	/** La celda de la rejilla donde está la pieza (su esquina mínima redondeada). */
	static BlockPos cell(Vec3d p, BlockSet set) {
		return BlockPos.ofFloored(p.x - set.sx / 2.0 + 0.5, p.y + 0.5, p.z - set.sz / 2.0 + 0.5);
	}

	private static boolean held(int flags) {
		return (flags & PieceEntity.F_HELD) != 0 && (flags & PieceEntity.F_TEAR) == 0;
	}

	/**
	 * Una vez por fotograma: dónde está, la vista de mundo, la inclinación hacia donde acelera y el reloj del aplastón
	 * (cuando pasa a la mano sin estar arrancándose).
	 */
	void frame(PieceEntity e, float td) {
		long now = System.nanoTime();
		if (lastNanos != 0 && now - lastNanos < 2_000_000L) return;   // segunda llamada en el mismo fotograma
		double dt = lastNanos == 0 ? 0 : MathHelper.clamp((now - lastNanos) / 1e9, 1 / 240.0, 0.1);
		lastNanos = now;
		Vec3d p = e.getLerpedPos(td);
		view.moveTo(p, set);
		if (dt > 0 && lastP != null) {
			Vec3d v = p.subtract(lastP).multiply(1 / dt);
			Vec3d a = v.subtract(lastV).multiply(1 / dt);
			if (a.length() > 400) {   // un salto (teletransporte, recarga): sin inclinación
				v = Vec3d.ZERO;
				a = Vec3d.ZERO;
				ax = 0;
				az = 0;
			}
			double k = 1 - Math.exp(-dt / 0.15);
			ax += (a.x - ax) * k;
			az += (a.z - az) * k;
			lastV = v;
		}
		lastP = p;
		double ah = Math.hypot(ax, az);
		double r = e.radius();
		lean = Math.toRadians(6 * MathHelper.clamp((r - 1) / 3, 0.3, 1) * MathHelper.clamp(ah / 30, 0, 1));
		kx = ah > 1e-3 ? az / ah : 0;
		kz = ah > 1e-3 ? -ax / ah : 0;

		boolean held = held(e.flags());
		double t = RoomFxClient.time();
		if (held && !lastHeld) squashStart = t;
		lastHeld = held;
		squashAge = t - squashStart;
		if (squashAge >= 0 && squashAge < 0.30) {
			double u = squashAge / 0.30;
			sy = u < 0.35 ? 1 + 0.06 * Math.sin(Math.PI * u / 0.35) : 1 - 0.08 * Math.sin(Math.PI * (u - 0.35) / 0.65);
			sxz = 1 / Math.sqrt(sy);
		} else {
			sy = 1;
			sxz = 1;
		}
	}

	/** Las caras del brillo del borde: todas las de fuera; con más de 1500, sin las de abajo y luego una de cada n. */
	int[] rimFaces() {
		if (rim != null) return rim;
		int[] r = ext;
		if (r.length > RIM_MAX) {
			IntArrayList up = new IntArrayList(r.length);
			for (int c : r) if ((c & 7) != 0) up.add(c);   // 0 = DOWN
			r = up.toIntArray();
		}
		if (r.length > RIM_MAX) {
			int step = (r.length + RIM_MAX - 1) / RIM_MAX;
			IntArrayList keep = new IntArrayList(RIM_MAX + 1);
			for (int i = 0; i < r.length; i += step) keep.add(r[i]);
			r = keep.toIntArray();
		}
		rim = r;
		return r;
	}

	/** Las caras cortadas que se dibujan (como mucho 2000). */
	int[] cutFaces() {
		if (cutDrawn == null) cutDrawn = cut.length <= CUT_MAX ? cut : Arrays.copyOf(cut, CUT_MAX);
		return cutDrawn;
	}

	/** Las caras de fuera de un BlockSet: (índice << 3) | dirección, en el orden de entries y de Direction. */
	static int[] exterior(BlockSet set) {
		IntArrayList out = new IntArrayList();
		List<BlockSet.Entry> es = set.entries;
		for (int i = 0; i < es.size(); i++) {
			BlockSet.Entry b = es.get(i);
			for (int d = 0; d < 6; d++) {
				if (!set.contains(b.x() + DX[d], b.y() + DY[d], b.z() + DZ[d])) out.add((i << 3) | d);
			}
		}
		return out.toIntArray();
	}

	private static int[] cutOf(BlockSet set, int[] ext) {
		IntArrayList out = new IntArrayList();
		for (int c : ext) {
			BlockSet.Entry b = set.entries.get(c >> 3);
			if (((b.faces() >> (c & 7)) & 1) != 0) out.add(c);
		}
		return out.toIntArray();
	}
}
