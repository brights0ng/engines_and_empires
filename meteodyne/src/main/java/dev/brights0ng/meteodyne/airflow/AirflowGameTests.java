// Written by Claude (GreedyFluids M7b); edited by Claude (b2: classifier, section cache and snapshot check; b3: a
// region around an anchor; b4: a region around a player, the commands and recorder, the switch; M7d d1: tiles; d2:
// saving and restoring; M7c: watts and hot walls from the heat tables; M7e: the speed limit; M7f: stone from the
// config, the recorder's profile; f3: AIRFLOW is the config's default, game-test servers run LEGACY; M8a: AIRFLOW
// started by the server's own handlers; the surface film from the config; M8b b4: secondaries, and the tests that count
// regions run without them; M8b5b: a cut room merges into one region and splits, the B3 test with merging off).
package dev.brights0ng.meteodyne.airflow;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.UnaryOperator;

import com.mojang.brigadier.exceptions.CommandSyntaxException;

import dev.brights0ng.greedyfluids.grid.CellGrid3D;
import dev.brights0ng.greedyfluids.grid.CellType;
import dev.brights0ng.greedyfluids.grid.Material;
import dev.brights0ng.greedyfluids.region.AirFrame;
import dev.brights0ng.greedyfluids.region.AirRegion;
import dev.brights0ng.greedyfluids.solve.Background3D;
import dev.brights0ng.meteodyne.Meteodyne;
import dev.brights0ng.meteodyne.airflow.AirflowCells.Cell;
import dev.brights0ng.meteodyne.airflow.AirflowCells.Kind;
import dev.brights0ng.meteodyne.heat.scan.ScanFormat;
import dev.brights0ng.meteodyne.heat.world.HeatTables;
import dev.brights0ng.meteodyne.rain.WeatherConfig;
import dev.brights0ng.paradynelib.gametest.GameTestStructures;
import net.minecraft.commands.arguments.blocks.BlockStateParser;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.SectionPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.SnowLayerBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * GreedyFluids M7b in the game, where the unit tests can't go (they run on Gradle's classpath, without Minecraft):
 * <ul>
 *   <li>b1: the bundled library loads and runs; the engine switch defaults to AIRFLOW (M7f f3) and a game-test
 *       server runs LEGACY;</li>
 *   <li>b2: the classifier's table ({@link AirflowCells}) and the section cache ({@link AirflowSections}): reading,
 *       edits, copy-on-write, dropping, and uniform sections from the palette;</li>
 *   <li>b2, opt-in: the container check, re-classifying the GreedyFluids snapshots with the in-game rule
 *       ({@code -Pmeteodyne.airflowSnapshots=<scans folder>}).</li>
 *   <li>b3: a region forms around an anchor on the air thread, hears an edit, and stops with its thread (b4 adds
 *       the rest: players, doors, sources, the switch).</li>
 *   <li>b4: a region forms around a (mock) player and hears a door placed, opened, and a campfire; the commands and
 *       the recorder answer; stopping leaves no air thread; airflow mode stands the legacy regions down (so
 *       {@code roomAt} reads 0) and legacy comes back unchanged.</li>
 * </ul>
 * Runs in the bare Meteodyne run, the core-less run and the aggregate. The section tests use the top sections of
 * the build height above their own spot, where no other test builds, and put the blocks back.
 */
@GameTestHolder(Meteodyne.MODID)
@PrefixGameTestTemplate(false)
public final class AirflowGameTests {

    /** An 8 m cube, open above and at the sides, on a stone floor, with a 200 W source in the middle of the air. */
    @GameTest(template = GameTestStructures.EMPTY)
    public static void libraryStepsARegionInGame(GameTestHelper helper) {
        int n = 8;
        CellGrid3D grid = new CellGrid3D(n, n, n);
        grid.fillSolid(0, 0, 0, n - 1, 0, n - 1, Material.STONE);
        AirRegion region = new AirRegion(grid, Background3D.uniform(n, n, n, 10), 0, 0);
        region.setSource(4, 1, 4, 200);
        for (int i = 0; i < 20; i++) {
            region.step();
        }
        AirFrame frame = region.publish();
        double t = frame.temperature(4.5, 1.5, 4.5);
        helper.assertTrue(Math.abs(region.time() - 10) < 1e-9, "20 steps of 0.5 s should be 10 s, got " + region.time());
        helper.assertTrue(Double.isFinite(t) && t > 10, "the source's air should be warmer than the 10 C background, got " + t);
        helper.assertTrue(WeatherConfig.HEAT_ENGINE.getDefault() == Airflow.Engine.AIRFLOW,
                "the config's default engine should be AIRFLOW, got " + WeatherConfig.HEAT_ENGINE.getDefault());
        helper.assertTrue(Airflow.engine() == Airflow.Engine.LEGACY, "a game-test server should run LEGACY, got " + Airflow.engine());
        helper.succeed();
    }

    /** The plan's table, block by block, with materials from the insulation map and codes shared by equal cells. */
    @GameTest(template = GameTestStructures.EMPTY)
    public static void classifierMapsBlocksToCells(GameTestHelper helper) {
        List<String> wrong = new ArrayList<>();
        BlockState door = Blocks.OAK_DOOR.defaultBlockState();
        BlockState trapdoor = Blocks.OAK_TRAPDOOR.defaultBlockState();
        BlockState gate = Blocks.OAK_FENCE_GATE.defaultBlockState();
        UnaryOperator<BlockState> northSouth = s -> s.setValue(BlockStateProperties.NORTH, true)
                .setValue(BlockStateProperties.SOUTH, true);

        expect(wrong, Blocks.AIR.defaultBlockState(), Kind.AIR);
        expect(wrong, Blocks.CAVE_AIR.defaultBlockState(), Kind.AIR);
        expect(wrong, Blocks.STONE.defaultBlockState(), Kind.SOLID);
        expect(wrong, Blocks.OAK_PLANKS.defaultBlockState(), Kind.SOLID);
        expect(wrong, door, Kind.DOOR_SHUT);
        expect(wrong, door.setValue(DoorBlock.HALF, DoubleBlockHalf.UPPER), Kind.DOOR_SHUT);
        expect(wrong, door.setValue(DoorBlock.OPEN, true), Kind.DOOR_OPEN);
        expect(wrong, trapdoor, Kind.DOOR_SHUT);
        expect(wrong, trapdoor.setValue(BlockStateProperties.OPEN, true), Kind.DOOR_OPEN);
        expect(wrong, gate, Kind.AIR);
        expect(wrong, gate.setValue(FenceGateBlock.OPEN, true), Kind.AIR);
        expect(wrong, Blocks.OAK_FENCE.defaultBlockState(), Kind.AIR);
        expect(wrong, northSouth.apply(Blocks.OAK_FENCE.defaultBlockState()), Kind.AIR);
        expect(wrong, northSouth.apply(Blocks.IRON_BARS.defaultBlockState()), Kind.AIR);
        expect(wrong, Blocks.GLASS_PANE.defaultBlockState(), Kind.AIR);
        expect(wrong, northSouth.apply(Blocks.GLASS_PANE.defaultBlockState()), Kind.SOLID);
        expect(wrong, Blocks.OAK_SLAB.defaultBlockState(), Kind.SOLID);
        expect(wrong, Blocks.WHITE_CARPET.defaultBlockState(), Kind.AIR);
        expect(wrong, Blocks.SNOW.defaultBlockState(), Kind.AIR);
        expect(wrong, Blocks.SNOW.defaultBlockState().setValue(SnowLayerBlock.LAYERS, 4), Kind.AIR);
        expect(wrong, Blocks.SNOW.defaultBlockState().setValue(SnowLayerBlock.LAYERS, 5), Kind.SOLID);
        expect(wrong, Blocks.OAK_STAIRS.defaultBlockState(), Kind.SOLID);
        expect(wrong, Blocks.FARMLAND.defaultBlockState(), Kind.SOLID);
        expect(wrong, Blocks.RED_BED.defaultBlockState(), Kind.SOLID);
        expect(wrong, Blocks.CHEST.defaultBlockState(), Kind.SOLID);
        expect(wrong, Blocks.CAMPFIRE.defaultBlockState(), Kind.AIR);
        expect(wrong, Blocks.TORCH.defaultBlockState(), Kind.AIR);
        expect(wrong, Blocks.OAK_LEAVES.defaultBlockState(), Kind.AIR);
        expect(wrong, Blocks.WATER.defaultBlockState(), Kind.SOLID);
        expect(wrong, Blocks.LAVA.defaultBlockState(), Kind.SOLID);
        expect(wrong, Blocks.OAK_FENCE.defaultBlockState().setValue(BlockStateProperties.WATERLOGGED, true), Kind.SOLID);
        expect(wrong, Blocks.OAK_SLAB.defaultBlockState().setValue(BlockStateProperties.WATERLOGGED, true), Kind.SOLID);
        helper.assertTrue(wrong.isEmpty(), "cells: " + String.join("; ", wrong));

        Cell stone = AirflowCells.classify(Blocks.STONE.defaultBlockState());
        HeatTables.Cell stoneHeat = HeatTables.cell(Blocks.STONE.defaultBlockState());
        helper.assertTrue(stoneHeat.conductance() != 1 || stoneHeat.capacity() != 1
                        || stone.material().conductancePerBlock() == AirflowCells.stoneConductance()
                        && stone.material().relHeatCapacity() == AirflowCells.stoneCapacity(),
                "stone's insulation should give the configured stone, got " + stone.material());
        double rhoCp = dev.brights0ng.greedyfluids.solve.SolverConfig3D.DEFAULT.rhoCp();
        helper.assertTrue(Math.abs(AirflowCells.stoneConductance() * rhoCp - WeatherConfig.airflowStoneConductivity()) < 1e-9
                        && Math.abs(AirflowCells.stoneCapacity() * rhoCp - 1e6 * WeatherConfig.airflowStoneHeatCapacity()) < 1e-3,
                "stone is the config's: " + AirflowCells.stoneConductance() + " m/s, " + AirflowCells.stoneCapacity());
        byte lower = AirflowCells.code(door), upper = AirflowCells.code(door.setValue(DoorBlock.HALF, DoubleBlockHalf.UPPER));
        helper.assertTrue(lower == upper, "both halves of a shut door should share a code: " + lower + ", " + upper);
        helper.assertTrue(AirflowCells.code(Blocks.CAVE_AIR.defaultBlockState()) == AirflowCells.AIR, "cave air is code 0");
        byte open = AirflowCells.code(door.setValue(DoorBlock.OPEN, true));
        helper.assertTrue(open != lower, "an open door has its own code");
        AirflowCells.Table table = AirflowCells.table();
        helper.assertTrue(table.cell(open).equals(AirflowCells.classify(door.setValue(DoorBlock.OPEN, true))),
                "the table holds the classified cell");
        helper.succeed();
    }

