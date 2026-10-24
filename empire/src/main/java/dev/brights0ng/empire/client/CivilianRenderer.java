// Written by Claude.
package dev.brights0ng.empire.client;

import com.mojang.blaze3d.vertex.PoseStack;

import dev.brights0ng.empire.civilian.CivilianEntity;
import dev.brights0ng.empire.civilian.CivilianSpeechStore;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.PlayerModel;
import net.minecraft.client.model.geom.ModelLayers;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.HumanoidMobRenderer;
import net.minecraft.client.renderer.entity.layers.HumanoidArmorLayer;
import net.minecraft.resources.ResourceLocation;

/** A civilian as a slim player (outer layer on) in its composed skin, with its speech bubble above its name. */
public class CivilianRenderer extends HumanoidMobRenderer<CivilianEntity, PlayerModel<CivilianEntity>> {

    public CivilianRenderer(EntityRendererProvider.Context context) {
        super(context, new PlayerModel<>(context.bakeLayer(ModelLayers.PLAYER_SLIM), true), 0.5F);
        addLayer(new HumanoidArmorLayer<>(this,
                new HumanoidModel<>(context.bakeLayer(ModelLayers.PLAYER_SLIM_INNER_ARMOR)),
                new HumanoidModel<>(context.bakeLayer(ModelLayers.PLAYER_SLIM_OUTER_ARMOR)),
                context.getModelManager()));
    }

    @Override
    protected void scale(CivilianEntity civilian, PoseStack poseStack, float partialTick) {
        // Same as players.
        poseStack.scale(0.9375F, 0.9375F, 0.9375F);
    }

    @Override
    public ResourceLocation getTextureLocation(CivilianEntity civilian) {
        return CivilianSkinCache.get(civilian.appearance());
    }

    @Override
    public void render(CivilianEntity civilian, float entityYaw, float partialTick, PoseStack poseStack,
                       MultiBufferSource buffer, int packedLight) {
        super.render(civilian, entityYaw, partialTick, poseStack, buffer, packedLight);
        CivilianSpeechStore.Bubble bubble = CivilianSpeechStore.get(civilian.getId());
        if (bubble != null) {
            CivilianBubbleRenderer.render(civilian, bubble, entityRenderDispatcher, getFont(), partialTick, poseStack,
                    buffer, packedLight);
        }
    }
}
