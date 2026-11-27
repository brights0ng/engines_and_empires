// Written by Gemini.
package dev.brights0ng.empire.rules;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Random;
import java.util.UUID;
import java.util.random.RandomGenerator;

/**
 * Pure occupational skill state, progression curve, action modifiers, rookie fumble model, starting-skill rolls, and
 * active-day decay for Empire civilians (Bright, 2026-11-27; zero Minecraft imports).
 *
 * <p>Skill levels live on {@code [0.0, 1.0]} backed by cumulative XP on a cubic curve ({@code XP(s) = MAX_XP * s^3}):
 * {@code 0.25} (Simple) in {@code 3} active workdays, {@code 0.50} (Competent) in {@code 24} days ({@code 8h}),
 * {@code 0.75} (Advanced) in {@code 81} days, and {@code 1.00} (Mastered) in {@code 192} days ({@code 2} MC years at
 * {@code 96} days/year). Per-action XP scales inversely with {@link #speedMultiplier(double)} alongside trait learning
 * rate, Fulfilment productivity, and sleep consolidation. Unused skills decay in XP-space at {@code 20%} of the
 * reference daily gain rate after {@link #DECAY_GRACE_DAYS} consecutive inactive active-days.
 */
public final class CivilianSkills {

    /** Named proficiency bands along {@code [0.0, 1.0]}. */
    public enum SkillTier {
        UNSKILLED("unskilled", "Unskilled", 0.00),
        SIMPLE("simple", "Simple", 0.25),
        COMPETENT("competent", "Competent", 0.50),
        ADVANCED("advanced", "Advanced", 0.75),
        MASTERED("mastered", "Mastered", 1.00);

        private final String id;
        private final String displayName;
        private final double minLevel;

        SkillTier(String id, String displayName, double minLevel) {
            this.id = id;
            this.displayName = displayName;
            this.minLevel = minLevel;
        }

        public String id() {
            return id;
        }

        public String displayName() {
            return displayName;
        }

        public double minLevel() {
            return minLevel;
        }

        public String langKey() {
            return "skill.empire.tier." + id;
        }

        public static SkillTier forLevel(double level) {
            double s = clamp01(level);
            if (s >= 1.0 - 1e-9) {
                return MASTERED;
            }
            if (s >= 0.75 - 1e-9) {
                return ADVANCED;
            }
            if (s >= 0.50 - 1e-9) {
                return COMPETENT;
            }
            if (s >= 0.25 - 1e-9) {
                return SIMPLE;
            }
            return UNSKILLED;
        }
    }

    /** Immutable per-skill snapshot for persistence and change detection. */
    public record Entry(double xp, int inactiveDays, boolean usedToday) {
        public Entry {
            xp = clampXp(xp);
            inactiveDays = Math.max(0, inactiveDays);
        }

        public static final Entry ZERO = new Entry(0.0, 0, false);

        public boolean isZero() {
            return xp <= 0.0 && inactiveDays == 0 && !usedToday;
        }
    }

    /** Immutable full-set snapshot stored in {@code CivilianRecord} and serialized via {@code CivilianSkillsNbt}. */
    public record Saved(Map<CivilianSkill, Entry> entries) {
        public static final Saved EMPTY = new Saved(Map.of());

        public Saved {
            if (entries == null || entries.isEmpty()) {
                entries = Map.of();
            } else {
                EnumMap<CivilianSkill, Entry> copy = new EnumMap<>(CivilianSkill.class);
                for (Map.Entry<CivilianSkill, Entry> e : entries.entrySet()) {
                    if (e.getKey() != null && e.getValue() != null && !e.getValue().isZero()) {
                        copy.put(e.getKey(), e.getValue());
                    }
                }
                entries = copy.isEmpty() ? Map.of() : Collections.unmodifiableMap(copy);
            }
        }

        public Entry entry(CivilianSkill skill) {
            return entries.getOrDefault(skill, Entry.ZERO);
        }

        public double level(CivilianSkill skill) {
            return levelForXp(entry(skill).xp());
        }
    }

