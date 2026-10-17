package dev.brights0ng.meteodyne.climate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.DoubleSupplier;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Split seam S11: the built-in calendar, and the order {@link SeasonSource} asks in (debug override, then a registered
 * provider, then the built-in calendar). Unit tests run without Minecraft, so they can't make a level or a
 * {@link SeasonProvider}; the order is checked on {@link SeasonSource}'s level-free core, with stand-in answers for the
 * provider and the calendar.
 */
class SeasonSourceTest {

    private static final long YEAR = BuiltinCalendar.DEFAULT_YEAR_TICKS;
    private static final BuiltinCalendar ON = BuiltinCalendar.DEFAULT;
    private static final BuiltinCalendar OFF = new BuiltinCalendar(false, YEAR);
    private static final DoubleSupplier BROKEN = () -> {
        throw new IllegalStateException("broken");
    };
    private static final Consumer<Throwable> NO_WARNING = e -> {
        throw new AssertionError("unexpected failure", e);
    };

    @AfterEach
    void reset() {
        SeasonSource.clearOverride();
    }

    @Test
    void theDefaultYearIsTwelveSubSeasonsOfSixDays() {
        assertEquals(12 * 6 * 24000L, YEAR);
        assertEquals(YEAR, SeasonSource.DEFAULT_YEAR_TICKS);
        assertTrue(ON.enabled());
        assertEquals(YEAR, ON.yearTicks());
    }

    @Test
    void theYearStartsAtDayTimeZero() {
        assertEquals(0.0, ON.yearFraction(0), 1e-12);
        assertEquals(1.0 / YEAR, ON.yearFraction(1), 1e-15);
        assertEquals("built-in calendar: day 1 of 72", ON.describe(0));
    }

    @Test
    void aQuarterOfTheYearIsAQuarterThrough() {
        assertEquals(0.25, ON.yearFraction(YEAR / 4), 1e-12);
        assertEquals(0.5, ON.yearFraction(YEAR / 2), 1e-12);
        assertEquals(0.75, ON.yearFraction(3 * YEAR / 4), 1e-12);
        assertEquals("built-in calendar: day 19 of 72", ON.describe(YEAR / 4));
    }

    @Test
    void theYearWrapsAround() {
        assertEquals(0.0, ON.yearFraction(YEAR), 1e-12);
        assertEquals(0.25, ON.yearFraction(YEAR + YEAR / 4), 1e-12);
        assertEquals(0.25, ON.yearFraction(7 * YEAR + YEAR / 4), 1e-12);
        assertEquals((YEAR - 1.0) / YEAR, ON.yearFraction(YEAR - 1), 1e-12);
        assertEquals("built-in calendar: day 1 of 72", ON.describe(YEAR));
        // Day time can't go below zero in game, but the calendar stays in 0-1 if it does.
        assertEquals(0.75, ON.yearFraction(-YEAR / 4), 1e-12);
    }

    @Test
    void theConfiguredYearLengthSetsTheCalendar() {
        BuiltinCalendar eightDays = new BuiltinCalendar(true, 8 * 24000L);
        assertEquals(0.25, eightDays.yearFraction(2 * 24000L), 1e-12);
        assertEquals(0.0, eightDays.yearFraction(8 * 24000L), 1e-12);
        assertEquals("built-in calendar: day 3 of 8", eightDays.describe(2 * 24000L));
        assertEquals(8 * 24000L, SeasonSource.resolveYearTicks(false, null, eightDays));
        assertThrows(IllegalArgumentException.class, () -> new BuiltinCalendar(true, 0));
    }

    @Test
    void switchedOffThereAreNoSeasons() {
        assertTrue(Double.isNaN(OFF.yearFraction(YEAR / 4)));
        assertTrue(Double.isNaN(
                SeasonSource.resolveFraction(Double.NaN, null, () -> OFF.yearFraction(YEAR / 4), NO_WARNING)));
        assertEquals(0, SeasonSource.resolveYearTicks(false, null, OFF));
        assertTrue(OFF.describe(0).startsWith("none"));
    }

