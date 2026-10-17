package dev.brights0ng.frontier.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import dev.brights0ng.frontier.deep.SculkListeners;
import dev.brights0ng.frontier.tier.FrontierLevel;
import dev.brights0ng.frontier.tier.FrontierLevels;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;

/**
 * Tells Frontier about every block change in a loaded chunk, so it can keep count of anchor blocks (beds, lanterns,
 * workstations). Block place and break events would miss explosions, pistons, {@code /fill} and Create contraptions
 * assembling and disassembling; everything goes through here. Meteodyne's heat simulation hears of every change through
 * a mixin of its own on the same method ({@code LevelChunkHeatMixin}, split seam S4).
 *
 * <p>{@code setBlockState} returns the block that was there before, or null if nothing changed.
 */
@Mixin(LevelChunk.class)
public abstract class LevelChunkFrontierMixin {

    @Inject(method = "setBlockState", at = @At("RETURN"))
    private void frontier$countFrontierBlocks(BlockPos pos, BlockState state, boolean isMoving,
                                                         CallbackInfoReturnable<BlockState> cir) {
        BlockState before = cir.getReturnValue();
        if (before == null) {
            return;
        }
        LevelChunk chunk = (LevelChunk) (Object) this;
        FrontierLevel frontier = FrontierLevels.of(chunk.getLevel());
        if (frontier != null) {
            frontier.onBlockChanged(chunk, pos, before, state);
        }
        if ((SculkListeners.isListener(before) || SculkListeners.isListener(state)) && chunk.getLevel() instanceof ServerLevel server) {
            BlockPos at = pos.immutable();
            if (server.getServer().isSameThread()) {
                SculkListeners.of(server).onBlockChanged(at, before, state);
            } else {
                server.getServer().execute(() -> SculkListeners.of(server).onBlockChanged(at, before, state));
            }
        }
    }
}
