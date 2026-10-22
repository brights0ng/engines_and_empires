<!-- Written by ChatGPT. -->
# Local air: retained-state source refresh

Follow-up: [door geometry transfer](local-air-geometry-transfer-results.md) now preserves supported door OPEN transitions with edit-time chronology, remapped state, fresh projection, and a separate geometry-energy ledger. The conservative topology limitations below describe this earlier checkpoint.

2026-10-21. This follows the [paced room checkpoint](local-air-scene-results.md). A supported campfire source toggle now retains the existing thermal states, velocity/flux field, elapsed progress, and cumulative energy ledger. It no longer requires full-domain geometry capture, coverage evidence, pressure-topology baking, or uniform-temperature reinitialization.

## Supported runtime path

The server-thread block-change hook accepts only an exact vanilla `CampfireBlock` whose only changed property is `LIT`. Both states must belong to the same block. The changed cell must be strictly inside an already prepared, running diagnostic domain. Loaded-only sampling checks the new cell against the captured occupancy and all solid/fluid material coefficients before accepting its immutable heat data. This supports normal and soul campfires. Arbitrary modded subclasses, driven sources, other block properties, boundary cells, uncertain edits, chunk changes, and data reloads retain the conservative rebuild behavior.

The event records its actual game tick and a monotonically increasing source revision. Workers receive immutable event lists and split physical steps at source-change ticks, alongside the existing thermal-response history. Heat before an extinguish tick remains accounted for; no heat is injected afterward. Several changes at one tick apply in event order without advancing physical time. A same-tick source refresh can publish without taking a physical step or altering any temperature or flux.

The one-cell validation runs in the server's block-change hook. Its cost is outside `snapshotAdvanceMs` and the sliced preparation counters, as other event-hook work already is. This trial does not qualify large bursts of source edits against the per-tick capture budget.

An already running job only knows intervals captured before the edit. Its chronological numerical progress is collected normally, but its old-source frame cannot become visible while newer source changes remain unapplied. Only a frame at the latest source revision is available to readers. Historical frames remain immutable. Geometry invalidation or stopping still retires the run and prevents delayed completion from reviving it.

There are at most 256 pending source events per diagnostic run, within the existing 2,400-tick clock-history/backlog bound. Exceeding admission or encountering an unsupported edit falls back to the existing reconstruction path. This is a diagnostic guard, not a selected production workload. Worker cost remains charged to the shared heat budget. Existing STEP metrics include source-refresh packets, which may perform zero physical steps. End-of-capture reports include the applied `sourceRevision`; source energy remains cumulative rather than resetting to zero when the fire goes out.

## Verification and trial isolation

Six new pure tests cover exact preservation of every temperature and face flux; retained energy during subsequent closed-room cooling; source-boundary splitting across one/two/64-step job caps; response-rate changes and multiple same-tick source events; in-flight stale publication; bounded source history and stopping; and reuse of the exact prepared geometry with material-mismatch fallback. The focused local-air suite passed **56/56**, with no skips.

The ordinary coupled game test now verifies that extinguishing immediately withholds the old field without invalidating geometry. For a result in that same geometry generation, it checks retained warmth/progress and conservation of the cumulative energy ledger. If an unrelated world edit causes a new geometry generation while it waits, it checks the original fallback contract: the rebuilt unlit room has zero source energy. The longer cooling interval is qualified by the isolated scene and pure tests. Door/topology changes still cold-start; this checkpoint does not implement state transfer across them.

The first full check passed aggregate/coreless but the ordinary Meteodyne run timed out waiting for a current source field. Its targeted rerun passed. The asynchronous fixture now yields for 1 ms per test tick so the unthrottled GameTestServer cannot simply race ahead while shared workers wait for CPU. This preserves its assertions and retained-history limit; it is test scheduling, not a measured production tick rate. The separately isolated trial supplies its own actual 50 ms deadlines.

Further full-suite checks exposed why a long continuity interval is unsuitable in the shared test world: comparisons could span different geometry generations, and an attempted bounded reheat loop exhausted its recovery limit under unrelated edits. The final ordinary fixture asserts the source edit itself retains geometry, then verifies the appropriate same-generation or conservative-rebuild contract using one immutable endpoint. Full cooling assertions run in the isolated diagnostic and pure suite. No runtime state transfer or suppression of world invalidation was introduced to make the tests pass. The intermediate failed build summaries are retained for review.

