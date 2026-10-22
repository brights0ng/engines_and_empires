<!-- Written by ChatGPT. -->
# Local air model: geometry invalidation comparison

2026-10-19. First implementation slice of stage 1 in [the local air model plan](local-air-model-plan.md). The response-scale control and airflow kernel remain subsequent work.

## Change and comparison

Previously, every block-state update inside a thermal region dirtied its whole cell/shelter snapshot. The handler received the previous state but did not use it. The original live capture recorded 93 geometry-rebuild jobs out of 124 completed jobs; it did not identify the invalidating blocks. Those numbers remain the reference-world baseline, not a measured result for this change.

The handler now compares collision shapes, fluid state, legacy cell kind/open faces, the motion-blocking heightmap predicate, and material coefficients. Source indexing still updates on every notification, and prepared source inputs are still recalculated each plan. Source-only changes reuse the cell snapshot; material-only changes refresh cells/coefficients while reusing shelter. Dynamic shapes and deferred off-thread notifications conservatively rebuild. Physical invalidation now covers shape neighbors, the sampled shelter margin, heightmap columns, and chunk changes in that margin. Data-table reloads and shelter-reach changes also invalidate prepared snapshots.

The new `HeatInvalidationGameTests` fixture warms a campfire region, turns its only source off and back on, then replaces a stone block with oak planks. It compares one-second cooling and material-update results against forced full snapshots from identical initial temperatures, sources, wind, and world geometry. Both trials explicitly load the same float offsets before stepping, since worker state normally retains doubles while publications store floats.

| Captured operation | Complete cell/shelter snapshots | Material-only snapshots |
| --- | ---: | ---: |
| Source off | 0 | 0 |
| Forced full-snapshot cooling control | 1 | 0 |
| Source on | 0 | 0 |
| Stone to oak | 0 | 1 |
| Forced full-snapshot material control | 1 | 0 |

All published temperature values matched exactly for each comparison pair. The five-job capture contained two full snapshots (the deliberately forced controls), one material-only refresh, two prepared source changes, and one material invalidation. Without reuse, the two source toggles and material replacement each took the full snapshot path. This establishes avoided work and thermal equivalence in a deterministic fixture; it is not a live-world CPU speedup measurement.

Additional checks exercise closed/open doors and trapdoors, changing snow-layer geometry despite matching coarse face masks, waterlogging, unknown deferred prior state, actual block hooks outside the region and above its heightmap column, and table-cache reload invalidation. Existing room calibration, cool-down, persistence, and wind fixtures remain part of the standalone suite.

## Telemetry and repeat capture

The live schema is now `thermal-live-v2`. Capture-only invalidation causes and bounded before/after block-state counts explain rebuild triggers; per-job region ids and separate geometry/material/source revisions identify dispatched inputs. Material snapshot time is separate from full geometry and cover time. The archived v1 baseline and offline thermal control are retained. See [thermal-performance.md](thermal-performance.md#invalidation-diagnostics-local-air-step-1) for field meanings and measurement boundaries.

After restarting the development client with these classes, repeat `/meteodyne heat benchmark start 60` in the original reference scene. Keep weather, settings, sources, position and loaded chunks matched. Capture steady conditions first, then source toggles, then door/block changes as separate trials. Use the reported state pairs to identify remaining churn before further cache optimization. No reference-world capture or in-game visual verification has been performed for this change.

## Verification

- Standalone Meteodyne: 84/84 game tests passed; 394 unit tests completed with no failures and two existing benchmark skips.
- Full compilation and all 1,092 unit tests completed with no failures (two existing benchmark skips). Coreless game tests passed 200/200. The aggregate passed 250/251: its existing `theGameTestServerHasNoSeasons` assertion fails because the root launch loads Serene Seasons from local `../../libs` through `prepareColonyMods`. The log confirms that provider is present. The root launch's optional-mod configuration was left unchanged. Full-run evidence: `build/local-air-step1-full-check-final.log`.
- Every standalone run passed: lib 3/3, Frontier 18/18, Meteodyne 84/84, Sable Fixes 2/2, Seismic 20/20, Spoilage 34/34, and Sparse Ores 13/13 both bare and with Create. Evidence: `../../build/local-air-step1-standalone-check.log` (`BUILD SUCCESSFUL`). These ran separately because the aggregate failure prevents Gradle from reaching every selected run in the combined invocation.
- The first full attempt exposed Windows separator handling in existing mixin-discovery tests. Six affected copies now match Meteodyne's existing portable conversion; coverage assertions are unchanged.
- Targeted logs: `../../build/local-air-step1-targeted.log` (initial comparison-fixture precision failure), `build/local-air-step1-regression.log` (corrected exact comparison and standalone suite passed).

The current geometry remains the established thermal face-mask approximation. This change does not implement partial-air connectivity, pressure projection, buoyancy, or a new thermal transport law.
