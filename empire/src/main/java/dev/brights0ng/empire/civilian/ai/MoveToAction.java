// Written by Gemini.
package dev.brights0ng.empire.civilian.ai;

import javax.annotation.Nullable;

import dev.brights0ng.empire.civilian.CivilianEntity;
import dev.brights0ng.empire.rules.LocationKey;
import dev.brights0ng.empire.rules.RouteMemory;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

/**
 * Level-4 pathfinding primitive: walks toward {@code target} within {@code reach}, measures elapsed ticks to update
 * {@link RouteMemory} on arrival, and tracks reach timeouts and prolonged stuck states.
 */
public final class MoveToAction implements CivilianAction {

    public static final double DEFAULT_WALK_SPEED = 0.6;
    public static final int DEFAULT_TIMEOUT_TICKS = 800;
    /** PROVISIONAL: ticks without meaningful horizontal movement before signalling a D12 stuck recovery. */
    public static final int STUCK_TELEPORT_TICKS = 1_200;
    private static final int REPATH_INTERVAL = 20;
    private static final int PROGRESS_CHECK_INTERVAL = 40;
    private static final double MIN_PROGRESS_SQ = 0.25 * 0.25;

    private final BlockPos target;
    private final double reach;
    private final double speedMod;
    @Nullable
    private final LocationKey fromKey;
    @Nullable
    private final LocationKey toKey;
    private final int timeoutTicks;

    private int elapsedTicks;
    private int repathTicks;
    private int progressTicks;
    private int stuckTicks;
    private int baselineTicks;
    @Nullable
    private Vec3 lastCheckPos;
    private boolean timedOut;

    public MoveToAction(BlockPos target, double reach, double speedMod,
                        @Nullable LocationKey fromKey, @Nullable LocationKey toKey, int timeoutTicks) {
        this.target = target.immutable();
        this.reach = Math.max(1.0, reach);
        this.speedMod = speedMod;
        this.fromKey = fromKey;
        this.toKey = toKey;
        this.timeoutTicks = Math.max(40, timeoutTicks);
    }

    public MoveToAction(BlockPos target, double reach, @Nullable LocationKey fromKey, @Nullable LocationKey toKey) {
        this(target, reach, DEFAULT_WALK_SPEED, fromKey, toKey, DEFAULT_TIMEOUT_TICKS);
    }

    public BlockPos target() {
        return target;
    }

    public int elapsedTicks() {
        return elapsedTicks;
    }

    public int stuckTicks() {
        return stuckTicks;
    }

    public boolean timedOut() {
        return timedOut;
    }

    @Override
    public String name() {
        return "move_to(" + (toKey != null ? toKey.id() : target.toShortString()) + ")";
    }

    @Override
    public void start(AiContext ctx) {
        CivilianEntity civ = ctx.civilian();
        elapsedTicks = 0;
        repathTicks = 0;
        progressTicks = 0;
        stuckTicks = 0;
        timedOut = false;
        lastCheckPos = civ.position();
        baselineTicks = RouteMemory.baselineTicks(
                civ.blockPosition().asLong(), target.asLong(), civ.derivedStats().movementSpeed());
        issueMove(civ);
    }

    @Override
    public Status tick(AiContext ctx) {
        CivilianEntity civ = ctx.civilian();
        elapsedTicks++;

        if (target.closerToCenterThan(civ.position(), reach)) {
            civ.getNavigation().stop();
            if (fromKey != null && toKey != null && fromKey != toKey && elapsedTicks > 0) {
                civ.routeMemory().recordTrip(fromKey, toKey, elapsedTicks, baselineTicks);
            }
            return Status.SUCCEEDED;
        }

        progressTicks++;
        if (progressTicks >= PROGRESS_CHECK_INTERVAL) {
            progressTicks = 0;
            Vec3 nowPos = civ.position();
            if (lastCheckPos != null && nowPos.distanceToSqr(lastCheckPos) < MIN_PROGRESS_SQ) {
                stuckTicks += PROGRESS_CHECK_INTERVAL;
            } else {
                stuckTicks = 0;
            }
            lastCheckPos = nowPos;
        }

        if (elapsedTicks >= timeoutTicks) {
            timedOut = true;
            civ.getNavigation().stop();
            return Status.FAILED;
        }

        repathTicks++;
        if (repathTicks >= REPATH_INTERVAL || civ.getNavigation().isDone()) {
            repathTicks = 0;
            issueMove(civ);
        }
        return Status.RUNNING;
    }

    @Override
    public void stop(AiContext ctx) {
        ctx.civilian().getNavigation().stop();
    }

    private void issueMove(CivilianEntity civ) {
        civ.getNavigation().moveTo(target.getX() + 0.5, target.getY(), target.getZ() + 0.5, speedMod);
    }
}
