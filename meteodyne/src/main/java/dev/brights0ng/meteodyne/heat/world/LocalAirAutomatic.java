// Updated by ChatGPT: reported natural initialization iteration progress.
// Updated by ChatGPT: thermal checkpoints across retirement, layout handoffs and explicit shutdown.
// Written by ChatGPT.
// Updated by ChatGPT: guarded overlap/sky reuse during layout preparation and startup reports.
// Updated by ChatGPT: selected committed visual owners without exposing replacement targets.
// Updated by ChatGPT: bounded rejected-cut expansion and retained explicit coverage failure feedback.
// Updated by Claude: reported per-owner restart, failure, topology and weather churn counters.
// Updated by Claude: reported per-owner edit classification counts.
// Updated by Claude: reported per-owner capture re-reads and requeued coverage columns.
// Updated by Claude: reported per-owner revision bumps by call site.
package dev.brights0ng.meteodyne.heat.world;

import java.util.*;
import static dev.brights0ng.meteodyne.heat.world.LocalAirGeometryCapture.Bounds;

/** Owner-thread coordinator only; HeatWorld supplies the existing capture allowance, executor and wall budget.
 * Dense/sparse transfers commit at a common tick; failed/unadmitted replacements retain their donor owners.
 */
final class LocalAirAutomatic {
    record Settings(int domains,int cells,int coverage,int steps) {
        Settings {
            if(domains<1 || domains>4 || cells<4096 || cells>131072 || coverage<0 || coverage>1_000_000 || steps<1 || steps>64)
                throw new IllegalArgumentException("Automatic limits: domains 1..4, total cells 4096..131072, coverage 0..1000000, steps 1..64");
        }
    }
    record Capture(LocalAirCoverage.World world,LocalAirDependencies dependencies) { }
    interface Factory {
        Capture capture(Bounds bounds);
        LocalAirRun.Weather weather(Bounds bounds);
        default LocalAirThermalState.Provider thermal() { return null; }
        default void save(LocalAirRun.Seed seed) { }
        default boolean loaded(LocalAirPlanner.Tile tile) { return true; }
    }
    static final class Domain {
        final long id;
        final Bounds bounds;
        final Capture capture;
        final LocalAirPreparation preparation;
        long weatherTick=-1;
        boolean wanted;
        LocalAirRun.Seed checkpoint;
        Domain(long id,Bounds bounds,Capture capture,LocalAirPreparation preparation) {
            this.id=id;this.bounds=bounds;this.capture=capture;this.preparation=preparation;
        }
    }
    record Decision(LocalAirPlanner.Tile key,String state,String reason) { }
    record Row(long id,Bounds bounds,boolean retained,String preparation,String run,String reason,long frameTick,long restarts,int airNodes,int faces,String role,int drivenSources,long drivenReads,boolean persistentThermal,int restoredCells,int estimatedCells,int equilibriumIterations,LocalAirStartup.Report startup,long thermalReads,long thermalReused,String progress,
               Map<String,Long> restartCauses,Map<String,Long> failureCauses,long topologyStages,long weatherRevisions,long gridRebuilds,long shortSteps,Map<String,Long> editOutcomes,
               long rereadCells,long requeuedColumns,Map<String,Long> revisionSites) { }
    record Transfer(long generation,long tick,int donors,int targets,double oldEnergy,double newEnergy,double incomingEnergy,double outgoingEnergy,double error) { }
    private static final class Transaction {
        final List<Domain> donors,targets;
        final Map<Domain,Long> attempts=new IdentityHashMap<>();
        final Set<Bounds> desired;
        long barrier=-1;
        boolean seeded;
        Transaction(List<Domain> donors,List<Domain> targets,Set<Bounds> desired) {
            this.donors=List.copyOf(donors);this.targets=List.copyOf(targets);this.desired=Set.copyOf(desired);
            for(var d:donors) attempts.put(d,d.preparation.attempts());for(var d:targets) attempts.put(d,d.preparation.attempts());
        }
    }
    private final Thread thread=Thread.currentThread();
    final Settings settings;
    private final Factory factory;
    private final List<Domain> domains=new ArrayList<>();
    private final List<Domain> retiring=new ArrayList<>();
    private final LocalAirCoverageRecovery recovery=new LocalAirCoverageRecovery();
    private final Map<Bounds,LocalAirCoverage.Failure> rejectedLayouts=new LinkedHashMap<>();
    private final Map<Bounds,String> rejectedPreparations=new LinkedHashMap<>();
    private Map<Bounds,String> coverageNotes=Map.of();
    private Transaction transaction;
    private Transfer lastTransfer;
    private long transfers;
    private double retiredSourceEnergy;
    private String transferState="NONE";
    private List<Decision> decisions=List.of();
    private long nextId;
    LocalAirAutomatic(Settings settings,Factory factory) { this.settings=settings;this.factory=factory; }
    private void owner() { if(Thread.currentThread()!=thread) throw new IllegalStateException("Automatic domains require owner thread"); }
    List<Domain> domains() { owner();var all=new ArrayList<>(domains);if(transaction!=null) all.addAll(transaction.targets);all.addAll(retiring);return List.copyOf(all); }
    List<Domain> committedDomains() { owner();return List.copyOf(domains); }
    Transfer lastTransfer() { owner();return lastTransfer; }
    String transferState() { owner();return transferState; }
    long transfers() { owner();return transfers; }
    double totalSourceEnergy() { owner();double value=retiredSourceEnergy;for(var d:domains) { var frame=d.preparation.field();if(frame!=null) value+=frame.sourceEnergy; }return value; }
    List<Decision> decisions() { owner();return decisions; }
    int cells() { owner();return domains().stream().mapToInt(d->d.bounds.cells()).sum(); }
    void reconcile(LocalAirPlanner.Plan plan,int minY,int maxY) {
        owner();for(var d:domains) d.wanted=false;var result=new ArrayList<Decision>();
        if(!plan.failure().isEmpty()) { cancel("PLAN_LIMIT");decisions=List.of(new Decision(null,"PLAN_LIMIT",plan.failure()));return; }
        for(var d:domains()) {
            var failure=d.preparation.coverageFailure();if(failure!=null) rejectedLayouts.put(d.bounds,failure);else rejectedLayouts.remove(d.bounds);
            var status=d.preparation.status();
            if(status==LocalAirPreparation.Status.REJECTED || status==LocalAirPreparation.Status.FAILED) rejectedPreparations.put(d.bounds,d.preparation.description());else rejectedPreparations.remove(d.bounds);
        }
        var recovered=recovery.update(plan,minY,maxY,rejectedLayouts.entrySet().stream().map(e->new LocalAirCoverageRecovery.Rejection(e.getKey(),e.getValue())).toList(),factory::loaded);
        coverageNotes=recovered.notes();
        var groups=new ArrayList<>(recovered.groups());groups.sort(Comparator.<LocalAirPlanner.Group>comparingInt(LocalAirAutomatic::priority).thenComparing(LocalAirPlanner.Group::key));
        var desired=new LinkedHashMap<Bounds,LocalAirPlanner.Group>();
        for(var g:groups) {
            if(g.state()!=LocalAirPlanner.State.PROPOSED_UNVERIFIED) { result.add(new Decision(g.key(),g.state().name(),"Planner deferred this whole group"));continue; }
            Bounds b;
            try { b=bounds(g,minY,maxY); }
            catch(IllegalArgumentException | ArithmeticException e) { result.add(new Decision(g.key(),"UNSUPPORTED_LAYOUT",e.getMessage()));continue; }
            desired.put(b,g);
        }
        rejectedLayouts.keySet().retainAll(desired.keySet());
        rejectedPreparations.keySet().retainAll(desired.keySet());
        var notes=new HashMap<>(coverageNotes);rejectedPreparations.forEach((b,reason)->notes.putIfAbsent(b,"Coverage blocked: "+reason));coverageNotes=Map.copyOf(notes);
        coverageNotes.forEach((b,message)->result.add(new Decision(desired.get(b)==null?null:desired.get(b).key(),message.startsWith("Coverage blocked")?"COVERAGE_BLOCKED":"COVERAGE_EXPANDED",message)));
        if(transaction!=null && !transaction.desired.equals(desired.keySet())) cancel("PLAN_SUPERSEDED");
        if(transaction!=null) {
            for(var d:domains) d.wanted=desired.containsKey(d.bounds) || transaction.donors.contains(d);
            result.add(new Decision(null,"TRANSFERRING",transferState));decisions=List.copyOf(result);return;
        }
        // A rejected owner cannot supply a sealed transfer seed. Save its last valid checkpoint before rebuilding.
        for(var d:List.copyOf(domains)) if(!desired.containsKey(d.bounds) && d.preparation.coverageFailure()!=null
                && desired.keySet().stream().anyMatch(b->touches(d.bounds,b) && b.cells()<=settings.cells-(cells()-d.bounds.cells()))) retire(d);
        var changed=desired.keySet().stream().filter(b->domains.stream().noneMatch(d->d.bounds.equals(b))).toList();
        var donors=domains.stream().filter(d->changed.stream().anyMatch(b->touches(d.bounds,b))).toList();
        var targetBounds=desired.keySet().stream().filter(b->donors.stream().anyMatch(d->touches(d.bounds,b))).toList();
        if(!donors.isEmpty()) {
            int targetCells=targetBounds.stream().mapToInt(Bounds::cells).sum();
            boolean cap=targetBounds.stream().anyMatch(b->b.cells()>32768) || domains.size()-donors.size()+targetBounds.size()>settings.domains
                    || targetCells>settings.cells-cells() || !retiring.isEmpty();
            if(cap || targetBounds.stream().anyMatch(rejectedPreparations::containsKey)) {
                for(var d:donors) d.wanted=true;
                if(cap) for(var b:targetBounds) coverageNote(b,"Coverage blocked: runtime owner/cell limit or transfer buffer headroom");
                result.add(new Decision(null,cap?"TRANSFER_HEADROOM":"COVERAGE_BLOCKED",cap?"Old and replacement buffers must fit the same total cell allowance; final owner/per-domain limits apply":"Rejected replacement retained until coverage can expand or the world changes"));
            }
            else {
                var targets=new ArrayList<Domain>();
                try {
                    var evidence=donors.stream().map(d->d.preparation.reuseEvidence()).filter(java.util.Objects::nonNull).toList();
                    for(var b:targetBounds) { var d=create(b);d.preparation.reuseEvidence(evidence);d.preparation.awaitLayoutSeed();targets.add(d); }
                    transaction=new Transaction(donors,targets,desired.keySet());transferState="CAPTURING_TARGETS";
                    for(var d:donors) d.wanted=true;
                    result.add(new Decision(null,"TRANSFERRING",transferState));
                } catch(RuntimeException e) { for(var d:targets) d.preparation.close();result.add(new Decision(null,"CAPTURE_UNAVAILABLE",e.toString())); }
            }
        }
        for(var entry:desired.entrySet()) {
            var b=entry.getKey();var g=entry.getValue();
            var existing=domains.stream().filter(d->d.bounds.equals(b)).findFirst().orElse(null);
            if(existing!=null) { existing.wanted=true;result.add(new Decision(g.key(),"OWNED","Domain "+existing.id+" allocated; "+existing.preparation.description()));continue; }
            if(targetBounds.contains(b)) continue;
            if(domains.stream().anyMatch(d->touches(d.bounds,b))) {
                result.add(new Decision(g.key(),"TRANSFER_PENDING","Resize/merge/split requires retained-state transfer; existing owners retained"));continue;
            }
            int finalOwners=transaction==null?domains.size():domains.size()-transaction.donors.size()+transaction.targets.size();
            if(finalOwners>=settings.domains || b.cells()>settings.cells-cells() || b.cells()>LocalAirCommand.CELL_CAP) {
                coverageNote(b,"Coverage blocked: runtime owner/cell limit includes retained and replacement buffers");
                result.add(new Decision(g.key(),"RUNTIME_LIMIT","Owner/cell allowance includes retained domains; per-domain cap 32768"));continue;
            }
            try {
                var d=create(b);d.wanted=true;domains.add(d);
                result.add(new Decision(g.key(),"OWNED","Domain "+d.id));
            } catch(RuntimeException e) { result.add(new Decision(g.key(),"CAPTURE_UNAVAILABLE",e.toString())); }
        }
        for(var d:List.copyOf(domains)) if(factory.thermal()!=null && !d.wanted && desired.keySet().stream().noneMatch(b->touches(d.bounds,b))) {
            retire(d);
        }
        decisions=List.copyOf(result);
    }
    private void retire(Domain d) {
        var seed=d.preparation.thermalCheckpoint();if(seed!=null) d.checkpoint=seed;
        if(d.checkpoint!=null) { factory.save(d.checkpoint);retiredSourceEnergy+=d.checkpoint.frame().sourceEnergy; }
        d.preparation.close();domains.remove(d);if(d.preparation.inFlight()) retiring.add(d);
    }
    private void coverageNote(Bounds b,String note) {
        var copy=new HashMap<>(coverageNotes);copy.put(b,note+"; "+copy.getOrDefault(b,""));coverageNotes=Map.copyOf(copy);
    }
    private Domain create(Bounds b) {
        var weather=factory.weather(b);var capture=factory.capture(b);
        var prep=new LocalAirPreparation(capture.world,b,b.cells(),settings.coverage,
                new LocalAirRun.Settings(weather.ambient(),weather.windX(),weather.windZ(),settings.steps),16);
        if(factory.thermal()!=null) prep.thermalInitialization(factory.thermal());
        prep.invalidationCause(capture.dependencies()::lastCause);prep.revisionSites(capture.dependencies()::revisionSites);
        return new Domain(++nextId,b,capture,prep);
    }
    void beforeEdit(int x,int y,int z) {
        owner();rejectedLayouts.clear();rejectedPreparations.clear();recovery.retryBlocked();
        if(transaction!=null && domains().stream().anyMatch(d->d.capture.dependencies.affectsBlock(x,y,z))) cancel("WORLD_EDIT");
    }
    private void cancel(String reason) {
        if(transaction==null) return;
        for(var d:transaction.donors) d.preparation.resumeLayout();
        for(var d:transaction.targets) { d.preparation.close();if(d.preparation.inFlight()) retiring.add(d); }
        transaction=null;transferState="CANCELLED: "+reason;
        decisions=List.of(new Decision(null,"CANCELLED",reason));
    }
    private void progress(long tick,double scale) {
        var tx=transaction;if(tx==null) return;
        for(var entry:tx.attempts.entrySet()) {
            var p=entry.getKey().preparation;var status=p.status();
            if(p.attempts()!=entry.getValue() || status==LocalAirPreparation.Status.REJECTED || status==LocalAirPreparation.Status.FAILED) {
                var failure=p.coverageFailure();if(failure!=null) rejectedLayouts.put(entry.getKey().bounds,failure);
                if(status==LocalAirPreparation.Status.REJECTED || status==LocalAirPreparation.Status.FAILED) rejectedPreparations.put(entry.getKey().bounds,p.description());
                cancel("STALE_OR_REJECTED");return;
            }
        }
        if(tx.barrier<0) {
            if(tx.targets.stream().anyMatch(d->d.preparation.published()==null) || tx.donors.stream().anyMatch(d->!d.preparation.readyForLayout())) return;
            tx.barrier=tick;
            for(var d:tx.donors) if(!d.preparation.holdLayout(tick,scale)) { cancel("DONOR_NOT_READY");return; }
            for(var d:tx.targets) if(!d.preparation.holdLayout(tick,scale)) { cancel("TARGET_NOT_READY");return; }
            transferState="SEALING_DONORS";
        }
        if(tick-tx.barrier>LocalAirRun.MAX_LAG_TICKS) { cancel("LAG_LIMIT");return; }
        if(!tx.seeded) {
            var seeds=new ArrayList<LocalAirRun.Seed>();
            for(var d:tx.donors) { var seed=d.preparation.layoutCheckpoint();if(seed==null) return;seeds.add(seed); }
            var seed=LocalAirRun.LayoutSeed.from(seeds);for(var d:tx.targets) d.preparation.installLayoutSeed(seed);
            tx.seeded=true;transferState="PROJECTING_TARGETS";return;
        }
        var frames=new ArrayList<LocalAirRun.Frame>();
        for(var d:tx.targets) { var seed=d.preparation.layoutCheckpoint();if(seed==null) return;frames.add(seed.frame()); }
        double oldEnergy=0,source=0;for(var d:tx.donors) { var frame=d.preparation.layoutCheckpoint().frame();oldEnergy+=frame.energy;source+=frame.sourceEnergy; }
        double newEnergy=0,retained=0,incoming=0;for(var frame:frames) { newEnergy+=frame.energy;retained+=frame.layoutRetainedEnergy;incoming+=frame.layoutIncomingEnergy; }
        double outgoing=oldEnergy-retained,error=newEnergy-retained-incoming;
        if(!Double.isFinite(error) || Math.abs(error)>1e-6) { cancel("ENERGY_CHECK_FAILED");return; }
        lastTransfer=new Transfer(++transfers,tx.barrier,tx.donors.size(),tx.targets.size(),oldEnergy,newEnergy,incoming,outgoing,error);
        retiredSourceEnergy+=source;
        for(var d:tx.donors) factory.save(d.preparation.layoutCheckpoint());
        for(var d:tx.donors) { d.preparation.close();domains.remove(d); }
        for(var d:tx.targets) { d.wanted=true;d.preparation.resumeLayout();domains.add(d); }
        transaction=null;transferState="COMMITTED";
        decisions=List.of(new Decision(null,"COMMITTED",lastTransfer.toString()));
    }
    private static int priority(LocalAirPlanner.Group g) {
        int p=2;for(var e:g.requests()) if(e.active()) p=Math.min(p,e.request().key().kind()==LocalAirDemandRegistry.Kind.PLAYER?0:1);return p;
    }
    static Bounds bounds(LocalAirPlanner.Group group,int minY,int maxY) {
        if(group.tiles().isEmpty()) throw new IllegalArgumentException("Empty group");
        int lx=Integer.MAX_VALUE,ly=lx,lz=lx,hx=Integer.MIN_VALUE,hy=hx,hz=hx;
        var unique=new HashSet<LocalAirPlanner.Tile>();
        for(var t:group.tiles()) {
            if(!unique.add(t)) throw new IllegalArgumentException("Duplicate tile");
            lx=Math.min(lx,t.x());ly=Math.min(ly,t.y());lz=Math.min(lz,t.z());hx=Math.max(hx,t.x());hy=Math.max(hy,t.y());hz=Math.max(hz,t.z());
        }
        int edge=LocalAirPlanner.TILE_EDGE,x=Math.multiplyExact(lx,edge),y=Math.multiplyExact(ly,edge),z=Math.multiplyExact(lz,edge);
        int[] coords=new int[group.tiles().size()*3];int cursor=0;
        for(var t:group.tiles()) { coords[cursor++]=t.x()-lx;coords[cursor++]=t.y()-ly;coords[cursor++]=t.z()-lz; }
        var layout=dev.brights0ng.paradynelib.heat.air.CellLayout.tiles(edge,coords);
        int nx=layout.nx,ny=layout.ny,nz=layout.nz;
        if(y<minY || (long)y+ny>maxY) throw new IllegalArgumentException("Partial-height tiles are not supported");
        return new Bounds(x,y,z,nx,ny,nz,layout.dense()?null:layout);
    }
    /** Reject overlap and face contact with retained owners: a seam cannot become two exterior boundaries. */
    static boolean touches(Bounds a,Bounds b) {
        if(a.sparse()!=null || b.sparse()!=null) {
            var ta=a.tiles();var tb=b.tiles();
            for(int i=0;i<ta.length;i+=3) for(int j=0;j<tb.length;j+=3) {
                long dx=Math.abs((long)a.x()+ta[i]-b.x()-tb[j]),dy=Math.abs((long)a.y()+ta[i+1]-b.y()-tb[j+1]),dz=Math.abs((long)a.z()+ta[i+2]-b.z()-tb[j+2]);
                if(dx+dy+dz<=16) return true;
            }
            return false;
        }
        long[] lowA={a.x(),a.y(),a.z()},lowB={b.x(),b.y(),b.z()};
        long[] highA={(long)a.x()+a.nx(),(long)a.y()+a.ny(),(long)a.z()+a.nz()},highB={(long)b.x()+b.nx(),(long)b.y()+b.ny(),(long)b.z()+b.nz()};
        int strict=0;
        for(int i=0;i<3;i++) {
            if(highA[i]<lowB[i] || highB[i]<lowA[i]) return false;
            if(highA[i]>lowB[i] && highB[i]>lowA[i]) strict++;
        }
        return strict>=2;
    }
    void observe(long tick,double scale) {
        owner();retiring.removeIf(d->!d.preparation.inFlight());for(var d:domains()) {
            if(retiring.contains(d)) continue;
            if(d.weatherTick<0 || tick-d.weatherTick>=LocalAirRun.PHYSICAL_TICKS) {
                d.weatherTick=tick;
                try { d.preparation.changeWeather(tick,factory.weather(d.bounds),scale); }
                catch(RuntimeException e) { d.preparation.weatherUnavailable(e.toString()); }
            }
            d.preparation.observe(tick,scale);
            if(domains.contains(d)) { var seed=d.preparation.thermalCheckpoint();if(seed!=null) d.checkpoint=seed; }
        }
        progress(tick,scale);
    }
    LocalAirReading query(long tick,double x,double y,double z,long maxAge) {
        return presentation(tick,x,y,z,maxAge).reading();
    }
    LocalAirPresentation presentation(long tick,double x,double y,double z,long maxAge) {
        owner();LocalAirReading.validate(tick,x,y,z,maxAge);
        for(var d:domains) if(contains(d.bounds,x,y,z)) {
            var data=d.wanted?d.preparation.presentation(tick,x,y,z,maxAge):LocalAirPresentation.missing(LocalAirReading.Status.PENDING,LocalAirReading.Reason.REFRESHING);
            String note=coverageNotes.entrySet().stream().filter(e->contains(e.getKey(),x,y,z)).map(Map.Entry::getValue).findFirst().orElse("");
            return note.isEmpty() || data.reading().currentSample().isPresent()?data:data.detail(note.startsWith("Coverage blocked")?note:data.detail()+"; enlarged coverage, "+d.bounds.cells()/4096+" tiles");
        }
        if(transaction!=null) for(var d:transaction.targets) if(contains(d.bounds,x,y,z)) return LocalAirPresentation.missing(LocalAirReading.Status.PENDING,LocalAirReading.Reason.PREPARING).detail(d.preparation.description());
        for(var entry:coverageNotes.entrySet()) if(contains(entry.getKey(),x,y,z)) return LocalAirPresentation.missing(LocalAirReading.Status.UNAVAILABLE,LocalAirReading.Reason.REJECTED).detail(entry.getValue());
        return LocalAirPresentation.missing(LocalAirReading.Status.UNAVAILABLE,LocalAirReading.Reason.OUTSIDE_DOMAIN).detail("Outside allocated coverage; inspect air auto and air plan");
    }
    private static boolean contains(Bounds b,double x,double y,double z) { return b.contains(x,y,z); }
    List<Row> rows() {
        owner();var rows=new ArrayList<Row>();
        for(var d:domains()) {
            var frame=d.preparation.field();var run=d.preparation.runStatus();
            var geometry=d.preparation.published();
            String role=retiring.contains(d)?"RETIRING":transaction!=null && transaction.targets.contains(d)?"TARGET":d.wanted?"COMMITTED":"RETAINED";
            rows.add(new Row(d.id,d.bounds,domains.contains(d) && !d.wanted,d.preparation.status().name(),run==null?"UNAVAILABLE":run.name(),d.preparation.runReason(),frame==null?-1:frame.throughTick,d.preparation.restarts(),geometry==null?-1:geometry.flow().nodes(),geometry==null?-1:geometry.flow().faces(),role,d.preparation.drivenSourceCells(),d.preparation.drivenReadCells(),frame!=null && frame.persistentThermal,frame==null?0:frame.restoredCells,frame==null?0:frame.estimatedCells,d.preparation.estimationQuality()==null?0:d.preparation.estimationQuality().iterations(),d.preparation.startup(),d.preparation.thermalReadCells(),d.preparation.thermalReusedCells(),d.preparation.description(),
                    d.preparation.restartCauses(),d.preparation.failureCauses(),d.preparation.topologyStages(),d.preparation.weatherRevisions(),frame==null?0:frame.gridRebuilds,frame==null?0:frame.shortSteps,d.preparation.editOutcomes(),
                    d.preparation.rereadCells(),d.preparation.requeuedColumns(),d.preparation.revisionSites()));
        }
        return List.copyOf(rows);
    }
    List<LocalAirRun.Seed> thermalCheckpoints() {
        owner();var out=new ArrayList<LocalAirRun.Seed>();
        for(var d:domains) { var seed=d.preparation.thermalCheckpoint();if(seed!=null) d.checkpoint=seed;if(d.checkpoint!=null) out.add(d.checkpoint); }return List.copyOf(out);
    }
    void persist() { owner();for(var seed:thermalCheckpoints()) factory.save(seed); }
    void close() { owner();persist();for(var d:domains()) d.preparation.close();domains.clear();retiring.clear();transaction=null;decisions=List.of();rejectedLayouts.clear();rejectedPreparations.clear();coverageNotes=Map.of();recovery.clear(); }
}
