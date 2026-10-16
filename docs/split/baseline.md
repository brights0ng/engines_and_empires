# Split baseline (step 0, 2026-10-16)

The numbers every later split step must reproduce. Recorded on branch `split-0` at commit `a1c352d` (the tree of
`pre-split` = `9ebdb39`, plus the docs commits, the Serene Seasons build fix, and the step-0 deletions; no code
changes).

Command: `./gradlew compileJava compileTestJava test runGameTestServer`

## Counts

| Check | Result |
|---|---|
| Unit tests (`test`) | **964 tests, 0 failures, 0 errors, 2 skipped**, in 119 result classes |
| Game tests (`runGameTestServer`) | **202/202 required tests passed** ("All 202 required tests passed :)") |

The two skipped unit tests (opt-in cloud harnesses; they assume `EAE_BENCH=1` and a sky dump taken in game with
`/eae clouds dump`):
- `weather.cloud.client.CloudEquivalenceTest.theSavedSkyMeshesExactlyAsBefore`
- `weather.cloud.client.CloudBenchmarkTest.benchmarkDumpedSky`

Both runs below gave identical counts.

## Timings

Sandbox: Claude Code cloud container, 4 cores, 15 GB RAM, Gradle 9.2.1 with the repo's `gradle.properties`
(1 GB daemon heap, parallel, build cache and configuration cache on). No `libs/` folder (fresh checkout).
Task times are from `--profile`. With the configuration cache on, Gradle runs `test` and `runGameTestServer` in
parallel, so the task times add up to more than the wall time.

| Run | Wall time | `compileJava` | `compileTestJava` | `test` | `runGameTestServer` | Notes |
|---|---|---|---|---|---|---|
| Cold | 9m 06s | 28 s | 12 s | 3m 31s | 1m 30s | empty Gradle caches: every dependency downloaded; `createMinecraftArtifacts` 3m 28s, `downloadAssets` 35 s |
| Warm | 3m 44s | 6 s | 2 s | 3m 35s | 1m 23s | `--no-build-cache` with `--rerun` on compile and both test tasks, so all four really ran; Minecraft artifacts up to date |

The unit tests are the critical path in a warm run (about 3.5 min); the game test server, including its startup,
takes about 1.5 min alongside them.

## Notes for later sessions
- During this session Maven Central rate-limited the sandbox's IP (HTTP 429, which Gradle reports as "Could not find
  com.google.code.gson:gson:2.10.1" while resolving the ModDevGradle plugin). The fix was a sandbox-only init script,
  `~/.gradle/init.d/central-mirror.gradle`, that puts Google's Maven Central mirror
  (`https://maven-central.storage-download.googleapis.com/maven2/`) ahead of the declared repositories. It is not in
  the repo and changes no artifact. If a later session hits the same error, recreate it:

  ```groovy
  def mirror = 'https://maven-central.storage-download.googleapis.com/maven2/'
  beforeSettings { settings ->
      settings.pluginManagement.repositories { maven { name = 'CentralMirror'; url = mirror } }
  }
  allprojects {
      buildscript.repositories { maven { name = 'CentralMirror'; url = mirror } }
      repositories { maven { name = 'CentralMirror'; url = mirror } }
  }
  ```
- Not checkable in the cloud: the client run (including GlitchCore and Serene Seasons at runtime), clouds and
  shaders, the PRD model and map GUIs, ten-year climatology runs, the Modrinth smoke test.
