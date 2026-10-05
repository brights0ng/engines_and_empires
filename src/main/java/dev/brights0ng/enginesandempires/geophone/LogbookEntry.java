package dev.brights0ng.enginesandempires.geophone;

/**
 * One saved reading in a {@link Logbook}.
 *
 * @param number what makes its default name: entry 5 is called "Reading 5". Numbers are handed out in order and never reused,
 *               so an entry can always be found by its number, even after others are deleted
 * @param name   a name the player gave it, or empty if they have not, in which case it is called by its number
 */
public record LogbookEntry(int number, ReaderReading reading, String name) {

    /** An entry with no name of its own. */
    public LogbookEntry(int number, ReaderReading reading) {
        this(number, reading, "");
    }

    /** Whether the player has given it a name. */
    public boolean hasName() {
        return !name.isEmpty();
    }

    /** The same entry with another name. Pass an empty name to go back to the default. */
    public LogbookEntry withName(String name) {
        return new LogbookEntry(number, reading, name);
    }

    /** The same entry holding another reading, keeping its number and its name. */
    public LogbookEntry withReading(ReaderReading reading) {
        return new LogbookEntry(number, reading, name);
    }
}
