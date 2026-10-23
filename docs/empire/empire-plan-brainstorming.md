<!-- Edited by Claude (2026-10-23): Pass 1 brought in line with what Bright decided and what is built; marked "Updated". -->
# Engines and Empires: "Empires" Conceptual Design & Architecture Specification

**Date:** 2026-10-20  
**Target Environment:** NeoForge 1.21.1, Java 21 (`engines_and_empires` post-split architecture)[cite: 5]  
**Status:** Conceptual & Systems Planning (Passes 1 & 2 Locked; AI Task Architecture Defined)  
**Companion Docs:** `empires-brainstorm.md`[cite: 1], `frontier-plan.md`[cite: 8], `mod-split-plan.md`[cite: 4, 5]

---

## 1. Executive Summary & Architectural Placement

**MineColonies** is being removed following the 8-mod split (`paradyne_lib`, `sable_fixes`, `sparse_ores`, `seismic_surveyance`, `meteodyne`, `frontier`, `simple_spoilage`, `engines_and_empires`)[cite: 1, 4, 5]. In its place, **Empires** serves as our native human, territorial, and logistical layer[cite: 1].

### Core Design Pillars
1. **People, Infrastructure, and Supply Webs:** An empire is defined by distinct individuals, physical infrastructure (heated rooms, pub, storage, rail networks), and regional specialization[cite: 1].
2. **Zero Micromanagement:** No complex GUI trees or per-citizen hand-holding[cite: 1]. Civilians interact organically with existing pack systems (`Frontier` habitation/guards/incursions[cite: 3], `Meteodyne` indoor heating/weather[cite: 9, 11], `Simple Spoilage` food freshness[cite: 15], `Encumbered` item weights[cite: 5, 6], and `Create` contraptions/trains[cite: 1]).
3. **One Capital Settlement per Empire:** Each empire has **exactly one main Settlement** (anchored by a 3×3×3 Bonfire) and optional satellite **Outposts** (established via a separate non-bonfire mechanic, to be specified later)[cite: 1].
4. **House Code Style:** All core rules (Dominion sacrifice curves, civilian schedule generation, utility task scoring) live in pure, unit-testable Java classes with zero Minecraft imports, wrapped by thin NeoForge event/entity adapters[cite: 3].

---

## 2. Pass 1: Founding, Staking, Dominion & The Bonfire Pantheon

### 2.1 Founding a Settlement
* **The Bonfire Block:** A **3×3×3 ornate campfire multiblock/structure**.
* **Settled Precondition (Updated):** The Bonfire's own chunk must be **`Settled` or `Civilized`** in `Frontier` when it is first lit and when the empire is founded. Placing it is not checked, so a bonfire placed early isn't wasted. None of the 3×3 chunks around it may belong to another empire[cite: 3].
* **Founding Ritual (Updated):** The player offers **5× of one god's raw meat** (its Tier 1 item) to the lit Bonfire.
  * The type of raw meat offered permanently selects the empire's initial **Patron Totem God** (see §2.4).
  * **Initial Territory & Dominion:** Founding immediately grants the empire a **Dominion ($D$) score of 10** and automatically stakes the **3×3 chunk grid (9 chunks)** centered on the Bonfire, leaving **1 free Dominion point** available to stake immediately.
