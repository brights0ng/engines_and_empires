package dev.brights0ng.enginesandempires.geophone;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import com.simibubi.create.content.kinetics.base.KineticBlockEntity;

import dev.brights0ng.enginesandempires.oregen.worldgen.ReadingDeposits;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.Containers;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

/**
 * The smart logger's block entity. Every quarter of the desk has one, since every quarter is a cog in the kinetic network,
 * but only the master's (the front-left quarter) does anything: it holds the readings, the map's view, the display switch
 * and the buttons. The others only turn.
 *
 * <h2>Listening</h2>
 * While it turns, a vibration heard by at least three geophones near it (how near depends on its speed, see
 * {@link LoggerListening}) gives it a reading of every deposit they heard, not only the nearest one as a wind-up reader
 * takes. Each arrives when the last geophone it needed has lit, the same as for a reader, and is only kept if the logger is
 * still turning then. They are kept by the same rules as a logbook's ({@link Logbook}), with room for
 * {@link LoggerListening#CAPACITY}: a newer reading of a deposit it has replaces the old one, and a new deposit is refused
 * once it is full.
 *
 * <h2>The display</h2>
 * On while the power button has it switched on, or while a redstone signal reaches any quarter. It shows the readings on a
 * map ({@link LoggerDisplay}). Its controls are worked out on the client ({@code client.SmartLoggerControls}): drag with the
 * left button, scroll to zoom, sneak-right-click to recentre on the logger, right-click a reading to select it. The view
 * belongs to the logger, not to whoever is looking, so everyone sees the same map; the selection is each player's own.
 *
 * <h2>The buttons</h2>
 * Two small bronze buttons, on the front half of the bar along the right-hand side, working like stone buttons: pressed,
 * they stay down for a second, and cannot be pressed again until they come up. See {@link LoggerDisplay.Button}.
 *
 * <h2>The dock</h2>
 * The back half of the bar holds a portable record display ({@link PrdItem}): right-click it with one to put it in, and
 * with an empty hand to take it back. Breaking the logger drops it. As it goes in, every reading on it that the logger
 * lacks, or has an older reading of, is loaded into the logger (by the usual rules, so it is refused once the logger is
 * full), and the display's green lamp flashes briefly. While it is docked, right-clicking a selected reading again sends
 * that reading to the display: its lamps say how that went, green for taken,
 * yellow if it already had that reading or a newer one, red if it is full. See {@link PrdSignal}.
 *
 * <h2>The goggles</h2>
 * Besides the counts and state, while the display is on they give the map's scale and, when the crosshair is on the map,
 * where it is pointing in the world and the reading under it, if any: its name, ore, position and confidence.
 */
public class SmartLoggerBlockEntity extends KineticBlockEntity {

    /** A reading on its way: heard, but not yet due, since the last geophone it needs has not lit yet. */
    private record Pending(long due, ReaderReading reading) {
    }

    private Logbook records = Logbook.empty(LoggerListening.CAPACITY);
    private boolean displaySwitch;
    private boolean redstone;
    private LoggerDisplay.View view;

    /** The portable record display in the dock, or empty. */
    private ItemStack docked = ItemStack.EMPTY;

    /**
     * On the client: until when (in wall-clock milliseconds) the view this player has just set by dragging or zooming is kept,
     * whatever the server sends. Without this the server's echo of a slightly older view would make the map jump back mid-drag.
     */
    private long localViewUntil;

    /** When each button comes back up, in game time, or 0 if it is up. */
    private final long[] buttonUp = new long[LoggerDisplay.Button.values().length];

    private final List<Pending> pending = new ArrayList<>();

    /** How many new deposits it has had to turn away since it filled up. Shown on the goggles. */
    private int refused;

    private boolean syncQueued;

    public SmartLoggerBlockEntity(BlockEntityType<?> type, BlockPos pos, BlockState state) {
        super(type, pos, state);
    }

    // ---- which quarter ----

    public boolean isMaster() {
        return getBlockState().getValue(SmartLoggerBlock.PART) == SmartLoggerBlock.Part.FRONT_LEFT;
    }

    public Direction facing() {
        return getBlockState().getValue(SmartLoggerBlock.HORIZONTAL_FACING);
    }

    public LoggerDisplay.Frame frame() {
        return SmartLoggerBlock.frame(facing());
    }

