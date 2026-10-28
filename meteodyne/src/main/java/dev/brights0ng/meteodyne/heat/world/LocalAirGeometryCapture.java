// Updated by ChatGPT: phase-specific material compatibility for known topology edits.
// Updated by ChatGPT: natural-source classification and exact saved material identity.
// Written by ChatGPT.
// Updated by ChatGPT: guarded cross-layout snapshot reuse with imported dependency footprints.
// Updated by Claude: per-cell edit classification and a lazy cross-cell read-context index.
// Updated by Claude: re-read only edited cells and their readers while capture is in progress.
// Updated by Claude: kept the reader index on fastutil; footprint reads have no distance bound.
// Updated by Claude: reported queued cells and re-read positions for edits after capture completes.
package dev.brights0ng.meteodyne.heat.world;

import java.util.Arrays;
import java.util.HashMap;
import java.util.ArrayList;
import dev.brights0ng.paradynelib.heat.air.FlowGeometry;
import dev.brights0ng.paradynelib.heat.air.FlowShape;
import dev.brights0ng.paradynelib.heat.air.ThermalGeometry;
import dev.brights0ng.paradynelib.heat.air.CellLayout;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;

/** Main-thread sliced capture; worker snapshots contain no world/Minecraft objects.
 * Complete geometry is distinct from verified exterior coverage and published field readiness.
 */
