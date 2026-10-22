<!-- Written by ChatGPT. -->
# Shared preparation pipeline verification, 2026-10-21

See [pipeline checkpoint](../../airflow/local-air-pipeline-results.md) for behavior, accounting, and limitations.

- `tests.log`: Java 21/cached JUnit, 171 successful, none skipped/failed. All `paradyne-lib` `heat.air` main/test sources plus the Minecraft-free capture, coverage, handoff, and preparation sources; selects the pure package and all three Meteodyne `LocalAir*Test` classes.
- `minecraft-compile.log`: direct Java 21 compilation of `HeatWorld` and all seven `LocalAir*` main/game-test files. Annotation processing disabled, empty source path; cached game-test dependencies, Minecraft/NeoForge compiled jar, JetBrains annotations, existing project classes, and freshly compiled pure airflow/capture classes. Game tests have not run.
- `gradle-startup.log`: required full-project check fails before startup on wrapper ZIP lock access.

Normal reproduction:

```powershell
.\gradlew.bat compileJava compileTestJava test runGameTestServer
```

No new production default, live solver field, performance result, or gameplay qualification is claimed.
