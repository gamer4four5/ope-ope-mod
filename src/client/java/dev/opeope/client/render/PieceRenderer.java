package dev.opeope.client.render;

import dev.opeope.OpeConfig;
import dev.opeope.OpeOpeMod;
import dev.opeope.client.config.OpeSettings;
import dev.opeope.entity.BlockSet;
import dev.opeope.entity.PieceEntity;
import net.minecraft.block.BlockState;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.LightmapTextureManager;
import net.minecraft.client.render.OverlayTexture;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.WorldRenderer;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.block.BlockModelRenderer;
import net.minecraft.client.render.block.BlockRenderManager;
import net.minecraft.client.render.entity.EntityRenderer;
import net.minecraft.client.render.entity.EntityRendererFactory;
import net.minecraft.client.texture.SpriteAtlasTexture;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.RotationAxis;
import org.joml.Matrix4f;

/**
 * Dibuja una pieza cortada bloque a bloque. Las armas forjadas, giradas como siempre. El resto, con el dibujo de bloques
 * del mundo sobre PieceView (tinte del bioma, caras tapadas entre sus bloques, sombra suave), agrupado por capa, y con
 * su movimiento: tiembla mientras se arranca, se inclina hacia donde acelera, se aplasta un poco al llegar a la mano y se
 * encoge un pelo al posarse (para no pelearse con los bloques ya puestos). El brillo lo pone TerrainFx después.
 */
public class PieceRenderer extends EntityRenderer<PieceEntity> {
	public PieceRenderer(EntityRendererFactory.Context ctx) {
		super(ctx);
		this.shadowRadius = 0f;
	}

	@Override
	public void render(PieceEntity e, float yaw, float tickDelta, MatrixStack ms, VertexConsumerProvider vcp, int light) {
		BlockSet set = e.blocks;
		if (set == null || set.isEmpty()) return;
		BlockRenderManager brm = MinecraftClient.getInstance().getBlockRenderManager();
		int lit = brighter(e, light);
		if (e.isWeaponShape()) {
			renderWeapon(e, set, tickDelta, ms, vcp, lit, brm);
			super.render(e, yaw, tickDelta, ms, vcp, lit);
			return;
		}

		PieceVisuals vis = PieceVisuals.of(e);
		vis.frame(e, tickDelta);
		boolean rm = OpeSettings.reducedMotion();
		int flags = e.flags();
		Matrix4f local = new Matrix4f();   // cada transformación de ms, repetida aquí (el brillo se dibuja con ella)
		ms.push();
		double bob = (flags & PieceEntity.F_SUSPENDED) != 0 && !rm ? Math.sin((e.age + tickDelta) * 0.12) * 0.025 : 0;
		if ((flags & PieceEntity.F_TEAR) != 0 && !rm) tremble(e, tickDelta, ms, local);
		if ((flags & PieceEntity.F_HELD) != 0 && !rm && e.radius() > 1.2 && vis.lean > 1e-4) {
			float half = set.sy / 2f;
			translate(ms, local, 0, half, 0);
			float rx = (float) (vis.lean * vis.kx), rz = (float) (vis.lean * vis.kz);
			ms.multiply(RotationAxis.POSITIVE_X.rotation(rx));
			local.rotateX(rx);
			ms.multiply(RotationAxis.POSITIVE_Z.rotation(rz));
			local.rotateZ(rz);
			translate(ms, local, 0, -half, 0);
		}
		if (!rm && vis.squashAge < 0.30) {   // alrededor del centro de abajo, que es el origen de la entidad
			ms.scale((float) vis.sxz, (float) vis.sy, (float) vis.sxz);
			local.scale((float) vis.sxz, (float) vis.sy, (float) vis.sxz);
		}
		translate(ms, local, -set.sx / 2f, (float) bob, -set.sz / 2f);
		vis.local = local;
		vis.pos = e.getLerpedPos(tickDelta);
		vis.td = tickDelta;
		if (vis.drawnStamp != PieceVisuals.stamp && PieceVisuals.DRAWN.size() < 256) {
			vis.drawnStamp = PieceVisuals.stamp;
			PieceVisuals.DRAWN.add(vis);
		}

		boolean landed = (flags & PieceEntity.F_LANDED) != 0;
		if (!PieceVisuals.broken) {
			try {
				models(vis, ms, vcp, brm, landed);
			} catch (RuntimeException ex) {
				if (!PieceVisuals.broken) OpeOpeMod.LOG.warn("PieceView: {}", ex.toString());
				PieceVisuals.broken = true;   // desde ahora, todo por el camino de siempre (este fotograma también)
			}
		}
		for (BlockSet.Entry b : PieceVisuals.broken ? set.visible() : vis.others) {
			ms.push();
			ms.translate(b.x(), b.y(), b.z());
			if (landed) landedShrink(ms);
			brm.renderBlockAsEntity(b.state(), ms, vcp, lit, OverlayTexture.DEFAULT_UV);
			ms.pop();
		}
		ms.pop();
		super.render(e, yaw, tickDelta, ms, vcp, lit);
	}

