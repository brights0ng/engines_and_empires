// Written by Gemini.
package dev.brights0ng.empire.civilian.ai;

import javax.annotation.Nullable;

import dev.brights0ng.empire.rules.CivilianPreferences.Activity;
import net.minecraft.core.BlockPos;

/** Level-3 hobby task driving {@link HobbyAction} for one of the four v1 hobbies. */
public final class PerformHobbyTask implements CivilianTask {

    private final Activity activity;
    private final LocationResolver locations;
    @Nullable
    private final BlockPos focusPos;
    @Nullable
    private HobbyAction action;

    public PerformHobbyTask(Activity activity, LocationResolver locations, @Nullable BlockPos focusPos) {
        this.activity = activity;
        this.locations = locations;
        this.focusPos = focusPos;
    }

    @Override
    public String name() {
        return "hobby_" + activity.id();
    }

    @Override
    public boolean isEligible(AiContext ctx) {
        return true;
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
        action = new HobbyAction(activity, locations, focusPos);
        action.start(ctx);
    }

    @Override
    public CivilianAction.Status tick(AiContext ctx) {
        if (action == null) {
            start(ctx);
        }
        return action.tick(ctx);
    }

    @Override
    public void stop(AiContext ctx) {
        if (action != null) {
            action.stop(ctx);
            action = null;
        }
    }

    @Override
    public boolean canYieldCleanly() {
        return action == null || action.canYieldCleanly();
    }

    @Override
    public String activeActionName() {
        return action != null ? action.name() : "hobby_" + activity.id();
    }
}
