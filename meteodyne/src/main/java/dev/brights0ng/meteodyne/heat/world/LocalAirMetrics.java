// Updated by ChatGPT: separated natural estimate worker costs.
// Written by ChatGPT.
// Updated by ChatGPT: aggregate continuation slices separately from owner collections.
package dev.brights0ng.meteodyne.heat.world;

import java.util.EnumMap;
import java.util.Map;

/** Bounded per-request counters across rebuilds; immutable snapshots, no raw event history. */
final class LocalAirMetrics {
    enum Stage { PLAN, BAKE, ESTIMATE, INITIALIZE, STEP }
    record Timing(Stage stage,long queueNanos,long wallNanos,long cpuNanos,int slices,long maxSliceNanos) {
        Timing(Stage stage,long queueNanos,long wallNanos,long cpuNanos) { this(stage,queueNanos,wallNanos,cpuNanos,1,wallNanos); }
    }
    record Row(long jobs,long failed,long discarded,long wallNanos,long queueNanos,long cpuNanos,long cpuSamples,
               long maxWallNanos,long maxQueueNanos,long slices,long maxSliceNanos) { }
    private final EnumMap<Stage,Row> rows=new EnumMap<>(Stage.class);
    void complete(Timing timing,boolean failed,boolean discarded) {
        if(timing==null) return;
        var old=rows.getOrDefault(timing.stage,new Row(0,0,0,0,0,0,0,0,0,0,0));
        boolean cpu=timing.cpuNanos>=0;
        rows.put(timing.stage,new Row(old.jobs+1,old.failed+(failed?1:0),old.discarded+(discarded?1:0),
                old.wallNanos+timing.wallNanos,old.queueNanos+timing.queueNanos,old.cpuNanos+(cpu?timing.cpuNanos:0),
                old.cpuSamples+(cpu?1:0),Math.max(old.maxWallNanos,timing.wallNanos),Math.max(old.maxQueueNanos,timing.queueNanos),old.slices+timing.slices,Math.max(old.maxSliceNanos,timing.maxSliceNanos)));
    }
    Map<Stage,Row> snapshot() { return Map.copyOf(rows); }
}
