// Updated by ChatGPT: exposed represented-cell air-state ranges for independent thermal storage.
// Written by ChatGPT; extended by ChatGPT with reusable crossing reciprocals and exact lattice scaling.
package dev.brights0ng.paradynelib.heat.air;

import java.util.Arrays;
import java.util.Objects;

/** Immutable staggered shared-face geometry. One node per connected air component of a block;
 * one signed velocity/flux per overlapping component pair on a shared face. No live-world types.
 */
public final class FlowGeometry {
    public final int nx, ny, nz;
    public final long revision;
    public final CellLayout layout;
    private final int[] neighbors;
    final FlowShape[] shapes;
    final int[] starts, nodeCell, low, high, axis, incidentStart, incident, component;
    final double[] volume, cx, cy, cz, area, distance, fx, fy, fz, solidArea, fluidArea;
    final int components;

    private FlowGeometry(Builder b) {
        nx=b.nx;ny=b.ny;nz=b.nz;layout=b.layout;revision=b.revision;shapes=b.shapes.clone();
        neighbors=layout.dense()?null:new int[Math.multiplyExact(shapes.length,6)];
        if(neighbors!=null) for(int c=0;c<shapes.length;c++) for(int a=0;a<3;a++) for(int side=0;side<2;side++) neighbors[c*6+a*2+side]=layout.neighbor(c,a,side);
        starts=new int[shapes.length+1];
        for(int c=0;c<shapes.length;c++) {
            starts[c+1]=Math.addExact(starts[c],shapes[c].components());
            if(starts[c+1]>b.nodeLimit) throw new IllegalArgumentException("Flow node admission limit exceeded");
        }
        int n=starts[shapes.length];
        nodeCell=new int[n];volume=new double[n];cx=new double[n];cy=new double[n];cz=new double[n];
        solidArea=new double[n];fluidArea=new double[n];
        for(int c=0;c<shapes.length;c++) {
            int x=cellX(c),y=cellY(c),z=cellZ(c);FlowShape s=shapes[c];
            for(int k=0;k<s.components();k++) {
                int i=starts[c]+k;nodeCell[i]=c;volume[i]=s.volume[k];
                cx[i]=x+s.cx[k];cy[i]=y+s.cy[k];cz[i]=z+s.cz[k];
                solidArea[i]=s.solidArea[k];fluidArea[i]=s.fluidArea[k];
            }
        }
        Faces faces=new Faces((int)Math.min(b.faceLimit,Math.max(16L,(long)n*3)),b.faceLimit);
        int[] pa=new int[256],pb=new int[256],count=new int[256],first=new int[256];
        for(int cell=0;cell<shapes.length;cell++) {
            int x=cellX(cell),y=cellY(cell),z=cellZ(cell);
            for(int a=0;a<3;a++) {
                if(neighbor(cell,a,0)<0 && b.openExterior) { boundary(faces,cell,a,0,x,y,z,pa,pb,count,first); }
                int next=neighbor(cell,a,1);
                if(next>=0) {
                    interfaceFaces(faces,cell,next,a,x,y,z,pa,pb,count,first);
                } else if(b.openExterior) { boundary(faces,cell,a,1,x,y,z,pa,pb,count,first); }
            }
        }
        low=Arrays.copyOf(faces.low,faces.size);high=Arrays.copyOf(faces.high,faces.size);axis=Arrays.copyOf(faces.axis,faces.size);
        area=Arrays.copyOf(faces.area,faces.size);distance=Arrays.copyOf(faces.distance,faces.size);
        fx=Arrays.copyOf(faces.x,faces.size);fy=Arrays.copyOf(faces.y,faces.size);fz=Arrays.copyOf(faces.z,faces.size);
        incidentStart=new int[n+1];
        for(int f=0;f<low.length;f++) { if(low[f]>=0) { incidentStart[low[f]+1]++; } if(high[f]>=0) { incidentStart[high[f]+1]++; } }
        for(int i=0;i<n;i++) { incidentStart[i+1]+=incidentStart[i]; }
        incident=new int[incidentStart[n]];int[] cursor=incidentStart.clone();
        for(int f=0;f<low.length;f++) { if(low[f]>=0) { incident[cursor[low[f]]++]=f; } if(high[f]>=0) { incident[cursor[high[f]]++]=f; } }
        component=new int[n];Arrays.fill(component,-1);int[] queue=new int[n];int groups=0;
        for(int seed=0;seed<n;seed++) {
            if(component[seed]>=0) { continue; }
            int read=0,write=0;queue[write++]=seed;component[seed]=groups;
            while(read<write) {
                int i=queue[read++];
                for(int j=incidentStart[i];j<incidentStart[i+1];j++) {
                    int f=incident[j],next=low[f]==i?high[f]:low[f];
                    if(next>=0 && component[next]<0) { component[next]=groups;queue[write++]=next; }
                }
            }
            groups++;
        }
        components=groups;
    }

