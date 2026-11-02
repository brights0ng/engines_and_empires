// Written by Claude.
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

    private static long tickOf(ScanFormat.Entry entry) {
        if (entry instanceof ScanFormat.End value) return value.tick();
        if (entry instanceof ScanFormat.Dimension value) return value.tick();
        if (entry instanceof ScanFormat.Position value) return value.tick();
        if (entry instanceof ScanFormat.BlockChange value) return value.tick();
        if (entry instanceof ScanFormat.Action value) return value.tick();
        if (entry instanceof ScanFormat.Section value) return value.tick();
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
