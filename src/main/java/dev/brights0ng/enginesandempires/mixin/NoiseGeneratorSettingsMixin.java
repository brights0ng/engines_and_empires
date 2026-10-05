package dev.brights0ng.enginesandempires.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;

/**
 * Switches off vanilla's large ore veins, the big copper and iron veins (with granite and tuff filler and
 * raw-ore blocks) that the overworld generates directly from its terrain noise. They are not worldgen
 * features, so removing features does not touch them, and they would appear alongside the pack's own
 * deposits.
 *
 * <p>Whether they generate is one boolean, {@code ore_veins_enabled}, in each dimension's noise settings.
 * The game reads it in a single place, when it builds the noise for a chunk (or for a single terrain
 * column, as the deposit resolver does), so forcing the accessor to return false turns them off for every
 * noise settings there is: the ordinary overworld, large biomes, amplified, and any added by other mods.
 * This is smaller and much less likely to clash with other mods than replacing whole noise settings files.
 *
 * <p>It affects only terrain generated after this mod is installed; chunks that already exist keep their veins.
 */
@Mixin(NoiseGeneratorSettings.class)
public abstract class NoiseGeneratorSettingsMixin {

    @Inject(method = "oreVeinsEnabled", at = @At("HEAD"), cancellable = true)
    private void enginesandempires$noOreVeins(CallbackInfoReturnable<Boolean> cir) {
        cir.setReturnValue(false);
    }
}
