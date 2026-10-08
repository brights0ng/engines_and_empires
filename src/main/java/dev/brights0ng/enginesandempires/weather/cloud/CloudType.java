package dev.brights0ng.enginesandempires.weather.cloud;

import java.util.Locale;

/**
 * The cloud types the pack's weather makes, by their standard (WMO) names: one registry for everything that depends on
 * a cloud's type (phase 4 of {@code claude/weather-backbone-plan.md}). Shared, pure: the server's spawner, the renderer,
 * the rain model, the fog and the debug tools all read their numbers from here.
 *
 * <h2>Families</h2>
 * <ul>
 *   <li><b>Heap</b> (cumulus up to cumulonimbus): single clouds, each with a lifespan of its own ({@link CloudLife}),
 *       formed where the air is unstable. A heap cloud can grow into the next type up if the air allows.</li>
 *   <li><b>Layer</b> (stratus, stratocumulus, nimbostratus, altostratus, cirrostratus, cirrus): sheets laid out by the
 *       spawner where lift and moisture hold. They have no fixed lifespan: they form, last while their conditions do,
 *       and dissolve once they drift out of them ({@link #formMinutes}, {@link #fadeMinutes}).</li>
 * </ul>
 *
 * <h2>Numbers</h2>
 * Heights and sizes are real-world, metres, scaled by {@link CloudScale#SCALE}. Heap bases are normally set by the
 * spawner from the air's humidity (the condensation level); the base range here is the clamp. Lifespans are real ones
 * at x0.2, in real minutes (Bright, 2026-10-04). Look values (density, coverage, softness, towers, anvils, darkness)
 * are the renderer's inputs, 0-1.
 *
 * <p>Rain numbers ({@link #rainPeak} etc.) say how a type <em>can</em> rain; whether a given cloud does, and how hard,
 * is the simulation's call ({@code CloudShape.precipitation}, from phase 4b).
 */
public enum CloudType {

