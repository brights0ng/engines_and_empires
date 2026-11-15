// Written by Claude (GreedyFluids M7b b4).
package dev.brights0ng.meteodyne.airflow;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import it.unimi.dsi.fastutil.doubles.DoubleArrayList;
import it.unimi.dsi.fastutil.longs.LongArrayList;

/**
 * The b4 smoke recorder ({@code /meteodyne airflow record}), on the server thread. It writes four CSVs as it goes and
 * a summary at the end:
 * <ul>
 *   <li>{@code ticks.csv}: per server tick, the airflow work on the server thread, µs: the block hook, section reads,
 *       the manager, and their total.</li>
 *   <li>{@code seconds.csv}: per second, players, regions by state, cached and pending sections, the air thread's
 *       busy share of one core, and the tick total's mean and max.</li>
 *   <li>{@code regions.csv}: per second and region, its window, state, boxes, ms per step, lag, queue, frame age
 *       (game time behind the tick), steps, drops, failures, edits, toggles, desyncs and source watts.</li>
 *   <li>{@code marks.csv}: notes added with {@code record mark} (joins, teleports, anything worth lining up).</li>
 *   <li>{@code summary.md}: the measures against Bright's pass bar (2026-11-14, option 1a): server thread mean ≤ 0.2
 *       ms and p99 ≤ 1 ms per tick; air thread ≤ 50 % of a core; frame age p95 ≤ 2 s; no failures or desyncs; drops
 *       only at joins or teleports (listed against the marks, for a person to judge).</li>
 * </ul>
 */
public final class AirflowRecorder {

    private final AirflowRegions regions;
    private final Path dir;
    private final BufferedWriter ticks, seconds, regionRows, marks;
    private long firstTick = -1, lastTickSeen;
    private long lastHook, lastRead, lastManager, lastBusy, lastWall;
    private long sum, max;
    private int inSecond, second;
    private final LongArrayList tickNanos = new LongArrayList();
    private final DoubleArrayList busy = new DoubleArrayList();
    private final DoubleArrayList frameAges = new DoubleArrayList();
    private final DoubleArrayList stepMs = new DoubleArrayList();
    private final Map<Long, long[]> slots = new HashMap<>(); // slot id → {drops, desyncs}
    private final Map<Integer, Integer> failures = new HashMap<>(); // region id → most failures seen
    private final List<String> dropTimes = new ArrayList<>();
    private final List<String> markLines = new ArrayList<>();
    private int maxBoxes, maxRegions;
    private IOException error;

