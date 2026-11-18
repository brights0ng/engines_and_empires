// Written by Claude (GreedyFluids M7e: the safety limits on opening tiles); edited by Claude (M8b b4: secondaries;
// M8b5b: merged regions).
package dev.brights0ng.meteodyne.airflow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;

class AirflowLimitsTest {

    private static final UUID A = new UUID(0, 1), B = new UUID(0, 2);

    /** Samples a player every 10 ticks moving {@code perScan} blocks along x; returns the last speed. */
    private static double walk(AirflowLimits.Speeds s, UUID id, long from, int scans, double perScan) {
        double v = 0;
        for (int i = 0; i <= scans; i++) {
            v = s.record(id, from + 10L * i, perScan * i, 64, 0);
        }
        return v;
    }

    @Test
    void speedIsTheMeanOverTheLastTwoSeconds() {
        AirflowLimits.Speeds s = new AirflowLimits.Speeds(AirflowLimits.WINDOW_TICKS);
        assertEquals(0, s.record(A, 0, 0, 64, 0), 1e-12, "no earlier sample: still");
        // walking, 4.317 blocks a second: 2.16 blocks per 10 ticks
        assertEquals(4.317, walk(s, A, 0, 8, 4.317 / 2), 1e-9);
        // running, 5.612
        assertEquals(5.612, walk(new AirflowLimits.Speeds(40), B, 0, 8, 5.612 / 2), 1e-9);
        assertTrue(AirflowLimits.mayOpen(4.317, 5.5));
        assertFalse(AirflowLimits.mayOpen(5.612, 5.5));
        assertTrue(AirflowLimits.mayOpen(80, 0), "0 is no limit");
    }

    @Test
    void pacingCountsItsPathAndATeleportLeavesTheWindow() {
        AirflowLimits.Speeds s = new AirflowLimits.Speeds(40);
        // back and forth 12 blocks every half second: 24 blocks a second, though it goes nowhere
        double v = 0;
        for (int i = 0; i <= 8; i++) {
            v = s.record(A, 10L * i, i % 2 == 0 ? 0 : 12, 64, 0);
        }
        assertEquals(24, v, 1e-9);
        // standing, then a teleport 1000 blocks away: fast until the jump leaves the window, 40 ticks on
        AirflowLimits.Speeds t = new AirflowLimits.Speeds(40);
        for (int i = 0; i <= 4; i++) {
            t.record(B, 10L * i, 0, 64, 0);
        }
        assertTrue(t.record(B, 50, 1000, 64, 0) > 100);
        assertTrue(t.record(B, 60, 1000, 64, 0) > 100);
        t.record(B, 70, 1000, 64, 0);
        assertTrue(t.record(B, 80, 1000, 64, 0) > 100, "the jump's start is still in the window");
        assertEquals(0, t.record(B, 90, 1000, 64, 0), 1e-12);
    }

    @Test
    void playersWhoLeaveAreForgotten() {
        AirflowLimits.Speeds s = new AirflowLimits.Speeds(40);
        s.record(A, 0, 0, 64, 0);
        s.record(B, 0, 0, 64, 0);
        s.keep(Set.of(B));
        assertEquals(1, s.tracked());
        assertEquals(0, s.record(A, 10, 500, 64, 0), 1e-12, "back with no history: still");
    }

    private static AirflowLimits.Tile<String> tile(String name, UUID owner, long last, boolean occupied) {
        return new AirflowLimits.Tile<>(name, owner, last, occupied);
    }

    @Test
    void theServerCapClosesTheLongestWaitingTilesAndNeverAnOccupiedOne() {
        List<AirflowLimits.Tile<String>> tiles = List.of(
                tile("home", A, 500, true),
                tile("left 1 s ago", A, 480, false),
                tile("left 20 s ago", A, 100, false),
                tile("their home", B, 500, true),
                tile("left 10 s ago", B, 300, false));
        assertEquals(List.of(), AirflowLimits.overCap(tiles, AirflowLimits.CapMode.SERVER, 5));
        assertEquals(List.of("left 20 s ago", "left 10 s ago"),
                AirflowLimits.overCap(tiles, AirflowLimits.CapMode.SERVER, 3));
        // more players in tiles than the cap: only the waiting ones close
        assertEquals(List.of("left 20 s ago", "left 10 s ago", "left 1 s ago"),
                AirflowLimits.overCap(tiles, AirflowLimits.CapMode.SERVER, 1));
    }

