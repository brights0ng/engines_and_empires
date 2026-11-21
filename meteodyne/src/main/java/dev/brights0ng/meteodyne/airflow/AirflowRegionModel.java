// Written by Claude (GreedyFluids M7b b3); edited by Claude (b4: counters; M7d d2: saved state in and out; M7c: the
// background field, fixed walls, thermostats and body heat; M7f: sources on the grid before the region is built, the
// heat diffusivity from the spec; held steps and edit-batch time for the profile; a weather sent ahead of an edit
// batch goes first, and door toggles refresh the follow weights; M8a: the surface film from the cell table; M8b: tile
// borders; M8b5b: the far side from regions of any size across; M8c c3: the source-velocity radius from the spec; M8e:
// the fixed walls' surface and keep-fine radius from the cell table; M8e e3: the dynamic start).
package dev.brights0ng.meteodyne.airflow;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import dev.brights0ng.greedyfluids.grid.CellEdit3D;
import dev.brights0ng.greedyfluids.grid.CellGrid3D;
import dev.brights0ng.greedyfluids.grid.CellGrid3D.FaceBoundary;
import dev.brights0ng.greedyfluids.grid.CellGrid3D.Side;
import dev.brights0ng.greedyfluids.grid.CellType;
import dev.brights0ng.greedyfluids.refine.AdaptiveMesh3D;
import dev.brights0ng.greedyfluids.region.AirFrame;
import dev.brights0ng.greedyfluids.region.AirRegion;
import dev.brights0ng.greedyfluids.region.ThermalState;
import dev.brights0ng.greedyfluids.solve.Background3D;
import dev.brights0ng.greedyfluids.solve.SolverConfig3D;
import dev.brights0ng.greedyfluids.solve.SteadyHeat3D;

/**
 * One GreedyFluids region on the air thread (M7b b3): an {@link AirRegion} built from cached section codes, fed the
 * region manager's events.
 * <ul>
 *   <li><b>Geometry:</b> {@link AirflowBatch.Change}s, merged per step ({@link AirflowBatch}). An edit whose cell isn't
 *       what it expects (AIR for a placement, SOLID for a break, a door for a removal) is skipped and counted as a
 *       desync; the region manager's events and the region's grid should never disagree. Fixed walls (lava, magma;
 *       M7c) come with the cells and follow their changes.</li>
 *   <li><b>Sources:</b> {@link SourceChange}s set a block's power. Bright's power rule (2026-11-04): a source in air or
 *       a door gets all of it; a solid one splits it equally over its AIR face neighbours inside the window (none: no
 *       power). Recomputed after every geometry batch. A source with an absolute limit (M7c) is a thermostat: off
 *       while the air it heats (or cools) is past the limit, on again 1 C back, checked every
 *       {@link #THERMOSTAT_STEPS} steps.</li>
 *   <li><b>Weather (M7c c2):</b> {@link Weather} sets the wind and the background field: per-cell temperatures from
 *       the server's lattice ({@link AirflowField#cells}) and follow weights from the sky light through the air
 *       ({@link AirflowField#sky}). Without a lattice (tests), one temperature and the M7b follow rule (air with only
 *       air above it). Follow weights are recomputed at the next weather after a geometry change or a door toggle.
 *       A weather that comes in the same batch ahead of the first edit is applied before the edits (M7f; Bright,
 *       2026-11-16): the outside air's change up to the edit is shifted with the follow weights it had, not the new
 *       ones (applied late, they pumped about 120 MJ a day into the model soak's house).</li>
 *   <li><b>Body heat (M7c c3):</b> {@link Bodies} replaces the moving heat sources (players).</li>
 *   <li><b>Saved state (M7d d2):</b> built from the tile's saved sections when there are any (decayed to the build
 *       tick, {@link AirflowSaved}); {@link #save} packs the current state the same way.</li>
 *   <li><b>Dynamic start (M8e e3; Bright, decisions 48–49, 53–56):</b> with the cell table's switch on and any source
 *       block or fixed wall, the region is moved toward GreedyFluids' heat-only estimate ({@link SteadyHeat3D}: lava
 *       steady, sources an in-game day on, air open to the sky (above its column's open height, as the borders'
 *       enclosure test) tied to the weather) before its first frame: all the way when built
 *       fresh, by 1 − e^(−Δt/τ) when restored from a save Δt ticks old, so a save left alone decays toward the estimate
 *       rather than the background; skipped when that weight is under {@link #MIN_START_WEIGHT} (merges, splits and
 *       quick reopens, all through a save). Thermostats count at their duty cycle: a heater whose air the estimate
 *       takes past its limit has its power scaled so the air sits at it (up to {@link #DUTY_PASSES} passes).
 *       EXCHANGE faces are insulated in the estimate (no neighbour's frame is read before the first step).</li>
 *   <li><b>Borders (M8b, B2):</b> with column tops, the tile's enclosed cut cells are EXCHANGE ({@link AirflowBorders}),
 *       set before the region is built and changed when a weather brings new tops. Before each step their far side
 *       (temperature and distance) is read from the running neighbours' latest frames ({@link #neighbours}), by
 *       position, so a region can face several (M8b5b, merged regions); insulated where none holds the cell.</li>
 * </ul>
 * Pure Java (GreedyFluids, no Minecraft).
 */
