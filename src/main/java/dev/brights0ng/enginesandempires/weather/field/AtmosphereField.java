package dev.brights0ng.enginesandempires.weather.field;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The atmosphere field (phase 3 of {@code claude/weather-backbone-plan.md}): coarse cells carrying the actual air
 * temperature (surface and aloft) and moisture around on the wind, near the players. Pure: {@link FieldEnv} supplies
 * the climate, wind and air-mass pushes.
 *
 * <h2>Each step, per cell</h2>
 * <ol>
 *   <li><b>Carry:</b> semi-Lagrangian: look back along the wind and take what was there (surface temperature with the
 *       surface wind, aloft temperature with the aloft wind, moisture with their average). Air moves at
 *       {@value #ADVECTION} blocks a second per m/s of wind: the pack's map is about 170 m a block across and its days 72x
 *       faster than real ones, so a real 10 m/s wind carries air about as far per in-game day as it does per real day.
 *       Where the look-back leaves the simulated tiles, fresh air at the climate's normal comes in.</li>
 *   <li><b>Mix:</b> a little of each neighbour, so the grid doesn't break into checkerboards.</li>
 *   <li><b>Air masses:</b> where a weather system is strong, toward its warm or cold air ({@link AirMassContact}),
 *       over about half a day.</li>
 *   <li><b>Back to normal:</b> toward the climate baseline (each cell's normal is stored in its tile and refreshed a
 *       little at a time by the server, {@link #refreshCell}; it only changes with the season): a day and a half over
 *       land, four days over water (so onshore winds keep coasts mild, Bright 2026-10-05); aloft three days.</li>
 *   <li><b>Moisture:</b> evaporation raises it toward the ground's equilibrium ({@link Moisture}); if the air holds more
 *       than it can at its cell's terrain height, the excess falls out. Air pushed up a mountainside cools and drops
 *       its water on the windward slope; the lee side gets air already wrung out: a rain shadow. The water dropped is
 *       recorded as recent precipitation (decaying over half a day); phase 4 turns it into real clouds and rain.</li>
 * </ol>
 *
 * <h2>Performance (perf report 2026-10-05)</h2>
 * Tiles can be stepped one at a time ({@link #stepTile}) so the server spreads them over ticks, and only tiles near
 * players are stepped; a tile coming back into use after a gap first catches up with the time it missed
 * ({@link #touch}). Reads remember the last tile used, and cells just outside the tiles keep their climate normal in a
 * cache ({@link #clearEdgeCache} refreshes it) instead of asking the climate every step.
 */
public final class AtmosphereField {

    public static final int CELL = 512;
    public static final int SIZE = 16;
    public static final int TILE = CELL * SIZE;
    /** Blocks per second of carry per m/s of wind. */
    public static final double ADVECTION = 0.4;
    /** The aloft level's normal offset from the surface: 1.5 km of standard cooling. */
    public static final double ALOFT_OFFSET = -9.75;
    static final double TAU_LAND = 36_000;
    static final double TAU_WATER = 96_000;
    static final double TAU_ALOFT = 72_000;
    static final double TAU_CONTACT = 12_000;
    static final double TAU_PRECIP = 12_000;
    static final double MIX = 0.05;
    /** How long a tile is kept after the last player leaves, ticks (3 in-game days). */
    public static final long MEMORY = 72_000;
    /** A tile unused for longer than this catches up before its next use, ticks. */
    static final long STALE = 200;

    private final Map<Long, FieldTile> tiles = new HashMap<>();
    /** Climate normals of cells just outside the tiles: {T, A, Q} by global cell key. */
    private final Map<Long, double[]> edge = new HashMap<>();
    /** The last tile read (most reads fall in the same tile as the one before). */
    private FieldTile lastTile;

    public Map<Long, FieldTile> tiles() {
        return tiles;
    }

    public static long key(int tx, int tz) {
        return ((long) tx << 32) | (tz & 0xFFFFFFFFL);
    }

    public FieldTile tile(int tx, int tz) {
        return tiles.get(key(tx, tz));
    }

    public void put(FieldTile tile) {
        tiles.put(key(tile.tx, tile.tz), tile);
        lastTile = null;
    }

    /** Makes sure the tiles within {@code radius} of (x, z) exist (new ones at the climate's normal) and are active. */
    public void ensure(double x, double z, double radius, FieldEnv env, long time) {
        int t0x = Math.floorDiv((int) Math.floor(x - radius), TILE);
        int t1x = Math.floorDiv((int) Math.floor(x + radius), TILE);
        int t0z = Math.floorDiv((int) Math.floor(z - radius), TILE);
        int t1z = Math.floorDiv((int) Math.floor(z + radius), TILE);
        for (int tz = t0z; tz <= t1z; tz++) {
            for (int tx = t0x; tx <= t1x; tx++) {
                FieldTile tile = tiles.get(key(tx, tz));
                if (tile == null) {
                    tile = fresh(tx, tz, env);
                    put(tile);
                }
                tile.lastActive = time;
            }
        }
    }

    /** A tile at the climate's normal. */
    static FieldTile fresh(int tx, int tz, FieldEnv env) {
        FieldTile tile = new FieldTile(tx, tz);
        for (int idx = 0; idx < SIZE * SIZE; idx++) {
            initCell(tile, idx, env);
        }
        return tile;
    }

    /** Sets cell {@code idx} of a new tile to the climate's normal (for building tiles a little at a time). */
    public static void initCell(FieldTile tile, int idx, FieldEnv env) {
        double[] c = refreshCell(tile, idx, env);
        tile.t[idx] = (float) c[0];
        tile.a[idx] = (float) (c[0] + ALOFT_OFFSET);
        tile.q[idx] = (float) (Moisture.capacity(c[0]) * Moisture.targetHumidity((int) c[2], c[1]));
    }

    /** Updates cell {@code idx}'s stored normal (temperature, humidity, surface) from the climate; returns it. */
    public static double[] refreshCell(FieldTile tile, int idx, FieldEnv env) {
        double[] c = env.climate(tile.cellX(idx % SIZE), tile.cellZ(idx / SIZE));
        tile.base[idx] = (float) c[0];
        tile.humidity[idx] = (float) c[1];
        tile.surface[idx] = (byte) c[2];
        return c;
    }

    /**
     * Marks the existing tiles within {@code radius} of (x, z) active (letting any that sat unused catch up first),
     * and returns the keys of the missing ones, nearest first (for building them a little at a time).
     */
    public List<Long> touch(double x, double z, double radius, long time) {
        int t0x = Math.floorDiv((int) Math.floor(x - radius), TILE);
        int t1x = Math.floorDiv((int) Math.floor(x + radius), TILE);
        int t0z = Math.floorDiv((int) Math.floor(z - radius), TILE);
        int t1z = Math.floorDiv((int) Math.floor(z + radius), TILE);
        List<long[]> missing = new ArrayList<>();
        for (int tz = t0z; tz <= t1z; tz++) {
            for (int tx = t0x; tx <= t1x; tx++) {
                FieldTile tile = tiles.get(key(tx, tz));
                if (tile != null) {
                    if (tile.lastActive > 0 && time - tile.lastActive > STALE) {
                        catchUp(tile, time - tile.lastActive);
                    }
                    tile.lastActive = time;
                } else {
                    double cx = (tx + 0.5) * TILE - x;
                    double cz = (tz + 0.5) * TILE - z;
                    missing.add(new long[]{key(tx, tz), (long) (cx * cx + cz * cz)});
                }
            }
        }
        missing.sort((a, b) -> Long.compare(a[1], b[1]));
        List<Long> out = new ArrayList<>(missing.size());
        for (long[] m : missing) {
            out.add(m[0]);
        }
        return out;
    }

    /**
     * A tile that sat unstepped for {@code gap} ticks: its air relaxes toward normal and its moisture toward the
     * ground's equilibrium as far as they would have in that time, and old rain fades, so it doesn't come back with
     * days-old weather frozen in place.
     */
    static void catchUp(FieldTile tile, long gap) {
        double aloftKeep = Math.exp(-gap / TAU_ALOFT);
        double rainKeep = Math.exp(-gap / TAU_PRECIP);
        for (int idx = 0; idx < SIZE * SIZE; idx++) {
            int surface = tile.surface[idx];
            double base = tile.base[idx];
            double keep = Math.exp(-gap / (surface == 2 ? TAU_WATER : TAU_LAND));
            tile.t[idx] = (float) (base + (tile.t[idx] - base) * keep);
            tile.a[idx] = (float) (base + ALOFT_OFFSET + (tile.a[idx] - base - ALOFT_OFFSET) * aloftKeep);
            double eq = Moisture.capacity(base) * Moisture.targetHumidity(surface, tile.humidity[idx]);
            double qKeep = Math.exp(-gap / Moisture.evaporationTicks(surface));
            tile.q[idx] = (float) (eq + (tile.q[idx] - eq) * qKeep);
            tile.p[idx] = (float) (tile.p[idx] * rainKeep);
            tile.r[idx] = (float) (tile.r[idx] * rainKeep);
        }
    }

    /** Drops tiles no player has been near for {@link #MEMORY} ticks. */
    public void forget(long time) {
        if (tiles.values().removeIf(t -> time - t.lastActive > MEMORY)) {
            lastTile = null;
        }
    }

    /** Forgets the cached climate normals of cells outside the tiles (call now and then: they follow the season). */
    public void clearEdgeCache() {
        edge.clear();
    }

    /**
     * Fills the edge cache for the cells bordering the tiles (two cells deep, where carry and mixing read outside),
     * until {@code deadline} (System.nanoTime). Returns whether every such cell is cached, so stepping can wait for
     * it rather than stall on cold climate lookups (perf report 2026-10-05: the first steps after logging in).
     */
    public boolean warmEdges(FieldEnv env, long deadline) {
        for (FieldTile tile : new ArrayList<>(tiles.values())) {
            int gi0 = tile.tx * SIZE;
            int gk0 = tile.tz * SIZE;
            for (int side = 0; side < 4; side++) {
                int ntx = tile.tx + (side == 0 ? 1 : side == 1 ? -1 : 0);
                int ntz = tile.tz + (side == 2 ? 1 : side == 3 ? -1 : 0);
                if (tiles.containsKey(key(ntx, ntz))) {
                    continue;
                }
                for (int depth = 0; depth < 2; depth++) {
                    for (int j = -2; j < SIZE + 2; j++) {
                        int gi = side == 0 ? gi0 + SIZE + depth : side == 1 ? gi0 - 1 - depth : gi0 + j;
                        int gk = side == 2 ? gk0 + SIZE + depth : side == 3 ? gk0 - 1 - depth : gk0 + j;
                        if (tiles.containsKey(key(Math.floorDiv(gi, SIZE), Math.floorDiv(gk, SIZE)))) {
                            continue;
                        }
                        if (!edge.containsKey(key(gi, gk))) {
                            if (System.nanoTime() >= deadline) {
                                return false;
                            }
                            cell(Var.T, gi, gk, env);
                        }
                    }
                }
            }
        }
        return true;
    }

    /** Steps every tile {@code dt} ticks (tests, spin-up). */
    public void step(long dt, FieldEnv env) {
        for (FieldTile tile : new ArrayList<>(tiles.values())) {
            stepTile(tile, dt, env);
        }
    }

    /** The tiles active since {@code since} (inside a player's zone then or later). */
    public List<FieldTile> active(long since) {
        List<FieldTile> out = new ArrayList<>();
        for (FieldTile tile : tiles.values()) {
            if (tile.lastActive >= since) {
                out.add(tile);
            }
        }
        return out;
    }

    /** Steps one tile {@code dt} ticks, reading its neighbours as they are now. */
    public void stepTile(FieldTile tile, long dt, FieldEnv world) {
        FieldEnv env = world.forArea(tile.cellX(0) - CELL, tile.cellZ(0) - CELL,
                tile.cellX(SIZE - 1) + CELL, tile.cellZ(SIZE - 1) + CELL);
        double seconds = dt / 20.0;
        int n = SIZE * SIZE;
        float[] t = new float[n];
        float[] a = new float[n];
        float[] q = new float[n];
        float[] p = new float[n];
        float[] r = new float[n];
        double s = ADVECTION * seconds;
        for (int k = 0; k < SIZE; k++) {
            for (int i = 0; i < SIZE; i++) {
                int idx = k * SIZE + i;
                double cx = tile.cellX(i);
                double cz = tile.cellZ(k);
                double normal = tile.base[idx];
                double biomeHumidity = tile.humidity[idx];
                int surface = tile.surface[idx];
                double[] w = env.wind(cx, cz);
                double tDep = sample(Var.T, cx - w[0] * s, cz - w[1] * s, env);
                double aDep = sample(Var.A, cx - w[2] * s, cz - w[3] * s, env);
                double qDep = sample(Var.Q, cx - (w[0] + w[2]) / 2 * s, cz - (w[1] + w[3]) / 2 * s, env);
                tDep = tDep * (1 - MIX) + MIX * neighbours(Var.T, cx, cz, env);
                qDep = qDep * (1 - MIX) + MIX * neighbours(Var.Q, cx, cz, env);

                double[] contact = env.contact(cx, cz);
                double push = Math.min(1, contact[1] * dt / TAU_CONTACT);
                double tNew = tDep + (normal + contact[0] - tDep) * push;
                tNew += (normal - tNew) * Math.min(1, dt / (surface == 2 ? TAU_WATER : TAU_LAND));
                double aNormal = normal + ALOFT_OFFSET;
                double aNew = aDep + (aNormal + 0.6 * contact[0] - aDep) * push;
                aNew += (aNormal - aNew) * Math.min(1, dt / TAU_ALOFT);

                double cap = Moisture.capacity(tNew + env.heightCorrection(tile.elevationOr0(idx)));
                double eq = cap * Moisture.targetHumidity(surface, biomeHumidity);
                double qNew = qDep;
                if (qNew < eq) {
                    qNew += (eq - qNew) * Math.min(1, dt / Moisture.evaporationTicks(surface));
                }
                double excess = Math.max(0, qNew - cap);
                qNew -= excess;

                t[idx] = (float) tNew;
                a[idx] = (float) aNew;
                q[idx] = (float) Math.max(0, qNew);
                p[idx] = (float) (tile.p[idx] * Math.exp(-dt / TAU_PRECIP) + excess);
                r[idx] = (float) (tile.r[idx] * Math.exp(-dt / TAU_PRECIP));
            }
        }
        tile.t = t;
        tile.a = a;
        tile.q = q;
        tile.p = p;
        tile.r = r;
    }

    /**
     * Rain falling at (x, z) (phase 4b, Bright 2026-10-06: realistic): {@code mm} of water leaves the air of the cell
     * there and is recorded as fallen rain. Returns what was actually taken (the air can't give more than it holds).
     */
    public double drain(double x, double z, double mm) {
        int gi = Math.floorDiv((int) Math.floor(x), CELL);
        int gk = Math.floorDiv((int) Math.floor(z), CELL);
        FieldTile tile = tiles.get(key(Math.floorDiv(gi, SIZE), Math.floorDiv(gk, SIZE)));
        if (tile == null || !(mm > 0)) {
            return 0;
        }
        int idx = Math.floorMod(gk, SIZE) * SIZE + Math.floorMod(gi, SIZE);
        double taken = Math.min(mm, Math.max(0, tile.q[idx] - 0.2));
        tile.q[idx] -= (float) taken;
        tile.r[idx] += (float) taken;
        return taken;
    }

    // ---- reading -----------------------------------------------------------------------------------------------

    /** What a cell holds. */
    public enum Var { T, A, Q, P, R, ANOMALY }

    /** {@code v} at (x, z), bilinear between cell centres; outside the tiles, the climate's normal. */
    public double sample(Var v, double x, double z, FieldEnv env) {
        double gx = x / CELL - 0.5;
        double gz = z / CELL - 0.5;
        int i0 = (int) Math.floor(gx);
        int k0 = (int) Math.floor(gz);
        double fx = gx - i0;
        double fz = gz - k0;
        double v00 = cell(v, i0, k0, env);
        double v10 = cell(v, i0 + 1, k0, env);
        double v01 = cell(v, i0, k0 + 1, env);
        double v11 = cell(v, i0 + 1, k0 + 1, env);
        return (v00 * (1 - fx) + v10 * fx) * (1 - fz) + (v01 * (1 - fx) + v11 * fx) * fz;
    }

    /** Whether (x, z) is inside a simulated tile. */
    public boolean covers(double x, double z) {
        return tiles.containsKey(key(Math.floorDiv((int) Math.floor(x), TILE), Math.floorDiv((int) Math.floor(z), TILE)));
    }

    /**
     * The field cell containing (x, z), unblended: {t, a, q, p, base, humidity, surface, elevation (0 while unknown)};
     * null outside the tiles. (The cloud diagnostics read whole cells.)
     */
    public double[] cellAt(double x, double z) {
        int gi = Math.floorDiv((int) Math.floor(x), CELL);
        int gk = Math.floorDiv((int) Math.floor(z), CELL);
        FieldTile tile = tiles.get(key(Math.floorDiv(gi, SIZE), Math.floorDiv(gk, SIZE)));
        if (tile == null) {
            return null;
        }
        int idx = Math.floorMod(gk, SIZE) * SIZE + Math.floorMod(gi, SIZE);
        return new double[]{tile.t[idx], tile.a[idx], tile.q[idx], tile.p[idx], tile.base[idx], tile.humidity[idx],
                tile.surface[idx], tile.elevationOr0(idx)};
    }

    private double neighbours(Var v, double cx, double cz, FieldEnv env) {
        return (sample(v, cx + CELL, cz, env) + sample(v, cx - CELL, cz, env) + sample(v, cx, cz + CELL, env)
                + sample(v, cx, cz - CELL, env)) / 4;
    }

    /** Cell (gi, gk)'s value (global cell indices). */
    private double cell(Var v, int gi, int gk, FieldEnv env) {
        int tx = Math.floorDiv(gi, SIZE);
        int tz = Math.floorDiv(gk, SIZE);
        FieldTile tile = lastTile;
        if (tile == null || tile.tx != tx || tile.tz != tz) {
            tile = tiles.get(key(tx, tz));
            if (tile != null) {
                lastTile = tile;
            }
        }
        if (tile == null) {
            if (v == Var.P || v == Var.R || v == Var.ANOMALY) {
                return 0;
            }
            long cellKey = key(gi, gk);
            double[] e = edge.get(cellKey);
            if (e == null) {
                double[] c = env.climate((gi + 0.5) * CELL, (gk + 0.5) * CELL);
                e = new double[]{c[0], c[0] + ALOFT_OFFSET, Moisture.capacity(c[0]) * Moisture.targetHumidity((int) c[2], c[1])};
                edge.put(cellKey, e);
            }
            return switch (v) {
                case T -> e[0];
                case A -> e[1];
                default -> e[2];
            };
        }
        int idx = Math.floorMod(gk, SIZE) * SIZE + Math.floorMod(gi, SIZE);
        return switch (v) {
            case T -> tile.t[idx];
            case A -> tile.a[idx];
            case Q -> tile.q[idx];
            case P -> tile.p[idx];
            case R -> tile.r[idx];
            case ANOMALY -> tile.t[idx] - tile.base[idx];
        };
    }
}