    // ---- Confirmed curve & decay constants (Bright, 2026-11-27) ----

    /** Active workdays to reach Competent (0.50) at baseline rates (8 real hours). */
    public static final double DAYS_TO_COMPETENT = 24.0;
    /** Active workdays to reach Mastered (1.00) at baseline rates (2 MC years * 96 days/year = 64 real hours). */
    public static final double DAYS_TO_MASTERED = 192.0;
    /** Cubic XP exponent: 0.5^3 = 1/8 = 24/192 exactly. */
    public static final double CURVE_EXPONENT = 3.0;
    /** Reference XP earned in one active workday at baseline (1.0x) speed and modifiers. */
    public static final double REFERENCE_DAILY_XP = 100.0;
    /** Cumulative XP required to reach Mastery (1.00): 192 * 100 = 19,200 XP. */
    public static final double MAX_XP = DAYS_TO_MASTERED * REFERENCE_DAILY_XP;
    /** Decay rate as a fraction of the reference daily gain rate (20%). */
    public static final double DECAY_RATE_RATIO = 0.20;
    /** XP lost per inactive active-day after the grace period (20.0 XP/day). */
    public static final double DAILY_DECAY_XP = REFERENCE_DAILY_XP * DECAY_RATE_RATIO;
    /** Consecutive active-days without using a skill before XP decay begins. */
    public static final int DECAY_GRACE_DAYS = 3;

    // ---- Mechanical effect constants ----

    /** Skill level below which rookie mistakes/fumbles can occur (0.00 .. < 0.25). */
    public static final double ROOKIE_SKILL_CEILING = 0.25;
    /** PROVISIONAL: base fumble probability at skill 0.0 for a neutral, comfortable, rested worker. */
    public static final double BASE_ROOKIE_FUMBLE_CHANCE = 0.25;
    /** PROVISIONAL: upper safety cap on per-action fumble probability so clumsy/cold rookies still make progress. */
    public static final double MAX_FUMBLE_CHANCE = 0.50;
    /** PROVISIONAL: weight scaling cold/fatigue stress through {@code coldFatiguePrecisionSensitivity}. */
    public static final double COLD_FATIGUE_FUMBLE_WEIGHT = 0.50;

    // ---- Starting-skill roll bounds (travellers & migrated legacy records) ----

    public static final double START_SKILL_MIN_LEVEL = 0.05;
    public static final double START_SKILL_MAX_LEVEL = 0.50;

    // ---- PROVISIONAL: calibrated per-action base XP (at 1.0x reference speed & modifiers) ----

    public static final double HARVEST_CROP_BASE_XP = 1.0;
    public static final double PLANT_CROP_BASE_XP = 1.0;
    public static final double TILL_SOIL_BASE_XP = 2.0;
    public static final double TRANSFER_BASE_XP = 4.0;
    public static final double TRANSFER_XP_PER_STACK = 2.0;
    public static final double TRANSFER_MAX_XP = 20.0;
    public static final double EXCAVATE_BLOCK_BASE_XP = 1.0;
    public static final double CONSTRUCT_BLOCK_BASE_XP = 1.0;
    /** Fraction of base action XP awarded when a rookie fumbles a non-completing action (e.g. failed seed/till). */
    public static final double FUMBLE_ATTEMPT_XP_FACTOR = 0.5;

    private static final int SKILL_COUNT = CivilianSkill.values().length;

    private final double[] xp = new double[SKILL_COUNT];
    private final int[] inactiveDays = new int[SKILL_COUNT];
    private final boolean[] usedToday = new boolean[SKILL_COUNT];

    public CivilianSkills() {
    }

    // ---- Static pure math ----

    /** Converts cumulative XP on {@code [0, MAX_XP]} to skill level on {@code [0.0, 1.0]} via cubic root. */
    public static double levelForXp(double xp) {
        double clamped = clampXp(xp);
        if (clamped <= 0.0) {
            return 0.0;
        }
        if (clamped >= MAX_XP) {
            return 1.0;
        }
        return clamp01(Math.cbrt(clamped / MAX_XP));
    }