    //        id                         family        deck        baseMin baseMax topMin topMax thick  grows radius water
    // Cumulus deaths are 45% of their lives (Bright, 2026-10-07: dying clouds popped out; they now fray and break up
    // over the whole death).
    // Humilis and mediocris are wider (Bright, 2026-10-07: wider than tall on average, as congestus): radius 400 ->
    // 600 m and 700 -> 1,100 m; the spawner still counts them at their old sizes, so their number is unchanged.
    CUMULUS_HUMILIS("cumulus_humilis", Family.HEAP, Deck.LOW, 600, 1500, 300, 800, true, true, 600, 0.25,
            new Life(2, 8, 0.22, 0.45, 0, 0), new Look(0.55f, 0.8f, 0.5f, 0.15f, 0, 0.15f, 0),
            Rain.NONE, false, Fog.FAIR),
    CUMULUS_MEDIOCRIS("cumulus_mediocris", Family.HEAP, Deck.LOW, 600, 1500, 800, 2000, true, true, 1100, 0.4,
            new Life(4, 9, 0.25, 0.45, 0, 0), new Look(0.65f, 0.85f, 0.45f, 0.35f, 0, 0.25f, 0),
            Rain.NONE, false, Fog.FAIR),
    // Radius 3 km (2026-10-07, Bright: congestus are usually tall and wide): 1-2.5x as wide as tall.
    CUMULUS_CONGESTUS("cumulus_congestus", Family.HEAP, Deck.LOW, 600, 1500, 3000, 6000, true, true, 3000, 0.7,
            new Life(6, 12, 0.3, 0.45, 0, 0), new Look(0.8f, 0.9f, 0.35f, 0.7f, 0, 0.4f, 0.2f),
            new Rain(0.3, 0.45, false), false, Fog.THICK),
    CUMULONIMBUS_CALVUS("cumulonimbus_calvus", Family.HEAP, Deck.LOW, 500, 1500, 8000, 12000, false, true, 4000,
            1.2, new Life(6, 12, 0.3, 0.35, 4, 12), new Look(0.9f, 0.95f, 0.3f, 0.85f, 0.35f, 0.6f, 0.5f),
            new Rain(0.75, 0.55, false), true, Fog.STORM),
    CUMULONIMBUS_CAPILLATUS("cumulonimbus_capillatus", Family.HEAP, Deck.LOW, 500, 1500, 10000, 15000, false, true,
            6000, 1.5, new Life(9, 18, 0.3, 0.35, 12, 36), new Look(0.95f, 0.95f, 0.3f, 0.9f, 0.8f, 0.7f, 0.6f),
            new Rain(0.95, 0.6, false), true, Fog.STORM),
    STRATUS("stratus", Family.LAYER, Deck.LOW, 100, 500, 200, 600, true, false, 3000, 0.25,
            new Life(25, 144, 0.15, 0.2, 0, 0), new Look(0.5f, 0.95f, 0.7f, 0, 0, 0.3f, 0),
            new Rain(0.15, 0.9, true), false, Fog.THICK),
    STRATOCUMULUS("stratocumulus", Family.LAYER, Deck.LOW, 500, 2000, 200, 800, true, false, 2500, 0.3,
            new Life(35, 144, 0.15, 0.2, 0, 0), new Look(0.55f, 0.75f, 0.55f, 0.05f, 0, 0.3f, 0),
            new Rain(0.2, 0.7, true), false, Fog.FAIR),
    NIMBOSTRATUS("nimbostratus", Family.LAYER, Deck.LOW, 300, 1500, 3000, 6000, false, false, 5000, 0.5,
            new Life(72, 288, 0.12, 0.15, 0, 0), new Look(0.85f, 0.98f, 0.6f, 0, 0, 0.6f, 0.4f),
            new Rain(0.55, 0.95, true), false, Fog.STORM),
    ALTOSTRATUS("altostratus", Family.LAYER, Deck.MID, 2000, 6000, 500, 2000, true, false, 5000, 0.2,
            new Life(60, 240, 0.15, 0.2, 0, 0), new Look(0.45f, 0.95f, 0.7f, 0, 0, 0.2f, 0),
            new Rain(0.12, 0.9, true), false, Fog.FAIR),
    CIRROSTRATUS("cirrostratus", Family.LAYER, Deck.HIGH, 6000, 10000, 300, 1000, true, false, 5000, 0.03,
            new Life(60, 240, 0.15, 0.2, 0, 0), new Look(0.2f, 0.9f, 0.85f, 0, 0, 0, 0),
            Rain.NONE, false, Fog.HIGH),
    CIRRUS("cirrus", Family.LAYER, Deck.HIGH, 6000, 10000, 300, 1500, true, false, 2000, 0.02,
            new Life(25, 96, 0.15, 0.2, 0, 0), new Look(0.25f, 0.6f, 0.9f, 0, 0, 0, 0),
            Rain.NONE, false, Fog.HIGH);

    /** Heap clouds have lifespans of their own; layer clouds last while their conditions do. */
    public enum Family { HEAP, LAYER }

    /** Which deck a cloud sits in: the spawner keeps at most one layer cloud per deck in each place. */
    public enum Deck { LOW, MID, HIGH }

    /** How thick a cloud's fog is from inside ({@code FogTuning}). */
    public enum Fog { STORM, THICK, FAIR, HIGH }

    /**
     * Real lifespans at x0.2, minutes: the cloud itself ({@code stormMin..stormMax}), its birth and death as fractions
     * of that, and the anvil left afterwards ({@code lingerMin..lingerMax}, 0 for none).
     */
    public record Life(double stormMin, double stormMax, double birth, double death, double lingerMin,
                       double lingerMax) {
    }

    /** The renderer's inputs, 0-1. */
    public record Look(float density, float coverage, float edgeSoftness, float tower, float anvil, float baseDarkness,
                       float stormDarkness) {
    }

    /** How a type rains at most: peak strength, its core's radius as a share of the cloud's, and whether a sheet. */
    public record Rain(double peak, double core, boolean sheet) {
        public static final Rain NONE = new Rain(0, 0, false);