    private static void expect(List<String> wrong, BlockState state, Kind kind) {
        Cell cell = AirflowCells.classify(state);
        String name = BlockStateParser.serialize(state);
        if (cell.kind() != kind) {
            wrong.add(name + " is " + cell.kind() + ", expected " + kind);
            return;
        }
        if (kind == Kind.AIR) {
            if (cell.material() != null) {
                wrong.add(name + " is air with a material");
            }
            return;
        }
        HeatTables.Cell heat = HeatTables.cell(state);
        double conductance = AirflowCells.stoneConductance() * heat.conductance();
        double capacity = AirflowCells.stoneCapacity() * Math.max(AirflowCells.MIN_CAPACITY, heat.capacity());
        if (Math.abs(cell.material().conductancePerBlock() - conductance) > 1e-9
                || Math.abs(cell.material().relHeatCapacity() - capacity) > 1e-9) {
            wrong.add(name + " has " + cell.material() + ", expected conductance " + conductance + " and capacity " + capacity);
        }
    }

    /** Reading a wanted section, edits through the block hook, copy-on-write views, unload and release. */
    @GameTest(template = GameTestStructures.EMPTY)
    public static void sectionsReadEditAndDrop(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos spot = helper.absolutePos(BlockPos.ZERO);
        int cx = SectionPos.blockToSectionCoord(spot.getX()), cz = SectionPos.blockToSectionCoord(spot.getZ());
        long key = SectionPos.asLong(cx, level.getMaxSection() - 1, cz);
        BlockPos origin = SectionPos.of(key).origin();
        BlockPos a = origin.offset(3, 4, 5), b = origin.offset(10, 11, 12), torch = origin.offset(1, 0, 1);
        helper.assertTrue(level.getChunk(cx, cz).getSection(level.getSectionIndexFromSectionY(SectionPos.y(key))).hasOnlyAir(),
                "the top section above the test should be empty");
        boolean opened = AirflowSections.get(level) == null;
        AirflowSections sections = AirflowSections.open(level);
        Recorder events = new Recorder();
        sections.addListener(events);
        byte stone = AirflowCells.code(Blocks.STONE.defaultBlockState());
        try {
            sections.want(key);
            helper.assertFalse(sections.ready(key), "a wanted section is read at the next fill, not before");
            helper.assertTrue(sections.fill(Long.MAX_VALUE) >= 1 && sections.ready(key), "the fill reads it");
            helper.assertTrue(events.ready.contains(key), "listeners hear the section is ready");
            AirflowSections.Codes empty = sections.codes(key);
            helper.assertTrue(empty.uniform() && empty.code(0) == AirflowCells.AIR, "an empty section is uniform air");

            level.setBlock(a, Blocks.STONE.defaultBlockState(), Block.UPDATE_CLIENTS);
            helper.assertTrue(events.changes.equals(List.of(a + " 0>" + stone)), "one change heard: " + events.changes);
            helper.assertTrue(empty.uniform() && empty.code(3, 4, 5) == AirflowCells.AIR, "an earlier view keeps its codes");
            AirflowSections.Codes one = sections.codes(key);
            helper.assertTrue(!one.uniform() && one.code(3, 4, 5) == stone && one.code(0, 0, 0) == AirflowCells.AIR,
                    "the edit is in the section");

            level.setBlock(b, Blocks.STONE.defaultBlockState(), Block.UPDATE_CLIENTS);
            helper.assertTrue(one.code(10, 11, 12) == AirflowCells.AIR, "a view handed out is copied before the next edit");
            helper.assertTrue(sections.codes(key).code(10, 11, 12) == stone, "the second edit is in the section");
            level.setBlock(torch, Blocks.TORCH.defaultBlockState(), Block.UPDATE_CLIENTS);
            helper.assertTrue(events.changes.size() == 2, "air to a torch keeps code 0, so no change: " + events.changes);

            sections.unloaded(cx, cz);
            helper.assertTrue(events.dropped.contains(key) && !sections.ready(key), "an unload drops the section");
            sections.fill(Long.MAX_VALUE);
            AirflowSections.Codes again = sections.codes(key);
            helper.assertTrue(again != null && again.code(3, 4, 5) == stone && again.code(10, 11, 12) == stone,
                    "a wanted section is read again");

            sections.release(key);
            helper.assertTrue(sections.codes(key) == null, "releasing the last user forgets the section");
            level.setBlock(a, Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS);
            helper.assertTrue(events.changes.size() == 2, "a forgotten section hears no edits: " + events.changes);
        } finally {
            sections.removeListener(events);
            for (BlockPos p : List.of(a, b, torch)) {
                level.setBlock(p, Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS);
            }
            if (opened) {
                AirflowSections.close(level);
            }
        }
        helper.succeed();
    }

    /**
     * A section whose states all share one code is stored as that code, found from the palette. The stone mass is
     * written straight into the chunk section (no light updates or block hooks): 8,192 lit block changes in one tick
     * left a light backlog that other tests' light checks tripped over.
     */
    @GameTest(template = GameTestStructures.EMPTY)
    public static void uniformSectionsComeFromThePalette(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos spot = helper.absolutePos(BlockPos.ZERO);
        int cx = SectionPos.blockToSectionCoord(spot.getX()), cz = SectionPos.blockToSectionCoord(spot.getZ());
        long top = SectionPos.asLong(cx, level.getMaxSection() - 1, cz);
        long below = SectionPos.asLong(cx, level.getMaxSection() - 2, cz);
        BlockPos torch = SectionPos.of(top).origin().offset(7, 0, 7);
        net.minecraft.world.level.chunk.LevelChunkSection massSection = level.getChunk(cx, cz)
                .getSection(level.getSectionIndexFromSectionY(SectionPos.y(below)));
        int flags = Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE;
        boolean opened = AirflowSections.get(level) == null;
        AirflowSections sections = AirflowSections.open(level);
        try {
            level.setBlock(torch, Blocks.TORCH.defaultBlockState(), flags);
            for (int i = 0; i < AirflowSections.SECTION_CELLS; i++) {
                massSection.setBlockState(i & 15, i >> 8, (i >> 4) & 15, Blocks.STONE.defaultBlockState(), false);
            }
            sections.want(top);
            sections.want(below);
            sections.fill(Long.MAX_VALUE);
            AirflowSections.Codes air = sections.codes(top), stone = sections.codes(below);
            helper.assertTrue(air.uniform() && air.code(0) == AirflowCells.AIR, "air and a torch are uniform air");
            byte stoneCode = AirflowCells.code(Blocks.STONE.defaultBlockState());
            helper.assertTrue(stone.uniform() && stone.code(0) == stoneCode, "a stone mass is uniform stone");
            CellGrid3D grid = new CellGrid3D(16, 16, 16);
            stone.writeTo(grid, 0, 0, 0, AirflowCells.table());
            helper.assertTrue(grid.type(15, 15, 15) == CellType.SOLID
                    && grid.material(15, 15, 15).equals(AirflowCells.table().cell(stoneCode).material()),
                    "a section writes its cells into a grid");
            sections.release(top);
            sections.release(below);
        } finally {
            level.setBlock(torch, Blocks.AIR.defaultBlockState(), flags);
            for (int i = 0; i < AirflowSections.SECTION_CELLS; i++) {
                massSection.setBlockState(i & 15, i >> 8, (i >> 4) & 15, Blocks.AIR.defaultBlockState(), false);
            }
            if (opened) {
                AirflowSections.close(level);
            }
        }
        helper.succeed();
    }

    /** Records what a section cache tells its listeners. */
    private static final class Recorder implements AirflowSections.Listener {
        final List<Long> ready = new ArrayList<>(), dropped = new ArrayList<>();
        final List<String> changes = new ArrayList<>();

        @Override
        public void sectionReady(long section) {
            ready.add(section);
        }

        @Override
        public void cellChanged(BlockPos pos, byte before, byte after) {
            changes.add(pos + " " + before + ">" + after);
        }

        @Override
        public void sectionDropped(long section) {
            dropped.add(section);
        }
    }

    /**
     * A region manager of its own (the engine is LEGACY here) with one fixed anchor: its tile's chunks are forced,
     * its sections read, the region built on the air thread and stepped; a block placed in its air reaches the next
     * frame; stopping joins the thread and releases the sections. The game test server ticks far faster than real
     * time while the air thread works in real time, so the timeout is in the tens of thousands of ticks (seconds here).
     */
    @GameTest(template = GameTestStructures.EMPTY, batch = "airflow_regions", timeoutTicks = 20_000)
    public static void regionFormsAroundAnAnchorAndHearsAnEdit(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos anchor = helper.absolutePos(new BlockPos(1, 2, 1));
        AirflowRegions regions = new AirflowRegions(level);
        regions.alignForTests(helper.absolutePos(BlockPos.ZERO));
        regions.neighbourTiles(AirflowNeighbourTiles.Mode.OFF, 0); // counts regions: no secondaries from other tests' builds
        int[] o = regions.tileOf(anchor.getX() + 0.5, anchor.getY() + 0.5, anchor.getZ() + 0.5);
        BlockPos edit = anchor.offset(3, 0, 3);
        helper.assertTrue(level.getBlockState(edit).isAir(), "the edit's cell should start as air");
        List<long[]> forced = new ArrayList<>();
        for (int cx = o[0] >> 4; cx < (o[0] + AirflowRegions.WX) >> 4; cx++) {
            for (int cz = o[2] >> 4; cz < (o[2] + AirflowRegions.WZ) >> 4; cz++) {
                if (level.setChunkForced(cx, cz, true)) {
                    forced.add(new long[] {cx, cz});
                }
            }
        }
        boolean opened = AirflowSections.get(level) == null;
        regions.anchors(l -> List.of(Vec3.atCenterOf(anchor)));
        regions.start();
        boolean[] stopped = {false};
        Runnable cleanup = () -> {
            stopped[0] = true;
            regions.stop();
            level.setBlock(edit, Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS);
            for (long[] c : forced) {
                level.setChunkForced((int) c[0], (int) c[1], false);
            }
            if (opened) {
                AirflowSections.close(level);
            }
        };
        helper.onEachTick(() -> {
            if (!stopped[0]) {
                regions.tick();
            }
        });
        helper.startSequence()
                .thenWaitUntil(() -> {
                    AirflowRegions.Region r = regions.regionAt(anchor.getX(), anchor.getY(), anchor.getZ());
                    helper.assertTrue(r != null && r.slot() != null && r.slot().steps() >= 2,
                            "waiting for the region to be built and stepped");
                })
                .thenExecute(() -> {
                    AirflowRegions.Region r = regions.regions().get(0);
                    helper.assertTrue(regions.regions().size() == 1, "one anchor, one region: " + regions.regions().size());
                    helper.assertTrue(r.x0() == o[0] && r.y0() == o[1] && r.z0() == o[2],
                            "the region is the anchor's tile: " + regionLine(r, level));
                    helper.assertTrue(r.slot().status() == AirflowRuntime.Status.RUNNING, "running: " + r.slot().failure());
                    helper.assertTrue(r.slot().frame().frame().isAir(edit.getX() - r.x0() + 0.5,
                            edit.getY() - r.y0() + 0.5, edit.getZ() - r.z0() + 0.5), "the cell is air in the frame");
                    level.setBlock(edit, Blocks.STONE.defaultBlockState(), Block.UPDATE_CLIENTS);
                })
                .thenWaitUntil(() -> {
                    AirflowRegions.Region r = regions.regions().get(0);
                    helper.assertFalse(r.slot().frame().frame().isAir(edit.getX() - r.x0() + 0.5,
                            edit.getY() - r.y0() + 0.5, edit.getZ() - r.z0() + 0.5), "waiting for the edit's frame");
                })
                .thenExecute(() -> {
                    AirflowRuntime.Frame f = regions.frameAt(anchor.getX() + 0.5, anchor.getY() + 0.5, anchor.getZ() + 0.5);
                    double t = f.frame().temperature(anchor.getX() - regions.regions().get(0).x0() + 0.5,
                            anchor.getY() - regions.regions().get(0).y0() + 0.5,
                            anchor.getZ() - regions.regions().get(0).z0() + 0.5);
                    helper.assertTrue(Double.isFinite(t), "a finite temperature at the anchor: " + t);
                    AirflowRuntime runtime = regions.runtime();
                    cleanup.run();
                    helper.assertFalse(runtime.alive(), "stopping joins the air thread");
                    helper.assertTrue(regions.regions().isEmpty(), "stopping retires the regions");
                })
                .thenSucceed();
    }

