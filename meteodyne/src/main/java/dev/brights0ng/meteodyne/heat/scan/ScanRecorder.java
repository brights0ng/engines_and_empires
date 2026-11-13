// Written by Claude; edited by Claude (recording format v2: CELLS records and section-range edits, GreedyFluids M6f).
package dev.brights0ng.meteodyne.heat.scan;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import java.util.zip.GZIPOutputStream;

import dev.brights0ng.meteodyne.Meteodyne;
import dev.brights0ng.meteodyne.ships.Ships;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/**
 * Server-thread capture, with one daemon performing all recording file I/O and compression.
 * Format v2: a mixed section's SECTION summary is followed by its CELLS, and BLOCK/ACTION cover every in-range
 * section, so a section's contents plus the BLOCK records after them in the file reproduce it at any later point.
 */
@EventBusSubscriber(modid = Meteodyne.MODID)
public final class ScanRecorder {
    private static volatile Session active;
    private static Status last;
    private static final byte[] CLOSE = new byte[0];

    public record Status(Path file, long durationTicks, long[] recordCounts, long fileSize,
                         int droppedOffThread, int sectionsPending, boolean recording) {
        public long records() {
            long total = 0;
            for (long count : recordCounts) total += count;
            return total;
        }
    }

    public static Path directory(net.minecraft.server.MinecraftServer server) {
        return server.getServerDirectory().resolve("greedyfluids-scans");
    }

    public static Path start(Path dir, ServerLevel level, UUID player) {
        ServerPlayer anchor = level.getServer().getPlayerList().getPlayer(player);
        if (anchor == null || anchor.serverLevel() != level) throw new IllegalArgumentException("Player is not in this level");
        return begin(dir, level, player, null, anchor.position());
    }

    /** Fixed/supplied trajectory for tests, with no player flags. */
    public static Path start(Path dir, ServerLevel level, Supplier<Vec3> anchor) {
        return begin(dir, level, null, anchor, anchor.get());
    }

