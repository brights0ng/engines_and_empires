package dev.brights0ng.enginesandempires.geophone;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

/**
 * What happens behind the logbook screen: a board of up to {@link Logbook#MAX_ENTRIES} readings kept on the book itself.
 * Everything it can do is {@link ReadingBoardMenu}'s; this only says where the book is and how it sounds.
 */
public class LogbookMenu extends HeldRecordsMenu {

    /** Where the slots are, shared with the screen that draws them. */
    public static final int READER_X = 185;
    public static final int READER_Y = 32;
    public static final int INVENTORY_X = 31;
    public static final int INVENTORY_Y = 174;

    /** From the network, on the client: the server says where the book is. */
    public LogbookMenu(int containerId, Inventory inventory, RegistryFriendlyByteBuf data) {
        this(containerId, inventory, data.readVarInt());
    }

    public LogbookMenu(int containerId, Inventory inventory, int logbookIndex) {
        super(SeismicContent.LOGBOOK_MENU.get(), containerId, inventory, logbookIndex, SeismicContent.LOGBOOK_DATA, Logbook.EMPTY,
                READER_X, READER_Y, INVENTORY_X, INVENTORY_Y);
    }

    @Override
    protected boolean holds(ItemStack stack) {
        return stack.getItem() instanceof LogbookItem;
    }

    @Override
    protected String fullMessageKey() {
        return "message.engines_and_empires.logbook.full";
    }

    @Override
    protected void playSound(Player player) {
        player.level().playSound(null, player.blockPosition(), SoundEvents.BOOK_PAGE_TURN, SoundSource.PLAYERS, 0.6F, 1.0F);
    }
}
