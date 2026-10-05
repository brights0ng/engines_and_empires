package dev.brights0ng.enginesandempires.frontier.spawn;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import dev.brights0ng.enginesandempires.frontier.Tier;

class SpawnRulesTest {

    private static final double HALF = 0.5;

    private static boolean denies(Tier tier, boolean monster, boolean creeper, boolean daytime, boolean sky, double roll) {
        return SpawnRules.denies(tier, monster, creeper, daytime, sky, roll, HALF);
    }

    @Test
    void civilizedLandHasNoHostilesAtAll() {
        assertTrue(denies(Tier.CIVILIZED, true, false, false, true, 0.0));
        assertTrue(denies(Tier.CIVILIZED, true, false, false, false, 0.0), "not underground either");
        assertTrue(denies(Tier.CIVILIZED, false, false, true, true, 0.0), "no polar bears");
        assertFalse(denies(Tier.CIVILIZED, false, false, false, true, 0.9), "animals are welcome");
    }

    @Test
    void settledLandHasNoCreepersOrDaytimeHunters() {
        assertTrue(denies(Tier.SETTLED, true, true, false, false, 0.0), "no creepers, even underground");
        assertTrue(denies(Tier.SETTLED, false, false, true, true, 0.0), "no polar bears");
    }

    @Test
    void settledLandHalvesSurfaceMonsters() {
        assertFalse(denies(Tier.SETTLED, true, false, false, true, 0.49));
        assertTrue(denies(Tier.SETTLED, true, false, false, true, 0.5));
        assertFalse(denies(Tier.SETTLED, true, false, false, false, 0.99), "only the surface is halved");
    }

    @Test
    void wildLandIsVanilla() {
        for (Tier tier : new Tier[] {Tier.UNINHABITED, Tier.FRONTIER}) {
            assertFalse(denies(tier, true, true, true, true, 0.99), tier.name());
        }
    }
}
