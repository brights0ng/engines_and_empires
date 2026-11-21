// Written by Claude (GreedyFluids M8e e3c).
package dev.brights0ng.meteodyne.airflow;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

import dev.brights0ng.greedyfluids.region.AirFrame;
import dev.brights0ng.meteodyne.airflow.AirflowCells.Cell;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

/**
 * GreedyFluids M8e e3c, report-only: what the dynamic start costs and does on recorded regions. Each region of a
 * recording ({@code -Pmeteodyne.airflowReplay=<background.gfbg>}, optionally {@code -Pmeteodyne.airflowReplayRegions=<ids>})
 * is built as it was first built (RECORDED, without its saves), with the dynamic start off and on: the build's time,
 * the boxes, the estimate's weight, iterations and time, and the air's mean and hottest offset over its background at
 * the first frame, over all its air and over its enclosed air (below its column's open height). Regions named in
 * {@code airflowReplayRegions} are also run cold with the full solver for the horizon (1,200 s, the recording's weather
 * at the build held), and its enclosed air compared with the estimate's. Writes
 * {@code build/reports/airflow-replay/start.md}.
 */
@EnabledIfSystemProperty(named = "meteodyne.airflowReplay", matches = ".+")
class AirflowStartCostTest {

    @Test
    void startCost() throws IOException {
        Path file = Path.of(System.getProperty("meteodyne.airflowReplay"));
        Set<Integer> only = new HashSet<>();
        String regions = System.getProperty("meteodyne.airflowReplayRegions", "");
        for (String s : regions.split(",")) {
            if (!s.isBlank()) {
                only.add(Integer.parseInt(s.trim()));
            }
        }
        AirflowBackgroundLog.Dump dump = AirflowBackgroundLog.read(file);
        StringBuilder md = new StringBuilder("# The dynamic start on " + file + "\n\n"
                + "| Region | Tiles | Fires | Fixed cells | Start | Build ms | Boxes | Weight | Iterations | Estimate ms"
                + " | Air mean / hottest over background (°C) | Enclosed air mean / hottest (°C) |\n"
                + "|---|---|---|---|---|---|---|---|---|---|---|---|\n");
        StringBuilder compare = new StringBuilder();
        Set<Integer> seen = new HashSet<>();
        for (AirflowBackgroundLog.Entry e : dump.entries()) {
            if (!(e instanceof AirflowBackgroundLog.Region b) || !seen.add(b.id()) || !only.isEmpty() && !only.contains(b.id())) {
                continue;
            }
            Cell[] palette = new Cell[b.palette().length];
            for (int i = 0; i < palette.length; i++) {
                palette[i] = AirflowReplayTest.cell(b.palette()[i], "code" + i);
            }
            for (boolean on : new boolean[] {false, true}) {
                AirflowRegionModel.Spec spec = AirflowReplayTest.spec(b, palette, AirflowReplayTest.Variant.RECORDED);
                spec = spec.withTable(spec.table().withDynamicStart(on));
                long t0 = System.nanoTime();
                AirflowRegionModel m = AirflowRegionModel.build(spec);
                double buildMs = (System.nanoTime() - t0) * 1e-6;
                double[] air = air(m.publish(), null);
                double[] enclosed = air(m.publish(), spec.tops());
                int fixed = 0;
                var grid = m.region().grid();
                for (int c = 0; c < grid.nx() * grid.ny() * grid.nz(); c++) {
                    fixed += Double.isNaN(grid.fixedTemperature(c)) ? 0 : 1;
                }
                md.append(String.format(Locale.ROOT, "| %d | %d | %d | %d | %s | %.0f | %d | %.3f | %d | %.1f | %.2f / %.1f | %.2f / %.1f |%n",
                        b.id(), spec.nx() * spec.ny() * spec.nz() / (64 * 32 * 64), spec.sourceCells().length, fixed,
                        on ? "dynamic" : "cold", buildMs, m.boxes(), m.startWeight(), m.startIterations(), m.startMs(),
                        air[0], air[1], enclosed[0], enclosed[1]));
                assertTrue(Double.isFinite(air[0]) && Double.isFinite(air[1]), "region " + b.id());
                if (on && only.contains(b.id())) {
                    AirflowRegionModel.Spec coldSpec = spec.withTable(spec.table().withDynamicStart(false));
                    AirflowRegionModel full = AirflowRegionModel.build(coldSpec);
                    long s0 = System.nanoTime();
                    while (full.time() < SteadyHorizon.SECONDS - 1e-9) {
                        full.step();
                    }
                    double[] after = air(full.publish(), spec.tops());
                    compare.append(String.format(Locale.ROOT, "- Region %d, the full solver cold for %.0f s: enclosed air"
                                    + " %.2f / %.1f (mean / hottest over background, °C; %.0f s of stepping)%n", b.id(),
                            SteadyHorizon.SECONDS, after[0], after[1], (System.nanoTime() - s0) * 1e-9));
                    // the estimate at the faces touching tied air: κ_eff (the default), 0.7 and 0.5 (e3c: the solver's
                    // κ gave the house 4× the full solver's warmth, 0.2 2×)
                    for (double opening : new double[] {Double.NaN, 0.7, 0.5}) {
                        AirflowRegionModel.startSettings = dev.brights0ng.greedyfluids.solve.SteadyHeat3D.Settings.DEFAULT
                                .withOpeningKappa(opening);
                        AirflowRegionModel est = AirflowRegionModel.build(spec);
                        AirflowRegionModel.startSettings = dev.brights0ng.greedyfluids.solve.SteadyHeat3D.Settings.DEFAULT;
                        double[] at = air(est.publish(), spec.tops());
                        compare.append(String.format(Locale.ROOT, "  - opening κ %s: estimate %.2f / %.1f, %+.1f %% against the"
                                        + " full solver's mean; mean |estimate − full| %.2f °C over its cells%n",
                                Double.isNaN(opening) ? "κ_eff" : String.format(Locale.ROOT, "%.2f", opening), at[0], at[1],
                                100 * (at[0] / after[0] - 1), meanGap(est.publish(), full.publish(), spec.tops())));
                    }
                }
            }
        }
        md.append('\n').append(compare);
        Path out = Path.of("build/reports/airflow-replay/start.md");
        Files.createDirectories(out.getParent());
        Files.writeString(out, md);
        System.out.println(md);
    }

