<!-- Written by ChatGPT. -->
# Thermal optimization measurements

Root files contain fresh three-fork `REFERENCE` and `COMPACT_DIRECTED` results, the optimized house comparison (`calibration.json`), and a single-fork `compact-every-sweep.json` diagnostic. `exploration/` retains earlier three-fork reference/adaptive experiments and their house comparison; those do not replace the final paired headline runs.

Schema `coupled-air-v2` records traversal mode, thermal sweep/check phase time, and full-check counts in addition to existing metadata. Fixtures, fingerprints, sources, wind, physical/thermal settings, warm-up/sample counts, pressure solver, and tolerances remain compatible with the earlier `coupled-air-v1` run. Different step/response cases evolve independently rather than restoring identical temperatures.

Runtime: Temurin 21.0.12.1+1-LTS, six processors, AMD64 Family 25 Model 97 Stepping 2; G1, 512 MiB initial/1 GiB maximum heap. Each case has five warm-up and ten measured steps. Final launch order was directed/reference, reference/directed, directed/reference across fork pairs. Direct Java was used because Gradle could not write its wrapper lock. This is not isolated hardware; retain fork variation and the coarse Windows CPU-counter resolution.

## Paired result

One physical second/update, response scale 1; medians of fork means:

| Scene | Reference wall ms | Directed wall ms | Directed range | Reference / directed CPU ms | Reference / directed thermal ms |
| --- | ---: | ---: | ---: | ---: | ---: |
| Multiroom 32³ | 109.03 | 66.45 | 65.23–67.30 | 106.25 / 65.63 | 70.26 / 28.52 |
| Stress 48³ | 420.41 | 276.09 | 273.50–277.02 | 407.81 / 271.88 | 247.89 / 109.60 |
| Partial-block house | 27.82 | 16.89 | 15.67–17.41 | 28.13 / 17.19 | 18.61 / 8.01 |

Reference wall ranges are 107.68–113.89, 403.91–420.57, and 27.39–29.50 ms. Phase medians are independent and need not sum to total medians. Pressure/velocity numerical code was not changed.

All **450 measured and 225 warm-up updates per mode** passed, as did every initial projection. Maximum optimized divergence was `9.697572e-8 / second`; maximum combined energy error was `1.975767e-8 C*air-cell`, with the unchanged `1e-8` check per thermal half. Fingerprints agree across modes/forks. Each scene receives a 75-unit air source capped at 65 C, 20 C background, and wind (6,0,2) m/s. Five physical-step/scale pairs are tested: `(0.25,1)`, `(0.5,1)`, `(1,1)`, `(1,0.5)`, `(1,2)`. Other workload limits are not qualified.

At 48³:

| Phase | Reference ms | Directed ms |
| --- | ---: | ---: |
| Thermal update sweeps | 121.28 | 84.98 |
| Thermal residual/energy checks | 110.92 | 19.73 |
| All thermal work, including assembly | 247.89 | 109.60 |
| Pressure | 103.15 | 106.88 |
| Velocity transport/buoyancy | 59.15 | 58.52 |

Maximum thermal-half sweeps/checks changed from 44/44 to 39/9. Smaller pressure timing differences are variation, not a changed pressure algorithm. Allocation is 368 bytes/update in both final modes, fixed records rather than allocation per cell/face.

## Diagnostic controls

The 48³ one-second/scale-1 case:

| Control | Forks | Thermal ms | Total ms | Max half sweeps / checks |
| --- | ---: | ---: | ---: | ---: |
| Final reference | 3 | 247.89 | 420.41 | 44 / 44 |
| Compact rows, every-sweep checks | 1 | 170.47 | 329.75 | 44 / 44 |
| Compact/adaptive, earlier experiment | 3 | 117.29 | 279.10 | 44 / 13 |
| Final compact/directed/adaptive | 3 | 109.60 | 276.09 | 39 / 9 |

The single-fork and earlier-batch controls have different run order and measurement strength; differences are not precise isolated speedups. They support compaction and reduced checking as the major gains. Directed ordering reduces sweeps, but its small complete-update advantage over the adaptive control overlaps fork variation.

Unique neighbor entries at 48³ fall from 1,267,996 to 649,728. Exterior checks visit 13,824 indexed faces instead of filtering 322,958 faces. Operator primitive arrays (rows/coefficients/face maps/order) account for about 30.87 MB reference and 18.23 MB directed, excluding headers, common state, geometry, and pressure. Construction counters include temporary arrays and are not retained-memory measurements. The reused ordering array adds 442,368 bytes at 48³.

## House behavior and verification

All three house variants completed 680 seconds without rejection: 2,040 optimized steps. Maximum difference across 81 sampled room/material/near-source readings versus the preceding coupled checkpoint is `1.864e-10 C`. Legacy-target crossings remain 197/175/96 s. This preserves prior coupled behavior and does not close the warmer-than-legacy calibration gate. This house comparison is a single deterministic run, not a three-fork performance claim; mean step times were 12.37/12.07/11.70 ms for sealed/window/missing-wall cases.

The pure numerical package has 103 passing tests. The project-wide command was attempted but failed at the wrapper-cache lock before execution. No full-build/game-test, client-visual, or live-capacity success is claimed. Details: [optimization results](../../airflow/local-air-thermal-optimization-results.md).

## Reproduce

```powershell
.\gradlew.bat :paradyne-lib:coupledAirBenchmark -PcoupledAirThermalMode=REFERENCE -PcoupledAirReport=build/benchmarks/thermal-opt/reference-1.json
.\gradlew.bat :paradyne-lib:coupledAirBenchmark -PcoupledAirThermalMode=COMPACT_DIRECTED -PcoupledAirReport=build/benchmarks/thermal-opt/directed-1.json
```

Repeat as three separate JVMs per mode, alternating order and preserving options. Use `COMPACT_EVERY_SWEEP` or `COMPACT_ADAPTIVE` for diagnostic controls. Add `-PcoupledAirMode=calibration` with a separate report filename for the house comparison. The offline default is `COMPACT_DIRECTED`; inspect acceptance counts before interpreting timings.