	/** Los bloques con modelo, capa a capa: un búfer por capa y pieza. La pila de matrices queda igual aunque algo falle. */
	private static void models(PieceVisuals vis, MatrixStack ms, VertexConsumerProvider vcp, BlockRenderManager brm, boolean landed) {
		BlockModelRenderer mr = brm.getModelRenderer();
		BlockPos.Mutable wp = new BlockPos.Mutable();
		BlockPos base = vis.seedBase;
		for (PieceVisuals.Group g : vis.groups) {
			VertexConsumer buf = vcp.getBuffer(g.layer());
			for (BlockSet.Entry b : g.entries()) {
				ms.push();
				try {
					ms.translate(b.x(), b.y(), b.z());
					if (landed) landedShrink(ms);
					BlockState st = b.state();
					wp.set(base.getX() + b.x(), base.getY() + b.y(), base.getZ() + b.z());
					mr.render(vis.view, brm.getModel(st), st, wp, ms, buf, true, vis.random, st.getRenderingSeed(wp), OverlayTexture.DEFAULT_UV);
				} finally {
					ms.pop();
				}
			}
		}
	}

	/** Mientras se arranca: tiembla, cada vez más fuerte (más tiempo si el trozo es grande). */
	private static void tremble(PieceEntity e, float td, MatrixStack ms, Matrix4f local) {
		int ticks = e.radius() > OpeConfig.TAKT_BIG_RADIUS ? OpeConfig.TAKT_TREMBLE_TICKS_BIG : OpeConfig.TAKT_TREMBLE_TICKS;
		double now = e.getWorld().getTime() + td;
		double k = MathHelper.clamp((now - e.fxTime()) / ticks, 0, 1);
		double amp = 0.02 + 0.06 * k;
		double s = now * 0.05;
		double ph = e.getId();
		double tau = Math.PI * 2;
		translate(ms, local, (float) (amp * Math.sin(tau * 13 * s + ph)), (float) (0.4 * amp * Math.sin(tau * 17 * s + 2 * ph)),
			(float) (amp * Math.sin(tau * 11 * s + 3 * ph)));
	}

	private static void translate(MatrixStack ms, Matrix4f local, float x, float y, float z) {
		ms.translate(x, y, z);
		local.translate(x, y, z);
	}

	/** Posándose o ya posada: un pelo más pequeña, para no pelearse con los bloques que ya están puestos. */
	private static void landedShrink(MatrixStack ms) {
		ms.translate(0.5f, 0.5f, 0.5f);
		ms.scale(0.998f, 0.998f, 0.998f);
		ms.translate(-0.5f, -0.5f, -0.5f);
	}

	/** Un arma forjada: girada como la lleva quien la usa, bloque a bloque (como siempre). */
	private static void renderWeapon(PieceEntity e, BlockSet set, float tickDelta, MatrixStack ms, VertexConsumerProvider vcp, int light,
			BlockRenderManager brm) {
		ms.push();
		float y = MathHelper.lerpAngleDegrees(tickDelta, e.prevYaw, e.getYaw());
		float p = MathHelper.lerp(tickDelta, e.prevPitch, e.getPitch());
		ms.multiply(RotationAxis.POSITIVE_Y.rotationDegrees(-y));
		ms.multiply(RotationAxis.POSITIVE_X.rotationDegrees(p));
		ms.translate(-set.sx / 2.0, -set.sy / 2.0, -set.sz / 2.0);
		for (BlockSet.Entry b : set.visible()) {
			ms.push();
			ms.translate(b.x(), b.y(), b.z());
			brm.renderBlockAsEntity(b.state(), ms, vcp, light, OverlayTexture.DEFAULT_UV);
			ms.pop();
		}
		ms.pop();
	}

	/**
	 * La luz de la entidad se mide dentro de la pieza (donde estaban sus bloques, que tarda en iluminarse, o bajo tierra
	 * en un trozo de terreno) y la pieza saldría negra: se toma también la luz justo encima de ella y se usa la mayor.
	 */
	private static int brighter(PieceEntity e, int light) {
		var w = e.getWorld();
		if (w == null) return light;
		var box = e.getBoundingBox();
		BlockPos above = BlockPos.ofFloored(box.getCenter().x, box.maxY + 0.5, box.getCenter().z);
		int top = WorldRenderer.getLightmapCoordinates(w, above);
		int block = Math.max(LightmapTextureManager.getBlockLightCoordinates(light), LightmapTextureManager.getBlockLightCoordinates(top));
		int sky = Math.max(LightmapTextureManager.getSkyLightCoordinates(light), LightmapTextureManager.getSkyLightCoordinates(top));
		return LightmapTextureManager.pack(block, sky);
	}

	@Override
	public Identifier getTexture(PieceEntity entity) {
		return SpriteAtlasTexture.BLOCK_ATLAS_TEXTURE;
	}
}
