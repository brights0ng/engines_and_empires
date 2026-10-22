<!-- Written by Claude; moved and updated by Gemini. -->
# Standalone-completeness audit (split step 4b)

Bright's standing principle (plan section 1, after step 4): every split mod must run complete and consistent without the
core mod, and the feature mods must work with each other without the core. The core adds only pack tuning and
integration with other mods' content, never something a mod's own features need.

For each of the seven feature mods this note lists (1) what its own features need and where each piece lives, (2) what
existed only because the core supplied it although the mod's own feature needed it (a bug against the principle), with
what was done about it, and (3) what degrades without the core and why that is acceptable (integration only).

How it was checked (2026-10-17, branch `split-4b`):
- every resource file under each mod's `src/main/resources` and `src/generated/resources`, and every resource file of
  the core (`src/main/resources`, `src/generated/resources`), by namespace and by the ids it names;
- every `Component.translatable` key in each mod's code against that mod's own `en_us.json` (no feature mod uses a key
  that only the core's lang has; the core's lang holds only its two weight tooltips);
- every block and item each mod registers against its own block states, models, textures, loot tables, mining tags,
  recipes and lang;
- every registration the core makes into a feature mod's extension points (`EnginesAndEmpires.init`,
  `EnginesAndEmpiresModClient`);
- the bare game test run of each mod (only it and what it requires) and, new in this step, the core-less combined run
  `:coreless:runGameTestServer` (all seven feature mods together, no core; see `coreless/build.gradle`).

"Outside mods" below are mods that are not ours (Create, Sable, Cold Sweat, MineColonies, ...). Data that names an
outside mod's content is integration and stays in the core (task rule: "unless it is another mod's content").

## Summary

| Mod | Bugs found | Fixed here | Left for Bright |
|---|---|---|---|
| Paradyne Lib | none | | |
| Sable Fixes | none | | |
| Sparse Ores | chips' loot lived in the core (ordinary and rich chip ores dropped whole items without it); rich and chip ores' smelting and rich ores' crushing lived in the core; with Create, chip ores crushed into whole items, rich ones too, and the chip switch was a world setting that needed a data reload | yes (4b task 1; recipes in 4c task 1; chip crushing and the startup config in 4d tasks 1 and 2) | |
| Seismic Surveyance | none of its own; the tier map layer it shows is Frontier's (next row); without Diesel Generators the combustive thumper had no fuel and did not say so | yes (4c task 2: fuel from data, and a hint) | |
| Meteodyne | none | | |
| Frontier | its tier map on Seismic's maps (`TierOverlay`) was registered by the core, so Frontier with Seismic and no core drew no tier map | yes: moved into `frontier.seismic.client` | |
| Simple Spoilage | none | | |

## Paradyne Lib (`paradyne-lib/`)

**Its own features and where they live.** Building blocks only (plan section 6): terrain probes, wind shelter and
force (`wind/`, Sable parts under `sable/`), the air and heat solver (`heat/`), ship cover (`sable/ships/`), the
`Services` registry and the three provider interfaces (`services/TemperatureProvider`, `WindProvider`, `StormProvider`,
each with a safe default when nothing registers), the wind config (`wind/WindConfig`, `paradyne_lib-wind-server.toml`) and its
config screen (`ParadyneLibClient`), the game test structure provider (`gametest/GameTestStructures`). Resources:
`assets/paradyne_lib/lang/en_us.json` (config title, the wind force group), `data/paradyne_lib/structure/gametest/empty.nbt`,
`paradyne_lib.mixins.json`.

**Supplied only by the core.** Nothing.

**Without the core.** Nothing changes. Without Meteodyne the providers answer their defaults (no temperature, calm, no
storm), which is the documented behaviour of the interfaces, not something the core provides.

## Sable Fixes (`sable-fixes/`)

**Its own features and where they live.** Two mixins on Sable (`sable_fixes.mixins.json`), its game test structure.
It requires Sable and nothing of ours.

