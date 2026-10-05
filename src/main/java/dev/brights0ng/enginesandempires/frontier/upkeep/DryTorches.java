package dev.brights0ng.enginesandempires.frontier.upkeep;

import dev.brights0ng.enginesandempires.frontier.FrontierContent;
import net.minecraft.core.BlockPos;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.WallTorchBlock;
import net.minecraft.world.level.block.state.BlockState;

/** Putting torches out and lighting them again. */
public final class DryTorches {

    /** What a burning torch becomes when it goes out, facing the same way. */
    public static BlockState dryFor(BlockState burning) {
        if (burning.hasProperty(WallTorchBlock.FACING)) {
            return FrontierContent.DRY_WALL_TORCH.get().defaultBlockState()
                    .setValue(WallTorchBlock.FACING, burning.getValue(WallTorchBlock.FACING));
        }
        return FrontierContent.DRY_TORCH.get().defaultBlockState();
    }

    /** What a dry torch becomes when it is lit again, facing the same way. */
    public static BlockState litFor(BlockState dry) {
        if (dry.hasProperty(WallTorchBlock.FACING)) {
            return Blocks.WALL_TORCH.defaultBlockState().setValue(WallTorchBlock.FACING, dry.getValue(WallTorchBlock.FACING));
        }
        return Blocks.TORCH.defaultBlockState();
    }

    /** Lights a dry torch again. (The new torch's clock starts through the block-change hook, like any placed torch.) */
    static InteractionResult relight(BlockState dry, Level level, BlockPos pos) {
        if (!level.isClientSide) {
            level.setBlock(pos, litFor(dry), Block.UPDATE_ALL);
            level.playSound(null, pos, SoundEvents.FLINTANDSTEEL_USE, SoundSource.BLOCKS, 0.8F, 1.1F);
        }
        return InteractionResult.sidedSuccess(level.isClientSide);
    }

    private DryTorches() {
    }
}
