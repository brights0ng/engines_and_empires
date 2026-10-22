<!-- Written by ChatGPT. -->
# Coverage and handoff verification, 2026-10-21

See [coverage report](../../airflow/local-air-coverage-results.md) for the conservative open-sky policy and remaining integration work.

- `tests.log`: Java 21/cached JUnit, 161 tests successful, none skipped/failed. The complete pure `heat.air` package plus `LocalAirGeometryCaptureTest` and `LocalAirCoverageTest` run together. Main inputs additionally include `LocalAirGeometryCapture`, `LocalAirCoverage`, and `LocalAirGeometryHandoff` from Meteodyne; they require no Minecraft runtime.
- `minecraft-compile.log`: direct API compilation of `LocalAirWorldSource` and `LocalAirGeometryGameTests` with Java 21, `-proc:none`, and an empty source path. Dependencies are the cached server classpath, cached Minecraft/NeoForge compiled jar, JetBrains annotations, existing Meteodyne/lib classes, and freshly compiled capture/coverage/air classes. Game tests are not executed.
- `gradle-startup.log`: required full check fails before Gradle starts on wrapper ZIP lock access.

Normal reproduction from the repository root:

```powershell
.\gradlew.bat compileJava compileTestJava test runGameTestServer
```

No live timing, source initialization, field publication, or gameplay qualification is claimed.
