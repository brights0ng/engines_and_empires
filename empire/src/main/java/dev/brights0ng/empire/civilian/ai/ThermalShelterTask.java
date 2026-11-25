// Written by Gemini.
package dev.brights0ng.empire.civilian.ai;

import javax.annotation.Nullable;

import dev.brights0ng.empire.rules.LocationKey;
import net.minecraft.core.BlockPos;

/**
 * Level-3 interrupt task: seeks indoor {@code HOME} shelter or the lit main bonfire when live Meteodyne temperature
 * pushes instant Comfort to {@code 0.0} (dangerous cold/heat).
 */
public final class ThermalShelterTask implements CivilianTask {

    private static final double SHELTER_SPEED = 0.72;
    private static final double SHELTER_REACH = 2.5;

    private final LocationResolver locations;
    @Nullable
    private BlockPos shelterPos;
    @Nullable
    private MoveToAction move;

    public ThermalShelterTask(LocationResolver locations) {
        this.locations = locations;
    }

    @Override
    public String name() {
        return "thermal_shelter";
    }

    @Override
    public boolean isEligible(AiContext ctx) {
        return true;
    }

    @Override
    public double baseWeight() {
        return 160.0;
    }

    @Override
    public double urgency(AiContext ctx) {
        return 1.0;
    }

    @Override
    public void start(AiContext ctx) {
        shelterPos = pickThermalShelter(ctx);
        if (shelterPos != null && !shelterPos.closerToCenterThan(ctx.civilian().position(), SHELTER_REACH)) {
            move = new MoveToAction(shelterPos, SHELTER_REACH, SHELTER_SPEED, null, null, 500);
            move.start(ctx);
        } else {
            move = null;
            ctx.civilian().getNavigation().stop();
        }
    }

    @Override
    public CivilianAction.Status tick(AiContext ctx) {
        if (move != null) {
            CivilianAction.Status s = move.tick(ctx);
            if (s != CivilianAction.Status.RUNNING) {
                move.stop(ctx);
                move = null;
            }
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
        return move != null ? move.name() : "warm_shelter";
    }

    @Nullable
    private BlockPos pickThermalShelter(AiContext ctx) {
        // Prefer valid indoor HOME if the citizen has a claimed bed; otherwise gather by the main bonfire.
        if (ctx.civilian().claimedBed() != null) {
            BlockPos indoor = locations.resolve(ctx, LocationKey.HOBBY_RELAX_HOME);
            if (indoor != null) {
                return indoor;
            }
        }
        BlockPos fireSpot = locations.resolve(ctx, LocationKey.FOOD_SITE);
        if (fireSpot != null) {
            return fireSpot;
        }
        return locations.resolve(ctx, LocationKey.HOME);
    }
}
