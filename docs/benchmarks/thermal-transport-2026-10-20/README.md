<!-- Written by ChatGPT. -->
# Shared-flux thermal transport checkpoint

Three separate Java 21 JVMs, each with five warm-up and ten measured updates per case. These are transport-only costs on a fixed pressure-projected wind field (6 m/s x, 2 m/s z). Every iteration restores the same 60 C interior pulse against a 20 C background and initially 20 C material, outside the timed section. The coupled temperature/buoyancy sequence, sources, snapshots, pressure work, and publication are absent. These are not comparisons of house-warming calibration.

Runtime: Temurin 21.0.12.1+1-LTS, Windows 11 AMD64, six available processors, AMD64 Family 25 Model 97 Stepping 2, 512 MiB initial/1 GiB maximum heap. Reports retain JVM arguments, geometry fingerprints, timings, allocations, sweep counts, residuals, and accepted/rejected counts. CPU timer resolution is coarse on this Windows JVM. The three archived files were generated from the checkpoint source with the direct Java compiler/runtime because Gradle daemon connectivity was unavailable.

## Results

**360 of 450 measured updates passed.** All 0.25/0.5/1/2-thermal-second cases passed in every fork; all 90 ten-second measurements were rejected. Another 225 warm-up updates are excluded from those measured counts, including 45 rejections. Geometry fingerprints agree across the three forks for each scene.

One thermal second per transport update; median of three fork means:

| Scene | Wall ms/update | Range of fork means | CPU ms/update | Maximum sweeps |
| --- | ---: | ---: | ---: | ---: |
| Multiroom 32³ | 52.07 | 51.41–53.83 | 51.56 | 67 |
| Stress 48³ | 204.21 | 203.66–208.61 | 203.13 | 75 |
| Partial-block house | 15.96 | 15.84–17.34 | 15.63 | 76 |

All measured updates allocated 72 bytes (the result record), with no allocation per cell/face. Thermal geometry plus sparse solver construction allocated about 17.65 MB at 32³, 60.42 MB at 48³, and 4.78 MB for the partial-block house. These allocation counters include temporary setup objects and exclude preexisting flow geometry/pressure state; they are not retained-heap measurements. Construction took roughly 18–20 ms, 12–15 ms, and 2–5 ms respectively; these are unwarmed individual setup measurements and not comparable warmed latency distributions.

For the ten-second cases, the 512-sweep cap was reached despite maximum unresolved temperature changes already below `1e-9 C`. Their final absolute energy-budget errors were approximately `1.11e-8`, `5.41e-8`, and `1.92e-8 C*air-cell`, respectively, against the fixed `1e-8` acceptance tolerance. Their median wall costs were 405/1346/107 ms; **these are failed-solve costs, not successful update timings**. Previous temperatures were retained. Small residuals alone did not waive the energy check. Large-interval roundoff and convergence criteria need further assessment; this result does not demonstrate physical instability or qualify ten-second thermal updates.

The current 48³ transport cost alone exceeds the plan's approximately 100 ms CPU/real-second direction at one thermal second per real second. The intended two thermal half steps also require a direct coupled benchmark; timings above must not be presented as the complete stage 3 update. Further numerical/performance work is required before live admission or default selection.

## Reproduce

Normal Gradle entry point (one fork):

```powershell
.\gradlew.bat :paradyne-lib:thermalTransportBenchmark -PthermalTransportReport=build/benchmarks/thermal-transport/fork-1.json
```

Repeat as three separate JVM invocations with distinct report filenames. Set equivalent heap/JVM options when comparing these reports. The benchmark records rejections and includes their timing; inspect `accepted`, `rejected`, and `warmupRejected` before interpreting a row. Unit verification is `:paradyne-lib:test`; the project-wide check remains the command in `docs/PROJECT.md`.

Implementation/verification limitations and next work: [checkpoint results](../../airflow/local-air-thermal-transport-results.md).
