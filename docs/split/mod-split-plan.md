# Splitting the core mod into a mod family (plan v3, 2026-10-16)

Status: **plan settled, nothing built.** Every question from v2 is answered (section 13). Decisions marked (Bright) are his. Facts come from the working tree at commit `9ebdb39` (clean; same as GitHub `brights0ng/engines_and_empires`): 530 main Java files (68.7k lines, including 31 game test classes that live in `src/main`) plus 124 unit test files. Companions: `claude/mod-split-manifest.csv` (every Java file -> target mod), `claude/mod-split-seams.csv` (every reference that would cross a forbidden boundary), and `claude/mod-split-steps.md` (the repo `CLAUDE.md` and one prompt per Claude Code cloud session).

## 1. Decisions (Bright)
1. Eight mods: **Paradyne Lib** (library), **Sable Fixes**, **Sparse Ores** (ore generation), **Seismic Surveyance**, **Meteodyne** (clouds, weather, temperature), **Frontier**, **Simple Spoilage**, and **Engines and Empires** (the core modpack mod).
2. Ids are the names in snake_case; packages are `dev.brights0ng.<name without underscores>`; the core keeps `dev.brights0ng.enginesandempires`.
3. **Sable** is required for Sable Fixes and the core mod; optional for everything else that touches it.
4. **Sable Fixes is standalone** (needs Sable only, not the library).
5. **Required dependencies:** every mod requires the library (except Sable Fixes); Seismic Surveyance requires Sparse Ores **and Create**; the core mod requires every mod except Sable Fixes. Everything else is optional wherever possible. **Create Diesel Generators is a soft dependency of Seismic** (its thumpers burn fuels by fluid tag, so no code depends on it).
6. **Placement rule:** a mechanism that is its own thing gets its own mod (food spoilage); anything that only hooks into outside mechanisms goes in the core mod. The library gives common building blocks and lets the mods talk to each other.
7. **Frontier owns** everything about mob spawning, including the "thumper shots stir up deposits" code (`frontier/deep`).
8. Rich ore blocks live in Sparse Ores; refining (chips, recipe cuts, borehead) lives in the core mod.
9. No save compatibility with pre-split worlds (test worlds only).
10. Versions: new mods start at **0.1.0**; the core mod goes to **0.4.0**. **All licences are All Rights Reserved.**
11. Order: package-first inside the single mod, then slice the build. The plan is settled before any code; implementation runs in Claude Code cloud sessions, one pull request per step into a `split` branch for Bright's review.

## 2. The family

| Mod | id | Package | Requires | Optional |
|---|---|---|---|---|
| Paradyne Lib | `paradyne_lib` | `dev.brights0ng.paradynelib` | NeoForge | Sable |
| Sable Fixes | `sable_fixes` | `dev.brights0ng.sablefixes` | Sable | |
| Sparse Ores | `sparse_ores` | `dev.brights0ng.sparseores` | lib | Create (zinc ore is found by block id only; load after it when present) |
| Seismic Surveyance | `seismic_surveyance` | `dev.brights0ng.seismicsurveyance` | lib, Sparse Ores, **Create** (brings Flywheel and Catnip) | Create: Diesel Generators, Sable |
| Meteodyne | `meteodyne` | `dev.brights0ng.meteodyne` | lib | Sable, Iris |
| Frontier | `frontier` | `dev.brights0ng.frontier` | lib | Seismic Surveyance (through one gated package) |
| Simple Spoilage | `simple_spoilage` | `dev.brights0ng.simplespoilage` | lib | Sable (ship holds, through the lib) |
| Engines and Empires | `engines_and_empires` | `dev.brights0ng.enginesandempires` | lib, the six feature mods, Sable, Create, Simulated/Aeronautics/Offroad, Encumbered | Cold Sweat, MineColonies, Serene Seasons, AppleSkin, Diet, Guard Villagers, Deeper and Darker, Born in Chaos, Naturalist |