    /** The block sources' horizon, s (GreedyFluids' default). */
    private static final class SteadyHorizon {
        static final double SECONDS = dev.brights0ng.greedyfluids.solve.SteadyHeat3D.DEFAULT_HORIZON;
        static final double KAPPA = dev.brights0ng.greedyfluids.solve.SteadyHeat3D.DEFAULT_KAPPA;
    }

    private static boolean counts(AirFrame f, int[] tops, int x, int y, int z) {
        return f.isAir(x + 0.5, y + 0.5, z + 0.5) && (tops == null || y < tops[z * f.nx() + x]);
    }

    /** The mean and hottest offset of a frame's air over its background, C: all of it, or with tops only the enclosed. */
    private static double[] air(AirFrame f, int[] tops) {
        double sum = 0, max = Double.NEGATIVE_INFINITY;
        int n = 0;
        for (int y = 0; y < f.ny(); y++) {
            for (int z = 0; z < f.nz(); z++) {
                for (int x = 0; x < f.nx(); x++) {
                    if (counts(f, tops, x, y, z)) {
                        double d = f.temperature(x + 0.5, y + 0.5, z + 0.5) - f.background().at(x, y, z);
                        sum += d;
                        max = Math.max(max, d);
                        n++;
                    }
                }
            }
        }
        return new double[] {n == 0 ? Double.NaN : sum / n, max};
    }

    /** The mean |a − b| over the enclosed air cells of a, C. */
    private static double meanGap(AirFrame a, AirFrame b, int[] tops) {
        double sum = 0;
        int n = 0;
        for (int y = 0; y < a.ny(); y++) {
            for (int z = 0; z < a.nz(); z++) {
                for (int x = 0; x < a.nx(); x++) {
                    if (counts(a, tops, x, y, z) && b.isAir(x + 0.5, y + 0.5, z + 0.5)) {
                        sum += Math.abs(a.temperature(x + 0.5, y + 0.5, z + 0.5) - b.temperature(x + 0.5, y + 0.5, z + 0.5));
                        n++;
                    }
                }
            }
        }
        return n == 0 ? Double.NaN : sum / n;
    }
}