    @Test
    void thePerPlayerCapCountsEachPlayersOwnTiles() {
        List<AirflowLimits.Tile<String>> tiles = List.of(
                tile("A home", A, 500, true),
                tile("A 1", A, 480, false),
                tile("A 2", A, 400, false),
                tile("A 3", A, 100, false),
                tile("B home", B, 500, true),
                tile("B 1", B, 50, false));
        List<String> closed = AirflowLimits.overCap(tiles, AirflowLimits.CapMode.PER_PLAYER, 2);
        assertEquals(Set.of("A 3", "A 2"), Set.copyOf(closed), "B's two are within their own cap");
        assertEquals(2, closed.size());
    }

    private static AirflowLimits.Tile<String> secondary(String name, UUID owner, long last) {
        return new AirflowLimits.Tile<>(name, owner, last, false, true);
    }

    @Test
    void theCapClosesTilesInGraceBeforeSecondariesAndNeverAPlayersTile() {
        List<AirflowLimits.Tile<String>> tiles = List.of(
                tile("home", A, 500, true),
                secondary("east of home", A, 500),
                secondary("south of home", A, 500),
                tile("left 1 s ago", A, 480, false),
                tile("left 20 s ago", A, 100, false));
        assertEquals(List.of("left 20 s ago", "left 1 s ago"),
                AirflowLimits.overCap(tiles, AirflowLimits.CapMode.SERVER, 3), "grace first, whatever its age");
        assertEquals(List.of("left 20 s ago", "left 1 s ago", "east of home"),
                AirflowLimits.overCap(tiles, AirflowLimits.CapMode.SERVER, 2));
        assertEquals(4, AirflowLimits.overCap(tiles, AirflowLimits.CapMode.SERVER, 0).size(), "the player's own stays");
    }

    @Test
    void aSecondaryOpensOnlyUnderTheCapOfKeptTiles() {
        List<AirflowLimits.Tile<String>> tiles = List.of(
                tile("A home", A, 500, true),
                secondary("A east", A, 500),
                tile("A left", A, 100, false),
                tile("B home", B, 500, true));
        // kept: A home, A east, B home; the tile in grace doesn't count (the cap closes it)
        assertTrue(AirflowLimits.roomFor(tiles, AirflowLimits.CapMode.SERVER, 4, A));
        assertFalse(AirflowLimits.roomFor(tiles, AirflowLimits.CapMode.SERVER, 3, A));
        // per player: A keeps two, B one
        assertFalse(AirflowLimits.roomFor(tiles, AirflowLimits.CapMode.PER_PLAYER, 2, A));
        assertTrue(AirflowLimits.roomFor(tiles, AirflowLimits.CapMode.PER_PLAYER, 2, B));
    }

    /** M8b5b: a merged region counts as its tiles, and the cap closes it whole. */
    @Test
    void aMergedRegionCountsAsItsTilesAndClosesWhole() {
        List<AirflowLimits.Tile<String>> tiles = List.of(
                new AirflowLimits.Tile<>("A home, merged with 2 more", A, 500, true, false, 3),
                new AirflowLimits.Tile<>("left, merged pair", A, 100, false, false, 2),
                tile("left 1 s ago", A, 480, false));
        assertTrue(AirflowLimits.roomFor(tiles, AirflowLimits.CapMode.SERVER, 4, A), "kept: 3 tiles");
        assertFalse(AirflowLimits.roomFor(tiles, AirflowLimits.CapMode.SERVER, 3, A));
        assertEquals(List.of("left, merged pair"), AirflowLimits.overCap(tiles, AirflowLimits.CapMode.SERVER, 4),
                "6 tiles over a cap of 4: the pair waited longest, and closing it is enough");
        assertEquals(List.of("left, merged pair", "left 1 s ago"),
                AirflowLimits.overCap(tiles, AirflowLimits.CapMode.SERVER, 3));
    }
}
