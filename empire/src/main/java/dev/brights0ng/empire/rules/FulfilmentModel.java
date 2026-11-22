// Written by Gemini.
package dev.brights0ng.empire.rules;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Pure fulfilment math for Empire civilians ({@code docs/empire/civilian-fulfilment.md}, Bright & Claude).
 *
 * <p>Eleven meters in four gated tiers (Survival, Security, Belonging, Purpose; 25% weight each) combine into
 * Happiness (0–1), divided by empire-wide Expectation {@code E(n)} to produce Fulfilment. No Minecraft imports.
 */
public final class FulfilmentModel {

    /** PROVISIONAL default value for meters whose source system is not yet built. */
    public static final double DEFAULT_PLACEHOLDER = 0.5;
    /** Default Safety hit reference {@code H_ref} (config). */
    public static final double DEFAULT_SAFETY_H_REF = 10.0;
    /** Guards offset this many hits each in the Safety meter. */
    public static final double SAFETY_HITS_PER_GUARD = 3.0;
    /** Default lower bound of comfortable temperature range, C (Cold Sweat). */
    public static final double DEFAULT_COMFORT_MIN_C = 10.0;
    /** Default upper bound of comfortable temperature range, C (Cold Sweat). */
    public static final double DEFAULT_COMFORT_MAX_C = 35.0;
    /** Default C beyond the comfort range where Comfort reaches 0.0. */
    public static final double DEFAULT_COMFORT_DANGER_MARGIN_C = 15.0;
    /** Walkable indoor floor blocks per inhabitant that max out Shelter (Bright, 2026-11-21). */
    public static final double SHELTER_FLOOR_PER_OCCUPANT = 25.0;
    /** Distinct nearby people over 3 days that max out Community. */
    public static final double COMMUNITY_TARGET_PEOPLE = 10.0;
    /** Social interactions over 3 days that max out Socializing. */
    public static final double SOCIALIZING_TARGET_INTERACTIONS = 5.0;
    /** Active days lived in a settlement for full long-term Commitment (~7 real days). */
    public static final double COMMITMENT_FULL_DAYS = 504.0;
    /** Newcomer bonus added to Commitment on arrival, decaying linearly to 0 over {@link #NEWCOMER_DECAY_DAYS}. */
    public static final double NEWCOMER_BONUS_MAX = 0.5;
    public static final double NEWCOMER_DECAY_DAYS = 25.0;
    /** Liked meals over 3 days that max out the food half (0.5) of Enjoyment (2/day = 6 meals? Spec: / 12 -> 0.5 at 6 meals). */
    public static final double ENJOYMENT_MEALS_DIVISOR = 12.0;
    /** Default Fulfilment threshold T below which grievance builds and above which it drains. */
    public static final double DEFAULT_GRIEVANCE_THRESHOLD = 0.75;
    /** PROVISIONAL default grievance build multiplier per active day times shortfall. */
    public static final double DEFAULT_GRIEVANCE_BUILD_PER_DAY = 1.0;
    /** PROVISIONAL default grievance drain per active day while above threshold. */
    public static final double DEFAULT_GRIEVANCE_DRAIN_PER_DAY = 0.5;
    /** Default hard cap on empire civilian population (config). */
    public static final int DEFAULT_POPULATION_CAP = 50;

    /** Default Expectation E(n) curve (§6). */
    public static final List<ExpectationPoint> DEFAULT_EXPECTATION_CURVE = List.of(
            new ExpectationPoint(1, 0.20),
            new ExpectationPoint(5, 0.35),
            new ExpectationPoint(10, 0.50),
            new ExpectationPoint(15, 0.60),
            new ExpectationPoint(20, 0.70),
            new ExpectationPoint(25, 0.80),
            new ExpectationPoint(30, 0.90),
            new ExpectationPoint(35, 1.00),
            new ExpectationPoint(40, 1.10),
            new ExpectationPoint(45, 1.20),
            new ExpectationPoint(50, 1.30)
    );

    public enum Tier {
        SURVIVAL("Survival", 0.25),
        SECURITY("Security", 0.25),
        BELONGING("Belonging", 0.25),
        PURPOSE("Purpose", 0.25);

