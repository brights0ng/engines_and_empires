// Written by Claude.
package dev.brights0ng.empire.rules;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

import dev.brights0ng.empire.rules.LoadPlan.Mode;

class LoadPlanTest {

    private static final long NOW = 10_000_000L;
    private static final long LIMIT = LoadPlan.INACTIVE_TICKS;

    private static long c(int x, int z) {
        return ChunkKeys.pack(x, z);
    }

    /** The 3x3 around (0, 0) plus (2, 0): an empire with one outer chunk. */
    private static Set<Long> staked() {
        Set<Long> staked = new HashSet<>();
        for (int x = -1; x <= 1; x++) {
            for (int z = -1; z <= 1; z++) {
                staked.add(c(x, z));
            }
        }
        staked.add(c(2, 0));
        return staked;
    }

    @Test
    void fiveDaysOfServerTime() {
        assertEquals(8_640_000L, LoadPlan.INACTIVE_TICKS);
    }

    @Test
    void offAndFrozenNeverLoad() {
        assertFalse(LoadPlan.mayLoad(Mode.OFF, true, true, NOW, NOW, LIMIT));
        assertFalse(LoadPlan.mayLoad(Mode.ALWAYS, false, false, NOW, NOW, LIMIT), "a frozen empire loads nothing");
        assertFalse(LoadPlan.mayLoad(Mode.MEMBER_ONLINE, false, false, NOW, NOW, LIMIT));
    }

    @Test
    void memberOnlineNeedsAMemberOnline() {
        assertTrue(LoadPlan.mayLoad(Mode.MEMBER_ONLINE, true, true, NOW, NOW, LIMIT));
        assertFalse(LoadPlan.mayLoad(Mode.MEMBER_ONLINE, true, false, NOW, NOW, LIMIT));
    }

    @Test
    void alwaysLoadsForFiveServerDaysAfterTheLastMember() {
        assertTrue(LoadPlan.mayLoad(Mode.ALWAYS, true, false, NOW, NOW - LIMIT, LIMIT), "exactly at the limit");
        assertFalse(LoadPlan.mayLoad(Mode.ALWAYS, true, false, NOW, NOW - LIMIT - 1, LIMIT), "past it");
        assertTrue(LoadPlan.mayLoad(Mode.ALWAYS, true, true, NOW, 0, LIMIT), "a member back online loads again");
    }

    @Test
    void wantedIsTheStakedPartOfEachThreeByThree() {
        // A civilian in (1, 0): its 3x3 spans x 0..2, of which (2, -1) and (2, 1) are not staked.
        Set<Long> wanted = LoadPlan.wanted(List.of(c(1, 0)), staked());
        assertEquals(Set.of(c(0, -1), c(0, 0), c(0, 1), c(1, -1), c(1, 0), c(1, 1), c(2, 0)), wanted);
        assertTrue(LoadPlan.wanted(List.of(c(50, 50)), staked()).isEmpty(), "a civilian outside loads nothing");
        assertEquals(9, LoadPlan.wanted(List.of(c(0, 0), c(0, 0)), staked()).size(), "overlaps count once");
    }

    @Test
    void ticketsAreAddedAndKeptForTheDelay() {
        LoadPlan.Tickets tickets = new LoadPlan.Tickets();
        Set<Long> first = Set.of(c(0, 0), c(1, 0));
        LoadPlan.Changes changes = tickets.update(first, staked(), true, 0, LoadPlan.KEEP_TICKS);
        assertEquals(first, changes.add());
        assertTrue(changes.remove().isEmpty());

        // (1, 0) stops being wanted at 100: kept until 100 + KEEP_TICKS.
        assertTrue(tickets.update(Set.of(c(0, 0)), staked(), true, 100, LoadPlan.KEEP_TICKS).isEmpty());
        assertTrue(tickets.update(Set.of(c(0, 0)), staked(), true, 100 + LoadPlan.KEEP_TICKS - 1, LoadPlan.KEEP_TICKS).isEmpty());
        changes = tickets.update(Set.of(c(0, 0)), staked(), true, 100 + LoadPlan.KEEP_TICKS, LoadPlan.KEEP_TICKS);
        assertEquals(Set.of(c(1, 0)), changes.remove());
        assertEquals(Set.of(c(0, 0)), tickets.held());
    }

    @Test
    void wantedAgainBeforeTheDelayKeepsTheTicket() {
        LoadPlan.Tickets tickets = new LoadPlan.Tickets();
        tickets.update(Set.of(c(1, 0)), staked(), true, 0, LoadPlan.KEEP_TICKS);
        tickets.update(Set.of(), staked(), true, 100, LoadPlan.KEEP_TICKS);
        assertTrue(tickets.update(Set.of(c(1, 0)), staked(), true, 500, LoadPlan.KEEP_TICKS).isEmpty(), "no reload");
        // The unwanted time starts again from the next time it stops being wanted.
        tickets.update(Set.of(), staked(), true, 700, LoadPlan.KEEP_TICKS);
        assertTrue(tickets.update(Set.of(), staked(), true, 700 + LoadPlan.KEEP_TICKS - 1, LoadPlan.KEEP_TICKS).isEmpty());
    }

    @Test
    void notMayLoadOrUnstakedDropsAtOnce() {
        LoadPlan.Tickets tickets = new LoadPlan.Tickets();
        tickets.update(Set.of(c(0, 0), c(2, 0)), staked(), true, 0, LoadPlan.KEEP_TICKS);

        Set<Long> withoutOuter = new HashSet<>(staked());
        withoutOuter.remove(c(2, 0));
        LoadPlan.Changes changes = tickets.update(Set.of(c(0, 0)), withoutOuter, true, 20, LoadPlan.KEEP_TICKS);
        assertEquals(Set.of(c(2, 0)), changes.remove(), "an unstaked chunk goes at once");

        changes = tickets.update(Set.of(c(0, 0)), staked(), false, 40, LoadPlan.KEEP_TICKS);
        assertEquals(Set.of(c(0, 0)), changes.remove(), "a closed gate drops everything");
        assertTrue(changes.add().isEmpty(), "and adds nothing");
        assertTrue(tickets.held().isEmpty());
    }

    @Test
    void clearReturnsEverything() {
        LoadPlan.Tickets tickets = new LoadPlan.Tickets();
        tickets.update(Set.of(c(0, 0), c(1, 0)), staked(), true, 0, LoadPlan.KEEP_TICKS);
        assertEquals(Set.of(c(0, 0), c(1, 0)), tickets.clear());
        assertTrue(tickets.held().isEmpty());
    }
}
