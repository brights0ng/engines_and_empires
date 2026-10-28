// Updated by ChatGPT: measured request startup phases and completion waits.
// Updated by ChatGPT: retained known static block edits and coalesced material replacement ownership.
// Updated by ChatGPT: sliced natural equilibrium work and chronological preparation edits.
// Updated by ChatGPT: budgeted thermal initialization reads and guarded immutable thermal checkpoints.
// Written by ChatGPT.
// Updated by ChatGPT: classify consumer readings under the existing publication guards.
// Updated by ChatGPT: prepaid cancellable worker continuations and live donor evidence leases.
// Updated by ChatGPT: paired consumer readings and render frames under one revision guard.
// Updated by ChatGPT: exposed coverage failure evidence and human-readable preparation progress.
// Updated by ChatGPT: reported bulk-certified sky cells separately from scalar geometry samples.
package dev.brights0ng.meteodyne.heat.world;

import dev.brights0ng.paradynelib.heat.air.ThermalGeometry;
import static dev.brights0ng.meteodyne.heat.world.LocalAirGeometryCapture.*;

/** One explicitly requested diagnostic domain, driven by HeatWorld's existing budgets and pool.
 * Optional coupled diagnostic with chronological weather and verified source updates; no worker world access or production routing.
 */
final class LocalAirPreparation {
    private static final class Ticket { volatile boolean cancelled; }
    enum Status { CAPTURING, PLAN_QUEUED, PLANNING, COVERAGE, BAKE_QUEUED, BAKING, PREPARED, REJECTED, FAILED, CLOSED }
    private static final class Estimation {
        final Prepared prepared;
        final LocalAirThermalState.Initial initial;
        final LocalAirRun.Weather weather;
        dev.brights0ng.paradynelib.heat.air.AirThermalInitializer solver;
        Estimation(Prepared prepared,LocalAirThermalState.Initial initial,LocalAirRun.Weather weather) {this.prepared=prepared;this.initial=initial;this.weather=weather;}
        void run(long allowance) {
            long deadline=System.nanoTime()+Math.min(8_000_000L,Math.max(1,allowance));
            if(solver==null) solver=new dev.brights0ng.paradynelib.heat.air.AirThermalInitializer(prepared.thermal(),
                    dev.brights0ng.paradynelib.heat.air.FlowBoundary.wind(prepared.flow(),weather.windX(),0,weather.windZ()),
                    initial.temperatures(prepared,weather.ambient()),weather.ambient(),initial.naturalSources(prepared,weather.ambient()),weather.windX(),weather.windZ());
            solver.advance(4096,deadline);
        }
    }
    static final class Work {
        private final Ticket ticket;
        private final Snapshot snapshot;
        private final LocalAirGeometryHandoff.Job bake;
        private final LocalAirRun.Work simulation;
        private final Estimation estimation;
        private final long submitted=System.nanoTime();
        private long completedNanos;
        private Work(Stage stage,Snapshot snapshot,LocalAirGeometryHandoff.Job bake,LocalAirRun.Work simulation) {this(stage,snapshot,bake,simulation,null);}
        private Work(Stage stage,Snapshot snapshot,LocalAirGeometryHandoff.Job bake,LocalAirRun.Work simulation,Estimation estimation) {
            this.estimation=estimation;
            this.ticket=stage.ticket;this.snapshot=snapshot;this.bake=bake;this.simulation=simulation;
        }
        Result run() {
            return runSlice(submitted,Long.MAX_VALUE);
        }
        boolean estimate() { return estimation!=null; }
        boolean continues(Result result) { return estimation!=null && !ticket.cancelled && !result.failed() && !estimation.solver.ready(); }
        void batchCompleted() { completedNanos=System.nanoTime(); }
        Result runSlice(long enqueuedAt,long allowance) {
            long start=System.nanoTime();
            long cpu=dev.brights0ng.paradynelib.heat.air.ThermalTiming.cpu();
            try {
                if(estimation!=null) {estimation.run(allowance);return finish(null,null,null,start,cpu,null,enqueuedAt);}
                if(simulation!=null) return finish(null,null,simulation.run(),start,cpu,null);
                var plan=bake==null?LocalAirCoverage.plan(snapshot):null;
                var completion=bake==null?null:bake.bake(ThermalGeometry.Coefficients.legacyMaterials());
                return finish(plan,completion,null,start,cpu,null);
            } catch(Throwable error) { return finish(null,null,null,start,cpu,error); }
        }
        private Result finish(LocalAirCoverage.Plan plan,LocalAirGeometryHandoff.Completion completion,LocalAirRun.Result run,long start,long cpu,Throwable error) {
            return finish(plan,completion,run,start,cpu,error,submitted);
        }
        private Result finish(LocalAirCoverage.Plan plan,LocalAirGeometryHandoff.Completion completion,LocalAirRun.Result run,long start,long cpu,Throwable error,long enqueuedAt) {
            completedNanos=System.nanoTime();
            long nanos=System.nanoTime()-start;
            var stage=estimation!=null?LocalAirMetrics.Stage.ESTIMATE:simulation!=null?(simulation.initialization()?LocalAirMetrics.Stage.INITIALIZE:LocalAirMetrics.Stage.STEP)
                    :bake==null?LocalAirMetrics.Stage.PLAN:LocalAirMetrics.Stage.BAKE;
            var timing=new LocalAirMetrics.Timing(stage,Math.max(0,start-enqueuedAt),nanos,
                    dev.brights0ng.paradynelib.heat.air.ThermalTiming.difference(cpu,dev.brights0ng.paradynelib.heat.air.ThermalTiming.cpu()));
            return new Result(this,plan,completion,run,nanos,error,timing,estimation);
        }
    }
    record Result(Work work,LocalAirCoverage.Plan plan,LocalAirGeometryHandoff.Completion completion,LocalAirRun.Result simulation,long nanos,Throwable error,LocalAirMetrics.Timing timing,Estimation estimation) {
        Result(Work work,LocalAirCoverage.Plan plan,LocalAirGeometryHandoff.Completion completion,LocalAirRun.Result simulation,long nanos,Throwable error,LocalAirMetrics.Timing timing) {this(work,plan,completion,simulation,nanos,error,timing,null);}
        Result(Work work,LocalAirCoverage.Plan plan,LocalAirGeometryHandoff.Completion completion,long nanos,Throwable error) {
            this(work,plan,completion,null,nanos,error,null);
        }
        boolean failed() { return error!=null || simulation!=null && simulation.failed(); }
    }
    static final int MAX_TOPOLOGIES=16,MAX_HISTORY_EVENTS=256;
    private record SourceEdit(long tick,int cell,Heat heat,double scale,boolean driven) {
        SourceEdit(long tick,int cell,Heat heat,double scale) { this(tick,cell,heat,scale,false); }
    }
    /** Each historical stage is read/baked/stepped only when it reaches the head of the queue. */
    private static final class Stage {
        final Ticket ticket=new Ticket(); // Work carries only cancellation/identity, never the world-bearing Stage.
        final LocalAirCoverage.World world;
        final LocalAirCoverage.History history;
        final LocalAirGeometryHandoff handoff;
        final java.util.ArrayList<SourceEdit> sources=new java.util.ArrayList<>();
        final java.util.HashSet<Long> edits=new java.util.HashSet<>(),sourceDirty=new java.util.HashSet<>();
        final java.util.HashSet<Integer> replacedSolid=new java.util.HashSet<>(),replacedFluid=new java.util.HashSet<>();
        LocalAirGeometryCapture capture;
        Snapshot snapshot;
        LocalAirCoverage coverage;
        LocalAirCoverage.Certificate certificate,incomingCoverage;
        LocalAirGeometryHandoff.Job bake;
        LocalAirRun run;
        dev.brights0ng.paradynelib.heat.air.AirThermalInitializer.Quality estimationQuality;
        Estimation estimation;
        LocalAirRun.Seed incoming;
        LocalAirRun.LayoutSeed layoutIncoming;
        java.util.Map<Integer,Heat> initialDriven;
        Status status=Status.CAPTURING;
        String reason="";
        long startTick=-1,replayedTick=-1,endTick=-1;
        double startScale;
        int appliedSources;
        long appliedWeather;
        long endWeatherRevision=Long.MAX_VALUE;
        boolean sealed;
        Stage(LocalAirCoverage.World world,LocalAirCoverage.History history,int tileEdge) {
            this.world=world;this.history=history;handoff=new LocalAirGeometryHandoff(world,tileEdge);
        }
        void close() {
            ticket.cancelled=true;
            if(run!=null) run.close();
            if(history!=null) history.close();
            handoff.invalidate();estimation=null;capture=null;snapshot=null;incoming=null;layoutIncoming=null;certificate=null;incomingCoverage=null;
        }
    }
    private final Thread owner=Thread.currentThread();
    private final LocalAirCoverage.World world;
    private final Bounds bounds;
    private final int cellLimit,coverageLimit;
    private final int tileEdge;
    private final LocalAirRun.Settings runSettings;
    private final java.util.ArrayDeque<Stage> stages=new java.util.ArrayDeque<>();
    private final dev.brights0ng.paradynelib.heat.air.ThermalClock clock=
            new dev.brights0ng.paradynelib.heat.air.ThermalClock(LocalAirRun.MAX_LAG_TICKS);
    private final LocalAirMetrics metrics=new LocalAirMetrics();
    private final LocalAirStartup startup=new LocalAirStartup();
    private java.util.List<LocalAirReuse> reusable=java.util.List.of();
    private Work outstanding;
    private long generation,context,geometry,material,now=-1;
    private long restarts;
    private String lastRestart="Initial request";
    private long captureReadCells,captureReusedCells;
    private long coverageReadCells,coverageReusedCells,coverageBulkCells;
    private int minY,maxY,historyEvents;
    private double scaleNow;
    private boolean closed,failed;
    private LocalAirThermalState.Provider thermalProvider;
    private LocalAirThermalState.Reader thermalReader;
    private LocalAirThermalState.Value[] thermalValues;
    private LocalAirThermalState.Initial thermalInitial;
    private int thermalCursor;
    private boolean awaitingLayoutSeed;
    private long layoutBarrier=-1;
    private String failure="";
    private final java.util.TreeMap<Long,LocalAirRun.WeatherChange> weatherHistory=new java.util.TreeMap<>();
    private long weatherRevision;
    private LocalAirRun.Weather latestWeather;
    private static final int MAX_DRIVEN_CELLS=256;
    private int[] drivenCells=new int[0];
    private Heat[] drivenHeat=new Heat[0];
    private long[] drivenTicks=new long[0];
    private int drivenCursor;
    private long drivenReads;
    private long thermalReads,thermalReused;

