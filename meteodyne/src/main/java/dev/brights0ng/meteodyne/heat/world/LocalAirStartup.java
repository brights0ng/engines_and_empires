// Written by ChatGPT.
package dev.brights0ng.meteodyne.heat.world;

import java.util.*;
import java.util.function.LongSupplier;

/** Owner-thread request-to-first-field clock. Phase durations include waits, and are not CPU costs. */
final class LocalAirStartup {
    enum Phase { CAPTURE, PLAN, COVERAGE, BAKE, THERMAL_INPUT, DRIVEN_INPUT, ESTIMATE, INITIALIZE, LAYOUT_WAIT, CATCH_UP, READY, STOPPED }
    record Report(String phase,double elapsedMs,long requestTick,Double firstFieldMs,Long firstFieldObservedTick,
                  Long firstFieldThroughTick,Double caughtUpMs,Long caughtUpObservedTick,Map<String,Double> phaseMs,
                  long workerJobs,double workerWallMs,double executorQueueMs,double completionWaitMs) { }
    private final LongSupplier nanos;
    private final long created;
    private long changed,requestTick=-1,ended=-1,jobs,worker,queue,collection;
    private Phase phase=Phase.CAPTURE;
    private final EnumMap<Phase,Long> phases=new EnumMap<>(Phase.class);
    private Double first,caughtUp;
    private Long firstTick,throughTick,caughtTick;
    LocalAirStartup() { this(System::nanoTime); }
    LocalAirStartup(LongSupplier nanos) { this.nanos=nanos;created=changed=nanos.getAsLong(); }
    boolean ready() { return ended>=0 && phase==Phase.READY; }
    void update(Phase next,long tick,Long frameTick) {
        if(ended>=0) { if(phase!=Phase.STOPPED || next==Phase.STOPPED) return;ended=-1; }
        long now=nanos.getAsLong();
        if(requestTick<0 && tick>=0) requestTick=tick;
        if(frameTick!=null && first==null) { first=(now-created)/1e6;firstTick=tick;throughTick=frameTick; }
        if(frameTick!=null && tick>=frameTick && tick-frameTick<LocalAirRun.PHYSICAL_TICKS) {
            caughtUp=(now-created)/1e6;caughtTick=tick;next=Phase.READY;
        }
        if(next!=phase) { phases.merge(phase,now-changed,Long::sum);phase=next;changed=now; }
        if(next==Phase.READY || next==Phase.STOPPED) { phases.merge(phase,now-changed,Long::sum);ended=now; }
    }
    void completed(LocalAirMetrics.Timing timing,long completedNanos) {
        if(ended>=0 || timing==null) return;
        jobs++;worker+=timing.wallNanos();queue+=timing.queueNanos();
        collection+=Math.max(0,nanos.getAsLong()-completedNanos);
    }
    Report report() {
        long now=ended>=0?ended:nanos.getAsLong();
        var values=new TreeMap<String,Double>();
        for(var e:phases.entrySet()) values.put(e.getKey().name(),e.getValue()/1e6);
        if(ended<0) values.merge(phase.name(),(now-changed)/1e6,Double::sum);
        return new Report(phase.name(),(now-created)/1e6,requestTick,first,firstTick,throughTick,caughtUp,caughtTick,
                Map.copyOf(values),jobs,worker/1e6,queue/1e6,collection/1e6);
    }
}