    /** The middle of the whole desk, half way up: where it listens from, and what its map starts centred on. */
    public Vec3 middle() {
        Direction right = SmartLoggerBlock.right(facing());
        Direction forward = SmartLoggerBlock.forward(facing());
        return new Vec3(worldPosition.getX() + 0.5 + 0.5 * (right.getStepX() + forward.getStepX()),
                worldPosition.getY() + 0.5,
                worldPosition.getZ() + 0.5 + 0.5 * (right.getStepZ() + forward.getStepZ()));
    }

    // ---- life ----

    @Override
    public void initialize() {
        super.initialize();
        if (level instanceof ServerLevel server && isMaster()) {
            LoggerRegistry.add(server, worldPosition, middle());
            checkRedstone();
        }
    }

    @Override
    public void invalidate() {
        super.invalidate();
        if (level instanceof ServerLevel server) {
            LoggerRegistry.remove(server, worldPosition);
        }
    }

    @Override
    public void tick() {
        super.tick();
        if (level == null || level.isClientSide || !isMaster()) {
            return;
        }
        long now = level.getGameTime();

        // Readings whose last geophone has now lit. Only kept if it is still turning to take them down.
        Iterator<Pending> waiting = pending.iterator();
        while (waiting.hasNext()) {
            Pending next = waiting.next();
            if (next.due() <= now) {
                waiting.remove();
                if (isListening()) {
                    keep(next.reading());
                }
            }
        }

        // Buttons coming back up.
        for (int i = 0; i < buttonUp.length; i++) {
            if (buttonUp[i] != 0 && now >= buttonUp[i]) {
                buttonUp[i] = 0;
                level.playSound(null, worldPosition, SoundEvents.STONE_BUTTON_CLICK_OFF, SoundSource.BLOCKS, 0.3F, 0.5F);
                syncQueued = true;
            }
        }

        if (syncQueued) {
            syncQueued = false;
            sendData();
        }
    }

    /** Only the master drives the stress on the network, and it takes the whole desk's share; the rest just turn. */
    @Override
    public float calculateStressApplied() {
        if (!isMaster()) {
            this.lastStressApplied = 0;
            return 0;
        }
        return super.calculateStressApplied();
    }

    // ---- listening ----

    /** Whether it is turning, so hears what the geophones near it hear. */
    public boolean isListening() {
        return listenRadius() > 0;
    }

    /** How far from its middle a geophone can be for it to hear through it, right now. Zero when it is not turning. */
    public double listenRadius() {
        return LoggerListening.radius(getSpeed());
    }

    /** A reading heard from a vibration, due at game time {@code due}. See {@link SeismicShots}. */
    public void receive(long due, ReaderReading reading) {
        pending.add(new Pending(due, reading));
    }

    /** Keeps a reading, if the rules let it: see {@link Logbook#save}. */
    Logbook.Outcome keep(ReaderReading reading) {
        Logbook.Saved saved = records.save(reading);
        switch (saved.outcome()) {
            case ADDED, REPLACED -> {
                records = saved.logbook();
                changed();
            }
            case FULL -> {
                refused++;
                setChanged();
            }
            default -> {
            }
        }
        return saved.outcome();
    }

    public Logbook records() {
        return records;
    }

    /** Replaces the readings wholesale: the reader screen's saves, deletes and renames (and the game tests). */
    public void setRecords(Logbook records) {
        this.records = records;
        if (!records.isFull()) {
            refused = 0;
        }
        changed();
    }

    // ---- the display ----

    public boolean displayOn() {
        return displaySwitch || redstone;
    }

    public LoggerDisplay.View view() {
        if (view == null) {
            Vec3 middle = middle();
            view = LoggerDisplay.home(middle.x, middle.z);
        }
        return view;
    }

    /** Looks for a redstone signal reaching any quarter of the desk. */
    public void checkRedstone() {
        if (level == null || level.isClientSide) {
            return;
        }
        boolean powered = false;
        for (SmartLoggerBlock.Part part : SmartLoggerBlock.Part.values()) {
            if (level.hasNeighborSignal(SmartLoggerBlock.partPos(worldPosition, facing(), part))) {
                powered = true;
                break;
            }
        }
        if (powered != redstone) {
            redstone = powered;
            changed();
        }
    }

    // ---- the buttons ----

    /** Whether a button is down, as of this game time. Used by the renderer, on the client. */
    public boolean buttonDown(LoggerDisplay.Button button, long now) {
        return buttonUp[button.ordinal()] > now;
    }

