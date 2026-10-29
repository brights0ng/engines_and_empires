// Written by ChatGPT.
// Updated by ChatGPT: player-scoped bounded visual and held thermometer trial.
// Updated by ChatGPT: displayed request startup phase and wait telemetry.
// Updated by ChatGPT: added explicit automatic view controls while preserving manual syntax.
// Updated by ChatGPT: routed trial status to the selected runtime and clarified allocation messages.
// Updated by Claude: added the scripted soak controls.
// Updated by Claude: raised the automatic total-cell ceiling to four 12-tile domains and named it a total in the usage text.
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
                .then(Commands.literal("auto").executes(c->automaticStatus(c.getSource()))
                        .then(Commands.literal("off").executes(c->{var w=world(c.getSource());if(w==null) return 0;w.stopAutomaticAir();
                            c.getSource().sendSuccess(()->Component.literal("Automatic trial stopped and its transient fields cleared."),false);return 1;}))
                        .then(Commands.literal("sample").then(Commands.argument("maxAgeTicks",IntegerArgumentType.integer(0,40))
                                .executes(c->automaticSample(c.getSource(),c.getSource().getPosition(),IntegerArgumentType.getInteger(c,"maxAgeTicks")))
                                .then(Commands.argument("position",Vec3Argument.vec3()).executes(c->automaticSample(c.getSource(),Vec3Argument.getVec3(c,"position"),IntegerArgumentType.getInteger(c,"maxAgeTicks"))))))
                        .then(Commands.argument("domains",IntegerArgumentType.integer(1,4))
                        .then(Commands.argument("totalCells",IntegerArgumentType.integer(4096,LocalAirAutomatic.MAX_CELLS))
                        .then(Commands.argument("coverageLimit",IntegerArgumentType.integer(0,EVIDENCE_CAP))
                        .then(Commands.argument("steps",IntegerArgumentType.integer(1,64)).executes(c->{
                            var w=world(c.getSource());if(w==null) return 0;
                            try { w.startAutomaticAir(new LocalAirAutomatic.Settings(IntegerArgumentType.getInteger(c,"domains"),
                                    IntegerArgumentType.getInteger(c,"totalCells"),IntegerArgumentType.getInteger(c,"coverageLimit"),IntegerArgumentType.getInteger(c,"steps"))); }
                            catch(RuntimeException e) { c.getSource().sendFailure(Component.literal(e.getMessage()));return 0; }
                            return automaticStatus(c.getSource());
                        }))))))
                .then(Commands.literal("plan").executes(c->planStatus(c.getSource()))
                        .then(Commands.literal("off").executes(c->{var w=world(c.getSource());if(w==null) return 0;
                            w.stopLocalAirPlan();c.getSource().sendSuccess(()->Component.literal("Automatic allocation planning off."),false);return 1;}))
                        .then(Commands.argument("radius",IntegerArgumentType.integer(0,32))
                        .then(Commands.argument("margin",IntegerArgumentType.integer(1,32))
                        .then(Commands.argument("totalTiles",IntegerArgumentType.integer(1,512))
                        .then(Commands.argument("componentTiles",IntegerArgumentType.integer(1,512)).executes(c->{
                            var w=world(c.getSource());if(w==null) return 0;
                            w.planLocalAir(new LocalAirPlanner.Settings(IntegerArgumentType.getInteger(c,"radius"),
                                    IntegerArgumentType.getInteger(c,"margin"),IntegerArgumentType.getInteger(c,"totalTiles"),
                                    IntegerArgumentType.getInteger(c,"componentTiles")));
                            return planStatus(c.getSource());
                        }))))))
                .then(Commands.literal("demands").executes(c->{
                    var world=world(c.getSource());if(world==null) return 0;
                    var snapshot=world.localAirDemands();
                    long active=snapshot.entries().stream().filter(LocalAirDemandRegistry.Entry::active).count();
                    c.getSource().sendSuccess(()->Component.literal("Local-air demand registry: "+active+" active owners, "+(snapshot.entries().size()-active)+" retained requests, "+snapshot.anchors().size()+" shared sample cells; revision "+snapshot.revision()+". Inspect allocation with air plan and the opt-in runtime with air auto."),false);
                    for(var kind:LocalAirDemandRegistry.Kind.values()) {
                        long count=snapshot.entries().stream().filter(e->e.active() && e.request().key().kind()==kind).count();
                        c.getSource().sendSuccess(()->Component.literal(kind+": "+count),false);
                    }
                    snapshot.anchors().stream().limit(16).forEach(a->c.getSource().sendSuccess(()->Component.literal("Demand: "+a),false));
                    if(snapshot.anchors().size()>16) c.getSource().sendSuccess(()->Component.literal("Showing first 16 cells; total "+snapshot.anchors().size()),false);
                    return 1;
                }))
                .then(Commands.literal("stop").executes(c->stop(c.getSource())))
                .then(Commands.literal("soak").requires(source->source.hasPermission(2))
                        .then(Commands.literal("build").executes(c->LocalAirSoak.build(c.getSource())))
                        .then(Commands.literal("start")
                                .then(Commands.literal("calm").executes(c->LocalAirSoak.start(c.getSource(),LocalAirSoak.Mode.CALM)))
                                .then(Commands.literal("busy").executes(c->LocalAirSoak.start(c.getSource(),LocalAirSoak.Mode.BUSY))))
                        .then(Commands.literal("stop").executes(c->LocalAirSoak.stop(c.getSource())))
                        .then(Commands.literal("status").executes(c->LocalAirSoak.status(c.getSource()))))
                .then(Commands.literal("view")
                        .then(Commands.literal("auto").then(Commands.argument("maxAgeTicks",IntegerArgumentType.integer(0,40))
                                .executes(c->view(c.getSource(),IntegerArgumentType.getInteger(c,"maxAgeTicks"),LocalAirView.Mode.AUTOMATIC))))
                        .then(Commands.literal("scale")
                                .executes(c->{ int spacing=LocalAirView.spacing(c.getSource().getPlayerOrException());c.getSource().sendSuccess(()->Component.literal("Local-air lattice spacing: "+spacing+" blocks (0 means view disabled)."),false);return 1; })
                                .then(Commands.argument("blocks",IntegerArgumentType.integer(1,32)).executes(c->{
                                    int blocks=IntegerArgumentType.getInteger(c,"blocks");
                                    boolean changed=LocalAirView.scale(c.getSource().getPlayerOrException(),blocks);
                                    if(changed) c.getSource().sendSuccess(()->Component.literal("Local-air view samples the full domain every "+blocks+" block(s). Simulation resolution is unchanged."),false);
                                    else c.getSource().sendFailure(Component.literal("Enable the view first with /meteodyne heat air view 40."));
                                    return changed?1:0;
                                })))
                        .then(Commands.literal("off").executes(c->{ LocalAirView.disable(c.getSource().getPlayerOrException());return 1; }))
                        .then(Commands.argument("maxAgeTicks",IntegerArgumentType.integer(0,40))
                                .executes(c->view(c.getSource(),IntegerArgumentType.getInteger(c,"maxAgeTicks"),LocalAirView.Mode.MANUAL))))
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
    private static int view(CommandSourceStack source,int maxAge,LocalAirView.Mode mode) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        boolean enabled=LocalAirView.enable(source.getPlayerOrException(),maxAge,mode);
        if(!enabled) {source.sendFailure(Component.literal("Local-air view needs the Overworld and a free viewer slot (maximum 4)."));return 0;}
        source.sendSuccess(()->Component.literal((mode==LocalAirView.Mode.AUTOMATIC?"Automatic":"Manual")+" local-air view and held thermometer trial on. Cubes show temperature relative to exterior weather; arrows show flow. Grey cubes are historical/unavailable. Stop with /meteodyne heat air view off."),false);
        var world=HeatWorld.of(source.getLevel());
        if(mode==LocalAirView.Mode.AUTOMATIC && world.automaticAir()==null)
            source.sendSuccess(()->Component.literal("Automatic runtime is off. Configure /meteodyne heat air plan and /meteodyne heat air auto to prepare player coverage. The view will wait without starting simulation work."),false);
        if(mode==LocalAirView.Mode.MANUAL && world.localAirStatus()==LocalAirPreparation.Status.CLOSED)
            source.sendSuccess(()->Component.literal("No manual domain is running. Use /meteodyne heat air live <minX> <minY> <minZ> <maxX> <maxY> <maxZ> <cellLimit> <coverageLimit> <steps>, or choose /meteodyne heat air view auto <maxAgeTicks> for automatic coverage."),false);
        return 1;
    }
    private static int planStatus(CommandSourceStack source) {
        var w=world(source);if(w==null) return 0;var p=w.localAirPlan();
        if(p==null) { source.sendSuccess(()->Component.literal("Planning off. Enable with /meteodyne heat air plan <radius> <margin> <totalTiles> <componentTiles>. These are experimental allocation limits."),false);return 1; }
        source.sendSuccess(()->Component.literal("Local-air plan at tick "+p.tick()+": demand revision "+p.demandRevision()+", layout revision "+p.layoutRevision()+"; "+p.proposedTiles()+" proposed 16-block tiles / "+p.proposedCells()+" cells, "+p.unsupported().size()+" unsupported requests. "+p.settings()+". "+p.failure()),false);
        source.sendSuccess(()->Component.literal("Planner proposals require runtime coverage admission. Inspect or enable the separate trial with air auto. Refreshes every 20 ticks."),false);
        p.groups().stream().limit(16).forEach(g->source.sendSuccess(()->Component.literal("Group "+g.key()+": "+g.state()+", "+g.tiles().size()+" tiles / "+g.cells()+" cells, "+g.requests().size()+" requests; missing chunks "+g.missingChunks().size()+" "+g.missingChunks().stream().limit(4).toList()),false));
        if(p.groups().size()>16) source.sendSuccess(()->Component.literal("Showing first 16 groups; total "+p.groups().size()),false);
        p.unsupported().stream().limit(4).forEach(r->source.sendSuccess(()->Component.literal("Unsupported: "+r.request().request().key()+" at "+r.request().request().cell()+": "+r.reason()),false));
        return 1;
    }
    private static int automaticStatus(CommandSourceStack source) {
        var w=world(source);if(w==null) return 0;var auto=w.automaticAir();
        if(auto==null) { source.sendSuccess(()->Component.literal("Automatic trial off. Configure air plan, then /meteodyne heat air auto <domains> <totalCells> <coverageLimit> <steps>. totalCells is shared by all owners; each owner is capped at the plan's componentTiles x 4096 cells."),false);return 1; }
        source.sendSuccess(()->Component.literal("Automatic trial: "+auto.settings+"; committed owners "+auto.committedDomains().size()+", total preparations "+auto.domains().size()+", reserved cells "+auto.cells()+"; layout "+auto.transferState()+", transfers "+auto.transfers()+". Capture, workers and wall budget are shared with existing heat work."),false);
        if(auto.lastTransfer()!=null) source.sendSuccess(()->Component.literal("Last layout transfer: "+auto.lastTransfer()),false);
        for(var row:auto.rows()) source.sendSuccess(()->Component.literal("Auto "+row),false);
        source.sendSuccess(()->Component.literal("Shared sky index: "+w.skyCache().report()),false);
        auto.decisions().stream().limit(16).forEach(d->source.sendSuccess(()->Component.literal("Admission "+d),false));
        if(auto.decisions().size()>16) source.sendSuccess(()->Component.literal("Showing first 16 admission decisions; total "+auto.decisions().size()),false);
        source.sendSuccess(()->Component.literal("OWNED means allocated; inspect preparation/run for readiness. Rejected cuts may expand within planner/runtime limits. Retained/target buffers count against the cell limit. Stop with air auto off."),false);
        return 1;
    }
    private static int automaticSample(CommandSourceStack source,Vec3 position,int maxAge) {
        var w=world(source);if(w==null) return 0;
        var reading=w.queryAutomaticLocalAir(position.x,position.y,position.z,maxAge);
        source.sendSuccess(()->Component.literal("Automatic local air: "+reading),false);return 1;
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
        var mode=source.getEntity() instanceof net.minecraft.server.level.ServerPlayer player?LocalAirView.mode(player):null;
        if(world.automaticAir()!=null && (mode==LocalAirView.Mode.AUTOMATIC
                || mode==null && world.localAirStatus()==LocalAirPreparation.Status.CLOSED)) return automaticStatus(source);
        var report=world.localAirReport();
        source.sendSuccess(()->Component.literal("Forcing: "+report.get("forcingMode")+"; last weather sample tick "+report.get("weatherSampleTick")+"."),false);
        if(report.containsKey("sampledWeather")) source.sendSuccess(()->Component.literal("Latest exterior sample: "+report.get("sampledWeather")),false);
        source.sendSuccess(()->Component.literal("Local air: preparation="+report.get("preparation")+", run="+report.get("run")+", physical job in flight="+report.get("physicalJobInFlight")+", attempts="+report.get("attempts")+". "+report.get("reason")),false);
        source.sendSuccess(()->Component.literal("Cold restarts: "+report.get("restarts")+"; last: "+report.get("lastRestart")),false);
        if(report.get("startup")!=null) source.sendSuccess(()->Component.literal("Request startup: "+report.get("startup")),false);
        if(report.get("invalidations") instanceof LocalAirDependencies.Diagnostics invalidations) {
            source.sendSuccess(()->Component.literal("Invalidation events by cause: "+invalidations.counts()+"; revision "+invalidations.revision()+". Events can coalesce into one restart; supported door transfers also increment the revision."),false);
            for(var event:invalidations.recent()) source.sendSuccess(()->Component.literal("Invalidation: "+event),false);
        }
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
