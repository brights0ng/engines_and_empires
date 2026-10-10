package dev.brights0ng.enginesandempires.weather.forecast;

import java.util.List;
import java.util.Locale;

import dev.brights0ng.enginesandempires.weather.rain.Precip;

/**
 * Checking forecasts against what happened (phase 7c, debug only). Pure.
 *
 * <h2>The truth</h2>
 * The live weather read hourly through the same lens as the forecast ({@link ForecastRun#observe}): the same nine spots,
 * the same chance maths. So a period's <b>observed chance</b> is what the forecast would have said had it known the
 * weather exactly; forecast and truth differ only by the forecast's errors (drift, re-rolled newcomers, the field's
 * edge, coarser steps), never by a difference in how they are measured. (Individual clouds are not used: they are
 * random placements of what the diagnostics want, and they aren't simulated during {@code /eae weather step}.)
 *
 * <h2>The scores, per product and lead (day forecast by period 1-4, longer forecast by day 1-4)</h2>
 * <ul>
 *   <li><b>Temperature:</b> mean absolute error of the period's high and low, F.</li>
 *   <li><b>Precipitation:</b> Brier score, the mean of (forecast chance - observed chance)^2, 0 perfect; its skill
 *       against always forecasting the average observed chance (1 perfect, 0 no better than that, negative worse); and
 *       hits, misses, false alarms and correct "dry"s, counting a period as wet at 50% or more.</li>
 *   <li><b>Kind:</b> how often the forecast named the kind that fell, when both called it wet.</li>
 *   <li><b>Wind:</b> mean error of the mean wind, m/s; of its direction, degrees (when both blow at 2 m/s or more).</li>
 * </ul>
 */
public final class ForecastVerify {

    /** A period counts as checked only with at least this share of its hours observed. */
    static final double COVERAGE = 0.75;
    static final double WET = 0.5;
    static final double DIRECTION_WIND = 2;

    /** One part of one forecast set against what happened. */
    public record Check(Forecast.Product product, int lead, String label, long issued, long start, long end,
                        int hours, double fMin, double fMax, double oMin, double oMax, double fChance,
                        double oChance, Precip fKind, Precip oKind, double fWind, double oWind, double fFrom,
                        double oFrom) {

        public static final String CSV_HEADER = "product,lead,label,issued,start,end,hours,f_low_F,f_high_F,o_low_F,"
                + "o_high_F,f_chance,o_chance,f_kind,o_kind,f_wind,o_wind,f_from,o_from";

        public String csv() {
            return String.format(Locale.ROOT, "%s,%d,\"%s\",%d,%d,%d,%d,%d,%d,%d,%d,%.3f,%.3f,%s,%s,%.2f,%.2f,%.0f,%.0f",
                    product.id, lead, label, issued, start, end, hours, ForecastText.fahrenheit(fMin),
                    ForecastText.fahrenheit(fMax), ForecastText.fahrenheit(oMin), ForecastText.fahrenheit(oMax),
                    fChance, oChance, fKind == null ? "" : fKind.name().toLowerCase(Locale.ROOT),
                    oKind == null ? "" : oKind.name().toLowerCase(Locale.ROOT), fWind, oWind, fFrom, oFrom);
        }
    }

    /**
     * What happened over [start, end) from the hourly observations {@code hours} (any order), or null if fewer than
     * {@value #COVERAGE} of its hours were observed.
     */
    public static Forecast.Outlook observed(List<ForecastRun.Observation> hours, long start, long end) {
        ForecastChance.Bucket b = null;
        int n = 0;
        for (ForecastRun.Observation o : hours) {
            if (o.time() < start || o.time() >= end) {
                continue;
            }
            if (b == null) {
                b = new ForecastChance.Bucket(o.points().length);
            }
            b.add(o.points(), o.wx(), o.wz(), o.pressure(), 1);
            n++;
        }
        double expected = Math.max(1, (end - start) / 1000.0);
        if (b == null || n < COVERAGE * expected) {
            return null;
        }
        return b.finish("observed", start, end);
    }

