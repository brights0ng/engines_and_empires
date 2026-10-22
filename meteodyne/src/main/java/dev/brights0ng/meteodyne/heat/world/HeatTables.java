// Updated by ChatGPT: classified source, material and physical geometry changes for snapshot invalidation.
package dev.brights0ng.meteodyne.heat.world;

import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

import dev.brights0ng.meteodyne.Meteodyne;
import dev.brights0ng.meteodyne.heat.HeatField;
import dev.brights0ng.meteodyne.heat.HeatSourceData;
import dev.brights0ng.paradynelib.heat.air.AirGrid;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.BooleanOp;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.registries.datamaps.DataMapType;
import net.neoforged.neoforge.registries.datamaps.DataMapsUpdatedEvent;
import net.neoforged.neoforge.registries.datamaps.RegisterDataMapTypesEvent;

/**
 * The heat simulation's two block tables (weather phase 8f-2): {@code heat_power} (what puts heat into the air) and
 * {@code insulation} (how blocks hold and pass it), and what each block state is as a cell ({@link #cell}). Lookups
 * are cached per block and per state and cleared when data maps reload. Server thread only.
 */
@EventBusSubscriber(modid = Meteodyne.MODID)
public final class HeatTables {

    public static final DataMapType<Block, HeatPowerData> POWER = DataMapType.builder(
            Meteodyne.id("heat_power"), Registries.BLOCK,
            HeatPowerData.CODEC).build();

    public static final DataMapType<Block, InsulationData> INSULATION = DataMapType.builder(
            Meteodyne.id("insulation"), Registries.BLOCK,
            InsulationData.CODEC).build();

    /**
     * What a block state is as a cell: {@link AirGrid#AIR} or {@link AirGrid#SOLID}, the faces air passes through
     * (solids), and its material.
     */
    public record Cell(byte kind, int openFaces, float conductance, float capacity) {
        static final Cell AIR = new Cell(AirGrid.AIR, AirGrid.ALL_FACES, 0, 0);
    }

    private static final HeatPowerData NO_POWER = new HeatPowerData(List.of());
    private static final Map<Block, HeatPowerData> POWER_CACHE = new IdentityHashMap<>();
    private static final Map<Block, InsulationData> INSULATION_CACHE = new IdentityHashMap<>();
    private static final Map<BlockState, Cell> CELLS = new IdentityHashMap<>();
    private static long revision;

    /** Invalidates prepared material snapshots after a data-map reload. */
    static synchronized long revision() {
        return revision;
    }

    record Change(boolean geometry, boolean material, boolean heightmap) { }

    /** Compare the inputs used by snapshots, not source power or unrelated state properties.
     * Dynamic collision shapes are deliberately conservative; neighbor-dependent state changes still arrive
     * through the block hook. This is the legacy geometry contract, not the future partial-air topology bake.
     */
    static Change change(BlockState before, BlockState after) {
        if (before == null) {
            return new Change(true, true, true); // Deferred off-thread changes have no coherent old world context.
        }
        if (before == after) {
            return new Change(false, false, false);
        }
        Cell a = cell(before), b = cell(after);
        boolean heightmap = (before.blocksMotion() || !before.getFluidState().isEmpty())
                != (after.blocksMotion() || !after.getFluidState().isEmpty());
        boolean geometry = heightmap || a.kind() != b.kind() || a.openFaces() != b.openFaces()
                || before.getFluidState() != after.getFluidState()
                || before.getBlock().hasDynamicShape() || after.getBlock().hasDynamicShape()
                || Shapes.joinIsNotEmpty(before.getCollisionShape(EmptyBlockGetter.INSTANCE, BlockPos.ZERO),
                        after.getCollisionShape(EmptyBlockGetter.INSTANCE, BlockPos.ZERO), BooleanOp.NOT_SAME);
        return new Change(geometry, a.conductance() != b.conductance() || a.capacity() != b.capacity(), heightmap);
    }

    /** Registers the data maps (mod bus; added in the mod class). */
    public static void register(RegisterDataMapTypesEvent event) {
        event.register(POWER);
        event.register(INSULATION);
    }

    @SubscribeEvent
    static void onDataMapsUpdated(DataMapsUpdatedEvent event) {
        clear();
    }

    /** Forgets everything looked up (data maps reloaded). */
    public static synchronized void clear() {
        revision++;
        POWER_CACHE.clear();
        INSULATION_CACHE.clear();
        CELLS.clear();
    }

    /** What {@code state} puts into the air, or null if nothing (or not in this state). */
    public static synchronized HeatPowerData.Variant power(BlockState state) {
        HeatPowerData d = data(state.getBlock());
        return d == NO_POWER ? null : d.find(state);
    }

