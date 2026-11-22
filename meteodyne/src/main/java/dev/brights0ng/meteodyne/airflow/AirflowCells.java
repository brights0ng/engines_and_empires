// Written by Claude (GreedyFluids M7b); edited by Claude (M7c: hot walls; M7f: real stone from the config; M8a: the
// surface film from the config; M8e: the fixed walls' surface and keep-fine radius from the config; M8e e3: the dynamic
// start's switch from the config; M8e4: the surface choice removed, and a new code's table keeps every setting).
package dev.brights0ng.meteodyne.airflow;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

import dev.brights0ng.greedyfluids.grid.CellGrid3D;
import dev.brights0ng.greedyfluids.grid.Material;
import dev.brights0ng.greedyfluids.solve.SolverConfig3D;
import dev.brights0ng.meteodyne.Meteodyne;
import dev.brights0ng.meteodyne.heat.scan.ScanFormat;
import dev.brights0ng.meteodyne.heat.world.HeatTables;
import dev.brights0ng.meteodyne.heat.world.InsulationData;
import dev.brights0ng.meteodyne.rain.WeatherConfig;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.TrapDoorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.AABB;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.TagsUpdatedEvent;
import net.neoforged.neoforge.registries.datamaps.DataMapsUpdatedEvent;

/**
 * What each block state is to GreedyFluids (M7b b2), as one byte code per cell: air, a solid of some material, or a
 * door, shut or open. The rules, in order:
 * <ol>
 *   <li>Blocks tagged {@code meteodyne:airflow_solid} are solids (beds by default: the shape rule alone would let
 *       air under and over them).</li>
 *   <li>Blocks holding water or lava (waterlogged ones too) are solids of the fluid's material; a block whose
 *       {@code heat_power} gives a {@code wall_temperature} (lava, magma; M7c) is a wall fixed at it.</li>
 *   <li>Blocks tagged {@code meteodyne:airflow_air} are air (fences and iron bars by default: their collision
 *       spans the cell, but air passes).</li>
 *   <li>Then the scanner's class ({@link ScanFormat#classify}): AIR and POROUS (leaves) are air; doors and trapdoors
 *       are doors, open or shut as the state says; fence gates are air; PARTIAL blocks follow
 *       {@link AirflowShapeRule}; SOLID is solid.</li>
 * </ol>
 * A solid's or door's material comes from {@code meteodyne:insulation} through {@link HeatTables#cell} (so a
 * waterlogged block is water's material): the table's stone-relative values times stone's own, with capacity floored
 * at 0.01 of stone's (GreedyFluids needs it positive). Stone's own (M7f; Bright, 2026-11-15: real stone by default)
 * come from {@code [heat] airflowStoneConductivity} (2.5 W/(m·K)) and {@code airflowStoneHeatCapacity}
 * (2.0 MJ/(m³·K)), read when a generation starts. GreedyFluids' benches keep their own {@link Material#STONE}
 * (42 W/(m·K) and 3.5 × air's heat capacity, legacy Meteodyne's game scale).
 *
 * <p>{@link #code} classifies on the server thread (it reads the heat tables). Codes keep their meaning for one
 * generation; a server-side data-map or tag reload starts a new one, empty, and sections cached before must be read
 * again. {@link #table} is an immutable snapshot of the codes so far, safe on any thread.
 */
@EventBusSubscriber(modid = Meteodyne.MODID)
public final class AirflowCells {

    /** The code of air, in every generation. */
    public static final byte AIR = 0;
    /** Blocks that are always solids to GreedyFluids. */
    public static final TagKey<Block> AIRFLOW_SOLID = BlockTags.create(Meteodyne.id("airflow_solid"));
    /** Blocks that are always air to GreedyFluids (fences and iron bars by default). */
    public static final TagKey<Block> AIRFLOW_AIR = BlockTags.create(Meteodyne.id("airflow_air"));
    /** Distinct cells a generation can hold; past it, solids fall back to stone's code (logged once). */
    static final int MAX_CODES = 256;
    /** Lowest heat capacity, relative to stone, a material is given. */
    static final double MIN_CAPACITY = 0.01;

