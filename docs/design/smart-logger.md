# Smart Logger — implementation notes

## Spec (from Bright)
- Block, 2x2 footprint, brass-casing look. 0.5 m raised side bar. The front half has 2 buttons, Power and Reader GUI. The back half is the **PRD dock** (the Logbook and PRD buttons were removed; see `claude/prd.md`).
- Buttons animate like stone buttons. After the first round of feedback they are bronze, 4 px across × 3 px along the bar, 1 px tall, and sink 0.5 px when pressed.
- Mixer-style small cogs. Cog openings on all four sides, since the cogs mesh on every side.
- Torque is only needed to collect readings. Listen radius 12 → 48 blocks, scaled by RPM.
- Keeps every deposit heard via ≥3 nearby geophones; a newer reading replaces the same deposit; capacity 256; refuses new deposits when full.
- Top-face 2D map. **Controls (round 2, same as the PRD map; full detail in `claude/prd.md`):**
  - Right-click a dot to select or unselect it. Clicking the selected dot again sends it to a docked PRD.
  - Left-click drag pans (no block breaking on the screen while the display is on).
  - Scroll zooms about the crosshair.
  - Shift + right-click recentres on the logger.
  - The view (pan and zoom) is per logger and shared by all players; the selection is per player.
- The map glass looks the same whether the display is on or off (near-black). The map draws straight onto it.
- Markers are small (0.75 px) and coloured by **ore**, using the brass geophone's palette. Unknown ore is grey.
- Readings with no height are drawn fainter. Once zoomed in far enough, a faint square shows the error band.
- Goggles on the map show:
  - the map extent and zoom
  - where the crosshair points (X, Z)
  - the hovered deposit's details (or, if nothing is hovered, this player's selected one, with a hint line); the hovered dot is ringed in white and the selected one in amber
  - the docked PRD's count
- The GUI shows each reading's ore (a swatch plus its name), with a full-detail tooltip on row hover. The logbook rows also get the ore swatch. All three boards now share `ReadingBoardScreen` and `ReadingBoardMenu`.

## Defaults chosen (not yet confirmed by Bright)
- **Layout:** 2 wide × 2 deep × 1 tall. The placed block is front-left (the master); the desk extends right and away from the player.
- **Top:** screen on the left 1.5 m, bar on the right 0.5 m. Front to back: POWER, READER, then the dock (back half).
- **Stress:** 4 su/RPM total, all on the master.
- **Radius:** `12 + 36 × min(|rpm|, 256) / 256`. Must be turning both at shot time and when each reading arrives.
- **Display:** on = power-button latch OR redstone at any quarter.
- **Map:** world-aligned. Zoom 0 = 1024 blocks per metre of screen; each level halves it; max zoom 6.

## Data change
- `ReaderReading` has an `ore` field (saved as `ore`, optional; old saves load as unknown).
- `Recording.ore` comes from `Echoer.oreId`.
- `ReadingDeposits.identify` fills the ore in when it works out the deposit.

## Code map (package `geophone`)
- `LoggerListening`, `LoggerDisplay` (layout, zoom/drag/recentre maths, buttons, dock, `hovered()`), `ReadingOres` (ore name and colour).
- `ReaderRecorder.chooseAll`; `Logbook` with a `capacity` field.
- `SmartLoggerBlock`, `SmartLoggerBlockEntity` (plus dock, `setView`, `sendToDisplay`), `LoggerRegistry`, hook in `SeismicShots.deliver`.
- Map input: `LoggerMapInput`, `LoggerMapPayloads`, `client/SmartLoggerControls`.
- `SmartLoggerMenu` (extends `ReadingBoardMenu`), `client/SmartLoggerScreen` (extends `ReadingBoardScreen`, 280 px wide).
- `client/SmartLoggerRenderer` (plus `renderDock` and the selection ring), `client/SmartLoggerHover` (goggle lines and hover).
- `LoggerDataGen`, `LoggerTextures` (screen glass, bronze button).
- Tests: `SmartLoggerRulesTest` (unit), `gametest/SmartLoggerGameTests` (6 in-world tests), plus PRD dock tests in `gametest/PrdGameTests`.
