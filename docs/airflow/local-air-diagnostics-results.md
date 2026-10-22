<!-- Written by ChatGPT. -->
# Local air: operator diagnostics and server timing checkpoint

Follow-up: the [paced larger-room trial and aggregate isolation](local-air-scene-results.md) resolves the Serene Seasons test leak described below and records door/source/wind timing. The original smoke measurements and failed-build evidence here remain historical.

2026-10-21. The [cold-start diagnostic](local-air-runtime-results.md) now has operator controls, point readings, field quality/freshness reporting, and real server-side scheduler timing captures. It remains an explicit fixed-forcing experiment; production thermal readers and instruments are unchanged.

## Controls and readings

All controls require operator permission level 2, under `/meteodyne heat air`:

| Command | Behavior |
| --- | --- |
| `status` (or just `air`) | Preparation/run status and reason, physical job presence, rebuild attempts, exact frame age/backlog, solver quality, capture work, shared budget balance, and per-stage timing totals/maxima |
| `sample [position]` | Temperature and the world x/y/z velocity vector at the supplied point; defaults to the command source's exact position |
| `stop` | Retire the experiment and withhold its field; running work retains its slot until collected |
| `start <min> <max> <ambient> <windX> <windZ> <cellLimit> <coverageLimit> <steps>` | Request one bounded cold-start domain with explicit forcing and work limits; `<min>` and `<max>` each contain three inclusive block coordinates |

No radius, weather input, or step-count default is selected by `start`. The command has hard entry guards of **32,768 cells**, **1,000,000 coverage evidence reads**, horizontal wind components within ±100 blocks/s, and **1–64 accepted steps per job**. Caller-specified limits can be lower. These are diagnostic admission guards, not qualified production radii or a universal memory/time guarantee. Invalid/oversized bounds are rejected before replacing an existing request; capture still never loads chunks.

For an already prepared, closed 5×5×5 test room whose outer floor/walls/roof occupy `0 64 0` through `4 68 4`, with a campfire inside:

```text
/meteodyne heat air start 0 64 0 4 68 4 20 0 0 125 0 2
/meteodyne heat air status
/meteodyne heat air sample 2.5 66.5 2.5
/meteodyne heat air stop
```

The zero coverage-read limit is appropriate only because that example's boundary has no air cut. An open domain needs the existing open-sky evidence scan and a sufficient explicit read limit. Missing geometry, roofed/partial cuts, boundary sources, and unsupported driven sources still withhold the field.

Sampling distinguishes **AIR**, **OCCUPIED**, **OUTSIDE_DOMAIN**, and **NO_FIELD**. Unavailable samples have no numeric air reading; they never masquerade as calm air or ambient temperature. Coordinates select the actual represented air pocket, including negative world coordinates. Readings carry their frame tick and exact age. No production freshness threshold, head-height convention, or gauge/redstone behavior is chosen here.

## Quality and timing semantics

Each immutable frame adds accepted-run high-water divergence, absolute pressure residual, thermal residual, and absolute per-step energy error; cumulative pressure iterations and thermal sweeps; and the signed energy ledger error `stored - initial - source - exterior`. Initial projection is included. Failed candidates remain unpublished, with the flow/pressure failure reason available in status. Older frames retain their own quality and values.

Per-request worker counters distinguish **PLAN**, **BAKE**, **INITIALIZE**, and **STEP**, including completed, failed, and discarded results. They accumulate across rebuilds; replacing/stopping the request drops its counters. Discarded work is still charged to the existing world budget. Wall time and dispatch-to-worker-start latency are distinct; the latter includes enqueue/handoff overhead. CPU totals include only supported measurements and report their sample count, rather than interpreting unavailable CPU counters as zero. CPU timing on this Windows host is coarse (the smoke totals occur in 15.625 ms increments).

Snapshot advance timing is owner-thread work inside `advance`, including refresh work done there; it is not all event, status, planning, or publication overhead. Charged cell units include a conservatively consumed allowance on a failed foreign shape call. Timings overlap their enclosing heat tick and must not be summed as independent costs.

