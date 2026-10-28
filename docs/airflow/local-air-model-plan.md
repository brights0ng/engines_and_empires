<!-- Written by ChatGPT. -->
# Local air model: implementation plan

Status: staged implementation, updated 2026-10-27. Bright selected the Boussinesq approach: approximately constant air density, temperature-driven buoyancy, and no simulation of compartment pressurization. Bright also selected a thermal-response speed control that leaves wind speeds and weather timing unchanged. Geometry caching and response control are implemented. The [pure airflow kernel and its comparison](local-air-kernel-results.md) implement stage 2, followed by the [measured pressure optimization](local-air-pressure-results.md). Stage 3 has a [shared-flux thermal foundation](local-air-thermal-transport-results.md), an [offline source/two-way coupling checkpoint](local-air-coupled-results.md), [thermal traversal optimization](local-air-thermal-optimization-results.md), [cached arithmetic](local-air-arithmetic-results.md), [faster wall-safe tracing](local-air-tracing-results.md), and [own-target calibration evidence](local-air-calibration-results.md). Bright accepted ending the performance-optimization pass; the original cost direction remains unmet and is retained for live measurements. The latest pressure-loop experiment stays off by default. The [timestep and finite-domain comparison](local-air-qualification-results.md) recommends 0.5 s for a prototype because coarse 1 s circulation cycles sharply diminish under temporal refinement. This recommendation does not change a production default or requalify the older 1 s settled calibration. The missing-wall padding-16/24 follow-up reduces the maximum local velocity difference to 0.00463 blocks/s; padding 16 is a provisional candidate for that house fixture, not a selected universal runtime radius. The [response-scale and elapsed-interval comparison](local-air-response-results.md) covers 0.1/0.5/1/2/10x at 0.5/0.25 s, with immutable chronological clock snapshots and bounded numerical catch-up controls. The [half-second settled calibration](local-air-refined-calibration-results.md) qualifies all three houses at 6,000 s, including strict field steadiness; the coupled rooms remain 0.92-1.41 C warmer than legacy with longer late cooling. The [boundary follow-up](local-air-boundary-results.md) adds explicit static-fluid heat storage/exchange, rejects omitted fluid material data, and preserves dry results. Calibration review, general topology/domain continuity, and representative live coverage/scheduling measurements remain gates; verification results are recorded at each checkpoint. The [world capture checkpoint](local-air-world-capture-results.md) now provides sliced, revision-checked solid/fluid snapshots and a closed diagnostic bake off the live path. The [coverage/handoff checkpoint](local-air-coverage-results.md) adds conservative open-sky evidence and revision-safe geometry publication components. The [shared preparation pipeline](local-air-pipeline-results.md) now connects explicit diagnostic requests to existing heat workers/snapshot budgets and conservative world invalidation. The [cold-start coupled diagnostic](local-air-runtime-results.md) adds static source capture, explicit uniform initialization, bounded chronological stepping, and immutable field publication for fixed-forcing requests. The [operator diagnostics checkpoint](local-air-diagnostics-results.md) adds explicit commands, point sampling, quality/freshness reporting, real headless server timing captures, and successful standalone/coreless verification. The [paced room trial and test isolation](local-air-scene-results.md) restore the aggregate check to 262/262 and exercise 1,008-cell door/source/open-wind phases. Available frames stay within 11 ticks, but first-field/rebuild delays take 6.45-17.25 seconds. The [retained-source checkpoint](local-air-source-refresh-results.md) now refreshes verified campfire lit/unlit changes without geometry rebuilds, preserving heat/flow/energy and qualifying actual cooling. Its isolated paced source-off field returns in 3 ticks. The [door topology-transfer checkpoint](local-air-geometry-transfer-results.md) now seals old work at the edit tick, remaps retained air/material warmth and compatible flux, reprojects, and catches up through preparation time. Supported door OPEN changes retain cumulative history; unknown/material/chunk edits still cold-start. Door reconstruction remains about 6.45 seconds in the paced room, with an initially edit-tick-old field. The [dependency invalidation checkpoint](local-air-dependency-results.md) now protects domain cells and recorded shape/sky/chunk reads while preserving fields across unrelated edits. The [repeated-edit checkpoint](local-air-history-results.md) now retains bounded intermediate door topologies and source events using historical world views and sequential edit-time transfers. The [incremental recapture checkpoint](local-air-recapture-results.md) now reuses cells with unchanged recorded read dependencies, reducing the paired paced door first-field delay from 6.65/6.50 s to 0.25 s. New domains and open-sky evidence still require full capture. The [live-weather checkpoint](local-air-weather-results.md) now adds opt-in center-uniform climate and surface-wind snapshots with retained heat and chronological weather/source/topology replay. The [paced live-weather trial](local-air-weather-scene-results.md) now passes retained-heat, ventilation, source/door and energy checks; its gust-only control delivers fields on 90% of ticks. Open-building door edits still wait about eight seconds for 1,240 sky-evidence samples per transition despite incremental domain snapshots. The [coverage-proof reuse checkpoint](local-air-coverage-reuse-results.md) now preserves guarded column evidence across supported edits: the repeated paced opening/closing field delay falls to six ticks (0.30 s), all four transitions reuse 4,960 sky positions, and the full check passes 263 aggregate / 213 coreless / 98 Meteodyne tests. New/uncertain domains still require full coverage capture. The [consumer query contract](local-air-query-contract.md) now exposes current/stale/pending/unavailable point readings with caller-selected age limits and existing revision guards. The [view and held thermometer trial](local-air-view-trial.md) adds player-scoped sparse arrows, temperature colors, quality/readout feedback, and an explicitly opted-in held thermometer. Client visual/gameplay review and remaining instrument semantics are the next integration gate. Arbitrary block-topology transfer, broader consumer integration, and production activation remain unimplemented; the later stage 5 checkpoints below add compatible layout continuity. Numerical/performance targets below remain acceptance directions rather than claims about live airflow.