final class AirflowRegionModel implements AirflowRuntime.Model {

    /** Steps between thermostat checks (2 s). */
    static final int THERMOSTAT_STEPS = 4;
    /** A thermostat's way back, C. */
    static final double HYSTERESIS = 1;
    /** The dynamic start's smallest weight worth a solve: a save about 12 s old (M8e e3). */
    static final double MIN_START_WEIGHT = 0.01;
    /** How many times the dynamic start re-solves with thermostats scaled to their duty cycle (M8e e3). */
    static final int DUTY_PASSES = 3;
    /** How far past its limit, C, a thermostat's estimated air must be for its duty cycle to be scaled. */
    static final double DUTY_TOLERANCE = 0.1;
    /**
     * The dynamic start's settings: GreedyFluids' defaults. Report-only tests ({@code AirflowStartCostTest}) set others
     * to compare them; nothing else writes it.
     */
    static SteadyHeat3D.Settings startSettings = SteadyHeat3D.Settings.DEFAULT;
    /**
     * The air's heat diffusivity where a spec doesn't give one, m²/s (M7f; Bright, 2026-11-15: set from Meteodyne,
     * not GreedyFluids' 1 m²/s, which mixes a heated room faster than its plume can layer it).
     */
    static final double DEFAULT_KAPPA = 0.05;

