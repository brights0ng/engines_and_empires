// Written by Gemini.
package dev.brights0ng.empire.civilian.ai;

import java.util.ArrayList;
import java.util.List;

import javax.annotation.Nullable;

import dev.brights0ng.empire.civilian.CivilianEntity;
import dev.brights0ng.empire.data.WorkAssignment;
import dev.brights0ng.empire.rules.CivilianPreferences.Activity;
import dev.brights0ng.empire.rules.CivilianSchedule;
import dev.brights0ng.empire.rules.CivilianSchedule.Anchor;
import dev.brights0ng.empire.rules.CivilianSchedule.Decision;
import dev.brights0ng.empire.rules.CivilianSchedule.PlannedSlot;
import dev.brights0ng.empire.rules.CivilianSchedule.SlotKind;
import dev.brights0ng.empire.rules.CivilianSleep;
import dev.brights0ng.empire.rules.LocationKey;
import dev.brights0ng.empire.rules.ScheduleClock;
import dev.brights0ng.empire.rules.TaskUtility;
import dev.brights0ng.empire.rules.TaskUtility.Candidate;
import net.minecraft.core.BlockPos;

/**
 * Level-2 Slot executor: manages travel to the slot's destination, utility-scored Task selection via
 * {@link TaskUtility} at decision boundaries, clean action/task yielding on deadline or quota completion (Bright D7),
 * and late-arrival anchor shifting.
 */
public final class CivilianSlotRunner {

    /** PROVISIONAL: safety cap on waiting for a clean action finish past {@code departAt} (10s = 200t). */
    public static final int CLEAN_FINISH_GRACE_TICKS = 200;
    private static final double TRAVEL_REACH = 3.0;
    private static final int TASK_RECHECK_INTERVAL = 40;

    private final LocationResolver locations;
    private final WorkSlotProvider workProvider;

    @Nullable
    private PlannedSlot activeSlot;
    @Nullable
    private MoveToAction travelAction;
    private List<CivilianTask> candidateTasks = List.of();
    @Nullable
    private CivilianTask activeTask;
    private int activeElapsedTicks;
    private int recheckTicks;
    private boolean yielding;
    private int yieldWaitTicks;
    private boolean travelFailedStuck;

    public CivilianSlotRunner(LocationResolver locations, WorkSlotProvider workProvider) {
        this.locations = locations;
        this.workProvider = workProvider;
    }

    public boolean hasActiveSlot() {
        return activeSlot != null;
    }

    @Nullable
    public PlannedSlot activeSlot() {
        return activeSlot;
    }

    @Nullable
    public CivilianTask activeTask() {
        return activeTask;
    }

    public boolean isTraveling() {
        return travelAction != null;
    }

    public boolean lastTravelFailedStuck() {
        return travelFailedStuck;
    }

    public void clearStuckFlag() {
        travelFailedStuck = false;
    }

    /** Starts executing a newly chosen {@link Decision} from Level 1. */
    public void startDecision(AiContext ctx, Decision decision, @Nullable LocationKey fromLocation) {
        stop(ctx);
        this.activeSlot = decision.slot();
        this.activeElapsedTicks = 0;
        this.recheckTicks = 0;
        this.yielding = false;
        this.yieldWaitTicks = 0;
        this.travelFailedStuck = false;
        this.candidateTasks = buildTasksFor(ctx, decision.slot());

        // SLEEP owns its own specialized bed/bonfire travel with unreachable-bed tracking and bed re-checks.
        if (decision.mustTravelFirst() && decision.slot().kind() != SlotKind.SLEEP) {
            BlockPos dest = locations.resolve(ctx, decision.destination());
            if (dest != null && !dest.closerToCenterThan(ctx.civilian().position(), TRAVEL_REACH)) {
                travelAction = new MoveToAction(dest, TRAVEL_REACH, MoveToAction.DEFAULT_WALK_SPEED,
                        fromLocation, decision.destination(), MoveToAction.DEFAULT_TIMEOUT_TICKS);
                travelAction.start(ctx);
                return;
            }
        }
        onArrivedAtSlot(ctx);
    }

