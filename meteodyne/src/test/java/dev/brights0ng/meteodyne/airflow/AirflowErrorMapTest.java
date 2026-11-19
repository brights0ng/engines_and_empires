// Written by Claude (GreedyFluids M8c c2).
package dev.brights0ng.meteodyne.airflow;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

import dev.brights0ng.greedyfluids.grid.CellGrid3D;
import dev.brights0ng.greedyfluids.refine.AdaptiveMesh3D;
import dev.brights0ng.meteodyne.airflow.AirflowBackgroundLog.Entry;
import dev.brights0ng.meteodyne.airflow.AirflowCells.Cell;
import dev.brights0ng.meteodyne.airflow.AirflowLeverReplayTest.Lever;
import dev.brights0ng.meteodyne.airflow.AirflowReplayTest.Variant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

/**
 * GreedyFluids M8c c2 (report-only; Bright's desktop): where a lever's error lives. DEFAULT, REF and one lever step in
 * lockstep on a replayed region ({@link AirflowLeverReplayTest}); per 10 s, each one's |T − REF| over the fluid cells,
 * split by class of air (OPEN, NEAR_GROUND, PARTIAL, SHELTERED), by distance from the nearest heat source (≤ 3, 4–8,
 * 9–16, over 16 cells) and by distance from any edit so far (≤ 3 cells or not): the mean in each part and its share of
 * the total. At the bin where the lever's 99th percentile is worst, its 20 worst cells.
 * <p>Runs only with {@code -Pmeteodyne.airflowErrorMap=<lever>} and {@code -Pmeteodyne.airflowReplay},
 * {@code -Pmeteodyne.airflowReplayRegions=<one id>} (optional {@code …Variants}, {@code …WindSpeed}). Writes
 * {@code build/reports/airflow-replay/errormap-<region>-<lever>.md}.
 */
@EnabledIfSystemProperty(named = "meteodyne.airflowErrorMap", matches = ".+")
class AirflowErrorMapTest {

    static final String[] CLASS = {"OPEN", "NEAR", "PARTIAL", "SHELTERED"};
    static final String[] BAND = {"≤3", "4–8", "9–16", ">16"};