    private static Path begin(Path dir, ServerLevel level, UUID player, Supplier<Vec3> anchor, Vec3 initial) {
        if (!level.getServer().isSameThread()) throw new IllegalStateException("Recording must start on the server thread");
        if (active != null) throw new IllegalStateException("A scan is already recording");
        try {
            Session session = new Session(dir, level, player, anchor, initial);
            session.dimension();
            active = session;
            last = null;
            return session.file;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public static Status stop() {
        Session s = active;
        if (s == null) throw new IllegalStateException("No scan is recording");
        s.checkThread();
        active = null;
        s.write(() -> {
            s.tag(ScanFormat.END); s.out.writeLong(s.tick());
            s.out.writeInt(s.dropped.get()); s.out.writeInt(s.pending.size());
        });
        s.handoff();
        s.queue.add(CLOSE);
        boolean interrupted = false;
        while (s.writer.isAlive()) {
            try { s.writer.join(); }
            catch (InterruptedException e) { interrupted = true; }
        }
        if (interrupted) Thread.currentThread().interrupt();
        s.checkWriter();
        last = s.status(false);
        return last;
    }

    public static void mark(String text) {
        Session s = requireActive();
        if (text.length() > 21000) throw new IllegalArgumentException("Mark is too long");
        s.write(() -> { s.tag(ScanFormat.MARK); s.out.writeLong(s.tick()); s.out.writeUTF(text); });
        s.maybeHandoff();
    }

    public static Status status() {
        Session s = active;
        if (s == null) return last;
        s.checkThread();
        s.checkWriter();
        return s.status(true);
    }

    private static Session requireActive() {
        Session s = active;
        if (s == null) throw new IllegalStateException("No scan is recording");
        s.checkThread();
        s.checkWriter();
        return s;
    }

    /** The inactive path is one volatile read and branch: no allocation or table lookup. */
    public static void blockChanged(LevelChunk chunk, BlockPos pos, BlockState before, BlockState after) {
        Session s = active;
        if (s == null) return;
        if (!(chunk.getLevel() instanceof ServerLevel level)) return;
        if (!level.getServer().isSameThread()) {
            s.dropped.incrementAndGet();
            return;
        }
        if (chunk.getLevel() != s.level || !s.inRange(pos)) return;
        s.write(() -> {
            int beforeId = s.id(before), afterId = s.id(after);
            s.tag(ScanFormat.BLOCK); s.out.writeLong(s.tick()); s.position(pos);
            s.out.writeInt(beforeId); s.out.writeInt(afterId);
        });
        s.maybeHandoff();
    }

    @SubscribeEvent
    static void tick(ServerTickEvent.Post event) {
        Session s = active;
        if (s == null || s.level.getServer() != event.getServer()) return;
        if (!s.updateAnchor()) {
            stop();
            return;
        }
        s.elapsed++;
        if ((s.elapsed - 1) % ScanFormat.TRAJECTORY_INTERVAL == 0) s.trajectory();
        if ((s.elapsed - 1) % 20 == 0) s.write(() -> {
            s.tag(ScanFormat.LOAD); s.out.writeLong(s.tick());
            s.out.writeFloat((float) (event.getServer().getAverageTickTimeNanos() / 1e6));
            s.out.writeByte((s.level.isRaining() ? 1 : 0) | (s.level.isThundering() ? 2 : 0));
        });
        s.sections();
        if (s.elapsed % 200 == 0) s.handoff();
        else s.maybeHandoff();
    }

    private static void action(net.minecraft.world.entity.player.Player player, BlockPos pos, int kind) {
        Session s = active;
        if (s == null || s.player == null || !s.player.equals(player.getUUID())
                || player.level() != s.level || !s.inRange(pos)) return;
        s.checkThread();
        s.write(() -> {
            s.tag(ScanFormat.ACTION); s.out.writeLong(s.tick()); s.position(pos); s.out.writeByte(kind);
        });
        s.maybeHandoff();
    }

    @SubscribeEvent
    static void broken(BlockEvent.BreakEvent event) { action(event.getPlayer(), event.getPos(), 0); }

    @SubscribeEvent
    static void placed(BlockEvent.EntityPlaceEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) action(player, event.getPos(), 1);
    }

    @SubscribeEvent
    static void used(PlayerInteractEvent.RightClickBlock event) {
        if (!event.getLevel().isClientSide()) action(event.getEntity(), event.getPos(), 2);
    }

    @SubscribeEvent
    static void changedDimension(PlayerEvent.PlayerChangedDimensionEvent event) {
        Session s = active;
        if (s != null && s.player != null && s.player.equals(event.getEntity().getUUID())) s.updateAnchor();
    }

    @SubscribeEvent
    static void stopping(ServerStoppingEvent event) {
        if (active != null && active.level.getServer() == event.getServer()) stop();
    }

    @SubscribeEvent
    static void loggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        if (active == null && event.getEntity() instanceof ServerPlayer player) {
            Path dir = directory(player.getServer());
            if (Files.exists(dir.resolve("autorecord"))) {
                try { start(dir, player.serverLevel(), player.getUUID()); }
                catch (RuntimeException e) { Meteodyne.LOGGER.error("Could not start automatic scan recording", e); }
            }
        }
    }

    @FunctionalInterface
    private interface Output { void write() throws IOException; }

    private static final class Session {
        final Path file;
        final UUID player;
        final Supplier<Vec3> anchor;
        ServerLevel level;
        Vec3 point;
        int bx, by, bz;
        long elapsed, lastTick;
        final Map<BlockState, Integer> ids = new IdentityHashMap<>();
        final long[] counts = new long[10];
        final AtomicInteger dropped = new AtomicInteger();
        final Set<SectionPos> inRange = new HashSet<>();
        final ArrayDeque<SectionPos> pending = new ArrayDeque<>();
        SectionPos centre;
        final ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        final DataOutputStream out = new DataOutputStream(buffer);
        final LinkedBlockingQueue<byte[]> queue = new LinkedBlockingQueue<>();
        final Thread writer;
        volatile IOException failure;
        volatile long fileSize;

