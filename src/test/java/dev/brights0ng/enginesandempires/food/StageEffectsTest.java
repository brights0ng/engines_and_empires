package dev.brights0ng.enginesandempires.food;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

/** Stage eating values: scaling food and saturation, and reading effects from config lines. */
class StageEffectsTest {

    @Test
    void foodIsScaledAndRounded() {
        StageEffects stale = new StageEffects(0.75, 0.75, List.of());
        StageEffects rotting = new StageEffects(0.5, 0.5, List.of());
        assertEquals(4, stale.nutrition(5), "bread: 3.75 rounds to 4");
        assertEquals(3, rotting.nutrition(5), "bread: 2.5 rounds to 3");
        assertEquals(6, stale.nutrition(8), "steak");
        assertEquals(4, rotting.nutrition(8), "steak");
        assertEquals(4.5f, stale.saturation(6f), 1e-6);
        assertEquals(3f, rotting.saturation(6f), 1e-6);
        assertEquals(5, StageEffects.NONE.nutrition(5));
    }

    @Test
    void effectLinesParse() {
        StageEffects.EffectSpec poison = StageEffects.EffectSpec.parse("minecraft:poison 3").orElseThrow();
        assertEquals("minecraft:poison", poison.effect());
        assertEquals(60, poison.durationTicks());
        assertEquals(1, poison.level());
        assertEquals(1f, poison.chance());
        StageEffects.EffectSpec hunger = StageEffects.EffectSpec.parse("  minecraft:hunger 30 2 0.5 ").orElseThrow();
        assertEquals(600, hunger.durationTicks());
        assertEquals(2, hunger.level());
        assertEquals(0.5f, hunger.chance());
    }

    @Test
    void badLinesAreRejected() {
        for (String bad : List.of("", "minecraft:poison", "minecraft:poison x", "minecraft:poison 3 0", "minecraft:poison 3 1 2",
                "minecraft:poison -1", "minecraft:poison 3 1 0.5 extra")) {
            assertTrue(StageEffects.EffectSpec.parse(bad).isEmpty(), "should reject '" + bad + "'");
        }
    }

    @Test
    void theDefaultsAreBrightsValues() {
        StageEffects rotting = StageEffects.DEFAULTS.get(FoodStage.ROTTING);
        assertEquals(0.5, rotting.foodMultiplier());
        assertEquals(List.of("minecraft:poison", "minecraft:weakness", "minecraft:nausea"),
                rotting.effects().stream().map(StageEffects.EffectSpec::effect).toList());
        assertEquals(List.of(60, 400, 400), rotting.effects().stream().map(StageEffects.EffectSpec::durationTicks).toList());
        assertEquals(0.75, StageEffects.DEFAULTS.get(FoodStage.STALE).foodMultiplier());
        assertEquals(StageEffects.NONE, StageEffects.DEFAULTS.get(FoodStage.RIPE));
    }

    @Test
    void dietNutritionDefaults() {
        assertEquals(1.25, StageEffects.DEFAULTS.get(FoodStage.FRESH).nutritionMultiplier());
        assertEquals(1.0, StageEffects.DEFAULTS.get(FoodStage.RIPE).nutritionMultiplier());
        assertEquals(0.5, StageEffects.DEFAULTS.get(FoodStage.STALE).nutritionMultiplier());
        assertEquals(0.0, StageEffects.DEFAULTS.get(FoodStage.ROTTING).nutritionMultiplier());
        assertEquals(0.025f, StageEffects.DEFAULTS.get(FoodStage.FRESH).dietGain(0.02f), 1e-6);
        assertEquals(0.01f, StageEffects.DEFAULTS.get(FoodStage.STALE).dietGain(0.02f), 1e-6);
        assertEquals(0f, StageEffects.DEFAULTS.get(FoodStage.ROTTING).dietGain(0.02f));
    }

    @Test
    void freshFoodIsOtherwiseUnchanged() {
        // The fresh bonus is nutrition only: fresh food's own food, saturation and effects are left alone
        assertTrue(!StageEffects.DEFAULTS.get(FoodStage.FRESH).changesFood());
        assertTrue(!StageEffects.NONE.changesFood());
        assertTrue(StageEffects.DEFAULTS.get(FoodStage.STALE).changesFood());
    }
}