    @Test
    void errorMap() throws IOException {
        Path file = Path.of(System.getProperty("meteodyne.airflowReplay"));
        int id = Integer.parseInt(System.getProperty("meteodyne.airflowReplayRegions").split(",")[0].trim());
        Lever lever = Lever.valueOf(System.getProperty("meteodyne.airflowErrorMap").trim());
        Variant variant = Variant.valueOf(System.getProperty("meteodyne.airflowReplayVariants", "RECORDED")
                .split(",")[0].trim());
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
        Cell[] palette = new Cell[build.palette().length];
        for (int i = 0; i < palette.length; i++) {
            palette[i] = AirflowReplayTest.cell(build.palette()[i], "code" + i);
        }
        int nx = build.nx(), ny = build.ny(), nz = build.nz(), n = nx * ny * nz;
        Lever[] levers = {Lever.DEFAULT, Lever.REF, lever};
        AirflowRegionModel[] models = new AirflowRegionModel[3];
        for (int i = 0; i < 3; i++) {
            models[i] = AirflowRegionModel.build(AirflowReplayTest.spec(build, palette, variant));
            AirflowLeverReplayTest.apply(levers[i], models[i].region().mesh());
        }
        Cell[] now = new Cell[n];
        for (int c = 0; c < n; c++) {
            now[c] = palette[build.cells()[c] & 0xFF];
        }
        List<int[]> sources = new ArrayList<>(), edits = new ArrayList<>();
        for (int c : build.sourceCells()) {
            sources.add(new int[] {c % nx, c / (nx * nz), c / nx % nz});
        }
        int[] sourceDist = distances(sources, nx, ny, nz), editDist = distances(edits, nx, ny, nz);
        List<String> rows = new ArrayList<>();
        String worstTable = "";
        double worstP99 = -1;
        int next = 1;
        double binEnd = AirflowReplayTest.BIN;
        while (models[0].time() < life) {
            List<Object> due = new ArrayList<>();
            boolean moved = false;
            while (next < events.size() && (events.get(next).tick() - build.tick()) / 20.0 <= models[0].time()) {
                Entry x = events.get(next++);
                Object event = AirflowReplayTest.event(x, nx, nz, now, build, variant);
                if (event != null) {
                    due.add(event);
                }
                if (x instanceof AirflowBackgroundLog.Source s && s.watts() != 0) {
                    sources.add(new int[] {s.x(), s.y(), s.z()});
                    moved = true;
                }
                if (x instanceof AirflowBackgroundLog.Edit e) {
                    edits.add(new int[] {e.x(), e.y(), e.z()});
                    moved = true;
                }
            }
            if (moved) {
                sourceDist = distances(sources, nx, ny, nz);
                editDist = distances(edits, nx, ny, nz);
            }
            for (AirflowRegionModel m : models) {
                if (!due.isEmpty()) {
                    m.apply(due);
                }
                m.step();
            }
            if (models[0].time() < binEnd - 1e-9) {
                continue;
            }
            double second = startSecond + binEnd;
            binEnd += AirflowReplayTest.BIN;
            CellGrid3D grid = models[1].region().grid();
            byte[] classes = AirflowReplayTest.classes(grid, models[1].region().background());
            float[] ref = models[1].region().thermalState().airArray();
            float[] def = models[0].region().thermalState().airArray();
            float[] lev = models[2].region().thermalState().airArray();
            StringBuilder row = new StringBuilder(String.format(Locale.ROOT, "| %.0f |", second));
            for (float[] run : new float[][] {def, lev}) {
                Parts p = parts(ref, run, classes, sourceDist, editDist);
                row.append(String.format(Locale.ROOT, " %.3f / %.3f | %s | %s | %s |", p.mean, p.p99, p.byClass,
                        p.byBand, p.byEdit));
            }
            rows.add(row.toString());
            double p99 = parts(ref, lev, classes, sourceDist, editDist).p99;
            if (p99 > worstP99) {
                worstP99 = p99;
                worstTable = worst(second, ref, def, lev, classes, sourceDist, editDist, grid,
                        models[2].region().mesh(), models[1].region().mesh(), nx, nz);
            }
        }
        List<String> out = new ArrayList<>();
        out.add(String.format(Locale.ROOT, "# Where %s's error lives: region %d, %s (GreedyFluids M8c c2, report-only)",
                lever, id, variant));
        out.add("");
        out.add("|T − REF| over fluid cells, °C. Each part: mean (share of the total error, %). Classes O/N/P/S: OPEN, "
                + "NEAR_GROUND, PARTIAL, SHELTERED. Source distance bands (cells to the nearest heat source): ≤3, 4–8, "
                + "9–16, >16. Edits: within 3 cells of an edit so far / not.");
        out.add("");
        out.add("| Second | DEFAULT mean / p99 | by class O/N/P/S | by source distance | near edit / not | " + lever
                + " mean / p99 | by class O/N/P/S | by source distance | near edit / not |");
        out.add("|---|---|---|---|---|---|---|---|---|");
        out.addAll(rows);
        out.add("");
        out.add(worstTable);
        Files.createDirectories(AirflowReplayTest.OUT);
        Files.write(AirflowReplayTest.OUT.resolve("errormap-" + id + "-" + lever + ".md"), out);
        System.out.printf(Locale.ROOT, "error map for region %d, %s: %d bins%n", id, lever, rows.size());
    }

    record Parts(double mean, double p99, String byClass, String byBand, String byEdit) {
    }

