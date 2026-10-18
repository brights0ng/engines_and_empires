# Build layout for the split (step 2a, R1 prototype report)

Status: **prototype done; layout recommended, awaiting Bright's decision before step 2b.**
Prototype: throwaway branch `split-2a-prototype`, folder `prototype/r1/multiproject/` (three commits, never merged into `split`).
Versions as the real build: NeoForge 21.1.251, ModDevGradle 2.0.147, Gradle 9.2.1, Java 21, configuration cache on.

## 1. Recommendation

**Use the multi-project build** (plan section 9 target): one Gradle project per mod, shared settings in a convention plugin from an included `build-logic/` build, and the root project as the dev aggregator. Risk R1 is retired: ModDevGradle loads mods from several projects in one run. The `mods {}` block takes another project's `SourceSet`, and MDG passes each mod's own classes and resources folders to FML (`-Dfml.modFolders=<modid>%%<folder>:...`).

Why this layout and not the fallback (one project, one source set per mod):
- **Everything the step asked for works**, and more: aggregate game tests, bare game tests per mod, dedicated server, data generation per mod, per-mod jars, and the dev client, which actually boots under Xvfb (section 3).
- **The graph is enforced by Gradle and javac, with no extra machinery.** A project can only import the projects it declares. The library importing Sparse Ores fails to compile; a cycle fails at configuration (section 3, rows 7 and 8). The fallback gives the same compile-time guarantee only if every source set's classpath is wired by hand.
- **Each mod is a normal Gradle project.** It has its own `gradle.properties`, its own `test` task, its own `run*` tasks, its own `src/generated/resources` and its own jar, and needs no custom wiring. Steps 2b-2f become "create a folder, `git mv` sources into it, add one `include` and one `requireMod`".
- **It is the plan's stated target**, so steps 2b-2f and 3 need no rewording.

Costs (none blocks it; each has a mitigation below):
- Each project keeps its own ~70 MB copy of the NeoForge/Minecraft jars in `build/moddev/artifacts`, about **0.6 GB for nine projects**. The expensive decompile/recompile runs once and is shared through `~/.gradle/caches/neoformruntime` (seen as "Waiting for lock on merge_..." while three projects set up in parallel).
- `./gradlew runGameTestServer` runs **every** project's game test run: the aggregate plus one bare run per mod. That is what plan section 7.4 wants, but it adds one server boot per mod, about 12 s each warm on this machine. A build service queues them one at a time (section 5.4).
- MDG generates IDE run configurations for every project that has runs (a bare game test run and a data run per mod). This can't be checked in the cloud; see Questions.

The fallback was **not built**: step 2a says to build it only if the multi-project build fails, and it did not fail.

## 2. Layout

```
settings.gradle            includeBuild('build-logic'), include every mod project
gradle.properties          shared versions + daemon settings; also the root (core) mod's mod_id/name/version
build.gradle               root = core mod + dev aggregator: requireMod(...) for every mod, client/server runs, buildAll
build-logic/
  settings.gradle
  build.gradle             groovy-gradle-plugin + ModDevGradle on its classpath
  src/main/groovy/eae.mod-conventions.gradle          the convention plugin
  src/main/groovy/dev/brights0ng/buildlogic/MinecraftRunLimit.groovy
paradyne-lib/
  gradle.properties        mod_id, mod_name, mod_version, mod_description
  build.gradle             plugins { id 'eae.mod-conventions' }
  src/main/{java,resources,templates/META-INF/neoforge.mods.toml}, src/test/java, src/generated/resources
sparse-ores/               same shape; build.gradle adds requireMod(':paradyne-lib')
```

During 2b-2e the root project stays what it is today (the core mod, still holding everything not yet sliced) and becomes the aggregator. The prototype's root is exactly that: a toy core mod that requires both other mods. Plan section 9 lists a `core/` folder too. Moving core out of the root in 2f is optional with this layout (see Questions).

## 3. What was proved

All commands were run from the repo root as `./gradlew -p prototype/r1/multiproject <task>` on the cloud sandbox (4 cores, 15 GB).

