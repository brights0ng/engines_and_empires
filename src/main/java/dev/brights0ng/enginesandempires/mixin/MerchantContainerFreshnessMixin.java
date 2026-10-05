package dev.brights0ng.enginesandempires.mixin;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import dev.brights0ng.enginesandempires.food.SourceFreshness;
import dev.brights0ng.enginesandempires.food.Spoilage;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.inventory.MerchantContainer;
import net.minecraft.world.item.trading.Merchant;
import net.minecraft.world.item.trading.MerchantOffer;

/** Food bought from a trader is stamped by the trader's freshness bucket as the trade's result appears. */
@Mixin(MerchantContainer.class)
public abstract class MerchantContainerFreshnessMixin {

    @Shadow
    @Final
    private Merchant merchant;

    @Shadow
    private MerchantOffer activeOffer;

    @Inject(method = "updateSellItem", at = @At("TAIL"))
    private void engines_and_empires$stampTrade(CallbackInfo ci) {
        if (merchant.isClientSide() || activeOffer == null || !(merchant instanceof Entity trader)) {
            return;
        }
        MerchantContainer self = (MerchantContainer) (Object) this;
        SourceFreshness.stampTrade(self.getItem(2), trader, merchant.getOffers().indexOf(activeOffer), activeOffer.getUses());
        // Shift-click trading refills the result mid-click; the click's settling needs to know what each refill was
        Spoilage.Places.noteMinted(self.getItem(2));
    }
}
