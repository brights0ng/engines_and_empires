// Written by ChatGPT.
package dev.brights0ng.meteodyne.heat.world;

/** Poll cheap guarded metadata each tick; pace full point sampling independently of missing-field notices. */
final class LocalAirViewSchedule {
    static final int INTERVAL=10;
    record State(long owner,boolean history,boolean renderable,LocalAirReading.Status status,LocalAirReading.Reason reason) { }
    record Update(boolean field,boolean packet,boolean caption) { }
    private State previous;
    private long fieldTick=-1,captionTick=-1,observedTick=-1;
    Update advance(long tick,State state) {
        if(tick<0 || tick<observedTick) throw new IllegalArgumentException("Nondecreasing view time required");
        observedTick=tick;
        boolean field=state.renderable && (fieldTick<0 || tick-fieldTick>=INTERVAL);
        boolean changed=!state.equals(previous);
        boolean caption=changed || field || captionTick<0 || tick-captionTick>=INTERVAL;
        boolean clear=previous==null || state.owner!=previous.owner || previous.renderable && !state.renderable || previous.history && !state.history;
        boolean packet=field || clear || !state.renderable && caption;
        if(field) fieldTick=tick;
        if(caption) captionTick=tick;
        previous=state;return new Update(field,packet,caption);
    }
}
