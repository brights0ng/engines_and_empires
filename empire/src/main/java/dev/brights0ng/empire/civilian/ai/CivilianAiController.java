// Written by Gemini.
package dev.brights0ng.empire.civilian.ai;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import javax.annotation.Nullable;

import dev.brights0ng.empire.EmpireConfig;
import dev.brights0ng.empire.civilian.CivilianEntity;
import dev.brights0ng.empire.civilian.CivilianFulfilmentMeters;
import dev.brights0ng.empire.data.CivilianRecord;
import dev.brights0ng.empire.data.EmpireRecord;
import dev.brights0ng.empire.data.EmpireSavedData;
import dev.brights0ng.empire.data.WorkAssignment;
import dev.brights0ng.empire.rules.CivilianSchedule;
import dev.brights0ng.empire.rules.CivilianSchedule.Anchor;
import dev.brights0ng.empire.rules.CivilianSchedule.Decision;
import dev.brights0ng.empire.rules.CivilianSchedule.PlanContext;
import dev.brights0ng.empire.rules.CivilianSchedule.SkeletonInput;
import dev.brights0ng.empire.rules.CivilianSleep;
import dev.brights0ng.empire.rules.FulfilmentModel.Evaluation;
import dev.brights0ng.empire.rules.FulfilmentModel.Meter;
import dev.brights0ng.empire.rules.LocationKey;
import dev.brights0ng.empire.rules.RouteMemory;
import dev.brights0ng.empire.rules.ScheduleClock;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;

/**
 * Level-1 Civilian AI controller (citizens only): maintains the daily Anchor Skeleton, invokes the live Gap-Filler at
 * decision points, and drives {@link CivilianSlotRunner}.
 *
 * <p>Interrupt hooks (dialogue pause, hostile flee, low-hunger snack, extreme Meteodyne temperature, stuck recovery)
 * live cleanly above the slot runner and pause/resume without corrupting today's skeleton.
 */
public final class CivilianAiController {

    /** PROVISIONAL: minimum low-hunger emergency threshold (right where Fulfilment's Hunger meter starts bleeding). */
    public static final int EMERGENCY_FOOD_FLOOR = 10;
    private static final long TIME_JUMP_THRESHOLD = 100L;
    private static final int ANCHOR_SYNC_INTERVAL = 200;

    private final CivilianEntity civilian;
    private final LocationResolver locations = new LocationResolver();
    private final CivilianSlotRunner slotRunner;

    private long lastDayTime = Long.MIN_VALUE;
    private int syncTicks;
    @Nullable
    private String lastDecisionReason = "none";

    public CivilianAiController(CivilianEntity civilian) {
        this(civilian, WorkSlotProvider.DEFAULT);
    }

    public CivilianAiController(CivilianEntity civilian, WorkSlotProvider workProvider) {
        this.civilian = civilian;
        this.slotRunner = new CivilianSlotRunner(locations, workProvider);
    }

    public LocationResolver locations() {
        return locations;
    }

    public CivilianSlotRunner slotRunner() {
        return slotRunner;
    }

    /** Emergency food level threshold at or below which a citizen eats immediately outside scheduled mealtime. */
    public int emergencyFoodThreshold() {
        return Math.max(EMERGENCY_FOOD_FLOOR, civilian.derivedStats().hungerPenaltyThreshold());
    }

    /** True while actively targeting a bed in {@link BedSleepTask} (for debugInfoLines compatibility). */
    public boolean isWalkingToBed() {
        return slotRunner.activeTask() instanceof BedSleepTask b && b.targetPos() != null;
    }

    /** True while actively targeting a bonfire rough spot in {@link RoughSleepTask}. */
    public boolean isWalkingToRoughSpot() {
        return slotRunner.activeTask() instanceof RoughSleepTask r && r.targetPos() != null;
    }

    @Nullable
    public BlockPos sleepTargetPos() {
        if (slotRunner.activeTask() instanceof BedSleepTask b) {
            return b.targetPos();
        }
        if (slotRunner.activeTask() instanceof RoughSleepTask r) {
            return r.targetPos();
        }
        return null;
    }

    /** Called by the existing 100-tick community scan when another citizen or member is seen nearby. */
    public void recordNearbyPersonSighting(ServerLevel level, @Nullable EmpireRecord empire) {
        AiContext ctx = AiContext.of(level, civilian, EmpireSavedData.of(level), empire);
        LocationKey here = locations.detectCurrentLocation(ctx);
        if (here != null) {
            civilian.crowdMemory().recordSighting(here, ctx.scheduleDay(), ctx.tickOfDay());
        }
    }

