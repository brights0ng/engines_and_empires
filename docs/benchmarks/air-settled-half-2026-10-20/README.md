<!-- Written by ChatGPT. -->
# Half-second settled house calibration

This follow-up retains the original house fixtures adapted from `AirGridTest` (original fixture authorship unmarked), the unlimited 75-unit virtual-air source, calm exterior, 20 C background, response 1, material coefficients, solver settings and long qualification protocol. Only coupled physical dt changes from 1 to 0.5 s. The legacy control continues taking one 1 s step per physical second. Geometry remains the original 24x16x24 calibration domain; this is not the separate padding-16 candidate.

| File | Content |
| --- | --- |
| `equilibrium-half.json` | Three houses, qualified heating windows and 900 s source-off cooling |
| `summary.json` | Derived comparison with historical 1 s calibration and prefix validation |
| `summarize.py` | Standard-library validation/derivation script |
| `tests.log` | Direct Java 21 compilation/JUnit verification |
| `gradle-startup.log` | Required full-project check, blocked before Gradle startup |
| `equilibrium-half.log` | Completed numerical run console output |

Each coupled step contributes a dt-weighted temperature/flux sample to its physical-time window, and participates in within-window extrema. Legacy samples remain at 1 s. Settling requires three consecutive 1,000 s windows at the **unchanged** per-state mean-drift, storage and room-range criteria. Strict instantaneous status independently checks endpoint changes and within-window ranges. It is not substituted for thermal-statistics qualification. The heat cap remains 10,000 physical seconds; an unqualified or failed row is not a passing calibration.

Own-target t95, normalized cooling crossings, and the trailing 3,000 s target mean use 1 s room samples, preserving the historical measurement resolution. No interpolation or half-second precision claim is made for crossing times. Sources are removed only after thermal qualification; both models then cool for 900 physical seconds. Energy and quality accounting include every coupled half-second update.

The harness checkpoints each completed house with root `complete=false`; it writes `complete=true` when the whole batch finishes. Check each row's qualification/failure status as well as the root flag. Original raw archives remain unchanged. `summarize.py` validates window coverage, unchanged thresholds, source energy, physical progress, all shared legacy heating samples against the historical long run, and coupled checkpoints through 600 s against the half-second unity response run.

Normal reproduction:

```text
.\gradlew.bat :paradyne-lib:coupledAirBenchmark -PcoupledAirMode=equilibrium -PcoupledAirEquilibriumProtocol=long -PcoupledAirEquilibriumStep=0.5 -PcoupledAirReport=docs/benchmarks/air-settled-half-2026-10-20/equilibrium-half.json
python -B docs/benchmarks/air-settled-half-2026-10-20/summarize.py
```

The new `coupledAirEquilibriumStep` property defaults to **1.0**, preserving existing equilibrium commands. It is independent of the qualification-step property used by the other modes. This environment uses cached Java 21/JUnit/Gson jars to compile every main/test source in the pure `heat.air` package and run tests/benchmark directly, with `-Xms512m -Xmx1g -XX:+UseG1GC`. Positional benchmark arguments are:

```text
docs/benchmarks/air-settled-half-2026-10-20/equilibrium-half.json equilibrium COMPACT_CACHED true CACHED_TRACE long 0.5 0,8,8,3 0.5
```

Arguments 6/7 are placeholders for other modes; equilibrium dt is argument 8 (zero based). `CoupledAirCalibrationTest` includes a deliberately one-second, unqualified pilot comparing the refined window against an independent two-step numerical reference and the unchanged legacy timing. Its console lines in `tests.log` are expected test controls, not failed long calibrations.

No tuning, runtime integration, full-project/game/client success, or new capacity benchmark is implied. See the [results report](../../airflow/local-air-refined-calibration-results.md).
