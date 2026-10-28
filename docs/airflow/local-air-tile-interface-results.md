<!-- Written by ChatGPT. -->
# Shared tile pressure and heat interfaces

This follows the [automatic tile planner](local-air-planner-results.md). The first numerical seam implementation assembles complete rectangular tile groups into one existing `FlowGeometry`/`ThermalGeometry` pair. One `CoupledAirGrid` then owns pressure, velocity, thermal storage, sources, and rollback for the entire group. Internal tile cuts are ordinary shared faces, with one signed flux and the same pairwise heat transport used elsewhere in the domain.

This checkpoint proves the two-tile contract before introducing sparse numerical storage. It does not activate automatic live domains, change production consumers, or qualify arbitrary planner unions for execution.

## Implementation

`paradyne-lib/.../heat/air/TiledAirGeometry.java` accepts immutable tile inputs containing shapes and separate solid/fluid material arrays. Local cell order is `(z * edge + y) * edge + x`. Tile keys are lattice coordinates; block origin equals key times edge. The numerical flow coordinates remain relative to the joined origin, exposed by `originX/Y/Z`.

Assembly requires identical tile edge and capture context/geometry/material versions, unique tile ownership, a complete rectangular envelope, representable block coordinates, and an explicit cell allowance. It rejects missing, disconnected, or irregular unions instead of filling their envelope with invented air. The provisional runtime tile edge is 16; the pure assembler supports 1..32 for small deterministic controls as well. Cell allowance is not a fluid-node memory or CPU guarantee.

Shapes/material arrays are copied at tile construction. The joined result has an immutable local-cell mapping for preparation-time source/material routing, plus cached seam face IDs. The solver loops retain their existing flat arrays and need no tile hash lookup. `openExterior` applies only at the joined envelope, never at an internal cut; prescribing an exterior velocity on a seam face is rejected by the existing boundary API. Solid/fluid storage and conduction also connect across the cut, including interfaces with no air passage at all.

These are numerical assembly checks, not world coverage evidence. A live adapter must still establish coherent capture, loaded geometry, ground/partial-boundary admission, sky/exterior evidence and actual node/face/memory limits before calling this an executable domain. The pure API does not query chunks or load world objects.

## Comparison evidence

The dense reference is built separately from the physical fixture, without the tile flattening/mapping code. Tests compare node/face connectivity, capacities and conductances, then evolve the assembled and reference domains with identical settings. This qualifies assembly and seam ownership; it is not an independent validation of the underlying pressure/thermal equations.

| Fixture | Evidence |
| --- | --- |
| Two 16-cubed air tiles, 32 x 16 x 16 joined box | 8,192 cells, 256 interior seam faces, one connected air component; exterior-face count matches the single box, with no extra seam surfaces |
| Initial 80 C pulse beside the seam, other air/exterior at 20 C, wind 1.5 blocks/s, buoyancy disabled | After eight 0.25 s steps, receiving cell on the other side reaches 24.9208186118 C; maximum temperature/flux differences from dense control are both exactly zero; final energy-ledger difference is -8.73e-11 in solver energy units |
| Mixed partial solid/fluid geometry across each of X/Y/Z seams, both wind directions | Six controls, eight 0.25 s coupled steps each; all state temperatures, face fluxes and pressure results match the dense construction; observed maximum temperature/flux differences are zero |
| Sealed heated room spanning an X seam, warm material storage, calm exterior | Twenty-four 0.25 s coupled steps; one air component, no exterior faces; final sum of absolute seam flux is 0.2685049864 cell-volume/s, while signed net flux is -1.16e-14 cell-volume/s |
| Matching half-open seam versus complementary closed half openings | Matching patches retain half-area faces; complementary openings produce no seam faces and retain two disconnected air components; solid conduction remains available where material patches meet |
| Fluid-fluid and fluid-solid contacts across each seam orientation | No invented air node or face; one thermal contact matches the two-capacity backward-Euler solution and conserves insulated energy |
| Solid source on tile edge | Local source cell maps once into global geometry; the other tile is a neighbor. Existing total-power/per-face semantics give respectively 12/72 power units for the six-face fixture |

Across the six small mixed controls and the sealed-room control, maximum observed divergence is 2.23e-11 (existing limit 1e-7), maximum thermal row residual is 5.54e-11, and maximum accumulated energy-ledger difference is 9.00e-9 solver energy units. Existing convergence/energy acceptance settings are unchanged. These short controls do not requalify long-term calibration, live performance, or multiplayer capacity.

Eight unit tests also cover negative-coordinate origins, input order independence, immutable input copies/results, unique cell mapping, duplicate/missing/irregular inputs, all three capture-version mismatches, edge mismatch, coordinate overflow, and explicit cell-limit rejection. Numerical details are printed into `TiledAirGeometryTest`'s test XML; fixture source remains the reproducible evidence.

Verification on 2026-10-26: IDE build passed. All eight focused `TiledAirGeometryTest` unit tests passed (`build/air-tile-focused.log`). The full `gradlew compileJava compileTestJava test runGameTestServer --continue --no-daemon --no-parallel --console=plain` check passed in 3m 24s (`build/air-tile-full.log`): 401 aggregate, 351 coreless, 103 standalone Meteodyne, and every other standalone GameTest run. The complete unit suites, including the new tile tests and six architecture checks, passed. No in-game visual or live multi-domain performance trial was performed.

## Next gate

Follow-up: the [automatic runtime checkpoint](local-air-automatic-runtime-results.md) connects compatible planner groups to the existing live admission/simulation pipeline and adds actual node/face allocation guards. [Retained layout transfers](local-air-layout-transfer-results.md) and [sparse assembly](local-air-sparse-results.md) now extend this initial rectangular contract to irregular full-tile unions. The original rectangular `join` entry point keeps its rejection contract; live irregular groups use `joinSparse`.

Bridge planner groups to bounded live capture and coverage admission, with one simulation owner per admitted connected group. This assembler intentionally cannot run irregular unions or partial-height tiles: add sparse geometry/storage or explicitly defer unsupported layouts without allocating empty bridges. Preserve retained warmth during resize/merge/split, and guard worker publication against superseded layouts. Automatic source forcing, consumer integration, persistence and production activation remain separate gates.
