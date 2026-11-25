// Written by Gemini.
package dev.brights0ng.empire.civilian.ai;

import javax.annotation.Nullable;

import dev.brights0ng.empire.civilian.CivilianBeds;
import dev.brights0ng.empire.civilian.CivilianEntity;
import dev.brights0ng.empire.data.EmpireRecord;
import net.minecraft.core.BlockPos;

/**
 * Level-3 rough-sleep fallback task: walks to a standable spot by the main bonfire and lies down rough. Periodically
 * re-checks for a newly available {@code HOME} bed while walking ({@value #BED_RECHECK_INTERVAL} ticks) and performs
 * one final check upon arrival before lying down (preserving Bright's 2026-11-21 sleep rules).
 */
public final class RoughSleepTask implements CivilianTask {

    /** Ticks between HOME bed re-checks while walking to the bonfire. */
    public static final int BED_RECHECK_INTERVAL = 40;
    private static final double ROUGH_REACH = 1.5;

    @Nullable
    private BlockPos roughPos;
    @Nullable
    private MoveToAction move;
    private int recheckTicks;

    @Nullable
    public BlockPos targetPos() {
        return roughPos;
    }

    @Override
    public String name() {
        return "rough_sleep";
    }

    @Override
    public boolean isEligible(AiContext ctx) {
        CivilianEntity civ = ctx.civilian();
        return !civ.isLyingDown()
                && !civ.isUsingItem()
                && !civ.isSleepWakeCooldown()
                && civ.sleep().wantsToSleep(ctx.dayTime(), civ.sleepNeedTicks());
    }

    @Override
    public double baseWeight() {
        return 60.0;
    }

    @Override
    public double urgency(AiContext ctx) {
        return 1.0;
    }

    @Override
    public void start(AiContext ctx) {
        CivilianEntity civ = ctx.civilian();
        EmpireRecord empire = ctx.empire();
        recheckTicks = 0;
        if (empire == null) {
            roughPos = civ.blockPosition();
        } else {
            roughPos = CivilianBeds.roughSpot(ctx.level(), empire, civ.identityUuid(), civ.blockPosition());
        }
        move = new MoveToAction(roughPos, ROUGH_REACH, MoveToAction.DEFAULT_WALK_SPEED,
                null, null, BedSleepTask.REACH_TIMEOUT);
        move.start(ctx);
    }

    @Override
    public CivilianAction.Status tick(AiContext ctx) {
        CivilianEntity civ = ctx.civilian();
        if (civ.isLyingDown()) {
            return CivilianAction.Status.SUCCEEDED;
        }
        if (roughPos == null || move == null) {
            start(ctx);
        }
        if (roughPos.closerToCenterThan(civ.position(), ROUGH_REACH)) {
            if (BedSleepTask.acquireReachableBed(ctx) != null) {
                return CivilianAction.Status.FAILED;
            }
            civ.startRoughSleeping();
            return CivilianAction.Status.SUCCEEDED;
        }

        recheckTicks++;
        if (recheckTicks >= BED_RECHECK_INTERVAL) {
            recheckTicks = 0;
            if (BedSleepTask.acquireReachableBed(ctx) != null) {
                return CivilianAction.Status.FAILED;
            }
        }

        CivilianAction.Status status = move.tick(ctx);
        if (status == CivilianAction.Status.SUCCEEDED || status == CivilianAction.Status.FAILED) {
            if (BedSleepTask.acquireReachableBed(ctx) != null) {
                return CivilianAction.Status.FAILED;
            }
            civ.startRoughSleeping();
            return CivilianAction.Status.SUCCEEDED;
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
        return move != null ? move.name() : "sleep_rough";
    }
}
