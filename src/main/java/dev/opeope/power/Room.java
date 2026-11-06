package dev.opeope.power;

import net.minecraft.registry.RegistryKey;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;

/** La esfera azul donde todo se puede cortar, mover y cambiar de sitio. Fija, como en el canon. */
public class Room {
	public enum State { GROWING, FIXED, FLYING }

	public Vec3d center;
	public double radius;
	public State state = State.GROWING;
	public Vec3d flyTo;
	public RegistryKey<World> dim;
	public double maxRadius;   // R-ROOM: hasta dónde crece

	// En vuelo (lanzado o volviendo a ti). Lanzado, el centro no se mueve hasta que aterriza.
	public Vec3d flyFrom;
	public int flyTicks, flyTotal;
	public double castRadius;  // el radio con el que aterriza
	public boolean fresh;      // lanzado con la misma pulsación que lo creó
	public boolean bringing;   // volviendo a ti (si no, lanzado)
	public boolean limitSent;  // ya avisó de que llegó al máximo mientras crecía

	public Room(RegistryKey<World> dim, Vec3d center, double radius) {
		this.dim = dim;
		this.center = center;
		this.radius = radius;
	}

	public boolean contains(Vec3d p) {
		return p.squaredDistanceTo(center) <= radius * radius;
	}
}
