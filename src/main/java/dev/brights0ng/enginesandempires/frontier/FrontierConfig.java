package dev.brights0ng.enginesandempires.frontier;

import dev.brights0ng.enginesandempires.frontier.tier.TierParams;
import net.neoforged.neoforge.common.ModConfigSpec;

/**
 * Frontier's settings. A SERVER config, so each world has its own copy ({@code serverconfig/engines_and_empires-server.toml}
 * in the world folder), and a server's values are what count for everyone on it.
 *
 * <p>Code reads them through {@link #params()}, which falls back to {@link TierParams#DEFAULTS} before the config has loaded.
 */
public final class FrontierConfig {

    private static final ModConfigSpec.Builder BUILDER = new ModConfigSpec.Builder();

    static {
        BUILDER.comment("How civilised each part of the Overworld is. Times are in game ticks (24000 = one day).",
                "Distances are in subchunks (16x16x16), measured as a cube.").push("tiers");
    }

    private static final TierParams D = TierParams.DEFAULTS;

    public static final ModConfigSpec.IntValue SETTLE_TICKS = BUILDER
            .comment("Inhabited time an area needs before it can be Settled (it also needs an anchor block).")
            .defineInRange("settleTicks", D.settleTicks(), 0, Integer.MAX_VALUE);

    public static final ModConfigSpec.LongValue GRACE_TICKS = BUILDER
            .comment("How long an area keeps its inhabited time with nobody there before it starts to fade.")
            .defineInRange("graceTicks", D.graceTicks(), 0L, Long.MAX_VALUE);

    public static final ModConfigSpec.DoubleValue DECAY_PER_TICK = BUILDER
            .comment("Inhabited ticks lost per tick once the grace is over. 1 = a day's worth lost per day.")
            .defineInRange("decayPerTick", D.decayPerTick(), 0.0, 1000.0);

    public static final ModConfigSpec.IntValue HABITATION_CAP = BUILDER
            .comment("Most inhabited time a single subchunk can bank, so a long-lived base still cools off in a few days.")
            .defineInRange("habitationCap", D.habitationCap(), 0, Integer.MAX_VALUE);

    public static final ModConfigSpec.IntValue AREA_RADIUS = BUILDER
            .comment("How far the Settled and Civilized checks look around a subchunk.")
            .defineInRange("areaRadius", D.areaRadius(), 0, 8);

    public static final ModConfigSpec.IntValue RING_RADIUS = BUILDER
            .comment("Land this close to Settled land is Uninhabited rather than Frontier.")
            .defineInRange("ringRadius", D.ringRadius(), 0, 32);

    public static final ModConfigSpec.IntValue NEAR_RADIUS = BUILDER
            .comment("Land this close to Settled land builds up inhabited time faster.")
            .defineInRange("nearRadius", D.nearRadius(), 0, 32);

    public static final ModConfigSpec.IntValue NEAR_MULTIPLIER = BUILDER
            .comment("How much faster inhabited time builds up next to Settled land.")
            .defineInRange("nearMultiplier", D.nearMultiplier(), 1, 100);

    public static final ModConfigSpec.IntValue MIN_ANCHORS = BUILDER
            .comment("Anchor blocks (tag engines_and_empires:settles) an area needs to be Settled. A bed counts twice.")
            .defineInRange("minAnchors", D.minAnchors(), 0, 1000);

    public static final ModConfigSpec.IntValue CIVILIZED_GUARDS = BUILDER
            .comment("Free-roaming guards an area needs to be Civilized.")
            .defineInRange("civilizedGuards", D.civilizedGuards(), 1, 1000);

    public static final ModConfigSpec.IntValue FRONTIER_BELOW_Y = BUILDER
            .comment("Everything below this height is Frontier.")
            .defineInRange("frontierBelowY", D.frontierBelowY(), -2048, 2048);

    public static final ModConfigSpec.IntValue UNINHABITED_BELOW_Y = BUILDER
            .comment("Everything below this height is at best Uninhabited.")
            .defineInRange("uninhabitedBelowY", D.uninhabitedBelowY(), -2048, 2048);

    static {
        BUILDER.pop().push("spawning");
    }

    public static final ModConfigSpec.IntValue FRONTIER_SPAWN_INTERVAL = BUILDER
            .comment("Frontier's extra spawner runs this often, in ticks, for each player standing near Frontier land.")
            .defineInRange("frontierSpawnInterval", 20, 1, 12000);

