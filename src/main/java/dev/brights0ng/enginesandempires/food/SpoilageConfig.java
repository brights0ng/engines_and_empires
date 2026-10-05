package dev.brights0ng.enginesandempires.food;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import net.neoforged.neoforge.common.ModConfigSpec;

/**
 * Food spoilage settings. A SERVER config ({@code serverconfig/engines_and_empires-spoilage-server.toml} in the world folder),
 * which the server sends to its players, so tooltips agree with the server.
 *
 * <p>Code reads them through {@link #times()}, which falls back to {@link SpoilTimes#DEFAULTS} before the config has loaded.
 */
public final class SpoilageConfig {

    public static final String FILE_NAME = "engines_and_empires-spoilage-server.toml";

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
        BUILDER.comment("The ice chest: food inside spoils slower while it has ice burning.").push("iceChest");
    }

    public static final ModConfigSpec.DoubleValue ICE_CHEST_SLOWDOWN = BUILDER
            .comment("How much slower food spoils while the chest is cooling: 0.9 = 90% slower (a 6-day stage lasts 60 days).")
            .defineInRange("slowdown", 0.9, 0.0, 1.0);

    public static final ModConfigSpec.IntValue ICE_TICKS = BUILDER
            .comment("How long one block of ice cools the chest, in ticks (20 = one second). 3000 = 2.5 minutes.")
            .defineInRange("iceTicks", (int) dev.brights0ng.enginesandempires.food.icechest.Coolants.DEFAULT_ICE_TICKS, 1, Integer.MAX_VALUE);

    public static final ModConfigSpec.ConfigValue<List<? extends String>> COOLANTS = BUILDER
            .comment("What the chest burns: \"<item id> <multiplier>\", the multiplier being of one block of ice's time.")
            .defineListAllowEmpty("coolants", dev.brights0ng.enginesandempires.food.icechest.Coolants.DEFAULT_LINES,
                    () -> "minecraft:ice 1",
                    line -> line instanceof String s && dev.brights0ng.enginesandempires.food.icechest.Coolants.parse(s).isPresent());

    static {
        BUILDER.pop();
    }

    public static final ModConfigSpec SPEC = BUILDER.build();

    public static double iceChestSlowdown() {
        return SPEC.isLoaded() ? ICE_CHEST_SLOWDOWN.get() : 0.9;
    }

    /** Every coolant item id to how many ticks one of it lasts. */
    public static Map<String, Long> coolants() {
        if (!SPEC.isLoaded()) {
            return dev.brights0ng.enginesandempires.food.icechest.Coolants.ticksByItem(
                    dev.brights0ng.enginesandempires.food.icechest.Coolants.DEFAULT_LINES,
                    dev.brights0ng.enginesandempires.food.icechest.Coolants.DEFAULT_ICE_TICKS);
        }
        return dev.brights0ng.enginesandempires.food.icechest.Coolants.ticksByItem(COOLANTS.get(), ICE_TICKS.get());
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
