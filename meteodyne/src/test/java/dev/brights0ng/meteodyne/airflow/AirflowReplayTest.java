// Written by Claude (GreedyFluids M8c c1).
package dev.brights0ng.meteodyne.airflow;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import dev.brights0ng.greedyfluids.grid.CellGrid3D;
import dev.brights0ng.greedyfluids.grid.CellType;
import dev.brights0ng.greedyfluids.grid.Material;
import dev.brights0ng.greedyfluids.mesh.BoxGraph3D;
import dev.brights0ng.greedyfluids.refine.AdaptiveMesh3D;
import dev.brights0ng.greedyfluids.solve.Background3D;
import dev.brights0ng.meteodyne.airflow.AirflowBackgroundLog.CellInfo;
import dev.brights0ng.meteodyne.airflow.AirflowBackgroundLog.Entry;
import dev.brights0ng.meteodyne.airflow.AirflowCells.Cell;
import dev.brights0ng.meteodyne.airflow.AirflowCells.Kind;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

/**
 * GreedyFluids M8c c1 (report-only; Bright's desktop): replays a recording's regions from its background dump
 * ({@link AirflowBackgroundLog}) through the real {@link AirflowRegionModel}, built from the same spec and fed the same
 * weather, sources, body heat and edits at the same game times, and says where the boxes are and why: which indicator
 * split each cell, and where the air sits relative to the weather.
 * <p>Classes of fluid cells, from the follow weights and the grid:
 * <ul>
 *   <li>OPEN: follows fully (sky 15) and is more than {@link #NEAR} cells above the solid below it;</li>
 *   <li>NEAR_GROUND: follows fully, within {@link #NEAR} cells above a solid (where the 4-block background lattice
 *       blends the ground's value into the air);</li>
 *   <li>PARTIAL: follows partly (sky 1–14);</li>
 *   <li>SHELTERED: follows nothing (sky 0: caves, rooms).</li>
 * </ul>
 * Per 10 s of game time: air-thread wall time per game second (core %), boxes, the hold's share of steps, hold ends by
 * the temperature trigger and the volume of the boxes that moved by class, boxes split by cause (T temperature jump, U
 * velocity jump, S unstable layering), the boxes each class holds (Σ 1/box volume over its cells), and the cells a
 * split holds fine by class and cause. Beside them, the recording's own busy % and boxes for the same seconds (its
 * {@code regions.csv}).
 * <p>Not replayed: saved temperatures (a restored region starts from its background) and the neighbours' borders
 * (insulated). Runs only with {@code -Pmeteodyne.airflowReplay=<background.gfbg>}; optional
 * {@code -Pmeteodyne.airflowReplayRegions=<ids, comma-separated>} (default: every region alive 30 s or more) and
 * {@code -Pmeteodyne.airflowReplayVariants=<names, comma-separated>} (default RECORDED):
 * <ul>
 *   <li>RECORDED: the inputs as sent;</li>
 *   <li>STILL: no wind at all;</li>
 *   <li>BUILD_WIND: the wind held at its value at the build;</li>
 *   <li>FROZEN: the background (lattice and value) held as at the build; wind as sent;</li>
 *   <li>WIND: the wind held at {@code -Pmeteodyne.airflowReplayWindSpeed} m/s (default 8) along the build's
 *       direction.</li>
 * </ul>
 * Writes {@code build/reports/airflow-replay/replay-<region>[-<variant>].md} and {@code .csv}.
 */
@EnabledIfSystemProperty(named = "meteodyne.airflowReplay", matches = ".+")
class AirflowReplayTest {

    static final int NEAR = 4;
    static final double BIN = 10, MIN_LIFE = 30;
    static final Path OUT = Path.of("build/reports/airflow-replay");

    /** What the replay changes in the inputs (report-only). */
    enum Variant { RECORDED, STILL, BUILD_WIND, FROZEN, WIND }

    /** The WIND variant's speed, m/s. */
    static final double WIND_SPEED = Double.parseDouble(System.getProperty("meteodyne.airflowReplayWindSpeed", "8"));

