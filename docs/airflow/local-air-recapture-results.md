<!-- Written by ChatGPT. -->
# Incremental local-air recapture

Updated 2026-10-21. Known door/source edit histories now reuse unchanged snapshot cells. In a paired paced 1,008-cell room trial, the first mapped door field returned in **5 ticks (0.25 s)** for both opening and closing, versus **133 / 130 ticks (6.65 / 6.50 s)** immediately before this change.

## What changed

The previous pipeline reread every domain cell at the diagnostic allowance of eight cells per tick. A 1,008-cell room therefore spent roughly 126 ticks on domain capture alone, even for two changed door halves. The worker bake and pressure solve remain complete rebuilds in this checkpoint.

`LocalAirWorldSource` now records an immutable packed-coordinate footprint for every successful domain-cell sample, including collision/fluid context reads. `LocalAirGeometryCapture` can clone a compatible baseline snapshot and resample only explicitly edited cells, cells whose footprint intersects those edits, and cells without read evidence. Unknown evidence defaults to sampling. Snapshot arrays, edit sets, and footprints remain owned/immutable; no world reference is introduced into worker work. Coordinate packing is independent of Minecraft runtime classes and checked against actual `BlockPos` in GameTests.

The baseline comes from the exact preceding sealed topology seed. The controller supplies the complete classified door edits and source cells changed since that baseline was captured, including source refreshes before the first door transition. Same-tick replacements merge their edits; different-tick stages retain their own historical views. This prevents an old snapshot's lit campfire from being restored after extinguishing it.

Bounds, context, material identity and revision guards remain. Unclassified relevant edits, chunk uncertainty, material reloads and unsupported history still take the existing conservative paths. A fresh domain still captures all cells. Coverage planning, sky evidence, baking and fresh pressure projection are unchanged; this is not a claim that an unchanged boundary automatically has valid sky evidence.

Actual sample attempts remain capped by the existing cell allowance. Reuse scanning and array copies are included in the shared capture wall-time measurement/deadline, rather than counted as world samples. The deadline remains soft for atomic operations and allocations. Footprints add bounded per-cell metadata (at most the existing 128 block reads per sample).

## Paced comparison

Same opt-in room, explicit 20 C ambient/zero room wind, fixed step caps, and a 50 ms tick deadline in both invocations. Each invocation passed its five-phase assertions. This is one paired headless trial, not an estimate of client FPS, production MSPT, or a latency guarantee under arbitrary load.

| Operation | Before: first field | After: first field | After: recaptured / reused cells |
| --- | ---: | ---: | ---: |
| Open both door halves | 133 ticks / 6.65 s | 5 ticks / 0.25 s | 2 / 1,006 |
| Close after extinguishing | 130 ticks / 6.50 s | 5 ticks / 0.25 s | 3 / 1,005 |

Opening first-field delay fell about 96.2%; closing fell about 96.2%. The third closing sample is the campfire state changed since the open topology's snapshot. Source-only refresh needed no geometry recapture. The first mapped frame still represents the edit tick, so it initially has five ticks of age; subsequent sampled frame ages stayed within ten ticks. Warmth, progress, source revisions, transfer counts, fresh projection and cumulative energy accounting passed throughout.

Maximum absolute ledger error after the change was below 3.5e-7; maximum divergence across all phases was below 3.6e-8. Absolute source/geometry ledger values differ between paced invocations because their actual edit/step boundaries differ; deterministic serial-versus-delayed equivalence remains covered by unit tests.

Fresh cold-start room fields took 145 ticks before / 134 after, and the new open-wind request took 346 / 345 ticks. Both paths still fully capture their domain and required coverage. Their timing differences are not presented as optimization gains.

## Verification and counters

Five new incremental capture tests cover full-capture equivalence, context-dependent neighboring cells, absent evidence, quotas/deadlines, stale revisions, incompatible baselines and immutable inputs. The repeated-edit timeline tests now exercise reuse across worker caps 1/2/64: three transitions need eight extra samples and reuse 367 cells in the 125-cell fixture. An additional source-before-door regression prevents stale source resurrection.

`captureReadCells` counts cumulative domain-cell sample attempts; `captureReusedCells` counts cumulative reused domain cells for the request, including work from canceled attempts. These are not individual block lookup counts, sky-evidence reads, or event-hook source validation costs. Explicitly replacing the request resets the counters. The paced CSV and reports include them.

The IDE builds and final full project check passed. All 88 local-air/mapper tests passed without skips, as did aggregate 263/263, coreless 212/212, standalone Meteodyne 96/96 and every other standalone suite. The full invocation exited 0 on its first run; no failed game-test retries were needed. Results are recorded with [the evidence](../benchmarks/air-recapture-2026-10-21/README.md). An earlier focused run exposed a runtime-class dependency in coordinate packing; the helper was corrected before the successful focused, paced and full checks.

## Remaining scope

This qualifies the supported room-door path. Full recapture remains for new/unknown domains and uncertain edits, and open-sky coverage can still dominate those requests. Geometry/material replacement, domain resizing, live weather/driven forcing, consumer integration and production activation remain separate work. No pressure/thermal solver optimization or production default changed.
