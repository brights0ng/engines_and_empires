# Mod split: repo CLAUDE.md and cloud session prompts (v1.1, 2026-10-16)

Companion to `claude/mod-split-plan.md` (the master plan). Each Claude Code cloud session gets: the **common preamble** (section 2), then **one step prompt** (section 4). The repo copy of the plan lives in `docs/split/` and is frozen; sessions report deviations in their PR description instead of editing it.

## 1. Repo `CLAUDE.md` (goes in the repo root on branch `split`)

```markdown
# Engines and Empires: working rules

NeoForge 1.21.1, Java 21, ModDevGradle 2.0.147, Gradle 9.2.1. Bright (brights0ng) owns design decisions; do not make them. If the plan is silent or wrong, stop at a green commit and say so in the PR.

## Split in progress
The single mod is being split into a family. Read `docs/split/mod-split-plan.md` first, then `docs/split/mod-split-manifest.csv` (every Java file -> target mod) and `docs/split/mod-split-seams.csv`.

Mods (id -> package): paradyne_lib -> dev.brights0ng.paradynelib; sable_fixes -> ...sablefixes; sparse_ores -> ...sparseores; seismic_surveyance -> ...seismicsurveyance; meteodyne -> ...meteodyne; frontier -> ...frontier; simple_spoilage -> ...simplespoilage; engines_and_empires (core) -> dev.brights0ng.enginesandempires.

## Import rules (compile-time graph; must stay acyclic)
lib <- sparse_ores <- seismic_surveyance <- (gated package frontier.seismic only) frontier
lib <- meteodyne;  lib <- simple_spoilage;  everything <- core;  sable_fixes imports nothing of ours.
Never: lib importing a feature mod; Sparse Ores or Seismic importing Frontier; Frontier importing Seismic/Sparse Ores outside `frontier.seismic`; a feature mod importing core.
Placement rule: code that makes our mechanism work or look right stays with the mechanism; code that makes someone else's mod follow ours, or fills in pack-specific content, goes to core.
Optional outside mods (Sable, Cold Sweat, MineColonies, Serene Seasons, Iris, AppleSkin, Diet, Simulated, Aeronautics, Offroad, Diesel Generators): only in `<mod>.compat.<othermod>` or a `sable` sub-package, reached after `ModList.isLoaded`; never in a signature or field of an always-loaded class. Create is a HARD dependency of Seismic and core only.

## Never touch
`pack/`, anything for the Modrinth profile, `tools/profiling`, `run/`. Do not delete tests to make things pass. Do not weaken assertions.

## Commands
Full check (baseline now 1041 unit tests with 2 skipped, 211/211 game tests, after steps 1a-1d): `./gradlew compileJava compileTestJava test runGameTestServer`
Cold compile ~9 min, warm ~5 min, game tests ~3 min on 2 cores / 7 GB. Run in the background and poll; do not run two Gradle builds at once.
If Maven Central answers 429, use the mirror workaround in `docs/split/baseline.md` (kept outside the repo).
A client boot under `xvfb-run` with software graphics is a valid crash-on-boot check in the cloud (see `docs/split/build-layout.md`). Anything visual cannot be tested there; say so rather than guess.

## Conventions
`git mv` for moves (keep history). One logical change per commit. Commit messages say what and why. Lang keys, ids and resource paths change only in the step that says so.
```

## 2. Common preamble (paste at the top of every step prompt)

