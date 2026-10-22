<!-- Written by ChatGPT. -->
# Cached pressure solve optimization, 2026-10-20

This follows the [stage 2 kernel baseline](local-air-kernel-results.md). The pure kernel now caches a sorted row stencil and a modified incomplete-Cholesky preconditioner. It remains offline: live weather, wind instruments, sources, heat transport, workers, and ships have no new behavior.

## What changed

The pressure operator still uses the same shared-face aperture/distance weights, prescribed inflow, pressure-open exterior, and symmetric sealed-component reference elimination. Its coefficients are gathered into reusable sorted rows when the solver is constructed, removing face classification and output scatter from each CG matrix application. Repeated neighbors are summed rather than assumed absent. The reference-eliminated operator is unchanged; independent tests compare its product against signed face fluxes.

Three numerical modes are retained: `DIAGONAL`, `INCOMPLETE_CHOLESKY`, and `MODIFIED_INCOMPLETE_CHOLESKY`. The default is the modified factor. Zero-fill Cholesky discards matrix fill outside the original neighbor graph. The modified form compensates both affected diagonals for 97% of each dropped fill contribution, improving long-wave error removal. A modified pivot below 25% of its original diagonal uses that original diagonal instead. Nonfinite or unsafe factor pivots discard the factor globally and use positive diagonal preconditioning. This fallback does not relax acceptance criteria; the actual operator's residual and corrected flux divergence must still pass.

The factor and transpose coefficients are cached once per immutable geometry/boundary owner. Each preconditioner application uses forward and backward triangular passes over retained arrays. Dot products are accumulated during matrix/preconditioner passes, and the residual norm during the solution update, reducing separate array traversals. True residuals are still recomputed periodically and on unsuccessful termination. Double storage, warm-start transaction checks, speed limits, and last-valid-field retention remain in place.

The cap remains 512 iterations. Residual tolerances remain `1e-8` relative and `1e-10` absolute RMS; maximum accepted volume-normalized divergence remains `1e-7 / second`. The 64-cap benchmark control remains a control, not a newly selected default. No thermal calibration or physical timestep was changed.

## Reproducing the measurement

Use `./gradlew.bat :paradyne-lib:flowBenchmark` for three JVM forks, five warm-up and ten measured steps per case. `-PflowBenchQuick=true` is a one-fork smoke check. `-PflowBenchPreconditioner=DIAGONAL` or `INCOMPLETE_CHOLESKY` selects a control; the default is `MODIFIED_INCOMPLETE_CHOLESKY`.

Reports now use `pure-flow-v2` and record the requested preconditioner and whether its factor was active. Scene geometry, forcing, thermal control, step sizes, 64/512 caps, and fingerprints remain compatible with the archived `pure-flow-v1` baseline. Do not interpret a missing timing after failed initialization as successful zero-cost work. Combined forcing still means prescribed-temperature buoyancy plus wind, not coupled heat transport.

These are single-owner numerical measurements, not live worker admission or whole-server performance measurements.

## Repeated result

The complete three-fork run is preserved in [benchmarks/flow-pressure-2026-10-20](../benchmarks/flow-pressure-2026-10-20/README.md). Runtime was Java 21.0.12.1+1-LTS, G1, 512 MiB initial/1 GiB maximum heap, six available processors, AMD64 Family 25 Model 97. The benchmark took 2m38s. Both geometry fingerprints match every new fork and the original baseline for every case.

Every 512-cap initialization passed; all **1,800 measured steps** passed, with no factor fallbacks. Maximum observed accepted-step divergence was `9.783e-8 / second` and maximum absolute exterior imbalance was `2.355e-5 block³/second` over the whole domain. This uses the same tolerances as the baseline, not an intentionally cheaper accuracy target. Independent unit fixtures compare corrected velocities between the diagonal, ordinary incomplete and modified incomplete solvers at that contract.

Combined wind plus supplied-temperature buoyancy, **one physical second per update**, cap 512:

| Scene | Prior / new wall ms/update, medians | New range of fork means | Speedup | Prior / new pressure wall ms | Prior / new maximum iterations |
| --- | ---: | ---: | ---: | ---: | ---: |
| Multiroom 32³ | 127.02 / 37.72 | 37.10–37.94 | 3.37× | 108.93 / 20.33 | 251 / 44 |
| Multiroom 48³ | 687.05 / 159.32 | 156.68–159.61 | 4.31× | 624.69 / 100.25 | 380 / 60 |
| 50-block row | 5.47 / 2.71 | 2.60–2.76 | 2.02× | 3.74 / 1.10 | 74 / 19 |
| Sealed house, with surrounding exterior | 19.88 / 7.49 | 7.14–7.49 | 2.66× | 15.89 / 3.27 | 140 / 29 |
| Partial-block house | 22.58 / 8.59 | 8.53–8.63 | 2.63× | 16.80 / 3.54 | 145 / 31 |