    /** Converts skill level on {@code [0.0, 1.0]} to cumulative XP on {@code [0, MAX_XP]} via cube. */
    public static double xpForLevel(double level) {
        double s = clamp01(level);
        if (s <= 0.0) {
            return 0.0;
        }
        if (s >= 1.0) {
            return MAX_XP;
        }
        return MAX_XP * s * s * s;
    }

    /** Action speed multiplier: {@code 0.5x} at {@code 0.0}, {@code 1.0x} at {@code 0.25}, ..., {@code 2.5x} at {@code 1.0}. */
    public static double speedMultiplier(double level) {
        return 0.5 + 2.0 * clamp01(level);
    }

    /** Work exhaustion multiplier: {@code 1.0x} at {@code 0.0} linearly down to {@code 0.5x} at {@code 1.0}. */
    public static double exhaustionMultiplier(double level) {
        return 1.0 - 0.5 * clamp01(level);
    }

    /** Tool durability consumption probability: {@code 1.0} at {@code 0.0} linearly down to {@code 0.25} at {@code 1.0}. */
    public static double toolDurabilityChance(double level) {
        return 1.0 - 0.75 * clamp01(level);
    }

    /** Harvested produce yield multiplier: {@code 1.0x} at {@code 0.0} linearly up to {@code 1.5x} at {@code 1.0}. */
    public static double cropYieldMultiplier(double level) {
        return 1.0 + 0.5 * clamp01(level);
    }

    /**
     * Rolls an integer drop count whose expectation equals {@code baseCount * cropYieldMultiplier(level)} (floor plus
     * probabilistic fractional remainder).
     */
    public static int rollScaledYield(int baseCount, double level, RandomGenerator rng) {
        if (baseCount <= 0) {
            return 0;
        }
        double expected = baseCount * cropYieldMultiplier(level);
        int guaranteed = (int) Math.floor(expected);
        double frac = expected - guaranteed;
        if (frac > 1e-9 && rng.nextDouble() < frac) {
            return guaranteed + 1;
        }
        return guaranteed;
    }

    /** Sleep consolidation factor on learning: {@code 1.0} before the first night, else {@code 0.5 .. 1.0}. */
    public static double sleepLearningMultiplier(float sleepLastNight) {
        if (Float.isNaN(sleepLastNight)) {
            return 1.0;
        }
        return 0.5 + 0.5 * clamp01(sleepLastNight);
    }

    /**
     * Time-efficiency XP scaling per action ({@code 1 / speedMultiplier(level)}): an Advanced worker at {@code 2.0x}
     * speed earns {@code 0.5x} XP per action relative to Simple ({@code 1.0x}), while the cubic curve simultaneously
     * demands {@code 9x} more XP per unit of skill at Advanced ({@code 0.75}) than at Simple ({@code 0.25}).
     */
    public static double timeEfficiencyXpScale(double currentLevel) {
        return 1.0 / speedMultiplier(currentLevel);
    }

    /** Computes the XP awarded by one completed action before clamping at {@link #MAX_XP}. */
    public static double computeActionXp(CivilianSkill skill, double baseActionXp, double currentLevel,
                                         CivilianDerivedStats stats, double productivity, float sleepLastNight) {
        Objects.requireNonNull(skill, "skill");
        Objects.requireNonNull(stats, "stats");
        if (!Double.isFinite(baseActionXp) || baseActionXp <= 0.0) {
            return 0.0;
        }
        double traitLearn = skill.learningMultiplier(stats);
        double prod = Double.isFinite(productivity) ? Math.max(0.5, Math.min(1.0, productivity)) : 1.0;
        double sleepMult = sleepLearningMultiplier(sleepLastNight);
        double effScale = timeEfficiencyXpScale(currentLevel);
        return Math.max(0.0, baseActionXp * traitLearn * prod * sleepMult * effScale);
    }

