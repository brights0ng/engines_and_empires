package dev.brights0ng.enginesandempires.weather.cloud;

import java.util.SplittableRandom;
import java.util.UUID;

/**
 * How long clouds live, and where in its life a cloud is. Shared (not client-only): the server gives each heap cloud
 * its lifespan from {@link #span}, layer clouds a birth and an end that the spawner moves while their conditions hold,
 * and the server, the renderer and the localized weather all turn a cloud's age and lifetime into its {@link Phase},
 * so every player and the server agree.
 *
 * <h2>Lifespans</h2>
 * Real lifespans at x0.2 (Bright, 2026-10-04: the same factor as {@link CloudScale}'s heights), per type
 * ({@link CloudType#life}), in real minutes. Within a type's range, a cloud's lifespan comes from its id, skewed toward
 * the short end ({@code u^2}): most clouds are short-lived and long-lived ones rare, as in the real world.
 *
 * <h2>Phases</h2>
 * A span is a birth (the cloud forms), a mature stretch, a death (it erodes away), and for storms with an anvil a
 * linger afterwards, in which only the anvil is left, thinning slowly (an orphan anvil). Measured from a cloud's actual
 * age and lifetime: birth from the start, death and linger back from the end, so a lifetime the server extends (a
 * cloud growing into a bigger type, a layer cloud whose conditions hold) keeps the phases right.
 */
public final class CloudLife {

    /** Ticks per real minute. */
    public static final int MINUTE = 1200;

    /**
     * One lifespan for a type, in ticks: {@code birth} and {@code death} lie inside {@code storm}; {@code linger}
     * follows it.
     */
    public record Span(int birth, int storm, int death, int linger) {

        /** The whole lifetime: the cloud, then its lingering anvil. */
        public int total() {
            return storm + linger;
        }
    }

    /** Cloud {@code id}'s lifespan as a cloud of type {@code type}. */
    public static Span span(CloudType type, UUID id) {
        CloudType.Life t = type.life;
        SplittableRandom rng = id == null ? new SplittableRandom(7) : StormVariety.random(id, 0x11FE ^ type.id.hashCode());
        double u = rng.nextDouble();
        double storm = (t.stormMin() + (t.stormMax() - t.stormMin()) * u * u) * MINUTE;
        double v = rng.nextDouble();
        double linger = (t.lingerMin() + (t.lingerMax() - t.lingerMin()) * v * v) * MINUTE;
        double birth = storm * t.birth();
        double death = storm * t.death();
        if (type.layer()) {
            // Layer clouds form and dissolve at their own pace, whatever their (condition-driven) length.
            birth = type.formMinutes() * MINUTE;
            death = type.fadeMinutes() * MINUTE;
            storm = Math.max(storm, birth + death);
        }
        return new Span((int) Math.round(birth), (int) Math.round(storm), (int) Math.round(death),
                (int) Math.round(linger));
    }

    /** {@link #span(CloudType, UUID)} by type id (unknown ids get mid-level cumulus lifespans). */
    public static Span span(String typeId, UUID id) {
        CloudType t = CloudType.of(typeId);
        return span(t == null ? CloudType.CUMULUS_MEDIOCRIS : t, id);
    }

    /**
     * Where a cloud is in its life, each 0-1.
     *
     * @param growth     how far it has formed: 0 nothing yet, 1 fully formed
     * @param decay      how far its body has eroded away: 0 not at all, 1 gone
     * @param anvilDecay how far its anvil has thinned away (equals {@code decay} for clouds without a linger)
     */
    public record Phase(double growth, double decay, double anvilDecay) {

        public static final Phase MATURE = new Phase(1, 0, 0);

        /** The body: formed and not yet eroded. */
        public double body() {
            return growth * (1 - decay);
        }

        /** Whether anything is left to draw. */
        public boolean visible() {
            return growth * (1 - anvilDecay) > 0.005;
        }

        /**
         * How strongly it can rain, 0-1 (Bright, 2026-10-04: dying storms taper off and lingering anvils stay dry).
         * Rain starts once the cloud is well formed and tapers to nothing over its death.
         */
        public double precipitation() {
            double formed = clamp01((growth - 0.6) / 0.4);
            return formed * (1 - decay);
        }

        /** A short name for debug output. */
        public String name() {
            if (growth < 1) {
                return "forming";
            }
            if (decay <= 0) {
                return "mature";
            }
            if (decay < 1) {
                return "dying";
            }
            return anvilDecay < 1 ? "anvil lingering" : "gone";
        }
    }

    /** The phase of a cloud with span {@code s} at age {@code age} of lifetime {@code lifetime} (ticks). */
    public static Phase phase(Span s, double age, double lifetime) {
        if (lifetime <= 0) {
            return Phase.MATURE;
        }
        double growth = smooth(age / Math.max(1, s.birth()));
        double stormEnd = lifetime - s.linger();
        double deathStart = stormEnd - s.death();
        double decay = clamp01((age - deathStart) / Math.max(1, s.death()));
        double anvilDecay = s.linger() > 0 ? clamp01((age - stormEnd) / s.linger()) : decay;
        return new Phase(growth, decay, anvilDecay);
    }

    /**
     * The lifetime for a cloud that has just become type {@code s} (a cumulus growing into a storm): at least its
     * current one, at least the new type's whole span, and enough from its age for half the new type's mature stretch,
     * its death and its linger.
     */
    public static long evolvedLifetime(Span s, long age, long current) {
        long mature = Math.max(0, s.storm() - s.birth() - s.death());
        return Math.max(current, Math.max(s.total(), age + mature / 2 + s.death() + s.linger()));
    }

    static double clamp01(double v) {
        return v < 0 ? 0 : Math.min(1, v);
    }

    static double smooth(double t) {
        t = clamp01(t);
        return t * t * (3 - 2 * t);
    }

    private CloudLife() {
    }
}