    public static final ModConfigSpec.IntValue FRONTIER_SPAWN_ATTEMPTS = BUILDER
            .comment("Packs the extra spawner tries to place around each player each time it runs.")
            .defineInRange("frontierSpawnAttempts", 2, 0, 64);

    public static final ModConfigSpec.IntValue FRONTIER_SPAWN_CAP = BUILDER
            .comment("Most mobs the extra spawner keeps around one player, on top of vanilla's own. About 35 makes Frontier roughly twice as busy.")
            .defineInRange("frontierSpawnCap", 35, 0, 1000);

    public static final ModConfigSpec.IntValue FRONTIER_SPAWN_MIN_DISTANCE = BUILDER
            .comment("Closest to a player the extra spawner places a mob (vanilla: 24).")
            .defineInRange("frontierSpawnMinDistance", 16, 1, 128);

    public static final ModConfigSpec.IntValue FRONTIER_SPAWN_MAX_DISTANCE = BUILDER
            .comment("Furthest from a player the extra spawner places a mob (vanilla: 128).")
            .defineInRange("frontierSpawnMaxDistance", 40, 2, 128);

    public static final ModConfigSpec.DoubleValue SETTLED_SURFACE_MONSTERS = BUILDER
            .comment("Share of the usual night-time monsters that still spawn on the surface of Settled land.")
            .defineInRange("settledSurfaceMonsters", 0.5, 0.0, 1.0);

    public static final ModConfigSpec.BooleanValue CIVILIZED_REGENERATION = BUILDER
            .comment("Whether players in Civilized land have Regeneration I.")
            .define("civilizedRegeneration", true);

    static {
        BUILDER.pop().push("guards");
    }

    public static final ModConfigSpec.IntValue GUARD_FREE_AREA = BUILDER
            .comment("Spots of ground a guard must be able to walk to, to count as free to move (64 is about an 8x8 yard).",
                    "Guards (tag engines_and_empires:guards, and MineColonies guards) only count toward Civilized land if free.")
            .defineInRange("guardFreeArea", 64, 1, 4096);

    public static final ModConfigSpec.IntValue GUARD_CHECK_INTERVAL = BUILDER
            .comment("How often, in ticks, each guard is checked again for being free to move.")
            .defineInRange("guardCheckInterval", 2400, 20, Integer.MAX_VALUE);

    static {
        BUILDER.pop().push("deep");
    }

    public static final ModConfigSpec.DoubleValue SCULK_RANGE_FRACTION = BUILDER
            .comment("Share of a thumper shot's range within which it sets off sculk sensors, shriekers and Wardens.")
            .defineInRange("sculkRangeFraction", 0.5, 0.0, 1.0);

    public static final ModConfigSpec.DoubleValue MECHANICAL_MOB_CHANCE = BUILDER
            .comment("Chance that a mechanical thumper shot brings 1-3 mobs of the 'thumper' roster up out of the ground.")
            .defineInRange("mechanicalMobChance", 0.15, 0.0, 1.0);

    public static final ModConfigSpec.DoubleValue COMBUSTIVE_MOB_CHANCE = BUILDER
            .comment("Chance that a combustive thumper shot (not a dry fire) brings 1-3 mobs of the 'thumper' roster up.")
            .defineInRange("combustiveMobChance", 0.30, 0.0, 1.0);

    public static final ModConfigSpec.IntValue SHOT_MOB_MIN_DISTANCE = BUILDER
            .comment("Closest to the thumper that a shot's mobs come up.")
            .defineInRange("shotMobMinDistance", 16, 0, 256);

    public static final ModConfigSpec.IntValue SHOT_MOB_MAX_DISTANCE = BUILDER
            .comment("Furthest from the thumper that a shot's mobs come up.")
            .defineInRange("shotMobMaxDistance", 32, 1, 256);

    public static final ModConfigSpec.IntValue STIR_TICKS = BUILDER
            .comment("How long a deposit stays stirred after a thumper shot's echo off it (mobs of the 'stirred' roster come up near it).")
            .defineInRange("stirTicks", TierParams.DAY * 3, 0, Integer.MAX_VALUE);

    public static final ModConfigSpec.IntValue DISTURBANCE_SHOTS = BUILDER
            .comment("Thumper shots (dry fires don't count) within the 3x3 chunks around a thumper, inside the window, that roll for an incursion.")
            .defineInRange("disturbanceShots", 5, 1, 1000);

    public static final ModConfigSpec.IntValue DISTURBANCE_WINDOW = BUILDER
            .comment("The window, in ticks, those shots are counted over.")
            .defineInRange("disturbanceWindow", 1200, 20, Integer.MAX_VALUE);

