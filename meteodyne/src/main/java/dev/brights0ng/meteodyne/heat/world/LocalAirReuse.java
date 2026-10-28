// Written by ChatGPT.
package dev.brights0ng.meteodyne.heat.world;

import java.util.Set;
import java.util.function.BooleanSupplier;
import static dev.brights0ng.meteodyne.heat.world.LocalAirGeometryCapture.*;

/** Owner-thread lease on immutable donor evidence. Donor dependencies stay registered until target admission.
 * Never passed to numerical workers. A revision, retirement or intervening topology withdraws the lease.
 */
final class LocalAirReuse {
    final Snapshot snapshot;
    final LocalAirCoverage.Certificate coverage;
    final Set<Long> edits;
    private final LocalAirRun.Seed thermal;
    private final Source donor;
    private final BooleanSupplier valid;
    LocalAirReuse(Snapshot snapshot,LocalAirCoverage.Certificate coverage,Set<Long> edits,Source donor,BooleanSupplier valid) {
        this(snapshot,coverage,edits,donor,valid,null);
    }
    LocalAirReuse(Snapshot snapshot,LocalAirCoverage.Certificate coverage,Set<Long> edits,Source donor,BooleanSupplier valid,LocalAirRun.Seed thermal) {
        if(coverage!=null && !LocalAirCoverage.matches(coverage,snapshot)) throw new IllegalArgumentException("Reuse proof belongs to another snapshot");
        if(thermal!=null && thermal.snapshot()!=snapshot) throw new IllegalArgumentException("Thermal donor belongs to another snapshot");
        this.thermal=thermal;this.snapshot=snapshot;this.coverage=coverage;this.edits=Set.copyOf(edits);this.donor=donor;this.valid=valid;
    }
    boolean accepts(Source target) {
        return target.sameWorld(donor) && target.materialRevision()==snapshot.materialRevision && valid.getAsBoolean()
                && donor.contextId()==snapshot.contextId && donor.geometryRevision()==snapshot.geometryRevision
                && donor.materialRevision()==snapshot.materialRevision;
    }
    LocalAirThermalState.Value temperature(Source target,Snapshot next,int cell,double ambient) {
        if(thermal==null || !accepts(target)) return null;
        var b=next.bounds;var old=snapshot.bounds;
        int prior=old.index(b.x()+b.cellX(cell)-old.x(),b.y()+b.cellY(cell)-old.y(),b.z()+b.cellZ(cell)-old.z());
        if(prior<0 || !snapshot.material(prior).equals(next.material(cell))) return null;
        var states=LocalAirThermalState.states(thermal.prepared(),prior);double[] offsets=new double[states.length];
        for(int i=0;i<states.length;i++) offsets[i]=thermal.frame().temperature(states[i])-ambient;
        var saved=new LocalAirThermalState.Saved(snapshot.material(prior),thermal.frame().throughTick,offsets);
        return new LocalAirThermalState.Value(saved,0,thermal.frame().persistentThermal,"RETAINED_DONOR");
    }
}
