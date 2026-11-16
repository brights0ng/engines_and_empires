// Written by Claude (GreedyFluids M7f: the acceptance scenarios); edited by Claude (M7f f2: saving and rebuilding a
// running scene, for the model soak; the outside air sent ahead of an edit, as the manager does).
package dev.brights0ng.meteodyne.airflow;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

import dev.brights0ng.greedyfluids.region.AirFrame;
import dev.brights0ng.meteodyne.Meteodyne;
import dev.brights0ng.meteodyne.airflow.AirflowCells.Cell;
import dev.brights0ng.meteodyne.airflow.AirflowCells.Kind;
import dev.brights0ng.meteodyne.rain.WeatherConfig;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/**
 * A tile built by hand for the acceptance scenarios (M7f; Bright, 2026-11-15: direct stepping, measured first). Blocks
 * are set by state into a window of whole sections, read and powered by the game's own rules ({@link AirflowCells},
 * {@link AirflowSources} and the heat tables), and the outside air is what the test chooses. The model is the one the
 * air thread runs ({@link AirflowRegionModel}), stepped here on the calling thread, faster than real time.
 * Region cells, x fastest, {@code (y * nz + z) * nx + x}.
 */
final class AirflowScene {

    /** The outside air at a lattice point, region cells, at simulated time {@code t}, C. */
    @FunctionalInterface
    interface Outside {
        double at(int x, int y, int z, double t);
    }

    final int nx, ny, nz;
    private final BlockState[] states;

    AirflowScene(int nx, int ny, int nz) {
        if (nx % 16 != 0 || ny % 16 != 0 || nz % 16 != 0) {
            throw new IllegalArgumentException("whole sections only: " + nx + "×" + ny + "×" + nz);
        }
        this.nx = nx;
        this.ny = ny;
        this.nz = nz;
        states = new BlockState[nx * ny * nz];
        Arrays.fill(states, Blocks.AIR.defaultBlockState());
    }

    int index(int x, int y, int z) {
        return (y * nz + z) * nx + x;
    }

    BlockState get(int x, int y, int z) {
        return states[index(x, y, z)];
    }

    AirflowScene set(int x, int y, int z, BlockState state) {
        states[index(x, y, z)] = state;
        return this;
    }

    /** Fills the box from (x0, y0, z0) to (x1, y1, z1), inclusive. */
    AirflowScene fill(int x0, int y0, int z0, int x1, int y1, int z1, BlockState state) {
        for (int y = y0; y <= y1; y++) {
            for (int z = z0; z <= z1; z++) {
                for (int x = x0; x <= x1; x++) {
                    set(x, y, z, state);
                }
            }
        }
        return this;
    }

    /** A hollow box: its faces {@code state}, its inside air. */
    AirflowScene shell(int x0, int y0, int z0, int x1, int y1, int z1, BlockState state) {
        fill(x0, y0, z0, x1, y1, z1, state);
        return fill(x0 + 1, y0 + 1, z0 + 1, x1 - 1, y1 - 1, z1 - 1, Blocks.AIR.defaultBlockState());
    }

    /** What the game makes of the block at (x, y, z). */
    Cell cell(int x, int y, int z) {
        return AirflowCells.classify(get(x, y, z));
    }

    /** Each column's first open height above its highest blocking cell, as the heightmap gives it (0 if none). */
    int[] tops() {
        int[] out = new int[nx * nz];
        for (int z = 0; z < nz; z++) {
            for (int x = 0; x < nx; x++) {
                int top = 0;
                for (int y = ny - 1; y >= 0; y--) {
                    if (cell(x, y, z).kind() != Kind.AIR) {
                        top = y + 1;
                        break;
                    }
                }
                out[z * nx + x] = top;
            }
        }
        return out;
    }

    /** The background lattice ({@link AirflowField}) at time {@code t}. */
    float[] lattice(Outside outside, double t) {
        int lx = AirflowField.points(nx), ly = AirflowField.points(ny), lz = AirflowField.points(nz);
        float[] out = new float[lx * ly * lz];
        for (int j = 0; j < ly; j++) {
            for (int k = 0; k < lz; k++) {
                for (int i = 0; i < lx; i++) {
                    out[(j * lz + k) * lx + i] = (float) outside.at(AirflowField.STEP * i, AirflowField.STEP * j,
                            AirflowField.STEP * k, t);
                }
            }
        }
        return out;
    }

