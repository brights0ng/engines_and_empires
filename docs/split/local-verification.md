# Local verification checklist (split step 3, the part only Bright's computer can do)

This is for Bright to run by hand with a real graphics card and a real player. The cloud session already did what a machine without a screen can do (see section 0), so none of that is repeated here. Every line says what to do, what you should see, and what counts as a failure. Tick the box when it passes; write down anything odd, with the log line, even if you are not sure it is a failure.

**Do not use the Modrinth profile.** Make a copy of any instance (the pack, a dev instance, a plain NeoForge 1.21.1 instance) and work in the copy. Use brand-new worlds: there is no save compatibility with pre-split worlds.

## 0. Before you start

### 0.1 What the cloud already checked (so you can skip it)
- A clean clone of `split` built with `./gradlew build buildAll`, and the full check (`compileJava compileTestJava test runGameTestServer --rerun-tasks`) passed: 1048 unit tests (2 skipped, the same two as before), game tests 214 in the aggregate run and every mod's bare run green.
- Each of the eight jars was opened and its `neoforge.mods.toml`, mixin config and contents checked.
- A dedicated server booted and shut down cleanly from the jars, once per mod with only its required mods, once with everything, once with everything plus Serene Seasons.
- A dev client booted under a virtual screen with software graphics, joined a world and ran for about a minute, with all mods and again with Meteodyne + Iris + Sodium. In that picture the voxel clouds drew.
- **Not checked there (so it is on this list):** what anything looks like or feels like, shader packs, sound, the PRD model, the map screens, every config screen, a long climatology run, and the real pack.

### 0.2 The jars to put in the copy
Build them (`./gradlew build buildAll` in the repo) or take them from the PR's build; they land in `build/allJars/`:

| Jar | Mod id | Version |
|---|---|---|
| `paradyne_lib-0.1.0.jar` | `paradyne_lib` | 0.1.0 |
| `sable_fixes-0.1.0.jar` | `sable_fixes` | 0.1.0 |
| `sparse_ores-0.1.0.jar` | `sparse_ores` | 0.1.0 |
| `seismic_surveyance-0.1.0.jar` | `seismic_surveyance` | 0.1.0 |
| `meteodyne-0.1.0.jar` | `meteodyne` | 0.1.0 |
| `frontier-0.1.0.jar` | `frontier` | 0.1.0 |
| `simple_spoilage-0.1.0.jar` | `simple_spoilage` | 0.1.0 |
| `engines_and_empires-0.4.0.jar` | `engines_and_empires` (core) | 0.4.0 |

**Take the old single `engines_and_empires` jar out of the copy first.** It has the same mod id as the new core, so having both is a duplicate-mod error that has nothing to do with the split.

Outside mods for the full set (the versions the repo builds against):
- Needed by the core: Create 6.0.10, Sable 2.0.5, Create Simulated, Create Aeronautics and Create Offroad 1.3.1, Encumbered 1.21.1-1.0.0, Deeper and Darker 1.4.1 (Frontier needs it too).
- Optional, add them for the full test: Cold Sweat 2.4.3, MineColonies (and its libraries), AppleSkin 3.0.9, Diet - AppleSeed Edition 3.0.2 (with CloudWorks API), Guard Villagers 2.4.12, Create Diesel Generators 1.3.15.
- Only for the sections that name them: Iris 1.8.14-beta.1 with Sodium 0.8.12 (section 3), Serene Seasons 10.1.0.9 with GlitchCore (section 7).

### 0.3 How to read the log
Every step below can be checked in `logs/latest.log` of the copy. Search it for `ERROR` and `WARN` after each step. Noise that is **not ours** and is not a failure (all seen in the cloud runs): `CloudWorks-RecipeParser` lines (a template that fails to parse, "No template for type=..."), `createdieselgenerators:blocks/andesite_girder_strut` "Unknown registry key", "Ambiguity between arguments [teleport, ...]", "Reference map '...refmap.json' ... could not be read" for other people's mods, and the sound-device error on a machine with no audio. Anything that names one of our mods, our packages (`dev.brights0ng...`) or one of our mixin configs is a failure to report.

Commands below need cheats on (permission level 2). In a world: `/gamemode creative`, `/time set day`.

---

## 1. Boot with all jars

