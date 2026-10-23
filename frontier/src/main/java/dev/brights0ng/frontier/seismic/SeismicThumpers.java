package dev.brights0ng.frontier.seismic;

import dev.brights0ng.frontier.incursion.ThumperAccess;
import dev.brights0ng.seismicsurveyance.logic.ChokedThumpers;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;

/** Seismic Surveyance's thumpers, for thumper incursions (split seam S6): {@link ChokedThumpers}. */
final class SeismicThumpers implements ThumperAccess {

    static final SeismicThumpers INSTANCE = new SeismicThumpers();

    @Override
    public boolean isThumper(BlockState state) {
        return ChokedThumpers.isThumper(state);
    }

    @Override
    public BlockPos basePos(BlockPos pos, BlockState state) {
        return ChokedThumpers.basePos(pos, state);
    }

    @Override
    public boolean choke(ServerLevel level, BlockPos pos) {
        return ChokedThumpers.choke(level, pos);
    }

    private SeismicThumpers() {
    }
}