This supersedes the staging in [airflow-prototype.md](airflow-prototype.md): buoyancy belongs in the first usable coupled prototype. Shared project rules remain in [PROJECT.md](../PROJECT.md); existing files in `../design` remain read-only references.

The [automatic demand registry](local-air-demand-registry-results.md) begins stage 5: players, loaded placed-source candidates, and placed temperature/wind sensors now produce shared required sample cells with lifecycle tracking and bounded grace retention. Held instruments reuse player demand. The [automatic tile planner](local-air-planner-results.md) now continuously groups those requests into stable tile proposals under explicit experimental limits, deferring whole groups for missing chunks or capacity and bounding planning work. These proposals do not yet allocate solver state or establish exterior coverage. Geometry/coverage admission, connected-domain continuity, and production consumer activation remain next.

The [shared tile interface checkpoint](local-air-tile-interface-results.md) assembles complete rectangular tile groups before building the common pressure/thermal geometry. Two-tile seam fixtures, including 16-cubed tiles, match the independently constructed dense control. Its pure assembler rejects irregular/partial-height unions; live admission and ownership are provided by the later runtime checkpoint below.

The [automatic runtime checkpoint](local-air-automatic-runtime-results.md) admits compatible rectangular planner groups through live capture and conservative coverage checks, then runs one coupled owner per group under the existing shared budgets. Actual node/face allocation is guarded. The trial is opt-in with explicit limits and a separate point-query API; production consumers and the manual viewer are unchanged. Its initial retained-layout deferral is extended by the transfer checkpoint below; irregular/partial-height groups, general forcing, persistence and production activation are still future gates.

The [layout transfer checkpoint](local-air-layout-transfer-results.md) transfers compatible rectangular resize/merge/split cohorts at a common tick, retaining overlapping air/material/fluid warmth and projecting replacement flow before atomic ownership commit. Volume entering/leaving coverage is explicitly accounted; removed capacity is not yet persisted. Invalidated/failed replacements cancel without partial publication. Temporary buffers must fit the configured cell allowance.

The [sparse-domain checkpoint](local-air-sparse-results.md) now executes irregular unions of full 16-block tiles with compact actual-cell capture, geometry and numerical storage. Concave cuts require the same exterior/ground evidence as outer boundaries, holes return no reading, and dense/sparse resize transfers retain overlapping warmth. The live L-shaped fixture uses 12,288 cells under an exact cell allowance instead of filling its 16,384-cell envelope. Partial-height tiles, driven forcing, natural-source initialization, thermal persistence, production consumers and default activation remain outstanding.

The [live driven-source checkpoint](local-air-driven-source-results.md) now samples registered block-entity power through the existing capture allowance and sends immutable chronological heat/cold terms to workers. Power-only changes retain geometry and stored warmth, including across supported door/layout barriers. The legacy strictest-net-direction thermostat law is preserved, including its behavior with opposing limits. Natural-source initialization, thermal persistence, production consumers, general topology support, overload qualification and default activation remain outstanding.

The [thermal initialization and persistence checkpoint](local-air-thermal-persistence-results.md) now seeds automatic domains from compatible saved per-phase state, legacy saves, or immutable legacy thermal estimates. Natural-only state remains transient, mixed/placed state persists through chunk checkpoints and removed coverage, and incoming layout cells can recover their saved warmth. The natural estimate is not yet production-qualified: a small-house comparison is 4.59 C below the coupled steady room mean, addressed by Bright's requested refinement checkpoint below. General topology support, checkpoint/overload qualification, production consumers and default activation remain outstanding.

The [natural initialization refinement checkpoint](local-air-initialization-refinement-results.md), requested by Bright after reviewing the 4.59 C mean gap, now refines fresh natural coverage with the new model's thermal/flow operators before publication. The original fixture's maximum room-cell error is about 0.000001 C in the measured run; 16 heating/cooling wind/fluid cases remain within 0.01 C of independent controls. Shared sliced work, explicit stationary/thermal-window qualification, preserved saved/donor states and chronological preparation edits are implemented. Representative arrival latency, larger-scene accuracy and overload qualification remain rollout gates.

The [static block-edit continuity checkpoint](local-air-block-edit-results.md) now extends chronological retained topology handoffs beyond door toggles. Ordinary static placement/removal, partial-state edits and fluid occupancy changes preserve unaffected warmth, retire replaced phase ownership explicitly and reproject before publication. Same-tick replacement cannot revive removed storage. Driven-candidate membership changes, unknown/chunk/context edits, checkpoint/overload qualification and production consumer/default activation remain outstanding.

The [startup timing and retained preparation checkpoint](local-air-startup-results.md), requested by Bright after observing 5-10 seconds of live startup, now reports request phases, worker and collection waits, first publication and catch-up. Prepaid worker continuations reduce a controlled paced natural fixture from 8.56 to 2.82 seconds without increasing its shared allowance. Automatic layout targets reuse guarded donor geometry, sky proofs and thermal inputs; the live growth fixture reads only newly represented thermal cells. First-ever manual capture, sustained movement/multiplayer capacity, larger-scene natural initialization, overload and default activation remain unqualified.

The [automatic viewer and held thermometer checkpoint](local-air-automatic-view-results.md) now lets an operator explicitly follow automatic domains with the existing view and held instrument. Readings and graphics share one guarded committed field; replacement targets stay hidden, world-aligned markers survive resizing, and ownership/freshness metadata clears incompatible client history. Manual diagnostics remain selectable. This trial adds consumer/lifecycle verification, not production player-climate or placed-sensor activation; wind-gauge/redstone decisions and earlier rollout/performance gates remain open.

The [coverage recovery checkpoint](local-air-coverage-recovery-results.md), requested after the automatic view spent roughly fifty seconds proving an obstructed boundary, now interleaves sky checks and reports the obstructing block/cut. Automatic proposals can expand by adjacent tiles and merge under existing admission, planner and runtime limits. Healthy donors remain available during blocked/rejected replacement recovery, and trial status identifies the selected automatic runtime. First-time clear sky proof, sustained movement/multiplayer capacity and performance/default-activation gates remain open.

