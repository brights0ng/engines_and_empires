<!-- Written by ChatGPT. -->
# Half-second settled house calibration, 2026-10-20

Follow-up: [static-fluid support and the boundary contract](local-air-boundary-results.md) add explicit fluid thermal ownership while preserving the dry regression results. This report retains the original dry-house measurements.

This rerun measures settled warmth, each model's own warm-up target, and source-off cooling at the proposed **0.5 s coupled physical step**. The legacy control retains its historical 1 s step. Source power, material settings, response 1, calm exterior, 20 C background, and the original padding-6 house fixtures remain unchanged. The earlier [1 s coupled calibration](local-air-calibration-results.md) is preserved as historical evidence.

## Method and interpretation

Use the same long protocol: three consecutive 1,000-physical-second windows must meet the existing per-state thermal-mean drift, energy-storage and room-range checks, with a 10,000 s heating cap. Both models then cool for 900 s with their sources removed. Targets use the trailing 3,000 s room mean. Own t95 and cooling crossings retain **one-second sampling resolution**, so times are comparable with the historical report; they are not interpolated half-second measurements.

Every coupled half-second update contributes to dt-weighted local temperature/flux means and within-window extrema. Legacy window samples stay at 1 s. Strict field steadiness is evaluated separately using endpoint changes and within-window ranges, so sampling one phase of a cycle cannot falsely qualify it. The numerical thresholds are unchanged. A steady field can contain continuing circulation: strict qualification concerns change over time, not zero velocity.

The long run is an offline calibration reference, not a proposed natural-source arrival warm-up or a new runtime initialization cost. It does not select a live domain radius, change source/material calibration, or replace the thermal-only path. Boundary semantics and padding limitations remain those in the [finite-domain report](local-air-qualification-results.md).

## Settled warmth and own-target heating

All three houses qualify at **6,000 physical seconds**, followed by the full 900 s cooling period. Temperatures below are rises above the same 20 C background. Own t95 means reaching 95% of that model's own qualified settled room rise.

| House | Legacy settled rise | Coupled 0.5 s settled rise | Difference | Legacy own t95 | Coupled own t95 |
| --- | ---: | ---: | ---: | ---: | ---: |
| Sealed | 11.471 C | 12.745 C | +1.274 C | 296 s | 374 s |
| Window | 10.802 C | 12.212 C | +1.410 C | 281 s | 360 s |
| Missing wall | 6.858 C | 7.778 C | +0.920 C | 188 s | 220 s |

Relative to the earlier coupled 1 s run, the refined settled means change by **+0.00631 / +0.00568 / -0.11288 C**, sealed/window/missing-wall. Sealed/window own t95 stays 374/360 s; missing-wall own t95 changes from 230 to 220 s. Crossing 95% of the **legacy** target occurs at 196/174/96 s in the refined run. Those are different targets and should not be substituted for coupled own t95.

## Cooling and material warmth

Both models cool from their own qualified source-off states; fractions use their own actual starting room rise.

| House | Legacy / coupled half-time | Legacy / coupled time to 10% | Legacy / coupled warmth remaining after 40 s |
| --- | ---: | ---: | ---: |
| Sealed | 40 / 42 s | 219 / 273 s | 49.68% / 50.93% |
| Window | 37 / 40 s | 205 / 264 s | 47.59% / 49.66% |
| Missing wall | 20 / 21 s | 126 / 191 s | 32.19% / 37.14% |

The near-half cooling time remains close to legacy, while the late tail stays longer. Coupled house-material rises before source removal are **6.085 / 5.854 / 3.922 C**, compared with legacy **4.926 / 4.638 / 3.041 C**. The refined missing-wall half-time is one second shorter than the old coupled run; the sampled 10% times are unchanged for all three houses. These observations do not isolate material storage from different ventilation, exterior handling or removal of legacy thermal heuristics.

## Strict field steadiness

At 0.5 s, **all three houses also pass the separate strict instantaneous-field criteria** over the final three 1,000 s windows. The earlier 1 s run passed thermal statistics but failed those strict checks. This strengthens the timestep evidence that the old persistent local cycles had a numerical contribution. It does not claim zero airflow or continuum convergence.

| House | Max local temperature range across qualifying windows | Max face-flux range | Max room-mean range |
| --- | ---: | ---: | ---: |
| Sealed | 0.00005483 C | 0.000002218 block3/s | 0.00001342 C |
| Window | 0.00004923 C | 0.000002039 block3/s | 0.00001144 C |
| Missing wall | 0.00001824 C | 0.000000953 block3/s | 0.000002314 C |

The strict limits remain 0.001 C for local temperature changes/ranges and `1e-5 block3/s` for face-flux changes/ranges, alongside the original storage criterion. Extrema include every accepted half-second sample. No threshold was loosened to obtain qualification.

## Verification and remaining work

- Direct Java 21 compilation of every main/test source in the pure `heat.air` package succeeds; **130/130 tests pass**, none skipped. The added calibration test independently checks physical-time weighting, substep extrema, unchanged legacy timing, source energy, accepted time and invalid dt handling.
- **41,400 coupled steps complete without rejection**: two updates per second for 6,000 s heating plus 900 s cooling in each house. Maximum divergence is `1.354e-8 /s`, below the unchanged `1e-7` criterion. Maximum combined two-half energy error is `1.999e-8 C*air-cell`, retaining each half's original `1e-8` check. Maximum absolute accumulated case energy-budget error is `9.014e-5 C*air-cell` against 450,000 delivered source units.
- All **5,418 shared legacy heating values** exactly reproduce the historical long run. All **72 coupled room/material/near-source prefix values** through 600 s exactly reproduce the half-second unity response archive. The probes and timestep-capable harness preserve those controls.
- The required full-project command remains blocked before Gradle startup by wrapper ZIP-lock access. These checks do not verify project-wide compilation, architecture/game tests, Gradle task wiring, client visuals, runtime scheduling or throughput.

This completes the planned fine-step settled calibration at response 1 in the original fixture domain. The concrete behavior choice remains: the coupled path is approximately **0.92-1.41 C warmer**, reaches its own t95 **32-79 s later**, and has longer late cooling than legacy. Source powers, material constants, response defaults and live behavior remain unchanged.

Before replacing established behavior, review those differences and the remaining material/fluid boundary contract. An opt-in prototype still needs a bounded world/worker adapter, explicit admission and coverage behavior, and live budget/freshness verification. Default radius/capacity and gameplay acceptance are not selected by this offline pass.

Raw results, validation script, protocol details and commands: [benchmark archive](../benchmarks/air-settled-half-2026-10-20/README.md).