    /**
     * Ticks the active Slot. Returns {@code true} while still running, or {@code false} when finished and Level 1
     * should call {@code decideNextSlot(...)}.
     */
    public boolean tick(AiContext ctx) {
        if (activeSlot == null) {
            return false;
        }
        CivilianEntity civ = ctx.civilian();
        CivilianSchedule schedule = civ.schedule();

        // 1. Travel phase
        if (travelAction != null) {
            CivilianAction.Status s = travelAction.tick(ctx);
            if (s == CivilianAction.Status.RUNNING) {
                if (travelAction.stuckTicks() >= MoveToAction.STUCK_TELEPORT_TICKS) {
                    travelFailedStuck = true;
                    stop(ctx);
                    return false;
                }
                return true;
            }
            boolean timedOut = travelAction.timedOut();
            boolean stuck = travelAction.stuckTicks() >= MoveToAction.STUCK_TELEPORT_TICKS / 2;
            travelAction.stop(ctx);
            travelAction = null;
            if (s == CivilianAction.Status.FAILED) {
                travelFailedStuck = timedOut || stuck;
                stop(ctx);
                return false;
            }
            // Arrived! Handle shiftable anchor or verify gap hasn't expired during travel.
            if (!onArrivedAtSlot(ctx)) {
                stop(ctx);
                return false;
            }
            return true;
        }

        // 2. Sleep completion check
        if (activeSlot.kind() == SlotKind.SLEEP) {
            long night = CivilianSleep.nightIndex(ctx.dayTime());
            if (civ.sleep().isRestedFor(night) || !civ.sleep().wantsToSleep(ctx.dayTime(), civ.sleepNeedTicks())) {
                if (activeSlot.isAnchor()) {
                    schedule.completeAnchor(activeSlot.anchorIndex());
                }
                stop(ctx);
                return false;
            }
            if (civ.isSleepWakeCooldown()) {
                return true;
            }
        }

        // 3. Check stop / yield conditions (deadline, quota, preemptive cap, outdoor window)
        if (!yielding && shouldStopSlot(ctx, activeSlot)) {
            yielding = true;
            yieldWaitTicks = 0;
        }

        if (yielding) {
            if (activeTask == null || activeTask.canYieldCleanly() || yieldWaitTicks >= CLEAN_FINISH_GRACE_TICKS) {
                finishSlot(ctx);
                return false;
            }
            yieldWaitTicks++;
        }

        // 4. Pick a Task via Level-2 TaskUtility if none active
        if (activeTask == null) {
            activeTask = pickBestTask(ctx, null);
            if (activeTask == null) {
                if (activeSlot.kind() == SlotKind.MEAL || activeSlot.kind() == SlotKind.WORK) {
                    finishSlot(ctx);
                    return false;
                }
                // Sleep while waiting on cooldown stays inside slot
                return activeSlot.kind() == SlotKind.SLEEP;
            }
            activeTask.start(ctx);
            recheckTicks = 0;
        }

        // 5. Tick active Level-3 Task
        activeElapsedTicks++;
        CivilianAction.Status taskStatus = activeTask.tick(ctx);

        if (taskStatus != CivilianAction.Status.RUNNING) {
            CivilianTask finished = activeTask;
            finished.stop(ctx);
            activeTask = null;

            if (activeSlot.kind() == SlotKind.MEAL && taskStatus == CivilianAction.Status.SUCCEEDED) {
                finishSlot(ctx);
                return false;
            }
            if (yielding || shouldStopSlot(ctx, activeSlot)) {
                finishSlot(ctx);
                return false;
            }
            // Pick next best task inside the same slot (e.g. BedSleepTask <-> RoughSleepTask handoff)
            CivilianTask next = pickBestTask(ctx, null);
            if (next == null) {
                if (activeSlot.kind() != SlotKind.SLEEP) {
                    finishSlot(ctx);
                    return false;
                }
                return true;
            }
            activeTask = next;
            activeTask.start(ctx);
            return true;
        }

        // Periodic light re-check for opportunistic higher-utility tasks inside SOCIALIZE (e.g. peer walked up)
        if (activeSlot.kind() == SlotKind.SOCIALIZE && activeTask.canYieldCleanly()) {
            recheckTicks++;
            if (recheckTicks >= TASK_RECHECK_INTERVAL) {
                recheckTicks = 0;
                CivilianTask better = pickBestTask(ctx, activeTask);
                if (better != null && better != activeTask) {
                    activeTask.stop(ctx);
                    activeTask = better;
                    activeTask.start(ctx);
                }
            }
        }
        return true;
    }

    public void stop(AiContext ctx) {
        if (travelAction != null) {
            travelAction.stop(ctx);
            travelAction = null;
        }
        if (activeTask != null) {
            activeTask.stop(ctx);
            activeTask = null;
        }
        activeSlot = null;
        candidateTasks = List.of();
        yielding = false;
        yieldWaitTicks = 0;
        activeElapsedTicks = 0;
    }

    public String describeState() {
        if (activeSlot == null) {
            return "idle";
        }
        String slotName = activeSlot.kind().id()
                + (activeSlot.hobby() != null ? ":" + activeSlot.hobby().id() : "")
                + (activeSlot.preemptiveBreak() ? "[break]" : "");
        if (travelAction != null) {
            return slotName + " -> travel -> " + travelAction.name();
        }
        String taskName = activeTask != null ? activeTask.name() : "none";
        String actionName = activeTask != null ? activeTask.activeActionName() : "none";
        return slotName + " -> " + taskName + " -> " + actionName;
    }

