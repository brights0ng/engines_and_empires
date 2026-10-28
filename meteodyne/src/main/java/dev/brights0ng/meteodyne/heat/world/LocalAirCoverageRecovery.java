// Written by ChatGPT.
package dev.brights0ng.meteodyne.heat.world;

import java.util.*;
import java.util.function.Predicate;
import static dev.brights0ng.meteodyne.heat.world.LocalAirGeometryCapture.Bounds;
import static dev.brights0ng.meteodyne.heat.world.LocalAirPlanner.*;

/** Bounded proposals only. A rejection may add one face-adjacent tile; every new cut is checked again.
 * Expansions live only while their original demand footprint remains present. Touching groups merge.
 */
final class LocalAirCoverageRecovery {
    record Rejection(Bounds bounds,LocalAirCoverage.Failure failure) { }
    record Result(List<Group> groups,Map<Bounds,String> notes) { }
    private record Component(Group group,List<List<Tile>> bases) { }
    private final Map<List<Tile>,Set<Tile>> extra=new HashMap<>();
    private final Map<List<Tile>,String> messages=new HashMap<>();
    private Settings policy;
    void clear() { extra.clear();messages.clear();policy=null; }
    void retryBlocked() { messages.values().removeIf(s->s.startsWith("Coverage blocked")); }

    Result update(Plan plan,int minY,int maxY,List<Rejection> failures,Predicate<Tile> loaded) {
        if(!plan.settings().equals(policy)) { clear();policy=plan.settings(); }
        var bases=plan.groups().stream().map(Group::tiles).collect(java.util.stream.Collectors.toSet());
        extra.keySet().retainAll(bases);messages.keySet().retainAll(bases);
        var components=components(plan);var notes=new HashMap<Bounds,String>();
        for(var rejected:failures) {
            var component=components.stream().filter(c->Objects.equals(bounds(c.group,minY,maxY),rejected.bounds)).findFirst().orElse(null);
            if(component==null || component.group.state()!=State.PROPOSED_UNVERIFIED) continue;
            var cut=rejected.failure.cut();String message;
            Tile tile=null;
            try {
                int delta=cut.side()==0?-1:1;
                tile=new Tile(Math.floorDiv(Math.addExact(cut.x(),cut.axis()==0?delta:0),16),
                        Math.floorDiv(Math.addExact(cut.y(),cut.axis()==1?delta:0),16),
                        Math.floorDiv(Math.addExact(cut.z(),cut.axis()==2?delta:0),16));
                if((long)tile.y()*16<minY || ((long)tile.y()+1)*16>maxY) message="world height limit";
                else if(component.group.tiles().contains(tile)) message="no additional tile at the rejected cut";
                else if(!loaded.test(tile)) message="required chunk is not loaded at tile "+tile;
                else message="";
            } catch(ArithmeticException e) { message="coordinate limit"; }
            var base=component.bases.getFirst();var old=new HashSet<>(extra.getOrDefault(base,Set.of()));
            if(message.isEmpty()) {
                var proposed=new HashSet<>(old);proposed.add(tile);extra.put(base,proposed);
                var candidate=components(plan);
                long count=candidate.stream().filter(c->c.group.state()==State.PROPOSED_UNVERIFIED).mapToLong(c->c.group.tiles().size()).sum();
                if(candidate.stream().filter(c->c.bases.contains(base)).anyMatch(c->c.group.tiles().size()>plan.settings().componentTiles() || c.group.cells()>LocalAirCommand.CELL_CAP)) message="component/per-domain tile limit";
                else if(count>plan.settings().totalTiles()) message="total planner tile limit";
                else if(candidate.stream().anyMatch(c->c.group.state()!=State.PROPOSED_UNVERIFIED && c.bases.contains(base))) message="expansion touches deferred coverage";
                if(message.isEmpty()) { components=candidate;messages.put(base,"Expanding coverage at "+LocalAirCoverage.face(cut)+" after "+rejected.failure.reason()); }
                else if(old.isEmpty()) extra.remove(base);else extra.put(base,old);
            }
            if(!message.isEmpty()) messages.put(base,"Coverage blocked: "+message+"; "+rejected.failure.reason());
        }
        for(var c:components) {
            String message=c.bases.stream().map(messages::get).filter(Objects::nonNull).findFirst().orElse("");
            var bounds=bounds(c.group,minY,maxY);if(!message.isEmpty() && bounds!=null) notes.put(bounds,message);
        }
        return new Result(components.stream().map(Component::group).toList(),Map.copyOf(notes));
    }
    private List<Component> components(Plan plan) {
        var result=new ArrayList<Component>();
        for(var group:plan.groups()) {
            var tiles=new TreeSet<>(group.tiles());tiles.addAll(extra.getOrDefault(group.tiles(),Set.of()));
            var bases=new ArrayList<List<Tile>>();bases.add(group.tiles());
            var requests=new ArrayList<>(group.requests());var missing=new LinkedHashSet<>(group.missingChunks());var state=group.state();
            boolean joined;
            do {
                joined=false;
                for(var prior:List.copyOf(result)) if(touches(tiles,prior.group.tiles())) {
                    result.remove(prior);tiles.addAll(prior.group.tiles());bases.addAll(prior.bases);requests.addAll(prior.group.requests());missing.addAll(prior.group.missingChunks());
                    if(prior.group.state()!=State.PROPOSED_UNVERIFIED) state=prior.group.state();joined=true;
                }
            } while(joined);
            result.add(new Component(new Group(tiles.first(),List.copyOf(tiles),requests,List.copyOf(missing),state,(long)tiles.size()*4096),List.copyOf(bases)));
        }
        result.sort(Comparator.comparing(c->c.group.key()));return result;
    }
    private static boolean touches(Set<Tile> a,List<Tile> b) {
        for(var x:a) for(var y:b) if(Math.abs((long)x.x()-y.x())+Math.abs((long)x.y()-y.y())+Math.abs((long)x.z()-y.z())<=1) return true;
        return false;
    }
    private static Bounds bounds(Group group,int minY,int maxY) {
        try { return LocalAirAutomatic.bounds(group,minY,maxY); }
        catch(IllegalArgumentException | ArithmeticException e) { return null; }
    }
}
