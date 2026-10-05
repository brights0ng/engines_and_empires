package dev.brights0ng.enginesandempires.geophone;

import dev.brights0ng.enginesandempires.oregen.worldgen.ReadingDeposits;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

/**
 * What happens behind every "board of readings" screen: the logbook's, the smart logger's reader screen, and the portable
 * record display's list. Each has a slot for a wind-up reader, the player's inventory, and the same five things to ask
 * for: save the reader's reading onto the board, load one of the board's readings into the reader, delete one, rename
 * one, and clear whatever reading the reader in the slot holds without saving it anywhere.
 *
 * <p>What differs is only where the readings live (on an item, or on a block entity), how many fit, and a few small
 * touches (the sound, the "full" message, anything that should react to a save). Subclasses give those; everything else
 * is decided here, on the server, by {@link Logbook}'s rules. The screen only says what was pressed
 * ({@link ReadingBoardActionPayload}), and nothing it sends is trusted: the entry is looked up by its number, the reader
 * must really be in the slot, and names are tidied here before they are kept.
 *
 * <p>Entries are always named by their number, never their row, so the right entry is changed even if another was deleted
 * meanwhile. Whatever is in the reader slot when the screen closes goes back to the player.
 */
public abstract class ReadingBoardMenu extends AbstractContainerMenu {

    /** The things the screen can ask for. */
    public static final int SAVE = 0;
    public static final int CLEAR_READER = 1;
    public static final int LOAD = 2;
    public static final int DELETE = 3;
    public static final int RENAME = 4;

    protected final Inventory inventory;
    private final int lockedSlot;
    private final Container readerContainer = new SimpleContainer(1);
    private final Slot readerSlot;

    /**
     * @param lockedSlot an inventory slot the player may not take from or put into while the screen is open (the one the
     *                   item holding the readings is in), or -1 for none
     */
    protected ReadingBoardMenu(MenuType<?> type, int containerId, Inventory inventory, int lockedSlot,
                               int readerX, int readerY, int inventoryX, int inventoryY) {
        super(type, containerId);
        this.inventory = inventory;
        this.lockedSlot = lockedSlot;
        this.readerSlot = addSlot(new Slot(readerContainer, 0, readerX, readerY) {
            @Override
            public boolean mayPlace(ItemStack stack) {
                return stack.getItem() instanceof WindupReaderItem;
            }

            @Override
            public int getMaxStackSize() {
                return 1;
            }
        });
        for (int row = 0; row < 3; row++) {
            for (int column = 0; column < 9; column++) {
                addInventorySlot(column + row * 9 + 9, inventoryX + column * 18, inventoryY + row * 18);
            }
        }
        for (int column = 0; column < 9; column++) {
            addInventorySlot(column, inventoryX + column * 18, inventoryY + 58);
        }
    }

    /** An inventory slot, locked if it is the one the readings' item is in. */
    private void addInventorySlot(int index, int x, int y) {
        if (index == lockedSlot) {
            addSlot(new Slot(inventory, index, x, y) {
                @Override
                public boolean mayPickup(Player player) {
                    return false;
                }

                @Override
                public boolean mayPlace(ItemStack stack) {
                    return false;
                }
            });
        } else {
            addSlot(new Slot(inventory, index, x, y));
        }
    }

    // ---- where the readings live: the subclass says ----

    /** The readings, as this side has them. Never null: an empty board if the thing holding them has gone. */
    public abstract Logbook records();

    /** Whether the thing holding the readings is still there, so they can be changed. */
    protected abstract boolean hasStore();

    /** Keeps the readings. Only called on the server, and only while {@link #hasStore()}. */
    protected abstract void setRecords(Logbook records);

    /** The message shown when a reading is refused for want of room. */
    protected abstract String fullMessageKey();

    /** The sound of something being changed. */
    protected abstract void playSound(Player player);

    /** Told what came of each save, for anything that should react to it (the portable display's lights). */
    protected void onSaved(Player player, Logbook.Outcome outcome) {
    }

    /** Whatever is in the reader slot. */
    public ItemStack readerStack() {
        return readerSlot.getItem();
    }

    /** What an entry is called: the name the player gave it, or "Reading" and its number. */
    public static Component displayName(LogbookEntry entry) {
        return entry.hasName() ? Component.literal(entry.name())
                : Component.translatable("gui.engines_and_empires.logbook.entry", entry.number());
    }

    // ---- what the player asks for ----

    /** Something the screen asked for. {@code number} is the entry's number (not its row); {@code name} is only for a rename. */
    public void act(Player player, int action, int number, String name) {
        if (player.level().isClientSide || !hasStore()) {
            return;
        }
        Logbook records = records();
        switch (action) {
            case SAVE -> save(player, records);
            case CLEAR_READER -> clearReader(player);
            case LOAD -> load(player, records, number);
            case DELETE -> delete(player, records, number);
            case RENAME -> rename(player, records, number, name);
            default -> {
            }
        }
    }