    /**
     * b4: the server's region manager (installed for the test) runs the tile holding a player's spot (d1); a door
     * placed shut, then opened, and a lit campfire reach the region; {@code status}, {@code sample} and
     * {@code record} answer and the recording's summary is written; stopping leaves no air thread.
     */
    @GameTest(template = GameTestStructures.EMPTY, batch = "airflow_player", timeoutTicks = 40_000)
    public static void regionHearsDoorsSourcesAndCommands(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos stand = helper.absolutePos(new BlockPos(1, 2, 1));
        AirflowRegions regions = new AirflowRegions(level);
        regions.alignForTests(helper.absolutePos(BlockPos.ZERO));
        int[] o = regions.tileOf(stand.getX() + 0.5, stand.getY(), stand.getZ() + 0.5);
        BlockPos lower = stand.offset(4, 0, 2), upper = lower.above(), fire = stand.offset(2, 0, 4);
        for (BlockPos p : List.of(lower, upper, fire)) {
            helper.assertTrue(level.getBlockState(p).isAir(), "the test's cells should start as air: " + p);
        }
        List<long[]> forced = new ArrayList<>();
        for (int cx = o[0] >> 4; cx < (o[0] + AirflowRegions.WX) >> 4; cx++) {
            for (int cz = o[2] >> 4; cz < (o[2] + AirflowRegions.WZ) >> 4; cz++) {
                if (level.setChunkForced(cx, cz, true)) {
                    forced.add(new long[] {cx, cz});
                }
            }
        }
        // A fixed anchor, not a mock player: Meteodyne's payloads now skip a mock's embedded connection
        // (MeteodyneNetwork), but Simulated's end_sea doesn't, which fails the test in the aggregate and core-less runs.
        Vec3 player = Vec3.atBottomCenterOf(stand);
        boolean opened = AirflowSections.get(level) == null;
        regions.anchors(l -> List.of(player));
        regions.start();
        AirflowRegions.useForTests(regions);
        var commands = level.getServer().getCommands().getDispatcher();
        var op = level.getServer().createCommandSourceStack().withLevel(level)
                .withPosition(Vec3.atBottomCenterOf(stand));
        int flags = Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE;
        BlockState shut = Blocks.OAK_DOOR.defaultBlockState();
        double watts = AirflowSources.watts(Blocks.CAMPFIRE.defaultBlockState());
        AirflowRuntime.Counters[] before = {AirflowRuntime.Counters.NONE};
        boolean[] done = {false};
        Runnable cleanup = () -> {
            if (done[0]) {
                return;
            }
            done[0] = true;
            AirflowRegions.useForTests(null);
            regions.stop();
            for (BlockPos p : List.of(upper, lower, fire)) {
                level.setBlock(p, Blocks.AIR.defaultBlockState(), flags);
            }
           for (long[] c : forced) {
                level.setChunkForced((int) c[0], (int) c[1], false);
            }
            if (opened) {
                AirflowSections.close(level);
            }
        };
        helper.startSequence()
                .thenWaitUntil(() -> {
                    AirflowRegions.Region r = regions.regionAt(player.x, player.y, player.z);
                    helper.assertTrue(r != null && r.slot() != null && r.slot().steps() >= 2,
                            "waiting for the player's region to be built and stepped");
                })
                .thenExecute(() -> {
                    AirflowRegions.Region r = regions.regionAt(player.x, player.y, player.z);
                    helper.assertTrue(r.x0() == o[0] && r.y0() == o[1] && r.z0() == o[2],
                            "the region is the player's tile: " + regionLine(r, level));
                    helper.assertTrue(command(commands, "meteodyne airflow record start", op) == 1, "record starts");
                    before[0] = r.slot().counters(); // other tests may have left blocks or sources in the window
                    level.setBlock(lower, shut, flags);
                    level.setBlock(upper, shut.setValue(DoorBlock.HALF, DoubleBlockHalf.UPPER), flags);
                    level.setBlock(fire, Blocks.CAMPFIRE.defaultBlockState(), flags);
                })
                .thenWaitUntil(() -> {
                    AirflowRuntime.Counters c = regions.regionAt(player.x, player.y, player.z)
                            .slot().counters();
                    helper.assertTrue(c.edits() >= before[0].edits() + 2
                                    && Math.abs(c.watts() - before[0].watts() - watts) < 1e-6,
                            "waiting for the door and the campfire (" + watts + " W more than " + before[0] + "): " + c);
                })
                .thenExecute(() -> {
                    level.setBlock(lower, shut.setValue(DoorBlock.OPEN, true), flags);
                    level.setBlock(upper, shut.setValue(DoorBlock.HALF, DoubleBlockHalf.UPPER).setValue(DoorBlock.OPEN, true),
                            flags);
                })
                .thenWaitUntil(() -> {
                    AirflowRuntime.Counters c = regions.regionAt(player.x, player.y, player.z)
                            .slot().counters();
                    helper.assertTrue(c.toggles() >= before[0].toggles() + 2, "waiting for both door halves to open: " + c);
                })
                .thenExecute(() -> {
                    AirflowRegions.Region r = regions.regionAt(player.x, player.y, player.z);
                    AirflowRuntime.Slot slot = r.slot();
                    helper.assertTrue(slot.counters().desyncs() == 0, "no desyncs: " + slot.counters());
                    helper.assertTrue(slot.failures() == 0 && slot.status() == AirflowRuntime.Status.RUNNING,
                            "no failures: " + slot.failure());
                    helper.assertTrue(command(commands, "meteodyne airflow status", op) == 1, "status answers");
                    helper.assertTrue(command(commands, "meteodyne airflow sample", op) == 1, "sample answers at the player");
                    helper.assertTrue(command(commands, "meteodyne airflow record mark door open", op) == 1, "mark");
                    viewSendsTheTile(helper, regions, r);
                })
                .thenIdle(40)
                .thenExecute(() -> {
                    AirflowRecorder rec = regions.recorder();
                    helper.assertTrue(rec != null && rec.seconds() >= 1, "a second recorded");
                    Path summary = rec.dir().resolve("summary.md");
                    helper.assertTrue(command(commands, "meteodyne airflow record stop", op) == 1, "record stops");
                    try {
                        String text = Files.readString(summary);
                        helper.assertTrue(text.contains("Server thread, p99 per tick") && text.contains("door open"),
                                "the summary has the measures and the mark");
                        helper.assertTrue(text.contains("Profile (M7f)") && text.contains("Profile by segment"),
                                "the summary has the profile and its segments");
                        helper.assertTrue(Files.readAllLines(rec.dir().resolve("ticks.csv")).size() > 20,
                                "ticks were recorded");
                    } catch (IOException e) {
                        helper.fail("summary: " + e);
                    }
                    AirflowRuntime runtime = regions.runtime();
                    cleanup.run();
                    helper.assertFalse(runtime.alive(), "stopping joins the air thread");
                    helper.assertFalse(Thread.getAllStackTraces().keySet().stream()
                            .anyMatch(t -> t.isAlive() && t.getName().equals(runtime.threadName())),
                            "no air thread is left");
                })
                .thenSucceed();
    }

    /** Runs a command; -1 if it does not parse. */
    private static int command(com.mojang.brigadier.CommandDispatcher<net.minecraft.commands.CommandSourceStack> commands, String text,
            net.minecraft.commands.CommandSourceStack source) {
        try {
            return commands.execute(text, source);
        } catch (CommandSyntaxException e) {
            return -1;
        }
    }

    private static String regionLine(AirflowRegions.Region r, ServerLevel level) {
        return AirflowCommand.regionLine(r, level.getGameTime());
    }

    /**
     * The debug view (2026-11-15): an update holds the running tile's outline and, near the lit campfire, cells off
     * the background; a part survives its codec.
     */
    private static void viewSendsTheTile(GameTestHelper helper, AirflowRegions regions, AirflowRegions.Region r) {
        List<AirflowView.Payload> parts = AirflowView.build(regions);
        List<AirflowView.Payload> mine = parts.stream().filter(p -> p.id() == r.id()).toList();
        helper.assertTrue(!mine.isEmpty(), "the view sends the player's tile");
        AirflowView.Payload first = mine.get(0);
        helper.assertTrue(first.kind() == AirflowView.Payload.TILE && first.state() == AirflowView.RUNNING
                        && first.ox() == r.x0() && first.oy() == r.y0() && first.oz() == r.z0()
                        && first.sx() == AirflowRegions.WX && first.sy() == AirflowRegions.WY,
                "the tile's outline: " + first.state() + " at " + first.ox() + ", " + first.oy() + ", " + first.oz());
        helper.assertTrue(mine.stream().allMatch(p -> p.count() <= AirflowView.PART_CELLS), "parts fit one message");
        io.netty.buffer.ByteBuf buf = io.netty.buffer.Unpooled.buffer();
        AirflowView.Payload.STREAM_CODEC.encode(buf, first);
        AirflowView.Payload back = AirflowView.Payload.STREAM_CODEC.decode(buf);
        helper.assertTrue(back.count() == first.count() && back.id() == first.id()
                        && java.util.Arrays.equals(back.cells(), java.util.Arrays.copyOf(first.cells(), first.count()
                        * AirflowView.CELL_BYTES)), "a part survives its codec");
        buf.release();
    }