    private void interfaceFaces(Faces out,int aCell,int bCell,int a,int x,int y,int z,int[] pa,int[] pb,int[] counts,int[] first) {
        FlowShape left=shapes[aCell],right=shapes[bCell];
        if(left==FlowShape.EMPTY && right==FlowShape.EMPTY) {
            add(out,starts[aCell],starts[bCell],a,1,x,y,z,1,0.5,0.5);return;
        }
        int pairs=0;
        for(int v=0;v<16;v++) for(int u=0;u<16;u++) {
            int l=left.patch(a,1,u,v),r=right.patch(a,0,u,v);
            if(l>=0 && r>=0) {
                int k=0;while(k<pairs && (pa[k]!=l || pb[k]!=r)) { k++; }
                if(k==pairs) { pa[k]=l;pb[k]=r;counts[k]=0;first[k]=v*16+u;pairs++; }
                counts[k]++;
            } else if(l>=0) { exchange(starts[aCell]+l,r); }
            else if(r>=0) { exchange(starts[bCell]+r,l); }
        }
        for(int k=0;k<pairs;k++) {
            add(out,starts[aCell]+pa[k],starts[bCell]+pb[k],a,1,x,y,z,counts[k]/256.0,
                    ((first[k]&15)+0.5)/16,((first[k]>>4)+0.5)/16);
        }
    }

    private void boundary(Faces out,int cell,int a,int side,int x,int y,int z,int[] pa,int[] pb,int[] counts,int[] first) {
        FlowShape shape=shapes[cell];
        if(shape==FlowShape.EMPTY) { add(out,side==0?-1:starts[cell],side==0?starts[cell]:-1,a,side,x,y,z,1,0.5,0.5);return; }
        int pairs=0;
        for(int v=0;v<16;v++) for(int u=0;u<16;u++) {
            int label=shape.patch(a,side,u,v);if(label<0) { continue; }
            int k=0;while(k<pairs && pa[k]!=label) { k++; }
            if(k==pairs) { pa[k]=label;counts[k]=0;first[k]=v*16+u;pairs++; }counts[k]++;
        }
        for(int k=0;k<pairs;k++) {
            int i=starts[cell]+pa[k];
            add(out,side==0?-1:i,side==0?i:-1,a,side,x,y,z,counts[k]/256.0,((first[k]&15)+0.5)/16,((first[k]>>4)+0.5)/16);
        }
    }

    private void exchange(int node,int occupied) {
        if(occupied==FlowShape.SOLID) { solidArea[node]+=1.0/256; }
        if(occupied==FlowShape.FLUID) { fluidArea[node]+=1.0/256; }
    }

    private void add(Faces out,int l,int h,int a,int side,int x,int y,int z,double opening,double u,double v) {
        double px=x+(a==0?side:u),py=y+(a==1?side:a==0?u:v),pz=z+(a==2?side:v);
        double p=a==0?px:a==1?py:pz;
        double lc=l<0?p:(a==0?cx[l]:a==1?cy[l]:cz[l]);
        double hc=h<0?p:(a==0?cx[h]:a==1?cy[h]:cz[h]);
        out.add(l,h,a,opening,hc-lc,px,py,pz);
    }