**Supplied only by the core.** Nothing. **Without the core.** Nothing changes (the core does not even require it).

## Sparse Ores (`sparse-ores/`)

**Its own features and where they live.**
- Ore generation: the deposit feature and its worldgen data (`data/sparse_ores/worldgen/configured_feature`,
  `placed_feature`, `neoforge/biome_modifier` x5), vanilla's ore veins off (`mixin/NoiseGeneratorSettingsMixin`),
  `/sparseores` (`debug/DepositCommand`), lang (`sparse_ores.ore.*`).
- Rich ore blocks (20; rich zinc only with Create): blocks and items (`rich/RichOreBlocks`), block states, block and item
  models (generated), textures (`src/main/resources/.../textures/block`), mining and ore tags (`c:ores*`,
  `minecraft:*_ores`, `mineable/pickaxe`, `needs_*_tool`, generated), loot tables
  (`data/sparse_ores/loot_table/blocks/*`, generated), lang.
- Chips (coal, diamond, quartz): items (`chips/ChipItems`), item models and textures (generated by `ChipDataGen`), the
  2x2 recipes (`data/sparse_ores/recipe/*_from_chips.json`), the coal chip's burn time
  (`data/neoforge/data_maps/item/furnace_fuels.json`), lang.
- **Since step 4c, its recipes (task 1):** rich ores smelt and blast into five times what their ordinary block gives
  (vanilla's numbers, chips for the chip ores: `data/sparse_ores/recipe/{smelting,blasting}/rich_*.json`) and, with
  Create, crush into five times Create's numbers (`crushing/rich_*.json`); the ordinary chip ores smelt into one chip
  under vanilla's ids while `chipDrops` is on (load condition `sparse_ores:chip_drops`, `chips/ChipDropsCondition`) and
  into vanilla's whole item (`sparse_ores:whole_items/*`) while it is off; Create's zinc ore furnace recipes name only
  the ordinary blocks, so they no longer match rich zinc ore. All built in `rich/RichOreRecipes` and `chips/ChipRecipes`;
  everything that names Create's content waits for Create (`neoforge:mod_loaded`).
- **Since step 4d (task 1), the chip ores crush into chips**: with Create, ordinary coal, deepslate coal, diamond,
  deepslate diamond and nether quartz ore crush into 2.2 chips (stone ore) or 2.8 chips (deepslate and nether ore) -- the
  pack's crushing lead of `docs/design/ore-refining.md` -- and the rich ones into five times that, never into whole items
  (`ChipRecipes.crushing`; rich ones under `sparse_ores:crushing/rich_*`, ordinary ones under Create's own ids
  `create:crushing/<block>`, which they replace). The ordinary ones follow `chipDrops` like the smelting: while it is off,
  Create's recipe applies, as the twin `sparse_ores:whole_items/crushing/<block>` (Create 6.0.10's numbers, mirrored; a game
  test compares each with the file in Create's jar). The rich ones give chips either way. Needs Create (`neoforge:mod_loaded`).
- **New in 4b (task 1):** the chip drops themselves. Ordinary coal, deepslate coal, diamond, deepslate diamond
  and nether quartz ore drop one chip (`data/minecraft/loot_table/blocks/*.json`, generated by
  `ChipDataGen.OrdinaryLoot`), rich chip ores five chips (`RichOreDataGen.Loot` with `Chips.chipped`), and every other
  rich ore five times vanilla's drop. The server config `sparse_ores-server.toml` (`SparseOresConfig`, `chipDrops`,
  default on, with its config screen `SparseOresClient` and lang key `sparse_ores.configuration.title`) switches the
  ordinary ores back to vanilla's whole items through the loot function `sparse_ores:chip_drop` (`chips/ChipDropFunction`).
  **Since step 4d (task 2) that config is a startup config**, `config/sparse_ores-startup.toml` (it was the server config
  `sparse_ores-server.toml`, in each world's `serverconfig/`), which NeoForge reads while the mod is constructed, before any
  data load; so the recipes' `sparse_ores:chip_drops` condition and the loot see the real value from the first load, and 4c's
  one-time data reload is gone. **Changing it needs a restart** (the config screen says so): recipes are decided when the
  data loads, and the setting is per instance now, not per world.

**Supplied only by the core (bugs).**
- *Fixed (task 1):* the loot tables that made coal, diamond and quartz ores drop chips, ordinary and rich
  (`src/generated/resources/data/minecraft/loot_table/blocks/{coal,deepslate_coal,diamond,deepslate_diamond,nether_quartz}_ore.json`
  and `data/sparse_ores/loot_table/blocks/rich_{coal,deepslate_coal,diamond,deepslate_diamond,nether_quartz}_ore.json`
  in the core). Without the core the chips had no source but the 2x2 recipe's reverse; now they are Sparse Ores' own.
  The pack's drops of all 40 ore blocks are unchanged (`PackOreDropsGameTests`).

**Without the core (integration only, acceptable).**
- The group-drop cuts (redstone, lapis, nether gold drop about a quarter of vanilla; their rich blocks five times
  that): pack tuning, `oregen/refining/LootCuts` in the core.
- Refining recipes: metals smelt to nuggets, Create's crushing and washing numbers, the iron golem's nuggets, and on
  top of Sparse Ores' rich ore recipes the pack's numbers where they differ (the metals' rich furnace recipes give
  nuggets; the group ores' rich crushing follows the pack's cut drops -- the chip ores' crushing is Sparse Ores' own
  since 4d): pack tuning (`RefiningRecipes`, which
  since step 4c replaces Sparse Ores' recipes by id only where its numbers differ). Without the core, ores refine as
  vanilla and Create have them, rich ores five times over (Q1, answered in 4c).
- *(Until 4d: Create crushing coal, diamond or quartz ore gave Create's whole items, not chips. Sparse Ores ships that now.)*
- Item weights of the chips and ores (`tags/item/weight/chips.json` and the rest): Encumbered integration (core).
- All ores are `sable:heavy` (`data/sable/tags/block/heavy.json`): Sable tuning (core).

## Seismic Surveyance (`seismic-surveyance/`)

**Its own features and where they live.** Requires the lib, Sparse Ores and Create. Thumpers (mechanical,
combustive, choked), strike plate, geophones, wind-up reader, logbook, smart logger, portable record display,
sledgehammer: blocks and items (`SeismicContent`), block states, models and textures (generated and hand-made), loot
tables (6; `combustive_thumper_column` is `noLootTable()`), recipes and their advancements (12 each, generated), mining
tag (`mineable/pickaxe`), the thumper fuel data map (`seismic_surveyance:thumper_fuels`, since step 4c; see "Adding a
combustive thumper fuel" below) and its fluid tags (`combustible`, `thumper_fuel`, `premium_thumper_fuel`), the
`strikable` block tag, lang (103 keys), the PRD arm pose (`META-INF/enumextensions.json`), `/seismic`. Deposits come
from Sparse Ores, which it requires.

**Supplied only by the core.** Nothing that Seismic's own features need.

**Without the core (integration only, acceptable).**
- The combustive thumper's fuels come from data (step 4c): Seismic's data map entries name Create: Diesel Generators'
  fluids behind a `neoforge:mod_loaded` condition and give its three fluid tags their grades (the tags take other mods'
  `#c:diesel`, `#c:gasoline`, ... fluids). Without a mod that adds a fuel (the pack's Diesel Generators) no fuel is
  registered: the thumper can still be built, never ignites, and its tooltip and goggles say it needs a compatible fuel
  source. That is plan decision 5 (Diesel Generators a soft dependency of Seismic); Seismic's `ThumperFuelGameTests`
  test both cases in its bare run, and the core's `CombustiveThumperFuelGameTests` test Diesel's fuels.
- Frontier's tier map on the PRD and smart logger maps is Frontier's layer, registered by Frontier when both are
  present (moved out of the core in this step, see Frontier).

### Adding a combustive thumper fuel

What the combustive thumper burns is the fluid data map `seismic_surveyance:thumper_fuels` (`ThumperFuels`). An entry
gives a fluid a grade, and the grade decides the shot (`CombustiveFiring`):

| Grade | Range | Burns per shot | |
|---|---|---|---|
| `premium` | 1024 | 150 mB | Diesel |
| `standard` | 768 | 250 mB | Gasoline, biodiesel |
| `volatile` | 1024 | 250 mB | Fires, then explodes (crude oil, plant oil, ethanol) |

A fluid with no entry does not ignite (a dud drop; it also jams the ram until drained). Another mod or a data pack adds a
fuel in either of two ways:

1. **A data map entry**, in its own `data/seismic_surveyance/data_maps/fluid/thumper_fuels.json` (files from every pack
   are merged), keyed by fluid id or `#` fluid tag:

   ```json
   {
     "values": {
       "examplemod:kerosene": { "grade": "standard" },
       "examplemod:rocket_fuel": { "neoforge:conditions": [{ "type": "neoforge:mod_loaded", "modid": "examplemod" }],
                                   "grade": "premium" }
     }
   }
   ```

   Name another mod's fluid behind a `neoforge:mod_loaded` condition, as Seismic does for Diesel Generators'. When two
   entries grade a fluid differently it takes the better grade (premium, then standard, then volatile); an entry written
   `{ "value": { "grade": "volatile" }, "replace": true }` sets the grade outright, and a top-level
   `"remove": ["examplemod:kerosene"]` takes a fluid out again.
2. **A fluid tag**: add the fluid to `seismic_surveyance:premium_thumper_fuel`, `seismic_surveyance:thumper_fuel` or
   `seismic_surveyance:combustible` (`data/seismic_surveyance/tags/fluid/<tag>.json`, with `"required": false` for an
   optional mod's fluid). Seismic's own entries give those tags their grades. The tags already take the common
   `c:diesel`, `c:gasoline`, `c:biodiesel`, `c:crude_oil`, `c:plantoil`, `c:ethanol` and `c:fuel` tags, so a mod that
   puts its fluids in those is picked up with no work.

The map is synced to clients (the tooltip and the goggles read it). While no fluid at all has an entry, the item and the
goggles show "Needs a compatible fuel source (for example Create: Diesel Generators)"
(`seismic_surveyance.combustive_thumper.no_fuel`).

## Meteodyne (`meteodyne/`)

**Its own features and where they live.** Requires the lib. Weather, clouds, temperature, heat: the glaze block
(state, 16 models, item model, texture, loot table, lang), the hail damage type and its tags
(`data/minecraft/tags/damage_type/bypasses_*`), `hail_damageable` with vanilla's crops, `heat_power`, `heat_sources`
and `insulation` data maps with vanilla's blocks, the biome climate data map, `sable:super_light` for glaze (an optional
tag of a mod it has optionally), cloud shaders (`assets/meteodyne/shaders/core`), Iris compat (`compat/iris`), Sable
parts (`sable/`, `ships/`), the built-in calendar (plan S11), configs (`WeatherConfig`, `CloudConfig`) and their screen,
`/meteodyne`. It registers the lib's temperature, wind and storm providers itself (`Meteodyne.init`).

**Supplied only by the core.** Nothing.

**Without the core (integration only, acceptable).**
- Modded machines as heat sources (`data/meteodyne/data_maps/block/heat_power.json`, `heat_sources.json`,
  `insulation.json` in the core: Create, Aeronautics, Diesel Generators, Cold Sweat) and their running drivers
  (`MachineHeatDrivers`, `ColdSweatHearths`).
- Hail damaging Farmer's Delight's and MineColonies' crops (the core's `hail_damageable.json`).
- Serene Seasons' calendar in place of the built-in one (`SereneSeasonsCompat`); Cold Sweat's world temperature
  (`ColdSweatCompat`); MineColonies' citizens following the local weather (the core's `mixin/compat/minecolonies`).

## Frontier (`frontier/`)

**Its own features and where they live.** Requires the lib and Deeper and Darker. Tiers, upkeep, spawning, incursions
and the Deep: dry torch and dry wall torch (states, models, item model, generated textures, loot table that the wall
torch shares, lang), the squelching heart (item, model, generated texture, lang; every Warden drops one, `incursion/IncursionEvents`),
block tags (`burns_out`, `settles`), entity tags (`daytime_hostile`, `guards` with the iron golem, `inhabitants`,
`never_provoked`, `provoked_from_below`, `stationary`), the Deep's rosters (`frontier/deep_roster/*`, Deeper and
Darker's mobs), `loud_machines` data map type, the tier map payloads and client cache (`map/`), the guard-source and
running-provider registries (plan S12, S14), its config and screen, `/frontier`. With Seismic Surveyance (optional),
`frontier.seismic`: the Deep listens to thumper shots, thumper incursions find thumpers, `/frontier deep`, the Deep's
game tests, and (new in this step) the tier map layer on Seismic's maps (`frontier.seismic.client.TierOverlay`).

**Supplied only by the core (bugs).**
- *Fixed:* `TierOverlay`, Frontier's tiers and incursions drawn on Seismic Surveyance's maps (the smart logger and the
  portable record display), was a core class registered by the core's client entry (plan section 4 and S8 put it there,
  as "glue between Frontier and Seismic"). It reads only Frontier's classes (`TierMapCache`, `TierGrid`,
  `TierMapPayloads`) and Seismic's `MapLayer`, and its legend uses Frontier's own lang keys (`gui.frontier.tier_map.*`),
  so with Frontier and Seismic and no core the tier map payloads and lang existed but nothing drew them. It moved
  (`git mv`) to `frontier/src/main/java/dev/brights0ng/frontier/seismic/client/TierOverlay.java`, Frontier's gated
  package, and Frontier's client entry registers it only when Seismic Surveyance is loaded
  (`FrontierSeismicClient.registerIfLoaded`). Its `@EventBusSubscriber` became a listener added at that registration,
  so Frontier never loads a Seismic class without Seismic. This is a deviation from plan section 4 / S8, made because
  the standing principle supersedes it (see the PR).

**Without the core (integration only, acceptable).**
- Loud machines: every entry of `frontier:loud_machines` names another mod's block (Create, Offroad, Aeronautics,
  Simulated, Diesel Generators; `data/frontier/data_maps/block/loud_machines.json` in the core), and Create's running
  test is the core's provider (`CreateLoudMachines`). Vanilla has no machines, so without the core nothing is loud; plan
  Q6 ("vanilla-only rosters and tags").
