package dev.brights0ng.enginesandempires.oregen;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

class OreMapTest {

    private static final long SEED = 20260920L;
    private static final OreLayer IRON = new OreLayer("iron", 384);
    private static final OreLayer GOLD = new OreLayer("gold", 1408);

    @Test
    void sameSeedAndLayerGiveIdenticalDeposits() {
        List<Deposit> first = new OreMap(SEED, IRON).depositsInBox(-4000, -4000, 4000, 4000);
        List<Deposit> second = new OreMap(SEED, IRON).depositsInBox(-4000, -4000, 4000, 4000);
        assertFalse(first.isEmpty());
        assertEquals(first, second);
    }

    @Test
    void differentSeedsGiveDifferentDeposits() {
        List<Deposit> first = new OreMap(SEED, IRON).depositsInBox(-4000, -4000, 4000, 4000);
        List<Deposit> second = new OreMap(SEED + 1, IRON).depositsInBox(-4000, -4000, 4000, 4000);
        assertNotEquals(first, second);
    }

    @Test
    void differentOresAreIndependentOfEachOther() {
        OreLayer copper = new OreLayer("copper", 384);
        List<Deposit> iron = new OreMap(SEED, IRON).depositsInBox(-4000, -4000, 4000, 4000);
        List<Deposit> other = new OreMap(SEED, copper).depositsInBox(-4000, -4000, 4000, 4000);
        Set<String> ironSpots = spots(iron);
        long shared = spots(other).stream().filter(ironSpots::contains).count();
        assertTrue(shared < 3, "two ores with the same scale should not share deposit spots");
    }

    /** The property worldgen relies on: asking chunk by chunk gives the same deposits as asking once. */
    @Test
    void queryingChunkByChunkMatchesOneBigQuery() {
        for (OreLayer layer : List.of(IRON, GOLD)) {
            OreMap map = new OreMap(SEED, layer);
            int min = -512;
            int max = 511;
            Set<Deposit> whole = new HashSet<>(map.depositsInBox(min, min, max, max));

            Set<Deposit> tiled = new HashSet<>();
            int tileCount = 0;
            for (int x = min; x <= max; x += 16) {
                for (int z = min; z <= max; z += 16) {
                    List<Deposit> tile = map.depositsInBox(x, z, x + 15, z + 15);
                    tileCount += tile.size();
                    tiled.addAll(tile);
                }
            }
            assertEquals(whole, tiled, layer.id());
            assertEquals(whole.size(), tileCount, "no deposit may appear in two tiles: " + layer.id());
        }
    }

    @Test
    void depositsNearAreWithinRadiusAndSortedNearestFirst() {
        OreMap map = new OreMap(SEED, IRON);
        List<Deposit> near = map.depositsNear(100, -250, 1024);
        assertFalse(near.isEmpty());
        long previous = -1;
        for (Deposit d : near) {
            long dx = d.x() - 100L;
            long dz = d.z() + 250L;
            long distanceSquared = dx * dx + dz * dz;
            assertTrue(distanceSquared <= 1024L * 1024L);
            assertTrue(distanceSquared >= previous);
            previous = distanceSquared;
        }
        // A smaller radius must return exactly the nearest ones from the bigger radius.
        List<Deposit> closer = map.depositsNear(100, -250, 400);
        assertEquals(near.subList(0, closer.size()), closer);
    }

    /** Density should be about one deposit per S x S, whatever S is. */
    @Test
    void densityFollowsScale() {
        for (OreLayer layer : List.of(IRON, GOLD)) {
            int half = layer.scale() * 16;
            List<Deposit> deposits = new OreMap(SEED, layer).depositsInBox(-half, -half, half, half);
            double area = (2.0 * half) * (2.0 * half);
            double perScaleSquared = deposits.size() / area * layer.scale() * layer.scale();
            assertTrue(perScaleSquared > 0.85 && perScaleSquared < 1.15,
                    layer.id() + " density per S^2 was " + perScaleSquared);
        }
    }

