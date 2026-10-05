package dev.brights0ng.enginesandempires.food.icechest;

/**
 * The ice chest's cooling over a stretch of time, worked out in one go: the chest is not ticked item by item, but settled
 * whenever something touches it (and once a second while loaded), so this covers any gap, including time spent unloaded.
 *
 * <p>Ice burns like furnace fuel: one item at a time, each good for a fixed number of ticks, at a flat rate however much food
 * is in the chest. A chest with no food in it does not burn ice.
 *
 * <p>Plain Java on purpose (no Minecraft types), so it can be unit tested.
 */
public final class IceChestCooling {

    /**
     * What a stretch of time did.
     *
     * @param coldTicks   how many of the ticks the chest was cooling
     * @param coolingLeft what is left of the item now burning
     * @param currentMax  how long the item now burning lasts in all (for the gauge); 0 once nothing is burning
     * @param itemsUsed   how many ice items were taken from the slot
     */
    public record Result(long coldTicks, long coolingLeft, long currentMax, int itemsUsed) {
    }

    /**
     * Runs the cooling for {@code elapsed} ticks.
     *
     * @param coolingLeft what was left of the item burning at the start
     * @param currentMax  how long that item lasts in all
     * @param iceCount    ice items waiting in the slot
     * @param perItem     how long each of them lasts (0 or less: the slot holds nothing that cools)
     * @param hasFood     whether there is food in the chest (no food, no melting)
     */
    public static Result run(long elapsed, long coolingLeft, long currentMax, int iceCount, long perItem, boolean hasFood) {
        if (elapsed <= 0 || !hasFood) {
            return new Result(0, coolingLeft, currentMax, 0);
        }
        long remaining = elapsed;
        long cold = Math.min(coolingLeft, remaining);
        long left = coolingLeft - cold;
        long max = currentMax;
        remaining -= cold;
        int used = 0;
        if (remaining > 0 && perItem > 0 && iceCount > 0) {
            // Whole items burnt through, then the one left burning
            long needed = (remaining + perItem - 1) / perItem;
            used = (int) Math.min(iceCount, needed);
            long supplied = used * perItem;
            long burnt = Math.min(supplied, remaining);
            cold += burnt;
            left = supplied - burnt;
            max = perItem;
        }
        if (left <= 0) {
            left = 0;
            max = 0;
        }
        return new Result(cold, left, max, used);
    }

    /** How much younger food gets for {@code coldTicks} of cooling at {@code slowdown} (0.9 = spoils 90% slower). */
    public static long youngerBy(long coldTicks, double slowdown) {
        return Math.round(coldTicks * Math.max(0, Math.min(1, slowdown)));
    }

    private IceChestCooling() {
    }
}
