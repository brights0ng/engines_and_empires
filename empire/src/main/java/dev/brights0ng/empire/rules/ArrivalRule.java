// Written by Claude.
package dev.brights0ng.empire.rules;

/**
 * When a traveller may come to an empire (Bright, 2026-10-24): one per in-game day, only while the main bonfire is lit,
 * a member is near it, the empire has fewer than {@link #PIONEER_LIMIT} citizens and no traveller is already waiting.
 */
public final class ArrivalRule {

    /** Citizens an empire can gain from travellers before it needs a Pub (provisional). */
    public static final int PIONEER_LIMIT = 3;
    /** Ticks from one arrival until the next may come (provisional). */
    public static final long ARRIVAL_INTERVAL = 24000;

    /** Whether a traveller may arrive now; {@code nextArrival} is the earliest game time the next one may. */
    public static boolean canArrive(boolean bonfireLit, boolean memberNear, int citizens, boolean travellerWaiting,
                                    long gameTime, long nextArrival) {
        return bonfireLit && memberNear && citizens < PIONEER_LIMIT && !travellerWaiting && gameTime >= nextArrival;
    }

    private ArrivalRule() {
    }
}
