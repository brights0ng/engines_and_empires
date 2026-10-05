package dev.brights0ng.enginesandempires.geophone;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.junit.jupiter.api.Test;

import dev.brights0ng.enginesandempires.geophone.DepositFinder.Metric;
import dev.brights0ng.enginesandempires.geophone.DepositFinder.Query;
import dev.brights0ng.enginesandempires.geophone.DepositFinder.Result;
import dev.brights0ng.enginesandempires.geophone.DepositFinder.Sighting;
import dev.brights0ng.enginesandempires.oregen.Deposit;
import dev.brights0ng.enginesandempires.oregen.DepositBody;
import dev.brights0ng.enginesandempires.oregen.DepositResolver;
import dev.brights0ng.enginesandempires.oregen.OreMap;
import dev.brights0ng.enginesandempires.oregen.OreType;
import dev.brights0ng.enginesandempires.oregen.OreTypes;
import dev.brights0ng.enginesandempires.oregen.Realm;
import dev.brights0ng.enginesandempires.oregen.ResolvedDeposit;
import dev.brights0ng.enginesandempires.oregen.TerrainProbe;

class DepositFinderTest {

    private static final long SEED = 20260920L;

    /** Resolved deposits over solid terrain, remembered so the many searches below do not rebuild the same bodies. */
    private static final Map<Long, ResolvedDeposit> RESOLVED = new ConcurrentHashMap<>();

    private static ResolvedDeposit solid(Deposit deposit) {
        return RESOLVED.computeIfAbsent(deposit.seed(), seed -> {
            OreType type = OreTypes.byId(deposit.oreId());
            DepositResolver.Result result = DepositResolver.resolve(deposit,
                    attempt -> DepositBody.generate(deposit, type, attempt), TerrainProbe.ALL_SOLID);
            return new ResolvedDeposit(deposit, result.outcome(), result.body());
        });
    }

    private static List<OreMap> maps(Realm realm) {
        return OreTypes.forRealm(realm).stream().map(type -> new OreMap(SEED, type.layer())).toList();
    }

    private static Result overworld(Query query) {
        return DepositFinder.find(maps(Realm.OVERWORLD), query, DepositFinderTest::solid);
    }

    private static Set<String> keys(Result result) {
        Set<String> keys = new HashSet<>();
        for (Sighting sighting : result.sightings()) {
            keys.add(sighting.oreId() + "@" + sighting.x() + "," + sighting.z());
        }
        return keys;
    }

    @Test
    void resultsAreWithinTheRadiusAndSortedNearestFirst() {
        Result result = overworld(Query.around(0, 64, 0, 600, Metric.SPHERE));
        assertTrue(result.sightings().size() >= 3, "expected several deposits within 600 blocks, saw " + result.sightings().size());
        double previous = -1.0;
        for (Sighting sighting : result.sightings()) {
            assertTrue(sighting.distance() <= 600.0, sighting.oreId() + " at " + sighting.distance());
            assertTrue(sighting.distance() >= previous, "results are not sorted nearest first");
            previous = sighting.distance();
        }
    }

    /** Range is measured to the nearest ore block, which for a big deposit can be much closer than its centre. */
    @Test
    void distanceIsToTheNearestOreBlockAndNotTheCentre() {
        Query query = Query.around(0, 64, 0, 700, Metric.SPHERE);
        boolean someCentreIsFurtherThanItsOre = false;
        for (Sighting sighting : overworld(query).sightings()) {
            DepositBody body = sighting.body();
            double[] best = {Double.POSITIVE_INFINITY};
            body.forEach((dx, dy, dz, kind) -> {
                if (kind == DepositBody.ORE || kind == DepositBody.RICH) {
                    double x = sighting.x() + dx - query.x();
                    double y = body.centerY() + dy - query.y();
                    double z = sighting.z() + dz - query.z();
                    best[0] = Math.min(best[0], Math.sqrt(x * x + y * y + z * z));
                }
            });
            assertEquals(best[0], sighting.distance(), 1.0e-9, sighting.oreId());

            // The reported nearest block really is an ore block of the deposit.
            byte kind = body.at(sighting.nearestX() - sighting.x(), sighting.nearestY() - body.centerY(),
                    sighting.nearestZ() - sighting.z());
            assertTrue(kind == DepositBody.ORE || kind == DepositBody.RICH, sighting.oreId() + " nearest block is not ore");

            double toCentre = Math.sqrt(Math.pow(sighting.x() - query.x(), 2) + Math.pow(sighting.centerY() - query.y(), 2)
                    + Math.pow(sighting.z() - query.z(), 2));
            if (toCentre > sighting.distance() + 3.0) {
                someCentreIsFurtherThanItsOre = true;
            }
        }
        assertTrue(someCentreIsFurtherThanItsOre, "no deposit was closer than its centre, which is very unlikely");
    }