    /**
     * b4: the switch. A campfire's legacy region warms a point next to it; with the engine set to AIRFLOW (its own
     * batch, so no other test runs meanwhile) the legacy regions stand down and {@code roomAt} reads 0; set back to
     * LEGACY, they warm it again.
     */
    @GameTest(template = GameTestStructures.EMPTY, batch = "airflow_switch", timeoutTicks = 40_000)
    public static void airflowModeStandsTheLegacyRegionsDown(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        var world = dev.brights0ng.meteodyne.heat.world.HeatWorld.of(level);
        BlockPos low = helper.absolutePos(BlockPos.ZERO), high = helper.absolutePos(new BlockPos(6, 6, 6));
        BlockPos fire = new BlockPos(3, 1, 3);
        Vec3 body = Vec3.atCenterOf(helper.absolutePos(fire)).add(1, 1, 0);
        world.setTestArea(new int[] {low.getX(), low.getY(), low.getZ(), high.getX(), high.getY(), high.getZ()});
        world.forgetRegions();
        helper.setBlock(fire, Blocks.CAMPFIRE);
        Runnable cleanup = () -> {
            Airflow.useForTests(Airflow.Engine.LEGACY);
            helper.setBlock(fire, Blocks.AIR);
            world.forgetRegions();
            world.setTestArea(null);
        };
        helper.startSequence()
                .thenWaitUntil(() -> helper.assertTrue(world.roomAt(body) > 0, "waiting for the legacy region to warm"))
                .thenExecute(() -> Airflow.useForTests(Airflow.Engine.AIRFLOW))
                .thenWaitUntil(() -> helper.assertTrue(world.regions().isEmpty() && world.roomAt(body) == 0,
                        "waiting for the legacy regions to stand down: " + world.roomAt(body)))
                .thenExecute(() -> Airflow.useForTests(Airflow.Engine.LEGACY))
                .thenWaitUntil(() -> helper.assertTrue(world.roomAt(body) > 0, "waiting for legacy to warm again"))
                .thenExecute(cleanup)
                .thenSucceed();
    }

    /**
     * M8a (Bright, 2026-11-16: game-test servers keep running LEGACY, and a test covers the start they skip): what a
     * server with {@code [heat] engine = AIRFLOW} does at start. The engine rule picks AIRFLOW off a game-test server;
     * the manager is made by the real {@code ServerStartedEvent} handler, on the overworld, and ticked by the real
     * level-tick handler (nothing here ticks it); the real {@code ServerStoppingEvent} handler stops it and joins its
     * air thread. Its own batch, as the switch's. The anchor is a fixed spot set after the start, not a mock player
     * (see {@link #regionHearsDoorsSourcesAndCommands}).
     */
    @GameTest(template = GameTestStructures.EMPTY, batch = "airflow_server_start", timeoutTicks = 40_000)
    public static void airflowStartsWithTheServer(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        var server = level.getServer();
        helper.assertTrue(Airflow.choose(false, Airflow.Engine.AIRFLOW) == Airflow.Engine.AIRFLOW
                && Airflow.choose(false, Airflow.Engine.LEGACY) == Airflow.Engine.LEGACY,
                "off a game-test server the configured engine runs");
        helper.assertTrue(Airflow.choose(true, Airflow.Engine.AIRFLOW) == Airflow.Engine.LEGACY,
                "a game-test server runs LEGACY whatever the config says");
        helper.assertTrue(!Airflow.active() && AirflowRegions.get() == null,
                "this game-test server started without the airflow manager");
        helper.assertTrue(level == server.overworld(), "game tests run in the overworld");
        Vec3 anchor = Vec3.atBottomCenterOf(helper.absolutePos(new BlockPos(1, 2, 1)));
        boolean opened = AirflowSections.get(level) == null;
        Airflow.useForTests(Airflow.Engine.AIRFLOW);
        AirflowRegions.onServerStarted(new net.neoforged.neoforge.event.server.ServerStartedEvent(server));
        AirflowRegions regions = AirflowRegions.get();
        if (regions == null) {
            Airflow.useForTests(Airflow.Engine.LEGACY);
            helper.fail("the ServerStartedEvent handler made no manager under AIRFLOW");
            return;
        }
        regions.anchors(l -> List.of(anchor));
        int[] o = regions.tileOf(anchor.x, anchor.y, anchor.z);
        List<long[]> forced = new ArrayList<>();
        for (int cx = o[0] >> 4; cx < (o[0] + AirflowRegions.WX) >> 4; cx++) {
            for (int cz = o[2] >> 4; cz < (o[2] + AirflowRegions.WZ) >> 4; cz++) {
                if (level.setChunkForced(cx, cz, true)) {
                    forced.add(new long[] {cx, cz});
                }
            }
        }
        AirflowRuntime runtime = regions.runtime();
        boolean[] done = {false};
        Runnable cleanup = () -> {
            if (done[0]) {
                return;
            }
            done[0] = true;
            AirflowRegions.onServerStopping(new net.neoforged.neoforge.event.server.ServerStoppingEvent(server));
            Airflow.useForTests(Airflow.Engine.LEGACY);
            for (long[] c : forced) {
                level.setChunkForced((int) c[0], (int) c[1], false);
            }
            if (opened) {
                AirflowSections.close(level);
            }
        };
        helper.startSequence()
                .thenWaitUntil(() -> {
                    AirflowRegions.Region r = regions.regionAt(anchor.x, anchor.y, anchor.z);
                    helper.assertTrue(r != null && r.slot() != null && r.slot().steps() >= 2,
                            "waiting for the anchor's tile to be built and stepped under the level tick");
                })
                .thenExecute(() -> {
                    AirflowRegions.Region r = regions.regionAt(anchor.x, anchor.y, anchor.z);
                    helper.assertTrue(regions.level() == server.overworld(), "the manager runs the overworld");
                    helper.assertTrue(r.x0() == o[0] && r.y0() == o[1] && r.z0() == o[2],
                            "the region is the anchor's tile: " + regionLine(r, level));
                    helper.assertTrue(r.slot().status() == AirflowRuntime.Status.RUNNING && r.slot().failures() == 0,
                            "running: " + r.slot().failure());
                    cleanup.run();
                    helper.assertTrue(AirflowRegions.get() == null, "the stopping handler clears the manager");
                    helper.assertFalse(runtime.alive(), "stopping joins the air thread");
                })
                .thenSucceed();
    }

    // ---- The container check (opt-in) ----

    /**
     * After the smoke (Bright, 2026-11-14, option a), with tiles (d1): only the tile holding a player stays alive. An
     * anchor moves one tile east: that tile's region opens, the first counts as empty at once and retires after the
     * grace period.
     */
    @GameTest(template = GameTestStructures.EMPTY, batch = "airflow_assign", timeoutTicks = 40_000)
    public static void onlyTheAssignedRegionStaysAlive(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Vec3 a = Vec3.atBottomCenterOf(helper.absolutePos(new BlockPos(1, 2, 1)));
        AirflowRegions regions = new AirflowRegions(level);
        regions.alignForTests(helper.absolutePos(BlockPos.ZERO));
        regions.neighbourTiles(AirflowNeighbourTiles.Mode.OFF, 0); // counts regions
        int[] oa = regions.tileOf(a.x, a.y, a.z);
        Vec3 b = a.add(AirflowRegions.WX, 0, 0);
        int[] ob = regions.tileOf(b.x, b.y, b.z);
        List<long[]> forced = new ArrayList<>();
        for (int cx = Math.min(oa[0], ob[0]) >> 4; cx < (Math.max(oa[0], ob[0]) + AirflowRegions.WX) >> 4; cx++) {
            for (int cz = Math.min(oa[2], ob[2]) >> 4; cz < (Math.max(oa[2], ob[2]) + AirflowRegions.WZ) >> 4; cz++) {
                if (level.setChunkForced(cx, cz, true)) {
                    forced.add(new long[] {cx, cz});
                }
            }
        }
        boolean opened = AirflowSections.get(level) == null;
        Vec3[] at = {a};
        regions.anchors(l -> List.of(at[0]));
        regions.start();
        boolean[] stopped = {false};
        helper.onEachTick(() -> {
            if (!stopped[0]) {
                regions.tick();
            }
        });
        AirflowRegions.Region[] first = {null};
        helper.startSequence()
                .thenWaitUntil(() -> {
                    AirflowRegions.Region r = regions.regionAt(a.x, a.y, a.z);
                    helper.assertTrue(r != null && r.slot() != null && r.occupied(), "waiting for the first region");
                })
                .thenExecute(() -> {
                    first[0] = regions.regionAt(a.x, a.y, a.z);
                    helper.assertTrue(first[0].x0() == oa[0] && ob[0] == oa[0] + AirflowRegions.WX && ob[1] == oa[1]
                            && ob[2] == oa[2], "the new spot is in the next tile east");
                    at[0] = b;
                })
                .thenWaitUntil(() -> {
                    helper.assertTrue(regions.regions().size() == 2 && !first[0].occupied(),
                            "waiting for a region around the new spot, with the first one unassigned");
                })
                .thenWaitUntil(() -> helper.assertTrue(!regions.regions().contains(first[0]),
                        "waiting for the first region's grace period to end"))
                .thenExecute(() -> {
                    StringBuilder seen = new StringBuilder();
                    for (AirflowRegions.Region r : regions.regions()) {
                        seen.append("; ").append(regionLine(r, level)).append(r.contains(b.x, b.y, b.z) ? " (holds it)" : "");
                    }
                    helper.assertTrue(regions.regions().size() == 1 && regions.regions().get(0).occupied()
                            && regions.regions().get(0).contains(b.x, b.y, b.z),
                            "one region, around the anchor at " + b + seen);
                    stopped[0] = true;
                    regions.stop();
                    for (long[] c : forced) {
                        level.setChunkForced((int) c[0], (int) c[1], false);
                    }
                    if (opened) {
                        AirflowSections.close(level);
                    }
                })
                .thenSucceed();
    }