    AirflowRecorder(AirflowRegions regions, Path dir) {
        this.regions = regions;
        this.dir = dir;
        try {
            Files.createDirectories(dir);
            ticks = Files.newBufferedWriter(dir.resolve("ticks.csv"));
            seconds = Files.newBufferedWriter(dir.resolve("seconds.csv"));
            regionRows = Files.newBufferedWriter(dir.resolve("regions.csv"));
            marks = Files.newBufferedWriter(dir.resolve("marks.csv"));
            ticks.write("tick,hook_us,read_us,manager_us,total_us\n");
            seconds.write("second,tick,players,regions,running,failed,pending_regions,cached_sections,pending_sections,"
                    + "air_busy_pct,tick_mean_us,tick_max_us\n");
            regionRows.write("second,region,slot,x0,y0,z0,status,boxes,step_ms,lag_s,queued,frame_age_s,steps,drops,"
                    + "failures,edits,toggles,desyncs,watts\n");
            marks.write("second,tick,text\n");
        } catch (IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
        AirflowSections.Stats s = regions.sections().stats();
        lastHook = s.hookNanos();
        lastRead = s.readNanos();
        lastManager = regions.managerNanos();
        lastBusy = regions.runtime().busyNanos();
        lastWall = System.nanoTime();
    }

    public Path dir() {
        return dir;
    }

    /** Seconds recorded so far. */
    public int seconds() {
        return second;
    }

    /** One server tick (after every level has ticked). */
    void tick() {
        long tick = regions.level().getGameTime();
        if (firstTick < 0) {
            firstTick = tick;
        }
        lastTickSeen = tick;
        AirflowSections.Stats s = regions.sections().stats();
        long hook = s.hookNanos() - lastHook, read = s.readNanos() - lastRead, manager = regions.managerNanos() - lastManager;
        lastHook = s.hookNanos();
        lastRead = s.readNanos();
        lastManager = regions.managerNanos();
        long total = hook + read + manager;
        tickNanos.add(total);
        sum += total;
        max = Math.max(max, total);
        write(ticks, tick + "," + us(hook) + "," + us(read) + "," + us(manager) + "," + us(total));
        if (++inSecond == 20) {
            second(tick, s);
        }
    }

    private void second(long tick, AirflowSections.Stats s) {
        second++;
        long now = System.nanoTime(), busyNow = regions.runtime().busyNanos();
        double busyPct = 100.0 * (busyNow - lastBusy) / Math.max(1, now - lastWall);
        lastBusy = busyNow;
        lastWall = now;
        busy.add(busyPct);
        int running = 0, failed = 0, pending = 0;
        for (AirflowRegions.Region r : regions.regions()) {
            AirflowRuntime.Slot slot = r.slot();
            if (slot == null) {
                pending++;
                write(regionRows, second + "," + r.id() + ",," + r.x0() + "," + r.y0() + "," + r.z0()
                        + ",READING,,,,,,,,,,,,");
                continue;
            }
            AirflowRuntime.Status status = slot.status();
            running += status == AirflowRuntime.Status.RUNNING ? 1 : 0;
            failed += status == AirflowRuntime.Status.FAILED ? 1 : 0;
            AirflowRuntime.Frame f = slot.frame();
            double age = f == null ? Double.NaN : (tick - f.tick()) * AirflowRuntime.TICK_SECONDS;
            if (status == AirflowRuntime.Status.RUNNING && f != null) {
                frameAges.add(age);
                stepMs.add(slot.stepMs());
                maxBoxes = Math.max(maxBoxes, slot.boxes());
            }
            AirflowRuntime.Counters c = slot.counters();
            long[] seen = slots.computeIfAbsent(slot.id(), k -> new long[2]);
            if (slot.drops() > seen[0]) {
                dropTimes.add(second + " s (region " + r.id() + ")");
            }
            seen[0] = slot.drops();
            seen[1] = c.desyncs();
            failures.merge(r.id(), slot.failures(), Math::max);
            write(regionRows, String.format(Locale.ROOT, "%d,%d,%d,%d,%d,%d,%s,%d,%.3f,%.3f,%d,%.2f,%d,%d,%d,%d,%d,%d,%.0f",
                    second, r.id(), slot.id(), r.x0(), r.y0(), r.z0(), status, slot.boxes(), slot.stepMs(), slot.lag(),
                    slot.queued(), age, slot.steps(), slot.drops(), slot.failures(), c.edits(), c.toggles(), c.desyncs(),
                    c.watts()));
        }
        maxRegions = Math.max(maxRegions, regions.regions().size());
        write(seconds, String.format(Locale.ROOT, "%d,%d,%d,%d,%d,%d,%d,%d,%d,%.2f,%s,%s", second, tick,
                regions.level().players().size(), regions.regions().size(), running, failed, pending, s.cached(),
                s.pending(), busyPct, us(sum / inSecond), us(max)));
        inSecond = 0;
        sum = 0;
        max = 0;
        flush();
    }

    /** Adds a note at the current second. */
    public void mark(String text) {
        String line = second + "," + regions.level().getGameTime() + "," + text.replace(',', ';').replace('\n', ' ');
        markLines.add(second + " s: " + text);
        write(marks, line);
        flush();
    }

    /** Closes the CSVs and writes {@code summary.md}. */
    void close() {
        try {
            ticks.close();
            seconds.close();
            regionRows.close();
            marks.close();
        } catch (IOException e) {
            error = error == null ? e : error;
        }
        try {
            Files.writeString(dir.resolve("summary.md"), String.join("\n", summary()) + "\n");
        } catch (IOException e) {
            error = error == null ? e : error;
        }
    }

    /** The summary's lines (also what the stop command prints). */
    public List<String> summary() {
        long[] sorted = tickNanos.toLongArray();
        Arrays.sort(sorted);
        double meanMs = sorted.length == 0 ? 0 : Arrays.stream(sorted).average().orElse(0) / 1e6;
        double p99Ms = percentile(sorted, 0.99) / 1e6, maxMs = sorted.length == 0 ? 0 : sorted[sorted.length - 1] / 1e6;
        double busyMean = busy.doubleStream().average().orElse(0);
        double[] ages = frameAges.toDoubleArray();
        Arrays.sort(ages);
        double ageP95 = percentileD(ages, 0.95);
        long drops = 0, desyncs = 0;
        for (long[] s : slots.values()) {
            drops += s[0];
            desyncs += s[1];
        }
        int fails = failures.values().stream().mapToInt(Integer::intValue).sum();
        List<String> out = new ArrayList<>();
        out.add("# Airflow smoke summary (GreedyFluids M7b b4)");
        out.add("");
        out.add(String.format(Locale.ROOT, "Recorded %d s (%d ticks, game ticks %d–%d) into `%s`. Regions at most %d at once; "
                        + "%d boxes at most; mean %.2f ms per step.", second, sorted.length, firstTick, lastTickSeen,
                dir.toAbsolutePath(), maxRegions, maxBoxes, stepMs.doubleStream().average().orElse(0)));
        if (error != null) {
            out.add("");
            out.add("**Write error:** " + error);
        }
        out.add("");
        out.add("| Measure | Value | Bar | Result |");
        out.add("|---|---|---|---|");
        out.add(row("Server thread, mean per tick", String.format(Locale.ROOT, "%.3f ms", meanMs), "≤ 0.2 ms", meanMs <= 0.2));
        out.add(row("Server thread, p99 per tick", String.format(Locale.ROOT, "%.3f ms", p99Ms), "≤ 1 ms", p99Ms <= 1));
        out.add("| Server thread, worst tick | " + String.format(Locale.ROOT, "%.3f ms", maxMs) + " | (reported) | |");
        out.add(row("Air thread, mean busy", String.format(Locale.ROOT, "%.1f %% of a core", busyMean), "≤ 50 %",
                busyMean <= 50));
        out.add(row("Frame age, p95", Double.isNaN(ageP95) ? "no frames" : String.format(Locale.ROOT, "%.2f s", ageP95),
                "≤ 2 s", !Double.isNaN(ageP95) && ageP95 <= 2));
        out.add(row("Region failures", Integer.toString(fails), "0", fails == 0));
        out.add(row("Desyncs", Long.toString(desyncs), "0", desyncs == 0));
        out.add("| Backlog drops | " + drops + " | only at joins or teleports | " + (drops == 0 ? "PASS" : "CHECK against the marks") + " |");
        if (!dropTimes.isEmpty()) {
            out.add("");
            out.add("Drops at: " + String.join("; ", dropTimes) + ".");
        }
        if (!markLines.isEmpty()) {
            out.add("");
            out.add("Marks: " + String.join("; ", markLines) + ".");
        }
        return out;
    }

    private static String row(String measure, String value, String bar, boolean pass) {
        return "| " + measure + " | " + value + " | " + bar + " | " + (pass ? "PASS" : "FAIL") + " |";
    }

    private static double percentile(long[] sorted, double q) {
        if (sorted.length == 0) {
            return 0;
        }
        return sorted[Math.min(sorted.length - 1, (int) Math.ceil(q * sorted.length) - 1)];
    }

    private static double percentileD(double[] sorted, double q) {
        if (sorted.length == 0) {
            return Double.NaN;
        }
        return sorted[Math.min(sorted.length - 1, Math.max(0, (int) Math.ceil(q * sorted.length) - 1))];
    }

    private static String us(long nanos) {
        return String.format(Locale.ROOT, "%.1f", nanos / 1e3);
    }

    private void write(BufferedWriter w, String line) {
        if (error != null) {
            return;
        }
        try {
            w.write(line);
            w.write('\n');
        } catch (IOException e) {
            error = e;
        }
    }

    private void flush() {
        if (error != null) {
            return;
        }
        try {
            ticks.flush();
            seconds.flush();
            regionRows.flush();
            marks.flush();
        } catch (IOException e) {
            error = e;
        }
    }
}
