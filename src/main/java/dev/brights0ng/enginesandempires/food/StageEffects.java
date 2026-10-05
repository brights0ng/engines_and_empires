package dev.brights0ng.enginesandempires.food;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * What eating food of a stage does, on top of the food's own values: how much of its food (hunger) and saturation it still
 * gives, any effects it adds, and how much of its Diet nutrition it gives (see {@code food.compat.DietCompat}). Read from
 * {@link SpoilageConfig}, so all of it can be changed per world.
 *
 * <p>Bright's values (2026-09-27): fresh and ripe as normal; stale 75% food and saturation; rotting 50%, with 3 s of poison
 * and 20 s each of weakness and nausea.
 *
 * <p>Nutrition (2026-09-29): fresh 125%, ripe 100%, stale 50%, rotting none.
 *
 * <p>Plain Java on purpose (no Minecraft types), so the numbers can be unit tested.
 */
public record StageEffects(double foodMultiplier, double saturationMultiplier, List<EffectSpec> effects,
                           double nutritionMultiplier) {

    public static final StageEffects NONE = new StageEffects(1, 1, List.of(), 1);

    /** The rotting effects as config lines. */
    public static final List<String> ROTTING_EFFECT_LINES = List.of("minecraft:poison 3", "minecraft:weakness 20", "minecraft:nausea 20");

    /** Bright's values (2026-09-27, nutrition 2026-09-29), the config's defaults. */
    public static final java.util.Map<FoodStage, StageEffects> DEFAULTS = java.util.Map.of(
            FoodStage.FRESH, new StageEffects(1, 1, List.of(), 1.25),
            FoodStage.RIPE, NONE,
            FoodStage.STALE, new StageEffects(0.75, 0.75, List.of(), 0.5),
            FoodStage.ROTTING, new StageEffects(0.5, 0.5, EffectSpec.parseAll(ROTTING_EFFECT_LINES), 0));

    public StageEffects {
        effects = List.copyOf(effects);
    }

    /** Normal nutrition. */
    public StageEffects(double foodMultiplier, double saturationMultiplier, List<EffectSpec> effects) {
        this(foodMultiplier, saturationMultiplier, effects, 1);
    }

    /** Whether eating at this stage changes the food's own food, saturation or effects (nutrition aside). */
    public boolean changesFood() {
        return foodMultiplier != 1 || saturationMultiplier != 1 || !effects.isEmpty();
    }

    /** The food (hunger points) food with {@code nutrition} gives at this stage: scaled, rounded to the nearest point. */
    public int nutrition(int nutrition) {
        return (int) Math.max(0, Math.round(nutrition * foodMultiplier));
    }

    public float saturation(float saturation) {
        return (float) Math.max(0, saturation * saturationMultiplier);
    }

    /** A Diet nutrition gain (0 to 1) at this stage. Not capped: Diet clamps the player's total itself. */
    public float dietGain(float gain) {
        return (float) Math.max(0, gain * nutritionMultiplier);
    }

    /**
     * An effect written in the config as {@code "<effect id> <seconds> [level] [chance]"}, for example
     * {@code "minecraft:poison 3"} or {@code "minecraft:hunger 30 1 0.5"}. Level starts at 1 (as in-game); chance is 0 to 1
     * and defaults to 1.
     */
    public record EffectSpec(String effect, double seconds, int level, float chance) {

        public int durationTicks() {
            return (int) Math.round(seconds * 20);
        }

        /** The effect in a config line, or empty if the line is not one. */
        public static Optional<EffectSpec> parse(String line) {
            String[] parts = line.trim().split("\\s+");
            if (parts.length < 2 || parts.length > 4 || parts[0].isEmpty()) {
                return Optional.empty();
            }
            try {
                double seconds = Double.parseDouble(parts[1]);
                int level = parts.length > 2 ? Integer.parseInt(parts[2]) : 1;
                float chance = parts.length > 3 ? Float.parseFloat(parts[3]) : 1f;
                if (seconds <= 0 || level < 1 || level > 256 || chance < 0 || chance > 1) {
                    return Optional.empty();
                }
                return Optional.of(new EffectSpec(parts[0], seconds, level, chance));
            } catch (NumberFormatException e) {
                return Optional.empty();
            }
        }

        public static List<EffectSpec> parseAll(List<? extends String> lines) {
            List<EffectSpec> out = new ArrayList<>();
            for (String line : lines) {
                parse(line).ifPresent(out::add);
            }
            return out;
        }
    }
}
