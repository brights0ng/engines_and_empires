// Written by ChatGPT.
// Extended by ChatGPT: separate local-air stage wall/CPU/queue timings and field-quality snapshots.
// Updated by ChatGPT: measured numerical slice counts and maximum slice wall time.
package dev.brights0ng.meteodyne.heat.world;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import com.google.gson.GsonBuilder;
import dev.brights0ng.meteodyne.Meteodyne;
import dev.brights0ng.meteodyne.rain.WeatherConfig;
import dev.brights0ng.paradynelib.heat.air.ThermalTiming;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;

/** Opt-in live thermal capture. Server-thread ownership; worker measurements return through HeatWorld's Done queue. */
final class HeatBenchmark {
    private Session session;
    private Path lastReport;
    private static final class Session {
        final CommandSourceStack source;
        final long requestedTick, warmupTicks, durationTicks;
        final Map<String,ThermalTiming.Samples> metrics=new LinkedHashMap<>();
        final java.util.List<JobMeasurement> jobsRaw=new java.util.ArrayList<>();
        final Map<String,Long> invalidations=new LinkedHashMap<>();
        final Map<String,Long> blockChanges=new LinkedHashMap<>();
        final Map<String,Long> snapshotWork=new LinkedHashMap<>();
        boolean recording;
        long startedWall, jobs, cells, rebuiltJobs, settleJobs;
        double simulatedSeconds, gameSeconds;
        Session(CommandSourceStack source,long tick,int warmup,int seconds) {
            this.source=source;requestedTick=tick;warmupTicks=warmup*20L;durationTicks=seconds*20L;
        }
        void add(String metric,long value) { metrics.computeIfAbsent(metric,k->new ThermalTiming.Samples()).add(value); }
    }
    record Token(Session owner,long submitted) { }
    record JobMeasurement(Token token,long wall,long cpu,long allocated,long queue,long solve,long publication,
                          int cells,boolean rebuilt,boolean settled,double simulatedSeconds,
                          int regionId,long geometryRevision,long materialRevision,long sourceRevision,
                          double gameSeconds,double responseScale) { }

