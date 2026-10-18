# Weather backbone, phase 8f: the heat simulation (rooms, air and walls) (2026-10-15)

Part of `claude/weather-backbone-phase8.md`; background in `claude/weather-backbone-phase8-research.md` section 9 and `claude/weather-backbone-phase8e.md` (sections 13-15: block temperatures for snow, ice and water). Status: **8f-1 (solver, section 15), 8f-2 (world side, section 16) and 8f-3 (the player, Cold Sweat's hearth family, block-entity sources, radiant heat; section 18) built and tested; from 8f-4, the in-world debug view and wind by the ships' shelter measure (section 17) (2026-10-15). 8f-5 (ships, section 20) built and tested. Next: 8f-4 tuning in play.**

## 1. Goal
Local heat and cold sources change what a **player** feels through the air and the walls around them, not a flat radius:
- **In the open:** only people close by feel it; the wind carries the rest away, downwind more than upwind, and more in strong wind.
- **In a room:** a source slowly warms (or cools) the whole space. How fast and how far depend on the room's size, its leaks, its walls and the outside temperature. A drafty hall never gets cosy; a sealed stone house does.

Scope (8e decision, section 13 there): 8f is for **player temperature only**, and **Overworld only**. Snow, ice and water keep using the block-temperature table (`HeatField`). Spoilage (ice chest, cellar) and greenhouses may read 8f's field later.

## 2. Decisions (Bright, 2026-10-15)
1. **Approach B:** a bounded grid around each group of sources, with the outside air as the boundary. No room detection: leaks come out continuously (a room with a window is still mostly a room).
2. **Budget:** runs on its own thread(s), not in step with game ticks; **default 0.25 CPU core** server side, configurable (like the cloud mesher's rebuild budget).
3. **When the budget runs short, the sim rate stays the same and quality gives way:** each step advances by however much time has really passed (variable time step), so a busy server takes fewer, bigger steps rather than falling behind (section 6).
4. **Noticeably warmer near the source** than in the rest of the room.
5. **Insulation per block**, as a datapack-tunable table (section 9).
6. **Thermal mass now:** walls hold heat (stone stays warm a while after the fire dies).
7. **Cold Sweat's hearth, boiler and icebox are replaced** by sources in our sim; their own air spreading and insulation are switched off.
8. **Timing:** close to a source a player feels it quickly; further away, later. **A 10x10x5 house reaches its warmth in about 5 real minutes.**
9. **State persists:** a room comes back after a restart or chunk reload exactly as it was.
10. **Sources:** section 9. **Not torches** (too many), **not players or mobs** (a region around every mob is too costly).
11. **Natural sources** (lava, magma, fire, soul fire and the like) only take part **while a player or a Create Aeronautics ship is within about 32 blocks** (loaded chunks only). The line is drawn **by block type** (a `natural` flag in the table), so a player-placed magma block counts as natural too.
12. **Natural-only regions start at their steady state** (a lava cave is already hot when you walk in) and **are not saved**; they are dropped when nobody is near and rebuilt when someone returns.
13. **Mixed regions:** natural sources count only while a player is near, **unless a placed source (a furnace, say) keeps the region alive**, in which case the natural sources inside its box keep counting (confirmed).
14. **Ice, glowstone and shroomlight warm or cool only by radiation** (line of sight), never through the grid: no regions for them, and no double counting of the cold that frozen biomes' climate already has.
15. **Soul fire is hot like fire, about 10% hotter.**
16. **The air has its own, lower limits** than the snow-and-ice table, as offsets above the outside air: **lava and magma +60 C, fires and campfires +45 C, furnaces, burners and engines +40 C** (`max_offset` in the table). Absolute limits (the icebox's 0 C) still apply too.
17. **Regions nobody is near step every 10 s** (players and ships near: every 1 s).
18. Calibrated feel accepted: a missing wall keeping some warmth in still air, and the room air cooling to half within about 40 s of the fire going out.
19. **Wind reaches each air cell as it reaches ships** (the ships' shelter check, not "open to the sky"), done cheaply over the region's block snapshot (section 17).
20. **Debug view:** `/eae heat show` toggles a live in-world view of every region within 128 blocks, drawn every frame while on (no limit on how much is drawn; it's for debugging).
21. **Hearth, boiler and icebox run by Cold Sweat's own controls** (8f-3): on exactly while Cold Sweat says it is using fuel (a redstone signal on a heating or cooling side, a smokestack, fuel), and Cold Sweat drains the fuel as before. Smart modes are forced off where we stand in (they decide fuel use by whom they insulate).
22. **Radiant heat from many sources is soft-capped** (8f-3): the strongest 32 in view are summed, warm ones eased toward the hottest contributor's air limit (+60 lava, +45 fire), cold ones toward -20 C.
23. **Until ships (8f-5), ships and other dimensions keep Cold Sweat's own way** (8f-3): hearths on ships work as before and Cold Sweat's `sublevel_blocks` term stays; other dimensions are untouched.
24. **The Create Aeronautics hot air burner is a source like a campfire** (power 1, radiant 0.5, +45; soul variant 1.1), scaled by its actual output: its hot-air setting x redstone signal / 15, over the server's most per burner.
25. **Guard against Sable's game-event hang** (2026-10-15): a mixin skips a ship's re-posted game event when its section range is broken (section 19).
26. **Ship rooms persist by fixing Sable's plot saving** (8f-5): a mixin writes plot chunks' attachments (and aux light) into each chunk's own tag, as Sable's loading expects; our chunk saving then works on ships unchanged (and other mods' ship data persists too).
27. **Hangars: a whole-ship check** (8f-5): once a second per ship, the world blocks around its world bounds are probed (the ships' shelter probe); if most of its top is covered, its open-sky cells act indoors, and its wind is cut by its world shelter.
28. **Ship regions step every 1 s while a player is within 16 blocks of the ship's world bounds, else every 10 s** (8f-5).
29. **Natural sources aboard a ship always count while it is loaded** (8f-5): a ship is its own anchor.

## 3. The model
### Grid
- Each **region** is a box of cells, one per block. Every cell holds a **temperature offset from the outside air** (C) and a kind: **air** (indoors), **exposed air** (open to the sky), **solid** (with conductance and heat capacity from the table), or **boundary** (the box's outermost layer and unloaded chunks), held at offset 0.
- **Exposed air** is air above the `MOTION_BLOCKING` heightmap (glass and leaves count as roof, which is right for air, unlike sky light). It is still simulated (so plumes exist) and loses heat steadily upward to the free atmosphere.
- Because the field is an offset from the outside air, a change in the weather (a front, night) moves the room's temperature with it at once, and the sim only carries what the sources added. The outside air is read (at the region's centre, once a second) only to turn absolute limits into offsets; the player's own reading (8f-3) is `Temperature.at` + offset.

### What moves heat
- **Mixing through air** (between neighbouring air cells), with an effective rate set by the timing target (section 7), not by real molecular diffusion (real rooms mix by convection).
- **Openings:** where indoor air meets open-sky air (a door, window or missing wall), air trades several times faster than it mixes indoors (stack effect and breezes).
- **Through and into walls**: between air and solid, and solid and solid, by the blocks' conductance (a series of half-cell resistances plus a surface film). Solids store heat by their heat capacity (decision 6).
- **Partial blocks** (slabs, stairs, open doors and trapdoors, fences, panes, campfires): air passes through the faces their collision shape doesn't fill; closed faces act as that block's material.
- **Warm air rises, cold air sinks:** between air cells one above the other, mixing is stronger when the lower is warmer and weaker when it is colder, and heat **drifts** upward through warm air and downward through cold air. A heated room is warmest under the ceiling and loses most through high openings; a cooled one is coldest at the floor and leaks low.
- **Wind** (decision 19): every air cell feels a share of the region's wind, 0 (sheltered) to 1 (open), by the ships' shelter measure for a one-block box (section 17). That share of the wind carries its heat off faster and brings in air from its upwind neighbour (implicit upwind, solved with everything else, stable at any step). A sealed room feels none; a porch open on its windward side feels most of it.

### Sources
- Each source puts **power** into the air cells touching it (into its own cell if that is air): heat per second, positive or negative. On/off by block state, or by its block entity (`driver`, 8f-3, section 18).
- **Limits:** a source acts as a **thermostat**, putting in no more than takes its cell to its limit: the stricter of its `max_offset` and its absolute limit (decision 16).
- **Radiant share:** part of a source's output is felt by line of sight instead; the grid gets the rest.

### Radiant heat (on top of the grid; 8f-3, section 18)
Direct radiation the wind does not touch: **line of sight** from the player to each source within its radiant reach, falling off with distance squared, blocked by solid blocks (the 8e-2 sun-ray block rule: glass and other light-passing blocks let a quarter through; lava stops it too). Computed on the main thread with the player's sample. Ice, glowstone and shroomlight are radiant only (decision 14). Many sources together are soft-capped (decision 22).

## 4. Regions
- **Placed** sources are indexed per loaded chunk (a scan when the chunk loads, then every block change); **natural** ones are looked for only in chunks near players and ships and in placed regions' boxes (kept 5 s per chunk, dropped on any block change there). Blocks with driven variants (8f-3) are indexed in any state and asked each plan whether they run.
- Sources whose margins touch (12 blocks each) share a region; its box is their bounding box plus 12 (`[heat] margin`), cut at its middle while longer than 48 blocks along an axis (`maxSize`), so regions never overlap (a cut is a seam where each side sees outside air: only very large groups, such as a lava lake).
- A region whose box is unchanged carries on; a new one starts from the fields of the regions it replaces, else from what was saved, else from 0 (natural-only: from its steady state). Regions of placed sources whose sources went out stay and cool until nothing above 0.2 C is left, then go (and their saved data with them).
- A region with chunks unloaded treats them as boundary; once its sources are gone and a chunk of it is unloaded, it is saved and dropped (it comes back when the chunks do).
- Ships (Sable plots): a region in the plot's own block space, outside air at the ship's world position, relative wind on its exposed cells (8f-5, section 20).

## 5. Threading and budget (decisions 2, 3 and 17)
- **Main thread, once a second:** plan (above); for each region, its blocks as cells and their shelter (only when blocks inside or within 16 blocks changed... in practice when blocks inside change), its sources (power, targets, limits), its wind (surface wind at the centre) and whether a player or ship is within 16 blocks of it.
- **Worker thread(s):** a budget fills by `budgetCores x 50 ms` each tick (up to one second's worth); while there is budget and room (2 jobs per thread), the most overdue regions are stepped, each by the game time since its last step (at most 120 s). Each job carries its own inputs; the main thread never touches a grid in use.
- **Results:** each finished step publishes the region's field, cell kinds and wind shares; readers use the latest and never wait.

## 6. Variable time step (decision 3)
- A step advances a region by the time since its last step: about 1 s near players, 10 s otherwise, more when behind. Source heat put in is power x elapsed time, so energy is exact however long the step.
- **Stability:** everything is solved **implicitly** (backward Euler) by red-black Gauss-Seidel sweeps; stable at any step length.
- **Sweeps** grow with the square root of the step (4 for 1 s, 13 for 10 s), so a 10 s step costs about three 1 s steps and loses little (section 15).
- **Cap:** a step longer than 10 s is split, so a room never jumps.

## 7. Timing (decision 8) and calibration
- Diffusion time grows with the square of distance, so near a source the air responds in seconds and the far side of a house in minutes; the radiant term adds instant warmth up close.
- **Calibration test (unit):** a 10x10x5 stone house (1-block walls, wooden door shut), one campfire in the middle, still air: the room average reaches **95% of its final offset in 5 minutes (+-1)**; 1 block from the fire, half the final felt warmth (radiant + air) within 15 s. Results in section 15.

## 8. Replacing Cold Sweat's hearth family (decisions 7, 21, 23; built in 8f-3, section 18)
Read in Cold Sweat 2.4.3 (`HearthBlockEntity`, which `BoilerBlockEntity` and `IceboxBlockEntity` extend):
- The hearth spreads "paths" through air by a capped search that stops at columns seeing the sky (`seeSkyMap`), up to `HEARTH_MAX_VOLUME` and range; every 20 ticks it gives insulation to living entities inside its area that can't see the sky (`insulateEntity`); `WorldHelper.getInsulationAt` answers the hearth level at a position.
- **Its controls (normal mode):** `checkInputSignal` sets `usingHotFuel = hasSmokestack && isHeatingOn && hotFuel > 0` (likewise cold); heating and cooling are redstone signals on its heating sides (hearth: east and south relative to its facing; boiler: any side) and cooling sides (hearth: west and down; icebox: sides and down). The hearth's top block is a smokestack; a boiler or icebox needs one placed on it. While using fuel it drains 1 every `getFuelDrainInterval()` (`tickDrainFuel`).
- **Smart mode:** fuel use is decided by `shouldInsulateEntity` (whether someone inside needs warmth or cooling) and cleared when it insulates no one (`clearFuelUsage`).
- **Block states are not enough:** `LIT` is "using hot fuel" (the boiler's also "warming waterskins"), but `FROSTED` only means "has cold fuel", so the old state-based icebox entry cooled forever without draining. Hence block-entity drivers.
- **As built:** in the Overworld outside ships, `tickPaths` and `insulateEntity` are cancelled and smart mode reads as off (mixins); the source is on while Cold Sweat's own `usingHotFuel` / `usingColdFuel` flags are set, and Cold Sweat drains the fuel. Cold Sweat's `blocks` modifier leaves the Overworld stack; `sublevel_blocks` (ships) stays until 8f-5.
- Hearth sides and smokestacks: heat or cold injected at the hearth; pipes ignored.

## 9. Sources and insulation (settled, Bright, 2026-10-15)
### Sources (`data/engines_and_empires/data_maps/block/heat_power.json`)
Power relative to a campfire = 1 (in the sim, power 1 is 150 units, `AirParams.powerUnit`; the grid gets the non-radiant share); radiant = share felt by line of sight; natural = only near players (decision 11); max = the most above the outside air (decision 16). "Driver" = on by its block entity (section 18).

| Source | Power | Radiant | On when | Natural | Max |
|---|---|---|---|---|---|
| Campfire | 1 | 0.5 | `lit` | no | +45 |
| Soul campfire | 1.1 | 0.5 | `lit` | no | +45 |
| Fire | 1.5 | 0.6 | always | yes | +45 |
| Soul fire | 1.65 | 0.6 | always | yes | +45 |
| Furnace, smoker | 0.5 | 0.2 | `lit` | no | +40 |
| Blast furnace | 0.8 | 0.2 | `lit` | no | +40 |
| Lava | 3 per face open to air | 0.7 | always | yes | +60 |
| Lava cauldron | 3 | 0.7 | always | no (placed) | +60 |
| Magma block | 0.5 | 0.4 | always | yes | +60 |
| Blaze burner: smouldering / fading / kindled / seething | 0.2 / 0.5 / 1 / 2 | 0.3 | by `blaze` | no | +40 |
| Diesel engine / huge cylinder / large (modular) part | 0.8 / 2.5 / 0.5 | 0.1 | driver `running` | no | +40 |
| Create Aeronautics hot air burner (`aeronautics:adjustable_burner`; soul variant `soulful`) | 1 (soul 1.1) x output share | 0.5 | driver `hot_air_burner` (setting x signal / 15 over `hotAirBurnerMaxHotAir`) | no | +45 |
| Portable engine, all 16 colours (tag `engines_and_empires:portable_engines`) | 0.6 | 0.1 | driver `running` (burning) | no | +40 |
| Cold Sweat hearth (`hearth_bottom`) | +2 heating / -2 cooling (both may run) | 0 | drivers `cold_sweat_heating` / `cold_sweat_cooling` | no | +40 / limit 0 C |
| Cold Sweat boiler | 2 | 0 | driver `cold_sweat_heating` | no | +40 |
| Cold Sweat icebox | -2 | 0 | driver `cold_sweat_cooling` | no | limit 0 C |
| Glowstone / shroomlight | 0.15 / 0.1 | radiant only | always | | |
| Ice / packed ice / blue ice | -0.2 / -0.4 / -0.8 | radiant only | always | | |

Torches, lanterns, redstone lamps, sea lanterns and other light blocks are not sources.

### Insulation (`data/engines_and_empires/data_maps/block/insulation.json`; relative to stone = 1)
Conductance: heat through one block (lower insulates better). Heat capacity: heat one block soaks up. Ranking follows reality; capacity is compressed (real air holds ~1000x less heat than stone, which would take a stone house hours to warm).

| Material | Conductance | Heat capacity |
|---|---|---|
| Wool, carpet (on its face), moss | 0.1 | 0.1 |
| Wood: logs, planks, wooden slabs, stairs, doors, trapdoors, fences | 0.25 | 0.5 |
| Hay, dried kelp | 0.2 | 0.3 |
| Snow block, snow layers, powder snow | 0.3 | 0.6 |
| Dirt (tag, mud included), sand, gravel, clay, packed mud, mud bricks | 0.6 | 0.8 |
| Bricks, terracotta | 0.7 | 0.8 |
| Stone, deepslate, cobble, ores, concrete (untagged) | 1.0 | 1.0 |
| Ice family | 1.2 | 1.0 |
| Glass blocks | 2.5 | 0.6 |
| Glass panes, iron bars | 3.0 | 0.2 |
| Metal blocks (iron, gold, copper storage tags; Create andesite, brass, copper and railway casings) | 4.0 | 1.3 |
| Water (any fluid but lava, waterlogged blocks too) | 1.5 | 2.0 |
| Leaves | 4.0 | 0.1 |

## 10. Persistence (decision 9)
- Each chunk (attachment `engines_and_empires:heat`, `HeatChunkData`) keeps the boxes of the saved regions that touch it and the offsets of its cells in them (only those 0.02 C or more from 0).
- Written every 30 s, when one of its chunks unloads and when the server stops. A new region starts from saved offsets; a saved box whose chunks are back but that no region covers (a room cooling after its fire went out) is brought back as a region of its own.
- Unloaded chunks don't tick, so the field pauses and resumes; no catching up. Natural-only regions are never saved.

## 11. Into the player's temperature (8f-3, built; section 18)
- Real players: outside air + **the room's offset** at the middle of the body (`HeatWorld.offsetAt`, 0.9 above the feet), then wind chill and heat index on that room air, the sun (8e-2), and the **radiant term** (`HeatRadiant`). All in `PersonClimate.Sample` (`roomC`, `radiantC`), cached half a second.
- Anything else in the Overworld (the dummy behind block-position queries such as thermometers, and mobs with temperatures): air + the room's offset at its block.
- `ApparentSync` carries the room and radiant terms to the client inside the existing once-a-second value.
- People on ships get neither yet (8f-5); Cold Sweat's `sublevel_blocks` still warms them there.

## 12. Open points
1. Drafts through windward openings indoors: later or never (partly covered now: indoor cells near openings feel some wind, section 17).
2. Large groups use seams (section 4); 2-block cells for very large regions not built (not needed so far).
3. Ships: done (section 20). Still simplified: a ship's air doesn't mix with the world's (a ship docked by a warm building gets nothing through the air, only radiant); a tilted ship's warm air still rises along its own up; world blocks shelter a ship only as a whole (decision 27).
4. ~~The Sable game-event hang~~: fixed by a guard mixin (section 19); full suite 190/190 (2026-10-15).
5. Shelter is recomputed when blocks inside the region change, not when blocks only in its 16-block halo do (a wall built just outside a region's box updates its shelter at the next change inside or when the region is rebuilt).
6. Data map entries for mods that may be absent (Diesel Generators, Cold Sweat) rely on NeoForge skipping unknown block ids; worth confirming on a server without them.
7. Diesel engines count as on or off (not by throttle); a superheated portable engine counts as an ordinary one. Tuning in play.

## 13. Sub-phases
- **8f-1 Solver (pure Java):** **done (section 15).**
- **8f-2 World side:** **done (section 16).**
- **8f-3 The player:** **done (section 18).**
- **8f-4 Debug view and tuning:** in-world view **done (section 17)**; in-game tuning ongoing.
- **8f-5 Ships:** **done (section 20).**

## 14. Code read for this (2026-10-15)
`weather/heat/HeatField`, `HeatMath`, `HeatSourceData`; `weather/climate/PersonClimate`, `ApparentSync`, `SunWarmth`, `SunLight`, `compat/ClimateTempModifier`, `compat/ColdSweatCompat`; `weather/wind/ShelterProbe`, `Shelter`, `WindParams`, `WindSources`, `WindColumn`; `weather/cloud/client/CloudMeshes`, `CloudOutlines`, `CloudRenderer` (budget and debug-drawing patterns); `weather/debug/WeatherDebugNetwork`, `WeatherMapPayloads` (payload pattern); `frontier/FrontierContent`, `FrontierEvents`, `FrontierChunkData` (attachment and chunk-event patterns), `mixin/LevelChunkFrontierMixin`; `weather/ships/ShipCover`, `ShipWeather`; Cold Sweat 2.4.3 `HearthBlockEntity` (tick, `checkInputSignal`, `checkForStateChange`, fuel), `BoilerBlockEntity`, `IceboxBlockEntity`, `HearthTopBlock` (a smokestack), `TempModifierInit` (modifier ids: `blocks`, `sublevel_blocks`, ...), `SublevelBlockTempModifier`, `WorldHelper.getInsulationAt`; Sable `GameEventDispatcherMixin`; Create Diesel Generators 1.3.15 `DieselEngineBlockEntity`, `HugeDieselEngineBlockEntity`, `PoweredEngineShaftBlockEntity`, `ModularDieselEngineBlockEntity`, `IEngine`; Simulated 1.3.1 `PortableEngineBlockEntity`.

## 15. 8f-1 as built (2026-10-15)

### Code (`weather/heat/air/`, pure Java, nothing of Minecraft)
- `AirGrid`: one region. Cells: `BOUNDARY` (held at 0, the box faces), `AIR` (indoor), `EXPOSED` (open sky), `SOLID` (conductance, capacity, open-face mask for partial blocks). `setAir` / `setSolid` / `setBoundary`, `setCells` / `setOffsets` / `exportOffsets` / `exportKinds` / `setWindShares` / `exportWindShares` (bulk, for the world side), `setWind`, `addSource(x, y, z, power, limit)`, `step(seconds)` (split at `maxStep`, sweeps by step length), `settle(seconds)` (steady start), `offset`, `offsetAt` (trilinear over air only), `meanAirOffset`, `maxMagnitude`, `setOffset`.
  - Face conductances: air-air `airMix`; indoor-to-open-sky air `airMix x openingMix`; with a solid on either side, film + half-cell resistances in series.
  - Vertical: mixing multiplied by `1 + convect x dT` (warmer below, up to `convectMax`) or `stable` (warmer above); a drift `rise x |mean offset|` (up to `riseMax`) carries heat up through warm air and down through cold. Coefficients fixed per step.
  - Open-sky cells lose `exposedLoss`; every air cell with a wind share `s` also loses `exposedWind x wind x s` and takes in air from upwind at `wind x s` (implicit upwind; air from beyond walls or the box comes in fresh). Without shares set, open-sky cells have 1 and indoor cells 0 (what the calibration used).
  - Sources: summed per cell; a limit makes the cell a thermostat inside the solve (exact at any step; the first version checked the limit only at the start of a step and overshot to -79 on a 10 s step).
- `AirParams`: the constants and `DEFAULT`; `gridPower(tablePower, radiantShare)`.
- `Radiant`: `warmth(params, power, share, distance, through)` (`radiantK x power x share x through / max(d, radiantMin)^2`), `reach` (distance where it drops to `radiantFloor`), `combine` (8f-3: the soft cap, section 18).
- `HeatClusters` (8f-2): groups sources into non-overlapping region boxes.

### Calibrated constants (`AirParams.DEFAULT`)
airMix 1.0, openingMix 4, film 0.15, wallK 0.035, solidCap 3.5, exposedLoss 0.8 /s, exposedWind 0.1 /s per m/s, convect 0.15 /C (max x4), stable 0.5, rise 0.01 /C (max 0.5), maxStep 10 s, sweeps 4, powerUnit 150 (a campfire puts 75 into the air), radiantK 12 (a campfire's radiant half: +6 C at 1 block), radiantMin 1, radiantFloor 0.25 C.

### What they give (campfire, still air, outside offset 0)
| Case | Result |
|---|---|
| 10x10x5 stone house, room average | +11.5 C; 95% of it at 296 s |
| 1 block from the fire, air / felt (with radiant) | +15.3 / +21.3 C; air at half in 14 s |
| Same house, floor / ceiling (by a wall) | +10.1 / +13.4 C |
| One window open / one wall missing | +10.8 / +6.9 C |
| 20x20x8 hall | +2.9 C |
| Fire out: room air at half / at a quarter | 40 s / 107 s (walls warm longer) |
| Open ground, 1 / 2 / 4 blocks (air only) | +3.2 / +0.9 / +0.1 C (plus radiant 6 / 1.5 / 0.4) |
| Open ground, 5 m/s wind, 1 block downwind / upwind | +4.2 / +0.7 C |
| 300 s in 10 s steps vs 1 s steps | 10.6 vs 10.8 (about 2% behind) |

### Cost
A house scene (9,216 cells): **0.45 ms per 1 s step, 1.4 ms per 10 s step** (desktop JVM, warmed up), so 0.25 core covers several hundred house-sized regions at one step a second. `settle` for a new natural region about 250 ms for the same box (on the air thread).

### Tests (all passing in the project, 2026-10-15)
- `AirGridTest` (16): the 5-minute house; felt within seconds and noticeably warmer by the fire; window leaks a little (above 85%), a missing wall much (below 65%), cooler toward the window; a 20x20x8 hall below 40%; walls hold heat after the fire goes out and it is nearly gone in ten minutes; 10 s steps within 10% of 1 s steps and one 5000 s call lands at the steady state; settling matches a long run; power scales the warmth; in the open only those close by feel it; wind carries it downwind; warm air rises and cold sinks; a source stops at its limit (stepping too); box faces stay at 0; an open slab face leaks about like a window; readings between cells use only air; sweeps grow with the step. (Still passing after the wind-share change.)
- `RadiantTest` (6): +6 C at 1 block and 1/d^2; glass a quarter, walls none; cold radiates; reach; grid power leaves out the radiant share; many sources ease toward the limit (8f-3).

## 16. 8f-2 as built (2026-10-15)

### Code (`weather/heat/world/`, plus small changes elsewhere)
- `HeatPowerData` (data map `engines_and_empires:heat_power`): variants with `state`, `power`, `radiant`, `natural`, `per_face`, `max_offset`, `limit` (and `driver`, 8f-3); `inAir()`.
- `InsulationData` (data map `engines_and_empires:insulation`): `conductance`, `capacity`; unlisted blocks are stone (1, 1).
- `HeatTables`: both data maps, cached lookups (cleared on data map reload), `offsetLimit` (the stricter of `max_offset` and the absolute limit, which falls back to the block's `heat_sources` limit), and `cell(state)`: air if no collision shape and no fluid (air, torches, flowers); any fluid but lava a closed solid of water; otherwise a solid of its material, open on the faces its collision shape doesn't fill.
- `HeatRegion`: box, grid, the main thread's prepared inputs (geometry with shelter, starting offsets, settle, sources, wind, near), `Published` (offsets, kinds and wind shares, `offsetAt` over air only, `maxMagnitude`).
- `HeatWorld` (per Overworld): the source index (chunk load scan; block changes via `LevelChunkFrontierMixin`, which now also calls `HeatWorld.onBlockChanged`), natural sources near players and ships (Sable sub-levels' bounds) and in placed boxes, the plan, the budgeted worker pool ("EAE Heat" threads), saving, `offsetAt(x, y, z)`, and test hooks (`setPaused`, `setExtraAnchors`, `setTestArea`, `runNow`, `forgetRegions`, `persistAll`, `clearSaved`).
- `HeatChunkData` (attachment `engines_and_empires:heat`), `HeatContent` (registers it and the data maps), `HeatSettings` + `[heat]` in the weather server config (`enabled`, `budgetCores` 0.25, `threads` 1, `nearStepSeconds` 1, `farStepSeconds` 10, `naturalReach` 32, `margin` 12, `maxSize` 48).
- `HeatCommand`: `/eae heat` (regions, placed sources, CPU used in the last second against the budget, the radiant warmth where you stand (8f-3), and where you stand: region, sources, last step, outside air, offset and air at head height), `/eae heat step <seconds>` (run every region now, for testing), `/eae heat show` (section 17).

### Tests (2026-10-15)
- Unit `HeatClustersTest` (4): margins, grouping, world height, cutting big groups into non-overlapping boxes. Passing.
- Game `HeatAirGameTests` (8, batch `heat_air_room`; each test fenced to its own cell with `setTestArea`; the live tick paused while they drive the regions): the tables (campfire, unlit campfire, lava, glowstone, torch, soul campfire, insulation, air limits, icebox floor); cells (stone, air, torch, bottom slab, water, wool); a sealed room (5x3x5 inside) with a campfire: corner +22.1, above the fire +26.9, outside the room +0.02 after 10 minutes, no wind inside, +0.55 ten minutes after the fire went out; a 3x3 hole in the roof to the sky: +2.8 against +22.4 sealed; lava in a stone cup: no region with nobody near, +37 C over it at once when someone is within 10 blocks (never past +60), dropped again when they leave; magma inside a campfire's box counts with nobody near (5 targets to 11); wind (section 17); saved: a warm room (+27.88) and a cooling one (+18.97) both come back exactly after their regions are forgotten and rebuilt. All still passing after 8f-3.
- **Full game test server:** 184/184 passing in the 8f-2 run; **190/190** after 8f-3 and the Sable guard (2026-10-15).
- **Placement-dependent hang (not 8f):** eating posts a game event, Sable's `GameEventDispatcherMixin.visitShipListeners` intersects a ship's plot box with the event box, and in some placements the result is an enormous box that vanilla's dispatcher walks section by section without end (thread dump 2026-10-15: `FoodGameTests.expectEating` -> `Player.eat` -> `GameEventDispatcher.post` inside Sable's wrapper).

## 17. 8f-4 so far: debug view and wind by shelter (2026-10-15)

### Wind by the ships' shelter measure (decision 19)
- **Shelter per air cell** (`HeatWorld.cover`, when a region's blocks are taken): the ships' check (`ShelterProbe` + `Shelter.exposure`) for a one-block box reduces to whether a solid block lies within the side reach (16, `WindParams.sideReach`) along each horizontal direction and within the roof reach (32) above. Done as sweeps over a snapshot of the region's blocks widened by 16 on each side (each row walked once each way, remembering the last solid block), and for the roof the column's highest block from the heightmap where nothing inside the box is above. Solid here: a block with a collision shape that isn't open all round (not air, fluids or fence-post-like shapes). The result is 5 bits per air cell (`HeatRegion.COVER_*`).
- **Each step** (worker): the bits and the region's wind direction give each cell's share of the wind by `Shelter.exposure` with the ships' roof weight (worked out for the 32 bit patterns once per step, then looked up), handed to the grid (`setWindShares`).
- **Cost:** the main thread reads (box + 16 each side) blocks when a region's blocks change: about 2-5x what it read before for a house-sized region, a few ms, only on change. The step cost doesn't change.
- **Test** (`theWindReachesAirAsItDoesShips`, wind forced east): in the lee of a stone wall 0, on its windward side 1.0, above the wall 1.0; inside the sealed room 0.

### In-world view (decision 20; `HeatView`, `client/HeatViewClient`)
- `/eae heat show` (ops) toggles it per player. While on, once a second the server sends every region within 128 blocks (from its box), one message per region (each region's air cells whose offset is 0.05 C or more from 0: position in the box, wind share 0-255, offset in 1/20 C) and then an end-of-frame message, so the client drops regions that are gone; off clears it.
- The client draws, every frame after translucent blocks: a cube 2 pixels across at the centre of each cell, coloured pale near 0, yellow +5, orange +15, red +30, magenta +45 and over, blue -5, deep blue -20 and under; and a white arrow along the region's wind, as long as the cell's share of it (0.35 blocks in full wind; none below a 5% share or in wind under 0.1 m/s). Not drawn through walls.

## 18. 8f-3 as built (2026-10-15)

### Sources driven by their block entity
- `HeatPowerData.Variant` has an optional `driver` (a resource id). A block with driven variants is indexed as a placed source in any state; each plan, for each driver the first variant that fits its state asks the driver for a share (0 off - 1 full) and counts at that share; different drivers count at once (a hearth may heat and cool at once, as Cold Sweat allows). `HeatTables.driven(state)`.
- `HeatDrivers`: `engines_and_empires:hot_air_burner` (a Create Aeronautics hot air burner's output share: `getGasOutput() / hotAirBurnerMaxHotAir`, 0 when not putting out gas); `engines_and_empires:running` (a portable engine burning: `getCurrentBurnTime() > 0`; a huge diesel cylinder with valid fuel whose shaft turns; a modular part whose controller generates; a normal diesel engine, or any other Create generator, generating speed); `engines_and_empires:cold_sweat_heating` / `cold_sweat_cooling` (registered by the Cold Sweat compat). Unknown drivers count as off (one warning). Each mod's classes are touched only when it is loaded.
- Portable engines are listed by a block tag, `engines_and_empires:portable_engines` (the 16 colours, optional entries).

### Cold Sweat's hearth family (`weather/climate/compat/ColdSweatHearths`, `mixin/compat/coldsweat/*`)
- Where: the Overworld, outside Sable sub-levels (`WorldHelper.isInSublevel`).
- `HearthReplacedMixin` (on `HearthBlockEntity`, so the boiler and icebox too): cancels `tickPaths` (no air spreading, both sides) and `insulateEntity` (returns false: no Warmth/Chill effect); `isSmartEnabled` returns false. `HearthKinSmartMixin`: the boiler's and icebox's own `isSmartEnabled` overrides too. All `@Pseudo`, so nothing happens without Cold Sweat.
- `HearthFuelAccessor`: reads the plain `usingHotFuel` / `usingColdFuel` flags (not the boiler's and icebox's waterskin-warming overrides). Those are the drivers; Cold Sweat itself drains fuel while they are set.
- `ColdSweatCompat.REPLACED` gains `cold_sweat:blocks` (Overworld stack). `cold_sweat:sublevel_blocks` stays.

### Radiant heat (`HeatRadiant`)
- Looks through the blocks within 10 of the body (sections skipped by palette when nothing in them radiates) for every table entry with a radiant share, natural ones included and driven ones while they run; each within its own reach (`Radiant.reach`), lava counted once (the face toward the person, not per face).
- Distance from the middle of the body to the nearest point of the block (so standing on magma counts as 1 block).
- The 32 strongest are traced: quarter-block steps from the body to the block's middle, skipping both ends; a block that stops light stops it, light-passing blocks let a quarter through (8e-2's sun rule), and lava stops it (a lake radiates from its surface only).
- Summed and soft-capped: `Radiant.combine(hot, hottestLimit, cold, 20)` = `L x tanh(sum / L)` each way, then added. One campfire feels as it would alone (6.0 -> 5.97); a lava lake's edge eases toward +60.

### The player (`PersonClimate`, `ClimateTempModifier`, `ApparentSync`)
- `PersonClimate.Sample` gains `roomC` (`HeatWorld.offsetAt` at the middle of the body) and `radiantC`; both 0 on ships (until 8f-5) or with `[heat] enabled = false`. `offsetC` works wind chill and heat index on outside air + room, then adds room, sun and radiant. The client gets all of it through the existing `ApparentSync` value.
- `ClimateTempModifier`: other entities (the thermometer dummy, mobs) get air + room offset at their block (server).

### Tests (2026-10-15)
- Unit `RadiantTest` (6, one new: the soft cap). Passing.
- Game `HeatPlayerGameTests` (6, batch `heat_air_room`, built 40 blocks above the cell and taken down after, so neighbours' fires and the lightning tests' "tallest thing" aren't disturbed): a campfire 1.5 blocks off radiates +2.66 C, a quarter (+0.67) through glass, none through stone; at a 7x7 lava lake's edge +57.0 (eased below +60); blue ice a block away -8.9; diesel and portable engines are driven, an unfuelled diesel engine is off; a hot air burner is one campfire (soul 1.1, counted once), off with no signal, a full share at full signal and the most hot air; a Cold Sweat hearth with fuel is no source until a redstone signal on its heating side, then it is (3 air targets) and the air beside it warms, and its smart mode reads as off. All passing.
- `ColdSweatGameTests.theOverworldGetsTheClimateInPlaceOfColdSweatsWorldTerms` updated: `cold_sweat:blocks` gone, `sublevel_blocks` kept. Passing (190/190).

## 19. Sable game-event guard (2026-10-15)
- **The bug:** Sable 2.0.5's `sculk_vibrations.GameEventDispatcherMixin` re-posts every game event once per ship near it, swapping the section range vanilla visits for the overlap of the ship's plot box and the event's box. For some ships that overlap comes out enormous or degenerate, and vanilla's dispatcher walks it section by section without end. Eating near such a ship froze the game test server (thread dump: `FoodGameTests.expectEating` -> `Player.eat` -> `GameEventDispatcher.post` in Sable's wrapper); it could freeze a real server the same way.
- **The guard:** `mixin/GameEventSectionGuardMixin` (on `GameEventDispatcher.post`, priority 1500 so it runs after Sable's injection at the same point, the `new ArrayList`): if the range spans more than 16 sections on any axis (vanilla's widest event reaches 3), it is emptied, so that ship is skipped for that event. Ordinary events are untouched.
- **Result:** full game test server 190/190, the food tests included.

## 20. 8f-5 as built: ships (2026-10-15)

### How ships' blocks are seen
Sable keeps a ship's blocks in its own plot chunks, in the Overworld level, far out at the plot grid (origin 10000 plots); the server chunk cache hands them out like any chunk, block changes in them go through `LevelChunk.setBlockState` (so our block-change hook indexes ship sources), and a ship's chunks loading from save post `ChunkEvent.Load`. So the regions machinery already ran on ship blocks in plot space; 8f-5 makes them behave as a ship.

### Regions on ships (`HeatWorld`)
- **Grouping:** sources are clustered by home: the world's together, each ship's on its own, and a ship's boxes cut to its plot (its whole reserved grid), so two ships, or a ship and the world, never share a region. Sources in a plot with no ship are dropped.
- **Per ship, once a plan** (`ShipAir`, public `shipAir(ship)` for tests): its plot content's centre in the world (outside air read there, so a ship's rooms feel the air where it flies, height included); the wind at that height (the surface/aloft blend people and ships use) less the ship's own velocity there, cut by its world shelter, turned into its own frame (only the horizontal part); whether a hangar roofs it (decision 27); whether a person (or test anchor) is within 16 blocks of its world bounds (decision 28).
- **Roofed:** its geometry is retaken with no open-sky cells; turning back when it leaves.
- **Natural sources aboard** (decision 29): every loaded ship's chunks are scanned for natural sources, which always count.
- **Gone ships:** plot chunks that no longer exist (removed or disassembled ships send no unload event) are dropped from the source index, natural cache and saved-chunk list each plan; their regions then go as unloaded ones do.
- **Saving:** as in the world (`HeatChunkData` on the plot chunks), now that Sable saves them (decision 26, `mixin/compat/sable/PlotChunkAttachmentSaveMixin`: `ServerLevelPlot.save` wrote `writeChunkAttachments` and `writeLightData` into the plot's tag instead of each chunk's, while `load` reads the chunk's). Plot positions are stable across reloads (fixed origin, saved plot coordinates).

### People aboard
- `HeatWorld.roomAt(point)`: a point in a plot reads that ship's rooms; a world point inside a ship's world bounds reads the ship's rooms there if a region holds it, else the world's. Used by `PersonClimate` (the middle of the body; a player Sable keeps in plot space reads the plot directly) and by `ClimateTempModifier` for other entities.
- `HeatRadiant.at(level, body, plotBody)`: the world's sources around the body (their lines also stopped by any ship's blocks they cross, `ShipCover.rayObstruction`, so a hull blocks the lava outside) plus, aboard, the ship's own sources around the body's place in its grid (traced in the grid); all eased together.
- `ShipCover.plotPoint`: the point in a ship's grid at a world point (fractional).
- Cold Sweat: `sublevel_blocks` leaves the Overworld stack too, and hearths, boilers and iceboxes on ships are replaced like any other in the Overworld.

### Debug view
- Ship regions are sent when the player is within 128 blocks of the ship's world bounds; the client draws them through the ship's render pose (cells and wind arrows, which come in the ship's frame).
- `/eae heat` also shows "Room here (body, ships included)".

### Tests (2026-10-15)
- Game `HeatShipGameTests` (3; each spawns its own ship 60 blocks up, plans only in its plot, removes it after):
  - a stone cabin (5x3x5 inside) on a ship with a campfire: region on the ship, corner +16.1 C after 10 minutes, +0.003 outside the cabin; the room saved in the ship chunk's own tag (not the plot's); 3 ticks later, a world point in the cabin reads +16.05 through the ship's pose, and a person there counts as near (own batch `heat_ship_cabin`, since it waits);
  - lava in a cup on a ship with nobody near: a natural-only region at once, +18.5 C over it;
  - wind 5 m/s east: 5.0 over the ship in the open; under a world roof (a hangar) roofed, 2.5, and the air over its cabin is indoors air.
- `ColdSweatGameTests` updated (`sublevel_blocks` gone too).
- **Full game test server: 193/193 passing.**
