// Written by ChatGPT.
package dev.brights0ng.paradynelib.heat.air;

import java.util.ArrayDeque;
import java.util.Iterator;
import java.util.ArrayList;
import java.util.List;

/** Single-owner bookkeeping for thermal response time, separate from elapsed game/airflow time.
 * Rate changes affect future ticks only. History retains the rate at the catch-up window's start plus
 * later changes; replacing changes within one tick bounds storage to at most maxCatchUpTicks + 1 entries.
 * This owns no temperatures, wind or scheduling and has no Minecraft dependencies.
 */
public final class ThermalClock {
    public static final double MIN_SCALE = 0.1, MAX_SCALE = 10.0;
    private record Change(long tick, double scale) { }
    public record Interval(double gameSeconds, double thermalSeconds) { }
    /** Immutable rate history for an offline job; tick bounds are inclusive/exclusive. */
    public record Segment(long startTick, long endTick, double scale) {
        public Segment {
            requireScale(scale);
            if (startTick < 0 || endTick <= startTick) { throw new IllegalArgumentException("Positive chronological segment required"); }
        }
        public double gameSeconds() { return (endTick - startTick) / 20.0; }
        public double thermalSeconds() { return ThermalClock.thermalSeconds(gameSeconds(), scale); }
    }
    private final long maxCatchUpTicks;
    private final ArrayDeque<Change> changes = new ArrayDeque<>();
    private long observedTick = -1;

    public ThermalClock(long maxCatchUpTicks) {
        if (maxCatchUpTicks < 1) { throw new IllegalArgumentException("Catch-up window must be positive"); }
        this.maxCatchUpTicks = maxCatchUpTicks;
    }

    public static double requireScale(double scale) {
        if (!Double.isFinite(scale) || scale < MIN_SCALE || scale > MAX_SCALE) {
            throw new IllegalArgumentException("Thermal response scale must be finite and between 0.1 and 10");
        }
        return scale;
    }

    public static double thermalSeconds(double gameSeconds, double scale) {
        requireScale(scale);
        if (!Double.isFinite(gameSeconds) || gameSeconds < 0 || !Double.isFinite(gameSeconds * scale)) {
            throw new IllegalArgumentException("Elapsed game seconds must be finite, non-negative and representable");
        }
        return gameSeconds * scale;
    }

    /** Observe the effective setting at a game tick, after integrating all earlier ticks at the previous rate. */
    public void observe(long tick, double scale) {
        requireScale(scale);
        if (tick < 0 || tick < observedTick) { throw new IllegalArgumentException("Game ticks must be monotonic"); }
        Change last = changes.peekLast();
        if (last == null || last.scale != scale) {
            if (last != null && last.tick == tick) { changes.removeLast(); }
            changes.addLast(new Change(tick, scale));
        }
        observedTick = tick;
        long oldest = Math.max(0, tick - maxCatchUpTicks);
        while (changes.size() > 1) {
            Iterator<Change> iterator = changes.iterator();
            iterator.next();
            if (iterator.next().tick > oldest) { break; }
            changes.removeFirst();
        }
    }

    /** Integrates the retained catch-up window. The initial observed rate also applies before its first tick. */
    public Interval interval(long sinceTick, long nowTick) {
        if (changes.isEmpty() || sinceTick < 0 || sinceTick > nowTick || nowTick != observedTick) {
            throw new IllegalArgumentException("Observe time before requesting a valid elapsed interval");
        }
        long start = Math.max(sinceTick, Math.max(0, nowTick - maxCatchUpTicks));
        double scale = changes.getFirst().scale, thermalTicks = 0;
        long cursor = start;
        for (Change change : changes) {
            if (change.tick <= start) { scale = change.scale; continue; }
            if (change.tick >= nowTick) { break; }
            thermalTicks += (change.tick - cursor) * scale;
            cursor = change.tick;
            scale = change.scale;
        }
        thermalTicks += (nowTick - cursor) * scale;
        return new Interval((nowTick - start) / 20.0, thermalTicks / 20.0);
    }

    /** Snapshot chronological rates within the same retained window as interval().
     * Coupled callers must preserve these transitions while subdividing physical time;
     * an interval's average rate can erase temperature/buoyancy history.
     * This does not schedule jobs, reconstruct past source/weather inputs, or advance a grid.
     */
    public List<Segment> segments(long sinceTick, long nowTick) {
        if (changes.isEmpty() || sinceTick < 0 || sinceTick > nowTick || nowTick != observedTick) {
            throw new IllegalArgumentException("Observe time before requesting a valid elapsed interval");
        }
        long start = Math.max(sinceTick, Math.max(0, nowTick - maxCatchUpTicks));
        double scale = changes.getFirst().scale;
        long cursor = start;
        var result = new ArrayList<Segment>();
        for (Change change : changes) {
            if (change.tick <= start) { scale = change.scale; continue; }
            if (change.tick >= nowTick) { break; }
            result.add(new Segment(cursor, change.tick, scale));
            cursor = change.tick; scale = change.scale;
        }
        if (cursor < nowTick) { result.add(new Segment(cursor, nowTick, scale)); }
        return List.copyOf(result);
    }
}