        private final String displayName;
        private final double weight;

        Tier(String displayName, double weight) {
            this.displayName = displayName;
            this.weight = weight;
        }

        public String displayName() {
            return displayName;
        }

        public double weight() {
            return weight;
        }
    }

    public enum Meter {
        HUNGER(Tier.SURVIVAL, "Hunger", 0.10),
        SLEEP(Tier.SURVIVAL, "Sleep", 0.10),
        HEALTH(Tier.SURVIVAL, "Health", 0.05),
        SAFETY(Tier.SECURITY, "Safety", 0.10),
        COMFORT(Tier.SECURITY, "Comfort", 0.05),
        SHELTER(Tier.SECURITY, "Shelter", 0.10),
        COMMUNITY(Tier.BELONGING, "Community", 0.10),
        SOCIALIZING(Tier.BELONGING, "Socializing", 0.05),
        COMMITMENT(Tier.BELONGING, "Commitment", 0.10),
        VOCATION(Tier.PURPOSE, "Vocation", 0.10),
        ENJOYMENT(Tier.PURPOSE, "Enjoyment", 0.15);

        private final Tier tier;
        private final String displayName;
        private final double weight;

        Meter(Tier tier, String displayName, double weight) {
            this.tier = tier;
            this.displayName = displayName;
            this.weight = weight;
        }

        public Tier tier() {
            return tier;
        }

        public String displayName() {
            return displayName;
        }

        public double weight() {
            return weight;
        }
    }

    /** Whether a meter reading comes from live data, partial placeholder data, or a pure placeholder. */
    public enum SourceKind {
        LIVE,
        PARTIAL,
        PLACEHOLDER
    }

    public record MeterReading(double value, SourceKind source, String detail) {
        public MeterReading {
            value = clamp01(value);
            Objects.requireNonNull(source);
            detail = detail != null ? detail : "";
        }

        public static MeterReading live(double value, String detail) {
            return new MeterReading(value, SourceKind.LIVE, detail);
        }

        public static MeterReading partial(double value, String detail) {
            return new MeterReading(value, SourceKind.PARTIAL, detail);
        }

        public static MeterReading placeholder(double value, String detail) {
            return new MeterReading(value, SourceKind.PLACEHOLDER, detail);
        }

        public boolean isPlaceholder() {
            return source == SourceKind.PLACEHOLDER;
        }

        public boolean isPartialPlaceholder() {
            return source == SourceKind.PARTIAL;
        }
    }

    public record ExpectationPoint(int population, double expectation) {
        public ExpectationPoint {
            if (population < 1) {
                throw new IllegalArgumentException("population must be >= 1: " + population);
            }
            if (!Double.isFinite(expectation) || expectation <= 0.0) {
                throw new IllegalArgumentException("expectation must be finite and > 0: " + expectation);
            }
        }
    }

    public record Evaluation(
            Map<Meter, MeterReading> meters,
            Map<Tier, Double> tierFractions,
            Map<Tier, Double> gates,
            Map<Tier, Double> effectiveTiers,
            double happiness,
            double expectation,
            double fulfilment,
            double productivity,
            Meter topShortfall,
            double topShortfallGain
    ) {
        public double meterValue(Meter meter) {
            return meters.get(meter).value();
        }
    }

