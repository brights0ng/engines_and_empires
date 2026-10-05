package dev.brights0ng.enginesandempires.geophone;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import dev.brights0ng.enginesandempires.geophone.Logbook.Outcome;
import dev.brights0ng.enginesandempires.geophone.Logbook.Saved;

class LogbookTest {

    private static final String OVERWORLD = "minecraft:overworld";

    /** A reading taken at game time 100. */
    private static ReaderReading reading(int x, long deposit) {
        return new ReaderReading(OVERWORLD, x, 10, 20, true, deposit, 100L);
    }

    private static ReaderReading reading(int x, long deposit, long takenAt) {
        return new ReaderReading(OVERWORLD, x, 10, 20, true, deposit, takenAt);
    }

    /** A logbook filled with n readings of different deposits, numbered 1 to n, each taken at game time 100. */
    private static Logbook filled(int n) {
        Logbook logbook = Logbook.EMPTY;
        for (int i = 1; i <= n; i++) {
            logbook = logbook.save(reading(i * 10, 1000 + i)).logbook();
        }
        return logbook;
    }

    @Test
    void aNewLogbookIsEmpty() {
        assertEquals(0, Logbook.EMPTY.size());
        assertFalse(Logbook.EMPTY.isFull());
        assertEquals(1, Logbook.EMPTY.nextNumber());
    }

    @Test
    void savedReadingsAreAddedInOrderAndNumbered() {
        Saved first = Logbook.EMPTY.save(reading(10, 1));
        assertEquals(Outcome.ADDED, first.outcome());
        assertEquals(1, first.entry().number());
        Saved second = first.logbook().save(reading(20, 2));
        assertEquals(Outcome.ADDED, second.outcome());
        assertEquals(2, second.entry().number());
        assertEquals(2, second.logbook().size());
        assertEquals(10, second.logbook().get(0).reading().x());
        assertEquals(20, second.logbook().get(1).reading().x());
    }

    @Test
    void itHoldsTwelveAndThenRefusesTheThirteenth() {
        assertEquals(12, Logbook.MAX_ENTRIES);
        Logbook full = filled(12);
        assertTrue(full.isFull());
        Saved refused = full.save(reading(999, 5000));
        assertEquals(Outcome.FULL, refused.outcome());
        assertNull(refused.entry());
        assertSame(full, refused.logbook(), "a refused save changes nothing");
        assertEquals(12, refused.logbook().size());
        assertEquals(10, refused.logbook().get(0).reading().x(), "and the oldest is not overwritten");
    }

    @Test
    void afterADeleteThereIsRoomAgain() {
        Logbook room = filled(12).delete(3);
        assertFalse(room.isFull());
        assertEquals(Outcome.ADDED, room.save(reading(999, 5000)).outcome());
    }

    // ---- the same deposit again ----

    @Test
    void aNewerReadingOfADepositAlreadyInTheLogbookUpdatesItsEntryInsteadOfAddingACopy() {
        Logbook logbook = filled(3);
        Saved saved = logbook.save(new ReaderReading(OVERWORLD, 555, -20, 66, false, 1002, 250L));
        assertEquals(Outcome.REPLACED, saved.outcome());
        assertEquals(3, saved.logbook().size(), "no copy");
        assertEquals(2, saved.entry().number(), "it keeps its number");
        assertEquals(555, saved.logbook().get(1).reading().x(), "in its place, with the new reading");
        assertFalse(saved.logbook().get(1).reading().hasHeight());
        assertEquals(10, saved.logbook().get(0).reading().x());
        assertEquals(30, saved.logbook().get(2).reading().x());
        assertEquals(logbook.nextNumber(), saved.logbook().nextNumber(), "a replacement uses up no number");
    }

