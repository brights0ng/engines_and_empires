package dev.brights0ng.enginesandempires.mixin;

import org.spongepowered.asm.mixin.Mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;

import dev.brights0ng.enginesandempires.food.FoodFreshness;
import dev.brights0ng.enginesandempires.food.Spoilage;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.food.FoodProperties;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

/**
 * Eating takes the unit from the top group of the stack (the one the player scrolled to), not the freshest. Players go
 * through here too ({@code Player.eat} calls up to this).
 */
@Mixin(LivingEntity.class)
public abstract class EatFreshnessMixin {

    @WrapMethod(method = "eat(Lnet/minecraft/world/level/Level;Lnet/minecraft/world/item/ItemStack;Lnet/minecraft/world/food/FoodProperties;)Lnet/minecraft/world/item/ItemStack;")
    private ItemStack engines_and_empires$eatFromTop(Level level, ItemStack food, FoodProperties properties, Operation<ItemStack> original) {
        FoodFreshness before = Spoilage.beforeSplit(food);
        int countBefore = food.getCount();
        ItemStack result = original.call(level, food, properties);
        if (before != null) {
            Spoilage.afterEating(food, before, countBefore - food.getCount());
        }
        return result;
    }
}
