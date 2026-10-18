# Food spoilage by temperature (2026-10-15)

Builds on `claude/food-spoilage.md` (stages, freshness cohorts, ice chest) and the weather backbone: air temperature (`weather/climate/Temperature`, 8d, underground included), the heat simulation's rooms (`claude/weather-backbone-phase8f.md`, 8f) and ships (8f-5). Status: **built and tested (section 4); full game test server 199/199.**

## 1. Decisions (Bright, 2026-10-15)
1. **Rate doubles every +10 C** (Q10 = 2), **normal speed at 20 C** (the tuned stage lengths): rate = 2^((T - 20) / 10). 35 C about 2.8x, 10 C 0.5x, 2 C about 0.29x.
2. **Frozen food nearly stops:** while frozen it ages at a flat 0.05x.
3. **Freezing and thawing have a margin:** food freezes once below -2 C and thaws only once above +2 C; between, it keeps its state.
4. **Thawing costs half a stage:** a thaw ages the food by half a stage (3 in-game days at the default stage length), once per thaw.
5. **Scope: everything that holds food:** containers (any block with an inventory, Create vaults and modded storage included), players' inventories, items on the ground, and Create belts and depots. Ships' holds by the ship's air (8f-5).
6. **The ice chest keeps its contents at 0 C and frozen** (replacing the flat 90% slowdown: food in it ages at 0.05x, and taking it out to warmth costs the thaw). **Its ice melts faster in warmer surroundings, on the same scale:** usage = the 20 C rate (today's per-item times: ice 2.5 min, ...) x 2^((outside - 20) / 10).
7. **Ice chest tooltip/gauge:** the temperature inside and outside, and how long the ice in it will last at the current rate.
8. **Unloaded time catches up at the place's seasonal average temperature** (plus its room offset).
9. **No humidity.**

### Assumed (flagged to Bright, not contradicted)
- The ice chest's ice doesn't melt while the air around it is at or below 0 C (the chest stays cold).
- An ice chest with no ice left is an ordinary container (its contents follow the air, and thaw above +2 C).
- Other dimensions: food ages by the clock alone (no temperature there).

## 2. The temperature food sees (`food/FoodTemperature`)
- Block (container, belt, depot): `Temperature.at` (underground included) + `HeatWorld.roomAt` at the block's middle. In a ship's plot: the air where the ship is (`Climate.sample` at the block's world position) + the ship's room.
- Entity (player, item on the ground): the same at its feet, with the room at the middle of its body (not wind chill, sun or radiant).
- Seasonal (long gaps): the day's mean for the time of year without air masses (`Baseline.Sample.dailyMean() - anomaly`), through the underground probe, + the room as it is now.
- NaN outside the Overworld (no change but the mark).

## 3. How it works
- **Pure model** (`food/FoodClimate`, unit tested): `Rules` (reference 20, doubling 10, frozen 0.05, freeze below -2, thaw above +2, thaw penalty 0.5 stage; the average of the three timed stages); `apply(food, now, ticks, T, forceFrozen)`: each cohort's frozen state by the margin (a thaw moves its birth half a stage earlier), then its birth moved by (1 - rate) x ticks (never past now).
- **Frozen cohorts:** `Cohort` has a `frozen` flag (saved, synced); a stack keeps frozen and thawed units of a stage apart (`FoodFreshness.settle` groups by stage, then frozen), so up to eight groups; the tooltip and hotbar say "Fresh (frozen)".
- **The mark:** a second item component, `engines_and_empires:food_climate`, the tick a stack's temperature was last applied to. Like the freshness, it doesn't keep stacks apart. Each visit ages a stack by the time since its mark at the place's temperature (or, past three sweeps, at the seasonal temperature: it sat unseen), then marks it. A stack reached twice in a tick (a Create vault's parts sharing one inventory) is aged once; a stack moved between places carries its mark. A stack seen for the first time takes no time but freezes or thaws by where it is.
- **The sweep** (`food/FoodClimateSweep`): every `[temperature] sweepSeconds` (60), spread over the ticks between: every loaded chunk's block entities (a `Container`'s slots, else the block's item handler: chests, barrels, Create vaults, belts, depots, modded storage; ships' plot chunks included), every player's inventory, every item on the ground. Ice chests run their own settle.
- **Ice chest** (`IceChestBlockEntity.settle`): the outside temperature (seasonal for long gaps) sets the melt factor (`IceChestCooling.meltFactor`: the food rate there, 0 at or below 0 C); `IceChestCooling.runScaled` burns the ice at that factor and returns the real cold ticks; the food is aged frozen for the cold part and at the outside air for the rest (so it thaws, with the penalty, once the ice is gone); no food, no melting (as before). Gauge data: listed seconds left of the burning item and its total, outside and inside temperature (tenths of a C), and minutes all its ice lasts at the current rate (-1: it doesn't melt here). The tooltip: "Inside: 0.0 C, frozen" (or the air), "Outside: ...", "Ice lasts h:mm:ss at this temperature" / "Too cold out for the ice to melt" / "No ice".
- **Config** (`[temperature]` in the spoilage server toml): `enabled`, `referenceC`, `doublingC`, `frozenRate`, `freezeBelowC`, `thawAboveC`, `thawPenaltyStages`, `sweepSeconds`. The ice chest's `slowdown` is gone; `iceTicks` is now the time at the reference temperature.

## 4. Tests (2026-10-15)
- Unit `FoodClimateTest` (8): doubling every 10 C; a cellar halves and a hot room doubles; never younger than new; the margin and the thaw penalty; frozen food nearly stops; the ice chest keeps it frozen in any air; frozen and thawed groups stay apart; an unknown temperature changes nothing. `IceChestCoolingTest` (8; two new: warmer air melts faster, nothing melts at freezing). `FoodFreshnessTest`, `FreshnessLedgerTest` still pass.
- Game `FoodTemperatureGameTests` (5, batch `food_temperature`, temperature set per test with `FoodTemperature.setOverride`): a barrel at 10 C ages half as fast and at 30 C twice as fast, a second visit in a tick does nothing; a gap longer than three sweeps takes the seasonal temperature; bread on the ground freezes at -10 C, stays frozen at +1 C, thaws at +10 C with the penalty; a player's inventory at 0 C ages at a quarter (not frozen); stacks marked at different times still stack.
- Game `FoodGameTests` ice chest tests moved to batch `food_temperature`: at 20 C one ice keeps bread frozen 3000 ticks (aging 150 of them) then it thaws with the penalty; two ice at 30 C last 3000 ticks; at -5 C nothing melts, the chest stays cold, the food frozen and the gauge says it won't melt; packed ice burns one at a time; no food, no melting.
- **Full game test server: 199/199 passing.**

## 5. Not covered / to check in play
- Create belts and depots are reached through their item handlers; whether every Create inventory hands out its live stacks there is to be checked in play.
- The ender chest isn't swept (its food catches up at the seasonal temperature wherever it next lands).
- Non-player eaters (MineColonies citizens) and their storage: their racks are block entities and are swept like any other.
