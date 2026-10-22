<!-- Written by ChatGPT. -->
# Local air: shared preparation pipeline checkpoint

2026-10-21. The [capture](local-air-world-capture-results.md) and [coverage/handoff](local-air-coverage-results.md) components now run through `HeatWorld`'s existing snapshot and worker scheduling when an internal diagnostic request explicitly enables one domain. There is no automatic demand, player command, configuration default, source initialization, or coupled solver activation in this checkpoint.

## Integrated behavior

`HeatWorld.requestLocalAirGeometry(bounds, cellLimit, coverageLimit)` admits one explicitly bounded domain and replaces any prior request. `LocalAirPreparation` advances geometry capture, worker-side coverage planning, owner-side evidence reads, and worker-side flow/thermal geometry baking. Publication returns prepared geometry only, never a ready temperature/velocity field. Each new request starts with no publication.

Capture and evidence reads share the existing **2 ms soft snapshot deadline and 256 work-unit allowance** with legacy preparation. At most eight cell units per tick go to the requested local-air domain; priority alternates each tick. Existing legacy units represent rows/columns, while local-air units represent sampled cells, so their work counts are not equal-cost measurements. Neither path can preempt a foreign shape call mid-call.

Planning and baking use the existing **EAE Heat pool**, not another executor. The experimental job counts toward the existing `threads * 2` dispatch limit and takes at most one physical slot per world. Thermal and local-air dispatch alternate first access to available slots. All completed experimental worker wall time, including stale/retired work and failures, is deducted from the existing `budgetMs` balance and included in `usedMs`. The opt-in benchmark records `local_air_worker_ms` separately. This follows the existing completion-based accounting; it does not reserve a worst-case runtime at submission or establish a hard per-job time limit.

The worker closure retains only an immutable work packet and completion queue. Snapshots, plans, and bake jobs contain no world/source references. The owner collects results, advances admission, and uses the existing revision-safe geometry handoff. Third-party runtime exceptions during owner capture fail the experimental request without escaping into the live tick; failed requests retry after their inputs change.

Invalidation may allow a replacement capture to start while the old worker finishes, but **it does not free the old physical slot**. The replacement cannot submit another job until the old result is collected. Replacing or clearing the requested domain likewise retains the retired slot for accounting. Replayed, foreign, closed, or superseded completions cannot publish geometry. This prevents edit churn from accumulating an unbounded experimental worker queue.

## World invalidation and lifecycle

Only active diagnostic requests register an atomic world epoch. Every block edit reported by the existing chunk mixin increments that epoch **before legacy relevance classification or off-thread marshalling**. Chunk load/unload hooks also increment it, including early off-thread event notification. This intentionally invalidates on all reported edits in the active Overworld, covering the solver box, outside/above evidence columns, and arbitrary shape-context neighbors without relying on a guessed halo.

`HeatTables`' existing reload revision is read directly by the source. Lookup and completion checks withhold geometry immediately after geometry/material/context/build-limit changes, before the next periodic plan. The next budgeted tick restarts preparation. Missing or unsupported inputs stay rejected until an epoch changes; they do not retry every tick unchanged.

Clear/replacement, `forgetRegions`, disabled heat, and server stopping retire the diagnostic domain and remove its epoch registration. Normal pause still pauses scheduling. Retired running work cannot revive its publication. No controller or world event mutation runs on a worker.

This is deliberately conservative invalidation. Unrelated edits anywhere in the active world may repeatedly restart capture; busy-world completion rate and cost are unmeasured. Changes that bypass the existing block/chunk hooks are not covered. Moving-ship transforms and automatic region merge/split/demand are not integrated. The open-sky policy still does not prove adequate finite-domain padding or distant-obstacle accuracy. Narrower dependency tracking and live radius/default decisions remain evidence-driven follow-ups.

## Verification and next step

- **171/171 pure tests pass**, none skipped: ten new preparation lifecycle tests plus the previous 161. They cover all preparation stages, publication withholding, physical-slot retention, stale bakes, reload/retry behavior, failure containment, close/replacement, worker ownership without world reads, and admission limits.
- Direct Java 21 compilation passes for `HeatWorld` and all seven `LocalAir*` main/game-test files against cached Minecraft/NeoForge dependencies and existing project classes.
- A new isolated game-test batch exercises actual shared-pool publication, real outside/above block-edit invalidation, chunk lifecycle hooks, material reloads, rebuilds, and explicit clear. **It has not executed.** The required full check still fails before Gradle startup because access to the wrapper ZIP lock is denied. Pure tests verify the controller; direct compilation does not verify event ordering, scheduler fairness, mod architecture, or live cost.

Evidence: [pipeline archive](../benchmarks/air-pipeline-2026-10-21/README.md). Next is explicit source/temperature initialization and bounded coupled stepping on the prepared geometry, followed by field-quality diagnostics and live scheduling measurements. Preserve the reported calibration difference and keep consumer/gameplay decisions separate. The live thermal solver remains the production path.

Follow-up: [cold-start coupled diagnostics](local-air-runtime-results.md) adds immutable static-source snapshots, explicit initialization, bounded stepping, and diagnostic field frames through this pipeline. Dynamic forcing, state continuity, and production consumers remain separate work.
