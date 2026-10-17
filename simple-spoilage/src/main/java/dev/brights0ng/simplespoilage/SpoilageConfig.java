package dev.brights0ng.simplespoilage;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import net.neoforged.neoforge.common.ModConfigSpec;

/**
 * Food spoilage settings. A SERVER config ({@code serverconfig/simple_spoilage-spoilage-server.toml} in the world folder),
 * which the server sends to its players, so tooltips agree with the server.
 *
 * <p>Code reads them through {@link #times()}, which falls back to {@link SpoilTimes#DEFAULTS} before the config has loaded.
 */
public final class SpoilageConfig {

    public static final String FILE_NAME = "simple_spoilage-spoilage-server.toml";

    private static final ModConfigSpec.Builder BUILDER = new ModConfigSpec.Builder();

    static {
        BUILDER.comment("How long food spends in each freshness stage, in in-game days (1 day = 24000 ticks = 20 minutes).",
                "Food is rotting once it has been through fresh, ripe and stale.").push("stages");
    }

    private static final SpoilTimes D = SpoilTimes.DEFAULTS;

    public static final ModConfigSpec.DoubleValue FRESH_DAYS = BUILDER
            .comment("Days food stays fresh.")
            .defineInRange("freshDays", D.freshTicks() / (double) SpoilTimes.DAY, 0.01, 10000.0);

    public static final ModConfigSpec.DoubleValue RIPE_DAYS = BUILDER
            .comment("Days food then stays ripe.")
            .defineInRange("ripeDays", D.ripeTicks() / (double) SpoilTimes.DAY, 0.01, 10000.0);

    public static final ModConfigSpec.DoubleValue STALE_DAYS = BUILDER
            .comment("Days food then stays stale, before it is rotting.")
            .defineInRange("staleDays", D.staleTicks() / (double) SpoilTimes.DAY, 0.01, 10000.0);

    static {
        BUILDER.pop();
        BUILDER.comment("What eating food of each stage does. The multipliers scale the food's own food (hunger) and saturation;",
                "food is rounded to the nearest point. Effects are \"<effect id> <seconds> [level] [chance]\", e.g.",
                "\"minecraft:poison 3\" or \"minecraft:hunger 30 1 0.5\" (level starts at 1; chance 0 to 1, default 1).",
                "nutritionMultiplier scales what it adds to the Diet mod's food groups (only used when Diet is installed).")
                .push("eating");
    }

    /** One stage's eating settings. */
    private record StageValues(ModConfigSpec.DoubleValue food, ModConfigSpec.DoubleValue saturation,
                               ModConfigSpec.ConfigValue<List<? extends String>> effects,
                               ModConfigSpec.DoubleValue nutrition) {
    }

    private static final Map<FoodStage, List<String>> EFFECT_DEFAULTS = Map.of(
            FoodStage.FRESH, List.of(),
            FoodStage.RIPE, List.of(),
            FoodStage.STALE, List.of(),
            FoodStage.ROTTING, StageEffects.ROTTING_EFFECT_LINES);

    private static final Map<FoodStage, StageValues> EATING = new EnumMap<>(FoodStage.class);

    static {
        for (FoodStage stage : FoodStage.values()) {
            StageEffects d = StageEffects.DEFAULTS.get(stage);
            BUILDER.push(stage.id());
            ModConfigSpec.DoubleValue food = BUILDER.comment("Share of the food's food (hunger) points it gives.")
                    .defineInRange("foodMultiplier", d.foodMultiplier(), 0.0, 10.0);
            ModConfigSpec.DoubleValue saturation = BUILDER.comment("Share of the food's saturation it gives.")
                    .defineInRange("saturationMultiplier", d.saturationMultiplier(), 0.0, 10.0);
            ModConfigSpec.ConfigValue<List<? extends String>> effects = BUILDER.comment("Effects eating it gives.")
                    .defineListAllowEmpty("effects", EFFECT_DEFAULTS.get(stage), () -> "minecraft:poison 3",
                            line -> line instanceof String s && StageEffects.EffectSpec.parse(s).isPresent());
            ModConfigSpec.DoubleValue nutrition = BUILDER.comment("Share of the food's Diet nutrition it gives.")
                    .defineInRange("nutritionMultiplier", d.nutritionMultiplier(), 0.0, 10.0);
            BUILDER.pop();
            EATING.put(stage, new StageValues(food, saturation, effects, nutrition));
        }
        BUILDER.pop();
        BUILDER.comment("Food by temperature (2026-10-15): food ages faster where it is warm and slower where it is cool,",
                "frozen food nearly stops, and thawing costs part of a stage. Containers, players, items on the ground and",
                "Create belts and depots, by the air and room temperature where they are.").push("temperature");
    }

    public static final ModConfigSpec.BooleanValue CLIMATE_ENABLED = BUILDER
            .comment("Whether temperature changes how fast food spoils (off: the clock alone, as before).")
            .define("enabled", true);

    public static final ModConfigSpec.DoubleValue CLIMATE_REFERENCE = BUILDER
            .comment("The temperature, C, food spoils at the speed the stage lengths above give.")
            .defineInRange("referenceC", FoodClimate.Rules.DEFAULTS.reference(), -50.0, 100.0);

