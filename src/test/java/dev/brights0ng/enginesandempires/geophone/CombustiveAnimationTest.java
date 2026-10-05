package dev.brights0ng.enginesandempires.geophone;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class CombustiveAnimationTest {

    @Test
    void bothKindsStartFullyRaisedAndEndAtRest() {
        for (boolean ignited : new boolean[]{true, false}) {
            assertEquals(1.0F, CombustiveAnimation.ramHeight(0, ignited), 1e-6);
            assertEquals(0.0F, CombustiveAnimation.ramHeight(CombustiveAnimation.endTick(ignited), ignited), 1e-6);
        }
    }

    /** The ram is on the anvil at the very tick the server makes the vibration. */
    @Test
    void theRamLandsExactlyAtTheImpactTick() {
        for (boolean ignited : new boolean[]{true, false}) {
            int impact = CombustiveAnimation.impactTick(ignited);
            assertEquals(0.0F, CombustiveAnimation.ramHeight(impact, ignited), 1e-6);
            assertTrue(CombustiveAnimation.ramHeight(impact - 0.5F, ignited) > 0, "still falling just before impact");
        }
    }

    /** Ignited: the ram hangs near the top during injection, then drops. */
    @Test
    void anIgnitedRamHangsUntilIgnition() {
        for (float t = 0.25F; t < CombustiveAnimation.IGNITE_TICK; t += 0.25F) {
            assertTrue(CombustiveAnimation.ramHeight(t, true) > 0.97F, "at " + t);
        }
    }

    @Test
    void theRamOnlyEverFallsBeforeImpact() {
        for (boolean ignited : new boolean[]{true, false}) {
            int from = ignited ? CombustiveAnimation.IGNITE_TICK : 0;
            float previous = CombustiveAnimation.ramHeight(from, ignited);
            for (float t = from + 0.1F; t <= CombustiveAnimation.impactTick(ignited); t += 0.1F) {
                float now = CombustiveAnimation.ramHeight(t, ignited);
                assertTrue(now <= previous + 1e-6, "rose at " + t);
                previous = now;
            }
        }
    }

    /** A real shot bounces visibly higher than a dud. */
    @Test
    void anIgnitedShotBouncesHigherThanADud() {
        float ignitedPeak = 0;
        float dudPeak = 0;
        for (float t = 0; t < 12; t += 0.1F) {
            if (t > CombustiveAnimation.IGNITED_IMPACT_TICK) {
                ignitedPeak = Math.max(ignitedPeak, CombustiveAnimation.ramHeight(t, true));
            }
            if (t > CombustiveAnimation.DUD_IMPACT_TICK) {
                dudPeak = Math.max(dudPeak, CombustiveAnimation.ramHeight(t, false));
            }
        }
        assertTrue(ignitedPeak > dudPeak * 2);
        assertTrue(dudPeak > 0);
    }

    @Test
    void playingCoversTheWholeSequence() {
        assertTrue(CombustiveAnimation.playing(0, true));
        assertTrue(CombustiveAnimation.playing(CombustiveAnimation.IGNITED_END_TICK - 0.01F, true));
        assertFalse(CombustiveAnimation.playing(CombustiveAnimation.IGNITED_END_TICK, true));
        assertFalse(CombustiveAnimation.playing(-1, false));
    }
}
