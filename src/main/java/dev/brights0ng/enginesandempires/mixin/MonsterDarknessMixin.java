package dev.brights0ng.enginesandempires.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import dev.brights0ng.enginesandempires.frontier.spawn.FrontierDarkness;
import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.level.ServerLevelAccessor;

/**
 * In Frontier land, torches do not keep monsters away: below y 0 light does not matter at all, and above it only daylight
 * does. Vanilla's monster darkness check ({@code Monster.isDarkEnoughToSpawn}, used by zombies, skeletons, creepers, spiders
 * and most modded monsters) is answered by {@link FrontierDarkness} there instead.
 */
@Mixin(Monster.class)
public abstract class MonsterDarknessMixin {

    @Inject(method = "isDarkEnoughToSpawn", at = @At("HEAD"), cancellable = true)
    private static void engines_and_empires$frontierDarkness(ServerLevelAccessor level, BlockPos pos, RandomSource random,
                                                           CallbackInfoReturnable<Boolean> cir) {
        Boolean frontier = FrontierDarkness.override(level, pos, random);
        if (frontier != null) {
            cir.setReturnValue(frontier);
        }
    }
}
