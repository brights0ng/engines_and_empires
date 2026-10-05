package dev.brights0ng.enginesandempires.geophone;

import java.util.Locale;

/**
 * The maths of the wind-up reader held as a compass: which way the needle points, and whether the arrow beside it says the
 * reading is above, level with, or below you.
 *
 * <p>The needle is a fraction of a turn, clockwise from straight up: 0 when what it points at is dead ahead, a quarter when it is
 * to your right, a half when it is behind you. The item is drawn from {@link #FRAMES} pictures of the needle, each covering the
 * fraction of a turn nearest to it, and the game picks between them by comparing this fraction against thresholds, which are
 * also worked out here so that the item model and the maths cannot disagree about where one picture ends and the next begins.
 *
 * <p>Nothing here touches Minecraft.
 */
public final class ReaderCompass {

    /** How many pictures of the needle the item has. */
    public static final int FRAMES = 32;

    /** How many blocks above or below you a reading can be and still be called level with you. */
    public static final double LEVEL_TOLERANCE = 3.0;

    /**
     * What the height arrow says. The {@link #value} of each is what the item model is told, and is the number the model
     * compares against to choose the picture.
     */
    public enum HeightBand {
        /** The reader does not know the height (it did not hear the deposit through enough geophones). No arrow. */
        NONE(0.0F),
        UP(0.25F),
        LEVEL(0.5F),
        DOWN(0.75F);

        private final float value;

        HeightBand(float value) {
            this.value = value;
        }

        public float value() {
            return value;
        }
    }

    /**
     * Which way the needle points, as a fraction of a turn clockwise from straight up, from 0 up to but not including 1.
     *
     * @param yawDegrees the direction the holder faces, as the game measures it: 0 is south, 90 is west, -90 is east
     */
    public static double needleFraction(double yawDegrees, double fromX, double fromZ, double toX, double toZ) {
        // The bearing of the target, clockwise from east as seen from above (z grows to the south), and the bearing of the
        // holder's own facing, which is 90 degrees more than their yaw.
        double target = Math.toDegrees(Math.atan2(toZ - fromZ, toX - fromX));
        double facing = yawDegrees + 90.0;
        double fraction = (target - facing) / 360.0;
        return fraction - Math.floor(fraction);
    }

    /**
     * The name of the item model that draws the needle at one position with one height arrow: {@code windup_reader_<frame>}, with
     * {@code _up}, {@code _level} or {@code _down} added if there is an arrow. The data generator writes the models under these
     * names, and the tests look for them under the same ones.
     */
    public static String pictureName(int frame, HeightBand band) {
        return "windup_reader_" + frame + (band == HeightBand.NONE ? "" : "_" + band.name().toLowerCase(Locale.ROOT));
    }

    /** The picture of the needle that covers this fraction of a turn: the nearest of {@link #FRAMES} evenly spaced ones. */
    public static int frameFor(double fraction) {
        return (int) Math.floor(fraction * FRAMES + 0.5) % FRAMES;
    }

    /**
     * The fraction of a turn at which the picture for this frame takes over from the one before it. The first frame takes over
     * from the start, and the last picture of the turn hands back to the first at {@link #wrapThreshold()}.
     */
    public static double frameThreshold(int frame) {
        return frame == 0 ? 0.0 : (frame - 0.5) / FRAMES;
    }

    /** The fraction of a turn at which the needle has come round far enough to be nearer the first picture again. */
    public static double wrapThreshold() {
        return (FRAMES - 0.5) / FRAMES;
    }

    /**
     * What the height arrow says for a reading this many blocks above (positive) or below (negative) the holder.
     *
     * @param known whether the reader knows the height at all
     */
    public static HeightBand heightBand(double blocksAbove, boolean known) {
        if (!known) {
            return HeightBand.NONE;
        }
        if (blocksAbove > LEVEL_TOLERANCE) {
            return HeightBand.UP;
        }
        if (blocksAbove < -LEVEL_TOLERANCE) {
            return HeightBand.DOWN;
        }
        return HeightBand.LEVEL;
    }

    private ReaderCompass() {
    }
}
