// Written by ChatGPT.
package dev.brights0ng.meteodyne.heat.world;

import java.util.*;

/** Server-owner registry of required sample cells. No domain allocation, world references or radius policy. */
public final class LocalAirDemandRegistry {
    public enum Kind { PLAYER, PLACED_SOURCE, TEMPERATURE_SENSOR, WIND_SENSOR }
    public record Key(Kind kind,String id) { public Key { Objects.requireNonNull(kind);Objects.requireNonNull(id); } }
    public record Cell(int x,int y,int z) { }
    public record Request(Key key,Cell cell,boolean temperature,boolean flow) {
        public Request { Objects.requireNonNull(key);Objects.requireNonNull(cell);if(!temperature && !flow) throw new IllegalArgumentException("A reading is required"); }
    }
    public record Entry(Request request,boolean active,long expiresAt) { }
    public record Anchor(Cell cell,boolean temperature,boolean flow,int activeOwners,int retainedOwners) { }
    public record Snapshot(long tick,long revision,List<Entry> entries,List<Anchor> anchors) {
        public Snapshot { entries=List.copyOf(entries);anchors=List.copyOf(anchors); }
    }
    private record Retired(Key key,Cell cell) { }
    private final Thread owner=Thread.currentThread();
    private final long graceTicks;
    private final Map<Key,Request> active=new LinkedHashMap<>();
    private final Map<Retired,Entry> retained=new LinkedHashMap<>();
    private long now=-1,revision;
    public LocalAirDemandRegistry(long graceTicks) {
        if(graceTicks<0) throw new IllegalArgumentException("Negative grace period");this.graceTicks=graceTicks;
    }
    private void owner() { if(Thread.currentThread()!=owner) throw new IllegalStateException("Demand registry requires its owner thread"); }
    public void advance(long tick) {
        owner();if(tick<0 || tick<now) throw new IllegalArgumentException("Nondecreasing demand time required");now=tick;
        if(retained.values().removeIf(e->e.expiresAt<=tick)) revision++;
    }
    public void put(Request request) {
        owner();if(now<0) throw new IllegalStateException("Advance demand time first");
        var previous=active.put(request.key,request);
        if(request.equals(previous)) return;
        if(previous!=null && !previous.cell.equals(request.cell)) retire(previous);
        retained.remove(new Retired(request.key,request.cell));revision++;
    }
    private void retire(Request request) {
        if(graceTicks>0) retained.put(new Retired(request.key,request.cell),new Entry(request,false,Math.addExact(now,graceTicks)));
    }
    public void remove(Key key) {
        owner();var previous=active.remove(key);if(previous!=null) { retire(previous);revision++; }
    }
    public void forget(Key key) {
        owner();boolean changed=active.remove(key)!=null;
        changed|=retained.keySet().removeIf(k->k.key.equals(key));if(changed) revision++;
    }
    /** Reconcile one producer type; refreshes do not extend already retired entries. */
    public void reconcile(Kind kind,List<Request> requests) {
        owner();var seen=new HashSet<Key>();
        for(var request:requests) {
            if(request.key.kind!=kind || !seen.add(request.key)) throw new IllegalArgumentException("Inconsistent producer snapshot");
        }
        for(var request:requests) put(request);
        for(var key:List.copyOf(active.keySet())) if(key.kind==kind && !seen.contains(key)) remove(key);
    }
    /** Unavailable chunks release both active and grace coverage immediately. Never requests a load. */
    public void unloadChunk(int x,int z) {
        owner();boolean changed=active.values().removeIf(r->inChunk(r.cell,x,z));
        changed|=retained.values().removeIf(e->inChunk(e.request.cell,x,z));if(changed) revision++;
    }
    private static boolean inChunk(Cell cell,int x,int z) { return cell.x>>4==x && cell.z>>4==z; }
    public void clear() { owner();if(!active.isEmpty() || !retained.isEmpty()) { active.clear();retained.clear();revision++; } }
    public Snapshot snapshot() {
        owner();var entries=new ArrayList<Entry>();
        for(var r:active.values()) entries.add(new Entry(r,true,-1));entries.addAll(retained.values());
        var grouped=new LinkedHashMap<Cell,int[]>();
        for(var entry:entries) {
            var r=entry.request;var a=grouped.computeIfAbsent(r.cell,k->new int[4]);
            if(r.temperature) a[0]=1;if(r.flow) a[1]=1;a[entry.active?2:3]++;
        }
        var anchors=new ArrayList<Anchor>();grouped.forEach((cell,a)->anchors.add(new Anchor(cell,a[0]!=0,a[1]!=0,a[2],a[3])));
        return new Snapshot(now,revision,entries,anchors);
    }
}