    public static final ModConfigSpec.IntValue DISTURBANCE_COOLDOWN = BUILDER
            .comment("After an incursion is called up, the 3x3 chunks around can't call another for this many ticks.")
            .defineInRange("disturbanceCooldown", 36000, 0, Integer.MAX_VALUE);

    public static final ModConfigSpec.DoubleValue MECHANICAL_INCURSION_CHANCE = BUILDER
            .comment("Chance that reaching the shot count calls up a (lesser) incursion, when the shots were all mechanical.",
                    "Mixed shots fall in between this and the combustive chance, by the share of combustive shots.")
            .defineInRange("mechanicalIncursionChance", 0.10, 0.0, 1.0);

    public static final ModConfigSpec.DoubleValue COMBUSTIVE_INCURSION_CHANCE = BUILDER
            .comment("Chance that reaching the shot count calls up a (lesser) incursion, when the shots were all combustive.")
            .defineInRange("combustiveIncursionChance", 0.25, 0.0, 1.0);

    static {
        BUILDER.pop().push("incursions");
    }

    public static final ModConfigSpec.DoubleValue SETTLED_MIN_CHANCE = BUILDER
            .comment("Nightly chance of a (lesser) incursion for Settled land with a weighted score of 0.",
                    "Rolled at nightfall, only for settlements with a player inside.")
            .defineInRange("settledMinChance", 0.02, 0.0, 1.0);

    public static final ModConfigSpec.DoubleValue SETTLED_MAX_CHANCE = BUILDER
            .comment("Nightly chance for Settled land at the full score (maxScore).")
            .defineInRange("settledMaxChance", 0.06, 0.0, 1.0);

    public static final ModConfigSpec.DoubleValue CIVILIZED_MIN_CHANCE = BUILDER
            .comment("Nightly chance of an incursion for Civilized land with a weighted score of 0.")
            .defineInRange("civilizedMinChance", 0.03, 0.0, 1.0);

    public static final ModConfigSpec.DoubleValue CIVILIZED_MAX_CHANCE = BUILDER
            .comment("Nightly chance for Civilized land at the full score.")
            .defineInRange("civilizedMaxChance", 0.10, 0.0, 1.0);

    public static final ModConfigSpec.DoubleValue GREATER_SHARE = BUILDER
            .comment("Share of a Civilized settlement's incursions that are greater ones.")
            .defineInRange("greaterShare", 0.20, 0.0, 1.0);

    public static final ModConfigSpec.DoubleValue MAX_SCORE = BUILDER
            .comment("Weighted score at which the chance tops out. Players 2 each; villagers, pillagers and colonists 0.25 each;",
                    "thumper shots in the last day range/128 each; loud machines that ran in the last day their weight",
                    "(data map engines_and_empires:loud_machines).")
            .defineInRange("maxScore", 25.0, 0.001, 100_000.0);

    public static final ModConfigSpec.DoubleValue PLAYER_WEIGHT = BUILDER
            .comment("Score for each player inside the settlement.")
            .defineInRange("playerWeight", 2.0, 0.0, 1000.0);

    public static final ModConfigSpec.DoubleValue INHABITANT_WEIGHT = BUILDER
            .comment("Score for each villager, pillager or colonist (tag engines_and_empires:inhabitants) inside the settlement.")
            .defineInRange("inhabitantWeight", 0.25, 0.0, 1000.0);

    public static final ModConfigSpec.IntValue IMMUNITY_TICKS = BUILDER
            .comment("After an incursion, a settlement can't have another nightly one for this long (240000 = 10 days).")
            .defineInRange("immunityTicks", TierParams.DAY * 10, 0, Integer.MAX_VALUE);

    public static final ModConfigSpec.IntValue HERO_LESSER_TICKS = BUILDER
            .comment("Hero of the Village for players inside when a lesser incursion is beaten (1200 = a minute).")
            .defineInRange("heroLesserTicks", 1200, 0, Integer.MAX_VALUE);

    public static final ModConfigSpec.IntValue HERO_GREATER_TICKS = BUILDER
            .comment("Hero of the Village for players inside when a greater incursion is beaten (144000 = two hours).")
            .defineInRange("heroGreaterTicks", 144000, 0, Integer.MAX_VALUE);

    public static final ModConfigSpec.IntValue WAVE_DELAY = BUILDER
            .comment("Ticks between one wave being beaten and the next coming up (and before the first).")
            .defineInRange("waveDelay", 200, 20, Integer.MAX_VALUE);

