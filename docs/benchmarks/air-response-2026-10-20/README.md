<!-- Written by ChatGPT. -->
# Coupled response and long-interval evidence

Source: `CoupledAirQualification.responses`, invoked by `CoupledAirBenchmark`. House geometry is adapted from `AirGridTest` (original fixture authorship unmarked). The original calibration domain is retained: 24x16x24 spatial cells, horizontal/top padding 6, ground depth 3, house 10x10x5. Solver tolerances, source/material definitions, flow kernel and response defaults are unchanged.

| File | Content |
| --- | --- |
| `responses.json` | Three houses at 0.1/0.5/1/2/10x response, 0.5 s physical steps, 600 s heating plus 80 s cooling |
| `responses-quarter.json` | The same fifteen cases at 0.25 s physical steps for temporal refinement |
| `summary.json` | Derived scalar comparisons, quality and exact unity-control validation |
| `summarize.py` | Standard-library derivation/validation script |
| `tests.log` | Direct Java 21/JUnit pure-package checks, including rate snapshots and test-only resumable catch-up |
| `gradle-startup.log` | Required full-project command, blocked at Gradle wrapper-lock access |
| `responses.log` | Completed run console output |
| `responses-quarter.log` | Completed refinement run console output |

Raw reports checkpoint after each completed case with `complete=false`, then write `complete=true` after the batch finishes. Validate row completion and rejection status as well as the root flag: the root flag indicates batch completion, not numerical acceptance. The derivation script checks all thirty case identities, accepted time, source energy, field sizes/finiteness, late-window coverage and unity prefix/field agreement with `../air-qualification-2026-10-20/timesteps.json`. House-field temperature errors include all 500 positions; velocity-component errors include only air positions and all three components. Material zero-velocity placeholders do not dilute velocity RMS.

With a functioning Gradle environment:

```text
.\gradlew.bat :paradyne-lib:coupledAirBenchmark -PcoupledAirMode=responses -PcoupledAirReport=docs/benchmarks/air-response-2026-10-20/responses.json
.\gradlew.bat :paradyne-lib:coupledAirBenchmark -PcoupledAirMode=responses -PcoupledAirQualificationStep=0.25 -PcoupledAirReport=docs/benchmarks/air-response-2026-10-20/responses-quarter.json
python -B docs/benchmarks/air-response-2026-10-20/summarize.py
```

The `responses` mode defaults physical dt to 0.5 s through `coupledAirQualificationStep`. Individual refinements can use `qualification-case`, with the optional fifth case value specifying response scale; for example, `-PcoupledAirQualificationStep=0.25 -PcoupledAirQualificationCase=0,6,6,3,10`. Existing four-value case specifications retain response 1.

This environment directly compiles every main/test source in the pure `heat.air` package using cached Java 21/JUnit/Gson jars, then runs the JUnit launcher and the benchmark main. JVM flags are `-Xms512m -Xmx1g -XX:+UseG1GC`; positional benchmark arguments are:

```text
docs/benchmarks/air-response-2026-10-20/responses.json responses COMPACT_CACHED true CACHED_TRACE long 0.5
docs/benchmarks/air-response-2026-10-20/responses-quarter.json responses COMPACT_CACHED true CACHED_TRACE long 0.25
```

`ThermalClockSegmentsTest` verifies chronological, immutable, clamped rate history. `CoupledAirIntervalTest` compares independent numerical trajectories with test-only jobs that yield after seven steps, for 1/10/120-second dispatches and all five scales. Those tests have fixed source/weather inputs; no live scheduler is implemented. See the [results report](../../airflow/local-air-response-results.md) for scope and remaining gates. The timing probes in the house report are diagnostic, not a new throughput benchmark.
