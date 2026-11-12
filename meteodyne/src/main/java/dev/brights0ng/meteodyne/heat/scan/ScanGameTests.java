// Written by Claude; edited by Claude (v2 CELLS replay test, GreedyFluids M6f).
package dev.brights0ng.meteodyne.heat.scan;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import dev.brights0ng.meteodyne.Meteodyne;
import dev.brights0ng.paradynelib.gametest.GameTestStructures;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CampfireBlock;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@GameTestHolder(Meteodyne.MODID)
@PrefixGameTestTemplate(false)
public final class ScanGameTests {
    @GameTest(template = GameTestStructures.EMPTY, batch = "scan")
    public static void snapshotClassifiesBlocks(GameTestHelper helper) {
        BlockPos stone = new BlockPos(1, 1, 1), door = new BlockPos(2, 1, 1);
        BlockPos water = new BlockPos(3, 1, 1), fire = new BlockPos(4, 1, 1);
        BlockPos leaves = new BlockPos(5, 1, 1), free = new BlockPos(1, 3, 1);
        helper.setBlock(stone, Blocks.STONE);
        helper.setBlock(door.below(), Blocks.STONE);
        helper.setBlock(door, Blocks.OAK_DOOR.defaultBlockState().setValue(DoorBlock.HALF, DoubleBlockHalf.LOWER));
        helper.setBlock(door.above(), Blocks.OAK_DOOR.defaultBlockState().setValue(DoorBlock.HALF, DoubleBlockHalf.UPPER));
        helper.setBlock(water, Blocks.WATER);
        helper.setBlock(fire, Blocks.CAMPFIRE.defaultBlockState().setValue(CampfireBlock.LIT, true));
        helper.setBlock(leaves, Blocks.OAK_LEAVES);
        helper.setBlock(free, Blocks.AIR);
        Path dir = tempDir();
        Path file = ScanSnapshot.write(helper.getLevel(), helper.absolutePos(new BlockPos(3, 3, 3)),
                8, 8, "test", dir, null);
        try {
            var scan = ScanFormat.readSnapshot(file);
            helper.assertTrue(scan.cell(helper.absolutePos(stone)).kind() == ScanFormat.SOLID, "stone is SOLID");
            helper.assertTrue(scan.cell(helper.absolutePos(door)).kind() == ScanFormat.OPENING, "lower door is OPENING");
            helper.assertTrue(scan.cell(helper.absolutePos(door.above())).kind() == ScanFormat.OPENING, "upper door is OPENING");
            helper.assertTrue(scan.cell(helper.absolutePos(water)).kind() == ScanFormat.WATER, "water is WATER");
            helper.assertTrue(scan.cell(helper.absolutePos(leaves)).kind() == ScanFormat.POROUS, "leaves are POROUS");
            var campfire = scan.cell(helper.absolutePos(fire));
            helper.assertTrue(campfire.kind() == ScanFormat.PARTIAL && (campfire.flags() & 1) != 0,
                    "lit campfire is PARTIAL and a source");
            helper.assertTrue(scan.cell(helper.absolutePos(free)).kind() == ScanFormat.AIR, "free cell is AIR");
            helper.assertTrue(scan.palette().get(0).equals(new ScanFormat.PaletteEntry("#unloaded", ScanFormat.UNKNOWN, (byte) 0)),
                    "palette 0 is unloaded");
            helper.assertTrue(scan.palette().get(1).equals(new ScanFormat.PaletteEntry("#outside", ScanFormat.UNKNOWN, (byte) 0)),
                    "palette 1 is outside");
            helper.succeed();
        } catch (IOException e) { throw new UncheckedIOException(e); }
    }

