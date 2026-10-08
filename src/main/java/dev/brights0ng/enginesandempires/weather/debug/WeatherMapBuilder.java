package dev.brights0ng.enginesandempires.weather.debug;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import dev.brights0ng.enginesandempires.weather.climate.Baseline;
import dev.brights0ng.enginesandempires.weather.climate.BiomeClimate;
import dev.brights0ng.enginesandempires.weather.climate.Climate;
import dev.brights0ng.enginesandempires.weather.climate.ClimateCurves;
import dev.brights0ng.enginesandempires.weather.climate.ClimateField;
import dev.brights0ng.enginesandempires.weather.climate.SeasonSource;
import dev.brights0ng.enginesandempires.weather.field.AtmosphereField;
import dev.brights0ng.enginesandempires.weather.field.FieldEnv;
import dev.brights0ng.enginesandempires.weather.field.Moisture;
import dev.brights0ng.enginesandempires.weather.rain.WeatherConfig;
import dev.brights0ng.enginesandempires.weather.sim.FrontGeometry;
import dev.brights0ng.enginesandempires.weather.sim.JetStream;
import dev.brights0ng.enginesandempires.weather.sim.PressureField;
import dev.brights0ng.enginesandempires.weather.sim.WeatherSystem;
import dev.brights0ng.enginesandempires.weather.sim.world.WeatherSim;
import net.minecraft.server.level.ServerLevel;

/**
 * Builds the debug map's grid on the server. Everything is at sea level (the map shows the climate, not the terrain).
 *
 * <ul>
 *   <li><b>The biome underfoot:</b> the chunk column's surface biome (cached) when zoomed in past the lattice; the
 *       nearest lattice node's otherwise.</li>
 *   <li><b>The regional climate:</b> smoothed, except when zoomed far out ({@link #COARSE} blocks a pixel or more):
 *       there the pixels are already coarser than the smoothing, so the raw lattice node stands in. Smoothing 81 nodes
 *       per pixel over a 100k-block view thrashed the caches and took 1.3 s a build (perf report 2026-10-05).</li>
 *   <li><b>The atmosphere field</b> is sampled only where it exists; elsewhere the air is at its normal.</li>
 *   <li><b>Built a few rows at a time</b> ({@link Job}), a few milliseconds per tick, so no build stalls the server.</li>
 * </ul>
 */
public final class WeatherMapBuilder {

    /** Surface wind arrows per side. */
    static final int ARROWS = 12;
    static final int JET_POINTS = 48;
    /** From this many blocks per pixel, raw lattice nodes stand in for the smoothed regional climate. */
    static final int COARSE = 256;

    /** Builds a whole map at once (commands, tests). */
    public static WeatherMapPayloads.Answer build(ServerLevel level, int cx, int cz, int scale, int size) {
        Job job = new Job(level, cx, cz, scale, size);
        job.work(Long.MAX_VALUE);
        return job.finish();
    }

    /** A map being built a few rows at a time. */
    public static final class Job {

        private final ServerLevel level;
        private final int cx;
        private final int cz;
        private final int s;
        private final int n;
        private final double half;
        private final float[] now;
        private final float[] mean;
        private final float[] humidity;
        private final byte[] surface;
        private final float[] pressure;
        private final float[] anomaly;
        private final float[] moisture;
        private final float[] relative;
        private final float[] rain;
        private final WeatherSim sim;
        private final List<WeatherSystem> near = new ArrayList<>();
        private final PressureField.Snapshot pressureNear;
        private final AtmosphereField air;
        private final FieldEnv env;
        private final ClimateField field;
        private final double w;
        private final int sea;
        private int row;

