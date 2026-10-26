// Written by ChatGPT.
package dev.brights0ng.empire.rules;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class CivilianFoodTest {

    @Test
    void initialStateMatchesVanilla() {
        assertEquals(new CivilianFood.Saved(20, 5.0F, 0.0F, 0, 0, 0, 0L), new CivilianFood().save());
    }

    @Test
    void exactlyFourExhaustionDoesNothing() {
        CivilianFood food = new CivilianFood();
        food.addExhaustion(4.0F);
        food.tick(30, 30, true, false);
        assertEquals(4.0F, food.exhaustion());
        assertEquals(5.0F, food.saturation());
        assertEquals(20, food.foodLevel());
    }

    @Test
    void moreThanFourExhaustionConsumesOneSaturation() {
        CivilianFood food = new CivilianFood();
        food.addExhaustion(4.01F);
        food.tick(30, 30, true, false);
        assertEquals(0.01F, food.exhaustion(), 0.00001F);
        assertEquals(4.0F, food.saturation());
        assertEquals(20, food.foodLevel());
    }

    @Test
    void emptySaturationConsumesOneFood() {
        CivilianFood food = state(20, 0);
        food.addExhaustion(4.01F);
        food.tick(30, 30, true, false);
        assertEquals(19, food.foodLevel());
        assertEquals(0.0F, food.saturation());
    }

    @Test
    void peacefulConsumesExhaustionButNoFood() {
        CivilianFood food = state(20, 0);
        food.addExhaustion(4.01F);
        food.tick(30, 30, true, true);
        assertEquals(20, food.foodLevel());
        assertEquals(0.01F, food.exhaustion(), 0.00001F);

        food = new CivilianFood();
        food.addExhaustion(4.01F);
        food.tick(30, 30, true, true);
        assertEquals(4.0F, food.saturation(), "Peaceful still consumes saturation");
    }

    @Test
    void exhaustionCapsAtFortyAndProcessesOnlyOncePerTick() {
        CivilianFood food = new CivilianFood();
        food.addExhaustion(39.0F);
        food.addExhaustion(5.0F);
        assertEquals(40.0F, food.exhaustion());
        food.tick(30, 30, true, false);
        assertEquals(36.0F, food.exhaustion());
        assertEquals(4.0F, food.saturation());
    }

    @Test
    void eatingClampsAndCountsAllNutritionEvenWhenFull() {
        CivilianFood food = state(18, 17);
        food.eat(5, 6.0F);
        assertEquals(20, food.foodLevel());
        assertEquals(20.0F, food.saturation());
        assertEquals(5, food.foodEatenToday());
        food.eat(5, 6.0F);
        assertEquals(20, food.foodLevel());
        assertEquals(20.0F, food.saturation());
        assertEquals(10, food.foodEatenToday());
    }

    @Test
    void eatingUsesAbsoluteSaturationAndClampsBothLowerBounds() {
        CivilianFood food = state(5, 0);
        food.eat(5, 6.0F);
        assertEquals(10, food.foodLevel());
        assertEquals(6.0F, food.saturation(), "no nutrition or modifier multiplication");
        food.eat(-30, -30.0F);
        assertEquals(0, food.foodLevel());
        assertEquals(0.0F, food.saturation());
    }

    @Test
    void fastRegenerationHealsOnTheTenthTickAndAddsSixExhaustion() {
        CivilianFood food = state(20, 6);
        assertNoEffectFor(food, 9, 20, 30, true, false);
        assertEquals(9, food.tickTimer());
        assertEquals(new CivilianFood.TickResult(1.0F, 0.0F), food.tick(20, 30, true, false));
        assertEquals(6.0F, food.exhaustion());
        assertEquals(0, food.tickTimer());
    }

    @Test
    void fastRegenerationUsesRemainingSaturationAndCapsAtSix() {
        CivilianFood food = state(20, 3);
        assertNoEffectFor(food, 9, 20, 30, true, false);
        assertEquals(0.5F, food.tick(20, 30, true, false).healAmount());
        assertEquals(3.0F, food.exhaustion());

        food = state(20, 10);
        assertNoEffectFor(food, 9, 20, 30, true, false);
        assertEquals(1.0F, food.tick(20, 30, true, false).healAmount());
        assertEquals(6.0F, food.exhaustion());
    }

    @Test
    void slowRegenerationHealsOnTheEightiethTick() {
        CivilianFood food = state(18, 0);
        assertNoEffectFor(food, 79, 20, 30, true, false);
        assertEquals(new CivilianFood.TickResult(1.0F, 0.0F), food.tick(20, 30, true, false));
        assertEquals(6.0F, food.exhaustion());
        assertEquals(0, food.tickTimer());
    }

    @Test
    void seventeenFoodDoesNotRegenerate() {
        CivilianFood food = state(17, 0);
        assertNoEffectFor(food, 200, 20, 30, true, false);
        assertEquals(0, food.tickTimer());
        assertEquals(0.0F, food.exhaustion());
    }

    @Test
    void gameruleDisablesBothRegenerationBranchesIncludingOnPeaceful() {
        for (boolean peaceful : new boolean[]{false, true}) {
            for (CivilianFood food : new CivilianFood[]{state(20, 6), state(18, 0)}) {
                assertNoEffectFor(food, 200, 20, 30, false, peaceful);
                assertEquals(0, food.tickTimer());
                assertEquals(0.0F, food.exhaustion());
            }
        }
    }

    @Test
    void exhaustionIsProcessedBeforeChoosingTheRegenerationBranch() {
        CivilianFood food = state(20, 1);
        food.addExhaustion(4.01F);
        assertNoEffectFor(food, 79, 20, 30, true, false);
        assertEquals(0.0F, food.saturation());
        assertEquals(1.0F, food.tick(20, 30, true, false).healAmount());
    }

    @Test
    void regenerationBranchesShareTheirTimerLikeVanilla() {
        CivilianFood food = state(18, 0);
        assertNoEffectFor(food, 9, 20, 30, true, false);
        food.eat(2, 6.0F);
        assertEquals(1.0F, food.tick(20, 30, true, false).healAmount());
        assertEquals(0, food.tickTimer());
    }

    @Test
    void starvationDamagesEveryEightyTicksUntilHalfHealthOnEveryDifficulty() {
        for (boolean peaceful : new boolean[]{false, true}) {
            CivilianFood food = state(0, 0);
            float health = 30.0F;
            for (int tick = 1; tick <= 80 * 20; tick++) {
                CivilianFood.TickResult result = food.tick(health, 30, false, peaceful);
                float expected = tick % 80 == 0 && health > 15.0F ? 1.0F : 0.0F;
                assertEquals(0.0F, result.healAmount());
                assertEquals(expected, result.starveDamage(), "tick " + tick);
                health -= result.starveDamage();
                assertTrue(health >= 15.0F);
                if (tick % 80 == 0) {
                    assertEquals(0, food.tickTimer());
                }
            }
            assertEquals(15.0F, health);
        }
    }

    @Test
    void starvationClampsPartialDamageAndResetsTimerAtOrBelowFloor() {
        CivilianFood food = state(0, 0);
        assertNoEffectFor(food, 79, 15.5F, 30, true, false);
        assertEquals(0.5F, food.tick(15.5F, 30, true, false).starveDamage());
        assertNoEffectFor(food, 80, 15, 30, true, false);
        assertEquals(0, food.tickTimer());
        assertNoEffectFor(food, 80, 14, 30, true, false);
        assertEquals(0, food.tickTimer());

        assertNoEffectFor(food, 79, 5.25F, 10, true, false);
        assertEquals(0.25F, food.tick(5.25F, 10, true, false).starveDamage());
    }

    @Test
    void fullHealthResetsAndKeepsTimerAtZero() {
        CivilianFood food = state(20, 6);
        assertNoEffectFor(food, 9, 20, 30, true, false);
        assertNoEffectFor(food, 200, 30, 30, true, false);
        assertEquals(0, food.tickTimer());
        assertNoEffectFor(food, 9, 20, 30, true, false);
        assertEquals(1.0F, food.tick(20, 30, true, false).healAmount());
    }

    @Test
    void deadBodiesDoNotRegenerateLikePlayerIsHurt() {
        CivilianFood food = state(20, 6);
        assertNoEffectFor(food, 200, 0, 30, true, false);
        assertEquals(0, food.tickTimer());
    }

    @Test
    void intakeRollsOverAtExactlyOneActiveDayAndClearsYesterdayOnTheNext() {
        CivilianFood food = new CivilianFood();
        food.eat(5, 6.0F);
        food.eat(5, 6.0F);
        for (int tick = 0; tick < 23_999; tick++) {
            food.countActiveTick();
        }
        assertEquals(23_999L, food.activeTicks());
        assertEquals(10, food.foodEatenToday());
        assertEquals(0, food.foodEatenYesterday());
        food.countActiveTick();
        assertEquals(24_000L, food.activeTicks());
        assertEquals(0, food.foodEatenToday());
        assertEquals(10, food.foodEatenYesterday());
        for (int tick = 0; tick < 23_999; tick++) {
            food.countActiveTick();
        }
        assertEquals(10, food.foodEatenYesterday());
        food.countActiveTick();
        assertEquals(48_000L, food.activeTicks());
        assertEquals(0, food.foodEatenToday());
        assertEquals(0, food.foodEatenYesterday());
    }

    @Test
    void savedFormRoundTripsEveryFieldAndResumesBothTimers() {
        CivilianFood.Saved saved = new CivilianFood.Saved(18, 0.0F, 2.25F, 79, 10, 5, 23_999L);
        CivilianFood restored = CivilianFood.restore(saved);
        assertEquals(saved, restored.save());
        assertEquals(1.0F, restored.tick(20, 30, true, false).healAmount());
        assertEquals(8.25F, restored.exhaustion());
        restored.countActiveTick();
        assertEquals(10, restored.foodEatenYesterday());
        assertEquals(0, restored.foodEatenToday());
        assertEquals(24_000L, restored.activeTicks());
    }

    @Test
    void copyPreservesAllFieldsAndMutatesIndependently() {
        CivilianFood food = CivilianFood.restore(new CivilianFood.Saved(19, 2.5F, 3.25F, 7, 10, 5, 35_000L));
        CivilianFood.Saved before = food.save();
        CivilianFood copy = food.copy();
        assertNotSame(food, copy);
        assertEquals(before, copy.save());
        copy.eat(5, 6.0F);
        copy.addExhaustion(1.0F);
        copy.tick(20, 30, true, false);
        copy.countActiveTick();
        assertEquals(before, food.save());
    }

    private static CivilianFood state(int foodLevel, float saturation) {
        return CivilianFood.restore(new CivilianFood.Saved(foodLevel, saturation, 0.0F, 0, 0, 0, 0L));
    }

    private static void assertNoEffectFor(CivilianFood food, int ticks, float health, float maxHealth,
                                         boolean naturalRegeneration, boolean peaceful) {
        for (int tick = 0; tick < ticks; tick++) {
            assertEquals(new CivilianFood.TickResult(0.0F, 0.0F),
                    food.tick(health, maxHealth, naturalRegeneration, peaceful), "tick " + (tick + 1));
        }
    }
}
