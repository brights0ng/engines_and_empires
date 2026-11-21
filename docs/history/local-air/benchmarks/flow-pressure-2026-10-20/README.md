<!-- Measurements, harness and documentation written by ChatGPT. -->
# Archived pressure optimization comparison

These JSON reports preserve the three-fork modified incomplete-Cholesky run from `build/benchmarks/flow/20261020-121150-212/`, copied unchanged. Each records runtime metadata, method selection/factor activation, geometry fingerprints, initialization quality, measured-step acceptance, phase timing distributions, and allocation counters. `summary.json` contains medians/ranges of three fork means and comparisons with the prior diagonal baseline.

`diagonal-smoke.json` is a separate fresh one-fork control from `build/benchmarks/flow/20261020-121524-696/`, using the final cached operator and reduction loops with diagonal preconditioning. It has one warm-up and two measured steps per case, and ran as part of the full-check invocation. It is not included in the repeated-run summary or the headline speedup ratios.

The summary checks both flow and thermal geometry fingerprints against every new fork and against `../flow-kernel-2026-10-19/summary.json`. Workloads, forcing, timesteps, caps, warm-up count, sample count, and acceptance tolerances are unchanged. The report schema advances to `pure-flow-v2` to add method/factor metadata. The old baseline remains unchanged. A missing timing after failed initialization is not a zero-cost pass.

Reproduce with `./gradlew.bat :paradyne-lib:flowBenchmark`; select the numerical control with `-PflowBenchPreconditioner=DIAGONAL` or `INCOMPLETE_CHOLESKY`. `-PflowBenchQuick=true` is a one-fork smoke check, not the repeated performance result. See `../../airflow/local-air-pressure-results.md` for scope, interpretation, setup/memory costs, and final regression results.

Measurements are specific to this machine/JVM/workload. Combined forcing does not include heat transport, and this is not a live worker-budget qualification.
