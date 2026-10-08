package dev.brights0ng.enginesandempires.weather.cloud.sim;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import dev.brights0ng.enginesandempires.weather.climate.ClimateCurves;
import dev.brights0ng.enginesandempires.weather.cloud.CloudType;
import dev.brights0ng.enginesandempires.weather.field.AtmosphereField;
import dev.brights0ng.enginesandempires.weather.field.Moisture;
import dev.brights0ng.enginesandempires.weather.sim.FrontGeometry;
import dev.brights0ng.enginesandempires.weather.sim.SimMath;
import dev.brights0ng.enginesandempires.weather.sim.WeatherSystem;

/**
 * Where clouds belong, worked out from the atmosphere (phase 4a of {@code claude/weather-backbone-plan.md}): for one
 * spot, the air's humidity and condensation level, its lift and its instability, and from those which clouds the
 * spawner should keep there and how much of the sky they cover. Pure: the field and the weather systems come in
 * through {@link Air} and {@link Systems}.
 *
 * <h2>What makes cloud</h2>
 * Clouds need moisture plus lift. The lift here comes from:
 * <ul>
 *   <li><b>Warm fronts:</b> warm air sliding up a shallow slope over the cold air ahead of the front, so the cloud
 *       reaches far ahead of it, lowering and thickening as the front nears: cirrus about 2-3.5 thousand blocks ahead,
 *       then cirrostratus, then altostratus, and nimbostratus over the last few hundred blocks and just behind.</li>
 *   <li><b>Cold fronts:</b> cold air shoving warm air up steeply: a narrow line of heap cloud (congestus up to
 *       cumulonimbus where the air allows) right at the front, broken stratocumulus behind it.</li>
 *   <li><b>Occluded fronts:</b> a band of nimbostratus with altostratus either side.</li>
 *   <li><b>Converging air</b> near a low's centre: layered cloud, nimbostratus in a deep low.</li>
 *   <li><b>Terrain:</b> wind blowing up a slope ({@code wind . slope}); moist air capped by stratus or stratocumulus,
 *       very moist air (or air the field is already wringing out) by nimbostratus.</li>
 *   <li><b>Sun-heated ground</b> (convection): see instability.</li>
 * </ul>
 * Highs work against all of it: their sinking air thins layer cloud and caps convection.
 *
 * <h2>Instability and heap clouds</h2>
 * How much warmer the air at the ground is than the air aloft, past the normal {@value #NORMAL_LAPSE} C difference:
 * the daytime sun ({@link ClimateCurves#solar}, 8:00-18:30, weaker in winter) warms the ground and makes the air unstable; the
 * night cools it and makes it stable. Water barely heats by day, but water warmer than the air above it heats that air
 * from below (cold air over a warm sea: ocean-effect showers). Forced lift (fronts, convergence, slopes) adds to it.
 * Humid air is needed too: dry air makes few, high-based cumulus. The result picks the biggest heap type the air can
 * build ({@link #heapType}) and how much of the sky it fills: on a sunny summer afternoon fair-weather cumulus pop up
 * and fade again by evening (Bright, 2026-10-05: the conditions are simulated, each cloud's place inside a field cell
 * is procedural).
 *
 * <h2>Condensation level</h2>
 * Low cloud bases sit where rising air reaches saturation: about 125 m per C of difference between air temperature and
 * dew point, so {@code 2500 m x (1 - RH)} with the dew point estimated from the humidity.
 */
public final class CloudDiagnostics {

    /** The air-aloft difference of a neutral atmosphere, C (the field's normal surface-to-aloft offset). */
    public static final double NORMAL_LAPSE = -AtmosphereField.ALOFT_OFFSET;

    /** Heap types by the instability needed: humilis, mediocris, congestus, calvus, capillatus. */
    static final double[] HEAP_THRESHOLDS = {0.12, 0.35, 0.6, 0.85, 1.1};
    static final CloudType[] HEAP_TYPES = {CloudType.CUMULUS_HUMILIS, CloudType.CUMULUS_MEDIOCRIS,
            CloudType.CUMULUS_CONGESTUS, CloudType.CUMULONIMBUS_CALVUS, CloudType.CUMULONIMBUS_CAPILLATUS};