    /**
     * M8b b4 (B3 with G2; Bright, 2026-11-17): a stone room cut by a tile's x border, high in the world above the
     * test's own spot (its own batch, so nothing else builds there), with a campfire on the anchor's side. The
     * anchor stands in the room's enclosed air, whose trace crosses the cut through the room's 4 × 5 section and
     * nothing else, so the tile across opens as the only secondary; a trace from the open air above the roof walks
     * nothing. Heat crosses: out of the fire's tile, into the other. With the anchor gone, both go into their grace
     * together and retire on the same tick: the count of regions never reads 1.
     */
    @GameTest(template = GameTestStructures.EMPTY, batch = "airflow_neighbours", timeoutTicks = 40_000)
    public static void aRoomAcrossABorderRunsTheTileAcross(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos spot = helper.absolutePos(BlockPos.ZERO);
        int sx = SectionPos.blockToSectionCoord(spot.getX()), sz = SectionPos.blockToSectionCoord(spot.getZ());
        int top = level.getMaxBuildHeight();
        int border = 16 * sx, y = top - 32, z = 16 * sz + 4;
        AirflowRegions regions = new AirflowRegions(level);
        // the section east of the border is the second of its tile: the border runs at x = 16 sx; the top band
        regions.alignForTests(new BlockPos(border + 16, y, z));
        regions.neighbourTiles(AirflowNeighbourTiles.Mode.ON, 4);
        regions.mergeTiles(AirflowMerge.Mode.OFF, Double.NaN); // B3 alone: two regions (M8b5b merges them)
        int[] west = regions.tileOf(border - 1, y, z), east = regions.tileOf(border, y, z);
        helper.assertTrue(east[0] == border && west[0] == border - AirflowRegions.WX && west[1] == y && east[1] == y,
                "the border runs through the room: " + west[0] + ", " + east[0] + " at y " + west[1]);
        // the room: outer x border-6..border+5, y..y+5, z..z+6; inside 10 × 4 × 5, the fire on the floor in the west
        BlockPos low = new BlockPos(border - 6, y, z), fire = new BlockPos(border - 3, y + 1, z + 3);
        Map<BlockPos, BlockState> prior = new HashMap<>();
        for (BlockPos p : BlockPos.betweenClosed(low, low.offset(11, 5, 6))) {
            prior.put(p.immutable(), level.getBlockState(p));
        }
        for (BlockPos p : prior.keySet()) {
            boolean shell = p.getX() == low.getX() || p.getX() == low.getX() + 11 || p.getY() == y || p.getY() == y + 5
                    || p.getZ() == z || p.getZ() == z + 6;
            level.setBlock(p, (shell ? Blocks.STONE : Blocks.AIR).defaultBlockState(), Block.UPDATE_CLIENTS);
        }
        level.setBlock(fire, Blocks.CAMPFIRE.defaultBlockState(), Block.UPDATE_CLIENTS);
        Vec3[] anchor = {new Vec3(border - 4.5, y + 1, z + 2.5)};
        List<long[]> forced = new ArrayList<>();
        for (int cx = west[0] >> 4; cx < (east[0] + AirflowRegions.WX) >> 4; cx++) {
            for (int cz = west[2] >> 4; cz < (west[2] + AirflowRegions.WZ) >> 4; cz++) {
                if (level.setChunkForced(cx, cz, true)) {
                    forced.add(new long[] {cx, cz});
                }
            }
        }
        boolean opened = AirflowSections.get(level) == null;
        regions.anchors(l -> anchor[0] == null ? List.of() : List.of(anchor[0]));
        regions.start();
        boolean[] stopped = {false}, sawOne = {false};
        helper.onEachTick(() -> {
            if (!stopped[0]) {
                regions.tick();
                if (anchor[0] == null && regions.regions().size() == 1) {
                    sawOne[0] = true;
                }
            }
        });
        Runnable cleanup = () -> {
            stopped[0] = true;
            regions.stop();
            for (Map.Entry<BlockPos, BlockState> e : prior.entrySet()) {
                level.setBlock(e.getKey(), e.getValue(), Block.UPDATE_CLIENTS);
            }
            for (long[] c : forced) {
                level.setChunkForced((int) c[0], (int) c[1], false);
            }
            if (opened) {
                AirflowSections.close(level);
            }
        };
        Vec3 a = anchor[0];
        helper.startSequence()
                .thenWaitUntil(() -> {
                    AirflowRegions.Region home = regions.regionAt(a.x, a.y, a.z);
                    AirflowRegions.Region across = regions.regionAt(border + 1, y + 1, z + 1);
                    helper.assertTrue(home != null && home.slot() != null && home.slot().steps() >= 2
                                    && across != null && across.slot() != null && across.slot().steps() >= 2,
                            "waiting for both tiles to be built and stepped: " + AirflowCommand.neighbourLine(regions));
                })
                .thenExecute(() -> {
                    AirflowRegions.Region home = regions.regionAt(a.x, a.y, a.z);
                    AirflowRegions.Region across = regions.regionAt(border + 1, y + 1, z + 1);
                    helper.assertTrue(regions.regions().size() == 2, "the player's tile and one secondary: "
                            + regions.regions().size() + "; " + AirflowCommand.neighbourLine(regions));
                    helper.assertTrue(home.occupied() && !home.secondary() && across.secondary() && !across.occupied(),
                            "roles: " + regionLine(home, level) + "; " + regionLine(across, level));
                    helper.assertTrue(regions.secondariesNow() == 1 && regions.secondariesOpened() == 1,
                            AirflowCommand.neighbourLine(regions));
                    for (int i = 0; i < AirflowNeighbourTiles.REPORTED.length; i++) {
                        helper.assertTrue(regions.wouldNow(i) == 1, "one tile would run at "
                                + AirflowNeighbourTiles.REPORTED[i] + " cells: " + AirflowCommand.neighbourLine(regions));
                    }
                    AirflowTrace inside = regions.traceFrom(a.x, a.y, a.z);
                    int cut = inside.crossings(AirflowNeighbourTiles.faceKey(0, 0, 0,
                            dev.brights0ng.greedyfluids.grid.CellGrid3D.Side.EAST));
                    int below = inside.crossings(AirflowNeighbourTiles.faceKey(0, 0, 0,
                            dev.brights0ng.greedyfluids.grid.CellGrid3D.Side.DOWN));
                    helper.assertTrue(inside.enclosed() && inside.complete() && inside.walked() == 10 * 4 * 5,
                            "the trace walks the room's inside: " + inside.walked() + ", complete " + inside.complete());
                    helper.assertTrue(cut == 20 && below == 0, "it crosses the cut through the room's 4 × 5 section "
                            + "and nowhere else: " + cut + " east, " + below + " down");
                    AirflowTrace outside = regions.traceFrom(a.x, y + 7, a.z);
                    helper.assertTrue(!outside.enclosed() && outside.walked() == 0,
                            "the open air above the roof walks nothing: " + outside.walked());
                })
                .thenWaitUntil(() -> {
                    AirflowRuntime.Counters h = regions.regionAt(a.x, a.y, a.z).slot().counters();
                    AirflowRuntime.Counters c = regions.regionAt(border + 1, y + 1, z + 1).slot().counters();
                    helper.assertTrue(h.exchangedJ() > 500 && c.exchangedJ() < -500,
                            "waiting for heat to cross: out " + h.exchangedJ() + " J, in " + -c.exchangedJ() + " J");
                })
                .thenExecute(() -> anchor[0] = null)
                .thenWaitUntil(() -> helper.assertTrue(regions.regions().isEmpty(),
                        "waiting for both tiles' grace to end: " + regions.regions().size()))
                .thenExecute(() -> {
                    helper.assertFalse(sawOne[0], "the secondary retired with the player's tile, not after it");
                    cleanup.run();
                })
                .thenSucceed();
    }

    /**
     * M8b5b (M1; Bright, 2026-11-18): the same cut room with merging on (LINKED, settle 1 s here). Once both tiles run
     * and the box has been chosen for the settle time, they merge into one region of 2 tiles, built from their save,
     * and the room's far half warms through air that now crosses the old border. With the player moved out onto the
     * roof (open air: no enclosed air, no links), the merged region splits after the settle time into the tile the
     * player still keeps; the far tile is dropped. With the player gone, it retires.
     */
    @GameTest(template = GameTestStructures.EMPTY, batch = "airflow_merge", timeoutTicks = 40_000)
    public static void aCutRoomMergesIntoOneRegionAndSplitsWhenThePlayerLeaves(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos spot = helper.absolutePos(BlockPos.ZERO);
        int sx = SectionPos.blockToSectionCoord(spot.getX()), sz = SectionPos.blockToSectionCoord(spot.getZ());
        int top = level.getMaxBuildHeight();
        int border = 16 * sx, y = top - 32, z = 16 * sz + 4;
        AirflowRegions regions = new AirflowRegions(level);
        regions.alignForTests(new BlockPos(border + 16, y, z));
        regions.neighbourTiles(AirflowNeighbourTiles.Mode.ON, 4);
        regions.mergeTiles(AirflowMerge.Mode.LINKED, 1.0);
        int[] west = regions.tileOf(border - 1, y, z), east = regions.tileOf(border, y, z);
        helper.assertTrue(east[0] == border && west[0] == border - AirflowRegions.WX && west[1] == y && east[1] == y,
                "the border runs through the room: " + west[0] + ", " + east[0] + " at y " + west[1]);
        BlockPos low = new BlockPos(border - 6, y, z), fire = new BlockPos(border - 3, y + 1, z + 3);
        Map<BlockPos, BlockState> prior = new HashMap<>();
        for (BlockPos p : BlockPos.betweenClosed(low, low.offset(11, 5, 6))) {
            prior.put(p.immutable(), level.getBlockState(p));
        }
        for (BlockPos p : prior.keySet()) {
            boolean shell = p.getX() == low.getX() || p.getX() == low.getX() + 11 || p.getY() == y || p.getY() == y + 5
                    || p.getZ() == z || p.getZ() == z + 6;
            level.setBlock(p, (shell ? Blocks.STONE : Blocks.AIR).defaultBlockState(), Block.UPDATE_CLIENTS);
        }
        level.setBlock(fire, Blocks.CAMPFIRE.defaultBlockState(), Block.UPDATE_CLIENTS);
        Vec3 inside = new Vec3(border - 4.5, y + 1, z + 2.5), roof = new Vec3(border - 4.5, y + 6, z + 2.5);
        Vec3[] anchor = {inside};
        List<long[]> forced = new ArrayList<>();
        for (int cx = west[0] >> 4; cx < (east[0] + AirflowRegions.WX) >> 4; cx++) {
            for (int cz = west[2] >> 4; cz < (west[2] + AirflowRegions.WZ) >> 4; cz++) {
                if (level.setChunkForced(cx, cz, true)) {
                    forced.add(new long[] {cx, cz});
                }
            }
        }
        boolean opened = AirflowSections.get(level) == null;
        regions.anchors(l -> anchor[0] == null ? List.of() : List.of(anchor[0]));
        regions.start();
        boolean[] stopped = {false};
        helper.onEachTick(() -> {
            if (!stopped[0]) {
                regions.tick();
            }
        });
        Runnable cleanup = () -> {
            stopped[0] = true;
            regions.stop();
            for (Map.Entry<BlockPos, BlockState> e : prior.entrySet()) {
                level.setBlock(e.getKey(), e.getValue(), Block.UPDATE_CLIENTS);
            }
            for (long[] c : forced) {
                level.setChunkForced((int) c[0], (int) c[1], false);
            }
            if (opened) {
                AirflowSections.close(level);
            }
        };
        long[] at = {level.getGameTime(), 0, 0, 0};
        double[] seen = new double[2];
        helper.startSequence()
                .thenWaitUntil(() -> {
                    AirflowRegions.Region home = regions.regionAt(inside.x, inside.y, inside.z);
                    helper.assertTrue(home != null && home.tiles() == 2 && home.slot() != null
                                    && home.slot().steps() >= 2 && home == regions.regionAt(border + 1, y + 1, z + 1),
                            "waiting for the two tiles to merge and step: " + AirflowCommand.mergeLine(regions)
                                    + "; " + AirflowCommand.neighbourLine(regions));
                })
                .thenExecute(() -> {
                    at[1] = level.getGameTime();
                    AirflowRegions.Region home = regions.regionAt(inside.x, inside.y, inside.z);
                    helper.assertTrue(regions.regions().size() == 1 && regions.merges() == 1 && regions.splits() == 0
                                    && regions.transitionsNow() == 0 && regions.transitionsAbandoned() == 0,
                            "one merged region: " + AirflowCommand.mergeLine(regions));
                    helper.assertTrue(home.x0() == west[0] && home.nx() == 2 * AirflowRegions.WX
                                    && home.ny() == AirflowRegions.WY && home.nz() == AirflowRegions.WZ,
                            "the box is the two tiles: " + regionLine(home, level));
                    helper.assertTrue(home.occupied() && !home.secondary(), "it holds the player: "
                            + regionLine(home, level));
                })
                .thenWaitUntil(() -> {
                    AirflowRegions.Region home = regions.regionAt(inside.x, inside.y, inside.z);
                    double far = farHalfOffset(home, border, y, z), across = acrossTheBorder(home, border, y, z);
                    helper.assertTrue(far > 0.2 && across > 0.001, "waiting for air to cross the old border "
                            + "(two tiles apart, none can) and the room's far half to warm: " + across + " m/s across, "
                            + far + " C over its background");
                    seen[0] = far;
                    seen[1] = across;
                })
                .thenExecute(() -> {
                    at[2] = level.getGameTime();
                    anchor[0] = roof;
                })
                .thenWaitUntil(() -> {
                    AirflowRegions.Region home = regions.regionAt(roof.x, roof.y, roof.z);
                    helper.assertTrue(home != null && home.tiles() == 1 && home.slot() != null
                                    && home.slot().status() == AirflowRuntime.Status.RUNNING,
                            "waiting for the split: " + AirflowCommand.mergeLine(regions));
                })
                .thenExecute(() -> {
                    at[3] = level.getGameTime();
                    Meteodyne.LOGGER.info("Airflow merge test: merged {} ticks after the start; far half {} C over its "
                                    + "background with {} m/s across the old border {} ticks later; split {} ticks after "
                                    + "the player left the room", at[1] - at[0], String.format(java.util.Locale.ROOT,
                                    "%.2f", seen[0]), String.format(java.util.Locale.ROOT, "%.3f", seen[1]),
                            at[2] - at[1], at[3] - at[2]);
                    helper.assertTrue(regions.splits() == 1 && regions.regions().size() == 1
                                    && regions.regionAt(border + 1, y + 1, z + 1) == null,
                            "split into the player's tile alone; the far tile dropped: "
                                    + AirflowCommand.mergeLine(regions) + ", " + regions.regions().size() + " regions");
                    anchor[0] = null;
                })
                .thenWaitUntil(() -> helper.assertTrue(regions.regions().isEmpty(),
                        "waiting for the tile's grace to end: " + regions.regions().size()))
                .thenExecute(cleanup)
                .thenSucceed();
    }