Ratios compare the original three-fork implementation to this three-fork implementation; they include both row caching and the stronger preconditioner. They are not an interleaved hardware-controlled experiment isolating one change. Phase medians are independent and need not add exactly to total medians. CPU time medians for the two large combined cases are 37.5 and 157.8 ms/update, respectively; Windows CPU resolution is coarse. Geometry, scalar temperatures, velocity transport, and the thermal-only controls are unchanged.

A fresh diagonal smoke control with the final cached rows/reduction loops also converged at cap 512. Its combined one-second cases needed 261/390 iterations at 32³/48³, versus 44/60 in the repeated modified-factor run. Smoke wall means were about 92/541 ms, but its one warm-up/two samples and full-check invocation differ from the headline three-fork measurement. It is archived separately as `diagonal-smoke.json`; it corroborates the numerical control rather than isolating a precise preconditioner-only timing ratio.

At cap 64, 27 of 180 initializations still failed, all in the large stress scene's wind/combined/storm cases. All 1,530 measured steps after successful initialization passed. This improves on the original control but still does not justify selecting 64 as the default.

### Setup and memory tradeoff

The additional row coefficients and triangular factor increase measured solver-construction allocation from 7.84 to **11.77 MiB** at 32³ and from 27.03 to **40.85 MiB** at 48³. These are allocation counters including temporary setup arrays, not retained-heap measurements. Geometry and supplied temperature storage are excluded. The constructor's temporary face weights are no longer retained after the row/factor cache is built. Measured step allocation remains **104 bytes**, with no allocation per cell or face in the numerical loop.

Representative factor/operator/grid construction costs for the combined one-second case are around 3 ms at 32³ and 10 ms at 48³. These are single constructor measurements per case/fork, not warmed latency distributions. Construction is once per geometry/boundary owner, not per update. A future runtime adapter must keep this work off the game thread and invalidate it only when geometry or prescribed-versus-open face classification changes.

### Decision gate

The optimization is accepted for the offline kernel: tested solutions conserve flow at the existing tolerance and the larger combined fixture is about four times faster. Runtime capacity is still unqualified. The 48³ case alone costs about 158 ms CPU per simulated second at a one-second step, above the plan's approximately 100 ms CPU per real second direction for the reference scene's combined thermal/flow workload. Smaller steps require more solves per second. The fixture and live scene differ, and heat transport/publication/scheduling are still absent, so these numbers must not become a live radius or multiplayer-capacity promise.

The next plan milestone remains offline shared-flux heat/air coupling. Keep the optimized pressure solver and unchanged thermal control for that comparison. Runtime admission, domain sizes, memory budget, and further pressure/transport work still need measurements before enabling an in-game prototype.

## Verification

All 24 airflow/pressure unit fixtures pass, including five new tests for operator-versus-face agreement, fused matrix reduction, exact ordinary IC on a one-dimensional passage, symmetric/positive preconditioning, reused-buffer overwrite, unsafe-factor fallback, and agreement between diagonal/ordinary/modified corrected velocities. The existing partial-connectivity, tiny-volume, wind/buoyancy, failure-retention, and time-separation tests are unchanged and pass with the new default.

The required full check was run with the fresh diagonal smoke benchmark in the same Gradle invocation: `compileJava compileTestJava test runGameTestServer :paradyne-lib:flowBenchmark -PflowBenchQuick=true -PflowBenchPreconditioner=DIAGONAL --continue`. Compilation passed. **1,122 unit tests** completed without failures/errors, with the two existing benchmark skips. Coreless passed 205/205. Every standalone run passed: Meteodyne 89, Frontier 18, lib 3, Sable Fixes 2, Seismic 20, Spoilage 34, Sparse Ores 13 bare and 13 with Create.

The initial aggregate result was 254/256: the known `theGameTestServerHasNoSeasons` failure from the local Serene Seasons launch setup, plus `forecastsAreCachedAndThrownAwayAfterAJump` at its temperature-difference assertion. An unchanged aggregate-only rerun passed the forecast test and finished **255/256**, with only the known seasons failure. Forecast/weather code, the tests, and launch setup were not changed. The full Gradle invocation and aggregate recheck therefore still exit unsuccessfully; they are not reported as entirely green.

Evidence: `../../build/flow-pressure-mic-benchmark.log`, `build/flow-pressure-full-check.log`, and `build/flow-pressure-aggregate-recheck.log`; exploratory ordinary/modified smoke logs are `build/flow-pressure-ic-smoke.log` and `build/flow-pressure-mic-smoke.log`. `git diff --check` passed. No client visual/gameplay trial or live airflow-worker performance test was performed.
