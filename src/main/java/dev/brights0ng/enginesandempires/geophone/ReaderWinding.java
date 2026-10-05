package dev.brights0ng.enginesandempires.geophone;

/**
 * The pure maths of winding a wind-up reader by hand: how fast a full wind builds up, and how much stamina it costs.
 *
 * <p>Winding takes {@link #WIND_TICKS} ticks (ten seconds) of continuously holding the crank. Over that time it costs the
 * winder {@link #FOOD_POINTS_SPENT} points of food (two and a half of the ten icons on the hunger bar), added the same way
 * sprinting or jumping cost hunger: as exhaustion, which the game itself takes out of saturation first and only touches the
 * food bar once saturation runs dry (see {@code Player.causeFoodExhaustion} and {@code FoodData.tick}). A reader counts as
 * fully wound once its charge reaches {@link #FULLY_WOUND}, which is set a hair under 1.0 so that repeated additions of
 * {@link #CHARGE_PER_TICK} are guaranteed to reach it despite floating-point rounding.
 *
 * <p>Nothing here touches Minecraft.
 */
public final class ReaderWinding {

    /** How long a full wind takes, holding the crank continuously: ten seconds at twenty ticks a second. */
    public static final int WIND_TICKS = 200;

    /** How much the charge rises for each tick the crank is held. */
    public static final float CHARGE_PER_TICK = 1.0F / WIND_TICKS;

    /** How many of the ten hunger icons a full wind costs: two and a half, the same as five points on the 0-20 hunger scale. */
    public static final float FOOD_POINTS_SPENT = 5.0F;

    /** How much exhaustion the game needs before it takes one point off the hunger bar. */
    private static final float EXHAUSTION_PER_FOOD_POINT = 4.0F;

    /** Total exhaustion added over a full wind, spread evenly across its ticks. */
    public static final float TOTAL_EXHAUSTION = FOOD_POINTS_SPENT * EXHAUSTION_PER_FOOD_POINT;

    /** How much exhaustion one tick of winding adds. */
    public static final float EXHAUSTION_PER_TICK = TOTAL_EXHAUSTION / WIND_TICKS;

    /**
     * A charge counts as fully wound once it is at least this close to 1.0: slightly under 1.0 rather than exactly 1.0, so that
     * {@link #WIND_TICKS} additions of {@link #CHARGE_PER_TICK} (which need not sum to exactly 1.0, being floats) are
     * guaranteed to cross it.
     */
    public static final float FULLY_WOUND = 1.0F - 1.0e-4F;

    /** The charge after one more tick of winding, capped at a full wind. */
    public static float advance(float charge) {
        return Math.min(1.0F, charge + CHARGE_PER_TICK);
    }

    /** Whether a charge is enough to capture a reading. */
    public static boolean isFullyWound(float charge) {
        return charge >= FULLY_WOUND;
    }

    private ReaderWinding() {
    }
}
