package dev.brights0ng.enginesandempires.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.khofonyx.encumbered.datamaps.EncumberedDataMaps;

import dev.brights0ng.enginesandempires.weight.ItemWeights;
import net.minecraft.core.Holder;
import net.minecraft.world.item.Item;

/**
 * Makes Encumbered weigh items by the pack's rules ({@link ItemWeights}) instead of its own data map.
 *
 * <p>{@code EncumberedDataMaps.getWeight} is the only place Encumbered looks a weight up: the server's per-tick total,
 * the mount check, the pickup check and the client tooltip all go through it. Its own data map (every vanilla item at
 * 1.0) is ignored.
 */
@Mixin(value = EncumberedDataMaps.class, remap = false)
public abstract class EncumberedWeightMixin {

    @Inject(method = "getWeight", at = @At("HEAD"), cancellable = true)
    private static void engines_and_empires$packWeight(Holder<Item> item, CallbackInfoReturnable<Float> cir) {
        cir.setReturnValue(ItemWeights.of(item));
    }
}
