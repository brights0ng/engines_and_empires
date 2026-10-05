package dev.brights0ng.enginesandempires.food;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import dev.brights0ng.enginesandempires.EnginesAndEmpiresMod;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.food.FoodProperties;
import net.minecraft.world.item.ItemStack;

/**
 * Turns a food's own {@link FoodProperties} into what eating it at its stage gives (see {@link StageEffects}): the stage is
 * that of the stack's top group, which is the unit eaten.
 */
public final class EatingEffects {

    /** Effect ids in the config that are not real effects, so each is only complained about once. */
    private static final Set<String> WARNED = new HashSet<>();

    /** The stage the next unit eaten from this stack is in; fresh for food that does not spoil or has not been stamped. */
    public static FoodStage stageOfTop(ItemStack food) {
        if (!Spoilage.isSpoilable(food)) {
            return FoodStage.FRESH;
        }
        long now = SpoilClock.now();
        if (now < 0) {
            return FoodStage.FRESH;
        }
        Cohort top = Spoilage.view(food, now).topCohort();
        return top == null ? FoodStage.FRESH : Spoilage.times().stage(now - top.born());
    }

    /** What eating the top unit of {@code food} gives. */
    public static FoodProperties adjust(ItemStack food, FoodProperties properties) {
        if (!Spoilage.isSpoilable(food)) {
            return properties;
        }
        return adjust(properties, SpoilageConfig.eating(stageOfTop(food)));
    }

    /**
     * The share of its Diet nutrition eating the top unit of {@code food} gives: 1 for food that does not spoil (so
     * non-perishables never get the fresh bonus).
     */
    public static StageEffects nutritionStage(ItemStack food) {
        if (!Spoilage.isSpoilable(food)) {
            return StageEffects.NONE;
        }
        return SpoilageConfig.eating(stageOfTop(food));
    }

    public static FoodProperties adjust(FoodProperties properties, StageEffects stage) {
        if (!stage.changesFood()) {
            return properties;
        }
        List<FoodProperties.PossibleEffect> effects = new ArrayList<>(properties.effects());
        for (StageEffects.EffectSpec spec : stage.effects()) {
            Holder<MobEffect> effect = effect(spec.effect());
            if (effect != null) {
                MobEffectInstance instance = new MobEffectInstance(effect, spec.durationTicks(), spec.level() - 1);
                effects.add(new FoodProperties.PossibleEffect(() -> instance, spec.chance()));
            }
        }
        return new FoodProperties(stage.nutrition(properties.nutrition()), stage.saturation(properties.saturation()),
                properties.canAlwaysEat(), properties.eatSeconds(), properties.usingConvertsTo(), List.copyOf(effects));
    }

    private static Holder<MobEffect> effect(String id) {
        ResourceLocation key = ResourceLocation.tryParse(id);
        Holder<MobEffect> holder = key == null ? null : BuiltInRegistries.MOB_EFFECT.getHolder(key).orElse(null);
        if (holder == null) {
            synchronized (WARNED) {
                if (WARNED.add(id)) {
                    EnginesAndEmpiresMod.LOGGER.warn("Food spoilage config names an effect that does not exist: {}", id);
                }
            }
        }
        return holder;
    }

    private EatingEffects() {
    }
}
