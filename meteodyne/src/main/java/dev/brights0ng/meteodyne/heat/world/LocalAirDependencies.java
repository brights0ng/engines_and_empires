// Written by ChatGPT.
// Updated by ChatGPT: bounded invalidation-cause telemetry without world references.
// Updated by ChatGPT: imported bounded coverage proof footprints without world reads.
// Updated by Claude: exposed the most recent invalidation cause for restart attribution.
// Updated by Claude: counted every revision bump by its call site.
package dev.brights0ng.meteodyne.heat.world;

import java.util.HashMap;
import java.util.HashSet;

/** Request-lifetime, conservative read footprint. No world objects; safe for off-thread edit hooks.
 * Columns retain a vertical interval rather than one entry per sky sample. Dependencies only grow:
 * cached shapes, rejected captures and in-flight old generations must remain protected.
 */
final class LocalAirDependencies {
    private static final int MAX_COLUMNS=65_536;
    private record Range(int low,int high) { }
    private final LocalAirGeometryCapture.Bounds bounds;
    private final HashMap<Long,Range> columns=new HashMap<>();
    private final HashSet<Long> chunks=new HashSet<>();
    private long revision;
    private boolean global;
    private boolean historyReads;
    record Event(long revision,long tick,String cause,int x,int y,int z,String scope,String before,String after) { }
    record Diagnostics(long revision,java.util.Map<String,Long> counts,java.util.List<Event> recent) { }
    private final java.util.ArrayDeque<Event> recent=new java.util.ArrayDeque<>();
    private final java.util.Map<String,Long> counts=new java.util.TreeMap<>();
    /** Call sites that bump this owner's revision; OTHER:<method> marks the untagged legacy overloads. */
    static final String APPLY_AUTOMATIC="APPLY_AUTOMATIC",MANUAL="MANUAL",OFF_THREAD="OFF_THREAD",BEFORE_EDIT="BEFORE_EDIT",
            CHUNK="CHUNK",HISTORY_OVERFLOW="HISTORY_OVERFLOW";
    private final java.util.TreeMap<String,Long> sites=new java.util.TreeMap<>();
    private long invalidate(String site,long tick,String cause,int x,int y,int z,String scope,String before,String after) {
        long previous=revision++;
        counts.merge(cause,1L,Long::sum);sites.merge(site,1L,Long::sum);
        if(recent.size()==8) recent.removeFirst();
        recent.addLast(new Event(revision,tick,cause,x,y,z,scope,before,after));return previous;
    }
    synchronized String lastCause() { return recent.isEmpty()?"NONE":recent.peekLast().cause(); }
    synchronized Diagnostics diagnostics() { return new Diagnostics(revision,java.util.Map.copyOf(counts),java.util.List.copyOf(recent)); }
    synchronized java.util.Map<String,Long> revisionSites() { return java.util.Collections.unmodifiableMap(new java.util.TreeMap<>(sites)); }
    synchronized long blockRevision(int x,int y,int z,long tick,String before,String after,String site) {
        return invalidate(site,tick,"BLOCK",x,y,z,scope(x,y,z),before,after);
    }
    synchronized void historyLost(int x,int y,int z,long tick) { invalidate(HISTORY_OVERFLOW,tick,"HISTORY_LOST",x,y,z,"historical overlay","unknown","unknown"); }
    private String scope(int x,int y,int z) {
        if(global) return "dependency budget fallback";
        if(bounds.contains(x,y,z)) return "domain";
        return affectsBlock(x,y,z)?"shape/sky read interval":"historical read guard";
    }
    synchronized void historyReads(boolean active) { historyReads=active; }

    LocalAirDependencies(LocalAirGeometryCapture.Bounds bounds) { this.bounds=bounds; }
    private static long key(int x,int z) { return ((long)x<<32) | (z&0xffffffffL); }
    synchronized long get() { return revision; }
    synchronized long getAndIncrement() { return invalidate("OTHER:getAndIncrement",-1,"EXPLICIT",0,0,0,"explicit invalidation","unknown","unknown"); }
    /** Register before touching the world, even when the lookup will fail. */
    synchronized void read(int x,int y,int z) {
        if(global) return;
        long key=key(x,z);var old=columns.get(key);
        if(old==null && columns.size()==MAX_COLUMNS) {
            // Bounded bookkeeping; unusual unbounded shape contexts fall back to world-wide invalidation.
            global=true;columns.clear();chunks.clear();return;
        }
        if(old==null || y<old.low || y>old.high)
            columns.put(key,old==null?new Range(y,y):new Range(Math.min(y,old.low),Math.max(y,old.high)));
        chunks.add(key(x>>4,z>>4));
    }
    synchronized boolean affectsBlock(int x,int y,int z) {
        if(global || bounds.contains(x,y,z)) return true;
        var range=columns.get(key(x,z));return range!=null && y>=range.low && y<=range.high;
    }
    /** Import a conservative proof footprint without reading chunks or expanding every vertical position. */
    synchronized void readBounds(LocalAirGeometryCapture.ReadBounds b) {
        if(global) return;
        long width=(long)b.highX()-b.lowX()+1,depth=(long)b.highZ()-b.lowZ()+1;
        if(width>MAX_COLUMNS || depth>MAX_COLUMNS || width*depth>MAX_COLUMNS) {global=true;columns.clear();chunks.clear();return;}
        for(int x=b.lowX();;x++) {
            for(int z=b.lowZ();;z++) {read(x,b.lowY(),z);read(x,b.highY(),z);if(z==b.highZ() || global) break;}
            if(x==b.highX() || global) break;
        }
    }
    synchronized void blockChanged(int x,int y,int z) { blockChanged(x,y,z,-1,"unknown","unknown","OTHER:blockChanged"); }
    synchronized void blockChanged(int x,int y,int z,long tick,String before,String after) { blockChanged(x,y,z,tick,before,after,"OTHER:blockChanged"); }
    synchronized void blockChanged(int x,int y,int z,long tick,String before,String after,String site) {
        if(historyReads || affectsBlock(x,y,z)) blockRevision(x,y,z,tick,before,after,site);
    }
    synchronized void chunkChanged(int x,int z) {
        chunkChanged(x,z,-1,"CHUNK");
    }
    synchronized void chunkChanged(int x,int z,long tick,String cause) {
        if(historyReads || global || chunks.contains(key(x,z)) || bounds.chunk(x,z))
            invalidate(CHUNK,tick,cause,x,0,z,"chunk coordinates","unknown","unknown");
    }
}
