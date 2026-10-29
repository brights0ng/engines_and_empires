// Written by Claude.
package dev.brights0ng.meteodyne.heat.airgrid;

import dev.brights0ng.meteodyne.Meteodyne;
import dev.brights0ng.paradynelib.gametest.GameTestStructures;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.state.properties.SlabType;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** The snapshot reads chunk sections into the grid, and refuses unloaded chunks and oversized boxes. */
@GameTestHolder(Meteodyne.MODID)
@PrefixGameTestTemplate(false)
public final class AirSnapshotGameTests {

    @GameTest(template = GameTestStructures.EMPTY, batch = "air_cell_snapshot")
    public static void snapshotReadsTheBoxFromChunkSections(GameTestHelper helper) {
        BlockPos stone = helper.absolutePos(new BlockPos(2, 2, 2));
        BlockPos slab = helper.absolutePos(new BlockPos(3, 2, 2));
        helper.getLevel().setBlockAndUpdate(stone, Blocks.STONE.defaultBlockState());
        helper.getLevel().setBlockAndUpdate(slab, Blocks.OAK_SLAB.defaultBlockState().setValue(SlabBlock.TYPE, SlabType.TOP));
        try {
            AirSnapshot.Result r = AirSnapshot.take(helper.getLevel(), stone.offset(-1, -1, -1), stone.offset(2, 1, 1));
            CellCountStudy.Grid g = r.grid();
            helper.assertTrue(g.sx() == 4 && g.sy() == 3 && g.sz() == 3, "4x3x3 box");
            helper.assertTrue(g.types()[g.index(1, 1, 1)] == AirCellType.SOLID.ordinal(), "Stone is SOLID");
            helper.assertTrue(g.types()[g.index(2, 1, 1)] == AirCellType.TOP_HALF.ordinal(), "Top slab is TOP_HALF");
            helper.assertTrue(g.types()[g.index(1, 2, 1)] == AirCellType.AIR.ordinal(), "Above the stone is AIR");
            helper.assertTrue(g.sky()[g.index(1, 1, 1)] == 0, "Walls carry no sky byte");
        } catch (AirSnapshot.Failure f) {
            helper.fail("Snapshot failed: " + f.getMessage());
        }
        BlockPos far = stone.offset(1 << 20, 0, 0);
        try {
            AirSnapshot.take(helper.getLevel(), far, far.offset(4, 4, 4));
            helper.fail("An unloaded chunk must fail the snapshot");
        } catch (AirSnapshot.Failure f) {
            helper.assertTrue(f.getMessage().contains("not loaded") && f.getMessage().contains("[" + (far.getX() >> 4) + ", "),
                    "The failure names the chunk: " + f.getMessage());
        }
        try {
            AirSnapshot.take(helper.getLevel(), stone.offset(-128, -64, -128), stone.offset(128, 64, 128));
            helper.fail("A box over the cap must fail");
        } catch (AirSnapshot.Failure f) {
            helper.assertTrue(f.getMessage().contains("cap"), "The failure names the cap: " + f.getMessage());
        }
        helper.succeed();
    }
}
