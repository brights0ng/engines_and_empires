package dev.brights0ng.enginesandempires.mixin.client;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import dev.brights0ng.enginesandempires.weather.cloud.client.CloudRenderer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;

/**
 * Pushes the far clip plane out to the cloud draw distance while the voxel clouds are on. Vanilla's is 4x the render
 * distance (768 blocks at 12 chunks), which would cut distant clouds off. Depth precision depends almost only on the
 * near plane, so terrain doesn't lose any.
 */
@Mixin(GameRenderer.class)
public abstract class GameRendererCloudDepthMixin {

    @Inject(method = "getDepthFar", at = @At("RETURN"), cancellable = true)
    private void engines_and_empires$cloudDepth(CallbackInfoReturnable<Float> cir) {
        if (CloudRenderer.active(Minecraft.getInstance().level)) {
            cir.setReturnValue(Math.max(cir.getReturnValue(), CloudRenderer.depthFar()));
        }
    }
}
