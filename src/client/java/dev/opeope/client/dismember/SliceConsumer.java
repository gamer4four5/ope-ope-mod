package dev.opeope.client.dismember;

import net.minecraft.client.render.VertexConsumer;
import org.joml.Matrix4f;
import org.joml.Vector4f;

/**
 * Corte libre: deja pasar solo lo que queda de un lado de un plano. Cada cara del modelo (un cuadrilátero) se recorta
 * contra el plano (Sutherland–Hodgman) y lo que queda se dibuja en abanico. Trabaja sobre los vértices ya transformados:
 * los lleva de vuelta al marco en el que se dio el plano con la inversa de la matriz de ese momento. Sirve para el ser
 * (se queda con su lado) y para la pieza (con el otro), con el modelo, la armadura y lo que lleve en la mano.
 */
public final class SliceConsumer implements VertexConsumer {
	private final VertexConsumer d;
	private final Matrix4f inv;
	private final float nx, ny, nz, dd;
	private final float[] buf = new float[4 * 8];
	private final int[] ibuf = new int[4 * 3];
	private int count;

	/** Se queda con lo que cumple n·L &gt; d (L: el punto en el marco de m). */
	public SliceConsumer(VertexConsumer delegate, Matrix4f m, float nx, float ny, float nz, float d) {
		this.d = delegate;
		this.inv = new Matrix4f(m).invert();
		this.nx = nx;
		this.ny = ny;
		this.nz = nz;
		this.dd = d;
	}

	@Override
	public void vertex(float x, float y, float z, int color, float u, float v, int overlay, int light, float nx, float ny, float nz) {
		int o = count * 8;
		buf[o] = x;
		buf[o + 1] = y;
		buf[o + 2] = z;
		buf[o + 3] = u;
		buf[o + 4] = v;
		buf[o + 5] = nx;
		buf[o + 6] = ny;
		buf[o + 7] = nz;
		ibuf[count * 3] = color;
		ibuf[count * 3 + 1] = overlay;
		ibuf[count * 3 + 2] = light;
		if (++count == 4) {
			emit();
			count = 0;
		}
	}

	private void emit() {
		float[] s = new float[4];
		boolean allIn = true, allOut = true;
		for (int i = 0; i < 4; i++) {
			int o = i * 8;
			Vector4f l = inv.transform(new Vector4f(buf[o], buf[o + 1], buf[o + 2], 1));
			s[i] = nx * l.x + ny * l.y + nz * l.z - dd;
			if (s[i] > 0) allOut = false;
			else allIn = false;
		}
		if (allOut) return;
		if (allIn) {
			for (int i = 0; i < 4; i++) put(buf, i * 8, i);
			return;
		}
		// recortar el cuadrilátero: como mucho 5 vértices; se dibuja como abanico de cuadriláteros
		float[] out = new float[6 * 8];
		int[] src = new int[6];
		int n = 0;
		for (int i = 0; i < 4; i++) {
			int j = (i + 1) % 4;
			float a = s[i], b = s[j];
			if (a > 0) {
				System.arraycopy(buf, i * 8, out, n * 8, 8);
				src[n++] = i;
			}
			if ((a > 0) != (b > 0)) {
				float t = a / (a - b);
				for (int c = 0; c < 8; c++) out[n * 8 + c] = buf[i * 8 + c] + (buf[j * 8 + c] - buf[i * 8 + c]) * t;
				src[n++] = a > 0 ? i : j;
			}
		}
		if (n < 3) return;
		for (int j = 1; j + 1 < n; j += 2) {
			int c = Math.min(j + 2, n - 1);
			put(out, 0, src[0]);
			put(out, j * 8, src[j]);
			put(out, (j + 1) * 8, src[j + 1]);
			put(out, c * 8, src[c]);
		}
	}

	private void put(float[] v, int o, int k) {
		d.vertex(v[o], v[o + 1], v[o + 2], ibuf[k * 3], v[o + 3], v[o + 4], ibuf[k * 3 + 1], ibuf[k * 3 + 2], v[o + 5], v[o + 6], v[o + 7]);
	}

	@Override
	public VertexConsumer vertex(float x, float y, float z) {
		d.vertex(x, y, z);
		return this;
	}

	@Override
	public VertexConsumer color(int r, int g, int b, int a) {
		d.color(r, g, b, a);
		return this;
	}

	@Override
	public VertexConsumer texture(float u, float v) {
		d.texture(u, v);
		return this;
	}

	@Override
	public VertexConsumer overlay(int u, int v) {
		d.overlay(u, v);
		return this;
	}

	@Override
	public VertexConsumer light(int u, int v) {
		d.light(u, v);
		return this;
	}

	@Override
	public VertexConsumer normal(float x, float y, float z) {
		d.normal(x, y, z);
		return this;
	}
}