    /**
     * The air over one spot, from the field.
     *
     * @param t         surface air temperature at sea level, daily mean, C
     * @param a         aloft air temperature, C
     * @param q         precipitable water, mm
     * @param p         recent precipitation wrung out of the air (orographic), mm
     * @param base      the climate's normal surface temperature (stands for the water temperature over water), C
     * @param humidity  the biome humidity, 0-1
     * @param surface   surface ordinal (0 land, 1 forest, 2 water, 3 ice)
     * @param elevation terrain above sea level, blocks
     * @param slopeX    terrain slope, blocks per block, x
     * @param slopeZ    z
     * @param windSX    surface wind, m/s
     * @param windAX    aloft wind, m/s
     * @param heightCorrection how much warmer (negative: colder) the air at {@code elevation} is, C
     */
    public record Air(double t, double a, double q, double p, double base, double humidity, int surface,
                      double elevation, double slopeX, double slopeZ, double windSX, double windSZ, double windAX,
                      double windAZ, double heightCorrection) {
    }

    /** The weather systems near the players, prepared once per spawner pass. */
    public static final class Systems {
        record Front(FrontGeometry.Type type, double[] xs, double[] zs, double strength, int hemisphere, double scale,
                     double minX, double maxX, double minZ, double maxZ) {
        }

        record Centre(boolean low, double x, double z, double radius, double strength) {
        }

        final List<Front> fronts = new ArrayList<>();
        final List<Centre> centres = new ArrayList<>();

        /** The fronts and centres of {@code systems}. */
        public static Systems of(List<WeatherSystem> systems) {
            Systems s = new Systems();
            for (WeatherSystem w : systems) {
                double strength = SimMath.clamp01(w.strength() / Math.max(1e-6, w.peak));
                s.centres.add(new Centre(w.kind == WeatherSystem.Kind.LOW, w.x, w.z, w.radius(), strength));
                if (w.kind != WeatherSystem.Kind.LOW) {
                    continue;
                }
                double scale = Math.max(0.6, Math.min(1.4, w.radius() / 4500));
                for (FrontGeometry.Front f : FrontGeometry.of(w)) {
                    int n = f.points().size();
                    double[] xs = new double[n];
                    double[] zs = new double[n];
                    double minX = Double.MAX_VALUE, maxX = -Double.MAX_VALUE, minZ = Double.MAX_VALUE,
                            maxZ = -Double.MAX_VALUE;
                    for (int i = 0; i < n; i++) {
                        xs[i] = f.points().get(i)[0];
                        zs[i] = f.points().get(i)[1];
                        minX = Math.min(minX, xs[i]);
                        maxX = Math.max(maxX, xs[i]);
                        minZ = Math.min(minZ, zs[i]);
                        maxZ = Math.max(maxZ, zs[i]);
                    }
                    s.fronts.add(new Front(f.type(), xs, zs, f.strength(), w.hemisphere, scale, minX, maxX, minZ,
                            maxZ));
                }
            }
            return s;
        }

        public static final Systems NONE = new Systems();
    }

