// Updated by ChatGPT: accepted prepared incoming thermal state while retaining donor overlap.
// Written by ChatGPT.
package dev.brights0ng.paradynelib.heat.air;

import java.util.*;

/** Offset/multiple-owner transfer for unchanged overlapping cells. Changed material/topology needs another policy.
 * One target maps once from disjoint donors; a transaction must prove disjoint targets before committing them.
 */
public final class DomainTransfer {
    public record Origin(int x,int y,int z) { }
    public static final class Donor {
        private final ThermalGeometry geometry;
        private final Origin origin;
        private final double[] temperature,flux;
        public Donor(ThermalGeometry geometry,Origin origin,double[] temperature,double[] flux) {
            this.geometry=Objects.requireNonNull(geometry);this.origin=Objects.requireNonNull(origin);
            if(geometry.unrepresentedFluidCells()!=0 || temperature.length!=geometry.states() || flux.length!=geometry.flow.faces())
                throw new IllegalArgumentException("Invalid layout donor arrays");
            this.temperature=temperature.clone();this.flux=flux.clone();
            for(double t:this.temperature) if(!Double.isFinite(t)) throw new IllegalArgumentException("Invalid donor temperature");
            for(double f:this.flux) if(!Double.isFinite(f)) throw new IllegalArgumentException("Invalid donor flux");
            extent(geometry,origin);
        }
        public double energy() { double e=0;for(int i=0;i<temperature.length;i++) e+=temperature[i]*geometry.capacity(i);return e; }
    }
    public static final class Mapping {
        private final ThermalGeometry target;
        private final double[] temperature,velocity;
        public final double retainedEnergy,incomingEnergy;
        private Mapping(ThermalGeometry target,double[] temperature,double[] velocity,double retained,double incoming) {
            this.target=target;this.temperature=temperature;this.velocity=velocity;retainedEnergy=retained;incomingEnergy=incoming;
        }
        public double temperature(int state) { return temperature[state]; }
        public double faceVelocity(int face) { return velocity[face]; }
        public FlowGrid.Step initialize(CoupledAirGrid grid) { return grid.initializeMapped(target,temperature,velocity); }
    }
    private record Face(int axis,long x,long y,long z,int low,int high) { }
    private static Face face(FlowGeometry f,Origin origin,int face) {
        int l=f.low[face],h=f.high[face],cell=f.nodeCell[l>=0?l:h],axis=f.axis[face];
        int x=f.cellX(cell),y=f.cellY(cell),z=f.cellZ(cell);
        if(l>=0) { if(axis==0) x++;else if(axis==1) y++;else z++; }
        return new Face(axis,(long)origin.x+x,(long)origin.y+y,(long)origin.z+z,l<0?-1:l-f.starts[f.nodeCell[l]],h<0?-1:h-f.starts[f.nodeCell[h]]);
    }
    private static void extent(ThermalGeometry g,Origin o) {
        Math.addExact(o.x,g.flow.nx-1);Math.addExact(o.y,g.flow.ny-1);Math.addExact(o.z,g.flow.nz-1);
    }
    private static boolean overlap(Donor a,Donor b) {
        boolean boxes=(long)a.origin.x<b.origin.x+(long)b.geometry.flow.nx && (long)b.origin.x<a.origin.x+(long)a.geometry.flow.nx
                && (long)a.origin.y<b.origin.y+(long)b.geometry.flow.ny && (long)b.origin.y<a.origin.y+(long)a.geometry.flow.ny
                && (long)a.origin.z<b.origin.z+(long)b.geometry.flow.nz && (long)b.origin.z<a.origin.z+(long)a.geometry.flow.nz;
        if(!boxes) return false;
        var f=a.geometry.flow;var g=b.geometry.flow;
        for(int c=0;c<f.cells();c++) {
            long x=(long)a.origin.x+f.cellX(c)-b.origin.x,y=(long)a.origin.y+f.cellY(c)-b.origin.y,z=(long)a.origin.z+f.cellZ(c)-b.origin.z;
            if(x>=0 && y>=0 && z>=0 && x<g.nx && y<g.ny && z<g.nz && g.index((int)x,(int)y,(int)z)>=0) return true;
        }
        return false;
    }
    private static boolean same(FlowShape a,FlowShape b) { return a==b || a.uniform==b.uniform && Arrays.equals(a.labels,b.labels); }
    private static void copy(ThermalGeometry a,ThermalGeometry b,int old,int next,double[] before,double[] after) {
        if((old<0)!=(next<0) || old>=0 && a.capacity(old)!=b.capacity(next)) throw new IllegalArgumentException("Changed storage at retained cell");
        if(old>=0) after[next]=before[old];
    }
    public static Mapping map(List<Donor> input,ThermalGeometry target,Origin origin,double ambient) {
        double[] initial=new double[target.states()];Arrays.fill(initial,ambient);
        return map(input,target,origin,ambient,initial);
    }
    /** Incoming cells use a prepared thermal estimate; donor-owned overlap always takes precedence. */
    public static Mapping map(List<Donor> input,ThermalGeometry target,Origin origin,double ambient,double[] initial) {
        if(initial.length!=target.states()) throw new IllegalArgumentException("Initial state count mismatch");
        for(double t:initial) if(!Double.isFinite(t) || t<=-273.15) throw new IllegalArgumentException("Invalid incoming temperature");
        var donors=List.copyOf(input);var next=target.flow;extent(target,origin);
        if(target.unrepresentedFluidCells()!=0 || !Double.isFinite(ambient) || ambient<=-273.15) throw new IllegalArgumentException("Invalid layout target");
        for(int i=0;i<donors.size();i++) for(int j=0;j<i;j++) if(overlap(donors.get(i),donors.get(j))) throw new IllegalArgumentException("Duplicate donor ownership");
        double[] temperatures=initial.clone();double retained=0,incoming=0;
        for(int cell=0;cell<next.shapes.length;cell++) {
            int x=origin.x+next.cellX(cell),y=origin.y+next.cellY(cell),z=origin.z+next.cellZ(cell);Donor found=null;int previous=-1;
            for(var donor:donors) {
                long lx=(long)x-donor.origin.x,ly=(long)y-donor.origin.y,lz=(long)z-donor.origin.z;var f=donor.geometry.flow;
                if(lx>=0 && ly>=0 && lz>=0 && lx<f.nx && ly<f.ny && lz<f.nz) { int index=f.index((int)lx,(int)ly,(int)lz);if(index>=0) { found=donor;previous=index;break; } }
            }
            if(found!=null) {
                var old=found.geometry;var f=old.flow;
                if(!same(f.shapes[previous],next.shapes[cell])) throw new IllegalArgumentException("Changed shape at retained cell");
                for(int k=0;k<next.shapes[cell].components();k++) copy(old,target,f.starts[previous]+k,next.starts[cell]+k,found.temperature,temperatures);
                copy(old,target,old.materialState(previous),target.materialState(cell),found.temperature,temperatures);
                copy(old,target,old.fluidState(previous),target.fluidState(cell),found.temperature,temperatures);
            }
            double energy=0;for(int k=next.starts[cell];k<next.starts[cell+1];k++) energy+=target.capacity(k)*temperatures[k];
            int state=target.materialState(cell);if(state>=0) energy+=target.capacity(state)*temperatures[state];
            state=target.fluidState(cell);if(state>=0) energy+=target.capacity(state)*temperatures[state];
            if(found==null) incoming+=energy;else retained+=energy;
        }
        var velocities=new HashMap<Face,Double>();
        for(var donor:donors) for(int f=0;f<donor.geometry.flow.faces();f++) {
            var key=face(donor.geometry.flow,donor.origin,f);
            if(velocities.putIfAbsent(key,donor.flux[f]/donor.geometry.flow.area[f])!=null) throw new IllegalArgumentException("Duplicate donor face ownership");
        }
        double[] velocity=new double[next.faces()];
        for(int f=0;f<velocity.length;f++) velocity[f]=velocities.getOrDefault(face(next,origin,f),0.0);
        return new Mapping(target,temperatures,velocity,retained,incoming);
    }
    private DomainTransfer() { }
}
