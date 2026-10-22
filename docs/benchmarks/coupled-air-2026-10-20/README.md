<!-- Written by ChatGPT. -->
# Offline coupled airflow/heat evidence

`performance-fork-1.json` through `performance-fork-3.json` are separate Java 21 JVM runs of the complete two-way half-thermal/flow/half-thermal sequence. Each case starts with uniform 20 C air/material, weather wind (6,0,2) m/s, a 75-unit air source capped at 65 C, five evolving warm-up steps, and ten evolving measured steps. Scenes match the preceding transport/flow fixtures' block geometry. Fingerprints agree across all three forks for each scene. The partial fixture retains its experimental thin occupied shape; it is not a live door snapshot.

Runtime: Temurin 21.0.12.1+1-LTS, six available processors, AMD64 Family 25 Model 97 Stepping 2; G1, 512 MiB initial/1 GiB maximum heap. The direct Java compiler/runtime was used with locally cached dependencies because Gradle could not access its wrapper lock. CPU counters have coarse Windows resolution.

## Performance

All **450 measured updates and 225 warm-up updates passed**, as did all 45 initial projections. Tested physical-step/response-scale pairs: `(0.25,1)`, `(0.5,1)`, `(1,1)`, `(1,0.5)`, `(1,2)`. Maximum observed accepted-step divergence was `9.698e-8 / second`; maximum combined two-half energy error was `1.986e-8 C*air-cell` (each thermal half retains its `1e-8` acceptance tolerance). No tolerances were relaxed. Other response scales and long intervals are not qualified by this run.

One physical second, response scale 1; median of fork means:

| Scene | Wall ms/update | Fork-mean wall range | CPU ms/update | Thermal / pressure / velocity ms | Max pressure iterations / thermal half sweeps |
| --- | ---: | ---: | ---: | ---: | ---: |
| Multiroom 32³ | 106.79 | 104.52–118.69 | 104.69 | 69.04 / 19.90 / 16.74 | 44 / 43 |
| Stress 48³ | 411.60 | 404.65–442.14 | 409.38 | 247.07 / 100.09 / 59.34 | 60 / 44 |
| Partial-block house | 27.03 | 26.82–29.66 | 26.56 | 18.50 / 3.46 / 4.94 | 31 / 43 |

Phase medians are independent and do not have to sum to the total median. Total includes rollback-buffer copies, checks, and complete accepted updates; phase counters have narrower boundaries. Measured allocation at the one-second/scale-1 setting was 336 bytes/update for fixed result/quality records, with no per-cell/face allocation in the numerical step. Construction/initialization allocation counters exclude already-created immutable thermal/flow geometry and include the pressure operator/factor and temporary setup arrays; they are not retained-heap measurements.

Every case evolves from its own initialization. Different timestep/response settings therefore sample different thermal/flow histories and physical durations, not identical restored fields. These results are a first coupled cost/quality measurement, not an isolated algorithm-speed comparison. The 48³ workload alone exceeds the approximately 100 ms CPU/real-second direction, before world preparation/publication and other regions. A finite valid solve is not proof of live capacity.

## House comparison

`calibration.json` is one deterministic run of sealed, single-window, and missing-wall variants of the existing 10x10x5 stone house with six blocks of surrounding space. Layout/materials and the virtual air campfire match `AirGridTest`; its original test/control is not edited. Both paths receive the same 75-unit unlimited fixture source. This deliberately compares the existing calibration fixture; it is not a full runtime source-table/shape adapter test.

Each model heats for 600 seconds and cools for 80 after source removal. All 2,040 coupled steps passed. The coupled boundary is pressure-open to exterior air, while legacy outer cells are fixed offsets; heuristic exposed losses/rise/opening behavior remain only in the legacy control. No experiment here isolates each model change or establishes the new steady state.

See [the checkpoint comparison table](../../airflow/local-air-coupled-results.md#measured-results-and-calibration-gate). The legacy sealed-house equilibrium control is +11.4713 C, with t95 296 s. The coupled house reaches 95% of that **legacy target** at 197 s and has +12.5206 C mean offset after 600 s. Do not call 197 s its own equilibrium t95. Per-step combined energy errors stayed below `2e-8 C*air-cell`; cumulative budget discrepancies after 680 steps were about `-8.1e-6 C*air-cell` in each case. The mean complete coupled step cost in these calm house runs was about 19.5–20.0 ms.

Warmer air/material and changed cooling are reported for Bright's review; no source power, material constant, legacy expectation, or live default was retuned to hide them.

## Reproduce

One performance JVM:

```powershell
.\gradlew.bat :paradyne-lib:coupledAirBenchmark -PcoupledAirReport=build/benchmarks/coupled-air/performance-fork-1.json
```

Repeat with three distinct output filenames/JVMs. The task fixes G1 and the same heap settings. One house comparison:

```powershell
.\gradlew.bat :paradyne-lib:coupledAirBenchmark -PcoupledAirMode=calibration -PcoupledAirReport=build/benchmarks/coupled-air/calibration.json
```

Inspect accepted/rejected counts and completed physical progress before interpreting performance. Test/build status and remaining gates are in [the checkpoint results](../../airflow/local-air-coupled-results.md).
