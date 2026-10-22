<!-- Written by ChatGPT. -->
# Local air: retained state across door topology changes

2026-10-21. Following [source refresh](local-air-source-refresh-results.md), supported door OPEN changes now transfer air/material warmth and compatible face motion into rebuilt geometry, with a fresh pressure projection and an explicit geometry-energy ledger. The diagnostic remains opt-in; production consumers and live weather forcing are unchanged.

## Runtime contract and chronology

The server-thread hook accepts an exact vanilla `DoorBlock` instance whose only changed property is `OPEN`, strictly inside the current domain. The same block and material ownership must remain. Both door halves can be coalesced while preparation is pending at the same game tick. Source toggles retain their separate source-only refresh path.

At the door-change tick, the old run is sealed. Already dispatched work may complete, and further bounded packets finish the old topology **through that exact tick**, including a short final interval and pending source transitions. The invalid topology is withheld immediately; its delayed jobs may contribute chronological progress to the transfer seed but never republish the old field. There is still only one physical local-air job slot, shared with existing heat work.

New geometry capture, sky-coverage evidence where required, and baking use the existing sliced preparation pipeline. An immutable seed contains temperatures, face fluxes, cumulative thermal/source/exterior history, quality counters, and the original energy baseline. Pressure and solver work buffers are not transferred. Old mutable solver ownership is retired before the new owner initializes.

The new run starts at the edit tick, then catches up through time spent rebuilding. Response-rate history is retained across that interval; elapsed time is neither skipped nor replayed using the old topology after the cut. Source revisions and cumulative accepted-step counts continue across transfers. More than 2,400 ticks of retained preparation history produces an explicit failure rather than truncating time.

Bounds/origin, context, table revision, and all captured material coefficients must be compatible. An incompatible seed fails initialization without publishing a field. Unknown edits, chunk changes, data reloads, different-tick edits while a transition is pending, boundary doors, and OPEN changes combined with other properties keep the conservative cold-rebuild behavior. This checkpoint does not implement domain resize/merge/split, a queue of intermediate topologies, redstone changes that also alter POWERED, moving ships, persistence, or general block replacement.

## Mapping and energy accounting

`GeometryTransfer` is a pure fixed-bounds mapper in Paradyne Lib:

- Unchanged air pockets retain their temperatures. Changed cells transfer air by overlap on the same 16-cubed geometry lattice. Unrelated pockets do not exchange heat merely because their component indices changed; a newly merged pocket averages only its actual local overlapping contributions.
- Solid and stationary-fluid storage retains cell/material ownership. A rotated thin door keeps its material temperature and stored heat when its capacity is unchanged. The caller must prove retained material identity; matching capacity alone does not authorize transferring heat into a replacement block.
- Newly represented capacity initializes at the request's explicit ambient temperature. Removed capacity leaves with its prior temperature. These are signed Celsius-based solver energy amounts, consistent with the existing ledger; they are not numerical error or pressure work.
- Face motion is copied on compatible shared patches. Newly opened patches contribute zero initial motion. The target grid then builds its own pressure operator and projects, so a newly closed wall cannot retain an invalid through-wall flux. Failed initialization is unavailable.

Frames/report output add `geometryTransfers` and cumulative `geometryExchangeEnergy` (incoming minus outgoing). The numerical ledger is now:

```text
error = stored - initial - source - exterior - geometryExchange
```

This separates numerical drift from the explicitly accounted heat associated with represented volume changes. It does not redistribute displaced air throughout a room or model physical pressure work by the swinging door.

## Verification

**69 focused tests passed**, with no skips: six mapper tests plus 63 local-air tests. Seven new runtime tests cover exact edit-time sealing with an in-flight packet and short tail; response history during preparation; same-tick edit coalescing and stale plans; unknown-change cancellation; later-tick fallback and stopping; history overflow; and source-ledger/revision continuity. Mapper tests cover air-pocket merge/split behavior, solid/fluid retention, rotating thin material, compatible patch motion, fresh projection, invalid inputs, and target-owner identity.

The isolated 1,008-cell paced scene passed **1/1**, now containing five phases. It asserts two air components with the door closed, one when open, retained warmth/progress, exactly one transfer per paired door edit, energy accounting, source-off cooling, and the final prescribed-wind control. Reproduce with:

```powershell
.\gradlew.bat :meteodyne:runGameTestServer '-Pmeteodyne.airScene=true' --console=plain
```

| Phase | Air nodes | Cumulative accepted steps | First field delay | Maximum sampled age | Transfers | Cumulative source energy | Geometry exchange |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| Closed door, lit | 452 | 20 | 129 ticks / 6.45 s | 10 ticks | 0 | 750.00 | 0 |
| Open door, lit | 452 | 40 | 129 ticks / 6.45 s | **129 ticks** | 1 | 1462.50 | -0.007109 |
| Source off, cooling | 452 | 60 | 3 ticks / 0.15 s | 10 ticks | 1 | 1466.25 | -0.007109 |
| Close door, retained cooling | 452 | 80 | 129 ticks / 6.45 s | **129 ticks** | 2 | 1466.25 | -0.362732 |
| Open exterior, explicit wind (new request) | 696 | 20 | 345 ticks / 17.25 s | 11 ticks | 0 | 0 | 0 |

The initial remapped field is at the edit tick, so its age includes the rebuild interval: **this does not meet the two-second freshness direction during reconstruction**. The solver subsequently catches up; end-of-phase age was one tick for each door phase and two ticks for the wind control. Capture windows lasted 16.48, 9.48, 9.09, 9.49, and 27.50 wall seconds. Opening and closing mapped initialization/projection took approximately 7.05 and 5.65 ms of worker wall time; geometry capture latency remains unchanged. This is a short paced headless trial, not a production-scene, client-FPS, or whole-server MSPT qualification.

Maximum accepted divergence across the phases was `3.52e-8`; maximum absolute cumulative numerical energy error was `3.44e-7`. Geometry exchange is deliberately reported separately. Source energy increases by the chronological pre-extinguish tail, then remains constant through source-off cooling and door closing. Steps can be shorter at source/edit/rate boundaries; step counts do not imply exactly half a second per accepted step.

Raw captures, the CSV, and verification summaries are in [the evidence directory](../benchmarks/air-geometry-transfer-2026-10-21/README.md). Broader domain/context invalidation and shorter rebuild unavailability remain necessary before production use.

## Project verification record

Compile and unit-test tasks passed in the full `compileJava compileTestJava test runGameTestServer --continue` invocation. Game results were aggregate **262/262**, coreless **211/211**, Frontier **18/18**, lib **3/3**, Sable Fixes **2/2**, Seismic **20/20**, Spoilage **34/34**, and Sparse Ores **13/13** both bare and with Create. Meteodyne initially failed three existing tests: two underground-cover readings reported skylight 15 despite their sealed fixture, and the shared-pipeline chunk-hook wait timed out. The full invocation therefore exited 1; it was not a clean first-attempt full check.

The unchanged `:meteodyne:runGameTestServer` rerun passed **95/95**, exit 0. No cover or pipeline assertions were edited, removed, or weakened. Thus every suite passed across the recorded full invocation plus that targeted retry. The isolated five-phase diagnostic and all 69 focused tests passed on their first execution. IDE compilation passed. Client visuals, production load, and first-attempt startup reliability are not established by these results.
