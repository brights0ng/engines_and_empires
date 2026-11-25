// Written by Gemini.
package dev.brights0ng.empire.civilian.ai;

import java.util.EnumSet;

import dev.brights0ng.empire.civilian.CivilianEntity;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.goal.Goal;

/**
 * Single NeoForge {@link Goal} bridge driving the 4-level Civilian AI ({@link CivilianAiController}) for
 * {@link CivilianEntity.Status#CITIZEN} entities only.
 */
public final class CivilianAiGoal extends Goal {

    private final CivilianEntity civilian;
    private final CivilianAiController controller;

    public CivilianAiGoal(CivilianEntity civilian, CivilianAiController controller) {
        this.civilian = civilian;
        this.controller = controller;
        setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK, Flag.JUMP));
    }

    @Override
    public boolean canUse() {
        return civilian.status() == CivilianEntity.Status.CITIZEN
                && civilian.isAlive()
                && !civilian.isLyingDown()
                && civilian.dialoguePlayer() == null
                && civilian.level() instanceof ServerLevel;
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
        if (civilian.level() instanceof ServerLevel serverLevel) {
            controller.tick(serverLevel);
        }
    }

    @Override
    public void stop() {
        // Keep active slot paused across brief dialogue interruptions; stop navigation cleanly.
        civilian.getNavigation().stop();
    }
}
