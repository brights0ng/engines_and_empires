// Written by ChatGPT.
package dev.brights0ng.meteodyne.instrument;

/** A sensor's zero and full-strength endpoints. Reversed ranges deliberately invert its output. */
public record SensorRange(double zero, double full) {
    public SensorRange {
        if (!Double.isFinite(zero) || !Double.isFinite(full) || Math.abs(full - zero) < 0.1 - 1e-9) {
            throw new IllegalArgumentException("Sensor endpoints must be finite and at least 0.1 apart");
        }
    }

    public double fraction(double reading) {
        return Double.isFinite(reading) ? Math.clamp((reading - zero) / (full - zero), 0, 1) : 0;
    }

    public int signal(double reading) {
        return (int) Math.round(15 * fraction(reading));
    }
}
