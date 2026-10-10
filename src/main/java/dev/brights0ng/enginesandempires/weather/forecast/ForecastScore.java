package dev.brights0ng.enginesandempires.weather.forecast;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import dev.brights0ng.enginesandempires.EnginesAndEmpiresMod;
import dev.brights0ng.enginesandempires.weather.rain.WeatherConfig;
import dev.brights0ng.enginesandempires.weather.sim.SimMath;
import dev.brights0ng.enginesandempires.weather.sim.world.WeatherSim;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/**
 * The forecast scoring tool (phase 7c of {@code claude/weather-backbone-phase7.md}; debug only, operators, through
 * {@code /eae weather forecast track|untrack|score|clear}).
 *
 * <h2>While a spot is tracked</h2>
 * <ul>
 *   <li><b>Forecasts are made automatically</b> for it, so a long {@code /eae weather step} fills itself with things to
 *       check: a day forecast at the start of every 6-hour period and a longer one every midnight (and both straight
 *       away when tracking starts). They run on the server thread (a tenth to half a second each): fine for a debug
 *       tool, a small hitch every few minutes of live play.</li>
 *   <li><b>The truth is read once an in-game hour</b> ({@link ForecastRun#observe} on a small snapshot of the live
 *       weather), during live play and at every hour of {@code /eae weather step} and the night skip.</li>
 *   <li>Each part of each forecast is <b>checked once it has ended</b> ({@link ForecastVerify}); scores add up per
 *       product and lead, and every check is kept for the CSV.</li>
 * </ul>
 *
 * <h2>Time of day</h2>
 * {@code /eae weather step} runs the weather ahead without moving the game clock, so the scorer keeps its own day time
 * for the simulation: the game's day time at the last tick plus however far the simulation has run since. Step whole
 * days (24, 48 ...) so the clock and the simulation stay in phase afterwards.
 *
 * <p>Nothing is saved: a restart starts over.
 */
@EventBusSubscriber(modid = EnginesAndEmpiresMod.MODID)
public final class ForecastScore {

    /** Hours of truth kept per region (long enough to check a whole 4-day forecast made a day earlier). */
    static final long KEEP_TRUTH = 7 * 24_000L;
    /** The most checks kept for the CSV. */
    static final int MAX_CHECKS = 20_000;

    private record Spot(double x, double z) {
    }

    private static final class Issued {
        final Forecast forecast;
        final boolean[] done;

        Issued(Forecast forecast) {
            this.forecast = forecast;
            this.done = new boolean[forecast.parts().size()];
        }
    }

    private static final List<Spot> TRACKED = new ArrayList<>();
    private static final Map<ForecastService.Key, List<ForecastRun.Observation>> TRUTH = new HashMap<>();
    private static final Map<ForecastService.Key, List<Issued>> ISSUED = new HashMap<>();
    private static final Map<ForecastService.Key, Long> FIRST_ISSUE = new HashMap<>();
    private static final Map<String, ForecastVerify.Tally> TALLIES = new HashMap<>();
    private static final List<ForecastVerify.Check> CHECKS = new ArrayList<>();
    private static long lastHour = Long.MIN_VALUE;
    private static boolean anchored;
    private static long anchorSim;
    private static long anchorDay;
    private static int made;
    private static int skipped;

    // ---- commands --------------------------------------------------------------------------------------------------

    /** Starts tracking the spot (x, z). */
    public static void track(ServerLevel level, double x, double z) {
        TRACKED.add(new Spot(x, z));
        anchor(level);
        lastHour = Long.MIN_VALUE;
        hour(level.getServer().overworld());
    }

    /** Stops tracking the tracked spot nearest (x, z); returns whether there was one. */
    public static boolean untrack(double x, double z) {
        Spot best = null;
        double bestD = Double.POSITIVE_INFINITY;
        for (Spot s : TRACKED) {
            double d = Math.hypot(s.x - x, s.z - z);
            if (d < bestD) {
                bestD = d;
                best = s;
            }
        }
        return best != null && TRACKED.remove(best);
    }

    /** Drops every record and score (tracking carries on). */
    public static void clear() {
        TRUTH.clear();
        ISSUED.clear();
        FIRST_ISSUE.clear();
        TALLIES.clear();
        CHECKS.clear();
        made = 0;
        skipped = 0;
        lastHour = Long.MIN_VALUE;
    }

    public static int tracked() {
        return TRACKED.size();
    }

    /** The score lines, product by product, lead by lead. */
    public static List<String> report() {
        List<String> out = new ArrayList<>();
        int truthHours = 0;
        for (List<ForecastRun.Observation> l : TRUTH.values()) {
            truthHours += l.size();
        }
        int pending = 0;
        for (List<Issued> l : ISSUED.values()) {
            pending += l.size();
        }
        out.add(String.format(java.util.Locale.ROOT, "%d spot(s) tracked; %d forecasts made, %d still running out; "
                + "%d hours of truth held; %d checks (%d parts skipped for lack of truth).", TRACKED.size(), made,
                pending, truthHours, CHECKS.size(), skipped));
        for (Forecast.Product product : Forecast.Product.values()) {
            for (int lead = 1; lead <= 7; lead++) {
                ForecastVerify.Tally t = TALLIES.get(tallyKey(product, lead));
                if (t == null) {
                    continue;
                }
                String name = product == Forecast.Product.TODAY ? "Day forecast, period " + lead
                        : "Longer forecast, day " + lead;
                out.add(name + ": " + t.describe());
            }
        }
        return out;
    }