    /**
     * A deposit's ore can be up to the diagonal of its reach away from its centre. Standing on the ore block
     * furthest from each deposit's centre must still find that deposit, at distance zero, however far its
     * centre is.
     */
    @Test
    void aDepositIsFoundEvenWhenTheQueryIsAtTheFarthestEdgeOfItsOre() {
        int checked = 0;
        for (OreType type : OreTypes.forRealm(Realm.OVERWORLD)) {
            List<Deposit> deposits = new OreMap(SEED, type.layer()).depositsNear(0, 0, type.layer().scale() * 6);
            for (Deposit deposit : deposits.subList(0, Math.min(6, deposits.size()))) {
                DepositBody body = solid(deposit).body();
                int[] far = {0, 0, 0};
                long[] farthest = {-1};
                body.forEach((dx, dy, dz, kind) -> {
                    if (kind == DepositBody.ORE || kind == DepositBody.RICH) {
                        long horizontal = (long) dx * dx + (long) dz * dz;
                        if (horizontal > farthest[0]) {
                            farthest[0] = horizontal;
                            far[0] = deposit.x() + dx;
                            far[1] = body.centerY() + dy;
                            far[2] = deposit.z() + dz;
                        }
                    }
                });
                Query query = Query.around(far[0], far[1], far[2], 1.0, Metric.SPHERE).onlyOres(List.of(type.id()));
                Result result = DepositFinder.find(maps(Realm.OVERWORLD), query, DepositFinderTest::solid);
                Sighting found = result.sightings().stream().filter(s -> s.deposit().equals(deposit)).findFirst().orElse(null);
                assertTrue(found != null, type.id() + " deposit at " + deposit.x() + "," + deposit.z()
                        + " was missed from its own farthest ore block");
                assertEquals(0.0, found.distance(), 0.0, type.id());
                checked++;
            }
        }
        assertTrue(checked >= 30, "checked only " + checked + " deposits");
    }

    /** Whatever the search skips as too far to bother with, it must never skip a deposit that is in range. */
    @Test
    void theSearchFindsExactlyWhatAnExhaustiveCheckOfEveryDepositWouldFind() {
        int[][] centres = {{0, 0}, {1234, -987}, {-3000, 450}};
        for (int[] centre : centres) {
            for (double radius : new double[]{150.0, 450.0}) {
                for (Metric metric : Metric.values()) {
                    Query query = Query.around(centre[0], 20, centre[1], radius, metric);
                    Set<String> expected = new HashSet<>();
                    int box = (int) radius + 400; // wider than any deposit can reach
                    for (OreMap map : maps(Realm.OVERWORLD)) {
                        for (Deposit deposit : map.depositsInBox(centre[0] - box, centre[1] - box, centre[0] + box, centre[1] + box)) {
                            Sighting sighting = DepositFinder.nearestOre(solid(deposit), query);
                            if (sighting != null && sighting.distance() <= radius) {
                                expected.add(sighting.oreId() + "@" + sighting.x() + "," + sighting.z());
                            }
                        }
                    }
                    assertEquals(expected, keys(overworld(query)),
                            "radius " + radius + " " + metric + " around " + centre[0] + "," + centre[1]);
                }
            }
        }
    }

    @Test
    void filteringByOreReturnsOnlyThatOre() {
        Query all = Query.around(0, 64, 0, 900, Metric.SPHERE);
        Set<String> present = new HashSet<>();
        for (Sighting sighting : overworld(all).sightings()) {
            present.add(sighting.oreId());
        }
        assertTrue(present.size() >= 3, "expected several kinds of ore, saw " + present);

        String wanted = present.iterator().next();
        Result only = overworld(all.onlyOres(List.of(wanted)));
        assertFalse(only.sightings().isEmpty());
        for (Sighting sighting : only.sightings()) {
            assertEquals(wanted, sighting.oreId());
        }
        // ... and it finds every one of that ore that the unfiltered search does.
        long expected = overworld(all).sightings().stream().filter(s -> s.oreId().equals(wanted)).count();
        assertEquals(expected, only.sightings().size());
    }

    @Test
    void askingForNoOresFindsNothingAndChecksNothing() {
        Result result = overworld(Query.around(0, 64, 0, 900, Metric.SPHERE).onlyOres(List.of()));
        assertTrue(result.sightings().isEmpty());
        assertEquals(0, result.candidates());
    }