    @Test
    void replay() throws IOException {
        Path file = Path.of(System.getProperty("meteodyne.airflowReplay"));
        AirflowBackgroundLog.Dump dump = AirflowBackgroundLog.read(file);
        Map<Integer, List<Entry>> byRegion = new LinkedHashMap<>();
        for (Entry e : dump.entries()) {
            byRegion.computeIfAbsent(e.id(), k -> new ArrayList<>()).add(e);
        }
        Set<Integer> wanted = new TreeSet<>();
        for (String s : System.getProperty("meteodyne.airflowReplayRegions", "").split(",")) {
            if (!s.isBlank()) {
                wanted.add(Integer.parseInt(s.trim()));
            }
        }
        List<Variant> variants = new ArrayList<>();
        for (String s : System.getProperty("meteodyne.airflowReplayVariants", "RECORDED").split(",")) {
            if (!s.isBlank()) {
                variants.add(Variant.valueOf(s.trim()));
            }
        }
        Map<String, double[]> recorded = recording(file.resolveSibling("regions.csv"));
        Files.createDirectories(OUT);
        int replayed = 0;
        for (var e : byRegion.entrySet()) {
            List<Entry> events = e.getValue();
            if (!(events.get(0) instanceof AirflowBackgroundLog.Region build)) {
                continue;
            }
            long end = events.get(events.size() - 1).tick();
            for (Entry x : events) {
                if (x instanceof AirflowBackgroundLog.Retire) {
                    end = x.tick();
                }
            }
            double life = (end - build.tick()) / 20.0;
            if (wanted.isEmpty() ? life < MIN_LIFE : !wanted.contains(e.getKey())) {
                continue;
            }
            for (Variant v : variants) {
                replay(build, events, life, dump.startTick(), recorded, v);
                replayed++;
            }
        }
        assertTrue(replayed > 0, "no region to replay (ids " + byRegion.keySet() + ")");
    }

