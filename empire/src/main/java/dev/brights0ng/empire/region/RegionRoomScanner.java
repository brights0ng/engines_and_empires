// Written by Gemini.
package dev.brights0ng.empire.region;

import java.util.Set;
import java.util.function.BiPredicate;

import javax.annotation.Nullable;

import dev.brights0ng.empire.rules.RegionRooms;
import dev.brights0ng.empire.rules.RoomSeparator;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.state.BlockState;

/**
 * World-level room partitioner and optional indoor/outdoor labeller usable by any region type.
 * <p>
 * Separator blocks ({@link RoomSeparator}) are matched purely by block tag regardless of open/closed state, and
 * belong to neither room they border. When indoor/outdoor labelling is enabled, any room that has sky access or
 * touches a non-separator whitelisted block with sky access is labelled {@link dev.brights0ng.empire.rules.RoomLabel#OUTDOOR}.
 */
public final class RegionRoomScanner {

    /**
     * True if {@code state} matches any separator category in {@code enabled} (regardless of open/closed blockstate).
     */
    public static boolean isSeparator(BlockState state, Set<RoomSeparator> enabled) {
        if (enabled.isEmpty()) {
            return false;
        }
        if (enabled.contains(RoomSeparator.DOOR) && state.is(BlockTags.DOORS)) {
            return true;
        }
        if (enabled.contains(RoomSeparator.TRAPDOOR) && state.is(BlockTags.TRAPDOORS)) {
            return true;
        }
        return enabled.contains(RoomSeparator.FENCE_GATE) && state.is(BlockTags.FENCE_GATES);
    }

    /**
     * Partitions {@code regionBlocks} into 6-connected rooms separated by {@code separators}.
     * When {@code labelIndoorOutdoor} is true, labels each room {@code OUTDOOR} if it contains or touches a
     * non-separator block passing {@code isWhitelisted} that has direct sky exposure, and {@code INDOOR} otherwise.
     */
    public static RegionRooms.Partition partition(ServerLevel level,
                                                  Set<Long> regionBlocks,
                                                  Set<RoomSeparator> separators,
                                                  boolean labelIndoorOutdoor,
                                                  @Nullable BiPredicate<BlockPos, BlockState> isWhitelisted) {
        BlockPos.MutableBlockPos sepCursor = new BlockPos.MutableBlockPos();
        BlockPos.MutableBlockPos skyCursor = new BlockPos.MutableBlockPos();

        RegionRooms.BlockPredicate sepPred = (x, y, z) -> {
            sepCursor.set(x, y, z);
            if (!level.hasChunkAt(sepCursor)) {
                return false;
            }
            return isSeparator(level.getBlockState(sepCursor), separators);
        };

        RegionRooms.BlockPredicate skyPred = (!labelIndoorOutdoor || isWhitelisted == null)
                ? null
                : (x, y, z) -> {
                    skyCursor.set(x, y, z);
                    if (!level.hasChunkAt(skyCursor)) {
                        return false;
                    }
                    if (!HomeRegionScanner.isExposedToSky(level, x, y, z)) {
                        return false;
                    }
                    BlockState state = level.getBlockState(skyCursor);
                    if (isSeparator(state, separators)) {
                        return false;
                    }
                    return isWhitelisted.test(skyCursor, state);
                };

        return RegionRooms.partition(regionBlocks, sepPred, labelIndoorOutdoor, skyPred);
    }

    private RegionRoomScanner() {
    }
}
