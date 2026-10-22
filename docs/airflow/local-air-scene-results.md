<!-- Written by ChatGPT. -->
# Local air: isolated aggregate tests and a paced room trial

Follow-up: [retained-state source refresh](local-air-source-refresh-results.md) now supports verified campfire toggles without rebuilding, qualifies actual cooling, and separates the paced fixture from competing regular game tests. The cold-restart measurements below remain the preceding checkpoint's results.

2026-10-21. This follows the [operator diagnostics checkpoint](local-air-diagnostics-results.md). Aggregate game tests now use a generated `build/game-test` directory. Their mod sync contains only declared `colonyRuntime` dependencies, with a configuration-cache-compatible verification task checking the resulting jar names. Top-level client additions in `libs/` no longer enter this run. The existing played client/server setup is unchanged.

## Reproduce the room trial

```powershell
.\gradlew.bat :meteodyne:runGameTestServer '-Pmeteodyne.airScene=true' --console=plain
```

This extends the existing coupled game test rather than replacing or skipping its ordinary test coverage. Without the property, its original 125-cell fixture runs. The larger variant has a longer bounded timeout and restores its block edits and diagnostic request on success or failure. It writes phase captures and a compact CSV into `meteodyne/build/reports/air-scene`.

The fixture is a **12 × 7 × 12 block domain: 1,008 cells**, with an internal stone partition, an actual two-block oak door facing across the partition, and a campfire. Each phase completes at least 20 physical steps. Ambient/initial temperature is explicitly 20 C, the thermal response scale remains 1, the maximum physical step is 0.5 s, and each worker packet allows at most two steps.

1. Closed door and lit campfire: assert two disconnected air components and positive source energy.
2. Open door: assert immediate withdrawal of the old field, rebuilding, one connected component, and positive source energy.
3. Extinguished campfire: assert immediate withdrawal, rebuilding, and zero source energy.
4. Remove the roof and east/west outer walls, then explicitly request 2 blocks/s eastward wind: assert admitted sky coverage, a connected air domain, and nonzero wind velocity.

Every phase also checks finite stored energy, absolute energy-ledger error below `1e-5`, and maximum accepted divergence below `1e-7`. The open-boundary fixture is placed 14 blocks below the build ceiling to bound the real sky-evidence scan. This is a diagnostic fixture, not an ordinary ground-level house or a qualified exterior-padding choice.

GameTestServer normally runs ticks without waiting. Only this opt-in fixture adds a 50 ms deadline to its game-test callback, targeting 20 TPS. These are real world capture, shared-worker, and publication measurements on a paced headless server. They do not measure whole-server MSPT, client FPS, player interaction, or the earlier roughly 110,000-cell reference scene. Callback pacing is included in capture wall duration, so enclosing heat-tick timing is not an unbiased production overhead measurement.

## Measured results

The extended standalone run passed **95/95 game tests**, including all four phases. Raw [phase captures and CSV](../benchmarks/air-scenes-2026-10-21/README.md) are archived separately from the earlier 125-cell smoke measurements.

| Phase | Air nodes | STEP jobs / accepted steps | Mean STEP wall ms/job | p95 STEP wall ms/job | p95 queue ms | First field delay | Maximum sampled frame age |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| Closed door, fire lit | 452 | 20 / 20 | 3.073 | 5.618 | 0.070 | 129 ticks / 6.45 s | 10 ticks / 0.50 s |
| Open door, fire lit | 452 | 20 / 20 | 1.289 | 1.533 | 0.033 | 129 ticks / 6.45 s | 10 ticks / 0.50 s |
| Fire extinguished | 452 | 20 / 20 | 0.442 | 1.007 | 0.044 | 223 ticks / 11.15 s | 10 ticks / 0.50 s |
| Open roof/sides, eastward wind | 696 | 19 / 20 | 0.937 | 2.957 | 0.163 | 345 ticks / 17.25 s | 11 ticks / 0.55 s |

Capture windows lasted 16.49, 16.44, 21.14, and 26.79 wall seconds. Jobs can contain fewer than the two permitted steps, and the final wind run used one two-step packet. These are short single-run samples with JIT/order effects, not a comparison attributing all cost differences to the door. CPU counters returned zero for many sub-15.625 ms jobs on this Windows host; zero measured CPU is not zero computational work. Wall timings are more informative for this trial.

Maximum accepted divergence was **2.68e-8**; maximum absolute cumulative energy-ledger error was **1.65e-7**. Closed-room phases had zero exterior energy. Lit phases each injected 750 solver energy units over ten simulated seconds; source-off phases injected zero. No worker failures or discarded results were reported in the four end-of-phase summaries.

The source-off phase incurred an additional revision invalidation during capture: attempts increased from two after the door phase to four after extinguishing, while the geometry revision advanced from two to four. Its first-field delay includes restarting that partial capture; the trace does not attribute the extra edit to a particular block. The new wind request resets request counters: it consumed 2,744 charged capture/evidence units and completed one preparation attempt. The first three phases share cumulative counters and must not be summed as independent phase totals.

## Interpretation and next implementation step

Published-frame age and time without any published frame are different quantities. The trial records the first publication delay separately from the maximum sampled age of available frames. A fast solver can meet the latter while capture/rebuild misses the two-second availability direction.

The eight-cell snapshot slice makes even this 1,008-cell domain require about 126 capture ticks before planning, baking, initialization, and publication. Source-only changes currently recapture geometry, and both source and door edits discard accumulated heat. The source-off phase therefore verifies a cold restart, **not cooling of the previously warmed room**. True retained-wall/air cooling cannot be qualified through this runtime path yet; the earlier offline cooling evidence does not remove that gap.

The next implementation step is immutable source refresh on unchanged geometry while retaining worker-owned thermal and velocity state. It needs energy-ledger continuity, chronological source-change intervals, and stale-result rejection tests. Topology edits then need explicit overlap/state transfer and reprojection. These are prerequisites for credible heat-up/cool-down and door-transition trials; production consumers remain unchanged.

## Verification

The full `compileJava compileTestJava test runGameTestServer --continue` invocation completed successfully. Game-test results: aggregate **262/262**, coreless **211/211**, Meteodyne **95/95**, Frontier **18/18**, lib **3/3**, Sable Fixes **2/2**, Seismic **20/20**, Spoilage **34/34**, and Sparse Ores **13/13** both bare and with Create. The extended paced run separately passed **95/95**. IDE compilation of the diagnostic helper also passed.

`verifyGameTestMods` passed twice, with the second invocation explicitly reusing the configuration cache while executing both the sync and verification. The generated test directory contained the six declared colony dependency jars and no Serene Seasons/client shader additions. No assertions were removed or weakened to resolve the aggregate failure.

The archived full-build summary is the record for this invocation. Shared unit-test XML outputs were rewritten after the build completed, so this checkpoint deliberately does not derive a new unit-test inventory from that later snapshot. Compilation and unit-test tasks in the recorded Gradle invocation succeeded; no client visual verification or large production-scene performance qualification is claimed.