    /** Every driven variant of {@code state} (8f-3: on by its block entity), or empty. */
    public static synchronized List<HeatPowerData.Variant> driven(BlockState state) {
        HeatPowerData d = data(state.getBlock());
        return d == NO_POWER ? List.of() : d.driven(state);
    }

    @SuppressWarnings("deprecation")
    private static HeatPowerData data(Block block) {
        HeatPowerData d = POWER_CACHE.get(block);
        if (d == null) {
            d = block.builtInRegistryHolder().getData(POWER);
            if (d == null) {
                d = NO_POWER;
            }
            POWER_CACHE.put(block, d);
        }
        return d;
    }

    /** {@code state}'s insulation ({@link InsulationData#STONE} when the table doesn't list it). */
    @SuppressWarnings("deprecation")
    public static synchronized InsulationData insulation(Block block) {
        InsulationData d = INSULATION_CACHE.get(block);
        if (d == null) {
            d = block.builtInRegistryHolder().getData(INSULATION);
            if (d == null) {
                d = InsulationData.STONE;
            }
            INSULATION_CACHE.put(block, d);
        }
        return d;
    }

    /**
     * The absolute air temperature, C, a source stops at: its own {@code limit}, else its {@code heat_sources} limit,
     * else NaN.
     */
    public static double absoluteLimit(BlockState state, HeatPowerData.Variant v) {
        if (!Double.isNaN(v.limit())) {
            return v.limit();
        }
        HeatSourceData.Variant block = HeatField.variantOf(state);
        return block == null ? Double.NaN : block.limit();
    }

    /**
     * The limit as an offset from the outside air {@code outsideC}, for the grid: the stricter of {@code max_offset}
     * and the absolute limit, in the direction the source pushes. NaN for none.
     */
    public static double offsetLimit(BlockState state, HeatPowerData.Variant v, double outsideC) {
        double abs = absoluteLimit(state, v);
        double fromAbs = Double.isNaN(abs) ? Double.NaN : abs - outsideC;
        double max = v.maxOffset();
        if (v.power() > 0) {
            if (Double.isNaN(max)) {
                return fromAbs;
            }
            return Double.isNaN(fromAbs) ? max : Math.min(max, fromAbs);
        }
        if (Double.isNaN(max)) {
            return fromAbs;
        }
        return Double.isNaN(fromAbs) ? -max : Math.max(-max, fromAbs);
    }

    /**
     * What {@code state} is as a cell: air if it has no collision shape and holds no fluid (air, torches, flowers);
     * water (any fluid but lava, waterlogged blocks too) as a closed solid of water's material; anything else a solid
     * of its material, open on the faces its collision shape doesn't fill (a slab's empty half, an open door's sides).
     */
    public static synchronized Cell cell(BlockState state) {
        Cell c = CELLS.get(state);
        if (c == null) {
            c = classify(state);
            CELLS.put(state, c);
        }
        return c;
    }

    private static Cell classify(BlockState state) {
        if (state.isAir()) {
            return Cell.AIR;
        }
        boolean fluid = !state.getFluidState().isEmpty();
        if (fluid && !state.is(Blocks.LAVA)) {
            InsulationData water = insulation(Blocks.WATER);
            InsulationData own = insulation(state.getBlock());
            InsulationData m = own == InsulationData.STONE ? water : own;
            return new Cell(AirGrid.SOLID, 0, (float) m.conductance(), (float) m.capacity());
        }
        VoxelShape shape = state.getCollisionShape(EmptyBlockGetter.INSTANCE, BlockPos.ZERO);
        if (shape.isEmpty() && !fluid) {
            return Cell.AIR;
        }
        int open = 0;
        if (!shape.isEmpty()) {
            open |= Block.isFaceFull(shape, Direction.WEST) ? 0 : AirGrid.WEST;
            open |= Block.isFaceFull(shape, Direction.EAST) ? 0 : AirGrid.EAST;
            open |= Block.isFaceFull(shape, Direction.DOWN) ? 0 : AirGrid.DOWN;
            open |= Block.isFaceFull(shape, Direction.UP) ? 0 : AirGrid.UP;
            open |= Block.isFaceFull(shape, Direction.NORTH) ? 0 : AirGrid.NORTH;
            open |= Block.isFaceFull(shape, Direction.SOUTH) ? 0 : AirGrid.SOUTH;
        }
        InsulationData m = insulation(state.getBlock());
        return new Cell(AirGrid.SOLID, open, (float) m.conductance(), (float) m.capacity());
    }

    private HeatTables() {
    }
}
