package dev.brights0ng.enginesandempires.mixin;

import org.spongepowered.asm.mixin.Mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;

import dev.brights0ng.enginesandempires.food.EatingEffects;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.food.FoodProperties;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

/**
 * A player eating food gets what its top group's stage gives (less food and saturation when stale or rotting, and the
 * rotting effects). The changed values go on to vanilla's own eating, so hunger, saturation and effects are all applied the
 * normal way; {@link EatFreshnessMixin} then takes the eaten unit off the top.
 */
@Mixin(Player.class)
public abstract class PlayerEatStageMixin {

    @WrapMethod(method = "eat(Lnet/minecraft/world/level/Level;Lnet/minecraft/world/item/ItemStack;Lnet/minecraft/world/food/FoodProperties;)Lnet/minecraft/world/item/ItemStack;")
    private ItemStack engines_and_empires$eatAtStage(Level level, ItemStack food, FoodProperties properties, Operation<ItemStack> original) {
        return original.call(level, food, EatingEffects.adjust(food, properties));
    }
}
