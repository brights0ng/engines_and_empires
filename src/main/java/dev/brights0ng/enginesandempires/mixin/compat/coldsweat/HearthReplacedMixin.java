package dev.brights0ng.enginesandempires.mixin.compat.coldsweat;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import dev.brights0ng.enginesandempires.weather.climate.compat.ColdSweatHearths;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.block.entity.BlockEntity;

/**
 * In the Overworld outside ships the pack's heat simulation stands in for Cold Sweat's hearth family (weather phase
 * 8f-3, {@link ColdSweatHearths}): no air spreading, no insulation given, no smart mode (it decides fuel use by who it
 * insulates). Fuel use then follows Cold Sweat's redstone controls.
 */
@Pseudo
@Mixin(targets = "com.momosoftworks.coldsweat.common.blockentity.HearthBlockEntity", remap = false)
public abstract class HearthReplacedMixin {

    @Inject(method = "tickPaths", at = @At("HEAD"), cancellable = true)
    private void engines_and_empires$noSpreading(CallbackInfo ci) {
        if (ColdSweatHearths.replaced((BlockEntity) (Object) this)) {
            ci.cancel();
        }
    }

    @Inject(method = "insulateEntity", at = @At("HEAD"), cancellable = true)
    private void engines_and_empires$noInsulation(LivingEntity entity, CallbackInfoReturnable<Boolean> cir) {
        if (ColdSweatHearths.replaced((BlockEntity) (Object) this)) {
            cir.setReturnValue(false);
        }
    }

    @Inject(method = "isSmartEnabled", at = @At("HEAD"), cancellable = true)
    private void engines_and_empires$noSmartMode(CallbackInfoReturnable<Boolean> cir) {
        if (ColdSweatHearths.replaced((BlockEntity) (Object) this)) {
            cir.setReturnValue(false);
        }
    }
}