    private static void replay(AirflowBackgroundLog.Region build, List<Entry> events, double life, long start,
            Map<String, double[]> recorded, Variant variant) throws IOException {
        Cell[] palette = new Cell[build.palette().length];
        for (int i = 0; i < palette.length; i++) {
            palette[i] = cell(build.palette()[i], "code" + i);
        }
        int nx = build.nx(), ny = build.ny(), nz = build.nz();
        AirflowRegionModel model = AirflowRegionModel.build(spec(build, palette, variant));
        Cell[] now = new Cell[nx * ny * nz];
        for (int c = 0; c < now.length; c++) {
            now[c] = palette[build.cells()[c] & 0xFF];
        }
        AdaptiveMesh3D mesh = model.region().mesh();
        CellGrid3D grid = model.region().grid();
        double dt = model.dt();
        int next = 1, edits = 0;
        byte[] classes = classes(grid, model.region().background());
        List<String> rows = new ArrayList<>(), csv = new ArrayList<>();
        csv.add("second,core_pct,boxes,held_pct,temp_ends,end_vol_open,end_vol_near,end_vol_partial,end_vol_sheltered,"
                + "split_T,split_U,split_S,boxes_open,boxes_near,boxes_partial,boxes_sheltered,fine_open,fine_near,"
                + "fine_partial,fine_sheltered,fine_T,fine_U,fine_S,rec_busy_pct,rec_boxes");
        long wall = 0;
        int steps = 0;
        long boxSum = 0;
        long held0 = mesh.heldSteps(), ends0 = mesh.holdCounters()[2];
        long[] split0 = mesh.splitEventsByCause();
        double[] endVolume = new double[4];
        double binEnd = BIN;
        while (model.time() < life) {
            List<Object> due = new ArrayList<>();
            while (next < events.size() && (events.get(next).tick() - build.tick()) / 20.0 <= model.time()) {
                Entry x = events.get(next++);
                Object event = event(x, nx, nz, now, build, variant);
                if (event != null) {
                    due.add(event);
                }
                if (x instanceof AirflowBackgroundLog.Edit) {
                    edits++;
                }
            }
            if (!due.isEmpty()) {
                model.apply(due);
                if (due.stream().anyMatch(o -> o instanceof AirflowBatch.Change)) {
                    classes = classes(grid, model.region().background());
                }
            }
            long ends = mesh.holdCounters()[2];
            long t0 = System.nanoTime();
            model.step();
            wall += System.nanoTime() - t0;
            steps++;
            boxSum += model.boxes();
            if (mesh.holdCounters()[2] > ends) {
                endVolume(mesh, grid, classes, endVolume);
            }
            if (model.time() >= binEnd - 1e-9) {
                classes = classes(grid, model.region().background());
                double second = (build.tick() - start) / 20.0 + binEnd;
                long[] split = mesh.splitEventsByCause();
                Census c = census(mesh, classes);
                double[] rec = variant == Variant.RECORDED ? recorded.get(build.id() + ":" + (int) second) : null;
                double core = 100 * wall * 1e-9 / BIN, held = 100.0 * (mesh.heldSteps() - held0) / steps;
                csv.add(String.format(Locale.ROOT, "%.0f,%.2f,%d,%.1f,%d,%.0f,%.0f,%.0f,%.0f,%d,%d,%d,%.0f,%.0f,%.0f,%.0f,"
                                + "%d,%d,%d,%d,%d,%d,%d,%s,%s", second, core, boxSum / steps, held,
                        mesh.holdCounters()[2] - ends0, endVolume[0], endVolume[1], endVolume[2], endVolume[3],
                        split[0] - split0[0], split[1] - split0[1], split[2] - split0[2], c.boxes[0], c.boxes[1],
                        c.boxes[2], c.boxes[3], c.fine[0], c.fine[1], c.fine[2], c.fine[3], c.cause[0], c.cause[1],
                        c.cause[2], rec == null ? "" : String.format(Locale.ROOT, "%.2f", rec[0]),
                        rec == null ? "" : String.format(Locale.ROOT, "%.0f", rec[1])));
                rows.add(String.format(Locale.ROOT, "| %.0f | %.2f | %s | %d | %s | %d | %s | %d / %d / %d | %s | %s | %s |",
                        second, core, rec == null ? "—" : String.format(Locale.ROOT, "%.2f", rec[0]), boxSum / steps,
                        rec == null ? "—" : String.format(Locale.ROOT, "%.0f", rec[1]), (int) held,
                        endShare(endVolume, mesh.holdCounters()[2] - ends0), split[0] - split0[0],
                        split[1] - split0[1], split[2] - split0[2], shares(c.boxes), shares(c.fine),
                        causeShares(c.cause)));
                wall = 0;
                steps = 0;
                boxSum = 0;
                held0 = mesh.heldSteps();
                ends0 = mesh.holdCounters()[2];
                split0 = split;
                endVolume = new double[4];
                binEnd += BIN;
            }
        }
        int[] census = new int[4];
        for (byte b : classes) {
            if (b >= 0) {
                census[b]++;
            }
        }
        List<String> out = new ArrayList<>();
        out.add(String.format(Locale.ROOT, "# Airflow replay: region %d, %s (GreedyFluids M8c c1, report-only)",
                build.id(), variant));
        out.add("");
        out.add(String.format(Locale.ROOT, "Origin %d, %d, %d; %d×%d×%d cells; %.0f s from recording second %.0f; built "
                        + "%s; %d sources at the build; %d edits replayed. dt %.2f s, κ %.3f. Fluid cells at the end: OPEN %d, "
                        + "NEAR_GROUND %d, PARTIAL %d, SHELTERED %d. Saved temperatures and neighbours' borders aren't "
                        + "replayed.", build.x0(), build.y0(), build.z0(), nx, ny, nz, life, (build.tick() - start) / 20.0,
                build.restored() ? "from saves in game (here from its background)" : "fresh",
                build.sourceCells().length, edits, dt, build.kappa(), census[0], census[1], census[2], census[3]));
        out.add("");
        out.add("Per 10 s: core % (air-thread wall time per game second, this replay; then the recording's busy %), mean boxes "
                + "(this replay; the recording's at the bin's end), held % of steps, hold ends by the temperature trigger "
                + "with the volume that moved by class (O/N/P/S), boxes split by cause (T/U/S), the boxes each class holds "
                + "(O/N/P/S, %), the cells a split holds fine by class (O/N/P/S, %) and by cause (T/U/S, % of them).");
        out.add("");
        out.add("| Second | Core % | Rec. busy % | Boxes | Rec. boxes | Held % | Temp. ends: volume O/N/P/S % | Splits T / U / S "
                + "| Boxes O/N/P/S % | Split-fine cells O/N/P/S % | Their causes T/U/S % |");
        out.add("|---|---|---|---|---|---|---|---|---|---|---|");
        out.addAll(rows);
        String name = "replay-" + build.id() + (variant == Variant.RECORDED ? "" : "-" + variant)
                + (variant == Variant.WIND ? String.format(Locale.ROOT, "%.0f", WIND_SPEED) : "");
        Files.write(OUT.resolve(name + ".md"), out);
        Files.write(OUT.resolve(name + ".csv"), csv);
        System.out.printf(Locale.ROOT, "replayed region %d, %s: %.0f s, %d rows%n", build.id(), variant, life,
                rows.size());
        assertFalse(rows.isEmpty());
    }

