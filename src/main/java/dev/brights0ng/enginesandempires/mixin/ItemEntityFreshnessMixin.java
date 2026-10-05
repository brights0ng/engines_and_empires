package dev.brights0ng.enginesandempires.mixin;

import org.spongepowered.asm.mixin.Mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;

import dev.brights0ng.enginesandempires.food.Spoilage;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;

/** Food lying on the ground merges into one item entity with its freshness kept (the new stack replaces the old one). */
@Mixin(ItemEntity.class)
public abstract class ItemEntityFreshnessMixin {

    @WrapMethod(method = "merge(Lnet/minecraft/world/item/ItemStack;Lnet/minecraft/world/item/ItemStack;I)Lnet/minecraft/world/item/ItemStack;")
    private static ItemStack engines_and_empires$settleFreshness(ItemStack destination, ItemStack origin, int amount,
                                                                 Operation<ItemStack> original) {
        Spoilage.Places places = Spoilage.Places.capture(destination, origin);
        ItemStack merged = original.call(destination, origin, amount);
        if (places != null) {
            places.settle(merged, origin);
        }
        return merged;
    }
}