    @Test
    void withNothingRegisteredTheBuiltinCalendarRunsTheYear() {
        assertFalse(SeasonSource.hasProvider(), "Meteodyne registers no provider of its own");
        assertEquals(0.25,
                SeasonSource.resolveFraction(Double.NaN, null, () -> ON.yearFraction(YEAR / 4), NO_WARNING), 1e-12);
        assertEquals(YEAR, SeasonSource.resolveYearTicks(false, null, ON));
        assertEquals("calendar", SeasonSource.resolveDescription(Double.NaN, null, () -> "calendar"));
    }

    @Test
    void aProviderReplacesTheCalendar() {
        assertEquals(0.6, SeasonSource.resolveFraction(Double.NaN, () -> 0.6, () -> 0.25, NO_WARNING), 1e-12);
        assertEquals(0.6, SeasonSource.resolveFraction(Double.NaN, () -> 0.6, () -> Double.NaN, NO_WARNING), 1e-12,
                "even with the calendar off");
        assertEquals(1_000_000, SeasonSource.resolveYearTicks(false, () -> 1_000_000, ON));
        assertEquals("provider", SeasonSource.resolveDescription(Double.NaN, () -> "provider", () -> "calendar"));
    }

    @Test
    void aProviderThatCantTellMeansNoSeasonsNotTheCalendar() {
        assertTrue(Double.isNaN(SeasonSource.resolveFraction(Double.NaN, () -> Double.NaN, () -> 0.25, NO_WARNING)));
        assertEquals(0, SeasonSource.resolveYearTicks(false, () -> 0, ON));
    }

    @Test
    void aFailingProviderMeansNoSeasons() {
        List<Throwable> failures = new ArrayList<>();
        assertTrue(Double.isNaN(SeasonSource.resolveFraction(Double.NaN, BROKEN, () -> 0.25, failures::add)));
        assertEquals(1, failures.size(), "the failure is reported");
        assertEquals("unknown", SeasonSource.resolveDescription(Double.NaN, () -> {
            throw new IllegalStateException("broken");
        }, () -> "calendar"));
        assertEquals(0, SeasonSource.resolveYearTicks(false, () -> {
            throw new IllegalStateException("broken");
        }, ON));
    }

    @Test
    void theDebugOverrideComesFirst() {
        SeasonSource.setOverride(1.3);
        assertTrue(SeasonSource.overridden());
        double o = SeasonSource.override();
        assertEquals(0.3, o, 1e-12, "wrapped into the year");
        assertEquals(0.3, SeasonSource.resolveFraction(o, null, () -> 0.25, NO_WARNING), 1e-12, "over the calendar");
        assertEquals(0.3, SeasonSource.resolveFraction(o, null, () -> Double.NaN, NO_WARNING), 1e-12,
                "with no seasons at all");
        assertEquals(0.3, SeasonSource.resolveFraction(o, () -> 0.6, () -> 0.25, NO_WARNING), 1e-12, "over a provider");
        assertEquals(0.3, SeasonSource.resolveFraction(o, BROKEN, () -> 0.25, NO_WARNING), 1e-12,
                "without asking the provider");
        assertTrue(SeasonSource.resolveDescription(o, () -> "provider", () -> "calendar").startsWith("overridden: 30.0%"));
        assertEquals(YEAR, SeasonSource.resolveYearTicks(true, null, OFF), "the run gets a year to move through");
        assertEquals(YEAR, SeasonSource.resolveYearTicks(true, () -> 0, ON), "a provider's unknown year: the default");

        SeasonSource.clearOverride();
        assertFalse(SeasonSource.overridden());
        assertTrue(Double.isNaN(SeasonSource.override()));
        assertEquals(0.6, SeasonSource.resolveFraction(SeasonSource.override(), () -> 0.6, () -> 0.25, NO_WARNING), 1e-12,
                "the provider again");
    }
}
