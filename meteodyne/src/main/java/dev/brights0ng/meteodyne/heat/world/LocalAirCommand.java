// Written by ChatGPT.
package dev.brights0ng.meteodyne.heat.world;

import java.util.Locale;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.coordinates.BlockPosArgument;
import net.minecraft.commands.arguments.coordinates.Vec3Argument;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.Vec3;

/** Operator-only experimental controls with explicit fixed/live forcing and bounded admission. */
final class LocalAirCommand {
    // Hard operator-entry guards, not selected production radii or solver defaults.
    static final int CELL_CAP=32_768,EVIDENCE_CAP=1_000_000;
    static LiteralArgumentBuilder<CommandSourceStack> tree() {
        return Commands.literal("air").requires(source->source.hasPermission(2))
                .executes(c->status(c.getSource()))
                .then(Commands.literal("status").executes(c->status(c.getSource())))
                .then(Commands.literal("stop").executes(c->stop(c.getSource())))
                .then(Commands.literal("sample").executes(c->sample(c.getSource(),c.getSource().getPosition()))
                        .then(Commands.argument("position",Vec3Argument.vec3()).executes(c->sample(c.getSource(),Vec3Argument.getVec3(c,"position")))))
                .then(Commands.literal("live")
                        .then(Commands.argument("min",BlockPosArgument.blockPos())
                        .then(Commands.argument("max",BlockPosArgument.blockPos())
                        .then(Commands.argument("cellLimit",IntegerArgumentType.integer(1,CELL_CAP))
                        .then(Commands.argument("coverageLimit",IntegerArgumentType.integer(0,EVIDENCE_CAP))
                        .then(Commands.argument("steps",IntegerArgumentType.integer(1,64)).executes(LocalAirCommand::live)))))))
                .then(Commands.literal("start")
                        .then(Commands.argument("min",BlockPosArgument.blockPos())
                        .then(Commands.argument("max",BlockPosArgument.blockPos())
                        .then(Commands.argument("ambient",DoubleArgumentType.doubleArg(-273.149,3000))
                        .then(Commands.argument("windX",DoubleArgumentType.doubleArg(-100,100))
                        .then(Commands.argument("windZ",DoubleArgumentType.doubleArg(-100,100))
                        .then(Commands.argument("cellLimit",IntegerArgumentType.integer(1,CELL_CAP))
                        .then(Commands.argument("coverageLimit",IntegerArgumentType.integer(0,EVIDENCE_CAP))
                        .then(Commands.argument("steps",IntegerArgumentType.integer(1,64)).executes(LocalAirCommand::start))))))))));
    }
    private static HeatWorld world(CommandSourceStack source) {
        var world=HeatWorld.of(source.getLevel());
        if(world==null) source.sendFailure(Component.literal("Local air diagnostics run in the Overworld only."));
        return world;
    }
    private static int start(CommandContext<CommandSourceStack> c) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        var source=c.getSource();var world=world(source);if(world==null) return 0;
        if(!dev.brights0ng.meteodyne.rain.WeatherConfig.heat().enabled()) {
            source.sendFailure(Component.literal("Enable the heat simulation before starting a local-air diagnostic."));return 0;
        }
        try {
            var bounds=bounds(BlockPosArgument.getBlockPos(c,"min"),BlockPosArgument.getBlockPos(c,"max"));
            if(bounds.y()<source.getLevel().getMinBuildHeight() || (long)bounds.y()+bounds.ny()>source.getLevel().getMaxBuildHeight())
                throw new IllegalArgumentException("Diagnostic bounds must lie within world build height");
            world.requestLocalAirSimulation(bounds,IntegerArgumentType.getInteger(c,"cellLimit"),IntegerArgumentType.getInteger(c,"coverageLimit"),
                    new LocalAirRun.Settings(DoubleArgumentType.getDouble(c,"ambient"),DoubleArgumentType.getDouble(c,"windX"),DoubleArgumentType.getDouble(c,"windZ"),IntegerArgumentType.getInteger(c,"steps")));
            source.sendSuccess(()->Component.literal("Fixed-weather local-air diagnostic requested. Check /meteodyne heat air status; stop with /meteodyne heat air stop."),false);
            return 1;
        } catch(IllegalArgumentException | ArithmeticException error) {
            source.sendFailure(Component.literal("Local-air request rejected: "+error.getMessage()));return 0;
        }
    }
    private static int live(CommandContext<CommandSourceStack> c) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        var source=c.getSource();var world=world(source);if(world==null) return 0;
        if(!dev.brights0ng.meteodyne.rain.WeatherConfig.heat().enabled()) {
            source.sendFailure(Component.literal("Enable the heat simulation before starting a local-air diagnostic."));return 0;
        }
        try {
            var b=bounds(BlockPosArgument.getBlockPos(c,"min"),BlockPosArgument.getBlockPos(c,"max"));
            if(b.y()<source.getLevel().getMinBuildHeight() || (long)b.y()+b.ny()>source.getLevel().getMaxBuildHeight())
                throw new IllegalArgumentException("Diagnostic bounds must lie within world build height");
            world.requestLiveLocalAirSimulation(b,IntegerArgumentType.getInteger(c,"cellLimit"),IntegerArgumentType.getInteger(c,"coverageLimit"),IntegerArgumentType.getInteger(c,"steps"));
            source.sendSuccess(()->Component.literal("Live local-air diagnostic requested: uniform exterior climate and surface weather wind at domain center, sampled every 10 ticks."),false);return 1;
        } catch(IllegalArgumentException | ArithmeticException error) {
            source.sendFailure(Component.literal("Live local-air request rejected: "+error.getMessage()));return 0;
        }
    }
    static LocalAirGeometryCapture.Bounds bounds(BlockPos low,BlockPos high) {
        var bounds=new LocalAirGeometryCapture.Bounds(low.getX(),low.getY(),low.getZ(),
                Math.addExact(Math.subtractExact(high.getX(),low.getX()),1),Math.addExact(Math.subtractExact(high.getY(),low.getY()),1),Math.addExact(Math.subtractExact(high.getZ(),low.getZ()),1));
        if(bounds.cells()>CELL_CAP) throw new IllegalArgumentException("Operator diagnostic exceeds "+CELL_CAP+" cells");
        return bounds;
    }
    private static int status(CommandSourceStack source) {
        var world=world(source);if(world==null) return 0;
        var report=world.localAirReport();
        source.sendSuccess(()->Component.literal("Forcing: "+report.get("forcingMode")+"; last weather sample tick "+report.get("weatherSampleTick")+"."),false);
        if(report.containsKey("sampledWeather")) source.sendSuccess(()->Component.literal("Latest exterior sample: "+report.get("sampledWeather")),false);
        source.sendSuccess(()->Component.literal("Local air: preparation="+report.get("preparation")+", run="+report.get("run")+", physical job in flight="+report.get("physicalJobInFlight")+", attempts="+report.get("attempts")+". "+report.get("reason")),false);
        source.sendSuccess(()->Component.literal(LocalAirDiagnostics.field(world.localAirField(),source.getLevel().getGameTime())),false);
        source.sendSuccess(()->Component.literal(String.format(Locale.ROOT,"Snapshot advance %.3f ms, %s charged cell units; shared budget balance %.3f ms.",
                report.get("snapshotAdvanceMs"),report.get("snapshotChargedUnits"),report.get("sharedBudgetMs"))),false);
        world.localAirPreparationMetrics().forEach((stage,row)->source.sendSuccess(()->Component.literal(String.format(Locale.ROOT,
                "%s: %d jobs, %d failed, %d discarded; wall %.3f ms (max %.3f), queue %.3f ms (max %.3f); CPU %s over %d measured jobs.",stage,row.jobs(),row.failed(),row.discarded(),row.wallNanos()/1e6,row.maxWallNanos()/1e6,
                row.queueNanos()/1e6,row.maxQueueNanos()/1e6,row.cpuSamples()==0?"unavailable":String.format(Locale.ROOT,"%.3f ms",row.cpuNanos()/1e6),row.cpuSamples())),false));
        return 1;
    }
    private static int stop(CommandSourceStack source) {
        var world=world(source);if(world==null) return 0;world.clearLocalAirGeometry();
        source.sendSuccess(()->Component.literal("Local-air diagnostic stopped. Retired work retains its slot until collected."),false);return 1;
    }
    private static int sample(CommandSourceStack source,Vec3 at) {
        var world=world(source);if(world==null) return 0;
        var sample=LocalAirDiagnostics.sample(world.localAirField(),source.getLevel().getGameTime(),at.x,at.y,at.z);
        String text=sample.availability()!=LocalAirDiagnostics.Availability.AIR?"Local air sample: "+sample.availability()+"; no air reading."
                :String.format(Locale.ROOT,"Diagnostic air at %.3f %.3f %.3f: %.3f C; velocity (x,y,z)=(%.4f, %.4f, %.4f) blocks/s; frame tick %d, age %d ticks.",
                        at.x,at.y,at.z,sample.temperature(),sample.x(),sample.y(),sample.z(),sample.throughTick(),sample.ageTicks());
        source.sendSuccess(()->Component.literal(text),false);return 1;
    }
}