    /** What a code is. */
    public enum Kind { AIR, SOLID, DOOR_SHUT, DOOR_OPEN }

    /**
     * One code's meaning: its kind; for solids and doors, its material (null for air); and for a solid, the
     * temperature its wall is fixed at, C (NaN for none; M7c).
     */
    public record Cell(Kind kind, Material material, double wall) {
        /** A cell with no fixed wall. */
        public Cell(Kind kind, Material material) {
            this(kind, material, Double.NaN);
        }

        /** Writes this cell into a GreedyFluids grid at (x, y, z), grid cells. */
        public void apply(CellGrid3D grid, int x, int y, int z) {
            switch (kind) {
                case AIR -> grid.setAir(x, y, z);
                case SOLID -> {
                    grid.setSolid(x, y, z, material);
                    if (!Double.isNaN(wall)) {
                        grid.setFixedTemperature(x, y, z, wall);
                    }
                }
                case DOOR_SHUT, DOOR_OPEN -> grid.setDoor(x, y, z, material, kind == Kind.DOOR_OPEN);
            }
        }

        /** Whether the cell holds simulated air (air and doors). */
        public boolean fluid() {
            return kind != Kind.SOLID;
        }
    }

    /** The codes of one generation so far; immutable (the array is never written after the table is made). */
    public static final class Table {
        /** The config's default surface film, m²·K/W: still indoor air ({@code [heat] airflowSurfaceFilm}). */
        public static final double DEFAULT_SURFACE_FILM = 0.13;
        private final long generation;
        private final Cell[] cells;
        private final double filmResistance;
        private final int fixedKeepFine;
        /** Whether regions start from the dynamic start's estimate (M8e e3; {@code [heat] airflowDynamicStart}). */
        private final boolean dynamicStart;

        Table(long generation, Cell[] cells, double filmResistance, int fixedKeepFine, boolean dynamicStart) {
            this.generation = generation;
            this.cells = cells;
            this.filmResistance = filmResistance;
            this.fixedKeepFine = fixedKeepFine;
            this.dynamicStart = dynamicStart;
        }

        /**
         * As above with the dynamic start off: tables built without the config (replays, unit tests) start cold, as
         * before M8e e3; the config's table ({@link AirflowCells#table}) takes {@code [heat] airflowDynamicStart}.
         */
        Table(long generation, Cell[] cells, double filmResistance, int fixedKeepFine) {
            this(generation, cells, filmResistance, fixedKeepFine, false);
        }

        /** As above with the library's default keep-fine radius around fixed walls (replays and unit tests). */
        Table(long generation, Cell[] cells, double filmResistance) {
            this(generation, cells, filmResistance, CellGrid3D.DEFAULT_FIXED_KEEP_FINE);
        }

        /** A table with the default surface film (unit tests that build their own tables, without the config). */
        Table(long generation, Cell[] cells) {
            this(generation, cells, DEFAULT_SURFACE_FILM * SolverConfig3D.DEFAULT.rhoCp());
        }

        public long generation() {
            return generation;
        }

        /** The surface film on each face of a solid in this generation, s/m (GreedyFluids' unit; M8a). */
        public double filmResistance() {
            return filmResistance;
        }

        /** The keep-fine cube radius around fixed walls that face air in this generation, cells (0: none; M8e). */
        public int fixedKeepFine() {
            return fixedKeepFine;
        }

        /** Whether regions start from the dynamic start's estimate ({@code [heat] airflowDynamicStart}; M8e e3). */
        public boolean dynamicStart() {
            return dynamicStart;
        }

        /** This table with another keep-fine radius around fixed walls (scenarios and replays that compare them; M8e). */
        Table withFixedKeepFine(int keepFine) {
            return new Table(generation, cells, filmResistance, keepFine, dynamicStart);
        }

