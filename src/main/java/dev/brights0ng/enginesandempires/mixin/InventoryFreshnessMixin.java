package dev.brights0ng.enginesandempires.mixin;

import java.util.ArrayList;
import java.util.List;

import org.spongepowered.asm.mixin.Mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;

import dev.brights0ng.enginesandempires.food.Spoilage;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;

/**
 * Food added to a player's inventory (picked up off the ground, given back from a closed menu, handed over by a mod)
 * brings its freshness with it into whichever stacks it joins.
 */
@Mixin(Inventory.class)
public abstract class InventoryFreshnessMixin {

    @WrapMethod(method = "add(ILnet/minecraft/world/item/ItemStack;)Z")
    private boolean engines_and_empires$settleFreshness(int slot, ItemStack stack, Operation<Boolean> original) {
        if (!Spoilage.isSpoilable(stack)) {
            return original.call(slot, stack);
        }
        Inventory self = (Inventory) (Object) this;
        Spoilage.Places places = Spoilage.Places.capture(stacks(self, stack));
        boolean added = original.call(slot, stack);
        if (places != null) {
            places.settle(stacks(self, stack));
        }
        return added;
    }

    private static List<ItemStack> stacks(Inventory inventory, ItemStack incoming) {
        int size = inventory.getContainerSize();
        List<ItemStack> stacks = new ArrayList<>(size + 1);
        for (int i = 0; i < size; i++) {
            stacks.add(inventory.getItem(i));
        }
        stacks.add(incoming);
        return stacks;
    }
}