    /**
     * Saves the reader's reading, unless the reader has none, or there is no room, or the board already has a reading of that
     * deposit that is as new or newer. A reading that does not say which deposit it is of (one taken before that was kept)
     * has it worked out from where it points first, so it is compared with what the board has like any other.
     */
    private void save(Player player, Logbook records) {
        ItemStack reader = readerSlot.getItem();
        if (!(reader.getItem() instanceof WindupReaderItem)) {
            tell(player, "no_reader");
            return;
        }
        ReaderReading reading = reader.get(SeismicContent.READER_READING.get());
        if (reading == null) {
            tell(player, "no_reading");
            return;
        }
        if (!reading.knowsDeposit() && player instanceof ServerPlayer server) {
            ReaderReading identified = ReadingDeposits.identify(server.getServer(), reading);
            if (identified != reading) {
                // Keep what was worked out on the reader too, so it is not worked out again.
                reader.set(SeismicContent.READER_READING.get(), identified);
                readerSlot.setChanged();
                reading = identified;
            }
        }
        Logbook.Saved saved = records.save(reading);
        switch (saved.outcome()) {
            case FULL -> player.displayClientMessage(Component.translatable(fullMessageKey()), true);
            case ALREADY -> tell(player, "already", displayName(saved.entry()));
            case OLDER -> tell(player, "older", displayName(saved.entry()));
            case ADDED -> {
                setRecords(saved.logbook());
                tell(player, "saved", displayName(saved.entry()));
                playSound(player);
            }
            case REPLACED -> {
                setRecords(saved.logbook());
                tell(player, "updated", displayName(saved.entry()));
                playSound(player);
            }
        }
        onSaved(player, saved.outcome());
    }

    /** Copies a saved reading into the reader, replacing whatever it had. The board keeps it. */
    private void load(Player player, Logbook records, int number) {
        int row = records.indexOfNumber(number);
        if (row < 0) {
            return;
        }
        ItemStack reader = readerSlot.getItem();
        if (!(reader.getItem() instanceof WindupReaderItem)) {
            tell(player, "no_reader");
            return;
        }
        LogbookEntry entry = records.get(row);
        reader.set(SeismicContent.READER_READING.get(), entry.reading());
        readerSlot.setChanged();
        tell(player, "loaded", displayName(entry));
        playSound(player);
    }

    private void delete(Player player, Logbook records, int number) {
        int row = records.indexOfNumber(number);
        if (row < 0) {
            return;
        }
        LogbookEntry entry = records.get(row);
        setRecords(records.delete(row));
        tell(player, "deleted", displayName(entry));
        playSound(player);
    }

    /** Gives an entry a new name. The name is tidied here, and an empty one puts the entry back to its default name. */
    private void rename(Player player, Logbook records, int number, String name) {
        if (records.indexOfNumber(number) < 0) {
            return;
        }
        Logbook renamed = records.rename(number, name);
        setRecords(renamed);
        tell(player, "renamed", displayName(renamed.get(renamed.indexOfNumber(number))));
        playSound(player);
    }

    /**
     * Wipes whatever reading the reader in the slot holds, without saving it anywhere. Once cleared, that reading is gone unless
     * it was already saved somewhere.
     */
    private void clearReader(Player player) {
        ItemStack reader = readerSlot.getItem();
        if (!(reader.getItem() instanceof WindupReaderItem)) {
            tell(player, "no_reader");
            return;
        }
        if (reader.get(SeismicContent.READER_READING.get()) == null) {
            tell(player, "reader_empty");
            return;
        }
        reader.remove(SeismicContent.READER_READING.get());
        readerSlot.setChanged();
        tell(player, "reader_cleared");
        playSound(player);
    }

    /** Every board says the same things, so they share the logbook's messages. */
    private static void tell(Player player, String message, Object... arguments) {
        player.displayClientMessage(Component.translatable("message.engines_and_empires.logbook." + message, arguments), true);
    }

    // ---- moving items about ----

    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        Slot slot = slots.get(index);
        if (slot == null || !slot.hasItem()) {
            return ItemStack.EMPTY;
        }
        ItemStack stack = slot.getItem();
        ItemStack original = stack.copy();
        if (index == 0) {
            // Out of the reader slot, into the inventory.
            if (!moveItemStackTo(stack, 1, slots.size(), true)) {
                return ItemStack.EMPTY;
            }
        } else if (stack.getItem() instanceof WindupReaderItem) {
            // From the inventory, into the reader slot. Only a reader goes there.
            if (!moveItemStackTo(stack, 0, 1, false)) {
                return ItemStack.EMPTY;
            }
        } else {
            return ItemStack.EMPTY;
        }
        if (stack.isEmpty()) {
            slot.setByPlayer(ItemStack.EMPTY);
        } else {
            slot.setChanged();
        }
        return original;
    }

    @Override
    public void removed(Player player) {
        super.removed(player);
        clearContainer(player, readerContainer);
    }
}
