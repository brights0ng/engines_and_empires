package dev.brights0ng.enginesandempires.oregen;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

/** Biome rules: which one applies, and that they scale shallow deposits only, past the size cap. */
class BiomeSizeTest {

    private static final List<BiomeRule> RULES = List.of(
            BiomeRule.of("#minecraft:is_mountain", 1.5),
            BiomeRule.of("#minecraft:is_badlands", 2.5),
            BiomeRule.of("#minecraft:is_ocean", 0.6),
            BiomeRule.of("minecraft:meadow", 1.5));

    private static double factor(String... matching) {
        Set<String> set = Set.of(matching);
        return BiomeRule.factorFor(RULES, rule -> set.contains(rule.selector()));
    }

    @Test
    void noMatchLeavesSizeAlone() {
        assertEquals(1.0, factor());
    }

    @Test
    void otherwiseAppliesOnlyWhereNothingElseMatched() {
        List<BiomeRule> rules = List.of(
                BiomeRule.of("#minecraft:is_mountain", 3.0),
                BiomeRule.of(BiomeRule.OTHERWISE, 0.25),
                BiomeRule.of("minecraft:meadow", 1.0));
        // The fallback is stronger on a log scale than x3, but it must not compete with real matches.
        assertEquals(3.0, BiomeRule.factorFor(rules, rule -> rule.selector().equals("#minecraft:is_mountain")));
        assertEquals(0.25, BiomeRule.factorFor(rules, rule -> false));
        // A matching rule of exactly 1 still counts as a match.
        assertEquals(1.0, BiomeRule.factorFor(rules, rule -> rule.selector().equals("minecraft:meadow")));
        BiomeRule.factorFor(rules, rule -> {
            assertFalse(rule.isOtherwise(), "the fallback was tested against a biome");
            return false;
        });
    }

    @Test
    void frozenOceansStayPoorForDiamond() {
        double frozenOcean = BiomeRule.factorFor(OreTypes.DIAMOND.biomes(),
                rule -> rule.selector().equals("#minecraft:is_ocean") || rule.selector().equals("#c:is_snowy"));
        assertEquals(0.5, frozenOcean);
    }

    private static BiomeRule badlandsGold() {
        return BiomeRule.select(OreTypes.GOLD.biomes(), rule -> rule.selector().equals("#minecraft:is_badlands"));
    }

    @Test
    void badlandsGoldReachesY100AndKeepsItsAverageOre() {
        BiomeRule rule = badlandsGold();
        DepthProfile normal = OreTypes.GOLD.depth();
        assertTrue(rule.raisesHeight());
        assertEquals(100, rule.maxY());
        assertEquals(100, rule.depthFor(normal).maxY());
        assertEquals(normal.minY(), rule.depthFor(normal).minY());
        assertTrue(rule.factor() > 3.0 && rule.factor() < 4.0, "badlands gold factor " + rule.factor());
        // The raised range with the scaled factor holds as much ore on average as the normal range at x2.5.
        assertEquals(normal.meanSize(2.5), rule.depthFor(normal).meanSize(rule.factor()), 1e-9);
        // Elsewhere gold keeps its normal range.
        assertFalse(BiomeRule.select(OreTypes.GOLD.biomes(), r -> r.selector().equals("#minecraft:is_ocean")).raisesHeight());
    }

    @Test
    void raisedRangesMustGoUp() {
        DepthProfile depth = new DepthProfile(-56, 32);
        assertThrows(IllegalArgumentException.class, () -> BiomeRule.raising("#minecraft:is_badlands", 2.5, depth, 32));
        assertThrows(IllegalArgumentException.class, () -> BiomeRule.raising("#minecraft:is_badlands", 2.5, depth, 10));
    }

