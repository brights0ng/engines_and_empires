<!-- Written by ChatGPT. -->
# Automatic local-air demand registry

This is step 1 of the production-integration follow-up to the [live comparison](local-air-live-comparison-results.md), within stage 5 of the [model plan](local-air-model-plan.md). The shared registry now observes required sample cells in the Overworld. It does not allocate domains, start coupled jobs, choose coverage radii, or change production readings.

## Producers and sharing

| Producer | Owner key | Required sample cell | Requested quantities |
| --- | --- | --- | --- |
| Alive, connected, non-spectator player | Player UUID | Existing body point, position + 0.9 Y, floored | Temperature and flow |
| Loaded placed heat-source candidate | Packed block position | Source block | Temperature and flow |
| Placed temperature sensor | Packed housing position | Block immediately in front of its facing | Temperature |
| Placed wind sensor | Packed housing position | Block immediately in front of its facing | Flow |

Kind is part of the owner key. Held instruments reuse the player request. Multiple owners at the same sample cell remain independently removable, while the immutable snapshot groups their requirements into one shared anchor. This is deduplication of planner input, not yet sharing of a simulated domain. A source anchor can lie in solid material; the planner must include its surrounding air rather than interpreting every anchor as an air sample.

Sources reuse `HeatWorld`'s existing placed-source candidate index. Current states are checked against the heat tables: non-natural in-air source definitions and driven-source candidates qualify. Natural sources do not independently request coverage; their participation inside future active domains remains a later planner/solver responsibility. Driven candidates request coverage even when their block entity is currently inactive; capturing their time-varying forcing is a separate gate.

## Lifecycle and cost

Players reconcile every server tick. Sources and sensor availability reconcile when dirty, when explicitly inspected, on a heat-table revision, or at least every 20 ticks. Sensor block changes and block-entity/chunk load notifications also update the sensor cache. Reads use already available chunks and do not request chunk loads. Plot-frame sources and mounted sensors are excluded until ship-frame planning is implemented.

Moving an owner to another cell, removing it, or leaving the level retires its previous request for 100 game ticks (five seconds at 20 TPS). Refreshing an unchanged owner does not change the revision; repeated removal does not extend expiry. Returning to the same retained cell reuses that entry. The interval is provisional demand retention, not thermal-state persistence policy or an approved production radius. Grace expiry alone must not erase room or material warmth in the future domain manager.

Chunk unload immediately removes active and retained requests in the unavailable sample chunk. Sensor housing unload also removes that sensor's entries when its sample lies across the chunk edge. Unavailable housing/sample chunks release the corresponding sensor request; cached loaded sensors can become eligible again when coverage is available. Off-thread load/unload notifications marshal registry mutations to the server thread. Registry mutations and snapshot reads enforce owner-thread access.

Heat disable clears demand; re-enable rebuilds it from current producers. Server shutdown clears transient registry state. No demand entries are saved. Registry refresh cost is recorded as `local_air_demand_ms` inside `thermal_tick_ms`; do not add these nested timings together.

Heat-table reload revalidates indexed source positions. Newly classified source blocks that were never indexed still require the existing block-change/chunk-load indexing path; this checkpoint does not add a loaded-world rescan on reload.

## Inspection and verification

`/meteodyne heat air demands` reports active owners by kind, retained requests, shared sample cells, and registry revision, with the first 16 anchors. Historical cells from one moving owner can produce multiple retained requests. The command points to `/air plan` for the later allocation proposals. It works without a manual coupled domain; `/air stop` stops that domain without disabling producer observation.

Six registry unit tests cover shared requirements, immutable snapshots, stable revisions, movement/return, exact expiry, reconciliation/removal, negative chunk coordinates, immediate unload/forget, atomic input validation, and thread/time guards. Two GameTests cover actual source/sensor blocks and removal, natural-source exclusion, sensor sampling position, unload cleanup, command registration, player movement/departure, held-instrument reuse, and absence of automatic solver activation.

Final verification on 2026-10-26: IDE build passed; `gradlew compileJava compileTestJava test runGameTestServer --continue --no-daemon --no-parallel --console=plain` completed successfully in 3m 27s (`build/air-demand-full.log`). All 400 aggregate, 350 coreless, and 102 standalone Meteodyne GameTests passed, along with every other standalone run. The six registry unit tests and all six architecture checks passed. This includes the final chunk-availability, sensor-state, off-thread notification, and benchmark-accounting changes. Client visuals and multiplayer scale have not been tested by this checkpoint.

## Next gate

Follow-up: the [automatic tile planner](local-air-planner-results.md) now consumes this registry under explicit experimental limits. Solver execution and coverage qualification remain separate gates.

Consume this registry in an automatic coverage planner: turn anchors into bounded spatial demand, combine overlapping requests, retain stable allocations across movement, and expose missing-chunk/admission reasons. Prove shared interfaces between touching tiles before treating them as independent runtime allocations. Domain ownership, warm-state transfer, persistence, general driven forcing, production consumer routing, and default activation remain subsequent work.
