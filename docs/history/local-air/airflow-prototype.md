<!-- Analysis and proposal written by ChatGPT. -->
# Local airflow prototype proposal

Status: historical first proposal; superseded by [the full local air model plan](local-air-model-plan.md). In particular, the newer plan includes temperature-driven buoyancy in the first usable coupled prototype rather than deferring it. The offline kernel is now implemented; see [stage 2 results](local-air-kernel-results.md). Runtime integration remains outstanding. This builds on the shared thermal worker/snapshot architecture. Shared rules remain in `../PROJECT.md`; the existing thermal design reference is read-only.

## Measured starting point

Input: Bright's `../../build/benchmarks/thermal/live-20261019-201256-762/summary.json` and `jobs.csv`. These were read as measurement data, not instructions. The full measured window lasted 60.0048 real seconds for 60 game seconds, with 1,200 thermal ticks and 124 completed measured jobs. At the end there were three world regions, totaling 110,293 cells, with no in-flight jobs and published ages of seven to eight ticks. Six jobs advanced ten simulated seconds, 115 advanced one second, and three were initialization-only jobs. No initial-settling jobs were captured.

| Measurement | Result | Interpretation |
| --- | ---: | --- |
| Main-thread thermal work | 8.46 ms per real second | Small average cost, concentrated in planning ticks |
| Main-thread thermal tick p99 / maximum | 11.09 / 23.83 ms | Not whole-server MSPT; individual planning spikes matter |
| Planning mean / maximum | 8.30 / 22.89 ms | Once-per-second work, includes geometry and cover |
| Worker wall time | 6.39 ms per real second | About 2.6% of the configured 250 ms/s wall-time accounting budget |
| Worker CPU time | 5.21 ms per real second | About 0.52% of one core; Windows counter resolution is coarse |
| Worker job wall mean / p95 | 3.09 / 10.46 ms | Mixed region sizes, step lengths and rebuild states |
| Queue wait p95 / maximum | 11.62 / 23.31 ms | Small compared with the one-second update cadence |
| Worker allocations | 2.90 MB per real second | Excludes server-thread geometry allocations |
| Geometry rebuild jobs | 93 / 124 (75%) | High rebuild frequency warrants investigation |

There were 116 geometry snapshots. Geometry preparation accounts for 85.6% of thermal server-thread wall time; cover alone accounts for 71.4%. Those stages are nested and must not be added. The current block-change hook marks every containing region's geometry dirty for any block-state update. The capture does not identify which blocks caused the invalidations. Source updates and geometry updates should be separated; invalidate airflow only when its physical geometry changes. Thermal material changes still invalidate thermal coefficients where necessary.

This is evidence of headroom in the measured scene, not a guarantee for many players, moving ships or many new airflow-only regions. The offline thermal baseline is retained under `../benchmarks/thermal-pre-flow-2026-10-19`.

## Proposed solver and ownership

Use a pure Java `FlowGrid` in Paradyne Lib alongside the existing `AirGrid`. Meteodyne owns world sampling, demand for regions, weather boundaries, optional ship adapters and consumer queries. Share server-side snapshots, worker capacity, budget accounting and immutable published results. Each mutable grid has a single worker owner.

Start with a three-dimensional, one-block grid. Store velocity components on cell faces (a staggered/MAC arrangement), pressure corrections at cell centers, and approximate open face areas plus air volume for partial blocks. Sharing each face flux between adjacent cells helps enforce balanced airflow and later thermal transport.

The geometry description must distinguish a block's solid material from the air around that material. A partial thermal solid cannot automatically become an entirely closed airflow cell. Closed doors, glass and full walls close the relevant connections; snow and slabs leave appropriate openings. Approximate fractional geometry at this resolution is not an exact simulation of every disconnected cavity within one block. Keep a clean extension point for improved geometry and material porosity.

