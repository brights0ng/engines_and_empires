// Written by Claude (GreedyFluids M8c c2); edited by Claude (c2: the REF run and error against it).
package dev.brights0ng.meteodyne.airflow;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

import dev.brights0ng.greedyfluids.refine.AdaptiveMesh3D;
import dev.brights0ng.greedyfluids.region.AirRegion;
import dev.brights0ng.greedyfluids.solve.Background3D;
import dev.brights0ng.meteodyne.airflow.AirflowBackgroundLog.Entry;
import dev.brights0ng.meteodyne.airflow.AirflowCells.Cell;
import dev.brights0ng.meteodyne.airflow.AirflowReplayTest.Variant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

/**
 * GreedyFluids M8c c2 (report-only; Bright's desktop): the wind levers on a replayed region. The default model and one
 * model per lever step in lockstep on the same recorded inputs ({@link AirflowReplayTest}'s replay: same spec, events
 * and input variant), so every difference in temperature is the lever's.
 * <ul>
 *   <li>DEFAULT: Meteodyne's region as it runs (the reference);</li>
 *   <li>TWIN: DEFAULT with its remesh cadence shifted by 16 steps: the noise floor any lever's drift is read against;</li>
 *   <li>CG: the thermal gate counts contrast between neighbouring air instead of departure from the background (W1);</li>
 *   <li>GATE05, GATE10: the thermal gate at 0.5 and 1 °C instead of 0.25 (W2);</li>
 *   <li>EPSU1, EPSU2: the velocity indicator's threshold at 1 and 2 m/s instead of 0.5 (W3);</li>
 *   <li>HOLDFLOW: the hold's flow test tolerates 50 % instead of 25 % (W4);</li>
 *   <li>REF: no thermal gate and no velocity hold: velocity refines everywhere and steps every step (the M7a
 *       reference check's REF). With it, each lever's error against REF is reported beside its drift from DEFAULT, so a
 *       lever docks no accuracy when its error is no larger than DEFAULT's.</li>
 * </ul>
 * Per 10 s and lever: core % (air-thread wall time per game second), boxes, held %, and the drift from DEFAULT over the
 * fluid cells (mean and 99th percentile of |ΔT|, and the mean over sheltered and partly following air). Per 10 s for
 * DEFAULT (W0): where velocity counts under the thermal gate, and how far the air sits from its background, by class
 * (OPEN, NEAR_GROUND, PARTIAL, SHELTERED; see {@link AirflowReplayTest}).
 * <p>Runs only with {@code -Pmeteodyne.airflowLevers=<names, comma-separated>} (DEFAULT and TWIN always run) and
 * {@code -Pmeteodyne.airflowReplay=<background.gfbg>}, {@code -Pmeteodyne.airflowReplayRegions=<one id>}; optional
 * {@code -Pmeteodyne.airflowReplayVariants=<one input variant>} (RECORDED, or WIND with
 * {@code -Pmeteodyne.airflowReplayWindSpeed}) and {@code -Pmeteodyne.airflowReplayFrom=<recording second>} (the summary
 * covers bins from it on; default 60 s after the build). Writes
 * {@code build/reports/airflow-replay/levers-<region>-<variant>.md}.
 */
@EnabledIfSystemProperty(named = "meteodyne.airflowLevers", matches = ".+")
class AirflowLeverReplayTest {

    enum Lever { DEFAULT, TWIN, CG, GATE05, GATE10, EPSU1, EPSU2, HOLDFLOW, REF }

    static void apply(Lever lever, AdaptiveMesh3D m) {
        switch (lever) {
            case TWIN -> m.setCadencePhaseForTesting(16);
            case CG -> m.setGateMode(AdaptiveMesh3D.GateMode.CONTRAST);
            case GATE05 -> m.setThermalVelocityGate(0.5);
            case GATE10 -> m.setThermalVelocityGate(1.0);
            case EPSU1 -> m.setVelocityThreshold(1.0);
            case EPSU2 -> m.setVelocityThreshold(2.0);
            case HOLDFLOW -> m.setAdaptiveHold(0.5, AirRegion.HOLD_DELTA_T);
            case REF -> {
                m.setThermalVelocityGate(Double.NaN);
                m.setVelocityHold(AirRegion.HOLD_REFRESH, 0, AirRegion.HOLD_WAKE);
            }
            default -> { }
        }
    }

