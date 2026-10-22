<!-- Written by ChatGPT. -->
# Coupled response scale and elapsed intervals, 2026-10-20

The numerical path can preserve response-rate history while advancing long elapsed intervals in bounded physical steps. `ThermalClock.segments(sinceTick, nowTick)` now returns an immutable chronological snapshot of the retained rates. Existing aggregate `interval()` behavior and live thermal-only consumers remain unchanged. No coupled runtime worker, source/weather-history adapter, cadence, or production timestep is introduced by this pass.

## Rate history and physical time

A coupled job must split at both the selected physical-step bound and rate transitions. For example, 3.65 seconds at 0.5x followed by a different response rate cannot be replaced by an average rate over the whole catch-up interval: the temperatures encountered by buoyancy would differ. A negative-control test confirms that equal total thermal time can produce a different coupled field when history is averaged away.

Snapshots use inclusive/exclusive game-tick bounds, retain the existing 120-second window when constructed with 2,400 ticks, preserve same-tick override semantics, and remain unchanged after the owning clock advances or prunes its history. A setting observed at the interval end affects future ticks only. Empty elapsed intervals return an empty snapshot. Invalid segment scales/time bounds are rejected. A snapshot contains at most the retained rate segments, not per-cell data. The clock remains single-owner; the returned list and records can be handed to an offline job.

The test-only resumable job uses at most seven numerical steps per call and at most ten game ticks (0.5 physical seconds) per step. It shortens a step to land on a rate transition when needed. With constant inputs, dispatch intervals of **1, 10, and 120 seconds** at **0.1/0.5/1/2/10x** exactly reproduce every temperature and face flux of an independently advanced 0.5-second reference. Off-step history changes also reproduce the chronological reference exactly across yields, including a transition at tick 73. Source energy and separate physical/thermal accounting are checked. A rejected first step retains its pending time, energy and field; direct unsplit 10/120-second steps remain invalid.

Separate controls keep supplied temperatures identical when comparing buoyancy, and use a uniform ambient field when comparing wind. At a given physical step, buoyancy flux is identical across scales; the uniform-wind comparison agrees within `1e-9`. Response speed changes subsequent temperatures and therefore later buoyancy, rather than multiplying measured wind or physical momentum time. The initial wind control explicitly starts at the prescribed wind; pressure-balancing a calm interior against windy exterior boundaries would introduce an unwanted transient into that control.

These are numerical orchestration checks using a small air/material fixture and fixed source/weather inputs. They do not implement or qualify live worker admission, wall-clock budgets, publication freshness, changing source/weather history, unload/reload, persistence, or natural-source initialization. Existing long thermal settling estimates must not become long physical airflow steps.

## House response protocol

The existing sealed, windowed, and missing-wall houses start cold at 20 C, with calm exterior wind and a 75-unit unlimited virtual-air source. Each case heats for 600 physical seconds and cools for 80 after source removal. The main comparison keeps physical steps at 0.5 s while changing response across 0.1/0.5/1/2/10x. A second batch repeats every case at 0.25 s. This uses the **original padding-6, ground-depth-3 calibration domain**, preserving the previous unity controls. It does not combine the separate padding-16/24 study with response refinement.

For an accepted full case, advanced thermal time is `680 * scale` seconds and delivered heating energy is `45,000 * scale`. Rates change the entire thermal equation, including transport/storage, while momentum advances on physical time. Comparing temperatures at 600 physical seconds does not compare equilibrium: the slower cases have had less thermal evolution, and faster thermal feedback can change transient flow. Do not promise an exact inverse scaling of coupled warm-up/cool-down or reinterpret these snapshots as own-target t95 values.

At the proposed 0.5 s physical step, the principal gameplay comparison is:

