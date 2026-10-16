package dev.brights0ng.enginesandempires.mixin.compat.coldsweat;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import dev.brights0ng.enginesandempires.weather.climate.compat.ColdSweatHearths;
import net.minecraft.world.level.block.entity.BlockEntity;

/** The boiler and icebox have their own smart setting; off where the heat simulation stands in (weather phase 8f-3). */
@Pseudo
@Mixin(targets = {"com.momosoftworks.coldsweat.common.blockentity.BoilerBlockEntity",
        "com.momosoftworks.coldsweat.common.blockentity.IceboxBlockEntity"}, remap = false)
public abstract class HearthKinSmartMixin {

    @Inject(method = "isSmartEnabled", at = @At("HEAD"), cancellable = true)
    private void engines_and_empires$noSmartMode(CallbackInfoReturnable<Boolean> cir) {
        if (ColdSweatHearths.replaced((BlockEntity) (Object) this)) {
            cir.setReturnValue(false);
        }
    }
}
