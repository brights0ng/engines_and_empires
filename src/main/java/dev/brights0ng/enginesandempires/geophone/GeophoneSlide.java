package dev.brights0ng.enginesandempires.geophone;

/**
 * The short animation of a geophone being pushed into the ground.
 *
 * <p>It starts held out from the face, with the tip of its spike just touching, and slides in along its own axis until
 * the crossguard meets the face and stops it. The slide speeds up as it goes and ends all at once, like something being
 * driven home, rather than easing to a stop. It is only how the geophone is drawn: its hitbox never moves.
 *
 * <p>The animation is a pure function of how long ago the geophone was placed, so a geophone that loads long after it was
 * placed simply comes out fully in, with no animation to replay, and nothing has to be remembered about it.
 *
 * <p>Nothing here touches Minecraft.
 */
public final class GeophoneSlide {

    /** How long the slide takes, in ticks. */
    public static final int TICKS = 6;

    /** How far through the slide it is, from 0 (just placed) to 1 (fully in). Ages before placement count as 0. */
    public static double progress(double ageTicks) {
        return Math.max(0.0, Math.min(1.0, ageTicks / TICKS));
    }

    /**
     * How far the geophone is drawn out from where it ends up, along its axis, in blocks. At the start this is the whole
     * spike, so the tip just touches the face; at the end it is 0.
     */
    public static double offset(double ageTicks) {
        double p = progress(ageTicks);
        return GeophoneOrientation.SPIKE_LENGTH * (1.0 - p * p);
    }

    /** Whether the slide is over. */
    public static boolean isDone(double ageTicks) {
        return ageTicks >= TICKS;
    }

    private GeophoneSlide() {
    }
}
