// Written by Gemini.
package dev.brights0ng.empire.region;

import java.util.Set;
import java.util.UUID;

import javax.annotation.Nullable;

import dev.brights0ng.empire.Empire;
import dev.brights0ng.empire.civilian.CivilianBeds;
import dev.brights0ng.empire.data.EmpireRecord;
import dev.brights0ng.empire.data.EmpireSavedData;
import dev.brights0ng.empire.data.RegionRecord;
import dev.brights0ng.empire.rules.BlockKeys;
import dev.brights0ng.empire.rules.ChunkKeys;
import dev.brights0ng.empire.rules.RegionFloodFill;
import dev.brights0ng.empire.rules.RegionInvalidReason;
import dev.brights0ng.empire.rules.RegionOrigin;
import dev.brights0ng.empire.rules.RegionRules;
import dev.brights0ng.empire.rules.RegionType;
import it.unimi.dsi.fastutil.longs.LongIterator;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;

/**
 * Flood-fill scanner and validator for {@link RegionType#HOME} regions (D4, D5).
 */
public final class HomeRegionScanner {

    /** Whitelisted interior blocks for {@code HOME} flood fill ({@code empire:region_home_interior}). */
    public static final TagKey<Block> INTERIOR_TAG = TagKey.create(
            Registries.BLOCK, ResourceLocation.fromNamespaceAndPath(Empire.MODID, "region_home_interior"));

    /** Hazard blocks that abort {@code HOME} flood fill and invalidate existing homes ({@code empire:region_home_hazards}). */
    public static final TagKey<Block> HAZARDS_TAG = TagKey.create(
            Registries.BLOCK, ResourceLocation.fromNamespaceAndPath(Empire.MODID, "region_home_hazards"));

    /** Minimum block light required on spawnable floor spots (provisional default, D5). */
    public static final int MIN_BLOCK_LIGHT = 1;

    private static final int[] DX = {1, -1, 0, 0, 0, 0};
    private static final int[] DY = {0, 0, 1, -1, 0, 0};
    private static final int[] DZ = {0, 0, 0, 0, 1, -1};

    public record ScanResult(@Nullable RegionRecord region, RegionInvalidReason failure) {

        public boolean succeeded() {
            return region != null && failure == RegionInvalidReason.NONE;
        }
    }

    public record RescanResult(Set<Long> blocks, long seed, RegionInvalidReason failure) {

        public boolean succeeded() {
            return failure == RegionInvalidReason.NONE && !blocks.isEmpty();
        }
    }

    /**
     * True if {@code state} at {@code pos} is whitelisted as {@code HOME} interior: air, blocks in
     * {@code empire:region_home_interior} (including {@code #minecraft:climbable}), or any non-fluid block without
     * collision.
     */
    public static boolean isInterior(BlockGetter level, BlockPos pos, BlockState state) {
        if (state.isAir() || state.is(INTERIOR_TAG) || state.is(BlockTags.CLIMBABLE)) {
            return true;
        }
        return state.getFluidState().isEmpty() && state.getCollisionShape(level, pos).isEmpty();
    }

    public static boolean isHazard(BlockState state) {
        return state.is(HAZARDS_TAG);
    }

