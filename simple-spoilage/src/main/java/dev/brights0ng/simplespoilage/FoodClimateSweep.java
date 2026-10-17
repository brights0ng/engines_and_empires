package dev.brights0ng.simplespoilage;

import java.util.Map;
import java.util.WeakHashMap;
import java.util.function.BiConsumer;
import java.util.function.DoubleSupplier;

import dev.brights0ng.simplespoilage.SimpleSpoilage;
import dev.brights0ng.simplespoilage.icechest.IceChestBlockEntity;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.entity.EntityTypeTest;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.event.level.ChunkEvent;
import net.neoforged.neoforge.event.tick.LevelTickEvent;
import net.neoforged.neoforge.items.IItemHandler;

/**
 * Applies temperature to the food in loaded places (food by temperature, 2026-10-15; {@code claude/food-temperature.md}):
 * once every {@code [temperature] sweepSeconds} (60), spread over the ticks between, every loaded chunk's block entities
 * that hold food (anything with slots: chests, barrels, Create vaults, belts and depots through their item handlers, and
 * modded storage), every player's inventory and every item on the ground. Each food stack carries the tick it was last
 * done to ({@link Spoilage#climateMark}), so a stack reached twice (a vault's parts sharing one inventory) or moved
 * between places is aged once for each stretch of time; one that sat unseen longer than three sweeps (an unloaded
 * chunk) catches up at the place's seasonal temperature. Ice chests run their own cooling ({@link IceChestBlockEntity}).
 */
@EventBusSubscriber(modid = SimpleSpoilage.MODID)
public final class FoodClimateSweep {

    private static final class State {
        final LongOpenHashSet loaded = new LongOpenHashSet();
        LongArrayList queue = new LongArrayList();
        int next;
        long cycleStart = Long.MIN_VALUE;
    }

    private static final Map<ServerLevel, State> STATES = new WeakHashMap<>();

    private static State state(ServerLevel level) {
        return STATES.computeIfAbsent(level, l -> new State());
    }

    @SubscribeEvent
    static void onChunkLoad(ChunkEvent.Load event) {
        if (event.getLevel() instanceof ServerLevel level && event.getChunk() instanceof LevelChunk chunk) {
            synchronized (STATES) {
                state(level).loaded.add(chunk.getPos().toLong());
            }
        }
    }

    @SubscribeEvent
    static void onChunkUnload(ChunkEvent.Unload event) {
        if (event.getLevel() instanceof ServerLevel level) {
            synchronized (STATES) {
                state(level).loaded.remove(event.getChunk().getPos().toLong());
            }
        }
    }

    @SubscribeEvent
    static void onLevelTick(LevelTickEvent.Post event) {
        if (!(event.getLevel() instanceof ServerLevel level) || !SpoilageConfig.climateEnabled()) {
            return;
        }
        long now = SpoilClock.now();
        if (now < 0) {
            return;
        }
        int period = SpoilageConfig.sweepTicks();
        State st;
        synchronized (STATES) {
            st = state(level);
            if (st.cycleStart == Long.MIN_VALUE || level.getGameTime() - st.cycleStart >= period) {
                st.cycleStart = level.getGameTime();
                st.queue = new LongArrayList(st.loaded);
                addShipChunks(level, st.queue);
                st.next = 0;
            }
        }
        if (st.next == 0) {
            // Once a cycle: people and items on the ground
            for (ServerPlayer player : level.players()) {
                player(level, player, now, period);
            }
            for (ItemEntity item : level.getEntities(EntityTypeTest.forClass(ItemEntity.class), e -> true)) {
                itemEntity(level, item, now, period);
            }
        }
        int perTick = (st.queue.size() + period - 1) / period;
        for (int i = 0; i < perTick && st.next < st.queue.size(); i++, st.next++) {
            long key = st.queue.getLong(st.next);
            LevelChunk chunk = level.getChunkSource().getChunkNow(ChunkPos.getX(key), ChunkPos.getZ(key));
            if (chunk != null) {
                chunk(level, chunk, now, period);
            }
        }
    }