    /** Evaluates Happiness, Fulfilment, Productivity, and the largest gated shortfall from meter readings. */
    public static Evaluation evaluate(Map<Meter, MeterReading> readings, double expectation, double placeholderDefault) {
        EnumMap<Meter, MeterReading> full = new EnumMap<>(Meter.class);
        double def = clamp01(placeholderDefault);
        for (Meter meter : Meter.values()) {
            MeterReading r = readings.get(meter);
            full.put(meter, r != null ? r : MeterReading.placeholder(def, "default"));
        }

        EnumMap<Tier, Double> tierFractions = new EnumMap<>(Tier.class);
        EnumMap<Tier, Double> gates = new EnumMap<>(Tier.class);
        EnumMap<Tier, Double> effectiveTiers = new EnumMap<>(Tier.class);
        double happiness = computeHappiness(full, tierFractions, gates, effectiveTiers);

        double safeE = Double.isFinite(expectation) && expectation > 0.0 ? expectation : 0.20;
        double fulfilment = happiness / safeE;
        double prod = productivity(fulfilment);

        // Identify the meter whose improvement to 1.0 would yield the largest Happiness gain (including downstream gates).
        Meter topShortfall = Meter.HUNGER;
        double bestGain = -1.0;
        EnumMap<Meter, MeterReading> scratch = new EnumMap<>(full);
        for (Meter meter : Meter.values()) {
            MeterReading orig = full.get(meter);
            if (orig.value() >= 1.0 - 1e-9) {
                continue;
            }
            scratch.put(meter, MeterReading.live(1.0, ""));
            double raised = computeHappiness(scratch, null, null, null);
            scratch.put(meter, orig);
            double gain = raised - happiness;
            if (gain > bestGain) {
                bestGain = gain;
                topShortfall = meter;
            }
        }
        if (bestGain < 0.0) {
            bestGain = 0.0;
        }

        return new Evaluation(
                Collections.unmodifiableMap(full),
                Collections.unmodifiableMap(tierFractions),
                Collections.unmodifiableMap(gates),
                Collections.unmodifiableMap(effectiveTiers),
                happiness,
                safeE,
                fulfilment,
                prod,
                topShortfall,
                bestGain
        );
    }

    private static double computeHappiness(Map<Meter, MeterReading> full,
                                           EnumMap<Tier, Double> tierFractionsOut,
                                           EnumMap<Tier, Double> gatesOut,
                                           EnumMap<Tier, Double> effectiveOut) {
        double gate = 1.0;
        double happiness = 0.0;
        for (Tier tier : Tier.values()) {
            double weightedSum = 0.0;
            for (Meter meter : Meter.values()) {
                if (meter.tier() == tier) {
                    weightedSum += full.get(meter).value() * meter.weight();
                }
            }
            double r = clamp01(weightedSum / tier.weight());
            double effective = r * gate * tier.weight();
            if (tierFractionsOut != null) tierFractionsOut.put(tier, r);
            if (gatesOut != null) gatesOut.put(tier, gate);
            if (effectiveOut != null) effectiveOut.put(tier, effective);
            happiness += effective;
            gate *= r;
        }
        return clamp01(happiness);
    }

    /** Linear 0.5x at Fulfilment 0 to 1.0x at Fulfilment 1.0, capped at 1.0x. */
    public static double productivity(double fulfilment) {
        return 0.5 + 0.5 * clamp01(fulfilment);
    }

    /** Survival / Hunger: only the lower half of the 0–20 food bar costs happiness. */
    public static double hunger(int foodLevel) {
        double fraction = clamp01(foodLevel / 20.0);
        return Math.min(1.0, fraction * 2.0);
    }

    /** Survival / Sleep: lastNight when finite, else placeholder default before the first night. */
    public static MeterReading sleep(float lastNight, double placeholderDefault) {
        if (Float.isNaN(lastNight)) {
            return MeterReading.placeholder(placeholderDefault, "no night yet");
        }
        double val = clamp01(lastNight);
        return MeterReading.live(val, String.format(java.util.Locale.ROOT, "lastNight %.2f", val));
    }

    /** Survival / Health: current health / max health. */
    public static double health(float currentHealth, float maxHealth) {
        if (!Float.isFinite(currentHealth) || !Float.isFinite(maxHealth) || maxHealth <= 0.0F) {
            return 0.0;
        }
        return clamp01(currentHealth / maxHealth);
    }

    /** Security / Safety: 1 - clamp((hits - 3 * guards) / H_ref, 0, 1). */
    public static double safety(int hitsLast3Days, int guards, double hRef) {
        if (!Double.isFinite(hRef) || hRef <= 0.0) {
            return 1.0;
        }
        double netHits = Math.max(0, hitsLast3Days) - SAFETY_HITS_PER_GUARD * Math.max(0, guards);
        return 1.0 - clamp01(netHits / hRef);
    }