    /**
     * What a region is built from, gathered on the server thread; immutable. {@code sections} holds the codes of each
     * 16³ section, ordered {@code (j * nsz + k) * nsx + i} for section i along x, j along y and k along z;
     * {@code sourceCells} are region cell indices {@code (y * nz + z) * nx + x}, parallel to {@code sourceWatts} and
     * {@code sourceLimits} (C, NaN for none; the array null for none at all). {@code saved} holds the saved sections in
     * the same order (entries null where none; the array null for none at all), to be decayed to game tick
     * {@code now}. {@code lattice} and {@code tops} are the background's ({@link Weather}); null for the uniform
     * {@code backgroundT}. {@code kappa} is the air's heat diffusivity, m²/s ({@code [heat] airflowHeatDiffusivity}).
     * {@code sourceRadius} is how far from a heat source the air's motion always counts, cells
     * ({@code [heat] airflowSourceVelocityRadius}).
     */
    record Spec(int x0, int y0, int z0, int nx, int ny, int nz, AirflowSections.Codes[] sections,
                AirflowCells.Table table, double backgroundT, double windX, double windZ, int[] sourceCells,
                double[] sourceWatts, double[] sourceLimits, AirflowSaved.Section[] saved, long now, float[] lattice,
                int[] tops, double kappa, int sourceRadius) {
        /** As above, with GreedyFluids' default source-velocity radius. */
        Spec(int x0, int y0, int z0, int nx, int ny, int nz, AirflowSections.Codes[] sections,
             AirflowCells.Table table, double backgroundT, double windX, double windZ, int[] sourceCells,
             double[] sourceWatts, double[] sourceLimits, AirflowSaved.Section[] saved, long now, float[] lattice,
             int[] tops, double kappa) {
            this(x0, y0, z0, nx, ny, nz, sections, table, backgroundT, windX, windZ, sourceCells, sourceWatts,
                    sourceLimits, saved, now, lattice, tops, kappa, AdaptiveMesh3D.DEFAULT_SOURCE_VELOCITY_RADIUS);
        }

        /** As above, with {@link #DEFAULT_KAPPA}. */
        Spec(int x0, int y0, int z0, int nx, int ny, int nz, AirflowSections.Codes[] sections,
             AirflowCells.Table table, double backgroundT, double windX, double windZ, int[] sourceCells,
             double[] sourceWatts, double[] sourceLimits, AirflowSaved.Section[] saved, long now, float[] lattice,
             int[] tops) {
            this(x0, y0, z0, nx, ny, nz, sections, table, backgroundT, windX, windZ, sourceCells, sourceWatts,
                    sourceLimits, saved, now, lattice, tops, DEFAULT_KAPPA);
        }

        /** As above, with nothing saved, no limits and a uniform background. */
        Spec(int x0, int y0, int z0, int nx, int ny, int nz, AirflowSections.Codes[] sections,
             AirflowCells.Table table, double backgroundT, double windX, double windZ, int[] sourceCells,
             double[] sourceWatts) {
            this(x0, y0, z0, nx, ny, nz, sections, table, backgroundT, windX, windZ, sourceCells, sourceWatts, null,
                    null, 0, null, null);
        }

        /** As above, built from saved sections. */
        Spec(int x0, int y0, int z0, int nx, int ny, int nz, AirflowSections.Codes[] sections,
             AirflowCells.Table table, double backgroundT, double windX, double windZ, int[] sourceCells,
             double[] sourceWatts, AirflowSaved.Section[] saved, long now) {
            this(x0, y0, z0, nx, ny, nz, sections, table, backgroundT, windX, windZ, sourceCells, sourceWatts, null,
                    saved, now, null, null);
        }

        /** This spec with another cell table (M8e: replays comparing fixed-wall surfaces). */
        Spec withTable(AirflowCells.Table other) {
            return new Spec(x0, y0, z0, nx, ny, nz, sections, other, backgroundT, windX, windZ, sourceCells,
                    sourceWatts, sourceLimits, saved, now, lattice, tops, kappa, sourceRadius);
        }
    }

    /** A block's source power, W (0: none), and its absolute limit, C (NaN: none); region cells. */
    record SourceChange(int x, int y, int z, double watts, double limit) {
        SourceChange(int x, int y, int z, double watts) {
            this(x, y, z, watts, Double.NaN);
        }
    }

    /**
     * The outside air: wind, m/s, and the background: the lattice ({@link AirflowField}, C) and each column's first
     * open height (region cells) for the follow weights; without a lattice, {@code backgroundT} everywhere.
     */
    record Weather(double backgroundT, double windX, double windZ, float[] lattice, int[] tops) {
        Weather(double backgroundT, double windX, double windZ) {
            this(backgroundT, windX, windZ, null, null);
        }
    }

    /** The players' body heat: x, y, z (region metres) and watts per player, packed. */
    record Bodies(double[] xyzw) {
    }

    /** One source block: its power, W, limit, C (NaN: none), and whether its thermostat has it on. */
    private static final class Block {
        double watts, limit;
        boolean on = true;

        Block(double watts, double limit) {
            this.watts = watts;
            this.limit = limit;
        }
    }

    private static final int[][] FACES = {{-1, 0, 0}, {1, 0, 0}, {0, -1, 0}, {0, 1, 0}, {0, 0, -1}, {0, 0, 1}};

    private final AirRegion region;
    private final CellGrid3D grid;
    private final int x0, y0, z0;
    private final int nx, ny, nz;
    private final boolean restored;
    private final Map<Integer, Block> blocks = new HashMap<>();
    private Map<Integer, Double> applied = new HashMap<>();
    private double backgroundT;
    private float[] lattice;
    private int[] tops;
    private boolean followStale;
    private int desyncs, sinceCheck;
    private long edits, toggles;
    private long editBatches, editNanos;
    /** M8b: the column tops the border conditions follow (null: none set). */
    private int[] borderTops;
    /** M8b, per side: the EXCHANGE cut cells (face-plane indices), their far temperatures and distances (the arrays
     *  the region reads), the frames they were last read from, and whether they must be read again. */
    private final int[][] cut = new int[6][];
    private final float[][] far = new float[6][], reach = new float[6][];
    private final List<AirflowBorders.Beside>[] readFrom = newLists();
    private final boolean[] unread = new boolean[6];
    private AirflowBorders.Neighbours neighbours = AirflowBorders.NONE;
    /** Heat out through EXCHANGE faces so far, J (into the neighbours; negative: in from them). */
    private double exchangedJ;
    /** The dynamic start (M8e e3): the weight it was applied at (0: not applied), its CG iterations and time, ms. */
    private double startWeight;
    private int startIterations;
    private double startMs;

