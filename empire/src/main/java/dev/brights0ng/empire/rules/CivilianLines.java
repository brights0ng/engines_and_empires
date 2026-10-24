// Written by Claude.
package dev.brights0ng.empire.rules;

import java.util.Locale;
import java.util.random.RandomGenerator;

/** The lang keys of what civilians say: single lines by category, and the scripted exchanges two civilians talk through. */
public final class CivilianLines {

    public enum Category {
        IDLE(8), GREETING(5), RAIN(4), NIGHT(4), REPLY(8);

        private final int count;

        Category(int count) {
            this.count = count;
        }

        public int count() {
            return count;
        }

        public String id() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    /** Lines in each exchange; exchange e has lines {@code talkKey(e, 0)} to {@code talkKey(e, TALK_LENGTHS[e] - 1)}. */
    public static final int[] TALK_LENGTHS = {3, 3, 2, 3, 2};

    public static String key(Category category, int index) {
        return "civilian.empire.line." + category.id() + "." + index;
    }

    /** A line index in {@code category}; never {@code last} when the category has more than one line. */
    public static int pick(Category category, RandomGenerator random, int last) {
        int count = category.count();
        if (count <= 1) {
            return 0;
        }
        if (last < 0 || last >= count) {
            return random.nextInt(count);
        }
        int i = random.nextInt(count - 1);
        return i >= last ? i + 1 : i;
    }

    public static String talkKey(int exchange, int line) {
        return "civilian.empire.talk." + exchange + "." + line;
    }

    private CivilianLines() {
    }
}
