package dev.brights0ng.frontier.incursion;

import dev.brights0ng.paradynelib.services.Services;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;

/**
 * The thumpers a thumper incursion goes after, and choking them with sculk (split seam S6). Frontier knows no thumpers
 * itself: Seismic Surveyance's are supplied from {@code frontier.seismic} when it is installed. With none registered
 * there are no thumpers, so a thumper incursion has none to go after or choke.
 */
public interface ThumperAccess {

    /** No thumpers anywhere. */
    ThumperAccess NONE = new ThumperAccess() {
    };

    Services.Key<ThumperAccess> KEY = Services.key("frontier_thumpers", ThumperAccess.class, NONE);

    /** The registered access, or {@link #NONE}. */
    static ThumperAccess get() {
        return Services.get(KEY);
    }

    /** Whether {@code state} is a working thumper, or part of one. */
    default boolean isThumper(BlockState state) {
        return false;
    }

    /** The block a thumper at {@code pos} (any block of it) stands on its plate with. */
    default BlockPos basePos(BlockPos pos, BlockState state) {
        return pos;
    }

    /** Chokes the thumper at {@code pos} (any block of it). Returns whether there was a working thumper there. */
    default boolean choke(ServerLevel level, BlockPos pos) {
        return false;
    }
}
