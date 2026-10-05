package dev.brights0ng.enginesandempires.food;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

/** Sorting loot tables and traders into freshness buckets, rolling a stage, and landing inside it. */
class FreshnessSourcesTest {

    private static final FreshnessSources.Bucket VILLAGE = new FreshnessSources.Bucket("village",
            List.of("minecraft:chests/village/*"), List.of("minecraft:villager"), Map.of(FoodStage.FRESH, 1, FoodStage.RIPE, 1));
    private static final FreshnessSources.Bucket HOSTILE = new FreshnessSources.Bucket("hostile",
            List.of("minecraft:chests/pillager_outpost", "minecraft:chests/woodland_mansion", "minecraft:chests/bastion_*"),
            List.of(), Map.of(FoodStage.RIPE, 1, FoodStage.STALE, 1));
    private static final FreshnessSources.Bucket OTHER = new FreshnessSources.Bucket("other",
            List.of("*"), List.of(), Map.of(FoodStage.STALE, 1, FoodStage.ROTTING, 1));
    private static final FreshnessSources SOURCES = new FreshnessSources(List.of(OTHER, HOSTILE, VILLAGE));

    private static String bucket(String lootTable) {
        return SOURCES.forLootTable(lootTable).map(FreshnessSources.Bucket::id).orElse("none");
    }

    @Test
    void lootTablesFindTheirBucket() {
        assertEquals("village", bucket("minecraft:chests/village/village_plains_house"));
        assertEquals("village", bucket("minecraft:chests/village/village_weaponsmith"));
        assertEquals("hostile", bucket("minecraft:chests/pillager_outpost"));
        assertEquals("hostile", bucket("minecraft:chests/woodland_mansion"));
        assertEquals("hostile", bucket("minecraft:chests/bastion_treasure"));
        assertEquals("other", bucket("minecraft:chests/ruined_portal"));
        assertEquals("other", bucket("minecraft:chests/shipwreck_supply"));
        assertEquals("other", bucket("somemod:chests/farmhouse"));
        assertEquals("other", bucket(""));
    }

    @Test
    void anExactIdBeatsAPattern() {
        FreshnessSources.Bucket farmhouse = new FreshnessSources.Bucket("farmhouse",
                List.of("minecraft:chests/village/village_plains_house"), List.of(), Map.of(FoodStage.FRESH, 1));
        FreshnessSources sources = new FreshnessSources(List.of(farmhouse, OTHER, VILLAGE));
        assertEquals("farmhouse", sources.forLootTable("minecraft:chests/village/village_plains_house").orElseThrow().id());
        assertEquals("village", sources.forLootTable("minecraft:chests/village/village_desert_house").orElseThrow().id());
    }

    @Test
    void tradersMatchExactly() {
        assertEquals("village", SOURCES.forTrader("minecraft:villager").orElseThrow().id());
        assertTrue(SOURCES.forTrader("minecraft:wandering_trader").isEmpty(), "unlisted traders sell fresh food");
    }

    @Test
    void rollsFollowTheWeights() {
        assertEquals(FoodStage.FRESH, VILLAGE.roll(0.0));
        assertEquals(FoodStage.FRESH, VILLAGE.roll(0.49));
        assertEquals(FoodStage.RIPE, VILLAGE.roll(0.5));
        assertEquals(FoodStage.RIPE, VILLAGE.roll(1.0));
        assertEquals(FoodStage.STALE, OTHER.roll(0.2));
        assertEquals(FoodStage.ROTTING, OTHER.roll(0.7));
        assertEquals(FoodStage.FRESH, new FreshnessSources.Bucket("x", List.of(), List.of(), Map.of()).roll(0.9));
    }

    @Test
    void agesLandInsideTheirStage() {
        SpoilTimes times = new SpoilTimes(1000, 2000, 3000);
        for (FoodStage stage : FoodStage.values()) {
            for (double f : new double[] {0, 0.3, 0.999, 1}) {
                assertEquals(stage, times.stage(times.ageWithin(stage, f)), stage + " at " + f);
            }
        }
    }
}
