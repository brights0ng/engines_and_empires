// Written by ChatGPT.
package dev.brights0ng.meteodyne.heat.world;

import com.google.gson.JsonParser;
import dev.brights0ng.meteodyne.Meteodyne;
import dev.brights0ng.paradynelib.gametest.GameTestStructures;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** Exercises real snapshot/solver/publication instrumentation and valid report output without timing thresholds. */
@GameTestHolder(Meteodyne.MODID)
@PrefixGameTestTemplate(false)
public final class HeatBenchmarkGameTests {
    @GameTest(template=GameTestStructures.EMPTY,batch="thermal_benchmark_capture")
    public static void captureRecordsActualThermalJobsAndExportsReports(GameTestHelper helper) {
        var level=helper.getLevel();var world=HeatWorld.of(level);
        BlockPos low=helper.absolutePos(BlockPos.ZERO),high=helper.absolutePos(new BlockPos(6,6,6));
        world.setPaused(true);world.setTestArea(new int[]{low.getX(),low.getY(),low.getZ(),high.getX(),high.getY(),high.getZ()});
        world.forgetRegions();
        try {
            helper.setBlock(new BlockPos(3,1,3),Blocks.CAMPFIRE);
            world.runNow(1);
            var source=level.getServer().createCommandSourceStack().withLevel(level);
            helper.assertTrue(world.benchmark().start(source,1,0),"capture starts");
            helper.assertTrue(!world.benchmark().start(source,1,0),"a second capture cannot replace an active baseline");
            world.benchmark().tick(level.getGameTime());
            helper.setBlock(new BlockPos(4,1,3),Blocks.STONE);
            world.runNow(1);
            helper.assertTrue(world.benchmark().stop(),"capture stops and writes reports");
            var path=world.benchmark().lastReport();
            helper.assertTrue(path!=null && java.nio.file.Files.exists(path.resolve("summary.csv")),"CSV report exists");
            helper.assertTrue(java.nio.file.Files.readAllLines(path.resolve("jobs.csv")).size()>1,"raw worker jobs are retained");
            var json=JsonParser.parseString(java.nio.file.Files.readString(path.resolve("summary.json"))).getAsJsonObject();
            helper.assertTrue(json.get("completedMeasuredJobs").getAsLong()>0,"real jobs were measured");
            var metrics=json.getAsJsonObject("metrics");
            for(String name:new String[]{"planning_ms","geometry_incremental_ms","cover_incremental_ms","snapshot_prepare_ms","snapshot_slice_ms","worker_wall_ms","worker_solve_ms","worker_publication_ms","queue_wait_ms"}) {
                helper.assertTrue(metrics.has(name) && metrics.getAsJsonObject(name).get("count").getAsInt()>0,"captured "+name);
            }
            helper.assertTrue(json.get("geometryRebuildJobs").getAsLong()>0,"dirty geometry is identified");
            helper.assertTrue(!world.benchmark().stop(),"stopping an inactive capture is harmless");
            helper.assertTrue(world.regions().stream().anyMatch(r->r.published()!=null && r.published().maxMagnitude()>0),"profiling retains normal heat output");
            helper.succeed();
        } catch(java.io.IOException error) { throw new IllegalStateException(error); }
        finally {
            world.benchmark().stop();
            helper.setBlock(new BlockPos(3,1,3),Blocks.AIR);helper.setBlock(new BlockPos(4,1,3),Blocks.AIR);
            world.forgetRegions();world.setTestArea(null);world.setPaused(false);
        }
    }
    private HeatBenchmarkGameTests() { }
}
