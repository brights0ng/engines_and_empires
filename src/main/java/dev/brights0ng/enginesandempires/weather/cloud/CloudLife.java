package dev.brights0ng.enginesandempires.weather.cloud;

import java.util.SplittableRandom;
import java.util.UUID;

/**
 * How long clouds live, and where in its life a cloud is. Shared (not client-only): the server gives each cloud its
 * lifetime from {@link #span} (the weather simulation, from phase 4 of {@code claude/weather-backbone-plan.md}), and
 * the renderer and the localized weather turn a cluster's age and lifetime into its {@link Phase}, so every player and
 * the server agree.
 *
 * <h2>Lifespans</h2>
 * Real lifespans at x0.2 (Bright's decision, 2026-10-04: the same factor as {@link CloudScale}'s heights), per PA cloud
 * type, in real minutes. Within a type's range, a formation's lifespan comes from its region id, skewed toward the
 * short end ({@code u^2}): most clouds are short-lived and long-lived ones rare, as in the real world. Every cluster of
 * a formation gets the same rolls, so a formation dissolves as one.
 *
 * <h2>Phases</h2>
 * A span is a birth (the cloud forms), a mature stretch, a death (it erodes away), and for storms with an anvil a
 * linger afterwards, in which only the anvil is left, thinning slowly (an orphan anvil). Measured from a cluster's
 * actual age and lifetime: birth from the start, death and linger back from the end, so a lifetime PA stretches (merges
 * keep the longer one) or the server extends (a cloud growing into a bigger type) keeps the phases right.
 *
 * <p>PA's own growth and decay (30 s ramps) are ignored: these replace them.
 */
public final class CloudLife {

    /** Ticks per real minute. */
    public static final int MINUTE = 1200;

    /**
     * A type's real lifespans at x0.2, in minutes. {@code stormMin..stormMax} is the cloud itself, birth to the end of
     * its death; {@code birth} and {@code death} are fractions of it; {@code lingerMin..lingerMax} the anvil left
     * afterwards (0 for none).
     */
    record Type(double stormMin, double stormMax, double birth, double death, double lingerMin, double lingerMax) {
    }

    private static final Type DEFAULT = new Type(4, 12, 0.25, 0.25, 0, 0);

    static Type type(String typeId) {
        if (typeId == null) {
            return DEFAULT;
        }
        String id = typeId.contains(":") ? typeId.substring(typeId.indexOf(':') + 1) : typeId;
        return switch (id) {
            // Real: a few minutes to a quarter of an hour; wisps that come and go.
            case "vapor_cluster" -> new Type(1, 3, 0.25, 0.35, 0, 0);
            // Real fair-weather cumulus: 10-40 min, forming in 5-10 and fraying away in 5-10.
            case "cumulus_humilis" -> new Type(2, 8, 0.22, 0.28, 0, 0);
            case "cumulus_mediocris" -> new Type(4, 9, 0.25, 0.25, 0, 0);
            // Real towering cumulus: 30-60 min.
            case "cumulus_congestus" -> new Type(6, 12, 0.3, 0.25, 0, 0);
            // Real single-cell storms: 30-60 min. A bald (calvus) top leaves little behind; a fibrous (capillatus)
            // anvil outlives its storm for an hour or more.
            case "cumulonimbus_calvus" -> new Type(6, 12, 0.3, 0.35, 4, 12);
            case "cumulonimbus_capillatus" -> new Type(9, 18, 0.3, 0.35, 12, 36);
            // Real supercells: 1-4 hours, the anvil lingering for hours after.
            case "supercell" -> new Type(12, 48, 0.2, 0.25, 12, 36);
            // Layer clouds last hours to days, and form and clear gradually.
            case "stratus_nebulosus" -> new Type(25, 144, 0.15, 0.2, 0, 0);
            case "stratocumulus" -> new Type(35, 144, 0.15, 0.2, 0, 0);
            case "nimbostratus" -> new Type(72, 288, 0.12, 0.15, 0, 0);
            case "cirrus" -> new Type(25, 96, 0.15, 0.2, 0, 0);
            default -> DEFAULT;
        };
    }

    /**
     * One formation's rolled lifespan for a type, in ticks: {@code birth} and {@code death} lie inside {@code storm};
     * {@code linger} follows it.
     */
    public record Span(int birth, int storm, int death, int linger) {

        /** The whole lifetime: the cloud, then its lingering anvil. */
        public int total() {
            return storm + linger;
        }
    }

    /** Formation {@code regionId}'s lifespan as a cloud of type {@code typeId}. */
    public static Span span(String typeId, UUID regionId) {
        Type t = type(typeId);
        SplittableRandom rng = regionId == null ? new SplittableRandom(7)
                : StormVariety.random(regionId, 0x11FE ^ (typeId == null ? 0 : typeId.hashCode()));
        double u = rng.nextDouble();
        double storm = (t.stormMin + (t.stormMax - t.stormMin) * u * u) * MINUTE;
        double v = rng.nextDouble();
        double linger = (t.lingerMin + (t.lingerMax - t.lingerMin) * v * v) * MINUTE;
        double birth = storm * t.birth;
        double death = storm * t.death;
        if (typeId != null && typeId.contains("supercell")) {
            // A supercell takes at least half an hour of real time to organise (6 min here), and as long to die.
            birth = Math.min(Math.max(birth, 6 * MINUTE), 0.4 * storm);
            death = Math.min(Math.max(death, 6 * MINUTE), 0.4 * storm);
        }
        return new Span((int) Math.round(birth), (int) Math.round(storm), (int) Math.round(death),
                (int) Math.round(linger));
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
         * How strongly it can rain, 0-1, for the localized weather (not used yet; Bright, 2026-10-04: dying storms
         * should taper off and lingering anvils stay dry once our own localized rain exists). Rain starts once the
         * cloud is well formed and tapers to nothing over its death.
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

    /**
     * The phase of a cloud of type {@code typeId} in formation {@code regionId}, at age {@code age} of lifetime
     * {@code lifetime} (ticks, PA's).
     */
    public static Phase phase(String typeId, UUID regionId, double age, double lifetime) {
        return phase(span(typeId, regionId), age, lifetime);
    }

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

    // ---- server-side lifetimes -----------------------------------------------------------------------------------

    /**
     * Lifetimes the server set (rather than PA's default) end in this many ticks past a multiple of {@link #MARK_MOD}.
     * PA saves lifetimes with its clouds, so this marks a cluster as already given its lifespan across restarts.
     * PA's default (12,000) is a multiple, so it never looks marked.
     */
    static final int MARK = 7;
    static final int MARK_MOD = 20;

    /** Whether {@code lifetime} was set by {@link #mark}. */
    public static boolean marked(int lifetime) {
        return Math.floorMod(lifetime, MARK_MOD) == MARK;
    }

    /** {@code lifetime} moved by under a second so that {@link #marked} is true. */
    public static int mark(long lifetime) {
        long base = Math.max(MARK_MOD, lifetime) / MARK_MOD * MARK_MOD;
        return (int) Math.min(Integer.MAX_VALUE - MARK_MOD, base + MARK);
    }

    /**
     * The lifetime for a cluster the server sees for the first time (not {@link #marked}): its span, or, for a cloud
     * that is already older than that (one from before lifespans existed), enough to die and linger from where it is.
     */
    public static long firstLifetime(Span s, long age) {
        return Math.max(s.total(), age + s.death() + s.linger() + MINUTE / 6);
    }

    /**
     * The lifetime for a cluster that has just become type {@code s} (a cumulus growing into a storm): at least its
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
