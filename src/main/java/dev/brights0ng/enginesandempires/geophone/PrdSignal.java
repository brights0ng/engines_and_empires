package dev.brights0ng.enginesandempires.geophone;

/**
 * A flash of the portable record display's three indicator lights, kept on the display itself so the player sees it
 * wherever it is: in hand, or sitting in a smart logger's dock. Only one flash plays at a time; a new one replaces the
 * last. When it has finished playing, the lights are dark again (except that the green one is lit, steady, while the
 * display's map is open, which the client decides by itself).
 *
 * <p>Nothing here touches Minecraft.
 *
 * @param pattern what is flashing
 * @param start   the game time it started
 */
public record PrdSignal(Pattern pattern, long start) {

    /** Which light, if any, is lit. */
    public enum Light {
        NONE, GREEN, YELLOW, RED
    }

    /** How long each blink is on, and then off, in ticks. */
    public static final int BLINK_TICKS = 4;
    /** How many blinks a red or yellow warning gives. */
    public static final int BLINKS = 3;
    /** How long the long green pulse lasts, in ticks. */
    public static final int LONG_PULSE_TICKS = 24;
    /** How long the brief green flash lasts, in ticks. */
    public static final int BRIEF_FLASH_TICKS = 6;

    /** The flashes the display can give. */
    public enum Pattern {
        /** No room for that reading: red, three blinks. */
        RED_BLINKS,
        /** It already holds that reading, or a newer one: yellow, three blinks. */
        YELLOW_BLINKS,
        /** A reading went onto it: one long green pulse. */
        GREEN_LONG,
        /** Its readings were loaded into a smart logger: a brief green flash. */
        GREEN_BRIEF;

        /** How long it plays for, in ticks. */
        public int duration() {
            return switch (this) {
                case RED_BLINKS, YELLOW_BLINKS -> BLINKS * 2 * BLINK_TICKS;
                case GREEN_LONG -> LONG_PULSE_TICKS;
                case GREEN_BRIEF -> BRIEF_FLASH_TICKS;
            };
        }
    }

    /** The light the flash has lit at game time {@code now}: none before it starts, and none once it has finished. */
    public Light lit(long now) {
        long age = now - start;
        if (age < 0 || age >= pattern.duration()) {
            return Light.NONE;
        }
        return switch (pattern) {
            case RED_BLINKS -> (age / BLINK_TICKS) % 2 == 0 ? Light.RED : Light.NONE;
            case YELLOW_BLINKS -> (age / BLINK_TICKS) % 2 == 0 ? Light.YELLOW : Light.NONE;
            case GREEN_LONG, GREEN_BRIEF -> Light.GREEN;
        };
    }

    /** What a flash on a display saying how a reading was saved onto it looks like. */
    public static Pattern forOutcome(Logbook.Outcome outcome) {
        return switch (outcome) {
            case ADDED, REPLACED -> Pattern.GREEN_LONG;
            case ALREADY, OLDER -> Pattern.YELLOW_BLINKS;
            case FULL -> Pattern.RED_BLINKS;
        };
    }
}
