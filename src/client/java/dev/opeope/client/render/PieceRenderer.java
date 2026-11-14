package dev.opeope.client.render;

import dev.opeope.OpeConfig;
import dev.opeope.OpeOpeMod;
import dev.opeope.client.config.OpeSettings;
import dev.opeope.entity.BlockSet;
import dev.opeope.entity.PieceEntity;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.block.BlockRenderType;
import net.minecraft.block.BlockState;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.color.block.BlockColors;
import net.minecraft.client.render.LightmapTextureManager;
import net.minecraft.client.render.OverlayTexture;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.RenderLayers;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.WorldRenderer;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.block.BlockModelRenderer;
import net.minecraft.client.render.block.BlockRenderManager;
import net.minecraft.client.render.entity.EntityRenderer;
import net.minecraft.client.render.entity.EntityRendererFactory;
import net.minecraft.client.render.model.BakedModel;
import net.minecraft.client.render.model.BakedQuad;
import net.minecraft.client.texture.SpriteAtlasTexture;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.RotationAxis;
import net.minecraft.util.math.random.Random;
import net.minecraft.world.EmptyBlockView;
import org.joml.Matrix4f;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * Dibuja una pieza cortada bloque a bloque. Las armas forjadas, giradas como siempre. Las piezas normales, con el
 * dibujo de bloques del mundo sobre PieceView (tinte del bioma, caras tapadas entre sus bloques, sombra suave), agrupado
 * por capa y con sus animaciones; las gigantes (> 350 bloques visibles), con caché de malla y culling interno para
 * mantener FPS altos en cortes de montañas.
 */
public class PieceRenderer extends EntityRenderer<PieceEntity> {
	private record CulledQuad(BakedQuad quad, float r, float g, float b) {}
	private record BakedBlock(int x, int y, int z, RenderLayer layer, CulledQuad[] quads) {}
	private record CachedMesh(List<BakedBlock> blocks, List<BlockSet.Entry> fallback) {}

	private static final Map<BlockSet, CachedMesh> MESH_CACHE = new WeakHashMap<>();

	public PieceRenderer(EntityRendererFactory.Context ctx) {
		super(ctx);
		this.shadowRadius = 0f;
	}

	@Override
	public void render(PieceEntity e, float yaw, float tickDelta, MatrixStack ms, VertexConsumerProvider vcp, int light) {
		BlockSet set = e.blocks;
		if (set == null || set.isEmpty()) return;
		MinecraftClient mc = MinecraftClient.getInstance();
		BlockRenderManager brm = mc.getBlockRenderManager();
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
		if (set.visible().size() > 350) {
			CachedMesh mesh = MESH_CACHE.computeIfAbsent(set, s -> buildMesh(s, brm, mc.getBlockColors()));
			ms.push();
			if (landed) {
				ms.translate(set.sx / 2f, set.sy / 2f, set.sz / 2f);
				ms.scale(0.998f, 0.998f, 0.998f);
				ms.translate(-set.sx / 2f, -set.sy / 2f, -set.sz / 2f);
			}
			int px = 0, py = 0, pz = 0;
			for (BakedBlock bb : mesh.blocks) {
				int dx = bb.x - px, dy = bb.y - py, dz = bb.z - pz;
				if (dx != 0 || dy != 0 || dz != 0) {
					ms.translate(dx, dy, dz);
					px = bb.x; py = bb.y; pz = bb.z;
				}
				MatrixStack.Entry entry = ms.peek();
				VertexConsumer vc = vcp.getBuffer(bb.layer);
				for (CulledQuad q : bb.quads) {
					vc.quad(entry, q.quad, q.r, q.g, q.b, 1.0f, lit, OverlayTexture.DEFAULT_UV);
				}
			}
			if (px != 0 || py != 0 || pz != 0) ms.translate(-px, -py, -pz);
			for (BlockSet.Entry b : mesh.fallback) {
				ms.push();
				ms.translate(b.x(), b.y(), b.z());
				brm.renderBlockAsEntity(b.state(), ms, vcp, lit, OverlayTexture.DEFAULT_UV);
				ms.pop();
			}
			ms.pop();
		} else {
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
		}
		ms.pop();
		super.render(e, yaw, tickDelta, ms, vcp, lit);
	}

	private static long packLocal(int x, int y, int z) {
		return (((long) (x & 0xFFFFF)) << 40) | (((long) (y & 0xFFFFF)) << 20) | ((long) (z & 0xFFFFF));
	}

	private static CachedMesh buildMesh(BlockSet set, BlockRenderManager brm, BlockColors colors) {
		LongOpenHashSet opaque = new LongOpenHashSet(set.entries.size());
		for (BlockSet.Entry e : set.entries) {
			if (e.state().isOpaqueFullCube(EmptyBlockView.INSTANCE, BlockPos.ORIGIN)) {
				opaque.add(packLocal(e.x(), e.y(), e.z()));
			}
		}
		List<BakedBlock> baked = new ArrayList<>();
		List<BlockSet.Entry> fallback = new ArrayList<>();
		Random rand = Random.create(42L);
		Direction[] dirs = Direction.values();

		for (BlockSet.Entry b : set.visible()) {
			BlockState st = b.state();
			if (st.getRenderType() != BlockRenderType.MODEL) {
				fallback.add(b);
				continue;
			}
			BakedModel model = brm.getModel(st);
			List<CulledQuad> qList = new ArrayList<>(6);
			for (Direction d : dirs) {
				if (opaque.contains(packLocal(b.x() + d.getOffsetX(), b.y() + d.getOffsetY(), b.z() + d.getOffsetZ()))) {
					continue;
				}
				List<BakedQuad> sideQuads = model.getQuads(st, d, rand);
				for (BakedQuad q : sideQuads) qList.add(bakeQuad(q, st, colors));
			}
			List<BakedQuad> generalQuads = model.getQuads(st, null, rand);
			for (BakedQuad q : generalQuads) qList.add(bakeQuad(q, st, colors));
			if (!qList.isEmpty()) {
				RenderLayer layer = RenderLayers.getEntityBlockLayer(st, false);
				baked.add(new BakedBlock(b.x(), b.y(), b.z(), layer, qList.toArray(CulledQuad[]::new)));
			}
		}
		return new CachedMesh(baked, fallback);
	}

	private static float dirShade(Direction face) {
		return switch (face) {
			case DOWN -> 0.5f;
			case NORTH, SOUTH -> 0.8f;
			case EAST, WEST -> 0.6f;
			default -> 1.0f;
		};
	}

	private static CulledQuad bakeQuad(BakedQuad q, BlockState st, BlockColors colors) {
		float shade = q.hasShade() ? dirShade(q.getFace()) : 1.0f;
		if (!q.hasColor()) return new CulledQuad(q, shade, shade, shade);
		int rgb = colors.getColor(st, null, null, q.getColorIndex());
		float r = (((rgb >> 16) & 0xFF) / 255.0f) * shade;
		float g = (((rgb >> 8) & 0xFF) / 255.0f) * shade;
		float b = ((rgb & 0xFF) / 255.0f) * shade;
		return new CulledQuad(q, r, g, b);
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
