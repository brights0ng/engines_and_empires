// Written by Gemini.
package dev.brights0ng.empire.civilian.ai;

import dev.brights0ng.empire.civilian.CivilianEntity;

/**
 * Level-4 eating primitive: eats fitting food from the citizen's pockets through vanilla {@code startUsingItem},
 * optionally preferring favourite foods first. Never yields mid-bite ({@link #canYieldCleanly()} returns {@code false}
 * while actively consuming an item).
 */
public final class ConsumeMealAction implements CivilianAction {

    private final boolean preferLiked;
    private final int maxItems;
    private int itemsStarted;
    private boolean waitingForFinish;

    public ConsumeMealAction(boolean preferLiked, int maxItems) {
        this.preferLiked = preferLiked;
        this.maxItems = Math.max(1, maxItems);
    }

    public ConsumeMealAction(boolean preferLiked) {
        this(preferLiked, 4);
    }

    @Override
    public String name() {
        return preferLiked ? "consume_meal" : "consume_snack";
    }

    @Override
    public void start(AiContext ctx) {
        itemsStarted = 0;
        waitingForFinish = ctx.civilian().isUsingItem();
    }

    @Override
    public Status tick(AiContext ctx) {
        CivilianEntity civ = ctx.civilian();
        if (civ.isUsingItem()) {
            waitingForFinish = true;
            return Status.RUNNING;
        }
        if (waitingForFinish) {
            waitingForFinish = false;
        }
        if (itemsStarted >= maxItems) {
            return Status.SUCCEEDED;
        }
        if (civ.startEatingFromInventory(preferLiked)) {
            itemsStarted++;
            waitingForFinish = true;
            return Status.RUNNING;
        }
        return Status.SUCCEEDED;
    }

    @Override
    public boolean canYieldCleanly() {
        return !waitingForFinish;
    }
}
