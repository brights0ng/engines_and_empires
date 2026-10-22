// Written by ChatGPT.
package dev.brights0ng.paradynelib.heat.air;

import java.util.Arrays;
import java.util.HashMap;

/** Fixed-bounds remapping for an edit of retained material. No pressure state or world objects are transferred.
 * Air transfers only through geometric overlap; solid/fluid storage keeps cell ownership.
 * New capacity starts at explicit ambient, removed capacity leaves with its old temperature.
 */
public final class GeometryTransfer {
    public static final class Mapping {
        private final double[] temperatures,velocities;
        private final ThermalGeometry target;
        public final double incomingEnergy,outgoingEnergy;
        private Mapping(ThermalGeometry target,double[] temperatures,double[] velocities,double incoming,double outgoing) {
            this.temperatures=temperatures;this.velocities=velocities;incomingEnergy=incoming;outgoingEnergy=outgoing;
            this.target=target;
        }
        public double temperature(int state) { return temperatures[state]; }
        public double faceVelocity(int face) { return velocities[face]; }
        public FlowGrid.Step initialize(CoupledAirGrid grid) { return grid.initializeMapped(target,temperatures,velocities); }
    }
    private record Face(int axis,int lowCell,int highCell,int lowLabel,int highLabel) { }
    private static Face face(FlowGeometry g,int face) {
        int l=g.low[face],h=g.high[face],lc=l<0?-1:g.nodeCell[l],hc=h<0?-1:g.nodeCell[h];
        return new Face(g.axis[face],lc,hc,l<0?-1:l-g.starts[lc],h<0?-1:h-g.starts[hc]);
    }
    private static boolean same(FlowShape a,FlowShape b) {
        return a==b || a.uniform==b.uniform && Arrays.equals(a.labels,b.labels);
    }
    private static void accumulate(int oldState,int newState,double capacity,double[] oldTemperature,
                                   double[] energy,double[] oldKept,double[] newKept) {
        if(oldState<0 || newState<0 || capacity==0) return;
        energy[newState]+=capacity*oldTemperature[oldState];oldKept[oldState]+=capacity;newKept[newState]+=capacity;
    }
    /** Caller must prove material identity is retained (e.g. a door OPEN toggle), not a block replacement.
     * Bounds/origin must be identical; resizing and material replacement require their own ownership policy.
     */
    public static Mapping map(ThermalGeometry old,ThermalGeometry next,double[] oldTemperature,double[] oldFlux,double ambient) {
        var a=old.flow;var b=next.flow;
        if(old.unrepresentedFluidCells()>0 || next.unrepresentedFluidCells()>0 || a.nx!=b.nx || a.ny!=b.ny || a.nz!=b.nz || oldTemperature.length!=old.states() || oldFlux.length!=a.faces()
                || !Double.isFinite(ambient) || ambient<=-273.15) throw new IllegalArgumentException("Invalid fixed-bounds transfer");
        for(double t:oldTemperature) if(!Double.isFinite(t)) throw new IllegalArgumentException("Nonfinite old temperature");
        for(double f:oldFlux) if(!Double.isFinite(f)) throw new IllegalArgumentException("Nonfinite old flux");
        double[] energy=new double[next.states()],oldKept=new double[old.states()],newKept=new double[next.states()];
        for(int cell=0;cell<a.shapes.length;cell++) {
            var left=a.shapes[cell];var right=b.shapes[cell];
            if(same(left,right)) {
                for(int k=0;k<left.components();k++) accumulate(a.starts[cell]+k,b.starts[cell]+k,left.volume[k],oldTemperature,energy,oldKept,newKept);
            } else {
                for(int z=0;z<16;z++) for(int y=0;y<16;y++) for(int x=0;x<16;x++) {
                    int l=left.label(x,y,z),r=right.label(x,y,z);
                    if(l>=0 && r>=0) accumulate(a.starts[cell]+l,b.starts[cell]+r,1.0/4096,oldTemperature,energy,oldKept,newKept);
                }
            }
            int l=old.materialState(cell),r=next.materialState(cell);
            if(l>=0 && r>=0) accumulate(l,r,Math.min(old.capacity(l),next.capacity(r)),oldTemperature,energy,oldKept,newKept);
            l=old.fluidState(cell);r=next.fluidState(cell);
            if(l>=0 && r>=0) accumulate(l,r,Math.min(old.capacity(l),next.capacity(r)),oldTemperature,energy,oldKept,newKept);
        }
        double incoming=0,outgoing=0;
        for(int i=0;i<next.states();i++) {
            double added=Math.max(0,next.capacity(i)-newKept[i])*ambient;incoming+=added;
            energy[i]=(energy[i]+added)/next.capacity(i);
        }
        for(int i=0;i<old.states();i++) outgoing+=Math.max(0,old.capacity(i)-oldKept[i])*oldTemperature[i];
        var faces=new HashMap<Face,Integer>();
        for(int f=0;f<a.faces();f++) faces.put(face(a,f),f);
        double[] velocities=new double[b.faces()];
        for(int f=0;f<b.faces();f++) {
            Face key=face(b,f);int lc=key.lowCell,hc=key.highCell;
            if((lc<0 || same(a.shapes[lc],b.shapes[lc])) && (hc<0 || same(a.shapes[hc],b.shapes[hc]))) {
                Integer previous=faces.get(key);
                if(previous!=null) velocities[f]=oldFlux[previous]/a.area[previous];
                continue;
            }
            double sum=0;
            for(int v=0;v<16;v++) for(int u=0;u<16;u++) {
                int nl=lc<0?-1:b.shapes[lc].patch(key.axis,1,u,v),nh=hc<0?-1:b.shapes[hc].patch(key.axis,0,u,v);
                if(nl!=key.lowLabel || nh!=key.highLabel) continue;
                int ol=lc<0?-1:a.shapes[lc].patch(key.axis,1,u,v),oh=hc<0?-1:a.shapes[hc].patch(key.axis,0,u,v);
                if(lc>=0 && ol<0 || hc>=0 && oh<0) continue;
                Integer previous=faces.get(new Face(key.axis,lc,hc,ol,oh));
                if(previous!=null) sum+=oldFlux[previous]/a.area[previous]/256;
            }
            velocities[f]=sum/b.area[f];
        }
        return new Mapping(next,energy,velocities,incoming,outgoing);
    }
    private GeometryTransfer() { }
}