    /** Deposits must not snap to the internal sampling lattice. */
    @Test
    void depositsDoNotSitOnALattice() {
        List<Deposit> deposits = new OreMap(SEED, IRON).depositsInBox(-6000, -6000, 6000, 6000);
        double latticeStep = IRON.scale() / 8.0;
        int nearLattice = 0;
        for (Deposit d : deposits) {
            double remainder = Math.abs(d.x() - Math.round(d.x() / latticeStep) * latticeStep);
            if (remainder < 1.5) {
                nearLattice++;
            }
        }
        assertTrue(nearLattice < deposits.size() * 0.15,
                nearLattice + " of " + deposits.size() + " deposits sit on lattice lines");
    }

    @Test
    void abundanceOfZeroRemovesEverythingAndFullKeepsEverything() {
        int half = 6000;
        List<Deposit> baseline = new OreMap(SEED, IRON).depositsInBox(-half, -half, half, half);
        assertFalse(baseline.isEmpty());

        assertTrue(new OreMap(SEED, IRON, (x, z) -> 0.0).depositsInBox(-half, -half, half, half).isEmpty());
        assertEquals(baseline, new OreMap(SEED, IRON, (x, z) -> 1.0).depositsInBox(-half, -half, half, half));
        // Out-of-range answers behave like the nearest end.
        assertEquals(baseline, new OreMap(SEED, IRON, (x, z) -> 7.0).depositsInBox(-half, -half, half, half));
        assertTrue(new OreMap(SEED, IRON, (x, z) -> -3.0).depositsInBox(-half, -half, half, half).isEmpty());
    }

    @Test
    void abundanceOnlyThinsAndNeverMovesDeposits() {
        int half = 12000;
        Set<Deposit> full = new HashSet<>(new OreMap(SEED, IRON).depositsInBox(-half, -half, half, half));
        Set<Deposit> sixty = new HashSet<>(new OreMap(SEED, IRON, (x, z) -> 0.6).depositsInBox(-half, -half, half, half));
        Set<Deposit> thirty = new HashSet<>(new OreMap(SEED, IRON, (x, z) -> 0.3).depositsInBox(-half, -half, half, half));

        // Nested: everything kept at 30% is also kept at 60%, and everything kept at 60% is in the full set.
        assertTrue(full.containsAll(sixty));
        assertTrue(sixty.containsAll(thirty));

        // And the fraction kept is about right (there are a few thousand deposits here).
        assertEquals(0.6, sixty.size() / (double) full.size(), 0.05);
        assertEquals(0.3, thirty.size() / (double) full.size(), 0.05);
    }

    @Test
    void abundanceCanVaryByPlace() {
        int half = 6000;
        List<Deposit> baseline = new OreMap(SEED, IRON).depositsInBox(-half, -half, half, half);
        // Nothing in the east half: the west half must come out exactly as before.
        List<Deposit> westOnly = new OreMap(SEED, IRON, (x, z) -> x >= 0 ? 0.0 : 1.0)
                .depositsInBox(-half, -half, half, half);
        assertEquals(baseline.stream().filter(d -> d.x() < 0).toList(), westOnly);
    }

    @Test
    void nanAbundanceNeverProducesDeposits() {
        List<Deposit> deposits = new OreMap(SEED, IRON, (x, z) -> Double.NaN).depositsInBox(-4000, -4000, 4000, 4000);
        assertTrue(deposits.isEmpty());
    }

    @Test
    void depositSeedsAreUniqueAndStable() {
        List<Deposit> deposits = new OreMap(SEED, IRON).depositsInBox(-6000, -6000, 6000, 6000);
        Set<Long> seeds = new HashSet<>();
        for (Deposit d : deposits) {
            seeds.add(d.seed());
        }
        assertEquals(deposits.size(), seeds.size());

        List<Deposit> again = new OreMap(SEED, IRON).depositsInBox(-6000, -6000, 6000, 6000);
        List<Long> first = new ArrayList<>();
        List<Long> second = new ArrayList<>();
        deposits.forEach(d -> first.add(d.seed()));
        again.forEach(d -> second.add(d.seed()));
        assertEquals(first, second);
    }

    @Test
    void emptyOrInvertedBoxGivesNothing() {
        assertTrue(new OreMap(SEED, IRON).depositsInBox(10, 10, 5, 20).isEmpty());
    }

    private static Set<String> spots(List<Deposit> deposits) {
        Set<String> spots = new HashSet<>();
        for (Deposit d : deposits) {
            spots.add(d.x() + "," + d.z());
        }
        return spots;
    }
}
