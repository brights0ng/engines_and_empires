package dev.brights0ng.enginesandempires.weather.forecast;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import dev.brights0ng.enginesandempires.weather.rain.Precip;

/**
 * How a forecast reads (phase 7b; Bright, 2026-10-09): temperatures in F, wind in m/s with a word. Pure.
 *
 * <ul>
 *   <li><b>Wind words</b> (a simplified Beaufort scale): calm under 0.5 m/s, light to 3.4, breezy to 8, windy to 14,
 *       strong to 21, gale to 28.5, storm beyond.</li>
 *   <li><b>Sky:</b> clear under 15% cover, partly cloudy to 45%, mostly cloudy to 80%, overcast beyond.</li>
 *   <li><b>Pressure trend</b> over the day: rising or falling past 2 hPa, otherwise steady.</li>
 * </ul>
 */
public final class ForecastText {

    static final double[] WIND_LIMITS = {0.5, 3.4, 8, 14, 21, 28.5};
    static final String[] WIND_WORDS = {"calm", "light", "breezy", "windy", "strong", "gale", "storm"};

    /** C to F, rounded to whole degrees. */
    public static long fahrenheit(double celsius) {
        return Math.round(celsius * 9 / 5 + 32);
    }

    /** The word for a wind of {@code mps}. */
    public static String windWord(double mps) {
        for (int i = 0; i < WIND_LIMITS.length; i++) {
            if (mps < WIND_LIMITS[i]) {
                return WIND_WORDS[i];
            }
        }
        return WIND_WORDS[WIND_WORDS.length - 1];
    }

    /** The 8-point compass name of a bearing (degrees clockwise from north). */
    public static String compass(double degrees) {
        String[] names = {"N", "NE", "E", "SE", "S", "SW", "W", "NW"};
        return names[(int) Math.round(((degrees % 360) + 360) % 360 / 45) % 8];
    }

    public static String sky(double cover) {
        return cover < 0.15 ? "Clear" : cover < 0.45 ? "Partly cloudy" : cover < 0.8 ? "Mostly cloudy" : "Overcast";
    }

    /** What falls, in words. */
    public static String kind(Precip kind) {
        return switch (kind) {
            case RAIN -> "rain";
            case MIXED -> "rain and snow";
            case SNOW -> "snow";
            case SLEET -> "sleet";
            case FREEZING_RAIN -> "freezing rain";
            case HAIL -> "hail";
        };
    }

    public static String intensity(double strength) {
        return strength < 0.25 ? "light" : strength < 0.6 ? "moderate" : "heavy";
    }

    public static String trend(double start, double end) {
        double d = end - start;
        return d > 2 ? "rising" : d < -2 ? "falling" : "steady";
    }

    /** The precipitation part: "Dry" or "60% chance of moderate rain". */
    static String precipitation(Forecast.Outlook o) {
        if (o.kind() == null) {
            return "Dry";
        }
        int pct = (int) (Math.round(o.chance() * 10) * 10);
        String what = intensity(o.intensity()) + " " + kind(o.kind());
        if (pct >= 90) {
            return capitalise(what) + " likely";
        }
        return String.format(Locale.ROOT, "%d%% chance of %s", Math.max(10, pct), what);
    }

    static String wind(Forecast.Outlook o) {
        String word = windWord(o.windSpeed());
        if (Double.isNaN(o.windFrom()) || o.windSpeed() < WIND_LIMITS[0]) {
            return "Wind calm";
        }
        String s = String.format(Locale.ROOT, "Wind %s %.0f m/s (%s)", compass(o.windFrom()), o.windSpeed(), word);
        if (o.gust() >= o.windSpeed() + 4) {
            s += String.format(Locale.ROOT, ", gusts %.0f", o.gust());
        }
        return s;
    }

    static String events(Forecast.Outlook o) {
        if (o.events().isEmpty()) {
            return "";
        }
        List<String> words = new ArrayList<>();
        for (Forecast.Event e : o.events()) {
            words.add(e.words);
        }
        return " " + capitalise(String.join(", ", words)) + " possible.";
    }

    /** One line per part. */
    public static List<String> lines(Forecast f) {
        List<String> out = new ArrayList<>();
        for (Forecast.Outlook o : f.parts()) {
            if (f.product() == Forecast.Product.TODAY) {
                out.add(String.format(Locale.ROOT, "%s: %s. %s. %d-%d°F. %s.%s", o.label(), sky(o.cover()),
                        precipitation(o), fahrenheit(o.tMin()), fahrenheit(o.tMax()), wind(o), events(o)));
            } else {
                out.add(String.format(Locale.ROOT, "%s: High %d°F, low %d°F. %s, %s. %s. Pressure %s.%s", o.label(),
                        fahrenheit(o.tMax()), fahrenheit(o.tMin()), sky(o.cover()),
                        uncapitalise(precipitation(o)), wind(o), trend(o.pressureStart(), o.pressureEnd()), events(o)));
            }
        }
        return out;
    }

    /** The heading line. */
    public static String heading(Forecast f) {
        return String.format(Locale.ROOT, "%s forecast for the area around %.0f, %.0f:",
                f.product() == Forecast.Product.TODAY ? "Today's" : f.parts().size() + "-day", f.x(), f.z());
    }

    static String capitalise(String s) {
        return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    static String uncapitalise(String s) {
        return s.isEmpty() ? s : Character.toLowerCase(s.charAt(0)) + s.substring(1);
    }

    private ForecastText() {
    }
}
