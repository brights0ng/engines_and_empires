<!-- Written by ChatGPT. -->
# Local air: world capture checkpoint

2026-10-20. Stage 4 now has an isolated world geometry/material adapter. It is not connected to the live thermal regions, worker scheduler, instruments, or a runtime opt-in command. The existing solver and calibration remain unchanged.

## Capture contract

`LocalAirGeometryCapture` accepts an explicit cell admission limit, per-slice cell allowance, and monotonic deadline. It checks geometry and material revisions before slices, after each sampled cell, and before snapshot handoff. Changed inputs invalidate the capture. Missing chunks and unsupported geometry have distinct terminal states; neither can produce a snapshot or become empty air.

The server-side `LocalAirWorldSource` reads only already loaded chunks through `getChunkNow`. Neighbor shape/fluid queries use the same restricted view, capped at 128 block reads per sampled cell. Block entity queries are rejected. Each phase accepts at most 64 shape boxes. Capture never asks the chunk provider to load a chunk.

Snapshots own immutable cell-local shape boxes and cloned primitive material arrays. They retain no source, level, block state, or chunk reference. Worker-side baking shares repeated shapes within a snapshot and builds explicit solid/fluid thermal states. The geometry revision and material-table revision travel with the snapshot. A completed snapshot remains a historical value: future worker publication must check its revisions against the current world again.

Solid and fluid values come independently from the existing insulation data map, using the fluid's legacy block for its material. Waterlogged blocks no longer require collapsing both materials into one. No material values are retuned.

## Geometry support and limits

| Input | Current handling |
| --- | --- |
| Air and cell-local colliders | Capture and sample onto the existing 16-by-16-by-16 occupancy grid |
| Thin snow | Use the snow outline volume; the lowest layer occupies one eighth block despite its empty collision shape |
| Slabs, doors, stairs | Capture their state-resolved shapes; game fixtures added for runtime verification |
| Water/lava and waterlogging | Sample fluid height separately; solid occupancy takes precedence where phases overlap |
| Unloaded neighbor or cell / outside build height | Missing geometry; withhold snapshot |
| Collider extending outside its owning cell, including fences | Unsupported; withhold snapshot until cross-cell material ownership is implemented |
| Block-entity-dependent shape | Unsupported; withhold snapshot |
| Sub-resolution solid feature | Unsupported rather than silently deleting the feature |

Box bounds and fluid heights use sample-center quantization to sixteenths. Nonaligned fluid surfaces are approximations, not exact fluid volumes. Empty-collision vegetation follows collision occupancy; snow is an explicit exception. Read/box budgets and between-cell deadlines bound cooperative work; they cannot preempt arbitrary third-party shape code in the middle of a call. Live capture cost and admission limits still need measurement.

The caller must provide a geometry revision covering block edits, relevant shape neighbors, chunk unload/reload, and domain changes. That invalidation wiring is not integrated yet. Material reloads use the existing `HeatTables` revision. Dry shape reuse follows Minecraft's nondynamic block-shape contract; fluid heights are sampled in context.

## Exterior coverage remains a gate

The only bake exposed here is a **closed diagnostic control**. A complete capture does not certify that its edges are outdoors. No heightmap or roof heuristic labels a cropped indoor passage as weather. Exterior classification, adequate margins, bounded domain admission, and current-revision worker publication must be implemented before this can supply usable live airflow. Outer solid/fluid truncation retains the existing insulated control.

## Verification

- **148/148 pure tests passed**, none skipped: the prior 140 tests plus eight capture tests. New checks cover slice/deadline limits, coordinate order, admission overflow, missing/unsupported states, revision changes during/between slices and before handoff, defensive shape/material ownership, separate wet storage, closed boundaries, and owner-thread capture with worker-thread baking.
- The Minecraft-facing adapter and two new game tests compile directly with Java 21 against cached Minecraft/NeoForge dependencies and existing project classes. This is an API check, not a clean full-project build.
- Game tests cover snow, slabs/waterlogging, open/closed doors, stairs, water/lava, rejected fence overhangs, and missing chunks without loading. They have **not executed**: the required `compileJava compileTestJava test runGameTestServer` command fails before Gradle startup because the wrapper ZIP lock is not writable.

Evidence and reproduction: [capture archive](../benchmarks/air-capture-2026-10-20/README.md). Next checkpoint: exterior/coverage admission and revision-safe worker handoff, followed by live scheduling/cost verification and source/state initialization. No live-ready field is claimed by this capture-only checkpoint.

Follow-up, 2026-10-21: [coverage admission and handoff](local-air-coverage-results.md) implements conservative open-sky evidence and revision-safe geometry publication components. Scheduler and invalidation wiring remain separate integration work.
