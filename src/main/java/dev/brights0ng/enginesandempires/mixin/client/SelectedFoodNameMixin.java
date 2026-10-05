package dev.brights0ng.enginesandempires.mixin.client;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;

import dev.brights0ng.enginesandempires.food.client.FoodClient;
import net.minecraft.client.gui.Gui;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;

/**
 * The item name that pops up above the hotbar when you switch to a food stack also shows its top group: "Bread (Fresh x21)".
 * Only the drawing is changed, not what the popup compares to decide whether to show again, so eating a unit (which changes
 * the count) does not bring the popup back.
 */
@Mixin(Gui.class)
public abstract class SelectedFoodNameMixin {

    @WrapOperation(method = "renderSelectedItemName(Lnet/minecraft/client/gui/GuiGraphics;I)V",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/world/item/ItemStack;getHighlightTip(Lnet/minecraft/network/chat/Component;)Lnet/minecraft/network/chat/Component;"))
    private Component engines_and_empires$addTopGroup(ItemStack stack, Component name, Operation<Component> original) {
        return FoodClient.withTopGroup(stack, original.call(stack, name));
    }
}