| # | Do | Look for | Failure if |
|---|---|---|---|
| 1.1 | Start the copy with all eight jars plus the needed outside mods. | The game reaches the main menu. | A red "Mod loading has failed" screen, a crash report, or the game exits. |
| 1.2 | Open **Mods**. | Eight entries of ours, with the versions in 0.2, each showing licence "All Rights Reserved". Frontier, Meteodyne, Paradyne Lib, Simple Spoilage and the core show their descriptions. | An entry is missing, a version is wrong, a name shows as a raw key, or you see two copies of one mod. |
| 1.3 | Create a new world (creative, cheats on) and join it. | You spawn normally. No error toast. | Disconnect, freeze, or an error screen on joining. |
| 1.4 | Open `logs/latest.log`, search `ERROR`. | Only the outside noise listed in 0.3. | Any error naming our mods, "Mixin apply failed", "Mixin transformation ... failed", "InjectionError", or a `missing translation`/raw `item.xxx`-style name in the creative tab. |
| 1.5 | Open the creative tab **Seismic Surveyance** and the Search tab; type `rich`, `chip`, `ice chest`. | Our items and blocks appear with names and pictures: Sledgehammer, Andesite and Brass Geophone, Wind-up Reader, Logbook, Portable Record Display, Smart Logger, Strike Plate, thumpers; Rich ore blocks and the chips; Ice Chest. | A purple-and-black checkerboard model, an empty icon, or a raw key as a name. (The thumpers, the Smart Logger and the PRD borrow Create's textures, so a checkerboard on them is worth special notice.) |
| 1.6 | Quit and look at `config/` (and `saves/<world>/serverconfig/`). | Files named `paradyne_lib-wind-server.toml`, `meteodyne-weather-server.toml`, `meteodyne-clouds-client.toml`, `frontier-server.toml`, `simple_spoilage-server.toml`. | A file with the old name (`engines_and_empires-*.toml`) or a missing one. |
| 1.7 | Quit, start again, rejoin the same world. | Same world loads with no new errors. | Data lost, errors that only appear on the second load. |

---

## 2. Config screens

Open **Mods**, pick the mod, press **Config**.

| # | Mod | Expect | Failure if |
|---|---|---|---|
| 2.1 | Paradyne Lib | A screen titled "Paradyne Lib" with the wind settings. | No Config button, a raw key as the title, or a crash. |
| 2.2 | Meteodyne | A screen titled "Meteodyne" with the weather settings and the cloud settings. | As above. Changing a cloud setting does not change the clouds without a restart (the client file is watched and should apply live). |
| 2.3 | Frontier | A screen titled "Frontier". | As above. |
| 2.4 | Simple Spoilage | A screen titled "Simple Spoilage". | As above. |
| 2.5 | Engines and Empires, Sparse Ores, Seismic Surveyance, Sable Fixes | **No** Config button. This is expected: these four have no settings and the core's empty screen was deliberately removed. | A Config button that opens a blank or crashing screen. (A leftover lang key `engines_and_empires.configuration.title` exists in the core's language file; it is harmless and not a failure.) |
| 2.6 | Change one value in each of 2.1-2.4, press Done, reopen. | The value is kept, and the matching `.toml` file in `config/` changed. | The value reverts, or the file does not change. |

---

## 3. Clouds and shaders under Iris (Meteodyne)

