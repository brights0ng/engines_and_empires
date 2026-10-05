package dev.brights0ng.enginesandempires.oregen;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

import dev.brights0ng.enginesandempires.oregen.shape.VeinShape;

class OreTypesTest {

    private static Set<String> ids(Iterable<OreType> types) {
        Set<String> ids = new HashSet<>();
        for (OreType type : types) {
            ids.add(type.id());
        }
        return ids;
    }

    @Test
    void everyOreIsDefinedInItsRealm() {
        assertEquals(Set.of("coal", "iron", "copper", "zinc", "redstone", "lapis", "crystal",
                "gold", "emerald", "diamond"), ids(OreTypes.forRealm(Realm.OVERWORLD)));
        assertEquals(Set.of("nether_quartz", "nether_gold"), ids(OreTypes.forRealm(Realm.NETHER)));
        assertEquals(12, OreTypes.ALL.size());
    }

    /** Quartz is a nether ore. It must never be generated in the overworld. */
    @Test
    void noQuartzGeneratesInTheOverworld() {
        for (OreType type : OreTypes.forRealm(Realm.OVERWORLD)) {
            assertFalse(type.id().contains("quartz"), type.id());
        }
        assertEquals(Realm.NETHER, OreTypes.NETHER_QUARTZ.realm());
    }

    @Test
    void netherGoldIsTreatedLikeOverworldGold() {
        OreType overworld = OreTypes.GOLD;
        OreType nether = OreTypes.NETHER_GOLD;
        assertEquals(Realm.NETHER, nether.realm());
        assertSame(overworld.shape(), nether.shape());
        assertEquals(overworld.shape(), VeinShape.GOLD);
        assertEquals(overworld.size(), nether.size());
        assertEquals(overworld.richShare(), nether.richShare(), 0.0);
        assertEquals(384, nether.layer().scale());
    }

    /** Nether ores are spaced much closer together than the overworld's (Bright's choice: gold 384, quartz 128). */
    @Test
    void netherOresAreSpacedCloserThanTheOverworlds() {
        assertEquals(384, OreTypes.NETHER_GOLD.layer().scale());
        assertEquals(128, OreTypes.NETHER_QUARTZ.layer().scale());
        assertTrue(OreTypes.NETHER_GOLD.layer().scale() < OreTypes.GOLD.layer().scale());
    }

    @Test
    void everyNetherDepositIsDeepSizedWhateverItsHeight() {
        for (OreType type : OreTypes.forRealm(Realm.NETHER)) {
            DepthProfile depth = type.depth();
            assertEquals(8.0, depth.maxMultiplier(), 0.0, type.id());
            for (int y = depth.minY(); y <= depth.maxY(); y++) {
                assertEquals(4.0, depth.sizeMultiplier(y, 0.0), 1.0e-9, type.id() + " at y=" + y);
                assertEquals(8.0, depth.sizeMultiplier(y, 1.0), 1.0e-9, type.id() + " at y=" + y);
            }
        }
    }

    @Test
    void overworldOresAreStillOnlyDeepBelowTheOrigin() {
        for (OreType type : OreTypes.forRealm(Realm.OVERWORLD)) {
            assertEquals(DepthProfile.ORIGIN_Y, type.depth().originY(), type.id());
            assertEquals(1.0, type.depth().sizeMultiplier(DepthProfile.ORIGIN_Y, 1.0), 0.0, type.id());
        }
    }

    @Test
    void lookupByIdFindsTheSameTypes() {
        for (OreType type : OreTypes.ALL) {
            assertSame(type, OreTypes.byId(type.id()));
        }
        assertThrows(IllegalArgumentException.class, () -> OreTypes.byId("unobtainium"));
        assertThrows(IllegalArgumentException.class, () -> OreTypes.byId("quartz"));
    }