The [shared sky index checkpoint](local-air-sky-index-results.md) now shares clear suffixes and obstruction thresholds across live capture owners, bulk-certifies supported empty sections and invalidates cached geometry/context chunks independently of domain lifetime. A real tall-column proof uses 251 scalar samples plus 24 section checks for 7,979 sky positions; a replacement owner uses no new world reads. Historical views retain chronological capture. This reduces evidence work without changing admission or selecting a player-arrival latency guarantee.

The [viewer publication timing checkpoint](local-air-view-publication-results.md) now retries guarded fields each tick while pacing complete lattices independently of pending notices. It fixes aligned weather/view clocks that could display pending indefinitely despite a running field, without relaxing publication or freshness checks. A delayed-worker regression and a live-provider GameTest cover current delivery between refreshes and immediate geometry withdrawal.

## 1. Intended behavior and scope

Build one local air system with temperature, three-dimensional velocity, and a pressure correction on shared physical geometry. Separate numerical components can own those fields, but they advance as one coupled model on the existing thermal workers.

- Weather drives background wind at the exterior of a local domain. Buildings and terrain redirect it through connected air space.
- Warm air rises, cold air sinks, and the pressure solve produces replacement/return flow. A hot room does not pump air through a closed wall.
- The same corrected airflow carries heat. Opening a door can create a draft and change the room's temperature distribution.
- Thin snow reduces the available space instead of closing an entire block. A small obstacle creates a local sheltered wake, with wind able to pass around it and recover downstream.
- Thermal source definitions, limits, radiant heat, insulation data, and stored wall heat retain their existing meanings. Preserve the established house-warming calibration unless Bright explicitly selects a change after comparison.
- Players and instruments can request local airflow without a nearby heat source. A source-only region system is insufficient.

Initial exclusions: acoustic waves, pressure vessels, air quantity/oxygen consumption, humidity transport, combustion chemistry, realistic turbulence, erosion, and weather generation. Do not add particle simulation, a GPU requirement, or a global atmosphere grid. Humidity could become another transported scalar later, but adds no cost now.

Preserve the current Overworld scope initially. An unavailable weather-wind provider gives a calm exterior, not a disabled buoyancy solver: a campfire can still drive local circulation. Player/instrument demand requests a field; it does not make players or instruments heat sources.

The local pressure correction routes airflow; it is not the atmospheric pressure a future barometer should display. Background weather pressure remains a distinct input. Constant density also means this model does not provide hot-air-balloon lift or aerostat gas physics automatically.

## 2. What the current implementation tells us

Relevant implementation anchors:

| Existing component | Keep or change |
| --- | --- |
| `paradyne-lib/.../heat/air/AirGrid.java` | Retain source/material semantics and the thermal-only control; add a coupled transport path rather than layering another convection model over it |
| `AirParams.java` | Preserve calibration data; separate legacy convection coefficients from parameters used by the new model |
| `meteodyne/.../heat/world/HeatWorld.java` | Reuse snapshots, worker ownership, budget accounting, completion collection, and publication patterns |
| `HeatRegion.java` | Extend geometry/result contracts; source-cluster edges cannot automatically become airflow outlets |
| `HeatTables.java` | Reuse material lookup; replace binary airflow openings with a geometry description that handles partial air space |
| `HeatSettings.java`, `rain/WeatherConfig.java` | Keep existing config keys compatible; add response speed and experimental airflow controls |
| Optional ship heat adapter | Reuse frame transforms and sampling boundaries; keep outside-mod imports gated |

The present thermal solver uses one temperature value per block, a regional horizontal wind times a scalar shelter share, and heuristic upward/downward heat drift. It does not solve local air velocity or pressure. A partial solid's temperature represents its material, so its empty space cannot simply reuse that same value as an air temperature.

Nearby thermal jobs normally run once per second; distant jobs can advance ten seconds at once. Thermal catch-up can reach 120 seconds, and natural-source initialization can settle 1,500 seconds. These shortcuts must not become a single airflow step.

Bright's 60-second live capture had 110,293 cells in three regions at the end. It measured 5.21 ms of worker CPU and 8.46 ms of thermal game-thread work per real second. Geometry preparation represented 85.6% of that game-thread work, with 93 of 124 measured worker jobs receiving rebuilt geometry. These are nested measurements, not additive costs. The capture does not identify the invalidating blocks.

Consequently, reducing geometry churn is part of the prototype, not a later optimization. The measured worker headroom is encouraging but does not account for the additional source-free regions this feature needs. See [thermal-baseline-results.md](thermal-baseline-results.md), [thermal-performance.md](thermal-performance.md), and the original capture for measurement boundaries.

## 3. Physical model

