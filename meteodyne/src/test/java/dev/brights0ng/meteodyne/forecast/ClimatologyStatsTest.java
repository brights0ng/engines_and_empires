package dev.brights0ng.meteodyne.forecast;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import dev.brights0ng.meteodyne.rain.Precip;

class ClimatologyStatsTest {

    private static ClimatologyStats.Hour hour(long day, double year, double t, double wet, Precip kind) {
        return new ClimatologyStats.Hour(day, year, t, wet, kind, false, false, 3);
    }

    @Test
    void seasonsAreSereneSeasonsQuarters() {
        assertEquals(ClimatologyStats.Season.SPRING, ClimatologyStats.Season.of(0));
        assertEquals(ClimatologyStats.Season.SPRING, ClimatologyStats.Season.of(0.249));
        assertEquals(ClimatologyStats.Season.SUMMER, ClimatologyStats.Season.of(0.25));
        assertEquals(ClimatologyStats.Season.AUTUMN, ClimatologyStats.Season.of(0.6));
        assertEquals(ClimatologyStats.Season.WINTER, ClimatologyStats.Season.of(0.99));
        assertEquals(ClimatologyStats.Season.SPRING, ClimatologyStats.Season.of(1.1), "wraps");
        assertEquals(ClimatologyStats.Season.SPRING, ClimatologyStats.Season.of(Double.NaN));
    }

    @Test
    void daysKeepTheirLowsHighsAndWetHours() {
        ClimatologyStats s = new ClimatologyStats();
        for (int h = 0; h < 24; h++) {
            // A cold night, a mild afternoon; rain for four hours at half cover.
            double t = h < 6 ? -3 : h < 15 ? 8 : 4;
            double wet = h >= 10 && h < 14 ? 0.5 : 0;
            s.add(hour(5, 0.1, t, wet, wet > 0 ? Precip.RAIN : null));
        }
        ClimatologyStats.Day d = s.days().get(0);
        assertEquals(24, d.hours());
        assertEquals(-3, d.low(), 1e-9);
        assertEquals(8, d.high(), 1e-9);
        assertEquals(2.0, d.wetHours(), 1e-9);
        assertTrue(d.wet(), "two expected hours of rain make a wet day");
        assertTrue(d.frost());
        assertEquals(2.0, d.kindHours(Precip.RAIN), 1e-9);
        assertEquals(0, d.kindHours(Precip.SNOW), 1e-9);
    }

    @Test
    void summariesSplitBySeasonAndFindTheLongestDrySpell() {
        ClimatologyStats s = new ClimatologyStats();
        // Spring: wet, dry, dry, dry, wet, dry. Summer: one hot dry day.
        boolean[] wetDays = {true, false, false, false, true, false};
        for (int d = 0; d < wetDays.length; d++) {
            for (int h = 0; h < 24; h++) {
                s.add(hour(d, 0.01 * d, 10 + (h % 2), wetDays[d] && h < 3 ? 1 : 0, Precip.RAIN));
            }
        }
        for (int h = 0; h < 24; h++) {
            s.add(hour(100, 0.3, 30, 0, null));
        }
        ClimatologyStats.Summary spring = s.summary(ClimatologyStats.Season.SPRING);
        assertEquals(6, spring.days());
        assertEquals(2, spring.wetDays());
        assertEquals(3, spring.longestDry());
        assertEquals(6.0, spring.wetHours(), 1e-9);
        assertEquals(10.5, spring.mean(), 1e-9);
        assertEquals(0, spring.frostDays());
        ClimatologyStats.Summary summer = s.summary(ClimatologyStats.Season.SUMMER);
        assertEquals(1, summer.days());
        assertEquals(30, summer.warmest(), 1e-9);
        assertEquals(0, s.summary(ClimatologyStats.Season.WINTER).days());
        assertTrue(spring.describe(ClimatologyStats.Season.SPRING).startsWith("Spring (6 d)"));
    }

