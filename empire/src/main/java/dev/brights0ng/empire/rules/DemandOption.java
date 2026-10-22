// Written by Claude.
package dev.brights0ng.empire.rules;

import java.util.List;

/**
 * One thing a god may ask for in one of its tiers: what ({@code kind} and {@code ids}, as in {@link DemandLine}) and its
 * {@code multiplier} m (0 to 1), which scales the tier's quantity q into a count.
 */
public record DemandOption(DemandLine.Kind kind, List<String> ids, double multiplier) {

    public DemandOption {
        ids = List.copyOf(ids);
        if (ids.isEmpty()) {
            throw new IllegalArgumentException("a demand option names at least one id");
        }
        if (!(multiplier >= 0 && multiplier <= 1)) {
            throw new IllegalArgumentException("multiplier must be 0-1, is " + multiplier);
        }
    }

    /** A demand line for {@code units} of this (fluid units are 1000 mB each), nothing paid yet. */
    public DemandLine line(int units) {
        int required = kind == DemandLine.Kind.FLUID ? units * DemandLine.MB_PER_UNIT : units;
        return new DemandLine(kind, ids, required, 0);
    }

    /** Whether a line asks for the same thing as this option. */
    public boolean sameAs(DemandLine line) {
        return line.kind() == kind && line.ids().equals(ids);
    }
}
