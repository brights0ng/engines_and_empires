package dev.brights0ng.enginesandempires.food;

import dev.brights0ng.enginesandempires.EnginesAndEmpiresMod;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

/** The message food spoilage sends from client to server: choosing which group of a food stack is on top. */
@EventBusSubscriber(modid = EnginesAndEmpiresMod.MODID)
public final class FoodNetwork {

    /** Bumped if a message's format ever changes. */
    private static final String VERSION = "1";

    @SubscribeEvent
    static void onRegisterPayloads(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar registrar = event.registrar(VERSION);
        registrar.playToServer(FoodTopPayload.TYPE, FoodTopPayload.STREAM_CODEC,
                (payload, context) -> rotateTop(context.player(), payload.containerId(), payload.slot(), payload.staler()));
    }

    /**
     * Puts the next group of the food in a slot of the player's open menu on top. Runs on the server's main thread; the menu
     * sends the change back to the client itself. Returns whether anything changed.
     */
    public static boolean rotateTop(Player player, int containerId, int slotIndex, boolean staler) {
        AbstractContainerMenu menu = player.containerMenu;
        if (menu.containerId != containerId || slotIndex < 0 || slotIndex >= menu.slots.size() || !menu.stillValid(player)) {
            return false;
        }
        Slot slot = menu.getSlot(slotIndex);
        ItemStack stack = slot.getItem();
        if (!Spoilage.isSpoilable(stack) || !slot.mayPickup(player)) {
            return false;
        }
        long now = SpoilClock.now();
        if (now < 0) {
            return false;
        }
        FoodFreshness view = Spoilage.view(stack, now);
        if (view.cohorts().size() < 2) {
            return false;
        }
        Spoilage.write(stack, view.rotateTop(staler ? 1 : -1));
        slot.setChanged();
        return true;
    }

    private FoodNetwork() {
    }
}
