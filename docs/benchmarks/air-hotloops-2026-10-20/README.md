<!-- Written by ChatGPT. -->
# Tracing and triangular pressure experiments

Final selection: `CACHED_TRACE`. The preceding thermal reciprocal and sampling-origin caches remain enabled. Pressure unrolling remains an explicit experiment and is off by default. These are offline numerical worker measurements.

| Files | Meaning |
| --- | --- |
| `REFERENCE-{1,2,3}.json` | Previous tracing arithmetic and ordinary triangular pressure loops, with preceding thermal/sampling caches enabled |
| `CACHED_TRACE-{1,2,3}.json` | Selected final default: cached trace direction reciprocals and exact binary lattice scaling; ordinary pressure loops |
| `OPTIMIZED-{1,2,3}.json` | Combined experiment: cached tracing plus pressure unrolling; not the selected default despite the experimental mode name |
| `UNROLLED_PRESSURE-ablation.json`, `CACHED_TRACE-ablation.json` | Single-fork controls with only the named change enabled |
| `exploration/*.json` | Earlier four-way exploration, before exact binary lattice scaling was added |
| `calibration.json` | Selected default: 600 seconds heating and 80 cooling per house |

The three reference/combined-experiment pairs reversed launch order on the middle pair. Following that experiment and the two ablations, the selected trace-only mode ran in three fresh JVMs. The selected-mode comparison therefore uses sequential groups, not interleaved reference/trace pairs. Keep run variation in mind, especially for small full-update differences. No benchmark runs overlapped each other.

All performance runs use five warm-up and ten evolving measured updates per scene/configuration, with construction/initial projection outside timing. Each report records Java/hardware/JVM settings, geometry fingerprints, counts, allocation and numerical quality. Java 21, `-Xms512m -Xmx1g -XX:+UseG1GC`; settings and physical/source/weather inputs are unchanged.

From Gradle, run separate JVMs for each control and output path:

```powershell
.\gradlew.bat :paradyne-lib:coupledAirBenchmark -PcoupledAirFlowKernel=REFERENCE -PcoupledAirReport=build/benchmarks/tracing-reference.json
.\gradlew.bat :paradyne-lib:coupledAirBenchmark -PcoupledAirFlowKernel=CACHED_TRACE -PcoupledAirReport=build/benchmarks/tracing-selected.json
.\gradlew.bat :paradyne-lib:coupledAirBenchmark -PcoupledAirFlowKernel=UNROLLED_PRESSURE -PcoupledAirReport=build/benchmarks/pressure-experiment.json
.\gradlew.bat :paradyne-lib:coupledAirBenchmark -PcoupledAirFlowKernel=OPTIMIZED -PcoupledAirReport=build/benchmarks/combined-experiment.json
.\gradlew.bat :paradyne-lib:coupledAirBenchmark -PcoupledAirMode=calibration -PcoupledAirReport=build/benchmarks/tracing-calibration.json
```

Direct main arguments are `output.json performance|calibration THERMAL_MODE cachedSamplingOrigins FLOW_KERNEL`. This archive uses `COMPACT_CACHED true` for the thermal/sampling arguments. The selected default kernel is `CACHED_TRACE`; to reproduce earlier thermal/arithmetic checkpoints without this pass, explicitly select `REFERENCE`.

Gradle verification remains blocked before startup by access denied on the user wrapper ZIP lock. Direct Java 21 compilation and JUnit execution verify the pure `heat.air` package. Full-project/game-test and live visual verification are not claimed.

See [results and decision](../../airflow/local-air-tracing-results.md).
