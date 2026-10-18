# Portable Record Display (PRD): implementation notes

Status:
- Round 1: Bright play-tested it ("works great").
- Round 2 (map controls, walking fix): built.
- Round 3 (3D model, live screen, hold poses): Bright said it "looks great".
- Round 4 (the model is the GUI): Bright play-tested it ("works great").
- Round 5 (model tweaks): Bright's verdict was "even better"; the train-casing parts were "perfect".
- Round 6 (handle seated, brass trim, centred when raised): Bright said it was "mostly great".
- **Round 7 (brass as proportional cutouts, dark oak grip): built 2026-09-23, not yet play-tested.**

Compiles. Datagen runs. PrdRulesTest and SeismicContentTest pass, and so do all 21 gametests.

## Spec (from Bright)
- Handheld item (`portable_record_display`), holds **16 readings**.
- **Three lamps (red, yellow, green) are on the PRD itself.** Green is steady while it's being worked. Otherwise the lamps only flash when something happens:
  - green long pulse = saved or updated
  - yellow ×3 = already has that reading or a newer one
  - red ×3 = full
  - brief green = upload on dock done
- **Smart logger dock** (back half of the bar):
  - Right-click holding a PRD to insert it; right-click with an empty hand to remove it. Breaking the logger drops it.
  - On insert, every PRD reading goes into the logger by the `Logbook.save` rules.

## Textures (rounds 5–7, Bright's asks)
- **Body: Create's train casing** (`create:block/railway_casing`), showing the middle of the texture on each face. Big faces get its brass frame; small ones get its dark steel centre. Bright said this is **perfect**; leave it.
- **Trim: proportional cutouts of Create's brass block** (`create:block/brass_block`). This covers the handle, bezel, floppy drive, buttons and eject, scroll wheel, lamp ledge, antenna base and back plate.
  - Each face is cut out of the texture where it sits, as a block model's faces are, one texel per model pixel (`PrdDataGen.cutout`: the face's position wrapped into 0–16, pulled back to fit).
  - Bright did **not** want a whole brass block squeezed onto each face (round 6 tried that, framed edges on every face, and he rejected it).
- **Handle grip: a sleeve cut out of dark oak planks** (`minecraft:block/dark_oak_planks`, `Tex.GRIP`, the same cutout method) round the brass upright, y 5.5–11.5.
- **Dark bits:** Create's industrial iron (`create:block/industrial_iron_block`): the antenna rod and knob, the drive slot, the wheel hub.
- **Ours:** only the screen picture and the three lenses (`PrdTextures`).
- Borrowed textures are listed in `PrdShape.Tex` with their locations; `PrdDataGen.trackCreateTextures` tells datagen they exist.
- Button words are engraved in dark ink (`PrdItemRenderer.drawButtonWords`, lang `gui.…portable_record_display.button.*`).

## Shape and placement (rounds 5–6)
- **The handle** is on the right, moved in 3 px (`GRIP_X` 16).
  - Its arms sit wholly within the body's right side (y 3–5 and 12–14), so nothing hangs off; tested.
  - The upright runs y 5–12. The handle boxes are tagged `Part.HANDLE`.
- **Third-person carry** translation is (0, 0.2, −2.7). The GUI icon translation x is 1.1.
- **Raised, it is centred:** `RAISED_X` 0, `CENTRE_X` 6.5 (the real middle, body left side to handle). The hands are spread to meet its sides (`RIGHT_HAND_OUT` 0.076, `LEFT_HAND_OUT` 0.104).
- **The eject button** presses 0.15 px (`EJECT_PRESS_DEPTH`), so it stays in sight.
- **The map turns with the player** (`PrdSession.yaw()` is the live yaw); the arrow always points up.
- **The north marker** sits at the edge of what shows, due north of the arrow (`PrdDisplay.edgeAlong`, unit-tested).

