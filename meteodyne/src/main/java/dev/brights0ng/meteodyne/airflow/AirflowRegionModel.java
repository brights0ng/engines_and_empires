// Written by Claude (GreedyFluids M7b b3).
package dev.brights0ng.meteodyne.airflow;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import dev.brights0ng.greedyfluids.grid.CellEdit3D;
import dev.brights0ng.greedyfluids.grid.CellGrid3D;
import dev.brights0ng.greedyfluids.grid.CellType;
import dev.brights0ng.greedyfluids.region.AirFrame;
import dev.brights0ng.greedyfluids.region.AirRegion;
import dev.brights0ng.greedyfluids.solve.Background3D;

/**
 * One GreedyFluids region on the air thread (M7b b3): an {@link AirRegion} built from cached section codes, fed the
 * region manager's events.
 * <ul>
 *   <li><b>Geometry:</b> {@link AirflowBatch.Change}s, merged per step ({@link AirflowBatch}). An edit whose cell isn't
 *       what it expects (AIR for a placement, SOLID for a break, a door for a removal) is skipped and counted as a
 *       desync; the region manager's events and the region's grid should never disagree.</li>
 *   <li><b>Sources:</b> {@link SourceChange}s set a block's power. Bright's power rule (2026-11-04): a source in air or
 *       a door gets all of it; a solid one splits it equally over its AIR face neighbours inside the window (none: no
 *       power). Recomputed after every geometry batch.</li>
 *   <li><b>Weather:</b> {@link Weather} sets a uniform background and the wind (interim, until part c). Follow
 *       weights are 1 for air with no solid or door above it inside the window, else 0, and are recomputed when the
 *       background is next set after a geometry change.</li>
 * </ul>
 * Pure Java (GreedyFluids, no Minecraft).
 */
final class AirflowRegionModel implements AirflowRuntime.Model {

    /**
     * What a region is built from, gathered on the server thread; immutable. {@code sections} holds the codes of each
     * 16³ section, ordered {@code (j * nsz + k) * nsx + i} for section i along x, j along y and k along z;
     * {@code sourceCells} are region cell indices {@code (y * nz + z) * nx + x}, parallel to {@code sourceWatts}.
     */
    record Spec(int x0, int y0, int z0, int nx, int ny, int nz, AirflowSections.Codes[] sections,
                AirflowCells.Table table, double backgroundT, double windX, double windZ, int[] sourceCells,
                double[] sourceWatts) {
    }

    /** A block's source power, W (0: none), region cells. */
    record SourceChange(int x, int y, int z, double watts) {
    }

    /** The outside air: uniform background temperature, C, and wind, m/s. */
    record Weather(double backgroundT, double windX, double windZ) {
    }

    private static final int[][] FACES = {{-1, 0, 0}, {1, 0, 0}, {0, -1, 0}, {0, 1, 0}, {0, 0, -1}, {0, 0, 1}};

    private final AirRegion region;
    private final CellGrid3D grid;
    private final int nx, ny, nz;
    private final Map<Integer, Double> blocks = new HashMap<>();
    private Map<Integer, Double> applied = new HashMap<>();
    private double backgroundT;
    private boolean followStale;
    private int desyncs;

    private AirflowRegionModel(CellGrid3D grid, Spec spec) {
        this.grid = grid;
        this.nx = spec.nx();
        this.ny = spec.ny();
        this.nz = spec.nz();
        this.backgroundT = spec.backgroundT();
        Background3D background = Background3D.uniform(nx, ny, nz, backgroundT).withFollow(follow());
        region = new AirRegion(grid, background, spec.windX(), spec.windZ());
        for (int i = 0; i < spec.sourceCells().length; i++) {
            blocks.merge(spec.sourceCells()[i], spec.sourceWatts()[i], Double::sum);
        }
        distribute();
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
        for (int j = 0; j < nsy; j++) {
            for (int k = 0; k < nsz; k++) {
                for (int i = 0; i < nsx; i++) {
                    spec.sections()[(j * nsz + k) * nsx + i].writeTo(grid, 16 * i, 16 * j, 16 * k, spec.table());
                }
            }
        }
        return new AirflowRegionModel(grid, spec);
    }

