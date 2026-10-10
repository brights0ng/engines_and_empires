package dev.brights0ng.enginesandempires.weather.sim.world;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.SplittableRandom;
import java.util.WeakHashMap;

import dev.brights0ng.enginesandempires.EnginesAndEmpiresMod;
import dev.brights0ng.enginesandempires.oregen.TerrainProbe;
import dev.brights0ng.enginesandempires.oregen.worldgen.NoiseTerrainProbe;
import dev.brights0ng.enginesandempires.weather.climate.Climate;
import dev.brights0ng.enginesandempires.weather.climate.ClimateCurves;
import dev.brights0ng.enginesandempires.weather.climate.SeasonSource;
import dev.brights0ng.enginesandempires.weather.climate.Temperature;
import dev.brights0ng.enginesandempires.weather.field.AirMassContact;
import dev.brights0ng.enginesandempires.weather.field.AtmosphereField;
import dev.brights0ng.enginesandempires.weather.field.FieldEnv;
import dev.brights0ng.enginesandempires.weather.field.FieldTile;
import dev.brights0ng.enginesandempires.weather.field.TerrainHeights;
import dev.brights0ng.enginesandempires.weather.rain.WeatherConfig;
import dev.brights0ng.enginesandempires.weather.sim.JetStream;
import dev.brights0ng.enginesandempires.weather.sim.PressureField;
import dev.brights0ng.enginesandempires.weather.sim.SimMath;
import dev.brights0ng.enginesandempires.weather.sim.SimParams;
import dev.brights0ng.enginesandempires.weather.sim.SystemsSim;
import dev.brights0ng.enginesandempires.weather.sim.WeatherSystem;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.LevelTickEvent;

/**
 * The weather systems running in the Overworld (phase 2 of {@code claude/weather-backbone-plan.md}): steps
 * {@link SystemsSim} every {@value #STEP} ticks around the players, keeps track of which areas have had weather, and
 * saves it all ({@link WeatherSimData}).
 *
 * <ul>
 *   <li><b>Spin-up</b> (Bright, 2026-10-05): the first time a world runs this (new worlds and worlds from before it),
 *       the simulation is run four in-game days ahead of now in one go, around the players or the world spawn, so
 *       storms are already spread out and mid-life rather than all born at once.</li>
 *   <li><b>Simulation time</b> is the game time plus an offset that {@code /eae weather step} moves forward.</li>
 *   <li><b>The atmosphere field</b> (phase 3) steps right after the systems, in tiles around the players; terrain
 *       heights for new cells are worked out a few milliseconds per tick from the world's terrain noise.</li>
 * </ul>
 */
@EventBusSubscriber(modid = EnginesAndEmpiresMod.MODID)
public final class WeatherSim {

    public static final int STEP = 100;
    /** Coverage cells, blocks. */
    static final int CELL = 8192;
    /** How long an area counts as having had weather after a player leaves, ticks (3 in-game days). */
    static final long COVERAGE_MEMORY = 72_000;
    static final int SPIN_UP_DAYS = 4;
    static final int COARSE_STEP = 1000;
    /** Time each tick may spend working out terrain heights for new field cells, nanoseconds. */
    static final long TERRAIN_BUDGET = 3_000_000;
    /** Stored cell normals refreshed per tick at most (a player's area, 4096 cells, in about 13 seconds). */
    static final int REFRESH_PER_TICK = 16;

    private static final Map<ServerLevel, WeatherSim> SIMS = new WeakHashMap<>();