| # | Check | Command | Result |
|---|---|---|---|
| 1 | Compile + unit tests per module, configuration cache on | `compileJava compileTestJava test` | green; `ServicesTest` 2 tests (lib), `SparseOresProviderTest` 1 test (compiles and runs against the lib); configuration cache entry stored |
| 2 | Aggregate game test run loads all mods | `:runGameTestServer` | FML mod list: Engines and Empires 0.4.0, Paradyne Lib 0.1.0, Sparse Ores 0.1.0 (plus minecraft, neoforge); **All 5 required tests passed** (1 lib, 2 Sparse Ores, 2 core) |
| 3 | Bare run, lib alone | `:paradyne-lib:runGameTestServer` | mod list: Paradyne Lib only; **All 1 required tests passed** |
| 4 | Bare run, Sparse Ores + what it requires | `:sparse-ores:runGameTestServer` | mod list: Paradyne Lib, Sparse Ores (no core); **All 2 required tests passed** |
| 5 | Dedicated server from the aggregator | `:runServer` | all three mods constructed; `Done (0.542s)!`; clean save on SIGTERM |
| 6 | Client | `:prepareClientRun :createLaunchScripts`, then `xvfb-run ./gradlew :runClient` with `LIBGL_ALWAYS_SOFTWARE=1` | run files and launch scripts generated for client, server, gameTestServer, data; the client **booted** on Mesa llvmpipe (GL 4.5 core) to the first-launch screen with all three mods (`prototype/r1/evidence/client-under-xvfb.png`) |
| 7 | Compiler rejects a forbidden import | add `import dev.brights0ng.sparseores.SparseOres;` to the lib | `error: package dev.brights0ng.sparseores does not exist` |
| 8 | Gradle rejects a cycle | add `requireMod(':')` (core) to Sparse Ores | `Circular referencing during evaluation for root project 'engines_and_empires'` |
| 9 | Data generation per mod | `:sparse-ores:runData` | `Languages: en_us for mod: sparse_ores`; wrote `sparse-ores/src/generated/resources/assets/sparse_ores/lang/en_us.json` |
| 10 | One jar per mod, own metadata | `assemble`, `buildAll` | `paradyne_lib-0.1.0.jar`, `sparse_ores-0.1.0.jar`, `engines_and_empires-0.4.0.jar` in `build/allJars`, each with its own `neoforge.mods.toml` (`license="All Rights Reserved"`, its own id, version and dependencies) and mixin config |
| 11 | Two mods' mixins in one run, same target method (the S4 shape) | game test `twoModsMixinsMerge` | `neoforge+sparse_ores+engines_and_empires`: both configs applied and both injectors ran (see 5.6 for a trap found on the way) |
| 12 | The repo's standard command | `compileJava compileTestJava test runGameTestServer` | green in 44 s warm; lib, aggregate and Sparse Ores runs queued one after another |

## 4. The files (exact, from the prototype)

### settings.gradle

```groovy
// R1 prototype (step 2a, throwaway): one multi-project build, one Gradle project per mod.
// The root project is the toy core mod and the dev aggregator at the same time, as the real root will be during 2b-2e.
pluginManagement {
    // The shared settings live in a convention plugin built from build-logic/ (an included build).
    includeBuild('build-logic')
    repositories {
        gradlePluginPortal()
    }
}

plugins {
    id 'org.gradle.toolchains.foojay-resolver-convention' version '1.0.0'
}

rootProject.name = 'engines_and_empires'

include 'paradyne-lib'
include 'sparse-ores'
```

### gradle.properties (root: shared versions, and the core mod's own values)

```properties
# Same daemon settings as the real build (configuration cache on, so the prototype proves the layout works with it).
org.gradle.jvmargs=-Xmx2G
org.gradle.daemon=true
org.gradle.parallel=true
org.gradle.caching=true
org.gradle.configuration-cache=true

# Shared versions: subprojects inherit the root project's properties.
parchment_minecraft_version=1.21.1
parchment_mappings_version=2024.11.17
minecraft_version=1.21.1
minecraft_version_range=[1.21.1]
neo_version=21.1.251
loader_version_range=[1,)
mod_license=All Rights Reserved

# The root project is the core mod. Every subproject overrides these four in its own gradle.properties.
mod_id=engines_and_empires
mod_name=Engines and Empires
mod_version=0.4.0
mod_description=Core mod for the Engines and Empires modpack (R1 prototype toy).
```

### paradyne-lib/gradle.properties (every mod project has one; it overrides the root's four mod values)

