# Food spoilage (core mod, package `food`)

## Design (Bright, 2026-09-27)
- Stages and what eating does:
  - Fresh: full food and saturation, 125% Diet nutrition.
  - Ripe: full effect.
  - Stale: 25% less food and saturation, 50% Diet nutrition.
  - Rotting: 50% less food and saturation, no Diet nutrition, 3 s poison, 20 s weakness, 20 s nausea.
- Food points round to the nearest point, halves up (confirmed).
- Nutrition multipliers set 2026-09-29; the Diet side is in `claude/diet-research.md`.
- Hidden: 10 substages each for fresh/ripe/stale + 1 rotting = 31.
- Stage length: 6 in-game days each (exactly 2 real hours), so rotting after 18 days.
- Same main stage stacked together: everything takes the less fresh (older) age.
- Different main stages stacked together: one stack holding separate groups (e.g. 21 fresh + 9 stale), shown in the tooltip. Shift+scroll over the stack in an inventory screen picks which group is on top; the top group is what gets eaten and what leaves first. Default top = freshest. A top the player picked stays picked when fresher food is added.
- Hotbar item-name popup shows the top group, e.g. "Bread (Fresh x21)".
- Everything edible spoils (wheat etc. don't, since they aren't food). Rotten flesh is always rotting.
- Where food starts: crafted, mob drops, fishing and everything else = fresh. Loot chests and trades, rolled per stack at a random point inside the stage:
  - village chests and villager trades: 50% fresh / 50% ripe
  - illager (pillager outpost, woodland mansion) and piglin (bastion) chests: 50% ripe / 50% stale
  - every other loot chest (ruined portals, shipwrecks, mineshafts, strongholds, all modded): 50% stale / 50% rotting
- These buckets are datapack-driven so modded loot tables can be sorted without code.
- AppleSkin is in the pack, and its previews show spoiled values.