        /** This table with the dynamic start on or off (scenarios, which start cold unless they ask; M8e e3). */
        Table withDynamicStart(boolean on) {
            return new Table(generation, cells, filmResistance, fixedKeepFine, on);
        }

        /**
         * This table with more codes and every setting kept (a newly seen cell; M8e4: rebuilding it from the settings
         * by hand once dropped the dynamic start's switch, so it was off in game after the first new block type).
         */
        Table withCells(Cell[] more) {
            return new Table(generation, more, filmResistance, fixedKeepFine, dynamicStart);
        }

        /** Codes in use, AIR included. */
        public int size() {
            return cells.length;
        }

        /** The meaning of {@code code} (an unsigned byte below {@link #size}). */
        public Cell cell(byte code) {
            return cells[code & 0xFF];
        }
    }

    private static final Cell AIR_CELL = new Cell(Kind.AIR, null);
    private static final Map<BlockState, Byte> CODES = new IdentityHashMap<>();
    private static final Map<Cell, Byte> INTERNED = new HashMap<>();
    private static final Map<Long, Material> MATERIALS = new HashMap<>();
    private static final List<Cell> CELLS = new ArrayList<>();
    private static long generation = 1;
    private static boolean overflowLogged;
    private static volatile Table table;
    /** Stone's per-block conductance, m/s, and heat capacity relative to air's, for this generation. */
    private static double stoneConductance, stoneCapacity;
    /** The surface film for this generation, s/m (the config's m²·K/W times air's heat capacity). */
    private static double filmResistance;

    static {
        reset(1);
    }

    /** The code of {@code state} in the current generation (server thread: reads the heat tables). */
    public static synchronized byte code(BlockState state) {
        Byte code = CODES.get(state);
        if (code == null) {
            code = intern(classify(state));
            CODES.put(state, code);
        }
        return code;
    }

    /** The codes so far, for any thread. */
    public static Table table() {
        return table;
    }

    /** The current generation: a new one starts, empty, at every server-side data-map or tag reload. */
    public static long generation() {
        return table.generation();
    }

    /** What {@code state} is, before interning. */
    static Cell classify(BlockState state) {
        if (state.is(AIRFLOW_SOLID) || Double.isFinite(AirflowSources.wall(state))) {
            return solid(state);
        }
        byte scan = ScanFormat.classify(state);
        if (scan == ScanFormat.WATER || scan == ScanFormat.LAVA) {
            return solid(state);
        }
        if (state.is(AIRFLOW_AIR)) {
            return AIR_CELL;
        }
        return switch (scan) {
            case ScanFormat.AIR, ScanFormat.POROUS -> AIR_CELL;
            case ScanFormat.OPENING -> state.getBlock() instanceof DoorBlock || state.getBlock() instanceof TrapDoorBlock
                    ? new Cell(state.getValue(BlockStateProperties.OPEN) ? Kind.DOOR_OPEN : Kind.DOOR_SHUT, material(state))
                    : AIR_CELL; // fence gates
            case ScanFormat.PARTIAL -> AirflowShapeRule.solid(boxes(state)) ? solid(state) : AIR_CELL;
            default -> solid(state); // SOLID (UNKNOWN is a recording-only class)
        };
    }

    private static Cell solid(BlockState state) {
        double wall = AirflowSources.wall(state);
        return new Cell(Kind.SOLID, material(state), Double.isFinite(wall) ? wall : Double.NaN);
    }

    /** The collision shape's boxes, block units. */
    static List<double[]> boxes(BlockState state) {
        List<double[]> out = new ArrayList<>();
        for (AABB b : state.getCollisionShape(EmptyBlockGetter.INSTANCE, BlockPos.ZERO).toAabbs()) {
            out.add(new double[] {b.minX, b.minY, b.minZ, b.maxX, b.maxY, b.maxZ});
        }
        return out;
    }

