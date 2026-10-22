<!-- Written by ChatGPT. -->
# Stage 3: sources and two-way heat/air coupling, 2026-10-20

Follow-up: [thermal traversal optimization](local-air-thermal-optimization-results.md) reduces measured cost while preserving this checkpoint's sampled house behavior. Timings and test counts below describe the original source/coupling checkpoint.

The offline coupled update is implemented following the [shared-flux thermal foundation](local-air-thermal-transport-results.md). This checkpoint includes prepared sources, thermostat energy accounting, self-evolving buoyancy/temperature, transactional failure handling, and a comparison with the accepted house fixture. **Stage 3 is not closed:** performance, calibration review, large-response/long-interval qualification, and the full project check remain gates. Nothing is enabled in Meteodyne's live thermal system.

## Implementation

`ThermalTransport.Source` supplies already-converted air/material power in the existing `C*air-cell/s` units and an absolute Celsius limit (or NaN for no limit). It does not reinterpret table powers or add the radiant share to air heating. Prepared source changes affect power/limit arrays without rebuilding thermal or pressure geometry. Invalid source changes retain previous sources.

The thermostat law matches `AirGrid`: sum power at each target, then select the strictest limit in the direction of that net power. A heater that would exceed its limit reduces its delivered power; it never discards unrelated heat or cools an already hotter cell. A cold source applies the corresponding lower-limit rule. The implicit solve checks the nonlinear source-limited residual and explicitly budgets requested energy, delivered source energy, exterior transfer, and remaining error. Withheld energy is signed, negative for withheld cooling. There is no generic temperature clamp. An isolated-cell comparison against the legacy solver verifies the same heating/cooling thermostat response.

`ThermalSources` routes already-prepared power on the physical geometry. A source in represented air distributes power by accessible volume among its own pockets. A full source block emits to touching neighboring air pockets; each eligible face gets one share of total power, or one full power contribution for a `perFace` source. Pockets sharing a face divide that face's contribution by aperture area, avoiding multiplication by pocket count. An enclosed non-per-face solid source retains the legacy material fallback; an enclosed per-face source emits no air power. Routing never opens the source block or changes mass connectivity. World table/driver/offset-limit capture is still future runtime adapter work.

`CoupledAirGrid` owns both mutable numerical components and advances:

1. Half the response-scaled thermal interval on the last valid projected flux.
2. One bounded **physical** airflow interval: velocity transport, temperature-driven buoyancy, and pressure projection.
3. The other thermal half on the newly projected flux.
4. Finite/temperature-bound checks and one complete commit.

The pressure/velocity candidate and pressure warm start are not accepted until the second thermal half succeeds. Reusable private temperature backup buffers restore air/material state after a momentum, pressure, second-half, or final-temperature failure. Failed steps report zero committed source/exterior energy; their phase records remain available as attempted-work diagnostics. Numerical scratch buffers may change but published temperatures/flow and the pressure warm start remain compatible. No failure silently clips temperature/velocity or advances physical progress. The existing standalone `FlowGrid.step` retains immediate successful acceptance.

Ambient-profile handling is explicit. Air/air unresolved mixing acts on departures from the prepared background profile; its corrections are opposite internal transfers. Absolute-temperature advection and air/material exchange retain their meanings. This preserves an unheated altitude gradient instead of letting diffusion alone create artificial buoyancy. Changing the reference does not reset stored temperatures. Material conduction can still redistribute a material's real temperature; no atmospheric adiabatic model is claimed.

One call advances at most `FlowSettings.maxStep` physical seconds. Response scaling changes the thermal halves, not gravity, weather timing, reported wind units, or the physical flow interval. Long thermal settling/catch-up is not converted into one long flow step. Supported numerical settings remain experimental and bounded; accepting a response-scale input does not guarantee convergence for every workload.

## Verification

