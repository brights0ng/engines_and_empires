package dev.brights0ng.enginesandempires.weather.forecast;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import dev.brights0ng.enginesandempires.weather.climate.ClimateCurves;
import dev.brights0ng.enginesandempires.weather.climate.Temperature;
import dev.brights0ng.enginesandempires.weather.cloud.CloudType;
import dev.brights0ng.enginesandempires.weather.cloud.sim.CloudDiagnostics;
import dev.brights0ng.enginesandempires.weather.field.AirMassContact;
import dev.brights0ng.enginesandempires.weather.field.AtmosphereField;
import dev.brights0ng.enginesandempires.weather.field.FieldEnv;
import dev.brights0ng.enginesandempires.weather.field.WarmNose;
import dev.brights0ng.enginesandempires.weather.rain.Precip;
import dev.brights0ng.enginesandempires.weather.sim.JetStream;
import dev.brights0ng.enginesandempires.weather.sim.PressureField;
import dev.brights0ng.enginesandempires.weather.sim.SystemsSim;
import dev.brights0ng.enginesandempires.weather.sim.WeatherSystem;

/**
 * Runs a forecast (phases 7b and 7d of {@code claude/weather-backbone-phase7.md}): steps a {@link ForecastSnapshot}'s
 * copies of the weather systems and the atmosphere field forward, and reads the sky every step on a grid across the
 * region ({@link ForecastGrid}), so a forecast can then be read for any spot in it. Runs off the server thread: it
 * touches nothing but the snapshot.
 *
 * <ul>
 *   <li><b>Systems</b> step with no live drift (each system's drift relaxes toward zero, its expected value);
 *       new-born systems' make-up blends toward the forecast's own roll the later they form
 *       ({@link SystemsSim#forForecast(long, long)}).</li>
 *   <li><b>Field</b> steps the copied tiles; the air just outside the copy is held as it was at the start.</li>
 *   <li><b>Season</b> is projected from the snapshot's year fraction; <b>time of day</b> from its day time.</li>
 * </ul>
 */
public final class ForecastRun {

    /** Ticks in a 6-hour period. */
    static final long PERIOD = 6000;
    static final long DAY = 24000;
    static final String[] PERIOD_NAMES = {"Night (12am-6am)", "Morning (6am-12pm)", "Afternoon (12pm-6pm)",
            "Evening (6pm-12am)"};
    /**
     * The warm layer aloft is worked out wherever the ground is below this, C: a little above where it matters live
     * ({@code Precip.MIXED_MAX}), so a spot whose corrected temperature is colder than the run's still gets it (7d).
     */
    static final double NOSE_BELOW = Precip.MIXED_MAX + 10;

    /** Runs the forecast. Stops early (returning null) if the thread is interrupted. */
    public static ForecastGrid run(ForecastSnapshot s) {
        return run(s, null);
    }

    /** As {@link #run(ForecastSnapshot)}, handing every reading it makes to {@code trace} (debug; may be null). */
    public static ForecastGrid run(ForecastSnapshot s, java.util.function.Consumer<Observation> trace) {
        List<ForecastGrid.Part> parts = parts(s);
        long end = parts.get(parts.size() - 1).end();
        long first = parts.get(0).start();
        SystemsSim systems = s.systems();
        AtmosphereField field = s.field();
        JetStream jet = systems.jet();
        List<SystemsSim.Anchor> anchors = List.of(new SystemsSim.Anchor(s.x(), s.z()));
        List<ForecastGrid.Step> steps = new ArrayList<>();
        long dt = Math.max(50, s.stepTicks());
        long t = s.time();
        sample(s, t, dt, first, steps, trace);
        while (t < end) {
            if (Thread.currentThread().isInterrupted()) {
                return null;
            }
            long step = Math.min(dt, end - t);
            long next = t + step;
            double season = season(s, next);
            systems.step(next, step, season, anchors, (x, z) -> true, null);
            field.step(step, env(s, systems.systems(), jet, next, season));
            t = next;
            sample(s, t, step, first, steps, trace);
        }
        return new ForecastGrid(s.product(), s.x(), s.z(), s.time(), s.dayTime(), List.copyOf(parts),
                List.copyOf(steps));
    }

