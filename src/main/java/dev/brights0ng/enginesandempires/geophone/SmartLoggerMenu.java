package dev.brights0ng.enginesandempires.geophone;

import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;

/**
 * What happens behind the smart logger's reader screen, opened with the reader button on its bar. It works like the
 * logbook's (see {@link ReadingBoardMenu}); the logger's readings live on its block entity rather than on an item, and there
 * can be many more of them.
 */
public class SmartLoggerMenu extends ReadingBoardMenu {

    /** Where the slots are, shared with the screen that draws them. The screen is wider than the logbook's. */
    public static final int READER_X = 240;
    public static final int READER_Y = 32;
    public static final int INVENTORY_X = 60;
    public static final int INVENTORY_Y = 174;

    private final BlockPos pos;

    /** From the network, on the client: the server says where the logger is. */
    public SmartLoggerMenu(int containerId, Inventory inventory, RegistryFriendlyByteBuf data) {
        this(containerId, inventory, data.readBlockPos());
    }

    public SmartLoggerMenu(int containerId, Inventory inventory, BlockPos pos) {
        super(SeismicContent.SMART_LOGGER_MENU.get(), containerId, inventory, -1, READER_X, READER_Y, INVENTORY_X, INVENTORY_Y);
        this.pos = pos.immutable();
    }

    /** The logger, as this side has it, or null if it is gone. */
    public SmartLoggerBlockEntity logger() {
        return inventory.player.level().getBlockEntity(pos) instanceof SmartLoggerBlockEntity logger && logger.isMaster()
                ? logger : null;
    }

    @Override
    public Logbook records() {
        SmartLoggerBlockEntity logger = logger();
        return logger == null ? Logbook.empty(LoggerListening.CAPACITY) : logger.records();
    }

    @Override
    protected boolean hasStore() {
        return logger() != null;
    }

    @Override
    protected void setRecords(Logbook records) {
        SmartLoggerBlockEntity logger = logger();
        if (logger != null) {
            logger.setRecords(records);
        }
    }

    @Override
    protected String fullMessageKey() {
        return "message.engines_and_empires.smart_logger.full";
    }

    @Override
    protected void playSound(Player player) {
        player.level().playSound(null, player.blockPosition(), SoundEvents.UI_CARTOGRAPHY_TABLE_TAKE_RESULT, SoundSource.PLAYERS,
                0.5F, 1.2F);
    }

    @Override
    public boolean stillValid(Player player) {
        SmartLoggerBlockEntity logger = logger();
        if (logger == null) {
            return false;
        }
        Vec3 middle = logger.middle();
        return player.distanceToSqr(middle) <= 10.0 * 10.0;
    }
}
