package dev.brights0ng.enginesandempires.mixin;

import org.spongepowered.asm.mixin.Mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;

import dev.brights0ng.enginesandempires.food.Spoilage;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.items.IItemHandler;
import net.neoforged.neoforge.items.ItemStackHandler;
import net.neoforged.neoforge.items.wrapper.InvWrapper;
import net.neoforged.neoforge.items.wrapper.SidedInvWrapper;

/**
 * Food moved through NeoForge item handlers (how Create's funnels, chutes, belts and most modded machines and storage move
 * items) keeps its freshness: {@link ItemStackHandler}, the standard handler most mods' inventories are built on, and the
 * two wrappers that put vanilla containers (chests, barrels, furnaces, shulker boxes) behind the handler interface.
 *
 * <p>These matter beyond keeping things tidy: the wrappers insert into an occupied slot by replacing its stack with a copy
 * of the incoming one, so without this, fresh food fed into a chest would make the rotten food already there fresh.
 */
@Mixin({ItemStackHandler.class, InvWrapper.class, SidedInvWrapper.class})
public abstract class ItemHandlerFreshnessMixin {

    @WrapMethod(method = "insertItem")
    private ItemStack engines_and_empires$settleInsert(int slot, ItemStack stack, boolean simulate, Operation<ItemStack> original) {
        if (simulate || !Spoilage.isSpoilable(stack)) {
            return original.call(slot, stack, simulate);
        }
        IItemHandler self = (IItemHandler) (Object) this;
        Spoilage.Places places = Spoilage.Places.capture(self.getStackInSlot(slot), stack);
        ItemStack remainder = original.call(slot, stack, simulate);
        if (places != null) {
            places.settle(self.getStackInSlot(slot), remainder);
        }
        return remainder;
    }
}