    /**
     * The sky over a region at one moment: its readings (the whole grid for a forecast run, 9 for a spot's truth), the
     * surface wind at the region's centre (m/s) and the pressure there (hPa), and the debug probe at the centre. The
     * scoring tool reads the live weather through exactly the same lens.
     */
    public record Observation(long time, ForecastChance.Point[] points, double wx, double wz, double pressure,
                              Probe probe) {

        public Observation(long time, ForecastChance.Point[] points, double wx, double wz, double pressure) {
            this(time, points, wx, wz, pressure, null);
        }
    }

    /**
     * Debug detail at the region's centre (7c trace): the field's sea-level temperature, the ground temperature, the
     * air's water, its humidity now (from the condensation level), the low decks' cover and why, the heap type, and
     * how much of the spot is under rain.
     */
    public record Probe(double fieldT, double groundT, double q, double rhNow, double stratus, double stratocumulus,
                        double nimbostratus, String heap, double wet, String lowSources) {
    }

    /**
     * The parts of a forecast starting at the snapshot's moment: four 6-hour periods from the current one (day
     * forecast), or {@code days} whole days from the next midnight (4-day forecast).
     */
    static List<ForecastGrid.Part> parts(ForecastSnapshot s) {
        long clock = clock(s.dayTime());
        List<ForecastGrid.Part> out = new ArrayList<>();
        if (s.product() == Forecast.Product.TODAY) {
            long periodStart = s.time() - clock % PERIOD;
            int period = (int) (clock / PERIOD);
            for (int k = 0; k < 4; k++) {
                int idx = (period + k) % 4;
                boolean tomorrow = period + k >= 4;
                String label = PERIOD_NAMES[idx] + (tomorrow ? " tomorrow" : "");
                out.add(new ForecastGrid.Part(label, Math.max(s.time(), periodStart + k * PERIOD),
                        periodStart + (k + 1) * PERIOD));
            }
        } else {
            long midnight = s.time() + (DAY - clock);
            long dayNumber = Math.floorDiv(s.dayTime() + 6000, DAY) + 2;
            for (int k = 0; k < Math.max(1, s.days()); k++) {
                String label = k == 0 ? String.format(Locale.ROOT, "Tomorrow (day %d)", dayNumber)
                        : String.format(Locale.ROOT, "Day %d", dayNumber + k);
                out.add(new ForecastGrid.Part(label, midnight + k * DAY, midnight + (k + 1) * DAY));
            }
        }
        return out;
    }

    /** Ticks since midnight at Minecraft day time {@code dayTime} (day time 0 is 6:00). */
    static long clock(long dayTime) {
        return Math.floorMod(dayTime + 6000, DAY);
    }

    /** The season factor at simulation time {@code t}. */
    static double season(ForecastSnapshot s, long t) {
        if (!Double.isFinite(s.yearFraction())) {
            return 0;
        }
        double year = s.yearFraction() + (s.yearTicks() > 0 ? (double) (t - s.time()) / s.yearTicks() : 0);
        return ClimateCurves.season(year);
    }

    /** Minecraft day time at simulation time {@code t}. */
    static long dayTime(ForecastSnapshot s, long t) {
        return s.dayTime() + (t - s.time());
    }

    /** Reads the grid at simulation time {@code t} (from the first part's start on); {@code step} ticks stand behind it. */
    private static void sample(ForecastSnapshot s, long t, long step, long first, List<ForecastGrid.Step> steps,
                               java.util.function.Consumer<Observation> trace) {
        if (t < first) {
            return;
        }
        Observation o = observe(s, t);
        steps.add(new ForecastGrid.Step(t, step / 1000.0, o.points(), o.wx(), o.wz(), o.pressure()));
        if (trace != null) {
            trace.accept(o);
        }
    }

