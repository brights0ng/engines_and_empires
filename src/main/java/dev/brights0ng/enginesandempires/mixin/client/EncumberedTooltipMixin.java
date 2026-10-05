package dev.brights0ng.enginesandempires.mixin.client;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.khofonyx.encumbered.client.ToolTipHandler;

import dev.brights0ng.enginesandempires.weight.ItemWeights;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.event.entity.player.ItemTooltipEvent;

/**
 * Replaces Encumbered's tooltip line ("Weight: 0.44444445") with one in kpg, Aeronautics' unit: "Weight: 0.5 kpg", plus
 * the whole stack's weight when there is more than one ("Weight: 0.5 kpg (32 kpg for 64)").
 */
@Mixin(value = ToolTipHandler.class, remap = false)
public abstract class EncumberedTooltipMixin {

    @Inject(method = "onItemTooltip", at = @At("HEAD"), cancellable = true)
    private static void engines_and_empires$kpgTooltip(ItemTooltipEvent event, CallbackInfo ci) {
        ci.cancel();
        ItemStack stack = event.getItemStack();
        if (stack.isEmpty()) {
            return;
        }
        float each = ItemWeights.of(stack.getItemHolder());
        Component line = stack.getCount() > 1
                ? Component.translatable("tooltip.engines_and_empires.weight.stack", ItemWeights.format(each),
                        ItemWeights.format((double) each * stack.getCount()), stack.getCount())
                : Component.translatable("tooltip.engines_and_empires.weight", ItemWeights.format(each));
        event.getToolTip().add(line.copy().withStyle(ChatFormatting.GRAY));
    }
}
