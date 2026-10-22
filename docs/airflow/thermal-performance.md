<!-- Written by ChatGPT. -->
# Thermal and airflow performance baseline

This harness records the existing thermodynamics implementation before the local airflow prototype. It changes no thermal equations or scheduling settings. The offline suite and live capture answer different questions; retain both when evaluating the prototype.

## Offline fixed scenes

From the repository root, using Java 21:

```powershell
.\gradlew.bat :paradyne-lib:thermalBenchmark -PthermalBenchLabel=pre-flow
```

This is an explicit benchmark task, separate from normal tests. It launches three fresh JVMs sequentially, each with a fixed 512 MiB heap and G1 GC. Each phase warms for at least 30 operations and 300 ms, then measures 100 batches of ten operations. Reports are written to a new UTC timestamped directory under `../../build/benchmarks/thermal`. No timing threshold decides test success.

| Scene | Dimensions | Cells, including boundary |
| --- | --- | --- |
| Open volume | 16×16×16 | 4,096 |
| Sealed house | 24×16×24 | 9,216 |
| House with doorway | 24×16×24 | 9,216 |
| House with a partial block in the opening | 24×16×24 | 9,216 |
| Partitioned rooms | 32×32×32 | 32,768 |
| Large partitioned volume | 48×48×48 | 110,592 |

Fixtures use the current `AirParams.DEFAULT`, a fixed wind vector (6, 2) m/s, and one 75-unit heat source limited to a +30 °C offset. Cell layouts, face masks and shelter arrays are fixed inputs; they are not assertions that current shelter is physically correct. The geometry fingerprint records those inputs. The partial-block fixture preserves the current thermal face-mask representation for later comparison with richer airflow geometry.

Each scene measures:

- `grid-create`: allocation and initialization of a new numerical grid from prepared arrays.
- `step-1s` and `step-10s`: the existing thermal solver at those simulated durations.
- `geometry-apply-and-step-1s`: applying prepared geometry, rebuilding numerical coefficients, and stepping.
- `publication`: allocation and export of offsets, cell kinds and wind shares.

These do **not** measure Minecraft block lookups, live source scanning, world geometry snapshots, region planning, or thread queueing. Use the live capture for those costs. Temperature evolves during warmup and measurement, so this measures repeated simulation work rather than repeatedly constructing a cold room.

Each fork writes JSON metadata and CSV measurements. Metadata includes revision, dirty-worktree flag, runtime, processor information, heap/VM arguments, thermal parameters and workload dimensions. Wall time, thread CPU time and thread allocated bytes are reported separately. Unsupported counters remain unavailable. Windows thread CPU timers can be coarse: interpret CPU averages across substantial batches, not individual zero-valued samples.

`summary.json` and `summary.csv` report the median of the three per-fork means, with the smallest/largest fork means to expose variability. Fork reports retain p50/p95/p99, maximum and allocation measurements. Offline percentiles describe **per-operation batch averages**, not individual job or frame latency. This is a lightweight comparison harness, not a JMH or whole-game benchmark.

To compare a later implementation:

```powershell
.\gradlew.bat :paradyne-lib:thermalBenchmark -PthermalBenchLabel=prototype -PthermalBenchCompare=docs/benchmarks/thermal-pre-flow-2026-10-19
```

Comparison requires the same harness schema and matching geometry fingerprints. `baselineWallRatio` is current mean cost divided by baseline mean cost: 1.20 means 20% slower. Keep hardware, Java, heap, settings, scene definitions and background applications comparable. Retain the thermal-only path when adding airflow phases; the current harness will not automatically start measuring a new solver. Add airflow and combined-job phases explicitly and distinguish their costs from the thermal-only control.

## In-game capture

Restart the development client after compiling these changes. In a loaded Overworld scene with active thermal sources, run:

```text
/meteodyne heat benchmark start 60
/meteodyne heat benchmark status
```

The command warms up for ten game seconds, then records sixty game seconds without pausing the server, changing blocks, advancing the simulation manually or overriding its CPU budget. It saves automatically and prints the report path. `stop` ends early and saves a partial report. A second capture cannot replace an active one.

Output defaults to `../build/benchmarks/thermal/live-UTC-TIMESTAMP/` relative to the game's working directory. For the root development client this is the repository's `../../build/benchmarks/thermal`, outside `run/`. The `meteodyne.thermalBenchDir` JVM property can select another output directory.

