// Written by Claude.
package dev.brights0ng.meteodyne.heat.world;

import java.util.LinkedHashMap;
import java.util.Map;
import dev.brights0ng.meteodyne.Meteodyne;
import dev.brights0ng.paradynelib.gametest.GameTestStructures;
import dev.brights0ng.paradynelib.heat.air.ThermalGeometry;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.phys.shapes.BooleanOp;
import net.minecraft.world.phys.shapes.Shapes;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** Fence, wall and fence-gate collision is clipped to its own cell; doors and beds already fit theirs. */
@GameTestHolder(Meteodyne.MODID)
@PrefixGameTestTemplate(false)
public final class LocalAirFenceGameTests {
    private static final int FLAGS=Block.UPDATE_CLIENTS|Block.UPDATE_KNOWN_SHAPE;

    @GameTest(template=GameTestStructures.EMPTY,batch="local_air_fences")
    public static void fenceWallAndClosedGateCaptureAndCoverWithOpenAirAboveThePost(GameTestHelper helper) {
        var gate=Blocks.OAK_FENCE_GATE.defaultBlockState().setValue(BlockStateProperties.OPEN,false);
        for(var post:java.util.List.of(Blocks.OAK_FENCE.defaultBlockState(),Blocks.COBBLESTONE_WALL.defaultBlockState(),gate)) {
            var raw=post.getCollisionShape(EmptyBlockGetter.INSTANCE,BlockPos.ZERO);
            var clipped=LocalAirWorldSource.collisionShape(post,EmptyBlockGetter.INSTANCE,BlockPos.ZERO);
            helper.assertTrue(raw.max(Direction.Axis.Y)>1,post+" collision rises above its cell");
            helper.assertTrue(!clipped.isEmpty() && clipped.max(Direction.Axis.Y)<=1,post+" is clipped to its cell");
            enclosed(helper,Map.of(new BlockPos(2,1,2),post),(c,p)->{
                helper.assertTrue(!c.snapshot().shape(2,1,2).empty(),post+" keeps its in-cell post");
                helper.assertTrue(c.snapshot().shape(2,2,2).empty(),"The cell above "+post+" is open air");
                double solid=p.flow().solidVolume(p.flow().index(2,1,2));
                helper.assertTrue(solid>0 && solid<1,post+" occupies part of its cell, not "+solid);
                helper.assertTrue(p.flow().solidVolume(p.flow().index(2,2,2))==0,"Nothing of "+post+" reaches the cell above");
            });
        }
        helper.succeed();
    }
    @GameTest(template=GameTestStructures.EMPTY,batch="local_air_fences")
    public static void doorAndBedCaptureWithoutClipping(GameTestHelper helper) {
        var door=Blocks.OAK_DOOR.defaultBlockState();
        var bed=Blocks.RED_BED.defaultBlockState().setValue(BedBlock.FACING,Direction.EAST);
        var blocks=new LinkedHashMap<BlockPos,BlockState>();
        blocks.put(new BlockPos(1,1,1),door.setValue(BlockStateProperties.DOUBLE_BLOCK_HALF,DoubleBlockHalf.LOWER));
        blocks.put(new BlockPos(1,2,1),door.setValue(BlockStateProperties.DOUBLE_BLOCK_HALF,DoubleBlockHalf.UPPER));
        blocks.put(new BlockPos(2,1,3),bed.setValue(BedBlock.PART,BedPart.FOOT));
        blocks.put(new BlockPos(3,1,3),bed.setValue(BedBlock.PART,BedPart.HEAD));
        for(var state:blocks.values()) {
            var raw=state.getCollisionShape(EmptyBlockGetter.INSTANCE,BlockPos.ZERO);
            var read=LocalAirWorldSource.collisionShape(state,EmptyBlockGetter.INSTANCE,BlockPos.ZERO);
            helper.assertTrue(!Shapes.joinIsNotEmpty(raw,read,BooleanOp.NOT_SAME),state+" is read unclipped");
            helper.assertTrue(raw.min(Direction.Axis.Y)>=0 && raw.max(Direction.Axis.Y)<=1,state+" already fits its cell");
        }
        enclosed(helper,blocks,(c,p)->{
            for(var pos:blocks.keySet()) helper.assertTrue(!c.snapshot().shape(pos.getX(),pos.getY(),pos.getZ()).empty(),blocks.get(pos)+" is captured");
        });
        helper.succeed();
    }

    /** A 5x4x5 stone shell, so coverage needs no sky evidence; blocks are placed at shell-local positions. */
    private static void enclosed(GameTestHelper helper,Map<BlockPos,BlockState> blocks,
            java.util.function.BiConsumer<LocalAirGeometryCapture,LocalAirGeometryCapture.Prepared> check) {
        var level=helper.getLevel();var low=helper.absolutePos(new BlockPos(1,1,1));var restore=new LinkedHashMap<BlockPos,BlockState>();
        try {
            for(int z=0;z<5;z++) for(int y=0;y<4;y++) for(int x=0;x<5;x++) {
                var p=low.offset(x,y,z);restore.put(p,level.getBlockState(p));
                level.setBlock(p,(x==0 || y==0 || z==0 || x==4 || y==3 || z==4?Blocks.STONE:Blocks.AIR).defaultBlockState(),FLAGS);
            }
            blocks.forEach((pos,state)->level.setBlock(low.offset(pos),state,FLAGS));
            var bounds=new LocalAirGeometryCapture.Bounds(low.getX(),low.getY(),low.getZ(),5,4,5);
            var dependencies=new LocalAirDependencies(bounds);
            var source=new LocalAirWorldSource(level,dependencies::get,dependencies);
            var c=new LocalAirGeometryCapture(bounds,bounds.cells(),source);
            c.advance(bounds.cells(),Long.MAX_VALUE);
            helper.assertTrue(c.status()==LocalAirGeometryCapture.Status.CAPTURED,"Capture failed for "+blocks.values()+": "+c.reason());
            var coverage=new LocalAirCoverage(LocalAirCoverage.plan(c.snapshot()),source,10_000);
            coverage.advance(10_000,Long.MAX_VALUE);
            helper.assertTrue(coverage.status()==LocalAirCoverage.Status.ADMITTED,"Coverage failed for "+blocks.values()+": "+coverage.reason());
            check.accept(c,c.snapshot().bakeClosedControl(ThermalGeometry.Coefficients.legacyMaterials()));
        } finally {
            var order=new java.util.ArrayList<>(restore.keySet());order.sort((a,b)->Integer.compare(b.getY(),a.getY()));
            for(var pos:order) level.setBlock(pos,restore.get(pos),FLAGS);
        }
    }
    private LocalAirFenceGameTests() { }
}
