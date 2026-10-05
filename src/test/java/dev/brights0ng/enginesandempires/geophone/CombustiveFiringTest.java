package dev.brights0ng.enginesandempires.geophone;

import static dev.brights0ng.enginesandempires.geophone.CombustiveFiring.Charge.EMPTY;
import static dev.brights0ng.enginesandempires.geophone.CombustiveFiring.Charge.FUEL;
import static dev.brights0ng.enginesandempires.geophone.CombustiveFiring.Charge.INERT;
import static dev.brights0ng.enginesandempires.geophone.CombustiveFiring.Charge.PREMIUM_FUEL;
import static dev.brights0ng.enginesandempires.geophone.CombustiveFiring.Charge.VOLATILE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;

class CombustiveFiringTest {

    @Test
    void dieselReachesFurthestAndBurnsLeast() {
        CombustiveFiring.Shot shot = CombustiveFiring.fire(PREMIUM_FUEL, 1000);
        assertEquals(1024, shot.range());
        assertEquals(150, shot.burnMb());
        assertFalse(shot.explodes());
        assertTrue(shot.ignited());
    }

    @Test
    void gasolineAndBiodieselReach768For250() {
        CombustiveFiring.Shot shot = CombustiveFiring.fire(FUEL, 1000);
        assertEquals(768, shot.range());
        assertEquals(250, shot.burnMb());
        assertFalse(shot.explodes());
    }

    /** The blast still drives the head home: full range, but it blows up. */
    @Test
    void volatileFluidsReachFullRangeAndExplode() {
        CombustiveFiring.Shot shot = CombustiveFiring.fire(VOLATILE, 1000);
        assertEquals(1024, shot.range());
        assertEquals(250, shot.burnMb());
        assertTrue(shot.explodes());
    }

    @Test
    void emptyAndInertTanksJustDropTheHead() {
        for (CombustiveFiring.Charge charge : new CombustiveFiring.Charge[]{EMPTY, INERT}) {
            CombustiveFiring.Shot shot = CombustiveFiring.fire(charge, charge == EMPTY ? 0 : 1000);
            assertEquals(128, shot.range(), charge.name());
            assertEquals(0, shot.burnMb(), charge.name());
            assertFalse(shot.explodes(), charge.name());
            assertFalse(shot.ignited(), charge.name());
        }
    }

    /** Less than one shot's worth is a dud: nothing burned, nothing blown up. */
    @Test
    void tooLittleOfAnythingIsADud() {
        assertEquals(128, CombustiveFiring.fire(PREMIUM_FUEL, 149).range());
        assertEquals(0, CombustiveFiring.fire(FUEL, 249).burnMb());
        assertFalse(CombustiveFiring.fire(VOLATILE, 249).explodes());
        assertEquals(1024, CombustiveFiring.fire(PREMIUM_FUEL, 150).range(), "exactly one shot's worth is enough");
    }

    /** Only a non-combustible jams the head; an empty tank does not. */
    @Test
    void onlyAnInertFluidStopsTheHeadBeingLifted() {
        assertFalse(CombustiveFiring.canLift(INERT));
        for (CombustiveFiring.Charge charge : new CombustiveFiring.Charge[]{EMPTY, PREMIUM_FUEL, FUEL, VOLATILE}) {
            assertTrue(CombustiveFiring.canLift(charge), charge.name());
        }
    }

    @Test
    void itLiftsFasterThanTheMechanicalThumperWinds() {
        double rpm = ThumperWinding.REFERENCE_RPM;
        assertTrue(ThumperWinding.chargePerTick(rpm, CombustiveThumperBlockEntity.LIFT_REFERENCE_TICKS)
                > ThumperWinding.chargePerTick(rpm));
        assertEquals(0.0, ThumperWinding.chargePerTick(ThumperWinding.MIN_RPM - 1, 60), "too slow still does nothing");
    }

    /** The generated tags: diesel premium, gasoline and biodiesel fuel, crude/plant oil/ethanol/c:fuel combustible. */
    @Test
    void theFluidTagsSortTheFuelsAsDesigned() throws Exception {
        Path tags = Path.of("src/generated/resources/data/engines_and_empires/tags/fluid");
        String premium = Files.readString(tags.resolve("premium_thumper_fuel.json"));
        String fuel = Files.readString(tags.resolve("thumper_fuel.json"));
        String combustible = Files.readString(tags.resolve("combustible.json"));
        assertTrue(premium.contains("#c:diesel"));
        assertTrue(fuel.contains("#c:gasoline") && fuel.contains("#c:biodiesel"));
        assertTrue(fuel.contains("#engines_and_empires:premium_thumper_fuel"));
        for (String burns : new String[]{"#c:crude_oil", "#c:plantoil", "#c:ethanol", "#c:fuel"}) {
            assertTrue(combustible.contains(burns), burns);
        }
        assertTrue(premium.contains("\"required\": false"), "the c: tags must be optional, so nothing breaks without Diesel Generators");
    }
}
