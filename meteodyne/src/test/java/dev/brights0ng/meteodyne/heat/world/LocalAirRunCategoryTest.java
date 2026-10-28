// Written by Claude.
package dev.brights0ng.meteodyne.heat.world;

import dev.brights0ng.paradynelib.heat.air.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class LocalAirRunCategoryTest {
    private static PressureProjector.Quality pressure(PressureProjector.Status status) { return new PressureProjector.Quality(status,0,0,0,0,0,0); }
    private static FlowGrid.Step flow(FlowGrid.Status status,PressureProjector.Quality pressure) { return new FlowGrid.Step(status,pressure,0,0,0); }
    private static ThermalTransport.Step thermal(ThermalTransport.Status status) { return new ThermalTransport.Step(status,0,0,0,0,0,0,0,0,0,0,0,0); }
    private static CoupledAirGrid.Step step(CoupledAirGrid.Status status,ThermalTransport.Step first,FlowGrid.Step flow,ThermalTransport.Step second) {
        return new CoupledAirGrid.Step(status,0.5,0.5,first,flow,second,0);
    }
    @Test void flowSpeedLimitWithoutPressureNamesNoReadyStage() {
        var s=step(CoupledAirGrid.Status.FLOW_FAILED,thermal(ThermalTransport.Status.READY),flow(FlowGrid.Status.SPEED_LIMIT,null),null);
        assertEquals("STEP_FLOW_FAILED_FLOW_SPEED_LIMIT",LocalAirRun.category(s));
    }
    @Test void pressureFailureNamesProjectorStatus() {
        var s=step(CoupledAirGrid.Status.FLOW_FAILED,thermal(ThermalTransport.Status.READY),
                flow(FlowGrid.Status.PRESSURE_FAILED,pressure(PressureProjector.Status.DIVERGENCE_LIMIT)),null);
        assertEquals("STEP_FLOW_FAILED_FLOW_PRESSURE_FAILED_PRESSURE_DIVERGENCE_LIMIT",LocalAirRun.category(s));
    }
    @Test void secondThermalFailureNamesSecondTransportStatus() {
        var s=step(CoupledAirGrid.Status.SECOND_THERMAL_FAILED,thermal(ThermalTransport.Status.READY),
                flow(FlowGrid.Status.READY,pressure(PressureProjector.Status.READY)),thermal(ThermalTransport.Status.NOT_CONVERGED));
        assertEquals("STEP_SECOND_THERMAL_FAILED_FLOW_READY_PRESSURE_READY_NOT_CONVERGED",LocalAirRun.category(s));
    }
}
