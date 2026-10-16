package dev.brights0ng.enginesandempires.weather.forecast;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import dev.brights0ng.enginesandempires.weather.climate.Climate;
import dev.brights0ng.enginesandempires.weather.climate.SeasonSource;
import dev.brights0ng.enginesandempires.weather.debug.WeatherDebugNetwork;
import dev.brights0ng.enginesandempires.weather.rain.WeatherConfig;
import dev.brights0ng.enginesandempires.weather.sim.world.WeatherSim;
import dev.brights0ng.meteodyne.Meteodyne;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/**
 * The climatology run (phase 8a of {@code claude/weather-backbone-phase8.md}; debug only, operators): runs the real
 * weather through a year (or a number of days) and gathers each chosen spot's weather by season, for tuning the
 * seasons without playing a year.
 *
 * <h2>How it runs</h2>
 * <ul>
 *   <li>Each server tick it steps the live simulation ahead an in-game hour at a time ({@code WeatherSim.advance}, as
 *       {@code /eae weather step}) until its time budget for the tick is spent, so the server keeps ticking.</li>
 *   <li>Serene Seasons' calendar doesn't move while the weather runs ahead, so the run sets {@link SeasonSource}'s
 *       debug override to where in the year the simulation has got to, and refreshes the field's climate normals
 *       once per in-game day. The override is cleared when the run ends, is stopped, or the server stops.</li>
 *   <li>Every in-game hour each spot is read through the forecast scorer's lens ({@link ForecastRun#observeSpot}: the
 *       same 9 readings, the same temperature correction for the spot).</li>
 *   <li>While it runs, the weather is simulated around every spot as if a player stood there
 *       ({@code WeatherSim.setExtraAnchors}), so spots can be anywhere.</li>
 * </ul>
 * The world's weather ends up the run's length ahead (like {@code /eae weather step}); the game clock and Serene
 * Seasons' calendar are left alone. Nothing is saved.
 */
@EventBusSubscriber(modid = Meteodyne.MODID)
public final class Climatology {

    /** Server time each tick may spend on the run, nanoseconds. */
    static final long TICK_BUDGET = 30_000_000L;
    static final long HOUR = 1000;
    static final long DAY = 24_000;

    /** A spot to read, its ground height, and the surface biome there (for the report). */
    public record Spot(double x, double z, int y, String biome) {
    }

    /** Hears how a run goes (commands send it to chat). */
    public interface Listener {
        void progress(int percent);

        void finished(List<String> report, Path csv);
    }

    private static final List<Spot> SPOTS = new ArrayList<>();
    private static Run run;
    /**
     * Spots for a run started by itself once the server is up, from the {@code eae.climatologyAuto} system property
     * ("x,z;x,z;..."; debug: headless runs on a copy of a real world, the server stopping when it is done). An entry
     * may instead name a biome ("minecraft:desert"), which is found nearest the world's origin when the run starts
     * (8b follow-up, 2026-10-14: no hot desert turned up in two surveys of test spots).
     */
    private static List<String> autoSpots;
    /** The last run's statistics (kept after it ends, for report and csv). */
    private static List<ClimatologyStats> last = List.of();
    private static List<Spot> lastSpots = List.of();
    private static List<String> lastTrace = List.of();

    private static final class Run {
        final List<Spot> spots;
        final List<ClimatologyStats> stats = new ArrayList<>();
        final long total;
        final double startFraction;
        final long yearTicks;
        final long anchorSim;
        final long anchorDay;
        final Listener listener;
        /** Hourly diagnosis rows per spot (debug trace, written beside the daily CSV). */
        final List<String> trace = new ArrayList<>();
        long done;
        int reported;

        Run(List<Spot> spots, long total, double startFraction, long yearTicks, long anchorSim, long anchorDay,
            Listener listener) {
            this.spots = spots;
            this.total = total;
            this.startFraction = startFraction;
            this.yearTicks = yearTicks;
            this.anchorSim = anchorSim;
            this.anchorDay = anchorDay;
            this.listener = listener;
            for (int i = 0; i < spots.size(); i++) {
                stats.add(new ClimatologyStats());
            }
        }

        double fraction() {
            return startFraction + (double) done / yearTicks;
        }
    }

