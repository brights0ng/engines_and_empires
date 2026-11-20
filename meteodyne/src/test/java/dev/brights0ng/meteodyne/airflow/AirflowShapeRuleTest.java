// Written by Claude (GreedyFluids M7b).
package dev.brights0ng.meteodyne.airflow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

/** GreedyFluids M7b b2: the partial-block rule on vanilla-like shapes, in 1/16 block units. */
class AirflowShapeRuleTest {

    private static double[] box(double x0, double y0, double z0, double x1, double y1, double z1) {
        return new double[] {x0 / 16, y0 / 16, z0 / 16, x1 / 16, y1 / 16, z1 / 16};
    }

    @Test
    void halfOrMoreOfTheCellIsSolid() {
        assertTrue(AirflowShapeRule.solid(List.of(box(0, 0, 0, 16, 8, 16))), "bottom slab");
        assertTrue(AirflowShapeRule.solid(List.of(box(0, 0, 0, 16, 15, 16))), "farmland");
        assertTrue(AirflowShapeRule.solid(List.of(box(1, 0, 1, 15, 14, 15))), "chest");
        assertTrue(AirflowShapeRule.solid(List.of(box(0, 0, 0, 16, 8, 16), box(0, 8, 8, 16, 16, 16))), "stairs");
        assertEquals(0.5, AirflowShapeRule.volume(List.of(box(0, 0, 0, 16, 8, 16))), 1e-12);
    }

    @Test
    void lowOrSmallShapesAreAir() {
        assertFalse(AirflowShapeRule.solid(List.of(box(0, 0, 0, 16, 1, 16))), "carpet");
        assertFalse(AirflowShapeRule.solid(List.of(box(0, 0, 0, 16, 6, 16))), "snow, 4 layers");
        assertFalse(AirflowShapeRule.solid(List.of(box(0, 0, 0, 16, 7, 16))), "campfire");
        assertFalse(AirflowShapeRule.solid(List.of(box(7, 0, 7, 9, 16, 9))), "a lone pane's post");
    }

    @Test
    void aShapeAcrossTheWholeCellBlocksFlow() {
        assertTrue(AirflowShapeRule.solid(List.of(box(7, 0, 0, 9, 16, 16))), "pane connected north-south blocks x");
        assertTrue(AirflowShapeRule.solid(List.of(box(0, 0, 7, 16, 16, 9))), "pane connected east-west blocks z");
        assertFalse(AirflowShapeRule.solid(List.of(box(7, 0, 0, 9, 16, 9))), "half a pane leaves a gap");
    }

    @Test
    void shapesAreClippedToTheCell() {
        // A fence-height box reaching 1.5 blocks up counts only its part inside the cell.
        assertEquals(AirflowShapeRule.volume(List.of(box(6, 0, 6, 10, 16, 10))),
                AirflowShapeRule.volume(List.of(box(6, 0, 6, 10, 24, 10))), 1e-12);
        assertEquals(0, AirflowShapeRule.volume(List.of()), 0);
    }
}
