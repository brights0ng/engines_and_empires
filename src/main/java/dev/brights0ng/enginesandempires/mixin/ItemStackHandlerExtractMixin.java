package dev.brights0ng.enginesandempires.mixin;

import org.spongepowered.asm.mixin.Mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;

import dev.brights0ng.enginesandempires.food.Spoilage;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.items.ItemStackHandler;

/**
 * Food taken out of an {@link ItemStackHandler} is the slot's top units, and the slot keeps the rest. (The handler makes two
 * fresh copies of the slot's stack for this, so without it both would keep the same units.)
 */
@Mixin(ItemStackHandler.class)
public abstract class ItemStackHandlerExtractMixin {

    @WrapMethod(method = "extractItem")
    private ItemStack engines_and_empires$settleExtract(int slot, int amount, boolean simulate, Operation<ItemStack> original) {
        ItemStackHandler self = (ItemStackHandler) (Object) this;
        Spoilage.Places places = simulate || amount <= 0 ? null : Spoilage.Places.capture(self.getStackInSlot(slot), ItemStack.EMPTY);
        ItemStack extracted = original.call(slot, amount, simulate);
        if (places != null) {
            places.settle(self.getStackInSlot(slot), extracted);
        }
        return extracted;
    }
}
