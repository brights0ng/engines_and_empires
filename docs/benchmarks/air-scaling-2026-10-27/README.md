<!-- Written by Claude. -->
# Coupled-air scaling baseline, 2026-10-27

Phase 0 baseline for `claude/local-air-runtime-plan.md`: offline `CoupledAirGrid` cost and acceptance against domain size, with `FlowSettings.DEFAULT` and `ThermalTransport.Settings.defaults()` (report field `settingsProfile: DEFAULT`). No tuning; later phases compare against these numbers.

## Protocol

- `CoupledAirBenchmark` mode `scaling`, one scene per JVM, three forks per scene (`<scene>-fork-<n>.json`).
- Scenes are the `ThermalBenchmark` fixtures with an open exterior and the 16x2x16 partial slab, built as in `performance` mode. Source node at (nx/2+.5, 3.5, nz/2+.5).
- Variants: **wind-only** (wind 6,0,2 m/s, no source); **heated** (same wind, `AirParams.DEFAULT.gridPower(1,.5)` capped at 65 C); **hot-still** (no wind, 300 W capped at 400 C).
- dt 0.5 s, response scale 1, background and exterior 20 C. 10 warmup and 40 measured steps; stepping continues after a rejection.
- Rebuild: after the last step, copy temperatures and face flux, then time a new owner plus `GeometryTransfer.map(...).initialize(...)`, as a live weather-change rebuild would.
- COMPACT_CACHED thermal traversal, CACHED_TRACE flow kernel, cached sampling origins; Java 21.0.12, AMD Family 25 Model 97, 6 logical processors, `-Xmx1g` G1.

## Results

Each cell is the median of the three fork values (fork means for the timing and count columns, fork maxima for max speed and divergence). Pressure and thermal ms are per-step means; thermal sweeps add both thermal halves.

| Scene | Variant | Cells | Air nodes | Faces | Accepted / rejected | Wall ms mean / p95 | Pressure ms | Thermal ms | Pressure iter mean / max | Thermal sweeps mean / max | Max speed m/s | Max divergence | Rebuild ms |
|---|---|--:|--:|--:|---|--:|--:|--:|--:|--:|--:|--:|--:|
| multiroom16 | wind-only | 4096 | 3543 | 10878 | 40 / 0 | 9.11 / 21.83 | 1.45 | 4.64 | 26.2 / 27 | 15.5 / 18 | 10.554 | 8.65e-08 | 31.39 |
| multiroom16 | heated | 4096 | 3543 | 10878 | 40 / 0 | 4.48 / 5.03 | 1.29 | 2.23 | 26.2 / 27 | 43.3 / 44 | 10.566 | 9.01e-08 | 12.58 |
| multiroom16 | hot-still | 4096 | 3543 | 10878 | 40 / 0 | 4.94 / 6.37 | 1.25 | 3.29 | 24.6 / 25 | 49.4 / 50 | 2.329 | 1.85e-09 | 4.15 |
| multiroom32x16 | wind-only | 16384 | 14463 | 43718 | 40 / 0 | 15.23 / 16.48 | 8.01 | 3.35 | 37.1 / 38 | 17.0 / 20 | 8.982 | 4.49e-08 | 67.02 |
| multiroom32x16 | heated | 16384 | 14463 | 43718 | 40 / 0 | 20.92 / 22.55 | 8.26 | 8.74 | 37.1 / 38 | 46.0 / 46 | 8.982 | 4.59e-08 | 13.71 |
| multiroom32x16 | hot-still | 16384 | 14463 | 43718 | 40 / 0 | 19.80 / 20.74 | 7.00 | 10.81 | 32.3 / 33 | 52.0 / 52 | 2.424 | 1.34e-09 | 8.47 |
| multiroom32 | wind-only | 32768 | 30847 | 93894 | 40 / 0 | 37.25 / 38.57 | 20.66 | 6.62 | 42.5 / 44 | 17.0 / 21 | 9.942 | 8.70e-08 | 114.83 |
| multiroom32 | heated | 32768 | 30847 | 93894 | 40 / 0 | 48.72 / 50.83 | 20.81 | 17.32 | 42.9 / 44 | 46.0 / 46 | 9.942 | 6.06e-08 | 25.58 |
| multiroom32 | hot-still | 32768 | 30847 | 93894 | 40 / 0 | 41.75 / 42.84 | 16.65 | 21.05 | 34.1 / 35 | 52.0 / 52 | 2.424 | 1.95e-09 | 16.77 |
| partial-block-house | wind-only | 9216 | 8138 | 24772 | 40 / 0 | 12.06 / 34.26 | 3.63 | 2.08 | 29.9 / 30 | 17.9 / 23 | 10.323 | 8.65e-08 | 43.65 |
| partial-block-house | heated | 9216 | 8138 | 24772 | 40 / 0 | 10.40 / 11.42 | 3.58 | 4.47 | 30.0 / 31 | 46.0 / 46 | 10.323 | 6.75e-08 | 10.91 |
| partial-block-house | hot-still | 9216 | 8138 | 24772 | 40 / 0 | 10.27 / 11.24 | 3.23 | 6.03 | 26.1 / 27 | 54.0 / 54 | 1.884 | 1.38e-09 | 6.59 |

No step was rejected in any fork, warmup included, so no rejection status appears. Every rebuild projected READY. Maximum step energy error stayed below 2e-8 in every row. Geometry fingerprints match across forks and variants of each scene.

## Notes

- **wind-only runs first in each JVM**, so its owner construction, its rebuild and (on the smaller scenes) its wall p95 include JIT warmup that the later variants avoid. Use the heated and hot-still rebuild figures as the warm rebuild cost; a fork-per-variant run would be needed to separate this properly.
- hot-still rebuilds take 0 pressure iterations: the mapped still-air velocities already satisfy the projection. Wind rebuilds take 5-7.
- Wall time grows roughly linearly with cells from 16k to 32k (heated: about 1.3 us per cell at 16k, 1.5 us at 32k); pressure is the largest share under wind on the 32k scene.
- Timings are from one desktop machine and are not thresholds.
- Report `timeUtc` values read 2026-10-28; the directory uses the local date of the run.
