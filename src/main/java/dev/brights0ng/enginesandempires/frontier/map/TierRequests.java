package dev.brights0ng.enginesandempires.frontier.map;

/**
 * When a map asks the server for its tiers again: every {@link #REFRESH_MILLIS} (Bright: 10 seconds) while it is looked at,
 * and straight away when it moves or zooms out past what it last asked for, but never more than twice a second.
 *
 * <p>Nothing here touches Minecraft.
 */
public final class TierRequests {

    public static final long REFRESH_MILLIS = 10_000;
    public static final long MIN_GAP_MILLIS = 500;

    /** What a map last asked for: the chunk it was centred on, how far around, and when. */
    public record Asked(int centreX, int centreZ, int radius, long at) {
    }

    /** Whether a map that last asked {@code last} (null if never) should ask again for this area now. */
    public static boolean shouldAsk(Asked last, int centreX, int centreZ, int radius, long now) {
        if (last == null) {
            return true;
        }
        if (now - last.at() < MIN_GAP_MILLIS) {
            return false;
        }
        if (now - last.at() >= REFRESH_MILLIS || radius > last.radius()) {
            return true;
        }
        int slack = Math.max(1, last.radius() / 4);
        return Math.abs(centreX - last.centreX()) > slack || Math.abs(centreZ - last.centreZ()) > slack;
    }

    private TierRequests() {
    }
}