```properties
mod_id=paradyne_lib
mod_name=Paradyne Lib
mod_version=0.1.0
mod_description=Shared building blocks for the Paradyne mods (R1 prototype toy).
```

### build-logic/build.gradle and build-logic/settings.gradle

```groovy
// Builds the convention plugin (src/main/groovy/eae.mod-conventions.gradle) that every mod project applies.
plugins {
    id 'groovy-gradle-plugin'
}

repositories {
    gradlePluginPortal()
}

dependencies {
    // The same ModDevGradle the real build uses; a precompiled script plugin can only apply plugins on its classpath.
    implementation 'net.neoforged:moddev-gradle:2.0.147'
}
```

```groovy
rootProject.name = 'build-logic'
```

### build-logic/src/main/groovy/eae.mod-conventions.gradle (the convention plugin)

```groovy
// Shared settings for every mod in the family. A mod project's build.gradle applies this plugin, then lists the mods it
// requires with requireMod(':path') and adds its outside dependencies; everything else is here.
//
// Per-mod values come from the project's own gradle.properties (mod_id, mod_name, mod_version, mod_description);
// shared versions come from the root gradle.properties.
plugins {
    id 'java-library'
    id 'net.neoforged.moddev'
}

version = mod_version
group = "dev.brights0ng.${mod_id.replace('_', '')}"

base {
    archivesName = mod_id
}

java.toolchain.languageVersion = JavaLanguageVersion.of(21)

repositories {
    // The real build's repositories (Create, Sable, Modrinth, CurseForge maven) go here once, for every mod.
}

sourceSets.main.resources {
    srcDir('src/generated/resources')
    exclude('**/*.bbmodel')
    exclude('src/generated/**/.cache')
}

// Mod projects this one requires, as Gradle paths (transitively closed). Read by requireMod in the projects that require
// this one, so a mod's runs always load everything it needs.
ext.requiredModPaths = [] as LinkedHashSet

/**
 * Declares that this mod requires the mod built by another project of the family: it compiles against it, and every run
 * of this project (the bare game test run, the data run) loads it, plus whatever that one requires in turn.
 * The mod <-> folder binding goes through ModDevGradle's mods {} block, not the runtime classpath, so each mod is
 * loaded from its own classes and resources folders and exactly once.
 */
ext.requireMod = { String path ->
    evaluationDependsOn(path)
    def required = project(path)
    // compileOnly, not implementation: on the runtime classpath the other mod's jar would be found as a second copy
    // of the mod next to its folders in mods {}. Test code compiles against it too.
    dependencies.add('compileOnly', required)
    dependencies.add('testImplementation', required)
    ([path] + required.requiredModPaths).each { String p ->
        if (requiredModPaths.add(p)) {
            def mod = project(p)
            neoForge.mods.create(mod.mod_id) {
                sourceSet(mod.sourceSets.main)
            }
        }
    }
}

neoForge {
    version = neo_version

    parchment {
        mappingsVersion = parchment_mappings_version
        minecraftVersion = parchment_minecraft_version
    }

    mods {
        "${mod_id}" {
            sourceSet(sourceSets.main)
        }
    }

    runs {
        // The bare game test run: this mod and the mods it requires, nothing else; runs only this mod's game tests.
        gameTestServer {
            type = 'gameTestServer'
            systemProperty 'neoforge.enabledGameTestNamespaces', mod_id
        }

        // This mod's data generator, writing into its own src/generated/resources.
        data {
            data()
            gameDirectory = project.file('run-data')
            programArguments.addAll '--mod', mod_id, '--all',
                    '--output', file('src/generated/resources/').absolutePath,
                    '--existing', file('src/main/resources/').absolutePath
        }

        configureEach {
            systemProperty 'forge.logging.markers', 'REGISTRIES'
            logLevel = org.slf4j.event.Level.INFO
        }
    }
}

// One Minecraft run at a time across the whole build: `./gradlew runGameTestServer` matches every project's run task.
def minecraftRunLimit = gradle.sharedServices.registerIfAbsent('minecraftRunLimit', dev.brights0ng.buildlogic.MinecraftRunLimit) {
    maxParallelUsages = 1
}
tasks.matching { it.name in ['runClient', 'runServer', 'runGameTestServer', 'runData'] }.configureEach {
    usesService(minecraftRunLimit)
}

// Every mod has a neoforge.mods.toml template under src/main/templates, expanded with the same properties.
def generateModMetadata = tasks.register('generateModMetadata', ProcessResources) {
    def replaceProperties = [
            minecraft_version_range: minecraft_version_range,
            neo_version            : neo_version,
            loader_version_range   : loader_version_range,
            mod_id                 : mod_id,
            mod_name               : mod_name,
            mod_license            : mod_license,
            mod_version            : mod_version,
            mod_description        : mod_description,
    ]
    inputs.properties replaceProperties
    expand replaceProperties
    from 'src/main/templates'
    into 'build/generated/sources/modMetadata'
}
sourceSets.main.resources.srcDir generateModMetadata
neoForge.ideSyncTask generateModMetadata

dependencies {
    testImplementation platform('org.junit:junit-bom:5.11.4')
    testImplementation 'org.junit.jupiter:junit-jupiter'
    testRuntimeOnly 'org.junit.platform:junit-platform-launcher'
}

tasks.named('test') {
    useJUnitPlatform()
}

tasks.withType(JavaCompile).configureEach {
    options.encoding = 'UTF-8'
}
```

