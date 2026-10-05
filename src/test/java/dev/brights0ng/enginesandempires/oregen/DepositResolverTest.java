package dev.brights0ng.enginesandempires.oregen;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

class DepositResolverTest {

    private static List<Deposit> depositsOf(OreType type, int count) {
        List<Deposit> all = new OreMap(BodySamples.SEED, type.layer()).depositsNear(0, 0, type.layer().scale() * 9);
        return all.subList(0, count);
    }

    private static DepositResolver.Result resolve(Deposit deposit, OreType type, TerrainProbe probe) {
        return DepositResolver.resolve(deposit, attempt -> DepositBody.generate(deposit, type, attempt), probe);
    }

    private static List<String> cells(DepositBody body) {
        List<String> all = new ArrayList<>();
        body.forEach((dx, dy, dz, kind) -> all.add(dx + "," + dy + "," + dz + ":" + kind));
        return all;
    }

    @Test
    void attemptZeroIsExactlyTheOrdinaryBody() {
        for (OreType type : OreTypes.ALL) {
            Deposit deposit = depositsOf(type, 1).get(0);
            DepositBody ordinary = DepositBody.generate(deposit, type);
            DepositBody attemptZero = DepositBody.generate(deposit, type, 0);
            assertEquals(cells(ordinary), cells(attemptZero), type.id());
            assertEquals(ordinary.centerY(), attemptZero.centerY(), type.id());
            assertEquals(0, attemptZero.attempt());
        }
    }

    @Test
    void laterAttemptsAreFreshDrawsForTheSamePlace() {
        for (OreType type : List.of(OreTypes.IRON, OreTypes.GOLD, OreTypes.NETHER_QUARTZ)) {
            Deposit deposit = depositsOf(type, 1).get(0);
            DepositBody first = DepositBody.generate(deposit, type, 0);
            DepositBody second = DepositBody.generate(deposit, type, 1);
            DepositBody third = DepositBody.generate(deposit, type, 2);
            assertEquals(1, second.attempt());
            assertNotEquals(cells(first), cells(second), type.id());
            assertNotEquals(cells(second), cells(third), type.id());
            // Same deposit, same generation, so still deterministic.
            assertEquals(cells(second), cells(DepositBody.generate(deposit, type, 1)), type.id());
        }
        // Across many deposits the new draws use new heights, which is what lets a redraw find rock.
        int differentHeight = 0;
        List<Deposit> deposits = depositsOf(OreTypes.IRON, 30);
        for (Deposit deposit : deposits) {
            if (DepositBody.generate(deposit, OreTypes.IRON, 0).drawnY() != DepositBody.generate(deposit, OreTypes.IRON, 1).drawnY()) {
                differentHeight++;
            }
        }
        assertTrue(differentHeight >= 20, "only " + differentHeight + " of 30 redraws changed height");
    }

    @Test
    void solidTerrainKeepsTheOrdinaryBodyAtOnce() {
        for (OreType type : OreTypes.ALL) {
            Deposit deposit = depositsOf(type, 1).get(0);
            DepositResolver.Result result = resolve(deposit, type, TerrainProbe.ALL_SOLID);
            assertEquals(0, result.outcome().attempt(), type.id());
            assertEquals(1.0, result.outcome().solidFraction(), 0.0, type.id());
            assertTrue(result.outcome().viable());
            assertEquals(cells(DepositBody.generate(deposit, type)), cells(result.body()), type.id());
        }
    }

    @Test
    void openAirEverywhereMeansThereIsNoDeposit() {
        for (OreType type : OreTypes.ALL) {
            Deposit deposit = depositsOf(type, 1).get(0);
            DepositResolver.Result result = resolve(deposit, type, (x, y, z) -> false);
            assertTrue(result.outcome().dropped(), type.id());
            assertFalse(result.outcome().viable());
            assertEquals(-1, result.outcome().attempt(), type.id());
            assertEquals(0.0, result.outcome().solidFraction(), 0.0, type.id());
            assertNull(result.body(), type.id());
        }
    }

