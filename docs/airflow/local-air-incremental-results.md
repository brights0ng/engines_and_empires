<!-- Written by ChatGPT. -->
# Local air model: incremental geometry preparation

2026-10-19. Follow-up to [the first cache change](local-air-step1-results.md), implementing the next optimization identified by Bright's `live-20261019-213525-704` capture. The airflow kernel and response-scale control remain subsequent work.

## Why this change

The v2 capture identified 1,039 snow-layer growth events and 1,101 per-region physical invalidations. It recorded 96 full geometry preparations, 55 rebuilt worker jobs out of 66 jobs, 8.26 ms/s of thermal main-thread work, and a maximum thermal tick of 53.85 ms. Geometry remained 85.6% of that main-thread work. Different domains, sources and wind prevent treating that capture as a matched speed comparison with v1. Its main value here is identifying the remaining rebuild cause.

## Implemented behavior

`HeatGeometryUpdate` coalesces local edits into primitive bit sets for affected cell columns, west/east rows, north/south rows and roof columns. Cell columns reconcile heightmap changes and shape neighbors; edits in the shelter halo or above the box still invalidate the sampled rows/columns. Material-only changes refresh their column without resampling shelter. Snow growth and melting retain the established thermal classification, masks, material coefficients and shelter effects.

Working arrays start from the previous completed snapshot and copy on their first actual write. Thus updates that change physical revision but leave the legacy thermal representation unchanged can complete without copying arrays or resubmitting cells to `AirGrid`. This is useful for intermediate snow heights. It does not implement the later partial-air connectivity model or dismiss those heights as physically identical for a future flow solver.

New regions and global invalidations queue complete rebuilds. The live tick rotates among regions, processing at most 256 line/column units per tick, with a shared 2 ms wall-time target and batches of at most eight units per region. A unit, array allocation or copy completes atomically, so 2 ms is a soft target. Snapshot metrics include target overruns. Main-thread region planning, initial thermal-state transfer and persistence are separate existing work and are not covered by this preparation target.

Block edits between slices requeue the affected inputs and lines. Completion requires draining all such work before publishing an immutable geometry snapshot. New thermal jobs wait while geometry is pending; their elapsed-time accounting remains intact. Completed previous thermal fields remain readable. Source/weather planning retains its existing one-second cadence. Chunk availability, data reload, shelter-reach and ship-roof changes supersede an incomplete full build. Disabling heat or forgetting regions releases queued input plans.

`planNow` and `runNow` intentionally drain preparation synchronously for debug commands and deterministic tests, outside the live allowance. The original full snapshot remains an independent test control; runtime preparation uses the new implementation.

## Verification and comparison

Three new game tests compare all cell kinds, conductances, capacities, open-face masks and shelter bits against the original full-snapshot implementation. They cover snow layers 1–8 and melting, doors/trapdoors, waterlogging, water/lava, material replacement, both horizontal halo directions, a high roof column, edits after cells have already been sampled, chunk availability during construction, immutable old arrays, shared work caps and round-robin progress. The existing exact temperature comparisons against forced full rebuilds remain intact.

In the small 9×9×9 snapshot fixture, the new full preparation reads 7,714 blocks; each snow-growth update reads at most 818 (89.4% fewer), including 500 coalesced notifications at the same location. Every tested local edit reads less than one quarter of a full preparation. These are deterministic operation-count comparisons against the new full path, not a live-world CPU or FPS claim. Exact geometry equivalence is checked independently against the retained original implementation.

The first compile/test run correctly exposed an outdated instrumentation expectation: the capture test's block edit now records incremental rather than full preparation. Its assertions now require the incremental and shared-preparation metrics. After that update, all 87 standalone Meteodyne game tests passed. Full-check evidence and final results are recorded in `../../build/local-air-incremental-full-check.log`; a final focused run is recorded in `build/local-air-incremental-final.log`.

The required full invocation (`compileJava compileTestJava test runGameTestServer --continue`) completed all checks: compilation and 1,092 unit tests had no failures (two existing benchmark skips); coreless passed 203/203; all standalone runs passed (Meteodyne 87, Frontier 18, lib 3, Sable Fixes 2, Seismic 20, Spoilage 34, Sparse Ores 13 bare and 13 with Create). The aggregate passed 253/254; its only failure is the previously observed `theGameTestServerHasNoSeasons` assertion because the root launch loads Serene Seasons. That local optional-mod setup and the assertion remain unchanged.

The final focused compile/game-test run passed 87/87 (`BUILD SUCCESSFUL`), including the corrected capture timing description and logged operation counts. `git diff --check` passed.

## Next reference capture

Restart the development client so it loads the new classes, then repeat `/meteodyne heat benchmark start 60` with the same snow/weather conditions, sources, location and loaded chunks. The new schema is `thermal-live-v3`.

Compare `thermal_tick_ms`, `snapshot_prepare_ms`, maximum/p99 slice time, sampled block reads, full versus incremental/thermal-no-op completions, jobs applying snapshots, and publication age. Check `snapshotPending` and prepared/current revisions at the end. Lower time with stalled work is not an improvement. V3 preparation runs outside `planning_ms`; comparing planning alone with v2 would overstate the gain. [Thermal performance documentation](thermal-performance.md#incremental-preparation-and-sliced-full-rebuilds-v3) defines the measurement boundaries.

No post-change reference-world capture or in-game visual verification has been performed. The established thermal solver, source laws and instrument behavior remain the control.