    /** One lever's run and what it has gathered over the summary's bins. */
    private static final class Run {
        final Lever lever;
        final AirflowRegionModel model;
        long wall, held0;
        int steps;
        long boxSum;
        double coreSum, boxesSum, heldSum, worstMean, worstP99, worstIndoor, errMean, errP99, errIndoor;
        int bins;

        Run(Lever lever, AirflowRegionModel model) {
            this.lever = lever;
            this.model = model;
            held0 = model.region().mesh().heldSteps();
        }
    }

    @Test
    void levers() throws IOException {
        Path file = Path.of(System.getProperty("meteodyne.airflowReplay"));
        int id = Integer.parseInt(System.getProperty("meteodyne.airflowReplayRegions").split(",")[0].trim());
        Variant variant = Variant.valueOf(System.getProperty("meteodyne.airflowReplayVariants", "RECORDED")
                .split(",")[0].trim());
        List<Lever> levers = new ArrayList<>(List.of(Lever.DEFAULT, Lever.TWIN));
        for (String s : System.getProperty("meteodyne.airflowLevers").split(",")) {
            Lever l = Lever.valueOf(s.trim());
            if (!levers.contains(l)) {
                levers.add(l);
            }
        }
        AirflowBackgroundLog.Dump dump = AirflowBackgroundLog.read(file);
        List<Entry> events = new ArrayList<>();
        for (Entry e : dump.entries()) {
            if (e.id() == id) {
                events.add(e);
            }
        }
        assertTrue(!events.isEmpty() && events.get(0) instanceof AirflowBackgroundLog.Region, "no region " + id);
        AirflowBackgroundLog.Region build = (AirflowBackgroundLog.Region) events.get(0);
        long end = events.get(events.size() - 1).tick();
        for (Entry x : events) {
            if (x instanceof AirflowBackgroundLog.Retire) {
                end = x.tick();
            }
        }
        double life = (end - build.tick()) / 20.0, startSecond = (build.tick() - dump.startTick()) / 20.0;
        double from = Double.parseDouble(System.getProperty("meteodyne.airflowReplayFrom",
                Double.toString(startSecond + 60)));

        Cell[] palette = new Cell[build.palette().length];
        for (int i = 0; i < palette.length; i++) {
            palette[i] = AirflowReplayTest.cell(build.palette()[i], "code" + i);
        }
        int nx = build.nx(), ny = build.ny(), nz = build.nz();
        List<Run> runs = new ArrayList<>();
        for (Lever l : levers) {
            AirflowRegionModel m = AirflowRegionModel.build(AirflowReplayTest.spec(build, palette, variant));
            apply(l, m.region().mesh());
            runs.add(new Run(l, m));
        }
        Run ref = runs.get(0);
        Cell[] now = new Cell[nx * ny * nz];
        for (int c = 0; c < now.length; c++) {
            now[c] = palette[build.cells()[c] & 0xFF];
        }
        List<String> censusRows = new ArrayList<>(), binRows = new ArrayList<>();
        int next = 1;
        double binEnd = AirflowReplayTest.BIN;
        while (ref.model.time() < life) {
            List<Object> due = new ArrayList<>();
            while (next < events.size() && (events.get(next).tick() - build.tick()) / 20.0 <= ref.model.time()) {
                Object event = AirflowReplayTest.event(events.get(next++), nx, nz, now, build, variant);
                if (event != null) {
                    due.add(event);
                }
            }
            for (Run r : runs) {
                if (!due.isEmpty()) {
                    r.model.apply(due);
                }
                long t0 = System.nanoTime();
                r.model.step();
                r.wall += System.nanoTime() - t0;
                r.steps++;
                r.boxSum += r.model.boxes();
            }
            if (ref.model.time() < binEnd - 1e-9) {
                continue;
            }
            double second = startSecond + binEnd;
            boolean counted = second >= from;
            byte[] classes = AirflowReplayTest.classes(ref.model.region().grid(), ref.model.region().background());
            float[] refAir = ref.model.region().thermalState().airArray();
            Run truth = runs.stream().filter(x -> x.lever == Lever.REF).findFirst().orElse(null);
            float[] truthAir = truth == null ? null : truth.model.region().thermalState().airArray();
            censusRows.add(census(second, ref.model, classes, refAir));
            StringBuilder row = new StringBuilder(String.format(Locale.ROOT, "| %.0f |", second));
            for (Run r : runs) {
                double core = 100 * r.wall * 1e-9 / AirflowReplayTest.BIN, boxes = (double) r.boxSum / r.steps;
                double held = 100.0 * (r.model.region().mesh().heldSteps() - r.held0) / r.steps;
                double[] drift = r == ref ? new double[3] : drift(refAir, r.model.region().thermalState().airArray(),
                        classes);
                row.append(String.format(Locale.ROOT, " %.2f / %.0fk / %.0f / %.3f, %.2f |", core, boxes / 1000, held,
                        drift[0], drift[1]));
                double[] err = truthAir == null ? new double[3]
                        : drift(truthAir, r.model.region().thermalState().airArray(), classes);
                if (counted) {
                    r.errMean = Math.max(r.errMean, err[0]);
                    r.errP99 = Math.max(r.errP99, err[1]);
                    r.errIndoor = Math.max(r.errIndoor, err[2]);
                    r.coreSum += core;
                    r.boxesSum += boxes;
                    r.heldSum += held;
                    r.worstMean = Math.max(r.worstMean, drift[0]);
                    r.worstP99 = Math.max(r.worstP99, drift[1]);
                    r.worstIndoor = Math.max(r.worstIndoor, drift[2]);
                    r.bins++;
                }
                r.wall = 0;
                r.steps = 0;
                r.boxSum = 0;
                r.held0 = r.model.region().mesh().heldSteps();
            }
            binRows.add(row.toString());
            binEnd += AirflowReplayTest.BIN;
        }

        String name = "levers-" + id + "-" + variant
                + (variant == Variant.WIND ? String.format(Locale.ROOT, "%.0f", AirflowReplayTest.WIND_SPEED) : "");
        List<String> out = new ArrayList<>();
        out.add(String.format(Locale.ROOT, "# Wind levers on a replay: region %d, %s (GreedyFluids M8c c2, report-only)",
                id, variant == Variant.WIND ? "wind " + AirflowReplayTest.WIND_SPEED + " m/s" : variant.toString()));
        out.add("");
        out.add(String.format(Locale.ROOT, "Every lever steps in lockstep with DEFAULT on the same inputs; the summary covers "
                + "recording seconds %.0f–%.0f. Drift: |ΔT| from DEFAULT over fluid cells, °C (worst 10 s bin): volume "
                + "mean, 99th percentile, and the mean over sheltered and partly following air. TWIN (DEFAULT with "
                + "its remesh cadence shifted) is the noise floor.", from, startSecond + life));
        out.add("");
        out.add("| Lever | Core % | × DEFAULT | Boxes | Held % | Drift mean | Drift p99 | Drift indoor mean | Error vs REF: mean "
                + "| p99 | indoor mean |");
        out.add("|---|---|---|---|---|---|---|---|---|---|---|");
        for (Run r : runs) {
            double core = r.coreSum / r.bins;
            out.add(String.format(Locale.ROOT, "| %s | %.2f | %.2f | %.1fk | %.0f | %.3f | %.3f | %.3f | %s |", r.lever,
                    core, core / (ref.coreSum / ref.bins), r.boxesSum / r.bins / 1000, r.heldSum / r.bins, r.worstMean,
                    r.worstP99, r.worstIndoor, levers.contains(Lever.REF) ? String.format(Locale.ROOT,
                            "%.3f | %.3f | %.3f", r.errMean, r.errP99, r.errIndoor) : "— | — | —"));
        }
        out.add("");
        out.add("## W0: where velocity counts (DEFAULT)");
        out.add("");
        out.add("Per class (OPEN / NEAR_GROUND / PARTIAL / SHELTERED): the share of its cells where velocity counts under "
                + "the thermal gate (warm boxes and their neighbours), the mean |air − background|, °C, and the share "
                + "more than 0.25 °C from it.");
        out.add("");
        out.add("| Second | Velocity counts, % | Mean departure, °C | Over 0.25 °C, % |");
        out.add("|---|---|---|---|");
        out.addAll(censusRows);
        out.add("");
        out.add("## Per 10 s");
        out.add("");
        StringBuilder head = new StringBuilder("| Second |"), rule = new StringBuilder("|---|");
        for (Run r : runs) {
            head.append(" ").append(r.lever).append(": core % / boxes / held % / drift mean, p99 |");
            rule.append("---|");
        }
        out.add(head.toString());
        out.add(rule.toString());
        out.addAll(binRows);
        Files.createDirectories(AirflowReplayTest.OUT);
        Files.write(AirflowReplayTest.OUT.resolve(name + ".md"), out);
        System.out.printf(Locale.ROOT, "levers on region %d, %s: %d bins%n", id, variant, binRows.size());
    }

