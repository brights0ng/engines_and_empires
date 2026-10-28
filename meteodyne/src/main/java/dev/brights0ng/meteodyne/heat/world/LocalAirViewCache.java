// Written by ChatGPT.
package dev.brights0ng.meteodyne.heat.world;

import java.util.List;

/** Client presentation state without Minecraft access, shared with lifecycle/freshness tests. */
public final class LocalAirViewCache {
    public record Frame(boolean enabled,long domainId,boolean retainHistory,long ageTicks,int maxAgeTicks,List<LocalAirViewNetwork.Point> points) {
        public Frame {points=List.copyOf(points);}
    }
    private List<LocalAirViewNetwork.Point> points=List.of();
    private long domain,received,age;
    private int maxAge;
    private boolean measured;
    public void clear() {points=List.of();domain=received=age=0;maxAge=0;measured=false;}
    public void accept(Frame payload,long now) {
        if(!payload.enabled() || domain!=payload.domainId() || !payload.retainHistory()) points=List.of();
        domain=payload.domainId();received=now;age=payload.ageTicks();maxAge=payload.maxAgeTicks();
        measured=payload.enabled() && !payload.points().isEmpty();
        if(measured) points=payload.points();
    }
    public List<LocalAirViewNetwork.Point> points() {return points;}
    public boolean current(long now) {
        if(!measured || now<received) return false;
        long elapsed=now-received;
        return elapsed<=40 && age<=maxAge && elapsed<=maxAge-age;
    }
}