        Session(Path dir, ServerLevel level, UUID player, Supplier<Vec3> anchor, Vec3 initial) throws IOException {
            this.level = level; this.player = player; this.anchor = anchor;
            setPoint(initial);
            lastTick = level.getGameTime();
            Files.createDirectories(dir);
            file = dir.resolve("rec-" + ScanFormat.timestamp() + ".gfrec");
            // Open synchronously, so a bad destination is reported before a recording becomes active.
            var gzip = new GZIPOutputStream(Files.newOutputStream(file, StandardOpenOption.CREATE_NEW,
                    StandardOpenOption.WRITE), true);
            fileSize = Files.size(file);
            out.writeInt(ScanFormat.RECORDING_MAGIC); out.writeInt(ScanFormat.RECORDING_VERSION);
            out.writeLong(lastTick); out.writeLong(System.currentTimeMillis());
            out.writeInt(ScanFormat.BLOCK_RADIUS_SECTIONS); out.writeInt(ScanFormat.SECTION_RADIUS);
            out.writeInt(ScanFormat.TRAJECTORY_INTERVAL);
            writer = new Thread(() -> {
                try (gzip) {
                    while (true) {
                        byte[] bytes = queue.take();
                        if (bytes == CLOSE) break;
                        gzip.write(bytes);
                        gzip.flush();
                        fileSize = Files.size(file);
                    }
                } catch (IOException e) { failure = e; }
                catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    failure = new IOException("Scan writer interrupted", e);
                } finally {
                    try { fileSize = Files.size(file); }
                    catch (IOException e) { failure = e; }
                }
            }, "meteodyne-scan-writer");
            writer.setDaemon(true);
            writer.start();
        }

        void checkThread() {
            if (!level.getServer().isSameThread()) throw new IllegalStateException("Scan access off server thread");
        }

        void checkWriter() {
            if (failure != null) throw new UncheckedIOException("Scan writer failed", failure);
        }

        void write(Output output) {
            try { output.write(); }
            catch (IOException e) { throw new UncheckedIOException(e); }
        }

        void tag(int tag) throws IOException { out.writeByte(tag); counts[tag]++; }
        long tick() { return lastTick = Math.max(lastTick, level.getGameTime()); }
        void position(BlockPos pos) throws IOException {
            out.writeInt(pos.getX()); out.writeInt(pos.getY()); out.writeInt(pos.getZ());
        }

        int id(BlockState state) throws IOException {
            Integer known = ids.get(state);
            if (known != null) return known;
            int id = ids.size();
            ids.put(state, id);
            tag(ScanFormat.STATE); out.writeInt(id); out.writeUTF(ScanFormat.stateString(state));
            out.writeByte(ScanFormat.classify(state)); out.writeByte(ScanFormat.flags(state));
            return id;
        }

        void setPoint(Vec3 value) {
            point = value;
            bx = net.minecraft.util.Mth.floor(value.x);
            by = net.minecraft.util.Mth.floor(value.y);
            bz = net.minecraft.util.Mth.floor(value.z);
        }

        /** True when pos lies in a section of the current summary range (the anchor's section before the first tick). */
        boolean inRange(BlockPos pos) {
            int cx = centre != null ? centre.x() : bx >> 4, cy = centre != null ? centre.y() : by >> 4;
            int cz = centre != null ? centre.z() : bz >> 4, r = ScanFormat.SECTION_RADIUS;
            return Math.abs((pos.getX() >> 4) - cx) <= r && Math.abs((pos.getY() >> 4) - cy) <= r
                    && Math.abs((pos.getZ() >> 4) - cz) <= r;
        }

        boolean updateAnchor() {
            if (player == null) { setPoint(anchor.get()); return true; }
            ServerPlayer entity = level.getServer().getPlayerList().getPlayer(player);
            if (entity == null) return false;
            if (entity.serverLevel() != level) {
                level = entity.serverLevel();
                centre = null; inRange.clear(); pending.clear();
                dimension();
            }
            setPoint(entity.position());
            return true;
        }

        void dimension() {
            write(() -> { tag(ScanFormat.DIM); out.writeLong(tick()); out.writeUTF(level.dimension().location().toString()); });
        }

        void trajectory() {
            int flags = 0;
            if (player != null) {
                ServerPlayer entity = level.getServer().getPlayerList().getPlayer(player);
                flags = (entity.isPassenger() ? 1 : 0)
                        | (Ships.get().near(level, point.x, point.y, point.z, 8) ? 2 : 0)
                        | (entity.isFallFlying() ? 4 : 0) | (entity.getAbilities().flying ? 8 : 0);
            }
            final int poseFlags = flags;
            write(() -> {
                tag(ScanFormat.POS); out.writeLong(tick());
                out.writeFloat((float) point.x); out.writeFloat((float) point.y); out.writeFloat((float) point.z);
                out.writeByte(poseFlags);
            });
        }

