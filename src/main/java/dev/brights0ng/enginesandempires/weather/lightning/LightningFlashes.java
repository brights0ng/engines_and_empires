package dev.brights0ng.enginesandempires.weather.lightning;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * The flashes a client has been told of (weather phase 6b/6c). No client-only classes, so the payload handler and the
 * bolt sound mixin can name it on either side; only a client ever fills it. New flashes wait here for the client's
 * lightning controller ({@code ClientLightning}) to pick them up; all are remembered for a few seconds so a vanilla
 * bolt can tell it was announced (its instant thunder is then left to the delayed one).
 */
public final class LightningFlashes {

    /** A flash and when it arrived, milliseconds (System.nanoTime). */
    public record Flash(LightningPayload payload, double arrivedMs) {
    }

    /** How long a flash is remembered, milliseconds. */
    private static final double KEEP_MS = 5000;
    /** A bolt within this many blocks of an announced strike point is that strike's. */
    private static final double SAME_STRIKE = 4;

    private static final Deque<Flash> RECENT = new ArrayDeque<>();
    private static final List<Flash> NEW = new ArrayList<>();

    public static double nowMs() {
        return System.nanoTime() / 1e6;
    }

    public static synchronized void accept(LightningPayload payload) {
        double now = nowMs();
        Flash f = new Flash(payload, now);
        RECENT.addLast(f);
        NEW.add(f);
        while (!RECENT.isEmpty() && now - RECENT.peekFirst().arrivedMs() > KEEP_MS) {
            RECENT.removeFirst();
        }
    }

    /** The flashes that arrived since the last call. */
    public static synchronized List<Flash> drainNew() {
        List<Flash> out = new ArrayList<>(NEW);
        NEW.clear();
        return out;
    }

    /** Whether a strike at (x, y, z) was announced in the last few seconds (a vanilla bolt there is ours). */
    public static synchronized boolean announcedNear(double x, double y, double z) {
        double now = nowMs();
        for (Flash f : RECENT) {
            LightningPayload p = f.payload();
            if (now - f.arrivedMs() <= KEEP_MS && p.struck()) {
                double dx = p.targetX() - x;
                double dy = p.targetY() - y;
                double dz = p.targetZ() - z;
                if (dx * dx + dy * dy + dz * dz <= SAME_STRIKE * SAME_STRIKE) {
                    return true;
                }
            }
        }
        return false;
    }

    public static synchronized void clear() {
        RECENT.clear();
        NEW.clear();
    }

    private LightningFlashes() {
    }
}