### build-logic/src/main/groovy/dev/brights0ng/buildlogic/MinecraftRunLimit.groovy

```groovy
package dev.brights0ng.buildlogic

import org.gradle.api.services.BuildService
import org.gradle.api.services.BuildServiceParameters

/**
 * A build service with no state: every Minecraft run task uses it, and it allows one user at a time, so the bare runs of
 * each mod and the aggregate run queue up instead of starting one game per mod at once under org.gradle.parallel.
 */
abstract class MinecraftRunLimit implements BuildService<BuildServiceParameters.None> {
}
```

### A mod with no requirements: paradyne-lib/build.gradle

```groovy
plugins {
    id 'eae.mod-conventions'
}
// The library requires no other mod of ours.
```

### A mod that requires another: sparse-ores/build.gradle

```groovy
plugins {
    id 'eae.mod-conventions'
}

requireMod(':paradyne-lib')
```

### The root: core mod + dev aggregator (build.gradle)

```groovy
// The root project: the toy core mod, which requires every mod, and the dev aggregator whose client, server and game
// test runs load all of them.
plugins {
    id 'eae.mod-conventions'
}

requireMod(':paradyne-lib')
requireMod(':sparse-ores')

neoForge {
    runs {
        client {
            client()
            systemProperty 'neoforge.enabledGameTestNamespaces', allModIds()
        }
        server {
            server()
            programArgument '--nogui'
            systemProperty 'neoforge.enabledGameTestNamespaces', allModIds()
        }
        // The aggregate game test run: core's own run already loads every mod; it runs every mod's game tests.
        gameTestServer {
            systemProperty 'neoforge.enabledGameTestNamespaces', allModIds()
        }
    }
}

String allModIds() {
    ([mod_id] + requiredModPaths.collect { project(it).mod_id }).join(',')
}

// Every mod's jar in one folder (build/allJars), for copying into a test instance; separate jars, no jar-in-jar.
tasks.register('buildAll', Sync) {
    from(tasks.named('jar'))
    requiredModPaths.each { from(project(it).tasks.named('jar')) }
    into(layout.buildDirectory.dir('allJars'))
}
```

### A mod's metadata template: sparse-ores/src/main/templates/META-INF/neoforge.mods.toml

```toml
modLoader="javafml"
loaderVersion="${loader_version_range}"
license="${mod_license}"

[[mods]]
modId="${mod_id}"
version="${mod_version}"
displayName="${mod_name}"
authors="brights0ng"
description='''
${mod_description}
'''

[[dependencies.${mod_id}]]
    modId="neoforge"
    type="required"
    versionRange="[${neo_version},)"
    ordering="NONE"
    side="BOTH"

[[dependencies.${mod_id}]]
    modId="minecraft"
    type="required"
    versionRange="${minecraft_version_range}"
    ordering="NONE"
    side="BOTH"

[[dependencies.${mod_id}]]
    modId="paradyne_lib"
    type="required"
    versionRange="[0.1.0,)"
    ordering="AFTER"
    side="BOTH"
[[mixins]]
    config="sparse_ores.mixins.json"
```

