package dev.brights0ng.enginesandempires.weather.forecast;

import java.util.ArrayList;
import java.util.List;

/**
 * A region's shared forecast run (phase 7d of {@code claude/weather-backbone-phase7.md}): every reading the run made, on
 * a grid across the region, so a forecast can be read for any spot in it ({@link #at}). Pure, immutable once made;
 * this is what the forecaster caches.
 *
 * <h2>Point forecasts (Bright, 2026-10-09)</h2>
 * The shared regions (512 and 2048 blocks) are too coarse for one answer: weather and especially temperature vary
 * with biome and height inside them. So the expensive run is shared, but each answer is for the asker's spot:
 * <ul>
 *   <li><b>Rain, kind, sky:</b> from the 3 x 3 readings around the spot ({@link Forecast.Product#spacing} apart: about
 *       128 blocks for the day forecast, 256 for the 4-day).</li>
 *   <li><b>Temperature:</b> the run's ground temperature there, plus an offset measured when the forecast is asked
 *       for: how much warmer or colder the spot feels now than the run reads it now. That carries the biome underfoot,
 *       the spot's exact height and anything else the 512-block field cells can't resolve. The kind of precipitation is
 *       decided again at the corrected temperature (a mountain top can snow under the same cloud that rains below).</li>
 *   <li><b>Wind and pressure:</b> the region's centre (they vary over thousands of blocks).</li>
 * </ul>
 *
 * @param product the kind of forecast
 * @param x       the region's centre, x
 * @param z       the region's centre, z
 * @param issued  simulation time the run started from
 * @param dayTime Minecraft day time then
 * @param parts   the periods or days
 * @param steps   every reading, in time order
 */
public record ForecastGrid(Forecast.Product product, double x, double z, long issued, long dayTime, List<Part> parts,
                           List<Step> steps) {

    /** One period or day to fill. */
    public record Part(String label, long start, long end) {
    }

    /**
     * One reading of the whole grid ({@link #side()}² spots, row by row from the north-west corner) at {@code time},
     * standing for {@code hours} in-game hours; wind (m/s) and pressure (hPa) at the region's centre.
     */
    public record Step(long time, double hours, ForecastChance.Point[] points, double wx, double wz, double pressure) {
    }

    /** Readings per side: the region and one spacing of margin each way. */
    public static int side(Forecast.Product product) {
        return product.region / product.spacing + 3;
    }

    public int side() {
        return side(product);
    }

    /** World position of grid spot {@code idx} for a region centred on (cx, cz). {x, z} */
    public static double[] spot(Forecast.Product product, double cx, double cz, int idx) {
        int n = side(product);
        double half = (n - 1) / 2.0 * product.spacing;
        return new double[]{cx - half + (idx % n) * product.spacing, cz - half + (idx / n) * product.spacing};
    }

    /** The 9 grid spots around (px, pz) in a region centred on (cx, cz): around the nearest, kept inside the grid. */
    public static int[] neighbourhood(Forecast.Product product, double cx, double cz, double px, double pz) {
        int n = side(product);
        double half = (n - 1) / 2.0 * product.spacing;
        int i0 = clamp((int) Math.round((px - (cx - half)) / product.spacing), 1, n - 2);
        int k0 = clamp((int) Math.round((pz - (cz - half)) / product.spacing), 1, n - 2);
        int[] out = new int[9];
        int m = 0;
        for (int dk = -1; dk <= 1; dk++) {
            for (int di = -1; di <= 1; di++) {
                out[m++] = (k0 + dk) * n + (i0 + di);
            }
        }
        return out;
    }

    /**
     * The forecast for the spot (px, pz), with its temperature {@code offset} C warmer than the run reads it (colder if
     * negative): see the class notes.
     */
    public Forecast at(double px, double pz, double offset) {
        int[] around = neighbourhood(product, x, z, px, pz);
        List<ForecastChance.Bucket> buckets = new ArrayList<>(parts.size());
        for (int i = 0; i < parts.size(); i++) {
            buckets.add(new ForecastChance.Bucket(around.length));
        }
        ForecastChance.Point[] pts = new ForecastChance.Point[around.length];
        for (Step s : steps) {
            int part = partOf(s.time());
            if (part < 0) {
                continue;
            }
            for (int j = 0; j < around.length; j++) {
                pts[j] = s.points()[around[j]].shifted(offset);
            }
            buckets.get(part).add(pts, s.wx(), s.wz(), s.pressure(), s.hours());
        }
        List<Forecast.Outlook> out = new ArrayList<>(parts.size());
        for (int i = 0; i < parts.size(); i++) {
            Part p = parts.get(i);
            out.add(buckets.get(i).finish(p.label(), p.start(), p.end()));
        }
        return new Forecast(product, px, pz, issued, dayTime, List.copyOf(out));
    }

    /** Which part simulation time {@code t} falls in, or -1 (the last part includes its end). */
    int partOf(long t) {
        for (int i = 0; i < parts.size(); i++) {
            Part p = parts.get(i);
            if (t >= p.start() && (t < p.end() || (i == parts.size() - 1 && t == p.end()))) {
                return i;
            }
        }
        return -1;
    }

    private static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }
}
