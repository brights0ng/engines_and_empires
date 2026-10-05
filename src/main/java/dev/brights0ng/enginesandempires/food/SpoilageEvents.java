package dev.brights0ng.enginesandempires.food;

import dev.brights0ng.enginesandempires.EnginesAndEmpiresMod;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.Slot;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.entity.player.PlayerContainerEvent;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;

/**
 * Where food gets stamped and tidied (see {@link Spoilage#normalize}): new food is stamped as born when it is first seen, and
 * food whose cohorts have aged into the same stage is merged. Nothing needs to tick for food to age (its stage comes from the
 * clock), so this is only housekeeping, all on the server.
 */
@EventBusSubscriber(modid = EnginesAndEmpiresMod.MODID)
public final class SpoilageEvents {

    /** How often a player's inventory is tidied, in ticks. */
    private static final int INVENTORY_INTERVAL = 20;

    @SubscribeEvent
    static void onPlayerTick(PlayerTickEvent.Post event) {
        Player player = event.getEntity();
        if (player.level().isClientSide() || player.tickCount % INVENTORY_INTERVAL != 0) {
            return;
        }
        Inventory inventory = player.getInventory();
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            Spoilage.normalize(inventory.getItem(i));
        }
        Spoilage.normalize(player.containerMenu.getCarried());
    }

    /** A container's food is stamped (and tidied) when someone opens it. */
    @SubscribeEvent
    static void onContainerOpen(PlayerContainerEvent.Open event) {
        if (event.getEntity().level().isClientSide()) {
            return;
        }
        for (Slot slot : event.getContainer().slots) {
            Spoilage.normalize(slot.getItem());
        }
    }

    /** Food dropped into the world (mob drops, broken containers, a crafted result thrown out) is stamped as it appears. */
    @SubscribeEvent
    static void onEntityJoin(EntityJoinLevelEvent event) {
        if (!event.getLevel().isClientSide() && event.getEntity() instanceof ItemEntity item) {
            Spoilage.normalize(item.getItem());
        }
    }

    private SpoilageEvents() {
    }
}