The existing `/meteodyne heat benchmark start [seconds]`, `status`, and `stop` commands now export separate `local_air_<stage>_wall_ms`, `_queue_ms`, and available `_cpu_ms` series, capture-local job/failure counts, and a compact `localAirAtEnd` status/quality/timing summary. Stage series require dispatch and collection in the same capture. The broader `local_air_worker_ms` collect-time series may include jobs dispatched earlier. Legacy `worker_*`, raw job rows, and their CPU fraction remain legacy-only. The end-of-capture request totals may span more than the capture window. No field arrays or unbounded request event history are exported.

## Actual server smoke measurements

The coupled game fixture now starts a bounded timing capture before preparation and stops after at least 20 accepted steps. It checks that the written JSON contains actual STEP timings, field availability, and quality. The scene has **125 block cells, 27 air nodes, one campfire, a closed stone boundary, initial/ambient 20 C, zero boundary wind, and a cap of two steps per job**.

| Run | Accepted steps | STEP jobs | Mean worker ms/job | p95 worker ms/job | p95 queue ms | Frame age at capture end |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| Standalone Meteodyne | 20 | 11 | 1.678 | 6.040 | 1.138 | 3 ticks |
| Coreless combined | 20 | 12 | 3.060 | 6.754 | 0.464 | 7 ticks |
| Aggregate | 20 | 10 | 2.243 | 8.308 | 3.660 | 39 ticks |

Some jobs finish a shorter captured interval, so a two-step cap does not imply exactly two steps per job. Maximum accepted divergence across these runs was **2.25e-9**, below the unchanged 1e-7 limit. Maximum absolute cumulative energy ledger error was **2.49e-8** in the solver's energy units. All three captures completed with usable diagnostic fields.

These are **accelerated headless GameTestServer smoke captures**, with only 10–12 measured STEP jobs per run. Capture wall windows were about 0.044–0.113 seconds; p95 equals the maximum at these small sample counts. They verify instrumentation and real dispatch/collection, not sustained 20-TPS fairness, player-scene latency, large-domain performance, or a production budget/radius. Do not compare their 125-cell costs directly with the earlier large-house benchmarks. A representative multi-region client/server trial remains necessary.

## Verification and remaining blocker

- **190/190 focused pure tests pass**, none skipped, including six new diagnostic/metrics tests. They cover availability and coordinate/age semantics, quality/energy reporting and immutable history, timing ownership/CPU availability, actual executor stage classification, stale cost accounting, and failed initialization.
- Full project compilation and unit tests ran through the IDE host environment: **1,254 passed, two existing benchmark tests skipped**, zero unit failures/errors (1,256 discovered).
- All standalone game runs pass: Meteodyne **95/95**, Frontier **18/18**, lib **3/3**, Sable Fixes **2/2**, Seismic **20/20**, Spoilage **34/34**, and Sparse Ores **13/13** both bare and with Create. Coreless combined passes **211/211**. This includes command permission/admission/stop tests and the previously unexecuted local-air capture, coverage, worker, and coupled fixtures.
- The exact required full check was attempted and then repeated with `--continue` to collect the remaining run results. The final aggregate is **261/262**, failing only `thegametestserverhasnoseasons` with “no seasons: no provider, no calendar.” Its log shows Serene Seasons loaded from the aggregate `run/mods` folder. The initial aggregate attempt also failed a forecast-temperature check; that check passed on the final attempt. These are existing climate assertions outside this patch; the aggregate is **not green**. No protected runtime files or assertions were changed to make it pass.
- Direct shell Gradle still fails loading native services (and earlier wrapper attempts failed lock access). IntelliJ's Gradle launch and a hidden IDE-host PowerShell full-check launcher successfully execute the build. The earlier reports' game-test limitations have therefore been superseded by this verification.

Evidence and reproduction: [diagnostics archive](../benchmarks/air-diagnostics-2026-10-21/README.md). The next gates are aggregate season-provider isolation and a representative large-scene live capture. Dynamic weather/driver updates, retained-state continuity, radius selection, and production consumers remain separate implementation work.