    private final ServerLevel level;
    private final WeatherSimData data;
    private SimParams params;
    private SystemsSim sim;
    private TerrainProbe terrain;
    /** How long the last live step took, nanoseconds (systems and field). */
    private long lastStepNanos;
    private int stepsLogged;
    /** Whether a step is under way (biome lookups can run queued server tasks mid-step; never step twice at once). */
    private boolean stepping;
    /** Field tiles waiting to be built, nearest first. */
    private final java.util.LinkedHashSet<Long> pendingTiles = new java.util.LinkedHashSet<>();
    /** The tile being built, and its next cell. */
    private FieldTile building;
    private int buildingNext;
    /** Where the round-robin refresh of stored cell normals is: tile index into a snapshot, and cell. */
    private List<FieldTile> refreshTiles = List.of();
    private int refreshTile;
    private int refreshCell;
    /** The systems' pressure shapes as of the last systems step (wind and pressure reads use it). */
    private PressureField.Snapshot snapshot = PressureField.Snapshot.of(List.of());
    /** The season factor as of the last systems step. */
    private double cachedSeason;
    /** The field environment of the last systems step, for the tiles stepped over the following ticks. */
    private FieldEnv stepEnv;
    /** Active tiles still to be stepped in this {@value #STEP}-tick window. */
    private final java.util.ArrayDeque<FieldTile> tileQueue = new java.util.ArrayDeque<>();
    /** Field time spent in the current and last windows (total, and the most in one tick), nanoseconds. */
    private long windowNanos;
    private long windowMaxNanos;
    private long lastWindowNanos;
    private long lastWindowMaxNanos;
    private int windowsLogged;
    /** Whether the cells around the field have their climate normals cached (stepping waits until they do). */
    private boolean edgesWarm;

    private WeatherSim(ServerLevel level) {
        this.level = level;
        this.data = level.getDataStorage().computeIfAbsent(WeatherSimData.factory(), WeatherSimData.NAME);
        rebuild();
    }

    /** The Overworld's simulation, or null for other levels. */
    public static WeatherSim of(ServerLevel level) {
        if (level == null || !Level.OVERWORLD.equals(level.dimension())) {
            return null;
        }
        WeatherSim s = SIMS.get(level);
        if (s == null) {
            s = new WeatherSim(level);
            SIMS.put(level, s);
        }
        return s;
    }

    @SubscribeEvent
    static void onLevelTick(LevelTickEvent.Post event) {
        if (!(event.getLevel() instanceof ServerLevel level) || !Level.OVERWORLD.equals(level.dimension())) {
            return;
        }
        WeatherSim s = of(level);
        if (s == null) {
            return;
        }
        if (level.getGameTime() % STEP == 0) {
            s.tick();
        }
        long start = System.nanoTime();
        s.buildTiles(start, TERRAIN_BUDGET);
        s.warmEdges(start, TERRAIN_BUDGET);
        s.stepQueuedTiles(level.getGameTime());
        s.workOutTerrain(start, TERRAIN_BUDGET);
        s.refreshNormals(start, TERRAIN_BUDGET);
    }

    /** Warms the field's edge cache within the tick's budget. */
    private void warmEdges(long start, long budget) {
        if (System.nanoTime() - start >= budget || stepEnv == null || stepping) {
            return;
        }
        stepping = true;
        try {
            edgesWarm = data.field.warmEdges(stepEnv, start + budget);
        } finally {
            stepping = false;
        }
    }

    private void tick() {
        if (!data.spunUp) {
            spinUp();
        }
        lastWindowNanos = windowNanos;
        lastWindowMaxNanos = windowMaxNanos;
        windowNanos = 0;
        windowMaxNanos = 0;
        // Report the cost of the first few live windows with a field, once per session.
        if (stepsLogged > 0 && windowsLogged < 3 && lastWindowNanos > 0) {
            windowsLogged++;
            EnginesAndEmpiresMod.LOGGER.info("Weather: field took {} ms over the last {} ticks (at most {} ms in one "
                            + "tick; {} active tiles)", String.format(java.util.Locale.ROOT, "%.1f", lastWindowNanos / 1e6),
                    STEP, String.format(java.util.Locale.ROOT, "%.2f", lastWindowMaxNanos / 1e6),
                    data.field.active(time() - STEP).size());
        }
        step(time(), STEP, true);
        // Report the cost of the first few live steps with a field, once per session.
        if (stepsLogged < 3 && !data.field.tiles().isEmpty()) {
            stepsLogged++;
            EnginesAndEmpiresMod.LOGGER.info("Weather: live systems step {} took {} ms ({} systems, {} field tiles, {} "
                            + "terrain cells still to work out)", stepsLogged, String.format(java.util.Locale.ROOT, "%.2f",
                            lastStepMillis()), data.systems.size(), data.field.tiles().size(), terrainPending());
        }
    }

    /** Makes sure the pure simulation matches the current config (rebuilt only when it changes). */
    private void rebuild() {
        SimParams now = WeatherConfig.simParams();
        if (sim == null || !now.equals(params)) {
            params = now;
            sim = new SystemsSim(new JetStream(params, level.getSeed()), level.getSeed(), data.systems, data.nextId);
        }
    }

