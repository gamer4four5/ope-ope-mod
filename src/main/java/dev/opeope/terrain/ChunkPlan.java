package dev.opeope.terrain;

import net.minecraft.util.math.BlockPos;
import org.jetbrains.annotations.Nullable;

/**
 * Lo que un arranque de Takt se llevaría: el resultado de ChunkPlanner.plan. Lo mismo en el servidor (el arranque) que
 * en el cliente (la vista previa). Los arrays no se tocan después de crearlo.
 */
public final class ChunkPlan {
	/** Nada que arrancar: sin bloques, ni cara ni cerrado, sin mira y con todos los arrays vacíos. */
	public static final ChunkPlan EMPTY = new ChunkPlan(null, false, false, new double[0], new long[0], new long[0],
		new int[0], new int[0], new int[0], 0, 0, 0, 0);

	/** Plan de suelo: el bloque de superficie de la mira; plan de cara: el bloque al que se mira. */
	@Nullable public final BlockPos aim;
	/** true: un pedazo de una pared, un talud o un techo (4.2.5): celdas en vez de columnas, sin cortes y sin subir. */
	public final boolean face;
	/** Solo plan de suelo: tres cortes o más cierran la forma. */
	public final boolean closed;
	/** Plan de suelo: el contorno 2D x0,z0,x1,z1,… en sentido antihorario (coordenadas del mundo), para los registros. */
	public final double[] outline;
	/** BlockPos.asLong, por x, luego z, luego y ascendentes. Suelo: los bloques de terreno; cara: todo el pedazo. */
	public final long[] blocks;
	/** Suelo: lo que va encima, en grupos enteros y en el orden de la búsqueda; cara: las plantas de encima. */
	public final long[] riders;
	/** Suelo: una por columna incluida, por x y luego z: TaktFx.pack(dx, dz, cima − aim.y) | (borde ? 1 &lt;&lt; 30 : 0). */
	public final int[] columns;
	/** Cara: una por bloque, en el orden de blocks: TaktFx.pack(dx, dz, dy) desde aim | (al aire ? 1 &lt;&lt; 30 : 0). */
	public final int[] cells;
	/** Los ids de los cortes que dieron forma al trozo, ascendentes: los únicos que se apartan y se gastan. */
	public final int[] usedCuts;
	/** Suelo: la distancia horizontal más grande del centro de la columna de la mira al de una columna incluida, + 0,5;
	 *  cara: la distancia más grande del centro del bloque mirado al de un bloque, + 0,5. */
	public final double radius;
	/** Suelo: la cima más alta de las columnas incluidas; cara: la y más alta de los bloques. */
	public final int topMax;
	/** Suelo: T y la profundidad máxima de la última pasada; cara: el radio del pedazo y 0. */
	public final double t, maxDepth;

	public ChunkPlan(@Nullable BlockPos aim, boolean face, boolean closed, double[] outline, long[] blocks, long[] riders,
			int[] columns, int[] cells, int[] usedCuts, double radius, int topMax, double t, double maxDepth) {
		this.aim = aim;
		this.face = face;
		this.closed = closed;
		this.outline = outline;
		this.blocks = blocks;
		this.riders = riders;
		this.columns = columns;
		this.cells = cells;
		this.usedCuts = usedCuts;
		this.radius = radius;
		this.topMax = topMax;
		this.t = t;
		this.maxDepth = maxDepth;
	}

	/** Bloques de terreno más lo que va encima. */
	public int size() {
		return blocks.length + riders.length;
	}

	public boolean isEmpty() {
		return size() == 0;
	}

	@Override
	public String toString() {
		return "ChunkPlan(" + blocks.length + " bloques + " + riders.length + " encima, cerrado " + closed + ", cara " + face
			+ ", cortes " + usedCuts.length + ", radio " + String.format(java.util.Locale.ROOT, "%.2f", radius) + ")";
	}
}
