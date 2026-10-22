<!-- Written by ChatGPT. -->
# Cached arithmetic benchmark evidence

See [analysis and limitations](../../airflow/local-air-arithmetic-results.md).

- `before-{1,2,3}.json`: preceding compiled implementation, `COMPACT_DIRECTED`, uncached sampling origins.
- `after-{1,2,3}.json`: reciprocal/metadata implementation, `COMPACT_CACHED`. Three paired forks, before then after, same settings/fixtures/JVM arguments. These provide headline medians. The retained source-level sampling control was added afterward.
- `final-before.json` / `final-after.json`: final source with both retained controls, unprofiled confirmation runs, separate from the headline forks.
- `profiled-after.json`: final optimized source under Java Flight Recorder; excluded from headline timing.
- `profile-summary.json`: sampled top methods, filtered to the main thread with `CoupledAirGrid.step` in the stack. Includes warm-up and all scenes/settings, excludes setup. Raw JFR and expanded execution-sample stacks are temporary build outputs.
- `calibration.json`: final optimized 600 s heating plus 80 s cooling per house. Control is `../thermal-optimization-2026-10-20/calibration.json`.

Each performance fork uses five warm-up and ten measured evolving updates per scene/configuration. Owners are constructed and initialized outside update timing. JVM: Java 21, `-Xms512m -Xmx1g -XX:+UseG1GC`. Fixtures, source/weather, settings, hardware, geometry fingerprints, counts, quality and allocation are recorded in each report.

Reproduce from the final source in separate sequential JVMs with the test runtime classpath, or through Gradle when available:

```powershell
.\gradlew.bat :paradyne-lib:coupledAirBenchmark -PcoupledAirThermalMode=COMPACT_DIRECTED -PcoupledAirCachedSampling=false -PcoupledAirReport=build/benchmarks/arithmetic-before.json
.\gradlew.bat :paradyne-lib:coupledAirBenchmark -PcoupledAirThermalMode=COMPACT_CACHED -PcoupledAirCachedSampling=true -PcoupledAirReport=build/benchmarks/arithmetic-after.json
.\gradlew.bat :paradyne-lib:coupledAirBenchmark -PcoupledAirMode=calibration -PcoupledAirReport=build/benchmarks/arithmetic-calibration.json
```

Direct main arguments are `output.json performance|calibration THERMAL_MODE true|false`; the last flag selects cached sampling origins. Defaults select both caches. For reciprocal-only and metadata-only ablations, select `COMPACT_CACHED false` and `COMPACT_DIRECTED true`, respectively; no isolated ablation speedup is claimed here.

For sampling, add `-XX:StartFlightRecording=filename=build/stage3-cached.jfr,settings=profile,dumponexit=true` to the Java launch. Expand stacks with `jfr print --json --stack-depth 64 --events jdk.ExecutionSample build/stage3-cached.jfr`. JFR emitted Windows PDH-counter warnings; execution sampling succeeded. Timing conclusions use separate unprofiled runs.

Full Gradle verification was attempted and blocked by wrapper ZIP-lock access before build startup. Direct Java 21 compilation and JUnit execution passed 105 numerical tests; no game or live visual verification is claimed.
