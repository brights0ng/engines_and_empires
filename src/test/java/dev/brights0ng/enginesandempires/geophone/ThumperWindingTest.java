package dev.brights0ng.enginesandempires.geophone;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class ThumperWindingTest {

    @Test
    void aFreshThumperIsNotWound() {
        assertFalse(ThumperWinding.isFullyWound(0.0F));
    }

    @Test
    void belowTheMinimumRpmNothingWindsAtAll() {
        assertEquals(0.0, ThumperWinding.chargePerTick(0.0), 0.0);
        assertEquals(0.0, ThumperWinding.chargePerTick(ThumperWinding.MIN_RPM - 0.01), 0.0);
        assertEquals(0.0F, ThumperWinding.advance(0.0F, 5.0), 0.0F);
    }

    @Test
    void atExactlyTheMinimumRpmItStartsWinding() {
        assertTrue(ThumperWinding.chargePerTick(ThumperWinding.MIN_RPM) > 0.0);
    }

    @Test
    void directionDoesNotMatterOnlyRate() {
        assertEquals(ThumperWinding.chargePerTick(40.0), ThumperWinding.chargePerTick(-40.0), 0.0);
    }

    @Test
    void fasterRotationWindsFaster() {
        assertTrue(ThumperWinding.chargePerTick(128.0) > ThumperWinding.chargePerTick(32.0));
    }

    @Test
    void atTheReferenceRpmAFullWindTakesExactlyTheReferenceTicks() {
        float charge = 0.0F;
        int ticks = 0;
        while (!ThumperWinding.isFullyWound(charge)) {
            charge = ThumperWinding.advance(charge, ThumperWinding.REFERENCE_RPM);
            ticks++;
            assertTrue(ticks <= ThumperWinding.REFERENCE_WIND_TICKS, "took more ticks than the reference duration");
        }
        assertEquals(ThumperWinding.REFERENCE_WIND_TICKS, ticks);
    }

    @Test
    void doubleTheReferenceRpmWindsInHalfTheTicks() {
        float charge = 0.0F;
        int ticks = 0;
        while (!ThumperWinding.isFullyWound(charge)) {
            charge = ThumperWinding.advance(charge, ThumperWinding.REFERENCE_RPM * 2.0);
            ticks++;
        }
        assertEquals(ThumperWinding.REFERENCE_WIND_TICKS / 2, ticks, 1);
    }

    @Test
    void chargeNeverExceedsAFullWindAndSettlesExactlyAtOne() {
        float charge = 0.0F;
        for (int i = 0; i < ThumperWinding.REFERENCE_WIND_TICKS + 50; i++) {
            charge = ThumperWinding.advance(charge, ThumperWinding.REFERENCE_RPM);
            assertTrue(charge <= 1.0F, "charge went over 1.0 at tick " + i);
        }
        assertEquals(1.0F, charge, 0.0F);
    }

    @Test
    void stoppingTheShaftHoldsTheChargeRatherThanLosingProgress() {
        float charge = 0.4F;
        assertEquals(charge, ThumperWinding.advance(charge, 0.0), 0.0F, "no rotation, no change, but no decay either");
    }

    @Test
    void resumingAfterAPauseContinuesFromWhereItLeftOff() {
        float charge = 0.5F;
        float afterOneMoreTick = ThumperWinding.advance(charge, ThumperWinding.REFERENCE_RPM);
        assertTrue(afterOneMoreTick > charge);
        assertEquals(charge + ThumperWinding.chargePerTick(ThumperWinding.REFERENCE_RPM), afterOneMoreTick, 1.0e-6F);
    }
}
