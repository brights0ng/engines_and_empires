// Written by ChatGPT.
package dev.brights0ng.paradynelib.heat.air;

import java.util.*;

/** Join coherent tiles into one shared operator, using dense or compact sparse cell storage.
 * Omitted tiles have no cells or thermal storage. No live-world or scheduling types.
 */
public final class TiledAirGeometry {
    private TiledAirGeometry() { }
    public record Key(int x,int y,int z) implements Comparable<Key> {
        @Override public int compareTo(Key k) {
            int c=Integer.compare(x,k.x);if(c==0) c=Integer.compare(y,k.y);if(c==0) c=Integer.compare(z,k.z);return c;
        }
    }
    /** Caller-assigned snapshot identity. All tiles must belong to exactly the same coherent capture. */
    public record Version(long context,long geometry,long material) { }

    /** Immutable primitive tile input; local cell order is (z * edge + y) * edge + x. */
    public static final class Tile {
        private final Key key;
        private final Version version;
        private final int edge;
        private final FlowShape[] shapes;
        private final double[] solidK,solidCapacity,fluidK,fluidCapacity;
        public Tile(Key key,Version version,int edge,FlowShape[] shapes,double[] solidK,double[] solidCapacity,
                    double[] fluidK,double[] fluidCapacity) {
            this.key=Objects.requireNonNull(key);this.version=Objects.requireNonNull(version);
            if(edge<1 || edge>32) throw new IllegalArgumentException("Tile edge must be 1..32");
            this.edge=edge;int cells=edge*edge*edge;
            if(shapes.length!=cells || solidK.length!=cells || solidCapacity.length!=cells
                    || fluidK.length!=cells || fluidCapacity.length!=cells) throw new IllegalArgumentException("Tile arrays must match its cell count");
            this.shapes=shapes.clone();this.solidK=solidK.clone();this.solidCapacity=solidCapacity.clone();
            this.fluidK=fluidK.clone();this.fluidCapacity=fluidCapacity.clone();
            for(int c=0;c<cells;c++) {
                Objects.requireNonNull(this.shapes[c]);
                if(!nonnegative(this.solidK[c]) || !positive(this.solidCapacity[c])
                        || !nonnegative(this.fluidK[c]) || !positive(this.fluidCapacity[c]))
                    throw new IllegalArgumentException("Invalid tile material values");
            }
        }
        public Key key() { return key; }
        public Version version() { return version; }
        public int edge() { return edge; }
    }
    /** One low-to-high tile interface. Face IDs refer to the joined geometry, not either isolated tile. */
    public static final class Seam {
        private final Key low,high;
        private final int axis;
        private final int[] faces;
        private Seam(Key low,Key high,int axis,List<Integer> faces) {
            this.low=low;this.high=high;this.axis=axis;this.faces=faces.stream().mapToInt(Integer::intValue).toArray();
        }
        public Key low() { return low; }
        public Key high() { return high; }
        public int axis() { return axis; }
        public int faces() { return faces.length; }
        public int face(int index) { return faces[index]; }
    }
    public static final class Joined {
        private final FlowGeometry flow;
        private final ThermalGeometry thermal;
        private final int edge,originX,originY,originZ;
        private final Version version;
        private final Map<Key,Integer> offsets;
        private final List<Seam> seams;
        private Joined(FlowGeometry flow,ThermalGeometry thermal,int edge,int x,int y,int z,Version version,
                       Map<Key,Integer> offsets,List<Seam> seams) {
            this.flow=flow;this.thermal=thermal;this.edge=edge;originX=x;originY=y;originZ=z;this.version=version;
            this.offsets=Map.copyOf(offsets);this.seams=List.copyOf(seams);
        }
        public FlowGeometry flow() { return flow; }
        public ThermalGeometry thermal() { return thermal; }
        public Version version() { return version; }
        public int originX() { return originX; }
        public int originY() { return originY; }
        public int originZ() { return originZ; }
        public List<Seam> seams() { return seams; }
        /** Map source/material cells once at preparation time; numerical loops use the existing flat arrays. */
        public int cell(Key key,int localCell) {
            var offset=offsets.get(key);
            if(offset==null || localCell<0 || localCell>=edge*edge*edge) throw new IllegalArgumentException("Cell outside joined tiles");
            return flow.index(key.x*edge-originX+localCell%edge,key.y*edge-originY+(localCell/edge)%edge,key.z*edge-originZ+localCell/(edge*edge));
        }
    }
    private record Interface(Key low,int axis) { }

