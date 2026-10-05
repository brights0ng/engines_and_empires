package dev.brights0ng.enginesandempires.geophone;

/**
 * The pure maths of winding the mechanical thumper up from Create's rotational power.
 *
 * <p>This is the same shape as {@link ReaderWinding} — a charge from 0 (unwound) to 1 (fully wound, ready to release) that
 * rises a little each tick — except that here the effort behind each tick's worth of winding is not "was the button held"
 * but "how fast was the shaft turning," so a faster network winds it faster rather than every tick counting the same. Below
 * {@link #MIN_RPM} it does not wind at all: a shaft barely turning should not slowly creep the thumper up over an hour.
 *
 * <p>A full wind, held right at {@link #REFERENCE_RPM}, takes {@link #REFERENCE_WIND_TICKS} ticks — the same ten seconds the
 * wind-up reader takes by hand, so the two feel like the same kind of wait. Both the reference speed and duration are
 * placeholders, meant to be tuned once this is turning in a real factory.
 *
 * <p>Nothing here touches Minecraft.
 */
public final class ThumperWinding {

    /** Below this many RPM (in either direction), the shaft is turning too slowly to wind it at all. */
    public static final double MIN_RPM = 8.0;

    /** At this many RPM, a full wind takes exactly {@link #REFERENCE_WIND_TICKS}. */
    public static final double REFERENCE_RPM = 64.0;

    /** How long a full wind takes at {@link #REFERENCE_RPM}. */
    public static final int REFERENCE_WIND_TICKS = 200;

    /** A charge counts as fully wound once it is at least this close to 1.0; see {@link ReaderWinding#FULLY_WOUND} for why. */
    public static final float FULLY_WOUND = 1.0F - 1.0e-4F;

    /** How much charge one tick at this RPM (positive or negative; only the rate matters, not the direction) adds. */
    public static double chargePerTick(double rpm) {
        return chargePerTick(rpm, REFERENCE_WIND_TICKS);
    }

    /**
     * The same, for a thumper whose full wind at {@link #REFERENCE_RPM} takes {@code referenceTicks} instead of
     * {@link #REFERENCE_WIND_TICKS}: the combustive thumper only lifts its head, and lifts it faster.
     */
    public static double chargePerTick(double rpm, int referenceTicks) {
        double magnitude = Math.abs(rpm);
        if (magnitude < MIN_RPM) {
            return 0.0;
        }
        return magnitude / (REFERENCE_RPM * referenceTicks);
    }

    /** The charge after one more tick at this RPM, capped at a full wind. */
    public static float advance(float charge, double rpm) {
        return advance(charge, rpm, REFERENCE_WIND_TICKS);
    }

    /** The charge after one more tick at this RPM, for a thumper with its own full-wind time; see {@link #chargePerTick(double, int)}. */
    public static float advance(float charge, double rpm, int referenceTicks) {
        return (float) Math.min(1.0, charge + chargePerTick(rpm, referenceTicks));
    }

    /** Whether a charge is enough to arm the thumper. */
    public static boolean isFullyWound(float charge) {
        return charge >= FULLY_WOUND;
    }

    private ThumperWinding() {
    }
}
