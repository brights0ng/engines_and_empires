<!-- Written by Gemini; updated by ChatGPT with airflow result links. -->
# Engines and Empires Documentation Index

Start with [`PROJECT.md`](PROJECT.md) for shared project rules, mod family architecture, import rules, protected paths, and build/test commands.

## Directory Structure

- **[`PROJECT.md`](PROJECT.md)** — Shared project instructions, dependency graph, and workflow conventions.
- **[`design/`](design/)** — Read-only reference notes from the pre-split project describing core feature behavior (`frontier.md`, `food-spoilage.md`, `food-temperature.md`, `ore-refining.md`, `prd.md`, `smart-logger.md`, `weather-backbone-phase8f.md`, `wind.md`). Do not edit these files; see [`design/README.md`](design/README.md).
- **[`empire/`](empire/)** — Design and architecture specifications for the upcoming Empires settlement and civilian system:
  - [`empire-plan-brainstorming.md`](empire/empire-plan-brainstorming.md) — Conceptual design, founding/dominion rules, civilian ability scores, fulfilment, and 4-layer utility AI pipeline.
- **[`meteodyne/`](meteodyne/)** — Feature documentation for the Meteodyne weather and temperature mod:
  - [`weather-instruments.md`](meteodyne/weather-instruments.md) — Thermometer, wind gauge, mounted sensors, proportional mount textures, and shelter-aware sampling.