    /**
     * Something clicked on the desk, anywhere: a button on the bar, or the map. On the client this only says whether the
     * click did anything, so the hand swings; the server does it.
     */
    public InteractionResult use(Player player, BlockHitResult hit) {
        return use(player, InteractionHand.MAIN_HAND, hit);
    }

    /** The same, with the hand that clicked, whose item may be going into the dock. */
    public InteractionResult use(Player player, InteractionHand hand, BlockHitResult hit) {
        if (hit.getDirection() != Direction.UP || level == null) {
            return InteractionResult.PASS;
        }
        Vec3 middle = middle();
        double[] local = frame().local(middle.x, middle.z, hit.getLocation().x, hit.getLocation().z);
        if (LoggerDisplay.dockAt(local[0], local[1])) {
            return useDock(player, hand);
        }
        LoggerDisplay.Button button = LoggerDisplay.buttonAt(local[0], local[1]);
        if (button != null) {
            if (!level.isClientSide) {
                press(button, player);
            }
            return InteractionResult.sidedSuccess(level.isClientSide);
        }
        // The map's clicks are worked out on the client, and anything that changes the logger comes back as a message.
        return LoggerDisplay.inScreenArea(local[0], local[1]) ? InteractionResult.sidedSuccess(level.isClientSide)
                : InteractionResult.PASS;
    }

    private void press(LoggerDisplay.Button button, Player player) {
        long now = level.getGameTime();
        if (buttonUp[button.ordinal()] > now) {
            return; // still down: a stone button cannot be pressed again until it comes up
        }
        buttonUp[button.ordinal()] = now + LoggerDisplay.BUTTON_PRESS_TICKS;
        level.playSound(null, worldPosition, SoundEvents.STONE_BUTTON_CLICK_ON, SoundSource.BLOCKS, 0.3F, 0.6F);
        syncQueued = true;
        switch (button) {
            case POWER -> {
                displaySwitch = !displaySwitch;
                changed();
            }
            case READER -> player.openMenu(new SimpleMenuProvider(
                    (id, inventory, opener) -> new SmartLoggerMenu(id, inventory, worldPosition),
                    Component.translatable("container.engines_and_empires.smart_logger")),
                    buffer -> buffer.writeBlockPos(worldPosition));
        }
    }

    // ---- the map's controls, as asked for by a client (see LoggerMapPayloads) ----

    /** Whether a player is near enough to work the map. */
    public boolean inReach(Player player) {
        return player.distanceToSqr(middle()) <= 10.0 * 10.0;
    }

    /**
     * A new view of the map, dragged, zoomed or recentred by a player. Refused while the display is off, or if it is not a
     * sane view. Returns whether it was taken.
     */
    public boolean setView(Player player, LoggerDisplay.View wanted) {
        if (!displayOn() || !LoggerDisplay.sane(wanted)) {
            return false;
        }
        if (!wanted.equals(view)) {
            view = wanted;
            changed();
        }
        return true;
    }

    /** On the client: a view this player has just set, kept for a moment whatever the server sends meanwhile. */
    public void setViewLocally(LoggerDisplay.View wanted) {
        view = wanted;
        localViewUntil = System.currentTimeMillis() + 400;
    }

    /**
     * Sends the entry with this number to the docked display: a selected reading right-clicked again. Nothing happens if no
     * display is docked, the display is off, or there is no such entry. Returns what came of it, or null if nothing did.
     */
    public Logbook.Outcome sendToDisplay(Player player, int number) {
        int row = records.indexOfNumber(number);
        if (docked.isEmpty() || !displayOn() || row < 0) {
            return null;
        }
        return send(player, records.get(row));
    }

    // ---- the dock ----

    /** The portable record display in the dock, or empty. */
    public ItemStack docked() {
        return docked;
    }

    /** A right-click on the dock: a display goes in, or an empty hand takes it back out. Anything else is not for the dock. */
    private InteractionResult useDock(Player player, InteractionHand hand) {
        ItemStack held = player.getItemInHand(hand);
        if (held.getItem() instanceof PrdItem && docked.isEmpty()) {
            if (!level.isClientSide) {
                dock(player, held.split(1));
            }
            return InteractionResult.sidedSuccess(level.isClientSide);
        }
        if (held.isEmpty() && !docked.isEmpty()) {
            if (!level.isClientSide) {
                player.setItemInHand(hand, docked);
                docked = ItemStack.EMPTY;
                level.playSound(null, worldPosition, SoundEvents.ITEM_FRAME_REMOVE_ITEM, SoundSource.BLOCKS, 0.6F, 1.2F);
                changed();
            }
            return InteractionResult.sidedSuccess(level.isClientSide);
        }
        return InteractionResult.PASS;
    }