        public Job(ServerLevel level, int cx, int cz, int scale, int size) {
            this.level = level;
            this.n = Math.max(8, Math.min(WeatherMapPayloads.MAX_SIZE, size));
            this.s = Math.max(WeatherMapPayloads.MIN_SCALE, Math.min(WeatherMapPayloads.MAX_SCALE, scale));
            this.cx = cx;
            this.cz = cz;
            this.half = n * s / 2.0;
            int cells = n * n;
            this.now = new float[cells];
            this.mean = new float[cells];
            this.humidity = new float[cells];
            this.surface = new byte[cells];
            this.pressure = new float[cells];
            this.anomaly = new float[cells];
            this.moisture = new float[cells];
            this.relative = new float[cells];
            this.rain = new float[cells];
            this.sim = WeatherSim.of(level);
            if (sim != null) {
                for (WeatherSystem ws : sim.systems()) {
                    double reach = half + 3.5 * ws.radius();
                    if (Math.abs(ws.x - cx) <= reach && Math.abs(ws.z - cz) <= reach) {
                        near.add(ws);
                    }
                }
            }
            this.pressureNear = PressureField.Snapshot.of(near);
            this.air = sim == null ? null : sim.field();
            this.env = sim == null ? null : sim.readEnv();
            this.field = Climate.field(level);
            this.w = WeatherConfig.localBiomeWeight();
            this.sea = level.getSeaLevel();
        }

        /** Builds rows until {@code deadline} (System.nanoTime); returns whether the map is complete. */
        public boolean work(long deadline) {
            while (row < n) {
                buildRow(row++);
                if (System.nanoTime() >= deadline) {
                    break;
                }
            }
            return row >= n;
        }

        private void buildRow(int k) {
            double z = cz + (k - n / 2.0 + 0.5) * s;
            boolean coarse = s >= COARSE;
            for (int i = 0; i < n; i++) {
                double x = cx + (i - n / 2.0 + 0.5) * s;
                BiomeClimate local = s >= ClimateField.SPACING
                        ? field.nearest(x, z)
                        : Climate.columnClimate(level, (int) Math.floor(x) >> 4, (int) Math.floor(z) >> 4);
                ClimateField.Regional regional = coarse
                        ? new ClimateField.Regional(local.mean(), local.swing(), local.humidity())
                        : field.regional(x, z);
                Baseline.Sample sample = Climate.sample(level, x, sea, z, local, regional);
                int idx = k * n + i;
                now[idx] = (float) sample.temperature();
                double annual = (1 - w) * regional.mean() + w * local.mean() + sample.band();
                mean[idx] = (float) (local.frozen() ? Math.min(annual, BiomeClimate.FROZEN_MAX) : annual);
                humidity[idx] = (float) sample.humidity();
                surface[idx] = (byte) (local.surface().ordinal() | (local.frozen() ? 4 : 0));
                pressure[idx] = (float) pressureNear.pressure(x, z);
                if (air != null && air.covers(x, z)) {
                    anomaly[idx] = (float) sim.anomaly(x, z);
                    double q = air.sample(AtmosphereField.Var.Q, x, z, env);
                    double t = air.sample(AtmosphereField.Var.T, x, z, env);
                    moisture[idx] = (float) q;
                    relative[idx] = (float) Math.min(1, q / Moisture.capacity(t));
                    rain[idx] = (float) (air.sample(AtmosphereField.Var.P, x, z, env)
                            + air.sample(AtmosphereField.Var.R, x, z, env));
                } else {
                    // Outside the field the air is at its normal: the ground's equilibrium humidity.
                    double rh = Moisture.targetHumidity(local.surface().ordinal(), sample.humidity());
                    double normal = sample.temperature() - sample.diurnal() - sample.anomaly();
                    moisture[idx] = (float) (Moisture.capacity(normal) * rh);
                    relative[idx] = (float) rh;
                }
            }
        }