## The model is the GUI (round 4)
Bright's decisions:
- **Right-click** raises the PRD in both hands. **The cursor moves freely** over the 3D model, pushing buttons and touching the glass. There is **no 2D GUI**.
- **The records list lives on the 3D screen**, with on-glass **MAP / RECORDS tabs**.
- **The side buttons do what they're labelled:**
  - DELETE: press twice within 3 s to confirm.
  - RENAME: type on the keyboard; the name shows on the glass.
  - **SAVE / LOAD / CLEAR / eject show "NO DISK"** until the **floppy-disk system** exists.
- **Hold Tab to look around** (rebindable).
- **Putting it down:** Esc, the inventory key (it doesn't open the inventory), or right-clicking off the device. Changing hotbar slot does not put it down.

How it works:
- `client/PrdSession` holds all client state for working it.
- Picking:
  - `PrdPoses.renderRaised` gives `PrdSession.capture` the model-px → clip matrix every frame.
  - The mouse is unprojected into a ray, and `PrdShape.pick` does slab tests against boxes tagged by `Part`.
  - It works in first person only.
- `client/PrdDeviceScreen` is invisible and just catches input.
- `PrdClient` tick handles the Tab look (closing and reopening our screen), the input guards while looking, and walking via raw GLFW.
- Glass (160×128 glass px): an 11 px tab bar, a 20 px strip, 10 px rows (9 shown), and the map behind the bars.
- Buttons are separate partial models, so they can depress.
- Server: `PrdActionPayload` → `PrdItem.act` (delete or rename, only for the selected hotbar slot or the off hand). Network VERSION 4.
- The old records GUI (`PrdMenu`, `PrdScreen`, `OpenPrdRecordsPayload`) is dormant, kept for the floppy revamp.
- Gotcha: `PrdItemRenderer.Canvas` fetches the textBackground buffer afresh for every shape, because drawing text ends the previous builder.

## 3D model (round 3)
- Bright's concept sketch, **mirrored so the handle is on the right**. Built in datagen (`PrdShape` → `PrdDataGen`).
- Layout, left to right:
  - scroll wheel on the left side
  - 5 buttons
  - the screen (10×8 model px) in a bezel, with the floppy drive under it
  - the D-handle on the right
- On top: the antenna, and a ledge with 3 tall lenses, all within the body's depth.
- The item model has parent `builtin/entity`; `client/PrdItemRenderer` draws the body, buttons and their words, the glass, and the lit lamp.
- Every glass shape is opaque (`overGlass`).
- `client/PrdPoses`:
  - a 0→1 raise over 6 ticks
  - first person via `RenderHandEvent`
  - `steadyBob` keeps 15% of the walking bob
  - all tuning numbers are constants at the top
- `client/PrdArmPose`: a two-handed third-person pose via a NeoForge enum extension.
- `SmartLoggerRenderer`: the docked PRD is drawn at scale 0.24.

## Code map (package `geophone`)
- **PRD (common):**
  - `PrdItem` (`act`), `PrdActionPayload`, `PrdSignal`
  - `PrdDisplay` (rotated-map maths, `edgeAlong`)
  - `PrdShape` (boxes, parts, textures, picking, glass layout), `PrdTextures` (the screen and lenses only), `PrdDataGen` (`cutout` UVs)
  - `OpenPrdRecordsPayload` (dormant)
- **PRD (client):** `PrdClient`, `PrdSession`, `PrdDeviceScreen`, `PrdGlass`, `PrdItemRenderer`, `PrdPoses`, `PrdArmPose`, `PrdScreen` (dormant).
- **Tests:** `PrdRulesTest` (textures, shape incl. handle seating and grip, picking, rows, pressed depth, north marker, lang); `gametest/PrdGameTests` (7).

## Placeholders / left for Bright
- Placeholder recipe: glass pane over brass–compass–brass, redstone below.
- Uploading to the logger doesn't bring along the PRD entry's name when it *updates* an existing entry.
- Follow-ups:
  - **floppy-disk system** (wires SAVE, LOAD, CLEAR and eject)
  - spin the scroll wheel on zoom
  - hover highlight on the buttons
  - sync "working it" so other players see the two-handed pose
  - hide the off-hand item in third person
  - picking in third-person view
