package dev.brights0ng.enginesandempires.weather.forecast;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

import dev.brights0ng.enginesandempires.weather.cloud.CloudType;
import dev.brights0ng.enginesandempires.weather.cloud.sim.CloudDiagnostics;
import dev.brights0ng.enginesandempires.weather.cloud.sim.CloudRain;
import dev.brights0ng.enginesandempires.weather.rain.Precip;
import dev.brights0ng.enginesandempires.weather.rain.RainModel;

/**
 * Turns what the sky would hold into the chance of something falling (phase 7b). Pure.
 *
 * <h2>At one spot, one moment ({@link #point})</h2>
 * The forecast never places clouds; it reads what the cloud spawner would want ({@link CloudDiagnostics.Need}):
 * <ul>
 *   <li><b>Layer cloud</b> (nimbostratus, altostratus, stratus, stratocumulus) rains as a sheet: the share of the spot
 *       under rain is its cover times its rain core, at the strength {@link CloudRain} gives the type in that air.</li>
 *   <li><b>Heap cloud</b> showers: only where a shower is, so the share under rain is the heap cover times the core's
 *       area (a core is about half a cloud's radius, so about a quarter of its area), and only 60% of congestus shower.
 *       Thunderstorms are cumulonimbus; hail comes from the strongest (capillatus raining hard), as live.</li>
 *   <li>Dry country thins rain as live does ({@link RainModel#wetness}).</li>
 * </ul>
 *
 * <h2>Over a period ({@link Bucket})</h2>
 * <ul>
 *   <li>A <b>sheet</b> passing over wets the whole region, so its contribution is the largest share seen during the
 *       period: half a period under a full nimbostratus band reads about 90%, not 50%.</li>
 *   <li><b>Showers</b> come and go: the chance of staying dry is the product of the dry shares, each counted once per
 *       {@value #HEAP_RENEW_HOURS} in-game hours (about how long a shower field takes to renew over a spot as storms
 *       drift and are born and die).</li>
 *   <li>Region chance = the mean of its sample spots' chances.</li>
 * </ul>
 */
public final class ForecastChance {

    /** In-game hours over which a spot's showers renew (independent chances of being hit). */
    static final double HEAP_RENEW_HOURS = 3;
    /** Chances below this read as dry and name no kind. */
    static final double DRY = 0.05;
    /** Share of cumulus congestus that shower ({@code CloudRain.showers}). */
    static final double CONGESTUS_SHOWERS = 0.6;
    static final double BLIZZARD_WIND = 13;
    static final double BLIZZARD_SNOW = 0.45;
    static final double GALE_GUST = 21;
    static final double GALE_WIND = 17;

    /**
     * What falls (or would) at one spot at one moment.
     *
     * @param cover         total sky cover, 0-1
     * @param layerWet      share of the spot under sheet rain, 0-1
     * @param layerStrength the sheet rain's strength, 0-1
     * @param heapWet       share of the spot under a shower, 0-1
     * @param heapStrength  a shower's strength, 0-1
     * @param thunder       whether the showers are thunderstorms
     * @param hail          whether the thunderstorms are strong enough for hail
     * @param kind          what falls, from the temperature on the way down
     * @param groundT       the air temperature at the ground, C
     * @param melt          the warm layer aloft's melt index ({@code WarmNose}; 7d re-decides the kind at a spot's
     *                      corrected temperature with it)
     * @param coldDepth     the cold layer's depth under it, 0-1
     */
    public record Point(double cover, double layerWet, double layerStrength, double heapWet, double heapStrength,
                        boolean thunder, boolean hail, Precip kind, double groundT, double melt, double coldDepth) {

        public Point(double cover, double layerWet, double layerStrength, double heapWet, double heapStrength,
                     boolean thunder, boolean hail, Precip kind, double groundT) {
            this(cover, layerWet, layerStrength, heapWet, heapStrength, thunder, hail, kind, groundT, 0, 0);
        }

        /** This reading at a ground temperature {@code offset} C warmer (colder if negative), its kind decided anew. */
        public Point shifted(double offset) {
            if (offset == 0) {
                return this;
            }
            double t = groundT + offset;
            return new Point(cover, layerWet, layerStrength, heapWet, heapStrength, thunder, hail,
                    Precip.decide(t, melt, coldDepth), t, melt, coldDepth);
        }

        public double wet() {
            return 1 - (1 - layerWet) * (1 - heapWet);
        }

        /** The strength of what falls, weighted by how much of each kind of rain there is. */
        public double strength() {
            double w = layerWet + heapWet;
            return w <= 0 ? 0 : (layerWet * layerStrength + heapWet * heapStrength) / w;
        }
    }