    @GameTest(template = GameTestStructures.EMPTY, batch = "scan")
    public static void recorderLogsEdits(GameTestHelper helper) {
        BlockPos origin = helper.absolutePos(new BlockPos(2, 2, 2));
        BlockPos p = origin; // Keep the edit and anchor in the same section even at a section boundary.
        helper.getLevel().setBlock(p, Blocks.AIR.defaultBlockState(), 3);
        Path file = ScanRecorder.start(tempDir(), helper.getLevel(), () -> Vec3.atLowerCornerOf(origin));
        try {
            helper.getLevel().setBlock(p, Blocks.STONE.defaultBlockState(), 3);
            helper.getLevel().setBlock(p, Blocks.AIR.defaultBlockState(), 3);
            helper.runAfterDelay(2, () -> {
                ScanRecorder.stop();
                try {
                    var recording = ScanFormat.readRecording(file);
                    Map<Integer, ScanFormat.State> states = new HashMap<>();
                    List<ScanFormat.BlockChange> edits = new ArrayList<>();
                    boolean section = false, end = false;
                    long lastTick = Long.MIN_VALUE;
                    for (var entry : recording.entries()) {
                        if (entry instanceof ScanFormat.State state) states.put(state.id(), state);
                        if (entry instanceof ScanFormat.BlockChange block) {
                            helper.assertTrue(states.containsKey(block.beforeId()) && states.containsKey(block.afterId()),
                                    "STATE precedes BLOCK references");
                            if (block.x() == p.getX() && block.y() == p.getY() && block.z() == p.getZ()) edits.add(block);
                        }
                        if (entry instanceof ScanFormat.Section summary) {
                            int sum = java.util.Arrays.stream(summary.counts()).sum();
                            helper.assertTrue(sum == 4096, "section classes cover 4096 cells");
                            if (summary.uniformId() >= 0) helper.assertTrue(states.containsKey(summary.uniformId()),
                                    "STATE precedes SECTION reference");
                            if (summary.sx() == p.getX() >> 4 && summary.sy() == p.getY() >> 4
                                    && summary.sz() == p.getZ() >> 4) section = true;
                        }
                        if (entry instanceof ScanFormat.End) end = true;
                        long tick = tickOf(entry);
                        if (tick != Long.MIN_VALUE) {
                            helper.assertTrue(tick >= lastTick, "record ticks never decrease");
                            lastTick = tick;
                        }
                    }
                    helper.assertTrue(edits.size() == 2, "exactly two edits at p");
                    helper.assertTrue(states.get(edits.get(0).beforeId()).kind() == ScanFormat.AIR
                            && states.get(edits.get(0).afterId()).kind() == ScanFormat.SOLID, "first edit AIR to SOLID");
                    helper.assertTrue(states.get(edits.get(1).beforeId()).kind() == ScanFormat.SOLID
                            && states.get(edits.get(1).afterId()).kind() == ScanFormat.AIR, "second edit SOLID to AIR");
                    helper.assertTrue(section, "anchor section is summarised in the first ticks");
                    helper.assertTrue(end, "END is present");
                    helper.assertTrue(recording.entries().stream().anyMatch(e -> e instanceof ScanFormat.Position pos
                            && pos.flags() == 0 && pos.x() == origin.getX() && pos.y() == origin.getY()
                            && pos.z() == origin.getZ()), "test anchor supplies POS with flags 0");
                    helper.succeed();
                } catch (IOException e) { throw new UncheckedIOException(e); }
            });
        } catch (RuntimeException e) {
            ScanRecorder.stop();
            throw e;
        }
    }

    /**
     * v2: a section 3 sections from the anchor but more than 48 blocks away is summarised with CELLS, its later edits
     * are logged, and its CELLS plus the BLOCK records after them in the file reproduce the live section exactly.
     * The edit made before the summary must not be applied (it is already in the cells). The door and glass may fall
     * in a neighbouring section, depending on where the test is placed; the full-section comparison covers them when not.
     */
    @GameTest(template = GameTestStructures.EMPTY, batch = "scanCells", timeoutTicks = 200)
    public static void recorderCellsReproduceSection(GameTestHelper helper) {
        BlockPos p = null;
        for (int x = 1; x <= 5 && p == null; x++) {
            BlockPos candidate = helper.absolutePos(new BlockPos(x, 2, 3));
            if ((candidate.getX() & 15) != 0) p = candidate;
        }
        BlockPos edit = p, early = p.above(), fire = p.below();
        BlockPos door = p.offset(0, 0, -2);
        Vec3 anchor = new Vec3(((p.getX() >> 4) - 3) * 16 + 0.5, p.getY() + 0.5, p.getZ() + 0.5);
        helper.assertTrue(edit.getX() - (int) Math.floor(anchor.x) > 48, "edit lies beyond the v1 48-block radius");
        var level = helper.getLevel();
        level.setBlock(fire, Blocks.CAMPFIRE.defaultBlockState().setValue(CampfireBlock.LIT, true), 3);
        level.setBlock(door.below(), Blocks.STONE.defaultBlockState(), 3);
        level.setBlock(door, Blocks.OAK_DOOR.defaultBlockState().setValue(DoorBlock.HALF, DoubleBlockHalf.LOWER), 3);
        level.setBlock(door.above(), Blocks.OAK_DOOR.defaultBlockState().setValue(DoorBlock.HALF, DoubleBlockHalf.UPPER), 3);
        Path file = ScanRecorder.start(tempDir(), level, () -> anchor);
        final BlockPos target = edit;
        try {
            level.setBlock(early, Blocks.GLASS.defaultBlockState(), 3); // Before the section is summarised.
            helper.runAfterDelay(80, () -> {
                level.setBlock(target, Blocks.STONE.defaultBlockState(), 3);
                level.setBlock(door, Blocks.OAK_DOOR.defaultBlockState().setValue(DoorBlock.HALF, DoubleBlockHalf.LOWER)
                        .setValue(DoorBlock.OPEN, true), 3);
                helper.runAfterDelay(2, () -> {
                    ScanRecorder.stop();
                    try { verifyCells(helper, ScanFormat.readRecording(file), target); }
                    catch (IOException e) { throw new UncheckedIOException(e); }
                });
            });
        } catch (RuntimeException e) {
            ScanRecorder.stop();
            throw e;
        }
    }

