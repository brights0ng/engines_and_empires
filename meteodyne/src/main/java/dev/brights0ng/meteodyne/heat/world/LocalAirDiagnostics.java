// Written by ChatGPT.
// Updated by Claude: reported weather grid rebuilds and short steps.
package dev.brights0ng.meteodyne.heat.world;

import java.util.Locale;

/** Immutable diagnostic readings. No production freshness threshold or gauge convention is selected here. */
final class LocalAirDiagnostics {
    enum Availability { NO_FIELD, OUTSIDE_DOMAIN, OCCUPIED, AIR }
    record Sample(Availability availability,long throughTick,long ageTicks,double temperature,double x,double y,double z) { }
    static Sample sample(LocalAirRun.Frame frame,long now,double x,double y,double z) {
        if(!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)) throw new IllegalArgumentException("Finite sample position required");
        if(frame==null) return unavailable(Availability.NO_FIELD,-1,-1);
        long age=age(frame,now);var b=frame.bounds;
        double lx=x-b.x(),ly=y-b.y(),lz=z-b.z();
        if(lx<0 || ly<0 || lz<0 || lx>=b.nx() || ly>=b.ny() || lz>=b.nz()) return unavailable(Availability.OUTSIDE_DOMAIN,frame.throughTick,age);
        if(b.index((int)lx,(int)ly,(int)lz)<0) return unavailable(Availability.OUTSIDE_DOMAIN,frame.throughTick,age);
        int node=frame.flow.nodeAt(lx,ly,lz);
        if(node<0) return unavailable(Availability.OCCUPIED,frame.throughTick,age);
        return new Sample(Availability.AIR,frame.throughTick,age,frame.temperature(node),frame.velocity(node,0),frame.velocity(node,1),frame.velocity(node,2));
    }
    private static Sample unavailable(Availability status,long tick,long age) { return new Sample(status,tick,age,Double.NaN,Double.NaN,Double.NaN,Double.NaN); }
    static long age(LocalAirRun.Frame frame,long now) {
        if(now<frame.throughTick) throw new IllegalArgumentException("Sample time precedes diagnostic frame");
        return now-frame.throughTick;
    }
    static String field(LocalAirRun.Frame frame,long now) {
        if(frame==null) return "No published coupled field.";
        var q=frame.quality;
        return String.format(Locale.ROOT,"Frame tick %d; age/backlog %d ticks; %d steps; %d air nodes, %d faces; max divergence %.3g; pressure residual %.3g; thermal residual %.3g C; step energy error %.3g; total energy balance error %.3g; %d pressure iterations, %d thermal sweeps, grid rebuilds %d, short steps %d.",
                frame.throughTick,age(frame,now),frame.acceptedSteps,frame.flow.nodes(),frame.flow.faces(),q.maxDivergence(),q.maxPressureResidual(),
                q.maxThermalResidual(),q.maxStepEnergyError(),q.energyBalanceError(),q.pressureIterations(),q.thermalSweeps(),frame.gridRebuilds,frame.shortSteps);
    }
}
