// Written by Claude.
package dev.brights0ng.empire.rules;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.SplittableRandom;

import org.junit.jupiter.api.Test;

class CivilianNamesTest {

    @Test
    void fortyUniqueNonBlankNames() {
        assertEquals(40, CivilianNames.NAMES.size());
        assertEquals(40, new HashSet<>(CivilianNames.NAMES).size());
        CivilianNames.NAMES.forEach(name -> assertFalse(name.isBlank()));
    }

    @Test
    void pickStaysInTheList() {
        SplittableRandom random = new SplittableRandom(3);
        for (int i = 0; i < 1_000; i++) {
            assertTrue(CivilianNames.NAMES.contains(CivilianNames.pick(random)));
        }
    }
}
