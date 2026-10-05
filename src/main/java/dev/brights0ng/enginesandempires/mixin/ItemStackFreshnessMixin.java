package dev.brights0ng.enginesandempires.mixin;

import javax.annotation.Nullable;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;

import dev.brights0ng.enginesandempires.food.FoodFreshness;
import dev.brights0ng.enginesandempires.food.Spoilage;
import net.minecraft.world.item.ItemStack;

/**
 * Food stacks of different freshness stack together, and keep their freshness straight while they do. See {@link Spoilage}
 * for how; this only passes the calls on.
 */
@Mixin(ItemStack.class)
public abstract class ItemStackFreshnessMixin {

    /** Food that differs only in freshness counts as the same item, so it stacks. */
    @Inject(method = "isSameItemSameComponents", at = @At("HEAD"), cancellable = true)
    private static void engines_and_empires$ignoreFreshness(ItemStack stack, ItemStack other, CallbackInfoReturnable<Boolean> cir) {
        Boolean same = Spoilage.sameIgnoringFreshness(stack, other);
        if (same != null) {
            cir.setReturnValue(same);
        }
    }

    /** But "exactly equal" still sees freshness, so menus send freshness changes to clients. */
    @Inject(method = "matches", at = @At("HEAD"), cancellable = true)
    private static void engines_and_empires$exactFreshness(ItemStack stack, ItemStack other, CallbackInfoReturnable<Boolean> cir) {
        Boolean equal = Spoilage.exactlyEqual(stack, other);
        if (equal != null) {
            cir.setReturnValue(equal);
        }
    }

    /** Hashes agree with {@link #engines_and_empires$ignoreFreshness}, for the sets and maps that use the two together. */
    @Inject(method = "hashItemAndComponents", at = @At("HEAD"), cancellable = true)
    private static void engines_and_empires$hashIgnoringFreshness(@Nullable ItemStack stack, CallbackInfoReturnable<Integer> cir) {
        Integer hash = Spoilage.hashIgnoringFreshness(stack);
        if (hash != null) {
            cir.setReturnValue(hash);
        }
    }

    /** A count change nothing else accounted for is resolved the safe way. */
    @Inject(method = "setCount", at = @At("HEAD"))
    private void engines_and_empires$reconcileFreshness(int count, CallbackInfo ci) {
        Spoilage.onSetCount((ItemStack) (Object) this, count);
    }

    /** The part split off a food stack is its top units. */
    @WrapMethod(method = "split")
    private ItemStack engines_and_empires$splitFromTop(int amount, Operation<ItemStack> original) {
        ItemStack self = (ItemStack) (Object) this;
        FoodFreshness before = Spoilage.beforeSplit(self);
        ItemStack taken = original.call(amount);
        if (before != null) {
            Spoilage.afterSplit(self, taken, before);
        }
        return taken;
    }
}
