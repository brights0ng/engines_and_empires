// Written by ChatGPT.
package dev.brights0ng.meteodyne.heat.world;

import dev.brights0ng.paradynelib.heat.air.ThermalGeometry;
import static dev.brights0ng.meteodyne.heat.world.LocalAirGeometryCapture.*;

/** One explicitly requested diagnostic domain, driven by HeatWorld's existing budgets and pool.
 * Optional coupled diagnostic with chronological weather and verified source updates; no worker world access or production routing.
 */
final class LocalAirPreparation {
    enum Status { CAPTURING, PLAN_QUEUED, PLANNING, COVERAGE, BAKE_QUEUED, BAKING, PREPARED, REJECTED, FAILED, CLOSED }
    static final class Work {
        private final Object ticket;
        private final Snapshot snapshot;
        private final LocalAirGeometryHandoff.Job bake;
        private final LocalAirRun.Work simulation;
        private final long submitted=System.nanoTime();
        private Work(Stage stage,Snapshot snapshot,LocalAirGeometryHandoff.Job bake,LocalAirRun.Work simulation) {
            this.ticket=stage.ticket;this.snapshot=snapshot;this.bake=bake;this.simulation=simulation;
        }
        Result run() {
            long start=System.nanoTime();
            long cpu=dev.brights0ng.paradynelib.heat.air.ThermalTiming.cpu();
            try {
                if(simulation!=null) return finish(null,null,simulation.run(),start,cpu,null);
                var plan=bake==null?LocalAirCoverage.plan(snapshot):null;
                var completion=bake==null?null:bake.bake(ThermalGeometry.Coefficients.legacyMaterials());
                return finish(plan,completion,null,start,cpu,null);
            } catch(Throwable error) { return finish(null,null,null,start,cpu,error); }
        }
        private Result finish(LocalAirCoverage.Plan plan,LocalAirGeometryHandoff.Completion completion,LocalAirRun.Result run,long start,long cpu,Throwable error) {
            long nanos=System.nanoTime()-start;
            var stage=simulation!=null?(simulation.initialization()?LocalAirMetrics.Stage.INITIALIZE:LocalAirMetrics.Stage.STEP)
                    :bake==null?LocalAirMetrics.Stage.PLAN:LocalAirMetrics.Stage.BAKE;
            var timing=new LocalAirMetrics.Timing(stage,Math.max(0,start-submitted),nanos,
                    dev.brights0ng.paradynelib.heat.air.ThermalTiming.difference(cpu,dev.brights0ng.paradynelib.heat.air.ThermalTiming.cpu()));
            return new Result(this,plan,completion,run,nanos,error,timing);
        }
    }
    record Result(Work work,LocalAirCoverage.Plan plan,LocalAirGeometryHandoff.Completion completion,LocalAirRun.Result simulation,long nanos,Throwable error,LocalAirMetrics.Timing timing) {
        Result(Work work,LocalAirCoverage.Plan plan,LocalAirGeometryHandoff.Completion completion,long nanos,Throwable error) {
            this(work,plan,completion,null,nanos,error,null);
        }
        boolean failed() { return error!=null || simulation!=null && simulation.failed(); }
    }
    static final int MAX_TOPOLOGIES=16,MAX_HISTORY_EVENTS=256;
    private record SourceEdit(long tick,int cell,Heat heat,double scale) { }
    /** Each historical stage is read/baked/stepped only when it reaches the head of the queue. */
    private static final class Stage {
        final Object ticket=new Object(); // Work carries this opaque identity, never the world-bearing Stage.
        final LocalAirCoverage.World world;
        final LocalAirCoverage.History history;
        final LocalAirGeometryHandoff handoff;
        final java.util.ArrayList<SourceEdit> sources=new java.util.ArrayList<>();
        final java.util.HashSet<Long> edits=new java.util.HashSet<>(),sourceDirty=new java.util.HashSet<>();
        LocalAirGeometryCapture capture;
        Snapshot snapshot;
        LocalAirCoverage coverage;
        LocalAirCoverage.Certificate certificate,incomingCoverage;
        LocalAirGeometryHandoff.Job bake;
        LocalAirRun run;
        LocalAirRun.Seed incoming;
        Status status=Status.CAPTURING;
        String reason="";
        long startTick=-1,replayedTick=-1,endTick=-1;
        double startScale;
        int appliedSources;
        long appliedWeather;
        long endWeatherRevision=Long.MAX_VALUE;
        boolean sealed;
        Stage(LocalAirCoverage.World world,LocalAirCoverage.History history) {
            this.world=world;this.history=history;handoff=new LocalAirGeometryHandoff(world);
        }
        void close() {
            if(run!=null) run.close();
            if(history!=null) history.close();
            handoff.invalidate();capture=null;snapshot=null;incoming=null;certificate=null;incomingCoverage=null;
        }
    }
    private final Thread owner=Thread.currentThread();
    private final LocalAirCoverage.World world;
    private final Bounds bounds;
    private final int cellLimit,coverageLimit;
    private final LocalAirRun.Settings runSettings;
    private final java.util.ArrayDeque<Stage> stages=new java.util.ArrayDeque<>();
    private final dev.brights0ng.paradynelib.heat.air.ThermalClock clock=
            new dev.brights0ng.paradynelib.heat.air.ThermalClock(LocalAirRun.MAX_LAG_TICKS);
    private final LocalAirMetrics metrics=new LocalAirMetrics();
    private Work outstanding;
    private long generation,context,geometry,material,now=-1;
    private long captureReadCells,captureReusedCells;
    private long coverageReadCells,coverageReusedCells;
    private int minY,maxY,historyEvents;
    private double scaleNow;
    private boolean closed,failed;
    private String failure="";
    private final java.util.TreeMap<Long,LocalAirRun.WeatherChange> weatherHistory=new java.util.TreeMap<>();
    private long weatherRevision;
    private LocalAirRun.Weather latestWeather;

