// Written by Claude.
package dev.brights0ng.empire.rules;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.Set;

import org.junit.jupiter.api.Test;

class DivineAreaTest {

    @Test
    void theCoreAndItsEightNeighboursAreDivine() {
        long core = ChunkKeys.pack(5, -7);
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                assertTrue(DivineArea.divine(core, ChunkKeys.pack(5 + dx, -7 + dz)), dx + "," + dz);
            }
        }
    }

    @Test
    void chunksTwoAwayOnEitherAxisAreNot() {
        long core = ChunkKeys.pack(5, -7);
        assertFalse(DivineArea.divine(core, ChunkKeys.pack(7, -7)));
        assertFalse(DivineArea.divine(core, ChunkKeys.pack(5, -9)));
        assertFalse(DivineArea.divine(core, ChunkKeys.pack(3, -5)));
        assertFalse(DivineArea.divine(core, ChunkKeys.pack(6, -5)));
    }

    @Test
    void itWorksAcrossTheOriginAndAtTheIntLimits() {
        assertTrue(DivineArea.divine(ChunkKeys.pack(0, 0), ChunkKeys.pack(-1, -1)));
        assertTrue(DivineArea.divine(ChunkKeys.pack(-1, 0), ChunkKeys.pack(0, 1)));
        assertFalse(DivineArea.divine(ChunkKeys.pack(Integer.MAX_VALUE, 0), ChunkKeys.pack(Integer.MIN_VALUE, 0)));
    }

    @Test
    void chunksListsTheNineDivineChunksCoreFirst() {
        long core = ChunkKeys.pack(-3, 12);
        long[] chunks = DivineArea.chunks(core);
        assertEquals(9, chunks.length);
        assertEquals(core, chunks[0]);
        Set<Long> distinct = new HashSet<>();
        for (long chunk : chunks) {
            assertTrue(DivineArea.divine(core, chunk));
            distinct.add(chunk);
        }
        assertEquals(9, distinct.size());
    }
}