    private void step(long time, long dt, boolean live) {
        if (stepping) {
            return;
        }
        stepping = true;
        try {
            stepNow(time, dt, live);
        } finally {
            stepping = false;
        }
    }

    private void stepNow(long time, long dt, boolean live) {
        long started = System.nanoTime();
        rebuild();
        sim.setDrift(WeatherConfig.drift());
        List<SystemsSim.Anchor> anchors = anchors();
        for (SystemsSim.Anchor a : anchors) {
            markCovered(a, time);
        }
        SplittableRandom nudges = live ? new SplittableRandom(SimMath.hash(level.getSeed(), 0x11D6EL, time)) : null;
        double season = season();
        sim.step(time, dt, season, anchors, (x, z) -> covered(x, z, time), nudges);
        data.nextId = sim.nextId();
        snapshot = PressureField.Snapshot.of(data.systems);
        cachedSeason = season;

        FieldEnv env = env(time, season);
        for (SystemsSim.Anchor a : anchors) {
            if (live) {
                // Live: new tiles are built a few milliseconds per tick (asking for many biomes at once stalls).
                pendingTiles.addAll(data.field.touch(a.x(), a.z(), params.zoneRadius(), time, dt));
            } else {
                data.field.ensure(a.x(), a.z(), params.zoneRadius(), env, time);
            }
        }
        // Only tiles near players are stepped (perf report 2026-10-05); live, they are spread over the next ticks.
        List<FieldTile> active = data.field.active(time);
        if (live && dt == STEP) {
            tileQueue.clear();
            tileQueue.addAll(active);
            stepEnv = env;
        } else {
            for (FieldTile tile : active) {
                data.field.stepTile(tile, dt, env);
                tile.lastStepped = time;
            }
        }
        data.field.forget(time);
        data.setDirty();
        if (live) {
            lastStepNanos = System.nanoTime() - started;
        }
    }

    /** Steps this tick's share of the queued tiles, so the window's tiles are spread evenly over its ticks. */
    private void stepQueuedTiles(long gameTime) {
        if (tileQueue.isEmpty() || stepEnv == null || stepping || !edgesWarm) {
            return;
        }
        stepping = true;
        long started = System.nanoTime();
        try {
            int ticksLeft = (int) (STEP - Math.floorMod(gameTime, (long) STEP));
            int count = (tileQueue.size() + ticksLeft - 1) / Math.max(1, ticksLeft);
            long time = time();
            for (int i = 0; i < count && !tileQueue.isEmpty(); i++) {
                FieldTile tile = tileQueue.poll();
                if (data.field.tile(tile.tx, tile.tz) != tile) {
                    continue;
                }
                long dt = tile.lastStepped <= 0 ? STEP : Math.max(1, Math.min(2L * STEP, time - tile.lastStepped));
                data.field.stepTile(tile, dt, stepEnv);
                tile.lastStepped = time;
            }
        } finally {
            stepping = false;
        }
        long spent = System.nanoTime() - started;
        windowNanos += spent;
        windowMaxNanos = Math.max(windowMaxNanos, spent);
    }

    /** What the field needs from the world, for one step. */
    private FieldEnv env(long time, double season) {
        double seconds = time / 20.0;
        JetStream jet = sim.jet();
        PressureField.Snapshot systems = PressureField.Snapshot.of(data.systems);
        AirMassContact contact = AirMassContact.prepare(data.systems, season);
        return env(systems, contact, jet, seconds, season);
    }

    /** A field environment over the given systems; {@code forArea} narrows both to the systems that can reach. */
    private FieldEnv env(PressureField.Snapshot systems, AirMassContact contact, JetStream jet, double seconds,
                         double season) {
        int sea = level.getSeaLevel();
        long seed = level.getSeed();
        return new FieldEnv() {
            @Override
            public double[] climate(double x, double z) {
                return Climate.normal(level, x, z);
            }

            @Override
            public double[] wind(double x, double z) {
                return PressureField.wind(systems, jet, x, z, seconds, season, seed);
            }

            @Override
            public double[] contact(double x, double z) {
                return contact.at(x, z);
            }

            @Override
            public double heightCorrection(double elevation) {
                return Temperature.heightCorrection(sea, sea + elevation);
            }

            @Override
            public FieldEnv forArea(double minX, double minZ, double maxX, double maxZ) {
                return env(systems.near(minX, minZ, maxX, maxZ), contact.near(minX, minZ, maxX, maxZ), jet, seconds,
                        season);
            }
        };
    }

