package dev.brights0ng.meteodyne.heat;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;

import org.junit.jupiter.api.Test;

class HeatMathTest {

    private static final double NO_LIMIT = Double.NaN;

    @Test
    void fadeIsFullCloseInAndLinearToTheRange() {
        assertEquals(13.89, HeatMath.fade(13.89, 7, 0.2), 1e-9, "full strength within half a block");
        assertEquals(13.89, HeatMath.fade(13.89, 7, 0.5), 1e-9);
        assertEquals(13.89 / 2, HeatMath.fade(13.89, 7, 3.75), 1e-9, "half way along");
        assertEquals(0, HeatMath.fade(13.89, 7, 7), 1e-9, "nothing at the range");
        assertEquals(0, HeatMath.fade(13.89, 7, 20), 1e-9, "or beyond it");
        assertEquals(-5.0, HeatMath.fade(-5.0, 4, 0.5), 1e-9, "cold fades the same way");
    }

    @Test
    void noSourcesLeaveTheAirAlone() {
        assertEquals(-4.2, HeatMath.apply(-4.2, List.of()), 1e-9);
    }

    @Test
    void aKindOfSourceAddsUpOnlyToItsCap() {
        // Ten campfires' worth (+139) cap at 27.78
        List<HeatMath.Group> campfires = List.of(new HeatMath.Group(139, 27.78, 204.4));
        assertEquals(-10 + 27.78, HeatMath.apply(-10, campfires), 1e-9);
        List<HeatMath.Group> few = List.of(new HeatMath.Group(5, 27.78, 204.4));
        assertEquals(-5, HeatMath.apply(-10, few), 1e-9, "below the cap it is the sum");
    }

    @Test
    void differentKindsAddUp() {
        List<HeatMath.Group> both = List.of(new HeatMath.Group(10, 27.78, NO_LIMIT), new HeatMath.Group(6, 26.67, NO_LIMIT));
        assertEquals(-20 + 16, HeatMath.apply(-20, both), 1e-9);
    }

    @Test
    void heatStopsAtItsLimitAndLeavesAirPastItAlone() {
        List<HeatMath.Group> lava = List.of(new HeatMath.Group(16.67, 111.1, 20));
        assertEquals(20, HeatMath.apply(10, lava), 1e-9, "stops at the limit");
        assertEquals(25, HeatMath.apply(25, lava), 1e-9, "air already hotter is left alone");
        assertEquals(26.67, HeatMath.apply(10, List.of(new HeatMath.Group(16.67, 111.1, NO_LIMIT))), 1e-9, "no limit: all of it");
    }

    @Test
    void anIceboxCoolsToFreezingButNoFurther() {
        List<HeatMath.Group> icebox = List.of(new HeatMath.Group(-8.33, 20, 0));
        assertEquals(10 - 8.33, HeatMath.apply(10, icebox), 1e-9, "a warm day: cooler");
        assertEquals(0, HeatMath.apply(5, icebox), 1e-9, "stops at freezing");
        assertEquals(-3, HeatMath.apply(-3, icebox), 1e-9, "already colder: left alone");
    }

    @Test
    void blueIceReachesWellBelowFreezing() {
        List<HeatMath.Group> blue = List.of(new HeatMath.Group(-11.11, 35.56, -17.78));
        assertEquals(5 - 11.11, HeatMath.apply(5, blue), 1e-9, "enough to freeze water on a mild day");
        assertEquals(-17.78, HeatMath.apply(-10, blue), 1e-9, "stops at its own limit");
    }

    @Test
    void heatComesBeforeCold() {
        // +20 (limit 15) then -10 (limit -50): 0 -> 15 -> 5; the other way round it would be 0 -> -10 -> 10 -> 15
        List<HeatMath.Group> mixed = List.of(new HeatMath.Group(-10, 20, -50), new HeatMath.Group(20, 30, 15));
        assertEquals(5, HeatMath.apply(0, mixed), 1e-9);
    }
}
