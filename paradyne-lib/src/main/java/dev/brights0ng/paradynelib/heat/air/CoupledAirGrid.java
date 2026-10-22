// Written by ChatGPT; extended by ChatGPT with explicit thermal traversal, sampling, and flow-kernel controls.
// Extended by ChatGPT with validated remapped-state initialization and fresh projection.
package dev.brights0ng.paradynelib.heat.air;

/** Offline, single-owner temperature/momentum sequence. No world access or scheduling.
 * A bounded physical step is half thermal -> momentum/buoyancy/projection -> half thermal.
 * Temperatures, velocity and pressure warm start commit together or retain their previous state.
 * Reusable rollback buffers are private; consumers cannot observe an intermediate half step.
 */
public final class CoupledAirGrid {
    public enum Status { READY, INVALID_INPUT, FIRST_THERMAL_FAILED, FLOW_FAILED, SECOND_THERMAL_FAILED, TEMPERATURE_LIMIT }
    public record Step(Status status, double physicalSeconds, double thermalSeconds,
                       ThermalTransport.Step first, FlowGrid.Step flow, ThermalTransport.Step second, long nanos) {
        public boolean ready() { return status == Status.READY; }
        public double sourceEnergy() { return ready() ? first.sourceEnergy() + second.sourceEnergy() : 0; }
        public double exteriorEnergy() { return ready() ? first.exteriorEnergy() + second.exteriorEnergy() : 0; }
        public double energyError() { return ready() ? first.energyError() + second.energyError() : Double.NaN; }
    }
    private final ThermalGeometry geometry;
    private final FlowSettings flowSettings;
    private final ThermalTransport thermal;
    private final FlowGrid flow;
    private final double[] saved, air, preparedFlux;

    public CoupledAirGrid(ThermalGeometry geometry, FlowBoundary boundary, FlowSettings flowSettings,
                          ThermalTransport.Settings thermalSettings) {
        this(geometry, boundary, flowSettings, thermalSettings, ThermalTransport.DEFAULT_MODE);
    }
    public CoupledAirGrid(ThermalGeometry geometry, FlowBoundary boundary, FlowSettings flowSettings,
                          ThermalTransport.Settings thermalSettings, ThermalTransport.Mode mode) {
        this(geometry, boundary, flowSettings, thermalSettings, mode, true);
    }
    CoupledAirGrid(ThermalGeometry geometry, FlowBoundary boundary, FlowSettings flowSettings,
                   ThermalTransport.Settings thermalSettings, ThermalTransport.Mode mode, boolean cacheSamplingOrigins) {
        this(geometry,boundary,flowSettings,thermalSettings,mode,cacheSamplingOrigins,FlowGrid.DEFAULT_KERNEL);
    }
    CoupledAirGrid(ThermalGeometry geometry, FlowBoundary boundary, FlowSettings flowSettings,
                   ThermalTransport.Settings thermalSettings, ThermalTransport.Mode mode, boolean cacheSamplingOrigins,
                   FlowGrid.Kernel kernel) {
        if(geometry.unrepresentedFluidCells()>0) {
            throw new IllegalArgumentException("Coupled fluid occupancy requires explicit thermal material values");
        }
        this.geometry = geometry; this.flowSettings = flowSettings;
        thermal = new ThermalTransport(geometry, thermalSettings, mode);
        flow = new FlowGrid(geometry.flow, boundary, flowSettings,
                PressureProjector.Preconditioner.MODIFIED_INCOMPLETE_CHOLESKY, cacheSamplingOrigins, kernel);
        saved = new double[geometry.states()]; air = new double[geometry.airStates()];
        preparedFlux = new double[geometry.flow.faces()];
    }
    public void setTemperature(int state, double celsius) { thermal.setTemperature(state, celsius); }
    public double temperature(int state) { return thermal.temperature(state); }
    public void setSources(ThermalTransport.Source... sources) { thermal.setSources(sources); }
    public double energy() { return thermal.energy(); }
    public double faceFlux(int face) { return flow.faceFlux(face); }
    public double componentVelocity(int node, int axis) { return flow.componentVelocity(node, axis); }
    public boolean initialized() { return flow.valid(); }
    public FlowGrid.Step initialize(double x, double y, double z) {
        flow.seedUniform(x, y, z); return flow.projectInitial();
    }
    /** Initialize a new owner from mapped state. Pressure scratch is new; only a successful projection is usable. */
    FlowGrid.Step initializeMapped(ThermalGeometry target,double[] temperature,double[] faceVelocity) {
        if(target!=geometry || initialized() || temperature.length!=geometry.states() || faceVelocity.length!=geometry.flow.faces())
            throw new IllegalArgumentException("Invalid mapped initialization");
        for(int i=0;i<temperature.length;i++) if(!Double.isFinite(temperature[i]) || i<geometry.airStates() && !supportedAir(temperature[i]))
            throw new IllegalArgumentException("Invalid mapped temperature");
        for(double speed:faceVelocity) if(!Double.isFinite(speed) || Math.abs(speed)>flowSettings.maxSpeed())
            throw new IllegalArgumentException("Invalid mapped velocity");
        for(int i=0;i<temperature.length;i++) thermal.setTemperature(i,temperature[i]);
        for(int f=0;f<faceVelocity.length;f++) flow.seedFace(f,faceVelocity[f]);
        return flow.projectInitial();
    }

