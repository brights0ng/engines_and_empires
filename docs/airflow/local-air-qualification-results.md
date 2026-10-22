<!-- Written by ChatGPT. -->
# Coupled timestep and finite-domain qualification, 2026-10-20

Boundary follow-up: [static-fluid thermal support](local-air-boundary-results.md) adds explicit fluid storage/exchange. The no-fluid-state limitation described below applies to the historical constructor; coupled owners now reject that representation when fluid is present. The timestep and dry-domain measurements remain unchanged.

Use **0.5 physical seconds as the proposed prototype timestep**. The rapid local oscillations observed at 1 s are strongly timestep dependent: they sharply diminish at 0.5 s and remain small at 0.25 s. This is evidence of a numerical contribution to the coarse-step cycles; stable room averages alone did not establish physical circulation behavior. No production default, source power, material coefficient, response scale, or solver tolerance was changed.

A 0.5 s step requires two coupled updates per simulated second. The earlier approximately 255 ms result was per update on its stated workload, not the cost of this refined physical second. Preserve the existing worker budget and remeasure CPU per advanced physical second before selecting runtime cadence/capacity. This diagnostic pass is not a new performance qualification.

## Timestep refinement

Three cold-start houses use identical geometry, a 75-unit unlimited virtual-air source, 20 C background, calm exterior wind, and response scale 1. All runs heat for 600 physical seconds and then cool for 80, with samples at matching physical times. Each case is independently initialized. The original padding is 6 and ground depth is 3. The quarter-second run is a comparison reference, not an exact solution.

| House | Max local temperature difference, 0.5 vs 0.25 s at 600 s | Max velocity-component difference | Late velocity-component range at 1 / 0.5 / 0.25 s |
| --- | ---: | ---: | ---: |
| Sealed | 0.01694 C | 0.00395 blocks/s | 0.09877 / 0.000372 / 0.000369 blocks/s |
| Window | 0.01710 C | 0.00395 blocks/s | 0.10263 / 0.000401 / 0.000399 blocks/s |
| Missing wall | 0.02457 C | 0.00381 blocks/s | 0.17832 / 0.004077 / 0.003270 blocks/s |

The maximum temperature differences include all 500 house air/material positions. Velocity differences/ranges use reconstructed cell-centered components, not face fluxes or vector norms. Late ranges cover seconds 540-600 and include slow residual drift. Normalized second differences also fall sharply: the sealed case's maximum `abs(v[n]-2v[n-1]+v[n-2])/dt^2` falls from 0.137 at 1 s to about `3.7e-8` at both finer steps. Missing-wall values fall from 0.249 to about `2.6e-6`. These are temporal diagnostics at fixed spatial resolution, not proof of continuum convergence or turbulence behavior.

The missing-wall room at 600 s is +7.83930 C at 1 s, +7.72636 C at 0.5 s, and +7.73463 C at 0.25 s. The half/quarter room difference is about 0.00828 C. At 680 s it is about 0.00237 C. Thus the coarse step can bias even the room average while satisfying divergence/energy checks. The refined sealed/window temperature fields differ by less than 0.018 C at source-off.

All 81 room/material/near-source prefix values at 1 s exactly reproduce the preceding 600/80 calibration archive. Measurement probes and the parameterized geometry preserve that control. The earlier [settled calibration report](local-air-calibration-results.md) remains valid as a **1 s measurement**; its 6,000 s means, own t95 values, and cooling tails have not been remeasured at 0.5 s. This finite-horizon comparison does not establish fine-step equilibrium.

## Exterior padding

At the proposed 0.5 s step, compare padding 8 with 16, changing horizontal and top extent together while preserving the house and three-layer ground depth. Domains contain 14,112 and 50,336 spatial cells. The wider case is a reference comparison, not an infinite exterior.

| House | Room-mean difference at 600 s | Max local temperature difference | Max velocity-component difference |
| --- | ---: | ---: | ---: |
| Sealed | 0.03463 C | 0.04780 C | 0.000097 blocks/s |
| Window | 0.03715 C | 0.05187 C | 0.000254 blocks/s |
| Missing wall | 0.08348 C | 0.11752 C | 0.068143 blocks/s |

Values are absolute differences. Sealed/window local flow changes little in this pair. The missing-wall late-mean velocity difference is also about 0.06920 blocks/s, so its larger difference is not just a single source-off snapshot. Both padded runs have small late within-window velocity ranges (about 0.00174 and 0.00269 blocks/s); this is a domain-sensitive circulation pattern rather than the coarse-step rapid cycles. The follow-up below compares padding 16 against a larger exterior. No agreed numerical acceptance threshold or live radius is selected here. Increasing padding also extends represented ground horizontally; this pair does not isolate individual boundary mechanisms.

### Follow-up: missing wall, padding 24

The unchanged single-case harness adds a 60x34x60 domain (122,400 spatial cells) at padding 24, ground depth 3, and step 0.5 s. It repeats the same cold-start 600 s heating plus 80 s cooling protocol. Compare all fields at the same 500 house positions:

| Padding pair | Room-mean difference at 600 s | Max local temperature difference | Max velocity-component difference | RMS velocity-component difference |
| --- | ---: | ---: | ---: | ---: |
| 8 versus 16 | 0.08348 C | 0.11752 C | 0.068143 blocks/s | 0.006960 blocks/s |
| 16 versus 24 | 0.03275 C | 0.04333 C | 0.004628 blocks/s | 0.001153 blocks/s |

All differences are absolute. Between 16 and 24, the maximum velocity difference is about 15 times smaller than the earlier pair; the temperature difference also shrinks. The late-mean maximum velocity difference is 0.004879 blocks/s, so the improvement persists across the last heating minute. The padding-24 within-window velocity range is 0.002920 blocks/s, with normalized second difference `4.478e-7`; the large domain does not reintroduce the coarse-step rapid cycles during this window. At 680 s the 16/24 room-mean difference is 0.02801 C and the maximum local velocity difference is 0.003107 blocks/s.

The padding-16 and padding-24 source-off room rises are +7.84911 C and +7.88187 C. The remaining boundary-induced field differences are of the same velocity order as the preceding 0.5/0.25 s timestep comparison, though that comparison used the smaller original domain. These are separate sensitivity studies, not a combined error bound. The results support **padding 16 as a provisional candidate for this experimental house fixture**, using 24 as a larger comparison reference. They do not prove infinite-domain convergence, qualify every geometry/weather condition, select a universal runtime radius, or establish long-run equilibrium. Padding 24 represents 2.43 times as many spatial cells as padding 16; this diagnostic run is not a capacity benchmark.

The follow-up completes **1,360 additional steps without rejection**. Direct compilation and all **121 numerical tests** pass again. Maximum divergence is `2.829e-9 /s`; maximum combined two-half energy error is `1.997e-8 C*air-cell`; absolute accumulated energy-budget error is `1.410e-5 C*air-cell`. The full Gradle check is attempted again and remains blocked at the same wrapper lock before startup. No Java source, numerical setting, source/material calibration, or production default changes in this follow-up. Across the original study plus this case, 19 cases complete 27,880 accepted steps.

Raw data and reproducible field/scalar comparisons are `missing-wall-padding24.json` and `radius-comparisons.json` in the [archive](../benchmarks/air-qualification-2026-10-20/README.md). The original 18-case report and raw files remain unchanged.

## Ground depth

At padding 8 and a 0.5 s step, increasing represented ground from three to six layers gives:

| House | Room-mean change at 600 s, deeper minus original | Max local temperature difference | Max velocity-component difference |
| --- | ---: | ---: | ---: |
| Sealed | +0.00306 C | 0.03862 C | 0.000114 blocks/s |
| Window | +0.00235 C | 0.03707 C | 0.000105 blocks/s |
| Missing wall | -0.00240 C | 0.02390 C | 0.000360 blocks/s |

Room-mean cooling differences at 680 s are +0.00699, +0.00595, and -0.00096 C respectively. The tested depth change matters less to room means and flow than the exterior-padding change. It still changes material temperatures and does not prove long-run ground-depth independence. Keep both depths in the evidence; do not silently replace the historical house fixture.

## Verification

- Direct Java 21 compilation of every main/test source in the pure `heat.air` package succeeds; **121/121 JUnit tests pass**, with none skipped. Six new tests cover matched geometry, physical clock/error normalization, and current boundary limitations.
- All **18 cases and 26,520 coupled steps** complete their 600 s heating plus 80 s cooling without rejection. Maximum divergence is `4.520e-9 /s`. Maximum combined two-half energy error is `1.998e-8 C*air-cell`, retaining each half's original `1e-8` criterion. Maximum absolute accumulated case energy-budget error is `2.283e-5 C*air-cell` against 45,000 units of delivered source energy.
- The derivation script validates counts, physical progress, late-window coverage, matched occupancy, and exact 81-value historical prefix reproduction. Full raw house fields are archived alongside scalar summaries.
- The required `compileJava compileTestJava test runGameTestServer` command is still blocked before Gradle startup by access denied on its wrapper ZIP lock. Direct numerical compilation/tests do not verify the Gradle task wiring, full mod dependency graph, architecture tests, game tests, or client visuals. No assertions were weakened and no tests were removed.

## Boundary semantics and remaining gates

Targeted tests pin the current behavior: exterior air with zero advective flux has no additional diffusive weather reservoir; unrepresented material beyond the outer boundary is insulated; fluid occupancy has no thermal state and does not form a conductive bridge. These are explicit limitations of this checkpoint. Padding/depth sensitivity cannot qualify missing fluid heat physics or select a gameplay boundary policy.

The planned larger-exterior comparison is complete; choosing a general runtime radius/cell budget remains open. Follow-up [response-scale and elapsed-interval checks](local-air-response-results.md) preserve chronological rates and compare bounded physical stepping. The [own-target calibration at 0.5 s](local-air-refined-calibration-results.md) is also complete. Stage 3 retains review of the calibration behavior and remaining material/fluid boundary contract. Keep the thermal-only comparison and existing source/material settings. The later runtime prototype must split elapsed time into bounded physical steps rather than treating distant/catch-up intervals as one airflow step.

Raw data, derivation script, geometry definitions, and reproduction commands: [qualification archive](../benchmarks/air-qualification-2026-10-20/README.md). No live world, instrument, client visual, or multiplayer behavior is qualified here.
