package dev.brights0ng.enginesandempires.geophone;

import java.util.List;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import io.netty.buffer.ByteBuf;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
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
 * The portable record display: a handheld version of the smart logger, holding up to {@link #CAPACITY} readings.
 *
 * <p>Right-click it to hold it up and work it by hand (see {@code PrdSession}, on the client): its own screen, buttons and
 * glass are the interface, with a map page and a records page. Deleting and renaming readings come to the server as
 * {@link PrdActionPayload}s ({@link #act}). Reading from and writing to a wind-up reader wait for the floppy drive; until
 * then the old records list with its reader slot ({@link PrdMenu}, {@link #openRecords}) is still here, but nothing opens it.
 *
 * <p>It can also be docked in a smart logger, which takes every reading on it that the logger lacks or has an older reading
 * of, and lets the player send the logger's readings onto it by double-clicking them on the logger's map.
 *
 * <p>Three small lights on its face (red, yellow, green) show what came of the last thing done to it: see
 * {@link PrdSignal}. The readings, and the last flash, live on the item, so they go wherever it goes.
 */
public class PrdItem extends Item {

    /** How many readings it holds. */
    public static final int CAPACITY = 16;

    /** A display with nothing on it. */
    public static final Logbook EMPTY = Logbook.empty(CAPACITY);

    /** How its readings are saved, and sent to players. */
    public static final Codec<Logbook> RECORDS_CODEC = LogbookCodecs.logbook(CAPACITY);
    public static final StreamCodec<ByteBuf, Logbook> RECORDS_STREAM = LogbookCodecs.stream(CAPACITY);

    /** How its last flash is saved, and sent to players. */
    public static final Codec<PrdSignal> SIGNAL_CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Codec.STRING.xmap(PrdItem::pattern, PrdSignal.Pattern::name).fieldOf("pattern").forGetter(PrdSignal::pattern),
            Codec.LONG.fieldOf("start").forGetter(PrdSignal::start)
    ).apply(instance, PrdSignal::new));
    public static final StreamCodec<ByteBuf, PrdSignal> SIGNAL_STREAM = StreamCodec.composite(
            ByteBufCodecs.VAR_INT.map(i -> PrdSignal.Pattern.values()[Math.floorMod(i, PrdSignal.Pattern.values().length)],
                    PrdSignal.Pattern::ordinal), PrdSignal::pattern,
            ByteBufCodecs.VAR_LONG, PrdSignal::start,
            PrdSignal::new);

    private static PrdSignal.Pattern pattern(String name) {
        try {
            return PrdSignal.Pattern.valueOf(name);
        } catch (IllegalArgumentException e) {
            return PrdSignal.Pattern.GREEN_BRIEF;
        }
    }

    public PrdItem(Properties properties) {
        super(properties);
    }

    /** Its readings. */
    public static Logbook records(ItemStack stack) {
        return stack.getOrDefault(SeismicContent.PRD_DATA.get(), EMPTY);
    }

    public static void setRecords(ItemStack stack, Logbook records) {
        stack.set(SeismicContent.PRD_DATA.get(), records);
    }

    /** Starts a flash of its lights, now. */
    public static void signal(ItemStack stack, Level level, PrdSignal.Pattern pattern) {
        stack.set(SeismicContent.PRD_SIGNAL.get(), new PrdSignal(pattern, level.getGameTime()));
    }

    // ---- worked by hand: see PrdActionPayload ----

    /** Take a reading off it. */
    public static final int DELETE = 0;
    /** Give a reading a new name (an empty one puts back its default name). */
    public static final int RENAME = 1;

    /**
     * Something asked for by a player working the display in their hands. On the server only, and only for a display in
     * the selected hotbar slot or the off hand; anything else is ignored. The entry is found by its number, and names are
     * tidied by {@link Logbook#rename}. Returns whether anything changed.
     */
    public static boolean act(Player player, int slot, int action, int number, String name) {
        if (player.level().isClientSide || (slot != player.getInventory().selected && slot != Inventory.SLOT_OFFHAND)) {
            return false;
        }
        ItemStack stack = player.getInventory().getItem(slot);
        if (!(stack.getItem() instanceof PrdItem)) {
            return false;
        }
        Logbook records = records(stack);
        int row = records.indexOfNumber(number);
        if (row < 0) {
            return false;
        }
        switch (action) {
            case DELETE -> {
                LogbookEntry entry = records.get(row);
                setRecords(stack, records.delete(row));
                tell(player, "deleted", ReadingBoardMenu.displayName(entry));
            }
            case RENAME -> {
                Logbook renamed = records.rename(number, name);
                setRecords(stack, renamed);
                tell(player, "renamed", ReadingBoardMenu.displayName(renamed.get(renamed.indexOfNumber(number))));
            }
            default -> {
                return false;
            }
        }
        player.level().playSound(null, player.blockPosition(), SoundEvents.UI_CARTOGRAPHY_TABLE_TAKE_RESULT, SoundSource.PLAYERS,
                0.4F, 1.6F);
        return true;
    }

    private static void tell(Player player, String message, Object... arguments) {
        player.displayClientMessage(Component.translatable("message.engines_and_empires.logbook." + message, arguments), true);
    }

    /** The light its last flash has lit at game time {@code now}, if any. */
    public static PrdSignal.Light flashing(ItemStack stack, long now) {
        PrdSignal signal = stack.get(SeismicContent.PRD_SIGNAL.get());
        return signal == null ? PrdSignal.Light.NONE : signal.lit(now);
    }

    /** Opens the map, on the client. The server has nothing to do: the map only draws what the item already carries. */
    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        if (level.isClientSide) {
            dev.brights0ng.enginesandempires.geophone.client.PrdClient.openMap(hand);
        }
        return InteractionResultHolder.sidedSuccess(stack, level.isClientSide);
    }

    /** Opens the records list for the display at this inventory slot. On the server; see {@link OpenPrdRecordsPayload}. */
    public static void openRecords(Player player, int slot) {
        if (player instanceof ServerPlayer server) {
            server.openMenu(new SimpleMenuProvider((id, inventory, ignored) -> new PrdMenu(id, inventory, slot),
                    Component.translatable("container.engines_and_empires.portable_record_display")),
                    buffer -> buffer.writeVarInt(slot));
        }
    }

    /** The inventory slot a hand's item is in: the selected hotbar slot, or the off hand. */
    public static int slotOf(Player player, InteractionHand hand) {
        return hand == InteractionHand.MAIN_HAND ? player.getInventory().selected : Inventory.SLOT_OFFHAND;
    }

    /**
     * Its lights flashing changes the item, which would otherwise make the hand dip and come back up each time, as if a
     * different item had been picked up. Only a real change of item does that.
     */
    @Override
    public boolean shouldCauseReequipAnimation(ItemStack oldStack, ItemStack newStack, boolean slotChanged) {
        return slotChanged || !oldStack.is(newStack.getItem());
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> tooltip, TooltipFlag flag) {
        Logbook records = records(stack);
        tooltip.add(Component.translatable("item.engines_and_empires.logbook.tooltip", records.size(), CAPACITY)
                .withStyle(records.isFull() ? ChatFormatting.RED : ChatFormatting.GRAY));
    }
}