    /** The spec the region was built from, without its saves (and, for STILL, without wind). */
    static AirflowRegionModel.Spec spec(AirflowBackgroundLog.Region b, Cell[] palette, Variant variant) {
        int nsx = b.nx() / 16, nsy = b.ny() / 16, nsz = b.nz() / 16;
        AirflowSections.Codes[] codes = new AirflowSections.Codes[nsx * nsy * nsz];
        for (int j = 0; j < nsy; j++) {
            for (int k = 0; k < nsz; k++) {
                for (int i = 0; i < nsx; i++) {
                    byte[] cells = new byte[AirflowSections.SECTION_CELLS];
                    for (int y = 0; y < 16; y++) {
                        for (int z = 0; z < 16; z++) {
                            for (int x = 0; x < 16; x++) {
                                int c = ((16 * j + y) * b.nz() + 16 * k + z) * b.nx() + 16 * i + x;
                                cells[AirflowSections.Codes.index(x, y, z)] = b.cells()[c];
                            }
                        }
                    }
                    codes[(j * nsz + k) * nsx + i] = new AirflowSections.Codes((j * nsz + k) * nsx + i, 1,
                            AirflowCells.AIR, cells);
                }
            }
        }
        AirflowCells.Table table = new AirflowCells.Table(1, palette, b.film());
        AirflowBackgroundLog.Weather w = b.weather();
        double[] wind = wind(w.windX(), w.windZ(), b.weather(), variant);
        return new AirflowRegionModel.Spec(b.x0(), b.y0(), b.z0(), b.nx(), b.ny(), b.nz(), codes, table,
                w.backgroundT(), wind[0], wind[1], b.sourceCells(), b.sourceWatts(),
                b.sourceLimits(), null, b.tick(), w.lattice(), w.tops(), b.kappa());
    }

    /** The wind a variant sends, given the one recorded and the build's weather: x, z, m/s. */
    static double[] wind(double wx, double wz, AirflowBackgroundLog.Weather build, Variant v) {
        return switch (v) {
            case STILL -> new double[] {0, 0};
            case BUILD_WIND -> new double[] {build.windX(), build.windZ()};
            case WIND -> {
                double speed = Math.hypot(build.windX(), build.windZ());
                yield speed == 0 ? new double[] {WIND_SPEED, 0}
                        : new double[] {WIND_SPEED * build.windX() / speed, WIND_SPEED * build.windZ() / speed};
            }
            default -> new double[] {wx, wz};
        };
    }

    static Cell cell(CellInfo info, String name) {
        Kind kind = Kind.values()[info.kind()];
        if (kind == Kind.AIR) {
            return new Cell(Kind.AIR, null);
        }
        Material m = new Material(name, info.conductance(), info.capacity());
        return kind == Kind.SOLID ? new Cell(kind, m, info.wall()) : new Cell(kind, m);
    }

    /** The model event for a dump record under the variant (null for none); edits update {@code now}. */
    private static Object event(Entry x, int nx, int nz, Cell[] now, AirflowBackgroundLog.Region build, Variant v) {
        if (x instanceof AirflowBackgroundLog.Weather w) {
            AirflowBackgroundLog.Weather b = build.weather();
            double[] wind = wind(w.windX(), w.windZ(), b, v);
            double wx = wind[0], wz = wind[1];
            boolean frozen = v == Variant.FROZEN;
            // tops as a fresh array, as the manager sends them (the model resets its background when they differ)
            return new AirflowRegionModel.Weather(frozen ? b.backgroundT() : w.backgroundT(), wx, wz,
                    frozen ? b.lattice() : w.lattice(), w.tops() == null ? null : w.tops().clone());
        }
        if (x instanceof AirflowBackgroundLog.Source s) {
            return new AirflowRegionModel.SourceChange(s.x(), s.y(), s.z(), s.watts(), s.limit());
        }
        if (x instanceof AirflowBackgroundLog.Bodies b) {
            return new AirflowRegionModel.Bodies(b.xyzw());
        }
        if (x instanceof AirflowBackgroundLog.Edit e) {
            int c = (e.y() * nz + e.z()) * nx + e.x();
            Cell before = now[c], after = cell(e.cell(), "edit");
            now[c] = after;
            return new AirflowBatch.Change(e.x(), e.y(), e.z(), before, after);
        }
        return null;
    }

    /** Each cell's class: 0 OPEN, 1 NEAR_GROUND, 2 PARTIAL, 3 SHELTERED (−1 for solids). */
    static byte[] classes(CellGrid3D grid, Background3D background) {
        int nx = grid.nx(), ny = grid.ny(), nz = grid.nz();
        byte[] out = new byte[nx * ny * nz];
        for (int z = 0; z < nz; z++) {
            for (int x = 0; x < nx; x++) {
                int above = Integer.MAX_VALUE / 2; // cells since the last solid below, going up
                for (int y = 0; y < ny; y++) {
                    int c = grid.index(x, y, z);
                    if (grid.type(x, y, z) == CellType.SOLID) {
                        out[c] = -1;
                        above = 0;
                        continue;
                    }
                    above++;
                    double f = background.follow(c);
                    out[c] = (byte) (f <= 0 ? 3 : f < 1 - 1e-6 ? 2 : above <= NEAR ? 1 : 0);
                }
            }
        }
        return out;
    }