    /** Reads the whole grid over the snapshot's region from its systems and field as they stand, at time {@code t}. */
    public static Observation observe(ForecastSnapshot s, long t) {
        int n = ForecastGrid.side(s.product());
        int[] all = new int[n * n];
        for (int i = 0; i < all.length; i++) {
            all[i] = i;
        }
        return read(s, t, all, true);
    }

    /**
     * Reads the 9 grid spots around (px, pz) (the ones a point forecast for that spot reads, {@link ForecastGrid#at}),
     * at time {@code t}: the scoring tool's truth.
     */
    public static Observation observeSpot(ForecastSnapshot s, long t, double px, double pz) {
        return read(s, t, ForecastGrid.neighbourhood(s.product(), s.x(), s.z(), px, pz), false);
    }

    private static Observation read(ForecastSnapshot s, long t, int[] spots, boolean probe) {
        List<WeatherSystem> list = s.systems().systems();
        double season = season(s, t);
        long dayTime = dayTime(s, t);
        double seconds = t / 20.0;
        PressureField.Snapshot pressure = PressureField.Snapshot.of(list);
        CloudDiagnostics.Systems diag = CloudDiagnostics.Systems.of(list);
        FieldEnv env = env(s, list, s.systems().jet(), t, season);
        ForecastChance.Point[] points = new ForecastChance.Point[spots.length];
        for (int j = 0; j < spots.length; j++) {
            double[] p = ForecastGrid.spot(s.product(), s.x(), s.z(), spots[j]);
            points[j] = point(s, env, list, diag, p[0], p[1], dayTime, season);
        }
        double[] w = PressureField.wind(pressure, s.systems().jet(), s.x(), s.z(), seconds, season, s.seed());
        return new Observation(t, points, w[0], w[1], pressure.pressure(s.x(), s.z()),
                probe ? probe(s, env, diag, dayTime, season, points[points.length / 2]) : null);
    }

    /**
     * The run's ground temperature at (x, z) at Minecraft day time {@code dayTime}, C (7d: the point forecast's
     * temperature offset is how much warmer the spot feels than this).
     */
    public static double lensGroundT(ForecastSnapshot s, double x, double z, long dayTime) {
        List<WeatherSystem> list = s.systems().systems();
        double season = season(s, s.time());
        FieldEnv env = env(s, list, s.systems().jet(), s.time(), season);
        CloudDiagnostics.Air air = air(s, env, x, z);
        return air.t() + heating(air, dayTime, season) + air.heightCorrection();
    }

    /** The trace's detail at the region's centre. */
    private static Probe probe(ForecastSnapshot s, FieldEnv env, CloudDiagnostics.Systems diag, long dayTime,
                               double season, ForecastChance.Point centre) {
        CloudDiagnostics.Air air = air(s, env, s.x(), s.z());
        CloudDiagnostics.Need need = CloudDiagnostics.diagnose(s.x(), s.z(), air, diag, dayTime, season);
        double groundT = air.t() + heating(air, dayTime, season) + air.heightCorrection();
        String sources = need.sourceOf(CloudType.STRATUS) + "/" + need.sourceOf(CloudType.STRATOCUMULUS) + "/"
                + need.sourceOf(CloudType.NIMBOSTRATUS);
        return new Probe(air.t(), groundT, air.q(), 1 - need.lclMetres() / 2500, need.cover(CloudType.STRATUS),
                need.cover(CloudType.STRATOCUMULUS), need.cover(CloudType.NIMBOSTRATUS),
                need.heapType() == null ? "" : need.heapType().id, centre.wet(), sources);
    }