Compile-time graph (must stay acyclic): lib <- Sparse Ores <- Seismic <- (gated) Frontier; lib <- Meteodyne; lib <- Simple Spoilage; everything <- core. Sable Fixes stands outside it. Seismic and Sparse Ores never reference Frontier.

Main lines today: Meteodyne ~36.9k (203 files), Seismic ~16.0k (102), Frontier ~8.0k (61), Sparse Ores ~6.3k (50), Simple Spoilage ~5.1k (47), core ~3.9k (42), lib ~2.5k (23), Sable Fixes ~0.1k (2).

## 3. Why the split is cheap
A scan assigning every file to its target mod finds only **34 forbidden references** plus **128 mechanical ones** (files reading `EnginesAndEmpiresMod.MODID/LOGGER`). The 34 reduce to the 14 seams in section 5. Outside-mod dependencies are small: Sparse Ores imports **no** outside mod; Meteodyne uses only Sable (9 files), Create/Simulated/Aeronautics (`HeatDrivers`, moving to core) and Iris (`IrisCompat`); Simple Spoilage only Sable (1 file); Frontier only Create (`LoudMachines`, moving to core); Seismic Create (18 files), Flywheel (7), Catnip (3), all now hard dependencies.

## 4. Placement rule, applied
If the code makes **our** mechanism work or look right, it stays with the mechanism; if it makes **someone else's** mod behave according to ours, or fills in pack-specific content, it goes to the core mod.

| Item | Where | Why / how |
|---|---|---|
| Cold Sweat (`ColdSweatCompat`, hearth mixins, water, modifiers) | core | hooks theirs; uses Meteodyne's temperature API |
| MineColonies weather behaviour (7 mixins) | core | changes theirs; Meteodyne exposes the local-weather queries (`ColonyWeather` moves with them) |
| Serene Seasons (`SereneSeasonsCompat`) | core | core registers it as Meteodyne's `SeasonProvider` (S11) |
| AppleSkin, Diet (`food/compat`) | core | stated rule |
| Encumbered weight (`weight/`, 3 mixins) | core | stated rule |
| Refining (`oregen/refining`, `BoreheadBreakingMixin`, chips, Create recipes, iron golem loot) | core | stated rule |
| MineColonies guard counting (`MineColoniesGuards`) | core | Frontier defines a guard-source registry; core registers MineColonies and the Guard Villagers tag |
| Machine heat drivers for Create/Simulated/Aeronautics/Diesel/Cold Sweat (`HeatDrivers` built-ins; machine entries of `heat_power.json`) | core | `HeatDrivers.register(...)` already exists; the data map splits (vanilla heat sources stay in Meteodyne) |
| Create loudness of machines (`LoudMachines`) | core | Frontier keeps the data-map weights; Create-aware weighting becomes a core-registered provider |
| PRD/logger tier overlay (`TierOverlay`) | core | glue between Frontier and Seismic (S8) |
| Pack mob rosters and tags (Deeper and Darker, Born in Chaos, Naturalist) | core datapack | Frontier ships vanilla-only defaults |
| `IrisCompat` (cloud shaders under Iris) | Meteodyne | the renderer is hidden under shaders without it; core ships the shader preset |
| Sable ship weather/wind/heat (`ShipWeather`, `WindShips`, `HeatRegion`) | Meteodyne / lib, behind Sable | the mechanism itself; Sable optional |
| `NoiseGeneratorSettingsMixin` (vanilla ore veins off) | Sparse Ores | part of the ore mechanism |
| PRD arm-pose enum extension (`enumextensions.json`) | Seismic | its only user |

## 5. The seams (what must change in code)
"After" is the end state.