final class LocalAirGeometryCapture {
    enum Status { CAPTURING, CAPTURED, MISSING_GEOMETRY, UNSUPPORTED_GEOMETRY, STALE }
    record Bounds(int x,int y,int z,int nx,int ny,int nz,CellLayout sparse) {
        Bounds(int x,int y,int z,int nx,int ny,int nz) { this(x,y,z,nx,ny,nz,null); }
        Bounds {
            if(nx<1 || ny<1 || nz<1) { throw new IllegalArgumentException("Positive dimensions required"); }
            Math.addExact(x,nx-1);Math.addExact(y,ny-1);Math.addExact(z,nz-1);
            if(sparse==null) Math.multiplyExact(Math.multiplyExact(nx,ny),nz);
            else if(sparse.nx!=nx || sparse.ny!=ny || sparse.nz!=nz) throw new IllegalArgumentException("Layout envelope mismatch");
        }
        int cells() { return sparse==null?Math.multiplyExact(Math.multiplyExact(nx,ny),nz):sparse.cells(); }
        CellLayout cellLayout() { return sparse==null?CellLayout.dense(nx,ny,nz):sparse; }
        int cellX(int c) { return sparse==null?c%nx:sparse.x(c); }
        int cellY(int c) { return sparse==null?(c/nx)%ny:sparse.y(c); }
        int cellZ(int c) { return sparse==null?c/(nx*ny):sparse.z(c); }
        int index(int lx,int ly,int lz) {
            if(lx<0 || ly<0 || lz<0 || lx>=nx || ly>=ny || lz>=nz) return -1;
            return sparse==null?(lz*ny+ly)*nx+lx:sparse.index(lx,ly,lz);
        }
        int neighbor(int c,int axis,int side) { int d=side==0?-1:1;return index(cellX(c)+(axis==0?d:0),cellY(c)+(axis==1?d:0),cellZ(c)+(axis==2?d:0)); }
        boolean contains(double wx,double wy,double wz) {
            double lx=wx-x,ly=wy-y,lz=wz-z;return lx>=0 && ly>=0 && lz>=0 && lx<nx && ly<ny && lz<nz && index((int)lx,(int)ly,(int)lz)>=0;
        }
        boolean chunk(int cx,int cz) {
            if(sparse==null) return cx>=(x>>4) && cx<=(((long)x+nx-1)>>4) && cz>=(z>>4) && cz<=(((long)z+nz-1)>>4);
            int edge=sparse.tileEdge();for(int t=0;t<sparse.tileCount();t++) {
                long lx=(long)x+sparse.tileCoordinate(t,0)*edge,lz=(long)z+sparse.tileCoordinate(t,2)*edge;
                if(cx>=(lx>>4) && cx<=((lx+edge-1)>>4) && cz>=(lz>>4) && cz<=((lz+edge-1)>>4)) return true;
            }
            return false;
        }
        /** Relative block origins of complete aligned tiles, proportional only to actual allocation. */
        int[] tiles() {
            if(sparse!=null) { int[] out=new int[sparse.tileCount()*3];for(int t=0;t<sparse.tileCount();t++) for(int a=0;a<3;a++) out[t*3+a]=sparse.tileCoordinate(t,a)*sparse.tileEdge();return out; }
            int[] out=new int[cells()/4096*3];int i=0;
            for(int zz=0;zz<nz;zz+=16) for(int yy=0;yy<ny;yy+=16) for(int xx=0;xx<nx;xx+=16) {out[i++]=xx;out[i++]=yy;out[i++]=zz;}return out;
        }
    }
    interface Source {
        long contextId(); // Unique capture-source identity; never reused across worlds/source lifetimes.
        long geometryRevision();
        long materialRevision();
        Cell sample(int x,int y,int z);
        /** Live owner-thread source sampling, separate from historical geometry reads. */
        default Heat drivenHeat(int x,int y,int z) { throw new UnsupportedOperationException("Driven source adapter unavailable"); }
        /** Immutable block reads from the last successful sample; absent evidence disables reuse. */
        default Footprint lastReads() { return null; }
        /** Explicit owner-thread identity check; independent worlds cannot share evidence. */
        default boolean sameWorld(Source other) { return this==other; }
        default void retainRead(int x,int y,int z) { }
        default void retainReads(ReadBounds bounds) { }
    }
    /** Packed block coordinates only, with no world references. */
    static final class Footprint {
        private final long[] reads;
        Footprint(long[] reads) { this.reads=reads.clone(); }
        long[] reads() { return reads.clone(); }
        void retain(Source target) { for(long read:reads) target.retainRead(blockX(read),blockY(read),blockZ(read)); }
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
    static int blockX(long key) { return (int)(key>>38); }
    static int blockY(long key) { return (int)(key<<52>>52); }
    static int blockZ(long key) { return (int)(key<<26>>38); }
    record ReadBounds(int lowX,int lowY,int lowZ,int highX,int highY,int highZ) {
        ReadBounds union(ReadBounds b) {
            return new ReadBounds(Math.min(lowX,b.lowX),Math.min(lowY,b.lowY),Math.min(lowZ,b.lowZ),
                    Math.max(highX,b.highX),Math.max(highY,b.highY),Math.max(highZ,b.highZ));
        }
        boolean unchanged(java.util.Set<Long> edits) {
            for(long edit:edits) if(contains(blockX(edit),blockY(edit),blockZ(edit))) return false;
            return true;
        }
        boolean contains(int x,int y,int z) { return x>=lowX && x<=highX && y>=lowY && y<=highY && z>=lowZ && z<=highZ; }
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
        int[] solidBoxes() { return solid.clone(); }
        int[] fluidBoxes() { return fluid.clone(); }
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
    record HeatTerm(double power,double absoluteLimit,double maxOffset,boolean perFace) {
        HeatTerm {
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
    /** Immutable independent terms; opposing drivers keep their own limits and face routing. */
    record Heat(java.util.List<HeatTerm> terms) {
        static final int MAX_TERMS=64;
        Heat { terms=java.util.List.copyOf(terms);if(terms.isEmpty() || terms.size()>MAX_TERMS) throw new IllegalArgumentException("Source term limit exceeded"); }
        Heat(double power,double absoluteLimit,double maxOffset,boolean perFace) { this(java.util.List.of(new HeatTerm(power,absoluteLimit,maxOffset,perFace))); }
        private HeatTerm single() { if(terms.size()!=1) throw new IllegalStateException("Compound source requires term-wise routing");return terms.getFirst(); }
        double power() { return single().power(); }
        double limit(double ambient) { return single().limit(ambient); }
        boolean active() { return terms.stream().anyMatch(t->t.power()!=0); }
        java.util.List<dev.brights0ng.paradynelib.heat.air.ThermalTransport.Source> routed(ThermalGeometry thermal,int cell,double ambient) {
            var out=new ArrayList<dev.brights0ng.paradynelib.heat.air.ThermalTransport.Source>();
            for(var term:terms) if(term.power()!=0) out.addAll(java.util.List.of(dev.brights0ng.paradynelib.heat.air.ThermalSources.fromBlock(thermal,cell,term.power(),term.limit(ambient),term.perFace())));
            return out;
        }
    }
    record Cell(Shape shape,double solidK,double solidCapacity,double fluidK,double fluidCapacity,Heat heat,boolean driven,boolean natural) {
        Cell(Shape shape,double solidK,double solidCapacity,double fluidK,double fluidCapacity,Heat heat,boolean driven) { this(shape,solidK,solidCapacity,fluidK,fluidCapacity,heat,driven,false); }
        Cell(Shape shape,double solidK,double solidCapacity,double fluidK,double fluidCapacity,Heat heat) { this(shape,solidK,solidCapacity,fluidK,fluidCapacity,heat,false); }
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
        private final boolean[] driven,natural;
        private final int[] drivenCells;
        private final Footprint[] footprints;
        private final double[] solidK,solidCapacity,fluidK,fluidCapacity;
        private long[] context; // Owner-thread lazy index; sorted, distinct.
        private Snapshot(LocalAirGeometryCapture c) {
            bounds=c.bounds;contextId=c.contextId;geometryRevision=c.geometryRevision;materialRevision=c.materialRevision;
            shapes=c.shapes.clone();solidK=c.solidK.clone();solidCapacity=c.solidCapacity.clone();
            heat=c.heat.clone();
            natural=c.natural.clone();driven=c.driven.clone();var cells=new ArrayList<Integer>();for(int i=0;i<driven.length;i++) if(driven[i]) cells.add(i);
            drivenCells=cells.stream().mapToInt(Integer::intValue).toArray();
            footprints=c.footprints.clone();
            fluidK=c.fluidK.clone();fluidCapacity=c.fluidCapacity.clone();
        }
        /** Worker-side diagnostic bake only. Exterior classification/admission is a separate required step. */
        Prepared bakeClosedControl(ThermalGeometry.Coefficients coefficients) {
            return bake(coefficients,false);
        }
        Shape shape(int x,int y,int z) { return shapes[bounds.index(x,y,z)]; }
        Heat heat(int index) { return heat[index]; }
        boolean natural(int index) { return natural[index]; }
        LocalAirThermalState.Material material(int cell) { return new LocalAirThermalState.Material(shapes[cell],solidK[cell],solidCapacity[cell],fluidK[cell],fluidCapacity[cell]); }
        boolean placedSources() { for(int i=0;i<heat.length;i++) if(!natural[i] && (driven[i] || heat[i]!=null && heat[i].active())) return true;return false; }
        int[] drivenCells() { return drivenCells.clone(); }
        boolean sameTransferContext(Snapshot previous) {
            return bounds.equals(previous.bounds) && contextId==previous.contextId && materialRevision==previous.materialRevision;
        }
        boolean retainsSolid(Snapshot previous,int cell) { return solidK[cell]==previous.solidK[cell] && solidCapacity[cell]==previous.solidCapacity[cell]; }
        boolean retainsFluid(Snapshot previous,int cell) { return fluidK[cell]==previous.fluidK[cell] && fluidCapacity[cell]==previous.fluidCapacity[cell]; }
        boolean sameMaterials(Snapshot previous) {
            return bounds.equals(previous.bounds) && contextId==previous.contextId && materialRevision==previous.materialRevision
                    && Arrays.equals(solidK,previous.solidK) && Arrays.equals(solidCapacity,previous.solidCapacity)
                    && Arrays.equals(fluidK,previous.fluidK) && Arrays.equals(fluidCapacity,previous.fluidCapacity);
        }
        boolean sameMaterial(int index,Cell cell) {
            return shapes[index].equals(cell.shape) && solidK[index]==cell.solidK && solidCapacity[index]==cell.solidCapacity
                    && fluidK[index]==cell.fluidK && fluidCapacity[index]==cell.fluidCapacity;
        }
        boolean sameCell(int index,Cell cell) {
            return sameMaterial(index,cell) && java.util.Objects.equals(heat[index],cell.heat)
                    && driven[index]==cell.driven && natural[index]==cell.natural;
        }
        boolean heatOnlyDiffers(int index,Cell cell) {
            return sameMaterial(index,cell) && !java.util.Objects.equals(heat[index],cell.heat)
                    && driven[index]==cell.driven && natural[index]==cell.natural;
        }
        /** True when some other cell's sample read this position. Cells without read evidence
         * conservatively count their six face neighbours as read context. */
        boolean context(long blockKey) {
            if(context==null) {
                var keys=new java.util.ArrayList<long[]>();int total=0;
                for(int i=0;i<shapes.length;i++) {
                    int x=bounds.x+bounds.cellX(i),y=bounds.y+bounds.cellY(i),z=bounds.z+bounds.cellZ(i);
                    long own=blockKey(x,y,z);
                    long[] reads=footprints[i]==null?null:footprints[i].reads();
                    if(reads==null || reads.length==0) reads=new long[]{blockKey(x-1,y,z),blockKey(x+1,y,z),blockKey(x,y-1,z),
                            blockKey(x,y+1,z),blockKey(x,y,z-1),blockKey(x,y,z+1)};
                    int n=0;for(long read:reads) if(read!=own) reads[n++]=read;
                    if(n>0) { keys.add(n==reads.length?reads:Arrays.copyOf(reads,n));total+=n; }
                }
                long[] all=new long[total];int at=0;
                for(var part:keys) { System.arraycopy(part,0,all,at,part.length);at+=part.length; }
                Arrays.sort(all);
                int n=0;for(int i=0;i<all.length;i++) if(n==0 || all[i]!=all[n-1]) all[n++]=all[i];
                context=Arrays.copyOf(all,n);
            }
            return Arrays.binarySearch(context,blockKey)>=0;
        }
        /** Layout transfer may change origins/context IDs, but never material/shape ownership in overlap. */
        boolean layoutCompatible(Snapshot previous) {
            if(materialRevision!=previous.materialRevision) return false;
            var a=bounds;var b=previous.bounds;
            for(int i=0;i<a.cells();i++) {
                long x=(long)a.x()+a.cellX(i)-b.x(),y=(long)a.y()+a.cellY(i)-b.y(),z=(long)a.z()+a.cellZ(i)-b.z();
                if(x<0 || y<0 || z<0 || x>=b.nx() || y>=b.ny() || z>=b.nz()) continue;
                int j=b.index((int)x,(int)y,(int)z);if(j<0) continue;
                if(!shapes[i].equals(previous.shapes[j]) || solidK[i]!=previous.solidK[j] || solidCapacity[i]!=previous.solidCapacity[j]
                        || fluidK[i]!=previous.fluidK[j] || fluidCapacity[i]!=previous.fluidCapacity[j]) return false;
            }
            return true;
        }
        dev.brights0ng.paradynelib.heat.air.ThermalTransport.Source[] sources(ThermalGeometry thermal,double ambient) {
            var out=new ArrayList<dev.brights0ng.paradynelib.heat.air.ThermalTransport.Source>();
            for(int i=0;i<heat.length;i++) if(heat[i]!=null && heat[i].active()) {
                for(int axis=0;axis<3;axis++) for(int side=0;side<2;side++) if(bounds.neighbor(i,axis,side)<0)
                    throw new IllegalArgumentException("Source touches domain boundary; enlarge the diagnostic domain");
                out.addAll(heat[i].routed(thermal,i,ambient));
            }
            return out.toArray(dev.brights0ng.paradynelib.heat.air.ThermalTransport.Source[]::new);
        }
        Prepared bakeAdmitted(ThermalGeometry.Coefficients coefficients,LocalAirCoverage.Certificate certificate) {
            if(!LocalAirCoverage.matches(certificate,this)) throw new IllegalArgumentException("Certificate belongs to another snapshot");
            return bake(coefficients,true);
        }
        /** Worker-only dense/sparse tile assembly, after the same whole-domain coverage certificate. */
        Prepared bakeTiledAdmitted(ThermalGeometry.Coefficients coefficients,LocalAirCoverage.Certificate certificate,int edge) {
            if(!LocalAirCoverage.matches(certificate,this)) throw new IllegalArgumentException("Certificate belongs to another snapshot");
            if(edge!=16 || bounds.x()%edge!=0 || bounds.y()%edge!=0 || bounds.z()%edge!=0
                    || bounds.nx()%edge!=0 || bounds.ny()%edge!=0 || bounds.nz()%edge!=0
                    || bounds.sparse()!=null && bounds.sparse().tileEdge()!=edge)
                throw new IllegalArgumentException("Complete aligned 16-block tiles required");
            var tiles=new ArrayList<dev.brights0ng.paradynelib.heat.air.TiledAirGeometry.Tile>();
            var version=new dev.brights0ng.paradynelib.heat.air.TiledAirGeometry.Version(contextId,geometryRevision,materialRevision);
            var cache=new HashMap<Shape,FlowShape>();int cells=edge*edge*edge;
            var positions=bounds.tiles();for(int t=0;t<positions.length;t+=3) {
                int x0=positions[t],y0=positions[t+1],z0=positions[t+2];
                var shape=new FlowShape[cells];double[] sk=new double[cells],sc=new double[cells],fk=new double[cells],fc=new double[cells];
                for(int c=0;c<cells;c++) {
                    int i=bounds.index(x0+c%edge,y0+(c/edge)%edge,z0+c/(edge*edge));
                    shape[c]=cache.computeIfAbsent(shapes[i],Shape::bake);sk[c]=solidK[i];sc[c]=solidCapacity[i];fk[c]=fluidK[i];fc[c]=fluidCapacity[i];
                }
                var key=new dev.brights0ng.paradynelib.heat.air.TiledAirGeometry.Key((bounds.x()+x0)/edge,(bounds.y()+y0)/edge,(bounds.z()+z0)/edge);
                tiles.add(new dev.brights0ng.paradynelib.heat.air.TiledAirGeometry.Tile(key,version,edge,shape,sk,sc,fk,fc));
            }
            // Experimental allocation guards, not reduced spatial resolution or production memory defaults.
            var joined=dev.brights0ng.paradynelib.heat.air.TiledAirGeometry.joinSparse(tiles,bounds.cells(),true,coefficients,Math.multiplyExact(bounds.cells(),4),Math.multiplyExact(bounds.cells(),12));
            return new Prepared(joined.flow(),joined.thermal());
        }
        private Prepared bake(ThermalGeometry.Coefficients coefficients,boolean openExterior) {
            var b=new FlowGeometry.Builder(bounds.cellLayout()).revision(geometryRevision).openExterior(openExterior);
            var cache=new HashMap<Shape,FlowShape>();
            for(int i=0;i<shapes.length;i++) {
                b.cell(bounds.cellX(i),bounds.cellY(i),bounds.cellZ(i),cache.computeIfAbsent(shapes[i],Shape::bake));
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
    private final boolean[] driven,natural;
    private final Footprint[] footprints;
    private final Snapshot baseline;
    private final java.util.List<LocalAirReuse> reusable;
    private final java.util.Set<Long> edits;
    private final double[] solidK,solidCapacity,fluidK,fluidCapacity;
    private final java.util.BitSet captured=new java.util.BitSet(),queued=new java.util.BitSet();
    // Position -> captured cells whose sample read it. Shape context reads are bounded in count, not distance,
    // so readers of a position cannot be found from its neighbourhood alone.
    private final Long2ObjectOpenHashMap<IntArrayList> readers=new Long2ObjectOpenHashMap<>();
    private final java.util.ArrayDeque<Integer> reread=new java.util.ArrayDeque<>();
    // Edited positions and the cells they queued since the last takeRereadPositions.
    private final java.util.HashSet<Long> rereadPositions=new java.util.HashSet<>();
    private int cursor,reused,readCount,rereadCount;
    private Status status=Status.CAPTURING;
    private String reason="";
    LocalAirGeometryCapture(Bounds bounds,int cellLimit,Source source) {
        this(bounds,cellLimit,source,null,java.util.Set.of());
    }
    /** Caller proves a complete classified edit history between the two snapshots. */
    LocalAirGeometryCapture(Bounds bounds,int cellLimit,Source source,Snapshot baseline,java.util.Set<Long> edits) {
        this(bounds,cellLimit,source,baseline,edits,java.util.List.of());
    }
    LocalAirGeometryCapture(Bounds bounds,int cellLimit,Source source,Snapshot baseline,java.util.Set<Long> edits,java.util.List<LocalAirReuse> reusable) {
        if(bounds.cells()>cellLimit) { throw new IllegalArgumentException("Capture exceeds cell admission limit"); }
        this.bounds=bounds;this.source=java.util.Objects.requireNonNull(source);
        contextId=source.contextId();geometryRevision=source.geometryRevision();materialRevision=source.materialRevision();
        if(baseline!=null && (!bounds.equals(baseline.bounds) || contextId!=baseline.contextId || materialRevision!=baseline.materialRevision
                || geometryRevision<baseline.geometryRevision)) throw new IllegalArgumentException("Incompatible incremental snapshot");
        this.baseline=baseline;this.edits=new java.util.HashSet<>(edits);this.reusable=java.util.List.copyOf(reusable);
        shapes=baseline==null?new Shape[bounds.cells()]:baseline.shapes.clone();
        heat=baseline==null?new Heat[shapes.length]:baseline.heat.clone();
        driven=baseline==null?new boolean[shapes.length]:baseline.driven.clone();
        natural=baseline==null?new boolean[shapes.length]:baseline.natural.clone();
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
    int rereadCells() { return rereadCount; }
    private void checkOwner() { if(Thread.currentThread()!=owner) { throw new IllegalStateException("Capture must stay on its owner thread"); } }
    private boolean current() {
        return source.contextId()==contextId && source.geometryRevision()==geometryRevision && source.materialRevision()==materialRevision;
    }
    int advance(int cellAllowance,long deadlineNanos) {
        checkOwner();if(cellAllowance<0) { throw new IllegalArgumentException("Nonnegative work allowance required"); }
        if((status==Status.CAPTURING || status==Status.CAPTURED) && !current()) { status=Status.STALE;reason="Geometry/material revision changed"; }
        int used=0;
        while(status==Status.CAPTURING && used<cellAllowance && !reread.isEmpty() && System.nanoTime()<deadlineNanos) {
            int i=reread.peekFirst();used++;rereadCount++;
            if(!readCell(i)) break;
            reread.removeFirst();queued.clear(i);
        }
        while(status==Status.CAPTURING && cursor<shapes.length && cellAllowance>0 && System.nanoTime()<deadlineNanos) {
            int i=cursor;
            int x=bounds.x+bounds.cellX(i),y=bounds.y+bounds.cellY(i),z=bounds.z+bounds.cellZ(i);
            if(baseline!=null && !edits.contains(blockKey(x,y,z)) && footprints[i]!=null && footprints[i].unchanged(edits)) {
                index(i);cursor++;reused++;continue;
            }
            if(baseline==null && reuse(i,x,y,z)) { index(i);cursor++;reused++;continue; }
            if(used>=cellAllowance) break;
            used++;
            if(!readCell(i)) break;
            cursor++;
        }
        if(status==Status.CAPTURING && cursor==shapes.length && reread.isEmpty()) status=Status.CAPTURED;
        return used;
    }
    /** One owner-thread sample of cell i; false when capture stopped (stale or unavailable). */
    private boolean readCell(int i) {
        int x=bounds.x+bounds.cellX(i),y=bounds.y+bounds.cellY(i),z=bounds.z+bounds.cellZ(i);
        readCount++;
        try {
            var cell=java.util.Objects.requireNonNull(source.sample(x,y,z));
            if(!current()) { status=Status.STALE;reason="Revision changed during capture";return false; }
            unindex(i);
            shapes[i]=cell.shape;solidK[i]=cell.solidK;solidCapacity[i]=cell.solidCapacity;
            heat[i]=cell.heat;
            driven[i]=cell.driven;natural[i]=cell.natural;
            footprints[i]=source.lastReads();
            fluidK[i]=cell.fluidK;fluidCapacity[i]=cell.fluidCapacity;
            index(i);return true;
        } catch(Unavailable e) { status=e.status;reason=e.getMessage();return false; }
    }
    /** Positions cell i depends on; without read evidence, its own position and six face neighbours. */
    private long[] reads(int i) {
        var reads=footprints[i]==null?null:footprints[i].reads();
        if(reads!=null && reads.length>0) return reads;
        int x=bounds.x+bounds.cellX(i),y=bounds.y+bounds.cellY(i),z=bounds.z+bounds.cellZ(i);
        return new long[]{blockKey(x,y,z),blockKey(x-1,y,z),blockKey(x+1,y,z),blockKey(x,y-1,z),blockKey(x,y+1,z),blockKey(x,y,z-1),blockKey(x,y,z+1)};
    }
    private void index(int i) {
        for(long read:reads(i)) {
            var list=readers.get(read);
            if(list==null) readers.put(read,list=new IntArrayList(2));
            if(!list.contains(i)) list.add(i);
        }
        captured.set(i);
    }
    private void unindex(int i) {
        if(!captured.get(i) && !queued.get(i)) return;
        for(long read:reads(i)) {
            var list=readers.get(read);if(list==null) continue;
            int k=list.indexOf(i);if(k>=0) { list.set(k,list.getInt(list.size()-1));list.removeInt(list.size()-1); }
            if(list.isEmpty()) readers.remove(read);
        }
    }
    /** An owner-thread edit at key, already applied to the source. Queues re-reads of the captured
     * cells it can affect (its own cell and every cell whose sample read it) and returns whether any
     * were queued. Cells the cursor has not reached are read later, after the edit. */
    boolean edited(long key,Cell sample) {
        checkOwner();if(status!=Status.CAPTURING && status!=Status.CAPTURED) return false;
        edits.add(key); // A later lease or baseline copy of this position would predate the edit.
        int own=bounds.index(blockX(key)-bounds.x,blockY(key)-bounds.y,blockZ(key)-bounds.z);
        var affected=new java.util.BitSet();
        if(own>=0 && captured.get(own)) affected.set(own);
        var list=readers.get(key);
        if(list!=null) for(int k=0;k<list.size();k++) if(captured.get(list.getInt(k))) affected.set(list.getInt(k));
        if(affected.isEmpty()) return false;
        if(own>=0 && affected.cardinality()==1 && affected.get(own) && sameCell(own,sample)) return false;
        rereadPositions.add(key);
        for(int c=affected.nextSetBit(0);c>=0;c=affected.nextSetBit(c+1)) {
            if(!queued.get(c)) { queued.set(c);reread.addLast(c); }
            captured.clear(c);rereadPositions.add(blockKey(bounds.x+bounds.cellX(c),bounds.y+bounds.cellY(c),bounds.z+bounds.cellZ(c)));
        }
        status=Status.CAPTURING;return true;
    }
    /** Whether the cell at key is queued for a re-read, which will see any edit made before it runs. */
    boolean pending(long key) {
        checkOwner();int own=bounds.index(blockX(key)-bounds.x,blockY(key)-bounds.y,blockZ(key)-bounds.z);
        return own>=0 && queued.get(own);
    }
    /** Edited positions and the positions of the cells they queued since the last call, which then clears them. */
    java.util.Set<Long> takeRereadPositions() {
        checkOwner();var out=java.util.Set.copyOf(rereadPositions);rereadPositions.clear();return out;
    }
    private boolean sameCell(int i,Cell cell) {
        return shapes[i].equals(cell.shape) && solidK[i]==cell.solidK && solidCapacity[i]==cell.solidCapacity
                && fluidK[i]==cell.fluidK && fluidCapacity[i]==cell.fluidCapacity
                && java.util.Objects.equals(heat[i],cell.heat) && driven[i]==cell.driven && natural[i]==cell.natural;
    }
    private boolean reuse(int i,int x,int y,int z) {
        for(var lease:reusable) {
            if(!lease.accepts(source)) continue;
            var old=lease.snapshot;var b=old.bounds;int j=b.index(x-b.x(),y-b.y(),z-b.z());
            if(j<0 || lease.edits.contains(blockKey(x,y,z)) || old.footprints[j]==null || !old.footprints[j].unchanged(lease.edits)
                    || edits.contains(blockKey(x,y,z)) || !old.footprints[j].unchanged(edits)) continue;
            old.footprints[j].retain(source);
            shapes[i]=old.shapes[j];heat[i]=old.heat[j];driven[i]=old.driven[j];natural[i]=old.natural[j];footprints[i]=old.footprints[j];
            solidK[i]=old.solidK[j];solidCapacity[i]=old.solidCapacity[j];fluidK[i]=old.fluidK[j];fluidCapacity[i]=old.fluidCapacity[j];return true;
        }
        return false;
    }
    Snapshot snapshot() {
        checkOwner();
        if(!current()) { status=Status.STALE;reason="Revision changed before handoff"; }
        if(status!=Status.CAPTURED) { throw new IllegalStateException("Capture unavailable: "+status+" "+reason); }
        return new Snapshot(this);
    }
}