    private AirflowRegionModel(CellGrid3D grid, Spec spec) {
        this.grid = grid;
        this.x0 = spec.x0();
        this.y0 = spec.y0();
        this.z0 = spec.z0();
        this.nx = spec.nx();
        this.ny = spec.ny();
        this.nz = spec.nz();
        this.backgroundT = spec.backgroundT();
        this.lattice = spec.lattice();
        this.tops = spec.tops();
        Background3D background = background();
        ThermalState initial = spec.saved() == null ? null
                : AirflowSaved.unpack(spec.saved(), nx, ny, nz, background::cell, spec.now());
        restored = initial != null;
        SolverConfig3D config = SolverConfig3D.DEFAULT.withKappa(spec.kappa()).withAmbientT(background.reference());
        for (int i = 0; i < spec.sourceCells().length; i++) {
            double limit = spec.sourceLimits() == null ? Double.NaN : spec.sourceLimits()[i];
            blocks.merge(spec.sourceCells()[i], new Block(spec.sourceWatts()[i], limit), (a, b) -> {
                a.watts += b.watts;
                return a;
            });
        }
        // M7f: the sources go on the grid before the region is built, so its keep-fine cubes hold them, as every
        // GreedyFluids bench builds a scene (sources set afterwards get none: an open fire meshed into a few huge boxes)
        for (Map.Entry<Integer, Double> w : wanted().entrySet()) {
            int c = w.getKey();
            grid.setSource(c % nx, c / (nx * nz), c / nx % nz, w.getValue());
        }
        // M8b: the border conditions go on the grid before the region is built, so its first mesh has them
        borderTops = spec.tops();
        for (FaceBoundary f : AirflowBorders.conditions(nx, ny, nz, borderTops)) {
            grid.setFaceBoundary(f.side(), f.x(), f.y(), f.z(), f.boundary());
        }
        region = initial == null ? new AirRegion(grid, background, null, spec.windX(), spec.windZ(), config)
                : AirRegion.restore(grid, background, initial, spec.windX(), spec.windZ(), config);
        region.mesh().setSourceVelocityRadius(spec.sourceRadius(), AdaptiveMesh3D.DEFAULT_SOURCE_VELOCITY_LINGER);
        cuts();
        distribute();
        dynamicStart(spec);
    }

    /** Moves the new region toward the dynamic start's estimate (M8e e3; see the class notes). */
    private void dynamicStart(Spec spec) {
        if (!spec.table().dynamicStart()) {
            return;
        }
        double weight = 1;
        if (restored) {
            long saved = Long.MIN_VALUE;
            for (AirflowSaved.Section s : spec.saved()) {
                if (s != null) {
                    saved = Math.max(saved, s.tick());
                }
            }
            weight = -Math.expm1(-Math.max(0, spec.now() - saved) / AirflowSaved.TAU_TICKS);
            if (weight < MIN_START_WEIGHT) {
                return;
            }
        }
        Map<Integer, Double> duty = new HashMap<>();
        double[] watts = startWatts(duty);
        if (!region.needsEstimate(watts)) {
            return;
        }
        float[] tie = skyOpen(spec.tops());
        SteadyHeat3D.Estimate e = region.estimateStart(startSettings, watts, tie);
        int iterations = e.iterations();
        double ms = e.ms();
        for (int pass = 0; pass < DUTY_PASSES && dutyCycles(e, duty); pass++) {
            e = region.estimateStart(startSettings, startWatts(duty), tie);
            iterations += e.iterations();
            ms += e.ms();
        }
        region.moveTowardEstimate(e, weight);
        startWeight = weight;
        startIterations = iterations;
        startMs = ms;
    }

