// Written by Gemini.
package dev.brights0ng.empire.civilian.ai;

import javax.annotation.Nullable;

import dev.brights0ng.empire.civilian.CivilianBeds;
import dev.brights0ng.empire.civilian.CivilianEntity;
import dev.brights0ng.empire.data.EmpireRecord;
import dev.brights0ng.empire.data.RegionRecord;
import dev.brights0ng.empire.rules.CivilianSleep;
import net.minecraft.core.BlockPos;

/**
 * Level-3 bed-sleep task: claims/validates a bed in a valid {@code HOME} region, walks to it (with
 * {@value #REACH_TIMEOUT}-tick timeout marking unreachable beds until their region updates), and enters bed sleep.
 */
public final class BedSleepTask implements CivilianTask {

    /** Ticks allowed to reach a bed before marking it unreachable and falling back to rough sleep. */
    public static final int REACH_TIMEOUT = 600;
    private static final double BED_REACH = 2.0;

    @Nullable
    private BlockPos bedPos;
    @Nullable
    private MoveToAction move;

    @Nullable
    public BlockPos targetPos() {
        return bedPos;
    }

    @Override
    public String name() {
        return "bed_sleep";
    }

    @Override
    public boolean isEligible(AiContext ctx) {
        CivilianEntity civ = ctx.civilian();
        if (civ.isLyingDown() || civ.isUsingItem() || civ.isSleepWakeCooldown()) {
            return false;
        }
        if (!civ.sleep().wantsToSleep(ctx.dayTime(), civ.sleepNeedTicks())) {
            return false;
        }
        BlockPos candidate = acquireReachableBed(ctx);
        if (candidate != null) {
            this.bedPos = candidate;
            return true;
        }
        this.bedPos = null;
        return false;
    }

    @Override
    public double baseWeight() {
        return 100.0;
    }

    @Override
    public double urgency(AiContext ctx) {
        return 1.0;
    }

    @Override
    public void start(AiContext ctx) {
        if (bedPos == null) {
            bedPos = acquireReachableBed(ctx);
        }
        if (bedPos != null) {
            move = new MoveToAction(bedPos, BED_REACH, MoveToAction.DEFAULT_WALK_SPEED, null, null, REACH_TIMEOUT);
            move.start(ctx);
        } else {
            move = null;
        }
    }

    @Override
    public CivilianAction.Status tick(AiContext ctx) {
        CivilianEntity civ = ctx.civilian();
        EmpireRecord empire = ctx.empire();
        long night = CivilianSleep.nightIndex(ctx.dayTime());

        if (civ.isSleeping()) {
            return CivilianAction.Status.SUCCEEDED;
        }
        if (empire == null || bedPos == null || !CivilianBeds.isValidClaimedBed(ctx.level(), empire, bedPos)) {
            if (bedPos != null) {
                CivilianBeds.releaseAndDetach(ctx.level(), empire != null ? empire.id() : null, civ.civilianId(), bedPos);
                civ.setClaimedBed(null);
                civ.clearUnreachableBed();
            }
            bedPos = null;
            return CivilianAction.Status.FAILED;
        }
        if (CivilianBeds.isOccupied(ctx.level(), bedPos)) {
            // Keep the bed claim; fall back to rough sleep for tonight.
            return CivilianAction.Status.FAILED;
        }
        if (bedPos.closerToCenterThan(civ.position(), BED_REACH)) {
            civ.clearUnreachableBed();
            civ.startSleeping(bedPos);
            return CivilianAction.Status.SUCCEEDED;
        }
        if (move == null) {
            move = new MoveToAction(bedPos, BED_REACH, MoveToAction.DEFAULT_WALK_SPEED, null, null, REACH_TIMEOUT);
            move.start(ctx);
        }
        CivilianAction.Status status = move.tick(ctx);
        if (status == CivilianAction.Status.SUCCEEDED) {
            civ.clearUnreachableBed();
            civ.startSleeping(bedPos);
            return CivilianAction.Status.SUCCEEDED;
        }
        if (status == CivilianAction.Status.FAILED) {
            RegionRecord region = ctx.data().regionAt(bedPos);
            civ.markUnreachableBed(night, bedPos, region);
            return CivilianAction.Status.FAILED;
        }
        return CivilianAction.Status.RUNNING;
    }

    @Override
    public void stop(AiContext ctx) {
        if (move != null) {
            move.stop(ctx);
            move = null;
        }
    }

    @Override
    public String activeActionName() {
        return move != null ? move.name() : "sleep_bed";
    }

    /** Attempts to validate or claim a non-occupied, non-unreachable bed inside a valid {@code HOME}. */
    @Nullable
    static BlockPos acquireReachableBed(AiContext ctx) {
        EmpireRecord empire = ctx.empire();
        if (empire == null) {
            return null;
        }
        CivilianEntity civ = ctx.civilian();
        long night = CivilianSleep.nightIndex(ctx.dayTime());
        BlockPos current = civ.claimedBed();
        if (current != null && CivilianBeds.isValidClaimedBed(ctx.level(), empire, current)
                && civ.isBedUnreachable(night, current, ctx.data().regionAt(current))) {
            return null;
        }
        BlockPos bed = CivilianBeds.ensureOrClaimBed(ctx.level(), empire, civ);
        if (bed == null || CivilianBeds.isOccupied(ctx.level(), bed)) {
            return null;
        }
        if (civ.isBedUnreachable(night, bed, ctx.data().regionAt(bed))) {
            return null;
        }
        return bed;
    }
}