    /**
     * Reads a spot: {@code need} from the diagnostics, the ground temperature {@code groundT} (C), the warm layer aloft
     * {@code melt} and the cold layer's depth {@code coldDepth} ({@code WarmNose}), and the air's humidity (dry
     * country).
     */
    public static Point point(CloudDiagnostics.Need need, double groundT, double melt, double coldDepth,
                              double humidity) {
        double wetness = RainModel.wetness(humidity);
        double layerDry = 1;
        double layerStrength = 0;
        double low = 0;
        double mid = 0;
        double high = 0;
        for (CloudType t : CloudType.values()) {
            if (!t.layer()) {
                continue;
            }
            double cover = need.cover(t);
            switch (t.deck) {
                case LOW -> low = Math.max(low, cover);
                case MID -> mid = Math.max(mid, cover);
                case HIGH -> high = Math.max(high, cover);
            }
            if (!t.rain.rains() || cover <= 0) {
                continue;
            }
            double s = t.rain.peak() * CloudRain.precipitation(t, need, false) * wetness;
            if (s <= RainModel.MIN) {
                continue;
            }
            layerDry *= 1 - Math.min(1, cover * t.rain.core());
            layerStrength = Math.max(layerStrength, s);
        }
        double heapWet = 0;
        double heapStrength = 0;
        boolean thunder = false;
        boolean hail = false;
        CloudType heap = need.heapType();
        if (heap != null && heap.rain.rains() && need.heapCover() > 0) {
            double precipitation = CloudRain.precipitation(heap, need, true);
            double s = heap.rain.peak() * precipitation * wetness;
            if (s > RainModel.MIN) {
                double share = heap == CloudType.CUMULUS_CONGESTUS ? CONGESTUS_SHOWERS : 1;
                heapWet = Math.min(1, need.heapCover() * share * heap.rain.core() * heap.rain.core());
                heapStrength = s;
                thunder = heap.thunder;
                // As live (RainModel.hailAt): thunderstorms with strong convection, raining hard in their core.
                double lightning = CloudRain.lightning(heap, precipitation);
                hail = thunder && lightning >= 0.35 && heap.rain.peak() * precipitation >= 0.55;
            }
        }
        double heapCover = need.heapType() == null ? 0 : need.heapCover();
        double cover = 1 - (1 - low) * (1 - mid) * (1 - 0.6 * high) * (1 - heapCover);
        Precip kind = Precip.decide(groundT, melt, coldDepth);
        return new Point(clamp01(cover), 1 - layerDry, layerStrength, heapWet, heapStrength, thunder, hail, kind,
                groundT, melt, coldDepth);
    }

    /** Everything seen over one period or day, gathered step by step. */
    public static final class Bucket {
        private final int spots;
        private final double[] layerMax;
        private final double[] heapDry;
        private final double[] thunderDry;
        private final boolean[] started;
        private int steps;
        private double coverSum;
        private double tMin = Double.POSITIVE_INFINITY;
        private double tMax = Double.NEGATIVE_INFINITY;
        private double windX;
        private double windZ;
        private double windSpeedSum;
        private double windMax;
        private double pressureStart = Double.NaN;
        private double pressureEnd = Double.NaN;
        private double wetWeight;
        private double strengthWeight;
        private final Map<Precip, Double> kinds = new EnumMap<>(Precip.class);
        private double hailWeight;
        private boolean blizzard;

        public Bucket(int spots) {
            this.spots = spots;
            this.layerMax = new double[spots];
            this.heapDry = new double[spots];
            this.thunderDry = new double[spots];
            this.started = new boolean[spots];
            java.util.Arrays.fill(heapDry, 1);
            java.util.Arrays.fill(thunderDry, 1);
        }

