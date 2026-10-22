<!-- Written by ChatGPT. -->
# Cached arithmetic optimization, 2026-10-20

The offline coupled solver now caches thermal diagonal reciprocals and velocity sampling origins. This follows the [thermal traversal checkpoint](local-air-thermal-optimization-results.md). Stage 3 remains open; these changes do not integrate airflow into the game or retune its physics.

## Measured cost

Three paired JVM forks compare the preceding `COMPACT_DIRECTED` implementation against the cached implementation. Each pair ran before then after. Values below are medians of fork means at one physical second and response scale 1:

| Scene | Before wall ms | After wall ms | Reduction |
| --- | ---: | ---: | ---: |
| Multiroom 32³ | 65.23 | 63.86 | 2.1% |
| Stress 48³ | 275.21 | 264.50 | 3.9% |
| Partial-block house | 16.63 | 15.81 | 4.9% |

At 48³, thermal work changes from 107.57 to 104.19 ms, thermal sweeps from 84.71 to 80.63 ms, and velocity transport from 63.45 to 59.51 ms. Pressure changes from 103.61 to 101.06 ms despite unchanged pressure code, illustrating run variation. CPU medians change from 276.56 to 262.50 ms/update. One before fork and part of one after fork have substantial wall-time spikes; all raw data remain archived. Treat this as a modest improvement, not a precise isolated speedup for either cache. The 100 ms CPU/s target remains unmet.

A separate unprofiled comparison using the final source's retained controls measures 279.75 to 263.45 ms at 48³. Its smaller scenes show no improvement, so the small-scene headline gains should not be treated as established across runs.

## Changes and controls

`COMPACT_CACHED` is the new offline thermal default. It retains directed/adaptive traversal and assembles one reciprocal per row after the complete diagonal is known. Sweeps multiply by that reciprocal. If the reciprocal overflows or is subnormal, that row retains division. The full acceptance calculation still uses the original diagonal and capacity division, unchanged thermostat law, and unchanged residual/energy tolerances. Reciprocal rounding can alter sweep counts; every accepted result still passes the original checks.

`FlowGrid` caches each air pocket's integer block origin once per immutable geometry owner, removing repeated integer division/remainder during sampling. Interpolation, per-pocket reconstruction, trace traversal, corner checks, and collision stopping remain unchanged. A package-local reference constructor and benchmark flag retain the old calculation. The partial-geometry comparison produces exactly equal velocities, pressure diagnostics, and blocked-trace counts in both wind directions.

The caches add `12 * airNodes + 8 * thermalStates` primitive bytes: **2,163,156 bytes (2.06 MiB)** at 48³, excluding array headers. They allocate once per owner. Measured update allocation remains **368 bytes**, with no per-cell or per-face update allocation.

## Remaining computation

A separate Java Flight Recorder run collected 1,525 main-thread execution samples inside `CoupledAirGrid.step`, excluding construction/initial projection but including warm-up and all scenes/settings:

| Sampled top method | Samples | Share |
| --- | ---: | ---: |
| Thermal advance | 542 | 35.5% |
| Pressure preconditioner | 330 | 21.6% |
| Geometry trace | 267 | 17.5% |
| Pressure matrix application and dot product | 221 | 14.5% |
| Pressure iteration driver | 51 | 3.3% |
| Velocity step driver | 43 | 2.8% |

Inlining and sampling bias limit attribution; these are aggregate CPU samples, not exact timers or a 48³-only breakdown. The unprofiled benchmark supplies headline timings. Thermal sweep arithmetic and sparse neighbor loads remain substantial. Within pressure, the two sequential triangular preconditioner passes are the largest sampled method. Within velocity work, tracing dominates sampling arithmetic. Future experiments should target those loops and demonstrate equal quality; this pass changes neither the pressure algorithm nor wall traversal.

## Verification

Java 21 directly compiles all main/test sources in the pure `heat.air` package. **105/105 tests pass**, retaining all existing assertions and adding extreme representable storage and exact cached/reference flow comparisons. The existing all-mode thermal test now includes cached arithmetic through material/source/reference changes and flux reversal.

The paired runs pass **450/450 measured updates per implementation**, plus 225 warm-up updates each, across physical steps 0.25/0.5/1 s and response scales 0.5/1/2. Optimized maximum divergence is `9.698e-8 /s`; maximum combined two-half energy error is `1.955e-8 C*air-cell`, with each half retaining its `1e-8` acceptance limit. The final source also has a separate unprofiled before/after control run and a profiled run archived separately from the headline forks.

All three calibration houses complete 600 s heating and 80 s cooling, totaling **2,040 steps without failure**. The maximum difference across 81 sampled room/material/near-source values versus the preceding checkpoint is **4.432e-13 °C**. Legacy-equilibrium target crossings remain 197/175/96 s; the existing warmer-than-legacy behavior remains unresolved and unretuned.

The required `gradlew compileJava compileTestJava test runGameTestServer` command remains blocked before Gradle starts by access denied on the user Gradle wrapper ZIP lock. The direct Java suite is numerical verification, not full-project/game-test or in-game visual verification.

Raw data and reproduction controls: [benchmark archive](../benchmarks/air-arithmetic-2026-10-20/README.md).