    /** How many hourly observations fall in [start, end). */
    public static int count(List<ForecastRun.Observation> hours, long start, long end) {
        int n = 0;
        for (ForecastRun.Observation o : hours) {
            if (o.time() >= start && o.time() < end) {
                n++;
            }
        }
        return n;
    }

    /** Part {@code index} of {@code f} against {@code observed} ({@code hours} observed). */
    public static Check check(Forecast f, int index, Forecast.Outlook observed, int hours) {
        Forecast.Outlook p = f.parts().get(index);
        return new Check(f.product(), index + 1, p.label(), f.issued(), p.start(), p.end(), hours, p.tMin(), p.tMax(),
                observed.tMin(), observed.tMax(), p.chance(), observed.chance(), p.kind(), observed.kind(),
                p.windSpeed(), observed.windSpeed(), p.windFrom(), observed.windFrom());
    }

    /** The smallest angle between two bearings, degrees. */
    static double angle(double a, double b) {
        double d = Math.abs(((a - b) % 360 + 540) % 360 - 180);
        return d;
    }

    /** Running scores for one product and lead. */
    public static final class Tally {
        int n;
        double tempErr;
        double brier;
        double obsSum;
        double obsSq;
        int hits;
        int misses;
        int falseAlarms;
        int dryRight;
        int kindChecked;
        int kindRight;
        double windErr;
        int windN;
        double dirErr;
        int dirN;

        public void add(Check c) {
            n++;
            tempErr += (Math.abs(c.fMax() - c.oMax()) + Math.abs(c.fMin() - c.oMin())) / 2 * 9 / 5;
            double d = c.fChance() - c.oChance();
            brier += d * d;
            obsSum += c.oChance();
            obsSq += c.oChance() * c.oChance();
            boolean fWet = c.fChance() >= WET;
            boolean oWet = c.oChance() >= WET;
            if (fWet && oWet) {
                hits++;
            } else if (oWet) {
                misses++;
            } else if (fWet) {
                falseAlarms++;
            } else {
                dryRight++;
            }
            if (fWet && oWet && c.fKind() != null && c.oKind() != null) {
                kindChecked++;
                if (c.fKind() == c.oKind()) {
                    kindRight++;
                }
            }
            windErr += Math.abs(c.fWind() - c.oWind());
            windN++;
            if (c.fWind() >= DIRECTION_WIND && c.oWind() >= DIRECTION_WIND && Double.isFinite(c.fFrom())
                    && Double.isFinite(c.oFrom())) {
                dirErr += angle(c.fFrom(), c.oFrom());
                dirN++;
            }
        }

        public int count() {
            return n;
        }

        public double temperatureError() {
            return n == 0 ? Double.NaN : tempErr / n;
        }

        public double brier() {
            return n == 0 ? Double.NaN : brier / n;
        }

        /** Brier skill against always forecasting the mean observed chance (NaN when the truth never varied). */
        public double skill() {
            if (n == 0) {
                return Double.NaN;
            }
            double mean = obsSum / n;
            double reference = obsSq / n - mean * mean;
            return reference < 1e-6 ? Double.NaN : 1 - brier() / reference;
        }

        /** One line. */
        public String describe() {
            if (n == 0) {
                return "nothing checked yet";
            }
            String skill = Double.isNaN(skill()) ? "n/a" : String.format(Locale.ROOT, "%+.2f", skill());
            String kinds = kindChecked == 0 ? "" : String.format(Locale.ROOT, "; kind right %d/%d", kindRight,
                    kindChecked);
            String dir = dirN == 0 ? "" : String.format(Locale.ROOT, ", %.0f°", dirErr / dirN);
            return String.format(Locale.ROOT, "%d checked; temp off %.1f°F; precip Brier %.3f (skill %s), hits %d, "
                            + "misses %d, false alarms %d, dry right %d%s; wind off %.1f m/s%s", n, temperatureError(),
                    brier(), skill, hits, misses, falseAlarms, dryRight, kinds, windErr / Math.max(1, windN), dir);
        }
    }

    private ForecastVerify() {
    }
}
