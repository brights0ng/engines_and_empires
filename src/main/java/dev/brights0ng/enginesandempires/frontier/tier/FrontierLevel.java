package dev.brights0ng.enginesandempires.frontier.tier;

import dev.brights0ng.enginesandempires.frontier.FrontierConfig;
import dev.brights0ng.enginesandempires.frontier.FrontierContent;
import dev.brights0ng.enginesandempires.frontier.FrontierTags;
import dev.brights0ng.enginesandempires.frontier.Tier;
import dev.brights0ng.enginesandempires.frontier.guard.GuardTracker;
import dev.brights0ng.enginesandempires.frontier.incursion.LoudMachines;
import dev.brights0ng.enginesandempires.frontier.upkeep.TorchUpkeep;
import it.unimi.dsi.fastutil.longs.Long2ByteOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongLinkedOpenHashSet;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;

/**
 * The tiers of one level (only the Overworld has one: every other dimension is Uninhabited everywhere).
 *
 * <p>Each loaded section's <em>base</em> tier (see {@link TierRules}) is kept in memory and worked out again when something
 * around it changes: an anchor block placed or broken, inhabited time crossing a step, a resident arriving. Those changes mark
 * the sections around them dirty, and a few dirty ones are worked out each tick. A slow sweep also goes over every loaded
 * section now and then, which is how inhabited time fading away gets noticed.
 *
 * <p>The counts themselves live in each chunk's {@link FrontierChunkData}, saved with the chunk. Runs on the server thread only.
 */
public final class FrontierLevel {

    /** Inhabited time is only reported as a change (marking the area dirty) each time it crosses a multiple of this. */
    public static final int HABITATION_STEP = 1200;

    private final ServerLevel level;
    private final Long2ByteOpenHashMap base = new Long2ByteOpenHashMap();
    private final SettledIndex settled = new SettledIndex();
    private final LongLinkedOpenHashSet dirty = new LongLinkedOpenHashSet();
    private long[] sweep = new long[0];
    private int sweepPos;
    private TierParams params = FrontierConfig.params();
    private final TorchUpkeep torches;
    private final GuardTracker guards;
    private final LoudMachines machines;

    public FrontierLevel(ServerLevel level) {
        this.level = level;
        base.defaultReturnValue((byte) -1);
        this.torches = new TorchUpkeep(this);
        this.guards = new GuardTracker(this);
        this.machines = new LoudMachines(level);
    }

    public ServerLevel level() {
        return level;
    }

    public TierParams params() {
        return params;
    }

    /** The torch clocks of this level. */
    public TorchUpkeep torches() {
        return torches;
    }

    /** The guards of this level, and which are free to move. */
    public GuardTracker guards() {
        return guards;
    }

    /** The loud machines in this level's loaded chunks, and when they last ran (for incursion odds). */
    public LoudMachines machines() {
        return machines;
    }

    /** Every Settled or Civilized section that counts as Settled land (loaded ones only). */
    public java.util.List<Long> settledSections() {
        return settled.all();
    }

    /** Marks the land within the area radius of a section to be worked out again (a guard arrived, left or was penned). */
    public void markAreaDirty(long section) {
        markArea(section, params.areaRadius());
    }

