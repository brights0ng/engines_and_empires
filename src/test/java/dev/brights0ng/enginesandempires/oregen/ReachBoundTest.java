package dev.brights0ng.enginesandempires.oregen;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Random;

import org.junit.jupiter.api.Test;

import dev.brights0ng.enginesandempires.oregen.shape.BodyShape;
import dev.brights0ng.enginesandempires.oregen.shape.ShapeField;
import dev.brights0ng.enginesandempires.oregen.shape.ShapeRequest;

/**
 * Worldgen trusts each shape's {@code maxReach} to say how far beyond a chunk it must look for deposits
 * that could reach in. If a shape ever exceeds its own bound, deposits would be cut off at chunk borders.
 * These tests hammer that bound with many random deposits, at every size from tiny to the largest.
 */
class ReachBoundTest {

    private static final int TRIALS = 20_000;

    @Test
    void noShapeEverExceedsItsHorizontalReachBound() {
        for (OreType type : OreTypes.ALL) {
            BodyShape shape = type.shape();
            int reference = type.size().median();
            int maxOre = type.maxOre();
            int bound = shape.maxReach(maxOre, reference);
            Random random = new Random(type.id().hashCode());

            int worst = 0;
            for (int trial = 0; trial < TRIALS; trial++) {
                // Sizes from a single block to the ore's largest, weighted towards the big end where reach is greatest.
                int wanted = trial % 4 == 0 ? maxOre : 1 + random.nextInt(maxOre);
                ShapeField field = shape.create(new ShapeRequest(new DepositRandom(random.nextLong()), wanted, reference));
                worst = Math.max(worst, Math.max(field.reachX(), field.reachZ()));
                assertTrue(field.reachX() <= bound && field.reachZ() <= bound,
                        type.id() + " reached " + field.reachX() + "/" + field.reachZ() + " for " + wanted
                                + " ore, but its bound is " + bound);
            }
            // A bound far above anything seen is wasteful, though safe. Report it so a bad formula is noticed.
            assertTrue(worst * 5 >= bound || bound <= 40, type.id() + " bound " + bound + " is very loose; worst seen " + worst);
        }
    }

    @Test
    void largestSizesAreCheapToDescribeEvenAtTheCap() {
        Map<String, Integer> reaches = new LinkedHashMap<>();
        for (OreType type : OreTypes.ALL) {
            Random random = new Random(1);
            int most = 0;
            for (int i = 0; i < 500; i++) {
                ShapeField field = type.shape().create(new ShapeRequest(new DepositRandom(random.nextLong()),
                        type.maxOre(), type.size().median()));
                long box = (2L * field.reachX() + 1) * (2L * field.reachY() + 1) * (2L * field.reachZ() + 1);
                assertTrue(box <= 3_000_000L, type.id() + " would need a box of " + box + " blocks");
                most = Math.max(most, field.reachY());
            }
            reaches.put(type.id(), most);
        }
        assertTrue(reaches.values().stream().allMatch(r -> r <= 96 + 30), "vertical reaches: " + reaches);
    }

    /**
     * A deposit taller than the space between its realm's bedrock floor and roof cannot fit in it. The
     * Nether has only about 118 usable blocks of height and its deposits are deep-sized, so a raw shape
     * can be too tall (a nether quartz cluster strung along a vertical fracture reaches 133). The builder
     * must always end up with geometry that fits, and almost always without giving up any ore.
     */
    @Test
    void everyDepositIsMadeToFitBetweenItsRealmsFloorAndCeiling() {
        for (OreType type : OreTypes.ALL) {
            int limit = DepositBody.verticalLimit(type.realm());
            assertTrue(2 * limit + 1 <= type.realm().highestY() - type.realm().lowestY() + 1, type.id());
            Random random = new Random(type.id().hashCode() * 31L + 7);
            int trials = 4000;
            int keptFullSize = 0;
            for (int trial = 0; trial < trials; trial++) {
                int wanted = trial % 2 == 0 ? type.maxOre() : 1 + random.nextInt(type.maxOre());
                DepositBody.Fit fit = DepositBody.fit(type, new DepositRandom(random.nextLong()), wanted);
                assertTrue(fit.field().reachY() <= limit, type.id() + " still reaches " + fit.field().reachY()
                        + " vertically for " + wanted + " ore, but the room allows " + limit);
                assertTrue(fit.wanted() >= wanted * 0.5, type.id() + " had to shrink " + wanted + " ore to " + fit.wanted());
                if (fit.wanted() == wanted) {
                    keptFullSize++;
                }
            }
            assertTrue(keptFullSize >= trials * 0.95,
                    type.id() + " kept its full size only " + keptFullSize + " times in " + trials);
        }
    }
}