    private static void verifyCells(GameTestHelper helper, ScanFormat.Recording recording, BlockPos target) {
        helper.assertTrue(recording.blockRadius() == ScanFormat.BLOCK_RADIUS_SECTIONS, "v2 header marks section-range edits");
        int sx = target.getX() >> 4, sy = target.getY() >> 4, sz = target.getZ() >> 4;
        Map<Integer, ScanFormat.State> states = new HashMap<>();
        int[] ids = null;
        boolean targetLogged = false;
        int applied = 0;
        StringBuilder trace = new StringBuilder();
        for (var entry : recording.entries()) {
            if (entry instanceof ScanFormat.Cells c && c.sx() == sx && c.sy() == sy && c.sz() == sz) trace.append(" CELLS@").append(c.tick());
            if (entry instanceof ScanFormat.Section c && c.sx() == sx && c.sy() == sy && c.sz() == sz) trace.append(" SECTION@").append(c.tick()).append("u").append(c.uniformId());
            if (entry instanceof ScanFormat.BlockChange b && b.x() >> 4 == sx && b.y() >> 4 == sy && b.z() >> 4 == sz) trace.append(" BLOCK@").append(b.tick());
            if (entry instanceof ScanFormat.State state) states.put(state.id(), state);
            if (entry instanceof ScanFormat.Cells cells && cells.sx() == sx && cells.sy() == sy && cells.sz() == sz) {
                ids = new int[4096];
                for (int i = 0; i < 4096; i++) {
                    ids[i] = cells.palette()[Short.toUnsignedInt(cells.cells()[i])];
                    helper.assertTrue(states.containsKey(ids[i]), "STATE precedes CELLS reference");
                }
                applied = 0;
            }
            if (entry instanceof ScanFormat.BlockChange block && block.x() >> 4 == sx && block.y() >> 4 == sy
                    && block.z() >> 4 == sz) {
                if (block.x() == target.getX() && block.y() == target.getY() && block.z() == target.getZ()
                        && states.get(block.afterId()).kind() == ScanFormat.SOLID) targetLogged = true;
                if (ids == null) continue; // Before the summary: already contained in the cells.
                int i = ((block.y() & 15) * 16 + (block.z() & 15)) * 16 + (block.x() & 15);
                helper.assertTrue(ids[i] == block.beforeId(), "BLOCK before-state matches the reconstructed cell");
                ids[i] = block.afterId();
                if (block.x() == target.getX() && block.y() == target.getY() && block.z() == target.getZ()) applied++;
            }
        }
        helper.assertTrue(ids != null, "the outer-ring section has CELLS");
        helper.assertTrue(targetLogged, "the edit beyond 48 blocks is logged");
        helper.assertTrue(applied == 1, "the target edit follows the summary and is applied:" + trace);
        var level = helper.getLevel();
        for (int y = 0; y < 16; y++) for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++) {
            String live = ScanFormat.stateString(level.getBlockState(new BlockPos(sx * 16 + x, sy * 16 + y, sz * 16 + z)));
            String replayed = states.get(ids[(y * 16 + z) * 16 + x]).state();
            if (!live.equals(replayed))
                helper.fail("cell " + x + "," + y + "," + z + ": live " + live + ", replayed " + replayed);
        }
        helper.succeed();
    }

    private static long tickOf(ScanFormat.Entry entry) {
        if (entry instanceof ScanFormat.End value) return value.tick();
        if (entry instanceof ScanFormat.Dimension value) return value.tick();
        if (entry instanceof ScanFormat.Position value) return value.tick();
        if (entry instanceof ScanFormat.BlockChange value) return value.tick();
        if (entry instanceof ScanFormat.Action value) return value.tick();
        if (entry instanceof ScanFormat.Section value) return value.tick();
        if (entry instanceof ScanFormat.Cells value) return value.tick();
        if (entry instanceof ScanFormat.Load value) return value.tick();
        if (entry instanceof ScanFormat.Mark value) return value.tick();
        return Long.MIN_VALUE;
    }

    private static Path tempDir() {
        try { return Files.createTempDirectory("gfscan"); }
        catch (IOException e) { throw new UncheckedIOException(e); }
    }

    private ScanGameTests() { }
}
