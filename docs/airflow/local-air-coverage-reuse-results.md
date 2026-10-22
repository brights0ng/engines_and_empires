<!-- Written by ChatGPT. -->
# Reusing admitted sky-coverage evidence

Updated 2026-10-21. Supported interior door/source histories can now reuse unchanged open-sky evidence. Fresh domains still capture their full sky proof. This targets the approximately eight-second door delay measured in the [paced weather trial](local-air-weather-scene-results.md).

## What changed

Each admitted column retains its proven lower height and a conservative bounding box of all block reads used to establish emptiness through the world ceiling, including collision/fluid shape context. Missing or empty footprint evidence disables reuse. Bounding boxes can cause extra recapture for edits between actual dependencies; they cannot omit recorded dependencies. Metadata is constant size per column, rather than a list for every block through the sky.

The next coverage plan is still rebuilt from the new immutable geometry snapshot. A prior column can be reused only when it spans the entire new requirement, all recorded edits miss its dependency box, and bounds, source context, material identity, build heights and revision ordering are compatible. New/lower requirements and affected/unproven columns are sampled normally. Bottom/partial-air rejection and the full coverage-admission limit remain. Reuse does not allow a domain that exceeds its configured evidence limit.

Proofs pass only from an exactly sealed predecessor to the next chronological topology. Same-tick replacement carries the original predecessor and merged edits. Certificates remain bound to their own new snapshot and revision; an old certificate cannot directly admit or publish a new snapshot. Immutable proof records contain no world objects, and workers still perform no world reads. Completed mutable coverage state and obsolete incoming certificates are released.

Coverage reuse is request scoped. The existing monotonic dependency tracker retains the block/context/column/chunk reads registered during the original proof. Relevant unknown edits, chunk uncertainty, material reloads, changed source identity or build heights take the conservative rebuild path. The real server regression checks an above-domain roof obstruction after reuse, along with relevant chunk hooks and material reloads; each withholds the field and recaptures instead of trusting stale proofs.

A source cell with unchanged local geometry can still be read by a distant boundary shape. Source-only refresh therefore also requires proof that its position misses complete sky-context evidence. A rejected immediate refresh takes the existing world-hook invalidation path. If such an unsupported event was already queued before proof capture, chronological replay fails explicitly and requires a restart; it cannot publish a field through an unverified exterior.

## Budget and counters

Actual coverage sample attempts still consume the existing eight-unit per-request allowance and shared snapshot deadline. Reuse scans also check that deadline but consume no world-read units; proof scans and allocations remain within the measured preparation path, whose deadline is soft for atomic operations.

Reports add cumulative `coverageReadCells` and `coverageReusedCells`. Reads count actual coverage sample attempts; reuse counts currently required sample positions established by a compatible column proof. Neither is a count of individual block/context lookups. Canceled attempts remain in these request counters, while an explicit replacement request resets them. Domain snapshot counters retain their previous meanings.

## Verification

Eleven new pure tests cover full reuse/new certificate identity, roof/context invalidation, missing evidence, lower column requirements, incompatible identities/heights/bounds/revisions, quotas/deadlines, negative coordinates, immutable edit sets and exact serial/delayed histories at worker caps 1/2/64. They also exercise source-context invalidation and explicit failure of unsupported queued events. One new real GameTest verifies cache reuse followed by roof/chunk/material invalidation. Existing weather-scene assertions now additionally require all later phases to preserve the initial actual sky-read count.

The final IDE build, six-phase paced trial and full project check pass. Final unit evidence contains 109 local-air/mapper tests without failures/errors/skips; aggregate passes 263/263, coreless 213/213 and standalone Meteodyne 98/98, with all other standalone suites also green. No final full-check retries were needed. Exact invocation scope and paced comparison results are recorded with [the evidence](../benchmarks/air-coverage-reuse-2026-10-21/README.md).

## Paced comparison

The final six-phase trial passed with the same 1,008-cell building, forcing schedule, two-step cap and explicit 50 ms tick deadline used before this change. The invocations use different recorded world origins/climate baselines, so their absolute temperatures and work-packet mixes are not a controlled calibration or solver-speed comparison. Numerical, revision and retention assertions pass independently in both.

| Phase | Before: first field | After: first field | Before available samples | After available samples |
| --- | ---: | ---: | ---: | ---: |
| Cold start | 283 ticks | 285 ticks | 201 / 484 | 201 / 486 |
| Warm gust + open door | 160 ticks / 8.00 s | 6 ticks / 0.30 s | 51 / 210 | 201 / 206 |
| Cold reversal + close/source off | 161 ticks / 8.05 s | 6 ticks / 0.30 s | 41 / 201 | 204 / 210 |
| Gusts + two door/source changes | 1 tick | 1 tick | 27 / 365 | 170 / 210 |
| Calm, fixed geometry | 2 ticks | 1 tick | 201 / 202 | 209 / 210 |
| Gust every 10 ticks, fixed geometry | 1 tick | 1 tick | 189 / 210 | 189 / 210 |

Opening/closing first-field delay falls about 96%. Both later door transitions also retain their chronological source and weather events. The combined edit phase's first sample precedes its scheduled edits; its more useful measures are availability (7.4% to 81.0%) and longest missing sequence (335 to 8 ticks, 16.75 s to 0.40 s). The sustained gust-only phase remains 90% available, with a one-tick gap per sampled wind update. This optimization does not change that publication guard.

Initial capture still reads 1,008 domain cells and 1,240 coverage cells. Across all four door transitions, the after trial reads only nine further domain cells, reuses 4,023 domain cells, and reuses 4,960 required sky positions without another sky sample. Total charged snapshot units fall from 7,217 to 2,257. Fresh cold-start latency is unchanged within this trial's tick variation; no cold-start improvement is claimed.

Maximum represented age in the final trial is ten ticks (0.5 s), including the first fields after door edits, versus 143/144 ticks in the before door phases. Available age and missing intervals remain distinct measurements. The final trial retains four geometry transfers and three source revisions; maximum absolute cumulative ledger error stays below 2.61e-7 and divergence below 2.09e-8. West/east inflow direction and actual post-source-off cooling pass.

Completed coupled packets average 1.64 ms wall time (p95 3.83 ms, maximum 14.98 ms). All 146 completed local-air packets total 304.67 ms over 76.60 s of heat capture. Heat-pipeline game-thread time averages 0.070 ms/tick (p95 0.161 ms); local-air snapshot advancement averages 0.0083 ms/tick. These are nested pipeline measurements, not whole-server MSPT or client FPS. The primary evidence is avoided reads and reduced waiting; no kernel arithmetic, accuracy, physical step or production setting changed.
## Remaining gate

This preserves the existing conservative outdoor-admission rule; it does not choose a universal numerical radius, resize/merge policy or production reading convention. New/uncertain domains still require full capture. Consumer freshness and unavailable-state behavior remain a separate decision before player/instrument integration.