    /** Calibrated base XP for a Logistics container transfer moving {@code movedItems} items. */
    public static double transferBaseXp(int movedItems) {
        if (movedItems <= 0) {
            return 0.0;
        }
        return Math.min(TRANSFER_MAX_XP, TRANSFER_BASE_XP + TRANSFER_XP_PER_STACK * (movedItems / 64.0));
    }

    /**
     * Combined cold/fatigue/hunger precision stress in {@code [0.0, 1.0]} feeding {@code coldFatiguePrecisionSensitivity}.
     */
    public static double coldFatigueStress(double comfort, float sleepLastNight, double hungerStrengthFactor) {
        double comfortDeficit = Double.isFinite(comfort) ? (1.0 - clamp01(comfort)) : 0.0;
        double sleepDeficit = Float.isNaN(sleepLastNight) ? 0.0 : (1.0 - clamp01(sleepLastNight));
        double hungerDeficit = Double.isFinite(hungerStrengthFactor) ? (1.0 - clamp01(hungerStrengthFactor)) : 0.0;
        return clamp01(comfortDeficit + 0.5 * sleepDeficit + hungerDeficit);
    }

    /**
     * Rookie mistake probability on {@code [0.0, MAX_FUMBLE_CHANCE]}. Strictly {@code 0.0} at or above
     * {@link #ROOKIE_SKILL_CEILING} ({@code 0.25}), scaling linearly down from {@code 0.0} and multiplied by
     * {@link CivilianDerivedStats#fumbleRateMultiplier()} and cold/fatigue precision sensitivity.
     */
    public static double fumbleChance(double level, CivilianDerivedStats stats,
                                      double comfort, float sleepLastNight, double hungerStrengthFactor) {
        Objects.requireNonNull(stats, "stats");
        double s = clamp01(level);
        if (s >= ROOKIE_SKILL_CEILING) {
            return 0.0;
        }
        double rookieFactor = (ROOKIE_SKILL_CEILING - s) / ROOKIE_SKILL_CEILING;
        double stress = coldFatigueStress(comfort, sleepLastNight, hungerStrengthFactor);
        double coldFatigueMult = 1.0 + stress * COLD_FATIGUE_FUMBLE_WEIGHT * stats.coldFatiguePrecisionSensitivity();
        double raw = BASE_ROOKIE_FUMBLE_CHANCE * rookieFactor * stats.fumbleRateMultiplier() * coldFatigueMult;
        return Math.min(MAX_FUMBLE_CHANCE, Math.max(0.0, raw));
    }

    // ---- Instance accessors & mutators ----

    public double xp(CivilianSkill skill) {
        return xp[Objects.requireNonNull(skill, "skill").ordinal()];
    }

    public double level(CivilianSkill skill) {
        return levelForXp(xp(skill));
    }

    public SkillTier tier(CivilianSkill skill) {
        return SkillTier.forLevel(level(skill));
    }

    public int inactiveDays(CivilianSkill skill) {
        return inactiveDays[Objects.requireNonNull(skill, "skill").ordinal()];
    }

    public boolean usedToday(CivilianSkill skill) {
        return usedToday[Objects.requireNonNull(skill, "skill").ordinal()];
    }

    /** Directly sets a skill's level on {@code [0.0, 1.0]} and clears its inactivity timer (debug/test). */
    public void setLevel(CivilianSkill skill, double level) {
        int idx = Objects.requireNonNull(skill, "skill").ordinal();
        xp[idx] = xpForLevel(level);
        inactiveDays[idx] = 0;
        usedToday[idx] = false;
    }

    /** Directly sets every skill to the same level (test convenience). */
    public void setAllLevels(double level) {
        for (CivilianSkill skill : CivilianSkill.values()) {
            setLevel(skill, level);
        }
    }

    /** Directly adds raw XP (positive or negative) to a skill, clamping to {@code [0, MAX_XP]}. */
    public double addRawXp(CivilianSkill skill, double deltaXp) {
        int idx = Objects.requireNonNull(skill, "skill").ordinal();
        if (!Double.isFinite(deltaXp) || deltaXp == 0.0) {
            return 0.0;
        }
        double before = xp[idx];
        double after = clampXp(before + deltaXp);
        xp[idx] = after;
        if (deltaXp > 0.0) {
            inactiveDays[idx] = 0;
            usedToday[idx] = true;
        } else if (after <= 0.0) {
            inactiveDays[idx] = 0;
            usedToday[idx] = false;
        }
        return after - before;
    }