    /** The case that was reported: a newer reading is saved, then an older one of the same deposit. */
    @Test
    void anOlderReadingOfADepositIsRefusedAndTheNewerOneIsKept() {
        Logbook logbook = Logbook.EMPTY.save(reading(500, 77, 900L)).logbook();
        Saved older = logbook.save(reading(300, 77, 400L));
        assertEquals(Outcome.OLDER, older.outcome());
        assertSame(logbook, older.logbook(), "nothing changes");
        assertEquals(1, older.logbook().size(), "and no new entry is written");
        assertEquals(500, older.logbook().get(0).reading().x(), "the newer reading is still the one held");
        assertEquals(1, older.entry().number(), "it says which entry already has this deposit");
    }

    @Test
    void theSameReadingSavedTwiceIsAlreadyThere() {
        Logbook logbook = Logbook.EMPTY.save(reading(500, 77, 900L)).logbook();
        Saved again = logbook.save(reading(500, 77, 900L));
        assertEquals(Outcome.ALREADY, again.outcome());
        assertSame(logbook, again.logbook());
        assertEquals(1, again.logbook().size());
    }

    /** Readings taken before times were kept have none. They count as older than any reading with a time. */
    @Test
    void aReadingWithNoTimeCountsAsOlderThanOneWithATime() {
        Logbook logbook = Logbook.EMPTY.save(reading(500, 77, 900L)).logbook();
        Saved unknown = logbook.save(new ReaderReading(OVERWORLD, 300, 10, 20, true, 77L, 0L));
        assertEquals(Outcome.OLDER, unknown.outcome());
        assertEquals(1, unknown.logbook().size());

        // And the other way round, a reading with a time replaces one that has none.
        Logbook old = Logbook.EMPTY.save(new ReaderReading(OVERWORLD, 300, 10, 20, true, 77L, 0L)).logbook();
        Saved newer = old.save(reading(500, 77, 900L));
        assertEquals(Outcome.REPLACED, newer.outcome());
        assertEquals(500, newer.logbook().get(0).reading().x());
    }

    @Test
    void twoReadingsWithNoTimeOfTheSameDepositCannotBeToldApartSoNoCopyIsMade() {
        ReaderReading first = new ReaderReading(OVERWORLD, 300, 10, 20, true, 77L, 0L);
        Logbook logbook = Logbook.EMPTY.save(first).logbook();
        Saved second = logbook.save(new ReaderReading(OVERWORLD, 310, 10, 20, true, 77L, 0L));
        assertEquals(Outcome.ALREADY, second.outcome());
        assertEquals(1, second.logbook().size());
    }

    @Test
    void aFullLogbookStillHandlesADepositItHasWhateverTheAge() {
        Logbook full = filled(12); // deposits 1001 to 1012, each taken at 100
        assertEquals(Outcome.REPLACED, full.save(reading(777, 1005, 200L)).outcome(), "newer: updates, needs no room");
        assertEquals(Outcome.OLDER, full.save(reading(777, 1005, 50L)).outcome(), "older: refused as older, not as full");
        assertEquals(Outcome.ALREADY, full.save(reading(777, 1005, 100L)).outcome());
        assertEquals(12, full.save(reading(777, 1005, 200L)).logbook().size());
        assertFalse(full.wouldRefuse(new ReaderReading(OVERWORLD, 1, 1, 1, true, 1005)));
        assertTrue(full.wouldRefuse(reading(1, 424242)));
        assertFalse(filled(11).wouldRefuse(reading(1, 424242)));
    }

    @Test
    void readingsThatDoNotSayWhichDepositTheyAreOfAreNeverTakenForCopies() {
        Logbook logbook = Logbook.EMPTY.save(reading(10, 0)).logbook().save(reading(10, 0)).logbook();
        assertEquals(2, logbook.size(), "a reading of an unknown deposit is always new");
    }

    @Test
    void theSameDepositNumberInAnotherDimensionIsAnotherDeposit() {
        Logbook logbook = Logbook.EMPTY.save(reading(10, 7)).logbook();
        Saved saved = logbook.save(new ReaderReading("minecraft:the_nether", 10, 10, 20, true, 7, 100L));
        assertEquals(Outcome.ADDED, saved.outcome());
        assertEquals(2, saved.logbook().size());
    }