    /** Capture-only, bounded state-pair diagnostics; counts are events, not additive timings or unique rebuilds. */
    void blockChanged(net.minecraft.world.level.block.state.BlockState before,
                      net.minecraft.world.level.block.state.BlockState after, HeatTables.Change change) {
        if (session==null || !session.recording) { return; }
        String kind=change.geometry()?"geometry":change.material()?"material":"unchanged_snapshot_inputs";
        String key=kind+": "+(before==null?"unknown/deferred":before.toString())+" -> "+after;
        if (!session.blockChanges.containsKey(key) && session.blockChanges.size()>=128) { key="other_state_pairs"; }
        session.blockChanges.merge(key,1L,Long::sum);
    }
    void invalidated(String cause) {
        if (session!=null && session.recording) { session.invalidations.merge(cause,1L,Long::sum); }
    }
    void sample(String metric,long nanos) {
        if (session!=null && session.recording) { session.add(metric,nanos); }
    }
    void work(String metric,long count) {
        if (session!=null && session.recording && count>0) { session.snapshotWork.merge(metric,count,Long::sum); }
    }
    boolean start(CommandSourceStack source,int seconds,int warmup) {
        if (session!=null) { source.sendFailure(Component.literal("A thermal benchmark capture is already active."));return false; }
        if (seconds<1 || seconds>300 || warmup<0 || warmup>60) { throw new IllegalArgumentException("Invalid capture duration"); }
        session=new Session(source,source.getLevel().getGameTime(),warmup,seconds);
        source.sendSuccess(()->Component.literal("Thermal benchmark: "+warmup+"s warmup, then "+seconds+"s capture. Keep the same scene loaded; heat view off."),false);
        return true;
    }
    void tick(long now) {
        Session s=session;
        if (s==null) { return; }
        long elapsed=now-s.requestedTick;
        if (!s.recording && elapsed>=s.warmupTicks) { s.recording=true;s.startedWall=System.nanoTime(); }
        if (elapsed>=s.warmupTicks+s.durationTicks) { finish(false); }
    }
    long begin() { return session!=null && session.recording?System.nanoTime():0; }
    void end(String phase,long start) { if (start!=0 && session!=null && session.recording) { session.add(phase,System.nanoTime()-start); } }
    Token submit() { return session!=null && session.recording?new Token(session,System.nanoTime()):null; }
    void completed(JobMeasurement m) {
        if (m==null || session!=m.token.owner || !session.recording) { return; }
        Session s=session;
        s.jobsRaw.add(m);
        s.add("worker_wall_ms",m.wall);s.add("worker_cpu_ms",m.cpu);s.add("worker_allocated_bytes",m.allocated);
        s.add("queue_wait_ms",m.queue);s.add("worker_solve_ms",m.solve);s.add("worker_publication_ms",m.publication);
        s.add(m.settled?"worker_settle_wall_ms":m.rebuilt?"worker_rebuild_wall_ms":"worker_clean_wall_ms",m.wall);
        s.simulatedSeconds+=m.simulatedSeconds;
        s.gameSeconds+=m.gameSeconds;
        s.jobs++;s.cells+=m.cells;if(m.rebuilt){s.rebuiltJobs++;}if(m.settled){s.settleJobs++;}
    }
    void localAirCompleted(Token token,LocalAirMetrics.Timing timing,boolean failed) {
        if(token==null || timing==null || session!=token.owner || !session.recording) return;
        String prefix="local_air_"+timing.stage().name().toLowerCase(Locale.ROOT);
        session.add(prefix+"_wall_ms",timing.wallNanos());session.add(prefix+"_queue_ms",timing.queueNanos());
        if(timing.cpuNanos()>=0) session.add(prefix+"_cpu_ms",timing.cpuNanos());
          session.snapshotWork.merge(prefix+"_jobs",1L,Long::sum);
          session.snapshotWork.merge(prefix+"_slices",(long)timing.slices(),Long::sum);
          session.add(prefix+"_max_slice_wall_ms",timing.maxSliceNanos());
        if(failed) session.snapshotWork.merge(prefix+"_failed_jobs",1L,Long::sum);
    }
    Path lastReport() { return lastReport; }
    String status() {
        if (session==null) { return lastReport==null?"No thermal capture active.":"Last thermal report: "+lastReport; }
        return "Thermal benchmark "+(session.recording?"recording":"warming up")+"; completed measured jobs: "+session.jobs;
    }
    boolean stop() { if(session==null){return false;}finish(true);return true; }
    private void finish(boolean stopped) {
        Session s=session;session=null;
        try {
            String stamp=DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS").withZone(ZoneOffset.UTC).format(Instant.now());
            Path base=Path.of(System.getProperty("meteodyne.thermalBenchDir","../build/benchmarks/thermal")).toAbsolutePath().normalize();
            Path directory=base.resolve("live-"+stamp);Files.createDirectories(directory);
            double seconds=s.recording?(System.nanoTime()-s.startedWall)/1e9:0;
            Map<String,Object> report=new LinkedHashMap<>();
            report.put("writtenBy","ChatGPT");report.put("schema","thermal-live-v3");report.put("timeUtc",Instant.now().toString());
            report.put("elapsedWallSeconds",seconds);report.put("requestedGameSeconds",s.durationTicks/20);report.put("stoppedEarly",stopped);
            report.put("warmupGameSeconds",s.warmupTicks/20);report.put("java",System.getProperty("java.runtime.version"));
            report.put("processors",Runtime.getRuntime().availableProcessors());report.put("heatSettings",WeatherConfig.heat().toString());
            report.put("dimension",s.source.getLevel().dimension().location().toString());report.put("position",s.source.getPosition().toString());
            report.put("simulatedSecondsAcrossJobs",s.simulatedSeconds);
            report.put("gameSecondsAcrossJobs",s.gameSeconds);
            report.put("thermalTimeScope","simulatedSecondsAcrossJobs and jobs.csv simulated_seconds are thermal seconds after response scaling, excluding natural initialization. gameSecondsAcrossJobs and game_seconds are unscaled elapsed game time. response_scale_at_dispatch is the selected rate, not necessarily the interval-average rate if it changed while a region waited.");
            report.put("completedMeasuredJobs",s.jobs);report.put("processedCells",s.cells);report.put("geometryRebuildJobs",s.rebuiltJobs);report.put("settleJobs",s.settleJobs);
            report.put("invalidationEvents",s.invalidations);report.put("blockStateChanges",s.blockChanges);
            report.put("snapshotWork",s.snapshotWork);
            report.put("snapshotScope","snapshot_prepare_ms is per-tick shared preparation, outside planning_ms but inside thermal_tick_ms. snapshot_slice_ms is one region's batch of at most eight lines/columns. geometry_snapshot_ms, geometry_incremental_ms and material_snapshot_ms sum active slice time for a completed update, excluding time waiting between ticks. Completed updates can overlap capture boundaries. Live preparation targets 2 ms and caps 256 line/column units per tick across all regions; atomic units, allocation, source refresh and scheduling can exceed the soft time target.");
            report.put("invalidationScope","Region invalidations coalesce before snapshots; source changes count prepared source-list changes. Block state pairs count relevant hook events once, capped at 128 distinct pairs plus overflow. No event history is retained outside a capture.");
            report.put("scope","Heat pipeline, including explicitly active local-air diagnostics. thermal_tick_ms contains planning_ms, legacy snapshot_prepare_ms, and local_air_snapshot_advance_ms; these timings overlap and must not be added. local_air_<stage>_wall_ms/cpu_ms/queue_ms require dispatch and collection within this capture; unfinished tail jobs are excluded. local_air_worker_ms is the broader collect-time series and may include jobs dispatched earlier. Legacy worker_* metrics and raw jobs remain legacy-only. This does not measure whole-server MSPT or client FPS.");
            var world=HeatWorld.of(s.source.getLevel());
            if (world!=null) {
                report.put("localAirAtEnd",world.localAirReport());
                report.put("responseScaleAtEnd",world.responseScale());
                report.put("responseScaleMode",world.responseScaleOverride()==null?"config":"temporary_override");
            }
            var regions=world==null?java.util.List.<HeatRegion>of():world.regions();
            report.put("regionsAtEnd",regions.stream().map(r->{
                Map<String,Object> region=new LinkedHashMap<>();region.put("id",r.id());region.put("box",r.box());region.put("sources",r.sourceCount());
                region.put("windMps",java.util.List.of(r.windX,r.windZ));region.put("naturalOnly",r.naturalOnly());
                region.put("geometryRevision",r.geometryRevision);region.put("materialRevision",r.materialRevision);
                region.put("sourceRevision",r.sourceRevision);region.put("preparedGeometryRevision",r.preparedGeometryRevision);
                region.put("preparedMaterialRevision",r.preparedMaterialRevision);
                region.put("snapshotPending",r.geometryDirty || r.materialDirty || r.geometry==null);
                region.put("ship",r.ship()!=null);region.put("publishedAgeTicks",r.published()==null?-1:s.source.getLevel().getGameTime()-r.published().gameTime());
                region.put("inFlight",r.inFlight);return region;
            }).toList());
            Map<String,Object> metrics=new LinkedHashMap<>();
            StringBuilder csv=new StringBuilder("metric,count,mean,p50,p95,p99,max,total\n");
            for(var entry:s.metrics.entrySet()) {
                var summary=entry.getValue().summary(entry.getKey().endsWith("_bytes")?1:1e6);metrics.put(entry.getKey(),summary);
                csv.append(String.format(Locale.ROOT,"%s,%d,%.6f,%.6f,%.6f,%.6f,%.6f,%.6f%n",entry.getKey(),summary.count(),summary.mean(),summary.p50(),summary.p95(),summary.p99(),summary.max(),summary.total()));
            }
            report.put("metrics",metrics);
            report.put("workerCpuCoreFraction",s.metrics.containsKey("worker_cpu_ms") && s.metrics.get("worker_cpu_ms").summary(1e6).count()>0 && seconds>0
                    ?s.metrics.get("worker_cpu_ms").summary(1e6).total()/(seconds*1000):null);
            Files.writeString(directory.resolve("summary.json"),new GsonBuilder().setPrettyPrinting().create().toJson(report));
            Files.writeString(directory.resolve("summary.csv"),csv);
            StringBuilder jobsCsv=new StringBuilder("cells,simulated_seconds,rebuilt,settled,wall_ms,cpu_ms,allocated_bytes,queue_ms,solve_ms,publication_ms,region_id,geometry_revision,material_revision,source_revision,game_seconds,response_scale_at_dispatch\n");
            for(var job:s.jobsRaw) {
                jobsCsv.append(String.format(Locale.ROOT,"%d,%.3f,%s,%s,%.6f,%s,%s,%.6f,%.6f,%.6f,%d,%d,%d,%d,%.3f,%.3f%n",job.cells,job.simulatedSeconds,job.rebuilt,job.settled,
                        job.wall/1e6,job.cpu<0?"":Double.toString(job.cpu/1e6),job.allocated<0?"":Long.toString(job.allocated),job.queue/1e6,job.solve/1e6,job.publication/1e6,
                        job.regionId,job.geometryRevision,job.materialRevision,job.sourceRevision,job.gameSeconds,job.responseScale));
            }
            Files.writeString(directory.resolve("jobs.csv"),jobsCsv);
            lastReport=directory;
            if(s.jobs==0) { s.source.sendFailure(Component.literal("No thermal jobs captured. Stand near an active heat source and repeat; this is not a solver baseline.")); }
            s.source.sendSuccess(()->Component.literal("Thermal capture saved: "+directory+" ("+s.jobs+" jobs)."),false);
            Meteodyne.LOGGER.info("Thermal benchmark saved to {} ({} jobs, {} wall seconds)",directory,s.jobs,seconds);
        } catch(Exception e) {
            Meteodyne.LOGGER.error("Could not write thermal benchmark",e);
            s.source.sendFailure(Component.literal("Could not save thermal benchmark: "+e.getMessage()));
        }
    }
}
