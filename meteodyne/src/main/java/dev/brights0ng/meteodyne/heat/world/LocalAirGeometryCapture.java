// Written by ChatGPT.
package dev.brights0ng.meteodyne.heat.world;

import java.util.Arrays;
import java.util.HashMap;
import java.util.ArrayList;
import dev.brights0ng.paradynelib.heat.air.FlowGeometry;
import dev.brights0ng.paradynelib.heat.air.FlowShape;
import dev.brights0ng.paradynelib.heat.air.ThermalGeometry;

/** Main-thread sliced capture; worker snapshots contain no world/Minecraft objects.
 * Complete geometry is distinct from verified exterior coverage and published field readiness.
 */
final class LocalAirGeometryCapture {
    enum Status { CAPTURING, CAPTURED, MISSING_GEOMETRY, UNSUPPORTED_GEOMETRY, STALE }
    record Bounds(int x,int y,int z,int nx,int ny,int nz) {
        Bounds {
            if(nx<1 || ny<1 || nz<1) { throw new IllegalArgumentException("Positive dimensions required"); }
            Math.addExact(x,nx-1);Math.addExact(y,ny-1);Math.addExact(z,nz-1);
            Math.multiplyExact(Math.multiplyExact(nx,ny),nz);
        }
        int cells() { return Math.multiplyExact(Math.multiplyExact(nx,ny),nz); }
    }
    interface Source {
        long contextId(); // Unique capture-source identity; never reused across worlds/source lifetimes.
        long geometryRevision();
        long materialRevision();
        Cell sample(int x,int y,int z);
        /** Immutable block reads from the last successful sample; absent evidence disables reuse. */
        default Footprint lastReads() { return null; }
    }
    /** Packed block coordinates only, with no world references. */
    static final class Footprint {
        private final long[] reads;
        Footprint(long[] reads) { this.reads=reads.clone(); }
        boolean unchanged(java.util.Set<Long> edits) {
            if(reads.length==0) return false;
            for(long read:reads) if(edits.contains(read)) return false;
            return true;
        }
        /** Conservative, constant-size coverage dependency evidence, including the sampled position. */
        ReadBounds enclose(int x,int y,int z) {
            if(reads.length==0 || blockX(blockKey(x,y,z))!=x || blockY(blockKey(x,y,z))!=y || blockZ(blockKey(x,y,z))!=z) return null;
            int lx=x,ly=y,lz=z,hx=x,hy=y,hz=z;
            for(long read:reads) {
                int rx=blockX(read),ry=blockY(read),rz=blockZ(read);
                lx=Math.min(lx,rx);ly=Math.min(ly,ry);lz=Math.min(lz,rz);
                hx=Math.max(hx,rx);hy=Math.max(hy,ry);hz=Math.max(hz,rz);
            }
            return new ReadBounds(lx,ly,lz,hx,hy,hz);
        }
    }
    private static int blockX(long key) { return (int)(key>>38); }
    private static int blockY(long key) { return (int)(key<<52>>52); }
    private static int blockZ(long key) { return (int)(key<<26>>38); }
    record ReadBounds(int lowX,int lowY,int lowZ,int highX,int highY,int highZ) {
        ReadBounds union(ReadBounds b) {
            return new ReadBounds(Math.min(lowX,b.lowX),Math.min(lowY,b.lowY),Math.min(lowZ,b.lowZ),
                    Math.max(highX,b.highX),Math.max(highY,b.highY),Math.max(highZ,b.highZ));
        }
        boolean unchanged(java.util.Set<Long> edits) {
            for(long edit:edits) {
                int x=blockX(edit),y=blockY(edit),z=blockZ(edit);
                if(x>=lowX && x<=highX && y>=lowY && y<=highY && z>=lowZ && z<=highZ) return false;
            }
            return true;
        }
    }
    /** BlockPos's 26/12/26 packing for valid Minecraft block coordinates, without a runtime class dependency. */
    static long blockKey(int x,int y,int z) {
        return ((x&0x3ffffffL)<<38) | ((z&0x3ffffffL)<<12) | (y&0xfffL);
    }
    static final class Unavailable extends RuntimeException {
        final Status status;
        Unavailable(Status status,String reason) {
            super(reason);
            if(status!=Status.MISSING_GEOMETRY && status!=Status.UNSUPPORTED_GEOMETRY) { throw new IllegalArgumentException(); }
            this.status=status;
        }
    }
    /** Cell-local half-open integer boxes in sixteenths. Solid occupancy wins over fluid occupancy. */
    static final class Shape {
        private final int[] solid,fluid;
        private final int hash;
        Shape(int[] solid,int[] fluid) {
            this.solid=validate(solid);this.fluid=validate(fluid);
            hash=31*Arrays.hashCode(this.solid)+Arrays.hashCode(this.fluid);
        }
        private static int[] validate(int[] boxes) {
            if(boxes.length%6!=0 || boxes.length>64*6) { throw new IllegalArgumentException("At most 64 boxes per phase"); }
            for(int i=0;i<boxes.length;i+=6) for(int axis=0;axis<3;axis++) {
                if(boxes[i+axis]<0 || boxes[i+axis+3]>16 || boxes[i+axis]>=boxes[i+axis+3]) {
                    throw new IllegalArgumentException("Nonempty boxes must lie within their owning cell");
                }
            }
            return boxes.clone();
        }
        FlowShape bake() {
            var b=new FlowShape.Builder();
            for(int i=0;i<fluid.length;i+=6) { b.fluidBox(fluid[i],fluid[i+1],fluid[i+2],fluid[i+3],fluid[i+4],fluid[i+5]); }
            for(int i=0;i<solid.length;i+=6) { b.solidBox(solid[i],solid[i+1],solid[i+2],solid[i+3],solid[i+4],solid[i+5]); }
            return b.build();
        }
        @Override public int hashCode() { return hash; }
        boolean empty() { return solid.length==0 && fluid.length==0; }
        @Override public boolean equals(Object other) {
            return other instanceof Shape s && Arrays.equals(solid,s.solid) && Arrays.equals(fluid,s.fluid);
        }
    }
    /** Existing table power already excludes radiation; limits remain absolute Celsius / ambient offsets. */
    record Heat(double power,double absoluteLimit,double maxOffset,boolean perFace) {
        Heat {
            if(!Double.isFinite(power) || !(Double.isNaN(absoluteLimit) || Double.isFinite(absoluteLimit))
                    || !(Double.isNaN(maxOffset) || Double.isFinite(maxOffset) && maxOffset>=0)) throw new IllegalArgumentException("Invalid source snapshot");
        }
        double limit(double ambient) {
            double offset=Double.isNaN(maxOffset)?Double.NaN:ambient+(power>0?maxOffset:-maxOffset);
            if(Double.isNaN(absoluteLimit)) return offset;
            if(Double.isNaN(offset)) return absoluteLimit;
            return power>0?Math.min(absoluteLimit,offset):Math.max(absoluteLimit,offset);
        }
    }
    record Cell(Shape shape,double solidK,double solidCapacity,double fluidK,double fluidCapacity,Heat heat) {
        Cell(Shape shape,double solidK,double solidCapacity,double fluidK,double fluidCapacity) {
            this(shape,solidK,solidCapacity,fluidK,fluidCapacity,null);
        }
        Cell {
            java.util.Objects.requireNonNull(shape);
            if(!Double.isFinite(solidK) || solidK<0 || !Double.isFinite(fluidK) || fluidK<0
                    || !Double.isFinite(solidCapacity) || solidCapacity<=0
                    || !Double.isFinite(fluidCapacity) || fluidCapacity<=0) { throw new IllegalArgumentException("Invalid material snapshot"); }
        }
    }
    record Prepared(FlowGeometry flow,ThermalGeometry thermal) { }
    static final class Snapshot {
        final Bounds bounds;
        final long contextId,geometryRevision,materialRevision;
        private final Shape[] shapes;
        private final Heat[] heat;
        private final Footprint[] footprints;
        private final double[] solidK,solidCapacity,fluidK,fluidCapacity;
        private Snapshot(LocalAirGeometryCapture c) {
            bounds=c.bounds;contextId=c.contextId;geometryRevision=c.geometryRevision;materialRevision=c.materialRevision;
            shapes=c.shapes.clone();solidK=c.solidK.clone();solidCapacity=c.solidCapacity.clone();
            heat=c.heat.clone();
            footprints=c.footprints.clone();
            fluidK=c.fluidK.clone();fluidCapacity=c.fluidCapacity.clone();
        }
        /** Worker-side diagnostic bake only. Exterior classification/admission is a separate required step. */
        Prepared bakeClosedControl(ThermalGeometry.Coefficients coefficients) {
            return bake(coefficients,false);
        }
        Shape shape(int x,int y,int z) { return shapes[(z*bounds.ny+y)*bounds.nx+x]; }
        Heat heat(int index) { return heat[index]; }
        boolean sameMaterials(Snapshot previous) {
            return bounds.equals(previous.bounds) && contextId==previous.contextId && materialRevision==previous.materialRevision
                    && Arrays.equals(solidK,previous.solidK) && Arrays.equals(solidCapacity,previous.solidCapacity)
                    && Arrays.equals(fluidK,previous.fluidK) && Arrays.equals(fluidCapacity,previous.fluidCapacity);
        }
        boolean sameMaterial(int index,Cell cell) {
            return shapes[index].equals(cell.shape) && solidK[index]==cell.solidK && solidCapacity[index]==cell.solidCapacity
                    && fluidK[index]==cell.fluidK && fluidCapacity[index]==cell.fluidCapacity;
        }
        dev.brights0ng.paradynelib.heat.air.ThermalTransport.Source[] sources(ThermalGeometry thermal,double ambient) {
            var out=new ArrayList<dev.brights0ng.paradynelib.heat.air.ThermalTransport.Source>();
            for(int i=0;i<heat.length;i++) if(heat[i]!=null && heat[i].power()!=0) {
                int x=i%bounds.nx,y=(i/bounds.nx)%bounds.ny,z=i/(bounds.nx*bounds.ny);
                if(x==0 || y==0 || z==0 || x==bounds.nx-1 || y==bounds.ny-1 || z==bounds.nz-1)
                    throw new IllegalArgumentException("Source touches domain boundary; enlarge the diagnostic domain");
                out.addAll(java.util.List.of(dev.brights0ng.paradynelib.heat.air.ThermalSources.fromBlock(
                        thermal,i,heat[i].power(),heat[i].limit(ambient),heat[i].perFace())));
            }
            return out.toArray(dev.brights0ng.paradynelib.heat.air.ThermalTransport.Source[]::new);
        }
        Prepared bakeAdmitted(ThermalGeometry.Coefficients coefficients,LocalAirCoverage.Certificate certificate) {
            if(!LocalAirCoverage.matches(certificate,this)) throw new IllegalArgumentException("Certificate belongs to another snapshot");
            return bake(coefficients,true);
        }
        private Prepared bake(ThermalGeometry.Coefficients coefficients,boolean openExterior) {
            var b=new FlowGeometry.Builder(bounds.nx,bounds.ny,bounds.nz).revision(geometryRevision).openExterior(openExterior);
            var cache=new HashMap<Shape,FlowShape>();
            for(int i=0;i<shapes.length;i++) {
                b.cell(i%bounds.nx,(i/bounds.nx)%bounds.ny,i/(bounds.nx*bounds.ny),cache.computeIfAbsent(shapes[i],Shape::bake));
            }
            var f=b.build();return new Prepared(f,new ThermalGeometry(f,solidK,solidCapacity,fluidK,fluidCapacity,coefficients));
        }
    }
    private final Thread owner=Thread.currentThread();
    private final Bounds bounds;
    private final Source source;
    private final long contextId,geometryRevision,materialRevision;
    private final Shape[] shapes;
    private final Heat[] heat;
    private final Footprint[] footprints;
    private final Snapshot baseline;
    private final java.util.Set<Long> edits;
    private final double[] solidK,solidCapacity,fluidK,fluidCapacity;
    private int cursor,reused,readCount;
    private Status status=Status.CAPTURING;
    private String reason="";
    LocalAirGeometryCapture(Bounds bounds,int cellLimit,Source source) {
        this(bounds,cellLimit,source,null,java.util.Set.of());
    }
    /** Caller proves a complete classified edit history between the two snapshots. */
    LocalAirGeometryCapture(Bounds bounds,int cellLimit,Source source,Snapshot baseline,java.util.Set<Long> edits) {
        if(bounds.cells()>cellLimit) { throw new IllegalArgumentException("Capture exceeds cell admission limit"); }
        this.bounds=bounds;this.source=java.util.Objects.requireNonNull(source);
        contextId=source.contextId();geometryRevision=source.geometryRevision();materialRevision=source.materialRevision();
        if(baseline!=null && (!bounds.equals(baseline.bounds) || contextId!=baseline.contextId || materialRevision!=baseline.materialRevision
                || geometryRevision<baseline.geometryRevision)) throw new IllegalArgumentException("Incompatible incremental snapshot");
        this.baseline=baseline;this.edits=java.util.Set.copyOf(edits);
        shapes=baseline==null?new Shape[bounds.cells()]:baseline.shapes.clone();
        heat=baseline==null?new Heat[shapes.length]:baseline.heat.clone();
        footprints=baseline==null?new Footprint[shapes.length]:baseline.footprints.clone();
        solidK=baseline==null?new double[shapes.length]:baseline.solidK.clone();
        solidCapacity=baseline==null?new double[shapes.length]:baseline.solidCapacity.clone();
        fluidK=baseline==null?new double[shapes.length]:baseline.fluidK.clone();
        fluidCapacity=baseline==null?new double[shapes.length]:baseline.fluidCapacity.clone();
    }
    Status status() { return status; }
    String reason() { return reason; }
    /** Processed domain cells, including reuse; readCells counts actual sample attempts. */
    int sampledCells() { return cursor; }
    int readCells() { return readCount; }
    int reusedCells() { return reused; }
    private void checkOwner() { if(Thread.currentThread()!=owner) { throw new IllegalStateException("Capture must stay on its owner thread"); } }
    private boolean current() {
        return source.contextId()==contextId && source.geometryRevision()==geometryRevision && source.materialRevision()==materialRevision;
    }
    int advance(int cellAllowance,long deadlineNanos) {
        checkOwner();if(cellAllowance<0) { throw new IllegalArgumentException("Nonnegative work allowance required"); }
        if((status==Status.CAPTURING || status==Status.CAPTURED) && !current()) { status=Status.STALE;reason="Geometry/material revision changed"; }
        int used=0;
        while(status==Status.CAPTURING && cellAllowance>0 && System.nanoTime()<deadlineNanos) {
            int i=cursor;
            int x=bounds.x+i%bounds.nx,y=bounds.y+(i/bounds.nx)%bounds.ny,z=bounds.z+i/(bounds.nx*bounds.ny);
            if(baseline!=null && !edits.contains(blockKey(x,y,z)) && footprints[i]!=null && footprints[i].unchanged(edits)) {
                cursor++;reused++;if(cursor==shapes.length) status=Status.CAPTURED;continue;
            }
            if(used>=cellAllowance) break;
            readCount++;
            try {
                var cell=java.util.Objects.requireNonNull(source.sample(x,y,z));
                used++;
                if(!current()) { status=Status.STALE;reason="Revision changed during capture";break; }
                shapes[i]=cell.shape;solidK[i]=cell.solidK;solidCapacity[i]=cell.solidCapacity;
                heat[i]=cell.heat;
                footprints[i]=source.lastReads();
                fluidK[i]=cell.fluidK;fluidCapacity[i]=cell.fluidCapacity;cursor++;
                if(cursor==shapes.length) { status=Status.CAPTURED; }
            } catch(Unavailable e) { used++;status=e.status;reason=e.getMessage(); }
        }
        return used;
    }
    Snapshot snapshot() {
        checkOwner();
        if(!current()) { status=Status.STALE;reason="Revision changed before handoff"; }
        if(status!=Status.CAPTURED) { throw new IllegalStateException("Capture unavailable: "+status+" "+reason); }
        return new Snapshot(this);
    }
}