    public int index(int x,int y,int z) { return layout.index(x,y,z); }
    public int cells() { return shapes.length; }
    /** Half-open range of independent air pockets belonging to this represented cell. */
    public int firstNode(int cell) { return starts[cell]; }
    public int endNode(int cell) { return starts[cell+1]; }
    public int cellX(int cell) { return layout.x(cell); }
    public int cellY(int cell) { return layout.y(cell); }
    public int cellZ(int cell) { return layout.z(cell); }
    public int neighbor(int cell,int axis,int side) { return neighbors==null?layout.neighbor(cell,axis,side):neighbors[cell*6+axis*2+side]; }
    public int nodes() { return volume.length; }
    public int faces() { return low.length; }
    public int components() { return components; }
    public double airVolume(int node) { return volume[node]; }
    public double solidInterfaceArea(int node) { return solidArea[node]; }
    public double fluidInterfaceArea(int node) { return fluidArea[node]; }
    public double solidVolume(int cell) { return shapes[cell].solidVolume(); }
    public double fluidVolume(int cell) { return shapes[cell].fluidVolume(); }
    public int faceLow(int face) { return low[face]; }
    public int faceHigh(int face) { return high[face]; }
    public int faceAxis(int face) { return axis[face]; }
    public double faceArea(int face) { return area[face]; }
    public boolean exterior(int face) { return low[face]<0 || high[face]<0; }

    public int nodeAt(double x,double y,double z) {
        if(!(x>=0 && y>=0 && z>=0 && x<nx && y<ny && z<nz)) { return -1; }
        int ix=(int)x,iy=(int)y,iz=(int)z,c=index(ix,iy,iz);
        if(c<0) return -1;
        int label=shapes[c].label((int)((x-ix)*16),(int)((y-iy)*16),(int)((z-iz)*16));
        return label<0?-1:starts[c]+label;
    }

    static final class Trace { double x,y,z;int node;boolean blocked; }

    /** Hierarchical voxel traversal: whole-block steps in empty cells, sixteenths only within partial shapes.
     * Every crossed face is checked, including conservative corner ties. End-point clamping alone is insufficient.
     */
    void trace(double x,double y,double z,double dx,double dy,double dz,Trace result) {
        trace(x,y,z,dx,dy,dz,result,true);
    }

    void trace(double x,double y,double z,double dx,double dy,double dz,Trace result,boolean cachedCrossings) {
        final double epsilon=1e-8;
        double rx=cachedCrossings?traceReciprocal(dx):0,ry=cachedCrossings?traceReciprocal(dy):0,
                rz=cachedCrossings?traceReciprocal(dz):0;
        double t=0;int node=nodeAt(x,y,z);result.blocked=false;
        if(node<0) { throw new IllegalArgumentException("Trace must start in represented air"); }
        int cap=8+(int)Math.ceil((Math.abs(dx)+Math.abs(dy)+Math.abs(dz))*48);
        for(int step=0;step<cap && t<1;step++) {
            double px=x+t*dx,py=y+t*dy,pz=z+t*dz;
            int cell=index((int)px,(int)py,(int)pz),scale=shapes[cell]==FlowShape.EMPTY?1:16;
            double tx=crossing(px,dx,rx,scale),ty=crossing(py,dy,ry,scale),tz=crossing(pz,dz,rz,scale);
            double advance=Math.min(tx,Math.min(ty,tz));
            if(t+advance>=1) {
                int end=nodeAt(x+dx,y+dy,z+dz);
                if(end>=0) { result.x=x+dx;result.y=y+dy;result.z=z+dz;result.node=end;return; }
            }
            double next=Math.min(1,t+advance),before=Math.max(t,next-epsilon),after=Math.min(1,next+epsilon);
            double bx=x+before*dx,by=y+before*dy,bz=z+before*dz;
            double ax=x+after*dx,ay=y+after*dy,az=z+after*dz;
            boolean wall=(tx<=advance+1e-12 && nodeAt(ax,by,bz)<0)
                    || (ty<=advance+1e-12 && nodeAt(bx,ay,bz)<0)
                    || (tz<=advance+1e-12 && nodeAt(bx,by,az)<0);
            int nextNode=nodeAt(ax,ay,az);
            if(wall || nextNode<0) { result.x=bx;result.y=by;result.z=bz;result.node=node;result.blocked=true;return; }
            t=after;node=nextNode;
        }
        result.x=x+t*dx;result.y=y+t*dy;result.z=z+t*dz;result.node=node;result.blocked=t<1;
    }

