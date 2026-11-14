// Written by Claude (GreedyFluids M7b b2).
package dev.brights0ng.meteodyne.airflow;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import dev.brights0ng.greedyfluids.grid.CellGrid3D;
import dev.brights0ng.meteodyne.Meteodyne;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongIterator;
import it.unimi.dsi.fastutil.longs.LongLinkedOpenHashSet;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.level.ChunkEvent;
import net.neoforged.neoforge.event.level.LevelEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.LevelTickEvent;

/**
 * The airflow cell codes ({@link AirflowCells}) of one level's chunk sections (M7b b2), for the sections some window
 * wants. Everything runs on the server thread.
 * <ul>
 *   <li><b>Wanting.</b> {@link #want} and {@link #release} count a section's users; a wanted section is read once
 *       its chunk is loaded, at most {@link #FILL_BUDGET_NANOS} of reading per tick (at least one section), and
 *       forgotten when its last user releases it.</li>
 *   <li><b>Reading.</b> A section whose blocks all share one code (air, a stone mass) is stored as that code, found
 *       from the section's palette; any other as {@code byte[4096]}, index {@code (y * 16 + z) * 16 + x}.</li>
 *   <li><b>Edits.</b> The block hook ({@link #blockChanged}) updates a cached section in place and tells the
 *       listeners when a cell's code changes. A {@link Codes} view handed out earlier keeps the codes it was made
 *       with: an edit copies the array first.</li>
 *   <li><b>Dropping.</b> A chunk unload drops its cached sections (a wanted one is read again once its chunk is
 *       back). A data-map or tag reload drops every section, at the next tick: the codes changed meaning.</li>
 * </ul>
 * Levels have no instance until {@link #open}; without one the block hook costs a map lookup.
 */
@EventBusSubscriber(modid = Meteodyne.MODID)
public final class AirflowSections {

    /** Cells in a section. */
    public static final int SECTION_CELLS = 4096;
    /** Server-thread time per tick spent reading sections, ns. */
    public static final long FILL_BUDGET_NANOS = 1_000_000L;

    /** Hears about one level's sections, on the server thread. */
    public interface Listener {
        /** A wanted section was read (its {@link #codes} are available). */
        default void sectionReady(long section) {
        }

        /** A block in a cached section changed code. */
        default void cellChanged(BlockPos pos, byte before, byte after) {
        }

        /** A cached section was dropped (chunk unload or reload); it is read again if still wanted. */
        default void sectionDropped(long section) {
        }
    }

    /** One section's codes at one moment: immutable, safe on any thread. */
    public static final class Codes {
        private final long section;
        private final long generation;
        private final byte uniform;
        private final byte[] cells;

        Codes(long section, long generation, byte uniform, byte[] cells) {
            this.section = section;
            this.generation = generation;
            this.uniform = uniform;
            this.cells = cells;
        }

        /** The section, as {@link SectionPos#asLong}. */
        public long section() {
            return section;
        }

        /** The {@link AirflowCells} generation the codes belong to. */
        public long generation() {
            return generation;
        }

        /** Whether every cell has one code. */
        public boolean uniform() {
            return cells == null;
        }

        /** The code at a section-local index ({@link #index}). */
        public byte code(int index) {
            return cells == null ? uniform : cells[index];
        }

        /** The code at section-local (x, y, z), each 0–15. */
        public byte code(int x, int y, int z) {
            return code(index(x, y, z));
        }

        /** Writes the section into {@code grid} with its (0, 0, 0) cell at grid cell (gx, gy, gz). */
        public void writeTo(CellGrid3D grid, int gx, int gy, int gz, AirflowCells.Table table) {
            if (table.generation() != generation) {
                throw new IllegalArgumentException("codes of generation " + generation + ", table " + table.generation());
            }
            for (int y = 0; y < 16; y++) {
                for (int z = 0; z < 16; z++) {
                    for (int x = 0; x < 16; x++) {
                        table.cell(code(x, y, z)).apply(grid, gx + x, gy + y, gz + z);
                    }
                }
            }
        }

        /** The section-local index of (x, y, z), each 0–15. */
        public static int index(int x, int y, int z) {
            return (y << 8) | (z << 4) | x;
        }
    }

    /** A cached section: one code, or an array shared with views until the next edit. */
    private static final class Entry {
        byte uniform;
        byte[] cells;
        boolean shared;

        byte code(int index) {
            return cells == null ? uniform : cells[index];
        }

        void set(int index, byte code) {
            if (cells == null) {
                cells = new byte[SECTION_CELLS];
                Arrays.fill(cells, uniform);
            } else if (shared) {
                cells = cells.clone();
            }
            shared = false;
            cells[index] = code;
        }
    }