    @Test
    void aReplacedEntryKeepsItsNameAndPlace() {
        Logbook named = filled(3).rename(2, "Iron by the river");
        Saved saved = named.save(reading(555, 1002, 500L));
        assertEquals(Outcome.REPLACED, saved.outcome());
        assertEquals("Iron by the river", saved.logbook().get(1).name(), "an update does not lose the name you gave it");
        assertEquals(555, saved.logbook().get(1).reading().x());
    }

    // ---- names ----

    @Test
    void anEntryStartsWithNoNameOfItsOwn() {
        assertFalse(Logbook.EMPTY.save(reading(10, 1)).entry().hasName());
        assertEquals("", Logbook.EMPTY.save(reading(10, 1)).entry().name());
    }

    @Test
    void anEntryCanBeGivenANameAndThenTakeItBack() {
        Logbook named = filled(3).rename(2, "Copper vein");
        assertEquals("Copper vein", named.get(1).name());
        assertTrue(named.get(1).hasName());
        assertFalse(named.get(0).hasName(), "the others are untouched");
        Logbook cleared = named.rename(2, "");
        assertFalse(cleared.get(1).hasName(), "an empty name goes back to the default");
    }

    @Test
    void renamingFindsTheEntryByItsNumberNotItsPlace() {
        Logbook logbook = filled(4).delete(0); // entries 2, 3 and 4 remain, in places 0, 1 and 2
        Logbook renamed = logbook.rename(4, "Deep gold");
        assertEquals("Deep gold", renamed.get(2).name());
        assertEquals(4, renamed.get(2).number());
        assertEquals(2, logbook.indexOfNumber(4));
        assertEquals(-1, logbook.indexOfNumber(1), "deleted, and its number never comes back");
    }

    @Test
    void renamingNeverChangesTheReadingOrTheNumber() {
        Logbook logbook = filled(2);
        Logbook renamed = logbook.rename(1, "Gold");
        assertEquals(logbook.get(0).reading(), renamed.get(0).reading());
        assertEquals(1, renamed.get(0).number());
        assertEquals(logbook.nextNumber(), renamed.nextNumber());
    }

    @Test
    void renamingAnEntryThatDoesNotExistIsAnError() {
        assertThrows(IllegalArgumentException.class, () -> filled(2).rename(9, "Nothing"));
        assertThrows(IllegalArgumentException.class, () -> Logbook.EMPTY.rename(1, "Nothing"));
    }

    @Test
    void namesAreTidiedBeforeTheyAreKept() {
        assertEquals("Iron", Logbook.cleanName("  Iron  "), "spaces at the ends go");
        assertEquals("Iron ore", Logbook.cleanName("Iron\u0007 ore"), "control characters go");
        assertEquals("Line oneline two", Logbook.cleanName("Line one\nline two"), "line breaks cannot get in: they are removed");
        assertEquals("", Logbook.cleanName("   "));
        assertEquals("", Logbook.cleanName("\t\n"));
        assertEquals("", Logbook.cleanName(null));
        assertEquals("Ünïcödé ✓", Logbook.cleanName("Ünïcödé ✓"), "ordinary characters, accented or not, stay");
    }

    @Test
    void aNameIsCutToTheMaximumLength() {
        String tooLong = "x".repeat(Logbook.MAX_NAME_LENGTH + 10);
        String cleaned = Logbook.cleanName(tooLong);
        assertEquals(Logbook.MAX_NAME_LENGTH, cleaned.length());
        assertEquals("x".repeat(Logbook.MAX_NAME_LENGTH), cleaned);
        assertEquals("y".repeat(Logbook.MAX_NAME_LENGTH), Logbook.cleanName("y".repeat(Logbook.MAX_NAME_LENGTH)), "exactly the limit is kept whole");
    }