## Ice chest (Bright, 2026-09-28/29): built 2026-09-29
- A dedicated block with 9 food slots (3x3) and 1 ice slot. The single ice slot rewards compacted ice or automation.
- **Since 2026-10-15 (food by temperature, `claude/food-temperature.md`):** while it has ice, food inside is kept at 0 C and frozen (ages at 0.05x; thaws, at half a stage's cost, once out in the warmth), and the ice melts faster in warmer air (the times below are at 20 C; twice as fast every 10 C warmer; not at all at 0 C or below). Before: food spoiled a flat 90% slower while cooling.
- Ice is burned one item at a time like furnace fuel, however full the chest is (at a rate set by the air around it, see above).
- Cooling time per item:

  | Item | Multiplier | Per item | Full stack |
  |---|---|---|---|
  | Snowball | x1/16 | ~9.4 s (188 ticks) | 10 min (16) |
  | Snow layer | x1/16 | ~9.4 s (188 ticks) | 10 min (64) |
  | Snow block | x1/4 | 37.5 s (750 ticks) | 40 min |
  | Ice | x1 | 2.5 min (3000 ticks) | 2 h 40 min |
  | Packed ice | x4 | 10 min (12000 ticks) | 10 h 40 min |
  | Blue ice | x16 | 40 min (48000 ticks) | 42 h 40 min |

  Crafted into packed or blue ice, raw ice is worth less (9 ice → 4, 81 → 16). So loose ice is the most efficient per block, and compacted ice fits more cooling in the one slot.
- Melted ice leaves nothing.
- The air around it (biome, season, room) sets how fast the ice melts (since 2026-10-15).
- Recipe, shaped:

  ```
  P P P
  I W I
  I B I
  ```

  P = iron plate (Create iron sheet, via tag `c:plates/iron`), I = iron ingot, W = any wool (`#minecraft:wool`), B = barrel.
- Look:
  - Based on the vanilla barrel: barrel sides framed with iron beams, and an iron top/lid.
  - Open and closed states like the barrel's, with no other animation.
  - When open, the top shows wool insides using the vanilla white wool texture.
- How it's built (package `food.icechest`):
  - `IceChestBlockEntity.settle` is lazy. It works out the cold time since the chest was last settled (`IceChestCooling`), burns ice items as needed, and ages the food frozen for the cold part and at the air around it for the rest (since 2026-10-15; before: born moved later by 0.9 x the cold ticks). This keeps it correct across unloaded time, since food ages by the global clock there too.
  - The chest settles before every read or change of its slots, and every 20 ticks while loaded, so the gauge and tooltips stay current.
  - Automation via item handler capability: ice goes in from the top only, food goes in from the sides only (food slots take only edible items), and food comes out from the bottom only. Ice can't be pulled out by automation.
  - Config `[iceChest]` in the spoilage server toml: `iceTicks` (3000 at 20 C; `slowdown` removed 2026-10-15), and `coolants` as `"<item id> <multiplier>"` lines.
  - Screen: vanilla dispenser background, an ice slot on the left, and a vertical cooling gauge. Hovering the gauge shows the temperature inside and outside and how long the ice lasts at the current rate (since 2026-10-15).
  - Textures are generated from vanilla barrel, iron block and white wool (`IceChestTextures` / `IceChestDataGen`, via the Data run). Model and blockstate mirror the vanilla barrel, with open/closed lid. It is mined with an axe.
- Implementation choice (flagged to Bright): ice doesn't melt while there is no food inside.

## Built
### Phase 1: stacking core + aging
- `FoodFreshness` component (`engines_and_empires:food_freshness`): list of cohorts (born tick, count), freshest first, max one per stage, plus `top` (0 = freshest, anything else = a stage the player picked).
- Age = Overworld game time minus born. Nothing ticks; stages are worked out when needed. Stage lengths: SERVER config `engines_and_empires-spoilage-server.toml` (older test worlds still have 5 days in theirs).
- Stacking: food differing only in freshness counts as the same item (`isSameItemSameComponents`), but `matches` stays exact so menus sync changes.
- Freshness goes where the units go through the menu clicks (all types except number-key swaps), inventory pickup, item entity merging, hoppers, ItemStackHandler insert/extract, InvWrapper/SidedInvWrapper insert, and `ItemStack.split`. The top units leave first.
- Anything else (unknown mod code) falls back to a safe guess: shrinking removes the freshest units, growing adds to the stalest. It can make food staler, never fresher.
- Unstamped food counts as born now; it gets stamped on player inventory ticks, on container open, when dropped into the world, and when compared on the server against stamped food.
- Debug: `/eae food inspect`, `/eae food age <ticks>` (main-hand item).

### Phase 2: choosing the top + where food starts
- Shift+scroll in any container screen except creative sends `FoodTopPayload`, and the server rotates the top group. Scrolling up gives the next staler group, down the next fresher. Only acts on stacks with 2+ groups, and plain scrolling is untouched. A tooltip line explains it while Shift is held.
- Eating takes the unit from the top group (`EatFreshnessMixin` wraps `LivingEntity.eat`, which `Player.eat` calls up to).
- Hotbar popup: `SelectedFoodNameMixin` changes only the drawn name, so eating doesn't re-trigger the popup.
- Rotten flesh: item tag `engines_and_empires:always_rotting`. `engines_and_empires:non_perishable` exists for food that should never spoil (empty for now).
- Buckets are files at `data/<ns>/freshness_sources/<name>.json` with `loot_tables` (exact ids, or patterns ending in `*`; `*` alone = everything else), `traders` (entity type ids) and `stages` (weights). An exact id beats a pattern, and a longer pattern beats a shorter one. The pack's files are `village.json`, `illager_and_piglin.json` and `everything_else.json`.
- Loot is stamped after `LootTable.fill`, i.e. containers only. Trades are stamped when the result slot is filled. The roll is seeded by trader, offer and uses, so reselecting a trade doesn't re-roll it. Shift-click trading keeps each trade's roll.

### Phase 3: stage effects
- `PlayerEatStageMixin` wraps `Player.eat` and swaps in adjusted `FoodProperties` for the top group's stage (`EatingEffects.adjust`), so vanilla applies the hunger, saturation and effects itself.
- Food points are scaled and rounded to the nearest point: bread (5) gives 4 stale and 3 rotting. Saturation is scaled exactly.
- Config, section `[eating.<stage>]` in the spoilage server toml: `foodMultiplier`, `saturationMultiplier`, `nutritionMultiplier` (Diet, added 2026-09-29) and `effects` as lines of `"<effect id> <seconds> [level] [chance]"`. Unknown effect ids are logged once and skipped.
- Food's own vanilla effects still apply on top (e.g. rotten flesh's hunger chance).
- AppleSkin compat: `food/compat/AppleSkinCompat` listens to `FoodValuesEvent` (only registered when AppleSkin is loaded), so its tooltip and HUD previews use `EatingEffects.adjust`. AppleSkin 3.0.9+mc1.21 (Modrinth id `uAKA6Laj`) is `compileOnly` + `localRuntime`, and declared optional in mods.toml.

### Phase 4: ice chest (see section above)

### Phase 5: Diet nutrition (2026-09-29)
- `food/compat/DietCompat` scales Diet's gains by the eaten group's stage. Details in `claude/diet-research.md`.
- Tests: `FoodFreshnessTest`, `FreshnessLedgerTest`, `FreshnessSourcesTest`, `StageEffectsTest`, `IceChestCoolingTest` (unit), `FoodGameTests` (29 in-game) and `DietGameTests` (7 in-game). All 97 game tests pass.

## Phase 6: temperature (2026-10-15)
See `claude/food-temperature.md`: spoilage doubles every 10 C (20 C normal), frozen food nearly stops, thawing costs half a stage, applied by a sweep to containers, players, items on the ground, belts and depots, and ships.

## Next
- Check Create's own inventories (belts, depots, vaults), Sophisticated Backpacks and Mouse Tweaks alongside shift+scroll.
- Non-player eaters (e.g. MineColonies citizens) don't use stage values yet.
