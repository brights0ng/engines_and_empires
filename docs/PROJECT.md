# Engines and Empires: shared project instructions

This is the shared instruction source for everyone working on the project. Read it before starting work. Paths in this document are relative to the repository root. Keep general project rules here; keep assistant-specific roles and preferences in the root `AGENTS.md` (ChatGPT/Codex) and `CLAUDE.md` (Claude).

Bright's current instructions take precedence over older project notes. Historical descriptions, implementation status, and test counts can become stale; check the current code and relevant verification evidence, and flag discrepancies instead of silently changing settled behavior.

## File attribution and collaboration
- Credit who wrote the content, not who created its current file. Preserve original authorship when moving or copying content, and add an accurate description of your contribution (for example, `written by Claude; restructured by ChatGPT`). Do not guess unknown authorship or track every minor edit separately.
- Put a creator comment at the top of newly authored files. When editing files that predate the working session, add an editor comment, preferably at the top. Use your own name (ChatGPT or Claude); wording may reflect the actual contribution. A working session can span chats.
- `AGENTS.md`, `CLAUDE.md`, and `docs/PROJECT.md` are exempt from authorship markings.
- Use valid comment syntax. If the format cannot contain comments, raise that with Bright before changing it; do not make the file invalid to add attribution.
- Check existing changes before editing. Preserve other contributors' work and keep changes focused so Bright, Claude, and ChatGPT can review and coordinate them.

## Platform and design ownership

NeoForge 1.21.1, Java 21, ModDevGradle 2.0.147, Gradle 9.2.1. Bright (brights0ng) owns design decisions; do not make them. If a plan is silent or wrong, stop at a green commit and say so in the PR.

## The mod family
One multi-project Gradle build, one project per mod. The root project is the core mod **and** the dev aggregator: its client, server, game test and data runs load every mod of the family.

| Folder | Mod id | Package (`dev.brights0ng.`) | Requires (ours) | Requires (outside) |
|---|---|---|---|---|
| `paradyne-lib/` | `paradyne_lib` | `paradynelib` | | |
| `sable-fixes/` | `sable_fixes` | `sablefixes` | (nothing of ours) | Sable |
| `sparse-ores/` | `sparse_ores` | `sparseores` | lib | |
| `seismic-surveyance/` | `seismic_surveyance` | `seismicsurveyance` | lib, Sparse Ores | Create |
| `meteodyne/` | `meteodyne` | `meteodyne` | lib | |
| `frontier/` | `frontier` | `frontier` | lib (Seismic optional, `frontier.seismic` only) | Deeper and Darker |
| `simple-spoilage/` | `simple_spoilage` | `simplespoilage` | lib | |
| root (`src/`) | `engines_and_empires` | `enginesandempires` | lib and the five feature mods (not Sable Fixes) | Sable, Create, Simulated, Aeronautics, Offroad, Encumbered |

Plan section 2 (`docs/history/mod-split-plan.md`) has the optional dependencies. New mods are 0.1.0, the core 0.4.0; every licence is All Rights Reserved. Shared build settings are the convention plugin `build-logic/src/main/groovy/eae.mod-conventions.gradle`, which applies `eae.family-base.gradle` (what a project that only runs mods shares with the mods); `docs/build-layout.md` explains the layout. One more project, `coreless/`, is a test-only mod (`coreless_tests`, package `corelesstests`, never released, no jar; split step 4c): its game test run is the feature mods' combined run without the core, and it holds the game tests of feature mods working together that need no core code. The aggregate loads it too, so those tests run with and without the core; `buildAll` leaves it out.

Background on behaviour that must not change: `docs/design/` (eight read-only reference notes; see its README; `docs/path-map.md` maps their old `claude/*.md` paths). Known problems: `docs/known-issues.md`. History of the split: `docs/history/mod-split-plan.md`. `docs/split/mod-split-manifest.csv` (every pre-split Java file and the mod it went to) stays because `ArchitectureTest` reads it.

## Import rules (compile-time graph; must stay acyclic)
lib <- sparse_ores <- seismic_surveyance <- (gated package frontier.seismic only) frontier
lib <- meteodyne;  lib <- simple_spoilage;  everything <- core;  sable_fixes imports nothing of ours.
Never: lib importing a feature mod; Sparse Ores or Seismic importing Frontier; Frontier importing Seismic/Sparse Ores outside `frontier.seismic`; a feature mod importing core.
Placement rule: code that makes our mechanism work or look right stays with the mechanism; code that makes someone else's mod follow ours, or fills in pack-specific content, goes to core.
Optional outside mods (Sable, Cold Sweat, MineColonies, Serene Seasons, Iris, AppleSkin, Diet, Simulated, Aeronautics, Offroad, Diesel Generators): only in `<mod>.compat.<othermod>` or a `sable` sub-package, reached after `ModList.isLoaded`; never in a signature or field of an always-loaded class. Create is a HARD dependency of Seismic and core only. Data that names an optional mod's content uses `"required": false` (tags) or a `neoforge:mod_loaded` condition (data map entries, loot tables, biome modifiers).
Gradle and javac enforce the graph between projects; `ArchitectureTest` (core unit test) also checks source text, including comments and strings. Its allowlist (`src/test/resources/arch-allowlist.txt`) keeps two justified lines (`HeatPlayerGameTests`, `WeatherChecksGameTests`); do not add more without Bright.