    LocalAirPreparation(LocalAirCoverage.World world,Bounds bounds,int cellLimit,int coverageLimit) {
        this(world,bounds,cellLimit,coverageLimit,null);
    }
    LocalAirPreparation(LocalAirCoverage.World world,Bounds bounds,int cellLimit,int coverageLimit,LocalAirRun.Settings runSettings) {
        if(coverageLimit<0 || bounds.cells()>cellLimit) throw new IllegalArgumentException("Invalid preparation admission limits");
        this.world=world;this.bounds=bounds;this.cellLimit=cellLimit;this.coverageLimit=coverageLimit;this.runSettings=runSettings;
        if(runSettings!=null) {
            latestWeather=new LocalAirRun.Weather(runSettings.ambient(),runSettings.windX(),runSettings.windZ());
            weatherHistory.put(0L,new LocalAirRun.WeatherChange(0,0,latestWeather));
        }
        restart();
    }
    private void owner() { if(Thread.currentThread()!=owner) throw new IllegalStateException("Preparation requires owner thread"); }
    private Stage head() { return stages.getFirst(); }
    private Stage latest() { return stages.getLast(); }
    private void restart() {
        for(var stage:stages) stage.close();stages.clear();
        failed=false;failure="";historyEvents=0;generation++;
        context=world.contextId();geometry=world.geometryRevision();material=world.materialRevision();
        minY=world.minBuildY();maxY=world.maxBuildY();
        var stage=new Stage(world,null);stage.capture=new LocalAirGeometryCapture(bounds,cellLimit,world);stages.add(stage);
        // A retired physical job still owns outstanding until collection charges its time.
    }
    private void refresh() {
        owner();if(!closed && (context!=world.contextId() || geometry!=world.geometryRevision()
                || material!=world.materialRevision() || minY!=world.minBuildY() || maxY!=world.maxBuildY())) restart();
    }
    private void fail(String reason) { failed=true;failure=reason;for(var stage:stages) stage.close(); }
    Status status() { refresh();return closed?Status.CLOSED:failed?Status.FAILED:head().status; }
    String reason() { owner();return failed?failure:closed?"":head().reason; }
    private void time(long tick,double scale) { clock.observe(tick,scale);now=tick;scaleNow=scale; }
    /** Transfer seeds only from an exactly sealed interval, never from an edit-tick-old frame early. */
    private void promote() {
        if(failed || closed) return;
        while(stages.size()>1) {
            var old=head();if(old.run==null) return;
            if(old.run.status()==LocalAirRun.Status.FAILED || old.run.status()==LocalAirRun.Status.LAG_LIMIT) {
                fail("Geometry transfer cannot retain chronological history: "+old.run.reason());return;
            }
            var seed=old.run.sealedSeed();if(seed==null) return;
            stages.removeFirst();head().incoming=seed;head().incomingCoverage=old.certificate;old.close();
        }
    }
    int advance(int allowance,long deadline) {
        refresh();if(allowance<0) throw new IllegalArgumentException("Nonnegative preparation allowance required");
        if(closed || failed) return 0;
        try {
            promote();if(failed) return 0;var stage=head();
            if(stage.status==Status.CAPTURING) {
                if(stage.capture==null) stage.capture=new LocalAirGeometryCapture(bounds,cellLimit,stage.world,
                        stage.incoming==null?null:stage.incoming.snapshot(),stage.edits);
                int beforeReads=stage.capture.readCells(),beforeReuse=stage.capture.reusedCells(),used;
                try { used=stage.capture.advance(allowance,deadline); }
                finally {
                    captureReadCells+=stage.capture.readCells()-beforeReads;
                    captureReusedCells+=stage.capture.reusedCells()-beforeReuse;
                }
                if(stage.capture.status()==LocalAirGeometryCapture.Status.CAPTURED) {
                    stage.snapshot=stage.capture.snapshot();stage.capture=null;stage.status=Status.PLAN_QUEUED;
                } else if(stage.capture.status()!=LocalAirGeometryCapture.Status.CAPTURING) {
                    stage.reason=stage.capture.reason();stage.status=Status.REJECTED;
                    if(stage.history!=null) stage.history.close();
                }
                return used;
            }
            if(stage.status==Status.COVERAGE) {
                int beforeReads=stage.coverage.sampledCells(),beforeReuse=stage.coverage.reusedCells(),used;
                try { used=stage.coverage.advance(allowance,deadline); }
                finally {
                    coverageReadCells+=stage.coverage.sampledCells()-beforeReads;
                    coverageReusedCells+=stage.coverage.reusedCells()-beforeReuse;
                }
                if(stage.coverage.status()==LocalAirCoverage.Status.ADMITTED) {
                    stage.certificate=stage.coverage.certificate();
                    stage.bake=stage.handoff.begin(stage.snapshot,stage.certificate);stage.status=Status.BAKE_QUEUED;
                    if(stage.history!=null) stage.history.close();
                    stage.coverage=null; // The immutable certificate now owns the completed proofs.
                } else if(stage.coverage.status()!=LocalAirCoverage.Status.CHECKING) {
                    stage.reason=stage.coverage.reason();stage.status=Status.REJECTED;
                    if(stage.history!=null) stage.history.close();
                }
                return used;
            }
            return 0;
        } catch(RuntimeException error) { fail(error.toString());return allowance; }
    }
    Work takeWork() {
        refresh();if(outstanding!=null || closed || failed) return null;
        promote();if(failed) return null;var stage=head();
        if(stage.status==Status.PREPARED && stage.run!=null) {
            var work=stage.run.takeWork();
            if(work!=null) outstanding=new Work(stage,stage.snapshot,null,work);
        } else if(stage.status==Status.PLAN_QUEUED || stage.status==Status.BAKE_QUEUED) {
            outstanding=new Work(stage,stage.snapshot,stage.status==Status.BAKE_QUEUED?stage.bake:null,null);
            stage.status=stage.status==Status.PLAN_QUEUED?Status.PLANNING:Status.BAKING;
        }
        return outstanding;
    }
    void complete(Result result) {
        refresh();if(result.work!=outstanding) return;
        var stage=stages.stream().filter(candidate->candidate.ticket==result.work.ticket).findFirst().orElse(null);
        boolean discarded=closed || failed || stage==null;
        metrics.complete(result.timing,result.failed(),discarded);outstanding=null;
        if(discarded) return;
        if(result.error!=null) { fail(result.error.toString());return; }
        if(result.simulation!=null) { stage.run.complete(result.simulation);return; }
        if(stage.status==Status.PLANNING) {
            stage.coverage=new LocalAirCoverage(result.plan,stage.world,coverageLimit,stage.incomingCoverage,stage.edits);stage.status=Status.COVERAGE;
        } else if(stage.status==Status.BAKING) {
            if(stage.handoff.complete(result.completion)) stage.status=Status.PREPARED;
            else { stage.status=Status.REJECTED;stage.reason="Geometry completion no longer current"; }
        }
    }
    Prepared published() {
        refresh();return !closed && !failed && stages.size()==1 && head().status==Status.PREPARED?head().handoff.published():null;
    }
    private double scaleAt(long tick) {
        return tick==now?scaleNow:clock.segments(tick,now).getFirst().scale();
    }
    private LocalAirRun.WeatherChange weatherAt(long tick) {
        LocalAirRun.WeatherChange found=null;
        for(var entry:weatherHistory.values()) {
            if(entry.tick()>tick) break;
            found=entry;
        }
        if(found==null) throw new IllegalStateException("Weather history no longer covers topology initialization");
        return found;
    }
    boolean changeWeather(long tick,LocalAirRun.Weather weather,double scale) {
        refresh();if(closed || failed || runSettings==null || tick<now) return false;
        time(tick,scale);
        if(weather.equals(latestWeather)) return true;
        // Revision keys retain weather on both sides of a same-tick topology barrier.
        while(weatherHistory.size()>1 && weatherHistory.higherEntry(weatherHistory.firstKey()).getValue().tick()<=tick-LocalAirRun.MAX_LAG_TICKS)
            weatherHistory.pollFirstEntry();
        if(weatherHistory.size()>=256) { fail("Weather history exceeds admission limit");return false; }
        latestWeather=weather;weatherRevision++;
        weatherHistory.put(weatherRevision,new LocalAirRun.WeatherChange(weatherRevision,tick,weather));return true;
    }
    void weatherUnavailable(String reason) { owner();if(!closed) fail("Live weather unavailable: "+reason); }
    private void replayTo(Stage stage,long tick) {
        for(var segment:clock.segments(stage.replayedTick,now)) {
            if(segment.startTick()>=tick) break;
            stage.run.observe(segment.startTick(),segment.scale());
        }
        stage.run.observe(tick,scaleAt(tick));stage.replayedTick=tick;
    }
    private void driveRun(Stage stage,long tick) {
        if(stage.run==null) {
            if(stage.history!=null && stage.incoming==null) return;
            if(stage.startTick<0) { stage.startTick=tick;stage.startScale=scaleAt(tick); }
            // Map topology under its sealed seed's boundary first. A weather edge at the same tick
            // must then project in the same order whether preparation completed early or late.
            var initial=stage.incoming==null?weatherAt(stage.startTick):new LocalAirRun.WeatherChange(
                    stage.incoming.frame().weatherRevision,stage.startTick,stage.incoming.frame().weather);
            stage.appliedWeather=initial.revision();
            stage.run=new LocalAirRun(stage.handoff.published(),stage.snapshot,runSettings,stage.startTick,stage.startScale,stage.incoming,initial.weather(),initial.revision());
            stage.replayedTick=stage.startTick;
        }
        if(stage.sealed) return;
        while(true) {
            var source=stage.appliedSources<stage.sources.size()?stage.sources.get(stage.appliedSources):null;
            LocalAirRun.WeatherChange weather=null;
            for(var candidate:weatherHistory.values()) if(candidate.revision()>stage.appliedWeather
                    && candidate.revision()<=stage.endWeatherRevision && candidate.tick()<=tick) { weather=candidate;break; }
            if(weather==null && source==null) break;
            if(weather!=null && (source==null || weather.tick()<=source.tick)) {
                replayTo(stage,weather.tick());
                if(!stage.run.changeWeather(weather.tick(),weather.weather(),weather.revision(),scaleAt(weather.tick()))) { fail("Chronological weather history rejected");return; }
                stage.appliedWeather=weather.revision();
            } else {
                if(!stage.certificate.unaffectedBy(sourceKey(source.cell))) {
                    fail("Chronological source update touches unverified sky context; restart required");return;
                }
                replayTo(stage,source.tick);
                if(!stage.run.changeSource(source.tick,source.cell,source.heat,source.scale)) { fail("Chronological source history rejected");return; }
                stage.appliedSources++;
            }
        }
        replayTo(stage,tick);
        if(stage.endTick>=0) {
            if(!stage.run.seal(tick,scaleAt(tick))) { fail("Chronological topology barrier rejected");return; }
            stage.sealed=true;
        }
    }
    void observe(long tick,double scale) {
        refresh();if(closed || failed) return;time(tick,scale);promote();if(failed) return;
        var stage=head();
        if(stages.size()>1 && tick-stage.endTick>LocalAirRun.MAX_LAG_TICKS
                || stage.history!=null && stage.run==null && tick-stage.startTick>LocalAirRun.MAX_LAG_TICKS) {
            fail("Geometry transfer cannot retain chronological history beyond lag limit");return;
        }
        if(stage.status==Status.PREPARED && runSettings!=null) {
            driveRun(stage,stage.endTick<0?tick:stage.endTick);
            if(!failed && stages.size()==1 && stage.run!=null && stage.run.status()==LocalAirRun.Status.READY && tick>stage.startTick) {
                historyEvents=0;stage.incoming=null;stage.incomingCoverage=null;
                stage.sources.subList(0,stage.appliedSources).clear();stage.appliedSources=0;
            }
        }
    }
    LocalAirRun.Frame field() {
        refresh();if(closed || failed || stages.size()!=1 || head().status!=Status.PREPARED || head().run==null
                || head().appliedSources!=head().sources.size()) return null;
        var frame=head().run.published();return frame!=null && frame.weatherRevision==weatherRevision?frame:null;
    }
    boolean changeSource(int x,int y,int z,long tick,double scale) {
        refresh();if(closed || failed || tick<now) return false;
        int cell=interiorCell(x,y,z);if(cell<0) return false;
        var stage=latest();
        // A same-shape source cell can still be context for a distant empty boundary shape.
        // Returning false lets the existing world hook invalidate and recapture conservatively.
        if(stage.certificate!=null && !stage.certificate.unaffectedBy(blockKey(x,y,z))) return false;
        try {
            var sampled=world.sample(x,y,z);
            if(stage.snapshot!=null) { if(!stage.snapshot.sameMaterial(cell,sampled)) return false; }
            else {
                var previous=stage.world.sample(x,y,z);
                if(!previous.shape().equals(sampled.shape()) || previous.solidK()!=sampled.solidK()
                        || previous.solidCapacity()!=sampled.solidCapacity() || previous.fluidK()!=sampled.fluidK()
                        || previous.fluidCapacity()!=sampled.fluidCapacity()) return false;
            }
            if(stages.size()==1 && stage.history==null && stage.status==Status.PREPARED && stage.run!=null) {
                time(tick,scale);driveRun(stage,tick);if(failed) return true;
                boolean accepted=stage.run.changeSource(tick,cell,sampled.heat(),scale);
                if(accepted) stage.sourceDirty.add(blockKey(x,y,z));return accepted;
            }
            if(stage.history==null) return false;
            time(tick,scale);
            if(++historyEvents>MAX_HISTORY_EVENTS) { fail("Chronological edit history exceeds admission limit");return true; }
            stage.sources.add(new SourceEdit(tick,cell,sampled.heat(),scale));stage.sourceDirty.add(blockKey(x,y,z));return true;
        } catch(RuntimeException error) { return false; }
    }
    LocalAirRun.Status runStatus() { refresh();return closed || failed || head().run==null?null:head().run.status(); }
    private int interiorCell(int x,int y,int z) {
        long lx=(long)x-bounds.x(),ly=(long)y-bounds.y(),lz=(long)z-bounds.z();
        if(lx<=0 || ly<=0 || lz<=0 || lx>=bounds.nx()-1 || ly>=bounds.ny()-1 || lz>=bounds.nz()-1) return -1;
        return (int)((lz*bounds.ny()+ly)*bounds.nx()+lx);
    }
    private long sourceKey(int cell) {
        return blockKey(bounds.x()+cell%bounds.nx(),bounds.y()+cell/bounds.nx()%bounds.ny(),bounds.z()+cell/(bounds.nx()*bounds.ny()));
    }
    /** Called after the revision increment, only for a verified vanilla door OPEN edit. */
    boolean changeGeometry(int x,int y,int z,long previousRevision,long tick,double scale) {
        owner();if(closed || failed || tick<now || context!=world.contextId() || material!=world.materialRevision()
                || geometry!=previousRevision || world.geometryRevision()!=previousRevision+1
                || minY!=world.minBuildY() || maxY!=world.maxBuildY() || interiorCell(x,y,z)<0) return false;
        var previous=latest();
        if(previous.history==null && (previous.status!=Status.PREPARED || previous.run==null)) return false;
        var history=world.freeze();if(history==null) return false;
        time(tick,scale);geometry=world.geometryRevision();generation++;
        if(++historyEvents>MAX_HISTORY_EVENTS || stages.size()>=MAX_TOPOLOGIES && previous.startTick!=tick) {
            history.close();fail("Chronological topology history exceeds admission limit");return true;
        }
        var next=new Stage(history.world(),history);next.startTick=tick;next.startScale=scale;
        next.edits.add(blockKey(x,y,z));next.edits.addAll(previous.sourceDirty);
        if(previous.history!=null && previous.startTick==tick) {
            // Same-tick halves have zero duration between them; replace their topology, retaining source revision events.
            next.incoming=previous.incoming;next.incomingCoverage=previous.incomingCoverage;next.sources.addAll(previous.sources);
            next.edits.addAll(previous.edits);next.sourceDirty.addAll(previous.sourceDirty);
            stages.removeLast();previous.close();
        } else {
            previous.endTick=tick;previous.endWeatherRevision=weatherRevision;
            if(previous.run!=null) {
                driveRun(previous,tick);
                if(failed) { history.close();return true; }
            }
        }
        stages.addLast(next);return true;
    }
    String runReason() {
        owner();if(failed) return failure;if(closed) return "";
        return stages.size()>1?"Replaying "+stages.size()+" chronological topologies":head().run==null?head().reason:head().run.reason();
    }
    java.util.Map<LocalAirMetrics.Stage,LocalAirMetrics.Row> metrics() { owner();return metrics.snapshot(); }
    long attempts() { owner();return generation; }
    long captureReadCells() { owner();return captureReadCells; }
    long captureReusedCells() { owner();return captureReusedCells; }
    long coverageReadCells() { owner();return coverageReadCells; }
    long coverageReusedCells() { owner();return coverageReusedCells; }
    void close() {
        owner();closed=true;for(var stage:stages) stage.close();stages.clear();generation++;
    }
}
