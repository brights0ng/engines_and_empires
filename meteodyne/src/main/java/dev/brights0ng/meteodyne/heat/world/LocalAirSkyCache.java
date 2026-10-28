// Written by ChatGPT.
package dev.brights0ng.meteodyne.heat.world;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import static dev.brights0ng.meteodyne.heat.world.LocalAirGeometryCapture.*;

/** World-lifetime, bounded sky index. Owner-thread reads; invalidation may arrive from any thread.
 * A contiguous clear suffix serves every higher query. Its first obstruction rejects every lower query.
 * Entries contain primitive evidence and epoch identities, never worlds, chunks or solver fields.
 */
final class LocalAirSkyCache {
    static final int MAX_COLUMNS=16_384,MAX_SECTIONS=16_384,MAX_CHUNKS=4096,MAX_CONTEXT_CHUNKS=64;
    enum State { CHECKING, CLEAR, BLOCKED, FALLBACK }
    record Result(State state,int clearLow,int obstruction,ReadBounds reads,int samples,int bulkCells,boolean hit,java.util.function.BooleanSupplier validity) {
        Result(State state,int clearLow,int obstruction,ReadBounds reads,int samples,int bulkCells,boolean hit) {this(state,clearLow,obstruction,reads,samples,bulkCells,hit,()->true);}
        boolean current() { return validity.getAsBoolean(); }
    }
    record Report(int columns,int sections,long queries,long hits,long samples,long sectionChecks,long bulkCells) { }
    private record Column(int x,int z) { }
    private record Section(int x,int y,int z) { }
    private static final class Epoch {
        final AtomicLong revision=new AtomicLong();
        volatile boolean active=true;
    }
    private record Stamp(Epoch epoch,long revision) {
        boolean valid() { return epoch.active && epoch.revision.get()==revision; }
    }
    private static final class Entry {
        int low,obstruction=Integer.MIN_VALUE;
        ReadBounds reads;
        final Map<Long,Stamp> stamps=new HashMap<>();
        Entry(int maxY) {low=maxY;}
        boolean valid() { return stamps.values().stream().allMatch(Stamp::valid); }
    }
    private record Empty(boolean empty,Stamp stamp) { }
    private final Thread owner=Thread.currentThread();
    private final LinkedHashMap<Column,Entry> columns=new LinkedHashMap<>(64,.75f,true);
    private final LinkedHashMap<Section,Empty> sections=new LinkedHashMap<>(64,.75f,true);
    private final ConcurrentHashMap<Long,Epoch> epochs=new ConcurrentHashMap<>();
    private final AtomicLong changes=new AtomicLong();
    private int minY,maxY;
    private long material=Long.MIN_VALUE;
    private long queries,hits,samples,sectionChecks,bulkCells;
    private void owner() { if(Thread.currentThread()!=owner) throw new IllegalStateException("Sky indexing requires owner thread"); }
    private static long chunk(int x,int z) { return ((long)x<<32)|(z&0xffffffffL); }
    void chunkChanged(int x,int z) {
        changes.incrementAndGet();var epoch=epochs.get(chunk(x,z));if(epoch!=null) epoch.revision.incrementAndGet();
    }
    void blockChanged(int x,int z) { chunkChanged(x>>4,z>>4); }
    void clear() {
        owner();changes.incrementAndGet();epochs.values().forEach(e->e.active=false);epochs.clear();columns.clear();sections.clear();
    }
    Report report() { owner();return new Report(columns.size(),sections.size(),queries,hits,samples,sectionChecks,bulkCells); }
    private Stamp stamp(int x,int z) {
        var epoch=epochs.computeIfAbsent(chunk(x,z),k->new Epoch());return new Stamp(epoch,epoch.revision.get());
    }
    private boolean evidence(Entry entry,ReadBounds reads) {
        if(reads==null) return false;
        long width=(long)(reads.highX()>>4)-(reads.lowX()>>4)+1,depth=(long)(reads.highZ()>>4)-(reads.lowZ()>>4)+1;
        if(width*depth>MAX_CONTEXT_CHUNKS) return false;
        for(int x=reads.lowX()>>4;x<=reads.highX()>>4;x++) for(int z=reads.lowZ()>>4;z<=reads.highZ()>>4;z++) {
            entry.stamps.putIfAbsent(chunk(x,z),stamp(x,z));
            if(entry.stamps.size()>MAX_CONTEXT_CHUNKS) return false;
        }
        entry.reads=entry.reads==null?reads:entry.reads.union(reads);return entry.valid();
    }
    private Result result(Entry entry,int low,int count,int bulk,boolean hit) {
        State state=entry.obstruction>=low?State.BLOCKED:entry.low<=low?State.CLEAR:State.CHECKING;
        if(hit && state!=State.CHECKING) hits++;
        return new Result(state,entry.low,entry.obstruction,entry.reads,count,bulk,hit,entry::valid);
    }
    /** One charged operation: at most one palette classification or scalar sample, plus 32 cached section skips. */
    Result advance(LocalAirCoverage.World world,int x,int low,int z) {
        owner();queries++;
        if(world.minBuildY()!=minY || world.maxBuildY()!=maxY || world.materialRevision()!=material || epochs.size()>=MAX_CHUNKS) {
            clear();minY=world.minBuildY();maxY=world.maxBuildY();material=world.materialRevision();
        }
        if(low<minY || low>=maxY) throw new IllegalArgumentException("Sky query outside world height");
        long before=changes.get();var key=new Column(x,z);var entry=columns.get(key);
        if(entry==null || !entry.valid()) { entry=new Entry(maxY);columns.put(key,entry); }
        while(columns.size()>MAX_COLUMNS) columns.remove(columns.keySet().iterator().next());
        var known=result(entry,low,0,0,true);if(known.state!=State.CHECKING) return known;
        int count=0,bulk=0;
        try {
            for(int skipped=0;skipped<32 && entry.low>low;skipped++) {
                int y=entry.low-1;var section=new Section(x>>4,Math.floorDiv(y,16),z>>4);var empty=sections.get(section);
                if(empty==null || !empty.stamp.valid()) {
                    var stamp=stamp(section.x,section.z);sectionChecks++;
                    boolean value=world.emptySkySection(section.x,section.y,section.z);
                    if(before!=changes.get() || !stamp.valid()) { columns.remove(key);return new Result(State.CHECKING,maxY,Integer.MIN_VALUE,null,0,0,false); }
                    empty=new Empty(value,stamp);sections.put(section,empty);
                    while(sections.size()>MAX_SECTIONS) sections.remove(sections.keySet().iterator().next());
                    // A classification consumes this operation, including when the section is occupied.
                    if(value) {
                        int next=Math.max(minY,section.y*16),span=entry.low-next;
                        if(!evidence(entry,new ReadBounds(x,next,z,x,entry.low-1,z))) return fallback(key);
                        entry.low=next;bulk+=span;bulkCells+=span;
                    }
                    break;
                }
                if(empty.empty) {
                    int next=Math.max(minY,section.y*16),span=entry.low-next;
                    if(!evidence(entry,new ReadBounds(x,next,z,x,entry.low-1,z))) return fallback(key);
                    entry.low=next;bulk+=span;bulkCells+=span;continue;
                }
                var cell=world.sample(x,y,z);count++;samples++;
                var footprint=world.lastReads();var bounds=footprint==null?null:footprint.enclose(x,y,z);
                if(!evidence(entry,bounds)) return fallback(key);
                if(cell.shape().empty()) entry.low=y;else entry.obstruction=y;
                break;
            }
            if(before!=changes.get() || !entry.valid()) { columns.remove(key);return new Result(State.CHECKING,maxY,Integer.MIN_VALUE,null,count,0,false); }
            return result(entry,low,count,bulk,false);
        } catch(RuntimeException error) { columns.remove(key);throw error; }
    }
    private Result fallback(Column key) { columns.remove(key);return new Result(State.FALLBACK,maxY,Integer.MIN_VALUE,null,0,0,false); }
}