## Never touch
`pack/`, anything for the Modrinth profile, `tools/profiling`, `run/`. Do not delete tests to make things pass. Do not weaken assertions. Do not edit `docs/design/`.

## Commands
- Full check: `./gradlew compileJava compileTestJava test runGameTestServer`. Baseline after split step 4d: 1066 unit tests (2 skipped, the `EAE_BENCH` cloud benchmarks), 229/229 aggregate game tests, every bare run green (Frontier 17, Meteodyne 67, lib 3, Sable Fixes 2, Seismic 20, Spoilage 34, Sparse Ores 12 both bare and bare with Create), and the core-less combined run 178/178 (4 of them the test-only mod's). New tests add to it; none may be removed or skipped without Bright.
- `runGameTestServer` runs the aggregate (`:runGameTestServer`, every mod), one bare run per mod (`:<project>:runGameTestServer`, only that mod and what it requires) and the core-less combined run (`:coreless:runGameTestServer`: the seven feature mods and what they require, Create, Sable and Deeper and Darker, no core; every feature mod's game tests and the test-only mod's; `-Pcoreless.namespaces=<ids>` narrows it); Sparse Ores has a second bare run with Create (`:sparse-ores:runGameTestServerWithCreate`, started after its bare run); a build service runs them one at a time. Bright's standing principle: every mod runs complete without the core and the feature mods work with each other without it (`docs/standalone-audit.md`).
- Data: `:<project>:runData` writes that mod's `src/generated/resources`; `:runData` is the core's. If the root's `:runData` hangs during startup (third-party class-init deadlock among Simulated, Veil and Create), rerun it; it is not our code. Commit the regenerated files; a data run on unchanged code must leave no diff.
- Jars: `./gradlew build buildAll` puts every mod's jar in `build/allJars` (separate jars, no jar-in-jar; game test classes are left out of the jars).
- Cold compile ~9 min, warm ~5 min, game tests ~3-5 min on 2-4 cores. Run in the background and poll; do not run two Gradle builds at once.
- If Maven Central answers 429 (Gradle reports it as "Could not find com.google.code.gson:gson:2.10.1" while resolving the ModDevGradle plugin), put this sandbox-only init script in `~/.gradle/init.d/central-mirror.gradle` (it is not part of the repo and changes no artifact):
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
- A client boot under `xvfb-run` with software graphics (`LIBGL_ALWAYS_SOFTWARE=1 xvfb-run ./gradlew :runClient`) is a valid crash-on-boot check in the cloud. Anything visual (rendering, shaders, GUIs, the PRD model and maps), long climatology runs and the Modrinth smoke test cannot be tested there; say so rather than guess.

## Adding a mod
1. A folder with `gradle.properties` (`mod_id`, `mod_name`, `mod_version=0.1.0`, `mod_description`; the plugin fails a project that keeps the root's id), a `build.gradle` that applies `id 'eae.mod-conventions'` and calls `requireMod(':paradyne-lib')` (and any other mod it requires) plus its outside dependencies, and `src/main/templates/META-INF/neoforge.mods.toml` (copy a sibling's; `type="optional"` and `ordering` for optional mods, licence `${mod_license}`).
2. `include '<folder>'` in `settings.gradle`; in the root `build.gradle`, `requireMod(':<folder>')` if the core requires it, else `loadMod(':<folder>')`, so the dev runs and `buildAll` include it.
3. Code in `dev.brights0ng.<id without underscores>`, with its own entry class holding `MODID` and `LOGGER`. Mixins: its own `<id>.mixins.json` with an `IMixinConfigPlugin` that skips mixins whose target mod is absent.
4. Game tests: `@GameTestHolder(MODID)` classes named `*GameTests` (that suffix is what keeps them out of the jar), using the empty structure the lib's `GameTestStructures` provider writes for the mod's own namespace from its data run.
5. Add the mod to `ArchitectureTest`'s `ROOTS` and `ALLOWED` (and `REQUIRED` for outside mods it requires), to plan section 2, and to the table above. If the core requires it, `CoreMetadataTest` pins the core's dependency list.
6. Prove: its own tests, its bare `:<folder>:runGameTestServer` with only its required mods (list them in the PR), and the full check.

## Conventions
`git mv` for moves (keep history). One logical change per commit. Commit messages say what and why. Ids, lang keys and resource paths are public: change one only when Bright asks, and list every rename in the PR. Optional-mod glue never makes the mod it lives in require that mod.
