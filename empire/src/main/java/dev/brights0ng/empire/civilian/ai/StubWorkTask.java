// Written by Gemini.
package dev.brights0ng.empire.civilian.ai;

import javax.annotation.Nullable;

import dev.brights0ng.empire.data.WorkAssignment;

/**
 * Level-3 placeholder work task for a {@link WorkAssignment}: ensures the citizen is within reach of the work anchor
 * and executes clean {@link StubWorkAction} cycles until the work quota completes or the departure deadline arrives.
 */
public final class StubWorkTask implements CivilianTask {

    private static final double WORK_REACH = 2.5;

    private final WorkAssignment assignment;
    @Nullable
    private CivilianAction currentAction;

    public StubWorkTask(WorkAssignment assignment) {
        this.assignment = assignment;
    }

    @Override
    public String name() {
        return "work_" + assignment.label();
    }

    @Override
    public boolean isEligible(AiContext ctx) {
        return ctx.civilian().workAssignment() != null;
    }

    @Override
    public double baseWeight() {
        return 80.0;
    }

    @Override
    public double urgency(AiContext ctx) {
        return 1.0;
    }

    @Override
    public void start(AiContext ctx) {
        if (!assignment.anchor().closerToCenterThan(ctx.civilian().position(), WORK_REACH)) {
            currentAction = new MoveToAction(assignment.anchor(), WORK_REACH, null, null);
        } else {
            currentAction = new StubWorkAction(assignment.anchor());
        }
        currentAction.start(ctx);
    }

    @Override
    public CivilianAction.Status tick(AiContext ctx) {
        if (currentAction == null) {
            start(ctx);
        }
        CivilianAction.Status status = currentAction.tick(ctx);
        if (status != CivilianAction.Status.RUNNING) {
            currentAction.stop(ctx);
            boolean wasMove = currentAction instanceof MoveToAction;
            currentAction = null;
            if (wasMove && status == CivilianAction.Status.SUCCEEDED) {
                currentAction = new StubWorkAction(assignment.anchor());
                currentAction.start(ctx);
                return CivilianAction.Status.RUNNING;
            }
            return status;
        }
        return CivilianAction.Status.RUNNING;
    }

    @Override
    public void stop(AiContext ctx) {
        if (currentAction != null) {
            currentAction.stop(ctx);
            currentAction = null;
        }
    }

    @Override
    public boolean canYieldCleanly() {
        return currentAction == null || currentAction.canYieldCleanly();
    }

    @Override
    public String activeActionName() {
        return currentAction != null ? currentAction.name() : "work_cycle";
    }
}
