package dev.brights0ng.enginesandempires.weather.rain;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;

/**
 * Each formation's drift velocity, eased over about {@link #TIME_TICKS} (10 s), for the rain's wind drift.
 *
 * <p>Why: the drift offset is (wind − the cloud's velocity) / fall speed × height, often a hundred blocks. PA moves a
 * cloud by each tick's gusty wind, and clients only hear about it once a second, so the raw velocity (and even one
 * measured between updates) steps every second; a small step moved the whole wet area by many blocks at once (Bright,
 * 2026-10-04: everything jumped about once a second). Easing it makes the rain track the cloud's average motion. The
 * server feeds it PA's per-tick velocity and the client the renderer's measured one; both settle to the same average.
 */
public final class VelocitySmoother {

    public static final double TIME_TICKS = 200;
    private static final long FORGET_TICKS = 1200;

    /** Per formation: vx, vz, the tick last eased. */
    private final Map<UUID, double[]> smoothed = new HashMap<>();
    private long lastPrune = Long.MIN_VALUE;

    /** Eases formation {@code id}'s velocity toward (vx, vz) up to {@code tick}; returns the eased {vx, vz}. */
    public synchronized double[] smooth(UUID id, double vx, double vz, long tick) {
        prune(tick);
        double[] s = smoothed.get(id);
        if (s == null) {
            smoothed.put(id, new double[]{vx, vz, tick});
            return new double[]{vx, vz};
        }
        long dt = tick - (long) s[2];
        if (dt > 0) {
            double a = 1 - Math.exp(-dt / TIME_TICKS);
            s[0] += (vx - s[0]) * a;
            s[1] += (vz - s[1]) * a;
        }
        if (dt != 0) {
            s[2] = tick;
        }
        return new double[]{s[0], s[1]};
    }

    private void prune(long tick) {
        if (tick - lastPrune < 200 && tick >= lastPrune) {
            return;
        }
        lastPrune = tick;
        for (Iterator<double[]> it = smoothed.values().iterator(); it.hasNext(); ) {
            long last = (long) it.next()[2];
            if (Math.abs(tick - last) > FORGET_TICKS) {
                it.remove();
            }
        }
    }
}
