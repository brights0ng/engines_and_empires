// Written by ChatGPT.
package dev.brights0ng.meteodyne.heat.world;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import dev.brights0ng.meteodyne.Meteodyne;
import dev.brights0ng.paradynelib.gametest.GameTestStructures;
import dev.brights0ng.paradynelib.heat.air.AirParams;
import dev.brights0ng.paradynelib.wind.WindParams;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** Independent legacy snapshot controls, immutable arrays, dirty replay and deterministic shared work limits. */
@GameTestHolder(Meteodyne.MODID)
@PrefixGameTestTemplate(false)
public final class HeatGeometryGameTests {
    @GameTest(template=GameTestStructures.EMPTY,batch="thermal_geometry_incremental")
    public static void incrementalSnowDoorsFluidsAndHaloMatchFullReference(GameTestHelper helper) {
        var level=helper.getLevel();
        var world=HeatWorld.of(level);
        Map<BlockPos,BlockState> restore=new LinkedHashMap<>();
        BlockPos low=helper.absolutePos(BlockPos.ZERO), high=low.offset(8,8,8);
        var r=new HeatRegion(0,new int[]{low.getX(),low.getY(),low.getZ(),high.getX(),high.getY(),high.getZ()},AirParams.DEFAULT);
        try {
            var full=new HeatGeometryUpdate(r,WindParams.DEFAULTS,true);
            drain(helper,full,level,7);
            long fullReads=full.blockReads;
            r.geometry=full.finish();
            same(helper,r.geometry,world.referenceGeometry(r));
            BlockPos at=low.offset(4,2,4);
            long maxSnowReads=0;
            for (int layers=1;layers<=8;layers++) {
                maxSnowReads=Math.max(maxSnowReads,edit(helper,world,r,restore,at,Blocks.SNOW.defaultBlockState().setValue(BlockStateProperties.LAYERS,layers),fullReads));
            }
            for (int layers=7;layers>=1;layers--) {
                edit(helper,world,r,restore,at,Blocks.SNOW.defaultBlockState().setValue(BlockStateProperties.LAYERS,layers),fullReads);
            }
            edit(helper,world,r,restore,at,Blocks.AIR.defaultBlockState(),fullReads);
            var door=Blocks.OAK_DOOR.defaultBlockState();
            edit(helper,world,r,restore,at,door,fullReads);
            edit(helper,world,r,restore,at,door.setValue(BlockStateProperties.OPEN,true),fullReads);
            var trapdoor=Blocks.OAK_TRAPDOOR.defaultBlockState();
            edit(helper,world,r,restore,at,trapdoor,fullReads);
            edit(helper,world,r,restore,at,trapdoor.setValue(BlockStateProperties.OPEN,true),fullReads);
            var slab=Blocks.OAK_SLAB.defaultBlockState();
            edit(helper,world,r,restore,at,slab,fullReads);
            edit(helper,world,r,restore,at,slab.setValue(BlockStateProperties.WATERLOGGED,true),fullReads);
            edit(helper,world,r,restore,at,Blocks.WATER.defaultBlockState(),fullReads);
            edit(helper,world,r,restore,at,Blocks.LAVA.defaultBlockState(),fullReads);
            edit(helper,world,r,restore,at,Blocks.STONE.defaultBlockState(),fullReads);
            edit(helper,world,r,restore,at,Blocks.OAK_PLANKS.defaultBlockState(),fullReads);
            edit(helper,world,r,restore,low.offset(-2,3,4),Blocks.STONE.defaultBlockState(),fullReads);
            edit(helper,world,r,restore,low.offset(4,3,10),Blocks.STONE.defaultBlockState(),fullReads);
            edit(helper,world,r,restore,low.offset(4,20,4),Blocks.STONE.defaultBlockState(),fullReads);
            edit(helper,world,r,restore,low.offset(4,20,4),Blocks.AIR.defaultBlockState(),fullReads);
            Meteodyne.LOGGER.info("Thermal geometry fixture: full {} sampled blocks; snow growth at most {} sampled blocks per update (500 coalesced notifications)",fullReads,maxSnowReads);
            helper.succeed();
        } finally { restore.forEach((p,s)->level.setBlock(p,s,3)); }
    }

    private static long edit(GameTestHelper helper, HeatWorld world, HeatRegion r, Map<BlockPos,BlockState> restore,
                             BlockPos at, BlockState state, long fullReads) {
        var level=helper.getLevel();
        restore.putIfAbsent(at,level.getBlockState(at));
        // Flag 2 prevents unrelated neighbor edits here; each explicitly sampled edit still includes shape neighbors.
        level.setBlock(at,state,2);
        var old=r.geometry;
        byte[] oldKinds=old.kinds().clone(), oldCover=old.cover().clone(), oldOpen=old.openFaces().clone();
        float[] oldCond=old.conductances().clone(), oldCap=old.capacities().clone();
        var update=new HeatGeometryUpdate(r,WindParams.DEFAULTS,false);
        // Hundreds of notifications for one affected location must remain one set of dirty lines.
        for (int repeat=0;repeat<500;repeat++) { update.dirty(at.getX(),at.getY(),at.getZ(),true); }
        drain(helper,update,level,3);
        var actual=update.finish();
        same(helper,actual,world.referenceGeometry(r));
        helper.assertTrue(update.blockReads<fullReads/4,"local edit samples less than a quarter of a full region: "+update.blockReads+" / "+fullReads);
        helper.assertTrue(Arrays.equals(oldKinds,old.kinds()) && Arrays.equals(oldCover,old.cover()) && Arrays.equals(oldOpen,old.openFaces())
                && Arrays.equals(oldCond,old.conductances()) && Arrays.equals(oldCap,old.capacities()),"previous worker snapshot remains immutable");
        r.geometry=actual;
        return update.blockReads;
    }

