package dev.brights0ng.enginesandempires.weather.heat.air;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * One region of the heat simulation (weather phase 8f-1): a box of cells, each holding how much warmer or colder than
 * the outside air it is (C). Pure Java; the world side fills in the cells, the sources and the wind, then calls
 * {@link #step} with however much time has passed.
 *
 * <p>Cell kinds:
 * <ul>
 *   <li>{@link #BOUNDARY}: held at the outside air (offset 0). The box's faces, and anything the world side wants
 *       treated as "outside" (unloaded chunks).</li>
 *   <li>{@link #AIR}: indoor air (under a roof): mixes with its neighbours, no wind.</li>
 *   <li>{@link #EXPOSED}: air open to the sky: mixes, is carried by the wind and loses heat to the free atmosphere.</li>
 *   <li>{@link #SOLID}: a block with a conductance and heat capacity (relative to stone); faces listed in its open
 *       mask (a slab's empty half, an open door) let air through as if air.</li>
 * </ul>
 *
 * <p>Each step, mixing, wall exchange, the wind over open-sky air (upwind: each exposed cell takes in air from its
 * upwind neighbour), losses and sources are solved together implicitly (backward Euler, red-black Gauss-Seidel), so
 * any step length is stable; steps longer than {@link AirParams#maxStep} are split. Between air cells one above the
 * other, mixing is stronger when the lower is warmer and weaker when it is colder, and heat drifts up through warm air
 * and down through cold (so a heated room is warmest under the ceiling, a cooled one coldest at the floor). A source
 * with a limit acts as a thermostat: it puts in no more than takes its cell to the limit.
 *
 * <p>Not thread safe; one region is stepped by one thread at a time.
 */
public final class AirGrid {

    public static final byte BOUNDARY = 0;
    public static final byte AIR = 1;
    public static final byte EXPOSED = 2;
    public static final byte SOLID = 3;

    /** Face bits for {@link #setSolid}'s open mask: -x, +x, -y, +y, -z, +z. */
    public static final int WEST = 1, EAST = 2, DOWN = 4, UP = 8, NORTH = 16, SOUTH = 32;
    public static final int ALL_FACES = 63;

    /** A source of heat (power > 0) or cold (power < 0) in a cell; {@code limit} is an offset, NaN for none. */
    public record Source(int cell, double power, double limit) {
    }

    private final int nx, ny, nz;
    private final AirParams params;
    private final byte[] kind;
    private final byte[] open;
    private final float[] conductance;
    private final float[] capacity;
    /** How much of the wind each cell feels, 0-1 (open sky 1 by default; the world side sets it from shelter). */
    private final float[] windShare;
    private double[] t;
    private double[] scratch;
    private double[] heat;
    private double[] limit;
    /** Per step: the vertical coefficients from a cell to the one above it, and from that one back down. */
    private double[] upward, downward;
    private final List<Source> sources = new ArrayList<>();
    private double windX, windZ;
    /** Face conductances (excluding buoyancy) to the next cell along x, y and z; 0 where none. */
    private double[] gx, gy, gz;
    private boolean dirty = true;

    public AirGrid(int nx, int ny, int nz, AirParams params) {
        if (nx < 3 || ny < 3 || nz < 3) {
            throw new IllegalArgumentException("a region needs at least 3 cells a side");
        }
        this.nx = nx;
        this.ny = ny;
        this.nz = nz;
        this.params = params;
        int n = nx * ny * nz;
        kind = new byte[n];
        open = new byte[n];
        conductance = new float[n];
        capacity = new float[n];
        windShare = new float[n];
        t = new double[n];
        scratch = new double[n];
        heat = new double[n];
        limit = new double[n];
        upward = new double[n];
        downward = new double[n];
        Arrays.fill(kind, AIR);
        for (int z = 0; z < nz; z++) {
            for (int y = 0; y < ny; y++) {
                for (int x = 0; x < nx; x++) {
                    if (x == 0 || y == 0 || z == 0 || x == nx - 1 || y == ny - 1 || z == nz - 1) {
                        kind[index(x, y, z)] = BOUNDARY;
                    }
                }
            }
        }
    }

    public int nx() {
        return nx;
    }

    public int ny() {
        return ny;
    }

    public int nz() {
        return nz;
    }

    public AirParams params() {
        return params;
    }

    public int index(int x, int y, int z) {
        return (z * ny + y) * nx + x;
    }

    public byte kind(int x, int y, int z) {
        return kind[index(x, y, z)];
    }

    /** Indoor or open-sky air (the box's faces stay boundary whatever is asked). */
    public void setAir(int x, int y, int z, boolean exposed) {
        set(x, y, z, exposed ? EXPOSED : AIR, 0, 0, 0);
    }

    /**
     * A block of the given conductance and heat capacity (relative to stone), with the faces in {@code openFaces}
     * letting air through.
     */
    public void setSolid(int x, int y, int z, double conductance, double capacity, int openFaces) {
        set(x, y, z, SOLID, conductance, capacity, openFaces);
    }

    /** Held at the outside air. */
    public void setBoundary(int x, int y, int z) {
        set(x, y, z, BOUNDARY, 0, 0, 0);
        t[index(x, y, z)] = 0;
    }

    /**
     * Sets every inner cell at once from arrays laid out like {@link #index} (the world side's snapshot of a region's
     * blocks): kinds, conductances, capacities and open faces. The box's faces stay boundary whatever the arrays say;
     * cells that become boundary are reset to 0. Offsets are kept.
     */
    public void setCells(byte[] kinds, float[] conductances, float[] capacities, byte[] openFaces) {
        for (int z = 1; z < nz - 1; z++) {
            for (int y = 1; y < ny - 1; y++) {
                for (int x = 1; x < nx - 1; x++) {
                    int i = index(x, y, z);
                    kind[i] = kinds[i];
                    conductance[i] = conductances[i];
                    capacity[i] = capacities[i];
                    open[i] = openFaces[i];
                    windShare[i] = kinds[i] == EXPOSED ? 1 : 0;
                    if (kinds[i] == BOUNDARY) {
                        t[i] = 0;
                    }
                }
            }
        }
        dirty = true;
    }

    /** Sets every non-boundary cell's offset from {@code values} (laid out like {@link #index}). */
    public void setOffsets(float[] values) {
        for (int i = 0; i < t.length; i++) {
            if (kind[i] != BOUNDARY) {
                t[i] = values[i];
            }
        }
    }

    /** Copies every cell's offset into {@code out} (laid out like {@link #index}). */
    public void exportOffsets(float[] out) {
        for (int i = 0; i < t.length; i++) {
            out[i] = (float) t[i];
        }
    }

    /** Copies every cell's kind into {@code out}. */
    public void exportKinds(byte[] out) {
        System.arraycopy(kind, 0, out, 0, kind.length);
    }

    /**
     * Sets how much of the wind every air cell feels, 0-1 (laid out like {@link #index}; other cells are left at 0):
     * the shelter of the walls around it (weather phase 8f-4, the same measure as ships' wind). Without it, open-sky
     * cells feel all of it and indoor cells none.
     */
    public void setWindShares(float[] shares) {
        for (int i = 0; i < kind.length; i++) {
            windShare[i] = isAirLike(kind[i]) ? Math.max(0, Math.min(1, shares[i])) : 0;
        }
    }

    /** Copies every cell's wind share into {@code out}. */
    public void exportWindShares(float[] out) {
        System.arraycopy(windShare, 0, out, 0, windShare.length);
    }

    private void set(int x, int y, int z, byte k, double cond, double cap, int openFaces) {
        if (x <= 0 || y <= 0 || z <= 0 || x >= nx - 1 || y >= ny - 1 || z >= nz - 1) {
            return;
        }
        int i = index(x, y, z);
        kind[i] = k;
        conductance[i] = (float) cond;
        capacity[i] = (float) cap;
        open[i] = (byte) openFaces;
        windShare[i] = k == EXPOSED ? 1 : 0;
        dirty = true;
    }

    /** The wind over open-sky air, m/s (blocks per second) along x and z. */
    public void setWind(double windX, double windZ) {
        this.windX = windX;
        this.windZ = windZ;
    }

    public void clearSources() {
        sources.clear();
    }

    /**
     * A source in cell (x, y, z) putting in {@code power} (C x air cells / s; negative for cold) until the cell reaches
     * {@code limit} (an offset from the outside air, C; NaN for none).
     */
    public void addSource(int x, int y, int z, double power, double limit) {
        sources.add(new Source(index(x, y, z), power, limit));
    }

    public List<Source> sources() {
        return sources;
    }

    /** The offset, C, in cell (x, y, z). */
    public double offset(int x, int y, int z) {
        return t[index(x, y, z)];
    }

    /** Sets the offset of a cell (loading a saved field). */
    public void setOffset(int x, int y, int z, double offset) {
        int i = index(x, y, z);
        if (kind[i] != BOUNDARY) {
            t[i] = offset;
        }
    }

    /** The offset at a point in cell coordinates (cell centres at integer + 0.5), trilinear over air cells. */
    public double offsetAt(double px, double py, double pz) {
        return sample(t, px - 0.5, py - 0.5, pz - 0.5);
    }

    /** The largest offset magnitude anywhere (for dropping regions that have cooled down). */
    public double maxMagnitude() {
        double m = 0;
        for (double v : t) {
            m = Math.max(m, Math.abs(v));
        }
        return m;
    }

    /** The mean offset over the air cells (indoor and exposed) inside the given cell box, inclusive. */
    public double meanAirOffset(int x0, int y0, int z0, int x1, int y1, int z1) {
        double sum = 0;
        int count = 0;
        for (int z = z0; z <= z1; z++) {
            for (int y = y0; y <= y1; y++) {
                for (int x = x0; x <= x1; x++) {
                    int i = index(x, y, z);
                    if (kind[i] == AIR || kind[i] == EXPOSED) {
                        sum += t[i];
                        count++;
                    }
                }
            }
        }
        return count == 0 ? 0 : sum / count;
    }

    /** Advances the field by {@code seconds} (split into steps of at most {@link AirParams#maxStep}). */
    public void step(double seconds) {
        if (!(seconds > 0)) {
            return;
        }
        int steps = (int) Math.ceil(seconds / params.maxStep());
        double dt = seconds / steps;
        int sweeps = sweepsFor(dt);
        for (int s = 0; s < steps; s++) {
            stepOnce(dt, sweeps);
        }
    }

    /**
     * Relaxation sweeps for a step of {@code dt} seconds: {@link AirParams#sweeps} for a 1-second step, growing with
     * the square root of the step (heat spreads further in a longer step), so a 10-second step costs about three
     * 1-second steps instead of ten.
     */
    int sweepsFor(double dt) {
        return Math.max(params.sweeps(), (int) Math.ceil(params.sweeps() * Math.sqrt(dt)));
    }

    /**
     * Runs the field to (near) its steady state: {@code seconds} of simulated time in long steps with more sweeps.
     * For regions that should start as they would have been all along (a lava cave a player walks into).
     */
    public void settle(double seconds) {
        int steps = (int) Math.ceil(seconds / params.maxStep());
        double dt = seconds / steps;
        for (int s = 0; s < steps; s++) {
            stepOnce(dt, params.sweeps() * 3);
        }
    }

    private void stepOnce(double dt, int sweeps) {
        if (dirty) {
            buildConductances();
            dirty = false;
        }
        double[] old = scratch;
        System.arraycopy(t, 0, old, 0, t.length);

        // Sources by cell: power summed; the strictest limit in the direction they push
        double[] q = heat;
        Arrays.fill(q, 0);
        Arrays.fill(limit, Double.NaN);
        for (Source s : sources) {
            q[s.cell] += s.power;
        }
        for (Source s : sources) {
            if (Double.isNaN(s.limit)) {
                continue;
            }
            double l = limit[s.cell];
            if (q[s.cell] > 0) {
                limit[s.cell] = Double.isNaN(l) ? s.limit : Math.min(l, s.limit);
            } else if (q[s.cell] < 0) {
                limit[s.cell] = Double.isNaN(l) ? s.limit : Math.max(l, s.limit);
            }
        }

        // Vertical mixing and buoyant drift, fixed for the step (from the start-of-step temperatures)
        for (int i = 0; i + nx < t.length; i++) {
            double g = gy[i];
            if (g == 0) {
                upward[i] = 0;
                downward[i] = 0;
                continue;
            }
            int a = i + nx;
            g *= buoyancy(i, a, old);
            double rise = drift(i, a, old);
            upward[i] = g + Math.max(0, rise);
            downward[i] = g + Math.max(0, -rise);
        }

        double windLoss = params.exposedWind() * Math.hypot(windX, windZ);
        double ax = Math.abs(windX);
        double az = Math.abs(windZ);
        int sz = nx * ny;
        for (int sweep = 0; sweep < sweeps; sweep++) {
            for (int parity = 0; parity < 2; parity++) {
                for (int z = 1; z < nz - 1; z++) {
                    for (int y = 1; y < ny - 1; y++) {
                        int start = 1 + ((1 + y + z + parity) & 1);
                        for (int x = start; x < nx - 1; x += 2) {
                            int i = index(x, y, z);
                            byte k = kind[i];
                            if (k == BOUNDARY) {
                                continue;
                            }
                            double c = k == SOLID ? capacity[i] * params.solidCap() : 1.0;
                            double diag = c / dt;
                            double rhs = diag * old[i];
                            // x neighbours
                            double g = gx[i - 1];
                            diag += g;
                            rhs += g * t[i - 1];
                            g = gx[i];
                            diag += g;
                            rhs += g * t[i + 1];
                            // z neighbours
                            g = gz[i - sz];
                            diag += g;
                            rhs += g * t[i - sz];
                            g = gz[i];
                            diag += g;
                            rhs += g * t[i + sz];
                            // y neighbours: mixing (stronger when warmer below) and the drift of warm air up and
                            // cold air down (more heat moves one way across the face than the other)
                            int below = i - nx;
                            diag += downward[below] + upward[i];
                            rhs += upward[below] * t[below] + downward[i] * t[i + nx];
                            if (k == EXPOSED) {
                                // Open sky: heat rises away into the free atmosphere
                                diag += params.exposedLoss();
                            }
                            float share = windShare[i];
                            if (share > 0 && k != SOLID) {
                                // Wind, as much as the cell's shelter lets through: carried off faster, and air coming
                                // in from the upwind side (implicit upwind; fresh air past walls)
                                diag += windLoss * share;
                                if (ax > 0) {
                                    int up = windX > 0 ? i - 1 : i + 1;
                                    double a = ax * share;
                                    diag += a;
                                    rhs += a * (isAirLike(kind[up]) ? t[up] : 0);
                                }
                                if (az > 0) {
                                    int up = windZ > 0 ? i - sz : i + sz;
                                    double a = az * share;
                                    diag += a;
                                    rhs += a * (isAirLike(kind[up]) ? t[up] : 0);
                                }
                            }
                            double without = rhs / diag;
                            double qi = q[i];
                            if (qi == 0) {
                                t[i] = without;
                                continue;
                            }
                            double with = (rhs + qi) / diag;
                            double lim = limit[i];
                            if (Double.isNaN(lim)) {
                                t[i] = with;
                            } else if (qi > 0) {
                                // A heat source works like a thermostat: no more than reaches its limit
                                t[i] = without >= lim ? without : Math.min(with, lim);
                            } else {
                                t[i] = without <= lim ? without : Math.max(with, lim);
                            }
                        }
                    }
                }
            }
        }
    }

    /** The vertical air-mixing multiplier between {@code lower} and the cell above it (1 unless both are air). */
    private double buoyancy(int lower, int upper, double[] temps) {
        if (!isAirLike(kind[lower]) || !isAirLike(kind[upper])) {
            return 1;
        }
        double d = temps[lower] - temps[upper];
        if (d > 0) {
            return Math.min(params.convectMax(), 1 + params.convect() * d);
        }
        return d < 0 ? params.stable() : 1;
    }

    /**
     * The buoyant drift across the face between air cells {@code lower} and the one above it, air cells per second:
     * positive carries heat up (the pair is warmer than outside), negative carries it down (colder); 0 unless both
     * are air.
     */
    private double drift(int lower, int upper, double[] temps) {
        if (!isAirLike(kind[lower]) || !isAirLike(kind[upper])) {
            return 0;
        }
        double mean = (temps[lower] + temps[upper]) / 2;
        return Math.signum(mean) * Math.min(params.riseMax(), params.rise() * Math.abs(mean));
    }

    private static boolean isAirLike(byte k) {
        return k == AIR || k == EXPOSED;
    }

    /**
     * Trilinear sample of {@code field} at cell coordinates (cell centres at integers), over the air cells only (their
     * weights rescaled); 0 where no air cell is in reach.
     */
    private double sample(double[] field, double px, double py, double pz) {
        int x0 = (int) Math.floor(px);
        int y0 = (int) Math.floor(py);
        int z0 = (int) Math.floor(pz);
        double fx = px - x0;
        double fy = py - y0;
        double fz = pz - z0;
        double sum = 0;
        double weight = 0;
        for (int c = 0; c < 8; c++) {
            int x = x0 + (c & 1);
            int y = y0 + ((c >> 1) & 1);
            int z = z0 + ((c >> 2) & 1);
            double w = ((c & 1) == 0 ? 1 - fx : fx) * (((c >> 1) & 1) == 0 ? 1 - fy : fy)
                    * (((c >> 2) & 1) == 0 ? 1 - fz : fz);
            if (w <= 0 || x < 0 || y < 0 || z < 0 || x >= nx || y >= ny || z >= nz) {
                continue;
            }
            int i = index(x, y, z);
            if (isAirLike(kind[i])) {
                sum += w * field[i];
                weight += w;
            }
        }
        return weight > 1e-9 ? sum / weight : 0;
    }

    private void buildConductances() {
        int n = t.length;
        gx = new double[n];
        gy = new double[n];
        gz = new double[n];
        for (int z = 0; z < nz; z++) {
            for (int y = 0; y < ny; y++) {
                for (int x = 0; x < nx; x++) {
                    int i = index(x, y, z);
                    if (x + 1 < nx) {
                        gx[i] = faceConductance(i, EAST, index(x + 1, y, z), WEST);
                    }
                    if (y + 1 < ny) {
                        gy[i] = faceConductance(i, UP, index(x, y + 1, z), DOWN);
                    }
                    if (z + 1 < nz) {
                        gz[i] = faceConductance(i, SOUTH, index(x, y, z + 1), NORTH);
                    }
                }
            }
        }
    }

    /** Conductance across the face between cell {@code a} (its face {@code faceA}) and cell {@code b} (its {@code faceB}). */
    private double faceConductance(int a, int faceA, int b, int faceB) {
        boolean airA = passesAir(a, faceA);
        boolean airB = passesAir(b, faceB);
        if (kind[a] == BOUNDARY && kind[b] == BOUNDARY) {
            return 0;
        }
        if (airA && airB) {
            // Indoor air meeting open-sky air: an opening (door, window, missing wall) trades air by the stack effect
            // and outdoor breezes, far faster than air stirring inside a room
            if ((kind[a] == AIR && kind[b] == EXPOSED) || (kind[a] == EXPOSED && kind[b] == AIR)) {
                return params.airMix() * params.openingMix();
            }
            return params.airMix();
        }
        double r = (airA ? params.film() : halfResistance(a)) + (airB ? params.film() : halfResistance(b));
        return 1 / r;
    }

    /** Whether cell {@code i} acts as air on face {@code face}: air, boundary air, or a solid open on that face. */
    private boolean passesAir(int i, int face) {
        byte k = kind[i];
        return k != SOLID || (open[i] & face) != 0;
    }

    private double halfResistance(int i) {
        return 0.5 / (Math.max(1e-4, conductance[i]) * params.wallK());
    }
}