| # | Today | After |
|---|---|---|
| S1 | `FoodTemperature` reads Meteodyne internals (`Temperature`, `Climate`, `Baseline`, `UndergroundProbe`, `HeatWorld`, `ShipCover`, `WeatherConfig`, `WeatherOwnership`) | Lib `TemperatureProvider` (air at a position, room offset, seasonal mean; NaN = unknown). Meteodyne implements; Simple Spoilage asks. No provider: food ages by the clock alone (today's behaviour outside the Overworld). |
| S2 | Lib wind code (`WindShips`, `WindCommand`) reads `WindSources` -> Meteodyne's atmosphere | Lib `WindProvider`; Meteodyne's `WindSources` becomes the implementation. No provider: calm. |
| S3 | `FrontierDarkness` reads `WeatherQueries`, `StormLight`, `WeatherOwnership` | Lib `StormProvider` (storm darkness and thunder at a position). No provider: no storm. |
| S4 | `LevelChunkFrontierMixin` does Frontier's work and `HeatWorld.onBlockChanged` | Two mixins, one per mod (Mixin merges injectors into the same method). |
| S5 | `SeismicShots`/`CombustiveThumperBlockEntity` call `Deep.of(level).onThump/onEchoes`; `ShotSource` lives in Frontier | `ShotSource` moves to Seismic; Seismic posts `ThumpEvent` and `EchoesEvent` on the NeoForge bus; Frontier's gated package listens. |
| S6 | `Incursion`/`Incursions` call `ChokedThumpers` | Frontier-owned `ThumperAccess` interface; `frontier.seismic` supplies the Seismic-backed implementation (no thumpers when absent). |
| S7 | `Deep` uses `DepositEchoes/Finder/Scanner`, `SeismicWave`, `WaveModel`, `Realm` | Stays in Frontier, in `frontier.seismic`, the only Frontier package allowed to import Seismic/Sparse Ores. |
| S8 | `PrdItemRenderer`/`SmartLoggerRenderer` draw Frontier's tier map (`TierOverlay`, `TierGrid`, `TierMapPayloads`) | Seismic `MapLayer` client extension point; core registers the tier layer. |
| S9 | `DepositCommand`, `ReadingDeposits`, `RichOreDataGen` reach into Seismic; `RichOreDataGen` calls `RefiningDataGen` | Each piece moves to the mod that owns what it touches; each mod runs its own datagen. |
| S10 | `NoiseTerrainProbe` reads `OreWorldgen.SKY_SHOWCASE` (lib -> Sparse Ores) | Drop the flag from the lib class. |
| S11 | `SeasonSource` references `SereneSeasonsCompat` | `SeasonProvider` interface in Meteodyne plus a built-in calendar (below). |
| S12 | `GuardTypes` references `MineColoniesGuards` | Frontier guard-source registry; core registers. |
| S13 | `HeatDrivers` has Create/Simulated/Aeronautics/Diesel/Cold Sweat drivers built in | Drivers move to core, registered through `HeatDrivers.register`. |
| S14 | `LoudMachines` uses Create's kinetic classes | Provider interface; Create-aware implementation in core. |

Mechanical: per-mod `MODID`/`LOGGER` constants replace `EnginesAndEmpiresMod` (128 files); literal `"engines_and_empires"` strings in code (34) and resources become the owning mod's id.

### S11 in detail: the "very simple" built-in calendar
Today `SeasonSource` (in `weather/climate`) returns `yearFraction(Level)` in [0,1) (0 = start of spring) from Serene Seasons, or NaN ("no seasons") without it, plus `available()`, `describe(level)` and the debug override (`setOverride/clearOverride/overridden`, used by `ClimatologyGameTests`, `WeatherCommand`, `WeatherMapBuilder`, `WeatherSim`).
- Meteodyne gets `SeasonProvider { double yearFraction(Level); String describe(Level); }`. `SeasonSource` consults, in order: the debug override, a registered provider (core registers Serene Seasons' when that mod is loaded), the built-in calendar.
- Built-in calendar: `yearFraction = (level.getDayTime() mod yearTicks) / yearTicks`, with `yearTicks` from a config value defaulting to `DEFAULT_YEAR_TICKS = 12 x 6 x 24000` (72 days, 12 sub-seasons of 6 days, the same shape as Serene's default). Config switch `useBuiltinCalendar` (default true); when false, behaviour is exactly today's no-seasons NaN. No sub-season names, no commands of its own beyond `describe`.
- Tests that assumed "no Serene Seasons = NaN" are updated to set the switch off.

## 6. What the library holds
Rule: **building blocks and interfaces only; no content, no gameplay registered in the world.** About 23 main files, ~2.5k lines, plus 8 unit-test files:
- Terrain: `TerrainProbe`, `NoiseTerrainProbe`.
- Wind: shelter, outline and force code from `weather/wind` (14 files) except `WindSources`, which becomes Meteodyne's `WindProvider` implementation; the Sable parts (`WindShips`, `ShipWind`, `WindForce`, `Silhouette`, `SubLevelSilhouetteMixin`, `ShelterProbe`, `WindCommand`, `WindContent`, `WindPhysics`) in a Sable-gated sub-package.
- The air/heat solver `weather/heat/air` (`AirGrid`, `AirParams`, `HeatClusters`, `Radiant`): it imports nothing of ours today. `HeatWorld` (the world binding, 1,298 lines) stays in Meteodyne.
- `ShipCover`, `ShipRules` (Sable-gated).
- The three provider interfaces (S1-S3) and a tiny `Services` registry (register at mod setup, look up by key, safe default when none).
- Shared test fixtures (`GameTestStructures`, the empty test structure).

## 7. Rules that keep dependencies optional
1. **Compat packages:** code touching an optional outside mod lives in `<mod>.compat.<othermod>` (or a `sable` sub-package) and is reached only after `ModList.isLoaded`; never in a signature or field of an always-loaded class. (Create is a hard dependency of Seismic and the core, so it needs no gating there.)
2. **Mixins:** today `mixins.json` has `required: true` and `defaultRequire: 1` and lists the MineColonies, Cold Sweat and Sable mixins unconditionally, so the pack cannot load without those mods. One mixin config per mod that has mixins (lib 1, Sable Fixes 2, Sparse Ores 1, Frontier 2, Meteodyne ~35, Spoilage 12, core 13; Seismic none), each with an `IMixinConfigPlugin` that applies a mixin only when its target mod is present. A config's `package` is a single prefix, so mixins can only be listed in the config of the package they live in; this is why the configs are split in step 1b, not later.
3. **Architecture test** (a unit test over source text, added before any Gradle slicing): allowed edges are exactly those in section 2; outside `compat`/gated packages no class mentions an optional outside mod's package.
4. **Bare runs:** a game test run per mod with only its required mods. Without it the optional graph rots silently.
5. **Mod metadata:** `type="optional"` in each `neoforge.mods.toml`, with `ordering="AFTER"` where one mod reads the other's registry (Sparse Ores and Seismic after Create). Licence field: `All Rights Reserved`.

## 8. Data, resources and names
- **Ids:** every block, item, payload, saved data, attachment, data map and tag moves to its owning mod's namespace (e.g. `seismic_surveyance:brass_geophone`). Network payload versions are per mod. No aliasing.
- **Resources (663 files under `data/` and `assets/`):** ownership follows the code that registers or reads the id. Settled by grep: `strikable` and the `combustible` fluid tag -> Seismic; `burns_out`, `settles`, `daytime_hostile`, `inhabitants`, `never_provoked`, `provoked_from_below`, `stationary`, `loud_machines`, deep rosters, `squelching_heart` -> Frontier; `non_perishable`, `freshness_sources`, `always_rotting` -> Simple Spoilage; the hail damage-type tags, `sable:heavy`/`super_light`, `heat_power`, `insulation`, shaders -> Meteodyne; `diet/groups`, `weight/*`, `furnace_fuels`, Create recipes, iron golem loot, quartz recipes -> core; rich ore blocks' mineable/needs-tool tags, `c:ores`, worldgen -> Sparse Ores.
- **Data maps and tags merge across packs**, so Meteodyne's `heat_power` keeps the vanilla sources and core adds the modded machines; tags use `"required": false` entries for other mods' ids.
- **Lang:** `en_us.json` (189 keys) splits by key stem (`logbook`, `smart_logger`, `portable_record_display`, `combustive_thumper`, `windup_reader`... -> Seismic; `ore`, `rich_*` -> Sparse Ores; `ice_chest`, `food` -> Spoilage; `incursion`, `tier_map`, `squelching_heart`, `dry_torch` -> Frontier; `weight`, chips -> core; `wind`, `hail` -> Meteodyne). Stale template keys (`configuration.magicNumber`, `logDirtBlock`, `items`) are deleted.
- **Configs:** each mod owns its specs (`FrontierConfig`, `SpoilageConfig`, `WindConfig`, `WeatherConfig`, `CloudConfig`). Each mod keeps today's values as its defaults; the pack's deviations go in `defaultconfigs/`.
- **Commands (Bright, Q4):** `/eae` today (10 command classes). Each mod owns its root (`/meteodyne`, `/frontier`, `/seismic`, `/sparseores`, `/spoilage`; the wind command goes with the lib or Meteodyne's root, decided in 2c), and the core keeps `/eae <feature>` as Brigadier redirects so existing muscle memory and scripts work.
- **Datagen:** one run per mod (`--mod <id>`), output into that mod's `src/generated/resources`.
- **Enum extension:** only the PRD arm pose (`enumextensions.json`) -> Seismic. No access transformers.

## 9. Build system
- Today: one Gradle project (ModDevGradle 2.0.147, Gradle 9.2.1, Java 21), one `mods` entry, a `neoforge.mods.toml` template, `colonyRuntime` and `shaderRuntime` configurations synced into `run/mods`.
- **Target:** multi-project build (`paradyne-lib/`, `sable-fixes/`, `sparse-ores/`, `seismic-surveyance/`, `meteodyne/`, `frontier/`, `simple-spoilage/`, `core/`), shared settings in a convention plugin, one dev aggregator whose client, server and game test runs load all of them, and a `buildAll` task collecting every jar into one folder for the Modrinth profile (the profile itself is never touched).
- **Risk R1, prototype first:** ModDevGradle's documentation covers several mods through the `mods { }` block and `loadedMods`, and isolated source sets through `addModdingDependenciesTo`; it does not document loading mods from several subprojects in one run. Step 2a is a throwaway lib + one dependent module proving client, server and game test runs. **Fallback:** one Gradle project with one source set per mod (documented), each source set's classpath holding only its allowed dependencies, so the compiler enforces the graph.
- Libraries ship as separate jars, not jar-in-jar.
- **Cloud build (verified 2026-10-16 in a cloud sandbox, 2 cores, 7 GB):** the sandbox reaches every build host (NeoForge, Create, Sable, Modrinth, CurseForge maven, Mojang, Gradle). The only blocker was Serene Seasons, read from the git-ignored `libs/` folder. Replacing `compileOnly files("libs/SereneSeasons-...jar")` with `compileOnly("maven.modrinth:serene-seasons:pHEgQQUE") { transitive = false }` made `./gradlew compileJava compileTestJava test runGameTestServer` pass: **964 unit tests (2 skipped), 202/202 game tests.** Times: cold compile ~9 min, warm ~5 min, game tests ~3 min. GlitchCore (`maven.modrinth:glitchcore:S2TfWrZR`) is needed only for the client run; add it with the client mods.
- Versions: new mods 0.1.0, core 0.4.0; licence All Rights Reserved everywhere.

## 10. Tests
- Unit tests (124 files, 964 tests): lib 8 files, Sparse Ores 19, Seismic 25, Frontier 11, Meteodyne 53, Spoilage 6, core 2; they move with their classes. `MixinConfigTest` becomes one per mod.
- Game tests (31 classes, 202 tests): follow their feature. Four need two mods and become **core integration tests**: `WeatherChecksGameTests` (MineColonies), `HeatPlayerGameTests` (Cold Sweat), `FrontierIncursionGameTests` (Create), `FrontierDeepGameTests` (Seismic). `FoodGameTests` has an AppleSkin part to split off.
- **Baseline to protect:** all unit tests green and 202/202 game tests, reproduced after every step (the total may be redistributed across mods, but the sum must hold; new architecture and mixin-config tests add to the unit total, never replace).
- Local-only checks (the cloud cannot run a client): clouds and shaders, the PRD model and map GUIs, ten-year climatology runs (`-Peae.*` properties, renamed `meteodyne.*`), the Modrinth pack smoke test.

## 11. Order of work (every step ends green; one PR per step into `split`)
0. **Prep (small):** tag `pre-split`, branch `split`; `docs/split/` (this plan, manifest, seams, steps) and a repo `CLAUDE.md`; apply the Serene Seasons fix; delete the empty stray `CloudConfig.java` at the repo root and the stale template lang keys; record the baseline.
1. **Package-first, still one mod and one jar:** (1a) per-feature entry points, `MODID`/`LOGGER`, `id()` helpers; (1b) script the package moves to the final package names from the manifest, split the mixin config per mod; (1c-A) seams S10-S14, S4, S9; (1c-B) seams S1-S3 and S5-S8; (1d) the architecture test. Verified entirely by the existing tests.
2. **Slice the build:** 2a prototype (R1); 2b lib + Sable Fixes; 2c Meteodyne; 2d Simple Spoilage + Frontier; 2e Sparse Ores + Seismic; 2f core (glue, refining, weight, compat, defaultconfigs, 0.4.0). Each sub-step also moves resources, lang, datagen and ids.
3. **Verify:** bare runs per mod, `buildAll`, local smoke test with the real pack.
4. **Tidy:** old-doc path map (the existing `claude/*.md` docs name the old paths), delete leftovers.
Cloud session sizing: 0, 1a, 1d, 2b, 2f one session each; 1b, 1c-A, 1c-B, 2c, 2d, 2e one session each but may spill into a second (the prompt tells the session to stop at a green commit and report what is left).

## 12. Unknowns to check
R1 above; Mixin ordering when two mods inject the same `LevelChunk` method (S4); that NeoForge data maps from two packs merge as assumed (they stack all packs' files); that several mixin configs listed in one `neoforge.mods.toml` (step 1b) behave as expected; `pack/config` still holds configs for removed mods (Project Atmosphere, Simple Clouds) and is left alone.

## 13. Questions resolved (Bright, 2026-10-16)
- **Q1** ids/packages: snake_case ids, concatenated packages, core keeps its package. **Yes.**
- **Q2** Seismic and Create: **Create is a hard requirement** (the mechanical thumper is essential); Create Diesel Generators soft. The proposed Create gating is dropped, which removes about one sub-step of work.
- **Q3** `IrisCompat` stays in Meteodyne; machine heat drivers and `LoudMachines` weighting go to core. **Yes.**
- **Q4** per-mod command roots with core `/eae` redirects. **Yes.**
- **Q5** built-in calendar, **very simple** (section 5, S11).
- **Q6** Frontier ships vanilla-only rosters and tags; core's datapack adds the pack's mobs. **Yes.**
- **Q7** defaults stay at today's values; `defaultconfigs/` carries deviations; new mods 0.1.0, core 0.4.0; all licences ARR.
- **Q8** one PR per step into `split`; step-0 deletions; leave `pack/config` alone. **Yes.**
- **Q9** package-first order. **Yes.**

Open logistics item: how `docs/split/` and `CLAUDE.md` reach the repo before step 0 (section 3 of `claude/mod-split-steps.md`).