    /**
     * The dynamic start's outdoor tie per region cell: 1 for air at or above its column's first open height
     * ({@code tops}, region cells by {@code z * nx + x}), 0 below; null without tops (GreedyFluids then ties air whose
     * follow weight is 1).
     */
    private float[] skyOpen(int[] tops) {
        if (tops == null) {
            return null;
        }
        float[] out = new float[nx * ny * nz];
        for (int z = 0; z < nz; z++) {
            for (int x = 0; x < nx; x++) {
                for (int y = Math.max(0, tops[z * nx + x]); y < ny; y++) {
                    if (grid.type(x, y, z).isFluid()) {
                        out[grid.index(x, y, z)] = 1;
                    }
                }
            }
        }
        return out;
    }

    /**
     * Per box of the current graph, W: every source block's power by the power rule, thermostats on and scaled by
     * their duty ({@code duty}, by block cell; 1 where absent).
     */
    private double[] startWatts(Map<Integer, Double> duty) {
        var g = region.mesh().graph();
        double[] out = new double[g.boxCount()];
        for (Map.Entry<Integer, Block> b : blocks.entrySet()) {
            List<Integer> into = targets(b.getKey());
            if (into.isEmpty()) {
                continue;
            }
            double w = b.getValue().watts * duty.getOrDefault(b.getKey(), 1.0) / into.size();
            for (int c : into) {
                int box = g.boxOfCellIndex(c);
                if (box >= 0) {
                    out[box] += w;
                }
            }
        }
        return out;
    }

    /**
     * Scales each thermostat whose air the estimate takes past its limit by the share of its rise that reaches the
     * limit (a heater whose air starts past its limit already goes to 0); returns whether any changed.
     */
    private boolean dutyCycles(SteadyHeat3D.Estimate e, Map<Integer, Double> duty) {
        var g = e.graph();
        boolean changed = false;
        for (Map.Entry<Integer, Block> b : blocks.entrySet()) {
            Block k = b.getValue();
            if (Double.isNaN(k.limit) || k.watts == 0) {
                continue;
            }
            List<Integer> into = targets(b.getKey());
            if (into.isEmpty()) {
                continue;
            }
            double t = 0, cold = 0;
            for (int c : into) {
                int box = g.boxOfCellIndex(c);
                t += e.boxT()[box];
                cold += e.coldBoxT()[box];
            }
            t /= into.size();
            cold /= into.size();
            boolean past = k.watts > 0 ? t > k.limit + DUTY_TOLERANCE : t < k.limit - DUTY_TOLERANCE;
            if (!past) {
                continue;
            }
            double rise = t - cold, share = rise != 0 ? (k.limit - cold) / rise : 0;
            duty.put(b.getKey(), duty.getOrDefault(b.getKey(), 1.0) * Math.max(0, Math.min(1, share)));
            changed = true;
        }
        return changed;
    }

    /** Builds the region (air thread: construction meshes the window, tens to hundreds of ms). */
    static AirflowRegionModel build(Spec spec) {
        if (spec.nx() % 16 != 0 || spec.ny() % 16 != 0 || spec.nz() % 16 != 0) {
            throw new IllegalArgumentException("window must be whole sections: " + spec.nx() + "×" + spec.ny() + "×" + spec.nz());
        }
        int nsx = spec.nx() / 16, nsy = spec.ny() / 16, nsz = spec.nz() / 16;
        if (spec.sections().length != nsx * nsy * nsz) {
            throw new IllegalArgumentException("expected " + nsx * nsy * nsz + " sections, got " + spec.sections().length);
        }
        CellGrid3D grid = new CellGrid3D(spec.nx(), spec.ny(), spec.nz());
        grid.setFilmResistance(spec.table().filmResistance()); // before the region: its stores and graph read it
        grid.setFixedSurface(spec.table().fixedSurface()); // M8e: likewise its stores
        grid.setFixedKeepFine(spec.table().fixedKeepFine()); // and its keep-fine marks
        for (int j = 0; j < nsy; j++) {
            for (int k = 0; k < nsz; k++) {
                for (int i = 0; i < nsx; i++) {
                    spec.sections()[(j * nsz + k) * nsx + i].writeTo(grid, 16 * i, 16 * j, 16 * k, spec.table());
                }
            }
        }
        return new AirflowRegionModel(grid, spec);
    }

