<!-- Written by ChatGPT. -->
# Meteodyne weather instruments

First implementation: thermometer and mounted temperature sensor. Bright approved the thermometer-first plan in the weather instruments chat on 2026-10-18. The liquid column, freezing mark, fixed scale, room-air reading, configurable analogue output, and shared measurement path implement that plan. The initial art, crafting costs, and default calibration below are reviewable first-pass choices.

## Player behavior

- The **Thermometer** reads local air while in the hotbar or offhand, refreshing once a second. The liquid column covers **-30 to 50 °C** with a freezing notch, warm/cold bands, and a grey bar when no valid reading is available. No permanent numeric overlay. Reading-only updates do not trigger the held-item re-equip animation or interrupt mining; actual slot/item changes and other component changes retain their normal behavior.
- The **Temperature Sensor** places on a floor, wall, or ceiling. Right-click for the altitude-sensor-style vertical range GUI. Drag the left **0** and right **15** output handles to their temperatures; Ctrl-drag moves both together. Scrolling or arrow keys changes a selected endpoint by 1 °C, Shift by 0.1 °C, and Tab selects the other endpoint. Close the GUI to save, as with the reference altitude sensor. Default endpoints are **0 and 30 °C**. Endpoints can be reversed; each accepts tenths from **-100 to 200 °C**. Equal endpoints are rejected. Values beyond either endpoint saturate at its output limit.
- The visible thermometer scale remains fixed when redstone endpoints change. Only its display eases; redstone uses the latest authoritative reading. Unknown readings produce zero output in either polarity.
- Readings include climate, altitude, underground conditions, and the room heat simulation. The mounted sensor samples the air cell immediately in front of its face, avoiding the housing's solid cell in the heat grid. They exclude personal wind chill, clothing, sun, and radiant heat. Other dimensions and disabled Meteodyne weather report unavailable.
- The sensor emits weak power on all sides and downward strong power, following the reference altitude sensor's output convention. Support removal drops the mounted block. Configuration persists with the placed block, but breaking and replacing it starts at defaults.
- Initial recipes: thermometer = three iron ingots, glass, redstone; sensor = thermometer, two iron ingots, redstone. They unlock from iron and a thermometer respectively. These costs are provisional.

## Architecture

`meteodyne.instrument` owns content and behavior; its `client` subpackage owns drawing and the configuration screen. No new required outside mods.

- `InstrumentReadings`: common server sampler, reusing `MeteodyneTemperature` for outside air and room offset; transforms plot positions when checking interaction reach.
- `InstrumentReading`: pure dimension/time/value record; `InstrumentReadingCodecs` keeps Minecraft serialization separate. The held thermometer uses ordinary item-component/inventory synchronization, including for other players seeing the held stack. Readings expire after 60 ticks and are rejected in another dimension.
- `SensorRange`: pure proportional redstone mapping with inverted ranges and invalid-reading handling.
- `SensorRangeControls`: pure drag/scroll/keyboard calibration state; preserves the signed span on Ctrl-drag and clamps the entire range together at its limits.
- `TemperatureSensorBlockEntity`: periodic sampling, output changes, persistent calibration, block-entity synchronization, and independent client animation.
- `SensorMenu` / `InstrumentNetwork`: server-owned menu and validated configuration requests. A request can change only the current menu's sensor, while it remains within reach and the player may build.
- `SensorScreen`: vertical 42×206 range editor based on the reference altitude sensor's layout and interaction. When Simulated is installed, the gated `compat.simulated.client.SimulatedSensorScreen` draws its original GUI atlas directly (including any resource-pack replacement). Without it, a standalone rendition uses the same layout and controls. No third-party textures are copied into Meteodyne and no required dependency is added.
- `SensorShape`: common geometry for generated models, picking/collision, and animated face coordinates. The mounting plate uses the reference altitude sensor's 16×2×16 proportions. Wall/ceiling arrangements keep the thermometer upright. The first-pass housing uses original Meteodyne pixel art; it does not import another mod's textures.

