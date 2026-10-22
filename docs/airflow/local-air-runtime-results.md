<!-- Written by ChatGPT. -->
# Local air: cold-start coupled diagnostic checkpoint

2026-10-21. The [shared preparation pipeline](local-air-pipeline-results.md) can now initialize and advance the coupled solver for an explicitly requested, fixed-forcing diagnostic. Production thermal readers still use the existing thermal-only path. There is no automatic activation, player command, instrument integration, or production-default change.

## Initialization and sources

`HeatWorld.requestLocalAirSimulation(bounds, cellLimit, coverageLimit, settings)` selects the experimental run. Settings explicitly supply uniform ambient/initial Celsius, horizontal boundary wind, and a maximum accepted step count per job (1–64). These are diagnostic inputs, not newly selected gameplay defaults. The geometry-only request remains available.

After capture, coverage admission, and geometry baking, the worker initializes every represented air pocket, solid body, and static-fluid body to the supplied temperature. It installs the fixed weather boundary and projects the initial wind. The clock begins when the prepared domain first receives its run initialization time; no elapsed heat is invented for time spent capturing geometry. Initial publication has zero accepted physical steps and zero source energy.

World capture now includes immutable static source records alongside occupancy/material values. Records contain existing `AirParams.gridPower` power (radiant share removed), the resolved absolute limit, ambient-relative maximum offset, and `per_face`. On the worker, `ThermalSources` routes this captured power through the represented air pockets and neighboring face openings, including its existing enclosed solid/fluid fallback. Heating uses the lower of absolute and ambient-relative limits; cooling uses the higher. Radiant-only and inactive variants contribute no air-source power. No source/material calibration is changed.

The explicit requested domain includes its represented static natural sources, just as existing source regions include natural sources inside their box. A source touching a domain boundary rejects initialization because all of its routing neighbors may not be represented. Driven block-entity sources reject capture rather than freezing an unreported driver value; their live source-revision/update contract is not integrated. Capture still uses the same bounded no-chunk-load world view. Source edits currently use the conservative geometry epoch and trigger a complete diagnostic rebuild.

## Physical time, response history, and worker ownership

Each physical step is at most **10 game ticks / 0.5 seconds**, with shorter steps at response-rate transitions and captured interval ends. Once at least ten ticks accumulate, a job captures immutable chronological `ThermalClock` segments. A job accepts at most the explicitly configured step cap, then yields through the existing shared worker slot/budget. A yielded interval retains its original end and response history until complete, including a tail shorter than ten ticks. New game time forms a later interval; it does not change an in-flight interval or average away a response transition.

Physical time controls momentum and boundary wind. The corresponding segment's response scale controls thermal time. `HeatWorld` forwards rate observations during ordinary ticks, response override changes, and pause bookkeeping. The diagnostic does not reconstruct historical weather or driver values: its boundary and captured static sources remain fixed for the run.

The maximum retained backlog is **2,400 ticks / 120 seconds**, matching the existing catch-up window. Exceeding it explicitly reports `LAG_LIMIT`, withholds the field, and requires a restart. It never silently skips the oldest time. A rejected coupled step advances no time for that step, publishes no failed candidate, and leaves the diagnostic failed until rebuilt/restarted. Earlier accepted substeps in that failed job remain private and are not presented as a successful frame.

Only one experimental task remains physically in flight per world, shared across preparation, initialization, and stepping. Mutable solver state transfers between sequential jobs; the owner does not read solver arrays while a worker holds it. Run work packets execute once to prevent replay from mutating a later solver state. Replaced, closed, or invalidated jobs cannot publish, and still retain their physical slot until collected and charged by the existing scheduler.

## Publication and lifecycle

`localAirField()` returns an immutable diagnostic frame with its snapshot context/geometry/material revisions, bounds, flow geometry, represented temperatures, node velocities, face fluxes, accepted-through game tick, accepted step count, accumulated thermal seconds, stored energy, source energy, and exterior energy. Historical frames retain their values while later jobs run. `localAirRunStatus()` and `localAirRunReason()` distinguish initialization, successful initialization/stepping, failure, and excess backlog. A `READY` run status means a successful diagnostic frame exists; its `throughTick` is still required to assess age.

As in the geometry checkpoint, all reported world edits/chunk changes in the active world conservatively invalidate the domain; data-map reloads invalidate it directly. Publication checks withhold the old field before the next plan. The replacement run **cold-starts again** at the explicitly supplied temperature. This is not retained-heat migration or a production edit policy. Persistence, merge/split/resizing, moving ships, variable weather, live driver sources, and production player/instrument queries remain outside this checkpoint. Source and weather snapshots are experimental inputs, not a claim of complete live-world climate coupling.

## Verification and remaining gates

- **184/184 pure tests passed**, none skipped: 13 new run tests plus the previous 171. They cover explicit initialization of all three phases, source heating/cooling and buoyant circulation, wind controls at response scales 0.1/1/10, strict source limits and per-face power, source snapshot ownership, boundary-source rejection, coupled failure withholding, lag rejection, worker/frame ownership, one-shot work, invalidation cold-starts, and identical field/energy results across step caps 1/2/64 with off-step rate transitions and short yielded tails.
- Direct Java 21 compilation passes for `HeatWorld` and all `LocalAir*` main/game-test sources against cached game dependencies and existing project classes. A new game fixture captures a real lit campfire, checks its table-derived radiant-adjusted power/limit, requests coupled stepping in a closed room, and verifies warming followed by field withholding/rebuild after extinguishing it.
- **Game tests have not executed.** The required full Gradle check still fails before startup on wrapper ZIP lock access. Direct compilation is not full-project, event-ordering, gameplay, or performance verification. No settled calibration or live benchmark was rerun for this integration.

Evidence: [coupled runtime archive](../benchmarks/air-runtime-2026-10-21/README.md). Next is diagnostic quality/freshness visibility and live scheduling/cost verification, followed by measured domain/forcing updates and state continuity. The documented coupled-versus-legacy calibration difference remains unresolved; this checkpoint does not retune it or enable a production default.

Follow-up: [operator diagnostics and server timing](local-air-diagnostics-results.md) adds explicit controls, point sampling, quality/age reporting, and actual headless server captures. Standalone/coreless game verification now passes; the aggregate has a recorded existing calendar assertion failure.