    /** The background now: the lattice and sky follow weights, or (without a lattice) uniform with the M7b rule. */
    private Background3D background() {
        if (lattice == null) {
            return Background3D.uniform(nx, ny, nz, backgroundT).withFollow(followAbove());
        }
        float[] follow = tops == null ? followAbove() : AirflowField.follow(AirflowField.sky(grid, tops));
        return Background3D.of(nx, ny, nz, AirflowField.cells(lattice, nx, ny, nz), follow);
    }

    @Override
    public void apply(List<Object> events) {
        List<AirflowBatch.Change> changes = new ArrayList<>();
        boolean sources = false;
        Weather ahead = null, weather = null;
        for (Object e : events) {
            if (e instanceof AirflowBatch.Change c) {
                if (changes.isEmpty() && weather != null) {
                    ahead = weather; // the manager's outside air up to the edits: shifted with the old follow weights
                    weather = null;
                }
                changes.add(c);
            } else if (e instanceof SourceChange s) {
                int cell = grid.index(s.x(), s.y(), s.z());
                if (s.watts() == 0) {
                    blocks.remove(cell);
                } else {
                    Block b = blocks.get(cell);
                    if (b == null) {
                        blocks.put(cell, new Block(s.watts(), s.limit()));
                    } else {
                        b.watts = s.watts();
                        b.limit = s.limit();
                    }
                }
                sources = true;
            } else if (e instanceof Weather w) {
                weather = w; // the latest wins
            } else if (e instanceof Bodies b) {
                region.setEntityHeat(b.xyzw());
            } else {
                throw new IllegalArgumentException("unknown airflow event " + e);
            }
        }
        if (ahead != null) {
            weather(ahead);
        }
        if (!changes.isEmpty()) {
            AirflowBatch batch = AirflowBatch.of(changes, nx, nz);
            long started = System.nanoTime();
            List<CellEdit3D> first = valid(batch.first);
            if (!first.isEmpty()) {
                region.applyEdits(first);
            }
            List<CellEdit3D> second = valid(batch.second);
            if (!second.isEmpty()) {
                region.applyEdits(second);
            }
            if (!first.isEmpty() || !second.isEmpty()) {
                editBatches++;
                editNanos += System.nanoTime() - started;
            }
            edits += first.size() + second.size();
            boolean toggled = false;
            for (AirflowBatch.DoorToggle d : batch.doors) {
                if (grid.type(d.x(), d.y(), d.z()) == CellType.DOOR) {
                    region.setDoorOpen(d.x(), d.y(), d.z(), d.open());
                    toggles++;
                    toggled = true;
                } else {
                    desyncs++;
                }
            }
            for (AirflowBatch.WallChange w : batch.walls) {
                if (grid.type(w.x(), w.y(), w.z()) == CellType.SOLID) {
                    region.setFixedTemperature(w.x(), w.y(), w.z(), w.celsius());
                } else {
                    desyncs++;
                }
            }
            if (!first.isEmpty() || !second.isEmpty()) {
                sources = true;
                followStale = true;
            }
            if (toggled) {
                followStale = true; // a shut door stops the sky (M7f)
            }
        }
        if (sources) {
            distribute();
        }
        if (weather != null) {
            weather(weather);
        }
    }

    /** Sets the background (when anything in it, or the follow weights, changed) and the wind. */
    private void weather(Weather w) {
        if (w.tops() != null) {
            borders(w.tops());
        }
        boolean changed = followStale || w.backgroundT() != backgroundT || w.lattice() != lattice || w.tops() != tops;
        if (changed) {
            backgroundT = w.backgroundT();
            lattice = w.lattice();
            tops = w.tops();
            followStale = false;
            region.setBackground(background());
        }
        region.setWind(w.windX(), w.windZ());
    }

    /**
     * M8b: new column tops change the conditions of the cut cells whose enclosure changed (a roof built or broken
     * over a cut); the region remeshes them.
     */
    private void borders(int[] tops) {
        List<FaceBoundary> changes = AirflowBorders.changes(nx, ny, nz, borderTops, tops);
        borderTops = tops;
        if (!changes.isEmpty()) {
            region.setFaceBoundaries(changes);
            cuts();
        }
    }