    @Override
    public void apply(List<Object> events) {
        List<AirflowBatch.Change> changes = new ArrayList<>();
        boolean sources = false;
        Weather weather = null;
        for (Object e : events) {
            if (e instanceof AirflowBatch.Change c) {
                changes.add(c);
            } else if (e instanceof SourceChange s) {
                int cell = grid.index(s.x(), s.y(), s.z());
                if (s.watts() == 0) {
                    blocks.remove(cell);
                } else {
                    blocks.put(cell, s.watts());
                }
                sources = true;
            } else if (e instanceof Weather w) {
                weather = w; // the latest wins
            } else {
                throw new IllegalArgumentException("unknown airflow event " + e);
            }
        }
        if (!changes.isEmpty()) {
            AirflowBatch batch = AirflowBatch.of(changes, nx, nz);
            List<CellEdit3D> first = valid(batch.first);
            if (!first.isEmpty()) {
                unsource(first);
                region.applyEdits(first);
            }
            List<CellEdit3D> second = valid(batch.second);
            if (!second.isEmpty()) {
                unsource(second);
                region.applyEdits(second);
            }
            for (AirflowBatch.DoorToggle d : batch.doors) {
                if (grid.type(d.x(), d.y(), d.z()) == CellType.DOOR) {
                    region.setDoorOpen(d.x(), d.y(), d.z(), d.open());
                } else {
                    desyncs++;
                }
            }
            if (!first.isEmpty() || !second.isEmpty()) {
                sources = true;
                followStale = true;
            }
        }
        if (sources) {
            distribute();
        }
        if (weather != null) {
            if (weather.backgroundT() != backgroundT || followStale) {
                backgroundT = weather.backgroundT();
                followStale = false;
                region.setBackground(Background3D.uniform(nx, ny, nz, backgroundT).withFollow(follow()));
            }
            region.setWind(weather.windX(), weather.windZ());
        }
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

    /**
     * Takes the power off cells about to become solid: GreedyFluids refuses a source in a solid cell (its edits
     * don't clear one). {@link #distribute} gives the power back to the new neighbours after the batch.
     */
    private void unsource(List<CellEdit3D> edits) {
        for (CellEdit3D e : edits) {
            if (e.kind() == CellEdit3D.Kind.PLACE) {
                int cell = grid.index(e.x(), e.y(), e.z());
                if (applied.remove(cell) != null) {
                    set(cell, 0);
                }
            }
        }
    }

    /** Spreads the source blocks over cells (the power rule) and sets what changed. */
    private void distribute() {
        Map<Integer, Double> want = new HashMap<>();
        for (Map.Entry<Integer, Double> b : blocks.entrySet()) {
            int cell = b.getKey();
            int x = cell % nx, z = cell / nx % nz, y = cell / (nx * nz);
            if (grid.type(x, y, z).isFluid()) {
                want.merge(cell, b.getValue(), Double::sum);
                continue;
            }
            int open = 0;
            for (int[] f : FACES) {
                open += air(x + f[0], y + f[1], z + f[2]) ? 1 : 0;
            }
            for (int[] f : FACES) {
                if (open > 0 && air(x + f[0], y + f[1], z + f[2])) {
                    want.merge(grid.index(x + f[0], y + f[1], z + f[2]), b.getValue() / open, Double::sum);
                }
            }
        }
        for (Map.Entry<Integer, Double> a : applied.entrySet()) {
            if (!want.containsKey(a.getKey())) {
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

    private boolean air(int x, int y, int z) {
        return grid.inBounds(x, y, z) && grid.type(x, y, z) == CellType.AIR;
    }

    private void set(int cell, double watts) {
        region.setSource(cell % nx, cell / (nx * nz), cell / nx % nz, watts);
    }

    /** 1 for air with only air above it inside the window, else 0. */
    private float[] follow() {
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
        region.step();
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

    /** Watts set per region cell (for tests). */
    Map<Integer, Double> appliedSources() {
        return Map.copyOf(applied);
    }

    AirRegion region() {
        return region;
    }
}
