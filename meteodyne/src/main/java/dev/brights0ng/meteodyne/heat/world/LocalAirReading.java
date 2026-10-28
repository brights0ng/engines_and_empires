// Written by ChatGPT.
package dev.brights0ng.meteodyne.heat.world;

import java.util.Objects;
import java.util.Optional;

/** Immutable point query for the experimental domain. Does not request work or select instrument behavior. */
public record LocalAirReading(Status status, Reason reason, Optional<Sample> sample) {
    public enum Status { CURRENT, STALE, PENDING, UNAVAILABLE }
    public enum Reason {
        NONE, AGE_LIMIT, NO_REQUEST, CLOSED, OUTSIDE_DOMAIN, OCCUPIED,
        PREPARING, REFRESHING, GEOMETRY_ONLY, REJECTED, FAILED, LAG_LIMIT
    }
    /** World-axis velocity in blocks/s; temperature in Celsius; age in game ticks, independent of thermal scale. */
    public record Sample(long throughTick, long ageTicks, double temperature, double x, double y, double z) {
        public Sample {
            if (throughTick < 0 || ageTicks < 0 || !Double.isFinite(temperature)
                    || !Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z))
                throw new IllegalArgumentException("Finite reading and nonnegative timestamps required");
        }
    }
    public LocalAirReading {
        Objects.requireNonNull(status); Objects.requireNonNull(reason); Objects.requireNonNull(sample);
        boolean measured = status == Status.CURRENT || status == Status.STALE;
        if (measured != sample.isPresent() || (status == Status.CURRENT) != (reason == Reason.NONE)
                || (status == Status.STALE) != (reason == Reason.AGE_LIMIT))
            throw new IllegalArgumentException("Inconsistent local-air reading");
    }
    static LocalAirReading missing(Status status, Reason reason) {
        return new LocalAirReading(status, reason, Optional.empty());
    }
    static void validate(long now, double x, double y, double z, long maxAgeTicks) {
        if (now < 0 || maxAgeTicks < 0 || !Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z))
            throw new IllegalArgumentException("Nonnegative time/age limit and finite position required");
    }
    /** Only readings inside the caller's age limit; stale values require explicit inspection of sample(). */
    public Optional<Sample> currentSample() { return status == Status.CURRENT ? sample : Optional.empty(); }
}
