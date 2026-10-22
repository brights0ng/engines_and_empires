<!-- Written by ChatGPT. -->
# Operator diagnostics and real server captures, 2026-10-21

See [diagnostics report](../../airflow/local-air-diagnostics-results.md) for scope, commands, measurements, and the aggregate failure.

- `pure-tests.log`: cached Java 21/JUnit run, 190 successful, none skipped/failed.
- `unit-tests.json`: per-project counts extracted from Gradle JUnit XML. 1,256 discovered, 1,254 successful, two skipped, zero failures/errors. No coreless unit sources exist.
- `full-check-summary.log`: task/outcome lines from the final full check with `--continue`. All standalone and coreless game runs pass; aggregate 261/262 has one existing no-seasons assertion failure.
- `initial-full-check-failures.log`: first full-check aggregate failure summary, including the forecast check that passed on the final attempt.
- `aggregate-season-context.log`: aggregate mod-loader and failing assertion context showing Serene Seasons loaded from its runtime mods folder. No runtime mods were removed or edited.
- `direct-gradle-startup.log`: native-service failure from the direct installed Gradle binary. The working verification path is IntelliJ's Gradle host, not this shell fallback.
- `standalone/`, `coreless/`, `aggregate/`: original `summary.json` and `summary.csv` from each real server's coupled timing fixture. These are fixed-forcing, accelerated headless **smoke** measurements, not player or large-domain benchmarks. Each capture contains at least 20 accepted coupled steps.

Required command from the project root (executed successfully as far as the recorded aggregate test failure through the IDE host):

```powershell
.\gradlew.bat compileJava compileTestJava test runGameTestServer
```

The final collection added `--continue --console=plain` so aggregate failure did not prevent standalone/coreless verification. Raw logs remain at `build/stage4-diagnostics-full-first.log` and `build/stage4-diagnostics-full-final.log`; the durable summaries here preserve the outcomes.

For a representative loaded scene, explicitly start the diagnostic domain, then use the existing `/meteodyne heat benchmark start 60` and retain the exported stage metrics and end-state quality. Reproduce the same scene, forcing, response scale, domain/admission limits, and source state when comparing captures.
