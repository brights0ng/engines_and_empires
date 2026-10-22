<!-- Written by ChatGPT. -->
# Thermal response speed

Follow-up: the [coupled response and elapsed-interval report](local-air-response-results.md) extends this historical Stage 1 evidence with chronological rate snapshots and numerical coupling checks. The live thermal-only control described here retains its existing behavior.

Implemented 2026-10-19 as the response-scale portion of stage 1 in [the local air model plan](local-air-model-plan.md). The geometry/cache comparison is documented separately in [incremental geometry preparation](local-air-incremental-results.md). No airflow kernel has been added.

## Use

The weather server config now accepts `heat.responseScale`, default `1.0`, supported range `0.1` through `10.0`. Existing keys and command names are retained. The existing eight-argument `HeatSettings` constructor retains a 1x default for callers.

| Command (operator permission 2) | Effect |
| --- | --- |
| `/meteodyne heat response` | Show effective response, config value and whether a temporary override is active |
| `/meteodyne heat response 0.5` | Temporarily slow thermal response; roughly twice the warming/cooling time |
| `/meteodyne heat response 2` | Temporarily speed thermal response; roughly half the warming/cooling time |
| `/meteodyne heat response reset` | Remove the override and use the current config value |

The override lasts for the world session and does not write the config or save temperatures differently. A config reload takes effect on subsequent ticks unless an override is active; reset immediately selects the reloaded value. Zero, NaN, infinity and out-of-range values are rejected. Changing the setting preserves current thermal state and geometry. Already-dispatched jobs retain their prepared inputs.

The existing `/meteodyne heat step <seconds>` debug command treats its argument as game seconds and reports the response multiplier. It intentionally runs synchronously outside the worker budget, as before. A large argument at a high response scale can take substantial time; use normal live evolution for gameplay comparisons.

## Time and behavior contract

`ThermalClock` in Paradyne Lib keeps elapsed game seconds separate from thermal evolution seconds. Rate transitions are recorded at game-tick boundaries. An interval spanning a response change integrates each segment at its original rate: five game seconds at 1x followed by five at 2x gives ten game seconds and fifteen thermal seconds. Changing the rate cannot retroactively accelerate time a distant region already waited. The timeline retains the current 120-game-second catch-up window, coalesces transitions within one tick, and prunes older history; storage is bounded to at most 2,401 transitions.

The thermal grid advances by the resulting thermal interval. Source heating/cooling, material conduction and storage response, exchange, mixing, ventilation and residual losses therefore use one consistent time scale. Source power and thermostat definitions, published game timestamps, weather evolution, wind m/s, update cadence, instantaneous radiation, fuel consumption, redstone and damage timers keep their existing meanings. Larger scales can require more thermal substeps and CPU; worker budgeting does not secretly reduce the selected scale.

For fixed inputs, this changes response timing while retaining the equilibrium equations and limits. A 2x response is not a promise of exactly half the observed warm-up time if source/fuel states, weather, numerical step sizes or later buoyancy feedback differ. At non-unity scales, thermal transport deliberately need not move at the displayed wind speed. The fixed-wind tests below isolate thermal time from those changing inputs. No claim is made about an unimplemented coupled flow solver.

Natural-source initialization remains 1,500 **thermal** settling seconds, independent of response scale, to preserve the accepted already-hot cave/arrival estimate. It is not elapsed time or a new airflow warm-up. Subsequent evolution uses the selected response rate. Loaded/saved warmth is not reset when the control changes.

## Capture fields

The v3 capture adds `gameSecondsAcrossJobs`, `responseScaleAtEnd` and `responseScaleMode`. Existing `simulatedSecondsAcrossJobs` continues to represent actual thermal evolution seconds and excludes initialization. The CSV appends `game_seconds` and `response_scale_at_dispatch`; it retains existing columns. Game-time totals represent processed time after the existing catch-up cap. A job spanning a rate transition can have a thermal/game interval ratio different from the selected dispatch-time rate. Config settings describe the persisted/configured value; the response fields expose a temporary override. `thermalTimeScope` explains the separate time units in each report.

## Verification

Six pure Java tests check separate game/thermal intervals, future-only rate changes, same-tick override/reset, rate history across the catch-up cap, long changing histories, invalid values/times, and complete warming/cooling with wall storage and prescribed wind at 0.1/0.5/1/2/10x. The prescribed-wind comparisons use identical thermal substeps and compare every published temperature value exactly.

Two new integration tests exercise real operator commands/permissions, invalid input, live config-value changes and override precedence/reset, field retention, unchanged wind/source limits/radiation/cadence, capture time units, and identical natural arrival temperatures at 1x and 10x. They pass as part of the 89-test standalone Meteodyne run. This verifies live config-value consumption; the file watcher itself was not manually exercised in a client.

Evidence: `../../build/thermal-response-initial.log` (timing tests and compile), `build/thermal-response-integration.log` (standalone tests and Meteodyne unit suite), and `build/thermal-response-full-check.log` (required full invocation with `--continue`). In-game visual/timing feel still requires Bright's client comparison at 0.5/1/2x in the same campfire room, using reset afterward to return to config.

Full results: compilation succeeded; 1,098 unit tests completed without failures (two existing benchmark skips); coreless passed 205/205; every standalone run passed (Meteodyne 89, Frontier 18, lib 3, Sable Fixes 2, Seismic 20, Spoilage 34, Sparse Ores 13 bare and 13 with Create). Aggregate passed 255/256. Its only failure is the existing `theGameTestServerHasNoSeasons` assertion because the root launch includes Serene Seasons. That local launch setup and assertion remain unchanged. `git diff --check` passed.