    /**
     * Records a completed action in {@code skill}, computing modified XP, clamping to {@link #MAX_XP}, marking the
     * skill practiced today, and resetting its inactivity counter. Returns the XP actually gained.
     */
    public double recordAction(CivilianSkill skill, double baseActionXp,
                               CivilianDerivedStats stats, double productivity, float sleepLastNight) {
        int idx = Objects.requireNonNull(skill, "skill").ordinal();
        double currentLevel = levelForXp(xp[idx]);
        double computed = computeActionXp(skill, baseActionXp, currentLevel, stats, productivity, sleepLastNight);
        inactiveDays[idx] = 0;
        usedToday[idx] = true;
        if (computed <= 0.0) {
            return 0.0;
        }
        double before = xp[idx];
        double after = clampXp(before + computed);
        xp[idx] = after;
        return after - before;
    }

    /** Resets every skill to 0 XP and 0 inactive days. */
    public void clearAll() {
        for (int i = 0; i < SKILL_COUNT; i++) {
            xp[i] = 0.0;
            inactiveDays[i] = 0;
            usedToday[i] = false;
        }
    }

    /**
     * Advances one active civilian day: skills used today reset their inactivity streak; unused positive skills
     * increment {@code inactiveDays} and lose {@link #DAILY_DECAY_XP} once {@code inactiveDays > DECAY_GRACE_DAYS}.
     * Returns true if any skill state changed.
     */
    public boolean advanceDay() {
        boolean changed = false;
        for (int i = 0; i < SKILL_COUNT; i++) {
            if (usedToday[i]) {
                usedToday[i] = false;
                if (inactiveDays[i] != 0) {
                    inactiveDays[i] = 0;
                }
                changed = true;
            } else if (xp[i] > 0.0) {
                inactiveDays[i]++;
                if (inactiveDays[i] > DECAY_GRACE_DAYS) {
                    xp[i] = Math.max(0.0, xp[i] - DAILY_DECAY_XP);
                    if (xp[i] <= 0.0) {
                        inactiveDays[i] = 0;
                    }
                }
                changed = true;
            }
        }
        return changed;
    }

    /** Advances {@code days} active civilian days (debug/testing). */
    public boolean advanceDays(int days) {
        boolean any = false;
        for (int i = 0; i < days; i++) {
            any |= advanceDay();
        }
        return any;
    }

    /** Returns all skills with positive XP, sorted highest level first (ties broken by declaration order). */
    public List<CivilianSkill> nonZeroSkillsSorted() {
        List<CivilianSkill> list = new ArrayList<>();
        for (CivilianSkill skill : CivilianSkill.values()) {
            if (xp(skill) > 0.0) {
                list.add(skill);
            }
        }
        list.sort(Comparator.<CivilianSkill>comparingDouble(this::xp).reversed().thenComparingInt(Enum::ordinal));
        return list;
    }

    /** Compact single-line summary for sneak-click / {@code info}, e.g. {@code Harvesting: Simple (0.31), Logistics: Unskilled (0.14)}. */
    public String formatCompactSummary() {
        List<CivilianSkill> active = nonZeroSkillsSorted();
        if (active.isEmpty()) {
            return "none";
        }
        List<String> parts = new ArrayList<>(active.size());
        for (CivilianSkill skill : active) {
            double lvl = level(skill);
            parts.add(String.format(Locale.ROOT, "%s: %s (%.2f)",
                    skill.displayName(), SkillTier.forLevel(lvl).displayName(), lvl));
        }
        return String.join(", ", parts);
    }

    // ---- Starting-skill rolling (travellers & migrated records) ----