    static Parts parts(float[] ref, float[] run, byte[] classes, int[] sourceDist, int[] editDist) {
        double[] cls = new double[4], band = new double[4], edit = new double[2];
        int[] clsN = new int[4], bandN = new int[4], editN = new int[2];
        double[] all = new double[classes.length];
        int n = 0;
        double total = 0;
        for (int c = 0; c < classes.length; c++) {
            if (classes[c] < 0) {
                continue;
            }
            double e = Math.abs(run[c] - ref[c]);
            all[n++] = e;
            total += e;
            cls[classes[c]] += e;
            clsN[classes[c]]++;
            int b = band(sourceDist[c]);
            band[b] += e;
            bandN[b]++;
            int k = editDist[c] <= 3 ? 0 : 1;
            edit[k] += e;
            editN[k]++;
        }
        double[] sorted = Arrays.copyOf(all, n);
        Arrays.sort(sorted);
        double p99 = n == 0 ? 0 : sorted[Math.min(n - 1, (int) Math.ceil(0.99 * n) - 1)];
        return new Parts(n == 0 ? 0 : total / n, p99, split(cls, clsN, total), split(band, bandN, total),
                split(edit, editN, total));
    }

    static int band(int d) {
        return d <= 3 ? 0 : d <= 8 ? 1 : d <= 16 ? 2 : 3;
    }

    private static String split(double[] sum, int[] count, double total) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < sum.length; i++) {
            b.append(i == 0 ? "" : " / ").append(count[i] == 0 ? "—" : String.format(Locale.ROOT, "%.3f (%.0f)",
                    sum[i] / count[i], total == 0 ? 0 : 100 * sum[i] / total));
        }
        return b.toString();
    }

    private static String worst(double second, float[] ref, float[] def, float[] lev, byte[] classes, int[] sourceDist,
            int[] editDist, CellGrid3D grid, AdaptiveMesh3D levMesh, AdaptiveMesh3D refMesh, int nx, int nz) {
        Integer[] cells = new Integer[classes.length];
        int m = 0;
        for (int c = 0; c < classes.length; c++) {
            if (classes[c] >= 0) {
                cells[m++] = c;
            }
        }
        Integer[] fluid = Arrays.copyOf(cells, m);
        Arrays.sort(fluid, (a, b) -> Double.compare(Math.abs(lev[b] - ref[b]), Math.abs(lev[a] - ref[a])));
        List<String> out = new ArrayList<>();
        out.add(String.format(Locale.ROOT, "## The lever's 20 worst cells at %.0f s (its worst 99th percentile)", second));
        out.add("");
        out.add("| x, y, z (region) | Class | Source dist. | Edit dist. | REF °C | DEFAULT °C | Lever °C | Lever: velocity "
                + "counts | Box volume, lever / REF |");
        out.add("|---|---|---|---|---|---|---|---|---|");
        for (int i = 0; i < Math.min(20, fluid.length); i++) {
            int c = fluid[i];
            int bl = levMesh.graph().boxOfCellIndex(c), br = refMesh.graph().boxOfCellIndex(c);
            out.add(String.format(Locale.ROOT, "| %d, %d, %d | %s | %s | %s | %.2f | %.2f | %.2f | %s | %.0f / %.0f |",
                    c % nx, c / (nx * nz), c / nx % nz, CLASS[classes[c]], dist(sourceDist[c]), dist(editDist[c]),
                    ref[c], def[c], lev[c], levMesh.velocityCounts(c) ? "yes" : "no",
                    bl < 0 ? 0.0 : (double) levMesh.graph().boxVolume(bl), br < 0 ? 0.0 : (double) refMesh.graph().boxVolume(br)));
        }
        return String.join("\n", out);
    }

    private static String dist(int d) {
        return d >= Integer.MAX_VALUE / 2 ? "—" : Integer.toString(d);
    }

    /** Chebyshev distance, cells, from each cell to the nearest point (MAX/2 with none). */
    static int[] distances(List<int[]> points, int nx, int ny, int nz) {
        int[] d = new int[nx * ny * nz];
        Arrays.fill(d, Integer.MAX_VALUE / 2);
        for (int[] p : points) {
            for (int y = 0; y < ny; y++) {
                int dy = Math.abs(y - p[1]);
                for (int z = 0; z < nz; z++) {
                    int dyz = Math.max(dy, Math.abs(z - p[2]));
                    int base = (y * nz + z) * nx;
                    for (int x = 0; x < nx; x++) {
                        int v = Math.max(dyz, Math.abs(x - p[0]));
                        if (v < d[base + x]) {
                            d[base + x] = v;
                        }
                    }
                }
            }
        }
        return d;
    }
}