    LocalAirPreparation(LocalAirCoverage.World world,Bounds bounds,int cellLimit,int coverageLimit) {
        this(world,bounds,cellLimit,coverageLimit,null);
    }
    LocalAirPreparation(LocalAirCoverage.World world,Bounds bounds,int cellLimit,int coverageLimit,LocalAirRun.Settings runSettings) {
        this(world,bounds,cellLimit,coverageLimit,runSettings,0);
    }
    LocalAirPreparation(LocalAirCoverage.World world,Bounds bounds,int cellLimit,int coverageLimit,LocalAirRun.Settings runSettings,int tileEdge) {
        if(coverageLimit<0 || bounds.cells()>cellLimit) throw new IllegalArgumentException("Invalid preparation admission limits");
        if(tileEdge!=0 && tileEdge!=16) throw new IllegalArgumentException("Unsupported tile edge");
        this.world=world;this.bounds=bounds;this.cellLimit=cellLimit;this.coverageLimit=coverageLimit;this.runSettings=runSettings;this.tileEdge=tileEdge;
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
        reusable=java.util.List.of();
        for(var stage:stages) stage.close();stages.clear();
        failed=false;failure="";historyEvents=0;generation++;
        thermalReader=null;thermalValues=null;thermalInitial=null;thermalCursor=0;
        drivenCells=new int[0];drivenHeat=new Heat[0];drivenTicks=new long[0];drivenCursor=0;
        context=world.contextId();geometry=world.geometryRevision();material=world.materialRevision();
        minY=world.minBuildY();maxY=world.maxBuildY();
        var stage=new Stage(world,null,tileEdge);stages.add(stage);
        // A retired physical job still owns outstanding until collection charges its time.
    }
    private void refresh() {
        owner();if(closed) return;
        long nextContext=world.contextId(),nextGeometry=world.geometryRevision(),nextMaterial=world.materialRevision();
        int nextMin=world.minBuildY(),nextMax=world.maxBuildY();
        if(context!=nextContext || geometry!=nextGeometry || material!=nextMaterial || minY!=nextMin || maxY!=nextMax) {
            var causes=new java.util.ArrayList<String>();
            if(context!=nextContext) causes.add("context "+context+" -> "+nextContext);
            if(geometry!=nextGeometry) causes.add("geometry "+geometry+" -> "+nextGeometry);
            if(material!=nextMaterial) causes.add("materials "+material+" -> "+nextMaterial);
            if(minY!=nextMin || maxY!=nextMax) causes.add("build height "+minY+".."+maxY+" -> "+nextMin+".."+nextMax);
            lastRestart="Observed after tick "+now+": "+String.join(", ",causes);restarts++;restart();
        }
    }
    private void fail(String reason) { failed=true;failure=reason;for(var stage:stages) stage.close(); }
    LocalAirReuse reuseEvidence() {
        owner();if(!readyForLayout()) return null;
        var stage=head();var snapshot=stage.snapshot;
        return new LocalAirReuse(snapshot,stage.certificate,stage.sourceDirty,world,
                ()->!closed && !failed && stages.size()==1 && head().snapshot==snapshot,thermalCheckpoint());
    }
    void reuseEvidence(java.util.List<LocalAirReuse> evidence) {
        owner();if(now>=0 || head().capture!=null) throw new IllegalStateException("Reuse must precede target capture/observation");
        reusable=java.util.List.copyOf(evidence);
    }
    Status status() { refresh();return closed?Status.CLOSED:failed?Status.FAILED:head().status; }
    String reason() { owner();return failed?failure:closed?"":head().reason; }
    LocalAirCoverage.Failure coverageFailure() {
        refresh();return closed || failed || head().status!=Status.REJECTED || head().coverage==null?null:head().coverage.failure();
    }
    String description() {
        refresh();if(closed) return "Field closed";if(failed) return failure;
        var stage=head();
        if(stage.status==Status.REJECTED) return "Coverage rejected: "+stage.reason;
        if(stage.status==Status.CAPTURING) return "Capturing geometry";
        if(stage.status==Status.COVERAGE) return stage.coverage.progress();
        if(!stage.reason.isEmpty()) return stage.reason;
        if(stage.status==Status.PREPARED) {
            if(thermalProvider!=null && thermalInitial==null && stage.incoming==null) return "Reading initial temperatures";
            if(awaitingLayoutSeed || layoutBarrier>=0) return "Preparing coverage handoff";
            if(stage.run==null) return "Initializing coupled field";
            return "Updating coupled field";
        }
        return "Preparing geometry: "+stage.status;
    }
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
            int used=advanceDriven(Math.min(16,allowance),deadline);
            return used+advanceGeometry(allowance-used,deadline);
        } catch(RuntimeException error) { fail(error.toString());return allowance; }
        finally { traceStartup(); }
    }
    private int advanceGeometry(int allowance,long deadline) {
        refresh();if(allowance<0) throw new IllegalArgumentException("Nonnegative preparation allowance required");
        if(closed || failed) return 0;
        try {
            promote();if(failed) return 0;var stage=head();
            if(stage.status==Status.CAPTURING) {
                if(stage.capture==null) stage.capture=new LocalAirGeometryCapture(bounds,cellLimit,stage.world,
                        stage.incoming==null?null:stage.incoming.snapshot(),stage.edits,stage.incoming==null?reusable:java.util.List.of());
                int beforeReads=stage.capture.readCells(),beforeReuse=stage.capture.reusedCells(),used;
                try { used=stage.capture.advance(allowance,deadline); }
                finally {
                    captureReadCells+=stage.capture.readCells()-beforeReads;
                    captureReusedCells+=stage.capture.reusedCells()-beforeReuse;
                }
                if(stage.capture.status()==LocalAirGeometryCapture.Status.CAPTURED) {
                    stage.snapshot=stage.capture.snapshot();stage.capture=null;stage.status=Status.PLAN_QUEUED;
                    bindDriven(stage.snapshot);
                } else if(stage.capture.status()!=LocalAirGeometryCapture.Status.CAPTURING) {
                    stage.reason=stage.capture.reason();stage.status=Status.REJECTED;
                    if(stage.history!=null) stage.history.close();
                }
                return used+advanceDriven(allowance-used,deadline);
            }
            if(stage.status==Status.COVERAGE) {
                int beforeReads=stage.coverage.sampledCells(),beforeReuse=stage.coverage.reusedCells(),beforeBulk=stage.coverage.bulkCells(),used;
                try { used=stage.coverage.advance(allowance,deadline); }
                finally {
                    coverageReadCells+=stage.coverage.sampledCells()-beforeReads;
                    coverageReusedCells+=stage.coverage.reusedCells()-beforeReuse;
                    coverageBulkCells+=stage.coverage.bulkCells()-beforeBulk;
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
                return used+advanceDriven(allowance-used,deadline);
            }
            return advanceInitial(advanceDriven(allowance,deadline),allowance,deadline);
        } catch(RuntimeException error) { fail(error.toString());return allowance; }
    }
    void thermalInitialization(LocalAirThermalState.Provider provider) {
        owner();if(now>=0) throw new IllegalStateException("Initialization policy must precede observation");thermalProvider=java.util.Objects.requireNonNull(provider);
    }
    private int advanceInitial(int used,int allowance,long deadline) {
        var stage=head();
        if(thermalProvider==null || thermalInitial!=null || stage.status!=Status.PREPARED || stage.incoming!=null) return used;
        if(used>=allowance || System.nanoTime()>=deadline) return used;
        if(thermalValues==null) thermalValues=new LocalAirThermalState.Value[bounds.cells()];
        while(thermalCursor<thermalValues.length && System.nanoTime()<deadline) {
            LocalAirThermalState.Value value=null;
            if(awaitingLayoutSeed) for(var lease:reusable) {
                value=lease.temperature(world,stage.snapshot,thermalCursor,latestWeather.ambient());if(value!=null) break;
            }
            if(value!=null) { thermalValues[thermalCursor++]=value;thermalReused++;continue; }
            if(used>=allowance) break;
            if(thermalReader==null) {
                thermalReader=thermalProvider.begin(stage.snapshot);
                if(thermalReader==null) { stage.reason="Awaiting legacy natural-source estimate";return used+1; }
            }
            value=thermalReader.read(thermalCursor);used++;thermalReads++;
            if(value==null) { thermalReader=null;thermalValues=null;thermalCursor=0;stage.reason="Awaiting legacy natural-source estimate";return used; }
            thermalValues[thermalCursor++]=value;
        }
        if(thermalCursor==thermalValues.length) { thermalInitial=new LocalAirThermalState.Initial(stage.snapshot,thermalValues);thermalValues=null;thermalReader=null;reusable=java.util.List.of();stage.reason=""; }
        return used;
    }
    LocalAirRun.Seed thermalCheckpoint() {
        refresh();var frame=field();return frame==null?null:new LocalAirRun.Seed(head().handoff.published(),head().snapshot,frame);
    }
    private void bindDriven(Snapshot snapshot) {
        if(runSettings==null) return;
        var cells=snapshot.drivenCells();
        if(cells.length>MAX_DRIVEN_CELLS) throw new IllegalArgumentException("Driven source cell admission limit exceeded (256)");
        var heat=new Heat[cells.length];var ticks=new long[cells.length];java.util.Arrays.fill(ticks,-1);
        for(int i=0;i<cells.length;i++) {
            int cell=cells[i];if(interiorCell(bounds.x()+bounds.cellX(cell),bounds.y()+bounds.cellY(cell),bounds.z()+bounds.cellZ(cell))<0)
                throw new IllegalArgumentException("Driven source touches domain boundary; enlarge coverage");
            int old=java.util.Arrays.binarySearch(drivenCells,cell);if(old>=0) { heat[i]=drivenHeat[old];ticks[i]=drivenTicks[old]; }
        }
        drivenCells=cells;drivenHeat=heat;drivenTicks=ticks;drivenCursor=0;
    }
    private boolean forcingReady() {
        for(long tick:drivenTicks) if(tick<0) return false;return true;
    }
    private java.util.Map<Integer,Heat> drivenSnapshot() {
        var out=new java.util.HashMap<Integer,Heat>();for(int i=0;i<drivenCells.length;i++) if(drivenHeat[i]!=null) out.put(drivenCells[i],drivenHeat[i]);return java.util.Map.copyOf(out);
    }
    /** Shares the capture allowance/deadline. Each live source is one bounded owner-thread read. */
    private int advanceDriven(int allowance,long deadline) {
        if(runSettings==null || now<0 || closed || failed || head().status==Status.REJECTED) return 0;
        int used=0;
        for(int visited=0;visited<drivenCells.length && used<allowance && System.nanoTime()<deadline;visited++) {
            int i=Math.floorMod(drivenCursor++,drivenCells.length);
            if(drivenTicks[i]>=0 && now-drivenTicks[i]<LocalAirRun.PHYSICAL_TICKS) continue;
            int cell=drivenCells[i];long attempt=generation;
            used++;drivenReads++;
            var heat=world.drivenHeat(bounds.x()+bounds.cellX(cell),bounds.y()+bounds.cellY(cell),bounds.z()+bounds.cellZ(cell));
            refresh();if(closed || failed || attempt!=generation) return used;
            var stage=latest();boolean changed=!java.util.Objects.equals(heat,drivenHeat[i]);
            drivenHeat[i]=heat;drivenTicks[i]=now;
            if(changed && stage.startTick>=0) {
                if(++historyEvents>MAX_HISTORY_EVENTS) { fail("Driven source history exceeds admission limit");return used; }
                stage.sources.add(new SourceEdit(now,cell,heat,scaleNow,true));
            }
        }
        return used;
    }
    Work takeWork() {
        refresh();if(outstanding!=null || closed || failed) return null;
        promote();if(failed) return null;var stage=head();
        if(stage.status==Status.PREPARED && stage.incoming==null && thermalInitial!=null && thermalInitial.needsRefinement() && forcingReady()) {
            if(stage.estimation==null) {
                if(!awaitingLayoutSeed && stage.startTick<0) {stage.startTick=now;stage.startScale=scaleNow;stage.initialDriven=drivenSnapshot();}
                stage.estimation=new Estimation(stage.handoff.published(),thermalInitial,weatherAt(stage.startTick<0?now:stage.startTick).weather());
            }
            stage.reason="Preparing natural thermal equilibrium";outstanding=new Work(stage,stage.snapshot,null,null,stage.estimation);
        } else if(stage.status==Status.PREPARED && stage.run!=null) {
            var work=stage.run.takeWork();
            if(work!=null) outstanding=new Work(stage,stage.snapshot,null,work);
        } else if(stage.status==Status.PLAN_QUEUED || stage.status==Status.BAKE_QUEUED) {
            outstanding=new Work(stage,stage.snapshot,stage.status==Status.BAKE_QUEUED?stage.bake:null,null);
            stage.status=stage.status==Status.PLAN_QUEUED?Status.PLANNING:Status.BAKING;
        }
        return outstanding;
    }
    void complete(Result result) {
        try {
        refresh();if(result.work!=outstanding) return;
        var stage=stages.stream().filter(candidate->candidate.ticket==result.work.ticket).findFirst().orElse(null);
        boolean discarded=closed || failed || stage==null;
        metrics.complete(result.timing,result.failed(),discarded);outstanding=null;
        startup.completed(result.timing,result.work.completedNanos>0?result.work.completedNanos:System.nanoTime());
        if(discarded) return;
        if(result.error!=null) { fail(result.error.toString());return; }
        if(result.estimation!=null) {
            stage.estimationQuality=result.estimation.solver.quality();
            if(result.estimation.solver.ready()) {
                thermalInitial=thermalInitial.refined(result.estimation.solver.solution(),result.estimation.weather.ambient());stage.estimation=null;stage.reason="";
            }
            return;
        }
        if(result.simulation!=null) { stage.run.complete(result.simulation);return; }
        if(stage.status==Status.PLANNING) {
            stage.coverage=new LocalAirCoverage(result.plan,stage.world,coverageLimit,stage.incomingCoverage,stage.edits,reusable);stage.status=Status.COVERAGE;
        } else if(stage.status==Status.BAKING) {
            if(stage.handoff.complete(result.completion)) { stage.status=Status.PREPARED;if(thermalProvider==null) reusable=java.util.List.of(); }
            else { stage.status=Status.REJECTED;stage.reason="Geometry completion no longer current"; }
        }
        } finally { traceStartup(); }
    }
    Prepared published() {
        refresh();return !closed && !failed && forcingReady() && (thermalProvider==null || thermalInitial!=null && !thermalInitial.needsRefinement() || head().incoming!=null) && stages.size()==1 && head().status==Status.PREPARED?head().handoff.published():null;
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
            if(!forcingReady()) return;
            if(thermalProvider!=null && stage.incoming==null && (thermalInitial==null || thermalInitial.needsRefinement())) return;
            if(awaitingLayoutSeed) return;
            if(stage.history!=null && stage.incoming==null) return;
            if(stage.startTick<0) { stage.startTick=tick;stage.startScale=scaleAt(tick); }
            if(stage.initialDriven==null) stage.initialDriven=drivenSnapshot();
            // Map topology under its sealed seed's boundary first. A weather edge at the same tick
            // must then project in the same order whether preparation completed early or late.
            var initial=stage.incoming==null?weatherAt(stage.startTick):new LocalAirRun.WeatherChange(
                    stage.incoming.frame().weatherRevision,stage.startTick,stage.incoming.frame().weather);
            stage.appliedWeather=initial.revision();
            var incoming=stage.incoming==null?null:stage.incoming.forEdit(stage.replacedSolid,stage.replacedFluid);
            stage.run=new LocalAirRun(stage.handoff.published(),stage.snapshot,runSettings,stage.startTick,stage.startScale,incoming,initial.weather(),initial.revision(),stage.layoutIncoming,stage.initialDriven,stage.incoming==null?thermalInitial:null);
            stage.replayedTick=stage.startTick;
        }
        if(stage.sealed) return;
        if(stage.run.status()==LocalAirRun.Status.FAILED || stage.run.status()==LocalAirRun.Status.LAG_LIMIT) {
            fail("Coupled simulation "+stage.run.status()+": "+stage.run.reason());return;
        }
        while(true) {
            var source=stage.appliedSources<stage.sources.size()?stage.sources.get(stage.appliedSources):null;
            if(source!=null && source.tick>tick) source=null;
            LocalAirRun.WeatherChange weather=null;
            for(var candidate:weatherHistory.values()) if(candidate.revision()>stage.appliedWeather
                    && candidate.revision()<=stage.endWeatherRevision && candidate.tick()<=tick) { weather=candidate;break; }
            if(weather==null && source==null) break;
            if(weather!=null && (source==null || weather.tick()<=source.tick)) {
                replayTo(stage,weather.tick());
                if(!stage.run.changeWeather(weather.tick(),weather.weather(),weather.revision(),scaleAt(weather.tick()))) { fail("Chronological weather history rejected");return; }
                stage.appliedWeather=weather.revision();
            } else {
                if(!source.driven && !stage.certificate.unaffectedBy(sourceKey(source.cell))) {
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
        if(stage.estimation!=null && stage.startTick>=0 && tick-stage.startTick>LocalAirRun.MAX_LAG_TICKS) {fail("Natural initialization exceeded retained weather/source history");return;}
        if(stage.status==Status.PREPARED && runSettings!=null) {
            long through=stage.endTick<0?tick:stage.endTick;if(layoutBarrier>=0) through=Math.min(through,layoutBarrier);
            driveRun(stage,through);
            if(layoutBarrier>=0 && stage.run!=null && !stage.run.layoutSealed() && !stage.run.seal(layoutBarrier,scaleAt(layoutBarrier)))
                fail("Layout barrier could not seal the run");
            if(!failed && layoutBarrier<0 && stages.size()==1 && stage.run!=null && stage.run.status()==LocalAirRun.Status.READY && tick>stage.startTick) {
                historyEvents=0;stage.incoming=null;stage.incomingCoverage=null;
                stage.sources.subList(0,stage.appliedSources).clear();stage.appliedSources=0;
            }
        }
        traceStartup();
    }
    boolean inFlight() { owner();return outstanding!=null; }
    boolean readyForLayout() { refresh();return !closed && !failed && stages.size()==1 && head().status==Status.PREPARED && head().run!=null && head().run.status()==LocalAirRun.Status.READY; }
    void awaitLayoutSeed() { owner();if(now>=0) throw new IllegalStateException("Seed policy must be set before observing a new owner");awaitingLayoutSeed=true; }
    boolean holdLayout(long tick,double scale) {
        refresh();if(closed || failed || !forcingReady() || thermalProvider!=null && (thermalInitial==null || thermalInitial.needsRefinement()) || stages.size()!=1 || head().status!=Status.PREPARED) return false;
        layoutBarrier=tick;time(tick,scale);head().startTick=head().run==null?tick:head().startTick;
        if(head().run==null) head().initialDriven=drivenSnapshot();
        observe(tick,scale);return !failed;
    }
    LocalAirRun.Seed layoutCheckpoint() {
        refresh();return closed || failed || layoutBarrier<0 || stages.size()!=1 || head().run==null?null:head().run.sealedSeed();
    }
    void installLayoutSeed(LocalAirRun.LayoutSeed seed) {
        owner();if(!awaitingLayoutSeed || layoutBarrier<0 || head().run!=null) throw new IllegalStateException("Layout seed not expected");
        head().layoutIncoming=seed;head().startTick=layoutBarrier;head().startScale=scaleAt(layoutBarrier);awaitingLayoutSeed=false;
    }
    void resumeLayout() {
        owner();layoutBarrier=-1;if(!closed && !failed && stages.size()==1) { head().layoutIncoming=null;if(head().run!=null) head().run.resumeLayout(); }
    }
    LocalAirRun.Frame field() {
        refresh();if(closed || failed || stages.size()!=1 || head().status!=Status.PREPARED || head().run==null
                || head().appliedSources!=head().sources.size()) return null;
        var frame=head().run.published();return frame!=null && frame.weatherRevision==weatherRevision?frame:null;
    }
    /** Owner-thread query; refreshes revision guards but never captures blocks or dispatches solver work. */
    LocalAirReading query(long tick,double x,double y,double z,long maxAgeTicks) {
        return presentation(tick,x,y,z,maxAgeTicks).reading();
    }
    LocalAirPresentation presentation(long tick,double x,double y,double z,long maxAgeTicks) {
        LocalAirReading.validate(tick,x,y,z,maxAgeTicks);
        var frame=field();
        var reading=query(frame,tick,x,y,z,maxAgeTicks);
        return new LocalAirPresentation(context,!closed,reading,frame,reading.currentSample().isPresent()?"":description());
    }
    private LocalAirReading query(LocalAirRun.Frame frame,long tick,double x,double y,double z,long maxAgeTicks) {
        if(closed) return LocalAirReading.missing(LocalAirReading.Status.UNAVAILABLE,LocalAirReading.Reason.CLOSED);
        if(!bounds.contains(x,y,z))
            return LocalAirReading.missing(LocalAirReading.Status.UNAVAILABLE,LocalAirReading.Reason.OUTSIDE_DOMAIN);
        if(failed) return LocalAirReading.missing(LocalAirReading.Status.UNAVAILABLE,LocalAirReading.Reason.FAILED);
        if(runSettings==null) return LocalAirReading.missing(LocalAirReading.Status.UNAVAILABLE,LocalAirReading.Reason.GEOMETRY_ONLY);
        if(head().status==Status.REJECTED) return LocalAirReading.missing(LocalAirReading.Status.UNAVAILABLE,LocalAirReading.Reason.REJECTED);
        if(head().status==Status.FAILED) return LocalAirReading.missing(LocalAirReading.Status.UNAVAILABLE,LocalAirReading.Reason.FAILED);
        var run=head().run;
        if(run!=null && run.status()==LocalAirRun.Status.LAG_LIMIT)
            return LocalAirReading.missing(LocalAirReading.Status.UNAVAILABLE,LocalAirReading.Reason.LAG_LIMIT);
        if(run!=null && run.status()==LocalAirRun.Status.FAILED)
            return LocalAirReading.missing(LocalAirReading.Status.UNAVAILABLE,LocalAirReading.Reason.FAILED);
        if(frame==null) return LocalAirReading.missing(LocalAirReading.Status.PENDING,
                run==null?LocalAirReading.Reason.PREPARING:LocalAirReading.Reason.REFRESHING);
        var point=LocalAirDiagnostics.sample(frame,tick,x,y,z);
        if(point.availability()!=LocalAirDiagnostics.Availability.AIR)
            return LocalAirReading.missing(LocalAirReading.Status.UNAVAILABLE,LocalAirReading.Reason.OCCUPIED);
        boolean current=point.ageTicks()<=maxAgeTicks;
        return new LocalAirReading(current?LocalAirReading.Status.CURRENT:LocalAirReading.Status.STALE,
                current?LocalAirReading.Reason.NONE:LocalAirReading.Reason.AGE_LIMIT,
                java.util.Optional.of(new LocalAirReading.Sample(point.throughTick(),point.ageTicks(),point.temperature(),point.x(),point.y(),point.z())));
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
            // A state edit can change driver selection; recapture candidate membership conservatively.
            if(sampled.driven() || stage.snapshot!=null && java.util.Arrays.binarySearch(stage.snapshot.drivenCells(),cell)>=0) return false;
            if(stage.snapshot!=null) { if(!stage.snapshot.sameMaterial(cell,sampled)) return false; }
            else {
                var previous=stage.world.sample(x,y,z);
                if(!previous.shape().equals(sampled.shape()) || previous.solidK()!=sampled.solidK()
                        || previous.solidCapacity()!=sampled.solidCapacity() || previous.fluidK()!=sampled.fluidK()
                        || previous.fluidCapacity()!=sampled.fluidCapacity()) return false;
            }
            if(layoutBarrier>=0 && tick>layoutBarrier && stage.run!=null) {
                time(tick,scale);if(++historyEvents>MAX_HISTORY_EVENTS) { fail("Layout source history exceeds limit");return true; }
                stage.sources.add(new SourceEdit(tick,cell,sampled.heat(),scale));stage.sourceDirty.add(blockKey(x,y,z));return true;
            }
            if(stages.size()==1 && stage.history==null && stage.status==Status.PREPARED && stage.run!=null) {
                time(tick,scale);driveRun(stage,tick);if(failed) return true;
                boolean accepted=stage.run.changeSource(tick,cell,sampled.heat(),scale);
                if(accepted) stage.sourceDirty.add(blockKey(x,y,z));return accepted;
            }
            if(stage.estimation!=null && stage.startTick>=0) {
                time(tick,scale);if(++historyEvents>MAX_HISTORY_EVENTS) {fail("Initialization source history exceeds admission limit");return true;}
                stage.sources.add(new SourceEdit(tick,cell,sampled.heat(),scale));stage.sourceDirty.add(blockKey(x,y,z));return true;
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
        if(lx<0 || ly<0 || lz<0 || lx>=bounds.nx() || ly>=bounds.ny() || lz>=bounds.nz()) return -1;
        int cell=bounds.index((int)lx,(int)ly,(int)lz);if(cell<0) return -1;
        for(int a=0;a<3;a++) for(int side=0;side<2;side++) if(bounds.neighbor(cell,a,side)<0) return -1;
        return cell;
    }
    private long sourceKey(int cell) {
        return blockKey(bounds.x()+bounds.cellX(cell),bounds.y()+bounds.cellY(cell),bounds.z()+bounds.cellZ(cell));
    }
    /** Retained-material edit control, also used by deterministic fixtures. */
    boolean changeGeometry(int x,int y,int z,long previousRevision,long tick,double scale) {
        return changeGeometry(x,y,z,previousRevision,tick,scale,true,true);
    }
    /** Called after a known server-thread block write and its single revision increment.
     * Replaced phases leave with their stored energy; new capacity starts at ambient.
     */
    boolean changeGeometry(int x,int y,int z,long previousRevision,long tick,double scale,boolean retainSolid,boolean retainFluid) {
        owner();if(closed || failed || tick<now || context!=world.contextId() || material!=world.materialRevision()
                || geometry!=previousRevision || world.geometryRevision()!=previousRevision+1
                || minY!=world.minBuildY() || maxY!=world.maxBuildY()) return false;
        var previous=latest();
        if(previous.history==null && (previous.status!=Status.PREPARED || previous.run==null)) return false;
        var history=world.freeze();if(history==null) return false;
        time(tick,scale);geometry=world.geometryRevision();generation++;
        if(++historyEvents>MAX_HISTORY_EVENTS || stages.size()>=MAX_TOPOLOGIES && previous.startTick!=tick) {
            history.close();fail("Chronological topology history exceeds admission limit");return true;
        }
        var next=new Stage(history.world(),history,tileEdge);next.startTick=tick;next.startScale=scale;
        next.edits.add(blockKey(x,y,z));next.edits.addAll(previous.sourceDirty);
        int cell=bounds.index(x-bounds.x(),y-bounds.y(),z-bounds.z());
        if(cell>=0) { if(!retainSolid) next.replacedSolid.add(cell);if(!retainFluid) next.replacedFluid.add(cell); }
        if(previous.history!=null && previous.startTick==tick) {
            // Same-tick halves have zero duration between them; replace their topology, retaining source revision events.
            next.incoming=previous.incoming;next.incomingCoverage=previous.incomingCoverage;next.sources.addAll(previous.sources);
            next.edits.addAll(previous.edits);next.sourceDirty.addAll(previous.sourceDirty);
            next.replacedSolid.addAll(previous.replacedSolid);next.replacedFluid.addAll(previous.replacedFluid);
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
    private void traceStartup() {
        if(startup.ready()) return;
        if(closed || failed || stages.isEmpty()) { startup.update(LocalAirStartup.Phase.STOPPED,now,null);return; }
        var stage=head();Long frameTick=null;
        var phase=switch(stage.status) {
            case CAPTURING -> LocalAirStartup.Phase.CAPTURE;
            case PLAN_QUEUED,PLANNING -> LocalAirStartup.Phase.PLAN;
            case COVERAGE -> LocalAirStartup.Phase.COVERAGE;
            case BAKE_QUEUED,BAKING -> LocalAirStartup.Phase.BAKE;
            case REJECTED,FAILED,CLOSED -> LocalAirStartup.Phase.STOPPED;
            case PREPARED -> !forcingReady()?LocalAirStartup.Phase.DRIVEN_INPUT:
                    thermalProvider!=null && stage.incoming==null && thermalInitial==null?LocalAirStartup.Phase.THERMAL_INPUT:
                    stage.estimation!=null || stage.incoming==null && thermalInitial!=null && thermalInitial.needsRefinement()?LocalAirStartup.Phase.ESTIMATE:
                    awaitingLayoutSeed || layoutBarrier>=0?LocalAirStartup.Phase.LAYOUT_WAIT:LocalAirStartup.Phase.INITIALIZE;
        };
        if(stages.size()==1 && stage.run!=null && stage.appliedSources==stage.sources.size() && !awaitingLayoutSeed && layoutBarrier<0) {
            var frame=stage.run.published();
            if(frame!=null && frame.weatherRevision==weatherRevision) { frameTick=frame.throughTick;phase=LocalAirStartup.Phase.CATCH_UP; }
        }
        startup.update(phase,now,frameTick);
    }
    LocalAirStartup.Report startup() { owner();traceStartup();return startup.report(); }
    long thermalReadCells() { owner();return thermalReads; }
    long thermalReusedCells() { owner();return thermalReused; }
    dev.brights0ng.paradynelib.heat.air.AirThermalInitializer.Quality estimationQuality() {owner();return closed || stages.isEmpty()?null:head().estimationQuality;}
    long attempts() { owner();return generation; }
    long restarts() { owner();return restarts; }
    String lastRestart() { owner();return lastRestart; }
    long captureReadCells() { owner();return captureReadCells; }
    long captureReusedCells() { owner();return captureReusedCells; }
    long coverageReadCells() { owner();return coverageReadCells; }
    long coverageReusedCells() { owner();return coverageReusedCells; }
    long coverageBulkCells() { owner();return coverageBulkCells; }
    long drivenReadCells() { owner();return drivenReads; }
    int drivenSourceCells() { owner();return drivenCells.length; }
    void close() {
        owner();closed=true;for(var stage:stages) stage.close();stages.clear();generation++;
        reusable=java.util.List.of();
        traceStartup();
    }
}
