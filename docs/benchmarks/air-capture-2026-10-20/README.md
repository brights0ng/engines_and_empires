<!-- Written by ChatGPT. -->
# World capture verification, 2026-10-20

See [capture report](../../airflow/local-air-world-capture-results.md) for scope and limitations.

- `tests.log`: Java 21 / cached JUnit run, 148 tests successful, zero skipped/failed. Compiles every main/test Java source in `paradyne-lib`'s `heat.air` package plus `LocalAirGeometryCapture` and `LocalAirGeometryCaptureTest`. JUnit selects the pure air package and the capture test class only.
- `minecraft-compile.log`: direct Java 21 compilation of `LocalAirWorldSource` and `LocalAirGeometryGameTests`, with annotation processing disabled and an empty source path. Classpath uses the cached game-test server dependency list, cached Minecraft/NeoForge compiled jar, JetBrains annotations, existing Meteodyne/lib classes, and freshly compiled pure capture/air classes. Does not execute Minecraft or rebuild dependencies.
- `gradle-startup.log`: required full check blocked by wrapper ZIP lock access before Gradle starts. Neither game tests nor the complete mod graph were verified.

Normal reproduction from the repository root:

```powershell
.\gradlew.bat compileJava compileTestJava test runGameTestServer
```

Capture is off the live path. There are no runtime performance measurements in this archive.
