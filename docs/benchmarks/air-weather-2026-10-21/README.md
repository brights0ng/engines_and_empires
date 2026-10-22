<!-- Written by ChatGPT. -->
# Live weather evidence

Recorded 2026-10-21. See [the implementation and scope](../../airflow/local-air-weather-results.md).

Final scoped verification passed: 98 local-air/mapper unit tests, standalone Meteodyne 97/97, and coreless 212/212. The full aggregate gate remains unverified/failed as detailed below.

Development tests exposed a same-tick door/weather ordering error: a delayed topology could initialize using newer weather or admit a later weather edge into its predecessor's edit-tick endpoint. The fix preserves seed weather during mapping, caps each sealed stage by its admitted weather revision, and retains revision-keyed same-tick samples on both sides of the barrier. Deterministic serial/delayed tests retain exact equality checks across worker caps 1/2/64. A command-tree nesting mistake initially made fixed `start` a child of `live`; the closing parentheses were corrected and the existing command assertions passed. An initial overflow test also undercounted its changes because the first sample equaled the initial weather; that test now exceeds the actual changed-sample admission limit.

## Final verification

The IDE builds passed, including a targeted build after correcting command nesting. The final focused invocation passed with exit 0 (`confirmed-summary.log`, BUILD SUCCESSFUL in 30s):

```text
gradlew :paradyne-lib:test --tests '*GeometryTransferTest' :meteodyne:test --tests '*LocalAir*Test' :meteodyne:runGameTestServer --continue --no-daemon --no-parallel --console=plain
```

`local-air-unit-results.json` is extracted by ChatGPT from final JUnit XML: 98 tests, zero failures/errors/skips. Ten weather tests are new. Standalone Meteodyne passed 97/97, including the new live-provider/gust fixture and fixed/live operator commands. The preceding scoped invocation passed coreless 212/212 (`coreless-and-retry-summary.log`). It also encountered a locked unit-results binary file and an unrelated `glazeskipsbrightlight` failure; both affected tasks passed the final retry without source or assertion changes.

The full project gate is **not green**. `full-rebuilt-summary.log` records `compileJava compileTestJava test runGameTestServer --continue --rerun-tasks`: compilation/unit tasks passed, but aggregate failed both the then-incorrect command tree and `everyorereciperesolvesasitdidbeforestep4c` (two missing recipe lines, including `domum_ornamentum:beige_bricks`). Coreless/standalone Meteodyne failed only the command test in that invocation. Other standalone suites passed. The command nesting was then corrected; the final focused/coreless checks establish the correction.

The next full invocation (`full-interrupted-summary.log`) was interrupted by `Gradle build daemon has been stopped: stop command received`, exited 1, and again reported the recipe-baseline mismatch. It cannot establish an aggregate pass. Earlier development output also reported missing runtime classes; forced rebuilding removed those errors. No recipe baselines, unrelated tests, assertions or production defaults were changed to obtain the focused passes.

Logs here are ChatGPT-extracted task/result excerpts from the named ignored build logs. Tests are headless correctness checks, with a 1 ms scheduling yield in asynchronous fixtures; they do not measure 20-TPS latency, client visuals or frequent-gust operator cost.
