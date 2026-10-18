# Old Project doc paths (split step 4)

Code comments and docs in this repo still name design notes by their path in the "Create: Engines and Empires" Claude
Project, `claude/<name>.md`. This table says where each one is in the repo now, if it is here at all. Paths are left as
they are in the sources (they are history, and the Project still has the originals); use this table to find a note.

## Notes that are in the repo

| Old path (Project) | Now (repo) | Notes |
|---|---|---|
| `claude/mod-split-plan.md` | `docs/split/mod-split-plan.md` | The repo copy is the one sessions read. |
| `claude/mod-split-steps.md` | `docs/split/mod-split-steps.md` | |
| `claude/mod-split-manifest.csv` | `docs/split/mod-split-manifest.csv` | Read by `ArchitectureTest` (per-mod file counts). |
| `claude/mod-split-seams.csv` | `docs/split/mod-split-seams.csv` | |
| `claude/food-spoilage.md` | `docs/design/food-spoilage.md` | Named in `docs/design/food-temperature.md`. |
| `claude/food-temperature.md` | `docs/design/food-temperature.md` | Named in Simple Spoilage's `FoodClimate`, `FoodClimateSweep`, `FoodTemperature`, `FoodTemperatureGameTests`, Meteodyne's `MeteodyneTemperature`, and `docs/design/food-spoilage.md`. |
| `claude/weather-backbone-phase8f.md` | `docs/design/weather-backbone-phase8f.md` | Named in Meteodyne's `HeatRadiant`, `HeatWorld`, Sable Fixes' `GameEventSectionGuardMixin`, `PlotChunkAttachmentSaveMixin`, the core's `ColdSweatHearths`, and `docs/design/food-temperature.md`. |
| `claude/prd.md` | `docs/design/prd.md` | Named in `docs/design/smart-logger.md`. |
| `claude/frontier.md` (assumed) | `docs/design/frontier.md` | Not named anywhere in the repo; the Project name is assumed to be the same file name. |
| `claude/wind.md` (assumed) | `docs/design/wind.md` | As above. Not the same note as `claude/wind-physics-research.md`, which `wind.md` names. |
| `claude/smart-logger.md` (assumed) | `docs/design/smart-logger.md` | As above. |
| `claude/ore-refining.md` (assumed) | `docs/design/ore-refining.md` | As above. Not the same note as `claude/ore-processing-research.md`, which `ore-refining.md` names. |

## Notes that are not in the repo (Project only)

| Old path (Project) | Named in |
|---|---|
| `claude/weather-backbone-plan.md` | the root `build.gradle`; Meteodyne's `Baseline`, `Climate`, `Temperature`, `CloudType`, `CloudRenderer`, `CloudDiagnostics`, `CloudRain`, `CloudSim`, `GustFronts`, `SimCloud`, `WeatherMapScreen`, `AtmosphereField`, `Moisture`, `WeatherGameTests`, `WeatherCommandMixin`, `WeatherConfig`, `PressureField`, `SimParams`, `SystemsSim`, `Atmosphere`, `CloudWorld`, `WeatherSim` |
| `claude/weather-backbone-phase7.md` | Meteodyne's `Forecast`, `ForecastGrid`, `ForecastRun`, `ForecastScore`, `ForecastService`, `Drift` |
| `claude/weather-backbone-phase8.md` | Meteodyne's `Climatology`, `ClimatologyStats`; `docs/design/weather-backbone-phase8f.md` |
| `claude/weather-backbone-phase8-research.md` | `docs/design/weather-backbone-phase8f.md` |
| `claude/weather-backbone-phase8c.md` | Meteodyne's `Underground` |
| `claude/weather-backbone-phase8d.md` | the core's `ColdSweatCompat` |
| `claude/weather-backbone-phase8e.md` | Meteodyne's `Apparent`, `RainSoak`, `SunLight`; the core's `ColdSweatWater`; `docs/design/weather-backbone-phase8f.md` |
| `claude/frontier-plan.md` | `docs/design/frontier.md` |
| `claude/wind-physics-research.md` | `docs/design/wind.md` |
| `claude/ore-processing-research.md` | `docs/design/ore-refining.md` |
| `claude/diet-research.md` | `docs/design/food-spoilage.md` |
| `claude/perf-report-2026-10-05.md` | the core's `CloudWorksDebugDumpMixin` |

(`claude/foo.md` in `docs/design/README.md` is only an example of the path form.)

Class names in the "named in" columns are the files' current names (after the split); search for the class to find
its project and package. The game also copies one into the generated `run/config/meteodyne-weather-server.toml` (from
`WeatherConfig`'s comments); `run/` is not tracked.
