package dev.opeope.power;

import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayList;
import java.util.List;

/** Tab 1: los patrones de corte. Un tajo, rodajas, cruz o dados. */
public final class Patterns {
	private Patterns() {}

	public static final String[] NAMES = {"Un tajo", "Rodajas", "Cruz", "Dados"};

	public static List<CutEngine.Plane> expand(ServerPlayerEntity p, CutEngine.Plane base, int pattern) {
		List<CutEngine.Plane> out = new ArrayList<>();
		Vec3d look = p.getRotationVector();
		Vec3d n = base.n();
		Vec3d aim = PowerManager.aimPoint(p, 200);
		Vec3d n2 = n.crossProduct(look);
		if (n2.lengthSquared() < 1e-4) n2 = n.crossProduct(new Vec3d(0, 1, 0));
		n2 = n2.normalize();
		switch (pattern) {
			case 1 -> {
				for (int k = -2; k <= 2; k++) out.add(new CutEngine.Plane(n, aim.add(n.multiply(k * 1.4))));
			}
			case 2 -> {
				out.add(base);
				out.add(new CutEngine.Plane(n2, aim));
			}
			case 3 -> {
				for (Vec3d axis : new Vec3d[]{n, n2, look}) {
					for (int k = -1; k <= 1; k++) out.add(new CutEngine.Plane(axis, aim.add(axis.multiply(k * 1.6))));
				}
			}
			default -> out.add(base);
		}
		return out;
	}
}
