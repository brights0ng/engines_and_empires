package dev.brights0ng.enginesandempires.geophone.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;

import dev.brights0ng.enginesandempires.geophone.GeophoneEntity;
import dev.brights0ng.enginesandempires.geophone.GeophoneGlow;
import dev.brights0ng.enginesandempires.geophone.GeophoneOrientation;
import dev.brights0ng.enginesandempires.geophone.GeophoneSlide;
import dev.brights0ng.enginesandempires.geophone.GeophoneTier;
import net.minecraft.client.resources.model.ModelResourceLocation;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.block.ModelBlockRenderer;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.resources.model.ModelManager;
import net.minecraft.resources.ResourceLocation;

/**
 * Draws a geophone from small block models: a body (the spike, the crossguard and the rod) and a sensor cap. Each tier has
 * its own set (see {@link GeophoneClient}); the andesite one always glows the same amber, while the brass one's glowing cap
 * is a plain, colourless texture that is tinted here, at render time, by whichever ore {@link GeophoneEntity#glowOreId()}
 * says it is showing (see {@link GeophoneGlow}). The models are drawn standing upright with the face of the block at their
 * y = 0, and turned here to point out of the face the geophone is staked into.
 *
 * <p>While the geophone is being pushed in, the whole thing is drawn held out along its axis by {@link GeophoneSlide}, and
 * slides home until the crossguard meets the face. That is only how it is drawn: the entity, and its hitbox, do not move.
 *
 * <p>The geophone is lit by the light in front of the face it is staked into. The cap is drawn at full brightness while the
 * geophone is glowing, so that it shows up in a dark cave or at night, which is what lets you see a pulse sweeping across
 * a field of them.
 */
public class GeophoneRenderer extends EntityRenderer<GeophoneEntity> {

    public GeophoneRenderer(EntityRendererProvider.Context context) {
        super(context);
        this.shadowRadius = 0.0F;
    }

    @Override
    public ResourceLocation getTextureLocation(GeophoneEntity entity) {
        return TextureAtlas.LOCATION_BLOCKS;
    }

    @Override
    public void render(GeophoneEntity entity, float entityYaw, float partialTick, PoseStack pose,
                       MultiBufferSource buffers, int packedLight) {
        GeophoneOrientation.Tilt tilt = GeophoneOrientation.tiltFor(
                entity.facing().getStepX(), entity.facing().getStepY(), entity.facing().getStepZ());

        pose.pushPose();
        if (tilt.xDegrees() != 0.0) {
            pose.mulPose(Axis.XP.rotationDegrees((float) tilt.xDegrees()));
        }
        if (tilt.zDegrees() != 0.0) {
            pose.mulPose(Axis.ZP.rotationDegrees((float) tilt.zDegrees()));
        }
        // The models are drawn inside one block, with the rod in the middle and the face of the block at y = 0. Put the rod
        // on the entity, and hold the whole thing out along its axis while it is still sliding in.
        double slide = GeophoneSlide.offset(entity.placedAge(partialTick));
        pose.translate(-0.5, slide, -0.5);

        ModelManager models = Minecraft.getInstance().getModelManager();
        ModelBlockRenderer renderer = Minecraft.getInstance().getBlockRenderer().getModelRenderer();
        VertexConsumer consumer = buffers.getBuffer(RenderType.entityCutoutNoCull(TextureAtlas.LOCATION_BLOCKS));

        boolean brass = entity.tier() == GeophoneTier.BRASS;
        ModelResourceLocation body = brass ? GeophoneClient.BRASS_BODY : GeophoneClient.BODY;
        ModelResourceLocation cap = brass ? GeophoneClient.BRASS_CAP : GeophoneClient.CAP;
        ModelResourceLocation capLit = brass ? GeophoneClient.BRASS_CAP_LIT : GeophoneClient.CAP_LIT;

        renderer.renderModel(pose.last(), consumer, null, models.getModel(body),
                1.0F, 1.0F, 1.0F, packedLight, OverlayTexture.NO_OVERLAY);
        boolean lit = entity.isLit();
        // Only a lit brass cap is ever tinted: its texture is colourless on purpose, and the dark cap and andesite's own
        // (already coloured) caps are always drawn at full strength.
        float[] tint = (brass && lit) ? GeophoneGlow.colorFor(entity.glowOreId()) : new float[]{1.0F, 1.0F, 1.0F};
        renderer.renderModel(pose.last(), consumer, null, models.getModel(lit ? capLit : cap),
                tint[0], tint[1], tint[2], lit ? LightTexture.FULL_BRIGHT : packedLight, OverlayTexture.NO_OVERLAY);
        pose.popPose();
    }
}