| House | Response | Room rise at 60 s | Room rise at 600 s | Own source-off warmth remaining after 40 s cooling |
| --- | ---: | ---: | ---: | ---: |
| Sealed | 0.5x | 5.512 C | 11.786 C | 62.76% |
| Sealed | 1x | 7.392 C | 12.527 C | 50.35% |
| Sealed | 2x | 9.508 C | 12.727 C | 35.37% |
| Window | 0.5x | 5.434 C | 11.355 C | 61.68% |
| Window | 1x | 7.242 C | 12.018 C | 49.10% |
| Window | 2x | 9.251 C | 12.196 C | 34.16% |
| Missing wall | 0.5x | 4.673 C | 7.548 C | 49.71% |
| Missing wall | 1x | 5.806 C | 7.726 C | 36.79% |
| Missing wall | 2x | 6.863 C | 7.774 C | 23.68% |

Temperature values are rises above the same 20 C background. Cooling fractions divide by each case's actual source-off room rise. The sealed 0.1x/10x cases retain 83.64%/5.33% after 40 physical seconds of cooling. Those extremes are numerical controls, not a recommendation to change the default. Full five-scale samples and material warmth are in the archive.

## Temporal refinement at every scale

Maximum absolute half-second versus quarter-second differences across the three houses, at source removal (600 s), are:

| Response | Room-mean difference | Local air/material temperature difference | Local velocity-component difference |
| --- | ---: | ---: | ---: |
| 0.1x | 0.00253 C | 0.01855 C | 0.003915 blocks/s |
| 0.5x | 0.00752 C | 0.02362 C | 0.003947 blocks/s |
| 1x | 0.00828 C | 0.02457 C | 0.003953 blocks/s |
| 2x | 0.00850 C | 0.02483 C | 0.003955 blocks/s |
| 10x | 0.00851 C | 0.02500 C | 0.003955 blocks/s |

The largest local temperature difference is approximately 0.025002 C. The largest component-velocity difference is approximately 0.003956 blocks/s; both bounds also cover the recorded 680 s endpoint and the final heating-minute mean. Room-mean cooling differences at 680 s stay below 0.00340 C. The quarter-second solution remains a comparison reference at fixed spatial resolution, not an exact solution or a universal error bound.

This supports retaining the proposed 0.5 s step across the tested response range on these fixtures. Each case remains a finite-horizon response comparison. Slow-rate cases are still warming late in the run, so their larger within-window ranges include drift and must not be called equilibrium oscillations. No spatial refinement, combined padding/rate study, storm qualification, or fine-step own-equilibrium calibration is claimed.

## Verification and next work

- Direct Java 21 compilation of all main/test sources in the pure `heat.air` package succeeds. **129/129 tests pass**, none skipped. Eight added tests cover chronological snapshots, segment validation, bounded elapsed advances, off-step transitions, failure accounting, and physical momentum controls. Pre-existing tests and numerical acceptance thresholds remain intact.
- All **30 house cases complete 61,200 accepted steps without rejection**. The 0.5/0.25 s batches advance 20,400/40,800 steps respectively. Maximum divergence is `1.240e-8 /s`, below the unchanged `1e-7` criterion. Maximum combined two-half energy error is `1.998e-8 C*air-cell`, retaining each half's original `1e-8` check. Maximum absolute accumulated case energy-budget error is `3.568e-5 C*air-cell`; delivered source energy ranges from 4,500 to 450,000 units across response scales.
- The unity rows exactly reproduce **180 scalar checkpoint values and 36,000 field values** from the prior timestep archive, across both physical steps. The derivation also validates source energy, time accounting, occupancy, geometry fingerprints, finite fields and late-window coverage.
- The required full-project `compileJava compileTestJava test runGameTestServer` invocation remains blocked before Gradle starts by access denied on its wrapper ZIP lock. Direct checks do not verify full-project/architecture/game tests, Gradle task wiring, or client behavior. These runs do not qualify live worker CPU cost or capacity; response speed can change thermal work, and the existing worker budget remains in force.

The follow-up [own-target heating/equilibrium and cooling calibration at 0.5 s](local-air-refined-calibration-results.md) is complete. It supplies a fine-step target independently of the older 1 s settled calibration. Source powers/materials/response defaults remain unchanged. Review the calibration differences and remaining material/fluid boundary contract before replacing established behavior. Chronological clock snapshots are ready for later runtime integration; a production resumable worker and live budget/freshness tests remain separate work.

Raw data, validation script and commands: [response archive](../benchmarks/air-response-2026-10-20/README.md).
