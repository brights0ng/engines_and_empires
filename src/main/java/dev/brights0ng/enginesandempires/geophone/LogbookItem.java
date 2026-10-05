package dev.brights0ng.enginesandempires.geophone;

import java.util.List;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;

/**
 * A logbook: holds up to {@link Logbook#MAX_ENTRIES} readings from wind-up readers. Right-click to open it; there a reader can
 * be put in a slot, its reading saved to the book, and a saved reading loaded into it, or deleted. See {@link LogbookMenu}.
 *
 * <p>The readings live on the item, so they go wherever the book goes: it can be dropped, traded or lost, and the readings with it.
 */
public class LogbookItem extends Item {

    public LogbookItem(Properties properties) {
        super(properties);
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack book = player.getItemInHand(hand);
        if (player instanceof ServerPlayer server) {
            // The menu finds the book again by where it is in the inventory: the selected slot, or the off hand.
            int index = hand == InteractionHand.MAIN_HAND ? player.getInventory().selected : Inventory.SLOT_OFFHAND;
            server.openMenu(new SimpleMenuProvider((id, inventory, ignored) -> new LogbookMenu(id, inventory, index),
                    Component.translatable("container.engines_and_empires.logbook")), buffer -> buffer.writeVarInt(index));
        }
        return InteractionResultHolder.sidedSuccess(book, level.isClientSide);
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> tooltip, TooltipFlag flag) {
        Logbook logbook = stack.getOrDefault(SeismicContent.LOGBOOK_DATA.get(), Logbook.EMPTY);
        tooltip.add(Component.translatable("item.engines_and_empires.logbook.tooltip", logbook.size(), Logbook.MAX_ENTRIES)
                .withStyle(ChatFormatting.GRAY));
    }
}