        /** The finished answer, with the systems, fronts, wind arrows and storm tracks added. */
        public WeatherMapPayloads.Answer finish() {
            float[] windX = new float[ARROWS * ARROWS];
            float[] windZ = new float[ARROWS * ARROWS];
            List<WeatherMapPayloads.Marker> markers = new ArrayList<>();
            List<WeatherMapPayloads.FrontLine> fronts = new ArrayList<>();
            List<float[]> jets = new ArrayList<>();
            if (sim != null) {
                JetStream jet = sim.jet();
                double seconds = sim.seconds();
                double season = sim.season();
                for (int k = 0; k < ARROWS; k++) {
                    double z = cz - half + (k + 0.5) * 2 * half / ARROWS;
                    for (int i = 0; i < ARROWS; i++) {
                        double x = cx - half + (i + 0.5) * 2 * half / ARROWS;
                        double[] wind = PressureField.wind(pressureNear, jet, x, z, seconds, season, sim.seed());
                        windX[k * ARROWS + i] = (float) wind[0];
                        windZ[k * ARROWS + i] = (float) wind[1];
                    }
                }
                for (WeatherSystem ws : near) {
                    if (Math.abs(ws.x - cx) <= half + ws.radius() && Math.abs(ws.z - cz) <= half + ws.radius()) {
                        markers.add(new WeatherMapPayloads.Marker((float) ws.x, (float) ws.z,
                                ws.kind == WeatherSystem.Kind.HIGH, (float) ws.strength(), (float) ws.radius(),
                                ws.stage().ordinal(), ws.blocking, ws.hemisphere, (float) ws.life(), ws.lifetime));
                    }
                    for (FrontGeometry.Front f : FrontGeometry.of(ws)) {
                        float[] pts = new float[f.points().size() * 2];
                        for (int i = 0; i < f.points().size(); i++) {
                            pts[2 * i] = (float) f.points().get(i)[0];
                            pts[2 * i + 1] = (float) f.points().get(i)[1];
                        }
                        fronts.add(new WeatherMapPayloads.FrontLine(f.type().ordinal(), ws.hemisphere,
                                (float) f.strength(), pts));
                    }
                }
                double ts = jet.params().trackSpacing();
                int kMin = (int) Math.floor((cz - half) / ts) - 1;
                int kMax = (int) Math.ceil((cz + half) / ts) + 1;
                for (int track = kMin; track <= kMax && jets.size() < 64; track++) {
                    float[] pts = new float[JET_POINTS * 2];
                    for (int i = 0; i < JET_POINTS; i++) {
                        double x = cx - half + i * 2 * half / (JET_POINTS - 1);
                        pts[2 * i] = (float) x;
                        pts[2 * i + 1] = (float) jet.trackZ(track, x, seconds, season);
                    }
                    jets.add(pts);
                }
            }
            return new WeatherMapPayloads.Answer(cx, cz, s, n, now, mean, humidity, surface, pressure, anomaly,
                    moisture, relative, rain, ARROWS, windX, windZ, markers, fronts, jets, info(level));
        }
    }

    /** The world's moment, for the map's header and the commands. */
    public static String info(ServerLevel level) {
        double year = SeasonSource.yearFraction(level);
        long day = level.getDayTime();
        int hour = (int) ((6 + Math.floorMod(day, 24000L) / 1000.0) % 24);
        int minute = (int) (Math.floorMod(day, 1000L) * 60 / 1000);
        String season = SeasonSource.available()
                ? String.format(Locale.ROOT, "%s (%.0f%% through the year, season factor %+.2f)",
                SeasonSource.describe(level), 100 * year, ClimateCurves.season(year))
                : SeasonSource.describe(level);
        String mode = WeatherConfig.airMassSource() == WeatherConfig.AirMassSource.BANDS
                ? String.format(Locale.ROOT, "bands every %d blocks, +/-%.0f C at %.0f%%", WeatherConfig.bandPeriod(),
                WeatherConfig.bandAmplitude(), 100 * WeatherConfig.bandWeight())
                : "biome air masses";
        return String.format(Locale.ROOT, "Season: %s. Time %02d:%02d. Air masses: %s.", season, hour, minute, mode);
    }

    private WeatherMapBuilder() {
    }
}