Temperature is the first concrete instrument. Reuse the sampler boundary, measurement record, range mapping, network/menu pattern, and geometry conventions for later instruments; direction will need its own treatment.

## Attribution for formats without comments

The new instrument JSON and PNG assets under `meteodyne/src/generated/resources` are generated from `InstrumentDataGen`, `ThermometerTextures`, `WindGaugeTextures`, `InstrumentMetalTextures`, `SensorShape`, and `WindSensorShape`. The original base tile, wind artwork, and instrument generation were written by ChatGPT; the reference-sized temperature mounting panels were designed by Bright and procedurally recreated by ChatGPT. The wind housing panels extend those proportional layout rules. Their creator comments live in those source files because JSON and PNG cannot carry normal source comments. The new instrument entries in the existing `meteodyne/src/main/resources/assets/meteodyne/lang/en_us.json` were written by ChatGPT; pre-existing translations were preserved and their authorship is unchanged.

## Verification

Unit tests cover redstone calibration and reading freshness. Game tests cover item/component serialization, agreement between held and mounted readings, redstone dust propagation, configuration persistence and validation, unsupported dimensions, mounting shapes, and heated room air. An additional test, registered only with Sable present, checks the mounted thermometer at a ship's physical world altitude.

Automated/source checks do not establish in-game visual quality. Check held/hotbar readability, the mounted face in all twelve orientations, configuration controls, multiplayer display updates, and cabin readings in a real client before approving the art and interaction feel.

### First-pass verification results

- Meteodyne unit suite: **379 tests, zero failures, two existing benchmark skips**.
- Final standalone run: **74/74 game tests**. Final Meteodyne-only namespace in the coreless environment with Sable/Create present: **87/87**, including the ship sensor test.
- Full-project check compiled all projects. The full coreless run passed **188/188**; all bare game-test runs passed. The aggregate passed **238/239**, with the existing `theGameTestServerHasNoSeasons` fixture failing when Serene Seasons was loaded in the current local configuration. No instrument test failed.
- The full unit check found the existing Windows path-separator bug in mixin-inventory tests in the core, Paradyne Lib, Simple Spoilage, and Sable Fixes. Their sources and the user's build configuration were left unchanged. The corresponding Meteodyne test was corrected to normalize both separators, preserving its assertions.
- Repeated Meteodyne data generation wrote **zero changed assets**. The built jar includes the instrument classes, models and all 33 held reading frames, and excludes the instrument game-test classes.
- Build logs: `build/instrument-final-check.log` and `build/instrument-full-check.log`. Jar: `meteodyne/build/libs/meteodyne-0.1.0.jar`.

Try in a dev world with `/give @s meteodyne:thermometer` and `/give @s meteodyne:temperature_sensor`. The art and in-client interaction review remain open.

### Follow-up: held animation and altitude-style GUI (2026-10-19)

Bright confirmed that the first thermometer/sensor implementation works in game, but reported the held thermometer lowering once per reading update and requested the altitude sensor's GUI style. The reading component changed the synchronized stack, which the default NeoForge re-equip hook treats as a replacement. `ThermometerItem` now excludes only `instrument_reading` from re-equip/mining-reset comparisons, preserving slot, item, count and other component changes. A game-test regression checks these distinctions without changing the live stacks.

The original source and atlas were inspected from the locally cached Simulated 1.3.1 sources. The revised GUI uses two draggable temperature endpoints, an output-strength gradient, click feedback, a current-temperature marker, paired Ctrl-drag, and save-on-close. Additional scrolling/keyboard controls retain tenths precision across the full supported temperature range. The pure controller tests cover inversion, coincident endpoint rejection, precision, and paired clamping. In-game visual confirmation of these follow-up changes is still required.