    /**
     * Rolls 1–3 trait-aligned starting skills in {@code [START_SKILL_MIN_LEVEL, START_SKILL_MAX_LEVEL]} (most commonly
     * 2 skills, biased toward skills with higher {@link CivilianSkill#startingAffinity(CivilianTraits)}).
     */
    public static CivilianSkills rollStarting(RandomGenerator rng, CivilianTraits traits) {
        Objects.requireNonNull(rng, "rng");
        Objects.requireNonNull(traits, "traits");

        int countRoll = rng.nextInt(100);
        int count = countRoll < 25 ? 1 : (countRoll < 80 ? 2 : 3);
        count = Math.min(count, SKILL_COUNT);

        List<CivilianSkill> pool = new ArrayList<>(List.of(CivilianSkill.values()));
        CivilianSkills skills = new CivilianSkills();

        for (int pick = 0; pick < count && !pool.isEmpty(); pick++) {
            double totalWeight = 0.0;
            double[] weights = new double[pool.size()];
            for (int i = 0; i < pool.size(); i++) {
                double aff = pool.get(i).startingAffinity(traits);
                double w = Math.pow(Math.max(0.15, 1.0 + aff), 2.5);
                weights[i] = w;
                totalWeight += w;
            }
            double r = rng.nextDouble() * totalWeight;
            int chosenIdx = pool.size() - 1;
            double acc = 0.0;
            for (int i = 0; i < pool.size(); i++) {
                acc += weights[i];
                if (r < acc) {
                    chosenIdx = i;
                    break;
                }
            }
            CivilianSkill chosen = pool.remove(chosenIdx);
            double aff = chosen.startingAffinity(traits);
            // Triangular base roll shifted gently by trait affinity, clamped to [0.05, 0.50].
            double u = 0.5 * (rng.nextDouble() + rng.nextDouble());
            double frac = clamp01(0.10 + 0.70 * u + 0.25 * aff);
            double level = START_SKILL_MIN_LEVEL + (START_SKILL_MAX_LEVEL - START_SKILL_MIN_LEVEL) * frac;
            skills.setLevel(chosen, Math.min(START_SKILL_MAX_LEVEL, Math.max(START_SKILL_MIN_LEVEL, level)));
        }
        return skills;
    }

    /** Deterministic trait-aligned starting skills seeded from a civilian UUID (for migrating legacy v1–v4 saves). */
    public static CivilianSkills defaultFor(UUID id, CivilianTraits traits) {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(traits, "traits");
        long seed = id.getMostSignificantBits() ^ (id.getLeastSignificantBits() * 31L) ^ 0x5C11_5EEDL;
        return rollStarting(new Random(seed), traits);
    }

    // ---- Persistence helpers ----

    public Saved save() {
        EnumMap<CivilianSkill, Entry> map = new EnumMap<>(CivilianSkill.class);
        for (CivilianSkill skill : CivilianSkill.values()) {
            int i = skill.ordinal();
            Entry entry = new Entry(xp[i], inactiveDays[i], usedToday[i]);
            if (!entry.isZero()) {
                map.put(skill, entry);
            }
        }
        return new Saved(map);
    }

    public static CivilianSkills restore(Saved saved) {
        CivilianSkills skills = new CivilianSkills();
        if (saved != null) {
            for (Map.Entry<CivilianSkill, Entry> e : saved.entries().entrySet()) {
                if (e.getKey() != null && e.getValue() != null) {
                    int idx = e.getKey().ordinal();
                    skills.xp[idx] = e.getValue().xp();
                    skills.inactiveDays[idx] = e.getValue().inactiveDays();
                    skills.usedToday[idx] = e.getValue().usedToday();
                }
            }
        }
        return skills;
    }

    public CivilianSkills copy() {
        return restore(save());
    }

    private static double clampXp(double value) {
        if (!Double.isFinite(value) || value <= 0.0) {
            return 0.0;
        }
        return Math.min(MAX_XP, value);
    }

    private static double clamp01(double value) {
        if (!Double.isFinite(value) || value <= 0.0) {
            return 0.0;
        }
        return Math.min(1.0, value);
    }
}
