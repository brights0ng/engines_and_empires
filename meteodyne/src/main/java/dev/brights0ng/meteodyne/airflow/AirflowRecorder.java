// Written by Claude (GreedyFluids M7b b4); edited by Claude (M7f: the profile by segment against M6f; M8b b4:
// secondaries; M8b5b: merges and splits).
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
import it.unimi.dsi.fastutil.ints.IntArrayList;
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
 * <p><b>Profile (M7f f2; Bright, 2026-11-15: the smoke bar decides, the M6f comparison is reported).</b> The summary
 * also gives each region's share of a core (its turns' wall time over the wall time it existed), the share of steps
 * the velocity hold kept heat-only, the edit batches and their wall time, and the JVM's used heap. {@code summary.md}
 * splits them by segment, a segment running from one mark to the next, beside M6f's offline replay of the same
 * activity when the mark names one (settling, mining, digging, building). {@code regions.csv} gains each region's
 * busy share per second and its cumulative held steps, edit batches and edit ms; {@code seconds.csv} the used heap.
 * <p><b>Secondaries (M8b b4).</b> {@code regions.csv} gains each region's role (PLAYER, SECONDARY or GRACE) and
 * {@code seconds.csv} the secondaries running and the tiles that would run at 2, 4 and 8 shared cells; the summary
 * adds their tile-seconds over the recording and the secondaries' share of the regions' air-thread time.
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
    // M7f profile, one entry per recorded second (index second - 1)
    private final LongArrayList secWall = new LongArrayList(), secBusy = new LongArrayList(),
            secSteps = new LongArrayList(), secHeld = new LongArrayList(), secBatches = new LongArrayList(),
            secEdit = new LongArrayList(), secDrops = new LongArrayList();
    private final IntArrayList secRunning = new IntArrayList();
    private final DoubleArrayList heapMb = new DoubleArrayList();
    private final IntArrayList frameAgeSecond = new IntArrayList(); // beside frameAges
    private final IntArrayList markSeconds = new IntArrayList();
    private final List<String> markTexts = new ArrayList<>();
    // M8b b4: the manager's tile-tick counters at the start, and the secondaries' busy time per second
    private final long secondaryTicksAtStart;
    /** M8b5b: the manager's merge, split and given-up counts at the start. */
    private final long mergesAtStart, splitsAtStart, abandonedAtStart;
    private final long[] wouldTicksAtStart = new long[AirflowNeighbourTiles.REPORTED.length];
    private final LongArrayList secSecondaryBusy = new LongArrayList();

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
                    + "air_busy_pct,tick_mean_us,tick_max_us,heap_mb,secondaries,would_2,would_4,would_8\n");
            regionRows.write("second,region,slot,x0,y0,z0,status,boxes,step_ms,lag_s,queued,frame_age_s,steps,drops,"
                    + "failures,edits,toggles,desyncs,watts,busy_pct,held,edit_batches,edit_ms,role\n");
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
        secondaryTicksAtStart = regions.secondaryTicks();
        mergesAtStart = regions.merges();
        splitsAtStart = regions.splits();
        abandonedAtStart = regions.transitionsAbandoned();
        for (int i = 0; i < wouldTicksAtStart.length; i++) {
            wouldTicksAtStart[i] = regions.wouldTicks(i);
        }
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
        long wall = Math.max(1, now - lastWall);
        double busyPct = 100.0 * (busyNow - lastBusy) / wall;
        lastBusy = busyNow;
        lastWall = now;
        busy.add(busyPct);
        int running = 0, failed = 0, pending = 0;
        long busySum = 0, stepsSum = 0, heldSum = 0, batchSum = 0, editSum = 0, dropSum = 0, secondaryBusy = 0;
        for (AirflowRegions.Region r : regions.regions()) {
            AirflowRuntime.Slot slot = r.slot();
            String role = r.occupied() ? "PLAYER" : r.secondary() ? "SECONDARY" : "GRACE";
            if (r.tiles() > 1) {
                role += "_MERGED" + r.tiles(); // M8b5b
            }
            if (slot == null) {
                pending++;
                write(regionRows, second + "," + r.id() + ",," + r.x0() + "," + r.y0() + "," + r.z0()
                        + ",READING,,,,,,,,,,,,,,,," + "," + role);
                continue;
            }
            AirflowRuntime.Status status = slot.status();
            running += status == AirflowRuntime.Status.RUNNING ? 1 : 0;
            failed += status == AirflowRuntime.Status.FAILED ? 1 : 0;
            AirflowRuntime.Frame f = slot.frame();
            double age = f == null ? Double.NaN : (tick - f.tick()) * AirflowRuntime.TICK_SECONDS;
            if (status == AirflowRuntime.Status.RUNNING && f != null) {
                frameAges.add(age);
                frameAgeSecond.add(second - 1);
                stepMs.add(slot.stepMs());
                maxBoxes = Math.max(maxBoxes, slot.boxes());
            }
            AirflowRuntime.Counters c = slot.counters();
            // drops, desyncs, busy ns, held steps, steps, edit batches, edit ns, as last seen
            long[] seen = slots.computeIfAbsent(slot.id(), k -> new long[7]);
            if (slot.drops() > seen[0]) {
                dropTimes.add(second + " s (region " + r.id() + ")");
                dropSum += slot.drops() - seen[0];
            }
            long busyNanos = slot.busyNanos();
            double regionBusy = 100.0 * (busyNanos - seen[2]) / wall;
            if (status == AirflowRuntime.Status.RUNNING) {
                busySum += busyNanos - seen[2];
                secondaryBusy += r.secondary() ? busyNanos - seen[2] : 0;
                heldSum += c.held() - seen[3];
                stepsSum += slot.steps() - seen[4];
                batchSum += c.editBatches() - seen[5];
                editSum += c.editNanos() - seen[6];
            }
            seen[0] = slot.drops();
            seen[1] = c.desyncs();
            seen[2] = busyNanos;
            seen[3] = c.held();
            seen[4] = slot.steps();
            seen[5] = c.editBatches();
            seen[6] = c.editNanos();
            failures.merge(r.id(), slot.failures(), Math::max);
            write(regionRows, String.format(Locale.ROOT,
                    "%d,%d,%d,%d,%d,%d,%s,%d,%.3f,%.3f,%d,%.2f,%d,%d,%d,%d,%d,%d,%.0f,%.2f,%d,%d,%.1f,%s",
                    second, r.id(), slot.id(), r.x0(), r.y0(), r.z0(), status, slot.boxes(), slot.stepMs(), slot.lag(),
                    slot.queued(), age, slot.steps(), slot.drops(), slot.failures(), c.edits(), c.toggles(), c.desyncs(),
                    c.watts(), regionBusy, c.held(), c.editBatches(), c.editNanos() / 1e6, role));
        }
        maxRegions = Math.max(maxRegions, regions.regions().size());
        Runtime jvm = Runtime.getRuntime();
        double heap = (jvm.totalMemory() - jvm.freeMemory()) / 1048576.0;
        secWall.add(wall);
        secRunning.add(running);
        secBusy.add(busySum);
        secSteps.add(stepsSum);
        secHeld.add(heldSum);
        secBatches.add(batchSum);
        secEdit.add(editSum);
        secDrops.add(dropSum);
        secSecondaryBusy.add(secondaryBusy);
        heapMb.add(heap);
        write(seconds, String.format(Locale.ROOT, "%d,%d,%d,%d,%d,%d,%d,%d,%d,%.2f,%s,%s,%.0f,%d,%d,%d,%d", second, tick,
                regions.level().players().size(), regions.regions().size(), running, failed, pending, s.cached(),
                s.pending(), busyPct, us(sum / inSecond), us(max), heap, regions.secondariesNow(), regions.wouldNow(0),
                regions.wouldNow(1), regions.wouldNow(2)));
        inSecond = 0;
        sum = 0;
        max = 0;
        flush();
    }

    /** Adds a note at the current second. */
    public void mark(String text) {
        String line = second + "," + regions.level().getGameTime() + "," + text.replace(',', ';').replace('\n', ' ');
        markLines.add(second + " s: " + text);
        markSeconds.add(second);
        markTexts.add(text);
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
            List<String> lines = new ArrayList<>(summary());
            lines.addAll(profile());
            Files.writeString(dir.resolve("summary.md"), String.join("\n", lines) + "\n");
        } catch (IOException e) {
            error = error == null ? e : error;
        }
    }

    /** The summary's lines (also what the stop command prints). */
    public List<String> summary() {
        Measures m = measures();
        double meanMs = m.serverMeanMs(), p99Ms = m.serverP99Ms(), maxMs = m.serverMaxMs(), busyMean = m.airBusyPct();
        double ageP95 = m.frameAgeP95();
        long drops = m.drops(), desyncs = m.desyncs();
        int fails = m.failures();
        List<String> out = new ArrayList<>();
        out.add("# Airflow smoke summary (GreedyFluids M7b b4)");
        out.add("");
        out.add(String.format(Locale.ROOT, "Recorded %d s (%d ticks, game ticks %d–%d) into `%s`. Regions at most %d at once; "
                        + "%d boxes at most; mean %.2f ms per step.", second, m.ticks(), firstTick, lastTickSeen,
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
        Segment all = segment(0, secWall.size());
        if (all != null) {
            out.add("");
            out.add(String.format(Locale.ROOT, "Profile (M7f): each region used %s of a core; the hold kept %s of steps "
                            + "heat-only; %d edit batches at %s each (worst one-second mean %s); heap at most %.0f MB "
                            + "(the whole JVM, the client too in single player).", pct(all.share()), pct(all.held()),
                    all.batches(), ms(all.perBatch()), ms(all.worstBatch()), all.heapMax()));
        }
        out.add("");
        out.add(neighbours());
        out.add("");
        out.add(String.format(Locale.ROOT, "Merged regions (M8b5b, %s, settle %.1f s): %d merges, %d splits, %d given "
                        + "up over the recording; regions.csv marks a merged region's role _MERGED<tiles>.",
                regions.mergeMode(), regions.mergeSettleSeconds(), regions.merges() - mergesAtStart,
                regions.splits() - splitsAtStart, regions.transitionsAbandoned() - abandonedAtStart));
        return out;
    }

    /**
     * M8b b4: the secondaries' tile-seconds over the recording and their share of the regions' air-thread time, and
     * the tile-seconds that would have run at each reported threshold.
     */
    String neighbours() {
        long busyAll = 0, busySecondary = 0;
        for (int s = 0; s < secBusy.size(); s++) {
            busyAll += secBusy.getLong(s);
            busySecondary += secSecondaryBusy.getLong(s);
        }
        StringBuilder at = new StringBuilder(), would = new StringBuilder();
        for (int i = 0; i < wouldTicksAtStart.length; i++) {
            String sep = i == 0 ? "" : " / ";
            at.append(i == 0 ? "" : "/").append(AirflowNeighbourTiles.REPORTED[i]);
            would.append(sep).append(String.format(Locale.ROOT, "%.0f", (regions.wouldTicks(i) - wouldTicksAtStart[i]) / 20.0));
        }
        return String.format(Locale.ROOT, "Neighbour tiles (M8b b4, %s at %d cells): secondaries ran %.0f tile-s, %s of "
                        + "the regions' air-thread time; at %s cells, %s tile-s would have run (tiles a player is in "
                        + "left out).", regions.neighbourMode(), regions.neighbourMinCells(),
                (regions.secondaryTicks() - secondaryTicksAtStart) / 20.0,
                busyAll == 0 ? "—" : pct(100.0 * busySecondary / busyAll), at, would);
    }

    /** One segment's profile (M7f): seconds [from, to) of the recording, 0-based. */
    private record Segment(int from, int to, double regions, double share, double held, long batches, double perBatch,
            double worstBatch, double ageP95, double serverMean, double serverP99, double heapMax, long drops) {
    }

    /** The smoke bar's measures so far (M7f f2: the soak checks them too). */
    record Measures(int ticks, double serverMeanMs, double serverP99Ms, double serverMaxMs, double airBusyPct,
            double frameAgeP95, int failures, long desyncs, long drops) {
    }

    Measures measures() {
        long[] sorted = tickNanos.toLongArray();
        Arrays.sort(sorted);
        double meanMs = sorted.length == 0 ? 0 : Arrays.stream(sorted).average().orElse(0) / 1e6;
        double p99Ms = percentile(sorted, 0.99) / 1e6, maxMs = sorted.length == 0 ? 0 : sorted[sorted.length - 1] / 1e6;
        double[] ages = frameAges.toDoubleArray();
        Arrays.sort(ages);
        long drops = 0, desyncs = 0;
        for (long[] s : slots.values()) {
            drops += s[0];
            desyncs += s[1];
        }
        return new Measures(sorted.length, meanMs, p99Ms, maxMs, busy.doubleStream().average().orElse(0),
                percentileD(ages, 0.95), failures.values().stream().mapToInt(Integer::intValue).sum(), desyncs, drops);
    }

    /** Each region's share of a core over recorded seconds [from, to), 0-based, % (NaN: no region ran). */
    double coreShare(int from, int to) {
        Segment s = segment(from, to);
        return s == null ? Double.NaN : s.share();
    }

    /** The seconds at which the lag cap dropped a region's backlog. */
    List<Integer> dropSeconds() {
        List<Integer> out = new ArrayList<>();
        for (String d : dropTimes) {
            out.add(Integer.parseInt(d.substring(0, d.indexOf(' '))));
        }
        return out;
    }

    private Segment segment(int from, int to) {
        to = Math.min(to, secWall.size());
        if (to <= from) {
            return null;
        }
        double regionNanos = 0, regionsSum = 0, worst = Double.NaN, heap = 0;
        long busyNs = 0, steps = 0, held = 0, batches = 0, edit = 0, drops = 0;
        for (int s = from; s < to; s++) {
            regionNanos += (double) secWall.getLong(s) * secRunning.getInt(s);
            regionsSum += secRunning.getInt(s);
            busyNs += secBusy.getLong(s);
            steps += secSteps.getLong(s);
            held += secHeld.getLong(s);
            batches += secBatches.getLong(s);
            edit += secEdit.getLong(s);
            drops += secDrops.getLong(s);
            heap = Math.max(heap, heapMb.getDouble(s));
            if (secBatches.getLong(s) > 0) {
                double mean = secEdit.getLong(s) / 1e6 / secBatches.getLong(s);
                worst = Double.isNaN(worst) ? mean : Math.max(worst, mean);
            }
        }
        DoubleArrayList ages = new DoubleArrayList();
        for (int i = 0; i < frameAges.size(); i++) {
            int s = frameAgeSecond.getInt(i);
            if (s >= from && s < to) {
                ages.add(frameAges.getDouble(i));
            }
        }
        double[] a = ages.toDoubleArray();
        Arrays.sort(a);
        int t0 = Math.min(from * 20, tickNanos.size()), t1 = Math.min(to * 20, tickNanos.size());
        long[] t = Arrays.copyOfRange(tickNanos.toLongArray(), t0, t1);
        Arrays.sort(t);
        return new Segment(from, to, regionsSum / (to - from),
                regionNanos == 0 ? Double.NaN : 100.0 * busyNs / regionNanos,
                steps == 0 ? Double.NaN : 100.0 * held / steps, batches,
                batches == 0 ? Double.NaN : edit / 1e6 / batches, worst, percentileD(a, 0.95),
                t.length == 0 ? Double.NaN : Arrays.stream(t).average().orElse(0) / 1e6,
                t.length == 0 ? Double.NaN : percentile(t, 0.99) / 1e6, heap, drops);
    }

    /** The segment table for {@code summary.md} (M7f): one row per stretch between marks. */
    List<String> profile() {
        List<String> out = new ArrayList<>();
        int n = secWall.size();
        if (n == 0 || markSeconds.isEmpty()) {
            return out;
        }
        IntArrayList starts = new IntArrayList();
        List<String> names = new ArrayList<>();
        if (markSeconds.getInt(0) > 0) {
            starts.add(0);
            names.add("(before the first mark)");
        }
        for (int i = 0; i < markSeconds.size(); i++) {
            int s = Math.min(markSeconds.getInt(i), n);
            if (!starts.isEmpty() && starts.getInt(starts.size() - 1) == s) {
                names.set(names.size() - 1, names.get(names.size() - 1) + "; " + markTexts.get(i));
            } else {
                starts.add(s);
                names.add(markTexts.get(i));
            }
        }
        out.add("");
        out.add("## Profile by segment (M7f)");
        out.add("");
        out.add("A segment runs from one mark to the next. Core share: each region's air-thread wall time over the time it "
                + "ran. M6f: the offline replay of the busiest 600 s of the same activity (64-wide windows 48 high, "
                + "kappa 1, game walls), per region, still / wind 8 m/s; then the hold's share and ms per edit batch.");
        out.add("");
        out.add("| Segment | Seconds | Regions | Core share per region | Held | Edit batches | ms per batch (mean / worst 1-s) "
                + "| Frame age p95 | Server ms mean / p99 | Heap max MB | Drops | M6f |");
        out.add("|---|---|---|---|---|---|---|---|---|---|---|---|");
        for (int k = 0; k < starts.size(); k++) {
            Segment g = segment(starts.getInt(k), k + 1 < starts.size() ? starts.getInt(k + 1) : n);
            if (g == null) {
                continue;
            }
            out.add(String.format(Locale.ROOT, "| %s | %d–%d | %.1f | %s | %s | %d | %s / %s | %s | %s / %s | %.0f | %d | %s |",
                    names.get(k).replace('|', '/'), g.from(), g.to(), g.regions(), pct(g.share()), pct(g.held()),
                    g.batches(), ms(g.perBatch()), ms(g.worstBatch()),
                    Double.isNaN(g.ageP95()) ? "—" : String.format(Locale.ROOT, "%.2f s", g.ageP95()),
                    num(g.serverMean()), num(g.serverP99()), g.heapMax(), g.drops(), m6f(names.get(k))));
        }
        return out;
    }

    /** M6f's offline replay of the busiest 600 s of the activity a mark names (64-wide), or a dash. */
    static String m6f(String mark) {
        String t = mark.toLowerCase(Locale.ROOT);
        if (t.contains("settl")) {
            return "1.55 / 2.28 %; held 29 %; 13 ms";
        }
        if (t.contains("mining") || t.contains("mine")) {
            return "3.32 / 4.80 %; held 2–3 %; 13 ms";
        }
        if (t.contains("dig")) {
            return "2.11 / 2.83 %; held 41 %; 15 ms";
        }
        if (t.contains("build")) {
            return "2.43 / 3.51 %; held 6 %; 13 ms";
        }
        return "—";
    }

    private static String pct(double v) {
        return Double.isNaN(v) ? "—" : String.format(Locale.ROOT, "%.2f %%", v);
    }

    private static String ms(double v) {
        return Double.isNaN(v) ? "—" : String.format(Locale.ROOT, "%.1f ms", v);
    }

    private static String num(double v) {
        return Double.isNaN(v) ? "—" : String.format(Locale.ROOT, "%.3f", v);
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