    private static void endVolume(AdaptiveMesh3D mesh, CellGrid3D grid, byte[] classes, double[] into) {
        BoxGraph3D g = mesh.graph();
        for (int b : mesh.lastHoldEndBoxes()) {
            for (int y = g.boxY0(b); y < g.boxY0(b) + g.boxSy(b); y++) {
                for (int z = g.boxZ0(b); z < g.boxZ0(b) + g.boxSz(b); z++) {
                    for (int x = g.boxX0(b); x < g.boxX0(b) + g.boxSx(b); x++) {
                        byte k = classes[grid.index(x, y, z)];
                        if (k >= 0) {
                            into[k]++;
                        }
                    }
                }
            }
        }
    }

    record Census(double[] boxes, int[] fine, int[] cause) {
    }

    static Census census(AdaptiveMesh3D mesh, byte[] classes) {
        BoxGraph3D g = mesh.graph();
        double[] boxes = new double[4];
        int[] fine = new int[4], cause = new int[3];
        for (int c = 0; c < classes.length; c++) {
            byte k = classes[c];
            int b = g.boxOfCellIndex(c);
            if (k < 0 || b < 0) {
                continue;
            }
            boxes[k] += 1.0 / g.boxVolume(b);
            byte why = mesh.splitCause(c);
            if (why != 0) {
                fine[k]++;
                for (int i = 0; i < 3; i++) {
                    if ((why & (1 << i)) != 0) {
                        cause[i]++;
                    }
                }
            }
        }
        return new Census(boxes, fine, cause);
    }

    private static String endShare(double[] v, long ends) {
        return ends == 0 ? "0" : ends + ": " + shares(v);
    }

    private static String shares(double[] v) {
        double t = v[0] + v[1] + v[2] + v[3];
        return t == 0 ? "—" : String.format(Locale.ROOT, "%.0f/%.0f/%.0f/%.0f", 100 * v[0] / t, 100 * v[1] / t,
                100 * v[2] / t, 100 * v[3] / t);
    }

    private static String shares(int[] v) {
        return shares(new double[] {v[0], v[1], v[2], v[3]});
    }

    private static String causeShares(int[] c) {
        int t = c[0] + c[1] + c[2];
        return t == 0 ? "—" : String.format(Locale.ROOT, "%.0f/%.0f/%.0f", 100.0 * c[0] / t, 100.0 * c[1] / t,
                100.0 * c[2] / t);
    }

    /** The recording's busy % (mean of the ten seconds up to it) and boxes per region and second ("id:second"). */
    static Map<String, double[]> recording(Path csv) throws IOException {
        Map<String, double[]> out = new HashMap<>();
        if (!Files.exists(csv)) {
            return out;
        }
        List<String> lines = Files.readAllLines(csv);
        List<String> head = List.of(lines.get(0).split(","));
        int second = head.indexOf("second"), region = head.indexOf("region"), boxes = head.indexOf("boxes"),
                busy = head.indexOf("busy_pct"), status = head.indexOf("status");
        Map<String, Integer> firstSeen = new HashMap<>();
        for (String line : lines.subList(1, lines.size())) {
            String[] f = line.split(",", -1);
            if (!"RUNNING".equals(f[status]) || f[busy].isEmpty()) {
                continue;
            }
            if (firstSeen.putIfAbsent(f[region], 1) == null) {
                continue; // the first row counts the slot's whole life so far
            }
            out.put(f[region] + ":" + f[second], new double[] {Double.parseDouble(f[busy]), Double.parseDouble(f[boxes])});
        }
        Map<String, double[]> binned = new HashMap<>();
        for (var e : out.entrySet()) {
            String[] k = e.getKey().split(":");
            int s = Integer.parseInt(k[1]);
            double sum = 0;
            int n = 0;
            for (int i = s - 9; i <= s; i++) {
                double[] v = out.get(k[0] + ":" + i);
                if (v != null) {
                    sum += v[0];
                    n++;
                }
            }
            binned.put(e.getKey(), new double[] {sum / n, e.getValue()[1]});
        }
        return binned;
    }
}