    // ---- control ---------------------------------------------------------------------------------------------------

    /**
     * Adds the spot (x, z). Its ground height is read once now, loading the chunk if needed (an unloaded chunk's
     * heightmap answers with the bottom of the world, which made far spots read as deep underground: much too warm).
     */
    public static Spot add(ServerLevel level, double x, double z) {
        int bx = (int) Math.floor(x);
        int bz = (int) Math.floor(z);
        level.getChunk(bx >> 4, bz >> 4);
        int y = level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, bx, bz);
        String biome = Climate.probeSurfaceBiome(level, bx, bz).getRegisteredName();
        Spot s = new Spot(x, z, y, biome);
        SPOTS.add(s);
        return s;
    }

    public static List<Spot> spots() {
        return List.copyOf(SPOTS);
    }

    /** Forgets every spot (a running run keeps its own). */
    public static void clearSpots() {
        SPOTS.clear();
    }

    public static boolean running() {
        return run != null;
    }

    /**
     * Starts a run of {@code days} in-game days over the current spots, from where in the year it is now; returns an
     * error message, or null if it started.
     */
    public static String start(ServerLevel level, int days, Listener listener) {
        if (run != null) {
            return "A climatology run is already going (stop it with /eae weather climatology stop).";
        }
        if (SPOTS.isEmpty()) {
            return "No spots: add one with /eae weather climatology add.";
        }
        WeatherSim sim = WeatherSim.of(level);
        if (sim == null) {
            return "The weather isn't running.";
        }
        double from = SeasonSource.yearFraction(level);
        if (!Double.isFinite(from)) {
            from = 0;
        }
        long yearTicks = SeasonSource.yearTicks(level);
        if (yearTicks <= 0) {
            yearTicks = SeasonSource.DEFAULT_YEAR_TICKS;
        }
        run = new Run(List.copyOf(SPOTS), days * DAY, from, yearTicks, sim.time(), level.getDayTime(), listener);
        SeasonSource.setOverride(from);
        List<double[]> anchors = new ArrayList<>();
        for (Spot s : run.spots) {
            anchors.add(new double[]{s.x(), s.z()});
        }
        sim.setExtraAnchors(anchors);
        sim.refreshAllNormals();
        sim.buildFieldNow();
        Meteodyne.LOGGER.info("Weather: climatology run of {} days over {} spot(s) from {}% through the year",
                days, run.spots.size(), String.format(Locale.ROOT, "%.1f", 100 * from));
        return null;
    }

    /** Stops the run now, keeping what it gathered; returns whether one was going. */
    public static boolean stop(ServerLevel level) {
        if (run == null) {
            return false;
        }
        end(level, false);
        return true;
    }

    /**
     * Steps the run up to {@code hours} in-game hours now (the tick handler calls this within its budget; tests call it
     * directly). Returns whether the run is still going afterwards.
     */
    public static boolean stepHours(ServerLevel level, int hours) {
        for (int i = 0; i < hours && run != null; i++) {
            hour(level);
        }
        return run != null;
    }

    // ---- reports ---------------------------------------------------------------------------------------------------

    /** The report lines: per spot, one line per season it has days in. */
    public static List<String> report() {
        Run r = run;
        List<ClimatologyStats> stats = r != null ? r.stats : last;
        List<Spot> spots = r != null ? r.spots : lastSpots;
        List<String> out = new ArrayList<>();
        if (r != null) {
            out.add(String.format(Locale.ROOT, "Running: %.0f%% done (day %d of %d); the season is overridden to %.1f%% "
                    + "through the year.", 100.0 * r.done / r.total, r.done / DAY, r.total / DAY, 100 * (r.fraction() % 1)));
        }
        if (stats.isEmpty()) {
            out.add("No climatology results yet.");
            return out;
        }
        for (int i = 0; i < spots.size(); i++) {
            Spot s = spots.get(i);
            ClimatologyStats st = stats.get(i);
            out.add(String.format(Locale.ROOT, "Spot %.0f, %.0f (%s, ground y %d), %d hours read:", s.x(), s.z(),
                    s.biome(), s.y(), st.hours()));
            out.add("  " + st.describeYear());
            for (ClimatologyStats.Season season : ClimatologyStats.Season.values()) {
                ClimatologyStats.Summary sum = st.summary(season);
                if (sum.days() > 0) {
                    out.add("  " + sum.describe(season));
                    out.add("    by sub-season: " + st.describeSubSeasons(season));
                }
            }
        }
        return out;
    }

    /**
     * Writes every day of every spot to {@code logs/climatology-<time>.csv}, and the hourly diagnosis trace beside it
     * ({@code climatology-trace-<time>.csv}); returns the daily file.
     */
    public static Path writeCsv(MinecraftServer server) throws IOException {
        Run r = run;
        List<ClimatologyStats> stats = r != null ? r.stats : last;
        List<Spot> spots = r != null ? r.spots : lastSpots;
        List<String> trace = r != null ? r.trace : lastTrace;
        Path dir = server.getServerDirectory().resolve("logs");
        Files.createDirectories(dir);
        long stamp = System.currentTimeMillis();
        Path file = dir.resolve("climatology-" + stamp + ".csv");
        List<String> lines = new ArrayList<>();
        lines.add("spot_x,spot_z,biome," + ClimatologyStats.Day.CSV_HEADER);
        for (int i = 0; i < spots.size(); i++) {
            Spot s = spots.get(i);
            String prefix = String.format(Locale.ROOT, "%.0f,%.0f,%s,", s.x(), s.z(), s.biome());
            for (ClimatologyStats.Day d : stats.get(i).days()) {
                lines.add(prefix + d.csv());
            }
        }
        Files.write(file, lines, StandardCharsets.UTF_8);
        if (!trace.isEmpty()) {
            List<String> t = new ArrayList<>(trace.size() + 1);
            t.add(TRACE_HEADER);
            t.addAll(trace);
            Files.write(dir.resolve("climatology-trace-" + stamp + ".csv"), t, StandardCharsets.UTF_8);
        }
        return file;
    }

    // ---- running ---------------------------------------------------------------------------------------------------

    @SubscribeEvent
    static void onServerTick(ServerTickEvent.Post event) {
        if (autoSpots != null && event.getServer().getTickCount() > 200) {
            startAuto(event.getServer());
        }
        if (run == null) {
            return;
        }
        ServerLevel level = event.getServer().overworld();
        long started = System.nanoTime();
        try {
            while (run != null && System.nanoTime() - started < TICK_BUDGET) {
                hour(level);
            }
        } catch (RuntimeException e) {
            Meteodyne.LOGGER.warn("Weather: the climatology run failed; stopping it", e);
            end(level, false);
        }
    }

    /** One in-game hour of the run: the season moves on, the weather steps, every spot is read. */
    private static void hour(ServerLevel level) {
        Run r = run;
        WeatherSim sim = WeatherSim.of(level);
        if (r == null || sim == null) {
            return;
        }
        if (r.done % DAY == 0) {
            // The season has moved a day on: the field's stored climate normals follow it.
            SeasonSource.setOverride(r.fraction());
            sim.refreshAllNormals();
        }
        sim.advance(HOUR);
        sim.buildFieldNow();
        r.done += HOUR;
        SeasonSource.setOverride(r.fraction());
        long now = sim.time();
        long dayTime = r.anchorDay + (now - r.anchorSim);
        ForecastSettings st = WeatherConfig.forecast();
        for (int i = 0; i < r.spots.size(); i++) {
            Spot s = r.spots.get(i);
            ClimatologyStats.Hour h = read(level, sim, s, now, dayTime, r.fraction(), st, r.trace, i);
            r.stats.get(i).add(h);
        }
        int percent = (int) (100 * r.done / r.total);
        if (percent / 10 > r.reported / 10) {
            r.reported = percent;
            if (r.listener != null && percent < 100) {
                r.listener.progress(percent);
            }
        }
        if (r.done >= r.total) {
            end(level, true);
        }
    }

    /**
     * The spot's reading this hour, through the forecast scorer's lens; adds the diagnosis at the spot itself to
     * {@code trace} (debug) when it isn't null.
     */
    static ClimatologyStats.Hour read(ServerLevel level, WeatherSim sim, Spot spot, long now, long dayTime,
                                      double yearFraction, ForecastSettings st, List<String> trace, int index) {
        double offset = ForecastService.offset(level, sim, spot.x(), spot.y(), spot.z(), st);
        ForecastService.Key key = ForecastService.Key.of(Forecast.Product.TODAY, spot.x(), spot.z());
        ForecastSnapshot s = ForecastService.snapshot(level, sim, key,
                ForecastSnapshot.truthDomain(Forecast.Product.TODAY), 0, dayTime, st);
        ForecastRun.Observation o = ForecastRun.observeSpot(s, now, spot.x(), spot.z());
        // The wind at the spot itself (the observation's is at its forecast region's centre, shared by nearby spots).
        double[] w = dev.brights0ng.enginesandempires.weather.sim.PressureField.wind(sim.snapshot(), sim.jet(),
                spot.x(), spot.z(), now / 20.0, sim.cachedSeason(), sim.seed());
        ClimatologyStats.Hour h = ClimatologyStats.hourOf(o.points(), offset, w[0], w[1],
                Math.floorDiv(dayTime + 6000, DAY), yearFraction - Math.floor(yearFraction));
        h = h.withHeard(ForecastRun.stormsWithin(s, now, spot.x(), spot.z(), HEARD_RADIUS, HEARD_SPACING));
        if (trace != null) {
            trace.add(traceRow(index, s, now, dayTime, h, sim));
        }
        return h;
    }

    /**
     * How far thunder counts as heard for the climatology, blocks: the live thunder's "far" layer (low thunder with a
     * roll; {@code ThunderPlan.FAR}), beyond which only a faint rumble plays (8b follow-up, 2026-10-14).
     */
    static final double HEARD_RADIUS = dev.brights0ng.enginesandempires.weather.lightning.ThunderPlan.FAR;
    /** Spacing of the readings over the earshot disc, blocks (~29 readings). */
    static final double HEARD_SPACING = 600;

    static final String TRACE_HEADER = "spot,day,clock,year_pct,season_factor,in_field,ground_T,field_T,aloft_T,water_mm,"
            + "rh,lcl_m,surface,biome_humidity,instability,heap,heap_cover,frontal,front,warm_ahead,convergence,"
            + "subsidence,wrung,orographic,ns,as,st,sc,cs,ci,ns_src,wet,kind,low_dist,low_radius,low_hPa,low_stage,"
            + "lows_4k,high_dist,high_hPa,systems_total,low_dz,track_dz";

    /** One hour of the trace: the lens's diagnosis at the spot, and the nearest low and high. */
    private static String traceRow(int index, ForecastSnapshot s, long now, long dayTime, ClimatologyStats.Hour h,
                                   WeatherSim sim) {
        Spot spot = run == null ? null : run.spots.get(index);
        double x = spot == null ? s.x() : spot.x();
        double z = spot == null ? s.z() : spot.z();
        ForecastRun.Diagnosis d = ForecastRun.diagnose(s, now, x, z);
        dev.brights0ng.enginesandempires.weather.cloud.sim.CloudDiagnostics.Need n = d.need();
        dev.brights0ng.enginesandempires.weather.cloud.sim.CloudDiagnostics.Air a = d.air();
        dev.brights0ng.enginesandempires.weather.sim.WeatherSystem low = null;
        dev.brights0ng.enginesandempires.weather.sim.WeatherSystem high = null;
        double lowD = Double.POSITIVE_INFINITY;
        double highD = Double.POSITIVE_INFINITY;
        int lows4k = 0;
        for (var sys : s.systems().systems()) {
            double dist = Math.hypot(sys.x - x, sys.z - z);
            if (sys.kind == dev.brights0ng.enginesandempires.weather.sim.WeatherSystem.Kind.LOW) {
                if (dist < 4000) {
                    lows4k++;
                }
                if (dist < lowD) {
                    lowD = dist;
                    low = sys;
                }
            } else if (dist < highD) {
                highD = dist;
                high = sys;
            }
        }
        long clock = ForecastRun.clock(dayTime);
        java.util.function.DoubleFunction<String> f1 = v -> String.format(Locale.ROOT, "%.1f", v);
        java.util.function.DoubleFunction<String> f3 = v -> String.format(Locale.ROOT, "%.3f", v);
        return String.join(",",
                Integer.toString(index), Long.toString(h.day()), Long.toString(clock / 1000),
                f1.apply(100 * h.yearFraction()), f3.apply(sim.cachedSeason()), Boolean.toString(d.inField()),
                f1.apply(d.groundT()), f1.apply(a.t()), f1.apply(a.a()), f1.apply(n.water()), f3.apply(n.rh()),
                Long.toString(Math.round(n.lclMetres())), Integer.toString(a.surface()), f3.apply(a.humidity()),
                f3.apply(n.instability()), n.heapType() == null ? "" : n.heapType().id, f3.apply(n.heapCover()),
                f3.apply(n.frontal()), n.front() == null ? "" : n.front(),
                Double.isFinite(n.warmAhead()) ? Long.toString(Math.round(n.warmAhead())) : "",
                f3.apply(n.convergence()), f3.apply(n.subsidence()), f3.apply(n.wrung()), f3.apply(n.orographic()),
                f3.apply(n.cover(dev.brights0ng.enginesandempires.weather.cloud.CloudType.NIMBOSTRATUS)),
                f3.apply(n.cover(dev.brights0ng.enginesandempires.weather.cloud.CloudType.ALTOSTRATUS)),
                f3.apply(n.cover(dev.brights0ng.enginesandempires.weather.cloud.CloudType.STRATUS)),
                f3.apply(n.cover(dev.brights0ng.enginesandempires.weather.cloud.CloudType.STRATOCUMULUS)),
                f3.apply(n.cover(dev.brights0ng.enginesandempires.weather.cloud.CloudType.CIRROSTRATUS)),
                f3.apply(n.cover(dev.brights0ng.enginesandempires.weather.cloud.CloudType.CIRRUS)),
                n.sourceOf(dev.brights0ng.enginesandempires.weather.cloud.CloudType.NIMBOSTRATUS).replace(',', ';'),
                f3.apply(h.wet()), h.kind() == null ? "" : h.kind().name().toLowerCase(Locale.ROOT),
                low == null ? "" : Long.toString(Math.round(lowD)), low == null ? "" : Long.toString(Math.round(low.radius())),
                low == null ? "" : f1.apply(low.strength()), low == null ? "" : low.stage().label(),
                Integer.toString(lows4k), high == null ? "" : Long.toString(Math.round(highD)),
                high == null ? "" : f1.apply(high.strength()), Integer.toString(s.systems().systems().size()),
                // +: the nearest low / the nearest storm track's centre lies south of the spot.
                low == null ? "" : Long.toString(Math.round(low.z - z)),
                Long.toString(Math.round(sim.jet().trackZ(sim.jet().nearestTrack(z), x, now / 20.0, sim.cachedSeason())
                        - z)));
    }

    private static void end(ServerLevel level, boolean complete) {
        Run r = run;
        run = null;
        SeasonSource.clearOverride();
        if (r == null) {
            return;
        }
        last = r.stats;
        lastSpots = r.spots;
        lastTrace = r.trace;
        WeatherSim sim = WeatherSim.of(level);
        if (sim != null) {
            sim.setExtraAnchors(List.of());
            // Back to the calendar's season.
            sim.refreshAllNormals();
        }
        Path csv = null;
        try {
            csv = writeCsv(level.getServer());
        } catch (IOException e) {
            Meteodyne.LOGGER.warn("Weather: couldn't write the climatology CSV", e);
        }
        List<String> report = report();
        Meteodyne.LOGGER.info("Weather: climatology run {} after {} days:\n{}", complete ? "finished" : "stopped",
                r.done / DAY, String.join("\n", report));
        if (r.listener != null) {
            r.listener.finished(report, csv);
        }
    }

    @SubscribeEvent
    static void onServerStopping(ServerStoppingEvent event) {
        run = null;
        autoSpots = null;
        SPOTS.clear();
        last = List.of();
        lastSpots = List.of();
        lastTrace = List.of();
        SeasonSource.clearOverride();
        WeatherSim sim = WeatherSim.of(event.getServer().overworld());
        if (sim != null) {
            sim.setExtraAnchors(List.of());
        }
    }

    @SubscribeEvent
    static void onServerStarted(net.neoforged.neoforge.event.server.ServerStartedEvent event) {
        String prop = System.getProperty("eae.climatologyAuto");
        if (prop == null || prop.isBlank()) {
            return;
        }
        List<String> spots = new ArrayList<>();
        for (String part : prop.split(";")) {
            String p = part.trim();
            if (p.contains(":") || p.split(",").length == 2) {
                spots.add(p);
            }
        }
        autoSpots = spots.isEmpty() ? null : spots;
    }

    /** How far from the origin a named biome is searched for, blocks (as {@code /locate biome}). */
    static final int BIOME_SEARCH_RADIUS = 6400;

    /** The nearest spot of biome {@code id} to the origin, {x, z}, or null if none is near enough. */
    static double[] findBiome(ServerLevel level, String id) {
        net.minecraft.resources.ResourceLocation rl = net.minecraft.resources.ResourceLocation.tryParse(id);
        if (rl == null) {
            return null;
        }
        var found = level.findClosestBiome3d(h -> h.is(rl), new net.minecraft.core.BlockPos(0, level.getSeaLevel(), 0),
                BIOME_SEARCH_RADIUS, 32, 64);
        if (found == null) {
            return null;
        }
        return new double[]{found.getFirst().getX(), found.getFirst().getZ()};
    }

    /** Starts the automatic run; the server stops once it is done (its report and CSVs are in the log folder). */
    private static void startAuto(MinecraftServer server) {
        List<String> spots = autoSpots;
        autoSpots = null;
        ServerLevel level = server.overworld();
        SPOTS.clear();
        for (String p : spots) {
            if (p.contains(":")) {
                double[] at = findBiome(level, p);
                if (at == null) {
                    Meteodyne.LOGGER.warn("Weather: climatology found no {} within {} blocks of the origin",
                            p, BIOME_SEARCH_RADIUS);
                    continue;
                }
                Meteodyne.LOGGER.info("Weather: climatology spot for {} at {}, {}", p, (long) at[0],
                        (long) at[1]);
                add(level, at[0], at[1]);
            } else {
                String[] xz = p.split(",");
                add(level, Double.parseDouble(xz[0].trim()), Double.parseDouble(xz[1].trim()));
            }
        }
        String daysProp = System.getProperty("eae.climatologyDays");
        long yearTicks = SeasonSource.yearTicks(level);
        int days = daysProp != null && !daysProp.isBlank() ? Integer.parseInt(daysProp.trim())
                : (int) ((yearTicks > 0 ? yearTicks : SeasonSource.DEFAULT_YEAR_TICKS) / DAY);
        String error = start(level, days, new Listener() {
            @Override
            public void progress(int percent) {
                Meteodyne.LOGGER.info("Weather: automatic climatology run {}% done", percent);
            }

            @Override
            public void finished(List<String> report, Path csv) {
                Meteodyne.LOGGER.info("Weather: automatic climatology run done; stopping the server");
                server.halt(false);
            }
        });
        if (error != null) {
            Meteodyne.LOGGER.warn("Weather: automatic climatology run didn't start: {}", error);
            server.halt(false);
        }
    }

    // ---- commands --------------------------------------------------------------------------------------------------

    @SubscribeEvent
    static void onRegisterCommands(RegisterCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("eae")
                .requires(source -> source.hasPermission(WeatherDebugNetwork.PERMISSION))
                .then(Commands.literal("weather")
                        .then(Commands.literal("climatology")
                                .executes(context -> report(context.getSource()))
                                .then(Commands.literal("add").executes(context -> add(context.getSource())))
                                .then(Commands.literal("spots").executes(context -> listSpots(context.getSource())))
                                .then(Commands.literal("clear").executes(context -> clear(context.getSource())))
                                .then(Commands.literal("run")
                                        .executes(context -> start(context.getSource(), -1))
                                        .then(Commands.argument("days",
                                                        com.mojang.brigadier.arguments.IntegerArgumentType.integer(1, 720))
                                                .executes(context -> start(context.getSource(),
                                                        com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(
                                                                context, "days")))))
                                .then(Commands.literal("stop").executes(context -> stop(context.getSource())))
                                .then(Commands.literal("report").executes(context -> report(context.getSource())))
                                .then(Commands.literal("csv").executes(context -> csv(context.getSource()))))));
    }

    private static int add(CommandSourceStack source) {
        Vec3 at = source.getPosition();
        Spot s = add(source.getServer().overworld(), at.x, at.z);
        source.sendSuccess(() -> Component.literal(String.format(Locale.ROOT,
                "Added climatology spot %.0f, %.0f (%s); %d spot(s). During a run the weather is simulated around every "
                        + "spot, so they can be anywhere.", s.x(), s.z(), s.biome(), SPOTS.size())), false);
        return 1;
    }

    private static int listSpots(CommandSourceStack source) {
        if (SPOTS.isEmpty()) {
            source.sendSuccess(() -> Component.literal("No climatology spots."), false);
            return 1;
        }
        for (Spot s : SPOTS) {
            source.sendSuccess(() -> Component.literal(String.format(Locale.ROOT, "  %.0f, %.0f (%s)", s.x(), s.z(),
                    s.biome())).withStyle(ChatFormatting.GRAY), false);
        }
        return 1;
    }

    private static int clear(CommandSourceStack source) {
        clearSpots();
        source.sendSuccess(() -> Component.literal("Cleared the climatology spots."), false);
        return 1;
    }

    private static int start(CommandSourceStack source, int days) {
        ServerLevel level = source.getServer().overworld();
        long yearTicks = SeasonSource.yearTicks(level);
        int length = days > 0 ? days : (int) ((yearTicks > 0 ? yearTicks : SeasonSource.DEFAULT_YEAR_TICKS) / DAY);
        MinecraftServer server = source.getServer();
        ServerPlayer player = source.getPlayer();
        UUID who = player == null ? null : player.getUUID();
        String error = start(level, length, new Listener() {
            @Override
            public void progress(int percent) {
                send(server, who, source, Component.literal("Climatology run " + percent + "% done.")
                        .withStyle(ChatFormatting.GRAY));
            }

            @Override
            public void finished(List<String> report, Path csv) {
                send(server, who, source, Component.literal("Climatology run done:").withStyle(ChatFormatting.AQUA));
                for (String line : report) {
                    send(server, who, source, Component.literal(line).withStyle(ChatFormatting.GRAY));
                }
                if (csv != null) {
                    send(server, who, source, Component.literal("Daily CSV: " + csv.toAbsolutePath())
                            .withStyle(ChatFormatting.GRAY));
                }
            }
        });
        if (error != null) {
            source.sendFailure(Component.literal(error));
            return 0;
        }
        source.sendSuccess(() -> Component.literal(String.format(Locale.ROOT,
                "Running the weather %d in-game days ahead over %d spot(s), from %s. The live weather moves ahead with it "
                        + "(test worlds only); /eae weather climatology stop ends it early.", length, SPOTS.size(),
                SeasonSource.describe(level))), true);
        return 1;
    }

    private static int stop(CommandSourceStack source) {
        if (!stop(source.getServer().overworld())) {
            source.sendFailure(Component.literal("No climatology run is going."));
            return 0;
        }
        return 1;
    }

    private static int report(CommandSourceStack source) {
        for (String line : report()) {
            source.sendSuccess(() -> Component.literal(line), false);
        }
        return 1;
    }

    private static int csv(CommandSourceStack source) {
        try {
            Path file = writeCsv(source.getServer());
            source.sendSuccess(() -> Component.literal("Wrote " + file.toAbsolutePath()), false);
            return 1;
        } catch (IOException e) {
            source.sendFailure(Component.literal("Couldn't write the CSV: " + e.getMessage()));
            return 0;
        }
    }

    /** To the player who started it if they are still online, otherwise to the original source (the console). */
    private static void send(MinecraftServer server, UUID who, CommandSourceStack source, Component message) {
        if (who != null) {
            ServerPlayer p = server.getPlayerList().getPlayer(who);
            if (p != null) {
                p.sendSystemMessage(message);
            }
            return;
        }
        source.sendSystemMessage(message);
    }

    private Climatology() {
    }
}