* **System Hooks:**
  * **Frontier:** The Bonfire acts as a high-value `settles` anchor[cite: 3].
  * **Meteodyne (`HeatWorld`):** Acts as a major heat source (higher power and radiant share than a standard campfire's Power 1 / Radiant 0.5 / Max +45°C), serving as the thermal heart of an early camp[cite: 9].
  * **Simple Spoilage:** Sacrificed meats must be fresh (not spoiled/rotting), except for offerings to the Rotten Flesh god[cite: 15].

### 2.2 Staking & Un-Staking Land (Talc Mechanics)
* **Dominion ($D$):** Belongs to the Empire as a whole. An empire with Dominion $D$ can hold at most $D$ staked chunks.
* **Verticality:** Staking claims the **entire 16×16 vertical chunk column** from bedrock to build height.
* **Staking (Blessed Talc):**
  * **Acquisition:** Mine **Talc Ore** blocks for **Raw Talc**, then right-click an active **Bonfire** with Raw Talc to create **Blessed Talc**.
  * **Application:** Right-click a block in an unstaked chunk with Blessed Talc. Consumes 1 Blessed Talc and stakes the chunk into the Empire, using 1 available Dominion point.
  * **Strict Contiguity & Tier Preconditions:**
    1. The target chunk **must be cardinally adjacent** (North, South, East, or West—no diagonals) to an already-staked chunk of the settlement.
    2. The target chunk **must already be `Settled` or `Civilized`** in `Frontier` (at least one subchunk in the column). If it is `Uninhabited` or `Frontier`, staking fails without consuming the item and displays an indicator message to the player that the chunk is not settled yet[cite: 3].
* **Voluntary Un-Staking (Tarnished Talc):**
  * **Acquisition:** Right-click **Soul Fire** or a **Soul Campfire** with Raw Talc to create **Tarnished Talc**.
  * **Application:** Right-click a block in a staked chunk with Tarnished Talc. Consumes the item and removes the chunk's stake.
  * **Dawn Cooldown:** A voluntarily un-staked Dominion point becomes **dormant** and does not become available to re-stake until the **next dawn**.
  * **Core & Bridge Protection:** Tarnished Talc **cannot** be used on the Bonfire's own chunk, nor can it be used on any "bridge" chunk whose removal would split the settlement's contiguous staked chunks into disconnected islands (enforced via a 2D cardinal graph connectivity check).
* **Forcible De-Staking:**
  * If a chunk is forcibly de-staked (exact triggers TBD in a later pass), **that Dominion point is lost indefinitely** (reducing the empire's current Dominion $D$ by 1).

### 2.3 Continuous Sacrifice Scaling & Sliding Material Tiers
> **Updated:** the tier bands, Qmax values and each god's items below are superseded by `docs/empire/sacrifice-tables-draft.md` (built 2026-10-22). Each sacrifice is now a rotating demand: one item per active tier, from two options per god. The pack uses Farm & Charm instead of Farmer's Delight.

More Dominion is acquired by offering further sacrifices at the Bonfire. Each completed sacrifice after founding grants **+5 Dominion**.
* **Rubber-Banding Protection:** Sacrifice requirements are a function of **current Dominion $D$ (active + dormant)**, *not* total historical sacrifices. An empire that forcibly loses Dominion drops back down the cost curve rather than paying inflated prices to recover lost land.
* **5 Material Tiers (Sliding Window of 2–3 Active Tiers):**
  Instead of discrete step brackets or accumulating all 5 tiers at once, sacrifice requirements use **continuous ramp-and-taper functions** so that a settlement at $D = 14$ pays slightly less than a settlement at $D = 15$, and only **2 to 3 material tiers are active simultaneously** at any given $D$:
  * **Tier 1:** Raw Meat (chooses/feeds the god; active from $D = 0$, peaks around $D = 14$, phases out by $D \approx 22$).
  * **Tier 2:** Farmable Vanilla Materials (wheat, bones, feathers, mushrooms, logs, ice, kelp, wool, cactus; ramps in after $D = 9$, peaks around $D = 20$, phases out by $D \approx 32$).
  * **Tier 3:** Processed Materials & Meals (iron/copper ingots, glass, books, andesite alloy, Farmer's Delight meals; ramps in around $D = 13$, peaks around $D = 25$, phases out by $D \approx 38$).
  * **Tier 4:** Fine Unprocessed Materials (gemstones, wither skeleton skulls, squelching hearts[cite: 3, 6], echo shards, rare non-crafted drops; ramps in around $D = 22$ and scales continuously).
  * **Tier 5:** Advanced Processed Materials (high-end Create mechanisms/brass/sheets, lavish Farmer's Delight feasts, etc.; ramps in around $D = 28$ and scales continuously).

#### Reference Continuous Formula (Pure Java)
For each tier $i \in \{1..5\}$ with parameters $(D_{\text{start}, i}, D_{\text{peak}, i}, D_{\text{end}, i}, Q_{\text{max}, i}, p_i)$:

$$Q_i(D) = \begin{cases} 0 & \text{if } D \le D_{\text{start}, i} \text{ or } D \ge D_{\text{end}, i} \\ \left\lceil Q_{\text{max}, i} \cdot \left(\frac{D - D_{\text{start}, i}}{D_{\text{peak}, i} - D_{\text{start}, i}}\right)^{p_i} \right\rceil & \text{if } D_{\text{start}, i} < D \le D_{\text{peak}, i} \\ \left\lceil Q_{\text{max}, i} \cdot \left(\frac{D_{\text{end}, i} - D}{D_{\text{end}, i} - D_{\text{peak}, i}}\right) \right\rceil & \text{if } D_{\text{peak}, i} < D < D_{\text{end}, i} \end{cases}$$

*(For Tiers 4 and 5, $D_{\text{end}} = \infty$, so they continue scaling past $D_{\text{peak}}$).*

### 2.4 The 9 Totem Gods, Satiety Upkeep & Exclusivity
The pantheon is restricted to **9 specific meat groups**:

| # | Founding Meat(s) | Totem God / Archetype | Lore & Domain Notes |
| :--- | :--- | :--- | :--- |
| **1** | **Beef** | The Iron Aurochs / Ox | Brawn, draft, heavy industry, Temperate Plains. |
| **2** | **Mutton** | **The Sunblessed Ram** *(Lop)* | Crag Ram[cite: 2]; warmth, high crags, cosmic envoy of The Aldered One (Gugmik) added to the Compact via late addendum[cite: 2]. |
| **3** | **Venison** | The Great / Pale Stag | Forests, wind, ways, frontier scouting[cite: 2]. |
| **4** | **Pork** | The Deep Boar / Iron Tusk | Earthworks, roots, delving, seismic stability[cite: 2]. |
| **5** | **Chicken / Duck** | The Marsh Harrier / Fowl | Wetlands, sky, wind, precision crafts. |
| **6** | **Cod / Catfish** | The Silt Dredger | Coastlines, muddy rivers, dredging, brine. |
| **7** | **Salmon / Bass** | The Silver Current / Leviathan | Cold rivers, preservation, frost/hydro power[cite: 2]. |
| **8** | **Rotten Flesh** | The Carrion Crow / Crown | Underground, sculk, night-watch; accepts spoiled meat[cite: 2]. |
| **9** | **Lizard Tail** | The Sun-Scale | Deserts, badlands, extreme heat, gold/brimstone. |

* **Server Exclusivity Config:** An optional server config (`uniqueGodsPerServer = true/false`, intended for small multiplayer servers and disabled on large servers) restricts each god to **at most 1 empire at a time**.
* **God Satiety & Abandonment:**
  * Gods require **regular sacrifices** (even smaller Tier 1 / Tier 2 offerings) to stay **Sated**.
  * If an empire ignores its god for too long, the god **leaves the settlement**.
  * **Consequences of Losing a God:** The empire **keeps its current Dominion ($D$)** and staked chunks, but loses all active God boons and cannot sacrifice for additional Dominion until a god is present.
  * **Re-Covenant or Switching Gods:** Once a settlement is godless, players can offer sacrifices to welcome their old god back **or** offer a different meat to adopt a **new god** (freeing the previous god for other empires if the exclusivity config is enabled).

---

## 3. Pass 2: Civilians — Housing, Ability Scores, Skills & Fulfilment

### 3.1 Population & Housing Rules (Q4)
* **Settlement Population:** Typically 5–25 civilians (~20 standard), soft-capped by settlement specialization and Larder food surplus[cite: 1].
* **The First 3 Civilians (Pioneer Grace):** Civilians 1, 2, and 3 **do not require beds or houses**; they can sleep rough around the Bonfire.
* **The 4th Civilian Threshold:** To recruit a **4th civilian** (and for every civilian thereafter), **all existing civilians (1–3) plus the new 4th civilian** must each have a claimed, housed bed.
* **Valid Room Requirements:**
  1. Must be an enclosed indoor room recognized by `Meteodyne`'s `HeatWorld` (sheltered and within livable temperature bounds)[cite: 6, 9].
  2. **Minimum Floor Space:** The room must provide at least **25 walkable shared floor blocks per person** living in that room (`walkableFloorBlocks >= bedsInRoom * 25`).

### 3.2 The 6 Ability Scores & Learning by Doing (Q5)
* **Learning by Doing:** Anyone can do any job[cite: 1]. Workers start at low skill (slow movement/work, dropping harvested items, forgetting to replant seeds) and gain skill XP through repetition[cite: 1].
* **Soft Scaling, No Hard Caps:** Any civilian can reach maximum skill level (e.g., 100) in any job regardless of their ability scores[cite: 1]. However, ability scores scale **both** skill-learning speed **and** final job throughput—so a high-Endurance master farmer is ultimately more productive than a low-Endurance master farmer.
* **The 6 Ability Scores (3-Pillar Effect Matrix):**
  Every civilian rolls 6 ability scores (**Endurance, Precision, Willpower, Wits, Organization, Teamwork**). Each score affects:
  1. **Labor** (work output/efficiency)
  2. **Social Behavior** (outward effect on coworkers/settlement)
  3. **Fulfilment** (inward morale resilience)

| Ability Score | Primary Associated Jobs[cite: 1] | 1. Labor Effect (Work & Output) | 2. Social Behavior (Outward) | 3. Fulfilment Effect (Inward) |
| :--- | :--- | :--- | :--- | :--- |
| **Endurance** | Farming, Mining, Hauling, Hand Cranks, Melee Guards | Faster heavy/arduous labor, higher carry weight (`Encumbered` kpg), longer shifts before tiring. | Less likely to complain when fatigued, hungry, or working grueling stations (hand cranks). | Better at ignoring others' complaining; loses less Fulfilment from harsh weather or basic gear. |
| **Precision** | Refining, Machine Operation, Crop Care, Marksmen, Medics | Drastically reduces rookie fumbles (dropped items, missed replants); boosts fine station speed & ranged accuracy. | Keeps workplaces tidy (sweeps up stray dropped items nearby that would annoy peers). | Gains bonus Fulfilment from quality tools/gear; loses less Fulfilment when learning a brand-new job. |
| **Willpower** | Guards/Soldiers, Thumper Crews, Frontier Pioneers | Maintains work speed even while `Grumbling` or in storms; higher combat threat threshold & slower Downed bleed-out. | Does not spread panic during incursions; steadies nearby civilians so they retreat orderly. | Slower Fulfilment decay during crises (low settlement protection, active incursions, or mourning a death). |
| **Wits** | Train Scheduling, Large Builds, Surveying, Apothecary | Global multiplier to **skill-learning speed** across all jobs; operates complex Create blocks more efficiently. | Shares "shop talk" during Pub downtime, granting a small skill XP boost to lower-level peers in the same job. | Gains more Fulfilment from downtime and varied diets (`Diet`); less Fulfilment drain from working a non-preferred job. |
| **Organization** | Warehouse Management, Hauling, Stationmasters | Smarter routing and batching; moves more items per trip, sorts inventories faster, wastes less time between tasks. | Coordinates smoothly at shared containers/stations, preventing congestion when multiple workers share storage. | Gains Fulfilment from stocked larders and orderly schedules; less bothered by long commutes to work. |
| **Teamwork** | Multi-worker Farms, Guard Squads, Refineries, Build Crews | Scales the **multi-worker efficiency bonus** when 2+ civilians share a job; softens under-staffed crew penalties. | Actively boosts the mood of nearby coworkers and Pub patrons, pulling `Grumbling` peers back toward `Content`. | Gains bonus Fulfilment when working alongside others or sharing a multi-bed barracks (dislikes working solo). |

### 3.3 Fulfilment States & Leaving (Q6)
* **Fulfilment Inputs:** Liked jobs, favourite foods & food freshness (`Simple Spoilage`), downtime, settlement protection (`Frontier` tier), housing quality, and equipment[cite: 1, 3, 15].
* **4 Fulfilment Brackets (0–100%):**
  1. **Thriving (80–100%):** +25% skill XP gain, +15% work/move speed, counts toward unlocking settlement specialization perks[cite: 1].
  2. **Content (40–79%):** Baseline 1.0× work speed and learning rate.
  3. **Grumbling (15–39%):** -25% work speed, -50% skill XP gain, takes longer Pub breaks, audibly/visually complains (especially on hand cranks)[cite: 1].
  4. **Packing (0–14%):** Stops working, packs bags, and waits at bed/Pub. If Fulfilment does not recover above 20% within 1 in-game day, the civilian **leaves the settlement** (and may reappear later in the global Pub visitor pool)[cite: 1].

### 3.4 Combat, Retreat Chain, Downed State & Death (Q7 & Q9)
* **Durability:** Civilians have high base HP and can equip any armor/weapons[cite: 1].
* **Non-Combatant Retreat Chain (Q9):**
  When threatened by hostiles, a non-guard civilian evaluates whether they can win (based on HP, gear, enemy strength, and **Willpower**). If they think they cannot win:
  1. **Seek Defense:** Pathfind toward the nearest active **Guard**.
  2. **Seek Home:** If no Guard is available, pathfind to their **claimed Home Bed** (indoors).
  3. **Seek Bonfire:** If homeless (Civilians 1–3), pathfind to the **Settlement Bonfire**.
  4. **Cornered Override:** If pathfinding to safety is blocked/impossible, they **fight back regardless** until the threat dies or they are Downed.
* **Downed & Revivable State (Q7):**
  * At 0 HP, a civilian enters a **Downed** state for a set bleed-out window (e.g., 3 minutes, extended by **Willpower**, paused if chunks unload)[cite: 1].
  * **Revival:** Any player (using bandages, healing potions, golden apples, or hearty meals) or an assigned civilian **Medic** can revive them[cite: 1].
  * **Permanent Death:** If the timer expires or hostiles finish them off uncontested, they die permanently[cite: 1]. The empire loses the person and all invested skills[cite: 1], `Frontier` deducts 1 day of habitation from nearby sections[cite: 3], and surviving civilians take a temporary **Mourning** Fulfilment penalty.

### 3.5 Recruiting at the Pub (Q8)
* **Pub Structure:** Travellers visit the settlement's **Pub structure** periodically[cite: 1].
* **Progressive Difficulty:**
  * **First 3–5 Recruits:** Easy to persuade (low demands, no bed required for the first 3, satisfied with basic food and a `Settled` campfire)[cite: 1, 3].
  * **Later Recruits:** Check the settlement's current headcount, average Fulfilment, `Civilized` tier protection, valid housed bed availability ($\ge 25$ floor blocks/person), and require tailored persuasion offerings (fresh favorite meals, equipment, or preferred workplaces)[cite: 1, 3, 15].

---

## 4. Civilian AI Architecture: Schedules, Chores, Utility Tasks & Atomic Actions

To keep civilian AI expressive, fast, and unit-testable without MineColonies-style state-machine spaghetti, civilian behavior is structured as a **4-Layer Pipeline**[cite: 1].

### 4.1 Layer 1: Daily Schedule (Periods & Chores)
* **Dawn Generation:** At dawn (`timeOfDay == 0`), each civilian constructs a daily schedule composed of time **Periods**, each assigned to a high-level **Chore**:
  * `WORK_JOB` (Morning & Afternoon shifts)
  * `EAT_AND_DRINK` (Midday & Evening meals from the Larder/Pub)
  * `LEISURE` (Pub socializing, resting, shop talk)
  * `SLEEP` (Returning to claimed bed or Bonfire)
* **Individual Shaping:** **Endurance** lengthens `WORK_JOB` periods; low Fulfilment (`Grumbling`) expands `LEISURE` periods.
* **Dynamic Interrupts:** External events splice temporary override Periods (`FLEE_OR_FIGHT`, `RALLY_DEFENSE`, `SEEK_SHELTER`) into the schedule when an Incursion starts[cite: 3] or severe `Meteodyne` weather (hail/blizzard) hits[cite: 11, 13].

### 4.2 Layer 2: Utility Task Selection (Weighted Priority Scoring)
During an active Period, the civilian evaluates the candidate **Tasks** associated with that Period's Chore (e.g., for `WORK_JOB: Farmer`: `FetchToolTask`, `DumpInventoryTask`, `HarvestAndReplantTask`, `PickupStrayDropsTask`, `ComplainTask`).
* Instead of a rigid `if/else` checklist, every candidate task $T$ computes a continuous **Utility Score** in pure Java:

$$\text{Utility}(T) = \text{Gate}(T) \times \left( \text{BaseWeight}(T) \times \text{UrgencyCurve}(T) + \text{MomentumBonus}(T) \right)$$

* **Gate ($0$ or $1$):** Fast boolean precondition (e.g., `DumpInventoryTask` is $0$ if carrying $0$ crops; `HarvestTask` is $0$ if no ripe crops exist).
* **Urgency Curve ($0.0 \to 1.0+$):** Continuous urgency scaling. For example, as inventory fullness $F \in [0, 1]$ approaches $1.0$, `DumpInventoryTask` scales via $F^3$, smoothly overtaking `HarvestAndReplantTask` when the civilian's pockets are nearly full—or immediately winning if no ripe crops remain ($R = 0$) so the worker puts away partial harvests instead of idling with full hands.
* **Momentum Bonus (+15–20):** Applied to whichever task is currently active until its natural loop terminates, preventing indecisive jittering at threshold boundaries.

### 4.3 Layer 3: Tasks as Condition-Driven Action Generators
A **Task** represents a complete multi-step goal (e.g., *"Harvest and replant crops until inventory is full or no ripe crops remain"*).
* While active, the Task picks one target at a time (with target-selection order improved by **Organization**) and emits a short queue of **Atomic Actions** (Layer 4).
* Utility scores are **only re-evaluated when an atomic action sequence finishes** (every few seconds) or when an external interrupt fires—resulting in near-zero per-tick CPU cost.

### 4.4 Layer 4: The Atomic Action Primitives
All Tasks across all jobs compile down to ~10 reusable Minecraft primitives:
1. **`MoveTo(BlockPos, reach, speedMod)`** — Pathfinding wrapper with stuck-timeout handling; speed scaled by Fulfilment & **Endurance**.
2. **`Equip(ItemPredicate)`** — Moves required tool/seed/weapon into mainhand/offhand.
3. **`BreakBlock(BlockPos, useTool)`** — Harvests/mines block and awards job skill XP. Speed scaled by Skill + **Endurance/Precision**. Low skill + low **Precision** rolls a **rookie fumble check** (dropping the item on the ground instead of into inventory)[cite: 1].
4. **`PlaceBlock(BlockPos, ItemPredicate)`** — Plants seed or places block. Low skill + low **Precision** rolls a chance to **forget to replant**[cite: 1].
5. **`InteractBlock(BlockPos)`** — Right-clicks block (doors, levers, berry bushes).
6. **`OperateStation(BlockPos, ticks)`** — Channels work at a Create block or workstation (hand crank, basin, stove, schematicannon)[cite: 1].
7. **`TransferItems(BlockPos, filter, mode)`** — Uses NeoForge `Capabilities.ItemHandler.BLOCK` to insert/extract at chests, `IceChestBlock`[cite: 6, 15], or Create depots/vaults; batch speed scaled by **Organization**.
8. **`ConsumeItem(slot)`** — Eats/drinks item, applying `Simple Spoilage` freshness and `Diet` effects to Fulfilment[cite: 6, 15].
9. **`SocializeOrEmote(target, emoteType, ticks)`** — Pub chatting (**Wits** XP sharing, **Teamwork** mood aura) or workplace grumbling (**Endurance** resistance)[cite: 1].
10. **`CombatEngage(Entity)`** — Melee or ranged attack execution.

---

## 5. Open Questions & Next Planning Passes

The following questions remain open for upcoming planning sessions:

### Pass 3A — Structures, Jobs & Workplace Targeting
1. **Structure Definitions:** What defines a valid **Pub** or **Workplace** structure? (e.g., An enclosed `HeatWorld` room containing specific tagged furniture/workstation blocks, similar to how housing checks beds + 25 floor blocks?)
2. **Job Target Binding:** How does a civilian know *which* farmland plot, *which* storage chests, or *which* Create machines belong to their job? (Automatic scan within staked empire chunks around a job block, or linked via a player ledger/clipboard item?)
3. **Full Job Roster & Guard Behavior:**
   * What is the complete v1 list of jobs (Farmer, Hauler/Warehouse, Hand-Crank Laborer, Miner/Thumper Crew, Engineer/Refiner, Guard/Soldier, Medic, Barkeep)?
   * How are Guards controlled by the player (autonomous patrol vs. rally banners / command horns / squad following)[cite: 1]?
4. **Schedule Customization:** Is the daily schedule fixed (Dawn setup: Morning Work $\rightarrow$ Midday Meal $\rightarrow$ Afternoon Work $\rightarrow$ Evening Pub $\rightarrow$ Night Sleep), or can players assign night shifts (e.g., nocturnal guards or 24-hour rotating factory crews)?

### Pass 3B — God Boons, Specialization & "Reach" Progression
5. **The 9 God Boons:** What specific passive/active blessings does each of the 9 Totem Gods grant to an empire, and do those boons scale with current Dominion ($D$) or Satiety?
6. **Settlement Specialization Mechanics (Brainstorm Q10–12):**
   * How are systematic settlement perks unlocked (number of workers in a job, sum of job skill levels, local biome tags, or combinations)[cite: 1]?
   * What mechanically prevents players from stacking all 20 civilians into a single "best" job[cite: 1]?
7. **Intertwining Empires with "Reach" (Brainstorm Q11):** How do trained civilians, soldiers, and specialized settlements concretely enable operating farther, deeper ($Y < 0$), and in harsher `Meteodyne` climate bands[cite: 1, 8]?

### Pass 3C — Outposts & Forcible De-Staking
8. **Outpost Mechanics (Brainstorm Q3):**
   * What block/ritual establishes an **Outpost** (since Bonfires are strictly 1 per empire for the main settlement)?
   * How many chunks and civilians can an Outpost hold, and does it draw from the Empire's main Dominion pool?
9. **Forcible De-Staking Triggers:** What exact events strip a staked chunk and permanently destroy 1 Dominion point? (e.g., `Frontier` habitation decaying below `Settled` for $X$ days[cite: 3], losing an Incursion on an edge chunk[cite: 3], or enemy faction sabotage?)

### Pass 4 — Create Integration, Chunk Loading & Low-LOD Simulation
10. **Chunk Loading Rules (Brainstorm Q13–15):**
    * While at least one player in the empire is online, which chunks stay force-loaded (all staked chunks, only chunks with active civilians/machines, or dynamic ticket loading for approaching Create trains)[cite: 1]?
    * How does Low-LOD simulation (when an empire player is online, but no player is within entity-rendering distance of the settlement) execute civilian tasks and fake crop growth[cite: 1]?
11. **Lore, Relics & Factions (Later):**
    * How do **Alderstone Ruins** (Chapels, Estates, Mausoleums), **Bonfire Whispers**, and **Relics** of *The Aldered One (Gugmik)* and *Lop (The Sunblessed Ram)* hook into the Bonfire and `Seismic Surveyance`[cite: 2, 6]?
    * How much of the opposing Factions (Sculk[cite: 1, 3], plus future Undead, Spirit, Goblin[cite: 1, 10]) needs interface hooks before v1 player empires are built[cite: 1]?