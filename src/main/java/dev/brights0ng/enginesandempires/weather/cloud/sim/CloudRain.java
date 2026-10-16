package dev.brights0ng.enginesandempires.weather.cloud.sim;

import dev.brights0ng.enginesandempires.weather.cloud.CloudScale;
import dev.brights0ng.enginesandempires.weather.cloud.CloudType;
import dev.brights0ng.enginesandempires.weather.sim.SimMath;

/**
 * How hard each cloud rains, decided by the simulation (phase 4b of {@code claude/weather-backbone-plan.md}). Pure.
 *
 * <h2>Whether and how hard ({@link #precipitation})</h2>
 * A cloud type's own rain ({@link CloudType#rain}) is how hard it <em>can</em> rain; how much of that it does depends on
 * the air it sits in (realistic rain frequency, Bright 2026-10-05):
 * <ul>
 *   <li><b>Cumulonimbus</b> always rain once grown, harder in moist air.</li>
 *   <li><b>Cumulus congestus</b>: about 60% of them shower (by their id), in moist enough air.</li>
 *   <li><b>Nimbostratus</b> rains steadily in moist air, harder where the deck is thick (a front's core).</li>
 *   <li><b>Altostratus</b>: light rain only from a thick deck in moist air (the last stretch before a warm front).</li>
 *   <li><b>Stratus and stratocumulus</b> drizzle only in nearly saturated air (Bright, 2026-10-06: drizzle counts as rain
 *       for gameplay), and only from a solid deck (cover over about half) holding enough water: bitterly cold air
 *       (under ~5 mm of precipitable water) gives at most the odd flurry (weather phase 8b, 2026-10-14: a polar cold pool
 *       under its inversion "snowed" in 60-70% of all hours).</li>
 * </ul>
 * The cloud's life still shapes it on every side ({@code CloudLife.Phase#precipitation}): nothing until it is well
 * formed, tapering as it dies.
 *
 * <h2>Virga ({@link #rainBottom})</h2>
 * Drops falling through air drier than saturation evaporate; how far they last grows with rain strength and with the
 * air's humidity: {@code (500 + 3500 s) RH^2 / (1 - RH)} metres. Below a base higher than that, the rain never lands.
 *
 * <h2>How much water ({@link #rateMmPerHour})</h2>
 * Real rain rates by strength: drizzle well under 1 mm an hour, steady rain 2-5, a thunderstorm's core 15-20
 * ({@code 20 s^2.5}). That much leaves the air under the rain (Bright, 2026-10-06: realistic drain).
 */
public final class CloudRain {

    /** How much rain makes a cloud's rain worth resending to clients. */
    static final double RESEND = 0.05;

    /**
     * How hard cloud {@code c} rains (0-1 of its type's peak) in air {@code need}. {@code thick} is how much of the sky
     * its type covers there (layer types; 1 for heap clouds).
     */
    public static double precipitation(SimCloud c, CloudDiagnostics.Need need) {
        return precipitation(c.type, need, c.type != CloudType.CUMULUS_CONGESTUS || showers(c));
    }

    /**
     * How hard a cloud of type {@code t} rains (0-1 of its type's peak) in air {@code need}, without a particular
     * cloud: {@code showers} says whether a cumulus congestus is one of the showering ones (the forecast asks for the
     * showering kind and counts their share itself).
     */
    public static double precipitation(CloudType t, CloudDiagnostics.Need need, boolean showers) {
        if (!t.rain.rains()) {
            return 0;
        }
        double rh = need.rh();
        double rhNow = 1 - need.lclMetres() / 2500;
        return switch (t) {
            case CUMULONIMBUS_CALVUS, CUMULONIMBUS_CAPILLATUS -> 0.6 + 0.4 * SimMath.smooth((rh - 0.4) / 0.35);
            case CUMULUS_CONGESTUS -> showers ? SimMath.smooth((rh - 0.45) / 0.25) : 0;
            case NIMBOSTRATUS -> SimMath.smooth((rh - 0.55) / 0.2) * (0.6 + 0.4 * SimMath.clamp01(need.cover(t)));
            case ALTOSTRATUS -> SimMath.smooth((need.cover(t) - 0.5) / 0.3) * SimMath.smooth((rh - 0.65) / 0.15);
            // Drizzle; under a high's sinking air only a light one ("anticyclonic gloom").
            case STRATUS -> SimMath.smooth((rhNow - 0.9) / 0.08) * (1 - 0.6 * need.subsidence()) * drizzleDeck(t, need);
            case STRATOCUMULUS -> 0.8 * SimMath.smooth((rhNow - 0.85) / 0.1) * (1 - 0.6 * need.subsidence())
                    * drizzleDeck(t, need);
            default -> 0;
        };
    }

