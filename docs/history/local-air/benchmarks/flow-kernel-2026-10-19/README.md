<!-- Measurements, harness and documentation written by ChatGPT. -->
# Archived pure airflow comparison

These JSON reports preserve the three-fork stage 2 run started on 2026-10-19 local time (`20261020-023926-773` UTC). They are copied unchanged from `build/benchmarks/flow/20261020-023926-773/` so a Gradle clean cannot erase the evidence. Each includes attribution, runtime metadata, fixture fingerprints, initial projection diagnostics, accepted/rejected counts, phase costs, and allocation counters.

`summary.json` aggregates each scene/mode/timestep/cap across forks. Timing entries are medians and ranges of three per-fork means. Failed initialization has no step samples; missing timing entries are not zero-cost passes. The summary was generated from the archived fork reports, checking that both geometry fingerprints match between forks.

Run `./gradlew.bat :paradyne-lib:flowBenchmark` to reproduce the workload. See `../../airflow/local-air-kernel-results.md` for the equations, fixtures, scope, limitations, acceptance gate, and full regression evidence. The original thermal-only baseline remains in the sibling `thermal-pre-flow-2026-10-19` folder. This flow comparison does not implement or benchmark coupled heat transport, live world snapshots, scheduling, publication, or instruments.

Measurements are specific to this machine/JVM/workload. No timing threshold is a correctness test.
