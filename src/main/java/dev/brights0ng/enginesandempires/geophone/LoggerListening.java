package dev.brights0ng.enginesandempires.geophone;

/**
 * How the smart logger listens. It works like a wind-up reader standing where it stands, listening through the geophones
 * around it (see {@link ReaderRecorder}), with three differences:
 * <ul>
 *   <li>It keeps every deposit the vibration let it hear, not only the nearest.</li>
 *   <li>It only listens while it is turning. The faster it turns, the further out it listens: from
 *       {@link #MIN_RADIUS} (a reader's own reach) at the slowest, up to {@link #MAX_RADIUS} at {@link #FULL_SPEED} or
 *       more.</li>
 *   <li>It holds up to {@link #CAPACITY} readings, kept by the same rules as a logbook's: see {@link Logbook}.</li>
 * </ul>
 *
 * <p>Nothing here touches Minecraft.
 */
public final class LoggerListening {

    /** How far out it listens at the slowest speed: as far as a wind-up reader does. */
    public static final double MIN_RADIUS = ReaderRecorder.LISTEN_RADIUS;

    /** How far out it listens at {@link #FULL_SPEED} or faster. */
    public static final double MAX_RADIUS = 48.0;

    /** The speed, in RPM, at which it listens as far as it can. Create's fastest ordinary speed. */
    public static final float FULL_SPEED = 256.0F;

    /** How many readings it holds. */
    public static final int CAPACITY = 256;

    /**
     * How far from the logger a geophone can be for it to listen through it, at this speed. Zero when it is not turning:
     * then it hears nothing at all. Either direction of turning counts the same.
     */
    public static double radius(float rpm) {
        float speed = Math.abs(rpm);
        if (!(speed > 0.0F)) {
            return 0.0;
        }
        return MIN_RADIUS + (MAX_RADIUS - MIN_RADIUS) * Math.min(speed, FULL_SPEED) / FULL_SPEED;
    }

    private LoggerListening() {
    }
}
