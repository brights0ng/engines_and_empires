// Written by ChatGPT.
package dev.brights0ng.meteodyne.heat.world;

import java.util.Arrays;
import java.util.List;
import dev.brights0ng.paradynelib.heat.air.*;
import static dev.brights0ng.meteodyne.heat.world.LocalAirGeometryCapture.*;

/** Diagnostic with chronological immutable source and exterior-weather updates; no world access.
 * Mutable solver ownership transfers between sequential worker jobs; owner sees immutable frames only.
 */
final class LocalAirRun {
    static final int PHYSICAL_TICKS=10,MAX_LAG_TICKS=2400,MAX_SOURCE_CHANGES=256;
    record Settings(double ambient,double windX,double windZ,int maxStepsPerJob) {
        Settings {
            if(!Double.isFinite(ambient) || ambient<=-273.15 || ambient>FlowSettings.DEFAULT.maxTemperatureC()
                    || !Double.isFinite(windX) || !Double.isFinite(windZ) || maxStepsPerJob<1 || maxStepsPerJob>64)
                throw new IllegalArgumentException("Invalid diagnostic initialization/step cap");
        }
    }
    enum Status { INITIALIZING, READY, FAILED, LAG_LIMIT, CLOSED }
    record SourceChange(long revision,long tick,int cell,Heat heat) { }
    record Weather(double ambient,double windX,double windZ) {
        Weather {
            if(!Double.isFinite(ambient) || ambient<=-273.15 || ambient>FlowSettings.DEFAULT.maxTemperatureC()
                    || !Double.isFinite(windX) || !Double.isFinite(windZ)
                    || Math.abs(windX)>FlowSettings.DEFAULT.maxSpeed() || Math.abs(windZ)>FlowSettings.DEFAULT.maxSpeed())
                throw new IllegalArgumentException("Unsupported local-air weather");
        }
    }
    record WeatherChange(long revision,long tick,Weather weather) { }
    record Seed(Prepared prepared,Snapshot snapshot,Frame frame) { }
    record Quality(double maxDivergence,double maxPressureResidual,double maxThermalResidual,
                   double maxStepEnergyError,long pressureIterations,long thermalSweeps,double energyBalanceError) { }
    static final class Frame {
        final long throughTick,acceptedSteps,sourceRevision,weatherRevision;
        final Weather weather;
        final double energy,thermalSeconds,sourceEnergy,exteriorEnergy,initialEnergy,geometryEnergy;
        final long transfers;
        final long contextId,geometryRevision,materialRevision;
        final Bounds bounds;
        final FlowGeometry flow;
        final Quality quality;
        private final double[] temperature,velocity,flux;
        private Frame(State state,Prepared prepared,Snapshot snapshot) {
            contextId=snapshot.contextId;geometryRevision=snapshot.geometryRevision;materialRevision=snapshot.materialRevision;
            bounds=snapshot.bounds;this.flow=prepared.flow();
            var flow=prepared.flow();throughTick=state.tick;acceptedSteps=state.steps;energy=state.grid.energy();
            sourceRevision=state.sourceRevision;
            weatherRevision=state.weatherRevision;weather=state.weather;
            thermalSeconds=state.thermal;sourceEnergy=state.source;exteriorEnergy=state.exterior;
            initialEnergy=state.initialEnergy;geometryEnergy=state.geometryEnergy;transfers=state.transfers;
            quality=new Quality(state.divergence,state.pressureResidual,state.thermalResidual,state.stepEnergyError,
                    state.pressureIterations,state.thermalSweeps,energy-state.initialEnergy-sourceEnergy-exteriorEnergy-geometryEnergy);
            temperature=new double[prepared.thermal().states()];velocity=new double[flow.nodes()*3];flux=new double[flow.faces()];
            for(int i=0;i<temperature.length;i++) temperature[i]=state.grid.temperature(i);
            for(int i=0;i<flow.nodes();i++) for(int axis=0;axis<3;axis++) velocity[i*3+axis]=state.grid.componentVelocity(i,axis);
            for(int i=0;i<flux.length;i++) flux[i]=state.grid.faceFlux(i);
        }
        double temperature(int state) { return temperature[state]; }
        double velocity(int node,int axis) { if(axis<0 || axis>2) throw new IllegalArgumentException();return velocity[node*3+axis]; }
        double flux(int face) { return flux[face]; }
    }
    private static final class State {
        CoupledAirGrid grid;
        final double[] background,exteriorCelsius;
        long tick,steps;
        long sourceRevision;
        long weatherRevision;
        Weather weather;
        final java.util.TreeMap<Integer,Heat> sources=new java.util.TreeMap<>();
        double thermal,source,exterior;
        double initialEnergy,divergence,pressureResidual,thermalResidual,stepEnergyError;
        double geometryEnergy;
        long transfers;
        long pressureIterations,thermalSweeps;
        void pressure(PressureProjector.Quality pressure) {
            divergence=Math.max(divergence,pressure.maxDivergence());pressureResidual=Math.max(pressureResidual,pressure.finalResidual());
            pressureIterations+=pressure.iterations();
        }
        State(Prepared prepared,Snapshot snapshot,Settings settings,long tick,Seed seed,Weather weather,long weatherRevision) {
            this.weather=weather;this.weatherRevision=weatherRevision;
            this.tick=tick;var flow=prepared.flow();
            grid=new CoupledAirGrid(prepared.thermal(),FlowBoundary.wind(flow,weather.windX,0,weather.windZ),
                    FlowSettings.DEFAULT,ThermalTransport.Settings.defaults());
            for(int i=0;i<prepared.thermal().states();i++) grid.setTemperature(i,weather.ambient);
            for(int i=0;i<snapshot.bounds.cells();i++) if(snapshot.heat(i)!=null) sources.put(i,snapshot.heat(i));
            grid.setSources(snapshot.sources(prepared.thermal(),weather.ambient));
            background=new double[flow.nodes()];Arrays.fill(background,weather.ambient);
            exteriorCelsius=new double[flow.faces()];Arrays.fill(exteriorCelsius,weather.ambient);
            FlowGrid.Step initial;
            if(seed==null) {
                initial=grid.initialize(weather.windX,0,weather.windZ);initialEnergy=grid.energy();
            } else {
                if(!snapshot.sameMaterials(seed.snapshot) || seed.frame.throughTick!=tick) throw new IllegalArgumentException("Incompatible geometry-transfer seed");
                var mapped=GeometryTransfer.map(seed.prepared.thermal(),prepared.thermal(),seed.frame.temperature,seed.frame.flux,weather.ambient);
                initial=mapped.initialize(grid);
                var old=seed.frame;steps=old.acceptedSteps;thermal=old.thermalSeconds;source=old.sourceEnergy;exterior=old.exteriorEnergy;
                sourceRevision=old.sourceRevision;initialEnergy=old.initialEnergy;geometryEnergy=old.geometryEnergy+mapped.incomingEnergy-mapped.outgoingEnergy;
                transfers=old.transfers+1;divergence=old.quality.maxDivergence();pressureResidual=old.quality.maxPressureResidual();
                thermalResidual=old.quality.maxThermalResidual();stepEnergyError=old.quality.maxStepEnergyError();
                pressureIterations=old.quality.pressureIterations();thermalSweeps=old.quality.thermalSweeps();
            }
            if(!initial.ready()) throw new IllegalStateException("Initial projection rejected: "+initial.status());
            pressure(initial.pressure());
        }
        void change(SourceChange change,Prepared prepared,Settings settings) {
            if(change.heat==null) sources.remove(change.cell);else sources.put(change.cell,change.heat);
            rebuildSources(prepared);sourceRevision=change.revision;
        }
        void rebuildSources(Prepared prepared) {
            var out=new java.util.ArrayList<ThermalTransport.Source>();
            for(var entry:sources.entrySet()) {
                var heat=entry.getValue();
                if(heat.power()!=0) out.addAll(java.util.List.of(ThermalSources.fromBlock(prepared.thermal(),entry.getKey(),heat.power(),heat.limit(weather.ambient),heat.perFace())));
            }
            grid.setSources(out.toArray(ThermalTransport.Source[]::new));
        }
        void changeWeather(WeatherChange change,Prepared prepared) {
            var next=change.weather;
            if(next.windX!=weather.windX || next.windZ!=weather.windZ) {
                double[] temperature=new double[prepared.thermal().states()],flux=new double[prepared.flow().faces()];
                for(int i=0;i<temperature.length;i++) temperature[i]=grid.temperature(i);
                for(int i=0;i<flux.length;i++) flux[i]=grid.faceFlux(i);
                var replacement=new CoupledAirGrid(prepared.thermal(),FlowBoundary.wind(prepared.flow(),next.windX,0,next.windZ),
                        FlowSettings.DEFAULT,ThermalTransport.Settings.defaults());
                var projection=GeometryTransfer.map(prepared.thermal(),prepared.thermal(),temperature,flux,next.ambient).initialize(replacement);
                if(!projection.ready()) throw new IllegalStateException("Weather projection rejected: "+projection.status());
                grid=replacement;pressure(projection.pressure());
            }
            weather=next;weatherRevision=change.revision;
            Arrays.fill(background,next.ambient);Arrays.fill(exteriorCelsius,next.ambient);rebuildSources(prepared);
        }
    }
    static final class Work {
        private final Prepared prepared;
        private final Snapshot snapshot;
        private final Settings settings;
        private final State state;
        private final long initialTick;
        private final List<ThermalClock.Segment> segments;
        private final List<SourceChange> changes;
        private final Seed seed;
        private final Weather initialWeather;
        private final long initialWeatherRevision;
        private final List<WeatherChange> weatherChanges;
        private final java.util.concurrent.atomic.AtomicBoolean started=new java.util.concurrent.atomic.AtomicBoolean();
        private Work(Prepared prepared,Snapshot snapshot,Settings settings,State state,long initialTick,List<ThermalClock.Segment> segments,List<SourceChange> changes,Seed seed,
                     Weather initialWeather,long initialWeatherRevision,List<WeatherChange> weatherChanges) {
            this.prepared=prepared;this.snapshot=snapshot;this.settings=settings;this.state=state;this.initialTick=initialTick;this.segments=List.copyOf(segments);
            this.changes=List.copyOf(changes);
            this.seed=seed;
            this.initialWeather=initialWeather;this.initialWeatherRevision=initialWeatherRevision;this.weatherChanges=List.copyOf(weatherChanges);
        }
        Result run() {
            if(!started.compareAndSet(false,true)) throw new IllegalStateException("Diagnostic work may execute only once");
            try {
                State next=state==null?new State(prepared,snapshot,settings,initialTick,seed,initialWeather,initialWeatherRevision):state;
                int used=0,change=0,weather=0;
                while(weather<weatherChanges.size() && weatherChanges.get(weather).tick==next.tick) next.changeWeather(weatherChanges.get(weather++),prepared);
                while(change<changes.size() && changes.get(change).tick==next.tick) next.change(changes.get(change++),prepared,settings);
                for(var segment:segments) {
                    if(next.tick!=segment.startTick()) throw new IllegalStateException("Nonchronological diagnostic interval");
                    while(next.tick<segment.endTick() && used<settings.maxStepsPerJob) {
                        long end=next.tick+Math.min(PHYSICAL_TICKS,segment.endTick()-next.tick);
                        if(change<changes.size()) {
                            if(changes.get(change).tick<next.tick) throw new IllegalStateException("Source history precedes retained state");
                            end=Math.min(end,changes.get(change).tick);
                        }
                        if(weather<weatherChanges.size()) {
                            if(weatherChanges.get(weather).tick<next.tick) throw new IllegalStateException("Weather history precedes retained state");
                            end=Math.min(end,weatherChanges.get(weather).tick);
                        }
                        var step=next.grid.step((end-next.tick)/20.0,segment.scale(),next.background,next.exteriorCelsius);
                        if(!step.ready()) return new Result(this,next,null,"Coupled step rejected: "+step.status()
                                +(step.flow()==null?"":"; flow="+step.flow().status()+"; pressure="+step.flow().pressure()));
                        next.tick=end;next.steps++;used++;next.thermal+=step.thermalSeconds();
                        next.source+=step.sourceEnergy();next.exterior+=step.exteriorEnergy();
                        next.pressure(step.flow().pressure());
                        next.thermalResidual=Math.max(next.thermalResidual,Math.max(step.first().maxTemperatureResidual(),step.second().maxTemperatureResidual()));
                        next.stepEnergyError=Math.max(next.stepEnergyError,Math.abs(step.energyError()));
                        next.thermalSweeps+=step.first().sweeps()+step.second().sweeps();
                        while(weather<weatherChanges.size() && weatherChanges.get(weather).tick==next.tick) next.changeWeather(weatherChanges.get(weather++),prepared);
                        while(change<changes.size() && changes.get(change).tick==next.tick) next.change(changes.get(change++),prepared,settings);
                    }
                    if(used==settings.maxStepsPerJob) break;
                }
                return new Result(this,next,new Frame(next,prepared,snapshot),null);
            } catch(RuntimeException error) { return new Result(this,null,null,error.toString()); }
        }
        boolean initialization() { return state==null; }
    }
    static final class Result {
        private final Work work;
        private final State state;
        private final Frame frame;
        private final String failure;
        private Result(Work work,State state,Frame frame,String failure) { this.work=work;this.state=state;this.frame=frame;this.failure=failure; }
        boolean failed() { return failure!=null; }
    }
    private final Thread owner=Thread.currentThread();
    private final Prepared prepared;
    private final Snapshot snapshot;
    private final Settings settings;
    private final long initialTick;
    private final ThermalClock clock=new ThermalClock(MAX_LAG_TICKS);
    private long observedTick,acceptedTick;
    private State state;
    private List<ThermalClock.Segment> pending=List.of();
    private final java.util.ArrayList<SourceChange> changes=new java.util.ArrayList<>();
    private long sourceRevision;
    private Work outstanding;
    private Frame published;
    private Status status=Status.INITIALIZING;
    private String reason="";
    private Seed seed;
    private final Weather initialWeather;
    private final long initialWeatherRevision;
    private long weatherRevision;
    private final java.util.ArrayList<WeatherChange> weatherChanges=new java.util.ArrayList<>();
    private boolean sealed;
    LocalAirRun(Prepared prepared,Snapshot snapshot,Settings settings,long tick,double responseScale) {
        this(prepared,snapshot,settings,tick,responseScale,null);
    }
    LocalAirRun(Prepared prepared,Snapshot snapshot,Settings settings,long tick,double responseScale,Seed seed) {
        this(prepared,snapshot,settings,tick,responseScale,seed,seed==null?new Weather(settings.ambient,settings.windX,settings.windZ):seed.frame.weather,seed==null?0:seed.frame.weatherRevision);
    }
    LocalAirRun(Prepared prepared,Snapshot snapshot,Settings settings,long tick,double responseScale,Seed seed,Weather initialWeather,long initialWeatherRevision) {
        if(tick<0) throw new IllegalArgumentException("Nonnegative initial tick required");
        this.prepared=prepared;this.snapshot=snapshot;this.settings=settings;initialTick=tick;acceptedTick=tick;
        this.seed=seed;if(seed!=null) sourceRevision=seed.frame.sourceRevision;
        this.initialWeather=initialWeather;this.initialWeatherRevision=initialWeatherRevision;weatherRevision=initialWeatherRevision;
        observe(tick,responseScale);
    }
    private void owner() { if(Thread.currentThread()!=owner) throw new IllegalStateException("Diagnostic control requires owner thread"); }
    boolean changeWeather(long tick,Weather weather,long revision,double scale) {
        java.util.Objects.requireNonNull(weather);
        owner();if(sealed || tick<observedTick || revision<=weatherRevision || weatherChanges.size()>=256
                || status!=Status.READY && status!=Status.INITIALIZING) return false;
        observe(tick,scale);if(status==Status.LAG_LIMIT) return false;
        weatherChanges.add(new WeatherChange(revision,tick,weather));weatherRevision=revision;return true;
    }
    boolean changeSource(long tick,int cell,Heat heat,double scale) {
        owner();
        if(sealed || (status!=Status.READY && status!=Status.INITIALIZING) || tick<observedTick || changes.size()>=MAX_SOURCE_CHANGES) return false;
        var b=snapshot.bounds;
        if(cell<0 || cell>=b.cells()) throw new IllegalArgumentException("Source cell outside domain");
        int x=cell%b.nx(),y=cell/b.nx()%b.ny(),z=cell/(b.nx()*b.ny());
        if(x==0 || y==0 || z==0 || x==b.nx()-1 || y==b.ny()-1 || z==b.nz()-1) return false;
        observe(tick,scale);
        if(status==Status.LAG_LIMIT) return false;
        changes.add(new SourceChange(++sourceRevision,tick,cell,heat));
        return true;
    }
    void observe(long tick,double scale) {
        owner();if(status==Status.CLOSED || status==Status.FAILED || status==Status.LAG_LIMIT) return;
        if(sealed && tick!=observedTick) throw new IllegalStateException("Geometry barrier cannot advance past its edit tick");
        clock.observe(tick,scale);observedTick=tick;
        if(tick-acceptedTick>MAX_LAG_TICKS) { status=Status.LAG_LIMIT;published=null;reason="Diagnostic backlog exceeds retained clock history; restart required"; }
    }
    boolean seal(long tick,double scale) {
        owner();if(sealed || tick<observedTick || status!=Status.READY && status!=Status.INITIALIZING) return false;
        observe(tick,scale);if(status==Status.LAG_LIMIT) return false;sealed=true;return true;
    }
    Seed sealedSeed() {
        owner();return sealed && outstanding==null && acceptedTick==observedTick && changes.isEmpty() && weatherChanges.isEmpty() && status==Status.READY
                ?new Seed(prepared,snapshot,published):null;
    }
    Work takeWork() {
        owner();if(outstanding!=null || status==Status.CLOSED || status==Status.FAILED || status==Status.LAG_LIMIT) return null;
        if(state!=null && pending.isEmpty()) {
            if(observedTick-acceptedTick<PHYSICAL_TICKS && changes.isEmpty() && weatherChanges.isEmpty() && !(sealed && observedTick>acceptedTick)) return null;
            pending=clock.segments(acceptedTick,observedTick);
        }
        var remaining=new java.util.ArrayList<ThermalClock.Segment>();
        for(var segment:pending) if(segment.endTick()>acceptedTick)
            remaining.add(new ThermalClock.Segment(Math.max(acceptedTick,segment.startTick()),segment.endTick(),segment.scale()));
        var segments=state==null?List.<ThermalClock.Segment>of():List.copyOf(remaining);
        outstanding=new Work(prepared,snapshot,settings,state,initialTick,segments,changes,seed,initialWeather,initialWeatherRevision,weatherChanges);return outstanding;
    }
    void complete(Result result) {
        owner();if(result.work!=outstanding) return;
        outstanding=null;
        if(status==Status.CLOSED || status==Status.LAG_LIMIT) return;
        if(result.failure!=null) { status=Status.FAILED;published=null;reason=result.failure;return; }
        state=result.state;seed=null;acceptedTick=result.frame.throughTick;published=result.frame;status=Status.READY;
        changes.removeIf(change->change.revision<=result.frame.sourceRevision);
        weatherChanges.removeIf(change->change.revision<=result.frame.weatherRevision);
        if(!pending.isEmpty() && acceptedTick==pending.getLast().endTick()) pending=List.of();
    }
    Frame published() { owner();return published!=null && published.sourceRevision==sourceRevision && published.weatherRevision==weatherRevision?published:null; }
    Status status() { owner();return status; }
    String reason() {
        owner();if(!reason.isEmpty()) return reason;
        if(!weatherChanges.isEmpty()) return "Awaiting chronological weather refresh";
        return changes.isEmpty()?"":"Awaiting chronological source refresh";
    }
    void close() { owner();status=Status.CLOSED;published=null;state=null; }
}