    /** Security / Comfort: 1.0 inside [minC, maxC], falling linearly to 0.0 at dangerMarginC beyond either bound. */
    public static double comfortFromTemp(double tempC, double minC, double maxC, double dangerMarginC) {
        if (!Double.isFinite(tempC)) {
            return Double.NaN;
        }
        double lo = Math.min(minC, maxC);
        double hi = Math.max(minC, maxC);
        if (tempC >= lo && tempC <= hi) {
            return 1.0;
        }
        if (!Double.isFinite(dangerMarginC) || dangerMarginC <= 0.0) {
            return 0.0;
        }
        double distance = tempC < lo ? (lo - tempC) : (tempC - hi);
        return clamp01(1.0 - distance / dangerMarginC);
    }

    /**
     * Security / Shelter (Bright, 2026-11-21): 0 with no valid HOME; otherwise walkable indoor floor blocks divided by
     * (occupants * 25), capped at 1.0.
     */
    public static double shelter(boolean hasValidHome, int walkableIndoorFloor, int occupants) {
        return shelter(hasValidHome, walkableIndoorFloor, occupants, SHELTER_FLOOR_PER_OCCUPANT);
    }

    /** Security / Shelter with an individualized {@code floorPerOccupant} target. */
    public static double shelter(boolean hasValidHome, int walkableIndoorFloor, int occupants, double floorPerOccupant) {
        if (!hasValidHome || walkableIndoorFloor <= 0 || occupants <= 0) {
            return 0.0;
        }
        double perOcc = Double.isFinite(floorPerOccupant) && floorPerOccupant > 0.0
                ? floorPerOccupant : SHELTER_FLOOR_PER_OCCUPANT;
        return clamp01(walkableIndoorFloor / (occupants * perOcc));
    }

    /** Belonging / Community: distinct people within 16 blocks over 3 days / 10, capped at 1.0. */
    public static double community(int distinctPeople3Days) {
        return community(distinctPeople3Days, COMMUNITY_TARGET_PEOPLE);
    }

    /** Belonging / Community with an individualized {@code targetPeople} threshold. */
    public static double community(int distinctPeople3Days, double targetPeople) {
        double target = Double.isFinite(targetPeople) && targetPeople > 0.0
                ? targetPeople : COMMUNITY_TARGET_PEOPLE;
        return clamp01(Math.max(0, distinctPeople3Days) / target);
    }

    /** Belonging / Socializing: social interactions over 3 days / 5, capped at 1.0. */
    public static double socializing(int interactions3Days) {
        return socializing(interactions3Days, SOCIALIZING_TARGET_INTERACTIONS);
    }

    /** Belonging / Socializing with an individualized {@code targetInteractions} threshold. */
    public static double socializing(int interactions3Days, double targetInteractions) {
        double target = Double.isFinite(targetInteractions) && targetInteractions > 0.0
                ? targetInteractions : SOCIALIZING_TARGET_INTERACTIONS;
        return clamp01(Math.max(0, interactions3Days) / target);
    }

    /** Belonging / Commitment: long-term days/504 + newcomer bonus (+0.5 decaying linearly to 0 over 25 days), cap 1. */
    public static double commitment(double daysLived) {
        double d = Double.isFinite(daysLived) ? Math.max(0.0, daysLived) : 0.0;
        double longTerm = d / COMMITMENT_FULL_DAYS;
        double newcomer = newcomerBonus(d);
        return clamp01(longTerm + newcomer);
    }

    /** Newcomer commitment bonus at {@code daysLived} active settlement days. */
    public static double newcomerBonus(double daysLived) {
        double d = Double.isFinite(daysLived) ? Math.max(0.0, daysLived) : 0.0;
        if (d >= NEWCOMER_DECAY_DAYS) {
            return 0.0;
        }
        return NEWCOMER_BONUS_MAX * (1.0 - d / NEWCOMER_DECAY_DAYS);
    }

    /** Purpose / Enjoyment: liked meals over 3 days / 12 (max 0.5) + activity half (max 0.5). */
    public static double enjoymentFoodHalf(int likedMeals3Days) {
        return Math.min(0.5, Math.max(0, likedMeals3Days) / ENJOYMENT_MEALS_DIVISOR);
    }

