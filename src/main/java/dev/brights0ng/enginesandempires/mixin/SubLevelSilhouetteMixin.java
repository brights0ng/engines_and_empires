package dev.brights0ng.enginesandempires.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import dev.brights0ng.enginesandempires.weather.wind.WindShips;
import dev.ryanhcode.sable.sublevel.SubLevel;
import dev.ryanhcode.sable.sublevel.system.SubLevelPhysicsSystem;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Keeps each physics object's wind outline current. Every block change inside a Sable sub-level goes through
 * {@code SubLevelPhysicsSystem.updateMassDataFromBlockChange} (that is how Sable keeps its mass and inertia right), so
 * this one hook sees placing, breaking, explosions and contraptions alike.
 */
@Mixin(value = SubLevelPhysicsSystem.class, remap = false)
public abstract class SubLevelSilhouetteMixin {

    @Inject(method = "updateMassDataFromBlockChange", at = @At("TAIL"))
    private void engines_and_empires$windOutline(SubLevel subLevel, BlockPos globalBlockPos, BlockState oldState,
                                                 BlockState newState, boolean notifyPipeline, CallbackInfo ci) {
        WindShips.onBlockChange(subLevel, globalBlockPos, oldState, newState);
    }
}
