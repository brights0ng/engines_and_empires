<!-- Written by ChatGPT. -->
# Sparse automatic air domains

The automatic runtime now executes irregular unions of complete 16-block tiles. An L-shaped three-tile group uses 12,288 cells rather than its 16,384-cell bounding box. Missing tiles have no captured geometry, air nodes, material storage or numerical state. Touching tiles still share one pressure/thermal owner and one physical face at each seam.

This extends [retained layout transfers](local-air-layout-transfer-results.md). It remains an explicitly enabled trial under the existing `air plan` and `air auto` commands; production consumer activation and selected defaults are separate work.

## Addressing and numerical storage

Paradyne Lib's immutable `CellLayout` represents either the existing dense box or a compact set of full tiles. Sparse cell IDs follow canonical tile order and tile-local cell order. An allocation-free primitive tile lookup maps spatial positions to represented cells; omitted positions return -1. Complete rectangles automatically select the existing dense layout and retain its traversal order.

`TiledAirGeometry.joinSparse` builds actual-cell shape/material arrays and a common flow/thermal geometry. The earlier rectangular `join` entry point retains its rejection contract. Six neighbors per represented sparse cell are cached during worker-side geometry construction. Pressure and thermal matrix loops still traverse flat node/face arrays; they do not look up tiles during each numerical sweep. Wind-directed thermal traversal sorts actual cells by spatial octant only when its cached direction changes, including air, solid and stationary-fluid states.

Capture and admission count actual cells, and preparation iterates their coordinates. Owner-thread layout construction only sorts tile metadata; per-cell geometry and neighbor construction remain worker work. Node/face guards, the 32,768-cell per-owner limit, combined capture allowance, shared worker lane and total reservation accounting remain in force. Rectangular domains do not allocate the sparse neighbor or spatial-order arrays.

## Holes, boundaries and dependencies

A hole is not evidence of outdoor air. Coverage planning examines every missing neighbor, including concave cuts inside the envelope. Whole-air cuts still require the existing bounded sky-column evidence and exterior-adjacent evidence; partial boundary air still rejects. Every exposed downward air cut still requires moving coverage into solid ground. No automatic boundary expansion is added to satisfy those checks.

Point queries in omitted cells return `OUTSIDE_DOMAIN`. Chunk and block invalidations test actual tile ownership, while explicitly recorded shape/context/sky reads remain dependencies even outside represented cells. The experimental uniform weather anchor uses the active tile center nearest the envelope center for sparse domains, avoiding weather reads anchored in an omitted tile. Lattice sampling also visits represented cells only and retains spacing anchored to the domain minimum; this does not connect the manual renderer to automatic owners.

## Retained changes

Layout transactions use actual tile overlap/face contact and actual world-cell ownership. Different masks with the same envelope are distinct layouts. Sparse-to-dense, dense-to-sparse and sparse layout changes retain unchanged overlapping air/material/fluid temperatures through the existing common-tick barrier and fresh projection. Donors with overlapping envelopes are permitted only when their represented cells are disjoint. Removed cells remain an explicit outgoing energy term, not a saved thermal archive.

Old and replacement allocations must fit the same total allowance throughout preparation. Invalid or unadmitted replacements retain the old owners. Existing common-tick source chronology, all-target atomic commit, energy checks and cancellation remain unchanged.

## Verification

Four new pure kernel tests cover compact cell/face mapping and both L-shaped seams; eight wind octants with mixed solid/fluid storage compared against an independently enumerated active-cell geometry; two 16-cubed tiles separated by a million tile coordinates in both horizontal axes, with only 8,192 allocated cells; and retained transfer from disjoint donors whose envelopes overlap. The huge-envelope fixture performs a coupled step without allocating or walking the omitted volume. Comparisons use existing strict pressure/thermal acceptance settings.

Three capture/coverage/dependency tests check actual-cell reads and allocations, negative origins, concave interior boundary rejection, unsupported downward cuts and context-dependent invalidation outside represented cells. Automatic lifecycle coverage now includes heated sparse growth to a rectangle and dense-to-sparse shrink with balanced retained energy. A lattice fixture also samples the huge sparse envelope without visiting holes.

The new live GameTest creates a connected L-shaped stone-lined passage with a campfire in each arm, in isolated loaded chunks. It gives the automatic runtime exactly 12,288 cells, verifies publication after four accepted coupled steps, one connected heated component across both seams, current readings in all arms, unchanged pressure acceptance and no reading in the missing tile. It restores its blocks and only the chunk tickets it added. Its tick scheduling allowance makes it an integration check, not a latency benchmark.

Verification on 2026-10-27: IDE build passed. Focused tests passed (`build/air-sparse-focused.log`). All Paradyne Lib and Meteodyne unit tests and all 105 standalone Meteodyne GameTests passed (`build/air-sparse-integration.log`). The complete `gradlew compileJava compileTestJava test runGameTestServer --continue --no-daemon --no-parallel --console=plain` check passed in 4m 34s (`build/air-sparse-full.log`): 403 aggregate, 353 coreless, 105 standalone Meteodyne GameTests and every other standalone run. Unit results contain 1,542 tests, zero failures/errors and the two existing optional benchmark skips; all six architecture checks passed. No existing tests were removed or newly skipped.

## Remaining gates

Only complete aligned tiles are supported; partial build-height tiles remain deferred. Conservative coverage can still reject an indoor cut, so arbitrary planner shapes are not guaranteed admission. General driven block-entity forcing, natural-source initialization, thermal persistence outside active coverage, production consumer integration, overload/multiplayer qualification and default activation remain outstanding. No client visual or representative multiplayer performance test was performed for this change.

Follow-up: the [live driven-source checkpoint](local-air-driven-source-results.md) now adds budgeted block-entity power observations and chronological retained forcing, including door/layout handoffs. Its source and geometry admission limits still apply; the other gates above remain outstanding.