    /**
     * What the spawner should keep at a spot.
     *
     * @param layer      sky cover wanted from each layer type, by {@link CloudType#ordinal()} (0 for heap types)
     * @param baseHint   per layer type, where in its base range to put the base (0 lowest, 1 highest)
     * @param heapType   the biggest heap cloud the air can build here, or null for none
     * @param heapCover  how much of the sky heap clouds fill, 0-1
     * @param lclMetres  the condensation level (low cloud base), metres above the ground
     * @param rh         relative humidity at the ground, 0-1
     * @param instability how unstable the air is, with forced lift (0 stable, 1 thunderstorm-ready)
     * @param heating    the afternoon heating (or night cooling) of the surface air, C
     * @param warmAhead  how far ahead of the nearest warm front, blocks (negative: behind it; NaN: none near)
     * @param frontal    the strongest frontal influence here, 0-1, and which front ({@link #front})
     * @param orographic upslope lift, 0-1
     * @param convergence lift near a low's centre, 0-1
     * @param subsidence sinking air under a high, 0-1
     * @param wrung      the field's recent condensation (rain wrung out of cooling air), 0-1
     * @param water      precipitable water in the air, mm
     * @param source     per layer type, what put it there (for the audit and debug output)
     * @param heapSource what drives the heap clouds here
     */
    public record Need(double[] layer, double[] baseHint, CloudType heapType, double heapCover, double lclMetres,
                       double rh, double instability, double heating, double warmAhead, double frontal, String front,
                       double orographic, double convergence, double subsidence, double wrung, double water,
                       String[] source, String heapSource) {

        /** Without the audit's extras (tests). */
        public Need(double[] layer, double[] baseHint, CloudType heapType, double heapCover, double lclMetres,
                    double rh, double instability, double heating, double warmAhead, double frontal, String front,
                    double orographic, double convergence, double subsidence) {
            this(layer, baseHint, heapType, heapCover, lclMetres, rh, instability, heating, warmAhead, frontal, front,
                    orographic, convergence, subsidence, 0, 0, new String[CloudType.values().length], "none");
        }

        /** What put layer type {@code t} here, or "none". */
        public String sourceOf(CloudType t) {
            String s = source[t.ordinal()];
            return s == null ? "none" : s;
        }

        /** The layer type wanted most in {@code deck}, or null. */
        public CloudType layerIn(CloudType.Deck deck, double threshold) {
            CloudType best = null;
            double cover = threshold;
            for (CloudType t : CloudType.values()) {
                if (t.layer() && t.deck == deck && layer[t.ordinal()] > cover) {
                    best = t;
                    cover = layer[t.ordinal()];
                }
            }
            return best;
        }

        public double cover(CloudType t) {
            return layer[t.ordinal()];
        }

        /** One line for debug output. */
        public String describe() {
            StringBuilder sb = new StringBuilder();
            for (CloudType t : CloudType.values()) {
                if (layer[t.ordinal()] > 0.05) {
                    sb.append(String.format(Locale.ROOT, " %s %.0f%%", t.id, 100 * layer[t.ordinal()]));
                }
            }
            if (heapType != null && heapCover > 0.02) {
                sb.append(String.format(Locale.ROOT, " heap up to %s %.0f%%", heapType.id, 100 * heapCover));
            }
            return sb.isEmpty() ? " clear" : sb.toString();
        }
    }

