// Written by ChatGPT.
// Updated by Claude: ordered groups by distance to the nearest player and capped components on bounding-box tiles.
package dev.brights0ng.meteodyne.heat.world;

import java.util.*;
import java.util.function.BiPredicate;
import static dev.brights0ng.meteodyne.heat.world.LocalAirDemandRegistry.*;

/** Bounded allocation proposals only. No geometry evidence, solver state, world references or chunk loads. */
public final class LocalAirPlanner {
    public static final int TILE_EDGE=16;
    // Hard preview guards, not production cell/memory defaults. Rejected input never yields a partial layout.
    static final int MAX_CANDIDATES=4096,MAX_REQUESTS=4096,MAX_VISITS=65_536;
    public record Settings(int radius,int margin,int totalTiles,int componentTiles) {
        public Settings {
            if(radius<0 || radius>32 || margin<1 || margin>32 || totalTiles<1 || totalTiles>512
                    || componentTiles<1 || componentTiles>512)
                throw new IllegalArgumentException("Radius 0..32, margin 1..32 and tile limits 1..512 required");
        }
    }
    public record Tile(int x,int y,int z) implements Comparable<Tile> {
        @Override public int compareTo(Tile t) {
            int c=Integer.compare(x,t.x);if(c==0) c=Integer.compare(y,t.y);if(c==0) c=Integer.compare(z,t.z);return c;
        }
    }
    public record Column(int x,int z) { }
    public record Unsupported(Entry request,String reason) { }
    public enum State { PROPOSED_UNVERIFIED, MISSING_CHUNKS, COMPONENT_LIMIT, TOTAL_LIMIT }
    /** Tiles form one face-connected calculation group. Interior faces must share pressure and heat flux.
     * playerDistance: tiles from the group's nearest tile to the nearest player; Integer.MAX_VALUE with no player. */
    public record Group(Tile key,List<Tile> tiles,List<Entry> requests,List<Column> missingChunks,State state,long cells,int playerDistance) {
        public Group { tiles=List.copyOf(tiles);requests=List.copyOf(requests);missingChunks=List.copyOf(missingChunks); }
        public Group(Tile key,List<Tile> tiles,List<Entry> requests,List<Column> missingChunks,State state,long cells) {
            this(key,tiles,requests,missingChunks,state,cells,Integer.MAX_VALUE);
        }
    }
    /** Cells a domain of componentTiles tiles may hold. */
    public static long componentCells(Settings settings) { return (long)settings.componentTiles*TILE_EDGE*TILE_EDGE*TILE_EDGE; }
    /** Tiles in the bounding box of these tiles: a domain can take the box, so the component cap applies to it. */
    public static long boxTiles(Collection<Tile> tiles) {
        if(tiles.isEmpty()) return 0;
        long lx=Long.MAX_VALUE,ly=lx,lz=lx,hx=Long.MIN_VALUE,hy=hx,hz=hx;
        for(var t:tiles) { lx=Math.min(lx,t.x);ly=Math.min(ly,t.y);lz=Math.min(lz,t.z);hx=Math.max(hx,t.x);hy=Math.max(hy,t.y);hz=Math.max(hz,t.z); }
        return (hx-lx+1)*(hy-ly+1)*(hz-lz+1);
    }
    /** Chebyshev distance in tiles from the nearest of these tiles to the nearest player tile. */
    static int playerDistance(Collection<Tile> tiles,Collection<Tile> players) {
        long best=Integer.MAX_VALUE;
        for(var p:players) for(var t:tiles)
            best=Math.min(best,Math.max(Math.abs((long)t.x-p.x),Math.max(Math.abs((long)t.y-p.y),Math.abs((long)t.z-p.z))));
        return (int)best;
    }
    public record Plan(long tick,long demandRevision,long layoutRevision,Settings settings,List<Group> groups,List<Unsupported> unsupported,
                       int tileVisits,String failure) {
        public Plan { groups=List.copyOf(groups);unsupported=List.copyOf(unsupported); }
        public int proposedTiles() { return groups.stream().filter(g->g.state==State.PROPOSED_UNVERIFIED).mapToInt(g->g.tiles.size()).sum(); }
        public long proposedCells() { return groups.stream().filter(g->g.state==State.PROPOSED_UNVERIFIED).mapToLong(Group::cells).sum(); }
    }
    private record Footprint(Entry entry,Tile first) { }
    private record Layout(List<Tile> tiles,State state) { }
    private final Thread owner=Thread.currentThread();
    private final Settings settings;
    private List<Layout> layout=List.of();
    private String failure="";
    private long revision;
    private Plan current;
    public LocalAirPlanner(Settings settings) { this.settings=Objects.requireNonNull(settings); }
    private void owner() { if(Thread.currentThread()!=owner) throw new IllegalStateException("Planner requires its owner thread"); }
    public Plan current() { owner();return current; }