    /** The region as the air thread would build it, with the outside air at time 0, the wind given and the config's κ. */
    Run build(Outside outside, double windX, double windZ) {
        return build(outside, windX, windZ, WeatherConfig.airflowHeatDiffusivity());
    }

    /** As above with the air's heat diffusivity {@code kappa}, m²/s. */
    Run build(Outside outside, double windX, double windZ, double kappa) {
        return new Run(this, AirflowRegionModel.build(spec(outside, 0, windX, windZ, kappa, null, 0)), outside, tops(),
                windX, windZ, kappa);
    }

    /**
     * The spec the region manager would send for this scene: codes, table, sources, the outside air at time {@code t},
     * and the saved sections to start from (null: none), decayed to game tick {@code now}.
     */
    private AirflowRegionModel.Spec spec(Outside outside, double t, double windX, double windZ, double kappa,
                                         AirflowSaved.Section[] saved, long now) {
        int nsx = nx / 16, nsy = ny / 16, nsz = nz / 16;
        AirflowSections.Codes[] codes = new AirflowSections.Codes[nsx * nsy * nsz];
        for (int j = 0; j < nsy; j++) {
            for (int k = 0; k < nsz; k++) {
                for (int i = 0; i < nsx; i++) {
                    codes[(j * nsz + k) * nsx + i] = section(i, j, k);
                }
            }
        }
        AirflowCells.Table table = AirflowCells.table(); // after the codes: coding may add to it
        List<Integer> cells = new ArrayList<>();
        List<Double> watts = new ArrayList<>(), limits = new ArrayList<>();
        for (int c = 0; c < states.length; c++) {
            AirflowSources.Power p = AirflowSources.power(states[c]);
            if (!p.isNone()) {
                cells.add(c);
                watts.add(p.watts());
                limits.add(p.limit());
            }
        }
        float[] lattice = lattice(outside, t);
        int[] tops = tops();
        return new AirflowRegionModel.Spec(0, 0, 0, nx, ny, nz, codes, table, lattice[0],
                windX, windZ, cells.stream().mapToInt(Integer::intValue).toArray(),
                watts.stream().mapToDouble(Double::doubleValue).toArray(),
                limits.stream().mapToDouble(Double::doubleValue).toArray(), saved, now, lattice, tops, kappa);
    }

    private AirflowSections.Codes section(int si, int sj, int sk) {
        byte[] codes = new byte[AirflowSections.SECTION_CELLS];
        boolean uniform = true;
        for (int y = 0; y < 16; y++) {
            for (int z = 0; z < 16; z++) {
                for (int x = 0; x < 16; x++) {
                    int local = (y * 16 + z) * 16 + x;
                    codes[local] = AirflowCells.code(get(16 * si + x, 16 * sj + y, 16 * sk + z));
                    uniform &= codes[local] == codes[0];
                }
            }
        }
        return new AirflowSections.Codes(SectionPos.asLong(si, sj, sk), AirflowCells.generation(), codes[0],
                uniform ? null : codes);
    }

    /** A built scene being stepped, with what the test does to it. */
    static final class Run {
        final AirflowScene scene;
        AirflowRegionModel model;
        private final Outside outside;
        private final int[] tops;
        private final double kappa;
        private double windX, windZ, weatherEvery = Double.POSITIVE_INFINITY, lastWeather;
        /** When the outside air last went ahead of an edit, s (once per instant). */
        private double aheadAt = Double.NaN;
        /** Simulated time before the current model was built (restarts), s. */
        private double before;
        private long stepNanos;
        private int steps;
        /** The steps' energy budget so far, J: sources, loss to ambient, advection out, change in stored heat. */
        private double sourceJ, lossJ, advectedJ, deltaJ;
        /** Background-shift energy of the models before the current one, J. */
        private double shiftBefore;

        private Run(AirflowScene scene, AirflowRegionModel model, Outside outside, int[] tops, double windX,
                    double windZ, double kappa) {
            this.scene = scene;
            this.model = model;
            this.outside = outside;
            this.tops = tops;
            this.windX = windX;
            this.windZ = windZ;
            this.kappa = kappa;
        }

        /** Re-sends the outside air every {@code seconds} (an outside air that changes with time). */
        Run weatherEvery(double seconds) {
            weatherEvery = seconds;
            return this;
        }

        /** Sets the wind now. */
        Run wind(double x, double z) {
            windX = x;
            windZ = z;
            sendWeather();
            return this;
        }

