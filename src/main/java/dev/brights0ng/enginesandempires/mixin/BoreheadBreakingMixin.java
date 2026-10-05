package dev.brights0ng.enginesandempires.mixin;

import java.util.List;
import java.util.function.Consumer;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;

import dev.brights0ng.enginesandempires.oregen.refining.BoreheadCrushing;
import dev.ryanhcode.offroad.handlers.server.MultiMiningServerManager;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

/**
 * Makes Create Aeronautics' borehead bearing crush the ores it mines, as Create's crushing wheels would, instead of
 * dropping what a pickaxe would (see {@link BoreheadCrushing}).
 *
 * <p>Offroad's rock cutting wheels only aim; the block is broken in {@code MultiMiningServerManager.BlockBreakingData.tick},
 * with Create's {@code BlockHelper.destroyBlock}, whose callback hands each drop to the borehead's storage. This wraps
 * that call: for an ore it still breaks the block (sound, particles, removal), but throws its drops away and hands over
 * the crushing results instead.
 */
@Mixin(value = MultiMiningServerManager.BlockBreakingData.class, remap = false)
public abstract class BoreheadBreakingMixin {

    @WrapOperation(method = "tick", at = @At(value = "INVOKE",
            target = "Lcom/simibubi/create/foundation/utility/BlockHelper;destroyBlock(Lnet/minecraft/world/level/Level;Lnet/minecraft/core/BlockPos;FLjava/util/function/Consumer;)V"))
    private void engines_and_empires$crushOres(Level level, BlockPos pos, float effectChance, Consumer<ItemStack> drops,
                                               Operation<Void> original) {
        List<ItemStack> crushed = BoreheadCrushing.crush(level, level.getBlockState(pos));
        if (crushed == null) {
            original.call(level, pos, effectChance, drops);
            return;
        }
        Consumer<ItemStack> discard = stack -> { };
        original.call(level, pos, effectChance, discard);
        for (ItemStack stack : crushed) {
            drops.accept(stack);
        }
    }
}