- **[`airflow/`](airflow/)** — Plans, numerical designs, and stage results for the local air and coupled thermal-flow solver in Paradyne Lib and Meteodyne:
  - [`local-air-model-plan.md`](airflow/local-air-model-plan.md) — Full local air model implementation plan (supersedes [`airflow-prototype.md`](airflow/airflow-prototype.md)).
  - [`thermal-performance.md`](airflow/thermal-performance.md) & [`thermal-baseline-results.md`](airflow/thermal-baseline-results.md) — Pre-airflow thermal benchmark harness, live capture telemetry, and baseline numbers.
  - [`thermal-response-control.md`](airflow/thermal-response-control.md), [`local-air-step1-results.md`](airflow/local-air-step1-results.md), & [`local-air-incremental-results.md`](airflow/local-air-incremental-results.md) — Stage 1 thermal response speed control, geometry invalidation, and incremental snapshot preparation.
  - [`local-air-kernel-results.md`](airflow/local-air-kernel-results.md) & [`local-air-pressure-results.md`](airflow/local-air-pressure-results.md) — Stage 2 pure airflow kernel and modified incomplete-Cholesky pressure optimization.
  - [`local-air-thermal-transport-results.md`](airflow/local-air-thermal-transport-results.md) & [`local-air-coupled-results.md`](airflow/local-air-coupled-results.md) — Stage 3 shared-flux thermal foundation and two-way heat/air coupling checkpoints.
  - [`local-air-thermal-optimization-results.md`](airflow/local-air-thermal-optimization-results.md) - Compact thermal rows, reduced convergence-check work, directed traversal, and repeated coupled performance comparisons.
  - [`local-air-arithmetic-results.md`](airflow/local-air-arithmetic-results.md) - Cached thermal arithmetic and velocity origins, repeated comparisons, and remaining solver hotspots.
  - [`local-air-tracing-results.md`](airflow/local-air-tracing-results.md) - Faster wall-safe tracing, pressure-kernel experiment, and the selected default's numerical/performance evidence.
  - [`local-air-calibration-results.md`](airflow/local-air-calibration-results.md) - Settled thermal means, each model's own warm-up time, material storage, cooling tails, and observed local oscillations.
  - [`local-air-qualification-results.md`](airflow/local-air-qualification-results.md) - Physical timestep refinement, exterior padding and ground-depth sensitivity, and remaining boundary limitations.
  - [`local-air-response-results.md`](airflow/local-air-response-results.md) - Coupled response-scale comparisons, chronological rate snapshots, and bounded long-interval numerical controls.
  - [`local-air-refined-calibration-results.md`](airflow/local-air-refined-calibration-results.md) - Half-second settled warmth, own-target heating/cooling, and strict field-steadiness comparison with legacy and the earlier coarse run.
  - [`local-air-boundary-results.md`](airflow/local-air-boundary-results.md) - Static-fluid heat storage/exchange, explicit admission checks, preserved dry results, and the exterior-boundary handoff.
  - [`local-air-world-capture-results.md`](airflow/local-air-world-capture-results.md) - Sliced world geometry/material snapshots, revision rejection, missing-chunk handling, and remaining live-admission limits.
  - [`local-air-coverage-results.md`](airflow/local-air-coverage-results.md) - Conservative open-sky evidence, bounded coverage scans, and revision-safe geometry worker handoff components.
  - [`local-air-pipeline-results.md`](airflow/local-air-pipeline-results.md) - Opt-in preparation through the shared heat workers/budgets, world invalidation, and request lifecycle controls.
  - [`local-air-runtime-results.md`](airflow/local-air-runtime-results.md) - Explicit cold-start coupled diagnostics, static source capture, bounded chronological stepping, and immutable field publication.
  - [`local-air-diagnostics-results.md`](airflow/local-air-diagnostics-results.md) - Operator controls/sampling, field quality and age, scheduler timing, actual server smoke captures, and full-check results.
  - [`local-air-scene-results.md`](airflow/local-air-scene-results.md) - Aggregate test isolation, paced larger-room door/source/wind trials, and the retained-state continuity gap.
  - [`local-air-source-refresh-results.md`](airflow/local-air-source-refresh-results.md) - Chronological campfire source refresh, retained warmth/flow/energy, cooling verification, and isolated paced trials.
  - [`local-air-geometry-transfer-results.md`](airflow/local-air-geometry-transfer-results.md) - Edit-time barriers, retained door geometry state, fresh projection, energy exchange, and opening/closing trial evidence.
  - [`local-air-dependency-results.md`](airflow/local-air-dependency-results.md) - Request-local block/chunk invalidation, recorded shape/sky dependencies, and retained fields across unrelated edits.
  - [`local-air-history-results.md`](airflow/local-air-history-results.md) - Bounded intermediate topologies, historical world reads, and chronological door/source edits during reconstruction.
  - [`local-air-recapture-results.md`](airflow/local-air-recapture-results.md) - Dependency-aware snapshot reuse and paired paced door reconstruction measurements.
  - [`local-air-weather-results.md`](airflow/local-air-weather-results.md) - Opt-in live climate/wind snapshots and chronological retained-state weather updates.
  - [`local-air-weather-scene-results.md`](airflow/local-air-weather-scene-results.md) - Paced open-building weather trial, repaired recipe baseline, and measured coverage/publication latency.
  - [`local-air-coverage-reuse-results.md`](airflow/local-air-coverage-reuse-results.md) - Guarded sky-column proof reuse and reduced paced open-building door latency.
- **[`benchmarks/`](benchmarks/)** — Archived multi-fork JSON benchmark baselines and summaries referenced by [`airflow/`](airflow/).
- **[`verification/`](verification/)** — Testing checklists and known operational issues:
  - [`local-verification.md`](verification/local-verification.md) — Manual in-game verification checklist for client rendering, shaders, GUIs, and standalone mod runs.
  - [`known-issues.md`](verification/known-issues.md) — Tracked known issues and workarounds.
- **[`history/`](history/)** — Architecture and audit records from splitting the core mod into the 8-mod family:
  - [`mod-split-plan.md`](history/mod-split-plan.md) — The v3.1 split plan and seam records.
  - [`build-layout.md`](history/build-layout.md) — Multi-project Gradle build layout, `build-logic` convention plugins, and the `coreless` test runner.
  - [`standalone-audit.md`](history/standalone-audit.md) — Standalone-completeness audit of the seven feature mods and combustive thumper fuel data map guide.
  - [`path-map.md`](history/path-map.md) — Mapping from legacy `claude/*.md` Project paths to repository paths.
- **[`split/`](split/)** — Holds [`mod-split-manifest.csv`](split/mod-split-manifest.csv), which maps pre-split Java files to their target mods and is read directly by `ArchitectureTest`.
