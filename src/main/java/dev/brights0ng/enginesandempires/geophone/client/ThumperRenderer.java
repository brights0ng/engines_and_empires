package dev.brights0ng.enginesandempires.geophone.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.simibubi.create.AllPartialModels;
import com.simibubi.create.content.kinetics.base.KineticBlockEntityRenderer;

import dev.brights0ng.enginesandempires.geophone.MechanicalThumperBlockEntity;
import dev.engine_room.flywheel.api.visualization.VisualizationManager;
import net.createmod.catnip.render.CachedBuffers;
import net.createmod.catnip.render.SuperByteBuffer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.core.Direction;
import net.minecraft.core.Direction.Axis;
import net.minecraft.core.Direction.AxisDirection;
import net.minecraft.world.level.block.state.BlockState;

/**
 * The mechanical thumper's ordinary block entity renderer, modelled on Create's {@code MechanicalPressRenderer} and
 * {@code MechanicalMixerRenderer}.
 *
 * <p>With Flywheel on, {@link ThumperVisual} draws the cog and head, and this only draws the lit lamp (a tint that
 * changes every frame, which is simpler here than as an instance). With Flywheel off, this draws everything:
 * <ul>
 *   <li>the cog: Create's shaftless cogwheel, stood up onto the thumper's axis and spun with Create's usual kinetic
 *       transform, which also handles tooth alignment with neighbouring cogs and the overstress tint;</li>
 *   <li>the head: moved down from its fully wound position by {@link MechanicalThumperBlockEntity#getRenderedHeadOffset};</li>
 * </ul>
 */
public class ThumperRenderer extends KineticBlockEntityRenderer<MechanicalThumperBlockEntity> {

    public ThumperRenderer(BlockEntityRendererProvider.Context context) {
        super(context);
    }

    /** The head and prongs reach outside the block, so it must not be culled just because the block itself is off screen. */
    @Override
    public boolean shouldRenderOffScreen(MechanicalThumperBlockEntity be) {
        return true;
    }

    @Override
    protected void renderSafe(MechanicalThumperBlockEntity be, float partialTicks, PoseStack ms, MultiBufferSource buffer,
                              int light, int overlay) {
        BlockState state = be.getBlockState();
        if (!VisualizationManager.supportsVisualization(be.getLevel())) {
            VertexConsumer solid = buffer.getBuffer(RenderType.solid());

            Axis axis = getRotationAxisOf(be);
            Direction facing = Direction.fromAxisAndDirection(axis, AxisDirection.POSITIVE);
            SuperByteBuffer cog = CachedBuffers.partialFacingVertical(AllPartialModels.SHAFTLESS_COGWHEEL, state, facing);
            renderRotatingBuffer(be, cog, ms, solid, light);

            SuperByteBuffer head = CachedBuffers.partial(ThumperClient.HEAD, state);
            head.rotateCentered(ThumperClient.modelRotation(state))
                    .translate(0, -be.getRenderedHeadOffset(partialTicks), 0)
                    .light(light)
                    .renderInto(ms, solid);
        }
        renderLamp(be, state, ms, buffer);
    }

    /**
     * The lit lamp over the baked dark one: off the whole time it is winding, then on, at full brightness, the moment it
     * is armed, and off again when it is released. The server tells the client both at once, so it switches cleanly.
     */
    private static void renderLamp(MechanicalThumperBlockEntity be, BlockState state, PoseStack ms, MultiBufferSource buffer) {
        if (!be.isArmed()) {
            return;
        }
        ms.pushPose();
        ms.rotateAround(ThumperClient.modelRotation(state), 0.5F, 0.5F, 0.5F);
        VertexConsumer consumer = buffer.getBuffer(RenderType.entityCutoutNoCull(TextureAtlas.LOCATION_BLOCKS));
        Minecraft.getInstance().getBlockRenderer().getModelRenderer().renderModel(ms.last(), consumer, null,
                ThumperClient.LAMP_LIT.get(), 1.0F, 1.0F, 1.0F, LightTexture.FULL_BRIGHT,
                OverlayTexture.NO_OVERLAY);
        ms.popPose();
    }
}