    /**
     * How much a stratus or stratocumulus deck can drizzle: a solid deck, with water in the air, that is thick enough
     * (0-1).
     */
    static double drizzleDeck(CloudType t, CloudDiagnostics.Need need) {
        return SimMath.smooth((need.cover(t) - 0.45) / 0.3) * SimMath.smooth((need.water() - 4) / 8)
                * deckDepth(t, need);
    }

    /**
     * How deep a low deck is, 0-1 (weather phase 8b follow-up, 2026-10-14; Bright chose a thickness proxy): drizzle
     * needs a deck a few hundred metres thick, which takes either lift or a whole layer of near-saturated air. A deck
     * that only forms because the night cools the ground (fog and thin night stratus under air whose day-mean humidity
     * is well short of saturation) is a few tens of metres thick and doesn't drizzle (before: polar summer nights
     * drizzled for ~8 hours each). Depth comes from:
     * <ul>
     *   <li>the day-mean humidity of the air mass ({@code need.rh()}, not the night's {@code rhNow}): from 90%,
     *       full at 100% (just above the ground's boosted equilibrium, {@code Moisture.COLD_BOOST_CAP});</li>
     *   <li>lift: fronts and comma heads, a low's convergence, upslope wind, air the field is wringing out: full from
     *       0.3.</li>
     * </ul>
     */
    static double deckDepth(CloudType t, CloudDiagnostics.Need need) {
        if (LEGACY_8B) {
            return 1;
        }
        // Fog decks (night cooling, warm air over cold sea) are thin whatever the air mass: they need lift to drizzle
        // (round 3, 2026-10-14: summer sea fog over a cold coast's water cell drizzled ~100 h a summer).
        double saturated = isFog(need.sourceOf(t)) ? 0 : SimMath.smooth((need.rh() - 0.90) / 0.1);
        double lift = Math.max(Math.max(need.frontal(), need.convergence()),
                Math.max(need.orographic(), need.wrung()));
        return Math.max(saturated, SimMath.smooth(lift / 0.3));
    }

    /** Whether a low deck's cause makes it fog: radiation fog / night stratus, or advection (sea) fog. */
    static boolean isFog(String source) {
        return "stable humid air".equals(source) || "warm air over cool water".equals(source);
    }

    /**
     * Debug only: {@code -Deae.legacy8b=true} restores the behaviour before the 8b follow-up (drizzle from any solid
     * deck; centred terrain slopes), for before/after climatology runs from the same world copy.
     */
    public static final boolean LEGACY_8B = Boolean.getBoolean("eae.legacy8b");

    /** Whether this cumulus congestus is one of the ones that shower (about 60%, fixed by its id). */
    static boolean showers(SimCloud c) {
        return CloudScale.unit(c.id.getMostSignificantBits() ^ c.id.getLeastSignificantBits(), 7) < 0.6;
    }

    /** How much lightning it makes, 0-1: cumulonimbus only, the fibrous-topped ones most. */
    public static double lightning(SimCloud c, double precipitation) {
        return lightning(c.type, precipitation);
    }

    /** How much lightning a cloud of type {@code t} raining {@code precipitation} makes, 0-1. */
    public static double lightning(CloudType t, double precipitation) {
        return switch (t) {
            case CUMULONIMBUS_CAPILLATUS -> precipitation;
            case CUMULONIMBUS_CALVUS -> 0.5 * precipitation;
            default -> 0;
        };
    }

    /**
     * World y below which its rain has evaporated, or negative infinity if it reaches the ground. {@code strength} is
     * the rain's actual strength (type peak times {@link #precipitation}); {@code rhNow} the air's relative humidity
     * below the cloud.
     */
    public static float rainBottom(double baseY, double strength, double rhNow) {
        if (strength <= 0) {
            return Float.NEGATIVE_INFINITY;
        }
        double rh = Math.max(0, Math.min(0.99, rhNow));
        double lasts = (500 + 3500 * Math.min(1, strength)) * rh * rh / (1 - rh) * CloudScale.SCALE;
        double bottom = baseY - lasts;
        // At or below the ground reference it lands everywhere that matters (valleys aside).
        return bottom <= CloudScale.GROUND_Y - 64 ? Float.NEGATIVE_INFINITY : (float) bottom;
    }

    /** Rain rate at strength {@code s} (0-1), mm per in-game hour (real rates at the pack's one-day-a-day pacing). */
    public static double rateMmPerHour(double s) {
        return 20 * Math.pow(Math.max(0, Math.min(1, s)), 2.5);
    }

    private CloudRain() {
    }
}
