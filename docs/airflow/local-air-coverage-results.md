<!-- Written by ChatGPT. -->
# Local air: coverage admission and geometry handoff

2026-10-21. Follow-up to the [world capture checkpoint](local-air-world-capture-results.md). The prototype now has a conservative exterior-evidence gate and a tested owner/worker geometry handoff contract. These are isolated components, not an enabled live airflow mode or an integration into `HeatWorld`'s scheduler.

## What qualifies as a supported boundary

`LocalAirCoverage.plan(snapshot)` runs without world access. It identifies boundary cells and deduplicates vertical evidence columns. The first supported policy deliberately requires entire boundary air cells to be empty in the captured collision/fluid occupancy model.

| Boundary condition | Admission |
| --- | --- |
| Side air cut | Require the captured cell's column and immediately adjacent outside column to be unobstructed through the exclusive world build ceiling |
| Top air cut | Require the column from that cell through the build ceiling to be unobstructed |
| Bottom air cut | Insufficient coverage; extend below the passage or provide a represented floor/ground |
| Partial boundary cell containing air | Insufficient coverage; enlarge the domain |
| Fully solid/fluid boundary cell | No air cut; retain insulated outer material truncation |
| Fully enclosed room inside the domain | Preserve its physical walls/roof; no weather faces enter that room |
| Obstructed evidence column | Insufficient coverage, including roofs outside the captured volume |
| Missing/unsupported evidence geometry | Distinct failure status; no certificate |

Every evidence cell is sampled from already loaded geometry, without a heightmap or inferred roof label. The scan includes the boundary cell itself and stops before the exclusive build ceiling. Side guard columns are one block beyond the box, not a universal simulation padding distance. Diagonal guard columns are not required because the existing geometry connects across faces.

This policy certifies a narrow **open-sky geometric condition**. It does not prove a universal outdoor wind/temperature reservoir, adequate clearance from distant buildings, or numerical independence from the finite boundary. A roofless courtyard can satisfy this geometric condition while still needing a larger domain to represent surrounding walls. The provisional fixture padding evidence remains provisional. Live domain-size/cost qualification and supported radius selection are still required.

The policy can also conservatively reject a partial boundary cell whose internal air never actually reaches an outer face. Supporting such cuts, overhanging colliders, caves with distant entrances, and selective per-face exterior treatment remains future work. Expand or decline an unsupported domain; never silently close the cut and label the resulting field ready.

## Budgets and revision ownership

`LocalAirCoverage` runs world reads on its owner thread, with a total evidence-cell admission limit, per-slice allowance, and monotonic deadline. It computes the complete required read count before sampling; exceeding the total limit rejects admission without starting the scan. Deduplicated columns scan from their lowest required cell once. Existing per-cell shape read/box limits still apply; a deadline cannot preempt third-party shape code mid-call.

Snapshots now retain a primitive source-context identity as well as geometry/material revisions. `LocalAirWorldSource` assigns a new identity to each source lifetime. Use the same source for geometry, coverage, and the handoff gate. A certificate belongs to exactly one snapshot and records the world build limits. Geometry/material/context changes invalidate it before, during, and after the scan. Build-height changes during coverage or publication also invalidate it. Workers retain neither the source nor world objects.

The future invalidation wiring **must include every evidence column and its shape context**, including cells outside/above the solver box, chunk unload/reload, and data-map reloads. The certificate cannot detect an edit that its caller fails to report in the revision. Source identities and geometry revisions must not be reused for a different world/domain lifecycle.

## Worker handoff

`LocalAirGeometryHandoff` owns no executor and starts no competing simulation manager. Its integration contract is:

1. Worker builds the pure coverage plan from the immutable geometry snapshot.
2. Owner captures the bounded evidence and obtains a current certificate.
3. Owner calls `begin(snapshot, certificate)` to obtain an immutable job; dispatch that job through the existing bounded workers when runtime integration is implemented.
4. Worker bakes flow geometry with open exterior faces and explicit solid/fluid thermal states, then returns an immutable completion envelope.
5. Owner accepts only the active job's completion with current context/revisions/build limits. Replacement, explicit invalidation, and active-job failure clear publication. Older completions or failures cannot overwrite/clear newer jobs.
6. Publication lookup rechecks revisions and withholds geometry after an edit.

Prepared geometry is not a ready velocity/temperature field. No source initialization, elapsed-clock snapshot, state migration, budget accounting, ready-field query API, or instrument behavior is implied. Worker exceptions must be reported to the active job's `failed` hook. Scheduler dispatch, invalidation event wiring, and immutable field publication remain to be integrated with `HeatWorld`.

## Verification

- **161/161 pure tests passed**, none skipped, including 13 new coverage/handoff tests. Controls cover open-sky face counts, column deduplication and slicing, read admission, roof/outside obstruction, bottom/partial cuts, missing/unsupported evidence, enclosed wet geometry, a roofed room inside an admitted domain, source/material/geometry/build-limit epochs, snapshot identity, worker thread ownership, replacement/replay/failure/invalidation, and stale publication withholding.
- Java 21 directly compiles the Minecraft-facing source and game-test class against cached Minecraft/NeoForge dependencies and existing project classes. A new game fixture captures below a roof that lies outside the solver box and requires coverage rejection.
- The required full Gradle command still fails before startup on the wrapper ZIP lock's access error. Game tests have not executed; direct compilation is not clean-build, runtime-cost, complete architecture, or in-game verification.

Evidence: [coverage archive](../benchmarks/air-coverage-2026-10-21/README.md). Next: connect source/domain revisions and evidence footprints to invalidation, then integrate these steps into the existing bounded worker pipeline with source/state initialization. Keep live activation gated on runtime verification and the unresolved calibration/consumer decisions.

Follow-up: the [shared preparation pipeline](local-air-pipeline-results.md) connects these stages to existing heat-worker dispatch, snapshot budgets, conservative world invalidation, and request lifecycle handling. Coupled field/source initialization remains separate work.