        void sections() {
            SectionPos next = SectionPos.of(bx >> 4, by >> 4, bz >> 4);
            if (!next.equals(centre)) {
                centre = next;
                Set<SectionPos> range = new HashSet<>();
                ArrayList<SectionPos> entered = new ArrayList<>();
                int radius = ScanFormat.SECTION_RADIUS;
                for (int y = Math.max(level.getMinSection(), next.y() - radius);
                     y < Math.min(level.getMaxSection(), next.y() + radius + 1); y++) {
                    for (int z = next.z() - radius; z <= next.z() + radius; z++) {
                        for (int x = next.x() - radius; x <= next.x() + radius; x++) {
                            SectionPos pos = SectionPos.of(x, y, z);
                            range.add(pos);
                            if (!inRange.contains(pos)) entered.add(pos);
                        }
                    }
                }
                pending.removeIf(pos -> !range.contains(pos));
                entered.addAll(pending);
                entered.sort(Comparator.comparingInt(pos -> distance(pos, next)));
                pending.clear(); pending.addAll(entered);
                inRange.clear(); inRange.addAll(range);
            }
            int budget = Math.min(16, pending.size());
            for (int i = 0; i < budget; i++) {
                SectionPos pos = pending.removeFirst();
                LevelChunk chunk = level.getChunkSource().getChunkNow(pos.x(), pos.z());
                if (chunk == null) pending.addLast(pos);
                else summary(pos, chunk);
            }
        }

        /** SECTION, then CELLS for a mixed section; every STATE they reference is written first. */
        void summary(SectionPos pos, LevelChunk chunk) {
            var section = chunk.getSection(level.getSectionIndexFromSectionY(pos.y()));
            ArrayList<BlockState> local = new ArrayList<>();
            short[] cells = new short[4096];
            if (section.hasOnlyAir()) local.add(Blocks.AIR.defaultBlockState());
            else {
                Map<BlockState, Integer> index = new IdentityHashMap<>();
                BlockState previous = null;
                int previousIndex = -1, i = 0;
                for (int y = 0; y < 16; y++) for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++) {
                    BlockState state = section.getBlockState(x, y, z);
                    if (state != previous) {
                        Integer known = index.get(state);
                        if (known == null) { known = local.size(); index.put(state, known); local.add(state); }
                        previous = state; previousIndex = known;
                    }
                    cells[i++] = (short) previousIndex;
                }
            }
            int size = local.size();
            int[] perIndex = new int[size];
            if (size == 1) perIndex[0] = 4096;
            else for (short cell : cells) perIndex[cell]++;
            int[] classes = new int[8];
            int sources = 0;
            for (int k = 0; k < size; k++) {
                classes[ScanFormat.classify(local.get(k))] += perIndex[k];
                if ((ScanFormat.flags(local.get(k)) & 1) != 0) sources += perIndex[k];
            }
            final int sourceCount = sources;
            write(() -> {
                int[] ids = new int[size];
                for (int k = 0; k < size; k++) ids[k] = id(local.get(k));
                long tick = tick();
                tag(ScanFormat.SECTION); out.writeLong(tick);
                out.writeInt(pos.x()); out.writeInt(pos.y()); out.writeInt(pos.z());
                for (int count : classes) out.writeShort(count);
                out.writeShort(sourceCount); out.writeInt(size == 1 ? ids[0] : -1);
                if (size == 1) return;
                tag(ScanFormat.CELLS); out.writeLong(tick);
                out.writeInt(pos.x()); out.writeInt(pos.y()); out.writeInt(pos.z());
                out.writeShort(size);
                for (int id : ids) out.writeInt(id);
                if (size <= 256) for (short cell : cells) out.writeByte(cell);
                else for (short cell : cells) out.writeShort(cell);
            });
        }

        static int distance(SectionPos a, SectionPos b) {
            return Math.max(Math.abs(a.x() - b.x()), Math.max(Math.abs(a.y() - b.y()), Math.abs(a.z() - b.z())));
        }

        void maybeHandoff() { if (buffer.size() >= 1024 * 1024) handoff(); }
        void handoff() {
            if (buffer.size() == 0) return;
            checkWriter();
            queue.add(buffer.toByteArray());
            buffer.reset();
        }

        Status status(boolean recording) {
            return new Status(file, elapsed, counts.clone(), fileSize, dropped.get(), pending.size(), recording);
        }
    }

    private ScanRecorder() { }
}
