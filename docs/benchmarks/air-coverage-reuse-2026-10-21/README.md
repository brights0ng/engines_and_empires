<!-- Written by ChatGPT. -->
# Coverage-proof reuse evidence

Recorded 2026-10-21. See [the implementation and comparison](../../airflow/local-air-coverage-reuse-results.md).

`before/` copies the unmodified six-phase control reports/trace from `air-weather-paced-2026-10-21/control/`. `after/` contains the unmodified final generated `summary.json`, `pipeline.json` and `trace.csv` after coverage reuse and source-context guards. The sources are the ChatGPT-authored opt-in fixture and existing heat benchmark. `comparison.json` is derived by ChatGPT from their phase snapshots and complete traces, including consecutive unavailable samples. No benchmark result was manually changed.

Both invocations use `:meteodyne:runGameTestServer -Pmeteodyne.airWeatherScene=true --continue --no-daemon --no-parallel --console=plain`. The final after run exits 0, passes its single generated six-phase GameTest, and reports BUILD SUCCESSFUL in 1m 30s (`paced-summary.log`, extracted from `build/air-coverage-reuse-final-paced.log`). Different recorded world origins/climate baselines preclude treating their absolute temperatures as a paired calibration or their packet means as an isolated kernel-speed comparison.

The final comparison shows 160/161-tick opening/closing first-field delay before, six ticks each after; 335 to eight ticks for the combined phase's longest unavailable interval; and maximum published ages reduced from 143/144 to ten ticks. Four transitions reuse 4,960 logical coverage positions. The initial actual sky-read count stays at 1,240 for every later phase. Domain reads/reuse remain 1,017 / 4,023. Changed read quantities can be audited against `snapshotChargedUnits` (7,217 before, 2,257 after). Before reports predate explicit coverage counters; the 1,240 reads per transition are established by charged-unit/domain-read deltas, not invented counter fields.

`focused-summary.log` is extracted from `build/air-coverage-reuse-context.log`: targeted unit/standalone verification exits 0, BUILD SUCCESSFUL in 28s. Eleven new pure tests cover compatible reuse, new certificate identity, context/roof safety, missing evidence, changed requirement spans, identity/material/height/bounds/revision guards, admission/deadlines, negative packing, immutable inputs, queued door/source/weather replay and unsupported source-context events. The new GameTest exercises actual roof/chunk/material invalidation after proof reuse. Standalone Meteodyne passes 98/98. The final full check runs after the final paced invocation; results are recorded below.

The first development unit invocation failed one test because its fake floor moved with the minimum build height, invalidating the whole bottom boundary before coverage could run. The fixture now fixes the floor when varying only metadata; assertions are unchanged. Subsequent unit/standalone checks passed. An earlier after trial also passed with five/six-tick first fields before the final conservative source-context guards; the final run above is authoritative.

These are bounded diagnostic correctness and headless pacing checks. Windows short-packet CPU counters remain coarse. New/uncertain domains still capture full sky evidence, and unknown source-context replay fails explicitly rather than claiming current outdoor coverage. No production consumer, radius, solver tolerance or gameplay calibration is selected here.

## Final full verification

`full-summary.log` records the full required check after all final Java changes and the final paced run:

```text
gradlew compileJava compileTestJava test runGameTestServer --continue --no-daemon --no-parallel --console=plain
```

It exits 0, BUILD SUCCESSFUL in 2m 38s. Aggregate 263/263, coreless 213/213, standalone Meteodyne 98/98, Frontier 17/17, Paradyne Lib 3/3, Sable Fixes 2/2, Seismic 20/20, Spoilage 34/34 and Sparse Ores 13/13 bare and 13/13 with Create all pass. Compilation and unit tasks pass. `local-air-unit-results.json` is extracted by ChatGPT from final JUnit XML: 109 local-air/mapper tests with zero failures/errors/skips. The new real roof/chunk/material regression passes in aggregate, coreless and standalone. No failed final full-check retries or weakened assertions were needed. IDE builds also pass after final Java edits.