    /** Forces immediate regeneration of today's Anchor Skeleton and cancels the current slot. */
    public void forceRebuildSchedule(ServerLevel level) {
        EmpireSavedData data = EmpireSavedData.of(level);
        CivilianRecord record = civilian.ownRecord(data);
        EmpireRecord empire = record != null ? data.getEmpire(record.empire()) : null;
        AiContext ctx = AiContext.of(level, civilian, data, empire);
        slotRunner.stop(ctx);
        rebuildSkeleton(ctx);
        civilian.syncNeedsToRecord();
    }

    /** Called when a citizen's {@link WorkAssignment} changes mid-day so quotas and active slot update cleanly. */
    public void onWorkAssignmentChanged(ServerLevel level) {
        EmpireSavedData data = EmpireSavedData.of(level);
        CivilianRecord record = civilian.ownRecord(data);
        EmpireRecord empire = record != null ? data.getEmpire(record.empire()) : null;
        AiContext ctx = AiContext.of(level, civilian, data, empire);
        if (civilian.schedule().needsRebuild(ctx.scheduleDay())) {
            rebuildSkeleton(ctx);
        } else {
            Evaluation eval = CivilianFulfilmentMeters.evaluate(level, civilian);
            civilian.schedule().updateWorkQuota(makeSkeletonInput(ctx, eval));
        }
        slotRunner.stop(ctx);
        civilian.syncNeedsToRecord();
    }

    /** Stops any active slot/action cleanly (e.g. when entering dialogue, leaving, or sleeping). */
    public void stopAll(ServerLevel level) {
        EmpireSavedData data = EmpireSavedData.of(level);
        CivilianRecord record = civilian.ownRecord(data);
        EmpireRecord empire = record != null ? data.getEmpire(record.empire()) : null;
        slotRunner.stop(AiContext.of(level, civilian, data, empire));
    }

    /** One server tick of the 4-level Citizen AI (invoked by {@link CivilianAiGoal}). */
    public void tick(ServerLevel level) {
        if (civilian.status() != CivilianEntity.Status.CITIZEN || !civilian.isAlive() || civilian.isLyingDown()) {
            return;
        }
        // Dialogue pauses the AI cleanly without discarding today's skeleton or active slot progress
        if (civilian.dialoguePlayer() != null) {
            return;
        }

        EmpireSavedData data = EmpireSavedData.of(level);
        CivilianRecord record = civilian.ownRecord(data);
        EmpireRecord empire = record != null ? data.getEmpire(record.empire()) : null;
        AiContext ctx = AiContext.of(level, civilian, data, empire);

        // Refresh route anchors periodically so moving home/work invalidates old learned travel times
        syncTicks++;
        if (syncTicks >= ANCHOR_SYNC_INTERVAL) {
            syncTicks = 0;
            locations.syncRouteAnchors(ctx);
        }

        // Detect day rollover or large forward/backward /time jumps
        boolean timeJumped = lastDayTime != Long.MIN_VALUE && Math.abs(ctx.dayTime() - lastDayTime) > TIME_JUMP_THRESHOLD;
        lastDayTime = ctx.dayTime();

        CivilianSchedule schedule = civilian.schedule();
        if (schedule.needsRebuild(ctx.scheduleDay())) {
            slotRunner.stop(ctx);
            locations.syncRouteAnchors(ctx);
            rebuildSkeleton(ctx);
        } else if (timeJumped) {
            slotRunner.stop(ctx);
            schedule.expireSkippedAnchors(ctx.tickOfDay());
        } else {
            // Even on normal ticks, expire any earlier anchor once a later anchor's start arrives
            if (schedule.expireSkippedAnchors(ctx.tickOfDay())) {
                slotRunner.stop(ctx);
            }
        }

        // If no slot is active, run Level 1 decideNextSlot once
        if (!slotRunner.hasActiveSlot()) {
            planNextSlot(ctx);
        }

        // Tick Level 2 -> 3 -> 4
        if (!slotRunner.tick(ctx)) {
            planNextSlot(ctx);
        }
    }

    private void rebuildSkeleton(AiContext ctx) {
        Evaluation eval = CivilianFulfilmentMeters.evaluate(ctx.level(), civilian);
        civilian.schedule().buildDailySkeleton(makeSkeletonInput(ctx, eval));
    }

    private SkeletonInput makeSkeletonInput(AiContext ctx, Evaluation eval) {
        long night = CivilianSleep.nightIndex(ctx.dayTime());
        int sleptSoFar = civilian.sleep().hasOpenSession() && civilian.sleep().sessionNight() == night
                ? civilian.sleep().sleptTicks() : 0;
        boolean rested = civilian.sleep().isRestedFor(night);
        WorkAssignment work = civilian.workAssignment();
        return new SkeletonInput(
                civilian.identityUuid(),
                ctx.scheduleDay(),
                ctx.tickOfDay(),
                civilian.sleepNeedTicks(),
                sleptSoFar,
                rested,
                civilian.traits(),
                civilian.derivedStats(),
                eval.fulfilment(),
                civilian.fulfilment().grievance(),
                EmpireConfig.fulfilmentGrievanceThreshold(),
                eval.meterValue(Meter.SOCIALIZING),
                work != null,
                work != null ? work.dailyAskTicks() : 0,
                civilian.crowdMemory()
        );
    }