    /**
     * Diagnoses the spot (x, z) with air {@code air}, the systems {@code systems}, Minecraft day time {@code dayTime}
     * and season factor {@code season} (-1 midwinter to +1 midsummer).
     */
    public static Need diagnose(double x, double z, Air air, Systems systems, long dayTime, double season) {
        double s = Double.isFinite(season) ? Math.max(-1, Math.min(1, season)) : 0;
        boolean water = air.surface() == 2;
        Layers b = new Layers();
        double[] layer = b.layer;

        // ---- humidity and condensation level
        double heating = water ? 0 : ClimateCurves.diurnal(dayTime) * ClimateCurves.diurnalRange(air.humidity())
                * (1 + 0.35 * s);
        double surfaceT = air.t() + heating;
        double groundT = surfaceT + air.heightCorrection();
        // The humidity now sets the cloud base; the day's mean humidity says how moist the air mass is.
        double rhNow = Math.max(0, Math.min(1.2, air.q() / Moisture.capacity(groundT)));
        double rh = Math.max(0, Math.min(1.2, air.q() / Moisture.capacity(air.t() + air.heightCorrection())));
        double lcl = Math.max(0, 2500 * (1 - Math.min(1, rhNow)));

        // ---- systems: fronts, low centres, highs
        double[] hint = new double[CloudType.values().length];
        java.util.Arrays.fill(hint, 0.5);
        double warmAhead = Double.NaN;
        double frontal = 0;
        String front = "none";
        double frontLift = 0;
        for (Systems.Front f : systems.fronts) {
            double reach = 4000 * f.scale();
            if (x < f.minX() - reach || x > f.maxX() + reach || z < f.minZ() - reach || z > f.maxZ() + reach) {
                continue;
            }
            double[] ds = signedDistance(f, x, z);
            double d = ds[0];
            double along = ds[1];
            double st = f.strength() * (1 - 0.5 * along);
            double L = f.scale();
            switch (f.type()) {
                case WARM -> {
                    b.reason = "warm front";
                    // Ahead (positive) is the cold side.
                    if (Double.isNaN(warmAhead) || Math.abs(d) < Math.abs(warmAhead)) {
                        warmAhead = d;
                    }
                    b.raise(CloudType.NIMBOSTRATUS, st * band(d, -300 * L, 600 * L, 200 * L)
                            * SimMath.smooth((rh - 0.45) / 0.2));
                    b.raise(CloudType.ALTOSTRATUS, st * band(d, 300 * L, 1500 * L, 250 * L)
                            * SimMath.smooth((rh - 0.25) / 0.2));
                    b.raise(CloudType.CIRROSTRATUS, st * band(d, 1200 * L, 2400 * L, 250 * L));
                    b.raise(CloudType.CIRRUS, 0.6 * st * band(d, 2000 * L, 3500 * L, 300 * L));
                    // The deck lowers as the front nears.
                    hint[CloudType.ALTOSTRATUS.ordinal()] = SimMath.clamp01((d - 300 * L) / (1200 * L));
                    hint[CloudType.CIRROSTRATUS.ordinal()] = SimMath.clamp01((d - 1200 * L) / (1200 * L));
                    hint[CloudType.NIMBOSTRATUS.ordinal()] = SimMath.clamp01((d + 300 * L) / (900 * L));
                    double here = st * band(d, -300 * L, 2400 * L, 300 * L);
                    if (here > frontal) {
                        frontal = here;
                        front = "warm";
                    }
                }
                case COLD -> {
                    b.reason = "cold front";
                    // Ahead (positive) is the warm sector.
                    double line = st * band(d, -250 * L, 400 * L, 150 * L);
                    frontLift = Math.max(frontLift, line);
                    b.raise(CloudType.STRATOCUMULUS, 0.55 * st * band(d, -2500 * L, -350 * L, 300 * L)
                            * SimMath.smooth((rh - 0.35) / 0.2));
                    b.raise(CloudType.ALTOSTRATUS, 0.4 * st * band(d, -900 * L, 300 * L, 200 * L)
                            * SimMath.smooth((rh - 0.35) / 0.2));
                    double here = Math.max(line, 0.55 * st * band(d, -2500 * L, -350 * L, 300 * L));
                    if (here > frontal) {
                        frontal = here;
                        front = "cold";
                    }
                }
                case OCCLUDED -> {
                    b.reason = "occluded front";
                    double ad = Math.abs(d);
                    b.raise(CloudType.NIMBOSTRATUS, st * band(ad, -1, 900 * L, 250 * L)
                            * SimMath.smooth((rh - 0.4) / 0.2));
                    b.raise(CloudType.ALTOSTRATUS, 0.8 * st * band(ad, 600 * L, 1800 * L, 250 * L));
                    b.raise(CloudType.CIRROSTRATUS, 0.5 * st * band(ad, 1400 * L, 2600 * L, 300 * L));
                    frontLift = Math.max(frontLift, 0.4 * st * band(ad, -1, 700 * L, 200 * L));
                    double here = st * band(ad, -1, 1800 * L, 250 * L);
                    if (here > frontal) {
                        frontal = here;
                        front = "occluded";
                    }
                }
            }
        }
        double convergence = 0;
        double subsidence = 0;
        for (Systems.Centre c : systems.centres) {
            double dx = x - c.x();
            double dz = z - c.z();
            double r = c.radius();
            double d2 = dx * dx + dz * dz;
            if (c.low()) {
                double core = 0.5 * r;
                if (d2 < 9 * core * core) {
                    convergence = Math.max(convergence, c.strength() * Math.exp(-d2 / (core * core)));
                }
            } else if (d2 < 9 * r * r) {
                subsidence = Math.max(subsidence, c.strength() * Math.exp(-d2 / (r * r)));
            }
        }
        if (convergence > 0.05) {
            // A low's centre is rising air: it cancels a nearby high's sinking (2026-10-06: a building high 5 km off
            // halved the nimbostratus at a deepening low's core).
            subsidence *= 1 - SimMath.clamp01(convergence);
            b.reason = "low centre";
            double moist = SimMath.smooth((rh - 0.5) / 0.2);
            if (convergence > 0.5) {
                b.raise(CloudType.NIMBOSTRATUS, convergence * moist);
            }
            b.raise(CloudType.STRATOCUMULUS, 0.8 * convergence * SimMath.smooth((rh - 0.4) / 0.2));
            b.raise(CloudType.ALTOSTRATUS, 0.7 * convergence * moist);
        }

        // ---- terrain
        double upslope = air.windSX() * air.slopeX() + air.windSZ() * air.slopeZ();
        double orographic = SimMath.clamp01(upslope / 2);
        double wrung = SimMath.clamp01(air.p() / 2);
        if (orographic > 0.05) {
            b.reason = "upslope";
            b.raise(CloudType.STRATOCUMULUS, orographic * SimMath.smooth((rh - 0.55) / 0.2));
            b.raise(CloudType.STRATUS, 0.7 * orographic * SimMath.smooth((rh - 0.75) / 0.15));
            // The field's wrung-out water counts as mountain rain only where the wind is actually climbing a slope
            // (2026-10-06: it also condenses where cold air cools moist air over flat ground, which isn't a deck).
            b.raise(CloudType.NIMBOSTRATUS, Math.max(wrung * SimMath.smooth(orographic / 0.3),
                    orographic * SimMath.smooth((rh - 0.85) / 0.1)));
        }
        if (wrung > 0.05) {
            // Moist air cooled to saturation over flat ground or water: low stratus and fog, not a raining deck.
            b.reason = "cooled moist air";
            b.raise(CloudType.STRATUS, 0.6 * wrung * SimMath.smooth((rh - 0.7) / 0.2));
        }

        // ---- stability: low stratus in moist, stable air (calm humid nights, cool water under warm air)
        // A sky already covered by layer cloud shades the ground: little afternoon heating under it.
        double overcast = 0;
        for (CloudType t : CloudType.values()) {
            if (t.layer() && t.deck != CloudType.Deck.HIGH) {
                overcast = Math.max(overcast, layer[t.ordinal()]);
            }
        }
        // Convection follows the sun, not the air temperature (2026-10-06: the temperature stays above the day's mean
        // until 22:30, which kept cumulus forming after dusk). By day the sun's heating; at night the cooling.
        double solar = water ? 0 : ClimateCurves.solar(dayTime) * ClimateCurves.diurnalRange(air.humidity())
                * (1 + 0.35 * s);
        double sunHeating = solar > 0 ? solar * (1 - 0.8 * SimMath.clamp01(overcast)) : Math.min(0, heating);
        double effective = water ? Math.max(air.t(), air.base()) : air.t() + sunHeating;
        double excess = effective - air.a() - NORMAL_LAPSE;
        double calm = 1 - SimMath.smooth((Math.hypot(air.windSX(), air.windSZ()) - 3) / 4);
        if (excess < 0) {
            b.reason = "stable humid air";
            b.raise(CloudType.STRATUS, calm * SimMath.smooth((rhNow - 0.98) / 0.08));
        }
        if (water && air.t() > air.base() + 2) {
            // Warm, moist air over cooler water: sea fog and stratus.
            b.reason = "warm air over cool water";
            b.raise(CloudType.STRATUS, SimMath.smooth((rh - 0.8) / 0.15));
        }
        b.reason = "humid air";
        b.raise(CloudType.STRATOCUMULUS, 0.5 * SimMath.smooth((rh - 0.75) / 0.15) * SimMath.smooth(-excess / 3 + 1));

        // ---- instability and heap clouds
        double moistFactor = SimMath.clamp01((rh - 0.3) / 0.5);
        // Deep convection needs water as well as humidity (2026-10-06: cold air holds little, so it builds cumulus
        // and the odd towering cumulus, not thunderstorms): weak below 6 mm of precipitable water, full from 20.
        double waterFactor = SimMath.smooth((air.q() - 6) / 14);
        double buoyancy = Math.max(0, Math.min(1.5, excess / 8)) * (0.4 + 0.6 * moistFactor);
        // Over land the night's inversion caps surface-based convection; the sea keeps its showers.
        if (!water) {
            buoyancy *= ClimateCurves.landBuoyancy(dayTime);
        }
        double instability = buoyancy * (0.35 + 0.65 * waterFactor);
        double forced = 1.2 * frontLift + 0.4 * convergence + 0.4 * orographic;
        instability += forced * (0.3 + 0.7 * moistFactor) * (0.5 + 0.5 * waterFactor);
        instability *= 1 - 0.5 * subsidence;
        CloudType heapType = heapType(instability, rh, air.q());
        // A high's sinking air caps convection under an inversion: small fair-weather cumulus only.
        if (subsidence > LID_SUBSIDENCE && heapType != null && heapType.ordinal() > CloudType.CUMULUS_HUMILIS.ordinal()) {
            heapType = CloudType.CUMULUS_HUMILIS;
        }
        String heapSource = heapType == null ? "none"
                : 1.2 * frontLift >= Math.max(buoyancy, 0.4 * Math.max(convergence, orographic)) ? "cold front line"
                : buoyancy >= 0.4 * Math.max(convergence, orographic)
                        ? (water ? "warm water under cold air" : sunHeating > 0 ? SUN : "unstable air")
                        : convergence >= orographic ? "low centre" : "upslope";
        double heapCover = 0;
        double frontLine = 0;
        if (heapType != null) {
            heapCover = Math.min(0.45, 0.25 + 0.3 * instability) * SimMath.smooth((rh - 0.25) / 0.2);
            // A front's line of storms fills its band; elsewhere convection is scattered.
            frontLine = 0.7 * frontLift * SimMath.smooth((rh - 0.4) / 0.2);
        }

        // ---- highs thin everything (the deep, raining decks most); a nimbostratus fills the middle deck itself
        for (CloudType t : CloudType.values()) {
            if (t.layer()) {
                double keep = t == CloudType.NIMBOSTRATUS || t == CloudType.ALTOSTRATUS
                        ? (1 - subsidence) * (1 - subsidence) : 1 - 0.8 * subsidence;
                layer[t.ordinal()] = SimMath.clamp01(layer[t.ordinal()] * keep);
            }
        }
        if (heapType != null) {
            // Under a low or middle overcast, scattered cumulus thin out (no sun, weaker thermals); a front's line and
            // thunderstorms punch through (2026-10-06: a full cumulus field filled the sky under a low's decks).
            double deck = 0;
            for (CloudType t : CloudType.values()) {
                if (t.layer() && t.deck != CloudType.Deck.HIGH) {
                    deck = Math.max(deck, layer[t.ordinal()]);
                }
            }
            boolean storm = heapType.ordinal() >= CloudType.CUMULONIMBUS_CALVUS.ordinal();
            heapCover = Math.max(storm ? heapCover : heapCover * (1 - 0.7 * SimMath.clamp01(deck)), frontLine);
            // Hills and ridges set thermals off more readily than flat ground.
            double slope = Math.hypot(air.slopeX(), air.slopeZ());
            heapCover *= 1 + 0.4 * SimMath.clamp01(slope * 2);
            heapCover *= 1 - 0.5 * subsidence;
        }
        // Low bases follow the condensation level.
        hint[CloudType.STRATUS.ordinal()] = lclHint(CloudType.STRATUS, lcl);
        hint[CloudType.STRATOCUMULUS.ordinal()] = lclHint(CloudType.STRATOCUMULUS, lcl);
        if (frontal <= 0 && convergence <= 0.05) {
            hint[CloudType.NIMBOSTRATUS.ordinal()] = lclHint(CloudType.NIMBOSTRATUS, lcl);
        }
        // The heating reported is the sun's (what drives convection), or the night's cooling.
        return new Need(layer, hint, heapType, SimMath.clamp01(heapCover), lcl, Math.min(1, rh), instability, sunHeating,
                warmAhead, frontal, front, orographic, convergence, subsidence, wrung, air.q(), b.source, heapSource);
    }

