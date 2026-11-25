// Written by Gemini.
package dev.brights0ng.empire.civilian.ai;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import javax.annotation.Nullable;

import dev.brights0ng.empire.EmpireConfig;
import dev.brights0ng.empire.civilian.CivilianBeds;
import dev.brights0ng.empire.civilian.CivilianEntity;
import dev.brights0ng.empire.civilian.CivilianFulfilmentMeters;
import dev.brights0ng.empire.data.CivilianRecord;
import dev.brights0ng.empire.data.EmpireRecord;
import dev.brights0ng.empire.data.EmpireSavedData;
import dev.brights0ng.empire.data.WorkAssignment;
import dev.brights0ng.empire.rules.CivilianDerivedStats;
import dev.brights0ng.empire.rules.CivilianSchedule;
import dev.brights0ng.empire.rules.CivilianSchedule.Anchor;
import dev.brights0ng.empire.rules.CivilianSchedule.Decision;
import dev.brights0ng.empire.rules.CivilianSchedule.PlanContext;
import dev.brights0ng.empire.rules.CivilianSchedule.SkeletonInput;
import dev.brights0ng.empire.rules.CivilianSchedule.SlotKind;
import dev.brights0ng.empire.rules.CivilianSleep;
import dev.brights0ng.empire.rules.FulfilmentModel;
import dev.brights0ng.empire.rules.FulfilmentModel.Evaluation;
import dev.brights0ng.empire.rules.FulfilmentModel.Meter;
import dev.brights0ng.empire.rules.LocationKey;
import dev.brights0ng.empire.rules.RouteMemory;
import dev.brights0ng.empire.rules.ScheduleClock;
import dev.brights0ng.paradynelib.services.TemperatureProvider;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;

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
    /** PROVISIONAL: duration to remain sheltered after hostile damage (20s = 400t). */
    public static final int FLEE_DURATION_TICKS = 400;
    /** PROVISIONAL: hysteresis comfort threshold to exit thermal shelter once warmed/cooled. */
    public static final double THERMAL_RECOVER_COMFORT = 0.35;
    /** PROVISIONAL: max ticks to wait in thermal shelter if no comfortable room exists yet (60s = 1,200t). */
    public static final int THERMAL_MAX_WAIT_TICKS = 1_200;
    /** PROVISIONAL: cooldown before re-triggering thermal shelter after hitting {@link #THERMAL_MAX_WAIT_TICKS}. */
    public static final int THERMAL_COOLDOWN_TICKS = 400;
    /** Consecutive stuck travel failures before last-resort D12 teleport home. */
    public static final int CONSECUTIVE_STUCK_LIMIT = 2;

    private static final long TIME_JUMP_THRESHOLD = 100L;
    private static final int ANCHOR_SYNC_INTERVAL = 200;
    private static final int THERMAL_CHECK_INTERVAL = 40;
    private static final double BODY_MIDDLE_Y = 0.9;

    public enum InterruptKind {
        NONE("none"),
        FLEE("flee"),
        EMERGENCY_EAT("emergency_eat"),
        THERMAL_SHELTER("thermal_shelter");

        private final String id;

        InterruptKind(String id) {
            this.id = id;
        }

        public String id() {
            return id;
        }
    }

    private final CivilianEntity civilian;
    private final LocationResolver locations = new LocationResolver();
    private final CivilianSlotRunner slotRunner;

    private long lastDayTime = Long.MIN_VALUE;
    private int syncTicks;
    private int consecutiveStuckFailures;
    private long fleeUntilGameTime = Long.MIN_VALUE;
    private int thermalCheckTicks;
    private int thermalActiveTicks;
    private long thermalCooldownUntil = Long.MIN_VALUE;
    @Nullable
    private TemperatureProvider temperatureProviderOverride;
    private InterruptKind activeInterrupt = InterruptKind.NONE;
    @Nullable
    private CivilianTask activeInterruptTask;
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

    /** Active interrupt preempting the daily schedule, or {@link InterruptKind#NONE}. */
    public InterruptKind activeInterrupt() {
        return activeInterrupt;
    }

    /** Notifies the AI that a hostile monster or outsider player just damaged this citizen. */
    public void notifyHostileDamage(long gameTime) {
        fleeUntilGameTime = Math.max(fleeUntilGameTime, gameTime + FLEE_DURATION_TICKS);
    }

    /** Test/diagnostic hook to inject a deterministic {@link TemperatureProvider} (null uses live provider). */
    public void setTemperatureProviderOverride(@Nullable TemperatureProvider provider) {
        this.temperatureProviderOverride = provider;
        this.thermalCheckTicks = THERMAL_CHECK_INTERVAL;
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
        AiContext ctx = AiContext.of(level, civilian, data, empire);
        clearInterrupt(ctx);
        slotRunner.stop(ctx);
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
            clearInterrupt(ctx);
            slotRunner.stop(ctx);
            locations.syncRouteAnchors(ctx);
            rebuildSkeleton(ctx);
        } else if (timeJumped) {
            clearInterrupt(ctx);
            slotRunner.stop(ctx);
            schedule.expireSkippedAnchors(ctx.tickOfDay());
        } else {
            if (schedule.expireSkippedAnchors(ctx.tickOfDay())) {
                slotRunner.stop(ctx);
            }
        }

        // Evaluate live interrupts (FLEE > EMERGENCY_EAT > THERMAL_SHELTER) before normal schedule execution
        if (tickInterrupts(ctx)) {
            return;
        }

        // If no slot is active, run Level 1 decideNextSlot once
        if (!slotRunner.hasActiveSlot()) {
            planNextSlot(ctx);
        }

        // Tick Level 2 -> 3 -> 4
        boolean wasSleep = slotRunner.activeSlot() != null && slotRunner.activeSlot().kind() == SlotKind.SLEEP;
        if (!slotRunner.tick(ctx)) {
            if (!wasSleep && slotRunner.lastTravelFailedStuck()) {
                slotRunner.clearStuckFlag();
                consecutiveStuckFailures++;
                if (consecutiveStuckFailures >= CONSECUTIVE_STUCK_LIMIT) {
                    consecutiveStuckFailures = 0;
                    teleportHomeAsLastResort(ctx);
                }
            } else {
                consecutiveStuckFailures = 0;
            }
            planNextSlot(ctx);
        }
    }

    /** Runs active interrupt evaluation and ticking; returns true when an interrupt owns this tick. */
    private boolean tickInterrupts(AiContext ctx) {
        long gameTime = ctx.level().getGameTime();
        InterruptKind desired = evaluateDesiredInterrupt(ctx, gameTime);

        if (desired != activeInterrupt) {
            // Let an active emergency bite finish cleanly before dropping EMERGENCY_EAT
            if (activeInterrupt == InterruptKind.EMERGENCY_EAT && activeInterruptTask != null
                    && !activeInterruptTask.canYieldCleanly() && desired == InterruptKind.NONE) {
                desired = InterruptKind.EMERGENCY_EAT;
            } else {
                clearInterrupt(ctx);
                if (desired != InterruptKind.NONE) {
                    // Pause/stop the scheduled slot so we resume cleanly via decideNextSlot afterward
                    slotRunner.stop(ctx);
                    activeInterrupt = desired;
                    activeInterruptTask = createInterruptTask(desired);
                    thermalActiveTicks = 0;
                    activeInterruptTask.start(ctx);
                }
            }
        }

        if (activeInterrupt == InterruptKind.NONE || activeInterruptTask == null) {
            return false;
        }

        if (activeInterrupt == InterruptKind.THERMAL_SHELTER) {
            thermalActiveTicks++;
            if (thermalActiveTicks >= THERMAL_MAX_WAIT_TICKS) {
                thermalCooldownUntil = gameTime + THERMAL_COOLDOWN_TICKS;
                clearInterrupt(ctx);
                return false;
            }
        }

        CivilianAction.Status status = activeInterruptTask.tick(ctx);
        if (status != CivilianAction.Status.RUNNING) {
            clearInterrupt(ctx);
        }
        return true;
    }

    private InterruptKind evaluateDesiredInterrupt(AiContext ctx, long gameTime) {
        // 1. Hostile damage flee (highest priority)
        if (gameTime < fleeUntilGameTime) {
            return InterruptKind.FLEE;
        }

        // 2. Low-hunger emergency snack (outside scheduled MEAL)
        boolean inScheduledMeal = slotRunner.activeSlot() != null && slotRunner.activeSlot().kind() == SlotKind.MEAL;
        if (!inScheduledMeal && civilian.food().foodLevel() <= emergencyFoodThreshold()
                && (civilian.isUsingItem() || civilian.hasFittingPocketFood())) {
            return InterruptKind.EMERGENCY_EAT;
        }

        // 3. Extreme Meteodyne temperature shelter (Comfort <= 0.0; hysteresis exit at >= 0.35)
        if (gameTime >= thermalCooldownUntil) {
            thermalCheckTicks++;
            if (thermalCheckTicks >= THERMAL_CHECK_INTERVAL) {
                thermalCheckTicks = 0;
                double comfort = sampleInstantComfort(ctx);
                if (Double.isFinite(comfort)) {
                    if (activeInterrupt == InterruptKind.THERMAL_SHELTER) {
                        if (comfort >= THERMAL_RECOVER_COMFORT) {
                            return InterruptKind.NONE;
                        }
                        return InterruptKind.THERMAL_SHELTER;
                    }
                    if (comfort <= 0.0) {
                        return InterruptKind.THERMAL_SHELTER;
                    }
                }
            } else if (activeInterrupt == InterruptKind.THERMAL_SHELTER) {
                return InterruptKind.THERMAL_SHELTER;
            }
        }

        return InterruptKind.NONE;
    }

    private double sampleInstantComfort(AiContext ctx) {
        TemperatureProvider provider = temperatureProviderOverride != null
                ? temperatureProviderOverride : TemperatureProvider.get();
        double air = provider.air(ctx.level(), civilian.blockPosition());
        if (!Double.isFinite(air)) {
            return Double.NaN;
        }
        double room = provider.room(ctx.level(), civilian.position().add(0.0, BODY_MIDDLE_Y, 0.0));
        double tempC = air + (Double.isFinite(room) ? room : 0.0);
        CivilianDerivedStats stats = civilian.derivedStats();
        return FulfilmentModel.comfortFromTemp(
                tempC,
                stats.comfortMinC(EmpireConfig.fulfilmentComfortMinC()),
                stats.comfortMaxC(EmpireConfig.fulfilmentComfortMaxC()),
                stats.comfortDangerMarginC(EmpireConfig.fulfilmentComfortDangerMarginC()));
    }

    private CivilianTask createInterruptTask(InterruptKind kind) {
        return switch (kind) {
            case FLEE -> new FleeToSafetyTask(locations);
            case EMERGENCY_EAT -> new EmergencyEatTask();
            case THERMAL_SHELTER -> new ThermalShelterTask(locations);
            case NONE -> throw new IllegalArgumentException("NONE has no task");
        };
    }

    private void clearInterrupt(AiContext ctx) {
        if (activeInterruptTask != null) {
            activeInterruptTask.stop(ctx);
            activeInterruptTask = null;
        }
        activeInterrupt = InterruptKind.NONE;
        thermalActiveTicks = 0;
    }

    /** Last-resort D12 stuck recovery: safely moves a genuinely trapped citizen back to HOME or bonfire. */
    private void teleportHomeAsLastResort(AiContext ctx) {
        EmpireRecord empire = ctx.empire();
        BlockPos bed = civilian.claimedBed();
        if (empire != null && bed != null && CivilianBeds.isValidClaimedBed(ctx.level(), empire, bed)) {
            Vec3 dest = CivilianBeds.bedTeleportPos(ctx.level(), bed, civilian);
            civilian.getNavigation().stop();
            civilian.teleportTo(dest.x, dest.y, dest.z);
            return;
        }
        BlockPos spot = empire != null
                ? CivilianBeds.roughSpot(ctx.level(), empire, civilian.identityUuid(), civilian.blockPosition())
                : civilian.home();
        if (spot != null) {
            civilian.getNavigation().stop();
            civilian.teleportTo(spot.getX() + 0.5, spot.getY(), spot.getZ() + 0.5);
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
        return Component.literal("AI: " + describeCurrentActivity()
                + " | " + civilian.schedule().summaryLine(tickOfDay));
    }

    /** Full schedule diagnostic report for {@code /empire debug civilian schedule}. */
    public List<Component> formatScheduleReport(ServerLevel level) {
        List<Component> lines = new ArrayList<>();
        int tickOfDay = ScheduleClock.timeOfDay(level.getDayTime());
        CivilianSchedule s = civilian.schedule();
        lines.add(Component.literal("=== Schedule: " + civilian.getName().getString()
                + " (" + civilian.status().name() + ") ==="));
        lines.add(Component.literal("Active: " + describeCurrentActivity() + " (reason: " + lastDecisionReason + ")"));
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

    private String describeCurrentActivity() {
        if (civilian.dialoguePlayer() != null) {
            return "INTERRUPT[dialogue] (paused: " + slotRunner.describeState() + ")";
        }
        if (activeInterrupt != InterruptKind.NONE) {
            String taskName = activeInterruptTask != null ? activeInterruptTask.name() : "none";
            String actionName = activeInterruptTask != null ? activeInterruptTask.activeActionName() : "none";
            return "INTERRUPT[" + activeInterrupt.id() + "] -> " + taskName + " -> " + actionName;
        }
        return slotRunner.describeState();
    }
}
