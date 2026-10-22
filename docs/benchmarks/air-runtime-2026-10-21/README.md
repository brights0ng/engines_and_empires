<!-- Written by ChatGPT. -->
# Cold-start coupled diagnostic verification, 2026-10-21

See [runtime checkpoint](../../airflow/local-air-runtime-results.md) for fixed-forcing scope and limitations.

- `tests.log`: Java 21/cached JUnit; 184 successful, none skipped/failed. All pure `heat.air` tests plus the four Meteodyne `LocalAir*Test` classes. Compiles the Minecraft-free capture, coverage, handoff, preparation, and run sources together with the pure library.
- `minecraft-compile.log`: direct Java 21 API compilation of `HeatWorld` and all `LocalAir*` main/game-test sources, `-proc:none`, empty source path; cached game-test classpath, Minecraft/NeoForge compiled jar, JetBrains annotations, existing project classes, and freshly compiled airflow/runtime classes. No game execution.
- `gradle-startup.log`: required full-project command fails before Gradle startup on wrapper ZIP lock access.

Normal reproduction:

```powershell
.\gradlew.bat compileJava compileTestJava test runGameTestServer
```

The game fixture, live worker cost, and gameplay remain unverified. Fixed-forcing controls and cold-start behavior do not establish state migration, dynamic weather/driver inputs, or production readiness.
