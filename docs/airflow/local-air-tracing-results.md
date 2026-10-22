<!-- Written by ChatGPT. -->
# Wall-safe tracing optimization, 2026-10-20

The selected offline kernel now reuses direction reciprocals throughout each trace and uses exact binary reciprocals for its block/sixteenth lattice scales. The pressure specialization experiment remains off by default. This follows the [cached arithmetic checkpoint](local-air-arithmetic-results.md); Stage 3 remains open.

## Measured result

Three reference forks and three final trace-only forks use the same evolving source/wind fixtures, physical steps, response scales, JVM arguments and acceptance settings. Values are medians of fork means at one physical second and response scale 1:

| Scene | Reference total wall ms | Selected total wall ms | Reduction | Reference / selected velocity transport ms |
| --- | ---: | ---: | ---: | ---: |
| Multiroom 32³ | 62.37 | 60.64 | 2.8% | 16.65 / 12.84 |
| Stress 48³ | 260.08 | 255.18 | 1.9% | 59.15 / 46.57 |
| Partial-block house | 15.81 | 14.27 | 9.7% | 4.95 / 3.31 |

The large fixture's velocity phase falls **21.3%**. Total cost improves less because unchanged thermal/pressure phases vary between runs: their medians are 100.07/99.87 ms reference and 102.97/104.27 ms selected, respectively. Total CPU medians are 260.94 versus 253.12 ms/update, with coarse Windows CPU counters. The reference forks ran earlier, interleaved with the combined experiment; selected trace-only forks followed the ablations. This is not a fully interleaved reference/selected experiment. Small total improvements should be treated cautiously; the repeatable phase reduction is stronger evidence than the exact full-update percentage.

The approximately 100 ms CPU/s target remains unmet at 48³. These are offline worker costs, not game-thread MSPT, live coverage, or multiplayer capacity measurements. Raw reports, launch order and reproduction controls are in the [benchmark archive](../benchmarks/air-hotloops-2026-10-20/README.md).

## Selected tracing change

Previously, each crossing recomputed `(plane - position) / direction`. A trace now computes each direction reciprocal once, then multiplies the remaining distance at subsequent crossings. A reciprocal that overflows or is subnormal retains the original division path. A zero direction still gives infinite crossing time. The geometry traversal only uses lattice scales 1 and 16, so multiplying a lattice-plane index by 1 or `0.0625` exactly replaces that scale division.

Plane ordering, the whole-block/partial-shape traversal, per-face occupancy checks, conservative corner checks, collision stop offsets, iteration cap and final node lookup remain in place. This does not bypass intermediate walls. Reciprocal rounding can change coordinates at floating-point roundoff scale; the retained reference path and tests compare collision decisions, component identity and positions.

The selected default is `FlowGrid.Kernel.CACHED_TRACE`. `REFERENCE` retains the preceding trace arithmetic. Both keep the preceding thermal/sampling caches enabled unless those separate controls are changed. No new per-cell/face work buffers are added; measured update allocation remains **368 bytes**, independent of cell count.

## Pressure experiment not selected

Most regular pressure rows have three neighbors in each triangular pass. An explicit three-entry specialization preserves subtraction order exactly; all other row degrees retain the original loop. It passes exact corrected-velocity, pressure and diagnostic comparisons for open/sealed partial geometry, warm starts and all three preconditioners.

However, the repeated combined experiment's 48³ pressure median is **102.96 ms**, versus **99.87 ms** for reference. An isolated single-fork ablation also does not establish an improvement. Variation and JIT behavior prevent attributing a precise slowdown, but the measurements do not justify changing the default. Ordinary triangular loops remain selected in both the coupled owner and standalone pressure constructors. `UNROLLED_PRESSURE` and `OPTIMIZED` retain the pressure-only and combined experiments for explicit offline comparisons; the name `OPTIMIZED` does not indicate the selected default.

No factorization, pressure tolerance, iteration cap, warm-start transaction, material data, source law or physical timestep was changed. Pressure preconditioning remains a future optimization target rather than a claimed improvement from this pass.

## Verification and remaining gates

Java 21 directly compiles every main/test source in the pure `heat.air` package. **109/109 numerical tests pass**, retaining all previous tests and assertions. Four additional fixtures cover exact pressure-kernel agreement, 20,000 seeded random traces through partial/solid/fluid geometry and long displacements, near-corner/thin-wall/subnormal-direction comparisons, and coupled source/material/flux agreement across all kernel modes, both wind directions and benchmark timestep/scale values. Existing failed-update retention, tiny-volume, ambient-profile and thermostat tests pass with the selected default.

The final selected configuration passes **450/450 measured updates plus 225 warm-ups**, as does the reference configuration. Maximum accepted selected divergence is `9.698e-8 /s`; maximum combined two-half energy error is `1.954e-8 C*air-cell`, with each half retaining its `1e-8` acceptance check. Maximum pressure work remains 60 iterations across these configurations. Other experiment reports are archived separately rather than pooled into the headline result.

All three calibration houses complete 600 s heating plus 80 s cooling: **2,040 steps without failure**. All 81 sampled room/material/near-source temperatures are exactly equal to the preceding arithmetic checkpoint's serialized values. Legacy-equilibrium target crossings remain 197/175/96 s. The warmer-than-legacy coupled calibration difference remains unresolved and unretuned; these crossings do not qualify the coupled model's own equilibrium.

The required full Gradle command remains blocked before build startup by access denied on the user wrapper ZIP lock. Direct numerical compilation/tests do not replace full-project/game-test verification. No live worker, client visual or gameplay check was performed. Stage 3 still needs its cost/calibration and remaining qualification gates before runtime integration.