        /** Steps until simulated time {@code seconds}. */
        Run until(double seconds) {
            while (time() < seconds - 1e-9) {
                if (time() - lastWeather >= weatherEvery - 1e-9) {
                    sendWeather();
                }
                long t0 = System.nanoTime();
                model.step();
                stepNanos += System.nanoTime() - t0;
                steps++;
                var st = model.region().stats();
                sourceJ += st.sourceEnergy;
                lossJ += st.ambientLoss;
                advectedJ += st.advectedOut;
                deltaJ += st.deltaEnergy;
                if (!model.healthy()) {
                    throw new IllegalStateException("non-finite values at " + time() + " s");
                }
            }
            return this;
        }

        private void sendWeather() {
            lastWeather = time();
            postWeather();
        }

        private void postWeather() {
            float[] lattice = scene.lattice(outside, time());
            model.apply(List.of(new AirflowRegionModel.Weather(lattice[0], windX, windZ, lattice, tops)));
        }

        /** Sends the outside air as it is now (the manager's update when the background moves). */
        Run weatherNow() {
            sendWeather();
            return this;
        }

        /**
         * Saves the region as the manager does when a tile retires, at game tick {@code tick}, and builds a new one from
         * the save and the scene's blocks now, as a tile reopening at once does (M7f f2, the soak). The outside air and
         * the wind carry over; simulated time runs on.
         */
        Run restart(long tick) {
            sendWeather(); // the save's background is then the rebuild's, as at a reopen within the same weather
            AirflowSaved.Tile tile = model.save(tick);
            double now = time();
            shiftBefore += model.region().mesh().backgroundShiftEnergy();
            model = AirflowRegionModel.build(scene.spec(outside, now, windX, windZ, kappa, tile.sections(), tick));
            before = now;
            lastWeather = now;
            return this;
        }

        /** The block at (x, y, z) becomes {@code state}, a source block whose power changes (a fire lit or put out). */
        Run light(int x, int y, int z, BlockState state) {
            scene.set(x, y, z, state);
            return power(x, y, z, state);
        }

        /** Applies events now (source changes, cell changes, bodies). */
        Run post(Object... events) {
            model.apply(List.of(events));
            return this;
        }

        /** The block at (x, y, z) changes to {@code after}: the cell change the region manager would send. */
        Run change(int x, int y, int z, BlockState after) {
            Cell before = scene.cell(x, y, z);
            scene.set(x, y, z, after);
            ahead();
            return post(new AirflowBatch.Change(x, y, z, before, scene.cell(x, y, z)));
        }

        /**
         * Sends the outside air up to now, as the manager does ahead of an edit (M7f), off the schedule: once per
         * instant, and nothing with an outside air that never changes. {@link #change} calls it.
         */
        Run ahead() {
            if (weatherEvery < Double.POSITIVE_INFINITY && time() != aheadAt && time() != lastWeather) {
                aheadAt = time();
                postWeather();
            }
            return this;
        }

        /** The source at (x, y, z) set to {@code state}'s power (the block itself stays). */
        Run power(int x, int y, int z, BlockState state) {
            AirflowSources.Power p = AirflowSources.power(state);
            return post(new AirflowRegionModel.SourceChange(x, y, z, p.watts(), p.limit()));
        }

        double time() {
            return before + model.time();
        }

        AirFrame frame() {
            return model.publish();
        }

        /** The air's offset from its background at a cell's centre, C (NaN in a solid). */
        double offset(int x, int y, int z) {
            AirFrame f = frame();
            return offset(f, x, y, z);
        }

        static double offset(AirFrame f, int x, int y, int z) {
            if (!f.isAir(x + 0.5, y + 0.5, z + 0.5)) {
                return Double.NaN;
            }
            return f.temperature(x + 0.5, y + 0.5, z + 0.5) - f.background().at(x, y, z);
        }

        /** The mean offset of the air cells in a box, inclusive, C. */
        double meanOffset(int x0, int y0, int z0, int x1, int y1, int z1) {
            return mean(x0, y0, z0, x1, y1, z1, true);
        }

        /** The mean air temperature in a box, inclusive, C. */
        double meanTemperature(int x0, int y0, int z0, int x1, int y1, int z1) {
            return mean(x0, y0, z0, x1, y1, z1, false);
        }