    /**
     * Refreshes the field cells' stored normals round-robin, until {@code budget} nanoseconds after {@code start}. The
     * normals change only with the season, so a full pass every few minutes is plenty.
     */
    private void refreshNormals(long start, long budget) {
        if (System.nanoTime() - start >= budget || data.field.tiles().isEmpty()) {
            return;
        }
        FieldEnv env = null;
        int cells = AtmosphereField.SIZE * AtmosphereField.SIZE;
        for (int done = 0; done < REFRESH_PER_TICK && System.nanoTime() - start < budget; done++) {
            if (refreshTile >= refreshTiles.size()) {
                // A full pass done: cells outside the tiles get their normals afresh too (they follow the season).
                data.field.clearEdgeCache();
                refreshTiles = new ArrayList<>(data.field.tiles().values());
                refreshTile = 0;
                refreshCell = 0;
                if (refreshTiles.isEmpty()) {
                    return;
                }
            }
            if (env == null) {
                env = readEnv();
            }
            AtmosphereField.refreshCell(refreshTiles.get(refreshTile), refreshCell, env);
            if (++refreshCell >= cells) {
                refreshCell = 0;
                refreshTile++;
            }
        }
    }

    /** Builds waiting field tiles at the climate's normal, until {@code budget} nanoseconds after {@code start}. */
    private void buildTiles(long start, long budget) {
        if (building == null && pendingTiles.isEmpty()) {
            return;
        }
        FieldEnv env = null;
        while (System.nanoTime() - start < budget) {
            if (building == null) {
                java.util.Iterator<Long> it = pendingTiles.iterator();
                if (!it.hasNext()) {
                    return;
                }
                long key = it.next();
                it.remove();
                int tx = (int) (key >> 32);
                int tz = (int) key;
                if (data.field.tile(tx, tz) != null) {
                    continue;
                }
                building = new FieldTile(tx, tz);
                buildingNext = 0;
            }
            if (env == null) {
                env = readEnv();
            }
            AtmosphereField.initCell(building, buildingNext++, env);
            if (buildingNext >= AtmosphereField.SIZE * AtmosphereField.SIZE) {
                building.lastActive = time();
                data.field.put(building);
                building = null;
                data.setDirty();
            }
        }
    }

    /** Works out terrain heights for field cells without one, until {@code budget} nanoseconds after {@code start}. */
    private void workOutTerrain(long start, long budget) {
        if (System.nanoTime() - start >= budget) {
            return;
        }
        if (terrain == null) {
            terrain = NoiseTerrainProbe.forLevel(level);
        }
        int minY = level.getMinBuildHeight();
        int maxY = level.getMaxBuildHeight() - 1;
        int sea = level.getSeaLevel();
        for (FieldTile tile : new ArrayList<>(data.field.tiles().values())) {
            for (int idx = 0; idx < tile.elevation.length; idx++) {
                if (!Float.isNaN(tile.elevation[idx])) {
                    continue;
                }
                int i = idx % AtmosphereField.SIZE;
                int k = idx / AtmosphereField.SIZE;
                tile.elevation[idx] = (float) TerrainHeights.elevation(terrain, (int) Math.floor(tile.cellX(i)),
                        (int) Math.floor(tile.cellZ(k)), minY, maxY, sea);
                if (System.nanoTime() - start > budget) {
                    return;
                }
            }
        }
    }

    private void spinUp() {
        long now = time();
        long t = now - SPIN_UP_DAYS * 24000L;
        int steps = SPIN_UP_DAYS * 24000 / COARSE_STEP;
        long started = System.nanoTime();
        for (int i = 1; i <= steps; i++) {
            step(t + (long) i * COARSE_STEP, COARSE_STEP, false);
        }
        data.spunUp = true;
        EnginesAndEmpiresMod.LOGGER.info("Weather: spun up {} in-game days of weather ({} systems, {} field tiles) in {} ms",
                SPIN_UP_DAYS, data.systems.size(), data.field.tiles().size(), (System.nanoTime() - started) / 1_000_000);
    }

