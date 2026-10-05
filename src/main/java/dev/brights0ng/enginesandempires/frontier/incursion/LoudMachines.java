package dev.brights0ng.enginesandempires.frontier.incursion;

import java.util.IdentityHashMap;
import java.util.Map;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import com.simibubi.create.content.kinetics.base.GeneratingKineticBlockEntity;
import com.simibubi.create.content.kinetics.base.KineticBlockEntity;

import dev.brights0ng.enginesandempires.EnginesAndEmpiresMod;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongIterator;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.neoforged.neoforge.registries.datamaps.DataMapType;

/**
 * The loud machines in a level's loaded chunks (Frontier phase 6): the ones in the data map
 * {@code engines_and_empires:loud_machines} ({@code data/<ns>/data_maps/block/loud_machines.json}), each with a weight. A
 * machine that ran in the last day adds its weight to its settlement's incursion score.
 *
 * <p>Positions are found as chunks load and blocks change, kept only in memory. Every {@link #SAMPLE_INTERVAL} ticks each is
 * checked for running, and the time it last ran is written to {@link IncursionData}, which saves it.
 *
 * <p>Running means: a Create kinetic block turning; a block whose {@code powered} or {@code lit} property is on (steam
 * whistles, Aeronautics' burners and vents); or, for engines that drive a separate shaft (the steam engine, the huge diesel
 * engine), a generating shaft turning within two blocks.
 */
public final class LoudMachines {

    /** A block's weight toward an incursion score while it runs. */
    public record LoudMachine(double weight) {
        public static final Codec<LoudMachine> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                Codec.doubleRange(0.0, 1000.0).fieldOf("weight").forGetter(LoudMachine::weight)
        ).apply(instance, LoudMachine::new));
    }

    public static final DataMapType<Block, LoudMachine> DATA_MAP = DataMapType.builder(
            ResourceLocation.fromNamespaceAndPath(EnginesAndEmpiresMod.MODID, "loud_machines"), Registries.BLOCK,
            LoudMachine.CODEC).build();

    public static final int SAMPLE_INTERVAL = 100;

    private static final Map<Block, Double> WEIGHTS = new IdentityHashMap<>();

    private final ServerLevel level;
    private final Long2ObjectOpenHashMap<LongOpenHashSet> byChunk = new Long2ObjectOpenHashMap<>();

    public LoudMachines(ServerLevel level) {
        this.level = level;
    }

    /** A block's loudness (0 for a quiet block). */
    @SuppressWarnings("deprecation")
    public static double weight(BlockState state) {
        Block block = state.getBlock();
        Double cached = WEIGHTS.get(block);
        if (cached == null) {
            LoudMachine machine = block.builtInRegistryHolder().getData(DATA_MAP);
            cached = machine == null ? 0.0 : machine.weight();
            WEIGHTS.put(block, cached);
        }
        return cached;
    }

    /** Forgets cached weights (data packs reloaded). */
    public static void clearCache() {
        WEIGHTS.clear();
    }

    public void onChunkLoad(LevelChunk chunk) {
        LongOpenHashSet found = new LongOpenHashSet();
        LevelChunkSection[] sections = chunk.getSections();
        int baseX = chunk.getPos().getMinBlockX();
        int baseZ = chunk.getPos().getMinBlockZ();
        for (int i = 0; i < sections.length; i++) {
            LevelChunkSection section = sections[i];
            if (section == null || section.hasOnlyAir() || !section.getStates().maybeHas(state -> weight(state) > 0)) {
                continue;
            }
            int baseY = (chunk.getMinSection() + i) << 4;
            for (int y = 0; y < 16; y++) {
                for (int z = 0; z < 16; z++) {
                    for (int x = 0; x < 16; x++) {
                        if (weight(section.getBlockState(x, y, z)) > 0) {
                            found.add(BlockPos.asLong(baseX + x, baseY + y, baseZ + z));
                        }
                    }
                }
            }
        }
        if (found.isEmpty()) {
            byChunk.remove(chunk.getPos().toLong());
        } else {
            byChunk.put(chunk.getPos().toLong(), found);
        }
    }

    public void onChunkUnload(LevelChunk chunk) {
        byChunk.remove(chunk.getPos().toLong());
    }

    public void onBlockChanged(BlockPos pos, BlockState before, BlockState after) {
        boolean was = weight(before) > 0;
        boolean is = weight(after) > 0;
        if (was == is) {
            return;
        }
        long at = pos.asLong();
        if (!level.getServer().isSameThread()) {
            level.getServer().execute(() -> update(at, is));
            return;
        }
        update(at, is);
    }

    private void update(long pos, boolean loud) {
        long chunk = ChunkPos.asLong(BlockPos.getX(pos) >> 4, BlockPos.getZ(pos) >> 4);
        if (loud) {
            byChunk.computeIfAbsent(chunk, c -> new LongOpenHashSet()).add(pos);
        } else {
            LongOpenHashSet set = byChunk.get(chunk);
            if (set != null && set.remove(pos) && set.isEmpty()) {
                byChunk.remove(chunk);
            }
        }
    }

    public void tick() {
        long now = level.getGameTime();
        if (now % SAMPLE_INTERVAL != 0 || byChunk.isEmpty()) {
            return;
        }
        IncursionData data = IncursionData.of(level);
        BlockPos.MutableBlockPos at = new BlockPos.MutableBlockPos();
        for (LongOpenHashSet set : byChunk.values()) {
            for (LongIterator it = set.iterator(); it.hasNext(); ) {
                long pos = it.nextLong();
                at.set(pos);
                BlockState state = level.getBlockState(at);
                double weight = weight(state);
                if (weight > 0 && isRunning(at, state)) {
                    data.machineRan(pos, weight, now);
                }
            }
        }
    }

    /** How many loud machines are tracked (the debug command). */
    public int tracked() {
        int n = 0;
        for (LongOpenHashSet set : byChunk.values()) {
            n += set.size();
        }
        return n;
    }

    /** Whether the machine at {@code pos} is running right now. */
    public boolean isRunning(BlockPos pos, BlockState state) {
        if (state.hasProperty(BlockStateProperties.POWERED) && state.getValue(BlockStateProperties.POWERED)) {
            return true;
        }
        if (state.hasProperty(BlockStateProperties.LIT) && state.getValue(BlockStateProperties.LIT)) {
            return true;
        }
        BlockEntity be = level.getBlockEntity(pos);
        if (be instanceof KineticBlockEntity kinetic) {
            return kinetic.getSpeed() != 0;
        }
        // An engine driving a shaft of its own (steam engine, huge diesel engine): is a generating shaft nearby turning?
        for (Direction direction : Direction.values()) {
            for (int step = 1; step <= 2; step++) {
                if (level.getBlockEntity(pos.relative(direction, step)) instanceof GeneratingKineticBlockEntity shaft
                        && shaft.getSpeed() != 0) {
                    return true;
                }
            }
        }
        return false;
    }
}
