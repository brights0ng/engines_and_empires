<!-- Written by ChatGPT. -->
# Pure airflow kernel: stage 2

The offline kernel is implemented in Paradyne Lib. It does not replace live wind or thermal behavior. Heat transport, world geometry adapters, worker scheduling, publication, instrument readings, and ship transforms remain later stages of the local air plan.

The measurements below preserve the initial 2026-10-19 diagonal-preconditioner baseline. See the [2026-10-20 pressure optimization](local-air-pressure-results.md) for the current cached row operator and modified incomplete-Cholesky default.

## Geometry and ownership

`FlowShape` bakes static 1/16-block occupancy into connected air components. Empty air, full solid and full fluid have shared fast representations. Solids and fluids occupy distinct volumes; waterlogging does not overwrite solid material. `FlowGeometry` holds air volumes, centroids, solid/fluid exchange areas, and one shared face per overlapping pair of adjacent air components. Matching face patches determine aperture area; taking the minimum of two unrelated opening percentages would create false connections.

A thin pane or closed door separating two sides inside one block therefore has two independent air nodes. The static bake is not a 16-times-finer dynamic fluid grid. Multiple openings between the same pair share one normal velocity. Shapes are numerical fixtures here, not Minecraft block-state mappings; door frames, actual collision shapes, fluid levels and unusual modded geometry still need the world adapter and its integration tests.

Geometry is immutable and revisioned. A `FlowBoundary` belongs to one geometry object. A `FlowGrid` has a single mutable owner, retains primitive work buffers, and publishes its internal field only after a successful projection. Changed topology requires a new grid and projection; this stage provides no state migration or asynchronous publication protocol.

## Update and quality contract

The sequence is wall-aware semi-Lagrangian velocity transport, exponential damping, supplied-temperature buoyancy, and pressure projection. Face velocities are signed along positive X/Y/Z; flux is velocity times aperture area. The same flux is available for the later heat transport stage.

Backtraces traverse full air blocks cheaply and use subcell crossings inside partial shapes. They stop at walls, fluids, and the represented boundary even if their destination lies in air beyond an obstruction. Velocity reconstruction stays inside the current air component, averaging openings on each side and interpolating between sides. This is a coarse dissipative approximation, not a claim to reproduce turbulent wakes or detailed aperture jets.

Buoyancy is `9.81 * scale * (temperature - background) / (background + 273.15)` along Y. Inputs are per-component Celsius values. A temperature field equal to a height-dependent background produces no buoyancy. Background stratification is prescribed; heat and background temperature are not evolved by this kernel. World-up transforms for rotated ships are not implemented.

Incoming weather-face normal velocities are prescribed; other exterior faces use zero pressure correction and permit outflow or replacement inflow. Sealed domains omit exterior faces entirely. Obstacle surfaces have no normal flux; tangential no-slip viscosity is not modeled.

Pressure uses a cached symmetric flux stencil with aperture/distance weights, diagonal preconditioning, double-precision PCG, reusable work arrays, and the previous accepted pressure correction as a warm start. Velocity/geometry fields also use doubles in this correctness baseline. Each sealed connected component receives one symmetric pressure reference. Incompatible net prescribed flux is rejected. Air volume enters divergence diagnostics rather than asymmetrically scaling matrix rows. Pressure has no atmospheric/barometer meaning.

The experimental default cap is 512 iterations. Relative/absolute RMS residual tolerances are `1e-8` / `1e-10`; accepted fields also require maximum volume-normalized divergence at most `1e-7 / second`. Diagnostics include residuals, iteration count, RMS/maximum divergence, boundary imbalance, blocked traces, and separate transport/pressure timings. The benchmark retains a 64-iteration control to expose cheap but invalid solves. These are offline numerical settings, not selected live capacity defaults.

The supported step is positive and at most one physical second by default. Thermal response scaling does not enter momentum. Nonfinite/unsupported temperatures, excessive per-face speeds, incompatible boundaries, and unsuccessful pressure solves are reported explicitly. No candidate is silently clipped or substituted for the last valid field. Without an accepted initialization, field queries fail explicitly. A stale valid field is not permission for a future runtime publisher to claim fresh flow.

