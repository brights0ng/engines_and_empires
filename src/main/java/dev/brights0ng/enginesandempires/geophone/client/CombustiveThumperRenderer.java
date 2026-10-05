package dev.brights0ng.enginesandempires.geophone.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import com.simibubi.create.content.kinetics.base.KineticBlockEntityRenderer;

import dev.brights0ng.enginesandempires.geophone.CombustiveThumperBlockEntity;
import dev.engine_room.flywheel.api.visualization.VisualizationManager;
import net.createmod.catnip.render.CachedBuffers;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.block.ModelBlockRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.core.Direction;
import net.minecraft.core.Direction.AxisDirection;
import net.minecraft.world.level.block.state.BlockState;

/**
 * The combustive thumper's ordinary block entity renderer.
 *
 * <p>Always, Flywheel or not: the two fuel gauges' needles and the ready lamp on the fuel cylinder, two blocks up. Both
 * change every frame in ways that are simpler to draw here than as instances.
 *
 * <p>With Flywheel off, also everything {@link CombustiveThumperVisual} otherwise draws: the shaft through the base, and
 * the ram between the rails with its lift rods.
 */
public class CombustiveThumperRenderer extends KineticBlockEntityRenderer<CombustiveThumperBlockEntity> {

    /** The gauge needle's pivot, in the top block's own pixels: the middle of the east gauge's face. */
    private static final float PIVOT_X = 15.5F / 16;
    private static final float PIVOT_Y = 12.0F / 16;
    private static final float PIVOT_Z = 8.0F / 16;

    /** The needle's sweep: this far to the viewer's left of straight up when empty, as far to the right when full. */
    private static final float SWEEP_DEGREES = 60.0F;

    public CombustiveThumperRenderer(BlockEntityRendererProvider.Context context) {
        super(context);
    }

    @Override
    public boolean shouldRenderOffScreen(CombustiveThumperBlockEntity be) {
        return true;
    }

    @Override
    protected void renderSafe(CombustiveThumperBlockEntity be, float partialTicks, PoseStack ms, MultiBufferSource buffer,
                              int light, int overlay) {
        BlockState state = be.getBlockState();
        if (!VisualizationManager.supportsVisualization(be.getLevel())) {
            VertexConsumer solid = buffer.getBuffer(RenderType.solid());
            net.minecraft.core.Direction.Axis axis = getRotationAxisOf(be);
            renderRotatingKineticBlock(be, shaft(axis), ms, solid, light);
            float rise = be.renderedRamHeight(partialTicks) * CombustiveThumperBlockEntity.RAM_TRAVEL;
            CachedBuffers.partial(ThumperClient.COMBUSTIVE_RAM, state)
                    .translate(0, rise, 0)
                    .light(LevelRenderer.getLightColor(be.getLevel(), be.getBlockPos().above()))
                    .renderInto(ms, solid);
            CachedBuffers.partial(ThumperClient.COMBUSTIVE_RODS, state)
                    .translate(0, 2 + rise, 0)
                    .rotateCentered(ThumperClient.modelRotation(state))
                    .light(LevelRenderer.getLightColor(be.getLevel(), be.getBlockPos().above(2)))
                    .renderInto(ms, solid);
        }
        renderCylinderParts(be, state, partialTicks, ms, buffer);
    }

    /** The needles and the lamp, on the fuel cylinder two blocks up, turned to match the column. */
    private static void renderCylinderParts(CombustiveThumperBlockEntity be, BlockState state, float partialTicks,
                                            PoseStack ms, MultiBufferSource buffer) {
        ModelBlockRenderer renderer = Minecraft.getInstance().getBlockRenderer().getModelRenderer();
        VertexConsumer consumer = buffer.getBuffer(RenderType.entityCutoutNoCull(TextureAtlas.LOCATION_BLOCKS));
        int topLight = LevelRenderer.getLightColor(be.getLevel(), be.getBlockPos().above(2));

        ms.pushPose();
        ms.translate(0, 2, 0);
        ms.rotateAround(ThumperClient.modelRotation(state), 0.5F, 0.5F, 0.5F);

        // Empty: needle tilted to the viewer's left. Full: to the right. A positive turn about +X tilts it towards +Z,
        // which, looking at the east face from outside, is the viewer's left.
        float angle = SWEEP_DEGREES - 2 * SWEEP_DEGREES * be.renderedFill(partialTicks);
        boolean warning = be.gaugeWarning();
        float r = warning ? 0.85F : 0.12F;
        float g = warning ? 0.12F : 0.11F;
        float b = warning ? 0.08F : 0.10F;
        for (int side = 0; side < 2; side++) {
            ms.pushPose();
            if (side == 1) {
                // The west gauge: the east one turned half around.
                ms.rotateAround(Axis.YP.rotationDegrees(180), 0.5F, 0.5F, 0.5F);
            }
            ms.translate(PIVOT_X, PIVOT_Y, PIVOT_Z);
            ms.mulPose(Axis.XP.rotationDegrees(angle));
            ms.translate(-PIVOT_X, -PIVOT_Y, -PIVOT_Z);
            renderer.renderModel(ms.last(), consumer, null, ThumperClient.COMBUSTIVE_NEEDLE.get(), r, g, b, topLight,
                    OverlayTexture.NO_OVERLAY);
            ms.popPose();
        }

        if (be.lampLit()) {
            renderer.renderModel(ms.last(), consumer, null, ThumperClient.COMBUSTIVE_LAMP_LIT.get(), 1.0F, 1.0F, 1.0F,
                    LightTexture.FULL_BRIGHT, OverlayTexture.NO_OVERLAY);
        }
        ms.popPose();
    }
}
