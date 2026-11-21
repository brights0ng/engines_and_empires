// Written by Gemini.
package dev.brights0ng.empire.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import dev.brights0ng.empire.region.RegionManager;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;

/**
 * Notifies {@link RegionManager} about every block change in a loaded Overworld chunk so regions update on explosions,
 * fire, pistons, {@code /setblock}, Create contraptions, and player edits alike (same pattern as Frontier's
 * {@code LevelChunkFrontierMixin} and Meteodyne's {@code LevelChunkHeatMixin}).
 *
 * <p>{@code setBlockState} returns the previous block state, or null if nothing changed.
 */
@Mixin(LevelChunk.class)
public abstract class LevelChunkRegionMixin {

    @Inject(method = "setBlockState", at = @At("RETURN"))
    private void empire$regionBlockChanged(BlockPos pos, BlockState state, boolean isMoving,
                                           CallbackInfoReturnable<BlockState> cir) {
        BlockState before = cir.getReturnValue();
        if (before == null) {
            return;
        }
        LevelChunk chunk = (LevelChunk) (Object) this;
        if (chunk.getLevel() instanceof ServerLevel server && server.dimension() == Level.OVERWORLD) {
            if (server.getServer().isSameThread()) {
                RegionManager.onBlockChanged(server, pos);
            } else {
                BlockPos at = pos.immutable();
                server.getServer().execute(() -> RegionManager.onBlockChanged(server, at));
            }
        }
    }
}