Add Iris 1.8.14-beta.1 and Sodium 0.8.12 to the copy. Have at least one shader pack (the pack's usual one, if it has one) in `shaderpacks/`. Start a world in the Overworld, daytime, clear sky first.

| # | Do | Look for | Failure if |
|---|---|---|---|
| 3.1 | Look at the sky with **no** shader pack selected. | Voxel (blocky-soft) clouds drifting, with shapes that differ and grow or fade over minutes. | Vanilla flat clouds only, no clouds at all, or clouds that flicker or tear. |
| 3.2 | Run `/meteodyne clouds info`. | One line starting "Clouds: N clusters in M formations ..." that also says whether the cloud renderer is on; it should be **on**. | The renderer is **off** without shaders, or the command is unknown. |
| 3.3 | Run `/meteodyne clouds outlines` twice. | Outlines appear around the cloud shapes, then go away; the chat message says on/off. | No change, or a crash. |
| 3.4 | Fly or walk around, turn fast, look straight up and down. | Clouds stay put in the world and keep the same shape as you move. They are hidden behind hills and solid blocks, and appear in front of the far sky. | Clouds draw in front of nearby terrain, or sit in the wrong place, or pop in and out as you turn. |
| 3.5 | Open **Options > Video Settings > Shader Packs** (Iris) and select a pack. | The pack loads. **The clouds are still drawn** (Meteodyne's Iris support keeps them from being hidden), and `/meteodyne clouds info` still says the renderer is on. | Clouds vanish under the shader pack, the info line says off, or the game crashes or goes black on enabling. |
| 3.6 | Switch the shader pack off, then on, then change dimension (Nether and back). | Clouds come back each time, with no crash. | Clouds do not return, or errors in the log naming `meteodyne` or Iris. |
| 3.7 | Make weather: `/meteodyne weather clouds spawn cumulonimbus_calvus` (the command suggests the other cloud types), then `/meteodyne weather precip rain`, then `/meteodyne weather lightning`. | A tall cloud forms nearby, rain falls from clouds that are over you, lightning is visible. Under a shader pack the same. | Rain with no cloud above, rain falling indoors or under a roof, or no visible effect. |
| 3.8 | Run `/meteodyne weather` and `/meteodyne weather season`. | A multi-line report (temperature here, humidity, wind, pressure, cloud wants) and a season line. | An error, or `NaN` showing in the numbers. |

---

## 4. The PRD model, the smart logger and the maps (tier overlay)

Needs Seismic Surveyance, Sparse Ores, Frontier **and the core** (the core is what draws the tier overlay). Create is required. Make it quick: in a fresh world run `/sparseores deposits coal` (the command lists the nearest deposits with coordinates and depth; you can name any ore) and travel to one. Take from the **Seismic Surveyance** creative tab: a Sledgehammer, two or three Andesite or Brass Geophones, a Strike Plate, a Wind-up Reader, a Logbook, a Portable Record Display and a Smart Logger.

### 4.1 Taking a reading
| # | Do | Look for | Failure if |
|---|---|---|---|
| 4.1.1 | Put geophones around a Strike Plate within range of a deposit. Hit the plate with the Sledgehammer and use the Wind-up Reader and Logbook as their tooltips say. | The reader winds ("Wound: n%"), then "Fully wound"; a reading appears in the Logbook with an ore name and a colour swatch. | Nothing is recorded, the reader never winds, or errors in the log. |
| 4.1.2 | Hover the logbook/reader; read the tooltips. | "n / m readings", "Fix: Precise/Approximate/Rough/Unknown". | Raw keys. |

### 4.2 The Portable Record Display (PRD)
| # | Do | Look for | Failure if |
|---|---|---|---|
| 4.2.1 | Look at the PRD in the creative tab, in the hotbar, in your hand and in third person. | A handheld device with a train-casing (railway casing) body, brass trim, a dark-oak grip, an antenna and three lamps, with real textures. | Purple-and-black checkerboard on any part, a missing part, a floating or mis-posed arm. |
| 4.2.2 | Right-click it. | It rises in both hands, centred. The mouse moves freely over it. The glass has **MAP** and **RECORDS** tabs. Hold **Tab** to look around. | It does not raise, the cursor is stuck, the glass is blank, or Tab does nothing. The key is listed under the category **Seismic Surveyance** in Controls ("PRD look"). |
| 4.2.3 | Click the **RECORDS** tab and the **MAP** tab; walk and turn while it is up. | Records list on the glass; the map turns with you and the arrow always points up; a north marker sits at the edge. | The map does not turn, the arrow spins, rows missing. |
| 4.2.4 | Press the side buttons. | **DELETE** needs two presses within 3 seconds; **RENAME** lets you type and the name shows on the glass; **SAVE / LOAD / CLEAR / eject** show "NO DISK" (the floppy system does not exist yet). | A button does nothing visible, or crashes. |
| 4.2.5 | Put it down with Esc, the inventory key, and right-clicking off the device (try each). | It lowers each time. The inventory key does **not** open the inventory. Changing hotbar slot does not put it down. | It stays stuck up, or you lose control of the camera or mouse. |
| 4.2.6 | Watch the lamps while saving and dock-uploading. | Green steady while it is being worked; green long pulse = saved or updated; yellow three flashes = already has that reading; red three flashes = full (16). | Lamps stay dark, wrong colours. |

### 4.3 The Smart Logger
| # | Do | Look for | Failure if |
|---|---|---|---|
| 4.3.1 | Place a Smart Logger (2 wide, 2 deep, one tall). Power it with a Create motor or a creative motor. | A brass-casing block with a raised bar on one side, two bronze buttons that sink when pressed, small cogs open on every side. | Wrong shape, missing textures, no torque connection. |
| 4.3.2 | Turn the display on (Power button, or a redstone signal), then take readings nearby. | The screen shows a map with small coloured dots, one colour per ore (the geophone colours). Readings arrive only while it is turning. | No dots, all grey, or readings arrive with no rotation. |
| 4.3.3 | Drive the map: left-drag to pan, scroll to zoom, right-click a dot to select (right-click again sends it to a docked PRD), Shift + right-click to recentre. | Each works as in `docs/design/smart-logger.md`. Goggles/hover show coordinates and the dot's details. | Dragging breaks blocks, zoom does nothing, selection does not show. |
| 4.3.4 | Right-click the logger holding the PRD (dock), then with an empty hand. | The PRD goes in and its readings are copied to the logger; an empty hand takes it out. Breaking the logger drops it. | The PRD vanishes or readings are lost. |

### 4.4 The tier overlay on both maps
| # | Do | Look for | Failure if |
|---|---|---|---|
| 4.4.1 | Stand near a village (Settled land), or run `/frontier habitation add 36000` next to your bed and a workstation, then `/frontier why`. Open the PRD map and the Smart Logger map. | A very faint fill in the tier colour (yellow Uninhabited, green Settled, blue Civilized) under the dots, an outline where land meets a lower tier, a small legend reading **Uninhabited / Settled / Civilized / Incursion**. Frontier-tier land is left clear. | No overlay and no legend with all eight jars installed, raw keys such as `gui.frontier.tier_map.settled` in the legend, or a crash on opening a map. |
| 4.4.2 | Compare with `/frontier map` (it draws coloured particle outlines around the chunks near you: red Frontier, yellow Uninhabited, green Settled, blue Civilized). | The tiers agree with the overlay (the overlay leaves Frontier clear). | They disagree. |
| 4.4.3 | During an incursion (section 5) look at the map. | A bright violet diamond marks it. | No marker. |
| 4.4.4 | Take the core jar out and rejoin. | The maps still work, with **no** overlay and no legend. | A crash. (This proves Seismic does not need Frontier or the core.) |

---

## 5. Thumper, then the Deep, then an incursion

This crosses Seismic Surveyance, Sparse Ores, Frontier (through its Seismic package), Deeper and Darker and the core. It is the main integration the split could have broken. Set the world to normal difficulty, cheats on.

| # | Do | Look for | Failure if |
|---|---|---|---|
| 5.1 | Place a Mechanical Thumper over bare ground near a deposit, give it Create rotation (a creative motor will do), and let it fire. | It slams and a survey reading goes out like a hammer blow, with a wider range. | It does not move, errors in the log, textures missing (Create's textures are borrowed). |
| 5.2 | Run `/frontier deep`. | "The Deep around you:" with lines for Disturbance (n of 5 shots in the last 60 s), sculk listeners in range, stirred deposits within 1024 blocks, and incursions called up. After the shot the Disturbance count is 1. | The command is missing, or the shot count stays 0 after a real shot. |
| 5.3 | If you do not want to build one: run `/frontier deep shot mechanical`, then `combustive`, then `dry`. | Each prints "A ... shot here: n sculk listeners and Wardens will hear it". | An error, or nothing printed. |
| 5.4 | Shoot repeatedly over a deposit (about 10 to 20 real shots; a real shot has a 15% chance for a mechanical and 30% for a combustive one of bringing a pack up 16-32 blocks away). | At some point a pack of Deeper and Darker sculk creatures rises through the ground, and `/frontier deep` lists stirred deposits. | Dozens of shots and never a pack, or an error when they spawn. |
| 5.5 | Thump near a sculk sensor or shrieker, or near the Deep Dark. | Sensors and shriekers react a moment after each shot (the wave reaches them); a Warden can be summoned near the Deep Dark after enough noise. | Sensors never react. |
| 5.6 | Stand in Settled land (see 4.4.1). Fire a Mechanical Thumper five times within a minute. | A **lesser incursion** starts: a boss bar titled "Incursion - Wave 1/N", sculk mobs at the edge of the settlement, passive mobs being killed. `/frontier incursion status` lists it and `/frontier deep` mentions "incursion #n". | No incursion after five shots in a minute within the 3x3 chunks, or the boss bar shows a raw key. |
| 5.7 | In Civilized land (five free-roaming guards nearby, or an iron golem group), five Combustive Thumper shots in a minute. | A chance to start a **Greater Incursion** (boss bar "Greater Incursion"). Run it several times: it should happen sometimes, not always. | Never, or always. |
| 5.8 | While an incursion runs, look at the thumpers. | Thumpers the incursion goes after turn into **Choked** thumper blocks (dead weight with no working machinery; a choked Combustive Thumper loses the column above its base). | Thumpers near an incursion keep working, or a Choked thumper shows a missing texture. |
| 5.9 | Dev shortcuts to repeat it: `/frontier incursion start lesser` or `greater` (fails with "You are not in a settlement" outside Settled land), `/frontier incursion odds`, `thumper`, `roll`, `next`, `stop`, `immunity clear`. Try a Squelching Heart (`/give @s frontier:squelching_heart`) at night. | Each prints something sensible; the heart says the night holds still, or "It only stirs at night." in the day. | Errors in the log, `NaN`s, or `stop` leaving mobs behind. |
| 5.10 | `/frontier tier`, `/frontier why`, `/frontier torches age 24000` near placed torches in Frontier land, then right-click one. | Tier names appear; torches turn into Dry Torches; right-clicking relights. | Torches never burn out, or a Dry Torch cannot be relit. |

---

## 6. One climatology run (Meteodyne)

A real long run is what the cloud cannot do. **Do this in singleplayer in a throwaway world.** (On a dedicated server the run starts by building the weather field on the server thread; in the cloud that took about two minutes, which killed a server with the default `max-tick-time` of 60000 through the watchdog. The same code runs before the split. On a server set `max-tick-time=-1` first; with that, a 2-day, 2-spot run finished in under a second after the start-up.)

| # | Do | Look for | Failure if |
|---|---|---|---|
| 6.1 | `/meteodyne weather climatology add`, walk to a different biome (a desert or snowy plain), `add` again. `spots` lists both. | "Added climatology spot x, z (biome); n spot(s)." | An error, or the wrong biome. |
| 6.2 | `/meteodyne weather climatology run 72` (72 days = one year of the built-in calendar; the argument goes up to 720). Let it run. | "Running the weather 72 in-game days ahead over 2 spot(s)", then progress messages "Climatology run n% done", then "Climatology run done:" followed by a report and a line "Daily CSV: .../logs/climatology-<time>.csv". | No progress, it stalls, the game freezes for minutes, an error naming `meteodyne`, or `NaN` in the report. |
| 6.3 | Open the CSV and the trace file beside it. | One row per day per spot; temperature rises and falls over the year (cold at the start, warm in the middle, cold again); humidity and rain are in sensible ranges. | A flat line, impossible values (hundreds of degrees), or empty cells. |
| 6.4 | `/meteodyne weather climatology report` and `csv` afterward; `clear` the spots. | The same statistics; `clear` empties the list. | The report is gone or errors. |
| 6.5 | Optional, for a ten-year run from a checkout: `./gradlew runServer -Pmeteodyne.world=<folder under run/> -Pmeteodyne.climatologyAuto="x,z;x,z" -Pmeteodyne.climatologyDays=720` (the property names are now `meteodyne.*`, not `eae.*`). | The server runs to the end and stops by itself. | It never stops, or the old `eae.` properties are still needed. |

---

## 7. Serene Seasons running with the Serene provider

Add Serene Seasons 10.1.0.9 and GlitchCore to the copy, with everything else.

| # | Do | Look for | Failure if |
|---|---|---|---|
| 7.1 | In a world, run `/meteodyne weather season`. | A line starting "Season:" and naming a Serene Seasons sub-season in capitals, for example `Season: EARLY_SPRING (0% through the year, ...)`. (In the cloud server with Serene Seasons this printed exactly that.) | It says "built-in calendar: day n of 72" while Serene Seasons is installed. That means the core did not register Serene Seasons as the season provider. |
| 7.2 | Take Serene Seasons out and run the same command. | "Season: built-in calendar: day 1 of 72 (0% through the year ...)". | A crash, `NaN`, or the temperature never changing through the year. |
| 7.3 | Put Serene Seasons back. Advance time with `/time add 10d` a few times, run `/meteodyne weather season` each time, and check the weather report (`/meteodyne weather`) "Season ... C" figure. | The season name changes as days pass; the "Season" temperature term moves with it (colder in winter). | The name never changes, or the temperature does not follow. |
| 7.4 | Look at the Serene Seasons calendar item and the leaf and grass colours. | They work as Serene Seasons does on its own. | The calendar shows nothing, or colours never change. |

---

## 8. Food and spoilage (`/spoilage`)

Needs Simple Spoilage (and Paradyne Lib). AppleSkin and Diet are optional here.

| # | Do | Look for | Failure if |
|---|---|---|---|
| 8.1 | `/give @s bread 16`, hold it, run `/spoilage inspect`. | Lines like `top: 16 fresh, substage 1/10 (step 0/30), 0.00 days old, born at tick ...`. The item tooltip shows "Fresh x16". | "Hold some food that spoils." with bread in your hand, or an unknown command. |
| 8.2 | `/spoilage age 144000` (one stage is 6 days = 144000 ticks). Inspect again, then repeat. | "Aged the stack by 144000 ticks." then the stage moves fresh to ripe to stale to rotting, with the tooltip and item name following. Rotting food gives poison, weakness and nausea when eaten. | The stage does not move, the tooltip does not change, or eating rotting food does nothing. |
| 8.3 | Hold a stack, then `/give` a second bread and merge in the inventory after ageing one stack. | Different stages stay as separate groups on one stack: "Fresh x21 / Stale x9" in the tooltip; Shift+scroll picks the top group. | Groups merge into one age wrongly, or a crash. |
| 8.4 | Put food in an **Ice Chest** with ice (Snow, Ice, Packed Ice, Blue Ice). Open it. | "Cooling: ... left", "Inside: 0 C, frozen", "Ice lasts ... at this temperature". Food inside barely ages. The ice melts faster in warm biomes and not at all below 0 C. | Food ages at full speed with ice present, or the GUI shows raw keys. |
| 8.5 | Check temperature response: leave bread in a hot biome inventory versus a cold one (wait a few minutes in each and compare `/spoilage inspect`). | Food ages faster in the warm and slower in the cold. | Same speed everywhere with Meteodyne installed. (Without Meteodyne it should age by the clock alone, with no error.) |
| 8.6 | With AppleSkin installed, hover or hold spoiled food. | Previews show the reduced food and saturation values. | The preview shows full values for rotting food. |
| 8.7 | Check the config: `simple_spoilage-server.toml` exists, and the old `/eae food` command is gone. | `/spoilage` is the only food command. | `/eae food ...` still works, or `food` appears anywhere in `/spoilage`. |

---

## 9. The `/eae` redirects

The core keeps `/eae <feature>` as an alias of each feature mod's own command. Test each pair; both must give the same answer.

| # | Run | Expect | Failure if |
|---|---|---|---|
| 9.1 | `/frontier tier` and `/eae frontier tier` | The same "Tier here: ..." line. Same for `/eae frontier why`, `map`, `guards`, `incursion status`, `deep`. | "Unknown or incomplete command", or the two differ. |
| 9.2 | `/seismic scan <ore> ...` (press Tab to see the choices) and `/eae seismic scan ...` | Same result. | Missing redirect. |
| 9.3 | `/sparseores deposits` and `/eae sparseores deposits` | The same ledger of deposits by ore. Also `phantoms`, `biomes`. | As above. |
| 9.4 | `/spoilage inspect` and `/eae spoilage inspect` (food in hand) | Same output. | As above. |
| 9.5 | `/meteodyne weather` works; `/eae weather`, `/eae wind`, `/eae heat`, `/eae clouds` do **not** exist. | The `/eae` ones answer "Incorrect argument for command" (they were removed on purpose after step 2c). | Any of the four `/eae` ones works, or `/meteodyne ...` is missing. |
| 9.6 | Type `/eae ` and press Tab. | Exactly four suggestions: `frontier`, `seismic`, `sparseores`, `spoilage`. | More, fewer or different. |
| 9.7 | Run each of 9.1-9.4 as a non-operator. | The same refusal as the direct command. | The `/eae` form lets a player do what the direct one refuses. |

---

## 10. Each mod loading alone, with only what it needs

Use a fresh copy of the instance for each row so nothing else is in `mods/`. In each: start, join a new world, wait a minute, then quit. Pass means no error screen, no `ERROR` naming the mod in the log, and the checks in the last column. These are the same required sets the cloud booted as a server; here it is a real client.

| # | `mods/` holds only | Expect | Failure if |
|---|---|---|---|
| 10.1 | `paradyne_lib` | Main menu, a world, and the Mods screen lists it with a Config button. Nothing visible changes in the world. | A crash, or an error naming a missing mod (it needs nothing but NeoForge). |
| 10.2 | `sable_fixes` + **Sable** (and what Sable itself needs, the Veil it carries). Not the library. | It loads and says Sable Fixes 0.1.0. Build or load a Sable ship (a sub-level), save and reload the world: the ship keeps its blocks and lighting. Log: Sable alone may print "Failed to apply tag physics properties. Unknown block: create:flywheel"; that is Sable's own data without Create, not ours. | A mixin error naming `sable_fixes`, a ship that loses its attachments or lighting after a reload, or a hang when events happen near a ship. |
| 10.3 | `sparse_ores` + `paradyne_lib` | New world: ores are sparse. `/sparseores deposits` lists deposits; `/sparseores biomes` and `phantoms` answer. Rich ore blocks and chips exist in the creative tab. Without the core, a rich ore drops five times what its vanilla ore drops (rich coal: 5 coal, rich redstone: 20-25 redstone), and ordinary ores drop vanilla's amounts; chips drop only with the core (split step 4). Ores come as deposits (see the `/sparseores deposits` list) rather than as vanilla scattered veins. Rich zinc exists only with Create (without it the loot is quietly skipped). | Normal vanilla veins everywhere, a missing command, or errors about Create (it is optional here). |
| 10.4 | `seismic_surveyance` + `sparse_ores` + `paradyne_lib` + **Create** (6.0.10) | The Seismic items and blocks work (section 4.1 and 5.1). The PRD and Smart Logger open. No Frontier, no tier overlay, no `/frontier`. | A missing item texture, a crash opening a screen, or an error about Frontier. |
| 10.5 | `meteodyne` + `paradyne_lib` | Clouds, weather, `/meteodyne weather` and `/meteodyne clouds info` work (section 3.1-3.2, 3.7-3.8). With no Serene Seasons it uses the built-in calendar. Rooms, fires and the sun change air temperature. | A crash, no clouds, no commands. No Sable or Iris is needed. |
| 10.6 | `frontier` + `paradyne_lib` + **Deeper and Darker** | `/frontier tier`, `map`, `why`, `torches`, `incursion ...` work (section 5.9-5.10). `/frontier deep` is **not** there (that lives with Seismic). No thumper triggers. | A crash, or `/frontier deep` present without Seismic. |
| 10.7 | `simple_spoilage` + `paradyne_lib` | `/spoilage inspect` and `age` work, food has stages and tooltips, the Ice Chest works (section 8). Without Meteodyne food ages by the clock only. | A crash, or an error about Meteodyne. |
| 10.8 | The core's required set: all of ours **except** Sable Fixes, plus Create, Sable, Simulated, Aeronautics, Offroad, Encumbered, Deeper and Darker | Loads and plays. Optional mods (Cold Sweat, MineColonies, Serene Seasons, AppleSkin, Diet, Guard Villagers, Diesel Generators) are absent and nothing complains except possibly the `Object with ID cold_sweat:... doesn't exist` and `createdieselgenerators:... doesn't exist` data-map lines (see the PR notes: expected without those mods, but loud). | A crash; a mixin error for MineColonies or Cold Sweat while they are absent. |
| 10.9 | Everything, then add the optional mods one at a time (Cold Sweat, MineColonies, AppleSkin, Diet, Guard Villagers, Diesel Generators) | Each one's glue switches on only when it is present: Cold Sweat's temperature follows Meteodyne's air temperature; MineColonies' weather behaviour and guard counting apply; Diet nutrition follows spoilage; Guard Villagers' guards count for Civilized land. | Glue active without its mod, or missing with it. |

---

## 11. What to send back
- A pass or fail for each numbered line (a copy of this file with the boxes ticked is enough).
- For each failure: the line number, what you saw, the `latest.log` (or the crash report), and a screenshot if it is visual.
- Any line where you were unsure.
- Anything you noticed that this list does not cover.
