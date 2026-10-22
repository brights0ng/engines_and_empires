<!-- Written by ChatGPT. -->
# Paced local-air room evidence

See [the scene report](../../airflow/local-air-scene-results.md) for setup, results, limitations, and the next implementation step.

- `phase-0.json`: closed door, lit campfire.
- `phase-1.json`: open door, lit campfire.
- `phase-2.json`: open door, extinguished campfire; cold restart, not retained-state cooling.
- `phase-3.json`: opened roof/outer sides, extinguished campfire, explicit 2 blocks/s eastward wind.
- `phases.csv`: first-field delay, maximum sampled age of published frames, accepted steps, energy, and divergence.
- `paced-build-summary.log`: extended standalone test outcome.
- `full-build-summary.log`: successful full check, unit task outcomes, and all game-test totals.
- `isolation-cache-summary.log`: test-mod sync/verification with configuration-cache reuse.

Captures were copied directly from the generated build reports. JSON contains its original `writtenBy` metadata. CSV and log are generated measurements, authored and collected by ChatGPT. The first three phase summaries share cumulative request counters; phase 3 starts a new request. Worker series are capture-local. Do not sum cumulative counters across phases or infer zero CPU cost from coarse Windows readings.

Reproduce with `./gradlew :meteodyne:runGameTestServer -Pmeteodyne.airScene=true --console=plain` (quote `'-Pmeteodyne.airScene=true'` in PowerShell). The ordinary coupled test remains unchanged when the property is absent. Targeted callback pacing does not constitute whole-server MSPT or client visual verification.
