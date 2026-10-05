package dev.brights0ng.enginesandempires.oregen;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import org.junit.jupiter.api.Test;

/** Properties every deposit of every ore must have, whatever its shape. */
class DepositBodyTest {

    @Test
    void generationIsDeterministic() {
        for (OreType type : OreTypes.ALL) {
            OreMap map = new OreMap(BodySamples.SEED, type.layer());
            Deposit deposit = map.depositsNear(0, 0, type.layer().scale() * 4).get(0);
            DepositBody first = DepositBody.generate(deposit, type);
            DepositBody second = DepositBody.generate(deposit, type);
            assertEquals(cells(first), cells(second), type.id());
            assertEquals(first.centerY(), second.centerY(), type.id());
            assertEquals(first.oreCount(), second.oreCount(), type.id());
        }
    }

    @Test
    void differentDepositsGiveDifferentBodies() {
        for (OreType type : OreTypes.ALL) {
            List<DepositBody> bodies = BodySamples.of(type);
            assertFalse(cells(bodies.get(0)).equals(cells(bodies.get(1))), type.id());
        }
    }

    @Test
    void requestedSizeFollowsTheSizeProfileScaledByDepth() {
        for (OreType type : OreTypes.ALL) {
            for (DepositBody body : BodySamples.of(type)) {
                double m = body.sizeMultiplier();
                assertTrue(body.requestedOre() >= Math.round(type.size().min() * m) - 1, type.id());
                assertTrue(body.requestedOre() <= Math.round(type.size().max() * m) + 1, type.id());
                assertTrue(body.requestedOre() <= type.maxOre(), type.id());
            }
        }
    }

    @Test
    void oreCountIsExactlyWhatWasAskedForApartFromASmallShortfallInTinyPockets() {
        for (OreType type : OreTypes.ALL) {
            List<DepositBody> bodies = BodySamples.of(type);
            long exact = bodies.stream().filter(b -> b.oreCount() == b.requestedOre()).count();
            for (DepositBody body : bodies) {
                assertTrue(body.oreCount() <= body.requestedOre(), type.id());
                assertTrue(body.oreCount() >= body.requestedOre() * 0.7, type.id()
                        + " asked " + body.requestedOre() + " got " + body.oreCount());
            }
            if (!type.shape().name().equals("pockets")) {
                assertEquals(bodies.size(), exact, type.id() + " should always fill its quota");
            } else {
                assertTrue(exact >= bodies.size() * 0.8, type.id() + " filled its quota only " + exact + " times");
            }
        }
    }

    @Test
    void richOreIsASubsetOfOreAndFollowsTheRichShareRule() {
        for (OreType type : OreTypes.ALL) {
            for (DepositBody body : BodySamples.of(type)) {
                int[] counts = count(body);
                assertEquals(body.oreCount(), counts[DepositBody.ORE] + counts[DepositBody.RICH], type.id());
                assertEquals(body.richCount(), counts[DepositBody.RICH], type.id());
                assertEquals(Math.round(body.oreCount() * type.richShareFor(body.oreCount())), body.richCount(), type.id());
                assertEquals(body.hostCount(), counts[DepositBody.HOST], type.id());
                assertEquals(body.voidCount(), counts[DepositBody.VOID], type.id());
            }
        }
    }

    @Test
    void reachStaysWithinTheOresBoundAndNothingIsEverTrimmed() {
        for (OreType type : OreTypes.ALL) {
            int bound = type.maxHorizontalReach();
            for (DepositBody body : BodySamples.of(type)) {
                assertFalse(body.clipped(), type.id() + " was trimmed");
                assertTrue(body.reachX() <= bound && body.reachZ() <= bound,
                        type.id() + " reached " + body.reachX() + "/" + body.reachZ() + " of allowed " + bound);
            }
        }
    }

    @Test
    void depositsStayAboveBedrockAndBelowTheSky() {
        for (OreType type : OreTypes.ALL) {
            for (DepositBody body : BodySamples.of(type)) {
                int[] range = {Integer.MAX_VALUE, Integer.MIN_VALUE};
                body.forEach((dx, dy, dz, kind) -> {
                    range[0] = Math.min(range[0], body.centerY() + dy);
                    range[1] = Math.max(range[1], body.centerY() + dy);
                });
                assertTrue(range[0] >= type.realm().lowestY(), type.id() + " dips to y=" + range[0]);
                assertTrue(range[1] <= type.realm().highestY(), type.id() + " rises to y=" + range[1]);
            }
        }
    }