    /**
     * True if {@code (x, y, z)} has direct sky exposure (no motion-blocking block such as stone or glass above it).
     * Uses the synchronous {@link Heightmap.Types#MOTION_BLOCKING} heightmap so glass roofs/windows are allowed and
     * roof breaks invalidate immediately (D5).
     */
    public static boolean isExposedToSky(ServerLevel level, int x, int y, int z) {
        return y + 1 >= level.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z);
    }

    /** True if every chunk touched by {@code region} is currently loaded in {@code level}. */
    public static boolean areChunksLoaded(ServerLevel level, RegionRecord region) {
        LongIterator it = region.chunks().iterator();
        while (it.hasNext()) {
            long chunk = it.nextLong();
            if (!level.hasChunk(ChunkKeys.getX(chunk), ChunkKeys.getZ(chunk))) {
                return false;
            }
        }
        return true;
    }

    /**
     * Flood-fills a candidate {@link RegionType#HOME} starting at {@code start} and validates it against all D5 rules.
     * Does not register the resulting region in {@code data}; the caller decides whether to persist it.
     */
    public static ScanResult scan(ServerLevel level, EmpireSavedData data, EmpireRecord empire, BlockPos start,
                                  RegionOrigin origin) {
        RegionFloodFill.Result fill = floodFill(
                level, data, empire, start.getX(), start.getY(), start.getZ(), null);

        if (!fill.succeeded()) {
            return new ScanResult(null, fill.failure());
        }

        RegionRecord candidate = new RegionRecord(
                UUID.randomUUID(), empire.id(), RegionType.HOME, origin, start.asLong(), fill.blocks());
        RegionInvalidReason reason = validate(level, data, empire, candidate);
        if (reason != RegionInvalidReason.NONE) {
            return new ScanResult(null, reason);
        }
        return new ScanResult(candidate, RegionInvalidReason.NONE);
    }

    /**
     * Re-flood-fills an existing {@code AUTO} {@link RegionType#HOME} from its seed coordinate (migrating the seed to
     * another bed currently in {@code region}'s block set if the original seed is no longer a bed, or keeping it fixed
     * otherwise) and validates the resulting shape against all D5 rules.
     */
    public static RescanResult rescan(ServerLevel level, EmpireSavedData data, EmpireRecord empire,
                                      RegionRecord region) {
        long seed = resolveSeed(level, region);
        if (!empire.state().staked().contains(BlockKeys.chunkKey(seed))) {
            return new RescanResult(Set.of(), seed, RegionInvalidReason.UNSTAKED);
        }
        RegionFloodFill.Result fill = floodFill(
                level, data, empire, BlockKeys.getX(seed), BlockKeys.getY(seed), BlockKeys.getZ(seed), region.id());
        if (!fill.succeeded()) {
            return new RescanResult(Set.of(), seed, fill.failure());
        }
        RegionRecord candidate = new RegionRecord(
                region.id(), region.empireId(), region.type(), region.origin(), seed, fill.blocks());
        RegionInvalidReason reason = validate(level, data, empire, candidate);
        if (reason != RegionInvalidReason.NONE) {
            return new RescanResult(Set.of(), seed, reason);
        }
        return new RescanResult(fill.blocks(), seed, RegionInvalidReason.NONE);
    }

    /**
     * Resolves the flood-fill seed for {@code region}: keeps {@code region.seed()} if it is still a bed head,
     * otherwise migrates to another bed head already in {@code region.blocks()} (lowest packed coordinate for
     * determinism), or keeps {@code region.seed()} fixed if no other bed exists in the region.
     */
    public static long resolveSeed(ServerLevel level, RegionRecord region) {
        long currentSeed = region.seed();
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos(
                BlockKeys.getX(currentSeed), BlockKeys.getY(currentSeed), BlockKeys.getZ(currentSeed));
        if (level.hasChunkAt(cursor) && CivilianBeds.isBedHead(level.getBlockState(cursor))) {
            return currentSeed;
        }
        long migrated = Long.MAX_VALUE;
        boolean found = false;
        LongIterator it = region.blocks().iterator();
        while (it.hasNext()) {
            long key = it.nextLong();
            cursor.set(BlockKeys.getX(key), BlockKeys.getY(key), BlockKeys.getZ(key));
            if (level.hasChunkAt(cursor) && CivilianBeds.isBedHead(level.getBlockState(cursor))) {
                if (!found || key < migrated) {
                    migrated = key;
                    found = true;
                }
            }
        }
        return found ? migrated : currentSeed;
    }

    private static RegionFloodFill.Result floodFill(ServerLevel level, EmpireSavedData data, EmpireRecord empire,
                                                    int startX, int startY, int startZ,
                                                    @Nullable UUID ignoreRegionId) {
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        return RegionFloodFill.fill(
                startX, startY, startZ,
                RegionRules.HOME_MAX_BLOCKS,
                (x, y, z) -> {
                    cursor.set(x, y, z);
                    if (!level.hasChunkAt(cursor)) {
                        return RegionFloodFill.Cell.BOUNDARY;
                    }
                    BlockState state = level.getBlockState(cursor);
                    if (isHazard(state)) {
                        return RegionFloodFill.Cell.HAZARD;
                    }
                    long chunk = ChunkKeys.pack(x >> 4, z >> 4);
                    if (!empire.state().staked().contains(chunk)) {
                        return RegionFloodFill.Cell.BOUNDARY;
                    }
                    RegionRecord existing = data.regionAt(BlockKeys.pack(x, y, z));
                    if (existing != null && !existing.id().equals(ignoreRegionId)) {
                        return RegionFloodFill.Cell.BOUNDARY;
                    }
                    if (isExposedToSky(level, x, y, z)) {
                        return RegionFloodFill.Cell.BOUNDARY;
                    }
                    return isInterior(level, cursor, state)
                            ? RegionFloodFill.Cell.INCLUDE
                            : RegionFloodFill.Cell.BOUNDARY;
                });
    }

    /**
     * Validates an existing or candidate {@link RegionType#HOME} against all D5 rules:
     * volume, height, territory, disjointness, sky exposure, block light, hazards (inside or 6-adjacent), and bed presence.
     */
    public static RegionInvalidReason validate(ServerLevel level, EmpireSavedData data, EmpireRecord empire,
                                               RegionRecord region) {
        int count = region.blockCount();
        if (count < RegionRules.HOME_MIN_BLOCKS) {
            return RegionInvalidReason.TOO_SMALL;
        }
        if (count > RegionRules.HOME_MAX_BLOCKS) {
            return RegionInvalidReason.TOO_LARGE;
        }
        if (region.maxY() - region.minY() + 1 < RegionRules.HOME_MIN_HEIGHT) {
            return RegionInvalidReason.TOO_SHORT;
        }

        // Territory check: all touched chunks must remain staked by this empire.
        LongIterator chunkIt = region.chunks().iterator();
        while (chunkIt.hasNext()) {
            if (!empire.state().staked().contains(chunkIt.nextLong())) {
                return RegionInvalidReason.UNSTAKED;
            }
        }

        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        BlockPos.MutableBlockPos below = new BlockPos.MutableBlockPos();
        BlockPos.MutableBlockPos neighbor = new BlockPos.MutableBlockPos();
        boolean hasTallColumn = false;
        boolean hasBed = false;

        LongIterator it = region.blocks().iterator();
        while (it.hasNext()) {
            long key = it.nextLong();
            RegionRecord existing = data.regionAt(key);
            if (existing != null && !existing.id().equals(region.id())) {
                return RegionInvalidReason.OVERLAPS_EXISTING;
            }

            int x = BlockKeys.getX(key);
            int y = BlockKeys.getY(key);
            int z = BlockKeys.getZ(key);
            cursor.set(x, y, z);

            if (!hasTallColumn && region.containsBlock(BlockKeys.pack(x, y + 1, z))) {
                hasTallColumn = true;
            }

            if (isExposedToSky(level, x, y, z)) {
                return RegionInvalidReason.EXPOSED_TO_SKY;
            }

            BlockState state = level.getBlockState(cursor);
            if (isHazard(state)) {
                return RegionInvalidReason.HAZARD;
            }

            if (!hasBed && CivilianBeds.isBedHead(state)) {
                hasBed = true;
            }

            // 6-adjacent hazard check (interior cells are checked directly by the main loop).
            for (int i = 0; i < 6; i++) {
                int nx = x + DX[i];
                int ny = y + DY[i];
                int nz = z + DZ[i];
                if (region.containsBlock(BlockKeys.pack(nx, ny, nz))) {
                    continue;
                }
                neighbor.set(nx, ny, nz);
                if (level.hasChunkAt(neighbor) && isHazard(level.getBlockState(neighbor))) {
                    return RegionInvalidReason.HAZARD;
                }
            }

            // Monster-spawn lighting check on spawnable floor spots.
            if (state.getCollisionShape(level, cursor).isEmpty()
                    && !state.is(BlockTags.WOOL_CARPETS)
                    && !region.containsBlock(BlockKeys.pack(x, y - 1, z))) {
                below.set(x, y - 1, z);
                BlockState belowState = level.getBlockState(below);
                if (belowState.isFaceSturdy(level, below, Direction.UP)
                        && level.getBrightness(LightLayer.BLOCK, cursor) < MIN_BLOCK_LIGHT) {
                    return RegionInvalidReason.TOO_DARK;
                }
            }
        }

        if (!hasTallColumn) {
            return RegionInvalidReason.TOO_SHORT;
        }
        if (!hasBed) {
            return RegionInvalidReason.NO_BED;
        }
        return RegionInvalidReason.NONE;
    }

    private HomeRegionScanner() {
    }
}
