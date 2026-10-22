<!-- Written by ChatGPT. -->
# Pre-airflow thermal baseline, 2026-10-19

The finalized baseline is `../../build/benchmarks/thermal/pre-flow-v2-20261019-180649-060/summary.json`, with per-fork JSON/CSV in the same directory. An unchanged archival copy of the summary and three fork JSON files is checked in under `docs/benchmarks/thermal-pre-flow-2026-10-19/`. Use this v2 run; earlier trial directories used shorter warmup and unbatched CPU measurements and are not comparable.

Environment: Windows amd64; AMD family 25 model 97 stepping 2; six processors available to Java; Eclipse Adoptium Java 21.0.12.1; three sequential isolated JVM forks; fixed 512 MiB heap and G1 GC. The working tree included the uncommitted benchmark harness. Exact revision, VM options, parameters and geometry fingerprints are in the reports. The benchmark ran separately from Gradle tests/game-test servers.

These are **numerical step costs**, excluding Minecraft geometry collection, region planning, worker queueing and publication. Each entry is the median of three warmed per-fork mean wall times, in milliseconds, for a one-second simulated thermal step.

| Scene | Cells | Median fork mean | Range of fork means |
| --- | ---: | ---: | ---: |
| Open | 4,096 | 0.0844 ms | 0.0840–0.0856 ms |
| Sealed house | 9,216 | 0.1968 ms | 0.1926–0.2050 ms |
| House with doorway | 9,216 | 0.1967 ms | 0.1927–0.1986 ms |
| Partial-block house | 9,216 | 0.1960 ms | 0.1958–0.1979 ms |
| Partitioned rooms | 32,768 | 0.8269 ms | 0.7969–0.8416 ms |
| Large partitioned volume | 110,592 | 2.6763 ms | 2.6690–2.6784 ms |

These observations establish a local control, not a performance promise for airflow. The prototype will add velocity state, pressure iterations and potentially more active regions. Compare the combined pipeline and result freshness as well as its per-region computation.

See [thermal-performance.md](thermal-performance.md) for repeat and comparison commands, live-capture instructions, measurement boundaries and limitations. A client capture remains necessary for performance in Bright's actual world; the headless capture test exercises instrumentation correctness only.

Verification: 394 Meteodyne unit tests (zero failures, two existing benchmark skips); 21 library thermal/timing tests; 82/82 standalone Meteodyne game tests; 97/97 Meteodyne game tests with ship integration; six core architecture checks passed. Capture tests validate real snapshot/solver/publication measurements and JSON/CSV output. Comparison tests validate median aggregation, slowdown ratios and changed-geometry rejection. Both mod jars were rebuilt and exclude benchmark test classes. Logs: `../../build/thermal-baseline-check.log`, `build/thermal-baseline-final-check.log`, and `build/thermal-baseline-final.log`.