    public static final ModConfigSpec.DoubleValue CLIMATE_DOUBLING = BUILDER
            .comment("How many C warmer doubles the speed (and as many cooler halves it).")
            .defineInRange("doublingC", FoodClimate.Rules.DEFAULTS.doubling(), 1.0, 100.0);

    public static final ModConfigSpec.DoubleValue CLIMATE_FROZEN_RATE = BUILDER
            .comment("How fast frozen food spoils, relative to the reference speed.")
            .defineInRange("frozenRate", FoodClimate.Rules.DEFAULTS.frozenRate(), 0.0, 1.0);

    public static final ModConfigSpec.DoubleValue CLIMATE_FREEZE_BELOW = BUILDER
            .comment("Food freezes below this temperature, C.")
            .defineInRange("freezeBelowC", FoodClimate.Rules.DEFAULTS.freezeBelow(), -100.0, 100.0);

    public static final ModConfigSpec.DoubleValue CLIMATE_THAW_ABOVE = BUILDER
            .comment("Frozen food thaws above this temperature, C (between the two it keeps its state).")
            .defineInRange("thawAboveC", FoodClimate.Rules.DEFAULTS.thawAbove(), -100.0, 100.0);

    public static final ModConfigSpec.DoubleValue CLIMATE_THAW_PENALTY = BUILDER
            .comment("How much of a stage a thaw ages food by.")
            .defineInRange("thawPenaltyStages", FoodClimate.Rules.DEFAULTS.thawPenalty(), 0.0, 3.0);

    public static final ModConfigSpec.IntValue CLIMATE_SWEEP_SECONDS = BUILDER
            .comment("How often, in seconds, the food in loaded places has its temperature applied.")
            .defineInRange("sweepSeconds", 60, 5, 3600);

    static {
        BUILDER.pop();
        BUILDER.comment("The ice chest: keeps the food inside at 0 C (frozen) while it has ice; the ice melts faster where",
                "it is warmer (twice as fast every doublingC above referenceC, not at all at 0 C or below).").push("iceChest");
    }

    public static final ModConfigSpec.IntValue ICE_TICKS = BUILDER
            .comment("How long one block of ice cools the chest at referenceC, in ticks (20 = one second). 3000 = 2.5 minutes.")
            .defineInRange("iceTicks", (int) dev.brights0ng.simplespoilage.icechest.Coolants.DEFAULT_ICE_TICKS, 1, Integer.MAX_VALUE);

    public static final ModConfigSpec.ConfigValue<List<? extends String>> COOLANTS = BUILDER
            .comment("What the chest burns: \"<item id> <multiplier>\", the multiplier being of one block of ice's time.")
            .defineListAllowEmpty("coolants", dev.brights0ng.simplespoilage.icechest.Coolants.DEFAULT_LINES,
                    () -> "minecraft:ice 1",
                    line -> line instanceof String s && dev.brights0ng.simplespoilage.icechest.Coolants.parse(s).isPresent());

    static {
        BUILDER.pop();
    }

    public static final ModConfigSpec SPEC = BUILDER.build();

    /** Whether temperature changes how fast food spoils. */
    public static boolean climateEnabled() {
        return !SPEC.isLoaded() || CLIMATE_ENABLED.get();
    }

    /** The temperature rules now in force. */
    public static FoodClimate.Rules climate() {
        if (!SPEC.isLoaded()) {
            return FoodClimate.Rules.DEFAULTS;
        }
        return new FoodClimate.Rules(CLIMATE_REFERENCE.get(), CLIMATE_DOUBLING.get(), CLIMATE_FROZEN_RATE.get(),
                CLIMATE_FREEZE_BELOW.get(), CLIMATE_THAW_ABOVE.get(), CLIMATE_THAW_PENALTY.get());
    }

    /** Ticks between temperature sweeps. */
    public static int sweepTicks() {
        return (SPEC.isLoaded() ? CLIMATE_SWEEP_SECONDS.get() : 60) * 20;
    }

    /** Every coolant item id to how many ticks one of it lasts. */
    public static Map<String, Long> coolants() {
        if (!SPEC.isLoaded()) {
            return dev.brights0ng.simplespoilage.icechest.Coolants.ticksByItem(
                    dev.brights0ng.simplespoilage.icechest.Coolants.DEFAULT_LINES,
                    dev.brights0ng.simplespoilage.icechest.Coolants.DEFAULT_ICE_TICKS);
        }
        return dev.brights0ng.simplespoilage.icechest.Coolants.ticksByItem(COOLANTS.get(), ICE_TICKS.get());
    }

    /** What eating food of this stage does, as now configured. */
    public static StageEffects eating(FoodStage stage) {
        if (!SPEC.isLoaded()) {
            return StageEffects.DEFAULTS.get(stage);
        }
        StageValues values = EATING.get(stage);
        return new StageEffects(values.food().get(), values.saturation().get(),
                StageEffects.EffectSpec.parseAll(values.effects().get()), values.nutrition().get());
    }

    /** The stage lengths now in force. */
    public static SpoilTimes times() {
        if (!SPEC.isLoaded()) {
            return SpoilTimes.DEFAULTS;
        }
        return new SpoilTimes(ticks(FRESH_DAYS.get()), ticks(RIPE_DAYS.get()), ticks(STALE_DAYS.get()));
    }

    private static long ticks(double days) {
        return Math.round(days * SpoilTimes.DAY);
    }

    private SpoilageConfig() {
    }
}