Weather supplies incoming flow at open exterior boundaries. Solid surfaces prevent normal flow; exterior outflow must be allowed. Advance provisional velocity, solve a bounded pressure correction to reduce unbalanced cell flux, then publish the corrected velocity. Track pressure residual/divergence and iteration count, not just execution time. Sealed/disconnected air pockets need appropriate pressure references. Solver pressure is a local correction, separate from the atmospheric pressure used by weather/barometers.

Keep existing thermal source strength, material storage and temperature-offset semantics. The eventual thermal integration consumes local face fluxes instead of the current regional vector multiplied by scalar shelter. Review the existing empirical wind-loss/opening terms to avoid counting ventilation twice. Buoyant airflow is a later stage; it must replace or recalibrate the existing vertical drift/mixing approximation rather than simply stacking on top.

## Region and scheduling policy

Airflow demand can come from players and instruments even without a heat source. Reuse stable spatial regions and share overlapping demand instead of creating a complete independent grid per gauge. First prototype domains should contain the entire obstacle/building and exterior padding; cover 32-cubed and 48-cubed fixtures in benchmarks.

A pressure domain need not coincide with a heat-source cluster. The current thermal split may cut a building and fixes its outer cells to outside temperature. Reusing that edge as open airflow would create phantom ventilation. Before general deployment, define overlap/boundary exchange or larger shared flow domains spanning several thermal regions. An instrument should report pending/unavailable while a requested local field is being prepared, rather than silently reporting unsheltered wind inside a cabin.

Publish at the existing nearby one-second cadence initially. Airflow uses its own bounded numerical steps or relaxation policy; do not inherit thermal ten-second steps, 120-second catch-up or 1,500-second natural-source settling. Under load, cap work and report field age. Main-thread world access remains in snapshots; workers never access live Minecraft objects. Shared budgets must protect thermal progress from pressure jobs.

Cache geometry and pressure working arrays. Use separate invalidation for sources, thermal material coefficients and airflow openings. Include changes in the surrounding calculation margin. Ship pose/nearby external obstacle changes need their own invalidation, even when plot blocks do not change.

## First milestones

1. Implement the pure numerical kernel and deterministic fixtures with balanced-flow tests. Preserve the existing thermal-only benchmark and add flow-only plus combined costs on identical geometry.
2. Hook a bounded local world region into the shared worker infrastructure and publish wind for the gauge/debug view. Use real geometry with partial blocks. Leave thermal transport unchanged for this first integration so behavior/performance changes are attributable.
3. Feed the same local fluxes into thermal transport, checking the existing house calibration, conservation and boundary behavior.
4. Integrate ship-local grids and external cover. Keep ambient wind, ship-relative ventilation and the established wind-force gameplay rules explicit at their consumer boundaries.
5. Add temperature-driven buoyancy only after wind-driven ventilation is validated and its cost is measured.

## Acceptance and performance evidence

Required scenes: unobstructed uniform wind; thin snow; a single full block; a narrow row aligned with the wind; a broad wall; a roof; a doorway; a sealed room; and two adjacent domains. Check that walls have negligible normal flux, sealed rooms do not acquire exterior wind, partial blocks do not seal a voxel, and downstream flow can recover around isolated obstructions. Capture directional changes rather than merely reducing scalar speed. A first coarse solver need not reproduce realistic turbulent wakes.

Measure numerical solve CPU/wall time, pressure iterations/residual, allocations, snapshot/rebuild time, active cells/regions, queue wait and published age. Compare the same live scene with the pre-prototype capture. Set an explicit cost target after the pure-kernel measurements; unused worker budget is not permission to add server-thread spikes or leave fields stale. Record behavior and performance together before changing defaults.

Numerical reference: [GPU Gems 3, Real-Time Simulation and Rendering of 3D Fluids](https://developer.nvidia.com/gpugems/gpugems3/part-v-physics-simulation/chapter-30-real-time-simulation-and-rendering-3d-fluids), especially velocity/pressure projection, solid boundaries and staggered-grid discussion. Its GPU timings do not predict this Java CPU implementation.
