<!-- Written by ChatGPT. -->
# Coupled house calibration, 2026-10-20

Latest settled comparison: the [half-second calibration](local-air-refined-calibration-results.md) remeasures own-target heating/cooling and qualifies strict field steadiness at the proposed prototype step. The 1 s measurements below remain archived historical results.

Follow-up: [timestep and domain qualification](local-air-qualification-results.md) identifies strong timestep sensitivity in the local oscillations below and recommends 0.5 s for a future prototype. This report's settled means and own-target times remain measurements at **1 s**, not a newly qualified fine-step equilibrium. No historical measurement has been replaced or retuned.

The coupled model settles to a warmer **mean temperature**, takes longer to reach 95% of its own warmth, and retains warmth longer late in cooling than the legacy model. This measurement pass changes no production coefficients, source powers, geometry, solver settings or thermal-response defaults. Bright accepted ending the performance-optimization pass; the remaining work here is behavior qualification.

## Settled warmth and own-target warm-up

The accepted 10×10×5 house fixture and its window/missing-wall variants use a 75-unit unlimited virtual-air source, calm exterior wind, 20°C background, one-second physical steps and response scale 1. Each model starts cold. Temperatures below are rises above that same background, not absolute Celsius temperatures.

| House | Legacy settled mean rise | Coupled settled mean rise | Difference | Legacy own t95 | Coupled own t95 |
| --- | ---: | ---: | ---: | ---: | ---: |
| Sealed | +11.471°C | +12.739°C | +1.267°C | 296 s | 374 s |
| One window | +10.802°C | +12.206°C | +1.404°C | 281 s | 360 s |
| Missing wall | +6.858°C | +7.891°C | +1.033°C | 188 s | 230 s |

Own t95 is the first one-second sample reaching 95% of **that model's own settled temperature rise**. The sealed case is about 4 min 56 s legacy versus 6 min 14 s coupled. Earlier reported coupled crossings of 197/175/96 s remain correct for the **legacy temperature target**; they do not mean the coupled model reaches 95% of its own warmer final state that quickly.

Near-fire feedback remains prompt. At 15 s, the sealed house's air one block from the source is +7.949°C legacy and +8.747°C coupled. Adding the existing unchanged +6°C clear-line radiant term gives +13.949°C and +14.747°C felt warmth above background. Both exceed half of their own final near-fire air-plus-radiant warmth (about 65% and 61%, respectively). Radiation is an unchanged analytical comparison here, not newly transported heat or a live player test.

## Cooling from settled conditions

After heating qualifies, both sources are removed and both models run for another 900 physical seconds. Fractions use each model's **actual source-off** room-temperature rise, so different starting warmth does not distort the normalized comparison.

| House | Legacy / coupled half-time | Legacy / coupled time to 10% | Legacy / coupled fraction after 40 s |
| --- | ---: | ---: | ---: |
| Sealed | 40 / 42 s | 219 / 273 s | 49.68% / 50.97% |
| One window | 37 / 40 s | 205 / 264 s | 47.59% / 49.70% |
| Missing wall | 20 / 22 s | 126 / 191 s | 32.19% / 37.20% |

The initial cooling half-time remains close to the accepted behavior, but the longer cooling tail is materially slower. Corresponding house-material mean rises before source removal are 4.926/6.081°C sealed, 4.638/5.850°C windowed, and 3.041/3.981°C with the missing wall, legacy/coupled respectively. The coupled fixture holds more warmth in its material state. These comparisons do not isolate the contributions of pressure-open boundaries, different ventilation, removed legacy heuristics, or material/exterior handling.

## What qualified, and what did not

The full coupled field does **not** become motionless. A strict pilot ran the sealed case for 6,000 s and found continuing local oscillations despite its stable room mean. Short 100 s thermal-average windows qualified sealed/window cases at 3,300 s, but the missing-wall case did not meet those checks by 6,000 s. Those results remain archived.

The final confirmation uses three consecutive **1,000 s windows**, at the same absolute drift and energy thresholds. Every coupled air/material state's window mean and every legacy cell's window mean must change by at most 0.001°C between windows. Absolute stored-energy change must average at most `0.001 C*air-cell/s`, and the room mean must span at most 0.01°C within a window. All three houses qualify at 6,000 s. The equilibrium target is their average room rise over the final 3,000 s, not a single favorable snapshot.

Strict instantaneous status remains separate and is **false for every house**. Its final checks include both endpoint changes and within-window ranges, preventing periodic motion from appearing steady when snapshots happen to sample the same phase. Maximum local temperature ranges over the qualifying windows are about 0.098/0.085/0.210°C; maximum face-flux ranges are 0.141/0.139/0.306 block³/s, sealed/window/missing-wall respectively. In contrast, room-mean ranges stay below 0.000050/0.000040/0.000987°C. These measurements qualify settled thermal statistics, not a fixed flow field or the physical cause of the oscillations. Timestep sensitivity and their interpretation remain part of numerical qualification.

## Verification

- Java 21 directly compiles every main/test source in the pure `heat.air` package; **115/115 tests pass**, with none skipped. Six added measurement tests cover own versus shared targets, normalized cooling, work caps, strict versus statistical qualification, invalid/drifting data, and cycle-phase sampling.
- The final run completes **20,700 coupled steps without rejection**: 6,000 heating plus 900 cooling per house. Maximum divergence is `4.520e-9 /s`; maximum combined two-half energy error is `1.997e-8 C*air-cell`, retaining each half's original `1e-8` acceptance check.
- Maximum absolute accumulated energy-budget error is `2.052e-5 C*air-cell` over a case with 450,000 source-energy units. This is accumulated accounting error, not a relaxed per-step criterion.
- All **105 shared prefix values** through 600 s exactly match the preceding archived calibration, covering room/material/near-source values. The new measurement probes do not alter the original heating trajectory.
- The required full Gradle check remains blocked before startup by access denied on the wrapper ZIP lock. These numerical checks do not replace full-project, architecture, game-test or client verification.

Raw data, negative pilots, methodology and commands: [benchmark archive](../benchmarks/air-equilibrium-2026-10-20/README.md). Only response scale 1 and the stated fixture/boundary conditions are qualified by this comparison.

## Decision and next work

The calibration difference is now concrete for Bright to review: roughly **1.0–1.4°C warmer settled rooms**, own t95 **42–79 s later**, and longer late cooling. No automatic source/material/response retuning was applied. Keeping this behavior for an opt-in prototype and restoring the old warming curve are different behavior choices; this report does not silently select either as a replacement default.

Before Stage 4, finish response-scale/long-interval and finite-domain/material-boundary qualification, including domain-padding sensitivity. Preserve the legacy comparison and resolve project-wide verification when the environment permits. The observed local oscillations also need a timestep comparison; the stable room mean alone does not close that numerical question. Stage 3 is not declared fully closed by this report.