    /** One bounded physical interval only: never converts a long thermal catch-up into a long airflow step. */
    public Step step(double physicalSeconds, double responseScale, double[] background, double[] exteriorCelsius) {
        long begin = System.nanoTime();
        if (!initialized() || !Double.isFinite(physicalSeconds) || physicalSeconds <= 0 || physicalSeconds > flowSettings.maxStep()
                || !Double.isFinite(responseScale) || responseScale < ThermalClock.MIN_SCALE || responseScale > ThermalClock.MAX_SCALE
                || !Double.isFinite(physicalSeconds * responseScale) || physicalSeconds * responseScale / 2 <= 0
                || background.length != air.length || exteriorCelsius.length != preparedFlux.length) {
            return result(Status.INVALID_INPUT, physicalSeconds, 0, null, null, null, begin);
        }
        for (double t : background) {
            if (!supportedAir(t)) { return result(Status.INVALID_INPUT, physicalSeconds, 0, null, null, null, begin); }
        }
        double seconds = ThermalClock.thermalSeconds(physicalSeconds, responseScale);
        thermal.setMixingReference(background);
        thermal.copyTemperatures(saved);
        var first = thermal.step(seconds / 2, flow, exteriorCelsius);
        if (!first.ready()) { return result(Status.FIRST_THERMAL_FAILED, physicalSeconds, seconds, first, null, null, begin); }
        thermal.copyAirTemperatures(air);
        var momentum = flow.prepareStep(physicalSeconds, air, background);
        if (!momentum.ready()) {
            thermal.restoreTemperatures(saved);
            return result(Status.FLOW_FAILED, physicalSeconds, seconds, first, momentum, null, begin);
        }
        flow.copyPreparedFlux(preparedFlux);
        var second = thermal.advance(seconds / 2, preparedFlux, exteriorCelsius);
        Status status = second.ready() ? Status.READY : Status.SECOND_THERMAL_FAILED;
        if (second.ready()) {
            thermal.copyAirTemperatures(air);
            for (double t : air) { if (!supportedAir(t)) { status = Status.TEMPERATURE_LIMIT; break; } }
        }
        if (status != Status.READY) {
            thermal.restoreTemperatures(saved); flow.discardPrepared();
        } else { flow.acceptPrepared(); }
        return result(status, physicalSeconds, seconds, first, momentum, second, begin);
    }
    private boolean supportedAir(double t) {
        return Double.isFinite(t) && t > -273.15 && t <= flowSettings.maxTemperatureC();
    }
    private Step result(Status status, double physical, double seconds, ThermalTransport.Step first,
                        FlowGrid.Step momentum, ThermalTransport.Step second, long begin) {
        return new Step(status, physical, seconds, first, momentum, second, System.nanoTime() - begin);
    }
}
