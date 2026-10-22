<!-- Written by ChatGPT. -->
# Thermal traversal optimization, 2026-10-20

The offline coupled solver now uses compact thermal rows, cached boundary/reference work, adaptive full-check scheduling, and flow-directed sweeps where appropriate. The equations, physical/thermal time separation, source laws, material data, pressure solver, and acceptance tolerances remain the same. This follows the [source/coupling checkpoint](local-air-coupled-results.md); stage 3 is still open.

## Measured improvement

Fresh three-fork reference and optimized runs used the same evolving source/wind fixtures and settings, with mode launch order alternated. Values are medians of fork means for one physical second per update at response scale 1:

| Scene | Reference total wall ms | Optimized total wall ms | Reduction | Reference / optimized thermal ms |
| --- | ---: | ---: | ---: | ---: |
| Multiroom 32³ | 109.03 | 66.45 | 39.1% | 70.26 / 28.52 |
| Stress 48³ | 420.41 | 276.09 | 34.3% | 247.89 / 109.60 |
| Partial-block house | 27.82 | 16.89 | 39.3% | 18.61 / 8.01 |

Thermal work in the large fixture fell **55.8%**, about 2.26 times faster. Complete numerical worker CPU cost fell from 407.81 to 271.88 ms/update. These are worker costs; whole-server MSPT, live coverage/freshness, world snapshots, and publication remain unmeasured. The approximately 100 ms CPU/real-second direction is still unmet for this fixture.

Optimized 48³ phase medians are about **109.60 ms thermal, 106.88 ms pressure, and 58.52 ms velocity**. Independent phase medians need not sum exactly to the total. Remaining thermal sweeps and pressure/velocity transport now have comparable significance.

Raw results, controls, counters, and commands: [benchmark archive](../benchmarks/thermal-optimization-2026-10-20/README.md).

## Changes

`ThermalStencil` combines mixing/material exchange and directed advection contributions to each unique neighbor. Cached face-to-row offsets scatter flux coefficients without searching rows. At 48³, neighbor entries fall from **1,267,996 to 649,728**. Repeated contributions are summed; no physical face, material connection, or disconnected pocket is discarded.

Diffusion diagonals are cached. Background-profile mixing corrections rebuild only when the reference changes, without resetting temperatures. Exterior faces are indexed once: an energy check visits **13,824 exterior faces instead of filtering 322,958 faces**. Full-geometry input flux balance validation remains.

While temperatures are changing substantially, a cheap maximum-update measure can defer a full residual/energy scan. Full checks run at least every eighth sweep, sooner when changes become small, and always at the work cap. The cheap measure **never authorizes publication**. Every accepted step passes the same full nonlinear residual, source/exterior energy budget, finite/bound checks, and transactional commit. The cap remains 512 sweeps; tolerances remain `1e-9 C` unresolved temperature and `1e-8 C*air-cell` energy error per thermal half. No generic temperature clamp or tolerance relaxation was added.

When signed face flux has a coherent component (absolute directional sum above 25% of that axis's absolute-flux sum), a cached octant order visits upstream cells first, including each cell's air pockets and material state. Direction changes overwrite a retained array. Otherwise the solver keeps alternating natural sweeps. This is a Gauss-Seidel ordering choice, not a change to wind, buoyancy, geometry, or the thermal operator. Maximum half-step sweeps/checks in the large one-second fixture fall from 44/44 to 39/9.

Four modes remain available: `REFERENCE`, `COMPACT_EVERY_SWEEP`, `COMPACT_ADAPTIVE`, and the offline default `COMPACT_DIRECTED`. Reference retains duplicate-neighbor rows and checks every sweep; common exterior indexing/reference-input caching is shared. Diagnostic controls show most gain comes from compaction and less frequent checks. Directed ordering reduces iterations, but its small complete-update timing advantage over the adaptive control overlaps fork variation; no large isolated ordering speedup is claimed.

## Memory and behavior

Primitive operator row/mapping/order storage at 48³ is approximately **30.87 MB reference versus 18.23 MB optimized**, excluding headers and common geometry, pressure, temperature/source buffers, and other shared state. This is array-layout accounting, not a retained-heap profile. Setup still allocates temporary arrays and is cached per geometry owner; it must remain worker work during integration. Update allocation is **368 bytes**, fixed diagnostic/result records with no allocation per cell/face.

All three house variants completed 600 seconds heating plus 80 cooling: **2,040 optimized coupled steps without rejection**. Across 81 sampled coupled room/material/near-source temperatures, the maximum difference from the preceding checkpoint was **1.864e-10 C**. Legacy-target crossings remain 197/175/96 s. The prior warmer-than-legacy calibration difference is preserved, not resolved or retuned. Mean calm-house update cost is now about 11.7–12.4 ms versus the previous checkpoint's 19.5–20.0 ms; that historical comparison is separate from the fresh paired headline runs.

## Verification and remaining gates

Java 21 directly compiled every main/test source in the pure `heat.air` package. **103/103 tests pass**, including seven new fixtures for unique neighbors, independent signed-flux/operator agreement, all-mode agreement through partial geometry/material/source/reference changes and wind reversal, full checks at acceptance/caps, directed convergence in either passage direction, all octants plus calm fallback, and directed failure retention. Existing 96 tests and their assertions remain intact.

The final comparison passed **450/450 measured optimized updates and 450/450 reference updates**, plus 225 warm-up steps per mode. Every initialization passed. Maximum accepted optimized divergence was `9.698e-8 / second`; maximum combined two-half energy error was `1.976e-8 C*air-cell`, with each half retaining its `1e-8` check. Workloads cover physical steps 0.25/0.5/1 s at scale 1 and 1 s at scales 0.5/2. Other scales/extremes, long intervals, and fluid/material exterior-boundary limitations remain unqualified.

The required `compileJava compileTestJava test runGameTestServer` command was attempted after final numerical changes but failed before execution at the sandbox-denied user Gradle wrapper lock. Direct Java/JUnit checks do not replace the full architecture/project build, standalone mod checks, or game tests. Logs: `build/stage3-thermal-directed-tests.log` and `build/stage3-thermal-directed-full-check.log`. No client visual/gameplay trial or live capacity capture was performed.

Next gates remain performance qualification, unresolved numerical/boundary coverage, calibration review, and full project verification before stage 4. No active-domain/radius defaults or gameplay calibration were selected from these measurements.