```text
You are working on the repo brights0ng/engines_and_empires, branch `split` (create the step branch `split-<step>` from it; open the PR into `split`, never into main).
Read first: CLAUDE.md, docs/split/mod-split-plan.md, and the manifest/seams CSVs. They are the master plan; Bright has approved every decision in them. You are an implementer, not a designer: if something is ambiguous or the plan is wrong, do the safest reading, flag it in the PR description under "Questions for Bright", and keep going; if you cannot continue safely, stop at the last green commit and say what is left.

Rules for every step:
- Behaviour must not change. The baseline is 1041 unit tests (2 skipped) and 211/211 game tests (it was 964/202 before the split; steps 1a-1d added tests). From 2b on, the full check also runs each mod's bare game tests. Run `./gradlew compileJava compileTestJava test runGameTestServer` (background it; it takes 5-10 min) before every commit that you intend to keep and at the end. New tests may add to the totals; none may be removed, skipped or weakened to get green.
- Do not touch `pack/`, the Modrinth profile, `tools/profiling` or `run/`. Do not add dependencies the plan does not name.
- Keep commits small and reviewable. Moves are `git mv`.
- If the build is OOM-killed, lower parallelism or heap; do not skip tests.
- If Maven Central rate-limits the sandbox (HTTP 429, reported by Gradle as "Could not find ..."), set up the mirror workaround described in `docs/split/baseline.md`. Keep it outside the repo; never commit it.
- A client boot under xvfb-run is allowed as a crash check. Cannot be checked in the cloud (say so in the PR, do not guess): client rendering, shaders, the PRD model and map GUIs, ten-year climatology runs, the Modrinth smoke test.

Finish with a PR into `split` whose description has: (1) what changed, (2) unit and game test counts before and after, (3) deviations from the plan, (4) Questions for Bright, (5) anything left undone.
```

## 3. Logistics before step 0
Done: the cloud sessions only see the repo, so `CLAUDE.md` (section 1), `docs/split/` and `docs/design/` were committed to branch `split`. Each step merges into `split` by pull request. When the plan changes, update both the Project copy and the repo copy.

## 4. Step prompts

### Step 0: prep (1 session, small)
Goal: a clean, reproducible starting point on branch `split`.
Tasks:
1. Tag the pre-split commit `pre-split` (on the commit `split` was branched from, excluding the docs commit).
2. In `build.gradle` replace `compileOnly files("libs/SereneSeasons-neoforge-1.21.1-10.1.0.9.jar")` with `compileOnly("maven.modrinth:serene-seasons:pHEgQQUE") { transitive = false }`. Make sure the Modrinth maven repository is declared with `exclusiveContent` for `maven.modrinth` if it is not already. For the client run only, add `runtimeOnly "maven.modrinth:glitchcore:S2TfWrZR"` where the other client mods are declared; do not change the existing local `libs/` handling for anything else.
3. Delete the empty stray `CloudConfig.java` at the repo root and the stale template lang keys (`configuration.magicNumber`, `logDirtBlock`, `items`) from `en_us.json`; confirm by grep that nothing references them.
4. Record the baseline: run the full check and write the exact counts and timings to `docs/split/baseline.md`.
Acceptance: full check green at 964 (2 skipped) / 202; `git tag` shows `pre-split`; `git status` clean; PR open.
Out of scope: anything in `pack/`; any refactor.

### Step 1a: entry points, ids, MODID/LOGGER (1 session)
Goal: make the later split mechanical without moving any package.
Tasks:
1. Split `EnginesAndEmpiresMod`'s registration into one entry-point class per future mod, in the final package of that mod (e.g. `dev.brights0ng.meteodyne.Meteodyne` with `public static final String MODID`, `LOGGER`, `static ResourceLocation id(String path)` and `static void init(IEventBus, ModContainer)`). The root `@Mod` class only calls each `init`. Mods: paradyne_lib, sable_fixes (none needed: it has no registrations), sparse_ores, seismic_surveyance, meteodyne, frontier, simple_spoilage, core. For now **every `MODID` is still `"engines_and_empires"`**; they flip in step 2.
2. Replace every read of `EnginesAndEmpiresMod.MODID/LOGGER` (128 files, see `docs/split/modid_refs` in the manifest set) and every literal `"engines_and_empires"` string in Java (34) by the owning feature's constant, per the manifest's target mod. Use `id(...)` instead of `ResourceLocation.fromNamespaceAndPath(MODID, ...)` where trivial.
3. Do not move or rename any other class yet (the entry-point classes are the only new files in final packages).
Acceptance: full check green at the baseline; `grep -r "EnginesAndEmpiresMod.MODID" src` finds nothing outside the core entry class; `grep -rn '"engines_and_empires"' src/main/java` finds only the `MODID` constants.
Out of scope: package moves, seams, resources.