    /** Purpose / Enjoyment reading with live food half and placeholder activity half (permanent 50% of 0.5 = 0.25). */
    public static MeterReading enjoyment(int likedMeals3Days, double placeholderDefault) {
        double foodHalf = enjoymentFoodHalf(likedMeals3Days);
        double activityHalf = 0.5 * clamp01(placeholderDefault);
        String detail = String.format(java.util.Locale.ROOT,
                "liked meals %d/6 (%.2f) + activity %.2f [placeholder]",
                Math.max(0, likedMeals3Days), foodHalf, activityHalf);
        return MeterReading.partial(foodHalf + activityHalf, detail);
    }

    /** Linearly interpolated Expectation E(n) from sorted (n, E) points. */
    public static double expectation(int citizenCount, List<ExpectationPoint> points) {
        List<ExpectationPoint> curve = (points == null || points.isEmpty()) ? DEFAULT_EXPECTATION_CURVE : points;
        int n = Math.max(1, citizenCount);
        if (n <= curve.get(0).population()) {
            return curve.get(0).expectation();
        }
        int last = curve.size() - 1;
        if (n >= curve.get(last).population()) {
            return curve.get(last).expectation();
        }
        for (int i = 0; i < last; i++) {
            ExpectationPoint a = curve.get(i);
            ExpectationPoint b = curve.get(i + 1);
            if (n >= a.population() && n <= b.population()) {
                if (a.population() == b.population()) {
                    return b.expectation();
                }
                double t = (double) (n - a.population()) / (double) (b.population() - a.population());
                return a.expectation() + t * (b.expectation() - a.expectation());
            }
        }
        return curve.get(last).expectation();
    }

    /** Parses config entries of the form {@code "n:E"} into a sorted curve, falling back to {@link #DEFAULT_EXPECTATION_CURVE}. */
    public static List<ExpectationPoint> parseExpectationCurve(List<? extends String> entries) {
        if (entries == null || entries.isEmpty()) {
            return DEFAULT_EXPECTATION_CURVE;
        }
        List<ExpectationPoint> parsed = new ArrayList<>();
        for (String entry : entries) {
            if (entry == null) {
                continue;
            }
            int sep = entry.indexOf(':');
            if (sep <= 0 || sep >= entry.length() - 1) {
                continue;
            }
            try {
                int pop = Integer.parseInt(entry.substring(0, sep).trim());
                double exp = Double.parseDouble(entry.substring(sep + 1).trim());
                if (pop >= 1 && Double.isFinite(exp) && exp > 0.0) {
                    parsed.add(new ExpectationPoint(pop, exp));
                }
            } catch (NumberFormatException ignored) {
                // Skip malformed entry.
            }
        }
        if (parsed.isEmpty()) {
            return DEFAULT_EXPECTATION_CURVE;
        }
        parsed.sort(Comparator.comparingInt(ExpectationPoint::population));
        return Collections.unmodifiableList(parsed);
    }

    /**
     * Advances grievance over {@code deltaTicks} active ticks. Paused whenever no empire member is online (§7).
     * Builds scaled by shortfall while {@code fulfilment < threshold}; drains while {@code fulfilment > threshold}.
     */
    public static double stepGrievance(double current, double fulfilment, double threshold,
                                       double buildPerDay, double drainPerDay,
                                       long deltaTicks, boolean memberOnline) {
        double g = clamp01(current);
        if (!memberOnline || deltaTicks <= 0L || !Double.isFinite(fulfilment)) {
            return g;
        }
        double days = deltaTicks / (double) CivilianFood.DAY_LENGTH;
        if (fulfilment < threshold) {
            double shortfall = threshold - fulfilment;
            return clamp01(g + shortfall * Math.max(0.0, buildPerDay) * days);
        }
        if (fulfilment > threshold) {
            return clamp01(g - Math.max(0.0, drainPerDay) * days);
        }
        return g;
    }

    public static double clamp01(double value) {
        if (!Double.isFinite(value) || value <= 0.0) {
            return 0.0;
        }
        return Math.min(1.0, value);
    }

    private FulfilmentModel() {
    }
}
