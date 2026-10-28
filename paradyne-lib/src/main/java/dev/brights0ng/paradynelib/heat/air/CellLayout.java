// Written by ChatGPT.
package dev.brights0ng.paradynelib.heat.air;

import java.util.*;

/** Immutable dense or compact full-tile cell addressing. No allocation proportional to an omitted envelope.
 * Primitive tile lookup is used for sampling/capture; numerical face neighbors are cached by FlowGeometry.
 */
public final class CellLayout {
    public final int nx,ny,nz;
    private final int edge,cube,cells;
    private final int[] tiles,table;
    private CellLayout(int nx,int ny,int nz,int edge,int[] tiles) {
        if(nx<1 || ny<1 || nz<1) throw new IllegalArgumentException("Positive layout dimensions required");
        this.nx=nx;this.ny=ny;this.nz=nz;this.edge=edge;this.tiles=tiles;
        cube=Math.multiplyExact(Math.multiplyExact(edge,edge),edge);
        cells=tiles==null?Math.multiplyExact(Math.multiplyExact(nx,ny),nz):Math.multiplyExact(tiles.length/3,cube);
        if(tiles==null) table=null;
        else {
            int capacity=1;while(capacity<(long)tiles.length/3*2) capacity=Math.multiplyExact(capacity,2);
            table=new int[capacity];Arrays.fill(table,-1);
            for(int i=0;i<tiles.length/3;i++) { int slot=hash(tiles[i*3],tiles[i*3+1],tiles[i*3+2])&(capacity-1);while(table[slot]>=0) slot=(slot+1)&(capacity-1);table[slot]=i; }
        }
    }
    public static CellLayout dense(int nx,int ny,int nz) { return new CellLayout(nx,ny,nz,1,null); }
    /** Nonnegative tile coordinates, with their envelope anchored at zero. Tile order is canonical z/y/x. */
    public static CellLayout tiles(int edge,int[] xyz) {
        if(edge<1 || edge>32 || xyz.length==0 || xyz.length%3!=0) throw new IllegalArgumentException("Invalid tile layout");
        var entries=new ArrayList<int[]>();int hx=0,hy=0,hz=0;
        for(int i=0;i<xyz.length;i+=3) {
            if(xyz[i]<0 || xyz[i+1]<0 || xyz[i+2]<0) throw new IllegalArgumentException("Layout coordinates must be nonnegative");
            hx=Math.max(hx,xyz[i]);hy=Math.max(hy,xyz[i+1]);hz=Math.max(hz,xyz[i+2]);entries.add(new int[]{xyz[i],xyz[i+1],xyz[i+2]});
        }
        entries.sort(Comparator.<int[]>comparingInt(a->a[2]).thenComparingInt(a->a[1]).thenComparingInt(a->a[0]));
        int[] sorted=new int[xyz.length];int[] previous=null;int i=0;
        for(var t:entries) { if(previous!=null && Arrays.equals(previous,t)) throw new IllegalArgumentException("Duplicate tile");System.arraycopy(t,0,sorted,i,3);i+=3;previous=t; }
        int nx=Math.toIntExact(((long)hx+1)*edge),ny=Math.toIntExact(((long)hy+1)*edge),nz=Math.toIntExact(((long)hz+1)*edge);
        long full;
        try { full=Math.multiplyExact(Math.multiplyExact((long)hx+1,(long)hy+1),(long)hz+1); }catch(ArithmeticException e) { full=Long.MAX_VALUE; }
        if(full==entries.size()) return dense(nx,ny,nz);
        return new CellLayout(nx,ny,nz,edge,sorted);
    }
    public boolean dense() { return tiles==null; }
    public int cells() { return cells; }
    public int x(int cell) { return tiles==null?cell%nx:tiles[(cell/cube)*3]*edge+(cell%cube)%edge; }
    public int y(int cell) { return tiles==null?(cell/nx)%ny:tiles[(cell/cube)*3+1]*edge+((cell%cube)/edge)%edge; }
    public int z(int cell) { return tiles==null?cell/(nx*ny):tiles[(cell/cube)*3+2]*edge+(cell%cube)/(edge*edge); }
    public int index(int x,int y,int z) {
        if(x<0 || y<0 || z<0 || x>=nx || y>=ny || z>=nz) return -1;
        if(tiles==null) return (z*ny+y)*nx+x;
        int tx=x/edge,ty=y/edge,tz=z/edge,slot=hash(tx,ty,tz)&(table.length-1);
        while(table[slot]>=0) {
            int t=table[slot];if(tiles[t*3]==tx && tiles[t*3+1]==ty && tiles[t*3+2]==tz) return t*cube+((z%edge)*edge+y%edge)*edge+x%edge;
            slot=(slot+1)&(table.length-1);
        }
        return -1;
    }
    public int neighbor(int cell,int axis,int side) {
        int x=x(cell),y=y(cell),z=z(cell),d=side==0?-1:1;
        return index(x+(axis==0?d:0),y+(axis==1?d:0),z+(axis==2?d:0));
    }
    /** Sparse metadata only; dense callers derive their own tile coordinates at the required edge. */
    public int tileCount() { return tiles==null?0:tiles.length/3; }
    public int tileCoordinate(int tile,int axis) { return tiles[tile*3+axis]; }
    public int tileEdge() { return edge; }
    private static int hash(int x,int y,int z) { int h=x*0x9e3779b9^Integer.rotateLeft(y*0x85ebca6b,11)^Integer.rotateLeft(z*0xc2b2ae35,22);h^=h>>>16;return h; }
    @Override public boolean equals(Object other) { return other instanceof CellLayout b && nx==b.nx && ny==b.ny && nz==b.nz && edge==b.edge && Arrays.equals(tiles,b.tiles); }
    @Override public int hashCode() { return Objects.hash(nx,ny,nz,edge,Arrays.hashCode(tiles)); }
    @Override public String toString() { return dense()?"dense":"sparse("+tileCount()+" tiles, "+cells+" cells)"; }
}