### Step 1b: package moves and mixin configs (1 session, may spill into 2)
Goal: every Java file sits in its final package; still one mod, one jar, one source tree.
Tasks:
1. Script the moves from `docs/split/mod-split-manifest.csv` (main and test). Use `git mv` and rewrite package declarations and imports. Target roots: `dev.brights0ng.paradynelib`, `...sablefixes`, `...sparseores`, `...seismicsurveyance`, `...meteodyne`, `...frontier`, `...simplespoilage`, and the unchanged `dev.brights0ng.enginesandempires` for core. Keep the sub-package structure under each root as in the manifest (e.g. `weather/wind/Shelter.java` -> `paradynelib/wind/Shelter.java` as the manifest says).
2. Classes that used to share a package and now do not need explicit imports; add them. Where a class now references another mod across a forbidden edge, **leave it** (it is a seam handled in 1c); list each one in `docs/split/seams-after-1b.md` so 1c can check them off. The file may be long; that is expected.
3. Split `engines_and_empires.mixins.json` into one config per mod that has mixins (lib 1, sable_fixes 2, sparse_ores 1, frontier 2, meteodyne ~35, simple_spoilage 12, core 13), each with its own `package`, an `IMixinConfigPlugin` (`shouldApplyMixin` returns true only when the target mod is loaded: Sable for lib's `SubLevelSilhouetteMixin` and the two Sable Fixes mixins; MineColonies and Cold Sweat for the core's compat mixins; vanilla targets always true), `required: true` only for configs with no optional targets. Register all configs in the single `neoforge.mods.toml` `[[mixins]]` block. Split `MixinConfigTest` the same way.
4. `enumextensions.json` stays where it is (it moves with Seismic in step 2e), but its `PrdArmPose` class path follows the class (done in 1b, with a unit test).
Acceptance: full check green at the baseline (test count may rise by the new per-mod mixin config tests; the 202 game tests must all pass); `git diff --stat -M` shows moves, not rewrites; the manifest's per-mod file counts match the tree (write a small script and attach its output); `seams-after-1b.md` exists and lists every remaining forbidden edge (about 34).
Out of scope: resolving seams, resources, ids, Gradle changes.

