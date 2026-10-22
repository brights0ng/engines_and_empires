// Written by ChatGPT.
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
    synchronized void historyReads(boolean active) { historyReads=active; }

    LocalAirDependencies(LocalAirGeometryCapture.Bounds bounds) { this.bounds=bounds; }
    private static long key(int x,int z) { return ((long)x<<32) | (z&0xffffffffL); }
    synchronized long get() { return revision; }
    synchronized long getAndIncrement() { return revision++; }
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
        if(global || (x>=bounds.x() && (long)x< (long)bounds.x()+bounds.nx()
                && y>=bounds.y() && (long)y<(long)bounds.y()+bounds.ny()
                && z>=bounds.z() && (long)z<(long)bounds.z()+bounds.nz())) return true;
        var range=columns.get(key(x,z));return range!=null && y>=range.low && y<=range.high;
    }
    synchronized void blockChanged(int x,int y,int z) { if(historyReads || affectsBlock(x,y,z)) revision++; }
    synchronized void chunkChanged(int x,int z) {
        if(historyReads || global || chunks.contains(key(x,z)) || (x>=(bounds.x()>>4) && x<=(((long)bounds.x()+bounds.nx()-1)>>4)
                && z>=(bounds.z()>>4) && z<=(((long)bounds.z()+bounds.nz()-1)>>4))) revision++;
    }
}
