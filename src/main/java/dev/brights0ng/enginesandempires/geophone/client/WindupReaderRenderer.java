package dev.brights0ng.enginesandempires.geophone.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;

import dev.brights0ng.enginesandempires.geophone.WindupReaderEntity;
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
 * Draws a placed wind-up reader from small block models: the brass body, with its crank, and a lamp on top. The models are
 * drawn inside one block with the reader's foot in the middle of the bottom, and turned here by the reader's yaw.
 *
 * <p>The lamp is drawn at full brightness while the reader holds a reading, so that it shows in the dark.
 */
public class WindupReaderRenderer extends EntityRenderer<WindupReaderEntity> {

    public WindupReaderRenderer(EntityRendererProvider.Context context) {
        super(context);
        this.shadowRadius = 0.25F;
    }

    @Override
    public ResourceLocation getTextureLocation(WindupReaderEntity entity) {
        return TextureAtlas.LOCATION_BLOCKS;
    }

    @Override
    public void render(WindupReaderEntity entity, float entityYaw, float partialTick, PoseStack pose,
                       MultiBufferSource buffers, int packedLight) {
        pose.pushPose();
        pose.mulPose(Axis.YP.rotationDegrees(-entityYaw));
        pose.translate(-0.5, 0.0, -0.5);

        ModelManager models = Minecraft.getInstance().getModelManager();
        ModelBlockRenderer renderer = Minecraft.getInstance().getBlockRenderer().getModelRenderer();
        VertexConsumer consumer = buffers.getBuffer(RenderType.entityCutoutNoCull(TextureAtlas.LOCATION_BLOCKS));

        renderer.renderModel(pose.last(), consumer, null, models.getModel(ReaderClient.BODY),
                1.0F, 1.0F, 1.0F, packedLight, OverlayTexture.NO_OVERLAY);
        boolean lit = entity.hasReading();
        renderer.renderModel(pose.last(), consumer, null, models.getModel(lit ? ReaderClient.LAMP_LIT : ReaderClient.LAMP),
                1.0F, 1.0F, 1.0F, lit ? LightTexture.FULL_BRIGHT : packedLight, OverlayTexture.NO_OVERLAY);
        pose.popPose();
    }
}
