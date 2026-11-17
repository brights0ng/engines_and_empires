<!-- Written by ChatGPT. -->
# Planner-driven live local-air trial

This connects the [demand registry](local-air-demand-registry-results.md), [tile planner](local-air-planner-results.md), and [shared rectangular tile assembly](local-air-tile-interface-results.md) to live capture and the existing coupled preparation pipeline. Admitted, compatible groups now have independent simulation owners and immutable queried fields. This is an explicitly enabled trial; production temperature/wind consumers and the manual arrow viewer keep their existing paths.

## Commands

```text
/meteodyne heat air plan <radius> <margin> <totalTiles> <componentTiles>
/meteodyne heat air auto <domains> <totalCells> <coverageLimit> <steps>
/meteodyne heat air auto
/meteodyne heat air auto sample <maxAgeTicks> [position]
/meteodyne heat air auto off
```

An initial trial policy is `air plan 4 4 32 8`, followed by `air auto 2 65536 1000000 4`. Those are explicit experimental inputs, not qualified production defaults. This allows two owners, 65,536 total reserved block cells, up to one million coverage reads per owner and at most four physical steps in one worker job. Each owner remains capped at 32,768 cells. Runtime argument ranges are 1..4 owners, 4,096..131,072 total cells, 0..1,000,000 coverage reads and 1..64 steps. A zero coverage allowance supports fully solid boundary fixtures, not ordinary open sky.

Bare `auto` reports settings, reserved cells, each owner (including retained owners), preparation/run state, reason, frame tick, restart count, air-node/face counts, and up to 16 admission decisions. `auto sample 40 ~ ~1 ~` queries air above the command position with a caller-selected age limit; sample age accepts 0..40 ticks. Automatic domains do not appear in the manual `air status`/arrow view. Operator permission, enabled heat and a configured Overworld plan are required.

`auto off` explicitly clears its transient fields. `plan off` also stops the runtime. Heat disable and server shutdown stop automatic owners. Changing runtime limits requires stopping the current trial first; changing the planner policy reconciles against existing owners without silently resetting their warmth. The experimental server-thread API is `HeatWorld.queryAutomaticLocalAir(x,y,z,maxAgeTicks)`, distinct from the existing manual-domain query.

## Admission and ownership

Follow-up: the [layout transfer checkpoint](local-air-layout-transfer-results.md) implements atomic compatible rectangular resize/merge/split, and the [sparse-domain checkpoint](local-air-sparse-results.md) extends execution and retained transfers to irregular full-tile unions. The initial deferral behavior described below remains the historical runtime baseline; partial-height tiles, inadequate temporary headroom and invalid coverage still defer replacements.

- Only planner groups marked `PROPOSED_UNVERIFIED` are considered. Each complete rectangular, full-height tile union gets one owner. Missing chunks, planner limits, irregular unions and partial-height tiles remain deferred with explicit reasons. No empty bridge joins distant requests.
- Compatible bounds reuse the existing owner. Active players take admission precedence, then other active owners, then grace-only groups, with deterministic spatial ties. Total owner/cell limits include retained owners.
- New bounds overlapping or face-touching an existing owner are `TRANSFER_PENDING` until resize/merge/split transfer exists. They do not start an overlapping solve or turn a shared cut into two exteriors. Existing owners keep running under the same limits. An owner no longer selected by the current plan is reported as retained and its query is pending rather than current. There is no automatic eviction policy in this checkpoint; retained owners can exhaust the trial allowance until explicitly stopped.
- Every new owner passes sliced world capture, conservative ground/partial-boundary and sky/exterior coverage admission, a versioned worker bake, and initialization before publishing. Invalid bottoms/cuts reject; coverage does not yet automatically grow around a rejected cut. Weather is sampled at each group's center under the existing center-uniform prototype convention.
- The admitted coherent snapshot is partitioned into immutable 16-cubed tile inputs on the worker, then joined before pressure/thermal geometry construction. Shapes, materials, sources and signed interface flux share the same flat domain indexing. No world objects enter worker work.

Automatic bakes additionally guard actual flow allocation: at most four air nodes and twelve faces per captured block cell in aggregate. Node limits are checked before node arrays; face limits before excess face-array growth. Each block adds at most one solid and one stationary-fluid storage state. These experimental guards reject excessive split-pocket geometry rather than reducing physical resolution. Counts bound allocations; they are not measured byte/CPU promises or selected production memory defaults.

## Shared work and invalidation

Manual and automatic local-air owners share the original local-air physical job slot and completion queue. Retired jobs keep that slot until collected and their measured wall time is charged. Dispatch rotates among owners, while the existing scheduler alternates local-air and legacy thermal access. There is no per-domain executor or multiplied core allowance.

All owners and legacy thermal snapshots share the existing 256-unit/2 ms preparation allowance. Local-air capture rotates its available allowance among owners; a single manual owner can still reclaim the full unused allowance. Geometry, node/face construction and coupled steps run on the existing heat pool. As before, the snapshot deadline is a soft bound around atomic work and worker wall accounting is an average allowance, not a preemptive job deadline.

Each owner registers its own domain/shape/sky dependency footprint. Block/chunk hooks invalidate these immediately, including off-thread hooks before server-thread marshalling. Existing verified vanilla campfire LIT refresh and door OPEN history/transfer apply to automatic owners. Material revision and stale-result guards remain intact. Unknown edits/chunk reconstruction can still cold-start under the prototype's existing policy; this checkpoint does not add persistence or general topology continuity.

Existing benchmark worker stage timings aggregate automatic and manual local-air jobs. The local-air report additionally includes automatic settings, reserved cells, owner rows and admission decisions. Planning/admission runs inside `local_air_planning_ms`; automatic weather/clock observation is included in the thermal tick. Snapshot and worker phase metrics keep their existing names. GameTest `testArea` filtering now also scopes planner input, avoiding unrelated sources in shared test worlds; normal play has no such filter.

## Verification and remaining gates

Five new pure lifecycle tests cover owner reuse, live tiled bake and heated publication, retained incompatible layouts, face-contact exclusion, total/owner limits, irregular/partial-height rejection, missing chunks, invalidated work, coverage rejection and stop-time discard. Geometry tests additionally exercise exact node/face allocation rejection without weakening convergence settings.

The in-world integration fixture has a two-tile closed heated room and a separated one-tile room: 12,288 cells total, two automatic owners, no connecting empty volume. It verifies both owners progress through the shared worker lane, publish current point queries, preserve one connected solve across the tile seam, retain the owner through a verified campfire forcing change, and discard availability on stop. It restores its blocks and separately owned fixture chunk tickets. Test pacing is a scheduling allowance, not a live performance result.

Final verification on 2026-10-26: IDE build passed; focused lifecycle/geometry tests and standalone Meteodyne passed. After correcting fixture isolation/chunk-ticket ownership and decimal command formatting at large random coordinates, the complete `gradlew compileJava compileTestJava test runGameTestServer --continue --no-daemon --no-parallel --console=plain` check passed in 3m 27s (`build/air-auto-final.log`): 402 aggregate, 352 coreless, 104 standalone Meteodyne GameTests and all other standalone runs. Unit suites passed, including five automatic lifecycle tests, nine tile/geometry tests and six architecture checks. Renderer/client behavior and multiplayer cost have not been tested in this checkpoint.

Next: retained-state resize/merge/split and irregular coverage, so moving demand can change allocations without losing warmth or remaining pending. General driven block-entity forcing is still rejected by capture, natural-source arrival still uses uniform coupled initialization in this trial, and production consumer migration, thermal persistence, overload qualification and default activation remain separate gates.