    /** One W0 row: per class, % of cells where velocity counts, mean |air − background| and % over 0.25 °C. */
    private static String census(double second, AirflowRegionModel model, byte[] classes, float[] air) {
        AdaptiveMesh3D mesh = model.region().mesh();
        Background3D bg = model.region().background();
        int[] cells = new int[4], counts = new int[4], over = new int[4];
        double[] departure = new double[4];
        for (int c = 0; c < classes.length; c++) {
            byte k = classes[c];
            if (k < 0) {
                continue;
            }
            cells[k]++;
            if (mesh.velocityCounts(c)) {
                counts[k]++;
            }
            double d = Math.abs(air[c] - bg.cell(c));
            departure[k] += d;
            if (d > 0.25) {
                over[k]++;
            }
        }
        StringBuilder a = new StringBuilder(), b = new StringBuilder(), o = new StringBuilder();
        for (int k = 0; k < 4; k++) {
            String sep = k == 0 ? "" : " / ";
            a.append(sep).append(pct(counts[k], cells[k]));
            b.append(sep).append(cells[k] == 0 ? "—" : String.format(Locale.ROOT, "%.2f", departure[k] / cells[k]));
            o.append(sep).append(pct(over[k], cells[k]));
        }
        return String.format(Locale.ROOT, "| %.0f | %s | %s | %s |", second, a, b, o);
    }

    private static String pct(int n, int of) {
        return of == 0 ? "—" : String.format(Locale.ROOT, "%.0f", 100.0 * n / of);
    }

    /** Over fluid cells: mean and 99th percentile of |ΔT|, and the mean over PARTIAL and SHELTERED cells. */
    static double[] drift(float[] ref, float[] other, byte[] classes) {
        double[] d = new double[classes.length];
        int n = 0, indoorN = 0;
        double sum = 0, indoor = 0;
        for (int c = 0; c < classes.length; c++) {
            if (classes[c] < 0) {
                continue;
            }
            double x = Math.abs(ref[c] - other[c]);
            d[n++] = x;
            sum += x;
            if (classes[c] >= 2) {
                indoor += x;
                indoorN++;
            }
        }
        if (n == 0) {
            return new double[3];
        }
        double[] sorted = Arrays.copyOf(d, n);
        Arrays.sort(sorted);
        return new double[] {sum / n, sorted[Math.min(n - 1, (int) Math.ceil(0.99 * n) - 1)],
                indoorN == 0 ? 0 : indoor / indoorN};
    }
}
