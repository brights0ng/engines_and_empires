package dev.brights0ng.enginesandempires.geophone;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

/**
 * What happens behind the portable record display's records list, opened from its map. The same board as the logbook's
 * (see {@link ReadingBoardMenu}), with room for {@link PrdItem#CAPACITY} readings, laid out like the smart logger's.
 *
 * <p>Every save flashes the display's lights: green for a reading taken, yellow for one it already has (or has newer),
 * red when there is no room. See {@link PrdSignal}.
 */
public class PrdMenu extends HeldRecordsMenu {

    /** Where the slots are, shared with the screen that draws them. The same as the smart logger's screen. */
    public static final int READER_X = SmartLoggerMenu.READER_X;
    public static final int READER_Y = SmartLoggerMenu.READER_Y;
    public static final int INVENTORY_X = SmartLoggerMenu.INVENTORY_X;
    public static final int INVENTORY_Y = SmartLoggerMenu.INVENTORY_Y;

    /** From the network, on the client: the server says where the display is. */
    public PrdMenu(int containerId, Inventory inventory, RegistryFriendlyByteBuf data) {
        this(containerId, inventory, data.readVarInt());
    }

    public PrdMenu(int containerId, Inventory inventory, int slot) {
        super(SeismicContent.PRD_MENU.get(), containerId, inventory, slot, SeismicContent.PRD_DATA, PrdItem.EMPTY,
                READER_X, READER_Y, INVENTORY_X, INVENTORY_Y);
    }

    @Override
    protected boolean holds(ItemStack stack) {
        return stack.getItem() instanceof PrdItem;
    }

    @Override
    protected String fullMessageKey() {
        return "message.engines_and_empires.portable_record_display.full";
    }

    @Override
    protected void playSound(Player player) {
        player.level().playSound(null, player.blockPosition(), SoundEvents.UI_CARTOGRAPHY_TABLE_TAKE_RESULT, SoundSource.PLAYERS,
                0.4F, 1.6F);
    }

    @Override
    protected void onSaved(Player player, Logbook.Outcome outcome) {
        PrdItem.signal(itemStack(), player.level(), PrdSignal.forOutcome(outcome));
    }
}
