<!-- Written by Claude (2026-10-24) from Bright's decisions in this session. -->
# Empire: Civilians, basics

**Date:** 2026-10-24 · **Status:** built on branch `civilians-basics`. Visuals (skin, model, bubbles) are unchecked in a client; Bright checks them.
**Scope:** the civilian entity, its skin compositor, idle wandering, speech bubbles, right-click dialogue and debug commands. The real civilian AI comes later; the vanilla goals here are temporary.

## Decisions (Bright, 2026-10-24)

- **D1 Model** = vanilla slim player model (3 px arms, outer layer on). No GeckoLib for civilians.
- **D2 Skin** = composited in-game from grayscale templates. Each civilian rolls 1-5 for style and 1-5 for colour in 5 categories (skin, hair, shirt, pants, shoes): 10 rolls, saved and synced, never rerolled.
- **D3 Speech** = bubbles above the head: text on a partly transparent dark background, with a thin bar along the bottom that shrinks as the line's time runs out. No chat messages.
- **D4 Names** always shown (custom name visible).
- **D5 Right-click** = dialogue: the civilian stops, faces the player and says a line. Each later right-click gets another line. No GUI.
- **D6** Civilians count as Frontier habitation (data tag only).

## Provisional defaults (one named constant each)

| Constant | Value | Where |
|---|---|---|
| `HOME_RADIUS` | 16 | `CivilianEntity` |
| `SPEECH_RADIUS` | 16 | `CivilianEntity` |
| `COOLDOWN_MIN`, `COOLDOWN_MAX` | 1200, 3600 ticks | `CivilianEntity` |
| `GREET_RADIUS`, `GREET_GAP` | 6, 6000 | `CivilianEntity` |
| `TALK_CHANCE` | 0.25 | `CivilianEntity` |
| `TALK_RADIUS` | 4 | `CivilianEntity` |
| `TALK_LINE_GAP` | 100 | `CivilianEntity` |
| `DIALOGUE_RANGE`, `DIALOGUE_LEAVE`, `DIALOGUE_IDLE` | 5, 200, 600 | `CivilianEntity` |
| `BUBBLE_WRAP` | 120 px | `CivilianBubbleRenderer` |
| `BUBBLE_RENDER_DIST` | 24 | `CivilianBubbleRenderer` |
| `BUBBLE_BG` | `0x99101010` | `CivilianBubbleRenderer` |
| `BUBBLE_BAR` | `0xFFE0B050` | `CivilianBubbleRenderer` |
| `SKIN_CACHE_MAX` | 512 | `CivilianSkinCache` |

Fixed by the brief and kept as private constants: rain 50 %, night 40 %, greeting 50 %, conversations break past 6 blocks, clicks within 10 ticks of the last line get no line, bubble duration `clamp(50 + 2 * chars, 60, 140)` ticks, 4 lines max, 3 px padding, 2 px bar, fade in 4 / out 6 ticks.

## Classes added

Pure rules (`dev.brights0ng.empire.rules`, no Minecraft imports):
- `CivilianAppearance`: the 10 rolls, packed int (3 bits a slot), 10-digit code.
- `CivilianSkinComposer`: layers of shade IDs through per-layer LUTs, later layers on top, misses counted.
- `CivilianNames`: Bright's 40 names.
- `CivilianLines`: line categories, talk exchanges and their lang keys; `pick` never repeats the last line.

Entity (`dev.brights0ng.empire.civilian`):
- `CivilianEntity`: look, name, home, empire, persistence, temporary goals, speech, conversations, dialogue.
- `CivilianFaceGoal`: holds the civilian still, facing its dialogue player or conversation partner.
- `CivilianSpawning`: the one spawn path (debug commands, game tests).
- `CivilianNetwork`: `empire:civilian_speech` (entity id + lang key), server to client.
- `CivilianSpeechStore`: the client's current line per civilian; common-safe.
- `CivilianInteractions`: turns a main-hand name tag click into a dialogue click (see Deviations).

Client (`dev.brights0ng.empire.client`):
- `CivilianClient`: renderer and reload-listener registration; clears skins and bubbles on logout.
- `CivilianSkinLibrary`: loads templates and shade LUTs on resource reload; Alex skin on failure.
- `CivilianSkinCache`: composed skins as dynamic textures, LRU.
- `CivilianRenderer`: slim player model, armour layers, player scale, bubble after the body.
- `CivilianBubbleRenderer`: the speech bubble.

Elsewhere: `command/CivilianDebugCommands` (`/empire debug civilian ...`), `gametest/CivilianGameTests`, `data/frontier/tags/entity_type/inhabitants.json`, the civilian lines in `en_us.json`, `EmpireContent.CIVILIAN` and its attributes. Unit tests: `CivilianAppearanceTest`, `CivilianSkinComposerTest`, `CivilianNamesTest`, `CivilianLinesTest`. Empire's test classpath gained Gson (as the lib's has) for the lang-file check.

## Deviations and open items

1. **Name tags.** The brief has `mobInteract` never consuming the item, which "also stops name tags renaming civilians". In 1.21.1 `Mob.interact` is final and applies a name tag *before* `mobInteract` runs, so that alone does not stop renaming. `CivilianInteractions` catches a main-hand name tag on a civilian in `PlayerInteractEvent.EntityInteract` and runs the same dialogue click instead. Bright: confirm this is what you want.
2. **Empire lookup is Overworld only.** Claims are Overworld chunks (the bonfire and claim code check `Level.OVERWORLD`), so a civilian spawned in another dimension gets no empire and its spawn block as home, rather than taking the empire of the Overworld claim at the same chunk coordinates.
3. **Greetings and the cooldown.** A greeting does not reset the line cooldown, so an idle line can follow a greeting soon after. Civilians in a dialogue or conversation say no idle lines or greetings.
4. **Clicks within 10 ticks of the last line** still start or refresh the dialogue; they just get no new line. If the click that starts a dialogue is one of these, the next click gets a reply, not a greeting.
5. **A failed conversation start** (no partner in range or sight) falls back to an ordinary line.
6. **Bubble anchor.** The bubble's bottom edge sits at `bbHeight + 0.85`, so multi-line bubbles grow upwards and never cover the name tag. Bright checks the look.
7. **Game test batch.** `CivilianGameTests` runs in its own batch (`civilians`): another test founding an empire in a neighbouring test area during the same batch would claim the chunk and change the civilian's home.
8. **Assets were untracked.** The templates, `catalog.json`, `shade_luts.json` and `palettes.json` were in the working tree but not committed; the branch's first commit adds them unchanged.