    /** M8b: lists each side's EXCHANGE cells from the grid, and has their far temperatures read again. */
    private void cuts() {
        for (Side side : Side.values()) {
            int s = side.ordinal();
            cut[s] = AirflowBorders.exchangeCells(grid, side);
            if (cut[s].length > 0 && far[s] == null) {
                far[s] = new float[grid.faceLength(side)];
                reach[s] = new float[far[s].length];
                Arrays.fill(far[s], Float.NaN);
                Arrays.fill(reach[s], Float.NaN);
                region.setExchangeTemperatures(side, far[s]);
                region.setExchangeDistances(side, reach[s]);
            }
            unread[s] = true;
        }
    }

    /**
     * M8b: the far side of the EXCHANGE cells, from the running neighbours' latest frames (read again only when a
     * frame is new, a neighbour came or went, or the cells changed). Cells no running neighbour holds are insulated.
     */
    private void exchange() {
        for (Side side : Side.values()) {
            int s = side.ordinal();
            if (cut[s].length == 0) {
                continue;
            }
            List<AirflowBorders.Beside> across = neighbours.across(side);
            if (!unread[s] && sameFrames(across, readFrom[s])) {
                continue;
            }
            readFrom[s] = across;
            unread[s] = false;
            for (int i : cut[s]) {
                AirflowBorders.read(across, side, i, nx, ny, nz, far[s], reach[s]);
            }
        }
    }

    /** Whether two reads of a side found the same frames at the same places. */
    private static boolean sameFrames(List<AirflowBorders.Beside> a, List<AirflowBorders.Beside> b) {
        if (a.size() != b.size()) {
            return false;
        }
        for (int i = 0; i < a.size(); i++) {
            AirflowBorders.Beside x = a.get(i), y = b.get(i);
            if (x.frame() != y.frame() || x.ox() != y.ox() || x.oy() != y.oy() || x.oz() != y.oz()) {
                return false;
            }
        }
        return true;
    }

    @SuppressWarnings("unchecked")
    private static List<AirflowBorders.Beside>[] newLists() {
        List<AirflowBorders.Beside>[] out = new List[6];
        Arrays.fill(out, List.of());
        return out;
    }

    /** Sets where the far temperatures come from (the region manager's running tiles; none by default). */
    void neighbours(AirflowBorders.Neighbours n) {
        neighbours = n == null ? AirflowBorders.NONE : n;
    }

    /** Heat out through the tile's EXCHANGE faces so far, J (negative: in from the neighbours). */
    double exchangedJoules() {
        return exchangedJ;
    }

    /** The edits whose cells are what they expect; the rest count as desyncs. */
    private List<CellEdit3D> valid(List<CellEdit3D> edits) {
        List<CellEdit3D> out = new ArrayList<>(edits.size());
        for (CellEdit3D e : edits) {
            CellType t = grid.type(e.x(), e.y(), e.z());
            boolean ok = switch (e.kind()) {
                case PLACE, PLACE_DOOR -> t == CellType.AIR;
                case BREAK -> t == CellType.SOLID;
                case REMOVE_DOOR -> t == CellType.DOOR;
            };
            if (ok) {
                out.add(e);
            } else {
                desyncs++;
            }
        }
        return out;
    }

    /** The cells a block's power goes into: its own if it is air or a door, else its AIR face neighbours. */
    private List<Integer> targets(int cell) {
        int x = cell % nx, z = cell / nx % nz, y = cell / (nx * nz);
        List<Integer> out = new ArrayList<>(6);
        if (grid.type(x, y, z).isFluid()) {
            out.add(cell);
            return out;
        }
        for (int[] f : FACES) {
            if (air(x + f[0], y + f[1], z + f[2])) {
                out.add(grid.index(x + f[0], y + f[1], z + f[2]));
            }
        }
        return out;
    }

    /**
     * Spreads the source blocks that are on over cells (the power rule) and sets what changed. A cell a block was
     * placed in has already lost its power (GreedyFluids clears it with the edit) and is left alone.
     */
    private void distribute() {
        Map<Integer, Double> want = wanted();
        for (Map.Entry<Integer, Double> a : applied.entrySet()) {
            int c = a.getKey();
            if (!want.containsKey(c) && grid.type(c % nx, c / (nx * nz), c / nx % nz).isFluid()) {
                set(a.getKey(), 0);
            }
        }
        for (Map.Entry<Integer, Double> w : want.entrySet()) {
            Double was = applied.get(w.getKey());
            if (was == null || was.doubleValue() != w.getValue()) {
                set(w.getKey(), w.getValue());
            }
        }
        applied = want;
    }