Use an incompressible Boussinesq approximation: constant reference density in transport and pressure projection, with temperature differences contributing a buoyant acceleration. This is a deliberate local gameplay approximation, especially near very hot sources. The approximation's physical motivation is described by [COMSOL's Boussinesq reference](https://www.comsol.com/multiphysics/boussinesq-approximation).

Conceptually:

```text
velocity change = transport + buoyancy - pressure correction + modest damping
net air volume entering each air control volume = net volume leaving it
temperature change = sources + wall exchange + mixing + transport by that velocity
buoyancy acceleration = buoyancyScale * g * (Tair - Tbackground) / TbackgroundKelvin
```

Use the actual background air temperature, without wind chill, sunlight felt by a player, or direct radiant warmth. Average the appropriate neighboring air temperatures when applying buoyancy to vertical faces. Positive differences accelerate upward; negative differences accelerate downward. Hydrostatic background balance is removed from the local pressure equation.

Use a reference temperature appropriate to the local domain and a fixed reference density; do not recompute a variable-density pressure matrix each temperature update. Snapshot the background temperature/altitude profile and weather vector separately from local disturbances. Transport air temperature consistently, then derive the published offset relative to background. An ambient-reference change must not reset room/wall temperature or create source energy.

Keep units consistent with existing instrument wind: one block is treated as one meter, velocity is blocks per game second, and temperature differences in Celsius equal differences in Kelvin. Start with `g = 9.81 m/s²` and the separate buoyancy multiplier. Store kinematic pressure (pressure correction divided by reference density) if that removes unnecessary density factors; do not label this field as atmospheric hPa in debugging.

For the first local model, omit adiabatic expansion/cooling. Do not claim that a plume followed over hundreds of blocks reproduces atmospheric stability. Test the chosen background-profile treatment in an unheated domain so it does not generate artificial circulation. Large-scale temperature, altitude cooling, pressure, and wind remain owned by the weather system.

Use a separate buoyancy-strength parameter for gameplay calibration. Changing thermal response speed must not implicitly multiply gravity, buoyancy acceleration at a given temperature, or reported m/s. Bound unsupported temperature/velocity extremes explicitly, record when bounds activate, and review them against lava/fire fixtures; silent clipping must not hide instability.

## 4. Shared geometry and state

### Resolution and layout

Start at one block per spatial cell, with a staggered/MAC arrangement: velocity components on faces, air temperature and pressure correction in air control volumes. Each shared face has one open area and one signed volume flux. Temperature transport and pressure projection consume that same flux.

Use flat primitive arrays and cached indices, not an object per cell or face. Cache masks, face areas, component connectivity, pressure coefficients/preconditioner, and solver work arrays. Reuse scratch storage between jobs. Float storage is the initial candidate for fields, with double reductions/diagnostics where needed; conservation and convergence tests decide whether pressure vectors also need doubles.

### Partial blocks and connectivity

The geometry contract records:

- Accessible air volume and the patches through which neighboring air volumes connect.
- Shared open face area, computed from overlapping free patches on both sides, not just the smaller of two unrelated percentages.
- Solid volume/material capacity and air-to-solid exchange surfaces.
- Fluid occupancy and a geometry revision independent of source state.

Provide fast paths for completely empty and completely full cells. Bake common partial shapes once using subcell geometry analysis; those subcells are not a finer dynamic fluid grid. Cache context-independent shapes by state. Snapshot context-sensitive shapes in the world context and invalidate them with their relevant neighbors.

One air node per block is the common case. Thin internal partitions can split the remaining air into disconnected pieces; use separate local air components where needed, or an equivalent connectivity-preserving representation. A closed door/trapdoor must not leak merely because its two exterior faces both contain some air. Add this fixture before enabling general partial-block flow.

Do not adopt a rule that every non-full block is transparent: a thin closed door can seal an opening. Conversely, a thin snow layer must leave the air above it connected. Water/lava occupy their actual sampled fluid space; no fluid dynamics are added. Check waterlogged shapes and source-block interfaces explicitly.

Small air volumes must not force thousands of explicit steps. Use the flux-form pressure system and implicit thermal transport described below. If tiny connected cavities require merging for conditioning, preserve their shared fluxes/energy and measure the approximation; do not close visible gaps with a large numerical epsilon.

### Air and material temperature

Give air and solid material distinct thermal state wherever both exist in a cell. Reuse existing material/source tables and full-solid storage. Start with straightforward primitive storage; compress rare partial-cell state only if memory profiling justifies it.

Sources inject their existing air power into connected nearby air volumes and retain thermostat limits. Air exchanges heat with solid surfaces; solid nodes exchange heat with adjacent solid material and retain thermal mass. A source block does not become a through-hole simply because it emits heat.

### Implementation boundaries

Working names below describe responsibilities; they are not public API commitments or instructions to rename existing paths.

| Layer | Proposed responsibility |
| --- | --- |
| Lib `FlowGeometry` | Immutable air components, shared faces, material interfaces, and revisioned topology; no Minecraft types |
| Lib `FlowGrid` / `PressureProjector` | Worker-owned velocity, pressure, transport scratch, cached operator, and numerical diagnostics |
| Lib coupled thermal state/transport | Air/material temperatures and implicit shared-flux heat update; reuse the existing material/source laws through tested shared helpers |
| Lib `CoupledAirGrid` | Advance the coupled sequence with settings and prepared inputs; return publication data/quality without scheduling threads |
| Meteodyne `HeatTables` and geometry preparation | Bake block/fluid shapes and material data, with separate invalidation/cache keys |
| Meteodyne `HeatWorld` / `HeatRegion` | Extend existing planning, worker dispatch, budgets, prepared geometry, persistence adapters, and published state; do not start a competing simulation manager |
| Meteodyne local-air query/demand API | One consumer entry point plus request sharing, coverage/freshness, and world/ship frame selection |
| Existing benchmark/debug/instrument adapters | Add phase/quality metrics and local-air readings while retaining the thermal-only control and accepted instrument interaction |

Keep `AirGrid` available as the legacy thermal reference throughout the experiment. Extract shared laws only where that avoids duplicated behavior, retaining original authorship and existing tests. Avoid a large package move or unrelated subsystem rewrite as part of the prototype.

## 5. Numerical update

Use an operator-split update with first-order methods initially. The staggered-grid/projection pattern is established in [GPU Gems 3, chapter 30](https://developer.nvidia.com/gpugems/gpugems3/part-v-physics-simulation/chapter-30-real-time-simulation-and-rendering-3d-fluids). Its GPU performance figures do not predict this Java CPU implementation.

For each bounded physical airflow interval:

1. Apply prepared geometry/source/weather changes, preserving compatible state.
2. Advance air/material temperature for half the thermal interval using the last corrected fluxes.
3. Advect velocity with a first-order semi-Lagrangian method, apply buoyancy using the updated temperature, and apply modest surface/volume damping.
4. Enforce solid boundary velocities and exterior conditions, solve the pressure correction, and project face velocities.
5. Advance temperature for the remaining half interval using the new corrected fluxes.
6. Validate finite values, transport balance, pressure convergence, and source limits. Publish only a complete usable result.

The half thermal steps reduce coupling lag; this is not a claim of second-order accuracy. Revisit their extra thermal cost after benchmarking. Retain a single-step reference implementation for comparison.

### Velocity transport and wall conditions

Semi-Lagrangian velocity transport avoids an explicit advection stability restriction, but large steps still lose accuracy. Trace paths through geometry, stop at closed faces, and restrict interpolation to the connected air component. Merely clamping the trace endpoint is insufficient: a long trace must not jump through a wall.

Use no normal flow through stationary solids, with tangential slip and bounded damping initially. Do not attempt viscous boundary layers at block resolution. Surface drag/mixing can provide coarse resistance in narrow passages without solving microscopic viscosity. Do not add artificial vorticity or turbulent gusts in the first prototype.

### Pressure projection

Use preconditioned conjugate gradients on the cached shared-face operator. The initial face-wise matrix-free implementation used diagonal preconditioning and warm starts. The [measured pressure optimization](local-air-pressure-results.md) now caches sparse rows and a modified incomplete-Cholesky factor after the baseline showed poor convergence. It preserves the same operator and acceptance tolerances while trading additional cache memory for fewer iterations. Retain the diagonal control; avoid committing to many fixed Jacobi sweeps without residual evidence. [Bridson and Mueller-Fischer's course notes](https://www.cs.ubc.ca/~rbridson/fluidsimulation/fluids_notes.pdf), chapter 4, provide a primary numerical reference.

Build the symmetric flux-form operator from open face areas and distances. Do not divide each pressure row by a different partial-cell volume and then assume ordinary CG still sees a symmetric matrix. Track each disconnected component: sealed components require a pressure reference/null-space treatment and compatible net flux; empty/isolated components must not produce zero-diagonal failures.

Stop on both relative and absolute criteria, with an iteration/work cap. A candidate cap is 64 iterations per projection, subject to measurement. Record initial/final residual, RMS/maximum volume-normalized divergence, iteration count, and boundary flux imbalance. Choose tolerances from uniform-temperature drift and ventilation tests, not just visually plausible vectors.

If convergence fails, continue within a bounded work allowance or retain a compatible last-valid result and mark it stale. Do not advertise an unconverged projection as ready. If geometry changed, old flow through newly closed faces is invalid: withdraw the affected result and use safe no-ventilation transport while preparing a valid replacement. Consumers must distinguish that temporary condition from measured calm air.

### Thermal transport and existing approximations

Use conservative, implicit upwind heat transport on the shared face fluxes, coupled with existing source limits and material exchange. The implicit path suits the current long thermal updates and avoids an explicit small-volume CFL restriction. Still measure numerical diffusion and convergence at high response scales and wind speeds; stability alone is insufficient.

Account for internal energy exchange in opposite pairs. Test conservation in insulated closed fixtures and account explicitly for exterior fluxes, sources, sinks, and thermostat clamping. Test uniform-temperature preservation under projected flow. Do not use a generic temperature clamp to conceal transport error.

In the coupled path:

| Current mechanism | Treatment |
| --- | --- |
| Regional wind multiplied by scalar shelter | Replace with local projected face fluxes |
| `rise`/`riseMax` and asymmetric vertical convection | Disable; buoyancy plus transport replaces them |
| `openingMix` stack-effect boost | Remove its ventilation role; explicit flow handles openings |
| `exposedWind` empirical heat removal | Disable where explicit ventilation already removes heat |
| General air mixing | Retain a small calibrated unresolved-mixing term; avoid erasing thermal layering |
| `exposedLoss` everywhere above a heightmap | Review against domain size; prefer actual exterior exchange, with any remaining unresolved loss documented and calibrated |
| Wall storage, conduction, source power/limits, direct radiation | Preserve their meaning and verify existing behavior |

Keep the legacy thermal path intact as an experimental comparison mode. Do not silently remove assertions from its tests when developing the coupled path. New behavior/calibration differences require evidence and Bright's decision before replacing accepted defaults.

## 6. Gameplay speed, cadence, and CPU controls

Bright selected a thermal-response control, separate from airflow time and weather time. Proposed key: `heat.responseScale`, default `1.0`, initial supported range `0.1` to `10.0`.

```text
thermal interval = elapsed game seconds * heat.responseScale
airflow interval = elapsed game seconds
```

Scale all thermal evolution consistently: source heating/cooling, air/material exchange, conduction, storage response, ventilation transport, and residual thermal losses. Increasing only source power would change how hot a room eventually gets; that is not this control. Leave thermostat setpoints, instantaneous radiation, fuel consumption, redstone sampling, player damage timers, weather movement, and displayed wind units unchanged.

For a fixed airflow field and constant sources/boundaries, scaling the complete thermal equation changes the approach to equilibrium rather than the equilibrium. In a coupled run, the transient airflow can also change because temperatures evolve sooner. This is an intentional gameplay time adjustment: heat transport need not traverse the world at the displayed m/s when the scale differs from one. Explain that honestly in developer/config documentation.

Examples: `0.5` targets roughly twice the heating/cooling response time; `2.0` targets roughly half. Those ratios are approximate once airflow feedback, thermostat transitions, weather changes, or fuel depletion enter the problem. Test exact time-rescaling behavior with prescribed flow before testing the coupled cases.

Changing the scale at runtime preserves current temperatures and velocity; it changes subsequent rates. Reject non-finite/unsupported values. Zero is not a hidden pause mode. Provide config reload support and an operator-only temporary tuning override with an explicit reset/status display; finalize command syntax during implementation without renaming existing commands.

| Control | Purpose | Starting proposal |
| --- | --- | --- |
| `heat.responseScale` | How quickly thermal state responds | 1.0; Bright selected this behavior |
| Existing `heat.nearStepSeconds` | Nearby publication/update target | Keep 1 s default initially |
| Existing `heat.farStepSeconds` | Distant thermal update target | Keep 10 s thermal default |
| Existing `heat.budgetCores` / `heat.threads` | Shared thermal + airflow worker allowance | Keep 0.25 / 1 defaults; update descriptions to reflect shared work |
| New experimental airflow enable flag | A/B testing and safe rollout | Off until acceptance gates pass |
| New buoyancy strength | Tune draft strength at the same temperature | 1.0 initial coefficient; calibrate in fixtures |
| Internal airflow maximum step/work cap | Numerical accuracy and bounded latency | Begin comparisons at 0.25, 0.5, and 1 s; select using cost and behavior |
| Active-cell/memory allowance | Bound expansion from players/instruments | Instrument first; select a default from multi-demand captures |

Scheduling more frequently is not a substitute for the response-speed control. CPU pressure must not silently modify the configured response scale.

## 7. Spatial domains and activation

### Prototype domain

Use explicit bounded test domains containing the entire fixture plus outside padding. Benchmark 32-cubed and 48-cubed volumes at the same block resolution. Start with eight blocks of padding as a test value, then double it to check whether the measured interior/wake changes materially. A 50-block obstacle fixture necessarily needs a longer domain.

A full building must not be cut by a prototype boundary and interpreted as ventilated. Report insufficient coverage when a fixture exceeds the configured supported domain instead of presenting a misleading result.

### Runtime domains

Demand comes from nearby players, loaded placed instruments, compatible ships, and existing thermal sources. Multiple requests share work. Held instruments use the player's existing demand; placed instruments must not cause chunk loading or each allocate a grid. Retain an inactivity grace period to avoid rebuilding domains whenever players cross a cell boundary.

Use stable spatial tiles as allocation/planning units, initially proposing 16-cubed blocks. Tiles do not lower physical resolution and are not separate atmospheres. Include a calculation margin outside the area promised to consumers.

All touching active tiles in one local solve share their interface faces and one pressure operator. Run CG across those tiles as one domain; an internal tile face must never receive an outside-temperature/zero-pressure condition. Connected active groups can run independently only where there is no shared simulated interface. Initially prove this contract with two tiles before optimizing tiled storage.

Dense arrays are sufficient for the fixed-domain kernel. Add tiled storage/iteration when runtime demand needs irregular unions; avoid a large mostly empty bounding box connecting distant players. Cache cross-tile face mapping rather than using hash lookup per solver cell. Budget domains using estimated work, fluid cells, and bytes, rather than axis length alone.

A finite local model still needs exterior boundaries. Prefer growing coverage around enclosed air touched by demand, within a bounded search/admission budget. Where a building/air passage continues beyond coverage, mark boundary confidence/coverage explicitly; do not turn a known interior cut into fresh outdoor air. Full treatment of arbitrarily large connected caves/buildings is outside a finite local guarantee. Establish supported range and overflow behavior before enabling the model by default.

### Exterior conditions

Sample weather through the existing surface/aloft blending API at the appropriate heights. The current world heat job's surface vector at its center is insufficient for a tall domain. Snapshot a compact height profile or corner samples; do not query the weather provider once per fluid cell.

At genuine exterior inflow, prescribe background wind and incoming temperature. At outflow, allow flow to leave using compatible pressure/open conditions. Calm exteriors must allow a buoyant plume to exit and replacement air to enter. Do not prescribe velocity and pressure incompatibly on every boundary face. Do not inject outdoor wind into every roofed or sky-exposed interior cell.

Missing chunks do not trigger loads. Distinguish missing geometry from known empty space; exclude uncertain boundaries from a ready shelter result. Chunk arrival/unload invalidates neighboring calculation margins. Open-water behavior remains an air/solid-fluid-boundary approximation.

## 8. Threading, budgets, and overload behavior

Retain main-thread snapshots, a shared low-priority worker pool, one mutable owner per domain, and immutable publications. Workers receive primitive prepared data only; no live world, block entity, chunk, or ship objects. Do not introduce a thread per domain or instrument.

Apply one shared server allowance across world and ship work, rather than multiplying the configured allowance for every domain. Preserve the distinction between measured worker CPU and the existing wall-time budget accounting.

Snapshot work is queued/coalesced and spread over ticks. Add separate revisions for geometry, material coefficients, sources, atmospheric inputs, and ship pose/external obstacles. Source-only changes do not rebuild a pressure matrix or rescan shelter. Block changes update affected cells/faces/neighbors and topology; invalidate expensive component/preconditioner data only where needed. Include the calculation margin, not just a region's published interior. A snapshot assembled over several ticks must reconcile intervening dirty cells before submission, rather than claiming a coherent revision for mixed old/new geometry.

Version prepared work and its resulting publication. Discard results for removed domains or superseded incompatible geometry. One publication pairs compatible temperature and velocity fields with geometry revision, game timestamp, actual flow progress, and quality/freshness metadata. Readers never wait for workers.

Bound worker slices at numerical stage/iteration boundaries so one difficult pressure solve cannot monopolize the pool. The existing accounting debits completed job wall time and is an average allowance, not a hard execution deadline. Check allowances during long work; amortize timer checks outside inner cell loops. Keep a per-job work cap and instrument actual slice duration.

Prioritize active nearby thermal/airflow results, then loaded sensor requests and retained distant work, with starvation prevention. Increasing the number of flow requests must not starve existing thermal updates. Warm-start slow-changing fields and sleep settled domains until sources, boundaries, geometry, or demand changes; sleeping retains a valid result rather than repeatedly solving unchanged calm air.

Under pressure:

1. Reuse compatible fields and coalesce requests instead of queueing duplicates.
2. Spend less work on distant domains and delay admission of new distant demand.
3. Preserve thermal elapsed-time accounting using the implicit thermal path with the latest compatible fluxes, while explicitly marking flow quality/age.
4. Bound airflow backlog and rebuild/warm-start from current conditions when far behind; do not replay 120 seconds of momentum or run 1,500 seconds of flow settling in one job.
5. Surface stale/pending/unavailable states and telemetry. Do not silently report raw outdoor wind inside a room whose local solve is missing.

If a safety/work cap reduces transient accuracy, report it. Do not silently change the thermal-response scale, close partial openings, reduce spatial resolution through doors, or publish a divergent field to meet a timing target.

Initially copy publication arrays at the nearby cadence, as the existing thermal system does. Never reuse a published buffer while readers can access it. Optimize publication storage only after measuring allocations; a buffer pool needs a proven reader-lifetime protocol.

## 9. Consumers, instruments, and ships

Provide a shared three-dimensional local-air query with temperature, velocity, validity/coverage, and age. Ambient weather and apparent ship-relative airflow remain separate concepts. Sample using geometry-aware interpolation so a query cannot blend through a wall into a different room.

Wind-gauge display decision is pending Bright's answer: recommended behavior is local horizontal flow, with the existing downwind arrow and cups both derived from that horizontal vector. Vertical flow is available in the model/debug view. If Bright selects total speed, update packet/state semantics and explain the vertical contribution in the UI before implementation. Do not change the accepted arrow direction, cup design, mount, range GUI, or anti-reequip-bob behavior.

The placed sensor's redstone uses the same speed quantity as its cups and configured range. A pending local field is not a zero-wind measurement. Preserve the last valid indication briefly when compatible, then use an explicit unavailable state; choose redstone handling with Bright when implementing this state rather than letting a default zero silently act as a measurement.

Thermometers consume the same air temperature as player climate sampling. Radiant warmth stays a separate felt-temperature term. Migrate wind-chill/exposure consumers to the shared field after comparison tests; do not multiply the new vector by old scalar shelter again. Update consumers in explicit milestones so the prototype does not quietly change ship forces.

For optional ships:

- Keep stable geometry in plot coordinates; transform world gravity into that frame for buoyancy. Plot-local positive Y is not necessarily world up.
- Derive ventilation boundaries from ambient/local world flow minus ship point velocity, including rotation where relevant. Transform vectors consistently.
- Preserve current gauge semantics concerning observer/ship movement unless Bright selects an apparent-wind instrument. A moving ship must not make an ambient wind gauge report its own speed accidentally.
- External hangar/terrain geometry and ship pose affect boundary conditions even when no plot block changes. Do not double-count the ship's own obstruction or apply the legacy shelter factor again.
- Maintain one-way exterior-to-plot boundary coupling initially. Detailed two-way ship wakes, swept world geometry, and interaction of neighboring moving ships require a separate measured extension.
- Existing ship-force rules remain an explicit integration boundary: wind pushes, does not add braking from the object's own speed, and does not add a second drag implementation. Feeding new thermal updrafts into lift/forces requires Bright's gameplay decision and separate tests.

Keep optional outside-mod types behind existing loaded-mod gates. Paradyne Lib's numerical code must not import Meteodyne, Minecraft, or optional ship APIs.

## 10. Persistence and transitions

Persist thermal state, not pressure work arrays. Reconstruct velocity from compatible cached state or a bounded warm-up; pressure has no absolute saved meaning. Save format changes for partial air/material state need explicit versioning and old-save migration tests. Existing temperature offsets must load without erasing retained room/wall warmth.

Preserve the accepted natural-source arrival behavior: a lava cave should already be hot when a player arrives. For the prototype, use the existing thermal-only natural-source settling path as an initial-temperature estimate, then warm-start and reconcile the coupled field in bounded background work. Do not run 1,500 seconds of coupled momentum to initialize it. Record initialization error against an offline coupled steady fixture; if the estimate visibly fails, bring Bright a measured choice between cached/prepared coupled state and a different initialization approximation. Natural-only thermal state remains transient; mixed placed/natural regions keep their established persistence policy.

When resizing/merging/splitting domains, transfer overlapping temperature and compatible face velocities with explicit ownership. Reproject after topology changes. Do not duplicate source energy, average unrelated air components, or let two domains both own the same boundary flux. Account for energy entering/leaving the represented area when geometry or coverage changes.

Keep an experimental mode switch for comparison. Document which changes require reconstruction and which can reload live. Disabling the prototype must restore the established thermal path without leaving background workers or consumer requests behind.

## 11. Performance targets and evidence

These are proposed engineering targets for choosing an implementation, not performance claims:

| Target | Initial acceptance direction |
| --- | --- |
| Worker cost, same ~110k-cell live scene | Aim for combined thermal + airflow at or below 100 ms CPU per real second; retain the shared 0.25 wall-core allowance and measure both |
| Game-thread snapshots/planning | Aim below the measured 8.46 ms/s average, with routine prepared-work slices targeting <=2 ms per tick; record exceptions/startup separately |
| Nearby publication freshness | Target a result within two game seconds in the agreed reference scene; report queue age and actual flow progress separately |
| Geometry churn | Stable geometry produces no pressure topology rebuilds; source-only toggles do not trigger geometry scans |
| Allocation | No allocation per cell in numerical loops; retain buffers and measure main-thread plus worker allocation and publication bytes |
| Numerical quality | Meet behavior/conservation/convergence fixtures at the selected workload; a cheap invalid result does not count as a pass |
| Scaling | Record total active/air cells, domain count, geometry revisions, memory, work per update, and number of demands sharing each domain |

The 100 ms/s target is approximately 10% of one core and roughly 19 times the measured thermal worker CPU rate. It is a target for this reference hardware/scene, not a multiplayer capacity promise. Windows CPU counters are coarse; retain warmed batches, multiple runs, and wall-time measurements. Confirm server MSPT/FPS separately because the current thermal capture does not measure whole-game performance.

Extend the existing benchmark rather than replacing its baseline:

- Preserve thermal-only runs and their geometry fingerprints.
- Add wind-only, buoyancy-only, and coupled runs on identical fixed fixtures.
- Separate geometry preparation, coefficient rebuild, velocity transport, pressure, thermal transport, publication, queueing, and initialization.
- Report pressure iterations/residual and transport quality alongside timings; compare equal accuracy, not just equal iteration count.
- Test 0.25/0.5/1 s flow steps, supported response scales, normal/storm winds, and 32-cubed/48-cubed plus elongated domains.
- Repeat the live reference scene with unchanged sources/weather, then add source-free instruments, multiple players, moving ships, and repeated door/source changes.
- Stress bounded overload deliberately: memory/cell admission, worker backlog, long absence, chunk churn, and config reload. Confirm existing thermal progress and the game thread remain protected.

First optimization order: avoid geometry rebuilds; bound active space; reuse arrays; warm-start/early-exit pressure; skip unchanged regions; then consider stronger preconditioning, tiled traversal, or parallelism if measurements demand them. Do not start with multigrid, adaptive spatial refinement, or GPU code.

## 12. Verification matrix

| Fixture/check | Required evidence |
| --- | --- |
| Uniform open wind | Correct vector/magnitude; minimal pressure/divergence drift; stable uniform temperature |
| Thin snow, slabs, stairs, fences | Geometry-appropriate openings; no blanket full-voxel shelter |
| Closed/open doors and trapdoors; panes | No artificial connection through a separating thin surface; opening changes local flow |
| Single full block and a narrow 50-block row along the wind | Flow routes around obstacles; affected wake is local rather than infinite first-hit shelter |
| Broad wall, roof, doorway, corridor | Directional shelter, turning flow, and ventilation through real connected openings |
| Sealed room with heat/cold | Hot plume/cold descent with return circulation; no exterior mass leakage or pressure-vessel behavior |
| Unheated ambient domain | No spurious buoyancy from reference/altitude handling |
| Warm ceiling / cold floor; initially unstable layering | Stable layering persists reasonably; unstable layering produces circulation |
| Existing campfire house and wall cool-down | Timing/equilibrium/material storage compared with accepted calibration; differences reviewed |
| Insulated thermal box and prescribed-flow transport | Energy budget, uniform-state preservation, source limits, monotonicity, and response-scale behavior |
| Two touching tiles, building straddling a seam | Same interior answer within agreed tolerance as one larger domain; no artificial exterior exchange |
| Narrow apertures, disconnected pockets, tiny air volumes | Bounded solver behavior, correct pressure references, no NaNs or silent closures |
| Geometry changes during a worker job | Old revision cannot republish invalid flow; no query through a newly closed wall |
| Resize/merge/split/save/reload | No duplicated energy, lost retained warmth, or duplicated interface flux |
| Rotated/moving ship and hangar | Correct world-up buoyancy and relative ventilation; no own-block double shelter |
| Consumer checks | Gauge/redstone agreement, unchanged downwind arrow/cups and no reequip bob; thermometers/player samples agree |
| Overload/missing chunks | No chunk loads, unbounded catch-up, blocked readers, false outdoor readings, or starving thermal jobs |

Use pure Java unit fixtures for numerical behavior, game tests for snapshot/domain/consumer integration, and Bright's client for instrument visuals and gameplay timing. Compilation cannot verify how plumes, cups, or rooms feel in play. Keep existing tests and optional-mod architecture checks intact.

## 13. Implementation sequence and decision gates

1. **Prepare the comparison and geometry cache.** Add dirty-cause/revision telemetry; reproduce rebuild churn; separate source/material/geometry changes. Add the approved response-scale contract and prescribed-flow timing tests. Preserve the pre-flow control.
2. **Build the pure flow kernel.** Add shared geometry, partial connectivity, MAC velocity, boundary conditions, pressure projection, buoyancy forcing from a supplied temperature field, and diagnostics. Run deterministic wind/buoyancy fixtures and benchmarks before runtime integration. Unit-level wind-only controls remain useful.
3. **Couple heat and airflow.** Add distinct air/material state, shared-flux thermal transport, source limits, and the update sequence. Remove overlapping legacy convection only in this experimental path. Demonstrate rising/sinking currents and room ventilation together; compare calibration and cost.
4. **Expose a bounded in-game prototype.** Reuse workers/budgets; capture real world geometry; add opt-in domains and debug arrows/temperature/quality overlays. Connect the gauge and sensor using Bright's selected reading convention. First user trial includes two-way temperature/buoyancy coupling.
5. **Generalize demand and domain continuity.** Share player/instrument/source requests; prove cross-tile pressure/heat interfaces, state migration, incomplete coverage, chunk changes, persistence, and overload behavior. Select supported radius/cell defaults from measurements.
6. **Integrate player climate and optional ship ventilation.** Compare shared queries, frame transforms, hangars, and consumer semantics. Discuss any proposed ship-force change separately before implementing it.
7. **Tune and qualify.** Bright compares campfire warm-up/cool-down at response scales 0.5/1/2, draft strength, wind direction/cup behavior, and storm shelter. Run targeted unit/game tests, standalone Meteodyne with/without ship integration, architecture checks, full required checks, and a repeated live benchmark. Enable a default only after behavior, performance, and remaining gameplay decisions pass review.

Each gate produces a concrete comparison/report. Stop to ask Bright when evidence calls for a gameplay change, rather than adjusting accepted calibration to make tests convenient.

## 14. Decision register

| Decision | State |
| --- | --- |
| Constant-density Boussinesq model; warm rises/cold sinks | Selected by Bright |
| No compartment pressurization or variable-density solver | Selected by Bright |
| Thermal-response speed separate from measured wind/weather time | Selected by Bright |
| Local horizontal gauge versus total-speed cups/weather-only gauge | Question sent; awaiting Bright |
| First usable prototype includes buoyancy and heat transport | Proposed staging consistent with selected model |
| Numerical step, pressure tolerances/preconditioner, active radius/cell budget | Select from the specified benchmark/quality gates |
| Gauge/redstone unavailable-state behavior | Ask during consumer integration, with a concrete state proposal |
| Intentional changes to accepted thermal timing/equilibrium | Ask only if the coupled comparison requires them |
| Thermal updrafts influencing ship forces/lift; apparent-wind instrument | Separate gameplay decision before implementation |