The report contains:

- `thermal_tick_ms`: thermal work on the server thread, excluding background computation.
- `planning_ms`: source/region planning and input preparation.
- `geometry_snapshot_ms`: changed-region geometry preparation, including cover.
- `cover_snapshot_ms`: the cover portion of that preparation.
- Worker wall and CPU time, allocations, executor queue wait, numerical solve time, and publication time.
- Separate worker distributions for clean jobs, geometry rebuilds and initial settling.
- Job/cell counts, simulated seconds, region boxes, sources, wind, ship status, result age and unfinished jobs at the end.

`summary.json` includes metadata and distributions; `summary.csv` provides a table; `jobs.csv` retains individual worker job measurements. In v1/v2, planning includes geometry, and geometry includes cover: these are nested timings and must not be added together. In v3, snapshot preparation moves outside planning (see below). Worker total includes solve and publication in all versions.

Only jobs submitted during the measured window and collected before it ends contribute to worker metrics. Unfinished tail jobs are excluded and end-of-capture region state exposes pending work. The CPU-core fraction is measured worker thread CPU seconds divided by elapsed real seconds, not a configured budget or a claim about whole-game utilization. Main-thread metrics use wall clocks. The existing `/meteodyne heat` display retains its original budget accounting.

### Invalidation diagnostics (local air step 1)

The first invalidation captures used `thermal-live-v2`; incremental preparation now uses `thermal-live-v3` (below). The archived v1 baseline remains unchanged. `geometryRebuildJobs` still means a job applied a cell snapshot, including a material-only refresh; it does not mean a physical topology changed. The following fields distinguish the work:

- `invalidationEvents`: per-region causes (`block_geometry`, `block_material`, `chunk_load`, `chunk_unload`, `region_created`, `ship_roof`, `data_reload`, `shelter_config`) and changes to prepared `source_inputs`. Multiple invalidations can coalesce into one snapshot. Source-input changes include driver and ambient-dependent source-limit updates.
- `blockStateChanges`: relevant block-hook events, grouped by full before/after state and classified as geometry, material, or `unchanged_snapshot_inputs`. The last category includes source-only toggles and unrelated state changes; it is not a count of heat-source toggles. Deferred off-thread notifications have an unknown prior state and conservatively invalidate geometry. State-pair detail is limited to 128 keys plus an overflow count, and collected only during the measured capture window.
- `material_snapshot_ms`: material preparation that reuses shelter. `geometry_snapshot_ms` remains the complete physical snapshot including `cover_snapshot_ms`. In v2, planning includes both; v3 reports preparation separately, as described below.
- `jobs.csv`: region id and geometry/material/source revisions captured at dispatch. End-of-capture region records include dirty-input and prepared-snapshot revisions. Revisions count invalidations or prepared source changes; they are not a fingerprint proving two physical shapes differ.

Source-only changes keep the cell/shelter snapshot. In v2, material-only changes sample the region's cells and refresh numerical coefficients, but reuse the shelter array; v3 narrows this to affected columns. Collision-shape/fluid changes, context-sensitive shapes, sampled shelter margins, shape neighbors, heightmap columns, and chunk transitions invalidate physical snapshots. This retains the existing thermal face-mask model; the connectivity-preserving partial-air geometry in the local air plan is a later step.

### Incremental preparation and sliced full rebuilds (v3)

Block edits now queue affected cell columns, horizontal shelter rows, and roof columns. Repeated notifications coalesce in bit sets. Material edits sample only the affected cell column. Columns account for changed heightmaps and include shape neighbors; changes outside the box still refresh affected shelter rows. Physical geometry revisions continue to advance even when the legacy thermal arrays do not change (for example, several intermediate snow heights). Such updates finish without submitting unchanged cells to the thermal solver. Snow is not ignored or treated as a full voxel to avoid work.

Full invalidation (new region, chunk availability, data reload, shelter-reach or ship-roof change) queues all columns and rows. Live work rotates among regions, with a shared cap of 256 row/column units per tick and a 2 ms time target. A unit, allocation or copy is atomic; this is a soft wall-time target, not a hard maximum. Copy-on-write arrays protect in-flight snapshots. Edits arriving between slices requeue sampled locations, and workers wait for complete geometry; elapsed thermal time is retained for the next job. Existing source and weather planning still occurs once per second. The debug/test `planNow` and `runNow` paths explicitly finish pending preparation synchronously, outside the live allowance.

