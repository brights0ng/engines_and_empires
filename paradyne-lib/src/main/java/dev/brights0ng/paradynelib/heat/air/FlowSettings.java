// Written by ChatGPT.
package dev.brights0ng.paradynelib.heat.air;

/** Experimental numerical settings, not gameplay defaults. Rejections are reported, never silently clipped. */
public record FlowSettings(int pressureIterations,double relativeResidual,double absoluteResidual,
                           double divergenceTolerance,double maxStep,double damping,double buoyancyScale,
                           double maxSpeed,double maxTemperatureC) {
    public static final FlowSettings DEFAULT = new FlowSettings(512,1e-8,1e-10,1e-7,1,0.05,1,100,3000);
    public FlowSettings {
        if(pressureIterations<1 || pressureIterations>10000 || !positive(relativeResidual) || !positive(absoluteResidual)
                || !positive(divergenceTolerance) || !positive(maxStep) || !Double.isFinite(damping) || damping<0
                || !Double.isFinite(buoyancyScale) || buoyancyScale<0 || !positive(maxSpeed)
                || !Double.isFinite(maxTemperatureC) || maxTemperatureC<=-273.15) {
            throw new IllegalArgumentException("Invalid bounded flow settings");
        }
    }
    private static boolean positive(double v) { return Double.isFinite(v) && v>0; }
}
