# Frontier: civilization tiers, incursions and survival direction

Status: design settled enough to build (2026-09-25). See `claude/frontier-plan.md` for the implementation plan. Nothing is implemented yet.

## Pack direction this sits in
- **Endgame shape: "Reach".** Progress is the set of places you can keep an operation running: farther, deeper, harsher. There's no single win condition or tech-tree gate.
- **Regional specialisation.** Biomes differ in ore deposit size (done), crop seasons (Serene Seasons fertility) and forests (Dynamic Trees), so friends' bases specialise and trade.
- **Difficulty is local.** It comes from where you go and what you do, not from server-wide milestones. The wild slowly gets harder as time passes.
- **Survival mods chosen:**
  - Serene Seasons, plus a compat mod with PMWeather
  - Dynamic Trees
  - Cold Sweat (must be configurable enough)
  - Diet – AppleSeed Edition, tuned mild
  - PMWeather + PMShaders + PMWeather Aeronautics compat
  - Simple Voice Chat
  - a gravestone mod
  - Attract to Sound
  - no minimap
- **Weather:** storms should never break blocks (or only rarely), but can wreck airships that aren't tied down.
- **Food spoilage is our own**, in the core mod, and mild:
  - Food spoils slowly.
  - Spoiled food is weaker and gives brief Hunger with a chance of Poison.
  - An ice chest greatly slows spoiling.
  - Freshness should be rounded into steps so stacks still merge.

## Tiers (Overworld)
| Tier | Where | Rules |
|---|---|---|
| Frontier | Everything below y0; anything far from civilisation | Very high hostile/neutral spawn rates near players, especially dangerous enemies, all neutral mobs permanently hostile. Spawns ignore light below y0; above y0 only daylight counts (block light is ignored). |
| Uninhabited | Within 8 subchunks of Settled/Civilized land; y0–54 is at best Uninhabited | Vanilla rules |
| Settled | Inhabited land (see below) | No daytime hostiles (polar bears, Naturalist predators); night surface hostiles at 1/2 rate; no creepers; lesser incursions |
| Civilized | Within 4 subchunks of 5+ free-roaming guards | No hostile spawns; passive spawns ×2; permanent Regeneration I; greater incursions |

- Villages, illager outposts, colonies and player bases are at least Settled.
- **Other dimensions are Uninhabited everywhere.** Neutral mobs are only permanently provoked in the Overworld.
- Below y0 is always dangerous (a hard rule, by choice).
- **Routes stay wild** unless very heavily used. Players can travel by day to be safe.

### Settled requirements
- Needs a bed, a lantern or a workstation, **and** enough inhabited time (about 1–2 in-game days).
- Inhabited time counts **players, villagers and MineColonies colonists**, which is our own counter, not vanilla's player-only `inhabitedTime`.
- **Land next to Settled land settles 4× as fast** (in 0.25 of the time), so well-used travel points become settled.
- The inhabitation must be recent: land with no recent inhabitants falls back to Uninhabited or Frontier.
- **Guards** (iron golems, Guard Villagers, MineColonies guards; to be a tag) only count if they're free to move, not penned.

### Torch burnout (mild upkeep)
- Outside Settled land, torches burn out after about a day and become a **dry torch**. Right-click it to relight it.
- Lanterns don't burn out.

## Incursions
- **Faction: Deeper and Darker's sculk creatures.** They come from below: the Deep answering industry and vibration.
- They spawn at night on the far edges of Settled or Civilized land.
  - They kill all passive mobs.
  - When not chasing something, they slowly hunt the player.
- **Lesser** incursions hit Settled land and are small and manageable. **Greater** incursions hit Civilized land and are long and brutal, like a blood moon.
- **Thumper triggers**, counted over the **3×3 chunks** around the thumper:
  - Long-range thumper shots can spawn Deeper and Darker mobs.
  - A mechanical thumper fired 5× within a minute summons a **lesser** incursion.
  - A combustive thumper fired 5× within a minute has a chance to summon a **greater** incursion.
- **Stirred deposits:** deposits that echo a long-range shot are stirred for a while, and Deep mobs spawn around them.
- Thumpers set off **sculk sensors, shriekers and Wardens** within range. The core mod does this itself, because vanilla sensors only hear 8 blocks.
- **Born in Chaos** is being considered as a Frontier-only spawn roster (undecided).
