<!-- Written by Claude -->
# Empire: god sacrifice tables (draft v2, for Bright's tuning)

**Date:** 2026-10-22 · **Status:** draft v2. The item choices are Bright's. The multipliers, curve amounts and rules in §1 are Claude's balance pass.
- v2 resets each tier's reference to its cheapest item.
- v2 lowers the echo shard and pottery sherd multipliers.
- v2 makes levitite obtainable (Silk Touch, diamond-tier pickaxe or better).

**Ids:** checked against the jars: Create 6.0.10, Create Big Cannons (CBC) 5.11.7, Create Diesel Generators (CDG) 1.21.1-1.3.15, Naturalist 2.0.7, Farm & Charm 1.1.29, Aeronautics 1.3.2 (bundled), and Frontier from the repo.

## 1. Rules

### 1.1 How a demand is set
- **Count asked** = `max(1, ceil(Q_tier(D) × m))`.
  - `m` runs from 0.0 to 1.0.
  - **1.0 = the cheapest item in that tier**, so no item is ever cheaper than its multiplier says.
  - Every other item's multiplier = the cheapest item's effort ÷ that item's effort.
- **Eligibility:** an option can only be picked when `Q_tier(D) × m ≥ 0.5`. If neither option in a tier qualifies, the god asks for 1 of the option with the higher multiplier.
- **Rotating demand:**
  - Each completed sacrifice rolls the next demand: one option per active tier, seeded by `(empire id, sacrifice count, tier)`.
  - The roll uses D at the moment it's made, and the demand then stays fixed until it's paid.
  - Right-clicking the Bonfire with an empty hand shows what's still owed on the action bar.
- **Fuels and fluids** are counted in 1000 mB units, read through the item's fluid-handler capability. This is independent of CDG's configurable canister capacity.

### 1.2 Curve
Tiers 1–3 ramp up and then taper off. Tiers 4–5 never end; after their peak they keep growing as `Qmax·√((D−start)/(peak−start))`.

| Tier | Start | Peak | End | Qmax (count of an m = 1.0 item at the peak) |
|---|---|---|---|---|
| T1 | 0 | 12 | 27 | 24 |
| T2 | 5 | 20 | 37 | 128 |
| T3 | 17 | 30 | 50 | 90 |
| T4 | 28 | 45 | ∞ | 16 (22 at D 60, 28 at D 80) |
| T5 | 38 | 60 | ∞ | 48 (66 at D 80) |

Q at each sacrifice:
- D10: T1 20 · T2 43
- D20: T1 11 · T2 128 · T3 21
- D30: T2 53 · T3 90 · T4 2
- D40: T3 45 · T4 11 · T5 4
- D50: T4 18 · T5 26
- D60: T4 22 · T5 48

Active tiers by D:

| D at sacrifice | 10, 15 | 20, 25 | 30, 35 | 40, 45 | 50+ |
|---|---|---|---|---|---|
| Active tiers | T1 T2 | T1 T2 T3 | T2 T3 T4 | T3 T4 T5 | T4 T5 |

"Peak" in the tables below = the count asked at the tier's peak.

## 2. Tables

### T1: meat (reference: raw beef = 1.0; Qmax 24)
| God | Item id(s) | m | Peak |
|---|---|---|---|
| Iron Aurochs | `minecraft:beef` | 1.0 | 24 |
| Sunblessed Ram | `minecraft:mutton` | 1.0 | 24 |
| Pale Stag | `naturalist:venison` | 0.8 | 20 |
| Deep Boar | `minecraft:porkchop` | 1.0 | 24 |
| Marsh Harrier | `minecraft:chicken`, `naturalist:duck` | 0.9 | 22 |
| Silt Dredger | `minecraft:cod`, `naturalist:catfish` | 0.75 | 18 |
| Silver Current | `minecraft:salmon`, `naturalist:bass` | 0.75 | 18 |
| Carrion Crow | `minecraft:rotten_flesh`, plus any raw meat at Simple Spoilage's Rotting stage | 1.0 | 24 |
| Sun-Scale | `naturalist:lizard_tail` | 0.6 | 15 |

### T2: crop / basic material (reference: red sand = 1.0; Qmax 128)
| God | Crop (m, peak) | Basic material (m, peak) |
|---|---|---|
| Iron Aurochs | `minecraft:wheat` 0.5, 64 | `minecraft:leather` 0.17, 22 |
| Sunblessed Ram | `farm_and_charm:barley` 0.5, 64 | `#minecraft:wool` 0.5, 64 |
| Pale Stag | `minecraft:sweet_berries` 0.5, 64 | `#minecraft:logs` 0.5, 64 |
| Deep Boar | `minecraft:potato` 0.5, 64 | `minecraft:coal` 0.35, 45 |
| Marsh Harrier | `minecraft:sugar_cane` 0.5, 64 | `minecraft:feather` 0.2, 26 |
| Silt Dredger | `minecraft:kelp` 0.5, 64 | `minecraft:clay_ball` 0.5, 64 |
| Silver Current | `minecraft:beetroot` 0.45, 58 | `minecraft:ice` 0.33, 43 |
| Carrion Crow | `minecraft:glow_berries` 0.33, 43 | `minecraft:bone` 0.2, 26 |
| Sun-Scale | `minecraft:cactus` 0.5, 64 | `minecraft:red_sand` 1.0, 128 |