    /** M8b5b: the mean offset over its background of the cut room's air east of the border, in {@code r}'s frame. */
    private static double farHalfOffset(AirflowRegions.Region r, int border, int y, int z) {
        AirflowRuntime.Frame f = r == null || r.slot() == null ? null : r.slot().frame();
        if (f == null || f.frame() == null) {
            return Double.NaN;
        }
        double sum = 0;
        int n = 0;
        for (int x = border; x <= border + 4; x++) {
            for (int yy = y + 1; yy <= y + 4; yy++) {
                for (int zz = z + 1; zz <= z + 5; zz++) {
                    int lx = x - r.x0(), ly = yy - r.y0(), lz = zz - r.z0();
                    if (f.frame().isAir(lx + 0.5, ly + 0.5, lz + 0.5)) {
                        sum += f.frame().temperature(lx + 0.5, ly + 0.5, lz + 0.5) - f.frame().background().at(lx, ly, lz);
                        n++;
                    }
                }
            }
        }
        return n == 0 ? Double.NaN : sum / n;
    }

    /** M8b5b: the largest air speed along x in the cut room's cells either side of the border, m/s, in {@code r}'s frame. */
    private static double acrossTheBorder(AirflowRegions.Region r, int border, int y, int z) {
        AirflowRuntime.Frame f = r == null || r.slot() == null ? null : r.slot().frame();
        if (f == null || f.frame() == null) {
            return Double.NaN;
        }
        double[] v = new double[3];
        double max = 0;
        for (int x = border - 1; x <= border; x++) {
            for (int yy = y + 1; yy <= y + 4; yy++) {
                for (int zz = z + 1; zz <= z + 5; zz++) {
                    if (f.frame().velocity(x - r.x0() + 0.5, yy - r.y0() + 0.5, zz - r.z0() + 0.5, v)) {
                        max = Math.max(max, Math.abs(v[0]));
                    }
                }
            }
        }
        return max;
    }

    /** Window size of part b3: four sections across, three tall. */
    private static final int WX = 64, WY = 48, WZ = 64;

    /**
     * M7e (Bright, 2026-11-15): an anchor faster than {@code airflowSpeedLimit} (5.5 blocks a second over 2 s) opens no
     * tile, while the tile it keeps coming back to stays alive at that speed past its grace; once it slows down in
     * the new tile, that tile opens.
     */
    @GameTest(template = GameTestStructures.EMPTY, batch = "airflow_speed", timeoutTicks = 40_000)
    public static void fastAnchorsOpenNoTilesButKeepTheirOwn(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Vec3 a = Vec3.atBottomCenterOf(helper.absolutePos(new BlockPos(1, 2, 1)));
        AirflowRegions regions = new AirflowRegions(level);
        regions.alignForTests(helper.absolutePos(BlockPos.ZERO));
        regions.neighbourTiles(AirflowNeighbourTiles.Mode.OFF, 0); // counts regions
        int[] oa = regions.tileOf(a.x, a.y, a.z);
        Vec3 c = a.add(AirflowRegions.WX, 0, 0);
        int[] oc = regions.tileOf(c.x, c.y, c.z);
        List<long[]> forced = new ArrayList<>();
        for (int cx = oa[0] >> 4; cx < (oc[0] + AirflowRegions.WX) >> 4; cx++) {
            for (int cz = oa[2] >> 4; cz < (oa[2] + AirflowRegions.WZ) >> 4; cz++) {
                if (level.setChunkForced(cx, cz, true)) {
                    forced.add(new long[] {cx, cz});
                }
            }
        }
        boolean opened = AirflowSections.get(level) == null;
        // 0: still at a; 1: back and forth between a and c every 10 ticks (128 blocks a second); 2: still at c
        int[] phase = {0};
        regions.anchors(l -> {
            if (phase[0] == 0) {
                return List.of(a);
            }
            if (phase[0] == 2) {
                return List.of(c);
            }
            return List.of((l.getGameTime() / AirflowRegions.SCAN_TICKS) % 2 == 0 ? a : c);
        });
        regions.start();
        boolean[] stopped = {false};
        helper.onEachTick(() -> {
            if (!stopped[0]) {
                regions.tick();
            }
        });
        AirflowRegions.Region[] home = {null};
        long[] held = {0};
        helper.startSequence()
                .thenWaitUntil(() -> {
                    AirflowRegions.Region r = regions.regionAt(a.x, a.y, a.z);
                    helper.assertTrue(r != null && r.slot() != null, "waiting for the still anchor's tile");
                })
                .thenExecute(() -> {
                    home[0] = regions.regionAt(a.x, a.y, a.z);
                    helper.assertTrue(oc[0] == oa[0] + AirflowRegions.WX, "c is in the next tile east");
                    held[0] = regions.heldForSpeed();
                    phase[0] = 1;
                })
                .thenWaitUntil(() -> helper.assertTrue(regions.heldForSpeed() - held[0] >= 40,
                        "waiting for 40 matches held for speed (past the grace)"))
                .thenExecute(() -> {
                    helper.assertTrue(regions.regions().size() == 1 && regions.regions().get(0) == home[0],
                            "only the anchor's own tile runs, never rebuilt: " + regions.regions().size());
                    helper.assertTrue(regions.regionAt(c.x, c.y, c.z) == null, "no tile opened at speed");
                    phase[0] = 2;
                })
                .thenWaitUntil(() -> {
                    AirflowRegions.Region r = regions.regionAt(c.x, c.y, c.z);
                    helper.assertTrue(r != null && r.slot() != null, "waiting for the tile to open once still");
                })
                .thenExecute(() -> {
                    stopped[0] = true;
                    regions.stop();
                    for (long[] f : forced) {
                        level.setChunkForced((int) f[0], (int) f[1], false);
                    }
                    if (opened) {
                        AirflowSections.close(level);
                    }
                })
                .thenSucceed();
    }

    /**
     * M7c (Bright, 2026-11-15): sources give real-world watts from {@code meteodyne:heat_power}; lava and magma are walls
     * at a fixed temperature (solid cells whose code carries it); radiant-only blocks put nothing into the air.
     */
    @GameTest(template = GameTestStructures.EMPTY, batch = "airflow_sources")
    public static void heatTablesGiveWattsAndHotWalls(GameTestHelper helper) {
        BlockState campfire = Blocks.CAMPFIRE.defaultBlockState();
        helper.assertTrue(AirflowSources.watts(campfire) == 10_000, "a lit campfire: " + AirflowSources.watts(campfire));
        helper.assertTrue(AirflowSources.watts(campfire.setValue(BlockStateProperties.LIT, false)) == 0, "an unlit one");
        BlockState furnace = Blocks.FURNACE.defaultBlockState().setValue(BlockStateProperties.LIT, true);
        helper.assertTrue(AirflowSources.watts(furnace) == 5_000, "a lit furnace: " + AirflowSources.watts(furnace));
        helper.assertTrue(AirflowSources.watts(Blocks.GLOWSTONE.defaultBlockState()) == 0, "glowstone is radiant only");
        helper.assertTrue(AirflowSources.watts(Blocks.LAVA.defaultBlockState()) == 0, "lava gives no watts");
        byte lavaCode = AirflowCells.code(Blocks.LAVA.defaultBlockState());
        byte magmaCode = AirflowCells.code(Blocks.MAGMA_BLOCK.defaultBlockState());
        byte stoneCode = AirflowCells.code(Blocks.STONE.defaultBlockState());
        AirflowCells.Table table = AirflowCells.table(); // after the codes: interning makes a new table
        Cell lava = table.cell(lavaCode), magma = table.cell(magmaCode), stone = table.cell(stoneCode);
        helper.assertTrue(lava.kind() == Kind.SOLID && lava.wall() == 1000, "lava is a wall at 1000 C: " + lava);
        helper.assertTrue(magma.kind() == Kind.SOLID && magma.wall() == 400, "magma at 400 C: " + magma);
        helper.assertTrue(Double.isNaN(stone.wall()), "stone isn't fixed: " + stone);
        CellGrid3D grid = new CellGrid3D(16, 16, 16);
        lava.apply(grid, 3, 3, 3);
        helper.assertTrue(grid.fixedTemperature(grid.index(3, 3, 3)) == 1000, "a lava cell is written fixed");
        helper.succeed();
    }

