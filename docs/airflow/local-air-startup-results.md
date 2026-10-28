<!-- Written by ChatGPT. -->
# Local air: startup timing and retained preparation

Status: implemented and verified 2026-10-27.

Bright observed roughly 5-10 seconds before the manual live viewer became available and requested startup measurement, less scheduling wait and more reuse before increasing budgets. This checkpoint adds those measurements and optimizations. It does not claim that every newly visited area is now immediate.

## Request timing

Manual `air status`, automatic status rows and existing benchmark summary snapshots now include a startup report:

- Request-to-first-publication wall time, its owner-observed game tick and the actual field-through tick.
- Time until the field is less than one physical update interval (10 ticks) behind the observed game tick. This is a diagnostic catch-up measurement, not a new production freshness default.
- Elapsed phases: capture, boundary planning, coverage proof, geometry bake, thermal/driven inputs, natural refinement, layout wait, initialization and catch-up.
- Worker active wall time, executor queue time and completed-work waiting for owner collection.

Phase elapsed times partition the startup timeline. Worker/queue/collection times explain parts of that timeline and must not be added to phase totals. Natural input waiting, admission/layout waiting and chronological catch-up remain visible. Automatic replacement publications are counted only after the layout barrier is released at ownership commit. A private projected target is not reported as public availability.

Reports freeze after the first caught-up publication; later edits retain their existing revision/restart/transfer metrics. Failed startup remains explicitly missing. A later reconstruction can resume a stopped startup clock. Renderer delivery still runs every ten ticks and is outside these solver-publication timings.

## Measured scheduling delay and change

A paced pure-Java pipeline fixture uses a 9x9x9 house with natural heat, 20 TPS owner scheduling, one asynchronous worker, the existing 256-read/2 ms snapshot allowance and an initial +8 C thermal estimate. The fixture is not Bright's live world and differs from the earlier legacy-derived accuracy fixture.

| Controlled run | First publication | Caught up | Worker active wall | Completion collection wait |
| --- | ---: | ---: | ---: | ---: |
| Original 256-unit estimate slices | 8.357 s | 8.557 s | 0.710 s | 7.373 s |
| More work within the same 8 ms slice | 4.461 s | 4.553 s | 0.620 s | 3.456 s |
| Prepaid continuations, shared 0.25 wall-core allowance | 2.753 s | 2.815 s | 0.667 s | 1.695 s |

These are individual characterization runs, not repeated capacity measurements. JVM warmup and timing variability affect worker costs. The baseline's work rate fits the same allowance; it had no competing legacy jobs. The continuation harness explicitly accrues, reserves, debits and refunds shared credit. [Raw measured output and a final full-check repeat](../benchmarks/air-startup-2026-10-27.md) preserve the evidence outside build-local logs. The final repeat reached a caught-up field in 2.402 s.

Natural preparation now permits up to 4,096 numerical work units within an 8 ms soft slice, rather than yielding at 256 even when time remains. Setup is included in that deadline. Pressure/coupled steps and short relaxation groups remain atomic and can overrun it.

The dispatcher can reserve up to 50 ms of already available shared wall credit for a natural-preparation continuation group. It debits that reservation before submitting, then refunds unused credit or charges an overrun on collection. Every short slice requeues behind other executor work; the same physical local-air lane remains occupied, with one mutable solver owner. A group has a hard 64-slice cap. Volatile cancellation is checked between slices. No world objects enter the continuation, and no extra pool/thread is created.

Only natural preparation uses this continuation path. Ordinary live stepping, planning and baking retain their dispatch behavior. Existing thermal jobs still use the same pool and allowance. Metrics distinguish owner collection groups from numerical slices and report the maximum slice wall time; a group can legitimately contain more than 8 ms of active work. Existing numerical acceptance, energy checks, source histories, response scaling and natural qualification criteria are unchanged.

## Reusing moving coverage

Automatic resize/merge/split targets can borrow immutable evidence from current live donors:

- Overlapping captured cells, excluding dirty source/context footprints.
- Proven empty sky columns whose full recorded read bounds remain valid.
- Compatible per-phase temperatures for thermal initialization, avoiding saved/legacy lookups for overlap.

A lease checks actual world identity, donor lifetime, geometry/material revisions and snapshot identity. Donor dependencies remain registered during target preparation. Reused read footprints are imported into target dependencies, including chunks and external shape/sky context, so they remain protected after donor retirement. World/chunk/material changes, cancellation and unsupported coverage retain their conservative invalidation behavior. Proof and snapshot objects never acquire worker world access.

Live temperature copies are preparation inputs, not a final transfer barrier or disk restore. The actual common-tick layout seed still wins for all overlap; it preserves current warmth and energy accounting. Incoming cells still use the existing saved/legacy/natural initialization policy. Natural-only state remains transient. Live donor cells are counted separately from disk restoration.

Capture arrays are allocated lazily in the budgeted preparation path, avoiding a second allocation when donor evidence is attached. Actual new world/input reads consume the existing work allowance. Cached copies retain the same shared deadline, including dependency registration; neither snapshot allowance nor server CPU budget is increased.

An independent shifted-domain control measured **27 -> 9 geometry reads** and **650 -> 186 coverage reads**, with 464 proven sky positions reused. The cold and reused geometry/material controls match. Tests also cover source dirtiness, world/material mismatch, donor retirement, external dependency/chunk invalidation and revision changes during sliced capture.

The real automatic growth fixture expands from 8,192 to 12,288 cells after ordinary block edits. It measured **4,097 geometry reads / 8,191 reused cells**, and **4,096 thermal input reads / 8,192 reused cells**. The one additional geometry read refreshes a source changed since the donor snapshot. Its measured startup was **3.861 s**, including about 1.967 s capture, 1.477 s thermal inputs and 0.315 s layout wait. An intermediate geometry-only reuse run took 7.283 s, with 4.512 s in thermal inputs. These headless paced observations are scene-specific; their assertions check retained ownership, reduced reads, energy and projection quality rather than imposing a noisy wall-time threshold.

## Verification

The final required command `gradlew compileJava compileTestJava test runGameTestServer --continue --no-daemon --no-parallel --console=plain` passed in **4m 17s** (`build/air-startup-verified.log`): **407 aggregate, 357 coreless and 109 standalone Meteodyne GameTests**, plus every other standalone run. Unit reports contain **1,579 tests**, zero failures/errors and the same two optional benchmark skips. All six architecture checks and IDE compilation passed. Tests exercise phase/catch-up clocks, frozen immutable reports, actual paced startup, continuation fairness/cancellation/cost accounting, overlap/sky/temperature reuse and real automatic growth after block changes. Existing independent natural-equilibrium accuracy tests remain intact. No production numerical tolerance or existing assertion was weakened.

## Remaining gates

The manual live diagnostic still starts from uniform temperature and still needs fresh geometry/sky evidence on its first request. Its first-ever startup is not made faster by donor reuse, and the natural-initializer speedup does not apply to that manual cold-start path. Its new phase report can identify the cause in Bright's specific scene.

The live growth measurement still spends several seconds in budgeted capture/input work. Sustained running, turns/teleports, several separated players, larger natural scenes, missing chunks and bounded overload remain unqualified. More owners share the same total allowance; these results do not promise 2.8-second multiplayer startup. Ahead-of-player preparation, longer-lived read caches, reduced rebuild cost and further scheduling policy need measurements before selection.

No production consumer or default activation changes were made. The automatic trial remains opt-in.