    /** Runs the simulation {@code ticks} ahead of the game clock ({@code /eae weather step}). */
    public void advance(long ticks) {
        long done = 0;
        while (done < ticks) {
            long dt = Math.min(COARSE_STEP, ticks - done);
            data.offset += dt;
            step(time(), dt, true);
            done += dt;
            // The forecast scorer (phase 7c) reads the truth at every hour of the jump.
            dev.brights0ng.enginesandempires.weather.forecast.ForecastScore.hour(level);
        }
        // The sky starts afresh from the new weather (warm, at the next cloud pass).
        CloudWorld.clear(level);
        // Forecasts made before the jump describe weather that has now happened.
        dev.brights0ng.enginesandempires.weather.forecast.ForecastService.invalidate(level);
    }

    /** Players' positions in the Overworld; the world spawn if there are none during spin-up. */
    private List<SystemsSim.Anchor> anchors() {
        List<SystemsSim.Anchor> out = new ArrayList<>();
        for (ServerPlayer p : level.players()) {
            out.add(new SystemsSim.Anchor(p.getX(), p.getZ()));
        }
        if (out.isEmpty() && !data.spunUp) {
            BlockPos spawn = level.getSharedSpawnPos();
            out.add(new SystemsSim.Anchor(spawn.getX(), spawn.getZ()));
        }
        if (out.isEmpty() && data.systems.isEmpty()) {
            BlockPos spawn = level.getSharedSpawnPos();
            out.add(new SystemsSim.Anchor(spawn.getX(), spawn.getZ()));
        }
        return out;
    }

    private void markCovered(SystemsSim.Anchor a, long time) {
        int r = (int) Math.ceil(params.zoneRadius() / CELL);
        int cx = Math.floorDiv((int) Math.floor(a.x()), CELL);
        int cz = Math.floorDiv((int) Math.floor(a.z()), CELL);
        for (int dz = -r; dz <= r; dz++) {
            for (int dx = -r; dx <= r; dx++) {
                data.cells.put(key(cx + dx, cz + dz), time);
            }
        }
        if (data.cells.size() > 50_000) {
            data.cells.values().removeIf(t -> time - t > COVERAGE_MEMORY);
        }
    }

    private boolean covered(double x, double z, long time) {
        Long last = data.cells.get(key(Math.floorDiv((int) Math.floor(x), CELL), Math.floorDiv((int) Math.floor(z), CELL)));
        return last != null && time - last <= COVERAGE_MEMORY;
    }

    private static long key(int cx, int cz) {
        return ((long) cx << 32) | (cz & 0xFFFFFFFFL);
    }

    // ---- reading ---------------------------------------------------------------------------------------------------

    /** The simulation time, ticks. */
    public long time() {
        return level.getGameTime() + data.offset;
    }

    public double seconds() {
        return time() / 20.0;
    }

    /** The season factor, -1 midwinter to +1 midsummer (0 without seasons). */
    public double season() {
        return ClimateCurves.season(SeasonSource.yearFraction(level));
    }

    public List<WeatherSystem> systems() {
        return data.systems;
    }

    /** The jet (settings changes take effect at the next systems step). */
    public JetStream jet() {
        return sim.jet();
    }

    /** The systems' pressure shapes as of the last systems step. */
    public PressureField.Snapshot snapshot() {
        return snapshot;
    }

    /** The season factor as of the last systems step (cheap; {@link #season()} asks Serene Seasons). */
    public double cachedSeason() {
        return cachedSeason;
    }

    /** Field time over the last {@value #STEP}-tick window, milliseconds: {total, most in one tick}. */
    public double[] fieldWindowMillis() {
        return new double[]{lastWindowNanos / 1e6, lastWindowMaxNanos / 1e6};
    }

    public long seed() {
        return level.getSeed();
    }

    /** The atmosphere field. */
    public AtmosphereField field() {
        return data.field;
    }

    /**
     * A forecast copy of the weather systems (phase 7b): as they are now, with the current drift settings, rolling
     * the make-up of systems that form during the forecast from {@code salt}.
     */
    public SystemsSim forecastSystems(long salt) {
        rebuild();
        sim.setDrift(WeatherConfig.drift());
        return sim.forForecast(salt);
    }

    /** The clouds (phase 4a). */
    public dev.brights0ng.enginesandempires.weather.cloud.sim.CloudSim cloudSim() {
        return data.clouds;
    }

    /** Marks the saved weather as changed. */
    public void markDirty() {
        data.setDirty();
    }

