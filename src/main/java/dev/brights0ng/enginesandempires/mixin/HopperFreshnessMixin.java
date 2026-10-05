package dev.brights0ng.enginesandempires.mixin;

import javax.annotation.Nullable;

import org.spongepowered.asm.mixin.Mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;

import dev.brights0ng.enginesandempires.food.Spoilage;
import net.minecraft.core.Direction;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.HopperBlockEntity;

/**
 * Food a hopper (or hopper minecart) moves into a slot joins the stack there with its freshness kept. Taking food out goes
 * through {@code ItemStack.split}, which already hands over the top units.
 */
@Mixin(HopperBlockEntity.class)
public abstract class HopperFreshnessMixin {

    @WrapMethod(method = "tryMoveInItem")
    private static ItemStack engines_and_empires$settleFreshness(@Nullable Container source, Container destination, ItemStack stack,
                                                                 int slot, @Nullable Direction direction, Operation<ItemStack> original) {
        Spoilage.Places places = Spoilage.isSpoilable(stack) ? Spoilage.Places.capture(destination.getItem(slot), stack) : null;
        ItemStack remainder = original.call(source, destination, stack, slot, direction);
        if (places != null) {
            places.settle(destination.getItem(slot), remainder);
        }
        return remainder;
    }
}
