// Written by ChatGPT.
// Updated by ChatGPT: identify rejected boundary cuts by world position and box face.
// Updated by ChatGPT: reused live donor sky proofs with target dependency registration.
// Updated by ChatGPT: interleaved column proofs and reported recoverable boundary cuts.
// Updated by ChatGPT: consumed shared suffix/obstruction evidence and exact empty-section proofs.
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
        /** Live sources for one world share this index; historical sources must return null. */
        default LocalAirSkyCache skyCache() { return null; }
        /** True only for a loaded section proven to contain exclusively supported, empty vanilla air. */
        default boolean emptySkySection(int chunkX,int sectionY,int chunkZ) { return false; }
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
    record Cut(int x,int y,int z,int axis,int side) { }
    record Failure(Cut cut,int x,int y,int z,String reason) { }
    private record Start(Column column,int y,Cut cut) { }
    /** Full empty-column proof; null read evidence deliberately disables reuse. */
    private record Proof(int lowY,ReadBounds reads) { }
    static final class Plan {
        private final Snapshot snapshot;
        private final Start[] starts;
        private final String rejection;
        private final Failure failure;
        private Plan(Snapshot snapshot,Start[] starts,String rejection,Failure failure) {
            this.snapshot=snapshot;this.starts=starts;this.rejection=rejection;this.failure=failure;
        }
    }
    /** Pure boundary planning. Whole-cell air is deliberately required at admitted air cuts.
     * Partial cells with internal air can conservatively reject even if that air misses the outer face.
     */
    static Plan plan(Snapshot s) {
        var b=s.bounds;var columns=new LinkedHashMap<Column,Start>();
        var cache=new HashMap<Shape,FlowShape>();String rejection="";Failure failure=null;
        outer: for(int cell=0;cell<b.cells();cell++) {
            int x=b.cellX(cell),y=b.cellY(cell),z=b.cellZ(cell);boolean boundary=false;
            for(int a=0;a<3;a++) for(int side=0;side<2;side++) boundary|=b.neighbor(cell,a,side)<0;
            if(!boundary) continue;
            var shape=s.shape(x,y,z);
            if(!shape.empty() && cache.computeIfAbsent(shape,Shape::bake).components()==0) continue;
            int wx=b.x()+x,wy=b.y()+y,wz=b.z()+z;
            if(b.neighbor(cell,1,0)<0) {
                rejection="Air reaches bottom coverage cut"+cutLocation(b,x,y,z)+". Move the bottom into full solid ground";
                failure=new Failure(new Cut(wx,wy,wz,1,0),wx,wy,wz,rejection);break outer;
            }
            if(!shape.empty()) {
                rejection="Partial boundary air requires a larger domain"+cutLocation(b,x,y,z)+". Move the box face past this cell onto full solid or full outdoor air";
                for(int a=0;a<3 && failure==null;a++) for(int side=0;side<2;side++) if(b.neighbor(cell,a,side)<0) {
                    failure=new Failure(new Cut(wx,wy,wz,a,side),wx,wy,wz,rejection);break;
                }
                break outer;
            }
            try {
                for(int a=0;a<3;a++) for(int side=0;side<2;side++) if(b.neighbor(cell,a,side)<0) {
                    var cut=new Cut(wx,wy,wz,a,side);addColumn(columns,wx,wy,wz,cut);
                    int delta=side==0?-1:1;
                    if(a==0) addColumn(columns,Math.addExact(wx,delta),wy,wz,cut);
                    if(a==2) addColumn(columns,wx,wy,Math.addExact(wz,delta),cut);
                }
            } catch(ArithmeticException e) { rejection="Exterior coordinates overflow";break outer; }
        }
        var starts=new Start[columns.size()];int i=0;
        for(var entry:columns.values()) starts[i++]=entry;
        return new Plan(s,starts,rejection,failure);
    }
    private static void addColumn(LinkedHashMap<Column,Start> columns,int x,int y,int z,Cut cut) {
        var column=new Column(x,z);var next=new Start(column,y,cut);
        columns.merge(column,next,(a,b)->a.y<=b.y?a:b);
    }
    private static String cutLocation(Bounds b,int x,int y,int z) {
        var faces=new java.util.ArrayList<String>();
        int cell=b.index(x,y,z);
        if(b.neighbor(cell,0,0)<0) faces.add("minX");if(b.neighbor(cell,0,1)<0) faces.add("maxX");
        if(b.neighbor(cell,1,0)<0) faces.add("minY");if(b.neighbor(cell,1,1)<0) faces.add("maxY");
        if(b.neighbor(cell,2,0)<0) faces.add("minZ");if(b.neighbor(cell,2,1)<0) faces.add("maxZ");
        return " at block ("+(b.x()+x)+", "+(b.y()+y)+", "+(b.z()+z)+"), face(s) "+String.join("/",faces);
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
    private final java.util.List<LocalAirReuse> reusable;
    private final java.util.Map<Column,Proof> proofs=new HashMap<>();
    private static final class Cursor {
        final Start start;
        int y;
        ReadBounds observed;
        boolean knownReads=true;
        boolean indexed,fallback;
        int proven;
        Cursor(Start start) { this.start=start;this.y=start.y; }
    }
    private final java.util.ArrayDeque<Cursor> pending=new java.util.ArrayDeque<>();
    private int reads,reused,bulk;
    private long required,verified;
    private Failure failure;
    private Status status=Status.CHECKING;
    private String reason="";
    LocalAirCoverage(Plan plan,World world,int readLimit) {
        this(plan,world,readLimit,null,java.util.Set.of());
    }
    /** Caller supplies the complete classified edit history since the preceding admitted snapshot.
     * Request-lifetime world dependencies must remain registered when proofs are reused.
     */
    LocalAirCoverage(Plan plan,World world,int readLimit,Certificate previous,java.util.Set<Long> edits) {
        this(plan,world,readLimit,previous,edits,java.util.List.of());
    }
    LocalAirCoverage(Plan plan,World world,int readLimit,Certificate previous,java.util.Set<Long> edits,java.util.List<LocalAirReuse> reusable) {
        this.plan=java.util.Objects.requireNonNull(plan);this.world=java.util.Objects.requireNonNull(world);
        minY=world.minBuildY();maxY=world.maxBuildY();
        this.edits=java.util.Set.copyOf(edits);this.reusable=java.util.List.copyOf(reusable);
        var snapshot=plan.snapshot;
        baseline=previous!=null && snapshot.bounds.equals(previous.snapshot.bounds)
                && snapshot.contextId==previous.snapshot.contextId && snapshot.materialRevision==previous.snapshot.materialRevision
                && snapshot.geometryRevision>=previous.snapshot.geometryRevision && minY==previous.minY && maxY==previous.maxY
                ?previous.proofs:java.util.Map.of();
        if(readLimit<0) throw new IllegalArgumentException("Nonnegative coverage limit required");
        var b=plan.snapshot.bounds;
        if(!current()) reject(Status.STALE,"Revisions changed before coverage capture");
        else if(!plan.rejection.isEmpty()) { failure=plan.failure;reject(Status.INSUFFICIENT_COVERAGE,plan.rejection); }
        else if(b.y()<minY || (long)b.y()+b.ny()>maxY) reject(Status.MISSING_GEOMETRY,"Domain exceeds build height");
        else {
            for(var start:plan.starts) required+=(long)maxY-start.y;
            if(required>readLimit) reject(Status.LIMIT_EXCEEDED,"Open-sky evidence exceeds coverage read admission limit");
            else if(plan.starts.length==0) status=Status.ADMITTED;
            else for(var start:plan.starts) pending.addLast(new Cursor(start));
        }
    }
    Status status() { return status; }
    String reason() { return reason; }
    Failure failure() { return failure; }
    String progress() { return "Checking open-sky coverage: "+verified+" / "+required+" cells; "+proofs.size()+" / "+plan.starts.length+" columns"; }
    int sampledCells() { return reads; }
    int reusedCells() { return reused; }
    int bulkCells() { return bulk; }
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
            var cursor=pending.removeFirst();var start=cursor.start;int y=cursor.y;
            var prior=baseline.get(start.column);
            if(!cursor.indexed && y==start.y && prior==null) for(var lease:reusable) {
                if(!lease.accepts(world) || lease.coverage==null || lease.coverage.minY!=minY || lease.coverage.maxY!=maxY) continue;
                var candidate=lease.coverage.proofs.get(start.column);
                if(candidate!=null && candidate.lowY<=start.y && candidate.reads!=null && candidate.reads.unchanged(lease.edits)) {
                    world.retainReads(candidate.reads);prior=candidate;break;
                }
            }
            if(!cursor.indexed && y==start.y && prior!=null && prior.lowY<=start.y && prior.reads!=null && prior.reads.unchanged(edits)) {
                proofs.put(start.column,prior);reused+=maxY-start.y;verified+=maxY-start.y;finishColumn();continue;
            }
            if(!cursor.fallback && world.skyCache()!=null) {
                used++;cursor.indexed=true;
                try {
                    var result=world.skyCache().advance(world,start.column.x,start.y,start.column.z);
                    reads+=result.samples()+result.bulkCells();bulk+=result.bulkCells();
                    if(result.reads()!=null) world.retainReads(result.reads());
                    if(!current()) { reject(Status.STALE,"Revision changed during indexed coverage read");break; }
                    if(!result.current()) { verified-=cursor.proven;cursor.proven=0;pending.addLast(cursor);continue; }
                    int proven=maxY-Math.max(start.y,result.clearLow());verified+=proven-cursor.proven;cursor.proven=proven;
                    if(result.state()==LocalAirSkyCache.State.CLEAR) {
                        proofs.put(start.column,new Proof(start.y,result.reads()));if(result.hit()) reused+=maxY-start.y;
                        finishColumn();continue;
                    }
                    if(result.state()==LocalAirSkyCache.State.BLOCKED) { obstructed(start,result.obstruction());break; }
                    if(result.state()==LocalAirSkyCache.State.FALLBACK) { cursor.fallback=true;verified-=cursor.proven;cursor.proven=0; }
                    pending.addLast(cursor);continue;
                } catch(Unavailable e) { unavailable(e);break; }
            }
            used++;reads++;
            try {
                var cell=world.sample(start.column.x,y,start.column.z);
                if(!current()) { reject(Status.STALE,"Revision changed during coverage read");break; }
                if(!cell.shape().empty()) {
                    obstructed(start,y);break;
                }
                var footprint=world.lastReads();
                var box=footprint==null?null:footprint.enclose(start.column.x,y,start.column.z);
                if(box==null) cursor.knownReads=false;
                else cursor.observed=cursor.observed==null?box:cursor.observed.union(box);
                cursor.y++;verified++;
                if(cursor.y==maxY) { proofs.put(start.column,new Proof(start.y,cursor.knownReads?cursor.observed:null));finishColumn(); }
                else pending.addLast(cursor);
            } catch(Unavailable e) {
                unavailable(e);
            }
        }
        return used;
    }
    private void unavailable(Unavailable e) { reject(e.status==LocalAirGeometryCapture.Status.MISSING_GEOMETRY?Status.MISSING_GEOMETRY:Status.UNSUPPORTED_GEOMETRY,e.getMessage()); }
    private void obstructed(Start start,int y) {
        String message="Open boundary column is obstructed at block ("+start.column.x+", "+y+", "+start.column.z+") from cut ("+start.cut.x+", "+start.cut.y+", "+start.cut.z+"), "+face(start.cut);
        failure=new Failure(start.cut,start.column.x,y,start.column.z,message);reject(Status.INSUFFICIENT_COVERAGE,message);
    }
    static String face(Cut cut) { return (cut.side==0?"min":"max")+"XYZ".charAt(cut.axis); }
    private void finishColumn() { if(pending.isEmpty()) status=Status.ADMITTED; }
    Certificate certificate() {
        checkOwner();if(!current()) reject(Status.STALE,"Revision changed before coverage handoff");
        if(status!=Status.ADMITTED) throw new IllegalStateException("Coverage unavailable: "+status+" "+reason);
        return new Certificate(plan.snapshot,minY,maxY,proofs);
    }
}