Follow-up verification: **386 Meteodyne unit tests, zero failures, two existing benchmark skips; 75/75 standalone game tests; 88/88 Meteodyne game tests with ship integration; core ArchitectureTest passed.** The rebuilt jar includes the optional GUI compatibility class. Logs: `build/instrument-gui-check.log` and `build/instrument-gui-architecture.log`.

### Follow-up: proportional mount textures (2026-10-19)

Bright supplied seven reference PNGs showing how the metal should be shortened by removing middle material while retaining its borders and bolt size. `InstrumentMetalTextures` recreates those panels from palette and layout rules; production generation never reads the reference images. The originals are preserved as test fixtures, with exact bolt coordinates and attribution in `meteodyne/src/test/resources/instrument-mount-reference/README.md`.

Keep the one-pixel edge and highlight rim and the 2×2 bolts. Bolts normally start two pixels from each edge. When opposing bolts would touch, use one centered bolt; dimensions below six pixels omit bolts. Two-pixel strips retain only the edge color, and four-pixel strips retain the edge and rim. The compact back preserves Bright's core field and lower-left highlight on its right bolt. Future dimensions require an explicit panel entry rather than silently stretching an existing texture.

Each logical panel occupies the upper-left part of a padded 16×16 sprite. Generated face UVs crop to its dimensions and rotate portrait faces, retaining one texture pixel per model pixel across floor, wall and ceiling mounts. Padding and the uniform renderer sprite retain normal atlas mipmap dimensions. Geometry, picking regions and thermometer-face artwork are unchanged.

Verification: all seven generated logical panels match Bright's originals pixel for pixel; all 51 metal faces have proportional UVs. **390 Meteodyne unit tests, zero failures, two existing benchmark skips; 75/75 standalone game tests.** A repeated data run wrote zero changed assets. Logs: `build/instrument-metal-check.log`, `build/instrument-metal-repeat.log`, and `build/instrument-metal-uv-check.txt`. These checks establish source and pixel correctness; the updated mounts still need an in-game visual check.

### Wind gauge (2026-10-19)

Bright requested the next instrument and specified a **downwind arrow** and **spinning anemometer cups** as the speed indicator. The Wind Gauge (`meteodyne:wind_gauge`) combines a compass-style direction dial with a four-cup rotor, without numeric overlays. It reads while in the hotbar or offhand, once per second; client animation turns the arrow relative to the holder's heading and spins the cups faster in stronger winds. Direction uses 32 frames and the rotor uses eight frames per quarter-turn. Very high rotation rates are capped to avoid appearing stationary through animation aliasing. Measurement-only component updates retain the thermometer's no-bobbing/no-mining-reset behavior.

The Wind Sensor (`meteodyne:wind_sensor`) uses the same mounting plate and support materials, with a square direction dial, mast and actual rotating cup geometry. It mounts on floors, walls and ceilings; ceiling cups hang below the bracket. `WindSensorShape` supplies housing, inventory rotor and live rotor geometry and the picking clearance around its full rotation. All 69 metal housing faces retain one texel per model pixel. The static inventory block model includes the cups; the placed block renders them separately for animation.

Right-click opens the shared altitude-style GUI, including the optional original atlas when Simulated is installed. Redstone represents **speed in m/s**. Default 0/15 endpoints are **0 and 30 m/s**, adjustable in tenths between **0 and 100 m/s**, including inverted ranges. Direction changes at constant speed still synchronize. Calibration persists; transient wind measurements are not saved to disk. The arrow is hidden in calm air (below 0.05 m/s); unavailable readings show a grey dash and produce zero redstone. A valid calm reading remains distinct from unavailable weather.

Both forms use `InstrumentReadings.wind`, reading Meteodyne's wind source and the existing surface/aloft blend at their physical world altitude, including gusts. These are **local sheltered wind readings**: world blocks and ship blocks reduce exposure using the shared directional wall and roof rules; the player's or ship's velocity is not applied. Ship plots resolve to world positions; the mounted dial also derives its world heading from the ship's transformed facing. Other dimensions and disabled Meteodyne weather return unavailable readings.