    /** Server-thread counters, for status. */
    public record Stats(int cached, int pending, long reads, long readNanos, long edits) {
    }

    private static final Map<ServerLevel, AirflowSections> LEVELS = new ConcurrentHashMap<>();

    private final ServerLevel level;
    private final Long2ObjectOpenHashMap<Entry> entries = new Long2ObjectOpenHashMap<>();
    private final Long2IntOpenHashMap wants = new Long2IntOpenHashMap();
    private final LongLinkedOpenHashSet pending = new LongLinkedOpenHashSet();
    private final List<Listener> listeners = new CopyOnWriteArrayList<>();
    private long generation = AirflowCells.generation();
    private long reads, readNanos, edits;

    private AirflowSections(ServerLevel level) {
        this.level = level;
    }

    /** The level's sections, made if needed (server thread). */
    public static AirflowSections open(ServerLevel level) {
        return LEVELS.computeIfAbsent(level, AirflowSections::new);
    }

    /** The level's sections, or null if not open. */
    public static AirflowSections get(ServerLevel level) {
        return LEVELS.get(level);
    }

    /** Forgets the level's sections. */
    public static void close(ServerLevel level) {
        LEVELS.remove(level);
    }

    public ServerLevel level() {
        return level;
    }

    public void addListener(Listener listener) {
        listeners.add(listener);
    }

    public void removeListener(Listener listener) {
        listeners.remove(listener);
    }

    /** Adds a user of {@code section} ({@link SectionPos#asLong}), which must lie inside the build height. */
    public void want(long section) {
        int sy = SectionPos.y(section);
        if (sy < level.getMinSection() || sy >= level.getMaxSection()) {
            throw new IllegalArgumentException("section y " + sy + " outside the build height");
        }
        if (wants.addTo(section, 1) == 0 && !entries.containsKey(section)) {
            pending.add(section);
        }
    }

    /** Removes a user of {@code section}; the last one forgets it. */
    public void release(long section) {
        int before = wants.addTo(section, -1);
        if (before <= 0) {
            wants.remove(section);
            throw new IllegalStateException("release without want: " + SectionPos.of(section));
        }
        if (before == 1) {
            wants.remove(section);
            entries.remove(section);
            pending.remove(section);
        }
    }

    /** Whether {@code section} is cached in the current generation. */
    public boolean ready(long section) {
        return generation == AirflowCells.generation() && entries.containsKey(section);
    }

    /** The codes of a cached section, or null if it isn't cached (or a reload hasn't been swept yet). */
    public Codes codes(long section) {
        if (generation != AirflowCells.generation()) {
            return null;
        }
        Entry e = entries.get(section);
        if (e == null) {
            return null;
        }
        e.shared = e.cells != null;
        return new Codes(section, generation, e.uniform, e.cells);
    }

    /**
     * Sweeps a reload, then reads wanted sections whose chunks are loaded until {@code budgetNanos} is spent (at least
     * one). Returns the sections read.
     */
    public int fill(long budgetNanos) {
        sweepGeneration();
        if (pending.isEmpty()) {
            return 0;
        }
        long start = System.nanoTime();
        LongArrayList read = new LongArrayList();
        for (LongIterator it = pending.iterator(); it.hasNext(); ) {
            long key = it.nextLong();
            LevelChunk chunk = level.getChunkSource().getChunkNow(SectionPos.x(key), SectionPos.z(key));
            if (chunk == null) {
                continue; // waits for its chunk
            }
            it.remove();
            entries.put(key, read(chunk, SectionPos.y(key)));
            read.add(key);
            if (System.nanoTime() - start >= budgetNanos) {
                break;
            }
        }
        reads += read.size();
        readNanos += System.nanoTime() - start;
        for (int i = 0; i < read.size(); i++) {
            for (Listener l : listeners) {
                l.sectionReady(read.getLong(i));
            }
        }
        return read.size();
    }

    public Stats stats() {
        return new Stats(entries.size(), pending.size(), reads, readNanos, edits);
    }

    /** Reads one section: from the palette when every state shares a code, else cell by cell. */
    private static Entry read(LevelChunk chunk, int sy) {
        LevelChunkSection section = chunk.getSection(chunk.getSectionIndexFromSectionY(sy));
        Entry e = new Entry();
        e.uniform = AirflowCells.AIR;
        if (section.hasOnlyAir()) {
            return e;
        }
        Map<BlockState, Byte> local = new IdentityHashMap<>();
        section.getStates().count((state, count) -> local.put(state, AirflowCells.code(state)));
        byte first = local.values().iterator().next();
        boolean one = true;
        for (byte c : local.values()) {
            one &= c == first;
        }
        if (one) {
            e.uniform = first;
            return e;
        }
        byte[] cells = new byte[SECTION_CELLS];
        for (int y = 0; y < 16; y++) {
            for (int z = 0; z < 16; z++) {
                for (int x = 0; x < 16; x++) {
                    BlockState state = section.getBlockState(x, y, z);
                    Byte c = local.get(state);
                    cells[Codes.index(x, y, z)] = c != null ? c : AirflowCells.code(state);
                }
            }
        }
        e.cells = cells;
        return e;
    }

