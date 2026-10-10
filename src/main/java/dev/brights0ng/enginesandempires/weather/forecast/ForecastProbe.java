package dev.brights0ng.enginesandempires.weather.forecast;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import dev.brights0ng.enginesandempires.weather.field.AtmosphereField;
import dev.brights0ng.enginesandempires.weather.rain.WeatherConfig;
import dev.brights0ng.enginesandempires.weather.sim.WeatherSystem;
import dev.brights0ng.enginesandempires.weather.sim.world.WeatherSim;
import net.minecraft.server.level.ServerLevel;

/**
 * Debugging (7c, {@code /eae weather forecast probe}): a day forecast's centre against the live weather, hour by hour,
 * in several variants, to find where a forecast's air drifts away from the real air. It runs the real simulation
 * {@code hours} ahead (like {@code /eae weather step}).
 *
 * <ul>
 *   <li><b>default:</b> the forecast as the forecaster makes it.</li>
 *   <li><b>noSeason:</b> without projecting the season forward.</li>
 *   <li><b>step1000:</b> hourly steps instead of 15-minute ones.</li>
 *   <li><b>liveEnv:</b> the forecast's copy of the air, stepped hourly alongside the live run with the live run's own
 *       environment (its storms, winds and climate). If this one follows the truth and the others don't, the forecast's
 *       storms are what differ; if it drifts too, the air itself is treated differently.</li>
 * </ul>
 */
public final class ForecastProbe {

    public static List<String> compare(ServerLevel level, double x, double z, int hours) {
        WeatherSim sim = WeatherSim.of(level);
        ForecastSettings st = WeatherConfig.forecast();
        ForecastService.Key key = ForecastService.Key.of(Forecast.Product.TODAY, x, z);
        long dayTime0 = level.getDayTime();
        long time0 = sim.time();
        String[] names = {"default", "noSeason", "step1000", "liveEnv"};
        double[][][] runs = new double[names.length][hours + 1][2];
        List<String> out = new ArrayList<>();
        ForecastSnapshot base = ForecastService.snapshot(level, sim, key, ForecastSnapshot.domain(Forecast.Product.TODAY),
                99, dayTime0, st);
        out.add(String.format(Locale.ROOT, "Probe at %.0f, %.0f: %d field tiles copied, year %.3f of %d ticks, "
                + "%d systems", key.centreX(), key.centreZ(), base.field().tiles().size(), base.yearFraction(),
                base.yearTicks(), base.systems().systems().size()));
        out.add("  systems now: " + near(base.systems().systems(), x, z));
        for (int v = 0; v < 3; v++) {
            ForecastSnapshot s = ForecastService.snapshot(level, sim, key, ForecastSnapshot.domain(Forecast.Product.TODAY),
                    99, dayTime0, st);
            if (v == 1) {
                s = new ForecastSnapshot(s.product(), s.x(), s.z(), s.seed(), s.seaLevel(), s.systems(), s.field(),
                        s.time(), s.dayTime(), s.yearFraction(), 0, s.stepTicks(), s.days(), s.heightScale(),
                        s.maxCooling());
            } else if (v == 2) {
                s = new ForecastSnapshot(s.product(), s.x(), s.z(), s.seed(), s.seaLevel(), s.systems(), s.field(),
                        s.time(), s.dayTime(), s.yearFraction(), s.yearTicks(), 1000, s.days(), s.heightScale(),
                        s.maxCooling());
            }
            double[][] series = runs[v];
            for (double[] row : series) {
                row[0] = Double.NaN;
                row[1] = Double.NaN;
            }
            ForecastRun.run(s, o -> {
                double h = (o.time() - time0) / 1000.0;
                int hi = (int) Math.round(h);
                if (Math.abs(h - hi) < 0.01 && hi <= hours && o.probe() != null) {
                    series[hi][0] = o.probe().q();
                    series[hi][1] = o.probe().fieldT();
                }
            });
            if (v == 0) {
                out.add("  forecast systems after " + hours + " h: " + near(s.systems().systems(), x, z));
            }
        }
        // liveEnv: the forecast's copy of the air, stepped with the live run's environment.
        AtmosphereField copy = base.field();
        double[][] truth = new double[hours + 1][2];
        for (int h = 0; h <= hours; h++) {
            if (h > 0) {
                sim.advance(1000);
                copy.step(1000, sim.readEnv());
            }
            ForecastSnapshot t = ForecastService.snapshot(level, sim, key,
                    ForecastSnapshot.truthDomain(Forecast.Product.TODAY), 0, dayTime0 + (sim.time() - time0), st);
            ForecastRun.Observation o = ForecastRun.observe(t, sim.time());
            truth[h][0] = o.probe().q();
            truth[h][1] = o.probe().fieldT();
            double[] c = copy.cellAt(key.centreX(), key.centreZ());
            runs[3][h][0] = c == null ? Double.NaN : c[2];
            runs[3][h][1] = c == null ? Double.NaN : c[0];
        }
        out.add("  live systems after " + hours + " h: " + near(sim.systems(), x, z));
        StringBuilder head = new StringBuilder("  hour | truth  q     T");
        for (String n : names) {
            head.append(String.format(Locale.ROOT, " | %-12s", n));
        }
        out.add(head.toString());
        for (int h = 0; h <= hours; h += 2) {
            StringBuilder sb = new StringBuilder(String.format(Locale.ROOT, "  %4d | %5.2f %5.2f", h, truth[h][0],
                    truth[h][1]));
            for (double[][] r : runs) {
                sb.append(String.format(Locale.ROOT, " | %5.2f %5.2f", r[h][0], r[h][1]));
            }
            out.add(sb.toString());
        }
        return out;
    }

    private static String near(List<WeatherSystem> systems, double x, double z) {
        List<WeatherSystem> l = new ArrayList<>(systems);
        l.sort((a, b) -> Double.compare(Math.hypot(a.x - x, a.z - z), Math.hypot(b.x - x, b.z - z)));
        StringBuilder sb = new StringBuilder();
        for (WeatherSystem s : l.subList(0, Math.min(4, l.size()))) {
            sb.append(String.format(Locale.ROOT, "[%s #%d %+.0f,%+.0f %.0fhPa] ", s.kind, s.id, s.x - x, s.z - z,
                    s.signedStrength()));
        }
        return sb.toString();
    }

    private ForecastProbe() {
    }
}
