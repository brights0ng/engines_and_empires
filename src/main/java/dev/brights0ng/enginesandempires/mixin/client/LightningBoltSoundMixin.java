package dev.brights0ng.enginesandempires.mixin.client;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;

import dev.brights0ng.enginesandempires.weather.lightning.LightningFlashes;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.LightningBolt;
import net.minecraft.world.level.Level;

/**
 * A vanilla bolt plays its thunder and crack at once, heard everywhere. For a strike the pack's weather announced
 * (weather phase 6c), {@code ClientLightning} plays them when the sound would arrive instead, so the bolt keeps quiet.
 * Bolts the weather didn't make (Channeling, commands, other mods) keep vanilla's sound.
 */
@Mixin(LightningBolt.class)
public abstract class LightningBoltSoundMixin {

    @WrapOperation(method = "tick", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/Level;playLocalSound(DDDLnet/minecraft/sounds/SoundEvent;Lnet/minecraft/sounds/SoundSource;FFZ)V"))
    private void engines_and_empires$delayedThunder(Level level, double x, double y, double z, SoundEvent sound,
                                                   SoundSource source, float volume, float pitch, boolean delay,
                                                   Operation<Void> original) {
        if (level.isClientSide() && LightningFlashes.announcedNear(x, y, z)) {
            return;
        }
        original.call(level, x, y, z, sound, source, volume, pitch, delay);
    }
}
