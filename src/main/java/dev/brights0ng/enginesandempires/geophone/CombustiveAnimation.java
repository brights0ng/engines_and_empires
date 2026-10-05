package dev.brights0ng.enginesandempires.geophone;

/**
 * The timing of the combustive thumper's firing sequence, and the ram's height through it. Pure maths, shared by the
 * server (which plays the sounds and makes the vibration at the right ticks) and the client (which draws the ram), so the
 * two can never disagree about when the ram lands.
 *
 * <p>Heights are a fraction of the ram's travel: 0 resting on the anvil, 1 fully raised. Times are ticks since the redstone
 * pulse released the latch.
 *
 * <p>An ignited shot: the latch releases and fuel is injected with a hiss while the ram hangs at the top, quivering, for
 * {@link #IGNITE_TICK} ticks; the charge then ignites and drives the ram down in two ticks, hard; it lands at
 * {@link #IGNITED_IMPACT_TICK}, bounces once, and settles. A dud: the latch releases and the ram simply falls under its own
 * weight, a little slower, lands at {@link #DUD_IMPACT_TICK}, and barely bounces.
 */
public final class CombustiveAnimation {

    /** Ticks of injection hiss before an ignited charge goes off. */
    public static final int IGNITE_TICK = 4;
    public static final int IGNITED_IMPACT_TICK = 6;
    public static final int IGNITED_END_TICK = 12;

    public static final int DUD_IMPACT_TICK = 5;
    public static final int DUD_END_TICK = 10;

    private static final float IGNITED_BOUNCE = 0.10F;
    private static final int IGNITED_BOUNCE_TICKS = 4;
    private static final float DUD_BOUNCE = 0.04F;
    private static final int DUD_BOUNCE_TICKS = 3;
    private static final float QUIVER = 0.015F;

    public static int impactTick(boolean ignited) {
        return ignited ? IGNITED_IMPACT_TICK : DUD_IMPACT_TICK;
    }

    public static int endTick(boolean ignited) {
        return ignited ? IGNITED_END_TICK : DUD_END_TICK;
    }

    /** Whether the sequence is still playing {@code t} ticks after release. */
    public static boolean playing(float t, boolean ignited) {
        return t >= 0 && t < endTick(ignited);
    }

    /** The ram's height {@code t} ticks after release. */
    public static float ramHeight(float t, boolean ignited) {
        if (t <= 0) {
            return 1.0F;
        }
        if (ignited) {
            if (t < IGNITE_TICK) {
                return 1.0F + QUIVER * (float) Math.sin(t * Math.PI * 1.5);
            }
            if (t < IGNITED_IMPACT_TICK) {
                float f = (t - IGNITE_TICK) / (IGNITED_IMPACT_TICK - IGNITE_TICK);
                return 1.0F - f * f;
            }
            return bounce(t - IGNITED_IMPACT_TICK, IGNITED_BOUNCE, IGNITED_BOUNCE_TICKS);
        }
        if (t < DUD_IMPACT_TICK) {
            float f = t / DUD_IMPACT_TICK;
            return 1.0F - f * f;
        }
        return bounce(t - DUD_IMPACT_TICK, DUD_BOUNCE, DUD_BOUNCE_TICKS);
    }

    private static float bounce(float sinceImpact, float height, int ticks) {
        if (sinceImpact >= ticks) {
            return 0.0F;
        }
        return height * (float) Math.sin(Math.PI * sinceImpact / ticks);
    }

    private CombustiveAnimation() {
    }
}
