# Design docs (read-only reference)

Copies of eight design/implementation notes from the "Create: Engines and Empires" Claude Project, for background while the mod is split (see `docs/history/mod-split-plan.md`). They describe behaviour that must not change; the game tests pin most of it.

- Paths like `claude/foo.md` inside these files refer to the Project, not to this repo. Only the eight files here are copied; the rest are not available to cloud sessions.
- Class and package names are the pre-split ones (`dev.brights0ng.enginesandempires.*`). Use `docs/split/mod-split-manifest.csv` to find where a class goes.
- The `/eae weather`, `/eae heat`, `/eae clouds` and `/eae wind` commands these notes name were removed in the split; the commands now live under `/meteodyne` (see `docs/path-map.md`).
- Do not edit these files. If one is wrong, say so in the PR.

Files: `frontier.md`, `food-spoilage.md`, `food-temperature.md`, `wind.md`, `weather-backbone-phase8f.md`, `prd.md`, `smart-logger.md`, `ore-refining.md`.
