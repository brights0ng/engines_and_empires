// Written by Gemini.
package dev.brights0ng.empire.civilian;

import java.util.EnumSet;
import java.util.UUID;

import javax.annotation.Nullable;

import dev.brights0ng.empire.data.CivilianRecord;
import dev.brights0ng.empire.data.EmpireRecord;
import dev.brights0ng.empire.data.EmpireSavedData;
import dev.brights0ng.empire.rules.CivilianSleep;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.goal.Goal;

/**
 * Temporary stand-in for the scheduler's sleep task: from a citizen's own bedtime until rested or morning, walks to
 * its claimed bed (auto-claiming the nearest valid bed on empire land) and sleeps, or falls back to rough sleep next
 * to the bonfire when no bed is available, occupied, or unreachable.
 */
public class CivilianSleepGoal extends Goal {

    /** Ticks allowed to reach a bed before falling back to rough sleep, or a rough spot before lying down in place. */
    public static final int REACH_TIMEOUT = 600;
    private static final double WALK_SPEED = 0.6;
    private static final double BED_REACH = 2.0;
    private static final double ROUGH_REACH = 1.5;
    private static final int REPATH_INTERVAL = 20;

    private final CivilianEntity civilian;
    @Nullable
    private BlockPos targetPos;
    private boolean targetingBed;
    private int elapsedTicks;
    private int repathTicks;

    public CivilianSleepGoal(CivilianEntity civilian) {
        this.civilian = civilian;
        setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK, Flag.JUMP));
    }

    @Override
    public boolean canUse() {
        if (civilian.status() != CivilianEntity.Status.CITIZEN
                || civilian.isLyingDown()
                || civilian.isUsingItem()
                || civilian.isSleepWakeCooldown()) {
            return false;
        }
        if (!(civilian.level() instanceof ServerLevel serverLevel)) {
            return false;
        }
        return civilian.sleep().wantsToSleep(serverLevel.getDayTime(), civilian.sleepNeedTicks());
    }

    @Override
    public boolean canContinueToUse() {
        return canUse();
    }

    @Override
    public boolean requiresUpdateEveryTick() {
        return true;
    }

    @Override
    public void start() {
        targetPos = null;
        targetingBed = false;
        elapsedTicks = 0;
        repathTicks = 0;
        if (!(civilian.level() instanceof ServerLevel serverLevel)) {
            return;
        }
        EmpireRecord empire = empireOf(serverLevel);
        long night = CivilianSleep.nightIndex(serverLevel.getDayTime());
        if (empire == null) {
            targetPos = civilian.blockPosition();
            return;
        }
        if (!civilian.isRoughOnlyNight(night)) {
            BlockPos bed = CivilianBeds.ensureOrClaimBed(serverLevel, empire, civilian);
            if (bed != null && !CivilianBeds.isOccupied(serverLevel, bed)) {
                targetingBed = true;
                targetPos = bed;
                moveTowardTarget();
                return;
            }
            civilian.markRoughOnlyNight(night);
        }
        switchToRough(serverLevel, empire);
    }

    @Override
    public void stop() {
        targetPos = null;
        targetingBed = false;
        elapsedTicks = 0;
        repathTicks = 0;
    }

    @Override
    public void tick() {
        if (!(civilian.level() instanceof ServerLevel serverLevel)) {
            return;
        }
        EmpireRecord empire = empireOf(serverLevel);
        long night = CivilianSleep.nightIndex(serverLevel.getDayTime());
        elapsedTicks++;

        if (targetingBed) {
            if (empire == null || targetPos == null || !CivilianBeds.isValidClaimedBed(serverLevel, empire, targetPos)) {
                if (targetPos != null) {
                    CivilianBeds.releaseAndDetach(serverLevel, empire != null ? empire.id() : null,
                            civilian.civilianId(), targetPos);
                    civilian.setClaimedBed(null);
                }
                civilian.markRoughOnlyNight(night);
                switchToRough(serverLevel, empire);
                return;
            }
            if (CivilianBeds.isOccupied(serverLevel, targetPos)) {
                // Keep the claim; sleep rough for the rest of tonight.
                civilian.markRoughOnlyNight(night);
                switchToRough(serverLevel, empire);
                return;
            }
            if (targetPos.closerToCenterThan(civilian.position(), BED_REACH)) {
                civilian.startSleeping(targetPos);
                return;
            }
            if (elapsedTicks >= REACH_TIMEOUT) {
                civilian.markRoughOnlyNight(night);
                switchToRough(serverLevel, empire);
                return;
            }
            stepNavigation();
            return;
        }

        if (targetPos == null) {
            switchToRough(serverLevel, empire);
        }
        if (targetPos != null && (targetPos.closerToCenterThan(civilian.position(), ROUGH_REACH)
                || elapsedTicks >= REACH_TIMEOUT)) {
            civilian.startRoughSleeping();
            return;
        }
        stepNavigation();
    }

    private void switchToRough(ServerLevel serverLevel, @Nullable EmpireRecord empire) {
        targetingBed = false;
        elapsedTicks = 0;
        repathTicks = 0;
        if (empire == null) {
            targetPos = civilian.blockPosition();
        } else {
            UUID seed = civilian.civilianId() != null ? civilian.civilianId() : civilian.getUUID();
            targetPos = CivilianBeds.roughSpot(serverLevel, empire, seed, civilian.blockPosition());
        }
        moveTowardTarget();
    }

    private void stepNavigation() {
        repathTicks++;
        if (repathTicks >= REPATH_INTERVAL || civilian.getNavigation().isDone()) {
            repathTicks = 0;
            moveTowardTarget();
        }
    }

    private void moveTowardTarget() {
        if (targetPos != null) {
            civilian.getNavigation().moveTo(
                    targetPos.getX() + 0.5, targetPos.getY(), targetPos.getZ() + 0.5, WALK_SPEED);
        }
    }

    @Nullable
    private EmpireRecord empireOf(ServerLevel serverLevel) {
        EmpireSavedData data = EmpireSavedData.of(serverLevel);
        CivilianRecord record = civilian.ownRecord(data);
        return record != null ? data.getEmpire(record.empire()) : null;
    }
}