    public static final ModConfigSpec.IntValue CHOKE_TICKS = BUILDER
            .comment("How long an incursion mob must stay within 2 blocks of a thumper to choke it.")
            .defineInRange("chokeTicks", 100, 1, Integer.MAX_VALUE);

    public static final ModConfigSpec.IntValue THUMPER_TIMEOUT = BUILDER
            .comment("A thumper incursion that has not been beaten by now retreats (12000 = 10 minutes).")
            .defineInRange("thumperTimeout", 12000, 20, Integer.MAX_VALUE);

    public static final ModConfigSpec.IntValue THUMPER_DEFEAT_DELAY = BUILDER
            .comment("Once every thumper it came for is choked, a thumper incursion ends in defeat after this long (2400 = 2 minutes).")
            .defineInRange("thumperDefeatDelay", 2400, 0, Integer.MAX_VALUE);

    public static final ModConfigSpec.IntValue THUMPER_WAVE_MIN_DISTANCE = BUILDER
            .comment("Closest to the thumper that a thumper incursion's waves come up.")
            .defineInRange("thumperWaveMinDistance", 24, 0, 256);

    public static final ModConfigSpec.IntValue THUMPER_WAVE_MAX_DISTANCE = BUILDER
            .comment("Furthest from the thumper that a thumper incursion's waves come up.")
            .defineInRange("thumperWaveMaxDistance", 40, 1, 256);

    static {
        BUILDER.pop().push("torches");
    }

    public static final ModConfigSpec.IntValue TORCH_BURN_TICKS = BUILDER
            .comment("How long a torch burns outside Settled land before it goes out (tag engines_and_empires:burns_out).",
                    "In Settled land torches are looked after and never go out; the clock starts when the land stops being Settled.")
            .defineInRange("torchBurnTicks", TierParams.DAY, 20, Integer.MAX_VALUE);

    public static final ModConfigSpec.IntValue TORCH_SWEEP_INTERVAL = BUILDER
            .comment("How often, in ticks, torches are checked.")
            .defineInRange("torchSweepInterval", 100, 1, 12000);

    static {
        BUILDER.pop().push("performance");
    }

    public static final ModConfigSpec.IntValue RECOMPUTE_BUDGET = BUILDER
            .comment("Most subchunk tiers worked out again per tick after something changed.")
            .defineInRange("recomputeBudget", 64, 1, 100_000);

    public static final ModConfigSpec.IntValue SWEEP_PERIOD_TICKS = BUILDER
            .comment("Every loaded subchunk's tier is checked again once in this many ticks, which is how fading is noticed.")
            .defineInRange("sweepPeriodTicks", 6000, 20, Integer.MAX_VALUE);

    static {
        BUILDER.pop();
    }

    public static final ModConfigSpec SPEC = BUILDER.build();

    /** The tier numbers now in force. */
    public static TierParams params() {
        if (!SPEC.isLoaded()) {
            return TierParams.DEFAULTS;
        }
        return new TierParams(SETTLE_TICKS.get(), GRACE_TICKS.get(), DECAY_PER_TICK.get(), HABITATION_CAP.get(),
                AREA_RADIUS.get(), RING_RADIUS.get(), NEAR_RADIUS.get(), NEAR_MULTIPLIER.get(), MIN_ANCHORS.get(),
                CIVILIZED_GUARDS.get(), FRONTIER_BELOW_Y.get(), UNINHABITED_BELOW_Y.get());
    }

    public static int recomputeBudget() {
        return SPEC.isLoaded() ? RECOMPUTE_BUDGET.get() : 64;
    }

    public static int sweepPeriodTicks() {
        return SPEC.isLoaded() ? SWEEP_PERIOD_TICKS.get() : 6000;
    }

    /** The extra Frontier spawner's numbers now in force. */
    public static SpawnParams spawn() {
        if (!SPEC.isLoaded()) {
            return SpawnParams.DEFAULTS;
        }
        int min = FRONTIER_SPAWN_MIN_DISTANCE.get();
        return new SpawnParams(FRONTIER_SPAWN_INTERVAL.get(), FRONTIER_SPAWN_ATTEMPTS.get(), FRONTIER_SPAWN_CAP.get(),
                min, Math.max(min + 1, FRONTIER_SPAWN_MAX_DISTANCE.get()), SETTLED_SURFACE_MONSTERS.get(),
                CIVILIZED_REGENERATION.get());
    }