    @Test
    void badlandsGoldIsDrawnUpToY100AndAverageOreIsKept() {
        OreType gold = OreTypes.GOLD;
        BiomeRule rule = badlandsGold();
        OreMap map = new OreMap(BodySamples.SEED, gold.layer());
        int above32 = 0;
        long plainOre = 0;
        long mesaOre = 0;
        int n = 0;
        for (Deposit deposit : map.depositsInBox(-60_000, -60_000, 60_000, 60_000)) {
            DepositBody plain = DepositBody.generate(deposit, gold, 0, 2.5);
            DepositBody mesa = DepositBody.generate(deposit, gold, 0, rule);
            assertTrue(mesa.drawnY() >= -56 && mesa.drawnY() <= 100, "drawn at " + mesa.drawnY());
            assertTrue(plain.drawnY() <= 32);
            if (mesa.drawnY() > 32) {
                above32++;
                assertEquals(rule.factor(), mesa.biomeMultiplier());
            }
            assertFalse(mesa.clipped(), "a raised badlands body was clipped");
            plainOre += plain.requestedOre();
            mesaOre += mesa.requestedOre();
            if (++n >= 1500) {
                break;
            }
        }
        assertTrue(above32 > n / 5, "only " + above32 + " of " + n + " drawn above y=32");
        // Same gold on average, within sampling noise (sizes are capped and vary a lot).
        double ratio = mesaOre / (double) plainOre;
        assertTrue(ratio > 0.9 && ratio < 1.1, "badlands gold total changed by x" + ratio);
    }

    @Test
    void cacheKeepsRaisedRulesApartFromPlainFactors() {
        OreType gold = OreTypes.GOLD;
        BiomeRule rule = badlandsGold();
        BodyCache cache = new BodyCache();
        for (Deposit deposit : new OreMap(BodySamples.SEED, gold.layer()).depositsInBox(-40_000, -40_000, 40_000, 40_000)) {
            DepositBody raised = cache.get(deposit, gold, 0, rule);
            DepositBody sameFactor = cache.get(deposit, gold, 0, rule.factor());
            if (raised.drawnY() != sameFactor.drawnY()) {
                return;
            }
        }
        throw new AssertionError("the raised range never changed a draw");
    }

    @Test
    void strongestMatchWinsAndRulesNeverStack() {
        assertEquals(2.5, factor("#minecraft:is_mountain", "#minecraft:is_badlands"));
        // 0.6 is further from 1 than 1.5 on a log scale.
        assertEquals(0.6, factor("#minecraft:is_mountain", "#minecraft:is_ocean"));
        assertEquals(1.5, factor("#minecraft:is_mountain", "minecraft:meadow"));
    }

    @Test
    void selectorsAndFactorsAreChecked() {
        assertThrows(IllegalArgumentException.class, () -> BiomeRule.of("badlands", 2.0));
        assertThrows(IllegalArgumentException.class, () -> BiomeRule.of("#minecraft:is_badlands", 0.0));
        assertThrows(IllegalArgumentException.class, () -> BiomeRule.of("#minecraft:is_badlands", Double.NaN));
        assertTrue(BiomeRule.of("#c:is_swamp", 1.5).isTag());
        assertEquals("c:is_swamp", BiomeRule.of("#c:is_swamp", 1.5).id());
        assertFalse(BiomeRule.of("minecraft:swamp", 1.5).isTag());
    }

    @Test
    void biomeScalesShallowDepositsOnlyAndCanPassTheCap() {
        OreType gold = OreTypes.GOLD;
        OreMap map = new OreMap(BodySamples.SEED, gold.layer());
        int shallow = 0;
        int deep = 0;
        int overCap = 0;
        for (Deposit deposit : map.depositsInBox(-40_000, -40_000, 40_000, 40_000)) {
            DepositBody plain = DepositBody.generate(deposit, gold, 0, 1.0);
            DepositBody mesa = DepositBody.generate(deposit, gold, 0, 2.5);
            assertEquals(plain.drawnY(), mesa.drawnY(), "the biome must not change the draw");
            if (gold.depth().isShallow(plain.drawnY())) {
                shallow++;
                assertEquals(2.5, mesa.biomeMultiplier());
                assertTrue(mesa.requestedOre() >= plain.requestedOre() * 2.5 * 0.5,
                        "boosted " + mesa.requestedOre() + " from " + plain.requestedOre());
                if (mesa.oreCount() > gold.size().max()) {
                    overCap++;
                }
            } else {
                deep++;
                assertEquals(1.0, mesa.biomeMultiplier());
                assertEquals(plain.requestedOre(), mesa.requestedOre(), "deep deposits ignore biome");
            }
            assertFalse(mesa.clipped(), "a boosted body was clipped at the reach bound");
            if (shallow >= 60 && deep >= 60) {
                break;
            }
        }
        assertTrue(shallow > 10 && deep > 10, "too few samples: " + shallow + " shallow, " + deep + " deep");
        assertTrue(overCap > 0, "no boosted gold deposit passed the size cap");
    }

