<!-- Written by ChatGPT. -->
# Experimental local-air consumer query

Updated 2026-10-26. The current sources remain under `meteodyne/.../heat/world/`; this checkpoint adds a public point query around the existing opt-in domain, without changing its solver or routing production instruments/player climate.

## Contract

Call `HeatWorld.queryLocalAir(x, y, z, maxAgeTicks)` on the server thread. Coordinates and returned velocity use world axes; velocity is blocks per second, temperature Celsius. Supply an explicit nonnegative maximum age in game ticks. Thermal response scale does not change that clock. There is no selected production freshness default.

| Status | Meaning | Sample |
| --- | --- | --- |
| `CURRENT` | Compatible published air frame, age at or below the caller's limit | Present |
| `STALE` | Compatible air frame older than the limit | Present, explicitly marked `AGE_LIMIT` |
| `PENDING` | Domain preparation or chronological revision refresh has no compatible publication yet | Empty |
| `UNAVAILABLE` | No request, closed request, geometry-only request, outside domain, occupied point, rejected preparation, solver/provider failure, or terminal lag limit | Empty |

`sample()` permits explicit inspection of a stale value. `currentSample()` returns a value only for `CURRENT`. Each sample carries its simulation-through tick and age. A missing reading is never a fabricated zero wind or outdoor temperature. Domain upper edges are exclusive. Invalid positions, negative ages, and sampling before an available frame's tick throw; off-thread queries throw.

Status describes the instant of the query; a retained result cannot track later edits, so consumers must query again when they need a new reading. A compatible initialized frame qualifies for sampling under the same rules as later frames; `CURRENT` is an age/revision statement, not a claim of thermal equilibrium or finished warm-up.

The query checks the existing revision guards before reading. It does not load chunks, capture block geometry, dispatch a worker, wait for a result, or create demand. Ordinary worker backlog can leave a compatible older frame available as `STALE`; source/weather/topology incompatibility withholds it entirely, even with an unlimited age allowance. It does not retain historical samples across invalidation. Results are immutable and contain no world, solver, geometry, or array references.

Reasons describe the requested point and current lifecycle, rather than the last measured point: closed first; then outside domain; then preparation failure/mode/rejection/run failure; then pending or measured air/occupied state. A stopped domain has been removed from `HeatWorld`, so its public reason is `NO_REQUEST`. Detailed operator diagnostics remain available through the existing status command. `HeatWorld.of` retains its existing Overworld-only scope.

## Verification and next gate

Added unit checks cover inclusive age boundaries, explicit stale access, occupied/outside points, source/weather refresh, invalidated in-flight work, cold preparation, close, geometry-only requests, rejected capture, provider/solver failure, terminal lag, and owner-thread/argument checks. The existing command GameTest also checks the public no-request, pending, outside, and stopped query behavior without advancing capture. Existing assertions remain intact.

Verification on 2026-10-26: IntelliJ build passed. The full `gradlew compileJava compileTestJava test runGameTestServer --continue --no-daemon --no-parallel --console=plain` check passed in 4m 7s (`build/air-query-full.log`): 396 aggregate, 346 coreless, 124 Empire, 17 Frontier, 98 Meteodyne, 3 lib, 2 Sable Fixes, 20 Seismic, 34 Spoilage, and 13 Sparse Ores both bare and with Create. All compile/unit tasks passed; the diagnostic unit suite now has 11 passing tests, including five added consumer-contract tests. No client visual/gameplay trial was performed for this API-only checkpoint.

The [opt-in view and held thermometer trial](local-air-view-trial.md) now exercise this API. Client visual/gameplay review is the next gate. Bright still needs to select gauge horizontal/total/weather-only semantics and unavailable/redstone behavior before those production behaviors change. Shared demand, general domain continuity, player climate, calibration review, and default activation remain later gates.
