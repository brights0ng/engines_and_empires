// Written by Claude (GreedyFluids M8b5a: the buoyancy reference, report-only variants until Bright picks).
package dev.brights0ng.meteodyne.airflow;

/**
 * What a region's air rises or sinks against (M8b5a; {@code [heat] airflowBuoyancy}). The background field (the
 * temperature the air relaxes and is shown against) is the same in every mode; only the buoyancy reference changes.
 * <ul>
 *   <li>{@link #LOCAL}: the background itself (M7 to M8b4). Underground, deeper is warmer, so a cave whose top holds
 *       warm air can read colder there than at its floor.</li>
 *   <li>{@link #STABLE} (P1): the background made never unstable upward along each vertical run of air: its
 *       potential temperature's running maximum, back to temperature (GreedyFluids {@code withStableBuoyancy}).</li>
 *   <li>{@link #OUTSIDE} (P4): the outside air at the same place, without the ground's insulation blend.</li>
 *   <li>{@link #OUTSIDE_STABLE}: the outside air, made stable as in {@link #STABLE}.</li>
 * </ul>
 */
public enum AirflowBuoyancy {
    LOCAL, STABLE, OUTSIDE, OUTSIDE_STABLE;

    /** Whether this mode reads the outside air. */
    public boolean outside() {
        return this == OUTSIDE || this == OUTSIDE_STABLE;
    }

    /** Whether this mode takes the stable form. */
    public boolean stable() {
        return this == STABLE || this == OUTSIDE_STABLE;
    }
}