    private void planNextSlot(AiContext ctx) {
        Evaluation eval = CivilianFulfilmentMeters.evaluate(ctx.level(), civilian);
        LocationKey here = locations.detectCurrentLocation(ctx);
        WorkAssignment work = civilian.workAssignment();
        boolean someoneNearby = ChatWithNearbyTask.findNearestSocialPartner(ctx) != null;

        PlanContext planCtx = new PlanContext(
                civilian.identityUuid(),
                ctx.scheduleDay(),
                ctx.tickOfDay(),
                here,
                work != null,
                work != null && !work.indoor(),
                someoneNearby,
                locations.isCurrentLocationComfortable(ctx),
                civilian.fulfilment().preferences().favouriteActivities(),
                eval.fulfilment(),
                civilian.fulfilment().grievance(),
                EmpireConfig.fulfilmentGrievanceThreshold(),
                civilian.traits(),
                (from, to) -> locations.estimateTravelTicks(ctx, from, to),
                civilian.crowdMemory(),
                key -> locations.isAvailable(ctx, key)
        );

        Decision decision = civilian.schedule().decideNextSlot(planCtx);
        lastDecisionReason = decision.reason();
        slotRunner.startDecision(ctx, decision, here);
    }

    /** Compact single-line summary included in {@code /empire debug civilian info} and sneak + right-click stats. */
    public Component formatSummaryLine(ServerLevel level) {
        int tickOfDay = ScheduleClock.timeOfDay(level.getDayTime());
        return Component.literal("AI: " + slotRunner.describeState()
                + " | " + civilian.schedule().summaryLine(tickOfDay));
    }

    /** Full schedule diagnostic report for {@code /empire debug civilian schedule}. */
    public List<Component> formatScheduleReport(ServerLevel level) {
        List<Component> lines = new ArrayList<>();
        int tickOfDay = ScheduleClock.timeOfDay(level.getDayTime());
        CivilianSchedule s = civilian.schedule();
        lines.add(Component.literal("=== Schedule: " + civilian.getName().getString()
                + " (" + civilian.status().name() + ") ==="));
        lines.add(Component.literal("Active: " + slotRunner.describeState() + " (reason: " + lastDecisionReason + ")"));
        lines.add(Component.literal(s.summaryLine(tickOfDay)
                + (ScheduleClock.isOutdoorTime(tickOfDay) ? " [outdoor OK]" : " [indoor only]")));
        WorkAssignment work = civilian.workAssignment();
        lines.add(Component.literal("Work: " + (work == null ? "unassigned"
                : work.label() + " @ " + work.anchor().toShortString()
                + (work.indoor() ? " (indoor)" : " (outdoor)")
                + " ask=" + ScheduleClock.format(work.dailyAskTicks()))));

        StringBuilder anchorLine = new StringBuilder("Anchors: ");
        List<Anchor> anchors = s.anchors();
        if (anchors.isEmpty()) {
            anchorLine.append("none");
        } else {
            for (int i = 0; i < anchors.size(); i++) {
                Anchor a = anchors.get(i);
                if (i > 0) anchorLine.append(", ");
                anchorLine.append(a.kind().id()).append(" ")
                        .append(ScheduleClock.format(a.startTick())).append("-")
                        .append(ScheduleClock.format(a.endTick()))
                        .append(a.completed() ? "[done]" : "");
            }
        }
        lines.add(Component.literal(anchorLine.toString()));

        List<String> routeParts = new ArrayList<>();
        for (var entry : civilian.routeMemory().estimates().entrySet()) {
            RouteMemory.Pair p = entry.getKey();
            routeParts.add(p.a().id() + "<->" + p.b().id() + "=" + ScheduleClock.format(entry.getValue()));
        }
        lines.add(Component.literal("Routes: " + (routeParts.isEmpty() ? "baseline only" : String.join(", ", routeParts))));

        String lunchPeak = civilian.crowdMemory().observedLunchTick() >= 0
                ? ScheduleClock.format(civilian.crowdMemory().observedLunchTick()) : "none";
        String dinnerPeak = civilian.crowdMemory().observedDinnerTick() >= 0
                ? ScheduleClock.format(civilian.crowdMemory().observedDinnerTick()) : "none";
        lines.add(Component.literal(String.format(Locale.ROOT,
                "Crowd: %d seen loc(s) | Meal peaks: lunch=%s, dinner=%s",
                civilian.crowdMemory().lastSeenDays().size(), lunchPeak, dinnerPeak)));
        return lines;
    }
}
