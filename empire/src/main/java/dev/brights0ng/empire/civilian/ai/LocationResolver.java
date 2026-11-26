// Written by Gemini.
package dev.brights0ng.empire.civilian.ai;

import javax.annotation.Nullable;

import dev.brights0ng.empire.civilian.CivilianBeds;
import dev.brights0ng.empire.civilian.CivilianEntity;
import dev.brights0ng.empire.civilian.CivilianRoster;
import dev.brights0ng.empire.data.EmpireRecord;
import dev.brights0ng.empire.data.RegionRecord;
import dev.brights0ng.empire.data.WorkAssignment;
import dev.brights0ng.empire.rules.BlockKeys;
import dev.brights0ng.empire.rules.ChunkKeys;
import dev.brights0ng.empire.rules.LocationKey;
import dev.brights0ng.empire.rules.Occupation;
import dev.brights0ng.empire.rules.RegionType;
import dev.brights0ng.empire.rules.RouteMemory;
import it.unimi.dsi.fastutil.longs.LongIterator;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.levelgen.Heightmap;

import java.util.UUID;

/**
 * Resolves logical {@link LocationKey}s to concrete standable {@link BlockPos} targets on staked empire land and
 * keeps {@link RouteMemory} anchor positions current.
 */
public final class LocationResolver {

    /** Maximum squared distance to consider a citizen currently "at" a named location (6 blocks). */
    private static final double AT_LOCATION_DIST_SQ = 36.0;
    /** Horizontal radius around empire home to search for a staked fishing shore spot. */
    private static final int FISHING_SEARCH_RADIUS = 24;
    /** Horizontal radius around empire home for outdoor walking waypoints. */
    private static final int WALK_RADIUS = 20;

    @Nullable
    private BlockPos cachedFishingSpot;
    private long cachedFishingDay = Long.MIN_VALUE;

    /** Resolves {@code key} to a concrete target {@link BlockPos}, or {@code null} if currently unavailable. */
    @Nullable
    public BlockPos resolve(AiContext ctx, LocationKey key) {
        CivilianEntity civ = ctx.civilian();
        EmpireRecord empire = ctx.empire();
        ServerLevel level = ctx.level();
        UUID seed = civ.identityUuid();

        return switch (key) {
            case HOME -> resolveHome(ctx);
            case WORK -> resolveWork(ctx);
            case FOOD_SITE, SOCIAL_HUB -> resolveGatheringSpot(level, empire, seed, civ.blockPosition());
            case HOBBY_FISHING -> resolveFishingSpot(ctx);
            case HOBBY_READING, HOBBY_RELAX_HOME -> resolveIndoorComfortSpot(ctx);
            case HOBBY_WALK -> resolveWalkSpot(ctx, ctx.civilian().getRandom().nextInt(360));
        };
    }

    /**
     * Resolves {@link LocationKey#WORK} dynamically: for region-backed occupations (e.g. {@link Occupation#FARMER}),
     * checks that the workplace region is still valid and picks a standable block beside its seed shed; for
     * coordinate-only debug assignments, returns the fixed anchor.
     */
    @Nullable
    private BlockPos resolveWork(AiContext ctx) {
        WorkAssignment work = ctx.civilian().workAssignment();
        if (work == null) {
            return null;
        }
        if (work.isOccupation()) {
            if (work.occupation() == Occupation.FARMER) {
                RegionRecord farm = resolveValidFarm(ctx, work);
                return farm != null ? resolveFarmSeedShedStandSpot(ctx, farm) : null;
            }
            return null;
        }
        return work.anchor();
    }

    /** Returns the seed block of the farmer's assigned farm's seed shed (look target), or {@code null} if invalid. */
    @Nullable
    public BlockPos resolveFarmShedFocus(AiContext ctx) {
        WorkAssignment work = ctx.civilian().workAssignment();
        if (work == null || !work.isOccupation() || work.occupation() != Occupation.FARMER) {
            return null;
        }
        RegionRecord farm = resolveValidFarm(ctx, work);
        if (farm == null) {
            return null;
        }
        RegionRecord.RoomRecord shed = resolveSeedShedRoom(farm);
        return shed != null ? BlockPos.of(shed.seed()) : null;
    }