    /** The assignment agreed for the pack: change this test only when the design changes. */
    @Test
    void shapesAreAssignedAsDesigned() {
        Map<String, String> expected = Map.ofEntries(
                Map.entry("iron", "lump"), Map.entry("zinc", "lump"),
                Map.entry("coal", "seam"), Map.entry("lapis", "seam"),
                Map.entry("gold", "vein"), Map.entry("redstone", "vein"), Map.entry("nether_gold", "vein"),
                Map.entry("copper", "disseminated"), Map.entry("diamond", "disseminated"),
                Map.entry("emerald", "pockets"), Map.entry("crystal", "pockets"), Map.entry("nether_quartz", "pockets"));
        for (Map.Entry<String, String> entry : expected.entrySet()) {
            assertEquals(entry.getValue(), OreTypes.byId(entry.getKey()).shape().name(), entry.getKey());
        }
        assertEquals(expected.size(), OreTypes.ALL.size());
    }

    @Test
    void spacingsAreSetForTheOresTheDesignPinnedDown() {
        assertEquals(384, OreTypes.IRON.layer().scale());
        assertEquals(1408, OreTypes.GOLD.layer().scale());
    }

    @Test
    void depthRangesLieInsideTheirRealms() {
        for (OreType type : OreTypes.ALL) {
            assertTrue(type.depth().minY() >= type.realm().lowestY(), type.id());
            assertTrue(type.depth().maxY() <= type.realm().highestY(), type.id());
        }
    }

    @Test
    void anOreWhoseDepthsLeaveItsRealmIsRejected() {
        OreType gold = OreTypes.GOLD;
        assertThrows(IllegalArgumentException.class, () -> new OreType(gold.layer(), Realm.NETHER, gold.shape(),
                gold.size(), new DepthProfile(-56, 32), gold.richShare()));
        assertThrows(IllegalArgumentException.class, () -> new OreType(gold.layer(), Realm.NETHER, gold.shape(),
                gold.size(), new DepthProfile(10, 200), gold.richShare()));
    }

    @Test
    void realmsKnowTheirDimensionsAndHeights() {
        assertSame(Realm.OVERWORLD, Realm.ofDimension("minecraft:overworld"));
        assertSame(Realm.NETHER, Realm.ofDimension("minecraft:the_nether"));
        assertNull(Realm.ofDimension("minecraft:the_end"));
        assertNull(Realm.ofDimension("aether:the_aether"));
        assertTrue(Realm.NETHER.lowestY() > 4 && Realm.NETHER.highestY() < 123, "deposits must stay clear of nether bedrock");
        assertTrue(Realm.OVERWORLD.lowestY() > -60 && Realm.OVERWORLD.highestY() <= 319);
    }

    @Test
    void reachBoundsAreWithinTheHardLimit() {
        for (OreType type : OreTypes.ALL) {
            int reach = type.maxHorizontalReach();
            assertTrue(reach > 0 && reach <= OreType.REACH_LIMIT, type.id() + " reach " + reach);
        }
    }

    @Test
    void oreCapScalesWithTheDeepestMultiplier() {
        assertEquals(1250, OreTypes.COAL.maxOre());         // all above y = 0: only its best biome boost (x2.5)
        assertEquals(4000, OreTypes.IRON.maxOre());         // reaches below y = 0: cap x 8
        assertEquals(240, OreTypes.EMERALD.maxOre());       // all above y = 0: highland boost x3
        assertEquals(2400, OreTypes.NETHER_GOLD.maxOre());  // every nether deposit is deep-sized: cap x 8
    }

    @Test
    void richShareFallsAsDepositsGrowAndIsCapped() {
        OreType iron = OreTypes.IRON;
        int median = iron.size().median();
        assertEquals(iron.richShare(), iron.richShareFor(median), 1.0e-12);
        assertTrue(iron.richShareFor(median / 2) > iron.richShareFor(median));
        assertTrue(iron.richShareFor(median * 8) < iron.richShareFor(median));
        assertTrue(iron.richShareFor(1) <= 0.6);
    }
}