    /**
     * Puts a display in the dock, and loads into the logger every reading on it that the logger lacks or has an older reading
     * of. A reading that does not say which deposit it is of has that worked out first, so it is compared like any other. A
     * new one keeps the name it had on the display.
     */
    void dock(Player player, ItemStack display) {
        docked = display;
        int added = 0;
        int updated = 0;
        for (LogbookEntry entry : PrdItem.records(display).entries()) {
            ReaderReading reading = entry.reading();
            if (!reading.knowsDeposit() && level instanceof ServerLevel server) {
                reading = ReadingDeposits.identify(server.getServer(), reading);
            }
            Logbook.Saved saved = records.save(reading);
            switch (saved.outcome()) {
                case ADDED -> {
                    records = entry.hasName() ? saved.logbook().rename(saved.entry().number(), entry.name()) : saved.logbook();
                    added++;
                }
                case REPLACED -> {
                    records = saved.logbook();
                    updated++;
                }
                case FULL -> refused++;
                default -> {
                }
            }
        }
        PrdItem.signal(docked, level, PrdSignal.Pattern.GREEN_BRIEF);
        if (player != null) {
            player.displayClientMessage(Component.translatable("message.engines_and_empires.smart_logger.uploaded", added, updated), true);
        }
        level.playSound(null, worldPosition, SoundEvents.ITEM_FRAME_ADD_ITEM, SoundSource.BLOCKS, 0.6F, 1.0F);
        changed();
    }

    /** Sends one of the logger's readings to the docked display, by the usual rules, and flashes its lamps to say how it went. */
    Logbook.Outcome send(Player player, LogbookEntry entry) {
        Logbook onDisplay = PrdItem.records(docked);
        Logbook.Saved saved = onDisplay.save(entry.reading());
        switch (saved.outcome()) {
            case ADDED -> PrdItem.setRecords(docked, entry.hasName()
                    ? saved.logbook().rename(saved.entry().number(), entry.name()) : saved.logbook());
            case REPLACED -> PrdItem.setRecords(docked, saved.logbook());
            default -> {
            }
        }
        PrdItem.signal(docked, level, PrdSignal.forOutcome(saved.outcome()));
        if (player != null) {
            Component name = ReadingBoardMenu.displayName(entry);
            player.displayClientMessage(switch (saved.outcome()) {
                case ADDED, REPLACED -> Component.translatable("message.engines_and_empires.smart_logger.sent", name);
                case ALREADY, OLDER -> Component.translatable("message.engines_and_empires.smart_logger.display_has", name);
                case FULL -> Component.translatable("message.engines_and_empires.portable_record_display.full");
            }, true);
        }
        level.playSound(null, worldPosition, SoundEvents.UI_CARTOGRAPHY_TABLE_TAKE_RESULT, SoundSource.BLOCKS, 0.4F,
                saved.outcome() == Logbook.Outcome.ADDED || saved.outcome() == Logbook.Outcome.REPLACED ? 1.6F : 0.7F);
        changed();
        return saved.outcome();
    }

    /** Drops the docked display where the logger was. Called as the logger is broken. */
    void dropDocked() {
        if (!docked.isEmpty() && level != null) {
            Vec3 middle = middle();
            Containers.dropItemStack(level, middle.x, middle.y + 0.5, middle.z, docked);
            docked = ItemStack.EMPTY;
        }
    }

    /** Saved and sent to players watching, once this tick is over. */
    private void changed() {
        setChanged();
        syncQueued = true;
    }

    // ---- rendering ----

    /** The whole desk, plus the buttons standing above it. */
    @Override
    protected AABB createRenderBoundingBox() {
        Direction right = SmartLoggerBlock.right(facing());
        Direction forward = SmartLoggerBlock.forward(facing());
        return new AABB(worldPosition).minmax(new AABB(worldPosition.relative(right).relative(forward))).inflate(0.1)
                .expandTowards(0, 0.25, 0);
    }

    // ---- goggles ----