## 5. Rules learned (apply these in 2b-2f)

1. **Bind mods to runs through `mods {}`; compile against our own mods with `compileOnly`.** `requireMod(':x')` does both. As an experiment the prototype also tried `implementation project(':x')`. It also passed (5/5, each mod loaded once), but Gradle then built every required mod's jar before each run and put it on the run classpath next to that mod's folders, and FML dropped the second copy without logging anything. `compileOnly` keeps one path, and it is the one MDG documents.
2. **Compile visibility is direct only.** `compileOnly` is not transitive, so a mod can import exactly the mods its own `build.gradle` names. Core must `requireMod` every mod it imports, not just the top of a chain. Runs are closed transitively: `requireMod(':seismic-surveyance')` also loads Sparse Ores and the lib.
3. **Outside mods are declared where they are used.** A `compileOnly` project dependency carries none of that project's dependencies. Seismic declares Create, and core declares Create again. Runtime-only extras for dev runs (the MineColonies jars synced into `run/mods`, Iris/Sodium through `shaderRuntime`, Guard Villagers, Deeper and Darker, AppleSkin, Diet, Cold Sweat at runtime) stay in the root aggregator only, so bare runs stay bare. Repositories go in the convention plugin once.
4. **One Minecraft run at a time.** `./gradlew runGameTestServer` matches the run task in every project, and with `org.gradle.parallel=true` the first prototype run started three game servers at once. The shared build service `MinecraftRunLimit` (`maxParallelUsages = 1`, used by `runClient`, `runServer`, `runGameTestServer`, `runData`) queues them. To run only the aggregate, use `./gradlew :runGameTestServer`; for one bare run, `:<project>:runGameTestServer`.
5. **Game test templates must be in the test's own namespace** (NeoForge runs only tests whose template is in the holder's mod id; today's `GameTestStructures` javadoc says the same). So every mod with game tests needs its own `data/<modid>/structure/gametest/empty.nbt`. The prototype copies today's file into each mod. Plan section 6 puts `GameTestStructures` in the lib. That fits if it becomes a data provider that takes the mod id, and each mod's data run adds it for itself.
6. **Mixin trap for S4.** A cancellable `@Inject` at `RETURN` that calls `setReturnValue` returns from the target at once, so another mod's injector at the same point never runs. The prototype's first run showed exactly this (`neoforge+sparse_ores`: core's injector was skipped). Today's `LevelChunkFrontierMixin` is a plain, non-cancellable `@Inject(method = "setBlockState", at = @At("RETURN"))`, so splitting it in two is safe if both halves stay non-cancellable. When a return value must change, MixinExtras' `@ModifyReturnValue` (bundled with NeoForge; the prototype's final mixins use it) chains across mods.
7. **Property inheritance.** Subprojects inherit the root's properties, so a project whose `gradle.properties` forgets `mod_id` silently becomes `engines_and_empires`. The real convention plugin should fail the build when a subproject's `mod_id` equals the root's. This guard is not in the prototype.
8. **A mod the aggregator loads but does not require** (Sable Fixes: core does not require it, but plan section 9 has the dev runs load every mod) needs the `mods {}` half of `requireMod` without the `compileOnly`. That is a three-line `loadMod(':sable-fixes')` next to `requireMod`. It is not prototyped, but it is the same `neoForge.mods.create` call.
9. **`runServer` gets no console input through Gradle** (Gradle does not forward stdin to the game), so `stop` can't be typed. Use the IDE run configuration or `build/moddev/runServer.sh` (written by `createLaunchScripts`) for an interactive server. SIGTERM saves and stops cleanly.
10. **Data runs per mod.** Each project's `data` run loads that mod plus what it requires, uses its own `run-data/` folder (as today's, which keeps MineColonies out), and writes into its own `src/generated/resources` with `--mod <its id>`.
11. **The client can be booted in the cloud.** `xvfb-run` plus Mesa llvmpipe (`LIBGL_ALWAYS_SOFTWARE=1`) is installed and starts the dev client. That is a crash-on-boot smoke test for client code paths (mod constructors, client setup, resource loading). It checks nothing visual: clouds, shaders, the PRD model and map GUIs still need Bright's machine.

## 6. Applying it to the real build