    /** Picks up changed settings now rather than at the next tick (for tests). */
    public void reloadParams() {
        params = FrontierConfig.params();
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Asking

    /** The tier at a block. */
    public Tier tierAt(BlockPos pos) {
        long key = keyOf(pos);
        Tier base = baseTier(key);
        boolean nearby = base == Tier.FRONTIER && settled.anyWithin(key, params.ringRadius());
        return TierRules.resolve(true, base, nearby, pos.getY(), params);
    }

    /** A section's base tier, worked out now if it has not been yet. Unloaded sections are Frontier. */
    public Tier baseTier(long key) {
        byte stored = base.get(key);
        if (stored < 0) {
            recompute(key);
            stored = base.get(key);
        }
        return stored < 0 ? Tier.FRONTIER : Tier.byId(stored);
    }

    /** Whether Settled land is within {@code radius} sections of this one. */
    public boolean settledWithin(long key, int radius) {
        return settled.anyWithin(key, radius);
    }

    /** Everything that went into the tier at a block, for the debug command. */
    public Report report(BlockPos pos) {
        long key = keyOf(pos);
        Tier base = baseTier(key);
        return new Report(tierAt(pos), base, countsAround(key), settled.anyWithin(key, params.ringRadius()),
                settled.anyWithin(key, params.nearRadius()), settled.contains(key), dirty.size(), this.base.size(),
                settled.size());
    }

    public record Report(Tier tier, Tier base, AreaCounts area, boolean settledInRing, boolean settledNear,
                         boolean isSettledSource, int dirty, int tracked, int settledSections) {
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Things happening

    /** Runs once a tick: works out some dirty sections, and moves the sweep along. */
    public void tick() {
        params = FrontierConfig.params();
        int budget = FrontierConfig.recomputeBudget();
        while (budget-- > 0 && !dirty.isEmpty()) {
            recompute(dirty.removeFirstLong());
        }
        if (sweepPos >= sweep.length) {
            sweep = base.keySet().toLongArray();
            sweepPos = 0;
        }
        int perTick = Math.max(1, (sweep.length + FrontierConfig.sweepPeriodTicks() - 1) / FrontierConfig.sweepPeriodTicks());
        for (int i = 0; i < perTick && sweepPos < sweep.length; i++) {
            long key = sweep[sweepPos++];
            if (base.containsKey(key)) {
                recompute(key);
            }
        }
        torches.tick();
        guards.tick();
        machines.tick();
    }

    /** A chunk has loaded: count its anchor blocks the first time it is seen, and look again at anything near what it holds. */
    public void onChunkLoad(LevelChunk chunk) {
        FrontierChunkData data = chunk.getData(FrontierContent.CHUNK_DATA);
        if (!data.scanned()) {
            data.setScanned(countAnchors(chunk));
            chunk.setUnsaved(true);
        }
        torches.onChunkLoad(chunk, data);
        machines.onChunkLoad(chunk);
        long now = level.getGameTime();
        for (int i = 0; i < data.sections(); i++) {
            boolean holds = data.anchors(i) > 0 || data.storedHabitation(i) > 0
                    || Habitation.recentResident(data.residentSeen(i), now, params);
            if (holds) {
                markArea(SectionKey.of(chunk.getPos().x, data.minSection() + i, chunk.getPos().z), params.areaRadius());
            }
        }
    }

    /** A chunk has unloaded: forget its sections' tiers. */
    public void onChunkUnload(LevelChunk chunk) {
        // The maps remember its ground tier as it was when last loaded (only if it was worked out: no fresh guesses now).
        Tier ground = groundTier(chunk, false);
        if (ground != null) {
            dev.brights0ng.enginesandempires.frontier.map.TierMemory.of(level).remember(chunk.getPos().x, chunk.getPos().z, ground);
        }
        torches.onChunkUnload(chunk);
        machines.onChunkUnload(chunk);
        for (int sy = level.getMinSection(); sy < level.getMaxSection(); sy++) {
            long key = SectionKey.of(chunk.getPos().x, sy, chunk.getPos().z);
            base.remove(key);
            settled.set(key, false);
            dirty.remove(key);
        }
    }

    /** A block changed from {@code before} to {@code after} in a loaded chunk. */
    public void onBlockChanged(LevelChunk chunk, BlockPos pos, BlockState before, BlockState after) {
        machines.onBlockChanged(pos, before, after);
        boolean anchorChanged = before.is(FrontierTags.SETTLES) != after.is(FrontierTags.SETTLES);
        boolean torchChanged = before.is(FrontierTags.BURNS_OUT) != after.is(FrontierTags.BURNS_OUT);
        if (!anchorChanged && !torchChanged) {
            return;
        }
        if (!level.getServer().isSameThread()) {
            // Some mods place blocks off the server thread; count it there instead.
            BlockPos at = pos.immutable();
            level.getServer().execute(() -> onBlockChanged(chunk, at, before, after));
            return;
        }
        FrontierChunkData data = chunk.getData(FrontierContent.CHUNK_DATA);
        if (torchChanged) {
            torches.onTorchChanged(chunk, data, pos, after.is(FrontierTags.BURNS_OUT));
        }
        if (!anchorChanged) {
            return;
        }
        if (!data.scanned()) {
            return; // it will be counted when the chunk is scanned
        }
        int i = data.index(pos.getY() >> 4);
        if (i < 0) {
            return;
        }
        data.addAnchors(i, after.is(FrontierTags.SETTLES) ? 1 : -1);
        chunk.setUnsaved(true);
        markArea(keyOf(pos), params.areaRadius());
    }

    /**
     * Someone (a player, villager, colonist or illager) is about at {@code pos}; they are counted every {@code ticks} ticks.
     *
     * <p>Inhabited time is kept per <em>area</em>: every section within {@link TierParams#areaRadius()} of {@code pos} is
     * credited, and a section's own value is how long anyone has been about in the area around it. Each section only gains
     * the time that has really passed since it was last credited, so a crowd (four players, a dozen villagers) settles land no
     * faster than one person. Next to Settled land it counts {@link TierParams#nearMultiplier()} times over. A resident also
     * marks its own section as recently lived in.
     */
    public void inhabit(BlockPos pos, int ticks, boolean resident) {
        long key = keyOf(pos);
        int multiplier = settled.anyWithin(key, params.nearRadius()) ? params.nearMultiplier() : 1;
        creditArea(key, ticks, multiplier, true);
        if (resident) {
            residentArrived(pos);
        }
    }

    /** Adds {@code ticks} of inhabited time to the area around {@code pos} outright (the debug command and tests). */
    public void addHabitation(BlockPos pos, int ticks) {
        creditArea(keyOf(pos), ticks, 1, false);
    }

    /** Takes {@code ticks} of inhabited time from the area around {@code pos} (someone was killed there by an incursion). */
    public void removeHabitation(BlockPos pos, int ticks) {
        creditArea(keyOf(pos), -ticks, 1, false);
    }

    private void creditArea(long centre, int ticks, int multiplier, boolean onlyTimePassed) {
        int sx = SectionKey.x(centre);
        int sy = SectionKey.y(centre);
        int sz = SectionKey.z(centre);
        int r = params.areaRadius();
        long now = level.getGameTime();
        for (int x = sx - r; x <= sx + r; x++) {
            for (int z = sz - r; z <= sz + r; z++) {
                LevelChunk chunk = level.getChunkSource().getChunkNow(x, z);
                if (chunk == null) {
                    continue;
                }
                FrontierChunkData data = chunk.getData(FrontierContent.CHUNK_DATA);
                boolean touched = false;
                for (int y = sy - r; y <= sy + r; y++) {
                    int i = data.index(y);
                    if (i < 0) {
                        continue;
                    }
                    int before = onlyTimePassed ? data.creditHabitation(i, ticks, multiplier, now, params)
                            : data.addHabitation(i, ticks * multiplier, now, params);
                    if (before < 0) {
                        continue;
                    }
                    touched = true;
                    int after = data.habitation(i, now, params);
                    // A section's tier only depends on its own inhabited time, so only it needs working out again.
                    if (before / HABITATION_STEP != after / HABITATION_STEP
                            || (before < params.settleTicks()) != (after < params.settleTicks())) {
                        dirty.add(SectionKey.of(x, y, z));
                    }
                }
                if (touched) {
                    chunk.setUnsaved(true);
                }
            }
        }
    }

    /** A resident has turned up at {@code pos} (loaded from disk, or spawned): the land counts as lived in right away. */
    public void residentArrived(BlockPos pos) {
        LevelChunk chunk = level.getChunkSource().getChunkNow(pos.getX() >> 4, pos.getZ() >> 4);
        if (chunk == null) {
            return;
        }
        FrontierChunkData data = chunk.getData(FrontierContent.CHUNK_DATA);
        int i = data.index(pos.getY() >> 4);
        if (i >= 0 && markResident(data, i, level.getGameTime())) {
            chunk.setUnsaved(true);
            markArea(keyOf(pos), params.areaRadius());
        }
    }

    private boolean markResident(FrontierChunkData data, int i, long now) {
        boolean wasRecent = Habitation.recentResident(data.residentSeen(i), now, params);
        data.setResidentSeen(i, now);
        return !wasRecent;
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Debug and tests

    /** Forgets inhabited time and residents in the chunks within {@code radius} sections of {@code pos}, then re-tiers. */
    public void clearHabitation(BlockPos pos, int radius) {
        int cx = pos.getX() >> 4;
        int cz = pos.getZ() >> 4;
        for (int x = cx - radius; x <= cx + radius; x++) {
            for (int z = cz - radius; z <= cz + radius; z++) {
                LevelChunk chunk = level.getChunkSource().getChunkNow(x, z);
                if (chunk != null && chunk.hasData(FrontierContent.CHUNK_DATA)) {
                    chunk.getData(FrontierContent.CHUNK_DATA).clearHabitation();
                    chunk.setUnsaved(true);
                }
            }
        }
        refreshAround(pos, radius + params.areaRadius());
    }

    /** Works out every section within {@code radius} of {@code pos} right now, instead of waiting for the queue. */
    public void refreshAround(BlockPos pos, int radius) {
        long centre = keyOf(pos);
        int sx = SectionKey.x(centre);
        int sy = SectionKey.y(centre);
        int sz = SectionKey.z(centre);
        for (int x = sx - radius; x <= sx + radius; x++) {
            for (int z = sz - radius; z <= sz + radius; z++) {
                for (int y = Math.max(level.getMinSection(), sy - radius); y <= Math.min(level.getMaxSection() - 1, sy + radius); y++) {
                    long key = SectionKey.of(x, y, z);
                    dirty.remove(key);
                    recompute(key);
                }
            }
        }
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Working tiers out

    private void recompute(long key) {
        int sx = SectionKey.x(key);
        int sz = SectionKey.z(key);
        if (level.getChunkSource().getChunkNow(sx, sz) == null) {
            base.remove(key);
            settled.set(key, false);
            return;
        }
        Tier tier = TierRules.base(countsAround(key), params);
        base.put(key, (byte) tier.ordinal());
        int topY = (SectionKey.y(key) << 4) + 15;
        settled.set(key, TierRules.isSettledSource(tier, topY, params));
    }

    /** Adds up the counts in the sections within {@link TierParams#areaRadius()} of a section. */
    public AreaCounts countsAround(long key) {
        int sx = SectionKey.x(key);
        int sy = SectionKey.y(key);
        int sz = SectionKey.z(key);
        int r = params.areaRadius();
        long now = level.getGameTime();
        int anchors = 0;
        long habitation = 0;
        boolean resident = false;
        int freeGuards = 0;
        for (int x = sx - r; x <= sx + r; x++) {
            for (int z = sz - r; z <= sz + r; z++) {
                for (int y = sy - r; y <= sy + r; y++) {
                    freeGuards += guards.freeIn(SectionKey.of(x, y, z));
                }
                LevelChunk chunk = level.getChunkSource().getChunkNow(x, z);
                if (chunk == null || !chunk.hasData(FrontierContent.CHUNK_DATA)) {
                    continue;
                }
                FrontierChunkData data = chunk.getData(FrontierContent.CHUNK_DATA);
                for (int y = sy - r; y <= sy + r; y++) {
                    int i = data.index(y);
                    if (i < 0) {
                        continue;
                    }
                    anchors += data.anchors(i);
                    if (x == sx && y == sy && z == sz) {
                        habitation = data.habitation(i, now, params); // already counts the whole area (see inhabit)
                    }
                    resident |= Habitation.recentResident(data.residentSeen(i), now, params);
                }
            }
        }
        return new AreaCounts(anchors, habitation, resident, freeGuards);
    }

    /** Marks every loaded section within {@code radius} of a section to be worked out again. */
    private void markArea(long key, int radius) {
        int sx = SectionKey.x(key);
        int sy = SectionKey.y(key);
        int sz = SectionKey.z(key);
        int minY = Math.max(level.getMinSection(), sy - radius);
        int maxY = Math.min(level.getMaxSection() - 1, sy + radius);
        for (int x = sx - radius; x <= sx + radius; x++) {
            for (int z = sz - radius; z <= sz + radius; z++) {
                if (level.getChunkSource().getChunkNow(x, z) == null) {
                    continue;
                }
                for (int y = minY; y <= maxY; y++) {
                    dirty.add(SectionKey.of(x, y, z));
                }
            }
        }
    }

    /** Counts the anchor blocks in each section of a chunk, skipping sections that cannot hold any. */
    private static int[] countAnchors(LevelChunk chunk) {
        LevelChunkSection[] sections = chunk.getSections();
        int[] counts = new int[sections.length];
        for (int i = 0; i < sections.length; i++) {
            LevelChunkSection section = sections[i];
            if (section == null || section.hasOnlyAir()
                    || !section.getStates().maybeHas(state -> state.is(FrontierTags.SETTLES))) {
                continue;
            }
            int n = 0;
            for (int y = 0; y < 16; y++) {
                for (int z = 0; z < 16; z++) {
                    for (int x = 0; x < 16; x++) {
                        if (section.getBlockState(x, y, z).is(FrontierTags.SETTLES)) {
                            n++;
                        }
                    }
                }
            }
            counts[i] = n;
        }
        return counts;
    }

    public static long keyOf(BlockPos pos) {
        return SectionKey.of(pos.getX() >> 4, pos.getY() >> 4, pos.getZ() >> 4);
    }

    /**
     * The tier at the ground in the middle of a loaded chunk: what the maps show for its column. With {@code mayWorkOut}
     * false, only a tier already worked out is used, and null is returned if there is none (as a chunk unloads, when working
     * it out would find nothing loaded).
     */
    public Tier groundTier(LevelChunk chunk, boolean mayWorkOut) {
        int x = chunk.getPos().getMinBlockX() + 8;
        int z = chunk.getPos().getMinBlockZ() + 8;
        int y = chunk.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, 8, 8) + 1;
        BlockPos pos = new BlockPos(x, y, z);
        if (mayWorkOut) {
            return tierAt(pos);
        }
        byte stored = base.get(keyOf(pos));
        if (stored < 0) {
            return null;
        }
        boolean nearby = stored == Tier.FRONTIER.ordinal() && settled.anyWithin(keyOf(pos), params.ringRadius());
        return TierRules.resolve(true, Tier.byId(stored), nearby, y, params);
    }
}