    @Test
    void depositsThatDoNotExistAreNotReportedButAreCounted() {
        DepositResolver.Outcome dropped = new DepositResolver.Outcome(-1, 0.0, true);
        Result result = DepositFinder.find(maps(Realm.OVERWORLD), Query.around(0, 64, 0, 900, Metric.SPHERE),
                deposit -> new ResolvedDeposit(deposit, dropped, null));
        assertTrue(result.sightings().isEmpty());
        assertTrue(result.candidates() > 0);
        assertEquals(result.candidates(), result.dropped());
    }

    @Test
    void flatRangeIgnoresDepthAndSphereRangeDoesNot() {
        int y = 600; // far above everything, so depth makes a big difference
        Result flat = overworld(Query.around(0, y, 0, 900, Metric.HORIZONTAL));
        Result sphere = overworld(Query.around(0, y, 0, 900, Metric.SPHERE));

        assertTrue(keys(flat).containsAll(keys(sphere)), "everything in 3D range is also in flat range");
        assertTrue(flat.sightings().size() > sphere.sightings().size(),
                "a search from y=600 should reach far more ore across the ground than through the air");
        for (Sighting sighting : sphere.sightings()) {
            assertTrue(Math.abs(sighting.nearestY() - y) <= sighting.distance() + 1.0e-9);
            assertTrue(sighting.distance() <= 900.0);
        }
        // Flat distance to a deposit is never more than its 3D distance.
        for (Sighting inFlat : flat.sightings()) {
            Sighting threeD = DepositFinder.nearestOre(inFlat.resolved(), Query.around(0, y, 0, 900, Metric.SPHERE));
            assertTrue(inFlat.distance() <= threeD.distance() + 1.0e-9);
        }
    }

    @Test
    void standingInsideOreFindsItAtDistanceZero() {
        Deposit deposit = new OreMap(SEED, OreTypes.IRON.layer()).depositsNear(0, 0, 2000).get(0);
        DepositBody body = solid(deposit).body();
        int[] cell = new int[3];
        body.forEach((dx, dy, dz, kind) -> {
            if (kind == DepositBody.ORE) {
                cell[0] = deposit.x() + dx;
                cell[1] = body.centerY() + dy;
                cell[2] = deposit.z() + dz;
            }
        });
        Query query = Query.around(cell[0], cell[1], cell[2], 5.0, Metric.SPHERE).onlyOres(List.of("iron"));
        Sighting nearest = overworld(query).sightings().get(0);
        assertEquals(0.0, nearest.distance(), 0.0);
        assertEquals(cell[0], nearest.nearestX());
        assertEquals(cell[1], nearest.nearestY());
        assertEquals(cell[2], nearest.nearestZ());
    }

    @Test
    void theNetherHasItsOwnOres() {
        Result result = DepositFinder.find(maps(Realm.NETHER), Query.around(0, 60, 0, 1500, Metric.SPHERE), DepositFinderTest::solid);
        assertFalse(result.sightings().isEmpty(), "expected nether deposits within 1500 blocks");
        for (Sighting sighting : result.sightings()) {
            assertEquals(Realm.NETHER, sighting.type().realm(), sighting.oreId());
        }
    }

    @Test
    void searchesAreDeterministicAndResultsCannotBeChanged() {
        Query query = Query.around(300, 40, -200, 500, Metric.SPHERE);
        Result first = overworld(query);
        Result second = overworld(query);
        assertEquals(keys(first), keys(second));
        assertEquals(first.candidates(), second.candidates());
        assertThrows(UnsupportedOperationException.class, () -> first.sightings().add(first.sightings().get(0)));
    }

    @Test
    void invalidQueriesAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> Query.around(0, 0, 0, 0.0, Metric.SPHERE));
        assertThrows(IllegalArgumentException.class, () -> Query.around(0, 0, 0, -5.0, Metric.SPHERE));
        assertThrows(IllegalArgumentException.class, () -> Query.around(0, 0, 0, Double.NaN, Metric.SPHERE));
        assertThrows(NullPointerException.class, () -> new Query(0, 0, 0, 10.0, null, null));
    }

    @Test
    void theGeophonesFourRangesAllWork() {
        // 16, 128, 512 and 1024 are the range tiers of the seismic survey design.
        int previous = -1;
        for (int radius : new int[]{16, 128, 512, 1024}) {
            List<Sighting> found = new ArrayList<>(overworld(Query.around(0, 64, 0, radius, Metric.SPHERE)).sightings());
            assertTrue(found.size() >= previous, "a longer range never finds fewer deposits");
            previous = found.size();
        }
    }
}