- **2b** creates `build-logic/` and moves the shared parts of today's `build.gradle` into the convention plugin: repositories, Java toolchain, Parchment, `generateModMetadata` (with today's extra `*_version_range` properties), JUnit setup, UTF-8, and the `test` task's `-Peae.harness*` pass-through. The root keeps everything that is core's or the aggregator's: today's dependencies, the `colonyRuntime`/`shaderRuntime` syncs into `run/mods`, the run definitions with their `-Peae.*` properties, core's own data run, and publishing. Then it adds `include 'paradyne-lib', 'sable-fixes'`, `requireMod(':paradyne-lib')` and `loadMod(':sable-fixes')` to the root.
- **2c-2e** each add one or two `include` lines and the matching `requireMod` in the root; the moved code needs no build changes beyond its own `build.gradle` (a few lines: `requireMod`, its outside dependencies).
- **Frontier's optional Seismic edge (2d):** `compileOnly project(':seismic-surveyance')` in Frontier with **no** `mods {}` binding, so Frontier's bare run loads without Seismic (plan 2d) and the aggregate run still loads it through core. Gradle can't confine that import to `frontier.seismic` inside one source set. The architecture test from 1d keeps enforcing the package rule (see Questions). Not prototyped.
- **Architecture test:** after slicing, sources live in several `src/main/java` folders; the test must scan all of them (2b adapts it, as the plan says).
- **Disk and memory:** about 70 MB of MDG artifacts per project (≈0.6 GB for nine). The prototype ran with `org.gradle.jvmargs=-Xmx2G`; today's real build uses `-Xmx1G` for one project. Watch configuration memory in 2b, and raise the heap if Gradle runs short (per the rules: lower parallelism or heap only if OOM-killed).
- **Timings:** the toy's numbers don't predict the real ones. Expect one extra game test server boot per sliced mod in the full check (the toy's took ~12 s each; the real mods, Meteodyne and Create especially, boot slower).

## 7. Fallback, for the record (not built)

| | Multi-project (recommended) | One project, one source set per mod |
|---|---|---|
| MDG support | `mods {}` with other projects' source sets: undocumented but proved here (R1) | `mods {}` + `addModdingDependenciesTo`: documented |
| Graph enforcement | automatic: project dependencies; javac and Gradle reject violations (rows 7, 8) | each source set's `compileClasspath` wired by hand; same javac guarantee once wired |
| Per-mod tests, runs, jars, datagen | free: every project has `test`, `run*`, `jar`, its own `src/generated` | hand-made per source set: a `Test` task, a `Jar` task, a metadata task, runs with `loadedMods` and `sourceSet` |
| Bare runs | the project's own `runGameTestServer` | a run per mod, each with `loadedMods` set by hand |
| Disk | ~70 MB MDG artifacts per project | one copy |
| Folder layout matches plan section 9 | yes | no (all mods under one `src/<mod>/`) |

## 8. Not checked here

- IDE run configurations (IntelliJ/Eclipse/VS Code): generated by MDG at IDE sync, not in the cloud. Bright should open the prototype once in IntelliJ to see the run list (one bare game test run and one data run per mod, plus the aggregate's client/server/game test runs).
- Rendering, shaders, GUIs (the client booted, nothing visual was judged).
- The real mods in this layout: the prototype uses toy mods only. Step 2b is the first real test.

## 9. Added in step 4b: the core-less combined run

`coreless/` is a Gradle project that builds no mod. It applies `eae.family-base` (split out of the convention plugin:
Java, repositories, NeoForge and Parchment, `requireMod`/`loadMod`, run logging and the one-run-at-a-time service), not
`eae.mod-conventions` (which applies the base and adds the project's own mod, its metadata, bare and data runs, JUnit
and jar settings). It `loadMod`s the seven feature mods, puts on its runtime classpath what they require from outside
(Create with Ponder, Flywheel and Registrate for Seismic; Sable for Sable Fixes; Deeper and Darker from Frontier's
`requiredRuntimeElements`), and has one run, `gameTestServer`, whose namespaces are the seven mod ids. Being a
`runGameTestServer` task, it is part of the full check. It has no `gradle.properties` and no `src/`, which is how
`ArchitectureTest` tells it apart from a mod project (and it fails if sources ever appear there).

