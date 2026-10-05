package dev.brights0ng.enginesandempires.food;

/**
 * How long food spends in each stage, in game ticks (24000 = one day). Food is rotting once it has been through all three.
 *
 * <p>The defaults are Bright's choice (2026-09-27): 6 in-game days per stage, exactly 2 real hours, so food rots after 18
 * days (6 hours of play).
 */
public record SpoilTimes(long freshTicks, long ripeTicks, long staleTicks) {

    public static final long DAY = 24000L;
    public static final SpoilTimes DEFAULTS = new SpoilTimes(6 * DAY, 6 * DAY, 6 * DAY);

    public SpoilTimes {
        freshTicks = Math.max(1, freshTicks);
        ripeTicks = Math.max(1, ripeTicks);
        staleTicks = Math.max(1, staleTicks);
    }

    /** The stage food of this age (in ticks) is in. A negative age (a clock that went backwards) counts as brand new. */
    public FoodStage stage(long age) {
        long a = Math.max(0, age);
        if (a < freshTicks) {
            return FoodStage.FRESH;
        }
        if (a < freshTicks + ripeTicks) {
            return FoodStage.RIPE;
        }
        if (a < freshTicks + ripeTicks + staleTicks) {
            return FoodStage.STALE;
        }
        return FoodStage.ROTTING;
    }

    /** How far into its stage food of this age is: 0 (just entered) to 9 (about to move on). Rotting is always 0. */
    public int substage(long age) {
        long a = Math.max(0, age);
        FoodStage stage = stage(a);
        long start = switch (stage) {
            case FRESH -> 0;
            case RIPE -> freshTicks;
            case STALE -> freshTicks + ripeTicks;
            case ROTTING -> -1;
        };
        if (start < 0) {
            return 0;
        }
        long length = length(stage);
        return (int) Math.min(FoodStage.SUBSTAGES - 1, (a - start) * FoodStage.SUBSTAGES / length);
    }

    /** The 31-step freshness index: 0 (freshest) to 30 (rotting). */
    public int step(long age) {
        return stage(age).ordinal() * FoodStage.SUBSTAGES + substage(age);
    }

    /** The age at which food enters a stage. */
    public long start(FoodStage stage) {
        return switch (stage) {
            case FRESH -> 0;
            case RIPE -> freshTicks;
            case STALE -> freshTicks + ripeTicks;
            case ROTTING -> freshTicks + ripeTicks + staleTicks;
        };
    }

    /**
     * An age somewhere inside a stage, {@code fraction} (0 to 1) of the way through it. Rotting has no end, so for it this
     * spreads over the first day of rotting.
     */
    public long ageWithin(FoodStage stage, double fraction) {
        double f = Math.max(0, Math.min(fraction, 1));
        long span = stage == FoodStage.ROTTING ? DAY : length(stage);
        return start(stage) + Math.min(span - 1, (long) (f * span));
    }

    private long length(FoodStage stage) {
        return switch (stage) {
            case FRESH -> freshTicks;
            case RIPE -> ripeTicks;
            case STALE -> staleTicks;
            case ROTTING -> Long.MAX_VALUE;
        };
    }
}
