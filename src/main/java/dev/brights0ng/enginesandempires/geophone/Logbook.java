package dev.brights0ng.enginesandempires.geophone;

import java.util.ArrayList;
import java.util.List;

/**
 * The readings saved in a logbook. It holds at most {@link #MAX_ENTRIES}, in the order they were saved. Each is called by a name
 * the player gave it, or "Reading 5" by default. The numbers are never reused, so an entry is never confused with a deleted one.
 *
 * <p>It is immutable: saving, renaming and deleting give back a new logbook, which is what gets stored on the item.
 *
 * <p>The rules for saving a reading:
 * <ul>
 *   <li>If the logbook already has a reading of that deposit, the two are compared by when they were taken. A newer one updates
 *       the entry, keeping its place, number and name. One taken at the same time is already there. An older one is refused,
 *       so a stale reading can never replace a newer one. A reading whose time is unknown counts as older than any known.
 *       None of these ever fill a row, so they work when the logbook is full.</li>
 *   <li>Otherwise it is added at the end, unless the logbook is full, in which case it is refused. Nothing is ever overwritten
 *       to make room: the player deletes something first.</li>
 * </ul>
 *
 * <p>Nothing here touches Minecraft.
 *
 * <p>The same rules keep the smart logger's readings, just with room for many more: see {@link #capacity}.
 *
 * @param nextNumber the number the next new entry will be given
 * @param capacity   how many readings it holds: {@link #MAX_ENTRIES} for a logbook item
 */
public record Logbook(List<LogbookEntry> entries, int nextNumber, int capacity) {

    /** How many readings a logbook holds. */
    public static final int MAX_ENTRIES = 12;

    /** The longest name an entry can be given, in characters. */
    public static final int MAX_NAME_LENGTH = 24;

    /** A logbook with nothing in it. */
    public static final Logbook EMPTY = new Logbook(List.of(), 1);

    /** What happened when a reading was saved. */
    public enum Outcome {
        /** It was added as a new entry. */
        ADDED,
        /** The logbook had this deposit, and that entry now has the newer reading. */
        REPLACED,
        /** The logbook already has exactly this reading of the deposit, so nothing changed. */
        ALREADY,
        /** The logbook already has a newer reading of the deposit, so this one was refused. */
        OLDER,
        /** The logbook is full and this is not a deposit it has, so nothing was saved. */
        FULL
    }

    /**
     * The result of saving a reading.
     *
     * @param logbook the logbook afterwards (unchanged unless the outcome is ADDED or REPLACED)
     * @param entry   the entry that was added or updated, or for ALREADY and OLDER the entry that already has the deposit;
     *                null only if it was refused for being full
     */
    public record Saved(Logbook logbook, Outcome outcome, LogbookEntry entry) {
    }

    /** The entries are kept as given, and cannot be changed afterwards. One with more than {@link #capacity} is cut short. */
    public Logbook {
        capacity = Math.max(1, capacity);
        entries = List.copyOf(entries.size() > capacity ? entries.subList(0, capacity) : entries);
        nextNumber = Math.max(1, nextNumber);
    }

    /** A logbook item's readings: room for {@link #MAX_ENTRIES}. */
    public Logbook(List<LogbookEntry> entries, int nextNumber) {
        this(entries, nextNumber, MAX_ENTRIES);
    }

    /** An empty one with room for this many readings. */
    public static Logbook empty(int capacity) {
        return new Logbook(List.of(), 1, capacity);
    }

    public int size() {
        return entries.size();
    }

    public boolean isFull() {
        return entries.size() >= capacity;
    }

    /** The entry at a position, counting from 0. */
    public LogbookEntry get(int index) {
        return entries.get(index);
    }

    /** Whether there is an entry at this position. */
    public boolean has(int index) {
        return index >= 0 && index < entries.size();
    }

    /** The position of the entry with this number, or -1. */
    public int indexOfNumber(int number) {
        for (int i = 0; i < entries.size(); i++) {
            if (entries.get(i).number() == number) {
                return i;
            }
        }
        return -1;
    }

    /** Whether saving this reading would be refused for want of room: the logbook is full, and it is not of a deposit already in it. */
    public boolean wouldRefuse(ReaderReading reading) {
        return isFull() && indexOfDeposit(reading) < 0;
    }

    /** Saves a reading, or refuses to. */
    public Saved save(ReaderReading reading) {
        int existing = indexOfDeposit(reading);
        if (existing >= 0) {
            LogbookEntry old = entries.get(existing);
            if (reading.takenAt() < old.reading().takenAt()) {
                return new Saved(this, Outcome.OLDER, old);
            }
            if (reading.takenAt() == old.reading().takenAt()) {
                return new Saved(this, Outcome.ALREADY, old);
            }
            LogbookEntry updated = old.withReading(reading);
            List<LogbookEntry> changed = new ArrayList<>(entries);
            changed.set(existing, updated);
            return new Saved(new Logbook(changed, nextNumber, capacity), Outcome.REPLACED, updated);
        }
        if (isFull()) {
            return new Saved(this, Outcome.FULL, null);
        }
        LogbookEntry added = new LogbookEntry(nextNumber, reading);
        List<LogbookEntry> grown = new ArrayList<>(entries);
        grown.add(added);
        return new Saved(new Logbook(grown, nextNumber + 1, capacity), Outcome.ADDED, added);
    }

    /**
     * The logbook with an entry given a new name. An empty name puts it back to being called by its number.
     *
     * @param number the entry's number, not its position, so it is the same entry even if others have been deleted since
     * @throws IllegalArgumentException if there is no entry with that number
     */
    public Logbook rename(int number, String name) {
        int index = indexOfNumber(number);
        if (index < 0) {
            throw new IllegalArgumentException("No entry number " + number);
        }
        List<LogbookEntry> changed = new ArrayList<>(entries);
        changed.set(index, entries.get(index).withName(cleanName(name)));
        return new Logbook(changed, nextNumber, capacity);
    }

    /**
     * Makes a name fit to keep: control characters removed, spaces trimmed off both ends, and cut to
     * {@link #MAX_NAME_LENGTH} characters. Anything that leaves nothing is an empty name, which means "no name".
     */
    public static String cleanName(String raw) {
        if (raw == null) {
            return "";
        }
        StringBuilder kept = new StringBuilder();
        raw.codePoints().filter(c -> !Character.isISOControl(c)).forEach(kept::appendCodePoint);
        String trimmed = kept.toString().strip();
        int length = trimmed.codePointCount(0, trimmed.length());
        if (length > MAX_NAME_LENGTH) {
            trimmed = trimmed.substring(0, trimmed.offsetByCodePoints(0, MAX_NAME_LENGTH)).strip();
        }
        return trimmed;
    }

    /**
     * The logbook without the entry at a position. The entries after it move up one place.
     *
     * @throws IndexOutOfBoundsException if there is no entry there
     */
    public Logbook delete(int index) {
        if (!has(index)) {
            throw new IndexOutOfBoundsException("No entry " + index + " in a logbook of " + entries.size());
        }
        List<LogbookEntry> remaining = new ArrayList<>(entries);
        remaining.remove(index);
        return new Logbook(remaining, nextNumber, capacity);
    }

    private int indexOfDeposit(ReaderReading reading) {
        for (int i = 0; i < entries.size(); i++) {
            if (entries.get(i).reading().sameDepositAs(reading)) {
                return i;
            }
        }
        return -1;
    }
}