    @Override
    public boolean addToGoggleTooltip(List<Component> tooltip, boolean isPlayerSneaking) {
        super.addToGoggleTooltip(tooltip, isPlayerSneaking);
        if (!isMaster()) {
            return true;
        }
        tooltip.add(line(Component.translatable(LANG + "readings", records.size(), records.capacity())
                .withStyle(records.isFull() ? ChatFormatting.RED : ChatFormatting.GRAY)));
        if (records.isFull() && refused > 0) {
            tooltip.add(line(Component.translatable(LANG + "refused", refused).withStyle(ChatFormatting.RED)));
        }
        tooltip.add(line(isListening()
                ? Component.translatable(LANG + "listening", Math.round(listenRadius())).withStyle(ChatFormatting.GOLD)
                : Component.translatable(LANG + "not_listening").withStyle(ChatFormatting.DARK_GRAY)));
        tooltip.add(line(Component.translatable(displayOn() ? LANG + "display_on" : LANG + "display_off")
                .withStyle(displayOn() ? ChatFormatting.GREEN : ChatFormatting.DARK_GRAY)));
        if (!docked.isEmpty()) {
            Logbook onDisplay = PrdItem.records(docked);
            tooltip.add(line(Component.translatable(LANG + "docked", onDisplay.size(), onDisplay.capacity())
                    .withStyle(onDisplay.isFull() ? ChatFormatting.RED : ChatFormatting.AQUA)));
        }
        // The goggles are only ever drawn on a client, where the map under the crosshair can be looked up.
        if (level != null && level.isClientSide && displayOn()) {
            dev.brights0ng.enginesandempires.geophone.client.SmartLoggerHover.addGoggleLines(this, tooltip);
        }
        return true;
    }

    private static Component line(Component text) {
        return Component.literal("    ").append(text);
    }

    private static final String LANG = "engines_and_empires.smart_logger.";

    // ---- saving and syncing ----

    @Override
    protected void write(CompoundTag compound, HolderLookup.Provider registries, boolean clientPacket) {
        if (isMaster()) {
            LogbookCodecs.ENTRIES.encodeStart(NbtOps.INSTANCE, records.entries())
                    .ifSuccess(entries -> compound.put("Readings", entries));
            compound.putInt("NextNumber", records.nextNumber());
            compound.putBoolean("DisplaySwitch", displaySwitch);
            compound.putBoolean("Redstone", redstone);
            if (view != null) {
                compound.putDouble("ViewX", view.centreX());
                compound.putDouble("ViewZ", view.centreZ());
                compound.putInt("Zoom", view.zoom());
            }
            if (!docked.isEmpty()) {
                compound.put("Docked", docked.save(registries));
            }
            if (clientPacket) {
                compound.putLongArray("ButtonsUp", buttonUp);
            } else {
                compound.putInt("Refused", refused);
            }
        }
        super.write(compound, registries, clientPacket);
    }

    @Override
    protected void read(CompoundTag compound, HolderLookup.Provider registries, boolean clientPacket) {
        if (compound.contains("Readings", Tag.TAG_LIST)) {
            List<LogbookEntry> entries = LogbookCodecs.ENTRIES.parse(NbtOps.INSTANCE, compound.get("Readings"))
                    .result().orElse(List.of());
            records = new Logbook(entries, compound.getInt("NextNumber"), LoggerListening.CAPACITY);
        }
        displaySwitch = compound.getBoolean("DisplaySwitch");
        redstone = compound.getBoolean("Redstone");
        // A view this player is dragging or zooming is theirs until they let go; the server's copy may be a step behind.
        boolean keepLocal = clientPacket && System.currentTimeMillis() < localViewUntil;
        if (!keepLocal) {
            view = compound.contains("Zoom")
                    ? new LoggerDisplay.View(compound.getDouble("ViewX"), compound.getDouble("ViewZ"), compound.getInt("Zoom"))
                    : null;
        }
        docked = compound.contains("Docked", Tag.TAG_COMPOUND)
                ? ItemStack.parse(registries, compound.getCompound("Docked")).orElse(ItemStack.EMPTY)
                : ItemStack.EMPTY;
        if (clientPacket) {
            long[] up = compound.getLongArray("ButtonsUp");
            for (int i = 0; i < buttonUp.length; i++) {
                buttonUp[i] = i < up.length ? up[i] : 0;
            }
        } else {
            refused = compound.getInt("Refused");
        }
        super.read(compound, registries, clientPacket);
    }
}