The first extended four-phase run passed its source-off checks, returning a field after three ticks, but later exceeded its bounded timeout in the wind phase. Its capture recorded sensor updates, structure blocks, and other test-fixture edits. The world-wide revision gate correctly restarted preparation for these competing edits. That failed run is recorded in the [evidence directory](../benchmarks/air-source-refresh-2026-10-21/README.md).

The paced fixture now has its own opt-in generator and template namespace, `meteodyne_air_scene`. The Gradle property below copies the existing generated empty structure into build output for this namespace and selects only the diagnostic scene. The ordinary test invocation still runs the entire regular suite, including its coupled cooling test; no normal test is removed. This makes scene timing independent of other fixtures mutating the same server world.

```powershell
.\gradlew.bat :meteodyne:runGameTestServer '-Pmeteodyne.airScene=true' --console=plain
```

Results remain paced headless measurements, not client visual verification or production-scale performance qualification. Initial capture and actual topology changes still have their existing preparation latency. The next state-continuity task is overlap transfer and reprojection for geometry edits, followed by renewed door-transition and larger real-scene qualification.

## Isolated paced measurements

The isolated diagnostic passed **1/1**, containing all four phases in the same 1,008-cell domain. This is additional to the ordinary suite, not a replacement for its tests.

| Phase | Nodes | First available field | Maximum sampled age | Mean STEP wall ms/job | Preparation attempts / bakes at phase end |
| --- | ---: | ---: | ---: | ---: | ---: |
| Closed door, lit | 452 | 131 ticks / 6.55 s | 10 ticks | 3.033 | 1 / 1 |
| Open door, lit | 452 | 129 ticks / 6.45 s | 10 ticks | 1.278 | 2 / 2 |
| Source off, retained state | 452 | **3 ticks / 0.15 s** | 10 ticks | 1.169 | **2 / 2** |
| Open exterior, 2 blocks/s wind | 696 | 345 ticks / 17.25 s | 10 ticks | 0.867 | 1 / 1 (new request) |

Source-off reuse left cumulative preparation attempts and bake count unchanged. The previously observed 11.15-second source-off delay included a recapture interrupted by another edit; this isolated run measures the new refresh path, not an equal-work solver speedup. Available frames stayed within half a second of the server. A geometry edit still incurs the existing delay and cold start.

Source energy was 750 at the end of the open-door heating phase and **753.75 after extinguishing and cooling**. The extra 3.75 accounts for the actual pre-edit tick after the last published hot frame. The source ledger then remained constant throughout cooling, while the monitored air temperature decreased and stored heat stayed in the closed domain. Source-off completion retained 40 cumulative accepted steps, rather than restarting the counter. Steps may be shorter at source/rate boundaries, so 20 further accepted steps need not mean exactly ten physical seconds.

The four capture windows lasted 16.58, 16.43, 9.09, and 27.24 wall seconds. They contained 20, 20, 19, and 20 measured STEP jobs. Maximum accepted divergence was `2.68e-8`; maximum absolute cumulative energy-ledger error was `1.65e-7`. These are short single-run samples with coarse Windows CPU counters; compare availability and state continuity, not small timing differences between phases.

## Final project check

The final `compileJava compileTestJava test runGameTestServer --continue` invocation completed successfully (exit 0). Results: aggregate **262/262**, coreless **211/211**, Meteodyne **95/95**, Frontier **18/18**, lib **3/3**, Sable Fixes **2/2**, Seismic **20/20**, Spoilage **34/34**, and Sparse Ores **13/13** both bare and with Create. The isolated four-phase diagnostic separately passed **1/1**, and all **56 focused local-air tests** passed. IDE compilation also succeeded. The [final build summary](../benchmarks/air-source-refresh-2026-10-21/full-build-summary.log) preserves the unit task outcomes and game-test totals.

Production consumers and live weather forcing remain unchanged. Geometry/material/unknown world invalidations still cold-start. Source-only continuity under supported toggles is implemented; continuity across those invalidations remains a separate task.