    @Test
    void cutsShrinkShallowDeposits() {
        OreType iron = OreTypes.IRON;
        OreMap map = new OreMap(BodySamples.SEED, iron.layer());
        int checked = 0;
        for (Deposit deposit : map.depositsInBox(-20_000, -20_000, 20_000, 20_000)) {
            DepositBody plain = DepositBody.generate(deposit, iron, 0, 1.0);
            if (!iron.depth().isShallow(plain.drawnY())) {
                continue;
            }
            DepositBody ocean = DepositBody.generate(deposit, iron, 0, 0.75);
            assertTrue(ocean.requestedOre() < plain.requestedOre(), "ocean iron was not smaller");
            if (++checked >= 30) {
                break;
            }
        }
        assertTrue(checked > 0);
    }

    @Test
    void cacheKeepsBiomeVariantsApart() {
        OreType gold = OreTypes.GOLD;
        BodyCache cache = new BodyCache();
        for (Deposit deposit : new OreMap(BodySamples.SEED, gold.layer()).depositsInBox(-40_000, -40_000, 40_000, 40_000)) {
            DepositBody plain = cache.get(deposit, gold, 0, 1.0);
            if (gold.depth().isShallow(plain.drawnY())) {
                DepositBody mesa = cache.get(deposit, gold, 0, 2.5);
                assertTrue(mesa.requestedOre() > plain.requestedOre());
                assertEquals(plain.requestedOre(), cache.get(deposit, gold, 0, 1.0).requestedOre());
                return;
            }
        }
        throw new AssertionError("no shallow gold deposit found");
    }

    @Test
    void maxOreAllowsForBiomeBoosts() {
        // Emerald is shallow throughout, so its biggest deposit comes from the biome boost.
        OreType emerald = OreTypes.EMERALD;
        assertEquals((int) Math.ceil(emerald.size().max() * 3.0), emerald.maxOre());
        // Coal likewise: its swamp boost lifts its cap.
        assertEquals((int) Math.ceil(OreTypes.COAL.size().max() * 2.5), OreTypes.COAL.maxOre());
        // Gold's deep multiplier (8) is larger than its best biome factor, so that still sets the bound.
        assertEquals(OreTypes.GOLD.size().max() * 8, OreTypes.GOLD.maxOre());
    }

    @Test
    void netherOresHaveNoBiomeRules() {
        assertFalse(OreTypes.NETHER_GOLD.hasBiomeRules());
        assertFalse(OreTypes.NETHER_QUARTZ.hasBiomeRules());
    }

    @Test
    void diamondAndRedstoneReachAboveTheOriginSoTheirRulesApply() {
        assertTrue(OreTypes.DIAMOND.depth().isShallow(OreTypes.DIAMOND.depth().maxY()));
        assertTrue(OreTypes.DIAMOND.depth().shallowOreShare() > 0.0);
        assertEquals(32, OreTypes.REDSTONE.depth().maxY());
        assertEquals(24, OreTypes.DIAMOND.depth().maxY());
    }

    @Test
    void shallowShareIsWeightedByDepthSize() {
        assertEquals(1.0, OreTypes.COAL.depth().shallowOreShare(), 1e-9);
        double gold = OreTypes.GOLD.depth().shallowOreShare();
        // 33 of gold's 89 heights are shallow, but deep deposits are ~6x bigger.
        assertTrue(gold > 0.05 && gold < 0.2, "gold shallow share " + gold);
    }
}
