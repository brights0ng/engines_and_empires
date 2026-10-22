<!-- Written by ChatGPT. -->
# Coupled house equilibrium and cooling evidence

The same three virtual-air campfire houses are compared without changing production source power, coefficients, solver settings, geometry or boundaries. Background is 20°C, exterior wind is calm, physical step is 1 s and response scale is 1. Warm-up starts cold; cooling starts after the selected thermal statistics qualify.

| File | Role |
| --- | --- |
| `strict-pilot.log` | Initial strict instantaneous-field pilot: the sealed case ran to its 6,000 s cap without qualifying; the window case was stopped after persistent oscillations were established. This log is not a complete three-house result. |
| `long-window-endpoint-pilot.log` | Preliminary long-window run, stopped before completing all houses when endpoint-only strict checks were found vulnerable to sampling the same phase of a repeating flow. |
| `thermal-short-windows.json` | Six consecutive 100 s windows. Sealed/window cases qualify at 3,300 s, then cool for 900 s. Missing-wall reaches its 6,000 s heating cap without qualifying and has no claimed own-equilibrium target or cooling result. |
| `equilibrium-long.json` | Final long-window confirmation: three consecutive 1,000 s windows; same drift, room-range and energy-balance thresholds; 10,000 s heating cap and 900 s cooling. |

The final strict condition checks maximum instantaneous state/cell changes and within-window ranges of 0.001°C, maximum face-flux change and within-window range of `1e-5 block³/s`, and absolute stored-energy rate of `0.001 C*air-cell/s`. Within-window ranges prevent sampling the same phase of a repeating flow from falsely qualifying it as motionless. Strict status is retained separately in every reported window and result. Earlier pilots used endpoint changes only; the final long-window report includes the range checks.

Thermal-statistics qualification instead checks changes between consecutive window means for **every** coupled air/material state and legacy cell, each at most 0.001°C. It also requires absolute window storage rate at most `0.001 C*air-cell/s` and within-window room-mean range at most 0.01°C. Flux mean changes/ranges and maximum local temperature ranges remain reported diagnostics. A qualified thermal mean does not claim motionless airflow or an instantaneous fixed temperature field.

The equilibrium target is the mean of the per-second room history over the full consecutive stable interval. Own t95 is the first one-second sample reaching 95% of that model's own target. The old common-legacy-target crossing is retained separately. Cooling fractions and half-times use each model's actual source-off room warmth. Cooling sample times are seconds since source removal. No failed/unqualified run is converted into a valid target; unavailable crossings use `-1` and unqualified target metrics are omitted. Averaging windows never enlarge the one-second solver step.

Reproduce via the existing benchmark task when Gradle is available:

```powershell
.\gradlew.bat :paradyne-lib:coupledAirBenchmark -PcoupledAirMode=equilibrium -PcoupledAirEquilibriumProtocol=long -PcoupledAirReport=build/benchmarks/equilibrium-long.json
.\gradlew.bat :paradyne-lib:coupledAirBenchmark -PcoupledAirMode=equilibrium -PcoupledAirEquilibriumProtocol=short -PcoupledAirReport=build/benchmarks/equilibrium-short.json
```

Direct main arguments: `output.json equilibrium COMPACT_CACHED true CACHED_TRACE long|short`. The default equilibrium protocol is `long`. Java 21, `-Xms512m -Xmx1g -XX:+UseG1GC`; machine/settings metadata are embedded in each JSON. Per-step wall timings exclude the surrounding legacy solve and statistics probes and are not a new performance comparison.

The fixtures remain in `CoupledAirBenchmark.house`; `CoupledAirCalibration` adds measurement/qualification only. Existing `calibration` and `performance` modes remain available. The original short 600 s heating / 80 s cooling control is archived in `../air-hotloops-2026-10-20/calibration.json`.

See [calibration interpretation and remaining gates](../../airflow/local-air-calibration-results.md).

Final verification: all three long-window cases qualify their thermal statistics at 6,000 s and complete 900 s cooling, totaling 20,700 accepted coupled updates. Strict instantaneous status is false in every case. All 105 common prefix values through 600 s exactly match the preceding calibration archive. Direct Java/JUnit verification passes 115 numerical tests; the full Gradle command remains blocked at wrapper-lock access before startup.
