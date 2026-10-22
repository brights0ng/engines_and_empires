<!-- Written by ChatGPT. -->
# Local-air dependency invalidation

Updated 2026-10-21. Explicit diagnostic domains now retain their geometry and warm field across unrelated block and chunk activity. This replaces the previous world-wide block/chunk revision bump; it does not activate production airflow consumers.

## Contract

`LocalAirDependencies` protects every cell in the requested bounds immediately. `LocalAirWorldSource.LoadedView` additionally registers every block/fluid lookup before accessing the loaded chunk, including collision-shape context, fluid-height context, coverage samples, and failed chunk lookups. Open-sky evidence therefore protects its sampled columns through the world ceiling, including the immediate outside columns. It is not sufficient to ignore everything outside the domain bounds.

The footprint uses conservative inclusive vertical intervals per sampled X/Z column, plus the chunks containing those reads. Dependencies accumulate for the entire request lifetime, across rebuilds and rejected captures: cached shapes and outstanding old-generation work may still depend on earlier reads. A later edit between two sampled heights may invalidate conservatively. A chunk event affects the request if that chunk intersects the domain or contains a recorded dependency, even when the changed block would otherwise be unrelated.

Bookkeeping is capped at 65,536 external/sample columns. Crossing that limit switches the request to conservative world-wide block/chunk invalidation and releases the footprint maps. Clearing/replacing the diagnostic request discards its footprint. No world or mutable Minecraft object is sent to a worker.

Both server-thread and off-thread hooks consult the thread-safe footprint. Relevant off-thread changes bump the revision before legacy work is marshalled to the server. Material-table revisions retain their independent invalidation path. Relevant edits still withhold stale geometry/fields immediately, and old worker results cannot publish after a relevant revision change. The verified campfire source-refresh and door OPEN transfer paths retain their existing contracts.

## Verification

Six new dependency unit tests cover negative coordinates and chunk boundaries, external column intervals, bounded fallback, off-thread edits, stale worker completion, and admitted sky certificates. Existing game tests now check:

- A distant block edit preserves the exact warmed frame and prepared geometry.
- Unrelated block and distant chunk hooks preserve the publication, while a domain edit, its chunk hooks, and material reload still rebuild.
- Real water-height sampling records context above the domain.
- A rejected roof obstruction remains watched so removing it can invalidate the failed attempt.

The IDE build passed. All 75 local-air/geometry-transfer unit tests passed (69 local-air tests, including the six new dependency tests, plus six mapper tests), with no failures or skips.

The first full `compileJava compileTestJava test runGameTestServer --continue` invocation exited 1. Compilation and all unit tasks passed, as did aggregate 262/262 and the unaffected standalone suites. Coreless and standalone Meteodyne each hit the pipeline fixture's 400-tick wait for its initial worker publication; standalone Meteodyne also failed the coupled fixture's existing cold-fallback energy assertion. That latter failure's exact cause was not established and must not be described as a proven scheduling issue.

The pipeline fixture now yields 1 ms per test tick, matching the existing coupled fixture's allowance because GameTestServer otherwise runs ticks without the normal 50 ms wait. Its 400-tick timeout and all assertions remain intact. The coupled assertion retains its contract and now prints source/geometry/material values on failure. These are test scheduling/diagnostic changes, with no solver or runtime relaxation. The targeted `:meteodyne:runGameTestServer :coreless:runGameTestServer --continue` rerun passed 95/95 and 211/211 respectively, exiting 0. All suites therefore passed across the initial check and targeted retry; the coupled failure did not recur.

Evidence: [`air-dependencies-2026-10-21`](../benchmarks/air-dependencies-2026-10-21/README.md). This checkpoint is not a clean first-attempt full-suite pass.

## Remaining scope

This removes unnecessary rebuilds. It does not shorten the actual door reconstruction, qualify a new rebuild latency, queue different-tick intermediate topologies, resize domains, or change production thermal readers. The previous approximately 6.45-second paced door rebuild measurement still describes the earlier trial; this checkpoint makes no new performance claim. Repeated edits during reconstruction and incremental recapture remain next.
