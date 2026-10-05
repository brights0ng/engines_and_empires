package dev.brights0ng.enginesandempires.food.icechest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;

import org.junit.jupiter.api.Test;

/** The ice chest's cooling over a stretch of time, and its coolants. */
class IceChestCoolingTest {

    private static final long ICE = 3000;

    @Test
    void oneIceCoolsForItsTimeThenStops() {
        IceChestCooling.Result r = IceChestCooling.run(6000, 0, 0, 1, ICE, true);
        assertEquals(3000, r.coldTicks());
        assertEquals(1, r.itemsUsed());
        assertEquals(0, r.coolingLeft());
        assertEquals(0, r.currentMax());
    }

    @Test
    void itemsBurnOneAtATime() {
        IceChestCooling.Result r = IceChestCooling.run(4000, 0, 0, 5, ICE, true);
        assertEquals(4000, r.coldTicks());
        assertEquals(2, r.itemsUsed(), "the second ice is burning, not finished");
        assertEquals(2000, r.coolingLeft());
        assertEquals(ICE, r.currentMax());
    }

    @Test
    void whatIsLeftBurnsFirst() {
        IceChestCooling.Result r = IceChestCooling.run(500, 1200, ICE, 3, ICE, true);
        assertEquals(500, r.coldTicks());
        assertEquals(0, r.itemsUsed());
        assertEquals(700, r.coolingLeft());
        IceChestCooling.Result through = IceChestCooling.run(1500, 1200, ICE, 3, ICE, true);
        assertEquals(1500, through.coldTicks());
        assertEquals(1, through.itemsUsed());
        assertEquals(2700, through.coolingLeft());
    }

    @Test
    void noFoodNoMelting() {
        IceChestCooling.Result r = IceChestCooling.run(100_000, 800, ICE, 4, ICE, false);
        assertEquals(0, r.coldTicks());
        assertEquals(0, r.itemsUsed());
        assertEquals(800, r.coolingLeft());
    }

    @Test
    void noIceNoCold() {
        assertEquals(0, IceChestCooling.run(5000, 0, 0, 0, ICE, true).coldTicks());
        assertEquals(0, IceChestCooling.run(5000, 0, 0, 3, 0, true).coldTicks(), "the slot holds something that does not cool");
    }

    @Test
    void ninetyPercentSlower() {
        assertEquals(2700, IceChestCooling.youngerBy(3000, 0.9));
        assertEquals(0, IceChestCooling.youngerBy(3000, 0));
    }

    /** Bright's numbers: a block of ice 2.5 minutes, a stack just under 3 hours, a stack of blue ice just under 43 hours. */
    @Test
    void coolantsLastAsChosen() {
        Map<String, Long> ticks = Coolants.ticksByItem(Coolants.DEFAULT_LINES, Coolants.DEFAULT_ICE_TICKS);
        assertEquals(3000L, ticks.get("minecraft:ice"));
        assertEquals(12000L, ticks.get("minecraft:packed_ice"));
        assertEquals(48000L, ticks.get("minecraft:blue_ice"));
        assertEquals(750L, ticks.get("minecraft:snow_block"));
        assertEquals(188L, ticks.get("minecraft:snowball"));
        assertEquals(188L, ticks.get("minecraft:snow"));
        double stackOfIceHours = 64 * ticks.get("minecraft:ice") / 20.0 / 3600;
        double stackOfBlueHours = 64 * ticks.get("minecraft:blue_ice") / 20.0 / 3600;
        assertTrue(stackOfIceHours > 2.5 && stackOfIceHours < 3, "a stack of ice: " + stackOfIceHours + " h");
        assertTrue(stackOfBlueHours > 42 && stackOfBlueHours < 43, "a stack of blue ice: " + stackOfBlueHours + " h");
    }

    @Test
    void badCoolantLinesAreRejected() {
        assertTrue(Coolants.parse("minecraft:ice").isEmpty());
        assertTrue(Coolants.parse("minecraft:ice zero").isEmpty());
        assertTrue(Coolants.parse("minecraft:ice -1").isEmpty());
        assertTrue(Coolants.parse("minecraft:ice 1 2").isEmpty());
    }
}
