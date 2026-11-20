<!-- Written by ChatGPT. -->
# Retained rectangular layout transfers

This extends the [automatic runtime](local-air-automatic-runtime-results.md) with transactional rectangular growth, shrinkage, merge and split. Retained world cells keep their air-pocket, solid and stationary-fluid temperatures. Compatible face motion is mapped by world position, and every replacement builds and projects its own pressure operator. This report records the rectangular checkpoint; the later [sparse-domain checkpoint](local-air-sparse-results.md) extends the same transaction to irregular tile unions.

## Handoff contract

The coordinator finds changed planner groups and the existing owners they overlap or face-touch. All related replacements form one transaction; distant unchanged owners keep running. Target capture, conservative exterior/ground coverage checks, and tiled geometry baking happen through the existing shared budgets. Pending targets cannot initialize from ambient or publish a field while awaiting their donor seed.

Once every target geometry is admitted and donors have valid runs, all donors seal at one game tick. Bounded jobs finish through exactly that tick, including a short tail and chronological source/weather/response-rate changes. Immutable checkpoints become available only after outstanding jobs complete. Targets then initialize from those checkpoints at the same tick and seal there while their new pressure projections finish.

Ownership switches only after **every** target initializes successfully and the energy check passes. Donors close and all targets become committed on the server thread together. Targets then replay the recorded weather/rate history through preparation time; elapsed simulation time is not skipped. No old and new owner both advance sources beyond the barrier. New owners start new per-owner energy/progress counters; retired source history is accumulated once by the coordinator rather than copied into each split child.

Plan supersession, relevant main-thread block edits, changed capture generations, rejected coverage, failed initialization or excessive lag cancel the entire transaction. Old owners resume from their barrier and replay retained history. Main-thread edit cancellation precedes the existing campfire/door handling, allowing that handling to retain state normally. Unknown off-thread edits/material/chunk changes retain the prototype's conservative invalidation/cold-rebuild policy. No successful target can publish alone while another replacement fails.

Closed targets with physical jobs in flight keep their reservation until collection, and late completions cannot publish. All targets use the existing physical local-air job slot, completion accounting, preparation allowance and executor.

## Mapping and energy

`DomainTransfer` in Paradyne Lib maps disjoint donors with different origins/sizes. Input temperature/flux arrays are copied. Overlapping donor ownership is rejected; the coordinator also produces disjoint replacement domains. Unchanged cell shapes and storage capacities are required by the mapper, and the runtime additionally proves identical overlapping shape/material snapshots and table revision. Air components are not mixed merely because their flat indices change. Solid and fluid states retain separate storage.

Only faces with compatible world positions, component labels and interior/exterior ownership retain velocity. An old exterior is not copied into a new internal outlet, and an internal cut cannot simply become an unprojected exterior. Newly represented capacity starts at the target's explicit ambient temperature; new boundary conditions are reconciled by projection.

The transfer record contains barrier tick, generation, donor/target counts, old/new represented energy, incoming/outgoing energy, and error:

```text
new represented energy = old represented energy + incoming - outgoing
outgoing = old represented energy - retained overlap energy
error = new represented energy - retained overlap energy - incoming
```

The absolute commit error limit is 1e-6 in existing solver energy units; pressure/thermal acceptance settings are unchanged. Energy is Celsius-based and signed, matching the existing solver ledger. Shrink/split capacity leaving coverage is an explicit outgoing term. **This is not an archive of removed warmth:** thermal persistence outside represented coverage and inactive-region aging remain future work. The guarantee here is retained overlap plus explicit volume-change accounting, not simulation or saving of the entire previously visited world.

## Limits and inspection

Existing `air plan` and `air auto` commands drive transfers automatically. `air auto` now reports committed owners separately from total preparations, roles (`COMMITTED`, `RETAINED`, `TARGET`, `RETIRING`), transfer phase/count and the last energy record. The live benchmark's automatic report includes the same layout state and record.

Old and replacement buffers must fit **the same total cell allowance** during preparation. A transaction without headroom is deferred as `TRANSFER_HEADROOM`; it does not discard donors to fit a replacement. The configured domain limit applies to the final committed owners; up to that many temporary target preparations may coexist, with all their cells still charged to the existing cell allowance. Per-domain 32,768-cell and exact node/face guards remain. Inactive unrelated owners still occupy their reservations; no eviction policy is added here.

Each replacement must independently pass existing boundary coverage checks. Shrinking through a continuing indoor air passage can therefore reject and retain the old owner, while cuts into solid ground/material or verified exterior can qualify. The implementation does not weaken those rules to force a planner layout to fit.

## Verification

Five pure mapper tests cover translated growth in all axes, negative origins, merges and splits with exact volume-energy accounting, separate partial air/solid/fluid storage, face compatibility and fresh projection, immutable donor copies, duplicate ownership and invalid retained cells/target identity.

Automatic lifecycle tests exercise real capture/bake/coupled jobs for multi-owner merge, split and subsequent growth, heated overlapping cells, common-tick commit, source history counted once, temporary headroom, plan/edit cancellation, invalidated work and late completion discard. A failed split replacement cancels its initialized sibling and resumes the original donor without skipping source time.

The live integration fixture now adds a source in a neighboring tile after initial publication, observes a retained growth transfer with the distant owner unchanged, then extinguishes it and waits for demand grace to expire. It verifies a retained shrink, current overlapping reads and balanced incoming/outgoing energy records. The fixture retains its independent chunks and normal tick scheduling allowance; it is not a multiplayer performance benchmark.

Verification on 2026-10-26: IDE build passed. Focused mapper/runtime tests and the extended live fixture passed (`build/air-layout-focused.log`, `build/air-layout-integration.log`). The complete `gradlew compileJava compileTestJava test runGameTestServer --continue --no-daemon --no-parallel --console=plain` check passed in 4m 26s (`build/air-layout-full.log`): 402 aggregate, 352 coreless, 104 standalone Meteodyne GameTests and every other standalone run. Unit suites passed, including five new mapper tests, all eight automatic lifecycle tests and six architecture checks. Production instruments, manual rendering, save formats and activation defaults are unchanged; no client visual or multiplayer performance trial was performed.

Follow-up: [irregular/sparse coverage](local-air-sparse-results.md) now executes nonrectangular demand unions without empty bounding-box bridges. General source forcing, initialization/thermal persistence outside active coverage, production consumer integration, scheduling qualification and default activation remain outstanding.