Every main and test source in the pure `heat.air` package compiled with Java 21. Direct JUnit Platform execution passed **96/96 tests**, including **19 new source/coupling tests** in addition to the previous checkpoint's 77. All existing thermal, timing, pressure, and airflow fixtures remain included.

New evidence covers signed source delivery/withholding, summed opposing sources and strict limits, ventilation plus thermostat storage, retained source inputs on validation failure, matching isolated legacy thermostat response, source-face/pocket routing, enclosed-source fallback, self-evolving hot updraft/cold descent and return circulation, ventilation through real openings versus sealed walls, preservation of an unheated altitude profile, response/physical-time separation, and failure retention at both thermal halves, pressure, speed, and final temperature checks. A second-half failure followed by a zero-iteration recovery verifies that rejected pressure did not become the warm start.

The required `compileJava compileTestJava test runGameTestServer` command was attempted again. It failed **before executing the build**, because the sandbox could not write the user Gradle wrapper lock. Earlier attempts with writable Gradle state failed at daemon connectivity; they were not passing builds either. The direct Java/JUnit checks do not replace project-wide compilation, architecture tests, standalone mod runs, or game tests. Logs: `../../build/stage3-coupled-tests.log` and `build/stage3-coupled-full-check.log`. No tests/assertions were removed, weakened, or skipped.

No client visual/gameplay trial, world adapter, gauge integration, worker admission test, or live performance capture was performed.

## Measured results and calibration gate

[Archived reports and reproduction commands](../benchmarks/coupled-air-2026-10-20/README.md) contain three performance JVM forks and one deterministic house comparison.

- All **450 measured coupled updates** and 225 warm-up updates passed on the tested workloads: physical steps 0.25/0.5/1 s at response scale 1, and 1 s at scales 0.5/2, across the 32³/48³/partial-block fixtures. This does not qualify scale 10 or long thermal intervals on those domains.
- At one physical second and response scale 1, median fork-mean update cost was **106.79 ms at 32³**, **411.60 ms at 48³**, and **27.03 ms** for the partial-block house. These include both thermal halves and velocity/pressure work, but exclude world preparation, publication, and scheduling. The larger fixture is well above the plan's approximately 100 ms CPU/s direction; radius/capacity defaults remain unqualified.
- Three house cases each completed 600 seconds heating and 80 seconds cooling: **2,040 coupled steps without rejection**. The legacy block layout, material coefficients, 75-unit virtual-air campfire power, and unlimited fixture source are preserved. The new pressure-open exterior and removed heuristic ventilation intentionally differ from the old numerical model, and the report does not isolate their individual contributions.

At 600 seconds, offsets above the same 20 C background were:

| House | Legacy mean offset | Coupled mean offset | Legacy / coupled after 40 s cooling |
| --- | ---: | ---: | ---: |
| Sealed | +11.39 C | +12.52 C | +5.63 / +6.31 C |
| One window | +10.73 C | +12.01 C | +5.08 / +5.90 C |
| Missing wall | +6.84 C | +7.84 C | +2.19 / +2.89 C |

For the sealed house, 49.45%/50.39% of each model's own end-of-heating warmth remained after 40 seconds. Thus absolute retained warmth increased, while this normalized cool-down measure stayed close to the accepted half-in-about-40-seconds behavior. The sealed coupled model reached 95% of the **legacy** equilibrium at 197 seconds versus the legacy model's 296 seconds. These are crossings of the same old target, **not equal fractions of each model's own equilibrium**; the coupled equilibrium and its own t95 have not been qualified.

The difference is recorded rather than calibrated away. Existing source powers/material constants, `AirGrid`, and accepted tests remain unchanged. Bright must review any intentional change to the accepted warming/equilibrium before replacement defaults. Next work should address measured solver cost and remaining numerical/geometry/boundary qualification, then present concrete calibration choices before exposing the stage 4 prototype. Fluid thermal state/interface treatment and finite-domain material boundaries retain the first checkpoint's documented limitations.
