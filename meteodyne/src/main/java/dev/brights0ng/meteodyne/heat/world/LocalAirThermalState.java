// Updated by ChatGPT: refined fresh natural temperatures while preserving stored phases.
// Written by ChatGPT.
// Updated by ChatGPT: distinguished live donor initialization from disk restoration.
package dev.brights0ng.meteodyne.heat.world;

import java.util.*;
import static dev.brights0ng.meteodyne.heat.world.LocalAirGeometryCapture.*;

/** Versioned per-cell thermal state. No pressure, flux, world handles, or cumulative source ledger is saved. */
final class LocalAirThermalState {
    record Material(Shape shape,double solidK,double solidCapacity,double fluidK,double fluidCapacity) {
        Material { new Cell(shape,solidK,solidCapacity,fluidK,fluidCapacity); }
    }
    static final class Saved {
        final Material material;
        final long tick;
        private final double[] offsets;
        Saved(Material material,long tick,double[] offsets) {
            this.material=Objects.requireNonNull(material);this.tick=tick;this.offsets=offsets.clone();
            if(tick<0 || offsets.length<1 || offsets.length>4098) throw new IllegalArgumentException("Invalid saved cell");
            for(double t:offsets) if(!Double.isFinite(t) || Math.abs(t)>10000) throw new IllegalArgumentException("Invalid saved offset");
        }
        double[] offsets() { return offsets.clone(); }
        boolean matches(Snapshot snapshot,int cell,int states) { return material.equals(snapshot.material(cell)) && offsets.length==states; }
    }
    /** Reader is captured once on the server thread, then used only by sliced preparation on that thread. */
    interface Reader { Value read(int cell); }
    interface Provider { Reader begin(Snapshot snapshot); }
    record Value(Saved saved,double estimate,boolean persistent,String origin) {
        Value { if(!Double.isFinite(estimate)) throw new IllegalArgumentException("Invalid thermal estimate"); }
        static Value ambient() { return new Value(null,0,false,"AMBIENT"); }
    }
    static final class Initial {
        final Snapshot snapshot;
        private final Value[] cells;
        final boolean persistent,refinementRequired;
        final int restored,estimated;
        final dev.brights0ng.paradynelib.heat.air.AirThermalInitializer.Solution refined;
        final double refinedAmbient;
        Initial(Snapshot snapshot,Value[] cells) {this(snapshot,cells,null,0);}
        private Initial(Snapshot snapshot,Value[] cells,dev.brights0ng.paradynelib.heat.air.AirThermalInitializer.Solution refined,double ambient) {
            this.refined=refined;refinedAmbient=ambient;
            if(cells.length!=snapshot.bounds.cells()) throw new IllegalArgumentException("Initial cell count mismatch");
            this.snapshot=snapshot;this.cells=cells.clone();boolean keep=snapshot.placedSources(),natural=false,fresh=false;int saved=0,estimate=0;
            for(int i=0;i<cells.length;i++) {var c=cells[i];Objects.requireNonNull(c);keep|=c.persistent;saved+=c.saved==null?0:1;estimate+=c.estimate!=0?1:0;natural|=snapshot.natural(i) && snapshot.heat(i)!=null && snapshot.heat(i).active();fresh|=!c.persistent && c.saved==null;}
            refinementRequired=natural && fresh;
            persistent=keep;restored=saved;estimated=estimate;
        }
        Initial refined(dev.brights0ng.paradynelib.heat.air.AirThermalInitializer.Solution solution,double ambient) { return new Initial(snapshot,cells,solution,ambient); }
        boolean needsRefinement() {return refined==null && refinementRequired;}
        dev.brights0ng.paradynelib.heat.air.ThermalTransport.Source[] naturalSources(Prepared prepared,double ambient) {
            snapshot.sources(prepared.thermal(),ambient); // Preserve all active-source boundary admission checks before refinement.
            var out=new ArrayList<dev.brights0ng.paradynelib.heat.air.ThermalTransport.Source>();
            for(int c=0;c<cells.length;c++) if(snapshot.natural(c) && snapshot.heat(c)!=null) out.addAll(snapshot.heat(c).routed(prepared.thermal(),c,ambient));
            return out.toArray(dev.brights0ng.paradynelib.heat.air.ThermalTransport.Source[]::new);
        }
        int restored(Prepared prepared) {
            int count=0;for(int c=0;c<cells.length;c++) if(cells[c].saved!=null && !cells[c].origin.equals("RETAINED_DONOR") && cells[c].saved.matches(snapshot,c,states(prepared,c).length)) count++;return count;
        }
        double[] temperatures(Prepared prepared,double ambient) {
            var thermal=prepared.thermal();var flow=prepared.flow();double[] out=new double[thermal.states()];Arrays.fill(out,ambient);
            for(int c=0;c<cells.length;c++) {
                int[] states=states(prepared,c);var value=cells[c];
                var saved=value.saved;boolean compatible=saved!=null && saved.matches(snapshot,c,states.length);
                for(int k=0;k<states.length;k++) {
                    double t=refined!=null && !compatible && !value.persistent?refined.temperature(states[k])+ambient-refinedAmbient:ambient+(compatible?saved.offsets[k]:value.estimate);
                    if(!Double.isFinite(t) || t<=-273.15 || t>dev.brights0ng.paradynelib.heat.air.FlowSettings.DEFAULT.maxTemperatureC())
                        throw new IllegalArgumentException("Restored temperature outside solver range");
                    out[states[k]]=t;
                }
            }
            return out;
        }
    }
    static int[] states(Prepared prepared,int cell) {
        var f=prepared.flow();var t=prepared.thermal();int m=t.materialState(cell),l=t.fluidState(cell);
        int[] out=new int[f.endNode(cell)-f.firstNode(cell)+(m<0?0:1)+(l<0?0:1)];int i=0;
        for(int n=f.firstNode(cell);n<f.endNode(cell);n++) out[i++]=n;
        if(m>=0) out[i++]=m;if(l>=0) out[i]=l;return out;
    }
    static Saved save(LocalAirRun.Seed seed,int cell) {
        var states=states(seed.prepared(),cell);var frame=seed.frame();double[] offsets=new double[states.length];
        for(int k=0;k<states.length;k++) offsets[k]=frame.temperature(states[k])-frame.weather.ambient();
        return new Saved(seed.snapshot().material(cell),frame.throughTick,offsets);
    }
    private LocalAirThermalState() { }
}
