// Written by Gemini.
package dev.brights0ng.empire.rules;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.List;

import org.junit.jupiter.api.Test;

import dev.brights0ng.empire.rules.TaskUtility.Candidate;

class TaskUtilityTest {

    @Test
    void gatesZeroOutIneligibleTasksAndMomentumStabilizesActiveTask() {
        assertEquals(0.0, TaskUtility.score(false, 100.0, 1.0, true, 15.0), 1e-6);
        assertEquals(50.0, TaskUtility.score(true, 50.0, 1.0, false, 15.0), 1e-6);
        assertEquals(65.0, TaskUtility.score(true, 50.0, 1.0, true, 15.0), 1e-6);

        // Active task keeps winning by momentum until rival urgency clearly overtakes it
        String activeWins = TaskUtility.pickBest(List.of(
                new Candidate<>("harvest", true, 50.0, 1.0, true),   // 65
                new Candidate<>("deposit", true, 60.0, 1.0, false)   // 60
        ));
        assertEquals("harvest", activeWins);

        String rivalOvertakes = TaskUtility.pickBest(List.of(
                new Candidate<>("harvest", true, 50.0, 1.0, true),   // 65
                new Candidate<>("deposit", true, 80.0, 1.0, false)   // 80
        ));
        assertEquals("deposit", rivalOvertakes);

        assertNull(TaskUtility.pickBest(List.of(
                new Candidate<>("blocked", false, 100.0, 1.0, true)
        )));
    }
}