The v3 measurements distinguish:

- `snapshot_prepare_ms`: actual shared preparation work each tick, including completed-input refresh. It is inside `thermal_tick_ms` and **outside** `planning_ms`; v1/v2 planning included snapshot work. Compare the complete thermal tick across versions.
- `snapshot_slice_ms`: one region's batch of at most eight row/column units.
- `geometry_snapshot_ms`: accumulated active slice time for a completed full rebuild. `geometry_incremental_ms` and `material_snapshot_ms` measure completed local updates. These exclude waiting between ticks and can span capture boundaries. Their totals must not be added to per-tick preparation time.
- `cover_snapshot_ms` / `cover_incremental_ms`: the shelter portion of completed full/incremental updates. Both are nested inside their geometry timings.
- `snapshotWork`: sampled block reads, completed row/column units, full/incremental completions, thermal-no-op completions, time-target overruns and slices reaching their unit cap. Missing counter keys mean no such events occurred during the capture. Completion counts may include preparation begun before the capture; sampled reads and executed units count only measured slices.
- `regionsAtEnd[].snapshotPending`: whether complete replacement geometry is still being prepared. Check this along with prepared/current revisions, `inFlight`, and publication age before interpreting lower cost as useful progress.

The independent original full-snapshot implementation is retained as a game-test control. Tests compare every cell kind, coefficient, open-face mask and shelter bit after snow growth/melt, doors, fluids, halo/roof changes and edits during sliced construction. These tests establish equivalence and bounded work, not reference-world latency or FPS.

### Thermal response timing in captures

The [thermal response control](thermal-response-control.md) adds time metadata to v3. `simulated_seconds` / `simulatedSecondsAcrossJobs` are thermal evolution seconds; `game_seconds` / `gameSecondsAcrossJobs` are unscaled elapsed game time. `response_scale_at_dispatch` reports the chosen rate when a job was prepared, while the thermal interval integrates any rate changes during its wait. `responseScaleAtEnd` and `responseScaleMode` identify the effective setting and a temporary override. Keep response scale fixed and matched for before/after performance trials. Natural initialization remains a fixed thermal settling estimate and is excluded from both job elapsed-time totals.

For the next reference-world comparison, capture a steady scene for 60 seconds, then a separate 60-second trial toggling the same campfire/furnace while keeping the building fixed. Use a third trial for door/block changes. Compare geometry/cover snapshot counts, state-pair causes, and game-thread time against the original capture. Keep world, sources, weather and settings matched. Headless fixtures verify reuse and thermal equivalence; their timings do not establish a live-world speedup.

This measures the thermal subsystem, not whole-server MSPT, rendering or client FPS. File export occurs after the measured window. No jobs means no usable solver baseline; the command reports that case. A headless integration test validates capture and report creation, but does not substitute for a capture in the player's actual client environment.

## Matching live trials

For useful before/after captures, keep the same world, position, loaded chunks, source states, doors, weather override and thermal settings. Turn the heat debug view off. Do not run the offline benchmark, build, profiler or another load generator during the capture.

Capture three trials per scene: a warm sealed house; the same house with an open doorway and fixed wind; and a larger build or ship cabin. Let new regions finish their initial settling before starting. Capture a separate trial while changing a door or a block to measure invalidation/rebuild cost; do not mix that with the steady-state trial. If using the existing wind override, restore its previous state afterward.

For the airflow prototype, record both added computation and useful behavior: flow solve time, pressure iterations/residual, active cells/regions, published-field age and thermal calibration. A fast solver whose jobs are stale or whose air crosses sealed walls is not a successful result. Background execution protects the tick from numerical work, but does not remove its CPU/memory cost or the server-thread cost of producing geometry snapshots.

## IntelliJ and verification

Bright's IntelliJ MCP connection is available and was verified through run-configuration discovery and the actual outgoing call hierarchy of `HeatWorld.run`. Existing `Client`, game-test and test run configurations are accessible. No connection credentials or user MCP configuration were changed.

Verification results and the recorded baseline directory are listed in the accompanying baseline results note. The selected baseline is archived under `../benchmarks/thermal-pre-flow-2026-10-19`; new timing reports in `build/` are generated artifacts.