    /**
     * M8a (Bright, 2026-11-16: a realistic surface film): {@code [heat] airflowSurfaceFilm} (m²·K/W, still air's 0.13
     * by default) reaches the cell table and every region's grid in GreedyFluids' unit, s/m (times air's rhoCp).
     */
    @GameTest(template = GameTestStructures.EMPTY)
    public static void regionsUseTheConfigsSurfaceFilm(GameTestHelper helper) {
        double rhoCp = dev.brights0ng.greedyfluids.solve.SolverConfig3D.DEFAULT.rhoCp();
        double expected = WeatherConfig.airflowSurfaceFilm() * rhoCp;
        helper.assertTrue(Math.abs(WeatherConfig.AIRFLOW_SURFACE_FILM.getDefault() - 0.13) < 1e-12,
                "the default is still air's 0.13 m²·K/W: " + WeatherConfig.AIRFLOW_SURFACE_FILM.getDefault());
        helper.assertTrue(Math.abs(AirflowCells.table().filmResistance() - expected) < 1e-9,
                "the table's film is the config's in s/m: " + AirflowCells.table().filmResistance() + " against " + expected);
        AirflowScene.Run run = new AirflowScene(16, 16, 16)
                .fill(0, 0, 0, 15, 0, 15, Blocks.STONE.defaultBlockState()).build((x, y, z, t) -> 10, 0, 0);
        double film = run.model.region().grid().filmResistance();
        helper.assertTrue(Math.abs(film - expected) < 1e-9, "a region's grid has it: " + film + " against " + expected);
        helper.succeed();
    }

    /**
     * d2: a campfire warms a closed stone room (closed, so the warmth stays in a few small boxes rather than mixing
     * into the open tile's big ones); stopping the manager saves the tile into its chunks; with the fire put out, a
     * new manager builds the tile from what was saved, so its very first frame is still warm in the room.
     * d3: the temperature lookup reads the live frame while the tile runs, the save while none does, and the
     * background once the save is gone.
     * e (2026-11-15): with the manager installed as the server's, the readers' room offset
     * ({@code MeteodyneTemperature.room}, which food and thermometers add to the outside air) follows the same three
     * sources; a point inside the room's stone wall reads the air beside it while the tile runs.
     */
    @GameTest(template = GameTestStructures.EMPTY, batch = "airflow_save", timeoutTicks = 40_000)
    public static void aTileComesBackWarmFromItsSave(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos stand = helper.absolutePos(new BlockPos(1, 2, 1));
        AirflowRegions[] regions = {new AirflowRegions(level)};
        BlockPos origin = helper.absolutePos(BlockPos.ZERO);
        regions[0].alignForTests(origin);
        regions[0].neighbourTiles(AirflowNeighbourTiles.Mode.OFF, 0); // counts restores
        int[] o = regions[0].tileOf(stand.getX() + 0.5, stand.getY(), stand.getZ() + 0.5);
        // the room fills the test structure above its floor: outer 7×6×7, interior 5×4×5
        BlockPos low = origin.above(), fire = low.offset(3, 1, 3), probe = low.offset(3, 3, 3);
        java.util.Map<BlockPos, BlockState> prior = new HashMap<>();
        for (BlockPos p : BlockPos.betweenClosed(low, low.offset(6, 5, 6))) {
            prior.put(p.immutable(), level.getBlockState(p));
        }
        Vec3 anchor = Vec3.atBottomCenterOf(stand);
        List<long[]> forced = new ArrayList<>();
        for (int cx = o[0] >> 4; cx < (o[0] + AirflowRegions.WX) >> 4; cx++) {
            for (int cz = o[2] >> 4; cz < (o[2] + AirflowRegions.WZ) >> 4; cz++) {
                if (level.setChunkForced(cx, cz, true)) {
                    forced.add(new long[] {cx, cz});
                }
            }
        }
        Runnable forget = () -> { // other tests' saves, and this one's, stay out of later runs
            for (int cx = o[0] >> 4; cx < (o[0] + AirflowRegions.WX) >> 4; cx++) {
                for (int cz = o[2] >> 4; cz < (o[2] + AirflowRegions.WZ) >> 4; cz++) {
                    LevelChunk c = level.getChunkSource().getChunkNow(cx, cz);
                    if (c != null && c.hasData(AirflowContent.CHUNK_DATA)) {
                        c.removeData(AirflowContent.CHUNK_DATA);
                    }
                }
            }
        };
        forget.run();
        for (BlockPos p : prior.keySet()) {
            boolean shell = p.getX() == low.getX() || p.getX() == low.getX() + 6 || p.getY() == low.getY()
                    || p.getY() == low.getY() + 5 || p.getZ() == low.getZ() || p.getZ() == low.getZ() + 6;
            level.setBlock(p, (shell ? Blocks.STONE : Blocks.AIR).defaultBlockState(), Block.UPDATE_CLIENTS);
        }
        level.setBlock(fire, Blocks.CAMPFIRE.defaultBlockState(), Block.UPDATE_CLIENTS);
        boolean opened = AirflowSections.get(level) == null;
        regions[0].anchors(l -> List.of(anchor));
        regions[0].start();
        boolean[] stopped = {false};
        helper.onEachTick(() -> {
            if (!stopped[0]) {
                regions[0].tick();
            }
        });
        double[] warm = {Double.NaN};
        Runnable cleanup = () -> {
            stopped[0] = true;
            AirflowRegions.useForTests(null);
            regions[0].stop();
            for (java.util.Map.Entry<BlockPos, BlockState> e : prior.entrySet()) {
                level.setBlock(e.getKey(), e.getValue(), Block.UPDATE_CLIENTS);
            }
            forget.run();
            for (long[] c : forced) {
                level.setChunkForced((int) c[0], (int) c[1], false);
            }
            if (opened) {
                AirflowSections.close(level);
            }
        };
        helper.startSequence()
                .thenWaitUntil(() -> {
                    AirflowRegions.Region r = regions[0].regionAt(anchor.x, anchor.y, anchor.z);
                    helper.assertTrue(r != null && r.slot() != null && r.slot().steps() >= 2,
                            "waiting for the tile to be built and stepped");
                })
                .thenExecute(() -> helper.assertTrue(regions[0].restores() == 0, "nothing saved yet"))
                .thenWaitUntil(() -> {
                    double[] t = sampleAt(regions[0], probe);
                    helper.assertTrue(t != null && t[0] > t[1] + 3, "waiting for the room to warm: "
                            + (t == null ? "no frame" : t[0] + " C against " + t[1]));
                    warm[0] = t[0] - t[1];
                })
                .thenExecute(() -> {
                    AirflowTemperatures.Reading live = AirflowTemperatures.at(regions[0], probe.getX() + 0.5,
                            probe.getY() + 0.5, probe.getZ() + 0.5);
                    helper.assertTrue(live.source() == AirflowTemperatures.Source.LIVE, "live while running: " + live);
                    AirflowRegions.useForTests(regions[0]);
                    var provider = dev.brights0ng.meteodyne.climate.MeteodyneTemperature.INSTANCE;
                    double room = provider.room(level, Vec3.atCenterOf(probe));
                    helper.assertTrue(room > 2, "the readers' room offset in the warm room: " + room);
                    // M8b5a: what a reader gets (its air plus the room) is the frame's air at the point, as it is;
                    // read while one frame stands (the air thread may publish between two reads)
                    AirflowRegions.Region held = regions[0].regionAt(probe.getX(), probe.getY(), probe.getZ());
                    double read = Double.NaN, here = Double.NaN;
                    for (int i = 0; i < 50 && Double.isNaN(here); i++) {
                        AirflowRuntime.Frame f = held.slot().frame();
                        double r = provider.air(level, probe) + provider.room(level, Vec3.atCenterOf(probe));
                        if (held.slot().frame() == f) {
                            read = r;
                            here = f.frame().temperature(probe.getX() - held.x0() + 0.5, probe.getY() - held.y0() + 0.5,
                                    probe.getZ() - held.z0() + 0.5);
                        }
                    }
                    helper.assertTrue(Math.abs(read - here) < 1e-4,
                            "a reading is the frame's air: " + read + " against " + here);
                    BlockPos inWall = low.offset(0, 3, 3);
                    double wall = provider.air(level, inWall) + provider.room(level, Vec3.atCenterOf(inWall));
                    // M7f: the mean of the air beside it, whatever it is (with κ 0.05 the air along a cold wall
                    // stays near the outside air, so a magnitude says nothing)
                    double beside = airBeside(regions[0], inWall);
                    helper.assertTrue(Double.isFinite(beside) && Math.abs(wall - beside) < 0.1,
                            "a block in the wall reads the air beside it: " + wall + " against " + beside);
                })
                .thenExecute(() -> {
                    regions[0].stop();
                    helper.assertTrue(regions[0].savesWritten() >= 1, "stopping saves the tile");
                    double savedRoom = dev.brights0ng.meteodyne.climate.MeteodyneTemperature.INSTANCE.room(level,
                            Vec3.atCenterOf(probe));
                    helper.assertTrue(savedRoom > 0, "the saved offset with no tile running: " + savedRoom);
                    AirflowRegions.useForTests(null);
                    LevelChunk c = level.getChunkSource().getChunkNow(probe.getX() >> 4, probe.getZ() >> 4);
                    helper.assertTrue(c != null && c.hasData(AirflowContent.CHUNK_DATA)
                            && c.getData(AirflowContent.CHUNK_DATA).section(probe.getY() >> 4) != null,
                            "the warm section is in its chunk's data");
                    AirflowTemperatures.Reading saved = AirflowTemperatures.at(regions[0], probe.getX() + 0.5,
                            probe.getY() + 0.5, probe.getZ() + 0.5);
                    helper.assertTrue(saved.source() == AirflowTemperatures.Source.SAVED, "saved when stopped: " + saved);
                    level.setBlock(fire, Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS);
                    regions[0] = new AirflowRegions(level);
                    regions[0].alignForTests(origin);
                    regions[0].neighbourTiles(AirflowNeighbourTiles.Mode.OFF, 0);
                    regions[0].anchors(l -> List.of(anchor));
                    regions[0].start();
                })
                .thenWaitUntil(() -> {
                    AirflowRegions.Region r = regions[0].regionAt(anchor.x, anchor.y, anchor.z);
                    helper.assertTrue(r != null && r.slot() != null && r.slot().frame() != null,
                            "waiting for the tile to be rebuilt");
                })
                .thenExecute(() -> {
                    helper.assertTrue(regions[0].restores() == 1, "the tile is built from its save");
                    double[] t = sampleAt(regions[0], probe);
                    // M7f: > 0.2, not > 1. A rebuild keeps the room's heat but meshes it afresh, so the layering of
                    // a still room (κ 0.05) averages out: the probe near the ceiling reads about the room's mean. A
                    // tile built without its save reads about 0 here.
                    helper.assertTrue(t != null && t[0] - t[1] > 0.2,
                            "the rebuilt room is still warm: " + (t == null ? "no frame" : t[0] + " C against " + t[1])
                                    + " (saved " + warm[0] + " above)");
                    AirflowRegions last = regions[0];
                    cleanup.run();
                    AirflowTemperatures.Reading none = AirflowTemperatures.at(last, probe.getX() + 0.5,
                            probe.getY() + 0.5, probe.getZ() + 0.5);
                    helper.assertTrue(none.source() == AirflowTemperatures.Source.BACKGROUND,
                            "the background once the save is gone: " + none);
                })
                .thenSucceed();
    }

