package dev.brights0ng.enginesandempires.frontier.incursion;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

import dev.brights0ng.enginesandempires.frontier.incursion.IncursionOdds.Kind;

class IncursionOddsTest {

    @Test
    void theChanceRisesInAStraightLineAndStopsAtTheFullScore() {
        assertEquals(0.02, IncursionOdds.chance(0, 25, 0.02, 0.06), 1e-9);
        assertEquals(0.04, IncursionOdds.chance(12.5, 25, 0.02, 0.06), 1e-9);
        assertEquals(0.06, IncursionOdds.chance(25, 25, 0.02, 0.06), 1e-9);
        assertEquals(0.10, IncursionOdds.chance(400, 25, 0.03, 0.10), 1e-9, "no higher than the maximum");
    }

    @Test
    void shotsWeighTheirRangeOver128() {
        assertEquals(4.0, IncursionOdds.shotWeight(512), 1e-9);
        assertEquals(6.0, IncursionOdds.shotWeight(768), 1e-9);
        assertEquals(8.0, IncursionOdds.shotWeight(1024), 1e-9);
    }

    @Test
    void settledLandOnlyGetsLesserOnes() {
        assertEquals(Kind.LESSER, IncursionOdds.roll(0.06, false, 0.2, 0.0));
        assertEquals(Kind.NONE, IncursionOdds.roll(0.06, false, 0.2, 0.06));
    }

    @Test
    void aFifthOfCivilizedIncursionsAreGreater() {
        assertEquals(Kind.GREATER, IncursionOdds.roll(0.10, true, 0.2, 0.019));
        assertEquals(Kind.LESSER, IncursionOdds.roll(0.10, true, 0.2, 0.021));
        assertEquals(Kind.LESSER, IncursionOdds.roll(0.10, true, 0.2, 0.099));
        assertEquals(Kind.NONE, IncursionOdds.roll(0.10, true, 0.2, 0.1));
    }
}