        private double mean(int x0, int y0, int z0, int x1, int y1, int z1, boolean offsets) {
            AirFrame f = frame();
            double sum = 0;
            int n = 0;
            for (int y = y0; y <= y1; y++) {
                for (int z = z0; z <= z1; z++) {
                    for (int x = x0; x <= x1; x++) {
                        if (f.isAir(x + 0.5, y + 0.5, z + 0.5)) {
                            double t = f.temperature(x + 0.5, y + 0.5, z + 0.5);
                            sum += offsets ? t - f.background().at(x, y, z) : t;
                            n++;
                        }
                    }
                }
            }
            return n == 0 ? Double.NaN : sum / n;
        }

        /** The mean velocity along {@code axis} (0 x, 1 up, 2 z) of the air cells in a box, inclusive, m/s. */
        double meanFlow(int axis, int x0, int y0, int z0, int x1, int y1, int z1) {
            AirFrame f = frame();
            double[] v = new double[3];
            double sum = 0;
            int n = 0;
            for (int y = y0; y <= y1; y++) {
                for (int z = z0; z <= z1; z++) {
                    for (int x = x0; x <= x1; x++) {
                        if (f.velocity(x + 0.5, y + 0.5, z + 0.5, v)) {
                            sum += v[axis];
                            n++;
                        }
                    }
                }
            }
            return n == 0 ? Double.NaN : sum / n;
        }

        /** The mean air speed of the air cells in a box, inclusive, m/s. */
        double meanSpeed(int x0, int y0, int z0, int x1, int y1, int z1) {
            AirFrame f = frame();
            double[] v = new double[3];
            double sum = 0;
            int n = 0;
            for (int y = y0; y <= y1; y++) {
                for (int z = z0; z <= z1; z++) {
                    for (int x = x0; x <= x1; x++) {
                        if (f.velocity(x + 0.5, y + 0.5, z + 0.5, v)) {
                            sum += Math.sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2]);
                            n++;
                        }
                    }
                }
            }
            return n == 0 ? Double.NaN : sum / n;
        }

        int boxes() {
            return model.boxes();
        }

        /** The boxes holding a box's air cells, inclusive, and the tallest and widest of them, cells. */
        int[] boxesIn(int x0, int y0, int z0, int x1, int y1, int z1) {
            var g = model.region().mesh().graph();
            java.util.Set<Integer> seen = new java.util.HashSet<>();
            int tallest = 0, widest = 0;
            for (int y = y0; y <= y1; y++) {
                for (int z = z0; z <= z1; z++) {
                    for (int x = x0; x <= x1; x++) {
                        int b = g.boxOfCell(x, y, z);
                        if (b >= 0 && seen.add(b)) {
                            tallest = Math.max(tallest, g.boxSy(b));
                            widest = Math.max(widest, Math.max(g.boxSx(b), g.boxSz(b)));
                        }
                    }
                }
            }
            return new int[] {seen.size(), tallest, widest};
        }

        /** Mean step time so far, ms. */
        double stepMs() {
            return steps == 0 ? 0 : stepNanos / 1e6 / steps;
        }

        /** Wall time of every step so far, ns. */
        long stepNanos() {
            return stepNanos;
        }

        /**
         * The energy budget so far, J: {sources, loss to ambient, advected out, Σ the steps' change in stored heat,
         * background shifts} (M7f f2, the model soak's daily budget).
         */
        double[] budget() {
            return new double[] {sourceJ, lossJ, advectedJ, deltaJ,
                    shiftBefore + model.region().mesh().backgroundShiftEnergy()};
        }

        int steps() {
            return steps;
        }
    }

    /** The scenario's numbers, logged as {@code Airflow scenario <name>: ...} for the report. */
    static final class Report {
        private final String name;
        private final StringBuilder line = new StringBuilder();

        Report(String name) {
            this.name = name;
        }

        Report add(String key, double value) {
            line.append(line.isEmpty() ? "" : ", ").append(key).append(' ')
                    .append(String.format(Locale.ROOT, "%.3f", value));
            return this;
        }

        /** Logs the numbers gathered so far with the run's cost, and starts a new line. */
        void log(Run run) {
            Meteodyne.LOGGER.info("Airflow scenario {}: {}; t {} s, {} boxes, {} ms/step over {} steps", name, line,
                    String.format(Locale.ROOT, "%.0f", run.time()), run.boxes(),
                    String.format(Locale.ROOT, "%.2f", run.stepMs()), run.steps());
            line.setLength(0);
        }
    }
}
