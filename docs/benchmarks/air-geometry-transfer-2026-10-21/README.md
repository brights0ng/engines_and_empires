<!-- Written by ChatGPT. -->
# Door geometry-transfer evidence

See the [geometry-transfer report](../../airflow/local-air-geometry-transfer-results.md) for the retained-material contract, energy ledger, and limits.

- `focused-unit-results.json`: six new pure mapper tests and 63 local-air tests, all passing.
- `phase-0.json` through `phase-4.json`: direct captures of closed/heated, opened/heated, source-off cooling, door closing, and new exterior-wind control.
- `phases.csv`: field delay/age, cumulative steps/source/exterior energy, numerical quality, transfer counts, and signed geometry exchange.
- `paced-build-summary.log`: isolated five-phase diagnostic passed 1/1.
- `initial-full-summary.log`: first full check; aggregate/coreless and all other standalone runs passed, while Meteodyne failed two existing underground-cover readings and the existing chunk-hook pipeline timeout. No assertions were changed for those failures.
- `full-task-summary.log`: compile/unit task outcomes and game-test summaries for that full invocation, including its nonzero outcome.
- `bare-retry-summary.log`: unchanged standalone Meteodyne rerun passed 95/95, exit 0. All suites passed across the initial invocation and this targeted retry.

Captures, CSVs, and log extracts are generated measurements collected by ChatGPT. JSON retains its original attribution. The first four phases share a cumulative request ledger; phase 4 replaces the request. Do not sum cumulative totals across phases. Both reconstruction delay and the initial remapped frame's backlog matter; the first mapped frame is not a fresh current-time solution.