    /** A field env for reading (the map, commands), at the current time. */
    public FieldEnv readEnv() {
        rebuild();
        return env(time(), season());
    }

    /** The air-mass anomaly at (x, z), C: the field's departure from the normal (0 outside the field). */
    public double anomaly(double x, double z) {
        AtmosphereField f = data.field;
        if (!f.covers(x, z)) {
            return 0;
        }
        return f.sample(AtmosphereField.Var.ANOMALY, x, z, NO_ENV);
    }

    /** Warm-nose readings by 128-block cell, refreshed every {@value #NOSE_TICKS} ticks. */
    private final Map<Long, double[]> noses = new java.util.HashMap<>();
    private long nosesTick = Long.MIN_VALUE;
    static final int NOSE_TICKS = 100;

    /**
     * The warm layer aloft at (x, z) (phase 5a): {melt index, cold layer depth} ({@link
     * dev.brights0ng.enginesandempires.weather.field.WarmNose}). Worked out per 128-block cell and kept for
     * {@value #NOSE_TICKS} ticks: it changes over hundreds of blocks and many minutes.
     */
    public synchronized double[] warmNose(double x, double z) {
        long now = level.getGameTime();
        if (now - nosesTick >= NOSE_TICKS || noses.size() > 8192) {
            noses.clear();
            nosesTick = now;
        }
        int ci = Math.floorDiv((int) Math.floor(x), 128);
        int ck = Math.floorDiv((int) Math.floor(z), 128);
        long key = ((long) ci << 32) ^ (ck & 0xFFFFFFFFL);
        double[] v = noses.get(key);
        if (v == null) {
            v = dev.brights0ng.enginesandempires.weather.field.WarmNose.at(data.systems, ci * 128 + 64, ck * 128 + 64,
                    (px, pz) -> Temperature.atSeaLevel(level, (int) Math.floor(px), (int) Math.floor(pz)));
            noses.put(key, v);
        }
        return v;
    }

    /** How long the last live step took, milliseconds. */
    public double lastStepMillis() {
        return lastStepNanos / 1e6;
    }

    /** Terrain cells still to be worked out. */
    public int terrainPending() {
        int n = 0;
        for (FieldTile tile : data.field.tiles().values()) {
            for (float e : tile.elevation) {
                if (Float.isNaN(e)) {
                    n++;
                }
            }
        }
        return n;
    }

    /** For anomaly reads: cells outside the tiles count as normal (anomaly 0), so the climate is never needed. */
    private static final FieldEnv NO_ENV = new FieldEnv() {
        @Override
        public double[] climate(double x, double z) {
            return new double[]{0, 0.5, 0};
        }

        @Override
        public double[] wind(double x, double z) {
            return new double[4];
        }

        @Override
        public double[] contact(double x, double z) {
            return new double[2];
        }

        @Override
        public double heightCorrection(double elevation) {
            return 0;
        }
    };

    // ---- debug -----------------------------------------------------------------------------------------------------

    /** Adds a low or high at (x, z), a third of the way through a typical life. */
    public WeatherSystem spawn(WeatherSystem.Kind kind, double x, double z) {
        rebuild();
        JetStream jet = sim.jet();
        int track = jet.nearestTrack(z);
        long lifetime = (kind == WeatherSystem.Kind.LOW ? 5 : 6) * 24000L;
        WeatherSystem s = new WeatherSystem(data.nextId++, kind, x, z, track, jet.hemisphere(track),
                (long) (lifetime * (kind == WeatherSystem.Kind.LOW ? 0.3 : 0.25)), lifetime,
                kind == WeatherSystem.Kind.LOW ? 26 : 14, kind == WeatherSystem.Kind.LOW ? 4500 : 7000, false);
        data.systems.add(s);
        sim = new SystemsSim(jet, level.getSeed(), data.systems, data.nextId);
        snapshot = PressureField.Snapshot.of(data.systems);
        data.setDirty();
        dev.brights0ng.enginesandempires.weather.forecast.ForecastService.invalidate(level);
        return s;
    }

    /** Removes every system (new ones form at the next step). */
    public void clear() {
        data.systems.clear();
        snapshot = PressureField.Snapshot.of(data.systems);
        data.setDirty();
        dev.brights0ng.enginesandempires.weather.forecast.ForecastService.invalidate(level);
    }
}
