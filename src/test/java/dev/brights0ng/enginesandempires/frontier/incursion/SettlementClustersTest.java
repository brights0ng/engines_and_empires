package dev.brights0ng.enginesandempires.frontier.incursion;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

import dev.brights0ng.enginesandempires.frontier.tier.SectionKey;

class SettlementClustersTest {

    @Test
    void touchingSectionsAreOneSettlementAndApartOnesAreTwo() {
        List<Set<Long>> clusters = SettlementClusters.cluster(List.of(
                SectionKey.of(0, 4, 0), SectionKey.of(1, 4, 0), SectionKey.of(2, 5, 1), // a diagonal still touches
                SectionKey.of(10, 4, 10), SectionKey.of(10, 4, 11)));
        assertEquals(2, clusters.size());
        assertEquals(3, clusters.get(0).size(), "biggest first");
        assertEquals(2, clusters.get(1).size());
    }

    @Test
    void aGapOfOneSectionSplitsThem() {
        assertEquals(2, SettlementClusters.cluster(List.of(SectionKey.of(0, 4, 0), SectionKey.of(2, 4, 0))).size());
    }

    @Test
    void theEdgeIsEveryColumnWithANeighbourOutside() {
        Set<Long> columns = new java.util.HashSet<>();
        for (int x = 0; x < 3; x++) {
            for (int z = 0; z < 3; z++) {
                columns.add(SettlementClusters.column(x, z));
            }
        }
        List<Long> edge = SettlementClusters.edge(columns);
        assertEquals(8, edge.size(), "a 3x3 has one middle column");
        assertFalse(edge.contains(SettlementClusters.column(1, 1)));
        assertTrue(edge.contains(SettlementClusters.column(0, 0)));
    }

    @Test
    void columnsPackBothWaysWithNegatives() {
        long c = SettlementClusters.column(-5, 7);
        assertEquals(-5, SettlementClusters.columnX(c));
        assertEquals(7, SettlementClusters.columnZ(c));
        long d = SettlementClusters.column(3, -9);
        assertEquals(3, SettlementClusters.columnX(d));
        assertEquals(-9, SettlementClusters.columnZ(d));
    }
}