    /** Layer cover being built up, and what put each type there. */
    private static final class Layers {
        final double[] layer = new double[CloudType.values().length];
        final String[] source = new String[CloudType.values().length];
        String reason = "?";

        /** {@code value} into slot {@code t} if it is more than what is there. */
        void raise(CloudType t, double value) {
            if (value > layer[t.ordinal()]) {
                layer[t.ordinal()] = value;
                source[t.ordinal()] = reason;
            }
        }
    }

    /** {@link #heapType(double, double, double)} with plenty of water. */
    static CloudType heapType(double i, double rh) {
        return heapType(i, rh, 30);
    }

    /**
     * The biggest heap type instability {@code i} builds in air of relative humidity {@code rh} holding {@code water}
     * mm, or null. Thunderstorms need moist air holding at least {@value #STORM_WATER} mm.
     */
    static CloudType heapType(double i, double rh, double water) {
        if (rh < 0.25) {
            return null;
        }
        CloudType best = null;
        for (int k = 0; k < HEAP_TYPES.length; k++) {
            if (i < HEAP_THRESHOLDS[k]) {
                break;
            }
            // Storms need moist air.
            if (k >= 3 && (rh < 0.5 + 0.05 * (k - 3) || water < STORM_WATER)) {
                break;
            }
            best = HEAP_TYPES[k];
        }
        return best;
    }

