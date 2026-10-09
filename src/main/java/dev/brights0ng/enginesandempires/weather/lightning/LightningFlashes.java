package dev.brights0ng.enginesandempires.weather.lightning;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * The flashes a client has been told of (weather phase 6b), kept for a few seconds for the glow and thunder of 6c.
 * No client-only classes, so the payload handler can name it on either side; only a client ever fills it.
 */
public final class LightningFlashes {

    /** A flash and when it arrived, in client game ticks. */
    public record Flash(LightningPayload payload, long tick) {
    }

    /** How long a flash is kept, ticks (long enough for thunder from the edge of the range: 3072 / 343 s). */
    private static final long KEEP = 20 * 12;
    private static final Deque<Flash> FLASHES = new ArrayDeque<>();

    public static synchronized void accept(LightningPayload payload, long tick) {
        FLASHES.addLast(new Flash(payload, tick));
        while (!FLASHES.isEmpty() && tick - FLASHES.peekFirst().tick() > KEEP) {
            FLASHES.removeFirst();
        }
    }

    /** The flashes since {@code sinceTick}. */
    public static synchronized List<Flash> since(long sinceTick) {
        List<Flash> out = new ArrayList<>();
        for (Flash f : FLASHES) {
            if (f.tick() >= sinceTick) {
                out.add(f);
            }
        }
        return out;
    }

    public static synchronized void clear() {
        FLASHES.clear();
    }

    private LightningFlashes() {
    }
}