    /** Writes every check to {@code logs/forecast-score-<time>.csv} under the server directory; returns the file. */
    public static Path writeCsv(ServerLevel level) throws IOException {
        Path dir = level.getServer().getServerDirectory().resolve("logs");
        Files.createDirectories(dir);
        Path file = dir.resolve("forecast-score-" + System.currentTimeMillis() + ".csv");
        List<String> lines = new ArrayList<>(CHECKS.size() + 1);
        lines.add(ForecastVerify.Check.CSV_HEADER);
        for (ForecastVerify.Check c : CHECKS) {
            lines.add(c.csv());
        }
        Files.write(file, lines, StandardCharsets.UTF_8);
        return file;
    }

    /** A tally (tests). */
    static ForecastVerify.Tally tally(Forecast.Product product, int lead) {
        return TALLIES.get(tallyKey(product, lead));
    }

    private static String tallyKey(Forecast.Product product, int lead) {
        return product.id + ":" + lead;
    }

    // ---- the clock -------------------------------------------------------------------------------------------------

    @SubscribeEvent
    static void onServerTick(ServerTickEvent.Post event) {
        if (TRACKED.isEmpty()) {
            return;
        }
        ServerLevel level = event.getServer().overworld();
        anchor(level);
        hour(level);
    }

    private static void anchor(ServerLevel level) {
        WeatherSim sim = WeatherSim.of(level.getServer().overworld());
        if (sim == null) {
            return;
        }
        anchorSim = sim.time();
        anchorDay = level.getServer().overworld().getDayTime();
        anchored = true;
    }

    /**
     * Once per simulation hour while something is tracked: reads the truth, checks finished forecast parts, and makes
     * the scheduled forecasts. Called every server tick and after every hour {@code WeatherSim.advance} runs.
     */
    public static void hour(ServerLevel level) {
        if (TRACKED.isEmpty() || level == null || !Level.OVERWORLD.equals(level.dimension())) {
            return;
        }
        WeatherSim sim = WeatherSim.of(level);
        if (sim == null) {
            return;
        }
        if (!anchored) {
            anchor(level);
        }
        long now = sim.time();
        long h = Math.floorDiv(now, 1000L);
        if (h == lastHour) {
            return;
        }
        lastHour = h;
        long dayTime = anchorDay + (now - anchorSim);
        ForecastSettings st = WeatherConfig.forecast();
        try {
            for (Spot spot : new ArrayList<>(TRACKED)) {
                for (Forecast.Product product : Forecast.Product.values()) {
                    ForecastService.Key key = ForecastService.Key.of(product, spot.x, spot.z);
                    observe(level, sim, key, dayTime, st, now);
                    check(key, now);
                    issue(level, sim, key, dayTime, st, h);
                }
            }
        } catch (RuntimeException e) {
            EnginesAndEmpiresMod.LOGGER.warn("Weather: the forecast scorer failed this hour", e);
        }
    }

    private static void observe(ServerLevel level, WeatherSim sim, ForecastService.Key key, long dayTime,
                                ForecastSettings st, long now) {
        List<ForecastRun.Observation> list = TRUTH.computeIfAbsent(key, k -> new ArrayList<>());
        if (!list.isEmpty() && list.get(list.size() - 1).time() >= now) {
            return;
        }
        ForecastSnapshot s = ForecastService.snapshot(level, sim, key, ForecastSnapshot.truthDomain(key.product()), 0,
                dayTime, st);
        list.add(ForecastRun.observe(s, now));
        list.removeIf(o -> o.time() < now - KEEP_TRUTH);
    }

    private static void check(ForecastService.Key key, long now) {
        List<Issued> list = ISSUED.get(key);
        if (list == null) {
            return;
        }
        List<ForecastRun.Observation> truth = TRUTH.getOrDefault(key, List.of());
        Iterator<Issued> it = list.iterator();
        while (it.hasNext()) {
            Issued issued = it.next();
            boolean all = true;
            for (int i = 0; i < issued.done.length; i++) {
                if (issued.done[i]) {
                    continue;
                }
                Forecast.Outlook p = issued.forecast.parts().get(i);
                if (p.end() > now) {
                    all = false;
                    continue;
                }
                issued.done[i] = true;
                Forecast.Outlook seen = ForecastVerify.observed(truth, p.start(), p.end());
                if (seen == null) {
                    skipped++;
                    continue;
                }
                ForecastVerify.Check c = ForecastVerify.check(issued.forecast, i, seen,
                        ForecastVerify.count(truth, p.start(), p.end()));
                TALLIES.computeIfAbsent(tallyKey(c.product(), c.lead()), k -> new ForecastVerify.Tally()).add(c);
                if (CHECKS.size() < MAX_CHECKS) {
                    CHECKS.add(c);
                }
            }
            if (all) {
                it.remove();
            }
        }
    }

    private static void issue(ServerLevel level, WeatherSim sim, ForecastService.Key key, long dayTime,
                              ForecastSettings st, long h) {
        long clock = ForecastRun.clock(dayTime);
        boolean first = !FIRST_ISSUE.containsKey(key);
        boolean due = key.product() == Forecast.Product.TODAY ? clock % ForecastRun.PERIOD < 1000 : clock < 1000;
        if (!first && !due) {
            return;
        }
        FIRST_ISSUE.putIfAbsent(key, h);
        long salt = SimMath.hash(level.getSeed(), 0xF0CA57L, key.product().ordinal(), key.rx(), key.rz(), h);
        ForecastSnapshot s = ForecastService.snapshot(level, sim, key, ForecastSnapshot.domain(key.product()), salt,
                dayTime, st);
        Forecast f = ForecastRun.run(s);
        if (f != null) {
            ISSUED.computeIfAbsent(key, k -> new ArrayList<>()).add(new Issued(f));
            made++;
        }
    }

    @SubscribeEvent
    static void onServerStopping(ServerStoppingEvent event) {
        TRACKED.clear();
        clear();
        anchored = false;
    }

    private ForecastScore() {
    }
}