    @Test
    void cuttingANameNeverSplitsACharacterMadeOfTwoParts() {
        // Each of these is one character, but two in Java's strings.
        String emoji = "\uD83D\uDC8E"; // a gem
        String name = emoji.repeat(Logbook.MAX_NAME_LENGTH + 5);
        String cleaned = Logbook.cleanName(name);
        assertEquals(Logbook.MAX_NAME_LENGTH, cleaned.codePointCount(0, cleaned.length()), "counted in characters, not in halves");
        assertEquals(emoji.repeat(Logbook.MAX_NAME_LENGTH), cleaned, "and none is cut in half");
    }

    @Test
    void renamingAppliesTheTidyingToo() {
        Logbook renamed = filled(1).rename(1, "  padded  ");
        assertEquals("padded", renamed.get(0).name());
        assertEquals("", filled(1).rename(1, "   ").get(0).name(), "a name of only spaces is no name");
    }

    // ---- deleting and the rest ----

    @Test
    void deletingRemovesTheEntryAndTheOnesAfterItMoveUp() {
        Logbook logbook = filled(4).delete(1);
        assertEquals(3, logbook.size());
        assertEquals(1, logbook.get(0).number());
        assertEquals(3, logbook.get(1).number());
        assertEquals(4, logbook.get(2).number());
    }

    @Test
    void aDeletedEntrysNumberIsNeverGivenAgain() {
        Logbook logbook = filled(3).delete(1);
        Saved saved = logbook.save(reading(500, 9000));
        assertEquals(4, saved.entry().number(), "not 2 again, and not 3, which is in use");
        Logbook emptied = filled(3).delete(0).delete(0).delete(0);
        assertEquals(0, emptied.size());
        assertEquals(4, emptied.save(reading(1, 1)).entry().number(), "even from empty, the count carries on");
    }

    @Test
    void deletingWhereThereIsNothingIsAnError() {
        assertThrows(IndexOutOfBoundsException.class, () -> filled(2).delete(2));
        assertThrows(IndexOutOfBoundsException.class, () -> filled(2).delete(-1));
        assertThrows(IndexOutOfBoundsException.class, () -> Logbook.EMPTY.delete(0));
        assertFalse(filled(2).has(2));
        assertTrue(filled(2).has(1));
        assertFalse(filled(2).has(-1));
    }

    @Test
    void aLogbookCannotBeChangedFromOutside() {
        Logbook logbook = filled(2);
        assertThrows(UnsupportedOperationException.class, () -> logbook.entries().add(new LogbookEntry(9, reading(1, 1))));
        List<LogbookEntry> source = new ArrayList<>(logbook.entries());
        Logbook copy = new Logbook(source, 3);
        source.clear();
        assertEquals(2, copy.size(), "it keeps its own copy of what it was given");
    }

    @Test
    void oneWithTooManyEntriesIsCutShortRatherThanRefusedOutright() {
        List<LogbookEntry> many = new ArrayList<>();
        for (int i = 1; i <= 20; i++) {
            many.add(new LogbookEntry(i, reading(i, i)));
        }
        Logbook logbook = new Logbook(many, 21);
        assertEquals(12, logbook.size());
        assertEquals(12, logbook.get(11).number());
    }

    @Test
    void aNonsenseNextNumberIsRepaired() {
        assertEquals(1, new Logbook(List.of(), 0).nextNumber());
        assertEquals(1, new Logbook(List.of(), -5).nextNumber());
    }

    @Test
    void savingNeverChangesTheLogbookItWasCalledOn() {
        Logbook original = filled(3);
        original.save(reading(1, 424242));
        original.save(reading(5, 1001, 999L));
        original.delete(0);
        original.rename(1, "changed?");
        assertEquals(3, original.size());
        assertEquals(1, original.get(0).number());
        assertEquals(10, original.get(0).reading().x());
        assertFalse(original.get(0).hasName());
    }
}
