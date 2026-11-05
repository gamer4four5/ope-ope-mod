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

	public Room(RegistryKey<World> dim, Vec3d center, double radius) {
		this.dim = dim;
		this.center = center;
		this.radius = radius;
	}

	public boolean contains(Vec3d p) {
		return p.squaredDistanceTo(center) <= radius * radius;
	}
}