    /** The watts each cell should get from the source blocks that are on (the power rule). */
    private Map<Integer, Double> wanted() {
        Map<Integer, Double> want = new HashMap<>();
        for (Map.Entry<Integer, Block> b : blocks.entrySet()) {
            if (!b.getValue().on) {
                continue;
            }
            List<Integer> into = targets(b.getKey());
            for (int c : into) {
                want.merge(c, b.getValue().watts / into.size(), Double::sum);
            }
        }
        return want;
    }

    /** Switches limited sources by the air they work on; returns whether any changed. */
    private boolean thermostats() {
        boolean changed = false;
        for (Map.Entry<Integer, Block> e : blocks.entrySet()) {
            Block b = e.getValue();
            if (Double.isNaN(b.limit)) {
                continue;
            }
            List<Integer> into = targets(e.getKey());
            if (into.isEmpty()) {
                continue;
            }
            double sum = 0;
            for (int c : into) {
                sum += region.sampleT(c % nx + 0.5, c / (nx * nz) + 0.5, c / nx % nz + 0.5);
            }
            double t = sum / into.size();
            boolean heats = b.watts > 0;
            boolean on = b.on ? (heats ? t < b.limit : t > b.limit)
                    : (heats ? t < b.limit - HYSTERESIS : t > b.limit + HYSTERESIS);
            if (on != b.on) {
                b.on = on;
                changed = true;
            }
        }
        return changed;
    }

    private boolean air(int x, int y, int z) {
        return grid.inBounds(x, y, z) && grid.type(x, y, z) == CellType.AIR;
    }

    private void set(int cell, double watts) {
        region.setSource(cell % nx, cell / (nx * nz), cell / nx % nz, watts);
    }

    /** M7b's follow weights: 1 for air with only air above it inside the window, else 0. */
    private float[] followAbove() {
        float[] w = new float[nx * ny * nz];
        for (int z = 0; z < nz; z++) {
            for (int x = 0; x < nx; x++) {
                for (int y = ny - 1; y >= 0 && grid.type(x, y, z) == CellType.AIR; y--) {
                    w[grid.index(x, y, z)] = 1;
                }
            }
        }
        return w;
    }

    @Override
    public void step() {
        exchange();
        region.step();
        exchangedJ += region.stats().exchangeOut;
        if (++sinceCheck >= THERMOSTAT_STEPS) {
            sinceCheck = 0;
            if (thermostats()) {
                distribute();
            }
        }
    }

    @Override
    public double time() {
        return region.time();
    }

    @Override
    public double dt() {
        return region.dt();
    }

    @Override
    public boolean healthy() {
        var stats = region.stats();
        return Double.isFinite(stats.maxSpeed) && Double.isFinite(stats.deltaEnergy);
    }

    @Override
    public AirFrame publish() {
        return region.publish();
    }

    @Override
    public int boxes() {
        return region.mesh().graph().boxCount();
    }

    /** Edits skipped because their cell wasn't what they expected. */
    int desyncs() {
        return desyncs;
    }

    @Override
    public AirflowRuntime.Counters counters() {
        double watts = 0;
        for (double w : applied.values()) {
            watts += w;
        }
        return new AirflowRuntime.Counters(edits, toggles, desyncs, watts, region.mesh().heldSteps(), editBatches,
                editNanos, exchangedJ);
    }

    @Override
    public AirflowSaved.Tile save(long tick) {
        Background3D background = region.background();
        return new AirflowSaved.Tile(x0, y0, z0, nx / 16, ny / 16, nz / 16,
                AirflowSaved.pack(region.thermalState(), background::cell, tick));
    }

    /** Whether the region started from saved state. */
    boolean restored() {
        return restored;
    }

    /** The dynamic start's weight (0: not applied), CG iterations and time, ms (M8e e3; for tests and the report). */
    double startWeight() {
        return startWeight;
    }

    int startIterations() {
        return startIterations;
    }

    double startMs() {
        return startMs;
    }

    /** Watts set per region cell (for tests). */
    Map<Integer, Double> appliedSources() {
        return Map.copyOf(applied);
    }

    AirRegion region() {
        return region;
    }
}