- MineColonies' guards and Guard Villagers' guards count as guards (`MineColoniesGuardSource`, the core's
  `data/frontier/tags/entity_type/guards.json`).
- Born in Chaos and Naturalist rosters and tags: not written yet (design work, step 4 (f)).

## Simple Spoilage (`simple-spoilage/`)

**Its own features and where they live.** Requires the lib. Food freshness (stages, tooltips, `/spoilage`), the ice
chest (state, models, textures, loot table, recipe, `mineable/axe`, lang), freshness sources (`freshness_sources/*`),
`non_perishable` and `always_rotting` item tags, its config and screen. Food temperature comes from the lib's
`TemperatureProvider`, which Meteodyne registers itself; Sable ships' holds through the lib, gated.

**Supplied only by the core.** Nothing.

**Without the core (integration only, acceptable).** AppleSkin's previews of spoiled food (`AppleSkinCompat`), Diet's
groups and their reduced nutrition (`DietCompat`, `data/engines_and_empires/diet/groups/*`), food item weights
(Encumbered).

## The feature mods with each other, without the core

The pairings that exist (all through the lib's `Services` or a gated package, none through the core):
- Meteodyne -> Simple Spoilage, Frontier, the lib's wind: Meteodyne registers the temperature, wind and storm providers.
- Seismic Surveyance -> Frontier: Frontier's `frontier.seismic` listens to Seismic's thump and echo events, registers
  the thumper access for incursions, and (since this step) the tier map layer.
- Sparse Ores -> Seismic Surveyance: a required dependency.

The core-less combined run (task 3, `:coreless:runGameTestServer`) loads all seven feature mods with what they require
and nothing else (Create 6.0.10 with Ponder and Flywheel, Sable 2.0.5 with Sable Companion and Veil, Deeper and Darker
1.4.1), and runs every feature mod's game tests: 158/158 on 2026-10-17, by mod (with `-Pcoreless.namespaces`) lib 3,
Sable Fixes 2, Sparse Ores 5, Seismic Surveyance 15, Meteodyne 79, Frontier 20, Simple Spoilage 34. Meteodyne and
Frontier run more than in their bare runs (67 and 13) because some of their tests register only with a partner present
(Sable for Meteodyne's ship tests, Seismic for Frontier's Deep tests). It exposed no problem. The core's own tests of these pairings
(`ProviderWiringGameTests`) import several mods and stay core tests; see Q3.

**Step 4c.** The core's game tests of these pairings that need no core code moved into a test-only mod in the same
project, `coreless_tests` (`coreless/`; see `docs/history/build-layout.md` section 9): `ProviderWiringGameTests` (3) and 7
of the 8 `FrontierIncursionGameTests` (the eighth reads the core's loud machines and stays in the core, as `PackIncursionGameTests` since step 4e). The combined
run runs them along with every feature mod's tests, and the aggregate runs them too. On 2026-10-18 it ran 175/175:
the 158 above, Sparse Ores' 4 new recipe tests and Seismic's 3 new fuel tests, and the 10 moved tests.

## Questions for Bright

- **Q1. Smelting chip ores and rich ores without the core.** With chip drops on, a silk-touched ordinary coal, diamond
  or nether quartz ore smelts to vanilla's whole item (vanilla's recipe), four times what mining it gives; and rich ores
  have no furnace recipe at all without the core. In the pack the core's refining recipes cover both (chip ores smelt
  to a chip, rich ores to five times their ordinary block). Should Sparse Ores ship its own furnace recipes for these
  (they would have to follow the `chipDrops` switch too, which a recipe cannot read, so this needs a design choice)?
  Not done: the task listed what moves, and the furnace recipes are refining.
  *Answered in step 4c (Bright: yes):* Sparse Ores ships them, with a load condition that reads `chipDrops`
  (`sparse_ores:chip_drops`; see Sparse Ores above).
- **Q2. Combustive thumper fuel without Diesel Generators.** Seismic's fuel tags hold only other mods' fluids, so in a
  game with Create and Seismic only, the combustive thumper never runs. That is plan decision 5; is it what you want
  for Seismic standalone, or should a vanilla fluid count?
  *Answered in step 4c:* fuel comes from a data map any mod can add to; with none registered the thumper can be built,
  never fires, and says it needs a fuel source (see "Adding a combustive thumper fuel").
- **Q3. Cross-mod game tests in the core-less run.** `ProviderWiringGameTests` (Meteodyne's providers, Frontier's
  thumper access, food temperature) and parts of `FrontierIncursionGameTests` test feature mods together but live in
  the core, so the core-less run does not run them. Running them there would need a test-only mod in `coreless/` (game
  tests are only found in mods). Wanted?
  *Answered in step 4c (yes):* the test-only mod `coreless_tests` holds them (above).