### T3: processed material / simple meal (reference: farmer's bread = 1.0; Qmax 90)
| God | Material (m, peak) | Simple meal (m, peak) |
|---|---|---|
| Iron Aurochs | `createbigcannons:cast_iron_ingot` 0.09, 9 | `farm_and_charm:goulash` 0.07, 7 |
| Sunblessed Ram | `create:brass_ingot` 0.1, 9 | `farm_and_charm:barley_soup` 0.18, 17 |
| Pale Stag | `create:andesite_alloy` 0.35, 32 | `farm_and_charm:oatmeal_with_strawberries` 0.35, 32 |
| Deep Boar | `createbigcannons:steel_ingot` 0.1, 9 | `farm_and_charm:bacon_with_eggs` 0.45, 41 |
| Marsh Harrier | `createbigcannons:hardened_nitro` 0.045, 5 | `farm_and_charm:oat_pancake` 0.3, 27 |
| Silt Dredger | `createbigcannons:steel_ingot` 0.1, 9 | `farm_and_charm:simple_tomato_soup` 0.35, 32 |
| Silver Current | `create:sturdy_sheet` 0.05, 5 | `farm_and_charm:potato_soup` 0.45, 41 |
| Carrion Crow | `create:blaze_cake` 0.05, 5 | `farm_and_charm:farmers_bread` 1.0, 90 |
| Sun-Scale | `minecraft:tinted_glass` 0.07, 7 | `farm_and_charm:roasted_corn` 0.35, 32 |

### T4: rare / extra rare (reference: blaze rod = 1.0; Qmax 16)
| God | Rare (m, peak, from D) | Extra rare (m, peak, from D) |
|---|---|---|
| Iron Aurochs | `minecraft:blaze_rod` 1.0, 16, D 29 | `minecraft:breeze_rod` 0.5, 8, D 30 |
| Sunblessed Ram | `minecraft:emerald` 0.5, 8, D 30 | `minecraft:nether_star` 0.025, 1, D 55 |
| Pale Stag | `minecraft:rabbit_foot` 0.65, 11, D 29 | `minecraft:sniffer_egg` 0.1, 2, D 34 |
| Deep Boar | `minecraft:diamond` 0.35, 6, D 30 | `minecraft:netherite_scrap` 0.08, 2, D 35 |
| Marsh Harrier | `minecraft:phantom_membrane` 0.5, 8, D 30 | `minecraft:shulker_shell` 0.25, 4, D 31 |
| Silt Dredger | `minecraft:nautilus_shell` 0.25, 4, D 31 | `minecraft:heart_of_the_sea` 0.03, 1, D 47 |
| Silver Current | `minecraft:prismarine_crystals` 0.65, 11, D 29 | `minecraft:dragon_breath` 0.15, 3, D 32 |
| Carrion Crow | `minecraft:echo_shard` 0.07, 2, D 36 | `frontier:squelching_heart` 0.03, 1, D 47 |
| Sun-Scale | `#minecraft:decorated_pot_sherds` 0.1, 2, D 34 | `minecraft:wither_skeleton_skull` 0.025, 1, D 55 |

Before an option's "from D", the fallback rule in §1.1 applies (e.g. the Crow at D 30 asks for 1 echo shard).

### T5: advanced material / advanced meal (reference: roasted chicken = 1.0; Qmax 48)
| God | Material (m, peak) | Advanced meal (m, peak) |
|---|---|---|
| Iron Aurochs | `createbigcannons:nethersteel_ingot` 0.12, 6 | `farm_and_charm:beef_patty_with_vegetables` 0.5, 24 |
| Sunblessed Ram | `create:electron_tube` 0.17, 9 | `farm_and_charm:baked_lamb_ham` 0.5, 24 |
| Pale Stag | fluid `createdieselgenerators:biodiesel`, 1000 mB units, 0.5, 24 | `farm_and_charm:stuffed_rabbit` 0.4, 20 |
| Deep Boar | `aeronautics:pearlescent_levitite` 0.1, 5 | `farm_and_charm:farmers_breakfast` 0.35, 17 |
| Marsh Harrier | `aeronautics:levitite` 0.11, 6 | `farm_and_charm:stuffed_chicken` 0.5, 24 |
| Silt Dredger | fluid `createdieselgenerators:gasoline`, 1000 mB units, 0.22, 11 | `farm_and_charm:chicken_wrapped_in_bacon` 0.4, 20 |
| Silver Current | `create:precision_mechanism` 0.08, 4 | `farm_and_charm:lamb_with_corn` 0.35, 17 |
| Carrion Crow | `createbigcannons:bronze_ingot` 0.33, 16 | `farm_and_charm:roasted_chicken` 1.0, 48 |
| Sun-Scale | fluid `createdieselgenerators:diesel`, 1000 mB units, 0.22, 11 | `farm_and_charm:grandmothers_strawberry_cake` 0.85, 41 |

**What the T5 rebalance does:** the meal and material options of each god now cost about the same effort. Cheap meals (roasted chicken, cake, stuffed chicken) are asked for in larger numbers, instead of being a 3× discount whenever the meal option rolls.

## 3. Supporting changes
- **Levitite drops:** Empire ships loot tables for `aeronautics:levitite` and `aeronautics:pearlescent_levitite`.
  - The block drops itself only when mined with a Silk Touch pickaxe of diamond tier or better.
  - Tier is checked with `isCorrectToolForDrops` against `minecraft:needs_diamond_tool`, so modded pickaxes of that tier work too.
- **Confirmed by Bright:**
  - The End is reachable.
  - The Nether is expected from Tier 3.
  - Bone meal exists (it just grows nothing), so CDG ethanol fermenting works.
  - The non-renewable echo shards and pottery sherds stay, at lower multipliers.
- **Shared chains:**
  - Dredger and Sun-Scale both need crude oil (gasoline and diesel come out of the same distillation, 1 : 1).
  - Boar and Dredger share steel.
  - Nethersteel uses netherite scrap, the Boar's extra-rare item.
- **To verify in play:** Naturalist drop rates for venison and lizard tails; the stack sizes of Farm & Charm's placeable meals (a count of 41 cakes is impractical if they don't stack).
