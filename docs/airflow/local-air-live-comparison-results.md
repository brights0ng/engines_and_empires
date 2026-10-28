<!-- Written by ChatGPT. -->
# First player-captured legacy / coupled overhead comparison

Bright supplied one capture of each on the evening of 2026-10-26 (filenames use UTC, 2026-10-27). Raw unmodified reports are archived under [`../benchmarks/air-live-comparison-2026-10-26`](../benchmarks/air-live-comparison-2026-10-26/): `legacy` is `live-20261027-000159-536`, `with-air` is `live-20261027-000459-435`. Each contains the original summary JSON/CSV and legacy jobs CSV. No code changes were made for this analysis.

Both captures completed 60 measured game seconds in approximately 60 wall seconds after ten seconds of warmup, with response scale 1, one worker and the configured 0.25-core budget. Both used the exact position (58.87037565654044, -59, -62.80135550605406), Java 21.0.12.1+1-LTS, and a runtime reporting six processors. The legacy region remained the same 27x16x27 box (11,664 cells), with nine natural sources, 60 measured jobs, no rebuild/settling jobs, and no recorded invalidations. The second capture additionally ran LIVE_CENTER airflow: 4,046 captured domain cells, 2,880 air nodes, 8,951 faces. These are different workloads, not interchangeable solver implementations on equal geometry.

## Measured window

| Metric | Legacy only | Legacy plus coupled air |
| --- | ---: | ---: |
| Heat pipeline game-thread mean, ms/tick | 0.02318 | 0.02640 |
| Heat pipeline game-thread p95, ms/tick | 0.1308 | 0.0803 |
| Heat pipeline game-thread maximum, ms/tick | 6.5308 | 0.4558 |
| Legacy worker wall, ms per real second | 0.8516 | 0.4581 |
| Added coupled worker wall, ms per real second | — | 13.3008 |
| Combined measured worker wall, ms per real second | 0.8516 | 13.7589 |
| Coupled worker CPU, ms per real second | — | 11.4583 |
| Coupled step jobs | — | 120 |
| Coupled job mean / p95 / max, ms wall | — | 6.6504 / 7.3453 / 11.3151 |
| Coupled executor queue mean / max, ms | — | 0.0471 / 1.2518 |

Rates divide measured metric totals by each report's actual elapsed wall seconds. Coupled worker CPU totals 687.5 ms over 60 seconds, about **1.15% of one CPU core**. It is separate from the wall-time estimate. The reports' top-level `workerCpuCoreFraction=0` covers legacy work only and is not the coupled CPU percentage. Legacy CPU samples all read zero, reflecting the coarse Windows counter; they do not establish zero CPU cost. Do not compute a coupled/legacy CPU ratio from them.

The mean heat-pipeline game-thread difference is +0.00323 ms/tick. Main-thread nested metrics are not added together. `local_air_worker_ms` and `local_air_step_wall_ms` describe the same 120 jobs here and must not be double-counted. `snapshot_prepare_ms` and local advance counts are calls, not necessarily ticks, because the shared scheduler can reclaim unused allowance. The cleaner second run and the first run's 19.7 ms worker outlier / 6.53 ms heat-tick outlier preclude interpreting lower legacy timings as an airflow speedup. Legacy solve mean was similar (0.4153 versus 0.4022 ms/job).

## Useful progress and limitations

The coupled run ended PREPARED / READY with a published field eight game ticks old (0.4 seconds at 20 TPS). Request-lifetime statistics show 295 accepted physical steps, zero failed/discarded STEP jobs, no geometry transfers and zero cold restarts or invalidation events. Maximum recorded divergence was 9.65e-8, below the configured 1e-7 limit; maximum thermal residual was 4.72e-11 C. Cumulative energy-balance error was -3.79e-6 in the solver's energy units. These lifetime counts/quality values are not all restricted to the measured 60-second window. End-of-capture freshness does not establish availability on every measured tick.

Startup statistics (137,681 charged reads and 48.55 ms active snapshot time; PLAN/BAKE/INITIALIZE timings) predate the measured steady window. The capture has no request-to-first-field latency measurement. Weather differed between runs and was not fixed; this is one pair, not repeated controlled evidence. Visualization state is not recorded. The heat pipeline capture does not measure client renderer time, view sampling/network cost in the separate view tick handler, whole-server MSPT, or FPS.

For this small steady scene the added worker cost is modest in absolute terms and the field is making useful progress. The coupled work is substantially more expensive than the legacy thermal calculation, despite its smaller domain, but does more physics and runs about twice as often. This pair cannot establish replacement-system speed, multiplayer capacity, linear scaling, or the plan's much larger reference-scene target. Repeat the pair for variability, then measure controlled source/door changes and larger domains separately; test startup and dense-view client performance in their own captures.