    private boolean onArrivedAtSlot(AiContext ctx) {
        if (activeSlot == null) {
            return false;
        }
        CivilianSchedule schedule = ctx.civilian().schedule();
        if (activeSlot.isAnchor()) {
            int idx = activeSlot.anchorIndex();
            if (idx >= 0 && idx < schedule.anchors().size()) {
                Anchor anchor = schedule.anchors().get(idx);
                if (anchor.shiftable() && ctx.tickOfDay() > anchor.startTick()) {
                    int travelToNext = 0;
                    for (int j = idx + 1; j < schedule.anchors().size(); j++) {
                        Anchor following = schedule.anchors().get(j);
                        if (!following.completed()) {
                            travelToNext = locations.estimateTravelTicks(ctx, anchor.location(), following.location());
                            break;
                        }
                    }
                    Anchor shifted = schedule.onArriveAtAnchor(idx, ctx.tickOfDay(), travelToNext);
                    if (shifted.completed()) {
                        return false;
                    }
                    activeSlot = new PlannedSlot(activeSlot.kind(), activeSlot.location(), activeSlot.hobby(),
                            idx, shifted.endTick(), activeSlot.preemptiveCapTicks(),
                            activeSlot.quotaDriven(), activeSlot.preemptiveBreak());
                }
            }
            return true;
        }
        // Dynamic gap slot: if travel delays ate the entire gap, abort immediately and let Level 1 re-plan
        return ctx.tickOfDay() < activeSlot.departAtTick();
    }

    private boolean shouldStopSlot(AiContext ctx, PlannedSlot slot) {
        int now = ctx.tickOfDay();
        CivilianSchedule schedule = ctx.civilian().schedule();
        if (slot.kind() == SlotKind.SLEEP) {
            long night = CivilianSleep.nightIndex(ctx.dayTime());
            return ctx.civilian().sleep().isRestedFor(night)
                    || !ctx.civilian().sleep().wantsToSleep(ctx.dayTime(), ctx.civilian().sleepNeedTicks());
        }
        if (now >= slot.departAtTick()) {
            return true;
        }
        if (slot.preemptiveBreak() && slot.preemptiveCapTicks() > 0 && activeElapsedTicks >= slot.preemptiveCapTicks()) {
            return true;
        }
        if (slot.kind() == SlotKind.WORK) {
            WorkAssignment work = ctx.civilian().workAssignment();
            if (work == null || schedule.remainingWorkTicks() <= 0) {
                return true;
            }
            if (!work.indoor() && !ScheduleClock.isOutdoorTime(now)) {
                return true;
            }
            if (work.isOccupation() && !locations.isAvailable(ctx, LocationKey.WORK)) {
                return true;
            }
        }
        if (slot.kind() == SlotKind.SOCIALIZE && schedule.remainingSocialTicks() <= 0) {
            return true;
        }
        if (slot.kind() == SlotKind.HOBBY && slot.location().isAlwaysOutdoor() && !ScheduleClock.isOutdoorTime(now)) {
            return true;
        }
        return false;
    }

    private void finishSlot(AiContext ctx) {
        if (activeSlot != null && activeSlot.isAnchor()) {
            ctx.civilian().schedule().completeAnchor(activeSlot.anchorIndex());
        }
        stop(ctx);
    }

    private List<CivilianTask> buildTasksFor(AiContext ctx, PlannedSlot slot) {
        return switch (slot.kind()) {
            case SLEEP -> List.of(new BedSleepTask(), new RoughSleepTask());
            case MEAL -> List.of(new EatScheduledMealTask());
            case WORK -> {
                WorkAssignment work = ctx.civilian().workAssignment();
                yield work != null ? workProvider.createWorkTasks(work, locations) : List.of();
            }
            case SOCIALIZE -> List.of(new ChatWithNearbyTask(), new GatherAtSocialSpotTask());
            case HOBBY -> {
                Activity act = slot.hobby() != null ? slot.hobby() : Activity.RELAX_AT_HOME;
                BlockPos focus = act == Activity.FISHING ? locations.resolveFishingSpot(ctx) : null;
                yield List.of(new PerformHobbyTask(act, locations, focus));
            }
            case RELAX -> List.of(new RelaxTask());
        };
    }

    @Nullable
    private CivilianTask pickBestTask(AiContext ctx, @Nullable CivilianTask current) {
        List<Candidate<CivilianTask>> scored = new ArrayList<>(candidateTasks.size());
        for (CivilianTask task : candidateTasks) {
            boolean eligible = task.isEligible(ctx);
            scored.add(new Candidate<>(
                    task,
                    eligible,
                    task.baseWeight(),
                    eligible ? task.urgency(ctx) : 0.0,
                    task == current,
                    TaskUtility.DEFAULT_MOMENTUM_BONUS
            ));
        }
        return TaskUtility.pickBest(scored);
    }
}