    @GameTest(template=GameTestStructures.EMPTY,batch="thermal_geometry_incremental")
    public static void changesDuringSlicedBuildAreReplayedBeforeCompletion(GameTestHelper helper) {
        var level=helper.getLevel();
        var world=HeatWorld.of(level);
        BlockPos low=helper.absolutePos(BlockPos.ZERO), high=low.offset(8,8,8), at=low.offset(4,2,4);
        var r=new HeatRegion(0,new int[]{low.getX(),low.getY(),low.getZ(),high.getX(),high.getY(),high.getZ()},AirParams.DEFAULT);
        var previous=level.getBlockState(at);
        try {
            var update=new HeatGeometryUpdate(r,WindParams.DEFAULTS,true);
            while (update.cellColumns<81) { helper.assertTrue(update.advance(level,1,Long.MAX_VALUE)==1,"one cell column per slice"); }
            update.advance(level,3,Long.MAX_VALUE);
            level.setBlock(at,Blocks.SNOW.defaultBlockState().setValue(BlockStateProperties.LAYERS,6),2);
            update.dirty(at.getX(),at.getY(),at.getZ(),true);
            boolean rejected=false;
            try { update.finish(); } catch (IllegalStateException expected) { rejected=true; }
            helper.assertTrue(rejected,"unfinished mixed-revision snapshot cannot be exported");
            drain(helper,update,level,2);
            same(helper,update.finish(),world.referenceGeometry(r));
            helper.assertTrue(update.cellColumns>81,"already sampled cells were reconciled after the edit");
            helper.succeed();
        } finally { level.setBlock(at,previous,3); }
    }

    @GameTest(template=GameTestStructures.EMPTY,batch="thermal_geometry_budget")
    public static void sharedWorkBudgetDefersPublicationAndAlternatesRegions(GameTestHelper helper) {
        var level=helper.getLevel();
        var world=HeatWorld.of(level);
        BlockPos a=helper.absolutePos(new BlockPos(3,1,3)), b=a.offset(40,0,0);
        var oldA=level.getBlockState(a);var oldB=level.getBlockState(b);
        world.setPaused(true);world.setTestArea(new int[]{a.getX()-1,a.getY()-1,a.getZ()-1,b.getX()+1,b.getY()+1,b.getZ()+1});
        world.forgetRegions();
        try {
            level.setBlock(a,Blocks.CAMPFIRE.defaultBlockState(),3);level.setBlock(b,Blocks.CAMPFIRE.defaultBlockState(),3);
            world.plan(level.getGameTime(),dev.brights0ng.meteodyne.rain.WeatherConfig.heat());
            helper.assertTrue(world.regions().size()==2,"two independent regions share the preparation allowance");
            helper.assertTrue(world.prepareSnapshots(256,0)==0,"expired budget does no snapshot work");
            helper.assertTrue(world.prepareSnapshots(1,Long.MAX_VALUE)==1,"first tick obeys shared work cap");
            helper.assertTrue(world.prepareSnapshots(1,Long.MAX_VALUE)==1,"second tick obeys shared work cap");
            for(var r:world.regions()) {
                helper.assertTrue(r.geometryUpdate!=null && r.geometryUpdate.cellColumns==1,"round-robin advances each region");
                helper.assertTrue(r.geometry==null && r.pendingGeometry==null && r.published==null,"partial arrays remain private");
            }
            world.onChunkLoad(level.getChunkSource().getChunkNow(a.getX()>>4,a.getZ()>>4));
            helper.assertTrue(world.regions().stream().anyMatch(r->r.geometryUpdate==null),"chunk availability supersedes an incomplete snapshot");
            world.prepareSnapshots(Integer.MAX_VALUE,Long.MAX_VALUE);
            for(var r:world.regions()) {
                helper.assertTrue(!r.geometryDirty && r.preparedGeometryRevision==r.geometryRevision,"completed snapshot reconciles input revision");
                same(helper,r.geometry,world.referenceGeometry(r));
            }
            world.runNow(1);
            helper.assertTrue(world.regions().stream().allMatch(r->r.published!=null),"normal thermal jobs resume after preparation");
            helper.succeed();
        } finally {
            level.setBlock(a,oldA,3);level.setBlock(b,oldB,3);
            world.forgetRegions();world.setTestArea(null);world.setPaused(false);
        }
    }

    private static void drain(GameTestHelper helper, HeatGeometryUpdate update, net.minecraft.server.level.ServerLevel level, int cap) {
        int slices=0;
        while (update.pending()) {
            int units=update.advance(level,cap,Long.MAX_VALUE);
            helper.assertTrue(units>0 && units<=cap,"bounded progress in each snapshot slice");
            helper.assertTrue(++slices<100_000,"bounded fixture completes");
        }
    }

    private static void same(GameTestHelper helper, HeatRegion.Geometry actual, HeatRegion.Geometry expected) {
        helper.assertTrue(Arrays.equals(actual.kinds(),expected.kinds()),"cell kinds match legacy full snapshot");
        helper.assertTrue(Arrays.equals(actual.openFaces(),expected.openFaces()),"open faces match legacy full snapshot");
        helper.assertTrue(Arrays.equals(actual.conductances(),expected.conductances()),"conductances match legacy full snapshot");
        helper.assertTrue(Arrays.equals(actual.capacities(),expected.capacities()),"capacities match legacy full snapshot");
        helper.assertTrue(Arrays.equals(actual.cover(),expected.cover()),"shelter lines match legacy full snapshot");
    }

    private HeatGeometryGameTests() { }
}
