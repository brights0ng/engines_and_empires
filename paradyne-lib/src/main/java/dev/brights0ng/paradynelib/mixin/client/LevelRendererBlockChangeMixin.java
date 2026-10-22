// Written by Claude.
package dev.brights0ng.paradynelib.mixin.client;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import dev.brights0ng.paradynelib.client.ClientBlockChanges;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.state.BlockState;

/** Feeds {@link ClientBlockChanges}: every client-side block change reaches the renderer through this method. */
@Mixin(LevelRenderer.class)
public abstract class LevelRendererBlockChangeMixin {

    @Inject(method = "blockChanged", at = @At("HEAD"))
    private void paradyne_lib$clientBlockChanged(BlockGetter level, BlockPos pos, BlockState oldState, BlockState newState,
                                                 int flags, CallbackInfo ci) {
        ClientBlockChanges.fire(pos, oldState, newState);
    }
}
