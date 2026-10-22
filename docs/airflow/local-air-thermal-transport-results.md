<!-- Written by ChatGPT. -->
# Stage 3: shared-flux thermal foundation, 2026-10-20

Historical first checkpoint. The [source and two-way coupling follow-up](local-air-coupled-results.md) implements the next numerical milestone and records its updated verification, cost, and calibration comparison. Results below describe this earlier foundation checkpoint.

This is the first checkpoint of stage 3 in [the local air plan](local-air-model-plan.md), not completion of heat/air coupling. It adds a pure thermal transport owner alongside the offline flow kernel. Meteodyne's live `AirGrid`, accepted calibration, source definitions, instruments, and scheduling are unchanged.

## Implementation

`ThermalGeometry` stores one thermal state per connected air pocket and a separate state per solid-bearing block. Air capacity is its accessible volume; material capacity is solid volume times its table capacity and the existing `solidCap`. A partial solid and its air therefore have independent temperatures. Multiple disconnected air pockets in the same block remain separate; they may exchange heat through the block's material when conductance is enabled, but never exchange air through it.

Static exchange links identify both thermal owners. Internal solid/air surfaces use the owning block's material. Shared block boundaries use overlapping sixteenth-block patches to identify air-to-neighboring-solid and solid-to-solid contact. Full/empty shapes have fast paths. Each internal thermal transfer appears as an equal and opposite pair. Mixing uses shared open air faces. Material film and half-block resistance follow the existing `AirGrid` laws; partial shapes retain a coarse half-block resistance approximation rather than a new thickness-calibrated material law.

`ThermalTransport` advances absolute Celsius temperatures with backward-Euler upwind transport on the exact signed shared-face volume fluxes, plus implicit pairwise mixing/material exchange. Incoming exterior air uses the supplied per-face temperature; outgoing air carries the solved local temperature. Stored air and wall temperatures are not reset when weather inputs change. This path adds none of the legacy rise, asymmetric convection, opening boost, or empirical exposed wind/loss terms. Radiation and sources are not implemented here.

The direct flow entry point requires an initialized `FlowGrid` with the identical immutable geometry owner, not merely a matching revision. A prescribed-flux control validates finite inputs and volume-normalized balance. Thermal seconds include the approved response scale; this owner neither advances physical airflow time nor scales velocities.

The nonsymmetric upwind system uses alternating forward/backward Gauss-Seidel sweeps over retained sparse rows. Defaults for this checkpoint are 512 sweeps, maximum unresolved temperature change `1e-9 C`, absolute energy-budget error `1e-8 C*air-cell`, and maximum prescribed-flow divergence `1e-7 / second`. These are experimental numerical checks, not qualified live defaults. Energy changes and exterior transfers use compensated summation; computing a small transfer by subtracting two large domain totals was insufficient at 48-cubed resolution.

Only a finite candidate passing both residual and energy checks replaces published temperatures. Invalid input, unbalanced flow, or failed convergence preserves all previous temperatures. There is no generic temperature clamp or silent tolerance relaxation. Work arrays and geometry are cached; the update has no allocation per cell/face.

Fluids exclude air but have no transported thermal state in this checkpoint; fluid interfaces are thermally insulated. Material at the outer domain boundary is also insulated. These explicit limitations must be resolved/reviewed during subsequent boundary/source integration, especially for water/lava fixtures. Exterior air exchange currently occurs through advection, without an additional exterior diffusive reservoir.

## Verification

Java 21 directly compiled every main and test source in the pure `heat.air` package using the locally cached JUnit/Gson dependencies. JUnit Platform ran **77 tests, all passing**, including **16 new thermal fixtures**. Coverage includes analytic upwind solutions in both directions, cold inflow, sealed-box conservation and monotonicity, uniform temperature in pressure-projected obstacle flow, material storage and analytic solid conduction, patch ownership, separate slab temperatures, disconnected pockets, a one-subcell cavity, tiny-volume transport, weather changes, exact prescribed-flow response scaling, invalid inputs/owners, failed-solve retention, and small energy changes in a 48-cubed domain. Existing thermal, pressure, airflow, and timing tests were included.

The required `compileJava compileTestJava test runGameTestServer` check was attempted but **did not run successfully**. The ordinary wrapper could not write the user-cache lock; an installed Gradle distribution using a writable project-local user home and read-only dependency cache then could not connect to its daemon. A no-daemon full-check attempt also required a single-use daemon and failed at that connection. This is not a passing full build or game-test result. No tests/assertions were removed, skipped, or weakened. Evidence is in `../../build/stage3-direct-tests.log`, `build/stage3-transport-targeted.log`, and `build/stage3-full-check.log`.

No in-game visual/gameplay verification or live-worker measurement was performed.

## Measurement and remaining gate

The [three-fork transport report](../benchmarks/thermal-transport-2026-10-20/README.md) measures fixed projected wind with a restored heat pulse and material exchange. It does not advance buoyancy or inject sources. Pressure construction/solve, world snapshots, and publication are excluded from update timings.

The measured transport cost and long-interval rejections prevent a runtime-readiness claim. Do not turn failed work into a timing success, select a live radius, or enable the experiment from this checkpoint. The absolute energy criterion also needs a measured roundoff/convergence assessment for large thermal intervals; reaching it must not be hidden by clamping temperatures.

Next work: add source injection/thermostat limits using the existing laws, assemble transactional half-thermal/flow/half-thermal updates, demonstrate self-evolving hot/cold circulation and ventilation, compare the campfire house and wall cool-down, and address solver cost/long-interval acceptance before stage 4. Retain the legacy control throughout.