    /** The least precipitable water a thunderstorm needs, mm (about what air at 7-8 C can hold, half full). */
    static final double STORM_WATER = 12;

    /** Sinking (0-1) above which a high's inversion caps heap clouds at cumulus humilis. */
    static final double LID_SUBSIDENCE = 0.5;

    /** The cause given to heap clouds the sun's heating builds. */
    public static final String SUN = "daytime sun";

    /** Where in type {@code t}'s base range a base at the condensation level {@code lcl} (metres) falls, 0-1. */
    static double lclHint(CloudType t, double lcl) {
        return SimMath.clamp01((lcl - t.baseMin) / Math.max(1, t.baseMax - t.baseMin));
    }

    /** 1 inside [lo, hi], easing to 0 over {@code edge} outside it. */
    static double band(double v, double lo, double hi, double edge) {
        if (v < lo) {
            return 1 - SimMath.smooth((lo - v) / edge);
        }
        if (v > hi) {
            return 1 - SimMath.smooth((v - hi) / edge);
        }
        return 1;
    }

    /**
     * Distance from (x, z) to front {@code f}, signed: positive ahead of it (a warm front's cold side, a cold front's
     * warm side), and how far along it the nearest point is (0-1). {distance, along}
     */
    static double[] signedDistance(Systems.Front f, double x, double z) {
        double best = Double.POSITIVE_INFINITY;
        double sign = 1;
        double along = 0;
        double total = 0;
        int n = f.xs().length;
        double[] lengths = new double[n];
        for (int i = 1; i < n; i++) {
            total += Math.hypot(f.xs()[i] - f.xs()[i - 1], f.zs()[i] - f.zs()[i - 1]);
            lengths[i] = total;
        }
        for (int i = 1; i < n; i++) {
            double ax = f.xs()[i - 1], az = f.zs()[i - 1];
            double dx = f.xs()[i] - ax, dz = f.zs()[i] - az;
            double len2 = dx * dx + dz * dz;
            double t = len2 <= 0 ? 0 : SimMath.clamp01(((x - ax) * dx + (z - az) * dz) / len2);
            double px = ax + t * dx - x;
            double pz = az + t * dz - z;
            double d = Math.sqrt(px * px + pz * pz);
            if (d < best) {
                best = d;
                // In the northern frame (+z south) both fronts' "ahead" side has a negative cross product.
                double cross = dx * (z - az) - dz * (x - ax);
                sign = cross * f.hemisphere() < 0 ? 1 : -1;
                along = total <= 0 ? 0 : (lengths[i - 1] + t * Math.sqrt(len2)) / total;
            }
        }
        return new double[]{sign * best, along};
    }

    private CloudDiagnostics() {
    }
}