    private static double traceReciprocal(double d) {
        double r=1/d;
        return Double.isFinite(r) && Math.abs(r)>=Double.MIN_NORMAL ? r : 0;
    }

    private static double crossing(double p,double d,double reciprocal,int scale) {
        if(d==0) { return Double.POSITIVE_INFINITY; }
        double lattice=d>0?Math.floor(p*scale)+1:Math.ceil(p*scale)-1;
        if(reciprocal!=0) {
            // The only lattice scales are 1 and 16. Their reciprocals are exact binary values.
            double plane=lattice*(scale==1?1:.0625);
            return Math.max(1e-12,(plane-p)*reciprocal);
        }
        return Math.max(1e-12,(lattice/scale-p)/d);
    }

    public static final class Builder {
        final int nx,ny,nz;final CellLayout layout;final FlowShape[] shapes;long revision;boolean openExterior;
        int nodeLimit=Integer.MAX_VALUE,faceLimit=Integer.MAX_VALUE;
        public Builder(int nx,int ny,int nz) {
            this(CellLayout.dense(nx,ny,nz));
        }
        public Builder(CellLayout layout) {
            this.layout=Objects.requireNonNull(layout);nx=layout.nx;ny=layout.ny;nz=layout.nz;shapes=new FlowShape[layout.cells()];Arrays.fill(shapes,FlowShape.EMPTY);
        }
        public Builder cell(int x,int y,int z,FlowShape shape) {
            int cell=layout.index(x,y,z);if(cell<0) throw new IndexOutOfBoundsException();
            shapes[cell]=Objects.requireNonNull(shape);return this;
        }
        public Builder openExterior(boolean open) { openExterior=open;return this; }
        /** Optional exact allocation guards, checked before node arrays or excess face growth. */
        public Builder admissionLimits(int nodes,int faces) {
            if(nodes<0 || faces<0) throw new IllegalArgumentException("Nonnegative flow admission limits required");
            nodeLimit=nodes;faceLimit=faces;return this;
        }
        public Builder revision(long value) { revision=value;return this; }
        public FlowGeometry build() { return new FlowGeometry(this); }
    }

    private static final class Faces {
        int size;final int limit;int[] low,high,axis;double[] area,distance,x,y,z;
        Faces(int capacity,int limit) { this.limit=limit;capacity=Math.max(1,capacity);low=new int[capacity];high=new int[capacity];axis=new int[capacity];area=new double[capacity];distance=new double[capacity];x=new double[capacity];y=new double[capacity];z=new double[capacity]; }
        void add(int l,int h,int a,double opening,double d,double px,double py,double pz) {
            if(!(d>0)) { throw new IllegalArgumentException("Positive face distance required"); }
            if(size>=limit) throw new IllegalArgumentException("Flow face admission limit exceeded");
            if(size==low.length) {
                int n=(int)Math.min(limit,(long)size*2);low=Arrays.copyOf(low,n);high=Arrays.copyOf(high,n);axis=Arrays.copyOf(axis,n);
                area=Arrays.copyOf(area,n);distance=Arrays.copyOf(distance,n);x=Arrays.copyOf(x,n);y=Arrays.copyOf(y,n);z=Arrays.copyOf(z,n);
            }
            low[size]=l;high[size]=h;axis[size]=a;area[size]=opening;distance[size]=d;x[size]=px;y[size]=py;z[size]=pz;size++;
        }
    }
}
