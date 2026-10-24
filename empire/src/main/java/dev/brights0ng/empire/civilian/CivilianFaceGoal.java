// Written by Claude.
package dev.brights0ng.empire.civilian;

import java.util.EnumSet;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.ai.goal.Goal;

/** Holds a civilian still, facing whoever it is talking to: the player in dialogue or its conversation partner. */
public class CivilianFaceGoal extends Goal {

    private final CivilianEntity civilian;

    public CivilianFaceGoal(CivilianEntity civilian) {
        this.civilian = civilian;
        setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
    }

    @Override
    public boolean canUse() {
        return civilian.faceTarget() != null;
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
    public void tick() {
        Entity target = civilian.faceTarget();
        civilian.getNavigation().stop();
        if (target != null) {
            civilian.getLookControl().setLookAt(target, 30.0F, 30.0F);
        }
    }
}