    /**
     * Adds ships' chunks to a sweep's queue (Sable's plot chunks made in play send no load event): none without Sable;
     * {@code sable/SpoilageSable} sets it when Sable is loaded.
     */
    private static volatile BiConsumer<ServerLevel, LongArrayList> shipChunks = (level, queue) -> {
    };

    /** Called once at mod construction, and only when Sable is loaded. */
    public static void shipChunks(BiConsumer<ServerLevel, LongArrayList> source) {
        shipChunks = source;
    }

    private static void addShipChunks(ServerLevel level, LongArrayList queue) {
        shipChunks.accept(level, queue);
    }

    /** Catching up past three sweeps counts as unseen time. */
    public static long catchUpTicks(int period) {
        return 3L * period;
    }

    // ---- the places ----

    static void chunk(ServerLevel level, LevelChunk chunk, long now, int period) {
        for (BlockEntity be : chunk.getBlockEntities().values().toArray(new BlockEntity[0])) {
            blockEntity(level, be, now, period);
        }
    }

    /** One block entity's food (public for the game tests). */
    public static void blockEntity(ServerLevel level, BlockEntity be, long now, int period) {
        if (be.isRemoved()) {
            return;
        }
        if (be instanceof IceChestBlockEntity chest) {
            chest.settle();
            return;
        }
        BlockPos pos = be.getBlockPos();
        Temperatures t = new Temperatures(() -> FoodTemperature.at(level, pos), () -> FoodTemperature.seasonal(level, pos));
        boolean changed = false;
        if (be instanceof Container container) {
            for (int slot = 0; slot < container.getContainerSize(); slot++) {
                changed |= stack(container.getItem(slot), now, t, period);
            }
        } else {
            IItemHandler handler = level.getCapability(Capabilities.ItemHandler.BLOCK, pos, be.getBlockState(), be, null);
            if (handler != null) {
                for (int slot = 0; slot < handler.getSlots(); slot++) {
                    changed |= stack(handler.getStackInSlot(slot), now, t, period);
                }
            }
        }
        if (changed) {
            be.setChanged();
        }
    }

    /** A player's inventory (public for the game tests). */
    public static void player(ServerLevel level, ServerPlayer player, long now, int period) {
        if (player.isSpectator()) {
            return;
        }
        Temperatures t = new Temperatures(() -> FoodTemperature.at(level, player),
                () -> FoodTemperature.seasonal(level, player.blockPosition()));
        boolean changed = false;
        var inv = player.getInventory();
        for (int slot = 0; slot < inv.getContainerSize(); slot++) {
            changed |= stack(inv.getItem(slot), now, t, period);
        }
        if (changed) {
            inv.setChanged();
        }
    }

    static void itemEntity(ServerLevel level, ItemEntity item, long now, int period) {
        ItemStack stack = item.getItem();
        if (!Spoilage.isSpoilable(stack)) {
            return;
        }
        Temperatures t = new Temperatures(() -> FoodTemperature.at(level, item),
                () -> FoodTemperature.seasonal(level, item.blockPosition()));
        if (stack(stack, now, t, period)) {
            item.setItem(stack);
        }
    }

    private static boolean stack(ItemStack stack, long now, Temperatures t, int period) {
        if (!Spoilage.isSpoilable(stack)) {
            return false;
        }
        return Spoilage.applyClimate(stack, now, t.current(), t::seasonal, catchUpTicks(period), false);
    }

    /** A place's temperatures, each looked up once and only if some food there needs it. */
    private static final class Temperatures {
        private final DoubleSupplier currentSource;
        private final DoubleSupplier seasonalSource;
        private double current = Double.NaN;
        private double seasonal = Double.NaN;
        private boolean hasCurrent;
        private boolean hasSeasonal;

        Temperatures(DoubleSupplier current, DoubleSupplier seasonal) {
            this.currentSource = current;
            this.seasonalSource = seasonal;
        }

        double current() {
            if (!hasCurrent) {
                current = currentSource.getAsDouble();
                hasCurrent = true;
            }
            return current;
        }

        double seasonal() {
            if (!hasSeasonal) {
                seasonal = seasonalSource.getAsDouble();
                hasSeasonal = true;
            }
            return seasonal;
        }
    }

    private FoodClimateSweep() {
    }
}
