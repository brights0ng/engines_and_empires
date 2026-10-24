// Written by Claude.
package dev.brights0ng.empire.civilian;

import dev.brights0ng.empire.Empire;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.Items;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;

/**
 * Keeps name tags from renaming civilians. Vanilla applies a name tag before {@link CivilianEntity#mobInteract} is ever
 * asked ({@code Mob.interact} is final), so a main-hand name tag is caught here and turned into the same dialogue click.
 */
@EventBusSubscriber(modid = Empire.MODID)
public final class CivilianInteractions {

    @SubscribeEvent
    static void onEntityInteract(PlayerInteractEvent.EntityInteract event) {
        if (event.getTarget() instanceof CivilianEntity civilian && event.getHand() == InteractionHand.MAIN_HAND
                && event.getItemStack().is(Items.NAME_TAG) && civilian.isAlive()) {
            event.setCancellationResult(civilian.talkTo(event.getEntity()));
            event.setCanceled(true);
        }
    }

    private CivilianInteractions() {
    }
}