    /** Returns the seed shed room of {@code farm} (the room containing {@code farm.seed()}, or first {@code SHED}). */
    @Nullable
    public static RegionRecord.RoomRecord resolveSeedShedRoom(RegionRecord farm) {
        RegionRecord.RoomRecord seedRoom = farm.roomAt(farm.seed());
        if (seedRoom != null && seedRoom.isShed()) {
            return seedRoom;
        }
        for (RegionRecord.RoomRecord room : farm.rooms()) {
            if (room.isShed()) {
                return room;
            }
        }
        return null;
    }

    @Nullable
    private static RegionRecord resolveValidFarm(AiContext ctx, WorkAssignment work) {
        EmpireRecord empire = ctx.empire();
        if (empire == null || work.workplaceRegionId() == null) {
            return null;
        }
        RegionRecord region = ctx.data().region(work.workplaceRegionId());
        if (region == null || !region.valid() || region.type() != RegionType.FARM
                || !region.empireId().equals(empire.id())) {
            return null;
        }
        return region;
    }

    /**
     * Finds a deterministic standable block adjacent to (or within a short radius of) {@code farm}'s seed shed on
     * staked empire land. Because shed blocks themselves are solid workstations/storage, farmers stand right beside
     * the shed rather than inside it.
     */
    @Nullable
    public static BlockPos resolveFarmSeedShedStandSpot(AiContext ctx, RegionRecord farm) {
        RegionRecord.RoomRecord shed = resolveSeedShedRoom(farm);
        if (shed == null) {
            return null;
        }
        ServerLevel level = ctx.level();
        EmpireRecord empire = ctx.empire();
        BlockPos seedPos = BlockPos.of(shed.seed());
        if (!level.hasChunkAt(seedPos)) {
            return seedPos;
        }

        // Pass 1: immediate horizontal neighbor of any block in the seed shed (dy in {0, -1, +1}), closest to seedPos.
        BlockPos best = null;
        double bestDistSq = Double.MAX_VALUE;
        BlockPos.MutableBlockPos cand = new BlockPos.MutableBlockPos();
        LongIterator it = shed.blocks().iterator();
        while (it.hasNext()) {
            long key = it.nextLong();
            int bx = BlockKeys.getX(key);
            int by = BlockKeys.getY(key);
            int bz = BlockKeys.getZ(key);
            for (Direction dir : Direction.Plane.HORIZONTAL) {
                int nx = bx + dir.getStepX();
                int nz = bz + dir.getStepZ();
                for (int dy : new int[] {0, -1, 1}) {
                    int ny = by + dy;
                    long candKey = BlockKeys.pack(nx, ny, nz);
                    if (shed.blocks().contains(candKey)) {
                        continue;
                    }
                    if (empire != null && !empire.state().staked().contains(ChunkKeys.pack(nx >> 4, nz >> 4))) {
                        continue;
                    }
                    cand.set(nx, ny, nz);
                    if (level.hasChunkAt(cand) && CivilianBeds.isStandable(level, cand)) {
                        double d2 = cand.distSqr(seedPos);
                        if (best == null || d2 < bestDistSq || (d2 == bestDistSq && cand.asLong() < best.asLong())) {
                            best = cand.immutable();
                            bestDistSq = d2;
                        }
                    }
                }
            }
        }
        if (best != null) {
            return best;
        }

        // Pass 2: wider search around seedPos (radius 2..4) if the shed's immediate edges are walled off.
        for (int r = 2; r <= 4; r++) {
            for (int dx = -r; dx <= r; dx++) {
                for (int dz = -r; dz <= r; dz++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != r) {
                        continue;
                    }
                    int nx = seedPos.getX() + dx;
                    int nz = seedPos.getZ() + dz;
                    if (empire != null && !empire.state().staked().contains(ChunkKeys.pack(nx >> 4, nz >> 4))) {
                        continue;
                    }
                    for (int dy = -2; dy <= 2; dy++) {
                        int ny = seedPos.getY() + dy;
                        if (shed.blocks().contains(BlockKeys.pack(nx, ny, nz))) {
                            continue;
                        }
                        cand.set(nx, ny, nz);
                        if (level.hasChunkAt(cand) && CivilianBeds.isStandable(level, cand)) {
                            double d2 = cand.distSqr(seedPos);
                            if (best == null || d2 < bestDistSq || (d2 == bestDistSq && cand.asLong() < best.asLong())) {
                                best = cand.immutable();
                                bestDistSq = d2;
                            }
                        }
                    }
                }
            }
            if (best != null) {
                return best;
            }
        }
        return null;
    }

    /** True if {@code key} currently resolves to a concrete destination in the world. */
    public boolean isAvailable(AiContext ctx, LocationKey key) {
        return resolve(ctx, key) != null;
    }

    /** Returns the named {@link LocationKey} the citizen is currently at (within 6 blocks), or {@code null} if mid-road. */
    @Nullable
    public LocationKey detectCurrentLocation(AiContext ctx) {
        BlockPos pos = ctx.civilian().blockPosition();
        LocationKey best = null;
        double bestDistSq = AT_LOCATION_DIST_SQ;
        for (LocationKey key : LocationKey.values()) {
            if (key == LocationKey.HOBBY_WALK) {
                continue;
            }
            BlockPos anchor = resolve(ctx, key);
            if (anchor == null) {
                continue;
            }
            double d2 = pos.distSqr(anchor);
            if (d2 <= bestDistSq) {
                bestDistSq = d2;
                best = key;
            }
        }
        return best;
    }

    /** Refreshes {@link RouteMemory} anchor positions so relocating Home/Work auto-invalidates stale travel times. */
    public void syncRouteAnchors(AiContext ctx) {
        RouteMemory routes = ctx.civilian().routeMemory();
        for (LocationKey key : LocationKey.values()) {
            if (key == LocationKey.HOBBY_WALK) {
                continue;
            }
            BlockPos pos = resolve(ctx, key);
            if (pos != null) {
                routes.updateAnchor(key, pos.asLong());
            }
        }
    }

    /** Estimates travel ticks between two locations using {@link RouteMemory} and live resolved coordinates. */
    public int estimateTravelTicks(AiContext ctx, @Nullable LocationKey from, LocationKey to) {
        BlockPos toPos = resolve(ctx, to);
        if (toPos == null) {
            return 0;
        }
        BlockPos fromPos = from != null ? resolve(ctx, from) : null;
        if (fromPos == null) {
            fromPos = ctx.civilian().blockPosition();
        }
        return ctx.civilian().routeMemory().estimateTicks(
                from, to, fromPos.asLong(), toPos.asLong(), ctx.civilian().derivedStats().movementSpeed());
    }

    /** True if the citizen's current position is comfortable for relaxing (valid HOME, near lit bonfire, or sheltered). */
    public boolean isCurrentLocationComfortable(AiContext ctx) {
        CivilianEntity civ = ctx.civilian();
        BlockPos pos = civ.blockPosition();
        EmpireRecord empire = ctx.empire();
        if (empire != null) {
            RegionRecord region = ctx.data().regionAt(pos);
            if (region != null && region.empireId().equals(empire.id())
                    && region.type() == RegionType.HOME && region.valid() && region.isIndoorBlock(pos)) {
                return true;
            }
            BlockPos bonfire = empire.mainBonfire();
            if (bonfire != null && bonfire.closerThan(pos, 8.0)) {
                return true;
            }
        }
        return !ctx.level().isRainingAt(pos.above()) && !civ.isInWater();
    }

    /** Picks a safe standable walk waypoint on staked empire land near empire home. */
    @Nullable
    public BlockPos resolveWalkSpot(AiContext ctx, int angleDeg) {
        EmpireRecord empire = ctx.empire();
        ServerLevel level = ctx.level();
        BlockPos origin = empire != null
                ? CivilianRoster.homeFor(empire, level, ctx.civilian().getBlockY())
                : (ctx.civilian().home() != null ? ctx.civilian().home() : ctx.civilian().blockPosition());
        double rad = Math.toRadians(angleDeg);
        for (int radius : new int[] {12, 8, 16, WALK_RADIUS, 5}) {
            int x = origin.getX() + (int) Math.round(Math.cos(rad) * radius);
            int z = origin.getZ() + (int) Math.round(Math.sin(rad) * radius);
            if (empire != null && !empire.state().staked().contains(ChunkKeys.pack(x >> 4, z >> 4))) {
                continue;
            }
            if (!level.hasChunk(x >> 4, z >> 4)) {
                continue;
            }
            int surfaceY = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
            BlockPos candidate = new BlockPos(x, surfaceY, z);
            if (CivilianBeds.isStandable(level, candidate)) {
                return candidate;
            }
        }
        return resolveGatheringSpot(level, empire, ctx.civilian().identityUuid(), ctx.civilian().blockPosition());
    }

    /** Finds a nearby water block adjacent to a standable shoreline block on staked empire land (cached per day). */
    @Nullable
    public BlockPos resolveFishingSpot(AiContext ctx) {
        if (cachedFishingDay == ctx.scheduleDay()) {
            if (cachedFishingSpot != null && isValidFishingShore(ctx.level(), ctx.empire(), cachedFishingSpot)) {
                return cachedFishingSpot;
            }
        }
        cachedFishingDay = ctx.scheduleDay();
        cachedFishingSpot = scanFishingSpot(ctx);
        return cachedFishingSpot;
    }

    /** Returns an adjacent water block next to {@code shorePos} so a fishing citizen can face the water. */
    @Nullable
    public static BlockPos adjacentWater(ServerLevel level, BlockPos shorePos) {
        for (Direction dir : Direction.Plane.HORIZONTAL) {
            BlockPos side = shorePos.relative(dir);
            if (level.hasChunkAt(side) && (level.getFluidState(side).is(FluidTags.WATER)
                    || level.getFluidState(side.below()).is(FluidTags.WATER))) {
                return side;
            }
        }
        return null;
    }

    @Nullable
    private BlockPos resolveHome(AiContext ctx) {
        CivilianEntity civ = ctx.civilian();
        EmpireRecord empire = ctx.empire();
        BlockPos bed = civ.claimedBed();
        if (empire != null && bed != null && CivilianBeds.isValidClaimedBed(ctx.level(), empire, bed)) {
            return bed;
        }
        if (empire != null) {
            return CivilianBeds.roughSpot(ctx.level(), empire, civ.identityUuid(), civ.blockPosition());
        }
        return civ.home() != null ? civ.home() : civ.blockPosition();
    }

    @Nullable
    private BlockPos resolveIndoorComfortSpot(AiContext ctx) {
        CivilianEntity civ = ctx.civilian();
        EmpireRecord empire = ctx.empire();
        BlockPos bed = civ.claimedBed();
        if (empire != null && bed != null && CivilianBeds.isValidClaimedBed(ctx.level(), empire, bed)) {
            for (Direction dir : Direction.Plane.HORIZONTAL) {
                BlockPos beside = bed.relative(dir);
                if (CivilianBeds.isStandable(ctx.level(), beside)) {
                    return beside;
                }
            }
            return bed;
        }
        return resolveGatheringSpot(ctx.level(), empire, civ.identityUuid(), civ.blockPosition());
    }

    private static BlockPos resolveGatheringSpot(ServerLevel level, @Nullable EmpireRecord empire,
                                                 UUID seed, BlockPos fallback) {
        if (empire == null) {
            return fallback.immutable();
        }
        return CivilianBeds.roughSpot(level, empire, seed, fallback);
    }

    @Nullable
    private static BlockPos scanFishingSpot(AiContext ctx) {
        ServerLevel level = ctx.level();
        EmpireRecord empire = ctx.empire();
        BlockPos origin = empire != null
                ? CivilianRoster.homeFor(empire, level, ctx.civilian().getBlockY())
                : ctx.civilian().blockPosition();

        BlockPos best = null;
        double bestDistSq = Double.MAX_VALUE;
        for (int dx = -FISHING_SEARCH_RADIUS; dx <= FISHING_SEARCH_RADIUS; dx += 2) {
            for (int dz = -FISHING_SEARCH_RADIUS; dz <= FISHING_SEARCH_RADIUS; dz += 2) {
                int x = origin.getX() + dx;
                int z = origin.getZ() + dz;
                if (empire != null && !empire.state().staked().contains(ChunkKeys.pack(x >> 4, z >> 4))) {
                    continue;
                }
                if (!level.hasChunk(x >> 4, z >> 4)) {
                    continue;
                }
                int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
                BlockPos pos = new BlockPos(x, y, z);
                if (isValidFishingShore(level, empire, pos)) {
                    double d2 = pos.distSqr(origin);
                    if (d2 < bestDistSq) {
                        bestDistSq = d2;
                        best = pos;
                    }
                }
            }
        }
        return best;
    }

    private static boolean isValidFishingShore(ServerLevel level, @Nullable EmpireRecord empire, BlockPos pos) {
        if (empire != null && !empire.state().staked().contains(ChunkKeys.pack(pos.getX() >> 4, pos.getZ() >> 4))) {
            return false;
        }
        return CivilianBeds.isStandable(level, pos) && adjacentWater(level, pos) != null;
    }
}
