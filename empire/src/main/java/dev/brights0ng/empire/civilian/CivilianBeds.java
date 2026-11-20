// Written by Gemini.
package dev.brights0ng.empire.civilian;

import java.util.Optional;
import java.util.UUID;

import javax.annotation.Nullable;

import dev.brights0ng.empire.data.EmpireRecord;
import dev.brights0ng.empire.rules.ChunkKeys;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.entity.ai.village.poi.PoiManager;
import net.minecraft.world.entity.ai.village.poi.PoiTypes;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.phys.Vec3;

/**
 * Bed claiming, validation, rough-sleep spot selection and night-skip teleport targets for citizens.
 *
 * <p>Citizens auto-claim the nearest unclaimed vanilla {@link PoiTypes#HOME} bed within {@link #CLAIM_RADIUS} blocks
 * of their home on their empire's staked chunks until proper housing replaces auto-claim.
 */
public final class CivilianBeds {

    /** Maximum distance from a citizen's home to search for an unclaimed bed (confirmed). */
    public static final int CLAIM_RADIUS = 48;
    /** Minimum horizontal distance from the bonfire's centre for rough sleep (1 block outside its 3x3). */
    public static final int ROUGH_BONFIRE_MIN = 2;
    /** Maximum horizontal distance from the bonfire's centre for rough sleep (3 blocks outside its 3x3). */
    public static final int ROUGH_BONFIRE_MAX = 4;
    /** Horizontal ring around empire home when there is no main bonfire. */
    private static final int ROUGH_HOME_MIN = 1;
    private static final int ROUGH_HOME_MAX = 3;
    private static final int ANGLE_STEPS = 18;

    /** True if {@code pos} is on {@code empire}'s staked land and still holds a valid bed head POI/block. */
    public static boolean isValidClaimedBed(ServerLevel level, EmpireRecord empire, @Nullable BlockPos pos) {
        if (pos == null) {
            return false;
        }
        long chunk = ChunkKeys.pack(pos.getX() >> 4, pos.getZ() >> 4);
        if (!empire.state().staked().contains(chunk)) {
            return false;
        }
        if (level.hasChunkAt(pos)) {
            return isBedHead(level.getBlockState(pos));
        }
        return level.getPoiManager().exists(pos, type -> type.is(PoiTypes.HOME));
    }

    /** True if the loaded block state is a bed's head block. */
    public static boolean isBedHead(BlockState state) {
        return state.is(BlockTags.BEDS)
                && state.hasProperty(BedBlock.PART)
                && state.getValue(BedBlock.PART) == BedPart.HEAD;
    }

    /** True if {@code bedHead} is loaded and currently marked occupied. */
    public static boolean isOccupied(ServerLevel level, BlockPos bedHead) {
        if (!level.hasChunkAt(bedHead)) {
            return false;
        }
        BlockState state = level.getBlockState(bedHead);
        return state.hasProperty(BedBlock.OCCUPIED) && state.getValue(BedBlock.OCCUPIED);
    }

    /**
     * Claims the nearest unclaimed bed on {@code empire}'s staked chunks within {@link #CLAIM_RADIUS} blocks of
     * {@code home}, acquiring its vanilla {@link PoiTypes#HOME} ticket. Returns null if none is available.
     */
    @Nullable
    public static BlockPos claimNearest(ServerLevel level, EmpireRecord empire, BlockPos home) {
        PoiManager poi = level.getPoiManager();
        Optional<BlockPos> closest = poi.findClosest(
                type -> type.is(PoiTypes.HOME),
                pos -> isClaimable(level, empire, pos),
                home,
                CLAIM_RADIUS,
                PoiManager.Occupancy.HAS_SPACE);
        if (closest.isEmpty()) {
            return null;
        }
        BlockPos target = closest.get();
        Optional<BlockPos> taken = poi.take(
                type -> type.is(PoiTypes.HOME),
                (type, pos) -> pos.equals(target),
                target,
                1);
        return taken.map(BlockPos::immutable).orElse(null);
    }

    private static boolean isClaimable(ServerLevel level, EmpireRecord empire, BlockPos pos) {
        long chunk = ChunkKeys.pack(pos.getX() >> 4, pos.getZ() >> 4);
        if (!empire.state().staked().contains(chunk)) {
            return false;
        }
        return !level.hasChunkAt(pos) || isBedHead(level.getBlockState(pos));
    }

    /** Releases a previously claimed bed's POI ticket if the POI still exists. */
    public static void release(ServerLevel level, @Nullable BlockPos bedPos) {
        if (bedPos == null) {
            return;
        }
        PoiManager poi = level.getPoiManager();
        if (poi.exists(bedPos, type -> type.is(PoiTypes.HOME))) {
            poi.release(bedPos);
        }
    }

