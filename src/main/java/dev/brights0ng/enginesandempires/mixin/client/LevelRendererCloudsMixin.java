package dev.brights0ng.enginesandempires.mixin.client;

import javax.annotation.Nullable;

import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.mojang.blaze3d.vertex.PoseStack;

import dev.brights0ng.enginesandempires.weather.cloud.client.CloudRenderer;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.LevelRenderer;

/** Hides vanilla's clouds while the voxel clouds draw the pack's instead. */
@Mixin(LevelRenderer.class)
public abstract class LevelRendererCloudsMixin {

    @Shadow
    @Nullable
    private ClientLevel level;

    @Inject(method = "renderClouds", at = @At("HEAD"), cancellable = true)
    private void engines_and_empires$hideVanillaClouds(PoseStack poseStack, Matrix4f frustumMatrix,
                                                       Matrix4f projectionMatrix, float partialTick, double camX,
                                                       double camY, double camZ, CallbackInfo ci) {
        if (CloudRenderer.active(this.level)) {
            ci.cancel();
        }
    }
}
