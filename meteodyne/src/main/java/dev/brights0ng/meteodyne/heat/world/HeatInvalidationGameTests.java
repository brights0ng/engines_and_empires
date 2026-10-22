// Written by ChatGPT.
package dev.brights0ng.meteodyne.heat.world;

import java.util.Arrays;
import com.google.gson.JsonParser;
import dev.brights0ng.meteodyne.Meteodyne;
import dev.brights0ng.paradynelib.gametest.GameTestStructures;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** Snapshot reuse must preserve source transitions, material response and the full-rebuild thermal control. */
@GameTestHolder(Meteodyne.MODID)
@PrefixGameTestTemplate(false)
public final class HeatInvalidationGameTests {
    @GameTest(template=GameTestStructures.EMPTY,batch="thermal_invalidation")
    public static void sourceAndMaterialChangesReuseShelterAndMatchControl(GameTestHelper helper) throws Exception {
        var level=helper.getLevel();
        var world=HeatWorld.of(level);
        BlockPos fire=new BlockPos(3,1,3), wall=new BlockPos(5,1,3);
        BlockPos low=helper.absolutePos(BlockPos.ZERO), high=helper.absolutePos(new BlockPos(6,6,6));
        world.setPaused(true);
        world.setTestArea(new int[]{low.getX(),low.getY(),low.getZ(),high.getX(),high.getY(),high.getZ()});
        world.forgetRegions();
        try {
            helper.setBlock(fire,Blocks.CAMPFIRE);
            helper.setBlock(wall,Blocks.STONE);
            world.runNow(60);
            var r=world.regions().getFirst();
            var geometry=r.geometry;
            long revision=r.geometryRevision, sourceRevision=r.sourceRevision;
            var source=level.getServer().createCommandSourceStack().withLevel(level);
            world.benchmark().start(source,60,0);
            world.benchmark().tick(level.getGameTime());

            // The only source going out must keep its warm, cooling region and update power immediately.
            helper.setBlock(fire,Blocks.CAMPFIRE.defaultBlockState().setValue(BlockStateProperties.LIT,false));
            float[] initial=r.published.offsets().clone();
            // Publications are floats, while the worker retains doubles. Start both trials from the same precision.
            r.grid.setOffsets(initial);
            world.runNow(1);
            helper.assertTrue(world.regions().contains(r) && r.sources.isEmpty(),"warm region persists with source off");
            helper.assertTrue(r.geometry==geometry && r.geometryRevision==revision,"lit toggle reuses geometry");
            helper.assertTrue(r.sourceRevision>sourceRevision,"source revision advances independently");
            float[] optimized=r.published.offsets().clone();
            r.grid.setOffsets(initial);
            r.invalidate(true,true);
            world.runNow(1);
            helper.assertTrue(Arrays.equals(optimized,r.published.offsets()),"source-only result equals forced full snapshot control");

            // Return the source, then replace a full stone block with a full wooden material.
            helper.setBlock(fire,Blocks.CAMPFIRE);
            world.runNow(1);
            byte[] cover=r.geometry.cover();
            revision=r.geometryRevision;
            long materialRevision=r.materialRevision;
            helper.setBlock(wall,Blocks.OAK_PLANKS);
            initial=r.published.offsets().clone();
            r.grid.setOffsets(initial);
            world.runNow(1);
            helper.assertTrue(r.geometry.cover()==cover && r.geometryRevision==revision,"material-only change reuses shelter/topology");
            helper.assertTrue(r.materialRevision>materialRevision,"material revision advances");
            BlockPos at=helper.absolutePos(wall);
            int index=r.index(at.getX()-r.box[0],at.getY()-r.box[1],at.getZ()-r.box[2]);
            helper.assertTrue(r.geometry.capacities()[index]==HeatTables.cell(Blocks.OAK_PLANKS.defaultBlockState()).capacity(),"new material reaches prepared snapshot");
            optimized=r.published.offsets().clone();
            r.grid.setOffsets(initial);
            r.invalidate(true,true);
            world.runNow(1);
            helper.assertTrue(Arrays.equals(optimized,r.published.offsets()),"material-only result equals forced full snapshot control");

            world.benchmark().stop();
            var report=JsonParser.parseString(java.nio.file.Files.readString(world.benchmark().lastReport().resolve("summary.json"))).getAsJsonObject();
            helper.assertTrue(report.get("schema").getAsString().equals("thermal-live-v3"),"versioned capture schema");
            helper.assertTrue(report.getAsJsonObject("blockStateChanges").keySet().stream().anyMatch(k->k.startsWith("unchanged_snapshot_inputs:")),"state pair identifies skipped rebuild");
            helper.assertTrue(report.getAsJsonObject("invalidationEvents").get("block_material").getAsLong()>0,"material cause recorded");
            helper.assertTrue(report.getAsJsonObject("metrics").has("material_snapshot_ms"),"material preparation measured separately");
            helper.assertTrue(java.nio.file.Files.readAllLines(world.benchmark().lastReport().resolve("jobs.csv")).getFirst().contains("geometry_revision"),"job revisions retained");
            helper.assertTrue(report.getAsJsonObject("metrics").getAsJsonObject("geometry_snapshot_ms").get("count").getAsInt()==2,"only the two forced controls rescan full geometry");
            helper.assertTrue(report.getAsJsonObject("metrics").getAsJsonObject("material_snapshot_ms").get("count").getAsInt()==1,"material replacement takes one material snapshot");

            // Actual block-hook coverage outside the region, including the remote heightmap column.
            checkExternalChange(helper,world,r,new BlockPos(r.box[3]+1,helper.absolutePos(fire).getY(),helper.absolutePos(fire).getZ()));
            checkExternalChange(helper,world,r,new BlockPos(helper.absolutePos(fire).getX(),r.box[4]+5,helper.absolutePos(fire).getZ()));
            var previous=r.geometry;
            HeatTables.clear();
            world.runNow(0);
            helper.assertTrue(r.geometry!=previous,"table reload invalidates prepared inputs");
            helper.succeed();
        } finally {
            world.benchmark().stop();
            helper.setBlock(fire,Blocks.AIR);helper.setBlock(wall,Blocks.AIR);
            world.forgetRegions();world.setTestArea(null);world.setPaused(false);
        }
    }

