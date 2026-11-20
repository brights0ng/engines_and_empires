<!-- Written by Gemini; updated by ChatGPT with airflow result links; updated by Claude: the local-air docs moved to history/local-air (GreedyFluids M7f f3). -->
# Engines and Empires Documentation Index

Start with [`PROJECT.md`](PROJECT.md) for shared project rules, mod family architecture, import rules, protected paths, and build/test commands.

## Directory Structure

- **[`PROJECT.md`](PROJECT.md)** — Shared project instructions, dependency graph, and workflow conventions.
- **[`design/`](design/)** — Read-only reference notes from the pre-split project describing core feature behavior (`frontier.md`, `food-spoilage.md`, `food-temperature.md`, `ore-refining.md`, `prd.md`, `smart-logger.md`, `weather-backbone-phase8f.md`, `wind.md`). Do not edit these files; see [`design/README.md`](design/README.md).
- **[`empire/`](empire/)** — Design and architecture specifications for the upcoming Empires settlement and civilian system:
  - [`empire-plan-brainstorming.md`](empire/empire-plan-brainstorming.md) — Conceptual design, founding/dominion rules, civilian ability scores, fulfilment, and 4-layer utility AI pipeline.
- **[`meteodyne/`](meteodyne/)** — Feature documentation for the Meteodyne weather and temperature mod:
  - [`weather-instruments.md`](meteodyne/weather-instruments.md) — Thermometer, wind gauge, mounted sensors, proportional mount textures, and shelter-aware sampling.
- **[`airflow/`](airflow/)** — The legacy heat regions' (`[heat] engine = LEGACY`) benchmark harness and response control. The default engine, GreedyFluids airflow, is documented in the GreedyFluids repository (`docs/design/3d.md`).
  - [`thermal-performance.md`](airflow/thermal-performance.md) & [`thermal-baseline-results.md`](airflow/thermal-baseline-results.md) — Pre-airflow thermal benchmark harness, live capture telemetry, and baseline numbers.
  - [`thermal-response-control.md`](airflow/thermal-response-control.md) — Thermal response speed control.
- **[`benchmarks/`](benchmarks/)** — The archived pre-airflow thermal baseline referenced by [`airflow/`](airflow/).
- **[`verification/`](verification/)** — Testing checklists and known operational issues:
  - [`local-verification.md`](verification/local-verification.md) — Manual in-game verification checklist for client rendering, shaders, GUIs, and standalone mod runs.
  - [`known-issues.md`](verification/known-issues.md) — Tracked known issues and workarounds.
- **[`history/`](history/)** — Records of finished or retired work:
  - [`mod-split-plan.md`](history/mod-split-plan.md) — The v3.1 split plan and seam records.
  - [`build-layout.md`](history/build-layout.md) — Multi-project Gradle build layout, `build-logic` convention plugins, and the `coreless` test runner.
  - [`standalone-audit.md`](history/standalone-audit.md) — Standalone-completeness audit of the seven feature mods and combustive thumper fuel data map guide.
  - [`path-map.md`](history/path-map.md) — Mapping from legacy `claude/*.md` Project paths to repository paths.
  - [`local-air/`](history/local-air/) — Retired: the local-air domains and Paradyne Lib's coupled thermal-flow solver, deleted in GreedyFluids M7f f3 when airflow became the default engine. Their plan ([`local-air-model-plan.md`](history/local-air/local-air-model-plan.md)), stage results and benchmarks are kept as a record; see [`local-air/README.md`](history/local-air/README.md).
- **[`split/`](split/)** — Holds [`mod-split-manifest.csv`](split/mod-split-manifest.csv), which maps pre-split Java files to their target mods and is read directly by `ArchitectureTest`.