### Step 1c-A: the internal seams (S10-S14, S4, S9) (1 session)
Goal: remove the cross-edges that need no new library API.
Tasks (each as its own commit, in this order; read the plan's section 5 row first):
1. **S10:** drop `OreWorldgen.SKY_SHOWCASE` from `NoiseTerrainProbe`.
2. **S11:** `SeasonProvider` in Meteodyne and the built-in calendar exactly as in plan section 5 "S11 in detail"; core's `SereneSeasonsCompat` registers itself when Serene Seasons is loaded. Keep the debug override. Update tests that assumed NaN without Serene Seasons to set `useBuiltinCalendar=false`; add tests for the calendar (year fraction at 0, quarter, wrap-around; config year length) and for the provider order override > provider > calendar.
3. **S12:** Frontier guard-source registry; core registers `MineColoniesGuards` and the Guard Villagers tag.
4. **S13:** move built-in machine drivers out of `HeatDrivers` into core (registered through `HeatDrivers.register` at core setup); split `heat_power.json`: vanilla sources stay in Meteodyne's data map file, machine entries (including the `#...:portable_engines` `running` driver) go to a core data map file. Resource namespaces are unchanged in this step.
5. **S14:** `LoudMachines` provider interface in Frontier; Create-aware implementation in core; data-map weights stay in Frontier.
6. **S4:** split `LevelChunkFrontierMixin` into Frontier's mixin and Meteodyne's `HeatWorld.onBlockChanged` mixin.
7. **S9:** move `DepositCommand`, `ReadingDeposits` and `RichOreDataGen` pieces to the owner of what each touches; `RichOreDataGen` no longer calls `RefiningDataGen` (core calls its own).
8. **S15 (chips):** move the chip items to Sparse Ores in package `sparseores.chips`: `RefiningItems` (rename `ChipItems`), `ChipTextures`, the chip list and `Chip` record from `Refining`, the chip item models and textures from `RefiningDataGen`, the 2x2 chip recipe, and the chip fuel value. Core's `Refining`/`RefiningRecipes`/`RefiningGameTests` import them. Ids stay `engines_and_empires:*_chip` until step 2e. Chip item weights stay in the core.
9. **Undo temporary visibility (from 1b):** make `MineColoniesGuards` (S12) and `ChipTextures`' shape helpers (S15) package-private/private again once their seams are fixed.
10. **`CloudWorksDebugDumpMixin`:** move it from Meteodyne to core (it patches Diet's library CloudWorks, which is outside-mod compat); keep it `@Pseudo`, and move it into the core's mixin config and its test.
11. **Template leftovers (Q11):** delete the stale lang keys `magicNumberIntroduction` and the three "Example Mod Configs" titles; confirm by grep that nothing uses them.
Acceptance: full check green; the corresponding rows removed from `seams-after-1b.md`; for S13/S14/S12 a unit test proving the default (nothing registered) behaviour and one with a fake registration.
Out of scope: S1-S3, S5-S8 (next step).

### Step 1c-B: the provider and event seams (S1-S3, S5-S8) (1 session, may spill into 2)
Goal: remove the remaining cross-edges using the new library API.
Tasks:
1. Add to `paradynelib`: `Services` (register by key at mod setup, `get` returns the registered provider or a documented default), `TemperatureProvider` (S1), `WindProvider` (S2), `StormProvider` (S3). Defaults: S1 unknown (NaN) so food ages by the clock alone; S2 calm; S3 no storm. Unit tests for each default and for registration.
2. **S1:** `FoodTemperature` uses `TemperatureProvider`; Meteodyne implements it from `Temperature`, `Climate`, `Baseline`, `UndergroundProbe`, `HeatWorld`, `ShipCover`, `WeatherConfig`, `WeatherOwnership`. Behaviour in the pack must be identical (game tests `FoodGameTests` etc. prove it).
3. **S2:** lib wind code reads `WindProvider`; `WindSources` becomes Meteodyne's implementation.
4. **S3:** `FrontierDarkness` reads `StormProvider`; Meteodyne implements it from `WeatherQueries`, `StormLight`, `WeatherOwnership`.
5. **S5:** `ShotSource` moves to Seismic; Seismic posts `ThumpEvent` and `EchoesEvent` (NeoForge event bus, with the data `Deep` needs); `Deep` listens in `frontier.seismic`.
6. **S6:** Frontier `ThumperAccess` interface; `frontier.seismic` implements it over `ChokedThumpers`; `Incursion`/`Incursions` depend only on the interface.
7. **S7:** confirm `Deep` and its Seismic/Sparse Ores imports sit only in `frontier.seismic`.
8. **S8:** Seismic `MapLayer` client extension point; move `TierOverlay` to core and register it; `PrdItemRenderer`/`SmartLoggerRenderer` iterate registered layers.
9. **Decisions from Bright (from the 1c-A review):** (a) The Create blaze burner heat entry is a core machine entry, not a Meteodyne heat source; make sure nothing in Meteodyne code or data references it. (b) Pin `useBuiltinCalendar=false` for the game-test run so climate tests never move with the time of day. First report in the PR what `false` does (no seasons, or a fixed calendar?) and that all 202 still pass with it pinned; if any need the built-in calendar, stop and ask. (c) Restore the lang key `engines_and_empires.configuration.title` with the text "Engines and Empires" so NeoForge's config screen shows the real name. (d) The core compat package names from 1c-A are accepted as `<mod>.compat.<othermod>`.
Acceptance: full check green; `seams-after-1b.md` is empty (every row struck with the commit that fixed it); a unit test per provider default; `FrontierDeepGameTests` passes through the event path.
Out of scope: architecture test, Gradle.

### Step 1d: the architecture test (1 session, small)
Goal: lock the graph before slicing.
Tasks: add a JUnit test (no new dependency; scan `src/main/java` source text) that (a) computes each file's mod from its package, (b) fails on any mention of a package outside the allowed edges of plan section 2, with `frontier.seismic` as the sole Frontier exception, (c) fails when a class outside a `compat` package or a `sable` sub-package mentions an optional outside mod's package (Sable, Cold Sweat, MineColonies, Serene Seasons, Iris, AppleSkin, Diet, Simulated, Aeronautics, Offroad, Diesel Generators), with an `arch-allowlist.txt` for justified exceptions (each line needs a one-line reason; start it empty and add only what you must, flagging each in the PR). Include a check that no Java file under a feature mod mentions `EnginesAndEmpiresMod`. Also assert the plan's file counts per mod from the manifest (±0).
Acceptance: full check green; the test fails if you add a deliberate forbidden import in a scratch commit (show the failure output in the PR, then revert).

### Step 2a: prototype R1 (1 session, throwaway)
Goal: prove which build layout works before touching the real tree.
Tasks: on a throwaway branch, make a minimal multi-project build (`paradyne-lib` + one dependent module, a toy block each, `neoforge.mods.toml` each) using ModDevGradle, and show that `runServer`/`runGameTestServer` from an aggregator project loads both mods (and a client run configuration is at least generated). If it works, document the exact `build.gradle` shapes and the convention plugin in `docs/split/build-layout.md`. If it does not, build the fallback (one project, one source set per mod, `mods {}` block with `sourceSet`s, `addModdingDependenciesTo`) and prove the same thing, plus show the compiler rejecting an import that breaks the graph. Report which layout is recommended and why.
Acceptance: written report in the PR; the toy builds committed on the throwaway branch only (not into `split`).
Result: multi-project layout approved by Bright (one Gradle project per mod; core stays at the root and is the aggregator; bare game tests per mod are part of the full check; the architecture test enforces `frontier.seismic`; client boot allowed as a cloud crash check). Report: `docs/split/build-layout.md`.

### Steps 2b-2f: slicing (one session each; same shape)
**Approved layout (read `docs/split/build-layout.md` sections 1-6 first; follow it exactly):** one Gradle project per mod, shared build logic in `build-logic/` (convention plugin), the root stays core and the dev aggregator, `requireMod(':x')` / `loadMod(':x')` helpers, one Minecraft run at a time via the `MinecraftRunLimit` build service. Rules from the prototype that apply to every slicing step: (1) a `compileOnly` project dependency carries none of its dependencies, so declare outside mods where they are used; runtime-only extras (MineColonies, Iris/Sodium, Guard Villagers, Deeper and Darker, AppleSkin, Diet, Cold Sweat) stay in the root only; (2) every mod with game tests gets its own empty structure under its own namespace, `GameTestStructures` takes the mod id and each mod's datagen adds it; (3) never make a mixin injector cancellable or set a return value at `RETURN` where another mod injects at the same point (`LevelChunkFrontierMixin` halves stay non-cancellable; use MixinExtras `@ModifyReturnValue` if a return value must change); (4) the convention plugin fails the build if a subproject's `mod_id` equals the root's; (5) a mod the aggregator loads but does not require (Sable Fixes) uses `loadMod`; (6) the architecture test must scan every module's `src/main/java` and `src/test/java`; (7) watch Gradle heap (`-Xmx2G` worked in the prototype), raise it only if OOM-killed; (8) the full check now includes each mod's bare `runGameTestServer`.
Common tasks for each module being cut out (use the layout the 2a report chose):
1. Create the module/source set, its `neoforge.mods.toml` (id, version, `license="All Rights Reserved"`, required and optional dependencies exactly as plan section 2, `ordering="AFTER"` where the plan says), its mixin config registration, its `MODID` constant flipped to the real id.
2. Move the module's sources, tests, resources (per plan section 8, by grep ownership), lang keys and `generated` resources; flip namespaces in resource paths and code (`data/engines_and_empires/...` -> `data/<id>/...`) and update every reference, including tags, data maps, loot tables, recipes, structure files and `GameTestStructures` lookups. No aliasing.
3. Network payloads use the mod's own `PayloadRegistrar` and version.
4. Datagen for that mod only, output to its `src/generated/resources`.
5. Commands: the mod's own root; core keeps `/eae <feature>` redirects (core step 2f).
6. Per-mod config specs keep today's defaults.
7. Mixin configs: where a bare run now proves the mixin plugin gating works, set `required: true` on the lib, Sable Fixes and core configs again (1b left them `false`); a skipped mixin is not a failure, but a broken injection should be.
8. Game tests need an empty test structure under each mod's own namespace: have the library's `GameTestStructures` helper take the namespace as a parameter and write one copy per mod, and update each mod's tests to use its own.
9. List in the PR every name you changed that spelled `engines_and_empires` (lang keys, saved data, config file names, entity tags, structure ids), so Bright can review them.
10. Prove: the module's own tests; a **bare** `runGameTestServer` with only its required mods (list the mods loaded in the PR); the aggregate full check at the baseline.
11. Carried forward from 1c-A (single-jar limits; do the part that belongs to your module): (a) S13 machine entries in `heat_power.json` move to core; Meteodyne keeps only its own sources. (b) The S12 Guard Villagers entry in the guards tag moves to core's datapack. (c) `SharedDataGen` (temporary core class building the shared loot-table and `mineable/pickaxe` tag providers) is deleted in the last slicing step; until then each module keeps its own parts. Say in the PR which of these you finished.

- **2b lib + Sable Fixes.** Lib: Sable-gated sub-package, `Services` + providers, `GameTestStructures`. Sable Fixes standalone, requires Sable only, plugin-gated mixins. Bare run for Sable Fixes needs only Sable.
- **2c Meteodyne.** Largest; include `IrisCompat` (Iris optional; move it into a `compat` package so the architecture test passes without an allowlist line), `shaderRuntime` configuration, `-Peae.*` -> `-Pmeteodyne.*` property rename (update `tools/` scripts only if they live outside `pack/` and `tools/profiling`; otherwise list them in the PR for Bright). Decide the wind command's home (lib vs Meteodyne root) and put the choice in "Questions for Bright" if unsure.
- **2d Simple Spoilage + Frontier.** Spoilage requires lib only (Sable optional through lib). Frontier requires lib; Seismic optional via `frontier.seismic` and `neoforge.mods.toml` `type="optional"`; Frontier bare run must pass without Seismic (the `ThumperAccess` default and `Deep` listener absent). `FrontierDeepGameTests` (now in `frontier.seismic`, registers whether or not Seismic is loaded) must be made conditional on Seismic or moved to core before the bare Frontier run.
- **2e Sparse Ores + Seismic.** Sparse Ores: no outside mod; `ordering="AFTER"` Create. Sparse Ores also flips the chip ids to `sparse_ores:*_chip` and updates every reference (core recipes, item weights, rich ore loot). Seismic: lib + Sparse Ores + Create required, Create Diesel Generators optional, `enumextensions.json` moves here, Flywheel/Catnip come with Create. Bare run for Seismic loads lib, Sparse Ores, Create.
- **2f core.** What remains plus glue: refining, weight, `food/compat`, all external compat, `HeatDrivers` registrations, `LoudMachines` impl, `TierOverlay`, `ColdSweatCompat`, MineColonies mixins, Serene provider, the four integration game tests, `defaultconfigs/` (only deviations from mod defaults), `/eae` redirects, pack datapack (Deeper and Darker, Born in Chaos, Naturalist rosters), version 0.4.0. Core requires lib, six feature mods, Sable, Create, Simulated/Aeronautics/Offroad, Encumbered.
Notes from 2c for later steps: (a) core's merged data files for another mod's data map live under that mod's namespace (`data/meteodyne/data_maps/...`), because NeoForge finds a data map's files by the map's own id; this is intentional, keep the pattern. (b) Decided by Bright after 2c: the `/eae weather`, `/eae heat`, `/eae clouds` and `/eae wind` commands are removed for good (deprecated; nothing should rely on them). Core gets NO redirects for those four. Other `/eae` redirects follow the plan for the remaining features. (c) The game-test calendar pin re-applies after a config reload (NeoForge's file watcher can undo it); keep that in any new test-server setup. (d) Meteodyne's optional Iris range is `[1.8,)`; accepted (there is no pack yet, only our mods; the Modrinth profile is a testing ground).
Acceptance for each: the PR lists the mods loaded for the bare run and its result; aggregate full check at the baseline (sum of unit tests across modules, 202 game tests); the architecture test (adapted to the new layout, same rules) green.

### Step 3: verify (1 session + Bright locally)
Cloud: from a clean checkout run `buildAll`, list every jar with its `mods.toml` ids and versions, run each module's bare game tests plus the aggregate. Bright locally: boot the real pack with the new jars (without touching the Modrinth profile: use a copy), clouds/shaders, the PRD model and map GUIs, one climatology run, a thumper-to-Deep-to-incursion walkthrough. The session prepares a checklist for the local run; it does not run it.

### Step 4: tidy (1 session)
Old-doc path map (old `claude/*.md` paths to new ones, written to `docs/split/path-map.md`), delete `docs/split` leftovers that are only scaffolding, update `CLAUDE.md` to the final layout, remove the `split` branch conventions. Bright reviews before merge.