    @Test
    void heightsFollowTheDepthProfile() {
        for (OreType type : OreTypes.ALL) {
            for (DepositBody body : BodySamples.of(type)) {
                assertTrue(body.drawnY() >= type.depth().minY() && body.drawnY() <= type.depth().maxY(), type.id());
                int origin = type.depth().originY();
                if (body.drawnY() >= origin) {
                    assertEquals(1.0, body.sizeMultiplier(), 0.0, type.id());
                } else if (body.drawnY() <= origin - DepthProfile.RAMP) {
                    assertTrue(body.sizeMultiplier() >= type.depth().deepMin() - 1.0e-9
                            && body.sizeMultiplier() <= type.depth().deepMax() + 1.0e-9, type.id());
                }
                // Only bodies pushed off bedrock or the sky (or the nether roof) move from the height they drew.
                if (body.centerY() != body.drawnY()) {
                    boolean nearBedrock = body.centerY() - body.reachY() <= type.realm().lowestY() + 1;
                    boolean nearSky = body.centerY() + body.reachY() >= type.realm().highestY() - 1;
                    assertTrue(nearBedrock || nearSky, type.id() + " moved without a reason");
                }
            }
        }
    }

    @Test
    void deepDepositsAreBiggerThanShallowOnesForOresThatSpanTheOrigin() {
        for (OreType type : OreTypes.ALL) {
            int origin = type.depth().originY();
            if (type.depth().minY() >= origin || type.depth().maxY() < origin + 20) {
                continue;
            }
            double shallow = 0;
            double deep = 0;
            int shallowN = 0;
            int deepN = 0;
            for (DepositBody body : BodySamples.of(type)) {
                if (body.drawnY() >= origin) {
                    shallow += body.oreCount();
                    shallowN++;
                } else if (body.drawnY() <= origin - DepthProfile.RAMP) {
                    deep += body.oreCount();
                    deepN++;
                }
            }
            if (shallowN >= 5 && deepN >= 5) {
                assertTrue(deep / deepN > 2.5 * shallow / shallowN,
                        type.id() + " deep mean " + deep / deepN + " vs shallow " + shallow / shallowN);
            }
        }
    }

    @Test
    void everyNetherDepositIsDeepSized() {
        for (OreType type : OreTypes.forRealm(Realm.NETHER)) {
            for (DepositBody body : BodySamples.of(type)) {
                assertTrue(body.sizeMultiplier() >= type.depth().deepMin() - 1.0e-9
                        && body.sizeMultiplier() <= type.depth().deepMax() + 1.0e-9,
                        type.id() + " multiplier " + body.sizeMultiplier());
                // Deep-sized means at least deepMin times the smallest shallow deposit.
                assertTrue(body.requestedOre() >= Math.round(type.size().min() * type.depth().deepMin()) - 1, type.id());
            }
        }
    }

    @Test
    void cavitiesAppearOnlyInTheHollowPocketOres() {
        for (OreType type : OreTypes.ALL) {
            boolean hollow = type.id().equals("crystal") || type.id().equals("nether_quartz");
            for (DepositBody body : BodySamples.of(type)) {
                assertEquals(hollow, body.voidCount() > 0, type.id());
            }
        }
    }

    @Test
    void atAndForEachAgree() {
        Random random = new Random(7);
        for (OreType type : OreTypes.ALL) {
            DepositBody body = BodySamples.of(type).get(3);
            int[] seen = {0};
            body.forEach((dx, dy, dz, kind) -> {
                assertEquals(kind, body.at(dx, dy, dz), type.id());
                assertTrue(kind != DepositBody.EMPTY);
                seen[0]++;
            });
            assertEquals(body.oreCount() + body.hostCount() + body.voidCount(), seen[0], type.id());
            // Blocks nobody listed must be empty, including far outside the box.
            int reach = Math.max(body.reachX(), Math.max(body.reachY(), body.reachZ()));
            for (int i = 0; i < 2000; i++) {
                int dx = random.nextInt(2 * reach + 40) - reach - 20;
                int dy = random.nextInt(2 * reach + 40) - reach - 20;
                int dz = random.nextInt(2 * reach + 40) - reach - 20;
                byte kind = body.at(dx, dy, dz);
                if (Math.abs(dx) > body.reachX() || Math.abs(dy) > body.reachY() || Math.abs(dz) > body.reachZ()) {
                    assertEquals(DepositBody.EMPTY, kind, type.id());
                }
            }
        }
    }

    @Test
    void everyBodyHasOre() {
        for (OreType type : OreTypes.ALL) {
            for (DepositBody body : BodySamples.of(type)) {
                assertTrue(body.oreCount() > 0, type.id());
                assertTrue(body.richCount() > 0, type.id() + " should have some rich ore");
            }
        }
    }

    private static int[] count(DepositBody body) {
        int[] counts = new int[5];
        body.forEach((dx, dy, dz, kind) -> counts[kind]++);
        return counts;
    }

    /** Every non-empty block, in order, as text, so two bodies can be compared exactly. */
    private static List<String> cells(DepositBody body) {
        List<String> all = new ArrayList<>();
        body.forEach((dx, dy, dz, kind) -> all.add(dx + "," + dy + "," + dz + ":" + kind));
        return all;
    }
}
