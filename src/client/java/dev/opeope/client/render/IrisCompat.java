package dev.opeope.client.render;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;

/**
 * Si Iris está instalado, ¿se está dibujando ahora la pasada de sombras de los shaders? Por reflexión, para no depender
 * de Iris al compilar ni al jugar sin él.
 */
final class IrisCompat {
	private static final MethodHandle SHADOW;
	private static final Object API;

	static {
		MethodHandle h = null;
		Object api = null;
		try {
			Class<?> c = Class.forName("net.irisshaders.iris.api.v0.IrisApi");
			api = c.getMethod("getInstance").invoke(null);
			h = MethodHandles.publicLookup().findVirtual(c, "isRenderingShadowPass", MethodType.methodType(boolean.class));
		} catch (Throwable ignored) {
			h = null;
			api = null;
		}
		SHADOW = h;
		API = api;
	}

	private IrisCompat() {}

	static boolean shadowPass() {
		if (SHADOW == null) return false;
		try {
			return (boolean) SHADOW.invoke(API);
		} catch (Throwable t) {
			return false;
		}
	}
}