    @GameTest(template=GameTestStructures.EMPTY,batch="thermal_invalidation")
    public static void physicalChangesIncludePartialShapesFluidsAndCalculationMargin(GameTestHelper helper) {
        var door=Blocks.OAK_DOOR.defaultBlockState();
        helper.assertTrue(HeatTables.change(door,door.setValue(BlockStateProperties.OPEN,true)).geometry(),"door opening invalidates geometry");
        var trapdoor=Blocks.OAK_TRAPDOOR.defaultBlockState();
        helper.assertTrue(HeatTables.change(trapdoor,trapdoor.setValue(BlockStateProperties.OPEN,true)).geometry(),"trapdoor opening invalidates geometry");
        var snow=Blocks.SNOW.defaultBlockState().setValue(BlockStateProperties.LAYERS,2);
        helper.assertTrue(HeatTables.change(snow,snow.setValue(BlockStateProperties.LAYERS,3)).geometry(),"thin snow geometry differs even with matching open-face bits");
        var slab=Blocks.OAK_SLAB.defaultBlockState();
        helper.assertTrue(HeatTables.change(slab,slab.setValue(BlockStateProperties.WATERLOGGED,true)).geometry(),"waterlogging invalidates fluid space");
        helper.assertTrue(HeatTables.change(null,slab).geometry(),"unknown deferred prior state rebuilds conservatively");
        var r=new HeatRegion(0,new int[]{0,0,0,6,6,6},dev.brights0ng.paradynelib.heat.air.AirParams.DEFAULT);
        helper.assertTrue(r.affectedBy(8,3,3,16,false),"shelter halo included");
        helper.assertTrue(r.affectedBy(3,100,3,16,true),"heightmap column included above calculation box");
        helper.assertTrue(r.affectedBy(3,-1,3,0,false),"shape neighbor included with zero shelter reach");
        helper.assertTrue(!r.affectedBy(23,3,3,16,false),"distant unrelated geometry excluded");
        helper.succeed();
    }

    private static void checkExternalChange(GameTestHelper helper, HeatWorld world, HeatRegion r, BlockPos at) {
        var level=helper.getLevel();
        helper.assertTrue(level.getChunkSource().getChunkNow(at.getX()>>4,at.getZ()>>4)!=null,"margin fixture uses an already loaded chunk");
        var previous=level.getBlockState(at);
        try {
            var next=(previous.blocksMotion() || !previous.getFluidState().isEmpty())?Blocks.AIR.defaultBlockState():Blocks.STONE.defaultBlockState();
            var geometry=r.geometry;
            level.setBlock(at,next,3);
            helper.assertTrue(r.geometryDirty,"external sampled geometry invalidated by block hook");
            world.runNow(0);
            helper.assertTrue(r.geometry!=geometry && !r.geometryDirty,"external change rebuilt before next step");
        } finally {
            level.setBlock(at,previous,3);
            world.runNow(0);
        }
    }

    private HeatInvalidationGameTests() { }
}
