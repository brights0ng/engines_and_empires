package dev.brights0ng.enginesandempires.mixin;

import org.spongepowered.asm.mixin.Mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;

import dev.brights0ng.enginesandempires.food.Spoilage;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ClickType;

/**
 * Every click in a menu (picking up, placing, splitting, dragging, shift-clicking, double-click collecting, in any mod's
 * menu too) is settled as a whole: the food in every slot and the cursor is recorded before, and after it the freshness is
 * put where the units went (see {@link dev.brights0ng.enginesandempires.food.FreshnessLedger}).
 *
 * <p>Number-key swaps are left out: they move whole stacks, which keep their own freshness anyway, and the ledger (which
 * only sees counts) would read a swap of two food stacks as units passing between them.
 */
@Mixin(AbstractContainerMenu.class)
public abstract class ContainerMenuFreshnessMixin {

    @WrapMethod(method = "doClick")
    private void engines_and_empires$settleFreshness(int slotId, int button, ClickType clickType, Player player, Operation<Void> original) {
        AbstractContainerMenu self = (AbstractContainerMenu) (Object) this;
        Spoilage.Places places = clickType == ClickType.SWAP ? null : Spoilage.Places.captureMenu(self);
        if (places == null) {
            original.call(slotId, button, clickType, player);
            return;
        }
        places.runAndSettle(() -> original.call(slotId, button, clickType, player), () -> Spoilage.Places.menuStacks(self));
    }
}
