# Wind on physics objects: implementation notes

Status: built 2026-10-01. Compiles; all 575 unit tests and all 101 gametests pass (4 new in `WindGameTests`). **Not yet play-tested**, and the Project Atmosphere reader has only been checked against PA's bytecode, not in a running game (the gametest server has no PA).

Research and background: `claude/wind-physics-research.md`.

## Bright's decisions (2026-10-01)
- **Quadratic** force: light winds barely noticeable, strong winds a real problem.
- **Wind only pushes, never brakes**, and adds no air resistance of its own (Sable/Aeronautics handle that). The object's own speed never adds to the push: flying into the wind counts the same as hovering. Moving with the wind eases the push, down to nothing at wind speed.
- **Altitude:** surface wind near the ground, blending into PA's aloft wind higher up. The top of the blend is meant to match the cloud base once the cloud renderer settles it.
- **Sails don't catch wind** (no change to Sable's lift providers).
- **One push coefficient** for every block.
- **Shelter:** checked once a second; probes above (32 blocks) and to all four sides (16 blocks). Walls count more the more squarely they face into the wind; a downwind wall doesn't count. A roof alone cuts the push by a weight (0.5). Covered on all five sides (or on the upwind sides) = immune.
- **Gusts:** PA's own, with a 1-second ramp-up. No per-ship turbulence.
- **Every sub-level** is pushed (ships, boats, Offroad vehicles, loose physics blocks).
- **Interpolate** between PA's 2 km regions.
- **PA access by reflection** (no compile dependency on the git-ignored jar).
- Overworld only. No wind source (no PA, no override) = nothing happens.

## How it works
- **Hook:** `WindPhysics` listens to Sable's public `ForgeSablePrePhysicsTickEvent` (every physics substep, 2 per tick by default) and queues an impulse into the pack's own Sable force group `engines_and_empires:wind`, which Simulated's contraption diagram shows in its own colour (light blue).
- **Per game tick per object** (`WindShips.refresh`):
  - builds the outline the first time (scans the plot's bounding box), then keeps it current through `SubLevelSilhouetteMixin` (TAIL of Sable's `SubLevelPhysicsSystem.updateMassDataFromBlockChange`, which every sub-level block change goes through);
  - runs the shelter check every `shelterInterval` ticks (`ShelterProbe`), and reads the ground height there (heightmap at the centre of mass);
  - samples the wind at the world centre of mass, blends surface/aloft by height, ramps rises, reads Sable's air pressure, and recomputes exposure from the stored cover and the current wind direction.
- **Per substep:** one velocity read, then `force = coefficient × pressure × exposure × area × closing²`, applied at the outline's centre of pressure (so ships turn their long side away from the wind and tall masts heel). One substep can close at most half the gap to wind speed, so light objects never overshoot.
- **Outline (`Silhouette`):** per own axis, how many (u, v) columns hold a solid block; area across direction d ≈ `|dx|·Ax + |dy|·Ay + |dz|·Az`. Solidity is Sable's own test (`VoxelNeighborhoodState.isSolid`), so it matches the blocks that have mass. Hollow insides don't count.
- **Shelter (`ShelterProbe`, `Shelter`):** a grid of 2–5 probes per face edge (about one per 4 blocks) on the top and four sides of the object's world box, walking outward through world blocks. The object's own blocks live in Sable's plot, so they never block its probes. Unloaded chunks count as open. Exposure = `(1 − upwind-weighted side cover) × (1 − roofWeight × top cover)`.
- **PA reader (`PaWind`):**
  - surface: `ForecastOrchestrator.getWind(BlockPos, long)` (the wind PA stored this tick; what its own entity push uses), speed `max(baseSpeed, gustSpeed)`. PA's random gust bursts are already in `baseSpeed` (smoothed in over about half a second); `gustSpeed` adds half the forecast's gust headroom on top.
  - aloft: private `WindEngine.STATES` (plain `get`), `getCurrentHighSpeed` / `getCurrentHighDirectionDeg`; falls back to surface where a region has no state.
  - Never calls PA's `WindVectorApi` getters: they advance PA's wind model on every call.
  - Bilinear between the 4 nearest region centres (`n·size + size/2`); region winds cached per game tick.
  - If anything is missing (a PA update), logs a warning once and wind is off.
- **Directions:** Minecraft yaw toward which the wind travels (PA's convention): 0 south, 90 west, 180 north, 270 east.

## Config (`serverconfig/engines_and_empires-wind-server.toml`)
| Key | Default | Meaning |
|---|---|---|
| `push.enabled` | true | |
| `push.pushCoefficient` | 0.005 | **First guess.** Aimed at a mid-sized airship (≈400 mass, 150 m² broadside) drifting ≈1.5 m/s at 10 m/s wind and ≈7 m/s at 25 m/s, assuming Sable's universal drag (0.09) is linear damping. |
| `push.gustRampSeconds` | 1.0 | Rises reach 95% in this time; drops are immediate. |
| `altitude.surfaceLayer` | 24 | Blocks above the ground with surface wind only. |
| `altitude.aloftHeight` | 192 | y from which only aloft wind is felt (vanilla cloud height for now). The blend is never narrower than 16 blocks. |
| `shelter.interval` | 20 | Ticks between shelter checks. |
| `shelter.sideReach` / `roofReach` | 16 / 32 | |
| `shelter.roofWeight` | 0.5 | |

## Debug command
- `/eae wind`: the wind source, surface and aloft wind where you stand, and the nearest physics object within 64 blocks (outline areas, cover per face, felt wind, aloft share, exposure, air pressure, push).
- `/eae wind set <speed m/s> <yaw>` forces the same wind everywhere (not saved); `/eae wind clear` returns to PA.

## Code map (`weather/wind`)
- Pure, unit tested: `WindParams`, `WindForce`, `Silhouette`, `Shelter`, `WindBlend` (region corners, aloft share, gust ramp), `WindColumn` (incl. yaw helpers).
- Minecraft side: `WindConfig`, `PaWind`, `WindSources` (override → PA → none), `ShipWind`, `WindShips`, `ShelterProbe`, `WindPhysics`, `WindContent` (force group + listener), `WindCommand`.
- `mixin/SubLevelSilhouetteMixin`.
- Lang: `force_group.engines_and_empires.wind(.description)`.
- Build: `compileOnly dev.ryanhcode.sable-companion:sable-companion-common-1.21.1:1.6.0` (Sable carries it at runtime), group added to the ryanhcode maven filter, `sable_companion_version` in gradle.properties.
- Tests: `WindForceTest`, `SilhouetteTest`, `ShelterTest`, `WindBlendTest` (unit); `gametest/WindGameTests` (force group registered, a floating block drifts downwind, calm air leaves it alone, a sealed box is fully sheltered).

## Open items / to check in play
- **Run `/eae wind` in the `clientWeather` run:** it should say "Project Atmosphere" and show non-zero winds. This is the first real test of the reader.
- **Tune `pushCoefficient`.** The drift estimates rest on Sable's universal drag being linear damping (its native code wasn't inspected).
- **Aloft direction** is assumed to use the same convention as the surface wind (both stored in degrees by PA); check that aloft wind blows the way `/pa wind` says.
- **Which PA wind moves its clouds** (decides whether `aloftHeight` should track the cloud base exactly).
- Gametest rooms are walled with barriers, which count as cover; the push tests run above the room.
- Not handled: one sub-level sheltering another (a ship in a carrier's hangar; probes only see world blocks), and subcontraptions merged into a ship's mass don't add to its outline.
- Very large ships: the first outline build scans the whole plot bounding box once (a one-off cost when first seen).