    /** One GreedyFluids material per distinct insulation value, named after the first block seen with it. */
    private static Material material(BlockState state) {
        HeatTables.Cell heat = HeatTables.cell(state);
        float conductance = heat.conductance(), capacity = heat.capacity();
        if (!(conductance > 0)) { // an air-shaped block tagged airflow_solid: the heat tables give it no material
            InsulationData own = HeatTables.insulation(state.getBlock());
            conductance = (float) own.conductance();
            capacity = (float) own.capacity();
        }
        long key = (long) Float.floatToIntBits(conductance) << 32 | (Float.floatToIntBits(capacity) & 0xFFFFFFFFL);
        Material m = MATERIALS.get(key);
        if (m == null) {
            m = new Material(BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString(),
                    stoneConductance * conductance, stoneCapacity * Math.max(MIN_CAPACITY, capacity));
            MATERIALS.put(key, m);
        }
        return m;
    }

    /** Stone's per-block conductance in this generation, m/s (conductivity over air's heat capacity, a block a metre). */
    static double stoneConductance() {
        return stoneConductance;
    }

    /** Stone's heat capacity in this generation, relative to air's. */
    static double stoneCapacity() {
        return stoneCapacity;
    }

    /** The code of {@code cell}, added to the table if new (package-visible for AirflowCellsGameTests; M8e4). */
    static synchronized byte intern(Cell cell) {
        Byte code = INTERNED.get(cell);
        if (code != null) {
            return code;
        }
        if (CELLS.size() >= MAX_CODES) {
            if (!overflowLogged) {
                overflowLogged = true;
                Meteodyne.LOGGER.warn("Airflow: more than {} distinct cells; further ones use stone's", MAX_CODES);
            }
            return cell.kind() == Kind.AIR ? AIR : stoneCode();
        }
        code = (byte) CELLS.size();
        CELLS.add(cell);
        INTERNED.put(cell, code);
        table = table.withCells(CELLS.toArray(Cell[]::new));
        return code;
    }

    /** A code already in the table for a plain solid: stone's if there is one, else the first plain solid's, else air. */
    private static byte stoneCode() {
        byte fallback = AIR;
        for (int i = 0; i < CELLS.size(); i++) {
            Cell c = CELLS.get(i);
            if (c.kind() == Kind.SOLID && Double.isNaN(c.wall())) {
                if (c.material().conductancePerBlock() == stoneConductance
                        && c.material().relHeatCapacity() == stoneCapacity) {
                    return (byte) i;
                }
                if (fallback == AIR) {
                    fallback = (byte) i;
                }
            }
        }
        return fallback;
    }

    /** Starts generation {@code next}, empty but for AIR. */
    private static synchronized void reset(long next) {
        generation = next;
        CODES.clear();
        INTERNED.clear();
        MATERIALS.clear();
        CELLS.clear();
        overflowLogged = false;
        double rhoCp = SolverConfig3D.DEFAULT.rhoCp();
        stoneConductance = WeatherConfig.airflowStoneConductivity() / rhoCp;
        stoneCapacity = WeatherConfig.airflowStoneHeatCapacity() * 1e6 / rhoCp;
        filmResistance = WeatherConfig.airflowSurfaceFilm() * rhoCp;
        CELLS.add(AIR_CELL);
        INTERNED.put(AIR_CELL, AIR);
        table = new Table(generation, CELLS.toArray(Cell[]::new), filmResistance,
                WeatherConfig.airflowFixedWallKeepFine(), WeatherConfig.airflowDynamicStart());
    }

    @SubscribeEvent
    static void onDataMapsUpdated(DataMapsUpdatedEvent event) {
        if (event.getCause() == DataMapsUpdatedEvent.UpdateCause.SERVER_RELOAD) {
            reset(generation + 1);
        }
    }

    @SubscribeEvent
    static void onTagsUpdated(TagsUpdatedEvent event) {
        if (event.getUpdateCause() == TagsUpdatedEvent.UpdateCause.SERVER_DATA_LOAD) {
            reset(generation + 1);
        }
    }

    /** For tests: the cells of the current generation, in code order. */
    static List<Cell> cells() {
        return List.copyOf(Arrays.asList(table.cells));
    }

    private AirflowCells() {
    }
}
