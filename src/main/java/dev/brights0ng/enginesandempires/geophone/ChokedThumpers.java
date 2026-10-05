package dev.brights0ng.enginesandempires.geophone;

import com.simibubi.create.content.kinetics.base.HorizontalAxisKineticBlock;

import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/** Finding thumpers and choking them with sculk (Frontier phase 6 incursions). */
public final class ChokedThumpers {

    /** Whether {@code state} is a working thumper, or part of one. */
    public static boolean isThumper(BlockState state) {
        return state.is(SeismicContent.MECHANICAL_THUMPER.get()) || state.is(SeismicContent.COMBUSTIVE_THUMPER.get())
                || state.is(SeismicContent.COMBUSTIVE_THUMPER_COLUMN.get());
    }

    /** The block a thumper at {@code pos} stands on the plate with: the combustive column's base, or the block itself. */
    public static BlockPos basePos(BlockPos pos, BlockState state) {
        return state.is(SeismicContent.COMBUSTIVE_THUMPER_COLUMN.get()) ? CombustiveThumperColumnBlock.basePos(pos, state) : pos;
    }

    /**
     * Chokes the thumper at {@code pos} (any block of it): it becomes its choked block, keeping its axis. A combustive one
     * loses its column, and the fuel in its tank. Returns whether there was a working thumper there.
     */
    public static boolean choke(ServerLevel level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        if (!isThumper(state)) {
            return false;
        }
        BlockPos base = basePos(pos, state);
        BlockState baseState = level.getBlockState(base);
        Block choked;
        if (baseState.is(SeismicContent.MECHANICAL_THUMPER.get())) {
            choked = SeismicContent.CHOKED_MECHANICAL_THUMPER.get();
        } else if (baseState.is(SeismicContent.COMBUSTIVE_THUMPER.get())) {
            choked = SeismicContent.CHOKED_COMBUSTIVE_THUMPER.get();
        } else {
            return false;
        }
        BlockState result = choked.defaultBlockState()
                .setValue(ChokedThumperBlock.HORIZONTAL_AXIS, baseState.getValue(HorizontalAxisKineticBlock.HORIZONTAL_AXIS));
        // With every update, so the column above (which needs a working base) falls away on its own.
        level.setBlock(base, result, Block.UPDATE_ALL);
        level.sendParticles(new BlockParticleOption(ParticleTypes.BLOCK, Blocks.SCULK.defaultBlockState()),
                base.getX() + 0.5, base.getY() + 1.0, base.getZ() + 0.5, 40, 0.5, 0.5, 0.5, 0.1);
        level.playSound(null, base, SoundEvents.SCULK_CATALYST_BLOOM, SoundSource.BLOCKS, 2.0F, 0.6F);
        return true;
    }

    private ChokedThumpers() {
    }
}