`SensorBlock` and `SensorBlockEntity` now hold the mounting, server sampling lifecycle, calibration, output, persistence, menu and synchronization shared by temperature and wind. `SensorRangeControls` accepts per-instrument bounds while preserving its original temperature defaults. `WindReading` and its codecs synchronize both vector components with dimension and timestamp. No required outside dependencies were added. Initial recipes are provisional: gauge = compass, three iron nuggets and an iron ingot; sensor = gauge, two iron ingots and redstone.

Verification: **394 Meteodyne unit tests, zero failures, two existing benchmark skips; 79/79 standalone game tests; 93/93 Meteodyne game tests with ship integration; core ArchitectureTest passed.** Tests cover cardinal travel direction, wraparound, freshness, calm, bounded animation speed, shared held/mounted sampling, same-speed direction changes, calibration and redstone, item and network serialization, mounting shapes, no bobbing and physical ship altitude. A repeated data run wrote zero changed assets. The jar includes both instruments, all 256 directional/rotor combinations, calm/unavailable textures, models, renderer, recipes and loot; test classes and reference fixtures are excluded. Logs: `build/wind-gauge-check.log` and `build/wind-gauge-integration.log`.

Give commands: `/give @s meteodyne:wind_gauge` and `/give @s meteodyne:wind_sensor`. In-game review remains necessary for cup readability and speed, arrow behavior while turning, all mounting orientations, and moving/rotating ships. Source and automated checks do not establish visual quality.

### Follow-up: cover-aware instruments (2026-10-19)

Bright requested that gauges and sensors respect cover like heat and ships. The temperature instruments already sample local room air through the cover-aware heat simulation. The wind instruments now scale both vector components by local exposure before synchronizing the reading. Cups, direction, GUI speed and redstone all consume that same sheltered measurement. Full shelter is valid calm air, rather than unavailable weather; uncovering restores the wind at the next normal one-second sample.

The shared rules live in Paradyne Lib: `ShelterProbe` uses collidable blocks, and `Shelter.exposure` weights upwind faces and the configured roof reduction. `ShelterProbe.measurePoint` adds a point-sized query without changing the existing box probes. Meteodyne's `InstrumentShelter` combines those world probes with optional ship rays through `Ships`. Upwind walls stop wind, downwind walls leave it unchanged, diagonal wind receives proportional shelter, and a roof alone applies `roofWeight` (50% by default). No extra motion correction or new cover configuration was introduced.

Mounted sensors exclude exactly their own block in both world and ship queries. The ship ray API now accepts an optional exact plot-block exclusion; existing callers use the unchanged overload and behavior. Adjacent blocks, including another collidable housing, still count as cover. Held instruments exclude no block. Sampling runs on the server at the existing instrument cadence, and world probes do not load missing chunks.

Verification: **394 Meteodyne unit tests, zero failures, two existing benchmark skips; 15 library shelter/blend unit tests; 81/81 standalone Meteodyne game tests; 3/3 standalone library game tests; 96/96 Meteodyne game tests with ships; six core architecture checks passed.** Added regressions cover roofs, upwind/downwind walls, diagonal exposure, cover removal, glass cover on all twelve mounting orientations, valid calm readings, held/mounted agreement, ship cabins, ships overhead, and exact self-exclusion after ship movement. High test fixtures are isolated and removed so they do not become cover or lightning targets for other tests. Logs: `build/wind-cover-check.log` and `build/wind-cover-integration.log`.

Both rebuilt jars are required when testing outside the development environment: `meteodyne/build/libs/meteodyne-0.1.0.jar` and `paradyne-lib/build/libs/paradyne_lib-0.1.0.jar`. In-game confirmation of cover transitions and rotor motion remains separate from the automated checks.