    /** The nether's open caverns: rock only far below where a deposit was drawn, so it has to be redrawn lower. */
    @Test
    void aDepositDrawnInAirIsRedrawnAtTheSamePlaceUntilItFindsRock() {
        OreType type = OreTypes.IRON; // heights -48 to 64
        TerrainProbe rockBelowSeaLevel = (x, y, z) -> y < 0;
        int redrawn = 0;
        int dropped = 0;
        for (Deposit deposit : depositsOf(type, 40)) {
            DepositResolver.Result result = resolve(deposit, type, rockBelowSeaLevel);
            if (result.outcome().dropped()) {
                dropped++;
                continue;
            }
            DepositBody body = result.body();
            assertEquals(result.outcome().attempt(), body.attempt());
            assertTrue(result.outcome().solidFraction() >= DepositResolver.MIN_SOLID_FRACTION);
            // The estimate really does come from this body's own ore blocks.
            assertEquals(result.outcome().solidFraction(),
                    DepositResolver.solidFraction(deposit, body, rockBelowSeaLevel), 0.0);
            if (result.outcome().attempt() > 0) {
                redrawn++;
            }
        }
        assertTrue(redrawn >= 4, "expected some deposits to need a redraw, saw " + redrawn);
        assertTrue(dropped <= 2, "8 draws should almost always find rock below y=0, but " + dropped + " of 40 were dropped");
    }

    @Test
    void aDepositWhoseWholeHeightRangeIsAirIsDropped() {
        OreType type = OreTypes.COAL; // heights 20 to 150, so never near y < -30
        TerrainProbe rockOnlyDeepDown = (x, y, z) -> y < -30;
        for (Deposit deposit : depositsOf(type, 20)) {
            assertTrue(resolve(deposit, type, rockOnlyDeepDown).outcome().dropped());
        }
    }

    @Test
    void theOutcomeIsDeterministic() {
        OreType type = OreTypes.GOLD;
        TerrainProbe probe = (x, y, z) -> (x + y + z) % 3 != 0;
        for (Deposit deposit : depositsOf(type, 10)) {
            DepositResolver.Outcome first = resolve(deposit, type, probe).outcome();
            DepositResolver.Outcome second = resolve(deposit, type, probe).outcome();
            assertEquals(first, second);
        }
    }

    @Test
    void thresholdsBehaveAsDocumented() {
        DepositBody body = BodySamples.of(OreTypes.IRON).get(0);
        assertTrue(body.oreCount() >= 100);
        assertFalse(DepositResolver.isViable(body, 0.0));
        assertFalse(DepositResolver.isViable(body, DepositResolver.MIN_SOLID_FRACTION - 0.01));
        assertTrue(DepositResolver.isViable(body, DepositResolver.MIN_SOLID_FRACTION));
        assertTrue(DepositResolver.isViable(body, 1.0));
    }

    @Test
    void aTinyDepositIsNotRejectedForBeingTinyAlone() {
        // Emerald deposits can hold only a handful of ore blocks; needing 8 of them would reject every one.
        DepositBody smallest = null;
        for (DepositBody body : BodySamples.of(OreTypes.EMERALD)) {
            if (smallest == null || body.oreCount() < smallest.oreCount()) {
                smallest = body;
            }
        }
        assertNotNull(smallest);
        assertTrue(DepositResolver.isViable(smallest, 1.0), "a fully solid tiny deposit must be viable");
    }

    @Test
    void onlyASmallSampleOfBlocksIsTested() {
        OreType type = OreTypes.IRON;
        Deposit deposit = depositsOf(type, 1).get(0);
        DepositBody body = DepositBody.generate(deposit, type);
        AtomicInteger calls = new AtomicInteger();
        double fraction = DepositResolver.solidFraction(deposit, body, (x, y, z) -> {
            calls.incrementAndGet();
            return true;
        });
        assertEquals(1.0, fraction, 0.0);
        assertTrue(calls.get() >= 1 && calls.get() <= DepositResolver.SAMPLES, "tested " + calls.get() + " blocks");
    }

    @Test
    void partlySolidTerrainGivesAProportionalShare() {
        OreType type = OreTypes.IRON;
        Deposit deposit = depositsOf(type, 1).get(0);
        DepositBody body = DepositBody.generate(deposit, type);
        int centre = body.centerY();
        double aboveCentre = DepositResolver.solidFraction(deposit, body, (x, y, z) -> y >= centre);
        double all = DepositResolver.solidFraction(deposit, body, (x, y, z) -> true);
        double none = DepositResolver.solidFraction(deposit, body, (x, y, z) -> false);
        assertEquals(1.0, all, 0.0);
        assertEquals(0.0, none, 0.0);
        assertTrue(aboveCentre > 0.15 && aboveCentre < 0.85, "half-solid terrain gave " + aboveCentre);
    }
}