    /** The extra Frontier spawner's numbers, and the other spawning rules'. */
    public record SpawnParams(int interval, int attempts, int cap, int minDistance, int maxDistance,
                              double settledSurfaceMonsters, boolean civilizedRegeneration) {
        public static final SpawnParams DEFAULTS = new SpawnParams(20, 2, 35, 16, 40, 0.5, true);
    }

    public static int torchBurnTicks() {
        return SPEC.isLoaded() ? TORCH_BURN_TICKS.get() : TierParams.DAY;
    }

    public static int torchSweepInterval() {
        return SPEC.isLoaded() ? TORCH_SWEEP_INTERVAL.get() : 100;
    }

    public static int guardFreeArea() {
        return SPEC.isLoaded() ? GUARD_FREE_AREA.get() : 64;
    }

    public static int guardCheckInterval() {
        return SPEC.isLoaded() ? GUARD_CHECK_INTERVAL.get() : 2400;
    }

    /** How close to and far from a thumper a shot's mobs come up: {min, max}. */
    public static int[] shotMobDistance() {
        int min = SPEC.isLoaded() ? SHOT_MOB_MIN_DISTANCE.get() : 16;
        int max = SPEC.isLoaded() ? SHOT_MOB_MAX_DISTANCE.get() : 32;
        return new int[]{min, Math.max(min + 1, max)};
    }

    /** How close to and far from a thumper a thumper incursion's waves come up: {min, max}. */
    public static int[] thumperWaveDistance() {
        int min = SPEC.isLoaded() ? THUMPER_WAVE_MIN_DISTANCE.get() : 24;
        int max = SPEC.isLoaded() ? THUMPER_WAVE_MAX_DISTANCE.get() : 40;
        return new int[]{min, Math.max(min + 1, max)};
    }

    /** The thumper consequences' numbers now in force. */
    public static DeepParams deep() {
        if (!SPEC.isLoaded()) {
            return DeepParams.DEFAULTS;
        }
        return new DeepParams(SCULK_RANGE_FRACTION.get(), MECHANICAL_MOB_CHANCE.get(), COMBUSTIVE_MOB_CHANCE.get(),
                STIR_TICKS.get(), DISTURBANCE_SHOTS.get(), DISTURBANCE_WINDOW.get(), DISTURBANCE_COOLDOWN.get(),
                MECHANICAL_INCURSION_CHANCE.get(), COMBUSTIVE_INCURSION_CHANCE.get());
    }

    /** What a thumper shot sets off. Bright's choices (2026-09-26): half range, 15% / 30%, 3 days, 5 shots, 30 min, 10% / 25%. */
    public record DeepParams(double sculkRangeFraction, double mechanicalMobChance, double combustiveMobChance,
                             int stirTicks, int disturbanceShots, int disturbanceWindow, int disturbanceCooldown,
                             double mechanicalIncursionChance, double combustiveIncursionChance) {
        public static final DeepParams DEFAULTS = new DeepParams(0.5, 0.15, 0.30, TierParams.DAY * 3, 5, 1200, 36000, 0.10, 0.25);
    }

    /** The incursions' numbers now in force. */
    public static IncursionParams incursions() {
        if (!SPEC.isLoaded()) {
            return IncursionParams.DEFAULTS;
        }
        return new IncursionParams(SETTLED_MIN_CHANCE.get(), SETTLED_MAX_CHANCE.get(), CIVILIZED_MIN_CHANCE.get(),
                CIVILIZED_MAX_CHANCE.get(), GREATER_SHARE.get(), MAX_SCORE.get(), PLAYER_WEIGHT.get(),
                INHABITANT_WEIGHT.get(), IMMUNITY_TICKS.get(), HERO_LESSER_TICKS.get(), HERO_GREATER_TICKS.get(),
                WAVE_DELAY.get(), CHOKE_TICKS.get(), THUMPER_TIMEOUT.get(), THUMPER_DEFEAT_DELAY.get());
    }

    /** Incursions. Bright's choices (2026-09-26). */
    public record IncursionParams(double settledMinChance, double settledMaxChance, double civilizedMinChance,
                                  double civilizedMaxChance, double greaterShare, double maxScore, double playerWeight,
                                  double inhabitantWeight, int immunityTicks, int heroLesserTicks, int heroGreaterTicks,
                                  int waveDelay, int chokeTicks, int thumperTimeout, int thumperDefeatDelay) {
        public static final IncursionParams DEFAULTS = new IncursionParams(0.02, 0.06, 0.03, 0.10, 0.20, 25.0, 2.0, 0.25,
                TierParams.DAY * 10, 1200, 144000, 200, 100, 12000, 2400);
    }

    private FrontierConfig() {
    }
}
