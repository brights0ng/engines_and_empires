// Written by Gemini.
package dev.brights0ng.empire.rules;

public final class DawnClock {

    public static final long DAY_TICKS = 24000L;

    public static long dayOf(long dayTime) {
        return Math.floorDiv(dayTime, DAY_TICKS);
    }

    public static long nextDawnDay(long dayTime) {
        return dayOf(dayTime) + 1L;
    }

    private DawnClock() {
    }
}
