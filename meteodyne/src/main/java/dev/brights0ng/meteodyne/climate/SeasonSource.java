package dev.brights0ng.meteodyne.climate;

import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.DoubleSupplier;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

import dev.brights0ng.meteodyne.Meteodyne;
import dev.brights0ng.meteodyne.rain.WeatherConfig;
import net.minecraft.world.level.Level;

/**
 * Where in the year it is. In order: the debug override (below), else a registered {@link SeasonProvider} (the pack
 * registers Serene Seasons' calendar when it is installed), else the {@link BuiltinCalendar} (split seam S11), which the
 * config can switch off for no seasons at all (a neutral year: every day is the year's mean).
 *
 * <h2>Debug override (phase 8a)</h2>
 * The climatology run ({@code weather/forecast/Climatology}) runs the weather through a whole year without moving the
 * calendar, so it sets a year position here that replaces the calendar everywhere the pack reads the season (climate
 * normals, weather systems, field, forecasts). Not saved; the run clears it when it ends and on server stop.
 */
public final class SeasonSource {

    /** The year's length while overridden without a calendar of known length: the built-in calendar's default. */
    public static final long DEFAULT_YEAR_TICKS = BuiltinCalendar.DEFAULT_YEAR_TICKS;

    private static volatile SeasonProvider provider;
    private static boolean warned;
    /** The overriding year position, 0-1, or NaN when the calendar applies. */
    private static volatile double override = Double.NaN;

    /** Makes {@code seasons} the calendar, in place of the built-in one. Call at mod setup; the last one registered wins. */
    public static void register(SeasonProvider seasons) {
        provider = Objects.requireNonNull(seasons);
    }

    /** Whether there are seasons: a provider is registered, or the built-in calendar runs. */
    public static boolean available() {
        return provider != null || WeatherConfig.calendar().enabled();
    }

    /** Replaces the calendar with year position {@code fraction} (wrapped into 0-1) until {@link #clearOverride}. */
    public static void setOverride(double fraction) {
        override = fraction - Math.floor(fraction);
    }

    /** Goes back to the calendar. */
    public static void clearOverride() {
        override = Double.NaN;
    }

    /** Whether a debug override replaces the calendar. */
    public static boolean overridden() {
        return !Double.isNaN(override);
    }

    /** How far through the year {@code level} is, 0-1 (0 = start of spring), or NaN without seasons. */
    public static double yearFraction(Level level) {
        SeasonProvider p = provider;
        BuiltinCalendar calendar = WeatherConfig.calendar();
        return resolveFraction(override, p == null ? null : () -> p.yearFraction(level),
                () -> calendar.yearFraction(level.getDayTime()), SeasonSource::warnOnce);
    }

    /** The season's name for debug output. */
    public static String describe(Level level) {
        SeasonProvider p = provider;
        BuiltinCalendar calendar = WeatherConfig.calendar();
        return resolveDescription(override, p == null ? null : () -> p.describe(level),
                () -> calendar.describe(level.getDayTime()));
    }

    /** How long the year is, ticks, or 0 without seasons (forecasts project the season forward with it). */
    public static long yearTicks(Level level) {
        SeasonProvider p = provider;
        return resolveYearTicks(overridden(), p == null ? null : () -> p.yearTicks(level), WeatherConfig.calendar());
    }

    // The order, apart from levels (unit tests run without Minecraft). A null provider means none is registered.

    /**
     * The override if set, else the provider's answer (NaN if it fails; {@code onFailure} hears why), else the built-in
     * calendar's.
     */
    static double resolveFraction(double override, DoubleSupplier provider, DoubleSupplier calendar,
                                  Consumer<Throwable> onFailure) {
        if (!Double.isNaN(override)) {
            return override;
        }
        if (provider == null) {
            return calendar.getAsDouble();
        }
        try {
            return provider.getAsDouble();
        } catch (RuntimeException | LinkageError e) {
            onFailure.accept(e);
            return Double.NaN;
        }
    }

    private static void warnOnce(Throwable e) {
        if (!warned) {
            warned = true;
            Meteodyne.LOGGER.warn("Climate: couldn't read the season provider's calendar; no seasons", e);
        }
    }

    static String resolveDescription(double override, Supplier<String> provider, Supplier<String> calendar) {
        if (!Double.isNaN(override)) {
            return String.format(java.util.Locale.ROOT, "overridden: %.1f%% through the year (climatology run)", 100 * override);
        }
        if (provider == null) {
            return calendar.get();
        }
        try {
            return provider.get();
        } catch (RuntimeException | LinkageError e) {
            return "unknown";
        }
    }

    /** The provider's year, else the calendar's; while overridden, an unknown year is the default one. */
    static long resolveYearTicks(boolean overridden, LongSupplier provider, BuiltinCalendar calendar) {
        if (provider == null) {
            return calendar.enabled() ? calendar.yearTicks() : overridden ? DEFAULT_YEAR_TICKS : 0;
        }
        try {
            long ticks = provider.getAsLong();
            return ticks <= 0 && overridden ? DEFAULT_YEAR_TICKS : ticks;
        } catch (RuntimeException | LinkageError e) {
            return overridden ? DEFAULT_YEAR_TICKS : 0;
        }
    }

    /** Whether a provider is registered (tests). */
    static boolean hasProvider() {
        return provider != null;
    }

    /** The debug override, or NaN (tests). */
    static double override() {
        return override;
    }

    private SeasonSource() {
    }
}