    /** A reload changed what codes mean: drop every section, and read the wanted ones again. */
    private void sweepGeneration() {
        long now = AirflowCells.generation();
        if (generation == now) {
            return;
        }
        generation = now;
        long[] dropped = entries.keySet().toLongArray();
        entries.clear();
        for (long key : wants.keySet()) {
            pending.add(key);
        }
        notifyDropped(dropped);
    }

    /** A block changed (server thread). */
    void changed(BlockPos pos, BlockState after) {
        if (generation != AirflowCells.generation()) {
            return; // swept at the next tick
        }
        Entry e = entries.get(SectionPos.asLong(pos));
        if (e == null) {
            return;
        }
        int index = Codes.index(pos.getX() & 15, pos.getY() & 15, pos.getZ() & 15);
        byte before = e.code(index), code = AirflowCells.code(after);
        if (before == code) {
            return;
        }
        e.set(index, code);
        edits++;
        BlockPos at = pos.immutable();
        for (Listener l : listeners) {
            l.cellChanged(at, before, code);
        }
    }

    /** An off-thread change, rechecked against the block there now. */
    private void recheck(BlockPos pos) {
        LevelChunk chunk = level.getChunkSource().getChunkNow(pos.getX() >> 4, pos.getZ() >> 4);
        if (chunk != null) {
            changed(pos, chunk.getBlockState(pos));
        }
    }

    /** A chunk unloaded: drop its cached sections; wanted ones wait for it to come back. */
    void unloaded(int cx, int cz) {
        List<Long> dropped = new ArrayList<>();
        for (int sy = level.getMinSection(); sy < level.getMaxSection(); sy++) {
            long key = SectionPos.asLong(cx, sy, cz);
            if (entries.remove(key) != null) {
                dropped.add(key);
            }
            if (wants.containsKey(key)) {
                pending.add(key);
            }
        }
        notifyDropped(dropped.stream().mapToLong(Long::longValue).toArray());
    }

    private void notifyDropped(long[] dropped) {
        for (long key : dropped) {
            for (Listener l : listeners) {
                l.sectionDropped(key);
            }
        }
    }

    /** From {@code LevelChunkHeatMixin}, any thread: a block changed in a loaded chunk. */
    public static void blockChanged(LevelChunk chunk, BlockPos pos, BlockState after) {
        if (LEVELS.isEmpty() || !(chunk.getLevel() instanceof ServerLevel level)) {
            return;
        }
        AirflowSections sections = LEVELS.get(level);
        if (sections == null) {
            return;
        }
        try {
            if (level.getServer().isSameThread()) {
                sections.changed(pos, after);
            } else {
                BlockPos at = pos.immutable();
                level.getServer().execute(() -> {
                    AirflowSections s = LEVELS.get(level);
                    if (s != null) {
                        s.recheck(at);
                    }
                });
            }
        } catch (RuntimeException e) {
            Meteodyne.LOGGER.error("Airflow: block change at {} failed", pos, e);
        }
    }

    @SubscribeEvent
    static void onLevelTick(LevelTickEvent.Post event) {
        if (!LEVELS.isEmpty() && event.getLevel() instanceof ServerLevel level) {
            AirflowSections sections = LEVELS.get(level);
            if (sections != null) {
                sections.fill(FILL_BUDGET_NANOS);
            }
        }
    }

    @SubscribeEvent
    static void onChunkUnload(ChunkEvent.Unload event) {
        if (LEVELS.isEmpty() || !(event.getLevel() instanceof ServerLevel level)) {
            return;
        }
        AirflowSections sections = LEVELS.get(level);
        if (sections == null) {
            return;
        }
        ChunkPos pos = event.getChunk().getPos();
        if (level.getServer().isSameThread()) {
            sections.unloaded(pos.x, pos.z);
        } else {
            level.getServer().execute(() -> sections.unloaded(pos.x, pos.z));
        }
    }

    @SubscribeEvent
    static void onLevelUnload(LevelEvent.Unload event) {
        if (event.getLevel() instanceof ServerLevel level) {
            LEVELS.remove(level);
        }
    }

    @SubscribeEvent
    static void onServerStopped(ServerStoppedEvent event) {
        LEVELS.clear();
    }
}