## Reproducing the comparison

Run `./gradlew.bat :paradyne-lib:flowBenchmark` for three separate JVMs, five warm-up steps and ten measured steps per workload. `-PflowBenchQuick=true` uses one JVM, one warm-up step and two measured steps as a smoke check. Reports are written under `build/benchmarks/flow/<UTC timestamp>/` as per-fork JSON and CSV. Timing thresholds do not affect ordinary tests.

The harness adds normal wind (6/0/2 m/s), storm wind (20/0/6), buoyancy, and combined wind plus prescribed-temperature buoyancy, at 0.25/0.5/1 physical seconds, with 64/512 pressure caps. Combined forcing does **not** mean coupled heat transport. Cases are 32³ multiroom, 48³ multiroom, a 64×8×8 domain with a 50-block row aligned with wind, a sealed house, and a partial-block house.

The existing `ThermalBenchmark` scene generator, frozen baseline, and fingerprints are unchanged. Shared fixture layouts feed both controls; the flow partial-door fixture replaces the legacy opening bitmask with a thin numerical slab. The long-row obstacle exists only in the flow fixture. Both thermal and flow fingerprints are recorded so those differences are explicit. Geometry creation and solver/coefficient creation are reported separately from initial projection, velocity transport and pressure. Geometry timing is a single build per scene/fork, not a warmed latency distribution. Solver creation allocation includes retained buffers; it is not total live heap. CPU/allocation counters can be unavailable, and Windows CPU resolution is coarse.

Reports retain rejected attempts, failed initialization, iterations and conservation quality beside costs. A case with failed initialization has no measured step samples; its zero accepted/rejected counts do not mean it passed. Its JSON `initialQuality` contains the failure diagnostics. Comparing a rejected 64-iteration candidate to a valid 512-iteration field as equal-quality work is invalid.

## Verification and decision gate

Nineteen deterministic unit fixtures cover occupancy/waterlogging, snow/slabs/stairs/fences, matched apertures, separating panes/doors/trapdoors, disconnected pressure references, stencil symmetry, uniform wind, obstacle routing and downstream recovery, room ventilation, sealed hot/cold return circulation, altitude-dependent ambient rest, wall/corner backtraces, tiny volumes, storm forcing, geometry ownership, physical versus thermal time, and failed-step retention. Pressure warm starts commit only after the owning grid also accepts corrected speed; an unaccepted or nonfinite pressure candidate leaves the retained warm start unchanged.

No in-game visual, room warm-up, live worker budget, publication freshness, or whole-server performance claim follows from these pure fixtures.

## Measured comparison, 2026-10-19

The complete three-JVM run is archived in [benchmarks/flow-kernel-2026-10-19](../benchmarks/flow-kernel-2026-10-19/README.md), with per-fork raw JSON and an aggregate summary. Java was 21.0.12.1+1-LTS, G1, 512 MiB initial/1 GiB maximum heap, six available processors, AMD64 Family 25 Model 97. The entire benchmark invocation took 7m55s. Every 512-cap initialization passed and all **1,800 measured steps** passed the residual/divergence gates across the five scenes, four modes, and three step sizes. Maximum observed accepted-step divergence was `9.985e-8 / second`; maximum absolute exterior imbalance was `5.905e-5 block³/second` over an entire domain. Internal shared-face fluxes cancel by construction.

At the 64-iteration control, 135 of 180 initializations failed. Of the 450 measured attempts after successful initialization, 360 failed projection and only 90 passed. Those attempts do not demonstrate useful low-cost flow progress. The higher cap is selected for correctness in the offline kernel, not as an acceptable live budget.

Combined wind plus supplied-temperature buoyancy at **one physical second per update**, cap 512:

| Scene | Air nodes / shared faces | Wall ms/update, median (range of fork means) | CPU ms/update, median | Transport / pressure wall ms, medians | Maximum pressure iterations |
| --- | ---: | ---: | ---: | ---: | ---: |
| Multiroom 32³ | 30,847 / 93,894 | 127.0 (126.3–131.0) | 118.8 | 17.5 / 108.9 | 251 |
| Multiroom 48³ | 106,535 / 322,958 | 687.0 (618.2–721.4) | 660.9 | 61.9 / 624.7 | 380 |
| 50-block row | 3,674 / 11,569 | 5.47 (5.24–5.48) | 6.25 | 1.60 / 3.74 | 74 |
| Sealed house, with surrounding exterior | 8,136 / 24,768 | 19.88 (19.88–19.90) | 18.75 | 3.96 / 15.89 | 140 |
| Partial-block house | 8,138 / 24,772 | 22.58 (21.46–24.15) | 20.31 | 5.28 / 16.80 | 145 |

Each timing median is taken independently; rounded phase medians need not add to the total median. CPU counters are coarse, especially for the small row case. Initialization is excluded from this table and retained separately in raw reports. Smaller timesteps require more updates per simulated second; their costs must not be read as a free increase in temporal accuracy. Response-scale behavior is checked by deterministic tests rather than multiplying momentum time in this harness.

Measured step allocation is 104 bytes for these cases: the step/quality result records, with no allocation per cell/face in the numerical loops. Solver creation allocates about 7.84 MiB at 32³ and 27.03 MiB at 48³, excluding geometry and supplied temperature arrays. Geometry preparation allocates about 21.63/74.20 MiB respectively, including transient construction buffers; these allocation counters are not retained-heap measurements. Rebuilding either on every step would be inappropriate.

A separate precision probe used a 12×5×5 open domain, one full block at (3,2,2), 20/0/6 m/s wind, and the default tolerances. The accepted double field had maximum divergence `6.622e-8 / second`; casting just its velocities to float raised that to `1.714e-6 / second`, outside the same gate (`../../build/flow-float-precision.log`). This supports keeping the initial correctness baseline in doubles; it is not a benchmark of an optimized float implementation or evidence that one is impossible.

**Gate:** numerical behavior is ready for further offline work; performance is not qualified for runtime integration. The 48³ combined case alone takes roughly 661 ms CPU per simulated second at the one-second step, versus the plan's approximately 100 ms CPU per real second target for the reference live scene's combined thermal/airflow work. The fixtures and live scene are different, so this is a warning about order of magnitude rather than a direct capacity estimate. The next engineering priority is stronger pressure preconditioning on these unchanged fixtures, followed by an equal-quality rerun. Keep the capped failure path and conservation gate; do not hide the problem by loosening tolerances, pretending failed candidates advanced, or changing thermal calibration.

Heat/air coupling remains stage 3. The existing thermal solver, response control, gauges, sources, weather, and ship-force behavior remain in use in-game.

## Full regression evidence

The final required invocation was `./gradlew.bat compileJava compileTestJava test runGameTestServer --continue` (`../../build/flow-kernel-final-check.log`). Compilation passed; 1,117 unit tests completed without failures or errors, with the two existing opt-in benchmark skips. All 19 new kernel tests passed. Coreless passed 205/205. Standalone results: Meteodyne 89/89, Frontier 18/18, lib 3/3, Sable Fixes 2/2, Seismic 20/20, Spoilage 34/34, Sparse Ores 13/13 bare and 13/13 with Create.

Aggregate passed 255/256; the command therefore exits unsuccessfully. Its only final failure is the existing `theGameTestServerHasNoSeasons` assertion because the local root launch includes Serene Seasons. The launch setup and assertion are unchanged. An earlier full run also hit `glazeSkipsBrightLight`; the unchanged standalone recheck passed 89/89 (`../../build/flow-kernel-meteodyne-recheck.log`), and the final full run passed it too. No glaze code or test was changed.

The three-fork numerical run is in `../../build/flow-kernel-qualification.log`; the earlier full check is retained as `build/flow-kernel-full-check.log`. The final regression includes the pressure warm-start transaction guard added during review. `git diff --check` passed. No client visual/gameplay trial was performed for this offline stage.

Numerical reference: [GPU Gems 3, chapter 30](https://developer.nvidia.com/gpugems/gpugems3/part-v-physics-simulation/chapter-30-real-time-simulation-and-rendering-3d-fluids) describes the transport/force/projection split and staggered-grid approach. Its GPU performance does not predict this Java CPU implementation.