    /** The latest frame's temperature at {@code at} and its background, C; or null before a frame. */
    private static double[] sampleAt(AirflowRegions regions, BlockPos at) {
        AirflowRegions.Region r = regions.regionAt(at.getX(), at.getY(), at.getZ());
        AirflowRuntime.Frame f = r == null || r.slot() == null ? null : r.slot().frame();
        if (f == null || f.frame() == null) {
            return null;
        }
        int lx = at.getX() - r.x0(), ly = at.getY() - r.y0(), lz = at.getZ() - r.z0();
        return new double[] {f.frame().temperature(lx + 0.5, ly + 0.5, lz + 0.5), f.frame().background().at(lx, ly, lz)};
    }

    /** The mean offset of the air beside a solid block in the latest frame (what a reader inside it gets), or NaN. */
    /** The mean air temperature of the frame's air cells beside {@code at}, C (M8b5a: as it is, not an offset). */
    private static double airBeside(AirflowRegions regions, BlockPos at) {
        AirflowRegions.Region r = regions.regionAt(at.getX(), at.getY(), at.getZ());
        AirflowRuntime.Frame f = r == null || r.slot() == null ? null : r.slot().frame();
        if (f == null || f.frame() == null) {
            return Double.NaN;
        }
        double sum = 0;
        int n = 0;
        for (net.minecraft.core.Direction d : net.minecraft.core.Direction.values()) {
            int lx = at.getX() + d.getStepX() - r.x0(), ly = at.getY() + d.getStepY() - r.y0();
            int lz = at.getZ() + d.getStepZ() - r.z0();
            if (f.frame().isAir(lx + 0.5, ly + 0.5, lz + 0.5)) {
                sum += f.frame().temperature(lx + 0.5, ly + 0.5, lz + 0.5);
                n++;
            }
        }
        return n == 0 ? Double.NaN : sum / n;
    }

    /**
     * Re-classifies every snapshot in {@code -Pmeteodyne.airflowSnapshots} with the in-game rule and compares it with
 * the bench's mapping (AIR is air, OPENING a door, everything else stone) over the window part b3 would build
 * around the snapshot's player. The report goes to the log and {@code m7b/b2-mapping.md} in that folder
 * ({@code b2-mapping-create.md} when Create is loaded, as in the core-less run). Without the property the test
 * passes at once.
 */
    @GameTest(template = GameTestStructures.EMPTY, batch = "airflow_snapshot_check", timeoutTicks = 2400)
    public static void snapshotMappingCheck(GameTestHelper helper) {
        String dir = System.getProperty("meteodyne.airflowSnapshots");
        if (dir == null || dir.isBlank()) {
            helper.succeed();
            return;
        }
        try {
            Path folder = Path.of(dir.trim());
            List<String> report = snapshotReport(helper.getLevel(), folder);
            boolean create = net.neoforged.fml.ModList.get().isLoaded("create");
            Path out = folder.resolve("m7b").resolve(create ? "b2-mapping-create.md" : "b2-mapping.md");
            Files.createDirectories(out.getParent());
            Files.writeString(out, String.join("\n", report) + "\n");
            Meteodyne.LOGGER.info("Airflow snapshot check ({}):\n{}", out, String.join("\n", report));
        } catch (IOException e) {
            helper.fail("snapshot check: " + e);
        }
        helper.succeed();
    }

    private static List<String> snapshotReport(ServerLevel level, Path folder) throws IOException {
        List<Path> files;
        try (var list = Files.list(folder)) {
            files = list.filter(f -> f.getFileName().toString().endsWith(".gfsnap")).sorted().toList();
        }
        HolderLookup<Block> blocks = level.registryAccess().lookupOrThrow(Registries.BLOCK);
        List<String> summary = new ArrayList<>(), detail = new ArrayList<>();
        summary.add("# Airflow mapping check (GreedyFluids M7b b2)");
        summary.add("");
        summary.add("Generated by `AirflowGameTests.snapshotMappingCheck`. Window: 64×48×64, section-snapped around the "
                + "snapshot's player (b3's rule). Bench: AIR is air, OPENING a door (Material.DOOR), the rest stone. "
                + "Game: `AirflowCells`. Unloaded and outside cells are stone in both; states this run can't parse "
                + "(mods not loaded) keep the bench's cell. Boxes: `AirRegion` construction, 10 °C, no wind, no sources.");
        summary.add("");
        summary.add("Run: " + net.neoforged.fml.ModList.get().size() + " mods loaded; Create "
                + (net.neoforged.fml.ModList.get().isLoaded("create") ? "loaded" : "not loaded") + ".");
        summary.add("");
        summary.add("| Snapshot | Window origin | Fluid cells, bench → game | Kind changes | Material-only changes "
                + "| Boxes, bench → game | Unparsed cells | Notes |");
        summary.add("|---|---|---|---|---|---|---|---|");
        for (Path file : files) {
            ScanFormat.Snapshot s = ScanFormat.readSnapshot(file);
            int wx0 = 16 * (int) Math.round((s.px() - WX / 2.0) / 16);
            int wz0 = 16 * (int) Math.round((s.pz() - WZ / 2.0) / 16);
            int wy0 = 16 * (Math.floorDiv((int) Math.floor(s.py()), 16) - 1);
            wy0 = Math.max(level.getMinBuildHeight(), Math.min(wy0, level.getMaxBuildHeight() - WY));
            int n = s.palette().size();
            Cell[] bench = new Cell[n], game = new Cell[n];
            boolean[] unparsed = new boolean[n];
            for (int i = 0; i < n; i++) {
                ScanFormat.PaletteEntry entry = s.palette().get(i);
                bench[i] = benchCell(entry);
                if (i < 2) { // #unloaded, #outside
                    game[i] = bench[i];
                    continue;
                }
                try {
                    BlockState state = BlockStateParser.parseForBlock(blocks, entry.state(), false).blockState();
                    byte code = AirflowCells.code(state);
                    game[i] = AirflowCells.table().cell(code);
                } catch (CommandSyntaxException e) {
                    unparsed[i] = true;
                    game[i] = bench[i];
                }
            }
            long[] count = new long[n];
            long missing = 0;
            CellGrid3D benchGrid = new CellGrid3D(WX, WY, WZ), gameGrid = new CellGrid3D(WX, WY, WZ);
            for (int y = 0; y < WY; y++) {
                for (int z = 0; z < WZ; z++) {
                    for (int x = 0; x < WX; x++) {
                        int sx = wx0 + x - s.x0(), sy = wy0 + y - s.y0(), sz = wz0 + z - s.z0();
                        int id = 0; // outside the snapshot (the window was clamped to the build height): as #unloaded
                        if (sx >= 0 && sy >= 0 && sz >= 0 && sx < s.nx() && sy < s.ny() && sz < s.nz()) {
                            id = Short.toUnsignedInt(s.cells()[(sy * s.nz() + sz) * s.nx() + sx]);
                        } else {
                            missing++;
                        }
                        count[id]++;
                        bench[id].apply(benchGrid, x, y, z);
                        game[id].apply(gameGrid, x, y, z);
                    }
                }
            }
            long benchFluid = 0, gameFluid = 0, kinds = 0, materials = 0, unknown = 0;
            Map<String, Long> changes = new HashMap<>();
            for (int i = 0; i < n; i++) {
                if (count[i] == 0) {
                    continue;
                }
                benchFluid += bench[i].fluid() ? count[i] : 0;
                gameFluid += game[i].fluid() ? count[i] : 0;
                unknown += unparsed[i] ? count[i] : 0;
                String block = s.palette().get(i).state().split("\\[", 2)[0];
                if (bench[i].kind() != game[i].kind()) {
                    kinds += count[i];
                    changes.merge(block + ": " + bench[i].kind() + " → " + game[i].kind(), count[i], Long::sum);
                } else if (bench[i].kind() != Kind.AIR && !sameValues(bench[i].material(), game[i].material())) {
                    materials += count[i];
                    changes.merge(block + ": " + bench[i].kind() + " material → " + game[i].material().name()
                            + " (" + fmt(game[i].material().conductancePerBlock() / AirflowCells.stoneConductance())
                            + ", " + fmt(game[i].material().relHeatCapacity() / AirflowCells.stoneCapacity()) + ")",
                            count[i], Long::sum);
                }
            }
            String boxes = boxes(benchGrid) + " → " + boxes(gameGrid);
            summary.add("| " + s.label() + " | " + wx0 + ", " + wy0 + ", " + wz0 + " | " + benchFluid + " → " + gameFluid
                    + " | " + kinds + " | " + materials + " | " + boxes + " | " + unknown + " | "
                    + (missing > 0 ? missing + " cells outside the snapshot (window clamped to the build height), stone in both" : "")
                    + " |");
            detail.add("");
            detail.add("## " + s.label() + " (" + file.getFileName() + ")");
            detail.add("");
            detail.add("| Block: change | Cells |");
            detail.add("|---|---|");
            changes.entrySet().stream()
                    .sorted(Map.Entry.<String, Long>comparingByValue(Comparator.reverseOrder())
                            .thenComparing(Map.Entry.<String, Long>comparingByKey()))
                    .limit(25)
                    .forEach(e -> detail.add("| " + e.getKey() + " | " + e.getValue() + " |"));
            if (changes.size() > 25) {
                detail.add("| (" + (changes.size() - 25) + " more) | |");
            }
            List<String> notParsed = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                if (unparsed[i] && count[i] > 0) {
                    notParsed.add(s.palette().get(i).state().split("\\[", 2)[0]);
                }
            }
            if (!notParsed.isEmpty()) {
                detail.add("");
                detail.add("Unparsed: " + String.join(", ", notParsed.stream().distinct().sorted().toList()));
            }
        }
        summary.addAll(detail);
        return summary;
    }

    /** The bench's mapping ({@code ScanSnapshot.of} in GreedyFluids' proto sources). */
    private static Cell benchCell(ScanFormat.PaletteEntry entry) {
        if (entry.kind() == ScanFormat.AIR) {
            return new Cell(Kind.AIR, null);
        }
        if (entry.kind() == ScanFormat.OPENING) {
            return new Cell(entry.state().contains("open=true") ? Kind.DOOR_OPEN : Kind.DOOR_SHUT, Material.DOOR);
        }
        return new Cell(Kind.SOLID, Material.STONE);
    }

    /** Whether the bench's and the game's materials are the same relative to each one's stone (M7f: the game's is real). */
    private static boolean sameValues(Material bench, Material game) {
        return Math.abs(bench.conductancePerBlock() / Material.STONE_CONDUCTANCE_PER_BLOCK
                - game.conductancePerBlock() / AirflowCells.stoneConductance()) < 1e-9
                && Math.abs(bench.relHeatCapacity() / Material.STONE.relHeatCapacity()
                - game.relHeatCapacity() / AirflowCells.stoneCapacity()) < 1e-9;
    }

    private static String boxes(CellGrid3D grid) {
        try {
            long start = System.nanoTime();
            AirRegion region = new AirRegion(grid, Background3D.uniform(grid.nx(), grid.ny(), grid.nz(), 10), 0, 0);
            return region.mesh().graph().boxCount() + " (" + (System.nanoTime() - start) / 1_000_000 + " ms)";
        } catch (RuntimeException e) {
            return "failed: " + e;
        }
    }

    private static String fmt(double v) {
        return String.format(java.util.Locale.ROOT, "%.3g", v);
    }

    private AirflowGameTests() {
    }
}
