// Written by Gemini.
package dev.brights0ng.sparseores.mixin.compat.dieselgenerators;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.jesz.createdieselgenerators.world.OilChunksSavedData;

import dev.brights0ng.sparseores.compat.dieselgenerators.OilDepositLedger;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;

/**
 * Intercepts Create: Diesel Generators oil queries and depletion, routing them
 * through Sparse Ores's multi-chunk shrinking reservoirs.
 */
@Pseudo
@Mixin(value = OilChunksSavedData.class, remap = false)
public abstract class OilChunksSavedDataMixin {

    @Shadow
    ServerLevel level;

    @Inject(method = "getChunkOilAmount(Lnet/minecraft/world/level/ChunkPos;)I", at = @At("HEAD"), cancellable = true)
    private void sparseores$getChunkOilAmount(ChunkPos chunkPos, CallbackInfoReturnable<Integer> cir) {
        if (level != null) {
            cir.setReturnValue(OilDepositLedger.get(level).getChunkOilAmount(chunkPos));
        }
    }

    @Inject(method = "setChunkAmount(Lnet/minecraft/world/level/ChunkPos;I)V", at = @At("HEAD"), cancellable = true)
    private void sparseores$setChunkAmount(ChunkPos chunkPos, int amount, CallbackInfo ci) {
        if (level != null) {
            OilDepositLedger.get(level).setChunkOilAmount(chunkPos, amount);
            ci.cancel();
        }
    }

    @Inject(method = "getBaseOilAmount(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/world/level/ChunkPos;)I", at = @At("HEAD"), cancellable = true)
    private static void sparseores$getBaseOilAmount(ServerLevel level, ChunkPos chunkPos, CallbackInfoReturnable<Integer> cir) {
        if (level != null) {
            cir.setReturnValue(OilDepositLedger.get(level).getChunkOilAmount(chunkPos));
        }
    }
}
