# Ore refining: implementation notes

Status: built 2026-09-24. Compiles, datagen runs, all 26 gametests pass. `RefiningTest` and `RichOresTest` pass. Not yet play-tested.

Research and background: see `claude/ore-processing-research.md`.

## Bright's decisions
- Create processing comes out ahead of the furnace.
- Drills keep pickaxe drops. The borehead bearing gives crushing-wheel ratios.
- Fortune and silk touch work normally.
- Ore-block smelting matches the ore's drops.
- Washing crushed ore gives 2 nuggets plus a 50% chance of a 3rd; the furnace gives 2.
- Copper stays cheap.
- Chips are the pack's own: 1 chip per ore, 4 chips = 1 item. Coal, diamond and quartz get chips; emerald is untouched.
- Group drops are cut by about 1/4.5 when mined, and by less when crushed.
- Iron golems and Create's washing of gravel, red sand and soul sand are cut to match. Loot chests are untouched.
- MineColonies is deferred.

## Numbers (all in `oregen/refining/Refining.java`)

### Metals
- Raw ore → 2 nuggets, and crushed ore → 2 nuggets, in the furnace or blast furnace.
- Washing crushed ore gives 2 nuggets + 50% chance of 1 more, plus Create's byproduct (unchanged).
- A silk-touched ore block smelts to what mining it drops: iron, gold and zinc give 2 nuggets; copper gives 7 (3.5 raw × 2).
- Crushing ore blocks keeps Create's numbers: 1.75 crushed ore from stone ore, 2.25 from deepslate; copper gives 5.25 / 7.25.

### The crushing lead
Crushing plus washing beats mining plus the furnace by 2.19× for stone ore and 2.81× for deepslate and nether ore. Chip and group ores use the same lead:

| Ore | Mined | Crushed (stone / deep) |
|---|---|---|
| Coal chip | 1 | 2.2 / 2.8 |
| Diamond chip | 1 | 2.2 / 2.8 |
| Quartz chip | 1 | 2.8 (nether) |
| Redstone | 1 | 2.2 / 2.8 |
| Lapis | 1–2 | 3.3 / 4.2 |
| Nether gold nuggets | 1 | 2.8 |

- The pack writes its own loot tables for coal, diamond, quartz, redstone, lapis and nether gold ore. They're generated from `RichOres` (`PACK_LOOT`).
- Redstone, lapis and emerald ore still smelt to 1. Nether gold ore now smelts to 1 nugget.

### Rich ores
- A rich block gives 5× its ordinary block in the furnace and in crushing, using the expected value as count + chance.
- Rich blocks previously had no furnace or crushing recipes at all, and Create's zinc tag recipe matched rich zinc.

### Chips
- Chip ores crush into chips, not whole items, at the lead in the table above: 2.2 chips from stone ore, 2.8 from deepslate and nether ore, and five times that from a rich block. Sparse Ores ships these crushing recipes itself (split step 4d; they need Create). The ordinary ores' follow the `chipDrops` option like their drops and smelting: with it off, Create's own recipes apply. `chipDrops` is a startup config (`config/sparse_ores-startup.toml`); changing it needs a restart.
- Items: `coal_chip`, `diamond_chip`, `quartz_chip`. Four craft back into the item in a 2×2.
- Coal chip burns for 400 ticks, via the `neoforge:furnace_fuels` data map.
- Textures are script-derived shards of the vanilla item (`ChipTextures`), placeholders for an artist.

### Other sources
- Iron golem drops 6–10 nuggets. Its poppies are unchanged.
- Washing gravel gives 3% iron nugget; red sand gives 8.5% gold nugget; soul sand gives 44.5% quartz chip and 0.5% gold nugget.

## Code map (`oregen/refining`)
- `Refining`: every rule and number. It has no Minecraft imports.
- `RefiningRecipes`: builds all the JSON. It overrides vanilla and Create recipes by using their ids.
- `RefiningDataGen`: the providers (recipes, golem loot, chip models and textures), hooked into `RichOreDataGen.gatherData`.
- `RefiningItems`: registers the chips.
- `ChipTextures`: draws the chip textures.
- `BoreheadCrushing` and `mixin/BoreheadBreakingMixin`:
  - The mixin does a `@WrapOperation` on `BlockHelper.destroyBlock` in Offroad's `MultiMiningServerManager.BlockBreakingData.tick`.
  - Only `#c:ores` blocks are crushed. Stone still drops normally.
- Tests: `RefiningTest` (unit) and `gametest/RefiningGameTests`. The gametests check that the overrides beat vanilla and Create at runtime, check the cut drops, check borehead crushing, and check that the mixin applies.
- Test dependency added: `testImplementation com.google.code.gson:gson:2.10.1`.

## Open items / left for Bright
- **Byproducts of washing crushed ore are unchanged.** Iron's 75% redstone is now a large redstone source compared with redstone ore, which drops 1.
- **Also left untouched (not on Bright's list):**
  - Create crushing of crimsite, ochrum, veridium and asurine: it gives crushed ore, already cut by the processing change, plus a chance of a nugget.
  - Tuff crushing (10% nugget of each metal).
  - Diorite (25% quartz).
  - Gilded blackstone (loot, and 18 nuggets when crushed).
  - Piglin bartering.
  - Horse armor crushing.
- The borehead drops crushing results, and the block's own XP orbs still spawn.
- Chip textures are placeholders.
