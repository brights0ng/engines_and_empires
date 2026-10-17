package dev.brights0ng.meteodyne.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import dev.brights0ng.meteodyne.heat.world.HeatWorld;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;

/**
 * Tells the heat simulation (weather phase 8f-2) about every block change in a loaded chunk: its source index and its
 * regions' blocks. Block place and break events would miss explosions, pistons, {@code /fill} and Create contraptions
 * assembling and disassembling; everything goes through here. Split from Frontier's {@code LevelChunkFrontierMixin}
 * (split seam S4); Mixin merges both injections into the same method.
 *
 * <p>{@code setBlockState} returns the block that was there before, or null if nothing changed.
 */
@Mixin(LevelChunk.class)
public abstract class LevelChunkHeatMixin {

    @Inject(method = "setBlockState", at = @At("RETURN"))
    private void meteodyne$heatBlockChanged(BlockPos pos, BlockState state, boolean isMoving,
                                                      CallbackInfoReturnable<BlockState> cir) {
        BlockState before = cir.getReturnValue();
        if (before == null) {
            return;
        }
        HeatWorld.onBlockChanged((LevelChunk) (Object) this, pos, before, state);
    }
}
