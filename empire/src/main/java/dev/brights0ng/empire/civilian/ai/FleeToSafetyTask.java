// Written by Gemini.
package dev.brights0ng.empire.civilian.ai;

import javax.annotation.Nullable;

import dev.brights0ng.empire.civilian.CivilianEntity;
import dev.brights0ng.empire.rules.LocationKey;
import net.minecraft.core.BlockPos;

/**
 * Level-3 interrupt task: flees briskly to the citizen's {@code HOME} (or main bonfire if homeless) after hostile
 * damage and stays sheltered until the danger timer clears.
 */
public final class FleeToSafetyTask implements CivilianTask {

    private static final double FLEE_SPEED = 0.85;
    private static final double SAFE_REACH = 2.5;

    private final LocationResolver locations;
    @Nullable
    private BlockPos shelterPos;
    @Nullable
    private MoveToAction move;

    public FleeToSafetyTask(LocationResolver locations) {
        this.locations = locations;
    }

    @Override
    public String name() {
        return "flee_safety";
    }

    @Override
    public boolean isEligible(AiContext ctx) {
        return true;
    }

    @Override
    public double baseWeight() {
        return 200.0;
    }

    @Override
    public double urgency(AiContext ctx) {
        return 1.0;
    }

    @Override
    public void start(AiContext ctx) {
        shelterPos = locations.resolve(ctx, LocationKey.HOME);
        if (shelterPos == null) {
            shelterPos = locations.resolve(ctx, LocationKey.FOOD_SITE);
        }
        if (shelterPos != null && !shelterPos.closerToCenterThan(ctx.civilian().position(), SAFE_REACH)) {
            move = new MoveToAction(shelterPos, SAFE_REACH, FLEE_SPEED, null, null, 400);
            move.start(ctx);
        } else {
            move = null;
            ctx.civilian().getNavigation().stop();
        }
    }

    @Override
    public CivilianAction.Status tick(AiContext ctx) {
        CivilianEntity civ = ctx.civilian();
        if (move != null) {
            CivilianAction.Status status = move.tick(ctx);
            if (status != CivilianAction.Status.RUNNING) {
                move.stop(ctx);
                move = null;
            }
            return CivilianAction.Status.RUNNING;
        }
        civ.getNavigation().stop();
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
        return move != null ? move.name() : "shelter_wait";
    }
}
