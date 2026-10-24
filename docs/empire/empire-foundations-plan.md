<!-- Written by Claude; brought up to date with the built mod by Claude (2026-10-23, 2026-10-24, 2026-10-24 review) -->
# Empire: Foundations plan (bonfire, settlement basics, flat sacrifice, Talc)

**Date:** 2026-10-21, updated 2026-10-24 · **Status:** built, phases 0–6, then gods (§7) and members/protection/satiety (§8). After phase 6, the flat bread sacrifice was replaced by the gods and their tiered demands (§7). Rows marked **Superseded** or **Built** record what changed. Open items are in §9.
**Design source:** `docs/empire/empire-plan-brainstorming.md` §2.1–2.4 (Pass 1). The demand numbers are in `docs/empire/sacrifice-tables-draft.md`. Bright's decisions (below) override the brainstorming doc where they differ.
**Out of scope here:** civilians, outposts, forcible de-staking triggers (wait on Empire's own invasions), god boons (wait on civilians). Protection and god satiety were out of scope at first and are now built (§8).

---

## 1. Decisions (Bright, 2026-10-21)

| # | Decision |
|---|---|
| D1 | New mod. Folder `empire/`, mod id `empire`, package `dev.brights0ng.empire`, version 0.1.0, All Rights Reserved. |
| D2 | **Superseded 2026-10-22:** Frontier is **required**. It is still imported only in `empire.compat.frontier`; `TierChecks` keeps its always-pass default for the moment before the Frontier check is installed. (Was: optional, with Settled checks skipped without it.) |
| D3 | Bonfire = **one placed item** (a 3×3×3 structure). States: **unlit**, **lit**, **roaring**. Roaring lasts **30 s (600 ticks)** after the bonfire is lit, then drops to lit. Roaring is much brighter and louder. Placeholder visuals: unlit = unlit campfire model, lit = campfire model, roaring = soul campfire model. Real models come later (ChatGPT). |
| D4 | **Superseded 2026-10-22:** founding = **5 of one god's founding meat** (its tier 1 item) at a lit, unclaimed bonfire; the meat chooses the empire's god. Every later sacrifice pays the god's current **rotating demand** (§7). (Was: 5 bread, flat.) |
| D5 | Founding stakes the 3×3 chunks around the bonfire. Only the **bonfire's own chunk** must be Settled/Civilized; the 8 around it need not be. A bonfire **cannot be lit for the first time** if any of those 9 chunks is staked by another empire. |
| D6 | Bonfires can be broken: slow, **iron axe minimum**. When the main bonfire breaks, the empire keeps everything (Dominion, land), but **sacrifices and every other bonfire-dependent action are blocked**. If the empire has another bonfire, the **closest** one becomes main automatically. Otherwise, the **first bonfire placed afterwards** becomes main. |
| D7 | Staked chunks have **no effect** yet: no protection, no buffs. |
| D8 | Talc ore: ordinary ore generation normally; when Sparse Ores is loaded, it generates as a **Sparse Ores deposit** instead. |
| D9 | Membership: **founder only**. One player = at most one empire. |

Still in force from the brainstorming doc (§2.1–2.2):
- Founding gives **D = 10** and stakes 9 chunks, leaving 1 point available. Each completed demand gives **+5 D**.
- An empire holds at most D staked chunks. Staking claims the whole vertical column.
- **Blessed Talc:** right-click the (main, lit) bonfire with Raw Talc. Using Blessed Talc on a block in an unstaked chunk stakes that chunk, uses 1 point and consumes 1 Blessed Talc. Requirements:
  - the chunk is **cardinally adjacent** (N/S/E/W) to the empire's land;
  - the chunk is **Settled/Civilized** (at least one section in the column; skipped without Frontier);
  - the chunk is not staked by anyone.
  - If any requirement fails, **nothing is consumed** and the player gets an action-bar message.
- **Tarnished Talc:** right-click a Soul Campfire with Raw Talc. Using Tarnished Talc on a block in one of the empire's own chunks un-stakes it. It cannot be used on:
  - the **core chunk**;
  - a **bridge chunk** (one whose removal splits the land into islands).
- A voluntarily freed point goes **dormant until the next dawn**.
- "Forcible de-staking" (the point is lost and D − 1) is **not built now**. Leave a method stub, nothing calls it.

## 2. Provisional defaults (to be confirmed by Bright)

These are the gaps the decisions don't cover. Each is a **single named constant or isolated method** so changing one is a one-line edit. Implementers: build exactly these, and do not invent others. If you hit a gap not listed, stop and ask.

| # | Gap | Provisional default |
|---|---|---|
| P1 | How a bonfire is lit / put out | Vanilla campfire rules: flint & steel, fire charge or a burning projectile lights it; water, a splash water potion or a shovel puts it out. Rain does nothing. Every unlit → lit transition starts a 600-tick roar. |
| P2 | Light levels | Vanilla caps light at 15 and a lit campfire already emits 15, so "much brighter" needs lit < 15: **unlit 0, lit 11, roaring 15** (constants). Emitted by the main block only. |
| P3 | "Louder" | Roaring: the campfire crackle plays at higher volume and twice as often, plus an occasional `fire.ambient`. Constants only; Bright will tune. |
| P4 | Break speed | Hardness **50** (obsidian-like, ~12.5 s with an iron axe). Tags `minecraft:mineable/axe` + `minecraft:needs_iron_tool`. Without an axe of iron tier or better, break speed is **0** (`PlayerEvent.BreakSpeed`), so it is truly unbreakable, not just slow. Explosion resistance 1200. |
| P5 | Paying | **Superseded:** founding takes exactly 5 of the founding meat, from one stack in the hand used. A demand takes partial payments: each click pays as much of one unpaid line as the held stack (or fluid container) covers, and progress is saved on the empire (§7). |
| P6 | Which bonfire belongs to an empire | A bonfire belongs to the empire that has staked its chunk. Replacement main bonfires (D6) must be in the empire's land. A bonfire outside any empire is "unclaimed" and can found a new empire. |
| P7 | Bonfire-dependent actions | Sacrifice and blessing Raw Talc both need the empire's **main** bonfire, lit or roaring. **Staking with already-blessed Talc does not** need a bonfire. |
| P8 | Core chunk for Tarnished Talc | The chunk of the **current main bonfire**. With no main bonfire: the **founding chunk** (stored on the empire). |
| P9 | Who may act | Only the founder can sacrifice, bless or use Talc for their empire. At an unclaimed bonfire, only a player with no empire can found. Anyone can light, extinguish or break any bonfire (D7: no protection). Anyone can read a bonfire's demand with an empty hand. |
| P10 | Dimension | Empires exist in the **Overworld only** (Frontier only tracks the Overworld). Bonfires can be placed anywhere, but founding, sacrifice and Talc use fail outside the Overworld with a message. Only Overworld bonfires are added to the bonfire registry, so a bonfire in another dimension never becomes an empire's main bonfire (fixed 2026-10-23). |
| P11 | Placement rule | Placing a bonfire needs a clear 3×3×3 space (bottom-centre on the clicked face). The Settled check runs at **first lighting/founding**, not at placement. That way a bonfire placed early isn't wasted, and secondary bonfires in decayed land still place. |
| P12 | Obtaining a bonfire | **Built 2026-10-23** (`data/empire/recipe/bonfire.json`): shaped, top row empty, then `L C L` / `L B L` (L any log, C campfire, B coal block). |
| P13 | Raw Talc → Blessed/Tarnished | 1 : 1, instant, one item per right-click. Does not consume the bonfire's fuel (there is none). |
| P14 | Soul Fire | Fire blocks can't be clicked (they have no clickable shape), so only the Soul Campfire block works for tarnishing in v1. Possible later addition, waiting on Bright: a Create haunting recipe (Raw Talc → Tarnished Talc), data-only with a `neoforge:mod_loaded` condition. **Not built now.** |
| P15 | Common-ore numbers | Stone + deepslate talc ore. Vein size 9, 8 veins/chunk, uniform y 0–96, overworld `#minecraft:is_overworld`. **Placeholders**; Bright tunes. |
| P16 | Meteodyne heat | An entry for `empire:bonfire` filtered on `lit=true`. Values are **placeholders** (2× the campfire: `power` 2.0, `radiant` 0.6, `max_offset` 60). Roaring gets no extra heat yet: one data-map value per block. Since 2026-10-23 the bonfire is also in `#minecraft:campfires`, which Meteodyne's own `heat_sources` entry already covers with the same values. |
| P17 | Core mod relationship | Root `build.gradle` uses `loadMod(':empire')` (the core does not require Empire). No `/eae` redirect yet. |

## 3. Architecture

### 3.1 Placement in the build
- New Gradle project `empire/`, set up per `docs/PROJECT.md` "Adding a mod" (steps 1–6).
- `requireMod(':paradyne-lib')`.
- Frontier: `compileOnly project(':frontier')` + `testImplementation project(':frontier')`. **No** `requireMod`, so the bare run of Empire loads without Frontier. This is the same pattern `frontier/build.gradle` uses for Seismic.
- Import graph additions (update `docs/PROJECT.md` table + import rules, `ArchitectureTest` ROOTS/ALLOWED, `docs/history/mod-split-plan.md` §2):
  - `lib <- empire`
  - `frontier <- (gated package empire.compat.frontier only) empire`
  - Later, 5b: `sparse_ores <- (gated empire.compat.sparseores only) empire`
- Add Empire to the `coreless` combined run (it is a feature mod). Empire+Frontier interaction game tests live in `coreless`.

### 3.2 Packages (`dev.brights0ng.empire.`)
| Package | Contents | Minecraft imports? |
|---|---|---|
| `rules` | `EmpireState`, `ClaimGraph`, `SacrificeCost`/`FlatSacrificeCost`, `DawnClock`, `StakeResult`/`UnstakeResult` enums | **No.** Pure Java, unit-tested. |
| `data` | `EmpireSavedData` (Overworld `SavedData`), `EmpireRecord` (MC wrapper around `EmpireState`), `BonfireRegistry` | yes |
| `bonfire` | `BonfireBlock` (main), `BonfirePartBlock` (filler), `BonfireBlockEntity`, `BonfireShape` (offsets), `BonfireEvents` (break speed, break → registry) | yes |
| `sacrifice` | `Sacrifices` (right-click handling, founding) | yes |
| `talc` | `TalcItems`, `TalcInteractions` (bless, tarnish, stake, unstake) | yes |
| `tier` | `TierCheck` interface + `TierChecks` holder (default: always passes) | yes, no Frontier |
| `compat.frontier` | `FrontierTierCheck` (installed only after `ModList.isLoaded("frontier")`) | Frontier |
| `command` | `EmpireCommand` (`/empire …`) | yes |
| `gametest`, `datagen` | per convention | yes |

Naming: do **not** call anything `Settlement`. Frontier already has `incursion.Settlement` / `SettlementClusters`. Use `Empire*` / `Bonfire*`.

### 3.3 Pure rules (`rules`)
- **`EmpireState`**:
  - Fields: `dominion` (int, D = staked + available + dormant), `LongSet staked` (packed `ChunkPos.asLong`-style x/z; pack/unpack with a local helper, no MC import), `List<Dormant>` (each `availableOnDay` long), `foundingChunk` (long).
  - Methods: `available(day)` = D − |staked| − dormantNotYetDue(day); `releaseDueDormant(day)`; `found(long centerChunk)` (D = 10, stakes the 3×3); `addDominion(int)`; `stake(chunk, day, tierOk, ownedElsewhere) → StakeResult`; `unstake(chunk, day, coreChunk) → UnstakeResult`; `forciblyDestake(chunk)` (stub: removes the chunk, D − 1; unused).
  - Constants: `FOUNDING_DOMINION = 10`, `DOMINION_PER_SACRIFICE = 5`, `FOUNDING_RADIUS = 1`.
- **`StakeResult`**: `OK, NOT_ADJACENT, NOT_SETTLED, OWNED_BY_OTHER, ALREADY_OURS, NO_DOMINION`.
- **`UnstakeResult`**: `OK, NOT_OURS, CORE_CHUNK, BRIDGE_CHUNK`.
  Each result maps 1:1 to a lang key `message.empire.stake.<lowercase>` / `message.empire.unstake.<lowercase>`.
- **`ClaimGraph`**:
  - `isAdjacent(LongSet, chunk)` checks the 4 cardinal neighbours.
  - `isBridge(LongSet, chunk)`: remove the chunk, flood fill (4-neighbour) from any remaining chunk. It's a bridge if fewer than |set| − 1 chunks are reached. O(n), n ≤ D; fine.
- **Superseded:** the planned `SacrificeCost`/`FlatSacrificeCost` (5 bread) was replaced by `SacrificeCurve` (the tier quantities Q(D)) and `DemandRoller` (one option per active tier), with `DemandLine`/`DemandOption` as the data. See §7.
- **`DawnClock`**: `long dayOf(long dayTime) = Math.floorDiv(dayTime, 24000)`. A freed point gets `availableOnDay = dayOf(now) + 1`, so it is due once the world clock passes the next 0-tick (dawn). The world is frozen offline, so no ticking is needed: release lazily on every read.

Unit tests (JUnit, `empire/src/test/java/dev/brights0ng/empire/rules/`): `EmpireStateTest`, `ClaimGraphTest`, `DawnClockTest`, `SacrificeCurveTest`, `DemandRollerTest`, `OfferingKeysTest`. Cover:
- founding = 9 chunks, D 10, available 1;
- every StakeResult and UnstakeResult branch;
- the ring/line/T-shape bridge cases;
- dormant release exactly at the day boundary;
- available never negative.

### 3.4 Saved data (`data`)
`EmpireSavedData`: one per server, attached to the Overworld's `DimensionDataStorage`, file `empire_empires`. Holds:
- `Map<UUID, EmpireRecord>` (empire id → record);
- `Map<UUID, UUID>` (founder → empire);
- `Long2ObjectMap<UUID>` chunk → empire. Rebuilt from records on load and **not saved**. This is the "who owns this chunk" lookup;
- `BonfireRegistry`: the set of every bonfire main-block `BlockPos` in the Overworld. Added on place, removed on break. This lets "closest other bonfire" work with chunks unloaded.

`EmpireRecord`: `id`, `founder` UUID, `EmpireState`, `@Nullable BlockPos mainBonfire`, `long foundedGameTime`.

Codec/NBT with a `version` int (= 1). `setDirty()` on every change.

**Main bonfire selection (D6, P6):**
- On main bonfire removal: candidates = registry positions whose chunk the same empire owns. Pick the one with the smallest squared distance to the old main position (tie → lowest `BlockPos.asLong`). If there are none, set `mainBonfire = null`.
- On placing a bonfire in a chunk owned by an empire whose `mainBonfire == null`, it becomes main.
- Send the founder (if online) a chat message on every main change.

### 3.5 Bonfire block (`bonfire`)
- **Multiblock:** the main block (`empire:bonfire`) sits at **bottom-centre**, with 26 `empire:bonfire_part` filler blocks around it. The filler block entity is not needed. Each part's offset is derived from its position relative to the main, which is found by checking the 3×3×3 neighbourhood for the main block. Prefer an `offset` int property on the part (0–26), so the lookup is O(1).
- **Placement:** `BlockItem` placement fails unless all 27 positions are replaceable. Place the main block, then the parts.
- **Breaking:** breaking **any** part or the main removes all 27 and drops one `empire:bonfire` item (loot table on the main only; parts drop nothing).
- **Shapes:** each part has the full-cube collision for its slot. The main and parts all forward `useItemOn` / `useWithoutItem` to the main block.
- **States on the main block:** `lit` (boolean) + `roaring` (boolean, only true while lit).
  - Two booleans, not one 3-value property, so Meteodyne's data map can filter `lit=true` and match both lit and roaring (P16).
  - Models (placeholder, `datagen` blockstate): `lit=false` → `minecraft:block/campfire_off`; `lit=true,roaring=false` → `minecraft:block/campfire`; `roaring=true` → `minecraft:block/soul_campfire`.
  - Parts render `RenderShape.INVISIBLE`. Known placeholder look: a 1-block campfire inside an invisible 3×3×3 box.
- **`BonfireBlockEntity`** (main only):
  - `roarTicks` (int; server ticker counts down and sets `roaring=false` at 0);
  - `@Nullable UUID empire` (cached owner, refreshed on use; the authority is `EmpireSavedData`);
  - `boolean everLit`, used for D5 "first lit" (see below).
  - Client: campfire smoke/flame particles from the main block; roaring = more and larger particles.
- **Lighting (P1):** handle flint & steel / fire charge / burning projectile like `CampfireBlock`.
  - The bonfire is in `#minecraft:campfires` (2026-10-23). It has no waterlogged state, so water never puts it out by flowing. A splash water potion douses only the block it hits and that block's neighbours. The 26 parts enclose the core block, so in practice a potion never reaches the core and never puts the bonfire out. Whenever the bonfire is unlit, the block entity clears `roaring`.
  - **First-lighting gate (D5):** if the bonfire is not yet the main bonfire of a founded empire **and** its chunk is not staked by anyone:
    - check that none of the 9 chunks around it is owned by any empire (`OWNED_BY_OTHER` message);
    - check the Settled rule on its own chunk (`NOT_SETTLED` message, via `TierChecks`).
    - On failure: don't light, don't consume durability.
  - Bonfires inside an empire's own land skip this gate.
  - Every successful light: `lit=true, roaring=true, roarTicks=600`.
- **Breaking rule (P4):** `PlayerEvent.BreakSpeed` sets the speed to 0 unless the held item is in `#minecraft:axes` **and** passes `isCorrectToolForDrops` on a state tagged `needs_iron_tool`. Use that check, not a hard-coded item list, so modded axes work.
- **Registry hooks:** `onPlace` → `BonfireRegistry.add` + the main-assignment rule. `onRemove` (main block, not moving) → `BonfireRegistry.remove` + the main-reselection rule.
- **Frontier anchor:** data-only. `empire/src/main/resources/data/frontier/tags/block/settles.json` contains `{"values":[{"id":"empire:bonfire","required":false}]}`. Check `ArchitectureTest` only scans Java; if it also flags resources, stop and ask.
- **Meteodyne heat (P16):** data-only. `data/meteodyne/data_maps/block/heat_power.json` in Empire's resources, with `"neoforge:conditions":[{"type":"neoforge:mod_loaded","modid":"meteodyne"}]`. Data maps merge across packs with the same id, so this adds to Meteodyne's file instead of replacing it.
  - Meteodyne also has `heat_sources.json` (keys `change/range/max_effect/limit`). Check which of the two maps `HeatWorld` actually reads for campfires, and add the entry to each map campfires appear in.
  - Suggested `heat_sources` values: the same as `#minecraft:campfires` (placeholders).

### 3.6 Settled check (`tier`, `compat.frontier`)
- `TierCheck { boolean columnSettled(ServerLevel level, ChunkPos chunk); }`. The default implementation returns true.
- In the mod constructor / common setup: `if (ModList.get().isLoaded("frontier")) TierChecks.install(FrontierTierCheck::create)`. This keeps the Frontier class out of every always-loaded class signature.
- `FrontierTierCheck`: for each section Y from `level.getMinSection()` to `getMaxSection() - 1`, call `FrontierLevels.tierAt(level, sectionCentre)`. Return true on the first one with `atLeast(Tier.SETTLED)`. (About 24 lookups per click; only on clicks, never per tick.) No Frontier code changes.

### 3.7 Sacrifice (`sacrifice`)
**Superseded in part by §7.** Right-click the main block (or any part) with an offering. Order of checks, each with an action-bar message and **no consumption on failure**:
1. Overworld (P10).
2. The bonfire is lit or roaring.
3. Resolve the bonfire's owner:
   - **Unclaimed bonfire:** the player must have no empire (P9). Re-run the D5 checks: 9 chunks free and own chunk Settled. This repeats the first-lighting gate because another empire could have claimed land since the bonfire was lit.
   - **Claimed bonfire:** the player must be its founder, and it must be that empire's **main** bonfire (P7).
4. Unclaimed: the held stack is ≥ 5 of one god's founding meat, fresh enough for that god. Claimed: the held stack (or fluid container) matches an unpaid line of the current demand.
5. Consume the items.
   - Unclaimed: create the empire under that god, `found(chunk)`, set this bonfire as main, rebuild the chunk index entries, roll the first demand.
   - Claimed: pay towards the line. When every line is paid, `addDominion(5)`, count the sacrifice and roll the next demand.
6. Feedback: sound + particles + action bar "Dominion: D (available A)".

Client side: the client knows neither the gods nor any empire's demand. The server sends every client the item, tag and fluid ids any god could take (`GodOfferingsNetwork`, on login and after `/reload`). The client claims a click on a lit Overworld bonfire only for such an item, and lets any other item do what it normally would.

### 3.8 Talc (`talc`)
- **Ids (public, final once committed):** `empire:talc_ore`, `empire:deepslate_talc_ore`, `empire:raw_talc`, `empire:blessed_talc`, `empire:tarnished_talc`, `empire:bonfire`, `empire:bonfire_part`. Placeholder textures are fine; flag them for ChatGPT.
- **Ore blocks:** stone/deepslate ore properties, `mineable/pickaxe`, `needs_stone_tool` (placeholder), dropping 1 Raw Talc (fortune applies; normal ore loot table via datagen).
- **Blessing (P7, P13):** Raw Talc on the main bonfire (lit/roaring, founder only) → 1 Raw Talc becomes 1 Blessed Talc (to inventory, or dropped if full).
- **Tarnishing (P13, P14):** Raw Talc on a lit `minecraft:soul_campfire` → 1 Tarnished Talc. Anyone can do this; no empire needed. Do not touch the campfire's cooking slots: intercept in `PlayerInteractEvent.RightClickBlock` and cancel.
- **Staking:** Blessed Talc used on any block.
  - Resolve the player's empire (none → `message.empire.no_empire`), check the Overworld, then `EmpireState.stake(...)`.
  - Inputs: `tierOk = TierChecks.columnSettled(...)`, `ownedElsewhere` from the chunk index.
  - On OK: shrink 1, update the index, success sound + particles at the chunk's corners.
- **Un-staking:** Tarnished Talc used on any block. `unstake(chunk, day, coreChunk(P8))` → on OK: shrink 1, update the index, the point goes dormant until the next dawn.
- **Worldgen (5a):** `configured_feature` + `placed_feature` (P15) + a biome modifier `empire:add_talc_ore` whose conditions are `[{"type":"neoforge:not","value":{"type":"neoforge:mod_loaded","modid":"sparse_ores"}}]`. So with Sparse Ores loaded the ordinary ore never generates (D8).

### 3.9 Commands (`/empire`)
| Command | Who | Does |
|---|---|---|
| `/empire info [player]` | all | Founder, D, available, dormant (with due day), staked count, main bonfire position, founding chunk |
| `/empire here` | all | Owner of the current chunk, whether it is adjacent/bridge/core for the caller's empire, Settled result |
| `/empire debug dominion <player> <set\|add> <n>` | op 2 | for tests |
| `/empire debug stake <player>` / `unstake <player>` | op 2 | current chunk, **runs the real rules** (so it's testable), prints the result enum |
| `/empire debug disband <player>` | op 2 | deletes the empire and frees its chunks |

## 4. Phases
Each phase = one or more commits, ending green on the targeted tests. **Stop for Bright's review after each phase.**

| Phase | Deliverable | Proof |
|---|---|---|
| **0 Scaffold** | `empire/` project, entry class `Empire` (`MODID`, `LOGGER`), toml, settings/root build wiring (P17), ArchitectureTest + PROJECT.md + plan §2 updates, coreless inclusion, empty game test structure via lib's `GameTestStructures` | `:empire:compileJava`, `:empire:runGameTestServer` (0 tests OK), `ArchitectureTest` |
| **1 Rules** | `rules` package + unit tests (§3.3) | `:empire:test` |
| **2 Data + commands** | `EmpireSavedData`, `EmpireRecord`, `BonfireRegistry`, chunk index, `/empire` commands (§3.4, §3.9) | Game tests: save/load round trip; debug stake/unstake obey rules; disband frees chunks |
| **3 Bonfire** | blocks, BE, states, roar timer, lighting + first-lighting gate, break rule, registry + main selection, Frontier tag + Meteodyne data (§3.5–3.6) | Game tests: place needs clear 3×3×3; breaking a part removes all 27 and drops 1; lit → roaring → lit after 600 ticks; no break progress without an iron axe; first lighting refused if a neighbour chunk is owned; main reselection picks the closest bonfire; null main + new bonfire in land → main |
| **4 Sacrifice** | §3.7 (built as 5 bread, then replaced by §7) | Game tests: founding = D 10 / 9 chunks / available 1; second sacrifice D 15; refused when unlit / non-founder / not main / not enough (nothing consumed); founder can't found twice |
| **5a Talc** | items, ores, worldgen (conditional), bless/tarnish/stake/unstake (§3.8) | Game tests: every StakeResult/UnstakeResult end to end; dormant point unavailable until the day rolls over (set `dayTime`); blessing needs the lit main bonfire; tarnishing at a soul campfire leaves its cooking slots untouched. Coreless/aggregate: Frontier Settled check blocks staking/founding in Uninhabited land and passes in Settled land |
| **5b Talc as a Sparse Ore** | `SparseOresApi.registerOre` dynamic hook (Option A), `SparseOresCompat` in `empire.compat.sparseores` (S=192, SeamShape.COAL, size 350, depth -48..96, rich 0.15), distinct celadon glow in Seismic Surveyance | `:sparse-ores:test`, `:empire:test`, `:seismic-surveyance:test`, `:coreless:runGameTestServer` (`EmpireSparseOresGameTests`: 4 tests) |
| **6 Claim overlay** | Claim overlay on Seismic maps (`ClaimOverlay`), pure grid/request rules (`ClaimGrid`, `ClaimRequests`), client cache (`ClaimMapCache`), network sync (`ClaimMapPayloads`, `ClaimMapNetwork`), gated compat (`EmpireSeismicClient`) | `:empire:test` (`ClaimGridTest`, `ClaimRequestsTest`), `:coreless:runGameTestServer` (`EmpireSeismicGameTests`: 2 tests) |

Finish: full check once (`./gradlew compileJava compileTestJava test runGameTestServer`) and report the new counts against the baseline in `docs/PROJECT.md`.

## 5. Talc under Sparse Ores (phase 5b) — Resolved (Option A)
Implemented via Option A:
- `SparseOresApi.registerOre(OreType, String stoneBlockId, String deepslateBlockId)` provides thread-safe dynamic registration before worldgen.
- `empire.compat.sparseores.SparseOresCompat` registers talc during Empire mod construction if `sparse_ores` is loaded:
  - Shape: `SeamShape.COAL`
  - Spacing $S = 192$
  - Size: median 350, min 100, max 500, sigma 0.35
  - Depth: minY -48, maxY 96
  - Rich share: 0.15
  - Blocks: `empire:talc_ore`, `empire:deepslate_talc_ore`
- Seismic Surveyance gives talc deposits a distinct celadon green glow (`0x8CBDB0`).
- Gated import edge `sparse_ores <- empire` permitted strictly through `dev.brights0ng.empire.compat.sparseores`.

## 6. Claim Overlay (phase 6) — Completed
Implemented following the Frontier `TierOverlay` pattern:
- **Presentation**: `ClaimOverlay` (`dev.brights0ng.empire.compat.seismic.ClaimOverlay`) implements `MapLayer`:
  - Amber fill (`0xF5A623`) with perimeter outline for own empire claims.
  - Crimson fill (`0xDC3545`) with perimeter outline for other empires' claims.
  - Flame gold/orange diamond marker (`0xFFFFA500`) with soot outline (`0xFF261204`) for main bonfires.
  - Legend swatches: *Empire Territory*, *Foreign Claim*, *Main Bonfire*.
- **Pure Rules**: `ClaimGrid` (2-bit packing/unpacking, edge detection) and `ClaimRequests` (10s refresh interval, 500ms min gap debouncing).
- **Network Sync**: `ClaimMapPayloads` (`Request` and `Answer`) and `ClaimMapNetwork`.
- **Gated Architecture**: Gated package `dev.brights0ng.empire.compat.seismic` registered dynamically on client boot if `seismic_surveyance` is loaded. Empire functions completely standalone without Seismic Surveyance.

## 7. Gods and tiered demands — Completed (2026-10-22)
Replaces the flat 5-bread sacrifice (D4, P5, §3.3, §3.7). The numbers and item choices are in `docs/empire/sacrifice-tables-draft.md`.
- **Gods as data:** `data/<namespace>/empire/god/<name>.json`, loaded by `god.Gods` (a reload listener) and parsed by `GodDefinition`. Each god has options for tiers 1–5, each with a multiplier 0–1, plus `accepts_rotting_meat`. The nine gods ship in Empire's own data. Lang key: `god.<namespace>.<name>`.
- **Pure rules:** `SacrificeCurve` gives Q per tier at a Dominion D. `DemandRoller` picks one option per active tier, seeded by (empire id, sacrifice count, tier). The count is `max(1, ceil(Q·m))`, and an option needs Q·m ≥ 0.5 to be picked at random.
- **Saved on the empire:** `god`, `sacrifice_count`, `demand` (each line's kind, ids, required and paid). Records saved before gods existed load godless; 5 of a god's founding meat at the main bonfire gives them that god ("adoption").
- **Meat freshness:** with Simple Spoilage (`empire.compat.simplespoilage`), founding meat and demanded founding meat must be fresh or ripe. The Carrion Crow takes rotten flesh and any raw meat at the Rotting stage.
- **Fluids** (T5 fuels) are counted in 1000 mB units and drained from the held container through its fluid-handler capability.
- **`uniqueGodsPerServer`** (server config, default `false`): a god another empire already has is refused at founding and adoption.
- **Levitite:** Empire ships loot tables for `aeronautics:levitite` and `aeronautics:pearlescent_levitite`. The block drops itself only to a Silk Touch pickaxe that passes `isCorrectToolForDrops` on a `needs_diamond_tool` state.
- **Still to design:** god boons. Members, protection and satiety are built (§8).

## 8. Members, main bonfire, protection, satiety — Built (2026-10-23/24)
Supersedes D7 (no protection), D9 (founder only) and P9 (who may act).
- **Members:** every member has equal control. A player with no empire joins by offering 5 of the god's tier-1 meat at the lit main bonfire. A member leaves by offering 5 Tarnished Talc at any of the empire's bonfires twice: clicks in the first 3 s after the warning are ignored, a click from 3 s to 30 s confirms, and later clicks warn again. The last member leaving leaves the empire **frozen** (land, Dominion, god kept) for anyone to join.
- **Main bonfire:** a bonfire can be main only when all 9 chunks of the 3×3 around it are staked by its empire. With no main, a qualifying bonfire becomes main automatically when it is placed, when a chunk staking completes its 3×3, or when a member uses it (the closest to the core chunk wins). The refusal message gives the main bonfire's position, or "N of 9 staked here".
- **Only the main bonfire burns:** while an empire has a main bonfire, its other bonfires cannot be lit. A lit one goes out (checked every 40 ticks).
- **Protection (divine land):** only an empire **with a main bonfire** has protected land: the 3×3 around the main bonfire, full column. Outsiders cannot break, place, use blocks, use buckets, touch item frames or armour stands, or trample farmland there. Explosions break no blocks there. Outsiders may read the demand, offer meat to join, and, at a frozen empire, light the main bonfire. Without a main bonfire nothing is protected, so outsiders can place a bonfire anywhere in a frozen empire's land. Only members can break an empire's bonfires.
- **Unstaking:** with a main bonfire, its 3×3 cannot be unstaked. Without one, only the core chunk (the last main bonfire's chunk, saved as `core_chunk`) cannot be.
- **Golden outline:** while Blessed or Tarnished Talc is held, protected land is outlined in gold. The gold replaces the claim border where the two meet. The client works the protected land out from the main-bonfire markers in its claim cache.
- **Satiety:** see the god-hunger rules in `GodSatiety`. The god is fed by founding, joining, adoption, a completed demand, or 5 of its meat in one click. From 75 % of one in-game year of member-online time the god is hungry and puts out the main bonfire every 15 s; at 100 % it leaves.
- **Client prediction (2026-10-24):** the server decides everything; the client only predicts refusals, so an outsider no longer sees break progress, a placed block, an opened block or moved fluid that the server then undoes.
  - `ClaimCacheKeeper` (client) keeps the claim cache fresh in the Overworld, radius `ClaimMapNetwork.AROUND_PLAYER_RADIUS` = 7 chunks around the player, every 10 s and on moving. The talc borders read the same cache and no longer ask on their own. The cache is cleared on leaving a server.
  - The server pushes fresh claims (`ClaimMapNetwork.pushNear`, players within 16 chunks) when protected land appears or goes: founding, a main bonfire set or lost, disbanding. Joining or leaving pushes to that player (their side of every claim changes).
  - `DivineProtection` resolves "outsider" on the client from `ClaimMapCache.protection` (and, for bonfires anywhere, the claim of the main block's chunk). Right clicks, bucket use and entity interactions still send their packet, so a stale cache costs only the prediction. Breaking is the exception: a stale "protected" can hold up breaking until the next refresh (≤ 10 s). Nothing is predicted at a bonfire (joining, reading and lighting are the server's call).

## 9. Open items (review, 2026-10-24)
| # | Item | Status |
|---|---|---|
| O1 | Create Big Guns explosions in protected land | **Unverified.** Protection filters only `ExplosionEvent.Detonate`. Add a game test that detonates a CBG shell in divine land; if it bypasses the event, Bright decides whether that is intended (like Create machines). |
| O2 | Main bonfire reselection uses two distance rules | `onBonfireRemoved`: 3D distance to the old main; `tryAssignMain`: 2D distance to the core chunk centre. Usually the same pick; fold into one method. |
| O3 | `EmpireState.forciblyDestake` | Stub, unused. Waits on Empire's invasion mechanics (Bright, 2026-10-24). |
| O4 | Haunting recipe Raw Talc → Tarnished Talc (P14) | Waiting on Bright. |
| O5 | Placeholders | Light levels (P2), roar sounds (P3), common talc ore numbers (P15), Meteodyne heat (P16). Bright tunes. |
| O6 | Client prediction | Not covered by game tests (server only). Check by hand with two clients. |

**Next track (Bright, 2026-10-24):** civilians. God boons wait on civilians (that is where boons act); forcible de-staking waits on invasions; outposts come much later. Chunk loading direction: `docs/empire/empire-plan-brainstorming.md` §5, Pass 4.