    /** loadedColumn must be a non-loading availability lookup on the owner thread. maxY is exclusive. */
    public Plan update(Snapshot demand,int minY,int maxY,BiPredicate<Integer,Integer> loadedColumn) {
        return update(demand,minY,maxY,loadedColumn,List.of());
    }
    /** players: block positions of the players to rank groups by; the caller supplies them, never a world. */
    public Plan update(Snapshot demand,int minY,int maxY,BiPredicate<Integer,Integer> loadedColumn,List<Cell> players) {
        owner();Objects.requireNonNull(demand);Objects.requireNonNull(loadedColumn);Objects.requireNonNull(players);
        if(minY>=maxY) throw new IllegalArgumentException("Empty world height");
        if(demand.entries().size()>MAX_REQUESTS) return publish(demand,List.of(),List.of(),0,"Request work limit exceeded");
        var unsupported=new ArrayList<Unsupported>();
        var tiles=new TreeSet<Tile>();var footprints=new ArrayList<Footprint>();int visits=0;
        for(var entry:demand.entries()) {
            var c=entry.request().cell();
            if(c.y()<minY || c.y()>=maxY) { unsupported.add(new Unsupported(entry,"Demand outside world height"));continue; }
            int extent=settings.radius+settings.margin;
            // Calculate in long before narrowing: even synthetic extreme coordinates must not wrap.
            long lx=(long)c.x()-extent,hx=(long)c.x()+extent,lz=(long)c.z()-extent,hz=(long)c.z()+extent;
            if(lx<Integer.MIN_VALUE || hx>Integer.MAX_VALUE || lz<Integer.MIN_VALUE || hz>Integer.MAX_VALUE) {
                unsupported.add(new Unsupported(entry,"Demand margin exceeds coordinate range"));continue;
            }
            int x0=Math.floorDiv((int)lx,TILE_EDGE),x1=Math.floorDiv((int)hx,TILE_EDGE);
            int z0=Math.floorDiv((int)lz,TILE_EDGE),z1=Math.floorDiv((int)hz,TILE_EDGE);
            int y0=Math.floorDiv((int)Math.max(minY,(long)c.y()-extent),TILE_EDGE);
            int y1=Math.floorDiv((int)Math.min((long)maxY-1,(long)c.y()+extent),TILE_EDGE);
            long count=(long)(x1-x0+1)*(y1-y0+1)*(z1-z0+1);
            if(count>MAX_VISITS-visits) return publish(demand,List.of(),unsupported,visits,"Tile visit work limit exceeded");
            visits+=(int)count;
            for(int x=x0;x<=x1;x++) for(int y=y0;y<=y1;y++) for(int z=z0;z<=z1;z++) {
                tiles.add(new Tile(x,y,z));
                if(tiles.size()>MAX_CANDIDATES) return publish(demand,List.of(),unsupported,visits,"Candidate tile work limit exceeded");
            }
            footprints.add(new Footprint(entry,new Tile(x0,y0,z0)));
        }
        // Do not build a rectangular bridge between distant requests. Missing tiles stay in this graph:
        // dropping them would invent independent calculation groups across unavailable geometry.
        var components=new ArrayList<List<Tile>>();var ownerByTile=new HashMap<Tile,Integer>();
        var remaining=new TreeSet<>(tiles);
        while(!remaining.isEmpty()) {
            int index=components.size();var component=new ArrayList<Tile>();var queue=new ArrayDeque<Tile>();
            var first=remaining.pollFirst();queue.add(first);
            while(!queue.isEmpty()) {
                var tile=queue.removeFirst();component.add(tile);ownerByTile.put(tile,index);
                for(var neighbor:neighbors(tile)) if(remaining.remove(neighbor)) queue.add(neighbor);
            }
            Collections.sort(component);components.add(component);
        }
        var requests=new ArrayList<List<Entry>>();for(int i=0;i<components.size();i++) requests.add(new ArrayList<>());
        for(var f:footprints) requests.get(ownerByTile.get(f.first)).add(f.entry);
        var order=new ArrayList<Integer>();for(int i=0;i<components.size();i++) order.add(i);
        var playerTiles=players.stream().map(p->new Tile(Math.floorDiv(p.x(),TILE_EDGE),Math.floorDiv(p.y(),TILE_EDGE),Math.floorDiv(p.z(),TILE_EDGE))).toList();
        var distances=new int[components.size()];for(int i=0;i<distances.length;i++) distances[i]=playerDistance(components.get(i),playerTiles);
        // Nearby player requests precede other active owners, then retained-only work; within a class, groups nearer
        // a player come first. Ties are spatially stable.
        order.sort(Comparator.<Integer>comparingInt(i->priority(requests.get(i))).thenComparingInt(i->distances[i]).thenComparing(i->components.get(i).getFirst()));
        var availability=new HashMap<Column,Boolean>();var groups=new ArrayList<Group>();int admitted=0;
        for(int i:order) {
            var component=components.get(i);var missing=new LinkedHashSet<Column>();long cells=0;
            for(var tile:component) {
                var column=new Column(tile.x,tile.z);
                if(!availability.computeIfAbsent(column,c->loadedColumn.test(c.x,c.z))) missing.add(column);
                long low=Math.max(minY,(long)tile.y*TILE_EDGE),high=Math.min(maxY,((long)tile.y+1)*TILE_EDGE);
                cells+=(high-low)*TILE_EDGE*TILE_EDGE;
            }
            State state;
            if(!missing.isEmpty()) state=State.MISSING_CHUNKS;
            else if(boxTiles(component)>settings.componentTiles) state=State.COMPONENT_LIMIT;
            else if(component.size()>settings.totalTiles-admitted) state=State.TOTAL_LIMIT;
            else { state=State.PROPOSED_UNVERIFIED;admitted+=component.size(); }
            groups.add(new Group(component.getFirst(),component,requests.get(i),List.copyOf(missing),state,cells,distances[i]));
        }
        groups.sort(Comparator.comparing(Group::key));
        return publish(demand,groups,unsupported,visits,"");
    }
    private static int priority(List<Entry> entries) {
        int result=2;
        for(var entry:entries) if(entry.active()) result=Math.min(result,entry.request().key().kind()==Kind.PLAYER?0:1);
        return result;
    }
    private static List<Tile> neighbors(Tile t) {
        return List.of(new Tile(t.x-1,t.y,t.z),new Tile(t.x+1,t.y,t.z),new Tile(t.x,t.y-1,t.z),
                new Tile(t.x,t.y+1,t.z),new Tile(t.x,t.y,t.z-1),new Tile(t.x,t.y,t.z+1));
    }
    private Plan publish(Snapshot demand,List<Group> groups,List<Unsupported> unsupported,int visits,String rejection) {
        var next=groups.stream().map(g->new Layout(g.tiles,g.state)).toList();
        if(!next.equals(layout) || !failure.equals(rejection)) revision++;
        layout=next;failure=rejection;
        return current=new Plan(demand.tick(),demand.revision(),revision,settings,groups,unsupported,visits,rejection);
    }
}