        public boolean rains() {
            return peak > 0;
        }
    }

    public final String id;
    public final Family family;
    public final Deck deck;
    /** Base heights above the ground, metres. */
    public final double baseMin;
    public final double baseMax;
    /** Tops: a thickness above the base if {@link #topIsThickness}, otherwise a height above the ground, metres. */
    public final double topMin;
    public final double topMax;
    public final boolean topIsThickness;
    /** Whether its top builds up from its base as it forms (convective clouds). */
    public final boolean grows;
    /** A typical cloud's real radius, metres. */
    public final double radius;
    /** Liquid water content, g/m^3 (real-world values; for shading). */
    public final double water;
    public final Life life;
    public final Look look;
    public final Rain rain;
    public final boolean thunder;
    public final Fog fog;

    CloudType(String id, Family family, Deck deck, double baseMin, double baseMax, double topMin, double topMax,
              boolean topIsThickness, boolean grows, double radius, double water, Life life, Look look, Rain rain,
              boolean thunder, Fog fog) {
        this.id = id;
        this.family = family;
        this.deck = deck;
        this.baseMin = baseMin;
        this.baseMax = baseMax;
        this.topMin = topMin;
        this.topMax = topMax;
        this.topIsThickness = topIsThickness;
        this.grows = grows;
        this.radius = radius;
        this.water = water;
        this.life = life;
        this.look = look;
        this.rain = rain;
        this.thunder = thunder;
        this.fog = fog;
    }

    /** A typical cloud's radius, blocks. */
    public double radiusBlocks() {
        return radius * CloudScale.SCALE;
    }

    /**
     * The radius the spawner counts a cloud of this type as filling, blocks: humilis and mediocris at their sizes
     * before they were widened (2026-10-07, Bright: keep the count), so the same number of them spawn.
     */
    public double countRadiusBlocks() {
        return switch (this) {
            case CUMULUS_HUMILIS -> 400 * CloudScale.SCALE;
            case CUMULUS_MEDIOCRIS -> 700 * CloudScale.SCALE;
            default -> radiusBlocks();
        };
    }

    public boolean heap() {
        return family == Family.HEAP;
    }

    public boolean layer() {
        return family == Family.LAYER;
    }

    /** How long a layer cloud takes to form, real minutes (heap clouds: their lifespan's birth). */
    public double formMinutes() {
        return switch (this) {
            case NIMBOSTRATUS, ALTOSTRATUS, CIRROSTRATUS -> 4;
            default -> 3;
        };
    }

    /** How long a layer cloud takes to dissolve once its conditions are gone, real minutes. */
    public double fadeMinutes() {
        return formMinutes();
    }

    /** The next heap type up, or null at the top (cumulus grows into congestus, congestus into cumulonimbus). */
    public CloudType grownInto() {
        return switch (this) {
            case CUMULUS_HUMILIS -> CUMULUS_MEDIOCRIS;
            case CUMULUS_MEDIOCRIS -> CUMULUS_CONGESTUS;
            case CUMULUS_CONGESTUS -> CUMULONIMBUS_CALVUS;
            case CUMULONIMBUS_CALVUS -> CUMULONIMBUS_CAPILLATUS;
            default -> null;
        };
    }

    /** The type with id {@code typeId} (a namespace is ignored), or null for unknown ids. */
    public static CloudType of(String typeId) {
        if (typeId == null) {
            return null;
        }
        String id = typeId.contains(":") ? typeId.substring(typeId.indexOf(':') + 1) : typeId;
        for (CloudType t : values()) {
            if (t.id.equals(id)) {
                return t;
            }
        }
        return null;
    }

    /** The type named {@code name}: an id or the enum name, any case. Null if none. */
    public static CloudType byName(String name) {
        if (name == null) {
            return null;
        }
        CloudType t = of(name.toLowerCase(Locale.ROOT));
        if (t != null) {
            return t;
        }
        try {
            return valueOf(name.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
