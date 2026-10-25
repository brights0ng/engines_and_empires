<!-- Written by Claude (2026-10-24) from Bright's decisions in this session; updated by Claude (2026-10-25) with Bright's answers and the phase 0, 1 and 2 results. -->
# Empire: Civilian chunk loading and catch-up plan

**Date:** 2026-10-24, updated 2026-10-25 · **Status:** phases 0, 1 and 2 built and green (branch `civilian-chunk-loading`, uncommitted), waiting for Bright's review. Moving civilians back (D12) waits for the civilian work AI.
**Design source:** `docs/empire/empire-plan-brainstorming.md` §5, Pass 4 (Bright's decisions, 2026-10-24), and Bright's answers of 2026-10-25. Bright's decisions override this doc where they differ.
**Out of scope:** civilian AI, low-LOD simulation, outposts, civilians on moving vehicles, Frontier's side of "civilians count as habitation".

---

## 1. Decisions (Bright)

| # | Decision |
|---|---|
| D1 | **Option 3 with catch-up, without keeping land in memory.** Civilians keep the chunks around themselves running; all other empire land unloads normally. Growth an unloaded empire chunk missed is caught up when it runs again. Option 1 (all land loaded) is out: late-game empires reach about **200 Dominion**, mostly farms. Option 2 (block ticks everywhere) is out on performance. |
| D2 | Offline loading is a **civilian** perk, not an empire-wide one. |
| D3 | An empire keeps working while none of its members is online (its god's hunger stays paused). Intended: it keeps members who play less often from falling far behind. |
| D4 | Civilians **count as habitation** for Frontier. |
| D5 | Civilians load **empire chunks only**. A civilian outside the empire with no player near is moved back to the empire. Civilians don't work outside empire land for now. |
| D6 | Loading spreads one chunk past the border (block updates only, no entities) when a civilian stands in an edge chunk. **Accepted.** Phase 0 shows that ring gets no random ticks, so no crops grow outside the border. |
| D7 | Catch-up: **vanilla saplings are left out.** Dynamic Trees grows only when the chunks around it are running (§3.1). |
| D8 | A server config enables civilian loading, or limits it to empires with a member online. |
| D9 | (2026-10-25, was P7) Catch-up covers the empire's **chunks with crops or Dynamic Trees**: the two block tags of §3.1, in any staked chunk. |
| D10 | (2026-10-25) A **frozen** (memberless) empire loads nothing. |
| D11 | (2026-10-25) An empire with members loads chunks for **5 days of server time** (8 640 000 ticks, config `civilianLoadingInactiveTicks`) after its last member was online. Past that, its civilians load nothing until a member logs in again. Time the server is off does not count (Bright, 2026-10-25, replacing "real-life days"). |
| D12 | (2026-10-25, was Q1) Moving civilians back keeps the **anchor ticket + teleport to the nearest staked chunk** (§3.2), so a civilian whose work is far from home is not sent all the way home. **Deferred to the civilian work AI.** Note for the AI: a civilian that is genuinely stuck may, as a last resort, ask to be teleported home. |
| D13 | (2026-10-25, was Q2) A frozen empire's growth clock **keeps running**: its crop land catches up whenever someone is near it. |
| D14 | (2026-10-25, was Q4) The chunks civilians load come from the **civilian records** (`lastChunk`), and NeoForge's saved tickets are not used. |

## 2. How Minecraft loads chunks (the facts this rests on)

| Ticket level | In the chunk |
|---|---|
| ≤ 31 | Everything runs, entities included |
| 32 | Block updates and block entities run; entities are frozen |
| 33 | Kept in memory; nothing runs |

- A chunk at level L holds its neighbours at L + 1 or lower. A 3×3 at 31 gives a 5×5 at ≤ 32 and a 7×7 in memory.
- **Random ticks** (crop growth) run only in an **entity-ticking** chunk (level ≤ 31) that has a non-spectator player within 128 blocks of its centre, or that a NeoForge forced ticket marked *ticking* holds. **Verified by phase 0** (2026-10-25). A level-32 chunk gets no random ticks, even next to a ticking ticket.
- Natural spawning refuses any mob farther than 128 blocks from the nearest player, so an offline empire gets no new hostile mobs. Mobs already there keep running. Non-persistent mobs more than 128 blocks from a player are removed while any player is in the dimension. Civilians are persistent (`setPersistenceRequired`, `removeWhenFarAway` false).
- Dynamic Trees (1.21.1 source, `release/1.21.1`):
  - Rooty soil (`SoilBlock.randomTick`) grows its tree one step per tick, scaled by DT's `treeGrowthMultiplier`.
  - DT saplings (`DynamicSaplingBlock.randomTick`) turn into a tree.
  - Each growth tick first checks `ChunkTreeHelper.isSurroundedByLoadedChunks`: all 8 blocks around the soil must be in entity-ticking chunks. Otherwise the tick does nothing.
  - DT ships the block tags `#dynamictrees:rooty_soil` and `#dynamictrees:saplings`.

## 3. Design

### 3.1 Catch-up (no civilians needed)
**Built in phase 1** (`growth.GrowthCatchUp`, `growth.GrowthConditions`, `rules.CatchUpMath`, `rules.GrowthClock`).

Every staked chunk keeps **one** timestamp in `EmpireSavedData` (`growthClock()`): the last game time it was **growing**.
- **Growing** (`GrowthConditions.randomTicks`): the chunk is entity-ticking (`ServerLevel.isNaturalSpawningAllowed(ChunkPos)`), and either a ticking forced ticket holds it (`DistanceManager.shouldForceTicks`) or a non-spectator player is within 128 blocks of its centre (`ChunkMap.getPlayersCloseForSpawning` is not empty). This copies vanilla's rule exactly, with public methods only (no access transformer).
  - It counts *any* mod's ticking ticket, not only Empire's, so a chunk that grew on its own is never paid twice.
  - Corrected 2026-10-25: the rule used to say `shouldTickBlocksAt`, which is level 32 and gets no random ticks.
- **Changed 2026-10-25 (Claude): no second "surrounded" clock.** Dynamic Trees' check (`ChunkTreeHelper.isSurroundedByLoadedChunks`, read in the 1.21.1 source) is **per block**: the 8 *blocks* around the soil must be entity-ticking, not the 8 neighbouring chunks.
  - A per-chunk "surrounded" clock would be stricter than DT. Trees in the middle of an edge chunk (one beside a level-32 ring, as around civilians) grow for real while that clock lags, and would be paid twice later.
  - Instead, DT blocks use the same clock as crops. DT's own check runs inside its `randomTick` during the catch-up and refuses a soil whose neighbours aren't running.
  - The cost: a soil on the rim of an edge chunk can lose a little growth. It never gains extra.

Every `SCAN_TICKS` the scanner checks each staked chunk of every empire:
- **Growing now, with a gap since it last grew:** queue a catch-up. The gap is the time between the two sightings less one `SCAN_TICKS`, since the chunk stopped and started again somewhere within an interval.
- **Growing now:** set the timestamp to now.
- **No timestamp yet** (newly staked, or a save from before version 4): the first sighting sets it and pays nothing.

A queued chunk is processed within a per-tick time budget:
- For each section that may hold a tagged block (`LevelChunkSection.maybeHas`), find each tagged block. A chunk with no tagged blocks costs only that check (D9).
- Call its own `randomTick` *n* times. *n* is drawn from a Poisson distribution with λ = gap × `randomTickSpeed` / 4096 (vanilla's chance per block), capped.
- A block that changed into something untagged stops early.
- A chunk that stops running before its catch-up starts gets its old timestamp back, so the gap is paid the next time it runs. One that stops halfway keeps what it got.
- Calling the block's own tick keeps modded crops, Farm & Charm, seasons and temperature in charge of the result. They see the conditions at catch-up time, not the conditions during the gap; that is an accepted approximation.
- Blocks a growing plant adds during the pass (a new sugar cane or cactus block above) are caught up in the same pass if the scan has not passed them yet. The plant's own height limit still applies.

**Block tags:**

| Tag | Contents | Cap per block |
|---|---|---|
| `empire:catches_up` | `#minecraft:crops` (includes the stems and all of Farm & Charm's crops except one), sweet berry bush, cocoa, sugar cane, cactus, bamboo, bamboo sapling, nether wart, `farm_and_charm:tomato_crop_body` (`required: false`; the only Farm & Charm crop missing from `#minecraft:crops`) | 64 |
| `empire:catches_up_trees` (renamed from `catches_up_surrounded`) | `#dynamictrees:rooty_soil`, `#dynamictrees:saplings` (both `required: false`) | 32 |

Vanilla saplings are in neither (D7).

**Lifecycle:**
- Founding and staking clear the chunk's timestamp, so staking never pays out growth for time before the claim.
- Unstaking removes the chunk's timestamps.
- If the empire is disbanded, its timestamps go with it.
- Saved in `EmpireSavedData` as `growth` (`chunks`, `times`), save version 4. Loading drops entries for chunks no one has staked.

### 3.2 Civilian loading (phase 2)
**Built in phase 2** (`civilian.CivilianChunkLoading`, `rules.LoadPlan`).
- **Tickets:** a NeoForge `TicketController` with id `empire:civilians` (registered from `Empire`). Tickets are owned by the **empire** UUID and are *ticking* tickets, so random ticks run (verified by phase 0).
- **Wanted set:** each empire wants the staked chunks among the 3×3 around each citizen's `CivilianRecord.lastChunk()` (D5, D14). Only staked chunks get tickets.
  - The record already holds the last chunk: `CivilianEntity.trackChunk` writes it on every chunk change. So this needs no new data and works before entities load.
  - The validation callback drops every saved `empire:civilians` ticket. The first recompute after a start rebuilds the set from the records, so a restart needs nothing special.
  - Travellers have no record and load nothing.
- **Who loads (D8, D10, D11):** an empire loads only if all of these hold:
  - the mode allows it;
  - it has members (not frozen);
  - a member is online, or (mode `ALWAYS`) was within `civilianLoadingInactiveTicks` of **game time**.

  "Last online" is a new `EmpireRecord` field, `last_member_online`: a game time, set at founding and at every recompute while a member is online. Records from before it start their count at the first recompute.
- **Removal:** a ticket goes at once when the empire may not load, its chunk is no longer staked, or the empire is disbanded. Otherwise it goes once its chunk has been unwanted for `KEEP_TICKS`.
- **Debug:** `/empire debug civilian roster` ends with `Chunk loading: on/off, N chunks held`.
- **Recompute** every `LOAD_TICKS`. A chunk no longer wanted keeps its ticket until it has been unwanted for `KEEP_TICKS`, so civilians walking back and forth across a chunk border don't reload it.
- **Config** `civilianLoading`, one of:
  - `ALWAYS` (still subject to D10 and D11);
  - `MEMBER_ONLINE`: only empires with a member online;
  - `OFF`: civilians freeze with their chunks.
- **Moving civilians back (D5, D12):** decided as below; **built with the civilian work AI, not in phase 2.**
  - While a civilian is outside empire land, it holds a memory-only anchor ticket on its own chunk (a custom `TicketType`, level 33). The chunk can't unload under it, and nothing there runs.
  - Every `LOAD_TICKS`, a civilian that is outside with no non-spectator player within 128 blocks is teleported to the nearest staked chunk: the surface at the point of that chunk closest to the civilian. Its anchor is released.
  - After a restart, a registry entry outside the empire briefly loads that chunk to move the civilian home.
  - Last resort (AI): a civilian that is genuinely stuck may ask to be teleported home.
- **Habitation (D4):** Frontier counts civilians as habitation. That change lives in Frontier and is specified with the civilian entity.

**Footprint today:** citizens stay within `HOME_RADIUS` (16) of the main bonfire, which never leaves the bonfire's staked 3×3. With `PIONEER_LIMIT` 3, civilian loading keeps the divine 3×3 (plus its level-32 ring) running. More land loads only once civilian work AI sends civilians out.

### 3.3 Pure rules (`rules`, no Minecraft imports, unit-tested)
- `CatchUpMath`: ticks to run for a gap (Poisson draw from an injected uniform source, cap applied, λ = 0 → 0).
- `GrowthClock`: per-chunk timestamps; returns the gap to catch up and updates the timestamp.
- `LoadPlan`: wanted set (civilian chunks → staked 3×3s), keep-delay bookkeeping (adds, removals due), mode filter, the frozen and 5-day gates.

## 4. Provisional defaults (to be confirmed by Bright)
Each is one constant. Build exactly these; stop and ask about any gap not listed.

| # | Gap | Provisional default |
|---|---|---|
| P1 | `SCAN_TICKS` (growth scan) | 20 |
| P2 | Catch-up budget per server tick | 2 ms (checked with `System.nanoTime` between blocks) |
| P3 | Cap on ticks per block | 64 for `catches_up`; 32 for `catches_up_surrounded` (DT growth steps are heavier) |
| P4 | `LOAD_TICKS` (ticket recompute, move-back check) | 20 |
| P5 | `KEEP_TICKS` | 600 (30 s) |
| P6 | `civilianLoading` default | `ALWAYS` |
| P8 | Inactivity limit (D11) | 5 days of server time = 8 640 000 ticks; server config `civilians.civilianLoadingInactiveTicks` |

(P7 became D9.)

## 5. Phases
Each phase ends green on targeted tests. **Stop for Bright's review after each phase.**

| Phase | Deliverable | Proof |
|---|---|---|
| **0 Verify** ✅ | `gametest/ChunkLoadingGameTests.tickingTicketRandomTicksOnlyItsChunk` (batch `chunkloading`), plus a test-only controller `empire:gametest` whose saved tickets are dropped on load. | **Done 2026-10-25.** `randomTickSpeed` 2048 for 200 ticks, no players: wheat under a ticking forced ticket reached age 5–7; wheat under a non-ticking forced ticket (also level 31) and in the level-32 ring next to the ticking chunk stayed at 0. Green on 4 runs of `:empire:runGameTestServer` (84/84). |
| **1 Catch-up** ✅ | **Done 2026-10-25.** `rules.CatchUpMath`, `rules.GrowthClock`, `growth.GrowthConditions`, `growth.GrowthCatchUp` (scan, budgeted queue), the clock on `EmpireSavedData` (save version 4), tags `empire:catches_up` and `empire:catches_up_trees`. | **Unit tests** (`CatchUpMathTest`, `GrowthClockTest`, 11 tests): λ; zero; cap; inversion values; mean ≈ λ; first sighting, continuous growth, gap, forget, restore. **Game tests** (`GrowthCatchUpGameTests`, batch `catchup`):<br>• a 200 000-tick gap brings wheat to full age;<br>• an untagged vanilla sapling is not touched;<br>• wheat in a staked level-32 chunk is untouched, and its clock unchanged;<br>• the first sighting pays nothing;<br>• staking, unstaking, founding and disbanding clear times.<br>`ChunkLoadingGameTests` also checks `GrowthConditions` against the three phase 0 sites. Full check green: every module's unit tests and all 10 game test servers (Empire 87/87). The "surrounded" test was dropped with the second clock. |
| **2 Civilian loading** ✅ | **Done 2026-10-25.** `rules.LoadPlan` (gates, wanted set, keep-delay bookkeeping), `civilian.CivilianChunkLoading` (controller, recompute every `LOAD_TICKS`, rebuild from records), `EmpireRecord.lastMemberOnline`, config section `civilians` (`civilianLoading`, `civilianLoadingInactiveTicks`), the roster debug line. Moving civilians back is deferred (D12). | **Unit tests** (`LoadPlanTest`, 9 tests): the 5-day constant; OFF and frozen; MEMBER_ONLINE; the 5-day edge; wanted set (unstaked excluded, outside → nothing, overlaps); keep delay; wanted again resets it; a closed gate or an unstaked chunk drops at once; clear. **Game tests** (`CivilianLoadingGameTests`, batches `civilianloading` and `civilianloading_off`):<br>• tickets are the staked 6 of an edge civilian's 3×3, and a ticking ticket really holds them;<br>• a newly staked chunk joins;<br>• disbanding releases all;<br>• an unwanted chunk is kept ≥ `KEEP_TICKS`, then dropped;<br>• frozen → none; over 5 server days → none, within → 9;<br>• OFF and MEMBER_ONLINE (no member online) → none.<br>Empire 92/92. |

Finish: full check once (`./gradlew compileJava compileTestJava test runGameTestServer`), then commit.

**Also fixed during phase 0:** `CivilianRosterGameTests.found` founded the test empire on the test origin's chunk. Game tests land at random positions, so an origin at a chunk edge put the bonfire in the next chunk, and `homeFollowsMainBonfire` failed now and then. It now founds on the bonfire's chunk.

## 6. Open questions
- ~~Q1 (moving civilians back)~~: answered, D12.
- ~~Q2~~ answered, D13. ~~Q3~~ answered, D11 (server time). ~~Q4~~ answered, D14.
- **Not built (out of scope here):** moving civilians back (D12, with the work AI); Frontier counting civilians as habitation (D4, in Frontier).
- **Untested by game tests:** a real restart. The drop-on-load callback and the rebuild on the first recompute are simple, but they are only covered by reading the code. Check in a client: civilians, a save and reload, then the roster debug line.
- **Watch later (no change now):**
  - Hostile mobs already in a civilian-loaded chunk keep running with no player near. They don't despawn while no player is in the dimension. Vanilla mobs don't target `CivilianEntity`, but if raiders or modded mobs ever do, civilians (whose death is permanent) can die offline.
  - Civilian-loaded chunks also gain "inhabited time", so their local difficulty rises as if a player were there.
- **Full check 2026-10-25:** `compileJava compileTestJava test runGameTestServer`, BUILD SUCCESSFUL. All 10 game test servers pass. `:sparse-ores:runGameTestServerWithCreate` still fails to start (missing GeckoLib, already broken before this work).
- **Pre-existing, unrelated:** `:sparse-ores:runGameTestServerWithCreate` fails to start (`paradyne_lib requires geckolib`). The build still passes, because that task's failure doesn't fail it.
