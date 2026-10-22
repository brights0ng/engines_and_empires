// Written by ChatGPT.
package dev.brights0ng.meteodyne.heat.world;

import java.util.Arrays;
import java.util.List;
import com.google.gson.JsonParser;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import dev.brights0ng.meteodyne.Meteodyne;
import dev.brights0ng.meteodyne.rain.WeatherConfig;
import dev.brights0ng.paradynelib.gametest.GameTestStructures;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@GameTestHolder(Meteodyne.MODID)
@PrefixGameTestTemplate(false)
public final class HeatResponseGameTests {
    @GameTest(template=GameTestStructures.EMPTY,batch="thermal_response")
    public static void commandReloadAndTelemetryScaleThermalTimeWithoutResettingState(GameTestHelper helper) throws Exception {
        var level=helper.getLevel();var world=HeatWorld.of(level);
        double config=WeatherConfig.HEAT_RESPONSE_SCALE.get();Double override=world.responseScaleOverride();
        var low=helper.absolutePos(BlockPos.ZERO);var high=helper.absolutePos(new BlockPos(6,6,6));
        BlockPos fire=new BlockPos(3,1,3),wall=new BlockPos(5,1,3);
        world.setPaused(true);world.setTestArea(new int[]{low.getX(),low.getY(),low.getZ(),high.getX(),high.getY(),high.getZ()});
        world.forgetRegions();
        try {
            WeatherConfig.HEAT_RESPONSE_SCALE.set(1.0);world.setResponseScaleOverride(null);
            helper.setBlock(fire,Blocks.CAMPFIRE);helper.setBlock(wall,Blocks.STONE);
            world.runNow(1);
            var r=world.regions().getFirst();
            var geometry=r.geometry;var sources=r.sources;
            double windX=r.windX,windZ=r.windZ;
            double near=WeatherConfig.heat().nearStep(),far=WeatherConfig.heat().farStep();
            long tick=level.getGameTime();
            Vec3 body=Vec3.atCenterOf(helper.absolutePos(fire)).add(1,1,0);
            double radiant=HeatRadiant.at(level,body);
            float[] initial=r.published.offsets().clone();
            r.grid.setOffsets(initial);world.runNow(2);
            float[] expected=r.published.offsets().clone();

            var dispatcher=level.getServer().getCommands().getDispatcher();
            var op=level.getServer().createCommandSourceStack().withLevel(level);
            var previousPublication=r.published;
            helper.assertTrue(dispatcher.execute("meteodyne heat response 2",op)==1,"operator can set response override");
            helper.assertTrue(world.responseScale()==2 && world.responseScaleOverride()==2,"override is effective");
            helper.assertTrue(r.published==previousPublication && r.geometry==geometry,"tuning preserves retained field and geometry");
            r.grid.setOffsets(initial);world.runNow(1);
            helper.assertTrue(Arrays.equals(expected,r.published.offsets()),"one second at 2x matches two seconds at 1x");

            WeatherConfig.HEAT_RESPONSE_SCALE.set(0.5);
            helper.assertTrue(world.responseScale()==2,"temporary override has precedence over reloaded config");
            helper.assertTrue(dispatcher.execute("meteodyne heat response reset",op)==1,"reset returns to config");
            helper.assertTrue(world.responseScale()==0.5 && world.responseScaleOverride()==null,"config response applies live");
            r.grid.setOffsets(initial);world.runNow(4);
            helper.assertTrue(Arrays.equals(expected,r.published.offsets()),"four seconds at 0.5x matches two seconds at 1x");
            helper.assertTrue(dispatcher.execute("meteodyne heat response",op)==1,"status command remains available");
            helper.assertTrue(!dispatcher.getRoot().getChild("meteodyne").canUse(op.withPermission(0)),"tuning requires operator permission");
            for(String invalid:new String[]{"0","-1","0.09","11","NaN","Infinity"}) {
                boolean rejected=false;
                try { dispatcher.execute("meteodyne heat response "+invalid,op); }
                catch(CommandSyntaxException expectedError) { rejected=true; }
                helper.assertTrue(rejected && world.responseScale()==0.5,"invalid response is rejected: "+invalid);
            }
            helper.assertTrue(r.sources.equals(sources),"source power and thermostat limits are unchanged");
            helper.assertTrue(r.windX==windX && r.windZ==windZ,"reported wind vector is not scaled");
            helper.assertTrue(WeatherConfig.heat().nearStep()==near && WeatherConfig.heat().farStep()==far,"publication cadence is unchanged");
            helper.assertTrue(level.getGameTime()==tick && HeatRadiant.at(level,body)==radiant,"game time and instantaneous radiation are unchanged");

            WeatherConfig.HEAT_RESPONSE_SCALE.set(1.0);world.setResponseScaleOverride(2.0);
            helper.assertTrue(world.benchmark().start(op,60,0),"capture starts");
            world.benchmark().tick(tick);world.runNow(3);world.benchmark().stop();
            var report=JsonParser.parseString(java.nio.file.Files.readString(world.benchmark().lastReport().resolve("summary.json"))).getAsJsonObject();
            helper.assertTrue(report.get("simulatedSecondsAcrossJobs").getAsDouble()==6,"capture records thermal seconds");
            helper.assertTrue(report.get("gameSecondsAcrossJobs").getAsDouble()==3,"capture separately records elapsed game seconds");
            helper.assertTrue(report.get("responseScaleAtEnd").getAsDouble()==2 && report.get("responseScaleMode").getAsString().equals("temporary_override"),"capture identifies tuning mode");
            helper.succeed();
        } finally {
            world.benchmark().stop();WeatherConfig.HEAT_RESPONSE_SCALE.set(config);world.setResponseScaleOverride(override);
            helper.setBlock(fire,Blocks.AIR);helper.setBlock(wall,Blocks.AIR);
            world.forgetRegions();world.setTestArea(null);world.setPaused(false);
        }
    }

    @GameTest(template=GameTestStructures.EMPTY,batch="thermal_response")
    public static void naturalArrivalEstimateIsIndependentOfResponseSpeed(GameTestHelper helper) {
        var level=helper.getLevel();var world=HeatWorld.of(level);
        Double override=world.responseScaleOverride();
        var low=helper.absolutePos(BlockPos.ZERO);var high=helper.absolutePos(new BlockPos(6,6,6));
        BlockPos magma=new BlockPos(3,1,3);
        world.setPaused(true);world.setTestArea(new int[]{low.getX(),low.getY(),low.getZ(),high.getX(),high.getY(),high.getZ()});
        world.setExtraAnchors(List.of(Vec3.atCenterOf(helper.absolutePos(magma))));world.forgetRegions();
        try {
            helper.setBlock(magma,Blocks.MAGMA_BLOCK);
            world.setResponseScaleOverride(1.0);world.runNow(0);
            var a=world.regions().getFirst();
            helper.assertTrue(a.naturalOnly && a.published.maxMagnitude()>0,"natural source is already warm on arrival");
            float[] expected=a.published.offsets().clone();
            world.forgetRegions();world.setResponseScaleOverride(10.0);world.runNow(0);
            helper.assertTrue(Arrays.equals(expected,world.regions().getFirst().published.offsets()),"response speed does not change natural initialization calibration");
            helper.succeed();
        } finally {
            world.setResponseScaleOverride(override);helper.setBlock(magma,Blocks.AIR);
            world.forgetRegions();world.setExtraAnchors(List.of());world.setTestArea(null);world.setPaused(false);
        }
    }

    private HeatResponseGameTests() { }
}
