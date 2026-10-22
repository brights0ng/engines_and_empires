<!-- Written by ChatGPT. -->
# Repeated local-air edits during reconstruction

Updated 2026-10-21. Supported door OPEN edits on different ticks now retain their intermediate topologies instead of forcing a cold start when another rebuild is pending. Verified campfire lit/unlit changes can also arrive during reconstruction. This remains an explicitly requested diagnostic simulation.

## Chronological contract

`LocalAirPreparation` owns an ordered queue of topology stages. The old run seals at the first edit tick, including any short physical tail and earlier source events. Each later stage captures, checks coverage, bakes, initializes from that sealed frame, reprojects pressure, and advances only to its next edit boundary. Heat, compatible face motion, cumulative source/exterior/geometry energy, response history, and accepted progress carry through every transfer. The final topology catches up to observed time. Intermediate geometry and fields remain unpublished.

Same-tick door halves coalesce into one topology, including when initialization or source work was already dispatched. Replacement uses the original incoming immutable seed and retains same-tick source revision events. An opaque stage ticket rejects retired work without exposing a world-bearing stage to workers. There is still one physical worker slot, retained until collection even when work is canceled.

## Historical world reads

A historical `LocalAirWorldSource` view fixes the geometry revision and remembers the block state before each subsequent server-thread edit. Shape and fluid context queries use the same overlay as direct cell queries. This supports a context lookup that happens for the first time after a later edit; replaying only the directly changed door's baked shape would not establish that guarantee.

The views do not eagerly copy a domain. Capture and sky-evidence reads remain sliced under the existing shared snapshot budget, loaded-only, and owner-thread-only. Worker work contains immutable snapshots and solver state, with no world-bearing stage reference. Material tables retain their independent revision fence.

Every server-thread block edit is remembered while historical reads remain open, even outside the currently sampled footprint: a future shape read might consult that position. Any chunk event or unrecorded off-thread block edit during that window conservatively invalidates retained history. Coverage completion releases each view's block-state retention; ordinary dependency-based invalidation then applies unless another queued view is still reading. Unsupported relevant edits, material reloads and uncertain world changes retain the cold-start fallback.

## Bounds

- At most 16 queued topologies, including the old topology, and 256 admitted edit events per pending reconstruction interval.
- At most 256 remembered changed block positions per historical view. Overflow invalidates the view and routes through conservative revision fallback rather than silently using a future block state.
- The existing 2,400-tick chronological catch-up limit and per-job physical-step cap remain. Topology/event admission overflow fails explicitly; missing retained time is not skipped. A completed historical stage can continue running past two minutes when its actual backlog remains within the limit.

This adds bounded event-hook bookkeeping outside the sliced snapshot counters, as earlier source-only refresh already did. Bursty edit-hook cost and rapid-edit rebuild latency have not received a paced performance qualification.

## Verification

The focused tests compare delayed reconstruction with serial processing across worker caps 1, 2 and 64, including partial capture, three door topologies, extinguishing/relighting, and a response-scale change. They check identical energy, progress, air temperature, velocity and face flux; the source ledger is 132.5 and elapsed thermal time 14.15 s in that controlled fixture. Other tests cover same-tick replacement with old source work still outstanding, topology/source admission limits, unknown revisions, close, and continued healthy operation beyond the retained-history window.

Real-world controls check historical fluid height against a changed above-block context, bounded history recording, and retention release. A new shared-pool GameTest opens a two-block door, extinguishes the fire, closes the door, relights, and reopens on separate ticks before reconstruction finishes. It requires all three transfers and both source revisions, retained warmth/progress, a connected final room, and bounded divergence/energy error. Its 1 ms cooperative tick yield is an asynchronous test scheduling allowance, not a real-time TPS measurement.

The IDE builds passed. The full project check exited 0: aggregate 263/263, coreless 212/212, standalone Meteodyne 96/96, every other standalone suite, and all unit tasks passed. After the final lifetime-guard correction and strengthened tests, all 82 focused tests and the 96/212 Meteodyne/coreless suites passed again. The aggregate pass precedes that final follow-up. See [the evidence](../benchmarks/air-history-2026-10-21/README.md) for commands and scope; no failed game-test retries were needed for this checkpoint.

## Next

Incremental recapture can now address actual reconstruction cost without erasing intermediate topology history. The earlier approximately 6.45-second paced single-door measurement is not a new result for this queue: rapid edits can add reconstruction work. General material replacement/resize continuity, live forcing, gameplay consumers and production activation remain later work.