    @Test
    void anHourFoldsTheNeighbourhoodWithTheSpotsCorrection() {
        // Nine readings: three under heavy snow at -4 C, the rest dry; one thunderstorm with hail.
        ForecastChance.Point[] points = new ForecastChance.Point[9];
        for (int i = 0; i < 9; i++) {
            boolean snowing = i < 3;
            points[i] = new ForecastChance.Point(1, snowing ? 1 : 0, 0.8, 0, 0, false, false,
                    snowing ? Precip.SNOW : null, -4);
        }
        points[8] = new ForecastChance.Point(1, 0, 0, 0.3, 0.9, true, true, Precip.RAIN, -4);
        ClimatologyStats.Hour h = ClimatologyStats.hourOf(points, 2, 3, 4, 7, 0.5);
        assertEquals(-2, h.t(), 1e-9, "the spot's +2 C correction applies");
        assertEquals((3 + 0.3) / 9, h.wet(), 1e-9);
        assertEquals(5, h.wind(), 1e-9);
        assertTrue(h.thunderNear());
        assertEquals(0.3 / 9, h.thunder(), 1e-9, "thunder counts the share under the storm, like wet");
        assertTrue(h.hail());
        assertEquals(7, h.day());
        // -2 C after the correction: what fell is re-decided, and stays snow.
        assertEquals(Precip.SNOW, h.kind());

        ForecastChance.Point dry = new ForecastChance.Point(0.2, 0, 0, 0, 0, false, false, null, 15);
        ClimatologyStats.Hour d = ClimatologyStats.hourOf(new ForecastChance.Point[]{dry, dry}, 0, 0, 0, 1, 0);
        assertNull(d.kind());
        assertFalse(d.thunderNear());
        assertEquals(0, d.thunder(), 1e-9);
        assertEquals(0, d.wet(), 1e-9);
    }

    /** Weather phase 8b: the year's shape (warmest and coldest weeks, first and last snow) and day-to-day swings. */
    @Test
    void theYearsShapeIsFound() {
        ClimatologyStats s = new ClimatologyStats();
        int days = 72;
        for (int d = 0; d < days; d++) {
            double year = d / 72.0;
            // Warmest at 1/3 of the year, coldest at 5/6; snow whenever the mean is below 1 C.
            double mean = 8 + 12 * Math.cos(2 * Math.PI * (year - 1.0 / 3));
            for (int h = 0; h < 24; h++) {
                boolean snowing = mean < 1 && h < 2;
                s.add(hour(d, year, mean, snowing ? 1 : 0, snowing ? Precip.SNOW : null));
            }
        }
        assertEquals(1.0 / 3, s.extremeWeekYear(true), 0.03, "warmest week");
        assertEquals(5.0 / 6, s.extremeWeekYear(false), 0.03, "coldest week");
        double first = s.snowYear(true);
        double last = s.snowYear(false);
        assertTrue(first > 0.6 && first < 0.8, "first snow in autumn: " + first);
        assertTrue(last > 0.95 || last < 0.1, "last snow around the turn of the year: " + last);
        // A smooth year has almost no day-to-day swing beyond its trend.
        assertTrue(s.summary(ClimatologyStats.Season.SPRING).variability() < 0.2);
        assertTrue(s.describeYear().startsWith("warmest week 33%"), s.describeYear());
    }

    /** 8b follow-up: wet and thunder hours split into a season's early, mid and late sub-seasons. */
    @Test
    void subSeasonsSplitTheSeason() {
        ClimatologyStats s = new ClimatologyStats();
        // Summer runs 0.25-0.5: early 0.25-1/3, mid to 5/12, late to 0.5. Rain only in early summer.
        for (int d = 0; d < 18; d++) {
            double year = 0.25 + (d + 0.5) / 72.0;
            for (int h = 0; h < 24; h++) {
                boolean rain = d < 6 && h < 2;
                s.add(hour(d, year, 25, rain ? 1 : 0, rain ? Precip.RAIN : null));
            }
        }
        String line = s.describeSubSeasons(ClimatologyStats.Season.SUMMER);
        assertTrue(line.startsWith("early 12 h / heard 0 h (6 d); mid 0 h"), line);
        assertTrue(line.contains("late 0 h / heard 0 h (6 d)"), line);
    }

    /** 8b follow-up: thunder heard is the chance of at least one storm in earshot, never less than overhead. */
    @Test
    void thunderHeardFromStormsInEarshot() {
        ClimatologyStats.Hour h = new ClimatologyStats.Hour(1, 0.3, 20, 0, null, false, true, 2, 0.05);
        assertEquals(1 - Math.exp(-1), h.withHeard(1).heard(), 1e-9, "one storm expected: 63%");
        assertEquals(0.05, h.withHeard(0).heard(), 1e-9, "none in earshot: at least the storm overhead");
    }
}
