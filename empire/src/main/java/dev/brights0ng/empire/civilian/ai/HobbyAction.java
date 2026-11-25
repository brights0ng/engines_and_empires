// Written by Gemini.
package dev.brights0ng.empire.civilian.ai;

import javax.annotation.Nullable;

import dev.brights0ng.empire.civilian.CivilianEntity;
import dev.brights0ng.empire.rules.CivilianPreferences.Activity;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionHand;

/**
 * Level-4 hobby primitive covering all four v1 activities ({@code FISHING}, {@code READING}, {@code RELAX_AT_HOME},
 * {@code WALK}). Credits live liked-hobby ticks to {@link dev.brights0ng.empire.rules.CivilianFulfilment} and updates
 * last-performed hobby day for staleness ordering.
 */
public final class HobbyAction implements CivilianAction {

    private static final int CYCLE_TICKS = 60;
    private static final double WAYPOINT_REACH = 2.0;

    private final Activity activity;
    private final LocationResolver locations;
    @Nullable
    private BlockPos focusTarget;
    @Nullable
    private MoveToAction walkStep;
    private int cycleTicks;
    private int activeTicks;
    private boolean recordedDay;

    public HobbyAction(Activity activity, LocationResolver locations, @Nullable BlockPos focusTarget) {
        this.activity = activity;
        this.locations = locations;
        this.focusTarget = focusTarget != null ? focusTarget.immutable() : null;
    }

    public int activeTicks() {
        return activeTicks;
    }

    @Override
    public String name() {
        return "hobby_" + activity.id();
    }

    @Override
    public void start(AiContext ctx) {
        cycleTicks = 0;
        activeTicks = 0;
        recordedDay = false;
        walkStep = null;
        if (activity == Activity.FISHING) {
            BlockPos water = LocationResolver.adjacentWater(ctx.level(), ctx.civilian().blockPosition());
            if (water != null) {
                focusTarget = water;
            }
        } else if (activity == Activity.WALK) {
            startNextWalkWaypoint(ctx);
        }
    }

    @Override
    public Status tick(AiContext ctx) {
        CivilianEntity civ = ctx.civilian();
        cycleTicks++;
        activeTicks++;

        // Credit Enjoyment hobby time while actively performing a liked hobby
        if (civ.fulfilment().preferences().likesActivity(activity)) {
            civ.fulfilment().recordLikedHobbyTicks(1L);
        }
        if (!recordedDay) {
            civ.schedule().recordHobbyPerformed(activity, ctx.scheduleDay());
            recordedDay = true;
        }

        switch (activity) {
            case FISHING -> {
                if (focusTarget != null) {
                    civ.getLookControl().setLookAt(
                            focusTarget.getX() + 0.5, focusTarget.getY() + 0.3, focusTarget.getZ() + 0.5, 20.0F, 20.0F);
                }
                if (cycleTicks == 1 || cycleTicks % CYCLE_TICKS == 0) {
                    civ.swing(InteractionHand.MAIN_HAND);
                }
            }
            case READING -> {
                // Quietly look slightly down/forward as if reading
                double lookX = civ.getX() + Math.cos(Math.toRadians(civ.getYRot() + 90.0)) * 2.0;
                double lookZ = civ.getZ() + Math.sin(Math.toRadians(civ.getYRot() + 90.0)) * 2.0;
                civ.getLookControl().setLookAt(lookX, civ.getEyeY() - 0.5, lookZ, 15.0F, 15.0F);
            }
            case RELAX_AT_HOME -> {
                if (cycleTicks % CYCLE_TICKS == 0) {
                    double angle = civ.getRandom().nextDouble() * Math.PI * 2.0;
                    civ.getLookControl().setLookAt(
                            civ.getX() + Math.cos(angle) * 4.0, civ.getEyeY(), civ.getZ() + Math.sin(angle) * 4.0, 15.0F, 15.0F);
                }
            }
            case WALK -> {
                if (walkStep == null) {
                    startNextWalkWaypoint(ctx);
                }
                if (walkStep != null) {
                    Status s = walkStep.tick(ctx);
                    if (s != Status.RUNNING) {
                        walkStep.stop(ctx);
                        walkStep = null;
                        return Status.SUCCEEDED;
                    }
                }
                return Status.RUNNING;
            }
        }

        if (cycleTicks >= CYCLE_TICKS) {
            return Status.SUCCEEDED;
        }
        return Status.RUNNING;
    }

    @Override
    public void stop(AiContext ctx) {
        if (walkStep != null) {
            walkStep.stop(ctx);
            walkStep = null;
        }
    }

    @Override
    public boolean canYieldCleanly() {
        return activity == Activity.WALK ? walkStep == null : cycleTicks >= 20;
    }

    private void startNextWalkWaypoint(AiContext ctx) {
        int angle = ctx.civilian().getRandom().nextInt(360);
        BlockPos dest = locations.resolveWalkSpot(ctx, angle);
        if (dest != null) {
            walkStep = new MoveToAction(dest, WAYPOINT_REACH, MoveToAction.DEFAULT_WALK_SPEED * 0.85,
                    null, null, 300);
            walkStep.start(ctx);
        }
    }
}
