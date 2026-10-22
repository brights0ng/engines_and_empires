// Written by ChatGPT.
package dev.brights0ng.meteodyne.heat.world;

import java.util.HashMap;
import java.util.LinkedHashMap;
import dev.brights0ng.paradynelib.heat.air.FlowShape;
import dev.brights0ng.paradynelib.heat.air.ThermalGeometry;
import static dev.brights0ng.meteodyne.heat.world.LocalAirGeometryCapture.*;

/** Conservative open-sky boundary evidence, not a numerical domain-size qualification.
 * Planning/baking are worker-safe; world reads and certificate handoff stay on the owner thread.
 */
final class LocalAirCoverage {
    interface World extends Source {
        int minBuildY();
        int maxBuildY(); // exclusive; obtained from the same world as the samples
        /** Owner-thread historical reads; implementations must reconstruct context as well as cell state. */
        default History freeze() { return null; }
    }
    interface History extends AutoCloseable {
        World world();
        /** Release world-read retention after coverage capture or retirement. Revision identity remains readable. */
        @Override void close();
    }
    enum Status { CHECKING, ADMITTED, INSUFFICIENT_COVERAGE, MISSING_GEOMETRY, UNSUPPORTED_GEOMETRY, STALE, LIMIT_EXCEEDED }
    private record Column(int x,int z) { }
    private record Start(Column column,int y) { }
    /** Full empty-column proof; null read evidence deliberately disables reuse. */
    private record Proof(int lowY,ReadBounds reads) { }
    static final class Plan {
        private final Snapshot snapshot;
        private final Start[] starts;
        private final String rejection;
        private Plan(Snapshot snapshot,Start[] starts,String rejection) {
            this.snapshot=snapshot;this.starts=starts;this.rejection=rejection;
        }
    }
    /** Pure boundary planning. Whole-cell air is deliberately required at admitted air cuts.
     * Partial cells with internal air can conservatively reject even if that air misses the outer face.
     */
    static Plan plan(Snapshot s) {
        var b=s.bounds;var columns=new LinkedHashMap<Column,Integer>();
        var cache=new HashMap<Shape,FlowShape>();String rejection="";
        outer: for(int z=0;z<b.nz();z++) for(int y=0;y<b.ny();y++) for(int x=0;x<b.nx();x++) {
            if(x!=0 && x!=b.nx()-1 && y!=0 && y!=b.ny()-1 && z!=0 && z!=b.nz()-1) continue;
            var shape=s.shape(x,y,z);
            if(!shape.empty() && cache.computeIfAbsent(shape,Shape::bake).components()==0) continue;
            if(y==0) { rejection="Air reaches bottom coverage cut";break outer; }
            if(!shape.empty()) { rejection="Partial boundary air requires a larger domain";break outer; }
            int wx=b.x()+x,wy=b.y()+y,wz=b.z()+z;
            columns.merge(new Column(wx,wz),wy,Math::min);
            try {
                if(x==0) columns.merge(new Column(Math.subtractExact(wx,1),wz),wy,Math::min);
                if(x==b.nx()-1) columns.merge(new Column(Math.addExact(wx,1),wz),wy,Math::min);
                if(z==0) columns.merge(new Column(wx,Math.subtractExact(wz,1)),wy,Math::min);
                if(z==b.nz()-1) columns.merge(new Column(wx,Math.addExact(wz,1)),wy,Math::min);
            } catch(ArithmeticException e) { rejection="Exterior coordinates overflow";break outer; }
        }
        var starts=new Start[columns.size()];int i=0;
        for(var entry:columns.entrySet()) starts[i++]=new Start(entry.getKey(),entry.getValue());
        return new Plan(s,starts,rejection);
    }
    static final class Certificate {
        private final Snapshot snapshot;
        private final int minY,maxY;
        private final java.util.Map<Column,Proof> proofs;
        private Certificate(Snapshot snapshot,int minY,int maxY,java.util.Map<Column,Proof> proofs) {
            this.snapshot=snapshot;this.minY=minY;this.maxY=maxY;this.proofs=java.util.Map.copyOf(proofs);
        }
        boolean current(World world) {
            return world.contextId()==snapshot.contextId && world.geometryRevision()==snapshot.geometryRevision && world.materialRevision()==snapshot.materialRevision
                    && world.minBuildY()==minY && world.maxBuildY()==maxY;
        }
        boolean unaffectedBy(long edit) {
            var changes=java.util.Set.of(edit);
            for(var proof:proofs.values()) if(proof.reads==null || !proof.reads.unchanged(changes)) return false;
            return true;
        }
        Prepared bake(Snapshot snapshot,ThermalGeometry.Coefficients coefficients) {
            if(snapshot!=this.snapshot) throw new IllegalArgumentException("Certificate belongs to another snapshot");
            return snapshot.bakeAdmitted(coefficients,this);
        }
    }
    static boolean matches(Certificate certificate,Snapshot snapshot) { return certificate.snapshot==snapshot; }
    private final Thread owner=Thread.currentThread();
    private final Plan plan;
    private final World world;
    private final int minY,maxY;
    private final java.util.Map<Column,Proof> baseline;
    private final java.util.Set<Long> edits;
    private final java.util.Map<Column,Proof> proofs=new HashMap<>();
    private int column,y,reads,reused;
    private ReadBounds observed;
    private boolean knownReads=true;
    private Status status=Status.CHECKING;
    private String reason="";
    LocalAirCoverage(Plan plan,World world,int readLimit) {
        this(plan,world,readLimit,null,java.util.Set.of());
    }
    /** Caller supplies the complete classified edit history since the preceding admitted snapshot.
     * Request-lifetime world dependencies must remain registered when proofs are reused.
     */
    LocalAirCoverage(Plan plan,World world,int readLimit,Certificate previous,java.util.Set<Long> edits) {
        this.plan=java.util.Objects.requireNonNull(plan);this.world=java.util.Objects.requireNonNull(world);
        minY=world.minBuildY();maxY=world.maxBuildY();
        this.edits=java.util.Set.copyOf(edits);
        var snapshot=plan.snapshot;
        baseline=previous!=null && snapshot.bounds.equals(previous.snapshot.bounds)
                && snapshot.contextId==previous.snapshot.contextId && snapshot.materialRevision==previous.snapshot.materialRevision
                && snapshot.geometryRevision>=previous.snapshot.geometryRevision && minY==previous.minY && maxY==previous.maxY
                ?previous.proofs:java.util.Map.of();
        if(readLimit<0) throw new IllegalArgumentException("Nonnegative coverage limit required");
        var b=plan.snapshot.bounds;
        if(!current()) reject(Status.STALE,"Revisions changed before coverage capture");
        else if(!plan.rejection.isEmpty()) reject(Status.INSUFFICIENT_COVERAGE,plan.rejection);
        else if(b.y()<minY || (long)b.y()+b.ny()>maxY) reject(Status.MISSING_GEOMETRY,"Domain exceeds build height");
        else {
            long required=0;
            for(var start:plan.starts) required+=(long)maxY-start.y;
            if(required>readLimit) reject(Status.LIMIT_EXCEEDED,"Open-sky evidence exceeds coverage read admission limit");
            else if(plan.starts.length==0) status=Status.ADMITTED;
            else y=plan.starts[0].y;
        }
    }
    Status status() { return status; }
    String reason() { return reason; }
    int sampledCells() { return reads; }
    int reusedCells() { return reused; }
    private void reject(Status status,String reason) { this.status=status;this.reason=reason; }
    private void checkOwner() { if(Thread.currentThread()!=owner) throw new IllegalStateException("Coverage capture requires owner thread"); }
    private boolean current() {
        return world.contextId()==plan.snapshot.contextId && world.geometryRevision()==plan.snapshot.geometryRevision && world.materialRevision()==plan.snapshot.materialRevision
                && world.minBuildY()==minY && world.maxBuildY()==maxY;
    }
    int advance(int allowance,long deadlineNanos) {
        checkOwner();if(allowance<0) throw new IllegalArgumentException("Nonnegative coverage allowance required");
        if((status==Status.CHECKING || status==Status.ADMITTED) && !current()) reject(Status.STALE,"Coverage revisions changed");
        int used=0;
        while(status==Status.CHECKING && used<allowance && System.nanoTime()<deadlineNanos) {
            var start=plan.starts[column];
            var prior=baseline.get(start.column);
            if(y==start.y && prior!=null && prior.lowY<=start.y && prior.reads!=null && prior.reads.unchanged(edits)) {
                proofs.put(start.column,prior);reused+=maxY-start.y;nextColumn();continue;
            }
            used++;reads++;
            try {
                var cell=world.sample(start.column.x,y,start.column.z);
                if(!current()) { reject(Status.STALE,"Revision changed during coverage read");break; }
                if(!cell.shape().empty()) { reject(Status.INSUFFICIENT_COVERAGE,"Open boundary column is obstructed");break; }
                var footprint=world.lastReads();
                var box=footprint==null?null:footprint.enclose(start.column.x,y,start.column.z);
                if(box==null) knownReads=false;
                else observed=observed==null?box:observed.union(box);
                y++;
                if(y==maxY) {
                    proofs.put(start.column,new Proof(start.y,knownReads?observed:null));nextColumn();
                }
            } catch(Unavailable e) {
                reject(e.status==LocalAirGeometryCapture.Status.MISSING_GEOMETRY?Status.MISSING_GEOMETRY:Status.UNSUPPORTED_GEOMETRY,e.getMessage());
            }
        }
        return used;
    }
    private void nextColumn() {
        observed=null;knownReads=true;column++;
        if(column==plan.starts.length) status=Status.ADMITTED;else y=plan.starts[column].y;
    }
    Certificate certificate() {
        checkOwner();if(!current()) reject(Status.STALE,"Revision changed before coverage handoff");
        if(status!=Status.ADMITTED) throw new IllegalStateException("Coverage unavailable: "+status+" "+reason);
        return new Certificate(plan.snapshot,minY,maxY,proofs);
    }
}