    /** Rectangular compatibility entry point. Live callers still need independent coverage admission.
     * cellLimit bounds represented cells, not fluid-node memory or CPU; no production default is selected here.
     */
    public static Joined join(List<Tile> input,int cellLimit,boolean openExterior,ThermalGeometry.Coefficients coefficients) {
        return join(input,cellLimit,openExterior,coefficients,Integer.MAX_VALUE,Integer.MAX_VALUE);
    }
    public static Joined join(List<Tile> input,int cellLimit,boolean openExterior,ThermalGeometry.Coefficients coefficients,int nodeLimit,int faceLimit) {
        return assemble(input,cellLimit,openExterior,coefficients,nodeLimit,faceLimit,false);
    }
    public static Joined joinSparse(List<Tile> input,int cellLimit,boolean openExterior,ThermalGeometry.Coefficients coefficients,int nodeLimit,int faceLimit) {
        return assemble(input,cellLimit,openExterior,coefficients,nodeLimit,faceLimit,true);
    }
    private static Joined assemble(List<Tile> input,int cellLimit,boolean openExterior,ThermalGeometry.Coefficients coefficients,int nodeLimit,int faceLimit,boolean sparse) {
        if(input.isEmpty() || cellLimit<1) throw new IllegalArgumentException("Tiles and a positive cell limit required");
        Objects.requireNonNull(coefficients);var tiles=new TreeMap<Key,Tile>();
        var first=Objects.requireNonNull(input.getFirst());int edge=first.edge;var version=first.version;
        int lx=Integer.MAX_VALUE,ly=lx,lz=lx,hx=Integer.MIN_VALUE,hy=hx,hz=hx;
        long actualCells=0;
        for(var tile:input) {
            if(tile.edge!=edge || !tile.version.equals(version)) throw new IllegalArgumentException("Tiles require matching size and capture versions");
            if(tiles.putIfAbsent(tile.key,tile)!=null) throw new IllegalArgumentException("Duplicate tile ownership");
            actualCells+=tile.shapes.length;if(actualCells>cellLimit) throw new IllegalArgumentException("Joined cell limit exceeded");
            var k=tile.key;lx=Math.min(lx,k.x);ly=Math.min(ly,k.y);lz=Math.min(lz,k.z);
            hx=Math.max(hx,k.x);hy=Math.max(hy,k.y);hz=Math.max(hz,k.z);
        }
        long count;
        try { count=Math.multiplyExact(Math.multiplyExact((long)hx-lx+1,(long)hy-ly+1),(long)hz-lz+1); }
        catch(ArithmeticException e) { count=Long.MAX_VALUE; }
        if(!sparse && count!=tiles.size()) throw new IllegalArgumentException("Only complete rectangular tile groups are supported; missing/irregular tiles require sparse assembly");
        int ox=origin(lx,hx,edge),oy=origin(ly,hy,edge),oz=origin(lz,hz,edge);
        int nx=Math.toIntExact(((long)hx-lx+1)*edge),ny=Math.toIntExact(((long)hy-ly+1)*edge),nz=Math.toIntExact(((long)hz-lz+1)*edge);
        int[] coordinates=new int[tiles.size()*3];int at=0;for(var k:tiles.keySet()) { coordinates[at++]=k.x-lx;coordinates[at++]=k.y-ly;coordinates[at++]=k.z-lz; }
        var layout=CellLayout.tiles(edge,coordinates);
        int cells=(int)actualCells;var builder=new FlowGeometry.Builder(layout).revision(version.geometry).openExterior(openExterior).admissionLimits(nodeLimit,faceLimit);
        double[] sk=new double[cells],sc=new double[cells],fk=new double[cells],fc=new double[cells];
        var offsets=new HashMap<Key,Integer>();var seamFaces=new LinkedHashMap<Interface,List<Integer>>();
        for(var tile:tiles.values()) {
            int x0=(tile.key.x-lx)*edge,y0=(tile.key.y-ly)*edge,z0=(tile.key.z-lz)*edge;
            offsets.put(tile.key,layout.index(x0,y0,z0));
            for(int c=0;c<tile.shapes.length;c++) {
                int x=x0+c%edge,y=y0+(c/edge)%edge,z=z0+c/(edge*edge),i=layout.index(x,y,z);
                builder.cell(x,y,z,tile.shapes[c]);sk[i]=tile.solidK[c];sc[i]=tile.solidCapacity[c];fk[i]=tile.fluidK[c];fc[i]=tile.fluidCapacity[c];
            }
            if(tile.key.x<hx && tiles.containsKey(new Key(tile.key.x+1,tile.key.y,tile.key.z))) seamFaces.put(new Interface(tile.key,0),new ArrayList<>());
            if(tile.key.y<hy && tiles.containsKey(new Key(tile.key.x,tile.key.y+1,tile.key.z))) seamFaces.put(new Interface(tile.key,1),new ArrayList<>());
            if(tile.key.z<hz && tiles.containsKey(new Key(tile.key.x,tile.key.y,tile.key.z+1))) seamFaces.put(new Interface(tile.key,2),new ArrayList<>());
        }
        var flow=builder.build();var thermal=new ThermalGeometry(flow,sk,sc,fk,fc,coefficients);
        for(int f=0;f<flow.faces();f++) if(!flow.exterior(f)) {
            int a=flow.nodeCell[flow.low[f]],axis=flow.axis[f];
            int coordinate=axis==0?flow.cellX(a):axis==1?flow.cellY(a):flow.cellZ(a);
            if(coordinate%edge==edge-1) {
                var low=new Key(lx+flow.cellX(a)/edge,ly+flow.cellY(a)/edge,lz+flow.cellZ(a)/edge);seamFaces.get(new Interface(low,axis)).add(f);
            }
        }
        var seams=new ArrayList<Seam>();
        seamFaces.forEach((s,faces)->seams.add(new Seam(s.low,new Key(s.low.x+(s.axis==0?1:0),s.low.y+(s.axis==1?1:0),s.low.z+(s.axis==2?1:0)),s.axis,faces)));
        return new Joined(flow,thermal,edge,ox,oy,oz,version,offsets,seams);
    }
    private static int origin(int low,int high,int edge) {
        long first=(long)low*edge,last=((long)high+1)*edge-1;
        if(first<Integer.MIN_VALUE || last>Integer.MAX_VALUE) throw new IllegalArgumentException("Tile block coordinates overflow");
        return (int)first;
    }
    private static boolean nonnegative(double x) { return Double.isFinite(x) && x>=0; }
    private static boolean positive(double x) { return Double.isFinite(x) && x>0; }
}
