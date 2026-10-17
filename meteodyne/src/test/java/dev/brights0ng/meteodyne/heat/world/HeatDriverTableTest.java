package dev.brights0ng.meteodyne.heat.world;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.util.ArrayList;
import java.util.List;
import java.util.function.DoubleSupplier;

import org.junit.jupiter.api.Test;

/**
 * Split seam S13: Meteodyne has no heat drivers of its own; a source runs only by a driver some mod registered. Unit
 * tests run without Minecraft, so the table is tried with stand-ins (string ids, plain answers).
 */
class HeatDriverTableTest {

    @Test
    void meteodyneRegistersNoDriversOfItsOwn() {
        assertEquals(0, HeatDrivers.registered());
    }

    @Test
    void withNothingRegisteredEveryDriverIsUnknown() {
        HeatDriverTable<String, DoubleSupplier> table = new HeatDriverTable<>();
        assertEquals(0, table.size());
        assertNull(table.get("engines_and_empires:running"), "an engine's entry finds no driver, so it stays off");
    }

    @Test
    void aRegisteredDriverRunsItsSources() {
        HeatDriverTable<String, DoubleSupplier> table = new HeatDriverTable<>();
        DoubleSupplier halfway = () -> 0.5;
        table.register("test:halfway", halfway);
        assertSame(halfway, table.get("test:halfway"));
        assertNull(table.get("test:other"));
        assertEquals(0.5, HeatDriverTable.share(table.get("test:halfway"), e -> { }), 1e-12);

        DoubleSupplier full = () -> 1.0;
        table.register("test:halfway", full);
        assertSame(full, table.get("test:halfway"), "registering again replaces it");
        assertEquals(1, table.size());
    }

    @Test
    void aDriversAnswerIsKeptToAShareOfThePower() {
        List<Throwable> failures = new ArrayList<>();
        assertEquals(1.0, HeatDriverTable.share(() -> 1.7, failures::add), 1e-12);
        assertEquals(0.0, HeatDriverTable.share(() -> -0.3, failures::add), 1e-12);
        assertEquals(0.0, HeatDriverTable.share(() -> Double.NaN, failures::add), 1e-12);
        assertEquals(0.0, HeatDriverTable.share(() -> Double.POSITIVE_INFINITY, failures::add), 1e-12);
        assertEquals(0, failures.size());
        assertEquals(0.0, HeatDriverTable.share(() -> {
            throw new IllegalStateException("broken");
        }, failures::add), 1e-12, "a failing driver leaves its source off");
        assertEquals(1, failures.size());
    }
}