    /** The weather at one spot. */
    private static ForecastChance.Point point(ForecastSnapshot s, FieldEnv env, List<WeatherSystem> systems,
                                              CloudDiagnostics.Systems diag, double x, double z, long dayTime,
                                              double season) {
        AtmosphereField field = s.field();
        CloudDiagnostics.Air air = air(s, env, x, z);
        CloudDiagnostics.Need need = CloudDiagnostics.diagnose(x, z, air, diag, dayTime, season);
        double heating = heating(air, dayTime, season);
        double groundT = air.t() + heating + air.heightCorrection();
        double melt = 0;
        double cold = 0;
        // As live, only cold ground turns a warm layer aloft into sleet or freezing rain (a spot's corrected
        // temperature may be colder than the run's, so this reaches a little warmer than live).
        if (groundT < NOSE_BELOW) {
            double[] nose = WarmNose.at(systems, x, z,
                    (px, pz) -> field.sample(AtmosphereField.Var.T, px, pz, env) + heating);
            melt = nose[0];
            cold = nose[1];
        }
        return ForecastChance.point(need, groundT, melt, cold, air.humidity());
    }

    /** The day's warming (or night's cooling) of the surface air, C: none over water, as the diagnostics. */
    static double heating(CloudDiagnostics.Air air, long dayTime, double season) {
        if (air.surface() == 2) {
            return 0;
        }
        double s = Math.max(-1, Math.min(1, season));
        return ClimateCurves.diurnal(dayTime) * ClimateCurves.diurnalRange(air.humidity()) * (1 + 0.35 * s);
    }

    /** The air over (x, z) from the forecast's field, built the way the cloud spawner builds it. */
    private static CloudDiagnostics.Air air(ForecastSnapshot s, FieldEnv env, double x, double z) {
        AtmosphereField field = s.field();
        double[] c = field.cellAt(x, z);
        if (c == null) {
            double[] normal = field.normalNear(x, z);
            return new CloudDiagnostics.Air(normal[0], normal[0] + AtmosphereField.ALOFT_OFFSET, 5, 0, normal[0],
                    normal[1], (int) normal[2], 0, 0, 0, 0, 0, 0, 0, 0);
        }
        double cell = AtmosphereField.CELL;
        double[] e = field.cellAt(x + cell, z);
        double[] w = field.cellAt(x - cell, z);
        double[] so = field.cellAt(x, z + cell);
        double[] no = field.cellAt(x, z - cell);
        double slopeX = e != null && w != null ? (e[7] - w[7]) / (2 * cell) : 0;
        double slopeZ = so != null && no != null ? (so[7] - no[7]) / (2 * cell) : 0;
        double[] wind = env.wind(x, z);
        return new CloudDiagnostics.Air(c[0], c[1], c[2], c[3], c[4], c[5], (int) c[6], c[7], slopeX, slopeZ,
                wind[0], wind[1], wind[2], wind[3], env.heightCorrection(c[7]));
    }

    /** The field's environment at simulation time {@code t}, built purely from the forecast's own state. */
    static FieldEnv env(ForecastSnapshot s, List<WeatherSystem> systems, JetStream jet, long t, double season) {
        PressureField.Snapshot pressure = PressureField.Snapshot.of(systems);
        AirMassContact contact = AirMassContact.prepare(systems, season);
        return env(s, pressure, contact, jet, t / 20.0, season);
    }

    private static FieldEnv env(ForecastSnapshot s, PressureField.Snapshot pressure, AirMassContact contact,
                                JetStream jet, double seconds, double season) {
        AtmosphereField field = s.field();
        int sea = s.seaLevel();
        long seed = s.seed();
        return new FieldEnv() {
            @Override
            public double[] climate(double x, double z) {
                return field.normalNear(x, z);
            }

            @Override
            public double[] wind(double x, double z) {
                return PressureField.wind(pressure, jet, x, z, seconds, season, seed);
            }

            @Override
            public double[] contact(double x, double z) {
                return contact.at(x, z);
            }

            @Override
            public double heightCorrection(double elevation) {
                return Temperature.heightCorrection(sea, sea + elevation, s.heightScale(), s.maxCooling());
            }

            @Override
            public FieldEnv forArea(double minX, double minZ, double maxX, double maxZ) {
                return env(s, pressure.near(minX, minZ, maxX, maxZ), contact.near(minX, minZ, maxX, maxZ), jet,
                        seconds, season);
            }
        };
    }

    private ForecastRun() {
    }
}