    /**
     * Validates a citizen's current bed claim (releasing it if broken or unstaked) and, if none is held, tries to claim
     * the nearest unclaimed bed on its empire's land.
     */
    @Nullable
    public static BlockPos ensureOrClaimBed(ServerLevel level, EmpireRecord empire, CivilianEntity civilian) {
        BlockPos current = civilian.claimedBed();
        if (current != null) {
            if (isValidClaimedBed(level, empire, current)) {
                return current;
            }
            release(level, current);
            civilian.setClaimedBed(null);
        }
        BlockPos home = civilian.home() != null ? civilian.home() : CivilianRoster.homeFor(empire, level, civilian.getBlockY());
        BlockPos claimed = claimNearest(level, empire, home);
        if (claimed != null) {
            civilian.setClaimedBed(claimed);
        }
        return claimed;
    }

    /**
     * Finds a standable rough-sleep spot 1-3 blocks outside the main bonfire's 3x3 footprint (or near empire home if
     * there is no main bonfire), spread around the anchor by a stable angle derived from {@code seed}.
     */
    public static BlockPos roughSpot(ServerLevel level, EmpireRecord empire, UUID seed, BlockPos fallback) {
        boolean hasBonfire = empire.mainBonfire() != null;
        BlockPos anchor = hasBonfire ? empire.mainBonfire() : CivilianRoster.homeFor(empire, level, fallback.getY());
        int minR = hasBonfire ? ROUGH_BONFIRE_MIN : ROUGH_HOME_MIN;
        int maxR = hasBonfire ? ROUGH_BONFIRE_MAX : ROUGH_HOME_MAX;
        double baseAngle = (Math.floorMod(seed.getMostSignificantBits() ^ seed.getLeastSignificantBits(), 3600L) / 3600.0)
                * Math.PI * 2.0;

        for (int radius = minR; radius <= maxR; radius++) {
            for (int step = 0; step < ANGLE_STEPS; step++) {
                int signed = step == 0 ? 0 : ((step % 2 == 1 ? 1 : -1) * ((step + 1) / 2));
                double angle = baseAngle + signed * (Math.PI * 2.0 / ANGLE_STEPS);
                int dx = (int) Math.round(Math.cos(angle) * radius);
                int dz = (int) Math.round(Math.sin(angle) * radius);
                if (hasBonfire && Math.abs(dx) <= 1 && Math.abs(dz) <= 1) {
                    continue;
                }
                BlockPos found = findStandableColumn(level, anchor.getX() + dx, anchor.getY(), anchor.getZ() + dz);
                if (found != null) {
                    return found;
                }
            }
        }
        return fallback.immutable();
    }

    @Nullable
    private static BlockPos findStandableColumn(ServerLevel level, int x, int baseY, int z) {
        int[] offsets = {0, 1, -1, 2, -2};
        for (int dy : offsets) {
            BlockPos pos = new BlockPos(x, baseY + dy, z);
            if (isStandable(level, pos)) {
                return pos;
            }
        }
        return null;
    }

    /** True if a civilian can stand or lie down safely at {@code pos}. */
    public static boolean isStandable(ServerLevel level, BlockPos pos) {
        if (!level.hasChunkAt(pos)) {
            return false;
        }
        BlockState feet = level.getBlockState(pos);
        BlockState head = level.getBlockState(pos.above());
        BlockState floor = level.getBlockState(pos.below());
        if (!feet.getCollisionShape(level, pos).isEmpty() || !feet.getFluidState().isEmpty()) {
            return false;
        }
        if (!head.getCollisionShape(level, pos.above()).isEmpty() || !head.getFluidState().isEmpty()) {
            return false;
        }
        if (feet.is(BlockTags.FIRE) || feet.is(BlockTags.CAMPFIRES) || head.is(BlockTags.FIRE)) {
            return false;
        }
        if (floor.getCollisionShape(level, pos.below()).isEmpty() || !floor.getFluidState().isEmpty()) {
            return false;
        }
        return !floor.is(Blocks.MAGMA_BLOCK) && !floor.is(Blocks.CACTUS)
                && !floor.is(BlockTags.FIRE) && !floor.is(BlockTags.CAMPFIRES);
    }

    /** Safe teleport destination beside a claimed bed (for night-skip D6 teleports). */
    public static Vec3 bedTeleportPos(ServerLevel level, BlockPos bedHead, CivilianEntity civilian) {
        if (level.hasChunkAt(bedHead)) {
            BlockState state = level.getBlockState(bedHead);
            if (state.is(BlockTags.BEDS) && state.hasProperty(BedBlock.FACING)) {
                Direction facing = state.getValue(BedBlock.FACING);
                Optional<Vec3> stand = BedBlock.findStandUpPosition(civilian.getType(), level, bedHead, facing, civilian.getYRot());
                if (stand.isPresent()) {
                    return stand.get();
                }
            }
        }
        return Vec3.atBottomCenterOf(bedHead.above());
    }

    private CivilianBeds() {
    }
}