        /**
         * Adds one step: {@code points} at the sample spots, the region's surface wind {@code wx, wz} (m/s) and
         * pressure (hPa), and how long the step stands for, in-game hours.
         */
        public void add(Point[] points, double wx, double wz, double pressure, double hours) {
            double cover = 0;
            double t = 0;
            for (int i = 0; i < spots; i++) {
                Point p = points[i];
                cover += p.cover();
                t += p.groundT();
                layerMax[i] = Math.max(layerMax[i], p.layerWet());
                double weight = started[i] ? hours / HEAP_RENEW_HOURS : 1;
                heapDry[i] *= Math.pow(1 - p.heapWet(), weight);
                if (p.thunder()) {
                    thunderDry[i] *= Math.pow(1 - p.heapWet(), weight);
                }
                started[i] = true;
                double wet = p.wet();
                if (wet > 0) {
                    double s = p.strength();
                    wetWeight += wet;
                    strengthWeight += wet * s;
                    kinds.merge(p.kind(), wet * Math.max(0.05, s), Double::sum);
                    if (p.hail()) {
                        hailWeight += p.heapWet();
                    }
                    if (p.kind() == Precip.SNOW && s >= BLIZZARD_SNOW && Math.hypot(wx, wz) >= BLIZZARD_WIND
                            && wet >= 0.3) {
                        blizzard = true;
                    }
                }
            }
            coverSum += cover / spots;
            t /= spots;
            tMin = Math.min(tMin, t);
            tMax = Math.max(tMax, t);
            double speed = Math.hypot(wx, wz);
            windX += wx;
            windZ += wz;
            windSpeedSum += speed;
            windMax = Math.max(windMax, speed);
            if (Double.isNaN(pressureStart)) {
                pressureStart = pressure;
            }
            pressureEnd = pressure;
            steps++;
        }

        public boolean empty() {
            return steps == 0;
        }

        /** The chance something falls in the region over the period, 0-1. */
        public double chance() {
            double sum = 0;
            for (int i = 0; i < spots; i++) {
                sum += 1 - (1 - layerMax[i]) * heapDry[i];
            }
            return clamp01(sum / spots);
        }

        /** The chance of a thunderstorm in the region over the period, 0-1. */
        public double thunderChance() {
            double sum = 0;
            for (int i = 0; i < spots; i++) {
                sum += 1 - thunderDry[i];
            }
            return clamp01(sum / spots);
        }

        /** The finished outlook. */
        public Forecast.Outlook finish(String label, long start, long end) {
            double chance = chance();
            double thunder = thunderChance();
            Precip kind = null;
            double best = 0;
            for (Map.Entry<Precip, Double> e : kinds.entrySet()) {
                if (e.getValue() > best) {
                    best = e.getValue();
                    kind = e.getKey();
                }
            }
            if (chance < DRY) {
                kind = null;
            }
            double intensity = wetWeight <= 0 ? 0 : strengthWeight / wetWeight;
            double mean = steps == 0 ? 0 : windSpeedSum / steps;
            double from = Math.hypot(windX, windZ) < 1e-6 * Math.max(1, steps) ? Double.NaN : fromDegrees(windX, windZ);
            double gust = windMax * 1.3 + (thunder >= 0.3 ? 6 : 0);
            Set<Forecast.Event> events = EnumSet.noneOf(Forecast.Event.class);
            if (thunder >= 0.2) {
                events.add(Forecast.Event.THUNDERSTORM);
            }
            if (hailWeight > 0 && thunder >= 0.2) {
                events.add(Forecast.Event.HAIL);
            }
            if (blizzard && chance >= 0.3) {
                events.add(Forecast.Event.BLIZZARD);
            }
            double total = 0;
            for (double v : kinds.values()) {
                total += v;
            }
            if (chance >= 0.2 && total > 0 && kinds.getOrDefault(Precip.FREEZING_RAIN, 0.0) / total >= 0.25) {
                events.add(Forecast.Event.FREEZING_RAIN);
            }
            if (gust >= GALE_GUST || windMax >= GALE_WIND) {
                events.add(Forecast.Event.GALE);
            }
            return new Forecast.Outlook(label, start, end, tMin, tMax, steps == 0 ? 0 : coverSum / steps, chance, kind,
                    intensity, mean, from, gust, pressureStart, pressureEnd, thunder, events);
        }
    }

    /**
     * The compass bearing a wind blowing toward (wx, wz) comes from, degrees clockwise from north (Minecraft's -z is
     * north, +x east).
     */
    public static double fromDegrees(double wx, double wz) {
        double deg = Math.toDegrees(Math.atan2(-wx, wz));
        return (deg % 360 + 360) % 360;
    }

    static double clamp01(double v) {
        return v < 0 ? 0 : Math.min(1, v);
    }

    private ForecastChance() {
    }
}
